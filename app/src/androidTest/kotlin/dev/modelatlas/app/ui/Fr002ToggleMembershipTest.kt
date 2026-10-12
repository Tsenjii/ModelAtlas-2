// FR-002 pending member toggle semantics (PRODUCT_SPEC §4/§6/§18; Contract §23.1/§23.6).
// Landed repo file; CI pending. Corrected-B: mapper split inside the offering level, zero ModelRow
// struct change, membership rows visible in `myModelRows`, `_allModelRows` stays visible-only.
// Baseline a3f581b/37229173570 (device683/core576/appJVM292 zero failures/errors/skips).
//
// Both ports below are LANDED and committed (FR-002 ACCEPTED —
// docs/TRACEABILITY.md:6, green b83d0bb/37332376642; nothing remains gated):
//
//   PORT A (landed) — mapper: `ModelRowMapper.buildPartitioned(...)`
//     (app/src/main/kotlin/dev/modelatlas/app/ui/ModelRowMapper.kt:79),
//     `ModelRowsPartition(visible, pendingMember)` (:101), and the optional
//     `includeHiddenFor: Set<MyModelKey>` parameter on `build` (:59, delegates
//     with emptySet()) / `buildPartitioned` / private `buildRows` (:178).
//
//   PORT B (landed) — VM: `AtlasViewModel._pendingMemberRows` (:305), the fixed
//     `myModelRows` 3-way combine (`_allModelRows + _pendingMemberRows +
//     myModelKeys`, :2537), the write-back `modelRowsMembershipUsed` trigger
//     (:314/:2207/:2592), and `AtlasViewModel.pendingCandidateKeys` (:2551,
//     the candidate-badge key source).
//
// Everything else below uses ACTUAL committed APIs as read at a3f581b plus the
// accepted in-flight CR-008-F2 clock tail param (`toggleMyModel(..., nowMillis)`),
// in the exact harness shape of MyModelsFoundationTest.kt:
//   - AtlasApplication + real Room + ViewModelProvider over a ViewModelStore,
//   - onMain / await / awaitUi helpers, 15_000 ms ceiling, no sleep-based waits,
//   - fixture rows cleaned via deleteOwnRows().
//
// Semantics locked under corrected-B:
//   1. Pending row (hidden, unverified offering + membership) IS visible in
//      `myModelRows`, with candidate-badge semantics via `pendingCandidateKeys`;
//      the row is never VERIFIED and never invents FREE/0 price fields.
//   2. Toggle-remove deletes ONLY the membership row; offerings / evidence /
//      samples (history) / provider_events byte-identical (row-for-row,
//      ordered concatenation compared).
//   3. Toggle-readd returns the pending row; offering stays hidden until a real
//      verified promotion (MODEL-007 promoteOnVerified path).
//   4. `allModelRows` and the compare truth derived from it (`compareChoices`
//      -> LoadChoicesFromDb over `_allModelRows`; `compareRows` carrying
//      providerModelId) NEVER contain the hidden offering.

package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.MANUAL_OFFERING_SOURCE_USER
import dev.modelatlas.app.data.ManualOfferingStore
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.MyModelsStore
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Fr002ToggleMembershipTest {

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

    // ── Test 1 ───────────────────────────────────────────────────────────────

    @Test
    fun hiddenCandidateMemberVisibleInMyModelRowsWithBadgeSemantics() {
        val fixture = saveFixture("pending-visible")
        seedHiddenCandidate(fixture.providerId, MODEL_PENDING)
        assertHiddenOfferingPresent(fixture.providerId, MODEL_PENDING)

        assertTrue(viewModel.allModelRows.value.none { it.providerModelId == MODEL_PENDING })

        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_PENDING) }
        val key = MyModelKey(fixture.providerId, MODEL_PENDING)
        await { viewModel.myModelKeys.first { it.contains(key) } }

        // Corrected-B core semantics: the pending (hidden, unverified) member
        // row IS surfaced in My Models, never as VERIFIED, never as free/$0.
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerId == fixture.providerId && it.providerModelId == MODEL_PENDING } } }
        val pendingRow = viewModel.myModelRows.value.first { it.providerModelId == MODEL_PENDING }
        assertFalse("pending row must never be VERIFIED", pendingRow.verified)
        assertFalse("pending row must not invent selected-key usability", pendingRow.selectedKeyUsable)
        assertEquals(PriceState.UNKNOWN, pendingRow.priceState)
        assertEquals(QuotaState.UNKNOWN, pendingRow.quotaState)

        // PROPOSED PORT B: candidate badge semantics come from
        // `pendingCandidateKeys` (out-of-band key set, never inside ModelRow).
        await { viewModel.pendingCandidateKeys.first { it.contains(key) } }

        // The hidden offering must NEVER reach the visible catalog / compare truth.
        assertTrue(viewModel.allModelRows.value.none { it.providerModelId == MODEL_PENDING })
        onMain { viewModel.openModelDetail(pendingRow) }
        // Choices derive from _allModelRows only: a unique-canonical hidden member
        // legitimately yields ZERO choices and no failure — gate on loading, not on non-emptiness.
        await { viewModel.compareChoicesLoading.first { !it } }
        assertTrue("hidden member must yield zero compare choices", viewModel.compareChoices.value.isEmpty())
        assertFalse(viewModel.compareChoicesLoadFailed.value)
        assertTrue(viewModel.allModelRows.value.none { it.providerModelId == MODEL_PENDING })
        assertTrue(viewModel.compareRows.value.none { it.providerModelId == MODEL_PENDING })
    }

    // ── Test 2 ───────────────────────────────────────────────────────────────

    @Test
    fun toggleRemoveDeletesMembershipRowOnlyAndRetainsOfferingEvidenceHistoryByteIdentical() {
        val fixture = saveFixture("pending-remove")
        seedHiddenCandidate(fixture.providerId, MODEL_PENDING)
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_PENDING) }
        val key = MyModelKey(fixture.providerId, MODEL_PENDING)
        await { viewModel.myModelKeys.first { it.contains(key) } }
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerModelId == MODEL_PENDING } } }
        val snapshotBeforeRemove = tableSnapshot(fixture.providerId)
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_PENDING) } // same toggle = remove
        await { viewModel.myModelKeys.first { !it.contains(key) } }

        val snapshotAfterRemove = tableSnapshot(fixture.providerId)
        // Membership row deleted; offerings/evidence/samples/provider_events/
        // provider_notices/checkins retained byte-identical (ordered dump equal).
        assertEquals(0, membershipCount(fixture.providerId, MODEL_PENDING))
        assertEquals(snapshotBeforeRemove.dropLast(1), snapshotAfterRemove.dropLast(1))
        // my_models line drops exactly the removed row.
        assertTrue(!snapshotAfterRemove[6].contains(MODEL_PENDING))
        // ...and `myModelRows` drops the pending row immediately.
        await { viewModel.myModelRows.first { rows -> rows.none { it.providerModelId == MODEL_PENDING } } }

        // PROPOSED PORT B: badge key gone too (re-filtered by live myModelKeys).
        await { viewModel.pendingCandidateKeys.first { !it.contains(key) } }

        assertHiddenOfferingPresent(fixture.providerId, MODEL_PENDING) // offering retained
        assertTrue(viewModel.allModelRows.value.none { it.providerModelId == MODEL_PENDING })
    }

    // ── Test 3 ───────────────────────────────────────────────────────────────

    @Test
    fun toggleReaddReturnsPendingRowAndKeepsHiddenOfferingOffAllModelRowsAndCompare() {
        val fixture = saveFixture("pending-readd")
        seedHiddenCandidate(fixture.providerId, MODEL_PENDING)
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_PENDING) }
        val key = MyModelKey(fixture.providerId, MODEL_PENDING)
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerModelId == MODEL_PENDING } } }

        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_PENDING) } // remove
        await { viewModel.myModelKeys.first { !it.contains(key) } }
        await { viewModel.myModelRows.first { rows -> rows.none { it.providerModelId == MODEL_PENDING } } }

        val retainedOffingsSnippet = tableSnapshot(fixture.providerId)

        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_PENDING) } // re-add
        await { viewModel.myModelKeys.first { it.contains(key) } }
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerModelId == MODEL_PENDING } } }
        await { viewModel.pendingCandidateKeys.first { it.contains(key) } }

        // offering / evidence / history unchanged by membership flips;
        // only the my_models row re-appeared (membershipCount asserted below).
        val afterReadd = tableSnapshot(fixture.providerId)
        assertEquals(1, membershipCount(fixture.providerId, MODEL_PENDING))
        // offerings/evidence/samples/provider_events/provider_notices/checkins
        // unchanged by the membership flips (only the my_models line may differ).
        assertEquals(retainedOffingsSnippet.dropLast(1), afterReadd.dropLast(1))
        assertHiddenOfferingPresent(fixture.providerId, MODEL_PENDING)
        assertTrue(viewModel.allModelRows.value.none { it.providerModelId == MODEL_PENDING })
        assertTrue(viewModel.compareRows.value.none { it.providerModelId == MODEL_PENDING })
    }

    // ===== fixture / snapshot helpers (mirrors MyModelsFoundationTest) =====

    private data class Fixture(val providerId: String)

    private fun saveFixture(label: String): Fixture {
        val name = "My Models Pending Toggle draft $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, MODEL_ID, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        return Fixture(item.providerId)
    }

    /**
     * Seeds a hidden, unverified manual offering for the same provider using
     * REAL repository + store APIs (no fake DAO, no edits to protected
     * entities). Reuses the fixture provider's existing endpoint id.
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

    private fun membershipCount(providerId: String, modelId: String): Int =
        runBlocking { MyModelsStore(app.database).isMyModel(providerId, modelId).let { if (it) 1 else 0 } }

    /** Ordered, deterministic dump of every history/evidence table for this provider. */
    private fun tableSnapshot(providerId: String): List<String> {
        val db = app.database.openHelper.readableDatabase
        fun dump(label: String, sql: String): String {
            val sb = StringBuilder(label).append('|')
            db.query(sql, arrayOf(providerId)).use { c ->
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) sb.append(c.getString(i) ?: "NULL").append(';')
                    sb.append("||")
                }
            }
            return sb.toString()
        }
        return listOf(
            dump("offerings", "SELECT * FROM offerings WHERE providerId=? ORDER BY rowid"),
            dump("evidence", "SELECT * FROM evidence WHERE providerId=? ORDER BY rowid"),
            dump("samples", "SELECT * FROM samples WHERE providerId=? ORDER BY rowid"),
            dump("provider_events", "SELECT * FROM provider_events WHERE providerId=? ORDER BY rowid"),
            dump("provider_notices", "SELECT * FROM provider_notices WHERE providerId=? ORDER BY rowid"),
            dump("checkins", "SELECT * FROM checkins WHERE providerId=? ORDER BY rowid"),
            dump("my_models", "SELECT providerId||'|'||providerModelId||'|'||addedAt||'|'||source FROM my_models WHERE providerId=? ORDER BY rowid"),
        )
    }

    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val list = providerIds.joinToString(",") { "?" }
        val ids = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", ids)
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
        const val BASE_URL = "https://mymodels-pending-toggle.fixture.invalid/v1"
        const val MODEL_ID = "mymodels-pending-toggle-shared-model"
        const val MODEL_PENDING = "mymodels-pending-toggle-hidden-candidate"
        const val FIXTURE_SECRET = "mymodels-pending-toggle-fixture-secret-never-persisted"
    }
}

// PROPOSED PORT A note: this file never calls `buildPartitioned` directly;
// the badge/row semantics it relies on is produced inside the VM by the
// PROPOSED partition. When the port lands, `myModelRows` should include the
// pending offering row (see AtlasViewModel hunk) and `pendingCandidateKeys`
// should be exactly the (providerId, providerModelId) set of hidden,
// still-unverified members — one key per row, not one per endpoint row id.
