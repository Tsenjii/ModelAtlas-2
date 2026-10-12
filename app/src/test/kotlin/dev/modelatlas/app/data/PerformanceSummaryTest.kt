package dev.modelatlas.app.data

import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-016 PerformanceSummary 聚合純函式驗證（驗收草案 1–7 對應命名；TRACEABILITY P11）。
 * 全為記憶體內純函式測試，不碰 DB／網路；測試樣本為本地構造值，非產品資料。
 */
class PerformanceSummaryTest {

    private fun sample(
        id: String,
        providerId: String = "p1",
        accountId: String = "a1",
        safeCredentialId: String = "cred-1",
        modelId: String = "m1",
        outcome: Outcome = Outcome.SUCCESS,
        httpStatus: Int? = 200,
        startedAt: Long = 1_000L,
        ttftMs: Long? = 100L,
        tps: Double? = 20.0,
        latencyMs: Long? = 500L,
        inputTokens: Long? = 100L,
        outputTokens: Long? = 50L,
        totalTokens: Long? = 150L,
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
        completedAt = if (outcome == Outcome.RUNNING) null else startedAt + 500L,
        ttftMs = ttftMs,
        tps = tps,
        totalLatencyMs = latencyMs,
        usage = Usage(inputTokens = inputTokens, outputTokens = outputTokens, totalTokens = totalTokens),
        httpStatus = httpStatus,
        outcome = outcome,
    )

    private val scope = PerformanceScope(providerId = "p1")
    private val window = PerformanceWindow(startInclusive = 0L, endExclusive = 10_000L)

    @Test
    fun successRateCountsOnlyTerminalOutcomes() {
        val rows = listOf(
            sample("ok1", outcome = Outcome.SUCCESS),
            sample("ok2", outcome = Outcome.SUCCESS),
            sample("bad", outcome = Outcome.SERVER, httpStatus = 500, ttftMs = 200L),
            sample("running", outcome = Outcome.RUNNING, httpStatus = null, ttftMs = 9_999L),
        )
        val summary = PerformanceSummaryAggregator.summarize(rows, scope, window)
        assertEquals(4, summary.requestCount)
        assertEquals(3, summary.decided)
        assertEquals(2, summary.successes)
        assertEquals(2.0 / 3.0, summary.successRate!!, 1e-9)
    }

    @Test
    fun windowBoundaryIsHalfOpenAndPinnedToClock() {
        val now = 10_000L
        val pinned = PerformanceWindow.endingAt(nowMillis = now, windowMillis = 5_000L)
        val rows = listOf(
            sample("before", startedAt = 4_999L),
            sample("atStart", startedAt = 5_000L),
            sample("inside", startedAt = 9_999L),
            sample("atEnd", startedAt = 10_000L),
        )
        val summary = PerformanceSummaryAggregator.summarize(rows, scope, pinned)
        assertEquals(2, summary.requestCount)
        assertEquals(9_999L, summary.lastObservedAt)
    }

    @Test
    fun emptyWindowReturnsNullNotZero() {
        val summary = PerformanceSummaryAggregator.summarize(emptyList(), scope, window)
        assertEquals(0, summary.requestCount)
        assertEquals(0, summary.decided)
        assertNull(summary.successRate)
        assertNull(summary.rate429)
        assertNull(summary.ttftMsP50)
        assertNull(summary.ttftMsP90)
        assertNull(summary.ttftMsP95)
        assertNull(summary.tpsP50)
        assertNull(summary.latencyMsP50)
        assertNull(summary.observedRpm)
        assertNull(summary.observedInputTpm)
        assertNull(summary.observedOutputTpm)
        assertNull(summary.observedTotalTpm)
        assertNull(summary.lastObservedAt)
    }

    @Test
    fun observedRatesAreNeverLabelledAsProviderLimits() {
        val banned = listOf("limit", "max")
        val fields = PerformanceSummary::class.java.declaredFields.filter { !it.isSynthetic }
        assertTrue(fields.isNotEmpty())
        for (field in fields) {
            val name = field.name.lowercase()
            for (token in banned) {
                assertFalse("field ${field.name} must not contain '$token'", name.contains(token))
            }
            val typeName = field.type.simpleName.lowercase()
            for (token in banned) {
                assertFalse("type ${field.type.simpleName} must not contain '$token'", typeName.contains(token))
            }
        }
        assertEquals("本地觀測", PerformanceSummaryAggregator.OBSERVED_LABEL)
    }

    @Test
    fun aggregationIsIsolatedByAccountAndCredential() {
        val rows = listOf(
            sample("s1", accountId = "a1", safeCredentialId = "cred-1"),
            sample("s2", accountId = "a2", safeCredentialId = "cred-1"),
            sample("s3", accountId = "a1", safeCredentialId = "cred-2"),
            sample("s4", accountId = "a1", safeCredentialId = "cred-1", outcome = Outcome.SERVER, httpStatus = 500),
        )
        val strict = PerformanceScope(providerId = "p1", accountId = "a1", safeCredentialId = "cred-1")
        val summary = PerformanceSummaryAggregator.summarize(rows, strict, window)
        assertEquals(2, summary.requestCount)
        assertEquals(2, summary.decided)
        assertEquals(1, summary.successes)
        val grouped = PerformanceSummaryAggregator.summarizeGrouped(rows, window)
        assertEquals(3, grouped.size)
        assertEquals(2, grouped[PerformanceScopeKey("p1", "a1", "cred-1", "m1", null, "CHAT_COMPLETIONS")]!!.requestCount)
    }

    @Test
    fun cancelledRequestsCountedExplicitlyNotAsSuccess() {
        val rows = listOf(
            sample("ok", outcome = Outcome.SUCCESS),
            sample("cx", outcome = Outcome.CANCELLED, httpStatus = null),
            sample("srv", outcome = Outcome.SERVER, httpStatus = 500),
        )
        val summary = PerformanceSummaryAggregator.summarize(rows, scope, window)
        assertEquals(3, summary.decided)
        assertEquals(1, summary.successes)
        assertEquals(1, summary.cancelled)
        assertEquals(1.0 / 3.0, summary.successRate!!, 1e-9)
        assertEquals(1, summary.httpErrorCount)
    }

    @Test
    fun aggregationNeverUsesLimitTruncation() {
        val rows = buildList {
            repeat(150) { i -> add(sample("ok$i", startedAt = i.toLong())) }
            repeat(100) { i -> add(sample("bad$i", outcome = Outcome.SERVER, httpStatus = 500, startedAt = 200L + i)) }
        }
        val wide = PerformanceWindow(startInclusive = 0L, endExclusive = 10_000L)
        val summary = PerformanceSummaryAggregator.summarize(rows, scope, wide)
        assertEquals(250, summary.requestCount)
        assertEquals(250, summary.decided)
        assertEquals(150, summary.successes)
        assertEquals(0.6, summary.successRate!!, 1e-9)
    }

    @Test
    fun quantilesIgnoreNullsAndRunning() {
        val rows = listOf(
            sample("a", ttftMs = 100L, tps = null, latencyMs = 300L),
            sample("b", ttftMs = 200L, tps = null, latencyMs = 300L),
            sample("c", ttftMs = 300L, tps = null, latencyMs = 300L),
            sample("d", outcome = Outcome.SERVER, httpStatus = 500, ttftMs = null, tps = null, latencyMs = null),
            sample("run", outcome = Outcome.RUNNING, httpStatus = null, ttftMs = 9_999L, tps = 9_999.0, latencyMs = 9_999L),
        )
        val summary = PerformanceSummaryAggregator.summarize(rows, scope, window)
        assertEquals(200.0, summary.ttftMsP50!!, 1e-9)
        assertEquals(280.0, summary.ttftMsP90!!, 1e-9)
        assertEquals(290.0, summary.ttftMsP95!!, 1e-9)
        assertNull(summary.tpsP50)
        assertNull(summary.tpsP90)
        assertEquals(300.0, summary.latencyMsP50!!, 1e-9)
    }

    @Test
    fun observedRpmAndTpmReflectWindowNotZeroFilled() {
        val minute = PerformanceWindow(startInclusive = 0L, endExclusive = 60_000L)
        val rows = listOf(
            sample("s1", startedAt = 1_000L, inputTokens = 60L, outputTokens = null, totalTokens = null),
            sample("s2", startedAt = 2_000L, inputTokens = 60L, outputTokens = null, totalTokens = null),
            sample("s3", startedAt = 3_000L, inputTokens = null, outputTokens = null, totalTokens = null),
        )
        val summary = PerformanceSummaryAggregator.summarize(rows, scope, minute)
        assertEquals(3.0, summary.observedRpm!!, 1e-9)
        assertEquals(120.0, summary.observedInputTpm!!, 1e-9)
        assertNull(summary.observedOutputTpm)
        assertNull(summary.observedTotalTpm)
        assertEquals(3_000L, summary.lastObservedAt)
    }

    @Test
    fun groupedSummariesKeepScopesSeparate() {
        val rows = listOf(
            sample("m1a", modelId = "m1", accountId = "a1"),
            sample("m1b", modelId = "m1", accountId = "a1", outcome = Outcome.SERVER, httpStatus = 500),
            sample("m2a", modelId = "m2", accountId = "a2"),
        )
        val grouped = PerformanceSummaryAggregator.summarizeGrouped(rows, window)
        assertEquals(2, grouped.size)
        val m1 = grouped[PerformanceScopeKey("p1", "a1", "cred-1", "m1", null, "CHAT_COMPLETIONS")]!!
        assertEquals(2, m1.requestCount)
        assertEquals(0.5, m1.successRate!!, 1e-9)
        val m2 = grouped[PerformanceScopeKey("p1", "a2", "cred-1", "m2", null, "CHAT_COMPLETIONS")]!!
        assertEquals(1, m2.requestCount)
        assertEquals(1.0, m2.successRate!!, 1e-9)
    }
}
