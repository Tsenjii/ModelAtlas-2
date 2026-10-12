package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.NewApiAccountQuota
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NINT-005A observer hook acceptance（device androidTest；本檔唯一新增）。
 *
 * 鎖定 [NewApiSnapshotStore.syncAccountQuota] 的 `AccountQuotaObserver` 接縫：
 * - 真 [NewApiSnapshotStore]（既有 quota transaction＋readback 不動）＋真 [NewApiAdapter]
 *   HTTP 棧＋真 in-memory Room＋MockWebServer loopback 真 socket。
 * - 傳輸改寫只存在測試層：DB baseUrl 維持 `https://…`（生產 EndpointPolicy gate），
 *   OkHttp Interceptor 僅換 scheme/host/port 到 loopback；path／Authorization 原樣。
 * - 觀察 sink 為本檔 fixture（僅測試，不當產品資料；secret 全 fake 只走 loopback）。
 *
 * 覆盖（每項真 production hook 路徑，非 fixture store）：
 * 1. default-null observer：舊 direct-store 契約完全不變（零 observation、原 2-call 路徑）。
 * 2. observer 恰好一次 delivery，quota commit/readback 之後；零額外 HTTP（無第二次 user/self）。
 * 3. 普通 sink 例外 fail-open：quota 回傳值與落庫完全不變。
 * 4. CancellationException 在 quota 已 commit 後原樣重拋（不吞、不偽裝成功）。
 * 5. 不安全 PAT safe ref：任何 HTTP 之前 fail-fast（零請求、零寫入）。
 */
@RunWith(AndroidJUnit4::class)
class NewApiAccountUsageObserverTest {

    private lateinit var db: AtlasDatabase
    private lateinit var server: MockWebServer

    private val providerId = "napi-usage-obs-p"
    private val accountId = "napi-usage-obs-a"
    private val patRef = "napi-usage-obs-pat"
    private val patSecret = "usage-obs-fake-pat-secret-0123456789"
    private val endpointId = "napi-usage-obs-e"
    private val baseHttps = "https://$providerId.example.com/v1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
    }

    // ===== 1. default-null observer：舊 direct-store 契約完全不變 =====

    @Test
    fun defaultNullObserverKeepsLegacySyncPathWithNoExtraHttp() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            seedProvider()
            enqueueQuotaSync()
            val store = NewApiSnapshotStore(db)
            val vault = CountingVault(mapOf(patRef to patSecret))
            val stored = store.syncAccountQuota(
                NewApiAdapter(vault, loopbackClient()), providerId, accountId, AccountPatRef(patRef),
            )
            // 原路徑：恰好 caps＋account 兩個 HTTP，無任何額外 /api/user/self。
            val recorded = drainRequests(expected = 2)
            assertEquals(listOf("/api/status", "/api/user/self"), recorded.map { it.path!! })
            assertEquals("Bearer $patSecret", recorded[1].getHeader("Authorization"))
            assertEquals(listOf("$patRef@${originOf(baseHttps)}"), vault.asked.toList())
            // quota readback 結果照舊；本例 head 存在且數值來自真 adapter 解析。
            assertNotNull(stored.latest)
            assertEquals(200, db.evidenceDao().getById(stored.latest!!.evidenceId)!!.status)
            assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, db.evidenceDao().getById(stored.latest!!.evidenceId)!!.source)
            assertEquals(1_000_000L, stored.latest!!.remainingRaw)
            assertEquals(250_000L, stored.latest!!.usedRaw)
        }
    }

    // ===== 2. 恰好一次 observation，在 quota commit＋readback 之後；零額外 HTTP =====

    @Test
    fun observerReceivesExactlyOneObservationAfterQuotaCommitWithNoExtraHttp() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            seedProvider()
            enqueueQuotaSync()
            val sink = RecordingObserver(db)
            val store = NewApiSnapshotStore(db, sink)
            val stored = store.syncAccountQuota(
                NewApiAdapter(CountingVault(mapOf(patRef to patSecret)), loopbackClient()),
                providerId, accountId, AccountPatRef(patRef),
            )
            // 恰好一次 delivery；HTTP 契約維持原 2-call，無第二次 /api/user/self。
            assertEquals(1, sink.observations.size)
            val recorded = drainRequests(expected = 2)
            assertEquals(listOf("/api/status", "/api/user/self"), recorded.map { it.path!! })
            // Delivery 在 quota commit＋readback 之後：觀察當下 head 已存在，且回傳值與落庫一致。
            val observation = sink.observations.single()
            assertTrue("observation must follow quota commit", observation.headCommitted)
            assertEquals(providerId, observation.observation.providerId)
            assertEquals(accountId, observation.observation.accountId)
            assertEquals(patRef, observation.observation.patRef.value)
            assertEquals(baseHttps, observation.observation.validatedBaseUrl)
            // 同 session caps／result 原樣轉傳，不重寫 source。
            val caps = observation.observation.capabilities as NewApiResult.Success
            assertEquals(NewApiVersion.RC25, caps.value.version.raw)
            assertEquals(NewApiVersionSupport.CERTIFIED_RC25, caps.value.version.support)
            assertEquals("newapi/api/status", caps.evidence.source)
            val result = observation.observation.result as NewApiResult.Success
            assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, result.evidence.source)
            assertEquals(200, result.evidence.httpStatus)
            assertEquals(42L, (result.value as NewApiAccountQuota).requestCount)
            assertEquals(250_000L, (result.value as NewApiAccountQuota).usedQuota)
            // quota readback 結果照舊；observer 不影響回傳值。
            assertNotNull(stored.latest)
            assertEquals(250_000L, stored.latest!!.usedRaw)
        }
    }

    // ===== 3. 普通 sink 例外 fail-open：quota 回傳值與落庫完全不變 =====

    @Test
    fun ordinaryObserverFailureStillReturnsCommittedQuota() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            seedProvider()
            enqueueQuotaSync()
            val sink = RecordingObserver(db, failure = IllegalStateException("usage sink exploded"))
            val store = NewApiSnapshotStore(db, sink)
            val stored = store.syncAccountQuota(
                NewApiAdapter(CountingVault(mapOf(patRef to patSecret)), loopbackClient()),
                providerId, accountId, AccountPatRef(patRef),
            )
            // quota enrichment 不因 usage persistence 失敗而失敗；證據照落庫。
            assertEquals(1, sink.observations.size)
            assertNotNull(stored.latest)
            assertEquals(250_000L, stored.latest!!.usedRaw)
            val evidence = db.evidenceDao().getById(stored.latest!!.evidenceId)!!
            assertEquals(200, evidence.status)
            assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, evidence.source)
            assertEquals(1_000_000L, stored.latest!!.remainingRaw)
            val recorded = drainRequests(expected = 2)
            assertEquals(listOf("/api/status", "/api/user/self"), recorded.map { it.path!! })
        }
    }

    // ===== 4. CancellationException 在 quota 已 commit 後原樣重拋 =====

    @Test
    fun cancellationExceptionFromObserverPropagatesAfterQuotaCommit() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            seedProvider()
            enqueueQuotaSync()
            val sink = RecordingObserver(db, failure = CancellationException("caller cancelled"))
            val store = NewApiSnapshotStore(db, sink)
            try {
                store.syncAccountQuota(
                    NewApiAdapter(CountingVault(mapOf(patRef to patSecret)), loopbackClient()),
                    providerId, accountId, AccountPatRef(patRef),
                )
                fail("CancellationException must propagate, not be swallowed")
            } catch (cancelled: CancellationException) {
                assertEquals("caller cancelled", cancelled.message)
            }
            // Cancel 前 quota transaction 已 commit：head／evidence 保留（last-good 不被抹掉）。
            assertEquals(1, sink.observations.size)
            val head = db.snapshotFactDao().head(
                NewApiSnapshotStore.accountQuotaScope(providerId, accountId).storageKey(),
                SnapshotKind.ACCOUNT_QUOTA.name,
            )
            assertNotNull("quota commit must survive observer cancellation", head)
            val evidence = db.evidenceDao().getById(head!!.latestEvidenceId)!!
            assertEquals(200, evidence.status)
            assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, evidence.source)
            val recorded = drainRequests(expected = 2)
            assertEquals(listOf("/api/status", "/api/user/self"), recorded.map { it.path!! })
        }
    }

    // ===== 5. 不安全 PAT safe ref：任何 HTTP 之前 fail-fast =====

    @Test
    fun invalidSafeRefFailsBeforeAnyHttp() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            seedProvider(patRef = "usage obs unsafe ref")
            val sink = RecordingObserver(db)
            val store = NewApiSnapshotStore(db, sink)
            try {
                store.syncAccountQuota(
                    NewApiAdapter(CountingVault(mapOf("usage obs unsafe ref" to patSecret)), loopbackClient()),
                    providerId, accountId, AccountPatRef("usage obs unsafe ref"),
                )
                fail("unsafe account usage ref must be rejected")
            } catch (_: IllegalArgumentException) {
            }
            // 零 HTTP（caps 也不發）、零 observation、零 quota 寫入。
            assertEquals(0, server.requestCount)
            assertEquals(0, sink.observations.size)
            assertNullHead()
        }
    }

    // ===== harness（fixture 僅本檔，不當產品資料；secret 全 fake 只走 loopback） =====

    /** 觀察 sink fixture：記錄 delivery 次數／內容，並在觀察當下檢查 quota head 是否已 commit。 */
    private class RecordingObserver(
        private val db: AtlasDatabase,
        private val failure: Exception? = null,
    ) : AccountQuotaObserver {
        class Entry(val observation: AccountQuotaObservation, val headCommitted: Boolean)

        val observations = mutableListOf<Entry>()

        override suspend fun onObserved(observation: AccountQuotaObservation) {
            val head = db.snapshotFactDao().head(
                NewApiSnapshotStore.accountQuotaScope(observation.providerId, observation.accountId).storageKey(),
                SnapshotKind.ACCOUNT_QUOTA.name,
            )
            observations += Entry(observation, head != null)
            failure?.let { throw it }
        }
    }

    private suspend fun assertNullHead() {
        val head = db.snapshotFactDao().head(
            NewApiSnapshotStore.accountQuotaScope(providerId, accountId).storageKey(),
            SnapshotKind.ACCOUNT_QUOTA.name,
        )
        assertTrue("invalid ref must write nothing", head == null)
    }

    private suspend fun seedProvider(patRef: String = this.patRef) {
        db.providerDao().insert(ProviderEntity(providerId, "Usage Observer Provider"))
        db.accountDao().insert(AccountEntity(accountId, providerId))
        db.credentialDao().insert(CredentialEntity(patRef, accountId))
        db.endpointDao().insert(
            EndpointEntity(endpointId, providerId, baseHttps, "$baseHttps/chat/completions"),
        )
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = providerId,
                workflowId = "wf-$providerId-usage-obs",
                authMode = AuthMode.BEARER,
                credentials = listOf(SafeCredentialRef(patRef, "PAT", CredentialRole.ACCOUNT)),
            ).toEntity(),
        )
    }

    private fun loopbackClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(RewriteToLoopback(server)).build()

    private fun originOf(base: String): String {
        val url = base.toHttpUrl()
        return "${url.scheme}://${url.host}:${url.port}"
    }

    private fun statusJson(): String =
        """{"success":true,"message":"","data":{"version":"v1.0.0-rc.25","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

    private fun accountJson(): String =
        """{"success":true,"message":"","data":{"quota":1000000,"used_quota":250000,"request_count":42,"group":"default","role":1,"status":1}}"""

    private fun enqueueQuotaSync() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusJson()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(accountJson()))
    }

    private fun drainRequests(expected: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(expected)
        repeat(expected) {
            out += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request #$it (expected $expected)" }
        }
        return out
    }

    private class CountingVault(secrets: Map<String, String>) : CredentialAccess {
        private val backing = secrets.toMap()
        val asked: MutableList<String> = mutableListOf()
        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            return backing[credentialId] ?: throw IllegalStateException("vault-refused")
        }
    }

    /** 測試傳輸接縫：只換 scheme/host/port 到 loopback 真 socket；path／Authorization 原樣。 */
    private class RewriteToLoopback(private val server: MockWebServer) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val path = orig.url.encodedPath
            return chain.proceed(orig.newBuilder().url(server.url(path).toString()).build())
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
