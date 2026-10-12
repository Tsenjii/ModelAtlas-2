package dev.modelatlas.core.onboarding.batch

import dev.modelatlas.core.onboarding.OnboardingFlowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嚴格非 secret 匯入 parser 的邊界：只收四個欄位、明確拒絕未知 / secret 欄位、
 * 大小上限集中、錯誤不帶 raw 輸入。
 */
class BatchJsonParserTest {

    @Test
    fun parsesArrayFormAndObjectWithProvidersForm() {
        val arrayForm = StrictBatchJsonParser.parse(
            """
            [
              {"baseUrl":"https://a.invalid/v1","websiteUrl":"https://a.invalid","suggestedModelId":"m-1","providerName":"A"},
              {"baseUrl":"https://b.invalid/v1"}
            ]
            """.trimIndent(),
        )
        val objectForm = StrictBatchJsonParser.parse(
            """{"providers":[{"baseUrl":"https://a.invalid/v1"}]}""",
        )

        assertEquals(2, arrayForm.items.size)
        assertEquals("batch-item-1", arrayForm.items[0].itemId)
        val first = arrayForm.items[0].draft
        assertEquals(OnboardingFlowType.QUICK, first.flowType)
        assertEquals("https://a.invalid/v1", first.baseUrl)
        assertEquals("https://a.invalid/", first.websiteUrl)
        assertEquals(listOf("m-1"), first.suggestedModelIds)
        assertEquals("A", first.providerName)
        assertEquals("", first.workflowId)
        // 匯入不帶任何憑證：沒有 raw key 的落點。
        assertTrue(first.credentials.isEmpty())
        assertTrue(first.safeCredentialIds.isEmpty())
        val second = arrayForm.items[1].draft
        assertNull(second.websiteUrl)
        assertNull(second.providerName)
        assertTrue(second.suggestedModelIds.isEmpty())
        assertEquals(1, objectForm.items.size)
    }

    @Test
    fun rejectsUnknownFieldsWithFixedClassification() {
        val failure = parseFailure(
            """[{"baseUrl":"https://a.invalid/v1","adapterType":"OPENAI_COMPATIBLE"}]""",
        )
        assertEquals(BatchParseError.UNKNOWN_FIELD, failure.error)
        assertEquals(0, failure.entryIndex!!)
        assertEquals("匯入不支援此欄位，只接受 baseUrl / websiteUrl / suggestedModelId / providerName", failure.message)
    }

    @Test
    fun rejectsSecretFieldsWithoutEchoingKeyOrValue() {
        val secretKey = parseFailure(
            """[{"baseUrl":"https://a.invalid/v1","apiKey":"$LEAKY_SECRET_FIXTURE"}]""",
        )
        assertEquals(BatchParseError.SECRET_FIELD_REFUSED, secretKey.error)
        assertFalse(secretKey.message!!.contains(LEAKY_SECRET_FIXTURE))
        assertFalse(secretKey.message!!.contains("apiKey"))

        val pat = parseFailure(
            """[{"baseUrl":"https://a.invalid/v1","pat":"$LEAKY_SECRET_FIXTURE"}]""",
        )
        assertEquals(BatchParseError.SECRET_FIELD_REFUSED, pat.error)

        val nestedSecret = parseFailure("""{"apiKey":"$LEAKY_SECRET_FIXTURE"}""")
        assertEquals(BatchParseError.SECRET_FIELD_REFUSED, nestedSecret.error)
    }

    @Test
    fun rejectsValuesThatLookLikeSecrets() {
        val failure = parseFailure(
            """[{"baseUrl":"https://a.invalid/v1","providerName":"$LEAKY_SECRET_FIXTURE"}]""",
        )
        assertEquals(BatchParseError.SECRET_VALUE_REFUSED, failure.error)
        assertEquals(0, failure.entryIndex!!)
        assertFalse(failure.message!!.contains(LEAKY_SECRET_FIXTURE))
    }

    @Test
    fun errorNeverContainsRawInput() {
        val raw = """[{"baseUrl":"https://a.invalid/v1","secretToken":"$LEAKY_SECRET_FIXTURE","note":"keep-me-out"}]"""
        val failure = parseFailure(raw)
        assertEquals(BatchParseError.SECRET_FIELD_REFUSED, failure.error)
        for (fragment in listOf(LEAKY_SECRET_FIXTURE, "keep-me-out", "a.invalid", raw)) {
            assertFalse(failure.message!!.contains(fragment))
        }
    }

    @Test
    fun rejectsMalformedAndUnsupportedShapes() {
        assertEquals(BatchParseError.EMPTY_INPUT, parseFailure("   ").error)
        assertEquals(BatchParseError.MALFORMED_JSON, parseFailure("""[{"baseUrl":}]""").error)
        assertEquals(BatchParseError.MALFORMED_JSON, parseFailure("""[{'baseUrl':'https://a.invalid'}]""").error)
        assertEquals(
            BatchParseError.UNSUPPORTED_ROOT,
            parseFailure("""{"baseUrl":"https://a.invalid/v1"}""").error,
        )
        assertEquals(BatchParseError.UNKNOWN_FIELD, parseFailure("""{"providers":[],"extra":1}""").error)
        assertEquals(
            BatchParseError.UNSUPPORTED_ROOT,
            parseFailure("""{"providers":{"baseUrl":"https://a.invalid/v1"}}""").error,
        )
        assertEquals(BatchParseError.EMPTY_ENTRY_LIST, parseFailure("[]").error)
        assertEquals(BatchParseError.ENTRY_NOT_OBJECT, parseFailure("""["https://a.invalid/v1"]""").error)
        assertEquals(
            BatchParseError.FIELD_TYPE_INVALID,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1","suggestedModelId":42}]""").error,
        )
        assertEquals(
            BatchParseError.FIELD_TYPE_INVALID,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1","providerName":{"x":1}}]""").error,
        )
    }

    @Test
    fun validatesUrlsAndRequiredBaseUrl() {
        assertEquals(BatchParseError.BASE_URL_MISSING, parseFailure("""[{"providerName":"A"}]""").error)
        assertEquals(
            BatchParseError.BASE_URL_INVALID,
            parseFailure("""[{"baseUrl":"http://a.invalid/v1"}]""").error,
        )
        assertEquals(
            BatchParseError.BASE_URL_INVALID,
            parseFailure("""[{"baseUrl":"https://user:pw@a.invalid/v1"}]""").error,
        )
        assertEquals(
            BatchParseError.BASE_URL_INVALID,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1?key=1"}]""").error,
        )
        assertEquals(
            BatchParseError.WEBSITE_URL_INVALID,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1","websiteUrl":"not-a-url"}]""").error,
        )
        assertEquals(
            BatchParseError.DUPLICATE_BASE_URL,
            parseFailure(
                """[{"baseUrl":"https://a.invalid/v1"},{"baseUrl":"https://a.invalid/v1/"}]""",
            ).error,
        )
    }

    @Test
    fun nullAndBlankOptionalValuesBecomeAbsent() {
        val parsed = StrictBatchJsonParser.parse(
            """[{"baseUrl":"https://a.invalid/v1","websiteUrl":null,"suggestedModelId":"  ","providerName":""}]""",
        )
        val draft = parsed.items.single().draft
        assertNull(draft.websiteUrl)
        assertNull(draft.providerName)
        assertTrue(draft.suggestedModelIds.isEmpty())
    }

    @Test
    fun sizeLimitsAreCentralisedAndEnforced() {
        // 集中技術限制：default 就是集中常數，parser 不得自行放寬。
        assertEquals(BatchLimits.DEFAULT_MAX_ITEMS, BatchLimits.DEFAULT.maxItems)
        assertEquals(BatchLimits.DEFAULT_MAX_RAW_INPUT_CHARS, BatchLimits.DEFAULT.maxRawInputChars)
        assertEquals(BatchLimits.DEFAULT_MAX_ENTRY_CHARS, BatchLimits.DEFAULT.maxEntryChars)
        assertEquals(BatchLimits.DEFAULT_MAX_URL_CHARS, BatchLimits.DEFAULT.maxUrlChars)
        assertEquals(BatchLimits.DEFAULT_MAX_MODEL_ID_CHARS, BatchLimits.DEFAULT.maxModelIdChars)
        assertEquals(BatchLimits.DEFAULT_MAX_PROVIDER_NAME_CHARS, BatchLimits.DEFAULT.maxProviderNameChars)

        val tooMany = (1..BatchLimits.DEFAULT_MAX_ITEMS + 1).joinToString(",", "[", "]") { index ->
            """{"baseUrl":"https://a$index.invalid/v1"}"""
        }
        assertEquals(BatchParseError.TOO_MANY_ENTRIES, parseFailure(tooMany).error)

        val oversized = "[" + " ".repeat(BatchLimits.DEFAULT_MAX_RAW_INPUT_CHARS) + """{"baseUrl":"https://a.invalid/v1"}]"""
        assertEquals(BatchParseError.INPUT_TOO_LARGE, parseFailure(oversized).error)

        val longUrl = "https://a.invalid/" + "x".repeat(BatchLimits.DEFAULT_MAX_URL_CHARS)
        assertEquals(
            BatchParseError.FIELD_TOO_LONG,
            parseFailure("""[{"baseUrl":"$longUrl"}]""").error,
        )

        val limits = BatchLimits(maxItems = 1, maxEntryChars = 32)
        assertEquals(
            BatchParseError.TOO_MANY_ENTRIES,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1"},{"baseUrl":"https://b.invalid/v1"}]""", limits).error,
        )
        assertEquals(
            BatchParseError.ENTRY_TOO_LARGE,
            parseFailure("""[{"baseUrl":"https://a.invalid/v1","providerName":"name that is too long"}]""", limits).error,
        )
    }

    private fun parseFailure(raw: String, limits: BatchLimits = BatchLimits.DEFAULT): BatchParseException =
        runCatching { StrictBatchJsonParser.parse(raw, limits) }.exceptionOrNull() as? BatchParseException
            ?: error("expected BatchParseException")

    private companion object {
        const val LEAKY_SECRET_FIXTURE = "sk-00000000000000000000000000000000"
    }
}
