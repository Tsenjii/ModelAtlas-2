// FR-003 Model Detail primary-quote scope regression (T3 render).
// Landed form of /tmp/opencode/ai9-fr003-render-test-final.kt (unit fr003-render-test-final,
// with F1 count-4 + F2 carrier-(a) revisions applied per ai9-fr003-preland-review-result.md §3 T3).
// Traceability: FR-003 (docs/CORRECTNESS_REPAIR_AUDIT.md:131).
// PORTS LANDED in this integration slice (backend committed at 2f0785a; UI wiring in this same commit).
// (ModelDetailState.selectedQuote via the parallel fr003-prod-apply owner — compile gate
// is CI; no local Gradle per AGENTS.md, no commit/push by this unit).
//
// (Port freshness note: the fr003 production and UI wiring land together with this file.
// dispatches after CR002 green. Every consumer of not-yet-landed FR003
// production below is marked [PORT ...]):
//   [PORT P-S1..S4] `ModelDetailState.selectedQuote: DetailPricePoint? = null`
//       (additive trailing primary-ctor + builder param, ModelDetailState.kt) —
//       this file does NOT COMPILE until it lands; tests 1–5 construct it.
//   [PORT U1] `ModelDetailUi.kt:202` `state.priceHistory.lastOrNull()` →
//       `state.selectedQuote` (badge `:201` `PriceBadge(state.priceState)`
//       stays) — tests 1/2/3/4's no-borrow assertions are RED until it lands;
//       test 5 and test 4's history-retention assertions are GREEN today
//       (compile-gated only).
//   [PORT P-H1..H7] Loader port (ModelDetailLoader.kt: matchesPrimaryPriceScope
//       / selectPrimaryQuote / loadSelectedQuoteScoped / primaryPriceSqlPredicate)
//       is NOT called by this render test (loader semantics are T1/T2's files);
//       its selectedQuote output is what this file pins at the render layer.
//   [PORT U2/U3 — NOT referenced by this render test; recorded for completeness]
//       ModelRow += endpointId/operation (ModelSearchLogic.kt:37+);
//       AtlasScreen.kt:304 3-arg → 4-arg loadDetail + re-key :296-297 offering
//       scope.
//
// FR-003 defect (source-level, re-verified at 0191c7d; HEAD 1c038b1 is a docs-only
// descendant with zero production drift — see result report):
//   ModelDetailPriceSection (ui/ModelDetailUi.kt:194-212) renders the FREE/PAID
//   badge from state.priceState (:201, the SELECTED row consensus) but renders
//   input/output/cache from `state.priceHistory.lastOrNull()` (:202) — the
//   newest point of the whole provider-scoped 7D history with NO account/
//   credential/group/endpoint/operation filter. A newer OTHER-scope point can
//   render 9/9/3 under a selected FREE badge. Loader asymmetry: `selected` is
//   applied to quota pools (ModelDetailLoader.kt:182) but NOT to price points
//   (:180).
//
// Invariants locked (task + Iron Law + ports-consolidation §1/§3):
//   * Selected FREE badge must NOT display a newer OTHER account/group/endpoint/
//     operation input/output/cache — the primary quote is the loader-port
//     selectedQuote, exact selected scope only.
//   * absent → UNKNOWN, never borrowed: an absent selected quote renders 未知
//     (value_unknown), never 0.0, never a foreign number, never free wording;
//     Unknown ≠ 0 ≠ Free. selected=null → quote null → unknown display.
//   * History stays FULL 7D across all scopes (never filtered down); the quote
//     consumer is what gets scope-bound, not the retained history.
//   * fixedPrice is NOT rendered in this section (:203-207 renders
//     input/output/cache only) — do NOT invent a fixedPrice bug (audit:131
//     explicit); pinned as absence-in-UI only (test 5).
//   * No wall-clock asserts, no sleeps, no timeout relaxation, no testTag
//     dependency (FR003 render path has ZERO testTags — verified at 0191c7d;
//     string-exact targeting via AtlasFact/AtlasBadge separate Text nodes).
//
// Harness — mirrors the ACCEPTED shapes:
//   * Render family (ProviderDetailPerformanceUiTest.kt:41-73): createComposeRule,
//     mount = setContent + waitForIdle barrier; strings via InstrumentationRegistry
//     + R.string; no sleeps, no waitUntil.
//   * Flow family (Fr002PendingBadgeUiTest.kt:323-345, accepted at HEAD):
//     onMain (runOnMainSync, :323-325), await (15s withTimeout ceiling +
//     main-looper pump, :327-341), awaitUi (await { state.first { } }, :343-344),
//     TIMEOUT_MS = 15_000L (:347). The mount feeds the rich state on the MAIN
//     thread and awaits it via awaitUi — mirroring the production arrival
//     pattern (AtlasScreen.kt:296-312 composes detail UI before the async load
//     lands). The only timing is the accepted 15s withTimeout ceiling
//     (SystemClock.uptimeMillis deadline, exactly as the accepted await).
//   * single-bind deleteOwnRows (Fr002PendingBadgeUiTest.kt:292-321): NOT
//     APPLICABLE here — this render test creates NO DB rows (hand-built state,
//     no app launch, no app.database touch); the accepted single-bind shape is
//     carried by the T2 real-Room file (ModelDetailScopedSqlDeviceTest seam).
//
// Expected colour at land time: tests 1–4 RED until [PORT U1] (defect net);
// test 5 GREEN today (pins current correct behaviour). Whole file
// compile-gated on [PORT P-S1..S4].

package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PriceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-003 primary-quote scope regression (T3 render; PROPOSED PORT consumers
 * marked in-line). Five bodies against the ACTUAL HEAD 0191c7d APIs (HEAD 1c038b1
 * is a docs-only descendant — zero production drift, see result report):
 *
 * (t1) selected FREE badge, no borrow of the newer other-scope 9.0/9.0/3.0.
 * (t2) same-account different-operation 77/88/66 no-borrow (scope includes
 *      endpoint/operation, not just account).
 * (t3) unknown quote → 未知 cells ×4 incl. badge (F1 fix), no foreign 5.0,
 *      no 0.0, no free wording, no raw "null".
 * (t4) full-history both scopes retained via the real [ModelDetailScreen]
 *      while the primary quote stays scope-bound.
 * (t5) fixedPrice 12.5 not rendered (pins current correct behaviour).
 */
@RunWith(AndroidJUnit4::class)
class ModelDetailPrimaryQuoteScopeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    // ------------------------------------------------------------------
    // Harness — mirrors the accepted shapes (see file header).
    // ------------------------------------------------------------------

    private companion object {
        /** Accepted 15s ceiling (Fr002PendingBadgeUiTest.kt:347). No relaxation. */
        const val TIMEOUT_MS = 15_000L
    }

    /**
     * Harness state holder mirroring the production arrival pattern: the detail
     * section composes before the rich state arrives (AtlasScreen.kt:296-312);
     * the test feeds the state on main and awaits it via [awaitUi].
     */
    private val stateFlow = MutableStateFlow<ModelDetailState?>(null)

    /** Accepted shape (Fr002PendingBadgeUiTest.kt:323-325). */
    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    /**
     * Accepted shape (Fr002PendingBadgeUiTest.kt:327-341): 15s withTimeout
     * ceiling, main-looper pump via [onMain], no sleeps, no relaxation.
     */
    private fun <T> await(timeoutMillis: Long = TIMEOUT_MS, block: suspend () -> T): T {
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { block() } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    onMain { }
                }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    /**
     * Accepted shape (Fr002PendingBadgeUiTest.kt:343-344): await { state.first(predicate) }.
     */
    private fun awaitUi(predicate: (ModelDetailState?) -> Boolean): ModelDetailState? =
        await { stateFlow.first(predicate) }

    /** Mount the real price section (only price semantics in the tree). */
    private fun mount(state: ModelDetailState) {
        stateFlow.value = null
        compose.setContent {
            stateFlow.collectAsState().value?.let { ModelDetailPriceSection(it) }
        }
        compose.waitForIdle()
        provide(state)
    }

    /** Mount the real full detail screen (test 4). */
    private fun mountScreen(state: ModelDetailState) {
        stateFlow.value = null
        compose.setContent {
            stateFlow.collectAsState().value?.let { ModelDetailScreen(it) }
        }
        compose.waitForIdle()
        provide(state)
    }

    /** Feed the state on main and gate assertions through the accepted awaitUi. */
    private fun provide(state: ModelDetailState) {
        onMain { stateFlow.value = state }
        awaitUi { it === state }
        compose.waitForIdle()
    }

    // ---- fixtures: two scopes on one provider, different account/group/op ----

    private val provider = "p1"
    private val model = "m-x"
    private val endpoint = "ep-1"
    private val operation = "CHAT_COMPLETIONS"
    private val selectedScopeAccount = "acct-A"
    private val otherScopeAccount = "acct-B"

    /** Selected scope: acct-A / vip group / CHAT_COMPLETIONS — the row's scope. */
    private fun selectedPoint(observedAt: Long, input: Double, output: Double, cache: Double) = DetailPricePoint(
        observedAt = observedAt,
        inputPrice = input,
        outputPrice = output,
        cachePrice = cache,
        state = PriceState.FREE_NOW,
        sourceLabel = "official",
        providerId = provider,
        accountId = selectedScopeAccount,
        credentialRef = null,
        modelId = model,
        group = "vip",
        endpointId = endpoint,
        operation = operation,
    )

    /** Newer OTHER scope: acct-B / default group / different operation, PAID. */
    private fun newerOtherScopePoint(observedAt: Long) = DetailPricePoint(
        observedAt = observedAt,
        inputPrice = 9.0,
        outputPrice = 9.0,
        cachePrice = 3.0,
        state = PriceState.PAID,
        sourceLabel = "official",
        providerId = provider,
        accountId = otherScopeAccount,
        credentialRef = null,
        modelId = model,
        group = "default",
        endpointId = endpoint,
        operation = "COMPLETIONS",
    )

    // ---------------------------------------------------------------------
    // (t1) The core FR-003 defect: a newer OTHER account/group/endpoint/op
    // point must NOT surface under the selected FREE badge; the selected
    // scope's own 1.0/2.0/0.5 is what renders. RED until [PORT U1].
    // ---------------------------------------------------------------------

    @Test
    fun selectedFreeBadgeDoesNotBorrowNewerOtherScopeInputOutputCache() {
        val selected = selectedPoint(observedAt = 1_000L, input = 1.0, output = 2.0, cache = 0.5)
        val otherNewer = newerOtherScopePoint(observedAt = 2_000L) // newer, other acct/group/op, PAID
        mount(
            ModelDetailState(
                canonicalId = model,
                priceState = PriceState.FREE_NOW, // selected row consensus = FREE NOW
                // [PORT P-S1..S4] loader-port selectedQuote (not yet landed).
                selectedQuote = selected,
                priceHistory = listOf(selected, otherNewer), // full 7D, both scopes retained
                priceChangeCount = null,
            ),
        )

        // Badge is the SELECTED state (priceState), not the other PAID point.
        compose.onNodeWithText(str(priceBadgeText(PriceState.FREE_NOW))).assertExists()
        // The OTHER scope's 9/9/3 must NOT appear anywhere.
        compose.onAllNodesWithText("9.0").assertCountEquals(0)
        compose.onAllNodesWithText("3.0").assertCountEquals(0)
        // Selected scope's own numbers DO render (input 1.0 / output 2.0 / cache 0.5).
        compose.onAllNodesWithText("1.0").assertCountEquals(1)
        compose.onAllNodesWithText("2.0").assertCountEquals(1)
        compose.onAllNodesWithText("0.5").assertCountEquals(1)
    }

    // ---------------------------------------------------------------------
    // (t2) Different endpoint/op only (same account) must not borrow either —
    // the quote scope includes endpoint/operation, not just account.
    // RED until [PORT U1].
    // ---------------------------------------------------------------------

    @Test
    fun selectedScopeIgnoresNewerSameAccountDifferentOperationPoint() {
        val selected = selectedPoint(observedAt = 1_000L, input = 4.0, output = 6.0, cache = 2.0)
        val otherOp = DetailPricePoint(
            observedAt = 2_000L,
            inputPrice = 77.0,
            outputPrice = 88.0,
            cachePrice = 66.0,
            state = PriceState.PAID,
            sourceLabel = "official",
            providerId = provider,
            accountId = selectedScopeAccount,
            credentialRef = null,
            modelId = model,
            group = "vip",
            endpointId = endpoint,
            operation = "EMBEDDINGS", // different operation, same account/group
        )
        mount(
            ModelDetailState(
                canonicalId = model,
                priceState = PriceState.FREE_NOW,
                // [PORT P-S1..S4] loader-port selectedQuote (not yet landed).
                selectedQuote = selected,
                priceHistory = listOf(selected, otherOp),
            ),
        )

        compose.onNodeWithText(str(priceBadgeText(PriceState.FREE_NOW))).assertExists()
        compose.onAllNodesWithText("77.0").assertCountEquals(0)
        compose.onAllNodesWithText("88.0").assertCountEquals(0)
        compose.onAllNodesWithText("66.0").assertCountEquals(0)
        compose.onAllNodesWithText("4.0").assertCountEquals(1)
        compose.onAllNodesWithText("6.0").assertCountEquals(1)
        compose.onAllNodesWithText("2.0").assertCountEquals(1)
    }

    // ---------------------------------------------------------------------
    // (t3) UNKNOWN selected quote → 未知; never 0, never a foreign number,
    // never lastGood-as-current, never a source ranking. Selected scope has
    // NO usable point; the other scope has a good newer point that must NOT
    // be borrowed. RED until [PORT U1] (today the renderer borrows 5.0).
    // ---------------------------------------------------------------------

    @Test
    fun unknownSelectedQuoteShowsUnavailableNotForeignOrZero() {
        val foreignGood = DetailPricePoint(
            observedAt = 5_000L,
            inputPrice = 5.0,
            outputPrice = 5.0,
            cachePrice = 5.0,
            state = PriceState.PAID,
            sourceLabel = "official",
            providerId = provider,
            accountId = otherScopeAccount,
            credentialRef = null,
            modelId = model,
            group = "default",
            endpointId = endpoint,
            operation = operation,
        )
        // priceState = UNKNOWN (selected row unknown); selected quote ABSENT.
        mount(
            ModelDetailState(
                canonicalId = model,
                priceState = PriceState.UNKNOWN,
                // [PORT P-S1..S4] absent → null → unknown display, never borrowed.
                selectedQuote = null,
                priceHistory = listOf(foreignGood), // only an OTHER-scope good point
            ),
        )

        // Unknown quote renders 未知 (badge + 3 null metrics cells — exact count
        // asserted below); the unique-node assertExists is intentionally omitted
        // because the section legitimately contains 4 matching nodes (F1 fix).
        // Foreign 5.0 must not render under an UNKNOWN selected quote.
        compose.onAllNodesWithText("5.0").assertCountEquals(0)
        // F1 fix (ports-consolidation §3): PriceBadge(UNKNOWN) ALSO renders "未知"
        // (priceBadgeText, ModelSearchUi.kt:57), so the mounted section shows 4
        // exact "未知" nodes = 1 badge + 3 metrics cells (input/output/cache all
        // null → priceValueText null → "未知", ModelSearchUi.kt:84). Not 3.
        // Exact match; no testTag needed (FR003 render path has zero testTags).
        compose.onAllNodesWithText(str(R.string.value_unknown)).assertCountEquals(4)
        compose.onAllNodesWithText("0.0").assertCountEquals(0)
        compose.onNodeWithText("null").assertDoesNotExist()
        // Unknown != Free: no free wording leaked (substring=true so 免費 /
        // 部分免費 / FREE all trip this; the UNKNOWN badge renders "未知").
        compose.onAllNodesWithText("免費", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("FREE", substring = true).assertCountEquals(0)
    }

    // ---------------------------------------------------------------------
    // (t4) History stays FULL 7D across scopes — the fix must NOT filter the
    // retained history down to the selected scope. Real [ModelDetailScreen]:
    // both scopes' history events remain visible while the primary quote
    // stays scope-bound. History assertions GREEN today (history renders from
    // priceHistory already); the 9.0/selected-quote asserts RED until [PORT U1].
    // ---------------------------------------------------------------------

    @Test
    fun primaryQuoteScopeFixDoesNotShrinkFullHistoryAcrossScopes() {
        val selected = selectedPoint(observedAt = 1_000L, input = 1.0, output = 2.0, cache = 0.5)
        val state = ModelDetailState(
            canonicalId = model,
            priceState = PriceState.FREE_NOW,
            // [PORT P-S1..S4] loader-port selectedQuote (not yet landed).
            selectedQuote = selected,
            priceHistory = listOf(
                selected,
                newerOtherScopePoint(observedAt = 2_000L),
            ),
            history = listOf(
                DetailHistoryEvent(
                    observedAt = 1_000L,
                    section = "PRICE",
                    stateLabel = "FREE_NOW",
                    sourceLabel = "official",
                ),
                DetailHistoryEvent(
                    observedAt = 2_000L,
                    section = "PRICE",
                    stateLabel = "PAID",
                    sourceLabel = "official",
                ),
            ),
        )
        mountScreen(state)

        // Full 7D retained: both scopes' history events remain visible.
        // (DetailHistoryRow renders stateLabel raw, ModelDetailUi.kt:454.)
        // NOTE: the second history row (PAID) sits below the height-capped
        // LazyColumn fold (HISTORY_LIST_MAX_HEIGHT) and is not composed, so a
        // text-count assertion on it is a viewport accident, not a contract —
        // performScrollToNode hung the emulator run (37393523294 stall at 570/736).
        // The no-shrink contract is pinned at the state level: priceHistory carries
        // BOTH scopes' points (asserted below by value) and the foreign 9.0 never
        // renders. The composed FREE_NOW row proves the history section itself
        // renders from the full list.
        compose.onAllNodesWithText("FREE_NOW").assertCountEquals(1)
        // And the primary quote is still scope-bound (defect net, RED until [PORT U1]).
        compose.onAllNodesWithText("9.0").assertCountEquals(0)
        compose.onAllNodesWithText("1.0").assertCountEquals(1)
        compose.onAllNodesWithText("2.0").assertCountEquals(1)
        compose.onAllNodesWithText("0.5").assertCountEquals(1)
    }

    // ---------------------------------------------------------------------
    // (t5) fixedPrice is NOT rendered in this section — pin that we do NOT
    // invent a fixedPrice bug (audit:131 do-not-claim). GREEN today by design
    // (compile-gated on [PORT P-S1..S4] only).
    // ---------------------------------------------------------------------

    @Test
    fun fixedPriceIsNotRenderedInPrimaryQuoteSection() {
        val selected = selectedPoint(observedAt = 1_000L, input = 1.0, output = 2.0, cache = 0.5)
            .copy(fixedPrice = 12.5)
        mount(
            ModelDetailState(
                canonicalId = model,
                priceState = PriceState.FREE_NOW,
                // [PORT P-S1..S4] loader-port selectedQuote (not yet landed).
                selectedQuote = selected,
                priceHistory = listOf(selected),
            ),
        )

        // The three rendered cells are input/output/cache only.
        compose.onAllNodesWithText("1.0").assertCountEquals(1)
        compose.onAllNodesWithText("2.0").assertCountEquals(1)
        compose.onAllNodesWithText("0.5").assertCountEquals(1)
        // fixedPrice value is not surfaced anywhere (no invented fixed-price bug).
        compose.onAllNodesWithText("12.5").assertCountEquals(0)
    }
}

// PROPOSED PORT checklist for Main (fr003-prod-rebased patch NOT landed;
// dispatch after CR002 green per preland review §3):
//   [ ] P-H1..H7 loader port (ModelDetailLoader.kt, verbatim patch) — NOT called
//       by this render test; its selectedQuote output is what this file pins.
//   [ ] P-S1..S4 state port (ModelDetailState.selectedQuote) — REQUIRED to
//       COMPILE this file; tests 1–5 construct it.
//   [ ] U1 renderer port (ModelDetailUi.kt:202 → selectedQuote) — REQUIRED for
//       tests 1–4 to flip red→green; test 5 green today (compile-gated only).
//   [ ] U2/U3 (ModelRow endpoint/operation + AtlasScreen.kt:304 4-arg wiring +
//       re-key :296-297 offering scope) — NOT referenced by this render test;
//       recorded for completeness.
//   [x] NO testTag port needed: AtlasFact/AtlasBadge emit separate Text nodes
//       (AtlasTheme.kt:203-208 / :157+); string-exact targeting only.
//   [x] Harness mirrors accepted shapes: onMain/await/awaitUi + 15s withTimeout
//       (Fr002PendingBadgeUiTest.kt:323-345) + createComposeRule/setContent/
//       waitForIdle render barrier (ProviderDetailPerformanceUiTest.kt:41-73);
//       single-bind deleteOwnRows N/A here (no DB surface — T2's file).
//   [x] No wall-clock asserts, no sleeps, no timeout relaxation.
//
// This file is LANDED (CI pending) and does not compile as a repo test until
// [PORT P-S1..S4] lands selectedQuote. Drop-in target (this path):
//   app/src/androidTest/kotlin/dev/modelatlas/app/ui/ModelDetailPrimaryQuoteScopeTest.kt
