package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.newapi.*
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fixture 僅存於 androidTest，不當產品資料。 */
@RunWith(AndroidJUnit4::class)
class AtlasRepositoryTest {
    private lateinit var context: Context
    private val open = mutableListOf<AtlasDatabase>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
        context.deleteDatabase(RESTART_DB)
    }

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    private fun connection(id: String = "off-1") = ConnectionRecord(
        id = id,
        providerId = "p1",
        accountId = "a1",
        credentialId = "c1",
        endpointId = "e1",
        name = "Seek",
        baseUrl = "https://seekai.cc/v1",
        endpointUrl = "https://seekai.cc/v1/chat/completions",
        modelId = "m1",
    )

    private fun sample(id: String = "s1") = PerformanceSample(
        id = id,
        providerId = "p1",
        accountId = "a1",
        safeCredentialId = "c1",
        endpointId = "e1",
        providerModelId = "m1",
        canonicalModelId = null,
        group = null,
        operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.PLAYGROUND,
        requestStartedAt = 1000L,
        streaming = true,
        usage = Usage(inputTokens = 3L, outputTokens = 7L),
        outcome = Outcome.RUNNING,
        rateLimitHeaders = mapOf("retry-after" to "1"),
        priceState = "FREE_NOW",
        quotaState = "UNKNOWN",
    )

    @Test
    fun connectionSurvivesRestartViaFileDb() = runBlocking {
        context.deleteDatabase(RESTART_DB)
        val db1 = AtlasDatabase.create(context, RESTART_DB).also { open += it }
        AtlasRepository(db1).addConnection(connection())
        db1.close()
        open.remove(db1)
        val db2 = AtlasDatabase.create(context, RESTART_DB).also { open += it }
        val rows = AtlasRepository(db2).connections.first()
        assertEquals(1, rows.size)
        assertEquals("off-1", rows[0].id)
        assertEquals("https://seekai.cc/v1", rows[0].baseUrl)
        assertEquals("UNKNOWN", rows[0].priceState)
    }

    @Test
    fun duplicateAddConnectionFailsAndPreservesExisting() = runBlocking {
        val repo = AtlasRepository(memoryDb())
        repo.addConnection(connection())
        var failed = false
        try {
            repo.addConnection(connection().copy(name = "Changed"))
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
        val rows = repo.connections.first()
        assertEquals(1, rows.size)
        assertEquals("Seek", rows[0].name)
    }

    @Test
    fun freeNowTelemetryIsSaved() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.save(sample())
        val rows = repo.samples.first()
        assertEquals(1, rows.size)
        val s = rows[0]
        assertEquals("FREE_NOW", s.priceState)
        assertEquals(Outcome.RUNNING, s.outcome)
        assertEquals(7L, s.usage.outputTokens)
        assertEquals("1", s.rateLimitHeaders["retry-after"])
        val evidence = db.evidenceDao().byRequest("s1")
        assertEquals("RUNNING", evidence?.outcome)
        assertEquals("PLAYGROUND", evidence?.source)
    }

    @Test
    fun sameIdSaveUpdatesWithoutDuplicating() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.save(sample())
        repo.save(
            sample().copy(
                outcome = Outcome.SUCCESS,
                completedAt = 2000L,
                totalLatencyMs = 900L,
                ttftMs = 100L,
            ),
        )
        assertEquals(1, db.sampleDao().countById("s1"))
        val row = db.sampleDao().getById("s1")
        assertEquals("SUCCESS", row?.outcome)
        assertEquals(2000L, row?.completedAt)
    }

    @Test
    fun recoverInterruptedMarksRunning() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.save(sample())
        repo.recoverInterrupted()
        val row = db.sampleDao().getById("s1")
        assertEquals("INTERRUPTED", row?.outcome)
        assertNull(row?.completedAt)
        assertNull(row?.totalLatencyMs)
    }

    @Test
    fun checkinIsolatedByDateAndProvider() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.completeCheckin("p1", "2026-09-27", 10L)
        repo.completeCheckin("p1", "2026-09-26", 11L)
        repo.completeCheckin("p2", "2026-09-27", 12L)
        repo.completeCheckin("p1", "2026-09-27", 13L)
        assertEquals(3, repo.checkins.first().size)
        assertEquals(13L, db.checkinDao().get("p1", "2026-09-27")?.completedAt)
        assertEquals("USER", db.checkinDao().get("p2", "2026-09-27")?.source)
    }

    @Test
    fun disabledCredentialIsExcludedFromConnections() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        db.accountDao().insert(AccountEntity("a2", "p1"))
        db.credentialDao().insert(CredentialEntity("c2", "a2", enabled = false))
        db.availabilityDao().insert(CredentialAvailabilityEntity("off-2", "off-1", "a2", "c2"))
        val rows = repo.connections.first()
        assertEquals(1, rows.size)
        assertEquals("off-1", rows[0].id)
    }

    @Test
    fun endpointProtocolIsChatCompletions() = runBlocking {
        val db = memoryDb()
        AtlasRepository(db).addConnection(connection())
        assertEquals("CHAT_COMPLETIONS", db.endpointDao().getById("e1")?.protocol)
    }

    @Test
    fun blankPriceSnapshotIdIsAcceptedWithGeneratedId() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        val scope = PricingScope("p1", "a1", "c1", "m1", null, "e1", "CHAT_COMPLETIONS")
        // F16：空白 id（含預設 ""）不再拒絕；store 統一 newId() 覆寫。
        repo.savePrice(PriceSnapshot(id = "  ", scope = scope, observedAt = 1L,
            input = BigDecimal("0.01"), output = BigDecimal("0.02")))
        val manual = repo.newApiSnapshots.price(scope, manual = true)
        assertTrue(manual.latest != null)
        assertTrue(manual.lastGood != null)
        assertTrue(manual.lastGood!!.id.isNotBlank())
        assertTrue(manual.lastGood!!.id.startsWith("fact:"))
        assertEquals("USER", manual.lastGood!!.source)
    }

    @Test
    fun blankQuotaSnapshotIdIsAcceptedWithGeneratedId() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        val scope = PricingScope("p1", "a1", "c1", "m1", null, "e1", "CHAT_COMPLETIONS")
        repo.saveQuota(QuotaSnapshot(id = "", scope = scope, observedAt = 1L))
        val manual = repo.newApiSnapshots.quota(scope, SnapshotKind.MANUAL_QUOTA)
        assertTrue(manual.latest != null)
        assertTrue(manual.latest!!.snapshotId!!.isNotBlank())
        assertTrue(manual.latest!!.snapshotId!!.startsWith("fact:"))
    }

    @Test
    fun priceEnrichmentIsolatedByAccount() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        db.accountDao().insert(AccountEntity("a2", "p1"))
        db.credentialDao().insert(CredentialEntity("c2", "a2"))
        db.availabilityDao().insert(CredentialAvailabilityEntity("av-2", "off-1", "a2", "c2"))
        // a1 有官方 PAID；a2 無任何 PRICE head。舊模糊投影按 provider/model/endpoint
        // 取首列，會把 a1 的 PAID 洩漏到 a2 列。
        val store = NewApiSnapshotStore(db)
        val scopeA1 = PricingScope("p1", "a1", null, "m1", "default", "e1", "CHAT_COMPLETIONS")
        store.persistPricing(scopeA1, capabilities(1000), NewApiResult.Success(evidence(1000), catalog()))
        assertEquals("PAID", store.price(scopeA1).latest!!.state)
        // CR-004 B1 fixture 適配（純追加，非 expectation 放寬）：ConnectionRecord 無
        // group 維度＝caller 未知（null），上面的 "default" head 現正確排除；另以
        // production storageKey 編碼補一顆 null-group PRICE head 供 connection 投影。
        val nullGroupScope = PricingScope("p1", "a1", null, "m1", null, "e1", "CHAT_COMPLETIONS")
        seedNullGroupPriceHead(db, nullGroupScope, "PAID")
        val rows = repo.connections.first()
        assertEquals(2, rows.size)
        assertEquals("PAID", rows.single { it.accountId == "a1" }.priceState)
        assertEquals("UNKNOWN", rows.single { it.accountId == "a2" }.priceState)
    }

    @Test
    fun priceEnrichmentUnknownWhenGroupsDisagree() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        val store = NewApiSnapshotStore(db)
        // scopeKey 排序下 "other" 先於 "default"：舊 LIMIT 1 會取 other 的 PAID；
        // 候選一致性（PAID vs UNKNOWN）應回 UNKNOWN，絕不挑一列。
        val other = PricingScope("p1", "a1", null, "m1", "other", "e1", "CHAT_COMPLETIONS")
        val default = PricingScope("p1", "a1", null, "m1", "default", "e1", "CHAT_COMPLETIONS")
        store.persistPricing(other, capabilities(1000), NewApiResult.Success(evidence(1000), catalog()))
        val mixedCatalog = catalog().copy(groupRatio = mapOf(
            "default" to NewApiRatio(null, true),
            "other" to NewApiRatio(BigDecimal("1.5"), false),
        ))
        store.persistPricing(default, capabilities(2000), NewApiResult.Success(evidence(2000), mixedCatalog))
        assertEquals("PAID", store.price(other).latest!!.state)
        assertEquals("UNKNOWN", store.price(default).latest!!.state)
        assertNull(store.price(default).lastGood)
        val rows = repo.connections.first()
        assertEquals(1, rows.size)
        assertEquals("UNKNOWN", rows[0].priceState)
    }

    @Test
    fun priceEnrichmentUnknownWhenOperationsDisagree() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        val store = NewApiSnapshotStore(db)
        // scopeKey 排序下 CHAT_COMPLETIONS 先於 ANTHROPIC_MESSAGES：舊 LIMIT 1 會取
        // CHAT 的 PAID；候選一致性（PAID vs UNKNOWN）應回 UNKNOWN，絕不挑一列。
        // 同時覆蓋 operation-aware 認證：ANTHROPIC 拿不到 anthropic spec 即 UNKNOWN。
        val chat = PricingScope("p1", "a1", null, "m1", "default", "e1", "CHAT_COMPLETIONS")
        val anthropic = PricingScope("p1", "a1", null, "m1", "default", "e1", "ANTHROPIC_MESSAGES")
        store.persistPricing(chat, capabilities(1000), NewApiResult.Success(evidence(1000), catalog()))
        val anthropicCatalog = catalog().copy(
            items = listOf(item().copy(supportedEndpointTypes = listOf("anthropic"))),
        )
        store.persistPricing(anthropic, capabilities(2000), NewApiResult.Success(evidence(2000), anthropicCatalog))
        assertEquals("PAID", store.price(chat).latest!!.state)
        assertEquals("UNKNOWN", store.price(anthropic).latest!!.state)
        assertNull(store.price(anthropic).lastGood)
        val rows = repo.connections.first()
        assertEquals(1, rows.size)
        assertEquals("UNKNOWN", rows[0].priceState)
    }

    /**
     * CR-004 B1 適配用 typed null-group PRICE head：scopeKey 經 production
     * [PricingScope.storageKey] 編碼，evidence／fact／head 三件一致；數值狀態直給，
     * 不經 catalog（本 helper 只服務帳號隔離 fixture）。
     */
    private suspend fun seedNullGroupPriceHead(db: AtlasDatabase, scope: PricingScope, state: String) {
        val key = scope.storageKey()
        val evidenceId = "cr004-iso-ev"
        db.evidenceDao().insert(
            EvidenceEntity(evidenceId, evidenceId, scope.providerId, scope.modelId, "OK", 200, 1000L, NewApiSnapshotStore.PRICE_SOURCE),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = evidenceId,
                scopeKey = key,
                kind = SnapshotKind.PRICE.name,
                observedAt = 1000L,
                snapshotId = evidenceId,
                state = state,
                status = "OK",
                confidence = "OFFICIAL",
                providerVersion = null,
                pricingVersion = null,
                itemPricingVersion = null,
                quotaPerUnit = null,
                groupRatio = null,
                statusObservedAt = 999L,
                remainingRaw = null,
                usedRaw = null,
                grantedRaw = null,
                unlimited = null,
                expiresAtRaw = null,
                expiryMeaning = null,
                expressionHash = null,
                priceUnit = "USD_PER_1K_TOKENS",
            ),
        )
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity(key, SnapshotKind.PRICE.name, evidenceId, 1000L, evidenceId, 1000L))
    }

    private fun evidence(time: Long, source: String = NewApiSnapshotStore.PRICE_SOURCE) = NewApiEvidence(
        source, time, NewApiVersion.RC25, NewApiVersionSupport.CERTIFIED_RC25, 200, "catalog-v1",
    )

    private fun capabilities(time: Long): NewApiResult<NewApiCapabilities> {
        val version = NewApiVersion.of(NewApiVersion.RC25)
        return NewApiResult.Success(evidence(time - 1, "newapi/api/status"), NewApiCapabilities(
            version, BigDecimal("500000"), "USD", false, NewApiModuleAccess(true, true), version.certifiedEndpoints,
        ))
    }

    private fun catalog(model: String = "m1", ratio: String = "2") = NewApiPricing(
        items = listOf(item(model).copy(modelRatio = BigDecimal(ratio))), skippedItems = 0,
        groupRatio = listOf("default", "other", "").associateWith { NewApiRatio(BigDecimal("1.5"), false) },
        usableGroups = listOf("default", "other", ""),
        supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "POST")),
        autoGroups = emptyList(), pricingVersion = "catalog-v1",
    )

    private fun item(model: String = "m1") = NewApiPricingItem(
        modelName = model, quotaType = 0, modelRatio = BigDecimal("2"), modelPrice = null,
        completionRatio = BigDecimal("2.5"), cacheRatio = BigDecimal("0.25"), createCacheRatio = BigDecimal("1.25"),
        imageRatio = null, audioRatio = null, audioCompletionRatio = null, enableGroups = listOf("all"),
        supportedEndpointTypes = listOf("openai"), billingMode = null, billingExpression = null,
        vendorId = null, pricingVersion = "item-v1",
    )

    companion object {
        private const val RESTART_DB = "test-restart.db"
    }
}
