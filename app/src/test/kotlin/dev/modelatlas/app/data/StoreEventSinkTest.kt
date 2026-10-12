package dev.modelatlas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-014 [StoreEventSink] 的 JVM 驗證。
 *
 * [EventStore] 需要真實 [AtlasDatabase]，無法在無 Robolectric 的 JVM 單元測試中假造；
 * 因此本測試只驗證 sink 承載的「OUTAGE 失敗證據映射」純邏輯——
 * 即 [EventStore.recordProviderHealth] 的 OUTAGE 門檻（decided／successes）由 sink 提供之值。
 * 實際寫入路徑由 EventPipelineTest（記憶體 fake sink）與 device-tests 覆蓋。
 * 全部為本地構造值，無任何 key／PAT／raw body。
 */
class StoreEventSinkTest {

    @Test
    fun outage_carriesDecisiveEvidence() {
        assertEquals(
            EventStore.MIN_DECISIVE_FOR_OUTAGE,
            StoreEventSink.outageDecidedFor(EventStore.PROVIDER_EVENT_OUTAGE),
        )
        assertEquals(0, StoreEventSink.outageSuccessesFor(EventStore.PROVIDER_EVENT_OUTAGE))
    }

    @Test
    fun outageEvidence_satisfiesOutageThreshold() {
        val decided = StoreEventSink.outageDecidedFor(EventStore.PROVIDER_EVENT_OUTAGE)
        val successes = StoreEventSink.outageSuccessesFor(EventStore.PROVIDER_EVENT_OUTAGE)
        // isOutageEvidence 為 EventStore 實例方法；此處以同一門檻常數重現其判定條件。
        assertEquals(EventStore.MIN_DECISIVE_FOR_OUTAGE, decided)
        assertEquals(0, successes)
        assertTrue(decided >= EventStore.MIN_DECISIVE_FOR_OUTAGE)
        assertTrue(successes.toDouble() / decided.toDouble() < EventStore.OUTAGE_SUCCESS_RATE_BELOW)
    }

    @Test
    fun singleFailureEvidence_isNeverEnoughForOutage() {
        // decided=1 永不產 OUTAGE（單次失敗不產中斷事件／不通知）。
        assertEquals(1, EventStore.MIN_DECISIVE_FOR_OUTAGE - 1)
        assertFalse(
            (EventStore.MIN_DECISIVE_FOR_OUTAGE - 1) >= EventStore.MIN_DECISIVE_FOR_OUTAGE &&
                0.toDouble() < EventStore.OUTAGE_SUCCESS_RATE_BELOW,
        )
    }

    @Test
    fun recovery_carriesNoSampleThreshold() {
        assertEquals(0, StoreEventSink.outageDecidedFor(EventStore.PROVIDER_EVENT_RECOVERY))
        assertEquals(0, StoreEventSink.outageSuccessesFor(EventStore.PROVIDER_EVENT_RECOVERY))
    }

    @Test
    fun notice_carriesNoSampleThreshold() {
        assertEquals(0, StoreEventSink.outageDecidedFor(EventStore.PROVIDER_EVENT_NOTICE))
        assertEquals(0, StoreEventSink.outageSuccessesFor(EventStore.PROVIDER_EVENT_NOTICE))
    }

    @Test
    fun unknownKind_carriesNoSampleThreshold() {
        assertEquals(0, StoreEventSink.outageDecidedFor("NOT_A_KIND"))
        assertEquals(0, StoreEventSink.outageSuccessesFor("NOT_A_KIND"))
    }

    @Test
    fun outageMapping_onlyMatchesCanonicalOutageKind() {
        val kinds = EventStore.PROVIDER_EVENT_KINDS
        kinds.forEach { kind ->
            val expected = if (kind == EventStore.PROVIDER_EVENT_OUTAGE) {
                EventStore.MIN_DECISIVE_FOR_OUTAGE
            } else {
                0
            }
            assertEquals(expected, StoreEventSink.outageDecidedFor(kind))
        }
    }
}