package dev.modelatlas.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-014 QUOTA_LOW pipeline vectors (TRACEABILITY X-014; locked spec §6.4).
 * In-memory fakes only; the delivery fake is rule-agnostic, so these tests verify
 * pipeline dispatch attempts, not NotificationRule evaluation or user permission.
 */
class EventQuotaScanTest {

    private data class QuotaEvent(
        val eventId: String,
        val scopeKey: String,
        val fromState: String?,
        val toState: String?,
        val observedAt: Long,
        val source: String,
        val evidenceId: String?,
    )

    private class FakeSink : EventSink {
        val quotaEvents = mutableListOf<QuotaEvent>()

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
            fromFixed: String?,
            toFixed: String?,
        ) = Unit

        override suspend fun writeProviderEvent(
            eventId: String,
            scopeKey: String,
            kind: String,
            observedAt: Long,
            source: String,
            evidenceId: String?,
            detail: String?,
        ) = Unit

        override suspend fun writeQuotaEvent(
            eventId: String,
            scopeKey: String,
            fromState: String?,
            toState: String?,
            observedAt: Long,
            source: String,
            evidenceId: String?,
        ) {
            quotaEvents += QuotaEvent(
                eventId = eventId,
                scopeKey = scopeKey,
                fromState = fromState,
                toState = toState,
                observedAt = observedAt,
                source = source,
                evidenceId = evidenceId,
            )
        }
    }

    private class FakeDelivery(
        private val succeed: Boolean = true,
    ) : DeliverySink {
        val delivered = mutableListOf<String>()
        val undelivered = mutableListOf<String>()
        var attempts = 0

        override fun deliver(eventId: String, scopeKey: String, source: String, observedAt: Long): Boolean {
            attempts++
            if (succeed) delivered += eventId
            return succeed
        }

        override fun markUndelivered(eventId: String) {
            undelivered += eventId
        }
    }

    private fun quotaObservation(
        fromState: String? = "QUOTA_AVAILABLE",
        toState: String? = "QUOTA_LOW",
        success: Boolean = true,
        scopeKey: String = "provider|account|credential|model|group|endpoint|operation",
        source: String = "OFFICIAL",
        evidenceId: String? = "evidence-1",
        observedAt: Long = 1234L,
    ) = EventPipeline.QuotaObservation(
        base = EventPipeline.Observation(
            scopeKey = scopeKey,
            source = source,
            observedAt = observedAt,
            success = success,
            evidenceId = evidenceId,
        ),
        fromState = fromState,
        toState = toState,
    )

    @Test
    fun V1_availableToLowWritesAndAttemptsDelivery() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val result = EventPipeline(sink, delivery).scanQuota(quotaObservation())

        assertTrue(result.written)
        assertTrue(result.delivered)
        assertEquals(1, result.deliveryAttempts)
        assertEquals(1, sink.quotaEvents.size)
        assertEquals(1, delivery.attempts)
        assertNull(result.reason)
    }

    @Test
    fun V2_nullUnknownPriorStateIsRejected() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val result = EventPipeline(sink, delivery).scanQuota(quotaObservation(fromState = null))

        assertFalse(result.written)
        assertEquals(EventPipeline.REASON_UNKNOWN_SIDE, result.reason)
        assertTrue(sink.quotaEvents.isEmpty())
        assertEquals(0, delivery.attempts)
    }

    @Test
    fun V3_stringUnknownPriorStateIsRejected() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val result = EventPipeline(sink, delivery).scanQuota(quotaObservation(fromState = "UNKNOWN"))

        assertFalse(result.written)
        assertEquals(EventPipeline.REASON_UNKNOWN_SIDE, result.reason)
        assertTrue(sink.quotaEvents.isEmpty())
        assertEquals(0, delivery.attempts)
    }

    @Test
    fun V4_repeatedLowStateDoesNotWriteOrDispatch() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val result = EventPipeline(sink, delivery).scanQuota(
            quotaObservation(fromState = "QUOTA_LOW", toState = "QUOTA_LOW"),
        )

        assertFalse(result.written)
        assertEquals(EventPipeline.REASON_NO_TRANSITION, result.reason)
        assertTrue(sink.quotaEvents.isEmpty())
        assertEquals(0, delivery.attempts)
    }

    @Test
    fun V5_syncFailureDoesNotWriteOrDispatch() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val result = EventPipeline(sink, delivery).scanQuota(quotaObservation(success = false))

        assertFalse(result.written)
        assertEquals(EventPipeline.REASON_SYNC_FAILURE, result.reason)
        assertTrue(sink.quotaEvents.isEmpty())
        assertEquals(0, delivery.attempts)
    }

    @Test
    fun V6_lowToAvailableWritesAndDelegatesDeliveryRuleToSink() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val result = EventPipeline(sink, delivery).scanQuota(
            quotaObservation(fromState = "QUOTA_LOW", toState = "QUOTA_AVAILABLE"),
        )

        assertTrue(result.written)
        assertTrue(result.delivered)
        assertEquals(1, result.deliveryAttempts)
        assertEquals(1, sink.quotaEvents.size)
        assertEquals("QUOTA_AVAILABLE", sink.quotaEvents.single().toState)
        assertEquals(1, delivery.attempts)
    }

    @Test
    fun V7_deliveryFailureStopsAtRetryCapAndMarksUndelivered() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery(succeed = false)
        val result = EventPipeline(sink, delivery).scanQuota(quotaObservation())

        assertTrue(result.written)
        assertFalse(result.delivered)
        assertEquals(EventPipeline.MAX_DELIVERY_ATTEMPTS, result.deliveryAttempts)
        assertEquals(EventPipeline.MAX_DELIVERY_ATTEMPTS, delivery.attempts)
        assertEquals(1, delivery.undelivered.size)
        assertEquals(EventPipeline.REASON_DELIVERY_EXHAUSTED, result.reason)
        assertEquals(1, sink.quotaEvents.size)
    }

    @Test
    fun V8_userProvenanceIsPreservedVerbatim() = runBlocking {
        // F-04（review）：生產 manual quota 的真 provenance 是 "USER"
        // （NewApiSnapshotStore.saveManualQuota 強制 source="USER"）——用真值斷言。
        val sink = FakeSink()
        EventPipeline(sink, FakeDelivery()).scanQuota(quotaObservation(source = "USER"))

        assertEquals("USER", sink.quotaEvents.single().source)
    }

    @Test
    fun V9_eventsRemainIsolatedByExactScopeKey() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val pipeline = EventPipeline(sink, delivery)

        val first = pipeline.scanQuota(quotaObservation(scopeKey = "scope-account-a"))
        val second = pipeline.scanQuota(quotaObservation(scopeKey = "scope-account-b"))

        assertTrue(first.written)
        assertTrue(second.written)
        assertEquals(listOf("scope-account-a", "scope-account-b"), sink.quotaEvents.map { it.scopeKey })
        assertEquals(2, sink.quotaEvents.map { it.eventId }.toSet().size)
        assertEquals(2, delivery.attempts)
    }

    @Test
    fun V10_nonLowDestinationStillWritesAndPipelineHasNoRuleGate() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val result = EventPipeline(sink, delivery).scanQuota(
            quotaObservation(fromState = "QUOTA_AVAILABLE", toState = "QUOTA_EXHAUSTED"),
        )

        assertTrue(result.written)
        assertTrue(result.delivered)
        assertEquals(1, sink.quotaEvents.size)
        assertEquals("QUOTA_EXHAUSTED", sink.quotaEvents.single().toState)
        assertEquals(1, delivery.attempts)
    }
}
