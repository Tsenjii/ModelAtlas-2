// =============================================================================
// PK-16 — parseScopeKey Int-overflow fail-closed regression (test-only slice).
// Spec: /tmp/opencode/ai11-spec-pk16-overflow.md §5 (production lands
// concurrently in SnapshotFacts.kt:281, subtraction-form guard).
// This file ADDS the new wrap vectors; pinned vectors (§4) unchanged.
// Style: SnapshotSliceNamespaceIdentityTest T4/T5 (AndroidJUnit4, direct
// parseScopeKey asserts, (a)/(b)/... sub-cases, fail-closed null, no throw).
// No DB, no probes, no secrets. CI-only verification: assertNull fails on
// StringIndexOutOfBoundsException (throw != null), so no try/catch needed.
// =============================================================================

package dev.modelatlas.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.core.domain.PricingScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PK-16 regression: `pos + len` Int-wrap window must return null, never throw.
 * KDoc contract (SnapshotFacts.kt:266-268): format mismatch -> null, caller
 * fail-closed, never guess. Valid storageKey() outputs unaffected.
 */
@RunWith(AndroidJUnit4::class)
class ScopeKeyOverflowTest {

    // -------------------------------------------------------------------------
    // Wrap window: pos >= 1 + in-Int-range len with pos + len > Int.MAX_VALUE.
    // Old guard (pos + len > key.length) wrapped negative and passed; line 282
    // substring(pos, negativeEnd) threw. New guard (len > key.length - pos)
    // rejects -> null. Each assertNull below fails if the old code throws.
    // -------------------------------------------------------------------------
    @Test
    fun intOverflowWrapsFailClosed_null_noThrow() {
        // (a) pos=11 wrap: "2147483647:x" (len=Int.MAX_VALUE, sum wraps -2147483638).
        assertNull(parseScopeKey("2147483647:x"))
        // (b) pos=15 wrap: second dim len wraps (sum wraps -2147483644).
        assertNull(parseScopeKey("1:a:2147483637:b"))
        // (c) pos=2 wrap (optional vector, spec §5): first dim "0:" then wrap.
        assertNull(parseScopeKey("0:2147483637:x"))
    }

    // -------------------------------------------------------------------------
    // Boundary / first-dim locks: equivalence with the old guard off the window.
    // -------------------------------------------------------------------------
    @Test
    fun overflowBoundaryAndFirstDim_failClosed() {
        // (a) Boundary non-wrap: sum == Int.MAX_VALUE, old guard already null.
        assertNull(parseScopeKey("2147483646:x"))
        // (b) No colon: first-dim prefix can never reach the wrap window.
        assertNull(parseScopeKey("2147483647"))
        // (c) Out-of-Int-range prefix already null via toIntOrNull (spec §4 pin).
        assertNull(parseScopeKey("2147483648:x"))
    }

    // -------------------------------------------------------------------------
    // Classic malformed pins (task vectors): oversized len, negative len,
    // truncated / trailing bytes — all null under both guards.
    // -------------------------------------------------------------------------
    @Test
    fun malformedLenTruncatedTrailing_failClosed() {
        // (a) Oversized len: 999 > remaining.
        assertNull(parseScopeKey("999:ab"))
        // (b) Negative len (non -1 sentinel).
        assertNull(parseScopeKey("-2:ab"))
        // (c) Empty / no-colon.
        assertNull(parseScopeKey(""))
        assertNull(parseScopeKey("malformed-not-a-scope-key"))
        // (d) Truncated valid key (drop last byte) -> null.
        val valid = PricingScope("p15", "a15", null, "m", null, "e15", "CHAT_COMPLETIONS").storageKey()
        assertTrue(parseScopeKey(valid)?.size == 7)
        assertNull(parseScopeKey(valid.dropLast(1)))
        // (e) Trailing bytes -> null (pos != key.length).
        assertNull(parseScopeKey(valid + "X"))
        assertNull(parseScopeKey(valid + ":0:"))
    }

    // -------------------------------------------------------------------------
    // Valid keys unaffected: storageKey() outputs satisfy len <= key.length-pos,
    // so the subtraction guard accepts exactly as the old guard did.
    // -------------------------------------------------------------------------
    @Test
    fun validKeys_parseUnchanged() {
        // (a) Round-trip: 7 dims, nulls where credentialId/group are null.
        val scope = PricingScope("p15", "a15", null, "fixture-model", null, "e15", "CHAT_COMPLETIONS")
        val parsed = parseScopeKey(scope.storageKey())
        assertTrue(parsed != null && parsed!!.size == 7)
        assertEquals(listOf("p15", "a15", null, "fixture-model", null, "e15", "CHAT_COMPLETIONS"), parsed)
        // (b) Empty-dims pin (spec §4): seven "-0:" dims -> seven empty strings.
        val empties = parseScopeKey("-0:-0:-0:-0:-0:-0:-0:")
        assertTrue(empties != null && empties!!.size == 7)
        assertTrue(empties!!.all { it == "" })
        // (c) providerScopeRange lo prefix shape: a REAL storageKey-style key whose
        // first dim is the provider (length-prefixed, every dim carries its own prefix).
        assertTrue(parseScopeKey("3:p15-1:13:fixture-model-1:3:e1516:CHAT_COMPLETIONS-1:")?.size == 7)
    }
}
