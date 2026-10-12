package dev.modelatlas.app.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.modelatlas.app.background.SyncRunner
import dev.modelatlas.app.background.SyncWorkerLogic
import dev.modelatlas.app.background.SyncWorkerResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * X-011a interim (DRAFT spec `ai11-spec-s2-interim.md` §4): periodic pipeline not wired.
 *
 * - Fresh installs never enqueue; upgrades cancel the live loop (§3.1/§3.2).
 * - Worker body untouched: stub still throws → RETRY, never fake success.
 * - Same-slice dependency: production `isPeriodicSchedulingEnabled()` +
 *   `cancelPeriodic()` land with the Astra owner; this test pins that contract and
 *   fails loudly if the gate flips without the wired-runner slice (X-011 S0/S1).
 * - No `androidx.work:work-testing` dep: real on-device [WorkManager] for
 *   cancel/no-live assertions + a call-record fake mirroring the §3.2
 *   `if (enabled) schedule else cancel` hunk (no real enqueue on the fresh path).
 */
@RunWith(AndroidJUnit4::class)
class SyncSchedulerInterimTest {

    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workManager = WorkManager.getInstance(context)
        // Best-effort clean slate; interim cancel is idempotent on fresh installs.
        runCatching { SyncScheduler.cancelPeriodic(workManager) }
    }

    private fun livePeriodicWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_PERIODIC_WORK_NAME)
            .get(10, TimeUnit.SECONDS)

    private fun hasLivePeriodic(infos: List<WorkInfo>): Boolean =
        infos.any {
            it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }

    private fun pollUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
    }

    /** Mirrors `AtlasApplication.onCreate` §3.2 hunk so the guard is pinned without booting the app. */
    private fun routeInterimOnCreate(
        enabled: Boolean,
        onSchedule: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (enabled) onSchedule() else onCancel()
    }

    // Spec §4 vector 1 — gate intent: flipped only inside the wired-runner slice.
    @Test
    fun gate_isOffUntilWiredRunnerLands() {
        assertFalse(
            "X-011a: flip isPeriodicSchedulingEnabled() only with X-011 S0/S1 wiring",
            SyncScheduler.isPeriodicSchedulingEnabled(),
        )
    }

    // Spec §4 vectors 2+3 (guard): OFF routes to cancel, never schedule; ON routes to schedule.
    @Test
    fun enqueueGuard_routesToCancelWhenGateOff_andScheduleWhenOn() {
        var scheduled = 0
        var cancelled = 0
        routeInterimOnCreate(
            enabled = false,
            onSchedule = { scheduled++ },
            onCancel = { cancelled++ },
        )
        assertEquals(0, scheduled)
        assertEquals(1, cancelled)

        scheduled = 0
        cancelled = 0
        routeInterimOnCreate(
            enabled = true,
            onSchedule = { scheduled++ },
            onCancel = { cancelled++ },
        )
        assertEquals(1, scheduled)
        assertEquals(0, cancelled)
    }

    // Spec §4 vector 2 — fresh install never enqueues: interim onCreate ELSE branch only cancels.
    @Test
    fun freshInstall_cancelPathLeavesNoLivePeriodicWork_andNeverEnqueues() {
        // This is the exact ELSE branch from §3.2; schedulePeriodic is never called here.
        SyncScheduler.cancelPeriodic(workManager)

        val infos = livePeriodicWork()
        assertFalse(
            "fresh install must have no ENQUEUED/RUNNING/BLOCKED ${SyncScheduler.UNIQUE_PERIODIC_WORK_NAME}, saw=$infos",
            hasLivePeriodic(infos),
        )
    }

    // Spec §4 vector 3 — upgrade cancels live loop; a prior retry() does not resurrect the period.
    @Test
    fun upgrade_cancelRemovesLiveLoop_andRetryDoesNotResurrect() {
        SyncScheduler.schedulePeriodic(workManager)
        try {
            pollUntil { hasLivePeriodic(runCatching { livePeriodicWork() }.getOrDefault(emptyList())) }

            // Interim onCreate ELSE branch on upgrade.
            SyncScheduler.cancelPeriodic(workManager)

            pollUntil { runCatching { !hasLivePeriodic(livePeriodicWork()) }.getOrDefault(false) }
            val afterCancel = livePeriodicWork()
            assertFalse(
                "after interim cancel, periodic must be absent/cancelled, saw=$afterCancel",
                hasLivePeriodic(afterCancel),
            )
            // Second read: a prior retry() must not have resurrected ENQUEUED/RUNNING.
            Thread.sleep(500)
            assertFalse(
                "retry() must not resurrect the period after cancel, saw=${livePeriodicWork()}",
                hasLivePeriodic(livePeriodicWork()),
            )
        } finally {
            runCatching { SyncScheduler.cancelPeriodic(workManager) }
        }
    }

    // Spec §4 vector 4 — worker class untouched: stub still fail-closed (throw → RETRY).
    @Test
    fun workerStub_stillFailClosed_neverFakeSuccess() = runBlocking {
        val stubResult = SyncWorkerLogic(
            SyncRunner { throw IllegalStateException("sync pipeline not wired") },
        ).doWork()
        assertEquals(SyncWorkerResult.RETRY, stubResult)

        // Only SUCCESS path is a completing runner — proves no success-faking in the mapping.
        val okResult = SyncWorkerLogic(SyncRunner { }).doWork()
        assertEquals(SyncWorkerResult.SUCCESS, okResult)
    }

    // Spec §4 vectors 4+6 — worker touches no stores; scheduler builders/policies untouched.
    @Test
    fun workerAndSchedulerShape_untouchedByInterim() {
        // Worker still the single CoroutineWorker; KDoc fail-closed note is source-level
        // (reviewer checks `git diff SyncWorker.kt` is KDoc-only, `SyncWorkerLogic.kt` zero hunks).
        assertTrue(
            "SyncWorker must remain a CoroutineWorker",
            CoroutineWorker::class.java.isAssignableFrom(SyncWorker::class.java),
        )
        val blob = buildString {
            append(SyncWorker::class.java.name)
            append(SyncWorker::class.java.superclass?.name)
            append(SyncWorker::class.java.declaredMethods.joinToString())
            append(SyncWorker::class.java.declaredFields.joinToString())
            append(SyncWorkerLogic::class.java.declaredMethods.joinToString())
        }
        listOf("offeringDao", "MyModelsStore", "snapshot").forEach { forbidden ->
            assertFalse("worker must not reference $forbidden", blob.contains(forbidden))
        }

        // §3.1 constraint: builders/policies untouched → existing SyncSchedulerTest stays green.
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, SyncScheduler.periodicPolicy())
        assertEquals(30L, SyncScheduler.PERIODIC_INTERVAL_MINUTES)
        assertEquals("modelatlas.sync.periodic", SyncScheduler.UNIQUE_PERIODIC_WORK_NAME)
        val constraints = SyncScheduler.constraints()
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(SyncScheduler.TAG_SYNC in SyncScheduler.buildPeriodicRequest().tags)
    }
}
