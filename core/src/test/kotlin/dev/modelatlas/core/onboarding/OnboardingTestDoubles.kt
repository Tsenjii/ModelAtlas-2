package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.QuotaState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

internal const val SAFE_CREDENTIAL_FIXTURE = "safe-credential-fixture"
internal const val LEAKY_SECRET_FIXTURE = "sk-00000000000000000000000000000000"
internal const val FIXTURE_BASE_URL = "https://api.fixture.invalid/v1"

internal class MemoryWorkflowStore : WorkflowStore {
    private val snapshots = mutableMapOf<String, OnboardingSnapshot>()
    val saves = mutableListOf<OnboardingSnapshot>()

    override suspend fun load(workflowId: String): OnboardingSnapshot? = snapshots[workflowId]

    override suspend fun save(snapshot: OnboardingSnapshot) {
        saves += snapshot
        snapshots[snapshot.workflowId] = snapshot
    }

    fun current(workflowId: String): OnboardingSnapshot? = snapshots[workflowId]

    fun put(snapshot: OnboardingSnapshot) {
        snapshots[snapshot.workflowId] = snapshot
    }

    fun savedWhere(predicate: (OnboardingSnapshot) -> Boolean): List<OnboardingSnapshot> = saves.filter(predicate)
}

internal class FixedClock : OnboardingClock {
    private var now = 1_000L

    override fun nowMillis(): Long {
        now += 10L
        return now
    }
}

internal class ScriptedStepExecutor(
    private val scripted: Map<OnboardingStepId, StepResult> = emptyMap(),
) : StepExecutor {
    val invocations = mutableListOf<Pair<OnboardingStepId, Int>>()
    val grants = mutableListOf<RequestGrant>()

    var behavior: (suspend (OnboardingSnapshot, OnboardingStepId, RequestGrant) -> StepResult)? = null
    var blockedStep: OnboardingStepId? = null
    val blocked = CompletableDeferred<Unit>()

    override suspend fun execute(
        snapshot: OnboardingSnapshot,
        step: OnboardingStepId,
        grant: RequestGrant,
    ): StepResult {
        invocations += step to grant.requestLimit
        grants += grant
        if (step == blockedStep) {
            blocked.complete(Unit)
            awaitCancellation()
        }
        behavior?.let { return it(snapshot, step, grant) }
        val scriptedResult = scripted[step]
        if (scriptedResult != null) {
            return scriptedResult.copy(requestsSpent = scriptedResult.requestsSpent.coerceAtMost(grant.requestLimit))
        }
        return StepResult(
            step = step,
            status = OnboardingStepStatus.SUCCESS,
            confidence = EvidenceConfidence.MEDIUM,
            requestsSpent = if (grant.requestLimit > 0) 1 else 0,
        )
    }

    fun callCount(step: OnboardingStepId): Int = invocations.count { it.first == step }

    fun order(): List<OnboardingStepId> = invocations.map { it.first }
}

internal class RecordingCommitter(private val failures: Int = 0) : Committer {
    val calls = mutableListOf<String>()
    private val receipts = mutableMapOf<String, CommitReceipt>()

    override suspend fun commit(snapshot: OnboardingSnapshot): CommitReceipt {
        calls += snapshot.workflowId
        val existing = receipts[snapshot.workflowId]
        if (existing != null) return existing
        if (calls.size <= failures) {
            throw java.io.IOException("commit transaction aborted")
        }
        val receipt = CommitReceipt(
            commitId = "commit-${snapshot.workflowId}",
            providerId = "provider-${snapshot.workflowId}",
            committedAtMillis = 42L,
        )
        receipts[snapshot.workflowId] = receipt
        return receipt
    }
}

internal fun quickDraft(
    workflowId: String = "wf-fixture",
    suggestedModelIds: List<String> = listOf("fixture-model-a", "fixture-model-b"),
    manualModelIds: List<String> = emptyList(),
) = OnboardingSnapshot(
    workflowId = workflowId,
    flowType = OnboardingFlowType.QUICK,
    baseUrl = FIXTURE_BASE_URL,
    websiteUrl = "https://fixture.invalid",
    suggestedModelIds = suggestedModelIds,
    manualModelIds = manualModelIds,
    safeCredentialIds = listOf(SAFE_CREDENTIAL_FIXTURE),
    credentials = listOf(SafeCredentialRef(SAFE_CREDENTIAL_FIXTURE, "main")),
)

internal fun happyPath(
    models: List<String> = listOf("fixture-model-a", "fixture-model-b"),
): Map<OnboardingStepId, StepResult> = mapOf(
    OnboardingStepId.INSPECT to StepResult(
        step = OnboardingStepId.INSPECT,
        status = OnboardingStepStatus.SUCCESS,
        evidenceIds = listOf("ev-inspect"),
        confidence = EvidenceConfidence.HIGH,
        requestsSpent = 1,
    ),
    OnboardingStepId.DETECT_TYPE to StepResult(
        step = OnboardingStepId.DETECT_TYPE,
        status = OnboardingStepStatus.SUCCESS,
        evidenceIds = listOf("ev-detect"),
        confidence = EvidenceConfidence.LOW,
        requestsSpent = 1,
        adapterType = AdapterType.OPENAI_COMPATIBLE,
    ),
    OnboardingStepId.AUTH to StepResult(
        step = OnboardingStepId.AUTH,
        status = OnboardingStepStatus.SUCCESS,
        authMode = AuthMode.BEARER,
        safeCredentialIds = listOf(SAFE_CREDENTIAL_FIXTURE),
    ),
    OnboardingStepId.CREDENTIAL_TEST to StepResult(
        step = OnboardingStepId.CREDENTIAL_TEST,
        status = OnboardingStepStatus.SUCCESS,
        evidenceIds = listOf("ev-credential-test"),
        confidence = EvidenceConfidence.HIGH,
        requestsSpent = 1,
        credentialTest = CredentialTestFinding(httpStatus = 200, reachable = true),
    ),
    OnboardingStepId.LIST_MODELS to StepResult(
        step = OnboardingStepId.LIST_MODELS,
        status = OnboardingStepStatus.SUCCESS,
        evidenceIds = listOf("ev-list-models"),
        requestsSpent = 1,
        catalog = CatalogFinding(
            supported = true,
            models = models.map { ModelFinding(it, ModelOrigin.CATALOG, ModelVerification.UNVERIFIED) },
        ),
    ),
    OnboardingStepId.TEST_MODEL to StepResult(
        step = OnboardingStepId.TEST_MODEL,
        status = OnboardingStepStatus.SUCCESS,
        evidenceIds = listOf("ev-test-model"),
        requestsSpent = 2,
        modelTests = TestModelFinding(
            findings = models.map {
                ModelFinding(it, ModelOrigin.CATALOG, ModelVerification.VERIFIED, confidence = EvidenceConfidence.HIGH)
            },
            testedTotal = models.size,
        ),
    ),
    OnboardingStepId.PRICING_QUOTA_NOTICE to StepResult(
        step = OnboardingStepId.PRICING_QUOTA_NOTICE,
        status = OnboardingStepStatus.PARTIAL,
        evidenceIds = listOf("ev-pricing"),
        confidence = EvidenceConfidence.LOW,
        requestsSpent = 3,
        pricingQuota = PricingQuotaFinding(
            pricingStatus = PriceStatus.UNKNOWN,
            quotaStatus = QuotaState.UNKNOWN,
            officialHealth = HealthState.UNKNOWN,
            observedHealth = HealthState.HEALTHY,
        ),
    ),
)

internal fun approval(approved: Boolean = true) =
    ReviewApproval(approved = approved, approvedAtMillis = 1L, approvedBy = "test-user")

internal fun OnboardingSnapshot.statusOf(step: OnboardingStepId): OnboardingStepStatus =
    stepStatuses[step] ?: OnboardingStepStatus.PENDING

internal fun OnboardingSnapshot.latest(step: OnboardingStepId): StepResult? = results.lastOrNull { it.step == step }

internal fun OnboardingSnapshot.historyOf(step: OnboardingStepId): List<StepResult> = results.filter { it.step == step }
