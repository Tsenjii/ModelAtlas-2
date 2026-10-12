package dev.modelatlas.app.data

import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.lmspeed.LMSPEED_SOURCE
import dev.modelatlas.core.lmspeed.LmspeedObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P7 S3 LmspeedSinkAdapter 轉接測試（TRACEABILITY LMS-001／LMS-003／LMS-005；純 JVM）。
 *
 * 只用假 writer（記憶體 store：`MutableList`）＋fixture 承載；不碰 Room／網路／secret，
 * 不寫任何來源 URL（除 `LMSPEED_SOURCE` 常數）、不當產品資料；
 * 釘住：TTL／confidence 透傳、canonical 不猜、空批 no-op、失敗上拋保舊、取消傳播。
 */
class LmspeedSinkAdapterTest {

    private class MemoryWriter(
        val written: MutableList<ExternalObservationEntity> = mutableListOf(),
        var error: Throwable? = null,
        var calls: Int = 0,
    ) : ExternalObservationWriter {
        override suspend fun write(rows: List<ExternalObservationEntity>) {
            calls++
            error?.let { throw it }
            written += rows
        }
    }

    private fun observation(
        observationId: String = "ext-obs-fixture-1",
        source: String = LMSPEED_SOURCE,
        externalModelId: String = "fixture-ext-model",
        canonicalModelId: String? = null,
        payloadJson: String = """{"slug":"fixture-ext-model"}""",
        fetchedAt: Long = 1_000L,
        ttlMillis: Long = 2_000L,
        confidence: String = MappingConfidence.LOW.name,
    ) = LmspeedObservation(
        observationId = observationId,
        source = source,
        externalModelId = externalModelId,
        canonicalModelId = canonicalModelId,
        payloadJson = payloadJson,
        fetchedAt = fetchedAt,
        ttlMillis = ttlMillis,
        confidence = confidence,
    )

    @Test
    fun mappingPreservesAllFieldsOneToOne() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        val row = observation()
        adapter.saveObservations(listOf(row))
        assertEquals(1, writer.calls)
        val kept = writer.written.single()
        assertEquals(row.observationId, kept.observationId)
        assertEquals(row.source, kept.source)
        assertEquals(row.externalModelId, kept.externalModelId)
        assertEquals(row.canonicalModelId, kept.canonicalModelId)
        assertEquals(row.payloadJson, kept.payloadJson)
        assertEquals(row.fetchedAt, kept.fetchedAt)
        assertEquals(row.ttlMillis, kept.ttlMillis)
        assertEquals(row.confidence, kept.confidence)
    }

    @Test
    fun ttlPassthroughIncludingZero() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        adapter.saveObservations(
            listOf(
                observation(observationId = "id-zero", ttlMillis = 0L),
                observation(observationId = "id-large", ttlMillis = 86_400_000L),
            ),
        )
        assertEquals(0L, writer.written.first { it.observationId == "id-zero" }.ttlMillis)
        assertEquals(86_400_000L, writer.written.first { it.observationId == "id-large" }.ttlMillis)
    }

    @Test
    fun confidenceAllLevelsPassthrough() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        val levels = listOf(
            MappingConfidence.HIGH.name,
            MappingConfidence.MEDIUM.name,
            MappingConfidence.LOW.name,
            MappingConfidence.UNKNOWN.name,
        )
        adapter.saveObservations(
            levels.mapIndexed { index, level ->
                observation(observationId = "id-$index", confidence = level)
            },
        )
        assertEquals(4, writer.written.size)
        levels.forEachIndexed { index, level ->
            assertEquals(level, writer.written.first { it.observationId == "id-$index" }.confidence)
        }
    }

    @Test
    fun canonicalNullNeverGuessed() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        adapter.saveObservations(listOf(observation(canonicalModelId = null)))
        assertNull(writer.written.single().canonicalModelId)
    }

    @Test
    fun canonicalNonNullPassthroughWithoutSynthesis() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        adapter.saveObservations(listOf(observation(canonicalModelId = "canon-fixture")))
        assertEquals("canon-fixture", writer.written.single().canonicalModelId)
    }

    @Test
    fun emptyListIsNoOpWithoutWriterCall() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        adapter.saveObservations(emptyList())
        assertEquals(0, writer.calls)
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun batchOrderAndCountPreserved() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        val rows = (1..5).map { observation(observationId = "batch-$it", externalModelId = "model-$it") }
        adapter.saveObservations(rows)
        assertEquals(1, writer.calls)
        assertEquals(5, writer.written.size)
        assertEquals((1..5).map { "batch-$it" }, writer.written.map { it.observationId })
        assertEquals((1..5).map { "model-$it" }, writer.written.map { it.externalModelId })
    }

    @Test
    fun payloadJsonByteIdentical() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        val payload = """{"slug":"fixture-x","name":"Fixture X","attribution":{"required":true}}"""
        adapter.saveObservations(listOf(observation(payloadJson = payload)))
        assertEquals(payload, writer.written.single().payloadJson)
    }

    @Test
    fun writerFailurePropagatesWithoutSwallow() = runBlocking {
        val writer = MemoryWriter(error = IllegalStateException("fixture db full"))
        val adapter = LmspeedSinkAdapter(writer)
        try {
            adapter.saveObservations(listOf(observation()))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("fixture db full", e.message)
        }
        assertEquals(1, writer.calls)
    }

    @Test
    fun cancellationPropagates() = runBlocking {
        val writer = MemoryWriter(error = CancellationException("fixture cancel"))
        val adapter = LmspeedSinkAdapter(writer)
        try {
            adapter.saveObservations(listOf(observation()))
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("fixture cancel", e.message)
        }
    }

    @Test
    fun sourceAndIdsWithSeparatorsPassthrough() = runBlocking {
        val writer = MemoryWriter()
        val adapter = LmspeedSinkAdapter(writer)
        val tricky = observation(
            observationId = "ext-obs:7:fix:ture:3:a:b:123",
            source = LMSPEED_SOURCE,
            externalModelId = "fix:ture/model:v1",
        )
        adapter.saveObservations(listOf(tricky))
        val kept = writer.written.single()
        assertEquals(LMSPEED_SOURCE, kept.source)
        assertEquals("fix:ture/model:v1", kept.externalModelId)
        assertEquals("ext-obs:7:fix:ture:3:a:b:123", kept.observationId)
    }
}
