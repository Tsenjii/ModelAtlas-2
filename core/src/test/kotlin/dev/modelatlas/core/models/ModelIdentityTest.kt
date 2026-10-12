package dev.modelatlas.core.models

import dev.modelatlas.core.domain.CanonicalModel
import dev.modelatlas.core.domain.canonicalIdFor
import dev.modelatlas.core.domain.normalizeModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MODEL-001／002：正規化／alias 優先序／去重。純函式，不碰 DB、不帶 secret。 */
class ModelIdentityTest {

    @Test
    fun normalizeLowercasesAndTrims() {
        assertEquals("gpt-4o", normalizeModelId("  GPT-4o  "))
    }

    @Test
    fun normalizeUnifiesSeparators() {
        assertEquals("gpt-4o", normalizeModelId("gpt_4o"))
        assertEquals("gpt-4o", normalizeModelId("gpt.4o"))
        assertEquals("gpt-4o", normalizeModelId("gpt 4o"))
        assertEquals("gpt-4o", normalizeModelId("gpt/4o"))
        assertEquals("a-b", normalizeModelId("a//b"))
        assertEquals("a-b", normalizeModelId("a--b"))
        assertEquals("a-b", normalizeModelId("-a_b-"))
    }

    @Test
    fun normalizeStripsLatestQualifier() {
        assertEquals("my-model", normalizeModelId("my-model:latest"))
        assertEquals("my-model", normalizeModelId("my-model@latest"))
        assertEquals("my-model", normalizeModelId("MY_MODEL:LATEST"))
    }

    @Test
    fun normalizeKeepsVersionNumbersDistinct() {
        assertEquals("model-v1-0", normalizeModelId("model_v1.0"))
        assertEquals("model-v1-0", normalizeModelId("MODEL V1 0"))
        assertFalse(normalizeModelId("model-v1") == normalizeModelId("model-v2"))
    }

    @Test
    fun normalizeBlankStaysBlankForCallerRejection() {
        assertEquals("", normalizeModelId("   "))
        assertEquals("", normalizeModelId("---"))
    }

    @Test
    fun canonicalIdIsStableAndDeterministic() {
        assertEquals(canonicalIdFor("GPT-4o"), canonicalIdFor("gpt_4o"))
        assertEquals("gpt-4o", canonicalIdFor("GPT-4o"))
    }

    @Test
    fun resolvePrefersExactOverNormalizedAlias() {
        val resolved = resolveCanonicalId(
            "GPT-4o",
            exactAliasIndex = mapOf("GPT-4o" to "canonical:vendor-x"),
            normalizedAliasIndex = mapOf("gpt-4o" to "canonical:vendor-y"),
        )
        assertEquals("canonical:vendor-x", resolved)
    }

    @Test
    fun resolveFallsBackToNormalizedAliasWhenNoExact() {
        val resolved = resolveCanonicalId(
            "GPT_4o",
            exactAliasIndex = mapOf("other" to "canonical:other"),
            normalizedAliasIndex = mapOf("gpt-4o" to "canonical:vendor-y"),
        )
        assertEquals("canonical:vendor-y", resolved)
    }

    @Test
    fun resolveFallsBackToNormalizedCanonicalWithoutAlias() {
        assertEquals("gpt-4o", resolveCanonicalId("GPT_4o"))
        assertEquals("gpt-4o", resolveCanonicalId("GPT-4o", emptyMap(), emptyMap()))
    }

    @Test
    fun isSameCanonicalIgnoresCaseAndSeparators() {
        assertTrue(isSameCanonical("GPT-4o", "gpt_4o"))
        assertTrue(isSameCanonical("my-model:latest", "MY-MODEL"))
        assertFalse(isSameCanonical("model-a", "model-b"))
    }

    @Test
    fun deduplicateReturnsExistingForSameNormalized() {
        val existing = listOf(CanonicalModel("gpt-4o", "gpt-4o", "GPT-4o"))
        val deduped = deduplicateCanonical(existing, "GPT_4o", "別名顯示")
        assertEquals("gpt-4o", deduped.canonicalId)
        assertEquals(1, (existing + deduped).distinctBy { it.canonicalId }.size)
    }

    @Test
    fun deduplicateCreatesNewForDistinctModel() {
        val existing = listOf(CanonicalModel("gpt-4o", "gpt-4o"))
        val created = deduplicateCanonical(existing, "claude-opus", "Claude")
        assertEquals("claude-opus", created.canonicalId)
        assertEquals("claude-opus", created.normalizedName)
        assertEquals("Claude", created.displayName)
    }

    @Test
    fun deduplicateFallsBackToTrimmedRawAsDisplayName() {
        val created = deduplicateCanonical(emptyList(), "  New-Model_X  ")
        assertEquals("new-model-x", created.canonicalId)
        assertEquals("New-Model_X", created.displayName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deduplicateRejectsBlankNormalized() {
        deduplicateCanonical(emptyList(), "   ")
    }
}
