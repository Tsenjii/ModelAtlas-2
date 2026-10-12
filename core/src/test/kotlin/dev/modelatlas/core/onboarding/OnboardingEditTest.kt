package dev.modelatlas.core.onboarding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 編輯後重跑、Advanced 免探測、auth / anonymous commit gate 與 catalog 計數的邊界。
 * 全部用檔內 doubles（既有 test doubles 只用不改），不觸網、不碰 Room。
 */
class OnboardingEditTest {
    private fun workflow(
        store: MemoryWorkflowStore,
        executor: ScriptedStepExecutor,
        committer: RecordingCommitter = RecordingCommitter(),
    ) = OnboardingWorkflow(store, executor, committer, FixedClock())

    /** 重跑時不花額度，讓「重跑幾步」不受預算上限干擾。 */
    private fun freeRerunExecutor(): ScriptedStepExecutor = ScriptedStepExecutor(happyPath()).apply {
        behavior = { _, step, _ -> happyPath()[step]!!.copy(requestsSpent = 0) }
    }

    private fun anonymousAdvancedDraft(anonymousConfirmed: Boolean = false) = quickDraft(
        suggestedModelIds = emptyList(),
        manualModelIds = listOf("manual-a"),
    ).copy(
        flowType = OnboardingFlowType.ADVANCED,
        providerName = "Fixture Anonymous",
        authModeOverride = AuthMode.NONE,
        anonymousConfirmed = anonymousConfirmed,
    )

    @Test
    fun editCannotInjectApprovalReceiptBudgetOrStaleFacts() = runTest {
        val store = MemoryWorkflowStore()
        val committer = RecordingCommitter()
        val workflow = workflow(store, ScriptedStepExecutor(happyPath()), committer)
        workflow.start(quickDraft())
        val before = store.current("wf-fixture")!!
        val spentBefore = before.budget.totalRequests

        val injected = before.copy(
            status = OnboardingStatus.COMMITTED,
            notes = "edit-marker",
            reviewApproval = approval(),
            reviewItems = ReviewItems(modelsFound = 999, modelsVerified = 999),
            commitReceipt = CommitReceipt(commitId = "injected-commit"),
            budget = ProbeBudgetState(totalRequests = 999),
            reservation = BudgetReservation(OnboardingStepId.TEST_MODEL, 9, 5, 5),
            rateLimitedAtMillis = 999_999L,
            detection = DetectionFinding(AdapterType.GENERIC_CUSTOM, EvidenceConfidence.HIGH, basis = "injected"),
            authDecision = AuthDecision(
                authMode = AuthMode.NONE,
                requiresCredential = false,
                credentialIds = listOf("injected-credential"),
            ),
            credentialTest = CredentialTestFinding(httpStatus = 200, reachable = true),
            catalog = CatalogFinding(
                supported = true,
                models = listOf(ModelFinding("injected-model", ModelOrigin.USER_MANUAL, ModelVerification.VERIFIED)),
                totalCount = 999,
            ),
            modelTests = TestModelFinding(
                findings = listOf(ModelFinding("injected-model", ModelOrigin.USER_MANUAL, ModelVerification.VERIFIED)),
                testedTotal = 1,
            ),
            pricingQuota = PricingQuotaFinding(pricingStatus = PriceStatus.VERIFIED_FREE),
            results = emptyList(),
            stepStatuses = emptyMap(),
        )

        workflow.editAndRerun("wf-fixture", injected, OnboardingStepId.REVIEW)
        val afterEdit = store.savedWhere { it.notes == "edit-marker" }.first()
        val afterReview = store.current("wf-fixture")!!

        assertNull("不可注入 approval", afterEdit.reviewApproval)
        assertNull("不可注入 review 內容", afterEdit.reviewItems)
        assertNull("不可注入 commit receipt", afterEdit.commitReceipt)
        assertNull("不可注入持有中的預算保留", afterEdit.reservation)
        assertNull("不可注入 429 停手旗標", afterEdit.rateLimitedAtMillis)
        assertEquals("已花額度不可被呼叫端改寫", spentBefore, afterEdit.budget.totalRequests)
        assertEquals("歷史結果不可被清空", before.results.size, afterEdit.results.size)
        assertEquals(OnboardingStatus.DRAFT, afterEdit.status)
        assertEquals(OnboardingFailureReason.SUPERSEDED, afterEdit.latest(OnboardingStepId.REVIEW)!!.reason)

        assertEquals(spentBefore, afterReview.budget.totalRequests)
        assertNull(afterReview.commitReceipt)
        assertNull(afterReview.reviewApproval)
        assertEquals(
            OnboardingFailureReason.REVIEW_NOT_APPROVED,
            afterReview.latest(OnboardingStepId.REVIEW)!!.reason,
        )
        val review = afterReview.reviewItems!!
        assertEquals("事實只能來自既有探測", AdapterType.OPENAI_COMPATIBLE, review.providerType)
        assertEquals(2, review.modelsFound)
        assertEquals(2, review.modelsVerified)
        assertEquals(PriceStatus.UNKNOWN, review.pricingStatus)
        assertTrue(committer.calls.isEmpty())
    }

    @Test
    fun baseUrlChangeRerunsFromTheEarliestStepAndDropsOldFindings() = runTest {
        val store = MemoryWorkflowStore()
        val executor = freeRerunExecutor()
        val workflow = workflow(store, executor)
        workflow.start(quickDraft())
        val firstPass = executor.order()
        val moved = "https://api.moved.invalid/v1"

        val summary = workflow.editAndRerun(
            workflowId = "wf-fixture",
            inputs = store.current("wf-fixture")!!.copy(baseUrl = moved, notes = "edit-marker"),
            fromStep = OnboardingStepId.PRICING_QUOTA_NOTICE,
        )

        val afterEdit = store.savedWhere { it.baseUrl == moved }.first()
        assertNull("換 base 必須清掉舊 adapter 偵測", afterEdit.detection)
        assertNull(afterEdit.authDecision)
        assertNull(afterEdit.credentialTest)
        assertNull(afterEdit.catalog)
        assertNull(afterEdit.modelTests)
        assertNull(afterEdit.pricingQuota)
        assertNull(afterEdit.reviewApproval)

        assertEquals(
            firstPass + listOf(
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
        assertEquals(2, executor.callCount(OnboardingStepId.INSPECT))
        assertEquals(2, executor.callCount(OnboardingStepId.DETECT_TYPE))
        assertEquals(OnboardingStop.NEEDS_INPUT, summary.stop)
        assertEquals(0, summary.totalRequestsSpent)
    }

    @Test
    fun authChangeRerunsFromAuthStepAndKeepsEarlierFindings() = runTest {
        val store = MemoryWorkflowStore()
        val executor = freeRerunExecutor()
        val workflow = workflow(store, executor)
        workflow.start(quickDraft())

        workflow.editAndRerun(
            workflowId = "wf-fixture",
            inputs = store.current("wf-fixture")!!.copy(
                credentials = listOf(SafeCredentialRef("safe-cred-2", "rotated")),
                safeCredentialIds = listOf("safe-cred-2"),
                notes = "edit-marker",
            ),
            fromStep = OnboardingStepId.REVIEW,
        )

        val afterEdit = store.savedWhere { it.notes == "edit-marker" }.first()
        assertEquals("只換憑證不必重跑 adapter 偵測", AdapterType.OPENAI_COMPATIBLE, afterEdit.detection!!.adapterType)
        assertNull(afterEdit.authDecision)
        assertNull(afterEdit.credentialTest)
        assertNull(afterEdit.reviewApproval)
        assertEquals(listOf("safe-cred-2"), afterEdit.safeCredentialIds)

        val secondPass = executor.order().drop(OnboardingStepId.ORDERED.size - 2)
        assertEquals(1, executor.callCount(OnboardingStepId.INSPECT))
        assertEquals(1, executor.callCount(OnboardingStepId.DETECT_TYPE))
        assertEquals(
            listOf(
                OnboardingStepId.AUTH,
                OnboardingStepId.CREDENTIAL_TEST,
                OnboardingStepId.LIST_MODELS,
                OnboardingStepId.TEST_MODEL,
                OnboardingStepId.PRICING_QUOTA_NOTICE,
            ),
            secondPass,
        )
    }

    @Test
    fun metadataOnlyEditReReviewsWithoutSpendingAnotherRequest() = runTest {
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = workflow(store, executor)
        workflow.start(quickDraft())
        val before = store.current("wf-fixture")!!
        val callsBefore = executor.invocations.toList()
        val spentBefore = before.budget.totalRequests

        val summary = workflow.editAndRerun(
            workflowId = "wf-fixture",
            inputs = before.copy(providerName = "Fixture Renamed", tags = listOf("tier-1"), notes = "只改名"),
            fromStep = OnboardingStepId.REVIEW,
        )

        assertEquals("只改 metadata 不得再發任何探測", callsBefore, executor.invocations.toList())
        assertEquals(0, executor.callCount(OnboardingStepId.REVIEW))
        assertEquals(0, executor.callCount(OnboardingStepId.COMMIT))
        assertEquals(spentBefore, summary.totalRequestsSpent)
        assertEquals(ProbeBudgetLimits.MAX_TOTAL_REQUESTS - spentBefore, summary.requestsRemaining)

        val snapshot = store.current("wf-fixture")!!
        assertEquals("Fixture Renamed", snapshot.providerName)
        assertNotNull(snapshot.reviewItems)
        assertNull(snapshot.reviewApproval)
        assertEquals(
            OnboardingFailureReason.REVIEW_NOT_APPROVED,
            snapshot.latest(OnboardingStepId.REVIEW)!!.reason,
        )
        assertEquals(OnboardingStepStatus.PENDING, snapshot.statusOf(OnboardingStepId.COMMIT))
        assertEquals(OnboardingStatus.NEEDS_INPUT, summary.status)
    }

    @Test
    fun editIsRejectedForCrossFlowLateRestartOrFinishedWorkflow() = runTest {
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = workflow(store, executor)
        workflow.start(quickDraft())
        val snapshot = store.current("wf-fixture")!!
        val callsBefore = executor.invocations.size

        assertTrue(
            runCatching {
                workflow.editAndRerun("wf-fixture", snapshot.copy(flowType = OnboardingFlowType.ADVANCED), OnboardingStepId.REVIEW)
            }.isFailure,
        )
        assertTrue(
            runCatching { workflow.editAndRerun("wf-fixture", snapshot, OnboardingStepId.COMMIT) }.isFailure,
        )
        assertTrue(runCatching { workflow.editAndRerun("wf-missing", snapshot, OnboardingStepId.REVIEW) }.isFailure)
        assertEquals("被拒的 edit 不得留下任何探測", callsBefore, executor.invocations.size)

        workflow.commit("wf-fixture", approval())
        assertTrue(runCatching { workflow.editAndRerun("wf-fixture", snapshot, OnboardingStepId.REVIEW) }.isFailure)
    }

    @Test
    fun advancedStartSkipsNetworkByDefaultAndQuickStillProbes() = runTest {
        val quickStore = MemoryWorkflowStore()
        val quickExecutor = ScriptedStepExecutor(happyPath())
        workflow(quickStore, quickExecutor).start(quickDraft())
        assertTrue("QUICK 預設仍要探測", quickExecutor.callCount(OnboardingStepId.INSPECT) > 0)

        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        val advanced = quickDraft(suggestedModelIds = emptyList(), manualModelIds = listOf("manual-a"))
            .copy(flowType = OnboardingFlowType.ADVANCED, adapterTypeOverride = AdapterType.OPENAI_COMPATIBLE)

        val summary = workflow(store, executor).start(advanced)
        val snapshot = store.current("wf-fixture")!!

        assertTrue("Advanced 預設不得發任何 request", executor.invocations.isEmpty())
        assertEquals(0, summary.totalRequestsSpent)
        assertEquals(ProbeBudgetLimits.MAX_TOTAL_REQUESTS, summary.requestsRemaining)
        for (step in OnboardingStepId.ORDERED.filter { it.ordinal < OnboardingStepId.REVIEW.ordinal }) {
            assertEquals(step.toString(), OnboardingStepStatus.SKIPPED, snapshot.statusOf(step))
            assertEquals(step.toString(), OnboardingFailureReason.USER_SKIPPED, snapshot.latest(step)!!.reason)
        }
        assertEquals(OnboardingStepStatus.NEEDS_INPUT, snapshot.statusOf(OnboardingStepId.REVIEW))
        assertEquals(OnboardingStepStatus.PENDING, snapshot.statusOf(OnboardingStepId.COMMIT))
        assertEquals(OnboardingStatus.NEEDS_INPUT, summary.status)
        assertEquals(OnboardingStop.NEEDS_INPUT, summary.stop)

        val review = snapshot.reviewItems!!
        assertNull("跳過探測不是 0 個模型", review.modelsFound)
        assertNull(review.modelsVerified)
        assertNull(review.modelsTestedTotal)
        assertEquals(AdapterType.OPENAI_COMPATIBLE, review.providerType)
        assertTrue(
            "跳過探測仍要明示未知欄位",
            review.unknownFields.containsAll(
                listOf("catalog", "modelsVerified", "pricing", "quota", "healthOfficial", "credentialTest", "checkinUrl"),
            ),
        )
    }

    @Test
    fun advancedAnonymousCommitIsRejectedUntilConfirmed() = runTest {
        val store = MemoryWorkflowStore()
        val committer = RecordingCommitter()
        val workflow = workflow(store, ScriptedStepExecutor(happyPath()), committer)
        workflow.start(anonymousAdvancedDraft(), runDetection = false)
        assertFalse("未確認的 NONE 不得預設為已確認", store.current("wf-fixture")!!.anonymousConfirmed)

        val rejected = runCatching { workflow.commit("wf-fixture", approval()) }.exceptionOrNull()

        assertTrue(rejected is CommitRejectedException)
        assertEquals(
            listOf(OnboardingFailureReason.POLICY_BLOCKED),
            (rejected as CommitRejectedException).reasons,
        )
        assertTrue(committer.calls.isEmpty())
        val snapshot = store.current("wf-fixture")!!
        assertEquals(
            OnboardingFailureReason.POLICY_BLOCKED,
            snapshot.latest(OnboardingStepId.COMMIT)!!.reason,
        )
        assertEquals(OnboardingStatus.NEEDS_INPUT, snapshot.status)
        assertNull(snapshot.commitReceipt)
    }

    @Test
    fun advancedAnonymousCommitSucceedsAfterExplicitConfirmation() = runTest {
        val store = MemoryWorkflowStore()
        val committer = RecordingCommitter()
        val workflow = workflow(store, ScriptedStepExecutor(happyPath()), committer)
        workflow.start(anonymousAdvancedDraft(anonymousConfirmed = true), runDetection = false)

        val summary = workflow.commit("wf-fixture", approval())

        assertEquals(OnboardingStatus.COMMITTED, summary.status)
        assertEquals(OnboardingStop.COMPLETED, summary.stop)
        assertEquals(listOf("wf-fixture"), committer.calls)
        assertEquals("provider-wf-fixture", store.current("wf-fixture")!!.commitReceipt!!.providerId)
    }

    @Test
    fun authFailedCredentialTestBlocksCommit() = runTest {
        val store = MemoryWorkflowStore()
        val committer = RecordingCommitter()
        val scripted = happyPath().toMutableMap()
        scripted[OnboardingStepId.CREDENTIAL_TEST] = StepResult(
            step = OnboardingStepId.CREDENTIAL_TEST,
            status = OnboardingStepStatus.FAILED,
            reason = OnboardingFailureReason.AUTH_FAILED,
            requestsSpent = 1,
            credentialTest = CredentialTestFinding(httpStatus = 401, reachable = true),
        )
        val workflow = workflow(store, ScriptedStepExecutor(scripted), committer)
        workflow.start(quickDraft())

        val rejected = runCatching { workflow.commit("wf-fixture", approval()) }.exceptionOrNull()

        assertTrue(rejected is CommitRejectedException)
        assertEquals(
            listOf(OnboardingFailureReason.AUTH_FAILED),
            (rejected as CommitRejectedException).reasons,
        )
        assertTrue(committer.calls.isEmpty())
        val snapshot = store.current("wf-fixture")!!
        assertNull(snapshot.commitReceipt)
        assertEquals(OnboardingFailureReason.AUTH_FAILED, snapshot.latest(OnboardingStepId.COMMIT)!!.reason)
        assertNotNull("401 證據仍留在快照", snapshot.credentialTest)
    }

    @Test
    fun missingCredentialDecisionAlsoBlocksCommit() = runTest {
        val store = MemoryWorkflowStore()
        val committer = RecordingCommitter()
        val scripted = happyPath().toMutableMap()
        scripted[OnboardingStepId.AUTH] = StepResult(
            step = OnboardingStepId.AUTH,
            status = OnboardingStepStatus.FAILED,
            reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
        )
        val workflow = workflow(store, ScriptedStepExecutor(scripted), committer)
        workflow.start(quickDraft())
        assertTrue(store.current("wf-fixture")!!.authDecision!!.missingCredential)

        // 缺憑證決策即使後續步驟被略過而跑完 review，仍不得放行 commit。
        workflow.skipStep("wf-fixture", OnboardingStepId.AUTH, "使用者略過 auth")
        assertNotNull(store.current("wf-fixture")!!.reviewItems)

        val rejected = runCatching { workflow.commit("wf-fixture", approval()) }.exceptionOrNull()

        assertTrue(rejected is CommitRejectedException)
        assertEquals(
            listOf(OnboardingFailureReason.AUTH_FAILED),
            (rejected as CommitRejectedException).reasons,
        )
        assertTrue(committer.calls.isEmpty())
    }

    @Test
    fun truncatedCatalogShowsTotalCountAndKeepsUnknownCountNull() = runTest {
        val models = listOf(
            ModelFinding("catalog-model-a", ModelOrigin.CATALOG, ModelVerification.UNVERIFIED),
            ModelFinding("catalog-model-b", ModelOrigin.CATALOG, ModelVerification.UNVERIFIED),
        )

        val truncatedWithTotal = reviewFor(
            CatalogFinding(supported = true, models = models, truncated = true, totalCount = 42),
            MemoryWorkflowStore(),
        )
        assertEquals("截斷但已知總數時要顯示完整數量", 42, truncatedWithTotal.modelsFound)

        val truncatedWithoutTotal = reviewFor(
            CatalogFinding(supported = true, models = models, truncated = true, totalCount = null),
            MemoryWorkflowStore(),
        )
        assertNull("截斷且總數未知時不可拿已載入筆數冒充完整數", truncatedWithoutTotal.modelsFound)

        val complete = reviewFor(CatalogFinding(supported = true, models = models), MemoryWorkflowStore())
        assertEquals(2, complete.modelsFound)

        val unsupported = reviewFor(
            CatalogFinding(supported = false, models = models, truncated = true, totalCount = 42),
            MemoryWorkflowStore(),
        )
        assertNull("不支援列舉時不可顯示為 0 或已知數", unsupported.modelsFound)
        assertTrue(unsupported.unknownFields.contains("catalog"))
    }

    private suspend fun reviewFor(catalog: CatalogFinding, store: MemoryWorkflowStore): ReviewItems {
        val scripted = happyPath().toMutableMap()
        scripted[OnboardingStepId.LIST_MODELS] = StepResult(
            step = OnboardingStepId.LIST_MODELS,
            status = OnboardingStepStatus.SUCCESS,
            requestsSpent = 1,
            catalog = catalog,
        )
        workflow(store, ScriptedStepExecutor(scripted)).start(quickDraft())
        return store.current("wf-fixture")!!.reviewItems!!
    }
}
