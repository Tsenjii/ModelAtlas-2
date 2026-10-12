package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6 session G：MODEL-008 Compare Live 產品 feed 測試（記憶體 db）。
 *
 * TRACEABILITY：MODEL-008（PRODUCT_SPEC §8／§3／§10）。
 * Fixture 僅存於 androidTest，不當產品資料；不用 mock，不碰既有檔。
 */
@RunWith(AndroidJUnit4::class)
class CompareLiveFeedTest {
    private lateinit var context: Context
    private var db: AtlasDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        runCatching { db?.close() }
        db = null
    }

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun finalSample(
        id: String,
        providerId: String = "p-feed",
        endpointId: String = "e-feed",
        modelId: String = "m-feed",
        startedAt: Long = 5_000L,
        completedAt: Long? = 6_000L,
        outcome: Outcome = Outcome.SUCCESS,
        ttftMs: Long? = 400L,
        tps: Double? = 110.0,
        inputTokens: Long? = 8L,
        outputTokens: Long? = 12L,
        httpStatus: Int? = 200,
        priceState: String = "UNKNOWN",
        quotaState: String = "UNKNOWN",
        headers: Map<String, String> = emptyMap(),
    ) = PerformanceSample(
        id = id,
        providerId = providerId,
        accountId = "a-feed",
        safeCredentialId = "c-feed",
        endpointId = endpointId,
        providerModelId = modelId,
        canonicalModelId = "canonical-feed",
        group = null,
        operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.COMPARE_LIVE,
        requestStartedAt = startedAt,
        firstTokenAt = if (ttftMs == null) null else startedAt + ttftMs,
        completedAt = completedAt,
        streaming = true,
        ttftMs = ttftMs,
        ttfbMs = 100L,
        totalLatencyMs = 1_000L,
        tps = tps,
        usage = Usage(inputTokens = inputTokens, outputTokens = outputTokens, totalTokens = 20L),
        httpStatus = httpStatus,
        outcome = outcome,
        rateLimitHeaders = headers,
        priceState = priceState,
        quotaState = quotaState,
    )

    @Test
    fun emptyDbReturnsEmptyAndNull() {
        val db = memoryDb()
        val feed = RoomCompareLiveSampleFeed(db)

        assertTrue(feed.finalSamples().isEmpty())
        assertNull(feed.latestFinalFor("p-feed", "e-feed", "m-feed"))
    }

    @Test
    fun runningSampleIsNotFinal() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val feed = RoomCompareLiveSampleFeed(db)
        repo.save(finalSample("run-1", completedAt = null, outcome = Outcome.RUNNING))

        assertTrue(feed.finalSamples().isEmpty())
        assertNull(feed.latestFinalFor("p-feed", "e-feed", "m-feed"))
    }

    @Test
    fun finalSampleRoundTripPreservesMetrics() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val feed = RoomCompareLiveSampleFeed(db)
        repo.save(
            finalSample(
                "final-1",
                outcome = Outcome.SUCCESS,
                httpStatus = 200,
                ttftMs = 400L,
                tps = 110.0,
                priceState = "FREE_NOW",
                headers = mapOf("retry-after" to "30"),
            ),
        )

        val got = feed.latestFinalFor("p-feed", "e-feed", "m-feed")
        assertNotNull(got)
        assertEquals("final-1", got!!.id)
        assertEquals(Outcome.SUCCESS, got.outcome)
        assertEquals(200, got.httpStatus)
        assertEquals(400L, got.ttftMs)
        assertEquals(110.0, got.tps!!, 1e-9)
        assertEquals(8L, got.usage.inputTokens)
        assertEquals(12L, got.usage.outputTokens)
        assertEquals("FREE_NOW", got.priceState)
        assertEquals("30", got.rateLimitHeaders["retry-after"])
        assertEquals(TelemetrySource.COMPARE_LIVE, got.source)
        assertEquals(1, feed.finalSamples().size)
    }

    @Test
    fun latestStartedAtWinsForSameTriple() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val feed = RoomCompareLiveSampleFeed(db)
        repo.save(finalSample("old-1", startedAt = 1_000L, completedAt = 2_000L))
        repo.save(finalSample("new-1", startedAt = 5_000L, completedAt = 6_000L))

        val got = feed.latestFinalFor("p-feed", "e-feed", "m-feed")
        assertNotNull(got)
        assertEquals("new-1", got!!.id)
        assertEquals(5_000L, got.requestStartedAt)
    }

    @Test
    fun tripleIsolationByProviderEndpointModel() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val feed = RoomCompareLiveSampleFeed(db)
        repo.save(finalSample("s-a", providerId = "p-a", endpointId = "e-a", modelId = "m-a"))
        repo.save(finalSample("s-b", providerId = "p-b", endpointId = "e-b", modelId = "m-b"))

        assertEquals("s-a", feed.latestFinalFor("p-a", "e-a", "m-a")!!.id)
        assertEquals("s-b", feed.latestFinalFor("p-b", "e-b", "m-b")!!.id)
        // 任一維不同即對不上＝未知，不捏造。
        assertNull(feed.latestFinalFor("p-a", "e-a", "m-b"))
        assertNull(feed.latestFinalFor("p-a", "e-b", "m-a"))
        assertNull(feed.latestFinalFor("p-unknown", "e-a", "m-a"))
        assertEquals(2, feed.finalSamples().size)
    }

    @Test
    fun nullMetricsStayNullNeverZero() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val feed = RoomCompareLiveSampleFeed(db)
        repo.save(finalSample("null-1", ttftMs = null, tps = null, inputTokens = null, outputTokens = null))

        val got = feed.latestFinalFor("p-feed", "e-feed", "m-feed")
        assertNotNull(got)
        assertNull(got!!.ttftMs)
        assertNull(got.tps)
        assertNull(got.usage.inputTokens)
        assertNull(got.usage.outputTokens)
        assertEquals("UNKNOWN", got.priceState)
    }

    @Test
    fun engineDoubleSaveSameIdEndsAsFinal() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val feed = RoomCompareLiveSampleFeed(db)
        // 模擬 InferenceEngine 同 id 寫兩次：先 RUNNING（completedAt null），再 SUCCESS final。
        repo.save(finalSample("dup-1", completedAt = null, outcome = Outcome.RUNNING))
        assertNull(feed.latestFinalFor("p-feed", "e-feed", "m-feed"))
        repo.save(finalSample("dup-1", completedAt = 6_000L, outcome = Outcome.SUCCESS))

        val got = feed.latestFinalFor("p-feed", "e-feed", "m-feed")
        assertNotNull(got)
        assertEquals(Outcome.SUCCESS, got!!.outcome)
        assertEquals(6_000L, got.completedAt)
    }

    @Test
    fun feedQueriesDoNotModifyRows() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val feed = RoomCompareLiveSampleFeed(db)
        repo.save(finalSample("ro-1"))
        assertEquals(1, db.sampleDao().countById("ro-1"))

        feed.finalSamples()
        feed.latestFinalFor("p-feed", "e-feed", "m-feed")
        feed.latestFinalFor("p-miss", "e-miss", "m-miss")

        assertEquals(1, db.sampleDao().countById("ro-1"))
        assertEquals("ro-1", db.sampleDao().getById("ro-1")!!.id)
    }
}
