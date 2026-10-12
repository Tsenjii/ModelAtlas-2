package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.newapi.NewApiAccountQuota
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiPricing
import dev.modelatlas.core.newapi.NewApiPricingItem
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import kotlinx.coroutines.CancellationException

/**
 * 已知 adapter（New API structured surface）的能力探測實作（PROV-002 repair）。
 *
 * Spec：`PRODUCT_SPEC.md` §12（New API structured endpoints 優先）；
 * 研究：`docs/ADAPTER_NEWAPI_RESEARCH.md`（`v1.0.0-rc.25` 認證版）。
 * TRACEABILITY：PROV-002。
 *
 * - HTTP 全部重用既有 [NewApiAdapter]（`fetchCapabilities／fetchNotice／fetchPricing／
 *   fetchAccountQuota／fetchTokenQuota`），本檔不另造任何 HTTP 邏輯、不拼 URL、
 *   不跟 redirect、不重試（adapter 內已保證）。
 * - 版本關卡：只有 `CERTIFIED_RC25` 才走 version-specific 端點；未知版本一律
 *   [NoticeProbeOutcome.Unsupported] 且不發 request（adapter 的 versionGate 同理）。
 * - `requestsSpent` 語意（對照 adapter 程式路徑）：
 *   `INPUT_INVALID`（base 非法）／`UNKNOWN_VERSION`（版本關卡）／
 *   `CREDENTIAL_UNAVAILABLE`（vault 無值）三者在 exchange 之前回傳，故為 0；
 *   其餘（含傳輸失敗／MALFORMED／限流）皆已發送恰好 1 個 request。
 * - `/api/status` 回 404＝可達但無 New API 面→能力不支援（不是失敗）；
 *   回 200 但 shape 不合＝PROTOCOL_MISMATCH（不猜型別）。
 * - 帳號 quota 由官方 `quota／usedQuota` 比較推導（`used < quota` 才 AVAILABLE，
 *   以比較避開減法溢位；缺任一即 UNKNOWN）；key quota 的 state 直接採用
 *   adapter 依研究 §3.10 推導值，不重做判定。
 * - 定價只計數（paid／free／unknown），金額判定留給呼叫端（見 `PricingObservation`）；
 *   任一負數價格視為不可信→unknown（PricingPolicy 拒收負價，本處不崩潰）。
 * - 意外例外（非取消）轉 `Failure(UNKNOWN)` 並計 1（ reservation 已保留，往高估計費；
 *   呼叫端視為非 authoritative，不寫反向事實）。
 */
class NewApiPricingQuotaNoticeSource(
    private val adapter: NewApiAdapter,
) : PricingQuotaNoticeSource {

    override suspend fun probeCapabilities(baseUrl: String): NoticeAttempt<NoticeCapabilities> {
        val result = runProbe { adapter.fetchCapabilities(baseUrl) }
            ?: return crashed(EVIDENCE_CAPABILITIES)
        return when (result) {
            is NewApiResult.Success -> {
                val caps = result.value
                if (caps.version.support != NewApiVersionSupport.CERTIFIED_RC25) {
                    NoticeAttempt(NoticeProbeOutcome.Unsupported(EVIDENCE_VERSION_UNCERTIFIED), spentOf(null))
                } else {
                    NoticeAttempt(
                        NoticeProbeOutcome.Success(
                            NoticeCapabilities(
                                certifiedNewApi = true,
                                versionRaw = caps.version.raw,
                                checkinEnabled = caps.checkinEnabled,
                                quotaPerUnit = caps.quotaPerUnit?.toPlainString(),
                            ),
                            EVIDENCE_CAPABILITIES,
                        ),
                        1,
                    )
                }
            }
            is NewApiResult.Failure -> NoticeAttempt(
                failureOf(result.status, notNewApiOn404 = true, EVIDENCE_CAPABILITIES),
                spentOf(result.status),
            )
        }
    }

    override suspend fun probeNotice(baseUrl: String, versionRaw: String?): NoticeAttempt<NoticePresence> {
        val version = NewApiVersion.of(versionRaw)
        val result = runProbe { adapter.fetchNotice(baseUrl, version) }
            ?: return crashed(EVIDENCE_NOTICE)
        return when (result) {
            is NewApiResult.Success -> NoticeAttempt(
                NoticeProbeOutcome.Success(NoticePresence(result.value.present), EVIDENCE_NOTICE),
                1,
            )
            is NewApiResult.Failure -> NoticeAttempt(
                failureOf(result.status, notNewApiOn404 = false, EVIDENCE_NOTICE),
                spentOf(result.status),
            )
        }
    }

    override suspend fun probeTokenQuota(
        baseUrl: String,
        keyRef: String,
        versionRaw: String?,
    ): NoticeAttempt<KeyQuotaObservation> {
        val key = runCatching { InferenceKeyRef(keyRef) }.getOrNull()
            ?: return NoticeAttempt(NoticeProbeOutcome.Failure(OnboardingFailureReason.INPUT_INVALID, EVIDENCE_TOKEN_QUOTA), 0)
        val version = NewApiVersion.of(versionRaw)
        val result = runProbe { adapter.fetchTokenQuota(baseUrl, key, version) }
            ?: return crashed(EVIDENCE_TOKEN_QUOTA)
        return when (result) {
            is NewApiResult.Success -> {
                val quota = result.value
                NoticeAttempt(
                    NoticeProbeOutcome.Success(
                        KeyQuotaObservation(
                            state = quota.state,
                            available = quota.totalAvailable,
                            unlimited = quota.unlimitedQuota,
                        ),
                        EVIDENCE_TOKEN_QUOTA,
                    ),
                    1,
                )
            }
            is NewApiResult.Failure -> NoticeAttempt(
                failureOf(result.status, notNewApiOn404 = false, EVIDENCE_TOKEN_QUOTA),
                spentOf(result.status),
            )
        }
    }

    override suspend fun probeAccountQuota(
        baseUrl: String,
        patRef: String,
        versionRaw: String?,
    ): NoticeAttempt<AccountQuotaObservation> {
        val pat = runCatching { AccountPatRef(patRef) }.getOrNull()
            ?: return NoticeAttempt(NoticeProbeOutcome.Failure(OnboardingFailureReason.INPUT_INVALID, EVIDENCE_ACCOUNT_QUOTA), 0)
        val version = NewApiVersion.of(versionRaw)
        val result = runProbe { adapter.fetchAccountQuota(baseUrl, pat, version) }
            ?: return crashed(EVIDENCE_ACCOUNT_QUOTA)
        return when (result) {
            is NewApiResult.Success -> NoticeAttempt(
                NoticeProbeOutcome.Success(deriveAccountQuota(result.value), EVIDENCE_ACCOUNT_QUOTA),
                1,
            )
            is NewApiResult.Failure -> NoticeAttempt(
                failureOf(result.status, notNewApiOn404 = false, EVIDENCE_ACCOUNT_QUOTA),
                spentOf(result.status),
            )
        }
    }

    override suspend fun probePricing(
        baseUrl: String,
        patRef: String,
        versionRaw: String?,
    ): NoticeAttempt<PricingObservation> {
        val pat = runCatching { AccountPatRef(patRef) }.getOrNull()
            ?: return NoticeAttempt(NoticeProbeOutcome.Failure(OnboardingFailureReason.INPUT_INVALID, EVIDENCE_PRICING), 0)
        val version = NewApiVersion.of(versionRaw)
        val result = runProbe { adapter.fetchPricing(baseUrl, pat, version) }
            ?: return crashed(EVIDENCE_PRICING)
        return when (result) {
            is NewApiResult.Success -> NoticeAttempt(
                NoticeProbeOutcome.Success(classifyPricing(result.value), EVIDENCE_PRICING),
                1,
            )
            is NewApiResult.Failure -> NoticeAttempt(
                failureOf(result.status, notNewApiOn404 = false, EVIDENCE_PRICING),
                spentOf(result.status),
            )
        }
    }

    private suspend fun <T> runProbe(block: suspend () -> NewApiResult<T>): NewApiResult<T>? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun crashed(evidenceId: String): NoticeAttempt<Nothing> =
        NoticeAttempt(NoticeProbeOutcome.Failure(OnboardingFailureReason.UNKNOWN, evidenceId), 1)

    /**
     * adapter 內只有三條「exchange 前回傳」路徑（validatedBase／versionGate／
     * resolveCredential），其餘皆已發送恰好 1 個 request。
     */
    private fun spentOf(status: NewApiStatus?): Int = when (status) {
        NewApiStatus.INPUT_INVALID,
        NewApiStatus.UNKNOWN_VERSION,
        NewApiStatus.CREDENTIAL_UNAVAILABLE,
        -> 0
        else -> 1
    }

    private fun failureOf(
        status: NewApiStatus,
        notNewApiOn404: Boolean,
        evidenceId: String,
    ): NoticeProbeOutcome<Nothing> {
        if (status == NewApiStatus.HTTP_NOT_FOUND && notNewApiOn404) {
            return NoticeProbeOutcome.Unsupported(EVIDENCE_NOT_NEWAPI)
        }
        if (status == NewApiStatus.UNKNOWN_VERSION) {
            return NoticeProbeOutcome.Unsupported(EVIDENCE_VERSION_UNCERTIFIED)
        }
        val reason = when (status) {
            NewApiStatus.HTTP_UNAUTHORIZED,
            NewApiStatus.HTTP_FORBIDDEN,
            -> OnboardingFailureReason.AUTH_FAILED
            NewApiStatus.HTTP_TOO_MANY_REQUESTS -> OnboardingFailureReason.RATE_LIMITED
            NewApiStatus.NETWORK_UNREACHABLE -> OnboardingFailureReason.NETWORK_UNREACHABLE
            NewApiStatus.TIMEOUT -> OnboardingFailureReason.TIMEOUT
            NewApiStatus.TLS_FAILED -> OnboardingFailureReason.TLS_FAILED
            NewApiStatus.HTTP_SERVER_ERROR -> OnboardingFailureReason.SERVER_ERROR
            NewApiStatus.MALFORMED -> OnboardingFailureReason.PROTOCOL_MISMATCH
            NewApiStatus.INPUT_INVALID -> OnboardingFailureReason.INPUT_INVALID
            NewApiStatus.CREDENTIAL_UNAVAILABLE -> OnboardingFailureReason.AUTH_MISSING_CREDENTIAL
            else -> OnboardingFailureReason.UNKNOWN
        }
        return NoticeProbeOutcome.Failure(
            reason = reason,
            evidenceId = evidenceId,
            rateLimited = status == NewApiStatus.HTTP_TOO_MANY_REQUESTS,
        )
    }

    private fun deriveAccountQuota(quota: NewApiAccountQuota): AccountQuotaObservation {
        val total = quota.quota
        val used = quota.usedQuota
        val state = if (total != null && used != null) {
            if (used < total) QuotaState.QUOTA_AVAILABLE else QuotaState.QUOTA_EXHAUSTED
        } else {
            QuotaState.UNKNOWN
        }
        return AccountQuotaObservation(state = state, quota = total, usedQuota = used)
    }

    private fun classifyPricing(pricing: NewApiPricing): PricingObservation {
        var paid = 0
        var free = 0
        var unknown = 0
        for (item in pricing.items) {
            when (classifyItem(item)) {
                1 -> paid++
                0 -> free++
                else -> unknown++
            }
        }
        return PricingObservation(
            itemsTotal = pricing.items.size,
            paidItems = paid,
            freeItems = free,
            unknownItems = unknown,
        )
    }

    private fun classifyItem(item: NewApiPricingItem): Int {
        val numerics = listOfNotNull(
            item.modelRatio,
            item.modelPrice,
            item.completionRatio,
            item.cacheRatio,
            item.createCacheRatio,
            item.imageRatio,
            item.audioRatio,
            item.audioCompletionRatio,
        )
        if (numerics.isEmpty()) return -1
        if (numerics.any { it.signum() < 0 }) return -1
        return if (numerics.any { it.signum() > 0 }) 1 else 0
    }

    private companion object {
        const val EVIDENCE_CAPABILITIES = "newapi-capabilities"
        const val EVIDENCE_NOTICE = "newapi-notice"
        const val EVIDENCE_TOKEN_QUOTA = "newapi-token-quota"
        const val EVIDENCE_ACCOUNT_QUOTA = "newapi-account-quota"
        const val EVIDENCE_PRICING = "newapi-pricing"
        const val EVIDENCE_NOT_NEWAPI = "newapi-not-newapi-surface"
        const val EVIDENCE_VERSION_UNCERTIFIED = "newapi-version-uncertified"
    }
}
