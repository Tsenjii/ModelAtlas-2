package dev.modelatlas.app.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.HealthRollup
import dev.modelatlas.app.data.HealthRollupStore
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.core.domain.HealthState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-001 device test（已落地 repo；實跑與 acceptance 由同 SHA CI 證明）。
 * 基線：full-green `3dc9306`／`37248072464`（device691/core576/appJVM292 全 0
 * fail/error/skip，Room14 byte-identical）；本檔隨 FR001 slice 首次進 CI。
 *
 * TRACEABILITY：FR-001（CORRECTNESS_REPAIR_AUDIT「FR-001 local CANCELLED denominator
 * — CONFIRMED」：`SampleDao.healthAggregates` 的 `decided`/AVG 只排 RUNNING，
 * CANCELLED 汙染 ProviderDetail local success/health，9 SUCCESS＋1 CANCEL→90%/DEGRADED）；
 * Contract §4／D-009：user cancel 不得計入本地 success/health 分母。既有正確先例：
 * `myUptimeSamples` SQL 已 `AND outcome != 'CANCELLED'`（Daos.kt）＋
 * `MyUptime.kt` list 輸入再濾一次——本測試把同一契約延伸到 ProviderDetail 聚合。
 *
 * **本 slice 已實作的 SQL 變更**（與本檔同 commit 落地）：`Daos.kt`
 * `SampleDao.healthAggregates` 的 `decided` 與三個 AVG leg 在 RUNNING 之外
 * 再排除 CANCELLED（total 不變（all-attempts）；rateLimited 與 decided 同基（含 CANCELLED 排除））：
 * ```
 * SUM(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' THEN 1 ELSE 0 END) AS decided,
 * AVG(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' THEN ttftMs END) AS avgTtftMs,
 * AVG(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' THEN tps END) AS avgTps,
 * AVG(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' THEN totalLatencyMs END) AS avgLatencyMs,
 * ```
 * 未落地前，標 [PROPOSED-EXPECTATION] 的斷言**設計上在 current HEAD 為 RED**
 * （9 SUCCESS＋1 CANCEL 實際為 decided=10 → 0.9 → "90%"/DEGRADED）；不得以放寬
 * expectation／刪測試換綠。標 [FIXED-SEMANTICS] 的斷言 pre/post-fix 都必須綠
 * （NETWORK 是真 failure，仍降級；formatter 既有 sub-1.0 cap 不變）。
 *
 * 範圍：ProviderDetailLoader 層級（真 Room＋真 loader 端到端），無 UI widget、
 * 無 createComposeRule。production 鏈：
 * `healthAggregates`（Daos.kt）→ `ProviderDetailLoader.performanceOf(rows, windowRows)`
 * （ProviderDetailLoader.kt）→ `successRatePercentLabel` → `HealthRollup.observedHealth`
 * （HealthRollup.kt）→ `HealthRollupStore.refreshObserved` 寫回 providers.observedHealth →
 * `ProviderDetailLoader.loadDetail` 讀回 → `ProviderDetailState.header.observedHealth`
 * （Overview「local health」欄）與 `ProviderDetailPerformance.success24h`
 * （Performance tab percent 欄）。fixture 只在本 test sourceSet，不作產品資料。
 */
@RunWith(AndroidJUnit4::class)
class Fr001UiPercentTest {

    private lateinit var db: AtlasDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- FR-001 主案例：9 SUCCESS + 1 CANCELLED → 100% HEALTHY（非 90% DEGRADED） ----

    @Test
    fun cancelExcludedFromLocalSuccessPercentAndDerivedHealth() = runBlocking {
        val now = 10_000_000_000L
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
        repeat(9) { i ->
            db.sampleDao().upsert(sample("s$i", now - 3_600_000L + i, "SUCCESS", ttftMs = 100L))
        }
        // 真實 cancel 形態（InferenceEngine finally）：completedAt 已判定、tps null。
        db.sampleDao().upsert(sample("cancel", now - 1_800_000L, "CANCELLED", ttftMs = 10_000L))

        // 直接 rows：照 ProviderDetailLoader.loadDetail 的實際呼叫形狀
        // （all-time `healthAggregates(providerId, 0L)`；window `healthAggregates(providerId, nowMillis - 24h)`）。
        val windowStart = now - ProviderDetailLoader.PERFORMANCE_WINDOW_MILLIS
        val allTimeRows = db.sampleDao().healthAggregates("p1", 0L)
        val windowRows = db.sampleDao().healthAggregates("p1", windowStart)

        // PROPOSED-EXPECTATION（FR001 SQL 未落地前 RED：actual decided=10）：
        // CANCELLED 退出 decided → 9 decided / 9 successes。
        assertEquals(9, allTimeRows.single().decided)
        assertEquals(9, allTimeRows.single().successes)
        // PROPOSED-EXPECTATION：AVG leg 同步排除 CANCELLED（cancel 的 ttftMs=10s 不污染均值）。
        assertEquals(100.0, allTimeRows.single().avgTtftMs!!, 1e-9)

        // FIXED-SEMANTICS：total/requestCount 維持 all-attempts（含已開始後取消的 attempt），
        // FR001 不縮 requestCount 語意。
        assertEquals(10, allTimeRows.single().total)

        // performanceOf 雙 rows 參數呼叫形狀（CR-005 C3 契約不變）。
        val perf = ProviderDetailLoader.performanceOf(allTimeRows, windowRows)
        // PROPOSED-EXPECTATION（未落地前 RED：actual 0.9）。
        assertEquals(1.0, perf!!.success24h!!, 1e-9)
        // FIXED-SEMANTICS：formatter 既有契約（精確 1.0 → "100%"；sub-1.0 cap 99 不變）。
        assertEquals("100%", ProviderDetailLoader.successRatePercentLabel(perf.success24h))
        assertEquals(10L, perf.requestCount!!)

        // derived health：HealthRollup 總和門檻（>=0.95 且無 429 → HEALTHY）。
        // PROPOSED-EXPECTATION（未落地前 RED：actual DEGRADED）。
        assertEquals(HealthState.HEALTHY, HealthRollup.observedHealth(allTimeRows))

        // 端到端 ProviderDetailState mapping：refreshObserved 寫回 → loadDetail 讀回。
        HealthRollupStore(db).refreshObserved("p1", now)
        val detail = ProviderDetailLoader.loadDetail(db, "p1", nowMillis = now)
        // PROPOSED-EXPECTATION（未落地前 RED：actual "DEGRADED"）。
        assertEquals(HealthState.HEALTHY.name, detail.header.observedHealth)
    }

    // ---- FR-001 反案例：真 NETWORK failure 仍降級（cancel 排除不得過頭） ----

    @Test
    fun genuineNetworkFailureStillDegradesAfterCancelExclusion() = runBlocking {
        val now = 10_000_000_000L
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
        repeat(9) { i ->
            db.sampleDao().upsert(sample("s$i", now - 3_600_000L + i, "SUCCESS", ttftMs = 100L))
        }
        // 真 failure：completedAt 已判定、httpStatus 503（非 429）。
        db.sampleDao().upsert(sample("net", now - 1_800_000L, "NETWORK", ttftMs = 10_000L, httpStatus = 503))

        val allTimeRows = db.sampleDao().healthAggregates("p1", 0L)
        val windowRows = db.sampleDao().healthAggregates("p1", now - ProviderDetailLoader.PERFORMANCE_WINDOW_MILLIS)

        // FIXED-SEMANTICS：NETWORK 是真 decided failure，pre/post-fix 都必須 10 decided / 9 successes；
        // FR001 的排除範圍只有 CANCELLED，不得把真 failure 一併排除。
        // total＝種子數 9 SUCCESS＋1 NETWORK＝10（本檔僅種 10 筆；首輪 CI 抓出 11 算術誤）。
        assertEquals(10, allTimeRows.single().decided)
        assertEquals(9, allTimeRows.single().successes)
        assertEquals(0, allTimeRows.single().rateLimited)
        assertEquals(10, allTimeRows.single().total)

        val perf = ProviderDetailLoader.performanceOf(allTimeRows, windowRows)
        assertEquals(0.9, perf!!.success24h!!, 1e-9)
        assertEquals("90%", ProviderDetailLoader.successRatePercentLabel(perf.success24h))
        assertEquals(HealthState.DEGRADED, HealthRollup.observedHealth(allTimeRows))

        HealthRollupStore(db).refreshObserved("p1", now)
        val detail = ProviderDetailLoader.loadDetail(db, "p1", nowMillis = now)
        assertEquals(HealthState.DEGRADED.name, detail.header.observedHealth)
    }

    // ---- Unknown != 0：純 cancel 窗 → decided<=0 → 未知（非 0%、非 OFFLINE） ----

    @Test
    fun pureCancelWindowYieldsUnknownNotZeroPercent() = runBlocking {
        val now = 10_000_000_000L
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
        db.sampleDao().upsert(sample("cancel", now - 1_800_000L, "CANCELLED"))

        val allTimeRows = db.sampleDao().healthAggregates("p1", 0L)
        val windowRows = db.sampleDao().healthAggregates("p1", now - ProviderDetailLoader.PERFORMANCE_WINDOW_MILLIS)

        // PROPOSED-EXPECTATION（未落地前 RED：actual decided=1）：
        // CANCELLED 全退出 → decided=0。
        assertEquals(0, allTimeRows.single().decided)
        // PROPOSED-EXPECTATION：decided<=0 → performanceOf null（不補 0，Unknown != 0）。
        assertNull(ProviderDetailLoader.performanceOf(allTimeRows, windowRows))
        // FIXED-SEMANTICS：null ratio → null label（畫面顯示未知，不渲染數字）。
        assertNull(ProviderDetailLoader.successRatePercentLabel(null))
        // PROPOSED-EXPECTATION（未落地前 RED：actual OFFLINE）。
        assertEquals(HealthState.UNKNOWN, HealthRollup.observedHealth(allTimeRows))

        // refreshObserved 對 UNKNOWN 不寫回（保留 last-good）；loadDetail 讀 null → UNKNOWN。
        HealthRollupStore(db).refreshObserved("p1", now)
        val detail = ProviderDetailLoader.loadDetail(db, "p1", nowMillis = now)
        // PROPOSED-EXPECTATION（未落地前 RED：actual "OFFLINE"）。
        assertEquals(HealthState.UNKNOWN.name, detail.header.observedHealth)
    }

    // ---- fixtures（只在本檔；形態照 ProviderDetailCorrectnessFlowTest） ----

    private fun sample(
        id: String,
        startedAt: Long,
        outcome: String,
        ttftMs: Long? = null,
        httpStatus: Int? = null,
    ) = SampleEntity(
        id = id,
        providerId = "p1",
        accountId = "a1",
        safeCredentialId = "c1",
        endpointId = "e1",
        providerModelId = "m1",
        requestStartedAt = startedAt,
        completedAt = startedAt,
        ttftMs = ttftMs,
        httpStatus = httpStatus,
        outcome = outcome,
    )
}
