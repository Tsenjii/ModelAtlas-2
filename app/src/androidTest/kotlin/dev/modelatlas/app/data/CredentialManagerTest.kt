package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PROV-006／PROV-009 credential 管理（Room v9）：
 * search／filter／batch enable-disable 走 DAO；custom audit 的 headerPrefix／isSecret 由新欄承載；
 * secret value 不在本層（只斷言未儲存於 DB 欄位）。
 */
@RunWith(AndroidJUnit4::class)
class CredentialManagerTest {
    private lateinit var db: AtlasDatabase
    private lateinit var manager: CredentialManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        manager = CredentialManager(db)
        runBlocking {
            db.providerDao().insert(ProviderEntity(providerId = "p1", name = "P1"))
            db.providerDao().insert(ProviderEntity(providerId = "p2", name = "P2"))
            db.accountDao().insert(AccountEntity(accountId = "a1", providerId = "p1"))
            db.accountDao().insert(AccountEntity(accountId = "a2", providerId = "p2"))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun registerCustomHeaderAuditsPrefixAndSecretFlag() = runBlocking {
        val audit = manager.registerCustomHeader(
            credentialId = "c1",
            accountId = "a1",
            label = "custom",
            headerName = "X-Api-Key",
            headerPrefix = "Bearer ",
            note = "team key",
            group = "g1",
            priority = 5,
        )
        assertEquals(CredentialAuthModes.CUSTOM_HEADER, audit.authMode)
        assertEquals("X-Api-Key", audit.headerName)
        assertEquals("Bearer ", audit.headerPrefix)
        assertTrue(audit.isSecret)
        assertEquals("p1", audit.providerId)
        // secret value 不應有任何欄位保存；DB 只存非敏感前綴。
        val row = db.credentialDao().getById("c1")!!
        assertEquals("Bearer ", row.headerPrefix)
        assertTrue(row.isSecret)
        assertEquals("team key", row.note)
    }

    @Test
    fun registerRejectsBlankHeaderName() = runBlocking {
        var threw = false
        try {
            manager.registerCustomHeader("c1", "a1", "l", headerName = " ", headerPrefix = "Bearer ")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun searchFiltersByDimensionAndQuery() = runBlocking {
        manager.registerCustomHeader("c1", "a1", "alpha", "X1", "Bearer ", group = "g1")
        manager.registerCustomHeader("c2", "a1", "beta", "X2", "Token ", group = "g2")
        manager.registerCustomHeader("c3", "a2", "gamma", "X3", "Bearer ", group = "g1")

        assertEquals(listOf("c1", "c2", "c3").sorted(), manager.search().map { it.credentialId }.sorted())
        assertEquals(listOf("c1", "c2").sorted(), manager.search(providerId = "p1").map { it.credentialId }.sorted())
        assertEquals(listOf("c1", "c3").sorted(), manager.search(group = "g1").map { it.credentialId }.sorted())
        assertEquals(listOf("c2").sorted(), manager.search(query = "bet").map { it.credentialId }.sorted())
        assertEquals(
            listOf("c2"),
            manager.search(authMode = CredentialAuthModes.CUSTOM_HEADER, query = "beta").map { it.credentialId },
        )
        // 未知 auth mode fail-closed。
        var threw = false
        try {
            manager.search(authMode = "NOPE")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun batchEnableDisableWritesThroughDao() = runBlocking {
        manager.registerCustomHeader("c1", "a1", "alpha", "X1", "Bearer ")
        manager.registerCustomHeader("c2", "a1", "beta", "X2", "Bearer ")
        manager.registerCustomHeader("c3", "a2", "gamma", "X3", "Bearer ")

        assertEquals(2, manager.setEnabledBatch(listOf("c1", "c2"), false))
        assertFalse(db.credentialDao().getById("c1")!!.enabled)
        assertFalse(db.credentialDao().getById("c2")!!.enabled)
        assertTrue(db.credentialDao().getById("c3")!!.enabled)
        assertEquals(2, manager.search(enabled = false).size)
        assertEquals(2, manager.setEnabledBatch(listOf("c1", "c2", "c2"), true))
        assertTrue(db.credentialDao().getById("c2")!!.enabled)
        assertEquals(0, manager.setEnabledBatch(emptyList(), false))
    }

    @Test
    fun auditMissingReturnsNull() = runBlocking {
        assertNull(manager.audit("missing"))
    }

    @Test
    fun legacyDefaultAuthModeStaysUnknown_notApiKey() = runBlocking {
        // RV1：未顯式傳入 authMode 的舊列／預設列保持未知 UNKNOWN，不得冒充 API_KEY；
        // 新列已知值由 call site 顯式傳入（如下 CUSTOM_HEADER）。
        db.credentialDao().insert(CredentialEntity(credentialId = "c-legacy", accountId = "a1"))
        val legacy = db.credentialDao().getById("c-legacy")!!
        assertEquals("UNKNOWN", legacy.authMode)
        // 未知不行經 API_KEY 篩出；不篩仍可見。
        assertTrue(db.credentialDao().search(null, null, null, null, "API_KEY", null).none { it.credentialId == "c-legacy" })
        assertEquals(listOf("c-legacy"), db.credentialDao().search(null, null, null, null, "UNKNOWN", null).map { it.credentialId })
        assertTrue(manager.search().any { it.credentialId == "c-legacy" })
        assertTrue(manager.search(authMode = CredentialAuthModes.API_KEY).none { it.credentialId == "c-legacy" })
    }
}
