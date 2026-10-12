package dev.modelatlas.core.domain

import java.math.BigDecimal
import java.time.LocalDate

/** 主分類（單一）＋ capabilities/modalities/endpointTypes（集合）。不得只用單一 modelType。 */
enum class ModelCategory {
    CHAT_REASONING,
    CODING,
    IMAGE,
    AUDIO,
    EMBEDDING,
    RERANK,
    SAFETY,
    OTHER,
}

enum class Capability {
    REASONING,
    CODING,
    TOOL_CALLING,
    FUNCTION_CALLING,
    STRUCTURED_OUTPUT,
    JSON_MODE,
    VISION,
    FILES,
    IMAGE_GENERATION,
    IMAGE_EDITING,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    WEB_SEARCH,
    COMPUTER_USE,
    LONG_CONTEXT,
    EMBEDDING,
    RERANK,
    SAFETY,
    OTHER,
}

enum class Modality {
    TEXT_INPUT,
    TEXT_OUTPUT,
    IMAGE_INPUT,
    IMAGE_OUTPUT,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    FILE_INPUT,
}

enum class EndpointType {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI_NATIVE,
    IMAGES_GENERATION,
    IMAGES_EDIT,
    AUDIO,
    EMBEDDINGS,
    RERANK,
    CUSTOM,
}

data class ModelClassification(
    val primaryCategory: ModelCategory,
    val capabilities: Set<Capability> = emptySet(),
    val modalities: Set<Modality> = emptySet(),
    val endpointTypes: Set<EndpointType> = emptySet(),
)

enum class HealthState {
    HEALTHY,
    DEGRADED,
    UNSTABLE,
    OFFLINE,
    UNKNOWN,
}

/** Official 與 Observed 分離；UNKNOWN 不得當作健康。 */
data class ProviderHealth(
    val providerId: String,
    val official: HealthState = HealthState.UNKNOWN,
    val observed: HealthState = HealthState.UNKNOWN,
    val checkedAt: Long = 0L,
)

/** 每日簽到：按裝置本地日期，同一 provider 每日一筆。 */
data class CheckinRecord(
    val providerId: String,
    val accountId: String? = null,
    val localDate: LocalDate,
    val completedAt: Long? = null,
    val source: String = "",
    val note: String? = null,
)

/** 無紀錄、非當日、或當日尚未完成（completedAt null）即為待簽到。 */
fun isCheckinPending(
    today: LocalDate,
    record: CheckinRecord?,
): Boolean = record == null || record.localDate != today || record.completedAt == null

enum class CandidateState {
    DISCOVERED,
    VERIFYING,
    VERIFIED,
    HIGH_VALUE_GREY_ZONE,
    STALE,
    FAILED,
    REJECTED,
}

/** 淘汰 / 降級共用理由（降級常用 PERFORMANCE_BELOW_THRESHOLD、STALE_NO_SIGNAL、SUPERSEDED）。 */
enum class CandidateDecisionReason {
    VERIFICATION_FAILED,
    ENDPOINT_UNREACHABLE,
    AUTH_FAILED,
    NON_HTTPS_ENDPOINT,
    CREDENTIAL_REDIRECT_RISK,
    QUOTA_EXHAUSTED,
    PRICING_UNSUPPORTED,
    CAPABILITY_MISMATCH,
    PERFORMANCE_BELOW_THRESHOLD,
    EVIDENCE_INSUFFICIENT,
    STALE_NO_SIGNAL,
    SUPERSEDED,
    MANUAL_REJECT,
    POLICY_VIOLATION,
    OTHER,
}

/** Crawler funnel 全狀態保存，不只存推薦結果；淘汰 / 降級理由與證據同存。 */
data class CrawlerCandidate(
    val id: String = "",
    val providerId: String? = null,
    val modelId: String? = null,
    val state: CandidateState = CandidateState.DISCOVERED,
    val rejectionReason: CandidateDecisionReason? = null,
    val downgradeReason: CandidateDecisionReason? = null,
    val evidenceIds: List<String> = emptyList(),
)

enum class PriceState {
    FREE_NOW,
    PAID,
    PARTIALLY_FREE,
    UNKNOWN,
}

/** Quota 全規格；UNKNOWN 不得當作可用；一次性贈金不是 recurring free。 */
enum class QuotaState {
    QUOTA_AVAILABLE,
    QUOTA_LOW,
    QUOTA_EXHAUSTED,
    RATE_LIMIT_ONLY,
    PUBLIC_POOL,
    UNLIMITED_CLAIMED,
    NO_KNOWN_CONSUMABLE_QUOTA,
    UNKNOWN,
}

/**
 * 定價 scope：Provider × Account/Credential × Model × Group × Endpoint × Operation。
 * 時間不在 scope，在 snapshot.observedAt。不同 Key / Endpoint / Operation 不互相覆蓋。
 */
data class PricingScope(
    val providerId: String,
    val accountId: String?,
    val credentialId: String?,
    val modelId: String,
    val group: String?,
    val endpointId: String,
    val operation: String,
)

data class PriceSnapshot(
    val id: String = "",
    val scope: PricingScope,
    val observedAt: Long,
    val input: BigDecimal? = null,
    val output: BigDecimal? = null,
    val fixedRequest: BigDecimal? = null,
    val fixedRequestKnownAbsent: Boolean = false,
    val cacheRead: BigDecimal? = null,
    val cacheWrite: BigDecimal? = null,
    val source: String = "",
    val evidenceId: String? = null,
)

data class QuotaSnapshot(
    val id: String = "",
    val scope: PricingScope,
    val observedAt: Long,
    val state: QuotaState = QuotaState.UNKNOWN,
    val source: String = "",
    val evidenceId: String? = null,
)

/**
 * MODEL-001 CanonicalModel：跨 Provider／Account／Credential 的同一模型正規化身份。
 * 同一 canonical 不因不同 key 重複建立（PRODUCT_SPEC §6）；原始 provider model ID
 * 永遠保存在 alias／offering，不以正規化名覆蓋。
 */
data class CanonicalModel(
    val canonicalId: String,
    val normalizedName: String,
    val displayName: String? = null,
)

/**
 * MODEL-002 ProviderModelAlias：原始 provider model ID → canonical 的映射。
 * 來源與信心分欄保存；manual（USER_OVERRIDE）恆 LOW，永不升為官方；
 * 未驗證映射以 verified=false 標 tentative，不得 VERIFIED。
 */
data class ProviderModelAlias(
    val aliasId: String,
    val providerId: String,
    val providerModelId: String,
    val canonicalModelId: String,
    val source: MappingSource,
    val confidence: MappingConfidence,
    val verified: Boolean = false,
    val observedAt: Long = 0L,
    val version: Int = 1,
)

/** 映射來源（PRODUCT_SPEC §7）：official／LMSpeed／metadata／normalization／agent 推斷／使用者覆寫。 */
enum class MappingSource {
    OFFICIAL,
    LMSPEED,
    METADATA,
    NORMALIZATION,
    AGENT_INFERENCE,
    USER_OVERRIDE,
}

/** 映射信心；manual 恆 LOW，UNKNOWN 不得當作已知。 */
enum class MappingConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

fun isOfficialMappingSource(source: MappingSource): Boolean = source == MappingSource.OFFICIAL

fun isManualMappingSource(source: MappingSource): Boolean = source == MappingSource.USER_OVERRIDE

/**
 * MODEL-001／002 模型 ID 正規化：大小寫／分隔符／版本後綴。
 * trim → 小寫 → `_ . / : @` 與空白統一為 `-` → 壓縮連續 `-` → 去頭尾 `-` →
 * 去除尾端 `-latest`。未知不猜：空字串正規化後仍為空，由呼叫端拒絕。
 */
fun normalizeModelId(raw: String): String {
    var s = raw.trim().lowercase()
    s = s.replace(Regex("[_./:@\\s]+"), "-")
    s = s.replace(Regex("-+"), "-")
    s = s.trim('-')
    if (s.endsWith("-latest")) s = s.removeSuffix("-latest").trimEnd('-')
    return s
}

/** 穩定 canonical 身份：正規化名即 id；P7 LMSpeed enrichment 只追加，不改此語意。 */
fun canonicalIdFor(raw: String): String = normalizeModelId(raw)
