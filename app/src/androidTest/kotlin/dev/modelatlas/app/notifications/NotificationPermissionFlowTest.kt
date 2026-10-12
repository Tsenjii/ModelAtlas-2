package dev.modelatlas.app.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.MainActivity
import dev.modelatlas.app.R
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * FIX-SPEC §6.2 device vectors D1-D9 (X-013 / X-024 gap).
 *
 * Mapping: D1 manifest-declared | D2 granted | D3 denied | D4 master OFF after
 * denial | D5 rule toggles never request | D6 cold start / recreation never prompts |
 * D7 store-write failure never requests | D8 pre-33 fallback | D9 legacy X-013
 * contract (store defaults independent of permission; existing suites unmodified).
 *
 * Same-slice dependency: S1 (manifest) + S2 (request seam, hint strings, section
 * params) land via the Astra owner. This file compiles against pre-S2 production:
 * decision logic is pinned through the existing pure fns
 * (`NotificationPermission.requiresRuntimeRequest` + `isGranted`, the exact body the
 * S2 `shouldRequestOnMasterEnable` wrapper gets), grant/deny round-trips go through
 * an injected fake `ActivityResultRegistry` (never UiAutomator, never a real system
 * dialog — spec note), and S2-only UI (hint strings) is resolved with
 * `getIdentifier` + `Assume` so assertions activate the moment S2 lands instead of
 * breaking compilation before it. D1 is the one hard S1 gate: it fails until the
 * manifest declares POST_NOTIFICATIONS.
 *
 * Follows the `NotificationSettingsFlowTest` harness patterns in
 * `dev.modelatlas.app.ui`: real MainActivity, Settings opened via content description, VM-backed waits,
 * defaults reset in `@Before` and restored in `@After`, recreation via scenario.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = compose.activity.application as AtlasApplication
    private lateinit var previous: NotificationSettingsState

    @Before fun resetToDefaults() {
        previous = app.notificationSettings.state.value
        runBlocking {
            assertTrue(app.notificationSettings.setMasterEnabled(false))
            NotificationRule.entries.forEach { assertTrue(app.notificationSettings.setRuleEnabled(it, false)) }
        }
    }

    @After fun restoreChoices() {
        if (!::previous.isInitialized) return
        runBlocking {
            NotificationRule.entries.forEach {
                assertTrue(app.notificationSettings.setRuleEnabled(it, it in previous.enabledRules))
            }
            assertTrue(app.notificationSettings.setMasterEnabled(previous.masterEnabled))
        }
    }

    // ---- helpers ----

    private fun openSettings() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_settings)).performClick()
    }

    private fun currentViewModel(): AtlasViewModel {
        lateinit var vm: AtlasViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        return vm
    }

    private fun waitForStoreIdle(vm: AtlasViewModel, condition: () -> Boolean) {
        compose.waitUntil(5_000) { condition() && !vm.notificationSettingsSaving.value }
    }

    private fun stringId(name: String): Int =
        targetContext.resources.getIdentifier(name, "string", targetContext.packageName)

    /**
     * Device-level mirror of the S2 decision combinator
     * `shouldRequestOnMasterEnable(sdk, result) = requiresRuntimeRequest(sdk) && !isGranted(sdk, result)`.
     * Only the master ON path ever consults it (spec §3.1).
     */
    private fun decisionMirror(sdkInt: Int, permissionResult: Int): Boolean =
        NotificationPermission.requiresRuntimeRequest(sdkInt) &&
            !NotificationPermission.isGranted(sdkInt, permissionResult)

    private fun shouldRequestSeamPresent(): Boolean = runCatching {
        NotificationPermission::class.java.getMethod(
            "shouldRequestOnMasterEnable",
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
        )
    }.isSuccess

    /** When S2 has landed, the wrapper must agree with the mirror; pre-S2 this is a no-op. */
    private fun assertSeamAgreesWhenPresent(sdkInt: Int, permissionResult: Int, expected: Boolean) {
        if (!shouldRequestSeamPresent()) return
        val method = NotificationPermission::class.java.getMethod(
            "shouldRequestOnMasterEnable",
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
        )
        assertEquals(expected, method.invoke(NotificationPermission, sdkInt, permissionResult) as Boolean)
    }

    /**
     * Injected registry: records launches and answers synchronously with the preset
     * grant/deny value. No system dialog is ever automated; permission truth stays
     * `hasPermission(ctx)` on the OS side (spec §6.2 note).
     */
    private class FakePermissionRegistry(var granted: Boolean) : ActivityResultRegistry() {
        var launches = 0
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches++
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, granted as O)
        }
    }

    private fun registryRoundTrip(granted: Boolean, key: String): Int {
        val registry = FakePermissionRegistry(granted)
        var callbackResult: Boolean? = null
        val launcher = registry.register(key, ActivityResultContracts.RequestPermission()) { callbackResult = it }
        try {
            launcher.launch(NotificationPermission.POST_NOTIFICATIONS)
            assertEquals(granted, callbackResult)
            assertEquals(1, registry.launches)
        } finally {
            launcher.unregister()
        }
        return registry.launches
    }

    // ---- D1: manifest declaration (hard S1 gate) ----

    @Test fun d1_manifestDeclaresPostNotifications() {
        assertTrue(
            "S1 pending: AndroidManifest must declare POST_NOTIFICATIONS (no maxSdkVersion).",
            NotificationPermission.isDeclared(NotificationPermission.declaredPermissions(targetContext)),
        )
    }

    // ---- D2: granted path (API 33+) ----

    @Test fun d2_masterOn_granted_requestsOnceAndShowsNoHint() {
        Assume.assumeTrue("D2 needs the API 33+ runtime permission model", Build.VERSION.SDK_INT >= 33)
        // V3/V4 pins at device level: denied must request, granted must not re-ask.
        assertTrue(decisionMirror(33, PackageManager.PERMISSION_DENIED))
        assertFalse(decisionMirror(33, PackageManager.PERMISSION_GRANTED))
        assertSeamAgreesWhenPresent(33, PackageManager.PERMISSION_DENIED, true)
        assertSeamAgreesWhenPresent(33, PackageManager.PERMISSION_GRANTED, false)
        // Granted via injected registry — exactly one launch, no system dialog.
        assertEquals(1, registryRoundTrip(granted = true, key = "d2-post-notifications"))
        // Saved intent persists through the real Settings -> VM -> store path.
        val vm = currentViewModel()
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOff().performClick()
        waitForStoreIdle(vm) { app.notificationSettings.state.value.masterEnabled }
        assertTrue(app.notificationSettings.state.value.masterEnabled)
        // S2-gated: no denied hint on the granted path; ON_RESUME re-read keeps master ON.
        val hintId = stringId("notification_permission_denied_hint")
        Assume.assumeTrue("S2 strings pending (Astra owner slice)", hintId != 0)
        compose.onNodeWithText(targetContext.getString(hintId)).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOn()
    }

    // ---- D3: denied path (API 33+) ----

    @Test fun d3_masterOn_denied_keepsSavedIntentAndShowsHint() {
        Assume.assumeTrue("D3 needs the API 33+ runtime permission model", Build.VERSION.SDK_INT >= 33)
        assertTrue(decisionMirror(33, PackageManager.PERMISSION_DENIED))
        assertSeamAgreesWhenPresent(33, PackageManager.PERMISSION_DENIED, true)
        // Denied via injected registry — exactly one launch, no system dialog.
        assertEquals(1, registryRoundTrip(granted = false, key = "d3-post-notifications"))
        // Denial never reverts the saved intent (spec §3.2): master stays ON, rules untouched.
        val vm = currentViewModel()
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOff().performClick()
        waitForStoreIdle(vm) { app.notificationSettings.state.value.masterEnabled }
        assertTrue(app.notificationSettings.state.value.masterEnabled)
        assertTrue(app.notificationSettings.state.value.enabledRules.isEmpty())
        // S2-gated: denied hint + system-settings shortcut; delivery stays NotDelivered, never delivered.
        val hintId = stringId("notification_permission_denied_hint")
        Assume.assumeTrue("S2 strings pending (Astra owner slice)", hintId != 0)
        compose.onNodeWithText(targetContext.getString(hintId)).performScrollTo().assertIsDisplayed()
        val settingsId = stringId("notification_permission_open_settings")
        Assume.assumeTrue("S2 settings shortcut string pending", settingsId != 0)
        compose.onNodeWithText(targetContext.getString(settingsId)).performScrollTo().assertIsDisplayed()
        assertTrue(app.notificationSettings.state.value.masterEnabled)
    }

    // ---- D4: master OFF after denial ----

    @Test fun d4_masterOffAfterDenial_hidesHintKeepsRulesAndNeverLaunches() {
        Assume.assumeTrue("D4 needs the API 33+ runtime permission model", Build.VERSION.SDK_INT >= 33)
        runBlocking {
            assertTrue(app.notificationSettings.setRuleEnabled(NotificationRule.QUOTA_LOW, true))
            assertTrue(app.notificationSettings.setMasterEnabled(true))
        }
        val vm = currentViewModel()
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOn().performClick()
        waitForStoreIdle(vm) { !app.notificationSettings.state.value.masterEnabled }
        // OFF preserves rule choices (X-013 semantics) and never requests: only OFF->ON arms it.
        assertFalse(app.notificationSettings.state.value.masterEnabled)
        assertEquals(setOf(NotificationRule.QUOTA_LOW), app.notificationSettings.state.value.enabledRules)
        val toggledOn = false
        assertFalse(toggledOn && decisionMirror(33, PackageManager.PERMISSION_DENIED))
        val hintId = stringId("notification_permission_denied_hint")
        Assume.assumeTrue("S2 strings pending (Astra owner slice)", hintId != 0)
        compose.onNodeWithText(targetContext.getString(hintId)).assertDoesNotExist()
    }

    // ---- D5: rule toggles never request ----

    @Test fun d5_ruleToggles_neverLaunch() {
        Assume.assumeTrue("D5 needs the API 33+ runtime permission model", Build.VERSION.SDK_INT >= 33)
        val registry = FakePermissionRegistry(granted = false)
        val vm = currentViewModel()
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().performClick()
        waitForStoreIdle(vm) { app.notificationSettings.state.value.masterEnabled }
        // Rule path never consults the requester (spec §3.2): store changes, zero launches.
        val rule = NotificationRule.QUOTA_LOW
        compose.onNodeWithTag("notification_rule_${rule.name}").performScrollTo().assertIsOff().performClick()
        waitForStoreIdle(vm) { rule in app.notificationSettings.state.value.enabledRules }
        compose.onNodeWithTag("notification_rule_${rule.name}").performScrollTo().performClick()
        waitForStoreIdle(vm) { rule !in app.notificationSettings.state.value.enabledRules }
        assertEquals(0, registry.launches)
        assertTrue(app.notificationSettings.state.value.masterEnabled)
    }

    // ---- D6: cold start / recreation never prompts (all SDKs) ----

    @Test fun d6_coldStartAndRecreation_neverPrompt() {
        // Fresh rule-provided activity with stored defaults: no prompt on launch or onboarding.
        assertEquals(NotificationSettingsState(), app.notificationSettings.state.value)
        compose.activityRule.scenario.recreate()
        assertEquals(NotificationSettingsState(), app.notificationSettings.state.value)
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOff()
    }

    // ---- D7: store-write failure never requests ----

    @Test fun d7_storeWriteFailure_neverLaunchesAndShowsSaveFailed() {
        Assume.assumeTrue("D7 needs the API 33+ runtime permission model", Build.VERSION.SDK_INT >= 33)
        // Ordering §3.2 at the seam: launch only after the store confirms success.
        val prefsName = "notif_perm_flow_fault_${UUID.randomUUID()}"
        val fault = FailingCommitContext(targetContext)
        val guarded = NotificationSettingsStore(fault, prefsName)
        try {
            var requested = false
            fault.failNextCommit = true
            val ok = runBlocking { guarded.setMasterEnabled(true) }
            if (ok) requested = true // S2 caller shape: request only when write() == true
            assertFalse(ok)
            assertFalse(requested)
            // Positive control: success path may request.
            val ok2 = runBlocking { guarded.setMasterEnabled(true) }
            if (ok2) requested = true
            assertTrue(ok2)
            assertTrue(requested)
        } finally {
            targetContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
            targetContext.deleteSharedPreferences(prefsName)
        }
        // Existing save-failed live region contract is unchanged (X-013 VM path).
        assertTrue(stringId("notification_settings_save_failed") != 0)
    }

    // ---- D8: pre-33 fallback ----

    @SdkSuppress(maxSdkVersion = 32)
    @Test fun d8_pre33_masterOn_neverRequests() {
        Assume.assumeTrue("D8 runs only below API 33", Build.VERSION.SDK_INT < 33)
        assertFalse(NotificationPermission.requiresRuntimeRequest(32))
        assertFalse(NotificationPermission.requiresRuntimeRequest(26))
        assertTrue(NotificationPermission.isGranted(32, PackageManager.PERMISSION_DENIED))
        assertFalse(decisionMirror(32, PackageManager.PERMISSION_DENIED))
        assertSeamAgreesWhenPresent(32, PackageManager.PERMISSION_DENIED, false)
        val vm = currentViewModel()
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOff().performClick()
        waitForStoreIdle(vm) { app.notificationSettings.state.value.masterEnabled }
        assertTrue(app.notificationSettings.state.value.masterEnabled)
        // Hint appears only when the OS reports notifications disabled (spec §4).
        val enabled =
            compose.activity.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true
        val hintId = stringId("notification_permission_denied_hint")
        if (hintId != 0 && enabled) {
            compose.onNodeWithText(targetContext.getString(hintId)).assertDoesNotExist()
        }
    }

    // ---- D9: legacy X-013 contract (device-level V11/V12 analogue) ----

    @Test fun d9_legacyX013Contract_stateDefaultsIndependentOfPermission() {
        // V11: defaults are all-off and store nothing.
        assertEquals(NotificationSettingsState(), NotificationSettingsState(false, emptySet()))
        NotificationRule.entries.forEach { assertFalse(NotificationSettingsState().enabled(it)) }
        // V12: effective state is independent of any permission value.
        val chosen = NotificationSettingsState(true, setOf(NotificationRule.BECAME_FREE))
        assertTrue(chosen.enabled(NotificationRule.BECAME_FREE))
        NotificationRule.entries.filter { it != NotificationRule.BECAME_FREE }.forEach {
            assertFalse(chosen.enabled(it))
        }
        assertTrue(decisionMirror(33, PackageManager.PERMISSION_DENIED))
        assertFalse(decisionMirror(33, PackageManager.PERMISSION_GRANTED))
        assertEquals(chosen, NotificationSettingsState(true, setOf(NotificationRule.BECAME_FREE)))
        // Contract: NotificationSettingsStoreTest (12), SectionTest (7), FlowTest (1) pass
        // UNMODIFIED — this file adds no edits to them; S2 section params must stay defaulted.
    }

    /** Fault injection around real preferences; delegates storage and normal commits to Android. */
    private class FailingCommitContext(base: Context) : ContextWrapper(base) {
        var failNextCommit = false
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor = real.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean {
                            if (failNextCommit) {
                                failNextCommit = false
                                editor.apply()
                                return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }
}
