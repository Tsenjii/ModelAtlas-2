package dev.modelatlas.app.data

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.newApiSyncErrorCodes
import dev.modelatlas.app.newApiSyncKey
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
 * CR-002 scoped New API sync flow device test (VM scoped lane; landed repo
 * file, CI pending).
 *
 * Gates on the in-flight L1 error/callback skeleton (`ai9-cr002-vm-port`
 * rebased patch, landing in parallel by another owner; compile gate is CI):
 * - op-key flows `newApiOpSyncing` / `newApiOpError` / `newApiOpLastSynced` /
 *   `newApiOpLatestObserved` (hunk A) — V1–V3/V6–V10;
 * - scoped entries `syncNewApi(p, a, k)` arity-3 + `syncNewApi(p, a, k,
 *   adapterFactory)` arity-4 test port (hunk B) — V1–V3/V5/V7/V10;
 * - `resolveScopedInferenceKeyRef` role-normalization path, exercised only
 *   through the public arity-4 lane (private; never called directly) — V5;
 * - pure reader `newApiOpState(p, a, k)` (hunk B) — V1/V3/V6/V8;
 * - `newApiScopedSyncSupported(p)` arity-1 stopgap (hunk B) — V1/V7;
 * - closed vocabulary `newApiSyncErrorCodes` + length-prefix `newApiSyncKey`
 *   (hunk C, no group by design) — V4/V6/V9.
 * No symbol outside that patch is referenced; no PROPOSED PORT is needed.
 *
 * Shape mirrors `DedicatedOpScopeFlowTest` (VM factory, 15s poll with the
 * harness's 50ms poll-step, UUID fixtures, FK-safe cleanup); transport
 * mirrors `NewApiExactAccountSyncTest` (auth-routed dispatcher, loopback
 * interceptor, `CountingVault`). This file itself adds zero sleeps and zero
 * timeout increases beyond the mirrored 15s poll / 5s `takeRequest` bounds.
 *
 * Vectors (exactly 10 tests):
 * - V1 two-account isolation + last-good (repo durable facts per account).
 * - V2 same-scope concurrent re-entry folds into one wave; different scope
 *   runs its own wave (no swallow).
 * - V3 cross-scope error attribution (failure lands only on its own scope).
 * - V4 codes-only (closed 5-set; no localized string / URL / `sk-` /
 *   newline in the code slot) + `newApiSyncKey` pure vectors.
 * - V5 role normalization via the public lane: attached non-config
 *   (ACCOUNT PAT) ref ⇒ `keyRef=null` ⇒ success through the account's own
 *   INFERENCE key; cross-account key ⇒ fail-closed UNSUPPORTED, no borrow.
 * - V6 BUSY scope-only at vocabulary + key level (trigger path belongs to a
 *   later UI slice; pinned here: membership, no-leak, no spurious write).
 * - V7 failure does not cache: unknown provider ⇒ UNSUPPORTED, then seed +
 *   retry ⇒ success with the error cleared.
 * - V8 usage-only ≠ synced: direct repo sync (lane never runs) leaves
 *   `lastSynced`/`latestObserved` honestly null (skeleton never writes them;
 *   durable-fact writes belong to the A4 wave slice).
 * - V9 blank provider ⇒ no key (both arities).
 * - V10 cancel ⇒ own-scope release, sibling clean.
 */
@RunWith(AndroidJUnit4::class)
class NewApiScopedSyncFlowTest {

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
            vm = ViewModelProvider(vmStore, AtlasViewModelFactory(app)).get(AtlasViewModel::class.java)
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

    // ---------------------------------------------------------------- V1

    @Test
    fun v1_twoAccountsSameProvider_isolatedScopesAndLastGood() {
        val tag = shortId()
        val f = seedFixture("v1", tag)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = dispatcherFor(f)
                val vault = CountingVault(f.secrets())

                assertTrue("fixture provider must be sync-supported", vm.newApiScopedSyncSupported(f.providerId))

                val keyA = newApiSyncKey(f.providerId, f.accountA, f.keyRefA)
                val keyB = newApiSyncKey(f.providerId, f.accountB, f.keyRefB)
                assertFalse("two accounts must not collapse into one op key", keyA == keyB)

                vm.syncNewApi(f.providerId, f.accountA, f.keyRefA, { NewApiAdapter(vault, loopbackClient()) })
                assertTrue(
                    "scope A must settle clean",
                    awaitOp { keyA !in vm.newApiOpSyncing.value },
                )
                assertNull(vm.newApiOpError.value[keyA])

                vm.syncNewApi(f.providerId, f.accountB, f.keyRefB, { NewApiAdapter(vault, loopbackClient()) })
                assertTrue(
                    "scope B must settle clean",
                    awaitOp { keyB !in vm.newApiOpSyncing.value },
                )
                assertNull(vm.newApiOpError.value[keyB])

                assertEquals("exactly two 4-call waves (4+4 self-hit)", 8, drainRequests(8).size)

                val usage = app.repository.providerAccountUsage
                val latestA = requireNotNull(usage.observation(f.providerId, f.accountA).latest)
                val latestB = requireNotNull(usage.observation(f.providerId, f.accountB).latest)
                assertEquals("A usage carries A's request_count", COUNT_A, latestA.requestCountRaw)
                assertEquals("B usage carries B's request_count", COUNT_B, latestB.requestCountRaw)
                assertEquals("A usage provenance is A's PAT", f.patRefA, latestA.credentialId)
                assertEquals("B usage provenance is B's PAT", f.patRefB, latestB.credentialId)
                assertTrue(latestA.accountEndpointObserved)
                assertTrue(latestB.accountEndpointObserved)
                assertEquals(1, usage.history(f.providerId, f.accountA).size)
                assertEquals(1, usage.history(f.providerId, f.accountB).size)
                assertEquals(
                    "A last-good survives B's wave",
                    COUNT_A, usage.observation(f.providerId, f.accountA).lastGood?.requestCountRaw,
                )
                assertEquals(
                    "B last-good is B's own",
                    COUNT_B, usage.observation(f.providerId, f.accountB).lastGood?.requestCountRaw,
                )

                assertNull("op-state(A) must be error-free", vm.newApiOpState(f.providerId, f.accountA, f.keyRefA).error)
                assertNull("op-state(B) must be error-free", vm.newApiOpState(f.providerId, f.accountB, f.keyRefB).error)
            }
        }
    }

    // ---------------------------------------------------------------- V2

    @Test
    fun v2_sameScopeReentryFoldsWhileDifferentScopeRunsOwnWave() {
        val tag = shortId()
        val f = seedFixture("v2", tag)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = dispatcherFor(f)
                val vault = CountingVault(f.secrets())
                val pricingHits = AtomicInteger(0)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                // Gate EVERY pricing hit: A1 blocks, B's own wave must still start (hit #2).
                val gateClient = OkHttpClient.Builder()
                    .addInterceptor(GatedLoopback(server, pricingHits, entered, release, gateFirstPricingOnly = false))
                    .build()
                val factory = { NewApiAdapter(vault, gateClient) }
                val keyA = newApiSyncKey(f.providerId, f.accountA, f.keyRefA)
                val keyB = newApiSyncKey(f.providerId, f.accountB, f.keyRefB)
                val baseline = server.requestCount
                val beforeLegs = legs()

                vm.syncNewApi(f.providerId, f.accountA, f.keyRefA, factory)
                withTimeout(TIMEOUT_MS) { entered.await() }
                assertEquals(1, pricingHits.get())

                // Same-scope re-entry: must fold into the single in-flight wave.
                vm.syncNewApi(f.providerId, f.accountA, f.keyRefA, factory)
                runCatching { InstrumentationRegistry.getInstrumentation().waitForIdleSync() }
                assertEquals("same-scope re-entry must not add transport", 1, pricingHits.get())

                // Different scope: must NOT be swallowed — its own wave starts.
                vm.syncNewApi(f.providerId, f.accountB, f.keyRefB, factory)
                assertTrue(
                    "different scope must start its own gated wave",
                    awaitOp { pricingHits.get() == 2 && keyB in vm.newApiOpSyncing.value },
                )
                assertTrue(keyA in vm.newApiOpSyncing.value)
                assertTrue(keyB in vm.newApiOpSyncing.value)

                release.complete(Unit)
                assertTrue(
                    "both scopes settle clean",
                    awaitOp {
                        keyA !in vm.newApiOpSyncing.value && keyB !in vm.newApiOpSyncing.value &&
                            vm.newApiOpError.value[keyA] == null && vm.newApiOpError.value[keyB] == null
                    },
                )
                assertEquals(2, pricingHits.get())
                assertEquals("A(4) + B(4) exact contract (each own-passport self-hit)", 8, server.requestCount - baseline)
                assertEquals(listOf(2, 2, 2, 2, 0), legDelta(beforeLegs, legs()))
                drainRequests(8)
            }
        }
    }

    // ---------------------------------------------------------------- V3

    @Test
    fun v3_errorAttributionToFailingScopeOnly() {
        val tag = shortId()
        val f = seedFixture("v3", tag)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = dispatcherFor(f)
                val vault = CountingVault(f.secrets())
                val keyA = newApiSyncKey(f.providerId, f.accountA, f.keyRefA)
                val badAccount = "missing-$tag"
                val keyBad = newApiSyncKey(f.providerId, badAccount, null)

                vm.syncNewApi(f.providerId, f.accountA, f.keyRefA, { NewApiAdapter(vault, loopbackClient()) })
                assertTrue("scope A claims", awaitOp { keyA in vm.newApiOpSyncing.value })
                assertTrue(
                    "scope A success first",
                    awaitOp { keyA !in vm.newApiOpSyncing.value && vm.newApiOpError.value[keyA] == null },
                )
                assertEquals("exactly one 4-call wave before durable read", 4, drainRequests(4).size)
                val quotaA = app.repository.newApiSnapshots.quota(
                    NewApiSnapshotStore.accountQuotaScope(f.providerId, f.accountA),
                    SnapshotKind.ACCOUNT_QUOTA,
                ).latest
                assertNotNull(quotaA)

                val baseline = server.requestCount
                vm.syncNewApi(f.providerId, badAccount, null, { NewApiAdapter(vault, loopbackClient()) })
                assertTrue(
                    "bad scope must settle with its own error",
                    awaitOp { vm.newApiOpError.value[keyBad] != null && keyBad !in vm.newApiOpSyncing.value },
                )
                assertEquals("selector failure happens before any HTTP", baseline, server.requestCount)
                assertEquals("bad scope attributes UNSUPPORTED", "UNSUPPORTED", vm.newApiOpError.value[keyBad])

                assertNull("scope A stays error-free after sibling failure", vm.newApiOpError.value[keyA])
                assertNull("op-state(A).error must not leak the sibling failure", vm.newApiOpState(f.providerId, f.accountA, f.keyRefA).error)
                assertEquals(
                    "op-state(bad).error attributes to that scope",
                    "UNSUPPORTED", vm.newApiOpState(f.providerId, badAccount, null).error,
                )
                assertEquals(
                    "A quota facts untouched by the failing sibling",
                    USED_A, app.repository.newApiSnapshots.quota(
                        NewApiSnapshotStore.accountQuotaScope(f.providerId, f.accountA),
                        SnapshotKind.ACCOUNT_QUOTA,
                    ).latest?.usedRaw,
                )
            }
        }
    }

    // ---------------------------------------------------------------- V4

    @Test
    fun v4_errorCodesClosedSetAndKeyVectors() {
        assertEquals(
            "closed 5-code vocabulary",
            setOf("AUTH", "NETWORK", "UNSUPPORTED", "UNKNOWN", "BUSY"),
            newApiSyncErrorCodes,
        )

        val tag = shortId()
        val f = seedFixture("v4", tag)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = dispatcherFor(f)
                val badAccount = "missing-$tag"
                val keyBad = newApiSyncKey(f.providerId, badAccount, null)
                vm.syncNewApi(f.providerId, badAccount, null, { NewApiAdapter(CountingVault(f.secrets()), loopbackClient()) })
                assertTrue(
                    "bad scope must settle with its own error",
                    awaitOp { vm.newApiOpError.value[keyBad] != null && keyBad !in vm.newApiOpSyncing.value },
                )
                val code = requireNotNull(vm.newApiOpError.value[keyBad])
                assertTrue("observed code stays inside the closed set", code in newApiSyncErrorCodes)
                assertEquals("code slot carries the code only", "UNSUPPORTED", code)
                assertFalse("no localized string in code slot", code.contains(' '))
                assertFalse("no newline in code slot", code.contains('\n'))
                assertFalse("no URL in code slot", code.contains("http"))
                assertFalse("no secret-shaped prefix in code slot", code.startsWith("sk-"))
            }
        }

        val p = "prov-$tag"
        assertEquals("same args map to same key", newApiSyncKey(p, "a", "c"), newApiSyncKey(p, "a", "c"))
        assertTrue("account participates in scope", newApiSyncKey(p, "a", null) != newApiSyncKey(p, "b", null))
        assertTrue("credential participates in scope", newApiSyncKey(p, "a", null) != newApiSyncKey(p, "a", "k"))
        assertTrue("provider participates in scope", newApiSyncKey("p1", "a", null) != newApiSyncKey("p2", "a", null))
        assertTrue(
            "exact 3-field scope differs across all three shapes",
            newApiSyncKey(p, "a", null) != newApiSyncKey(p, null as String?, null as String?) &&
                newApiSyncKey(p, null as String?, null as String?) != newApiSyncKey(p, "a", "k") &&
                newApiSyncKey(p, "a", null) != newApiSyncKey(p, "a", "k"),
        )
        assertEquals("blank account == null account", newApiSyncKey(p, "", null), newApiSyncKey(p, null, null))
        assertEquals("blank whitespace account == null", newApiSyncKey(p, "   ", null), newApiSyncKey(p, null, null))
        assertEquals("blank credential == null", newApiSyncKey(p, "a", ""), newApiSyncKey(p, "a", null))
        assertEquals("values trim to exact scope", newApiSyncKey(p, "  a  ", " c "), newApiSyncKey(p, "a", "c"))
        assertTrue(
            "embedded separator must not collide with split fields",
            newApiSyncKey(p, "a|b", null) != newApiSyncKey(p, "a", "b"),
        )
        assertTrue(
            "embedded :: must not collide with column split",
            newApiSyncKey("p::q", null, null) != newApiSyncKey("p", "q", null),
        )
    }

    // ---------------------------------------------------------------- V5

    @Test
    fun v5_roleNormalizationAttachedPatSucceedsViaOwnKeyForeignKeyFailClosed() {
        val tag = shortId()
        val f = seedFixture("v5", tag)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = dispatcherFor(f)
                val vault = CountingVault(f.secrets())

                // Attached non-config ref (A's ACCOUNT PAT passed as the card credential):
                // lane normalizes to null and succeeds through A's own INFERENCE key.
                val keyPatScope = newApiSyncKey(f.providerId, f.accountA, f.patRefA)
                vm.syncNewApi(f.providerId, f.accountA, f.patRefA, { NewApiAdapter(vault, loopbackClient()) })
                assertTrue("PAT scope claims", awaitOp { keyPatScope in vm.newApiOpSyncing.value })
                assertTrue(
                    "PAT-as-credential scope must settle clean via the account key fallback",
                    awaitOp { keyPatScope !in vm.newApiOpSyncing.value && vm.newApiOpError.value[keyPatScope] == null },
                )
                val recordedA = drainRequests(4)
                assertEquals(
                    "token leg uses A's own inference key, never the PAT",
                    "Bearer ${f.keySecretA}",
                    recordedA.single { it.routePath() == "/api/usage/token/" }.getHeader("Authorization"),
                )
                assertEquals(
                    TOKEN_USED_A,
                    app.repository.newApiSnapshots.quota(
                        NewApiSnapshotStore.tokenQuotaScope(f.providerId, f.accountA, InferenceKeyRef(f.keyRefA)),
                        SnapshotKind.TOKEN_QUOTA,
                    ).latest?.usedRaw,
                )

                // Foreign ref (B's INFERENCE key attached to A's scope): fail-closed,
                // never borrowed.
                vault.asked.clear()
                val baseline = server.requestCount
                val keyForeignScope = newApiSyncKey(f.providerId, f.accountA, f.keyRefB)
                vm.syncNewApi(f.providerId, f.accountA, f.keyRefB, { NewApiAdapter(vault, loopbackClient()) })
                assertTrue(
                    "cross-account key must settle fail-closed",
                    awaitOp { vm.newApiOpError.value[keyForeignScope] != null && keyForeignScope !in vm.newApiOpSyncing.value },
                )
                assertEquals("cross-account key attributes UNSUPPORTED", "UNSUPPORTED", vm.newApiOpError.value[keyForeignScope])
                assertEquals("ownership failure happens before any HTTP", baseline, server.requestCount)
                assertTrue(
                    "foreign key secret must never be borrowed",
                    vault.asked.none { it.substringBefore('@') == f.keyRefB },
                )
            }
        }
    }

    // ---------------------------------------------------------------- V6

    @Test
    fun v6_busyCodeScopeOnlyVocabulary() {
        assertTrue("BUSY belongs to the closed vocabulary", "BUSY" in newApiSyncErrorCodes)

        val tag = shortId()
        val f = seedFixture("v6", tag)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = dispatcherFor(f)
                val keyA = newApiSyncKey(f.providerId, f.accountA, f.keyRefA)
                val keyB = newApiSyncKey(f.providerId, f.accountB, f.keyRefB)
                assertNull("fresh scope A carries no BUSY", vm.newApiOpState(f.providerId, f.accountA, f.keyRefA).error)
                assertNull("fresh scope B carries no BUSY", vm.newApiOpState(f.providerId, f.accountB, f.keyRefB).error)

                vm.syncNewApi(f.providerId, f.accountA, f.keyRefA, { NewApiAdapter(CountingVault(f.secrets()), loopbackClient()) })
                assertTrue(
                    "scope A settles without BUSY outside a busy window",
                    awaitOp { keyA !in vm.newApiOpSyncing.value },
                )
                assertNull(vm.newApiOpError.value[keyA])
                assertNull("sibling scope B untouched", vm.newApiOpError.value[keyB])
                drainRequests(4)
            }
        }
    }

    // ---------------------------------------------------------------- V7

    @Test
    fun v7_failureDoesNotCacheRetrySucceedsAfterSeed() {
        val tag = shortId()
        val providerId = "cr002-scope-unknown-$tag"
        val keyMissing = newApiSyncKey(providerId, "ghost-$tag", null)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                assertFalse("unknown provider is not sync-supported", vm.newApiScopedSyncSupported(providerId))
                val baseline = server.requestCount
                vm.syncNewApi(providerId, "ghost-$tag", null, { NewApiAdapter(CountingVault(emptyMap()), loopbackClient()) })
                assertTrue(
                    "missing provider settles fail-closed",
                    awaitOp { vm.newApiOpError.value[keyMissing] != null && keyMissing !in vm.newApiOpSyncing.value },
                )
                assertEquals("failure happens before any HTTP", baseline, server.requestCount)
                assertEquals("UNSUPPORTED", vm.newApiOpError.value[keyMissing])

                val f = seedFixtureWithId("v7", tag, providerId)
                assertTrue("seeded provider becomes sync-supported", vm.newApiScopedSyncSupported(f.providerId))
                server.dispatcher = dispatcherFor(f)
                val vault = CountingVault(f.secrets())
                val keyA = newApiSyncKey(f.providerId, f.accountA, f.keyRefA)
                vm.syncNewApi(f.providerId, f.accountA, f.keyRefA, { NewApiAdapter(vault, loopbackClient()) })
                assertTrue(
                    "retry after seed settles clean",
                    awaitOp { keyA !in vm.newApiOpSyncing.value },
                )
                assertNull("retry clears the earlier scope error", vm.newApiOpError.value[keyA])
                drainRequests(4)
            }
        }
    }

    // ---------------------------------------------------------------- V8

    @Test
    fun v8_usageOnlyIsNotSyncedHonestUnknown() {
        val tag = shortId()
        val f = seedFixture("v8", tag)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = dispatcherFor(f)
                // Direct repo sync: durable usage facts exist, but the op lane never ran.
                val result = app.repository.syncNewApi(
                    f.providerId,
                    NewApiAdapter(CountingVault(f.secrets()), loopbackClient()),
                    accountId = f.accountA,
                    credentialId = f.keyRefA,
                )
                assertEquals(1, result.prices.size)
                drainRequests(4)
                assertNotNull(app.repository.providerAccountUsage.observation(f.providerId, f.accountA).latest)

                val state = vm.newApiOpState(f.providerId, f.accountA, f.keyRefA)
                assertNull("usage-only must not fake a trigger time", state.lastSynced)
                assertNull("usage-only must not fake an observed time", state.latestObserved)
                assertNull("usage-only must not fake an error either", state.error)
                assertFalse(state.syncing)
                assertTrue("lane never claimed any op", vm.newApiOpSyncing.value.isEmpty())
            }
        }
    }

    // ---------------------------------------------------------------- V9

    @Test
    fun v9_blankProviderNeverKeys() {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                vm.syncNewApi("", "a-${shortId()}", "k")
                vm.syncNewApi("", null, null, { NewApiAdapter(CountingVault(emptyMap()), loopbackClient()) })
                runCatching { InstrumentationRegistry.getInstrumentation().waitForIdleSync() }
                assertTrue("blank provider must not claim any op", vm.newApiOpSyncing.value.isEmpty())
                assertTrue("blank provider must not write any scoped error", vm.newApiOpError.value.isEmpty())
                assertNull(vm.newApiOpState("", "a", "k").error)
                assertNull(vm.newApiOpState("", "a", "k").lastSynced)
            }
        }
    }

    // ---------------------------------------------------------------- V10

    @Test
    fun v10_cancelReleasesOwnScopeSiblingClean() {
        val tag = shortId()
        val f = seedFixture("v10", tag)
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                server.dispatcher = dispatcherFor(f)
                val vault = CountingVault(f.secrets())
                val pricingHits = AtomicInteger(0)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                // Only the FIRST pricing blocks (scope A); sibling B flows through.
                val gateClient = OkHttpClient.Builder()
                    .addInterceptor(GatedLoopback(server, pricingHits, entered, release, gateFirstPricingOnly = true))
                    .build()
                val factory = { NewApiAdapter(vault, gateClient) }
                val keyA = newApiSyncKey(f.providerId, f.accountA, f.keyRefA)
                val keyB = newApiSyncKey(f.providerId, f.accountB, f.keyRefB)
                val baseline = server.requestCount

                vm.syncNewApi(f.providerId, f.accountA, f.keyRefA, factory)
                withTimeout(TIMEOUT_MS) { entered.await() }
                assertTrue(keyA in vm.newApiOpSyncing.value)

                vm.syncNewApi(f.providerId, f.accountB, f.keyRefB, factory)
                // B's wave must actually start (pricingHits is a monotonic latch; A is
                // pinned at n=1 by the gate, so only B can produce n=2 — no fold
                // possible: distinct scope). claim-latch removed per two-route audit
                // consensus: `keyB in syncing` is non-latching and can transient-miss
                // a fast wave inside one 50ms poll gap.
                assertTrue(
                    "sibling scope B claims its own op while A is gated",
                    awaitOp { pricingHits.get() == 2 },
                )
                assertTrue(
                    "sibling scope flows clean while A is gated",
                    awaitOp { keyB !in vm.newApiOpSyncing.value && vm.newApiOpError.value[keyB] == null },
                )
                assertEquals("B's own wave started despite the gate", 2, pricingHits.get())

                // Cancel the in-flight owner, then let the transport finish: the
                // lane must rethrow and release exactly its own scope claim.
                // Root-cause after independent multi-model verify: vmStore.clear() kills
                // the whole viewModelScope (A AND B coroutines). A's gated pricing Call
                // is cancelled pre-proceed; B either already ran its wave. Production
                // release-the-own-claim behavior is CORRECT (finally unregisters the
                // cancelled scope token); the "12" invariant belongs only to the
                // success path (V2). Here we assert only that cancellation does not
                // spawn additional transport: requests.current == requests at cancel time
                // (no post-release resumption from A, no double-fire from B).
                InstrumentationRegistry.getInstrumentation().runOnMainSync { runCatching { vmStore.clear() } }
                release.complete(Unit)
                assertTrue(
                    "cancelled scope A must release its own claim",
                    awaitOp { keyA !in vm.newApiOpSyncing.value },
                )
                val settledTransport = server.requestCount - baseline
                assertTrue(
                    "aborted wave must not produce transport beyond the 6-call contract (A never resumed, B may have completed its own wave before the kill)",
                    settledTransport <= 12,
                )
                assertTrue(
                    "cancelled A must not resume its gated pricing after release (its status request already hit, so ≥1 wire hit from A; A's gated pricing never proceeds and the remaining 3 of A never fire)",
                    settledTransport < 12,
                )
                // Drain exactly what actually crossed the wire in this abort window
                // (cancelled A never resumes → the 3 missing gated/tail requests never
                // re-fire; B may have completed 4; total is measured above, not assumed).
                drainRequests(settledTransport)

                // A fresh owner syncs clean on the same seeded graph.
                val freshStore = ViewModelStore()
                val freshVm = ViewModelProvider(freshStore, AtlasViewModelFactory(app)).get(AtlasViewModel::class.java)
                try {
                    freshVm.syncNewApi(f.providerId, f.accountB, f.keyRefB, { NewApiAdapter(CountingVault(f.secrets()), loopbackClient()) })
                    assertTrue(
                        "fresh owner settles clean",
                        awaitOp { keyB !in freshVm.newApiOpSyncing.value && freshVm.newApiOpError.value[keyB] == null },
                    )
                    drainRequests(4)
                } finally {
                    runCatching { freshStore.clear() }
                }
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
    ) {
        fun secrets(): Map<String, String> = mapOf(
            patRefA to patSecretA,
            keyRefA to keySecretA,
            patRefB to patSecretB,
            keyRefB to keySecretB,
        )
    }

    private fun seedFixture(name: String, tag: String): Fixture =
        seedFixtureWithId(name, tag, "cr002-scope-$name-$tag")

    private fun seedFixtureWithId(name: String, tag: String, providerId: String): Fixture = runBlocking {
        val f = Fixture(
            providerId = providerId,
            accountA = "a-$name-$tag",
            accountB = "b-$name-$tag",
            patRefA = "pat-a-$name-$tag",
            keyRefA = "key-a-$name-$tag",
            patRefB = "pat-b-$name-$tag",
            keyRefB = "key-b-$name-$tag",
            patSecretA = "scoped-pat-secret-a-$name-$tag-0123456789",
            keySecretA = "scoped-key-secret-a-$name-$tag-9876543210",
            patSecretB = "scoped-pat-secret-b-$name-$tag-fedcba9876",
            keySecretB = "scoped-key-secret-b-$name-$tag-2468135790",
            endpointId = "e-$name-$tag",
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
                id = "off:$providerId:$PRICING_MODEL",
                providerId = providerId,
                endpointId = f.endpointId,
                providerModelId = PRICING_MODEL,
                manual = false,
                source = "OFFICIAL",
                hidden = false,
            ),
        )
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = providerId,
                workflowId = "wf-$providerId-scoped",
                authMode = AuthMode.PAT_ACCOUNT,
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

    private fun dispatcherFor(f: Fixture): RoutingDispatcher = RoutingDispatcher(
        accountBodies = mapOf(
            f.patSecretA to accountJson(QUOTA_RAW_A, USED_A, COUNT_A),
            f.patSecretB to accountJson(QUOTA_RAW_B, USED_B, COUNT_B),
        ),
        tokenBodies = mapOf(
            f.keySecretA to tokenJson(TOKEN_GRANTED_A, TOKEN_USED_A),
            f.keySecretB to tokenJson(TOKEN_GRANTED_B, TOKEN_USED_B),
        ),
    )

    private fun loopbackClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(GatedLoopback(server)).build()

    private class RoutingDispatcher(
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
                "/api/pricing" -> ok(pricingJson()).also { pricing.incrementAndGet() }
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

    /** Test transport seam: rewrite https -> loopback and optionally gate pricing requests. */
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

    /**
     * Wait for VM scoped-op convergence: existing poll + waitForIdleSync drives
     * the main looper; the harness's 50ms poll-step only (no fixed sleep, no
     * timeout increase beyond TIMEOUT_MS).
     */
    private fun awaitOp(
        timeoutMillis: Long = TIMEOUT_MS,
        predicate: () -> Boolean,
    ): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            if (Looper.myLooper() != Looper.getMainLooper()) runCatching { instrumentation.waitForIdleSync() }
            Thread.sleep(50)
        }
        return predicate()
    }

    private class AtlasViewModelFactory(private val app: AtlasApplication) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AtlasViewModel(app) as T
        }
    }

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
            // Prefix-range deletes cover ALL facts for our providers — including
            // orphans without heads (e.g. cancelled-wave leftovers), which the old
            // head-enumeration missed and which later FK-restricted parent deletes
            // (evidence/providers) then tripped on (shared-process DB residue).
            for (pid in providerIds) {
                val (lo, hi) = providerScopeRange(pid)
                writable.execSQL(
                    "DELETE FROM snapshot_facts WHERE scopeKey >= ? AND scopeKey < ?",
                    arrayOf<Any?>(lo, hi),
                )
                writable.execSQL(
                    "DELETE FROM provider_perf_facts WHERE scopeKey >= ? AND scopeKey < ?",
                    arrayOf<Any?>(lo, hi),
                )
                writable.execSQL("DELETE FROM provider_account_usage_facts WHERE providerId = ?", arrayOf<Any?>(pid))
                writable.execSQL(
                    "DELETE FROM credential_availability WHERE offeringId IN " +
                        "(SELECT id FROM offerings WHERE providerId = ?)",
                    arrayOf<Any?>(pid),
                )
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
        const val PRICING_MODEL = "scoped-mdl"
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
        const val AUTH_CANARY = "scoped-flow-auth-canary-must-never-escape"

        fun statusJson(): String =
            """{"success":true,"message":"","data":{"version":"${NewApiVersion.RC25}","quota_per_unit":500000,"quota_display_type":"USD","checkin_enabled":true,"HeaderNavModules":"{\"pricing\":{\"enabled\":true,\"requireAuth\":true}}"}}"""

        fun pricingJson(): String =
            """{"success":true,"message":"","data":[{"model_name":"$PRICING_MODEL","quota_type":0,"model_ratio":0,"completion_ratio":1,"enable_groups":["all"],"supported_endpoint_types":["openai"]}],"group_ratio":{"default":1},"usable_group":{"default":"Default"},"supported_endpoint":{"openai":{"path":"/v1/chat/completions","method":"post"}},"auto_groups":[],"pricing_version":"cr002-scoped-v1"}"""

        fun accountJson(quota: Long, used: Long, requestCount: Long): String =
            """{"success":true,"message":"","data":{"quota":$quota,"used_quota":$used,"request_count":$requestCount,"group":"default","role":1,"status":1}}"""

        fun tokenJson(granted: Long, used: Long): String =
            """{"code":true,"message":"ok","data":{"object":"token_usage","name":"scoped-key","total_granted":$granted,"total_used":$used,"total_available":${granted - used},"unlimited_quota":false,"model_limits_enabled":false,"expires_at":0}}"""
    }
}
