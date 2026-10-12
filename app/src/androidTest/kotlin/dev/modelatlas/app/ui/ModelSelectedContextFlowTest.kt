package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.data.ConnectionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * MODEL-007／008 selected-context flow 邊界：真 AtlasApplication + Room + Keystore vault + AtlasViewModel。
 *
 * 對應 reviewer 證實的 production regression：selected account／credential／group 原本只有
 * `selectModelKey()` 會寫，而它沒有任何 caller，`refresh()`／`selectInternal()`／save 路徑
 * 只改 `_state.selectedId`，因此三個 flow 永遠 null → `CompareLiveWiring.buildRequestFromDb`
 * 必回 null（MODEL-008 死路徑），`ModelRowMapper` 的 selected-key 投影也永遠空（MODEL-007）。
 *
 * 本檔只驗證由真實 ConnectionRecord 驅動的投影語意：
 * - 選哪條連線，context 就是那條連線的 account／credential，group 恆 null（未指定，非 wildcard）；
 * - 切換連線即隔離（不殘留前一把 key）；
 * - save 自動選取同樣有 context（不只 selectInternal 有）；
 * - 已選 key 被停用／刪除後，其 credential 不得留在 context（clear 或 fallback）；
 * - 停用／匿名／不存在的連線不得捏造 credential；
 * - `selectModelKey` 明確指定的 group 在同一把 key 內，經 Room emission 與 rows 重算後仍保留，
 *   換 key 才清成 null。
 *
 * 不做真實 HTTP（不呼叫 send／requestCompareLive）；secret 為固定測試字串；
 * teardown 只刪本檔自己建立的 provider 資料與 vault refs。
 */
@RunWith(AndroidJUnit4::class)
class ModelSelectedContextFlowTest {
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
            viewModel = ViewModelProvider(viewModelStore, AtlasViewModelFactory(app)).get(AtlasViewModel::class.java)
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
    fun selectedContextFollowsTheChosenConnectionAndIsolatesKeys() {
        val first = givenConnection("ctx-a")
        val second = givenConnection("ctx-b")
        awaitSettled(first.connectionId, second.connectionId)

        onMain { viewModel.selectConnection(second.connectionId) }
        awaitUi { it.selectedId == second.connectionId }
        val onSecond = awaitContext { it.credentialId == second.credentialId }
        assertEquals(second.accountId, onSecond.accountId)

        onMain { viewModel.selectConnection(first.connectionId) }
        awaitUi { it.selectedId == first.connectionId }
        val onFirst = awaitContext { it.credentialId == first.credentialId }
        assertEquals(first.accountId, onFirst.accountId)
        assertNull("連線層沒有 group 維度：未指定就是 null", onFirst.group)
        assertNotEquals(second.credentialId, onFirst.credentialId)
        assertContextMirrorsSelection()

        // 真 DB request builder 必須接上選取態，且拒絕混搭另一個 account 的憑證。
        val rows = awaitRows { result -> result.any { it.providerId == first.providerId } }
        val detailRow = rows.first { it.providerId == first.providerId }
        runBlocking {
            val request = CompareLiveWiring.buildRequestFromDb(
                app.database, detailRow, rows, onFirst.accountId, onFirst.credentialId, onFirst.group,
            )
            assertNotNull("選取連線必須能建立真比測請求", request)
            request!!.offerings.forEach { offering ->
                assertEquals(first.providerId, offering.providerId)
                assertEquals(first.accountId, offering.accountId)
                assertEquals(first.credentialId, offering.credentialId)
            }
            assertNull(CompareLiveWiring.buildRequestFromDb(
                app.database, detailRow, rows, second.accountId, first.credentialId, null,
            ))
        }

        // 明確指定的 group 必須在同一把 key 內被後續 refresh 沿用，不被靜默抹回 null。
        onMain { viewModel.selectModelKey(first.accountId, first.credentialId, EXPLICIT_GROUP) }
        awaitContext { it.group == EXPLICIT_GROUP }

        // 新增連線 → Room emission → refresh() → refreshModelRows() 全都會跑；
        // 等 rows 確實重算完成後，group 仍必須保留（否則 rows 會退回未指定 scope）。
        val third = givenConnection("ctx-c")
        awaitSettled(third.connectionId)
        awaitRows { rows -> rows.any { it.providerModelId == "ctx-fixture-model-ctx-c" } }

        val afterEmission = awaitContext { it.credentialId == first.credentialId }
        assertEquals(EXPLICIT_GROUP, afterEmission.group)
        assertEquals(EXPLICIT_GROUP, viewModel.selectedGroup.value)
        assertContextMirrorsSelection()

        // 換 key 之後 group 必須清成 null：不黏在舊 key 上，也不是 wildcard。
        onMain { viewModel.selectConnection(second.connectionId) }
        val afterSwitch = awaitContext { it.credentialId == second.credentialId }
        assertNull(afterSwitch.group)
    }

    @Test
    fun savingAConnectionAutoSelectsItsDerivedContext() {
        val name = "Selected Context Save ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, "ctx-save-model", SECRET) }

        awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }
        val saved = viewModel.state.value.connections.first { it.name == name }
        providerIds += saved.providerId
        credentialRefs += saved.credentialId

        val afterSave = awaitContext { it.credentialId == saved.credentialId }
        assertEquals(saved.accountId, afterSave.accountId)
        val context = currentContext()
        assertEquals(saved.accountId, context.accountId)
        assertEquals(saved.credentialId, context.credentialId)
        assertNull(context.group)
        assertEquals(saved.id, viewModel.state.value.selectedId)
        assertContextMirrorsSelection()
    }

    @Test
    fun disablingOrDeletingTheSelectedKeyDropsItsContext() {
        val disabled = givenConnection("ctx-disable")
        val deleted = givenConnection("ctx-delete")
        awaitSettled(disabled.connectionId, deleted.connectionId)

        onMain { viewModel.selectConnection(disabled.connectionId) }
        awaitUi { it.selectedId == disabled.connectionId }
        awaitContext { it.credentialId == disabled.credentialId }

        onMain { viewModel.setCredentialEnabled(disabled.connectionId, false) }
        // 停用後該連線退出 active 清單（managed 仍可見），此時才檢查 context。
        awaitUi { state -> !state.saving && state.connections.none { it.id == disabled.connectionId } }
        val afterDisable = awaitContext { it.credentialId != disabled.credentialId }
        // 停用後不得殘留該 key，也不得改用別的 key 冒充；只允許 clear 或 fallback 到可用連線。
        assertNotEquals(disabled.credentialId, afterDisable.credentialId)
        assertContextMirrorsSelection()

        onMain { viewModel.selectConnection(deleted.connectionId) }
        awaitUi { it.selectedId == deleted.connectionId }
        awaitContext { it.credentialId == deleted.credentialId }

        onMain { viewModel.deleteCredential(deleted.connectionId) }
        awaitUi { state -> !state.saving && state.managedConnections.none { it.id == deleted.connectionId } }
        val afterDelete = awaitContext { it.credentialId != deleted.credentialId }
        assertNotEquals(deleted.credentialId, afterDelete.credentialId)
        assertContextMirrorsSelection()
    }

    /**
     * 不變條件：context 只能是「目前被選中且可用」的那條連線的 safe ref；
     * 選中為 null／不可用／無 credential（匿名）時 context 必須全空（fail-closed）。
     */
    private fun assertContextMirrorsSelection() {
        val state = viewModel.state.value
        val context = currentContext()
        val selected = state.connections.firstOrNull { it.id == state.selectedId }
        if (selected == null || !selected.enabled || selected.credentialId.isBlank()) {
            assertNull("選中連線不可用時不得保留 credential（實際 selectedId=${state.selectedId}）", context.credentialId)
            return
        }
        assertEquals(selected.credentialId, context.credentialId)
        assertEquals(selected.accountId, context.accountId)
    }

    private data class Selected(
        val accountId: String?,
        val credentialId: String?,
        val group: String?,
    )

    private fun currentContext(): Selected = Selected(
        viewModel.selectedAccountId.value,
        viewModel.selectedCredentialId.value,
        viewModel.selectedGroup.value,
    )

    /** 等 selected-key 三個 flow 收斂到 predicate 成立；逾時即失敗（不用固定 sleep）。 */
    private fun awaitContext(
        timeoutMillis: Long = TIMEOUT_MS,
        predicate: (Selected) -> Boolean,
    ): Selected {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async {
                withTimeout(timeoutMillis) {
                    combine(
                        viewModel.selectedAccountId,
                        viewModel.selectedCredentialId,
                        viewModel.selectedGroup,
                    ) { account, credential, group -> Selected(account, credential, group) }
                        .first(predicate)
                }
            }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                if (Looper.myLooper() != Looper.getMainLooper()) runCatching { instrumentation.waitForIdleSync() }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    /** 等 rows 重算完成；逾時即失敗（不用固定 sleep）。 */
    private fun awaitRows(
        timeoutMillis: Long = TIMEOUT_MS,
        predicate: (List<ModelRow>) -> Boolean,
    ): List<ModelRow> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { viewModel.allModelRows.first(predicate) } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                if (Looper.myLooper() != Looper.getMainLooper()) runCatching { instrumentation.waitForIdleSync() }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    /**
     * 等 Room 流程與 viewModelScope 都收斂：以 withTimeout + state.first 等待，不用固定 sleep。
     * 測試執行緒不是主執行緒，所以一併用 waitForIdleSync 讓 Main 派發的協程有機會推進。
     */
    private fun awaitUi(timeoutMillis: Long = TIMEOUT_MS, predicate: (AtlasUiState) -> Boolean): AtlasUiState {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { viewModel.state.first(predicate) } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                if (Looper.myLooper() != Looper.getMainLooper()) runCatching { instrumentation.waitForIdleSync() }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    private fun awaitSettled(vararg connectionIds: String) {
        val wanted = connectionIds.toSet()
        awaitUi { state ->
            !state.saving && !state.busy &&
                state.connections.count { it.id in wanted } == wanted.size &&
                state.managedConnections.count { it.id in wanted } == wanted.size
        }
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun newCredentialRef(): String = newId().also {
        credentialRefs += it
        app.vault.store(it, VAULT_ORIGIN, SECRET)
    }

    /** 建立一整組 provider/account/credential/endpoint/offering/availability，全部是本檔自己的 UUID。 */
    private fun givenConnection(label: String): Fixture {
        val credentialId = newCredentialRef()
        val record = ConnectionRecord(
            id = newId(),
            providerId = newId().also { providerIds += it },
            accountId = newId(),
            credentialId = credentialId,
            endpointId = newId(),
            name = "Selected Context Fixture $label",
            baseUrl = BASE_URL,
            endpointUrl = ENDPOINT_URL,
            modelId = "ctx-fixture-model-$label",
        )
        runBlocking { app.repository.addConnection(record) }
        return Fixture(
            providerId = record.providerId,
            accountId = record.accountId,
            credentialId = record.credentialId,
            connectionId = record.id,
        )
    }

    /** 只刪本檔建立的 provider 及其子資料，順序滿足 RESTRICT 外鍵；其他 session 的資料不動。 */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val providerList = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val accountsOfProvider = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($providerList))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM credential_availability WHERE $accountsOfProvider", args)
            db.execSQL("DELETE FROM credentials WHERE $accountsOfProvider", args)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($providerList)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private data class Fixture(
        val providerId: String,
        val accountId: String,
        val credentialId: String,
        val connectionId: String,
    )

    private class AtlasViewModelFactory(private val app: AtlasApplication) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AtlasViewModel(app) as T
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val HOST = "selected-context.fixture.invalid"
        const val BASE_URL = "https://$HOST/v1"
        const val ENDPOINT_URL = "https://$HOST/v1/chat/completions"
        const val VAULT_ORIGIN = "https://$HOST:443"
        const val SECRET = "selected-context-fixture-not-a-real-secret"
        const val EXPLICIT_GROUP = "ctx-group-vip"
    }
}
