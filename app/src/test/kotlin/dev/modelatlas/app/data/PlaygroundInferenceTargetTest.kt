package dev.modelatlas.app.data

import dev.modelatlas.core.inference.InferenceAuthField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Production builder 的獨立 contract：不得借其他 Key/Group，未知不偷填。 */
class PlaygroundInferenceTargetTest {
    private fun record(
        credentialId: String = "safe-selected-ref",
        anonymous: Boolean = false,
        protocol: String = "RESPONSES",
    ) = ConnectionRecord(
        id = "offering-A",
        providerId = "provider-A",
        accountId = "account-A",
        credentialId = credentialId,
        endpointId = "endpoint-A",
        name = "Provider A",
        baseUrl = "https://provider.fixture.invalid/v1",
        endpointUrl = "https://provider.fixture.invalid/v1/responses",
        modelId = "reasoning-model",
        anonymous = anonymous,
        protocol = protocol,
        canonicalModelId = "canonical-reasoning",
    )

    @Test
    fun selectedBearerPreservesAllScopeDimensions() {
        val target = buildPlaygroundInferenceTarget(
            record(),
            bearerCredentialId = "safe-selected-ref",
            authFields = emptyList(),
            selectedGroup = "free-scope",
        )
        assertEquals("provider-A", target.providerId)
        assertEquals("account-A", target.accountId)
        assertEquals("endpoint-A", target.endpointId)
        assertEquals("https://provider.fixture.invalid/v1/responses", target.endpointUrl)
        assertEquals("reasoning-model", target.modelId)
        assertEquals("canonical-reasoning", target.canonicalModelId)
        assertEquals("free-scope", target.group)
        assertEquals("RESPONSES", target.operation)
        assertEquals("safe-selected-ref", target.credentialId)
        assertEquals("safe-selected-ref", target.telemetryCredentialId)
    }

    @Test
    fun customOnlyKeepsTelemetryAnchorWithoutSendingBearer() {
        val fields = listOf(InferenceAuthField("X-API-Key", "safe-custom-field"))
        val target = buildPlaygroundInferenceTarget(
            record(protocol = "CHAT_COMPLETIONS"),
            bearerCredentialId = null,
            authFields = fields,
            selectedGroup = "selected-group",
        )
        assertNull(target.credentialId)
        assertEquals("safe-selected-ref", target.telemetryCredentialId)
        assertEquals(fields, target.authFields)
        assertEquals("selected-group", target.group)
        assertEquals("CHAT_COMPLETIONS", target.operation)
    }

    @Test
    fun anonymousDoesNotForgeCredentialOrGroup() {
        val target = buildPlaygroundInferenceTarget(
            record(credentialId = "", anonymous = true, protocol = "CHAT_COMPLETIONS"),
            bearerCredentialId = null,
            authFields = emptyList(),
            selectedGroup = null,
        )
        assertNull(target.credentialId)
        assertNull(target.telemetryCredentialId)
        assertNull(target.group)
    }

    @Test
    fun selectedBearerCannotBorrowSiblingRef() {
        assertThrows(IllegalArgumentException::class.java) {
            buildPlaygroundInferenceTarget(
                record(),
                bearerCredentialId = "safe-other-account",
                authFields = emptyList(),
                selectedGroup = null,
            )
        }
    }

    @Test
    fun anonymousCannotSendBearerAndCorruptScope() {
        assertThrows(IllegalArgumentException::class.java) {
            buildPlaygroundInferenceTarget(
                record(credentialId = "", anonymous = true),
                bearerCredentialId = "safe-other",
                authFields = emptyList(),
                selectedGroup = null,
            )
        }
    }

    @Test
    fun missingNonAnonymousCredentialFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            buildPlaygroundInferenceTarget(
                record(credentialId = "", anonymous = false),
                bearerCredentialId = null,
                authFields = emptyList(),
                selectedGroup = null,
            )
        }
    }
}
