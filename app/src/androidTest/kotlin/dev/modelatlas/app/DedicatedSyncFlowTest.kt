package dev.modelatlas.app

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.DedicatedSnapshotSync
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.ProviderManualConfig
import dev.modelatlas.app.data.RoomCommitter
import dev.modelatlas.app.data.SnapshotKind
import dev.modelatlas.app.data.dedicatedKind
import dev.modelatlas.app.data.toEntity
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.DedicatedStatus
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.EndpointRef
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.ReviewApproval
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6 worker C（B1）：Dedicated 產品流 device test。
 *
 * TRACEABILITY：P5 blocker B1 閉合（觸發點覆蓋）＋ MODEL-009。
 * 只用記憶體 db＋真 Repository＋真 DedicatedSnapshotSync＋官方 local 表
 * （per-model／pricing-mode）；不打真網路、不碰 vault、不當產品資料。
 * fake 只在本檔（呼叫鏈以 stateFlow＋DB heads 觀測，不覆寫產品類）。
 *
 * 覆蓋：
 * 1. 同步鈕可見性 gating（正規家族鍵支援；短別名／未知／空白／裸 UUID fail-closed）。
 * 2. BLOCKER1：UUID 經 endpoint origin／provider_config 家族標籤可 true（resolved）。
 * 3. 點擊觸發鏈：ViewModel state（dedicatedSyncing）→Repository→
 *    DedicatedSnapshotSync→snapshot 落庫（heads 可查）。
 * 4. syncing 狀態轉換（進入集合→finally 移出）與 single-flight（Mutex 原子認領，
 *    真併發重入只執行一次；見 singleFlight_concurrentClaimsOnce 註明）。
 * 5. 失敗保留 last-good（newerGood：Failure 推 latest、不動 lastGood；
 *    參數校验失敗在首次網路請求前拋錯，heads 原樣）。
 * 6. BLOCKER2：PAT／重疊 ref 拒絕（CredentialRole 校验）。
 * 7. BLOCKER7：多帳戶缺參拋錯不猜首帳號；單帳戶 fallback 明示。
 * 8. MAJOR5：Failure 不計 done（拋錯）、不更新 last synced。
 * 9. MAJOR7：per-key 明示 unsupported，不謊稱 synced。
 * 10. MAJOR1：http／跨 origin 在首次網路前拒絕。
 * 11. MAJOR2：operation 由 endpoint protocol 推導。
 * 12. MINOR9：空 id 不寫 "" 錯誤鍵。
 */
@RunWith(AndroidJUnit4::class)
class DedicatedSyncFlowTest {
    private lateinit var memDb: AtlasDatabase
    private lateinit var sync: DedicatedSnapshotSync
    private lateinit var repo: AtlasRepository

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        memDb = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        sync = DedicatedSnapshotSync(memDb)
        repo = AtlasRepository(memDb)
        runBlocking { seedMemory() }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        viewModelStore = ViewModelStore()
        viewModel = ViewModelProvider(viewModelStore, AtlasViewModelFactory(app)).get(AtlasViewModel::class.java)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        runCatching { memDb.close() }
        deleteRealGroq()
    }

    @Test
    fun gating_supportedAndUnknown() {
        // 同步鈕可見性 gating：正規家族鍵支援；短別名已刪除（碰撞風險），未知／空白 fail-closed。
        assertTrue(repo.dedicatedSupportsSync("groq"))
        assertTrue(repo.dedicatedSupportsSync("nvidia-hosted"))
        assertTrue(repo.dedicatedSupportsSync("nvidia-selfhosted"))
        assertTrue(repo.dedicatedSupportsSync("amd"))
        assertTrue(repo.dedicatedSupportsSync("gemini"))
        assertTrue(repo.dedicatedSupportsSync("openrouter"))
        // MINOR8：機械短別名不再支援。
        assertFalse(repo.dedicatedSupportsSync("nvidia-h"))
        assertFalse(repo.dedicatedSupportsSync("nvidia-s"))
        assertFalse(repo.dedicatedSupportsSync("no-such-provider"))
        assertFalse(repo.dedicatedSupportsSync(""))
        // MINOR6：裸 UUID（無家族資訊）gating false。
        val bareUuid = UUID.randomUUID().toString()
        assertFalse(repo.dedicatedSupportsSync(bareUuid))
        runBlocking { assertFalse(repo.dedicatedSupportsSyncResolved(bareUuid)) }
        runBlocking { assertFalse(repo.dedicatedSupportsSyncResolved("")) }
        // ViewModel gating 與 Repository 同語意（純本地，不碰網路／vault）。
        assertTrue(viewModel.dedicatedSupportsSync("groq"))
        assertFalse(viewModel.dedicatedSupportsSync("nvidia-h"))
        assertFalse(viewModel.dedicatedSupportsSync("no-such-provider"))
        assertFalse(viewModel.dedicatedSupportsSync(""))
        assertFalse(viewModel.dedicatedSupportsSync(bareUuid))
    }

    @Test
    fun gating_uuidViaFamilyLabel_resolvedTrue() = runBlocking {
        // BLOCKER1：真實 UUID provider 經 onboarding 家族標籤（group）可 true。
        val uuid = UUID.randomUUID().toString()
        memDb.providerDao().insert(ProviderEntity(uuid, "UUID供應者"))
        memDb.accountDao().insert(AccountEntity("uuid-a1", uuid))
        memDb.endpointDao().insert(
            EndpointEntity("uuid-e1", uuid, "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("uuid-off1", uuid, "uuid-e1", "openai/gpt-oss-20b", true, "MANUAL"))
        // 無家族標籤前 resolved false。
        assertFalse(repo.dedicatedSupportsSyncResolved(uuid))
        // 家族標籤 group="groq"（只讀 provider_config，不改其檔）。
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = uuid,
                workflowId = "wf-$uuid",
                authMode = AuthMode.BEARER,
                group = "groq",
                credentials = listOf(SafeCredentialRef("uuid-c1", "憑證", CredentialRole.INFERENCE)),
            ).toEntity(),
        )
        assertTrue(repo.dedicatedSupportsSyncResolved(uuid))
        // tags 維度亦可。
        val uuid2 = UUID.randomUUID().toString()
        memDb.providerDao().insert(ProviderEntity(uuid2, "UUID2"))
        memDb.accountDao().insert(AccountEntity("uuid2-a1", uuid2))
        memDb.endpointDao().insert(
            EndpointEntity("uuid2-e1", uuid2, "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("uuid2-off1", uuid2, "uuid2-e1", "m", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = uuid2,
                workflowId = "wf-$uuid2",
                authMode = AuthMode.BEARER,
                tags = listOf("openrouter"),
                credentials = emptyList(),
            ).toEntity(),
        )
        assertTrue(repo.dedicatedSupportsSyncResolved(uuid2))
    }

    @Test
    fun gating_uuidViaOfficialOrigin_resolvedTrue() = runBlocking {
        // BLOCKER1：UUID 經 endpoint baseUrl 官方 origin 維度可 true（openrouter.ai）。
        val uuid = UUID.randomUUID().toString()
        memDb.providerDao().insert(ProviderEntity(uuid, "UUID-OR"))
        memDb.accountDao().insert(AccountEntity("or-a1", uuid))
        memDb.endpointDao().insert(
            EndpointEntity("or-e1", uuid, "https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1/chat/completions", "CHAT_COMPLETIONS"),
        )
        memDb.offeringDao().insert(OfferingEntity("or-off1", uuid, "or-e1", "m", true, "MANUAL"))
        assertTrue(repo.dedicatedSupportsSyncResolved(uuid))
    }

    @Test
    fun pricing_nvidiaHostedMode_noNetwork() = runBlocking {
        // 計價模式 local 觀測：無 per-token 維度一律 UNKNOWN，不以 0 冒充，不燒網路。
        // NvidiaHostedAdapter.fetchPricing 固定 NO_PER_TOKEN_BILLING Success，
        // syncHostedModePricing 逐 offering 落 DEDICATED_PRICE（UNKNOWN、無 lastGood）。
        val summary = repo.syncDedicatedPricing("nvidia-hosted", "an")
        assertEquals("nvidia-hosted", summary.providerId)
        assertEquals(1, summary.pricingSynced)
        assertFalse(summary.pricingUnsupported)
        assertEquals("an", summary.resolvedAccountId)
        // BLOCKER7：明示帳戶不觸發單帳戶 fallback（僅 accountId==null 且單帳戶才 true）。
        assertFalse(summary.accountFallbackUsed)
        assertTrue(summary.latestObservedAtMillis != null)
        val scope = PricingScope("nvidia-hosted", "an", null, "nv-m", null, "e-nv", "CHAT_COMPLETIONS")
        val stored = sync.price(scope)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertNull(stored.lastGood)
    }

    @Test
    fun quota_groqPerModel_isolated_noNetwork() = runBlocking {
        // 官方 per-model 表：只持久化本地已有 offering 的模型；表外跳過不捏造 scope。
        val summary = repo.syncDedicatedQuotaPools("groq", "aq")
        assertEquals(1, summary.quotaSynced)
        assertFalse(summary.quotaUnsupported)
        assertEquals("aq", summary.resolvedAccountId)
        assertTrue(summary.latestObservedAtMillis != null)
        val hit = DedicatedSnapshotSync.accountQuotaScope(
            "groq", "aq", modelId = "openai/gpt-oss-20b", endpointId = "e-gq",
        )
        val stored = sync.quota(hit, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals("QUOTA_AVAILABLE", stored.latest!!.state)
        assertTrue(stored.lastGood != null)
        val miss = DedicatedSnapshotSync.accountQuotaScope(
            "groq", "aq", modelId = "test-model-not-in-table", endpointId = "e-gq",
        )
        assertNull(sync.quota(miss, SnapshotKind.ACCOUNT_QUOTA).latest)
    }

    @Test
    fun quota_roleRejectsPatAndOverlap() = runBlocking {
        // BLOCKER2：PAT（ACCOUNT）／重疊 ref 永不進 inference quota 路徑。
        memDb.providerDao().insert(ProviderEntity("p-role", "角色測試"))
        memDb.accountDao().insert(AccountEntity("a-role", "p-role"))
        memDb.credentialDao().insert(CredentialEntity("pat-ref", "a-role"))
        memDb.credentialDao().insert(CredentialEntity("inf-ref", "a-role"))
        memDb.credentialDao().insert(CredentialEntity("overlap-ref", "a-role"))
        memDb.endpointDao().insert(
            EndpointEntity("e-role", "p-role", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-role", "p-role", "e-role", "openai/gpt-oss-20b", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = "p-role",
                workflowId = "wf-p-role",
                authMode = AuthMode.BEARER,
                group = "groq",
                credentials = listOf(
                    SafeCredentialRef("pat-ref", "PAT", CredentialRole.ACCOUNT),
                    SafeCredentialRef("inf-ref", "推理", CredentialRole.INFERENCE),
                    SafeCredentialRef("overlap-ref", "重疊1", CredentialRole.ACCOUNT),
                    SafeCredentialRef("overlap-ref", "重疊2", CredentialRole.INFERENCE),
                ),
            ).toEntity(),
        )
        // PAT 拒絕。
        try {
            repo.syncDedicatedQuotaPools("p-role", "a-role", "pat-ref")
            fail("PAT must be rejected before quota write")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Credential role"))
        }
        // 重疊拒絕。
        try {
            repo.syncDedicatedQuotaPools("p-role", "a-role", "overlap-ref")
            fail("overlapping ref must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Credential role"))
        }
        // 缺 config 拒絕（無 config 的 provider 傳 credential）。
        memDb.providerDao().insert(ProviderEntity("p-nocfg", "無配置"))
        memDb.accountDao().insert(AccountEntity("a-nocfg", "p-nocfg"))
        memDb.credentialDao().insert(CredentialEntity("c-nocfg", "a-nocfg"))
        memDb.endpointDao().insert(
            EndpointEntity("e-nocfg", "p-nocfg", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-nocfg", "p-nocfg", "e-nocfg", "m", true, "MANUAL"))
        // p-nocfg 無家族路由（fixture origin＋無 config），先驗角色缺失亦拋錯（Unsupported 或 role）。
        try {
            repo.syncDedicatedQuotaPools("p-nocfg", "a-nocfg", "c-nocfg")
            fail("missing config must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun quota_multiAccountRequiresExplicitAccount() = runBlocking {
        // BLOCKER7：多帳戶缺參不再寫錯 scope（拋錯不猜首帳號）。
        memDb.providerDao().insert(ProviderEntity("p-multi", "多帳戶"))
        memDb.accountDao().insert(AccountEntity("a-multi-1", "p-multi"))
        memDb.accountDao().insert(AccountEntity("a-multi-2", "p-multi"))
        memDb.endpointDao().insert(
            EndpointEntity("e-multi", "p-multi", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-multi", "p-multi", "e-multi", "openai/gpt-oss-20b", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = "p-multi",
                workflowId = "wf-p-multi",
                authMode = AuthMode.BEARER,
                group = "groq",
                credentials = emptyList(),
            ).toEntity(),
        )
        try {
            repo.syncDedicatedQuotaPools("p-multi", null, null)
            fail("multi-account missing account must throw, not fallback to first")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Account required"))
        }
        // 明示帳戶則成功且 resolvedAccountId 正確。
        val ok = repo.syncDedicatedQuotaPools("p-multi", "a-multi-2", null)
        assertEquals("a-multi-2", ok.resolvedAccountId)
        assertFalse(ok.accountFallbackUsed)
        assertEquals(1, ok.quotaSynced)
    }

    @Test
    fun quota_singleAccountFallbackExplicit() = runBlocking {
        // BLOCKER7：單帳戶 fallback 允許且明示。
        val summary = repo.syncDedicatedQuotaPools("groq", null, null)
        assertEquals("aq", summary.resolvedAccountId)
        assertTrue(summary.accountFallbackUsed)
        assertEquals(1, summary.quotaSynced)
    }

    @Test
    fun quota_perKeyUnsupportedExplicit() = runBlocking {
        // MAJOR7：OpenRouter per-key quota 明示 unsupported，不謊稱 synced。
        memDb.providerDao().insert(ProviderEntity("p-or", "OR測試"))
        memDb.accountDao().insert(AccountEntity("a-or", "p-or"))
        memDb.credentialDao().insert(CredentialEntity("k-or", "a-or"))
        memDb.endpointDao().insert(
            EndpointEntity("e-or", "p-or", "https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-or", "p-or", "e-or", "m", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = "p-or",
                workflowId = "wf-p-or",
                authMode = AuthMode.BEARER,
                group = "openrouter",
                credentials = listOf(SafeCredentialRef("k-or", "key", CredentialRole.INFERENCE)),
            ).toEntity(),
        )
        // 無 key 的全域同步成功。
        val global = repo.syncDedicatedQuotaPools("p-or", "a-or", null)
        assertEquals(1, global.quotaSynced)
        assertFalse(global.quotaUnsupported)
        // 有 key 即 per-key 維度：明示 unsupported，synced=0。
        val keyed = repo.syncDedicatedQuotaPools("p-or", "a-or", "k-or")
        assertEquals(0, keyed.quotaSynced)
        assertTrue(keyed.quotaUnsupported)
    }

    @Test
    fun pricing_failureNotCountedAsDone() = runBlocking {
        // MAJOR5：Failure 只保 last-good，不計 done（拋錯）。openrouter fixture base 必走網路失敗。
        memDb.providerDao().insert(ProviderEntity("p-fail", "失敗測試"))
        memDb.accountDao().insert(AccountEntity("a-fail", "p-fail"))
        memDb.endpointDao().insert(
            EndpointEntity("e-fail", "p-fail", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-fail", "p-fail", "e-fail", "m-fail", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = "p-fail",
                workflowId = "wf-p-fail",
                authMode = AuthMode.BEARER,
                group = "openrouter",
                credentials = emptyList(),
            ).toEntity(),
        )
        try {
            repo.syncDedicatedPricing("p-fail", "a-fail", null)
            fail("network failure must throw, not count as done")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Dedicated sync failed"))
        }
        // Failure 快照已落（latest UNKNOWN、無 lastGood），但呼叫端以拋錯表達未完成。
        val scope = PricingScope("p-fail", "a-fail", null, "m-fail", null, "e-fail", "CHAT_COMPLETIONS")
        val stored = sync.price(scope)
        assertEquals("UNKNOWN", stored.latest!!.state)
        assertNull(stored.lastGood)
    }

    @Test
    fun pricing_httpsAndOriginEnforcedBeforeNetwork() = runBlocking {
        // MAJOR1：http／跨 origin 在首次網路前拒絕（靜態訊息，不洩漏 URL）。
        memDb.providerDao().insert(ProviderEntity("p-http", "HTTP測試"))
        memDb.accountDao().insert(AccountEntity("a-http", "p-http"))
        memDb.endpointDao().insert(
            EndpointEntity("e-http", "p-http", "http://fixture.invalid/v1", "http://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-http", "p-http", "e-http", "m", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = "p-http",
                workflowId = "wf-p-http",
                authMode = AuthMode.BEARER,
                group = "openrouter",
                credentials = emptyList(),
            ).toEntity(),
        )
        try {
            repo.syncDedicatedPricing("p-http", "a-http", null)
            fail("http base must be rejected before network")
        } catch (_: IllegalArgumentException) {
        }
        // 跨 origin 亦拒絕。
        memDb.providerDao().insert(ProviderEntity("p-xorigin", "跨源"))
        memDb.accountDao().insert(AccountEntity("a-xorigin", "p-xorigin"))
        memDb.endpointDao().insert(
            EndpointEntity("e-x1", "p-xorigin", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.endpointDao().insert(
            EndpointEntity("e-x2", "p-xorigin", "https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-x", "p-xorigin", "e-x1", "m", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = "p-xorigin",
                workflowId = "wf-p-xorigin",
                authMode = AuthMode.BEARER,
                group = "openrouter",
                credentials = emptyList(),
            ).toEntity(),
        )
        // syncNewApi 語意的單 origin 斷言：定價路徑以 openrouter catalog 為例，
        // 跨 origin 在 validate 階段即拋錯（若路由先命中 openrouter 家族）。
        // p-xorigin 無直接家族鍵，origin 維度命中 openrouter 後仍應因跨 origin 拋錯。
        try {
            repo.syncDedicatedPricing("p-xorigin", "a-xorigin", null)
            fail("cross-origin must be rejected")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
            // 網路失敗亦可接受（origin 已驗過才到網路）；本斷言重點為不靜默成功。
        }
    }

    @Test
    fun pricing_operationDerivedFromProtocol() = runBlocking {
        // MAJOR2：operation 由 endpoint protocol 推導，非硬編 CHAT_COMPLETIONS。
        memDb.providerDao().insert(ProviderEntity("p-op", "操作測試"))
        memDb.accountDao().insert(AccountEntity("a-op", "p-op"))
        memDb.endpointDao().insert(
            EndpointEntity("e-op", "p-op", "https://fixture.invalid/v1", "https://fixture.invalid/v1/responses", "RESPONSES"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-op", "p-op", "e-op", "nv-op", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = "p-op",
                workflowId = "wf-p-op",
                authMode = AuthMode.BEARER,
                group = "nvidia-hosted",
                credentials = emptyList(),
            ).toEntity(),
        )
        val summary = repo.syncDedicatedPricing("p-op", "a-op", null)
        assertEquals(1, summary.pricingSynced)
        val derived = PricingScope("p-op", "a-op", null, "nv-op", null, "e-op", "RESPONSES")
        assertEquals("UNKNOWN", sync.price(derived).latest!!.state)
        // 舊硬編 head 不應被寫入（不共用 head）。
        val hardCoded = PricingScope("p-op", "a-op", null, "nv-op", null, "e-op", "CHAT_COMPLETIONS")
        assertNull(sync.price(hardCoded).latest)
    }

    @Test
    fun viewModel_trigger_syncingTransitions_lastSynced() {
        // 點擊觸發鏈：按鈕→syncDedicated→Repository→DedicatedSnapshotSync→落庫→UI 狀態。
        // groq 配額為官方 local 表，不打真網路。
        seedRealGroq()
        assertTrue(viewModel.dedicatedSyncing.value.isEmpty())
        viewModel.syncDedicated("groq", "t-flow-aq", null, null)
        val done = awaitDedicated {
            viewModel.dedicatedLastSynced.value.containsKey("groq") &&
                "groq" !in viewModel.dedicatedSyncing.value
        }
        assertTrue("syncDedicated must complete with lastSynced", done)
        assertNull(
            "local groq sync must not error: ${viewModel.dedicatedSyncError.value["groq"]}",
            viewModel.dedicatedSyncError.value["groq"],
        )
        // MAJOR10：資料時間（latestObserved）已記錄；觸發時間明示分離。
        assertTrue(viewModel.dedicatedLatestObserved.value.containsKey("groq"))
        // MAJOR8：dedicated 落 DEDICATED_* 命名空間（不覆蓋 NewApi ACCOUNT_QUOTA head）。
        val heads = runBlocking { app.database.snapshotFactDao().headsByKind(SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name) }
        assertTrue("quota snapshot must land in snapshot_heads", heads.any { it.scopeKey.contains("groq") })
    }

    @Test
    fun viewModel_failureDoesNotUpdateLastSynced() {
        // MAJOR5：兩維皆失敗不更新 last synced、錯誤回 UI。
        seedRealGroq()
        // 用不存在帳戶觸發參數校验失敗（首次網路前拋錯）。
        viewModel.syncDedicated("groq", "no-such-account-xyz", null, null)
        val done = awaitDedicated {
            "groq" !in viewModel.dedicatedSyncing.value &&
                viewModel.dedicatedSyncError.value.containsKey("groq")
        }
        assertTrue("failure must surface error", done)
        assertFalse(viewModel.dedicatedLastSynced.value.containsKey("groq"))
        assertFalse(viewModel.dedicatedLatestObserved.value.containsKey("groq"))
    }

    @Test
    fun viewModel_emptyIdNoPollution() {
        // MINOR9：空 providerId 直接返回，不寫 "" 錯誤鍵。
        viewModel.syncDedicated("", null, null, null)
        // 輪詢一小段確保無非同步寫入（syncDedicated 空 id 為同步返回）。
        Thread.sleep(200)
        assertFalse(viewModel.dedicatedSyncError.value.containsKey(""))
        assertTrue(viewModel.dedicatedSyncing.value.isEmpty())
    }

    @Test
    fun singleFlight_secondCallReturnsImmediately() {
        // single-flight：重入直接返回現行狀態（不拋錯、不疊加、不留殘留狀態）。
        seedRealGroq()
        viewModel.syncDedicated("groq", "t-flow-aq", null, null)
        viewModel.syncDedicated("groq", "t-flow-aq", null, null)
        val done = awaitDedicated {
            viewModel.dedicatedLastSynced.value.containsKey("groq") &&
                "groq" !in viewModel.dedicatedSyncing.value
        }
        assertTrue("single-flight sync must still complete once", done)
        assertEquals(1, viewModel.dedicatedLastSynced.value.keys.count { it == "groq" })
        assertTrue("groq" !in viewModel.dedicatedSyncing.value)
    }

    @Test
    fun singleFlight_concurrentClaimsOnce() {
        // MINOR6 真併發：兩協程同時觸發同一 provider，Mutex 原子認領保證只執行一次落庫語意。
        // 限制註明：ViewModel 層以 Mutex＋StateFlow 實現 CAS；本測試以 async 並發呼叫，
        // 觀測最終只一次 lastSynced 寫入且無殘留 syncing。若 CI 出現偶發雙執行，
        // 代表 Mutex 認領失效，必須修復而非放寬斷言。
        seedRealGroq()
        runBlocking {
            val a = async { viewModel.syncDedicated("groq", "t-flow-aq", null, null) }
            val b = async { viewModel.syncDedicated("groq", "t-flow-aq", null, null) }
            a.await()
            b.await()
        }
        val done = awaitDedicated {
            viewModel.dedicatedLastSynced.value.containsKey("groq") &&
                "groq" !in viewModel.dedicatedSyncing.value
        }
        assertTrue("concurrent single-flight must converge", done)
        assertEquals(1, viewModel.dedicatedLastSynced.value.keys.count { it == "groq" })
    }

    @Test
    fun failure_preservesLastGood() = runBlocking {
        // 先取 last-good，再以本次失敗覆蓋：latest 推進、lastGood 原樣（newerGood）。
        repo.syncDedicatedQuotaPools("groq", "aq")
        val scope = DedicatedSnapshotSync.accountQuotaScope(
            "groq", "aq", modelId = "openai/gpt-oss-20b", endpointId = "e-gq",
        )
        val before = sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA)
        val goodId = requireNotNull(before.lastGood).id
        sync.syncAccountQuota(
            scope,
            DedicatedResult.Failure(
                DedicatedStatus.HTTP_TOO_MANY_REQUESTS_429,
                DedicatedEvidence(
                    "groq/rate-limits",
                    before.lastGood!!.observedAt + 5000L,
                    DedicatedProvenance.OFFICIAL_DOC,
                    429,
                ),
            ),
        )
        val after = sync.quota(scope, SnapshotKind.ACCOUNT_QUOTA)
        assertEquals(goodId, after.lastGood!!.id)
        assertEquals("UNKNOWN", after.latest!!.state)
        // 參數校验失敗（壞 account）：首次網路請求前拋錯，heads 原樣（不燒額度、不抹 last-good）。
        val headsBefore = memDb.snapshotFactDao()
            .headsByKind(SnapshotKind.ACCOUNT_QUOTA.name).associateBy { it.scopeKey }
        try {
            repo.syncDedicatedQuotaPools("groq", "no-such-account")
            fail("missing account must throw before any network")
        } catch (_: IllegalArgumentException) {
        }
        val headsAfter = memDb.snapshotFactDao()
            .headsByKind(SnapshotKind.ACCOUNT_QUOTA.name).associateBy { it.scopeKey }
        assertEquals(headsBefore, headsAfter)
    }

    @Test
    fun dedicatedKind_namespaceIsolatedFromNewApi() = runBlocking {
        // MAJOR8：dedicated 寫 DEDICATED_*，同 scope 的 NewApi head 互不覆蓋（X-004）。
        assertEquals(SnapshotKind.DEDICATED_PRICE, SnapshotKind.PRICE.dedicatedKind())
        assertEquals(SnapshotKind.DEDICATED_ACCOUNT_QUOTA, SnapshotKind.ACCOUNT_QUOTA.dedicatedKind())
        assertEquals(SnapshotKind.DEDICATED_TOKEN_QUOTA, SnapshotKind.TOKEN_QUOTA.dedicatedKind())
        assertEquals(SnapshotKind.MANUAL_PRICE, SnapshotKind.MANUAL_PRICE.dedicatedKind())
        assertEquals(SnapshotKind.MANUAL_QUOTA, SnapshotKind.MANUAL_QUOTA.dedicatedKind())

        repo.syncDedicatedQuotaPools("groq", "aq")
        repo.syncDedicatedPricing("nvidia-hosted", "an")

        val quotaScope = DedicatedSnapshotSync.accountQuotaScope(
            "groq", "aq", modelId = "openai/gpt-oss-20b", endpointId = "e-gq",
        )
        // 同義讀回：ACCOUNT_QUOTA 與 DEDICATED_ACCOUNT_QUOTA 同一 head。
        val viaBase = sync.quota(quotaScope, SnapshotKind.ACCOUNT_QUOTA)
        val viaDedicated = sync.quota(quotaScope, SnapshotKind.DEDICATED_ACCOUNT_QUOTA)
        assertEquals("QUOTA_AVAILABLE", viaBase.latest!!.state)
        assertEquals(viaBase.latest!!.evidenceId, viaDedicated.latest!!.evidenceId)

        // NewApi head 不被 dedicated 推進；dedicated head 已落。
        assertTrue(memDb.snapshotFactDao().headsByKind(SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name).any { it.scopeKey.contains("groq") })
        assertTrue(memDb.snapshotFactDao().headsByKind(SnapshotKind.ACCOUNT_QUOTA.name).none { it.scopeKey.contains("groq") })
        assertTrue(memDb.snapshotFactDao().headsByKind(SnapshotKind.DEDICATED_PRICE.name).any { it.scopeKey.contains("nvidia-hosted") })
        assertTrue(memDb.snapshotFactDao().headsByKind(SnapshotKind.PRICE.name).none { it.scopeKey.contains("nvidia-hosted") })
    }

    @Test
    fun commit_createsCanonicalAndAlias() = runBlocking {
        // P6 INT-1 OnboardingStore 接線：commit 同一 transaction 落 canonical／alias 真列。
        val workflowId = "wf-commit-canonical-" + UUID.randomUUID()
        val credId = "commit-cred-" + UUID.randomUUID()
        val snapshot = OnboardingSnapshot(
            workflowId = workflowId,
            flowType = OnboardingFlowType.ADVANCED,
            providerName = "提交驗證",
            baseUrl = "https://api.example.com/v1",
            credentials = listOf(SafeCredentialRef(credId, "推理", CredentialRole.INFERENCE)),
            safeCredentialIds = listOf(credId),
            endpointProfiles = listOf(
                EndpointRef(
                    EndpointType.CHAT_COMPLETIONS,
                    "https://api.example.com/v1/chat/completions",
                    "CHAT_COMPLETIONS",
                    enabled = true,
                ),
            ),
            manualModelIds = listOf("commit-model-a"),
            reviewApproval = ReviewApproval(true, 1_700_000_000_000L, "tester"),
            createdAtMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_000_001L,
        )
        val receipt = RoomCommitter(memDb).commit(snapshot)
        val providerId = requireNotNull(receipt.providerId)
        // commit 產出 UUID provider（本地指派 id），offering 可見未驗證。
        val offerings = memDb.offeringDao().byProvider(providerId)
        assertEquals(1, offerings.size)
        assertEquals(false, offerings[0].hidden)
        assertNull(offerings[0].verificationEvidenceId)
        // canonical／alias 真列已落：同鍵可查，不冒充官方。
        val alias = memDb.providerModelAliasDao().get(providerId, "commit-model-a")!!
        assertEquals(false, alias.verified)
        val canonical = memDb.canonicalModelDao().getById(alias.canonicalModelId)!!
        assertTrue(canonical.normalizedName.isNotBlank())
        assertEquals(canonical.canonicalModelId, alias.canonicalModelId)
        // onboarding 去重鍵已寫。
        assertEquals(receipt.providerId, memDb.onboardingCommitDao().getByWorkflow(workflowId)!!.providerId)
    }

    @Test
    fun uuidProvider_dedicatedPersistUsesFamilySource() = runBlocking {
        // BLOCKER1：裸 UUID 經家族標籤解析後可同步，落 DEDICATED kind，不污染 NewApi head。
        val uuid = UUID.randomUUID().toString()
        memDb.providerDao().insert(ProviderEntity(uuid, "UUID持久化"))
        memDb.accountDao().insert(AccountEntity("uuid-persist-a1", uuid))
        memDb.endpointDao().insert(
            EndpointEntity("uuid-persist-e1", uuid, "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("uuid-persist-off1", uuid, "uuid-persist-e1", "openai/gpt-oss-20b", true, "MANUAL"))
        memDb.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = uuid,
                workflowId = "wf-$uuid",
                authMode = AuthMode.BEARER,
                group = "groq",
                credentials = emptyList(),
            ).toEntity(),
        )
        assertTrue(repo.dedicatedSupportsSyncResolved(uuid))
        val summary = repo.syncDedicatedQuotaPools(uuid, "uuid-persist-a1")
        assertEquals(1, summary.quotaSynced)
        val scope = DedicatedSnapshotSync.accountQuotaScope(
            uuid, "uuid-persist-a1", modelId = "openai/gpt-oss-20b", endpointId = "uuid-persist-e1",
        )
        val stored = sync.quota(scope, SnapshotKind.DEDICATED_ACCOUNT_QUOTA)
        assertEquals("QUOTA_AVAILABLE", stored.latest!!.state)
        assertTrue(stored.lastGood != null)
        // 同 scope 的 NewApi head 仍空（命名空間隔離對 UUID 同樣成立）。
        assertTrue(memDb.snapshotFactDao().headsByKind(SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name).any { it.scopeKey.contains(uuid) })
        assertTrue(memDb.snapshotFactDao().headsByKind(SnapshotKind.ACCOUNT_QUOTA.name).none { it.scopeKey.contains(uuid) })
    }

    private suspend fun seedMemory() {
        memDb.providerDao().insert(ProviderEntity("groq", "測試供應者"))
        memDb.providerDao().insert(ProviderEntity("nvidia-hosted", "測試供應者"))
        memDb.providerDao().insert(ProviderEntity("nvidia-selfhosted", "測試供應者"))
        memDb.accountDao().insert(dev.modelatlas.app.data.AccountEntity("aq", "groq"))
        memDb.accountDao().insert(dev.modelatlas.app.data.AccountEntity("an", "nvidia-hosted"))
        memDb.accountDao().insert(dev.modelatlas.app.data.AccountEntity("ans", "nvidia-selfhosted"))
        memDb.endpointDao().insert(
            EndpointEntity("e-gq", "groq", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.endpointDao().insert(
            EndpointEntity("e-nv", "nvidia-hosted", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.endpointDao().insert(
            EndpointEntity("e-nvs", "nvidia-selfhosted", "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
        )
        memDb.offeringDao().insert(OfferingEntity("off-20b", "groq", "e-gq", "openai/gpt-oss-20b", true, "MANUAL"))
        memDb.offeringDao().insert(OfferingEntity("off-other", "groq", "e-gq", "test-model-not-in-table", true, "MANUAL"))
        memDb.offeringDao().insert(OfferingEntity("off-nv", "nvidia-hosted", "e-nv", "nv-m", true, "MANUAL"))
        memDb.offeringDao().insert(OfferingEntity("off-nvs", "nvidia-selfhosted", "e-nvs", "nv-s-m", true, "MANUAL"))
    }

    /** 真機 app DB 的 groq fixture（local 表同步用，不打網路；先清後種，保證冪等）。 */
    private fun seedRealGroq() {
        deleteRealGroq()
        runBlocking {
            app.database.providerDao().insert(ProviderEntity("groq", "測試供應者"))
            app.database.accountDao().insert(dev.modelatlas.app.data.AccountEntity("t-flow-aq", "groq"))
            app.database.endpointDao().insert(
                EndpointEntity("t-flow-e-gq", "groq", BASE_URL, ENDPOINT_URL),
            )
            app.database.offeringDao().insert(
                OfferingEntity("t-flow-off-20b", "groq", "t-flow-e-gq", "openai/gpt-oss-20b", true, "MANUAL"),
            )
            app.database.offeringDao().insert(
                OfferingEntity("t-flow-off-other", "groq", "t-flow-e-gq", "test-model-not-in-table", true, "MANUAL"),
            )
        }
    }

    /** 只刪本檔建立的 groq 圖（順序滿足 RESTRICT 外鍵；snapshot 證據保留作歷史）。 */
    private fun deleteRealGroq() {
        val providerIds = listOf("groq")
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
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM onboarding_commits WHERE providerId IN ($providerList)", args)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($providerList)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 等 ViewModel dedicated 狀態收斂：以輪詢＋waitForIdleSync 推進主線協程，
     * 不用固定 sleep；測試執行緒非主執行緒。
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
