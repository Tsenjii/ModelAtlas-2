package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.lmspeed.LmspeedFetcher
import dev.modelatlas.core.lmspeed.LmspeedRawModel
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiModuleAccess
import dev.modelatlas.core.newapi.NewApiPricing
import dev.modelatlas.core.newapi.NewApiPricingItem
import dev.modelatlas.core.newapi.NewApiRatio
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiSupportedEndpoint
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NAPI-004 M2 bundle＋perf gate＋quota 守衛（device androidTest）。
 *
 * 真 in-memory Room＋真 `AtlasRepository`／`NewApiSnapshotStore`／bundle；
 * 網路邊界一律不碰：定價用 typed `NewApiResult` fixture 經
 * `buildPricingScopes`／`syncPricingSession` 落庫（與 `syncNewApi` 同 session
 * 語意：一次 caps＋pricing 建 scope 落庫），adapter 只以計數
 * `CredentialAccess` 構造從不發請求，external 只注入計數 fake fetcher。
 * Fixture 僅存本檔，不當產品資料。無 sleep、無放寬斷言。
 *
 * 本檔驗證 typed snapshot 存儲、七維 scope、last-good、FREE_NOW telemetry、
 * null adapter 靜默跳過及外部／provider performance 分離；adapter 注入的真
 * VM→Repository→HTTP 生產 caller 由 NewApiBundleProductionFlowTest 直釘。
 * - perf-metrics 回應形狀＝U（survey）：本檔只斷言 gate 旗標
 *   `providerPerformanceFetched=false`，不斷言任何 perf 具體欄。
 */
@RunWith(AndroidJUnit4::class)
class MyModelsNewApiBundleTest {

    private lateinit var context: Context
    private val open = mutableListOf<AtlasDatabase>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    // ===== 1. scope 真相：head endpointId＝offering 值，operation＝mapper 值 =====

    @Test
    fun pricingScopesCarryOfferingEndpointAndMapperOperation() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = repo.newApiSnapshots
        // endpoint protocol 故意與官方 operation 錯開：scope operation 若仍為
        // mapper 值，即證明非 `ConnectionRecord.protocol`／本地 protocol 冒充。
        seedNewApiProvider(
            db = db,
            providerId = "napi-scope-p1",
            accountId = "a-scope-1",
            patRef = "pat-scope-1",
            endpoints = listOf("e-scope-1" to "ANTHROPIC_MESSAGES", "e-scope-2" to "ANTHROPIC_MESSAGES"),
            offerings = listOf("e-scope-1" to "scope-model-a", "e-scope-2" to "scope-model-b"),
        )
        val pricing = catalog(
            ratios = mapOf("scope-model-a" to BigDecimal("2"), "scope-model-b" to BigDecimal("0")),
            pricingVersion = "catalog-v1",
        )
        val scopes = store.buildPricingScopes(
            "napi-scope-p1",
            "a-scope-1",
            AccountPatRef("pat-scope-1"),
            pricing,
            listOf(db.endpointDao().getById("e-scope-1")!!, db.endpointDao().getById("e-scope-2")!!),
            listOf(
                db.offeringDao().getById("off:napi-scope-p1:scope-model-a")!!,
                db.offeringDao().getById("off:napi-scope-p1:scope-model-b")!!,
            ),
        )
        assertEquals(2, scopes.size)
        val byModel = scopes.associateBy { it.modelId }
        // endpointId＝offering 值（各模型跟自己 offering 的 endpoint，不互借）。
        assertEquals("e-scope-1", byModel.getValue("scope-model-a").endpointId)
        assertEquals("e-scope-2", byModel.getValue("scope-model-b").endpointId)
        for (s in scopes) {
            assertEquals("napi-scope-p1", s.providerId)
            assertEquals("a-scope-1", s.accountId)
            // 價格 scope 永不帶 credential（PAT 維度）。
            assertNull(s.credentialId)
            // operation＝mapper 展開值（CHAT_COMPLETIONS），不是 endpoint protocol。
            assertEquals("CHAT_COMPLETIONS", s.operation)
            assertEquals("default", s.group)
        }
        assertTrue(scopes.none { it.group == "all" })
    }

    // ===== 2. 落庫：head 寫入＋七維精確＋pricing_version＋不經 float =====

    @Test
    fun pricingSessionPersistsHeadsWithExactScope() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = repo.newApiSnapshots
        seedNewApiProvider(
            db = db,
            providerId = "napi-head-p1",
            accountId = "a-head-1",
            patRef = "pat-head-1",
            endpoints = listOf("e-head-1" to "CHAT_COMPLETIONS"),
            offerings = listOf("e-head-1" to "head-model"),
        )
        val pricingHead = catalog(ratios = mapOf("head-model" to BigDecimal("2")), pricingVersion = "catalog-v1")
        val scopes = store.buildPricingScopes(
            "napi-head-p1",
            "a-head-1",
            AccountPatRef("pat-head-1"),
            pricingHead,
            listOf(db.endpointDao().getById("e-head-1")!!),
            listOf(db.offeringDao().getById("off:napi-head-p1:head-model")!!),
        )
        assertEquals(1, scopes.size)
        val scope = scopes.single()
        val stored = store.syncPricingSession(AccountPatRef("pat-head-1"), caps(1000L), pricingOk(1000L, pricingHead), scopes)
        assertEquals(1, stored.size)

        val head = db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)
        assertNotNull(head)
        val parts = parseScopeKey(head!!.scopeKey)!!
        // 七維精確：provider／account／null credential／model／group／endpoint／operation。
        assertEquals("napi-head-p1", parts[0])
        assertEquals("a-head-1", parts[1])
        assertNull(parts[2])
        assertEquals("head-model", parts[3])
        assertEquals("default", parts[4])
        assertEquals("e-head-1", parts[5])
        assertEquals("CHAT_COMPLETIONS", parts[6])

        val readBack = store.price(scope)
        assertNotNull(readBack.latest)
        assertNotNull(readBack.lastGood)
        // 定價三模式全程 BigDecimal(String)，不經 float／Double：精確比較。
        assertEquals(0, BigDecimal("0.004").compareTo(readBack.lastGood!!.input!!))
        assertEquals("PAID", readBack.latest!!.state)
        assertEquals("catalog-v1", readBack.latest!!.pricingVersion)
        assertEquals("item-v1", readBack.latest!!.itemPricingVersion)
        assertEquals(1L, count(db, "snapshot_heads"))
    }

    // ===== 3. FREE_NOW：head 派生 FREE 照讀＋telemetry 照存＋perf 恆 false =====

    @Test
    fun freeModelHeadDerivesFreeNowAndBundleReadsItWhileTelemetryPersists() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = repo.newApiSnapshots
        seedNewApiProvider(
            db = db,
            providerId = "napi-free-p1",
            accountId = "a-free-1",
            patRef = "pat-free-1",
            endpoints = listOf("e-free-1" to "CHAT_COMPLETIONS"),
            offerings = listOf("e-free-1" to "free-model"),
        )
        val pricingFree = catalog(ratios = mapOf("free-model" to BigDecimal("0")), pricingVersion = "catalog-v1")
        val scopes = store.buildPricingScopes(
            "napi-free-p1",
            "a-free-1",
            AccountPatRef("pat-free-1"),
            pricingFree,
            listOf(db.endpointDao().getById("e-free-1")!!),
            listOf(db.offeringDao().getById("off:napi-free-p1:free-model")!!),
        )
        store.syncPricingSession(AccountPatRef("pat-free-1"), caps(1000L), pricingOk(1000L, pricingFree), scopes)
        assertEquals("FREE_NOW", store.price(scopes.single()).latest!!.state)

        // FREE 推斷一樣完整保存 telemetry：先落一筆 SUCCESS sample。
        db.sampleDao().upsert(
            SampleEntity(
                id = "s-free-1",
                providerId = "napi-free-p1",
                accountId = "a-free-1",
                safeCredentialId = "inf-free-1",
                endpointId = "e-free-1",
                providerModelId = "free-model",
                outcome = "SUCCESS",
            ),
        )

        val result = repo.enrichMyModelBundle(
            providerId = "napi-free-p1",
            providerModelId = "free-model",
            accountId = "a-free-1",
            group = "default",
            endpointId = "e-free-1",
            operation = "CHAT_COMPLETIONS",
        )
        // FREE evidence 只讀已派生 state（分歧即 UNKNOWN、無候選 null，不重算）。
        assertEquals("FREE_NOW", result.freePriceState)
        // perf 形狀 U：只斷言 gate 旗標，不斷言任何 perf 具體欄。
        assertFalse(result.providerPerformanceFetched)
        assertTrue(result.stepFailures.isEmpty())
        // telemetry 原樣保留（bundle 零寫入 samples）。
        val sample = db.sampleDao().getById("s-free-1")
        assertNotNull(sample)
        assertEquals("SUCCESS", sample!!.outcome)
        assertEquals("free-model", sample.providerModelId)
    }

    // ===== 4. 無 adapter／非支援家族：靜默跳過，stepFailures 無新標籤 =====

    @Test
    fun unsupportedProviderBundleSkipsLegsSilentlyWithoutLabels() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        db.providerDao().insert(ProviderEntity(providerId = "napi-skip-p1", name = "P napi-skip-p1"))

        val result = repo.enrichMyModelBundle(
            providerId = "napi-skip-p1",
            providerModelId = "skip-model",
        )
        // 非支援家族（New API 的 syncNewApi 需 VM 特權 adapter，本層不可及）：
        // pricing／quota 腿靜默跳過（null），不記 PRICING／QUOTA 標籤。
        assertNull(result.pricingSummary)
        assertNull(result.quotaSummary)
        assertFalse(result.providerPerformanceFetched)
        assertFalse(result.externalRefreshAttempted)
        assertNull(result.externalRefreshOk)
        assertNull(result.freePriceState)
        assertTrue(result.stepFailures.isEmpty())
        assertTrue(result.stepFailures.none { it == "PRICING_NEWAPI" })
    }

    // ===== 5. raw-secret 形狀：網路前拋出，呼叫數 0 =====

    @Test
    fun rawSecretCredentialRefFailsClosedBeforeAnyNetworkCall() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val fetcher = CountingFetcher()
        try {
            repo.enrichMyModelBundle(
                providerId = "napi-raw-p1",
                providerModelId = "raw-model",
                credentialId = "sk-live-EXAMPLE_NOT_A_REAL_KEY",
                lmspeedFetcher = fetcher,
            )
            fail("raw secret ref must fail closed before network")
        } catch (e: IllegalArgumentException) {
            // 靜態訊息：無 URL／server body／secret 回顯。
            val message = e.message ?: ""
            assertFalse(message.contains("sk-live-EXAMPLE_NOT_A_REAL_KEY"))
            assertFalse(message.contains("http"))
        }
        // 入口守衛在任何 adapter／fetcher 呼叫前拋出：零燒額度。
        assertEquals(0, fetcher.calls)
    }

    // ===== 6. syncNewApi 層守衛：adapter 已注入但參數非法→憑證零呼叫 =====

    @Test
    fun syncNewApiRejectsBlankProviderBeforeTouchingCredentials() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        var credentialCalls = 0
        val access = CredentialAccess { _, _ ->
            credentialCalls++
            "fixture-pat-value-do-not-log"
        }
        val adapter = NewApiAdapter(access, allowLoopbackHttpForTests = true)
        try {
            repo.syncNewApi("", adapter)
            fail("blank providerId must fail closed")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, credentialCalls)
    }

    // ===== 7. pricing 失敗：last-good 不變，只增 event =====

    @Test
    fun pricingFailurePreservesLastGoodAndAppendsEventOnly() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = repo.newApiSnapshots
        seedNewApiProvider(
            db = db,
            providerId = "napi-fail-p1",
            accountId = "a-fail-1",
            patRef = "pat-fail-1",
            endpoints = listOf("e-fail-1" to "CHAT_COMPLETIONS"),
            offerings = listOf("e-fail-1" to "fail-model"),
        )
        val pricingFail = catalog(ratios = mapOf("fail-model" to BigDecimal("2")), pricingVersion = "catalog-v1")
        val scopes = store.buildPricingScopes(
            "napi-fail-p1",
            "a-fail-1",
            AccountPatRef("pat-fail-1"),
            pricingFail,
            listOf(db.endpointDao().getById("e-fail-1")!!),
            listOf(db.offeringDao().getById("off:napi-fail-p1:fail-model")!!),
        )
        val scope = scopes.single()
        store.syncPricingSession(AccountPatRef("pat-fail-1"), caps(1000L), pricingOk(1000L, pricingFail), scopes)
        val goodHead = db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)!!
        val goodSnapshotId = goodHead.lastGoodSnapshotId!!
        val goodInput = store.price(scope).lastGood!!.input!!

        // 失敗（PAT 401 語意）：新 event 照記，last-good 不動。
        val failure = NewApiResult.Failure(
            NewApiStatus.HTTP_UNAUTHORIZED,
            NewApiEvidence(
                NewApiSnapshotStore.PRICE_SOURCE,
                2000L,
                null,
                NewApiVersionSupport.UNKNOWN,
                401,
                null,
            ),
        )
        store.syncPricingSession(AccountPatRef("pat-fail-1"), caps(2000L), failure, scopes)

        val after = db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)!!
        assertEquals(goodSnapshotId, after.lastGoodSnapshotId)
        assertEquals(0, goodInput.compareTo(store.price(scope).lastGood!!.input!!))
        // 只增 event：歷史 append（失敗 event 可查），head 指標仍單一。
        val history = store.history(scope, SnapshotKind.PRICE)
        assertEquals(2, history.size)
        assertEquals(1L, count(db, "snapshot_heads"))
        assertEquals(NewApiStatus.HTTP_UNAUTHORIZED.name, history.first().status)
        // 錯誤分類為靜態 enum 名，不含 URL／server body／secret。
        assertFalse(history.first().status.contains("http"))
        assertFalse(history.first().status.contains("sk-"))
    }

    // ===== 8. pricing_version：只增 event，不刪歷史 =====

    @Test
    fun pricingVersionChangeOnlyAppendsEvents() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = repo.newApiSnapshots
        seedNewApiProvider(
            db = db,
            providerId = "napi-ver-p1",
            accountId = "a-ver-1",
            patRef = "pat-ver-1",
            endpoints = listOf("e-ver-1" to "CHAT_COMPLETIONS"),
            offerings = listOf("e-ver-1" to "ver-model"),
        )
        val pricingV1 = catalog(ratios = mapOf("ver-model" to BigDecimal("2")), pricingVersion = "catalog-v1")
        val pricingV2 = pricingV1.copy(pricingVersion = "catalog-v2")
        val scopes = store.buildPricingScopes(
            "napi-ver-p1",
            "a-ver-1",
            AccountPatRef("pat-ver-1"),
            pricingV1,
            listOf(db.endpointDao().getById("e-ver-1")!!),
            listOf(db.offeringDao().getById("off:napi-ver-p1:ver-model")!!),
        )
        val scope = scopes.single()
        store.syncPricingSession(AccountPatRef("pat-ver-1"), caps(1000L), pricingOk(1000L, pricingV1), scopes)
        store.syncPricingSession(AccountPatRef("pat-ver-1"), caps(2000L), pricingOk(2000L, pricingV2), scopes)

        val history = store.history(scope, SnapshotKind.PRICE)
        assertEquals(2, history.size)
        assertEquals(1L, count(db, "snapshot_heads"))
        // 新版本成為 latest，舊版本 event 保留（只增不改寫歷史）。
        assertEquals("catalog-v2", history.first().pricingVersion)
        assertEquals("catalog-v1", history.last().pricingVersion)
        assertEquals("catalog-v2", store.price(scope).latest!!.pricingVersion)
    }

    // ===== 9. external 有寫入時 provider perf 仍恆 false（來源分離） =====

    @Test
    fun providerPerfStaysFalseEvenWhenExternalRefreshWrites() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = repo.newApiSnapshots
        seedNewApiProvider(
            db = db,
            providerId = "napi-perf-p1",
            accountId = "a-perf-1",
            patRef = "pat-perf-1",
            endpoints = listOf("e-perf-1" to "CHAT_COMPLETIONS"),
            offerings = listOf("e-perf-1" to "perf-model"),
        )
        val pricingPerf = catalog(ratios = mapOf("perf-model" to BigDecimal("0")), pricingVersion = "catalog-v1")
        val scopes = store.buildPricingScopes(
            "napi-perf-p1",
            "a-perf-1",
            AccountPatRef("pat-perf-1"),
            pricingPerf,
            listOf(db.endpointDao().getById("e-perf-1")!!),
            listOf(db.offeringDao().getById("off:napi-perf-p1:perf-model")!!),
        )
        store.syncPricingSession(AccountPatRef("pat-perf-1"), caps(1000L), pricingOk(1000L, pricingPerf), scopes)

        val fetcher = CountingFetcher(rows = listOf(LmspeedRawModel(slug = "ext-perf-model")))
        val result = repo.enrichMyModelBundle(
            providerId = "napi-perf-p1",
            providerModelId = "perf-model",
            accountId = "a-perf-1",
            group = "default",
            lmspeedFetcher = fetcher,
            endpointId = "e-perf-1",
            operation = "CHAT_COMPLETIONS",
        )
        // external 腿確實跑了（注入 fetcher 被呼叫），但 provider-wide perf
        // 全 repo 無 fetch 路徑：恆 false（Unknown），不拿 external 冒充。
        assertEquals(1, fetcher.calls)
        assertTrue(result.externalRefreshAttempted)
        assertFalse(result.providerPerformanceFetched)
        assertTrue(result.stepFailures.none { it.contains("PERF") })
        assertEquals("FREE_NOW", result.freePriceState)
    }

    // ===== harness（fixture 僅本檔，不當產品資料） =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    private suspend fun seedNewApiProvider(
        db: AtlasDatabase,
        providerId: String,
        accountId: String,
        patRef: String,
        endpoints: List<Pair<String, String>>,
        offerings: List<Pair<String, String>>,
    ) {
        val infRef = "inf-$accountId"
        db.providerDao().insert(ProviderEntity(providerId = providerId, name = "P $providerId"))
        db.accountDao().insert(AccountEntity(accountId, providerId))
        db.credentialDao().insert(CredentialEntity(patRef, accountId))
        db.credentialDao().insert(CredentialEntity(infRef, accountId))
        for ((endpointId, protocol) in endpoints) {
            db.endpointDao().insert(
                EndpointEntity(
                    endpointId = endpointId,
                    providerId = providerId,
                    baseUrl = "https://$providerId.example.com/v1",
                    endpointUrl = "https://$providerId.example.com/v1/chat/completions",
                    protocol = protocol,
                ),
            )
        }
        for ((endpointId, modelId) in offerings) {
            db.offeringDao().insert(
                OfferingEntity(
                    id = "off:$providerId:$modelId",
                    providerId = providerId,
                    endpointId = endpointId,
                    providerModelId = modelId,
                    manual = false,
                    source = "OFFICIAL",
                    hidden = false,
                ),
            )
        }
        val manualConfig = ProviderManualConfig(
            providerId = providerId,
            workflowId = "wf-$providerId-bundle",
            authMode = AuthMode.BEARER,
            credentials = listOf(
                SafeCredentialRef(patRef, "帳號 PAT", CredentialRole.ACCOUNT),
                SafeCredentialRef(infRef, "推理憑證", CredentialRole.INFERENCE),
            ),
        )
        db.providerConfigDao().insert(manualConfig.toEntity())
    }

    private fun caps(time: Long): NewApiResult<NewApiCapabilities> {
        val version = NewApiVersion.of(NewApiVersion.RC25)
        return NewApiResult.Success(
            NewApiEvidence("newapi/api/status", time - 1, NewApiVersion.RC25, NewApiVersionSupport.CERTIFIED_RC25, 200, null),
            NewApiCapabilities(
                version,
                BigDecimal("500000"),
                "USD",
                false,
                NewApiModuleAccess(true, true),
                version.certifiedEndpoints,
            ),
        )
    }

    private fun pricingOk(time: Long, pricing: NewApiPricing): NewApiResult<NewApiPricing> =
        NewApiResult.Success(
            NewApiEvidence(
                NewApiSnapshotStore.PRICE_SOURCE,
                time,
                NewApiVersion.RC25,
                NewApiVersionSupport.CERTIFIED_RC25,
                200,
                pricing.pricingVersion,
            ),
            pricing,
        )

    private fun catalog(ratios: Map<String, BigDecimal>, pricingVersion: String): NewApiPricing =
        NewApiPricing(
            items = ratios.map { (model, ratio) -> item(model, ratio) },
            skippedItems = 0,
            groupRatio = mapOf("default" to NewApiRatio(BigDecimal("1"), false)),
            usableGroups = listOf("default"),
            supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "POST")),
            autoGroups = emptyList(),
            pricingVersion = pricingVersion,
        )

    private fun item(model: String, ratio: BigDecimal): NewApiPricingItem =
        NewApiPricingItem(
            modelName = model,
            quotaType = 0,
            modelRatio = ratio,
            modelPrice = null,
            completionRatio = BigDecimal("1"),
            cacheRatio = null,
            createCacheRatio = null,
            imageRatio = null,
            audioRatio = null,
            audioCompletionRatio = null,
            enableGroups = listOf("all"),
            supportedEndpointTypes = listOf("openai"),
            billingMode = null,
            billingExpression = null,
            vendorId = null,
            pricingVersion = "item-v1",
        )

    private class CountingFetcher(
        val rows: List<LmspeedRawModel> = emptyList(),
    ) : LmspeedFetcher {
        var calls: Int = 0
        override suspend fun fetchModels(): List<LmspeedRawModel> {
            calls++
            return rows
        }
    }

    private fun count(db: AtlasDatabase, table: String): Long {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst())
            return it.getLong(0)
        }
    }
}
