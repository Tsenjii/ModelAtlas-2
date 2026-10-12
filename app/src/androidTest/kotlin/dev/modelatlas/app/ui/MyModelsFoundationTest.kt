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
 * MYM-003 foundation（D-008／Contract §23.1；Catalog ≠ My Models）。
 *
 * 真 `AtlasApplication`（真 Room＋真 Keystore）＋真 `AtlasViewModel`；
 * 不發 inference（只用 save／ADD／toggle／remove 路徑）：
 * 1. saveConnection 成功即自動入會，`myModelRows` 可見；
 * 2. addProviderModel ADD 成功即自動入會（免二次 toggle，不以 Test 作 gate）；
 * 3. 移除 membership 後 `myModelRows` 掉列但 `allModelRows` 保留（主頁分離）；
 * 4. ADD 被拒（空白 model）絕不入會。
 * Fixture 只清自己 rows／vault refs／my_models 行。
 */
@RunWith(AndroidJUnit4::class)
class MyModelsFoundationTest {

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
    fun saveConnectionAutoJoinsMyModelsAndShowsInMyRows() {
        val fixture = saveFixture("foundation-save")
        val key = MyModelKey(fixture.providerId, MODEL_ID)

        await { viewModel.myModelKeys.first { it.contains(key) } }
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerId == fixture.providerId } } }
        assertTrue(viewModel.myModelRows.value.any { it.providerId == fixture.providerId && it.providerModelId == MODEL_ID })
    }

    @Test
    fun addProviderModelSuccessAutoJoinsWithoutSecondToggle() {
        val fixture = saveFixture("foundation-add")
        await { viewModel.myModelKeys.first { it.contains(MyModelKey(fixture.providerId, MODEL_ID)) } }

        onMain { viewModel.addProviderModel(fixture.providerId, MODEL_ADDED) }
        await { viewModel.providerModelAction.first { it.status == "SUCCESS" } }

        // 自動入會：免二次 toggle，也不要求先 Test。
        val key = MyModelKey(fixture.providerId, MODEL_ADDED)
        await { viewModel.myModelKeys.first { it.contains(key) } }
        assertEquals("SUCCESS", viewModel.providerModelAction.value.status)
    }

    @Test
    fun removedMembershipDropsMyRowsButKeepsAllRows() {
        val fixture = saveFixture("foundation-remove")
        val key = MyModelKey(fixture.providerId, MODEL_ID)
        await { viewModel.myModelRows.first { rows -> rows.any { it.providerId == fixture.providerId } } }

        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_ID) }
        await { viewModel.myModelKeys.first { !it.contains(key) } }
        await { viewModel.myModelRows.first { rows -> rows.none { it.providerId == fixture.providerId } } }

        // Catalog 側保留：allModelRows 仍有該列，只是主頁（myModelRows）不再顯示。
        assertTrue(viewModel.allModelRows.value.any { it.providerId == fixture.providerId })
        assertTrue(viewModel.myModelRows.value.none { it.providerId == fixture.providerId })
    }

    @Test
    fun rejectedAddNeverJoinsMyModels() {
        val fixture = saveFixture("foundation-reject")
        await { viewModel.myModelKeys.first { it.contains(MyModelKey(fixture.providerId, MODEL_ID)) } }

        onMain { viewModel.addProviderModel(fixture.providerId, "   ") }
        val done = await { viewModel.providerModelAction.first { it.status == "FAILURE" } }
        assertEquals("ADD", done.kind)
        assertFalse(viewModel.myModelKeys.value.any { it.providerId == fixture.providerId && it.providerModelId.isBlank() })
        assertEquals(1, viewModel.myModelKeys.value.count { it.providerId == fixture.providerId })
    }

    // ===== harness =====

    private data class Fixture(val providerId: String)

    private fun saveFixture(label: String): Fixture {
        val name = "My Models Foundation $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, MODEL_ID, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        return Fixture(item.providerId)
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
        const val BASE_URL = "https://mymodels-foundation.fixture.invalid/v1"
        const val MODEL_ID = "mymodels-foundation-shared-model"
        const val MODEL_ADDED = "mymodels-foundation-added-model"
        const val FIXTURE_SECRET = "mymodels-foundation-fixture-secret-never-persisted"
    }
}
