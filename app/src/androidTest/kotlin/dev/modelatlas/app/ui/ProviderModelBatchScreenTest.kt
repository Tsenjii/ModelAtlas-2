package dev.modelatlas.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.ProviderModelBatchState
import dev.modelatlas.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderModelBatchScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitSelectionUsesCallbackAndBatchOwnershipDisablesRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calls = mutableListOf<List<String>>()
        val state = mutableStateOf(ProviderDetailState(
            header = ProviderDetailHeader("p", "Batch fixture", "https://fixture.invalid/v1"),
            models = listOf(ProviderDetailModel("first", "UNKNOWN"), ProviderDetailModel("second", "UNKNOWN")),
            selectedTab = ProviderDetailTab.MODELS,
            actionScope = ProviderDetailActionScope("selected-key", "Selected connection", group = "free"),
        ))
        compose.setContent {
            ProviderDetailScreen(
                state = state.value, onBack = {}, onTab = {}, onSync = {},
                onTestNow = {}, onAddModel = {}, onOpenCheckin = {}, onCompleteCheckin = null,
                onCancelAction = {}, onTestBatch = { calls += it },
            )
        }
        fun revealStart() { compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasTestTag("provider_model_batch_start")) }
        revealStart()
        compose.onNodeWithTag("provider_model_batch_start").assertIsNotEnabled()
        val select = context.getString(R.string.model_batch_select_all)
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(select))
        compose.onNodeWithText(select).performClick()
        revealStart()
        compose.onNodeWithTag("provider_model_batch_start").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(listOf("first", "second")), calls)
            state.value = state.value.copy(modelBatch = ProviderModelBatchState(
                providerId = "p", connectionId = "selected-key", group = "free",
                models = listOf("first", "second"), running = true, status = "RUNNING",
            ))
        }
        revealStart()
        compose.onNodeWithTag("provider_model_batch_start").assertIsNotEnabled()
    }
}
