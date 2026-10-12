package dev.modelatlas.app.ui

import dev.modelatlas.app.ProviderModelActionState
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import org.junit.Assert.*
import org.junit.Test

/** PROV-001 / MODEL-006: production projection gates, independent of fixture UI feedback. */
class ProviderModelVerificationProjectionTest {
    private val scope = ProviderDetailVerificationScope("p", "a", "c", "e", "CHAT_COMPLETIONS", "g")
    private val key = ProviderDetailVerifiedKey("e", "m")
    private fun success() = PerformanceSample(
        id = "sample", providerId = "p", accountId = "a", safeCredentialId = "c",
        endpointId = "e", providerModelId = "m", canonicalModelId = null, group = "g", operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.MANUAL_TEST, requestStartedAt = 1, completedAt = 2,
        outcome = Outcome.SUCCESS,
    )

    @Test fun manualSuccessIsVerifiedOnlyInItsCompleteSelectedScope() {
        assertEquals(setOf(key), ProviderDetailLoader.verifiedKeysOf(listOf(success()), scope))
        assertTrue(ProviderDetailLoader.verifiedKeysOf(listOf(success()), null).isEmpty())
        assertTrue(ProviderDetailLoader.verifiedKeysOf(listOf(success()), scope.copy(operation = "")).isEmpty())
    }

    @Test fun eachForeignScopeAndNonFinalOrWrongSourceIsRejectedIndependently() {
        val s = success()
        val rejected = listOf(
            s.copy(providerId = "other"), s.copy(accountId = "other"), s.copy(safeCredentialId = "other"),
            s.copy(endpointId = "other"), s.copy(operation = "RESPONSES"), s.copy(group = null),
            s.copy(group = "g "), s.copy(providerModelId = "m "), s.copy(completedAt = null),
            s.copy(outcome = Outcome.CANCELLED), s.copy(outcome = Outcome.RUNNING),
            s.copy(source = TelemetrySource.PLAYGROUND),
        )
        rejected.forEach { sample ->
            assertTrue("Unrelated/non-final sample cannot mark a model verified: $sample",
                ProviderDetailLoader.verifiedKeysOf(listOf(sample), scope).isEmpty())
        }
    }

    @Test fun hiddenCandidateCannotBorrowAnEarlierMatchingSuccess() {
        val candidate = OfferingEntity("manual", "p", "e", "m", hidden = true)
        val rows = ProviderDetailLoader.modelsOf(listOf(candidate), verifiedKeys = setOf(key))
        assertFalse(rows.single().verified)
        assertTrue(rows.single().hidden)
        assertTrue(ProviderDetailLoader.modelsOf(listOf(candidate.copy(hidden = false)),
            verifiedKeys = setOf(key)).single().verified)
    }

    @Test fun sameModelOnAnotherEndpointCannotDonateVerification() {
        val selected = OfferingEntity("selected", "p", "e", "m", hidden = false)
        val other = selected.copy(id = "other", endpointId = "e2", verificationEvidenceId = "other-evidence")
        val rows = ProviderDetailLoader.modelsOf(listOf(other, selected),
            verifiedKeys = setOf(ProviderDetailVerifiedKey("e2", "m")), preferredEndpointId = "e")
        assertEquals(1, rows.size)
        assertFalse(rows.single().verified)
    }

    @Test fun staleScopeDropsActionAndVerificationButRetainsProviderLastGood() {
        val detail = ProviderDetailState(
            header = ProviderDetailHeader("p", "provider", "https://fixture.invalid/v1"),
            models = listOf(ProviderDetailModel("m", verified = true)),
            actionScope = ProviderDetailActionScope("connection", "selected key"),
            history = listOf(ProviderDetailHistoryEvent("time", "section", "state")),
        )
        val downgraded = providerDetailScopeProjection(detail, fresh = false)
        assertNull(downgraded.actionScope)
        assertFalse(downgraded.models.single().verified)
        assertEquals(detail.header, downgraded.header)
        assertEquals(detail.history, downgraded.history)
        assertEquals(detail.models.single().modelId, downgraded.models.single().modelId)
        assertEquals(detail, providerDetailScopeProjection(detail, fresh = true))
    }

    @Test fun draftClearRequiresNewSuccessfulAddRatherThanTestFailureOrCancellation() {
        val add = ProviderModelActionState(kind = "ADD", status = "SUCCESS", clearToken = 2)
        assertTrue(shouldClearModelDraft(1, add))
        assertFalse(shouldClearModelDraft(2, add))
        listOf("FAILURE", "CANCELLED", "RUNNING").forEach {
            assertFalse(shouldClearModelDraft(1, add.copy(status = it)))
        }
        assertFalse(shouldClearModelDraft(1, add.copy(kind = "TEST")))
    }

    @Test fun connectionChoiceDistinguishesKeyEndpointAndUnknownFromAnonymous() {
        fun label(keyLabel: String, endpoint: String, anonymous: Boolean) = connectionChoiceDetail(
            keyLabel, "m", 1, endpoint, "CHAT_COMPLETIONS", anonymous,
            "Unknown", "Anonymous", "Key: ", "Model: ", "Endpoint: ", "Operation: ",
        )
        assertNotEquals(label("key one", "e", false), label("key two", "e", false))
        assertNotEquals(label("key one", "e", false), label("key one", "e2", false))
        assertTrue(label("", "e", false).contains("Key: Unknown"))
        assertTrue(label("", "e", true).contains("Key: Anonymous"))
    }
}
