// FR-003 primary-quote exact-scope pure-unit matrix — T1.
// Test vectors: the committed vector list in this file (T1 pure-unit leg; FR-003
// ACCEPTED — docs/TRACEABILITY.md:19, backend green 2f0785a/37382168445; audit row
// docs/CORRECTNESS_REPAIR_AUDIT.md:131).
// PORTS LANDED in this same integration slice (working tree, committed together):
//   [P-H2] ModelDetailLoader.SelectedOfferingScope (endpointId/operation,
//       defaults null = unknown scope), internal matchesPrimaryPriceScope (11-arg
//       detached parity copy of ModelRowMapper.matchesPriceHead) and internal
//       selectPrimaryQuote (maxOf observedAt + singleOrNull tie→null). Same-module
//       internal visibility is what this app-unit-test sourceSet uses.
//   [P-H7] readPrimaryPriceRow `?: ""` unknown-bucket mapping + missing-fact →
//       PriceState.UNKNOWN (toDetailPricePoint) is exercised by the T2 device file's
//       seam. The negative-fixed vector below pins the selector half: a
//       uniquely-newest UNKNOWN-state negative-fixed point is returned as-is (no
//       zero-fill, no lastGood borrow).
//
// Vectors (preland-review §3 T1, unchanged): per-dimension isolation
// (account/group/endpoint/operation/credential/account-blank), blank-group reject
// ("" vs null → reject), unknown↔unknown match, no-wildcard either direction,
// tie→null, unique-newest→point, no-lastGood (empty→null), negative-fixed→
// UNKNOWN-state quote, cross-chunk tie (2 modelIds, same newest observedAt → null).

package dev.modelatlas.app.ui

import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-003 exact selected-scope predicate + primary-quote selector matrix (T1 pure-unit;
 * PROPOSED PORT consumers marked in-line). Eighteen bodies against the [PORT P-H2]
 * signatures (rebased patch hunks old@142/+83):
 *
 * matcher (12): exact accept; per-dimension isolation rejects (account, group,
 * endpoint, operation, credential, account-blank); blank-group "" vs null reject
 * (both directions); unknown↔unknown accept; unknown≠wildcard both directions;
 * provider/model scope gate.
 *
 * selector (6): empty→null (no lastGood); unique-newest→point; tie→null;
 * cross-model tie→null; negative-fixed UNKNOWN-state quote returned as-is;
 * newest-wins regardless of sourceLabel (no source ranking).
 */
class ModelDetailPrimaryQuoteTest {

    private val selected = SelectedKey(accountId = "acct-A", credentialId = "cred-A", group = "vip")

    // [PORT P-H2] SelectedOfferingScope (not yet landed); defaults null = unknown scope.
    private val offering =
        ModelDetailLoader.SelectedOfferingScope(endpointId = "ep-1", operation = "CHAT_COMPLETIONS")

    private val scopedModels = setOf("m-x")

    /**
     * [PORT P-H2] 11-arg matcher passthrough. Nullable dims are passed straight through
     * (SQL NULL rows reach the matcher as null; DetailPricePoint carries "" for the same
     * unknown bucket per [PORT P-H7] `?: ""` — both spellings are exercised below).
     */
    private fun matches(
        providerId: String = "p1",
        accountId: String? = "acct-A",
        credentialRef: String? = "cred-A",
        modelId: String = "m-x",
        group: String? = "vip",
        endpointId: String? = "ep-1",
        operation: String? = "CHAT_COMPLETIONS",
        requestingProviderId: String = "p1",
        scopedModelIds: Set<String> = scopedModels,
        selectedKey: SelectedKey = selected,
        scope: ModelDetailLoader.SelectedOfferingScope? = offering,
    ): Boolean = ModelDetailLoader.matchesPrimaryPriceScope(
        providerId = providerId,
        accountId = accountId,
        credentialRef = credentialRef,
        modelId = modelId,
        group = group,
        endpointId = endpointId,
        operation = operation,
        requestingProviderId = requestingProviderId,
        scopedModelIds = scopedModelIds,
        selected = selectedKey,
        offeringScope = scope,
    )

    /** HEAD DetailPricePoint fixture (endpointId/operation are non-null Strings; "" = unknown bucket). */
    private fun quotePoint(
        observedAt: Long,
        modelId: String = "m-x",
        input: Double? = 1.0,
        output: Double? = 2.0,
        fixed: Double? = null,
        cache: Double? = 0.5,
        state: PriceState = PriceState.PAID,
        sourceLabel: String = "official",
    ) = DetailPricePoint(
        observedAt = observedAt,
        inputPrice = input,
        outputPrice = output,
        fixedPrice = fixed,
        cachePrice = cache,
        state = state,
        sourceLabel = sourceLabel,
        providerId = "p1",
        accountId = "acct-A",
        credentialRef = "cred-A",
        modelId = modelId,
        group = "vip",
        endpointId = "ep-1",
        operation = "CHAT_COMPLETIONS",
    )

    // ---------------------------------------------------------------------
    // Matcher: exact accept + per-dimension isolation.
    // ---------------------------------------------------------------------

    @Test
    fun exactSelectedScopeMatches() {
        assertTrue(matches())
    }

    @Test
    fun accountMismatchRejects() {
        assertFalse(matches(accountId = "acct-B"))
    }

    @Test
    fun groupMismatchRejects() {
        assertFalse(matches(group = "default"))
    }

    @Test
    fun endpointMismatchRejects() {
        assertFalse(matches(endpointId = "ep-2"))
    }

    @Test
    fun operationMismatchRejects() {
        assertFalse(matches(operation = "EMBEDDINGS"))
    }

    @Test
    fun credentialMismatchRejects() {
        assertFalse(matches(credentialRef = "cred-B"))
    }

    @Test
    fun nullCredentialPointMatchesAnySelectedKey() {
        // Official PAT pricing (null credentialRef) is scope-free vs the selected key.
        assertTrue(matches(credentialRef = null))
        assertTrue(
            matches(
                credentialRef = null,
                selectedKey = selected.copy(credentialId = "cred-other"),
            ),
        )
    }

    @Test
    fun blankGroupDoesNotMatchNullSelectedGroup() {
        // Raw exact: null ≠ "" ≠ concrete (no normalization on group).
        assertFalse(matches(group = "", selectedKey = selected.copy(group = null)))
        assertFalse(matches(group = null, selectedKey = selected.copy(group = "")))
        assertFalse(matches(group = "", selectedKey = selected.copy(group = "vip")))
    }

    @Test
    fun unknownEndpointAndOperationMatchUnknownOffering() {
        val unknownScope = ModelDetailLoader.SelectedOfferingScope(endpointId = null, operation = null)
        assertTrue(matches(endpointId = "", operation = "", scope = unknownScope))
        assertTrue(matches(endpointId = null, operation = null, scope = unknownScope))
        assertTrue(matches(endpointId = "UNKNOWN", operation = "  unknown ", scope = unknownScope))
        assertTrue(matches(endpointId = "", operation = "", scope = ModelDetailLoader.SelectedOfferingScope()))
    }

    @Test
    fun unknownScopeIsNotWildcard() {
        val unknownScope = ModelDetailLoader.SelectedOfferingScope(endpointId = null, operation = null)
        // Concrete point vs unknown offering: reject (no wildcard on the offering side).
        assertFalse(matches(endpointId = "ep-1", operation = "CHAT_COMPLETIONS", scope = unknownScope))
        assertFalse(matches(endpointId = "ep-1", scope = unknownScope))
        assertFalse(matches(operation = "CHAT_COMPLETIONS", scope = unknownScope))
        // Unknown point vs concrete offering: reject (no wildcard on the point side).
        assertFalse(matches(endpointId = "", operation = ""))
        assertFalse(matches(endpointId = null, operation = null))
    }

    @Test
    fun blankAccountSelectedMatchesOnlyUnknownAccount() {
        val blankAccount = selected.copy(accountId = "")
        assertTrue(matches(accountId = null, selectedKey = blankAccount))
        assertTrue(matches(accountId = "", selectedKey = blankAccount))
        assertFalse(matches(accountId = "acct-A", selectedKey = blankAccount))
    }

    @Test
    fun providerModelScopeGate() {
        assertFalse(matches(providerId = "p2"))
        assertFalse(matches(requestingProviderId = "p2"))
        assertFalse(matches(modelId = "m-other"))
        assertFalse(matches(modelId = ""))
        assertFalse(matches(modelId = "   "))
    }

    // ---------------------------------------------------------------------
    // Selector: [PORT P-H2] selectPrimaryQuote (candidates pre-filtered).
    // ---------------------------------------------------------------------

    @Test
    fun emptyCandidatesReturnNullNeverLastGood() {
        // [PORT P-H2] empty → null; never a stale/lastGood fallback.
        assertNull(ModelDetailLoader.selectPrimaryQuote(emptyList()))
    }

    @Test
    fun uniqueNewestCandidateWins() {
        val older = quotePoint(observedAt = 1_000L, input = 1.0)
        val newer = quotePoint(observedAt = 2_000L, input = 9.0)
        assertEquals(newer, ModelDetailLoader.selectPrimaryQuote(listOf(older, newer)))
        assertEquals(newer, ModelDetailLoader.selectPrimaryQuote(listOf(newer, older)))
    }

    @Test
    fun tieAtNewestObservedAtReturnsNull() {
        // Same newest observedAt across sourceLabel/id = no ranking basis → null (Unknown).
        val a = quotePoint(observedAt = 2_000L, input = 1.0, sourceLabel = "official")
        val b = quotePoint(observedAt = 2_000L, input = 9.0, sourceLabel = "manual")
        assertNull(ModelDetailLoader.selectPrimaryQuote(listOf(a, b)))
    }

    @Test
    fun crossModelTieAtNewestReturnsNull() {
        // Cross-chunk shape: per-chunk newest rows merge; same observedAt on 2 modelIds → null.
        val a = quotePoint(observedAt = 2_000L, modelId = "m-x", input = 1.0)
        val b = quotePoint(observedAt = 2_000L, modelId = "m-y", input = 1.0)
        assertNull(
            ModelDetailLoader.selectPrimaryQuote(
                listOf(a, b),
            ),
        )
    }

    @Test
    fun negativeFixedUnknownStateQuoteReturnedAsIs() {
        // Negative fixedPrice latest → UNKNOWN-state quote (never lastGood-as-current):
        // the uniquely-newest point is returned untouched — state stays UNKNOWN,
        // the negative fixed value is preserved (no zero-fill, no filtering).
        val olderGood = quotePoint(observedAt = 1_000L, state = PriceState.PAID, fixed = 3.0)
        val negativeFixedUnknown = quotePoint(
            observedAt = 2_000L,
            state = PriceState.UNKNOWN,
            fixed = -1.0,
            input = null,
            output = null,
            cache = null,
        )
        val picked = ModelDetailLoader.selectPrimaryQuote(listOf(olderGood, negativeFixedUnknown))
        assertEquals(negativeFixedUnknown, picked)
        assertEquals(PriceState.UNKNOWN, picked?.state)
        assertEquals(-1.0, picked?.fixedPrice)
    }

    @Test
    fun newestWinsRegardlessOfSourceLabel() {
        // No source ranking: an older official point never beats a newer manual one.
        val olderOfficial = quotePoint(observedAt = 1_000L, input = 1.0, sourceLabel = "official")
        val newerManual = quotePoint(observedAt = 2_000L, input = 9.0, sourceLabel = "manual")
        assertEquals(
            newerManual,
            ModelDetailLoader.selectPrimaryQuote(listOf(olderOfficial, newerManual)),
        )
    }
}
