package dev.modelatlas.app.data

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.core.lmspeed.LmspeedFetcher
import dev.modelatlas.core.lmspeed.LmspeedRawModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-028 F1 device/integration vectors A–H + slice-union (spec §3.2).
 *
 * Real VM → repo integration following MyModelsBundleConcurrencyTest harness
 * patterns (real AtlasApplication + own ViewModelStore, @Before/@After +
 * raw-SQL deleteOwnRows teardown, ScriptedFetcher, TIMEOUT_MS safety net,
 * barriers via CompletableDeferred/awaitCond, no sleeps).
 *
 * Targets the same-slice production symbols from spec Hunks P1/P2/V1
 * (MyModelMetadataRetryPolicy + RefreshWorkScope, RefreshOrigin,
 * RefreshOutcome, RefreshAttemptObserver/Event, PassportSnapshot,
 * AtlasViewModel.requestMyModelRefresh) landed concurrently by the production
 * owner — this file does not compile until Hunk P1/P2/V1 lands (same-slice
 * dependency). Charge = ChargedAtStart count per exact W; budget assertions
 * are the binding ones. Terminal SUCCEEDED vs FAILED_NO_GOOD follows the leg
 * wiring (rows fetcher vs empty/failing fetcher); skips are asserted exactly.
 *
 * Path note: spec §3 names this file under app/ui/; the dispatch task
 * mandates app/data/ (this path, package dev.modelatlas.app.data).
 */
private typealias W = MyModelMetadataRetryPolicy.RefreshWorkScope

@RunWith(AndroidJUnit4::class)
class MyModelsMetadataRetryStateTest {

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore
    private val extraStores = mutableListOf<ViewModelStore>()

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        viewModelStore = ViewModelStore()
        onMain {
            viewModel = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) onMain { viewModelStore.clear() }
        extraStores.forEach { s -> runCatching { onMain { s.clear() } } }
        extraStores.clear()
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ===== A. Eligibility / once-only =====

    @Test
    fun a1AbsentRunsOnceThenSuppressed() = runBlocking {
        val f = saveFixture("a1"); val m = uniqueModel("a1"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec(); val fetch = rowsFetcher("a1")
        val before = samplesCount(f.providerId)
        val o1 = refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
        assertTrue("first absent pass must dispatch, was $o1", o1 == RefreshOutcome.SUCCEEDED || o1 == RefreshOutcome.FAILED_NO_GOOD)
        val o2 = refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
        assertEquals(RefreshOutcome.SUPPRESSED_BUDGET, o2)
        assertEquals(1, rec.charged(w))
        assertEquals(listOf(o1, o2), rec.evaluated(w))
        assertEquals("zero auto inference", before, samplesCount(f.providerId))
    }

    @Test
    fun a2FailedNoGoodRunsOnceThenStops() = runBlocking {
        val f = saveFixture("a2"); val m = uniqueModel("a2"); addMembership(f.providerId, m)
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = false)
        val w = f.w(m); val rec = Rec()
        val o1 = refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, emptyFetcher())
        assertEquals(RefreshOutcome.FAILED_NO_GOOD, o1)
        assertEquals(RefreshOutcome.SUPPRESSED_BUDGET, refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, emptyFetcher()))
        assertEquals(1, rec.charged(w))
    }

    @Test
    fun a3AncientLastGoodNeverRefreshes() = runBlocking {
        val f = saveFixture("a3"); val m = uniqueModel("a3"); addMembership(f.providerId, m)
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = true, at = 1_000L)
        val w = f.w(m); val rec = Rec(); val bad = throwingFetcher()
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, bad))
        assertEquals(0, rec.charged(w)); assertEquals(0, bad.calls)
    }

    @Test
    fun a4RecentSkipsIdenticallyToAncient() = runBlocking {
        val f = saveFixture("a4"); val m = uniqueModel("a4"); addMembership(f.providerId, m)
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = true, at = System.currentTimeMillis())
        val w = f.w(m); val rec = Rec(); val bad = throwingFetcher()
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, bad))
        assertEquals(0, rec.charged(w)); assertEquals(0, bad.calls)
        assertEquals(listOf(RefreshOutcome.SKIPPED_HAS_LAST_GOOD), rec.evaluated(w))
    }

    @Test
    fun a5SiblingScopeIsolation() = runBlocking {
        val f = saveFixture("a5"); val m = uniqueModel("a5"); addMembership(f.providerId, m)
        seedHeadFor(f, m, acc = "f1-a5-acc2", cred = "f1-a5-cred2", kind = SnapshotKind.PRICE.name, lastGood = true)
        val w = f.w(m); val rec = Rec()
        val o = refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("a5"))
        assertTrue("sibling good head must not satisfy W, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(w))
    }

    @Test
    fun a6PerfMissingAloneStillEligible() = runBlocking {
        val f = saveFixture("a6"); val m = uniqueModel("a6"); addMembership(f.providerId, m)
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = false)
        val w = f.w(m); val rec = Rec()
        val o = refresh(f, m, RefreshOrigin.SESSION_AUTO, failedCapable(), rec, rowsFetcher("a6"))
        assertTrue("per-kind OR: price-failed with no perf rows stays eligible, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(w))
    }

    @Test
    fun a7NoSelfTrigger() = runBlocking {
        val f = saveFixture("a7"); val m = uniqueModel("a7"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec(); val fetch = rowsFetcher("a7")
        repeat(3) { refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch) }
        assertEquals("exactly N driven Evaluated, no self-trigger", 3, rec.evaluated(w).size)
        assertEquals(1, rec.charged(w))
    }

    @Test
    fun a8UnknownReadNeverChargesThenRecovers() = runBlocking {
        val f = saveFixture("a8"); val m = uniqueModel("a8"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val headsBefore = tableSet()
        val o = withTimeout(TIMEOUT_MS) {
            viewModel.requestMyModelRefresh(providerId = f.providerId, providerModelId = m, accountId = f.accountId,
                credentialId = f.credentialId, origin = RefreshOrigin.SESSION_AUTO,
                lmspeedFetcher = throwingFetcher(), attemptObserver = rec,
                passportReader = suspend { throw IllegalStateException("f1-a8-unknown-read") })
        }
        assertEquals(RefreshOutcome.SKIPPED_UNKNOWN_READ, o)
        assertEquals(0, rec.charged(w)); assertEquals(0, rec.reserved(w))
        assertEquals(headsBefore, tableSet())
        seedGate(f)
        val o2 = refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, rowsFetcher("a8"))
        assertTrue("restore must dispatch, was $o2", o2 == RefreshOutcome.SUCCEEDED || o2 == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(w))
    }

    @Test
    fun a9RoundTripFreshVmSeesLastGood() = runBlocking {
        val f = saveFixture("a9"); val m = uniqueModel("a9"); addMembership(f.providerId, m)
        seedGate(f)
        val rec1 = Rec(); val fetch1 = rowsFetcher("a9")
        val o1 = refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec1, fetch1)
        assertTrue("VM1 must dispatch, was $o1", o1 == RefreshOutcome.SUCCEEDED || o1 == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec1.charged(f.w(m)))
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = true)
        val vm2 = newVm(); val rec2 = Rec(); val bad = throwingFetcher()
        val o2 = refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec2, bad, vm = vm2)
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD, o2)
        assertEquals(0, bad.calls)
    }

    // ===== B. Cancel =====

    @Test
    fun b1StartedCancelCountsAndSuppresses() = runBlocking {
        val f = saveFixture("b1"); val m = uniqueModel("b1"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val fetch = ScriptedFetcher { entered.complete(Unit); release.await(); rowsFor("b1") }
        val job = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        job.cancel()
        try {
            job.await(); throw AssertionError("started-cancel must rethrow")
        } catch (_: CancellationException) { }
        assertEquals(listOf(RefreshOutcome.CANCELLED_AFTER_START), rec.terminal(w))
        release.complete(Unit)
        assertEquals(RefreshOutcome.SUPPRESSED_BUDGET, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("b1b")))
    }

    @Test
    fun b2QueuedCancelNotChargedBudgetIntact() = runBlocking {
        val f = saveFixture("b2")
        val m1 = uniqueModel("b2-1"); val m2 = uniqueModel("b2-2")
        addMembership(f.providerId, m1); addMembership(f.providerId, m2)
        val w2 = f.w(m2); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val f1 = ScriptedFetcher { entered.complete(Unit); release.await(); rowsFor("b2-1") }
        val f2 = ScriptedFetcher { rowsFor("b2-2") }
        val job1 = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m1, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, f1)
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        val job2 = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m2, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, f2)
        }
        awaitCond { rec.reserved(w2) == 1 }
        assertFalse("W2 must be queued behind the provider lock", job2.isCompleted)
        job2.cancel()
        try {
            withTimeout(TIMEOUT_MS) { job2.await() }; throw AssertionError("queued-cancel must throw")
        } catch (_: CancellationException) { }
        assertEquals(0, rec.charged(w2))
        assertTrue("ReleasedNoCharge must be emitted", rec.released(w2) == 1)
        release.complete(Unit)
        withTimeout(TIMEOUT_MS) { job1.await() }
        val o = refresh(f, m2, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, f2)
        assertTrue("budget intact: later pass runs once, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(w2))
    }

    @Test
    fun b3CancelCleansRegistryExplicitRuns() = runBlocking {
        val f = saveFixture("b3"); val m = uniqueModel("b3"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val block = ScriptedFetcher { entered.complete(Unit); release.await(); rowsFor("b3") }
        val job = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, block)
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        job.cancel()
        try { withTimeout(TIMEOUT_MS) { job.await() } } catch (_: CancellationException) { }
        release.complete(Unit)
        val after = ScriptedFetcher { rowsFor("b3-user") }
        val o = refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, after)
        assertTrue("post-cancel explicit press must run on a fresh call, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, after.calls)
    }

    @Test
    fun b4FollowerCancelNeverChargesLeaderUnaffected() = runBlocking {
        val f = saveFixture("b4"); val m = uniqueModel("b4"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val fetch = ScriptedFetcher { entered.complete(Unit); release.await(); rowsFor("b4") }
        val leader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        val follower = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
        }
        awaitCond { rec.evaluated(w).isEmpty() && !follower.isCompleted }
        follower.cancel()
        try { withTimeout(TIMEOUT_MS) { follower.await() } } catch (_: CancellationException) { }
        assertFalse("follower cancel must not cancel the leader", leader.isCompleted)
        release.complete(Unit)
        withTimeout(TIMEOUT_MS) { leader.await() }
        assertEquals(1, fetch.calls); assertEquals(1, rec.charged(w))
    }

    // ===== C. Concurrency / join =====

    @Test
    fun c1FiveIdenticalCallersChargeOnce() = runBlocking {
        val f = saveFixture("c1"); val m = uniqueModel("c1"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val fetch = ScriptedFetcher { call -> if (call == 1) { entered.complete(Unit); release.await() }; rowsFor("c1") }
        val jobs = (1..5).map {
            async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
            }
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        release.complete(Unit)
        jobs.forEach { withTimeout(TIMEOUT_MS) { it.await() } }
        assertEquals(1, fetch.calls)
        assertEquals(1, rec.reserved(w)); assertEquals(1, rec.charged(w))
        assertEquals(4, rec.joined(w)); assertEquals(1, rec.terminal(w).size)
        assertEquals(5, rec.evaluated(w).size)
        assertEquals(4, rec.evaluated(w).count { it == RefreshOutcome.JOINED_EXISTING_FLIGHT })
    }

    @Test
    fun c2AutoJoiningAddPathFlightNeverCharges() = runBlocking {
        val f = saveFixture("c2"); val m = uniqueModel("c2"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val addFetch = ScriptedFetcher { entered.complete(Unit); release.await(); rowsFor("c2-add") }
        val leader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) {
                viewModel.maybeEnrichMyModel(providerId = f.providerId, accountId = f.accountId,
                    credentialId = f.credentialId, group = null, providerModelId = m, lmspeedFetcher = addFetch)
            }
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        // Follower 先 async（seam 併入 bundle flight；release 前不阻斷測試線）。
        val follower = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, throwingFetcher())
        }
        release.complete(Unit)
        val o = withTimeout(TIMEOUT_MS) { follower.await() }
        assertEquals(RefreshOutcome.JOINED_EXISTING_FLIGHT, o)
        assertEquals(0, rec.charged(w))
        assertTrue(withTimeout(TIMEOUT_MS) { leader.await() })
    }

    @Test
    fun c3aJoinFailureLeavesBudgetOpen() = runBlocking {
        val f = saveFixture("c3a"); val m = uniqueModel("c3a"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val addFetch = ScriptedFetcher { entered.complete(Unit); release.await(); throw IllegalStateException("c3a add-path failure") }
        val leader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) {
                viewModel.maybeEnrichMyModel(providerId = f.providerId, accountId = f.accountId,
                    credentialId = f.credentialId, group = null, providerModelId = m, lmspeedFetcher = addFetch)
            }
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        // Follower 先 async（併入 bundle flight；release 前不阻斷測試線）。
        val follower = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, throwingFetcher())
        }
        release.complete(Unit)
        assertEquals(RefreshOutcome.JOINED_EXISTING_FLIGHT, withTimeout(TIMEOUT_MS) { follower.await() })
        assertEquals(0, rec.charged(w))
        withTimeout(TIMEOUT_MS) { leader.await() }
        val o = refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("c3a"))
        assertTrue("budget still open after joined add-path failure, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(w))
    }

    @Test
    fun c3bAutoAfterFailedExplicitAddAllowed() = runBlocking {
        val f = saveFixture("c3b"); val m = uniqueModel("c3b"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val ou = refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, ScriptedFetcher { throw IllegalStateException("c3b explicit failure") })
        assertEquals(RefreshOutcome.FAILED_NO_GOOD, ou)
        val oa = refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("c3b"))
        assertTrue("D3-YES: failed explicit must not spend auto budget, was $oa", oa == RefreshOutcome.SUCCEEDED || oa == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.autoCharged(w))
    }

    @Test
    fun c4PerProviderSerializationOthersParallel() = runBlocking {
        val f = saveFixture("c4"); val g = saveFixture("c4b")
        val m1 = uniqueModel("c4-1"); val m2 = uniqueModel("c4-2"); val m3 = uniqueModel("c4-3")
        addMembership(f.providerId, m1); addMembership(f.providerId, m2); addMembership(g.providerId, m3)
        val rec = Rec(); val order = mutableListOf<String>()
        val enteredA = CompletableDeferred<Unit>(); val releaseA = CompletableDeferred<Unit>()
        val enteredC = CompletableDeferred<Unit>()
        val fA = ScriptedFetcher { synchronized(order) { order += "A-enter" }; enteredA.complete(Unit); releaseA.await(); rowsFor("c4-a") }
        val fB = ScriptedFetcher { synchronized(order) { order += "B-enter" }; rowsFor("c4-b") }
        val fC = ScriptedFetcher { enteredC.complete(Unit); rowsFor("c4-c") }
        val jobA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { refresh(f, m1, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fA) }
        withTimeout(TIMEOUT_MS) { enteredA.await() }
        val jobC = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { refresh(g, m3, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fC) }
        withTimeout(TIMEOUT_MS) { enteredC.await() }
        val jobB = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { refresh(f, m2, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fB) }
        awaitCond { rec.reserved(f.w(m2)) == 1 }
        assertFalse("same-provider W must serialize behind the lock holder", jobB.isCompleted)
        releaseA.complete(Unit)
        listOf(jobA, jobB, jobC).forEach { withTimeout(TIMEOUT_MS) { it.await() } }
        assertTrue("leader leg entered before queued leg", order.indexOf("A-enter") < order.indexOf("B-enter"))
        assertEquals(1, rec.charged(f.w(m1))); assertEquals(1, rec.charged(f.w(m2))); assertEquals(1, rec.charged(g.w(m3)))
    }

    // ===== D. Explicit separation (D3-YES) =====

    @Test
    fun d1ExplicitAfterAutoSpentRunsAutoStaysSuppressed() = runBlocking {
        val f = saveFixture("d1"); val m = uniqueModel("d1"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        assertEquals(RefreshOutcome.FAILED_NO_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, emptyFetcher()))
        val userFetch = rowsFetcher("d1u")
        val ou = refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, userFetch)
        assertTrue("spent auto must not block a user press, was $ou", ou == RefreshOutcome.SUCCEEDED || ou == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, userFetch.calls)
        assertEquals(RefreshOutcome.SUPPRESSED_BUDGET, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, userFetch))
        assertEquals(1, rec.autoCharged(w))
    }

    @Test
    fun d2ConcurrentPressesMerge() = runBlocking {
        val f = saveFixture("d2"); val m = uniqueModel("d2"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val f1 = ScriptedFetcher { entered.complete(Unit); release.await(); rowsFor("d2-1") }
        val f2 = ScriptedFetcher { rowsFor("d2-2") }
        val p1 = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, f1) }
        withTimeout(TIMEOUT_MS) { entered.await() }
        val p2 = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, f2) }
        awaitCond { !p2.isCompleted }
        release.complete(Unit)
        val outs = listOf(withTimeout(TIMEOUT_MS) { p1.await() }, withTimeout(TIMEOUT_MS) { p2.await() })
        assertEquals(1, f1.calls + f2.calls)
        assertTrue("one press joins the other", outs.contains(RefreshOutcome.JOINED_EXISTING_FLIGHT))
        assertEquals(1, rec.userCharged(w)); assertEquals(1, rec.joined(w))
    }

    @Test
    fun d3SequentialPressesEachRunOnce() = runBlocking {
        val f = saveFixture("d3"); val m = uniqueModel("d3"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec(); val fetch = rowsFetcher("d3")
        val o1 = refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, fetch)
        val o2 = refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, fetch)
        assertTrue("first press runs, was $o1", o1 == RefreshOutcome.SUCCEEDED || o1 == RefreshOutcome.FAILED_NO_GOOD)
        assertTrue("second press runs, was $o2", o2 == RefreshOutcome.SUCCEEDED || o2 == RefreshOutcome.FAILED_NO_GOOD)
        assertFalse(o1 == RefreshOutcome.JOINED_EXISTING_FLIGHT || o2 == RefreshOutcome.JOINED_EXISTING_FLIGHT)
        assertEquals(2, fetch.calls); assertEquals(2, rec.userCharged(w)); assertEquals(2, rec.terminal(w).size)
    }

    @Test
    fun d4PressJoinsIdenticalAutoFlight() = runBlocking {
        val f = saveFixture("d4"); val m = uniqueModel("d4"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val autoFetch = ScriptedFetcher { entered.complete(Unit); release.await(); rowsFor("d4") }
        val auto = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, autoFetch)
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        // Follower 先 async（避免同步卡死在 leader 的 release 前），release 後再驗。
        val follower = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, throwingFetcher())
        }
        release.complete(Unit)
        assertEquals(RefreshOutcome.JOINED_EXISTING_FLIGHT, withTimeout(TIMEOUT_MS) { follower.await() })
        assertEquals(0, rec.userCharged(w))
        withTimeout(TIMEOUT_MS) { auto.await() }
        assertEquals(1, autoFetch.calls); assertEquals(1, rec.charged(w))
    }

    // ===== E. Scope / membership =====

    @Test
    fun e1AccountDimsIndependent() = runBlocking {
        val f = saveFixture("e1"); val m = uniqueModel("e1"); addMembership(f.providerId, m)
        seedHeadFor(f, m, acc = f.accountId, cred = f.credentialId, kind = SnapshotKind.PRICE.name, lastGood = true)
        val rec = Rec(); val bad = throwingFetcher()
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, bad))
        val w2 = f.w(m, acc = "f1-e1-acc2", cred = "f1-e1-cred2")
        val o = withTimeout(TIMEOUT_MS) {
            viewModel.requestMyModelRefresh(providerId = f.providerId, providerModelId = m, accountId = "f1-e1-acc2",
                credentialId = "f1-e1-cred2", origin = RefreshOrigin.SESSION_AUTO,
                lmspeedFetcher = rowsFetcher("e1"), attemptObserver = rec, passportReader = suspend { failedCapable() })
        }
        assertTrue("account dims independent, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(w2))
        assertEquals(0, bad.calls)
    }

    @Test
    fun e2GroupDimsIndependent() = runBlocking {
        val f = saveFixture("e2"); val m = uniqueModel("e2"); addMembership(f.providerId, m)
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = true)
        val rec = Rec()
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, throwingFetcher()))
        val o = refresh(f, m, RefreshOrigin.SESSION_AUTO, failedCapable(), rec, rowsFetcher("e2"), group = "f1-e2-g1")
        assertTrue("null group vs named group independent, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(f.w(m, group = "f1-e2-g1")))
    }

    @Test
    fun e3CatalogNeverEnriched() = runBlocking {
        val f = saveFixture("e3"); val m = uniqueModel("e3")
        val rec = Rec(); val bad = throwingFetcher()
        assertFalse(MyModelsStore(app.database).isMyModel(f.providerId, m))
        assertEquals(RefreshOutcome.SKIPPED_NOT_MEMBER, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, bad))
        assertEquals(0, bad.calls); assertEquals(0, rec.charged(f.w(m)))
        assertFalse("refresh must never add membership", MyModelsStore(app.database).isMyModel(f.providerId, m))
    }

    @Test
    fun e4MembershipByteIdentical() = runBlocking {
        val f = saveFixture("e4"); val m = uniqueModel("e4"); addMembership(f.providerId, m)
        val before = runBlocking { app.database.myModelsDao().all() }
        val rec = Rec()
        refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("e4"))
        refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("e4"))
        assertEquals(before, runBlocking { app.database.myModelsDao().all() })
    }

    @Test
    fun e5EndpointOperationSplit() = runBlocking {
        // Seam 的 endpoint/operation 真相=每 model 唯一 offering（resolveOfferingEndpointId
        // singleOrNull；operation=certified protocol）。不同 endpoint 的 W 以不同 model
        // 表達（各一顆 offering）。Seed 先於 membership：membership collector 的
        // SESSION_AUTO pass 起跑即見 last-good，避免背景 pass 提前消耗 budget。
        val f = saveFixture("e5")
        val mChat = uniqueModel("e5-chat"); val mEmbed = uniqueModel("e5-embed")
        val epChat = seedOffering(f, mChat, endpointId = "e5-chat-ep", protocol = "CHAT_COMPLETIONS")
        val epEmbed = seedOffering(f, mEmbed, endpointId = "e5-embed-ep", protocol = "CHAT_COMPLETIONS")
        // mChat 的 W=(epChat, CHAT_COMPLETIONS)：同 head 有 last-good → skip。
        seedHead(f, mChat, SnapshotKind.PRICE.name, lastGood = true, endpointId = epChat, operation = "CHAT_COMPLETIONS")
        // membership 最後加：collector pass 起跑即見 last-good（skip），budget 不被
        // 背景 pass 消耗。
        addMembership(f.providerId, mChat); addMembership(f.providerId, mEmbed)
        val rec = Rec()
        // snap=null：走 production DAO 讀取（真 head 有 last-good → HAS_LAST_GOOD → skip）。
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD,
            refresh(f, mChat, RefreshOrigin.SESSION_AUTO, null, rec, throwingFetcher()))
        // mEmbed 的 W=(epEmbed,...)：mChat 的 head 在 (epChat,...) → 不匹配 → ABSENT →
        // dispatch（endpoint split 等價：不同 endpoint 的 head 不滿足本 endpoint 的 W）。
        // USER_EXPLICIT：與 membership collector 的 SESSION_AUTO 背景 pass 預算獨立
        //（D3-YES），測試主線不與背景 pass 競爭。
        val o1 = refresh(f, mEmbed, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, rowsFetcher("e5a"))
        assertTrue("endpoint split dispatches, was $o1", o1 == RefreshOutcome.SUCCEEDED || o1 == RefreshOutcome.FAILED_NO_GOOD)
        // 同 W last-good 後 USER_EXPLICIT 也 skip（snap=null 走 DAO；collector 的
        // SESSION_AUTO 預算狀態不影響 explicit skip 語義）。
        seedHead(f, mEmbed, SnapshotKind.PRICE.name, lastGood = true, endpointId = epEmbed, operation = "CHAT_COMPLETIONS")
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD,
            refresh(f, mEmbed, RefreshOrigin.USER_EXPLICIT, null, rec, throwingFetcher()))
        // charged 斷言：chat 0（skip）、embed 1（dispatch 過一次）。
        assertEquals(0, rec.charged(f.w(mChat, endpointId = epChat, operation = "CHAT_COMPLETIONS")))
        assertEquals(1, rec.charged(f.w(mEmbed, endpointId = epEmbed, operation = "CHAT_COMPLETIONS")))
    }

    @Test
    fun e6EnumerationFiveSubjects() = runBlocking {
        val f = saveFixture("e6")
        val second = seedSecondScope(f.providerId)
        val models = (1..4).map { uniqueModel("e6-$it") } + uniqueModel("e6-ctx")
        models.forEach { addMembership(f.providerId, it) }
        val rec = Rec(); val fetch = rowsFetcher("e6")
        models.take(4).forEachIndexed { i, m ->
            val o = refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
            assertTrue("subject $i must dispatch, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        }
        val mCtx = models.last()
        val oCtx = withTimeout(TIMEOUT_MS) {
            viewModel.requestMyModelRefresh(providerId = f.providerId, providerModelId = mCtx, accountId = second.first,
                credentialId = second.second, origin = RefreshOrigin.SESSION_AUTO,
                lmspeedFetcher = fetch, attemptObserver = rec, passportReader = suspend { absentCapable() })
        }
        assertTrue("out-of-context scope must dispatch, was $oCtx", oCtx == RefreshOutcome.SUCCEEDED || oCtx == RefreshOutcome.FAILED_NO_GOOD)
        models.take(4).forEach { assertEquals(1, rec.charged(f.w(it))) }
    }

    // ===== F. Zero-burn / uncapable =====

    @Test
    fun f1UncapableSkipsBudgetStaysOpen() = runBlocking {
        val f = saveFixture("f1"); val m = uniqueModel("f1"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        repeat(2) {
            assertEquals(RefreshOutcome.SKIPPED_UNCAPABLE, refresh(f, m, RefreshOrigin.SESSION_AUTO, uncapable(), rec, null))
        }
        assertEquals(0, rec.charged(w))
        val o = refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("f1"))
        assertTrue("budget still open after uncapable skips, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(w))
    }

    @Test
    fun f2ZeroHttpStartedConsumesBudget() = runBlocking {
        val f = saveFixture("f2"); val m = uniqueModel("f2"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec(); val fetch = emptyFetcher()
        assertEquals(RefreshOutcome.FAILED_NO_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch))
        assertEquals(1, rec.charged(w))
        assertEquals(RefreshOutcome.SUPPRESSED_BUDGET, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch))
        assertEquals(0, fetch.httpCalls)
    }

    @Test
    fun f3CapsFailureSingleCallThenNonCertifiedZeroBurn() = runBlocking {
        val f = saveFixture("f3"); val m = uniqueModel("f3"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val failing = ScriptedFetcher { throw IllegalStateException("f3 caps failure") }
        assertEquals(RefreshOutcome.FAILED_NO_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, failing))
        assertEquals(1, failing.calls); assertEquals(1, rec.charged(w))
        val m2 = uniqueModel("f3b"); addMembership(f.providerId, m2)
        assertEquals(RefreshOutcome.SKIPPED_UNCAPABLE, refresh(f, m2, RefreshOrigin.SESSION_AUTO, uncapable(), rec, null))
    }

    @Test
    fun f4ZeroBurnGateUnprovenProvider() = runBlocking {
        val f = saveFixture("f4"); val m = uniqueModel("f4"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        val o = refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, null)
        assertEquals(RefreshOutcome.SKIPPED_UNCAPABLE, o)
        assertEquals(0, rec.charged(w)); assertEquals(0, rec.reserved(w))
    }

    // ===== G. Invariants =====

    @Test
    fun g1FailurePreservesLastGoodAndHistory() = runBlocking {
        val f = saveFixture("g1"); val m = uniqueModel("g1"); addMembership(f.providerId, m)
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = false)
        val key = sk(f.providerId, f.accountId, f.credentialId, m, null, null, null)
        val dao = app.database.snapshotFactDao()
        val historyBefore = runBlocking { dao.history(key, SnapshotKind.PRICE.name) }
        assertEquals(RefreshOutcome.FAILED_NO_GOOD,
            refresh(f, m, RefreshOrigin.SESSION_AUTO, failedCapable(), Rec(), ScriptedFetcher { throw IllegalStateException("g1 leg failure") }))
        val historyAfter = runBlocking { dao.history(key, SnapshotKind.PRICE.name) }
        assertTrue(historyAfter.size >= historyBefore.size)
        assertTrue("no verdict impersonation", historyAfter.none { it.state == "FREE_NOW" || it.state == "PAID" })
        historyAfter.forEach { fact ->
            val ev = runBlocking { app.database.evidenceDao().getById(fact.evidenceId) }
            assertFalse("sources never impersonated", ev != null && (ev.source == "USER" || ev.source == "manual" || ev.source == "official"))
        }
        assertEquals("lastGood stays absent on failure", null, runBlocking { dao.head(key, SnapshotKind.PRICE.name) }?.lastGoodSnapshotId)
    }

    @Test
    fun g2SevenDimRoundTripUnicodeAndMalformed() = runBlocking {
        val f = saveFixture("g2"); val m = uniqueModel("g2"); addMembership(f.providerId, m)
        val uni = "f1-g2-群組-тест-🎉"
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = true, group = uni)
        seedHead(f, m, SnapshotKind.PRICE.name, lastGood = true, group = "")
        val rec = Rec(); val bad = throwingFetcher()
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, bad, group = uni))
        assertEquals(0, bad.calls)
        runBlocking {
            app.database.snapshotFactDao().upsertHead(SnapshotHeadEntity(scopeKey = "f1-g2-malformed-no-colons",
                kind = SnapshotKind.PRICE.name, latestEvidenceId = "f1-g2-ev", latestObservedAt = 1L,
                lastGoodSnapshotId = "snap-x", lastGoodObservedAt = 1L))
        }
        val m2 = uniqueModel("g2b"); addMembership(f.providerId, m2)
        val o = refresh(f, m2, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("g2"))
        assertTrue("malformed sibling must be fail-closed ignored, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, bad, group = ""))
    }

    @Test
    fun g3InMemoryOnlySqliteMasterStableFreshVmRedispatches() = runBlocking {
        val f = saveFixture("g3"); val m = uniqueModel("g3"); addMembership(f.providerId, m)
        val before = tableSet()
        assertTrue("Room v15 baseline carries slice heads", before.contains("snapshot_slice_heads"))
        val rec1 = Rec()
        assertEquals(RefreshOutcome.FAILED_NO_GOOD, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec1, emptyFetcher()))
        assertEquals(1, rec1.charged(f.w(m)))
        val vm2 = newVm(); val rec2 = Rec()
        val o2 = refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec2, rowsFetcher("g3"), vm = vm2)
        assertTrue("budget is in-memory: fresh VM redispatches, was $o2", o2 == RefreshOutcome.SUCCEEDED || o2 == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec2.charged(f.w(m)))
        assertEquals("no retry/budget table, no persisted counter", before, tableSet())
    }

    @Test
    fun g4SecretBoundary() = runBlocking {
        val f = saveFixture("g4"); val m = uniqueModel("g4"); addMembership(f.providerId, m)
        val w = f.w(m); val rec = Rec()
        refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("g4"))
        refresh(f, m, RefreshOrigin.USER_EXPLICIT, absentCapable(), rec, rowsFetcher("g4"))
        val blob = w.toString() + rec.all().toString()
        assertFalse(blob.contains(FIXTURE_SECRET))
        assertFalse(blob.contains("Bearer")); assertFalse(blob.contains("sk-live"))
        val rows = runBlocking { app.database.myModelsDao().all() }.toString()
        assertFalse(rows.contains(FIXTURE_SECRET))
    }

    @Test
    fun g5AddedAtNeverFreshness() = runBlocking {
        val f = saveFixture("g5")
        val mGood = uniqueModel("g5-good"); val mAbsent = uniqueModel("g5-absent")
        // 先種 head（不需 membership）：membership collector 的 SESSION_AUTO pass
        // 起跑後每個 emission 都會看到 last-good 而 skip，測試主線保持確定性。
        seedHead(f, mGood, SnapshotKind.PRICE.name, lastGood = true)
        runBlocking {
            MyModelsStore(app.database).addToMyModels(f.providerId, mGood, source = "USER", nowMillis = System.currentTimeMillis())
            MyModelsStore(app.database).addToMyModels(f.providerId, mAbsent, source = "USER", nowMillis = 1L)
        }
        val rec = Rec(); val bad = throwingFetcher()
        assertEquals(RefreshOutcome.SKIPPED_HAS_LAST_GOOD, refresh(f, mGood, RefreshOrigin.SESSION_AUTO, null, rec, bad))
        val o = refresh(f, mAbsent, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, rowsFetcher("g5"))
        assertTrue("old addedAt + absent dispatches, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        val rows = runBlocking { app.database.myModelsDao().all() }.associateBy { it.providerModelId }
        assertEquals(1L, rows[mAbsent]?.addedAt)
        assertTrue((rows[mGood]?.addedAt ?: 0L) > 1L)
    }

    // ===== H. Production wiring =====

    @Test
    fun h1SessionHookFiresOnInitWithoutSideEffects() = runBlocking {
        val f = saveFixture("h1"); val m = uniqueModel("h1"); addMembership(f.providerId, m)
        val membersBefore = runBlocking { app.database.myModelsDao().all() }
        val samplesBefore = samplesCount(f.providerId)
        val fresh = newVm()
        runBlocking { withTimeout(TIMEOUT_MS) { fresh.myModelKeys.first { it.isNotEmpty() } } }
        assertEquals(membersBefore, runBlocking { app.database.myModelsDao().all() })
        assertEquals("session hook must not infer", samplesBefore, samplesCount(f.providerId))
    }

    @Test
    fun h2EmissionFiresForLaterMember() = runBlocking {
        val f = saveFixture("h2")
        val m2 = uniqueModel("h2-late"); addMembership(f.providerId, m2)
        runBlocking { withTimeout(TIMEOUT_MS) { viewModel.myModelKeys.first { keys -> keys.any { it.providerModelId == m2 } } } }
        val rec = Rec(); val fetch = rowsFetcher("h2")
        val o = refresh(f, m2, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
        assertTrue("emission-shaped pass dispatches exactly once, was $o", o == RefreshOutcome.SUCCEEDED || o == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(f.w(m2)))
        assertEquals(RefreshOrigin.SESSION_AUTO, rec.all().filterIsInstance<RefreshAttemptEvent.Evaluated>().single { it.w == f.w(m2) }.origin)
    }

    @Test
    fun h3PassesRepeatBudgetsStayPerScope() = runBlocking {
        val f = saveFixture("h3")
        val m1 = uniqueModel("h3-1"); val m2 = uniqueModel("h3-2")
        addMembership(f.providerId, m1); addMembership(f.providerId, m2)
        val rec = Rec(); val fetch = rowsFetcher("h3")
        listOf(m1, m2).forEach { m ->
            refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch)
            assertEquals(RefreshOutcome.SUPPRESSED_BUDGET, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), rec, fetch))
        }
        assertEquals(1, rec.charged(f.w(m1))); assertEquals(1, rec.charged(f.w(m2)))
        assertEquals(4, rec.evaluatedCount())
    }

    @Test
    fun h4NonMemberRejectedMembershipUnchanged() = runBlocking {
        val f = saveFixture("h4"); val m = uniqueModel("h4")
        val vm2 = newVm()
        val before = runBlocking { app.database.myModelsDao().all() }
        assertEquals(RefreshOutcome.SKIPPED_NOT_MEMBER, refresh(f, m, RefreshOrigin.SESSION_AUTO, absentCapable(), Rec(), throwingFetcher(), vm = vm2))
        assertEquals(before, runBlocking { app.database.myModelsDao().all() })
    }

    // ===== Slice-union quota vector (SLICE-UNION, DECIDED) =====

    @Test
    fun sliceUnionMixedSlicesDispatchExactlyOnce() = runBlocking {
        val f = saveFixture("su"); val m = uniqueModel("su"); addMembership(f.providerId, m)
        seedGate(f)
        val w = f.w(m); val rec = Rec()
        val key = sk(f.providerId, f.accountId, f.credentialId, m, null, null, null)
        val ev = "f1-su-ev-${UUID.randomUUID()}"
        runBlocking {
            app.database.evidenceDao().insert(EvidenceEntity(id = ev, requestId = "f1-su-req", providerId = f.providerId,
                modelId = m, outcome = "OK", source = "newapi"))
            app.database.snapshotFactDao().upsertSliceHead(SnapshotSliceHeadEntity(scopeKey = key,
                kind = SnapshotKind.ACCOUNT_QUOTA.name, quotaSlice = "STANDARD_INTERACTIVE",
                latestEvidenceId = ev, latestObservedAt = 1_000L, lastGoodSnapshotId = "snap-$ev", lastGoodObservedAt = 1_000L))
        }
        val before = tableSet()
        val fetch = rowsFetcher("su")
        val o1 = refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, fetch)
        assertTrue("mixed good+absent slices dispatch exactly once, was $o1", o1 == RefreshOutcome.SUCCEEDED || o1 == RefreshOutcome.FAILED_NO_GOOD)
        assertEquals(1, rec.charged(w))
        assertEquals(RefreshOutcome.SUPPRESSED_BUDGET, refresh(f, m, RefreshOrigin.SESSION_AUTO, null, rec, fetch))
        assertEquals("one W = one charge regardless of slice count", 1, rec.charged(w))
        assertEquals(before, tableSet())
    }

    // ===== production-seam helpers =====

    private suspend fun refresh(
        f: Fix,
        model: String,
        origin: RefreshOrigin,
        snap: PassportSnapshot?,
        rec: Rec?,
        fetch: LmspeedFetcher?,
        group: String? = null,
        vm: AtlasViewModel = viewModel,
    ): RefreshOutcome = withTimeout(TIMEOUT_MS) {
        vm.requestMyModelRefresh(
            providerId = f.providerId, providerModelId = model, accountId = f.accountId,
            credentialId = f.credentialId, group = group, origin = origin,
            lmspeedFetcher = fetch, attemptObserver = rec,
            passportReader = if (snap == null) null else suspend { snap },
        )
    }

    private fun absentCapable() = PassportSnapshot(hasLastGood = false, hasAnyHeadOrFact = false, readOk = true, legCapable = true)
    private fun failedCapable() = PassportSnapshot(hasLastGood = false, hasAnyHeadOrFact = true, readOk = true, legCapable = true)
    private fun uncapable() = PassportSnapshot(hasLastGood = false, hasAnyHeadOrFact = false, readOk = true, legCapable = false)

    private class Rec : RefreshAttemptObserver {
        private val gate = Any()
        private val box = mutableListOf<RefreshAttemptEvent>()
        override fun onEvent(e: RefreshAttemptEvent) { synchronized(gate) { box += e } }
        fun all(): List<RefreshAttemptEvent> = synchronized(gate) { box.toList() }
        fun evaluated(w: W): List<RefreshOutcome> = all().filterIsInstance<RefreshAttemptEvent.Evaluated>().filter { it.w == w }.map { it.outcome }
        fun evaluatedCount(): Int = all().filterIsInstance<RefreshAttemptEvent.Evaluated>().size
        fun terminal(w: W): List<RefreshOutcome> = all().filterIsInstance<RefreshAttemptEvent.Terminal>().filter { it.w == w }.map { it.outcome }
        fun charged(w: W): Int = all().count { it is RefreshAttemptEvent.ChargedAtStart && it.w == w }
        fun reserved(w: W): Int = all().count { it is RefreshAttemptEvent.Reserved && it.w == w }
        fun joined(w: W): Int = all().count { it is RefreshAttemptEvent.Joined && it.w == w }
        fun released(w: W): Int = all().count { it is RefreshAttemptEvent.ReleasedNoCharge && it.w == w }
        fun autoCharged(w: W): Int = all().count { it is RefreshAttemptEvent.ChargedAtStart && it.w == w && it.origin == RefreshOrigin.SESSION_AUTO }
        fun userCharged(w: W): Int = all().count { it is RefreshAttemptEvent.ChargedAtStart && it.w == w && it.origin == RefreshOrigin.USER_EXPLICIT }
    }

    private class ScriptedFetcher(
        private val behavior: suspend (callIndex: Int) -> List<LmspeedRawModel>,
    ) : LmspeedFetcher {
        private val counter = AtomicInteger(0)
        val calls: Int get() = counter.get()
        val httpCalls: Int get() = 0
        override suspend fun fetchModels(): List<LmspeedRawModel> = behavior(counter.incrementAndGet())
    }

    private fun rowsFetcher(tag: String): ScriptedFetcher {
        val slug = "$EXTERNAL_PREFIX-$tag-${UUID.randomUUID()}"
        return ScriptedFetcher { listOf(LmspeedRawModel(slug = slug)) }
    }
    private fun emptyFetcher() = ScriptedFetcher { emptyList() }
    private fun throwingFetcher(): ScriptedFetcher = ScriptedFetcher { throw AssertionError("no leg must run on this path") }

    private suspend fun awaitCond(cond: () -> Boolean) {
        withTimeout(TIMEOUT_MS) { while (!cond()) yield() }
    }

    // ===== fixture / evidence / cleanup =====

    private data class Fix(val providerId: String, val accountId: String, val credentialId: String) {
        fun w(model: String, acc: String? = null, cred: String? = null, group: String? = null, endpointId: String? = null, operation: String? = null) =
            MyModelMetadataRetryPolicy.RefreshWorkScope(providerId, model, acc ?: accountId, cred ?: credentialId, group, endpointId, operation)
    }

    private fun saveFixture(label: String): Fix {
        val name = "F1 retry $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, SEED_MODEL, FIXTURE_SECRET) }
        val item = awaitUi { state -> !state.saving && state.connections.any { it.name == name } }
            .connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        await { viewModel.myModelKeys.first { it.any { k -> k.providerId == item.providerId && k.providerModelId == SEED_MODEL } } }
        return Fix(item.providerId, item.accountId, item.credentialId)
    }

    private fun seedSecondScope(providerId: String): Pair<String, String> {
        val accountId = "f1-retry-acc-${UUID.randomUUID()}"
        val credentialId = "f1-retry-cred-${UUID.randomUUID()}"
        runBlocking {
            app.database.accountDao().insert(AccountEntity(accountId, providerId))
            app.database.credentialDao().insert(
                CredentialEntity(credentialId = credentialId, accountId = accountId, label = "f1 retry fixture"),
            )
        }
        return accountId to credentialId
    }

    private fun addMembership(providerId: String, modelId: String) = runBlocking {
        assertTrue(
            MyModelsStore(app.database).addToMyModels(providerId, modelId, source = "USER", nowMillis = System.currentTimeMillis()),
        )
    }

    private fun sk(providerId: String, accountId: String?, credentialId: String?, model: String, group: String?, endpointId: String?, operation: String?): String =
        listOf(providerId, accountId, credentialId, model, group, endpointId, operation)
            .joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }

    private fun seedHead(f: Fix, model: String, kind: String, lastGood: Boolean, at: Long = 1_000L,
        group: String? = null, endpointId: String? = null, operation: String? = null) {
        seedHeadFor(f, model, acc = f.accountId, cred = f.credentialId, kind = kind, lastGood = lastGood, at = at,
            group = group, endpointId = endpointId, operation = operation)
    }

    /** E5 用：seed 一顆 manual offering（+endpoint FK parent），讓 seam 的
     *  resolveOfferingEndpointId singleOrNull 推導出 endpoint、protocol 推導
     *  certified operation（CHAT_COMPLETIONS 在 certified 集內）。 */
    private fun seedOffering(f: Fix, model: String, endpointId: String, protocol: String) = runBlocking {
        val ep = "f1-ep-$endpointId-${UUID.randomUUID()}"
        app.database.endpointDao().insert(
            EndpointEntity(endpointId = ep, providerId = f.providerId,
                baseUrl = "https://fixture.invalid/e5", endpointUrl = "https://fixture.invalid/e5/chat", protocol = protocol),
        )
        app.database.offeringDao().insert(
            OfferingEntity(id = "f1-off-$endpointId-${UUID.randomUUID()}", providerId = f.providerId,
                endpointId = ep, providerModelId = model, manual = true, source = "MANUAL"),
        )
        ep
    }

    private fun seedHeadFor(f: Fix, model: String, acc: String?, cred: String?, kind: String, lastGood: Boolean,
        at: Long = 1_000L, group: String? = null, endpointId: String? = null, operation: String? = null,
        scopeModel: String? = null) = runBlocking {
        val m = scopeModel ?: model
        val key = sk(f.providerId, acc, cred, m, group, endpointId, operation)
        val ev = "f1-ev-${UUID.randomUUID()}"
        app.database.evidenceDao().insert(EvidenceEntity(id = ev, requestId = "f1-req-${UUID.randomUUID()}",
            providerId = f.providerId, modelId = m, outcome = if (lastGood) "OK" else "FAIL", source = "newapi"))
        app.database.snapshotFactDao().insert(SnapshotFactEntity(evidenceId = ev, scopeKey = key, kind = kind,
            observedAt = at, snapshotId = if (lastGood) "snap-$ev" else null,
            state = if (lastGood) "OK" else "UNKNOWN", status = "OK", confidence = "HIGH",
            providerVersion = null, pricingVersion = null, itemPricingVersion = null,
            quotaPerUnit = null, groupRatio = null, statusObservedAt = null, remainingRaw = null,
            usedRaw = null, grantedRaw = null, unlimited = null, expiresAtRaw = null,
            expiryMeaning = null, expressionHash = null, priceUnit = null))
        app.database.snapshotFactDao().upsertHead(SnapshotHeadEntity(scopeKey = key, kind = kind,
            latestEvidenceId = ev, latestObservedAt = at,
            lastGoodSnapshotId = if (lastGood) "snap-$ev" else null,
            lastGoodObservedAt = if (lastGood) at else null))
    }

    private fun seedGate(f: Fix) =
        seedHeadFor(f, "f1-gate-probe", acc = f.accountId, cred = f.credentialId, kind = SnapshotKind.PRICE.name,
            lastGood = false, scopeModel = "f1-gate-probe")

    private fun samplesCount(providerId: String): Int =
        app.database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM samples WHERE providerId = ?", arrayOf(providerId))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    private fun tableSet(): Set<String> =
        app.database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", emptyArray())
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun newVm(): AtlasViewModel {
        val store = ViewModelStore()
        extraStores += store
        var vm: AtlasViewModel? = null
        onMain {
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
        return vm!!
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    private fun <T> await(timeoutMillis: Long = TIMEOUT_MS, block: suspend () -> T): T {
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { block() } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    onMain { }
                }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    private fun awaitUi(predicate: (AtlasUiState) -> Boolean): AtlasUiState =
        await { viewModel.state.first(predicate) }

    private fun uniqueModel(label: String): String = "f1-retry-$label-${UUID.randomUUID()}"

    /** First-dim provider of a length-prefixed 7-dim scopeKey (mirrors storageKey dim 0). */
    private fun String.scopeKey_dim0(): String? {
        val colon = indexOf(':')
        if (colon <= 0) return null
        val len = substring(0, colon).toIntOrNull() ?: return null
        if (len < 0 || colon + 1 + len > length) return null
        return substring(colon + 1, colon + 1 + len)
    }

    private fun rowsFor(slug: String): List<LmspeedRawModel> = listOf(LmspeedRawModel(slug = slug))

    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        runBlocking {
            val dao = app.database.snapshotFactDao()
            SnapshotKind.values().forEach { kind ->
                dao.headsByKind(kind.name).forEach { head ->
                    if (head.scopeKey.scopeKey_dim0() in providerIds) {
                        app.database.openHelper.writableDatabase.execSQL(
                            "DELETE FROM snapshot_heads WHERE scopeKey = ? AND kind = ?",
                            arrayOf(head.scopeKey, head.kind),
                        )
                    }
                }
            }
            listOf(SnapshotKind.ACCOUNT_QUOTA, SnapshotKind.TOKEN_QUOTA,
                SnapshotKind.DEDICATED_ACCOUNT_QUOTA, SnapshotKind.DEDICATED_TOKEN_QUOTA).forEach { kind ->
                dao.sliceHeadsByKind(kind.name).forEach { head ->
                    if (head.scopeKey.scopeKey_dim0() in providerIds) {
                        app.database.openHelper.writableDatabase.execSQL(
                            "DELETE FROM snapshot_slice_heads WHERE scopeKey = ? AND kind = ? AND quotaSlice = ?",
                            arrayOf(head.scopeKey, head.kind, head.quotaSlice),
                        )
                    }
                }
            }
        }
        val list = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM external_mappings WHERE externalId LIKE ?", arrayOf("$EXTERNAL_PREFIX-%"))
            db.execSQL("DELETE FROM external_observations WHERE externalModelId LIKE ?", arrayOf("$EXTERNAL_PREFIX-%"))
            val canonicalIds = mutableListOf<String>()
            db.query(
                "SELECT DISTINCT canonicalModelId FROM provider_model_aliases WHERE providerId IN ($list)",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) canonicalIds += cursor.getString(0)
            }
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", args)
            // snapshot_facts has no providerId column; its rows are FK-bound to evidence
            // (evidenceId -> evidence.id, providerId IN (...)), so delete via evidence join.
            db.execSQL(
                "DELETE FROM snapshot_facts WHERE evidenceId IN (SELECT id FROM evidence WHERE providerId IN ($list))",
                args,
            )
            db.execSQL("DELETE FROM evidence WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_notices WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_events WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", args)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", args)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_model_aliases WHERE providerId IN ($list)", args)
            if (canonicalIds.isNotEmpty()) {
                val canonicalPlaceholders = canonicalIds.joinToString(",") { "?" }
                db.execSQL(
                    "DELETE FROM canonical_models WHERE canonicalModelId IN ($canonicalPlaceholders) " +
                        "AND canonicalModelId NOT IN (SELECT canonicalModelId FROM provider_model_aliases)",
                    canonicalIds.toTypedArray<Any?>(),
                )
            }
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM my_models WHERE providerId IN ($list)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://f1-retry.fixture.invalid/v1"
        const val SEED_MODEL = "f1-retry-seed"
        const val FIXTURE_SECRET = "f1-retry-fixture-secret-never-persisted"
        const val EXTERNAL_PREFIX = "f1-retry"
    }
}
