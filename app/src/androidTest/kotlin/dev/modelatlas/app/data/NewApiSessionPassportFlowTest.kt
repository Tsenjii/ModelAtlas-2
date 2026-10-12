// CR-009 session-passport flow test — landed form of /tmp/opencode/ai9-cr009-test-final-reanchored.kt
// (unit cr009-tests-reanchor-2, re-anchored vectors; production landed in this same integration
// slice via ai9-cr009-passport-final.patch: NewApiSessionPassport.kt + AtlasRepository/
// NewApiSnapshotStore/ProviderPerfStore/AtlasViewModel passport call sites + F4 import).
// 9 vectors: wave 7/11/8 arithmetic, summaryFacts=0 persisted-once, registry lifetime /
// cancel-skip-remember, strict-PAT (marked PROPOSED where the production option is deferred).

package dev.modelatlas.app.data

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiPerfModelSummary
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiModuleAccess
import dev.modelatlas.core.newapi.NewApiPerfPoint
import dev.modelatlas.core.newapi.NewApiPerfWindow
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-009 wave/passport oracle — LANDED form (production landed in this same
 * integration slice: NewApiSessionPassport.kt + repo/store/VM call sites + F4 import).
 * Vectors byte-identical to the re-anchored draft; O-1 resolved as assertFalse
 * (matchesSummary returns non-nullable Boolean).
 *
 * 兩層證據（如實分層，不誇大）：
 * - **Flow 層（真鏈路）**：真 VM／真 repository／真 store／真 adapter／loopback 真
 *   socket（MockWebServer）→ 真裝置 androidTest。與 [ProviderPerfBundleFlowTest]
 *   （HEAD 12-call baseline `:284`）同一 harness 形狀；PROPOSED 斷言打在
 *   post-patch 行為（12→7、memo hit persisted-once、registry 生命週期）。
 * - **Unit 層（PROPOSED PORT）**：對提案的 [NewApiSessionPassport]（hunk 1，byte
 *   -identical 草稿 `ai9-cr009-preland-c1.frag`）直接打 exact-match contract
 *   （create/matchesLeg/matchesSummary/memberGoodCount）；R-2 的 window-miss 向量
 *   只在這一層可表達（production flow 兩側恆 168）。
 *
 * PROPOSED PORT 斷言標記規則：每個依賴 hunk 1–6 符號的斷言前都有
 * `PROPOSED PORT:` 註解；移除該檔宣告前不得視為任何 acceptance。
 *
 * R-1（carry，不得違反）：token 腿在提案中傳 `pat = passport.patRef` 給
 * `matchesLeg`（自我比較，PAT 維度在 token 腿**由構造即 vacuous**）——本檔**不寫**
 * 「token 腿遇不同 PAT ref 則 miss」的負向測試。Strict-PAT 選項見檔尾
 * PROPOSED STRICT-PAT 段（Main 未決，僅 PROPOSED）。
 *
 * R-2（carry，不得違反）：兩條 G1 腿傳 `hours = passport.hours`（自我比較）——
 * 本檔**不寫**「G1 caps reuse 遇不同 window 則 miss」的負向測試；window-miss
 * 向量只在 unit 層的 `matchesSummary` 上（V-U8）。
 *
 * F7／P-empty（open product lock — 不得先凍結 oracle）：本檔**不**凍結 N-member
 * empty-wave memo-hit 計數（1 memo-hit vs N failure markers 待 Main 決）。
 *
 * Fixture 資料聲明：與 [ProviderPerfBundleFlowTest] 同一 rc.25
 * source-derived synthetic 口徑；僅本檔、僅供 loopback 傳輸，不是 live 產品資料。
 */
@RunWith(AndroidJUnit4::class)
class NewApiSessionPassportFlowTest {

    private lateinit var app: AtlasApplication
    private lateinit var vmStore: ViewModelStore
    private lateinit var vm: AtlasViewModel
    private lateinit var server: MockWebServer

    private val providerIds = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "must not run on main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        vmStore = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::vmStore.isInitialized) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { runCatching { vmStore.clear() } }
        }
        runCatching { server.shutdown() }
        deleteOwnRows()
        providerIds.clear()
    }

    // =================================================================================
    // V-1 — 全 passport wave（G1+G2）：baseline 12 → 7；memo hit persisted-once
    // （summaryFacts = 0、零寫、零 history dupe）；later leader 消費 VM 供給的 passport。
    // PROPOSED PORT：7-count 與 census 斷言依賴 hunks 1–6 全部落地。
    // =================================================================================

    @Test
    fun fullPassportWaveAnchorsBaselineTwelveToSevenAndMemoHitPersistsSummaryOnce() {
        val tag = shortId()
        val providerId = "passport-flow-full-$tag"
        val accountId = "a-passport-full-$tag"
        val patRef = "pat-passport-full-$tag"
        val keyRef = "key-passport-full-$tag"
        val patSecret = "passport-pat-secret-full-$tag-0123456789"
        val keySecret = "passport-key-secret-full-$tag-9876543210"
        val endpointId = "e-passport-full-$tag"
        val modelA = "passport-full-a-$tag"
        val modelB = "passport-full-b-$tag"
        providerIds += providerId

        val summaryA = PerfModel(modelA, 111L, 98.5, 11.25, listOf(97.0, 98.0, 98.5))
        val summaryB = PerfModel(modelB, 222L, 77.25, 33.5, listOf(70.0, 75.0, 77.25))
        val groupsA = listOf(
            PerfGroup("default", 120L, 300L, 98.5, 11.25, listOf(
                PerfPoint(1_760_000_100L, 120L, 300L, 98.5, 11.25),
            )),
        )
        val groupsB = listOf(
            PerfGroup("default", 900L, 1800L, 70.5, 30.0, listOf(
                PerfPoint(1_760_000_400L, 900L, 1800L, 70.5, 30.0),
            )),
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(modelA, modelB))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, modelA, source = "USER", nowMillis = 5_000L))
                assertTrue(store.addToMyModels(providerId, modelB, source = "USER", nowMillis = 5_001L))

                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(modelA to "0", modelB to "2"),
                    summaryModels = listOf(summaryA, summaryB),
                    queryGroups = { model ->
                        when (model) {
                            modelA -> groupsA
                            modelB -> groupsB
                            else -> emptyList()
                        }
                    },
                )
                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))

                // Seed：2-arg positional 與提案相容（passport trailing-defaulted）。
                val seedLoopback = PerfLoopback(server)
                val seeded = app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals(2, seeded.prices.size)
                assertEquals("seed sync runs the 4-call provider-wide contract (own-passport self-hit)", 4, seedLoopback.total())
                drainRequests(expected = 4)
                assertEquals(
                    NewApiVersionSupport.CERTIFIED_RC25,
                    app.repository.providerPerf.certifiedProviderVersion(providerId).support,
                )
                assertEquals(168L, ProviderPerfStore.DEFAULT_ENRICHMENT_HOURS.toLong())
                val sampleBaseline = countRows("samples", providerId)

                // Wave：A 領先（pricing 真阻塞），B 排隊於 provider lock。
                val releasePricing = CompletableDeferred<Unit>()
                val loopback = PerfLoopback(server, gateFirstPricing = true, releaseFirstPricing = releasePricing)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val pricingBuilds = AtomicInteger(0)
                val perfBuilds = AtomicInteger(0)
                val pricingFactory = {
                    pricingBuilds.incrementAndGet()
                    NewApiAdapter(vault, client)
                }
                val perfFactory = {
                    perfBuilds.incrementAndGet()
                    NewApiAdapter(vault, client)
                }
                val baseline = server.requestCount
                val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            credentialId = null,
                            group = null,
                            providerModelId = modelA,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = pricingFactory,
                            newApiPerfAdapterFactory = perfFactory,
                        )
                    }
                }
                var jobB: kotlinx.coroutines.Deferred<Boolean>? = null
                try {
                    withTimeout(TIMEOUT_MS) { loopback.firstPricing.await() }
                    assertEquals(1, loopback.pricing.get())
                    assertEquals("the gate must actually hold the first pricing request", 0, loopback.perfQuery.get())

                    val second = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_MS) {
                            vm.maybeEnrichMyModel(
                                providerId = providerId,
                                accountId = accountId,
                                credentialId = null,
                                group = null,
                                providerModelId = modelB,
                                lmspeedFetcher = null,
                                newApiAdapterFactory = pricingFactory,
                                newApiPerfAdapterFactory = perfFactory,
                            )
                        }
                    }
                    jobB = second
                    assertFalse("second model must queue while the first pricing leg is gated", second.isCompleted)
                    assertEquals("queued leader must not burn a second pricing fetch", 1, loopback.pricing.get())
                    assertEquals("no perf request may start before the pricing barrier releases", 0, loopback.perfQuery.get())
                } finally {
                    releasePricing.complete(Unit)
                }
                assertTrue("first model bundle must complete", withTimeout(TIMEOUT_MS) { jobA.await() })
                assertTrue("queued model bundle must complete", withTimeout(TIMEOUT_MS) { jobB!!.await() })

                // ---- PROPOSED PORT ORACLE：12 → 7（H6 registry 消費 + H4 caps reuse +
                // H5 memo hit）。HEAD baseline :284 = 12；全 passport 下：A = 6
                // （outer status1＋pricing1＋account1＋token1〔matchesLeg 各跳 1 status〕
                // ＋summary1〔miss→fetch+persist+remember〕＋query1）；B = 1（caps 跳過＋
                // memo HIT 0 HTTP＋query1）。改變即誠實失敗。
                assertEquals(7, server.requestCount - baseline)

                // Census（ RecordedRequest.path 含 query，故用 encodedPath 判斷）：
                val recorded = drainRequests(expected = 7)
                assertEquals(1, recorded.count { it.encodedPath() == "/api/status" })
                assertEquals(1, recorded.count { it.encodedPath() == "/api/pricing" })
                assertEquals(1, recorded.count { it.encodedPath() == "/api/user/self" })
                assertEquals(1, recorded.count { it.encodedPath() == "/api/usage/token/" })
                assertEquals(
                    "B's memo hit must issue zero summary HTTP requests",
                    1,
                    recorded.count { it.encodedPath() == "/api/perf-metrics/summary" },
                )
                val queries = recorded.filter { it.encodedPath() == "/api/perf-metrics" }
                assertEquals(2, queries.size)
                assertEquals(
                    listOf(modelA, modelB),
                    queries.map { requireNotNull(it.requestUrl!!.queryParameter("model")) }.sorted(),
                )
                for (query in queries) {
                    assertEquals(listOf("168"), query.requestUrl!!.queryParameterValues("hours"))
                    assertNull(query.requestUrl!!.queryParameter("group"))
                }
                // Adapter 建構不受 passport 影響（只共享 caps/summary 觀察）。
                assertEquals(1, pricingBuilds.get())
                assertEquals(2, perfBuilds.get())

                // PROPOSED PORT: summaryFacts = 0 persisted-once — memo hit 零寫。
                // 只有 A 的 summary fetch 落庫一次（provider-wide，兩 model 各一列）；
                // B 的 hit 不再 persistSummaryRows：per-model SUMMARY history 各恰 1 列
                // （HEAD baseline :380 為 2——每 model 腿各跑一次；passport 下 1）。
                val perf = app.repository.providerPerf
                assertEquals(
                    "A's summary fetch must persist exactly once",
                    1,
                    perf.history(providerId, modelA, null, 168, ProviderPerfKind.SUMMARY).size,
                )
                assertEquals(
                    "B's memo hit must write zero summary rows (persisted-once)",
                    1,
                    perf.history(providerId, modelB, null, 168, ProviderPerfKind.SUMMARY).size,
                )
                val sumA = perf.observation(providerId, modelA, null, 168, ProviderPerfKind.SUMMARY)
                assertEquals(111L, sumA.lastGood!!.avgLatencyMs!!)
                assertEquals(98.5, sumA.lastGood!!.successRate!!, 0.0)
                val sumB = perf.observation(providerId, modelB, null, 168, ProviderPerfKind.SUMMARY)
                assertEquals(222L, sumB.lastGood!!.avgLatencyMs!!)
                // B's last-good comes from A's provider-wide summary fetch (one fetch
                // persists BOTH member models' summaries — hence B's persisted-once
                // history row above); B's own memo hit writes zero. So B's rate is B's
                // own fixture value (77.25), not A's (98.5).
                assertEquals(77.25, sumB.lastGood!!.successRate!!, 0.0)

                // Per-model GROUP 事實仍各自存在（per-model query 在 hit/miss 都跑）。
                assertNotNull(perf.observation(providerId, modelA, "default", 168, ProviderPerfKind.GROUP).lastGood)
                assertNotNull(perf.observation(providerId, modelB, "default", 168, ProviderPerfKind.GROUP).lastGood)
                assertEquals(
                    "query leg must run for every member even on memo hit",
                    1,
                    perf.history(providerId, modelB, "default", 168, ProviderPerfKind.GROUP).size,
                )

                // 認證邊界（不變）：perf 端點只帶管理 PAT；inference key 永不進 /api/…。
                for (request in recorded.filter { it.encodedPath().startsWith("/api/perf-metrics") }) {
                    assertEquals("Bearer $patSecret", request.getHeader("Authorization"))
                    assertFalse(request.getHeader("Authorization")!!.contains(keySecret))
                }
                assertTrue(vault.asked.any { it.startsWith("$patRef@") })

                // 槽位分離（HEAD :409-416 同口徑）：samples 不動；price/quota +2。
                assertEquals(sampleBaseline, countRows("samples", providerId))
                val scopeA = PricingScope(providerId, accountId, null, modelA, "default", endpointId, "CHAT_COMPLETIONS")
                val scopeB = PricingScope(providerId, accountId, null, modelB, "default", endpointId, "CHAT_COMPLETIONS")
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(scopeA).latest!!.state)
                assertEquals("PAID", app.repository.newApiSnapshots.price(scopeB).latest!!.state)
                assertTrue(store.isMyModel(providerId, modelA))
                assertTrue(store.isMyModel(providerId, modelB))
                assertPerfMetricsAbsentFromOtherSlots(providerId, listOf("1760000100", "1760000400", SERIES_SCHEMA))
            }
        }
    }

    // =================================================================================
    // V-2 — Registry 生命週期（結構性 drain，無 clock/TTL）：wave 結束（flight 全數
    // 移除）→ passport 清除 → 下一波 refetch fresh：7 再現（永不 2/4/1）。
    // PROPOSED PORT：依賴 hunk 6 drain-clear（兩條 cleanup 路徑同一 critical section）。
    // =================================================================================

    @Test
    fun passportRegistryClearsOnStructuralDrainAndNextWaveRefetchesFresh() {
        val tag = shortId()
        val providerId = "passport-flow-drain-$tag"
        val accountId = "a-passport-drain-$tag"
        val patRef = "pat-passport-drain-$tag"
        val keyRef = "key-passport-drain-$tag"
        val patSecret = "passport-pat-secret-drain-$tag-0123456789"
        val keySecret = "passport-key-secret-drain-$tag-9876543210"
        val endpointId = "e-passport-drain-$tag"
        val modelA = "passport-drain-a-$tag"
        val modelB = "passport-drain-b-$tag"
        providerIds += providerId

        val summaryA = PerfModel(modelA, 111L, 98.5, 11.25, listOf(98.5))
        val summaryB = PerfModel(modelB, 222L, 77.25, 33.5, listOf(77.25))

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(modelA, modelB))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, modelA, source = "USER", nowMillis = 5_000L))
                assertTrue(store.addToMyModels(providerId, modelB, source = "USER", nowMillis = 5_001L))

                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(modelA to "0", modelB to "2"),
                    summaryModels = listOf(summaryA, summaryB),
                    queryGroups = { emptyList() },
                )
                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))

                val seedLoopback = PerfLoopback(server)
                app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals(4, seedLoopback.total())
                drainRequests(expected = 4)

                // Wave 1：全 passport（gated concurrent，與 V-1 同序）→ 7。
                val (loopback1, delta1) = runFullGatedWave(vault, providerId, accountId, modelA, modelB)
                assertEquals("full-passport wave must anchor 12 to 7", 7, delta1)
                assertEquals(1, loopback1.status.get())
                assertEquals(1, loopback1.pricing.get())
                assertEquals(1, loopback1.account.get())
                assertEquals(1, loopback1.token.get())
                assertEquals(1, loopback1.perfSummary.get())
                assertEquals(2, loopback1.perfQuery.get())
                drainRequests(expected = 7)

                // PROPOSED PORT: wave 2（drain 之後）必須再現 7——registry 已被結構性
                // drain 清除（無 TTL 存活），passport 由 wave 2 的 outer caps 重建；
                // 每波 refetch fresh（anti-caching），never 2/4/1。
                val (loopback2, delta2) = runFullGatedWave(vault, providerId, accountId, modelA, modelB)
                assertEquals(
                    "post-drain wave must refetch fresh: 7 again, never lower",
                    7,
                    delta2,
                )
                assertEquals(1, loopback2.status.get())
                assertEquals(1, loopback2.pricing.get())
                assertEquals(1, loopback2.account.get())
                assertEquals(1, loopback2.token.get())
                assertEquals(1, loopback2.perfSummary.get())
                assertEquals(2, loopback2.perfQuery.get())

                // Persisted-once per fetch（append-only；hit 不寫）：兩波共 2 列。
                val perf = app.repository.providerPerf
                assertEquals(
                    "two waves, two summary fetches, one row each (hits wrote zero)",
                    2,
                    perf.history(providerId, modelA, null, 168, ProviderPerfKind.SUMMARY).size,
                )
                assertTrue(store.isMyModel(providerId, modelA))
                assertTrue(store.isMyModel(providerId, modelB))
            }
        }
    }

    // =================================================================================
    // V-3 — Cancel-skip-remember：mid-flight cancel 在 remember 行之前重拋 → 不寫
    // passport；cancelled wave drain 後 registry 為空 → 下一波 ≡ V-1 同形 7
    // （registry 為空故 A 以 passport=null 領跑、重建 own passport 並 remember；
    // 同 scope B 為 generation follower，跑 A memo 上的 perf → hit → 僅 query 1）。
    // 已 commit 的觀察保留（不刪除）。PROPOSED PORT：依賴 hunk 6 remember 位置。
    // =================================================================================

    @Test
    fun cancelledMidFlightWaveSkipsRememberAndNextWaveRepeatsSeven() {
        val tag = shortId()
        val providerId = "passport-flow-cancel-$tag"
        val accountId = "a-passport-cancel-$tag"
        val patRef = "pat-passport-cancel-$tag"
        val keyRef = "key-passport-cancel-$tag"
        val patSecret = "passport-pat-secret-cancel-$tag-0123456789"
        val keySecret = "passport-key-secret-cancel-$tag-9876543210"
        val endpointId = "e-passport-cancel-$tag"
        val modelA = "passport-cancel-a-$tag"
        val modelB = "passport-cancel-b-$tag"
        providerIds += providerId

        val summaryA = PerfModel(modelA, 654L, 61.0, 8.5, listOf(61.0))
        val summaryB = PerfModel(modelB, 655L, 62.0, 8.6, listOf(62.0))

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(modelA, modelB))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, modelA, source = "USER", nowMillis = 8_000L))
                assertTrue(store.addToMyModels(providerId, modelB, source = "USER", nowMillis = 8_001L))

                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(modelA to "0", modelB to "2"),
                    summaryModels = listOf(summaryA, summaryB),
                    queryGroups = { emptyList() },
                )
                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))

                val seedLoopback = PerfLoopback(server)
                app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals(4, seedLoopback.total())
                drainRequests(expected = 4)

                // Cancelled wave：卡住第一個 perf query（真 flight 中），取消。
                val release = CompletableDeferred<Unit>()
                val loopback = PerfLoopback(server, gateFirstQuery = true, releaseFirstQuery = release)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val perf = app.repository.providerPerf
                val job = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    vm.maybeEnrichMyModel(
                        providerId = providerId,
                        accountId = accountId,
                        credentialId = null,
                        group = null,
                        providerModelId = modelA,
                        lmspeedFetcher = null,
                        newApiAdapterFactory = { NewApiAdapter(vault, client) },
                        newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                    )
                }
                var thrown: Throwable? = null
                try {
                    withTimeout(TIMEOUT_MS) { loopback.queried.await() }
                    assertEquals(1, loopback.perfQuery.get())
                    assertNotNull(
                        "summary leg completed before the query leg: the cancel point is the query leg",
                        perf.observation(providerId, modelA, null, 168, ProviderPerfKind.SUMMARY).lastGood,
                    )
                    job.cancel(CancellationException("passport flow cancel"))
                    thrown = runCatching { withTimeout(TIMEOUT_MS) { job.await() } }.exceptionOrNull()
                } finally {
                    release.complete(Unit)
                }
                assertTrue("cancel inside the wave must be rethrown", thrown is CancellationException)

                // PROPOSED PORT: cancel-skip-remember——cancel 在 remember 行之前
                // 重拋 → 無 passport 寫入；cancelled wave 的 flight drain 後 registry
                // 為空。下一波 ≡ V-1 同形（registry 為空故 A 以 passport=null 領跑、
                // own-passport caps reuse 重建 4＋perf 2〔own-caps reuse、summary
                // miss、query〕= 6 並 remember；B 為 generation follower，在 A 的
                // memo 上 perf → hit → query 1；合計 7）。gated concurrent
                // （sequential 會在兩呼叫間 drain，且無法重現排隊序）。
                val (followUp, followDelta) = runFullGatedWave(vault, providerId, accountId, modelA, modelB)
                assertEquals(
                    "post-cancel wave must run the V-1 7-call shape (nothing was remembered)",
                    7,
                    followDelta,
                )
                assertEquals(1, followUp.status.get())
                assertEquals(1, followUp.pricing.get())
                assertEquals(1, followUp.account.get())
                assertEquals(1, followUp.token.get())
                assertEquals("B memo-hits on A's summary: only A's summary fetched fresh", 1, followUp.perfSummary.get())
                assertEquals(2, followUp.perfQuery.get())

                // 已 commit 的觀察保留（cancelled wave 的 summary 腿已在 cancel 前落庫；
                // 什麼都不刪）：cancelled-A fetch + follow-up A fetch = 2 列
                // （append-only，無 dupe；follow-up B 為 memo hit，零寫）。
                assertEquals(
                    "committed pre-cancel observations must stay (nothing deleted)",
                    2,
                    perf.history(providerId, modelA, null, 168, ProviderPerfKind.SUMMARY).size,
                )
                assertTrue(store.isMyModel(providerId, modelA))
                assertTrue(store.isMyModel(providerId, modelB))
            }
        }
    }

    // =================================================================================
    // V-4 — 排隊中 leader cancel（cleanup B）：B 排隊於 provider lock 時被 cancel →
    // 不污染 A；A 正常完成＋remember；wave drain 乾淨；下一波 7 再現。
    // PROPOSED PORT：依賴 hunk 6 queued-cancel cleanup 的 drain-clear。
    // =================================================================================

    @Test
    fun queuedLeaderCancelWhileLockedKeepsLeaderResultAndDrainsRegistryClean() {
        val tag = shortId()
        val providerId = "passport-flow-qcancel-$tag"
        val accountId = "a-passport-qcancel-$tag"
        val patRef = "pat-passport-qcancel-$tag"
        val keyRef = "key-passport-qcancel-$tag"
        val patSecret = "passport-pat-secret-qcancel-$tag-0123456789"
        val keySecret = "passport-key-secret-qcancel-$tag-9876543210"
        val endpointId = "e-passport-qcancel-$tag"
        val modelA = "passport-qcancel-a-$tag"
        val modelB = "passport-qcancel-b-$tag"
        providerIds += providerId

        val summaryA = PerfModel(modelA, 321L, 88.5, 15.5, listOf(88.5))
        val summaryB = PerfModel(modelB, 322L, 89.5, 16.5, listOf(89.5))

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(modelA, modelB))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, modelA, source = "USER", nowMillis = 9_000L))
                assertTrue(store.addToMyModels(providerId, modelB, source = "USER", nowMillis = 9_001L))

                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(modelA to "0", modelB to "2"),
                    summaryModels = listOf(summaryA, summaryB),
                    queryGroups = { emptyList() },
                )
                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))

                val seedLoopback = PerfLoopback(server)
                app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals(4, seedLoopback.total())
                drainRequests(expected = 4)

                val releasePricing = CompletableDeferred<Unit>()
                val loopback = PerfLoopback(server, gateFirstPricing = true, releaseFirstPricing = releasePricing)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            credentialId = null,
                            group = null,
                            providerModelId = modelA,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = { NewApiAdapter(vault, client) },
                            newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                        )
                    }
                }
                var jobB: kotlinx.coroutines.Deferred<Boolean>? = null
                var bThrown: Throwable? = null
                try {
                    withTimeout(TIMEOUT_MS) { loopback.firstPricing.await() }
                    val second = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_MS) {
                            vm.maybeEnrichMyModel(
                                providerId = providerId,
                                accountId = accountId,
                                credentialId = null,
                                group = null,
                                providerModelId = modelB,
                                lmspeedFetcher = null,
                                newApiAdapterFactory = { NewApiAdapter(vault, client) },
                                newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                            )
                        }
                    }
                    jobB = second
                    assertFalse("B must queue behind the provider lock", second.isCompleted)
                    // B 排隊中被 cancel（cleanup B 路徑）。
                    second.cancel(CancellationException("queued leader cancel"))
                    bThrown = runCatching { withTimeout(TIMEOUT_MS) { second.await() } }.exceptionOrNull()
                } finally {
                    releasePricing.complete(Unit)
                }
                assertTrue("B's queued cancel must be rethrown", bThrown is CancellationException)
                assertTrue("A must complete normally despite B's queued cancel", withTimeout(TIMEOUT_MS) { jobA.await() })

                // PROPOSED PORT: A 的結果已 commit、passport 已 remember（B 的 cancel
                // 不得污染）；wave drain 乾淨 → 單 model 後續 wave（B 的 generation
                // 未遞增→ legAlreadyRan=false → 全腿重跑；passport 已 drain → 重建 own）
                // = 6（outer status1＋pricing1＋account1＋token1＋summary1＋query1）。
                val followUp = PerfLoopback(server)
                val followClient = OkHttpClient.Builder().addInterceptor(followUp).build()
                val followBaseline = server.requestCount
                assertTrue(
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            credentialId = null,
                            group = null,
                            providerModelId = modelB,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = { NewApiAdapter(vault, followClient) },
                            newApiPerfAdapterFactory = { NewApiAdapter(vault, followClient) },
                        )
                    },
                )
                assertEquals(
                    "wave must drain clean: the single-model follow-up wave re-anchors to 6 (fresh own passport)",
                    6,
                    server.requestCount - followBaseline,
                )
                assertEquals(1, followUp.status.get())
                assertEquals(1, followUp.pricing.get())
                assertEquals(1, followUp.account.get())
                assertEquals(1, followUp.token.get())
                assertEquals(1, followUp.perfSummary.get())
                assertEquals(1, followUp.perfQuery.get())
                // B 的排隊 cancel 燒掉零個 pricing fetch（總計 = A wave1 1 次 + follow-up 1 次）。
                assertEquals(1, loopback.pricing.get())
                assertEquals(1, followUp.pricing.get())
                assertTrue(store.isMyModel(providerId, modelA))
                assertTrue(store.isMyModel(providerId, modelB))
            }
        }
    }

    // =================================================================================
    // V-5 — create gate null（uncertified caps）：`create` 對 uncertified 回 null →
    // 無 passport、無 failure memo；legs 保持 legacy fresh-caps；3-status per session
    // 保留；下一波形狀不變（nothing remembered）。PROPOSED PORT：依賴 hunk 1 gate。
    // （outer-caps Failure 的 12-unchanged 形狀屬 P6-open 推導向量，見檔尾。）
    // =================================================================================

    @Test
    fun uncertifiedCapsCreateNoPassportAndFailureWavesKeepLegacySessionShape() {
        val tag = shortId()
        val providerId = "passport-flow-uncert-$tag"
        val accountId = "a-passport-uncert-$tag"
        val patRef = "pat-passport-uncert-$tag"
        val keyRef = "key-passport-uncert-$tag"
        val patSecret = "passport-pat-secret-uncert-$tag-0123456789"
        val keySecret = "passport-key-secret-uncert-$tag-9876543210"
        val endpointId = "e-passport-uncert-$tag"
        val modelA = "passport-uncert-a-$tag"
        val modelB = "passport-uncert-b-$tag"
        providerIds += providerId

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(modelA, modelB))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, modelA, source = "USER", nowMillis = 10_000L))
                assertTrue(store.addToMyModels(providerId, modelB, source = "USER", nowMillis = 10_001L))

                // 與 V-1 唯一差異：站方回非認證版本 → create gate 回 null。
                server.dispatcher = PerfDispatcher(
                    statusVersion = UNKNOWN_VERSION,
                    pricingModels = mapOf(modelA to "0", modelB to "2"),
                    summaryModels = emptyList(),
                    queryGroups = { emptyList() },
                )
                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))

                val seedLoopback = PerfLoopback(server)
                app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals(3, seedLoopback.total())
                drainRequests(expected = 3)
                assertEquals(
                    NewApiVersionSupport.UNKNOWN,
                    app.repository.providerPerf.certifiedProviderVersion(providerId).support,
                )

                // Wave：單呼叫（modelA）= syncNewApi 3-status（create→null；legs 全 fresh
                // caps，但三條 data 腿皆被 versionGate 以零 HTTP 短路）＋ syncPerf 0
                // （stored gate 0 HTTP）= 3。generation 不遞增（uncertified pricing
                // 不 sync）→ 後續呼叫也是全 fresh。status==3 即 fresh-fetch 證明
                // （若誤建 passport，只會見 status 1）。
                val loopback = PerfLoopback(server)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val baseline = server.requestCount
                val result = withTimeout(TIMEOUT_MS) {
                    vm.maybeEnrichMyModel(
                        providerId = providerId,
                        accountId = accountId,
                        credentialId = null,
                        group = null,
                        providerModelId = modelA,
                        lmspeedFetcher = null,
                        newApiAdapterFactory = { NewApiAdapter(vault, client) },
                        newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                    )
                }
                assertTrue(result)
                assertEquals(
                    "uncertified wave must keep the 3-status shape (versionGate short-circuits all data legs; no passport)",
                    3,
                    server.requestCount - baseline,
                )
                assertEquals(3, loopback.status.get())
                assertEquals(0, loopback.pricing.get())
                assertEquals(0, loopback.account.get())
                assertEquals(0, loopback.token.get())
                assertEquals(0, loopback.perfSummary.get())
                assertEquals(0, loopback.perfQuery.get())

                val perf = app.repository.providerPerf
                val expectedScopes = listOf(
                    providerPerfScopeKey(providerId, modelA, null, 168),
                    providerPerfScopeKey(providerId, modelA, "default", 168),
                    providerPerfScopeKey(providerId, modelB, null, 168),
                    providerPerfScopeKey(providerId, modelB, "default", 168),
                    providerPerfScopeKey(providerId, null, null, 168),
                )
                for (kind in ProviderPerfKind.values()) {
                    val heads = app.database.providerPerfDao().headsByKind(kind.name)
                    assertTrue(
                        "no perf head may exist for an uncertified provider ($kind)",
                        heads.none { it.scopeKey in expectedScopes },
                    )
                }
                // PROPOSED PORT: 無 passport → 無 failure memo → 下一波形狀不變
                //（nothing remembered；anti-TTL；6 再現）。
                val followBaseline = server.requestCount
                val followUp = PerfLoopback(server)
                val followClient = OkHttpClient.Builder().addInterceptor(followUp).build()
                assertTrue(
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            credentialId = null,
                            group = null,
                            providerModelId = modelA,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = { NewApiAdapter(vault, followClient) },
                            newApiPerfAdapterFactory = { NewApiAdapter(vault, followClient) },
                        )
                    },
                )
                assertEquals(
                    "no failure memo: the follow-up uncertified wave repeats the 3-status shape",
                    3,
                    server.requestCount - followBaseline,
                )
                assertEquals(3, followUp.status.get())
                assertEquals(0, followUp.perfSummary.get())

                // Membership 保留；pricing 維持 Unknown 語意（不補 0／不補 Free）。
                assertTrue(store.isMyModel(providerId, modelA))
                assertTrue(store.isMyModel(providerId, modelB))
                assertEquals(
                    "no official price may be persisted for an uncertified provider",
                    0,
                    countRows("price_snapshots", providerId),
                )
                assertEquals(0, countRows("samples", providerId))
            }
        }
    }

    // =================================================================================
    // V-6 — 不同 account 的 leader（gated concurrent，兩 account 同時在飛）：
    // a1 領先並 remember passport；a2 排隊於同一 provider lock 後讀到 a1 的 passport
    // → 全腿 exact-miss（accountId/patRef）→ 全 fresh（永不跨 account reuse）；且
    // per-(provider, account, credential) generation 分離（HEAD :2825/:2871）→ a2 跑
    // 自己的 New-API 腿（D-4：不同 account 不被吞掉）。Wave = 15。
    // PROPOSED PORT：依賴 hunk 2/4/5 的 exact-match 消費；N-2 honest note（
    // per-(provider,account) registry key 仍在 draft 之外）。a2 的 perf provenance
    // 解析（own PAT）屬 implementation-slice 錨點——census 以 status=5／summary=2
    // 鎖「全 fresh／無跨 account reuse」語意，實作 slice 重錨。
    // =================================================================================

    @Test
    fun differentAccountLeaderMissesPassportAndRefetchesAllLegsFresh() {
        val tag = shortId()
        val providerId = "passport-flow-acct-$tag"
        val accountOne = "a1-passport-acct-$tag"
        val accountTwo = "a2-passport-acct-$tag"
        val patOne = "pat1-passport-acct-$tag"
        val patTwo = "pat2-passport-acct-$tag"
        val keyRef = "key-passport-acct-$tag"
        val keyTwoB = "key2-passport-acct-$tag"
        val patOneSecret = "passport-pat1-secret-acct-$tag-0123456789"
        val patTwoSecret = "passport-pat2-secret-acct-$tag-9876543210"
        val keySecret = "passport-key-secret-acct-$tag-1123456789"
        val keyTwoBSecret = "passport-key2-secret-acct-$tag-2234567890"
        val endpointId = "e-passport-acct-$tag"
        val model = "passport-acct-model-$tag"
        providerIds += providerId

        val summary = PerfModel(model, 111L, 98.5, 11.25, listOf(98.5))

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountOne, patOne, keyRef, endpointId, listOf(model))
                // 第二 account：own PAT credential ＋ own inference key（keyTwoB），
                // 並把 patTwo 註冊為 config ACCOUNT、keyTwoB 註冊為 INFERENCE。
                // 缺任一項，B 的腿都在 pre-network 拋錯（PAT 選不到／resolvedKey
                // 為 null 跳過 token 腿），wave 永遠到不了 15 census。
                val db = app.database
                db.accountDao().insert(AccountEntity(accountTwo, providerId))
                db.credentialDao().insert(CredentialEntity(patTwo, accountTwo))
                db.credentialDao().insert(CredentialEntity(keyTwoB, accountTwo))
                val seededConfig = requireNotNull(db.providerConfigDao().getByProvider(providerId)) {
                    "seedProvider must have written the provider config"
                }.toManualConfig()
                db.providerConfigDao().updateSnapshotJson(
                    providerId,
                    seededConfig.copy(
                        credentials = seededConfig.credentials + listOf(
                            SafeCredentialRef(patTwo, "PAT", CredentialRole.ACCOUNT),
                            SafeCredentialRef(keyTwoB, "KEY", CredentialRole.INFERENCE),
                        ),
                    ).encodeToSnapshotJson(),
                )

                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, model, source = "USER", nowMillis = 11_000L))

                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(model to "0"),
                    summaryModels = listOf(summary),
                    queryGroups = { emptyList() },
                )
                val vault = CountingVault(mapOf(
                    patOne to patOneSecret,
                    patTwo to patTwoSecret,
                    keyRef to keySecret,
                    keyTwoB to keyTwoBSecret,
                ))

                val seedLoopback = PerfLoopback(server)
                app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                    accountId = accountOne,
                )
                assertEquals(4, seedLoopback.total())
                drainRequests(expected = 4)

                // Wave：A（a1）領先（pricing 真阻塞）；B（a2）排隊於同一 provider lock
                // （BundleEnrichmentKey 的 accountId 維度不同 → B 非 follower，是排隊的
                // 另一 leader；generation 在 A 遞增前捕捉）。
                val releasePricing = CompletableDeferred<Unit>()
                val loopback = PerfLoopback(server, gateFirstPricing = true, releaseFirstPricing = releasePricing)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val baseline = server.requestCount
                val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountOne,
                            credentialId = null,
                            group = null,
                            providerModelId = model,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = { NewApiAdapter(vault, client) },
                            newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                        )
                    }
                }
                val jobB = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountTwo,
                            credentialId = null,
                            group = null,
                            providerModelId = model,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = { NewApiAdapter(vault, client) },
                            newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                        )
                    }
                }
                try {
                    withTimeout(TIMEOUT_MS) { loopback.firstPricing.await() }
                    assertFalse("B (different account) must queue behind the provider lock", jobB.isCompleted)
                } finally {
                    releasePricing.complete(Unit)
                }
                assertTrue(withTimeout(TIMEOUT_MS) { jobA.await() })
                assertTrue(withTimeout(TIMEOUT_MS) { jobB.await() })

                // PROPOSED PORT: 不同 account 的 leader——a1 的 passport 對 a2 全腿
                // exact-miss（accountId/patRef）→ 全 fresh（永不跨 account reuse）；
                // 且 per-(provider, account, credential) generation 分離（HEAD :2825/:2871）
                // → B 跑自己的 New-API 腿（D-4，不同 account 不被吞掉）。
                // Wave = A 6 + B 9 = 15：
                //   A: outer status1＋pricing1＋account1＋token1＋summary1＋query1
                //     （own passport caps reuse——matchesLeg 全 match）
                //   B: outer status1＋pricing1＋account-leg status1＋account1
                //     ＋token-leg status1＋token1＋perf caps status1＋summary1＋query1
                //     （全 fresh——accountId/patRef miss；B 的 summary 也 fresh：無跨
                //     account memo hit）
                assertEquals(15, server.requestCount - baseline)
                assertEquals(
                    "a1's passport must not serve a2: all five status GETs are fresh caps fetches",
                    5,
                    loopback.status.get(),
                )
                assertEquals("per-scope generation: B re-runs its own pricing leg", 2, loopback.pricing.get())
                assertEquals(2, loopback.account.get())
                assertEquals(2, loopback.token.get())
                assertEquals("no cross-account memo hit", 2, loopback.perfSummary.get())
                assertEquals(2, loopback.perfQuery.get())
                drainRequests(expected = 15)

                // PROPOSED PORT: drain 乾淨（B 完成後 flights 空 → drain-clear）→
                // a2 的後續單呼叫 = 6（fresh own passport；若 a1 的 stale passport
                // 殘存，a2 的全腿 miss 會是 9）。
                val followUp = PerfLoopback(server)
                val followClient = OkHttpClient.Builder().addInterceptor(followUp).build()
                val followBaseline = server.requestCount
                assertTrue(
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountTwo,
                            credentialId = null,
                            group = null,
                            providerModelId = model,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = { NewApiAdapter(vault, followClient) },
                            newApiPerfAdapterFactory = { NewApiAdapter(vault, followClient) },
                        )
                    },
                )
                assertEquals(
                    "post-drain a2 call must refetch with a fresh own passport (6, not 9)",
                    6,
                    server.requestCount - followBaseline,
                )
            }
        }
    }

    // =================================================================================
    // PROPOSED PORT — Unit 層：對提案 [NewApiSessionPassport] 直接打 exact-match
    // contract（hunk 1，byte-identical 草稿）。R-2 的 window-miss 向量只在這層可表達。
    // =================================================================================

    // PROPOSED PORT: 依賴 hunk 1 的 NewApiSessionPassport / NewApiSessionAuthRole /
    // WaveSummary / NewApiEvidence / NewApiResult / NewApiCapabilities。
    private fun certifiedCaps(): NewApiResult<NewApiCapabilities> = NewApiResult.Success(
        evidence = NewApiEvidence(
            source = "newapi/api/status",
            observedAt = 1_000L,
            providerVersion = NewApiVersion.RC25,
            versionSupport = NewApiVersionSupport.CERTIFIED_RC25,
            httpStatus = 200,
        ),
        value = NewApiCapabilities(
            version = NewApiVersion(NewApiVersion.RC25, NewApiVersionSupport.CERTIFIED_RC25),
            quotaPerUnit = null,
            quotaDisplayType = null,
            checkinEnabled = null,
            pricingModule = NewApiModuleAccess(null, null),
            certifiedEndpoints = emptyMap(),
        ),
    )

    @Test
    fun passportCreateIssuesPassportOnlyForCertifiedStatusSourcedCaps() {
        // PROPOSED PORT: create gate 的正例＋每個拒絕維度。
        val pat = AccountPatRef("pat-unit-create-001")
        val passport = NewApiSessionPassport.create(
            providerId = "p-unit",
            baseUrl = "https://p-unit.example.com/v1",
            origin = "https://p-unit.example.com",
            accountId = "a-unit",
            pat = pat,
            caps = certifiedCaps(),
        )
        assertNotNull(passport)
        assertEquals(NewApiSessionAuthRole.ACCOUNT_PAT, passport!!.authRole)
        assertEquals(NewApiVersion.RC25, passport.versionRaw)
        assertEquals(168, passport.hours)
        val anon = NewApiSessionPassport.create(
            providerId = "p-unit",
            baseUrl = "https://p-unit.example.com/v1",
            origin = "https://p-unit.example.com",
            accountId = null,
            pat = null,
            caps = certifiedCaps(),
        )
        assertNotNull("pat-less certified caps must still create a passport", anon)
        assertEquals(
            "pat-less caps must produce an ANON passport (never an ACCOUNT_PAT hit path; N-1)",
            NewApiSessionAuthRole.ANON,
            anon!!.authRole,
        )
        // Failure caps → null（failure waves 無 passport）。
        val failure = NewApiResult.Failure(
            status = NewApiStatus.HTTP_SERVER_ERROR,
            evidence = NewApiEvidence(source = "newapi/api/status", observedAt = 1_000L),
        )
        assertNull(NewApiSessionPassport.create("p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com", "a-unit", pat, failure))
        // 錯誤 source → null（只認 status 來源）。
        val wrongSource = (certifiedCaps() as NewApiResult.Success).copy(
            evidence = (certifiedCaps() as NewApiResult.Success).evidence.copy(source = "newapi/api/pricing"),
        )
        assertNull(NewApiSessionPassport.create("p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com", "a-unit", pat, wrongSource))
        // 缺 httpStatus → null。
        val noHttp = (certifiedCaps() as NewApiResult.Success).copy(
            evidence = (certifiedCaps() as NewApiResult.Success).evidence.copy(httpStatus = null),
        )
        assertNull(NewApiSessionPassport.create("p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com", "a-unit", pat, noHttp))
        // Uncertified 版本 → null（support 檢查先於 raw）。
        val uncertified = NewApiResult.Success(
            evidence = NewApiEvidence(
                source = "newapi/api/status",
                observedAt = 1_000L,
                providerVersion = "v0.4.0-probe",
                versionSupport = NewApiVersionSupport.UNKNOWN,
                httpStatus = 200,
            ),
            value = NewApiCapabilities(
                version = NewApiVersion.of("v0.4.0-probe"),
                quotaPerUnit = null,
                quotaDisplayType = null,
                checkinEnabled = null,
                pricingModule = NewApiModuleAccess(null, null),
                certifiedEndpoints = emptyMap(),
            ),
        )
        assertNull(NewApiSessionPassport.create("p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com", "a-unit", pat, uncertified))
    }

    @Test
    fun passportMatchesLegEnforcesEveryIdentityDimension() {
        // PROPOSED PORT: matchesLeg 每一維 exact-match；named-vs-null account 不互通。
        val base = NewApiSessionPassport.create(
            providerId = "p-unit",
            baseUrl = "https://p-unit.example.com/v1",
            origin = "https://p-unit.example.com",
            accountId = "a-unit",
            pat = AccountPatRef("pat-unit-leg"),
            caps = certifiedCaps(),
        )!!
        val hours = NewApiPerfWindow.normalize(168)
        fun matches(
            providerId: String = "p-unit",
            baseUrl: String = "https://p-unit.example.com/v1",
            origin: String = "https://p-unit.example.com",
            authRole: NewApiSessionAuthRole = NewApiSessionAuthRole.ACCOUNT_PAT,
            pat: AccountPatRef? = AccountPatRef("pat-unit-leg"),
            accountId: String? = "a-unit",
            legHours: Int = hours,
        ): Boolean = base.matchesLeg(providerId, baseUrl, origin, authRole, pat, accountId, legHours)
        assertTrue(matches())
        assertFalse("provider mismatch", matches(providerId = "other"))
        assertFalse("base mismatch", matches(baseUrl = "https://other.example.com/v1"))
        assertFalse("origin mismatch", matches(origin = "https://other.example.com"))
        assertFalse("authRole mismatch", matches(authRole = NewApiSessionAuthRole.ANON))
        assertFalse("safe PAT ref mismatch", matches(pat = AccountPatRef("pat-unit-other")))
        assertFalse("null pat vs named pat must not match", matches(pat = null))
        assertFalse("named account vs null must not match", matches(accountId = null))
        assertFalse("different account must not match", matches(accountId = "a-other"))
    }

    @Test
    fun passportMatchesSummaryRequiresLegMatchCertifiedRawAndSameWindow() {
        // PROPOSED PORT: matchesSummary = leg match + summary != null + certified raw。
        // R-2 的 window-miss 向量唯一合法位置（production flow 兩側恆 168，不可達）。
        val evidence = (certifiedCaps() as NewApiResult.Success).evidence
        val base = NewApiSessionPassport.create(
            providerId = "p-unit",
            baseUrl = "https://p-unit.example.com/v1",
            origin = "https://p-unit.example.com",
            accountId = "a-unit",
            pat = AccountPatRef("pat-unit-sum"),
            caps = certifiedCaps(),
        )!!
        assertFalse("no memo yet: matchesSummary must miss", base.matchesSummary(
            "p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com",
            NewApiSessionAuthRole.ACCOUNT_PAT, AccountPatRef("pat-unit-sum"), "a-unit", 168, NewApiVersion.RC25,
        ))
        val withMemo = base.copy(summary = WaveSummary(
            evidence = evidence,
            models = listOf(
                NewApiPerfModelSummary(
                    modelName = "m-unit",
                    avgLatencyMs = 100L,
                    successRate = 90.0,
                    avgTps = 10.0,
                    recentSuccessRates = listOf(90.0),
                    requestCount = null,
                ),
            ),
        ))
        assertTrue(withMemo.matchesSummary(
            "p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com",
            NewApiSessionAuthRole.ACCOUNT_PAT, AccountPatRef("pat-unit-sum"), "a-unit", 168, NewApiVersion.RC25,
        ))
        assertFalse("certified-raw mismatch must miss",
            withMemo.matchesSummary(
                "p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com",
                NewApiSessionAuthRole.ACCOUNT_PAT, AccountPatRef("pat-unit-sum"), "a-unit", 168, "v1.0.0-rc.24",
            ))
        assertFalse("normalized-window mismatch must miss (R-2: unit-level only)",
            withMemo.matchesSummary(
                "p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com",
                NewApiSessionAuthRole.ACCOUNT_PAT, AccountPatRef("pat-unit-sum"), "a-unit", 24, NewApiVersion.RC25,
            ))
        assertFalse("leg identity mismatch must miss even with a memo",
            withMemo.matchesSummary(
                "p-unit", "https://p-unit.example.com/v1", "https://p-unit.example.com",
                NewApiSessionAuthRole.ACCOUNT_PAT, AccountPatRef("pat-unit-other"), "a-unit", 168, NewApiVersion.RC25,
            ))
        assertEquals("member present: memo carries the catalog row count", 1, withMemo.summary!!.memberGoodCount("m-unit"))
        assertEquals("member absent: zero claimed", 0, withMemo.summary!!.memberGoodCount("m-absent"))
    }

    // =================================================================================
    // PROPOSED ARITHMETIC VECTORS（不可執行——不可從全 port production flow 到達；
    // P4-dependent，實作 slice 落地時重錨。「8」= G1-only ONLY。）
    //
    // Baseline（HEAD 1c038b1 — carried, androidTest zero-diff 0191c7d..1c038b1;
    // ProviderPerfBundleFlowTest.kt:284，byte-verified）：
    //   2-model wave 12 = caps 5 / summary 2 / query 2 / pricing 1 / account 1 / token 1
    //   [leader A = syncNewApi 6 + syncPerf 3 = 9; leader B = syncPerf 3 = 3]
    //
    //   full passport (G1+G2) = 7   ← V-1 的可執行 oracle
    //     A: status1+pricing1+account1+token1+summary1+query1 = 6
    //        （account/token/perf 腿 matchesLeg 各跳 1 status GET；outer caps 保留為
    //        passport-creation 觀察，永不消除）
    //     B: query1 = 1（caps 跳過＋memo HIT 0 HTTP＋per-model query 必跑）
    //
    //   G2-only（H5 memo + H6 registry；H4 legs reverted）= 11
    //     A: syncNewApi 6（caps 全 fresh）+ syncPerf 3 = 9
    //     B: syncPerf 2（caps 1 fresh + memo hit 0 + query 1）= 2
    //
    //   G1-only（H4 caps reuse + H6 registry；H5 memo off）= 8
    //     A: syncNewApi 4 + syncPerf 2 = 6
    //     B: syncPerf 2（caps reuse 0 + summary own-fetch 1〔不共享〕+ query 1）= 2
    //
    //   failure waves（outer caps Failure → create null → 無 passport → 全腿 fresh）
    //     = 12 unchanged（P6 open：無 failure memo；P-empty lock 同步開放中）
    //
    // 提醒：被複用的舊報告曾把 full-passport 波寫成「8」——為 slip；「8」= G1-only
    // （ai9-cr009-summary-vectors / ai9-pool-next-cr009-wave-arith / preland 三方確認）。
    // 另：ai9-cr009-summary-vectors 的「subsequent wave 4」片段無法從現行 call graph
    // 推得——不得在未重推前使用。
    // =================================================================================

    // =================================================================================
    // PROPOSED STRICT-PAT（NOT in this draft；R-1 carry；Main 未決——僅 PROPOSED）
    //
    // 提案的 token 腿傳 `pat = passport.patRef` 給 matchesLeg（自我比較）→ PAT 維度
    // 在 token 腿由構造即 vacuous（hunk 4 / ai9-cr009-preland-c4.frag :63）。跨 account
    // reuse 仍被 accountId + base/origin/version 閘住（安全方向）。
    //
    // PROPOSED 選項：給 `syncTokenQuota` 增加 PAT 參數，使 token 腿比較「腿自己的 PAT」。
    // 若該選項落地：
    //   1. V-6 之外可新增負向向量「token 腿遇不同 PAT ref 則 miss」（今日不可表達，
    //      R-1 禁止對本 draft 撰寫）。
    //   2. wave 計數不變（strict-PAT 只影響 match 條件，不改變 reuse 與否的 Happy path）。
    // 在 Main 決策前：本檔不斷言 token 腿的 PAT 維度行為，僅保留 R-1 警示。
    // =================================================================================

    // =================================================================================
    // F7 / P-empty（open product lock — oracle UNFROZEN）
    //
    // 提案 draft 會 remember certified+surface-valid 的 summary **含空 `models: []`**
    // （HEAD :649-657 summarySurfaceValid 對空回 true；:296-300 persistSummaryRows 寫
    // 1 列 provider-level empty marker）。若 Main 決「never remember empty」，N-member
    // empty-wave 的 memo-hit oracle 從「1 memo-hit」變「N failure markers」。本檔
    // **不**凍結該計數：空波向量待 P-empty 決策後另行凍結（不允許以測試先射後畫靶）。
    // =================================================================================

    // ===== harness（fixture 僅本檔；形狀照抄 ProviderPerfBundleFlowTest.kt @ 1c038b1） =====

    /**
     * 全 passport gated wave helper（與 V-1 同一 deterministic 排序）：A 的首個 pricing
     * 請求真阻塞於 interceptor → B 排隊於 provider lock（generation 在 A 遞增前捕捉）
     * → release → 兩者完成。回傳該波的 loopback（各端點 tally）與 wave-only delta。
     * 兩個 model 必須同時在飛（sequential 呼叫會在兩者之間觸發 drain-clear，永遠
     * 拿不到 7-count——這正是 V-2/V-3 必須用 gated concurrent wave 的原因）。
     */
    private suspend fun runFullGatedWave(
        vault: CountingVault,
        providerId: String,
        accountId: String,
        modelA: String,
        modelB: String,
    ): Pair<PerfLoopback, Int> {
        val releasePricing = CompletableDeferred<Unit>()
        val loopback = PerfLoopback(server, gateFirstPricing = true, releaseFirstPricing = releasePricing)
        val client = OkHttpClient.Builder().addInterceptor(loopback).build()
        val baseline = server.requestCount
        // async needs a CoroutineScope receiver; this helper is a plain suspend fun.
        // The gated A/B concurrency (and the jobB-not-completed assertion below)
        // requires both waves in flight simultaneously, hence a scope hosting both.
        coroutineScope {
            val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    vm.maybeEnrichMyModel(
                        providerId = providerId,
                        accountId = accountId,
                        credentialId = null,
                        group = null,
                        providerModelId = modelA,
                        lmspeedFetcher = null,
                        newApiAdapterFactory = { NewApiAdapter(vault, client) },
                        newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                    )
                }
            }
            val jobB = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    vm.maybeEnrichMyModel(
                        providerId = providerId,
                        accountId = accountId,
                        credentialId = null,
                        group = null,
                        providerModelId = modelB,
                        lmspeedFetcher = null,
                        newApiAdapterFactory = { NewApiAdapter(vault, client) },
                        newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                    )
                }
            }
            try {
                withTimeout(TIMEOUT_MS) { loopback.firstPricing.await() }
                assertFalse("second model must queue while the first pricing leg is gated", jobB.isCompleted)
            } finally {
                releasePricing.complete(Unit)
            }
            assertTrue("first model bundle must complete", withTimeout(TIMEOUT_MS) { jobA.await() })
            assertTrue("queued model bundle must complete", withTimeout(TIMEOUT_MS) { jobB.await() })
        }
        return loopback to (server.requestCount - baseline)
    }

    private suspend fun seedProvider(
        providerId: String,
        accountId: String,
        patRef: String,
        keyRef: String,
        endpointId: String,
        models: List<String>,
    ) {
        val db = app.database
        val baseHost = "https://$providerId.example.com"
        db.providerDao().insert(ProviderEntity(providerId = providerId, name = "P $providerId"))
        db.accountDao().insert(AccountEntity(accountId, providerId))
        db.credentialDao().insert(CredentialEntity(patRef, accountId))
        db.credentialDao().insert(CredentialEntity(keyRef, accountId))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = endpointId,
                providerId = providerId,
                baseUrl = "$baseHost/v1",
                endpointUrl = "$baseHost/v1/chat/completions",
                protocol = "CHAT_COMPLETIONS",
            ),
        )
        for (m in models) {
            db.offeringDao().insert(
                OfferingEntity(
                    id = "off:$providerId:$m",
                    providerId = providerId,
                    endpointId = endpointId,
                    providerModelId = m,
                    manual = false,
                    source = "OFFICIAL",
                    hidden = false,
                ),
            )
        }
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = providerId,
                workflowId = "wf-$providerId-passportflow",
                authMode = AuthMode.BEARER,
                credentials = listOf(
                    SafeCredentialRef(patRef, "PAT", CredentialRole.ACCOUNT),
                    SafeCredentialRef(keyRef, "KEY", CredentialRole.INFERENCE),
                ),
            ).toEntity(),
        )
    }

    /** Provider 維度精確計數（只對確有 providerId 欄的表；不做全域假設）。 */
    private fun countRows(table: String, providerId: String): Int {
        app.database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE providerId = ?", arrayOf<Any?>(providerId))
            .use { c ->
                assertTrue(c.moveToFirst())
                return c.getInt(0)
            }
    }

    /**
     * 來源分離守衛（Contract §2／D-009）：站方 perf canary 值不得出現在 local samples／
     * external／price／quota／snapshot 事實槽（HEAD :951-985 同形）。
     */
    private suspend fun assertPerfMetricsAbsentFromOtherSlots(providerId: String, canaryValues: List<String>) {
        for (table in listOf("samples", "price_snapshots", "quota_snapshots", "provider_events")) {
            val rows = rowsAsText(table, "providerId = ?", arrayOf<Any?>(providerId))
            for (canary in canaryValues) {
                assertFalse("provider perf metric leaked into $table", rows.any { it.contains(canary) })
            }
        }
        val canonicalIds = ArrayList<String>()
        app.database.openHelper.readableDatabase.query(
            "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId = ?",
            arrayOf<Any?>(providerId),
        ).use { c -> while (c.moveToNext()) canonicalIds += c.getString(0) }
        assertTrue("source-separation guard must not be vacuous", canonicalIds.isNotEmpty())
        for (table in listOf("external_observations", "external_mappings")) {
            for (id in canonicalIds) {
                val rows = rowsAsText(table, "canonicalModelId = ?", arrayOf<Any?>(id))
                for (canary in canaryValues) {
                    assertFalse("provider perf metric leaked into $table", rows.any { it.contains(canary) })
                }
            }
        }
        val factRows = mutableListOf<String>()
        for (kind in SnapshotKind.values()) {
            for (head in app.database.snapshotFactDao().headsByKind(kind.name)) {
                if (parseScopeKey(head.scopeKey)?.getOrNull(0) != providerId) continue
                app.database.snapshotFactDao().evidence(head.latestEvidenceId)?.let { factRows += it.toString() }
                head.lastGoodSnapshotId?.let { id ->
                    app.database.snapshotFactDao().evidence(id)?.let { factRows += it.toString() }
                }
            }
        }
        for (canary in canaryValues) {
            assertFalse("provider perf metric leaked into snapshot_facts", factRows.any { it.contains(canary) })
        }
    }

    private fun rowsAsText(table: String, where: String, args: Array<Any?>): List<String> {
        val out = ArrayList<String>()
        app.database.openHelper.readableDatabase.query("SELECT * FROM $table WHERE $where", args).use { c ->
            while (c.moveToNext()) {
                val sb = StringBuilder()
                for (i in 0 until c.columnCount) {
                    sb.append(c.getString(i)).append(' ')
                }
                out += sb.toString()
            }
        }
        return out
    }

    private fun drainRequests(expected: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(expected)
        repeat(expected) {
            out += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request #$it (expected $expected)" }
        }
        return out
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    private fun RecordedRequest.encodedPath(): String = requestUrl!!.encodedPath

    private class CountingVault(secrets: Map<String, String>) : CredentialAccess {
        private val backing = secrets.toMap()
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            return backing[credentialId] ?: throw IllegalStateException("vault-refused")
        }
    }

    /**
     * 測試傳輸接縫（HEAD :1046-1100 同形）：只換 scheme/host/port 到 loopback 真
     * socket；tally 各端點真實請求數；可選 deterministic 閘門真阻塞於 interceptor 內。
     * 產品路徑永不開啟。
     */
    private class PerfLoopback(
        private val server: MockWebServer,
        private val gateFirstPricing: Boolean = false,
        private val releaseFirstPricing: CompletableDeferred<Unit>? = null,
        private val gateFirstQuery: Boolean = false,
        private val releaseFirstQuery: CompletableDeferred<Unit>? = null,
    ) : Interceptor {
        val status = AtomicInteger(0)
        val pricing = AtomicInteger(0)
        val account = AtomicInteger(0)
        val token = AtomicInteger(0)
        val perfSummary = AtomicInteger(0)
        val perfQuery = AtomicInteger(0)
        val queryModels: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val firstPricing = CompletableDeferred<Unit>()
        val queried = CompletableDeferred<Unit>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val url = orig.url
            when (url.encodedPath) {
                "/api/status" -> status.incrementAndGet()
                "/api/user/self" -> account.incrementAndGet()
                "/api/usage/token/" -> token.incrementAndGet()
                "/api/pricing" -> {
                    val n = pricing.incrementAndGet()
                    if (n == 1) {
                        firstPricing.complete(Unit)
                        if (gateFirstPricing) releaseFirstPricing?.let { runBlocking { it.await() } }
                    }
                }
                "/api/perf-metrics/summary" -> perfSummary.incrementAndGet()
                "/api/perf-metrics" -> {
                    val n = perfQuery.incrementAndGet()
                    queryModels += url.queryParameter("model").orEmpty()
                    queried.complete(Unit)
                    if (n == 1 && gateFirstQuery) releaseFirstQuery?.let { runBlocking { it.await() } }
                }
            }
            val loopback = server.url("/")
            val rewritten = orig.newBuilder()
                .url(
                    url.newBuilder()
                        .scheme(loopback.scheme)
                        .host(loopback.host)
                        .port(loopback.port)
                        .build(),
                )
                .build()
            return chain.proceed(rewritten)
        }

        fun total(): Int = status.get() + pricing.get() + account.get() + token.get() +
            perfSummary.get() + perfQuery.get()
    }

    /** rc.25 source-derived synthetic 回應（PERF-SHAPE-A/B 口徑；不是 live 產品資料）。 */
    private class PerfDispatcher(
        private val statusVersion: String = NewApiVersion.RC25,
        private val pricingModels: Map<String, String>,
        private val summaryModels: List<PerfModel>,
        private val queryGroups: (String) -> List<PerfGroup>,
        private val perfFailure: PerfFailure = PerfFailure.NONE,
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.path?.substringBefore('?')) {
                "/api/status" -> ok(statusJson(statusVersion))
                "/api/pricing" -> ok(pricingJson(pricingModels))
                "/api/user/self" -> ok(ACCOUNT_JSON)
                "/api/usage/token/" -> ok(TOKEN_JSON)
                "/api/perf-metrics/summary" -> when (perfFailure) {
                    PerfFailure.HTTP_500, PerfFailure.SUMMARY_ONLY -> driverError()
                    PerfFailure.NONE -> ok(perfSummaryJson(summaryModels))
                }
                "/api/perf-metrics" -> when (perfFailure) {
                    PerfFailure.HTTP_500 -> driverError()
                    PerfFailure.NONE, PerfFailure.SUMMARY_ONLY -> {
                        val model = request.requestUrl!!.queryParameter("model").orEmpty()
                        ok(perfQueryJson(model, queryGroups(model)))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }

        private fun ok(body: String): MockResponse =
            MockResponse().setResponseCode(200).setBody(body)

        private fun driverError(): MockResponse = MockResponse()
            .setResponseCode(500)
            .setBody("""{"success":false,"message":"$DRIVER_CANARY"}""")
    }

    private enum class PerfFailure { NONE, SUMMARY_ONLY, HTTP_500 }

    private data class PerfPoint(
        val ts: Long,
        val ttftMs: Long,
        val latencyMs: Long,
        val successRate: Double,
        val tps: Double,
    )

    private data class PerfGroup(
        val group: String,
        val ttftMs: Long,
        val latencyMs: Long,
        val successRate: Double,
        val tps: Double,
        val points: List<PerfPoint>,
    )

    private data class PerfModel(
        val name: String,
        val latencyMs: Long,
        val successRate: Double,
        val tps: Double,
        val recent: List<Double>,
    )

    /**
     * 本檔 fixture 精確清理（teardown correctness；FK RESTRICT 順序，HEAD :1182-1270 同形）。
     */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val db = app.database
        val placeholders = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val writable = db.openHelper.writableDatabase
        val canonicalIds: List<String> = runBlocking {
            val ids = LinkedHashSet<String>()
            db.openHelper.readableDatabase.query(
                "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN ($placeholders)",
                args,
            ).use { c -> while (c.moveToNext()) ids += c.getString(0) }
            ids.toList()
        }
        val perfScopes: List<Pair<String, String>> = runBlocking {
            val out = ArrayList<Pair<String, String>>()
            writable.query(
                "SELECT DISTINCT scopeKey, kind FROM provider_perf_facts " +
                    "WHERE evidenceId IN (SELECT id FROM evidence WHERE providerId IN ($placeholders))",
                args,
            ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getString(1) }
            out
        }
        val ownHeads: List<Pair<String, String>> = runBlocking {
            val out = ArrayList<Pair<String, String>>()
            for (kind in SnapshotKind.values()) {
                for (h in db.snapshotFactDao().headsByKind(kind.name)) {
                    val parts = parseScopeKey(h.scopeKey) ?: continue
                    if (parts.getOrNull(0) in providerIds) out += h.scopeKey to h.kind
                }
            }
            out
        }
        writable.beginTransaction()
        try {
            deleteOwnProviderAccountUsageRows(writable, providerIds)
            writable.execSQL(
                "DELETE FROM provider_perf_facts WHERE evidenceId IN " +
                    "(SELECT id FROM evidence WHERE providerId IN ($placeholders))",
                args,
            )
            for ((scopeKey, kind) in perfScopes) {
                writable.execSQL(
                    "DELETE FROM provider_perf_heads WHERE scopeKey = ? AND kind = ?",
                    arrayOf<Any?>(scopeKey, kind),
                )
            }
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_facts WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_heads WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            writable.execSQL("DELETE FROM price_snapshots WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM quota_snapshots WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM samples WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM my_models WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($placeholders)", args)
            writable.execSQL(
                "DELETE FROM credentials WHERE accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($placeholders))",
                args,
            )
            writable.execSQL("DELETE FROM offerings WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM endpoints WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM accounts WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM evidence WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM providers WHERE providerId IN ($placeholders)", args)
            if (canonicalIds.isNotEmpty()) {
                val ph = canonicalIds.joinToString(",") { "?" }
                writable.execSQL(
                    "DELETE FROM canonical_models WHERE canonicalModelId IN ($ph) " +
                        "AND canonicalModelId NOT IN (SELECT canonicalModelId FROM provider_model_aliases)",
                    canonicalIds.toTypedArray<Any?>(),
                )
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val SERIES_SCHEMA = "dbcd0a3c01b55203"
        const val UNKNOWN_VERSION = "v0.4.0-probe"
        const val DRIVER_CANARY = "passport-driver-canary-must-never-escape"
        const val ACCOUNT_JSON =
            """{"success":true,"message":"","data":{"quota":1000000,"used_quota":250000,"request_count":42,"group":"default","role":1,"status":1}}"""
        const val TOKEN_JSON =
            """{"code":true,"message":"ok","data":{"object":"token_usage","name":"passport-flow-key","total_granted":1000,"total_used":400,"total_available":600,"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""

        fun statusJson(version: String): String =
            """{"success":true,"message":"","data":{"version":"$version","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

        fun pricingJson(models: Map<String, String>): String {
            val items = models.entries.joinToString(",") { (m, ratio) ->
                """{"model_name":"$m","quota_type":0,"model_ratio":$ratio,"completion_ratio":1,"enable_groups":["all"],"supported_endpoint_types":["openai"]}"""
            }
            return """{"success":true,"message":"","data":[$items],"group_ratio":{"default":1},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"passport-flow-v1"}"""
        }

        fun perfSummaryJson(models: List<PerfModel>): String {
            val items = models.joinToString(",") { row ->
                val recent = row.recent.joinToString(",")
                """{"model_name":"${row.name}","avg_latency_ms":${row.latencyMs},"success_rate":${row.successRate},"avg_tps":${row.tps},"recent_success_rates":[$recent]}"""
            }
            return """{"success":true,"message":"","data":{"models":[$items]}}"""
        }

        fun perfQueryJson(model: String, groups: List<PerfGroup>): String {
            val items = groups.joinToString(",") { group ->
                val points = group.points.joinToString(",") { p ->
                    """{"ts":${p.ts},"avg_ttft_ms":${p.ttftMs},"avg_latency_ms":${p.latencyMs},"success_rate":${p.successRate},"avg_tps":${p.tps}}"""
                }
                """{"group":"${group.group}","avg_ttft_ms":${group.ttftMs},"avg_latency_ms":${group.latencyMs},"success_rate":${group.successRate},"avg_tps":${group.tps},"series":[$points]}"""
            }
            return """{"success":true,"message":"","data":{"model_name":"$model","series_schema":"$SERIES_SCHEMA","groups":[$items]}}"""
        }
    }
}
