package dev.modelatlas.app.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.MainActivity
import dev.modelatlas.app.R
import dev.modelatlas.app.notifications.NotificationRule
import dev.modelatlas.app.notifications.NotificationSettingsState
import dev.modelatlas.app.notifications.NotificationSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** X-013: actual top-right Settings → VM → preferences, including Activity recreation. */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var previous: NotificationSettingsState
    private val app get() = compose.activity.application as AtlasApplication

    @Before fun rememberChoices() {
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

    private fun openSettings() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_settings)).performClick()
    }

    @Test fun realSettingsPersistIndependentChoicesAcrossRecreation() {
        lateinit var vm: AtlasViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOff()
        val chosen = NotificationRule.QUOTA_LOW
        compose.onNodeWithTag("notification_rule_${chosen.name}").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) {
            chosen in app.notificationSettings.state.value.enabledRules && !vm.notificationSettingsSaving.value
        }
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) {
            app.notificationSettings.state.value.masterEnabled && !vm.notificationSettingsSaving.value
        }
        assertEquals(NotificationSettingsState(true, setOf(chosen)), NotificationSettingsStore(app).state.value)

        compose.activityRule.scenario.recreate()
        openSettings()
        compose.onNodeWithTag("notification_master").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) {
            !app.notificationSettings.state.value.masterEnabled && !vm.notificationSettingsSaving.value
        }
        compose.onNodeWithTag("notification_rule_${chosen.name}").performScrollTo().assertIsOn()
        assertEquals(NotificationSettingsState(false, setOf(chosen)), NotificationSettingsStore(app).state.value)
    }
}
