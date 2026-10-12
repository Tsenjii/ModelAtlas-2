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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.app.data.CompareKeyChoice
import dev.modelatlas.app.data.CompareKeySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MODEL-008 / PRODUCT_SPEC §6 / §8: real main-owned CompareKeyPicker composable.
 * Rows are selected by visible label text + Role.Checkbox semantics (not by encoding
 * the collision-safe compare_key_ tag), so label changes by main cannot silently
 * break collision coverage assertions.
 */
@RunWith(AndroidJUnit4::class)
class CompareKeyPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private val checkboxRole = SemanticsMatcher("Role.Checkbox") {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox
    }

    private fun exactText(label: String) = SemanticsMatcher("exact text '$label'") {
        it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    }

    private fun row(label: String) = compose.onNode(
        isToggleable() and hasAnyDescendant(exactText(label)),
        useUnmergedTree = true,
    )

    private val alpha = CompareKeySelection("provider-alpha", "acc-alpha-1", "cred-alpha-1", null)
    private val beta = CompareKeySelection("provider-beta", "acc-beta-1", "cred-beta-1", null)
    private val betaSecond = CompareKeySelection("provider-beta", "acc-beta-2", "cred-beta-2", null)
    private val betaSecondGroup = CompareKeySelection("provider-beta", "acc-beta-2", "cred-beta-2", "gold")

    private val choices = listOf(
        CompareKeyChoice(alpha, "Alpha Cloud", "Key ····a1"),
        CompareKeyChoice(beta, "Beta AI", "Key ····b1"),
        CompareKeyChoice(betaSecond, "Beta AI", "Account 2 · Key ····b2"),
        CompareKeyChoice(betaSecondGroup, "Beta AI", "Account 2 · Key ····b2 · gold"),
    )

    private data class Host(
        val selections: MutableState<Set<CompareKeySelection>>,
        val calls: MutableList<Pair<CompareKeySelection, Boolean>>,
    )

    private fun mount(
        initial: Set<CompareKeySelection> = emptySet(),
        enabled: Boolean = true,
        width: Int = 320,
        fontScale: Float = 1f,
        extraChoices: List<CompareKeyChoice> = emptyList(),
    ): Host {
        val host = Host(mutableStateOf(initial), mutableListOf())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Surface(modifier = Modifier.width(width.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            CompareKeyPicker(
                                choices = choices + extraChoices,
                                selections = host.selections.value,
                                onSelectionChange = { selection, selected ->
                                    host.calls += selection to selected
                                    // Controlled: only the parent mutates state.
                                    if (selected) {
                                        host.selections.value = host.selections.value + selection
                                    } else {
                                        host.selections.value = host.selections.value - selection
                                    }
                                },
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
        }
        return host
    }

    @Test
    fun crossProviderAndSameProviderDistinctKeysRenderDistinctRowsWithExactCallbackIdentity() {
        val host = mount()
        val alphaRow = row("Key ····a1")
        val betaRow = row("Key ····b1")
        val betaSecondRow = row("Account 2 · Key ····b2")
        val betaGroupRow = row("Account 2 · Key ····b2 · gold")

        alphaRow.performScrollTo().assertIsDisplayed().assertIsEnabled().assertIsOff().assert(checkboxRole)
        betaRow.performScrollTo().assertIsDisplayed().assertIsOff().assert(checkboxRole)
        betaSecondRow.performScrollTo().assertIsDisplayed().assertIsOff().assert(checkboxRole)
        betaGroupRow.performScrollTo().assertIsDisplayed().assertIsOff().assert(checkboxRole)

        alphaRow.performClick()
        betaRow.performClick()
        betaSecondRow.performClick()
        betaGroupRow.performClick()

        assertEquals(
            listOf(
                alpha to true,
                beta to true,
                betaSecond to true,
                betaSecondGroup to true,
            ),
            host.calls,
        )
        assertNotEquals(beta, betaSecond)
        assertNotEquals(betaSecond, betaSecondGroup)
        assertNotEquals(alpha, beta)
    }

    @Test
    fun uncheckedRowsNeverAutoSelectForeignKeysAndSelectedStateIsParentControlled() {
        val host = mount()
        // Nothing pre-selected: no foreign or first-key auto-selection.
        row("Key ····a1").performScrollTo().assertIsOff()
        row("Key ····b1").performScrollTo().assertIsOff()
        row("Account 2 · Key ····b2").performScrollTo().assertIsOff()

        // Parent-controlled: checking beta via callback reflects only after parent updates state.
        row("Account 2 · Key ····b2").performClick()
        row("Account 2 · Key ····b2").assertIsOn()
        // Same provider's other account/key and the other provider stay unchecked.
        row("Key ····b1").assertIsOff()
        row("Key ····a1").performScrollTo().assertIsOff()

        // Uncheck: callback carries selected=false with the same exact selection identity.
        row("Account 2 · Key ····b2").performClick()
        row("Account 2 · Key ····b2").assertIsOff()
        assertEquals(
            listOf(betaSecond to true, betaSecond to false),
            host.calls,
        )
    }

    @Test
    fun disabledPickerKeepsRowsVisibleButNeverInvokesCallback() {
        val host = mount(enabled = false)
        row("Key ····a1").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        row("Key ····b1").performScrollTo().assertIsNotEnabled()

        row("Key ····a1").performClick()
        row("Key ····b1").performClick()

        assertTrue(host.calls.isEmpty())
        assertTrue(host.selections.value.isEmpty())
        row("Key ····a1").assertIsOff()
    }

    @Test
    fun allSixCapSelectionsRemainUncheckableWhileStateControlled() {
        val six = (1..3).flatMap { p ->
            (1..2).map { k ->
                CompareKeySelection("provider-p$p", "acc-p${p}-$k", "cred-p${p}-$k", null)
            }
        }
        val sixChoices = six.mapIndexed { i, s ->
            CompareKeyChoice(s, "Provider P${s.providerId.takeLast(1)}", "Key ····row$i unique")
        }
        val host = mount(initial = six.toSet(), extraChoices = sixChoices)
        sixChoices.forEach { choice ->
            row(choice.credentialLabel).performScrollTo().assertIsDisplayed().assertIsOn()
        }
        // At the six cap, selected rows must still be uncheckable (no disabled-on-selected).
        val target = sixChoices[2]
        row(target.credentialLabel).performClick()
        assertEquals(listOf(target.selection to false), host.calls)
        row(target.credentialLabel).assertIsOff()
        // Others remain on.
        row(sixChoices[0].credentialLabel).performScrollTo().assertIsOn()
    }

    @Test
    fun rowsAreCheckboxTargetsAtLeast48dpAndReachableInNarrowTwoTimesFontViewport() {
        mount(width = 280, fontScale = 2f)
        val labels = choices.map { it.credentialLabel }
        labels.forEach { label ->
            row(label).performScrollTo().assertIsDisplayed().assert(checkboxRole)
                .assertHeightIsAtLeast(48.dp)
        }
        // Every row is a single distinct checkbox target: 4 choices, no duplicates.
        compose.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(labels.size)
    }
}
