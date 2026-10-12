package dev.modelatlas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LMS-002 外部映射純函式測試（TRACEABILITY LMS-002）。
 *
 * 只測 `mapExternalToCanonical` 純邏輯：無 DB、無網路、無 secret；
 * fixture 僅存於 test sourceSet，不當產品資料。
 */
class ExternalMappingTest {

    @Test
    fun blankReturnsUnknown() {
        assertNull(mapExternalToCanonical(""))
    }

    @Test
    fun whitespaceBlankReturnsUnknown() {
        assertNull(mapExternalToCanonical("   "))
    }

    @Test
    fun separatorOnlyNormalizesToBlankReturnsUnknown() {
        assertNull(mapExternalToCanonical("___...///"))
    }

    @Test
    fun exactAliasHitReturnsCanonical() {
        val result = mapExternalToCanonical(
            "GPT-4o-2024-05",
            exactAliasIndex = mapOf("GPT-4o-2024-05" to "gpt-4o"),
            knownCanonicalIds = setOf("gpt-4o", "other-model"),
        )
        assertEquals("gpt-4o", result)
    }

    @Test
    fun normalizedDirectHitReturnsCanonical() {
        val result = mapExternalToCanonical(
            "GPT-4o",
            knownCanonicalIds = setOf("gpt-4o"),
        )
        assertEquals("gpt-4o", result)
    }

    @Test
    fun separatorsNormalizedToKnown() {
        val result = mapExternalToCanonical(
            "Llama 3.1 70B",
            knownCanonicalIds = setOf("llama-3-1-70b"),
        )
        assertEquals("llama-3-1-70b", result)
    }

    @Test
    fun slashAndColonSeparatorsMatchKnown() {
        val result = mapExternalToCanonical(
            "org/model:v1",
            knownCanonicalIds = setOf("org-model-v1"),
        )
        assertEquals("org-model-v1", result)
    }

    @Test
    fun latestSuffixStripsAndMatchesKnown() {
        val result = mapExternalToCanonical(
            "gpt-4o-latest",
            knownCanonicalIds = setOf("gpt-4o"),
        )
        assertEquals("gpt-4o", result)
    }

    @Test
    fun normalizedAliasHitIsCaseAndSeparatorInsensitive() {
        val result = mapExternalToCanonical(
            "GPT_4o",
            normalizedAliasIndex = mapOf("gpt-4o" to "gpt-4o-canonical"),
        )
        assertEquals("gpt-4o-canonical", result)
    }

    @Test
    fun unknownReturnsNullInsteadOfSynthesizing() {
        val result = mapExternalToCanonical(
            "some-brand-new-model-xyz-123",
            knownCanonicalIds = setOf("gpt-4o"),
        )
        assertNull(result)
    }

    @Test
    fun knownMismatchWithoutAliasReturnsNull() {
        val result = mapExternalToCanonical(
            "claude-3-5",
            knownCanonicalIds = setOf("gpt-4o"),
            normalizedAliasIndex = mapOf("gpt-4o" to "gpt-4o"),
        )
        assertNull(result)
    }

    @Test
    fun exactAliasWinsOverKnownNormalization() {
        val result = mapExternalToCanonical(
            "Foo",
            exactAliasIndex = mapOf("Foo" to "explicit-canonical"),
            knownCanonicalIds = setOf("foo"),
        )
        assertEquals("explicit-canonical", result)
    }

    // ---- P7-B2 MINOR：順序對齊 ModelIdentity（exact→alias→normalized）＋來源/confidence 結構 ----

    @Test
    fun aliasWinsOverKnownNormalization() {
        // 同一正規化名同時命中 alias 與 known 時，alias 優先（對齊 ModelIdentity）。
        val result = mapExternalToCanonical(
            "GPT_4o",
            normalizedAliasIndex = mapOf("gpt-4o" to "alias-canonical"),
            knownCanonicalIds = setOf("gpt-4o"),
        )
        assertEquals("alias-canonical", result)
    }

    @Test
    fun decisionExactIsHigh() {
        val decision = mapExternalToCanonicalDecision(
            "Foo",
            exactAliasIndex = mapOf("Foo" to "explicit-canonical"),
            knownCanonicalIds = setOf("foo"),
            source = "fixture-source",
        )
        assertEquals("explicit-canonical", decision.canonicalId)
        assertEquals("fixture-source", decision.source)
        assertEquals(dev.modelatlas.core.domain.MappingConfidence.HIGH.name, decision.confidence)
    }

    @Test
    fun decisionAliasIsMedium() {
        val decision = mapExternalToCanonicalDecision(
            "GPT_4o",
            normalizedAliasIndex = mapOf("gpt-4o" to "alias-canonical"),
            knownCanonicalIds = setOf("gpt-4o"),
            source = "fixture-source",
        )
        assertEquals("alias-canonical", decision.canonicalId)
        assertEquals(dev.modelatlas.core.domain.MappingConfidence.MEDIUM.name, decision.confidence)
    }

    @Test
    fun decisionKnownFallbackIsLow() {
        val decision = mapExternalToCanonicalDecision(
            "GPT-4o",
            knownCanonicalIds = setOf("gpt-4o"),
            source = "fixture-source",
        )
        assertEquals("gpt-4o", decision.canonicalId)
        assertEquals(dev.modelatlas.core.domain.MappingConfidence.LOW.name, decision.confidence)
    }

    @Test
    fun decisionMissIsUnknownWithSource() {
        val decision = mapExternalToCanonicalDecision(
            "some-brand-new-model-xyz-123",
            knownCanonicalIds = setOf("gpt-4o"),
            source = "fixture-source",
        )
        assertNull(decision.canonicalId)
        assertEquals("fixture-source", decision.source)
        assertEquals(dev.modelatlas.core.domain.MappingConfidence.UNKNOWN.name, decision.confidence)
    }
}
