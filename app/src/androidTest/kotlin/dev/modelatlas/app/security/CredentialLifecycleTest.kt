package dev.modelatlas.app.security

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
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
import java.util.UUID

/**
 * 憑證生命週期的安全邊界：停用、啟用、刪除在 Room metadata 與 Keystore vault 之間的邊界。
 *
 * 只使用 androidTest fixture：不讀任何真實憑證，secret 為固定的測試字串。
 * 以 UUID 隔離 vault refs，測試結束只移除自己建立的 refs，不動其他測試資料。
 */
@RunWith(AndroidJUnit4::class)
class CredentialLifecycleTest {
    private lateinit var context: Context
    private lateinit var vault: AndroidCredentialVault
    private val databases = mutableListOf<AtlasDatabase>()
    private val credentialRefs = mutableListOf<String>()

    private val secret = "lifecycle-fixture-not-a-real-key"
    private val origin = "https://lifecycle.fixture.invalid"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        vault = AndroidCredentialVault(context)
    }

    @After
    fun tearDown() {
        for (ref in credentialRefs) runCatching { vault.remove(ref) }
        credentialRefs.clear()
        for (db in databases) runCatching { db.close() }
        databases.clear()
    }

    @Test
    fun disablingHidesConnectionsButKeepsManagedRowAndSecret() = runBlocking {
        val db = database()
        val repository = AtlasRepository(db)
        val lifecycle = CredentialLifecycle(repository, vault)
        val credentialId = newCredentialRef()
        val connectionId = givenManagedConnection(repository, credentialId)
        vault.store(credentialId, origin, secret)
        assertTrue(connectionIds(repository).contains(connectionId))

        lifecycle.setEnabled(credentialId, false)

        assertFalse("disabled credential must not offer connections", connectionIds(repository).contains(connectionId))
        assertTrue("disabled credential stays manageable", managedIds(repository).contains(connectionId))
        assertCredentialRowDisabled(db, credentialId)
        assertTrue("disabling must not destroy the secret", vault.has(credentialId))
        assertNoPlaintextSecret()
    }

    @Test
    fun enablingRequiresExistingVaultSecretAndNeverEchoesIt() = runBlocking {
        val db = database()
        val repository = AtlasRepository(db)
        val lifecycle = CredentialLifecycle(repository, vault)
        val credentialId = newCredentialRef()
        val connectionId = givenManagedConnection(repository, credentialId)
        lifecycle.setEnabled(credentialId, false)

        val error = failureOf { lifecycle.setEnabled(credentialId, true) }

        assertFalse("no secret may be resurrected by enabling", vault.has(credentialId))
        assertCredentialRowDisabled(db, credentialId)
        assertFalse(connectionIds(repository).contains(connectionId))
        assertTrue(managedIds(repository).contains(connectionId))
        assertNoSecretLeak(error, secret)
        assertNoPlaintextSecret()
    }

    @Test
    fun enablingWithStoredVaultSecretRestoresConnection() = runBlocking {
        val db = database()
        val repository = AtlasRepository(db)
        val lifecycle = CredentialLifecycle(repository, vault)
        val credentialId = newCredentialRef()
        val connectionId = givenManagedConnection(repository, credentialId)
        vault.store(credentialId, origin, secret)
        lifecycle.setEnabled(credentialId, false)
        assertFalse(connectionIds(repository).contains(connectionId))

        lifecycle.setEnabled(credentialId, true)

        assertTrue(
            "enabled credential must offer its connection again",
            connectionIds(repository).contains(connectionId),
        )
        assertTrue(vault.has(credentialId))
        assertNoPlaintextSecret()
    }

    @Test
    fun deleteRemovesSecretCredentialAvailabilityButKeepsTelemetry() = runBlocking {
        val db = database()
        val repository = AtlasRepository(db)
        val lifecycle = CredentialLifecycle(repository, vault)
        val credentialId = newCredentialRef()
        val connectionId = givenManagedConnection(repository, credentialId)
        vault.store(credentialId, origin, secret)
        val requestId = UUID.randomUUID().toString()
        repository.save(sample(requestId, credentialId))

        lifecycle.delete(credentialId)

        assertNoVaultEntry(credentialId)
        assertCredentialRowsRemoved(db, credentialId, connectionId)
        assertFalse(connectionIds(repository).contains(connectionId))
        assertFalse("deleted credential leaves nothing to manage", managedIds(repository).contains(connectionId))
        val kept = repository.samples.first().single { it.id == requestId }
        assertEquals(credentialId, kept.safeCredentialId)
        assertEquals(Outcome.SUCCESS, kept.outcome)
        val evidence = db.evidenceDao().byRequest(requestId)
        assertNotNull("evidence must survive credential deletion", evidence)
        assertEquals("SUCCESS", evidence?.outcome)
        assertNoPlaintextSecret()
    }

    @Test
    fun deleteRetryCompletesAfterSecretWasAlreadyRemovedManually() = runBlocking {
        val db = database()
        val repository = AtlasRepository(db)
        val lifecycle = CredentialLifecycle(repository, vault)
        val credentialId = newCredentialRef()
        val connectionId = givenManagedConnection(repository, credentialId)
        vault.store(credentialId, origin, secret)
        assertTrue("earlier attempt already dropped the secret", vault.remove(credentialId))

        lifecycle.delete(credentialId)

        assertNoVaultEntry(credentialId)
        assertCredentialRowsRemoved(db, credentialId, connectionId)
        assertFalse(managedIds(repository).contains(connectionId))
        lifecycle.delete(credentialId) // 已完成的刪除重放亦為冪等。
        val error = failureOf { lifecycle.setEnabled(credentialId, true) }
        assertNoSecretLeak(error, secret)
        assertNoPlaintextSecret()
    }

    private fun newCredentialRef(): String =
        UUID.randomUUID().toString().also { credentialRefs += it }

    private fun database(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { databases += it }

    private suspend fun givenManagedConnection(repository: AtlasRepository, credentialId: String): String {
        val connectionId = UUID.randomUUID().toString()
        repository.addConnection(
            ConnectionRecord(
                id = connectionId,
                providerId = UUID.randomUUID().toString(),
                accountId = UUID.randomUUID().toString(),
                credentialId = credentialId,
                endpointId = UUID.randomUUID().toString(),
                name = "Lifecycle Fixture",
                baseUrl = "$origin/v1",
                endpointUrl = "$origin/v1/chat/completions",
                modelId = "lifecycle-fixture-model",
            ),
        )
        return connectionId
    }

    private fun sample(id: String, safeCredentialId: String) = PerformanceSample(
        id = id,
        providerId = "lifecycle-fixture-provider",
        accountId = "lifecycle-fixture-account",
        safeCredentialId = safeCredentialId,
        endpointId = "lifecycle-fixture-endpoint",
        providerModelId = "lifecycle-fixture-model",
        canonicalModelId = null,
        group = null,
        operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.PLAYGROUND,
        requestStartedAt = 1000L,
        completedAt = 1200L,
        streaming = true,
        usage = Usage(inputTokens = 3L, outputTokens = 5L),
        outcome = Outcome.SUCCESS,
        priceState = "FREE_NOW",
        quotaState = "UNKNOWN",
    )

    private suspend fun connectionIds(repository: AtlasRepository): List<String> =
        repository.connections.first().map { it.id }

    /** 管理清單必須仍看得到停用憑證，否則使用者無從恢復。 */
    private suspend fun managedIds(repository: AtlasRepository): List<String> =
        repository.managementConnections.first().map { it.id }

    /** 停用後 metadata row 保留但 enabled 為 false；直接讀 row 以免依賴新的 DAO 投影。 */
    private fun assertCredentialRowDisabled(db: AtlasDatabase, credentialId: String) {
        assertEquals(1L, credentialCount(db, credentialId).toLong())
        val enabled = credentialEnabled(db, credentialId)
        assertNotNull("credential metadata row must survive disabling", enabled)
        assertFalse("disabled credential must not be enabled", enabled!!)
    }

    /** 刪除後 credentials 與 credential_availability 都不得留下，歷史樣本另外保留。 */
    private suspend fun assertCredentialRowsRemoved(
        db: AtlasDatabase,
        credentialId: String,
        connectionId: String,
    ) {
        assertEquals(0L, credentialCount(db, credentialId).toLong())
        assertNull("availability row must be removed with the credential", db.availabilityDao().getById(connectionId))
    }

    private fun credentialCount(db: AtlasDatabase, credentialId: String): Int =
        db.query("SELECT COUNT(*) FROM credentials WHERE credentialId = ?", arrayOf<Any?>(credentialId)).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun credentialEnabled(db: AtlasDatabase, credentialId: String): Boolean? =
        db.query("SELECT enabled FROM credentials WHERE credentialId = ?", arrayOf<Any?>(credentialId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        }

    private fun assertNoVaultEntry(credentialId: String) {
        assertFalse("secret must be gone from the vault", vault.has(credentialId))
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        assertFalse(preferences.contains("$credentialId.ciphertext"))
        assertFalse(preferences.contains("$credentialId.iv"))
        assertFalse(preferences.contains("$credentialId.origin"))
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        val result = runCatching { block() }
        assertTrue("credential lifecycle must reject this case", result.isFailure)
        return result.exceptionOrNull()!!
    }

    private fun assertNoSecretLeak(error: Throwable, vararg values: String) {
        val text = error.message.orEmpty() + "\n" + error.toString()
        for (value in values) assertFalse("lifecycle failure must not echo secret: $text", text.contains(value))
    }

    /** 只掃描 persistence 內容；fixture secret 不該以明文存在任何 preference。 */
    private fun assertNoPlaintextSecret() {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        for ((key, value) in preferences.all) {
            assertFalse("plaintext secret in preference $key", value.toString().contains(secret))
        }
    }

    private companion object { const val PREFERENCES = "credential_ciphertexts" }
}
