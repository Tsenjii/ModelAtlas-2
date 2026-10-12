package dev.modelatlas.app.data

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Clock
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiDomainMapper
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-001 A1／A5 exact account/key 的 **repository＋真 HTTP** 兩帳號 regression
 * （device androidTest；本檔唯一新增；selector 級斷言在 [NewApiCredentialSelectorTest]、
 * cross-provider ordering 在 `NewApiCredentialCrossProviderTest`、perf provenance 在
 * `ProviderPerfAccountSelectionTest`——均為其他 owner，本檔不重做、不擴 policy）。
 *
 * 真鏈路層級（不誇大）：
 * - 真 Room（[AtlasDatabase]）→ 真 [AtlasRepository.syncNewApi]／
 *   [AtlasRepository.enrichMyModelBundle] → 真 [NewApiAdapter] HTTP 棧 →
 *   loopback 真 socket（MockWebServer）→ 真 `snapshot_heads`／`snapshot_facts`／
 *   `provider_account_usage_*`／`quota_snapshots`／`evidence` 落庫；讀回一律用
 *   真 typed reader（[StoredQuota.latest]、[StoredProviderAccountUsage.latest]、
 *   `usage.observation`／`usage.history`）與真 DAO head 查詢。
 *
 * 傳輸說明（非掩飾，review 必讀）：
 * - DB 的 endpoint baseUrl 維持 `https://…example.com/v1`（生產 `EndpointPolicy`
 *   HTTPS gate 要求；`http` 在 `syncNewApi` 前置即 fail-fast，不燒額度）。
 * - 測試端 [Loopback] interceptor 只做**傳輸改寫**：已通過生產驗證的 `https`
 *   request 只換 scheme/host/port 為 loopback 真 socket，path／Authorization
 *   原樣保留並被 MockWebServer 真實記錄。DB 不存 loopback、不存 secret；改寫
 *   只存在測試傳輸層。
 * - **[TwoAccountDispatcher] 的站方回應由本次 request 實際帶上的
 *   `Authorization` 決定**：PAT A 拿到 A 的帳號 quota/count、PAT B 拿到 B 的；
 *   inference key A 拿到 A key 的 token、key B 拿到 B 的。因此若 production
 *   流程借用别帳號 credential（A1 舊 bug 即如此），回傳的 quota/requestCount
 *   必然錯，此斷言會失敗——不是只核對 store 到的 key。
 *
 * Fixture 資料聲明：本檔 JSON／數值皆為 rc.25 source-derived **synthetic**
 * fixture，僅供 loopback 傳輸，不是 live 產品資料；secret 為合成 fake 值、DB
 * 只存 safe ref。A／B 數值刻意互異，任何串欄或借用都會失敗。
 *
 * HTTP 契約（dual-mode：hit=4／miss=6／uncertified=3-status／failure=3-status）：
 * 每次 certified 成功 provider-wide sync 自命中 own-passport 為 4 個請求——
 * `status／pricing／user/self／token`，`/api/user/self`
 * 每 sync 恰一次（observer 不另發請求），無 inference 請求；
 * 6-shape 僅見於 passport-miss（由 V-6 B-leg pin，本檔不重 pin）。
 * [ScriptedClock]（FakeClock）只在兩次 sync 之間前進，同一次 sync 內 observedAt
 * 為常數；append-only／last-good 時序斷言因此是精確值，非時序巧合。
 *
 * 隔離：每 case 唯一 UUID provider fixture；teardown 依 FK RESTRICT 順序精確
 * 清理自己的 rows（usage facts/heads → snapshot facts/heads → price/quota →
 * samples → my_models → configs → credentials → offerings → endpoints →
 * accounts → aliases → evidence → providers → orphan canonical），**不做
 * 全域 teardown**（不清別人的資料）。零 sleep／零 retry／零 expectation 放寬。
 */
@RunWith(AndroidJUnit4::class)
class NewApiExactAccountSyncTest {

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

    // ===== 1. 顯式 A→B：每帳號自己的 PAT/key、回傳值可區分、persisted 七維 head key-owned =====

    @Test
    fun syncAThenSyncBUsesEachAccountsOwnPatKeyAndReturnsDistinguishingQuotaAndCount() {
        val tag = shortId()
        val fixture = seedFixture("ab", tag)
        val clock = ScriptedClock(PHASE_A_MS)
        server.dispatcher = TwoAccountDispatcher(
            fixture.model,
            accountBodies = mapOf(
                fixture.patSecretA to accountJson(QUOTA_RAW_A, USED_A, COUNT_A),
                fixture.patSecretB to accountJson(QUOTA_RAW_B, USED_B, COUNT_B),
            ),
            tokenBodies = mapOf(
                fixture.keySecretA to tokenJson(TOKEN_GRANTED_A, TOKEN_USED_A),
                fixture.keySecretB to tokenJson(TOKEN_GRANTED_B, TOKEN_USED_B),
            ),
        )
        val vault = CountingVault(fixture.secrets())
        val usage = app.repository.providerAccountUsage

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val adapter = NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(Loopback(server)).build(), clock)

                // ---- Sync A（explicit account A＋key A）----
                val beforeA = server.requestCount
                val beforeLegsA = legs()
                val resultA = app.repository.syncNewApi(
                    fixture.providerId, adapter,
                    accountId = fixture.accountA, credentialId = fixture.keyRefA,
                )
                val recordedA = drainRequests(4)
                assertSixCallSequence(recordedA)
                assertEquals("latest A sync must still be the 4-call contract (own-passport self-hit)", listOf(1, 1, 1, 1, 0), legDelta(beforeLegsA, legs()))
                assertEquals(beforeA + 4, server.requestCount)

                // A 的 sync 必須用 A 的 PAT（pricing／account）與 A 的 inference key（token）。
                assertEquals("Bearer ${fixture.patSecretA}", recordedA.single { it.routePath() == "/api/pricing" }.getHeader("Authorization"))
                assertEquals("Bearer ${fixture.patSecretA}", recordedA.single { it.routePath() == "/api/user/self" }.getHeader("Authorization"))
                assertEquals("Bearer ${fixture.keySecretA}", recordedA.single { it.routePath() == "/api/usage/token/" }.getHeader("Authorization"))

                // 回傳的 typed 結果必須是 A 自己的值（quota／requestCount 區分的主斷言）。
                val usageA = requireNotNull(resultA.accountUsage) { "sync must return the typed usage read" }
                val latestA = requireNotNull(usageA.latest) { "the account leg must record a usage fact" }
                assertUsageFact(latestA, fixture, fixture.accountA, fixture.patRefA, COUNT_A, PHASE_A_MS)
                assertEquals(latestA.evidenceId, usageA.lastGood?.evidenceId)
                assertEquals("OK", resultA.accountQuota.latest?.status)
                assertEquals(QuotaState.QUOTA_AVAILABLE.name, resultA.accountQuota.latest?.state)
                assertEquals(QUOTA_RAW_A, resultA.accountQuota.latest?.remainingRaw)
                assertEquals(USED_A, resultA.accountQuota.latest?.usedRaw)
                val tokenA = requireNotNull(resultA.tokenQuota) { "explicit key A must produce a token quota" }
                assertEquals("OK", tokenA.latest?.status)
                assertEquals(TOKEN_USED_A, tokenA.latest?.usedRaw)
                assertEquals(1, usage.history(fixture.providerId, fixture.accountA).size)

                // A 的 persisted head：PRICE 七維 scope key、TOKEN key-owned scope key。
                val priceScopeA = PricingScope(fixture.providerId, fixture.accountA, null, fixture.model, PRICE_GROUP, fixture.endpointId, PRICE_OPERATION)
                val priceHeadA = app.database.snapshotFactDao().head(priceScopeA.storageKey(), SnapshotKind.PRICE.name)
                assertNotNull("A must own an exact 7-dim PRICE head", priceHeadA)
                assertEquals(
                    listOf(fixture.providerId, fixture.accountA, null, fixture.model, PRICE_GROUP, fixture.endpointId, PRICE_OPERATION),
                    parseScopeKey(priceHeadA!!.scopeKey),
                )
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(priceScopeA).latest!!.state)
                val tokenScopeA = NewApiSnapshotStore.tokenQuotaScope(fixture.providerId, fixture.accountA, InferenceKeyRef(fixture.keyRefA))
                val tokenHeadA = app.database.snapshotFactDao().head(tokenScopeA.storageKey(), SnapshotKind.TOKEN_QUOTA.name)
                assertNotNull("A's token head must be key-owned", tokenHeadA)
                assertEquals(
                    listOf(fixture.providerId, fixture.accountA, fixture.keyRefA, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL),
                    parseScopeKey(tokenHeadA!!.scopeKey),
                )

                // ---- Sync B（explicit account B＋key B；FakeClock 前進）----
                clock.advanceTo(PHASE_B_MS)
                val beforeB = server.requestCount
                val beforeLegsB = legs()
                val resultB = app.repository.syncNewApi(
                    fixture.providerId, adapter,
                    accountId = fixture.accountB, credentialId = fixture.keyRefB,
                )
                val recordedB = drainRequests(4)
                assertSixCallSequence(recordedB)
                assertEquals(listOf(1, 1, 1, 1, 0), legDelta(beforeLegsB, legs()))
                assertEquals(beforeB + 4, server.requestCount)
                assertEquals("Bearer ${fixture.patSecretB}", recordedB.single { it.routePath() == "/api/pricing" }.getHeader("Authorization"))
                assertEquals("Bearer ${fixture.patSecretB}", recordedB.single { it.routePath() == "/api/user/self" }.getHeader("Authorization"))
                assertEquals("Bearer ${fixture.keySecretB}", recordedB.single { it.routePath() == "/api/usage/token/" }.getHeader("Authorization"))

                // 回傳值必須區分 B（不是只核對 stored key 正確）：B 的值 ≠ A 的值。
                val usageB = requireNotNull(resultB.accountUsage)
                val latestB = requireNotNull(usageB.latest)
                assertUsageFact(latestB, fixture, fixture.accountB, fixture.patRefB, COUNT_B, PHASE_B_MS)
                assertTrue("repeated sync evidence must advance with the fake clock", latestB.observedAt > latestA.observedAt)
                assertEquals("OK", resultB.accountQuota.latest?.status)
                assertEquals(QUOTA_RAW_B, resultB.accountQuota.latest?.remainingRaw)
                assertEquals(USED_B, resultB.accountQuota.latest?.usedRaw)
                val tokenB = requireNotNull(resultB.tokenQuota)
                assertEquals("OK", tokenB.latest?.status)
                assertEquals(TOKEN_USED_B, tokenB.latest?.usedRaw)
                assertEquals(1, usage.history(fixture.providerId, fixture.accountB).size)
                assertTrue("distinct account rows must not share values", USED_B != USED_A && COUNT_B != COUNT_A && TOKEN_USED_B != TOKEN_USED_A)

                // B 的 sync 不得碰 A 的 persisted scope。
                assertEquals("A's usage must stay exactly one row", 1, usage.history(fixture.providerId, fixture.accountA).size)
                assertEquals(
                    "A's usage head must be untouched by B's sync",
                    latestA.evidenceId,
                    usage.observation(fixture.providerId, fixture.accountA).latest?.evidenceId,
                )
                assertEquals(
                    "A's account quota head must be untouched",
                    resultA.accountQuota.latest?.evidenceId,
                    app.repository.newApiSnapshots.quota(
                        NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.accountA),
                        SnapshotKind.ACCOUNT_QUOTA,
                    ).latest?.evidenceId,
                )
                assertEquals(
                    "A's token head must be untouched",
                    tokenHeadA.latestEvidenceId,
                    app.database.snapshotFactDao().head(tokenScopeA.storageKey(), SnapshotKind.TOKEN_QUOTA.name)?.latestEvidenceId,
                )
                assertEquals(
                    "A's PRICE head must be untouched",
                    priceHeadA.latestEvidenceId,
                    app.database.snapshotFactDao().head(priceScopeA.storageKey(), SnapshotKind.PRICE.name)?.latestEvidenceId,
                )

                // B 的 persisted head 同為七維／key-owned。
                val priceScopeB = PricingScope(fixture.providerId, fixture.accountB, null, fixture.model, PRICE_GROUP, fixture.endpointId, PRICE_OPERATION)
                val priceHeadB = app.database.snapshotFactDao().head(priceScopeB.storageKey(), SnapshotKind.PRICE.name)
                assertNotNull("B must own an exact 7-dim PRICE head", priceHeadB)
                assertEquals(
                    listOf(fixture.providerId, fixture.accountB, null, fixture.model, PRICE_GROUP, fixture.endpointId, PRICE_OPERATION),
                    parseScopeKey(priceHeadB!!.scopeKey),
                )
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(priceScopeB).latest!!.state)
                val tokenScopeB = NewApiSnapshotStore.tokenQuotaScope(fixture.providerId, fixture.accountB, InferenceKeyRef(fixture.keyRefB))
                val tokenHeadB = app.database.snapshotFactDao().head(tokenScopeB.storageKey(), SnapshotKind.TOKEN_QUOTA.name)
                assertNotNull(tokenHeadB)
                assertEquals(
                    listOf(fixture.providerId, fixture.accountB, fixture.keyRefB, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL),
                    parseScopeKey(tokenHeadB!!.scopeKey),
                )

                // Vault 只解析過自己擁有的四把 ref，從未解析外來 ref。
                assertEquals(
                    setOf(fixture.patRefA, fixture.keyRefA, fixture.patRefB, fixture.keyRefB),
                    vault.asked.map { it.substringBefore('@') }.toSet(),
                )
            }
        }
    }

    // ===== 2. Bundle：收到的 B scope 精確透傳，membership 保留 =====

    @Test
    fun bundleForwardsRequestedAccountBScopeAndKeepsMembership() {
        val tag = shortId()
        val fixture = seedFixture("bundle", tag)
        val clock = ScriptedClock(PHASE_A_MS)
        server.dispatcher = TwoAccountDispatcher(
            fixture.model,
            accountBodies = mapOf(
                fixture.patSecretA to accountJson(QUOTA_RAW_A, USED_A, COUNT_A),
                fixture.patSecretB to accountJson(QUOTA_RAW_B, USED_B, COUNT_B),
            ),
            tokenBodies = mapOf(
                fixture.keySecretA to tokenJson(TOKEN_GRANTED_A, TOKEN_USED_A),
                fixture.keySecretB to tokenJson(TOKEN_GRANTED_B, TOKEN_USED_B),
            ),
        )
        val vault = CountingVault(fixture.secrets())

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val store = MyModelsStore(app.database)
                val addedAt = 7_000L
                assertTrue(store.addToMyModels(fixture.providerId, fixture.model, source = "USER", nowMillis = addedAt))

                val adapter = NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(Loopback(server)).build(), clock)
                val before = server.requestCount
                val beforeLegs = legs()
                val result = app.repository.enrichMyModelBundle(
                    providerId = fixture.providerId,
                    providerModelId = fixture.model,
                    accountId = fixture.accountB,
                    credentialId = fixture.keyRefB,
                    group = PRICE_GROUP,
                    endpointId = fixture.endpointId,
                    operation = PRICE_OPERATION,
                    newApiAdapter = adapter,
                    newApiPerfAdapter = null,
                )
                val recorded = drainRequests(4)
                assertSixCallSequence(recorded)
                assertEquals(listOf(1, 1, 1, 1, 0), legDelta(beforeLegs, legs()))
                assertEquals(before + 4, server.requestCount)

                // bundle 必須把收到的 account/key 透傳：pricing／account 用 PAT B，token 用 key B。
                assertEquals("Bearer ${fixture.patSecretB}", recorded.single { it.routePath() == "/api/pricing" }.getHeader("Authorization"))
                assertEquals("Bearer ${fixture.patSecretB}", recorded.single { it.routePath() == "/api/user/self" }.getHeader("Authorization"))
                assertEquals("Bearer ${fixture.keySecretB}", recorded.single { it.routePath() == "/api/usage/token/" }.getHeader("Authorization"))

                // 回傳的 quota／token 必為 B 的值，usage fact 帶 B 的 PAT provenance。
                assertEquals("OK", result.newApiAccountQuota?.latest?.status)
                assertEquals(USED_B, result.newApiAccountQuota?.latest?.usedRaw)
                assertEquals("OK", result.newApiTokenQuota?.latest?.status)
                assertEquals(TOKEN_USED_B, result.newApiTokenQuota?.latest?.usedRaw)
                assertEquals(1, result.newApiPricingSynced)
                assertTrue("bundle must report no failed legs", result.stepFailures.isEmpty())
                assertEquals("FREE_NOW", result.freePriceState)
                val usageB = requireNotNull(
                    app.repository.providerAccountUsage.observation(fixture.providerId, fixture.accountB).latest,
                ) { "the bundle must record B's account usage" }
                assertUsageFact(usageB, fixture, fixture.accountB, fixture.patRefB, COUNT_B, PHASE_A_MS)
                assertTrue(
                    "account A must not be synced by B's bundle",
                    app.repository.providerAccountUsage.history(fixture.providerId, fixture.accountA).isEmpty(),
                )

                // token head key-owned by B's key（七維）。
                val tokenScopeB = NewApiSnapshotStore.tokenQuotaScope(fixture.providerId, fixture.accountB, InferenceKeyRef(fixture.keyRefB))
                val tokenHeadB = app.database.snapshotFactDao().head(tokenScopeB.storageKey(), SnapshotKind.TOKEN_QUOTA.name)
                assertNotNull(tokenHeadB)
                assertEquals(
                    listOf(fixture.providerId, fixture.accountB, fixture.keyRefB, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL),
                    parseScopeKey(tokenHeadB!!.scopeKey),
                )

                // membership 保留：bundle 不改寫也不刪除。
                assertTrue(store.isMyModel(fixture.providerId, fixture.model))
                val membership = requireNotNull(membershipRow(fixture.providerId, fixture.model)) { "membership row must be retained" }
                assertEquals("USER", membership.second)
                assertEquals(addedAt, membership.first)
                assertTrue(result.aliasAttached)
                assertEquals(MappingSource.NORMALIZATION.name, result.aliasSource)
                assertEquals(MappingConfidence.LOW.name, result.aliasConfidence)
            }
        }
    }

    // ===== 3. Legacy（省略帳號＋省略 key）多帳號：fail-closed，0 HTTP／0 vault／0 寫入 =====

    @Test
    fun legacyAmbiguousMultiAccountFailsClosedBeforeAnyHttpVaultAccessOrWrite() {
        val tag = shortId()
        val fixture = seedFixture("ambig", tag)
        server.dispatcher = TwoAccountDispatcher(fixture.model, emptyMap(), emptyMap())
        val vault = CountingVault(fixture.secrets())

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val before = server.requestCount
                val beforeLegs = legs()
                val err = runCatching {
                    app.repository.syncNewApi(
                        fixture.providerId,
                        NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(Loopback(server)).build()),
                    )
                }.exceptionOrNull()
                assertTrue("multi-account legacy must fail closed, actual: $err", err is IllegalArgumentException)
                assertEquals("ambiguous legacy must not touch the network", before, server.requestCount)
                assertEquals("no leg may run", listOf(0, 0, 0, 0, 0), legDelta(beforeLegs, legs()))
                assertTrue("no credential may be resolved from the vault", vault.asked.isEmpty())
                val message = err?.message ?: ""
                assertFalse("static message must not echo refs", message.contains(fixture.patRefA) || message.contains(fixture.patRefB) || message.contains(fixture.keyRefA) || message.contains(fixture.keyRefB))
                assertFalse("static message must not echo secrets", message.contains(fixture.patSecretA) || message.contains(fixture.patSecretB))
                assertFalse("static message must not leak URL", message.contains("http"))

                // 失敗前不得有任何寫入。
                val usage = app.repository.providerAccountUsage
                assertTrue(usage.history(fixture.providerId, fixture.accountA).isEmpty())
                assertTrue(usage.history(fixture.providerId, fixture.accountB).isEmpty())
                assertNull(
                    "account quota head must not exist for A",
                    app.repository.newApiSnapshots.quota(
                        NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.accountA),
                        SnapshotKind.ACCOUNT_QUOTA,
                    ).latest,
                )
                assertNull(
                    "account quota head must not exist for B",
                    app.repository.newApiSnapshots.quota(
                        NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.accountB),
                        SnapshotKind.ACCOUNT_QUOTA,
                    ).latest,
                )
                val priceScopeA = PricingScope(fixture.providerId, fixture.accountA, null, fixture.model, PRICE_GROUP, fixture.endpointId, PRICE_OPERATION)
                assertNull(app.database.snapshotFactDao().head(priceScopeA.storageKey(), SnapshotKind.PRICE.name))
                val tokenScopeA = NewApiSnapshotStore.tokenQuotaScope(fixture.providerId, fixture.accountA, InferenceKeyRef(fixture.keyRefA))
                assertNull(app.database.snapshotFactDao().head(tokenScopeA.storageKey(), SnapshotKind.TOKEN_QUOTA.name))
            }
        }
    }

    // ===== 4. 顯式 account A＋key B：ownership mismatch，0 HTTP／0 vault／0 寫入 =====

    @Test
    fun explicitAccountAWithInferenceKeyBRejectedBeforeAnyHttp() {
        val tag = shortId()
        val fixture = seedFixture("mismatch", tag)
        server.dispatcher = TwoAccountDispatcher(fixture.model, emptyMap(), emptyMap())
        val vault = CountingVault(fixture.secrets())

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val before = server.requestCount
                val beforeLegs = legs()
                val err = runCatching {
                    app.repository.syncNewApi(
                        fixture.providerId,
                        NewApiAdapter(vault, OkHttpClient.Builder().addInterceptor(Loopback(server)).build()),
                        accountId = fixture.accountA, credentialId = fixture.keyRefB,
                    )
                }.exceptionOrNull()
                assertTrue("account A + key B must be rejected, actual: $err", err is IllegalArgumentException)
                assertEquals("ownership rejection must not touch the network", before, server.requestCount)
                assertEquals("no leg may run", listOf(0, 0, 0, 0, 0), legDelta(beforeLegs, legs()))
                assertTrue("no credential may be resolved from the vault", vault.asked.isEmpty())
                val message = err?.message ?: ""
                assertFalse("rejection must not echo any ref", message.contains(fixture.keyRefA) || message.contains(fixture.keyRefB) || message.contains(fixture.patRefA) || message.contains(fixture.patRefB))
                assertFalse("rejection must not echo secrets", message.contains(fixture.keySecretA) || message.contains(fixture.keySecretB) || message.contains(fixture.patSecretA) || message.contains(fixture.patSecretB))
                assertFalse("rejection must not leak URL", message.contains("http"))

                // 失敗前不得有任何寫入。
                val usage = app.repository.providerAccountUsage
                assertTrue(usage.history(fixture.providerId, fixture.accountA).isEmpty())
                assertTrue(usage.history(fixture.providerId, fixture.accountB).isEmpty())
                assertNull(
                    "account quota head must not exist for A",
                    app.repository.newApiSnapshots.quota(
                        NewApiSnapshotStore.accountQuotaScope(fixture.providerId, fixture.accountA),
                        SnapshotKind.ACCOUNT_QUOTA,
                    ).latest,
                )
                assertNull(
                    "token quota head must not exist for B",
                    app.database.snapshotFactDao().head(
                        NewApiSnapshotStore.tokenQuotaScope(fixture.providerId, fixture.accountB, InferenceKeyRef(fixture.keyRefB)).storageKey(),
                        SnapshotKind.TOKEN_QUOTA.name,
                    ),
                )
            }
        }
    }

    // ===== harness（fixture 僅本檔，不當產品資料；FK-RESTRICT 精確清理，不全域 teardown） =====

    private class TwoAccountFixture(
        val tag: String,
        val providerId: String,
        val accountA: String,
        val accountB: String,
        val patRefA: String,
        val keyRefA: String,
        val patRefB: String,
        val keyRefB: String,
        val patSecretA: String,
        val keySecretA: String,
        val patSecretB: String,
        val keySecretB: String,
        val endpointId: String,
        val model: String,
    ) {
        /** 兩把帳號 PATs＋兩把 inference keys 的 synthetic fake secrets，只服務 loopback。 */
        fun secrets(): Map<String, String> = mapOf(
            patRefA to patSecretA,
            keyRefA to keySecretA,
            patRefB to patSecretB,
            keyRefB to keySecretB,
        )
    }

    private fun seedFixture(name: String, tag: String): TwoAccountFixture = runBlocking {
        val providerId = "exact-sync-$name-$tag"
        val fixture = TwoAccountFixture(
            tag = tag,
            providerId = providerId,
            accountA = "a-$name-$tag",
            accountB = "b-$name-$tag",
            patRefA = "pat-a-$name-$tag",
            keyRefA = "key-a-$name-$tag",
            patRefB = "pat-b-$name-$tag",
            keyRefB = "key-b-$name-$tag",
            patSecretA = "exact-pat-secret-a-$name-$tag-0123456789",
            keySecretA = "exact-key-secret-a-$name-$tag-9876543210",
            patSecretB = "exact-pat-secret-b-$name-$tag-fedcba9876",
            keySecretB = "exact-key-secret-b-$name-$tag-2468135790",
            endpointId = "e-$name-$tag",
            model = "mdl-$name-$tag",
        )
        providerIds += providerId
        val db = app.database
        val baseHost = "https://$providerId.example.com"
        db.providerDao().insert(ProviderEntity(providerId = providerId, name = "P $providerId"))
        db.accountDao().insert(AccountEntity(fixture.accountA, providerId))
        db.accountDao().insert(AccountEntity(fixture.accountB, providerId))
        db.credentialDao().insert(CredentialEntity(fixture.patRefA, fixture.accountA))
        db.credentialDao().insert(CredentialEntity(fixture.keyRefA, fixture.accountA))
        db.credentialDao().insert(CredentialEntity(fixture.patRefB, fixture.accountB))
        db.credentialDao().insert(CredentialEntity(fixture.keyRefB, fixture.accountB))
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
                workflowId = "wf-$providerId-exact",
                authMode = AuthMode.BEARER,
                credentials = listOf(
                    SafeCredentialRef(fixture.patRefA, "PAT", CredentialRole.ACCOUNT),
                    SafeCredentialRef(fixture.keyRefA, "KEY", CredentialRole.INFERENCE),
                    SafeCredentialRef(fixture.patRefB, "PAT", CredentialRole.ACCOUNT),
                    SafeCredentialRef(fixture.keyRefB, "KEY", CredentialRole.INFERENCE),
                ),
            ).toEntity(),
        )
        fixture
    }

    /** 精確斷言一筆 usage fact 的完整語意（scope／provenance／原值／source）。 */
    private fun assertUsageFact(
        fact: ProviderAccountUsageFactEntity,
        fixture: TwoAccountFixture,
        accountId: String,
        patRef: String,
        requestCount: Long,
        observedAt: Long,
    ) {
        assertEquals(providerAccountUsageScopeKey(fixture.providerId, accountId), fact.scopeKey)
        assertEquals(ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name, fact.kind)
        assertEquals(fixture.providerId, fact.providerId)
        assertEquals(accountId, fact.accountId)
        assertEquals(
            "the ACCOUNT PAT safe ref is provenance, never another account's PAT or the inference key",
            patRef, fact.credentialId,
        )
        assertEquals("OK", fact.status)
        assertEquals(NewApiSnapshotStore.ACCOUNT_SOURCE, fact.source)
        assertEquals(200, fact.httpStatus)
        assertEquals(NewApiVersion.RC25, fact.providerVersion)
        assertEquals(requestCount, fact.requestCountRaw)
        assertEquals(ProviderAccountUsageStore.COUNTER_SEMANTICS_UNSPECIFIED, fact.counterSemantics)
        assertTrue("a 2xx account response is an observed account endpoint", fact.accountEndpointObserved)
        assertEquals(observedAt, fact.observedAt)
        assertTrue("usage evidence ids live in their own namespace", fact.evidenceId.startsWith("usage:"))
        assertFalse("no raw secret may ever reach a usage fact", fact.toString().contains(fixture.patSecretA) || fact.toString().contains(fixture.keySecretA) || fact.toString().contains(fixture.patSecretB) || fact.toString().contains(fixture.keySecretB))
    }

    private fun membershipRow(providerId: String, modelId: String): Pair<Long, String>? =
        app.database.openHelper.readableDatabase.query(
            "SELECT addedAt, source FROM my_models WHERE providerId = ? AND providerModelId = ?",
            arrayOf<Any?>(providerId, modelId),
        ).use { c ->
            if (!c.moveToFirst()) null else c.getLong(0) to c.getString(1)
        }

    private fun drainRequests(expected: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(expected)
        repeat(expected) {
            out += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request #$it (expected $expected)" }
        }
        return out
    }

    /** dual-mode 契約凍結：certified 自命中為 4-call——status／pricing／account／token；6-shape 僅見於 passport-miss（由 V-6 B-leg pin）。 */
    private fun assertSixCallSequence(recorded: List<RecordedRequest>) {
        assertEquals(
            listOf("/api/status", "/api/pricing", "/api/user/self", "/api/usage/token/"),
            recorded.map { it.routePath() },
        )
    }

    private fun RecordedRequest.routePath(): String = requestUrl!!.encodedPath

    private fun legs(): List<Int> = (server.dispatcher as TwoAccountDispatcher).tally()

    private fun legDelta(before: List<Int>, after: List<Int>): List<Int> =
        after.zip(before).map { (now, was) -> now - was }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    /**
     * 站方回應由本次 request 實際帶上的 `Authorization` 決定：PAT A → A 帳號、
     * PAT B → B 帳號、key A → A key token、key B → B key token；其餘 401。
     * 因此借用外來 credential 會在回傳值與 Authorization 斷言雙重失敗。
     */
    private class TwoAccountDispatcher(
        private val model: String,
        private val accountBodies: Map<String, String>,
        private val tokenBodies: Map<String, String>,
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
                "/api/user/self" -> routeByAuth(request, accountBodies).also { account.incrementAndGet() }
                "/api/usage/token/" -> routeByAuth(request, tokenBodies).also { token.incrementAndGet() }
                else -> MockResponse().setResponseCode(401).setBody("""{"success":false,"message":"$AUTH_CANARY"}""").also { unexpected.incrementAndGet() }
            }
        }

        private fun routeByAuth(request: RecordedRequest, bodies: Map<String, String>): MockResponse {
            val body = strictBearerSecret(request.getHeader("Authorization"))?.let { bodies[it] }
            return if (body != null) {
                ok(body)
            } else {
                MockResponse().setResponseCode(401).setBody("""{"success":false,"message":"$AUTH_CANARY"}""")
            }
        }

        /** 嚴格 Bearer 驗證：缺 header／wrong scheme／空或含空白 secret 一律 null（→401）。 */
        private fun strictBearerSecret(header: String?): String? {
            if (header == null) return null
            val prefix = "Bearer "
            if (!header.startsWith(prefix)) return null
            val secret = header.substring(prefix.length)
            if (secret.isEmpty() || secret != secret.trim() || secret.any { it.isWhitespace() }) return null
            return secret
        }

        private fun ok(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)

        fun tally(): List<Int> = listOf(status.get(), pricing.get(), account.get(), token.get(), unexpected.get())
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
        const val PRICE_GROUP = "default"
        const val PRICE_OPERATION = "CHAT_COMPLETIONS"
        const val QUOTA_RAW_A = 1_000_000L
        const val USED_A = 250_000L
        const val COUNT_A = 4_242_424L
        const val QUOTA_RAW_B = 2_000_000L
        const val USED_B = 750_000L
        const val COUNT_B = 8_641_000L
        const val TOKEN_GRANTED_A = 1_000L
        const val TOKEN_USED_A = 400L
        const val TOKEN_GRANTED_B = 9_000L
        const val TOKEN_USED_B = 7_500L
        const val PHASE_A_MS = 1_000L
        const val PHASE_B_MS = 2_000L
        const val AUTH_CANARY = "exact-account-auth-canary-must-never-escape"

        fun statusJson(): String =
            """{"success":true,"message":"","data":{"version":"$RC25_VERSION","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

        fun pricingJson(model: String): String =
            """{"success":true,"message":"","data":[{"model_name":"$model","quota_type":0,"model_ratio":0,"completion_ratio":1,"enable_groups":["all"],"supported_endpoint_types":["openai"]}],"group_ratio":{"default":1},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"exact-sync-v1"}"""

        /** 站方帳號回應：quota/used/request_count 原值由 caller 指定（synthetic fixture）。 */
        fun accountJson(quota: Long, used: Long, requestCount: Long): String =
            """{"success":true,"message":"","data":{"quota":$quota,"used_quota":$used,"request_count":$requestCount,"group":"default","role":1,"status":1}}"""

        fun tokenJson(granted: Long, used: Long): String =
            """{"code":true,"message":"ok","data":{"object":"token_usage","name":"exact-key","total_granted":$granted,"total_used":$used,"total_available":${granted - used},"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""
    }
}
