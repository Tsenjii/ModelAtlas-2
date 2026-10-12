package dev.modelatlas.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.core.onboarding.CredentialLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用既有 AndroidJUnit4；驗證表單 helper 邊界，不冒充 Compose 畫面／還原測試。 */
@RunWith(AndroidJUnit4::class)
class FormBoundaryDeviceTest {
    private val validJson = """[{"baseUrl":"https://example.com/v1","providerName":"測試供應商"}]"""

    @Test
    fun rejectedSecretAndMalformedInputNeverReachCallbackAndAreCleared() {
        val rejected = listOf(
            """[{"baseUrl":"https://example.com/v1","apiKey":"fixture-only"}]""",
            """[{"baseUrl":"https://example.com/v1","pat":"fixture-only"}]""",
            """[{"baseUrl":"https://example.com/v1","providerName":"Bearer fixture-only"}]""",
            """{"apiKey":"fixture-only""",
        )
        rejected.forEach { raw ->
            val input = TransientFormInput().apply { value = raw }
            var calls = 0
            assertFalse(input.submitBatch { calls++ })
            assertEquals(0, calls)
            assertEquals("", input.value)
        }
    }

    @Test
    fun acceptedMetadataIsHandedOffOnceThenCleared() {
        val input = TransientFormInput().apply { value = validJson }
        var calls = 0
        assertTrue(input.submitBatch { raw -> calls++; assertEquals(validJson, raw) })
        assertEquals(1, calls)
        assertEquals("", input.value)
        assertFalse(input.submitBatch { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailureStillClearsInput() {
        val input = TransientFormInput().apply { value = validJson }
        val failure = IllegalStateException("測試失敗")
        var caught = false
        try {
            input.submitBatch { throw failure }
        } catch (e: IllegalStateException) {
            assertTrue(e === failure)
            caught = true
        }
        assertTrue(caught)
        assertEquals("", input.value)
    }

    @Test
    fun busyCloseCancelsInsteadOfNavigatingAndClearsBeforeCallback() {
        val input = TransientFormInput().apply { value = "fixture-only" }
        var cancels = 0
        var closes = 0
        closeBatchForm(true, input, { cancels++; assertEquals("", input.value) }, { closes++ })
        assertEquals(1, cancels)
        assertEquals(0, closes)
        input.value = "fixture-only"
        closeBatchForm(false, input, { cancels++ }, { closes++; assertEquals("", input.value) })
        assertEquals(1, cancels)
        assertEquals(1, closes)
    }

    @Test
    fun advancedClearIncludesInferenceAccountHeaderAndQuerySecrets() {
        val inference = CredentialDraft().apply { label = "推理"; secret = "fixture-inference" }
        val account = CredentialDraft().apply { label = "帳號"; secret = "fixture-account" }
        val header = AuthDraft().apply { name = "X-Api-Key"; value = "fixture-header" }
        val query = AuthDraft().apply { location = CredentialLocation.QUERY; name = "api_key"; value = "fixture-query" }
        repeat(2) { clearAdvancedFormSecrets(listOf(inference), listOf(account), listOf(header, query)) }
        assertEquals("", inference.secret)
        assertEquals("", account.secret)
        assertEquals("", header.value)
        assertEquals("", query.value)
        assertEquals("推理", inference.label)
        assertEquals("帳號", account.label)
        assertEquals("X-Api-Key", header.name)
        assertEquals("api_key", query.name)
    }

    @Test
    fun passwordTransformationMasksEvenMalformedMultilineJson() {
        val raw = "{\n\"apiKey\":\"fixture-only\""
        val masked = PasswordVisualTransformation().filter(AnnotatedString(raw)).text.text
        assertEquals(raw.length, masked.length)
        assertTrue(masked.all { it == '\u2022' })
        assertFalse(masked.contains("fixture-only"))
    }
}
