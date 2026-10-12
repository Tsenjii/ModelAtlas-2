package dev.modelatlas.core.newapi

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingExpressionParserTest {
    private fun known(expr: String, vars: Map<String, BigDecimal> = emptyMap()): BigDecimal {
        val r = PricingExpressionParser.evaluate(expr, vars)
        assertTrue("expected Known but was $r for expr=$expr", r is PricingExprResult.Known)
        return (r as PricingExprResult.Known).value
    }

    private fun unknown(expr: String, vars: Map<String, BigDecimal> = emptyMap()): PricingExprUnknownReason {
        val r = PricingExpressionParser.evaluate(expr, vars)
        assertTrue("expected Unknown but was $r for expr=$expr", r is PricingExprResult.Unknown)
        return (r as PricingExprResult.Unknown).reason
    }

    private fun assertKnownEquals(expected: BigDecimal, actual: BigDecimal) {
        // BigDecimal.equals 會比 scale，strip 後 2E+1 與 20 不 equal；數值等值用 compareTo。
        assertTrue("expected $expected but was $actual", actual.compareTo(expected) == 0)
    }

    @Test
    fun precedenceMulBeforeAdd() {
        assertKnownEquals(BigDecimal("14"), known("2 + 3 * 4"))
        assertKnownEquals(BigDecimal("20"), known("(2 + 3) * 4"))
    }

    @Test
    fun leftAssociativeSubDiv() {
        assertKnownEquals(BigDecimal("5"), known("10 - 3 - 2"))
        assertKnownEquals(BigDecimal("2.5"), known("10 / 4"))
    }

    @Test
    fun unaryMinusAndPlus() {
        assertKnownEquals(BigDecimal("-2"), known("-5 + 3"))
        assertKnownEquals(BigDecimal("5"), known("--5"))
        assertKnownEquals(BigDecimal("-6"), known("-(2 + 4)"))
        assertKnownEquals(BigDecimal("8"), known("+5 + +3"))
    }

    @Test
    fun variablesResolvedExactly() {
        val v = known(
            "input_tokens * w_in + output_tokens * w_out",
            mapOf(
                "input_tokens" to BigDecimal("1000"),
                "w_in" to BigDecimal("2"),
                "output_tokens" to BigDecimal("500"),
                "w_out" to BigDecimal("6"),
            ),
        )
        assertKnownEquals(BigDecimal("5000"), v)
    }

    @Test
    fun unknownVariableIsUnknownNotZero() {
        val reason = unknown("price * 2", mapOf("other" to BigDecimal("1")))
        assertEquals(PricingExprUnknownReason.UNKNOWN_VARIABLE, reason)
        val missing = PricingExpressionParser.evaluate("x + 1", emptyMap())
        assertTrue(missing is PricingExprResult.Unknown)
    }

    @Test
    fun emptyAndBlankAreUnknown() {
        assertEquals(PricingExprUnknownReason.EMPTY, unknown(""))
        assertEquals(PricingExprUnknownReason.EMPTY, unknown("   "))
    }

    @Test
    fun tooLongIsUnknown() {
        val long = "1" + "+1".repeat(1500)
        assertEquals(PricingExprUnknownReason.TOO_LONG, unknown(long))
    }

    @Test
    fun tooDeepNestingIsUnknown() {
        val deep = "(".repeat(40) + "1" + ")".repeat(40)
        val r = PricingExpressionParser.evaluate(deep)
        assertTrue(r is PricingExprResult.Unknown)
        assertEquals(
            PricingExprUnknownReason.TOO_COMPLEX,
            (r as PricingExprResult.Unknown).reason,
        )
    }

    @Test
    fun tooManyTokensIsUnknown() {
        val many = (1..600).joinToString("+") { "1" }
        val r = PricingExpressionParser.evaluate(many)
        assertTrue(r is PricingExprResult.Unknown)
    }

    @Test
    fun divisionByZeroIsUnknown() {
        assertEquals(PricingExprUnknownReason.DIVISION_BY_ZERO, unknown("1 / 0"))
        assertEquals(PricingExprUnknownReason.DIVISION_BY_ZERO, unknown("1 / (2 - 2)"))
        assertEquals(PricingExprUnknownReason.DIVISION_BY_ZERO, unknown("0 / 0"))
        assertEquals(
            PricingExprUnknownReason.DIVISION_BY_ZERO,
            unknown("10 / z", mapOf("z" to BigDecimal.ZERO)),
        )
    }

    @Test
    fun negativeResultStaysKnownForPolicyLayer() {
        val v = known("2 - 5")
        assertKnownEquals(BigDecimal("-3"), v)
        assertTrue(v.signum() < 0)
    }

    @Test
    fun negativeVariableProducesKnownNegative() {
        val v = known("q * r", mapOf("q" to BigDecimal("-2"), "r" to BigDecimal("3")))
        assertKnownEquals(BigDecimal("-6"), v)
    }

    @Test
    fun bigDecimalPrecisionNotDouble() {
        assertKnownEquals(BigDecimal("0.3"), known("0.1 + 0.2"))
        assertKnownEquals(BigDecimal("0.02"), known("0.1 * 0.2"))
    }

    @Test
    fun whitespaceIsIgnored() {
        assertKnownEquals(BigDecimal("7"), known("  3  +\t4\n"))
    }

    @Test
    fun functionCallIsUnsupportedUnknown() {
        val r1 = PricingExpressionParser.evaluate("max(1, 2)")
        assertTrue(r1 is PricingExprResult.Unknown)
        val r2 = PricingExpressionParser.evaluate("min(3)")
        assertTrue(r2 is PricingExprResult.Unknown)
    }

    @Test
    fun unsupportedOperatorsAreUnknown() {
        for (e in listOf("1 % 2", "2 ^ 3", "2 ** 3", "1 && 0", "1 == 1", "a ? b : c", "1; 2")) {
            val r = PricingExpressionParser.evaluate(e, mapOf("a" to BigDecimal.ONE, "b" to BigDecimal.ONE, "c" to BigDecimal.ONE))
            assertTrue("expected Unknown for $e but was $r", r is PricingExprResult.Unknown)
        }
    }

    @Test
    fun exponentAndMemberAccessAreUnknown() {
        assertTrue(PricingExpressionParser.evaluate("1e6") is PricingExprResult.Unknown)
        assertTrue(PricingExpressionParser.evaluate("foo.bar") is PricingExprResult.Unknown)
        assertTrue(PricingExpressionParser.evaluate(".5 + 1") is PricingExprResult.Unknown)
        assertTrue(PricingExpressionParser.evaluate("'abc'") is PricingExprResult.Unknown)
    }

    @Test
    fun malformedSyntaxIsUnknown() {
        assertTrue(PricingExpressionParser.evaluate("()") is PricingExprResult.Unknown)
        assertTrue(PricingExpressionParser.evaluate("(1 + 2") is PricingExprResult.Unknown)
        assertTrue(PricingExpressionParser.evaluate("1 +") is PricingExprResult.Unknown)
        assertTrue(PricingExpressionParser.evaluate("1 2") is PricingExprResult.Unknown)
        assertTrue(PricingExpressionParser.evaluate("5.") is PricingExprResult.Unknown)
    }

    @Test
    fun quotaConversionFollowsResearchFormula() {
        val r = PricingExpressionParser.evaluateQuota(
            expression = "2",
            quotaPerUnit = BigDecimal("500000"),
            groupRatio = BigDecimal("1"),
        )
        assertTrue(r is PricingExprResult.Known)
        assertKnownEquals(BigDecimal("1"), (r as PricingExprResult.Known).value)
    }

    @Test
    fun quotaWithGroupRatioScales() {
        val r = PricingExpressionParser.evaluateQuota(
            expression = "2",
            quotaPerUnit = BigDecimal("500000"),
            groupRatio = BigDecimal("10"),
        )
        assertTrue(r is PricingExprResult.Known)
        assertKnownEquals(BigDecimal("10"), (r as PricingExprResult.Known).value)
    }

    @Test
    fun quotaGroupZeroIsKnownZeroButNotFreeDecision() {
        val r = PricingExpressionParser.evaluateQuota(
            expression = "2.5",
            quotaPerUnit = BigDecimal("500000"),
            groupRatio = BigDecimal.ZERO,
        )
        assertTrue(r is PricingExprResult.Known)
        assertEquals(0, (r as PricingExprResult.Known).value.signum())
    }

    @Test
    fun quotaMissingRateIsUnknownNeverDefaults() {
        val noUnit = PricingExpressionParser.evaluateQuota("2", quotaPerUnit = null, groupRatio = BigDecimal.ONE)
        assertEquals(
            PricingExprUnknownReason.INVALID_RATE,
            (noUnit as PricingExprResult.Unknown).reason,
        )
        val noGroup = PricingExpressionParser.evaluateQuota("2", quotaPerUnit = BigDecimal("500000"), groupRatio = null)
        assertEquals(
            PricingExprUnknownReason.INVALID_RATE,
            (noGroup as PricingExprResult.Unknown).reason,
        )
        val zeroUnit = PricingExpressionParser.evaluateQuota("2", quotaPerUnit = BigDecimal.ZERO, groupRatio = BigDecimal.ONE)
        assertTrue(zeroUnit is PricingExprResult.Unknown)
        val negUnit = PricingExpressionParser.evaluateQuota("2", quotaPerUnit = BigDecimal("-1"), groupRatio = BigDecimal.ONE)
        assertTrue(negUnit is PricingExprResult.Unknown)
        val negGroup = PricingExpressionParser.evaluateQuota("2", quotaPerUnit = BigDecimal("500000"), groupRatio = BigDecimal("-1"))
        assertTrue(negGroup is PricingExprResult.Unknown)
    }

    @Test
    fun quotaPropagatesExprUnknown() {
        val r = PricingExpressionParser.evaluateQuota(
            expression = "x + 1",
            quotaPerUnit = BigDecimal("500000"),
            groupRatio = BigDecimal.ONE,
        )
        assertEquals(
            PricingExprUnknownReason.UNKNOWN_VARIABLE,
            (r as PricingExprResult.Unknown).reason,
        )
    }

    @Test
    fun quotaWithVariablesAndFormula() {
        val r = PricingExpressionParser.evaluateQuota(
            expression = "tokens * coeff",
            variables = mapOf("tokens" to BigDecimal("2000000"), "coeff" to BigDecimal("3")),
            quotaPerUnit = BigDecimal("500000"),
            groupRatio = BigDecimal("1"),
        )
        assertTrue(r is PricingExprResult.Known)
        assertKnownEquals(BigDecimal("3000000"), (r as PricingExprResult.Known).value)
    }
}
