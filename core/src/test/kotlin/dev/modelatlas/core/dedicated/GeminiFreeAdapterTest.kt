package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private object GmClock : Clock {
    override fun epochMillis(): Long = 1_760_000_000_000L
    override fun monotonicMillis(): Long = 1_760_000_000_000L
}

/**
 * Gemini Free 邊界測試（ADAPT-005；`docs/PROVIDER_QUOTA_PRICING_RESEARCH.md` §1.3/§2.2/§3.3/§5.2）。
 */
class GeminiFreeAdapterTest {

    @Test
    fun freeTierNotAvailableIsExplicitRefusalNotUnknown() {
        val adapter = GeminiFreeAdapter(GmClock)
        assertEquals(GeminiFreeAdapter.FreeTierCell.FREE_OF_CHARGE, adapter.parseFreeTierCell("Free of charge"))
        assertEquals(GeminiFreeAdapter.FreeTierCell.NOT_AVAILABLE, adapter.parseFreeTierCell("Not available"))
        assertEquals(GeminiFreeAdapter.FreeTierCell.UNKNOWN_CELL, adapter.parseFreeTierCell(null))
        assertTrue(adapter.assessFree(GeminiFreeAdapter.FreeTierCell.FREE_OF_CHARGE) is FreeAssessment.Free)
        // 明確拒絕與 Unknown 分欄（D-003 不可混用）。
        assertTrue(adapter.assessFree(GeminiFreeAdapter.FreeTierCell.NOT_AVAILABLE) is FreeAssessment.NotOffered)
        assertTrue(adapter.assessFree(GeminiFreeAdapter.FreeTierCell.UNKNOWN_CELL) is FreeAssessment.Unknown)
    }

    @Test
    fun quotaIsProjectTimesModelWithPacificResetAndLoginRequired() {
        val adapter = GeminiFreeAdapter(GmClock)
        val result = adapter.fetchQuotaPools("proj-1", "gemini-3-flash")
        assertTrue(result is DedicatedResult.Success)
        val pools = (result as DedicatedResult.Success).value
        assertEquals(1, pools.size)
        val pool = pools.single()
        assertEquals(QuotaScopeKind.PER_PROJECT_MODEL, pool.scopeKind)
        // projectId 另欄保存；modelId 只放純模型識別（禁併寫 pid::mid）。
        assertEquals("proj-1", pool.projectId)
        assertEquals("gemini-3-flash", pool.modelId)
        assertEquals(QuotaPoolSlice.STANDARD_INTERACTIVE, pool.quotaSlice)
        assertEquals(QuotaValueState.UNKNOWN_REQUIRES_LOGIN, pool.rpm.state)
        assertEquals(QuotaValueState.UNKNOWN_REQUIRES_LOGIN, pool.rpd.state)
        // TPD 僅部分模型可能有（存在性未證）→ Unknown，不猜 RequiresLogin。
        assertEquals(QuotaValueState.UNKNOWN, pool.tpd.state)
        assertEquals("PACIFIC_MIDNIGHT", pool.resetSemantics)
        // Priority 0.3x 為獨立切片（同 project×model 並存），不可與標準池相加/覆蓋。
        val priority = adapter.priorityQuotaPool("proj-1", "gemini-3-flash")
        assertEquals("proj-1", priority.projectId)
        assertEquals("gemini-3-flash", priority.modelId)
        assertEquals(QuotaPoolSlice.PRIORITY, priority.quotaSlice)
        // 不同 project 即不同池（projectId 另欄比較，不靠字串併寫）。
        val other = adapter.quotaPools("proj-2", "gemini-3-flash").single()
        assertTrue(other.projectId != pool.projectId)
        assertEquals(pool.modelId, other.modelId)
        // 空 id 拒絕。
        assertTrue(adapter.fetchQuotaPools("", "m") is DedicatedResult.Failure)
    }

    @Test
    fun priorityAndBatchAreIndependentSlicesNeverMerged() {
        val adapter = GeminiFreeAdapter(GmClock)
        val batch = adapter.batchQuotaPool("proj-1", "gemini-3-flash")
        assertEquals("proj-1", batch.projectId)
        assertEquals("gemini-3-flash", batch.modelId)
        assertEquals(QuotaPoolSlice.BATCH, batch.quotaSlice)
        // Batch reset 語意官方未述 → null，不猜。
        assertEquals(null, batch.resetSemantics)
        // 三切片同鍵並存：四元鍵各異，assertNoMerge 通過。
        val all = adapter.quotaPoolsAll("proj-1", "gemini-3-flash")
        assertEquals(3, all.size)
        assertEquals(
            setOf(
                QuotaPoolSlice.STANDARD_INTERACTIVE,
                QuotaPoolSlice.PRIORITY,
                QuotaPoolSlice.BATCH,
            ),
            all.map { it.quotaSlice }.toSet(),
        )
        assertTrue(all.all { it.projectId == "proj-1" && it.modelId == "gemini-3-flash" })
        assertTrue(adapter.quotaPoolsAll("", "m").isEmpty())
    }

    @Test
    fun errors429SplitThreeWaysAnd402IsDoNotRetry() {
        val adapter = GeminiFreeAdapter(GmClock)
        assertEquals(
            DedicatedErrorCategory.RATE_LIMITED_MINUTE,
            adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("rate_limit_exceeded", 429)).category,
        )
        assertEquals(
            DedicatedErrorCategory.RATE_LIMITED_DAILY,
            adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("quota_exceeded", 429)).category,
        )
        assertEquals(
            DedicatedErrorCategory.RATE_LIMITED_MINUTE,
            adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("too_many_requests", 429)).category,
        )
        val pay = adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("payment_required", 402))
        assertEquals(DedicatedErrorCategory.DO_NOT_RETRY, pay.category)
        assertEquals(false, pay.retryable)
        // 停用 billing 是 400 failed_precondition，不是 402。
        assertEquals(
            DedicatedErrorCategory.DO_NOT_RETRY,
            adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("failed_precondition", 400)).category,
        )
        // 退役走 404 model_not_found；410 缺席但傳輸層真實 → 專屬 UNEXPECTED_GONE_410。
        assertEquals(
            DedicatedErrorCategory.MODEL_RETIRED,
            adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("model_not_found", 404)).category,
        )
        assertEquals(DedicatedErrorCategory.UNEXPECTED_GONE_410, adapter.classifyHttpStatus(410).category)
        assertEquals(DedicatedErrorCategory.AUTH_INVALID, adapter.classifyHttpStatus(401, "authentication").category)
    }

    @Test
    fun unknownCodeFallsBackToHttpStatusBeforeGivingUp() {
        val adapter = GeminiFreeAdapter(GmClock)
        // else 分支先看 httpStatus：未知 code + 429 仍判分鐘級限流（可重試）。
        val odd429 = adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("unrecognized_future_code", 429))
        assertEquals(DedicatedErrorCategory.RATE_LIMITED_MINUTE, odd429.category)
        assertEquals(true, odd429.retryable)
        // 未知 code + 402 仍勿重試（官方 Don't retry 語意由 status 承載）。
        val odd402 = adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("unrecognized_future_code", 402))
        assertEquals(DedicatedErrorCategory.DO_NOT_RETRY, odd402.category)
        assertEquals(false, odd402.retryable)
        // 未知 code + 410 走專屬分類，不折成 UNKNOWN，更不誤判退役。
        val odd410 = adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("unrecognized_future_code", 410))
        assertEquals(DedicatedErrorCategory.UNEXPECTED_GONE_410, odd410.category)
        assertEquals(false, odd410.retryable)
        // 已知 code 優先於 httpStatus：payment_required 即使配 429 status 仍勿重試。
        val payOn429 = adapter.classifyError(GeminiFreeAdapter.GeminiErrorInput("payment_required", 429))
        assertEquals(DedicatedErrorCategory.DO_NOT_RETRY, payOn429.category)
        assertEquals(false, payOn429.retryable)
    }

    @Test
    fun spendTiersFollowOfficialText() {
        val tiers = GeminiFreeAdapter(GmClock).spendRateLimitPer10Min()
        assertEquals("N/A", tiers.free)
        assertEquals("$10", tiers.tier1)
        // Tier2 $50 vs 索引快取 $200 衝突以 B（直接取得純文字版）為準。
        assertEquals("$50", tiers.tier2)
        assertEquals("$200", tiers.tier3)
    }
}
