package dev.modelatlas.app

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.ProviderManualConfig
import dev.modelatlas.app.data.toEntity
import dev.modelatlas.core.onboarding.AuthMode
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-002 A3/A4：dedicated scoped operation-state device test（VM level，無 UI）。
 *
 * TRACEABILITY：B1／DedicatedSyncFlow、PROV-009、MODEL-007；CR-002 A3/A4。
 * 設計依：V1–V6 語意以本檔 committed KDoc「覆蓋」段與 `// --- V1`…`// --- V6` 測試
 * 區塊為準（CR-002 Dedicated VM closure ACCEPTED＝`873daa6`／`37163047722`，見
 * docs/ACTIVE_TASK.md:69 與 docs/TRACEABILITY.md:3 banner）。
 *
 * 只使用真 app DB（真 Repository→DedicatedSnapshotSync→官方 local 表），不打真網路、
 * 不碰 vault；fixture 一律以 UUID provider 隔離，teardown 以 FK 安全順序刪除本檔圖。
 * 全部斷言觀測 `dedicatedOp*` flow 與 `dedicatedOpState` selector（exact scope），
 * 不以 provider 聚合冒充 per-card 狀態；既有 15s 輪詢 timeout／sleep 模式不放大。
 *
 * 覆蓋：
 * 1. V1：同 provider 兩帳戶 op 鍵隔離，失敗 scope 不污染成功 scope 的 last-good。
 * 2. V2：跨 scope single-flight 不折疊、不吞併，各 op 鍵各得一次成功寫入。
 * 3. V3：錯誤歸因只落失敗 scope，成功 sibling 的 op 讀取無聚合錯誤洩漏。
 * 4. V4：`dedicatedOpKey` 長度前綴編碼純向量（null/blank 同義、防碰撞、空 provider 不入鍵）。
 * 5. V5：per-scope 觸發時間與資料觀測時間分離；失敗 scope 兩者皆不寫。
 * 6. V6：card `onDedicatedSync` 參數建構 contract 表（group 只在選取 context 相符時透傳）。
 */
@RunWith(AndroidJUnit4::class)
class DedicatedOpScopeFlowTest {
    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    /** 本檔建立的 provider（UUID 隔離），tearDown 以 FK 安全順序刪除。 */
    private val seededProviders = mutableListOf<String>()

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
        seededProviders.toList().forEach { runCatching { deleteProviderGraph(it) } }
        seededProviders.clear()
    }

    // ---------------------------------------------------------------- V1

    @Test
    fun v1_twoAccountsSameProvider_isolatedScopeLastGood() {
        val provider = newProviderId()
        val accountA = newAccountId()
        val accountB = newAccountId()
        seedGroqFamily(provider, accountA, accountB)
        val keyA = dedicatedOpKey(provider, accountA, null, null)
        val keyB = dedicatedOpKey(provider, accountB, null, null)
        val badAccount = newAccountId()
        val keyBad = dedicatedOpKey(provider, badAccount, null, null)

        viewModel.syncDedicated(provider, accountA, null, null)
        assertTrue(
            "scope A must complete with its own lastSynced",
            awaitDedicated {
                keyA in viewModel.dedicatedOpLastSynced.value && keyA !in viewModel.dedicatedOpSyncing.value
            },
        )
        assertNull("successful scope A must not carry an op error", viewModel.dedicatedOpError.value[keyA])
        assertNull("sibling scope B must not inherit A's error", viewModel.dedicatedOpError.value[keyB])
        assertFalse("sibling scope B must not inherit A's success", viewModel.dedicatedOpLastSynced.value.containsKey(keyB))
        val lastGoodA = viewModel.dedicatedOpLastSynced.value[keyA]
        assertNotNull(lastGoodA)

        viewModel.syncDedicated(provider, badAccount, null, null)
        assertTrue(
            "bad scope must settle with its own error",
            awaitDedicated {
                viewModel.dedicatedOpError.value[keyBad] != null && keyBad !in viewModel.dedicatedOpSyncing.value
            },
        )
        assertNull("failed scope must not write lastSynced", viewModel.dedicatedOpLastSynced.value[keyBad])
        assertNull("failed scope must not write latestObserved", viewModel.dedicatedOpLatestObserved.value[keyBad])
        assertEquals("prior scope A last-good must be preserved", lastGoodA, viewModel.dedicatedOpLastSynced.value[keyA])
        assertNull("prior scope A must stay error-free", viewModel.dedicatedOpError.value[keyA])
    }

    // ---------------------------------------------------------------- V2

    @Test
    fun v2_crossScopeSingleFlight_notSwallowed() {
        val provider = newProviderId()
        val accountA = newAccountId()
        val accountB = newAccountId()
        seedGroqFamily(provider, accountA, accountB)
        val keyA = dedicatedOpKey(provider, accountA, null, null)
        val keyB = dedicatedOpKey(provider, accountB, null, null)

        // 兩個不同 op 鍵（同 provider 不同 account）必須各自認領、各自完成；
        // provider-keyed single-flight 會吞掉第二個 scope，此斷言即防該回歸。
        viewModel.syncDedicated(provider, accountA, null, null)
        viewModel.syncDedicated(provider, accountB, null, null)
        assertTrue(
            "both scopes must complete with distinct lastSynced entries",
            awaitDedicated {
                keyA in viewModel.dedicatedOpLastSynced.value &&
                    keyB in viewModel.dedicatedOpLastSynced.value &&
                    keyA !in viewModel.dedicatedOpSyncing.value &&
                    keyB !in viewModel.dedicatedOpSyncing.value
            },
        )
        assertNotEquals("two scopes must not collapse into one key", keyA, keyB)
        assertEquals(1, viewModel.dedicatedOpLastSynced.value.keys.count { it == keyA })
        assertEquals(1, viewModel.dedicatedOpLastSynced.value.keys.count { it == keyB })
        assertNull(viewModel.dedicatedOpError.value[keyA])
        assertNull(viewModel.dedicatedOpError.value[keyB])
    }

    // ---------------------------------------------------------------- V3

    @Test
    fun v3_errorAttribution_noProviderAggregateLeakIntoScope() {
        val provider = newProviderId()
        val accountA = newAccountId()
        seedGroqFamily(provider, accountA)
        val keyA = dedicatedOpKey(provider, accountA, null, null)
        val badAccount = newAccountId()
        val keyBad = dedicatedOpKey(provider, badAccount, null, null)

        viewModel.syncDedicated(provider, accountA, null, null)
        assertTrue(
            "scope A success first",
            awaitDedicated { keyA in viewModel.dedicatedOpLastSynced.value && keyA !in viewModel.dedicatedOpSyncing.value },
        )

        viewModel.syncDedicated(provider, badAccount, null, null)
        assertTrue(
            "bad scope must surface an op error",
            awaitDedicated { viewModel.dedicatedOpError.value[keyBad] != null && keyBad !in viewModel.dedicatedOpSyncing.value },
        )

        // 錯誤歸因：失敗只落該 op 鍵；成功 sibling 的 exact-scope 讀取不得看到錯誤。
        assertNull("scope A op error must remain null after sibling failure", viewModel.dedicatedOpError.value[keyA])
        val goodState = viewModel.dedicatedOpState(provider, accountA, null, null)
        assertNull("dedicatedOpState(A).error must not leak provider aggregate", goodState.error)
        assertNotNull("dedicatedOpState(A).lastSynced must remain", goodState.lastSynced)
        val badState = viewModel.dedicatedOpState(provider, badAccount, null, null)
        assertNotNull("dedicatedOpState(bad).error must attribute to that scope", badState.error)
        assertNull("dedicatedOpState(bad).lastSynced must be absent", badState.lastSynced)
    }

    // ---------------------------------------------------------------- V4

    @Test
    fun v4_keyEncoding_pureVectors() {
        val p = "prov-" + UUID.randomUUID()

        assertEquals("same args must map to same key", dedicatedOpKey(p, "a", "c", "g"), dedicatedOpKey(p, "a", "c", "g"))
        assertNotEquals("group participates in scope", dedicatedOpKey(p, "a", "c", null), dedicatedOpKey(p, "a", "c", "g"))
        assertNotEquals("credential participates in scope", dedicatedOpKey(p, "a", null, null), dedicatedOpKey(p, "a", "k", null))
        assertNotEquals("account participates in scope", dedicatedOpKey(p, "a", null, null), dedicatedOpKey(p, "b", null, null))
        assertNotEquals("provider participates in scope", dedicatedOpKey("p1", "a", null, null), dedicatedOpKey("p2", "a", null, null))

        // null 與 blank 同義（皆 unknown scope，映射相同鍵）。
        assertEquals("blank account == null account", dedicatedOpKey(p, "", null, null), dedicatedOpKey(p, null, null, null))
        assertEquals("blank whitespace account == null", dedicatedOpKey(p, "   ", null, null), dedicatedOpKey(p, null, null, null))
        assertEquals("blank credential == null", dedicatedOpKey(p, "a", "", null), dedicatedOpKey(p, "a", null, null))
        assertEquals("blank group == null", dedicatedOpKey(p, "a", "c", "  "), dedicatedOpKey(p, "a", "c", null))
        assertEquals("non-blank values trim to exact scope", dedicatedOpKey(p, "  a  ", " c ", null), dedicatedOpKey(p, "a", "c", null))

        // 長度前綴防碰撞：raw "|"／"::" 串接會與多欄組合互撞，本方案不得互撞。
        assertNotEquals(
            "embedded separator must not collide with split fields",
            dedicatedOpKey(p, "a|b", null, null),
            dedicatedOpKey(p, "a", "b", null),
        )
        assertNotEquals(
            "embedded :: must not collide with column split",
            dedicatedOpKey("p::q", null, null, null),
            dedicatedOpKey("p", "q", null, null),
        )

        // MINOR9：空白 provider 在 VM 即返回，永不入任何 op 鍵。
        viewModel.syncDedicated("", null, null, null)
        assertTrue("blank provider must not claim any op", viewModel.dedicatedOpSyncing.value.isEmpty())
        assertTrue("blank provider must not write a scoped key", viewModel.dedicatedOpError.value.isEmpty())
        assertFalse(
            "blank provider key must never exist",
            viewModel.dedicatedOpError.value.containsKey(dedicatedOpKey("", null, null, null)),
        )
    }

    // ---------------------------------------------------------------- V5

    @Test
    fun v5_triggerObservedSeparationPerScope() {
        val provider = newProviderId()
        val accountA = newAccountId()
        seedGroqFamily(provider, accountA)
        val keyA = dedicatedOpKey(provider, accountA, null, null)
        val badAccount = newAccountId()
        val keyBad = dedicatedOpKey(provider, badAccount, null, null)

        viewModel.syncDedicated(provider, accountA, null, null)
        assertTrue(
            "scope A must record both trigger and observed",
            awaitDedicated { keyA in viewModel.dedicatedOpLastSynced.value && keyA !in viewModel.dedicatedOpSyncing.value },
        )
        val trigger = viewModel.dedicatedOpLastSynced.value[keyA]
        val observed = viewModel.dedicatedOpLatestObserved.value[keyA]
        assertNotNull("device trigger time must be recorded", trigger)
        assertNotNull("data observed time must be recorded separately", observed)
        assertTrue("trigger time is device-local and >= data observed time", trigger!! >= observed!!)

        viewModel.syncDedicated(provider, badAccount, null, null)
        assertTrue(
            "failed scope must settle",
            awaitDedicated { viewModel.dedicatedOpError.value[keyBad] != null && keyBad !in viewModel.dedicatedOpSyncing.value },
        )
        assertNull("failed scope must not write trigger time", viewModel.dedicatedOpLastSynced.value[keyBad])
        assertNull("failed scope must not write observed time", viewModel.dedicatedOpLatestObserved.value[keyBad])
    }

    // ---------------------------------------------------------------- V6

    @Test
    fun v6_cardContract_argumentTable() {
        val provider = "prov-" + UUID.randomUUID()

        // onDedicatedSync 參數建構 contract（plan §2.3）：account/credential blank→null；
        // group 只在該卡自己的 account＋credential 與 selected context 完全相同時透傳。
        val anon = item(provider, accountId = "", credentialId = "")
        assertEquals(SyncArgs(provider, null, null, null), cardSyncArgs(anon, null, null, "vip"))

        val scoped = item(provider, accountId = "acc-a", credentialId = "cred-a")
        assertEquals(
            "no matching selection -> group null",
            SyncArgs(provider, "acc-a", "cred-a", null),
            cardSyncArgs(scoped, null, null, "vip"),
        )
        assertEquals(
            "matching selection -> group passed",
            SyncArgs(provider, "acc-a", "cred-a", "vip"),
            cardSyncArgs(scoped, "acc-a", "cred-a", "vip"),
        )
        assertEquals(
            "account matches but credential differs -> group null",
            SyncArgs(provider, "acc-a", "cred-a", null),
            cardSyncArgs(scoped, "acc-a", "cred-b", "vip"),
        )
        assertEquals(
            "blank selected group -> null",
            SyncArgs(provider, "acc-a", "cred-a", null),
            cardSyncArgs(scoped, "acc-a", "cred-a", "   "),
        )

        val accountOnly = item(provider, accountId = "acc-a", credentialId = "")
        assertEquals(
            "anonymous credential must not match a selected credential",
            SyncArgs(provider, "acc-a", null, null),
            cardSyncArgs(accountOnly, "acc-a", null, "vip"),
        )

        // 參數表中的 group 差異必須反映到真 production 的 exact-scope op 鍵。
        assertNotEquals(
            "selected group must not collapse into the no-group scope",
            dedicatedOpKey(provider, "acc-a", "cred-a", "vip"),
            dedicatedOpKey(provider, "acc-a", "cred-a", null),
        )
        assertEquals(
            "anonymous args must resolve to the null/null/null scope",
            dedicatedOpKey(provider, null, null, null),
            dedicatedOpKey(provider, "".takeIf { it.isNotBlank() }, "".takeIf { it.isNotBlank() }, null),
        )
    }

    // ---------------------------------------------------------------- fixture / harness

    private data class SyncArgs(
        val providerId: String,
        val accountId: String?,
        val credentialId: String?,
        val group: String?,
    )

    private fun item(provider: String, accountId: String, credentialId: String): ConnectionItem =
        ConnectionItem(
            id = "item-" + UUID.randomUUID(),
            providerId = provider,
            name = "fixture",
            baseUrl = BASE_URL,
            modelId = "openai/gpt-oss-20b",
            credentialLabel = "fixture",
            accountId = accountId,
            credentialId = credentialId,
        )

    /**
     * Reference implementation of the onDedicatedSync argument-construction rule
     * (plan §2.3). UI caller is a later slice; this pins the tuple contract without
     * mounting Compose.
     */
    private fun cardSyncArgs(
        item: ConnectionItem,
        selectedAccountId: String?,
        selectedCredentialId: String?,
        selectedGroup: String?,
    ): SyncArgs {
        val accountId = item.accountId.takeIf { it.isNotBlank() }
        val credentialId = item.credentialId.takeIf { it.isNotBlank() }
        val group = if (item.accountId == selectedAccountId && item.credentialId == selectedCredentialId) {
            selectedGroup?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        return SyncArgs(item.providerId, accountId, credentialId, group)
    }

    private fun newProviderId(): String = "op-scope-p-" + UUID.randomUUID()

    private fun newAccountId(): String = "op-scope-a-" + UUID.randomUUID()

    /**
     * 種一個走 groq 家族的 UUID provider：pricing UNSUPPORTED、quota PER_MODEL 官方 local 表，
     * 不打真網路。accountIds 可多個（V1／V2 兩帳戶）。
     */
    private fun seedGroqFamily(provider: String, vararg accountIds: String) {
        seededProviders.add(provider)
        val db = app.database
        runBlocking {
            db.providerDao().insert(ProviderEntity(provider, "OP-scope fixture"))
            accountIds.forEach { db.accountDao().insert(AccountEntity(it, provider)) }
            db.endpointDao().insert(
                EndpointEntity("$provider-ep", provider, BASE_URL, ENDPOINT_URL),
            )
            db.offeringDao().insert(
                OfferingEntity("$provider-off", provider, "$provider-ep", "openai/gpt-oss-20b", true, "MANUAL"),
            )
            db.providerConfigDao().insert(
                ProviderManualConfig(
                    providerId = provider,
                    workflowId = "wf-$provider",
                    authMode = AuthMode.BEARER,
                    group = "groq",
                    credentials = emptyList(),
                ).toEntity(),
            )
        }
    }

    /**
     * 只刪本檔建立的 provider 圖，順序滿足 RESTRICT 外鍵。
     * snapshot evidence 為歷史，不刪（與 DedicatedSyncFlowTest 同語意）。
     */
    private fun deleteProviderGraph(provider: String) {
        val args = arrayOf<Any?>(provider)
        val accountsOfProvider = "accountId IN (SELECT accountId FROM accounts WHERE providerId = ?)"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM checkins WHERE providerId = ?", args)
            db.execSQL("DELETE FROM credential_availability WHERE $accountsOfProvider", args)
            db.execSQL("DELETE FROM credentials WHERE $accountsOfProvider", args)
            db.execSQL("DELETE FROM offerings WHERE providerId = ?", args)
            db.execSQL("DELETE FROM endpoints WHERE providerId = ?", args)
            db.execSQL("DELETE FROM accounts WHERE providerId = ?", args)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId = ?", args)
            db.execSQL("DELETE FROM onboarding_commits WHERE providerId = ?", args)
            db.execSQL("DELETE FROM providers WHERE providerId = ?", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 等 ViewModel dedicated op 狀態收斂：以既有輪詢＋waitForIdleSync 推進主線協程，
     * 不用固定 sleep、不放大 timeout；測試執行緒非主執行緒。
     */
    private fun awaitDedicated(timeoutMillis: Long = TIMEOUT_MS, predicate: () -> Boolean): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            if (Looper.myLooper() != Looper.getMainLooper()) runCatching { instrumentation.waitForIdleSync() }
            Thread.sleep(50)
        }
        return predicate()
    }

    private class AtlasViewModelFactory(private val app: AtlasApplication) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AtlasViewModel(app) as T
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://fixture.invalid/v1"
        const val ENDPOINT_URL = "https://fixture.invalid/v1/chat/completions"
    }
}
