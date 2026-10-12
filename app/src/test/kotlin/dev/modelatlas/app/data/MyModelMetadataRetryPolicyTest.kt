package dev.modelatlas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-028 F1 JVM policy-machine tests (vectors J1–J9, spec §3.3).
 *
 * Targets the same-slice production symbols from spec Hunk P1
 * (`MyModelMetadataRetryPolicy` + `RefreshWorkScope` / `PassportClass` /
 * `AutoState`) landed concurrently by the production owner — this file does
 * not compile until Hunk P1 lands; that same-slice dependency is recorded in
 * `/tmp/opencode/ai11-d028-f1-jvmtest-result.md`.
 *
 * Pure Kotlin: no Android/Room/clock. Covers classify / reserve / onStart /
 * onQueuedCancel / onTerminal / onUserExplicit per T1–T13, D2-YES charge
 * semantics (zeroHttp started = charged), D3-YES independent budgets,
 * slice-union W keying (one exact W = one charge), UNKNOWN_READ never
 * charges.
 */
class MyModelMetadataRetryPolicyTest {

    private fun w(
        providerId: String = "p1",
        providerModelId: String = "m1",
        accountId: String? = "a1",
        credentialId: String? = "cred-1",
        group: String? = null,
        endpointId: String? = "e1",
        operation: String? = "CHAT_COMPLETIONS",
    ): MyModelMetadataRetryPolicy.RefreshWorkScope =
        MyModelMetadataRetryPolicy.RefreshWorkScope(
            providerId = providerId,
            providerModelId = providerModelId,
            accountId = accountId,
            credentialId = credentialId,
            group = group,
            endpointId = endpointId,
            operation = operation,
        )

    /** J1 (T5): double-reserve refused; second claim on the same W fails. */
    @Test
    fun j1DoubleReserveRefused() {
        val policy = MyModelMetadataRetryPolicy()
        val scope = w()
        assertTrue(policy.reserve(scope))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.RESERVED_AUTO,
            policy.autoStateOf(scope),
        )
        // Second reservation on the same exact W is refused; state unchanged.
        assertFalse(policy.reserve(scope))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.RESERVED_AUTO,
            policy.autoStateOf(scope),
        )
        // A distinct W has its own independent budget cell.
        val sibling = w(accountId = "a2")
        assertTrue(policy.reserve(sibling))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.RESERVED_AUTO,
            policy.autoStateOf(sibling),
        )
        // Start is only legal from RESERVED_AUTO: fresh W cannot start.
        assertFalse(policy.onStart(w(providerModelId = "m-fresh")))
    }

    /** J2 (T8/T9): terminal states are absorbing; terminal without start is ignored. */
    @Test
    fun j2TerminalAbsorbing() {
        val policy = MyModelMetadataRetryPolicy()
        // Success path absorbs: later failure cannot overwrite SUCCEEDED.
        val ok = w(providerModelId = "m-ok")
        assertTrue(policy.reserve(ok))
        assertTrue(policy.onStart(ok))
        policy.onTerminal(ok, anyGoodRow = true, cancelledAfterStart = false)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_SUCCEEDED,
            policy.autoStateOf(ok),
        )
        policy.onTerminal(ok, anyGoodRow = false, cancelledAfterStart = false)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_SUCCEEDED,
            policy.autoStateOf(ok),
        )
        // Spent budget never re-arms: reserve refused after terminal.
        assertFalse(policy.reserve(ok))

        // No-good path absorbs: later success cannot overwrite NO_GOOD (no re-arm).
        val bad = w(providerModelId = "m-bad")
        assertTrue(policy.reserve(bad))
        assertTrue(policy.onStart(bad))
        policy.onTerminal(bad, anyGoodRow = false, cancelledAfterStart = false)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_NO_GOOD,
            policy.autoStateOf(bad),
        )
        policy.onTerminal(bad, anyGoodRow = true, cancelledAfterStart = false)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_NO_GOOD,
            policy.autoStateOf(bad),
        )
        assertFalse(policy.reserve(bad))

        // Started-cancel lands CANCELLED_AFTER_START (T10 policy projection).
        val cx = w(providerModelId = "m-cx")
        assertTrue(policy.reserve(cx))
        assertTrue(policy.onStart(cx))
        policy.onTerminal(cx, anyGoodRow = false, cancelledAfterStart = true)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_CANCELLED,
            policy.autoStateOf(cx),
        )

        // Terminal without start is ignored: IDLE stays IDLE, RESERVED stays RESERVED.
        val idle = w(providerModelId = "m-idle")
        policy.onTerminal(idle, anyGoodRow = true, cancelledAfterStart = false)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.IDLE_UNTRIED,
            policy.autoStateOf(idle),
        )
        val reserved = w(providerModelId = "m-res")
        assertTrue(policy.reserve(reserved))
        policy.onTerminal(reserved, anyGoodRow = true, cancelledAfterStart = false)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.RESERVED_AUTO,
            policy.autoStateOf(reserved),
        )
    }

    /** J3 (T11): queued-unstarted cancel releases to IDLE_UNTRIED; budget intact. */
    @Test
    fun j3QueuedCancelReleasesBudgetIntact() {
        val policy = MyModelMetadataRetryPolicy()
        val scope = w()
        assertTrue(policy.reserve(scope))
        assertTrue(policy.onQueuedCancel(scope))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.IDLE_UNTRIED,
            policy.autoStateOf(scope),
        )
        // Budget intact: reservation allowed again after the queued cancel.
        assertTrue(policy.reserve(scope))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.RESERVED_AUTO,
            policy.autoStateOf(scope),
        )

        // Cancel with nothing reserved is refused (no state created).
        val fresh = w(providerModelId = "m-fresh")
        assertFalse(policy.onQueuedCancel(fresh))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.IDLE_UNTRIED,
            policy.autoStateOf(fresh),
        )

        // Cancel after start is too late: refused, CHARGED_RUNNING retained.
        val started = w(providerModelId = "m-started")
        assertTrue(policy.reserve(started))
        assertTrue(policy.onStart(started))
        assertFalse(policy.onQueuedCancel(started))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.CHARGED_RUNNING,
            policy.autoStateOf(started),
        )
    }

    /**
     * J4 (T2, A8 logic parity): throwing-reader equivalent (`readOk = false`)
     * classifies UNKNOWN_READ for every passport combination, never charges,
     * never mutates state; a working reader then proceeds normally.
     */
    @Test
    fun j4ThrowingReaderUnknownReadNeverCharges() {
        val policy = MyModelMetadataRetryPolicy()
        // Truth table: readOk=false forces UNKNOWN_READ regardless of the rest.
        for (hasLastGood in listOf(false, true)) {
            for (hasAnyHeadOrFact in listOf(false, true)) {
                for (legCapable in listOf(false, true)) {
                    assertEquals(
                        "readOk=false must classify UNKNOWN_READ " +
                            "(lastGood=$hasLastGood, head=$hasAnyHeadOrFact, capable=$legCapable)",
                        MyModelMetadataRetryPolicy.PassportClass.UNKNOWN_READ,
                        policy.classify(
                            hasLastGood = hasLastGood,
                            hasAnyHeadOrFact = hasAnyHeadOrFact,
                            readOk = false,
                            legCapable = legCapable,
                        ),
                    )
                }
            }
        }
        // UNKNOWN_READ never charges and never persists: no state change.
        val scope = w()
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.IDLE_UNTRIED,
            policy.autoStateOf(scope),
        )
        // No reservation may be derived from an unknown read: start refused.
        assertFalse(policy.onStart(scope))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.IDLE_UNTRIED,
            policy.autoStateOf(scope),
        )
        // Recovery (A8 restore): a working reader classifies ABSENT and the
        // same W may then reserve exactly once.
        assertEquals(
            MyModelMetadataRetryPolicy.PassportClass.ABSENT,
            policy.classify(
                hasLastGood = false,
                hasAnyHeadOrFact = false,
                readOk = true,
                legCapable = true,
            ),
        )
        assertTrue(policy.reserve(scope))
        assertFalse(policy.reserve(scope))
    }

    /**
     * J5 (T2/T3): structural no-TTL — `classify` takes NO time argument, and
     * both ages map to the identical classification input/output.
     */
    @Test
    fun j5NoTtlNoTimeParam() {
        // Signature gate: exactly one classify overload with exactly the four
        // structural booleans — no Long/time/age/clock parameter, ever.
        val overloads =
            MyModelMetadataRetryPolicy::class.java.methods.filter { it.name == "classify" }
        assertEquals(1, overloads.size)
        val params = overloads.single().parameterTypes
        assertEquals(4, params.size)
        assertTrue(params.all { it == java.lang.Boolean.TYPE })

        // Age-indifference: an ancient lastGood (FX-5a) and a recent lastGood
        // (FX-5b) produce byte-identical classifications — age is not an input.
        val policy = MyModelMetadataRetryPolicy()
        val ancient = policy.classify(
            hasLastGood = true,
            hasAnyHeadOrFact = true,
            readOk = true,
            legCapable = true,
        )
        val recent = policy.classify(
            hasLastGood = true,
            hasAnyHeadOrFact = true,
            readOk = true,
            legCapable = true,
        )
        assertEquals(MyModelMetadataRetryPolicy.PassportClass.HAS_LAST_GOOD, ancient)
        assertEquals(ancient, recent)

        // Full T2 truth table on the working-reader path.
        assertEquals(
            MyModelMetadataRetryPolicy.PassportClass.HAS_LAST_GOOD,
            policy.classify(hasLastGood = true, hasAnyHeadOrFact = false, readOk = true, legCapable = false),
        )
        assertEquals(
            MyModelMetadataRetryPolicy.PassportClass.FAILED_NO_GOOD,
            policy.classify(hasLastGood = false, hasAnyHeadOrFact = true, readOk = true, legCapable = true),
        )
        assertEquals(
            MyModelMetadataRetryPolicy.PassportClass.ABSENT,
            policy.classify(hasLastGood = false, hasAnyHeadOrFact = false, readOk = true, legCapable = true),
        )
        // Uncapable with nothing known stays ABSENT here (caller maps to
        // SKIPPED_UNCAPABLE); the budget cell is untouched either way.
        assertEquals(
            MyModelMetadataRetryPolicy.PassportClass.ABSENT,
            policy.classify(hasLastGood = false, hasAnyHeadOrFact = false, readOk = true, legCapable = false),
        )
    }

    /**
     * J6 (T13, D3-YES): `onUserExplicit` never mutates auto state — before,
     * during, or after the auto budget is spent.
     */
    @Test
    fun j6UserExplicitNeverMutatesAuto() {
        val policy = MyModelMetadataRetryPolicy()
        val scope = w()

        // Before: explicit press on an untried W leaves IDLE_UNTRIED.
        policy.onUserExplicit(scope)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.IDLE_UNTRIED,
            policy.autoStateOf(scope),
        )
        // Explicit press on a never-seen W creates no budget cell.
        val stranger = w(providerModelId = "m-stranger")
        policy.onUserExplicit(stranger)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.IDLE_UNTRIED,
            policy.autoStateOf(stranger),
        )

        // During: explicit press mid-flight leaves RESERVED/CHARGED untouched.
        assertTrue(policy.reserve(scope))
        policy.onUserExplicit(scope)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.RESERVED_AUTO,
            policy.autoStateOf(scope),
        )
        assertTrue(policy.onStart(scope))
        policy.onUserExplicit(scope)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.CHARGED_RUNNING,
            policy.autoStateOf(scope),
        )

        // After: explicit press after auto spent changes nothing; auto stays
        // suppressed and the reverse holds (spent auto never blocks presses —
        // presses are caller-tracked, so the machine simply ignores them).
        policy.onTerminal(scope, anyGoodRow = false, cancelledAfterStart = false)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_NO_GOOD,
            policy.autoStateOf(scope),
        )
        policy.onUserExplicit(scope)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_NO_GOOD,
            policy.autoStateOf(scope),
        )
        assertFalse(policy.reserve(scope))
    }

    /** J7 (T6/T12): a follower join consumes no budget token. */
    @Test
    fun j7JoinConsumesNoToken() {
        val policy = MyModelMetadataRetryPolicy()
        val scope = w()
        // Leader takes the single claim and charges at start.
        assertTrue(policy.reserve(scope))
        // Follower's join attempt on the same W is refused and moves nothing:
        // no second reservation, no state transition, no charge.
        assertFalse(policy.reserve(scope))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.RESERVED_AUTO,
            policy.autoStateOf(scope),
        )
        assertTrue(policy.onStart(scope))
        // A join never starts its own attempt: double-start refused.
        assertFalse(policy.onStart(scope))
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.CHARGED_RUNNING,
            policy.autoStateOf(scope),
        )
        // Leader terminal settles the single shared cell once.
        policy.onTerminal(scope, anyGoodRow = false, cancelledAfterStart = false)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_NO_GOOD,
            policy.autoStateOf(scope),
        )
    }

    /**
     * J8: exact-W key equality — `null` vs `""` vs named stay distinct per
     * dim (slice-union: one exact W = one charge cell); endpoint-only or
     * operation-only diffs split budgets; string form carries safe refs only.
     */
    @Test
    fun j8KeyEqualityAndSafeRefs() {
        // Null vs empty vs named are distinct keys per nullable dim.
        assertNotEquals(w(group = null), w(group = ""))
        assertNotEquals(w(group = ""), w(group = "g1"))
        assertNotEquals(w(accountId = null), w(accountId = ""))
        assertNotEquals(w(credentialId = null), w(credentialId = ""))
        // Identical dims are one key.
        assertEquals(w(), w())
        assertEquals(w().hashCode(), w().hashCode())

        // Endpoint-only diff splits (distinct W, independent budget cells).
        val policy = MyModelMetadataRetryPolicy()
        val ep1 = w(endpointId = "e1")
        val ep2 = w(endpointId = "e2")
        assertNotEquals(ep1, ep2)
        assertTrue(policy.reserve(ep1))
        assertTrue(policy.reserve(ep2))

        // Operation-only diff splits as well.
        val opA = w(providerModelId = "m-op", operation = "CHAT_COMPLETIONS")
        val opB = w(providerModelId = "m-op", operation = "EMBEDDINGS")
        assertNotEquals(opA, opB)

        // Slice-union single-charge shape: the scope carries exactly the seven
        // key dims (five BundleEnrichmentKey dims + endpoint + operation), so
        // per-slice quota heads union under one W rather than per-slice cells.
        val ctor =
            MyModelMetadataRetryPolicy.RefreshWorkScope::class.java.declaredConstructors.single()
        assertEquals(7, ctor.parameterTypes.size)

        // Safe-refs-only string form: carries the safe dims, never a secret.
        val rendered = w().toString()
        assertTrue(rendered.contains("p1"))
        assertTrue(rendered.contains("cred-1"))
        assertFalse(rendered.contains("sk-live-CANARY"))
        assertFalse(rendered.contains("Bearer"))
    }

    /**
     * J9 (T7, D2-YES): a started attempt always spends the budget — the
     * `zeroHttp` flag is ignored for budget purposes.
     */
    @Test
    fun j9ZeroHttpStartedStillSpent() {
        val policy = MyModelMetadataRetryPolicy()
        // Zero-HTTP started attempt still charges: lands AUTO_SPENT_NO_GOOD.
        val zero = w(providerModelId = "m-zero")
        assertTrue(policy.reserve(zero))
        assertTrue(policy.onStart(zero))
        policy.onTerminal(zero, anyGoodRow = false, cancelledAfterStart = false, zeroHttp = true)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_NO_GOOD,
            policy.autoStateOf(zero),
        )
        // Later pass stays suppressed (repeat would emit SUPPRESSED_BUDGET).
        assertFalse(policy.reserve(zero))

        // Non-zero-HTTP failure is identical: transport volume never matters.
        val plain = w(providerModelId = "m-plain")
        assertTrue(policy.reserve(plain))
        assertTrue(policy.onStart(plain))
        policy.onTerminal(plain, anyGoodRow = false, cancelledAfterStart = false, zeroHttp = false)
        assertEquals(policy.autoStateOf(zero), policy.autoStateOf(plain))

        // Zero-HTTP with a good row still succeeds (charge + SUCCEEDED).
        val goodZero = w(providerModelId = "m-goodzero")
        assertTrue(policy.reserve(goodZero))
        assertTrue(policy.onStart(goodZero))
        policy.onTerminal(goodZero, anyGoodRow = true, cancelledAfterStart = false, zeroHttp = true)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_SUCCEEDED,
            policy.autoStateOf(goodZero),
        )

        // Zero-HTTP started-cancel still counts (CANCELLED_AFTER_START).
        val cxZero = w(providerModelId = "m-cxzero")
        assertTrue(policy.reserve(cxZero))
        assertTrue(policy.onStart(cxZero))
        policy.onTerminal(cxZero, anyGoodRow = false, cancelledAfterStart = true, zeroHttp = true)
        assertEquals(
            MyModelMetadataRetryPolicy.AutoState.AUTO_SPENT_CANCELLED,
            policy.autoStateOf(cxZero),
        )
    }
}
