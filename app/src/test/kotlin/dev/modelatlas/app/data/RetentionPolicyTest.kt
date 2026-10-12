package dev.modelatlas.app.data

import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-017 RetentionPolicy／RollupStore／RetentionRunner 驗證（TRACEABILITY P11）。
 * D-2026-09-30-006（永久保留）：全部斷言「不刪除」；任何視窗／opt-in 皆為 no-op。
 * 全為記憶體內純函式測試，不碰 DB／排程；測試樣本為本地構造值，非產品資料。
 * 天數語意只以注入的 [kotlin.time.Duration] 表達，測試內亦無裸數字天數常數。
 */
class RetentionPolicyTest {

    private fun sample(
        id: String,
        providerId: String = "p1",
        accountId: String = "a1",
        safeCredentialId: String = "cred-1",
        modelId: String = "m1",
        outcome: Outcome = Outcome.SUCCESS,
        startedAt: Long = 100L,
    ): PerformanceSample = PerformanceSample(
        id = id,
        providerId = providerId,
        accountId = accountId,
        safeCredentialId = safeCredentialId,
        endpointId = "e1",
        providerModelId = modelId,
        canonicalModelId = null,
        group = null,
        operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.PLAYGROUND,
        requestStartedAt = startedAt,
        completedAt = if (outcome == Outcome.RUNNING) null else startedAt + 10L,
        usage = Usage(),
        httpStatus = if (outcome == Outcome.SUCCESS) 200 else null,
        outcome = outcome,
    )

    private val now = 1_000_000L
    private val span = 60_000L

    @Test
    fun defaultPolicyDeletesNothing() {
        val rows = listOf(
            sample("old-ok", startedAt = 100L),
            sample("old-bad", outcome = Outcome.SERVER, startedAt = 200L),
            sample("run", outcome = Outcome.RUNNING, startedAt = 300L),
        )
        val plan = RetentionRunner.planPrune("p1", rows, RetentionPolicy(), now)
        assertTrue(RetentionRunner.isNoOp(RetentionPolicy()))
        assertTrue(plan.idsToDelete.isEmpty())
        assertEquals(3, rows.size)
    }

    @Test
    fun nullWindowIsNoOp() {
        val policy = RetentionPolicy(rawSampleWindow = null, deletesRawEvidence = true)
        val rows = listOf(sample("old", startedAt = 100L))
        val plan = RetentionRunner.planPrune("p1", rows, policy, now)
        assertTrue(RetentionRunner.isNoOp(policy))
        assertTrue(plan.idsToDelete.isEmpty())
    }

    @Test
    fun rawEvidenceDeletionIsOptInAndDefaultsOff() {
        assertFalse(RetentionPolicy().deletesRawEvidence)
        val policy = RetentionPolicy(rawSampleWindow = 100_000L.milliseconds, deletesRawEvidence = false)
        val rows = listOf(sample("old", startedAt = 100L))
        val plan = RetentionRunner.planPrune("p1", rows, policy, now)
        assertTrue(RetentionRunner.isNoOp(policy))
        assertTrue(plan.idsToDelete.isEmpty())
        // D-2026-09-30-006：即便 opt-in 亦永久保留，一律 no-op。
        val optIn = RetentionPolicy(rawSampleWindow = 100_000L.milliseconds, deletesRawEvidence = true)
        val optInPlan = RetentionRunner.planPrune("p1", rows, optIn, now)
        assertTrue(RetentionRunner.isNoOp(optIn))
        assertTrue(optInPlan.idsToDelete.isEmpty())
    }

    @Test
    fun defaultPolicyHasNullWindowAndEmptyBuckets() {
        val policy = RetentionPolicy()
        assertNull(policy.rawSampleWindow)
        assertTrue(policy.rollupBuckets.isEmpty())
        assertFalse(policy.deletesRawEvidence)
    }

    @Test
    fun pruneIsScopedPerProviderAndNeverGlobal() {
        // D-2026-09-30-006（永久保留）：即便帶視窗＋opt-in，兩 provider 皆不刪；
        // 作用域標註仍保留（p1 計畫不含 p2 樣本），但 idsToDelete 恆為空。
        val policy = RetentionPolicy(rawSampleWindow = 100_000L.milliseconds, deletesRawEvidence = true)
        val rows = listOf(
            sample("p1-old", providerId = "p1", startedAt = 100L),
            sample("p2-old", providerId = "p2", startedAt = 100L),
        )
        val p1Plan = RetentionRunner.planPrune("p1", rows, policy, now)
        assertTrue(p1Plan.idsToDelete.isEmpty())
        assertEquals("p1", p1Plan.providerId)
        val p2Plan = RetentionRunner.planPrune("p2", rows, policy, now)
        assertTrue(p2Plan.idsToDelete.isEmpty())
        assertEquals("p2", p2Plan.providerId)
        assertTrue(RetentionRunner.isNoOp(policy))
    }

    @Test
    fun rollupNeverDeletesRunningOrTerminalEvidenceRows() {
        // D-2026-09-30-006（永久保留）：RUNNING／evidence／普通舊樣本一律保留，不刪任何列。
        val policy = RetentionPolicy(rawSampleWindow = 100_000L.milliseconds, deletesRawEvidence = true)
        val rows = listOf(
            sample("old-run", outcome = Outcome.RUNNING, startedAt = 100L),
            sample("old-evidence", outcome = Outcome.SERVER, startedAt = 200L),
            sample("old-plain", outcome = Outcome.SERVER, startedAt = 300L),
        )
        val plan = RetentionRunner.planPrune("p1", rows, policy, now, evidenceSampleIds = setOf("old-evidence"))
        assertTrue(plan.idsToDelete.isEmpty())
        assertEquals(1, plan.retainedRunning)
        assertEquals(1, plan.retainedEvidence)
        assertTrue(RetentionRunner.isNoOp(policy))
    }

    @Test
    fun rollupPreservesAggregatesWhileRawIsPruned() {
        val old = listOf(
            sample("o1", startedAt = 1_000L),
            sample("o2", outcome = Outcome.SERVER, startedAt = 2_000L),
            sample("o3", startedAt = 3_000L),
        )
        val buckets = RollupStore.buildBuckets(old, span)
        assertEquals(3, RollupStore.requestCount(buckets))
        assertEquals(3, RollupStore.decidedCount(buckets))
        assertEquals(2, RollupStore.successCount(buckets))
        // D-2026-09-30-006（永久保留）：raw 永不剪除，桶僅為聚合查詢效能抽象。
        val policy = RetentionPolicy(rawSampleWindow = 100_000L.milliseconds, deletesRawEvidence = true)
        val plan = RetentionRunner.planPrune("p1", old, policy, now)
        assertTrue(plan.idsToDelete.isEmpty())
        assertTrue(RetentionRunner.isNoOp(policy))
        // 桶聚合在永久保留下仍完整可用。
        assertEquals(3, RollupStore.requestCount(buckets))
        assertEquals(2, RollupStore.successCount(buckets))
    }

    @Test
    fun cutoffIsHalfOpenAndPinnedToClock() {
        // D-2026-09-30-006（永久保留）：cutoff 不再觸發刪除；恰落 cutoff／早於 cutoff 者皆保留。
        val window = 100_000L.milliseconds
        val policy = RetentionPolicy(rawSampleWindow = window, deletesRawEvidence = true)
        val cutoff = now - window.inWholeMilliseconds
        val rows = listOf(
            sample("just-before", startedAt = cutoff - 1L),
            sample("at-cutoff", startedAt = cutoff),
        )
        val plan = RetentionRunner.planPrune("p1", rows, policy, now)
        assertTrue(plan.idsToDelete.isEmpty())
        assertTrue(RetentionRunner.isNoOp(policy))
    }

    @Test
    fun bucketsIsolateScopesPerAccountAndCredential() {
        val rows = listOf(
            sample("a1", accountId = "a1", startedAt = 1_000L),
            sample("a2", accountId = "a2", startedAt = 1_000L),
            sample("c2", safeCredentialId = "cred-2", startedAt = 1_000L),
        )
        val buckets = RollupStore.buildBuckets(rows, span)
        assertEquals(3, buckets.size)
        assertEquals(1, buckets.count { it.accountId == "a2" })
        assertEquals(1, buckets.count { it.safeCredentialId == "cred-2" })
        for (bucket in buckets) {
            assertEquals(0L, bucket.bucketStartInclusive)
            assertEquals(span, bucket.bucketEndExclusive)
            assertEquals(1.0, bucket.successRate!!, 1e-9)
        }
    }

    @Test
    fun explicitWindowWithOptInPrunesOnlyOldDecidedSamples() {
        // D-2026-09-30-006（永久保留）：即便帶視窗＋opt-in，舊 decided／RUNNING 皆不刪，恆為 no-op。
        val window = 100_000L.milliseconds
        val policy = RetentionPolicy(rawSampleWindow = window, deletesRawEvidence = true)
        val cutoff = now - window.inWholeMilliseconds
        val rows = listOf(
            sample("old-decided", startedAt = cutoff - 10L),
            sample("recent-decided", startedAt = cutoff + 10L),
            sample("old-running", outcome = Outcome.RUNNING, startedAt = cutoff - 10L),
        )
        val plan = RetentionRunner.planPrune("p1", rows, policy, now)
        assertTrue(plan.idsToDelete.isEmpty())
        assertTrue(RetentionRunner.isNoOp(policy))
    }
}
