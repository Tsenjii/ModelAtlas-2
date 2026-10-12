package dev.modelatlas.app.data

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NAPI 生產 caller 真證據（device androidTest；本檔唯一新增）。
 *
 * 補 NAPI-004 未涵蓋的缺口：真 Room／真 VM（[AtlasViewModel.maybeEnrichMyModel]）
 * → 真 repository bundle（[AtlasRepository.enrichMyModelBundle]／[AtlasRepository.syncNewApi]）
 * → 真 [NewApiAdapter] HTTP 棧 → MockWebServer loopback 真 socket。
 *
 * 傳輸說明（非掩飾，review 必讀）：
 * - DB 的 endpoint baseUrl 維持 `https://…example.com/v1`（生產 [EndpointPolicy] HTTPS
 *   gate 要求；`http` 在 [AtlasRepository.syncNewApi] 前置即 fail-fast，不燒額度）。
 * - 測試用 OkHttp [Interceptor] 只做路由：把已通過生產驗證的 `https` request 改寫為
 *   `server.url(path)` 的 loopback `http` 真 socket（Authorization／path／body 原樣保留，
 *   MockWebServer 真實記錄）。DB 不存 loopback、不存 secret；改寫僅存在測試傳輸層。
 * - 因此 MockWebServer 記錄的 path／Authorization／計數即為 adapter 真實送出的證據；
 *   store 落庫／head／last-good／alias／membership 皆走真生產路徑。Fixture JSON 僅存本檔，
 *   經真 adapter 解析＋真 store 落庫後才斷言 DB，不拿 fixture 值冒充產品資料。
 *
 * 覆盖：
 * 1. [concurrentWaveSharesSinglePricingFetchAndEachModelKeepsExactScopeEvidence]：
 *    同 provider 雙模型同 wave 併發 → `fetchPricing` HTTP 計數＝1（deterministic barrier），
 *    兩模型各自 alias／membership／exact scope head＋FREE／PAID 各得其值＋PAT／key 分離。
 * 2. [patAndInferenceKeyAreNotInterchangeableOverHttp]：adapter 直接對 MockWebServer：
 *    PAT 走 pricing／account、inference key 走 token、互換零請求、401 不重試、錯誤不洩 secret。
 * 3. [syncFailureKeepsMembershipAndLastGood]：PAT 缺失致第三腿 `PRICING_NEWAPI` fail-open，
 *    membership／last-good／FREE head 不被刪除，且零網路請求（前置 fail-fast）。
 * 4. [failedLegDoesNotAdvanceGenerationSoNextModelRetries]：首模型 New API 腿例外失敗
 *    （null，不推進 generation）後，次模型仍能注入 adapter 重試成功。
 * 5. [httpPricingFailureDoesNotAdvanceGenerationSoNextModelRetriesAndWritesBHead]：
 *    首模型已過 head gate 但 `/api/pricing` 回 HTTP 500（`prices=[]`、`newApiPricingSynced=0`、
 *    未拋例外）後，同 provider 次模型必須再次發出 pricing GET 並成功寫 B head。
 *    鎖住 generation 成功判斷 `> 0` 的真退路（HTTP failure 不是例外）。
 *
 * Barrier／timeout：全部 [CompletableDeferred]＋[withTimeout]（15s）；零 `sleep`／零放寬。
 */
@RunWith(AndroidJUnit4::class)
class NewApiBundleProductionFlowTest {

    private lateinit var app: AtlasApplication
    private lateinit var vmStore: ViewModelStore
    private lateinit var vm: AtlasViewModel
    private lateinit var server: MockWebServer

    private val providerIds = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "must not run on main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        vmStore = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::vmStore.isInitialized) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { runCatching { vmStore.clear() } }
        }
        runCatching { server.shutdown() }
        deleteOwnRows()
        providerIds.clear()
    }

    // These pre-existing cases cover only the M2 pricing/quota port. The new NINT-004
    // ProviderPerfBundleFlowTest owns real perf HTTP, persistence and per-model coverage.
    // Explicitly disable only the unrelated controlled port; production never passes this
    // override. All original M2 assertions remain intact, including exact HTTP counts.
    private fun pricingOnlyPerfPort(): NewApiAdapter? = null

    // ===== 1. 同 provider 雙模型同 wave：pricing 計數=1＋各自 exact scope =====

    @Test
    fun concurrentWaveSharesSinglePricingFetchAndEachModelKeepsExactScopeEvidence() {
        val tag = shortId()
        val providerId = "napi-flow-wave-$tag"
        val accountId = "a-flow-wave-$tag"
        val patRef = "pat-flow-wave-$tag"
        val keyRef = "key-flow-wave-$tag"
        val patSecret = "flow-pat-secret-wave-$tag-0123456789"
        val keySecret = "flow-key-secret-wave-$tag-9876543210"
        val endpointId = "e-flow-wave-$tag"
        val modelFree = "flow-free-$tag"
        val modelPaid = "flow-paid-$tag"
        providerIds += providerId

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(modelFree, modelPaid))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, modelFree, source = "USER", nowMillis = 1_000L))
                assertTrue(store.addToMyModels(providerId, modelPaid, source = "USER", nowMillis = 1_001L))

                // 先經真 sync 構造已認證 PRICE head（M2 零燒額守衛的前置證明）。
                enqueueFullSync(pricingJson(mapOf(modelFree to "0", modelPaid to "2")))
                val seedVault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                val seedClient = OkHttpClient.Builder().addInterceptor(RewriteToMock(server)).build()
                val seedAdapter = NewApiAdapter(seedVault, seedClient)
                val seedResult = app.repository.syncNewApi(providerId, seedAdapter)
                assertEquals(2, seedResult.prices.size)
                assertNotNull(seedResult.accountQuota)
                assertNotNull("INFERENCE key 存在時 token quota 不得為 null", seedResult.tokenQuota)
                drainRequests(expected = 4)
                val freeScopePre = PricingScope(providerId, accountId, null, modelFree, "default", endpointId, "CHAT_COMPLETIONS")
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(freeScopePre).latest!!.state)

                // Wave：第一個 pricing 請求抵達即卡住，等第二個模型已排隊才放行。
                val pricingHits = AtomicInteger(0)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                val builds = AtomicInteger(0)
                val gateClient = OkHttpClient.Builder()
                    .addInterceptor(
                        RewriteToMock(
                            server = server,
                            pricingHits = pricingHits,
                            pricingEntered = entered,
                            pricingRelease = release,
                            gateFirstPricingOnly = true,
                        ),
                    )
                    .build()
                val factory = {
                    builds.incrementAndGet()
                    NewApiAdapter(vault, gateClient)
                }
                enqueueFullSync(pricingJson(mapOf(modelFree to "0", modelPaid to "2")))
                val baseline = server.requestCount

                val jobFree = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            credentialId = null,
                            group = null,
                            providerModelId = modelFree,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    }
                }
                withTimeout(TIMEOUT_MS) { entered.await() }
                // 第一腿 HTTP 已在飛行中（deterministic barrier，非 sleep 猜測）。
                assertEquals(1, pricingHits.get())

                val jobPaid = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            credentialId = null,
                            group = null,
                            providerModelId = modelPaid,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    }
                }
                // 同 provider 第二 key 必須排隊（per-provider 鎖），不得在第一腿完成前假成功。
                assertFalse("second model must queue while first pricing is gated", jobPaid.isCompleted)
                assertEquals("queued leader must not burn a second pricing fetch", 1, pricingHits.get())

                release.complete(Unit)
                assertTrue("first model bundle must complete", withTimeout(TIMEOUT_MS) { jobFree.await() })
                assertTrue("queued model bundle must complete after release", withTimeout(TIMEOUT_MS) { jobPaid.await() })

                // 同一 wave 只燒一次 PAT pricing；第二 leader 跳過 adapter 注入但證據照傳。
                assertEquals("same provider wave must share one pricing fetch", 1, pricingHits.get())
                assertEquals("only first leader builds the privileged adapter", 1, builds.get())
                assertEquals("wave must issue exactly one provider-wide sync (4 HTTP)", 4, server.requestCount - baseline)

                val recorded = drainRequests(expected = 4)
                val pricingReq = recorded.single { it.path == "/api/pricing" }
                val tokenReq = recorded.single { it.path == "/api/usage/token/" }
                assertEquals("Bearer $patSecret", pricingReq.getHeader("Authorization"))
                assertEquals("Bearer $keySecret", tokenReq.getHeader("Authorization"))
                assertFalse("PAT and inference secrets must differ", patSecret == keySecret)
                assertTrue("pricing must resolve PAT ref", vault.asked.any { it.startsWith("$patRef@") })
                assertTrue("token quota must resolve inference ref", vault.asked.any { it.startsWith("$keyRef@") })

                // 各自 alias／membership／exact scope head（七維不互借）。
                assertTrue(store.isMyModel(providerId, modelFree))
                assertTrue(store.isMyModel(providerId, modelPaid))
                assertAliasAttached(providerId, modelFree)
                assertAliasAttached(providerId, modelPaid)
                val freeScope = PricingScope(providerId, accountId, null, modelFree, "default", endpointId, "CHAT_COMPLETIONS")
                val paidScope = PricingScope(providerId, accountId, null, modelPaid, "default", endpointId, "CHAT_COMPLETIONS")
                val freeHead = app.database.snapshotFactDao().head(freeScope.storageKey(), SnapshotKind.PRICE.name)
                val paidHead = app.database.snapshotFactDao().head(paidScope.storageKey(), SnapshotKind.PRICE.name)
                assertNotNull("free model exact head missing", freeHead)
                assertNotNull("paid model exact head missing", paidHead)
                assertEquals(listOf(providerId, accountId, null, modelFree, "default", endpointId, "CHAT_COMPLETIONS"), parseScopeKey(freeHead!!.scopeKey))
                assertEquals(listOf(providerId, accountId, null, modelPaid, "default", endpointId, "CHAT_COMPLETIONS"), parseScopeKey(paidHead!!.scopeKey))
                assertNotNull(freeHead.lastGoodSnapshotId)
                assertNotNull(paidHead.lastGoodSnapshotId)
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(freeScope).latest!!.state)
                assertEquals("PAID", app.repository.newApiSnapshots.price(paidScope).latest!!.state)
            }
        }
    }

    // ===== 2. PAT／inference key 分離＋401 不重試（真 HTTP 邊界） =====

    @Test
    fun patAndInferenceKeyAreNotInterchangeableOverHttp() {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val patRef = "pat-flow-sep-${shortId()}"
                val keyRef = "key-flow-sep-${shortId()}"
                val patSecret = "flow-pat-secret-sep-0123456789abcdef"
                val keySecret = "flow-key-secret-sep-fedcba9876543210"
                val rc25 = NewApiVersion.of(NewApiVersion.RC25)
                fun adapter(vault: CredentialAccess) =
                    NewApiAdapter(vault, allowLoopbackHttpForTests = true)
                fun base(): String = server.url("/v1").toString().trimEnd('/')
                fun originOf(base: String): String {
                    val url = base.toHttpUrl()
                    return "${url.scheme}://${url.host}:${url.port}"
                }

                // PAT → pricing；key → token；各走各的 Authorization。
                server.enqueue(MockResponse().setResponseCode(200).setBody(pricingJson(mapOf("sep-model" to "1"))))
                val patVault = CountingVault(mapOf(patRef to patSecret))
                val base = base()
                val pricing = withTimeout(TIMEOUT_MS) {
                    adapter(patVault).fetchPricing(base, AccountPatRef(patRef), rc25)
                } as NewApiResult.Success
                val pricingReq = takeOneRequest()
                assertEquals("GET", pricingReq.method)
                assertEquals("/api/pricing", pricingReq.path)
                assertEquals("Bearer $patSecret", pricingReq.getHeader("Authorization"))
                assertEquals(listOf("$patRef@${originOf(base)}"), patVault.asked.toList())
                assertEquals(1, pricing.value.items.size)

                server.enqueue(MockResponse().setResponseCode(200).setBody(tokenJson()))
                val keyVault = CountingVault(mapOf(keyRef to keySecret))
                val quota = withTimeout(TIMEOUT_MS) {
                    adapter(keyVault).fetchTokenQuota(base, InferenceKeyRef(keyRef), rc25)
                } as NewApiResult.Success
                val tokenReq = takeOneRequest()
                assertEquals("/api/usage/token/", tokenReq.path)
                assertEquals("Bearer $keySecret", tokenReq.getHeader("Authorization"))
                assertEquals(listOf("$keyRef@${originOf(base)}"), keyVault.asked.toList())
                assertNotNull(quota.value)

                // 互換：vault 拒絕 → CREDENTIAL_UNAVAILABLE，且零請求（不燒額度、不洩漏）。
                val before = server.requestCount
                val patOnly = CountingVault(mapOf(patRef to patSecret))
                val keyOnly = CountingVault(mapOf(keyRef to keySecret))
                val patAsKey = withTimeout(TIMEOUT_MS) {
                    adapter(keyOnly).fetchTokenQuota(base, InferenceKeyRef(patRef), rc25)
                } as NewApiResult.Failure
                val keyAsPat = withTimeout(TIMEOUT_MS) {
                    adapter(patOnly).fetchPricing(base, AccountPatRef(keyRef), rc25)
                } as NewApiResult.Failure
                assertEquals(NewApiStatus.CREDENTIAL_UNAVAILABLE, patAsKey.status)
                assertEquals(NewApiStatus.CREDENTIAL_UNAVAILABLE, keyAsPat.status)
                assertEquals("credential refusal must not touch network", before, server.requestCount)
                assertFalse(patAsKey.toString().contains(patSecret))
                assertFalse(keyAsPat.toString().contains(keySecret))

                // 401 不重試：第二份健康回應必須留著（若重試會被消耗）。
                server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"message":"unauthorized"}"""))
                server.enqueue(MockResponse().setResponseCode(200).setBody(pricingJson(mapOf("sep-model" to "1"))))
                val unauthorized = withTimeout(TIMEOUT_MS) {
                    adapter(CountingVault(mapOf(patRef to patSecret))).fetchPricing(base, AccountPatRef(patRef), rc25)
                } as NewApiResult.Failure
                assertEquals(NewApiStatus.HTTP_UNAUTHORIZED, unauthorized.status)
                assertEquals(401, unauthorized.evidence.httpStatus)
                assertEquals(before + 1, server.requestCount)
                assertFalse(unauthorized.toString().contains(patSecret))
            }
        }
    }

    // ===== 3. 第三腿 fail-open：membership／last-good 不刪＋零燒額 =====

    @Test
    fun syncFailureKeepsMembershipAndLastGood() {
        val tag = shortId()
        val providerId = "napi-flow-fail-$tag"
        val accountId = "a-flow-fail-$tag"
        val patRef = "pat-flow-fail-$tag"
        val keyRef = "key-flow-fail-$tag"
        val patSecret = "flow-pat-secret-fail-$tag-0123456789"
        val keySecret = "flow-key-secret-fail-$tag-9876543210"
        val endpointId = "e-flow-fail-$tag"
        val model = "flow-fail-model-$tag"
        providerIds += providerId

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(model))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, model, source = "USER", nowMillis = 2_000L))

                enqueueFullSync(pricingJson(mapOf(model to "0")))
                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                val client = OkHttpClient.Builder().addInterceptor(RewriteToMock(server)).build()
                val good = app.repository.syncNewApi(providerId, NewApiAdapter(vault, client))
                assertEquals(1, good.prices.size)
                drainRequests(expected = 4)

                val scope = PricingScope(providerId, accountId, null, model, "default", endpointId, "CHAT_COMPLETIONS")
                val goodHead = app.database.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)!!
                val goodLastGood = goodHead.lastGoodSnapshotId!!
                val goodInput = app.repository.newApiSnapshots.price(scope).lastGood!!.input!!
                val historyBefore = app.repository.newApiSnapshots.history(scope, SnapshotKind.PRICE).size

                // 破壞 PAT（前置在首次網路請求前 fail-fast）：第三腿應 fail-open，不燒額度。
                app.database.credentialDao().deleteById(patRef)
                val pricingHits = AtomicInteger(0)
                val failingClient = OkHttpClient.Builder().addInterceptor(RewriteToMock(server, pricingHits)).build()
                val failingVault = CountingVault(mapOf(keyRef to keySecret))
                val baselineFail = server.requestCount
                val result = app.repository.enrichMyModelBundle(
                    providerId = providerId,
                    providerModelId = model,
                    accountId = accountId,
                    group = "default",
                    newApiAdapter = NewApiAdapter(failingVault, failingClient),
                    endpointId = endpointId,
                    operation = "CHAT_COMPLETIONS",
                )
                assertTrue("missing PAT must surface static PRICING_NEWAPI", result.stepFailures.contains("PRICING_NEWAPI"))
                assertNull("failed leg must leave typed newApi columns null", result.newApiPricingSynced)
                assertEquals("no network on pre-check failure", 0, pricingHits.get())
                assertEquals("no new HTTP on pre-check failure", baselineFail, server.requestCount)
                assertFalse("static label must not leak secret", result.stepFailures.toString().contains(patSecret))
                assertFalse("static label must not leak URL", result.stepFailures.any { it.contains("http") })

                // membership／last-good／FREE head 全保留（fail-open 不刪真相）。
                assertTrue(store.isMyModel(providerId, model))
                val afterHead = app.database.snapshotFactDao().head(scope.storageKey(), SnapshotKind.PRICE.name)!!
                assertEquals(goodLastGood, afterHead.lastGoodSnapshotId)
                assertEquals(0, goodInput.compareTo(app.repository.newApiSnapshots.price(scope).lastGood!!.input!!))
                assertEquals(historyBefore, app.repository.newApiSnapshots.history(scope, SnapshotKind.PRICE).size)
                assertEquals("FREE_NOW", result.freePriceState)
                assertAliasAttached(providerId, model)
            }
        }
    }

    // ===== 4. 失敗不推進 generation：次模型仍能重試 =====

    @Test
    fun failedLegDoesNotAdvanceGenerationSoNextModelRetries() {
        val tag = shortId()
        val providerId = "napi-flow-retry-$tag"
        val accountId = "a-flow-retry-$tag"
        val patRef = "pat-flow-retry-$tag"
        val keyRef = "key-flow-retry-$tag"
        val patSecret = "flow-pat-secret-retry-$tag-0123456789"
        val keySecret = "flow-key-secret-retry-$tag-9876543210"
        val endpointId = "e-flow-retry-$tag"
        val modelA = "flow-retry-a-$tag"
        val modelB = "flow-retry-b-$tag"
        providerIds += providerId

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(modelA, modelB))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, modelA, source = "USER", nowMillis = 3_000L))
                assertTrue(store.addToMyModels(providerId, modelB, source = "USER", nowMillis = 3_001L))

                // 前置證明 head（否則 VM 零燒額守衛直接跳過，測不到 generation 語意）。
                enqueueFullSync(pricingJson(mapOf(modelA to "0", modelB to "2")))
                val seedVault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                val seedClient = OkHttpClient.Builder().addInterceptor(RewriteToMock(server)).build()
                assertEquals(2, app.repository.syncNewApi(providerId, NewApiAdapter(seedVault, seedClient)).prices.size)
                drainRequests(expected = 4)

                // 首模型：刪 offerings 使 syncNewApi 例外（failOpen→null，不推進 generation）。
                app.database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM offerings WHERE providerId = ?",
                    arrayOf<Any?>(providerId),
                )
                val firstHits = AtomicInteger(0)
                val firstBuilds = AtomicInteger(0)
                val firstVault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                val firstClient = OkHttpClient.Builder().addInterceptor(RewriteToMock(server, firstHits)).build()
                val firstFactory = {
                    firstBuilds.incrementAndGet()
                    NewApiAdapter(firstVault, firstClient)
                }
                assertTrue(
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            providerModelId = modelA,
                            newApiAdapterFactory = firstFactory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    },
                )
                assertEquals("exceptional leg must not touch pricing HTTP", 0, firstHits.get())
                assertTrue(store.isMyModel(providerId, modelA))

                // 補回 offerings，次模型必須仍能注入 adapter 重試（若 generation 被假推進，此處將零請求）。
                reinsertOfferings(providerId, endpointId, listOf(modelA, modelB))
                enqueueFullSync(pricingJson(mapOf(modelA to "0", modelB to "2")))
                val retryHits = AtomicInteger(0)
                val retryBuilds = AtomicInteger(0)
                val retryVault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                val retryClient = OkHttpClient.Builder().addInterceptor(RewriteToMock(server, retryHits)).build()
                val retryFactory = {
                    retryBuilds.incrementAndGet()
                    NewApiAdapter(retryVault, retryClient)
                }
                val baseline = server.requestCount
                assertTrue(
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            providerModelId = modelB,
                            newApiAdapterFactory = retryFactory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    },
                )
                assertEquals("retry must still inject adapter after a failed leg", 1, retryHits.get())
                assertEquals(1, retryBuilds.get())
                assertEquals(4, server.requestCount - baseline)
                drainRequests(expected = 4)
                assertTrue(store.isMyModel(providerId, modelB))
                assertAliasAttached(providerId, modelB)
                val scopeB = PricingScope(providerId, accountId, null, modelB, "default", endpointId, "CHAT_COMPLETIONS")
                assertNotNull(app.database.snapshotFactDao().head(scopeB.storageKey(), SnapshotKind.PRICE.name)?.lastGoodSnapshotId)
            }
        }
    }

    // ===== 5. HTTP pricing 500（空結果非例外）不推進 generation：次模型重試寫 B head =====
    //
    // 真退路鎖定：`/api/pricing` 500 → adapter 回 Failure（未拋例外）→ `syncNewApi` 回
    // `prices=[]`（`newApiPricingSynced=0`）→ VM `> 0` 判斷不推進 generation → 同 provider
    // 次模型必須再次注入 adapter 發出 pricing GET。本測試為循序兩次 VM 呼叫
    //（deterministic queue，無併發競態）；4-call/phase 來自真契約（syncNewApi 的
    // outer caps＋pricing 與 legs 經 own-passport reuse 各自 quota），非編造——
    // 若契約改變，drain 逾時即誠實失敗，不靜默通過。

    @Test
    fun httpPricingFailureDoesNotAdvanceGenerationSoNextModelRetriesAndWritesBHead() {
        val tag = shortId()
        val providerId = "napi-flow-h500-$tag"
        val accountId = "a-flow-h500-$tag"
        val patRef = "pat-flow-h500-$tag"
        val keyRef = "key-flow-h500-$tag"
        val patSecret = "flow-pat-secret-h500-$tag-0123456789"
        val keySecret = "flow-key-secret-h500-$tag-9876543210"
        val endpointId = "e-flow-h500-$tag"
        val modelA = "flow-h500-a-$tag"
        val modelB = "flow-h500-b-$tag"
        providerIds += providerId

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                seedProvider(providerId, accountId, patRef, keyRef, endpointId, listOf(modelA, modelB))
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(providerId, modelA, source = "USER", nowMillis = 4_000L))
                assertTrue(store.addToMyModels(providerId, modelB, source = "USER", nowMillis = 4_001L))

                // 前置證明 head（只含 A；B 尚無 head，gate 已過、語意仍缺 B）。
                enqueueFullSync(pricingJson(mapOf(modelA to "0")))
                val seedVault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                val seedClient = OkHttpClient.Builder().addInterceptor(RewriteToMock(server)).build()
                val seedResult = app.repository.syncNewApi(providerId, NewApiAdapter(seedVault, seedClient))
                assertEquals(1, seedResult.prices.size)
                drainRequests(expected = 4)
                val scopeA = PricingScope(providerId, accountId, null, modelA, "default", endpointId, "CHAT_COMPLETIONS")
                val scopeB = PricingScope(providerId, accountId, null, modelB, "default", endpointId, "CHAT_COMPLETIONS")
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(scopeA).latest!!.state)
                assertNull("B must have no PRICE head before retry", app.database.snapshotFactDao().head(scopeB.storageKey(), SnapshotKind.PRICE.name))
                val aGoodBefore = app.database.snapshotFactDao().head(scopeA.storageKey(), SnapshotKind.PRICE.name)!!.lastGoodSnapshotId!!
                val aInputBefore = app.repository.newApiSnapshots.price(scopeA).lastGood!!.input!!

                val pricingHits = AtomicInteger(0)
                val builds = AtomicInteger(0)
                val vault = CountingVault(mapOf(patRef to patSecret, keyRef to keySecret))
                val client = OkHttpClient.Builder().addInterceptor(RewriteToMock(server, pricingHits)).build()
                val factory = {
                    builds.incrementAndGet()
                    NewApiAdapter(vault, client)
                }

                // Phase 1：pricing 500 → 空結果（0，非例外）。VM 必須 fail-open 回 true。
                enqueuePricingFailureSync()
                val baselineFail = server.requestCount
                assertTrue(
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            providerModelId = modelA,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    },
                )
                assertEquals("failed pricing must still issue exactly one pricing GET", 1, pricingHits.get())
                assertEquals("failed sync still runs account/token legs per contract (4 HTTP)", 4, server.requestCount - baselineFail)
                drainRequests(expected = 4)
                // 空結果不寫 PRICE facts：A last-good 不動，B 仍無 head（未被刪除，而是從未寫入）。
                assertEquals(aGoodBefore, app.database.snapshotFactDao().head(scopeA.storageKey(), SnapshotKind.PRICE.name)!!.lastGoodSnapshotId)
                assertEquals(0, aInputBefore.compareTo(app.repository.newApiSnapshots.price(scopeA).lastGood!!.input!!))
                assertNull("B must still have no PRICE head after failed pricing", app.database.snapshotFactDao().head(scopeB.storageKey(), SnapshotKind.PRICE.name))
                assertTrue(store.isMyModel(providerId, modelA))

                // Phase 2：同 provider 次模型必須再次注入 adapter（若 0 被誤判成功，此處將零請求）。
                enqueueFullSync(pricingJson(mapOf(modelB to "2")))
                val baselineRetry = server.requestCount
                assertTrue(
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = providerId,
                            accountId = accountId,
                            providerModelId = modelB,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    },
                )
                assertEquals("retry must issue a second pricing GET", 2, pricingHits.get())
                assertEquals("retry injected adapter again (no false advance)", 2, builds.get())
                assertEquals("retry sync runs full legs per contract (4 HTTP)", 4, server.requestCount - baselineRetry)
                val recorded = drainRequests(expected = 4)
                assertEquals(1, recorded.count { it.path == "/api/pricing" })
                // B head 真寫入（exact scope＋last-good＋PAID），非 fixture 斷言。
                val bHead = app.database.snapshotFactDao().head(scopeB.storageKey(), SnapshotKind.PRICE.name)
                assertNotNull("retry must write B PRICE head", bHead)
                assertEquals(
                    listOf(providerId, accountId, null, modelB, "default", endpointId, "CHAT_COMPLETIONS"),
                    parseScopeKey(bHead!!.scopeKey),
                )
                assertNotNull(bHead.lastGoodSnapshotId)
                assertEquals("PAID", app.repository.newApiSnapshots.price(scopeB).latest!!.state)
                assertTrue(store.isMyModel(providerId, modelB))
                assertAliasAttached(providerId, modelB)
            }
        }
    }

    // ===== harness（fixture 僅本檔，不當產品資料） =====

    private suspend fun seedProvider(
        providerId: String,
        accountId: String,
        patRef: String,
        keyRef: String,
        endpointId: String,
        models: List<String>,
    ) {
        val db = app.database
        val baseHost = "https://$providerId.example.com"
        db.providerDao().insert(ProviderEntity(providerId = providerId, name = "P $providerId"))
        db.accountDao().insert(AccountEntity(accountId, providerId))
        db.credentialDao().insert(CredentialEntity(patRef, accountId))
        db.credentialDao().insert(CredentialEntity(keyRef, accountId))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = endpointId,
                providerId = providerId,
                baseUrl = "$baseHost/v1",
                endpointUrl = "$baseHost/v1/chat/completions",
                protocol = "CHAT_COMPLETIONS",
            ),
        )
        for (m in models) {
            db.offeringDao().insert(
                OfferingEntity(
                    id = "off:$providerId:$m",
                    providerId = providerId,
                    endpointId = endpointId,
                    providerModelId = m,
                    manual = false,
                    source = "OFFICIAL",
                    hidden = false,
                ),
            )
        }
        val cfg = ProviderManualConfig(
            providerId = providerId,
            workflowId = "wf-$providerId-flow",
            authMode = AuthMode.BEARER,
            credentials = listOf(
                SafeCredentialRef(patRef, "PAT", CredentialRole.ACCOUNT),
                SafeCredentialRef(keyRef, "KEY", CredentialRole.INFERENCE),
            ),
        )
        db.providerConfigDao().insert(cfg.toEntity())
    }

    private suspend fun reinsertOfferings(providerId: String, endpointId: String, models: List<String>) {
        for (m in models) {
            runCatching {
                app.database.offeringDao().insert(
                    OfferingEntity(
                        id = "off:$providerId:$m",
                        providerId = providerId,
                        endpointId = endpointId,
                        providerModelId = m,
                        manual = false,
                        source = "OFFICIAL",
                        hidden = false,
                    ),
                )
            }
        }
    }

    private suspend fun assertAliasAttached(providerId: String, modelId: String) {
        val row = withTimeout(TIMEOUT_MS) {
            var r = app.database.providerModelAliasDao().get(providerId, modelId)
            while (r == null) {
                yield()
                r = app.database.providerModelAliasDao().get(providerId, modelId)
            }
            r!!
        }
        assertEquals(MappingSource.NORMALIZATION.name, row.source)
        assertEquals(MappingConfidence.LOW.name, row.confidence)
        assertFalse(row.verified)
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    private fun statusJson(): String =
        """{"success":true,"message":"","data":{"version":"v1.0.0-rc.25","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

    private fun pricingJson(models: Map<String, String>): String {
        val items = models.entries.joinToString(",") { (m, ratio) ->
            """{"model_name":"$m","quota_type":0,"model_ratio":$ratio,"completion_ratio":1,"enable_groups":["all"],"supported_endpoint_types":["openai"]}"""
        }
        return """{"success":true,"message":"","data":[$items],"group_ratio":{"default":1},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"flow-v1"}"""
    }

    private fun accountJson(): String =
        """{"success":true,"message":"","data":{"quota":1000000,"used_quota":250000,"request_count":42,"group":"default","role":1,"status":1}}"""

    private fun tokenJson(): String =
        """{"code":true,"message":"ok","data":{"object":"token_usage","name":"flow-key","total_granted":1000,"total_used":400,"total_available":600,"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""

    /** 一次完整 provider-wide sync 的 4 個 HTTP（status／pricing／account／token；own-passport reuse 自命中）。 */
    private fun enqueueFullSync(pricingBody: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusJson()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(pricingBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody(accountJson()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tokenJson()))
    }

    /** pricing 500 的失敗 sync：同樣 4 個 HTTP（pricing Failure 不中斷 account／token 腿；passport 仍由 certified outer caps 建立）。 */
    private fun enqueuePricingFailureSync() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusJson()))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"internal"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(accountJson()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tokenJson()))
    }

    private fun takeOneRequest(): RecordedRequest =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request" }

    private fun drainRequests(expected: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(expected)
        repeat(expected) {
            out += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request #$it (expected $expected)" }
        }
        return out
    }

    private class CountingVault(secrets: Map<String, String>) : CredentialAccess {
        private val backing = secrets.toMap()
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            return backing[credentialId] ?: throw IllegalStateException("vault-refused")
        }
    }

    /**
     * 測試傳輸接縫：把已通過生產驗證的 `https` request 改寫為 loopback MockWebServer 真 socket。
     * Counting＋deterministic gate（首個 pricing 可卡住等第二模型排隊）；其餘請求直通。
     */
    private class RewriteToMock(
        private val server: MockWebServer,
        private val pricingHits: AtomicInteger = AtomicInteger(0),
        private val pricingEntered: CompletableDeferred<Unit>? = null,
        private val pricingRelease: CompletableDeferred<Unit>? = null,
        private val gateFirstPricingOnly: Boolean = false,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val path = orig.url.encodedPath
            if (path == "/api/pricing") {
                val n = pricingHits.incrementAndGet()
                if (pricingEntered != null && n == 1 && !pricingEntered.isCompleted) {
                    pricingEntered.complete(Unit)
                }
                val gate = pricingRelease
                if (gate != null && (!gateFirstPricingOnly || n == 1)) {
                    runBlocking { gate.await() }
                }
            }
            val rewritten = orig.newBuilder().url(server.url(path).toString()).build()
            return chain.proceed(rewritten)
        }
    }

    /**
     * 本檔 fixture 精確清理（teardown correctness）。
     *
     * - `scopeKey` 是長度前綴編碼（見 [PricingScope.storageKey]），`LIKE 'pid|%'`
     *   永遠命不中：必須經真 [parseScopeKey] 查出自己的 `(scopeKey, kind)` 再精確刪除，
     *   不得 wildcard 波及他人。
     * - `canonicalIds` 必須在刪 aliases 之前查（刪後永遠取不到）；FK 順序為先刪引用
     *  （aliases）再刪被引用（canonical，且僅 orphan 才刪）。
     * - `price_snapshots`／`quota_snapshots` 有 `providerId` 欄：精確 `IN`，不用錯欄
     *   `scope LIKE`（原欄名不存在且會誤傷）。
     * - NINT-005A：`provider_account_usage_facts` FK → `evidence`（RESTRICT）。本檔經真
     *   `repo.syncNewApi` 會寫 usage facts，所以 evidence 之前必須先經共用 test-only helper
     *   [deleteOwnProviderAccountUsageRows] 精確刪自己的 usage facts／heads，否則 teardown
     *   會被 FK RESTRICT 擋下；順序是 usage facts → snapshot facts → … → evidence。
     * - 失敗即拋出報告（不吞例外當成功）；fixture 全 UUID 唯一 scope，殘留不影響他人，
     *   但必須可見。
     */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val db = app.database
        // 自己的 snapshot (scopeKey, kind)：真 parseScopeKey 過濾 provider 維。
        val ownHeads: List<Pair<String, String>> = runBlocking {
            val out = ArrayList<Pair<String, String>>()
            for (kind in SnapshotKind.values()) {
                for (h in db.snapshotFactDao().headsByKind(kind.name)) {
                    val parts = parseScopeKey(h.scopeKey) ?: continue
                    if (parts.getOrNull(0) in providerIds) out += h.scopeKey to h.kind
                }
            }
            out
        }
        // 自己的 canonicalIds：必須在刪 aliases 之前查。
        val canonicalIds: List<String> = runBlocking {
            val ids = LinkedHashSet<String>()
            val list = providerIds.joinToString(",") { "?" }
            val args = providerIds.toTypedArray<Any?>()
            db.openHelper.readableDatabase.query(
                "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN ($list)",
                args,
            ).use { c -> while (c.moveToNext()) ids += c.getString(0) }
            ids.toList()
        }
        // FK 順序精確刪除：facts→heads→price/quota→evidence→子表→aliases→providers→orphan canonical。
        val writable = db.openHelper.writableDatabase
        writable.beginTransaction()
        try {
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_facts WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_heads WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            val list = providerIds.joinToString(",") { "?" }
            val args = providerIds.toTypedArray<Any?>()
            deleteOwnProviderAccountUsageRows(writable, providerIds)
            writable.execSQL("DELETE FROM price_snapshots WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM quota_snapshots WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM evidence WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM samples WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM my_models WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", args)
            writable.execSQL(
                "DELETE FROM credentials WHERE accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))",
                args,
            )
            writable.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($list)", args)
            writable.execSQL("DELETE FROM providers WHERE providerId IN ($list)", args)
            if (canonicalIds.isNotEmpty()) {
                val ph = canonicalIds.joinToString(",") { "?" }
                writable.execSQL(
                    "DELETE FROM canonical_models WHERE canonicalModelId IN ($ph) " +
                        "AND canonicalModelId NOT IN (SELECT canonicalModelId FROM provider_model_aliases)",
                    canonicalIds.toTypedArray<Any?>(),
                )
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
