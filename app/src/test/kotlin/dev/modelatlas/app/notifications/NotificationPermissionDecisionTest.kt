package dev.modelatlas.app.notifications

import android.content.pm.PackageManager
import dev.modelatlas.app.background.NotificationDispatchResult
import dev.modelatlas.app.background.NotificationPermission as BackgroundPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI11 S3 — POST_NOTIFICATIONS manifest + runtime request seam, JVM vectors V1-V12
 * (spec /tmp/opencode/ai11-spec-notif-manifest.md §6.1).
 *
 * Pure-JVM only: no Android runtime, no Context, no launcher. V7-V9 exercise
 * background.NotificationPermission.dispatch; V11-V12 exercise NotificationSettingsState
 * defaults. Extends nothing; existing NotificationPermissionTest files are untouched.
 */
class NotificationPermissionDecisionTest {

    // V1: requiresRuntimeRequest guards the API 33 boundary (minSdk 26).
    @Test
    fun v1_requiresRuntimeRequestVersionGuard() {
        assertFalse(NotificationPermission.requiresRuntimeRequest(32))
        assertTrue(NotificationPermission.requiresRuntimeRequest(33))
        assertFalse(NotificationPermission.requiresRuntimeRequest(26))
        assertTrue(NotificationPermission.requiresRuntimeRequest(35))
    }

    // V2: pre-33 master ON never requests (manifest grant is enough).
    @Test
    fun v2_noRequestOnMasterEnablePre33() {
        assertFalse(
            NotificationPermission.shouldRequestOnMasterEnable(
                32, PackageManager.PERMISSION_DENIED,
            ),
        )
    }

    // V3: API 33 master ON + denied -> request.
    @Test
    fun v3_requestOnMasterEnableWhenDenied33() {
        assertTrue(
            NotificationPermission.shouldRequestOnMasterEnable(
                33, PackageManager.PERMISSION_DENIED,
            ),
        )
    }

    // V4: API 33 master ON + already granted -> no re-ask.
    @Test
    fun v4_noRequestOnMasterEnableWhenGranted33() {
        assertFalse(
            NotificationPermission.shouldRequestOnMasterEnable(
                33, PackageManager.PERMISSION_GRANTED,
            ),
        )
    }

    // V5: API 35 follows the same granted/denied rule as 33.
    @Test
    fun v5_requestRuleOn35MatchesGrantState() {
        assertFalse(
            NotificationPermission.shouldRequestOnMasterEnable(
                35, PackageManager.PERMISSION_GRANTED,
            ),
        )
        assertTrue(
            NotificationPermission.shouldRequestOnMasterEnable(
                35, PackageManager.PERMISSION_DENIED,
            ),
        )
    }

    // V6: pre-33 isGranted holds regardless of the permission result.
    @Test
    fun v6_grantedBelowRuntimeApiRegardlessOfResult() {
        assertTrue(NotificationPermission.isGranted(26, PackageManager.PERMISSION_DENIED))
    }

    // V7: dispatch with permitted=false never sends; silent NotDelivered.
    @Test
    fun v7_dispatchDeniedIsSilentNotDelivered() {
        var sent = false
        val result = BackgroundPermission.dispatch(permitted = false) { sent = true }

        assertFalse(sent)
        assertTrue(result is NotificationDispatchResult.NotDelivered)
        assertEquals(
            BackgroundPermission.NOT_DELIVERED_PERMISSION_MISSING,
            (result as NotificationDispatchResult.NotDelivered).reason,
        )
        assertEquals(
            "POST_NOTIFICATIONS_DENIED",
            (result as NotificationDispatchResult.NotDelivered).reason,
        )
    }

    // V8: dispatch with permitted=true and a clean send delivers.
    @Test
    fun v8_dispatchPermittedDelivers() {
        var sent = false
        val result = BackgroundPermission.dispatch(permitted = true) { sent = true }

        assertTrue(sent)
        assertTrue(result is NotificationDispatchResult.Delivered)
    }

    // V9: dispatch with a throwing send maps to NotDelivered, no crash.
    @Test
    fun v9_dispatchThrowingSendIsNotDelivered() {
        val result = BackgroundPermission.dispatch(permitted = true) {
            throw IllegalStateException("boom")
        }

        assertTrue(result is NotificationDispatchResult.NotDelivered)
        assertEquals(
            "IllegalStateException",
            (result as NotificationDispatchResult.NotDelivered).reason,
        )
    }

    // V10: canonical channel metadata stays secret-free.
    @Test
    fun v10_channelMetadataIsSecretFree() {
        val metadata = NotificationPermission.channelMetadata()
        assertTrue(metadata.id.isNotBlank())
        assertTrue(metadata.name.isNotBlank())
        assertTrue(metadata.description.isNotBlank())

        val haystack = (metadata.id + " " + metadata.name + " " + metadata.description).lowercase()
        for (forbidden in listOf("key", "token", "secret", "pat", "credential", "password")) {
            assertFalse("channel metadata must not contain '$forbidden'", haystack.contains(forbidden))
        }
    }

    // V11: store state defaults to master OFF, no rules, nothing enabled.
    @Test
    fun v11_settingsStateDefaultsToOff() {
        val state = NotificationSettingsState()

        assertFalse(state.masterEnabled)
        assertTrue(state.enabledRules.isEmpty())
        for (rule in NotificationRule.entries) {
            assertFalse("default must not enable $rule", state.enabled(rule))
        }
    }

    // V12: master + single rule enables only that rule, independent of OS permission.
    @Test
    fun v12_singleRuleEnabledOnlyForThatRule() {
        val state = NotificationSettingsState(
            masterEnabled = true,
            enabledRules = setOf(NotificationRule.BECAME_FREE),
        )

        assertTrue(state.enabled(NotificationRule.BECAME_FREE))
        for (rule in NotificationRule.entries) {
            if (rule != NotificationRule.BECAME_FREE) {
                assertFalse("only BECAME_FREE must be enabled, not $rule", state.enabled(rule))
            }
        }
        // Permission state lives outside the store: grant/deny flips the request
        // decision but never the saved state itself.
        assertTrue(
            NotificationPermission.shouldRequestOnMasterEnable(33, PackageManager.PERMISSION_DENIED),
        )
        assertFalse(
            NotificationPermission.shouldRequestOnMasterEnable(33, PackageManager.PERMISSION_GRANTED),
        )
        assertTrue(state.enabled(NotificationRule.BECAME_FREE))
        assertFalse(state.enabled(NotificationRule.QUOTA_LOW))
    }
}
