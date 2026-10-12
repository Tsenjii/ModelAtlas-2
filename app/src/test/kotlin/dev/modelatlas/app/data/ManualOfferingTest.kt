package dev.modelatlas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MODEL-006 JVM 單測（fixture 僅存於 test sourceSet，不當產品資料；無 secret、無網路/DB）。
 */
class ManualOfferingTest {

    private fun draft(
        providerId: String = "p1",
        endpointId: String = "e1",
        modelId: String = "m-manual-1",
        evidenceId: String = "ev-create-1",
    ) = ManualOfferingStore.createHiddenOffering(
        providerId = providerId,
        endpointId = endpointId,
        providerModelId = modelId,
        evidenceId = evidenceId,
        observedAt = 1000L,
    )

    @Test
    fun create_returnsHiddenManualDraft() {
        val d = draft()
        assertTrue(d.manual)
        assertEquals("MANUAL", d.source)
        assertTrue(d.hidden)
        assertFalse(ManualOfferingStore.isVisible(d))
        assertFalse(ManualOfferingStore.isUsable(d))
        assertEquals("ev-create-1", d.evidenceId)
    }

    @Test
    fun create_duplicateReturnsExistingId() {
        val first = draft()
        val second = ManualOfferingStore.createHiddenOffering(
            providerId = "p1",
            endpointId = "e1",
            providerModelId = "m-manual-1",
            evidenceId = "ev-create-2",
            observedAt = 2000L,
            existing = listOf(first),
        )
        assertEquals(first.id, second.id)
        assertEquals(first.evidenceId, second.evidenceId)
    }

    @Test
    fun create_sameModelDifferentEndpoint_isolated() {
        val first = draft(endpointId = "e1")
        val second = ManualOfferingStore.createHiddenOffering(
            providerId = "p1",
            endpointId = "e2",
            providerModelId = "m-manual-1",
            evidenceId = "ev-create-2",
            observedAt = 2000L,
            existing = listOf(first),
        )
        assertTrue(first.id != second.id)
    }

    @Test
    fun create_sameModelDifferentProvider_isolated() {
        val first = draft(providerId = "p1")
        val second = ManualOfferingStore.createHiddenOffering(
            providerId = "p2",
            endpointId = "e1",
            providerModelId = "m-manual-1",
            evidenceId = "ev-create-2",
            observedAt = 2000L,
            existing = listOf(first),
        )
        assertTrue(first.id != second.id)
    }

    @Test
    fun create_blankModelId_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualOfferingStore.createHiddenOffering(
                providerId = "p1",
                endpointId = "e1",
                providerModelId = "  ",
                evidenceId = "ev-1",
                observedAt = 1000L,
            )
        }
    }

    @Test
    fun create_officialSource_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualOfferingStore.createHiddenOffering(
                providerId = "p1",
                endpointId = "e1",
                providerModelId = "m-1",
                source = "OFFICIAL",
                evidenceId = "ev-1",
                observedAt = 1000L,
            )
        }
    }

    @Test
    fun create_requiresEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualOfferingStore.createHiddenOffering(
                providerId = "p1",
                endpointId = "e1",
                providerModelId = "m-1",
                evidenceId = "  ",
                observedAt = 1000L,
            )
        }
    }

    @Test
    fun create_userSource_accepted() {
        val d = ManualOfferingStore.createHiddenOffering(
            providerId = "p1",
            endpointId = "e1",
            providerModelId = "m-1",
            source = "USER",
            evidenceId = "ev-1",
            observedAt = 1000L,
        )
        assertEquals("USER", d.source)
        assertTrue(d.hidden)
    }

    @Test
    fun promote_withEvidence_becomesVisibleAndUsable() {
        val promoted = ManualOfferingStore.promoteOnVerified(draft(), "ev-verify-ok")
        assertTrue(ManualOfferingStore.isVisible(promoted))
        assertTrue(ManualOfferingStore.isUsable(promoted))
        assertEquals("ev-verify-ok", promoted.verificationEvidenceId)
        assertEquals("ev-create-1", promoted.evidenceId)
        assertTrue(promoted.manual)
    }

    @Test
    fun promote_blankEvidence_rejectedAndStaysHidden() {
        val d = draft()
        assertThrows(IllegalArgumentException::class.java) {
            ManualOfferingStore.promoteOnVerified(d, "  ")
        }
        assertTrue(d.hidden)
        assertFalse(ManualOfferingStore.isUsable(d))
    }

    @Test
    fun freeNameWithoutEvidence_staysHidden() {
        val d = draft(modelId = "super-free-ultra")
        assertFalse(ManualOfferingStore.isUsable(d))
        assertFalse(ManualOfferingStore.canPromote(d, ""))
    }

    @Test
    fun downgrade_keepsDraft_notRemoved() {
        val failed = ManualOfferingStore.downgradeOnFailure(
            draft(),
            failureEvidenceId = "ev-fail-1",
            failureReason = "PROBE_429",
        )
        assertTrue(failed.hidden)
        assertFalse(failed.removed)
        assertFalse(ManualOfferingStore.isUsable(failed))
        assertEquals("ev-create-1", failed.evidenceId)
        assertEquals("ev-fail-1", failed.lastFailureEvidenceId)
    }

    @Test
    fun downgrade_verifiedStaysVisible_failurePreservesLastGood() {
        val promoted = ManualOfferingStore.promoteOnVerified(draft(), "ev-verify-ok")
        val failed = ManualOfferingStore.downgradeOnFailure(
            promoted,
            failureEvidenceId = "ev-fail-2",
            failureReason = "TIMEOUT",
        )
        assertTrue(ManualOfferingStore.isVisible(failed))
        assertTrue(ManualOfferingStore.isUsable(failed))
        assertFalse(failed.removed)
        assertEquals("ev-verify-ok", failed.verificationEvidenceId)
    }
}
