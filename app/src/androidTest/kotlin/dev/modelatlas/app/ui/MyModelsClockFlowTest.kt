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

/**
 * CR-008 F2 VM clock (PRODUCT_SPEC §6/18; Contract §23.1–23.3).
 *
 * Real `AtlasApplication` + real `AtlasViewModel` + real Room/Keystore; public
 * paths only (`toggleMyModel` / `saveConnection` / `saveManualConnection` /
 * `addProviderModel`). Never calls private `addMyModelExplicit`.
 *
 * - Toggle tests use the landed additive tail `toggleMyModel(..., nowMillis)`
 *   and fail on the old VM behavior (which persisted `addedAt = 0`).
 * - Save/ADD tests assert only the real default clock (`addedAt > 0`, never
 *   wall-clock equality) plus exact source and row shape.
 * - No failure/cancel enrichment assertions here: those need F1 controlled
 *   ports and are explicitly out of scope.
 * - No F1 retry/visibility claims, no `ModelRow.addedAt` plumbing, no schema/
 *   DAO changes, no frozen-expectation edits. Fixtures are test-only.
 */
@RunWith(AndroidJUnit4::class)
class MyModelsClockFlowTest {

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

    @Test
    fun toggleInjectedTimestampPersistsExactly() {
        val fixture = saveFixtureAndAwaitJoin("clock-toggle")
        val key = MyModelKey(fixture.providerId, MODEL_ADDED)

        // Fresh providerId per fixture, so MODEL_ADDED starts absent; the toggle
        // below is the first membership write for this key. Only the injected
        // clock call needs the landed port; absence itself needs no setup.
        val t = 1_700_000_000_000L
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_ADDED, nowMillis = t) }
        await { viewModel.myModelKeys.first { it.contains(key) } }

        val row = runBlocking {
            app.database.myModelsDao().all().single {
                it.providerId == fixture.providerId && it.providerModelId == MODEL_ADDED
            }
        }
        assertEquals(t, row.addedAt)
        assertEquals("USER", row.source)
    }

    @Test
    fun toggleRemoveThenReaddUsesNewClockExactlyOnce() {
        val fixture = saveFixtureAndAwaitJoin("clock-readd")
        val key = MyModelKey(fixture.providerId, MODEL_ADDED)

        val t1 = 1_700_000_000_000L
        val t2 = 1_700_000_000_999L
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_ADDED, nowMillis = t1) }
        await { viewModel.myModelKeys.first { it.contains(key) } }
        // Removal carries no timestamp: the remove path takes no clock argument.
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_ADDED) }
        await { viewModel.myModelKeys.first { !it.contains(key) } }
        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_ADDED, nowMillis = t2) }
        await { viewModel.myModelKeys.first { it.contains(key) } }

        val rows = runBlocking {
            app.database.myModelsDao().all().filter {
                it.providerId == fixture.providerId && it.providerModelId == MODEL_ADDED
            }
        }
        assertEquals(1, rows.size)
        assertEquals(t2, rows.single().addedAt)
        assertEquals("USER", rows.single().source)
    }

    @Test
    fun saveConnectionPersistsRealDefaultClockNonzero() {
        val fixture = saveFixtureAndAwaitJoin("clock-save")
        val rows = runBlocking {
            app.database.myModelsDao().all().filter { it.providerId == fixture.providerId }
        }
        assertEquals(1, rows.size)
        assertTrue("addedAt must be >0 after real default clock, was ${rows.single().addedAt}", rows.single().addedAt > 0L)
        assertEquals("USER", rows.single().source)
    }

    @Test
    fun saveManualConnectionPersistsRealDefaultClockNonzero() {
        val name = "My Models Clock clock-manual ${UUID.randomUUID()}"
        // Manual connections surface in the same `state.connections` projection
        // as saveConnection today; if that projection ever filters manual rows,
        // this barrier (not the clock assertion) is what times out.
        onMain { viewModel.saveManualConnection(name, BASE_URL, MODEL_ID, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId

        await { viewModel.myModelKeys.first { it.contains(MyModelKey(item.providerId, MODEL_ID)) } }
        val rows = runBlocking {
            app.database.myModelsDao().all().filter { it.providerId == item.providerId }
        }
        assertEquals(1, rows.size)
        assertTrue("addedAt must be >0 after real default clock, was ${rows.single().addedAt}", rows.single().addedAt > 0L)
        assertEquals("USER", rows.single().source)
    }

    @Test
    fun addProviderModelSuccessAutoJoinPersistsRealDefaultClockNonzero() {
        val fixture = saveFixtureAndAwaitJoin("clock-add")
        val key = MyModelKey(fixture.providerId, MODEL_ADDED)

        onMain { viewModel.addProviderModel(fixture.providerId, MODEL_ADDED) }
        // Status flips SUCCESS before auto-join, so the keys await below is the
        // real membership barrier; keep both awaits in this order.
        await { viewModel.providerModelAction.first { it.status == "SUCCESS" } }
        await { viewModel.myModelKeys.first { it.contains(key) } }

        val row = runBlocking {
            app.database.myModelsDao().all().single {
                it.providerId == fixture.providerId && it.providerModelId == MODEL_ADDED
            }
        }
        assertTrue("addedAt must be >0 after real default clock, was ${row.addedAt}", row.addedAt > 0L)
        assertEquals("USER", row.source)
    }

    @Test
    fun rejectedAddLeavesMembershipExactlyUnchanged() {
        val fixture = saveFixtureAndAwaitJoin("clock-reject")
        // Barrier first: auto-join from save runs after `saving=false`, so the
        // `before` snapshot is only valid once membership is confirmed present.
        val before = runBlocking {
            app.database.myModelsDao().all().filter { it.providerId == fixture.providerId }
        }
        assertEquals(1, before.size)

        onMain { viewModel.addProviderModel(fixture.providerId, "   ") }
        await { viewModel.providerModelAction.first { it.status == "FAILURE" } }

        val after = runBlocking {
            app.database.myModelsDao().all().filter { it.providerId == fixture.providerId }
        }
        assertEquals(before, after)
        assertTrue(after.all { it.addedAt > 0L })
        assertFalse(after.any { it.providerModelId.isBlank() })
    }

    // ===== harness (mirrors MyModelsFoundationTest; T1/T2/T3 corrections applied) =====

    private data class Fixture(val providerId: String)

    private fun saveFixture(label: String): Fixture {
        val name = "My Models Clock $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, MODEL_ID, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        return Fixture(item.providerId)
    }

    /**
     * Production auto-joins membership after the `saving=false` state update
     * (same `finally`), so the connections barrier alone does not prove the
     * `my_models` row exists. Every `my_models` snapshot below starts from
     * this membership barrier.
     */
    private fun saveFixtureAndAwaitJoin(label: String): Fixture {
        val fixture = saveFixture(label)
        await { viewModel.myModelKeys.first { it.contains(MyModelKey(fixture.providerId, MODEL_ID)) } }
        return fixture
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
            // Enrichment attaches canonical/alias rows per provider; aliases are
            // owned by this fixture's provider and must be removed. Canonical
            // rows stay: `provider_model_aliases` holds a RESTRICT FK to
            // `canonical_models`, and canonical rows are shared by normalized
            // name across fixtures.
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
        const val BASE_URL = "https://mymodels-clock.fixture.invalid/v1"
        const val MODEL_ID = "mymodels-clock-shared-model"
        const val MODEL_ADDED = "mymodels-clock-added-model"
        const val FIXTURE_SECRET = "mymodels-clock-fixture-secret-never-persisted"
    }
}
