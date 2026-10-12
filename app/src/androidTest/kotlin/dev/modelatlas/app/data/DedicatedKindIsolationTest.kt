package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.DedicatedStatus
import dev.modelatlas.core.dedicated.OpenRouterFreeAdapter
import dev.modelatlas.core.dedicated.PricingFact
import dev.modelatlas.core.dedicated.PricingMode
import dev.modelatlas.core.dedicated.QuotaDimension
import dev.modelatlas.core.dedicated.QuotaPool
import dev.modelatlas.core.dedicated.QuotaScopeKind
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.newapi.NewApiAccountQuota
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiExpiryMeaning
import dev.modelatlas.core.newapi.NewApiModuleAccess
import dev.modelatlas.core.newapi.NewApiPricing
import dev.modelatlas.core.newapi.NewApiPricingItem
import dev.modelatlas.core.newapi.NewApiRatio
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiSupportedEndpoint
import dev.modelatlas.core.newapi.NewApiTokenQuota
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MAJOR8／X-004 kind 命名空間隔離：同七維 `scopeKey` 下官方 `PRICE`／`ACCOUNT_QUOTA`／
 * `TOKEN_QUOTA` 與 dedicated `DEDICATED_*` 兩組 head 並存、互不覆蓋，`MANUAL_*` 為第三個
 * 獨立分槽（LOW 永不覆蓋任何官方或 dedicated 槽）。
 *
 * 只用 typed fixture ＋本機記憶體 Room；不連線、不帶 secret、不當產品資料。
 */
@RunWith(AndroidJUnit4::class)
class DedicatedKindIsolationTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: NewApiSnapshotStore
    private lateinit var dedicated: DedicatedSnapshotSync

    private val priceScope = PricingScope("openrouter", "a", null, "m-kind", "default", "e-or", "CHAT_COMPLETIONS")

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = NewApiSnapshotStore(db)
        dedicated = DedicatedSnapshotSync(db)
        db.providerDao().insert(ProviderEntity("openrouter", "測試供應者"))
        db.accountDao().insert(AccountEntity("a", "openrouter"))
        db.credentialDao().insert(CredentialEntity("c", "a"))
        db.endpointDao().insert(
            EndpointEntity(
                "e-or", "openrouter",
                "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions",
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun priceAndDedicatedPriceHeadsCoexistOnTheSameScopeKey() = runBlocking {
        persistOfficialPrice(priceScope, 1000L, "2")
        val official = store.price(priceScope)
        val dedicatedPrice = dedicated.syncPricing(
            priceScope,
            DedicatedResult.Success(
                dedicatedEvidence(2000L, "openrouter/api/v1/models"),
                PricingFact(
                    "m-kind", BigDecimal("0.001"), BigDecimal("0.002"), null,
                    PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC,
                ),
            ),
        )

        assertEquals("PAID", official.latest!!.state)
        assertEquals("PAID", dedicatedPrice.latest!!.state)
        assertEquals(1000L, official.latest!!.observedAt)
        assertEquals(2000L, dedicatedPrice.latest!!.observedAt)
        // 兩組 head 各自的 latest／lastGood 皆不共用。
        assertNotEquals(official.latest!!.evidenceId, dedicatedPrice.latest!!.evidenceId)
        assertNotEquals(official.lastGood!!.id, dedicatedPrice.lastGood!!.id)
        decimal("0.006", official.lastGood!!.input)
        decimal("0.001", dedicatedPrice.lastGood!!.input)

        val key = priceScope.storageKey()
        val officialHead = head(key, SnapshotKind.PRICE)
        val dedicatedHead = head(key, SnapshotKind.DEDICATED_PRICE)
        assertEquals(1000L, officialHead.latestObservedAt)
        assertEquals(2000L, dedicatedHead.latestObservedAt)
        assertNotEquals(officialHead.lastGoodSnapshotId, dedicatedHead.lastGoodSnapshotId)
        assertEquals(2L, count("snapshot_heads"))
        assertEquals(1, db.snapshotFactDao().headsByKind(SnapshotKind.PRICE.name).size)
        assertEquals(1, db.snapshotFactDao().headsByKind(SnapshotKind.DEDICATED_PRICE.name).size)
        // 同 key 的手填槽仍空：官方與 dedicated 都不是手填。
        assertNull(headOrNull(key, SnapshotKind.MANUAL_PRICE))
    }

    @Test
    fun oneKindFailureNeitherAdvancesNorClearsTheOtherSlotsLastGood() = runBlocking {
        persistOfficialPrice(priceScope, 1000L, "2")
        dedicated.syncPricing(
            priceScope,
            DedicatedResult.Success(
                dedicatedEvidence(2000L, "openrouter/api/v1/models"),
                PricingFact(
                    "m-kind", BigDecimal("0.001"), BigDecimal("0.002"), null,
                    PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC,
                ),
            ),
        )
        val officialGood = store.price(priceScope).lastGood!!
        val dedicatedGood = dedicated.price(priceScope).lastGood!!

        store.persistPricing(
            priceScope, capabilities(2999L),
            NewApiResult.Failure(NewApiStatus.HTTP_UNAUTHORIZED, officialEvidence(3000L).copy(httpStatus = 401)),
        )
        dedicated.syncPricing(
            priceScope,
            DedicatedResult.Failure(
                DedicatedStatus.HTTP_UNAUTHORIZED_401,
                dedicatedEvidence(4000L, "openrouter/api/v1/models", 401),
            ),
        )

        val official = store.price(priceScope)
        val observed = dedicated.price(priceScope)
        assertEquals(3000L, official.latest!!.observedAt)
        assertEquals("UNKNOWN", official.latest!!.state)
        assertEquals("HTTP_UNAUTHORIZED", official.latest!!.status)
        assertEquals(officialGood, official.lastGood)
        assertEquals(4000L, observed.latest!!.observedAt)
        assertEquals("UNKNOWN", observed.latest!!.state)
        assertEquals("HTTP_UNAUTHORIZED_401", observed.latest!!.status)
        assertEquals(dedicatedGood, observed.lastGood)
        assertNotEquals(official.latest!!.evidenceId, observed.latest!!.evidenceId)
        assertEquals(2, store.history(priceScope, SnapshotKind.PRICE).size)
        assertEquals(2, dedicated.history(priceScope, SnapshotKind.DEDICATED_PRICE).size)
    }

    @Test
    fun manualPriceSlotIsIndependentFromOfficialAndDedicatedSlots() = runBlocking {
        persistOfficialPrice(priceScope, 1000L, "2")
        dedicated.syncPricing(
            priceScope,
            DedicatedResult.Success(
                dedicatedEvidence(2000L, "openrouter/api/v1/models"),
                PricingFact(
                    "m-kind", BigDecimal("0.001"), BigDecimal("0.002"), null,
                    PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC,
                ),
            ),
        )
        val officialBefore = store.price(priceScope)
        val dedicatedBefore = dedicated.price(priceScope)

        val manualId = store.saveManualPrice(
            PriceSnapshot(
                id = "caller-price", scope = priceScope, observedAt = 3000L,
                input = BigDecimal("0.5"), output = BigDecimal("1.5"), fixedRequestKnownAbsent = true,
                source = NewApiSnapshotStore.PRICE_SOURCE, evidenceId = officialBefore.latest!!.evidenceId,
            ),
        )

        val manual = store.price(priceScope, manual = true)
        assertEquals(manualId, manual.lastGood!!.id)
        decimal("0.5", manual.lastGood!!.input)
        assertEquals("LOW", manual.latest!!.confidence)
        assertEquals("USER", manual.lastGood!!.source)
        assertEquals("USER", db.evidenceDao().getById(manual.latest!!.evidenceId)!!.source)
        // 手填（最新時刻）不得推進或清空官方／dedicated 槽。
        assertEquals(officialBefore, store.price(priceScope))
        assertEquals(dedicatedBefore, dedicated.price(priceScope))
        assertEquals(1000L, head(priceScope.storageKey(), SnapshotKind.PRICE).latestObservedAt)
        assertEquals(2000L, head(priceScope.storageKey(), SnapshotKind.DEDICATED_PRICE).latestObservedAt)
        assertEquals(3000L, head(priceScope.storageKey(), SnapshotKind.MANUAL_PRICE).latestObservedAt)
        assertEquals(3L, count("snapshot_heads"))
        assertEquals(1, store.history(priceScope, SnapshotKind.MANUAL_PRICE).size)
        // dedicated 層的 head 永不指向手填槽（手填 latest 只有 USER evidence）。
        assertEquals(
            manual.latest!!.evidenceId,
            head(priceScope.storageKey(), SnapshotKind.MANUAL_PRICE).latestEvidenceId,
        )
        assertNotEquals(manual.latest!!.evidenceId, dedicated.price(priceScope).latest!!.evidenceId)
    }

    @Test
    fun accountQuotaKindsCoexistAndManualQuotaKeepsItsOwnSlot() = runBlocking {
        val quotaScope = DedicatedSnapshotSync.accountQuotaScope("openrouter", "a")
        assertEquals(NewApiSnapshotStore.accountQuotaScope("openrouter", "a"), quotaScope)

        store.persistAccountQuota(
            quotaScope,
            NewApiResult.Success(
                officialEvidence(1000L, NewApiSnapshotStore.ACCOUNT_SOURCE),
                NewApiAccountQuota(123L, 22L, null, null, null, null),
            ),
        )
        val dedicatedQuota = dedicated.syncAccountQuota(
            quotaScope,
            DedicatedResult.Success(
                dedicatedEvidence(2000L, "openrouter/api/v1/key"),
                QuotaPool(
                    QuotaScopeKind.PLATFORM_GLOBAL, null,
                    rpm = QuotaDimension.known(20L), rpd = QuotaDimension.known(50L),
                    provenance = DedicatedProvenance.OFFICIAL_DOC, isGroundTruth = false,
                ),
            ),
        )
        val manualId = store.saveManualQuota(
            QuotaSnapshot(
                id = "caller-quota", scope = quotaScope, observedAt = 3000L,
                state = QuotaState.UNLIMITED_CLAIMED,
                source = NewApiSnapshotStore.ACCOUNT_SOURCE,
                evidenceId = store.quota(quotaScope, SnapshotKind.ACCOUNT_QUOTA).latest!!.evidenceId,
            ),
        )

        val official = store.quota(quotaScope, SnapshotKind.ACCOUNT_QUOTA)
        val manual = store.quota(quotaScope, SnapshotKind.MANUAL_QUOTA)
        assertEquals(123L, official.latest!!.remainingRaw)
        assertEquals(22L, official.latest!!.usedRaw)
        assertEquals(1000L, official.lastGood!!.observedAt)
        assertEquals("PUBLIC_POOL", dedicatedQuota.latest!!.state)
        // dedicated limits 不可當 remaining（limits≠remaining）。
        assertNull(dedicatedQuota.latest!!.remainingRaw)
        assertEquals(2000L, dedicatedQuota.lastGood!!.observedAt)
        assertEquals(manualId, manual.lastGood!!.id)
        assertEquals(QuotaState.UNLIMITED_CLAIMED, manual.lastGood!!.state)
        assertEquals("LOW", manual.latest!!.confidence)
        assertEquals("USER", manual.lastGood!!.source)
        // 兩個 kind 參數都讀同一個 dedicated head；官方 head 完全獨立。
        assertEquals(dedicatedQuota, dedicated.quota(quotaScope, SnapshotKind.ACCOUNT_QUOTA))
        assertEquals(dedicatedQuota, dedicated.quota(quotaScope, SnapshotKind.DEDICATED_ACCOUNT_QUOTA))
        assertNotEquals(official.latest!!.evidenceId, dedicatedQuota.latest!!.evidenceId)
        val key = quotaScope.storageKey()
        assertEquals(1000L, head(key, SnapshotKind.ACCOUNT_QUOTA).latestObservedAt)
        assertEquals(2000L, head(key, SnapshotKind.DEDICATED_ACCOUNT_QUOTA).latestObservedAt)
        assertEquals(3000L, head(key, SnapshotKind.MANUAL_QUOTA).latestObservedAt)
        assertEquals(3L, count("snapshot_heads"))
    }

    @Test
    fun tokenQuotaKindsCoexistOnTheSameCredentialScope() = runBlocking {
        val tokenScope = DedicatedSnapshotSync.tokenQuotaScope("openrouter", "a", "c")
        assertEquals(NewApiSnapshotStore.tokenQuotaScope("openrouter", "a", InferenceKeyRef("c")), tokenScope)

        store.persistTokenQuota(
            tokenScope,
            NewApiResult.Success(
                officialEvidence(1000L, NewApiSnapshotStore.TOKEN_SOURCE),
                NewApiTokenQuota(
                    Long.MAX_VALUE, 499L, 500L, false, null, 0L,
                    NewApiExpiryMeaning.ZERO_AMBIGUOUS, null, QuotaState.QUOTA_AVAILABLE,
                ),
            ),
        )
        dedicated.syncOpenRouterKeyQuota(
            tokenScope, "c",
            DedicatedResult.Success(
                dedicatedEvidence(2000L, "openrouter/api/v1/key"),
                OpenRouterFreeAdapter.KeyQuotaFacts(70L, 200L, 90L, false, true),
            ),
        )

        val official = store.quota(tokenScope, SnapshotKind.TOKEN_QUOTA)
        val observed = dedicated.quota(tokenScope, SnapshotKind.TOKEN_QUOTA)
        assertEquals(500L, official.latest!!.remainingRaw)
        assertEquals(499L, official.latest!!.usedRaw)
        assertEquals(Long.MAX_VALUE, official.latest!!.grantedRaw)
        assertEquals(1000L, official.lastGood!!.observedAt)
        assertEquals(90L, observed.latest!!.remainingRaw)
        assertEquals(70L, observed.latest!!.usedRaw)
        assertEquals(200L, observed.latest!!.grantedRaw)
        assertEquals("QUOTA_AVAILABLE", observed.latest!!.state)
        assertEquals(false, observed.latest!!.isEstimated)
        assertEquals(2000L, observed.lastGood!!.observedAt)
        val key = tokenScope.storageKey()
        assertEquals(1000L, head(key, SnapshotKind.TOKEN_QUOTA).latestObservedAt)
        assertEquals(2000L, head(key, SnapshotKind.DEDICATED_TOKEN_QUOTA).latestObservedAt)
        assertEquals(2L, count("snapshot_heads"))
    }

    @Test
    fun dedicatedKindMappingRewritesOnlyTheThreeOfficialDimensions() = runBlocking {
        assertEquals(SnapshotKind.DEDICATED_PRICE, SnapshotKind.PRICE.dedicatedKind())
        assertEquals(SnapshotKind.DEDICATED_ACCOUNT_QUOTA, SnapshotKind.ACCOUNT_QUOTA.dedicatedKind())
        assertEquals(SnapshotKind.DEDICATED_TOKEN_QUOTA, SnapshotKind.TOKEN_QUOTA.dedicatedKind())
        // 手填槽與已 namespaced 的 kind 皆為恆等（不誤改手填／不二次命名）。
        assertEquals(SnapshotKind.MANUAL_PRICE, SnapshotKind.MANUAL_PRICE.dedicatedKind())
        assertEquals(SnapshotKind.MANUAL_QUOTA, SnapshotKind.MANUAL_QUOTA.dedicatedKind())
        assertEquals(SnapshotKind.DEDICATED_PRICE, SnapshotKind.DEDICATED_PRICE.dedicatedKind())
        assertEquals(SnapshotKind.DEDICATED_ACCOUNT_QUOTA, SnapshotKind.DEDICATED_ACCOUNT_QUOTA.dedicatedKind())
        assertEquals(SnapshotKind.DEDICATED_TOKEN_QUOTA, SnapshotKind.DEDICATED_TOKEN_QUOTA.dedicatedKind())
        // dedicated 層禁止把手填槽當作可讀 kind。
        try {
            dedicated.quota(priceScope, SnapshotKind.MANUAL_QUOTA)
            fail("Manual quota slot must be rejected by the dedicated layer")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun historiesAndHeadsNeverCrossKindBoundaries() = runBlocking {
        persistOfficialPrice(priceScope, 1000L, "2")
        dedicated.syncPricing(
            priceScope,
            DedicatedResult.Success(
                dedicatedEvidence(2000L, "openrouter/api/v1/models"),
                PricingFact(
                    "m-kind", BigDecimal("0.001"), BigDecimal("0.002"), null,
                    PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC,
                ),
            ),
        )
        store.saveManualPrice(
            PriceSnapshot(
                id = "caller-price", scope = priceScope, observedAt = 3000L,
                input = BigDecimal("0.5"), output = BigDecimal("1.5"), fixedRequestKnownAbsent = true,
                source = NewApiSnapshotStore.PRICE_SOURCE, evidenceId = "caller-anchor",
            ),
        )

        val officialRows = store.history(priceScope, SnapshotKind.PRICE)
        val dedicatedRows = dedicated.history(priceScope, SnapshotKind.DEDICATED_PRICE)
        val manualRows = store.history(priceScope, SnapshotKind.MANUAL_PRICE)
        assertEquals(1, officialRows.size)
        assertEquals(1, dedicatedRows.size)
        assertEquals(1, manualRows.size)
        assertEquals(SnapshotKind.PRICE.name, officialRows.single().kind)
        assertEquals(SnapshotKind.DEDICATED_PRICE.name, dedicatedRows.single().kind)
        assertEquals(SnapshotKind.MANUAL_PRICE.name, manualRows.single().kind)
        // provenance 分槽：官方／dedicated／手填三種 evidence source 互不冒充。
        assertEquals(NewApiSnapshotStore.PRICE_SOURCE, sourceOf(officialRows.single().evidenceId))
        assertEquals("openrouter/api/v1/models", sourceOf(dedicatedRows.single().evidenceId))
        assertEquals("USER", sourceOf(manualRows.single().evidenceId))
        assertEquals(3, setOf(officialRows, dedicatedRows, manualRows).flatten().map { it.evidenceId }.toSet().size)
        // dedicated 只在 DEDICATED_* 留下 head，官方／手填 head 不含 dedicated 列。
        for (kind in listOf(SnapshotKind.PRICE, SnapshotKind.MANUAL_PRICE)) {
            for (head in db.snapshotFactDao().headsByKind(kind.name)) {
                assertFalse(db.evidenceDao().getById(head.latestEvidenceId)!!.source.startsWith("openrouter/"))
            }
        }
        val tables = db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'").use {
                generateSequence { if (it.moveToNext()) it.getString(0) else null }.toList()
            }
        assertTrue(tables.none { it.contains("dedicated", ignoreCase = true) })
    }

    private suspend fun persistOfficialPrice(scope: PricingScope, time: Long, ratio: String) {
        store.persistPricing(
            scope, capabilities(time - 1),
            NewApiResult.Success(officialEvidence(time), catalog(scope.modelId, ratio)),
        )
    }

    private fun officialEvidence(time: Long, source: String = NewApiSnapshotStore.PRICE_SOURCE) = NewApiEvidence(
        source, time, NewApiVersion.RC25, NewApiVersionSupport.CERTIFIED_RC25, 200, "catalog-v1",
    )

    private fun dedicatedEvidence(time: Long, source: String, httpStatus: Int = 200) =
        DedicatedEvidence(source, time, DedicatedProvenance.OFFICIAL_DOC, httpStatus)

    private fun capabilities(time: Long): NewApiResult<NewApiCapabilities> {
        val version = NewApiVersion.of(NewApiVersion.RC25)
        return NewApiResult.Success(
            NewApiEvidence("newapi/api/status", time, NewApiVersion.RC25, NewApiVersionSupport.CERTIFIED_RC25, 200, "catalog-v1"),
            NewApiCapabilities(version, BigDecimal("500000"), "USD", false, NewApiModuleAccess(true, true), version.certifiedEndpoints),
        )
    }

    private fun catalog(model: String, ratio: String) = NewApiPricing(
        items = listOf(
            NewApiPricingItem(
                modelName = model, quotaType = 0, modelRatio = BigDecimal(ratio), modelPrice = null,
                completionRatio = BigDecimal("2.5"), cacheRatio = BigDecimal("0.25"), createCacheRatio = BigDecimal("1.25"),
                imageRatio = null, audioRatio = null, audioCompletionRatio = null, enableGroups = listOf("all"),
                supportedEndpointTypes = listOf("openai"), billingMode = null, billingExpression = null,
                vendorId = null, pricingVersion = "item-v1",
            ),
        ),
        skippedItems = 0,
        groupRatio = mapOf("default" to NewApiRatio(BigDecimal("1.5"), false)),
        usableGroups = listOf("default"),
        supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "POST")),
        autoGroups = emptyList(), pricingVersion = "catalog-v1",
    )

    private suspend fun head(scopeKey: String, kind: SnapshotKind) =
        db.snapshotFactDao().head(scopeKey, kind.name)!!

    private suspend fun headOrNull(scopeKey: String, kind: SnapshotKind) =
        db.snapshotFactDao().head(scopeKey, kind.name)

    private suspend fun sourceOf(evidenceId: String) = db.evidenceDao().getById(evidenceId)!!.source

    private fun decimal(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual!!))
    }

    private fun count(table: String): Long = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }
}
