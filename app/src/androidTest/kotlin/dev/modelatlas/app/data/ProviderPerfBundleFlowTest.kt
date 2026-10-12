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
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiPerfPoint
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
 * NINT-004 provider-wide perf 真 production flow 證據（device androidTest；本檔唯一新增）。
 *
 * 覆蓋 TRACEABILITY NINT-004／Contract §2（PROVIDER_WIDE 分離）／§3.1（預設 7d）／
 * §6.1–§6.2（summary＋per-model／group）／§23.3（加入 My Models 自動 enrichment）／
 * D-008／D-009；PRODUCT_SPEC §11／§12。
 *
 * 真鏈路層級（不誇大，逐 case 如實）：
 * - Case 1／Case 4 走**真 VM**（[AtlasViewModel.maybeEnrichMyModel]，真 Room／真 Main
 *   barrier）→ 真 `AtlasRepository.enrichMyModelBundle` → 真 [ProviderPerfStore] →
 *   真 `NewApiAdapter.fetchPerfSummary`／`fetchPerfQuery` → loopback 真 socket
 *   （MockWebServer）→ 真 Room `provider_perf_facts`／`provider_perf_heads`。
 * - Case 2／Case 3／Case 5 直接打**真 repository 腿**（繞開 VM 自身的 pricing 證據守衛，
 *   才能精準命中 perf 腿的 stored-cert gate 與 typed failure 語意）；其後鏈路與上完全
 *   相同：真 store／真 adapter／loopback HTTP／真 Room。
 *
 * 傳輸說明（非掩飾，review 必讀）：
 * - DB 的 endpoint baseUrl 維持 `https://…-perf-<uuid>.example.com/v1`（生產
 *   [dev.modelatlas.core.network.EndpointPolicy] HTTPS gate 要求）；`http` 在
 *   `enrichMyModelBundle`／[ProviderPerfStore] 前置即 fail-fast，不燒額度。
 * - 測試端 OkHttp [PerfLoopback] interceptor 只做**傳輸改寫**：已通過生產驗證的
 *   `https` request 只換 scheme/host/port 為 loopback 真 socket，path／query
 *   （`model`／`hours`／`group`）／Authorization／method 原樣保留並由 MockWebServer
 *   真實記錄。DB 不存 loopback、不存 secret；改寫只存在測試傳輸層。
 * - credential 為 `CountingVault(safeRef -> synthetic fake secret)`，只對 loopback
 *   生效；本檔斷言 perf／perf-summary 的 Authorization 只可能是**管理 PAT** 的 fake
 *   secret，且明確不是 inference key secret。
 *
 * **Fixture 資料聲明**：本檔所有 JSON／數值皆為 rc.25（`QuantumNous/new-api`
 * tag `v1.0.0-rc.25` = `f1164142`，PERF-SHAPE-A/B）**source-derived synthetic**
 * 測試 fixture，僅存在本檔、僅供 loopback 傳輸，**不是 live 產品資料**、不進任何
 * 產品槽。斷言一律打在經真 adapter 解析＋真 store 落庫後的 DB；fixture 值不冒充
 * 產品事實（跨 model／group 使用互異數值，任何串欄或借用都會失敗）。
 *
 * 隔離：每個 case 唯一 UUID provider fixture；所有 DB 斷言皆 provider／canonical 維度
 * 精確查詢，不做全域總量假設，不污染其他 test class。teardown 依 FK RESTRICT 順序
 * （perf facts → perf heads → snapshot facts/heads → price/quota snapshots → samples /
 * my_models / configs / credentials / offerings / endpoints / accounts / aliases →
 * evidence → providers → orphan canonical）。
 *
 * Barrier／timeout：全部 [CompletableDeferred]＋`withTimeout(TIMEOUT_MS)`（15s，與既有
 * flow test 同值，未延長）；零 `sleep`、零 expectation 放寬。閘門釋放包在 `finally`，
 * 失敗路徑不會留下被阻塞的 OkDisk 執行緒。
 */
@RunWith(AndroidJUnit4::class)
class ProviderPerfBundleFlowTest {

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

    // ===== 1. 真 VM wave：provider-wide pricing 仍 1 次，每 model 各自 168h perf 落新表 =====

    @Test
    fun twoModelsInOneProviderWaveShareOnePricingButEachModelGetsItsOwn168hPerfQuery() {
        val tag = shortId()
        val providerId = "perf-flow-wave-$tag"
        val accountId = "a-perf-wave-$tag"
        val patRef = "pat-perf-wave-$tag"
        val keyRef = "key-perf-wave-$tag"
        val patSecret = "perf-pat-secret-wave-$tag-0123456789"
        val keySecret = "perf-key-secret-wave-$tag-9876543210"
        val endpointId = "e-perf-wave-$tag"
        val modelA = "perf-wave-a-$tag"
        val modelB = "perf-wave-b-$tag"
        val modelAbsent = "perf-wave-absent-$tag"
        providerIds += providerId

        val summaryA = PerfModel(modelA, 111L, 98.5, 11.25, listOf(97.0, 98.0, 98.5))
        val summaryB = PerfModel(modelB, 222L, 77.25, 33.5, listOf(70.0, 75.0, 77.25))
        val groupsA = listOf(
            PerfGroup("default", 120L, 300L, 98.5, 11.25, listOf(
                PerfPoint(1_760_000_100L, 120L, 300L, 98.5, 11.25),
                PerfPoint(1_760_000_200L, 130L, 320L, 98.4, 11.1),
            )),
            PerfGroup("vip", 60L, 150L, 99.9, 20.0, listOf(
                PerfPoint(1_760_000_300L, 60L, 150L, 99.9, 20.0),
            )),
        )
        val groupsB = listOf(
            PerfGroup("default", 900L, 1800L, 70.5, 30.0, listOf(
                PerfPoint(1_760_000_400L, 900L, 1800L, 70.5, 30.0),
                PerfPoint(1_760_000_500L, 950L, 1900L, 69.5, 29.0),
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

                // 前置：真 syncNewApi 建立 certified 官方 evidence＋PRICE head
                // （VM 零燒額守衛與 perf stored-cert gate 的唯一 DB 真相來源）。
                val seedLoopback = PerfLoopback(server)
                val seeded = app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals(2, seeded.prices.size)
                assertEquals("seed sync runs the 4-call provider-wide contract (own-passport self-hit)", 4, seedLoopback.total())
                drainRequests(expected = 4)
                assertEquals(
                    "seeded official evidence must certify rc.25 purely through persisted DB state",
                    NewApiVersionSupport.CERTIFIED_RC25,
                    app.repository.providerPerf.certifiedProviderVersion(providerId).support,
                )
                assertEquals(
                    "product default performance window must be 168h (Contract §3.1)",
                    168L,
                    ProviderPerfStore.DEFAULT_ENRICHMENT_HOURS.toLong(),
                )
                val priceBaseline = countRows("price_snapshots", providerId)
                val quotaBaseline = countRows("quota_snapshots", providerId)
                val sampleBaseline = countRows("samples", providerId)

                // Wave：第一個 provider-wide pricing 請求**真阻塞**在 interceptor 內，
                // 第二 model 必須排隊；放行後兩者各自完成（deterministic，零 sleep）。
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
                    // pricing 仍卡在 interceptor：此時 perf 腿不可能已發出請求。
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

                // 同一 wave：provider-wide pricing 只燒一次 PAT；每個 exact model 各自
                // 建 perf adapter 並發出自己的 168h query（pricing 去重不得吃掉 per-model perf）。
                assertEquals("same provider wave must share one pricing fetch", 1, loopback.pricing.get())
                assertEquals("pricing adapter is built once per wave leader", 1, pricingBuilds.get())
                assertEquals("every exact model must get its own perf adapter", 2, perfBuilds.get())
                assertEquals("B memo-hits on A's summary: only one summary fetch per wave", 1, loopback.perfSummary.get())
                assertEquals("each exact model queries perf exactly once", 2, loopback.perfQuery.get())
                assertEquals(listOf(modelA, modelB), loopback.queryModels.toList())
                // sync 腿 4（outer status1＋pricing1＋account1＋token1〔matchesLeg 各跳
                // 1 status；outer caps 保留為 passport-creation 觀察〕）＋A perf 2
                // （own-caps reuse 0 status＋summary1＋query1）＋B query1（caps 跳過＋
                // memo HIT 0 HTTP＋per-model query 必跑）＝7：真契約總量，改變即誠實失敗。
                assertEquals(7, server.requestCount - baseline)

                // RecordedRequest.path 含 query，故一律用 requestUrl.encodedPath 判斷。
                val recorded = drainRequests(expected = 7)
                assertEquals(1, recorded.count { it.encodedPath() == "/api/pricing" })
                assertEquals(1, recorded.count { it.encodedPath() == "/api/perf-metrics/summary" })
                val queries = recorded.filter { it.encodedPath() == "/api/perf-metrics" }
                assertEquals(2, queries.size)
                for (query in queries) {
                    assertEquals(
                        "product enrichment must request the 168h window, not the server default",
                        listOf("168"),
                        query.requestUrl!!.queryParameterValues("hours"),
                    )
                    assertNull("group unspecified means all groups", query.requestUrl!!.queryParameter("group"))
                }
                assertEquals(
                    listOf(modelA, modelB),
                    queries.map { requireNotNull(it.requestUrl!!.queryParameter("model")) }.sorted(),
                )
                // 認證邊界：perf 兩端點只帶管理 PAT，inference key 永不進 `/api/…`。
                for (request in recorded.filter { it.encodedPath().startsWith("/api/perf-metrics") }) {
                    assertEquals("Bearer $patSecret", request.getHeader("Authorization"))
                    assertFalse(
                        "inference secret must never reach perf endpoints",
                        request.getHeader("Authorization")!!.contains(keySecret),
                    )
                }
                assertTrue("perf leg must resolve the PAT safe ref", vault.asked.any { it.startsWith("$patRef@") })

                // ---- 新表真值：每 model 各自 model／group／history，168h scope ----
                val perf = app.repository.providerPerf
                val groupA = perf.observation(providerId, modelA, "default", 168, ProviderPerfKind.GROUP)
                assertNotNull("A/default GROUP latest missing", groupA.latest)
                assertNotNull("A/default GROUP last-good missing", groupA.lastGood)
                val goodA = groupA.lastGood!!
                assertEquals(modelA, goodA.modelId)
                assertEquals("default", goodA.groupName)
                assertEquals(168L, goodA.hours.toLong())
                assertEquals("OK", goodA.status)
                assertFalse(goodA.empty)
                assertEquals(NewApiVersion.RC25, goodA.providerVersion)
                assertEquals(120L, goodA.avgTtftMs!!)
                assertEquals(300L, goodA.avgLatencyMs!!)
                assertEquals(98.5, goodA.successRate!!, 0.0)
                assertEquals(11.25, goodA.avgTps!!, 0.0)
                assertEquals(2, goodA.seriesPointCount!!)
                assertEquals(SERIES_SCHEMA, goodA.seriesSchema)
                assertNull("GROUP rows carry no recent_success_rates", goodA.recentSuccessRates)
                assertEquals(accountId, goodA.accountId)
                assertEquals(patRef, goodA.credentialId)
                assertEquals(
                    "each group keeps its own typed time series",
                    listOf(
                        NewApiPerfPoint(1_760_000_100L, 120L, 300L, 98.5, 11.25),
                        NewApiPerfPoint(1_760_000_200L, 130L, 320L, 98.4, 11.1),
                    ),
                    perf.seriesPoints(goodA),
                )

                val groupAVip = perf.observation(providerId, modelA, "vip", 168, ProviderPerfKind.GROUP)
                assertNotNull("A/vip group must not be overwritten by A/default", groupAVip.lastGood)
                assertEquals("vip", groupAVip.lastGood!!.groupName)
                assertEquals(60L, groupAVip.lastGood!!.avgTtftMs!!)
                assertEquals(99.9, groupAVip.lastGood!!.successRate!!, 0.0)

                val groupB = perf.observation(providerId, modelB, "default", 168, ProviderPerfKind.GROUP)
                assertNotNull("B/default GROUP last-good missing", groupB.lastGood)
                assertEquals(modelB, groupB.lastGood!!.modelId)
                assertEquals(900L, groupB.lastGood!!.avgTtftMs!!)
                assertEquals(1800L, groupB.lastGood!!.avgLatencyMs!!)
                assertEquals(70.5, groupB.lastGood!!.successRate!!, 0.0)
                assertEquals(30.0, groupB.lastGood!!.avgTps!!, 0.0)

                // Summary scope：provider-wide 每 model 一列（group 恆 null），percent 逐值。
                val sumA = perf.observation(providerId, modelA, null, 168, ProviderPerfKind.SUMMARY)
                assertNotNull(sumA.lastGood)
                assertEquals(modelA, sumA.lastGood!!.modelId)
                assertNull(sumA.lastGood!!.groupName)
                assertEquals(111L, sumA.lastGood!!.avgLatencyMs!!)
                assertEquals(98.5, sumA.lastGood!!.successRate!!, 0.0)
                assertEquals(11.25, sumA.lastGood!!.avgTps!!, 0.0)
                assertEquals(listOf(97.0, 98.0, 98.5), decodePerfRates(sumA.lastGood!!.recentSuccessRates))
                assertNull("SUMMARY rows carry no per-group series", sumA.lastGood!!.seriesJson)
                assertNull(sumA.lastGood!!.seriesPointCount)
                val sumB = perf.observation(providerId, modelB, null, 168, ProviderPerfKind.SUMMARY)
                assertEquals(modelB, sumB.lastGood!!.modelId)
                assertEquals(222L, sumB.lastGood!!.avgLatencyMs!!)
                assertEquals(77.25, sumB.lastGood!!.successRate!!, 0.0)
                assertNull("summary rows carry no TTFT", sumB.lastGood!!.avgTtftMs)

                // 每個 GROUP scope 本 wave 各一次觀察（provider-wide summary 不影響 group 歷史）。
                assertEquals(1, perf.history(providerId, modelA, "default", 168, ProviderPerfKind.GROUP).size)
                assertEquals(1, perf.history(providerId, modelA, "vip", 168, ProviderPerfKind.GROUP).size)
                assertEquals(1, perf.history(providerId, modelB, "default", 168, ProviderPerfKind.GROUP).size)
                // B 的 memo hit 零寫（persisted-once）：A 的 summary fetch 落庫一次，
                // 同 model scope 恰 1 筆歷史。
                assertEquals(1, perf.history(providerId, modelA, null, 168, ProviderPerfKind.SUMMARY).size)

                // 未被請求的 model／未被同步的窗口／缺席 group 不得憑空存在
                // （Unknown≠0；absent group ≠ 零流量）。
                val absentModel = perf.observation(providerId, modelAbsent, null, 168, ProviderPerfKind.GROUP)
                assertNull(absentModel.latest)
                assertNull(absentModel.lastGood)
                assertNull(
                    "perf must not fabricate a 24h window",
                    perf.observation(providerId, modelA, "default", 24, ProviderPerfKind.GROUP).lastGood,
                )
                assertNull(perf.observation(providerId, modelA, "absent-group", 168, ProviderPerfKind.GROUP).lastGood)
                // 跨 provider 隔離：別的 provider scope 不得被本 provider 的 wave 命中。
                assertNull(
                    perf.observation("other-provider-$tag", modelA, "default", 168, ProviderPerfKind.GROUP).lastGood,
                )

                // pricing 腿仍各自精確寫 head（perf 不影響既有七維 FREE 證據）。
                val scopeA = PricingScope(providerId, accountId, null, modelA, "default", endpointId, "CHAT_COMPLETIONS")
                val scopeB = PricingScope(providerId, accountId, null, modelB, "default", endpointId, "CHAT_COMPLETIONS")
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(scopeA).latest!!.state)
                assertEquals("PAID", app.repository.newApiSnapshots.price(scopeB).latest!!.state)
                assertTrue(store.isMyModel(providerId, modelA))
                assertTrue(store.isMyModel(providerId, modelB))

                // 槽位分離（真 baseline 比較，非字串 absence）：
                // - samples 完全不動（站方 metric 不得變成本機 telemetry）。
                // - price/quota 只因 wave 的 pricing 腿各多一個 scope 的觀察（2 price＋2 quota）。
                // - 站方 perf canary 不得出現在 external／price／quota／snapshot 事實槽。
                assertEquals(
                    "provider perf must never create local samples",
                    sampleBaseline,
                    countRows("samples", providerId),
                )
                assertEquals(priceBaseline + 2, countRows("price_snapshots", providerId))
                assertEquals(quotaBaseline + 2, countRows("quota_snapshots", providerId))
                assertPerfMetricsAbsentFromOtherSlots(providerId, listOf("1760000100", "1760000400", SERIES_SCHEMA))
            }
        }
    }

    // ===== 2. perf 腿 HTTP 失敗：membership／perf last-good／pricing 全保留，不造成功 =====

    @Test
    fun perfHttpFailureKeepsMembershipLastGoodAndPricingWithoutFakingSuccess() {
        val tag = shortId()
        val providerId = "perf-flow-fail-$tag"
        val accountId = "a-perf-fail-$tag"
        val patRef = "pat-perf-fail-$tag"
        val keyRef = "key-perf-fail-$tag"
        val patSecret = "perf-pat-secret-fail-$tag-0123456789"
        val keySecret = "perf-key-secret-fail-$tag-9876543210"
        val endpointId = "e-perf-fail-$tag"
        val model = "perf-fail-model-$tag"
        providerIds += providerId

        val summaryRow = PerfModel(model, 321L, 88.5, 15.5, listOf(88.5))
        val groupRows = listOf(
            PerfGroup("default", 210L, 640L, 88.5, 15.5, listOf(
                PerfPoint(1_760_000_600L, 210L, 640L, 88.5, 15.5),
            )),
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(model))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, model, source = "USER", nowMillis = 6_000L))

                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(model to "0"),
                    summaryModels = listOf(summaryRow),
                    queryGroups = { groupRows },
                )
                // controlled clock（本 case 專用）：seed＝1_000／good＝2_000／fail＝3_000，
                // 讓 observedAt 完全可預期，不受 realSystemClock ms 競速影響。
                val clock = ScriptedClock(1_000L)
                val loopback = PerfLoopback(server)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val perf = app.repository.providerPerf
                val scope = PricingScope(providerId, accountId, null, model, "default", endpointId, "CHAT_COMPLETIONS")

                app.repository.syncNewApi(providerId, NewApiAdapter(vault, client, clock))
                assertEquals(4, loopback.total())

                // Phase 1：真 good perf → last-good 基線。
                clock.set(2_000L)
                val good = app.repository.enrichMyModelBundle(
                    providerId = providerId,
                    providerModelId = model,
                    accountId = accountId,
                    group = "default",
                    endpointId = endpointId,
                    operation = "CHAT_COMPLETIONS",
                    newApiAdapter = NewApiAdapter(vault, client, clock),
                    newApiPerfAdapter = NewApiAdapter(vault, client, clock),
                )
                assertTrue("a good perf leg must report fetched", good.providerPerformanceFetched)
                assertFalse(good.stepFailures.contains(PERF_LABEL))
                assertEquals(1, loopback.perfSummary.get())
                assertEquals(1, loopback.perfQuery.get())
                val groupGood = perf.observation(providerId, model, "default", 168, ProviderPerfKind.GROUP)
                assertNotNull(groupGood.lastGood)
                val goodEvidenceId = groupGood.lastGood!!.evidenceId
                val perfGoodObservedAt = groupGood.lastGood!!.observedAt
                assertEquals("the good perf observation carries the scripted timestamp", 2_000L, perfGoodObservedAt)
                val summaryGoodEvidenceId = perf.observation(providerId, model, null, 168, ProviderPerfKind.SUMMARY)
                    .lastGood!!.evidenceId
                // pricing 第三腿在 seed 與 good bundle 各跑一次 → 2 次觀察（1_000／2_000）。
                val priceHistoryBefore = app.repository.newApiSnapshots.history(scope, SnapshotKind.PRICE)
                assertEquals("seed sync plus the good bundle are two price observations", 2, priceHistoryBefore.size)
                assertEquals(listOf(2_000L, 1_000L), priceHistoryBefore.map { it.observedAt })
                val priceEvidenceBefore = priceHistoryBefore.map { it.evidenceId }

                // Phase 2：perf 兩端點各回一次 HTTP 500（body 含 driver canary），
                // pricing 腿仍成功（獨立腿，perf 失敗不得阻擋 pricing）。
                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(model to "0"),
                    summaryModels = listOf(summaryRow),
                    queryGroups = { groupRows },
                    perfFailure = PerfFailure.HTTP_500,
                )
                clock.set(3_000L)
                val failLoopback = PerfLoopback(server)
                val failClient = OkHttpClient.Builder().addInterceptor(failLoopback).build()
                val failed = app.repository.enrichMyModelBundle(
                    providerId = providerId,
                    providerModelId = model,
                    accountId = accountId,
                    group = "default",
                    endpointId = endpointId,
                    operation = "CHAT_COMPLETIONS",
                    newApiAdapter = NewApiAdapter(vault, failClient, clock),
                    newApiPerfAdapter = NewApiAdapter(vault, failClient, clock),
                )
                assertFalse("an HTTP perf failure must never be reported as fetched", failed.providerPerformanceFetched)
                assertTrue("typed perf failure must surface a static label", failed.stepFailures.contains(PERF_LABEL))
                assertFalse("the pricing leg succeeded and must not be labelled", failed.stepFailures.contains("PRICING_NEWAPI"))
                assertEquals("one failing summary request per bundle", 1, failLoopback.perfSummary.get())
                assertEquals("one failing query request per bundle", 1, failLoopback.perfQuery.get())

                // membership 保留；舊 price 觀察全部保留，本次 pricing 腿真寫出新的 good fact。
                assertTrue(store.isMyModel(providerId, model))
                val priceHistoryAfter = app.repository.newApiSnapshots.history(scope, SnapshotKind.PRICE)
                assertEquals(
                    "the failing perf bundle still ran the pricing leg once (append-only history)",
                    3,
                    priceHistoryAfter.size,
                )
                assertEquals(listOf(3_000L, 2_000L, 1_000L), priceHistoryAfter.map { it.observedAt })
                assertEquals(
                    "both earlier price observations must remain in history",
                    priceEvidenceBefore.toSet(),
                    priceHistoryAfter.drop(1).map { it.evidenceId }.toSet(),
                )
                val priceNow = app.repository.newApiSnapshots.price(scope)
                assertEquals("FREE_NOW", priceNow.latest!!.state)
                assertEquals(
                    "the newest price observation advances latest and last-good",
                    3_000L,
                    priceNow.latest!!.observedAt,
                )
                assertEquals(3_000L, priceNow.lastGood!!.observedAt)

                // perf：HTTP failure 只 append marker；t=2_000 的 good last-good 不被 t=3_000 破壞。
                val afterGroup = perf.observation(providerId, model, "default", 168, ProviderPerfKind.GROUP)
                assertEquals("a failed leg must not move group last-good", goodEvidenceId, afterGroup.lastGood!!.evidenceId)
                assertEquals(perfGoodObservedAt, afterGroup.lastGood!!.observedAt)
                assertEquals(88.5, afterGroup.lastGood!!.successRate!!, 0.0)
                assertEquals(
                    "a failure marker must not enter the known-group history",
                    1,
                    perf.history(providerId, model, "default", 168, ProviderPerfKind.GROUP).size,
                )
                assertEquals(
                    perfGoodObservedAt,
                    perf.history(providerId, model, "default", 168, ProviderPerfKind.GROUP).single().observedAt,
                )
                assertEquals(
                    "a failing summary must not disturb the previous per-model summary last-good",
                    summaryGoodEvidenceId,
                    perf.observation(providerId, model, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId,
                )
                // failure marker 記在 model 級 GROUP scope（group=null），不得冒充任一 group 量測。
                val modelScope = perf.observation(providerId, model, null, 168, ProviderPerfKind.GROUP)
                assertNotNull("the failure marker must be recorded", modelScope.latest)
                assertEquals(NewApiStatus.HTTP_SERVER_ERROR.name, modelScope.latest!!.status)
                assertEquals(500, modelScope.latest!!.httpStatus)
                assertEquals("the failure marker carries the newest scripted timestamp", 3_000L, modelScope.latest!!.observedAt)
                assertFalse(modelScope.latest!!.empty)
                assertNull("a failure marker must never become last-good", modelScope.lastGood)
                assertNull(modelScope.latest!!.avgTtftMs)
                assertNull(modelScope.latest!!.successRate)
                assertEquals(1, perf.history(providerId, model, null, 168, ProviderPerfKind.GROUP).size)
                // summary failure marker 記在 provider 級 SUMMARY scope（modelId=null）。
                val providerSummaryScope = perf.observation(providerId, null, null, 168, ProviderPerfKind.SUMMARY)
                assertEquals(NewApiStatus.HTTP_SERVER_ERROR.name, providerSummaryScope.latest!!.status)
                assertEquals(3_000L, providerSummaryScope.latest!!.observedAt)
                assertNull(providerSummaryScope.lastGood)

                // 站方 error body 不得外流（label／fact 全無 canary）。
                val labels = failed.stepFailures.toString()
                assertFalse("driver message must not surface in static labels", labels.contains(DRIVER_CANARY))
                assertFalse("secrets must not surface in static labels", labels.contains(patSecret) || labels.contains(keySecret))
                assertFalse("static labels must not carry URLs", failed.stepFailures.any { it.contains("http") })
                val persisted = perf.history(providerId, model, "default", 168, ProviderPerfKind.GROUP)
                    .plus(perf.history(providerId, model, null, 168, ProviderPerfKind.GROUP))
                    .plus(perf.history(providerId, model, null, 168, ProviderPerfKind.SUMMARY))
                    .plus(perf.history(providerId, null, null, 168, ProviderPerfKind.SUMMARY))
                assertFalse("raw driver text must never be persisted", persisted.toString().contains(DRIVER_CANARY))
                assertFalse("raw driver text must never be persisted", persisted.toString().contains(patSecret))
                assertPerfMetricsAbsentFromOtherSlots(providerId, listOf("1760000600", SERIES_SCHEMA))
            }
        }
    }

    // ===== 3. 部分成功：summary 失敗但 query good → 兩個旗標同時成立 =====

    @Test
    fun perfSummaryFailureStillPersistsQueryGoodRowsAndKeepsBothFlags() {
        val tag = shortId()
        val providerId = "perf-flow-partial-$tag"
        val accountId = "a-perf-partial-$tag"
        val patRef = "pat-perf-partial-$tag"
        val keyRef = "key-perf-partial-$tag"
        val patSecret = "perf-pat-secret-partial-$tag-0123456789"
        val keySecret = "perf-key-secret-partial-$tag-9876543210"
        val endpointId = "e-perf-partial-$tag"
        val model = "perf-partial-model-$tag"
        providerIds += providerId

        val summaryRow = PerfModel(model, 456L, 91.0, 21.0, listOf(91.0))
        val groupRows = listOf(
            PerfGroup("svip", 45L, 205L, 93.25, 44.5, listOf(
                PerfPoint(1_760_000_700L, 45L, 205L, 93.25, 44.5),
                PerfPoint(1_760_000_800L, 50L, 215L, 93.0, 43.0),
            )),
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(model))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, model, source = "USER", nowMillis = 7_000L))

                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(model to "3"),
                    summaryModels = listOf(summaryRow),
                    queryGroups = { groupRows },
                    perfFailure = PerfFailure.SUMMARY_ONLY,
                )
                val loopback = PerfLoopback(server)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                app.repository.syncNewApi(providerId, NewApiAdapter(vault, client))

                val result = app.repository.enrichMyModelBundle(
                    providerId = providerId,
                    providerModelId = model,
                    accountId = accountId,
                    group = "svip",
                    endpointId = endpointId,
                    operation = "CHAT_COMPLETIONS",
                    newApiAdapter = NewApiAdapter(vault, client),
                    newApiPerfAdapter = NewApiAdapter(vault, client),
                )
                // summary 失敗不抹掉 query good：fetched 仍 true，failed 仍報靜態標籤。
                assertTrue("query good rows must count as fetched", result.providerPerformanceFetched)
                assertTrue("a typed summary failure must still be labelled", result.stepFailures.contains(PERF_LABEL))
                assertEquals(1, loopback.perfSummary.get())
                assertEquals(1, loopback.perfQuery.get())

                val perf = app.repository.providerPerf
                val good = perf.observation(providerId, model, "svip", 168, ProviderPerfKind.GROUP)
                assertNotNull("query good rows must be persisted despite summary failure", good.lastGood)
                assertEquals("OK", good.lastGood!!.status)
                assertEquals(model, good.lastGood!!.modelId)
                assertEquals("svip", good.lastGood!!.groupName)
                assertEquals(45L, good.lastGood!!.avgTtftMs!!)
                assertEquals(205L, good.lastGood!!.avgLatencyMs!!)
                assertEquals(93.25, good.lastGood!!.successRate!!, 0.0)
                assertEquals(44.5, good.lastGood!!.avgTps!!, 0.0)
                assertEquals(
                    listOf(
                        NewApiPerfPoint(1_760_000_700L, 45L, 205L, 93.25, 44.5),
                        NewApiPerfPoint(1_760_000_800L, 50L, 215L, 93.0, 43.0),
                    ),
                    perf.seriesPoints(good.lastGood!!),
                )
                // summary failure marker 記在 provider 級 scope（modelId=null），不得冒充該 model 量測。
                val providerSummary = perf.observation(providerId, null, null, 168, ProviderPerfKind.SUMMARY)
                assertNotNull(providerSummary.latest)
                assertEquals(NewApiStatus.HTTP_SERVER_ERROR.name, providerSummary.latest!!.status)
                assertNull("a failing summary surface must not create last-good", providerSummary.lastGood)
                assertEquals(
                    "the failure marker keeps the endpoint source",
                    ProviderPerfStore.PERF_SUMMARY_SOURCE,
                    app.database.evidenceDao().getById(providerSummary.latest!!.evidenceId)!!.source,
                )
                assertEquals(
                    "a failed summary surface must not fabricate per-model rows",
                    0,
                    perf.history(providerId, model, null, 168, ProviderPerfKind.SUMMARY).size,
                )
                assertTrue(store.isMyModel(providerId, model))
                assertPerfMetricsAbsentFromOtherSlots(providerId, listOf("1760000700", SERIES_SCHEMA))
            }
        }
    }

    // ===== 4. 同 slice 內取消：重拋 CancellationException，且不造 perf success =====

    @Test
    fun cancellingInsideThePerfLegIsRethrownAndNeverRecordsPerfSuccess() {
        val tag = shortId()
        val providerId = "perf-flow-cancel-$tag"
        val accountId = "a-perf-cancel-$tag"
        val patRef = "pat-perf-cancel-$tag"
        val keyRef = "key-perf-cancel-$tag"
        val patSecret = "perf-pat-secret-cancel-$tag-0123456789"
        val keySecret = "perf-key-secret-cancel-$tag-9876543210"
        val endpointId = "e-perf-cancel-$tag"
        val model = "perf-cancel-model-$tag"
        providerIds += providerId

        val summaryRow = PerfModel(model, 654L, 61.0, 8.5, listOf(61.0))
        val groupRows = listOf(
            PerfGroup("default", 333L, 777L, 61.0, 8.5, listOf(
                PerfPoint(1_760_000_900L, 333L, 777L, 61.0, 8.5),
            )),
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(model))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, model, source = "USER", nowMillis = 8_000L))

                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                server.dispatcher = PerfDispatcher(
                    pricingModels = mapOf(model to "0"),
                    summaryModels = listOf(summaryRow),
                    queryGroups = { groupRows },
                )
                val seedLoopback = PerfLoopback(server)
                app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )

                // 卡住第一個 perf query 請求（真 flight 中）；finally 保證釋放執行緒。
                val release = CompletableDeferred<Unit>()
                val loopback = PerfLoopback(
                    server,
                    gateFirstQuery = true,
                    releaseFirstQuery = release,
                )
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val perf = app.repository.providerPerf
                val job = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    vm.maybeEnrichMyModel(
                        providerId = providerId,
                        accountId = accountId,
                        credentialId = null,
                        group = null,
                        providerModelId = model,
                        lmspeedFetcher = null,
                        newApiAdapterFactory = { NewApiAdapter(vault, client) },
                        newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                    )
                }
                var thrown: Throwable? = null
                try {
                    withTimeout(TIMEOUT_MS) { loopback.queried.await() }
                    assertEquals(1, loopback.perfQuery.get())
                    // summary 腿已完成 → 證明取消點在 query 腿（順序真契約，非時序巧合）。
                    assertNotNull(perf.observation(providerId, model, null, 168, ProviderPerfKind.SUMMARY).lastGood)
                    job.cancel(CancellationException("flow test cancel"))
                    thrown = runCatching { withTimeout(TIMEOUT_MS) { job.await() } }.exceptionOrNull()
                } finally {
                    release.complete(Unit)
                }
                assertTrue(
                    "cancel inside the perf leg must be rethrown, never converted into a false success",
                    thrown is CancellationException,
                )

                // 被取消的 query 不得留下任何 GROUP 事實／head。
                val groupScope = perf.observation(providerId, model, "default", 168, ProviderPerfKind.GROUP)
                assertNull("cancelled perf query must not record a latest row", groupScope.latest)
                assertNull("cancelled perf query must not record last-good", groupScope.lastGood)
                assertEquals(0, perf.history(providerId, model, "default", 168, ProviderPerfKind.GROUP).size)
                assertEquals(0, perf.history(providerId, model, null, 168, ProviderPerfKind.GROUP).size)

                // membership 不受取消影響；pricing 腿已完成的事實保留。
                assertTrue(store.isMyModel(providerId, model))
                val scope = PricingScope(providerId, accountId, null, model, "default", endpointId, "CHAT_COMPLETIONS")
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(scope).latest!!.state)
                assertPerfMetricsAbsentFromOtherSlots(providerId, listOf("1760000900", SERIES_SCHEMA))
            }
        }
    }

    // ===== 5. DB 未認證版本：perf 腿零請求、零寫入（certification 來自 DB，非 flag） =====

    @Test
    fun unknownStoredCertificationIssuesZeroPerfRequestsAndWritesNoPerfFacts() {
        val tag = shortId()
        val providerId = "perf-flow-uncert-$tag"
        val accountId = "a-perf-uncert-$tag"
        val patRef = "pat-perf-uncert-$tag"
        val keyRef = "key-perf-uncert-$tag"
        val patSecret = "perf-pat-secret-uncert-$tag-0123456789"
        val keySecret = "perf-key-secret-uncert-$tag-9876543210"
        val endpointId = "e-perf-uncert-$tag"
        val model = "perf-uncert-model-$tag"
        providerIds += providerId

        val summaryRow = PerfModel(model, 999L, 55.5, 5.5, listOf(55.5))
        val groupRows = listOf(
            PerfGroup("default", 999L, 999L, 55.5, 5.5, listOf(
                PerfPoint(1_760_001_000L, 999L, 999L, 55.5, 5.5),
            )),
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(model))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, model, source = "USER", nowMillis = 9_000L))

                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                // 與 Case 1 唯一差異：站方回非認證版本字串 → 官方 evidence 於 DB 呈 UNKNOWN。
                server.dispatcher = PerfDispatcher(
                    statusVersion = UNKNOWN_VERSION,
                    pricingModels = mapOf(model to "0"),
                    summaryModels = listOf(summaryRow),
                    queryGroups = { groupRows },
                )
                val seedLoopback = PerfLoopback(server)
                app.repository.syncNewApi(
                    providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals(
                    "the fixture must produce UNKNOWN certification purely through persisted DB evidence",
                    NewApiVersionSupport.UNKNOWN,
                    app.repository.providerPerf.certifiedProviderVersion(providerId).support,
                )

                val loopback = PerfLoopback(server)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val result = app.repository.enrichMyModelBundle(
                    providerId = providerId,
                    providerModelId = model,
                    accountId = accountId,
                    group = "default",
                    endpointId = endpointId,
                    operation = "CHAT_COMPLETIONS",
                    newApiAdapter = NewApiAdapter(vault, client),
                    newApiPerfAdapter = NewApiAdapter(vault, client),
                )
                assertFalse("an uncertified version must never report provider perf", result.providerPerformanceFetched)
                assertEquals("the uncertified gate must issue zero perf summary requests", 0, loopback.perfSummary.get())
                assertEquals("the uncertified gate must issue zero perf query requests", 0, loopback.perfQuery.get())
                assertEquals("only the pricing leg may talk to an uncertified provider", 3, loopback.status.get())

                val perf = app.repository.providerPerf
                val expectedScopes = listOf(
                    providerPerfScopeKey(providerId, model, null, 168),
                    providerPerfScopeKey(providerId, model, "default", 168),
                    providerPerfScopeKey(providerId, null, null, 168),
                )
                for (kind in ProviderPerfKind.values()) {
                    val heads = app.database.providerPerfDao().headsByKind(kind.name)
                    assertTrue(
                        "no perf head may exist for an uncertified provider ($kind)",
                        heads.none { it.scopeKey in expectedScopes },
                    )
                }
                assertNull(perf.observation(providerId, model, "default", 168, ProviderPerfKind.GROUP).latest)
                assertEquals(0, perf.history(providerId, model, "default", 168, ProviderPerfKind.GROUP).size)
                assertEquals(0, perf.history(providerId, model, null, 168, ProviderPerfKind.SUMMARY).size)

                // membership 保留；pricing 維持 Unknown 語意（不補 0／不補 Free）。
                assertTrue(store.isMyModel(providerId, model))
                assertTrue(
                    "an uncertified provider must never derive FREE_NOW",
                    result.freePriceState == null || result.freePriceState == "UNKNOWN",
                )
                assertEquals(
                    "no official price may be persisted for an uncertified provider",
                    0,
                    countRows("price_snapshots", providerId),
                )
                assertEquals(0, countRows("samples", providerId))
            }
        }
    }

    // ===== harness（fixture 僅本檔，不當產品資料） =====

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
                workflowId = "wf-$providerId-perfflow",
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
     * external／price／quota／snapshot 事實槽。
     *
     * - `samples`／`price_snapshots`／`quota_snapshots`／`provider_events` 有 providerId。
     * - `external_observations`／`external_mappings` **沒有** providerId（不可冒造 column），
     *   只以本 fixture 自己的 canonicalIds 精確掃描。
     * - `snapshot_facts`／`snapshot_heads` 無 providerId 欄，經真 [parseScopeKey] 過濾後掃描。
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
     * 本檔唯一使用 controlled clock 的 case（HTTP failure）专用：每個 phase 由測試
     * 明確指定 `observedAt`（1_000／2_000／3_000），因此 last-good／append-history 的
     * 時序契約可被**精確**斷言，不受 realSystemClock 毫秒競速影響。
     *
     * `monotonicMillis()` 以 `value - start` 表達，隨 phase 單調不減；[set] 只允許
     * 往前推進（倒退會直接拋出，避免測試自己製造違反 newerGood 的時間）。
     */
    private class ScriptedClock(private val start: Long) : Clock {
        private val value = AtomicLong(start)
        fun set(millis: Long) {
            require(millis >= value.get()) { "scripted clock must move forward" }
            value.set(millis)
        }
        override fun epochMillis(): Long = value.get()
        override fun monotonicMillis(): Long = value.get() - start
    }

    /**
     * 測試傳輸接縫：只換 scheme/host/port 到 loopback 真 socket（path／query／
     * Authorization 原樣保留並被 MockWebServer 真實記錄），並以 atomic tally 記錄各
     * 端點真實請求數；可選 deterministic 閘門（首個 pricing 或首個 perf query）**真阻塞**
     * 於 interceptor 內，供 wave 排序與 cancel 觀察。產品路徑永不開啟。
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

    /**
     * rc.25（`f1164142`）source-derived synthetic 回應（PERF-SHAPE-A/B）。僅供 loopback
     * 傳輸層；不是 live 產品資料。`queryGroups` 以被請求的 model 回應**同名** model
     * （站方 response 必須與 requested exact model 吻合；store 最後 fail-closed 驗證）。
     */
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
     * 本檔 fixture 精確清理（teardown correctness；FK RESTRICT 順序）。
     *
     * - `provider_perf_facts` 無 providerId 欄，只能經 `evidence` 子查詢精準定位；
     *   `provider_perf_heads` 由實際 facts 的 (scopeKey, kind) 集合精確刪除（長度前綴
     *   編碼，絕不用 LIKE，見 [providerPerfScopeKey]）。
     * - `snapshot_facts`／`snapshot_heads` 經真 [parseScopeKey] 過濾 provider 維。
     * - `evidence` 必須在所有引用它的子列（perf facts、snapshot facts、NINT-005A 的
     *   `provider_account_usage_facts`，FK RESTRICT）之後刪；usage 兩表走共用 test-only
     *   helper [deleteOwnProviderAccountUsageRows] 精確刪自己的 rows，不得 wildcard。
     * - canonicalIds 必須在刪 aliases 之前查。
     * - 失敗即拋出（不吞例外）；fixture 全 UUID 唯一 scope，不影響他人。
     */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val db = app.database
        val placeholders = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val writable = db.openHelper.writableDatabase
        // 自己的 canonicalIds：必須在刪 aliases 之前查。
        val canonicalIds: List<String> = runBlocking {
            val ids = LinkedHashSet<String>()
            db.openHelper.readableDatabase.query(
                "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN ($placeholders)",
                args,
            ).use { c -> while (c.moveToNext()) ids += c.getString(0) }
            ids.toList()
        }
        // 自己的 perf (scopeKey, kind)：由事實集合反查真 key（非 wildcard）。
        val perfScopes: List<Pair<String, String>> = runBlocking {
            val out = ArrayList<Pair<String, String>>()
            writable.query(
                "SELECT DISTINCT scopeKey, kind FROM provider_perf_facts " +
                    "WHERE evidenceId IN (SELECT id FROM evidence WHERE providerId IN ($placeholders))",
                args,
            ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getString(1) }
            out
        }
        // 自己的 snapshot (scopeKey, kind)：真 parseScopeKey 過濾 provider 維。
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
            // 1. perf facts（FK → evidence，必須最先）
            writable.execSQL(
                "DELETE FROM provider_perf_facts WHERE evidenceId IN " +
                    "(SELECT id FROM evidence WHERE providerId IN ($placeholders))",
                args,
            )
            // 2. perf heads（實際 scopeKey 集合）
            for ((scopeKey, kind) in perfScopes) {
                writable.execSQL(
                    "DELETE FROM provider_perf_heads WHERE scopeKey = ? AND kind = ?",
                    arrayOf<Any?>(scopeKey, kind),
                )
            }
            // 3. 舊 snapshot facts／heads（FK → evidence）
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_facts WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_heads WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            // 4. price/quota snapshot children 與其他 provider 維子列（FK → evidence／providers）
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
            // 5. evidence（所有引用子列皆已刪除）→ providers
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
        const val PERF_LABEL = "PROVIDER_PERF"
        const val SERIES_SCHEMA = "dbcd0a3c01b55203"
        const val UNKNOWN_VERSION = "v0.4.0-probe"
        const val DRIVER_CANARY = "perf-driver-canary-must-never-escape"
        const val ACCOUNT_JSON =
            """{"success":true,"message":"","data":{"quota":1000000,"used_quota":250000,"request_count":42,"group":"default","role":1,"status":1}}"""
        const val TOKEN_JSON =
            """{"code":true,"message":"ok","data":{"object":"token_usage","name":"perf-flow-key","total_granted":1000,"total_used":400,"total_available":600,"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""

        fun statusJson(version: String): String =
            """{"success":true,"message":"","data":{"version":"$version","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

        fun pricingJson(models: Map<String, String>): String {
            val items = models.entries.joinToString(",") { (m, ratio) ->
                """{"model_name":"$m","quota_type":0,"model_ratio":$ratio,"completion_ratio":1,"enable_groups":["all"],"supported_endpoint_types":["openai"]}"""
            }
            return """{"success":true,"message":"","data":[$items],"group_ratio":{"default":1},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"perf-flow-v1"}"""
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
