package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.HealthState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingWorkflowTest {
    private val store = MemoryWorkflowStore()
    private val committer = RecordingCommitter()

    private fun workflow(executor: ScriptedStepExecutor, budget: ProbeBudget = ProbeBudget.DEFAULT) =
        OnboardingWorkflow(store, executor, committer, FixedClock(), budget)

    @Test
    fun stepOrderAndStatusValuesFollowTheContract() {
        assertEquals(
            listOf(
                "inspect", "detectType", "auth", "credentialTest", "listModels",
                "testModel", "pricingQuotaNotice", "review", "commit",
            ),
            OnboardingStepId.ORDERED.map { it.wireName },
        )
        assertTrue(
            OnboardingStepStatus.values().toSet().containsAll(
                setOf(
                    OnboardingStepStatus.SUCCESS,
                    OnboardingStepStatus.PARTIAL,
                    OnboardingStepStatus.SKIPPED,
                    OnboardingStepStatus.FAILED,
                    OnboardingStepStatus.STALE,
                    OnboardingStepStatus.SUPERSEDED,
                ),
            ),
        )
        assertEquals(
            setOf(
                OnboardingStatus.DRAFT, OnboardingStatus.RUNNING, OnboardingStatus.NEEDS_INPUT,
                OnboardingStatus.BLOCKED, OnboardingStatus.REVIEW, OnboardingStatus.COMMITTED,
                OnboardingStatus.ABANDONED,
            ),
            OnboardingStatus.values().toSet(),
        )
    }

    @Test
    fun probesRunInFixedOrderAndStopAtReview() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val summary = workflow(executor).start(quickDraft())

        assertEquals(
            listOf(
                OnboardingStepId.INSPECT,
                OnboardingStepId.DETECT_TYPE,
                OnboardingStepId.AUTH,
                OnboardingStepId.CREDENTIAL_TEST,
                OnboardingStepId.LIST_MODELS,
                OnboardingStepId.TEST_MODEL,
                OnboardingStepId.PRICING_QUOTA_NOTICE,
            ),
            executor.order(),
        )
        assertEquals(OnboardingStatus.NEEDS_INPUT, summary.status)
        assertEquals(OnboardingStop.NEEDS_INPUT, summary.stop)
        assertEquals(OnboardingStepStatus.NEEDS_INPUT, store.current("wf-fixture")!!.statusOf(OnboardingStepId.REVIEW))
        assertEquals(OnboardingStepStatus.PENDING, store.current("wf-fixture")!!.statusOf(OnboardingStepId.COMMIT))
    }

    @Test
    fun everyStepIsPersistedBeforeAndAfterExecution() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        workflow(executor).start(quickDraft())

        for (step in listOf(OnboardingStepId.INSPECT, OnboardingStepId.TEST_MODEL, OnboardingStepId.PRICING_QUOTA_NOTICE)) {
            assertTrue(
                "缺少 $step 的執行中快照",
                store.savedWhere { it.statusOf(step) == OnboardingStepStatus.RUNNING }.isNotEmpty(),
            )
        }
        assertTrue(store.savedWhere { it.statusOf(OnboardingStepId.TEST_MODEL) == OnboardingStepStatus.SUCCESS }.isNotEmpty())
        assertTrue(store.savedWhere { it.reservation?.step == OnboardingStepId.TEST_MODEL }.isNotEmpty())
        assertNull(store.current("wf-fixture")!!.reservation)
    }

    @Test
    fun resumeDoesNotRerunSuccessfulSteps() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = workflow(executor)
        workflow.start(quickDraft())
        val before = executor.invocations.toList()

        workflow.resume("wf-fixture")

        assertEquals(before, executor.invocations.toList())
    }

    @Test
    fun missingCredentialIsNeedsInputAndNeverBlocked() = runTest {
        val scripted = happyPath().toMutableMap()
        scripted[OnboardingStepId.AUTH] = StepResult(
            step = OnboardingStepId.AUTH,
            status = OnboardingStepStatus.FAILED,
            reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
        )
        val executor = ScriptedStepExecutor(scripted)
        val summary = workflow(executor).start(quickDraft())
        val snapshot = store.current("wf-fixture")!!

        assertEquals(OnboardingStatus.NEEDS_INPUT, summary.status)
        assertEquals(OnboardingStop.NEEDS_INPUT, summary.stop)
        assertEquals(OnboardingStepStatus.NEEDS_INPUT, snapshot.statusOf(OnboardingStepId.AUTH))
        assertEquals(OnboardingStepStatus.SUCCESS, snapshot.statusOf(OnboardingStepId.INSPECT))
        assertEquals(0, executor.callCount(OnboardingStepId.CREDENTIAL_TEST))
        assertEquals(OnboardingStepStatus.PENDING, snapshot.statusOf(OnboardingStepId.CREDENTIAL_TEST))
        assertTrue(snapshot.authDecision!!.missingCredential)
    }

    @Test
    fun noModelsAtAllIsNeedsInputNotAFakeZero() = runTest {
        val scripted = happyPath().toMutableMap()
        scripted[OnboardingStepId.LIST_MODELS] = StepResult(
            step = OnboardingStepId.LIST_MODELS,
            status = OnboardingStepStatus.SKIPPED,
            reason = OnboardingFailureReason.NOT_APPLICABLE,
            catalog = CatalogFinding(supported = false),
        )
        val executor = ScriptedStepExecutor(scripted)
        val summary = workflow(executor).start(quickDraft(suggestedModelIds = emptyList(), manualModelIds = emptyList()))
        val snapshot = store.current("wf-fixture")!!

        assertEquals(OnboardingStatus.NEEDS_INPUT, summary.status)
        assertEquals(OnboardingFailureReason.NO_MODELS_TO_TEST, snapshot.latest(OnboardingStepId.TEST_MODEL)!!.reason)
        assertNull(snapshot.modelTests)
        assertNull(snapshot.reviewItems)
        assertEquals(0, executor.callCount(OnboardingStepId.TEST_MODEL))
    }

    @Test
    fun unknownCountsStayNullInsteadOfZero() = runTest {
        val scripted = happyPath().toMutableMap()
        scripted[OnboardingStepId.LIST_MODELS] = StepResult(
            step = OnboardingStepId.LIST_MODELS,
            status = OnboardingStepStatus.SKIPPED,
            reason = OnboardingFailureReason.NOT_APPLICABLE,
            catalog = CatalogFinding(supported = false),
        )
        scripted[OnboardingStepId.TEST_MODEL] = StepResult(
            step = OnboardingStepId.TEST_MODEL,
            status = OnboardingStepStatus.PARTIAL,
            requestsSpent = 1,
            modelTests = TestModelFinding(
                findings = listOf(ModelFinding("manual-a", ModelOrigin.USER_MANUAL, ModelVerification.FAILED)),
                testedTotal = 1,
            ),
        )
        val executor = ScriptedStepExecutor(scripted)
        workflow(executor).start(quickDraft(suggestedModelIds = emptyList(), manualModelIds = listOf("manual-a")))
        val review = store.current("wf-fixture")!!.reviewItems!!

        assertNull("不支援列舉不可顯示為 0", review.modelsFound)
        assertEquals(0, review.modelsVerified)
        assertEquals(1, review.modelsTestedTotal)
        assertTrue(review.modelsVerifiedIds.isEmpty())
        assertEquals(PriceStatus.UNKNOWN, review.pricingStatus)
        assertEquals(QuotaState.UNKNOWN, review.quotaStatus)
        assertEquals(HealthState.UNKNOWN, review.officialHealth)
        assertTrue(review.unknownFields.contains("catalog"))
        assertTrue(review.unknownFields.contains("pricing"))
        assertTrue(review.unknownFields.contains("quota"))
        assertNull(review.checkinUrl)
    }

    @Test
    fun partialTestKeepsLastGoodFindingsAndRetryKeepsHistory() = runTest {
        val scripted = happyPath().toMutableMap()
        scripted[OnboardingStepId.TEST_MODEL] = StepResult(
            step = OnboardingStepId.TEST_MODEL,
            status = OnboardingStepStatus.PARTIAL,
            requestsSpent = 2,
            modelTests = TestModelFinding(
                findings = listOf(
                    ModelFinding("fixture-model-a", ModelOrigin.CATALOG, ModelVerification.VERIFIED),
                    ModelFinding("fixture-model-b", ModelOrigin.CATALOG, ModelVerification.FAILED),
                ),
                testedTotal = 2,
            ),
        )
        val executor = ScriptedStepExecutor(scripted)
        val workflow = workflow(executor)
        workflow.start(quickDraft())
        assertEquals(1, store.current("wf-fixture")!!.reviewItems!!.modelsVerified)

        workflow.retryStep("wf-fixture", OnboardingStepId.TEST_MODEL)
        val snapshot = store.current("wf-fixture")!!
        val history = snapshot.historyOf(OnboardingStepId.TEST_MODEL)

        assertEquals(2, executor.callCount(OnboardingStepId.TEST_MODEL))
        assertEquals(2, history.size)
        assertEquals(OnboardingStepStatus.SUPERSEDED, history[0].status)
        assertEquals(OnboardingFailureReason.SUPERSEDED, history[0].reason)
        assertEquals(2, history[1].attempt)
        assertTrue(snapshot.historyOf(OnboardingStepId.PRICING_QUOTA_NOTICE).any { it.status == OnboardingStepStatus.STALE })
        assertNull("上游重跑必須作廢 review approval", snapshot.reviewApproval)
    }

    @Test
    fun rateLimitStopsEveryLaterProbe() = runTest {
        val scripted = happyPath().toMutableMap()
        scripted[OnboardingStepId.CREDENTIAL_TEST] = StepResult(
            step = OnboardingStepId.CREDENTIAL_TEST,
            status = OnboardingStepStatus.FAILED,
            reason = OnboardingFailureReason.RATE_LIMITED,
            credentialTest = CredentialTestFinding(httpStatus = 429, reachable = true),
        )
        val executor = ScriptedStepExecutor(scripted)
        val summary = workflow(executor).start(quickDraft())
        val snapshot = store.current("wf-fixture")!!

        assertTrue(summary.rateLimited)
        assertEquals(0, executor.callCount(OnboardingStepId.LIST_MODELS))
        assertEquals(0, executor.callCount(OnboardingStepId.TEST_MODEL))
        assertEquals(0, executor.callCount(OnboardingStepId.PRICING_QUOTA_NOTICE))
        assertEquals(OnboardingStepStatus.SKIPPED, snapshot.statusOf(OnboardingStepId.LIST_MODELS))
        assertEquals(OnboardingFailureReason.RATE_LIMITED_STOP, snapshot.latest(OnboardingStepId.LIST_MODELS)!!.reason)
        assertEquals(429, snapshot.credentialTest!!.httpStatus)
    }

    @Test
    fun inspectFailureBlocksAndRetryReopensDownstream() = runTest {
        var inspectFails = true
        val executor = ScriptedStepExecutor(happyPath())
        executor.behavior = { _, step, grant ->
            val scriptedResult = happyPath()[step]!!
            if (step == OnboardingStepId.INSPECT && inspectFails) {
                StepResult(
                    step = OnboardingStepId.INSPECT,
                    status = OnboardingStepStatus.FAILED,
                    reason = OnboardingFailureReason.NETWORK_UNREACHABLE,
                )
            } else {
                scriptedResult.copy(requestsSpent = scriptedResult.requestsSpent.coerceAtMost(grant.requestLimit))
            }
        }
        val workflow = workflow(executor)
        val blocked = workflow.start(quickDraft())
        val blockedSnapshot = store.current("wf-fixture")!!

        assertEquals(OnboardingStatus.BLOCKED, blocked.status)
        assertEquals(OnboardingStepStatus.FAILED, blockedSnapshot.statusOf(OnboardingStepId.INSPECT))
        assertEquals(OnboardingStepStatus.SKIPPED, blockedSnapshot.statusOf(OnboardingStepId.CREDENTIAL_TEST))
        assertEquals(
            OnboardingFailureReason.NETWORK_UNREACHABLE,
            blockedSnapshot.latest(OnboardingStepId.TEST_MODEL)!!.reason,
        )
        assertEquals(0, executor.callCount(OnboardingStepId.TEST_MODEL))

        inspectFails = false
        workflow.retryStep("wf-fixture", OnboardingStepId.INSPECT)
        val recovered = store.current("wf-fixture")!!

        assertEquals(OnboardingStatus.NEEDS_INPUT, store.current("wf-fixture")!!.status)
        assertEquals(OnboardingStepStatus.SUCCESS, recovered.statusOf(OnboardingStepId.INSPECT))
        assertEquals(1, executor.callCount(OnboardingStepId.TEST_MODEL))
        assertEquals(
            OnboardingFailureReason.SUPERSEDED,
            recovered.historyOf(OnboardingStepId.INSPECT).first().reason,
        )
    }

    @Test
    fun userSkipKeepsWorkflowRunnable() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = workflow(executor)
        workflow.start(quickDraft())

        val summary = workflow.skipStep("wf-fixture", OnboardingStepId.LIST_MODELS, "使用者略過")
        val snapshot = store.current("wf-fixture")!!

        assertEquals(OnboardingStepStatus.SKIPPED, snapshot.statusOf(OnboardingStepId.LIST_MODELS))
        assertEquals(OnboardingFailureReason.USER_SKIPPED, snapshot.latest(OnboardingStepId.LIST_MODELS)!!.reason)
        assertEquals(OnboardingStatus.NEEDS_INPUT, summary.status)
        assertNotNull(snapshot.catalog)
    }

    @Test
    fun reviewAndCommitCannotBeSkipped() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = workflow(executor)
        workflow.start(quickDraft())

        assertTrue(runCatching { workflow.skipStep("wf-fixture", OnboardingStepId.REVIEW) }.isFailure)
        assertTrue(runCatching { workflow.skipStep("wf-fixture", OnboardingStepId.COMMIT) }.isFailure)
        assertTrue(runCatching { workflow.retryStep("wf-missing", OnboardingStepId.INSPECT) }.isFailure)
    }

    @Test
    fun commitNeedsApprovalAndModelsButNotSuccessfulTests() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = workflow(executor)
        workflow.start(quickDraft())

        val rejected = runCatching { workflow.commit("wf-fixture") }.exceptionOrNull()
        assertTrue(rejected is CommitRejectedException)
        assertEquals(
            listOf(OnboardingFailureReason.REVIEW_NOT_APPROVED),
            (rejected as CommitRejectedException).reasons,
        )
        assertEquals(OnboardingStatus.NEEDS_INPUT, store.current("wf-fixture")!!.status)
        assertTrue(committer.calls.isEmpty())

        val committed = workflow.commit("wf-fixture", approval())
        assertEquals(OnboardingStatus.COMMITTED, committed.status)
        assertEquals(OnboardingStop.COMPLETED, committed.stop)
        assertEquals(1, committer.calls.size)
        assertEquals("provider-wf-fixture", store.current("wf-fixture")!!.commitReceipt!!.providerId)
    }

    @Test
    fun commitSucceedsEvenWhenEveryModelTestFailed() = runTest {
        val scripted = happyPath(models = listOf("manual-a")).toMutableMap()
        scripted[OnboardingStepId.TEST_MODEL] = StepResult(
            step = OnboardingStepId.TEST_MODEL,
            status = OnboardingStepStatus.FAILED,
            reason = OnboardingFailureReason.MODEL_NOT_FOUND,
            requestsSpent = 1,
            modelTests = TestModelFinding(
                findings = listOf(ModelFinding("manual-a", ModelOrigin.USER_MANUAL, ModelVerification.FAILED)),
                testedTotal = 1,
            ),
        )
        val executor = ScriptedStepExecutor(scripted)
        val workflow = workflow(executor)
        workflow.start(quickDraft(suggestedModelIds = emptyList(), manualModelIds = listOf("manual-a")))
        assertEquals(0, store.current("wf-fixture")!!.reviewItems!!.modelsVerified)

        val committed = workflow.commit("wf-fixture", approval())

        assertEquals(OnboardingStatus.COMMITTED, committed.status)
        assertEquals(1, committer.calls.size)
    }

    @Test
    fun commitIsRejectedWhenNoModelSurvives() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = workflow(executor)
        workflow.start(quickDraft())
        store.put(
            store.current("wf-fixture")!!.copy(
                manualModelIds = emptyList(),
                catalog = CatalogFinding(supported = true, models = emptyList()),
                reviewApproval = approval(),
            ),
        )

        val rejected = runCatching { workflow.commit("wf-fixture") }.exceptionOrNull()

        assertTrue(rejected is CommitRejectedException)
        assertEquals(
            listOf(OnboardingFailureReason.NO_MODELS_TO_TEST),
            (rejected as CommitRejectedException).reasons,
        )
        assertTrue(committer.calls.isEmpty())
        assertEquals(OnboardingStatus.NEEDS_INPUT, store.current("wf-fixture")!!.status)
    }

    @Test
    fun commitReplayAndRestartAreIdempotent() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val wf = workflow(executor)
        wf.start(quickDraft())
        wf.commit("wf-fixture", approval())

        val replay = wf.commit("wf-fixture", approval())
        assertEquals(OnboardingStatus.COMMITTED, replay.status)
        assertEquals(1, committer.calls.size)

        val afterRestart = workflow(executor).commit("wf-fixture", approval())
        assertEquals(OnboardingStatus.COMMITTED, afterRestart.status)
        assertEquals(1, committer.calls.size)
    }

    @Test
    fun failedCommitCanBeReplayedAndStaysIdempotent() = runTest {
        val flaky = RecordingCommitter(failures = 1)
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = OnboardingWorkflow(store, executor, flaky, FixedClock())
        workflow.start(quickDraft())
        workflow.commit("wf-fixture", approval())
        val afterFailure = store.current("wf-fixture")!!

        assertEquals(OnboardingStepStatus.FAILED, afterFailure.statusOf(OnboardingStepId.COMMIT))
        assertNull(afterFailure.commitReceipt)
        assertEquals(1, flaky.calls.size)

        val retried = workflow.retryStep("wf-fixture", OnboardingStepId.COMMIT)
        assertEquals(OnboardingStatus.COMMITTED, retried.status)
        assertEquals(2, flaky.calls.size)

        assertTrue(runCatching { workflow.retryStep("wf-fixture", OnboardingStepId.COMMIT) }.isFailure)
        assertEquals(OnboardingStatus.COMMITTED, workflow.commit("wf-fixture", approval()).status)
        assertEquals(2, flaky.calls.size)
    }

    @Test
    fun abandonKeepsFinishedStepsAndBlocksLaterCommit() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = workflow(executor)
        workflow.start(quickDraft())

        val summary = workflow.abandon("wf-fixture", "使用者放棄")
        val snapshot = store.current("wf-fixture")!!

        assertEquals(OnboardingStatus.ABANDONED, summary.status)
        assertEquals(OnboardingStatus.ABANDONED, snapshot.status)
        assertEquals(OnboardingStepStatus.SUCCESS, snapshot.statusOf(OnboardingStepId.TEST_MODEL))
        assertEquals(OnboardingFailureReason.ABANDONED, snapshot.latest(OnboardingStepId.REVIEW)!!.reason)
        assertTrue(runCatching { workflow.commit("wf-fixture", approval()) }.isFailure)
        assertEquals(OnboardingStatus.ABANDONED, workflow.resume("wf-fixture").status)
    }

    @Test
    fun advancedDraftKeepsMultipleCredentialsAndEndpointsWithoutSecretValues() = runTest {
        val executor = ScriptedStepExecutor(happyPath())
        val advanced = quickDraft(suggestedModelIds = emptyList(), manualModelIds = listOf("manual-a"))
            .copy(
                flowType = OnboardingFlowType.ADVANCED,
                credentials = listOf(
                    SafeCredentialRef("safe-cred-1", "primary"),
                    SafeCredentialRef("safe-cred-2", "backup", role = CredentialRole.ACCOUNT, accountRef = "account-1"),
                ),
                safeCredentialIds = listOf("safe-cred-1", "safe-cred-2"),
                customAuthFields = listOf(CustomAuthFieldRef(CredentialLocation.HEADER, "X-Api-Key")),
                endpointProfiles = listOf(
                    EndpointRef(EndpointType.CHAT_COMPLETIONS, FIXTURE_BASE_URL, "CHAT_COMPLETIONS"),
                ),
                manualPricing = listOf(
                    ManualPricingInput(
                        modelId = "manual-a",
                        decimalAmount = "0.001250",
                        currency = "USD",
                        perUnit = PricePerUnit.INPUT_TOKEN,
                    ),
                ),
                manualQuotaState = QuotaState.UNKNOWN,
            )

        val summary = workflow(executor).start(advanced)
        val snapshot = store.current("wf-fixture")!!

        // ADVANCED 預設 runDetection=false：探測步 SKIPPED，pricingQuota 維持 null，
        // Review 仍會建立，quota 由 manualQuotaState 落為 UNKNOWN。
        assertEquals(OnboardingStatus.NEEDS_INPUT, summary.status)
        assertEquals(listOf("primary", "backup"), snapshot.credentials.map { it.label })
        assertNotNull(snapshot.reviewItems)
        assertEquals(listOf("X-Api-Key"), snapshot.reviewItems!!.auth.customAuthFieldNames)
        assertNull(snapshot.pricingQuota)
        assertEquals(QuotaState.UNKNOWN, snapshot.reviewItems!!.quotaStatus)
        assertEquals("0.001250", snapshot.manualPricing.single().decimalAmount)
    }
}
