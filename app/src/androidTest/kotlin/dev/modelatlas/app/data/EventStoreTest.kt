package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.PricingScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * X-018 事件層驗收（Room v10；TRACEABILITY X-018／PRODUCT_SPEC §10末端
 * `… → Snapshot → Event`／§11 History／§14 Notices-Events可讀）。
 *
 * 只用本地 Room＋假 id；不連線、不帶 secret、不寫任何來源 URL／endpoint、
 * 不當產品資料。Fixture 只在本 androidTest。
 */
@RunWith(AndroidJUnit4::class)
class EventStoreTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: EventStore

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
        account: String? = "a-x018",
        credential: String? = "c-x018",
        group: String? = null,
        operation: String = "CHAT_COMPLETIONS",
        model: String = "m-x018",
    ): String = PricingScope(
        providerId = "p-x018",
        accountId = account,
        credentialId = credential,
        modelId = model,
        group = group,
        endpointId = "e-x018",
        operation = operation,
    ).storageKey()

    @Test
    fun priceTransitionEmitsEventWithBothKnownValues() = runBlocking {
        val key = scope()
        val emitted = store.recordPriceTransition(
            scopeKey = key,
            fromInput = "0.001",
            fromOutput = "0.002",
            toInput = "0.002",
            toOutput = "0.004",
            observedAt = 1000L,
            source = "OFFICIAL",
            evidenceId = "ev-x018-1",
            success = true,
            eventId = "price-x018-1",
        )
        assertNotNull(emitted)
        assertEquals("0.001", emitted!!.fromInput)
        assertEquals("0.002", emitted.toInput)
        assertEquals("0.002", emitted.fromOutput)
        assertEquals("0.004", emitted.toOutput)
        assertEquals("OFFICIAL", emitted.source)
        assertEquals("ev-x018-1", emitted.evidenceId)
        assertEquals(1, store.priceHistory(key).size)
        assertEquals("price-x018-1", store.priceHistory(key).single().eventId)
    }

    @Test
    fun unknownNeverBecomesZeroInEventPayload() = runBlocking {
        val key = scope()
        // 舊值 null → 不發事件（BACKLOG X-018 驗收第 1 項後半）。
        val noEmit = store.recordPriceTransition(
            scopeKey = key,
            fromInput = null,
            fromOutput = null,
            toInput = "0.002",
            toOutput = "0.004",
            observedAt = 1000L,
            source = "OFFICIAL",
            success = true,
            eventId = "price-x018-null-old",
        )
        assertNull(noEmit)
        // 已發事件的 null 欄保持 null，永不變 "0"（X-001）。
        val emitted = store.recordPriceTransition(
            scopeKey = key,
            fromInput = "0.001",
            fromOutput = null,
            toInput = "0.002",
            toOutput = null,
            observedAt = 2000L,
            source = "OFFICIAL",
            success = true,
            eventId = "price-x018-null-field",
        )
        assertNotNull(emitted)
        assertNull(emitted!!.fromOutput)
        assertNull(emitted.toOutput)
        assertTrue(emitted.fromInput != "0" && emitted.toInput != "0")
        val quotaNull = store.recordQuotaTransition(
            scopeKey = key,
            fromState = null,
            toState = "QUOTA_AVAILABLE",
            observedAt = 3000L,
            source = "OBSERVED",
            success = true,
            eventId = "quota-x018-null-old",
        )
        assertNull(quotaNull)
        assertEquals(0, store.quotaHistory(key).size)
    }

    @Test
    fun eventsAreScopeIsolatedSevenDimensions() = runBlocking {
        val base = scope(account = "a-x018", credential = "c-x018", group = null, operation = "CHAT_COMPLETIONS")
        val otherAccount = scope(account = "a-other", credential = "c-x018", group = null, operation = "CHAT_COMPLETIONS")
        val otherGroup = scope(account = "a-x018", credential = "c-x018", group = "g-other", operation = "CHAT_COMPLETIONS")
        val otherOp = scope(account = "a-x018", credential = "c-x018", group = null, operation = "EMBEDDINGS")
        store.recordPriceTransition(base, "0.001", "0.002", "0.002", "0.004", observedAt = 1000L, source = "OFFICIAL", success = true, eventId = "price-x018-iso-base")
        store.recordPriceTransition(otherAccount, "0.001", "0.002", "0.009", "0.009", observedAt = 1000L, source = "OFFICIAL", success = true, eventId = "price-x018-iso-acct")
        store.recordQuotaTransition(base, "QUOTA_AVAILABLE", "QUOTA_LOW", observedAt = 1000L, source = "OBSERVED", success = true, eventId = "quota-x018-iso-base")
        store.recordQuotaTransition(otherGroup, "QUOTA_AVAILABLE", "QUOTA_EXHAUSTED", observedAt = 1000L, source = "OBSERVED", success = true, eventId = "quota-x018-iso-group")
        // 同時點不同 scope 不互相覆蓋（X-004）：各查各的。
        val basePrices = store.priceHistory(base)
        assertEquals(1, basePrices.size)
        assertEquals("0.002", basePrices.single().toInput)
        assertEquals(1, store.priceHistory(otherAccount).size)
        assertEquals("0.009", store.priceHistory(otherAccount).single().toInput)
        assertEquals(0, store.priceHistory(otherOp).size)
        val baseQuotas = store.quotaHistory(base)
        assertEquals(1, baseQuotas.size)
        assertEquals("QUOTA_LOW", baseQuotas.single().toState)
        assertEquals("QUOTA_EXHAUSTED", store.quotaHistory(otherGroup).single().toState)
        assertEquals(0, store.quotaHistory(otherAccount).size)
    }

    @Test
    fun provenanceSourceIsCarriedAndNotUpgraded() = runBlocking {
        val key = scope()
        val price = store.recordPriceTransition(
            scopeKey = key,
            fromInput = "0.001",
            fromOutput = "0.002",
            toInput = "0.003",
            toOutput = "0.004",
            observedAt = 1000L,
            source = "MANUAL",
            success = true,
            eventId = "price-x018-manual",
        )
        // MANUAL 永不升為 OFFICIAL（X-005）。
        assertEquals("MANUAL", price!!.source)
        val quota = store.recordQuotaTransition(key, "QUOTA_AVAILABLE", "QUOTA_LOW", observedAt = 2000L, source = "MANUAL", success = true, eventId = "quota-x018-manual")
        assertEquals("MANUAL", quota!!.source)
        val recovery = store.recordProviderHealth("p-x018-src", EventStore.PROVIDER_EVENT_RECOVERY, observedAt = 3000L, source = "MANUAL", success = true, eventId = "prov-x018-manual")
        assertEquals("MANUAL", recovery!!.source)
    }

    @Test
    fun syncFailureEmitsNoEventAndKeepsLastGood() = runBlocking {
        val key = scope()
        assertNull(
            store.recordPriceTransition(key, "0.001", "0.002", "0.009", "0.009", observedAt = 1000L, source = "OFFICIAL", success = false, eventId = "price-x018-fail"),
        )
        assertNull(
            store.recordQuotaTransition(key, "QUOTA_AVAILABLE", "QUOTA_EXHAUSTED", observedAt = 1000L, source = "OBSERVED", success = false, eventId = "quota-x018-fail"),
        )
        assertNull(
            store.recordProviderHealth("p-x018-fail", EventStore.PROVIDER_EVENT_RECOVERY, observedAt = 1000L, source = "OBSERVED", success = false, eventId = "prov-x018-fail"),
        )
        // 失敗路徑不產生任何列（否則會記錄不存在的狀態變化；X-006）。
        assertEquals(0, store.priceHistory(key).size)
        assertEquals(0, store.quotaHistory(key).size)
        assertEquals(0, store.eventsOf("p-x018-fail").size)
        assertEquals(0, db.priceEventDao().count())
        assertEquals(0, db.quotaEventDao().count())
        assertEquals(0, db.providerEventDao().count())
    }

    @Test
    fun outageRequiresHealthEvidenceNotSingleFailure() = runBlocking {
        // 單次失敗不產 OUTAGE（BACKLOG X-018 驗收第 6 項）。
        assertNull(
            store.recordProviderHealth("p-x018-out", EventStore.PROVIDER_EVENT_OUTAGE, observedAt = 1000L, source = "OBSERVED", decided = 1, successes = 0, success = true, eventId = "prov-x018-single"),
        )
        assertEquals(0, store.eventsOf("p-x018-out").size)
        // 無已判定樣本亦不產 OUTAGE（Unknown 不猜）。
        assertNull(
            store.recordProviderHealth("p-x018-out", EventStore.PROVIDER_EVENT_OUTAGE, observedAt = 2000L, source = "OBSERVED", decided = 0, successes = 0, success = true, eventId = "prov-x018-zero"),
        )
        // 充分證據（多個已判定＋OFFLINE 級成功率）才產 OUTAGE。
        val outage = store.recordProviderHealth("p-x018-out", EventStore.PROVIDER_EVENT_OUTAGE, observedAt = 3000L, source = "OBSERVED", decided = 4, successes = 0, success = true, eventId = "prov-x018-out")
        assertNotNull(outage)
        assertEquals(EventStore.PROVIDER_EVENT_OUTAGE, outage!!.kind)
        assertEquals(1, store.eventsOf("p-x018-out").size)
    }

    @Test
    fun quotaTransitionNeedsBothKnownAndChange() = runBlocking {
        val key = scope()
        // 新舊相等 → 無轉換，不發。
        assertNull(store.recordQuotaTransition(key, "QUOTA_AVAILABLE", "QUOTA_AVAILABLE", observedAt = 1000L, source = "OBSERVED", success = true, eventId = "quota-x018-same"))
        // 真轉換 → 發。
        val emitted = store.recordQuotaTransition(key, "QUOTA_AVAILABLE", "QUOTA_LOW", observedAt = 2000L, source = "OBSERVED", evidenceId = "ev-x018-q", success = true, eventId = "quota-x018-change")
        assertNotNull(emitted)
        assertEquals("QUOTA_AVAILABLE", emitted!!.fromState)
        assertEquals("QUOTA_LOW", emitted.toState)
        assertEquals("ev-x018-q", emitted.evidenceId)
        assertEquals(1, store.quotaHistory(key).size)
    }

    @Test
    fun noticeTruncatesSummaryAndKeepsRefNotRawBody() = runBlocking {
        val rawBody = "x".repeat(EventStore.MAX_SUMMARY_CHARS + 100)
        val saved = store.saveNotice(
            noticeId = "notice-x018-1",
            providerId = "p-x018-notice",
            summary = rawBody,
            ref = "ev-x018-notice",
            source = "OFFICIAL",
            evidenceId = "ev-x018-notice",
            observedAt = 1000L,
        )
        // 負向（X-003）：raw body 永不原樣落地，只存截斷摘要＋ref。
        assertEquals(EventStore.MAX_SUMMARY_CHARS, saved.summary.length)
        assertTrue(saved.summary != rawBody)
        assertEquals("ev-x018-notice", saved.ref)
        assertEquals("OFFICIAL", saved.source)
        assertEquals(1, store.noticesOf("p-x018-notice").size)
    }

    @Test
    fun priceNoChangeEmitsNoEvent() = runBlocking {
        val key = scope()
        assertNull(
            store.recordPriceTransition(key, "0.001", "0.002", "0.001", "0.002", fromState = "PAID", toState = "PAID", observedAt = 1000L, source = "OFFICIAL", success = true, eventId = "price-x018-same"),
        )
        assertEquals(0, store.priceHistory(key).size)
        // 只有 state 變（價格值不變）仍是轉換（free/paid 轉換史，§11）。
        val stateChange = store.recordPriceTransition(key, "0.001", "0.002", "0.001", "0.002", fromState = "PAID", toState = "FREE_NOW", observedAt = 2000L, source = "OFFICIAL", success = true, eventId = "price-x018-state")
        assertNotNull(stateChange)
        assertEquals("PAID", stateChange!!.fromState)
        assertEquals("FREE_NOW", stateChange.toState)
    }

    @Test
    fun providerEventsAreIsolatedPerProvider() = runBlocking {
        store.recordProviderHealth("p-x018-a", EventStore.PROVIDER_EVENT_NOTICE, observedAt = 1000L, source = "OFFICIAL", detail = "fixture-a", success = true, eventId = "prov-x018-a")
        store.recordProviderHealth("p-x018-b", EventStore.PROVIDER_EVENT_RECOVERY, observedAt = 2000L, source = "OBSERVED", detail = "fixture-b", success = true, eventId = "prov-x018-b")
        val a = store.eventsOf("p-x018-a")
        assertEquals(1, a.size)
        assertEquals(EventStore.PROVIDER_EVENT_NOTICE, a.single().kind)
        assertEquals("fixture-a", a.single().detail)
        assertEquals(1, store.eventsOf("p-x018-b").size)
        assertEquals(0, store.eventsOf("p-x018-missing").size)
        assertEquals(0, store.noticesOf("p-x018-a").size)
    }
}
