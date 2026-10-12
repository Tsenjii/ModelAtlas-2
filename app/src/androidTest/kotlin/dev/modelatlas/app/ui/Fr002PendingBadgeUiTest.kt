// FR-002 pending member row CAUTION badge — LOCK-C UI device test
// (PRODUCT_SPEC §4/§6/§18; Contract §23.1/§23.6; ai9-fr002-badge-ui-spec-result.md §5 vectors 1–3).
// Landed form of /tmp/opencode/ai9-fr002-badge-test-draft.kt (unit fr002-badge-test-draft-takeover,
// zlkpro GLM, with the PROPOSED PORT U1–U3 Compose assertions enabled — the Astra UI slice supplying
// ModelRowCard.pending / ModelSearchResults.pendingCandidateKeys threading is in this same commit).
// Semantics locked (badge-ui-spec §3/§5):
//   V1  pure-pending member row (member key with NO visible row) shows the provider_detail_candidate
//       CAUTION badge; whole-row tap invokes the same onRowClick (unchanged).
//   V2  mixed key (one VISIBLE + one HIDDEN offering under ONE membership key) is ABSENT from
//       pendingCandidateKeys → the visible row shows NO badge.
//   V3  membership removal drops the key from pendingCandidateKeys and the row from myModelRows
//       IMMEDIATELY via the live myModelKeys gate — badge disappears reactively, no reload, no sleeps.
//   Invariants: pending rows render natural UNKNOWN price/quota, never VERIFIED, never FREE/$0;
//       badge ⟺ key ∈ pendingCandidateKeys only; badge state never writes offerings.hidden.

package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.R
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.MANUAL_OFFERING_SOURCE_USER
import dev.modelatlas.app.data.ManualOfferingStore
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Fr002PendingBadgeUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        viewModelStore = ViewModelStore()
        onMain {
            viewModel = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) onMain { viewModelStore.clear() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ── LOCK-C vector 1 / spec §5.1: pure-pending badged ─────────────────────

    @Test
    fun purePendingMemberRowShowsCandidateBadgeAndKeepsWholeRowTap() {
        val fixture = saveFixture("badge-pure")
        // Real VM path: saveConnection auto-joins the visible base model, then
        // addProviderModel creates the hidden USER candidate + membership — a member
        // key with NO visible row (pure-pending).
        onMain { viewModel.addProviderModel(fixture.providerId, MODEL_PENDING) }
        await { viewModel.providerModelAction.first { it.status == "SUCCESS" } }
        val key = MyModelKey(fixture.providerId, MODEL_PENDING)
        await { viewModel.myModelKeys.first { it.contains(key) } }

        // Pending row surfaces in myModelRows with natural fields only.
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerModelId == MODEL_PENDING } } }
        val pendingRow = viewModel.myModelRows.value.first { it.providerModelId == MODEL_PENDING }
        assertFalse("pending row must never be VERIFIED", pendingRow.verified)
        assertFalse("pending row must not invent selected-key usability", pendingRow.selectedKeyUsable)
        assertEquals(PriceState.UNKNOWN, pendingRow.priceState)
        assertEquals(QuotaState.UNKNOWN, pendingRow.quotaState)

        // Badge contract: key ∈ pendingCandidateKeys ⟺ badge.
        await { viewModel.pendingCandidateKeys.first { it.contains(key) } }
        // Catalog truth stays clean: hidden offering never enters allModelRows.
        assertTrue(viewModel.allModelRows.value.none { it.providerModelId == MODEL_PENDING })

        // Compose rendering: the pending row shows the CAUTION candidate badge; the badge is a
        // non-interactive child of AtlasCard(onClick) so tapping its text fires the SAME row click.
        val candidateLabel = app.getString(R.string.provider_detail_candidate)
        var clicked: ModelRow? = null
        compose.setContent {
            AtlasTheme {
                ModelRowCard(
                    row = pendingRow,
                    dimNonFree = true,
                    pending = MyModelKey(pendingRow.providerId, pendingRow.providerModelId)
                        in viewModel.pendingCandidateKeys.value,
                    onClick = { clicked = pendingRow },
                )
            }
        }
        compose.onNodeWithText(candidateLabel).assertIsDisplayed()
        compose.onNodeWithText(candidateLabel).performClick()
        assertEquals(pendingRow, clicked)
        // Natural rendering: UNKNOWN price badge text present; never FREE / $0.
        compose.onNodeWithText(app.getString(priceBadgeText(PriceState.UNKNOWN))).assertIsDisplayed()
        compose.onAllNodesWithText("FREE").assertCountEquals(0)
        compose.onAllNodesWithText("$0").assertCountEquals(0)
    }

    // ── LOCK-C vector 2 / spec §5.2: mixed key (visible + hidden) NOT badged ──

    @Test
    fun mixedKeyWithVisibleAndHiddenOfferingIsNeverBadged() {
        val fixture = saveFixture("badge-mixed")
        // saveConnection auto-join makes (provider, MODEL_ID) a member key WITH a visible row.
        val key = MyModelKey(fixture.providerId, MODEL_ID)
        await { viewModel.myModelKeys.first { it.contains(key) } }
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerModelId == MODEL_ID } } }

        // Add a hidden sibling offering under the SAME membership key via real store +
        // repository APIs (ManualOfferingStore.createHiddenOffering + addManualOffering).
        seedHiddenCandidate(fixture.providerId, MODEL_ID)
        onMain { viewModel.refreshModelRows() }
        await { viewModel.modelRowsLoading.first { !it } }

        // H3 mixed-key exclusion: the key has a visible row → ABSENT from
        // pendingCandidateKeys, hence the visible row renders NO badge.
        await { viewModel.pendingCandidateKeys.first { !it.contains(key) } }
        assertFalse(
            "mixed key must not be pending-candidate",
            viewModel.pendingCandidateKeys.value.contains(key),
        )
        // Only the visible offering surfaces for the mixed key in myModelRows.
        val visibleRowCount = viewModel.myModelRows.value.count {
            it.providerId == fixture.providerId && it.providerModelId == MODEL_ID
        }
        assertEquals("only the visible offering surfaces for the mixed key", 1, visibleRowCount)
        // The hidden sibling stays out of the visible catalog truth: MODEL_ID rows in
        // allModelRows are exactly the visible one (no duplicate/hidden twin).
        assertEquals(
            1,
            viewModel.allModelRows.value.count {
                it.providerId == fixture.providerId && it.providerModelId == MODEL_ID
            },
        )

        // Compose rendering: the visible row of a mixed key shows NO candidate badge.
        val visibleRow = viewModel.myModelRows.value
            .first { it.providerId == fixture.providerId && it.providerModelId == MODEL_ID }
        val candidateLabel = app.getString(R.string.provider_detail_candidate)
        compose.setContent {
            AtlasTheme {
                ModelRowCard(
                    row = visibleRow,
                    dimNonFree = false,
                    pending = MyModelKey(visibleRow.providerId, visibleRow.providerModelId)
                        in viewModel.pendingCandidateKeys.value, // false for the mixed key
                    onClick = {},
                )
            }
        }
        compose.onAllNodesWithText(candidateLabel).assertCountEquals(0)
    }

    // ── LOCK-C vector 3 / spec §5.3: remove unbadges immediately, no reload ───

    @Test
    fun membershipRemovalUnbadgesImmediatelyWithoutReload() {
        val fixture = saveFixture("badge-remove")
        onMain { viewModel.addProviderModel(fixture.providerId, MODEL_PENDING) }
        await { viewModel.providerModelAction.first { it.status == "SUCCESS" } }
        val key = MyModelKey(fixture.providerId, MODEL_PENDING)
        await { viewModel.myModelKeys.first { it.contains(key) } }
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerModelId == MODEL_PENDING } } }
        await { viewModel.pendingCandidateKeys.first { it.contains(key) } }
        val pendingRowSnapshot = viewModel.myModelRows.value.first { it.providerModelId == MODEL_PENDING }

        // Same toggle = remove. NO viewModel.refreshModelRows() call here: the live
        // myModelKeys gate (P3 combine) must drop the row and the badge key reactively.
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_PENDING) }

        // V3: badge key gone, row gone — immediately, without any reload.
        await { viewModel.pendingCandidateKeys.first { !it.contains(key) } }
        await { viewModel.myModelRows.first { rows -> rows.none { it.providerModelId == MODEL_PENDING } } }
        assertFalse(viewModel.pendingCandidateKeys.value.contains(key))
        // The hidden offering itself is retained (membership-only delete, D-006).
        assertHiddenOfferingPresent(fixture.providerId, MODEL_PENDING)
        assertTrue(viewModel.myModelKeys.value.none { it == key })

        // Compose rendering: with the removal applied, a card whose pending flag is derived from
        // the live state shows NO candidate badge (reactive disappearance, no sleeps).
        val candidateLabel = app.getString(R.string.provider_detail_candidate)
        compose.setContent {
            AtlasTheme {
                ModelRowCard(
                    row = pendingRowSnapshot,
                    dimNonFree = true,
                    pending = MyModelKey(pendingRowSnapshot.providerId, pendingRowSnapshot.providerModelId)
                        in viewModel.pendingCandidateKeys.value,
                    onClick = {},
                )
            }
        }
        compose.onAllNodesWithText(candidateLabel).assertCountEquals(0)
    }

    // ===== fixture / snapshot helpers (mirrors Fr002ToggleMembershipTest at HEAD) =====

    private data class Fixture(val providerId: String)

    private fun saveFixture(label: String): Fixture {
        val name = "My Models Pending Badge $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, MODEL_ID, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        return Fixture(item.providerId)
    }

    /**
     * Seeds a hidden, unverified manual offering for the same provider using REAL
     * repository + store APIs (no fake DAO, no edits to protected entities). Reuses
     * the fixture provider's existing endpoint id.
     */
    private fun seedHiddenCandidate(providerId: String, modelId: String): String {
        val db = app.database
        val endpointId = runBlocking {
            db.offeringDao().byProvider(providerId).first().endpointId
        }
        val draft = ManualOfferingStore.createHiddenOffering(
            providerId = providerId,
            endpointId = endpointId,
            providerModelId = modelId,
            source = MANUAL_OFFERING_SOURCE_USER,
            evidenceId = "ev-${UUID.randomUUID()}",
            observedAt = 1000L,
        )
        return runBlocking {
            AtlasRepository(db).addManualOffering(draft)
            endpointId
        }
    }

    private fun assertHiddenOfferingPresent(providerId: String, modelId: String) {
        val db = app.database
        val row = runBlocking { db.offeringDao().byProvider(providerId).first { it.providerModelId == modelId } }
        assertTrue(row.hidden)
        assertTrue(row.manual)
    }

    // cleanup: mirror of the committed Fr002EndpointVisibilityTest.deleteOwnRows —
    // single-bind ids array, outer IN clauses are plain subqueries with no placeholders.
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val list = providerIds.joinToString(",") { "?" }
        val ids = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM snapshot_heads WHERE scopeKey IN (SELECT scopeKey FROM snapshot_facts WHERE evidenceId IN (SELECT id FROM evidence WHERE providerId IN ($list)))", ids)
            db.execSQL("DELETE FROM snapshot_facts WHERE evidenceId IN (SELECT id FROM evidence WHERE providerId IN ($list))", ids)
            db.execSQL("DELETE FROM price_snapshots WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM evidence WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_notices WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_events WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", ids)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", ids)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM my_models WHERE providerId IN ($list)", ids)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

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

    private fun awaitUi(predicate: (AtlasUiState) -> Boolean): AtlasUiState =
        await { viewModel.state.first(predicate) }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://fr002-badge.fixture.invalid/v1"
        const val MODEL_ID = "fr002-badge-base-model"
        const val MODEL_PENDING = "fr002-badge-hidden-candidate"
        const val FIXTURE_SECRET = "fr002-badge-fixture-secret-never-persisted"
    }
}
