package dev.modelatlas.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.app.data.CompareKeySelection
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.models.CompareLiveRowResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PK-29 CompareLiveTable scoped-key device vectors (createComposeRule).
 *
 * TRACEABILITY: PK-29 (LazyColumn duplicate-key crash when one offeringId renders
 * under two scoped comparisonIds). Production rows carry distinct non-blank
 * comparisonId by runner invariant; the table keys on it (CompareLiveUi.kt:302).
 * Scoped ids below come from the production CompareLiveWiring.scopedComparisonId.
 */
@RunWith(AndroidJUnit4::class)
class CompareLiveTableKeyTest {

    @get:Rule
    val compose = createComposeRule()

    private val selC1 = CompareKeySelection(providerId = "p1", accountId = "a1", credentialId = "c1")
    private val selC2 = CompareKeySelection(providerId = "p1", accountId = "a1", credentialId = "c2")

    private fun tableFixtureRow(
        offeringId: String,
        comparisonId: String,
        modelId: String,
        success: Boolean,
    ) = CompareLiveRowResult(
        offeringId = offeringId,
        comparisonId = comparisonId,
        providerId = "p1",
        endpointId = "e1",
        providerModelId = modelId,
        canonicalId = "canon-pk29",
        success = success,
        outcome = if (success) Outcome.SUCCESS else Outcome.RATE_LIMIT,
    )

    private fun isPresent(label: String): Boolean =
        compose.onAllNodesWithText(label, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    /** Bounded scroll: the rows live inside a bounded LazyColumn (320dp max).
     * performScrollToNode composes + brings the node into the LAZY viewport
     * (swipes on the wrong scrollable never reach inner overflow). Falls back
     * to a bounded swipe loop only if no lazy list is found. */
    private fun scrollUntilVisible(label: String, maxSwipes: Int = 8) {
        if (isPresent(label) && isDisplayed(label)) return
        val lazies = compose.onAllNodes(hasScrollToIndexAction(), useUnmergedTree = true)
        if (lazies.fetchSemanticsNodes().isNotEmpty()) {
            // LazyColumn scroll action composes the target node.
            runCatching { lazies[0].performScrollToNode(hasText(label)) }
            if (isDisplayed(label)) return
        }
        repeat(maxSwipes) {
            if (isPresent(label) && isDisplayed(label)) return
            val scrollables = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (scrollables.isEmpty()) return
            scrollables.indices.forEach { _ ->
                if (!(isPresent(label) && isDisplayed(label))) {
                    compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)[0]
                        .performTouchInput { swipeUp() }
                }
            }
        }
    }

    private fun scrollUntilVisibleUp(label: String, maxSwipes: Int = 8) {
        if (isPresent(label) && isDisplayed(label)) return
        val lazies = compose.onAllNodes(hasScrollToIndexAction(), useUnmergedTree = true)
        if (lazies.fetchSemanticsNodes().isNotEmpty()) {
            runCatching { lazies[0].performScrollToNode(hasText(label)) }
            if (isDisplayed(label)) return
        }
        repeat(maxSwipes) {
            if (isPresent(label) && isDisplayed(label)) return
            val scrollables = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (scrollables.isEmpty()) return
            compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)[0]
                .performTouchInput { swipeDown() }
        }
    }

    private fun isDisplayed(label: String): Boolean =
        compose.onAllNodesWithText(label, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty() &&
            runCatching {
                compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed(); true
            }.getOrDefault(false)

    // T6 crash vector: same offeringId under two scoped keys renders without
    // IllegalArgumentException; both cards present with their own model/outcome text.
    @Test
    fun duplicateOfferingDistinctScopedKeysRenderBothCardsWithoutCrash() {
        val scopedA = CompareLiveWiring.scopedComparisonId("o1", selC1)
        val scopedB = CompareLiveWiring.scopedComparisonId("o1", selC2)
        compose.setContent {
            CompareLiveTable(
                canonicalId = "canon-pk29",
                rows = listOf(
                    tableFixtureRow("o1", scopedA, "model-o1-c1", success = true),
                    tableFixtureRow("o1", scopedB, "model-o1-c2", success = false),
                ),
                onRunClick = {},
            )
        }
        compose.onNodeWithText("model-o1-c1").assertIsDisplayed()
        compose.onNodeWithText("成功").assertIsDisplayed()
        scrollUntilVisible("model-o1-c2")
        compose.onNodeWithText("model-o1-c2").assertIsDisplayed()
        compose.onNodeWithText("失敗（RATE_LIMIT）").assertIsDisplayed()
    }

    // T7 legacy regression guard: single defaulted row keeps the exact summary text.
    @Test
    fun legacySingleRowSummaryTextUnchanged() {
        compose.setContent {
            CompareLiveTable(
                canonicalId = "canon-pk29",
                rows = listOf(
                    tableFixtureRow("o1", "o1", "model-o1", success = true),
                ),
                onRunClick = {},
            )
        }
        compose.onNodeWithText("model-o1").assertIsDisplayed()
        // Exact production string (strings.xml compare_summary_format).
        compose.onNodeWithText("1 成功 / 0 失敗（共 1 列）").assertIsDisplayed()
    }

    // T8 mixed batch: o1/key-c1 + o1/key-c2 + o2/key-c2 -> 3 cards, no crash,
    // input order kept.
    @Test
    fun mixedBatchRendersThreeCardsInInputOrderWithoutCrash() {
        val rows = listOf(
            tableFixtureRow(
                "o1",
                CompareLiveWiring.scopedComparisonId("o1", selC1),
                "model-o1-c1",
                success = true,
            ),
            tableFixtureRow(
                "o1",
                CompareLiveWiring.scopedComparisonId("o1", selC2),
                "model-o1-c2",
                success = false,
            ),
            tableFixtureRow(
                "o2",
                CompareLiveWiring.scopedComparisonId("o2", selC2),
                "model-o2-c2",
                success = true,
            ),
        )
        compose.setContent {
            CompareLiveTable(
                canonicalId = "canon-pk29",
                rows = rows,
                onRunClick = {},
            )
        }
        compose.onNodeWithText("model-o1-c1").assertIsDisplayed()
        // Rendering proof: every card can be brought into the viewport (below-fold
        // LazyColumn nodes exist pre-composition; isPresent alone cannot
        // distinguish displayed). Order is pinned at the JVM level (T5 runner
        // order vector); here we pin presence + no crash + scrollability.
        scrollUntilVisible("model-o1-c2")
        compose.onNodeWithText("model-o1-c2").assertIsDisplayed()
        scrollUntilVisible("model-o2-c2")
        compose.onNodeWithText("model-o2-c2").assertIsDisplayed()
        scrollUntilVisibleUp("model-o1-c1")
        compose.onNodeWithText("model-o1-c1").assertIsDisplayed()
    }
}
