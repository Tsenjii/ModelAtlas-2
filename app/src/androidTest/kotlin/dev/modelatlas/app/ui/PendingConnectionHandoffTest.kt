package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.Usage
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PROV-001 / MODEL-006 / MODEL-007：save → pending 交接的確定性 regression。
 *
 * TRACEABILITY：PROV-001（`PRODUCT_SPEC` §4 Detail Models）、MODEL-006（§8 Manual
 * offering）、MODEL-007（§6 selected-key usable projection）。
 *
 * Regression 本體：`AtlasViewModel.refresh()` 的 pending eviction 曾用 OR
 *（任一 cache 先到就移除 pending），導致 management 投影先到、active 仍是舊快照時：
 * pending 被提前移除 → active `connections` 沒有新 id → `selectedId` fallback 到舊列 →
 * context 變更 → 在途 probe 被合法取消機制誤殺（CANCELLED）。Production 已改為 AND
 *（兩邊都 publish 才移除），本檔鎖住該語意。
 *
 * 真實度：真 `AtlasApplication`（真 Room ＋真 Keystore vault）＋真 `AtlasViewModel`；
 * engine 只以既有 `providerModelTestEngine` 建構參數注入
 * `InferenceEngine(GatedProbeTransport, app.repository)`（與
 * `ProviderModelActionViewModelTest` 相同 seam），遙測仍經真 repository 落庫，
 * 不做任何網路 I/O，不新增 production test hook，不用 fake repository。
 * Room 在裝置上不保證 management／active emission 順序；本檔把順序變成確定性輸入：
 * 先取消 VM init collectors（之後無 async emission 再覆寫 cache），再把 repository
 * 真實投影讀回、經反射寫入 VM 私有 `connectionCache`／`managementCache`，最後反射呼叫
 * 真正的 production `refresh()`。因此 eviction／fallback／context／取消鏈全是
 * production 程式碼，不是 helper 鏡像；舊 OR 下 management-first 會在公開
 * UI state／context 斷言上失敗。
 *
 * 訊號一律是 production 公開可觀察值：`state.selectedId`／`connections`／
 * `managedConnections`／`selectedAccountId`／`selectedCredentialId`／
 * `providerModelAction`。反射只用於 cache 控制與 eviction 輔助不變量檢查。
 * fixture（測試憑證值、provider／連線）只存在本 test sourceSet，
 * teardown 只刪本檔自己的 rows 與 vault refs，不碰產品歷史。
 */
@RunWith(AndroidJUnit4::class)
class PendingConnectionHandoffTest {

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore
    private val transport = GatedProbeTransport()

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        transport.reset()
        viewModelStore = ViewModelStore()
        onMain {
            viewModel = ViewModelProvider(viewModelStore, HandoffViewModelFactory(app, transport))
                .get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            runCatching {
                onMain { viewModel.cancelProviderModelAction() }
                if (viewModel.providerModelAction.value.busy) awaitAction("CANCELLED")
            }
        }
        if (::viewModelStore.isInitialized) onMain { viewModelStore.clear() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ---- 1. management-first：單一投影到達不得掉選取，兩邊都到才 evict ----

    @Test
    fun managementFirstHandoffKeepsPendingSelectionAndCompletesProbe() {
        val old = saveOldConnection("沿用舊連線")
        isolateCollectors()
        val pending = savePendingConnection("交接新連線", MODEL_MGMT, old.id)
        assertEquals(pending.id, viewModel.state.value.selectedId)
        await { viewModel.selectedCredentialId.first { it == pending.credentialId } }
        val knownSamples = sampleIds(pending.providerId)

        onMain { viewModel.testProviderModel(pending.providerId, MODEL_MGMT) }
        assertEquals("probe 必須被接受為 RUNNING", "RUNNING", viewModel.providerModelAction.value.status)
        await { transport.awaitStarted() }

        // Management 投影先到，active 仍是舊快照：pending 必須存活，選取／列表／context 不得掉。
        driveHandoff(
            connection = listOf(activeRowOf(old.id)),
            management = listOf(managedRowOf(old.id), managedRowOf(pending.id)),
        )
        val single = viewModel.state.value
        assertEquals("management-first 不得把 pending 選取 fallback 到舊列", pending.id, single.selectedId)
        assertTrue(connectionIds().containsAll(listOf(old.id, pending.id)))
        assertTrue(managedIds().containsAll(listOf(old.id, pending.id)))
        assertEquals(pending.accountId, viewModel.selectedAccountId.value)
        assertEquals(pending.credentialId, viewModel.selectedCredentialId.value)
        assertEquals("RUNNING", viewModel.providerModelAction.value.status)
        assertTrue("單一投影到達時 pending 不得 evict", pendingRecords().containsKey(pending.id))

        // Active 投影隨後到達：兩邊都到 pending 才 evict，兩列皆無重複。
        driveHandoff(
            connection = listOf(activeRowOf(old.id), activeRowOf(pending.id)),
            management = listOf(managedRowOf(old.id), managedRowOf(pending.id)),
        )
        assertEquals(pending.id, viewModel.state.value.selectedId)
        assertSingleOccurrence(connectionIds(), pending.id)
        assertSingleOccurrence(managedIds(), pending.id)
        assertTrue(connectionIds().containsAll(listOf(old.id, pending.id)))
        assertTrue(managedIds().containsAll(listOf(old.id, pending.id)))
        assertEquals(pending.credentialId, viewModel.selectedCredentialId.value)
        assertEquals("RUNNING", viewModel.providerModelAction.value.status)
        assertFalse("兩邊都到達後 pending 必須 evict", pendingRecords().containsKey(pending.id))

        // 放行 gate：probe 必須以本次 exact sample 完成，不得被交接誤殺。
        transport.release()
        val done = awaitAction("SUCCESS")
        assertEquals(pending.id, done.connectionId)
        assertEquals("TEST", done.kind)
        assertEquals("單次 minimal probe 不得重發", 1, transport.callCount)
        assertProbePromoted(pending, knownSamples, MODEL_MGMT)
        assertNoSecretInOwnRows()
    }

    // ---- 2. active-first：單一投影橋接 managed 不得重複，兩邊都到才 evict ----

    @Test
    fun activeFirstHandoffBridgesManagedAndEvictsOnlyWhenBothArrive() {
        val old = saveOldConnection("沿用舊連線")
        isolateCollectors()
        val pending = savePendingConnection("交接新連線", MODEL_ACTIVE, old.id)
        assertEquals(pending.id, viewModel.state.value.selectedId)
        await { viewModel.selectedCredentialId.first { it == pending.credentialId } }
        val knownSamples = sampleIds(pending.providerId)

        onMain { viewModel.testProviderModel(pending.providerId, MODEL_ACTIVE) }
        assertEquals("probe 必須被接受為 RUNNING", "RUNNING", viewModel.providerModelAction.value.status)
        await { transport.awaitStarted() }

        // Active 投影先到，management 仍是舊快照：managed 側由 pending 橋接，恰好一次。
        driveHandoff(
            connection = listOf(activeRowOf(old.id), activeRowOf(pending.id)),
            management = listOf(managedRowOf(old.id)),
        )
        assertEquals("active-first 不得掉選取", pending.id, viewModel.state.value.selectedId)
        assertSingleOccurrence(connectionIds(), pending.id)
        assertSingleOccurrence(managedIds(), pending.id)
        assertTrue(connectionIds().containsAll(listOf(old.id, pending.id)))
        assertTrue("managed 側必須由 pending 橋接", managedIds().containsAll(listOf(old.id, pending.id)))
        assertEquals(pending.credentialId, viewModel.selectedCredentialId.value)
        assertEquals("RUNNING", viewModel.providerModelAction.value.status)
        assertTrue("active 先到時 pending 必須保留到 management 也到", pendingRecords().containsKey(pending.id))

        // Management 投影隨後到達：兩邊都到 pending 才 evict，兩列皆無重複。
        driveHandoff(
            connection = listOf(activeRowOf(old.id), activeRowOf(pending.id)),
            management = listOf(managedRowOf(old.id), managedRowOf(pending.id)),
        )
        assertEquals(pending.id, viewModel.state.value.selectedId)
        assertSingleOccurrence(connectionIds(), pending.id)
        assertSingleOccurrence(managedIds(), pending.id)
        assertEquals(pending.credentialId, viewModel.selectedCredentialId.value)
        assertEquals("RUNNING", viewModel.providerModelAction.value.status)
        assertFalse("兩邊都到達後 pending 必須 evict", pendingRecords().containsKey(pending.id))

        transport.release()
        val done = awaitAction("SUCCESS")
        assertEquals(pending.id, done.connectionId)
        assertEquals("單次 minimal probe 不得重發", 1, transport.callCount)
        assertProbePromoted(pending, knownSamples, MODEL_ACTIVE)
        assertNoSecretInOwnRows()
    }

    // ---- 3. 停用：blocked/disabled pending 不得復活為可用（安全對應項） ----

    @Test
    fun disabledPendingIsEvictedAndNeverResurrectsAsUsable() {
        val old = saveOldConnection("沿用舊連線")
        isolateCollectors()
        val pending = savePendingConnection("待停用連線", MODEL_BLOCKED, old.id)
        assertEquals(pending.id, viewModel.state.value.selectedId)
        await { viewModel.selectedCredentialId.first { it == pending.credentialId } }

        onMain { viewModel.testProviderModel(pending.providerId, MODEL_BLOCKED) }
        assertEquals("probe 必須被接受為 RUNNING", "RUNNING", viewModel.providerModelAction.value.status)
        await { transport.awaitStarted() }

        // 真停用路徑：直連取消＋本機封鎖＋Room 寫入＋refresh，全是 production 呼叫。
        onMain { viewModel.setCredentialEnabled(pending.id, false) }
        awaitUi { !it.saving }
        val terminal = awaitAction("CANCELLED")
        assertEquals("停用取消必須歸屬同一條連線", pending.id, terminal.connectionId)
        // context 變更 Gate：finally 的 refresh 跑完，context 才離開被封鎖 credential。
        await { viewModel.selectedCredentialId.first { it != pending.credentialId } }
        assertFalse("已停用 pending 不得留在選取上", viewModel.state.value.selectedId == pending.id)
        assertFalse("active 列表不得含已停用 pending", connectionIds().contains(pending.id))
        assertTrue("舊連線仍可用", connectionIds().contains(old.id))
        assertFalse("停用必須 evict pending", pendingRecords().containsKey(pending.id))

        // Room 真實投影：active 排除停用列，management 保留停用列。
        val activeNow = activeRowsContaining(old.id)
        assertFalse("active 投影不得含停用列", activeNow.any { it.id == pending.id })
        val mgmtNow = managedRowsContaining(pending.id)
        val disabledRow = mgmtNow.first { it.id == pending.id }
        assertFalse("management 列必須標停用", disabledRow.credentialEnabled)

        // 之後任何單一投影到達，都不得讓 pending 復活為可用。
        driveHandoff(
            connection = activeNow.filter { it.id == old.id },
            management = listOf(mgmtNow.first { it.id == old.id }, disabledRow),
        )
        assertFalse("停用列不得重回選取", viewModel.state.value.selectedId == pending.id)
        assertFalse("context 不得重攜被封鎖 credential", viewModel.selectedCredentialId.value == pending.credentialId)
        assertFalse("active 列表不得復活停用列", connectionIds().contains(pending.id))
        assertTrue("舊連線仍可用", connectionIds().contains(old.id))
        val managedPending = viewModel.state.value.managedConnections.firstOrNull { it.id == pending.id }
        assertNotNull("management 必須保留停用列（可見但不可用）", managedPending)
        assertFalse("management 停用列不得顯示為可用", managedPending!!.enabled)
        assertFalse("pending 不得復活", pendingRecords().containsKey(pending.id))
        assertEquals("停用後動作維持 CANCELLED，不重啟", "CANCELLED", viewModel.providerModelAction.value.status)
        assertNoSecretInOwnRows()
    }

    // ---- harness：真 save → collector 隔離 → 反射 cache 控制 → 真 refresh ----

    private data class Saved(
        val id: String,
        val providerId: String,
        val accountId: String,
        val credentialId: String,
        val endpointId: String,
    )

    /** 走真 `saveConnection`（真 vault ＋真 Room ＋自動選取），等 collectors 投影進列表。 */
    private fun saveOldConnection(label: String): Saved {
        val name = "Pending Handoff $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, "fixture-model-${UUID.randomUUID()}", FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name } &&
                state.managedConnections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        val record = activeRowOf(item.id)
        return Saved(record.id, record.providerId, record.accountId, record.credentialId, record.endpointId)
    }

    /**
     * 走真 `saveConnection`，但 collectors 已隔離：只等 save 自己的選取訊號，
     * 不等列表（列表只由受控 `refresh()` 投影）。
     */
    private fun savePendingConnection(label: String, model: String, notId: String): Saved {
        val name = "Pending Handoff $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, model, FIXTURE_SECRET) }
        val selected = awaitUi { state ->
            !state.saving && state.selectedId != null && state.selectedId != notId
        }.selectedId!!
        val record = pendingRecords()[selected]
            ?: throw AssertionError("save 已選取 $selected，但 pending 缺對應記錄")
        providerIds += record.providerId
        credentialRefs += record.credentialId
        return Saved(record.id, record.providerId, record.accountId, record.credentialId, record.endpointId)
    }

    /**
     * 取消 VM init collectors（含 date ticker）：此後 Room re-emission 不再覆寫
     * cache，交接順序完全由本測試的反射注入決定。呼叫前 state 已投影舊列，
     * 證明 collectors 跑過至少一次；readyCache 必須已為 true，否則 fail-fast。
     */
    private fun isolateCollectors() {
        onMain {
            assertTrue("隔離前 VM 必須已 ready，否則 probe 起點不可靠", readReadyCache())
            val scopeJob = viewModel.viewModelScope.coroutineContext[Job]
                ?: throw AssertionError("viewModelScope 缺少 Job")
            scopeJob.children.forEach { it.cancel() }
        }
    }

    /** 在 Main 上以受控 cache 呼叫真正的 production `refresh()`；返回時投影已完成。 */
    private fun driveHandoff(connection: List<ConnectionRecord>, management: List<ConnectionRecord>) {
        onMain {
            vmField("connectionCache").set(viewModel, connection)
            vmField("managementCache").set(viewModel, management)
            AtlasViewModel::class.java.getDeclaredMethod("refresh").apply {
                isAccessible = true
            }.invoke(viewModel)
        }
    }

    private fun connectionIds(): List<String> = viewModel.state.value.connections.map { it.id }

    private fun managedIds(): List<String> = viewModel.state.value.managedConnections.map { it.id }

    private fun assertSingleOccurrence(ids: List<String>, id: String) {
        assertEquals("列表不得重複（id=$id）", 1, ids.count { it == id })
    }

    private fun activeRowOf(id: String): ConnectionRecord =
        activeRowsContaining(id).first { it.id == id }

    private fun managedRowOf(id: String): ConnectionRecord =
        managedRowsContaining(id).first { it.id == id }

    private fun activeRowsContaining(id: String): List<ConnectionRecord> = runBlocking {
        withTimeout(TIMEOUT_MS) {
            app.repository.connections.first { rows -> rows.any { it.id == id } }
        }
    }

    private fun managedRowsContaining(id: String): List<ConnectionRecord> = runBlocking {
        withTimeout(TIMEOUT_MS) {
            app.repository.managementConnections.first { rows -> rows.any { it.id == id } }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingRecords(): Map<String, ConnectionRecord> =
        (vmField("pendingById").get(viewModel) as Map<String, ConnectionRecord>).toMap()

    private fun readReadyCache(): Boolean = vmField("readyCache").get(viewModel) as Boolean

    private fun vmField(name: String) =
        AtlasViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    /**
     * 本次 probe 的 exact sample promote 斷言：final SUCCESS＋MANUAL_TEST＋完整 scope，
     * verification 引用本次 sample；target 只帶所選連線的 safe ref。
     */
    private fun assertProbePromoted(saved: Saved, knownSamples: Set<String>, model: String) {
        val probeIds = sampleIds(saved.providerId) - knownSamples
        assertEquals("單次 minimal probe 只可產生一個新樣本", 1, probeIds.size)
        val probeId = probeIds.single()
        val sample = sampleOf(probeId) ?: throw AssertionError("找不到本次 probe 樣本")
        assertEquals("MANUAL_TEST", sample["source"])
        assertEquals("SUCCESS", sample["outcome"])
        assertEquals(saved.accountId, sample["accountId"])
        assertEquals(saved.credentialId, sample["safeCredentialId"])
        assertEquals(saved.endpointId, sample["endpointId"])
        assertEquals(model, sample["modelId"])
        assertFalse("成功樣本必須有完成時間", sample["completedAt"].isNullOrBlank())
        val offering = offeringOf(saved.providerId, model)
            ?: throw AssertionError("找不到已驗證 offering")
        assertEquals("驗證成功必須 promote（hidden=0）", "0", offering["hidden"])
        val verification = offering["verificationEvidenceId"]
        assertTrue("promote 必須引用本次 sample", verification?.contains(probeId) == true)
        val target = transport.lastTarget()
        assertEquals(saved.providerId, target.providerId)
        assertEquals(saved.accountId, target.accountId)
        assertEquals(saved.credentialId, target.credentialId)
        assertEquals(saved.endpointId, target.endpointId)
        assertNoSecret(transport.promptText())
    }

    private fun sampleIds(providerId: String): Set<String> =
        sampleRows(providerId).mapNotNull { it["id"] }.toSet()

    private fun sampleOf(id: String): Map<String, String?>? =
        queryRows("SELECT * FROM samples WHERE id = ?", arrayOf(id)).singleOrNull()

    private fun sampleRows(providerId: String): List<Map<String, String?>> = queryRows(
        "SELECT * FROM samples WHERE providerId = ? ORDER BY startedAt ASC, id ASC",
        arrayOf(providerId),
    )

    private fun offeringOf(providerId: String, modelId: String): Map<String, String?>? = queryRows(
        "SELECT * FROM offerings WHERE providerId = ? AND modelId = ?",
        arrayOf(providerId, modelId),
    ).singleOrNull()

    private fun queryRows(sql: String, args: Array<String>): List<Map<String, String?>> {
        val out = mutableListOf<Map<String, String?>>()
        app.database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, String?>(cursor.columnCount)
                for (index in 0 until cursor.columnCount) {
                    row[cursor.getColumnName(index)] = cursor.getString(index)
                }
                out += row
            }
        }
        return out
    }

    /** raw key 不得跨出 vault 邊界：本檔 provider 的 telemetry／evidence／offering 皆掃描。 */
    private fun assertNoSecretInOwnRows() {
        for (providerId in providerIds) {
            val rows = queryRows("SELECT * FROM samples WHERE providerId = ?", arrayOf(providerId)) +
                queryRows("SELECT * FROM evidence WHERE providerId = ?", arrayOf(providerId)) +
                queryRows("SELECT * FROM offerings WHERE providerId = ?", arrayOf(providerId))
            for (row in rows) {
                for ((column, value) in row) {
                    assertFalse("telemetry 不得含測試憑證值（欄位 $column）", value?.contains(FIXTURE_SECRET) == true)
                }
            }
        }
    }

    private fun assertNoSecret(text: String) {
        assertFalse("UI 邊界不得含測試憑證值", text.contains(FIXTURE_SECRET))
    }

    /** 只刪本檔建立的 provider 及其子資料，順序滿足 RESTRICT 外鍵；其他資料不動。 */
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
            for (providerId in providerIds) {
                db.execSQL(
                    "DELETE FROM snapshot_facts WHERE scopeKey LIKE ?",
                    arrayOf("${providerId.length}:$providerId%"),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    /**
     * 等 VM 的 flow 收斂：viewModelScope 在 Main，以已排序的 Main barrier 推進 continuation；
     * 逾時即失敗（不用固定 sleep）。與 ProviderModelActionViewModelTest 同一慣例。
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

    private fun awaitAction(status: String, timeoutMillis: Long = TIMEOUT_MS) =
        await(timeoutMillis) {
            viewModel.providerModelAction.first {
                it.status == status || it.status in setOf("SUCCESS", "FAILURE", "CANCELLED")
            }
        }.also { actual ->
            assertEquals("Unexpected terminal action; status=${actual.status}", status, actual.status)
        }

    private fun awaitUi(predicate: (AtlasUiState) -> Boolean): AtlasUiState =
        await { viewModel.state.first(predicate) }

    /**
     * 測試專用 transport：只產生事件序列，不做任何網路 I/O，也不碰 vault。
     * 事件順序沿用 core engine 測試慣例（RequestSent → Headers → Delta → usage → Finished）。
     * `started`／`gate` 在建構時就緒：測試端 `awaitStarted`／`release` 永遠不越界；
     * 串流結束點由測試決定，以便在「請求進行中」做交接斷言。不用 sleep 或 busy wait。
     */
    private class GatedProbeTransport : InferenceTransport {
        private var started = CompletableDeferred<Unit>()
        private var gate = CompletableDeferred<Unit>()
        private val targets = CopyOnWriteArrayList<InferenceTarget>()
        private val prompts = CopyOnWriteArrayList<List<PromptMessage>>()

        val callCount: Int get() = targets.size

        fun reset() {
            targets.clear()
            prompts.clear()
            started = CompletableDeferred()
            gate = CompletableDeferred()
        }

        fun lastTarget(): InferenceTarget = targets.last()

        fun promptText(): String = prompts.joinToString(separator = "\n") { list -> list.joinToString("\n") }

        suspend fun awaitStarted() {
            started.await()
        }

        fun release() {
            gate.complete(Unit)
        }

        override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
            targets += target
            prompts += messages
            emit(StreamEvent.RequestSent(epochMillis = System.currentTimeMillis(), monotonicMillis = monotonic()))
            emit(
                StreamEvent.Headers(
                    status = 200,
                    rateLimits = mapOf("x-ratelimit-remaining-requests" to "9"),
                    observedMonotonicMs = monotonic(),
                ),
            )
            emit(StreamEvent.Delta("OK", observedMonotonicMs = monotonic()))
            emit(StreamEvent.TokenUsage(Usage(inputTokens = 3L, outputTokens = 2L, totalTokens = 5L)))
            started.complete(Unit)
            gate.await()
            emit(StreamEvent.Finished)
        }

        private fun monotonic(): Long = System.nanoTime() / 1_000_000
    }

    private class HandoffViewModelFactory(
        private val app: AtlasApplication,
        private val transport: InferenceTransport,
    ) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            // 只在此處注入測試用 engine；正式路徑仍走 app.engine。
            val engine = InferenceEngine(transport, app.repository)
            return AtlasViewModel(app, engine) as T
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://pending-handoff.fixture.invalid/v1"
        const val FIXTURE_SECRET = "pending-handoff-fixture-secret-never-persisted"
        const val MODEL_MGMT = "pending-handoff-model-mgmt"
        const val MODEL_ACTIVE = "pending-handoff-model-active"
        const val MODEL_BLOCKED = "pending-handoff-model-blocked"
    }
}
