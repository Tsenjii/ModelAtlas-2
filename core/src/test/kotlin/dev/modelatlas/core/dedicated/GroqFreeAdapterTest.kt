package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private object GrClock : Clock {
    override fun epochMillis(): Long = 1_760_000_000_000L
    override fun monotonicMillis(): Long = 1_760_000_000_000L
}

/**
 * Groq Free 邊界測試（ADAPT-006；`docs/PROVIDER_QUOTA_PRICING_RESEARCH.md` §1.4/§2.3/§3.4/§5.3）。
 */
class GroqFreeAdapterTest {

    @Test
    fun freeTableHasTenRowsAndPerModelValuesArePreserved() {
        val adapter = GroqFreeAdapter(GrClock)
        assertEquals(10, adapter.freePlanTable().size)
        val pools = (adapter.fetchQuotaPools() as DedicatedResult.Success).value
        assertEquals(10, pools.size)
        val byModel = pools.associateBy { it.modelId }
        // 主力推論模型 RPD 達標但 TPD 200K < 1M（ordinary gate 明確不通過，非 Unknown）。
        assertEquals(1000L, byModel.getValue("openai/gpt-oss-120b").rpd.value)
        assertEquals(200000L, byModel.getValue("openai/gpt-oss-120b").tpd.value)
        // 安全模型最高 TPD 500K，仍 < 1M；per-model 不合併。
        assertEquals(14400L, byModel.getValue("meta-llama/llama-prompt-guard-2-22m").rpd.value)
        assertEquals(500000L, byModel.getValue("meta-llama/llama-prompt-guard-2-22m").tpd.value)
        // Whisper 無 TPM/TPD（語音維度另計 ASH/ASD 由官方表外欄承載，此處保持 Unknown）。
        assertNull(byModel.getValue("whisper-large-v3").tpm.value)
        assertEquals(QuotaScopeKind.PER_ORG_MODEL, pools.first().scopeKind)
        // 官方表 ≠ ground truth。
        assertEquals(false, pools.first().isGroundTruth)
        // 表外模型 → Unknown pool（非 0）。
        val unknown = adapter.unknownPoolFor("llama-3.3-70b-versatile")
        assertNull(unknown.rpd.value)
        assertEquals(QuotaValueState.UNKNOWN, unknown.rpd.state)
    }

    @Test
    fun freeIsPlanAttributeNeverModelName() {
        val adapter = GroqFreeAdapter(GrClock)
        assertTrue(adapter.assessFree("Free", true) is FreeAssessment.Free)
        // plan 非 Free → NotFree（即使模型名像免費）。
        assertTrue(adapter.assessFree("Developer", true) is FreeAssessment.NotFree)
        // plan=Free 但 ContactSales/表外 → Unknown（非 0、非 unlimited）。
        assertTrue(adapter.assessFree("Free", false) is FreeAssessment.Unknown)
        assertTrue(adapter.assessFree("Free", null) is FreeAssessment.Unknown)
        assertTrue(adapter.assessFree(null, true) is FreeAssessment.Unknown)
    }

    @Test
    fun headerUnitsAreHardcodedRequestsDailyTokensMinutely() {
        val adapter = GroqFreeAdapter(GrClock)
        val headers = adapter.parseHeaders(
            mapOf(
                "x-ratelimit-limit-requests" to "1000",
                "x-ratelimit-remaining-requests" to "999",
                "x-ratelimit-reset-requests" to "1700000000",
                "x-ratelimit-limit-tokens" to "8000",
                "x-ratelimit-remaining-tokens" to "7999",
                "x-ratelimit-reset-tokens" to "60",
                "retry-after" to "5",
            ),
            200,
        )
        // `...-requests` = 日（RPD），`...-tokens` = 分鐘（TPM）；不可依名推斷。
        assertEquals(1000L, headers.rpdLimit)
        assertEquals(999L, headers.rpdRemaining)
        assertEquals(8000L, headers.tpmLimit)
        // retry-after 僅 429 才取；200 即使有該 header 亦忽略。
        assertNull(headers.retryAfterSeconds)
        val limited = adapter.parseHeaders(mapOf("retry-after" to "5"), 429)
        assertEquals(5L, limited.retryAfterSeconds)
        val missing = adapter.parseHeaders(emptyMap(), 200)
        assertNull(missing.rpdLimit)
        assertNull(missing.tpmLimit)
    }

    @Test
    fun errors498499AreIndependentAndPaidBlockIs400() {
        val adapter = GroqFreeAdapter(GrClock)
        // 498 可重試但非 rate limit；499 caller 取消；兩者獨立。
        assertEquals(
            DedicatedErrorCategory.FLEX_CAPACITY_RETRYABLE_NOT_RATE_LIMIT,
            adapter.classifyHttpStatus(498).category,
        )
        assertTrue(adapter.classifyHttpStatus(498).retryable)
        assertEquals(DedicatedErrorCategory.CALLER_CANCELLED, adapter.classifyHttpStatus(499).category)
        assertEquals(DedicatedErrorCategory.RATE_LIMITED_MINUTE, adapter.classifyHttpStatus(429).category)
        // 付費阻擋 = 400 + blocked_api_access，不是 402。
        assertEquals(
            DedicatedErrorCategory.SPEND_BLOCKED,
            adapter.classifyError(GroqFreeAdapter.GroqErrorInput(400, "invalid_request_error", "blocked_api_access: spend limit")).category,
        )
        // 402 未定義、410 未列 → 保留。
        assertEquals(DedicatedErrorCategory.UNKNOWN, adapter.classifyHttpStatus(402).category)
        assertEquals(DedicatedErrorCategory.UNKNOWN, adapter.classifyHttpStatus(410).category)
        assertEquals(DedicatedErrorCategory.MODEL_RETIRED, adapter.classifyHttpStatus(404).category)
    }

    @Test
    fun freePlanEvidenceIsSnapshotNotLiveFetch() {
        val adapter = GroqFreeAdapter(GrClock)
        // 常數表為 2026-09-28 研究快照：observedAt 固定快照日期，不用 clock.now() 冒充剛抓到。
        val evidence = adapter.freePlanEvidence()
        assertEquals(GroqFreeAdapter.FREE_PLAN_OBSERVED_AT_MILLIS, evidence.observedAt)
        assertEquals(DedicatedProvenance.OFFICIAL_DOC, evidence.provenance)
        assertEquals(GroqFreeAdapter.SOURCE_LIMITS, evidence.source)
        // 官方表 ≠ ground truth（high-level summary，權威值在帳號 Limits 頁）。
        val pools = (adapter.fetchQuotaPools() as DedicatedResult.Success).value
        assertEquals(false, pools.first().isGroundTruth)
        // Unknown 維持 null：表外模型各維度值皆 null。
        val unknown = adapter.unknownPoolFor("llama-3.3-70b-versatile")
        assertNull(unknown.rpm.value)
        assertNull(unknown.tpd.value)
    }
}
