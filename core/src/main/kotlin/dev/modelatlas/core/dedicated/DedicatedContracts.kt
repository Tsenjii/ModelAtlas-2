package dev.modelatlas.core.dedicated

/**
 * Dedicated adapters 共用小 interface（Phase 5）。
 *
 * TRACEABILITY: ADAPT-001 ~ ADAPT-007（共用契約 + ADAPT-007 provenance 分離）。
 * Spec: `PRODUCT_SPEC.md` §9（Adapter capability 獨立缺失）、§10（provenance / FREE /
 * Pricing / Quota）、§12（Dedicated Adapters）；`RESEARCH_POLICY.md` §3 / §8；
 * `DECISIONS.md` D-2026-09-28-001 / D-2026-09-28-003。
 * 研究：`docs/NVIDIA_NIM_RESEARCH.md`、`docs/PROVIDER_QUOTA_PRICING_RESEARCH.md`、
 * `docs/AMD_CLOUD_RESEARCH.md`、`docs/ADAPTER_NEWAPI_RESEARCH.md`。
 *
 * 只吃研究已證實 contract，未證實一律 [QuotaValueState.UNKNOWN] / null，絕不用 0 / Free 代替。
 *
 * 設計原則（全部是純資料 + 純函式，無 IO、無 vault、無 clock、無網路）：
 * - **Capability 獨立缺失**：每個能力回 [DedicatedResult.Success] / [DedicatedResult.Unsupported]
 *   / [DedicatedResult.Failure] 三態。[Unsupported]（該能力不支援）與 [Failure]（本次讀取失敗，
 *   呼叫端必須保留 last-good、不得清空）嚴格區分；單一能力缺失不得拖垮整體同步
 *   （見 [DedicatedSync.combineIndependent]）。
 * - **official / local provenance 分離**（ADAPT-007）：每個結果都帶 [DedicatedEvidence.provenance]；
 *   `OFFICIAL_*` / `OBSERVED` / `LOCAL_TELEMETRY` / `SELF_HOSTED_OFFICIAL` / `SELF_REPORTED` /
 *   `EXTERNAL` / `MANUAL` 永不互冒（X-005）。
 * - **per-model quota 不合併**：[QuotaPool] 以 `scopeKind + projectId + modelId + quotaSlice` 逐筆保存；多 pool 不做假平均，
 *   不知道 total 不畫百分比（見 [DedicatedSync.assertNoMerge]）。
 * - **名稱不得判定免費**：所有 `isFree*` 函式簽名刻意**不收 model name**；只吃 pricing 數值 /
 *   plan tier / 官方欄位。名稱含 `free` / `flash` / `lite` 皆非證據。
 * - **429 / 402 / 410 分開**：[DedicatedStatus] 為三者保留獨立值，絕不折成 `HTTP_CLIENT_ERROR`；
 *   每家 classifier 必須對三者分支（見各 adapter 的 `classifyHttpStatus`）。
 */
enum class DedicatedProvenance {
    /** 官方文件全文直取（B）。 */
    OFFICIAL_DOC,

    /** 官方文件經搜尋索引取得（C），需標 `retrievedVia = SEARCH_INDEX` 並保留快照。 */
    OFFICIAL_DOC_VIA_INDEX,

    /** Provider 官方人員於官方論壇/部落格發言（S），層級低於文件。 */
    OFFICIAL_STAFF_FORUM,

    /** 實機 HTTP 觀測（A）。NVIDIA 本輪 A=0；OpenRouter 僅 catalog 一筆。 */
    OBSERVED,

    /** 第三方彙整（E），絕不可寫入 official 欄位。 */
    EXTERNAL,

    /** 本機裝置 + 這把 key + 現在的真實使用（Playground telemetry 等）。 */
    LOCAL_TELEMETRY,

    /** Self-hosted 管理/metrics 端點回報（NVIDIA `/v1/metrics`、AMD dedicated 等），不混入 local。 */
    SELF_HOSTED_OFFICIAL,

    /** Self-hosted 自擁容量自述（GPU 決定），非 provider limit。 */
    SELF_REPORTED,

    /** 使用者手動輸入/修正。 */
    MANUAL,

    UNKNOWN,
}

/** official 家族判定（B/C/S）。EXTERNAL / OBSERVED / LOCAL 等皆非 official。 */
fun DedicatedProvenance.isOfficialFamily(): Boolean = when (this) {
    DedicatedProvenance.OFFICIAL_DOC,
    DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX,
    DedicatedProvenance.OFFICIAL_STAFF_FORUM,
    -> true
    else -> false
}

/**
 * Quota 數值狀態（ADAPT-007 核心；綜合 P5A §4 + P5B §6/§11）。
 *
 * 五（+1）態不可共用單一 null：
 * - [KNOWN]：限額存在且有官方數值。
 * - [EXISTS_BUT_UNPUBLISHED]：限額存在但數值未公開。
 * - [DIMENSION_NOT_APPLICABLE]：維度根本不存在（例：無 per-token 計費下的 TPD）。
 * - [NO_PROVIDER_QUOTA]：明確無 provider 限額（self-hosted / dedicated 自擁容量）。
 * - [NOT_OFFERED]：明確拒絕提供（Gemini `Not available`、Groq `ContactSales`），非 Unknown。
 * - [UNKNOWN]：查不到，不猜。
 * - [UNKNOWN_REQUIRES_LOGIN]：唯一官方來源需登入（Gemini AI Studio、Groq Limits 頁等）。
 */
enum class QuotaValueState {
    KNOWN,
    EXISTS_BUT_UNPUBLISHED,
    DIMENSION_NOT_APPLICABLE,
    NO_PROVIDER_QUOTA,
    NOT_OFFERED,
    UNKNOWN,
    UNKNOWN_REQUIRES_LOGIN,
}

/** Quota 作用域（PricingScope 的 scope 維度；不同 scope 絕不互相覆蓋，X-004）。
 * 官方未定義 scope 時用 [UNKNOWN]（禁猜；E 級第三方說法不得寫入）。 */
enum class QuotaScopeKind {
    PLATFORM_GLOBAL,
    PER_PROJECT,
    PER_ORGANIZATION,
    PER_ACCOUNT,
    PER_KEY,
    PER_IP,
    PER_MODEL,
    PER_PROJECT_MODEL,
    PER_ORG_MODEL,
    PER_KEY_MODEL,
    PER_ACCOUNT_MODEL,
    INSTANCE_OWNED,
    UNKNOWN,
}

/**
 * 同一 scope 下的獨立限額切片（ADAPT-005；SPEC §12 Gemini Free）。
 *
 * 同一 project × model 下標準 interactive、priority、batch 為三個獨立限額，
 * 不可相加/覆蓋（X-004）。以本欄區分，不得把 `::priority` 之類後綴併入 `modelId`。
 * `null` = 未區分（舊資料／無切片概念的 provider 相容；新程式碼應明確填寫）。
 */
enum class QuotaPoolSlice {
    STANDARD_INTERACTIVE,
    PRIORITY,
    BATCH,
}

/** 模型可用性（SPEC §12 NVIDIA 條至少四態；catalog 出現 ≠ 這把 key 可用）。 */
enum class ModelAvailability {
    CATALOG_LISTED,
    VERIFIED_USABLE,
    TEMPORARILY_UNAVAILABLE,
    NOT_AVAILABLE_FOR_ACCOUNT,
    NOT_OFFERED,
    UNKNOWN,
}

/** SPEC §9 capability 清單（adapter capability 應能獨立缺失）。 */
enum class DedicatedCapability {
    DISCOVER_MODELS,
    FETCH_PRICING,
    FETCH_BALANCE,
    FETCH_QUOTA_POOLS,
    FETCH_USAGE,
    FETCH_PROVIDER_PERFORMANCE,
    FETCH_NOTICE,
    FETCH_ABOUT,
    FETCH_CAPABILITIES,
    FETCH_ACCOUNT,
    FETCH_ENDPOINT_PROFILES,
    TEST_MODEL,
    PROBE_ENDPOINT,
}

/**
 * 讀取結果狀態。429 / 402 / 410 各有獨立值，絕不折成 [HTTP_CLIENT_ERROR]。
 * 410 在 OpenRouter / Gemini / Groq 官方表皆未列（保留/不預期）；NVIDIA 觀察到（權限缺失）。
 */
enum class DedicatedStatus {
    OK,
    HTTP_UNAUTHORIZED_401,
    HTTP_PAYMENT_REQUIRED_402,
    HTTP_FORBIDDEN_403,
    HTTP_NOT_FOUND_404,
    HTTP_GONE_410,
    HTTP_TOO_MANY_REQUESTS_429,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    HTTP_REDIRECT,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    TLS_FAILED,
    RESPONSE_TOO_LARGE,
    MALFORMED,
    INPUT_INVALID,
    CREDENTIAL_UNAVAILABLE,
    CAPABILITY_UNSUPPORTED,
    UNKNOWN,
}

/** 單次讀取的 evidence：來源 + 觀測時間 + provenance + http 狀態，不帶 raw body / secret。 */
data class DedicatedEvidence(
    val source: String,
    val observedAt: Long,
    val provenance: DedicatedProvenance,
    val httpStatus: Int? = null,
    val providerVersion: String? = null,
    val note: String? = null,
)

/**
 * 三態結果：成功 / 能力不支援 / 本次失敗。
 * - [Unsupported]：該 provider 根本無此能力（例：NVIDIA hosted 無 quota structured endpoint），
 *   呼叫端跳過即可，不得視為 provider 失效。
 * - [Failure]：本次讀取失敗（401/429/網路/解析失敗），呼叫端必須保留 last-good、不得清空。
 */
sealed interface DedicatedResult<out T> {
    data class Success<out T>(val evidence: DedicatedEvidence, val value: T) : DedicatedResult<T>
    data class Unsupported(val evidence: DedicatedEvidence, val reason: String) : DedicatedResult<Nothing>
    data class Failure(val status: DedicatedStatus, val evidence: DedicatedEvidence) : DedicatedResult<Nothing>
}

/** 單一 quota 維度（RPM / RPD / TPM / TPD 等）：數值 + 狀態 + 上界旗標。Unknown 即 value=null。 */
data class QuotaDimension(
    val value: Long?,
    val state: QuotaValueState,
    /** true = 官方上界（例：NVIDIA `up to 40 for most models`），不可寫成 per-model 精確值。 */
    val upperBound: Boolean = false,
    /** true = 官方明言僅適用大多數模型，個人值需登入。 */
    val appliesToMostModels: Boolean = false,
    /** true = 唯一官方來源需登入才能取得。 */
    val requiresLogin: Boolean = false,
) {
    init {
        require(value == null || value >= 0) { "quota value must be null (Unknown) or non-negative" }
    }

    companion object {
        fun known(value: Long): QuotaDimension =
            QuotaDimension(value = value, state = QuotaValueState.KNOWN)

        fun unknown(): QuotaDimension = QuotaDimension(value = null, state = QuotaValueState.UNKNOWN)

        fun unpublished(): QuotaDimension =
            QuotaDimension(value = null, state = QuotaValueState.EXISTS_BUT_UNPUBLISHED)

        fun noDimension(): QuotaDimension =
            QuotaDimension(value = null, state = QuotaValueState.DIMENSION_NOT_APPLICABLE)

        fun noProviderQuota(): QuotaDimension =
            QuotaDimension(value = null, state = QuotaValueState.NO_PROVIDER_QUOTA)

        fun notOffered(): QuotaDimension =
            QuotaDimension(value = null, state = QuotaValueState.NOT_OFFERED)

        fun requiresLogin(): QuotaDimension = QuotaDimension(
            value = null,
            state = QuotaValueState.UNKNOWN_REQUIRES_LOGIN,
            requiresLogin = true,
        )
    }
}

/**
 * Resource Pool 單筆（per-model 不合併）。
 * `modelId == null` = provider / account / key 層級全域池；非 null = 該模型專屬池。
 * 同一模型在不同 Provider / Account / Credential / Group / Endpoint / Operation 即不同 scope，
 * 由呼叫端的 PricingScope 承載，本結構只保證「不把兩筆 pool 平均/覆蓋」。
 *
 * - `modelId` 只放純模型識別，不得併入 project/org 名稱或 `::priority` 之類後綴
 *   （ADAPT-005 reviewer 要求；project 放 [projectId]、priority/batch 放 [quotaSlice]）。
 * - `projectId`：project 粒度（Gemini per project × model；未知即 null，不猜）。
 * - `quotaSlice`：同一 project × model 下的獨立限額切片（標準／priority／batch）。
 */
data class QuotaPool(
    val scopeKind: QuotaScopeKind,
    val modelId: String?,
    val rpm: QuotaDimension = QuotaDimension.unknown(),
    val rpd: QuotaDimension = QuotaDimension.unknown(),
    val tpm: QuotaDimension = QuotaDimension.unknown(),
    val tpd: QuotaDimension = QuotaDimension.unknown(),
    /** 重置語意（例：`UTC_DAY` / `PACIFIC_MIDNIGHT` / `ROLLING_60S`）；未知即 null，不猜。 */
    val resetSemantics: String? = null,
    val provenance: DedicatedProvenance,
    /** true = per-account ground truth；false = 文件表（high-level summary，非 ground truth）。 */
    val isGroundTruth: Boolean = false,
    /** Project 粒度（Gemini per project × model）；未知即 null，不猜、不併入 modelId。 */
    val projectId: String? = null,
    /** 獨立限額切片（標準／priority／batch）；無切片概念時為 null（舊資料相容）。 */
    val quotaSlice: QuotaPoolSlice? = null,
)

/** 計價模式。`NO_PER_TOKEN_BILLING` / `PER_GPU_*` / `CREDITS_SPEND` 皆非「價格為 0」。 */
enum class PricingMode {
    PER_TOKEN,
    FIXED_PER_REQUEST,
    NO_PER_TOKEN_BILLING,
    PER_GPU_YEAR,
    PER_GPU_HOUR,
    CREDITS_SPEND,
    UNKNOWN,
}

/** 單模型價格事實（精確 decimal；缺失為 null，絕不用 0 代替）。 */
data class PricingFact(
    val modelId: String,
    val inputPer1kUsd: java.math.BigDecimal?,
    val outputPer1kUsd: java.math.BigDecimal?,
    val fixedPerRequestUsd: java.math.BigDecimal?,
    val mode: PricingMode,
    val provenance: DedicatedProvenance,
)

/**
 * 免費判定（derived state 輸入之一；最終 FREE_NOW 仍由 `PricingPolicy` + telemetry 判定）。
 * 注意：本 sealed 的所有工廠函式都不收 model name，從簽名層面禁止名稱判定。
 */
sealed interface FreeAssessment {
    data class Free(val evidence: String) : FreeAssessment
    data class NotFree(val evidence: String) : FreeAssessment
    data class NotOffered(val evidence: String) : FreeAssessment
    data class Unknown(val evidence: String) : FreeAssessment
}

/** 429 / 402 / 410 分開的錯誤分類（各家 classifier 必須三分支，不可合併）。 */
enum class DedicatedErrorCategory {
    RATE_LIMITED_MINUTE,
    RATE_LIMITED_DAILY,
    RATE_LIMITED_SPEND,
    UPSTREAM_RATE_LIMITED,
    INSUFFICIENT_CREDITS,
    IN_FLIGHT_BUDGET_TEMPORARY,
    SINGLE_REQUEST_EXCEEDS_BUDGET,
    KEY_CREDIT_EXHAUSTED,
    SPEND_BLOCKED,
    FLEX_CAPACITY_RETRYABLE_NOT_RATE_LIMIT,
    CALLER_CANCELLED,
    AUTH_INVALID,
    AUTH_OK_BUT_INFERENCE_DENIED,
    NOT_AVAILABLE_FOR_ACCOUNT,
    MODEL_RETIRED,
    UNEXPECTED_GONE_410,
    TEMPORARILY_UNAVAILABLE,
    DO_NOT_RETRY,
    RETRYABLE,
    SUCCESS_AS_LENGTH,
    UNKNOWN,
}

data class DedicatedError(
    val httpStatus: Int,
    val providerCode: String?,
    val category: DedicatedErrorCategory,
    val retryable: Boolean,
    val provenance: DedicatedProvenance,
)

/** Provider 種類（hosted 與 self-hosted / shared 與 dedicated 皆為獨立實例，不共用語意）。 */
enum class DedicatedProviderType {
    NVIDIA_NIM_HOSTED,
    NVIDIA_NIM_SELF_HOSTED,
    AMD_SHARED,
    AMD_DEDICATED,
    OPENROUTER_FREE,
    GEMINI_FREE,
    GROQ_FREE,
}

/** 獨立缺失聚合 + 不合併守衛（純函式）。 */
object DedicatedSync {
    /**
     * 聚合多個獨立能力結果：任一 [DedicatedResult.Unsupported] / [DedicatedResult.Failure]
     * 只影響自己，不拖垮其他 [DedicatedResult.Success]。
     * 回傳仍成功的 values；呼叫端對 Failure 必須保留 last-good（本函式不刪任何東西）。
     */
    fun <T> combineIndependent(results: List<DedicatedResult<T>>): List<T> =
        results.mapNotNull { (it as? DedicatedResult.Success)?.value }

    /**
     * per-model 不合併守衛：相同 (scopeKind, projectId, modelId, quotaSlice) 出現兩次即拋錯
     * （呼叫端不得平均/覆蓋）。
     * 全域池（modelId == null）允許至多一筆；多全域池同樣拋錯。
     * 同 model 可有多 scope 欄（per-key／per-IP／per-account 並存）或多切片
     * （標準／priority／batch 並存），故唯一鍵為四元組合；
     * 真正的合併（四元組完全相同出現兩次）才拒絕。
     *
     * null 切片語意（ADAPT-005／ADAPT-007；X-004）：`quotaSlice == null` 表「未區分」
     * （舊資料／無切片概念的 provider 相容；新程式碼應明確填寫）。
     * 未區分即涵蓋全體，故同一 (scopeKind, projectId, modelId) 下 null 不得與
     * 任何明確切片並存（否則 null 池是否已含切片資料無法判定，屬合併風險）；
     * 同鍵下全為 null（單筆）或全為明確且互異切片才放行。
     */
    fun assertNoMerge(pools: List<QuotaPool>) {
        // 同 model 可有多 scope 欄（per-key／per-IP／per-account 並存）或多切片
        // （標準／priority／batch 並存），故唯一鍵為組合；
        // 真正的合併（同 scope＋同 project＋同 model＋同切片出現兩次）才拒絕。
        val seen = HashSet<List<Any?>>()
        for (pool in pools) {
            require(seen.add(listOf(pool.scopeKind, pool.projectId, pool.modelId, pool.quotaSlice))) {
                "Quota pools must not be merged: duplicate scopeKind=${pool.scopeKind} " +
                    "projectId=${pool.projectId} modelId=${pool.modelId} quotaSlice=${pool.quotaSlice}"
            }
        }
        // null 切片不得與明確切片混用：同一 (scopeKind, projectId, modelId) 下
        // 既有 null 又有明確切片即拋錯（null 涵蓋全體，無法證明不重疊）。
        val byOwner = pools.groupBy { listOf(it.scopeKind, it.projectId, it.modelId) }
        for ((key, group) in byOwner) {
            val hasNull = group.any { it.quotaSlice == null }
            val hasExplicit = group.any { it.quotaSlice != null }
            require(!(hasNull && hasExplicit)) {
                "Quota pools must not mix null slice with explicit slices: " +
                    "scopeKind=${key[0]} projectId=${key[1]} modelId=${key[2]}"
            }
        }
    }

    /** HTTP status → 獨立 [DedicatedStatus]（429 / 402 / 410 永不折成 CLIENT_ERROR）。 */
    fun statusOf(httpCode: Int): DedicatedStatus = when (httpCode) {
        401 -> DedicatedStatus.HTTP_UNAUTHORIZED_401
        402 -> DedicatedStatus.HTTP_PAYMENT_REQUIRED_402
        403 -> DedicatedStatus.HTTP_FORBIDDEN_403
        404 -> DedicatedStatus.HTTP_NOT_FOUND_404
        410 -> DedicatedStatus.HTTP_GONE_410
        429 -> DedicatedStatus.HTTP_TOO_MANY_REQUESTS_429
        in 300..399 -> DedicatedStatus.HTTP_REDIRECT
        in 400..499 -> DedicatedStatus.HTTP_CLIENT_ERROR
        in 500..599 -> DedicatedStatus.HTTP_SERVER_ERROR
        else -> DedicatedStatus.UNKNOWN
    }

    /**
     * [DedicatedError] → 獨立 [DedicatedStatus] 接線（ADAPT-007；SPEC §9）。
     *
     * 以 `error.httpStatus`（傳輸層實際 HTTP status）經 [statusOf] 轉換；
     * 429 / 402 / 410 永不折成 CLIENT_ERROR。應用層語意（mid-stream body code、
     * length-as-success 等）由 [DedicatedError.category] 承載，本函式不覆寫：
     * 傳輸成功（2xx）但 body 帶錯仍回傳輸層 status，呼叫端以 category 判讀。
     */
    fun statusOf(error: DedicatedError): DedicatedStatus = statusOf(error.httpStatus)
}
