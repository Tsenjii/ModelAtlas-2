package dev.modelatlas.core.newapi

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class PricingExpressionBoundsTest {
    @Test fun nestedMissingClosingDelimitersReturnUnknown() {
        listOf("((1", "(1+(2", "(((x").forEach {
            assertEquals(PricingExprUnknownReason.SYNTAX_ERROR,
                PricingExpressionParser.evaluate(it, mapOf("x" to BigDecimal.ONE)).unknownReasonOrNull())
        }
    }

    @Test fun exponentSizedVariablesAndRatesAreRejectedBeforeArithmetic() {
        val huge = BigDecimal("1e2147483647")
        assertEquals(PricingExprUnknownReason.TOO_COMPLEX,
            PricingExpressionParser.evaluate("x+1", mapOf("x" to huge)).unknownReasonOrNull())
        assertEquals(PricingExprUnknownReason.TOO_COMPLEX,
            PricingExpressionParser.evaluateQuota("1", quotaPerUnit = huge, groupRatio = BigDecimal.ONE).unknownReasonOrNull())
    }

    @Test fun intermediateScaleCannotGrowUnbounded() {
        assertEquals(PricingExprUnknownReason.TOO_COMPLEX,
            PricingExpressionParser.evaluate("x*x", mapOf("x" to BigDecimal("1e-4000"))).unknownReasonOrNull())
    }
}
