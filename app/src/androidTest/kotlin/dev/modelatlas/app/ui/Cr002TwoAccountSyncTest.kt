package dev.modelatlas.app.ui

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.MyModelsStore
import dev.modelatlas.app.data.NewApiSnapshotStore
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.ProviderManualConfig
import dev.modelatlas.app.data.SnapshotKind
import dev.modelatlas.app.data.deleteOwnProviderAccountUsageRows
import dev.modelatlas.app.data.storageKey
import dev.modelatlas.app.data.parseScopeKey
import dev.modelatlas.app.data.toEntity
import dev.modelatlas.core.domain.PricingScope
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-002 A2 two-account wave-identity device test (VM level through
 * `AtlasViewModel.maybeEnrichMyModel`; landed repo file, CI pending).
 *
 * Landed from the `/tmp` draft per `ai9-cr002-test-ports-check-result.md`
 * verdicts: package corrected to this directory (`dev.modelatlas.app.ui`,
 * with explicit imports for the `data`-package harness symbols); vector list
 * below is the draft's actual scope (wave/dedup/follower/transport/facts —
 * per-account support exactness and guard families are NOT claimed here);
 * this file itself uses zero sleeps (15s `withTimeout` + 5s `takeRequest`
 * timeouts only).
 *
 * Vectors (exactly 2 tests):
 * 1. `queuedAccountBRunsOwnPatAndTokenQuotaLegsWhileFollowerAwaitsLeader` —
 *    CR-002 A2 queued A/B wave identity: leader A blocked on a barrier,
 *    same-scope follower awaits the actual in-flight work (no early return,
  *    no extra transport), leader B queued on the provider lock runs its own
  *    full 6-call leg (misses A's registry passport, all legs fresh) with its
  *    own PAT/key after release (fetchPricing==2,
  *    10 HTTP total = A 4 (own-passport self-hit) + B 6; B account/token
  *    quota + usage not suppressed by A's
  *    generation). RED-by-design pre-A2-fix (B suppressed by the
  *    provider-keyed generation → 0 New API HTTP for B); GREEN after the A2
  *    generation re-key.
  * 2. `sameScopeFollowerAwaitsLeaderResultWithoutExtraTransport` — same-scope
  *    follower only: two identical-key callers, one 4-call wave. GREEN on
 *    current HEAD throughout (fresh `ViewModelStore` per test → empty
 *    generations); kept as the RED/GREEN-scoping regression guard.
 *
 * Production assumption under test (spec-cr002 §2.5, VM-internal, no new
 * parameter): `newApiLegGenerations` keyed by
 * `newApiSyncKey(providerId, accountId, credentialId)` instead of bare
 * `providerId`.
 *
 * Harness reuse (no re-implementation of accepted coverage):
 * - two-account fixture / auth-routing dispatcher / CountingVault shape:
 *   `NewApiExactAccountSyncTest` (TwoAccountDispatcher);
 * - VM creation, gated loopback interceptor (`CompletableDeferred` barrier),
 *   `::pricingOnlyPerfPort`, exact 6-call drain:
 *   `NewApiBundleProductionFlowTest`;
 * - shared cleanup helpers `deleteOwnProviderAccountUsageRows` /
 *   `parseScopeKey` (androidTest `data` package + main `SnapshotFacts.kt`).
 */
@RunWith(AndroidJUnit4::class)
class Cr002TwoAccountSyncTest {

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

    // Perf transport is out of this vector: the third leg stays a null controlled port so
    // the exact HTTP count is pricing/account/token only (production never passes this).
    private fun pricingOnlyPerfPort(): NewApiAdapter? = null

    // =========================================================================
    // 1. Core V2: queued A (gated leader) -> follower(A) -> B leader.
    //    After release: A runs its own 4-call leg (own-passport self-hit) and B
    //    runs its own full 6-call leg (misses A's registry passport, all legs
    //    fresh) with its own PAT/key; B account/token/quota/usage must exist
    //    with B values.
    // =========================================================================

    @Test
    fun queuedAccountBRunsOwnPatAndTokenQuotaLegsWhileFollowerAwaitsLeader() {
        val tag = shortId()
        val f = seedFixture("queued", tag)
        val model = f.model

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val store = MyModelsStore(app.database)
                assertTrue(store.addToMyModels(f.providerId, model, source = "USER", nowMillis = 5_000L))

                // Auth-routed dispatcher (bodies selected by the Authorization actually sent).
                val dispatcher = RoutingDispatcher(
                    model = model,
                    accountBodies = mapOf(
                        f.patSecretA to accountJson(QUOTA_RAW_A, USED_A, COUNT_A),
                        f.patSecretB to accountJson(QUOTA_RAW_B, USED_B, COUNT_B),
                    ),
                    tokenBodies = mapOf(
                        f.keySecretA to tokenJson(TOKEN_GRANTED_A, TOKEN_USED_A),
                        f.keySecretB to tokenJson(TOKEN_GRANTED_B, TOKEN_USED_B),
                    ),
                )
                server.dispatcher = dispatcher

                // --- Precondition: provider already has New API official evidence
                // (hasNewApiEvidenceHead gate) via one real A sync; drained separately. ---
                val seedVault = CountingVault(f.secrets())
                val seedClient = OkHttpClient.Builder().addInterceptor(GatedLoopback(server)).build()
                val seed = app.repository.syncNewApi(
                    f.providerId,
                    NewApiAdapter(seedVault, seedClient),
                    accountId = f.accountA,
                    credentialId = f.keyRefA,
                )
                assertEquals(1, seed.prices.size)
                drainRequests(4)
                assertEquals(1, app.repository.providerAccountUsage.history(f.providerId, f.accountA).size)

                // --- Gated wave: first /api/pricing blocks until release. ---
                val pricingHits = AtomicInteger(0)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val builds = AtomicInteger(0)
                val vault = CountingVault(f.secrets())
                val gateClient = OkHttpClient.Builder()
                    .addInterceptor(
                        GatedLoopback(
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
                val baseline = server.requestCount
                val beforeLegs = legs()

                val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = f.providerId,
                            accountId = f.accountA,
                            credentialId = f.keyRefA,
                            group = PRICE_GROUP,
                            providerModelId = model,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    }
                }
                withTimeout(TIMEOUT_MS) { entered.await() }
                // A's pricing request is intercepted and held before the socket write:
                // exactly the preceding /api/status is recorded, and A is not complete.
                assertFalse("gated leader must not complete", jobA.isCompleted)
                assertEquals(1, pricingHits.get())
                assertEquals(1, server.requestCount - baseline)

                // Same-scope follower: same provider+account+credential+group.
                val jobFollower = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = f.providerId,
                            accountId = f.accountA,
                            credentialId = f.keyRefA,
                            group = PRICE_GROUP,
                            providerModelId = model,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    }
                }
                assertFalse("same-scope follower must await the actual in-flight work", jobFollower.isCompleted)
                assertEquals("follower must not add transport", 1, pricingHits.get())
                assertEquals(1, server.requestCount - baseline)

                // Account B, same provider/model but own PAT+key: must queue on the
                // provider lock, and must NOT be suppressed by A's wave generation.
                val jobB = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = f.providerId,
                            accountId = f.accountB,
                            credentialId = f.keyRefB,
                            group = PRICE_GROUP,
                            providerModelId = model,
                            lmspeedFetcher = null,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    }
                }
                assertFalse("B leader must queue behind A, not complete early", jobB.isCompleted)
                assertEquals("queued B must not burn transport while A is gated", 1, pricingHits.get())
                assertEquals(1, server.requestCount - baseline)

                release.complete(Unit)
                assertTrue("A leader must complete after release", withTimeout(TIMEOUT_MS) { jobA.await() })
                assertTrue("same-scope follower must complete after leader", withTimeout(TIMEOUT_MS) { jobFollower.await() })
                assertTrue("B leader must complete after A releases the provider lock", withTimeout(TIMEOUT_MS) { jobB.await() })

                // --- Transport identity: A and B each run their own full leg. ---
                assertEquals("different account/key must not be deduped by A's wave", 2, pricingHits.get())
                assertEquals("one privileged adapter per account leader; follower builds none", 2, builds.get())
                assertEquals("A(4) + B(6) exact contract (A own-passport self-hit; B misses A's registry passport and refetches all legs fresh)", 10, server.requestCount - baseline)
                assertEquals(
                    "A(4) + B(6) per-leg contract",
                    listOf(4, 2, 2, 2, 0),
                    legDelta(beforeLegs, legs()),
                )
                assertEquals("no perf/inference/foreign HTTP", 0, dispatcher.unexpected.get())

                val recorded = drainRequests(10)
                val paths = recorded.map { it.routePath() }
                assertEquals(
                    listOf(
                        "/api/status", "/api/pricing", "/api/user/self", "/api/usage/token/",
                        "/api/status", "/api/pricing", "/api/status", "/api/user/self", "/api/status", "/api/usage/token/",
                    ),
                    paths,
                )
                assertEquals(
                    "each account's pricing/account leg uses its own PAT",
                    listOf("Bearer ${f.patSecretA}", "Bearer ${f.patSecretB}"),
                    recorded.filter { it.routePath() == "/api/pricing" }.map { it.getHeader("Authorization") },
                )
                assertEquals(
                    listOf("Bearer ${f.patSecretA}", "Bearer ${f.patSecretB}"),
                    recorded.filter { it.routePath() == "/api/user/self" }.map { it.getHeader("Authorization") },
                )
                assertEquals(
                    "each account's token leg uses its own inference key",
                    listOf("Bearer ${f.keySecretA}", "Bearer ${f.keySecretB}"),
                    recorded.filter { it.routePath() == "/api/usage/token/" }.map { it.getHeader("Authorization") },
                )
                assertEquals(
                    "vault resolved exactly the four owned safe refs, no foreign borrow",
                    setOf(f.patRefA, f.keyRefA, f.patRefB, f.keyRefB),
                    vault.asked.map { it.substringBefore('@') }.toSet(),
                )

                // --- B PAT/token values and B quota/usage legs actually ran. ---
                val usage = app.repository.providerAccountUsage
                val usageA = requireNotNull(usage.observation(f.providerId, f.accountA).latest)
                assertEquals(COUNT_A, usageA.requestCountRaw)
                assertEquals(f.patRefA, usageA.credentialId)
                assertTrue(usageA.accountEndpointObserved)

                val usageB = requireNotNull(usage.observation(f.providerId, f.accountB).latest) {
                    "A's provider-only wave must not stand in for Account B; B usage must exist"
                }
                assertEquals("B usage must carry B's request_count, not A's", COUNT_B, usageB.requestCountRaw)
                assertEquals("B usage provenance must be B's ACCOUNT PAT", f.patRefB, usageB.credentialId)
                assertTrue(usageB.accountEndpointObserved)
                assertEquals("B leg must be persisted for B only", 1, usage.history(f.providerId, f.accountB).size)
                assertEquals("A seed + A wave = 2, B wave did not touch A", 2, usage.history(f.providerId, f.accountA).size)
                assertTrue(
                    "dispatcher values are deliberately distinct",
                    USED_A != USED_B && COUNT_A != COUNT_B && TOKEN_USED_A != TOKEN_USED_B,
                )

                val quotaA = requireNotNull(
                    app.repository.newApiSnapshots.quota(
                        NewApiSnapshotStore.accountQuotaScope(f.providerId, f.accountA),
                        SnapshotKind.ACCOUNT_QUOTA,
                    ).latest,
                )
                val quotaB = requireNotNull(
                    app.repository.newApiSnapshots.quota(
                        NewApiSnapshotStore.accountQuotaScope(f.providerId, f.accountB),
                        SnapshotKind.ACCOUNT_QUOTA,
                    ).latest,
                ) { "B account quota leg must run under B's PAT" }
                assertEquals(USED_A, quotaA.usedRaw)
                assertEquals(USED_B, quotaB.usedRaw)

                val tokenScopeA = NewApiSnapshotStore.tokenQuotaScope(
                    f.providerId, f.accountA, InferenceKeyRef(f.keyRefA),
                )
                val tokenScopeB = NewApiSnapshotStore.tokenQuotaScope(
                    f.providerId, f.accountB, InferenceKeyRef(f.keyRefB),
                )
                val tokenHeadB = app.database.snapshotFactDao().head(tokenScopeB.storageKey(), SnapshotKind.TOKEN_QUOTA.name)
                assertNotNull("B token head must be key-owned by B's inference key", tokenHeadB)
                assertEquals(
                    listOf(f.providerId, f.accountB, f.keyRefB, "", null, "", NewApiDomainMapper.OPERATION_GLOBAL),
                    parseScopeKey(tokenHeadB!!.scopeKey),
                )
                assertEquals(
                    TOKEN_USED_B,
                    app.repository.newApiSnapshots.quota(tokenScopeB, SnapshotKind.TOKEN_QUOTA).latest?.usedRaw,
                )
                assertEquals(
                    TOKEN_USED_A,
                    app.repository.newApiSnapshots.quota(tokenScopeA, SnapshotKind.TOKEN_QUOTA).latest?.usedRaw,
                )

                val priceScopeB = PricingScope(
                    f.providerId, f.accountB, null, model, PRICE_GROUP, f.endpointId, PRICE_OPERATION,
                )
                val priceHeadB = app.database.snapshotFactDao().head(priceScopeB.storageKey(), SnapshotKind.PRICE.name)
                assertNotNull("B must own its exact 7-dim PRICE head from its own wave", priceHeadB)
                assertEquals(
                    listOf(f.providerId, f.accountB, null, model, PRICE_GROUP, f.endpointId, PRICE_OPERATION),
                    parseScopeKey(priceHeadB!!.scopeKey),
                )
                assertEquals("FREE_NOW", app.repository.newApiSnapshots.price(priceScopeB).latest!!.state)

                assertTrue("membership untouched", store.isMyModel(f.providerId, model))
            }
        }
    }

    // =========================================================================
    // 2. Same-scope follower only: two identical-key callers, one wave, follower
    //    completes only after the leader's actual work, zero extra transport.
    // =========================================================================

    @Test
    fun sameScopeFollowerAwaitsLeaderResultWithoutExtraTransport() {
        val tag = shortId()
        val f = seedFixture("follower", tag)
        val model = f.model

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                assertTrue(MyModelsStore(app.database).addToMyModels(f.providerId, model, source = "USER", nowMillis = 6_000L))

                server.dispatcher = RoutingDispatcher(
                    model = model,
                    accountBodies = mapOf(
                        f.patSecretA to accountJson(QUOTA_RAW_A, USED_A, COUNT_A),
                        f.patSecretB to accountJson(QUOTA_RAW_B, USED_B, COUNT_B),
                    ),
                    tokenBodies = mapOf(
                        f.keySecretA to tokenJson(TOKEN_GRANTED_A, TOKEN_USED_A),
                        f.keySecretB to tokenJson(TOKEN_GRANTED_B, TOKEN_USED_B),
                    ),
                )
                val seedClient = OkHttpClient.Builder().addInterceptor(GatedLoopback(server)).build()
                app.repository.syncNewApi(
                    f.providerId,
                    NewApiAdapter(CountingVault(f.secrets()), seedClient),
                    accountId = f.accountA,
                    credentialId = f.keyRefA,
                )
                drainRequests(4)

                val pricingHits = AtomicInteger(0)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val builds = AtomicInteger(0)
                val gateClient = OkHttpClient.Builder()
                    .addInterceptor(
                        GatedLoopback(server, pricingHits, entered, release, gateFirstPricingOnly = true),
                    )
                    .build()
                val factory = {
                    builds.incrementAndGet()
                    NewApiAdapter(CountingVault(f.secrets()), gateClient)
                }
                val baseline = server.requestCount
                val beforeLegs = legs()

                val jobLeader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = f.providerId,
                            accountId = f.accountA,
                            credentialId = f.keyRefA,
                            group = PRICE_GROUP,
                            providerModelId = model,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    }
                }
                withTimeout(TIMEOUT_MS) { entered.await() }

                val jobFollower = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        vm.maybeEnrichMyModel(
                            providerId = f.providerId,
                            accountId = f.accountA,
                            credentialId = f.keyRefA,
                            group = PRICE_GROUP,
                            providerModelId = model,
                            newApiAdapterFactory = factory,
                            newApiPerfAdapterFactory = ::pricingOnlyPerfPort,
                        )
                    }
                }
                assertFalse(jobLeader.isCompleted)
                assertFalse("follower must not resolve early", jobFollower.isCompleted)
                assertEquals("follower must share the single in-flight wave", 1, pricingHits.get())

                release.complete(Unit)
                assertTrue(jobLeader.await())
                assertTrue("follower completes without exception once the leader's deferred is completed", jobFollower.await())

                assertEquals(1, pricingHits.get())
                assertEquals("follower must not build a second privileged adapter", 1, builds.get())
                assertEquals("exactly one 4-call wave for the identical key (own-passport reuse)", 4, server.requestCount - baseline)
                assertEquals(
                    "exactly one 4-call wave per-leg contract",
                    listOf(1, 1, 1, 1, 0),
                    legDelta(beforeLegs, legs()),
                )
                assertEquals(
                    listOf("/api/status", "/api/pricing", "/api/user/self", "/api/usage/token/"),
                    drainRequests(4).map { it.routePath() },
                )
            }
        }
    }

    // =========================================================================
    // Fixture / harness (synthetic, loopback-only; no product data).
    // =========================================================================

    private class Fixture(
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
        fun secrets(): Map<String, String> = mapOf(
            patRefA to patSecretA,
            keyRefA to keySecretA,
            patRefB to patSecretB,
            keyRefB to keySecretB,
        )
    }

    private fun seedFixture(name: String, tag: String): Fixture = runBlocking {
        val providerId = "cr002-wave-$name-$tag"
        val f = Fixture(
            providerId = providerId,
            accountA = "a-$name-$tag",
            accountB = "b-$name-$tag",
            patRefA = "pat-a-$name-$tag",
            keyRefA = "key-a-$name-$tag",
            patRefB = "pat-b-$name-$tag",
            keyRefB = "key-b-$name-$tag",
            patSecretA = "wave-pat-secret-a-$name-$tag-0123456789",
            keySecretA = "wave-key-secret-a-$name-$tag-9876543210",
            patSecretB = "wave-pat-secret-b-$name-$tag-fedcba9876",
            keySecretB = "wave-key-secret-b-$name-$tag-2468135790",
            endpointId = "e-$name-$tag",
            model = "mdl-$name-$tag",
        )
        providerIds += providerId
        val db = app.database
        val baseHost = "https://$providerId.example.com"
        db.providerDao().insert(ProviderEntity(providerId = providerId, name = "P $providerId"))
        db.accountDao().insert(AccountEntity(f.accountA, providerId))
        db.accountDao().insert(AccountEntity(f.accountB, providerId))
        db.credentialDao().insert(CredentialEntity(f.patRefA, f.accountA))
        db.credentialDao().insert(CredentialEntity(f.keyRefA, f.accountA))
        db.credentialDao().insert(CredentialEntity(f.patRefB, f.accountB))
        db.credentialDao().insert(CredentialEntity(f.keyRefB, f.accountB))
        db.endpointDao().insert(
            EndpointEntity(
                endpointId = f.endpointId,
                providerId = providerId,
                baseUrl = "$baseHost/v1",
                endpointUrl = "$baseHost/v1/chat/completions",
                protocol = "CHAT_COMPLETIONS",
            ),
        )
        db.offeringDao().insert(
            OfferingEntity(
                id = "off:$providerId:${f.model}",
                providerId = providerId,
                endpointId = f.endpointId,
                providerModelId = f.model,
                manual = false,
                source = "OFFICIAL",
                hidden = false,
            ),
        )
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = providerId,
                workflowId = "wf-$providerId-wave",
                authMode = AuthMode.BEARER,
                credentials = listOf(
                    SafeCredentialRef(f.patRefA, "PAT", CredentialRole.ACCOUNT),
                    SafeCredentialRef(f.keyRefA, "KEY", CredentialRole.INFERENCE),
                    SafeCredentialRef(f.patRefB, "PAT", CredentialRole.ACCOUNT),
                    SafeCredentialRef(f.keyRefB, "KEY", CredentialRole.INFERENCE),
                ),
            ).toEntity(),
        )
        f
    }

    /**
     * Routes account/token bodies by the Authorization actually sent, so a
     * cross-account borrow fails on both the header assertion and the value
     * assertion (same shape as `NewApiExactAccountSyncTest.TwoAccountDispatcher`,
     * but the per-fixture bodies are injected from the test instead of a global map).
     */
    private class RoutingDispatcher(
        private val model: String,
        private val accountBodies: Map<String, String>,
        private val tokenBodies: Map<String, String>,
    ) : Dispatcher() {
        val status = AtomicInteger(0)
        val pricing = AtomicInteger(0)
        val account = AtomicInteger(0)
        val token = AtomicInteger(0)
        val unexpected = AtomicInteger(0)

        fun tally(): List<Int> = listOf(status.get(), pricing.get(), account.get(), token.get(), unexpected.get())

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?')
            return when (path) {
                "/api/status" -> ok(statusJson()).also { status.incrementAndGet() }
                "/api/pricing" -> ok(pricingJson(model)).also { pricing.incrementAndGet() }
                "/api/user/self" -> routeByAuth(request, accountBodies).also { account.incrementAndGet() }
                "/api/usage/token/" -> routeByAuth(request, tokenBodies).also { token.incrementAndGet() }
                else -> MockResponse().setResponseCode(401)
                    .setBody("""{"success":false,"message":"$AUTH_CANARY"}""")
                    .also { unexpected.incrementAndGet() }
            }
        }

        private fun routeByAuth(request: RecordedRequest, bodies: Map<String, String>): MockResponse {
            val body = strictBearerSecret(request.getHeader("Authorization"))?.let { bodies[it] }
            return if (body != null) ok(body)
            else MockResponse().setResponseCode(401).setBody("""{"success":false,"message":"$AUTH_CANARY"}""")
        }

        /** Strict Bearer: missing header / wrong scheme / blank / whitespace => null (401). */
        private fun strictBearerSecret(header: String?): String? {
            if (header == null) return null
            val prefix = "Bearer "
            if (!header.startsWith(prefix)) return null
            val secret = header.substring(prefix.length)
            if (secret.isEmpty() || secret != secret.trim() || secret.any { it.isWhitespace() }) return null
            return secret
        }

        private fun ok(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)
    }

    private class CountingVault(secrets: Map<String, String>) : CredentialAccess {
        private val backing = secrets.toMap()
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            return backing[credentialId] ?: throw IllegalStateException("vault-refused")
        }
    }

    /** Test transport seam: rewrite https -> loopback and optionally gate the first pricing request. */
    private class GatedLoopback(
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
                if (pricingEntered != null && n == 1 && !pricingEntered.isCompleted) pricingEntered.complete(Unit)
                val gate = pricingRelease
                if (gate != null && (!gateFirstPricingOnly || n == 1)) {
                    runBlocking { gate.await() }
                }
            }
            val rewritten = orig.newBuilder().url(server.url(path).toString()).build()
            return chain.proceed(rewritten)
        }
    }

    private fun RecordedRequest.routePath(): String = requestUrl!!.encodedPath

    private fun legs(): List<Int> = (server.dispatcher as RoutingDispatcher).tally()

    private fun legDelta(before: List<Int>, after: List<Int>): List<Int> =
        after.zip(before).map { (now, was) -> now - was }

    private fun drainRequests(expected: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(expected)
        repeat(expected) {
            out += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "missing HTTP request #$it (expected $expected)" }
        }
        return out
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    /** Precise FK-RESTRICT cleanup for this file's UUID fixtures only (no global teardown). */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val db = app.database
        val writable = db.openHelper.writableDatabase
        val canonicalIds: List<String> = writable.query(
            "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN (" +
                providerIds.joinToString(",") { "?" } + ")",
            providerIds.toTypedArray<Any?>(),
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
            val placeholders = providerIds.joinToString(",") { "?" }
            val args = providerIds.toTypedArray<Any?>()
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
                writable.execSQL(
                    "DELETE FROM canonical_models WHERE canonicalModelId IN (" +
                        canonicalIds.joinToString(",") { "?" } + ") " +
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
        const val AUTH_CANARY = "two-account-wave-auth-canary-must-never-escape"

        fun statusJson(): String =
            """{"success":true,"message":"","data":{"version":"${NewApiVersion.RC25}","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

        fun pricingJson(model: String): String =
            """{"success":true,"message":"","data":[{"model_name":"$model","quota_type":0,"model_ratio":0,"completion_ratio":1,"enable_groups":["all"],"supported_endpoint_types":["openai"]}],"group_ratio":{"default":1},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"cr002-wave-v1"}"""

        fun accountJson(quota: Long, used: Long, requestCount: Long): String =
            """{"success":true,"message":"","data":{"quota":$quota,"used_quota":$used,"request_count":$requestCount,"group":"default","role":1,"status":1}}"""

        fun tokenJson(granted: Long, used: Long): String =
            """{"code":true,"message":"ok","data":{"object":"token_usage","name":"wave-key","total_granted":$granted,"total_used":$used,"total_available":${granted - used},"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""
    }
}
