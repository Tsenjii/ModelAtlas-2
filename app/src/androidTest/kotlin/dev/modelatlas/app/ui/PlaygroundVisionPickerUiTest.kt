package dev.modelatlas.app.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PLAY-004: user-visible picker eligibility. No file access or remote requests in this test. */
@RunWith(AndroidJUnit4::class)
class PlaygroundVisionPickerUiTest {
    @get:Rule val compose = createComposeRule()

    private val connection = ConnectionItem(
        id = "fixture-connection", providerId = "fixture-provider",
        name = "Fixture", baseUrl = "https://example.invalid/v1",
        modelId = "model", credentialLabel = "test-key",
        endpointId = "fixture-endpoint", operation = "CHAT_COMPLETIONS",
    )

    private fun show(
        canAttach: Boolean,
        group: State<String?>? = null,
        resetToken: State<Long>? = null,
        busy: Boolean = false,
        onNewConversation: () -> Unit = {},
    ) {
        compose.setContent {
            AtlasTheme {
                PlaygroundTab(
                    state = AtlasUiState(
                        connections = listOf(connection),
                        selectedId = connection.id,
                        connectionsLoading = false,
                        conversationResetToken = resetToken?.value ?: 0L,
                        busy = busy,
                    ),
                    onSelect = {},
                    onSend = {},
                    onSendVision = { _, _ -> },
                    canAttachVision = canAttach,
                    selectedGroup = group?.value,
                    onCancel = {},
                    onNewConversation = onNewConversation,
                    onGotoProviders = {},
                    showConnections = false,
                    onShowConnections = {},
                )
            }
        }
    }

    private val imageAction get() =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.playground_attach_image)

    @Test fun unsupportedOrUnknownVisionCannotStartPicker() {
        show(canAttach = false)
        compose.onNodeWithContentDescription(imageAction).assertIsNotEnabled()
    }

    @Test fun supportedVisionHasAccessiblePickerAction() {
        show(canAttach = true)
        compose.onNodeWithContentDescription(imageAction).assertIsEnabled()
    }

    @Test fun switchingGroupCannotReuseUnsentDraft() {
        val group = mutableStateOf<String?>("group-a")
        show(canAttach = true, group = group)
        compose.onNode(hasSetTextAction()).performTextInput("group-a private draft")
        val send = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_send)
        compose.onNodeWithContentDescription(send).assertIsEnabled()
        compose.runOnIdle { group.value = "group-b" }
        // No text from group A remains available for sending in group B.
        compose.onNodeWithContentDescription(send).assertIsNotEnabled()
    }
    @Test fun newConversationResetsDraftThroughUiCallback() {
        val generation = mutableStateOf(0L)
        show(canAttach = true, resetToken = generation, onNewConversation = {
            generation.value += 1L
        })
        compose.onNode(hasSetTextAction()).performTextInput("draft from previous conversation")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val send = context.getString(R.string.action_send)
        val newConversation = context.getString(R.string.playground_new_conversation)
        compose.onNodeWithContentDescription(send).assertIsEnabled()
        compose.onNodeWithContentDescription(newConversation).assertIsEnabled().performClick()
        compose.onNodeWithContentDescription(send).assertIsNotEnabled()
    }

    @Test fun newConversationIsDisabledWhileStreaming() {
        show(canAttach = true, busy = true)
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.playground_new_conversation)
        compose.onNodeWithContentDescription(label).assertIsNotEnabled()
    }
}
