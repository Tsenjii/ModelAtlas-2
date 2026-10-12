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
import dev.modelatlas.app.data.CredentialAvailabilityEntity
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * CR-003 B3：真 VM + Room 的 availability exact scope identity 投影回歸。
 *
 * 本檔只走真實 production flow：ConnectionRecord → repository.addConnection →
 * AtlasViewModel（selected context）→ Room offerings／credential_availability →
 * allModelRows／myModelRows。不 fake mapper、不開網、不送 inference，
 * teardown 只刪本檔自己建立的 provider 子資料與 vault refs。
 *
 * 語意契約（production 已有 VM 最少結構欄位接線：availability 經 matchedOffering
 * 取得 concrete endpointId／operation）：
 * - 選中連線的 account／credential／group 精確對應 persisted availability 時，
 *   該列 selectedKeyUsable 必為 true，且 price/quota 投影自 persisted 值；
 *   否則（group 不符、key 不符、endpoint/op 不符）絕不借列，全 UNKNOWN／false。
 * - 同 offering／account／credential 的不同 group 列在 v14 可共存；切 group 只
 *   投影該 group 自己的 state，錯 group 不得借用。
 */
@RunWith(AndroidJUnit4::class)
class ModelAvailabilityScopeFlowTest {
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
    fun selectedConnectionWithConcreteEndpointAndKeyProjectsUsableState() {
        val fixture = givenConnection(
            label = "scope-valid",
            priceState = "FREE_NOW",
            quotaState = "QUOTA_AVAILABLE",
        )
        awaitSettled(fixture.connectionId)

        onMain { viewModel.selectConnection(fixture.connectionId) }
        awaitUi { state -> state.selectedId == fixture.connectionId }
        val context = awaitContext { it.credentialId == fixture.credentialId && it.group == null }
        assertEquals(fixture.accountId, context.accountId)
        assertNull("連線層沒有 group 維度：未指定就是 null", context.group)

        onMain { viewModel.toggleMyModel(fixture.providerId, fixture.modelId) }

        val rows = awaitRows { result ->
            result.any { it.providerId == fixture.providerId && it.selectedKeyUsable }
        }
        val row = rows.first { it.providerId == fixture.providerId }
        assertEquals(fixture.modelId, row.providerModelId)
        assertEquals(PriceState.FREE_NOW, row.priceState)
        assertEquals(QuotaState.QUOTA_AVAILABLE, row.quotaState)
        assertTrue("concrete CHAT_COMPLETIONS endpoint + 選中 key 必須 usable", row.selectedKeyUsable)
        assertNull(row.group)

        val myRows = awaitMyRows { result ->
            result.any { it.providerId == fixture.providerId && it.selectedKeyUsable }
        }
        val myRow = myRows.first { it.providerId == fixture.providerId }
        assertEquals(fixture.modelId, myRow.providerModelId)
        assertEquals(PriceState.FREE_NOW, myRow.priceState)
        assertTrue(myRow.selectedKeyUsable)

        // 模型與 availability 必須仍持久化（enrichment 不刪列）；且零 inference 燒錄。
        runBlocking {
            assertNotNull(app.database.offeringDao().getById(fixture.connectionId))
            val availabilityRows = app.database.availabilityDao().byCredential(fixture.credentialId)
            assertTrue(availabilityRows.isNotEmpty())
            assertEquals(
                0L,
                countRows("SELECT COUNT(*) FROM samples WHERE providerId = ?", fixture.providerId),
            )
        }
    }

    @Test
    fun sameOfferingAccountAndKeyTwoGroupsProjectOwnStateWithoutBorrow() {
        val fixture = givenConnection(label = "scope-groups", priceState = "UNKNOWN", quotaState = "UNKNOWN")
        awaitSettled(fixture.connectionId)

        // 同 offering／account／credential、不同 group 的兩列：v14 應可共存。
        runBlocking {
            app.database.availabilityDao().insert(
                CredentialAvailabilityEntity(
                    id = newId(),
                    offeringId = fixture.connectionId,
                    accountId = fixture.accountId,
                    credentialId = fixture.credentialId,
                    priceState = "FREE_NOW",
                    quotaState = "QUOTA_AVAILABLE",
                    group = GROUP_A,
                ),
            )
            app.database.availabilityDao().insert(
                CredentialAvailabilityEntity(
                    id = newId(),
                    offeringId = fixture.connectionId,
                    accountId = fixture.accountId,
                    credentialId = fixture.credentialId,
                    priceState = "PAID",
                    quotaState = "QUOTA_EXHAUSTED",
                    group = GROUP_B,
                ),
            )
            val rows = app.database.availabilityDao().byCredential(fixture.credentialId)
            assertTrue("groupA+groupB 同 o/a/key 列應共存", rows.any { it.group == GROUP_A } && rows.any { it.group == GROUP_B })
        }

        onMain { viewModel.selectConnection(fixture.connectionId) }
        awaitUi { state -> state.selectedId == fixture.connectionId }

        onMain { viewModel.selectModelKey(fixture.accountId, fixture.credentialId, GROUP_A) }
        awaitContext { it.group == GROUP_A && it.credentialId == fixture.credentialId }
        val rowA = awaitRows { result ->
            result.any { it.providerId == fixture.providerId && it.priceState == PriceState.FREE_NOW }
        }.first { it.providerId == fixture.providerId }
        assertEquals(PriceState.FREE_NOW, rowA.priceState)
        assertEquals(QuotaState.QUOTA_AVAILABLE, rowA.quotaState)
        assertTrue(rowA.selectedKeyUsable)
        assertEquals(GROUP_A, rowA.group)

        onMain { viewModel.selectModelKey(fixture.accountId, fixture.credentialId, GROUP_B) }
        awaitContext { it.group == GROUP_B && it.credentialId == fixture.credentialId }
        val rowB = awaitRows { result ->
            result.any { it.providerId == fixture.providerId && it.priceState == PriceState.PAID }
        }.first { it.providerId == fixture.providerId }
        assertEquals(PriceState.PAID, rowB.priceState)
        assertEquals(QuotaState.QUOTA_EXHAUSTED, rowB.quotaState)
        assertTrue(rowB.selectedKeyUsable)
        assertEquals(GROUP_B, rowB.group)

        // 錯 group（無此列）不得借用 groupA/B 的 state 或 usable。
        onMain { viewModel.selectModelKey(fixture.accountId, fixture.credentialId, GROUP_C) }
        awaitContext { it.group == GROUP_C && it.credentialId == fixture.credentialId }
        val rowC = awaitRows { result ->
            result.any { it.providerId == fixture.providerId && !it.selectedKeyUsable }
        }.first { it.providerId == fixture.providerId }
        assertFalse(rowC.selectedKeyUsable)
        assertEquals(PriceState.UNKNOWN, rowC.priceState)
        assertEquals(QuotaState.UNKNOWN, rowC.quotaState)
        assertEquals(GROUP_C, rowC.group)

        runBlocking {
            assertNotNull(app.database.offeringDao().getById(fixture.connectionId))
            assertEquals(
                0L,
                countRows("SELECT COUNT(*) FROM samples WHERE providerId = ?", fixture.providerId),
            )
        }
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
                runCatching { instrumentation.runOnMainSync { } }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

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
                runCatching { instrumentation.runOnMainSync { } }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    private fun awaitMyRows(
        timeoutMillis: Long = TIMEOUT_MS,
        predicate: (List<ModelRow>) -> Boolean,
    ): List<ModelRow> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { viewModel.myModelRows.first(predicate) } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                runCatching { instrumentation.runOnMainSync { } }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    private fun awaitUi(timeoutMillis: Long = TIMEOUT_MS, predicate: (AtlasUiState) -> Boolean): AtlasUiState {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { viewModel.state.first(predicate) } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                runCatching { instrumentation.runOnMainSync { } }
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

    private fun countRows(sql: String, arg: String): Long {
        val db = app.database.openHelper.writableDatabase
        db.query(sql, arrayOf(arg)).use { cursor ->
            check(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    private fun givenConnection(
        label: String,
        priceState: String = "UNKNOWN",
        quotaState: String = "UNKNOWN",
    ): Fixture {
        val credentialId = newCredentialRef()
        val record = ConnectionRecord(
            id = newId(),
            providerId = newId().also { providerIds += it },
            accountId = newId(),
            credentialId = credentialId,
            endpointId = newId(),
            name = "Availability Scope Fixture $label",
            baseUrl = BASE_URL,
            endpointUrl = ENDPOINT_URL,
            modelId = "scope-fixture-model-$label-${UUID.randomUUID()}",
            priceState = priceState,
            quotaState = quotaState,
        )
        runBlocking { app.repository.addConnection(record) }
        return Fixture(
            providerId = record.providerId,
            accountId = record.accountId,
            credentialId = record.credentialId,
            connectionId = record.id,
            modelId = record.modelId,
        )
    }

    /** 只刪本檔建立的 provider 及其子資料（含 membership），順序滿足 RESTRICT 外鍵。 */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val providerList = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val accountsOfProvider = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($providerList))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            // 本檔 provider 的 alias 先收集其 canonical，刪 alias 後才能判定 orphan。
            val canonicalIds = HashSet<String>()
            db.query(
                "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN ($providerList)",
                args,
            ).use { cursor -> while (cursor.moveToNext()) canonicalIds += cursor.getString(0) }
            db.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM samples WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM my_models WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM credential_availability WHERE $accountsOfProvider", args)
            db.execSQL("DELETE FROM credentials WHERE $accountsOfProvider", args)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($providerList)", args)
            // orphan canonical 才刪：仍有 aliases／classifications／external 引用就保留。
            for (canonicalId in canonicalIds) {
                db.execSQL(
                    "DELETE FROM canonical_models WHERE canonicalModelId = ? " +
                        "AND NOT EXISTS (SELECT 1 FROM provider_model_aliases WHERE canonicalModelId = ?) " +
                        "AND NOT EXISTS (SELECT 1 FROM model_classifications WHERE canonicalModelId = ?) " +
                        "AND NOT EXISTS (SELECT 1 FROM external_mappings WHERE canonicalModelId = ?) " +
                        "AND NOT EXISTS (SELECT 1 FROM external_observations WHERE canonicalModelId = ?)",
                    arrayOf<Any?>(canonicalId, canonicalId, canonicalId, canonicalId, canonicalId),
                )
            }
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
        val modelId: String,
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
        const val HOST = "avail-scope.fixture.invalid"
        const val BASE_URL = "https://$HOST/v1"
        const val ENDPOINT_URL = "https://$HOST/v1/chat/completions"
        const val VAULT_ORIGIN = "https://$HOST:443"
        const val SECRET = "availability-scope-fixture-not-a-real-secret"
        const val GROUP_A = "grp-alpha"
        const val GROUP_B = "grp-beta"
        const val GROUP_C = "grp-absent"
    }
}
