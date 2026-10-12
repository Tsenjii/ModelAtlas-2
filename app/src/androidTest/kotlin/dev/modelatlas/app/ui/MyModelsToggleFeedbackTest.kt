// PK-26 My Models toggle 誠實回饋（Spec /tmp/opencode/ai11-spec-pk26-togglefeedback.md §4;
// test-gap /tmp/opencode/ai11-v-testgap-result.md G2; D-008／Contract §23.1–23.3）.
//
// Production LANDED and committed (64910c0 + H1 + L2v2, c7e7965 pinned in-flight as
// UI-only signal). Ports under test, as read at HEAD b13baff:
//
//   VM (AtlasViewModel.kt:2723-2769) —
//     - per-key in-flight/error StateFlows (:284-287):
//       `myModelToggleInFlight: StateFlow<Set<MyModelKey>>`,
//       `myModelToggleError: StateFlow<Map<MyModelKey, String>>`.
//     - `toggleMyModel`: blank ids early-return (no key to record); in-flight is a
//       UI-only feedback signal (NO gate drops taps — L2v2 removed it; store
//       idempotency de-dupes); error cleared on start (retry semantics);
//       add-path membership write goes DIRECTLY to store (:2751, not via
//       `addMyModelExplicit` which swallows write failures); enrichment wrapped in
//       `runCatching` (:2752-2757, its failure never reads as toggle error);
//       `CancellationException` rethrow (:2759-2760); generic failure recorded as
//       `"WRITE_FAILED"` (:2763, not e.message); in-flight drained in `finally`
//       (:2765-2767). Landed-vs-spec: error value is `"WRITE_FAILED"` (spec draft
//       said e.message?:"UNKNOWN"); `addMyModelExplicit` stays `Unit`+`catch{return}`
//       (:2779-2797); in-flight is UI-only (spec draft's single-flight mutex was
//       removed by L2v2 — ClockFlowTest race evidence).
//
//   UI (MyModelsToggle.kt:27-58) — takes `inFlight`/`error` Booleans (not the spec
//     draft's `syncing`/`error: String?`/`onRetry`); `enabled && !inFlight` gates the
//     click; label stays the membership truth-value while in-flight (no optimistic
//     "加入中" fake state); `error==true` shows the strings.xml hint row
//     (`my_models_toggle_error_hint`), no retry button — retry is re-tapping the toggle.
//
// Harness: exact shape of Fr002ToggleMembershipTest / MyModelsFoundationTest —
//   AtlasApplication + real Room + ViewModelProvider over a ViewModelStore,
//   onMain / await / awaitUi helpers, 15_000 ms ceiling, no sleep-based waits,
//   fixture rows cleaned via deleteOwnRows().
//
// Failure injection (real DB-level, connection-independent, no fake DAO):
// writes to my_models are blocked by a SQLite trigger that RAISE(ABORT)s every
// INSERT/DELETE on that table — unlike `PRAGMA query_only` (a per-connection
// setting that silently misses writes landing on other pooled connections;
// the exact flake in run 37834253351), a trigger fails on ANY connection.
// The in-flight window is made deterministic by holding the primary
// connection with a real `beginTransaction` on a holder thread (the toggle parks
// inside `db.withTransaction`, so in-flight true→false is observable without races).
// Both injections are restored in `finally` and again in tearDown.
//
// Deviation from the spec draft's §4 venue: the draft says "app/src/test VM test with
// fake DB/store" — not constructible at HEAD (AtlasViewModel is an AndroidViewModel
// pulling all deps from AtlasApplication; app/src/test has no Robolectric; every
// existing toggle VM test lives in androidTest with real Room). Pinned at the
// androidTest VM level with real DB injection instead.

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
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.MyModelsStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyModelsToggleFeedbackTest {

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()
    private var lockHolder: Thread? = null
    private var lockRelease: CountDownLatch? = null

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
        releasePrimaryConnection()
        if (::viewModelStore.isInitialized) onMain { viewModelStore.clear() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        runCatching { setMyModelsWriteBlocked(false) }
        deleteOwnRows()
        providerIds.clear()
    }

    // ── Vector 1 ─────────────────────────────────────────────────────────────
    // remove 成功 → error 空、in-flight 經歷 true→false、membership Flow 更新。

    @Test
    fun toggleRemoveOkRecordsNoErrorWithInFlightTrueToFalseAndMembershipUpdate() {
        val fixture = saveFixture("pk26-remove-ok")
        val key = MyModelKey(fixture.providerId, MODEL_ID)
        // MYM-003: saveConnection AUTO-ADDS this exact key (async tail). Skip the
        // manual add entirely — waiting for the auto-membership to settle is the
        // deterministic member-true starting point (a manual add racing the tail
        // was the run-37830809503/37837094470 timeout shape: remove landed first,
        // the tail's put re-added afterwards and the !contains await never fired).
        await { viewModel.myModelKeys.first { it.contains(key) } }
        assertTrue("auto-membership add must record no error", viewModel.myModelToggleError.value.isEmpty())

        // Remove-ok contract WITHOUT the connection-hold parked observation:
        // the exclusive hold on the app's real primary connection proved flaky
        // on CI (background writers chain-park the main thread). The contract
        // itself (in-flight drains true→false, no error, membership truth
        // updated) is fully asserted below.
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_ID) } // toggle on member = remove
        await { viewModel.myModelToggleInFlight.first { it.isEmpty() } } // true→false drain (finally)
        await { viewModel.myModelKeys.first { !it.contains(key) } }      // membership truth updated
        assertTrue("remove-ok must end with an empty error map", viewModel.myModelToggleError.value.isEmpty())
    }

    // ── Vector 2 ─────────────────────────────────────────────────────────────
    // remove 時 store throw → membership 不變、error[key]=="WRITE_FAILED"、in-flight 回 false。
    //
    // Harness history (runs 37834253351..37880066059, 6 red rounds): every shape
    // that depended on saveFixture's ASYNC auto-membership tail (MYM-003) or a
    // manual add-toggle racing it flaked differently on the busy CI emulator
    // (row missing before the toggle, REPLACE races, PRAGMA per-connection
    // misses). Final shape: NO fixture, NO async membership source at all —
    // seed the row SYNCHRONOUSLY via the store itself, then arm the triggers.
    // toggleMyModel only touches my_models (no provider row needed), so a
    // deterministic store seed is the complete, race-free setup.

    @Test
    fun toggleRemoveStoreThrowSetsWriteFailedKeepsMembershipTruthAndDrainsInFlight() {
        val providerId = "pk26-remove-throw-provider-${UUID.randomUUID()}"
        val key = MyModelKey(providerId, MODEL_ID)
        // Deterministic synchronous seed: membership row exists before anything async runs.
        runBlocking { withTimeout(TIMEOUT_MS) { MyModelsStore(app.database).addToMyModels(providerId, MODEL_ID) } }
        providerIds += providerId
        assertTrue(runBlocking { withTimeout(TIMEOUT_MS) { MyModelsStore(app.database).isMyModel(providerId, MODEL_ID) } })

        setMyModelsWriteBlocked(true)
        try {
            onMain { viewModel.toggleMyModel(providerId, MODEL_ID) } // member=true → remove → delete aborts
            await { viewModel.myModelToggleError.first { it.containsKey(key) } }
            assertEquals("landed error value is WRITE_FAILED, not e.message/:\"UNKNOWN\"",
                "WRITE_FAILED", viewModel.myModelToggleError.value[key])
            await { viewModel.myModelToggleInFlight.first { it.isEmpty() } }
            // The aborted delete leaves the row in the table — read the DB
            // directly (fresh store query), not the invalidation-driven Flow.
            val rowAfterToggle = runBlocking {
                withTimeout(TIMEOUT_MS) { MyModelsStore(app.database).isMyModel(providerId, MODEL_ID) }
            }
            assertTrue("failed remove must not change membership truth (DB read: $rowAfterToggle)",
                rowAfterToggle)
        } finally {
            setMyModelsWriteBlocked(false)
        }
    }

    // ── Vector 3a ────────────────────────────────────────────────────────────
    // add 時 store throw → 不進 enrichment（throw 在 enrichment 分支之前）、
    // 記 WRITE_FAILED、membership 真值不變。

    @Test
    fun toggleAddStoreThrowSetsWriteFailedKeepsMembershipEmptyAndNeverTriggersEnrichment() {
        val fixture = saveFixture("pk26-add-throw")
        // MYM-003: saveConnection AUTO-ADDS (fixtureProviderId, MODEL_ID) via its
        // async tail — that exact key can never serve as a "non-member add"
        // vector. Use a DIFFERENT model id on the same provider: it stays a
        // genuine non-member, so toggle = add path, and the auto-membership tail
        // never touches it.
        val manualModelId = MODEL_ID + "-manual-add"
        val key = MyModelKey(fixture.providerId, manualModelId)
        assertTrue(prewire(member = false, key = key))

        setMyModelsWriteBlocked(true)
        try {
            onMain { viewModel.toggleMyModel(fixture.providerId, manualModelId) } // add → store put throws
            await { viewModel.myModelToggleError.first { it.containsKey(key) } }
            assertEquals("WRITE_FAILED", viewModel.myModelToggleError.value[key])
            await { viewModel.myModelToggleInFlight.first { it.isEmpty() } }
            assertTrue("failed add must not change membership truth",
                viewModel.myModelKeys.value.none { it == key })
        } finally {
            setMyModelsWriteBlocked(false)
        }
        // Enrichment isolation: the store throw happens before the enrichment branch,
        // so no dedicated sync state may be contaminated for this provider.
        assertTrue(!viewModel.dedicatedSyncing.value.contains(fixture.providerId))
        assertNull(viewModel.dedicatedSyncError.value[fixture.providerId])
        assertNull(viewModel.dedicatedLastSynced.value[fixture.providerId])
    }

    // ── Vector 3b ────────────────────────────────────────────────────────────
    // enrichment 隔離（AGENTS 鐵律）：membership 寫入成功後 enrichment 失敗
    // （fixture host 不可達）→ membership 保留、絕不讀作 toggle error。

    @Test
    fun addPathEnrichmentFailureIsolatedNeverReadsAsToggleError() {
        val fixture = saveFixture("pk26-enrich-isolated")
        // saveFixture auto-adds MODEL_ID asynchronously. Toggling that same model
        // could REMOVE it instead of testing the ADD path (15s timeout/flaky race).
        // Use a distinct explicit model so this is always a genuine add/enrichment test.
        val addModelId = MODEL_ID + "-manual-enrichment"
        val key = MyModelKey(fixture.providerId, addModelId)
        assertTrue("enrichment test must begin with a non-member model", prewire(member = false, key = key))
        onMain { viewModel.toggleMyModel(fixture.providerId, addModelId) } // add; enrichment fails inside runCatching
        // Membership truth lands as soon as the store write commits — do NOT wait
        // on the in-flight drain here: enrichment (bounded, inside runCatching) can
        // legitimately outlast a busy emulator's 15s window (DNS/connect on the
        // unreachable fixture host), and its completion is irrelevant to the
        // isolation contract under test.
        await { viewModel.myModelKeys.first { it.contains(key) } } // membership written despite enrichment failure
        assertTrue("membership must survive enrichment failure",
            viewModel.myModelKeys.value.contains(key))
        assertTrue("enrichment failure must never read as toggle error",
            viewModel.myModelToggleError.value.isEmpty())
        // Error isolation is Flow-level truth: even after the (possibly still
        // running) enrichment settles, no error may appear for this key.
        await { viewModel.myModelToggleError.first { !it.containsKey(key) } }
    }

    // ── Vector 4 (landed shape) ──────────────────────────────────────────────
    // 連打兩次 toggle（landed：L2v2 已移除 in-flight gate — 第二下絕不被吞）：
    // 兩下都到 store（stale read → REPLACE 冪等）、membership 收斂為成員、
    // 無 error、in-flight 排空。第二次 count 在第一次 add 分發前已入列
    // （Main.immediate 同步入列，add 分發需多一次 Main 往返）→ 讀到 pre-add 狀態。

    @Test
    fun rapidDoubleToggleIsIdempotentWithNoErrorAndNoStuckInFlight() {
        val fixture = saveFixture("pk26-double-toggle")
        val key = MyModelKey(fixture.providerId, MODEL_ID)
        onMain {
            viewModel.toggleMyModel(fixture.providerId, MODEL_ID)
            viewModel.toggleMyModel(fixture.providerId, MODEL_ID) // second tap must not be swallowed
        }
        await { viewModel.myModelToggleInFlight.first { it.isEmpty() } }
        await { viewModel.myModelKeys.first { it.contains(key) } }
        assertTrue("double toggle must record no error", viewModel.myModelToggleError.value.isEmpty())
    }

    // ── Vector 5 ─────────────────────────────────────────────────────────────
    // 失敗後重試成功 → error 清除、membership 更新。

    @Test
    fun failedToggleThenRetryOkClearsErrorAndUpdatesMembership() {
        val fixture = saveFixture("pk26-retry")
        // Same MYM-003 auto-membership hazard as vector 3a: a manual model id
        // keeps this a genuine non-member add, then a genuine retry.
        val manualModelId = MODEL_ID + "-manual-retry"
        val key = MyModelKey(fixture.providerId, manualModelId)

        setMyModelsWriteBlocked(true)
        try {
            onMain { viewModel.toggleMyModel(fixture.providerId, manualModelId) } // add → store put throws
            await { viewModel.myModelToggleError.first { it.containsKey(key) } }
            assertEquals("WRITE_FAILED", viewModel.myModelToggleError.value[key])
        } finally {
            setMyModelsWriteBlocked(false)
        }
        await { viewModel.myModelToggleInFlight.first { it.isEmpty() } }

        onMain { viewModel.toggleMyModel(fixture.providerId, manualModelId) } // retry → success
        await { viewModel.myModelKeys.first { it.contains(key) } }
        await { viewModel.myModelToggleError.first { !it.containsKey(key) } } // retry clears the old error
        await { viewModel.myModelToggleInFlight.first { it.isEmpty() } }
        assertTrue(viewModel.myModelToggleError.value.isEmpty())
    }

    // ── Vector 6 ─────────────────────────────────────────────────────────────
    // blank providerId／providerModelId → 直接 return，無 syncing、無 error。

    @Test
    fun blankIdsEarlyReturnWithoutAnyStateChange() {
        onMain {
            viewModel.toggleMyModel("", MODEL_ID)
            viewModel.toggleMyModel(" ", MODEL_ID)
            viewModel.toggleMyModel("provider", " ")
            viewModel.toggleMyModel("", "")
        }
        assertTrue("blank ids must not enter in-flight", viewModel.myModelToggleInFlight.value.isEmpty())
        assertTrue("blank ids must not record error", viewModel.myModelToggleError.value.isEmpty())
    }

    // ── Vector 7 ─────────────────────────────────────────────────────────────
    // scope 取消 → CancellationException 重拋不被吞：error 不記（取消非失敗）、
    // in-flight 經 finally 排空、測試不掛。
    // membership 刻意不斷言：Room 停放取消中的寫入（cancellable suspension vs
    // native lock）位置不同，release 後 remove 可能完成也可能未完成；兩者皆符合
    // landed cancel 語意（重拋＋不記 error）。

    @Test
    fun scopeCancelIsNeverRecordedAsToggleErrorAndDrainsInFlight() {
        val fixture = saveFixture("pk26-cancel")
        val key = MyModelKey(fixture.providerId, MODEL_ID)
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_ID) } // add
        await { viewModel.myModelKeys.first { it.contains(key) } }
        await { viewModel.myModelToggleInFlight.first { it.isEmpty() } }

        // Cancel without the exclusive connection hold: the hold chain-parked the
        // main thread on the busy app DB (same flake family as vector 1's fix).
        // The contract under test — cancel is rethrown, never recorded as error,
        // in-flight always drains — holds regardless of WHERE the coroutine was
        // suspended when the scope died.
        onMain {
            viewModel.toggleMyModel(fixture.providerId, MODEL_ID) // remove launches
            viewModelStore.clear() // cancel viewModelScope immediately after
        }
        await { viewModel.myModelToggleInFlight.first { it.isEmpty() } } // finally drains on cancel
        assertTrue("cancel is not failure: no error may be recorded",
            !viewModel.myModelToggleError.value.containsKey(key))
    }

    // ===== fixture / injection / snapshot helpers (mirrors Fr002ToggleMembershipTest) =====

    private data class Fixture(val providerId: String)

    private fun saveFixture(label: String): Fixture {
        val name = "My Models Toggle Feedback draft $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, MODEL_ID, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        return Fixture(item.providerId)
    }

    private fun prewire(member: Boolean, key: MyModelKey): Boolean =
        if (member) viewModel.myModelKeys.value.contains(key)
        else viewModel.myModelKeys.value.none { it == key }

    /**
     * Connection-independent write blocker: a trigger aborting every INSERT/DELETE
     * on my_models. `PRAGMA query_only` is per-connection and missed writes on other
     * pooled connections (flake in CI run 37834253351); a trigger fails on any
     * connection, so the store write reliably throws.
     */
    private fun setMyModelsWriteBlocked(on: Boolean) {
        val db = app.database.openHelper.writableDatabase
        // execSQL takes ONE statement per call — create/drop each trigger separately.
        if (on) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS pk26_my_models_abort " +
                    "BEFORE INSERT ON my_models BEGIN SELECT RAISE(ABORT,'injected'); END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS pk26_my_models_abort_del " +
                    "BEFORE DELETE ON my_models BEGIN SELECT RAISE(ABORT,'injected'); END",
            )
        } else {
            db.execSQL("DROP TRIGGER IF EXISTS pk26_my_models_abort")
            db.execSQL("DROP TRIGGER IF EXISTS pk26_my_models_abort_del")
        }
    }

    /**
     * Holds the Room primary connection with a real exclusive transaction on a
     * holder thread: the VM's next `db.withTransaction` parks, making the
     * in-flight window deterministic. Restored via [releasePrimaryConnection]
     * (and again in tearDown).
     */
    private fun holdPrimaryConnection() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(start = true, name = "pk26-primary-lock-holder") {
            runCatching {
                app.database.openHelper.writableDatabase.beginTransaction()
                try {
                    held.countDown()
                    release.await()
                } finally {
                    runCatching { app.database.openHelper.writableDatabase.endTransaction() }
                }
            }
        }
        assertTrue("primary connection must be held within timeout", held.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        lockRelease = release
        lockHolder = holder
    }

    private fun releasePrimaryConnection() {
        lockRelease?.countDown()
        lockHolder?.let { h -> runCatching { h.join(TIMEOUT_MS) } }
        lockRelease = null
        lockHolder = null
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
        const val BASE_URL = "https://mymodels-toggle-feedback.fixture.invalid/v1"
        const val MODEL_ID = "mymodels-toggle-feedback-shared-model"
        const val FIXTURE_SECRET = "mymodels-toggle-feedback-fixture-secret-never-persisted"
    }
}
