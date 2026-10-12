package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.domain.Modality
import dev.modelatlas.core.domain.ModelCategory
import dev.modelatlas.core.onboarding.ManualClassificationInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MODEL-001…003 CanonicalStore 邊界測試（Room v7）。
 *
 * 只用本地 Room＋假 id；不連線、不帶 secret、不當產品資料。
 * 釘住：canonical 去重、三源 confidence 分離、重複寫入冪等、
 * manual 恆 LOW 且不覆蓋官方、未驗證標 tentative、snapshotJson→真表遷移。
 */
@RunWith(AndroidJUnit4::class)
class CanonicalStoreTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: CanonicalStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = CanonicalStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveCanonicalDeduplicatesAcrossKeys() = runBlocking {
        val first = store.saveCanonical("GPT-4o", "GPT-4o", 1000L)
        val second = store.saveCanonical("gpt_4o", "別名顯示", 2000L)
        assertEquals(first.canonicalModelId, second.canonicalModelId)
        assertEquals("gpt-4o", second.canonicalModelId)
        assertEquals("gpt-4o", second.normalizedName)
        assertEquals(first.displayName, second.displayName)
    }

    @Test
    fun saveAliasRoundTripsExactAndNormalizedResolution() = runBlocking {
        store.saveCanonical("GPT-4o")
        val alias = store.saveAlias(
            providerId = "fixture-provider",
            providerModelId = "GPT-4o",
            canonicalModelId = "gpt-4o",
            source = MappingSource.NORMALIZATION.name,
            confidence = MappingConfidence.MEDIUM.name,
        )
        assertEquals(false, alias.verified)
        val fetched = db.providerModelAliasDao().get("fixture-provider", "GPT-4o")!!
        assertEquals("gpt-4o", fetched.canonicalModelId)
        assertEquals(1, db.providerModelAliasDao().byCanonical("gpt-4o").size)
    }

    @Test
    fun saveAliasIdenticalRewriteIsIdempotent() = runBlocking {
        store.saveCanonical("m1")
        val first = store.saveAlias("p", "m1", "m1", MappingSource.METADATA.name, MappingConfidence.MEDIUM.name)
        val second = store.saveAlias("p", "m1", "m1", MappingSource.METADATA.name, MappingConfidence.MEDIUM.name)
        assertEquals(first, second)
        assertEquals(1, db.providerModelAliasDao().byCanonical("m1").size)
    }

    @Test
    fun manualAliasMustNotOverwriteOfficial() = runBlocking {
        store.saveCanonical("m2")
        store.saveAlias("p", "m2", "m2", MappingSource.OFFICIAL.name, MappingConfidence.HIGH.name, verified = true)
        var rejected = false
        try {
            store.saveAlias("p", "m2", "m2", MappingSource.USER_OVERRIDE.name, MappingConfidence.LOW.name)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        val kept = db.providerModelAliasDao().get("p", "m2")!!
        assertEquals(MappingSource.OFFICIAL.name, kept.source)
        assertEquals(MappingConfidence.HIGH.name, kept.confidence)
    }

    @Test
    fun manualAliasMustStayLow() = runBlocking {
        store.saveCanonical("m3")
        var rejected = false
        try {
            store.saveAlias("p", "m3", "m3", MappingSource.USER_OVERRIDE.name, MappingConfidence.HIGH.name)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertNull(db.providerModelAliasDao().get("p", "m3"))
    }

    @Test
    fun unverifiedAliasStaysTentative() = runBlocking {
        store.saveCanonical("m4")
        var rejected = false
        try {
            store.saveAlias("p", "m4", "m4", MappingSource.NORMALIZATION.name, MappingConfidence.LOW.name, verified = true)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        val tentative = store.saveAlias("p", "m4", "m4", MappingSource.NORMALIZATION.name, MappingConfidence.LOW.name)
        assertEquals(false, tentative.verified)
    }

    @Test
    fun threeClassificationSourcesStaySeparate() = runBlocking {
        store.saveCanonical("m5")
        store.saveClassification(
            canonicalModelId = "m5",
            source = ClassificationSources.CATALOG,
            confidence = MappingConfidence.HIGH.name,
            primaryCategory = ModelCategory.CHAT_REASONING.name,
            capabilities = setOf(Capability.REASONING.name),
            modalities = setOf(Modality.TEXT_INPUT.name, Modality.TEXT_OUTPUT.name),
            endpointTypes = setOf(EndpointType.CHAT_COMPLETIONS.name),
            observedAt = 1000L,
        )
        store.saveClassification(
            canonicalModelId = "m5",
            source = ClassificationSources.OBSERVED,
            confidence = MappingConfidence.MEDIUM.name,
            primaryCategory = ModelCategory.CHAT_REASONING.name,
            capabilities = setOf(Capability.TOOL_CALLING.name),
            modalities = setOf(Modality.TEXT_INPUT.name),
            endpointTypes = setOf(EndpointType.CHAT_COMPLETIONS.name),
            observedAt = 2000L,
        )
        store.saveClassification(
            canonicalModelId = "m5",
            source = ClassificationSources.MANUAL,
            confidence = MappingConfidence.LOW.name,
            primaryCategory = ModelCategory.CODING.name,
            capabilities = setOf(Capability.CODING.name),
            modalities = emptySet(),
            endpointTypes = emptySet(),
            observedAt = 3000L,
        )
        val rows = db.modelClassificationDao().byCanonical("m5")
        assertEquals(3, rows.size)
        val catalog = store.getClassification("m5", ClassificationSources.CATALOG)!!
        assertEquals(MappingConfidence.HIGH.name, catalog.classification.confidence)
        assertEquals(setOf(Capability.REASONING.name), catalog.capabilities)
        assertEquals(ModelCategory.CHAT_REASONING.name, catalog.classification.primaryCategory)
        val manual = store.getClassification("m5", ClassificationSources.MANUAL)!!
        assertEquals(MappingConfidence.LOW.name, manual.classification.confidence)
        assertEquals(ModelCategory.CODING.name, manual.classification.primaryCategory)
    }

    @Test
    fun manualClassificationMustStayLow() = runBlocking {
        store.saveCanonical("m6")
        var rejected = false
        try {
            store.saveClassification("m6", ClassificationSources.MANUAL, MappingConfidence.HIGH.name, ModelCategory.CODING.name)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertNull(store.getClassification("m6", ClassificationSources.MANUAL))
    }

    @Test
    fun saveClassificationRewriteReplacesAssocSets() = runBlocking {
        store.saveCanonical("m7")
        store.saveClassification(
            "m7", ClassificationSources.OBSERVED, MappingConfidence.MEDIUM.name,
            ModelCategory.CHAT_REASONING.name,
            capabilities = setOf(Capability.REASONING.name, Capability.CODING.name),
            modalities = setOf(Modality.TEXT_INPUT.name),
            endpointTypes = setOf(EndpointType.CHAT_COMPLETIONS.name),
        )
        store.saveClassification(
            "m7", ClassificationSources.OBSERVED, MappingConfidence.MEDIUM.name,
            ModelCategory.CHAT_REASONING.name,
            capabilities = setOf(Capability.CODING.name),
            modalities = setOf(Modality.TEXT_INPUT.name, Modality.TEXT_OUTPUT.name),
            endpointTypes = setOf(EndpointType.RESPONSES.name),
        )
        val details = store.getClassification("m7", ClassificationSources.OBSERVED)!!
        assertEquals(setOf(Capability.CODING.name), details.capabilities)
        assertEquals(setOf(Modality.TEXT_INPUT.name, Modality.TEXT_OUTPUT.name), details.modalities)
        assertEquals(setOf(EndpointType.RESPONSES.name), details.endpointTypes)
        assertEquals(1, db.modelClassificationDao().byCanonical("m7").size)
    }

    @Test
    fun unknownCategoryStaysNullNeverZero() = runBlocking {
        store.saveCanonical("m8")
        val row = store.saveClassification(
            "m8", ClassificationSources.OBSERVED, MappingConfidence.LOW.name,
            primaryCategory = null,
        )
        assertNull(row.primaryCategory)
        assertEquals(emptySet<String>(), store.getClassification("m8", ClassificationSources.OBSERVED)!!.capabilities)
    }

    @Test
    fun classificationFromManualSnapshotMigratesFourDimensions() = runBlocking {
        store.saveCanonical("DeepSeek-V3")
        val migrated = store.classificationFromManualSnapshot(
            canonicalModelId = "deepseek-v3",
            input = ManualClassificationInput(
                primaryCategory = ModelCategory.CODING,
                capabilities = setOf(Capability.CODING, Capability.TOOL_CALLING),
                modalities = setOf(Modality.TEXT_INPUT, Modality.TEXT_OUTPUT),
                endpointTypes = setOf(EndpointType.CHAT_COMPLETIONS),
            ),
            observedAt = 4000L,
        )!!
        assertEquals(ClassificationSources.MANUAL, migrated.source)
        assertEquals(MappingConfidence.LOW.name, migrated.confidence)
        assertEquals(4000L, migrated.observedAt)
        val details = store.getClassification("deepseek-v3", ClassificationSources.MANUAL)!!
        assertEquals(ModelCategory.CODING.name, details.classification.primaryCategory)
        assertEquals(setOf(Capability.CODING.name, Capability.TOOL_CALLING.name), details.capabilities)
        assertEquals(setOf(Modality.TEXT_INPUT.name, Modality.TEXT_OUTPUT.name), details.modalities)
        assertEquals(setOf(EndpointType.CHAT_COMPLETIONS.name), details.endpointTypes)
    }

    @Test
    fun classificationFromNullManualSnapshotWritesNothing() = runBlocking {
        store.saveCanonical("m9")
        assertNull(store.classificationFromManualSnapshot("m9", null, 1000L))
        assertEquals(0, db.modelClassificationDao().byCanonical("m9").size)
    }

    @Test
    fun observeClassificationEmitsDetailsFlow() = runBlocking {
        store.saveCanonical("m10")
        store.saveClassification(
            "m10", ClassificationSources.CATALOG, MappingConfidence.HIGH.name,
            ModelCategory.IMAGE.name,
            capabilities = setOf(Capability.IMAGE_GENERATION.name),
            modalities = setOf(Modality.TEXT_INPUT.name, Modality.IMAGE_OUTPUT.name),
            endpointTypes = setOf(EndpointType.IMAGES_GENERATION.name),
        )
        val emitted = store.observeClassification("m10", ClassificationSources.CATALOG).first()!!
        assertEquals(ModelCategory.IMAGE.name, emitted.classification.primaryCategory)
        assertEquals(setOf(Capability.IMAGE_GENERATION.name), emitted.capabilities)
        assertNull(store.observeClassification("m10", ClassificationSources.MANUAL).first())
    }

    @Test
    fun connectionRecordCarriesClassificationSnapshot() {
        val base = ConnectionRecord(
            id = "fixture-availability",
            providerId = "fixture-provider",
            accountId = "fixture-account",
            credentialId = "fixture-credential",
            endpointId = "fixture-endpoint",
            name = "測試供應者",
            baseUrl = "https://fixture.invalid/v1",
            endpointUrl = "https://fixture.invalid/v1/chat/completions",
            modelId = "fixture-model",
        )
        assertNull(base.canonicalModelId)
        assertNull(base.primaryCategory)
        val enriched = base.copy(
            canonicalModelId = "fixture-model",
            classificationSource = ClassificationSources.MANUAL,
            classificationConfidence = MappingConfidence.LOW.name,
            primaryCategory = ModelCategory.CODING.name,
        )
        assertEquals("fixture-model", enriched.canonicalModelId)
        assertEquals(ClassificationSources.MANUAL, enriched.classificationSource)
        assertEquals(MappingConfidence.LOW.name, enriched.classificationConfidence)
        assertEquals(ModelCategory.CODING.name, enriched.primaryCategory)
        assertEquals("fixture-availability", enriched.offeringId)
    }

    /**
     * MAJOR6 (a)：唯一衝突重試路徑（串行二次寫入模擬併發落後者重試）。
     * winner 已為 OFFICIAL／HIGH，跨源 LOW 重試必須被守衛擋下，不刪資料、不覆蓋。
     */
    @Test
    fun saveAliasConflictRetryKeepsOfficialGuard() = runBlocking {
        store.saveCanonical("m-retry")
        store.saveAlias(
            "pr", "mr", "m-retry",
            MappingSource.OFFICIAL.name, MappingConfidence.HIGH.name, verified = true,
        )
        var rejected = false
        try {
            store.saveAlias(
                "pr", "mr", "m-retry",
                MappingSource.USER_OVERRIDE.name, MappingConfidence.LOW.name,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        val kept = db.providerModelAliasDao().get("pr", "mr")!!
        assertEquals(MappingSource.OFFICIAL.name, kept.source)
        assertEquals(MappingConfidence.HIGH.name, kept.confidence)
        assertEquals(true, kept.verified)
        assertEquals(1, db.providerModelAliasDao().byCanonical("m-retry").size)
    }

    /**
     * MAJOR6：高信心官方映射不被低信心跨源覆蓋（official 不被 manual 降級）。
     */
    @Test
    fun highConfidenceOfficialNotOverwrittenByLow() = runBlocking {
        store.saveCanonical("m-high")
        store.saveAlias(
            "ph", "mh", "m-high",
            MappingSource.OFFICIAL.name, MappingConfidence.HIGH.name, verified = true,
        )
        var rejected = false
        try {
            store.saveAlias(
                "ph", "mh", "m-high",
                MappingSource.NORMALIZATION.name, MappingConfidence.LOW.name,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        val kept = db.providerModelAliasDao().get("ph", "mh")!!
        assertEquals(MappingSource.OFFICIAL.name, kept.source)
        assertEquals(MappingConfidence.HIGH.name, kept.confidence)
        assertEquals(true, kept.verified)
    }

    /**
     * MAJOR6：併發跨源收斂到官方（串行語意下 OFFICIAL 恆勝）。
     * 空鍵併發：USER_OVERRIDE 先勝則 OFFICIAL 重試升級覆蓋；
     * OFFICIAL 先勝則 USER_OVERRIDE 重試被守衛拒絕；終態恆為 OFFICIAL／HIGH。
     */
    @Test
    fun concurrentCrossSourceAliasConvergesToOfficial() = runBlocking {
        store.saveCanonical("m-race")
        val results = listOf(
            async(Dispatchers.IO) {
                runCatching {
                    store.saveAlias(
                        "pc", "mc", "m-race",
                        MappingSource.OFFICIAL.name, MappingConfidence.HIGH.name, verified = true,
                    )
                }
            },
            async(Dispatchers.IO) {
                runCatching {
                    store.saveAlias(
                        "pc", "mc", "m-race",
                        MappingSource.USER_OVERRIDE.name, MappingConfidence.LOW.name,
                    )
                }
            },
        ).awaitAll()
        assertTrue(results.any { it.isSuccess })
        val final = db.providerModelAliasDao().get("pc", "mc")!!
        assertEquals(MappingSource.OFFICIAL.name, final.source)
        assertEquals(MappingConfidence.HIGH.name, final.confidence)
        assertEquals(1, db.providerModelAliasDao().byCanonical("m-race").size)
    }
}
