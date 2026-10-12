package dev.modelatlas.core.dedicated

import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 合成 fixture；不得當成產品資料或真實端點回應。
private const val CALL_GUARD_MS = 15_000L
private const val API_REF = "safe-ref-nvidia-key-fixture"
private const val API_SECRET = "fixture-nvapi-secret-ZZ11"

private object FixedClock : Clock {
    override fun epochMillis(): Long = 1_760_000_000_000L
    override fun monotonicMillis(): Long = 1_760_000_000_000L
}

/**
 * NVIDIA hosted（ADAPT-001）+ self-hosted（ADAPT-002）邊界測試。
 *
 * 依據 `docs/NVIDIA_NIM_RESEARCH.md`（2026-09-28，docs-only，A=0）。
 */
class NvidiaAdaptersTest {
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

    // ---- ADAPT-001 hosted quota：RPM 上界 + RPD/TPD/TPM Unknown ----

    @Test
    fun hostedQuotaIsRpmUpperBoundWithEverythingElseUnknown() {
        val adapter = hostedAdapter()
        val result = adapter.fetchQuotaPools()
        assertTrue(result is DedicatedResult.Success)
        val pools = (result as DedicatedResult.Success).value
        assertEquals(1, pools.size)
        val pool = pools.single()
        // scope 官方未定義 → UNKNOWN（E 級 per-key 說法不用，禁猜）。
        assertEquals(QuotaScopeKind.UNKNOWN, pool.scopeKind)
        assertEquals(40L, pool.rpm.value)
        assertEquals(QuotaValueState.KNOWN, pool.rpm.state)
        assertTrue(pool.rpm.upperBound)
        assertTrue(pool.rpm.appliesToMostModels)
        assertNull(pool.rpd.value)
        assertNull(pool.tpm.value)
        assertNull(pool.tpd.value)
        assertEquals(QuotaValueState.UNKNOWN, pool.rpd.state)
        assertEquals(QuotaValueState.UNKNOWN, pool.tpd.state)
        assertEquals(DedicatedProvenance.OFFICIAL_DOC_VIA_INDEX, result.evidence.provenance)
    }

    @Test
    fun hostedPricingModeCarriesNoZeroPrice() {
        val adapter = hostedAdapter()
        val result = adapter.fetchPricing()
        assertTrue(result is DedicatedResult.Success)
        assertEquals(PricingMode.NO_PER_TOKEN_BILLING, (result as DedicatedResult.Success).value)
    }

    @Test
    fun hostedFreeNeedsFullChainAndNeverReadsNames() {
        val adapter = hostedAdapter()
        // 完整證據鏈才 Free；任一未知 → Unknown（不猜）。
        assertTrue(adapter.assessFree(true, true, true) is FreeAssessment.Free)
        assertTrue(adapter.assessFree(null, true, true) is FreeAssessment.Unknown)
        assertTrue(adapter.assessFree(true, null, true) is FreeAssessment.Unknown)
        assertTrue(adapter.assessFree(true, true, null) is FreeAssessment.Unknown)
        assertTrue(adapter.assessFree(false, true, true) is FreeAssessment.Unknown)
        // 簽名層面：assessFree 不收 model name，名稱含 free 亦無法輸入。
    }

    @Test
    fun hosted403And410ShareNotAvailableBut402StaysUnknown() {
        val adapter = hostedAdapter()
        val forbidden = adapter.classifyHttpStatus(403)
        val gone = adapter.classifyHttpStatus(410)
        val payment = adapter.classifyHttpStatus(402)
        val limited = adapter.classifyHttpStatus(429)
        assertEquals(DedicatedErrorCategory.NOT_AVAILABLE_FOR_ACCOUNT, forbidden.category)
        assertEquals(DedicatedErrorCategory.NOT_AVAILABLE_FOR_ACCOUNT, gone.category)
        // 同根因兩 status；402 不可套用付費不足假設。
        assertEquals(DedicatedErrorCategory.UNKNOWN, payment.category)
        assertEquals(DedicatedErrorCategory.TEMPORARILY_UNAVAILABLE, limited.category)
        // 無官方 contract → 一律 OBSERVED。
        assertEquals(DedicatedProvenance.OBSERVED, forbidden.provenance)
        assertEquals(DedicatedProvenance.OBSERVED, gone.provenance)
        assertEquals(DedicatedProvenance.OBSERVED, payment.provenance)
        assertEquals(ModelAvailability.NOT_AVAILABLE_FOR_ACCOUNT, adapter.availabilityOf(403))
        assertEquals(ModelAvailability.NOT_AVAILABLE_FOR_ACCOUNT, adapter.availabilityOf(410))
        assertEquals(ModelAvailability.VERIFIED_USABLE, adapter.availabilityOf(200))
        assertEquals(ModelAvailability.TEMPORARILY_UNAVAILABLE, adapter.availabilityOf(429))
    }

    @Test
    fun hostedProbeMapsHttpToAvailability() = runBlocking {
        val adapter = hostedAdapter()
        val base = baseUrl()

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"x","choices":[]}"""))
        val ok = withTimeout(CALL_GUARD_MS) { adapter.probeModel(base, API_REF, "meta/llama3-8b") }
        assertTrue(ok is DedicatedResult.Success)
        assertEquals(ModelAvailability.VERIFIED_USABLE, (ok as DedicatedResult.Success).value)
        val sent = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("Bearer $API_SECRET", sent.getHeader("Authorization"))
        assertTrue((sent.path ?: "").endsWith("/v1/chat/completions"))

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"status":403}"""))
        val denied = withTimeout(CALL_GUARD_MS) { adapter.probeModel(base, API_REF, "meta/llama3-8b") }
        assertTrue(denied is DedicatedResult.Failure)
        assertEquals(DedicatedStatus.HTTP_FORBIDDEN_403, (denied as DedicatedResult.Failure).status)

        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"status":429}"""))
        val limited = withTimeout(CALL_GUARD_MS) { adapter.probeModel(base, API_REF, "meta/llama3-8b") }
        assertTrue(limited is DedicatedResult.Failure)
        assertEquals(DedicatedStatus.HTTP_TOO_MANY_REQUESTS_429, (limited as DedicatedResult.Failure).status)
    }

    @Test
    fun hostedNoticeIsIndependentUnsupported() {
        val adapter = hostedAdapter()
        val notice = adapter.fetchNotice()
        assertTrue(notice is DedicatedResult.Unsupported)
        // 獨立缺失：不影響 quota（仍成功）。
        assertTrue(adapter.fetchQuotaPools() is DedicatedResult.Success)
    }

    // ---- ADAPT-002 self-hosted ----

    @Test
    fun selfHostedMetricsPathIsVersionAware() {
        val adapter = NvidiaSelfHostedAdapter(FixedClock)
        assertEquals("/metrics", adapter.metricsPathFor("1.1.0"))
        assertEquals("/v1/metrics", adapter.metricsPathFor("2.0.9"))
        assertEquals("/v1/metrics", adapter.metricsPathFor("3.0.0"))
        assertEquals("/v1/metrics", adapter.metricsPathFor("latest"))
        assertNull(adapter.metricsPathFor("9.9.9-fictional"))
        assertNull(adapter.metricsPathFor(null))
        // 推論端不驗證。
        assertEquals(false, adapter.inferenceAuthRequired())
    }

    @Test
    fun selfHostedReady503IsStartingNotUnstable() {
        val adapter = NvidiaSelfHostedAdapter(FixedClock)
        assertEquals(SelfHostedReadiness.READY, adapter.readinessOf(200))
        assertEquals(SelfHostedReadiness.STARTING, adapter.readinessOf(503))
        assertEquals(SelfHostedReadiness.UNKNOWN, adapter.readinessOf(500))
    }

    @Test
    fun selfHostedQuotaIsExplicitlyNoProviderQuota() {
        val adapter = NvidiaSelfHostedAdapter(FixedClock)
        val pools = (adapter.fetchQuotaPools() as DedicatedResult.Success).value
        assertEquals(1, pools.size)
        assertEquals(QuotaValueState.NO_PROVIDER_QUOTA, pools.single().rpm.state)
        assertEquals(QuotaValueState.NO_PROVIDER_QUOTA, pools.single().rpd.state)
        assertEquals(QuotaScopeKind.INSTANCE_OWNED, pools.single().scopeKind)
        assertEquals(
            setOf(PricingMode.PER_GPU_YEAR, PricingMode.PER_GPU_HOUR),
            adapter.billingModes(),
        )
        assertEquals(16L, adapter.licenseProjection().maxGpusDeveloperProgram)
    }

    @Test
    fun selfHostedMetricsSchemaNeverMixesVersions() {
        val adapter = NvidiaSelfHostedAdapter(FixedClock)
        val v1Text = "# HELP x\n" +
            "gpu_cache_usage_perc 12.5\n" +
            "num_requests_running 3\n" +
            "request_success_total 42\n"
        val v1 = adapter.parseMetrics(v1Text, "1.1.0")
        assertEquals(NvidiaSelfHostedAdapter.MetricsSchema.NIM_V1_NAMES, v1.schema)
        assertEquals(12.5, v1.nimV1.getValue("gpu_cache_usage_perc"), 0.0)
        assertEquals(DedicatedProvenance.SELF_HOSTED_OFFICIAL, v1.provenance)

        // 2.x 不硬套 1.x 指標名：同樣文字在 2.x 只計 passthrough，不解析 NIM 名。
        val v2 = adapter.parseMetrics(v1Text, "2.0.9")
        assertEquals(NvidiaSelfHostedAdapter.MetricsSchema.VLLM_NATIVE_PASSTHROUGH, v2.schema)
        assertTrue(v2.nimV1.isEmpty())
        assertEquals(3, v2.vllmPassthroughLines)
    }

    @Test
    fun selfHostedLoadedModelsDistinguishesEmptyFromMissing() {
        val adapter = NvidiaSelfHostedAdapter(FixedClock)
        val loaded = adapter.parseLoadedModels("""{"data":[{"id":"m-a"},{"id":"m-b"}]}""")
        assertTrue(loaded is DedicatedResult.Success)
        assertEquals(listOf("m-a", "m-b"), ((loaded as DedicatedResult.Success).value as NvidiaSelfHostedAdapter.LoadedModels.Loaded).ids)

        // 空陣列 = 尚未載入，非不存在、非 Unknown。
        val empty = adapter.parseLoadedModels("""{"data":[]}""")
        assertTrue(empty is DedicatedResult.Success)
        assertTrue((empty as DedicatedResult.Success).value is NvidiaSelfHostedAdapter.LoadedModels.EmptyNotLoaded)

        val broken = adapter.parseLoadedModels("""{"data":"nope"}""")
        assertTrue(broken is DedicatedResult.Failure)
    }

    @Test
    fun selfHostedRequestIdsAreClientGenerated() {
        val adapter = NvidiaSelfHostedAdapter(FixedClock)
        assertNotEquals(adapter.newRequestId(), adapter.newRequestId())
    }

    // ---- harness ----

    private fun hostedAdapter(): NvidiaHostedAdapter {
        val vault = CredentialAccess { ref, _ ->
            if (ref == API_REF) API_SECRET else throw IllegalStateException("refused")
        }
        return NvidiaHostedAdapter(vault, clock = FixedClock, allowLoopbackHttpForTests = true)
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.port}"
}
