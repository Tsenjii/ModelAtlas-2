package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR008-F2 store no-change invariants (PRODUCT_SPEC §18; Contract §23.1; D-008).
 *
 * This class locks the CURRENT [MyModelsStore] clock behaviour and changes nothing
 * in production: default `nowMillis = 0L` stays legacy `0`, explicit fixed timestamps
 * with USER/ASSISTANT/EXPLORE sources persist exactly, REPLACE re-add keeps a single
 * row with the latest timestamp, and unrelated legacy rows plus sentinel
 * offering/sample/evidence rows are byte-identical after clock writes.
 *
 * Scope: store level only (true in-memory Room + true [MyModelsStore]). It does NOT
 * cover the VM public flow (`AtlasViewModel.addMyModelExplicit` / `toggleMyModel`
 * clock injection) — that belongs to the separate VM clock owner. No secrets,
 * network, adapters, inference, or history deletion; fixtures live only in androidTest.
 */
@RunWith(AndroidJUnit4::class)
class MyModelsClockStoreTest {
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

    @Test
    fun legacyDefaultStaysZeroExplicitSourcesPersistFixedTimestampsAndReplaceKeepsLatest() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)

        // Legacy default: omitted nowMillis persists exactly 0 (no clock invention).
        assertTrue(store.addToMyModels("p-clock", "legacy-model"))
        assertEquals(0L, db.myModelsDao().all().single { it.providerModelId == "legacy-model" }.addedAt)

        // Explicit fixed timestamps + each allowed source persist exactly.
        assertTrue(store.addToMyModels("p-clock", "user-model", source = "USER", nowMillis = T_USER))
        assertTrue(store.addToMyModels("p-clock", "asst-model", source = "ASSISTANT", nowMillis = T_ASST))
        assertTrue(store.addToMyModels("p-clock", "explore-model", source = "EXPLORE", nowMillis = T_EXPL))
        assertEquals(
            MyModelEntity("p-clock", "user-model", T_USER, "USER"),
            db.myModelsDao().all().single { it.providerModelId == "user-model" },
        )
        assertEquals(
            MyModelEntity("p-clock", "asst-model", T_ASST, "ASSISTANT"),
            db.myModelsDao().all().single { it.providerModelId == "asst-model" },
        )
        assertEquals(
            MyModelEntity("p-clock", "explore-model", T_EXPL, "EXPLORE"),
            db.myModelsDao().all().single { it.providerModelId == "explore-model" },
        )

        // REPLACE re-add: same key keeps a single row with the latest timestamp.
        assertTrue(store.addToMyModels("p-clock", "user-model", source = "USER", nowMillis = T_USER_LATEST))
        assertEquals(1, db.myModelsDao().count("p-clock", "user-model"))
        assertEquals(
            MyModelEntity("p-clock", "user-model", T_USER_LATEST, "USER"),
            db.myModelsDao().all().single { it.providerModelId == "user-model" },
        )
        assertEquals(4, db.myModelsDao().all().size)
        assertTrue(store.isMyModel("p-clock", "user-model"))
    }

    @Test
    fun unrelatedLegacyRowAndSentinelTablesUnaffectedByClockWrites() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)

        // Sentinel catalog/history rows (offering + sample + evidence) predate clock writes.
        db.providerDao().insert(ProviderEntity(providerId = "p-sentinel", name = "Sentinel"))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = "e-sentinel",
                providerId = "p-sentinel",
                baseUrl = "https://sentinel.fixture.invalid/v1",
                endpointUrl = "https://sentinel.fixture.invalid/v1/chat/completions",
            ),
        )
        db.offeringDao().insert(
            OfferingEntity(
                id = "cat:p-sentinel:m-sentinel",
                providerId = "p-sentinel",
                endpointId = "e-sentinel",
                providerModelId = "m-sentinel",
                manual = false,
                source = "OFFICIAL",
                hidden = false,
            ),
        )
        db.sampleDao().upsert(
            SampleEntity(
                id = "s-sentinel",
                providerId = "p-sentinel",
                accountId = "a-sentinel",
                safeCredentialId = "c-sentinel",
                endpointId = "e-sentinel",
                providerModelId = "m-sentinel",
                outcome = "SUCCESS",
            ),
        )
        db.evidenceDao().insert(
            EvidenceEntity(
                id = "ev-sentinel",
                requestId = "s-sentinel",
                providerId = "p-sentinel",
                modelId = "m-sentinel",
                outcome = "SUCCESS",
                status = 200,
                timestamp = 1_700_000_000_000L,
                source = "MANUAL_TEST",
                version = 1,
            ),
        )
        val sentinelOffering = db.offeringDao().getById("cat:p-sentinel:m-sentinel")
        val sentinelSample = db.sampleDao().getById("s-sentinel")
        val sentinelEvidence = db.evidenceDao().getById("ev-sentinel")

        // Unrelated legacy membership row (default clock) coexists with the target key.
        assertTrue(store.addToMyModels("p-other", "other-model"))

        // Target-key clock writes: explicit add then REPLACE re-add with a later timestamp.
        assertTrue(store.addToMyModels("p-clock", "m1", source = "USER", nowMillis = T_USER))
        assertTrue(store.addToMyModels("p-clock", "m1", source = "USER", nowMillis = T_USER_LATEST))

        // Unrelated legacy row keeps exactly addedAt 0 / USER and stays a member.
        assertEquals(
            MyModelEntity("p-other", "other-model", 0L, "USER"),
            db.myModelsDao().all().single { it.providerModelId == "other-model" },
        )
        assertTrue(store.isMyModel("p-other", "other-model"))

        // Target key is a single row at the latest timestamp; total membership is exact.
        assertEquals(1, db.myModelsDao().count("p-clock", "m1"))
        assertEquals(
            MyModelEntity("p-clock", "m1", T_USER_LATEST, "USER"),
            db.myModelsDao().all().single { it.providerModelId == "m1" },
        )
        assertEquals(2, db.myModelsDao().all().size)

        // Sentinel offering/sample/evidence/history rows are unchanged (D-006: no purge).
        assertEquals(sentinelOffering, db.offeringDao().getById("cat:p-sentinel:m-sentinel"))
        assertEquals(sentinelSample, db.sampleDao().getById("s-sentinel"))
        assertEquals(sentinelEvidence, db.evidenceDao().getById("ev-sentinel"))
    }

    private companion object {
        const val T_USER = 1_700_000_000_000L
        const val T_ASST = 1_700_000_000_001L
        const val T_EXPL = 1_700_000_000_002L
        const val T_USER_LATEST = 1_700_000_000_010L
    }
}
