package dev.modelatlas.app.data

import dev.modelatlas.core.domain.HealthState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §11 health rollup 純函式邊界（MODEL-003 追補／PROV-006）：
 * 未知不猜、official 不經此、429 為不穩定訊號、合併為加總非平均。
 */
class HealthRollupTest {

    private fun row(
        modelId: String = "m",
        decided: Int,
        successes: Int,
        rateLimited: Int = 0,
        lastObservedAt: Long? = null,
    ) = HealthAggregateRow(
        providerId = "p",
        modelId = modelId,
        total = decided,
        decided = decided,
        successes = successes,
        rateLimited = rateLimited,
        avgTtftMs = null,
        avgTps = null,
        avgLatencyMs = null,
        lastObservedAt = lastObservedAt,
    )

    @Test
    fun noDecidedSamplesIsUnknown_notHealthyNotOffline() {
        assertEquals(HealthState.UNKNOWN, HealthRollup.observedHealth(0, 0, 0))
        assertEquals(HealthState.UNKNOWN, HealthRollup.observedHealth(decided = 0, successes = 5, rateLimited = 0))
        assertEquals(HealthState.UNKNOWN, HealthRollup.observedHealth(emptyList()))
    }

    @Test
    fun thresholdsMapDeterministically() {
        assertEquals(HealthState.HEALTHY, HealthRollup.observedHealth(100, 100, 0))
        assertEquals(HealthState.HEALTHY, HealthRollup.observedHealth(100, 95, 0))
        assertEquals(HealthState.DEGRADED, HealthRollup.observedHealth(100, 94, 0))
        assertEquals(HealthState.DEGRADED, HealthRollup.observedHealth(100, 90, 0))
        assertEquals(HealthState.UNSTABLE, HealthRollup.observedHealth(100, 89, 0))
        assertEquals(HealthState.UNSTABLE, HealthRollup.observedHealth(100, 50, 0))
        assertEquals(HealthState.OFFLINE, HealthRollup.observedHealth(100, 49, 0))
        assertEquals(HealthState.OFFLINE, HealthRollup.observedHealth(100, 0, 0))
    }

    @Test
    fun rateLimitDegradesHealthySuccess() {
        assertEquals(HealthState.HEALTHY, HealthRollup.observedHealth(100, 100, 0))
        assertEquals(HealthState.DEGRADED, HealthRollup.observedHealth(100, 99, 3))
    }

    @Test
    fun successesClampedNeverAboveDecided() {
        assertEquals(HealthState.HEALTHY, HealthRollup.observedHealth(decided = 10, successes = 999, rateLimited = 0))
    }

    @Test
    fun aggregateIsSumNotAverage() {
        val rows = listOf(
            row("a", decided = 100, successes = 0),
            row("b", decided = 100, successes = 100),
        )
        // 加總 100/200 = 0.5 → UNSTABLE（與逐列平均結果不同時證明為加總）。
        assertEquals(HealthState.UNSTABLE, HealthRollup.observedHealth(rows))
        val skewed = listOf(
            row("a", decided = 99, successes = 0),
            row("b", decided = 1, successes = 1),
        )
        // 兩模型逐列平均成功率 = 50% → 會誤判 UNSTABLE；加總 1/100 = 1% → OFFLINE（證明合併為加總）。
        assertEquals(HealthState.OFFLINE, HealthRollup.observedHealth(skewed))
    }

    @Test
    fun observedAtTakesMaxOrZero() {
        assertEquals(0L, HealthRollup.observedAt(emptyList()))
        assertEquals(
            3000L,
            HealthRollup.observedAt(
                listOf(
                    row("a", decided = 1, successes = 1, lastObservedAt = 1000),
                    row("b", decided = 1, successes = 1, lastObservedAt = 3000),
                    row("c", decided = 1, successes = 1, lastObservedAt = null),
                ),
            ),
        )
    }
}
