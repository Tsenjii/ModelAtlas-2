package dev.modelatlas.core.onboarding.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次匯入的 fail-closed 守門邊界（`docs/PROVIDER_ONBOARDING_CONTRACT.md` §3）。
 *
 * 聚焦「疑似 secret 一律拒絕、且不回顯命中內容」與排程狀態語意，互補
 * [BatchJsonParserTest]（parser 形狀 / 大小上限）與 [BatchOnboardingImporterTest]（執行器）。
 * 純 JVM、無 Android：直接釘住 [BatchSecretGuard]、[StrictBatchJsonParser] 與 [BatchItemStatus]。
 */
class BatchGuardTest {

    @Test
    fun secretKeyNamesAreDetectedFailClosedAcrossNormalisedForms() {
        val secretKeys = listOf(
            "apiKey", "API_KEY", "api-key", "Api Key",
            "authorization", "Authorization", "bearer", "Bearer",
            "x-token", "accessToken", "refresh_token",
            "client-secret", "private_key", "private-key",
            "passwd", "password", "passphrase",
            "jwt", "cookie", "session", "credential", "pat",
        )
        for (key in secretKeys) {
            assertTrue("must treat as secret key: $key", BatchSecretGuard.keyLooksLikeSecret(key))
        }
        val safeKeys = listOf(
            "baseUrl", "websiteUrl", "providerName", "suggestedModelId",
            "group", "tags", "notes", "modelId",
        )
        for (key in safeKeys) {
            assertFalse("must treat as safe key: $key", BatchSecretGuard.keyLooksLikeSecret(key))
        }
    }

    @Test
    fun secretLookingValuesAreDetectedAcrossKnownPrefixes() {
        val secretValues = listOf(
            "sk-abcdefgh12345678",
            "gsk_abcdefgh1234",
            "AIzaSyA1234567890",
            "nvapi-1234567890",
            "hf_abcdefgh",
            "xai-abcdefghijklmnop",
            "Bearer abcdefghijkl",
        )
        for (value in secretValues) {
            assertTrue("must treat as secret value: $value", BatchSecretGuard.valueLooksLikeSecret(value))
        }
        val safeValues = listOf(
            "https://api.example.invalid/v1",
            "https://example.invalid",
            "fixture-model-a",
            "gpt-4o",
            "provider-name",
        )
        for (value in safeValues) {
            assertFalse("must not treat as secret: $value", BatchSecretGuard.valueLooksLikeSecret(value))
        }
    }

    @Test
    fun parserRefusesSecretKeysAtRootAndEntryWithoutEchoing() {
        val root = parseFailure("""{"apiKey":"sk-abcdefgh12345678"}""")
        assertEquals(BatchParseError.SECRET_FIELD_REFUSED, root.error)
        assertFalse(root.message!!.contains("apiKey"))
        assertFalse(root.message!!.contains("sk-"))

        val entry = parseFailure(
            """[{"baseUrl":"https://a.invalid/v1","authorization":"Bearer abcdefghijkl"}]""",
        )
        assertEquals(BatchParseError.SECRET_FIELD_REFUSED, entry.error)
        assertEquals(0, entry.entryIndex)
        assertFalse(entry.message!!.contains("a.invalid"))
    }

    @Test
    fun parserRefusesSecretLookingValuesInFreeTextWithoutEchoing() {
        val failure = parseFailure(
            """[{"baseUrl":"https://a.invalid/v1","providerName":"sk-abcdefgh12345678"}]""",
        )
        assertEquals(BatchParseError.SECRET_VALUE_REFUSED, failure.error)
        assertEquals(0, failure.entryIndex)
        assertFalse(failure.message!!.contains("sk-"))
        assertFalse(failure.message!!.contains("a.invalid"))
    }

    @Test
    fun parserGuardIsCaseInsensitiveAndCoversDashUnderscoreForms() {
        assertEquals(
            BatchParseError.SECRET_FIELD_REFUSED,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1","API-KEY":"x"}]""").error,
        )
        assertEquals(
            BatchParseError.SECRET_FIELD_REFUSED,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1","Authorization":"x"}]""").error,
        )
        assertEquals(
            BatchParseError.SECRET_FIELD_REFUSED,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1","refresh_token":"x"}]""").error,
        )
    }

    @Test
    fun batchItemStatusTerminalAndResumableFollowContract() {
        val terminal = listOf(
            BatchItemStatus.AWAITING_REVIEW,
            BatchItemStatus.NEEDS_INPUT,
            BatchItemStatus.VALIDATION_FAILED,
            BatchItemStatus.RATE_LIMITED,
            BatchItemStatus.BLOCKED,
            BatchItemStatus.FAILED,
        )
        for (status in terminal) assertTrue("$status must be terminal", status.terminal)

        val nonTerminal = listOf(
            BatchItemStatus.PENDING,
            BatchItemStatus.RUNNING,
            BatchItemStatus.INTERRUPTED,
            BatchItemStatus.CANCELLED,
        )
        for (status in nonTerminal) assertFalse("$status must not be terminal", status.terminal)

        for (status in listOf(
            BatchItemStatus.PENDING,
            BatchItemStatus.AWAITING_REVIEW,
            BatchItemStatus.NEEDS_INPUT,
            BatchItemStatus.VALIDATION_FAILED,
            BatchItemStatus.RATE_LIMITED,
            BatchItemStatus.BLOCKED,
            BatchItemStatus.FAILED,
        )) {
            assertFalse("$status must not be resumable", status.resumable)
        }
        for (status in listOf(
            BatchItemStatus.RUNNING,
            BatchItemStatus.INTERRUPTED,
            BatchItemStatus.CANCELLED,
        )) {
            assertTrue("$status must be resumable", status.resumable)
        }
    }

    private fun parseFailure(raw: String): BatchParseException =
        runCatching { StrictBatchJsonParser.parse(raw) }.exceptionOrNull() as? BatchParseException
            ?: error("expected BatchParseException")
}
