package dev.modelatlas.core.onboarding.batch

import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.onboarding.OnboardingClock
import dev.modelatlas.core.onboarding.OnboardingNotFoundException
import dev.modelatlas.core.onboarding.OnboardingRunSummary
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.OnboardingStateException
import dev.modelatlas.core.onboarding.OnboardingStatus
import dev.modelatlas.core.onboarding.OnboardingStepId
import dev.modelatlas.core.onboarding.OnboardingStepStatus
import dev.modelatlas.core.onboarding.ProbeBudgetState
import dev.modelatlas.core.onboarding.SystemOnboardingClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 批次匯入執行器（Phase 3 Providers；`docs/PROVIDER_ONBOARDING_CONTRACT.md` §0.2 / §1 / §3）。
 *
 * 保證（逐條對應契約）：
 * - **共用 workflow**：每筆都走同一個 [dev.modelatlas.core.onboarding.OnboardingWorkflow]，
 *   不另開旁路、不自製探測流程。
 * - **單一執行**：同一實例的執行以 [gate] 序列化，逐筆 `await`，不平行發探測、不平行耗 quota。
 * - **絕不自動 approve / commit**：只呼叫 `start` / `resume`；不寫 `reviewApproval`；
 *   預設 factory 內建 [BatchNoCommitCommitter]，並拒絕帶 approval 的匯入輸入。
 * - **跑到 Review**：每筆驅動到 `Review` 停下等人類確認（`AWAITING_REVIEW`），已驗證結果保留。
 * - **可恢復**：每筆的 `workflowId` 固定保存；`resume` 不重跑已完成（已到 Review）的筆，
 *   取消 / 進程中斷的筆從最後完成的步驟續跑。
 * - **429 停排**：任一筆遇 `rateLimited` 即停止排程後續筆（已完成結果保留），批次轉 `PAUSED`。
 * - **逐筆失敗可續**：匯入輸入驗證失敗或單筆 workflow 失敗都以固定分類記錄，續跑其他筆。
 *
 * 不做：UI、Room、網路、vault。所有外部行為（持久化、探測、commit 拒絕）由注入 port 決定。
 */
class BatchOnboardingImporter(
    private val store: BatchStateStore,
    private val workflows: BatchWorkflowFactory,
    private val clock: OnboardingClock = SystemOnboardingClock,
    private val limits: BatchLimits = BatchLimits.DEFAULT,
    private val ids: BatchIdGenerator = UuidBatchIdGenerator,
) {
    private val gate = Mutex()

    @Volatile
    private var cancelRequested = false

    /** 請求停止排程：目前這筆跑完後不再排新筆（協作式，不中斷已在飛的 request）。 */
    fun requestCancel() {
        cancelRequested = true
    }

    /** 建立批次並逐筆跑到終結；每筆狀態變動即整份保存。 */
    suspend fun start(draft: BatchDraft): BatchSnapshot = gate.withLock {
        cancelRequested = false
        if (draft.items.isEmpty()) throw BatchException(BatchFailure.EMPTY_BATCH)
        if (draft.items.size > limits.maxItems) throw BatchException(BatchFailure.ITEM_LIMIT_EXCEEDED)
        val now = clock.nowMillis()
        val batchId = ids.newId()
        if (store.load(batchId) != null) throw BatchException(BatchFailure.BATCH_ALREADY_EXISTS)
        val seeded = persist(
            BatchSnapshot(
                batchId = batchId,
                status = BatchStatus.RUNNING,
                items = draft.items.mapIndexed { index, item -> seed(item, index, now) },
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        runBatch(seeded)
    }

    /** 續跑：已完成（終結）的筆不重跑；被取消 / 中斷的筆續跑；`COMPLETED` 批次直接回現況。 */
    suspend fun resume(batchId: String): BatchSnapshot = gate.withLock {
        cancelRequested = false
        val stored = store.load(batchId) ?: throw BatchException(BatchFailure.BATCH_NOT_FOUND)
        if (stored.status == BatchStatus.COMPLETED) return@withLock stored
        val now = clock.nowMillis()
        val reopened = persist(
            stored.copy(
                status = BatchStatus.RUNNING,
                stopReason = null,
                // 上次殘留 RUNNING 代表進程被殺；標 INTERRUPTED 後才續跑，保留稽核軌跡。
                items = stored.items.map { item ->
                    if (item.status == BatchItemStatus.RUNNING) {
                        item.copy(
                            status = BatchItemStatus.INTERRUPTED,
                            failure = BatchFailure.CANCELLED,
                            updatedAtMillis = now,
                        )
                    } else {
                        item
                    }
                },
                updatedAtMillis = now,
            ),
        )
        runBatch(reopened)
    }

    /**
     * 重驅動單筆（`NEEDS_INPUT` / `BLOCKED` / `FAILED` / `RATE_LIMITED` / `CANCELLED`）。
     *
     * 已到 `Review` 的筆為 no-op。輸入修改不由此處進行：批次 draft 是匯入輸入，
     * 真正可續跑狀態在 workflow 快照（`WorkflowStore`），由 app 端在那裡補輸入。
     */
    suspend fun retryItem(batchId: String, itemId: String): BatchSnapshot = gate.withLock {
        cancelRequested = false
        val stored = store.load(batchId) ?: throw BatchException(BatchFailure.BATCH_NOT_FOUND)
        val index = stored.items.indexOfFirst { it.itemId == itemId }
        if (index < 0) throw BatchException(BatchFailure.ITEM_NOT_FOUND)
        if (stored.status == BatchStatus.COMPLETED) return@withLock stored
        val now = clock.nowMillis()
        val reopened = persist(
            stored.copy(
                status = BatchStatus.RUNNING,
                stopReason = null,
                items = stored.items.mapIndexed { i, item ->
                    if (i == index) {
                        item.copy(
                            status = if (item.status.resumable) item.status else BatchItemStatus.PENDING,
                            failure = null,
                            workflowReason = null,
                            finishedAtMillis = null,
                            updatedAtMillis = now,
                        )
                    } else {
                        item
                    }
                },
                updatedAtMillis = now,
            ),
        )
        val after = runItem(reopened, index)
        finalize(after, rateLimitStopOf(after, index))
    }

    /**
     * 修正尚未開始探測的單筆輸入（`VALIDATION_FAILED` / `PENDING`）。
     *
     * 已發過 request 的筆（`attempts > 0`）拒絕換輸入：此時改輸入必須走 workflow 的
     * `EDIT_INPUT_AND_RERUN_FROM_STEP` 語意（作廢 Review 與下游），不在批次層偷換。
     * 換完不自動續跑，由呼叫端決定 `retryItem` / `resume`。
     */
    suspend fun updateItemDraft(
        batchId: String,
        itemId: String,
        draft: OnboardingSnapshot,
    ): BatchSnapshot = gate.withLock {
        val stored = store.load(batchId) ?: throw BatchException(BatchFailure.BATCH_NOT_FOUND)
        val index = stored.items.indexOfFirst { it.itemId == itemId }
        if (index < 0) throw BatchException(BatchFailure.ITEM_NOT_FOUND)
        if (stored.items[index].attempts > 0) throw BatchException(BatchFailure.WORKFLOW_STATE_REFUSED)
        // 明確輸入不做靜默修改：疑似 secret 直接拒絕整個呼叫，不進狀態。
        if (hasSecret(draft)) throw BatchException(BatchFailure.FORBIDDEN_INPUT_REFUSED)
        val now = clock.nowMillis()
        val updated = stored.mark(index) {
            it.copy(
                draft = draft,
                status = BatchItemStatus.PENDING,
                failure = null,
                workflowReason = null,
                workflowStatus = null,
                rateLimited = false,
                reviewReached = false,
                requestsSpent = 0,
                updatedAtMillis = now,
                finishedAtMillis = null,
            )
        }
        val status = if (updated.items.any { !it.status.terminal }) {
            BatchStatus.RUNNING
        } else {
            BatchStatus.COMPLETED
        }
        persist(updated.copy(status = status, stopReason = null, updatedAtMillis = now))
    }

    suspend fun load(batchId: String): BatchSnapshot? = store.load(batchId)

    private suspend fun runBatch(initial: BatchSnapshot): BatchSnapshot {
        var current = initial
        var stop: BatchFailure? = null
        for (index in current.items.indices) {
            if (cancelRequested) {
                stop = BatchFailure.CANCELLED
                break
            }
            val item = current.items[index]
            if (item.status.terminal) continue
            current = runItem(current, index)
            if (current.items[index].status == BatchItemStatus.RATE_LIMITED) {
                // 429 立停：不排後續筆，已完成結果保留。
                stop = BatchFailure.RATE_LIMITED
                break
            }
        }
        if (stop == null && cancelRequested) stop = BatchFailure.CANCELLED
        return finalize(current, stop)
    }

    private fun rateLimitStopOf(batch: BatchSnapshot, index: Int): BatchFailure? =
        if (batch.items[index].status == BatchItemStatus.RATE_LIMITED) {
            BatchFailure.RATE_LIMITED
        } else {
            null
        }

    private suspend fun finalize(current: BatchSnapshot, stop: BatchFailure?): BatchSnapshot {
        val now = clock.nowMillis()
        val allSettled = current.items.all { it.status.terminal }
        val status = when {
            stop != null -> BatchStatus.PAUSED
            allSettled -> BatchStatus.COMPLETED
            else -> BatchStatus.RUNNING
        }
        return persist(current.copy(status = status, stopReason = stop, updatedAtMillis = now))
    }

    private suspend fun runItem(batch: BatchSnapshot, index: Int): BatchSnapshot {
        val item = batch.items[index]
        val validationFailure = validateItem(item, batch.items)
        if (validationFailure != null) {
            val now = clock.nowMillis()
            return persist(
                batch.mark(index) {
                    // 拒絕即清：approval / commit 殘留與疑似 secret 不進持久化，訊息仍固定不帶 raw。
                    val sanitized = when (validationFailure) {
                        BatchFailure.REVIEW_APPROVAL_REFUSED ->
                            it.draft.copy(reviewApproval = null)
                        BatchFailure.WORKFLOW_STATE_REFUSED ->
                            it.draft.copy(reviewApproval = null, commitReceipt = null)
                        BatchFailure.FORBIDDEN_INPUT_REFUSED -> clearedDraft(it.draft)
                        else -> it.draft
                    }
                    it.copy(
                        draft = sanitized,
                        status = BatchItemStatus.VALIDATION_FAILED,
                        failure = validationFailure,
                        requestsSpent = 0,
                        updatedAtMillis = now,
                        finishedAtMillis = now,
                    )
                }.let { it.copy(updatedAtMillis = now) },
            )
        }
        val now = clock.nowMillis()
        val workflowId = item.workflowId ?: item.draft.workflowId.ifBlank { ids.newId() }
        val started = persist(
            batch.mark(index) {
                it.copy(
                    draft = it.draft.copy(workflowId = workflowId),
                    workflowId = workflowId,
                    status = BatchItemStatus.RUNNING,
                    failure = null,
                    workflowReason = null,
                    workflowStatus = null,
                    rateLimited = false,
                    reviewReached = false,
                    startedAtMillis = if (it.startedAtMillis > 0L) it.startedAtMillis else now,
                    updatedAtMillis = now,
                    finishedAtMillis = null,
                )
            }.let { it.copy(updatedAtMillis = now) },
        )
        return try {
            val summary = drive(started.items[index])
            persist(started.mark(index) { classify(it, summary, clock.nowMillis()) })
        } catch (e: CancellationException) {
            // 取消不算完成：先落盤（NonCancellable），恢復後續跑這一筆。
            val cancelledAt = clock.nowMillis()
            withContext(NonCancellable) {
                val marked = started.mark(index) {
                    it.copy(
                        status = BatchItemStatus.CANCELLED,
                        failure = BatchFailure.CANCELLED,
                        updatedAtMillis = cancelledAt,
                    )
                }
                persist(
                    marked.copy(
                        status = BatchStatus.PAUSED,
                        stopReason = BatchFailure.CANCELLED,
                        updatedAtMillis = cancelledAt,
                    ),
                )
            }
            throw e
        } catch (e: Exception) {
            val failedAt = clock.nowMillis()
            persist(
                started.mark(index) {
                    it.copy(
                        status = BatchItemStatus.FAILED,
                        failure = classifyException(e),
                        updatedAtMillis = failedAt,
                        finishedAtMillis = failedAt,
                    )
                }.let { it.copy(updatedAtMillis = failedAt) },
            )
        }
    }

    /**
     * 逐筆共用 workflow：以 workflow 快照是否存在決定 `start` 還是 `resume`，
     * 兩者都由同一個 [dev.modelatlas.core.onboarding.OnboardingWorkflow] 執行，批次層不自己跑步驟。
     *
     * 用 workflow 快照（而非批次狀態）當判準，進程在「已發 request、尚未記回批次」時被殺也能續跑。
     */
    private suspend fun drive(item: BatchItemState): OnboardingRunSummary {
        val workflowId = item.workflowId
            ?: throw BatchException(BatchFailure.WORKFLOW_STATE_INVALID)
        val workflow = workflows.create(workflowId)
        val draft = item.draft.copy(workflowId = workflowId, reviewApproval = null)
        if (workflow.load(workflowId) == null) return workflow.start(draft)
        return try {
            workflow.resume(workflowId)
        } catch (e: OnboardingNotFoundException) {
            // 快照在崩潰前未落地（尚未發過任何 request）：退回 start，不影響額度保護。
            workflow.start(draft)
        }
    }

    private fun classify(item: BatchItemState, summary: OnboardingRunSummary, now: Long): BatchItemState {
        val review = summary.progress.first { it.step == OnboardingStepId.REVIEW }
        val reviewReached = review.status in REVIEW_REACHED_STATUSES
        val blocking = summary.progress.firstOrNull {
            it.status == OnboardingStepStatus.FAILED || it.status == OnboardingStepStatus.NEEDS_INPUT
        }
        val status: BatchItemStatus
        val failure: BatchFailure?
        when {
            // 防禦性：import 路徑不該走到 COMMITTED（refusing committer 會先擋）。
            summary.status == OnboardingStatus.COMMITTED -> {
                status = BatchItemStatus.FAILED
                failure = BatchFailure.UNEXPECTED_COMMIT
            }
            summary.rateLimited -> {
                status = BatchItemStatus.RATE_LIMITED
                failure = BatchFailure.RATE_LIMITED
            }
            summary.status == OnboardingStatus.ABANDONED -> {
                status = BatchItemStatus.FAILED
                failure = BatchFailure.WORKFLOW_STATE_INVALID
            }
            summary.status == OnboardingStatus.BLOCKED -> {
                status = BatchItemStatus.BLOCKED
                failure = BatchFailure.BLOCKED
            }
            reviewReached -> {
                status = BatchItemStatus.AWAITING_REVIEW
                failure = null
            }
            summary.status == OnboardingStatus.NEEDS_INPUT -> {
                status = BatchItemStatus.NEEDS_INPUT
                failure = BatchFailure.NEEDS_INPUT
            }
            else -> {
                status = BatchItemStatus.FAILED
                failure = BatchFailure.WORKFLOW_ERROR
            }
        }
        return item.copy(
            status = status,
            failure = failure,
            workflowReason = if (status == BatchItemStatus.AWAITING_REVIEW) review.reason else blocking?.reason,
            workflowStatus = summary.status,
            rateLimited = summary.rateLimited,
            reviewReached = reviewReached,
            attempts = item.attempts + 1,
            requestsSpent = summary.totalRequestsSpent,
            updatedAtMillis = now,
            finishedAtMillis = now,
        )
    }

    private fun classifyException(e: Exception): BatchFailure = when (e) {
        is BatchCommitRefusedException -> BatchFailure.UNEXPECTED_COMMIT
        is OnboardingNotFoundException, is OnboardingStateException -> BatchFailure.WORKFLOW_STATE_INVALID
        else -> BatchFailure.WORKFLOW_ERROR
    }

    /**
     * 匯入輸入的本地驗證：全部在發任何 request 之前完成，失敗以固定分類記錄並續其他筆。
     * 不回顯任何欄位值，只回 [BatchFailure]。
     */
    private fun validateItem(item: BatchItemState, all: List<BatchItemState>): BatchFailure? {
        val draft = item.draft
        if (draft.reviewApproval != null) return BatchFailure.REVIEW_APPROVAL_REFUSED
        if (draft.status == OnboardingStatus.COMMITTED ||
            draft.status == OnboardingStatus.ABANDONED ||
            draft.commitReceipt != null
        ) {
            return BatchFailure.WORKFLOW_STATE_REFUSED
        }
        val baseUrl = draft.baseUrl?.trim().orEmpty()
        if (baseUrl.isEmpty()) return BatchFailure.BASE_URL_MISSING
        if (baseUrl.length > limits.maxUrlChars) return BatchFailure.FIELD_TOO_LONG
        if (!isValidHttpsUrl(baseUrl)) return BatchFailure.BASE_URL_INVALID
        val websiteUrl = draft.websiteUrl?.trim().orEmpty()
        if (websiteUrl.isNotEmpty()) {
            if (websiteUrl.length > limits.maxUrlChars) return BatchFailure.FIELD_TOO_LONG
            if (!isValidHttpsUrl(websiteUrl)) return BatchFailure.WEBSITE_URL_INVALID
        }
        for (modelId in draft.manualModelIds + draft.suggestedModelIds) {
            val trimmed = modelId.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length > limits.maxModelIdChars) return BatchFailure.FIELD_TOO_LONG
            if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return BatchFailure.MODEL_ID_INVALID
        }
        val providerName = draft.providerName?.trim().orEmpty()
        if (providerName.isNotEmpty()) {
            if (providerName.length > limits.maxProviderNameChars) return BatchFailure.FIELD_TOO_LONG
            if (providerName.any { it.isISOControl() }) return BatchFailure.NAME_INVALID
        }
        for (ref in safeCredentialIds(draft)) {
            if (ref.length > limits.maxProviderNameChars) return BatchFailure.FIELD_TOO_LONG
            if (ref.any { it.isWhitespace() || it.isISOControl() }) {
                return BatchFailure.SAFE_CREDENTIAL_REF_INVALID
            }
        }
        val earlier = all.filter { it.index < item.index && it.status != BatchItemStatus.VALIDATION_FAILED }
        if (earlier.any { it.itemId == item.itemId }) return BatchFailure.ITEM_ID_DUPLICATE
        // 尾端 `/` 正規化後比對，與 parser 一致；只比固定分類，不回顯 URL。
        val duplicateKey = baseUrl.trimEnd('/')
        if (earlier.any { (it.draft.baseUrl?.trim().orEmpty().trimEnd('/')) == duplicateKey }) {
            return BatchFailure.DUPLICATE_BASE_URL
        }
        if (secretBearingFields(draft).any { BatchSecretGuard.valueLooksLikeSecret(it) }) {
            return BatchFailure.FORBIDDEN_INPUT_REFUSED
        }
        return null
    }

    /**
     * 所有可能帶文字的欄位：自由文字、URL、safe ref 名稱。
     * 命中即視為疑似 secret，該筆不執行且內容不進持久化狀態。
     */
    private fun secretBearingFields(draft: OnboardingSnapshot): List<String> {
        val out = ArrayList<String>()
        draft.baseUrl?.let { out += it }
        draft.websiteUrl?.let { out += it }
        draft.checkinUrl?.let { out += it }
        draft.providerName?.let { out += it }
        draft.group?.let { out += it }
        draft.notes?.let { out += it }
        out += draft.tags
        out += draft.manualModelIds
        out += draft.suggestedModelIds
        out += safeCredentialIds(draft)
        draft.credentials.forEach { ref ->
            out += ref.label
            ref.accountRef?.let { out += it }
        }
        draft.customAuthFields.forEach { out += it.name }
        draft.endpointProfiles.forEach { out += it.url }
        draft.manualPricing.forEach { pricing ->
            out += pricing.note.orEmpty()
            pricing.modelId?.let { out += it }
            pricing.group?.let { out += it }
        }
        return out
    }

    private fun hasSecret(draft: OnboardingSnapshot): Boolean =
        secretBearingFields(draft).any { BatchSecretGuard.valueLooksLikeSecret(it) }

    /**
     * 清空所有可能帶文字的欄位，保留結構性識別（`workflowId`、flow type）。
     * 只在已確認含疑似 secret 時使用：被清空的筆已標 `VALIDATION_FAILED`，
     * 必須由呼叫端用 `updateItemDraft` 重新給乾淨輸入才會再排程。
     */
    private fun clearedDraft(draft: OnboardingSnapshot): OnboardingSnapshot = draft.copy(
        providerName = null,
        baseUrl = null,
        websiteUrl = null,
        group = null,
        checkinUrl = null,
        notes = null,
        tags = emptyList(),
        safeCredentialIds = emptyList(),
        credentials = emptyList(),
        customAuthFields = emptyList(),
        endpointProfiles = emptyList(),
        suggestedModelIds = emptyList(),
        manualModelIds = emptyList(),
        manualPricing = emptyList(),
        detection = null,
        authDecision = null,
        credentialTest = null,
        catalog = null,
        modelTests = null,
        pricingQuota = null,
        reviewItems = null,
        reviewApproval = null,
        results = emptyList(),
        budget = ProbeBudgetState(),
        reservation = null,
        rateLimitedAtMillis = null,
        commitReceipt = null,
    )

    private fun safeCredentialIds(draft: OnboardingSnapshot): List<String> =
        (draft.safeCredentialIds + draft.credentials.map { it.safeCredentialId })

    private fun isValidHttpsUrl(url: String): Boolean = runCatching {
        EndpointPolicy.validate(url)
    }.isSuccess

    /**
     * 落盤前的最後一道閘：疑似 secret、預先核准、已結束 workflow 狀態一律不進 PENDING。
     * 該筆當場標 `VALIDATION_FAILED`，`itemId` 疑似 secret 也換成位置式識別字，
     * approval / receipt 殘留一併清除，絕不自動 commit。
     */
    private fun seed(item: BatchItemDraft, index: Int, now: Long): BatchItemState {
        val idSecret = BatchSecretGuard.valueLooksLikeSecret(item.itemId)
        val draftSecret = hasSecret(item.draft)
        val approvalRefused = item.draft.reviewApproval != null
        val stateRefused = item.draft.status == OnboardingStatus.COMMITTED ||
            item.draft.status == OnboardingStatus.ABANDONED ||
            item.draft.commitReceipt != null
        val refused = idSecret || draftSecret || approvalRefused || stateRefused
        val failure = when {
            idSecret || draftSecret -> BatchFailure.FORBIDDEN_INPUT_REFUSED
            approvalRefused -> BatchFailure.REVIEW_APPROVAL_REFUSED
            stateRefused -> BatchFailure.WORKFLOW_STATE_REFUSED
            else -> null
        }
        val sanitized = when {
            draftSecret -> clearedDraft(item.draft)
            approvalRefused || stateRefused ->
                item.draft.copy(reviewApproval = null, commitReceipt = null)
            else -> item.draft
        }
        return BatchItemState(
            itemId = if (idSecret) "$DEFAULT_ITEM_ID_PREFIX${index + 1}" else {
                item.itemId.trim().ifBlank { "$DEFAULT_ITEM_ID_PREFIX${index + 1}" }
            },
            index = index,
            draft = sanitized,
            status = if (refused) BatchItemStatus.VALIDATION_FAILED else BatchItemStatus.PENDING,
            failure = failure,
            updatedAtMillis = now,
            finishedAtMillis = if (refused) now else null,
        )
    }

    private suspend fun persist(snapshot: BatchSnapshot): BatchSnapshot {
        store.save(snapshot)
        return snapshot
    }

    private fun BatchSnapshot.mark(index: Int, transform: (BatchItemState) -> BatchItemState): BatchSnapshot =
        copy(items = items.mapIndexed { i, item -> if (i == index) transform(item) else item })

    private companion object {
        const val DEFAULT_ITEM_ID_PREFIX = "batch-item-"

        val REVIEW_REACHED_STATUSES = setOf(
            OnboardingStepStatus.NEEDS_INPUT,
            OnboardingStepStatus.SUCCESS,
            OnboardingStepStatus.PARTIAL,
        )
    }
}
