package dev.modelatlas.core.domain

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingPolicyTest {
    private fun scope(
        operation: String = "text",
        credentialId: String? = "cred-1",
        endpointId: String = "ep-1",
        modelId: String = "m-1",
    ) = PricingScope(
        providerId = "p1",
        accountId = "a1",
        credentialId = credentialId,
        modelId = modelId,
        group = "g1",
        endpointId = endpointId,
        operation = operation,
    )

    private fun snapshot(
        scope: PricingScope = scope(),
        input: BigDecimal? = BigDecimal.ZERO,
        output: BigDecimal? = BigDecimal.ZERO,
        fixed: BigDecimal? = null,
        fixedAbsent: Boolean = true,
        cacheRead: BigDecimal? = null,
        cacheWrite: BigDecimal? = null,
    ) = PriceSnapshot(
        scope = scope,
        observedAt = 1L,
        input = input,
        output = output,
        fixedRequest = fixed,
        fixedRequestKnownAbsent = fixedAbsent,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
    )

    @Test
    fun nullSnapshotIsUnknown() {
        assertEquals(PriceAssessment(PriceState.UNKNOWN, false), PricingPolicy.evaluate(null))
    }

    @Test
    fun freeNowWhenAllZeroAndFixedAbsent() {
        val r = PricingPolicy.evaluate(snapshot())
        assertEquals(PriceState.FREE_NOW, r.state)
        assertFalse(r.cacheCostsExtra)
    }

    @Test
    fun freeNowWhenFixedZero() {
        val r = PricingPolicy.evaluate(snapshot(fixed = BigDecimal.ZERO, fixedAbsent = false))
        assertEquals(PriceState.FREE_NOW, r.state)
    }

    @Test
    fun freeNowAcceptsScaledZero() {
        val r = PricingPolicy.evaluate(
            snapshot(
                input = BigDecimal("0.00"),
                output = BigDecimal("0.000"),
                fixed = BigDecimal("0.0"),
                fixedAbsent = false,
            ),
        )
        assertEquals(PriceState.FREE_NOW, r.state)
    }

    @Test
    fun unknownWhenInputOrOutputNull() {
        assertEquals(PriceState.UNKNOWN, PricingPolicy.evaluate(snapshot(input = null)).state)
        assertEquals(PriceState.UNKNOWN, PricingPolicy.evaluate(snapshot(output = null)).state)
    }

    @Test
    fun unknownWhenFixedUnknownAndNotAbsent() {
        val r = PricingPolicy.evaluate(snapshot(fixed = null, fixedAbsent = false))
        assertEquals(PriceState.UNKNOWN, r.state)
    }

    @Test
    fun paidWhenBothLegsPositive() {
        val r = PricingPolicy.evaluate(
            snapshot(input = BigDecimal("0.001"), output = BigDecimal("0.002")),
        )
        assertEquals(PriceState.PAID, r.state)
        assertFalse(r.cacheCostsExtra)
    }

    @Test
    fun positivePriceNeverFreeByName() {
        val s = snapshot(
            scope = scope(modelId = "openrouter-free-myth"),
            input = BigDecimal("0.001"),
            output = BigDecimal("0.002"),
        )
        assertEquals(PriceState.PAID, PricingPolicy.evaluate(s).state)
    }

    @Test
    fun partiallyFreeWhenOneLegZero() {
        val r = PricingPolicy.evaluate(
            snapshot(input = BigDecimal.ZERO, output = BigDecimal("0.002")),
        )
        assertEquals(PriceState.PARTIALLY_FREE, r.state)
    }

    @Test
    fun partiallyFreeWhenTokensFreeButFixedCharged() {
        val r = PricingPolicy.evaluate(
            snapshot(fixed = BigDecimal("0.001"), fixedAbsent = false),
        )
        assertEquals(PriceState.PARTIALLY_FREE, r.state)
    }

    @Test
    fun negativePriceIsUnknownNeverFree() {
        assertEquals(
            PriceState.UNKNOWN,
            PricingPolicy.evaluate(snapshot(input = BigDecimal("-0.001"))).state,
        )
        assertEquals(
            PriceState.UNKNOWN,
            PricingPolicy.evaluate(
                snapshot(fixed = BigDecimal("-1"), fixedAbsent = false),
            ).state,
        )
    }

    @Test
    fun unsupportedOperationIsUnknownEvenIfZero() {
        val r = PricingPolicy.evaluate(snapshot(scope = scope(operation = "image")))
        assertEquals(PriceState.UNKNOWN, r.state)
    }

    @Test
    fun chatCompletionsOperationIsSupported() {
        assertEquals(
            PriceState.FREE_NOW,
            PricingPolicy.evaluate(snapshot(scope = scope(operation = "CHAT_COMPLETIONS"))).state,
        )
        assertEquals(
            PriceState.PAID,
            PricingPolicy.evaluate(
                snapshot(
                    scope = scope(operation = "CHAT_COMPLETIONS"),
                    input = BigDecimal("0.001"),
                    output = BigDecimal("0.002"),
                ),
            ).state,
        )
    }

    @Test
    fun cacheExtraShownIndependentlyOfFree() {
        val r = PricingPolicy.evaluate(snapshot(cacheRead = BigDecimal("0.0001")))
        assertEquals(PriceState.FREE_NOW, r.state)
        assertTrue(r.cacheCostsExtra)
    }

    @Test
    fun cacheZeroOrNullIsNotExtra() {
        assertFalse(PricingPolicy.evaluate(snapshot()).cacheCostsExtra)
        assertFalse(
            PricingPolicy.evaluate(snapshot(cacheRead = BigDecimal("0.00"))).cacheCostsExtra,
        )
    }

    @Test
    fun scopeIsolationDifferentKeysDoNotCollide() {
        val a = snapshot(scope = scope(credentialId = "cred-1", endpointId = "ep-1"))
        val b = snapshot(
            scope = scope(credentialId = "cred-2", endpointId = "ep-1"),
            input = BigDecimal("0.005"),
            output = BigDecimal("0.005"),
        )
        val c = snapshot(scope = scope(credentialId = "cred-1", endpointId = "ep-2"))
        val d = snapshot(scope = scope(operation = "image"))
        assertNotEquals(a.scope, b.scope)
        assertNotEquals(a.scope, c.scope)
        assertNotEquals(a.scope, d.scope)
        val store = mutableMapOf<PricingScope, PriceSnapshot>()
        store[a.scope] = a
        store[b.scope] = b
        store[c.scope] = c
        store[d.scope] = d
        assertEquals(4, store.size)
        assertEquals(PriceState.FREE_NOW, PricingPolicy.evaluate(store[a.scope]).state)
        assertEquals(PriceState.PAID, PricingPolicy.evaluate(store[b.scope]).state)
    }
}
