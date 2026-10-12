package dev.modelatlas.app.data

import dev.modelatlas.app.notifications.NotificationManagerDeliverySink
import dev.modelatlas.app.notifications.NotificationRule
import dev.modelatlas.app.notifications.NotificationSettingsState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-014 slice-2 PRICE_CHANGE 管線／投遞／預設值 JVM 驗證（TRACEABILITY X-014／
 * slice-2 spec §6.1 vectors 1-15＋§6.3 default-OFF）。
 *
 * Fakes 與 harness 形狀 mirror [EventPipelineTest]（:17-66）與
 * [NotificationManagerDeliverySinkTest]（harness :30-59）；兩檔皆只讀、未改。
 * 全為記憶體內 fake，不碰 DB／網路；樣本為本地構造值，非產品資料、非 mock 框架；
 * 無任何 key／PAT／raw body。
 *
 * Store 層 vectors（spec §6.1 vectors 16-19）不在此檔：[EventStore] 需真實
 * [AtlasDatabase]，JVM 無 Robolectric 下無法假造（[StoreEventSinkTest] KDoc
 * 已載明此約束）；該四向量落在 device [PriceChangeDeliveryFlowTest]，
 * 以真實 in-memory Room 覆蓋。
 */
class PriceChangePipelineTest {

    // ── Fakes（mirror EventPipelineTest :17-70） ──

    private class FakeSink : EventSink {
        data class PriceCall(
            val eventId: String,
            val scopeKey: String,
            val fromInput: String?,
            val fromOutput: String?,
            val toInput: String?,
            val toOutput: String?,
            val fromFixed: String?,
            val toFixed: String?,
            val source: String,
        )

        val priceEvents = mutableListOf<PriceCall>()
        val providerEvents = mutableListOf<String>()

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
            // D-FIXED-1：與擴展後介面同簽（override 不帶預設值，Kotlin 規則）；
            // fake 完整記錄 fixed 腿，供相等閘向量斷言。
            fromFixed: String?,
            toFixed: String?,
        ) {
            priceEvents.add(
                PriceCall(eventId, scopeKey, fromInput, fromOutput, toInput, toOutput, fromFixed, toFixed, source),
            )
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
            providerEvents.add(eventId)
        }

        // X-014 slice-3: interface member added with scanQuota; signature-only
        // match (no quota vectors in this price file — EventQuotaScanTest owns those).
        val quotaEvents = mutableListOf<String>()

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
        fromFixed: String? = null,
        toFixed: String? = null,
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
        fromFixed = fromFixed,
        toFixed = toFixed,
    )

    // ── Pipeline vectors 1-9（spec §6.1） ──

    // 1. 新舊皆已知且不等 → 寫事件＋投遞成功（mirror EventPipelineTest V1 :103-115）。
    @Test
    fun priceChangeBothSidesKnownWritesAndDelivers() = runBlocking {
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

    // 2. X-001：from 全 null（含 fixed）→ UNKNOWN_SIDE，不寫（mirror V2 :118-125）。
    @Test
    fun unknownFromSideEmitsNothing() = runBlocking {
        val sink = FakeSink()
        val pipeline = EventPipeline(sink, FakeDelivery())
        val result = pipeline.scanPrice(priceObservation(fromInput = null, fromOutput = null, fromFixed = null))
        assertFalse(result.written)
        assertFalse(result.delivered)
        assertEquals(0, sink.priceEvents.size)
        assertEquals(EventPipeline.REASON_UNKNOWN_SIDE, result.reason)
    }

    // 3. X-001 反方向：to 全 null → UNKNOWN_SIDE，不寫。
    @Test
    fun unknownToSideEmitsNothing() = runBlocking {
        val sink = FakeSink()
        val pipeline = EventPipeline(sink, FakeDelivery())
        val result = pipeline.scanPrice(priceObservation(toInput = null, toOutput = null, toFixed = null))
        assertFalse(result.written)
        assertFalse(result.delivered)
        assertEquals(0, sink.priceEvents.size)
        assertEquals(EventPipeline.REASON_UNKNOWN_SIDE, result.reason)
    }

    // 4. X-006：success=false → SYNC_FAILURE，sink 0、delivery 0（mirror V3 :127-138）。
    @Test
    fun syncFailureEmitsNothing() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val pipeline = EventPipeline(sink, delivery)
        val result = pipeline.scanPrice(priceObservation(success = false))
        assertFalse(result.written)
        assertEquals(0, sink.priceEvents.size)
        assertEquals(0, delivery.delivered.size)
        assertEquals(0, delivery.attempts)
        assertEquals(EventPipeline.REASON_SYNC_FAILURE, result.reason)
    }

    // 5. 三腿全等 → NO_TRANSITION，不重複轟炸（mirror V4 :141-151，另加 fixed 腿）。
    @Test
    fun identicalLegsEmitsNothing() = runBlocking {
        val sink = FakeSink()
        val pipeline = EventPipeline(sink, FakeDelivery())
        val result = pipeline.scanPrice(
            priceObservation(
                fromInput = "1.00", fromOutput = "2.00", toInput = "1.00", toOutput = "2.00",
                fromFixed = "5", toFixed = "5",
            ),
        )
        assertFalse(result.written)
        assertEquals(0, sink.priceEvents.size)
        assertEquals(EventPipeline.REASON_NO_TRANSITION, result.reason)
    }

    // 6. D-FIXED-1 證明：token 腿全 null、fixed 5→0 → 寫＋投遞（W-pipe 前失敗、後通過）。
    @Test
    fun fixedOnlyChangeWritesAndDelivers() = runBlocking {
        val sink = FakeSink()
        val delivery = FakeDelivery()
        val pipeline = EventPipeline(sink, delivery)
        val result = pipeline.scanPrice(
            priceObservation(
                fromInput = null, fromOutput = null, toInput = null, toOutput = null,
                fromFixed = "5", toFixed = "0",
            ),
        )
        assertTrue(result.written)
        assertTrue(result.delivered)
        assertEquals(1, result.deliveryAttempts)
        assertEquals(1, sink.priceEvents.size)
        assertEquals("5", sink.priceEvents.single().fromFixed)
        assertEquals("0", sink.priceEvents.single().toFixed)
        assertNull(result.reason)
    }

    // 7a. 腿獨立：fixed 相等但 token 變 → 寫。
    @Test
    fun fixedEqualButTokenChangeWrites() = runBlocking {
        val sink = FakeSink()
        val pipeline = EventPipeline(sink, FakeDelivery())
        val result = pipeline.scanPrice(
            priceObservation(fromFixed = "5", toFixed = "5"),
        )
        assertTrue(result.written)
        assertEquals(1, sink.priceEvents.size)
        assertNull(result.reason)
    }

    // 7b. 腿獨立：token 相等但 fixed 變 → 寫。
    @Test
    fun tokenEqualButFixedChangeWrites() = runBlocking {
        val sink = FakeSink()
        val pipeline = EventPipeline(sink, FakeDelivery())
        val result = pipeline.scanPrice(
            priceObservation(
                fromInput = "1.00", fromOutput = "2.00", toInput = "1.00", toOutput = "2.00",
                fromFixed = "5", toFixed = "0",
            ),
        )
        assertTrue(result.written)
        assertEquals(1, sink.priceEvents.size)
        assertNull(result.reason)
    }

    // 8. 投遞恆敗 → 寫＋未送達，attempts=3=MAX，重試耗盡記未投遞（mirror V7 :186-198）。
    @Test
    fun deliveryRetriesThenMarksUndelivered() = runBlocking {
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

    // 9. 前兩次失敗第三次成功 → 停、不重複投遞（mirror V8 :200-214）。
    @Test
    fun retrySucceedsWithoutDuplicate() = runBlocking {
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

    // ── Sink harness（mirror NotificationManagerDeliverySinkTest :30-59，gate 改 PRICE_CHANGE） ──

    private class PostRecorder {
        var calls: Int = 0
        var lastTitle: String? = null
        var lastBody: String? = null

        fun record(title: String, body: String) {
            calls++
            lastTitle = title
            lastBody = body
        }
    }

    private class PriceHarness(
        masterEnabled: Boolean,
        enabledRules: Set<NotificationRule>,
        private val hasPermission: Boolean = true,
        private val areNotificationsEnabled: Boolean = true,
        private val channelImportance: Int? = IMPORTANCE_DEFAULT,
        private val throwOnPost: Boolean = false,
    ) {
        val recorder = PostRecorder()
        private val state = NotificationSettingsState(
            masterEnabled = masterEnabled,
            enabledRules = enabledRules,
        )

        val sink = NotificationManagerDeliverySink(
            isRuleEnabled = { state.enabled(NotificationRule.PRICE_CHANGE) },
            hasPermission = { hasPermission },
            areNotificationsEnabled = { areNotificationsEnabled },
            channelImportance = { channelImportance },
            titleText = { PRICE_CHANGE_TITLE },
            bodyText = { scopeKey -> "$scopeKey $PRICE_CHANGE_BODY" },
            postNotification = { _, title, body ->
                if (throwOnPost) throw SecurityException("fake dispatch refusal")
                recorder.record(title, body)
            },
        )

        fun deliver(): Boolean =
            sink.deliver("price-fake-event", "price-fake-scope", "newapi/api/pricing", 0L)
    }

    // 10. master OFF → 已處理（true），0 投遞（mirror :62-66）。
    @Test
    fun masterOff_isProcessedWithoutNotifying() {
        val harness = PriceHarness(masterEnabled = false, enabledRules = setOf(NotificationRule.PRICE_CHANGE))
        assertTrue(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    // 11. master ON 但 PRICE_CHANGE 未啟 → 已處理（true），0 投遞（mirror :69-73）。
    @Test
    fun priceChangeRuleOff_isProcessedWithoutNotifying() {
        val harness = PriceHarness(masterEnabled = true, enabledRules = emptySet())
        assertTrue(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    // 12. 規則隔離證明：只開 OUTAGE_RECOVERY 時 PRICE_CHANGE 實例靜默（true，0 投遞）。
    @Test
    fun outageRuleOnButPriceChangeOff_isSilent() {
        val harness = PriceHarness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.OUTAGE_RECOVERY),
        )
        assertTrue(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    // 13a. 缺權限 → false，0 投遞（mirror :76-84）。
    @Test
    fun missingPermission_isNotDelivered() {
        val harness = PriceHarness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.PRICE_CHANGE),
            hasPermission = false,
        )
        assertFalse(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    // 13b. 通知總開關 OFF → false，0 投遞（mirror :87-95）。
    @Test
    fun notificationsGloballyOff_isNotDelivered() {
        val harness = PriceHarness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.PRICE_CHANGE),
            areNotificationsEnabled = false,
        )
        assertFalse(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    // 13c. channel importance NONE → false，0 投遞（mirror :97-106）。
    @Test
    fun channelImportanceNone_isNotDelivered() {
        val harness = PriceHarness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.PRICE_CHANGE),
            channelImportance = IMPORTANCE_NONE,
        )
        assertFalse(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    // 13d. 投遞拋出 → false 且不拋（mirror :122-131）。
    @Test
    fun dispatchFailure_isNotDeliveredAndDoesNotThrow() {
        val harness = PriceHarness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.PRICE_CHANGE),
            throwOnPost = true,
        )
        assertFalse(harness.deliver())
        assertEquals(0, harness.recorder.calls)
    }

    // 14. 授權 → 投遞一次；title 為價格文案、body 含 scopeKey、無 secret 痕跡（X-003，mirror :109-120）。
    @Test
    fun granted_notifiesOnceWithPriceChangeCopy() {
        val harness = PriceHarness(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.PRICE_CHANGE),
            channelImportance = IMPORTANCE_DEFAULT,
        )
        assertTrue(harness.deliver())
        assertEquals(1, harness.recorder.calls)
        assertEquals(PRICE_CHANGE_TITLE, harness.recorder.lastTitle)
        assertTrue(harness.recorder.lastBody!!.contains("price-fake-scope"))
        // X-003：body 只有 scopeKey＋公開文案，不得含 key／PAT／header 痕跡。
        for (secret in listOf("sk-", "Bearer", "PAT", "Authorization")) {
            assertFalse(harness.recorder.lastBody!!.contains(secret))
        }
    }

    // 15. markUndelivered 為 no-op（mirror :145-149）。
    @Test
    fun markUndelivered_isNoOp() {
        val harness = PriceHarness(masterEnabled = true, enabledRules = setOf(NotificationRule.PRICE_CHANGE))
        harness.sink.markUndelivered("price-fake-event")
        assertEquals(0, harness.recorder.calls)
    }

    // §6.3 default-OFF：state 預設 master 關＋零規則，PRICE_CHANGE 永不預設啟用。
    @Test
    fun settingsDefault_isMasterOffWithNoRules() {
        val state = NotificationSettingsState()
        assertFalse(state.masterEnabled)
        assertTrue(state.enabledRules.isEmpty())
        assertFalse(state.enabled(NotificationRule.PRICE_CHANGE))
        assertFalse(state.enabled(NotificationRule.OUTAGE_RECOVERY))
    }

    private companion object {
        // android.app.NotificationManager 的 importance 常數以純值重現，
        // 避免 JVM 測試觸及 framework（值與 framework 一致：NONE=0／DEFAULT=3）。
        const val IMPORTANCE_NONE = 0
        const val IMPORTANCE_DEFAULT = 3

        // PRICE_CHANGE 文案以測試常量重現 production when(rule) 路由語意
        // （production 真字串由 device 端 priceChangeBodyResourceCarriesScopeKeyOnly 覆蓋）。
        const val PRICE_CHANGE_TITLE = "price change title"
        const val PRICE_CHANGE_BODY = "price changed"
    }
}
