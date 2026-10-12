package dev.modelatlas.app.security

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 僅使用測試 fixture：不讀取真實憑證，也不含產品資料。 */
@RunWith(AndroidJUnit4::class)
class CredentialBoundaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = AndroidCredentialVault(context)
    private val created = mutableListOf<String>()

    private val secret = "boundary-fixture-not-a-real-key"
    private val secondSecret = "second-boundary-fixture-not-a-real-key"
    private val inlineSecret = "boundary-userinfo-fixture-secret"
    private val querySecret = "boundary-query-fixture-secret"
    private val fragmentSecret = "boundary-fragment-fixture-secret"
    private val canonicalOrigin = "https://boundary.fixture.invalid:443"

    private fun reference(): String = UUID.randomUUID().toString().also { created += it }

    private fun preferences() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun preferencesFile(): File {
        val base = context.applicationInfo.dataDir
            ?: context.filesDir.parent
            ?: throw AssertionError("app data dir unavailable")
        return File(File(base, "shared_prefs"), "$PREFERENCES.xml")
    }

    private fun failureOf(block: () -> Any?): Throwable {
        val result = runCatching(block)
        assertTrue("boundary case must be rejected", result.isFailure)
        return result.exceptionOrNull()!!
    }

    private fun assertNoLeak(error: Throwable, vararg values: String) {
        val text = error.message.orEmpty()
        for (value in values) assertFalse("rejection must not echo input: $text", text.contains(value))
    }

    private fun assertNotStored(id: String) {
        val prefs = preferences()
        assertFalse(prefs.contains("$id.origin"))
        assertFalse(prefs.contains("$id.iv"))
        assertFalse(prefs.contains("$id.ciphertext"))
        assertFalse(vault.has(id))
    }

    @After fun clean() {
        for (id in created) vault.remove(id)
        created.clear()
    }

    @Test fun plainHttpOriginIsRejectedBeforeAnythingIsPersisted() {
        val id = reference()
        val error = failureOf { vault.store(id, "http://boundary.fixture.invalid:443", secret) }
        assertTrue(error is IllegalArgumentException)
        assertEquals("HTTPS required", error.message)
        assertNoLeak(error, secret, "http://")
        assertNotStored(id)
    }

    @Test fun userInfoOriginIsRejectedAndUserInfoIsNeverEchoed() {
        val id = reference()
        val error = failureOf {
            vault.store(id, "https://user:$inlineSecret@boundary.fixture.invalid:443", secret)
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals("HTTPS required", error.message)
        assertNoLeak(error, inlineSecret, secret, "user:")
        assertNotStored(id)
    }

    @Test fun queryOriginIsRejectedAndQueryIsNeverEchoed() {
        val id = reference()
        val error = failureOf {
            vault.store(id, "https://boundary.fixture.invalid:443?api-key=$querySecret", secret)
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals("Query and fragment not allowed", error.message)
        assertNoLeak(error, querySecret, "api-key", secret)
        assertNotStored(id)
    }

    @Test fun fragmentOriginIsRejectedAndFragmentIsNeverEchoed() {
        val id = reference()
        val error = failureOf {
            vault.store(id, "https://boundary.fixture.invalid:443#$fragmentSecret", secret)
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals("Query and fragment not allowed", error.message)
        assertNoLeak(error, fragmentSecret, "#", secret)
        assertNotStored(id)
    }

    @Test fun pathOriginIsRejectedBecauseScopeIsOriginOnly() {
        val basePath = reference()
        val error = failureOf { vault.store(basePath, "$canonicalOrigin/v1", secret) }
        assertTrue(error is IllegalArgumentException)
        assertEquals("Origin must not contain a path", error.message)
        assertNoLeak(error, secret, "/v1")
        assertNotStored(basePath)

        val chatPath = reference()
        val chatError = failureOf {
            vault.store(chatPath, "$canonicalOrigin/v1/chat/completions", secret)
        }
        assertEquals("Origin must not contain a path", chatError.message)
        assertNotStored(chatPath)
    }

    @Test fun bearerTokenAppliesTheSameOriginBoundary() {
        val id = reference()
        vault.store(id, canonicalOrigin, secret)
        val unsafe = listOf(
            "http://boundary.fixture.invalid:443",
            "https://user:$inlineSecret@boundary.fixture.invalid:443",
            "https://boundary.fixture.invalid:443?api-key=$querySecret",
            "https://boundary.fixture.invalid:443#$fragmentSecret",
            "$canonicalOrigin/v1",
        )
        for (origin in unsafe) {
            val error = failureOf { vault.bearerToken(id, origin) }
            assertTrue(error is IllegalArgumentException)
            assertNoLeak(error, secret, inlineSecret, querySecret, fragmentSecret)
            assertTrue(vault.has(id))
            assertEquals(secret, vault.bearerToken(id, canonicalOrigin))
        }
    }

    @Test fun httpsHostIsNormalizedAndDefaultPortIsEquivalent() {
        val id = reference()
        vault.store(id, "https://BOUNDARY.fixture.INVALID:443", secret)
        assertEquals(canonicalOrigin, preferences().getString("$id.origin", null))
        val equivalent = listOf(
            canonicalOrigin,
            "https://boundary.fixture.invalid",
            "https://boundary.fixture.invalid/",
            "https://BoUnDaRy.Fixture.Invalid:443",
        )
        for (origin in equivalent) assertEquals(origin, secret, vault.bearerToken(id, origin))
    }

    @Test fun nonDefaultPortAndOtherHostAreDifferentScopes() {
        val defaultPort = reference()
        val explicitPort = reference()
        vault.store(defaultPort, canonicalOrigin, secret)
        vault.store(explicitPort, "https://boundary.fixture.invalid:8443", secondSecret)

        val otherPort = failureOf { vault.bearerToken(defaultPort, "https://boundary.fixture.invalid:8443") }
        assertTrue(otherPort is IllegalStateException)
        assertEquals("Credential scope mismatch", otherPort.message)
        assertNoLeak(otherPort, secret)

        val defaultAsExplicit = failureOf { vault.bearerToken(explicitPort, "https://boundary.fixture.invalid:443") }
        assertTrue(defaultAsExplicit is IllegalStateException)
        assertEquals("Credential scope mismatch", defaultAsExplicit.message)

        val otherHost = failureOf { vault.bearerToken(defaultPort, "https://other.fixture.invalid:443") }
        assertTrue(otherHost is IllegalStateException)
        assertNoLeak(otherHost, secret)

        assertEquals(secret, vault.bearerToken(defaultPort, "https://boundary.fixture.invalid"))
        assertEquals(secondSecret, vault.bearerToken(explicitPort, "https://boundary.fixture.invalid:8443/"))
    }

    @Test fun hasTracksStateAcrossStoreFailedStoreAndRemove() {
        val id = reference()
        assertFalse(vault.has(id))
        vault.store(id, canonicalOrigin, secret)
        assertTrue(vault.has(id))

        val error = failureOf { vault.store(id, canonicalOrigin, secondSecret) }
        assertTrue(error is IllegalStateException)
        assertEquals("Credential already exists", error.message)
        assertNoLeak(error, secret, secondSecret)
        assertTrue(vault.has(id))
        assertEquals(secret, vault.bearerToken(id, canonicalOrigin))

        assertTrue(vault.remove(id))
        assertFalse(vault.has(id))
    }

    @Test fun removeIsIdempotentAndCredentialCannotBeReadBack() {
        val id = reference()
        vault.store(id, canonicalOrigin, secret)

        assertTrue(vault.remove(id))
        assertFalse(vault.remove(id))
        assertFalse(vault.remove(id))
        assertFalse(vault.has(id))

        val error = failureOf { vault.bearerToken(id, canonicalOrigin) }
        assertTrue(error is IllegalStateException)
        assertNoLeak(error, secret)
        assertNotStored(id)
    }

    @Test fun legacyFixtureStaysAccessibleAfterLaterOperations() {
        val legacy = reference()
        val disposable = reference()
        val other = reference()
        vault.store(legacy, canonicalOrigin, secret)

        vault.store(other, "https://boundary.fixture.invalid:8443", secondSecret)
        failureOf { vault.store(disposable, "http://boundary.fixture.invalid:443", secret) }
        failureOf { vault.store(disposable, "$canonicalOrigin/v1", secret) }
        assertTrue(vault.remove(other))

        val reopened = AndroidCredentialVault(context)
        assertTrue(reopened.has(legacy))
        assertEquals(secret, reopened.bearerToken(legacy, canonicalOrigin))
        assertEquals(secret, reopened.bearerToken(legacy, "https://boundary.fixture.invalid"))
        assertFalse(reopened.has(disposable))
        assertFalse(reopened.has(other))
    }

    @Test fun sharedPreferencesNeverHoldThePlaintextSecret() {
        val id = reference()
        vault.store(id, canonicalOrigin, secret)

        val ciphertext = preferences().getString("$id.ciphertext", null)
        val iv = preferences().getString("$id.iv", null)
        assertNotNull("ciphertext must be persisted", ciphertext)
        assertNotNull("iv must be persisted", iv)
        assertNotEquals(secret, ciphertext)
        assertNotEquals(Base64.encodeToString(secret.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), ciphertext)
        assertFalse(String(Base64.decode(ciphertext!!, Base64.NO_WRAP), Charsets.UTF_8).contains(secret))
        assertEquals(canonicalOrigin, preferences().getString("$id.origin", null))

        for ((key, value) in preferences().all) {
            assertFalse("plaintext secret in preference $key", value.toString().contains(secret))
        }

        val file = preferencesFile()
        assertTrue("shared_prefs file must exist", file.isFile)
        val raw = file.readText()
        assertFalse("plaintext secret in ${file.name}", raw.contains(secret))
        assertTrue(raw.contains("$id.ciphertext"))
    }

    private companion object { const val PREFERENCES = "credential_ciphertexts" }
}
