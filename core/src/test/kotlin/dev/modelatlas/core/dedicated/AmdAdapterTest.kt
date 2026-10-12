package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.CredentialAccess
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 合成 fixture；不得當成產品資料。
private const val CALL_GUARD_MS = 15_000L
private const val KEY_REF = "safe-ref-amd-key-fixture"
private const val KEY_SECRET = "fixture-rc-secret-ZZ22"

private object AmdClock : dev.modelatlas.core.inference.Clock {
    override fun epochMillis(): Long = 1_760_000_000_000L
    override fun monotonicMillis(): Long = 1_760_000_000_000L
}

/**
 * AMD adapter 邊界測試（ADAPT-003；`docs/AMD_CLOUD_RESEARCH.md`）。
 */
class AmdAdapterTest {
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
    fun parsesStructuredModelsWithExactDecimals() {
        val adapter = amdAdapter()
        val body = """{"data":[
          {"id":"m-a","pricing":{"prompt":"0.00000014","completion":"0.00000028","input_cache_read":"0.00000002"},"context_length":131072,"free":true,"stability":"stable"},
          {"id":"m-b","pricing":{"prompt":"0.000001","completion":"0.000002"},"context_length":1048576,"free":false,"stability":"beta"},
          {"id":"","pricing":{}}
        ]}"""
        val result = adapter.parseModels(body)
        assertTrue(result is DedicatedResult.Success)
        val entries = (result as DedicatedResult.Success).value
        // 空 id 跳過。
        assertEquals(2, entries.size)
        assertEquals(0, entries[0].promptPerTokenUsd!!.compareTo(java.math.BigDecimal("0.00000014")))
        assertEquals(true, entries[0].free)
        assertEquals(131072L, entries[0].contextLength)
        assertEquals(false, entries[1].free)
    }

    @Test
    fun freeFlagAloneIsNeverEnoughAndNamesAreNeverRead() {
        val adapter = amdAdapter()
        // free:true 但 pricing 未知 → Unknown（不猜）。
        assertTrue(adapter.assessFree(null, null, true) is FreeAssessment.Unknown)
        // free:true + 雙零價 → Free；其餘 → Unknown（本層不斷言 Paid，避免越權）。
        assertTrue(
            adapter.assessFree(java.math.BigDecimal("0"), java.math.BigDecimal("0"), true) is FreeAssessment.Free,
        )
        assertTrue(
            adapter.assessFree(java.math.BigDecimal("0.000001"), java.math.BigDecimal("0"), true) is FreeAssessment.Unknown,
        )
        // 簽名不收 model name：名稱含 free 亦無法成為輸入。
    }

    @Test
    fun sharedQuotaKeepsThreeRpmColumnsAndUnknownRest() {
        val pools = amdAdapter().quotaPools()
        assertEquals(3, pools.size)
        val byScope = pools.associateBy { it.scopeKind }
        assertEquals(30L, byScope.getValue(QuotaScopeKind.PER_KEY).rpm.value)
        assertEquals(120L, byScope.getValue(QuotaScopeKind.PER_IP).rpm.value)
        assertEquals(20L, byScope.getValue(QuotaScopeKind.PER_ACCOUNT).rpm.value)
        for (pool in pools) {
            assertNull(pool.rpd.value)
            assertNull(pool.tpm.value)
            assertNull(pool.tpd.value)
            assertEquals(QuotaValueState.UNKNOWN, pool.rpd.state)
        }
        val dedicated = amdAdapter().dedicatedQuotaPools().single()
        assertEquals(QuotaScopeKind.INSTANCE_OWNED, dedicated.scopeKind)
        assertEquals(QuotaValueState.NO_PROVIDER_QUOTA, dedicated.rpm.state)
    }

    @Test
    fun usage502IsUnknownNotZeroAndByModelIsNotMerged() {
        val adapter = amdAdapter()
        val failed = adapter.parseUsage("""{"status":"ok"}""", 502)
        assertTrue(failed is DedicatedResult.Failure)
        assertEquals(DedicatedStatus.HTTP_SERVER_ERROR, (failed as DedicatedResult.Failure).status)

        val ok = adapter.parseUsage(
            """{"status":"ok","rpm_limit":30,"daily_cost_limit_usd":10,"daily_cost_used_usd":1.5,
               "daily_cost_remaining_usd":8.5,"daily_reset_at":"2026-09-28T00:00:00+08:00",
               "by_model":[{"model":"m-a","requests":10,"total_tokens":100,"cost":0.01},
                           {"model":"m-b","requests":5,"total_tokens":50,"cost":0.02}]}""",
            200,
        )
        assertTrue(ok is DedicatedResult.Success)
        val facts = (ok as DedicatedResult.Success).value
        assertEquals(2, facts.byModel.size)
        assertEquals("m-a", facts.byModel[0].model)
        assertEquals("m-b", facts.byModel[1].model)
        // 以當次回傳 reset 為準（不寫死午夜）。
        assertEquals("2026-09-28T00:00:00+08:00", facts.spend.resetAt)
    }

    @Test
    fun rateLimitHeadersMissingStaysUnknown() {
        val parsed = amdAdapter().parseRateLimitHeaders(emptyMap())
        assertNull(parsed.userRpmLimit)
        assertNull(parsed.dailyUsdRemaining)
        val full = amdAdapter().parseRateLimitHeaders(
            mapOf(
                "X-RateLimit-Limit-User-RPM" to "30",
                "X-RateLimit-Remaining-User-RPM" to "29",
                "X-RateLimit-Reset-User-RPM" to "60",
                "X-RateLimit-Remaining-User-Daily-USD" to "8.5",
            ),
        )
        assertEquals(30L, full.userRpmLimit)
        assertEquals(8.5, full.dailyUsdRemaining!!, 0.0)
    }

    @Test
    fun keyShapeIsRcPlus48Hex() {
        val adapter = amdAdapter()
        assertTrue(adapter.isKeyShapeValid("rc-" + "a".repeat(48)))
        assertFalse(adapter.isKeyShapeValid("sk-anything"))
        assertFalse(adapter.isKeyShapeValid("rc-short"))
        assertFalse(adapter.isKeyShapeValid(null))
    }

    @Test
    fun errors429402410AreSeparateAndDualEnvelopeIsRead() {
        val adapter = amdAdapter()
        assertEquals(DedicatedErrorCategory.RATE_LIMITED_MINUTE, adapter.classifyHttpStatus(429).category)
        assertEquals(DedicatedErrorCategory.UNKNOWN, adapter.classifyHttpStatus(402).category)
        assertEquals(DedicatedErrorCategory.UNKNOWN, adapter.classifyHttpStatus(410).category)
        assertEquals(DedicatedErrorCategory.AUTH_INVALID, adapter.classifyHttpStatus(401).category)
        // 雙 envelope：platform detail 形與 gateway error 形。
        assertEquals(
            "token_rate_limit_exceeded",
            adapter.extractProviderCode("""{"detail":{"error":{"message":"x","code":"token_rate_limit_exceeded"}}}"""),
        )
        assertEquals(
            "rate_limit_exceeded",
            adapter.extractProviderCode("""{"error":{"message":"x","code":"rate_limit_exceeded"}}"""),
        )
        assertTrue(adapter.isModelNotSupported400(400, "Requested model m-x not supported"))
        assertFalse(adapter.isModelNotSupported400(404, "Requested model m-x not supported"))
        assertEquals(ModelAvailability.CATALOG_LISTED, adapter.availabilityOf(400, "Requested model m-x not supported"))
        assertEquals(ModelAvailability.VERIFIED_USABLE, adapter.availabilityOf(200, ""))
    }

    @Test
    fun discoverModelsUsesBearerAndBoundedBody() = runBlocking {
        val adapter = amdAdapter()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"m-a","pricing":{"prompt":"0","completion":"0"},"free":true}]}"""),
        )
        val result = withTimeout(CALL_GUARD_MS) {
            adapter.discoverModels("http://127.0.0.1:${server.port}", KEY_REF)
        }
        assertTrue(result is DedicatedResult.Success)
        assertEquals("m-a", (result as DedicatedResult.Success).value.single().id)
        val sent = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("Bearer $KEY_SECRET", sent.getHeader("Authorization"))
        assertEquals("/v1/models", sent.path)
    }

    private fun amdAdapter(): AmdAdapter {
        val vault = CredentialAccess { ref, _ ->
            if (ref == KEY_REF) KEY_SECRET else throw IllegalStateException("refused")
        }
        return AmdAdapter(vault, clock = AmdClock, allowLoopbackHttpForTests = true)
    }
}
