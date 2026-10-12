package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fixture 僅存於 androidTest，不當產品資料。③-1 My Models membership（D-008／Contract §23.1）。 */
@RunWith(AndroidJUnit4::class)
class MyModelsMembershipFlowTest {
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

    private suspend fun seedCatalogOffering(db: AtlasDatabase, providerId: String, endpointId: String, modelId: String, hidden: Boolean) {
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
                hidden = hidden,
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

    @Test
    fun addThenRemoveKeepsOfferingAndTelemetry() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        seedCatalogOffering(db, "p1", "e-p1", "m1", hidden = false)
        seedSample(db, "s-1", "p1", "m1")

        assertTrue(store.addToMyModels("p1", "m1"))
        assertEquals(setOf(MyModelKey("p1", "m1")), store.myModels.first())
        assertTrue(store.isMyModel("p1", "m1"))

        assertFalse(store.removeFromMyModels("p1", "m1"))
        assertFalse(store.isMyModel("p1", "m1"))
        assertEquals(emptySet<MyModelKey>(), store.myModels.first())
        assertEquals(emptySet<MyModelKey>(), store.myModelKeys())

        val offering = db.offeringDao().getById("cat:p1:m1")
        assertNotNull(offering)
        assertEquals("m1", offering?.providerModelId)
        val telemetry = db.sampleDao().getById("s-1")
        assertNotNull(telemetry)
        assertEquals("m1", telemetry?.providerModelId)
    }

    @Test
    fun duplicateAddIsIdempotentSingleRow() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)

        assertTrue(store.addToMyModels("p1", "m1", source = "USER", nowMillis = 100L))
        assertTrue(store.addToMyModels("p1", "m1", source = "ASSISTANT", nowMillis = 200L))

        assertEquals(setOf(MyModelKey("p1", "m1")), store.myModels.first())
        assertEquals(1, store.myModelKeys().size)
        assertEquals(1, db.myModelsDao().all().size)
        assertTrue(store.isMyModel("p1", "m1"))
    }

    @Test
    fun crossProviderMembershipIsolated() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        seedCatalogOffering(db, "p1", "e-p1", "m1", hidden = false)
        seedCatalogOffering(db, "p2", "e-p2", "m2", hidden = false)

        assertTrue(store.addToMyModels("p1", "m1"))
        assertTrue(store.addToMyModels("p2", "m2"))
        assertEquals(
            setOf(MyModelKey("p1", "m1"), MyModelKey("p2", "m2")),
            store.myModels.first(),
        )

        assertFalse(store.removeFromMyModels("p1", "m1"))

        assertFalse(store.isMyModel("p1", "m1"))
        assertTrue(store.isMyModel("p2", "m2"))
        assertEquals(setOf(MyModelKey("p2", "m2")), store.myModelKeys())
        assertEquals(setOf(MyModelKey("p2", "m2")), store.myModels.first())
    }

    @Test
    fun hiddenOfferingExplicitAddStillMembership() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        seedCatalogOffering(db, "p1", "e-p1", "m1", hidden = true)

        assertTrue(store.addToMyModels("p1", "m1", source = "EXPLORE"))

        assertTrue(store.isMyModel("p1", "m1"))
        assertEquals(setOf(MyModelKey("p1", "m1")), store.myModels.first())
        val offering = db.offeringDao().getById("cat:p1:m1")
        assertNotNull(offering)
        assertTrue(offering!!.hidden)
    }

    @Test
    fun catalogWriteWithoutExplicitAddDoesNotPolluteMyModels() = runBlocking {
        val db = memoryDb()
        val store = MyModelsStore(db)
        seedCatalogOffering(db, "p1", "e-p1", "m1", hidden = false)
        seedSample(db, "s-1", "p1", "m1")

        assertEquals(emptySet<MyModelKey>(), store.myModels.first())
        assertEquals(emptySet<MyModelKey>(), store.myModelKeys())
        assertFalse(store.isMyModel("p1", "m1"))
        assertNotNull(db.offeringDao().getById("cat:p1:m1"))
        assertNotNull(db.sampleDao().getById("s-1"))
    }
}
