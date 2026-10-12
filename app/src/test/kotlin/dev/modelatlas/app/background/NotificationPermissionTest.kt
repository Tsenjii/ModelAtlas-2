package dev.modelatlas.app.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X-024 acceptance 1 (metadata hygiene) + permission guard/dispatch semantics. */
class NotificationPermissionTest {

    private val secretTerms = listOf("key", "token", "pat", "secret", "credential", "authorization")

    @Test
    fun channelIsCreatedWithNoSecretsInMetadata() {
        val metadata = NotificationPermission.channelMetadata()

        assertEquals(NotificationPermission.CHANNEL_ID, metadata.id)
        val haystack = (metadata.id + " " + metadata.name + " " + metadata.description).lowercase()
        for (term in secretTerms) {
            assertFalse("channel metadata must not contain '$term'", haystack.contains(term))
        }
    }

    @Test
    fun runtimeGuardAndPermissionDeniedIsSilentNotCrash() {
        assertFalse(NotificationPermission.requiresRuntimePermission(32))
        assertTrue(NotificationPermission.requiresRuntimePermission(33))

        // API < 33: no runtime grant needed -> allowed regardless of probe.
        assertTrue(NotificationPermission.isGranted(26) { false })
        // API 33+: probe decides.
        assertFalse(NotificationPermission.isGranted(33) { false })
        assertTrue(NotificationPermission.isGranted(33) { true })

        var sent = false
        val result = NotificationPermission.dispatch(permitted = false) { sent = true }

        assertFalse(sent)
        assertTrue(result is NotificationDispatchResult.NotDelivered)
    }
}
