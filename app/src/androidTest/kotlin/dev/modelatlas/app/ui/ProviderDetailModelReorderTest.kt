package dev.modelatlas.app.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
// onAllNodes is a member of SemanticsNodeInteractionsProvider (compose rule) —
// NOT a top-level import (same trap as HANDOFF §3 lazy-list lessons).
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.ProviderModelActionState
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PriceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PK-28 / S27 — Provider Detail reorder-while-testing binding (device half).
 *
 * TRACEABILITY: PK-28 (production contract already landed at HEAD:
 * `ProviderDetailScreen.kt` `items(models, key = { it.modelId },
 * contentType = { PROVIDER_DETAIL_MODEL_CONTENT_TYPE })`).
 *
 * - TV-5 (this file): mount production `ProviderDetailScreen` on the MODELS tab with
 *   models [a PAID, b PAID] and a TEST RUNNING action for modelId=b. The indeterminate
 *   spinner must exist under b's card only (and b's test button shows
 *   `provider_detail_testing` while a's still shows `provider_detail_test_now`); after
 *   flipping to [b FREE, a PAID] (screen re-sorts via `sortDetailModels` to [b, a]) the
 *   same assertions must hold.
 * - TV-6 scroll-anchor is INTENTIONALLY OMITTED: below-fold LazyColumn device
 *   assertions have hung before (HANDOFF §6.5); ordering proof stays at JVM state
 *   level (`ProviderDetailModelKeysTest.sortKeepsKeysStableAndFreeNowFlipOnlyReorders`).
 * - TV-7 regressions that must stay green (NOT modified here):
 *   `ProviderDetailStateTest.freeNowModelsSortFirstStably`,
 *   `ProviderDetailFlowTest.modelsTabSortsFreeNowFirstWithStableSink`,
 *   `ProviderModelActionScreenTest`.
 *
 * Matcher note (verified against production at HEAD): the spinner node is matched with
 * `hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)` (material3
 * `LinearProgressIndicator` indeterminate semantics) scoped by
 * `hasParent(hasAnyDescendant(hasText(modelId)))`. The parent scope is deliberate: a
 * plain `hasAnyAncestor(hasAnyDescendant(hasText(...)))` would also match via the
 * LazyColumn/root ancestor (which contains every model text as a descendant), so it
 * cannot exclude the status card's own busy spinner
 * (`DetailActionStatusCard` renders its own `LinearProgressIndicator` while RUNNING,
 * with no model text inside). The model-row spinner is a direct child of the card
 * `Column` that contains the model-id text; the status card's spinner parent contains
 * no model text. Header syncing is off (`header.syncing=false`, no op states), so no
 * other spinners exist.
 *
 * Fixtures live only in this test sourceSet, never product data. Mount params mirror
 * `ProviderModelActionScreenTest` minimal wiring.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDetailModelReorderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    private companion object {
        const val MODEL_A = "pk28-model-a"
        const val MODEL_B = "pk28-model-b"
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onAllNodes(hasScrollToIndexAction())
            .onFirst()
            .performScrollToNode(matcher)
        compose.waitForIdle()
    }

    /** Indeterminate spinner whose immediate card Column contains [modelId]. */
    private fun spinnerInModelCard(modelId: String): SemanticsMatcher =
        hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate) and
            hasParent(hasAnyDescendant(hasText(modelId)))

    private fun runningTestAction(modelId: String) = ProviderModelActionState(
        providerId = "p1",
        modelId = modelId,
        connectionId = "c1",
        kind = "TEST",
        status = "RUNNING",
    )

    private fun baseState(
        models: List<ProviderDetailModel>,
        action: ProviderModelActionState,
    ) = ProviderDetailState(
        header = ProviderDetailHeader(
            providerId = "p1",
            name = "fixture provider",
            baseUrl = "https://fixture.invalid/v1",
        ),
        models = models,
        selectedTab = ProviderDetailTab.MODELS,
        action = action,
        actionScope = ProviderDetailActionScope(
            connectionId = "c1",
            connectionName = "fixture connection",
            credentialLabel = "fixture key",
        ),
    )

    private fun mountScreen(initial: ProviderDetailState): MutableState<ProviderDetailState> {
        val state = mutableStateOf(initial)
        compose.setContent {
            ProviderDetailScreen(
                state = state.value,
                onBack = {},
                onTab = {},
                onSync = {},
                onTestNow = {},
                onAddModel = {},
                onOpenCheckin = {},
                onCompleteCheckin = {},
                onCancelAction = {},
            )
        }
        return state
    }

    /** Spinner bound to [withSpinner]'s card only; button labels corroborate the binding. */
    private fun assertSpinnerBoundTo(withSpinner: String, withoutSpinner: String) {
        val testingLabel = str(R.string.provider_detail_testing)
        val testNowLabel = str(R.string.provider_detail_test_now)
        // Bring both cards into composition before asserting (content is a LazyColumn).
        scrollTo(hasText(withSpinner))
        scrollTo(hasText(withoutSpinner))
        compose.onAllNodes(spinnerInModelCard(withSpinner)).assertCountEquals(1)
        compose.onAllNodes(spinnerInModelCard(withoutSpinner)).assertCountEquals(0)
        scrollTo(hasText(testingLabel))
        compose.onAllNodes(hasText(testingLabel) and hasClickAction()).assertCountEquals(1)
        compose.onAllNodes(hasText(testNowLabel) and hasClickAction()).assertCountEquals(1)
    }

    // TV-5: reorder-while-testing keeps the spinner bound to the testing row.
    @Test
    fun reorderWhileTestingKeepsSpinnerBoundToTestingRow() {
        val state = mountScreen(
            baseState(
                models = listOf(
                    ProviderDetailModel(MODEL_A, PriceState.PAID.name),
                    ProviderDetailModel(MODEL_B, PriceState.PAID.name),
                ),
                action = runningTestAction(MODEL_B),
            ),
        )
        compose.waitForIdle()
        assertSpinnerBoundTo(withSpinner = MODEL_B, withoutSpinner = MODEL_A)

        // FREE_NOW flip: screen re-sorts via sortDetailModels to [b, a]; binding must hold.
        state.value = state.value.copy(
            models = listOf(
                ProviderDetailModel(MODEL_A, PriceState.PAID.name),
                ProviderDetailModel(MODEL_B, PriceState.FREE_NOW.name),
            ),
        )
        compose.waitForIdle()
        assertSpinnerBoundTo(withSpinner = MODEL_B, withoutSpinner = MODEL_A)
    }
}
