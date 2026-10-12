package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.QuotaState
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 可恢復的 onboarding state machine（PROVIDER_ONBOARDING_CONTRACT §3）。
 *
 * 本類不碰網路、不碰 Room、不碰 UI：真實行為全由 [WorkflowStore] / [StepExecutor] / [Committer] 注入。
 * 同一實例以 [gate] 保證 single-flight：同時只有一個探測在跑，後到的呼叫沿用同一份已存快照、不開第二份探測。
 */
class OnboardingWorkflow(
    private val store: WorkflowStore,
    private val executor: StepExecutor,
    private val committer: Committer,
    private val clock: OnboardingClock = SystemOnboardingClock,
    private val budget: ProbeBudget = ProbeBudget.DEFAULT,
) {
    private val gate = Mutex()

    suspend fun load(workflowId: String): OnboardingSnapshot? = store.load(workflowId)

    /** Review 改名與選取模型不重跑探測；既有 catalog/evidence 保留，不偽造驗證。 */
    suspend fun editReview(
        workflowId: String,
        providerName: String?,
        selectedModelIds: List<String>,
        manualModelId: String? = null,
    ): OnboardingSnapshot = gate.withLock {
        val current = requireSnapshot(workflowId)
        check(current.status !in setOf(OnboardingStatus.COMMITTED, OnboardingStatus.ABANDONED))
        val manual = (current.manualModelIds + listOfNotNull(manualModelId?.trim()?.takeIf { it.isNotEmpty() })).distinct()
        val eligible = (manual + current.catalog?.models.orEmpty().map { it.modelId } + current.suggestedModelIds).toSet()
        val selected = selectedModelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(selected.isNotEmpty() && selected.all { it in eligible })
        // 建議 ID 被人類確認後轉為 USER_MANUAL 候選；仍然不宣稱已驗證。
        val confirmedManual = (manual + selected.filter { id -> current.catalog?.models.orEmpty().none { it.modelId == id } }).distinct()
        val edited = current.copy(
            providerName = providerName?.trim()?.takeIf { it.isNotEmpty() },
            manualModelIds = confirmedManual,
            selectedModelIds = selected,
            reviewApproval = null,
            updatedAtMillis = clock.nowMillis(),
        )
        persist(edited.copy(reviewItems = buildReviewItems(edited)))
    }

    /**
     * 編輯非機密輸入後從指定步驟重新探測；歷史與已花預算不重設。
     * 呼叫端負責先把新機密送入 vault；這裡只接受 safe refs。
     */
    suspend fun editAndRerun(
        workflowId: String,
        inputs: OnboardingSnapshot,
        fromStep: OnboardingStepId = OnboardingStepId.INSPECT,
        runDetection: Boolean = inputs.flowType == OnboardingFlowType.QUICK,
    ): OnboardingRunSummary = gate.withLock {
        val previous = requireSnapshot(workflowId)
        check(previous.status !in setOf(OnboardingStatus.COMMITTED, OnboardingStatus.ABANDONED))
        require(fromStep.ordinal <= OnboardingStepId.REVIEW.ordinal)
        require(inputs.flowType == previous.flowType)
        // callers 不能藉 inputs 注入 approval、探測事實、receipt 或較低 budget。
        var edited = previous.copy(
            providerName = inputs.providerName,
            baseUrl = inputs.baseUrl,
            websiteUrl = inputs.websiteUrl,
            group = inputs.group,
            checkinUrl = inputs.checkinUrl,
            tags = inputs.tags,
            notes = inputs.notes,
            safeCredentialIds = inputs.safeCredentialIds,
            credentials = inputs.credentials,
            customAuthFields = inputs.customAuthFields,
            authModeOverride = inputs.authModeOverride,
            anonymousConfirmed = inputs.anonymousConfirmed,
            endpointProfiles = inputs.endpointProfiles,
            adapterTypeOverride = inputs.adapterTypeOverride,
            suggestedModelIds = inputs.suggestedModelIds,
            manualModelIds = inputs.manualModelIds,
            selectedModelIds = null,
            manualPricing = inputs.manualPricing,
            manualQuotaState = inputs.manualQuotaState,
            manualQuotaNote = inputs.manualQuotaNote,
            manualClassification = inputs.manualClassification,
            reviewApproval = null,
            reviewItems = null,
            reservation = null,
            rateLimitedAtMillis = null,
            status = OnboardingStatus.DRAFT,
            updatedAtMillis = clock.nowMillis(),
        )
        val connectionChanged = previous.baseUrl != edited.baseUrl || previous.websiteUrl != edited.websiteUrl ||
            previous.endpointProfiles != edited.endpointProfiles || previous.adapterTypeOverride != edited.adapterTypeOverride
        val authChanged = previous.credentials != edited.credentials || previous.safeCredentialIds != edited.safeCredentialIds ||
            previous.customAuthFields != edited.customAuthFields || previous.authModeOverride != edited.authModeOverride
        val modelsChanged = previous.manualModelIds != edited.manualModelIds || previous.suggestedModelIds != edited.suggestedModelIds
        val earliest = when {
            connectionChanged -> OnboardingStepId.INSPECT
            authChanged -> OnboardingStepId.AUTH
            modelsChanged -> OnboardingStepId.TEST_MODEL
            else -> fromStep
        }
        val restart = if (earliest.ordinal < fromStep.ordinal) earliest else fromStep
        if (restart.ordinal <= OnboardingStepId.DETECT_TYPE.ordinal) edited = edited.copy(detection = null)
        if (restart.ordinal <= OnboardingStepId.AUTH.ordinal) edited = edited.copy(authDecision = null)
        if (restart.ordinal <= OnboardingStepId.CREDENTIAL_TEST.ordinal) edited = edited.copy(credentialTest = null)
        if (restart.ordinal <= OnboardingStepId.LIST_MODELS.ordinal) edited = edited.copy(catalog = null)
        if (restart.ordinal <= OnboardingStepId.TEST_MODEL.ordinal) edited = edited.copy(modelTests = null)
        if (restart.ordinal <= OnboardingStepId.PRICING_QUOTA_NOTICE.ordinal) edited = edited.copy(pricingQuota = null)
        for (step in OnboardingStepId.ORDERED.filter { it.ordinal >= restart.ordinal }) {
            edited = supersedeLatest(edited, step).withStepStatus(step, OnboardingStepStatus.PENDING)
        }
        if (edited.flowType == OnboardingFlowType.ADVANCED && !runDetection) {
            for (step in OnboardingStepId.ORDERED.filter { it.ordinal < OnboardingStepId.REVIEW.ordinal && it.ordinal >= restart.ordinal }) {
                edited = edited.withStepStatus(step, OnboardingStepStatus.SKIPPED).copy(
                    results = edited.results + StepResult(step, OnboardingStepStatus.SKIPPED,
                        reason = OnboardingFailureReason.USER_SKIPPED, confidence = EvidenceConfidence.LOW, updatedAtMillis = clock.nowMillis()),
                )
            }
        }
        drive(persist(edited))
    }

    /** 建立新 workflow 並從第一步開始跑；draft 只放非 secret 輸入，workflowId 留空時由 workflow 產生。 */
    suspend fun start(
        draft: OnboardingSnapshot,
        runDetection: Boolean = draft.flowType == OnboardingFlowType.QUICK,
    ): OnboardingRunSummary = gate.withLock {
        val now = clock.nowMillis()
        val workflowId = draft.workflowId.ifBlank { UUID.randomUUID().toString() }
        // 已存在的 workflowId 不可重複 start：否則預算與 429 旗標被靜默重設，繞過 quota 保護。
        if (draft.workflowId.isNotBlank() && store.load(workflowId) != null) {
            throw OnboardingStateException("workflow 已存在，不可重複 start: $workflowId")
        }
        val fresh = draft.copy(
            workflowId = workflowId,
            status = OnboardingStatus.DRAFT,
            results = emptyList(),
            stepStatuses = initialStatuses(),
            budget = ProbeBudgetState(),
            reservation = null,
            rateLimitedAtMillis = null,
            commitReceipt = null,
            reviewApproval = null,
            createdAtMillis = if (draft.createdAtMillis > 0L) draft.createdAtMillis else now,
            updatedAtMillis = now,
        )
        val initial = if (draft.flowType == OnboardingFlowType.ADVANCED && !runDetection) {
            val skippedSteps = OnboardingStepId.ORDERED.filter { it.ordinal < OnboardingStepId.REVIEW.ordinal }
            fresh.copy(
                stepStatuses = fresh.stepStatuses + skippedSteps.associateWith { OnboardingStepStatus.SKIPPED },
                results = skippedSteps.map {
                    StepResult(it, OnboardingStepStatus.SKIPPED, reason = OnboardingFailureReason.USER_SKIPPED,
                        confidence = EvidenceConfidence.LOW, updatedAtMillis = now)
                },
            )
        } else fresh
        drive(persist(initial))
    }

    /** 從最後完成的步驟繼續；不重跑已 SUCCESS 的步驟；被殺掉的 in-flight 步驟記 INTERRUPTED 後重跑；被取消的步驟重開。 */
    suspend fun resume(workflowId: String): OnboardingRunSummary = gate.withLock {
        val stored = requireSnapshot(workflowId)
        if (stored.status == OnboardingStatus.COMMITTED || stored.status == OnboardingStatus.ABANDONED) {
            return@withLock summaryOf(stored, OnboardingStop.COMPLETED)
        }
        drive(persist(recoverCancelled(recoverInterrupted(stored))))
    }

    /** 取消不算完成：因取消而 FAILED 的步驟回到 PENDING，等 resume 重跑；真正的失敗保持 FAILED。 */
    private fun recoverCancelled(s: OnboardingSnapshot): OnboardingSnapshot {
        var out = s
        for (step in OnboardingStepId.ORDERED) {
            if (out.statusOf(step) == OnboardingStepStatus.FAILED &&
                latestResult(out, step)?.reason == OnboardingFailureReason.CANCELLED
            ) {
                out = out.withStepStatus(step, OnboardingStepStatus.PENDING)
            }
        }
        return out
    }

    /** 重跑單步：舊結果標 SUPERSEDED 保留歷史，下游重開為 PENDING，上游不動。 */
    suspend fun retryStep(workflowId: String, step: OnboardingStepId): OnboardingRunSummary = gate.withLock {
        var snapshot = requireSnapshot(workflowId)
        if (snapshot.status == OnboardingStatus.COMMITTED || snapshot.status == OnboardingStatus.ABANDONED) {
            throw OnboardingStateException("已結束的 workflow 不可重試: ${snapshot.status}")
        }
        snapshot = supersedeLatest(snapshot, step)
        snapshot = snapshot.withStepStatus(step, OnboardingStepStatus.PENDING)
        // 手動重試是明確的使用者動作（契約 §4「稍後重試單步」）：解除 429 停手旗標；
        // 總量與 per-step 預算仍在，額度不會因此恢復。
        snapshot = snapshot.copy(rateLimitedAtMillis = null)
        if (step.ordinal < OnboardingStepId.REVIEW.ordinal) {
            snapshot = snapshot.copy(reviewApproval = null)
        }
        if (step == OnboardingStepId.COMMIT && snapshot.statusOf(OnboardingStepId.REVIEW) !in DONE) {
            snapshot = snapshot.withStepStatus(OnboardingStepId.REVIEW, OnboardingStepStatus.PENDING)
        }
        snapshot = invalidateDownstream(snapshot, step, reopen = true)
        snapshot = snapshot.copy(status = OnboardingStatus.DRAFT, updatedAtMillis = clock.nowMillis())
        drive(persist(snapshot))
    }

    /** 使用者跳過單步；不重開下游、不清 approval，之後繼續往下跑。 */
    suspend fun skipStep(
        workflowId: String,
        step: OnboardingStepId,
        reason: String? = null,
    ): OnboardingRunSummary = gate.withLock {
        var snapshot = requireSnapshot(workflowId)
        if (snapshot.status == OnboardingStatus.COMMITTED || snapshot.status == OnboardingStatus.ABANDONED) {
            throw OnboardingStateException("已結束的 workflow 不可跳過步驟: ${snapshot.status}")
        }
        if (step == OnboardingStepId.REVIEW || step == OnboardingStepId.COMMIT) {
            throw OnboardingStateException("review / commit 不可跳過")
        }
        val now = clock.nowMillis()
        val attempt = currentAttempt(snapshot, step) + 1
        val skipped = StepResult(
            step = step,
            status = OnboardingStepStatus.SKIPPED,
            attempt = attempt,
            reason = OnboardingFailureReason.USER_SKIPPED,
            message = reason,
            updatedAtMillis = now,
        )
        snapshot = snapshot.replaceResult(step, attempt, skipped).withStepStatus(step, OnboardingStepStatus.SKIPPED)
        snapshot = snapshot.copy(updatedAtMillis = now)
        drive(persist(snapshot))
    }

    /**
     * 提交。必須有 [ReviewApproval] 且至少一個 manual / catalog 模型；
     * 不要求所有模型測試成功。同一 workflowId 重放為冪等 no-op。
     */
    suspend fun commit(
        workflowId: String,
        approval: ReviewApproval? = null,
    ): OnboardingRunSummary = gate.withLock {
        var snapshot = requireSnapshot(workflowId)
        if (snapshot.status == OnboardingStatus.COMMITTED) {
            return@withLock summaryOf(snapshot, OnboardingStop.COMPLETED)
        }
        if (snapshot.status == OnboardingStatus.ABANDONED) {
            throw OnboardingStateException("已 abandon 的 workflow 不可 commit")
        }
        val now = clock.nowMillis()
        if (approval != null) {
            snapshot = snapshot.copy(reviewApproval = approval, updatedAtMillis = now)
        }
        snapshot = persist(snapshot)
        val rejection = preDriveRejection(snapshot)
        if (rejection != null) {
            if (rejection != OnboardingFailureReason.BLOCKED_UPSTREAM) {
                persist(recordRejection(snapshot, rejection, now))
            }
            throw CommitRejectedException(listOf(rejection))
        }
        if (snapshot.statusOf(OnboardingStepId.REVIEW) !in DONE) {
            snapshot = snapshot.withStepStatus(OnboardingStepId.REVIEW, OnboardingStepStatus.PENDING)
        }
        if (snapshot.statusOf(OnboardingStepId.COMMIT) !in DONE) {
            snapshot = snapshot.withStepStatus(OnboardingStepId.COMMIT, OnboardingStepStatus.PENDING)
        }
        drive(persist(snapshot))
    }

    /** 放棄（丟棄已驗證結果，屬 destructive 動作：確認由呼叫端負責）。 */
    suspend fun abandon(workflowId: String, reason: String? = null): OnboardingRunSummary = gate.withLock {
        val snapshot = requireSnapshot(workflowId)
        if (snapshot.status == OnboardingStatus.COMMITTED) {
            throw OnboardingStateException("已 commit 的 workflow 不可 abandon")
        }
        if (snapshot.status == OnboardingStatus.ABANDONED) {
            return@withLock summaryOf(snapshot, OnboardingStop.COMPLETED)
        }
        val now = clock.nowMillis()
        var out = snapshot.copy(reservation = null, status = OnboardingStatus.ABANDONED, updatedAtMillis = now)
        for (step in OnboardingStepId.ORDERED) {
            if (out.statusOf(step) in DONE) continue
            val attempt = currentAttempt(out, step) + 1
            val abandoned = StepResult(
                step = step,
                status = OnboardingStepStatus.SKIPPED,
                attempt = attempt,
                reason = OnboardingFailureReason.ABANDONED,
                message = reason,
                updatedAtMillis = now,
            )
            out = out.replaceResult(step, attempt, abandoned).withStepStatus(step, OnboardingStepStatus.SKIPPED)
        }
        summaryOf(persist(out), OnboardingStop.COMPLETED)
    }

    private suspend fun drive(initial: OnboardingSnapshot): OnboardingRunSummary {
        var current = initial
        var iterations = 0
        while (true) {
            val next = nextRunnableStep(current) ?: break
            iterations++
            if (iterations > MAX_DRIVE_ITERATIONS) {
                throw OnboardingStateException("onboarding 步驟無法收斂，停在 $next")
            }
            val outcome = runStep(current, next)
            current = outcome.snapshot
            if (outcome.stop != OnboardingStop.NONE) {
                return finish(current, outcome.stop)
            }
        }
        return finish(current, OnboardingStop.NONE)
    }

    private suspend fun runStep(s: OnboardingSnapshot, step: OnboardingStepId): StepOutcome {
        val now = clock.nowMillis()
        val attempt = currentAttempt(s, step) + 1
        val perStepLimit = budget.requestLimitFor(step)

        if (s.rateLimitedAtMillis != null && perStepLimit > 0) {
            return finalize(
                s = s,
                step = step,
                attempt = attempt,
                reserved = BudgetReservation(step, attempt, 0, 0),
                raw = StepResult(
                    step = step,
                    status = OnboardingStepStatus.SKIPPED,
                    attempt = attempt,
                    reason = OnboardingFailureReason.RATE_LIMITED_STOP,
                    message = STOPPED_BY_429_MESSAGE,
                    updatedAtMillis = now,
                ),
                now = now,
                invalidate = false,
            )
        }

        if (step == OnboardingStepId.TEST_MODEL && testTargets(s).isEmpty()) {
            return finalize(
                s = s,
                step = step,
                attempt = attempt,
                reserved = BudgetReservation(step, attempt, 0, 0),
                raw = StepResult(
                    step = step,
                    status = OnboardingStepStatus.NEEDS_INPUT,
                    attempt = attempt,
                    reason = OnboardingFailureReason.NO_MODELS_TO_TEST,
                    message = NO_MODELS_MESSAGE,
                    updatedAtMillis = now,
                ),
                now = now,
                invalidate = false,
            )
        }

        val reserved = planReservation(s, step, attempt)
        if (reserved.requests <= 0 && perStepLimit > 0) {
            return finalize(
                s = s,
                step = step,
                attempt = attempt,
                reserved = reserved,
                raw = StepResult(
                    step = step,
                    status = OnboardingStepStatus.SKIPPED,
                    attempt = attempt,
                    reason = OnboardingFailureReason.BUDGET_EXHAUSTED,
                    message = BUDGET_MESSAGE,
                    updatedAtMillis = now,
                ),
                now = now,
                invalidate = false,
            )
        }

        val grant = RequestGrant(
            step = step,
            attempt = attempt,
            requestLimit = reserved.requests,
            maxTestModels = reserved.testModels,
            rateLimitStopped = s.rateLimitedAtMillis != null,
        )
        val started = persist(
            s.copy(
                status = OnboardingStatus.RUNNING,
                reservation = reserved,
                stepStatuses = s.stepStatuses + (step to OnboardingStepStatus.RUNNING),
                results = s.results + StepResult(
                    step = step,
                    status = OnboardingStepStatus.RUNNING,
                    attempt = attempt,
                    requestLimit = reserved.requests,
                    updatedAtMillis = now,
                ),
                updatedAtMillis = now,
            ),
        )

        val execution = try {
            withTimeout(ProbeBudgetLimits.STEP_TIMEOUT_SECONDS * 1000) {
                executeStep(started, step, grant)
            }
        } catch (e: TimeoutCancellationException) {
            StepExecution(
                result = StepResult(
                    step = step,
                    status = OnboardingStepStatus.FAILED,
                    attempt = attempt,
                    reason = OnboardingFailureReason.TIMEOUT,
                    message = STEP_TIMEOUT_MESSAGE,
                    requestsSpent = grant.reservedRequests,
                    updatedAtMillis = now,
                ),
            )
        } catch (e: CancellationException) {
            finalize(
                s = started,
                step = step,
                attempt = attempt,
                reserved = reserved,
                raw = StepResult(
                    step = step,
                    status = OnboardingStepStatus.FAILED,
                    attempt = attempt,
                    reason = OnboardingFailureReason.CANCELLED,
                    message = CANCELLED_MESSAGE,
                    requestsSpent = grant.reservedRequests,
                    updatedAtMillis = now,
                ),
                now = now,
                invalidate = false,
            )
            throw e
        } catch (e: Exception) {
            StepExecution(
                result = StepResult(
                    step = step,
                    status = OnboardingStepStatus.FAILED,
                    attempt = attempt,
                    reason = if (step == OnboardingStepId.COMMIT) OnboardingFailureReason.UNKNOWN else classify(e),
                    message = SAFE_FAILURE_MESSAGE,
                    updatedAtMillis = now,
                ),
            )
        }

        return finalize(
            s = started,
            step = step,
            attempt = attempt,
            reserved = reserved,
            raw = execution.result,
            now = now,
            invalidate = true,
            receipt = execution.receipt,
        )
    }

    private suspend fun executeStep(
        s: OnboardingSnapshot,
        step: OnboardingStepId,
        grant: RequestGrant,
    ): StepExecution = when (step) {
        OnboardingStepId.REVIEW -> StepExecution(reviewResult(s))
        OnboardingStepId.COMMIT -> commitResult(s)
        else -> StepExecution(executor.execute(s, step, grant))
    }

    private fun reviewResult(s: OnboardingSnapshot): StepResult {
        val approval = s.reviewApproval
        return if (approval != null && approval.approved) {
            StepResult(
                step = OnboardingStepId.REVIEW,
                status = OnboardingStepStatus.SUCCESS,
                confidence = EvidenceConfidence.MEDIUM,
                message = REVIEW_APPROVED_MESSAGE,
            )
        } else {
            StepResult(
                step = OnboardingStepId.REVIEW,
                status = OnboardingStepStatus.NEEDS_INPUT,
                reason = OnboardingFailureReason.REVIEW_NOT_APPROVED,
                message = REVIEW_PENDING_MESSAGE,
            )
        }
    }

    private suspend fun commitResult(s: OnboardingSnapshot): StepExecution {
        val rejection = commitRejection(s)
        if (rejection != null) {
            return StepExecution(
                StepResult(
                    step = OnboardingStepId.COMMIT,
                    status = OnboardingStepStatus.FAILED,
                    reason = rejection,
                    message = commitRejectionMessage(rejection),
                ),
            )
        }
        val receipt = committer.commit(s)
        return StepExecution(
            result = StepResult(
                step = OnboardingStepId.COMMIT,
                status = OnboardingStepStatus.SUCCESS,
                evidenceIds = listOf(receipt.commitId),
                confidence = EvidenceConfidence.HIGH,
            ),
            receipt = receipt,
        )
    }

    private suspend fun finalize(
        s: OnboardingSnapshot,
        step: OnboardingStepId,
        attempt: Int,
        reserved: BudgetReservation,
        raw: StepResult,
        now: Long,
        invalidate: Boolean,
        receipt: CommitReceipt? = null,
    ): StepOutcome {
        val effective = normalize(raw, step, attempt, reserved, now)
        val stop = if (receipt != null) OnboardingStop.COMPLETED else stopFor(step, effective)
        var out = s.replaceResult(step, attempt, effective).withStepStatus(step, effective.status)
        out = applyFinding(step, out, effective)
        out = chargeBudget(out, step, reserved, effective.requestsSpent)
        if (effective.reason == OnboardingFailureReason.RATE_LIMITED) {
            out = out.copy(rateLimitedAtMillis = now)
        }
        if (stop == OnboardingStop.BLOCKED) {
            out = markBlockedDownstream(out, step, effective.reason ?: OnboardingFailureReason.UNKNOWN)
        } else if (invalidate) {
            out = invalidateDownstream(out, step, reopen = false)
        }
        if (receipt != null) {
            out = out.copy(commitReceipt = receipt)
        }
        out = out.copy(
            status = if (receipt != null) OnboardingStatus.COMMITTED else OnboardingStatus.RUNNING,
            updatedAtMillis = now,
        )
        return StepOutcome(persist(out), stop)
    }

    private fun normalize(
        raw: StepResult,
        step: OnboardingStepId,
        attempt: Int,
        reserved: BudgetReservation,
        now: Long,
    ): StepResult {
        var status = raw.status
        var reason = raw.reason
        if (status == OnboardingStepStatus.PENDING || status == OnboardingStepStatus.RUNNING || status == OnboardingStepStatus.SUPERSEDED) {
            status = OnboardingStepStatus.FAILED
            reason = reason ?: OnboardingFailureReason.UNKNOWN
        }
        if (status == OnboardingStepStatus.FAILED && reason != null && reason in NEEDS_INPUT_REASONS) {
            status = OnboardingStepStatus.NEEDS_INPUT
        }
        return raw.copy(
            step = step,
            status = status,
            reason = reason,
            attempt = attempt,
            requestsSpent = raw.requestsSpent.coerceIn(0, reserved.requests),
            requestLimit = reserved.requests,
            updatedAtMillis = now,
        )
    }

    private fun stopFor(step: OnboardingStepId, result: StepResult): OnboardingStop = when {
        result.status == OnboardingStepStatus.NEEDS_INPUT -> OnboardingStop.NEEDS_INPUT
        result.reason != null && result.reason in NEEDS_INPUT_REASONS -> OnboardingStop.NEEDS_INPUT
        step == OnboardingStepId.INSPECT && result.status == OnboardingStepStatus.FAILED -> OnboardingStop.BLOCKED
        else -> OnboardingStop.NONE
    }

    private fun applyFinding(
        step: OnboardingStepId,
        s: OnboardingSnapshot,
        result: StepResult,
    ): OnboardingSnapshot = when (step) {
        OnboardingStepId.INSPECT -> s
        OnboardingStepId.DETECT_TYPE -> s.copy(
            detection = DetectionFinding(
                adapterType = result.adapterType ?: s.adapterTypeOverride ?: AdapterType.UNKNOWN,
                confidence = result.confidence,
                basis = result.evidenceIds.firstOrNull(),
            ),
        )
        OnboardingStepId.AUTH -> {
            val mode = result.authMode ?: s.authModeOverride ?: AuthMode.BEARER
            s.copy(
                authDecision = AuthDecision(
                    authMode = mode,
                    requiresCredential = mode != AuthMode.NONE,
                    credentialIds = result.safeCredentialIds.ifEmpty { s.safeCredentialIds },
                    customAuthFieldNames = s.customAuthFields.map { it.name },
                    missingCredential = result.reason == OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
                ),
            )
        }
        OnboardingStepId.CREDENTIAL_TEST -> s.copy(credentialTest = result.credentialTest ?: CredentialTestFinding())
        OnboardingStepId.LIST_MODELS -> s.copy(catalog = result.catalog ?: s.catalog)
        OnboardingStepId.TEST_MODEL -> s.copy(modelTests = result.modelTests ?: s.modelTests)
        OnboardingStepId.PRICING_QUOTA_NOTICE -> s.copy(pricingQuota = result.pricingQuota ?: s.pricingQuota)
        OnboardingStepId.REVIEW -> s.copy(reviewItems = buildReviewItems(s))
        OnboardingStepId.COMMIT -> s
    }

    private fun chargeBudget(
        s: OnboardingSnapshot,
        step: OnboardingStepId,
        reserved: BudgetReservation,
        spend: Int,
    ): OnboardingSnapshot {
        val amount = spend.coerceIn(0, reserved.requests)
        val models = if (step == OnboardingStepId.TEST_MODEL) minOf(amount, reserved.testModels) else 0
        return s.copy(
            reservation = null,
            budget = s.budget.copy(
                totalRequests = s.budget.totalRequests + amount,
                testModelsAttempted = s.budget.testModelsAttempted + models,
                stepUsage = s.budget.stepUsage + (step to (s.budget.usedFor(step) + amount)),
            ),
        )
    }

    private fun invalidateDownstream(
        s: OnboardingSnapshot,
        from: OnboardingStepId,
        reopen: Boolean,
    ): OnboardingSnapshot {
        val later = OnboardingStepId.ORDERED.filter { it.ordinal > from.ordinal }
        val now = clock.nowMillis()
        var statuses = s.stepStatuses
        var results = s.results
        for (step in later) {
            val current = statuses[step] ?: OnboardingStepStatus.PENDING
            if (current !in TERMINAL) continue
            val latest = latestOf(results, step)
            if (reopen && latest?.reason == OnboardingFailureReason.USER_SKIPPED) continue
            statuses = statuses + (step to OnboardingStepStatus.STALE)
            results = replaceLatest(results, step) {
                it.copy(status = OnboardingStepStatus.STALE, reason = OnboardingFailureReason.STALE_UPSTREAM, updatedAtMillis = now)
            }
        }
        val approval = if (later.contains(OnboardingStepId.REVIEW)) null else s.reviewApproval
        return s.copy(stepStatuses = statuses, results = results, reviewApproval = approval)
    }

    private fun markBlockedDownstream(
        s: OnboardingSnapshot,
        from: OnboardingStepId,
        reason: OnboardingFailureReason,
    ): OnboardingSnapshot {
        val now = clock.nowMillis()
        var out = s
        for (step in OnboardingStepId.ORDERED) {
            if (step.ordinal <= from.ordinal) continue
            if (out.statusOf(step) in DONE || out.statusOf(step) == OnboardingStepStatus.SKIPPED) continue
            val attempt = currentAttempt(out, step) + 1
            val skipped = StepResult(
                step = step,
                status = OnboardingStepStatus.SKIPPED,
                attempt = attempt,
                reason = reason,
                message = BLOCKED_MESSAGE,
                updatedAtMillis = now,
            )
            out = out.replaceResult(step, attempt, skipped).withStepStatus(step, OnboardingStepStatus.SKIPPED)
        }
        return out.copy(status = OnboardingStatus.BLOCKED)
    }

    private fun recoverInterrupted(s: OnboardingSnapshot): OnboardingSnapshot {
        val running = OnboardingStepId.ORDERED.filter { s.statusOf(it) == OnboardingStepStatus.RUNNING }
        if (running.isEmpty()) return s.copy(reservation = null)
        val now = clock.nowMillis()
        val held = s.reservation
        var out = s.copy(reservation = null)
        if (held != null) {
            out = chargeBudget(out, held.step, held, held.requests)
        }
        for (step in running) {
            val attempt = currentAttempt(out, step)
            val previous = latestResult(out, step)
                ?: StepResult(step = step, status = OnboardingStepStatus.RUNNING, attempt = attempt)
            out = out.replaceResult(
                step,
                attempt,
                previous.copy(
                    status = OnboardingStepStatus.FAILED,
                    reason = OnboardingFailureReason.INTERRUPTED,
                    message = INTERRUPTED_MESSAGE,
                    updatedAtMillis = now,
                ),
            ).withStepStatus(step, OnboardingStepStatus.PENDING)
        }
        return out
    }

    private fun commitRejection(s: OnboardingSnapshot): OnboardingFailureReason? {
        val base = preDriveRejection(s) ?: return null
        if (base != OnboardingFailureReason.REVIEW_NOT_APPROVED) return base
        if (s.statusOf(OnboardingStepId.REVIEW) !in DONE) return OnboardingFailureReason.REVIEW_INCOMPLETE
        return null
    }

    private fun preDriveRejection(s: OnboardingSnapshot): OnboardingFailureReason? {
        val approval = s.reviewApproval
        if (approval == null || !approval.approved) return OnboardingFailureReason.REVIEW_NOT_APPROVED
        if (s.flowType == OnboardingFlowType.ADVANCED && s.authModeOverride == AuthMode.NONE && !s.anonymousConfirmed) {
            return OnboardingFailureReason.POLICY_BLOCKED
        }
        if (s.statusOf(OnboardingStepId.INSPECT) == OnboardingStepStatus.FAILED) return OnboardingFailureReason.BLOCKED_UPSTREAM
        if (s.authDecision?.missingCredential == true ||
            latestResult(s, OnboardingStepId.CREDENTIAL_TEST)?.reason in setOf(
                OnboardingFailureReason.AUTH_FAILED, OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
            )
        ) return OnboardingFailureReason.AUTH_FAILED
        if (s.reviewItems == null) return OnboardingFailureReason.REVIEW_INCOMPLETE
        if (!hasCommitModels(s)) return OnboardingFailureReason.NO_MODELS_TO_TEST
        return null
    }

    private fun recordRejection(
        s: OnboardingSnapshot,
        reason: OnboardingFailureReason,
        now: Long,
    ): OnboardingSnapshot {
        val step = OnboardingStepId.COMMIT
        val attempt = currentAttempt(s, step) + 1
        val rejected = StepResult(
            step = step,
            status = OnboardingStepStatus.NEEDS_INPUT,
            attempt = attempt,
            reason = reason,
            message = commitRejectionMessage(reason),
            updatedAtMillis = now,
        )
        val out = s.replaceResult(step, attempt, rejected).withStepStatus(step, OnboardingStepStatus.NEEDS_INPUT)
        return out.copy(status = OnboardingStatus.NEEDS_INPUT, updatedAtMillis = now)
    }

    private fun buildReviewItems(s: OnboardingSnapshot): ReviewItems {
        val catalog = s.catalog
        val tests = s.modelTests
        val verified = tests?.findings?.filter { it.verification == ModelVerification.VERIFIED }?.map { it.modelId } ?: emptyList()
        val verifiedCount = tests?.findings?.count { it.verification == ModelVerification.VERIFIED }
        val testedTotal = tests?.let { it.testedTotal ?: it.findings.size }
        val pricing = s.pricingQuota
        val pricingStatus = pricing?.pricingStatus ?: PriceStatus.UNKNOWN
        val quotaStatus = pricing?.quotaStatus ?: s.manualQuotaState ?: QuotaState.UNKNOWN
        val officialHealth = pricing?.officialHealth ?: HealthState.UNKNOWN
        val observedHealth = pricing?.observedHealth ?: HealthState.UNKNOWN
        val checkinUrl = pricing?.checkinUrl ?: s.checkinUrl
        val adapterType = s.adapterTypeOverride ?: s.detection?.adapterType ?: AdapterType.UNKNOWN
        val unknown = ArrayList<String>()
        if (adapterType == AdapterType.UNKNOWN) unknown.add("providerType")
        if (catalog == null || !catalog.supported) unknown.add("catalog")
        if (catalog?.supported == true && catalog.truncated && catalog.totalCount == null) unknown.add("modelsFoundCount")
        if (tests == null) unknown.add("modelsVerified")
        if (verifiedCount != null && verifiedCount == 0) unknown.add("modelsVerifiedEmpty")
        if (pricingStatus == PriceStatus.UNKNOWN) unknown.add("pricing")
        if (quotaStatus == QuotaState.UNKNOWN) unknown.add("quota")
        if (officialHealth == HealthState.UNKNOWN) unknown.add("healthOfficial")
        if (observedHealth == HealthState.UNKNOWN) unknown.add("healthObserved")
        if (checkinUrl == null) unknown.add("checkinUrl")
        if (s.credentialTest == null) unknown.add("credentialTest")
        val confidenceOf: (OnboardingStepId) -> EvidenceConfidence = { step ->
            latestResult(s, step)?.confidence ?: EvidenceConfidence.UNKNOWN
        }
        return ReviewItems(
            providerType = adapterType,
            providerTypeBasis = s.detection?.basis,
            auth = ReviewAuth(
                authMode = s.authDecision?.authMode ?: s.authModeOverride ?: AuthMode.BEARER,
                credentialLabels = s.credentials.map { it.label },
                customAuthFieldNames = s.customAuthFields.map { it.name },
                credentialTestStatus = s.statusOf(OnboardingStepId.CREDENTIAL_TEST),
                failureReason = latestResult(s, OnboardingStepId.CREDENTIAL_TEST)?.reason,
            ),
            modelsFound = catalog?.let { if (!it.supported) null else it.totalCount ?: if (!it.truncated) it.models.size else null },
            modelsVerified = verifiedCount,
            modelsVerifiedIds = verified,
            modelsTestedTotal = testedTotal,
            pricingStatus = pricingStatus,
            quotaStatus = quotaStatus,
            officialHealth = officialHealth,
            observedHealth = observedHealth,
            unknownFields = unknown,
            confidences = mapOf(
                ReviewItem.PROVIDER_TYPE to confidenceOf(OnboardingStepId.DETECT_TYPE),
                ReviewItem.AUTH to confidenceOf(OnboardingStepId.CREDENTIAL_TEST),
                ReviewItem.MODELS_FOUND to confidenceOf(OnboardingStepId.LIST_MODELS),
                ReviewItem.MODELS_VERIFIED to confidenceOf(OnboardingStepId.TEST_MODEL),
                ReviewItem.PRICING to confidenceOf(OnboardingStepId.PRICING_QUOTA_NOTICE),
                ReviewItem.QUOTA to confidenceOf(OnboardingStepId.PRICING_QUOTA_NOTICE),
                ReviewItem.HEALTH_OFFICIAL to confidenceOf(OnboardingStepId.PRICING_QUOTA_NOTICE),
                ReviewItem.HEALTH_OBSERVED to confidenceOf(OnboardingStepId.PRICING_QUOTA_NOTICE),
            ),
            evidenceIds = s.results.flatMap { it.evidenceIds }.distinct(),
            checkinUrl = checkinUrl,
            requiresDailyCheckin = s.checkinUrl != null || pricing?.requiresDailyCheckin == true,
            requestsSpent = s.budget.totalRequests,
            rateLimited = s.rateLimitedAtMillis != null,
        )
    }

    private fun planReservation(s: OnboardingSnapshot, step: OnboardingStepId, attempt: Int): BudgetReservation {
        val perStep = budget.requestLimitFor(step)
        if (perStep == 0) return BudgetReservation(step, attempt, 0, 0)
        val stepLeft = (perStep - s.budget.usedFor(step)).coerceAtLeast(0)
        val totalLeft = (budget.maxTotalRequests - s.budget.totalRequests - (s.reservation?.requests ?: 0)).coerceAtLeast(0)
        if (step == OnboardingStepId.TEST_MODEL) {
            val modelsLeft = (budget.maxTestModels - s.budget.testModelsAttempted).coerceAtLeast(0)
            val requests = minOf(perStep, stepLeft, totalLeft, modelsLeft, testTargets(s).size)
            return BudgetReservation(step, attempt, requests, requests)
        }
        return BudgetReservation(step, attempt, minOf(perStep, stepLeft, totalLeft), 0)
    }

    private fun testTargets(s: OnboardingSnapshot): List<String> {
        val fromCatalog = s.catalog?.takeIf { it.supported }?.models?.map { it.modelId } ?: emptyList()
        return (fromCatalog + s.manualModelIds + s.suggestedModelIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun hasCommitModels(s: OnboardingSnapshot): Boolean {
        val models = (s.manualModelIds + s.catalog?.models.orEmpty().map { it.modelId }).filter { it.isNotBlank() }
        return models.any { s.selectedModelIds == null || it in s.selectedModelIds }
    }

    private fun nextRunnableStep(s: OnboardingSnapshot): OnboardingStepId? {
        if (s.status == OnboardingStatus.COMMITTED || s.status == OnboardingStatus.ABANDONED) return null
        return OnboardingStepId.ORDERED.firstOrNull { s.statusOf(it) in RUNNABLE }
    }

    private suspend fun finish(s: OnboardingSnapshot, stop: OnboardingStop): OnboardingRunSummary {
        val status = when (stop) {
            OnboardingStop.BLOCKED -> OnboardingStatus.BLOCKED
            OnboardingStop.COMPLETED -> s.status
            OnboardingStop.NEEDS_INPUT, OnboardingStop.NONE -> terminalStatus(s)
        }
        val out = s.copy(status = status, updatedAtMillis = clock.nowMillis())
        return summaryOf(persist(out), stop)
    }

    private fun terminalStatus(s: OnboardingSnapshot): OnboardingStatus {
        if (s.status == OnboardingStatus.COMMITTED || s.status == OnboardingStatus.ABANDONED) return s.status
        if (s.statusOf(OnboardingStepId.INSPECT) == OnboardingStepStatus.FAILED) return OnboardingStatus.BLOCKED
        val waiting = OnboardingStepId.ORDERED.firstOrNull { s.statusOf(it) == OnboardingStepStatus.NEEDS_INPUT }
        if (waiting != null) return OnboardingStatus.NEEDS_INPUT
        if (OnboardingStepId.ORDERED.all { s.statusOf(it) in TERMINAL }) return OnboardingStatus.REVIEW
        return s.status
    }

    private fun summaryOf(s: OnboardingSnapshot, stop: OnboardingStop): OnboardingRunSummary = OnboardingRunSummary(
        workflowId = s.workflowId,
        status = s.status,
        stop = stop,
        progress = OnboardingStepId.ORDERED.map { step ->
            val result = latestResult(s, step)
            OnboardingStepProgress(
                step = step,
                status = s.statusOf(step),
                attempt = result?.attempt ?: 0,
                requestsSpent = result?.requestsSpent ?: 0,
                reason = result?.reason,
            )
        },
        totalRequestsSpent = s.budget.totalRequests,
        requestsRemaining = (budget.maxTotalRequests - s.budget.totalRequests).coerceAtLeast(0),
        rateLimited = s.rateLimitedAtMillis != null,
        commitReceipt = s.commitReceipt,
    )

    private fun commitRejectionMessage(reason: OnboardingFailureReason): String = when (reason) {
        OnboardingFailureReason.REVIEW_NOT_APPROVED -> REVIEW_PENDING_MESSAGE
        OnboardingFailureReason.REVIEW_INCOMPLETE -> "尚無 review 內容"
        OnboardingFailureReason.NO_MODELS_TO_TEST -> NO_MODELS_MESSAGE
        OnboardingFailureReason.BLOCKED_UPSTREAM -> BLOCKED_MESSAGE
        else -> "不可 commit"
    }

    private fun classify(e: Exception): OnboardingFailureReason = when (e) {
        is SocketTimeoutException -> OnboardingFailureReason.TIMEOUT
        is SSLException -> OnboardingFailureReason.TLS_FAILED
        is UnknownHostException, is ConnectException -> OnboardingFailureReason.NETWORK_UNREACHABLE
        is IOException -> OnboardingFailureReason.NETWORK_UNREACHABLE
        else -> OnboardingFailureReason.UNKNOWN
    }

    private fun currentAttempt(s: OnboardingSnapshot, step: OnboardingStepId): Int = latestResult(s, step)?.attempt ?: 0

    private fun latestResult(s: OnboardingSnapshot, step: OnboardingStepId): StepResult? = latestOf(s.results, step)

    private fun latestOf(results: List<StepResult>, step: OnboardingStepId): StepResult? = results.lastOrNull { it.step == step }

    private fun OnboardingSnapshot.replaceResult(step: OnboardingStepId, attempt: Int, result: StepResult): OnboardingSnapshot {
        val list = results.toMutableList()
        val index = list.indexOfLast { it.step == step && it.attempt == attempt }
        if (index >= 0) list[index] = result else list += result
        return copy(results = list)
    }

    private fun replaceLatest(results: List<StepResult>, step: OnboardingStepId, transform: (StepResult) -> StepResult): List<StepResult> {
        val list = results.toMutableList()
        val index = list.indexOfLast { it.step == step }
        if (index < 0) return results
        list[index] = transform(list[index])
        return list
    }

    private fun supersedeLatest(s: OnboardingSnapshot, step: OnboardingStepId): OnboardingSnapshot {
        val latest = latestResult(s, step) ?: return s
        val updated = latest.copy(
            status = OnboardingStepStatus.SUPERSEDED,
            reason = OnboardingFailureReason.SUPERSEDED,
            updatedAtMillis = clock.nowMillis(),
        )
        return s.copy(results = replaceLatest(s.results, step) { updated })
    }

    private fun OnboardingSnapshot.statusOf(step: OnboardingStepId): OnboardingStepStatus =
        stepStatuses[step] ?: OnboardingStepStatus.PENDING

    private fun OnboardingSnapshot.withStepStatus(step: OnboardingStepId, status: OnboardingStepStatus): OnboardingSnapshot =
        copy(stepStatuses = stepStatuses + (step to status))

    private suspend fun persist(s: OnboardingSnapshot): OnboardingSnapshot {
        store.save(s)
        return s
    }

    private suspend fun requireSnapshot(workflowId: String): OnboardingSnapshot =
        store.load(workflowId) ?: throw OnboardingNotFoundException(workflowId)

    private fun initialStatuses(): Map<OnboardingStepId, OnboardingStepStatus> =
        OnboardingStepId.values().associateWith { OnboardingStepStatus.PENDING }

    private class StepExecution(val result: StepResult, val receipt: CommitReceipt? = null)

    private class StepOutcome(val snapshot: OnboardingSnapshot, val stop: OnboardingStop)

    private companion object {
        val DONE = setOf(OnboardingStepStatus.SUCCESS, OnboardingStepStatus.PARTIAL)

        val RUNNABLE = setOf(
            OnboardingStepStatus.PENDING,
            OnboardingStepStatus.RUNNING,
            OnboardingStepStatus.STALE,
        )

        val TERMINAL = setOf(
            OnboardingStepStatus.NEEDS_INPUT,
            OnboardingStepStatus.SUCCESS,
            OnboardingStepStatus.PARTIAL,
            OnboardingStepStatus.SKIPPED,
            OnboardingStepStatus.FAILED,
        )

        val NEEDS_INPUT_REASONS = setOf(
            OnboardingFailureReason.INPUT_INVALID,
            OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
            OnboardingFailureReason.AUTH_FAILED,
            OnboardingFailureReason.POLICY_BLOCKED,
            OnboardingFailureReason.NO_MODELS_TO_TEST,
            OnboardingFailureReason.REVIEW_NOT_APPROVED,
            OnboardingFailureReason.REVIEW_INCOMPLETE,
        )

        const val MAX_DRIVE_ITERATIONS = 64
        const val CANCELLED_MESSAGE = "已取消，未完成"
        const val INTERRUPTED_MESSAGE = "上次執行被中斷，可重試"
        const val SAFE_FAILURE_MESSAGE = "步驟執行失敗"
        const val BLOCKED_MESSAGE = "前一步失敗，已停止後續步驟"
        const val STOPPED_BY_429_MESSAGE = "已遇 429，停止後續探測"
        const val BUDGET_MESSAGE = "探測預算用盡，未執行"
        const val STEP_TIMEOUT_MESSAGE = "單步探測逾時，未完成"
        const val NO_MODELS_MESSAGE = "沒有可測模型，等待 catalog 或手動 model id"
        const val REVIEW_PENDING_MESSAGE = "等待人工確認"
        const val REVIEW_APPROVED_MESSAGE = "已確認"
    }
}
