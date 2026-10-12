package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PricingScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * X-014 slice-2 PRICE_CHANGE device 驗證（TRACEABILITY X-014／slice-2 spec
 * §6.1 vectors 16-19 store＋§6.2 vectors 20-22 delivery flow）。
 *
 * 真實 in-memory Room＋真實 [EventPipeline]＋真實 [StoreEventSink]／[EventStore]；
 * 只有投遞端是 fake [DeliverySink]（記錄呼叫，不碰 Android 通知 framework）。
 * Fixture 只在本 androidTest；無任何 key／PAT／raw body。
 *
 * Store vectors 16-19 落在此檔而非 JVM 檔：[EventStore] 需真實 [AtlasDatabase]，
 * JVM 無 Robolectric 下無法假造（[StoreEventSinkTest] KDoc 已載明此約束）。
 *
 * syncNewApi→scanPrice 整合級 smoke 未收：[AtlasRepository.syncNewApi] 需
 * 真 adapter＋MockWebServer＋憑證链路（見 NewApiScopedSyncFlowTest 的 VM 級
 * harness），非 trivial；以本檔 20-22 的 pipeline＋store＋delivery 覆蓋代之。
 */
@RunWith(AndroidJUnit4::class)
class PriceChangeDeliveryFlowTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: EventStore

    /** 規則閘仿真：OFF 時如 production sink 第一閘回 true（已處理，不投遞、不重試）。 */
    private class FakeDelivery(
        var ruleEnabled: Boolean = true,
        var succeed: Boolean = true,
    ) : DeliverySink {
        val delivered = mutableListOf<String>()
        val posts = mutableListOf<String>()
        val undelivered = mutableListOf<String>()
        var attempts = 0

        override fun deliver(eventId: String, scopeKey: String, source: String, observedAt: Long): Boolean {
            attempts++
            if (!ruleEnabled) return true
            if (!succeed) return false
            delivered.add(eventId)
            posts.add(scopeKey)
            return true
        }

        override fun markUndelivered(eventId: String) {
            undelivered.add(eventId)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = EventStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun scope(
        account: String? = "a-x014",
        credential: String? = "c-x014",
        group: String? = null,
        operation: String = "CHAT_COMPLETIONS",
        model: String = "m-x014",
    ): String = PricingScope(
        "p-x014",
        account,
        credential,
        model,
        group,
        "e-x014",
        operation,
    ).storageKey()

    private fun priceObservation(
        scopeKey: String,
        fromInput: String? = "1.00",
        fromOutput: String? = "2.00",
        toInput: String? = "0.50",
        toOutput: String? = "1.00",
        fromFixed: String? = null,
        toFixed: String? = null,
        success: Boolean = true,
    ) = EventPipeline.PriceObservation(
        base = EventPipeline.Observation(
            scopeKey = scopeKey,
            source = "newapi/api/pricing",
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

    // ── Store vectors 16-19（mirror EventStoreTest :61-179 模式，直接打真實 store） ──

    // 16. 腿變 → 寫列（mirror EventStoreTest :59-82）。
    @Test
    fun recordPriceTransitionEmitsOnLegChange() = runBlocking {
        val key = scope()
        val emitted = store.recordPriceTransition(
            scopeKey = key,
            fromInput = "1.00",
            fromOutput = "2.00",
            toInput = "0.50",
            toOutput = "1.00",
            observedAt = 1000L,
            source = "newapi/api/pricing",
            evidenceId = "ev-x014-1",
            success = true,
            eventId = "price-x014-1",
        )
        assertNotNull(emitted)
        assertEquals("1.00", emitted!!.fromInput)
        assertEquals("0.50", emitted.toInput)
        assertEquals(1, store.priceHistory(key).size)
        assertEquals("price-x014-1", store.priceHistory(key).single().eventId)
    }

    // 17. 未知側或全等 → null（mirror :85-99 未知、:248-254 全等）。
    @Test
    fun recordPriceTransitionNullOnUnknownSideOrEqual() = runBlocking {
        val key = scope()
        assertNull(
            store.recordPriceTransition(key, null, null, "0.50", "1.00", observedAt = 1000L, source = "OFFICIAL", success = true, eventId = "price-x014-null"),
        )
        assertNull(
            store.recordPriceTransition(key, "1.00", "2.00", "1.00", "2.00", observedAt = 2000L, source = "OFFICIAL", success = true, eventId = "price-x014-same"),
        )
        assertEquals(0, store.priceHistory(key).size)
    }

    // 18. success=false → 不寫（mirror :176-194）。
    @Test
    fun failedSyncWritesNothing() = runBlocking {
        val key = scope()
        assertNull(
            store.recordPriceTransition(key, "1.00", "2.00", "0.50", "1.00", observedAt = 1000L, source = "OFFICIAL", success = false, eventId = "price-x014-fail"),
        )
        assertEquals(0, store.priceHistory(key).size)
    }

    // 19. 跨帳號隔離（mirror :130-151 otherAccount）。
    @Test
    fun scopeIsolationAcrossAccounts() = runBlocking {
        val base = scope(account = "a-x014", credential = "c-x014")
        val otherAccount = scope(account = "a-other", credential = "c-x014")
        store.recordPriceTransition(base, "1.00", "2.00", "0.50", "1.00", observedAt = 1000L, source = "OFFICIAL", success = true, eventId = "price-x014-iso-base")
        store.recordPriceTransition(otherAccount, "1.00", "2.00", "0.09", "0.09", observedAt = 1000L, source = "OFFICIAL", success = true, eventId = "price-x014-iso-acct")
        assertEquals(1, store.priceHistory(base).size)
        assertEquals("0.50", store.priceHistory(base).single().toInput)
        assertEquals(1, store.priceHistory(otherAccount).size)
        assertEquals("0.09", store.priceHistory(otherAccount).single().toInput)
    }

    // ── Device vectors 20-22（spec §6.2：真 Room＋真管線＋fake 投遞） ──

    // 20. 已知腿變 → 落一列 price_events（exact scopeKey／fromInput／toInput）＋投遞帶同 eventId。
    @Test
    fun priceChangePersistsPriceEventAndDelivers() = runBlocking {
        val key = scope()
        val delivery = FakeDelivery(ruleEnabled = true, succeed = true)
        val pipeline = EventPipeline(StoreEventSink(store), delivery)
        val result = pipeline.scanPrice(priceObservation(key))
        assertTrue(result.written)
        assertTrue(result.delivered)
        assertEquals(1, result.deliveryAttempts)
        val rows = store.priceHistory(key)
        assertEquals(1, rows.size)
        assertEquals(key, rows.single().scopeKey)
        assertEquals("1.00", rows.single().fromInput)
        assertEquals("0.50", rows.single().toInput)
        assertEquals(rows.single().eventId, delivery.delivered.single())
    }

    // 21. 規則 OFF → 列照存（append-only 不丟），0 投遞，管線記 delivered＋1 attempt（靜默已處理）。
    @Test
    fun priceChangeRuleOffPersistsButDoesNotNotify() = runBlocking {
        val key = scope()
        val delivery = FakeDelivery(ruleEnabled = false, succeed = true)
        val pipeline = EventPipeline(StoreEventSink(store), delivery)
        val result = pipeline.scanPrice(priceObservation(key))
        assertTrue(result.written)
        assertTrue(result.delivered)
        assertEquals(1, result.deliveryAttempts)
        assertEquals(1, store.priceHistory(key).size)
        assertEquals(0, delivery.posts.size)
        assertEquals(0, delivery.undelivered.size)
    }

    // 22. 拒絕投遞 → 寫＋未送達，attempts=3 上限，markUndelivered 1 次，不 crash。
    @Test
    fun permissionDeniedMarksUndeliveredAfterCap() = runBlocking {
        val key = scope()
        val delivery = FakeDelivery(ruleEnabled = true, succeed = false)
        val pipeline = EventPipeline(StoreEventSink(store), delivery)
        val result = pipeline.scanPrice(priceObservation(key))
        assertTrue(result.written)
        assertFalse(result.delivered)
        assertEquals(EventPipeline.MAX_DELIVERY_ATTEMPTS, result.deliveryAttempts)
        assertEquals(EventPipeline.MAX_DELIVERY_ATTEMPTS, delivery.attempts)
        assertEquals(1, delivery.undelivered.size)
        assertEquals(EventPipeline.REASON_DELIVERY_EXHAUSTED, result.reason)
    }

    // F1 修復後的 D-FIXED-1 邊界 pin：fixed-only 變經真管線→真 store 時，
    // 管線閘放行（written＋delivered），store gates 同樣納入 fixed 腿（不持久化
    // 於列，列上腿欄為 null），事件照樣落列——「有通知必有列」append-only
    // 不變量（reviewer F1；X-014 slice 2）。
    @Test
    fun fixedOnlyChangeDeliversAndPersistsRow() = runBlocking {
        val key = scope()
        val delivery = FakeDelivery(ruleEnabled = true, succeed = true)
        val pipeline = EventPipeline(StoreEventSink(store), delivery)
        val result = pipeline.scanPrice(
            priceObservation(key, fromInput = null, fromOutput = null, toInput = null, toOutput = null, fromFixed = "5", toFixed = "0"),
        )
        assertTrue(result.written)
        assertTrue(result.delivered)
        val history = store.priceHistory(key)
        assertEquals(1, history.size)
        // 列上腿欄原樣為 null（fixed 不持久化）；fromState/toState 未帶入。
        assertNull(history.single().fromInput)
        assertNull(history.single().toInput)
    }

    // W2 production 文案 pin：真字串資源以 scopeKey 格式化、不含腿值（X-003）。
    @Test
    fun priceChangeBodyResourceCarriesScopeKeyOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = scope()
        val body = context.getString(R.string.notification_price_change_body, key)
        assertTrue(body.contains(key))
        assertFalse(body.contains("1.00"))
    }
}
