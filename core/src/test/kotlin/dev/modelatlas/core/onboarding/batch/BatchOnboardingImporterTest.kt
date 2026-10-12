package dev.modelatlas.core.onboarding.batch

import dev.modelatlas.core.onboarding.Committer
import dev.modelatlas.core.onboarding.FixedClock
import dev.modelatlas.core.onboarding.MemoryWorkflowStore
import dev.modelatlas.core.onboarding.OnboardingFailureReason
import dev.modelatlas.core.onboarding.OnboardingStepId
import dev.modelatlas.core.onboarding.OnboardingStepStatus
import dev.modelatlas.core.onboarding.OnboardingStatus
import dev.modelatlas.core.onboarding.OnboardingWorkflow
import dev.modelatlas.core.onboarding.RecordingCommitter
import dev.modelatlas.core.onboarding.StepExecutor
import dev.modelatlas.core.onboarding.StepResult
import dev.modelatlas.core.onboarding.WorkflowStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批次匯入執行器的邊界：逐筆跑到 Review、單一執行、可恢復、429 停排、逐筆分類、絕不自動 commit。 */
class BatchOnboardingImporterTest {

    @Test
    fun runsEveryItemToReviewSequentiallyWithoutApprovingOrCommitting() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val committer = RecordingCommitter()
        val importer = importer(batchStore, workflowStore, executor, committer)

        val result = importer.start(batchOf(batchDraft("a"), batchDraft("b"), batchDraft("c")))

        assertEquals(BatchStatus.COMPLETED, result.status)
        assertNull(result.stopReason)
        assertEquals(3, result.countOf(BatchItemStatus.AWAITING_REVIEW))
        assertEquals(1, executor.maxInFlight)
        assertEquals(listOf("wf-a", "wf-b", "wf-c"), executor.executions.map { it.first }.distinct())
        assertTrue(committer.calls.isEmpty())
        assertTrue(executor.executions.none { it.second == OnboardingStepId.COMMIT })

        for (item in result.items) {
            assertNull(item.failure)
            assertTrue(item.reviewReached)
            assertEquals(OnboardingStatus.NEEDS_INPUT, item.workflowStatus)
            assertEquals(OnboardingFailureReason.REVIEW_NOT_APPROVED, item.workflowReason)
            assertEquals(PROBE_STEPS.size, executor.callCountFor(item.workflowId!!))
            val snapshot = workflowStore.current(item.workflowId!!)
            assertNotNull(snapshot)
            assertNotNull(snapshot!!.reviewItems)
            assertNull(snapshot.reviewApproval)
            assertEquals(OnboardingStepStatus.NEEDS_INPUT, snapshot.stepStatuses[OnboardingStepId.REVIEW])
            assertEquals(OnboardingStepStatus.PENDING, snapshot.stepStatuses[OnboardingStepId.COMMIT])
            assertEquals(9, item.requestsSpent)
        }
    }

    @Test
    fun classifiesPerItemValidationFailuresAndKeepsRunningTheRest() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val committer = RecordingCommitter()
        val importer = importer(batchStore, workflowStore, executor, committer)

        val result = importer.start(
            batchOf(
                batchDraft("a"),
                batchDraft("b", baseUrl = "http://b.fixture.invalid/v1"),
                batchDraft("c") { it.copy(notes = LEAKY_SECRET_FIXTURE) },
                batchDraft("d"),
                batchDraft("e", baseUrl = "https://a.fixture.invalid/v1"),
            ),
        )

        assertEquals(BatchFailure.BASE_URL_INVALID, result.itemOf("b")!!.failure)
        assertEquals(BatchItemStatus.VALIDATION_FAILED, result.itemOf("b")!!.status)
        assertEquals(BatchFailure.FORBIDDEN_INPUT_REFUSED, result.itemOf("c")!!.failure)
        assertEquals(BatchItemStatus.VALIDATION_FAILED, result.itemOf("c")!!.status)
        assertEquals(BatchFailure.DUPLICATE_BASE_URL, result.itemOf("e")!!.failure)
        assertEquals(BatchItemStatus.VALIDATION_FAILED, result.itemOf("e")!!.status)
        assertEquals(BatchItemStatus.AWAITING_REVIEW, result.itemOf("a")!!.status)
        assertEquals(BatchItemStatus.AWAITING_REVIEW, result.itemOf("d")!!.status)
        assertEquals(BatchStatus.COMPLETED, result.status)
        assertEquals(0, executor.callCountFor("wf-b"))
        assertEquals(0, executor.callCountFor("wf-e"))
        assertEquals(0, result.itemOf("b")!!.requestsSpent)
        assertNull(workflowStore.current("wf-b"))
        assertNull(workflowStore.current("wf-e"))
        assertTrue(committer.calls.isEmpty())
    }

    @Test
    fun refusesSecretBearingItemWithoutPersistingTheSecret() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val importer = importer(batchStore, workflowStore, executor, RecordingCommitter())

        val result = importer.start(
            batchOf(
                batchDraft("a") { it.copy(notes = LEAKY_SECRET_FIXTURE) },
                batchDraft("b"),
            ),
        )

        val refused = result.itemOf("a")!!
        assertEquals(BatchItemStatus.VALIDATION_FAILED, refused.status)
        assertEquals(BatchFailure.FORBIDDEN_INPUT_REFUSED, refused.failure)
        assertNull(refused.draft.notes)
        assertNull(workflowStore.current("wf-a"))
        assertEquals(0, executor.callCountFor("wf-a"))
        assertEquals(BatchItemStatus.AWAITING_REVIEW, result.itemOf("b")!!.status)
        assertFalse(batchStore.saves.toString().contains(LEAKY_SECRET_FIXTURE))
    }

    @Test
    fun refusesPreApprovedItemAndRefusesCommitEntirely() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val committer = RecordingCommitter()
        val importer = importer(batchStore, workflowStore, executor, committer)

        val result = importer.start(batchOf(preApprovedDraft("a"), batchDraft("b")))

        assertEquals(BatchFailure.REVIEW_APPROVAL_REFUSED, result.itemOf("a")!!.failure)
        assertNull(result.itemOf("a")!!.draft.reviewApproval)
        assertEquals(0, executor.callCountFor("wf-a"))
        assertTrue(committer.calls.isEmpty())
        assertEquals(BatchItemStatus.AWAITING_REVIEW, result.itemOf("b")!!.status)
    }

    @Test
    fun workflowLevelNeedsInputIsClassifiedAndDoesNotStopTheBatch() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val importer = importer(batchStore, workflowStore, executor, RecordingCommitter())
        executor.handler = { workflowId, step, _ ->
            if (workflowId == "wf-b" && step == OnboardingStepId.AUTH) {
                StepResult(
                    step = step,
                    status = OnboardingStepStatus.NEEDS_INPUT,
                    reason = OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
                    requestsSpent = 0,
                )
            } else {
                null
            }
        }

        val result = importer.start(batchOf(batchDraft("a"), batchDraft("b"), batchDraft("c")))

        val stuck = result.itemOf("b")!!
        assertEquals(BatchItemStatus.NEEDS_INPUT, stuck.status)
        assertEquals(BatchFailure.NEEDS_INPUT, stuck.failure)
        assertEquals(OnboardingFailureReason.AUTH_MISSING_CREDENTIAL, stuck.workflowReason)
        assertFalse(stuck.reviewReached)
        assertEquals(
            listOf(OnboardingStepId.INSPECT, OnboardingStepId.DETECT_TYPE, OnboardingStepId.AUTH),
            executor.executions.filter { it.first == "wf-b" }.map { it.second },
        )
        assertEquals(BatchItemStatus.AWAITING_REVIEW, result.itemOf("a")!!.status)
        assertEquals(BatchItemStatus.AWAITING_REVIEW, result.itemOf("c")!!.status)
        assertEquals(BatchStatus.COMPLETED, result.status)
    }

    @Test
    fun rateLimitStopsSchedulingAndResumeContinuesOnlyTheRemainingItems() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val importer = importer(batchStore, workflowStore, executor, RecordingCommitter())
        executor.handler = { workflowId, step, grant ->
            if (workflowId == "wf-a" && step == OnboardingStepId.TEST_MODEL) {
                StepResult(
                    step = step,
                    status = OnboardingStepStatus.PARTIAL,
                    reason = OnboardingFailureReason.RATE_LIMITED,
                    requestsSpent = 1,
                    requestLimit = grant.requestLimit,
                )
            } else {
                null
            }
        }

        val stopped = importer.start(batchOf(batchDraft("a"), batchDraft("b")))

        assertEquals(BatchStatus.PAUSED, stopped.status)
        assertEquals(BatchFailure.RATE_LIMITED, stopped.stopReason)
        assertEquals(BatchItemStatus.RATE_LIMITED, stopped.itemOf("a")!!.status)
        assertTrue(stopped.itemOf("a")!!.rateLimited)
        assertEquals(BatchItemStatus.PENDING, stopped.itemOf("b")!!.status)
        assertNull(workflowStore.current("wf-b"))
        assertEquals(0, executor.callCountFor("wf-b"))

        val callsBeforeResume = executor.callCountFor("wf-a")
        val resumed = importer.resume("batch-1")

        assertEquals(BatchStatus.COMPLETED, resumed.status)
        assertNull(resumed.stopReason)
        assertEquals(callsBeforeResume, executor.callCountFor("wf-a"))
        assertEquals(PROBE_STEPS.size, executor.callCountFor("wf-b"))
        // 429 停排：rate-limited 筆保留不重跑，只續跑剩餘筆。
        assertEquals(BatchItemStatus.RATE_LIMITED, resumed.itemOf("a")!!.status)
        assertEquals(BatchFailure.RATE_LIMITED, resumed.itemOf("a")!!.failure)
        assertEquals(BatchItemStatus.AWAITING_REVIEW, resumed.itemOf("b")!!.status)
        assertEquals(1, resumed.countOf(BatchItemStatus.AWAITING_REVIEW))
    }

    @Test
    fun cooperativeCancelStopsSchedulingAndResumeKeepsCompletedItems() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val importer = importer(batchStore, workflowStore, executor, RecordingCommitter())
        executor.handler = { workflowId, step, _ ->
            if (workflowId == "wf-b" && step == OnboardingStepId.TEST_MODEL) importer.requestCancel()
            null
        }

        val paused = importer.start(batchOf(batchDraft("a"), batchDraft("b"), batchDraft("c")))

        assertEquals(BatchStatus.PAUSED, paused.status)
        assertEquals(BatchFailure.CANCELLED, paused.stopReason)
        assertEquals(BatchItemStatus.AWAITING_REVIEW, paused.itemOf("a")!!.status)
        assertEquals(BatchItemStatus.AWAITING_REVIEW, paused.itemOf("b")!!.status)
        assertEquals(BatchItemStatus.PENDING, paused.itemOf("c")!!.status)
        val aCalls = executor.callCountFor("wf-a")
        val bCalls = executor.callCountFor("wf-b")

        val resumed = importer.resume("batch-1")

        assertEquals(BatchStatus.COMPLETED, resumed.status)
        assertEquals(aCalls, executor.callCountFor("wf-a"))
        assertEquals(bCalls, executor.callCountFor("wf-b"))
        assertEquals(PROBE_STEPS.size, executor.callCountFor("wf-c"))
        assertEquals(3, resumed.countOf(BatchItemStatus.AWAITING_REVIEW))
    }

    @Test
    fun cancellationMidItemIsPersistedAndRestartResumesOnlyThatItem() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        executor.blockOn = "wf-b" to OnboardingStepId.TEST_MODEL
        val importer = importer(batchStore, workflowStore, executor, RecordingCommitter())

        val job = launch { importer.start(batchOf(batchDraft("a"), batchDraft("b"), batchDraft("c"))) }
        executor.blocked.await()
        job.cancelAndJoin()

        val persisted = batchStore.current("batch-1")!!
        assertEquals(BatchStatus.PAUSED, persisted.status)
        assertEquals(BatchFailure.CANCELLED, persisted.stopReason)
        assertEquals(BatchItemStatus.AWAITING_REVIEW, persisted.itemOf("a")!!.status)
        assertEquals(BatchItemStatus.CANCELLED, persisted.itemOf("b")!!.status)
        assertEquals(BatchItemStatus.PENDING, persisted.itemOf("c")!!.status)
        assertEquals(
            OnboardingFailureReason.CANCELLED,
            workflowStore.current("wf-b")!!.results.last { it.step == OnboardingStepId.TEST_MODEL }.reason,
        )

        // 進程重啟：狀態與 workflow 快照都從持久化層讀回，換新的執行器。
        val restartedExecutor = BatchScriptedExecutor()
        val restarted = importer(
            MemoryBatchStateStore().apply { put(persisted) },
            copyOf(workflowStore),
            restartedExecutor,
            RecordingCommitter(),
        )

        val resumed = restarted.resume("batch-1")

        assertEquals(BatchStatus.COMPLETED, resumed.status)
        assertEquals(0, restartedExecutor.callCountFor("wf-a"))
        assertEquals(PROBE_STEPS.size, restartedExecutor.callCountFor("wf-c"))
        // 被取消的那筆從中斷處續跑：已完成的 INSPECT..LIST 不重跑，只重跑 TEST_MODEL + PRICING。
        assertEquals(1, restartedExecutor.callCount("wf-b", OnboardingStepId.TEST_MODEL))
        assertEquals(1, restartedExecutor.callCount("wf-b", OnboardingStepId.PRICING_QUOTA_NOTICE))
        assertEquals(2, restartedExecutor.callCountFor("wf-b"))
        assertEquals(3, resumed.countOf(BatchItemStatus.AWAITING_REVIEW))
    }

    @Test
    fun concurrentRunsStaySingleFlightAndNeverProbeInParallel() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val committer = RecordingCommitter()
        val importer = importer(batchStore, workflowStore, executor, committer)
        val gate = CompletableDeferred<Unit>()
        executor.blockOn = "wf-a" to OnboardingStepId.TEST_MODEL
        executor.gate = gate

        val first = launch { importer.start(batchOf(batchDraft("a"), batchDraft("b"))) }
        executor.blocked.await()
        val second = launch { importer.resume("batch-1") }
        runCurrent()

        assertFalse(second.isCompleted)
        assertEquals(1, executor.maxInFlight)
        assertEquals(0, executor.callCountFor("wf-b"))

        gate.complete(Unit)
        first.join()
        second.join()

        val finished = batchStore.current("batch-1")!!
        assertEquals(BatchStatus.COMPLETED, finished.status)
        assertEquals(2, finished.countOf(BatchItemStatus.AWAITING_REVIEW))
        assertEquals(1, executor.maxInFlight)
        assertEquals(executor.executions.size, executor.executions.toSet().size)
        assertTrue(committer.calls.isEmpty())
    }

    @Test
    fun batchStateRoundTripsAndRestartResumesWithoutProbingAgain() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val importer = importer(batchStore, workflowStore, executor, RecordingCommitter())
        val finished = importer.start(batchOf(batchDraft("a"), batchDraft("b")))

        val encoded = BatchJson.encodeToString(BatchSnapshot.serializer(), finished)
        assertFalse(encoded.contains(LEAKY_SECRET_FIXTURE))
        assertTrue(encoded.contains("wf-a"))
        assertTrue(encoded.contains("AWAITING_REVIEW"))
        val decoded = BatchJson.decodeFromString(BatchSnapshot.serializer(), encoded)
        assertEquals(finished, decoded)

        val restartedStore = MemoryBatchStateStore().apply { put(decoded) }
        val restartedExecutor = BatchScriptedExecutor()
        val restarted = importer(restartedStore, copyOf(workflowStore), restartedExecutor, RecordingCommitter())

        assertEquals(listOf("batch-1"), restartedStore.listBatchIds())
        val resumed = restarted.resume("batch-1")

        assertEquals(BatchStatus.COMPLETED, resumed.status)
        assertEquals(0, restartedExecutor.executions.size)
        assertEquals(2, resumed.countOf(BatchItemStatus.AWAITING_REVIEW))
        assertEquals("wf-a", resumed.itemOf("a")!!.workflowId)
    }

    @Test
    fun fixingAVerificationFailureBeforeAnyProbeAllowsRetryOfThatItem() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val importer = importer(batchStore, workflowStore, executor, RecordingCommitter())
        val first = importer.start(batchOf(batchDraft("a"), batchDraft("b", baseUrl = "not-a-url")))

        assertEquals(BatchFailure.BASE_URL_INVALID, first.itemOf("b")!!.failure)
        val fixed = batchDraft("b", baseUrl = "https://b2.fixture.invalid/v1").draft

        val updated = importer.updateItemDraft("batch-1", "b", fixed)
        assertEquals(BatchItemStatus.PENDING, updated.itemOf("b")!!.status)
        assertNull(updated.itemOf("b")!!.failure)

        val retried = importer.retryItem("batch-1", "b")
        assertEquals(BatchItemStatus.AWAITING_REVIEW, retried.itemOf("b")!!.status)
        assertEquals(BatchStatus.COMPLETED, retried.status)
        assertEquals(PROBE_STEPS.size, executor.callCountFor("wf-b"))

        val afterProbe = retried.itemOf("a")!!
        val refused = runCatching {
            importer.updateItemDraft("batch-1", afterProbe.itemId, fixed)
        }.exceptionOrNull()
        assertTrue(refused is BatchException)
        assertEquals(BatchFailure.WORKFLOW_STATE_REFUSED, (refused as BatchException).failure)
    }

    @Test
    fun rejectsEmptyAndOversizedBatchesBeforeAnyProbe() = runTest {
        val batchStore = MemoryBatchStateStore()
        val workflowStore = MemoryWorkflowStore()
        val executor = BatchScriptedExecutor()
        val importer = importer(batchStore, workflowStore, executor, RecordingCommitter())

        val empty = runCatching { importer.start(batchOf()) }.exceptionOrNull()
        assertTrue(empty is BatchException)
        assertEquals(BatchFailure.EMPTY_BATCH, (empty as BatchException).failure)

        val tooMany = runCatching {
            importer(batchStore, workflowStore, executor, RecordingCommitter(), BatchLimits(maxItems = 1))
                .start(batchOf(batchDraft("a"), batchDraft("b")))
        }.exceptionOrNull()
        assertTrue(tooMany is BatchException)
        assertEquals(BatchFailure.ITEM_LIMIT_EXCEEDED, (tooMany as BatchException).failure)

        val missing = runCatching { importer.resume("nope") }.exceptionOrNull()
        assertTrue(missing is BatchException)
        assertEquals(BatchFailure.BATCH_NOT_FOUND, (missing as BatchException).failure)
        assertEquals(0, executor.executions.size)
        assertTrue(batchStore.saves.isEmpty())
    }

    @Test
    fun defaultFactoryNeverCommits() = runTest {
        val committer = BatchNoCommitCommitter()
        val failed = runCatching {
            committer.commit(batchDraft("a").draft.copy(workflowId = "wf-a"))
        }.exceptionOrNull()
        assertTrue(failed is BatchCommitRefusedException)
        assertFalse(failed!!.message!!.contains("wf-a"))
    }

    private fun importer(
        batchStore: BatchStateStore,
        workflowStore: WorkflowStore,
        executor: StepExecutor,
        committer: Committer,
        limits: BatchLimits = BatchLimits.DEFAULT,
    ): BatchOnboardingImporter = BatchOnboardingImporter(
        store = batchStore,
        workflows = BatchWorkflowFactory { workflowId ->
            OnboardingWorkflow(workflowStore, executor, committer, FixedClock())
        },
        clock = FixedClock(),
        limits = limits,
        ids = SequentialBatchIds("batch"),
    )


    private companion object {
        val PROBE_STEPS = listOf(
            OnboardingStepId.INSPECT,
            OnboardingStepId.DETECT_TYPE,
            OnboardingStepId.AUTH,
            OnboardingStepId.CREDENTIAL_TEST,
            OnboardingStepId.LIST_MODELS,
            OnboardingStepId.TEST_MODEL,
            OnboardingStepId.PRICING_QUOTA_NOTICE,
        )
        const val LEAKY_SECRET_FIXTURE = "sk-00000000000000000000000000000000"
    }
}
