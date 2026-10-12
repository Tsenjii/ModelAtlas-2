package dev.modelatlas.app.onboarding

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.RoomWorkflowStore
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.network.OpenAiTransport
import dev.modelatlas.core.onboarding.OnboardingFailureReason
import dev.modelatlas.core.onboarding.OnboardingStateException
import dev.modelatlas.core.onboarding.OnboardingStatus
import dev.modelatlas.core.onboarding.OnboardingStepId
import dev.modelatlas.core.onboarding.OnboardingStepStatus
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
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
 * Quick Add 應用層整合測試：真 [AtlasDatabase]（檔案 DB，用完關閉刪除）、真
 * [AndroidCredentialVault]（AndroidKeyStore，UUID safe refs）、MockWebServer loopback；
 * service 與 engine 兩條 transport 都開 `allowLoopbackHttpForTests`。
 *
 * 覆蓋的邊界：
 * 1. 非法 `baseUrl` 在寫 vault 之前就被拒，錯誤訊息不回顯輸入。
 * 2. 有 key 時以 https 等價 origin 寫入 vault，snapshot 只留 safe ref。
 * 3. 無 key 時停在 NEEDS_INPUT，且不送任何帶憑證的請求。
 * 4. 匿名 inspect 不碰 vault（可觀察面：HEAD 無 Authorization、vault 條目不變）。
 * 5. commit 落 Provider/Account/Credential/Endpoint/Offering/Availability，且重放冪等。
 * 6. `abandon` 清孤兒 vault refs；已 commit 的 `abandon` 先拋錯且 vault 保留。
 * 7. 關閉資料庫後全檔掃描，找不到 fixture secret 的任何常見編碼明文。
 *
 * 已知限制（待主 session 修復）：
 * - `RoomCommitter` 走 `EndpointPolicy` 嚴格 HTTPS，loopback http `baseUrl` 無法 commit；
 *   測試 5、6、7 先斷言這個現況（且不寫半筆），再把 baseUrl 正規化成 https 等價值走完 commit。
 * - engine 的 `OpenAiTransport` 以 http origin 解析憑證，vault 只認 https origin，
 *   所以 `TEST_MODEL` 不會真的送出 chat 請求；測試 4 把這個現況釘住。
 *
 * 不讀真實憑證；secret 為固定 fixture 字串，teardown 只刪本檔建立的 vault ref 與 DB。
 */
@RunWith(AndroidJUnit4::class)
class QuickAddServiceTest {
    private lateinit var context: Context
    private lateinit var vault: AndroidCredentialVault
    private lateinit var repository: AtlasRepository
    private lateinit var service: QuickAddService
    private lateinit var server: MockWebServer

    private var database: AtlasDatabase? = null
    private var vaultKeysBefore: Set<String> = emptySet()

    /** preflight 自己打的那一發；之後所有「請求數」都以它為基準。 */
    private var requestsAtSetup: Int = 0
    private var consumedRequests: Int = 0

    private val databaseName = "quickadd-it-" + UUID.randomUUID() + ".db"
    private val ownedRefs = mutableListOf<String>()
    private val secret = "quickadd-fixture-secret-4f8c1d2e9a7b"
    private val models = listOf("fixture-model-a", "fixture-model-b")

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        vault = AndroidCredentialVault(context)
        database = Room.databaseBuilder(context, AtlasDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        repository = AtlasRepository(requireDb())
        val engine = InferenceEngine(
            OpenAiTransport(vault, OkHttpClient(), allowLoopbackHttpForTests = true),
            repository,
        )
        server = MockWebServer()
        server.start()
        service = QuickAddService(
            vault = vault,
            engine = engine,
            database = requireDb(),
            probeClient = OkHttpClient(),
            allowLoopbackHttpForTests = true,
        )
        vaultKeysBefore = vaultPreferenceKeys()
        // 探測前先確認這台裝置允許 loopback cleartext；被 NetworkSecurityPolicy 擋下時直接報清楚原因。
        runBlocking { assertLoopbackCleartextReachable() }
        // preflight 那一發已在上面取用過，之後的請求數都以這裡為基準。
        requestsAtSetup = server.requestCount
        consumedRequests = server.requestCount
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        for (ref in ownedRefs) runCatching { vault.remove(ref) }
        ownedRefs.clear()
        deleteDatabaseFiles()
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun invalidBaseUrlIsRejectedBeforeTheVaultIsTouched() = within {
        val rejected = listOf(
            "   ",
            "http://api.fixture.invalid/v1",
            "https://user:$secret@api.fixture.invalid/v1",
            "https://api.fixture.invalid/v1?api-key=$secret",
            "https://api.fixture.invalid/v1#$secret",
            "not-a-url",
        )
        for (candidate in rejected) {
            val failure = runCatching { service.startQuickAdd(candidate, secretTransient = secret) }
            assertTrue("must reject: $candidate", failure.isFailure)
            val problem = failure.exceptionOrNull()!!
            assertTrue("expected IllegalArgumentException for $candidate", problem is IllegalArgumentException)
            // 固定安全字串：不含 URL、不含 key、不含 server body。
            assertEquals("\u8F38\u5165\u7121\u6548", problem.message)
            assertFalse(problem.message.orEmpty().contains(secret))
            assertFalse(problem.message.orEmpty().contains("fixture.invalid"))
        }
        assertEquals("no vault entry may be created", vaultKeysBefore, vaultPreferenceKeys())
        assertEquals(0, countRows("onboarding_snapshots"))
        assertEquals(0, countRows("onboarding_commits"))
        assertEquals("no request may be sent for a rejected baseUrl", requestsAtSetup, server.requestCount)
    }

    @Test
    fun secretIsStoredUnderTheHttpsEquivalentOriginAndSnapshotHoldsOnlySafeRef() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        val ref = onlyOwnedRef(workflowId)

        // vault：origin 是 http loopback 的 https 等價值（同 host/port），值只可還原回自己。
        assertTrue(vault.has(ref))
        assertEquals(loopbackHttpsOrigin(), vaultOriginOf(ref))
        assertEquals(secret, vault.bearerToken(ref, loopbackHttpsOrigin()))
        assertEquals(secret, vault.bearerToken(ref, "${loopbackHttpsOrigin()}/"))
        val rebound = runCatching { vault.bearerToken(ref, "https://other.fixture.invalid:443") }
        assertTrue("a ref must stay bound to its own origin", rebound.isFailure)

        // snapshot：只有 safe ref，沒有 secret。
        val snapshot = snapshotOf(workflowId)
        assertEquals(listOf(ref), snapshot.safeCredentialIds)
        assertEquals(1, snapshot.credentials.size)
        assertEquals(ref, snapshot.credentials.single().safeCredentialId)
        assertTrue(snapshot.credentials.single().label.isNotBlank())
        assertTrue(ref.matches(Regex("[0-9a-fA-F-]{36}")))
        val json = snapshotJsonOf(workflowId)
        assertFalse(json.contains(secret))
        assertTrue(json.contains(ref))

        // 探測真的帶著 Bearer 打到 loopback（http origin → https 等價 origin 的映射生效）。
        val requests = drainRequests()
        val credentialed = requests.filter { it.getHeader("Authorization") != null }
        assertEquals(2, credentialed.size)
        assertTrue(credentialed.all { it.method == "GET" && it.path == MODELS_PATH })
        assertTrue(credentialed.all { it.getHeader("Authorization") == "Bearer $secret" })

        // 流程停在人工確認前，review 已有真實探測結果。
        assertEquals(OnboardingStatus.NEEDS_INPUT, snapshot.status)
        assertEquals(models.size, service.reviewSummary(workflowId).modelsFound ?: -1)
    }

    @Test
    fun anonymousQuickAddStopsAtNeedsInputWithoutSendingAnyCredentialedRequest() = within {
        enqueueInspect()
        val workflowId = service.startQuickAdd(loopbackBase())

        val snapshot = snapshotOf(workflowId)
        assertEquals("an anonymous run must not create a vault entry", vaultKeysBefore, vaultPreferenceKeys())
        assertEquals(OnboardingStatus.NEEDS_INPUT, snapshot.status)
        assertEquals(OnboardingStepStatus.NEEDS_INPUT, snapshot.stepStatuses[OnboardingStepId.AUTH])
        assertEquals(
            OnboardingFailureReason.AUTH_MISSING_CREDENTIAL,
            snapshot.results.last { it.step == OnboardingStepId.AUTH }.reason,
        )

        // 零憑證請求：inspect + detectType 兩次 HEAD 之後就停住，後續步驟完全沒發。
        val requests = drainRequests()
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.method == "HEAD" && it.path == BASE_PATH })
        assertTrue(requests.none { it.getHeader("Authorization") != null })
        // 有界觀察窗（不是固定 sleep）：期間不得再抵達任何請求。
        assertNull(server.takeRequest(QUIET_WINDOW_MS, TimeUnit.MILLISECONDS))
        assertEquals(requestsAtSetup + 2, server.requestCount)
        assertEquals(0, countRows("onboarding_commits"))
        assertEquals(0, countRows("credentials"))
    }

    @Test
    fun anonymousInspectNeverResolvesTheVault() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        val ref = onlyOwnedRef(workflowId)

        val requests = drainRequests()
        val heads = requests.filter { it.method == "HEAD" }
        assertEquals(2, heads.size)
        assertTrue(heads.all { it.path == BASE_PATH })
        assertTrue("inspect must stay unauthenticated", heads.all { it.getHeader("Authorization") == null })
        assertEquals(
            "inspect/detectType must not add or replace vault entries",
            vaultKeysBefore + setOf("$ref.origin", "$ref.iv", "$ref.ciphertext"),
            vaultPreferenceKeys(),
        )
        assertTrue(vault.has(ref))
        assertEquals(secret, vault.bearerToken(ref, loopbackHttpsOrigin()))

        // 同一輪只有 catalog 路徑帶憑證。engine 的 chat 端點因為 vault 只認 https origin 而不發送。
        val credentialed = requests.filter { it.getHeader("Authorization") != null }
        assertEquals(2, credentialed.size)
        assertTrue(credentialed.all { it.path == MODELS_PATH })
        assertTrue(
            "known gap: the engine transport resolves the credential with the http origin, " +
                "which the vault refuses, so TEST_MODEL must not reach the server",
            requests.none { it.method == "POST" },
        )
        assertEquals(OnboardingStatus.NEEDS_INPUT, snapshotOf(workflowId).status)
        assertEquals(0, countRows("onboarding_commits"))
    }

    @Test
    fun commitWritesEveryTableAndReplayingItIsIdempotent() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        val ref = onlyOwnedRef(workflowId)
        val probeRequests = server.requestCount - requestsAtSetup
        assertEquals("inspect + detectType + credentialTest + listModels + pricingQuotaNotice(/api/status)", 5, probeRequests)

        // 現況：loopback http baseUrl 過不了 EndpointPolicy 的 HTTPS 檢查，且整筆不落地。
        val rawFailure = runCatching { service.approveAndCommit(workflowId) }
        assertTrue(rawFailure.isFailure)
        assertEquals("commit 未完成", rawFailure.exceptionOrNull()!!.message)
        assertEquals(0, countRows("providers"))
        assertEquals(0, countRows("accounts"))
        assertEquals(0, countRows("credentials"))
        assertEquals(0, countRows("endpoints"))
        assertEquals(0, countRows("offerings"))
        assertEquals(0, countRows("credential_availability"))
        assertNull(requireDb().onboardingCommitDao().getByWorkflow(workflowId))
        assertTrue("a rejected commit must not touch the vault", vault.has(ref))
        assertEquals("a rejected commit must not probe again", requestsAtSetup + probeRequests, server.requestCount)

        val httpsBase = promoteBaseUrlToHttps(workflowId)
        val receipt = service.approveAndCommit(workflowId, approvedBy = "integration-test")
        assertEquals("commit-$workflowId", receipt.commitId)
        val providerId = requireNotNull(receipt.providerId)

        assertEquals(1, countRows("providers"))
        assertEquals(1, countRows("accounts"))
        assertEquals(1, countRows("credentials"))
        assertEquals(1, countRows("endpoints"))
        assertEquals(models.size, countRows("offerings"))
        assertEquals(models.size, countRows("credential_availability"))
        assertEquals(1, countRows("onboarding_commits"))

        val provider = requireNotNull(requireDb().providerDao().getById(providerId))
        assertTrue(provider.name.isNotBlank())
        assertNull("no check-in evidence means no invented check-in", provider.checkinUrl)
        assertFalse(provider.requiresDailyCheckin)
        assertEquals("UNKNOWN", provider.officialHealth)
        assertEquals("UNKNOWN", provider.observedHealth)

        val accounts = requireDb().accountDao().byProvider(providerId)
        assertEquals(1, accounts.size)
        val credential = requireDb().credentialDao().getById(ref)
        assertEquals(accounts.single().accountId, credential?.accountId)
        assertTrue(credential?.label?.isNotBlank() == true)
        // 憑證表只有 safe ref 與 metadata，沒有任何密鑰欄位可用。
        assertEquals(ref, credential?.credentialId)

        val connections = awaitFlow { repository.managementConnections.first() }
        assertEquals(models.size, connections.size)
        val endpoint = requireDb().endpointDao().getById(connections.first().endpointId)
        assertEquals(httpsBase, endpoint?.baseUrl)
        assertEquals("$httpsBase/chat/completions", endpoint?.endpointUrl)
        assertTrue(connections.all { it.priceState == "UNKNOWN" && it.quotaState == "UNKNOWN" })
        for (modelId in models) {
            val offeringId = connections.first { it.modelId == modelId }.offeringId
            val offering = requireNotNull(requireDb().offeringDao().getById(offeringId))
            assertEquals("ONBOARDING", offering.source)
            assertFalse("catalog models are not user-typed", offering.manual)
        }
        assertEquals(models.size, requireDb().availabilityDao().byCredential(ref).size)
        assertEquals(providerId, requireDb().onboardingCommitDao().getByWorkflow(workflowId)?.providerId)
        assertEquals(OnboardingStatus.COMMITTED, snapshotOf(workflowId).status)

        // telemetry：即使 testModel 因 vault 作用域失敗，仍保存 BACKGROUND_PROBE 樣本且只含 safe ref。
        val samples = awaitFlow { requireDb().sampleDao().observeLatest().first() }
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.source == TelemetrySource.BACKGROUND_PROBE.name })
        assertTrue(samples.all { it.safeCredentialId == ref })
        assertTrue("no probe may stay RUNNING", samples.none { it.outcome == "RUNNING" })
        assertEquals(samples.size, countRows("evidence"))

        // 冪等重放：同一 receipt、同一 provider，不長出第二套資料。
        val replayed = service.approveAndCommit(workflowId, approvedBy = "integration-test-replay")
        assertEquals(receipt, replayed)
        assertEquals(1, countRows("providers"))
        assertEquals(1, countRows("accounts"))
        assertEquals(1, countRows("credentials"))
        assertEquals(1, countRows("endpoints"))
        assertEquals(models.size, countRows("offerings"))
        assertEquals(models.size, countRows("credential_availability"))
        assertEquals(1, countRows("onboarding_commits"))
        assertEquals(
            connections.map { it.id }.toSet(),
            awaitFlow { repository.managementConnections.first() }.map { it.id }.toSet(),
        )
    }

    /**
     * PROV-002 repair：Quick Add 全鏈的 `PRICING_QUOTA_NOTICE` 對已知 adapter
     *（此處為 New API structured surface）真嘗試 capabilities／token-quota／notice，
     * 不再是固定 placeholder；Review 承接真實 quota／notice 結論。
     */
    @Test
    fun pricingQuotaNoticeReallyAttemptsKnownAdapterSurface() = within {
        enqueueInspect()
        enqueueCatalog(models)
        enqueueCatalog(models)
        enqueueNewApiFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        onlyOwnedRef(workflowId)

        // 已知 adapter 面真的被打到，且順序固定：capabilities → token-quota → notice。
        val requests = drainRequests()
        assertEquals(
            listOf(BASE_PATH, BASE_PATH, MODELS_PATH, MODELS_PATH, "/api/status", "/api/usage/token/", "/api/notice"),
            requests.map { it.path },
        )
        val credentialed = requests.filter { it.getHeader("Authorization") != null }
        assertEquals("catalog x2 + token-quota x1", 3, credentialed.size)
        assertTrue(credentialed.all { it.getHeader("Authorization") == "Bearer $secret" })

        val snapshot = snapshotOf(workflowId)
        val pricing = snapshot.results.last { it.step == OnboardingStepId.PRICING_QUOTA_NOTICE }
        assertEquals(OnboardingStepStatus.SUCCESS, pricing.status)
        assertEquals("capabilities + token-quota + notice 各 1 發", 3, pricing.requestsSpent)
        assertEquals(QuotaState.QUOTA_AVAILABLE, pricing.pricingQuota?.quotaStatus)
        assertEquals(true, pricing.pricingQuota?.noticeFetched)
        assertEquals(true, pricing.pricingQuota?.requiresDailyCheckin)
        // Quick Add 無 PAT：pricing 恆記不支援＋evidence，不猜價格。
        assertEquals(dev.modelatlas.core.onboarding.PriceStatus.UNKNOWN, pricing.pricingQuota?.pricingStatus)
        assertTrue(pricing.evidenceIds.contains("newapi-token-quota"))
        assertTrue(pricing.evidenceIds.contains("newapi-notice"))

        // Review 承接真實結論（非 placeholder）。
        assertEquals(QuotaState.QUOTA_AVAILABLE, service.reviewSummary(workflowId).quotaStatus)
        assertEquals(OnboardingStatus.NEEDS_INPUT, snapshot.status)
    }

    @Test
    fun abandonRemovesOrphanVaultRefsButRefusesAfterCommitAndKeepsThem() = within {
        // (a) 未 commit 就放棄：孤兒 safe ref 從 vault 清掉，產品資料不落地。
        enqueueInspect()
        enqueueCatalogFlow()
        val abandoned = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        val abandonedRef = onlyOwnedRef(abandoned)
        assertTrue(vault.has(abandonedRef))

        service.abandon(abandoned, reason = "integration-test-abandon")

        assertFalse("abandon must clear its own orphan ref", vault.has(abandonedRef))
        assertEquals(OnboardingStatus.ABANDONED, snapshotOf(abandoned).status)
        assertEquals(0, countRows("providers"))
        assertEquals(0, countRows("onboarding_commits"))
        assertFalse(vaultPreferenceKeys().any { it.startsWith("$abandonedRef.") })

        // (b) 已 commit 再放棄：先拋錯、vault 保留、產品資料不動。
        // (a) 留下 3 個未消耗的 SSE enqueue（TEST_MODEL 在送出前即因 vault 綁定失敗；
        // 真實伺服器沒有 queue 問題）。重置 dispatcher 讓 (b) 從乾淨 queue 開始，
        // 否則 LIST_MODELS 會吃到舊 SSE → catalog 失敗 → TEST_MODEL 無模型 → REVIEW 到不了。
        server.dispatcher = QueueDispatcher()
        enqueueInspect()
        enqueueCatalogFlow()
        val committed = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        val committedRef = onlyOwnedRef(committed)
        promoteBaseUrlToHttps(committed)
        val providerId = requireNotNull(service.approveAndCommit(committed).providerId)
        assertTrue(vault.has(committedRef))

        val failure = runCatching { service.abandon(committed) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is OnboardingStateException)
        assertEquals(
            "\u5DF2 commit \u7684 workflow \u4E0D\u53EF abandon",
            failure.exceptionOrNull()!!.message,
        )
        assertTrue("a refused abandon must keep the credential", vault.has(committedRef))
        assertEquals(secret, vault.bearerToken(committedRef, loopbackHttpsOrigin()))
        assertEquals(1, countRows("providers"))
        assertEquals(1, countRows("onboarding_commits"))
        assertEquals(OnboardingStatus.COMMITTED, snapshotOf(committed).status)
        assertNotNull(requireDb().providerDao().getById(providerId))

        // 空白 workflowId 一律固定安全字串，不回顯輸入。
        val blankAbandon = runCatching { service.abandon("") }
        assertTrue(blankAbandon.isFailure)
        assertEquals("workflow \u4E0D\u5B58\u5728", blankAbandon.exceptionOrNull()!!.message)
        val blankCommit = runCatching { service.approveAndCommit("") }
        assertTrue(blankCommit.isFailure)
        assertEquals("workflow \u4E0D\u5B58\u5728", blankCommit.exceptionOrNull()!!.message)
    }

    @Test
    fun noPlaintextFixtureSecretSurvivesInTheDatabaseOrThePreferenceFile() = within {
        enqueueInspect()
        enqueueCatalogFlow()
        val workflowId = service.startQuickAdd(loopbackBase(), secretTransient = secret)
        val ref = onlyOwnedRef(workflowId)
        promoteBaseUrlToHttps(workflowId)
        service.approveAndCommit(workflowId)
        // 探測、telemetry 與產品資料都要真的落地，掃描才有意義。
        assertTrue(drainRequests().any { it.getHeader("Authorization") == "Bearer $secret" })
        assertTrue(awaitFlow { requireDb().sampleDao().observeLatest().first() }.isNotEmpty())
        assertTrue(awaitFlow { repository.managementConnections.first() }.isNotEmpty())
        assertTrue(vault.has(ref))

        // 關閉後才掃描，確保 WAL 已 checkpoint 進主檔。
        closeDatabase()
        val databaseFile = context.getDatabasePath(databaseName)
        val scanned = listOf(
            databaseFile,
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
            File(databaseFile.path + "-journal"),
            sharedPreferencesFile(),
        )
        for (file in scanned) {
            if (!file.isFile) continue
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            for (variant in secretVariants(secret)) {
                assertFalse("plaintext secret variant left in ${file.name}", text.contains(variant))
            }
        }
        // 反向證明：vault 確實存了東西，而且是密文不是明文。
        val prefs = sharedPreferencesFile().readText()
        assertTrue(prefs.contains("$ref.ciphertext"))
        assertTrue(prefs.contains("$ref.origin"))
        assertFalse(prefs.contains(secret))
    }

    // ------------------------------------------------------------- helpers

    private fun <T> within(block: suspend CoroutineScope.() -> T): T =
        runBlocking { withTimeout(TEST_TIMEOUT_MS) { block() } }

    /** Flow 等待一律有界；不用固定 sleep。 */
    private suspend fun <T> awaitFlow(block: suspend () -> T): T = withTimeout(FLOW_TIMEOUT_MS) { block() }

    private fun requireDb(): AtlasDatabase = database ?: error("database already closed")

    private fun loopbackBase(): String = "http://$LOOPBACK_HOST:${server.port}$BASE_PATH"

    private fun loopbackHttpsBase(): String = "https://$LOOPBACK_HOST:${server.port}$BASE_PATH"

    private fun loopbackHttpsOrigin(): String = "https://$LOOPBACK_HOST:${server.port}"

    private suspend fun snapshotOf(workflowId: String): OnboardingSnapshot =
        requireNotNull(RoomWorkflowStore(requireDb()).load(workflowId)) { "snapshot missing: $workflowId" }

    private suspend fun snapshotJsonOf(workflowId: String): String =
        requireNotNull(requireDb().onboardingSnapshotDao().getByWorkflow(workflowId)).snapshotJson

    /** 記下本測試建立的 vault ref，teardown 只清這些。 */
    private suspend fun onlyOwnedRef(workflowId: String): String {
        val ref = snapshotOf(workflowId).safeCredentialIds.single()
        ownedRefs += ref
        return ref
    }

    /**
     * `RoomCommitter` 走 `EndpointPolicy` 嚴格 HTTPS，loopback http `baseUrl` 不可 commit。
     * 這裡只把 baseUrl 正規化成同一 host/port 的 https 等價值，其餘狀態全部來自真實探測。
     */
    private suspend fun promoteBaseUrlToHttps(workflowId: String): String {
        assertEquals(loopbackBase(), snapshotOf(workflowId).baseUrl)
        RoomWorkflowStore(requireDb()).save(snapshotOf(workflowId).copy(baseUrl = loopbackHttpsBase()))
        assertEquals(loopbackHttpsBase(), snapshotOf(workflowId).baseUrl)
        return loopbackHttpsBase()
    }

    private fun countRows(table: String): Int = requireDb().openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /**
     * 探測流程已回傳，伺服器端沒有 in-flight 請求，所以「尚未取用的數量」就是確定性清單長度。
     * 不含 preflight 與先前已取用的請求。
     */
    private fun drainRequests(): List<RecordedRequest> {
        val pending = server.requestCount - consumedRequests
        val out = ArrayList<RecordedRequest>(pending)
        repeat(pending) { index ->
            val request = server.takeRequest(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (request == null) throw AssertionError("missing request #${consumedRequests + index}")
            consumedRequests++
            out += request
        }
        return out
    }

    private fun vaultPreferenceKeys(): Set<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.keys.toSet()

    private fun vaultOriginOf(ref: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("$ref.origin", null)

    private fun sharedPreferencesFile(): File {
        val base = context.applicationInfo.dataDir ?: context.filesDir.parent
        ?: throw AssertionError("app data dir unavailable")
        return File(File(base, "shared_prefs"), "$PREFERENCES.xml")
    }

    private fun closeDatabase() {
        database?.let { runCatching { it.close() } }
        database = null
    }

    private fun deleteDatabaseFiles() {
        closeDatabase()
        runCatching { context.deleteDatabase(databaseName) }
        val path = context.getDatabasePath(databaseName).path
        for (suffix in listOf("", "-wal", "-shm", "-journal")) runCatching { File(path + suffix).delete() }
    }

    private fun secretVariants(value: String): List<String> = listOf(
        value,
        value.reversed(),
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)),
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)),
        URLEncoder.encode(value, "UTF-8"),
    )

    // ------------------------------------------------------------ fixtures

    /** INSPECT 與 DETECT_TYPE 各送一次 HEAD。 */
    private fun enqueueInspect() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)) }
    }

    private fun enqueueCatalog(modelIds: List<String>) {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", JSON).setBody(catalog(modelIds)),
        )
    }

    private fun catalog(ids: List<String>): String = buildString {
        append("""{"object":"list","data":[""")
        ids.forEachIndexed { index, id ->
            if (index > 0) append(',')
            append("""{"id":"$id","object":"model"}""")
        }
        append("]}")
    }

    /** CREDENTIAL_TEST 與 LIST_MODELS 各吃一組回應；chat 端點備著但目前不會被送到。 */
    private fun enqueueCatalogFlow() {
        enqueueCatalog(models)
        enqueueCatalog(models)
        repeat(MAX_TEST_MODELS) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", SSE)
                    .setHeadersDelay(HEADERS_DELAY_MS, TimeUnit.MILLISECONDS)
                    .setBody(chatStream()),
            )
        }
    }

    /**
     * 已知 adapter（New API 認證版 `v1.0.0-rc.25`）面：capabilities 報 certified＋
     * checkin 開啟；token-quota 報可用；notice 報存在。由 `pricingQuotaNotice`
     * 依序取用（capabilities → token-quota → notice），各 1 個 request。
     */
    private fun enqueueNewApiFlow() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("""{"success":true,"data":{"version":"v1.0.0-rc.25","checkin_enabled":true}}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("""{"code":true,"data":{"total_available":5000,"total_granted":10000,"total_used":5000,"unlimited_quota":false,"expires_at":0}}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("""{"success":true,"data":{"content":"fixture-notice"}}"""),
        )
    }

    private fun chatStream(): String = buildString {
        append("""data: {"model":"fixture-model-a","choices":[{"index":0,"delta":{"content":"ok"}}]}""")
        append("\n\n")
        append("""data: {"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
        append("\n\n")
        append("data: [DONE]\n\n")
    }

    /** 確認這台裝置沒有擋 loopback cleartext，否則整合測試的前提不成立。 */
    private suspend fun assertLoopbackCleartextReachable() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", JSON))
        val call = OkHttpClient().newCall(
            Request.Builder().url(loopbackBase()).head().build(),
        )
        val outcome = runCatching { call.execute().use { it.code } }
        val error = outcome.exceptionOrNull()
        if (error != null) {
            throw AssertionError(
                "loopback cleartext is not usable on this device (API ${android.os.Build.VERSION.SDK_INT}; " +
                    "the app manifest sets usesCleartextTraffic=false). QuickAddService integration tests " +
                    "need app/build.gradle.kts androidTestImplementation(mockwebserver:4.12.0) applied AND a " +
                    "debug/androidTest network security policy that permits cleartext to 127.0.0.1",
                error,
            )
        }
        assertEquals(200, outcome.getOrThrow())
        val recorded = requireNotNull(server.takeRequest(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertEquals("HEAD", recorded.method)
        assertEquals(BASE_PATH, recorded.path)
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val BASE_PATH = "/v1"
        const val MODELS_PATH = "/v1/models"
        const val JSON = "application/json"
        const val SSE = "text/event-stream"
        const val PREFERENCES = "credential_ciphertexts"
        const val MAX_TEST_MODELS = 3
        const val TEST_TIMEOUT_MS = 180_000L
        const val FLOW_TIMEOUT_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val QUIET_WINDOW_MS = 1_000L
        const val HEADERS_DELAY_MS = 50L
    }
}
