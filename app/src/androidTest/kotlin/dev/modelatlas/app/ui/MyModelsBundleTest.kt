package dev.modelatlas.app.ui

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.CanonicalStore
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.ExternalStore
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.MyModelsStore
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.domain.canonicalIdFor
import dev.modelatlas.core.domain.normalizeModelId
import dev.modelatlas.core.lmspeed.LMSPEED_SOURCE
import dev.modelatlas.core.lmspeed.LmspeedFetcher
import dev.modelatlas.core.lmspeed.LmspeedRawModel
import kotlinx.coroutines.flow.first
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
 * MYM-004 §23.3 bundle 餘項：membership／canonical（既有表語意）與 enrichment 失敗隔離。
 *
 * 真 in-memory Room＋真 `MyModelsStore`／`CanonicalStore`／`ExternalStore`／`AtlasRepository`；
 * 網路邊界只按既有 `syncLmspeedEnrichment(fetcher=...)` 顯式注入 fake fetcher，不打網路、
 * 不碰 vault。fixture 僅存本檔，不當產品資料。
 *
 * 範圍說明（誠實界定，不冒充已落地功能）：
 * - §23.3 的 bundle 自動接線 API（自動 attach canonical/alias、provider perf、external 自動、
 *   FREE evidence、exact account/credential/group scope）在此 tree 尚未落地；本檔先鎖既有
 *   membership／canonical／external 表語意與失敗隔離。bundle API 落地後由後續 slice 補齊
 *   對應斷言，不改本檔既有 expectation。
 * - Unknown != 0／Free：未知 scope／未解析映射維持 null，不猜 fallback；external 只寫外部槽，
 *   不偽造 local price／quota／evidence。
 */
@RunWith(AndroidJUnit4::class)
class MyModelsBundleTest {

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
    }

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    // ===== 1. canonical／alias attach（既有表語意）

    @Test
    fun membershipAttachThenCanonicalAliasPersist() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        val canonical = CanonicalStore(db)
        val rawModelId = "GLM-5.3/latest"
        seedCatalogOffering(db, "p-bundle-attach", "e-bundle-attach", rawModelId)
        seedSample(db, "s-bundle-attach", "p-bundle-attach", rawModelId)

        assertTrue(store.addToMyModels("p-bundle-attach", rawModelId, source = "USER", nowMillis = 10L))
        val saved = canonical.saveCanonical(rawModelId, nowMillis = 20L)
        canonical.saveAlias(
            providerId = "p-bundle-attach",
            providerModelId = rawModelId,
            canonicalModelId = saved.canonicalModelId,
            source = MappingSource.OFFICIAL.name,
            confidence = MappingConfidence.HIGH.name,
            verified = true,
        )

        assertEquals(canonicalIdFor(rawModelId), saved.canonicalModelId)
        assertEquals(normalizeModelId(rawModelId), saved.normalizedName)
        assertEquals("glm-5-3", saved.normalizedName)
        assertEquals(setOf(MyModelKey("p-bundle-attach", rawModelId)), store.myModels.first())

        assertNotNull(db.canonicalModelDao().getById(saved.canonicalModelId))
        val alias = db.providerModelAliasDao().get("p-bundle-attach", rawModelId)
        assertNotNull(alias)
        val aliasRow = alias!!
        assertEquals(saved.canonicalModelId, aliasRow.canonicalModelId)
        assertEquals(MappingSource.OFFICIAL.name, aliasRow.source)
        assertTrue(aliasRow.verified)

        // attach 不得讓既有 offering／telemetry 消失（D-006 永久保留）。
        assertNotNull(db.offeringDao().getById("cat:p-bundle-attach:$rawModelId"))
        assertNotNull(db.sampleDao().getById("s-bundle-attach"))
    }

    @Test
    fun canonicalAliasAttachIsIdempotent() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        val canonical = CanonicalStore(db)
        seedCatalogOffering(db, "p-bundle-idem", "e-bundle-idem", "bundle-idem-model")

        assertTrue(store.addToMyModels("p-bundle-idem", "bundle-idem-model", source = "USER", nowMillis = 1L))
        assertTrue(store.addToMyModels("p-bundle-idem", "bundle-idem-model", source = "ASSISTANT", nowMillis = 2L))
        assertEquals(1, db.myModelsDao().all().size)

        val first = canonical.saveCanonical("bundle-idem-model", nowMillis = 1L)
        val second = canonical.saveCanonical("bundle-idem-model", nowMillis = 2L)
        assertEquals(first.canonicalModelId, second.canonicalModelId)

        val aliasA = canonical.saveAlias(
            providerId = "p-bundle-idem",
            providerModelId = "bundle-idem-model",
            canonicalModelId = first.canonicalModelId,
            source = MappingSource.OFFICIAL.name,
            confidence = MappingConfidence.HIGH.name,
            verified = true,
        )
        val aliasB = canonical.saveAlias(
            providerId = "p-bundle-idem",
            providerModelId = "bundle-idem-model",
            canonicalModelId = first.canonicalModelId,
            source = MappingSource.OFFICIAL.name,
            confidence = MappingConfidence.HIGH.name,
            verified = true,
        )
        assertEquals(aliasA.aliasId, aliasB.aliasId)
        assertEquals(1, db.providerModelAliasDao().byCanonical(first.canonicalModelId).size)
        assertEquals(setOf(MyModelKey("p-bundle-idem", "bundle-idem-model")), store.myModelKeys())
    }

    @Test
    fun crossProviderAliasAndMembershipIsolated() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        val canonical = CanonicalStore(db)
        seedCatalogOffering(db, "p-bundle-x", "e-bundle-x", "bundle-shared")
        seedCatalogOffering(db, "p-bundle-y", "e-bundle-y", "bundle-shared")

        assertTrue(store.addToMyModels("p-bundle-x", "bundle-shared"))
        assertTrue(store.addToMyModels("p-bundle-y", "bundle-shared"))
        val canonicalRow = canonical.saveCanonical("bundle-shared")
        canonical.saveAlias(
            "p-bundle-x", "bundle-shared", canonicalRow.canonicalModelId,
            MappingSource.OFFICIAL.name, MappingConfidence.HIGH.name, verified = true,
        )
        canonical.saveAlias(
            "p-bundle-y", "bundle-shared", canonicalRow.canonicalModelId,
            MappingSource.OFFICIAL.name, MappingConfidence.HIGH.name, verified = true,
        )

        val aliasX = db.providerModelAliasDao().get("p-bundle-x", "bundle-shared")
        val aliasY = db.providerModelAliasDao().get("p-bundle-y", "bundle-shared")
        assertNotNull(aliasX)
        assertNotNull(aliasY)
        val rowX = aliasX!!
        val rowY = aliasY!!
        assertEquals(canonicalRow.canonicalModelId, rowX.canonicalModelId)
        assertEquals(canonicalRow.canonicalModelId, rowY.canonicalModelId)
        assertTrue(rowX.aliasId != rowY.aliasId)
        assertEquals(2, db.providerModelAliasDao().byCanonical(canonicalRow.canonicalModelId).size)

        // 移除 x membership 只動 x 的 membership 列，不刪 alias／offering／telemetry。
        assertFalse(store.removeFromMyModels("p-bundle-x", "bundle-shared"))
        assertFalse(store.isMyModel("p-bundle-x", "bundle-shared"))
        assertTrue(store.isMyModel("p-bundle-y", "bundle-shared"))
        assertEquals(setOf(MyModelKey("p-bundle-y", "bundle-shared")), store.myModelKeys())
        assertNotNull(db.providerModelAliasDao().get("p-bundle-x", "bundle-shared"))
        assertNotNull(db.offeringDao().getById("cat:p-bundle-x:bundle-shared"))
    }

    // ===== 2. Unknown scope 不猜 fallback；不支援 provider fail-open false

    @Test
    fun unknownScopeResolvesNullWithoutFallback() = runBlocking {
        val db = memoryDb()
        val canonical = CanonicalStore(db)

        assertNull(db.canonicalModelDao().getById(normalizeModelId("ghost-bundle-model")))
        assertNull(db.providerModelAliasDao().get("ghost-bundle-provider", "ghost-bundle-model"))
        assertNull(ExternalStore(db).resolve("ghost-bundle-external", LMSPEED_SOURCE))

        // 未支援／未知 provider：resolved gating fail-open false，不寫假鍵、不猜。
        assertFalse(AtlasRepository(db).dedicatedSupportsSyncResolved("ghost-bundle-provider"))
        assertFalse(AtlasRepository(db).dedicatedSupportsSyncResolved(""))
    }

    // ===== 3. Catalog 背景寫入永不碰 my_models（負向＋正向各一）

    @Test
    fun catalogBackgroundWriteNeverJoinsMyModels() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        seedCatalogOffering(db, "p-bundle-cat", "e-bundle-cat", "bundle-cat-model")
        seedSample(db, "s-bundle-cat", "p-bundle-cat", "bundle-cat-model")

        // 負向：背景 catalog＋telemetry 落庫後，my_models 仍空。
        assertEquals(emptySet<MyModelKey>(), store.myModels.first())
        assertFalse(store.isMyModel("p-bundle-cat", "bundle-cat-model"))
        assertNotNull(db.offeringDao().getById("cat:p-bundle-cat:bundle-cat-model"))
        assertNotNull(db.sampleDao().getById("s-bundle-cat"))

        // 正向：只有明確加入才進 my_models。
        assertTrue(store.addToMyModels("p-bundle-cat", "bundle-cat-model", source = "EXPLORE"))
        assertEquals(setOf(MyModelKey("p-bundle-cat", "bundle-cat-model")), store.myModels.first())
        assertTrue(store.isMyModel("p-bundle-cat", "bundle-cat-model"))
    }

    // ===== 4. enrichment 來源失敗：membership／offering／telemetry 不丟（失敗保舊）

    @Test
    fun failedEnrichmentSourceKeepsMembershipOfferingAndTelemetry() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        val repo = AtlasRepository(db)
        seedCatalogOffering(db, "p-bundle-fail", "e-bundle-fail", "bundle-fail-model")
        seedSample(db, "s-bundle-fail", "p-bundle-fail", "bundle-fail-model")
        assertTrue(store.addToMyModels("p-bundle-fail", "bundle-fail-model"))

        val ok = FakeFetcher(rows = listOf(LmspeedRawModel(slug = "fixture-bundle-keep")))
        val summary = repo.syncLmspeedEnrichment(fetcher = ok, nowMillis = 1_000L, ttlMillis = 5_000L)
        assertEquals(1, summary.saved)
        assertEquals(1, db.externalObservationDao().count())

        val failing = FakeFetcher(error = IllegalStateException("fixture bundle fetch boom"))
        var threw = false
        try {
            repo.syncLmspeedEnrichment(fetcher = failing, nowMillis = 2_000L, ttlMillis = 5_000L)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)

        // 失敗保舊：舊觀測原地保留，未被刪除。
        assertEquals(1, db.externalObservationDao().count())
        assertEquals(
            "fixture-bundle-keep",
            db.externalObservationDao().byExternal(LMSPEED_SOURCE, "fixture-bundle-keep").single().externalModelId,
        )
        // enrichment 失敗不得影響 membership／offering／telemetry。
        assertTrue(store.isMyModel("p-bundle-fail", "bundle-fail-model"))
        assertEquals(setOf(MyModelKey("p-bundle-fail", "bundle-fail-model")), store.myModelKeys())
        assertNotNull(db.offeringDao().getById("cat:p-bundle-fail:bundle-fail-model"))
        assertNotNull(db.sampleDao().getById("s-bundle-fail"))
    }

    // ===== 5. 缺來源 Unknown：external 未解析維持 null，且不偽造 local evidence

    @Test
    fun unmappedExternalStaysUnknownWithoutFabricatingLocalEvidence() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)

        val summary = repo.syncLmspeedEnrichment(
            fetcher = FakeFetcher(rows = listOf(LmspeedRawModel(slug = "fixture-bundle-unmapped"))),
            nowMillis = 1_000L,
            ttlMillis = 5_000L,
        )
        assertEquals(1, summary.saved)
        assertEquals(0, summary.mapped)

        val mapping = db.externalMappingDao().resolve("fixture-bundle-unmapped", LMSPEED_SOURCE)
        assertNotNull(mapping)
        assertNull(mapping!!.canonicalModelId)

        // 讀寫分離：external 只寫外部槽，不偽造 price／quota／evidence。
        assertEquals(0, countRows(db, "price_snapshots"))
        assertEquals(0, countRows(db, "quota_snapshots"))
        assertEquals(0, countRows(db, "evidence"))
    }

    // ===== 6. bundle API §23.3（DATA 已落地）：canonical／alias 自動 attach＋冪等

    @Test
    fun bundleAttachesCanonicalAliasNormalizationLowAndIsIdempotent() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        seedCatalogOffering(db, "p-bundle-api", "e-bundle-api", "bundle-api-model")

        val first = repo.enrichMyModelBundle("p-bundle-api", "bundle-api-model")
        assertEquals(canonicalIdFor("bundle-api-model"), first.canonicalModelId)
        assertTrue(first.aliasAttached)
        assertEquals(MappingSource.NORMALIZATION.name, first.aliasSource)
        assertEquals(MappingConfidence.LOW.name, first.aliasConfidence)
        assertFalse(first.providerPerformanceFetched)
        assertFalse(first.externalRefreshAttempted)
        assertNull(first.externalRefreshOk)
        assertTrue(first.stepFailures.isEmpty())

        // 持久化列 provenance：NORMALIZATION／LOW、verified=false。
        val persisted = db.providerModelAliasDao().get("p-bundle-api", "bundle-api-model")
        assertNotNull(persisted)
        val aliasRow = persisted!!
        assertEquals(first.canonicalModelId, aliasRow.canonicalModelId)
        assertEquals(MappingSource.NORMALIZATION.name, aliasRow.source)
        assertEquals(MappingConfidence.LOW.name, aliasRow.confidence)
        assertFalse(aliasRow.verified)
        assertNotNull(db.canonicalModelDao().getById(first.canonicalModelId!!))
        assertEquals(1, countRows(db, "canonical_models"))
        assertEquals(1, countRows(db, "provider_model_aliases"))

        // 冪等重跑：沿用既有 canonical／alias，不翻倍、provenance 不變。
        val second = repo.enrichMyModelBundle("p-bundle-api", "bundle-api-model")
        assertEquals(first.canonicalModelId, second.canonicalModelId)
        assertTrue(second.aliasAttached)
        assertEquals(MappingSource.NORMALIZATION.name, second.aliasSource)
        assertEquals(MappingConfidence.LOW.name, second.aliasConfidence)
        assertEquals(1, countRows(db, "canonical_models"))
        assertEquals(1, countRows(db, "provider_model_aliases"))
    }

    // ===== 7. 不支援 provider 調 bundle：canonical 照 attach、pricing／quota null、membership 不動

    @Test
    fun bundleOnUnsupportedProviderAttachesCanonicalWithoutTouchingMembership() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        val repo = AtlasRepository(db)
        seedCatalogOffering(db, "p-bundle-unsup", "e-bundle-unsup", "bundle-unsup-model")
        seedSample(db, "s-bundle-unsup", "p-bundle-unsup", "bundle-unsup-model")
        assertTrue(store.addToMyModels("p-bundle-unsup", "bundle-unsup-model", source = "ASSISTANT"))
        assertFalse(repo.dedicatedSupportsSyncResolved("p-bundle-unsup"))

        val result = repo.enrichMyModelBundle("p-bundle-unsup", "bundle-unsup-model")
        assertEquals(canonicalIdFor("bundle-unsup-model"), result.canonicalModelId)
        assertTrue(result.aliasAttached)
        assertNull(result.pricingSummary)
        assertNull(result.quotaSummary)
        assertFalse(result.providerPerformanceFetched)
        assertTrue(result.stepFailures.isEmpty())

        // membership 完全不動（bundle 零寫 membership）；offering／telemetry 保留。
        assertTrue(store.isMyModel("p-bundle-unsup", "bundle-unsup-model"))
        assertEquals(setOf(MyModelKey("p-bundle-unsup", "bundle-unsup-model")), store.myModelKeys())
        assertEquals(1, db.myModelsDao().all().size)
        assertNotNull(db.offeringDao().getById("cat:p-bundle-unsup:bundle-unsup-model"))
        assertNotNull(db.sampleDao().getById("s-bundle-unsup"))
    }

    // ===== 8. 不同 provider／model 隔離（同 model 跨 provider 共用 canonical、alias 各自獨立）

    @Test
    fun bundleIsolatesDifferentProvidersAndModels() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        seedCatalogOffering(db, "p-bundle-iso-a", "e-bundle-iso-a", "bundle-iso-shared")
        seedCatalogOffering(db, "p-bundle-iso-b", "e-bundle-iso-b", "bundle-iso-shared")
        // 同 provider、不同 model：不重用 provider 列，直接加 offering。
        db.offeringDao().insert(
            OfferingEntity(
                id = "cat:p-bundle-iso-a:bundle-iso-other",
                providerId = "p-bundle-iso-a",
                endpointId = "e-bundle-iso-a",
                providerModelId = "bundle-iso-other",
                manual = false,
                source = "OFFICIAL",
                hidden = false,
            ),
        )

        val sharedA = repo.enrichMyModelBundle("p-bundle-iso-a", "bundle-iso-shared")
        val sharedB = repo.enrichMyModelBundle("p-bundle-iso-b", "bundle-iso-shared")
        val other = repo.enrichMyModelBundle("p-bundle-iso-a", "bundle-iso-other")

        assertEquals(canonicalIdFor("bundle-iso-shared"), sharedA.canonicalModelId)
        assertEquals(sharedA.canonicalModelId, sharedB.canonicalModelId)
        assertEquals(canonicalIdFor("bundle-iso-other"), other.canonicalModelId)
        assertTrue(sharedA.canonicalModelId != other.canonicalModelId)

        val aliasA = db.providerModelAliasDao().get("p-bundle-iso-a", "bundle-iso-shared")
        val aliasB = db.providerModelAliasDao().get("p-bundle-iso-b", "bundle-iso-shared")
        assertNotNull(aliasA)
        assertNotNull(aliasB)
        val rowA = aliasA!!
        val rowB = aliasB!!
        // 同 canonical、不同 provider → alias 列各自獨立。
        assertTrue(rowA.aliasId != rowB.aliasId)
        assertEquals(sharedA.canonicalModelId, rowA.canonicalModelId)
        assertEquals(sharedA.canonicalModelId, rowB.canonicalModelId)
        // 跨 provider／model 不誤命中。
        assertNotNull(db.providerModelAliasDao().get("p-bundle-iso-a", "bundle-iso-other"))
        assertNull(db.providerModelAliasDao().get("p-bundle-iso-b", "bundle-iso-shared-other"))
        assertEquals(2, countRows(db, "canonical_models"))
        assertEquals(3, countRows(db, "provider_model_aliases"))
    }

    // ===== 9. external refresh fail-open：拋錯 fetcher→null＋標籤，membership／alias 不動

    @Test
    fun bundleExternalRefreshFailsOpenOnFetcherError() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        val repo = AtlasRepository(db)
        seedCatalogOffering(db, "p-bundle-failopen", "e-bundle-failopen", "bundle-failopen-model")
        assertTrue(store.addToMyModels("p-bundle-failopen", "bundle-failopen-model", source = "USER", nowMillis = 1_000L))

        val result = repo.enrichMyModelBundle(
            "p-bundle-failopen",
            "bundle-failopen-model",
            lmspeedFetcher = FakeFetcher(error = IllegalStateException("fixture closeout fetch boom")),
        )

        assertTrue(result.externalRefreshAttempted)
        assertNull(result.externalRefreshOk)
        assertEquals(listOf("EXTERNAL_REFRESH"), result.stepFailures)
        // fail-open：canonical／alias 照常落地（refresh 在 attach 之後，不刪已存列）。
        assertTrue(result.aliasAttached)
        assertEquals(MappingSource.NORMALIZATION.name, result.aliasSource)
        assertEquals(MappingConfidence.LOW.name, result.aliasConfidence)
        assertNotNull(db.providerModelAliasDao().get("p-bundle-failopen", "bundle-failopen-model"))
        // membership／offering 不動；外部槽零寫入；attach 讀到真 0（非 null）。
        assertTrue(store.isMyModel("p-bundle-failopen", "bundle-failopen-model"))
        assertEquals(1, db.myModelsDao().all().size)
        assertNotNull(db.offeringDao().getById("cat:p-bundle-failopen:bundle-failopen-model"))
        assertEquals(0, countRows(db, "external_observations"))
        assertEquals(0, result.externalObservationCount)
        assertFalse(result.stepFailures.contains("EXTERNAL_ATTACH"))
    }

    // ===== 10. external refresh 成功零寫入→false 可達（嘗試但無觀測寫入）

    @Test
    fun bundleExternalRefreshFalseWhenFetcherSavesNothing() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        val repo = AtlasRepository(db)
        seedCatalogOffering(db, "p-bundle-zerowrite", "e-bundle-zerowrite", "bundle-zerowrite-model")
        assertTrue(store.addToMyModels("p-bundle-zerowrite", "bundle-zerowrite-model", source = "USER", nowMillis = 1_000L))

        val result = repo.enrichMyModelBundle(
            "p-bundle-zerowrite",
            "bundle-zerowrite-model",
            lmspeedFetcher = FakeFetcher(rows = emptyList()),
        )

        assertTrue(result.externalRefreshAttempted)
        assertEquals(false, result.externalRefreshOk)
        assertTrue(result.stepFailures.isEmpty())
        assertTrue(result.aliasAttached)
        assertEquals(0, countRows(db, "external_observations"))
        assertEquals(0, result.externalObservationCount)
        assertTrue(store.isMyModel("p-bundle-zerowrite", "bundle-zerowrite-model"))
        assertEquals(1, db.myModelsDao().all().size)
    }

    // ===== 11. credential scope：非法 ref（空白／sk- 開頭）bundle 入口 fail-closed 拋錯

    @Test
    fun bundleCredentialRefFailClosedAtEntry() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        val repo = AtlasRepository(db)
        seedCatalogOffering(db, "p-bundle-cred", "e-bundle-cred", "bundle-cred-model")
        assertTrue(store.addToMyModels("p-bundle-cred", "bundle-cred-model", source = "USER", nowMillis = 1_000L))

        var blankThrew = false
        try {
            repo.enrichMyModelBundle("p-bundle-cred", "bundle-cred-model", credentialId = "   ")
        } catch (_: IllegalArgumentException) {
            blankThrew = true
        }
        assertTrue(blankThrew)

        var secretThrew = false
        try {
            repo.enrichMyModelBundle("p-bundle-cred", "bundle-cred-model", credentialId = "sk-fixture-raw-secret")
        } catch (_: IllegalArgumentException) {
            secretThrew = true
        }
        assertTrue(secretThrew)

        // fail-closed 在任何寫入前拒絕：membership 不動、alias 未建、canonical 未寫。
        assertTrue(store.isMyModel("p-bundle-cred", "bundle-cred-model"))
        assertEquals(1, db.myModelsDao().all().size)
        assertNull(db.providerModelAliasDao().get("p-bundle-cred", "bundle-cred-model"))
        assertEquals(0, countRows(db, "canonical_models"))
        assertEquals(0, countRows(db, "provider_model_aliases"))
    }

    // ===== harness

    private class FakeFetcher(
        val rows: List<LmspeedRawModel> = emptyList(),
        var error: Throwable? = null,
    ) : LmspeedFetcher {
        override suspend fun fetchModels(): List<LmspeedRawModel> {
            error?.let { throw it }
            return rows
        }
    }

    private suspend fun seedCatalogOffering(
        db: AtlasDatabase,
        providerId: String,
        endpointId: String,
        modelId: String,
    ) {
        db.providerDao().insert(ProviderEntity(providerId = providerId, name = "P $providerId"))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = endpointId,
                providerId = providerId,
                baseUrl = "https://$providerId.example.com/v1",
                endpointUrl = "https://$providerId.example.com/v1/chat/completions",
            ),
        )
        db.offeringDao().insert(
            OfferingEntity(
                id = "cat:$providerId:$modelId",
                providerId = providerId,
                endpointId = endpointId,
                providerModelId = modelId,
                manual = false,
                source = "OFFICIAL",
                hidden = false,
            ),
        )
    }

    private suspend fun seedSample(db: AtlasDatabase, id: String, providerId: String, modelId: String) {
        db.sampleDao().upsert(
            SampleEntity(
                id = id,
                providerId = providerId,
                accountId = "a-$providerId",
                safeCredentialId = "c-$providerId",
                endpointId = "e-$providerId",
                providerModelId = modelId,
                outcome = "SUCCESS",
            ),
        )
    }

    private fun countRows(db: AtlasDatabase, table: String): Int {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }
}
