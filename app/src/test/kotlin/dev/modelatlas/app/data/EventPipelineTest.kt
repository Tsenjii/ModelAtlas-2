package dev.modelatlas.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-014 EventPipeline 驗證（8 個；TRACEABILITY X-014／PRODUCT_SPEC §10 pipeline／§16）。
 * 全為記憶體內 fake，不碰 DB／網路；樣本為本地構造值，非產品資料、非 mock 框架；
 * 無任何 key／PAT／raw body。
 */
class EventPipelineTest {

    private class FakeSink : EventSink {
        val priceEvents = mutableListOf<String>()
        val quotaEvents = mutableListOf<String>()
        val providerEvents = mutableListOf<Triple<String, String, String>>() // eventId, kind, source

        override suspend fun writePriceEvent(
            eventId: String,
            scopeKey: String,
            fromInput: String?,
            fromOutput: String?,
            toInput: String?,
            toOutput: String?,
            observedAt: Long,
            source: String,
            evidenceId: String?,
            // D-FIXED-1: signature-only match for the extended interface; the
            // fake keeps recording only the event id (vectors' expectations unchanged).
            fromFixed: String?,
            toFixed: String?,
        ) {
            priceEvents.add(eventId)
        }

        override suspend fun writeProviderEvent(
            eventId: String,
            scopeKey: String,
            kind: String,
            observedAt: Long,
            source: String,
            evidenceId: String?,
            detail: String?,
        ) {
            providerEvents.add(Triple(eventId, kind, source))
        }

        override suspend fun writeQuotaEvent(
            eventId: String,
            scopeKey: String,
            fromState: String?,
            toState: String?,
            observedAt: Long,
            source: String,
            evidenceId: String?,
        ) {
            quotaEvents.add(eventId)
        }
    }

    private class FakeDelivery(
        private val failFirst: Int = 0,
        private val succeed: Boolean = true,
    ) : DeliverySink {
        val delivered = mutableListOf<String>()
        val undelivered = mutableListOf<String>()
        var attempts = 0

        override fun deliver(eventId: String, scopeKey: String, source: String, observedAt: Long): Boolean {
            attempts++
            val ok = succeed && attempts > failFirst
            if (ok) delivered.add(eventId)
            return ok
        }

        override fun markUndelivered(eventId: String) {
            undelivered.add(eventId)
        }
    }

    private fun priceObservation(
        fromInput: String? = "1.00",
        fromOutput: String? = "2.00",
        toInput: String? = "0.50",
        toOutput: String? = "1.00",
        success: Boolean = true,
        source: String = "OBSERVED",
    ) = EventPipeline.PriceObservation(
        base = EventPipeline.Observation(
            scopeKey = "p1|a1|cred1|m1|null|null|null|CHAT",
            source = source,
            observedAt = 1000L,
            success = success,
        ),
        fromInput = fromInput,
        fromOutput = fromOutput,
        toInput = toInput,
        toOutput = toOutput,
    )

    private fun healthObservation(
        consecutiveFailures: Int,
        success: Boolean = true,
        source: String = "OBSERVED",
    ) = EventPipeline.HealthObservation(
        base = EventPipeline.Observation(
            scopeKey = "p1",
            source = source,
            observedAt = 2000L,
            success = success,
        ),
        consecutiveFailures = consecutiveFailures,
    )

    // 1. 新舊皆有已知值才寫事件，寫完投遞成功。
    @Test
    fun priceTransitionWithBothSidesKnownWritesAndDelivers() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val pipeline = EventPipeline(sink, delivery)
        val result = pipeline.scanPrice(priceObservation())
        assertTrue(result.written)
        assertTrue(result.delivered)
        assertEquals(1, result.deliveryAttempts)
        assertEquals(1, sink.priceEvents.size)
        assertEquals(1, delivery.delivered.size)
        assertNull(result.reason)
    }

    // 2. X-001：舊值任一側全 null（未知）不發事件；未知永不冒充已知。
    @Test
    fun unknownSideNeverBecomesKnownAndEmitsNoEvent() = runBlocking {
        val pipeline = EventPipeline(FakeSink(), FakeDelivery())
        val result = pipeline.scanPrice(priceObservation(fromInput = null, fromOutput = null))
        assertFalse(result.written)
        assertFalse(result.delivered)
        assertEquals(EventPipeline.REASON_UNKNOWN_SIDE, result.reason)
    }

    // 3. X-006：sync failure 不掃描、不寫事件、不投遞（保留 last-good）。
    @Test
    fun syncFailureEmitsNoEventAndDeliversNothing() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val pipeline = EventPipeline(sink, delivery)
        val result = pipeline.scanPrice(priceObservation(success = false))
        assertFalse(result.written)
        assertEquals(0, sink.priceEvents.size)
        assertEquals(0, delivery.delivered.size)
        assertEquals(EventPipeline.REASON_SYNC_FAILURE, result.reason)
    }

    // 4. 無轉換（新舊相等）不寫事件（FREE_NOW→FREE_NOW 不重複轟炸）。
    @Test
    fun noTransitionEmitsNoEventEvenForRepeatedFreeNow() = runBlocking {
        val sink = FakeSink()
        val pipeline = EventPipeline(sink, FakeDelivery())
        val result = pipeline.scanPrice(
            priceObservation(fromInput = null, fromOutput = "FREE", toInput = null, toOutput = "FREE"),
        )
        assertFalse(result.written)
        assertEquals(0, sink.priceEvents.size)
        assertEquals(EventPipeline.REASON_NO_TRANSITION, result.reason)
    }

    // 5. 單次失敗不產 OUTAGE；連續兩次失敗才產 OUTAGE（§1 同源語意）。
    @Test
    fun outageRequiresConsecutiveFailuresNotSingle() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val pipeline = EventPipeline(sink, delivery)
        val single = pipeline.scanHealth(healthObservation(consecutiveFailures = 1), wasOutaged = false)
        assertFalse(single.written)
        assertEquals(EventPipeline.REASON_NO_TRANSITION, single.reason)
        val outage = pipeline.scanHealth(healthObservation(consecutiveFailures = 2), wasOutaged = false)
        assertTrue(outage.written)
        assertEquals(1, sink.providerEvents.size)
        assertEquals(EventStore.PROVIDER_EVENT_OUTAGE, sink.providerEvents.single().second)
        assertTrue(outage.delivered)
    }

    // 6. 成功樣本在先前 OUTAGE 之後判 RECOVERY；source 原樣攜帶不升級（X-005）。
    @Test
    fun recoveryEmitsAfterOutageWithProvenancePreserved() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val pipeline = EventPipeline(sink, delivery)
        val recovery = pipeline.scanHealth(
            healthObservation(consecutiveFailures = 0, source = "MANUAL"),
            wasOutaged = true,
        )
        assertTrue(recovery.written)
        assertEquals(EventStore.PROVIDER_EVENT_RECOVERY, sink.providerEvents.single().second)
        assertEquals("MANUAL", sink.providerEvents.single().third)
        assertEquals(1, delivery.delivered.size)
    }

    // 7. 投遞重試至多 MAX_DELIVERY_ATTEMPTS 次，超過標記未投遞（不假裝已送、不無界重試）。
    @Test
    fun deliveryRetriesUpToCapThenMarksUndelivered() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery(succeed = false)
        val pipeline = EventPipeline(sink, delivery)
        val result = pipeline.scanPrice(priceObservation())
        assertTrue(result.written)
        assertFalse(result.delivered)
        assertEquals(EventPipeline.MAX_DELIVERY_ATTEMPTS, result.deliveryAttempts)
        assertEquals(EventPipeline.MAX_DELIVERY_ATTEMPTS, delivery.attempts)
        assertEquals(1, delivery.undelivered.size)
        assertEquals(EventPipeline.REASON_DELIVERY_EXHAUSTED, result.reason)
    }

    // 8. 首次失敗後重試成功即停，不重複投遞同一事件。
    @Test
    fun retrySucceedsOnLaterAttemptWithoutDuplicateDelivery() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery(failFirst = 2)
        val pipeline = EventPipeline(sink, delivery)
        val result = pipeline.scanPrice(priceObservation())
        assertTrue(result.written)
        assertTrue(result.delivered)
        assertEquals(3, result.deliveryAttempts)
        assertEquals(3, delivery.attempts)
        assertEquals(1, delivery.delivered.size)
        assertEquals(0, delivery.undelivered.size)
        assertNull(result.reason)
    }
}
