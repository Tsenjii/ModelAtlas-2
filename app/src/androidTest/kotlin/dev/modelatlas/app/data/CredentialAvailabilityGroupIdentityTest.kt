package dev.modelatlas.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-003 availability exact group identity（Room 14 foundation regression）。
 *
 * 鎖定語意：availabilityGroupIdentity(null)="n"；非 null＝"v"+原 group（空字串/
 * Unicode/大小寫原樣保留，不混）。CredentialAvailabilityEntity 追加 groupIdentity
 * （預設由 group 計算）；AvailabilityDao.insert 正規化 identity（防 copy(group)
 * 沿用舊 key）；byExactScope(offeringId,accountId,credentialId,group) 以 IS 等值
 * 精確比對，null 不當 wildcard。Room13→14 只修 availability identity；本測試不
 * 建 seed 的 secret-like 名稱，fixture 僅測試用，不當產品資料。
 */
@RunWith(AndroidJUnit4::class)
class CredentialAvailabilityGroupIdentityTest {
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

    /** FK RESTRICT 順序：provider → endpoint → account → credential → offering。 */
    private suspend fun seedScope(db: AtlasDatabase) {
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = "e1",
                providerId = "p1",
                baseUrl = "https://p1.example/v1",
                endpointUrl = "https://p1.example/v1/chat/completions",
                protocol = "CHAT_COMPLETIONS",
            ),
        )
        db.accountDao().insert(AccountEntity(accountId = "a1", providerId = "p1"))
        db.credentialDao().insert(CredentialEntity(credentialId = "c1", accountId = "a1"))
        db.offeringDao().insert(
            OfferingEntity(
                id = "off-1",
                providerId = "p1",
                endpointId = "e1",
                providerModelId = "m1",
                manual = false,
                source = "OFFICIAL",
                hidden = false,
                verificationEvidenceId = "ev-1",
            ),
        )
    }

    private fun availability(
        id: String,
        group: String?,
        priceState: String = "UNKNOWN",
        quotaState: String = "UNKNOWN",
    ) = CredentialAvailabilityEntity(
        id = id,
        offeringId = "off-1",
        accountId = "a1",
        credentialId = "c1",
        priceState = priceState,
        quotaState = quotaState,
        group = group,
    )

    private suspend fun expectAbort(block: suspend () -> Unit) {
        try {
            block()
            fail("expected SQLiteConstraintException (ABORT)")
        } catch (_: SQLiteConstraintException) {
        }
    }

    @Test
    fun sameOfferingAccountKeyCoexistAcrossGroupsAndReadExact() = runBlocking {
        val db = memoryDb()
        seedScope(db)
        db.availabilityDao().insert(availability("av-a", "vip"))
        db.availabilityDao().insert(availability("av-b", "free"))
        db.availabilityDao().insert(availability("av-n", null))
        assertEquals(3, db.availabilityDao().byCredential("c1").size)
        assertEquals("av-a", db.availabilityDao().byExactScope("off-1", "a1", "c1", "vip").firstOrNull()?.id)
        assertEquals("av-b", db.availabilityDao().byExactScope("off-1", "a1", "c1", "free").firstOrNull()?.id)
        assertEquals("av-n", db.availabilityDao().byExactScope("off-1", "a1", "c1", null).firstOrNull()?.id)
        assertTrue(db.availabilityDao().byExactScope("off-1", "a1", "c1", "VIP").isEmpty())
    }

    @Test
    fun duplicateNullGroupAndDuplicateConcreteGroupAbortKeepOldRowsUnchanged() = runBlocking {
        val db = memoryDb()
        seedScope(db)
        db.availabilityDao().insert(availability("av-1", null, priceState = "PAID"))
        db.availabilityDao().insert(availability("av-2", "vip", priceState = "FREE_NOW"))
        expectAbort { db.availabilityDao().insert(availability("av-3", null, priceState = "UNKNOWN")) }
        expectAbort { db.availabilityDao().insert(availability("av-4", "vip", priceState = "PARTIALLY_FREE")) }
        assertEquals(2, db.availabilityDao().byCredential("c1").size)
        assertEquals("PAID", db.availabilityDao().getById("av-1")?.priceState)
        assertEquals("FREE_NOW", db.availabilityDao().getById("av-2")?.priceState)
        assertNull(db.availabilityDao().getById("av-3"))
        assertNull(db.availabilityDao().getById("av-4"))
    }

    @Test
    fun nullEmptyUnicodeCaseGroupsStayDistinctAndVerbatim() = runBlocking {
        assertEquals("n", availabilityGroupIdentity(null))
        assertEquals("v", availabilityGroupIdentity(""))
        assertEquals("vVIP", availabilityGroupIdentity("VIP"))
        assertEquals("vvip", availabilityGroupIdentity("vip"))
        assertEquals("v台灣", availabilityGroupIdentity("台灣"))
        assertEquals(
            5,
            setOf(
                availabilityGroupIdentity(null),
                availabilityGroupIdentity(""),
                availabilityGroupIdentity("VIP"),
                availabilityGroupIdentity("vip"),
                availabilityGroupIdentity("台灣"),
            ).size,
        )
        val db = memoryDb()
        seedScope(db)
        db.availabilityDao().insert(availability("av-1", null))
        db.availabilityDao().insert(availability("av-2", ""))
        db.availabilityDao().insert(availability("av-3", "VIP"))
        db.availabilityDao().insert(availability("av-4", "vip"))
        db.availabilityDao().insert(availability("av-5", "台灣"))
        assertEquals(5, db.availabilityDao().byCredential("c1").size)
        assertEquals("", db.availabilityDao().byExactScope("off-1", "a1", "c1", "").firstOrNull()?.group)
        assertEquals("VIP", db.availabilityDao().byExactScope("off-1", "a1", "c1", "VIP").firstOrNull()?.group)
        assertEquals("vip", db.availabilityDao().byExactScope("off-1", "a1", "c1", "vip").firstOrNull()?.group)
        assertEquals("台灣", db.availabilityDao().byExactScope("off-1", "a1", "c1", "台灣").firstOrNull()?.group)
        assertTrue(db.availabilityDao().byExactScope("off-1", "a1", "c1", "Vip").isEmpty())
    }

    @Test
    fun daoInsertCorrectsStaleCopyGroupIdentityAndExactScopeReadsNewGroup() = runBlocking {
        val db = memoryDb()
        seedScope(db)
        val stale = CredentialAvailabilityEntity(
            id = "av-1",
            offeringId = "off-1",
            accountId = "a1",
            credentialId = "c1",
            group = "A",
        )
        db.availabilityDao().insert(stale.copy(group = "B"))
        val stored = db.availabilityDao().getById("av-1")!!
        assertEquals("B", stored.group)
        assertEquals("vB", stored.groupIdentity)
        assertEquals("av-1", db.availabilityDao().byExactScope("off-1", "a1", "c1", "B").firstOrNull()?.id)
        assertTrue(db.availabilityDao().byExactScope("off-1", "a1", "c1", "A").isEmpty())
    }

    @Test
    fun usableRowsForIsolatesTwoGroupStatesOnPersistedScope() = runBlocking {
        val db = memoryDb()
        seedScope(db)
        db.availabilityDao().insert(availability("av-a", "grp-a", priceState = "PAID", quotaState = "QUOTA_LOW"))
        db.availabilityDao().insert(availability("av-b", "grp-b", priceState = "FREE_NOW", quotaState = "QUOTA_AVAILABLE"))
        val repo = AtlasRepository(db)
        val rowsA = repo.usableRowsFor("a1", "c1", "grp-a")
        val rowsB = repo.usableRowsFor("a1", "c1", "grp-b")
        assertEquals(1, rowsA.size)
        assertEquals("PAID", rowsA[0].priceState)
        assertEquals("QUOTA_LOW", rowsA[0].quotaState)
        assertEquals("grp-a", rowsA[0].group)
        assertEquals("off-1", rowsA[0].offeringId)
        assertEquals("e1", rowsA[0].endpointId)
        assertEquals("a1", rowsA[0].accountId)
        assertEquals("c1", rowsA[0].credentialId)
        assertEquals(1, rowsB.size)
        assertEquals("FREE_NOW", rowsB[0].priceState)
        assertEquals("QUOTA_AVAILABLE", rowsB[0].quotaState)
        assertEquals("grp-b", rowsB[0].group)
        assertEquals("off-1", rowsB[0].offeringId)
        assertEquals("e1", rowsB[0].endpointId)
        assertEquals("a1", rowsB[0].accountId)
        assertEquals("c1", rowsB[0].credentialId)
    }

    @Test
    fun availabilityInsertsAndReadersLeaveHistoryAndMembershipSlotsUntouched() = runBlocking {
        val db = memoryDb()
        seedScope(db)
        val repo = AtlasRepository(db)
        db.availabilityDao().insert(availability("av-a", "grp-a"))
        db.availabilityDao().insert(availability("av-n", null))
        repo.usableRowsFor("a1", "c1", "grp-a")
        repo.usableRowsFor("a1", "c1", null)
        assertEquals(2, db.availabilityDao().byCredential("c1").size)
        assertEquals(0, db.myModelsDao().all().size)
        assertEquals(0, rawCount(db, "samples"))
        assertEquals(0, rawCount(db, "evidence"))
        assertEquals(0, rawCount(db, "price_snapshots"))
        assertEquals(0, rawCount(db, "quota_snapshots"))
    }

    private fun rawCount(db: AtlasDatabase, table: String): Int {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }
}
