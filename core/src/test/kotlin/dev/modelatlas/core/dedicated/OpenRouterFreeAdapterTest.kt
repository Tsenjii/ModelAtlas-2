package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 合成 fixture；不得當成產品資料。
private const val CALL_GUARD_MS = 15_000L

private object OrClock : Clock {
    override fun epochMillis(): Long = 1_760_000_000_000L
    override fun monotonicMillis(): Long = 1_760_000_000_000L
}

/**
 * OpenRouter Free 邊界測試（ADAPT-004；`docs/PROVIDER_QUOTA_PRICING_RESEARCH.md` §1.2/§2.1/§3.2/§5.1）。
 */
class OpenRouterFreeAdapterTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun freeNeedsDoubleZeroPricingAndNeverReadsNames() {
        val adapter = openRouter()
        assertTrue(adapter.isFreeByPricing("0", "0") is FreeAssessment.Free)
        assertTrue(adapter.isFreeByPricing("0.0", "0") is FreeAssessment.Free)
        assertTrue(adapter.isFreeByPricing(null, "0") is FreeAssessment.Unknown)
        assertTrue(adapter.isFreeByPricing("0", null) is FreeAssessment.Unknown)
        assertTrue(adapter.isFreeByPricing("0.15", "0") is FreeAssessment.NotFree)
        // 名稱不是參數：`:free` 後綴存在亦不充分（仍須 pricing 雙零）。
        assertTrue(adapter.isFreeByPricing("0", "0") is FreeAssessment.Free)
        // 附加費用 null（不存在）vs "0"（免費）不同語意。
        assertEquals(SurchargeKind.ABSENT_UNKNOWN, adapter.surchargeKind(null))
        assertEquals(SurchargeKind.FREE_ZERO, adapter.surchargeKind("0"))
        assertEquals(SurchargeKind.CHARGED, adapter.surchargeKind("0.0001"))
        // 零值正規化：任何小數位數的零皆為免費；空字串／非數值回 Unknown（不猜 CHARGED）。
        assertEquals(SurchargeKind.FREE_ZERO, adapter.surchargeKind("0.000"))
        assertEquals(SurchargeKind.FREE_ZERO, adapter.surchargeKind(" 0 "))
        assertEquals(SurchargeKind.ABSENT_UNKNOWN, adapter.surchargeKind(""))
        assertEquals(SurchargeKind.ABSENT_UNKNOWN, adapter.surchargeKind("abc"))
    }

    @Test
    fun quotaTierFollowsCreditsNotIsFreeTier() {
        val adapter = openRouter()
        // <9 credits → 50；9 起 → 1000；is_free_tier 不可預測（9 credits 回 false 仍給高檔）。
        assertEquals(50L, adapter.quotaPools(null).single().rpd.value)
        assertEquals(50L, adapter.quotaPools(8).single().rpd.value)
        assertEquals(1000L, adapter.quotaPools(9).single().rpd.value)
        assertEquals(1000L, adapter.quotaPools(20).single().rpd.value)
        val pool = adapter.quotaPools(20).single()
        assertEquals(QuotaScopeKind.PLATFORM_GLOBAL, pool.scopeKind)
        assertEquals(20L, pool.rpm.value)
        assertNull(pool.tpd.value)
        assertEquals(QuotaValueState.UNKNOWN, pool.tpd.state)
    }

    @Test
    fun keyRemainingIsPolicyNotCeiling() {
        val adapter = openRouter()
        val result = adapter.parseKeyQuota(
            """{"data":{"is_free_tier":false,"free_model_daily_requests":{"used":10,"limit":1000,"remaining":990}}}""",
        )
        assertTrue(result is DedicatedResult.Success)
        val facts = (result as DedicatedResult.Success).value
        assertEquals(990L, facts.freeRemaining)
        assertTrue(facts.remainingIsPolicyNotCeiling)
        assertEquals(false, facts.isFreeTier)
    }

    @Test
    fun error429SplitsSelfVsUpstreamAnd402SplitsByLimitSource() {
        val adapter = openRouter()
        val self429 = adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(429, null, "rate_limit_exceeded", null, null, null, true, null),
        )
        val upstream429 = adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(429, null, "rate_limit_exceeded", "UPSTREAM_001", null, null, false, null),
        )
        assertEquals(DedicatedErrorCategory.RATE_LIMITED_MINUTE, self429.category)
        assertEquals(DedicatedErrorCategory.UPSTREAM_RATE_LIMITED, upstream429.category)

        val temp = adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(402, null, null, null, "openrouter_in_flight_budget", "in_flight_budget_exhausted", false, null),
        )
        val single = adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(402, null, null, null, "openrouter_in_flight_budget", "weight_exceeds_budget", false, null),
        )
        val credits = adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(402, null, null, null, "openrouter_credits", null, false, null),
        )
        val keyLimit = adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(402, null, null, null, "openrouter_key_limit", null, false, null),
        )
        val noSource = adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(402, null, null, null, null, null, false, null),
        )
        assertEquals(DedicatedErrorCategory.IN_FLIGHT_BUDGET_TEMPORARY, temp.category)
        assertTrue(temp.retryable)
        assertEquals(DedicatedErrorCategory.SINGLE_REQUEST_EXCEEDS_BUDGET, single.category)
        assertEquals(DedicatedErrorCategory.INSUFFICIENT_CREDITS, credits.category)
        assertEquals(DedicatedErrorCategory.KEY_CREDIT_EXHAUSTED, keyLimit.category)
        assertEquals(DedicatedErrorCategory.DO_NOT_RETRY, noSource.category)

        // 4 個 error_type 轉成功 + length，不得計失敗。
        val length = adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(200, 400, "context_length_exceeded", null, null, null, false, null),
        )
        assertEquals(DedicatedErrorCategory.SUCCESS_AS_LENGTH, length.category)

        // 410 保留/不預期。
        assertEquals(DedicatedErrorCategory.UNKNOWN, adapter.classifyError(
            OpenRouterFreeAdapter.ErrorInput(410, null, null, null, null, null, false, null),
        ).category)
    }

    @Test
    fun errorInputIsExtractedEvenOn200() {
        val adapter = openRouter()
        val mid = adapter.extractErrorInput(
            200,
            """{"error":{"code":429,"message":"x","metadata":{"error_type":"rate_limit_exceeded","provider_code":"P1"}}}""",
            false,
        )
        assertEquals(429, mid.bodyErrorCode)
        assertEquals("P1", mid.providerCode)
        val gone = adapter.extractErrorInput(410, """{"error":{"message":"x"}}""", false)
        assertEquals(410, gone.httpStatus)
    }

    @Test
    fun discoverModelsParsesPricingStrings() = runBlocking {
        val adapter = openRouter()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                // 真實形狀（ADAPT-004；A 觀測）：附加費用三欄為明確 JSON null，非缺鍵；
                // 第二筆為缺鍵對照（舊 schema 相容），兩者值皆 null（Unknown 維持 null）。
                """{"data":[
                  {"id":"qwen/qwen3.8-27b:free","pricing":{"prompt":"0","completion":"0","request":null,"image":null,"web_search":null}},
                  {"id":"paid-model","pricing":{"prompt":"0.15","completion":"0.6"}}
                ]}""",
            ),
        )
        val result = withTimeout(CALL_GUARD_MS) { adapter.discoverModels("http://127.0.0.1:${server.port}") }
        assertTrue(result is DedicatedResult.Success)
        val entries = (result as DedicatedResult.Success).value
        assertEquals(2, entries.size)
        assertTrue(adapter.isFreeByPricing(entries[0].prompt, entries[0].completion) is FreeAssessment.Free)
        assertTrue(adapter.isFreeByPricing(entries[1].prompt, entries[1].completion) is FreeAssessment.NotFree)
        // 明確 null vs 缺鍵：值同為 null（Unknown），存在旗標區分。
        assertNull(entries[0].request)
        assertTrue(entries[0].requestPresent)
        assertTrue(entries[0].imagePresent)
        assertTrue(entries[0].webSearchPresent)
        assertNull(entries[1].request)
        assertTrue(!entries[1].requestPresent)
        assertTrue(!entries[1].imagePresent)
        assertTrue(!entries[1].webSearchPresent)
        // 存在旗標不參與免費判定：雙零仍 Free，與附加欄存在與否無關。
        assertEquals(SurchargeKind.ABSENT_UNKNOWN, adapter.surchargeKind(entries[0].request))
        assertEquals(SurchargeKind.ABSENT_UNKNOWN, adapter.surchargeKind(entries[1].request))
    }

    private fun openRouter(): OpenRouterFreeAdapter {
        val vault = CredentialAccess { _, _ -> throw IllegalStateException("anonymous models; no credential") }
        return OpenRouterFreeAdapter(vault, clock = OrClock, allowLoopbackHttpForTests = true)
    }
}
