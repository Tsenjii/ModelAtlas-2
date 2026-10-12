package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.QuotaState
import kotlinx.serialization.Serializable

enum class OnboardingFlowType { QUICK, ADVANCED }

/** 固定探測順序，不可調換（PROVIDER_ONBOARDING_CONTRACT §1.2）。 */
enum class OnboardingStepId {
    INSPECT,
    DETECT_TYPE,
    AUTH,
    CREDENTIAL_TEST,
    LIST_MODELS,
    TEST_MODEL,
    PRICING_QUOTA_NOTICE,
    REVIEW,
    COMMIT;

    val wireName: String
        get() = when (this) {
            INSPECT -> "inspect"
            DETECT_TYPE -> "detectType"
            AUTH -> "auth"
            CREDENTIAL_TEST -> "credentialTest"
            LIST_MODELS -> "listModels"
            TEST_MODEL -> "testModel"
            PRICING_QUOTA_NOTICE -> "pricingQuotaNotice"
            REVIEW -> "review"
            COMMIT -> "commit"
        }

    companion object {
        val ORDERED: List<OnboardingStepId> = values().toList()
    }
}

/** STALE 會被重跑，SUPERSEDED 只留歷史，NEEDS_INPUT 等使用者補輸入。 */
enum class OnboardingStepStatus { PENDING, RUNNING, NEEDS_INPUT, SUCCESS, PARTIAL, SKIPPED, FAILED, STALE, SUPERSEDED }

enum class OnboardingStatus { DRAFT, RUNNING, NEEDS_INPUT, BLOCKED, REVIEW, COMMITTED, ABANDONED }

enum class OnboardingStop { NONE, NEEDS_INPUT, BLOCKED, COMPLETED }

enum class OnboardingFailureReason {
    INPUT_INVALID,
    NETWORK_UNREACHABLE,
    TLS_FAILED,
    TIMEOUT,
    AUTH_MISSING_CREDENTIAL,
    AUTH_FAILED,
    RATE_LIMITED,
    QUOTA_EXHAUSTED,
    MODEL_NOT_FOUND,
    NO_MODELS_TO_TEST,
    PROTOCOL_MISMATCH,
    SERVER_ERROR,
    CANCELLED,
    INTERRUPTED,
    POLICY_BLOCKED,
    NOT_APPLICABLE,
    BLOCKED_UPSTREAM,
    STALE_UPSTREAM,
    BUDGET_EXHAUSTED,
    RATE_LIMITED_STOP,
    REVIEW_NOT_APPROVED,
    REVIEW_INCOMPLETE,
    USER_SKIPPED,
    ABANDONED,
    SUPERSEDED,
    UNKNOWN,
}

enum class AuthMode { NONE, API_KEY, BEARER, CUSTOM_HEADER, PAT_ACCOUNT }

enum class AdapterType { OPENAI_COMPATIBLE, ANTHROPIC_MESSAGES, GEMINI_NATIVE, GENERIC_CUSTOM, UNKNOWN }

enum class EvidenceSource { PROBE, USER_MANUAL, OFFICIAL_DOC, NOTICE_PAGE }

enum class EvidenceConfidence { HIGH, MEDIUM, LOW, UNKNOWN }

enum class PriceStatus { VERIFIED_FREE, VERIFIED_PAID, MIXED, UNKNOWN }

enum class PricingScopeKey { PROVIDER, ACCOUNT, CREDENTIAL, MODEL, GROUP, ENDPOINT, OPERATION }

enum class PricePerUnit { INPUT_TOKEN, OUTPUT_TOKEN, PER_REQUEST, PER_MINUTE, PER_IMAGE, PER_1K_TOKENS, UNKNOWN }

enum class CredentialLocation { HEADER, QUERY }

enum class CredentialRole { INFERENCE, ACCOUNT }

enum class ModelOrigin { CATALOG, USER_MANUAL, SUGGESTED }

enum class ModelVerification { VERIFIED, FAILED, UNVERIFIED, UNKNOWN }

enum class ObservationOutcome { SUCCESS, FAILED, CANCELLED, UNKNOWN }

enum class ReviewItem { PROVIDER_TYPE, AUTH, MODELS_FOUND, MODELS_VERIFIED, PRICING, QUOTA, HEALTH_OFFICIAL, HEALTH_OBSERVED }

/**
 * Safe credential ref：只保存 vault 內的 id 與 label，絕不保存原始值。
 * raw key / PAT 只在 UI 短暫記憶體 → vault 路徑出現，不進 snapshot。
 */
@Serializable
data class SafeCredentialRef(
    val safeCredentialId: String,
    val label: String,
    val role: CredentialRole = CredentialRole.INFERENCE,
    val accountRef: String? = null,
)

/** 只回顯欄位名稱（HEADER / QUERY + name），值不保存。 */
@Serializable
data class CustomAuthFieldRef(
    val location: CredentialLocation,
    val name: String,
    val safeCredentialId: String? = null,
    val overrideConfirmed: Boolean = false,
)

@Serializable
data class ManualClassificationInput(
    val primaryCategory: dev.modelatlas.core.domain.ModelCategory,
    val capabilities: Set<dev.modelatlas.core.domain.Capability> = emptySet(),
    val modalities: Set<dev.modelatlas.core.domain.Modality> = emptySet(),
    val endpointTypes: Set<EndpointType> = emptySet(),
)

@Serializable
data class EndpointRef(
    val endpointType: EndpointType,
    val url: String,
    val protocol: String,
    val enabled: Boolean = true,
)

/** Advanced 手填定價；金額為精確 decimal 字串，confidence 恆為 LOW。 */
@Serializable
data class ManualPricingInput(
    val modelId: String? = null,
    val group: String? = null,
    val endpointType: EndpointType? = null,
    val operation: String? = null,
    val decimalAmount: String,
    val currency: String,
    val perUnit: PricePerUnit,
    val effectiveFrom: String? = null,
    val source: EvidenceSource = EvidenceSource.USER_MANUAL,
    val note: String? = null,
)

@Serializable
data class StepResult(
    val step: OnboardingStepId,
    val status: OnboardingStepStatus,
    val attempt: Int = 0,
    val evidenceIds: List<String> = emptyList(),
    val confidence: EvidenceConfidence = EvidenceConfidence.UNKNOWN,
    val requestsSpent: Int = 0,
    val requestLimit: Int? = null,
    val reason: OnboardingFailureReason? = null,
    val message: String? = null,
    val updatedAtMillis: Long = 0L,
    val adapterType: AdapterType? = null,
    val authMode: AuthMode? = null,
    val safeCredentialIds: List<String> = emptyList(),
    val credentialTest: CredentialTestFinding? = null,
    val catalog: CatalogFinding? = null,
    val modelTests: TestModelFinding? = null,
    val pricingQuota: PricingQuotaFinding? = null,
)

@Serializable
data class ModelFinding(
    val modelId: String,
    val origin: ModelOrigin,
    val verification: ModelVerification,
    val source: EvidenceSource = EvidenceSource.PROBE,
    val confidence: EvidenceConfidence = EvidenceConfidence.LOW,
)

@Serializable
data class CatalogFinding(
    val supported: Boolean = false,
    val models: List<ModelFinding> = emptyList(),
    val truncated: Boolean = false,
    val totalCount: Int? = null,
)

@Serializable
data class TestModelFinding(
    val findings: List<ModelFinding> = emptyList(),
    val testedTotal: Int? = null,
    val stoppedByRateLimit: Boolean = false,
)

/** 查無官方來源一律 UNKNOWN，不預設 0、不預設可用。 */
@Serializable
data class PricingQuotaFinding(
    val pricingStatus: PriceStatus = PriceStatus.UNKNOWN,
    val quotaStatus: QuotaState = QuotaState.UNKNOWN,
    val officialHealth: HealthState = HealthState.UNKNOWN,
    val observedHealth: HealthState = HealthState.UNKNOWN,
    val noticeFetched: Boolean? = null,
    val checkinUrl: String? = null,
    val requiresDailyCheckin: Boolean? = null,
)

@Serializable
data class CredentialTestFinding(
    val httpStatus: Int? = null,
    val reachable: Boolean? = null,
    val anonymous: Boolean? = null,
)

@Serializable
data class DetectionFinding(
    val adapterType: AdapterType = AdapterType.UNKNOWN,
    val confidence: EvidenceConfidence = EvidenceConfidence.LOW,
    val basis: String? = null,
)

@Serializable
data class AuthDecision(
    val authMode: AuthMode = AuthMode.BEARER,
    val requiresCredential: Boolean = true,
    val credentialIds: List<String> = emptyList(),
    val customAuthFieldNames: List<String> = emptyList(),
    val missingCredential: Boolean = false,
)

@Serializable
data class ReviewAuth(
    val authMode: AuthMode = AuthMode.BEARER,
    val credentialLabels: List<String> = emptyList(),
    val customAuthFieldNames: List<String> = emptyList(),
    val credentialTestStatus: OnboardingStepStatus = OnboardingStepStatus.PENDING,
    val failureReason: OnboardingFailureReason? = null,
)

@Serializable
data class ReviewItems(
    val providerType: AdapterType = AdapterType.UNKNOWN,
    val providerTypeBasis: String? = null,
    val auth: ReviewAuth = ReviewAuth(),
    val modelsFound: Int? = null,
    val modelsVerified: Int? = null,
    val modelsVerifiedIds: List<String> = emptyList(),
    val modelsTestedTotal: Int? = null,
    val pricingStatus: PriceStatus = PriceStatus.UNKNOWN,
    val quotaStatus: QuotaState = QuotaState.UNKNOWN,
    val officialHealth: HealthState = HealthState.UNKNOWN,
    val observedHealth: HealthState = HealthState.UNKNOWN,
    val unknownFields: List<String> = emptyList(),
    val confidences: Map<ReviewItem, EvidenceConfidence> = emptyMap(),
    val evidenceIds: List<String> = emptyList(),
    val checkinUrl: String? = null,
    val requiresDailyCheckin: Boolean = false,
    val requestsSpent: Int = 0,
    val rateLimited: Boolean = false,
)

/** 人工確認。任一上游重跑會作廢此 approval，必須重新確認。 */
@Serializable
data class ReviewApproval(
    val approved: Boolean,
    val approvedAtMillis: Long,
    val approvedBy: String? = null,
)

@Serializable
data class CommitReceipt(
    val commitId: String,
    val providerId: String? = null,
    val committedAtMillis: Long = 0L,
)

/** 執行前保留的額度；hold 可在取消 / 中斷時釋放，requestsSpent 永不回收。 */
@Serializable
data class BudgetReservation(
    val step: OnboardingStepId,
    val attempt: Int,
    val requests: Int,
    val testModels: Int,
)

@Serializable
data class ProbeBudgetState(
    val totalRequests: Int = 0,
    val testModelsAttempted: Int = 0,
    val stepUsage: Map<OnboardingStepId, Int> = emptyMap(),
) {
    fun usedFor(step: OnboardingStepId): Int = stepUsage[step] ?: 0
}

/**
 * 探測預算。所有上限集中在此，不散落在呼叫端；callers 不得自行放寬。
 *
 * 單 workflow 總 request 上限 [maxTotalRequests]、[maxTestModels] 個測試模型；
 * per-step 上限見 [ProbeBudgetLimits.PER_STEP_REQUESTS]。
 */
@Serializable
data class ProbeBudget(
    val maxTotalRequests: Int = ProbeBudgetLimits.MAX_TOTAL_REQUESTS,
    val maxTestModels: Int = ProbeBudgetLimits.MAX_TEST_MODELS,
    val maxRequestsPerStep: Map<OnboardingStepId, Int> = ProbeBudgetLimits.PER_STEP_REQUESTS,
) {
    init {
        require(maxTotalRequests in 1..ProbeBudgetLimits.MAX_TOTAL_REQUESTS) {
            "maxTotalRequests 必須介於 1..${ProbeBudgetLimits.MAX_TOTAL_REQUESTS}"
        }
        require(maxTestModels in 1..ProbeBudgetLimits.MAX_TEST_MODELS) {
            "maxTestModels 必須介於 1..${ProbeBudgetLimits.MAX_TEST_MODELS}"
        }
    }

    fun requestLimitFor(step: OnboardingStepId): Int = maxRequestsPerStep[step] ?: 0

    companion object {
        val DEFAULT: ProbeBudget = ProbeBudget()
    }
}

object ProbeBudgetLimits {
    const val MAX_TOTAL_REQUESTS = 10
    const val MAX_TEST_MODELS = 3

    /** 產品常數未定（契約 §10），集中於此可調。 */
    const val STEP_TIMEOUT_SECONDS = 15L

    val PER_STEP_REQUESTS: Map<OnboardingStepId, Int> = mapOf(
        OnboardingStepId.INSPECT to 1,
        OnboardingStepId.DETECT_TYPE to 1,
        OnboardingStepId.AUTH to 0,
        OnboardingStepId.CREDENTIAL_TEST to 1,
        OnboardingStepId.LIST_MODELS to 1,
        OnboardingStepId.TEST_MODEL to 3,
        OnboardingStepId.PRICING_QUOTA_NOTICE to 3,
        OnboardingStepId.REVIEW to 0,
        OnboardingStepId.COMMIT to 0,
    )
}

/**
 * Workflow 快照：可序列化、非 secret、無任意 payload。
 * 金額以精確 decimal 字串保存；未知數量以 null 表示，不以 0 代替。
 */
@Serializable
data class OnboardingSnapshot(
    val workflowId: String,
    val flowType: OnboardingFlowType,
    val status: OnboardingStatus = OnboardingStatus.DRAFT,
    val providerName: String? = null,
    val baseUrl: String? = null,
    val websiteUrl: String? = null,
    val group: String? = null,
    val checkinUrl: String? = null,
    val tags: List<String> = emptyList(),
    val notes: String? = null,
    val safeCredentialIds: List<String> = emptyList(),
    val credentials: List<SafeCredentialRef> = emptyList(),
    val customAuthFields: List<CustomAuthFieldRef> = emptyList(),
    val authModeOverride: AuthMode? = null,
    val anonymousConfirmed: Boolean = false,
    val endpointProfiles: List<EndpointRef> = emptyList(),
    val adapterTypeOverride: AdapterType? = null,
    val suggestedModelIds: List<String> = emptyList(),
    val manualModelIds: List<String> = emptyList(),
    /** null 表示全部候選；非 null 為 Review 明確選取的提交子集。 */
    val selectedModelIds: List<String>? = null,
    val manualPricing: List<ManualPricingInput> = emptyList(),
    val manualQuotaState: QuotaState? = null,
    val manualQuotaNote: String? = null,
    val manualClassification: ManualClassificationInput? = null,
    val detection: DetectionFinding? = null,
    val authDecision: AuthDecision? = null,
    val credentialTest: CredentialTestFinding? = null,
    val catalog: CatalogFinding? = null,
    val modelTests: TestModelFinding? = null,
    val pricingQuota: PricingQuotaFinding? = null,
    val reviewItems: ReviewItems? = null,
    val reviewApproval: ReviewApproval? = null,
    val results: List<StepResult> = emptyList(),
    val stepStatuses: Map<OnboardingStepId, OnboardingStepStatus> =
        OnboardingStepId.values().associateWith { OnboardingStepStatus.PENDING },
    val budget: ProbeBudgetState = ProbeBudgetState(),
    val reservation: BudgetReservation? = null,
    val rateLimitedAtMillis: Long? = null,
    val commitReceipt: CommitReceipt? = null,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val expiresAtMillis: Long? = null,
)
