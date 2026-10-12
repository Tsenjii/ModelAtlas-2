package dev.modelatlas.app.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** These credentials are test fixtures only, never product data. */
@RunWith(AndroidJUnit4::class)
class AndroidCredentialVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = AndroidCredentialVault(context)
    private val id = UUID.randomUUID().toString()
    private val origin = "https://fixture.invalid:443"
    private val fixture = "test-fixture-not-a-real-key"

    @After fun clean() { vault.remove(id) }

    @Test fun encryptedValueSurvivesVaultRecreation() {
        vault.store(id, origin, fixture)
        assertEquals(fixture, AndroidCredentialVault(context).bearerToken(id, origin))
        val preferences = context.getSharedPreferences("credential_ciphertexts", Context.MODE_PRIVATE)
        assertFalse(preferences.all.values.any { it.toString().contains(fixture) })
        assertNotNull(preferences.getString("$id.iv", null))
    }

    @Test fun credentialCannotBeUsedAtAnotherOrigin() {
        vault.store(id, origin, fixture)
        try {
            vault.bearerToken(id, "https://different.invalid:443")
            fail("Origin mismatch must fail")
        } catch (expected: IllegalStateException) {
            assertFalse(expected.message.orEmpty().contains(fixture))
        }
    }

    @Test fun existingReferenceCannotBeSilentlyRebound() {
        vault.store(id, origin, fixture)
        try {
            vault.store(id, "https://different.invalid:443", "replacement-fixture")
            fail("Existing reference must not be replaced")
        } catch (_: IllegalStateException) {
            assertEquals(fixture, vault.bearerToken(id, origin))
        }
    }
}
