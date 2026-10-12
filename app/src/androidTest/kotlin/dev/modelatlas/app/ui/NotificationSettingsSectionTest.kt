package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.R
import dev.modelatlas.app.notifications.NotificationRule
import dev.modelatlas.app.notifications.NotificationSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** X-013 / D-2026-09-30-005 / PRODUCT_SPEC §16: real production section rendering. */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val ruleLabels = linkedMapOf(
        NotificationRule.BECAME_FREE to R.string.notification_rule_became_free,
        NotificationRule.QUOTA_LOW to R.string.notification_rule_quota_low,
        NotificationRule.PENDING_CHECKIN to R.string.notification_rule_pending_checkin,
        NotificationRule.FAVORITE_RECOVERED to R.string.notification_rule_favorite_recovered,
        NotificationRule.PRICE_CHANGE to R.string.notification_rule_price_change,
        NotificationRule.OUTAGE_RECOVERY to R.string.notification_rule_outage_recovery,
    )

    private val switchRole = SemanticsMatcher("Role.Switch") {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Switch
    }

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun ruleTag(rule: NotificationRule) = "notification_rule_${rule.name}"

    private data class Host(
        val state: MutableState<NotificationSettingsState>,
        val masterCalls: MutableList<Boolean>,
        val ruleCalls: MutableList<Pair<NotificationRule, Boolean>>,
    )

    private fun mount(
        initial: NotificationSettingsState = NotificationSettingsState(),
        enabled: Boolean = true,
        fontScale: Float = 1f,
    ): Host {
        val host = Host(mutableStateOf(initial), mutableListOf(), mutableListOf())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Surface(modifier = Modifier.width(280.dp)) {
                        // Like the Settings dialog, the host owns scrolling, not the section.
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            NotificationSettingsSection(
                                state = host.state.value,
                                onMasterChanged = { host.masterCalls += it },
                                onRuleChanged = { rule, checked -> host.ruleCalls += rule to checked },
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
        }
        return host
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun defaultsAreOffWithDistinctLabelledAccessibleRows() {
        val host = mount()
        row("notification_master")
            .assertIsOff()
            .assertIsEnabled()
            .assert(switchRole)
            .assertTextEquals(str(R.string.notification_master_label))
            .assertHeightIsAtLeast(48.dp)
        ruleLabels.forEach { (rule, label) ->
            row(ruleTag(rule))
                .assertIsOff()
                .assertIsEnabled()
                .assertIsDisplayed()
                .assert(switchRole)
                .assertTextEquals(str(label))
                .assertHeightIsAtLeast(48.dp)
        }
        // Unmerged tree too: no duplicate nested Switch toggle/hit targets.
        compose.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(7)
        assertTrue(host.masterCalls.isEmpty())
        assertTrue(host.ruleCalls.isEmpty())
    }

    @Test
    fun masterCallbackIsExactAndDisplayWaitsForParentState() {
        val host = mount()
        row("notification_master").performClick()
        assertEquals(listOf(true), host.masterCalls)
        assertTrue(host.ruleCalls.isEmpty())
        row("notification_master").assertIsOff()

        compose.runOnIdle { host.state.value = host.state.value.copy(masterEnabled = true) }
        row("notification_master").assertIsOn().performClick()
        assertEquals(listOf(true, false), host.masterCalls)
        assertTrue(host.ruleCalls.isEmpty())
    }

    @Test
    fun everyRuleReportsItsExactIdentityAndNewValueWhileMasterOff() {
        val host = mount()
        ruleLabels.keys.forEach { rule ->
            row(ruleTag(rule)).assertIsEnabled().assertIsOff().performClick()
            compose.runOnIdle {
                host.state.value = host.state.value.copy(enabledRules = setOf(rule))
            }
            row(ruleTag(rule)).assertIsOn().performClick()
            compose.runOnIdle {
                host.state.value = host.state.value.copy(enabledRules = emptySet())
            }
        }
        assertEquals(
            ruleLabels.keys.flatMap { listOf(it to true, it to false) },
            host.ruleCalls,
        )
        assertTrue(host.masterCalls.isEmpty())
        row("notification_master").assertIsOff()
    }

    @Test
    fun turningMasterOffPreservesAndShowsMixedRuleChoices() {
        val selected = setOf(NotificationRule.QUOTA_LOW, NotificationRule.FAVORITE_RECOVERED)
        val host = mount(NotificationSettingsState(masterEnabled = true, enabledRules = selected))
        row("notification_master").assertIsOn().performClick()
        assertEquals(listOf(false), host.masterCalls)
        assertTrue(host.ruleCalls.isEmpty())
        compose.runOnIdle { host.state.value = host.state.value.copy(masterEnabled = false) }

        row("notification_master").assertIsOff()
        compose.onNodeWithText(str(R.string.notification_master_off_hint))
            .performScrollTo().assertIsDisplayed()
        ruleLabels.keys.forEach { rule ->
            val node = row(ruleTag(rule)).assertIsEnabled().assertIsDisplayed()
            if (rule in selected) node.assertIsOn() else node.assertIsOff()
        }
        assertEquals(selected, host.state.value.enabledRules)
        assertTrue(host.ruleCalls.isEmpty())
    }

    @Test
    fun disabledSectionKeepsCheckedChoicesAndRejectsAllTouches() {
        val host = mount(
            NotificationSettingsState(masterEnabled = true, enabledRules = ruleLabels.keys.toSet()),
            enabled = false,
        )
        (listOf("notification_master") + ruleLabels.keys.map(::ruleTag)).forEach { tag ->
            row(tag).assertIsOn().assertIsNotEnabled().assert(switchRole)
                .performTouchInput { click(center) }
        }
        compose.runOnIdle {
            assertTrue(host.masterCalls.isEmpty())
            assertTrue(host.ruleCalls.isEmpty())
        }
    }

    @Test
    fun labelAreaIsPartOfTheSwitchTouchTarget() {
        val host = mount()
        val rule = NotificationRule.PRICE_CHANGE
        row(ruleTag(rule)).performTouchInput {
            // Left of the trailing Switch: tapping the labelled row must also toggle.
            click(center.copy(x = width * 0.25f))
        }
        compose.runOnIdle {
            assertEquals(listOf(rule to true), host.ruleCalls)
            assertTrue(host.masterCalls.isEmpty())
        }
    }

    @Test
    fun narrowHostAndLargeTextKeepEveryLabelAndRowReachable() {
        mount(fontScale = 2f)
        ruleLabels.forEach { (rule, label) ->
            row(ruleTag(rule))
                .assertIsDisplayed()
                .assertTextEquals(str(label))
                .assertHeightIsAtLeast(48.dp)
                .assertIsEnabled()
        }
    }
}
