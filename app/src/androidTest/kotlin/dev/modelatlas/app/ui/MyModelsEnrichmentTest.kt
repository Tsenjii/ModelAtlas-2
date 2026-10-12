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
 * ③-2 My Models 加入後自動 enrichment 第一片（D-008／Contract §23.3）。
 *
 * 真 `AtlasApplication`（真 Room＋真 Keystore）＋真 `AtlasViewModel`；
 * 不發任何 inference（engine 永不被呼叫），只驗 enrichment 觸發語意：
 * - 不支援 structured pricing／quota 的 provider：靜默跳過，不寫錯誤、
 *   不進入 syncing、membership 照常成立。
 * - 移除 membership：不觸發任何同步副作用。
 * - 空白 providerId：no-op，不崩潰。
 * 支援端真正的 pricing／quota 同步由既有 ADAPT／DedicatedSyncFlowTest 覆蓋；
 * enrichment 失敗不得影響 membership（store 層已由 membership tests 鎖定）。
 */
@RunWith(AndroidJUnit4::class)
class MyModelsEnrichmentTest {

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
    fun addOnUnsupportedProviderSkipsEnrichmentSilentlyButKeepsMembership() {
        val fixture = saveFixture("enrich-skip")
        assertFalse(viewModel.dedicatedSupportsSync(fixture.providerId))

        // saveConnection 成功即自動入會（MYM-003）：先等自動入會完成，再行斷言，
        // 不與自動入會 race。
        val key = MyModelKey(fixture.providerId, MODEL_ID)
        await { viewModel.myModelKeys.first { it.contains(key) } }

        // 靜默跳過：不進 syncing、不寫錯誤、不碰 last-synced。
        await { viewModel.dedicatedSyncing.first { !it.contains(fixture.providerId) } }
        assertFalse(viewModel.dedicatedSyncError.value.containsKey(fixture.providerId))
        assertFalse(viewModel.dedicatedLastSynced.value.containsKey(fixture.providerId))
        assertTrue(viewModel.myModelKeys.value.contains(key))
    }

    @Test
    fun removeMembershipTriggersNoSyncSideEffect() {
        val fixture = saveFixture("enrich-remove")
        val key = MyModelKey(fixture.providerId, MODEL_ID)
        await { viewModel.myModelKeys.first { it.contains(key) } }

        onMain { viewModel.toggleMyModel(fixture.providerId, MODEL_ID) }
        await { viewModel.myModelKeys.first { !it.contains(key) } }

        assertFalse(viewModel.dedicatedSyncing.value.contains(fixture.providerId))
        assertFalse(viewModel.dedicatedSyncError.value.containsKey(fixture.providerId))
    }

    @Test
    fun enrichEntryPointIsFailClosedOnBlankProvider() = runBlocking {
        assertFalse(viewModel.maybeEnrichMyModel(""))
        assertFalse(viewModel.maybeEnrichMyModel("   "))
        assertFalse(viewModel.dedicatedSyncing.value.isNotEmpty())
    }

    @Test
    fun enrichEntryPointUsesResolvedSupportCheck() = runBlocking {
        val fixture = saveFixture("enrich-resolved")
        // 與 production 同一 DB 維度判定一致：fixture 無家族路由即不支援。
        assertEquals(
            app.repository.dedicatedSupportsSyncResolved(fixture.providerId),
            viewModel.maybeEnrichMyModel(fixture.providerId),
        )
        assertFalse(viewModel.dedicatedSyncError.value.containsKey(fixture.providerId))
    }

    // ===== harness =====

    private data class Fixture(val providerId: String)

    private fun saveFixture(label: String): Fixture {
        val name = "My Models Enrich $label ${UUID.randomUUID()}"
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
        const val BASE_URL = "https://mymodels-enrich.fixture.invalid/v1"
        const val MODEL_ID = "mymodels-enrich-shared-model"
        const val FIXTURE_SECRET = "mymodels-enrich-fixture-secret-never-persisted"
    }
}
