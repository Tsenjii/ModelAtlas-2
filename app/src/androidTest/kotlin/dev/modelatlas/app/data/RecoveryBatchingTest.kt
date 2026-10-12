package dev.modelatlas.app.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.StartupRecovery
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PK-19 / S18 batch-`recoverInterrupted` device tests (real in-memory Room + real
 * [AtlasRepository]), per `/tmp/opencode/ai11-spec-pk19-batch.md` §6.
 *
 * Production under test (lands concurrently): `recoverInterrupted` wraps the read +
 * per-row `save()` in ONE top-level `db.withTransaction` (read moved inside, `save()`
 * reused, no raw UPDATE). Nested `withTransaction` inherits via `TransactionElement`,
 * so N rows = 1 BEGIN / 1 commit / 1 invalidation refresh, and any failure rolls
 * back the whole batch (all-or-nothing, F29-retryable).
 *
 * Vector → test map (spec §6 T1-T6; T7 is static-review-only, no test):
 * - T1 multi-row semantics → [batchRecoveryMarksAllRunningInterruptedWithUntouchedColumns]
 * - T2 terminal rows untouched → [terminalAndPreInterruptedRowsAreByteIdentical]
 * - T1-transaction query-counter (exactly one top-level tx for K=3) →
 *   [batchRecoveryCommitsExactlyOneTopLevelTransaction]
 * - T3 single-transaction + rollback (deterministic trigger pin) →
 *   [injectedEvidenceFailureRollsBackWholeBatch]
 * - T4 empty / idempotent → [emptyRecoverySucceedsWithZeroChanges] +
 *   [secondRecoveryIsIdempotentNoop]
 * - T5 F29 integration on failure → [startupRecoveryFailsDegradedThenSelfHeals]
 * - T6 single-invalidation regression (timing-bounded, signal only) →
 *   [recoveryProducesSingleSamplesEmission]
 *
 * Notes:
 * - `Outcome` has no FAILED; the failed terminal leg uses SERVER (HTTP 500).
 * - T3/T5 triggers are test-only SQL via `openHelper.writableDatabase`; no
 *   production hook. T6 is a regression signal only: Room coalescing can collapse
 *   even the old per-row shape, so T3 + code review stay the authoritative pins.
 * - Fixtures live only in androidTest, never product data; no secrets anywhere.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryBatchingTest {
    private lateinit var context: Context
    private val open = mutableListOf<AtlasDatabase>()

    // Query-counter capture (Cr010 pattern: direct executor => synchronous callback).
    private val queries = CopyOnWriteArrayList<SqlRead>()
    @Volatile private var captureReads = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
        queries.clear()
        captureReads = false
    }

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    private fun capturingDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, args ->
                    if (captureReads) queries += SqlRead(sql, args.toList())
                },
                Executor { it.run() },
            )
            .build()
            .also { open += it }

    private fun runningSample(
        id: String,
        startedAt: Long,
        httpStatus: Int? = null,
    ) = PerformanceSample(
        id = id,
        providerId = "p1",
        accountId = "a1",
        safeCredentialId = "c1",
        endpointId = "e1",
        providerModelId = "m1",
        canonicalModelId = null,
        group = null,
        operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.PLAYGROUND,
        requestStartedAt = startedAt,
        streaming = true,
        usage = Usage(inputTokens = 3L, outputTokens = 7L),
        outcome = Outcome.RUNNING,
        httpStatus = httpStatus,
        priceState = "FREE_NOW",
        quotaState = "UNKNOWN",
    )

    // =====================================================================
    // T1: multi-row semantics — K=3 RUNNING rows recover, nothing else touched.
    // =====================================================================

    @Test
    fun batchRecoveryMarksAllRunningInterruptedWithUntouchedColumns() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val ids = listOf("rb-s1", "rb-s2", "rb-s3")
        val started = listOf(1000L, 2000L, 3000L)
        ids.forEachIndexed { i, id ->
            repo.save(runningSample(id, started[i], httpStatus = if (i == 1) 200 else null))
        }
        val before = ids.associateWith { db.sampleDao().getById(it)!! }

        repo.recoverInterrupted()

        ids.forEachIndexed { i, id ->
            val row = db.sampleDao().getById(id)
            assertEquals("INTERRUPTED", row?.outcome)
            assertNull(row?.completedAt)
            assertNull(row?.totalLatencyMs)
            // Every non-outcome column identical to pre-recovery.
            assertEquals(before[id]!!.copy(outcome = "INTERRUPTED"), row)
            val ev = db.evidenceDao().byRequest(id)
            assertNotNull("evidence missing for $id", ev)
            assertEquals("sample:$id", ev!!.id)
            assertEquals("INTERRUPTED", ev.outcome)
            assertEquals(started[i], ev.timestamp)
            assertEquals("PLAYGROUND", ev.source)
            assertEquals(if (i == 1) 200 else null, ev.status)
        }
        assertEquals(3L, countWhere(db, "SELECT COUNT(*) FROM evidence"))
    }

    // =====================================================================
    // T2: terminal + pre-existing INTERRUPTED rows stay byte-identical.
    // =====================================================================

    @Test
    fun terminalAndPreInterruptedRowsAreByteIdentical() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.save(runningSample("rb-t2-run", 1000L))
        repo.save(
            runningSample("rb-t2-ok", 1100L, httpStatus = 200).copy(
                outcome = Outcome.SUCCESS,
                completedAt = 2000L,
                totalLatencyMs = 900L,
                ttftMs = 100L,
            ),
        )
        repo.save(
            runningSample("rb-t2-err", 1200L, httpStatus = 500).copy(
                outcome = Outcome.SERVER,
                completedAt = 3000L,
                totalLatencyMs = 800L,
            ),
        )
        repo.save(
            runningSample("rb-t2-cancel", 1300L).copy(
                outcome = Outcome.CANCELLED,
                completedAt = 3100L,
            ),
        )
        repo.save(
            runningSample("rb-t2-prev", 900L).copy(outcome = Outcome.INTERRUPTED),
        )
        val untouchedIds = listOf("rb-t2-ok", "rb-t2-err", "rb-t2-cancel", "rb-t2-prev")
        val samplesBefore = untouchedIds.associateWith { db.sampleDao().getById(it)!! }
        val evidenceBefore = untouchedIds.associateWith { db.evidenceDao().byRequest(it)!! }

        repo.recoverInterrupted()

        assertEquals("INTERRUPTED", db.sampleDao().getById("rb-t2-run")?.outcome)
        untouchedIds.forEach { id ->
            assertEquals("sample row changed for terminal $id", samplesBefore[id], db.sampleDao().getById(id))
            assertEquals("evidence changed for terminal $id", evidenceBefore[id], db.evidenceDao().byRequest(id))
        }
        assertEquals(5L, countWhere(db, "SELECT COUNT(*) FROM samples"))
        assertEquals(5L, countWhere(db, "SELECT COUNT(*) FROM evidence"))
    }

    // =====================================================================
    // T-query-counter: K=3 rows cost exactly ONE top-level transaction.
    // `RoomDatabase.withTransaction` begins via legacy `beginTransaction()`,
    // intercepted as "BEGIN EXCLUSIVE TRANSACTION" (Room 2.7.1 verified);
    // nested `save()` inherits via TransactionElement (no extra BEGIN).
    // InvalidationTracker refresh uses IMMEDIATE, never EXCLUSIVE, so this
    // count is immune to async tracker refresh.
    // =====================================================================

    @Test
    fun batchRecoveryCommitsExactlyOneTopLevelTransaction() = runBlocking {
        val db = capturingDb()
        val repo = AtlasRepository(db)
        listOf("rb-q1" to 1000L, "rb-q2" to 2000L, "rb-q3" to 3000L).forEach { (id, at) ->
            repo.save(runningSample(id, at))
        }

        queries.clear()
        // Drain any invalidation pending from the fixture saves BEFORE capture: the flush
        // rides the next transaction open (which would be recovery's first BEGIN), making
        // the once-flush assertion count fixture noise. Retry the open/close until the
        // tracker reports a clean log (single pass can miss on slow CI timing — the
        // flush rides an async tracker thread; seen flaking in run 37900428554).
        repeat(5) {
            db.openHelper.writableDatabase.beginTransaction()
            db.openHelper.writableDatabase.endTransaction()
            // Give the async tracker thread a beat to consume its pending flush.
            Thread.sleep(50)
        }
        queries.clear()
        captureReads = true
        try {
            repo.recoverInterrupted()
        } finally {
            captureReads = false
        }
        val reads = queries.toList()

        // Room nests withTransaction (outer batch + per-save inner tx both issue
        // BEGIN IMMEDIATE). The batch contract's deterministic observable: ZERO
        // invalidation flushes BETWEEN the first and last write (mid-batch interleave)
        // and no writes outside the write group. The tracker's flushes that ride
        // the capture-start residue or the post-batch refresh are Room-internal
        // async timing, not batch writes (flaked into the window in run 37900428554:
        // both flushes sat OUTSIDE the write group; the old "at most one" count
        // counted them and broke).
        val firstWriteIdx = reads.indexOfFirst {
            it.sql.contains("REPLACE", ignoreCase = true) || it.sql.contains("INSERT", ignoreCase = true)
        }
        val lastWriteIdx = reads.indexOfLast {
            it.sql.contains("REPLACE", ignoreCase = true) || it.sql.contains("INSERT", ignoreCase = true)
        }
        assertTrue("batch must contain writes, got: $reads", firstWriteIdx in reads.indices)
        val midBatchFlushes = reads.subList(firstWriteIdx, lastWriteIdx + 1).count {
            it.sql.contains("room_table_modification_log")
        }
        assertTrue("K=3 recovery must not flush invalidation mid-batch, got: $reads", midBatchFlushes == 0)
        val writesAfterLastWrite = reads.drop(lastWriteIdx + 1).count {
            it.sql.contains("REPLACE", ignoreCase = true) || it.sql.contains("INSERT", ignoreCase = true)
        }
        assertEquals("no write may commit outside the batch transaction, got: $reads", 0, writesAfterLastWrite)
        val sampleWrites = reads.count { it.sql.contains("samples", ignoreCase = true) && it.sql.contains("REPLACE", ignoreCase = true) }
        assertEquals(3, sampleWrites)
        val evidenceWrites = reads.count { it.sql.contains("evidence", ignoreCase = true) && it.sql.contains("REPLACE", ignoreCase = true) }
        assertEquals(3, evidenceWrites)

        // The counted tx did real work (guards a vacuous 1-BEGIN pass).
        listOf("rb-q1", "rb-q2", "rb-q3").forEach { id ->
            assertEquals("INTERRUPTED", db.sampleDao().getById(id)?.outcome)
        }
    }

    // =====================================================================
    // T3: injected evidence failure rolls back the WHOLE batch (all-or-nothing).
    // Under the old per-row shape rb-q.../rb-t3-s1 would already be INTERRUPTED
    // at abort time; single-tx shape leaves all 3 RUNNING. Deterministic pin.
    // =====================================================================

    @Test
    fun injectedEvidenceFailureRollsBackWholeBatch() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val ids = listOf("rb-t3-s1", "rb-t3-s2", "rb-t3-s3")
        ids.forEachIndexed { i, id -> repo.save(runningSample(id, 1000L + i)) }
        val samplesBefore = ids.associateWith { db.sampleDao().getById(it)!! }
        val evidenceBefore = ids.associateWith { db.evidenceDao().byRequest(it)!! }

        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER rb_t3_abort BEFORE INSERT ON evidence " +
                "WHEN NEW.requestId='rb-t3-s2' BEGIN SELECT RAISE(ABORT,'injected'); END",
        )
        try {
            val error = runCatching { repo.recoverInterrupted() }.exceptionOrNull()
            assertNotNull("trigger ABORT must fail recoverInterrupted", error)
            assertTrue(
                "must surface a SQLite failure, got: $error",
                chainHasSQLiteException(error),
            )
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS rb_t3_abort")
        }

        ids.forEach { id ->
            assertEquals("row $id must remain RUNNING after rollback", "RUNNING", db.sampleDao().getById(id)?.outcome)
            assertEquals(samplesBefore[id], db.sampleDao().getById(id))
            assertEquals(evidenceBefore[id], db.evidenceDao().byRequest(id))
        }
        assertEquals(3L, countWhere(db, "SELECT COUNT(*) FROM evidence"))
    }

    // =====================================================================
    // T4: empty recovery is a no-op; second call after success is idempotent.
    // =====================================================================

    @Test
    fun emptyRecoverySucceedsWithZeroChanges() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.recoverInterrupted()
        assertTrue(db.sampleDao().running().isEmpty())
        assertEquals(0L, countWhere(db, "SELECT COUNT(*) FROM samples"))
        assertEquals(0L, countWhere(db, "SELECT COUNT(*) FROM evidence"))
    }

    @Test
    fun secondRecoveryIsIdempotentNoop() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.save(runningSample("rb-t4-s1", 1000L))
        repo.recoverInterrupted()
        val sampleAfterFirst = db.sampleDao().getById("rb-t4-s1")!!
        val evidenceAfterFirst = db.evidenceDao().byRequest("rb-t4-s1")!!

        repo.recoverInterrupted()

        assertEquals("INTERRUPTED", db.sampleDao().getById("rb-t4-s1")?.outcome)
        assertEquals(sampleAfterFirst, db.sampleDao().getById("rb-t4-s1"))
        assertEquals(evidenceAfterFirst, db.evidenceDao().byRequest("rb-t4-s1"))
        assertEquals(1L, countWhere(db, "SELECT COUNT(*) FROM samples"))
        assertEquals(1L, countWhere(db, "SELECT COUNT(*) FROM evidence"))
    }

    // =====================================================================
    // T5: F29 integration — faulted recovery reports failure and preserves
    // RUNNING; dropping the fault and retrying self-heals, deleting nothing.
    // =====================================================================

    @Test
    fun startupRecoveryFailsDegradedThenSelfHeals() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        val ids = listOf("rb-t5-s1", "rb-t5-s2", "rb-t5-s3")
        ids.forEachIndexed { i, id -> repo.save(runningSample(id, 1000L + i)) }

        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER rb_t5_abort BEFORE INSERT ON evidence " +
                "WHEN NEW.requestId='rb-t5-s2' BEGIN SELECT RAISE(ABORT,'injected'); END",
        )
        try {
            val failed = StartupRecovery.run(
                recover = { repo.recoverInterrupted() },
                maxAttempts = 2,
                delayMsForAttempt = { 0L },
            )
            assertTrue("faulted recovery must report failure", !failed.success)
            assertEquals(2, failed.attempts)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS rb_t5_abort")
        }
        ids.forEach { id ->
            assertEquals("RUNNING must be preserved for retry", "RUNNING", db.sampleDao().getById(id)?.outcome)
            assertEquals(1, db.sampleDao().countById(id))
        }

        val healed = StartupRecovery.run(
            recover = { repo.recoverInterrupted() },
            maxAttempts = 3,
            delayMsForAttempt = { 0L },
        )
        assertTrue(healed.success)
        ids.forEach { id ->
            assertEquals("INTERRUPTED", db.sampleDao().getById(id)?.outcome)
            assertEquals(1, db.sampleDao().countById(id))
            assertNotNull(db.evidenceDao().byRequest(id))
        }
        assertEquals(3L, countWhere(db, "SELECT COUNT(*) FROM samples"))
        assertEquals(3L, countWhere(db, "SELECT COUNT(*) FROM evidence"))
    }

    // =====================================================================
    // T6: single-invalidation regression — one post-recovery `samples`
    // emission for K=3 (bounded quiet-wait; regression signal only, see
    // header: coalescing can collapse even the old shape).
    // =====================================================================

    @Test
    fun recoveryProducesSingleSamplesEmission() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        listOf("rb-t6-s1", "rb-t6-s2", "rb-t6-s3").forEachIndexed { i, id ->
            repo.save(runningSample(id, 1000L + i))
        }
        val emissions = CopyOnWriteArrayList<List<Outcome>>()
        val job = launch {
            repo.samples.collect { emissions.add(it.map { s -> s.outcome }) }
        }
        try {
            withTimeout(10_000L) { while (emissions.isEmpty()) delay(10L) }
            val base = emissions.size

            repo.recoverInterrupted()

            withTimeout(10_000L) {
                while (emissions.none { snap -> snap.size == 3 && snap.all { it == Outcome.INTERRUPTED } }) {
                    delay(10L)
                }
            }
            delay(250L) // bounded quiet-wait: let any extra coalesced emissions land.
            assertEquals(
                "recovery must invalidate samples exactly once (signal only)",
                base + 1,
                emissions.size,
            )
        } finally {
            job.cancel()
            job.join()
        }
    }

    // =====================================================================
    // PK-11 health-hook (commit e608dd5 + M2; spec v2 §4) — save() path:
    // the hook fires AFTER the save transaction; a cancel delivered while the
    // hook is in-flight (gated at the refreshObserved aggregate read — order
    // contract, not timing coincidence) must be RETHROWN from save(), and the
    // save transaction must already have committed (sample+evidence persist).
    // =====================================================================

    @Test
    fun cancellationInsideTheRollupHookIsRethrownAndSaveTransactionAlreadyCommitted() {
        val gateEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        // Written and read only on the query-callback thread; latches carry the
        // happens-before edge for the gate itself.
        var gateArmed = true
        val gatedDb = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (gateArmed && sql.trimStart().startsWith("SELECT") && sql.contains("FROM samples")) {
                        gateArmed = false
                        gateEntered.countDown()
                        releaseGate.await()
                    }
                },
                Executor { it.run() },
            )
            .build()
            .also { open += it }
        val thrown = runBlocking {
            val repo = AtlasRepository(gatedDb)
            val scope = this
            gatedDb.providerDao().insert(
                ProviderEntity(providerId = "p1", name = "P1", observedHealth = "HEALTHY", observedHealthAt = 1L),
            )
            // Seed via direct upsert (REPLACE only — no SELECT-FROM-samples noise
            // ahead of the hook) so the gated SELECT is the hook's aggregate read.
            gatedDb.sampleDao().upsert(
                SampleEntity(
                    id = "rb-pk11-cancel-history", providerId = "p1", accountId = "a1",
                    safeCredentialId = "c1", endpointId = "e1", providerModelId = "m1",
                    requestStartedAt = 1_000L, completedAt = 1_100L,
                    outcome = "SUCCESS", httpStatus = 200,
                ),
            )
            val sample = runningSample("rb-pk11-cancel", 5_000L, httpStatus = 200).copy(
                outcome = Outcome.SUCCESS,
                completedAt = 6_000L,
                totalLatencyMs = 1_000L,
            )
            val captured = CompletableDeferred<Throwable?>()
            // Dispatchers.IO: runBlocking's event loop is BLOCKED below by the
            // JUC latch await (a non-suspending call); a plain launch on the
            // runBlocking dispatcher would never start before the gate times
            // out. The save must run on its own thread.
            val job = scope.launch(Dispatchers.IO) {
                captured.complete(runCatching { repo.save(sample) }.exceptionOrNull())
            }
            val capturedThrowable = try {
                withTimeout(10_000L) {
                    assertTrue("hook never entered the refresh path", gateEntered.await(10, TimeUnit.SECONDS))
                }
                // The gate is held inside the hook's refreshObserved aggregate
                // read: a proven in-hook point, not a timing coincidence.
                job.cancel(CancellationException("hook cancel"))
                releaseGate.countDown()
                withTimeout(10_000L) { captured.await() }
            } finally {
                releaseGate.countDown()
            }
            // Save transaction committed BEFORE the hook fired: despite the
            // rethrow, sample+evidence are persisted and untouched.
            assertEquals("SUCCESS", gatedDb.sampleDao().getById("rb-pk11-cancel")?.outcome)
            assertNotNull("evidence must persist", gatedDb.evidenceDao().byRequest("rb-pk11-cancel"))
            capturedThrowable
        }
        assertTrue(
            "cancellation inside the rollup hook must be rethrown, got: $thrown",
            thrown is CancellationException,
        )
    }

    // =====================================================================
    // PK-11 recovery pin (spec v2 §4 T13, landed-shape scoped): recovery
    // INTERRUPTED rows keep completedAt=null and the M2 hook skips
    // null-completedAt rows, so even the in-tx save() hook never fires on the
    // recovery path — observed columns stay byte-identical. The v2
    // Set<String>-return + caller-side refresh (AtlasApplication) is a later
    // slice; not pinned here.
    // =====================================================================

    @Test
    fun recoveryPathNeverMovesObservedColumnsThroughTheSaveHook() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        db.providerDao().insert(
            ProviderEntity(providerId = "p1", name = "P1", observedHealth = "HEALTHY", observedHealthAt = 1L),
        )
        val ids = listOf("rb-pk11-r1", "rb-pk11-r2", "rb-pk11-r3")
        ids.forEachIndexed { i, id -> repo.save(runningSample(id, 1000L + i)) }
        val before = db.providerDao().getById("p1")!!

        repo.recoverInterrupted()

        ids.forEach { id -> assertEquals("INTERRUPTED", db.sampleDao().getById(id)?.outcome) }
        assertEquals(
            "recovery must not move observed columns via the in-tx hook",
            before,
            db.providerDao().getById("p1"),
        )
        val after = db.providerDao().getById("p1")!!
        assertEquals("HEALTHY", after.observedHealth)
        assertEquals(1L, after.observedHealthAt)
        assertEquals("UNKNOWN", after.officialHealth)
    }

    // =====================================================================
    // PK-11 T14: empty recovery (no RUNNING rows) -> zero refreshObserved
    // invocations: no save() -> no hook -> no providers.observedHealth write.
    // =====================================================================

    @Test
    fun emptyRecoveryIssuesZeroObservedHealthRefreshes() = runBlocking {
        val db = capturingDb()
        val repo = AtlasRepository(db)
        db.providerDao().insert(
            ProviderEntity(providerId = "p1", name = "P1", observedHealth = "HEALTHY", observedHealthAt = 1L),
        )
        queries.clear()
        captureReads = true
        try {
            repo.recoverInterrupted()
        } finally {
            captureReads = false
        }
        val observedWrites = queries.count {
            it.sql.contains("UPDATE providers", ignoreCase = true) &&
                it.sql.contains("observedHealth", ignoreCase = true)
        }
        assertEquals("empty recovery must issue zero observed-health refreshes", 0, observedWrites)
        assertTrue(db.sampleDao().running().isEmpty())
        assertEquals(0L, countWhere(db, "SELECT COUNT(*) FROM samples"))
        assertEquals(0L, countWhere(db, "SELECT COUNT(*) FROM evidence"))
    }

    // ===== harness (nested in-file; no HTTP / vault / secrets) =====

    private data class SqlRead(val sql: String, val args: List<Any?>)

    private fun countWhere(db: AtlasDatabase, sql: String): Long {
        db.openHelper.readableDatabase.query(sql).use {
            if (!it.moveToFirst()) fail("no row for count: $sql")
            return it.getLong(0)
        }
    }

    private fun chainHasSQLiteException(error: Throwable?): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            if (cur is SQLiteException) return true
            cur = cur.cause
        }
        return false
    }
}
