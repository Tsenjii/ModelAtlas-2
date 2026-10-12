package dev.modelatlas.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.core.onboarding.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 驗證 UI 提交與證據邊界；不取代 Compose 互動／還原驗收。 */
@RunWith(AndroidJUnit4::class)
class ReviewBoundaryDeviceTest {
    private fun ready() = OnboardingSnapshot(
        workflowId = "review-fixture", flowType = OnboardingFlowType.ADVANCED,
        status = OnboardingStatus.REVIEW, manualModelIds = listOf("manual-model"),
        reviewItems = ReviewItems(),
        stepStatuses = mapOf(OnboardingStepId.REVIEW to OnboardingStepStatus.SUCCESS),
    )

    @Test fun unknownPriceAndUnverifiedManualModelDoNotBlockCommit() {
        val snapshot = ready()
        assertEquals(PriceStatus.UNKNOWN, snapshot.reviewItems!!.pricingStatus)
        assertEquals(null, snapshot.reviewItems!!.modelsVerified)
        assertTrue(canSubmitReview(snapshot, false, false))
        val waiting = snapshot.copy(status = OnboardingStatus.NEEDS_INPUT,
            stepStatuses = mapOf(OnboardingStepId.REVIEW to OnboardingStepStatus.NEEDS_INPUT),
            results = listOf(StepResult(OnboardingStepId.REVIEW, OnboardingStepStatus.NEEDS_INPUT, reason = OnboardingFailureReason.REVIEW_NOT_APPROVED)))
        assertTrue(canSubmitReview(waiting, false, false))
        assertFalse(canSubmitReview(waiting.copy(results = emptyList()), false, false))
    }

    @Test fun dirtyBusyEmptySelectionAndTerminalStatesCannotSubmit() {
        val snapshot = ready()
        assertFalse(canSubmitReview(snapshot, false, true))
        assertFalse(canSubmitReview(snapshot, true, false))
        assertFalse(canSubmitReview(snapshot.copy(selectedModelIds = emptyList()), false, false))
        assertFalse(canSubmitReview(snapshot.copy(manualModelIds = emptyList(), suggestedModelIds = listOf("suggested-only")), false, false))
        listOf(OnboardingStatus.BLOCKED, OnboardingStatus.COMMITTED, OnboardingStatus.ABANDONED).forEach {
            assertFalse(canSubmitReview(snapshot.copy(status = it), false, false))
        }
    }

    @Test fun missingAuthAnonymousConfirmationAndIncompleteReviewCannotSubmit() {
        val snapshot = ready()
        assertFalse(canSubmitReview(snapshot.copy(authDecision = AuthDecision(missingCredential = true)), false, false))
        assertFalse(canSubmitReview(snapshot.copy(reviewItems = ReviewItems(auth = ReviewAuth(failureReason = OnboardingFailureReason.AUTH_FAILED))), false, false))
        assertFalse(canSubmitReview(snapshot.copy(authModeOverride = AuthMode.NONE), false, false))
        assertTrue(canSubmitReview(snapshot.copy(authModeOverride = AuthMode.NONE, anonymousConfirmed = true), false, false))
        assertFalse(canSubmitReview(snapshot.copy(stepStatuses = emptyMap()), false, false))
        assertFalse(canSubmitReview(snapshot.copy(stepStatuses = snapshot.stepStatuses + (OnboardingStepId.INSPECT to OnboardingStepStatus.FAILED)), false, false))
    }

    @Test fun evidenceIsMappedToConclusionWithoutResurrectingSupersededEvidence() {
        val snapshot = ready().copy(results = listOf(
            StepResult(OnboardingStepId.DETECT_TYPE, OnboardingStepStatus.SUCCESS, evidenceIds = listOf("type-evidence")),
            StepResult(OnboardingStepId.PRICING_QUOTA_NOTICE, OnboardingStepStatus.SUCCESS, evidenceIds = listOf("price-evidence")),
        ))
        assertEquals(listOf("type-evidence"), reviewEvidence(snapshot, ReviewItem.PROVIDER_TYPE))
        assertEquals(listOf("price-evidence"), reviewEvidence(snapshot, ReviewItem.PRICING))
        assertTrue(reviewEvidence(snapshot, ReviewItem.AUTH).isEmpty())
        val stale = snapshot.copy(results = snapshot.results + StepResult(OnboardingStepId.DETECT_TYPE, OnboardingStepStatus.SUPERSEDED))
        assertTrue(reviewEvidence(stale, ReviewItem.PROVIDER_TYPE).isEmpty())
        assertTrue(reviewEvidence(snapshot.copy(stepStatuses = mapOf(OnboardingStepId.DETECT_TYPE to OnboardingStepStatus.STALE)), ReviewItem.PROVIDER_TYPE).isEmpty())
    }

    @Test fun manualOverridesCannotInheritHighProbeConfidence() {
        val review = ReviewItems(confidences = mapOf(ReviewItem.PROVIDER_TYPE to EvidenceConfidence.HIGH, ReviewItem.QUOTA to EvidenceConfidence.HIGH))
        val snapshot = ready().copy(adapterTypeOverride = AdapterType.OPENAI_COMPATIBLE,
            manualQuotaState = dev.modelatlas.core.domain.QuotaState.QUOTA_AVAILABLE)
        assertEquals(EvidenceConfidence.LOW, reviewConfidence(snapshot, review, ReviewItem.PROVIDER_TYPE))
        assertEquals(EvidenceConfidence.LOW, reviewConfidence(snapshot, review, ReviewItem.QUOTA))
        assertEquals(EvidenceConfidence.UNKNOWN, reviewConfidence(snapshot, review, ReviewItem.MODELS_VERIFIED))
    }
}
