package dev.modelatlas.core.newapi

import dev.modelatlas.core.domain.QuotaState
import java.math.BigDecimal

/**
 * New API 憑證角色分離。
 *
 * 研究依據：`docs/ADAPTER_NEWAPI_RESEARCH.md` §1（觀測日期 2026-09-27，
 * New API `v1.0.0-rc.25` tag 原始碼為準）。
 *
 * 帳號 PAT 只走 `/api/…`（`middleware.UserAuth`），inference API key 只走 `/v1/…`
 * 與 `/api/usage/token/`（`TokenAuth` / `TokenAuthReadOnly`）。兩者互不接受，
 * 因此以兩個不可互換的型別在編譯期強制分離，避免誤送造成洩漏面。
 *
 * 兩個型別只承載「安全參照」（DB 的 safeCredentialId / label），不承載原始 secret；
 * 原始值只在 HTTP 邊界由 `CredentialAccess` 取值。
 */
@JvmInline
value class AccountPatRef(val value: String) {
    init { validateCredentialRef(value) }
}

@JvmInline
value class InferenceKeyRef(val value: String) {
    init { validateCredentialRef(value) }
}

private const val MAX_CREDENTIAL_REF_CHARS = 256

private fun validateCredentialRef(value: String) {
    require(value.isNotBlank()) { "Credential ref must not be blank" }
    require(value.length <= MAX_CREDENTIAL_REF_CHARS) { "Credential ref too long" }
    require(value.none { it.code < 0x20 || it.code == 0x7F }) {
        "Credential ref must not contain control characters"
    }
}

enum class NewApiVersionSupport {
    /** 唯一被明確認證、研究過的版本：`v1.0.0-rc.25`。 */
    CERTIFIED_RC25,

    /** 未知／未研究版本；不得推定與 rc.25 相容。 */
    UNKNOWN,
}

/**
 * Provider 版本。`raw` 只保留安全的版本字串；未知版本其 [support] 必為
 * [NewApiVersionSupport.UNKNOWN]，且不會提供任何 version-specific endpoint。
 */
data class NewApiVersion(val raw: String?, val support: NewApiVersionSupport) {
    init {
        val expected =
            if (raw == RC25) NewApiVersionSupport.CERTIFIED_RC25 else NewApiVersionSupport.UNKNOWN
        require(support == expected) { "Version support must be derived from the raw version" }
    }

    /** rc.25 已確認的唯讀端點；未知版本一律空 map，不猜相容。 */
    val certifiedEndpoints: Map<NewApiEndpoint, String>
        get() = if (support == NewApiVersionSupport.CERTIFIED_RC25) {
            NewApiEndpoint.entries.associateWith { it.path }
        } else {
            emptyMap()
        }

    /**
     * rc.25 已確認的 provider-wide perf 端點（GAP-V1）。
     *
     * 刻意與 [certifiedEndpoints] 分開：後者由 [NewApiEndpoint.entries] 自動推導，
     * 新增 perf path 進該 enum 會靜默擴張既有 5 端點認證 map；perf 認證改由
     * [NewApiPerfEndpoint] + 本屬性顯式表達。未知版本一律空 map。
     */
    val certifiedPerfEndpoints: Map<NewApiPerfEndpoint, String>
        get() = if (support == NewApiVersionSupport.CERTIFIED_RC25) {
            NewApiPerfEndpoint.entries.associateWith { it.path }
        } else {
            emptyMap()
        }

    companion object {
        const val RC25 = "v1.0.0-rc.25"

        val UNKNOWN = NewApiVersion(null, NewApiVersionSupport.UNKNOWN)

        /**
         * 只認證 `v1.0.0-rc.25`。空白、超長、含控制或非安全字元者一律回
         * [UNKNOWN]（raw 丟棄），不猜其他版本相容性。
         */
        fun of(rawVersion: String?): NewApiVersion {
            val sanitized = sanitize(rawVersion) ?: return UNKNOWN
            val support = if (sanitized == RC25) {
                NewApiVersionSupport.CERTIFIED_RC25
            } else {
                NewApiVersionSupport.UNKNOWN
            }
            return NewApiVersion(sanitized, support)
        }

        private const val MAX_VERSION_CHARS = 64

        private fun sanitize(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty() || trimmed.length > MAX_VERSION_CHARS) return null
            if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return null
            if (trimmed.any { it.code > 0x7E }) return null
            return trimmed
        }
    }
}

/** rc.25 已確認的唯讀端點路徑（研究 §3）。 */
enum class NewApiEndpoint(val path: String) {
    STATUS("/api/status"),
    NOTICE("/api/notice"),
    PRICING("/api/pricing"),
    ACCOUNT_SELF("/api/user/self"),
    TOKEN_USAGE("/api/usage/token/"),
}

/** 統一、安全的讀取結果狀態；不攜帶 server message 或 body。 */
enum class NewApiStatus {
    OK,
    UNKNOWN_VERSION,
    HTTP_UNAUTHORIZED,
    HTTP_FORBIDDEN,
    HTTP_NOT_FOUND,
    HTTP_TOO_MANY_REQUESTS,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    HTTP_REDIRECT,
    HTTP_UNSUPPORTED,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    TLS_FAILED,
    RESPONSE_TOO_LARGE,
    MALFORMED,
    INPUT_INVALID,
    CREDENTIAL_UNAVAILABLE,
    UNKNOWN,
}

/**
 * Evidence metadata：必含來源、觀測時間、provider 版本（及認證狀態）。
 * 不落地原 response；`pricingVersion` 為官方 pricing 版本雜湊。
 */
data class NewApiEvidence(
    val source: String,
    val observedAt: Long,
    val providerVersion: String? = null,
    val versionSupport: NewApiVersionSupport = NewApiVersionSupport.UNKNOWN,
    val httpStatus: Int? = null,
    val pricingVersion: String? = null,
)

sealed interface NewApiResult<out T> {
    data class Success<out T>(
        val evidence: NewApiEvidence,
        val value: T,
    ) : NewApiResult<T>

    data class Failure(
        val status: NewApiStatus,
        val evidence: NewApiEvidence,
    ) : NewApiResult<Nothing>
}

/** `/api/status` 解析出的能力事實；未知欄位為 null，絕不猜 0 或預設值。 */
data class NewApiCapabilities(
    val version: NewApiVersion,
    val quotaPerUnit: BigDecimal?,
    val quotaDisplayType: String?,
    val checkinEnabled: Boolean?,
    val pricingModule: NewApiModuleAccess,
    val certifiedEndpoints: Map<NewApiEndpoint, String>,
)

/** `HeaderNavModules`（JSON 字串，需二次 parse）中單一模組的開關。 */
data class NewApiModuleAccess(
    val enabled: Boolean?,
    val requireAuth: Boolean?,
)

/**
 * `/api/notice`。內容為站方可控的外部文字；adapter 刻意只回「是否存在」，
 * 不把原文帶出，避免 server message 外洩。
 */
data class NewApiNotice(val present: Boolean)

/**
 * `/api/pricing` 的原始官方事實。
 *
 * 本 adapter 只提供**精確十進位事實**（直接以字串轉 `BigDecimal`，不經 float），
 * **絕不 eval `billing_expr`、絕不做 quota/USD 換算**；換算由另一 parser session
 * （`PricingExpressionParser`）與 domain `PricingPolicy` 負責。
 *
 * `data: []` 是官方「無可用模型」的事實，[items] 為空且 [skippedItems] 為 0；
 * 與 [NewApiStatus.MALFORMED]（無法解讀）明確區分。
 */
data class NewApiPricing(
    val items: List<NewApiPricingItem>,
    val skippedItems: Int,
    val groupRatio: Map<String, NewApiRatio>,
    val usableGroups: List<String>,
    val supportedEndpoint: Map<String, NewApiSupportedEndpoint>,
    val autoGroups: List<String>,
    val pricingVersion: String?,
)

/** ratio 可能是 number 或字串（研究 §3.6）；字串一律標 [nonNumeric]，不猜數值。 */
data class NewApiRatio(val exact: BigDecimal?, val nonNumeric: Boolean)

data class NewApiSupportedEndpoint(val path: String?, val method: String?)

/** 單一 model 的原始定價事實；所有數值為精確 decimal，缺失為 null。 */
data class NewApiPricingItem(
    val modelName: String,
    val quotaType: Int?,
    val modelRatio: BigDecimal?,
    val modelPrice: BigDecimal?,
    val completionRatio: BigDecimal?,
    val cacheRatio: BigDecimal?,
    val createCacheRatio: BigDecimal?,
    val imageRatio: BigDecimal?,
    val audioRatio: BigDecimal?,
    val audioCompletionRatio: BigDecimal?,
    val enableGroups: List<String>,
    val supportedEndpointTypes: List<String>,
    val billingMode: String?,
    /** 原始 `billing_expr`；僅傳遞事實，永不求值。 */
    val billingExpression: String?,
    val vendorId: Int?,
    /**
     * 注意：`data[]` 內只有第一筆會帶 `pricing_version`，且與頂層同名欄位值不同
     * （研究 §3.4），故兩者分別保存、不合併。
     */
    val pricingVersion: String?,
)

/**
 * `/api/user/self` 的**帳號層級** quota。刻意排除 username / email / id 等 PII。
 * `quota` 為整數 quota 單位原值；未知為 null，不當 0。
 */
data class NewApiAccountQuota(
    val quota: Long?,
    val usedQuota: Long?,
    val requestCount: Long?,
    val group: String?,
    val role: Int?,
    val status: Int?,
)

enum class NewApiExpiryMeaning {
    /** 原始值 > 0：可解讀為絕對 epoch 秒。 */
    ABSOLUTE_EPOCH_SECONDS,

    /** 原始值 == 0：此端點把「永不過期」與 0 混同（研究 §3.10），不可判定。 */
    ZERO_AMBIGUOUS,

    /** 欄位缺失或非數值。 */
    UNKNOWN,
}

/**
 * `/api/usage/token/` 的 **Key 層級** quota（以 inference API key 認證）。
 * 原始整數欄位保留；[state] 為研究 §3.10 規則推導的便利值，證據不足即
 * [QuotaState.UNKNOWN]，不當 0。
 */
data class NewApiTokenQuota(
    val totalGranted: Long?,
    val totalUsed: Long?,
    val totalAvailable: Long?,
    val unlimitedQuota: Boolean?,
    val modelLimitsEnabled: Boolean?,
    val expiresAtRaw: Long?,
    val expiryMeaning: NewApiExpiryMeaning,
    val expired: Boolean?,
    val state: QuotaState,
)
