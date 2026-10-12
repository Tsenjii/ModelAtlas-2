package dev.modelatlas.core.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {
    @Test
    fun classificationHoldsPrimaryPlusSets() {
        val c = ModelClassification(
            primaryCategory = ModelCategory.CHAT_REASONING,
            capabilities = setOf(Capability.CODING, Capability.TOOL_CALLING),
            modalities = setOf(Modality.TEXT_INPUT, Modality.TEXT_OUTPUT),
            endpointTypes = setOf(EndpointType.CHAT_COMPLETIONS),
        )
        assertEquals(ModelCategory.CHAT_REASONING, c.primaryCategory)
        assertTrue(c.capabilities.contains(Capability.TOOL_CALLING))
        assertTrue(c.modalities.contains(Modality.TEXT_INPUT))
        assertTrue(c.endpointTypes.contains(EndpointType.CHAT_COMPLETIONS))
    }

    @Test
    fun healthOfficialAndObservedAreIndependent() {
        val h = ProviderHealth(
            providerId = "p1",
            official = HealthState.HEALTHY,
            observed = HealthState.OFFLINE,
        )
        assertEquals(HealthState.HEALTHY, h.official)
        assertEquals(HealthState.OFFLINE, h.observed)
        assertEquals(HealthState.UNKNOWN, ProviderHealth("p2").observed)
    }

    @Test
    fun checkinPendingFollowsDeviceLocalDate() {
        val today = LocalDate.of(2026, 9, 27)
        assertTrue(isCheckinPending(today, null))
        assertFalse(
            isCheckinPending(
                today,
                CheckinRecord("p1", localDate = today, completedAt = 100L, source = "USER"),
            ),
        )
        assertTrue(
            isCheckinPending(
                today,
                CheckinRecord("p1", localDate = today, completedAt = null, source = "USER"),
            ),
        )
        assertTrue(
            isCheckinPending(
                today,
                CheckinRecord(
                    "p1",
                    localDate = today.minusDays(1),
                    completedAt = 100L,
                    source = "USER",
                ),
            ),
        )
    }

    @Test
    fun candidateFunnelStatesAreComplete() {
        val expected = setOf(
            "DISCOVERED", "VERIFYING", "VERIFIED", "HIGH_VALUE_GREY_ZONE",
            "STALE", "FAILED", "REJECTED",
        )
        assertEquals(expected, CandidateState.values().map { it.name }.toSet())
    }

    @Test
    fun candidateCarriesRejectionOrDowngradeReason() {
        val rejected = CrawlerCandidate(
            state = CandidateState.REJECTED,
            rejectionReason = CandidateDecisionReason.AUTH_FAILED,
        )
        val grey = CrawlerCandidate(
            state = CandidateState.HIGH_VALUE_GREY_ZONE,
            downgradeReason = CandidateDecisionReason.EVIDENCE_INSUFFICIENT,
        )
        assertEquals(CandidateDecisionReason.AUTH_FAILED, rejected.rejectionReason)
        assertEquals(CandidateDecisionReason.EVIDENCE_INSUFFICIENT, grey.downgradeReason)
    }

    @Test
    fun quotaDefaultsToUnknown() {
        val scope = PricingScope("p1", null, null, "m", null, "ep", "text")
        assertEquals(QuotaState.UNKNOWN, QuotaSnapshot(scope = scope, observedAt = 0L).state)
    }

    @Test
    fun classificationEnumsMatchSpec() {
        assertEquals(
            setOf(
                "CHAT_REASONING", "CODING", "IMAGE", "AUDIO",
                "EMBEDDING", "RERANK", "SAFETY", "OTHER",
            ),
            ModelCategory.values().map { it.name }.toSet(),
        )
        assertEquals(
            setOf(
                "REASONING", "CODING", "TOOL_CALLING", "FUNCTION_CALLING",
                "STRUCTURED_OUTPUT", "JSON_MODE", "VISION", "FILES",
                "IMAGE_GENERATION", "IMAGE_EDITING", "AUDIO_INPUT", "AUDIO_OUTPUT",
                "WEB_SEARCH", "COMPUTER_USE", "LONG_CONTEXT", "EMBEDDING",
                "RERANK", "SAFETY", "OTHER",
            ),
            Capability.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun quotaStatesMatchSpec() {
        assertEquals(
            setOf(
                "QUOTA_AVAILABLE", "QUOTA_LOW", "QUOTA_EXHAUSTED", "RATE_LIMIT_ONLY",
                "PUBLIC_POOL", "UNLIMITED_CLAIMED", "NO_KNOWN_CONSUMABLE_QUOTA", "UNKNOWN",
            ),
            QuotaState.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun modalityAndEndpointEnumsMatchSpec() {
        assertEquals(
            setOf(
                "TEXT_INPUT", "TEXT_OUTPUT", "IMAGE_INPUT", "IMAGE_OUTPUT",
                "AUDIO_INPUT", "AUDIO_OUTPUT", "FILE_INPUT",
            ),
            Modality.values().map { it.name }.toSet(),
        )
        assertEquals(
            setOf(
                "CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE",
                "IMAGES_GENERATION", "IMAGES_EDIT", "AUDIO", "EMBEDDINGS", "RERANK", "CUSTOM",
            ),
            EndpointType.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun checkinCarriesCompletedAtAndSource() {
        val r = CheckinRecord(
            providerId = "p1",
            localDate = LocalDate.of(2026, 9, 27),
            completedAt = 123L,
            source = "USER",
        )
        assertEquals(123L, r.completedAt)
        assertEquals("USER", r.source)
    }
}
