package dev.modelatlas.app.data

import dev.modelatlas.core.newapi.NewApiPerfPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NINT-004 pure-codec regression (Contract §6.2 / D-006 full series preservation,
 * Unknown != 0, PRODUCT_SPEC §18).
 *
 * Synthetic typed fixtures only — never product facts.
 * Shape reference: upstream `QuantumNous/new-api` tag `v1.0.0-rc.25` = `f1164142`
 * (PERF-SHAPE-B `BucketPoint`: `ts` = Unix epoch seconds bucket start, gaps not
 * interpolated, `request_count` never serialized so point count is not a sample count).
 *
 * Pure JVM: exercises only the internal [encodePerfSeries] / [decodePerfSeries] and
 * [encodePerfRates] / [decodePerfRates] helpers. No Room, no Android, no network.
 */
class ProviderPerfCodecTest {

    @Test
    fun largeEpochSecondsRoundTripWithoutDoubleLoss() {
        // 2^53 + 1: Double cannot represent this exactly (rounds to 2^53).
        val bigTs = 9_007_199_254_740_993L
        val points = listOf(
            NewApiPerfPoint(bigTs, 12L, 34L, 99.25, 11.5),
            NewApiPerfPoint(bigTs + 10L, 0L, 0L, 0.0, 0.0),
        )
        val encoded = encodePerfSeries(points)
        // Exact decimal form must survive; a Double path would emit exponent form.
        assertTrue(encoded.contains("9007199254740993"))
        val decoded = decodePerfSeries(encoded)
            ?: throw AssertionError("expected round-trip, got null: $encoded")
        assertEquals(2, decoded.size)
        assertEquals(bigTs, decoded[0].tsEpochSeconds)
        assertEquals(12L, decoded[0].avgTtftMs)
        assertEquals(34L, decoded[0].avgLatencyMs)
        assertEquals(99.25, decoded[0].successRate, 0.0)
        assertEquals(11.5, decoded[0].avgTps, 0.0)
        assertEquals(bigTs + 10L, decoded[1].tsEpochSeconds)
    }

    @Test
    fun reorderedPointsNormalizedAndGapsRetainedWithoutInterpolation() {
        // Out of order with a gap (1000 -> 1600 -> 3000): no filler buckets.
        val late = NewApiPerfPoint(3_000L, 10L, 20L, 90.0, 5.0)
        val early = NewApiPerfPoint(1_000L, 11L, 21L, 91.5, 6.5)
        val mid = NewApiPerfPoint(1_600L, 0L, 0L, 100.0, 0.0)
        val decoded = decodePerfSeries(encodePerfSeries(listOf(late, early, mid)))
            ?: throw AssertionError("expected decoded series, got null")
        // Point count is bucket count only — never a request sample count.
        assertEquals(3, decoded.size)
        assertEquals(listOf(1_000L, 1_600L, 3_000L), decoded.map { it.tsEpochSeconds })
        assertEquals(early, decoded[0])
        assertEquals(mid, decoded[1])
        assertEquals(late, decoded[2])
    }

    @Test
    fun nullVersusEmptyAndAbsentRatesPreserved() {
        // Series: null = unobserved, "[]" = authoritative empty.
        assertNull(decodePerfSeries(null))
        assertEquals(emptyList<NewApiPerfPoint>(), decodePerfSeries("[]"))
        assertEquals("[]", encodePerfSeries(emptyList()))
        // Rates: null = key absent/null (Unknown), "" = explicit empty array.
        assertNull(encodePerfRates(null))
        assertNull(decodePerfRates(null))
        assertEquals("", encodePerfRates(emptyList()))
        assertEquals(emptyList<Double>(), decodePerfRates(""))
        val rates = listOf(99.5, 100.0, 0.0)
        assertEquals(rates, decodePerfRates(encodePerfRates(rates)))
    }

    @Test
    fun malformedOrStringNumericJsonFailsClosedWithoutDefaultZeros() {
        // Missing key.
        assertNull(decodePerfSeries("""[{"tsEpochSeconds":1,"avgTtftMs":2,"avgLatencyMs":3,"successRate":4.0}]"""))
        // String numerics must not parse (strict numeric-only shape).
        assertNull(
            decodePerfSeries(
                """[{"tsEpochSeconds":"1000","avgTtftMs":"10","avgLatencyMs":"20","successRate":"90.0","avgTps":"5.0"}]""",
            ),
        )
        // Truncated / wrong top-level shape.
        assertNull(decodePerfSeries("""[{"tsEpochSeconds":"""))
        assertNull(decodePerfSeries("""{"tsEpochSeconds":1000}"""))
        assertNull(decodePerfSeries("null"))
        // Rates: non-numeric segments fail closed, never default to zeros.
        assertNull(decodePerfRates("a,b"))
        assertNull(decodePerfRates("1.0,,2.0"))
        assertNull(decodePerfRates("1.0,abc"))
    }
}
