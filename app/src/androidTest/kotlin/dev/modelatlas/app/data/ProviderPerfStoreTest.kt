package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
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
 * NINT-004 provider-perf store tests（device androidTest；本檔唯一新增）。
 *
 * 真 in-memory Room（v12 entities＋real DAOs）＋真 [ProviderPerfStore.syncPerf]
 * ＋真 [NewApiAdapter] HTTP 棧 → MockWebServer loopback 真 socket。
 * Fixture JSON 僅存本檔（rc.25 PERF-SHAPE-B §10 形狀衍生），經真 adapter 解析＋
 * 真 store 落庫後才斷言 DB，不拿 fixture 值冒充產品資料。
 *
 * 傳輸說明（review 必讀）：
 * - DB endpoint baseUrl 維持 `https://…example.com/v1`（生產 [EndpointPolicy] HTTPS
 *   gate 要求）；測試用 OkHttp [Interceptor] 只做路由改寫（scheme／host／port→loopback，
 *   path＋query＋Authorization 原樣保留，MockWebServer 真實記錄）。
 * - Secret 全為本檔 fake（`loopback-pat-secret-…`），只發往 loopback；raw `sk-` 絕不進
 *   repo／log／prompt（raw 測試只用短 `sk-ab` 走 seed-guard 可達的 store 拒絕路徑）。
 * - Stored cert 前置為測試顯式種子的官方 fact／head／evidence triple（source＋rc25＋OK＋
 *   http200 全匹配），僅為測試夾具，不是 deployment verified 宣告。
 * - 每個測試用自己 isolated DB；`samples`／external／price-quota 零副作用斷言只讀自己
 *   DB，不做全局 DB 斷言。`seriesPointCount` 只是點數輔助，不當樣本數；bucket points
 *   不是 samples。未知版本／零 HTTP／`request_count` 無欄（Unknown，不造 0）。
 */
@RunWith(AndroidJUnit4::class)
class ProviderPerfStoreTest {

    private lateinit var server: MockWebServer
    private val open = mutableListOf<AtlasDatabase>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    // ===== 1. happy path：summary＋query 逐值持久化＋全 series＋evidence＋零副作用 =====

    @Test
    fun syncPersistsSummaryAndQueryVerbatimWithFullSeries() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p1"
        seedProvider(db, provider, "a-p1", "e-p1")
        seedCertifiedPrice(db, provider, "a-p1", "e-p1")
        val store = ProviderPerfStore(db)
        val vault = CountingVault(mapOf("unused-ref" to "loopback-unused"))
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())

        val result = store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)), provider, "m-perf-a")

        assertTrue(result.certified)
        assertTrue(result.fetched)
        assertFalse(result.failed)
        assertEquals(2, result.summaryFacts)
        assertEquals(2, result.queryFacts)

        // Summary 逐值：percent 原樣（兩位小數保留），0 保留為 0 不猜 Unknown。
        val summaryA = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY)
        assertEquals(98.37, summaryA.lastGood!!.successRate!!, 0.0)
        assertEquals(120L, summaryA.lastGood!!.avgLatencyMs)
        assertEquals(12.5, summaryA.lastGood!!.avgTps!!, 0.0)
        assertNull("summary 列無 TTFT 欄", summaryA.lastGood!!.avgTtftMs)
        assertEquals(listOf(97.5, 98.0, 98.37), decodePerfRates(summaryA.lastGood!!.recentSuccessRates))
        assertNull("request_count 無欄：SUMMARY 不存點數", summaryA.lastGood!!.seriesPointCount)
        assertNull(summaryA.lastGood!!.seriesJson)
        val summaryB = store.observation(provider, "m-perf-b", null, 168, ProviderPerfKind.SUMMARY)
        assertEquals(0L, summaryB.lastGood!!.avgLatencyMs)
        assertEquals(100.0, summaryB.lastGood!!.successRate!!, 0.0)
        assertEquals("顯式空陣列與缺席不同", emptyList<Double>(), decodePerfRates(summaryB.lastGood!!.recentSuccessRates))

        // Query 逐元素：group 缺席≠零流量（vip 照存）；series 每點全存（非 pointCount 代替）。
        val default = store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP)
        assertEquals(2, default.lastGood!!.seriesPointCount)
        assertEquals("dbcd0a3c01b55203", default.lastGood!!.seriesSchema)
        val points = store.seriesPoints(default.lastGood!!)!!
        assertEquals("normalized 系列依 ts 升冪", listOf(1_700_000_000L, 1_700_000_060L), points.map { it.tsEpochSeconds })
        assertEquals(10L, points[0].avgTtftMs)
        assertEquals(99.0, points[0].successRate, 0.0)
        assertEquals(19.5, points[1].avgTps, 0.0)
        val vip = store.observation(provider, "m-perf-a", "vip", 168, ProviderPerfKind.GROUP)
        assertEquals("顯式空序列記 []，仍為 good 列", "[]", vip.lastGood!!.seriesJson)
        assertEquals(0, vip.lastGood!!.seriesPointCount)
        assertTrue(store.seriesPoints(vip.lastGood!!)!!.isEmpty())

        // Evidence 一致：source／providerId／timestamp 與 fact 對齊。
        for (fact in listOf(summaryA.lastGood!!, default.lastGood!!)) {
            val evidence = db.evidenceDao().getById(fact.evidenceId)!!
            val expectedSource = if (fact.kind == ProviderPerfKind.SUMMARY.name) {
                ProviderPerfStore.PERF_SUMMARY_SOURCE
            } else {
                ProviderPerfStore.PERF_QUERY_SOURCE
            }
            assertEquals(expectedSource, evidence.source)
            assertEquals(provider, evidence.providerId)
            assertEquals(fact.observedAt, evidence.timestamp)
            assertEquals(200, evidence.status)
        }

        // FK RESTRICT：只捕獲特定約束例外（其他例外照常拋出，不吞例外假 pass）。
        val victim = default.lastGood!!
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM evidence WHERE id = ?", arrayOf(victim.evidenceId))
            throw AssertionError("FK RESTRICT must block evidence delete")
        } catch (_: SQLiteConstraintException) {
        }
        assertNotNull(db.providerPerfDao().fact(victim.evidenceId))

        // 零副作用（isolated DB）：samples／external／price-quota 全零；snapshot 僅種子 triple。
        assertEquals(0L, count(db, "samples"))
        assertEquals(0L, count(db, "external_observations"))
        assertEquals(0L, count(db, "external_mappings"))
        assertEquals(0L, count(db, "price_snapshots"))
        assertEquals(0L, count(db, "quota_snapshots"))
        assertEquals(1L, count(db, "snapshot_facts"))
        assertEquals(4L, count(db, "provider_perf_facts"))

        // 匿名：三個 HTTP 皆無 Authorization；hours＝產品預設 168。
        val recorded = drain(3)
        assertEquals("/api/status", recorded[0].path)
        assertTrue(recorded[1].path!!.startsWith("/api/perf-metrics/summary"))
        assertTrue(recorded[1].path!!.contains("hours=168"))
        assertTrue(recorded[2].path!!.contains("model=m-perf-a"))
        assertTrue(recorded[2].path!!.contains("hours=168"))
        for (req in recorded) assertNull(req.getHeader("Authorization"))
        assertTrue(vault.asked.isEmpty())
    }

    // ===== 2. PAT provenance：ACCOUNT ref 發 PAT，INFERENCE ref 不碰；匿名不需 AuthMode =====

    @Test
    fun patProvenanceSendsPat_inferenceRefUntouched() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p2"
        val patRef = "pat-perf-p2"
        val keyRef = "key-perf-p2"
        seedProvider(db, provider, "a-p2", "e-p2", patRef, keyRef)
        seedCertifiedPrice(db, provider, "a-p2", "e-p2")
        val store = ProviderPerfStore(db)
        val patSecret = "loopback-pat-secret-p2-0123456789"
        val vault = CountingVault(mapOf(patRef to patSecret))
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())

        val result = store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)), provider, "m-perf-a")

        assertTrue(result.fetched)
        val recorded = drain(3)
        assertNull("status 無需憑證", recorded[0].getHeader("Authorization"))
        assertEquals("Bearer $patSecret", recorded[1].getHeader("Authorization"))
        assertEquals("Bearer $patSecret", recorded[2].getHeader("Authorization"))
        assertTrue("PAT ref 必須被取用", vault.asked.isNotEmpty())
        assertTrue("只取 PAT ref", vault.asked.all { it.startsWith("$patRef@") })
        assertTrue("INFERENCE ref 永不進 perf", vault.asked.none { it.startsWith("$keyRef@") })
        val fact = store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP).lastGood!!
        assertEquals("a-p2", fact.accountId)
        assertEquals(patRef, fact.credentialId)
        assertFalse(result.toString().contains(patSecret))
    }

    // ===== 3. raw ref：首次網路請求前 fail-closed，零請求 =====

    @Test
    fun rawCredentialRefFailsClosedBeforeAnyNetwork() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p3"
        seedProvider(db, provider, "a-p3", "e-p3", "sk-ab", null)
        seedCertifiedPrice(db, provider, "a-p3", "e-p3")
        val store = ProviderPerfStore(db)
        val baseline = server.requestCount
        try {
            store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(5_000L)), provider, "m-perf-a")
            throw AssertionError("raw ref must fail closed")
        } catch (e: IllegalArgumentException) {
            assertFalse((e.message ?: "").contains("sk-ab"))
            assertFalse((e.message ?: "").contains("http"))
        }
        assertEquals("拒絕前零網路請求", baseline, server.requestCount)
        assertEquals(0L, count(db, "provider_perf_facts"))
    }

    // ===== 4. scope 隔離：168／24、model、group、null 各自獨立 =====

    @Test
    fun hoursAndScopeStayIsolated() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p4"
        seedProvider(db, provider, "a-p4", "e-p4")
        seedCertifiedPrice(db, provider, "a-p4", "e-p4")
        val store = ProviderPerfStore(db)
        fun adapterAt(now: Long) = NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(now))

        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        val r168 = store.syncPerf(adapterAt(5_000L), provider, "m-perf-a", hours = 168)
        assertTrue(r168.fetched)
        drain(3)
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        val r24 = store.syncPerf(adapterAt(6_000L), provider, "m-perf-a", hours = 24)
        assertTrue(r24.fetched)
        drain(3)

        // 168 與 24 各自 head；同 model 不同 hours 不互借。
        assertEquals(5_000L, store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY).lastGood!!.observedAt)
        assertEquals(6_000L, store.observation(provider, "m-perf-a", null, 24, ProviderPerfKind.SUMMARY).lastGood!!.observedAt)
        assertEquals(5_000L, store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP).lastGood!!.observedAt)
        assertEquals(6_000L, store.observation(provider, "m-perf-a", "default", 24, ProviderPerfKind.GROUP).lastGood!!.observedAt)
        // null-group summary 與真 group 分頭；query 只問 m-perf-a，m-perf-b 無 GROUP head。
        assertNull(store.observation(provider, "m-perf-b", "default", 168, ProviderPerfKind.GROUP).latest)
        assertNotNull(store.observation(provider, "m-perf-b", null, 168, ProviderPerfKind.SUMMARY).lastGood)
    }

    // ===== 5. 同時間或較舊同步不得退 last-good，但 append 歷史 =====

    @Test
    fun olderSyncNeverRegressesLastGood() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p5"
        seedProvider(db, provider, "a-p5", "e-p5")
        seedCertifiedPrice(db, provider, "a-p5", "e-p5")
        val store = ProviderPerfStore(db)

        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        assertTrue(store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(3_000L)), provider, "m-perf-a").fetched)
        drain(3)
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        assertTrue(store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(1_000L)), provider, "m-perf-a").fetched)
        drain(3)

        val summary = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY)
        assertEquals(3_000L, summary.latest!!.observedAt)
        assertEquals(3_000L, summary.lastGood!!.observedAt)
        assertEquals(listOf(3_000L, 1_000L), store.history(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY).map { it.observedAt })
        val group = store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP)
        assertEquals(3_000L, group.latest!!.observedAt)
        assertEquals(3_000L, group.lastGood!!.observedAt)
    }

    // ===== 6. 雙腿失敗：last-good 不動、append 歷史、fetched false =====

    @Test
    fun doubleFailurePreservesLastGood() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p6"
        seedProvider(db, provider, "a-p6", "e-p6")
        seedCertifiedPrice(db, provider, "a-p6", "e-p6")
        val store = ProviderPerfStore(db)

        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        assertTrue(store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(1_000L)), provider, "m-perf-a").fetched)
        drain(3)
        enqueue(statusJson())
        enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"internal"}"""))
        enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"internal"}"""))
        val failed = store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(2_000L)), provider, "m-perf-a")
        drain(3)

        assertFalse(failed.fetched)
        assertTrue(failed.failed)
        assertTrue(failed.certified)
        // Summary 失敗標記落在 provider 級 scope；per-model 真列原樣保留。
        val summaryMarker = store.observation(provider, null, null, 168, ProviderPerfKind.SUMMARY)
        assertEquals(2_000L, summaryMarker.latest!!.observedAt)
        assertEquals("HTTP_SERVER_ERROR", summaryMarker.latest!!.status)
        assertEquals(500, summaryMarker.latest!!.httpStatus)
        assertNull(summaryMarker.lastGood)
        val summary = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY)
        assertEquals(1_000L, summary.latest!!.observedAt)
        assertEquals(1_000L, summary.lastGood!!.observedAt)
        assertEquals(1, store.history(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY).size)
        // Query 失敗標記落在 (model,null) scope；per-group 真列原樣保留。
        val groupMarker = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP)
        assertEquals("HTTP_SERVER_ERROR", groupMarker.latest!!.status)
        assertNull(groupMarker.lastGood)
        val group = store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP)
        assertEquals(1_000L, group.latest!!.observedAt)
        assertEquals(1_000L, group.lastGood!!.observedAt)
        assertEquals(1, store.history(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP).size)
    }

    // ===== 7. authoritative empty：不是錯誤、不推 last-good、fetched false =====

    @Test
    fun authoritativeEmptyPreservesLastGood() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p7"
        seedProvider(db, provider, "a-p7", "e-p7")
        seedCertifiedPrice(db, provider, "a-p7", "e-p7")
        val store = ProviderPerfStore(db)

        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        assertTrue(store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(1_000L)), provider, "m-perf-a").fetched)
        drain(3)
        enqueue(statusJson())
        enqueue("""{"success":true,"data":{"models":[]}}""")
        enqueue("""{"success":true,"data":{"model_name":"m-perf-a","series_schema":"dbcd0a3c01b55203","groups":[]}}""")
        val empty = store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(2_000L)), provider, "m-perf-a")
        drain(3)

        assertFalse(empty.fetched)
        assertFalse(empty.failed)
        // Summary 空標記落在 provider 級 scope；per-model 真列不受空窗影響。
        val summary = store.observation(provider, null, null, 168, ProviderPerfKind.SUMMARY)
        assertTrue(summary.latest!!.empty)
        assertEquals("OK", summary.latest!!.status)
        assertNull("空標記不推 last-good", summary.lastGood)
        val perModel = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY)
        assertEquals("舊 good 列不受空窗影響", 1_000L, perModel.lastGood!!.observedAt)
        assertEquals(1_000L, perModel.latest!!.observedAt)
        // Query 空標記落在 (model,null) scope；per-group 真列原樣保留。
        val groupEmpty = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP)
        assertTrue(groupEmpty.latest!!.empty)
        assertNull(groupEmpty.lastGood)
        val groupGood = store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP)
        assertEquals(1_000L, groupGood.lastGood!!.observedAt)
    }

    // ===== 8. 超範圍 summary 整面 MALFORMED；query 腿照跑 good =====

    @Test
    fun outOfRangeSummaryIsWholeSurfaceMalformed() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p8"
        seedProvider(db, provider, "a-p8", "e-p8")
        seedCertifiedPrice(db, provider, "a-p8", "e-p8")
        val store = ProviderPerfStore(db)
        enqueue(statusJson())
        enqueue("""{"success":true,"data":{"models":[{ "model_name":"m-perf-a","avg_latency_ms":120,"success_rate":150.0,"avg_tps":12.5},{"model_name":"m-perf-b","avg_latency_ms":10,"success_rate":50.0,"avg_tps":1.0}]}}""")
        enqueue(queryJson())

        val result = store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(5_000L)), provider, "m-perf-a")
        drain(3)

        assertEquals("一列超範圍即整面只有標記", 1, result.summaryFacts)
        assertEquals(2, result.queryFacts)
        assertTrue("query 腿 good 即 fetched", result.fetched)
        assertTrue("invalid summary is reported without discarding good query", result.failed)
        val marker = store.observation(provider, null, null, 168, ProviderPerfKind.SUMMARY)
        assertEquals("MALFORMED", marker.latest!!.status)
        assertNull(marker.lastGood)
        assertNull("有效列也不得殘留 good", store.observation(provider, "m-perf-b", null, 168, ProviderPerfKind.SUMMARY).latest)
        assertNotNull(store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP).lastGood)
    }

    // ===== 9. 負值 group 指標整面 MALFORMED =====

    @Test
    fun negativeGroupMetricIsWholeSurfaceMalformed() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p9"
        seedProvider(db, provider, "a-p9", "e-p9")
        seedCertifiedPrice(db, provider, "a-p9", "e-p9")
        val store = ProviderPerfStore(db)
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue("""{"success":true,"data":{"model_name":"m-perf-a","series_schema":"dbcd0a3c01b55203","groups":[{ "group":"default","avg_ttft_ms":10,"avg_latency_ms":100,"success_rate":-1.0,"avg_tps":20.0,"series":[]}]}}""")

        val result = store.syncPerf(NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(5_000L)), provider, "m-perf-a")
        drain(3)

        assertEquals(1, result.queryFacts)
        assertTrue("summary 腿 good 即 fetched", result.fetched)
        val marker = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP)
        assertEquals("MALFORMED", marker.latest!!.status)
        assertNull(marker.lastGood)
        assertNull(store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP).latest)
    }

    // ===== 10. exact request identity：錯 model echo／錯 group 不得持久化 good =====

    @Test
    fun exactRequestIdentityEnforced() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p10"
        seedProvider(db, provider, "a-p10", "e-p10")
        seedCertifiedPrice(db, provider, "a-p10", "e-p10")
        val store = ProviderPerfStore(db)
        fun adapterAt(now: Long) = NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(now))

        // Phase A：指定 group=vip，回 vip 即 good。
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(vipOnlyQueryJson("m-perf-a"))
        val good = store.syncPerf(adapterAt(5_000L), provider, "m-perf-a", group = "vip")
        drain(3)
        assertTrue(good.fetched)
        assertNotNull(store.observation(provider, "m-perf-a", "vip", 168, ProviderPerfKind.GROUP).lastGood)

        // Phase B：同樣要 vip，回 default 即整面 MALFORMED，last-good 不動。
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        store.syncPerf(adapterAt(6_000L), provider, "m-perf-a", group = "vip")
        drain(3)
        val after = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP)
        assertEquals("MALFORMED", after.latest!!.status)
        assertNull(after.lastGood)
        assertEquals("vip 真列不受錯 group 面影響", 5_000L, store.observation(provider, "m-perf-a", "vip", 168, ProviderPerfKind.GROUP).lastGood!!.observedAt)

        // Phase C：回別人 model 名，整面 MALFORMED；別人不得有 good。
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson().replace("\"m-perf-a\"", "\"intruder-model\""))
        store.syncPerf(adapterAt(7_000L), provider, "m-perf-a")
        drain(3)
        val wrong = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP)
        assertEquals("MALFORMED", wrong.latest!!.status)
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM provider_perf_facts WHERE modelId = ? AND status = 'OK'",
            arrayOf("intruder-model"),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("別人的 model 不得持久化 good", 0L, it.getLong(0))
        }
    }

    // ===== 11. 分歧重複整面 MALFORMED（不靜默改歷史） =====

    @Test
    fun divergentDuplicatesAreWholeSurfaceMalformed() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p11"
        seedProvider(db, provider, "a-p11", "e-p11")
        seedCertifiedPrice(db, provider, "a-p11", "e-p11")
        val store = ProviderPerfStore(db)
        fun adapterAt(now: Long) = NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(now))

        // Phase A：summary 同 model 分歧值 → SUMMARY 標記；query 腿照跑。
        enqueue(statusJson())
        enqueue("""{"success":true,"data":{"models":[{ "model_name":"m-perf-a","avg_latency_ms":120,"success_rate":90.0,"avg_tps":12.5},{"model_name":"m-perf-a","avg_latency_ms":130,"success_rate":91.0,"avg_tps":12.5}]}}""")
        enqueue(queryJson())
        val dupSummary = store.syncPerf(adapterAt(5_000L), provider, "m-perf-a")
        drain(3)
        assertEquals(1, dupSummary.summaryFacts)
        assertEquals("MALFORMED", store.observation(provider, null, null, 168, ProviderPerfKind.SUMMARY).latest!!.status)
        assertNotNull(store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP).lastGood)

        // Phase B：query 同 group 分歧值 → GROUP 標記；summary 腿照跑。
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue("""{"success":true,"data":{"model_name":"m-perf-a","series_schema":"dbcd0a3c01b55203","groups":[{ "group":"default","avg_ttft_ms":10,"avg_latency_ms":100,"success_rate":99.0,"avg_tps":20.0,"series":[]},{ "group":"default","avg_ttft_ms":11,"avg_latency_ms":101,"success_rate":98.0,"avg_tps":21.0,"series":[]}]}}""")
        val dupQuery = store.syncPerf(adapterAt(6_000L), provider, "m-perf-a")
        drain(3)
        assertEquals(1, dupQuery.queryFacts)
        assertEquals("MALFORMED", store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP).latest!!.status)
        assertNotNull(store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY).lastGood)
    }

    // ===== 12. version gate：無 stored cert 零請求零寫入；新鮮漂移只記 gate 標記 =====

    @Test
    fun versionGateBlocksUncertified_andDriftWritesGateMarkers() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-p12"
        seedProvider(db, provider, "a-p12", "e-p12")
        val store = ProviderPerfStore(db)
        val adapter = NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(5_000L))

        // Phase A：無任何官方證據 → 零請求、零寫入。
        val blocked = store.syncPerf(adapter, provider, "m-perf-a")
        assertFalse(blocked.certified)
        assertFalse(blocked.fetched)
        assertEquals(0, blocked.summaryFacts)
        assertEquals(0, blocked.queryFacts)
        assertEquals(0, server.requestCount)
        assertEquals(0L, count(db, "provider_perf_facts"))
        assertEquals(0L, count(db, "evidence"))

        // Phase B：stored cert 通過，但新鮮 /api/status 漂移 → 只記 gate 標記。
        seedCertifiedPrice(db, provider, "a-p12", "e-p12")
        enqueue("""{"success":true,"data":{"version":"v9.0.0","quota_per_unit":500000}}""")
        val drifted = store.syncPerf(adapter, provider, "m-perf-a")
        assertFalse(drifted.certified)
        assertFalse(drifted.fetched)
        assertEquals("只有 1 個 status 請求", 1, server.requestCount)
        val summaryGate = store.observation(provider, null, null, 168, ProviderPerfKind.SUMMARY)
        assertEquals("UNKNOWN_VERSION", summaryGate.latest!!.status)
        assertEquals("gate 標記沿用 caps 真實 source", "newapi/api/status", db.evidenceDao().getById(summaryGate.latest!!.evidenceId)!!.source)
        assertNull(summaryGate.lastGood)
        val groupGate = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP)
        assertEquals("UNKNOWN_VERSION", groupGate.latest!!.status)
        assertNull(groupGate.lastGood)
    }

    @Test
    fun otherModelSummaryCannotClaimRequestedModelEnrichment() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-other-model"
        seedProvider(db, provider, "a-other-model", "e-other-model")
        seedCertifiedPrice(db, provider, "a-other-model", "e-other-model")
        val store = ProviderPerfStore(db)
        enqueue(statusJson())
        enqueue("""{"success":true,"data":{"models":[{"model_name":"other-model","avg_latency_ms":10,"success_rate":100,"avg_tps":1}]}}""")
        enqueue("""{"success":true,"data":{"model_name":"m-perf-a","series_schema":"dbcd0a3c01b55203","groups":[]}}""")
        val result = store.syncPerf(
            NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(5_000L)), provider, "m-perf-a",
        )
        drain(3)
        assertFalse("a different model's summary cannot satisfy the requested model", result.fetched)
        assertFalse(result.failed)
        assertNotNull("background provider summary is still retained",
            store.observation(provider, "other-model", null, 168, ProviderPerfKind.SUMMARY).lastGood)
        assertNull(store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY).lastGood)
        assertNull(store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP).lastGood)
    }

    @Test
    fun nonFiniteAndNegativeMetricsNeverReplaceLastGood() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-nonfinite"
        seedProvider(db, provider, "a-nonfinite", "e-nonfinite")
        seedCertifiedPrice(db, provider, "a-nonfinite", "e-nonfinite")
        val store = ProviderPerfStore(db)
        fun adapterAt(now: Long) = NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(now))
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        assertTrue(store.syncPerf(adapterAt(5_000L), provider, "m-perf-a").fetched)
        drain(3)
        val original = store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY).lastGood!!
        // JSON exponent overflow is well-formed JSON, but cannot be an honest finite metric.
        val invalidModels = listOf(
            """{"model_name":"m-perf-a","avg_latency_ms":1,"success_rate":99,"avg_tps":1e999}""",
            """{"model_name":"m-perf-a","avg_latency_ms":-1,"success_rate":99,"avg_tps":1}""",
            """{"model_name":"m-perf-a","avg_latency_ms":1,"success_rate":99,"avg_tps":-1}""",
        )
        for ((index, row) in invalidModels.withIndex()) {
            enqueue(statusJson())
            enqueue("""{"success":true,"data":{"models":[$row]}}""")
            enqueue(queryJson())
            val partial = store.syncPerf(adapterAt(6_000L + index), provider, "m-perf-a")
            drain(3)
            assertTrue(partial.fetched)
            assertTrue(partial.failed)
            assertEquals(original.evidenceId,
                store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId)
            assertEquals("MALFORMED",
                store.observation(provider, null, null, 168, ProviderPerfKind.SUMMARY).latest!!.status)
        }
        val groupGood = store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP).lastGood!!
        enqueue(statusJson())
        enqueue("""{"success":true,"data":{"models":[]}}""")
        enqueue(queryJson().replace("\"avg_tps\":19.5", "\"avg_tps\":1e999"))
        val invalidPoint = store.syncPerf(adapterAt(9_000L), provider, "m-perf-a")
        drain(3)
        assertFalse(invalidPoint.fetched)
        assertTrue(invalidPoint.failed)
        assertEquals(groupGood.evidenceId,
            store.observation(provider, "m-perf-a", "default", 168, ProviderPerfKind.GROUP).lastGood!!.evidenceId)
        assertEquals("MALFORMED",
            store.observation(provider, "m-perf-a", null, 168, ProviderPerfKind.GROUP).latest!!.status)
    }

    @Test
    fun mismatchedPersistedCertificateIsRejectedBeforeNetwork() = runBlocking {
        // Controlled DB corruption fixtures; production never mutates these immutable rows.
        val corruptions = listOf(
            "UPDATE evidence SET providerId = 'foreign-provider' WHERE id = 'cert-ev-1'",
            "UPDATE evidence SET source = 'external/source' WHERE id = 'cert-ev-1'",
            "UPDATE evidence SET outcome = 'UNKNOWN' WHERE id = 'cert-ev-1'",
            "UPDATE evidence SET timestamp = timestamp + 1 WHERE id = 'cert-ev-1'",
            "UPDATE snapshot_facts SET kind = 'TOKEN_QUOTA' WHERE evidenceId = 'cert-ev-1'",
            "UPDATE snapshot_facts SET scopeKey = 'foreign-scope' WHERE evidenceId = 'cert-ev-1'",
        )
        for ((index, mutation) in corruptions.withIndex()) {
            val db = memoryDb()
            val provider = "perf-store-corrupt-$index"
            seedProvider(db, provider, "a-corrupt-$index", "e-corrupt-$index")
            seedCertifiedPrice(db, provider, "a-corrupt-$index", "e-corrupt-$index")
            db.openHelper.writableDatabase.execSQL(mutation)
            val blocked = ProviderPerfStore(db).syncPerf(
                NewApiAdapter(CountingVault(emptyMap()), rewriteClient(), FakeClock(5_000L)), provider, "m-perf-a",
            )
            assertFalse(blocked.certified)
            assertFalse(blocked.fetched)
            assertFalse(blocked.failed)
            assertEquals(0L, count(db, "provider_perf_facts"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun foreignAccountPatIsRejectedBeforeNetworkOrVaultResolution() = runBlocking {
        val db = memoryDb()
        val provider = "perf-store-foreignpat"
        val pat = "safe-pat-foreign"
        seedProvider(db, provider, "a-foreignpat", "e-foreignpat", pat)
        seedCertifiedPrice(db, provider, "a-foreignpat", "e-foreignpat")
        db.providerDao().insert(ProviderEntity("other-provider", "Other"))
        db.accountDao().insert(AccountEntity("other-account", "other-provider"))
        db.openHelper.writableDatabase.execSQL("UPDATE credentials SET accountId = 'other-account' WHERE credentialId = ?", arrayOf(pat))
        val vault = CountingVault(emptyMap())
        try {
            ProviderPerfStore(db).syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)), provider, "m-perf-a")
            throw AssertionError("foreign PAT must be rejected")
        } catch (e: IllegalArgumentException) {
            assertEquals("Cross-provider refs rejected", e.message)
        }
        assertEquals(0, server.requestCount)
        assertTrue(vault.asked.isEmpty())
        assertEquals(0L, count(db, "provider_perf_facts"))
    }

    // ===== harness（fixture 僅本檔，不當產品資料；secret 全 fake 只走 loopback） =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries().build().also { open += it }

    private suspend fun seedProvider(
        db: AtlasDatabase,
        provider: String,
        account: String,
        endpoint: String,
        patRef: String? = null,
        keyRef: String? = null,
    ) {
        db.providerDao().insert(ProviderEntity(provider, "P $provider"))
        db.accountDao().insert(AccountEntity(account, provider))
        db.endpointDao().insert(
            EndpointEntity(endpoint, provider, "https://$provider.example.com/v1", "https://$provider.example.com/v1/chat/completions"),
        )
        if (patRef != null) {
            db.credentialDao().insert(CredentialEntity(patRef, account))
            val refs = mutableListOf(SafeCredentialRef(patRef, "PAT", CredentialRole.ACCOUNT))
            if (keyRef != null) {
                db.credentialDao().insert(CredentialEntity(keyRef, account))
                refs += SafeCredentialRef(keyRef, "KEY", CredentialRole.INFERENCE)
            }
            db.providerConfigDao().insert(
                ProviderManualConfig(provider, "wf-$provider-perf", AuthMode.BEARER, credentials = refs).toEntity(),
            )
        }
    }

    /**
     * Stored cert 前置：官方 PRICE last-good triple（kind／scopeKey／head 一致、
     * `OK`＋HTTP 200＋官方 source＋evidence providerId／timestamp 一致＋rc.25）。
     * 測試夾具明示，非 deployment verified 宣告。
     */
    private suspend fun seedCertifiedPrice(
        db: AtlasDatabase,
        provider: String,
        account: String,
        endpoint: String,
        time: Long = 1_000L,
    ) {
        val scope = PricingScope(provider, account, null, "cert-model", "default", endpoint, "CHAT_COMPLETIONS")
        val key = scope.storageKey()
        db.evidenceDao().insert(
            EvidenceEntity("cert-ev-1", "cert-ev-1", provider, "cert-model", "OK", 200, time, NewApiSnapshotStore.PRICE_SOURCE),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = "cert-ev-1",
                scopeKey = key,
                kind = SnapshotKind.PRICE.name,
                observedAt = time,
                snapshotId = "cert-ev-1",
                state = "PAID",
                status = "OK",
                confidence = "OFFICIAL",
                providerVersion = NewApiVersion.RC25,
                pricingVersion = "catalog-v1",
                itemPricingVersion = null,
                quotaPerUnit = null,
                groupRatio = null,
                statusObservedAt = time - 1,
                remainingRaw = null,
                usedRaw = null,
                grantedRaw = null,
                unlimited = null,
                expiresAtRaw = null,
                expiryMeaning = null,
                expressionHash = null,
                priceUnit = "USD_PER_1K_TOKENS",
            ),
        )
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity(key, SnapshotKind.PRICE.name, "cert-ev-1", time, "cert-ev-1", time))
    }

    private fun rewriteClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(RewriteToMock(server)).build()

    private fun enqueue(body: String) = enqueue(MockResponse().setResponseCode(200).setBody(body))

    private fun enqueue(response: MockResponse) = server.enqueue(response)

    private fun statusJson(): String =
        """{"success":true,"data":{"version":"v1.0.0-rc.25","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

    private fun summaryJson(): String =
        """{"success":true,"data":{"models":[{"model_name":"m-perf-a","avg_latency_ms":120,"success_rate":98.37,"avg_tps":12.5,"recent_success_rates":[97.5,98.0,98.37],"request_count":999},{"model_name":"m-perf-b","avg_latency_ms":0,"success_rate":100.0,"avg_tps":0.0,"recent_success_rates":[]}]}}"""

    private fun queryJson(): String =
        """{"success":true,"data":{"model_name":"m-perf-a","series_schema":"dbcd0a3c01b55203","groups":[{"group":"default","avg_ttft_ms":10,"avg_latency_ms":100,"success_rate":99.0,"avg_tps":20.0,"series":[{"ts":1700000060,"avg_ttft_ms":11,"avg_latency_ms":101,"success_rate":98.5,"avg_tps":19.5},{"ts":1700000000,"avg_ttft_ms":10,"avg_latency_ms":100,"success_rate":99.0,"avg_tps":20.0}]},{"group":"vip","avg_ttft_ms":0,"avg_latency_ms":50,"success_rate":100.0,"avg_tps":30.0,"series":[]}]}}"""

    private fun vipOnlyQueryJson(model: String): String =
        """{"success":true,"data":{"model_name":"$model","series_schema":"dbcd0a3c01b55203","groups":[{"group":"vip","avg_ttft_ms":5,"avg_latency_ms":60,"success_rate":97.0,"avg_tps":25.0,"series":[]}]}}"""

    private fun drain(expected: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(expected)
        repeat(expected) {
            out += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request #$it (expected $expected)" }
        }
        return out
    }

    private fun count(db: AtlasDatabase, table: String): Long {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst())
            return it.getLong(0)
        }
    }

    private class FakeClock(var now: Long) : Clock {
        override fun epochMillis(): Long = now
        override fun monotonicMillis(): Long = now
    }

    private class CountingVault(secrets: Map<String, String>) : CredentialAccess {
        private val backing = secrets.toMap()
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            return backing[credentialId] ?: throw IllegalStateException("vault-refused")
        }
    }

    /**
     * 測試傳輸接縫：把已通過生產驗證的 `https` request 改寫為 loopback MockWebServer
     * 真 socket（scheme／host／port 替換；path＋query＋Authorization 原樣保留）。
     * DB 不存 loopback、不存 secret。
     */
    private class RewriteToMock(private val server: MockWebServer) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val target = orig.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            return chain.proceed(orig.newBuilder().url(target).build())
        }
    }
}
