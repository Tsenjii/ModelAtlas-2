package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.QuotaState

/**
 * `PRICING_QUOTA_NOTICE` 步驟的能力探測來源（PROV-002 repair）。
 *
 * Spec：`PRODUCT_SPEC.md` §5（Quick Add 自動鏈：pricing／quota／rate-limit → notice／about／docs）、
 * §9（adapter capability 獨立缺失）、§12（New API structured endpoints 優先）；
 * `PROVIDER_ONBOARDING_CONTRACT.md` §1.2（step 7 `pricingQuotaNotice`，預算 ≤ 3 calls）/ §4（失敗分類）/ §5（quota 保護）。
 * TRACEABILITY：PROV-002。
 *
 * 契約：
 * - 已知 adapter（具 structured pricing／quota／notice 端點者）必須經此介面**真嘗試**；
 *   只有能力不支援（[NoticeProbeOutcome.Unsupported]）或本次失敗（[NoticeProbeOutcome.Failure]）
 *   才回報 Unknown；禁止不經嘗試直接填 UNKNOWN placeholder。
 * - 三態語意沿用 `DedicatedResult`（見 `core/dedicated/DedicatedContracts.kt`）：
 *   Unsupported＝該面根本無此能力（呼叫端跳過，不視為 provider 失效）；
 *   Failure＝本次讀取失敗（呼叫端保留 last-good，不得清空、不得裝成 authoritative 事實）。
 * - 每次探測至多 1 個 request；[NoticeAttempt.requestsSpent] 永遠是實際發送數，
 *   未發送（INPUT_INVALID／版本關卡／缺憑證）必為 0，呼叫端以此計費。
 * - 回傳只含分類／計數／安全 evidence id；不含 URL／key／server body／憑證原文。
 * - 取消（CancellationException）一律向上传遞，不轉分類（實作不得吞掉）。
 * - 定價隔離：觀測值只描述本次 scope（baseUrl＋所用憑證角色），不推論其他
 *   Provider／Account／Credential／Model／Group／Endpoint／Operation。
 */
sealed interface NoticeProbeOutcome<out T> {
    data class Success<out T>(val value: T, val evidenceId: String) : NoticeProbeOutcome<T>

    data class Unsupported(val evidenceId: String) : NoticeProbeOutcome<Nothing>

    data class Failure(
        val reason: OnboardingFailureReason,
        val evidenceId: String,
        /** true＝本次命中 429：呼叫端必須立即停手，不再嘗試後續能力（契約 §4 RATE_LIMITED_429）。 */
        val rateLimited: Boolean = false,
    ) : NoticeProbeOutcome<Nothing>
}

/** 單次能力探測回執：結果＋實際發送數（未發送必為 0）。 */
data class NoticeAttempt<out T>(
    val outcome: NoticeProbeOutcome<T>,
    val requestsSpent: Int,
) {
    init {
        require(requestsSpent >= 0) { "requestsSpent 不可為負" }
    }
}

/** 版本／能力事實（about 面）：是否為已認證的已知 adapter＋簽到開關。未知欄位為 null，不猜。 */
data class NoticeCapabilities(
    val certifiedNewApi: Boolean,
    val versionRaw: String?,
    val checkinEnabled: Boolean?,
    val quotaPerUnit: String?,
)

/** 公告存在性（notice 面）：只記存在與否，不帶出原文。 */
data class NoticePresence(val present: Boolean)

/** Key 層級 quota 觀測（inference 憑證）：state 由 adapter 依官方規則推導，不足即 UNKNOWN。 */
data class KeyQuotaObservation(
    val state: QuotaState,
    val available: Long?,
    val unlimited: Boolean?,
)

/** 帳號層級 quota 觀測（PAT）：state 由官方 quota／usedQuota 算術推導，缺任一即 UNKNOWN。 */
data class AccountQuotaObservation(
    val state: QuotaState,
    val quota: Long?,
    val usedQuota: Long?,
)

/**
 * 官方定價觀測（PAT）：只計數，不帶金額。
 *
 * - `paidItems > 0`＝至少一筆官方非零價格（VERIFIED_PAID 的唯一依據）。
 * - 全零且無未知項才可判全免；任一未知項即整體 UNKNOWN（缺價格≠免費）。
 */
data class PricingObservation(
    val itemsTotal: Int,
    val paidItems: Int,
    val freeItems: Int,
    val unknownItems: Int,
)

/**
 * 定價／配額／公告探測來源。
 *
 * @param versionRaw 能力探測回報的版本原文（未認證時呼叫端不應呼叫後續探測；
 * 即使誤呼，實作也必須以版本關卡拒絕且不發 request）。
 * @param keyRef inference 角色 safeCredentialId；@param patRef ACCOUNT（PAT）角色 safeCredentialId。
 * 原始 secret 永不經此介面，只在 HTTP 邊界由 CredentialAccess 取值。
 */
interface PricingQuotaNoticeSource {
    suspend fun probeCapabilities(baseUrl: String): NoticeAttempt<NoticeCapabilities>

    suspend fun probeNotice(baseUrl: String, versionRaw: String?): NoticeAttempt<NoticePresence>

    suspend fun probeTokenQuota(baseUrl: String, keyRef: String, versionRaw: String?): NoticeAttempt<KeyQuotaObservation>

    suspend fun probeAccountQuota(baseUrl: String, patRef: String, versionRaw: String?): NoticeAttempt<AccountQuotaObservation>

    suspend fun probePricing(baseUrl: String, patRef: String, versionRaw: String?): NoticeAttempt<PricingObservation>
}
