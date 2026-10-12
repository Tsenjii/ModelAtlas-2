package dev.modelatlas.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Phase3 儲存測試：account/provider/credential 一致性、UNIQUE、enable/disable、delete、rollback。Fixture 只在 androidTest。 */
@RunWith(AndroidJUnit4::class)
class AtlasPhase3Test {
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

    private fun connection(
        id: String = "off-1",
        providerId: String = "p1",
        accountId: String = "a1",
        credentialId: String = "c1",
        endpointId: String = "e1",
        offeringId: String = id,
    ) = ConnectionRecord(
        id = id,
        providerId = providerId,
        accountId = accountId,
        credentialId = credentialId,
        endpointId = endpointId,
        name = "Seek",
        baseUrl = "https://seekai.cc/v1",
        endpointUrl = "https://seekai.cc/v1/chat/completions",
        modelId = "m1",
        offeringId = offeringId,
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
        rateLimitHeaders = emptyMap(),
        priceState = "FREE_NOW",
        quotaState = "UNKNOWN",
    )

    @Test
    fun accountCheckinIsolation() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        repo.addAccount("p1", "a2")
        repo.completeCheckin("p1", "2026-09-27", 10L, "a1")
        repo.completeCheckin("p1", "2026-09-27", 20L, "a2")
        repo.completeCheckin("p1", "2026-09-27", 30L)
        assertEquals(10L, repo.getCheckin("p1", "2026-09-27", "a1")?.completedAt)
        assertEquals(20L, repo.getCheckin("p1", "2026-09-27", "a2")?.completedAt)
        assertEquals(30L, repo.getCheckin("p1", "2026-09-27", "")?.completedAt)
        assertEquals(3, db.checkinDao().listByProviderDate("p1", "2026-09-27").size)
    }

    @Test
    fun crossProviderRefsRejectAndNoOrphan() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        repo.addConnection(connection(id = "off-2", providerId = "p2", accountId = "a2", credentialId = "c2", endpointId = "e2", offeringId = "off-2"))
        try {
            repo.attachCredential("off-1", "a2", "c3", "x", "av-3")
            fail("expected cross-provider reject")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(db.credentialDao().getById("c3"))
        assertNull(db.availabilityDao().getById("av-3"))
        try {
            repo.attachCredential("off-1", "a1", "c2", "x", "av-4")
            fail("expected cross-account credential reject")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(db.availabilityDao().getById("av-4"))
        try {
            repo.completeCheckin("p1", "2026-09-27", 1L, "a2")
            fail("expected cross-provider checkin reject")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun secondCredentialSameOffering() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        repo.addAccount("p1", "a2")
        repo.attachCredential("off-1", "a2", "c2", "第二憑證", "av-2")
        assertEquals(2, repo.connections.first().size)
        assertEquals(2, repo.managementConnections.first().size)
        assertNotNull(db.offeringDao().getById("off-1"))
        assertEquals("第二憑證", db.credentialDao().getById("c2")?.label)
    }

    @Test
    fun enableDisableManagementProjection() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        repo.addAccount("p1", "a2")
        repo.attachCredential("off-1", "a2", "c2", "第二憑證", "av-2")
        repo.setCredentialEnabled("c2", false)
        val enabledOnly = repo.connections.first()
        assertEquals(1, enabledOnly.size)
        assertEquals("off-1", enabledOnly[0].id)
        val all = repo.managementConnections.first()
        assertEquals(2, all.size)
        val disabled = all.first { it.credentialId == "c2" }
        assertEquals(false, disabled.credentialEnabled)
        assertEquals(true, all.first { it.credentialId == "c1" }.credentialEnabled)
        repo.setCredentialEnabled("c2", true)
        assertEquals(2, repo.connections.first().size)
    }

    @Test
    fun deleteCredentialMetadataKeepsTelemetry() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        repo.save(sample())
        repo.deleteCredentialMetadata("c1")
        assertNull(db.credentialDao().getById("c1"))
        assertEquals(0, db.availabilityDao().byCredential("c1").size)
        assertEquals(0, repo.connections.first().size)
        assertEquals(0, repo.managementConnections.first().size)
        assertNotNull(db.sampleDao().getById("s1"))
        assertNotNull(db.evidenceDao().byRequest("s1"))
    }

    @Test
    fun transactionRollbackOnFailedAttachAndAdd() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        try {
            repo.attachCredential("no-offering", "a1", "cX", "x", "av-x")
            fail("expected offering not found")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(db.credentialDao().getById("cX"))
        assertNull(db.availabilityDao().getById("av-x"))
        try {
            repo.addConnection(connection(id = "off-9", accountId = "a9", credentialId = "c9", endpointId = "e9", offeringId = "off-9"))
            fail("expected duplicate provider abort")
        } catch (_: Exception) {
        }
        assertNull(db.accountDao().getById("a9"))
        assertNull(db.credentialDao().getById("c9"))
        assertNull(db.availabilityDao().getById("off-9"))
    }

    @Test
    fun duplicateUniqueConstraints() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        var failed = false
        try {
            repo.addAccount("p1", "a1")
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        failed = false
        try {
            repo.attachCredential("off-1", "a1", "c1", "主要憑證", "off-1")
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        var trioFailed = false
        try {
            db.availabilityDao().insert(CredentialAvailabilityEntity("other-id", "off-1", "a1", "c1"))
        } catch (_: SQLiteConstraintException) {
            trioFailed = true
        } catch (_: Exception) {
            trioFailed = true
        }
        assertTrue(trioFailed)
        assertEquals("主要憑證", db.credentialDao().getById("c1")?.label)
    }

    @Test
    fun credentialReuseDoesNotOverwriteLabel() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        db.endpointDao().insert(EndpointEntity("e2", "p1", "https://seekai.cc/v1", "https://seekai.cc/v1/chat/completions", "CHAT_COMPLETIONS"))
        db.offeringDao().insert(OfferingEntity("off-2", "p1", "e2", "m2", true, "MANUAL"))
        repo.attachCredential("off-2", "a1", "c1", "HackedLabel", "av-3")
        assertEquals("主要憑證", db.credentialDao().getById("c1")?.label)
        assertEquals(2, repo.connections.first().size)
    }

    @Test
    fun providerCheckinConfigHttpsValidation() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.addConnection(connection())
        repo.setProviderCheckinConfig("p1", "https://example.com/checkin", true)
        assertEquals("https://example.com/checkin", db.providerDao().getById("p1")?.checkinUrl)
        assertEquals(true, db.providerDao().getById("p1")?.requiresDailyCheckin)
        try {
            repo.setProviderCheckinConfig("p1", "http://example.com/checkin", true)
            fail("expected http reject")
        } catch (_: IllegalArgumentException) {
        }
        try {
            repo.setProviderCheckinConfig("p1", null, true)
            fail("expected null url reject when requires")
        } catch (_: IllegalArgumentException) {
        }
        try {
            repo.setProviderCheckinConfig("p1", "https://example.com/checkin?x=1", true)
            fail("expected query reject")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("https://example.com/checkin", db.providerDao().getById("p1")?.checkinUrl)
        repo.setProviderCheckinConfig("p1", null, false)
        assertNull(db.providerDao().getById("p1")?.checkinUrl)
    }
}
