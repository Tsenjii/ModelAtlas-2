package dev.modelatlas.app.background

import kotlinx.coroutines.CancellationException

/** A single sync attempt; throws on failure. Wired by the integrator. */
fun interface SyncRunner {
    suspend fun run()
}

/** Outcome of one worker attempt. */
enum class SyncWorkerResult { SUCCESS, RETRY }

/**
 * X-011 — worker failure semantics (BACKLOG X-011 acceptance 2–4, X-006).
 *
 * The logic only invokes [SyncRunner]. On failure it returns
 * [SyncWorkerResult.RETRY] and **never** touches snapshot/offering state:
 * - last-good observed facts are left byte-for-byte equal (X-006),
 * - no offering is deleted and no `visible` flag is cleared,
 * - the caller maps RETRY to WorkManager `Result.retry()`.
 */
class SyncWorkerLogic(private val runner: SyncRunner) {

    suspend fun doWork(): SyncWorkerResult =
        try {
            runner.run()
            SyncWorkerResult.SUCCESS
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // One failure must not clear last-good facts nor delete data (X-006 / X-012).
            SyncWorkerResult.RETRY
        }
}
