package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaygroundVisionGateTest {
    private val available = ModelRow(
        providerId = "p",
        providerModelId = "vision-model",
        canonicalId = "canonical",
        endpointId = "e",
        operation = "CHAT_COMPLETIONS",
        group = "free",
        selectedKeyUsable = true,
        supportsVision = true,
        capabilities = setOf(Capability.VISION, Capability.CODING),
    )
    private fun resolve(rows: List<ModelRow>, group: String? = "free", operation: String = "CHAT_COMPLETIONS") =
        selectedVisionCapabilities(rows, "p", "vision-model", "e", operation, group)

    @Test fun knownSelectedVisionPassesThroughAllActualCapabilities() {
        assertEquals(setOf(Capability.VISION, Capability.CODING), resolve(listOf(available)))
    }

    @Test fun unknownOrUnusableCannotSendImages() {
        assertTrue(resolve(emptyList()).isEmpty())
        assertTrue(resolve(listOf(available.copy(capabilities = emptySet(), supportsVision = false))).isEmpty())
        assertTrue(resolve(listOf(available.copy(selectedKeyUsable = false))).isEmpty())
    }

    @Test fun foreignGroupEndpointModelOrOperationCannotLendVision() {
        assertTrue(resolve(listOf(available), group = "paid").isEmpty())
        assertTrue(resolve(listOf(available.copy(endpointId = "other"))).isEmpty())
        assertTrue(resolve(listOf(available.copy(providerModelId = "other"))).isEmpty())
        assertTrue(resolve(listOf(available.copy(providerId = "other"))).isEmpty())
        assertTrue(resolve(listOf(available), operation = "RESPONSES").isEmpty())
    }
}
