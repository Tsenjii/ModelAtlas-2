package dev.modelatlas.app

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.app.ui.ModelRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * S1 (PK-01): disable-failure must not run [blockCredentialLocally] before the write.
 *
 * Spec: /tmp/opencode/ai11-spec-block-rollback.md section 6 (T1..T5). Targets the
 * `credentialEnableTestWriter` constructor seam landed by the production owner in the
 * same slice — this file does NOT compile until that seam lands (same-slice dependency).
 *
 * Harness reuse: [givenConnection] / [awaitSettled] / [awaitUi] / fixture teardown copied
 * from ProviderManagementViewModelTest; pending-bridge save + [isolateCollectors] pattern
 * from PendingConnectionHandoffTest (saveConnection -> pendingById, collectors cancelled
 * so Room emission cannot pre-empt the bridge).
 *
 * No real inference: T1/T2 never execute send/provider-action (that would hit the network);
 * "not rejected" is proven deterministically via the [blockedIds] reflection proxy plus the
 * enabled projections. T5 exercises only the synchronous saving-gate rejections, which
 * return before any transport/engine is touched.
 */
@RunWith(AndroidJUnit4::class)
class CredentialRollbackFlowTest {
    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) viewModelStore.clear()
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ---- T1: disable failure keeps selection, chat, enabled state; send path not poisoned ----

    @Test
    fun disableFailureKeepsSelectionChatAndEnabledState() {
        installVm(writer = failingDisableWriter())
        val target = givenConnection("rollback-t1-target")
        val kept = givenConnection("rollback-t1-kept")
        awaitSettled(target.connectionId, kept.connectionId)

        viewModel.selectConnection(target.connectionId)
        awaitUi { it.selectedId == target.connectionId }
        val seeded = listOf(
            ChatMessage(id = UUID.randomUUID().toString(), role = "user", text = "rollback-t1-seed"),
        )
        seedMessages(seeded)
        val before = viewModel.state.value.messages
        assertEquals(seeded, before)

        viewModel.setCredentialEnabled(target.connectionId, false)

        val settled = awaitUi { !it.saving }
        assertEquals(app.getString(R.string.error_credential_update), settled.error)
        assertEquals("failed disable must not deselect", target.connectionId, settled.selectedId)
        assertEquals("failed disable must not wipe chat", before, settled.messages)
        assertTrue(
            "failed disable must stay usable in active list",
            settled.connections.any { it.id == target.connectionId && it.enabled },
        )
        val managed = settled.managedConnections.first { it.id == target.connectionId }
        assertTrue("failed disable must stay enabled in management", managed.enabled)
        assertEquals(target.credentialId, managed.credentialId)
        assertEquals(
            "Room row must stay enabled",
            true,
            runBlocking { app.database.credentialDao().getById(target.credentialId)?.enabled },
        )
        assertTrue("vault secret must be kept", app.vault.has(target.credentialId))
        assertFalse(
            "blocked set must not be polluted (send/provider gate proxy: no network executed)",
            blockedIds().contains(target.credentialId),
        )
        val rendered = settled.connections.joinToString("\n") { it.toString() } + "\n" +
            settled.managedConnections.joinToString("\n") { it.toString() } + "\n" + settled.error
        assertFalse("UI state must never carry the fixture secret", rendered.contains(SECRET))
    }

    // ---- T2: disable failure keeps the pending bridge row ----

    @Test
    fun disableFailureKeepsPendingBridgeRow() {
        installVm(writer = failingDisableWriter())
        val old = givenConnection("rollback-t2-old")
        awaitSettled(old.connectionId)
        isolateCollectors()

        val name = "Rollback Fixture t2-pending ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, "fixture-model-rollback-t2", SECRET) }
        val pendingId = awaitUi { state ->
            !state.saving && state.selectedId != null && state.selectedId != old.connectionId
        }.selectedId!!
        val pending = pendingRecords()[pendingId]
            ?: throw AssertionError("save selected $pendingId but pendingById lacks the record")
        providerIds += pending.providerId
        credentialRefs += pending.credentialId

        // Pre-state: production refresh() bridges the pending row into both lists.
        driveRefresh()
        assertTrue("pending row must bridge into active list", connectionIds().contains(pendingId))
        assertTrue("pending row must bridge into managed list", managedIds().contains(pendingId))
        assertEquals(pendingId, viewModel.state.value.selectedId)

        viewModel.setCredentialEnabled(pendingId, false)

        val settled = awaitUi { !it.saving }
        assertEquals(app.getString(R.string.error_credential_update), settled.error)
        assertEquals("failed disable must not drop the pending selection", pendingId, settled.selectedId)
        assertTrue("pending row must survive in active list", connectionIds().contains(pendingId))
        assertTrue("pending row must survive in managed list", managedIds().contains(pendingId))
        assertTrue("pendingById must keep the record", pendingRecords().containsKey(pendingId))
        assertFalse("blocked set must not be polluted", blockedIds().contains(pending.credentialId))
    }

    // ---- T3: disable success semantics unchanged (regression guard) ----

    @Test
    fun disableSuccessUnchanged() {
        installVm()
        val target = givenConnection("rollback-t3-target")
        awaitSettled(target.connectionId)

        viewModel.selectConnection(target.connectionId)
        awaitUi { it.selectedId == target.connectionId }

        viewModel.setCredentialEnabled(target.connectionId, false)

        val settled = awaitUi { state ->
            !state.saving && state.connections.none { it.id == target.connectionId }
        }
        assertNull("disabling must not fail: ${settled.error}", settled.error)
        assertFalse(
            "successful disable still hides from management-enabled projection",
            settled.managedConnections.first { it.id == target.connectionId }.enabled,
        )
        assertNull("successful disable still clears selection", settled.selectedId)
        assertTrue("successful disable still clears chat", settled.messages.isEmpty())
        assertTrue("disabling must keep the vault secret", app.vault.has(target.credentialId))
        assertEquals(
            "Room row must be disabled",
            false,
            runBlocking { app.database.credentialDao().getById(target.credentialId)?.enabled },
        )
    }

    // ---- T4: enable path unchanged (success restores, failure stays disabled) ----

    @Test
    fun enablePathUnchanged() {
        var failEnable = false
        installVm(
            writer = { credId, enabled ->
                if (enabled && failEnable) throw IllegalStateException("rollback-test-enable-boom")
                else app.credentialLifecycle.setEnabled(credId, enabled)
            },
        )
        val target = givenConnection("rollback-t4-target")
        awaitSettled(target.connectionId)

        viewModel.setCredentialEnabled(target.connectionId, false)
        awaitUi { state ->
            !state.saving && state.connections.none { it.id == target.connectionId }
        }

        viewModel.setCredentialEnabled(target.connectionId, true)
        val restored = awaitUi { state ->
            !state.saving && state.connections.any { it.id == target.connectionId && it.enabled } &&
                state.managedConnections.firstOrNull { it.id == target.connectionId }?.enabled == true
        }
        assertNull("re-enabling must not fail: ${restored.error}", restored.error)
        assertEquals(
            true,
            runBlocking { app.database.credentialDao().getById(target.credentialId)?.enabled },
        )

        viewModel.setCredentialEnabled(target.connectionId, false)
        awaitUi { state ->
            !state.saving && state.connections.none { it.id == target.connectionId }
        }

        failEnable = true
        viewModel.setCredentialEnabled(target.connectionId, true)
        val failed = awaitUi { !it.saving }
        assertEquals(app.getString(R.string.error_credential_update), failed.error)
        assertTrue(
            "failed enable must stay out of the active list",
            failed.connections.none { it.id == target.connectionId },
        )
        assertFalse(
            "failed enable must stay disabled in management",
            failed.managedConnections.first { it.id == target.connectionId }.enabled,
        )
        assertEquals(
            "Room row must stay disabled",
            false,
            runBlocking { app.database.credentialDao().getById(target.credentialId)?.enabled },
        )
        assertTrue(
            "blocked set must be unchanged (still blocked)",
            blockedIds().contains(target.credentialId),
        )
    }

    // ---- T5: saving gate covers the write window (block moved later stays safe) ----

    @Test
    fun noInferenceDuringWindow() {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        installVm(
            writer = { credId, enabled ->
                if (!enabled) {
                    entered.complete(Unit)
                    gate.await()
                } else {
                    app.credentialLifecycle.setEnabled(credId, enabled)
                }
            },
        )
        val target = givenConnection("rollback-t5-target")
        val fallback = givenConnection("rollback-t5-fallback")
        awaitSettled(target.connectionId, fallback.connectionId)
        viewModel.selectConnection(target.connectionId)
        awaitUi { it.selectedId == target.connectionId }

        viewModel.setCredentialEnabled(target.connectionId, false)
        runBlocking { withTimeout(TIMEOUT_MS) { entered.await() } }
        assertTrue("write window must hold saving", viewModel.state.value.saving)

        val preparing = app.getString(R.string.error_preparing)
        viewModel.send("rollback-t5-window-probe")
        assertEquals("send during the window must be rejected", preparing, viewModel.state.value.error)
        viewModel.testProviderModel(target.providerId, "rollback-t5-probe-model")
        val action = viewModel.providerModelAction.value
        assertEquals("provider action during the window must be rejected", "FAILURE", action.status)
        assertEquals(preparing, action.message)
        viewModel.requestCompareLive(
            ModelRow(providerId = target.providerId, providerModelId = "rollback-t5", canonicalId = "rollback-t5"),
        )
        assertEquals("compare during the window must be rejected", "BUSY", viewModel.compareError.value)

        gate.complete(Unit)
        val settled = awaitUi { state ->
            !state.saving && state.connections.none { it.id == target.connectionId }
        }
        // window 期間被擋的 send/provider action 會把 error_preparing 寫進
        // state.error（gate 生效的證據）——settled 時它仍在是正確行為。只要求
        // 沒有「disable 失敗」的錯誤（error_credential_update）出現。
        val credentialUpdateError = app.getString(R.string.error_credential_update)
        assertTrue(
            "write must succeed; only the gate rejections' preparing error may remain, was ${settled.error}",
            settled.error == null || settled.error == app.getString(R.string.error_preparing),
        )
        assertFalse(
            settled.managedConnections.first { it.id == target.connectionId }.enabled,
        )
        assertFalse("must not surface the credential-update error", settled.error == credentialUpdateError)
        // D1（S1 review）：refresh() 的 auto-select 取 connections.first()，投影查詢無
        // ORDER BY——其他 androidTest 留下的連線可能搶首位。只斷言「仍指向某顆啟用中
        // 連線」而非特定 fallback，避免跨類別 DB 污染造成偽失敗。
        val selected = settled.connections.firstOrNull { it.id == settled.selectedId }
        assertNotNull("auto-select must point at an enabled connection", selected)
    }

    // ===== harness (ProviderManagementViewModelTest 慣例 + PendingConnectionHandoffTest pending 模式) =====

    private fun installVm(writer: (suspend (String, Boolean) -> Unit)? = null) {
        viewModelStore = ViewModelStore()
        viewModel = ViewModelProvider(viewModelStore, RollbackViewModelFactory(app, writer))
            .get(AtlasViewModel::class.java)
    }

    private fun failingDisableWriter(): suspend (String, Boolean) -> Unit = { credId, enabled ->
        if (!enabled) throw IllegalStateException("rollback-test-disable-boom")
        else app.credentialLifecycle.setEnabled(credId, enabled)
    }

    private fun connectionIds(): List<String> = viewModel.state.value.connections.map { it.id }

    private fun managedIds(): List<String> = viewModel.state.value.managedConnections.map { it.id }

    @Suppress("UNCHECKED_CAST")
    private fun pendingRecords(): Map<String, ConnectionRecord> =
        (vmField("pendingById").get(viewModel) as Map<String, ConnectionRecord>).toMap()

    @Suppress("UNCHECKED_CAST")
    private fun blockedIds(): Set<String> =
        (vmField("blockedCredentialIds").get(viewModel) as MutableSet<String>).toSet()

    private fun vmField(name: String) =
        AtlasViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun seedMessages(msgs: List<ChatMessage>) {
        @Suppress("UNCHECKED_CAST")
        val state = vmField("_state").get(viewModel) as MutableStateFlow<AtlasUiState>
        state.update { it.copy(messages = msgs) }
    }

    /**
     * 取消 VM init collectors（含 date ticker）：此後 Room re-emission 不再覆寫 cache，
     * 交接順序完全受控（PendingConnectionHandoffTest.isolateCollectors 同一慣例）。
     */
    private fun isolateCollectors() {
        onMain {
            val scopeJob = viewModel.viewModelScope.coroutineContext[Job]
                ?: throw AssertionError("viewModelScope 缺少 Job")
            scopeJob.children.forEach { it.cancel() }
        }
    }

    /** 在 Main 上呼叫真正的 production `refresh()`；返回時投影已完成。 */
    private fun driveRefresh() {
        onMain {
            AtlasViewModel::class.java.getDeclaredMethod("refresh").apply {
                isAccessible = true
            }.invoke(viewModel)
        }
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    /**
     * 等 Room 流程與 viewModelScope 都收斂：以 withTimeout + state.first 等待，不用固定 sleep。
     * 測試執行緒不是主執行緒，所以一併用 waitForIdleSync 讓 Main 派發的 ViewModel 協程有機會推進。
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

    private fun newId(): String = UUID.randomUUID().toString()

    private fun newCredentialRef(): String = newId().also {
        credentialRefs += it
        app.vault.store(it, VAULT_ORIGIN, SECRET)
    }

    /** 建立一整組 provider/account/credential/endpoint/offering/availability，全部是本檔自己的 UUID。 */
    private fun givenConnection(
        label: String,
        checkinUrl: String? = null,
        providerId: String = newId().also { providerIds += it },
    ): Fixture {
        val credentialId = newCredentialRef()
        val record = ConnectionRecord(
            id = newId(),
            providerId = providerId,
            accountId = newId(),
            credentialId = credentialId,
            endpointId = newId(),
            name = "Rollback Fixture $label",
            baseUrl = BASE_URL,
            endpointUrl = ENDPOINT_URL,
            modelId = "fixture-model-$label",
            checkinUrl = checkinUrl,
            requiresDailyCheckin = checkinUrl != null,
        )
        runBlocking { app.repository.addConnection(record) }
        return Fixture(
            providerId = record.providerId,
            accountId = record.accountId,
            credentialId = record.credentialId,
            endpointId = record.endpointId,
            offeringId = record.offeringId,
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
            // committer 產物：config / commit 去重鍵 / snapshot（含 workflowId=provider 關聯以外的孤兒不碰）。
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($providerList)", args)
            db.execSQL(
                "DELETE FROM onboarding_commits WHERE providerId IN ($providerList)",
                args,
            )
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
        val endpointId: String,
        val offeringId: String,
        val connectionId: String,
    )

    private class RollbackViewModelFactory(
        private val app: AtlasApplication,
        private val writer: (suspend (String, Boolean) -> Unit)?,
    ) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            // S1 同片 seam：production owner 並行落地 `credentialEnableTestWriter` 尾參；
            // 未落地前本檔不編譯（同片依賴，非本檔問題）。
            return AtlasViewModel(app, credentialEnableTestWriter = writer) as T
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val HOST = "rollback-cred.fixture.invalid"
        const val BASE_URL = "https://$HOST/v1"
        const val ENDPOINT_URL = "https://$HOST/v1/chat/completions"
        const val VAULT_ORIGIN = "https://$HOST:443"
        const val SECRET = "rollback-cred-fixture-not-a-real-secret"
    }
}
