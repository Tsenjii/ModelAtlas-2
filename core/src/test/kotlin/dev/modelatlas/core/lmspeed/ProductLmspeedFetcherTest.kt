package dev.modelatlas.core.lmspeed

import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P7 S3 ProductLmspeedFetcher 邊界測試（TRACEABILITY LMS-001／LMS-005；純 JVM）。
 *
 * 只用假 vault／假 transport／假 clock＋記憶體 budget／記憶體 sink；
 * 不連線、不帶真 key（fixture key 僅 `fixture-*`，不當產品資料）；
 * 釘住：safe ref 佔位＋不存 key、單日上限、失敗分類、取消傳播、端到端透傳。
 */
class ProductLmspeedFetcherTest {

    companion object {
        private const val SAFE_REF = "safe-ref-fixture-lmspeed"
        private const val ORIGIN = LMSPEED_FETCH_ORIGIN
        private const val FIXTURE_KEY = "fixture-lmspeed-public-key-must-not-leak"
    }

    private class FakeCredentials(
        var keyForRef: Map<String, String> = mapOf(SAFE_REF to FIXTURE_KEY),
        var error: Throwable? = null,
        val seenRefs: MutableList<String> = mutableListOf(),
        val seenOrigins: MutableList<String> = mutableListOf(),
    ) : CredentialAccess {
        override fun bearerToken(credentialId: String, origin: String): String {
            seenRefs += credentialId
            seenOrigins += origin
            error?.let { throw it }
            return keyForRef[credentialId] ?: throw IllegalStateException("fixture vault miss")
        }
    }

    private class FakeClock(var now: Long = 1_000L) : Clock {
        override fun epochMillis(): Long = now
        override fun monotonicMillis(): Long = now
    }

    private class FakeTransport(
        var models: List<LmspeedRawModel> = emptyList(),
        var error: Throwable? = null,
        var calls: Int = 0,
        val seenKeys: MutableList<String> = mutableListOf(),
    ) : LmspeedHttpTransport {
        override suspend fun fetch(apiKey: String): List<LmspeedRawModel> {
            calls++
            seenKeys += apiKey
            error?.let { throw it }
            return models
        }
    }

    private class MemorySink(
        val stored: MutableList<LmspeedObservation> = mutableListOf(),
    ) : LmspeedObservationSink {
        override suspend fun saveObservations(rows: List<LmspeedObservation>) {
            for (row in rows) {
                stored.removeAll { it.observationId == row.observationId }
                stored.add(row)
            }
        }
    }

    private fun raw(slug: String = "fixture-alpha") = LmspeedRawModel(
        slug = slug,
        name = "Fixture Alpha",
        developer = "FixtureWorks",
        lmspeedUrl = "https://lmspeed.net/model/$slug",
        sourceEndpoint = LMSPEED_MODELS_ENDPOINT,
    )

    private fun fetcher(
        creds: FakeCredentials = FakeCredentials(),
        transport: FakeTransport = FakeTransport(),
        clock: FakeClock = FakeClock(),
        budget: LmspeedDailyBudget = InMemoryLmspeedDailyBudget(),
        safeRef: String = SAFE_REF,
    ) = ProductLmspeedFetcher(
        safeCredentialId = safeRef,
        credentials = creds,
        transport = transport,
        budget = budget,
        clock = clock,
    )

    @Test
    fun fetchDelegatesToTransportWithResolvedKey() = runBlocking {
        val creds = FakeCredentials()
        val transport = FakeTransport(models = listOf(raw("fixture-a")))
        val out = fetcher(creds, transport).fetchModels()
        assertEquals(1, out.size)
        assertEquals("fixture-a", out.single().slug)
        assertEquals(1, transport.calls)
        assertEquals(listOf(FIXTURE_KEY), transport.seenKeys)
        assertEquals(listOf(SAFE_REF), creds.seenRefs)
        assertEquals(listOf(ORIGIN), creds.seenOrigins)
    }

    @Test
    fun blankSafeRefRejectedWithoutSideEffects() {
        val creds = FakeCredentials()
        val transport = FakeTransport()
        try {
            ProductLmspeedFetcher("   ", creds, transport)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, transport.calls)
        assertTrue(creds.seenRefs.isEmpty())
    }

    @Test
    fun vaultRefusalMapsToCredentialUnavailableWithoutConsumingBudget() = runBlocking {
        val budget = InMemoryLmspeedDailyBudget()
        val creds = FakeCredentials(error = IllegalStateException("fixture vault locked"))
        val transport = FakeTransport(models = listOf(raw()))
        try {
            fetcher(creds, transport, budget = budget).fetchModels()
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.CREDENTIAL_UNAVAILABLE, e.category)
        }
        assertEquals(0, transport.calls)
        assertEquals(LMSPEED_MAX_REQUESTS_PER_DAY, budget.remaining(1_000L))
    }

    @Test
    fun blankResolvedKeyMapsToCredentialUnavailable() = runBlocking {
        val creds = FakeCredentials(keyForRef = mapOf(SAFE_REF to "   "))
        val transport = FakeTransport(models = listOf(raw()))
        try {
            fetcher(creds, transport).fetchModels()
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.CREDENTIAL_UNAVAILABLE, e.category)
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun dailyBudgetExhaustedThrowsWithoutNetwork() = runBlocking {
        val clock = FakeClock(now = 5_000L)
        val budget = InMemoryLmspeedDailyBudget(maxPerDay = 2)
        val creds = FakeCredentials()
        val transport = FakeTransport(models = listOf(raw()))
        val f = fetcher(creds, transport, clock, budget)
        f.fetchModels()
        f.fetchModels()
        assertEquals(0, budget.remaining(5_000L))
        try {
            f.fetchModels()
            fail("expected DAILY_BUDGET_EXHAUSTED")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.DAILY_BUDGET_EXHAUSTED, e.category)
        }
        assertEquals(2, transport.calls)
        assertEquals(2, creds.seenRefs.size)
    }

    @Test
    fun budgetResetsNextDay() = runBlocking {
        val clock = FakeClock(now = 1_000L)
        val budget = InMemoryLmspeedDailyBudget(maxPerDay = 1)
        val f = fetcher(FakeCredentials(), FakeTransport(models = listOf(raw())), clock, budget)
        f.fetchModels()
        assertEquals(0, budget.remaining(clock.now))
        clock.now = LMSPEED_MILLIS_PER_DAY + 1_000L
        assertEquals(1, budget.remaining(clock.now))
        f.fetchModels()
        assertEquals(0, budget.remaining(clock.now))
    }

    @Test
    fun budgetRollUsesDeviceLocalDayNotEpochDay() {
        // 1970-01-01T04:30:00Z＝美東 1969-12-31 23:30；一小時後 epoch 同日、美東已換日。
        // 舊 floorDiv 邏輯兩者同日（不重置）；device-local 必須重置。
        val instantA = 16_200_000L // 04:30Z
        val instantB = instantA + 3_600_000L // 05:30Z
        val newYork = java.time.ZoneId.of("America/New_York")
        val budget = InMemoryLmspeedDailyBudget(maxPerDay = 1, zone = newYork)
        assertTrue(budget.tryConsume(instantA))
        assertEquals(0, budget.remaining(instantA))
        // 美東已換日 → 恢復滿額；UTC 視角仍同日（對照，epoch 切分不會重置）。
        assertEquals(1, budget.remaining(instantB))
        val utcBudget = InMemoryLmspeedDailyBudget(maxPerDay = 1, zone = java.time.ZoneId.of("UTC"))
        assertTrue(utcBudget.tryConsume(instantA))
        assertEquals(0, utcBudget.remaining(instantB))
    }

    @Test
    fun httpFailuresClassifyByStatus() {
        assertEquals(LmspeedFetchFailure.AUTH, ProductLmspeedFetcher.classifyHttpStatus(401))
        assertEquals(LmspeedFetchFailure.AUTH, ProductLmspeedFetcher.classifyHttpStatus(403))
        assertEquals(LmspeedFetchFailure.NOT_FOUND, ProductLmspeedFetcher.classifyHttpStatus(404))
        assertEquals(LmspeedFetchFailure.RATE_LIMIT, ProductLmspeedFetcher.classifyHttpStatus(429))
        assertEquals(LmspeedFetchFailure.CLIENT, ProductLmspeedFetcher.classifyHttpStatus(400))
        assertEquals(LmspeedFetchFailure.SERVER, ProductLmspeedFetcher.classifyHttpStatus(500))
        assertEquals(LmspeedFetchFailure.SERVER, ProductLmspeedFetcher.classifyHttpStatus(503))
        assertEquals(LmspeedFetchFailure.UNKNOWN, ProductLmspeedFetcher.classifyHttpStatus(302))
    }

    @Test
    fun transportErrorsClassifyByType() {
        assertEquals(
            LmspeedFetchFailure.TLS,
            ProductLmspeedFetcher.classifyTransportError(SSLException("fixture tls")),
        )
        assertEquals(
            LmspeedFetchFailure.NETWORK,
            ProductLmspeedFetcher.classifyTransportError(UnknownHostException("fixture dns")),
        )
        assertEquals(
            LmspeedFetchFailure.TIMEOUT,
            ProductLmspeedFetcher.classifyTransportError(SocketTimeoutException("fixture timeout")),
        )
        assertEquals(
            LmspeedFetchFailure.NETWORK,
            ProductLmspeedFetcher.classifyTransportError(IOException("fixture io")),
        )
    }

    @Test
    fun networkErrorSurfacesAsClassifiedException() = runBlocking {
        val transport = FakeTransport(error = UnknownHostException("fixture dns down"))
        try {
            fetcher(transport = transport).fetchModels()
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.NETWORK, e.category)
        }
    }

    @Test
    fun timeoutErrorSurfacesAsTimeout() = runBlocking {
        val transport = FakeTransport(error = SocketTimeoutException("fixture slow"))
        try {
            fetcher(transport = transport).fetchModels()
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertEquals(LmspeedFetchFailure.TIMEOUT, e.category)
        }
    }

    @Test
    fun classifiedHttpExceptionPassesThroughUnchanged() = runBlocking {
        val seen = LmspeedFetchException(LmspeedFetchFailure.RATE_LIMIT, 429)
        val transport = FakeTransport(error = seen)
        try {
            fetcher(transport = transport).fetchModels()
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertTrue(e === seen)
            assertEquals(LmspeedFetchFailure.RATE_LIMIT, e.category)
            assertEquals(429, e.httpStatus)
        }
    }

    @Test
    fun cancellationPropagatesWithoutWrapping() = runBlocking {
        val transport = FakeTransport(error = CancellationException("fixture cancel"))
        try {
            fetcher(transport = transport).fetchModels()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("fixture cancel", e.message)
        }
    }

    @Test
    fun exceptionMessageAndToStringNeverContainKey() = runBlocking {
        val transport = FakeTransport(error = UnknownHostException("fixture down"))
        try {
            fetcher(transport = transport).fetchModels()
            fail("expected LmspeedFetchException")
        } catch (e: LmspeedFetchException) {
            assertTrue(!(e.message ?: "").contains(FIXTURE_KEY))
            assertTrue(!e.toString().contains(FIXTURE_KEY))
        }
        val f = fetcher()
        assertTrue(!f.toString().contains(FIXTURE_KEY))
        assertTrue(!f.toString().contains(SAFE_REF))
    }

    @Test
    fun endToEndImporterWithProductFetcherAndMemorySink() = runBlocking {
        val transport = FakeTransport(models = listOf(raw("fixture-e2e-a"), raw("fixture-e2e-b")))
        val productFetcher = fetcher(transport = transport)
        val sink = MemorySink()
        val result = LmspeedImporter(productFetcher, sink).importOnce(nowMillis = 9_000L, ttlMillis = 1_000L)
            as LmspeedImportResult.Success
        assertEquals(2, result.saved)
        assertEquals(2, sink.stored.size)
        assertTrue(sink.stored.all { it.source == LMSPEED_SOURCE })
        assertTrue(sink.stored.all { it.fetchedAt == 9_000L })
        assertTrue(sink.stored.all { it.ttlMillis == 1_000L })
        assertTrue(sink.stored.all { it.confidence == MappingConfidence.LOW.name })
        assertTrue(sink.stored.all { it.canonicalModelId == null })
    }
}
