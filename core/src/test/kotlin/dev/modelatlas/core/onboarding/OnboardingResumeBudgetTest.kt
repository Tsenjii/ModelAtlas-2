
package dev.modelatlas.core.onboarding

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingResumeBudgetTest {
    @Test
    fun cancelledStepIsNotCompletedAndResumeRerunsOnlyThatStep() = runTest {
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        executor.blockedStep = OnboardingStepId.TEST_MODEL
        val workflow = OnboardingWorkflow(store, executor, RecordingCommitter(), FixedClock())

        val job = launch { workflow.start(quickDraft()) }
        executor.blocked.await()
        job.cancelAndJoin()
        val cancelled = store.current("wf-fixture")!!

        assertEquals(OnboardingStepStatus.FAILED, cancelled.statusOf(OnboardingStepId.TEST_MODEL))
        assertEquals(OnboardingFailureReason.CANCELLED, cancelled.latest(OnboardingStepId.TEST_MODEL)!!.reason)
        assertEquals(OnboardingStepStatus.SUCCESS, cancelled.statusOf(OnboardingStepId.INSPECT))
        assertEquals(OnboardingStepStatus.SUCCESS, cancelled.statusOf(OnboardingStepId.LIST_MODELS))
        assertNull(cancelled.reservation)

        executor.blockedStep = null
        val resumed = workflow.resume("wf-fixture")
        val snapshot = store.current("wf-fixture")!!

        assertEquals(1, executor.callCount(OnboardingStepId.INSPECT))
        assertEquals(1, executor.callCount(OnboardingStepId.LIST_MODELS))
        assertEquals(2, executor.callCount(OnboardingStepId.TEST_MODEL))
        assertEquals(1, executor.callCount(OnboardingStepId.PRICING_QUOTA_NOTICE))
        assertEquals(2, snapshot.latest(OnboardingStepId.TEST_MODEL)!!.attempt)
        assertEquals(OnboardingStepStatus.SUCCESS, snapshot.statusOf(OnboardingStepId.TEST_MODEL))
        assertEquals(OnboardingStatus.NEEDS_INPUT, resumed.status)
    }

    @Test
    fun killedProcessKeepsReservedRequestsSpentAndSkipsTheStep() = runTest {
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        executor.blockedStep = OnboardingStepId.TEST_MODEL
        val workflow = OnboardingWorkflow(store, executor, RecordingCommitter(), FixedClock())

        val job = launch { workflow.start(quickDraft(suggestedModelIds = listOf("fixture-model-a", "fixture-model-b", "fixture-model-c"))) }
        executor.blocked.await()
        val inFlight = store.savedWhere { it.statusOf(OnboardingStepId.TEST_MODEL) == OnboardingStepStatus.RUNNING }.last()
        job.cancelAndJoin()

        val restartedStore = MemoryWorkflowStore().apply { put(inFlight) }
        val restartedExecutor = ScriptedStepExecutor(happyPath())
        val restarted = OnboardingWorkflow(restartedStore, restartedExecutor, RecordingCommitter(), FixedClock())

        val summary = restarted.resume("wf-fixture")
        val snapshot = restartedStore.current("wf-fixture")!!

        assertEquals(4, inFlight.budget.totalRequests)
        assertEquals(3, inFlight.reservation!!.requests)
        assertEquals(10, snapshot.budget.totalRequests)
        assertEquals(
            OnboardingFailureReason.INTERRUPTED,
            snapshot.historyOf(OnboardingStepId.TEST_MODEL).first().reason,
        )
        assertEquals(OnboardingFailureReason.BUDGET_EXHAUSTED, snapshot.latest(OnboardingStepId.TEST_MODEL)!!.reason)
        assertEquals(0, restartedExecutor.callCount(OnboardingStepId.TEST_MODEL))
        assertEquals(1, restartedExecutor.callCount(OnboardingStepId.PRICING_QUOTA_NOTICE))
        assertEquals(0, summary.requestsRemaining)
        assertEquals(10, summary.totalRequestsSpent)
    }

    @Test
    fun concurrentResumesOnOneInstanceProbeOnlyOnce() = runTest {
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        executor.blockedStep = OnboardingStepId.TEST_MODEL
        val workflow = OnboardingWorkflow(store, executor, RecordingCommitter(), FixedClock())

        val starter = launch { workflow.start(quickDraft()) }
        executor.blocked.await()
        val first = async { workflow.resume("wf-fixture") }
        val second = async { workflow.resume("wf-fixture") }
        executor.blockedStep = null
        starter.cancelAndJoin()
        val summaries = listOf(first.await(), second.await())

        assertEquals(1, executor.callCount(OnboardingStepId.INSPECT))
        assertEquals(1, executor.callCount(OnboardingStepId.DETECT_TYPE))
        assertEquals(1, executor.callCount(OnboardingStepId.CREDENTIAL_TEST))
        assertEquals(1, executor.callCount(OnboardingStepId.LIST_MODELS))
        assertEquals(2, executor.callCount(OnboardingStepId.TEST_MODEL))
        assertEquals(1, executor.callCount(OnboardingStepId.PRICING_QUOTA_NOTICE))
        assertTrue(summaries.all { it.status == OnboardingStatus.NEEDS_INPUT })
    }

    @Test
    fun totalBudgetCeilingStopsFurtherProbes() = runTest {
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        val workflow = OnboardingWorkflow(store, executor, RecordingCommitter(), FixedClock(), ProbeBudget(maxTotalRequests = 4))

        val summary = workflow.start(quickDraft())
        val snapshot = store.current("wf-fixture")!!

        assertEquals(4, summary.totalRequestsSpent)
        assertEquals(0, summary.requestsRemaining)
        assertEquals(0, executor.callCount(OnboardingStepId.TEST_MODEL))
        assertEquals(0, executor.callCount(OnboardingStepId.PRICING_QUOTA_NOTICE))
        assertEquals(OnboardingFailureReason.BUDGET_EXHAUSTED, snapshot.latest(OnboardingStepId.TEST_MODEL)!!.reason)
        assertEquals(OnboardingFailureReason.BUDGET_EXHAUSTED, snapshot.latest(OnboardingStepId.PRICING_QUOTA_NOTICE)!!.reason)
        assertTrue(snapshot.budget.totalRequests <= 4)
    }

    @Test
    fun testModelStepNeverExceedsThreeModels() = runTest {
        val models = (1..5).map { "fixture-model-$it" }
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath(models))
        executor.behavior = { snapshot, step, grant ->
            val scripted = happyPath(models)[step]!!
            if (step == OnboardingStepId.TEST_MODEL) {
                val findings = ArrayList<ModelFinding>()
                var spent = 0
                for (model in snapshot.catalog!!.models) {
                    if (!grant.tryReserve()) break
                    spent++
                    findings += ModelFinding(model.modelId, model.origin, ModelVerification.VERIFIED)
                }
                scripted.copy(
                    requestsSpent = spent,
                    modelTests = TestModelFinding(findings = findings, testedTotal = spent),
                )
            } else {
                scripted.copy(requestsSpent = scripted.requestsSpent.coerceAtMost(grant.requestLimit))
            }
        }
        val workflow = OnboardingWorkflow(store, executor, RecordingCommitter(), FixedClock())

        workflow.start(quickDraft(suggestedModelIds = emptyList()))
        val snapshot = store.current("wf-fixture")!!
        val grant = executor.grants.first { it.step == OnboardingStepId.TEST_MODEL }

        assertEquals(3, grant.requestLimit)
        assertEquals(3, grant.maxTestModels)
        assertEquals(3, snapshot.budget.testModelsAttempted)
        assertEquals(3, snapshot.latest(OnboardingStepId.TEST_MODEL)!!.requestsSpent)
        assertEquals(5, snapshot.catalog!!.models.size)
        assertEquals(5, snapshot.reviewItems!!.modelsFound)
        assertEquals(3, snapshot.reviewItems!!.modelsVerified)
        assertEquals(3, snapshot.reviewItems!!.modelsTestedTotal)
        assertTrue(snapshot.budget.totalRequests <= ProbeBudgetLimits.MAX_TOTAL_REQUESTS)
    }

    @Test
    fun executorCannotOverReportSpentRequests() = runTest {
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        executor.behavior = { _, step, _ -> happyPath()[step]!!.copy(requestsSpent = 99) }
        val workflow = OnboardingWorkflow(store, executor, RecordingCommitter(), FixedClock())

        val summary = workflow.start(quickDraft())
        val snapshot = store.current("wf-fixture")!!

        assertTrue(snapshot.budget.totalRequests <= ProbeBudgetLimits.MAX_TOTAL_REQUESTS)
        assertTrue(summary.totalRequestsSpent <= ProbeBudgetLimits.MAX_TOTAL_REQUESTS)
        assertTrue(snapshot.results.all { it.requestsSpent <= (it.requestLimit ?: 0) })
        assertEquals(0, snapshot.latest(OnboardingStepId.AUTH)!!.requestsSpent)
    }

    @Test
    fun budgetCeilingsAreCentralAndCannotBeRaised() {
        assertEquals(10, ProbeBudgetLimits.MAX_TOTAL_REQUESTS)
        assertEquals(3, ProbeBudgetLimits.MAX_TEST_MODELS)
        assertEquals(1, ProbeBudget.DEFAULT.requestLimitFor(OnboardingStepId.INSPECT))
        assertEquals(3, ProbeBudget.DEFAULT.requestLimitFor(OnboardingStepId.TEST_MODEL))
        assertEquals(3, ProbeBudget.DEFAULT.requestLimitFor(OnboardingStepId.PRICING_QUOTA_NOTICE))
        assertEquals(0, ProbeBudget.DEFAULT.requestLimitFor(OnboardingStepId.REVIEW))
        assertEquals(0, ProbeBudget.DEFAULT.requestLimitFor(OnboardingStepId.COMMIT))
        assertTrue(runCatching { ProbeBudget(maxTotalRequests = 11) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ProbeBudget(maxTestModels = 4) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(ProbeBudgetLimits.STEP_TIMEOUT_SECONDS > 0L)
    }

    @Test
    fun requestGrantNeverExceedsItsReservation() {
        val grant = RequestGrant(OnboardingStepId.TEST_MODEL, 1, 2, 2, false)
        assertTrue(grant.tryReserve())
        assertTrue(grant.tryReserve())
        assertFalse(grant.tryReserve())
        assertEquals(2, grant.reservedRequests)
        assertEquals(0, grant.availableRequests)
    }

    @Test
    fun failuresNeverPersistUrlOrSecretAndSnapshotsRoundTrip() = runTest {
        val store = MemoryWorkflowStore()
        val executor = ScriptedStepExecutor(happyPath())
        executor.behavior = { _, step, grant ->
            if (step == OnboardingStepId.CREDENTIAL_TEST) {
                throw java.net.ConnectException("connect $FIXTURE_BASE_URL?key=$LEAKY_SECRET_FIXTURE")
            }
            val scripted = happyPath()[step]!!
            scripted.copy(requestsSpent = scripted.requestsSpent.coerceAtMost(grant.requestLimit))
        }
        val workflow = OnboardingWorkflow(store, executor, RecordingCommitter(), FixedClock())

        workflow.start(quickDraft())
        val snapshot = store.current("wf-fixture")!!
        val persisted = store.saves.joinToString(separator = "\n")

        assertEquals(
            OnboardingFailureReason.NETWORK_UNREACHABLE,
            snapshot.latest(OnboardingStepId.CREDENTIAL_TEST)!!.reason,
        )
        assertFalse(persisted.contains(LEAKY_SECRET_FIXTURE))
        assertFalse(persisted.contains("?key="))
        assertTrue(snapshot.results.none { (it.message ?: "").contains("key=") })
        assertEquals(listOf(SAFE_CREDENTIAL_FIXTURE), snapshot.safeCredentialIds)

        val json = Json.encodeToString(OnboardingSnapshot.serializer(), snapshot)
        assertFalse(json.contains(LEAKY_SECRET_FIXTURE))
        assertTrue(json.contains(SAFE_CREDENTIAL_FIXTURE))
        assertEquals(snapshot, Json.decodeFromString(OnboardingSnapshot.serializer(), json))
        assertEquals(snapshot, workflow.load("wf-fixture"))
    }
}
