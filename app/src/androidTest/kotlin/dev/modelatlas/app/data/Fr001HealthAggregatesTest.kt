package dev.modelatlas.app.data

// FR-001 device test（已落地 repo；實跑與 acceptance 由同 SHA CI 證明）。
// Real-Room test locking SampleDao.healthAggregates cancel exclusion
// (FR-001, D-009, Intelligence Contract §4.1/§4.2, PRODUCT_SPEC §11/§18).
//
// Landed SQL predicate (same commit, Daos.kt):
//   decided / rateLimited / avgTtftMs / avgTps / avgLatencyMs legs use
//   `outcome != 'RUNNING' AND outcome != 'CANCELLED'`.
//
// Policy bounds: only user RUNNING and user CANCELLED leave the decided base.
// No invalid/content-policy outcome is invented (Outcome has none); INTERRUPTED,
// AUTH, RATE_LIMIT, TIMEOUT, SERVER, NETWORK, PARSER, MODEL_UNAVAILABLE,
// CREDENTIAL_SCOPE, OTHER all remain real decided outcomes/failures.
// Provider-reported perf percent 0..100 (provider_perf_facts) and MyUptime are
// locked as untouched by local health aggregation.
// Window args mirror production exactly: healthAggregates(providerId, 0L) and
// healthAggregates(providerId, nowMillis - PERFORMANCE_WINDOW_MILLIS) with
// `startedAt >= :sinceMillis`; myUptimeSamples half-open [startInclusive, endExclusive).

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.EvidenceEntity
import dev.modelatlas.app.data.HealthRollup
import dev.modelatlas.app.data.HealthRollupStore
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.ProviderPerfFactEntity
import dev.modelatlas.app.data.ProviderPerfKind
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.app.ui.ProviderDetailLoader
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Fr001HealthAggregatesTest {

    private lateinit var db: AtlasDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // V1: 9 SUCCESS + 1 CANCELLED -> decided=9, successes=9, local ratio 1.0.
    @Test
    fun nineSuccessPlusOneCancelledDecidesNineAndLocalRatioIsOne() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        val base = BASE
        repeat(9) { i ->
            db.sampleDao().upsert(
                sample(
                    id = "ok-$i", startedAt = base + i * 1_000L, outcome = "SUCCESS", http = 200,
                    tps = 10.0 + i, ttftMs = 100L + i, latencyMs = 1_000L + i,
                ),
            )
        }
        // Cancelled row carries cancel-elapsed latency/TTFT: must not pollute AVGs.
        db.sampleDao().upsert(
            sample(
                id = "cancelled-1", startedAt = base + 99_000L, outcome = "CANCELLED",
                http = null, tps = null, ttftMs = 50L, latencyMs = 9_000L,
            ),
        )

        val rows = db.sampleDao().healthAggregates(P, 0L)
        val row = rows.single { it.modelId == M }
        // PROPOSED predicate: decided excludes RUNNING and CANCELLED. Current HEAD = 10.
        assertEquals(10, row.total) // raw request count includes CANCELLED (honest sample count)
        assertEquals(9, row.decided)
        assertEquals(9, row.successes)
        assertEquals(0, row.rateLimited)
        assertEquals(14.0, row.avgTps!!, 1e-9) // (10..18)/9; null-ignored AVG, same pre/post
        assertEquals(104.0, row.avgTtftMs!!, 1e-9) // (100..108)/9; current HEAD 98.6 (cancelled 50 included)
        assertEquals(1004.0, row.avgLatencyMs!!, 1e-9) // (1000..1008)/9; current HEAD 1803.6 (cancelled 9000 included)
        assertEquals(base + 99_000L, row.lastObservedAt)

        assertEquals(HealthState.HEALTHY, HealthRollup.observedHealth(rows))

        val perf = ProviderDetailLoader.performanceOf(rows, rows)
        requireNotNull(perf)
        assertEquals(1.0, perf.success24h!!, 1e-9) // local ratio 1.0
        assertEquals("100%", ProviderDetailLoader.successRatePercentLabel(perf.success24h))
        assertEquals(10L, perf.requestCount) // raw request count, not decided
        assertEquals(1004L, perf.latencyMs) // AVG local, cancel-excluded

        // Local observation never writes provider-reported official health.
        assertEquals("UNKNOWN", db.providerDao().getById(P)!!.officialHealth)
    }

    // V2: SUCCESS/NETWORK/429 are real failures retained in the denominator.
    @Test
    fun successNetworkRateLimitRealFailuresStayInDenominator() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        val base = BASE
        db.sampleDao().upsert(sample("s1", base + 1_000L, "SUCCESS", http = 200))
        db.sampleDao().upsert(sample("s2", base + 2_000L, "SUCCESS", http = 200))
        db.sampleDao().upsert(sample("net", base + 3_000L, "NETWORK", http = null))
        db.sampleDao().upsert(sample("rl", base + 4_000L, "RATE_LIMIT", http = 429))
        db.sampleDao().upsert(sample("run", base + 5_000L, "RUNNING", http = null))
        db.sampleDao().upsert(sample("cx", base + 6_000L, "CANCELLED", http = null))

        val rows = db.sampleDao().healthAggregates(P, 0L)
        val row = rows.single { it.modelId == M }
        assertEquals(6, row.total)
        assertEquals(4, row.decided) // SUCCESS,SUCCESS,NETWORK,RATE_LIMIT; current HEAD = 5
        assertEquals(2, row.successes)
        assertEquals(1, row.rateLimited)
        // 2/4 = 0.5 -> UNSTABLE boundary; real failures keep the ratio honest.
        assertEquals(HealthState.UNSTABLE, HealthRollup.observedHealth(rows))
        val perf = ProviderDetailLoader.performanceOf(rows, rows)
        requireNotNull(perf)
        assertEquals(0.5, perf.success24h!!, 1e-9)
        assertEquals("50%", ProviderDetailLoader.successRatePercentLabel(perf.success24h))
    }

    // V3: cancelled-only -> decided=0 rendered Unknown, never 0 / OFFLINE.
    @Test
    fun cancelledOnlyDecidesZeroAndStaysUnknownNotZero() = runBlocking {
        db.providerDao().insert(
            ProviderEntity(providerId = P, name = "P1", observedHealth = "DEGRADED", observedHealthAt = 1L),
        )
        val base = BASE
        db.sampleDao().upsert(sample("cx1", base + 1_000L, "CANCELLED"))
        db.sampleDao().upsert(sample("cx2", base + 2_000L, "CANCELLED"))

        val rows = db.sampleDao().healthAggregates(P, 0L)
        val row = rows.single { it.modelId == M }
        assertEquals(2, row.total)
        assertEquals(0, row.decided) // current HEAD = 2
        assertEquals(0, row.successes)
        assertEquals(0, row.rateLimited)
        assertEquals(HealthState.UNKNOWN, HealthRollup.observedHealth(rows))
        assertNull(ProviderDetailLoader.performanceOf(rows, rows)) // no 0.0 ratio fallback
        assertNull(ProviderDetailLoader.successRatePercentLabel(null)) // UI shows Unknown

        // Unknown never writes back: last-good observed health is preserved.
        val state = HealthRollupStore(db).refreshObserved(P, NOW, 0L)
        assertEquals(HealthState.UNKNOWN, state)
        assertEquals("DEGRADED", db.providerDao().getById(P)!!.observedHealth)
        assertEquals("UNKNOWN", db.providerDao().getById(P)!!.officialHealth)
    }

    // V4: RUNNING never counted; CANCELLED with 429 must not count as rateLimited.
    @Test
    fun runningNeverCountsAndCancelled429DoesNotDemoteHealth() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        val base = BASE
        db.sampleDao().upsert(
            sample(
                id = "run", startedAt = base + 1_000L, outcome = "RUNNING",
                http = null, tps = 1.0, ttftMs = 5L, latencyMs = 5_000L,
            ),
        )
        db.sampleDao().upsert(
            sample(
                id = "ok", startedAt = base + 2_000L, outcome = "SUCCESS",
                http = 200, tps = 20.0, ttftMs = 100L, latencyMs = 1_000L,
            ),
        )
        // User stopped after a 429 response: rateLimited base is PROPOSED guarded.
        db.sampleDao().upsert(sample("cx429", base + 3_000L, "CANCELLED", http = 429))

        val rows = db.sampleDao().healthAggregates(P, 0L)
        val row = rows.single { it.modelId == M }
        assertEquals(3, row.total)
        assertEquals(1, row.decided) // current HEAD = 2
        assertEquals(1, row.successes)
        assertEquals(0, row.rateLimited) // PROPOSED base; current HEAD = 1 -> false DEGRADED
        assertEquals(100.0, row.avgTtftMs!!, 1e-9)
        assertEquals(20.0, row.avgTps!!, 1e-9)
        assertEquals(1_000.0, row.avgLatencyMs!!, 1e-9)
        assertEquals(HealthState.HEALTHY, HealthRollup.observedHealth(rows))
    }

    // V5a: healthAggregates stays provider-exact + model-grouped + window-exact.
    // NOTE (honest contract): healthAggregates has NO account/credential/endpoint/
    // operation dimension (SQL GROUP BY providerId, modelId), so account decoys
    // merge here BY DESIGN; exact account/key/endpoint/group isolation is locked
    // on the MyUptime path in V5b below, not invented on this query.
    @Test
    fun providerModelAndWindowDecoysStayOutOfTheSelectedAggregate() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        db.providerDao().insert(ProviderEntity(providerId = P2, name = "P2"))
        val now = NOW
        val since = now - ProviderDetailLoader.PERFORMANCE_WINDOW_MILLIS
        db.sampleDao().upsert(sample("inside", now - 3_600_000L, "SUCCESS", http = 200, provider = P))
        db.sampleDao().upsert(sample("boundary", since, "SUCCESS", http = 200, provider = P)) // >= inclusive
        db.sampleDao().upsert(sample("old", since - 1L, "SUCCESS", http = 200, provider = P))
        db.sampleDao().upsert(sample("other-model", now - 3_600_000L, "SUCCESS", http = 200, provider = P, model = M2))
        db.sampleDao().upsert(sample("other-provider", now - 3_600_000L, "SUCCESS", http = 200, provider = P2))

        val windowed = db.sampleDao().healthAggregates(P, since)
        assertEquals(2, windowed.size) // m1 + m2 groups only, no p2 row
        assertTrue(windowed.all { it.providerId == P })
        val m1Window = windowed.single { it.modelId == M }
        assertEquals(2, m1Window.total) // inside + boundary; old excluded by startedAt
        assertEquals(2, m1Window.decided)
        assertEquals(2, m1Window.successes)
        assertEquals(1, windowed.single { it.modelId == M2 }.total) // separate group, not merged

        val allTime = db.sampleDao().healthAggregates(P, 0L)
        assertEquals(3, allTime.single { it.modelId == M }.total) // old included all-time
        assertEquals(1, db.sampleDao().healthAggregates(P2, since).single { it.modelId == M }.total)
    }

    // V5b: MyUptime exact six-dim scope + CANCELLED exclusion stay untouched.
    @Test
    fun uptimeExactScopeDecoysExcludedAndInterruptedRetained() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        val now = NOW
        val start = now - 3_600_000L
        val end = now
        db.sampleDao().upsert(sample("uptime-success", start, "SUCCESS", http = 200))
        db.sampleDao().upsert(sample("uptime-window-start", start, "SUCCESS", http = 200)) // inclusive
        db.sampleDao().upsert(sample("uptime-interrupted", now - 1_800_000L, "INTERRUPTED"))
        db.sampleDao().upsert(sample("uptime-other-account", now - 1_800_000L, "SUCCESS", http = 200, account = "a2"))
        db.sampleDao().upsert(sample("uptime-other-key", now - 1_800_000L, "SUCCESS", http = 200, credential = "c2"))
        db.sampleDao().upsert(sample("uptime-other-endpoint", now - 1_800_000L, "SUCCESS", http = 200, endpoint = "e2"))
        db.sampleDao().upsert(sample("uptime-other-group", now - 1_800_000L, "SUCCESS", http = 200, group = "vip"))
        db.sampleDao().upsert(sample("uptime-cancelled", now - 1_800_000L, "CANCELLED"))
        db.sampleDao().upsert(sample("uptime-window-end", end, "SUCCESS", http = 200)) // end exclusive

        val mine = db.sampleDao().myUptimeSamples(P, A, C, M, E, null, start, end)
        assertEquals(
            setOf("uptime-success", "uptime-window-start", "uptime-interrupted"),
            mine.map { it.id }.toSet(),
        ) // CANCELLED excluded; INTERRUPTED retained; account/key/endpoint/group/window decoys out.
        val vip = db.sampleDao().myUptimeSamples(P, A, C, M, E, "vip", start, end)
        assertEquals(listOf("uptime-other-group"), vip.map { it.id }) // null group is not a wildcard
    }

    // V6: provider-reported percent 0..100 and official health untouched by local aggregation.
    @Test
    fun providerPerfPercentAndOfficialHealthRemainUntouched() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        val now = NOW
        db.evidenceDao().insert(
            EvidenceEntity("evidence-perf", "request-perf", P, M, "OK", status = 200, timestamp = now, source = "newapi/api/perf-metrics"),
        )
        db.providerPerfDao().insertFact(
            ProviderPerfFactEntity(
                evidenceId = "evidence-perf",
                scopeKey = "fixture:summary",
                kind = ProviderPerfKind.SUMMARY.name,
                observedAt = now,
                empty = false,
                status = "OK",
                providerVersion = null,
                httpStatus = 200,
                modelId = M,
                groupName = null,
                hours = 168,
                accountId = null,
                credentialId = null,
                avgTtftMs = 120L,
                avgLatencyMs = 800L,
                successRate = 87.5, // provider-reported percent, saved verbatim (never /100)
                avgTps = 42.0,
                recentSuccessRates = "[90.0,85.0,87.0]",
                seriesSchema = null,
                seriesJson = null,
                seriesPointCount = null,
            ),
        )
        db.sampleDao().upsert(sample("local-ok", now - 1_000L, "SUCCESS", http = 200))

        // Run every local path this slice touches.
        val rows = db.sampleDao().healthAggregates(P, 0L)
        HealthRollup.observedHealth(rows)
        requireNotNull(ProviderDetailLoader.performanceOf(rows, rows))
        val state = HealthRollupStore(db).refreshObserved(P, now, 0L)
        assertEquals(HealthState.HEALTHY, state)

        val fact = db.providerPerfDao().fact("evidence-perf")
        requireNotNull(fact)
        assertEquals(87.5, fact.successRate!!, 0.0) // still percent 0..100, not overwritten/rescaled
        assertEquals(M, fact.modelId)
        assertEquals(120L, fact.avgTtftMs)
        assertTrue(db.providerPerfDao().headsByKind(ProviderPerfKind.SUMMARY.name).isEmpty()) // no head writes
        assertEquals("UNKNOWN", db.providerDao().getById(P)!!.officialHealth) // official = official source only
        assertEquals("HEALTHY", db.providerDao().getById(P)!!.observedHealth) // local writes observed only
    }

    // =====================================================================
    // PK-11 health-hook — save() path (commit e608dd5 + M2; spec v2 §4 T1-T7).
    // Terminal non-RUNNING/non-CANCELLED samples with NON-null completedAt fire
    // HealthRollupStore.refreshObserved AFTER the save transaction (best-effort,
    // never masks save); RUNNING/CANCELLED short-circuit on outcome (before the
    // completedAt check); completedAt==null rows are skipped (M2: no device
    // wall-clock fabrication). Each case pre-states providers.observedHealth to
    // pin last-good; official stays official-source-only (T12 carried per case).
    // =====================================================================

    // T1: terminal SUCCESS via repo.save -> hook fires, observed moves to
    // HEALTHY at sample.completedAt (never device-now); official untouched.
    // The hook's aggregate sees the just-committed sample (1/1 -> HEALTHY,
    // not the UNKNOWN of a zero-decided pre-state) -> post-save-tx proof.
    @Test
    fun hookFiresForTerminalSampleAndMovesObservedSnapshotAtCompletedAt() = runBlocking {
        db.providerDao().insert(
            ProviderEntity(providerId = P, name = "P1", observedHealth = "DEGRADED", observedHealthAt = 1L),
        )
        val repo = AtlasRepository(db)
        val completedAt = BASE + 5_000L
        repo.save(hookSample("hook-t1", BASE, completedAt, Outcome.SUCCESS, http = 200))
        val row = db.providerDao().getById(P)!!
        assertEquals("HEALTHY", row.observedHealth)
        assertEquals(completedAt, row.observedHealthAt)
        assertEquals("UNKNOWN", row.officialHealth)
    }

    // T2: 9 seeded SUCCESS + 1 SERVER via save = 10 decided / 9 success
    // = 0.90 -> DEGRADED written at the boundary, at sample.completedAt.
    @Test
    fun hookWritesDegradedAtThe090Boundary() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        val base = BASE
        repeat(9) { i -> db.sampleDao().upsert(sample("t2-ok-$i", base + i * 1_000L, "SUCCESS", http = 200)) }
        val repo = AtlasRepository(db)
        val completedAt = base + 9_000L
        repo.save(hookSample("t2-err", base + 9_000L, completedAt, Outcome.SERVER, http = 500))
        val row = db.providerDao().getById(P)!!
        assertEquals("DEGRADED", row.observedHealth)
        assertEquals(completedAt, row.observedHealthAt)
        assertEquals("UNKNOWN", row.officialHealth)
    }

    // T3: 1 SUCCESS + 1 SERVER = 1/2 = 0.50 -> UNSTABLE written.
    @Test
    fun hookWritesUnstableAtThe050Boundary() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        db.sampleDao().upsert(sample("t3-ok", BASE + 1_000L, "SUCCESS", http = 200))
        val repo = AtlasRepository(db)
        val completedAt = BASE + 2_000L
        repo.save(hookSample("t3-err", BASE + 2_000L, completedAt, Outcome.SERVER, http = 500))
        val row = db.providerDao().getById(P)!!
        assertEquals("UNSTABLE", row.observedHealth)
        assertEquals(completedAt, row.observedHealthAt)
    }

    // T4: 0/2 failures in the window -> OFFLINE written (pipeline is null in
    // this harness, so the OFFLINE branch stays a pure rollup write).
    @Test
    fun hookWritesOfflineWhenNoSuccessRemains() = runBlocking {
        db.providerDao().insert(
            ProviderEntity(providerId = P, name = "P1", observedHealth = "UNSTABLE", observedHealthAt = 1L),
        )
        db.sampleDao().upsert(sample("t4-err-1", BASE + 1_000L, "SERVER", http = 500))
        val repo = AtlasRepository(db)
        val completedAt = BASE + 2_000L
        repo.save(hookSample("t4-err-2", BASE + 2_000L, completedAt, Outcome.SERVER, http = 500))
        val row = db.providerDao().getById(P)!!
        assertEquals("OFFLINE", row.observedHealth)
        assertEquals(completedAt, row.observedHealthAt)
        assertEquals("UNKNOWN", row.officialHealth)
    }

    // T5: 429 blocks HEALTHY — 20 SUCCESS + 1 RATE_LIMIT(429) = 20/21 = 0.952
    // (>= HEALTHY threshold) but rateLimited=1 keeps it DEGRADED.
    @Test
    fun hook429RateLimitedBlocksHealthy() = runBlocking {
        db.providerDao().insert(ProviderEntity(providerId = P, name = "P1"))
        repeat(20) { i -> db.sampleDao().upsert(sample("t5-ok-$i", BASE + i * 1_000L, "SUCCESS", http = 200)) }
        val repo = AtlasRepository(db)
        val completedAt = BASE + 20_000L
        repo.save(hookSample("t5-rl", BASE + 20_000L, completedAt, Outcome.RATE_LIMIT, http = 429))
        val row = db.providerDao().getById(P)!!
        assertEquals("DEGRADED", row.observedHealth)
        assertEquals(completedAt, row.observedHealthAt)
    }

    // T6: CANCELLED with NON-null completedAt -> the outcome gate short-circuits
    // BEFORE the completedAt check; hook never fires; last-good preserved.
    @Test
    fun hookNeverFiresForCancelledSampleEvenWithNonNullCompletedAt() = runBlocking {
        db.providerDao().insert(
            ProviderEntity(providerId = P, name = "P1", observedHealth = "DEGRADED", observedHealthAt = 1L),
        )
        val repo = AtlasRepository(db)
        repo.save(hookSample("t6-cancel", BASE + 1_000L, BASE + 9_000L, Outcome.CANCELLED))
        val row = db.providerDao().getById(P)!!
        assertEquals("DEGRADED", row.observedHealth)
        assertEquals(1L, row.observedHealthAt)
        assertEquals("UNKNOWN", row.officialHealth)
    }

    // T7: RUNNING (completedAt null) -> hook never fires; last-good preserved.
    @Test
    fun hookNeverFiresForRunningSample() = runBlocking {
        db.providerDao().insert(
            ProviderEntity(providerId = P, name = "P1", observedHealth = "DEGRADED", observedHealthAt = 1L),
        )
        val repo = AtlasRepository(db)
        repo.save(hookSample("t7-running", BASE + 1_000L, null, Outcome.RUNNING))
        val row = db.providerDao().getById(P)!!
        assertEquals("DEGRADED", row.observedHealth)
        assertEquals(1L, row.observedHealthAt)
    }

    // ---- concrete fixture helpers (real SampleEntity shape; no placeholders) ----

    private fun sample(
        id: String,
        startedAt: Long,
        outcome: String,
        http: Int? = null,
        tps: Double? = null,
        ttftMs: Long? = null,
        latencyMs: Long? = null,
        provider: String = P,
        account: String = A,
        credential: String = C,
        endpoint: String = E,
        model: String = M,
        group: String? = null,
    ) = SampleEntity(
        id = id,
        providerId = provider,
        accountId = account,
        safeCredentialId = credential,
        endpointId = endpoint,
        providerModelId = model,
        group = group,
        requestStartedAt = startedAt, // column name is `startedAt`
        completedAt = if (outcome == "RUNNING") null else startedAt,
        outcome = outcome,
        httpStatus = http,
        tps = tps,
        ttftMs = ttftMs,
        totalLatencyMs = latencyMs,
    )

    /** Hook-triggering sample via the real ProductionSample shape (repo.save path). */
    private fun hookSample(
        id: String,
        startedAt: Long,
        completedAt: Long?,
        outcome: Outcome,
        http: Int? = null,
    ) = PerformanceSample(
        id = id,
        providerId = P,
        accountId = A,
        safeCredentialId = C,
        endpointId = E,
        providerModelId = M,
        canonicalModelId = null,
        group = null,
        operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.PLAYGROUND,
        requestStartedAt = startedAt,
        streaming = false,
        completedAt = completedAt,
        outcome = outcome,
        httpStatus = http,
        priceState = "FREE_NOW",
        quotaState = "UNKNOWN",
    )

    private companion object {
        const val P = "p1"
        const val P2 = "p2"
        const val M = "m1"
        const val M2 = "m2"
        const val A = "a1"
        const val C = "c1"
        const val E = "e1"
        const val BASE = 1_700_000_000_000L
        const val NOW = 1_700_000_100_000L
    }
}
