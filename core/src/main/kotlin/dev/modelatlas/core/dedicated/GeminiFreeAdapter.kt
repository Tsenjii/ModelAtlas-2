package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.SystemClock

/**
 * Gemini Free adapter（Google Gemini Developer API）。
 *
 * TRACEABILITY: ADAPT-005（Gemini Free project×model quota）。
 * Spec: `PRODUCT_SPEC.md` §12 Gemini Free（Quota 可能是 Project × Model，
 * RPM/TPM/RPD 不得硬合 provider-global）。
 * 研究：`docs/PROVIDER_QUOTA_PRICING_RESEARCH.md` §1.3 / §2.2 / §3.3 / §5.2
 *（B；per-model 數值唯一官方來源需登入 AI Studio，本輪未取 → RequiresLogin）。
 *
 * 已證實 contract（只吃這些）：
 * - 免費判定唯一模型層級證據：pricing 頁 Free Tier 欄位 `Free of charge` vs `Not available`。
 *   `Not available` = 明確拒絕（NOT_OFFERED），非 Unknown；Playground `Can be tested`
 *   標記、模型名（flash/lite/preview）、試用皆非證據。
 * - Quota scope = per project × per model variation（B：`per project, not per API key`）。
 *   三維並立 RPM / TPM(input) / RPD（任一超即錯）；RPD 重置 Pacific 午夜（非 UTC/rolling）。
 * - Priority inference 獨立限額（預設 0.3x，計入整體 traffic，不可相加/覆蓋）；
 *   Batch 獨立（concurrent 100 / input 2GB / storage 20GB / 每模型 enqueued tokens）；
 *   spend-based rolling 10min（Free N/A、T1 $10、T2 $50、T3 $200）命中回 429。
 * - RPD / RPM 數值文件已不再公開 → [QuotaValueState.UNKNOWN_REQUIRES_LOGIN]；
 *   TPM 同（需登入）；TPD 僅部分模型可能有（存在性未證 → [QuotaValueState.UNKNOWN]）；
 *   官方免責 `Specified rate limits are not guaranteed and actual capacity may vary` 即合約一部分。
 * - Grounding `N RPD free` 為功能子額度（部分與他模型共享），絕不可當整體 RPD。
 * - Error 以 `code` 欄位為準：`rate_limit_exceeded`（分鐘）/ `quota_exceeded`（日）/
 *   spend-based 429 / `payment_required` 402（明文 Don't retry；billing 下所有 project/key
 *   同停）/ `failed_precondition` 400（billing 停用）/ `not_found`+`model_not_found` 404
 *   （退役走 404，非 410）/ `permission_denied` 403 / `authentication` 401。
 *   410 缺席。Client errors（400/402/403）官方明文勿重試。官方 SDK 預設退避 4 次，
 *   用 SDK 時不得疊第二層。
 */
class GeminiFreeAdapter(
    private val clock: Clock = SystemClock,
) {
    val providerType: DedicatedProviderType = DedicatedProviderType.GEMINI_FREE

    val supportedCapabilities: Set<DedicatedCapability> = setOf(
        DedicatedCapability.FETCH_PRICING,
        DedicatedCapability.FETCH_QUOTA_POOLS,
        DedicatedCapability.FETCH_ACCOUNT,
        DedicatedCapability.FETCH_CAPABILITIES,
    )

    // ---- 免費判定（pricing Free Tier 欄位；簽名不收 model name） ----

    enum class FreeTierCell { FREE_OF_CHARGE, NOT_AVAILABLE, UNKNOWN_CELL }

    fun parseFreeTierCell(raw: String?): FreeTierCell {
        val v = raw?.trim() ?: return FreeTierCell.UNKNOWN_CELL
        return when {
            v.equals("Free of charge", ignoreCase = true) -> FreeTierCell.FREE_OF_CHARGE
            v.equals("Not available", ignoreCase = true) -> FreeTierCell.NOT_AVAILABLE
            else -> FreeTierCell.UNKNOWN_CELL
        }
    }

    /** `Not available` = 明確拒絕（NOT_OFFERED），與 Unknown 分欄（D-003 不可混用）。 */
    fun assessFree(cell: FreeTierCell): FreeAssessment = when (cell) {
        FreeTierCell.FREE_OF_CHARGE -> FreeAssessment.Free("pricing Free Tier = Free of charge (official)")
        FreeTierCell.NOT_AVAILABLE -> FreeAssessment.NotOffered("pricing Free Tier = Not available (official explicit refusal)")
        FreeTierCell.UNKNOWN_CELL -> FreeAssessment.Unknown("Free Tier 欄位未知則 Unknown，不猜")
    }

    // ---- Quota（project × model；數值需登入；priority/batch 獨立切片） ----

    /**
     * 單一 project × model 標準 interactive 池。
     * `projectId` 另欄保存、`modelId` 只放純模型識別（reviewer 要求；禁併寫 `pid::mid`）。
     * RPM/RPD/TPM 官方數值需登入 → RequiresLogin；TPD 僅「部分模型可能有」
     * （存在性本身未證 → [QuotaValueState.UNKNOWN]，不猜）。
     * Priority 0.3x 與 Batch 另為獨立切片（見 [priorityQuotaPool]／[batchQuotaPool]），
     * 呼叫端分別保存，不可相加/覆蓋。
     */
    fun quotaPools(projectId: String, model: String): List<QuotaPool> {
        val pid = projectId.trim().takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: return emptyList()
        val mid = model.trim().takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: return emptyList()
        val pools = listOf(standardPool(pid, mid))
        DedicatedSync.assertNoMerge(pools)
        return pools
    }

    fun fetchQuotaPools(
        projectId: String,
        model: String,
    ): DedicatedResult<List<QuotaPool>> {
        val pools = quotaPools(projectId, model)
        if (pools.isEmpty()) {
            return DedicatedResult.Failure(
                DedicatedStatus.INPUT_INVALID,
                DedicatedEvidence(SOURCE_QUOTA, clock.epochMillis(), DedicatedProvenance.OFFICIAL_DOC),
            )
        }
        return DedicatedResult.Success(
            DedicatedEvidence(
                SOURCE_QUOTA,
                clock.epochMillis(),
                DedicatedProvenance.OFFICIAL_DOC,
                note = "per project x model (projectId/modelId/slice separate columns); " +
                    "RPM/RPD/TPM values require AI Studio login; TPD existence unconfirmed; " +
                    "Pacific midnight reset",
            ),
            pools,
        )
    }

    /**
     * Priority 限額池（0.3x 預設，計入整體 traffic；獨立切片 [QuotaPoolSlice.PRIORITY]，
     * 與標準池同 project×model 並存，不可相加/覆蓋）。
     */
    fun priorityQuotaPool(projectId: String, model: String): QuotaPool {
        val pid = validatedId(projectId, "projectId")
        val mid = validatedId(model, "model")
        return priorityPool(pid, mid)
    }

    /**
     * Batch 獨立限額池（concurrent 100 / input 2GB / storage 20GB /
     * 每模型 enqueued tokens；Free tier 數值未公開 → 全維 Unknown；
     * 獨立切片 [QuotaPoolSlice.BATCH]，reset 語意官方未述 → null，不猜）。
     */
    fun batchQuotaPool(projectId: String, model: String): QuotaPool {
        val pid = validatedId(projectId, "projectId")
        val mid = validatedId(model, "model")
        return batchPool(pid, mid)
    }

    /**
     * 同一 project × model 的三個獨立池（標準／priority／batch；四元鍵各異，
     * [DedicatedSync.assertNoMerge] 通過，不合併）。
     * 輸入非法回空列（與 [quotaPools] 一致，呼叫端轉 INPUT_INVALID）。
     */
    fun quotaPoolsAll(projectId: String, model: String): List<QuotaPool> {
        val pid = projectId.trim().takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: return emptyList()
        val mid = model.trim().takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: return emptyList()
        val pools = listOf(standardPool(pid, mid), priorityPool(pid, mid), batchPool(pid, mid))
        DedicatedSync.assertNoMerge(pools)
        return pools
    }

    private fun validatedId(raw: String, name: String): String {
        val v = raw.trim()
        require(v.isNotEmpty() && v.length <= 256) { "$name must be non-blank (<=256)" }
        return v
    }

    private fun standardPool(pid: String, mid: String): QuotaPool = QuotaPool(
        scopeKind = QuotaScopeKind.PER_PROJECT_MODEL,
        modelId = mid,
        rpm = QuotaDimension.requiresLogin(),
        rpd = QuotaDimension.requiresLogin(),
        tpm = QuotaDimension.requiresLogin(),
        tpd = QuotaDimension.unknown(),
        resetSemantics = "PACIFIC_MIDNIGHT",
        provenance = DedicatedProvenance.OFFICIAL_DOC,
        isGroundTruth = false,
        projectId = pid,
        quotaSlice = QuotaPoolSlice.STANDARD_INTERACTIVE,
    )

    private fun priorityPool(pid: String, mid: String): QuotaPool = QuotaPool(
        scopeKind = QuotaScopeKind.PER_PROJECT_MODEL,
        modelId = mid,
        rpm = QuotaDimension.unknown(),
        rpd = QuotaDimension.unknown(),
        tpm = QuotaDimension.unknown(),
        tpd = QuotaDimension.unknown(),
        resetSemantics = "PACIFIC_MIDNIGHT",
        provenance = DedicatedProvenance.OFFICIAL_DOC,
        isGroundTruth = false,
        projectId = pid,
        quotaSlice = QuotaPoolSlice.PRIORITY,
    )

    private fun batchPool(pid: String, mid: String): QuotaPool = QuotaPool(
        scopeKind = QuotaScopeKind.PER_PROJECT_MODEL,
        modelId = mid,
        rpm = QuotaDimension.unknown(),
        rpd = QuotaDimension.unknown(),
        tpm = QuotaDimension.unknown(),
        tpd = QuotaDimension.unknown(),
        resetSemantics = null,
        provenance = DedicatedProvenance.OFFICIAL_DOC,
        isGroundTruth = false,
        projectId = pid,
        quotaSlice = QuotaPoolSlice.BATCH,
    )

    // ---- 錯誤語意（code 欄位為準；未知 code 先看 httpStatus；429 三分；402 勿重試；400≠402；退役 404；410 專屬 UNEXPECTED_GONE_410） ----

    data class GeminiErrorInput(val code: String?, val httpStatus: Int)

    fun classifyError(input: GeminiErrorInput): DedicatedError {
        val code = input.code?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }
        return when (code) {
            "rate_limit_exceeded" -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.RATE_LIMITED_MINUTE, true, DedicatedProvenance.OFFICIAL_DOC)
            "quota_exceeded" -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.RATE_LIMITED_DAILY, false, DedicatedProvenance.OFFICIAL_DOC)
            "too_many_requests" -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.RATE_LIMITED_MINUTE, true, DedicatedProvenance.OFFICIAL_DOC)
            "payment_required" -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.DO_NOT_RETRY, false, DedicatedProvenance.OFFICIAL_DOC)
            "failed_precondition" -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.DO_NOT_RETRY, false, DedicatedProvenance.OFFICIAL_DOC)
            "model_not_found", "not_found" -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.MODEL_RETIRED, false, DedicatedProvenance.OFFICIAL_DOC)
            "permission_denied" -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.AUTH_OK_BUT_INFERENCE_DENIED, false, DedicatedProvenance.OFFICIAL_DOC)
            "authentication" -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.AUTH_INVALID, false, DedicatedProvenance.OFFICIAL_DOC)
            // code 缺席或未知：先看 httpStatus（傳輸層事實），再無才 Unknown（ADAPT-005）。
            // 410 官方表缺席但傳輸層真實 → 專屬 UNEXPECTED_GONE_410 接線，
            // 不折成 UNKNOWN，亦不誤判 MODEL_RETIRED（退役走 404 model_not_found）。
            else -> when (input.httpStatus) {
                429 -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.RATE_LIMITED_MINUTE, true, DedicatedProvenance.OBSERVED)
                402 -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.DO_NOT_RETRY, false, DedicatedProvenance.OFFICIAL_DOC)
                410 -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.UNEXPECTED_GONE_410, false, DedicatedProvenance.OBSERVED)
                else -> DedicatedError(input.httpStatus, code, DedicatedErrorCategory.UNKNOWN, false, DedicatedProvenance.OBSERVED)
            }
        }
    }

    /** 429 / 402 / 410 三者分開斷言用（410 官方表缺席 → 專屬 UNEXPECTED_GONE_410，不誤判退役）。 */
    fun classifyHttpStatus(httpStatus: Int, code: String? = null): DedicatedError =
        classifyError(GeminiErrorInput(code, httpStatus))

    // ---- Tier / spend（B；Tier2 $50 vs 索引快取 $200 衝突以 B 為準，保留 C 記錄） ----

    data class SpendTier(val free: String?, val tier1: String?, val tier2: String?, val tier3: String?)

    fun spendRateLimitPer10Min(): SpendTier = SpendTier(
        free = "N/A",
        tier1 = "$10",
        tier2 = "$50",
        tier3 = "$200",
    )

    companion object {
        const val SOURCE_QUOTA = "gemini/rate-limits"
        const val SOURCE_PRICING = "gemini/pricing"
        const val RESET_SEMANTICS = "PACIFIC_MIDNIGHT"
    }
}
