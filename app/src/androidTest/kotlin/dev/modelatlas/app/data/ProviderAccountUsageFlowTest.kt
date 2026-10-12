package dev.modelatlas.app.data

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiDomainMapper
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
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
 * NINT-005A provider-reported **account** request count 真 production flow 證據
 * （device androidTest；本檔唯一新增）。
 *
 * 覆蓋 TRACEABILITY NINT-005A／Contract §2（Local／Provider／External 分離）、
 * §6.4（provider-reported account usage 專屬槽）、§23.3（加入 My Models 自動
 * enrichment）；PRODUCT_SPEC §10／§12／§18；AGENTS 永久鐵律（Unknown≠0／Free、
 * 來源分離、safe ref、counter semantics 不猜）。
 *
 * 真鏈路層級（不誇大，逐 case 如實）：
 * - Case 1／2／4 走**真 VM**（[AtlasViewModel.maybeEnrichMyModel]，真 Room／真 Main
 *   barrier）→ 真 `AtlasRepository.enrichMyModelBundle` → 真
 *   `AtlasRepository.syncNewApi` → 真 `NewApiSnapshotStore.syncAccountQuota` →
 *   真 [AccountQuotaObserver] hook → 真 `ProviderAccountUsageStore` → 真
 *   `NewApiAdapter.fetchAccountQuota` → loopback 真 socket（MockWebServer）→ 真 Room
 *   `provider_account_usage_facts`／`provider_account_usage_heads`；讀回一律用真
 *   typed reader（`repository.providerAccountUsage.observation`／`history`）與真
 *   `NewApiSyncResult.accountUsage` 回傳值。
 * - Case 3／5 走**真 repository 腿**（`AtlasRepository.syncNewApi` 本身就是 pricing 腿
 *   的 production caller；Case 5 另以真 constructor injection 注入失敗／取消
 *   observer）。繞開 VM 自身的 provider-wave generation 去重（M2）才能在**同一個**
 *   provider 上連續觀察多輪真 HTTP count 語意——該去重本身已被 Case 1 的
  *   `full 4 requests` 與 `user/self == 1` 斷言鎖住。
 *
 * 傳輸說明（非掩飾，review 必讀）：
 * - DB 的 endpoint baseUrl 維持 `https://…-usage-<uuid>.example.com/v1`（生產
 *   [dev.modelatlas.core.network.EndpointPolicy] HTTPS gate 要求）；`http` 在
 *   `syncNewApi`／store 前置即 fail-fast，不燒額度。
 * - 測試端 OkHttp [UsageLoopback] interceptor 只做**傳輸改寫**：已通過生產驗證的
 *   `https` request 只換 scheme/host/port 為 loopback 真 socket，path／query／
 *   Authorization／method 原樣保留並由 MockWebServer 真實記錄。DB 不存 loopback、
 *   不存 secret；改寫只存在測試傳輸層。
 * - credential 為 `CountingVault(safeRef -> synthetic fake secret)`，只對 loopback
 *   生效；本檔斷言 `/api/user/self` 只可能帶**管理 PAT 自己的帳號**所屬 fake
 *   secret，並明確不是 selected inference key 的 secret（account total ≠ key usage）。
 *
 * **Fixture 資料聲明**：本檔所有 JSON／數值皆為 rc.25（`QuantumNous/new-api` tag
 * `v1.0.0-rc.25` = `f1164142`）**source-derived synthetic** 測試 fixture，僅存在
 * 本檔、僅供 loopback 傳輸，**不是 live 產品資料**、不進任何產品槽。斷言一律打在
 * 經真 adapter 解析＋真 store 落庫後的 DB；account count／quota／token／perf 各腿
 * 使用互異數值（任何串欄或借用都會失敗）。
 *
 * 鐵律守衛（逐 case 精準命中，不做全域假設）：
 * - `request_count` **缺席＝Unknown**，絕不補 0／補 Free；`0` 是站方真值不是缺席。
 * - counter semantics 恆 `UNSPECIFIED`：本檔不猜累計／窗口／rate／delta，也不把
 *   新值小於舊值判成 reset（reset 語意屬後續片）。
 * - account total 只落 provider×account scope：不產生 per-key／per-model／per-group
 *   usage；PAT safe ref 只作 provenance，不進 scope。
 * - usage 觀察永不建立 local `samples`、永不寫 My Uptime／external／perf 槽。
 * - observer 失敗 fail-open（quota／token／typed result 全保留）；取消必傳播，且
 *   已 commit 的 quota 事實不得回捲。
 *
 * 隔離：每個 case 唯一 UUID provider fixture；所有 DB 斷言皆 provider／scope 精確
 * 查詢。teardown 依 FK RESTRICT 順序（**usage facts → usage heads → perf facts →
 * perf heads → snapshot facts/heads → price/quota snapshots → samples / my_models /
 * configs / credentials / offerings / endpoints / accounts / aliases → evidence →
 * providers → orphan canonical**）；usage 表以精確 provider 維＋由自身 facts 反查的
 * `(scopeKey, kind)` 清理（共用 helper，絕不用 `LIKE`）。
 *
 * Barrier／timeout：全部 `withTimeout(TIMEOUT_MS)`（15s，與既有 flow test 同值，
 * 未延長）；零 `sleep`、零 expectation 放寬。Case 3 用 controlled clock 讓每輪
 * `observedAt` 完全可預期，故 append-only／last-good 時序斷言是**精確值**而非時序
 * 巧合。
 */
@RunWith(AndroidJUnit4::class)
class ProviderAccountUsageFlowTest {

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

    // ===== 1. 真 VM wave：user/self 恰一次、完整 6 請求不變、account total 一次一列 =====

    @Test
    fun vmEnrichmentRecordsOneAccountTotalPerSyncInsideTheUnchangedSixCallSequence() {
        val tag = shortId()
        val fixture = seedFixture("wave", tag, listOf(modelName("wave", tag)))
        val accountScope = NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.accountId)
        val usage = app.repository.providerAccountUsage

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = UsageDispatcher(
                    accountBody = accountJson(requestCount = COUNT_TOTAL),
                    pricingModels = { mapOf(fixture.model to "0") },
                )
                assertTrue(
                    MyModelsStore(app.database).addToMyModels(fixture.providerId, fixture.model, source = "USER", nowMillis = 11_000L),
                )
                val vault = CountingVault(fixture.secrets())

                // 前置：真 syncNewApi 建立 certified 官方 evidence 與 PRICE／ACCOUNT_QUOTA／
                // TOKEN_QUOTA heads（VM 零燒額守衛的唯一 DB 真相來源），並回第一筆 typed 結果。
                val seedLoopback = UsageLoopback(server)
                val seeded = app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals("the seed sync runs the 4-call provider-wide contract (own-passport self-hit)", 4, seedLoopback.total())
                assertEquals("the seed sync must ask the station account exactly once", 1, seedLoopback.account.get())
                assertEquals("own-passport reuse leaves a single outer capabilities probe", 1, seedLoopback.status.get())
                assertEquals(1, seedLoopback.pricing.get())
                assertEquals(1, seedLoopback.token.get())
                val seedUsage = requireNotNull(seeded.accountUsage) { "the sync must return the typed usage read" }
                val seedLatest = requireNotNull(seedUsage.latest) { "the account leg must record a usage fact" }
                assertUsageFact(seedLatest, fixture, COUNT_TOTAL)
                assertEquals(seedLatest.evidenceId, seedUsage.lastGood?.evidenceId)
                assertEquals(
                    "the returned typed read must equal the stored head",
                    seedLatest.evidenceId,
                    usage.observation(fixture.providerId, fixture.accountId).latest?.evidenceId,
                )
                assertUsageEvidenceRow(seedLatest, NewApiSnapshotStore.ACCOUNT_SOURCE, "OK")
                val seedQuota = app.repository.newApiSnapshots.quota(accountScope, SnapshotKind.ACCOUNT_QUOTA)
                assertEquals(ACCOUNT_QUOTA_OK, seedQuota.latest?.status)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, seedQuota.latest?.state)
                assertEquals(
                    "usedQuota keeps its existing ACCOUNT_QUOTA slot; usage must not duplicate it",
                    USED_QUOTA,
                    seedQuota.latest?.usedRaw,
                )
                drainRequests(expected = 4)

                // Wave：真 VM → 真 repository → 真 adapter factory → loopback。
                val loopback = UsageLoopback(server)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val pricingBuilds = AtomicInteger(0)
                val perfBuilds = AtomicInteger(0)
                val baselineRequests = server.requestCount
                val enriched = vm.maybeEnrichMyModel(
                    providerId = fixture.providerId,
                    accountId = fixture.accountId,
                    credentialId = null,
                    group = null,
                    providerModelId = fixture.model,
                    lmspeedFetcher = null,
                    newApiAdapterFactory = {
                        pricingBuilds.incrementAndGet()
                        NewApiAdapter(vault, client)
                    },
                    // Perf gate 明確關閉（既有 flow test 同一 controlled port 用法）：本 case
                    // 只驗 count 腿既不偷開 perf 端點、也不改變既有 6 請求序列。
                    newApiPerfAdapterFactory = {
                        perfBuilds.incrementAndGet()
                        null
                    },
                )
                assertTrue("the real VM enrichment wave must complete", enriched)

                // 真 HTTP 契約：整條 provider-wide 序列是 4 個請求（own-passport 復用兩腿
                // caps；outer status 保留為 passport-creation 觀察）、user/self 仍恰一次。
                assertEquals("the account count must not add any HTTP call", 4, server.requestCount - baselineRequests)
                assertEquals(4, loopback.total())
                assertEquals("My Models enrichment must ask the station account exactly once", 1, loopback.account.get())
                assertEquals(1, loopback.status.get())
                assertEquals(1, loopback.pricing.get())
                assertEquals(1, loopback.token.get())
                assertEquals("no inference request may be issued by the usage leg", 0, loopback.inference.get())
                assertEquals("a closed perf port must stay closed", 0, loopback.perfSummary.get() + loopback.perfQuery.get())
                assertEquals("exactly one pricing adapter per wave leader", 1, pricingBuilds.get())
                assertEquals("exactly one perf port resolution per wave leader", 1, perfBuilds.get())
                val recorded = drainRequests(expected = 4)
                assertEquals(1, recorded.count { it.encodedPath() == "/api/status" })
                assertEquals(1, recorded.count { it.encodedPath() == "/api/pricing" })
                assertEquals(1, recorded.count { it.encodedPath() == "/api/user/self" })
                assertEquals(1, recorded.count { it.encodedPath() == "/api/usage/token/" })
                assertTrue("no local inference leg may run", recorded.none { it.encodedPath().startsWith("/v1/") })

                // 認證邊界：account total 來自**管理 PAT 自己的帳號**，不是被選中的 inference key。
                val accountRequest = recorded.single { it.encodedPath() == "/api/user/self" }
                assertEquals("Bearer ${fixture.patSecret}", accountRequest.getHeader("Authorization"))
                assertFalse(
                    "the selected inference key secret must never authenticate the account leg",
                    accountRequest.getHeader("Authorization")!!.contains(fixture.keySecret),
                )
                val tokenRequest = recorded.single { it.encodedPath() == "/api/usage/token/" }
                assertEquals("Bearer ${fixture.keySecret}", tokenRequest.getHeader("Authorization"))
                assertTrue(
                    "the account leg must resolve the account PAT safe ref",
                    vault.asked.any { it.startsWith("${fixture.patRef}@") },
                )

                // ---- 真 Room 新表：account total 每輪 sync 一列，無 per-key/model/group 維 ----
                val observed = usage.observation(fixture.providerId, fixture.accountId)
                val waveLatest = requireNotNull(observed.latest) { "the wave must record the account total" }
                assertUsageFact(waveLatest, fixture, COUNT_TOTAL)
                assertEquals(
                    "the newest observation advances latest and last-good",
                    waveLatest.evidenceId,
                    observed.lastGood?.evidenceId,
                )
                assertTrue("a newer wave must be a newer observation", waveLatest.observedAt >= seedLatest.observedAt)
                assertUsageEvidenceRow(waveLatest, NewApiSnapshotStore.ACCOUNT_SOURCE, "OK")
                val history = usage.history(fixture.providerId, fixture.accountId)
                assertEquals("one account-total row per sync (seed + wave)", 2, history.size)
                assertEquals(
                    "history is newest first",
                    listOf(waveLatest.evidenceId, seedLatest.evidenceId),
                    history.map { it.evidenceId },
                )
                assertEquals(
                    "every row lives in the single provider×account scope",
                    setOf(providerAccountUsageScopeKey(fixture.providerId, fixture.accountId)),
                    history.map { it.scopeKey }.toSet(),
                )
                assertEquals(
                    "the kind slot is dedicated to account usage",
                    setOf(ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name),
                    history.map { it.kind }.toSet(),
                )
                assertEquals(
                    "exactly one usage row per sync, never one per key/model/group",
                    2,
                    countUsageRows(fixture.providerId),
                )
                assertTrue("no usage row may carry the inference key as provenance", history.none { it.credentialId == fixture.keyRef })
                assertTrue("no usage row may carry the model", history.none { it.toString().contains(fixture.model) })

                // 跨 scope 隔離：別的 account／別的 provider 都不得命中本 provider 的觀測。
                assertNull(usage.observation(fixture.providerId, "a-usage-absent-$tag").latest)
                assertNull(usage.observation("usage-absent-provider-$tag", fixture.accountId).latest)
                assertTrue(usage.history("usage-absent-provider-$tag", fixture.accountId).isEmpty())

                // 永不影響既有槽：local telemetry／membership／quota／token／perf。
                assertEquals("the account count must never create local samples", 0, countRows("samples", fixture.providerId))
                assertTrue(MyModelsStore(app.database).isMyModel(fixture.providerId, fixture.model))
                val tokenQuota = app.repository.newApiSnapshots.quota(
                    PricingScope(
                        fixture.providerId,
                        fixture.accountId,
                        fixture.keyRef,
                        "",
                        null,
                        "",
                        NewApiDomainMapper.OPERATION_GLOBAL,
                    ),
                    SnapshotKind.TOKEN_QUOTA,
                )
                assertEquals("OK", tokenQuota.latest?.status)
                assertEquals(TOKEN_USED, tokenQuota.latest?.usedRaw)
                assertEquals(
                    "the account total must not overwrite the account quota slot",
                    USED_QUOTA,
                    app.repository.newApiSnapshots.quota(accountScope, SnapshotKind.ACCOUNT_QUOTA).latest?.usedRaw,
                )
                assertNoPerfFacts(fixture.providerId)
                assertUsageCountAbsentFromOtherSlots(fixture.providerId, listOf(COUNT_TOTAL.toString()))
            }
        }
    }

    // ===== 2. 真 VM wave 帶真 perf 腿：count 槽與 quota／token／perf／local 槽互不冒充 =====

    @Test
    fun vmEnrichmentKeepsPerfQuotaTokenAndLocalSlotsSeparatedFromTheAccountCount() {
        val tag = shortId()
        val fixture = seedFixture("slots", tag, listOf(modelName("slots", tag)))
        val accountScope = NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.accountId)
        val usage = app.repository.providerAccountUsage

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = UsageDispatcher(
                    accountBody = accountJson(requestCount = COUNT_TOTAL),
                    pricingModels = { mapOf(fixture.model to "0") },
                    perfSummaryBody = perfSummaryJson(fixture.model, PERF_LATENCY_MS),
                    perfQueryBody = perfQueryJson(fixture.model, PERF_QUERY_TS),
                )
                assertTrue(
                    MyModelsStore(app.database).addToMyModels(fixture.providerId, fixture.model, source = "USER", nowMillis = 12_000L),
                )
                val vault = CountingVault(fixture.secrets())

                // 前置真 sync：certification＋第一筆 account total（與 Case 1 同一前置）。
                app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(UsageLoopback(server)).build()),
                )
                drainRequests(expected = 4)

                // Wave：perf controlled port 注入真 adapter → perf 腿照既有 NINT-004 契約真跑。
                val loopback = UsageLoopback(server)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val baselineRequests = server.requestCount
                assertTrue(
                    vm.maybeEnrichMyModel(
                        providerId = fixture.providerId,
                        accountId = fixture.accountId,
                        credentialId = null,
                        group = null,
                        providerModelId = fixture.model,
                        lmspeedFetcher = null,
                        newApiAdapterFactory = { NewApiAdapter(vault, client) },
                        newApiPerfAdapterFactory = { NewApiAdapter(vault, client) },
                    ),
                )
                // 4（sync own-passport 復用）＋ perf 的 summary／query（perf caps 經 own
                // passport 復用，不另燒 status）。
                assertEquals(6, server.requestCount - baselineRequests)
                assertEquals("still exactly one station account call per sync", 1, loopback.account.get())
                assertEquals(1, loopback.perfSummary.get())
                assertEquals(1, loopback.perfQuery.get())
                drainRequests(expected = 6)

                // ---- 三個數值槽並存，互不覆蓋（account total ≠ key usage ≠ quota ≠ perf） ----
                val usageLatest = requireNotNull(usage.observation(fixture.providerId, fixture.accountId).latest)
                assertUsageFact(usageLatest, fixture, COUNT_TOTAL)
                val accountQuota = app.repository.newApiSnapshots.quota(accountScope, SnapshotKind.ACCOUNT_QUOTA)
                assertEquals(USED_QUOTA, accountQuota.latest?.usedRaw)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, accountQuota.latest?.state)
                val tokenQuota = app.repository.newApiSnapshots.quota(
                    PricingScope(
                        fixture.providerId,
                        fixture.accountId,
                        fixture.keyRef,
                        "",
                        null,
                        "",
                        NewApiDomainMapper.OPERATION_GLOBAL,
                    ),
                    SnapshotKind.TOKEN_QUOTA,
                )
                assertEquals(TOKEN_USED, tokenQuota.latest?.usedRaw)
                assertTrue(
                    "the account total must be its own datum, never the token, quota or perf number",
                    COUNT_TOTAL !in setOfNotNull(TOKEN_USED, USED_QUOTA, ACCOUNT_QUOTA_RAW, PERF_LATENCY_MS),
                )
                assertTrue(
                    "no usage row may repeat the token, quota or perf raw values",
                    usage.history(fixture.providerId, fixture.accountId).none {
                        it.requestCountRaw in setOfNotNull(TOKEN_USED, USED_QUOTA, PERF_LATENCY_MS)
                    },
                )

                // ---- Perf gate 不變：provider perf 仍照既有 summary／query 落自己的表 ----
                val perf = app.repository.providerPerf
                val groupScope = perf.observation(fixture.providerId, fixture.model, PERF_GROUP, PERF_HOURS, ProviderPerfKind.GROUP)
                val groupGood = requireNotNull(groupScope.lastGood) { "the perf leg must still persist its group scope" }
                assertEquals("OK", groupGood.status)
                assertEquals(PERF_LATENCY_MS, groupGood.avgLatencyMs!!)
                val summaryScope = perf.observation(fixture.providerId, fixture.model, null, PERF_HOURS, ProviderPerfKind.SUMMARY)
                val summaryGood = requireNotNull(summaryScope.lastGood) { "the perf leg must still persist its summary scope" }
                assertEquals(PERF_LATENCY_MS, summaryGood.avgLatencyMs!!)
                assertEquals(
                    "the perf leg keeps its own evidence source, never the account usage source",
                    ProviderPerfStore.PERF_SUMMARY_SOURCE,
                    app.database.evidenceDao().getById(summaryGood.evidenceId)!!.source,
                )

                // ---- local／membership 不變；provider-reported count 不進 local telemetry ----
                assertEquals("station metrics must never become local samples", 0, countRows("samples", fixture.providerId))
                assertTrue(MyModelsStore(app.database).isMyModel(fixture.providerId, fixture.model))
                assertEquals("one account-total row per sync (seed + wave)", 2, countUsageRows(fixture.providerId))
                assertUsageCountAbsentFromOtherSlots(fixture.providerId, listOf(COUNT_TOTAL.toString()))
            }
        }
    }

    // ===== 3. typed 回傳值矩陣：zero／缺席／負數／大數／caps 失敗各自語意分離 =====

    @Test
    fun typedSyncResultKeepsZeroMissingNegativeHugeAndCapsFailureApartWithoutInventingUnknown() {
        val tag = shortId()
        val model = modelName("matrix", tag)
        val fixture = seedFixture("matrix", tag, listOf(model))
        val usage = app.repository.providerAccountUsage
        val accountScope = NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.accountId)

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val vault = CountingVault(fixture.secrets())
                // controlled clock：六輪 phase 的 observedAt 完全可預期，時序斷言是精確值。
                val clock = ScriptedClock(PHASE_ONE_MS)
                val dispatcher = UsageDispatcher(
                    accountBody = accountJson(requestCount = BASE_COUNT),
                    pricingModels = { mapOf(model to "0") },
                )
                server.dispatcher = dispatcher

                // Phase 1：真 good count → typed result 與 DB head 同一 evidence。
                val loopback1 = UsageLoopback(server)
                val first = app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(loopback1).build(), clock),
                )
                assertEquals(4, loopback1.total())
                drainRequests(expected = 4)
                val firstFact = requireNotNull(first.accountUsage?.latest) { "phase 1 must return a typed usage read" }
                assertUsageFact(firstFact, fixture, BASE_COUNT)
                assertEquals(PHASE_ONE_MS, firstFact.observedAt)
                assertEquals(
                    "the returned typed read must match the stored latest row",
                    firstFact.evidenceId,
                    usage.observation(fixture.providerId, fixture.accountId).latest?.evidenceId,
                )
                assertEquals(
                    "usedQuota stays in its existing ACCOUNT_QUOTA slot and is never duplicated",
                    USED_QUOTA,
                    app.repository.newApiSnapshots.quota(accountScope, SnapshotKind.ACCOUNT_QUOTA).latest?.usedRaw,
                )
                assertEquals(firstFact.evidenceId, first.accountUsage?.lastGood?.evidenceId)

                // Phase 2：`request_count = 0` 且 quota 欄缺席 → 0 是真值不是 Unknown；
                // quota 缺席不得影響 count 自身的 good 判定（兩個槽各自獨立）。
                dispatcher.accountBody = accountZeroCountJson()
                clock.set(PHASE_TWO_MS)
                val loopback2 = UsageLoopback(server)
                val zero = app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(loopback2).build(), clock),
                )
                assertEquals(4, loopback2.total())
                drainRequests(expected = 4)
                val zeroFact = requireNotNull(zero.accountUsage?.latest)
                assertEquals("zero is an observed value, never Unknown", 0L, zeroFact.requestCountRaw)
                assertEquals("OK", zeroFact.status)
                assertEquals(200, zeroFact.httpStatus)
                assertTrue("a 2xx account response is an observed account endpoint", zeroFact.accountEndpointObserved)
                assertEquals(PHASE_TWO_MS, zeroFact.observedAt)
                assertEquals("a zero count is still a good observation", zeroFact.evidenceId, zero.accountUsage?.lastGood?.evidenceId)
                val quotaAfterZero = app.repository.newApiSnapshots.quota(accountScope, SnapshotKind.ACCOUNT_QUOTA)
                assertEquals("an absent quota stays UNKNOWN, never 0", QuotaState.UNKNOWN.name, quotaAfterZero.latest?.state)
                assertNull("an absent used_quota must stay null", quotaAfterZero.latest?.usedRaw)

                // Phase 3：count 欄缺席 → Unknown 留 null（不補 0），且不推進 last-good。
                dispatcher.accountBody = accountMissingCountJson()
                clock.set(PHASE_THREE_MS)
                val loopback3 = UsageLoopback(server)
                val missing = app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(loopback3).build(), clock),
                )
                assertEquals(4, loopback3.total())
                drainRequests(expected = 4)
                val missingFact = requireNotNull(missing.accountUsage?.latest)
                assertNull("a missing count must stay Unknown, never be filled with 0", missingFact.requestCountRaw)
                assertEquals("OK", missingFact.status)
                assertEquals(PHASE_THREE_MS, missingFact.observedAt)
                assertEquals("an Unknown count must not steal last-good", zeroFact.evidenceId, missing.accountUsage?.lastGood?.evidenceId)
                assertEquals(
                    "Unknown≠0：last-good keeps the observed zero value",
                    0L,
                    usage.observation(fixture.providerId, fixture.accountId).lastGood?.requestCountRaw,
                )

                // Phase 4：負數 → MALFORMED 標記＋null count，last-good 不動。
                dispatcher.accountBody = accountJson(requestCount = NEGATIVE_COUNT)
                clock.set(PHASE_FOUR_MS)
                val loopback4 = UsageLoopback(server)
                val negative = app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(loopback4).build(), clock),
                )
                assertEquals(4, loopback4.total())
                drainRequests(expected = 4)
                val negativeFact = requireNotNull(negative.accountUsage?.latest)
                assertEquals(NewApiStatus.MALFORMED.name, negativeFact.status)
                assertNull("a negative count is never persisted as a value", negativeFact.requestCountRaw)
                assertEquals(PHASE_FOUR_MS, negativeFact.observedAt)
                assertEquals(
                    "a malformed observation must not move last-good",
                    zeroFact.evidenceId,
                    negative.accountUsage?.lastGood?.evidenceId,
                )

                // Phase 5：Long 上界附近的真值 → 精確保存（不經 double、不截斷）。
                dispatcher.accountBody = accountJson(requestCount = HUGE_COUNT)
                clock.set(PHASE_FIVE_MS)
                val loopback5 = UsageLoopback(server)
                val huge = app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(loopback5).build(), clock),
                )
                assertEquals(4, loopback5.total())
                drainRequests(expected = 4)
                val hugeFact = requireNotNull(huge.accountUsage?.latest)
                assertEquals("the exact Long precision must survive the adapter parse", HUGE_COUNT, hugeFact.requestCountRaw)
                assertEquals(PHASE_FIVE_MS, hugeFact.observedAt)
                assertEquals(hugeFact.evidenceId, huge.accountUsage?.lastGood?.evidenceId)

                // Phase 6：capabilities 失敗 → usage 記 caps 自己的 evidence source
                // （不得被改寫成 account source），且沒有真正的 account response。
                dispatcher.statusFailure = true
                clock.set(PHASE_SIX_MS)
                val loopback6 = UsageLoopback(server)
                val capsFailed = app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(loopback6).build(), clock),
                )
                // capabilities 失敗時三個資料腿都不發請求：只留 3 次 status 探測。
                assertEquals("a capabilities failure must not issue any data request", 3, loopback6.total())
                assertEquals("no account HTTP may be issued when capabilities failed", 0, loopback6.account.get())
                drainRequests(expected = 3)
                val capsFact = requireNotNull(capsFailed.accountUsage?.latest)
                assertEquals(ProviderAccountUsageStore.CAPS_SOURCE, capsFact.source)
                assertEquals(NewApiStatus.HTTP_SERVER_ERROR.name, capsFact.status)
                assertEquals(500, capsFact.httpStatus)
                assertNull("a capability failure carries no account request count", capsFact.requestCountRaw)
                assertFalse(
                    "a capability failure is not an observed account endpoint response",
                    capsFact.accountEndpointObserved,
                )
                assertEquals(PHASE_SIX_MS, capsFact.observedAt)
                assertEquals(
                    "a capability failure must not move last-good",
                    hugeFact.evidenceId,
                    capsFailed.accountUsage?.lastGood?.evidenceId,
                )

                // 歷史 append-only：新→舊六筆（good／zero／missing／negative／huge／caps failure）。
                val history = usage.history(fixture.providerId, fixture.accountId)
                assertEquals("every observation appends; none is overwritten", 6, history.size)
                assertEquals(
                    listOf(
                        capsFact.evidenceId,
                        hugeFact.evidenceId,
                        negativeFact.evidenceId,
                        missingFact.evidenceId,
                        zeroFact.evidenceId,
                        firstFact.evidenceId,
                    ),
                    history.map { it.evidenceId },
                )
                assertEquals(
                    listOf(PHASE_SIX_MS, PHASE_FIVE_MS, PHASE_FOUR_MS, PHASE_THREE_MS, PHASE_TWO_MS, PHASE_ONE_MS),
                    history.map { it.observedAt },
                )
                assertEquals(
                    "counter semantics stay UNSPECIFIED for every row, including failures",
                    setOf(ProviderAccountUsageStore.COUNTER_SEMANTICS_UNSPECIFIED),
                    history.map { it.counterSemantics }.toSet(),
                )
                assertUsageCountAbsentFromOtherSlots(fixture.providerId, listOf(BASE_COUNT.toString(), HUGE_COUNT.toString()))
            }
        }
    }

    // ===== 4. account 端點 HTTP 失敗：account source 失敗標記＋quota/token/membership 不回歸 =====

    @Test
    fun accountHttpFailureKeepsQuotaTokenMembershipAndLastGoodWhileUsageRecordsAccountSourceMarker() {
        val tag = shortId()
        val fixture = seedFixture("acctfail", tag, listOf(modelName("acctfail", tag)))
        val accountScope = NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.accountId)
        val usage = app.repository.providerAccountUsage

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val dispatcher = UsageDispatcher(
                    accountBody = accountJson(requestCount = BASE_COUNT),
                    pricingModels = { mapOf(fixture.model to "0") },
                )
                server.dispatcher = dispatcher
                val vault = CountingVault(fixture.secrets())
                assertTrue(
                    MyModelsStore(app.database).addToMyModels(fixture.providerId, fixture.model, source = "USER", nowMillis = 13_000L),
                )

                val seedLoopback = UsageLoopback(server)
                app.repository.syncNewApi(
                    fixture.providerId,
                    NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(seedLoopback).build()),
                )
                assertEquals(4, seedLoopback.total())
                drainRequests(expected = 4)
                val goodFact = requireNotNull(usage.observation(fixture.providerId, fixture.accountId).latest)
                assertUsageFact(goodFact, fixture, BASE_COUNT)

                // Wave：只有 /api/user/self 回 500；pricing／token 腿照舊成功。
                dispatcher.accountFailure = true
                val loopback = UsageLoopback(server)
                val client = OkHttpClient.Builder().addInterceptor(loopback).build()
                val baselineRequests = server.requestCount
                assertTrue(
                    vm.maybeEnrichMyModel(
                        providerId = fixture.providerId,
                        accountId = fixture.accountId,
                        credentialId = null,
                        group = null,
                        providerModelId = fixture.model,
                        lmspeedFetcher = null,
                        newApiAdapterFactory = { NewApiAdapter(vault, client) },
                        newApiPerfAdapterFactory = { null },
                    ),
                )
                assertEquals("a failing account leg must not change the 4-call sequence (own-passport reuse; the 500 is one served call, not an extra fetch)", 4, server.requestCount - baselineRequests)
                assertEquals("still exactly one station account call per sync", 1, loopback.account.get())
                assertEquals("the token leg still runs independently", 1, loopback.token.get())
                val recorded = drainRequests(expected = 4)
                val accountRequest = recorded.single { it.encodedPath() == "/api/user/self" }
                assertEquals("Bearer ${fixture.patSecret}", accountRequest.getHeader("Authorization"))
                assertFalse(
                    "the failing account leg must still authenticate with the account PAT, not the inference key",
                    accountRequest.getHeader("Authorization")!!.contains(fixture.keySecret),
                )

                // usage：失敗標記落在 account source，count 留 null，last-good 不動。
                val after = usage.observation(fixture.providerId, fixture.accountId)
                val failedFact = requireNotNull(after.latest) { "the failing account leg must still record an observation" }
                assertEquals(NewApiStatus.HTTP_SERVER_ERROR.name, failedFact.status)
                assertEquals(500, failedFact.httpStatus)
                assertNull("a failed account response carries no request count", failedFact.requestCountRaw)
                assertEquals(
                    "the failure keeps the real account source, never a rewritten one",
                    NewApiSnapshotStore.ACCOUNT_SOURCE,
                    failedFact.source,
                )
                assertEquals(
                    "the station answered with an HTTP status, so the account endpoint was observed",
                    true,
                    failedFact.accountEndpointObserved,
                )
                assertEquals("a failure must not move last-good", goodFact.evidenceId, after.lastGood?.evidenceId)
                assertEquals(BASE_COUNT, after.lastGood?.requestCountRaw)
                assertTrue(failedFact.observedAt >= goodFact.observedAt)
                assertUsageEvidenceRow(failedFact, NewApiSnapshotStore.ACCOUNT_SOURCE, NewApiStatus.HTTP_SERVER_ERROR.name)
                assertEquals(
                    "the failure marker appends history instead of replacing the good row",
                    2,
                    usage.history(fixture.providerId, fixture.accountId).size,
                )

                // 既有槽不回歸：account quota 失敗但 last-good 保留；token 腿仍成功；
                // membership 保留；local telemetry 仍為零。
                val quotaAfter = app.repository.newApiSnapshots.quota(accountScope, SnapshotKind.ACCOUNT_QUOTA)
                assertEquals(NewApiStatus.HTTP_SERVER_ERROR.name, quotaAfter.latest?.status)
                assertEquals("a failed quota leg must stay UNKNOWN", QuotaState.UNKNOWN.name, quotaAfter.latest?.state)
                assertNotNull("the previous good quota must survive the failing leg", quotaAfter.lastGood)
                val quotaHistory = app.repository.newApiSnapshots.history(accountScope, SnapshotKind.ACCOUNT_QUOTA)
                assertEquals("the failing quota leg appends history", 2, quotaHistory.size)
                assertEquals(
                    "the good quota row keeps its own usedRaw value",
                    USED_QUOTA,
                    quotaHistory.first { it.status == ACCOUNT_QUOTA_OK }.usedRaw,
                )
                val tokenAfter = app.repository.newApiSnapshots.quota(
                    PricingScope(
                        fixture.providerId,
                        fixture.accountId,
                        fixture.keyRef,
                        "",
                        null,
                        "",
                        NewApiDomainMapper.OPERATION_GLOBAL,
                    ),
                    SnapshotKind.TOKEN_QUOTA,
                )
                assertEquals("the token leg must still succeed independently", "OK", tokenAfter.latest?.status)
                assertEquals(TOKEN_USED, tokenAfter.latest?.usedRaw)
                assertTrue(MyModelsStore(app.database).isMyModel(fixture.providerId, fixture.model))
                assertEquals("a failing account leg must never create local samples", 0, countRows("samples", fixture.providerId))
                assertNoPerfFacts(fixture.providerId)
                assertUsageCountAbsentFromOtherSlots(fixture.providerId, listOf(BASE_COUNT.toString()))
            }
        }
    }

    // ===== 5. observer 接縫：普通例外 fail-open 保留 quota/token；取消必傳播且不回捲已 commit =====
    //
    // 與 `NewApiAccountUsageObserverTest` 的分工：那份在 store 層（直接
    // `NewApiSnapshotStore.syncAccountQuota`）驗 hook 語意；本 case 走**真 repository
    // wiring**（`AtlasRepository(db, observer)` → 真 pricing leg → 真 `NewApiSyncResult`
    // 回傳），證明 fail-open 連 token 腿與 typed result 都不受影響，且取消傳播時
    // 已 commit 的 quota 事實仍留在 DB（不回捲）。

    @Test
    fun failingUsageObserverIsFailOpenWhileCancelledObserverPropagatesAfterQuotaCommit() {
        val tag = shortId()
        val failFixture = seedFixture("obsfail", tag, listOf(modelName("obsfail", tag)))
        val cancelFixture = seedFixture("obscancel", tag, listOf(modelName("obscancel", tag)))

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = UsageDispatcher(
                    accountBody = accountJson(requestCount = COUNT_TOTAL),
                    pricingModels = { mapOf(failFixture.model to "0", cancelFixture.model to "0") },
                )

                // ---- Phase A：observer 拋普通例外 → fail-open，quota／token／typed result 全保留 ----
                val failVault = CountingVault(failFixture.secrets())
                val failObserved = AtomicInteger(0)
                val failRepo = AtlasRepository(
                    app.database,
                    object : AccountQuotaObserver {
                        override suspend fun onObserved(observation: AccountQuotaObservation) {
                            failObserved.incrementAndGet()
                            throw IllegalStateException("usage sink failed")
                        }
                    },
                )
                val failLoopback = UsageLoopback(server)
                val failResult = failRepo.syncNewApi(
                    failFixture.providerId,
                    NewApiAdapter(failVault, OkHttpClient.Builder().addInterceptor(failLoopback).build()),
                )
                assertEquals("the observer must run exactly once per account fetch", 1, failObserved.get())
                assertEquals("the observer must not add or skip any HTTP call", 4, failLoopback.total())
                assertEquals(1, failLoopback.account.get())
                assertEquals(1, failResult.prices.size)
                assertEquals("a failing observer must not break the quota leg", ACCOUNT_QUOTA_OK, failResult.accountQuota.latest?.status)
                assertEquals(USED_QUOTA, failResult.accountQuota.latest?.usedRaw)
                assertNotNull("a failing observer must not break the token leg", failResult.tokenQuota)
                assertEquals("OK", failResult.tokenQuota?.latest?.status)
                assertEquals(TOKEN_USED, failResult.tokenQuota?.latest?.usedRaw)
                assertNull("a failing observer writes no usage fact", failResult.accountUsage?.latest)
                assertNull("a failing observer promotes no last-good", failResult.accountUsage?.lastGood)
                assertEquals("no usage row may exist for the failed observation", 0, countUsageRows(failFixture.providerId))
                drainRequests(expected = 4)

                // ---- Phase B：observer 拋取消 → 必傳播，且已 commit 的 quota 事實不回捲 ----
                val cancelVault = CountingVault(cancelFixture.secrets())
                val cancelObserved = AtomicInteger(0)
                val cancelRepo = AtlasRepository(
                    app.database,
                    object : AccountQuotaObserver {
                        override suspend fun onObserved(observation: AccountQuotaObservation) {
                            cancelObserved.incrementAndGet()
                            throw CancellationException("cancelled inside the usage observer")
                        }
                    },
                )
                val cancelLoopback = UsageLoopback(server)
                val thrown = runCatching {
                    cancelRepo.syncNewApi(
                        cancelFixture.providerId,
                        NewApiAdapter(cancelVault, OkHttpClient.Builder().addInterceptor(cancelLoopback).build()),
                    )
                }.exceptionOrNull()
                assertTrue(
                    "a cancelled observation must be propagated, never converted into a false success",
                    thrown is CancellationException,
                )
                assertEquals("the observer must run exactly once per account fetch", 1, cancelObserved.get())
                // capabilities＋pricing＋account 已完成（第二 capabilities 被 own-passport
                // 復用消除）；取消發生在 token 腿之前。
                assertEquals(3, cancelLoopback.total())
                assertEquals(1, cancelLoopback.account.get())
                assertEquals("the token leg was never reached because cancellation propagated first", 0, cancelLoopback.token.get())
                drainRequests(expected = 3)
                val cancelScope = NewApiSnapshotStore.accountQuotaScope(cancelFixture.providerId, cancelFixture.accountId)
                val committedQuota = app.repository.newApiSnapshots.quota(cancelScope, SnapshotKind.ACCOUNT_QUOTA)
                assertEquals(
                    "the quota transaction committed before the observer and must not be rolled back",
                    ACCOUNT_QUOTA_OK,
                    committedQuota.latest?.status,
                )
                assertEquals(USED_QUOTA, committedQuota.latest?.usedRaw)
                assertEquals(
                    "no token quota row may exist for the cancelled sync",
                    0,
                    app.repository.newApiSnapshots.history(
                        PricingScope(
                            cancelFixture.providerId,
                            cancelFixture.accountId,
                            cancelFixture.keyRef,
                            "",
                            null,
                            "",
                            NewApiDomainMapper.OPERATION_GLOBAL,
                        ),
                        SnapshotKind.TOKEN_QUOTA,
                    ).size,
                )
                assertEquals("a cancelled observation must not write a usage fact", 0, countUsageRows(cancelFixture.providerId))
                assertNull(
                    "a cancelled observation must not publish a typed usage read",
                    app.repository.providerAccountUsage.observation(cancelFixture.providerId, cancelFixture.accountId).latest,
                )
            }
        }
    }

    // ===== 精確斷言 helper（全部打在真 adapter 解析＋真 store 落庫後的 DB 列） =====

    /** happy-path fact 的完整語意：provider×account scope、PAT safe ref provenance、count 原值。 */
    private fun assertUsageFact(
        fact: ProviderAccountUsageFactEntity,
        fixture: UsageFixture,
        requestCount: Long,
    ) {
        assertEquals(providerAccountUsageScopeKey(fixture.providerId, fixture.accountId), fact.scopeKey)
        assertEquals(ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name, fact.kind)
        assertEquals(fixture.providerId, fact.providerId)
        assertEquals(fixture.accountId, fact.accountId)
        assertEquals("the PAT safe ref is provenance only, never the inference key", fixture.patRef, fact.credentialId)
        assertEquals("OK", fact.status)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, fact.source)
        assertEquals(200, fact.httpStatus)
        assertEquals(NewApiVersion.RC25, fact.providerVersion)
        assertEquals(requestCount, fact.requestCountRaw)
        assertEquals(
            "counter semantics are never guessed",
            ProviderAccountUsageStore.COUNTER_SEMANTICS_UNSPECIFIED,
            fact.counterSemantics,
        )
        assertTrue("a 2xx account response is an observed account endpoint", fact.accountEndpointObserved)
        assertTrue("usage evidence ids live in their own namespace", fact.evidenceId.startsWith("usage:"))
        assertFalse(
            "no raw secret may ever reach a usage fact",
            fact.toString().contains(fixture.patSecret) || fact.toString().contains(fixture.keySecret),
        )
    }

    /** usage 自己的 evidence 列：outcome=status、status=httpStatus、source=真實 source、無 body。 */
    private suspend fun assertUsageEvidenceRow(
        fact: ProviderAccountUsageFactEntity,
        expectedSource: String,
        expectedOutcome: String,
    ) {
        val evidence = requireNotNull(app.database.evidenceDao().getById(fact.evidenceId)) {
            "every usage fact must own a durable evidence row"
        }
        assertEquals(expectedOutcome, evidence.outcome)
        assertEquals(expectedSource, evidence.source)
        assertEquals(fact.httpStatus, evidence.status)
        assertEquals(fact.observedAt, evidence.timestamp)
        assertEquals("usage carries no model attribution", "", evidence.modelId)
        assertEquals(fact.providerId, evidence.providerId)
        assertTrue("the evidence id is the usage namespace id", evidence.id.startsWith("usage:"))
    }

    // ===== harness（fixture 僅本檔，不當產品資料） =====

    private class UsageFixture(
        val tag: String,
        val providerId: String,
        val accountId: String,
        val patRef: String,
        val keyRef: String,
        val patSecret: String,
        val keySecret: String,
        val endpointId: String,
        val model: String,
    ) {
        /** 兩個 synthetic fake secret 只服務 loopback；DB 只存 safe ref。 */
        fun secrets(): Map<String, String> = mapOf(patRef to patSecret, keyRef to keySecret)
    }

    private fun seedFixture(name: String, tag: String, models: List<String>): UsageFixture = runBlocking {
        val providerId = "usage-flow-$name-$tag"
        val accountId = "a-usage-$name-$tag"
        val patRef = "pat-usage-$name-$tag"
        val keyRef = "key-usage-$name-$tag"
        val endpointId = "e-usage-$name-$tag"
        providerIds += providerId
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
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = providerId,
                workflowId = "wf-$providerId-usageflow",
                authMode = AuthMode.BEARER,
                credentials = listOf(
                    SafeCredentialRef(patRef, "PAT", CredentialRole.ACCOUNT),
                    SafeCredentialRef(keyRef, "KEY", CredentialRole.INFERENCE),
                ),
            ).toEntity(),
        )
        UsageFixture(
            tag = tag,
            providerId = providerId,
            accountId = accountId,
            patRef = patRef,
            keyRef = keyRef,
            patSecret = "usage-pat-secret-$name-$tag-0123456789",
            keySecret = "usage-key-secret-$name-$tag-9876543210",
            endpointId = endpointId,
            model = models.first(),
        )
    }

    /** Provider 維度精確計數（只對確有 providerId 欄的表；不做全域假設）。 */
    private fun countRows(table: String, providerId: String): Int {
        app.database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE providerId = ?", arrayOf<Any?>(providerId))
            .use { c ->
                assertTrue(c.moveToFirst())
                return c.getInt(0)
            }
    }

    /** 本槽 facts 有 providerId 欄：精確 provider 維計數（不用 LIKE、不做全域假設）。 */
    private fun countUsageRows(providerId: String): Int {
        app.database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM provider_account_usage_facts WHERE providerId = ?",
            arrayOf<Any?>(providerId),
        ).use { c ->
            assertTrue(c.moveToFirst())
            return c.getInt(0)
        }
    }

    private fun assertNoPerfFacts(providerId: String) {
        app.database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM provider_perf_facts " +
                "WHERE evidenceId IN (SELECT id FROM evidence WHERE providerId = ?)",
            arrayOf<Any?>(providerId),
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a closed perf port must write no perf fact", 0, c.getInt(0))
        }
    }

    /**
     * 來源分離守衛（Contract §2／D-009）：站方 account count canary 不得出現在 local samples／
     * external／price／quota／perf 槽。
     *
     * - `samples`／`price_snapshots`／`quota_snapshots`／`provider_events` 有 providerId。
     * - `provider_perf_facts` 無 providerId（以 evidence 子查詢精準定位）。
     * - `external_observations`／`external_mappings` 也沒有 providerId（不可冒造 column）：
     *   本 guard 對這兩表做**唯讀**全域掃描（canary 為 7 位／19 位長數字，不可能與他人
     *   fixture 碰撞），因此不依賴本 provider 是否已有 canonical alias——純 repository
     *   腿的 case 也仍然是非空的守衛，且不寫入、不刪除他人資料。
     */
    private fun assertUsageCountAbsentFromOtherSlots(providerId: String, canaryValues: List<String>) {
        for (table in listOf("samples", "price_snapshots", "quota_snapshots", "provider_events")) {
            val rows = rowsAsText(table, "providerId = ?", arrayOf<Any?>(providerId))
            for (canary in canaryValues) {
                assertFalse("provider account count leaked into $table", rows.any { it.contains(canary) })
            }
        }
        val perfRows = rowsAsText(
            "provider_perf_facts",
            "evidenceId IN (SELECT id FROM evidence WHERE providerId = ?)",
            arrayOf<Any?>(providerId),
        )
        for (canary in canaryValues) {
            assertFalse("provider account count leaked into provider_perf_facts", perfRows.any { it.contains(canary) })
        }
        for (table in listOf("external_observations", "external_mappings")) {
            val rows = rowsAsText(table, "1 = 1", emptyArray<Any?>())
            for (canary in canaryValues) {
                assertFalse("provider account count leaked into $table", rows.any { it.contains(canary) })
            }
        }
    }

    private fun rowsAsText(table: String, where: String, args: Array<Any?>): List<String> {
        val out = ArrayList<String>()
        app.database.openHelper.readableDatabase.query("SELECT * FROM $table WHERE $where", args).use { c ->
            while (c.moveToNext()) {
                val sb = StringBuilder()
                for (i in 0 until c.columnCount) {
                    sb.append(c.getString(i)).append(' ')
                }
                out += sb.toString()
            }
        }
        return out
    }

    private fun drainRequests(expected: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(expected)
        repeat(expected) {
            out += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request #$it (expected $expected)" }
        }
        return out
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    /**
     * 模型 id 前綴刻意與 provider／account ref 前綴（`usage-`／`a-usage-`）不同，
     * 讓「usage 列不得帶 model」這條斷言不會被 scopeKey 內的 accountId 誤命中。
     */
    private fun modelName(name: String, tag: String): String = "mdl-$name-$tag"

    private fun RecordedRequest.encodedPath(): String = requestUrl!!.encodedPath

    private class CountingVault(secrets: Map<String, String>) : CredentialAccess {
        private val backing = secrets.toMap()
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            return backing[credentialId] ?: throw IllegalStateException("vault-refused")
        }
    }

    /**
     * Case 3 專用 controlled clock：每輪 phase 由測試明確指定 `observedAt`（1_000…6_000），
     * 因此 last-good／append-history 的時序契約可被**精確值**斷言，不受 realSystemClock
     * 毫秒競速影響；[set] 只允許往前推進（倒退直接拋出，測試不自己製造違反 newerGood 的時間）。
     */
    private class ScriptedClock(private val start: Long) : Clock {
        private val value = AtomicLong(start)
        fun set(millis: Long) {
            require(millis >= value.get()) { "scripted clock must move forward" }
            value.set(millis)
        }

        override fun epochMillis(): Long = value.get()

        override fun monotonicMillis(): Long = value.get() - start
    }

    /**
     * 測試傳輸接縫：只換 scheme/host/port 到 loopback 真 socket（path／query／
     * Authorization 原樣保留並被 MockWebServer 真實記錄），並以 atomic tally 記錄各
     * 端點真實請求數。產品路徑永不開啟。
     */
    private class UsageLoopback(private val server: MockWebServer) : Interceptor {
        val status = AtomicInteger(0)
        val pricing = AtomicInteger(0)
        val account = AtomicInteger(0)
        val token = AtomicInteger(0)
        val perfSummary = AtomicInteger(0)
        val perfQuery = AtomicInteger(0)
        val inference = AtomicInteger(0)

        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val url = orig.url
            when (url.encodedPath) {
                "/api/status" -> status.incrementAndGet()
                "/api/pricing" -> pricing.incrementAndGet()
                "/api/user/self" -> account.incrementAndGet()
                "/api/usage/token/" -> token.incrementAndGet()
                "/api/perf-metrics/summary" -> perfSummary.incrementAndGet()
                "/api/perf-metrics" -> perfQuery.incrementAndGet()
                else -> if (url.encodedPath.startsWith("/v1/")) inference.incrementAndGet()
            }
            val loopback = server.url("/")
            val rewritten = orig.newBuilder()
                .url(
                    url.newBuilder()
                        .scheme(loopback.scheme)
                        .host(loopback.host)
                        .port(loopback.port)
                        .build(),
                )
                .build()
            return chain.proceed(rewritten)
        }

        fun total(): Int = status.get() + pricing.get() + account.get() + token.get() +
            perfSummary.get() + perfQuery.get() + inference.get()
    }

    /**
     * rc.25 source-derived synthetic 回應；僅供 loopback 傳輸層，不是 live 產品資料。
     * [accountBody]／[statusFailure]／[accountFailure] 由各 case 明確設定：觀察語意
     * （zero／缺席／負數／大數／失敗）完全由站方回應決定，測試不注入任何假 count。
     */
    private class UsageDispatcher(
        var accountBody: String,
        val pricingModels: () -> Map<String, String>,
        var statusFailure: Boolean = false,
        var accountFailure: Boolean = false,
        val perfSummaryBody: String? = null,
        val perfQueryBody: String? = null,
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.path?.substringBefore('?')) {
                "/api/status" -> if (statusFailure) {
                    MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"$STATUS_CANARY"}""")
                } else {
                    ok(statusJson())
                }
                "/api/pricing" -> ok(pricingJson(pricingModels()))
                "/api/user/self" -> if (accountFailure) {
                    MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"$ACCOUNT_CANARY"}""")
                } else {
                    ok(accountBody)
                }
                "/api/usage/token/" -> ok(tokenJson())
                "/api/perf-metrics/summary" -> perfSummaryBody?.let { ok(it) } ?: notFound()
                "/api/perf-metrics" -> perfQueryBody?.let { ok(it) } ?: notFound()
                else -> notFound()
            }

        private fun ok(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)

        private fun notFound(): MockResponse = MockResponse().setResponseCode(404)
    }

    /**
     * 本檔 fixture 精確清理（teardown correctness；FK RESTRICT 順序）。
     *
     * - usage 兩表走共用 test-only helper [deleteOwnProviderAccountUsageRows]：facts 以精確
     *   provider 維刪（FK → evidence，必須最先），heads 只刪由自身 facts 反查出的真
     *   `(scopeKey, kind)`（長度前綴編碼，絕不用 LIKE，見 [providerAccountUsageScopeKey]）。
     * - `provider_perf_facts`／`provider_perf_heads` 無 providerId 欄：由自身 facts 集合
     *   反查真 key 後精確刪。
     * - `snapshot_facts`／`snapshot_heads` 經真 [parseScopeKey] 過濾 provider 維。
     * - `evidence` 必須在所有引用它的子列（usage／perf／snapshot facts）之後刪。
     * - 失敗即拋出（不吞例外）；fixture 全 UUID 唯一 scope，不影響他人。
     */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val db = app.database
        val placeholders = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val evidenceWhere = "SELECT id FROM evidence WHERE providerId IN ($placeholders)"
        val writable = db.openHelper.writableDatabase
        // 自己的 canonicalIds：必須在刪 aliases 之前查。
        val canonicalIds: List<String> = db.openHelper.readableDatabase.query(
            "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN ($placeholders)",
            args,
        ).use { c ->
            val ids = LinkedHashSet<String>()
            while (c.moveToNext()) ids += c.getString(0)
            ids.toList()
        }
        // perf 表無 providerId 欄：由自身 facts 集合反查真 (scopeKey, kind)（非 wildcard）。
        val perfScopes = scopedKeys(writable, "provider_perf_facts", evidenceWhere, args)
        // 自己的 snapshot (scopeKey, kind)：真 parseScopeKey 過濾 provider 維。
        val ownHeads = ArrayList<Pair<String, String>>()
        runBlocking {
            for (kind in SnapshotKind.values()) {
                for (h in db.snapshotFactDao().headsByKind(kind.name)) {
                    val parts = parseScopeKey(h.scopeKey) ?: continue
                    if (parts.getOrNull(0) in providerIds) ownHeads += h.scopeKey to h.kind
                }
            }
        }
        writable.beginTransaction()
        try {
            // 1. usage facts（FK → evidence）→ usage heads：共用 test-only helper（精確
            // provider 維＋由事實反查的真 scopeKey，絕不用 LIKE），必須在任何其他刪除前跑。
            deleteOwnProviderAccountUsageRows(writable, providerIds)
            // 2. perf facts（FK → evidence）→ perf heads
            writable.execSQL(
                "DELETE FROM provider_perf_facts WHERE evidenceId IN ($evidenceWhere)",
                args,
            )
            for ((scopeKey, kind) in perfScopes) {
                writable.execSQL(
                    "DELETE FROM provider_perf_heads WHERE scopeKey = ? AND kind = ?",
                    arrayOf<Any?>(scopeKey, kind),
                )
            }
            // 3. snapshot facts／heads（FK → evidence）
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_facts WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_heads WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            // 4. provider 維子列
            writable.execSQL("DELETE FROM price_snapshots WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM quota_snapshots WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM samples WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM my_models WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($placeholders)", args)
            writable.execSQL(
                "DELETE FROM credentials WHERE accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($placeholders))",
                args,
            )
            writable.execSQL("DELETE FROM offerings WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM endpoints WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM accounts WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($placeholders)", args)
            // 5. evidence（所有引用子列皆已刪除）→ providers → orphan canonical
            writable.execSQL("DELETE FROM evidence WHERE providerId IN ($placeholders)", args)
            writable.execSQL("DELETE FROM providers WHERE providerId IN ($placeholders)", args)
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

    private fun scopedKeys(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        evidenceWhere: String,
        args: Array<Any?>,
    ): List<Pair<String, String>> = db.query(
        "SELECT DISTINCT scopeKey, kind FROM $table WHERE evidenceId IN ($evidenceWhere)",
        args,
    ).use { c ->
        val out = ArrayList<Pair<String, String>>()
        while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        out
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val RC25_VERSION = NewApiVersion.RC25
        const val ACCOUNT_QUOTA_OK = "OK"
        const val ACCOUNT_QUOTA_RAW = 1_000_000L
        const val USED_QUOTA = 250_000L
        const val COUNT_TOTAL = 4_242_424L
        const val BASE_COUNT = 7_777_777L
        const val NEGATIVE_COUNT = -5L
        const val HUGE_COUNT = 9_223_372_036_854_775_806L
        const val TOKEN_GRANTED = 1_000L
        const val TOKEN_USED = 400L
        const val PERF_HOURS = 168
        const val PERF_GROUP = "default"
        const val PERF_LATENCY_MS = 4_242L
        const val PERF_QUERY_TS = 1_760_002_100L
        const val SERIES_SCHEMA = "dbcd0a3c01b55203"
        const val PHASE_ONE_MS = 1_000L
        const val PHASE_TWO_MS = 2_000L
        const val PHASE_THREE_MS = 3_000L
        const val PHASE_FOUR_MS = 4_000L
        const val PHASE_FIVE_MS = 5_000L
        const val PHASE_SIX_MS = 6_000L
        const val STATUS_CANARY = "usage-status-canary-must-never-escape"
        const val ACCOUNT_CANARY = "usage-account-canary-must-never-escape"

        fun statusJson(): String =
            """{"success":true,"message":"","data":{"version":"$RC25_VERSION","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

        fun pricingJson(models: Map<String, String>): String {
            val items = models.entries.joinToString(",") { (m, ratio) ->
                """{"model_name":"$m","quota_type":0,"model_ratio":$ratio,"completion_ratio":1,"enable_groups":["all"],"supported_endpoint_types":["openai"]}"""
            }
            return """{"success":true,"message":"","data":[$items],"group_ratio":{"default":1},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"usage-flow-v1"}"""
        }

        /** 站方帳號回應：`request_count` 原值由 case 指定（合成 fixture，非產品資料）。 */
        fun accountJson(requestCount: Long): String =
            """{"success":true,"message":"","data":{"quota":$ACCOUNT_QUOTA_RAW,"used_quota":$USED_QUOTA,"request_count":$requestCount,"group":"default","role":1,"status":1}}"""

        /** `request_count = 0` 且 quota 欄缺席：0 是真值、quota 缺席是 Unknown。 */
        fun accountZeroCountJson(): String =
            """{"success":true,"message":"","data":{"request_count":0}}"""

        /** `request_count` 缺席：Unknown 留 null，絕不補 0。 */
        fun accountMissingCountJson(): String =
            """{"success":true,"message":"","data":{"quota":$ACCOUNT_QUOTA_RAW,"used_quota":$USED_QUOTA,"group":"default","role":1,"status":1}}"""

        fun tokenJson(): String =
            """{"code":true,"message":"ok","data":{"object":"token_usage","name":"usage-flow-key","total_granted":$TOKEN_GRANTED,"total_used":$TOKEN_USED,"total_available":600,"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""

        fun perfSummaryJson(model: String, latencyMs: Long): String =
            """{"success":true,"message":"","data":{"models":[{"model_name":"$model","avg_latency_ms":$latencyMs,"success_rate":95.5,"avg_tps":22.5,"recent_success_rates":[95.0,95.5]}]}}"""

        fun perfQueryJson(model: String, ts: Long): String =
            """{"success":true,"message":"","data":{"model_name":"$model","series_schema":"$SERIES_SCHEMA","groups":[{"group":"$PERF_GROUP","avg_ttft_ms":321,"avg_latency_ms":$PERF_LATENCY_MS,"success_rate":95.5,"avg_tps":22.5,"series":[{"ts":$ts,"avg_ttft_ms":321,"avg_latency_ms":$PERF_LATENCY_MS,"success_rate":95.5,"avg_tps":22.5}]}]}}"""
    }
}
