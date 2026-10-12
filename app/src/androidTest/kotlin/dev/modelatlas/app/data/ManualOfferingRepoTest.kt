package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6 session N：MODEL-006/007 接線測試（記憶體 db，不寫磁碟 fixture）。
 *
 * TRACEABILITY: MODEL-006（manual hidden offering）/ MODEL-007（selected-key usable projection）。
 * Spec: PRODUCT_SPEC §8（manual offering 需實測 evidence 才 promote）、
 * §6（selected-key 語意）、§10（MANUAL/USER 分槽、LOW 不升 official）。
 *
 * Fixture 僅存於 androidTest，不當產品資料；只用 safe credential refs（c1/c2），無 raw key/PAT。
 */
@RunWith(AndroidJUnit4::class)
class ManualOfferingRepoTest {
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

    /** 父列（provider/endpoint/account/credential）預建；offering FK RESTRICT 要求父列存在。 */
    private suspend fun seedParents(db: AtlasDatabase) {
        db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = "e1",
                providerId = "p1",
                baseUrl = "https://p1.example/v1",
                endpointUrl = "https://p1.example/v1/chat/completions",
            ),
        )
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = "e2",
                providerId = "p1",
                baseUrl = "https://p1.example/v1",
                endpointUrl = "https://p1.example/v1/chat/completions",
            ),
        )
        db.accountDao().insert(AccountEntity(accountId = "a1", providerId = "p1"))
        db.credentialDao().insert(CredentialEntity(credentialId = "c1", accountId = "a1"))
        db.credentialDao().insert(CredentialEntity(credentialId = "c2", accountId = "a1"))
    }

    private fun hiddenDraft(
        endpointId: String = "e1",
        modelId: String = "m1",
        source: String = MANUAL_OFFERING_SOURCE_MANUAL,
    ): OfferingDraft = ManualOfferingStore.createHiddenOffering(
        providerId = "p1",
        endpointId = endpointId,
        providerModelId = modelId,
        source = source,
        evidenceId = "ev-1",
        observedAt = 1000L,
    )

    @Test
    fun addManualOfferingInsertsHiddenRowWithTripleDerivedId() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val id = repo.addManualOffering(hiddenDraft())
        assertEquals("manual:p1:e1:m1", id)
        val stored = db.offeringDao().getById(id)!!
        assertEquals("p1", stored.providerId)
        assertEquals("e1", stored.endpointId)
        assertEquals("m1", stored.providerModelId)
        assertTrue(stored.manual)
        assertEquals("MANUAL", stored.source)
        assertTrue(stored.hidden)
        assertNull(stored.verificationEvidenceId)
    }

    @Test
    fun addManualOfferingIsIdempotentOnTriple() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val first = repo.addManualOffering(hiddenDraft())
        val second = repo.addManualOffering(hiddenDraft())
        assertEquals(first, second)
        assertEquals(1, db.offeringDao().byProvider("p1").size)
    }

    @Test
    fun sameModelOnDifferentEndpointIsDistinctOffering() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val id1 = repo.addManualOffering(hiddenDraft(endpointId = "e1"))
        val id2 = repo.addManualOffering(hiddenDraft(endpointId = "e2"))
        assertTrue(id1 != id2)
        assertEquals(2, db.offeringDao().byProvider("p1").size)
    }

    @Test
    fun officialSourceDraftIsRejected() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val draft = hiddenDraft().copy(source = "OFFICIAL")
        var failed = false
        try {
            repo.addManualOffering(draft)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(db.offeringDao().byProvider("p1").isEmpty())
    }

    @Test
    fun unverifiedOfferingIsInvisibleInUsableRowsButRetained() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val id = repo.addManualOffering(hiddenDraft())
        val rows = repo.usableRowsFor("a1", "c1", null)
        assertTrue(rows.isEmpty())
        // 失敗/未驗證不刪列：last-good 留存。
        assertEquals(id, db.offeringDao().getById(id)!!.id)
    }

    @Test
    fun verifiedOfferingIsVisibleInUsableRows() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val verified = hiddenDraft().copy(hidden = false, verificationEvidenceId = "ev-verify-1")
        val id = repo.addManualOffering(verified)
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-1",
                offeringId = id,
                accountId = "a1",
                credentialId = "c1",
                priceState = "PAID",
                quotaState = "QUOTA_AVAILABLE",
            ),
        )
        val rows = repo.usableRowsFor("a1", "c1", null)
        assertEquals(1, rows.size)
        assertEquals(id, rows[0].offeringId)
        assertEquals("PAID", rows[0].priceState)
        assertEquals("QUOTA_AVAILABLE", rows[0].quotaState)
        assertEquals("MANUAL", rows[0].sourceLabel)
        assertTrue(rows[0].hasAvailability)
    }

    @Test
    fun promoteWithEmptyEvidenceReturnsRejectedNotThrow() {
        val draft = ManualOfferingStore.createHiddenOffering(
            providerId = "p1",
            endpointId = "e1",
            providerModelId = "m1",
            evidenceId = "ev-1",
            observedAt = 1000L,
        )
        val result = promoteManualOffering(draft, "")
        assertTrue(result is ManualPromoteResult.Rejected)
        assertTrue((result as ManualPromoteResult.Rejected).reason.isNotBlank())
        assertTrue(draft.hidden)
    }

    @Test
    fun promoteWithEvidenceReturnsPromotedVisible() {
        val draft = ManualOfferingStore.createHiddenOffering(
            providerId = "p1",
            endpointId = "e1",
            providerModelId = "m1",
            evidenceId = "ev-1",
            observedAt = 1000L,
        )
        val result = promoteManualOffering(draft, "ev-verify-1")
        assertTrue(result is ManualPromoteResult.Promoted)
        val promoted = (result as ManualPromoteResult.Promoted).draft
        assertTrue(ManualOfferingStore.isVisible(promoted))
        assertTrue(ManualOfferingStore.isUsable(promoted))
    }

    @Test
    fun downgradeOnFailureKeepsRowAndNeverRemoves() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val id = repo.addManualOffering(hiddenDraft())
        val draft = hiddenDraft()
        val downgraded = ManualOfferingStore.downgradeOnFailure(draft, "ev-fail-1", "timeout")
        assertTrue(!downgraded.removed)
        assertTrue(downgraded.hidden)
        assertEquals("ev-fail-1", downgraded.lastFailureEvidenceId)
        // 一次失敗不刪列、不標 REMOVED。
        assertEquals(id, db.offeringDao().getById(id)!!.id)
    }

    @Test
    fun usableRowsAreIsolatedByCredential() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val verified = hiddenDraft().copy(hidden = false, verificationEvidenceId = "ev-verify-1")
        val id = repo.addManualOffering(verified)
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-c1",
                offeringId = id,
                accountId = "a1",
                credentialId = "c1",
                priceState = "PAID",
                quotaState = "QUOTA_AVAILABLE",
            ),
        )
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-c2",
                offeringId = id,
                accountId = "a1",
                credentialId = "c2",
                priceState = "FREE_NOW",
                quotaState = "QUOTA_LOW",
            ),
        )
        val rowsC1 = repo.usableRowsFor("a1", "c1", null)
        val rowsC2 = repo.usableRowsFor("a1", "c2", null)
        assertEquals(1, rowsC1.size)
        assertEquals(1, rowsC2.size)
        assertEquals("PAID", rowsC1[0].priceState)
        assertEquals("FREE_NOW", rowsC2[0].priceState)
        assertEquals("QUOTA_AVAILABLE", rowsC1[0].quotaState)
        assertEquals("QUOTA_LOW", rowsC2[0].quotaState)
    }

    @Test
    fun usableRowsAreIsolatedByGroup() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val verified = hiddenDraft().copy(hidden = false, verificationEvidenceId = "ev-verify-1")
        val id = repo.addManualOffering(verified)
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-g1",
                offeringId = id,
                accountId = "a1",
                credentialId = "c1",
                priceState = "PAID",
                quotaState = "QUOTA_AVAILABLE",
                group = "g1",
            ),
        )
        val matched = repo.usableRowsFor("a1", "c1", "g1")
        assertEquals(1, matched.size)
        assertTrue(matched[0].hasAvailability)
        val mismatched = repo.usableRowsFor("a1", "c1", "g2")
        assertEquals(1, mismatched.size)
        assertTrue(!mismatched[0].hasAvailability)
        assertEquals("UNKNOWN", mismatched[0].priceState)
    }

    @Test
    fun officialRowWithSameIdIsNotReturnedAsManual() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        db.offeringDao().insert(
            OfferingEntity(
                id = "manual:p1:e1:m1",
                providerId = "p1",
                endpointId = "e1",
                providerModelId = "m1",
                manual = false,
                source = "OFFICIAL",
                hidden = false,
                verificationEvidenceId = null,
            ),
        )
        var failed = false
        try {
            repo.addManualOffering(hiddenDraft())
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
        val stored = db.offeringDao().getById("manual:p1:e1:m1")!!
        assertTrue(!stored.manual)
        assertEquals("OFFICIAL", stored.source)
        assertEquals(1, db.offeringDao().byProvider("p1").size)
    }

    @Test
    fun verifiedResendUpdatesHiddenAndVerification() = runBlocking {
        val db = memoryDb()
        seedParents(db)
        val repo = AtlasRepository(db)
        val id = repo.addManualOffering(hiddenDraft())
        assertTrue(db.offeringDao().getById(id)!!.hidden)
        assertNull(db.offeringDao().getById(id)!!.verificationEvidenceId)
        val promoted = (promoteManualOffering(hiddenDraft(), "ev-verify-1") as ManualPromoteResult.Promoted).draft
        val second = repo.addManualOffering(promoted)
        assertEquals(id, second)
        val stored = db.offeringDao().getById(id)!!
        assertTrue(!stored.hidden)
        assertEquals("ev-verify-1", stored.verificationEvidenceId)
        db.availabilityDao().insert(
            CredentialAvailabilityEntity(
                id = "av-verify",
                offeringId = id,
                accountId = "a1",
                credentialId = "c1",
                priceState = "PAID",
                quotaState = "QUOTA_AVAILABLE",
            ),
        )
        val rows = repo.usableRowsFor("a1", "c1", null)
        assertEquals(1, rows.size)
        assertEquals(id, rows[0].offeringId)
    }
}
