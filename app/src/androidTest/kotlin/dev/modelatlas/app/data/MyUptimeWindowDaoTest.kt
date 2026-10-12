package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MYM-005 My Uptime device DAO 測試（Contract §23.4–23.5；D-009）：真 Room（in-memory）
 * 驗證 [SampleDao.myUptimeSamples] windowed query：24h/7d/30d/All 切窗、半開邊界
 * （落 start 計／落 end 不計）、SQL 層 CANCELLED 排除／INTERRUPTED 保留、scope 六維
 * 隔離，以及 [MyUptimePreset.DEFAULT_7D]／[MyUptimeAggregator.ROLLING_WINDOW_MILLIS]
 * 常數與 rolling-60s（`current*`）對長窗平均（`observed*`）命名區分。
 * fixture 只存本檔；無 sleep；時鐘由測試注入，不讀系統時鐘。
 */
@RunWith(AndroidJUnit4::class)
class MyUptimeWindowDaoTest {
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

    private val scope = MyUptimeScope(
        providerId = "p1",
        accountId = "a1",
        safeCredentialId = "c1",
        modelId = "m1",
        endpointId = "e1",
    )

    private suspend fun insert(
        id: String,
        at: Long,
        outcome: String = "SUCCESS",
        group: String? = null,
        providerId: String = "p1",
        accountId: String = "a1",
        credentialId: String = "c1",
        modelId: String = "m1",
        endpointId: String = "e1",
        totalTokens: Long? = null,
    ) {
        db.sampleDao().upsert(
            SampleEntity(
                id = id,
                providerId = providerId,
                accountId = accountId,
                safeCredentialId = credentialId,
                endpointId = endpointId,
                providerModelId = modelId,
                group = group,
                requestStartedAt = at,
                outcome = outcome,
                totalTokens = totalTokens,
            ),
        )
    }

    private suspend fun queryIds(
        startInclusive: Long,
        endExclusive: Long,
        scope: MyUptimeScope = this.scope,
    ): List<String> = db.sampleDao().myUptimeSamples(
        providerId = scope.providerId,
        accountId = scope.accountId,
        safeCredentialId = scope.safeCredentialId,
        modelId = scope.modelId,
        endpointId = scope.endpointId,
        group = scope.group,
        startInclusive = startInclusive,
        endExclusive = endExclusive,
    ).map { it.id }

    @Test
    fun windowSlices24h7d30dAll() = runBlocking {
        val now = 10_000_000_000L
        val day = 86_400_000L
        insert("in1h", now - 30 * 60_000L)
        insert("in24h", now - 10 * 3_600_000L)
        insert("in7d", now - 5 * day)
        insert("in30d", now - 20 * day)
        insert("inAll", now - 40 * day)
        insert("zeroTs", 0L)
        insert("atNowTs", now)

        val w24 = MyUptimePreset.LAST_24H.windowFor(now)
        assertEquals(listOf("in24h", "in1h"), queryIds(w24.startInclusive, w24.endExclusive))
        // 7d 是 D-009 預設（DEFAULT_7D）；切窗只含 7d 內樣本。
        val w7 = MyUptimePreset.DEFAULT_7D.windowFor(now)
        assertEquals(listOf("in7d", "in24h", "in1h"), queryIds(w7.startInclusive, w7.endExclusive))
        val w30 = MyUptimePreset.LAST_30D.windowFor(now)
        assertEquals(listOf("in30d", "in7d", "in24h", "in1h"), queryIds(w30.startInclusive, w30.endExclusive))
        // ALL = [0, now)：落 start（0）計、落 end（now）不計。
        val wAll = MyUptimePreset.ALL.windowFor(now)
        assertEquals(
            listOf("zeroTs", "inAll", "in30d", "in7d", "in24h", "in1h"),
            queryIds(wAll.startInclusive, wAll.endExclusive),
        )
    }

    @Test
    fun halfOpenBoundaryStartInclusiveEndExclusive() = runBlocking {
        val now = 10_000_000_000L
        // 亂序插入，同時鎖 ORDER BY startedAt ASC。
        insert("atEnd", 2_000L)
        insert("beforeStart", 999L)
        insert("inside", 1_500L)
        insert("atStart", 1_000L)
        insert("afterEnd", 2_500L)
        // 半開窗 [1000, 2000)：落 start 計、落 end 不計。
        assertEquals(listOf("atStart", "inside"), queryIds(1_000L, 2_000L))

        insert("presetStart", now - 86_400_000L)
        insert("presetEnd", now)
        val window = MyUptimePreset.LAST_24H.windowFor(now)
        // preset 窗同守：落 windowFor(now) 的 start 計、落 now（end）不計。
        assertEquals(listOf("presetStart"), queryIds(window.startInclusive, window.endExclusive))
    }

    @Test
    fun sqlLayerExcludesCancelledKeepsInterrupted() = runBlocking {
        insert("cancelled", 2_000L, outcome = "CANCELLED")
        insert("interrupted", 3_000L, outcome = "INTERRUPTED")
        insert("success", 4_000L, outcome = "SUCCESS")
        insert("running", 5_000L, outcome = "RUNNING")
        insert("server", 6_000L, outcome = "SERVER")
        val ids = queryIds(1_000L, 10_000L)
        // SQL 層即排除 CANCELLED（User Stop 不進列）；INTERRUPTED 是真實歷史，保留。
        assertFalse(ids.contains("cancelled"))
        assertTrue(ids.contains("interrupted"))
        assertEquals(listOf("interrupted", "success", "running", "server"), ids)
    }

    @Test
    fun defaultPresetConstantIs7d() {
        assertEquals(MyUptimePreset.LAST_7D, MyUptimePreset.DEFAULT_7D)
        assertEquals(604_800_000L, MyUptimePreset.DEFAULT_7D.windowMillis)
        assertEquals(3_600_000L, MyUptimePreset.LAST_1H.windowMillis)
        assertEquals(86_400_000L, MyUptimePreset.LAST_24H.windowMillis)
        assertEquals(2_592_000_000L, MyUptimePreset.LAST_30D.windowMillis)
        assertEquals(0L, MyUptimePreset.ALL.windowMillis)
        assertNull(MyUptimePreset.CUSTOM.windowMillis)
        val now = 9_000_000_000L
        val window = MyUptimePreset.DEFAULT_7D.windowFor(now)
        assertEquals(now - 604_800_000L, window.startInclusive)
        assertEquals(now, window.endExclusive)
        val custom = MyUptimePreset.CUSTOM.windowFor(now, customWindowMillis = 123_456L)
        assertEquals(now - 123_456L, custom.startInclusive)
        assertEquals(now, custom.endExclusive)
        assertThrows(IllegalArgumentException::class.java) { MyUptimePreset.CUSTOM.windowFor(now) }
    }

    @Test
    fun rolling60sDistinctFromLongWindowAverages() = runBlocking {
        assertEquals(60_000L, MyUptimeAggregator.ROLLING_WINDOW_MILLIS)
        val now = 1_000_000_000L
        insert("run1", now - 10_000L, outcome = "RUNNING")
        insert("ok1", now - 30_000L, totalTokens = 120L)
        insert("old", now - 50 * 60_000L, totalTokens = 60_000L)
        val samples = db.sampleDao().myUptimeSamples(
            providerId = scope.providerId,
            accountId = scope.accountId,
            safeCredentialId = scope.safeCredentialId,
            modelId = scope.modelId,
            endpointId = scope.endpointId,
            group = scope.group,
            startInclusive = 0L,
            endExclusive = now,
        ).map { it.toSample() }

        // rolling-60s（current*）：60s = 1min，run1+ok1；RUNNING 計入 RPM（X-016）、
        // TPM 只取非 null usage。
        val rates = MyUptimeAggregator.currentObservedRates(samples, scope, now)
        assertEquals(2.0, rates.currentRpm!!, 1e-9)
        assertEquals(120.0, rates.currentTpm!!, 1e-9)

        // 長窗平均（observed*）：LAST_1H = 3 樣本 / 60 min；與 rolling-60s 命名區分，
        // 不得互相冒充。
        val summary = MyUptimeAggregator.summarize(samples, scope, MyUptimePreset.LAST_1H.windowFor(now))
        assertEquals(3, summary.sampleCount)
        assertEquals(2, summary.decided)
        assertEquals(2, summary.successes)
        assertEquals(1.0, summary.successRate!!, 1e-9)
        assertEquals(0.05, summary.observedRpm!!, 1e-9)
        assertEquals(1002.0, summary.observedTpm!!, 1e-9)
        assertEquals(now - 10_000L, summary.lastObservedAt)
        assertTrue(rates.currentRpm != summary.observedRpm)
        assertTrue(rates.currentTpm != summary.observedTpm)
    }

    @Test
    fun scopeSixDimensionIsolationIncludingEndpoint() = runBlocking {
        val now = 1_000_000_000L
        insert("base", now - 60_000L)
        insert("otherProvider", now - 60_000L, providerId = "p2")
        insert("otherAccount", now - 60_000L, accountId = "a2")
        insert("otherCredential", now - 60_000L, credentialId = "c2")
        insert("otherModel", now - 60_000L, modelId = "m2")
        insert("otherEndpoint", now - 60_000L, endpointId = "e2")
        insert("otherGroup", now - 60_000L, group = "g1")
        // 六維精確全等；`group` 以 `IS :group` 精確比對，null 只等 NULL，不當萬用字元。
        assertEquals(listOf("base"), queryIds(0L, now))
        assertEquals(listOf("otherGroup"), queryIds(0L, now, scope.copy(group = "g1")))
    }
}
