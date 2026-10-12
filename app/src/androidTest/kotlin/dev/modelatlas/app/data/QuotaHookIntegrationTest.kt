package dev.modelatlas.app.data

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * X-014 slice-4 W-int：`AtlasRepository.syncNewApi` → `EventPipeline.scanQuota`
 * 整合測試（F-06；DECISIONS D-2026-10-09-031 item 6）。
 *
 * Harness mirror [NewApiExactAccountSyncTest]：loopback MockWebServer＋真
 * [NewApiAdapter] HTTP 棧＋真 Room（`AtlasApplication.database`）＋真
 * [AtlasRepository]（僅注入 `quotaPipeline` 的自建實例，不用 singleton
 * `app.repository`，避免 production 接線未落地前的管線污染）。不需要
 * [NewApiScopedSyncFlowTest] 的 wave/VM 夾具——本檔直接打 repo `syncNewApi`
 * 明確帳號＋明確 key 入口（`accountId`＋`credentialId`），wave identity 與
 * scoped-VM 語意由 CR-002 既有測試覆蓋，此處不重 pin。
 *
 * 向量（每個測試只負責一件事）：
 * - **I1** 真轉換：首次 sync（account quota 500 → QUOTA_AVAILABLE；pre-read
 *   首見 lastGood==null → fail-closed，零寫入）；第二次 sync（account quota
 *   0 → QUOTA_EXHAUSTED）→ fake sink 恰好收到 ONE `writeQuotaEvent`，scopeKey
 *   為 `accountQuotaScope.storageKey()`、source `newapi/api/user/self`、
 *   fromState `QUOTA_AVAILABLE`、toState `QUOTA_EXHAUSTED`、evidenceId 為新
 *   fact 的 id（`result.accountQuota.latest.evidenceId`）。
 * - **I2** 401 夾層：I1 終態後一次 401（latest 降為 UNKNOWN、lastGood 不動），
 *   再一次 quota 不變的好回應 → 零新增寫入（共仍恰好一列）。
 * - **I3** token 腿：inference key 已配置；token available>0 → 0 → sink 在 token
 *   scopeKey 上恰好一列，source `newapi/api/usage/token`。
 * - **I4** null-pipeline no-op：同 I1 夾具但 `AtlasRepository(db)`（無
 *   quotaPipeline）→ 兩次 sync 皆成功無例外，quota rows 正常落庫（F-01 形狀：
 *   pre-reads 只在 hook 啟用時執行）。
 * - **I5** 無重複掃描：兩次 quota 完全相同的 sync → sink 零寫入。
 *
 * 誠實範圍說明（review 必讀）：
 * - 單帳號 reduction：ExactAccountSync 用雙帳號證明跨帳號不借用；本檔只測
 *   quota hook wiring，用單帳號＋明確 PAT/key（同表、同 role、同 ownership
 *   校验、同 4-call 傳輸形狀），不重做跨帳號隔離證明。
 * - I5 的「零 scanQuota 呼叫」在 sink 層可觀測為零 `writeQuotaEvent`：無轉換的
 *   scan 本來就不調 sink；零寫入是更強的可觀測不變量（有掃無寫與無掃在本層
 *   不可區分，斷言零寫入即涵蓋）。
 * - Fake sink 不設 F-03 的 `toState == "QUOTA_LOW"` 生產 sink gate：F-03 屬
 *   wiring slice 另一 owner 的 production 檔（`StoreEventSink`），本檔只斷言
 *   pipeline 層的 transition 發射；QUOTA_EXHAUSTED 邊在此正是要被看見。
 * - 無 Android 通知 framework 參與：delivery 端為 recording fake（恆回 true）。
 * - 全部 JSON／數值為 synthetic fixture，只走 loopback；DB 只存 safe ref。
 *
 * 隔離：每 case 唯一 UUID provider fixture；teardown 沿用 ExactAccountSync 的
 * FK-RESTRICT 精確清理（usage facts/heads → snapshot facts/heads → price/quota
 * → samples → my_models → configs → credentials → offerings → endpoints →
 * accounts → aliases → evidence → providers → orphan canonical），不做全域
 * teardown。零 sleep／零 retry。
 */
@RunWith(AndroidJUnit4::class)
class QuotaHookIntegrationTest {

    private lateinit var app: AtlasApplication
    private lateinit var server: MockWebServer

    private val providerIds = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "must not run on main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        deleteOwnRows()
        providerIds.clear()
    }

    // ===== I1：account AVAILABLE → EXHAUSTED 真轉換恰好一列 =====

    @Test
    fun i1_accountAvailableToExhaustedEmitsExactlyOneQuotaEvent() {
        val tag = shortId()
        val fixture = seedFixture("i1", tag)
        val clock = ScriptedClock(1_000L)
        server.dispatcher = MutableDispatcher(
            model = fixture.model,
            patSecret = fixture.patSecret,
            keySecret = fixture.keySecret,
            accountBody = accountJson(quota = 500L, used = 100L, requestCount = 42L),
            tokenBody = tokenJson(granted = 1_000L, used = 400L),
        )
        val sink = FakeQuotaSink()
        val repo = AtlasRepository(app.database, quotaPipeline = EventPipeline(sink, FakeDelivery()))
        val adapter = NewApiAdapter(
            CountingVault(fixture.secrets()),
            OkHttpClient.Builder().addInterceptor(Loopback(server)).build(),
            clock,
        )
        val accountScope = NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.account)

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                // ---- 首次 sync：500 → QUOTA_AVAILABLE；首見 pre-read → 零寫入 ----
                val beforeFirst = server.requestCount
                val first = repo.syncNewApi(
                    fixture.providerId, adapter,
                    accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertTrue("first sync must hit the network", server.requestCount > beforeFirst)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, first.accountQuota.latest?.state)
                assertEquals(500L, first.accountQuota.latest?.remainingRaw)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, first.accountQuota.lastGood?.state?.name)
                assertTrue("first-seen pre-read must be fail-closed (no scan)", sink.writes.isEmpty())

                // ---- 第二次 sync：quota 0 → QUOTA_EXHAUSTED → 恰好一列 ----
                clock.advanceTo(2_000L)
                (server.dispatcher as MutableDispatcher).accountBody =
                    accountJson(quota = 0L, used = 0L, requestCount = 42L)
                val beforeSecond = server.requestCount
                val second = repo.syncNewApi(
                    fixture.providerId, adapter,
                    accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertTrue("second sync must hit the network", server.requestCount > beforeSecond)
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, second.accountQuota.latest?.state)
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, second.accountQuota.lastGood?.state?.name)

                assertEquals("exactly one quota transition must be emitted", 1, sink.writes.size)
                val write = sink.writes.single()
                assertEquals(accountScope.storageKey(), write.scopeKey)
                assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, write.source)
                assertEquals("newapi/api/user/self", write.source)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, write.fromState)
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, write.toState)
                assertEquals(
                    "evidenceId must be the new fact's id",
                    second.accountQuota.latest?.evidenceId,
                    write.evidenceId,
                )
                assertEquals(second.accountQuota.latest?.observedAt, write.observedAt)
                assertNotNull(write.eventId)
            }
        }
    }

    // ===== I2：401 夾層＋不變好回應 → 零新增寫入 =====

    @Test
    fun i2_unauthorizedBetweenKnownStatesEmitsNoEvent() {
        val tag = shortId()
        val fixture = seedFixture("i2", tag)
        val clock = ScriptedClock(1_000L)
        val dispatcher = MutableDispatcher(
            model = fixture.model,
            patSecret = fixture.patSecret,
            keySecret = fixture.keySecret,
            accountBody = accountJson(quota = 500L, used = 100L, requestCount = 7L),
            tokenBody = tokenJson(granted = 1_000L, used = 400L),
        )
        server.dispatcher = dispatcher
        val sink = FakeQuotaSink()
        val repo = AtlasRepository(app.database, quotaPipeline = EventPipeline(sink, FakeDelivery()))
        val adapter = NewApiAdapter(
            CountingVault(fixture.secrets()),
            OkHttpClient.Builder().addInterceptor(Loopback(server)).build(),
            clock,
        )
        val accountScope = NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.account)

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                repo.syncNewApi(fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef)
                assertTrue(sink.writes.isEmpty())

                clock.advanceTo(2_000L)
                dispatcher.accountBody = accountJson(quota = 0L, used = 0L, requestCount = 7L)
                val exhausted = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, exhausted.accountQuota.latest?.state)
                assertEquals(1, sink.writes.size)

                // ---- 401：latest 降為 UNKNOWN，lastGood 原樣不動，零新增寫入 ----
                clock.advanceTo(3_000L)
                dispatcher.forceAccount401 = true
                val unauthorized = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(
                    "a 401 must downgrade latest to UNKNOWN, not forge a state",
                    QuotaState.UNKNOWN.name,
                    unauthorized.accountQuota.latest?.state,
                )
                assertEquals(
                    "last-good must survive the 401",
                    QuotaState.QUOTA_EXHAUSTED.name,
                    unauthorized.accountQuota.lastGood?.state?.name,
                )
                assertEquals("the 401 leg must not produce a transition", 1, sink.writes.size)

                // ---- 好回應但 quota 不變（仍為 0）：lastGood 無差 → 零新增寫入 ----
                clock.advanceTo(4_000L)
                dispatcher.forceAccount401 = false
                val unchanged = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, unchanged.accountQuota.latest?.state)
                assertEquals(1, sink.writes.size)
                val write = sink.writes.single()
                assertEquals(accountScope.storageKey(), write.scopeKey)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, write.fromState)
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, write.toState)
            }
        }
    }

    // ===== I3：token available>0 → 0 在 token scope 上恰好一列 =====

    @Test
    fun i3_tokenAvailableToExhaustedEmitsExactlyOneTokenEvent() {
        val tag = shortId()
        val fixture = seedFixture("i3", tag)
        val clock = ScriptedClock(1_000L)
        val dispatcher = MutableDispatcher(
            model = fixture.model,
            patSecret = fixture.patSecret,
            keySecret = fixture.keySecret,
            // account 腿全程固定 AVAILABLE：I3 只看 token 腿，不被 account 轉換污染。
            accountBody = accountJson(quota = 500L, used = 100L, requestCount = 9L),
            tokenBody = tokenJson(granted = 1_000L, used = 400L),
        )
        server.dispatcher = dispatcher
        val sink = FakeQuotaSink()
        val repo = AtlasRepository(app.database, quotaPipeline = EventPipeline(sink, FakeDelivery()))
        val adapter = NewApiAdapter(
            CountingVault(fixture.secrets()),
            OkHttpClient.Builder().addInterceptor(Loopback(server)).build(),
            clock,
        )
        val tokenScope = NewApiSnapshotStore.tokenQuotaScope(
            fixture.providerId, fixture.account, InferenceKeyRef(fixture.keyRef),
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val first = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, first.tokenQuota?.latest?.state)
                assertTrue("first-seen token pre-read must be fail-closed (no scan)", sink.writes.isEmpty())

                clock.advanceTo(2_000L)
                dispatcher.tokenBody = tokenJson(granted = 1_000L, used = 1_000L)
                val second = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, second.tokenQuota?.latest?.state)

                assertEquals("exactly one token transition must be emitted", 1, sink.writes.size)
                val write = sink.writes.single()
                assertEquals(tokenScope.storageKey(), write.scopeKey)
                assertEquals(NewApiSnapshotStore.TOKEN_SOURCE, write.source)
                assertEquals("newapi/api/usage/token", write.source)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, write.fromState)
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, write.toState)
                assertEquals(
                    "evidenceId must be the new token fact's id",
                    second.tokenQuota?.latest?.evidenceId,
                    write.evidenceId,
                )
                assertEquals(second.tokenQuota?.latest?.observedAt, write.observedAt)
            }
        }
    }

    // ===== I4：null-pipeline no-op——sync 成功、rows 正常落庫 =====

    @Test
    fun i4_nullPipelineSyncSucceedsAndPersistsQuotaRows() {
        val tag = shortId()
        val fixture = seedFixture("i4", tag)
        val clock = ScriptedClock(1_000L)
        val dispatcher = MutableDispatcher(
            model = fixture.model,
            patSecret = fixture.patSecret,
            keySecret = fixture.keySecret,
            accountBody = accountJson(quota = 500L, used = 100L, requestCount = 11L),
            tokenBody = tokenJson(granted = 1_000L, used = 400L),
        )
        server.dispatcher = dispatcher
        // 刻意不傳 quotaPipeline：F-01 形狀——pre-reads 跳過、sync 照常成功。
        val repo = AtlasRepository(app.database)
        val adapter = NewApiAdapter(
            CountingVault(fixture.secrets()),
            OkHttpClient.Builder().addInterceptor(Loopback(server)).build(),
            clock,
        )
        val accountScope = NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.account)
        val tokenScope = NewApiSnapshotStore.tokenQuotaScope(
            fixture.providerId, fixture.account, InferenceKeyRef(fixture.keyRef),
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val first = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, first.accountQuota.latest?.state)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, first.tokenQuota?.latest?.state)

                clock.advanceTo(2_000L)
                dispatcher.accountBody = accountJson(quota = 0L, used = 0L, requestCount = 11L)
                // 無管線時 hook 為 no-op：不得拋例外（此行若拋，測試即紅）。
                val second = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, second.accountQuota.latest?.state)
                assertEquals(QuotaState.QUOTA_EXHAUSTED.name, second.accountQuota.lastGood?.state?.name)

                // quota rows 照常落庫：typed reader 與真 DAO head 皆可見。
                assertEquals(
                    QuotaState.QUOTA_EXHAUSTED.name,
                    repo.newApiSnapshots.quota(accountScope, SnapshotKind.ACCOUNT_QUOTA).latest?.state,
                )
                assertEquals(
                    QuotaState.QUOTA_AVAILABLE.name,
                    repo.newApiSnapshots.quota(tokenScope, SnapshotKind.TOKEN_QUOTA).latest?.state,
                )
                assertNotNull(
                    "account quota head must persist without a pipeline",
                    app.database.snapshotFactDao().head(accountScope.storageKey(), SnapshotKind.ACCOUNT_QUOTA.name),
                )
                assertNotNull(
                    "token quota head must persist without a pipeline",
                    app.database.snapshotFactDao().head(tokenScope.storageKey(), SnapshotKind.TOKEN_QUOTA.name),
                )
            }
        }
    }

    // ===== I5：quota 不變的連續 sync → 零寫入 =====

    @Test
    fun i5_identicalQuotaResponsesEmitNoEvent() {
        val tag = shortId()
        val fixture = seedFixture("i5", tag)
        val clock = ScriptedClock(1_000L)
        server.dispatcher = MutableDispatcher(
            model = fixture.model,
            patSecret = fixture.patSecret,
            keySecret = fixture.keySecret,
            accountBody = accountJson(quota = 500L, used = 100L, requestCount = 13L),
            tokenBody = tokenJson(granted = 1_000L, used = 400L),
        )
        val sink = FakeQuotaSink()
        val repo = AtlasRepository(app.database, quotaPipeline = EventPipeline(sink, FakeDelivery()))
        val adapter = NewApiAdapter(
            CountingVault(fixture.secrets()),
            OkHttpClient.Builder().addInterceptor(Loopback(server)).build(),
            clock,
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val first = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, first.accountQuota.latest?.state)
                assertTrue("first-seen pre-read must be fail-closed (no scan)", sink.writes.isEmpty())

                // 回應完全相同（dispatcher bodies 不動），只有時鐘前進。
                clock.advanceTo(2_000L)
                val second = repo.syncNewApi(
                    fixture.providerId, adapter, accountId = fixture.account, credentialId = fixture.keyRef,
                )
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, second.accountQuota.latest?.state)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, second.tokenQuota?.latest?.state)
                assertTrue("an unchanged quota must not scan into an event", sink.writes.isEmpty())
            }
        }
    }

    // ===== harness（fixture 僅本檔，不當產品資料；FK-RESTRICT 精確清理，不全域 teardown） =====

    private class SingleAccountFixture(
        val providerId: String,
        val account: String,
        val patRef: String,
        val keyRef: String,
        val patSecret: String,
        val keySecret: String,
        val endpointId: String,
        val model: String,
    ) {
        /** PAT＋inference key 的 synthetic fake secrets，只服務 loopback。 */
        fun secrets(): Map<String, String> = mapOf(
            patRef to patSecret,
            keyRef to keySecret,
        )
    }

    private fun seedFixture(name: String, tag: String): SingleAccountFixture = runBlocking {
        val providerId = "quota-hook-$name-$tag"
        val fixture = SingleAccountFixture(
            providerId = providerId,
            account = "a-$name-$tag",
            patRef = "pat-$name-$tag",
            keyRef = "key-$name-$tag",
            patSecret = "quota-hook-pat-secret-$name-$tag-0123456789",
            keySecret = "quota-hook-key-secret-$name-$tag-9876543210",
            endpointId = "e-$name-$tag",
            model = "mdl-$name-$tag",
        )
        providerIds += providerId
        val db = app.database
        val baseHost = "https://$providerId.example.com"
        db.providerDao().insert(ProviderEntity(providerId = providerId, name = "P $providerId"))
        db.accountDao().insert(AccountEntity(fixture.account, providerId))
        db.credentialDao().insert(CredentialEntity(fixture.patRef, fixture.account))
        db.credentialDao().insert(CredentialEntity(fixture.keyRef, fixture.account))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = fixture.endpointId,
                providerId = providerId,
                baseUrl = "$baseHost/v1",
                endpointUrl = "$baseHost/v1/chat/completions",
                protocol = "CHAT_COMPLETIONS",
            ),
        )
        db.offeringDao().insert(
            OfferingEntity(
                id = "off:$providerId:${fixture.model}",
                providerId = providerId,
                endpointId = fixture.endpointId,
                providerModelId = fixture.model,
                manual = false,
                source = "OFFICIAL",
                hidden = false,
            ),
        )
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = providerId,
                workflowId = "wf-$providerId-quota-hook",
                authMode = AuthMode.BEARER,
                credentials = listOf(
                    SafeCredentialRef(fixture.patRef, "PAT", CredentialRole.ACCOUNT),
                    SafeCredentialRef(fixture.keyRef, "KEY", CredentialRole.INFERENCE),
                ),
            ).toEntity(),
        )
        fixture
    }

    /** 只記錄 `writeQuotaEvent` 的 fake sink（price／provider 腿為 no-op；不碰通知 framework）。 */
    private class FakeQuotaSink : EventSink {
        data class QuotaWrite(
            val eventId: String,
            val scopeKey: String,
            val fromState: String?,
            val toState: String?,
            val observedAt: Long,
            val source: String,
            val evidenceId: String?,
        )

        val writes: MutableList<QuotaWrite> = Collections.synchronizedList(mutableListOf())

        override suspend fun writePriceEvent(
            eventId: String,
            scopeKey: String,
            fromInput: String?,
            fromOutput: String?,
            toInput: String?,
            toOutput: String?,
            observedAt: Long,
            source: String,
            evidenceId: String?,
            fromFixed: String?,
            toFixed: String?,
        ) = Unit

        override suspend fun writeProviderEvent(
            eventId: String,
            scopeKey: String,
            kind: String,
            observedAt: Long,
            source: String,
            evidenceId: String?,
            detail: String?,
        ) = Unit

        override suspend fun writeQuotaEvent(
            eventId: String,
            scopeKey: String,
            fromState: String?,
            toState: String?,
            observedAt: Long,
            source: String,
            evidenceId: String?,
        ) {
            writes += QuotaWrite(eventId, scopeKey, fromState, toState, observedAt, source, evidenceId)
        }
    }

    /** 只記錄投遞呼叫的 fake delivery（恆回 true＝已處理；不碰 Android 通知 framework）。 */
    private class FakeDelivery : DeliverySink {
        val delivered = Collections.synchronizedList(mutableListOf<String>())

        override fun deliver(eventId: String, scopeKey: String, source: String, observedAt: Long): Boolean {
            delivered += eventId
            return true
        }

        override fun markUndelivered(eventId: String) = Unit
    }

    /**
     * 站方 dispatcher：status／pricing 恆 200（與 ExactAccountSync 同形）；
     * account／token 腿按本次 request 實際帶上的 `Authorization` 路由——PAT 對上
     * 才回當前景 `accountBody`，key 對上才回當前景 `tokenBody`，否則 401；測試在
     * 兩次 sync 之間改寫 bodies／`forceAccount401` 即切換下一輪站方回應。
     */
    private class MutableDispatcher(
        private val model: String,
        private val patSecret: String,
        private val keySecret: String,
        @Volatile var accountBody: String,
        @Volatile var tokenBody: String,
        @Volatile var forceAccount401: Boolean = false,
    ) : Dispatcher() {
        val status = AtomicInteger(0)
        val pricing = AtomicInteger(0)
        val account = AtomicInteger(0)
        val token = AtomicInteger(0)
        val unexpected = AtomicInteger(0)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?')
            return when (path) {
                "/api/status" -> ok(statusJson()).also { status.incrementAndGet() }
                "/api/pricing" -> ok(pricingJson(model)).also { pricing.incrementAndGet() }
                "/api/user/self" -> {
                    account.incrementAndGet()
                    if (forceAccount401) {
                        unauthorized()
                    } else if (request.getHeader("Authorization") == "Bearer $patSecret") {
                        ok(accountBody)
                    } else {
                        unauthorized()
                    }
                }
                "/api/usage/token/" -> {
                    token.incrementAndGet()
                    if (request.getHeader("Authorization") == "Bearer $keySecret") {
                        ok(tokenBody)
                    } else {
                        unauthorized()
                    }
                }
                else -> unauthorized().also { unexpected.incrementAndGet() }
            }
        }

        private fun ok(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)

        private fun unauthorized(): MockResponse =
            MockResponse().setResponseCode(401).setBody("""{"success":false,"message":"$AUTH_CANARY"}""")
    }

    /**
     * 測試傳輸接縫：只換 scheme/host/port 到 loopback 真 socket（path／
     * Authorization 原樣保留並被 MockWebServer 真實記錄）。產品路徑永不開啟。
     */
    private class Loopback(private val server: MockWebServer) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val url = orig.url
            val loopback = server.url("/")
            val rewritten = orig.newBuilder().url(
                url.newBuilder()
                    .scheme(loopback.scheme)
                    .host(loopback.host)
                    .port(loopback.port)
                    .build(),
            ).build()
            return chain.proceed(rewritten)
        }
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
     * FakeClock：同一次 sync 內 constant、多次 sync 之間只允許前進。
     * 讓 repeated sync 的 evidence observedAt 精確可斷言（append-only／last-good
     * 時序是精確值，不是時序巧合）；倒退直接拋出。
     */
    private class ScriptedClock(private val start: Long) : Clock {
        private val value = AtomicLong(start)
        fun advanceTo(millis: Long) {
            require(millis >= value.get()) { "scripted clock must move forward" }
            value.set(millis)
        }

        override fun epochMillis(): Long = value.get()

        override fun monotonicMillis(): Long = value.get() - start
    }

    /**
     * 本檔 fixture 精確清理（FK RESTRICT 順序；不做全域 teardown）。順序：
     * usage facts/heads（shared helper，精確 provider 維＋由事實反查真 scopeKey，
     * 絕不 LIKE）→ snapshot facts/heads（真 parseScopeKey 過濾 provider 維）→
     * price/quota snapshots → samples → my_models → configs → credentials →
     * offerings → endpoints → accounts → aliases → evidence → providers →
     * orphan canonical。失敗即拋出（不吞例外）；fixture 全 UUID 唯一 scope。
     */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val db = app.database
        val placeholders = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val writable = db.openHelper.writableDatabase
        val canonicalIds: List<String> = writable.query(
            "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN ($placeholders)",
            args,
        ).use { c ->
            val ids = LinkedHashSet<String>()
            while (c.moveToNext()) ids += c.getString(0)
            ids.toList()
        }
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
            deleteOwnProviderAccountUsageRows(writable, providerIds)
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_facts WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
            for ((scopeKey, kind) in ownHeads) {
                writable.execSQL("DELETE FROM snapshot_heads WHERE scopeKey = ? AND kind = ?", arrayOf<Any?>(scopeKey, kind))
            }
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

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val RC25_VERSION = NewApiVersion.RC25
        const val AUTH_CANARY = "quota-hook-auth-canary-must-never-escape"

        fun statusJson(): String =
            """{"success":true,"message":"","data":{"version":"$RC25_VERSION","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

        fun pricingJson(model: String): String =
            """{"success":true,"message":"","data":[{"model_name":"$model","quota_type":0,"model_ratio":0,"completion_ratio":1,"enable_groups":["all"],"supported_endpoint_types":["openai"]}],"group_ratio":{"default":1},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"quota-hook-v1"}"""

        /** 站方帳號回應：quota/used/request_count 原值由 caller 指定（synthetic fixture）。 */
        fun accountJson(quota: Long, used: Long, requestCount: Long): String =
            """{"success":true,"message":"","data":{"quota":$quota,"used_quota":$used,"request_count":$requestCount,"group":"default","role":1,"status":1}}"""

        fun tokenJson(granted: Long, used: Long): String =
            """{"code":true,"message":"ok","data":{"object":"token_usage","name":"quota-hook-key","total_granted":$granted,"total_used":$used,"total_available":${granted - used},"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""

        fun shortId(): String = UUID.randomUUID().toString().take(8)
    }
}
