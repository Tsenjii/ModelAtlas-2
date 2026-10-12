package dev.modelatlas.app

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.RoomCommitter
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.EndpointRef
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.ReviewApproval
import dev.modelatlas.core.onboarding.SafeCredentialRef
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/**
 * Provider 管理面的整合邊界：真 AtlasApplication + Room + Keystore vault + AtlasViewModel。
 *
 * 覆蓋三件事：同日同 provider 的簽到只標指定帳號；停用某 credential 後 active 排除但 managed 仍可見；
 * 同一 credential 掛兩個 offering 時，停用與刪除都必須把兩邊一起排除。
 *
 * 不做真實 HTTP（不呼叫 send）。fixture 以 UUID 隔離，secret 為固定測試字串；
 * teardown 只刪除本檔自己建立的 provider 資料與 vault refs。
 */
@RunWith(AndroidJUnit4::class)
class ProviderManagementViewModelTest {
    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        viewModelStore = ViewModelStore()
        viewModel = ViewModelProvider(viewModelStore, AtlasViewModelFactory(app)).get(AtlasViewModel::class.java)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    @Test
    fun checkinMarksOnlyTheTargetedAccountOfTheSameProviderOnTheSameDay() {
        val first = givenConnection("checkin-first", checkinUrl = CHECKIN_URL)
        val second = givenAccountConnection("checkin-second", first.providerId)
        awaitSettled(first.connectionId, second.connectionId)

        val before = viewModel.state.value
        assertTrue(before.managedConnections.first { it.id == first.connectionId }.checkinPending)
        assertTrue(before.managedConnections.first { it.id == second.connectionId }.checkinPending)

        viewModel.completeCheckin(first.connectionId)

        val settled = awaitUi { state ->
            val target = state.managedConnections.firstOrNull { it.id == first.connectionId }
            val sibling = state.managedConnections.firstOrNull { it.id == second.connectionId }
            !state.saving && target != null && sibling != null && !target.checkinPending && sibling.checkinPending
        }
        assertNull("check-in must not fail: ${settled.error}", settled.error)
        assertTrue(
            "the sibling account of the same provider must stay pending",
            settled.managedConnections.first { it.id == second.connectionId }.checkinPending,
        )

        val today = LocalDate.now().toString()
        val rows = runBlocking { app.database.checkinDao().listByProviderDate(first.providerId, today) }
        assertEquals("same provider and day must record only the targeted account", 1, rows.size)
        assertEquals(first.accountId, rows.single().accountId)
        assertEquals(first.providerId, rows.single().providerId)
    }

    @Test
    fun disablingOneCredentialExcludesItFromActiveButKeepsItVisibleInManagement() {
        val kept = givenConnection("disable-kept")
        val target = givenConnection("disable-target")
        awaitSettled(kept.connectionId, target.connectionId)

        viewModel.setCredentialEnabled(target.connectionId, false)

        val settled = awaitUi { state ->
            !state.saving &&
                state.connections.none { it.id == target.connectionId } &&
                state.connections.any { it.id == kept.connectionId && it.enabled } &&
                state.managedConnections.firstOrNull { it.id == target.connectionId }?.enabled == false &&
                state.managedConnections.firstOrNull { it.id == kept.connectionId }?.enabled == true
        }
        assertNull("disabling must not fail: ${settled.error}", settled.error)

        val managed = settled.managedConnections.first { it.id == target.connectionId }
        assertEquals(target.credentialId, managed.credentialId)
        assertEquals(target.accountId, managed.accountId)
        assertTrue("disabling must keep the vault secret so it can be re-enabled", app.vault.has(target.credentialId))
        val rendered = settled.connections.joinToString("\n") { it.toString() } + "\n" +
            settled.managedConnections.joinToString("\n") { it.toString() } + "\n" + settled.error
        assertFalse("UI state must never carry the fixture secret", rendered.contains(SECRET))
    }

    @Test
    fun oneCredentialBehindTwoOfferingsIsExcludedOnBothSidesAfterDisableAndDelete() {
        val first = givenConnection("shared-offering-a")
        val second = givenSecondOffering("shared-offering-b", first)
        assertEquals("both offerings must share one credential", first.credentialId, second.credentialId)
        assertEquals("both offerings must share one credential", first.credentialId, second.credentialId)
        assertNotEquals("the two offerings must be distinct rows", first.offeringId, second.offeringId)
        val ids = setOf(first.connectionId, second.connectionId)
        awaitSettled(*ids.toTypedArray())

        viewModel.setCredentialEnabled(first.connectionId, false)

        val disabled = awaitUi { state ->
            !state.saving && state.connections.none { it.id in ids } &&
                state.managedConnections.count { it.id in ids } == 2 &&
                state.managedConnections.filter { it.id in ids }.none { it.enabled }
        }
        assertNull("disabling must not fail: ${disabled.error}", disabled.error)
        assertTrue("disabling must keep the vault secret", app.vault.has(first.credentialId))

        viewModel.setCredentialEnabled(first.connectionId, true)

        val restored = awaitUi { state ->
            !state.saving && state.connections.count { it.id in ids } == 2 &&
                state.managedConnections.count { it.id in ids } == 2 &&
                state.managedConnections.filter { it.id in ids }.all { it.enabled }
        }
        assertNull("re-enabling must not fail: ${restored.error}", restored.error)

        viewModel.deleteCredential(first.connectionId)

        val deleted = awaitUi { state ->
            !state.saving && state.connections.none { it.id in ids } && state.managedConnections.none { it.id in ids }
        }
        assertNull("deleting must not fail: ${deleted.error}", deleted.error)
        assertFalse("deleting must drop the vault secret", app.vault.has(first.credentialId))
        assertEquals(0, runBlocking { app.database.availabilityDao().byCredential(first.credentialId) }.size)
        assertNull(runBlocking { app.database.credentialDao().getById(first.credentialId) })
    }

    // ===== P1：sync 按鈕只顯示於支援 NewAPI sync 的 provider，狀態為 provider 級 =====

    @Test
    fun supportsSync_onlyPatAccountProviderIsSyncCapable() {
        val bearer = givenCommittedProvider(
            "p1-bearer",
            credentials = listOf(SafeCredentialRef(newId(), "推理憑證", CredentialRole.INFERENCE)),
            authMode = AuthMode.BEARER,
        )
        val pat = givenCommittedProvider(
            "p1-pat",
            credentials = listOf(
                SafeCredentialRef(newId(), "帳號PAT", CredentialRole.ACCOUNT),
                SafeCredentialRef(newId(), "推理憑證", CredentialRole.INFERENCE),
            ),
            authMode = AuthMode.PAT_ACCOUNT,
        )
        val legacy = givenConnection("p1-legacy")
        awaitSettled(bearer.connectionId, pat.connectionId, legacy.connectionId)

        // supportsSync 由 provider_config 回填（非同步），需等待 PAT 側為 true 且其餘維持 false。
        val settled = awaitUi { state ->
            val byConn = { id: String -> state.managedConnections.firstOrNull { it.id == id } }
            val b = byConn(bearer.connectionId)
            val p = byConn(pat.connectionId)
            val l = byConn(legacy.connectionId)
            b != null && p != null && l != null && p.supportsSync && !b.supportsSync && !l.supportsSync
        }
        assertNull("supportsSync mapping must not fail: ${settled.error}", settled.error)
        assertTrue(settled.managedConnections.first { it.id == pat.connectionId }.supportsSync)
        assertFalse(settled.managedConnections.first { it.id == bearer.connectionId }.supportsSync)
        assertFalse(settled.managedConnections.first { it.id == legacy.connectionId }.supportsSync)
        // provider 級 syncing 初始為空集合（非全域 Boolean）。
        assertTrue(settled.syncingProviders.isEmpty())
    }

    @Test
    fun syncNewApi_blankProviderId_failsWithoutProviderState() {
        viewModel.syncNewApi("")
        val settled = awaitUi { state ->
            state.error != null && state.syncingProviders.isEmpty()
        }
        assertEquals(app.getString(R.string.error_sync_failed), settled.error)
    }

    @Test
    fun syncNewApi_bearerWithoutPat_failsUnavailableAndClearsProviderState() {
        val bearer = givenCommittedProvider(
            "p1-bearer-nosync",
            credentials = listOf(SafeCredentialRef(newId(), "推理憑證", CredentialRole.INFERENCE)),
            authMode = AuthMode.BEARER,
        )
        awaitSettled(bearer.connectionId)
        // BEARER 無 PAT：syncNewApi 在首次網路請求前拋 IllegalArgumentException，不燒額度。
        viewModel.syncNewApi(bearer.providerId)
        val settled = awaitUi { state ->
            state.error != null && state.syncingProviders.isEmpty() &&
                state.managedConnections.any { it.id == bearer.connectionId }
        }
        assertEquals(app.getString(R.string.error_sync_unavailable), settled.error)
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
            name = "Management Fixture $label",
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

    /** 同一 provider 的第二個帳號：addConnection 對重複 provider 會 ABORT，所以走 addAccount + attachCredential。 */
    private fun givenAccountConnection(label: String, providerId: String): Fixture {
        val accountId = newId()
        val credentialId = newCredentialRef()
        val endpointId = newId()
        val offeringId = newId()
        val connectionId = newId()
        runBlocking {
            app.repository.addAccount(providerId, accountId)
            app.database.endpointDao().insert(
                EndpointEntity(endpointId, providerId, BASE_URL, ENDPOINT_URL, "CHAT_COMPLETIONS"),
            )
            app.database.offeringDao().insert(
                OfferingEntity(offeringId, providerId, endpointId, "fixture-model-$label", true, "MANUAL"),
            )
            app.repository.attachCredential(offeringId, accountId, credentialId, CREDENTIAL_LABEL, connectionId)
        }
        return Fixture(providerId, accountId, credentialId, endpointId, offeringId, connectionId)
    }

    /** 同一 credential 掛第二個 offering：offering 綁 provider/endpoint/model，key 綁定走 availability。 */
    private fun givenSecondOffering(label: String, base: Fixture): Fixture {
        val offeringId = newId()
        val connectionId = newId()
        runBlocking {
            app.database.offeringDao().insert(
                OfferingEntity(offeringId, base.providerId, base.endpointId, "fixture-model-$label", true, "MANUAL"),
            )
            app.repository.attachCredential(
                offeringId,
                base.accountId,
                base.credentialId,
                CREDENTIAL_LABEL,
                connectionId,
            )
        }
        return Fixture(
            providerId = base.providerId,
            accountId = base.accountId,
            credentialId = base.credentialId,
            endpointId = base.endpointId,
            offeringId = offeringId,
            connectionId = connectionId,
        )
    }

    /** 經 RoomCommitter 建立含 provider_config 的 provider（供 supportsSync 判定）；vault 不碰真值，只用 ref 佔位。 */
    private fun givenCommittedProvider(
        label: String,
        credentials: List<SafeCredentialRef>,
        authMode: AuthMode,
    ): Fixture {
        val workflowId = newId()
        val snapshot = OnboardingSnapshot(
            workflowId = workflowId,
            flowType = OnboardingFlowType.ADVANCED,
            providerName = "Management Fixture $label",
            baseUrl = BASE_URL,
            credentials = credentials,
            safeCredentialIds = credentials.map { it.safeCredentialId },
            customAuthFields = emptyList(),
            authModeOverride = authMode,
            endpointProfiles = listOf(
                EndpointRef(EndpointType.CHAT_COMPLETIONS, ENDPOINT_URL, "CHAT_COMPLETIONS", enabled = true),
            ),
            manualModelIds = listOf("fixture-model-$label"),
            reviewApproval = ReviewApproval(true, 1_700_000_000_000L, "tester"),
            createdAtMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_000_001L,
        )
        val receipt = runBlocking { RoomCommitter(app.database).commit(snapshot) }
        val providerId = requireNotNull(receipt.providerId)
        providerIds += providerId
        val rows = runBlocking {
            app.repository.managementConnections.first { list ->
                list.any { it.providerId == providerId }
            }.filter { it.providerId == providerId }
        }
        val first = rows.first()
        return Fixture(
            providerId = providerId,
            accountId = first.accountId,
            credentialId = first.credentialId,
            endpointId = first.endpointId,
            offeringId = first.offeringId,
            connectionId = first.id,
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

    private class AtlasViewModelFactory(private val app: AtlasApplication) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AtlasViewModel(app) as T
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val HOST = "provider-mgmt.fixture.invalid"
        const val BASE_URL = "https://$HOST/v1"
        const val ENDPOINT_URL = "https://$HOST/v1/chat/completions"
        const val CHECKIN_URL = "https://$HOST/daily-checkin"
        const val VAULT_ORIGIN = "https://$HOST:443"
        const val CREDENTIAL_LABEL = "主要憑證"
        const val SECRET = "provider-mgmt-fixture-not-a-real-secret"
    }
}
