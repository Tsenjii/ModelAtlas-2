package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.dedicated.DedicatedEvidence
import dev.modelatlas.core.dedicated.DedicatedProvenance
import dev.modelatlas.core.dedicated.DedicatedResult
import dev.modelatlas.core.dedicated.PricingFact
import dev.modelatlas.core.dedicated.PricingMode
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.newapi.NewApiCapabilities
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiModuleAccess
import dev.modelatlas.core.newapi.NewApiPricing
import dev.modelatlas.core.newapi.NewApiPricingItem
import dev.modelatlas.core.newapi.NewApiRatio
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiSupportedEndpoint
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NAPI-003 M5 endpoint／operation 維度 FREE 回歸（device androidTest，真 repo）。
 *
 * 鎖定 `deriveFreePriceState` dims 5..6（endpoint／operation）精確比對語意，
 * 防跨維冒充 FREE（Contract §7＋§6.3）：
 * - e1（FREE_NOW）／e2（PAID）同 provider／account／model／group scope、不同
 *   endpoint：各查詢各得其值（現況忽略 dims 5..6 時兩查詢都會誤 UNKNOWN）。
 * - 同七維 scope 多 evidence 分歧 → "UNKNOWN"；無候選 → null（不補 0／Free）。
 * - 具名 credential head（credential 維非 null）排除，不進 freePriceState 候選。
 * - MANUAL_PRICE kind 不進 freePriceState（manual 不冒充 official）。
 * - 省略 endpointId／operation（null）→ null：caller 缺值只命中 null 維 head，
 *   絕不借用具名 endpoint／operation head 的值（7 維不互借）。
 *
 * 真 in-memory Room＋真 `AtlasRepository`／`NewApiSnapshotStore`／
 * `DedicatedSnapshotSync`：head 一律經既有 store 路徑以 typed fixture 落庫
 * （PRICE＝New API 官方入口、DEDICATED_PRICE＝dedicated 觀測、MANUAL_PRICE＝
 * 手填槽），無網路、無 sleep、無放寬斷言、無補 0／Free。Fixture provider 為
 * dedicated UNSUPPORTED 家族（gemini，source 前綴 family 綁定）：bundle 的
 * pricing／quota 腿走既有路徑即降級 Unsupported（零網路、零寫入），freePriceState
 * 只讀 DB heads。Fixture 僅存本檔，不當產品資料。
 *
 * 誠實界定（相對 NAPI-001 DATA 片）：本檔直釘 M5 修正後語意——經
 * `enrichMyModelBundle` 的 controlled ports `endpointId?`／`operation?`
 * （NAPI-001 加上；呼叫端缺值只命中 null 維，七維不互借）。NAPI-001 落地前
 * 本檔不編譯（ports 尚不存在）：device CI 必須跑在 NAPI-001＋本檔同一 M5
 * slice 上（卡片順序：M5 小先行）。分歧 UNKNOWN／credential 排除／
 * MANUAL_PRICE 排除／無候選 null 為修正前後皆成立的既有 invariants。
 * 本檔不斷言 `parseScopeKey` 解析行為（七維精確已由 MyModelsNewApiBundleTest
 * 釘住），也不刪既有 parseScopeKey 截斷／尾字節斷言。
 */
@RunWith(AndroidJUnit4::class)
class NewApiEndpointOperationFreeTest {

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

    // ===== 1. dims 5..6 精確過濾：同 scope 各 endpoint 各得其值（e1 FREE／e2 PAID）=====

    @Test
    fun endpointOperationScopesEachGetTheirOwnFreeState() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = NewApiSnapshotStore(db)
        val dedicated = DedicatedSnapshotSync(db)
        seedFixture(db)
        val freeScope = PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-1", OP_CHAT)
        val paidScope = PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-2", OP_CHAT)

        // e1：New API 官方 PRICE head（七維 scope，endpoint e-eo-1）→ FREE_NOW；
        // e2：dedicated DEDICATED_PRICE head（同 dims 0..4，endpoint e-eo-2）→ PAID。
        persistOfficialPrice(store, freeScope, 1_000L, BigDecimal("0"))
        syncDedicatedPrice(dedicated, paidScope, 1_000L, BigDecimal("0.002"), BigDecimal("0.004"))
        // fixture 落庫真相：兩 head 各自 state（FREE_NOW vs PAID 分歧是真實的），
        // 各自 kind 的 head 並存（dedicated 觀測不覆蓋官方入口）。
        assertEquals("FREE_NOW", store.price(freeScope).latest!!.state)
        assertEquals("PAID", dedicated.price(paidScope).latest!!.state)

        // dims 5..6 精確過濾：同 provider／account／model／group，endpoint 各自
        // 命中各自 state；現況（忽略 dims 5..6）兩查詢都會誤 UNKNOWN。
        val freeResult = repo.enrichMyModelBundle(
            providerId = PROVIDER,
            providerModelId = MODEL,
            accountId = ACCOUNT,
            group = GROUP,
            endpointId = "e-eo-1",
            operation = OP_CHAT,
        )
        assertEquals("FREE_NOW", freeResult.freePriceState)
        assertEquals("PAID", repo.enrichMyModelBundle(
            providerId = PROVIDER,
            providerModelId = MODEL,
            accountId = ACCOUNT,
            group = GROUP,
            endpointId = "e-eo-2",
            operation = OP_CHAT,
        ).freePriceState)
        // 兩種 kind（PRICE／DEDICATED_PRICE）都受 dims 5..6 過濾：e2 為
        // DEDICATED_PRICE head 也被 endpoint 維正確排除／命中。
        // dedicated UNSUPPORTED 家族：pricing／quota 腿走既有路徑即降級
        //（零網路、零寫入），freePriceState 只讀 DB heads；推導無失敗標籤。
        assertNotNull(freeResult.pricingSummary)
        assertTrue(freeResult.pricingSummary!!.pricingUnsupported)
        assertNotNull(freeResult.quotaSummary)
        assertTrue(freeResult.quotaSummary!!.quotaUnsupported)
        assertNull(freeResult.newApiPricingSynced)
        assertTrue(freeResult.stepFailures.isEmpty())
    }

    // ===== 2. 同七維 scope 多 evidence 分歧 → UNKNOWN =====

    @Test
    fun sameScopeDivergentStatesYieldUnknown() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = NewApiSnapshotStore(db)
        val dedicated = DedicatedSnapshotSync(db)
        seedFixture(db)
        val scope = PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-1", OP_CHAT)

        // 同七維 scope（同 endpoint／operation）兩 head：PRICE（官方入口）→ PAID。
        persistOfficialPrice(store, scope, 1_000L, BigDecimal("2"))
        assertEquals("PAID", freeStateOf(repo, "e-eo-1", OP_CHAT))

        // 同 scopeKey 第二 head（DEDICATED_PRICE）→ FREE_NOW：候選分歧即
        // UNKNOWN，不挑一列、不補值（ headsByKind 禁 LIMIT 1 同語意）。
        syncDedicatedPrice(dedicated, scope, 2_000L, BigDecimal("0"), BigDecimal("0"))
        assertNotNull(db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name))
        assertNotNull(db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.DEDICATED_PRICE.name))
        val diverged = repo.enrichMyModelBundle(
            providerId = PROVIDER,
            providerModelId = MODEL,
            accountId = ACCOUNT,
            group = GROUP,
            endpointId = "e-eo-1",
            operation = OP_CHAT,
        )
        assertEquals("UNKNOWN", diverged.freePriceState)
        assertTrue(diverged.stepFailures.isEmpty())
    }

    // ===== 3. 無候選 → null（不補 0／Free、不借別維）=====

    @Test
    fun noMatchingCandidateYieldsNull() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = NewApiSnapshotStore(db)
        seedFixture(db)

        // 無任何 head：null（Unknown，不補 0／不補 Free）。
        assertNull(freeStateOf(repo, "e-eo-1", OP_CHAT))

        // 只落 e1（endpoint e-eo-1）。
        persistOfficialPrice(
            store,
            PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-1", OP_CHAT),
            1_000L, BigDecimal("0"),
        )
        // endpoint 無 head：null（不借 e-eo-1 的 FREE_NOW）。
        assertNull(freeStateOf(repo, "e-eo-2", OP_CHAT))
        // operation 無 head：null（不借 CHAT_COMPLETIONS 的值）。
        assertNull(freeStateOf(repo, "e-eo-1", OP_RESPONSES))
        // model 無 head：null。
        assertNull(freeStateOf(repo, "e-eo-1", OP_CHAT, modelId = "eo-none"))
        // group 省略（null）：只命中 null 維 head——不借 default group 的值
        //（HIGH-2 同語意延伸到 dims 5..6 前先成立）。
        assertNull(freeStateOf(repo, "e-eo-1", OP_CHAT, group = null))
    }

    // ===== 4. 省略 endpointId／operation → null（caller 缺值只命中 null 維）=====

    @Test
    fun omittedEndpointOperationYieldsNullWithoutBorrowing() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = NewApiSnapshotStore(db)
        val dedicated = DedicatedSnapshotSync(db)
        seedFixture(db)
        persistOfficialPrice(
            store,
            PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-1", OP_CHAT),
            1_000L, BigDecimal("0"),
        )
        syncDedicatedPrice(
            dedicated,
            PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-2", OP_CHAT),
            1_000L, BigDecimal("0.002"), BigDecimal("0.004"),
        )
        // sanity：具名值各得其值。
        assertEquals("FREE_NOW", freeStateOf(repo, "e-eo-1", OP_CHAT))
        assertEquals("PAID", freeStateOf(repo, "e-eo-2", OP_CHAT))

        // 省略 endpointId／operation（null）：只命中 null 維 head——store 落庫
        // head 一律具名七維，故無候選 → null，絕不借用 e1／e2 的值
        //（7 維不互借；省略維度不得拿具名 endpoint state 冒充整體 FREE）。
        assertNull(freeStateOf(repo, null, null))
        // 給 endpoint 省略 operation：null（不借 e1 的 CHAT_COMPLETIONS 值）。
        assertNull(freeStateOf(repo, "e-eo-1", null))
        // 給 operation 省略 endpoint：null（不借任何 endpoint 的值）。
        assertNull(freeStateOf(repo, null, OP_CHAT))
    }

    // ===== 5. 具名 credential head 排除 =====

    @Test
    fun namedCredentialHeadIsExcludedFromFreeEvidence() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = NewApiSnapshotStore(db)
        seedFixture(db)

        // 具名 credential 的 PRICE head（credential 維非 null，PAID）：official
        // 維候選必須排除（deriveFreePriceState 恆要求 credential 維 null）。
        val credScope = PricingScope(PROVIDER, ACCOUNT, "c-eo-1", MODEL, GROUP, "e-eo-1", OP_CHAT)
        persistOfficialPrice(store, credScope, 1_000L, BigDecimal("2"))
        assertNotNull(db.snapshotFactDao().head(credScope.storageKey(), SnapshotKind.PRICE.name))

        // 具名 credential head 不進 freePriceState 候選：單獨存在 → null
        //（不補它的 PAID、不補 0／Free）。
        assertNull(freeStateOf(repo, "e-eo-1", OP_CHAT))

        // 同 endpoint／operation 的 official 維 head（credential null）→ FREE_NOW：
        // 具名 credential head 被排除，不造成分歧（若排除失效會誤 UNKNOWN）。
        persistOfficialPrice(
            store,
            PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-1", OP_CHAT),
            2_000L, BigDecimal("0"),
        )
        assertEquals("FREE_NOW", freeStateOf(repo, "e-eo-1", OP_CHAT))
    }

    // ===== 6. MANUAL_PRICE 不進 freePriceState =====

    @Test
    fun manualPriceDoesNotEnterFreeEvidence() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val store = NewApiSnapshotStore(db)
        seedFixture(db)
        val scope = PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-1", OP_CHAT)

        // 手填槽（MANUAL_PRICE，USER source／LOW confidence）落同七維 scope；
        // 經既有 saveManualPrice 生產路徑，不捏造 official 列。
        store.saveManualPrice(
            PriceSnapshot(
                scope = scope,
                observedAt = 1_000L,
                input = BigDecimal("0.5"),
                output = BigDecimal("1.5"),
                fixedRequest = BigDecimal("1"),
            ),
        )
        assertNotNull(db.snapshotFactDao().head(scope.storageKey(), SnapshotKind.MANUAL_PRICE.name))

        // MANUAL_PRICE 不進 freePriceState：單獨存在 → null（不補其值、不冒充
        // official；deriveFreePriceState 只讀 PRICE／DEDICATED_PRICE）。
        assertNull(freeStateOf(repo, "e-eo-1", OP_CHAT))

        // official 維 head（FREE_NOW）落庫後：MANUAL_PRICE head 被排除，不造成
        // 分歧（若手填槽被誤讀會誤 UNKNOWN）。
        persistOfficialPrice(store, scope, 2_000L, BigDecimal("0"))
        assertEquals("FREE_NOW", freeStateOf(repo, "e-eo-1", OP_CHAT))
    }

    // ===== 7. dim 6（operation）：同 endpoint 不同 operation 各得其值 =====

    @Test
    fun operationScopesEachGetTheirOwnStateOnSameEndpoint() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val dedicated = DedicatedSnapshotSync(db)
        seedFixture(db)

        // 同 endpoint e-eo-1、不同 operation（dim 6 精確）：
        // CHAT_COMPLETIONS → FREE_NOW、text（CHAT_COMPLETIONS 舊別名，兩者皆為
        // PricingPolicy 支援 operation）→ PAID。
        syncDedicatedPrice(
            dedicated,
            PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-1", OP_CHAT),
            1_000L, BigDecimal("0"), BigDecimal("0"),
        )
        syncDedicatedPrice(
            dedicated,
            PricingScope(PROVIDER, ACCOUNT, null, MODEL, GROUP, "e-eo-1", OP_TEXT),
            2_000L, BigDecimal("0.002"), BigDecimal("0.004"),
        )
        assertEquals("FREE_NOW", freeStateOf(repo, "e-eo-1", OP_CHAT))
        assertEquals("PAID", freeStateOf(repo, "e-eo-1", OP_TEXT))
        // 無此 operation head：null（不借同 endpoint 其他 operation 的值）。
        assertNull(freeStateOf(repo, "e-eo-1", OP_RESPONSES))
    }

    // ===== harness（fixture 僅本檔，不當產品資料）=====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    /** dedicated UNSUPPORTED 家族 fixture（gemini）：pricing／quota 腿降級 Unsupported，零網路零寫入。 */
    private suspend fun seedFixture(db: AtlasDatabase) {
        db.providerDao().insert(ProviderEntity(providerId = PROVIDER, name = "P $PROVIDER"))
        db.accountDao().insert(AccountEntity(ACCOUNT, PROVIDER))
        db.credentialDao().insert(CredentialEntity("c-eo-1", ACCOUNT))
        for (endpointId in listOf("e-eo-1", "e-eo-2")) {
            db.endpointDao().insert(
                EndpointEntity(
                    endpointId = endpointId,
                    providerId = PROVIDER,
                    baseUrl = "https://gemini.fixture.invalid/v1",
                    endpointUrl = "https://gemini.fixture.invalid/v1/chat/completions",
                ),
            )
        }
    }

    /** PRICE head（New API 官方入口）：經 store 層 internal 接縫注入 typed fixture。 */
    private suspend fun persistOfficialPrice(
        store: NewApiSnapshotStore,
        scope: PricingScope,
        time: Long,
        ratio: BigDecimal,
    ) {
        store.persistPricing(scope, caps(time - 1), pricingOk(time, catalog(scope.modelId, ratio)))
    }

    /** DEDICATED_PRICE head：經既有 dedicated store 路徑（typed fixture，無網路）。 */
    private suspend fun syncDedicatedPrice(
        dedicated: DedicatedSnapshotSync,
        scope: PricingScope,
        time: Long,
        input: BigDecimal,
        output: BigDecimal,
    ) {
        dedicated.syncPricing(
            scope,
            DedicatedResult.Success(
                DedicatedEvidence("${scope.providerId}/pricing", time, DedicatedProvenance.OFFICIAL_DOC),
                PricingFact(scope.modelId, input, output, null, PricingMode.PER_TOKEN, DedicatedProvenance.OFFICIAL_DOC),
            ),
        )
    }

    private suspend fun freeStateOf(
        repo: AtlasRepository,
        endpointId: String?,
        operation: String?,
        modelId: String = MODEL,
        group: String? = GROUP,
    ): String? = repo.enrichMyModelBundle(
        providerId = PROVIDER,
        providerModelId = modelId,
        accountId = ACCOUNT,
        group = group,
        endpointId = endpointId,
        operation = operation,
    ).freePriceState

    private fun caps(time: Long): NewApiResult<NewApiCapabilities> {
        val version = NewApiVersion.of(NewApiVersion.RC25)
        return NewApiResult.Success(
            NewApiEvidence("newapi/api/status", time, NewApiVersion.RC25, NewApiVersionSupport.CERTIFIED_RC25, 200, null),
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

    private fun catalog(model: String, ratio: BigDecimal): NewApiPricing =
        NewApiPricing(
            items = listOf(item(model, ratio)),
            skippedItems = 0,
            groupRatio = mapOf("default" to NewApiRatio(BigDecimal("1"), false)),
            usableGroups = listOf("default"),
            supportedEndpoint = mapOf("openai" to NewApiSupportedEndpoint("/v1/chat/completions", "POST")),
            autoGroups = emptyList(),
            pricingVersion = "catalog-v1",
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

    companion object {
        private const val PROVIDER = "gemini"
        private const val ACCOUNT = "a-eo-1"
        private const val MODEL = "eo-model"
        private const val GROUP = "default"
        private const val OP_CHAT = "CHAT_COMPLETIONS"
        private const val OP_TEXT = "text"
        private const val OP_RESPONSES = "RESPONSES"
    }
}
