package dev.modelatlas.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** ③-1 My Models toggle：真主 AI composable 渲染＋回調（D-008／Contract §23.1）。 */
@RunWith(AndroidJUnit4::class)
class MyModelsToggleTest {
    @get:Rule
    val compose = createComposeRule()

    private val buttonRole = SemanticsMatcher("Role.Button") {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Button
    }

    private fun mount(
        initial: Boolean = false,
        enabled: Boolean = true,
    ): Pair<MutableState<Boolean>, MutableList<Unit>> {
        val state = mutableStateOf(initial)
        val calls = mutableListOf<Unit>()
        compose.setContent {
            MaterialTheme {
                Surface {
                    MyModelsToggle(
                        isMyModel = state.value,
                        onToggle = { calls += Unit },
                        modifier = Modifier,
                        enabled = enabled,
                    )
                }
            }
        }
        return state to calls
    }

    @Test
    fun notMemberShowsJoinAndReportsToggleWithoutChangingItself() {
        val (state, calls) = mount(initial = false)
        compose.onNodeWithText("加入 My Models")
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction().assert(buttonRole)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(Unit), calls)
            // Controlled: display only follows the parent state.
            assertTrue(!state.value)
        }
        compose.onNodeWithText("移出 My Models").assertDoesNotExist()
    }

    @Test
    fun memberShowsRemoveAndParentStateDrivesLabel() {
        val (state, calls) = mount(initial = true)
        compose.onNodeWithText("移出 My Models")
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Unit), calls) }
        compose.runOnIdle { state.value = false }
        compose.onNodeWithText("加入 My Models").assertIsDisplayed()
    }

    @Test
    fun disabledToggleIsVisibleButNeverFires() {
        val (_, calls) = mount(initial = false, enabled = false)
        compose.onNodeWithText("加入 My Models")
            .assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    // ── PK-26 toggle feedback（landed additive params inFlight/error Booleans）──

    private fun mountFeedback(
        initial: Boolean = false,
        inFlight: Boolean = false,
        error: Boolean = false,
    ): Pair<MutableState<Boolean>, MutableList<Unit>> {
        val state = mutableStateOf(initial)
        val calls = mutableListOf<Unit>()
        compose.setContent {
            MaterialTheme {
                Surface {
                    MyModelsToggle(
                        isMyModel = state.value,
                        onToggle = { calls += Unit },
                        inFlight = inFlight,
                        error = error,
                    )
                }
            }
        }
        return state to calls
    }

    // Vector 8: in-flight → button disabled, label stays the truth-value (no
    // optimistic "加入中" fake state), no hint row (no progress indicator landed).
    @Test
    fun inFlightDisablesButtonAndKeepsTruthLabelWithoutHint() {
        mountFeedback(initial = false, inFlight = true)
        compose.onNodeWithText("加入 My Models")
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("操作失敗，請再試一次").assertDoesNotExist()
    }

    // Vector 8 (member variant): same truth-value contract while removing.
    @Test
    fun inFlightMemberKeepsRemoveLabelDisabled() {
        mountFeedback(initial = true, inFlight = true)
        compose.onNodeWithText("移出 My Models")
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("操作失敗，請再試一次").assertDoesNotExist()
    }

    // Vector 9: error → hint row from strings.xml; landed has no onRetry param —
    // the retry affordance is re-tapping the toggle itself, so the click still fires.
    @Test
    fun errorShowsHintRowAndRetapStillFiresToggle() {
        val (_, calls) = mountFeedback(initial = false, error = true)
        compose.onNodeWithText("操作失敗，請再試一次").assertIsDisplayed()
        compose.onNodeWithText("加入 My Models")
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Unit), calls) }
    }

    // Vector 10: error=false + inFlight=false defaults keep the legacy 3-test
    // behavior (button enabled, no hint row, controlled label).
    @Test
    fun explicitDefaultsMatchLegacyBehavior() {
        val (state, calls) = mountFeedback(initial = false, inFlight = false, error = false)
        compose.onNodeWithText("加入 My Models")
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        compose.onNodeWithText("操作失敗，請再試一次").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(!state.value)
            assertTrue(calls.isEmpty())
        }
    }

    // Vector 11: controlled — clicking the toggle never self-mutates the label or
    // dismisses the hint row while the parent state is unchanged.
    @Test
    fun clickNeverSelfMutatesLabelOrDismissesHint() {
        val (state, calls) = mountFeedback(initial = false, error = true)
        compose.onNodeWithText("加入 My Models").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Unit), calls)
            assertTrue(!state.value)
        }
        compose.onNodeWithText("加入 My Models").assertIsDisplayed()
        compose.onNodeWithText("操作失敗，請再試一次").assertIsDisplayed()
    }
}
