package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
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
 * CR-001 A5 ProviderPerf selected-PAT account provenance regression (device androidTest).
 *
 * 契約來源（唯讀，不在本檔重定義產品規則）：
 * - CORRECTNESS_REPAIR_AUDIT A5 (CONFIRMED) + CR-001 gate：repository scope foundation，
 *   explicit requested account/key、only validated same-account PAT fallback、fail-closed
 *   legacy ambiguity、bundle exact pass-through、provider-perf account provenance。
 * - PRODUCT_SPEC §6 (Account/Credential/PAT_ACCOUNT vs inference key) / §10 (provenance) /
 *   §12 (New API PAT=Account/Management, API key=Inference) / §18 (完成條件)。
 * - Contract §23.3 (加入即自動接 intelligence，enrichment 失敗不刪模型) + source2
 *   (rc.25 `f1164142` PERF-SHAPE-B)；NINT-004 existing guards（cert gate / scope / PAT /
 *   source / finite / cancel / last-good）全保留，本檔只加 account 選擇，不放寬任一守門。
 *
 * LOCKED additive API（生產端已合入 accountId port；本檔為 account provenance regression）：
 * - `ProviderPerfStore.syncPerf(adapter, providerId, providerModelId, hours=168,
 *   group=null, accountId:String?=null)` —— `accountId` 必須在最後、預設 null。
 * - `AtlasRepository.enrichMyModelBundle` 已有 `accountId`，必須原樣 pass-through 給 perf 腿。
 *
 * 選擇器契約（本檔 9 tests 鎖定）：
 * - explicit accountId + same-account enabled pure-ACCOUNT valid PAT → 用該 PAT；
 *   同 account 內 disabled/stale 在前、valid 在後 → 跳過前者用後者；永不借別 account。
 * - explicit VALID account 無 PAT（config 缺 ACCOUNT refs）→ 匿名 public perf 保留，
 *   provenance 雙 null（requested account 本身不是 PAT provenance）；invalid configured
 *   PAT 一律 fail-closed，不得靜默降級匿名。
 * - no explicit + 有 configured PAT → 要求 provider 內唯一 account（唯一才用 sole PAT；
 *   多 account 即 ambiguous fail-closed）；無 PAT → 匿名 legacy 保留；invalid-only → 失敗，
 *   不得靜默降級匿名。
 *
 * 傳輸/隔離聲明（review 必讀）：
 * - 真 in-memory Room (v12+ perf 兩表 + real DAOs) + 真 [ProviderPerfStore.syncPerf] /
 *   真 [AtlasRepository.enrichMyModelBundle] + 真 [NewApiAdapter] HTTP 棧 → MockWebServer
 *   loopback 真 socket。DB endpoint baseUrl 維持 `https://…example.com/v1` 讓生產
 *   EndpointPolicy HTTPS gate 照常生效；測試只用 OkHttp Interceptor 改寫 scheme/host/port，
 *   path/query/Authorization 原樣保留並由 MockWebServer 真實記錄。
 * - Secret 全為本檔 synthetic fake (`loopback-pat-secret-…`)，只發往 loopback；raw `sk-`
 *   絕不進 repo/log/prompt；vault 只綁本檔 safeCredentialRef。
 * - Stored cert 前置為測試顯式種子的官方 PRICE fact/head/evidence triple
 *   (source+rc25+OK+http200 全匹配)，僅為測試夾具，不是 deployment VERIFIED 宣告。
 * - Fixture JSON 僅存本檔 (rc.25 PERF-SHAPE-B 形狀衍生 synthetic values)，經真 adapter 解析 +
 *   真 store 落庫後才斷言 DB；fixture 值不冒充產品資料（跨 model/group 用互異數值，
 *   任何串欄或借用都會失敗）。
 * - 每個測試用自己 isolated DB；teardown 關閉 DB (in-memory drop；FK RESTRICT 順序由
 *   Room 持有：perf facts → heads → snapshot facts/heads → evidence，關閉即全清，
 *   不做全域 DB 斷言)。零 sleep；drain 用 5s takeRequest；bundle 以外全 `runBlocking`，
 *   取消安全（CancellationException 由 store 重拋，本檔不吞）。
 * - CR001 保留 exactly status+summary+query 3 HTTP；no `/v1/inference`；requested-model
 *   fetched gate（other-model summary 不能滿足 requested）。
 *
 * 注意：本檔呼叫含 `accountId` 的 LOCKED API；生產端已合入該 additive param（selector+perf
 * 同片），9 tests 一起編譯，實跑結果以 same-SHA CI artifact 為準，本檔不宣稱 CI。
 */
@RunWith(AndroidJUnit4::class)
class ProviderPerfAccountSelectionTest {

    private lateinit var server: MockWebServer
    private val open = mutableListOf<AtlasDatabase>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    // ===== 1. explicit B：同 provider A/B 下選 PAT-B，summary/query 皆 Bearer B，provenance=B =====

    @Test
    fun explicitAccountB_SelectsPatB_OnSummaryAndQuery_WithBProvenance() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-sel"
            val accountA = "a-acct-sel-A"
            val accountB = "a-acct-sel-B"
            val patA = "pat-acct-sel-A"
            val patB = "pat-acct-sel-B"
            val endpoint = "e-acct-sel"
            val requested = "m-acct-target"
            val other = "m-acct-other"
            // Config 刻意 A 在前、B 在後：若實作偷拿 first ref 即選錯 A，本測試必失敗。
            seedTwoAccountProvider(db, provider, accountA, patA, accountB, patB, endpoint)
            seedCertifiedPrice(db, provider, accountA, endpoint)
            val store = ProviderPerfStore(db)
            val patBSecret = "loopback-pat-secret-acct-sel-B-0123456789"
            val vault = CountingVault(mapOf(patA to "loopback-pat-secret-acct-sel-A-9876543210", patB to patBSecret))
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, other))
            enqueue(queryTwoGroupsJson(requested))

            val result = store.syncPerf(
                NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)),
                provider,
                requested,
                accountId = accountB,
            )

            assertTrue(result.certified)
            assertTrue("requested model present in summary+query must report fetched", result.fetched)
            assertFalse(result.failed)
            assertEquals(2, result.summaryFacts)
            assertEquals(2, result.queryFacts)

            // Exactly 3 HTTP：status + summary + query；no /v1/inference。
            val recorded = drain(3)
            assertEquals("/api/status", recorded[0].path)
            assertTrue(recorded[1].path!!.startsWith("/api/perf-metrics/summary"))
            assertTrue(recorded[1].path!!.contains("hours=168"))
            assertTrue(recorded[2].path!!.contains("model=$requested"))
            assertTrue(recorded[2].path!!.contains("hours=168"))
            for (req in recorded) {
                val p = req.path ?: ""
                assertFalse("perf leg must never touch inference endpoints: $p", p.startsWith("/v1/") || p.contains("/inference"))
            }
            assertNull("status 無需憑證", recorded[0].getHeader("Authorization"))
            assertEquals("Bearer $patBSecret", recorded[1].getHeader("Authorization"))
            assertEquals("Bearer $patBSecret", recorded[2].getHeader("Authorization"))
            assertTrue("必須取用 PAT-B", vault.asked.any { it.startsWith("$patB@") })
            assertTrue("永不借 PAT-A", vault.asked.none { it.startsWith("$patA@") })
            assertFalse(result.toString().contains(patBSecret))

            // Provenance：account/credential 落 B；identity 仍為 provider-reported 原值。
            val summary = store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!
            assertEquals(accountB, summary.accountId)
            assertEquals(patB, summary.credentialId)
            assertEquals("identity 必須是站方回的 requested model", requested, summary.modelId)
            assertNull(summary.groupName)
            assertEquals(168, summary.hours)
            assertEquals(88.5, summary.successRate!!, 0.0)
            val group = store.observation(provider, requested, "default", 168, ProviderPerfKind.GROUP).lastGood!!
            assertEquals(accountB, group.accountId)
            assertEquals(patB, group.credentialId)
            assertEquals(requested, group.modelId)
            assertEquals("default", group.groupName)
            assertEquals(99.0, group.successRate!!, 0.0)
            // Evidence source 誠實：summary/query 各自真 source。
            assertEquals(ProviderPerfStore.PERF_SUMMARY_SOURCE, db.evidenceDao().getById(summary.evidenceId)!!.source)
            assertEquals(ProviderPerfStore.PERF_QUERY_SOURCE, db.evidenceDao().getById(group.evidenceId)!!.source)
            // Other-model summary 保留但不滿足 requested gate（requested 自己的列必須存在）。
            assertNotNull(store.observation(provider, other, null, 168, ProviderPerfKind.SUMMARY).lastGood)
            // FK RESTRICT：evidence 不得被直接刪除。
            try {
                db.openHelper.writableDatabase.execSQL("DELETE FROM evidence WHERE id = ?", arrayOf(group.evidenceId))
                throw AssertionError("FK RESTRICT must block evidence delete")
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
            }
            assertNotNull(db.providerPerfDao().fact(group.evidenceId))
            // 零副作用槽：samples/external 不動。
            assertEquals(0L, count(db, "samples"))
            assertEquals(0L, count(db, "external_observations"))
        }
    }

    // ===== 2. 同 account 內 invalid-first 跳過，用後面的 valid SAME account 成功 =====

    @Test
    fun sameAccount_InvalidFirst_SkipsToLaterValidSameAccount() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-skip"
            val accountA = "a-acct-skip-A"
            val accountB = "a-acct-skip-B"
            val patA = "pat-acct-skip-A"
            val patBDisabled = "pat-acct-skip-B-disabled"
            val patBGood = "pat-acct-skip-B-good"
            val endpoint = "e-acct-skip"
            val requested = "m-acct-skip-target"
            seedProviderShell(db, provider, endpoint)
            db.accountDao().insert(AccountEntity(accountA, provider))
            db.accountDao().insert(AccountEntity(accountB, provider))
            db.credentialDao().insert(CredentialEntity(patA, accountA))
            db.credentialDao().insert(CredentialEntity(patBDisabled, accountB))
            db.credentialDao().insert(CredentialEntity(patBGood, accountB))
            // Disabled 必須真的生效（同 account 內排第一，valid 排第二）。
            assertEquals(1, db.credentialDao().setEnabled(patBDisabled, false))
            assertFalse(db.credentialDao().getById(patBDisabled)!!.enabled)
            db.providerConfigDao().insert(
                ProviderManualConfig(
                    provider, "wf-$provider-skip", AuthMode.BEARER,
                    credentials = listOf(
                        SafeCredentialRef(patBDisabled, "PAT", CredentialRole.ACCOUNT),
                        SafeCredentialRef(patBGood, "PAT", CredentialRole.ACCOUNT),
                        SafeCredentialRef(patA, "PAT", CredentialRole.ACCOUNT),
                    ),
                ).toEntity(),
            )
            seedCertifiedPrice(db, provider, accountA, endpoint)
            val store = ProviderPerfStore(db)
            val goodSecret = "loopback-pat-secret-acct-skip-B-good-0123456789"
            val vault = CountingVault(mapOf(patBGood to goodSecret, patA to "loopback-pat-secret-acct-skip-A-0000"))
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-skip-other"))
            enqueue(queryTwoGroupsJson(requested))

            val result = store.syncPerf(
                NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)),
                provider, requested, accountId = accountB,
            )

            assertTrue(result.certified)
            assertTrue(result.fetched)
            assertFalse(result.failed)
            val recorded = drain(3)
            assertEquals("Bearer $goodSecret", recorded[1].getHeader("Authorization"))
            assertEquals("Bearer $goodSecret", recorded[2].getHeader("Authorization"))
            assertTrue(vault.asked.any { it.startsWith("$patBGood@") })
            assertTrue("disabled 首選不得進 vault", vault.asked.none { it.startsWith("$patBDisabled@") })
            assertTrue("永不借別 account 的 A", vault.asked.none { it.startsWith("$patA@") })
            val fact = store.observation(provider, requested, "default", 168, ProviderPerfKind.GROUP).lastGood!!
            assertEquals(accountB, fact.accountId)
            assertEquals(patBGood, fact.credentialId)
        }
    }

    // ===== 3. invalid B + valid A：請求 B 必須 fail-closed，0HTTP/0vault，不借 A，不匿名，保 lastGood =====

    @Test
    fun invalidB_WithValidA_FailsClosed_NoBorrowNoAnonymous_PreservesLastGood() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-noborrow"
            val accountA = "a-acct-noborrow-A"
            val accountB = "a-acct-noborrow-B"
            val patA = "pat-acct-noborrow-A"
            val patB = "pat-acct-noborrow-B"
            val endpoint = "e-acct-noborrow"
            val requested = "m-acct-noborrow-target"
            seedTwoAccountProvider(db, provider, accountA, patA, accountB, patB, endpoint)
            seedCertifiedPrice(db, provider, accountA, endpoint)
            val store = ProviderPerfStore(db)
            val secretA = "loopback-pat-secret-acct-noborrow-A-0123456789"
            val secretB = "loopback-pat-secret-acct-noborrow-B-0123456789"
            val vault = CountingVault(mapOf(patA to secretA, patB to secretB))

            // Phase 1：B 有效時先建 lastGood 基線（explicit B）。
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-noborrow-other"))
            enqueue(queryTwoGroupsJson(requested))
            val good = store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)), provider, requested, accountId = accountB)
            assertTrue(good.fetched)
            drain(3)
            val goodSummaryId = store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId
            val goodGroupId = store.observation(provider, requested, "default", 168, ProviderPerfKind.GROUP).lastGood!!.evidenceId
            val factsAfterGood = count(db, "provider_perf_facts")
            val httpAfterGood = server.requestCount
            val vaultAfterGood = vault.asked.size

            // Phase 2：B 變 invalid-only（停用），A 仍有效。
            assertEquals(1, db.credentialDao().setEnabled(patB, false))
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-noborrow-other"))
            enqueue(queryTwoGroupsJson(requested))

            try {
                store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(6_000L)), provider, requested, accountId = accountB)
                throw AssertionError("invalid B must fail closed, never borrow A or fall back to anonymous")
            } catch (e: IllegalArgumentException) {
                assertFalse((e.message ?: "").contains(patB))
                assertFalse((e.message ?: "").contains("http"))
            }
            assertEquals("invalid B 不得有新增 HTTP", httpAfterGood, server.requestCount)
            assertEquals("invalid B 不得再碰 vault（含 A 亦不得借）", vaultAfterGood, vault.asked.size)
            assertEquals("fail-closed 不得寫入任何 perf fact", factsAfterGood, count(db, "provider_perf_facts"))
            assertEquals("summary lastGood 必須保留", goodSummaryId, store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId)
            assertEquals("group lastGood 必須保留", goodGroupId, store.observation(provider, requested, "default", 168, ProviderPerfKind.GROUP).lastGood!!.evidenceId)
        }
    }

    // ===== 4. 無 explicit + 唯一 account：用 sole PAT 成功 =====

    @Test
    fun noExplicit_SingleAccount_UsesSolePat() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-unique"
            val account = "a-acct-unique"
            val pat = "pat-acct-unique"
            val endpoint = "e-acct-unique"
            val requested = "m-acct-unique-target"
            seedTwoAccountProvider(db, provider, account, pat, "a-acct-unused-never", "pat-acct-unused-never", endpoint, singleAccountOnly = true)
            seedCertifiedPrice(db, provider, account, endpoint)
            val store = ProviderPerfStore(db)
            val secret = "loopback-pat-secret-acct-unique-0123456789"
            val vault = CountingVault(mapOf(pat to secret))
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-unique-other"))
            enqueue(queryTwoGroupsJson(requested))

            val result = store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)), provider, requested)

            assertTrue(result.certified)
            assertTrue(result.fetched)
            val recorded = drain(3)
            assertEquals("Bearer $secret", recorded[1].getHeader("Authorization"))
            assertEquals("Bearer $secret", recorded[2].getHeader("Authorization"))
            val fact = store.observation(provider, requested, "default", 168, ProviderPerfKind.GROUP).lastGood!!
            assertEquals(account, fact.accountId)
            assertEquals(pat, fact.credentialId)
        }
    }

    // ===== 5. 無 explicit + 多 account：ambiguous fail-closed，0HTTP/0vault，保 lastGood =====

    @Test
    fun noExplicit_MultiAccount_FailsAmbiguous_PreservesLastGood() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-ambig"
            val accountA = "a-acct-ambig-A"
            val accountB = "a-acct-ambig-B"
            val patA = "pat-acct-ambig-A"
            val patB = "pat-acct-ambig-B"
            val endpoint = "e-acct-ambig"
            val requested = "m-acct-ambig-target"
            seedTwoAccountProvider(db, provider, accountA, patA, accountB, patB, endpoint)
            seedCertifiedPrice(db, provider, accountA, endpoint)
            val store = ProviderPerfStore(db)
            val vault = CountingVault(mapOf(patA to "loopback-pat-secret-acct-ambig-A-0", patB to "loopback-pat-secret-acct-ambig-B-0"))

            // 先以 explicit B 建 lastGood，證明 ambiguous 失敗保住它。
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-ambig-other"))
            enqueue(queryTwoGroupsJson(requested))
            assertTrue(store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)), provider, requested, accountId = accountB).fetched)
            drain(3)
            val goodId = store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId
            val factsAfterGood = count(db, "provider_perf_facts")
            val httpAfterGood = server.requestCount
            val vaultAfterGood = vault.asked.size
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-ambig-other"))
            enqueue(queryTwoGroupsJson(requested))

            try {
                store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(6_000L)), provider, requested)
                throw AssertionError("multi-account without explicit account must fail closed (ambiguous)")
            } catch (e: IllegalArgumentException) {
                assertFalse((e.message ?: "").contains("http"))
            }
            assertEquals(httpAfterGood, server.requestCount)
            assertEquals(vaultAfterGood, vault.asked.size)
            assertEquals(factsAfterGood, count(db, "provider_perf_facts"))
            assertEquals(goodId, store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId)
        }
    }

    // ===== 6. 無 PAT：匿名 legacy 保留，3HTTP 無 Authorization =====

    @Test
    fun noExplicit_NoPat_AnonymousLegacyKept() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-anon"
            val account = "a-acct-anon"
            val endpoint = "e-acct-anon"
            val requested = "m-acct-anon-target"
            seedProviderShell(db, provider, endpoint)
            db.accountDao().insert(AccountEntity(account, provider))
            // 刻意無 providerConfig（無 PAT）：匿名路徑。
            seedCertifiedPrice(db, provider, account, endpoint)
            val store = ProviderPerfStore(db)
            val vault = CountingVault(emptyMap())
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-anon-other"))
            enqueue(queryTwoGroupsJson(requested))

            val result = store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)), provider, requested)

            assertTrue(result.certified)
            assertTrue(result.fetched)
            val recorded = drain(3)
            for (req in recorded) assertNull(req.getHeader("Authorization"))
            assertTrue(vault.asked.isEmpty())
            val summary = store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!
            assertNull("匿名 provenance 必須雙 null", summary.accountId)
            assertNull(summary.credentialId)
            assertEquals(requested, summary.modelId)
        }
    }

    // ===== 7. invalid-only 無 explicit：失敗，不靜默匿名，0HTTP/0vault，保 lastGood =====

    @Test
    fun noExplicit_InvalidOnly_FailsNotAnonymous_PreservesLastGood() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-invalidonly"
            val account = "a-acct-invalidonly"
            val pat = "pat-acct-invalidonly"
            val endpoint = "e-acct-invalidonly"
            val requested = "m-acct-invalidonly-target"
            seedProviderShell(db, provider, endpoint)
            db.accountDao().insert(AccountEntity(account, provider))
            db.credentialDao().insert(CredentialEntity(pat, account))
            db.providerConfigDao().insert(
                ProviderManualConfig(provider, "wf-$provider-inv", AuthMode.BEARER, credentials = listOf(SafeCredentialRef(pat, "PAT", CredentialRole.ACCOUNT))).toEntity(),
            )
            seedCertifiedPrice(db, provider, account, endpoint)
            val store = ProviderPerfStore(db)
            val vault = CountingVault(mapOf(pat to "loopback-pat-secret-acct-invalidonly-0"))

            // Phase 1：有效時先建 lastGood（唯一 account 無 explicit 即 sole PAT 路徑）。
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-invalidonly-other"))
            enqueue(queryTwoGroupsJson(requested))
            assertTrue(store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)), provider, requested).fetched)
            drain(3)
            val goodId = store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId
            val factsAfterGood = count(db, "provider_perf_facts")
            val httpAfterGood = server.requestCount
            val vaultAfterGood = vault.asked.size

            // Phase 2：唯一 PAT 停用 → invalid-only；無 explicit 也不得匿名。
            assertEquals(1, db.credentialDao().setEnabled(pat, false))
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-invalidonly-other"))
            enqueue(queryTwoGroupsJson(requested))
            try {
                store.syncPerf(NewApiAdapter(vault, rewriteClient(), FakeClock(6_000L)), provider, requested)
                throw AssertionError("invalid-only must fail closed, never silently anonymous")
            } catch (e: IllegalArgumentException) {
                assertFalse((e.message ?: "").contains(pat))
            }
            assertEquals(httpAfterGood, server.requestCount)
            assertEquals(vaultAfterGood, vault.asked.size)
            assertEquals(factsAfterGood, count(db, "provider_perf_facts"))
            assertEquals(goodId, store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId)
        }
    }

    // ===== 8. bundle pass-through：enrichMyModelBundle(accountId=B) 的 perf 腿必須帶 B provenance =====
    //
    // Disjoint helper：本 case 用 isolated Room + 直接 `AtlasRepository(db)`（不碰 app.database
    // 共享 DB），newApiAdapter=null（跳過 pricing NewApi 腿，只跑 perf 腿），因此與上面 store
    // cases 的 harness 完全 disjoint。若 repo 未把 requested account 傳給 perf store，
    // 本測試以 B provenance 斷言乾淨失敗（抓到 A 借用或匿名即紅）。

    @Test
    fun bundle_PassesRequestedAccountB_ToPerfProvenance() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-bundle"
            val accountA = "a-acct-bundle-A"
            val accountB = "a-acct-bundle-B"
            val patA = "pat-acct-bundle-A"
            val patB = "pat-acct-bundle-B"
            val endpoint = "e-acct-bundle"
            val requested = "m-acct-bundle-target"
            seedTwoAccountProvider(db, provider, accountA, patA, accountB, patB, endpoint)
            // Bundle 需要 offering（pricing 腿跳過亦無妨，perf 腿不依賴 offering，但種一行保真）。
            db.offeringDao().insert(
                OfferingEntity(id = "off:$provider:$requested", providerId = provider, endpointId = endpoint, providerModelId = requested, manual = false, source = "OFFICIAL", hidden = false),
            )
            seedCertifiedPrice(db, provider, accountA, endpoint)
            val repo = AtlasRepository(db)
            val secretB = "loopback-pat-secret-acct-bundle-B-0123456789"
            val vault = CountingVault(mapOf(patA to "loopback-pat-secret-acct-bundle-A-0000", patB to secretB))
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-bundle-other"))
            enqueue(queryTwoGroupsJson(requested))
            val httpBefore = server.requestCount

            val result = repo.enrichMyModelBundle(
                providerId = provider,
                providerModelId = requested,
                accountId = accountB,
                credentialId = null,
                group = null,
                lmspeedFetcher = null,
                endpointId = endpoint,
                operation = "CHAT_COMPLETIONS",
                newApiAdapter = null,
                newApiPerfAdapter = NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)),
            )

            assertTrue("bundle with B must report perf fetched", result.providerPerformanceFetched)
            assertFalse(result.stepFailures.any { it.contains("http") || it.contains(secretB) })
            val recorded = drain(3)
            assertEquals(3, server.requestCount - httpBefore)
            assertEquals("/api/status", recorded[0].path)
            assertTrue(recorded[1].path!!.startsWith("/api/perf-metrics/summary"))
            assertTrue(recorded[2].path!!.contains("model=$requested"))
            for (req in recorded) {
                val p = req.path ?: ""
                assertFalse("bundle perf leg must never touch inference: $p", p.startsWith("/v1/") || p.contains("/inference"))
            }
            assertEquals("Bearer $secretB", recorded[1].getHeader("Authorization"))
            assertEquals("Bearer $secretB", recorded[2].getHeader("Authorization"))
            assertTrue(vault.asked.none { it.startsWith("$patA@") })
            val perf = repo.providerPerf
            val group = perf.observation(provider, requested, "default", 168, ProviderPerfKind.GROUP).lastGood!!
            assertEquals(accountB, group.accountId)
            assertEquals(patB, group.credentialId)
            assertEquals(requested, group.modelId)
            val summary = perf.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!
            assertEquals(accountB, summary.accountId)
            assertEquals(patB, summary.credentialId)
        }
    }

    // ===== 9. explicit VALID account 無 PAT：匿名 public perf 保留，provenance 雙 null =====

    @Test
    fun explicitValidAccount_NoPat_AnonymousPublicPerfKept() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val db = memoryDb()
            val provider = "perf-acct-anon-explicit"
            val account = "a-acct-anon-explicit"
            val endpoint = "e-acct-anon-explicit"
            val requested = "m-acct-anon-explicit-target"
            seedProviderShell(db, provider, endpoint)
            db.accountDao().insert(AccountEntity(account, provider))
            // 刻意無 providerConfig（無 ACCOUNT PAT）：explicit 帳號不得借別家 PAT，
            // 匿名 public perf 依既有契約保留（site 設定決定是否要求）。
            seedCertifiedPrice(db, provider, account, endpoint)
            val store = ProviderPerfStore(db)
            val vault = CountingVault(emptyMap())
            enqueue(statusJson())
            enqueue(summaryTwoModelsJson(requested, "m-acct-anon-explicit-other"))
            enqueue(queryTwoGroupsJson(requested))

            val result = store.syncPerf(
                NewApiAdapter(vault, rewriteClient(), FakeClock(5_000L)),
                provider,
                requested,
                accountId = account,
            )

            assertTrue(result.certified)
            assertTrue("requested model present must report fetched (anonymous, explicit account)", result.fetched)
            assertFalse(result.failed)
            val recorded = drain(3)
            for (req in recorded) {
                assertNull("explicit 帳號無 PAT 仍匿名：不得帶 Authorization", req.getHeader("Authorization"))
            }
            assertTrue("無 PAT 不得碰 vault", vault.asked.isEmpty())
            // requested account 本身不是 PAT provenance：匿名 provenance 必須雙 null。
            val summary = store.observation(provider, requested, null, 168, ProviderPerfKind.SUMMARY).lastGood!!
            assertNull(summary.accountId)
            assertNull(summary.credentialId)
            assertEquals(requested, summary.modelId)
            val group = store.observation(provider, requested, "default", 168, ProviderPerfKind.GROUP).lastGood!!
            assertNull(group.accountId)
            assertNull(group.credentialId)
            assertEquals(requested, group.modelId)
        }
    }

    // ===== harness（fixture 僅本檔 synthetic，不當產品資料；secret 全 fake 只走 loopback） =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries().build().also { open += it }

    /** Provider shell：provider + 單一 endpoint（`https` base 過生產 gate）。 */
    private suspend fun seedProviderShell(db: AtlasDatabase, provider: String, endpoint: String) {
        db.providerDao().insert(ProviderEntity(provider, "P $provider"))
        db.endpointDao().insert(
            EndpointEntity(endpoint, provider, "https://$provider.example.com/v1", "https://$provider.example.com/v1/chat/completions"),
        )
    }

    /**
     * 雙帳號 provider：各一 ACCOUNT PAT（config 順序 A→B，測 explicit B 不偷拿首位）。
     * `singleAccountOnly=true` 時只建第一組（供唯一-account 測試）。
     */
    private suspend fun seedTwoAccountProvider(
        db: AtlasDatabase,
        provider: String,
        accountA: String,
        patA: String,
        accountB: String,
        patB: String,
        endpoint: String,
        singleAccountOnly: Boolean = false,
    ) {
        seedProviderShell(db, provider, endpoint)
        db.accountDao().insert(AccountEntity(accountA, provider))
        db.credentialDao().insert(CredentialEntity(patA, accountA))
        val refs = mutableListOf(SafeCredentialRef(patA, "PAT", CredentialRole.ACCOUNT))
        if (!singleAccountOnly) {
            db.accountDao().insert(AccountEntity(accountB, provider))
            db.credentialDao().insert(CredentialEntity(patB, accountB))
            refs += SafeCredentialRef(patB, "PAT", CredentialRole.ACCOUNT)
        }
        db.providerConfigDao().insert(
            ProviderManualConfig(provider, "wf-$provider-acct", AuthMode.BEARER, credentials = refs).toEntity(),
        )
    }

    /**
     * Stored cert 前置：官方 PRICE last-good triple（kind/scope/head 一致、`OK`+HTTP200+
     * 官方 source+evidence providerId/timestamp 一致+rc.25）。測試夾具明示，非 deployment VERIFIED。
     */
    private suspend fun seedCertifiedPrice(db: AtlasDatabase, provider: String, account: String, endpoint: String, time: Long = 1_000L) {
        val scope = PricingScope(provider, account, null, "cert-model", "default", endpoint, "CHAT_COMPLETIONS")
        val key = scope.storageKey()
        db.evidenceDao().insert(
            EvidenceEntity("cert-ev-1", "cert-ev-1", provider, "cert-model", "OK", 200, time, NewApiSnapshotStore.PRICE_SOURCE),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = "cert-ev-1",
                scopeKey = key,
                kind = SnapshotKind.PRICE.name,
                observedAt = time,
                snapshotId = "cert-ev-1",
                state = "PAID",
                status = "OK",
                confidence = "OFFICIAL",
                providerVersion = NewApiVersion.RC25,
                pricingVersion = "catalog-v1",
                itemPricingVersion = null,
                quotaPerUnit = null,
                groupRatio = null,
                statusObservedAt = time - 1,
                remainingRaw = null,
                usedRaw = null,
                grantedRaw = null,
                unlimited = null,
                expiresAtRaw = null,
                expiryMeaning = null,
                expressionHash = null,
                priceUnit = "USD_PER_1K_TOKENS",
            ),
        )
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity(key, SnapshotKind.PRICE.name, "cert-ev-1", time, "cert-ev-1", time))
    }

    private fun rewriteClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(RewriteToMock(server)).build()

    private fun enqueue(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    private fun statusJson(): String =
        """{"success":true,"data":{"version":"v1.0.0-rc.25","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

    /** Synthetic summary：requested + other 互異數值（串欄即失敗）；含 `request_count` 證明 store 不採信。 */
    private fun summaryTwoModelsJson(requested: String, other: String): String =
        """{"success":true,"data":{"models":[{"model_name":"$requested","avg_latency_ms":120,"success_rate":88.5,"avg_tps":15.5,"recent_success_rates":[88.0,88.5],"request_count":999},{"model_name":"$other","avg_latency_ms":200,"success_rate":70.0,"avg_tps":5.0,"recent_success_rates":[70.0]}]}}"""

    /** Synthetic query：default + vip 互異數值 + 各 1 point；series_schema 沿用 rc.25 常量形狀。 */
    private fun queryTwoGroupsJson(model: String): String =
        """{"success":true,"data":{"model_name":"$model","series_schema":"dbcd0a3c01b55203","groups":[{"group":"default","avg_ttft_ms":10,"avg_latency_ms":100,"success_rate":99.0,"avg_tps":20.0,"series":[{"ts":1760000100,"avg_ttft_ms":10,"avg_latency_ms":100,"success_rate":99.0,"avg_tps":20.0}]},{"group":"vip","avg_ttft_ms":20,"avg_latency_ms":200,"success_rate":80.0,"avg_tps":10.0,"series":[{"ts":1760000200,"avg_ttft_ms":20,"avg_latency_ms":200,"success_rate":80.0,"avg_tps":10.0}]}]}}"""

    private fun drain(expected: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(expected)
        repeat(expected) {
            out += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request #$it (expected $expected)" }
        }
        return out
    }

    private fun count(db: AtlasDatabase, table: String): Long {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst())
            return it.getLong(0)
        }
    }

    private class FakeClock(var now: Long) : Clock {
        override fun epochMillis(): Long = now
        override fun monotonicMillis(): Long = now
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
     * 測試傳輸接縫：把已通過生產驗證的 `https` request 改寫為 loopback MockWebServer 真 socket
     * （scheme/host/port 替換；path+query+Authorization 原樣保留）。DB 不存 loopback、不存 secret。
     */
    private class RewriteToMock(private val server: MockWebServer) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val target = orig.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            return chain.proceed(orig.newBuilder().url(target).build())
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
