package dev.modelatlas.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PK-12/S5 device vectors for the landed Provider Detail G2 capability gate.
 *
 * This mounts the production screen directly.  The fixture is UI-only and does not
 * stand in for the VM/repository capability probe covered by the companion data test.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDetailNewApiCapabilityGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun baseState() = ProviderDetailState(
        header = ProviderDetailHeader(
            providerId = "pk12-ui-provider",
            name = "PK-12 fixture provider",
            baseUrl = "https://fixture.invalid/v1",
        ),
    )

    private fun mount(
        supported: Boolean,
        syncEnabled: Boolean = true,
        saving: Boolean = false,
        syncing: Boolean = false,
        onSync: () -> Unit = {},
    ) {
        compose.setContent {
            ProviderDetailScreen(
                state = baseState(),
                onBack = {},
                onTab = {},
                onSync = onSync,
                onTestNow = null,
                onAddModel = null,
                onOpenCheckin = {},
                onCompleteCheckin = {},
                onCancelAction = {},
                saving = saving,
                syncEnabled = syncEnabled,
                newApiSyncSupported = supported,
                // Screen-level param (not a state field): syncing drives the
                // header-syncing fallback when the op state is absent.
                newApiOpState = if (syncing) OpState(syncing = true) else null,
            )
        }
        compose.waitForIdle()
    }

    private fun syncButton() = compose.onNodeWithTag("provider_detail_newapi_sync")

    private fun assertBackPresent() {
        compose.onNodeWithText(context.getString(R.string.provider_detail_back), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun tv1_unsupportedProvider_hidesNewApiSyncCta() {
        mount(supported = false)

        syncButton().assertDoesNotExist()
        assertBackPresent()
    }

    @Test
    fun tv2_supportedAndScopeEnabled_showsEnabledCtaAndClicksOnce() {
        var calls = 0
        mount(supported = true, onSync = { calls++ })

        syncButton().assertIsDisplayed().assertIsEnabled()
        syncButton().performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        assertBackPresent()
    }

    @Test
    fun tv3_supportedButScopeDisabled_keepsCtaVisibleAndDisabled() {
        mount(supported = true, syncEnabled = false)

        syncButton().assertIsDisplayed().assertIsNotEnabled()
        assertBackPresent()
    }

    @Test
    fun tv4_savingDisablesSupportedCtaAndSyncingPreservesSemantics() {
        // Single setContent per test (Compose rule contract): one mutable mount,
        // flip the driving state between the saving and syncing phases.
        val saving = androidx.compose.runtime.mutableStateOf(false)
        var opSyncing = false
        compose.setContent {
            ProviderDetailScreen(
                state = baseState(),
                onBack = {},
                onTab = {},
                onSync = {},
                onTestNow = null,
                onAddModel = null,
                onOpenCheckin = {},
                onCompleteCheckin = {},
                onCancelAction = {},
                saving = saving.value,
                syncEnabled = true,
                newApiSyncSupported = true,
                newApiOpState = if (opSyncing) OpState(syncing = true) else null,
            )
        }
        saving.value = true // saving phase
        compose.waitForIdle()
        syncButton().assertIsDisplayed().assertIsNotEnabled()
        assertBackPresent()

        opSyncing = true // syncing phase (op state present, not saving)
        saving.value = false
        compose.waitForIdle()
        syncButton().assertIsDisplayed().assertIsNotEnabled()
        syncButton().assertHasClickAction()
        assertBackPresent()
    }

    @Test
    fun tv5_backButtonRemainsWithVisibleCtaInAllEnabledRows() {
        // Single setContent per test: scope-enable/Disable is driven by a state flip.
        val syncEnabled = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            ProviderDetailScreen(
                state = baseState(),
                onBack = {},
                onTab = {},
                onSync = {},
                onTestNow = null,
                onAddModel = null,
                onOpenCheckin = {},
                onCompleteCheckin = {},
                onCancelAction = {},
                saving = false,
                syncEnabled = syncEnabled.value,
                newApiSyncSupported = true,
            )
        }
        compose.waitForIdle()
        syncButton().assertIsDisplayed()
        assertBackPresent()

        syncEnabled.value = false
        compose.waitForIdle()
        syncButton().assertIsDisplayed()
        assertBackPresent()
    }
}
