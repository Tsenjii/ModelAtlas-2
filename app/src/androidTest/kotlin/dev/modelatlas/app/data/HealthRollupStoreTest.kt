package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §11 health rollup 落庫層（PROV-006）：讀 samples 聚合、寫 providers observed 快照；
 * official／observed 分離、無樣本不清空 last-good。
 */
@RunWith(AndroidJUnit4::class)
class HealthRollupStoreTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: HealthRollupStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = HealthRollupStore(db)
        runBlocking {
            db.providerDao().insert(
                ProviderEntity(providerId = "p1", name = "P1", officialHealth = "DEGRADED"),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun sample(id: String, model: String, outcome: String, status: Int?, at: Long) {
        db.sampleDao().upsert(
            SampleEntity(
                id = id,
                providerId = "p1",
                accountId = "a1",
                safeCredentialId = "c1",
                endpointId = "e1",
                providerModelId = model,
                outcome = outcome,
                httpStatus = status,
                requestStartedAt = at,
            ),
        )
    }

    @Test
    fun observedHealthRollsUpFromSamples() = runBlocking {
        repeat(10) { i -> sample("s$i", "m1", "SUCCESS", 200, 1000L + i) }
        assertEquals(HealthState.HEALTHY, store.observedHealth("p1"))
        assertEquals(mapOf("m1" to HealthState.HEALTHY), store.modelHealth("p1"))
    }

    @Test
    fun refreshWritesObservedSnapshotButOfficialStays() = runBlocking {
        repeat(4) { i -> sample("ok$i", "m1", "SUCCESS", 200, 1000L) }
        sample("bad", "m1", "SERVER", 500, 1000L)
        // 4/5 = 0.8 → UNSTABLE
        assertEquals(HealthState.UNSTABLE, store.refreshObserved("p1", nowMillis = 5000L))
        val row = db.providerDao().getById("p1")!!
        assertEquals("UNSTABLE", row.observedHealth)
        assertEquals(5000L, row.observedHealthAt)
        // official 不被 rollup 觸碰。
        assertEquals("DEGRADED", row.officialHealth)
        assertEquals(0L, row.officialHealthAt)
    }

    @Test
    fun noSamplesIsUnknownAndDoesNotClearLastGood() = runBlocking {
        assertEquals(HealthState.UNKNOWN, store.observedHealth("p1"))
        // 先寫入 last-good
        db.providerDao().updateObservedHealth("p1", "HEALTHY", 111L)
        assertEquals(HealthState.UNKNOWN, store.refreshObserved("p1", nowMillis = 9999L))
        val row = db.providerDao().getById("p1")!!
        assertEquals("HEALTHY", row.observedHealth)
        assertEquals(111L, row.observedHealthAt)
    }

    @Test
    fun providerHealthKeepsOfficialAndObservedSeparate() = runBlocking {
        repeat(10) { i -> sample("s$i", "m1", "SUCCESS", 200, 1000L) }
        val health = store.providerHealth("p1")
        assertEquals("p1", health.providerId)
        assertEquals(HealthState.DEGRADED, health.official)
        assertEquals(HealthState.HEALTHY, health.observed)
        assertNotNull(health)
    }

    // =====================================================================
    // PK-11 health-hook M2 (commit e608dd5 + M2; spec v2 §4):
    // - terminal non-RUNNING/non-CANCELLED sample with completedAt==null is
    //   SKIPPED by the save() hook — no device wall-clock fabrication;
    //   last-good observedHealth/observedHealthAt stay byte-identical.
    // - faulty refresh never masks save: the save transaction commits BEFORE
    //   the hook fires, so sample+evidence persist and nothing escapes save.
    // =====================================================================

    // M2: null completedAt skipped (INTERRUPTED terminal leg) — last-good
    // preserved byte-identical; official untouched.
    @Test
    fun nullCompletedAtTerminalSampleIsSkippedAndLastGoodPreserved() = runBlocking {
        db.providerDao().updateObservedHealth("p1", "HEALTHY", 111L)
        val repo = AtlasRepository(db)
        repo.save(interruptSample("hook-null-at", requestStartedAt = 5_000L))
        assertEquals("INTERRUPTED", db.sampleDao().getById("hook-null-at")!!.outcome)
        val row = db.providerDao().getById("p1")!!
        assertEquals("HEALTHY", row.observedHealth)
        assertEquals(111L, row.observedHealthAt)
        assertEquals("DEGRADED", row.officialHealth)
        assertEquals(0L, row.officialHealthAt)
    }

    // T10: faulty refresh (trigger abort on the providers UPDATE) must not mask
    // save: exception swallowed at the hook, save returns normally, sample+
    // evidence persisted (the save transaction commits BEFORE the hook fires),
    // last-good kept.
    @Test
    fun faultyRefreshDoesNotMaskSaveAndLastGoodSurvives() = runBlocking {
        db.providerDao().updateObservedHealth("p1", "HEALTHY", 111L)
        db.sampleDao().upsert(
            SampleEntity(
                id = "hook-t10-history", providerId = "p1", accountId = "a1",
                safeCredentialId = "c1", endpointId = "e1", providerModelId = "m1",
                requestStartedAt = 1_000L, completedAt = 1_100L,
                outcome = "SUCCESS", httpStatus = 200,
            ),
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER hr_t10_abort BEFORE UPDATE ON providers " +
                "WHEN NEW.providerId='p1' BEGIN SELECT RAISE(ABORT,'injected'); END",
        )
        try {
            val repo = AtlasRepository(db)
            // Non-null completedAt is REQUIRED: with null the hook skips before
            // refreshObserved (M2) and the abort trigger would never fire —
            // the test would be vacuous (independent review finding, MED).
            repo.save(
                interruptSample("hook-t10-err", requestStartedAt = 2_000L).copy(
                    outcome = Outcome.SERVER,
                    httpStatus = 500,
                    completedAt = 2_100L,
                ),
            )
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS hr_t10_abort")
        }
        assertEquals("SERVER", db.sampleDao().getById("hook-t10-err")!!.outcome)
        assertNotNull("evidence must persist", db.evidenceDao().byRequest("hook-t10-err"))
        val row = db.providerDao().getById("p1")!!
        assertEquals("HEALTHY", row.observedHealth)
        assertEquals(111L, row.observedHealthAt)
        // setUp fixture pins officialHealth=DEGRADED; the hook never touches official.
        assertEquals("DEGRADED", row.officialHealth)
    }

    private fun interruptSample(
        id: String,
        requestStartedAt: Long,
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
        requestStartedAt = requestStartedAt,
        streaming = false,
        completedAt = null,
        outcome = Outcome.INTERRUPTED,
    )

    @Test
    fun sinceWindowExcludesOldSamples() = runBlocking {
        sample("old", "m1", "SUCCESS", 200, 1000L)
        sample("new", "m1", "SERVER", 500, 9000L)
        // 全期 1/2 = 0.5 → UNSTABLE
        assertEquals(HealthState.UNSTABLE, store.observedHealth("p1", sinceMillis = 0L))
        // 只取新樣本 → 0/1 → OFFLINE
        assertEquals(HealthState.OFFLINE, store.observedHealth("p1", sinceMillis = 5000L))
    }
}
