package dev.modelatlas.core.lmspeed

import dev.modelatlas.core.domain.MappingConfidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 S2 LmspeedImporter 邊界測試（TRACEABILITY LMS-001／LMS-005；純 JVM，不連線、不帶 key）。
 *
 * 只用假 fetcher／假 sink＋fixture slug；不寫任何來源 URL（除合法 `https://lmspeed.net/…` 回指）、
 * 不含任何 secret；釘住：成功落庫、失敗保舊、TTL／來源標記、confidence 降級、非法筆略過。
 */
class LmspeedImporterTest {

    private class FakeFetcher(
        var models: List<LmspeedRawModel> = emptyList(),
        var error: Throwable? = null,
        var calls: Int = 0,
    ) : LmspeedFetcher {
        override suspend fun fetchModels(): List<LmspeedRawModel> {
            calls++
            error?.let { throw it }
            return models
        }
    }

    private class FakeSink(
        val stored: MutableList<LmspeedObservation> = mutableListOf(),
        var failOnSave: Throwable? = null,
        var calls: Int = 0,
    ) : LmspeedObservationSink {
        override suspend fun saveObservations(rows: List<LmspeedObservation>) {
            calls++
            failOnSave?.let { throw it }
            // 模擬 ExternalStore upsert 語意：同 observationId 覆寫。
            for (row in rows) {
                stored.removeAll { it.observationId == row.observationId }
                stored.add(row)
            }
        }
    }

    private fun raw(
        slug: String = "fixture-alpha",
        endpoint: String = LMSPEED_MODELS_ENDPOINT,
        lane: String? = null,
        displayOnly: Boolean = false,
        url: String? = "https://lmspeed.net/model/fixture-alpha",
    ) = LmspeedRawModel(
        slug = slug,
        name = "Fixture Alpha",
        developer = "FixtureWorks",
        lmspeedUrl = url,
        sourceEndpoint = endpoint,
        lane = lane,
        includeDisplayOnly = displayOnly,
    )

    private fun oldRow() = LmspeedObservation(
        observationId = lmspeedObservationIdOf(LMSPEED_SOURCE, "fixture-old", 1000L),
        source = LMSPEED_SOURCE,
        externalModelId = "fixture-old",
        canonicalModelId = null,
        payloadJson = """{"slug":"fixture-old"}""",
        fetchedAt = 1000L,
        ttlMillis = 1000L,
        confidence = MappingConfidence.LOW.name,
    )

    @Test
    fun successPersistsAllRows() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-alpha"), raw("fixture-beta", url = "https://lmspeed.net/model/fixture-beta")))
        val sink = FakeSink()
        val importer = LmspeedImporter(fetcher, sink)
        val result = importer.importOnce(nowMillis = 5000L, ttlMillis = 1000L) as LmspeedImportResult.Success
        assertEquals(2, result.saved)
        assertEquals(2, sink.stored.size)
        assertEquals(1, fetcher.calls)
        assertEquals(1, sink.calls)
    }

    @Test
    fun sourceMarkingIsAlwaysLmspeed() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-a"), raw("fixture-b", url = null)))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(7000L, 1000L) as LmspeedImportResult.Success
        assertEquals(2, result.saved)
        assertTrue(result.rows.all { it.source == LMSPEED_SOURCE })
        assertTrue(sink.stored.all { it.source == LMSPEED_SOURCE })
        assertEquals("LMSPEED", LMSPEED_SOURCE)
    }

    @Test
    fun fetchedAtIsStampedFromCaller() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-a")))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(nowMillis = 4242L, ttlMillis = 500L) as LmspeedImportResult.Success
        assertEquals(4242L, result.rows.single().fetchedAt)
        assertEquals(4242L, sink.stored.single().fetchedAt)
    }

    @Test
    fun ttlIsStampedFromCaller() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-a")))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(nowMillis = 1000L, ttlMillis = 8888L) as LmspeedImportResult.Success
        assertEquals(8888L, result.rows.single().ttlMillis)
        assertEquals(8888L, sink.stored.single().ttlMillis)
    }

    @Test
    fun defaultConfidenceIsLow() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-a"), raw("fixture-b", lane = "verified", url = null)))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertTrue(result.rows.all { it.confidence == MappingConfidence.LOW.name })
    }

    @Test
    fun provisionalLaneDowngradesToUnknown() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-a", lane = "provisional")))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(MappingConfidence.UNKNOWN.name, result.rows.single().confidence)
        assertTrue(result.rows.single().payloadJson.contains("provisional"))
    }

    @Test
    fun displayOnlyDowngradesToUnknown() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-a", displayOnly = true)))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(MappingConfidence.UNKNOWN.name, result.rows.single().confidence)
        assertTrue(result.rows.single().payloadJson.contains("display_only"))
    }

    @Test
    fun blankSlugIsSkippedWithoutFailingWholeBatch() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("   ", url = null), raw("fixture-good")))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(1, result.saved)
        assertEquals("fixture-good", result.rows.single().externalModelId)
        assertEquals(1, sink.stored.size)
    }

    @Test
    fun nonModelsEndpointIsRejected() = runBlocking {
        val fetcher = FakeFetcher(
            models = listOf(
                raw("fixture-pricing", endpoint = "GET /api/v1/pricing", url = null),
                raw("fixture-bench", endpoint = "GET /api/v1/benchmarks", url = null),
                raw("fixture-ok"),
            ),
        )
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(1, result.saved)
        assertEquals("fixture-ok", result.rows.single().externalModelId)
    }

    @Test
    fun detailEndpointIsAccepted() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-d", endpoint = LMSPEED_MODEL_DETAIL_ENDPOINT)))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(1, result.saved)
        assertTrue(result.rows.single().payloadJson.contains("GET /api/v1/models/{slug}"))
    }

    @Test
    fun parkingDomainUrlIsRejected() = runBlocking {
        val fetcher = FakeFetcher(
            models = listOf(
                raw("fixture-parked", url = "https://lmspeed.com/model/fixture-parked"),
                raw("fixture-vpn", url = "https://lmspeed.co/model/x"),
                raw("fixture-ok", url = "https://lmspeed.net/model/fixture-ok"),
            ),
        )
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(1, result.saved)
        assertEquals("fixture-ok", result.rows.single().externalModelId)
    }

    @Test
    fun fetchFailureNeverCallsSinkPreservingOldData() = runBlocking {
        val fetcher = FakeFetcher(error = IllegalStateException("fixture network down"))
        val sink = FakeSink(mutableListOf(oldRow()))
        val importer = LmspeedImporter(fetcher, sink)
        val result = importer.importOnce(2000L, 1000L)
        assertTrue(result is LmspeedImportResult.Failure)
        assertEquals(0, (result as LmspeedImportResult.Failure).saved)
        assertEquals(0, sink.calls)
        assertEquals(1, sink.stored.size)
        assertEquals("fixture-old", sink.stored.single().externalModelId)
    }

    @Test
    fun sinkFailureReturnsFailureWithoutDeleting() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-new")))
        val sink = FakeSink(mutableListOf(oldRow()), failOnSave = IllegalStateException("fixture db full"))
        val result = LmspeedImporter(fetcher, sink).importOnce(2000L, 1000L)
        assertTrue(result is LmspeedImportResult.Failure)
        assertEquals(0, (result as LmspeedImportResult.Failure).saved)
        assertEquals(1, sink.stored.size)
        assertEquals("fixture-old", sink.stored.single().externalModelId)
    }

    @Test
    fun observationIdIsDeterministic() = runBlocking {
        val first = FakeSink()
        LmspeedImporter(FakeFetcher(models = listOf(raw("fixture-a"))), first).importOnce(1000L, 500L)
        val second = FakeSink()
        LmspeedImporter(FakeFetcher(models = listOf(raw("  fixture-a  ", url = null))), second).importOnce(1000L, 999L)
        assertEquals(first.stored.single().observationId, second.stored.single().observationId)
        val third = FakeSink()
        LmspeedImporter(FakeFetcher(models = listOf(raw("fixture-a"))), third).importOnce(2000L, 500L)
        assertTrue(first.stored.single().observationId != third.stored.single().observationId)
    }

    @Test
    fun canonicalStaysNullNeverGuessed() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-a"), raw("fixture-b", url = null)))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertTrue(result.rows.all { it.canonicalModelId == null })
        assertNull(sink.stored.first().canonicalModelId)
    }

    @Test
    fun payloadCarriesAttributionAndNoSecret() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-a")))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        val payload = result.rows.single().payloadJson
        assertTrue(payload.contains("https://lmspeed.net"))
        assertTrue(payload.contains("LMSpeed"))
        assertTrue(payload.contains("fixture-a"))
        assertTrue(!payload.contains("x-api-key", ignoreCase = true))
        assertTrue(payload.isNotBlank())
    }

    @Test
    fun duplicateSlugsDedupeToSingleRow() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(raw("fixture-dup"), raw("fixture-dup", url = null)))
        val sink = FakeSink()
        val result = LmspeedImporter(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(1, result.saved)
        assertEquals(1, sink.stored.size)
    }

    @Test
    fun emptyFetchSucceedsWithZeroWithoutSinkCall() = runBlocking {
        val fetcher = FakeFetcher(models = emptyList())
        val sink = FakeSink()
        val result = importerOf(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(0, result.saved)
        assertTrue(result.rows.isEmpty())
        assertEquals(0, sink.calls)
    }

    // ---- P7 repair：enrichment 欄位（LMS-005）只搬運、不猜 ----

    @Test
    fun enrichmentFieldsRidePayloadWhenPresent() = runBlocking {
        val fetcher = FakeFetcher(
            models = listOf(
                LmspeedRawModel(
                    slug = "fixture-rich",
                    description = "Fixture description",
                    contextWindowTokens = 1048576,
                    maxOutputTokens = 384000,
                    capabilities = listOf("TEXT", "TOOL_CALLING"),
                    externalTps = 49.5,
                    externalFirstTokenLatency = 7150.25,
                    benchmarkRef = """{"metric_key":"overall_score"}""",
                    pricingRef = """{"input_price_per_million":1.74}""",
                ),
            ),
        )
        val sink = FakeSink()
        val result = importerOf(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(1, result.saved)
        val payload = result.rows.single().payloadJson
        assertTrue(payload.contains("Fixture description"))
        assertTrue(payload.contains("1048576"))
        assertTrue(payload.contains("TOOL_CALLING"))
        assertTrue(payload.contains("external_tps"))
        assertTrue(payload.contains("external_first_token_latency"))
        assertTrue(payload.contains("benchmark_ref"))
        assertTrue(payload.contains("pricing_ref"))
    }

    @Test
    fun enrichmentAbsentStaysUnknownOmitted() = runBlocking {
        val fetcher = FakeFetcher(models = listOf(LmspeedRawModel(slug = "fixture-bare")))
        val sink = FakeSink()
        val result = importerOf(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        assertEquals(1, result.saved)
        val payload = result.rows.single().payloadJson
        assertTrue(!payload.contains("external_tps"))
        assertTrue(!payload.contains("external_first_token_latency"))
        assertTrue(!payload.contains("benchmark_ref"))
        assertTrue(!payload.contains("pricing_ref"))
        assertTrue(!payload.contains("capabilities"))
    }

    @Test
    fun enrichmentInvalidValuesCollapseToUnknown() = runBlocking {
        val fetcher = FakeFetcher(
            models = listOf(
                LmspeedRawModel(
                    slug = "fixture-invalid",
                    contextWindowTokens = -5,
                    maxOutputTokens = 0,
                    capabilities = listOf("   ", ""),
                    externalTps = Double.NaN,
                    externalFirstTokenLatency = -1.0,
                ),
            ),
        )
        val sink = FakeSink()
        val result = importerOf(fetcher, sink).importOnce(1000L, 1000L) as LmspeedImportResult.Success
        // 非法值不失敗整批：列保留，非法欄位省略（Unknown），不填零。
        assertEquals(1, result.saved)
        val payload = result.rows.single().payloadJson
        assertTrue(!payload.contains("context_window_tokens"))
        assertTrue(!payload.contains("max_output_tokens"))
        assertTrue(!payload.contains("capabilities"))
        assertTrue(!payload.contains("external_tps"))
        assertTrue(!payload.contains("external_first_token_latency"))
    }

    private fun importerOf(fetcher: FakeFetcher, sink: FakeSink) = LmspeedImporter(fetcher, sink)
}
