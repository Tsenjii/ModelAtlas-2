package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.SystemClock

/**
 * Groq Free adapter（免費是帳號 plan 屬性，非模型屬性）。
 *
 * TRACEABILITY: ADAPT-006（Groq Free model quota/rate limits）。
 * Spec: `PRODUCT_SPEC.md` §12 Groq Free（官方 model info + rate-limit docs/headers；
 * 可能 per-model RPM/RPD/TPM/TPD）。
 * 研究：`docs/PROVIDER_QUOTA_PRICING_RESEARCH.md` §1.4 / §2.3 / §3.4 / §5.3（B；
 * Free Plan Limits 官方表 10 列；官方自述 high-level summary，權威值在帳號 Limits 頁）。
 *
 * 已證實 contract（只吃這些）：
 * - 免費 = plan 維度（Free Plan vs Developer Plan 兩張獨立表）；models 頁 price 欄為付費速率，
 *   Free 額度與付費價分開存（X-004）。無 free-model catalog 概念；名稱/`$0` 皆非證據。
 * - Free Plan Limits 表（10 列，唯一一張，無分頁）逐模型 RPM/RPD/TPM/TPD/ASH/ASD；
 *   表外模型 + `ContactSales`（llama-3.1-8b-instant / llama-3.3-70b-versatile /
 *   minimax-m2.7 等）→ Unknown（D-003），不可據 Developer 欄推論 Free。
 * - Scope = per organization × per model（B）；任一 limit 先觸頂即超；TPM 外另有 ITPM/OTPM
 *   分離（hover `X in / Y out`，無數值表 → Unknown）；cached tokens 不計入。
 * - 8 種語意：RPM/RPD/TPM/TPD/ASH/ASD/ITPM/OTPM。Header 單位硬編碼：`...-requests` 三兄弟
 *   皆 RPD（日），`...-tokens` 三兄弟皆 TPM（分鐘）；`retry-after` 僅 429（秒），其餘每次都有；
 *   TPD 無對應 header（只能取官方表）。
 * - Error 全表 206/400/401/403/404/413/422/424/429/498/499/500/502/503（B）：
 *   429 throttle + retry-after；402 未列（付費阻擋 = 400 + `blocked_api_access`，org 全域）；
 *   410 未列（退役走 404）；498 Flex 容量（可重試但非 rate limit，獨立分類）；
 *   499 caller 取消；5xx 明文不計費；body 固定 `{error:{message,type}}`（無 metadata，
 *   不可與 OpenRouter 共用 parser）；424 Remote MCP 認證；422 含 hallucination；
 *   413/403 為 permission（非 moderation）。
 * - 官方表 ≠ ground truth（high-level summary，可能有例外）；免費無 spend limit 機制；
 *   downgrade 說明的 `unlimited requests` 未定義條件 → 不可標 unlimited（U）。
 */
class GroqFreeAdapter(
    private val clock: Clock = SystemClock,
) {
    val providerType: DedicatedProviderType = DedicatedProviderType.GROQ_FREE

    val supportedCapabilities: Set<DedicatedCapability> = setOf(
        DedicatedCapability.FETCH_PRICING,
        DedicatedCapability.FETCH_QUOTA_POOLS,
        DedicatedCapability.FETCH_ACCOUNT,
        DedicatedCapability.FETCH_CAPABILITIES,
    )

    // ---- Free Plan Limits 官方表（B；10 列全部；isGroundTruth=false） ----

    data class FreePlanRow(
        val modelId: String,
        val rpm: Long?,
        val rpd: Long?,
        val tpm: Long?,
        val tpd: Long?,
        val ash: Long?,
        val asd: Long?,
    )

    /** 官方表快照（documented table，非 per-account ground truth）。表外一律 Unknown。 */
    fun freePlanTable(): List<FreePlanRow> = FREE_PLAN_ROWS.toList()

    /**
     * Free Plan 官方表快照證據（ADAPT-006；`PROVIDER_QUOTA_PRICING_RESEARCH.md` §5.3）。
     *
     * 常數表為 2026-09-28 研究快照，非即時抓取：`observedAt` 固定為快照日期
     * [FREE_PLAN_OBSERVED_AT_MILLIS]，不用 `clock.now()` 冒充剛抓到；
     * `provenance` 為 OFFICIAL_DOC（逐列抄自官方表）；`isGroundTruth = false`
     * 由呼叫端 [quotaPools] 承載（官方自述 high-level summary，權威值在帳號 Limits 頁）。
     */
    fun freePlanEvidence(): DedicatedEvidence = DedicatedEvidence(
        source = SOURCE_LIMITS,
        observedAt = FREE_PLAN_OBSERVED_AT_MILLIS,
        provenance = DedicatedProvenance.OFFICIAL_DOC,
        note = "snapshot of official Free Plan Limits table as of 2026-09-28 (B); " +
            "high-level summary, may have exceptions; exact limits on account Limits page; not a live fetch",
    )

    /**
     * 免費判定：plan 維度。簽名只收 plan tier + 該模型是否有已公布 Free 額度，不收 model name。
     * `ContactSales` / 表外 → Unknown（非 0、非 unlimited）。
     */
    fun assessFree(planTier: String?, hasPublishedFreeLimits: Boolean?): FreeAssessment {
        val tier = planTier?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: return FreeAssessment.Unknown("plan tier 未知則 Unknown")
        if (!tier.equals("Free", ignoreCase = true)) {
            return FreeAssessment.NotFree("plan=$tier 非 Free（official plan 屬性）")
        }
        return when (hasPublishedFreeLimits) {
            true -> FreeAssessment.Free("plan=Free + 該模型有已公布 Free 額度（official table）")
            false -> FreeAssessment.Unknown("plan=Free 但該模型無已公布額度（ContactSales/表外）→ Unknown")
            null -> FreeAssessment.Unknown("Free 額度有無未知則 Unknown，不猜")
        }
    }

    // ---- Quota pools（per org × per model；逐模型存；缺值 Unknown；不合併） ----

    fun quotaPools(): List<QuotaPool> {
        val pools = FREE_PLAN_ROWS.map { row ->
            QuotaPool(
                scopeKind = QuotaScopeKind.PER_ORG_MODEL,
                modelId = row.modelId,
                rpm = row.rpm?.let { QuotaDimension.known(it) } ?: QuotaDimension.unknown(),
                rpd = row.rpd?.let { QuotaDimension.known(it) } ?: QuotaDimension.unknown(),
                tpm = row.tpm?.let { QuotaDimension.known(it) } ?: QuotaDimension.unknown(),
                tpd = row.tpd?.let { QuotaDimension.known(it) } ?: QuotaDimension.unknown(),
                resetSemantics = null,
                provenance = DedicatedProvenance.OFFICIAL_DOC,
                isGroundTruth = false,
            )
        }
        DedicatedSync.assertNoMerge(pools)
        return pools
    }

    fun fetchQuotaPools(): DedicatedResult<List<QuotaPool>> = DedicatedResult.Success(
        freePlanEvidence(),
        quotaPools(),
    )

    /** 表外模型 → 單筆 Unknown pool（明確非 0；isGroundTruth=false）。 */
    fun unknownPoolFor(modelId: String): QuotaPool {
        val mid = modelId.trim().takeIf { it.isNotEmpty() && it.length <= 256 } ?: "unknown-model"
        return QuotaPool(
            scopeKind = QuotaScopeKind.PER_ORG_MODEL,
            modelId = mid,
            rpm = QuotaDimension.unknown(),
            rpd = QuotaDimension.unknown(),
            tpm = QuotaDimension.unknown(),
            tpd = QuotaDimension.unknown(),
            resetSemantics = null,
            provenance = DedicatedProvenance.OFFICIAL_DOC,
            isGroundTruth = false,
        )
    }

    // ---- Headers（單位硬編碼對照；不可依名推斷；缺失即 Unknown） ----

    data class GroqHeaders(
        /** `x-ratelimit-*-requests` 三兄弟 → RPD（日）。 */
        val rpdLimit: Long?,
        val rpdRemaining: Long?,
        val rpdResetUnix: Long?,
        /** `x-ratelimit-*-tokens` 三兄弟 → TPM（分鐘）。 */
        val tpmLimit: Long?,
        val tpmRemaining: Long?,
        val tpmResetUnix: Long?,
        /** 僅 429，秒。 */
        val retryAfterSeconds: Long?,
    )

    fun parseHeaders(headers: Map<String, String>, httpStatus: Int): GroqHeaders {
        fun longOf(name: String): Long? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.toLongOrNull()
        return GroqHeaders(
            rpdLimit = longOf("x-ratelimit-limit-requests"),
            rpdRemaining = longOf("x-ratelimit-remaining-requests"),
            rpdResetUnix = longOf("x-ratelimit-reset-requests"),
            tpmLimit = longOf("x-ratelimit-limit-tokens"),
            tpmRemaining = longOf("x-ratelimit-remaining-tokens"),
            tpmResetUnix = longOf("x-ratelimit-reset-tokens"),
            retryAfterSeconds = if (httpStatus == 429) longOf("retry-after") else null,
        )
    }

    // ---- 錯誤語意（498/499 獨立；付費阻擋 400；410 缺席；429/402/410 分開） ----

    data class GroqErrorInput(
        val httpStatus: Int,
        val errorType: String?,
        val message: String?,
    )

    fun classifyError(input: GroqErrorInput): DedicatedError {
        val type = input.errorType?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
        val message = input.message?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        // 付費阻擋：400 + blocked_api_access（org 全域所有 key 一起被擋），不是 402。
        if (input.httpStatus == 400 && message.contains("blocked_api_access")) {
            return DedicatedError(input.httpStatus, type, DedicatedErrorCategory.SPEND_BLOCKED, false, DedicatedProvenance.OFFICIAL_DOC)
        }
        return when (input.httpStatus) {
            429 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.RATE_LIMITED_MINUTE, true, DedicatedProvenance.OFFICIAL_DOC)
            498 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.FLEX_CAPACITY_RETRYABLE_NOT_RATE_LIMIT, true, DedicatedProvenance.OFFICIAL_DOC)
            499 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.CALLER_CANCELLED, false, DedicatedProvenance.OFFICIAL_DOC)
            402 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
            410 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
            401 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.AUTH_INVALID, false, DedicatedProvenance.OFFICIAL_DOC)
            403 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OFFICIAL_DOC)
            404 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.MODEL_RETIRED, false, DedicatedProvenance.OFFICIAL_DOC)
            in 500..599 -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.TEMPORARILY_UNAVAILABLE, true, DedicatedProvenance.OFFICIAL_DOC)
            else -> DedicatedError(input.httpStatus, type, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
        }
    }

    /** 429 / 402 / 410 三者分開（402 未定義、410 未列 → 保留 OBSERVED）。 */
    fun classifyHttpStatus(httpStatus: Int, errorType: String? = null, message: String? = null): DedicatedError =
        classifyError(GroqErrorInput(httpStatus, errorType, message))

    companion object {
        const val SOURCE_LIMITS = "groq/rate-limits"
        const val SOURCE_MODELS = "groq/models"
        const val SOURCE_ERRORS = "groq/errors"

        /**
         * Free Plan 官方表快照時間（ADAPT-006 誠實化）：2026-09-28 00:00 UTC，
         * `PROVIDER_QUOTA_PRICING_RESEARCH.md` 觀測日期。常數表證據用此固定值，
         * 不用 `clock.now()`（[GroqFreeAdapter.freePlanEvidence]）。
         */
        const val FREE_PLAN_OBSERVED_AT_MILLIS = 1790553600000L

        /**
         * Free Plan Limits 官方表（B，HTML server-side render 完整抽出；10 列，無分頁）。
         * 注意：官方自述 high-level summary，權威值在帳號 Limits 頁（isGroundTruth=false）。
         */
        val FREE_PLAN_ROWS: List<FreePlanRow> = listOf(
            FreePlanRow("canopylabs/orpheus-arabic-saudi", rpm = 10, rpd = 100, tpm = 1200, tpd = 3600, ash = null, asd = null),
            FreePlanRow("canopylabs/orpheus-v1-english", rpm = 10, rpd = 100, tpm = 1200, tpd = 3600, ash = null, asd = null),
            FreePlanRow("meta-llama/llama-prompt-guard-2-22m", rpm = 30, rpd = 14400, tpm = 15000, tpd = 500000, ash = null, asd = null),
            FreePlanRow("meta-llama/llama-prompt-guard-2-86m", rpm = 30, rpd = 14400, tpm = 15000, tpd = 500000, ash = null, asd = null),
            FreePlanRow("openai/gpt-oss-120b", rpm = 30, rpd = 1000, tpm = 8000, tpd = 200000, ash = null, asd = null),
            FreePlanRow("openai/gpt-oss-20b", rpm = 30, rpd = 1000, tpm = 8000, tpd = 200000, ash = null, asd = null),
            FreePlanRow("openai/gpt-oss-safeguard-20b", rpm = 30, rpd = 1000, tpm = 8000, tpd = 200000, ash = null, asd = null),
            FreePlanRow("qwen/qwen3.8-27b", rpm = 30, rpd = 1000, tpm = 8000, tpd = 200000, ash = null, asd = null),
            FreePlanRow("whisper-large-v3", rpm = 20, rpd = 2000, tpm = null, tpd = null, ash = 7200, asd = 28800),
            FreePlanRow("whisper-large-v3-turbo", rpm = 20, rpd = 2000, tpm = null, tpd = null, ash = 7200, asd = 28800),
        )

        /** 官方表外（Free 無已公布額度）→ Unknown 的模型（節錄；D-003）。 */
        val MODELS_WITHOUT_PUBLISHED_FREE_LIMITS: Set<String> = setOf(
            "llama-3.1-8b-instant",
            "llama-3.3-70b-versatile",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "meta-llama/llama-guard-4-12b",
            "moonshotai/kimi-k2-instruct",
            "qwen/qwen3-32b",
            "qwen/qwen3.6-27b",
            "minimaxai/minimax-m2.7",
        )
    }
}
