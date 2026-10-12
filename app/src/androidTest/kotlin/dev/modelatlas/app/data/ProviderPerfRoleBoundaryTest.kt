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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NINT-004 credential-role boundary tests（device androidTest；本檔唯一新增）。
 *
 * 契約來源：Contract §6（rc.25 `/api/perf-metrics` 用 dashboard/account 認證，永不用
 * inference key）／§23.3（加入 My Models 的 enrichment 必須接得上）；PRODUCT_SPEC §1
 * （raw secret 不出邊界、角色不互相冒充）／§12（PAT ＝ Account/Management credential、
 * API key ＝ Inference credential）／§18（完成條件）。
 *
 * 覆蓋「角色邊界」上三個既有 store test 未覆蓋的失敗模式（外國 PAT case 已在
 * `ProviderPerfStoreTest.foreignAccountPatIsRejectedBeforeNetworkOrVaultResolution`
 * 覆蓋，本檔不重複）：
 * 1. 同一 safeCredentialId 同時掛 ACCOUNT＋INFERENCE 兩種角色 → fail-closed。
 * 2. 明確停用（enabled=false）的 ACCOUNT ref → 不得靜默降級匿名、也不得借 INFERENCE。
 * 3. >256 字元的危險 ref → 在上線前（wire）就拒絕。
 *
 * 傳輸說明（review 必讀）：
 * - 每個 case 都是真 in-memory Room（v12 entities＋real DAOs）＋真
 *   [ProviderPerfStore.syncPerf]＋真 [NewApiAdapter] HTTP 棧 → MockWebServer loopback
 *   真 socket；DB endpoint 維持 `https://…example.com/v1` 讓生產 [EndpointPolicy] 的
 *   HTTPS gate 照常生效，測試只用 OkHttp [Interceptor] 做 loopback 路由改寫。
 * - **先做 positive 前置**：每個 case 都先種「已持久化的官方 certified triple」
 *   （source＋rc.25＋OK＋HTTP 200＋kind/scope/evidence 全匹配）並跑一次匿名 sync，
 *   斷言 `certified && fetched` 與 3 個真 HTTP 請求，證明 version gate 已放行、這條
 *   路徑真的會打到 wire；之後才安裝違規 credential config 驗角色邊界。
 * - 違規階段先 enqueue 3 個**無害 typed 回應**（真 rc.25 形狀）：若閘門失效而誤打網路，
 *   測試會乾淨地失敗而不是 hang；同時斷言 HTTP 數不變、vault 零取用。
 * - Vault 只放 loopback fake secret（`loopback-fake-…`）並綁定到本檔 safeCredentialRef，
 *   這樣「誤用 INFERENCE/停用 credential」會真的產生 wire 請求而被 HTTP 斷言抓到，
 *   不是靠 vault 丟例外。raw `sk-` 值不進本檔、不進 repo／log／prompt。
 * - Fixture JSON 僅存本檔（rc.25 PERF-SHAPE-B §10 形狀衍生），只作傳輸輸入，不冒充
 *   產品資料；斷言全部落在真 DB／真 evidence。
 */
@RunWith(AndroidJUnit4::class)
class ProviderPerfRoleBoundaryTest {

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

    // ===== Case 1：同一 ref 同時是 ACCOUNT＋INFERENCE → 角色不純即 fail-closed =====

    @Test
    fun sameRefDeclaredAsAccountAndInferenceFailsClosedBeforeHttpAndVault() = runBlocking {
        val db = memoryDb()
        val provider = "perf-role-dual"
        val account = "a-role-dual"
        val endpoint = "e-role-dual"
        val dualRef = "pat-role-dual"
        seedProviderShell(db, provider, account, endpoint)
        seedCertifiedPrice(db, provider, account, endpoint)
        val store = ProviderPerfStore(db)
        val vault = CountingVault(mapOf(dualRef to "loopback-fake-secret-role-dual-0123456789"))

        // Positive 前置：stored 官方 certified triple 真的放行，匿名路徑真的打到 wire。
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        val positive = store.syncPerf(adapter(vault, 5_000L), provider, MODEL)
        val positiveRequests = drain(3)
        assertTrue("stored certified triple 必須被接受", positive.certified)
        assertTrue("anonymous perf sync 必須真的取到資料", positive.fetched)
        assertFalse(positive.failed)
        assertEquals("/api/status", positiveRequests[0].path)
        assertEquals("anonymous 不該動 vault", 0, vault.asked.size)
        val goodSummary = store.observation(provider, MODEL, null, 168, ProviderPerfKind.SUMMARY).lastGood
        assertNotNull("positive 前置必須留下 good 列", goodSummary)
        val factsAfterPositive = count(db, "provider_perf_facts")
        val httpAfterPositive = server.requestCount

        // 違規 config：同一 safeCredentialId 既是 ACCOUNT 又是 INFERENCE（角色不純）。
        seedCredentialRow(db, dualRef, account)
        seedConfig(db, provider, listOf(
            SafeCredentialRef(dualRef, "PAT", CredentialRole.ACCOUNT),
            SafeCredentialRef(dualRef, "KEY", CredentialRole.INFERENCE),
        ))
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())

        try {
            store.syncPerf(adapter(vault, 6_000L), provider, MODEL)
            throw AssertionError("dual-role credential ref must fail closed")
        } catch (e: IllegalArgumentException) {
            assertEquals("Credential role unavailable", e.message)
            assertFalse("靜態訊息不得夾帶 ref", (e.message ?: "").contains(dualRef))
        }
        assertEquals("角色邊界失效就等於打線上：違規同步 HTTP 增量必須為 0", 0, server.requestCount - httpAfterPositive)
        assertEquals("違規前不得取用任何 secret", 0, vault.asked.size)
        assertEquals("fail-closed 不得寫入任何 perf fact", factsAfterPositive, count(db, "provider_perf_facts"))
        assertEquals("last-good 不得被違規同步取代",
            goodSummary!!.evidenceId,
            store.observation(provider, MODEL, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId)
    }

    // ===== Case 2：明確停用的 ACCOUNT ref → 不得靜默降級匿名、不得借 INFERENCE =====

    @Test
    fun disabledAccountRefNeverFallsBackToAnonymousOrInference() = runBlocking {
        val db = memoryDb()
        val provider = "perf-role-disabled"
        val account = "a-role-disabled"
        val endpoint = "e-role-disabled"
        val disabledPat = "pat-role-disabled"
        val inferenceKey = "key-role-enabled"
        seedProviderShell(db, provider, account, endpoint)
        seedCertifiedPrice(db, provider, account, endpoint)
        val store = ProviderPerfStore(db)
        val vault = CountingVault(mapOf(
            disabledPat to "loopback-fake-secret-role-disabled-0123456789",
            inferenceKey to "loopback-fake-secret-role-inference-0123456789",
        ))

        // Positive 前置：同一 DB 先以匿名路徑證明 stored certified triple 放行且會上線。
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        val positive = store.syncPerf(adapter(vault, 5_000L), provider, MODEL)
        drain(3)
        assertTrue(positive.certified)
        assertTrue(positive.fetched)
        assertEquals("anonymous 不該動 vault", 0, vault.asked.size)
        val goodGroup = store.observation(provider, MODEL, "default", 168, ProviderPerfKind.GROUP).lastGood
        assertNotNull("positive 前置必須留下 good 列", goodGroup)
        val factsAfterPositive = count(db, "provider_perf_facts")
        val httpAfterPositive = server.requestCount

        // 違規 config：ACCOUNT ref 的 DB row 被明確停用，另有一把可用 INFERENCE key。
        seedCredentialRow(db, disabledPat, account)
        seedCredentialRow(db, inferenceKey, account)
        assertEquals("停用必須真的生效", 1, db.credentialDao().setEnabled(disabledPat, false))
        assertFalse(db.credentialDao().getById(disabledPat)!!.enabled)
        assertTrue("旁邊的 INFERENCE key 是可用的，確保不是因為無 credential 而通過",
            db.credentialDao().getById(inferenceKey)!!.enabled)
        seedConfig(db, provider, listOf(
            SafeCredentialRef(disabledPat, "PAT", CredentialRole.ACCOUNT),
            SafeCredentialRef(inferenceKey, "KEY", CredentialRole.INFERENCE),
        ))
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())

        try {
            store.syncPerf(adapter(vault, 6_000L), provider, MODEL)
            throw AssertionError("disabled ACCOUNT ref must fail closed")
        } catch (e: IllegalArgumentException) {
            assertEquals("Credential ownership mismatch", e.message)
            assertFalse("靜態訊息不得夾帶 ref", (e.message ?: "").contains(disabledPat))
        }
        assertEquals("不得靜默降級為匿名：違規同步 HTTP 增量必須為 0", 0, server.requestCount - httpAfterPositive)
        assertEquals("不得借 INFERENCE credential", 0, vault.asked.size)
        assertEquals("fail-closed 不得寫入任何 perf fact", factsAfterPositive, count(db, "provider_perf_facts"))
        assertEquals("last-good 不得被違規同步取代",
            goodGroup!!.evidenceId,
            store.observation(provider, MODEL, "default", 168, ProviderPerfKind.GROUP).lastGood!!.evidenceId)
    }

    // ===== Case 3：>256 字元的危險 ref → 在 wire 之前就拒絕 =====

    @Test
    fun overlongCredentialRefIsRejectedBeforeWireAndVault() = runBlocking {
        val db = memoryDb()
        val provider = "perf-role-overlong"
        val account = "a-role-overlong"
        val endpoint = "e-role-overlong"
        val overlongRef = OVERLONG_REF
        assertEquals(257, overlongRef.length)
        assertTrue("被拒的必須是長度閘門：ref 不得含空白／控制字元、不得為 sk- 前綴",
            overlongRef.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F } && !overlongRef.startsWith("sk-"))
        seedProviderShell(db, provider, account, endpoint)
        seedCertifiedPrice(db, provider, account, endpoint)
        val store = ProviderPerfStore(db)
        val vault = CountingVault(mapOf(overlongRef to "loopback-fake-secret-role-overlong-0123456789"))

        // Positive 前置：stored 官方 certified triple 放行，匿名路徑真的打到 wire。
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())
        val positive = store.syncPerf(adapter(vault, 5_000L), provider, MODEL)
        drain(3)
        assertTrue(positive.certified)
        assertTrue(positive.fetched)
        assertEquals("anonymous 不該動 vault", 0, vault.asked.size)
        val goodSummary = store.observation(provider, MODEL, null, 168, ProviderPerfKind.SUMMARY).lastGood
        assertNotNull("positive 前置必須留下 good 列", goodSummary)
        val factsAfterPositive = count(db, "provider_perf_facts")
        val httpAfterPositive = server.requestCount

        // 違規 config：ACCOUNT ref 長度超標（無空白／無控制字元／非 sk- 前綴，
        // 確保被拒的是長度閘門本身，不是被其他規則擋掉）。
        seedCredentialRow(db, overlongRef, account)
        seedConfig(db, provider, listOf(SafeCredentialRef(overlongRef, "PAT", CredentialRole.ACCOUNT)))
        enqueue(statusJson())
        enqueue(summaryJson())
        enqueue(queryJson())

        try {
            store.syncPerf(adapter(vault, 6_000L), provider, MODEL)
            throw AssertionError("overlong credential ref must be rejected")
        } catch (e: IllegalArgumentException) {
            assertEquals("Credential ref too long", e.message)
            assertFalse("靜態訊息不得夾帶 ref", (e.message ?: "").contains(overlongRef))
        }
        assertEquals("危險 ref 不得上線：違規同步 HTTP 增量必須為 0", 0, server.requestCount - httpAfterPositive)
        assertEquals("危險 ref 不得進 vault", 0, vault.asked.size)
        assertEquals("fail-closed 不得寫入任何 perf fact", factsAfterPositive, count(db, "provider_perf_facts"))
        assertEquals("last-good 不得被違規同步取代",
            goodSummary!!.evidenceId,
            store.observation(provider, MODEL, null, 168, ProviderPerfKind.SUMMARY).lastGood!!.evidenceId)
    }

    // ===== harness（fixture 僅本檔，不當產品資料；secret 全 fake 只走 loopback） =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries().build().also { open += it }

    private fun adapter(vault: CredentialAccess, now: Long): NewApiAdapter =
        NewApiAdapter(vault, rewriteClient(), FakeClock(now))

    /** Provider / Account / Endpoint（不帶任何 credential config；`https` base 過生產 gate）。 */
    private suspend fun seedProviderShell(db: AtlasDatabase, provider: String, account: String, endpoint: String) {
        db.providerDao().insert(ProviderEntity(provider, "P $provider"))
        db.accountDao().insert(AccountEntity(account, provider))
        db.endpointDao().insert(
            EndpointEntity(endpoint, provider, "https://$provider.example.com/v1", "https://$provider.example.com/v1/chat/completions"),
        )
    }

    /** 只建 metadata row（vault 內沒有 raw 值；`enabled` 預設 true，停用另外用 `setEnabled`）。 */
    private suspend fun seedCredentialRow(db: AtlasDatabase, credentialId: String, account: String) {
        db.credentialDao().insert(CredentialEntity(credentialId, account))
    }

    /** 走真 `ProviderManualConfig.toEntity()`（含既有 secret guard）落 provider config。 */
    private suspend fun seedConfig(db: AtlasDatabase, provider: String, refs: List<SafeCredentialRef>) {
        db.providerConfigDao().insert(
            ProviderManualConfig(
                provider,
                "wf-$provider-role",
                AuthMode.BEARER,
                credentials = refs,
            ).toEntity(),
        )
    }

    /**
     * Stored cert 前置：官方 PRICE last-good triple（kind／scopeKey／head 一致、`OK`＋
     * HTTP 200＋官方 source＋evidence providerId／timestamp 一致＋rc.25）。測試夾具明示，
     * 不是 deployment verified 宣告。
     */
    private suspend fun seedCertifiedPrice(
        db: AtlasDatabase,
        provider: String,
        account: String,
        endpoint: String,
        time: Long = 1_000L,
    ) {
        val scope = PricingScope(provider, account, null, "cert-model", "default", endpoint, "CHAT_COMPLETIONS")
        val key = scope.storageKey()
        db.evidenceDao().insert(
            EvidenceEntity("cert-ev-role-1", "cert-ev-role-1", provider, "cert-model", "OK", 200, time, NewApiSnapshotStore.PRICE_SOURCE),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = "cert-ev-role-1",
                scopeKey = key,
                kind = SnapshotKind.PRICE.name,
                observedAt = time,
                snapshotId = "cert-ev-role-1",
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
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity(key, SnapshotKind.PRICE.name, "cert-ev-role-1", time, "cert-ev-role-1", time))
    }

    private fun rewriteClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(RewriteToMock(server)).build()

    private fun enqueue(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    private fun statusJson(): String =
        """{"success":true,"data":{"version":"v1.0.0-rc.25","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

    private fun summaryJson(): String =
        """{"success":true,"data":{"models":[{"model_name":"$MODEL","avg_latency_ms":120,"success_rate":98.37,"avg_tps":12.5,"recent_success_rates":[97.5,98.0,98.37]}]}}"""

    private fun queryJson(): String =
        """{"success":true,"data":{"model_name":"$MODEL","series_schema":"dbcd0a3c01b55203","groups":[{"group":"default","avg_ttft_ms":10,"avg_latency_ms":100,"success_rate":99.0,"avg_tps":20.0,"series":[{"ts":1700000000,"avg_ttft_ms":10,"avg_latency_ms":100,"success_rate":99.0,"avg_tps":20.0}]}]}}"""

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

    private class FakeClock(val now: Long) : Clock {
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
     * 測試傳輸接縫：把已通過生產驗證的 `https` request 改寫為 loopback MockWebServer 真
     * socket（scheme／host／port 替換；path＋query＋Authorization 原樣保留）。DB 不存
     * loopback、不存 secret。
     */
    private class RewriteToMock(private val server: MockWebServer) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val target = orig.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            return chain.proceed(orig.newBuilder().url(target).build())
        }
    }

    private companion object {
        const val MODEL = "m-role-boundary"

        /** 257 字元（>256 safe-ref 上限）；無空白／控制字元、非 `sk-` 前綴。 */
        val OVERLONG_REF: String = "pat-" + "q".repeat(253)
    }
}