package dev.modelatlas.app.ui

import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PK-28 / S27 — Provider Detail model-list stable identity (JVM half).
 *
 * TRACEABILITY: PK-28 (production contract already landed at HEAD:
 * `ProviderDetailScreen.kt` `items(models, key = { it.modelId },
 * contentType = { PROVIDER_DETAIL_MODEL_CONTENT_TYPE })` with
 * `PROVIDER_DETAIL_MODEL_CONTENT_TYPE = "providerDetailModel"`).
 *
 * The Compose `items(...)` key itself is not unit-testable on JVM; these tests pin
 * the loader/sort side of the key contract that makes `modelId` a safe stable key:
 * - `ProviderDetailLoader.modelsOf` trims + `seen`-dedupes, so emitted ids are unique
 *   and non-blank (TV-1..TV-3);
 * - `sortDetailModels` only reorders (FREE_NOW stable sink), never drops/duplicates,
 *   so keys are stable across sorts and a FREE_NOW flip only moves rows (TV-4,
 *   complements `ProviderDetailStateTest.freeNowModelsSortFirstStably`
 *   and `ProviderDetailFlowTest.modelsTabSortsFreeNowFirstWithStableSink`,
 *   which are NOT modified here).
 *
 * Fixtures live only in this test sourceSet, never product data.
 */
class ProviderDetailModelKeysTest {

    private fun offering(id: String, endpointId: String, providerModelId: String): OfferingEntity =
        OfferingEntity(id, "p1", endpointId, providerModelId)

    private fun assertUniqueNonBlankIds(rows: List<ProviderDetailModel>) {
        val ids = rows.map { it.modelId }
        assertTrue("emitted modelIds must all be non-blank: $ids", ids.all { it.isNotBlank() })
        assertEquals("emitted modelIds must be unique (loader key contract): $ids", ids.size, ids.toSet().size)
    }

    // TV-1: same providerModelId on two endpoints/offering ids -> one row.
    @Test
    fun sameModelIdAcrossEndpointsDedupesToSingleRow() {
        val rows = ProviderDetailLoader.modelsOf(
            listOf(
                offering("o1", "e1", "shared-model"),
                offering("o2", "e2", "shared-model"),
                offering("o3", "e1", "other-model"),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.modelId == "shared-model" })
        assertEquals(1, rows.count { it.modelId == "other-model" })
        assertUniqueNonBlankIds(rows)
    }

    // TV-2: "m", "m ", "  " -> single "m" row; blank skipped.
    @Test
    fun trimCollisionDedupesAndBlankIsSkipped() {
        val rows = ProviderDetailLoader.modelsOf(
            listOf(
                offering("o1", "e1", "m"),
                offering("o2", "e1", "m "),
                offering("o3", "e1", "  "),
            ),
        )
        assertEquals(listOf("m"), rows.map { it.modelId })
        assertUniqueNonBlankIds(rows)
    }

    // TV-3: flip preferredEndpointId -> same modelId set, still unique.
    @Test
    fun preferredEndpointFlipKeepsSameUniqueModelIdSet() {
        val offerings = listOf(
            offering("o1", "e1", "m1"),
            offering("o2", "e2", "m1"),
            offering("o3", "e2", "m2"),
        )
        val fromE1 = ProviderDetailLoader.modelsOf(offerings, preferredEndpointId = "e1")
        val fromE2 = ProviderDetailLoader.modelsOf(offerings, preferredEndpointId = "e2")
        assertEquals(setOf("m1", "m2"), fromE1.map { it.modelId }.toSet())
        assertEquals(fromE1.map { it.modelId }.toSet(), fromE2.map { it.modelId }.toSet())
        assertUniqueNonBlankIds(fromE1)
        assertUniqueNonBlankIds(fromE2)
    }

    // TV-4: keys stable across sortDetailModels; FREE_NOW flip reorders only.
    @Test
    fun sortKeepsKeysStableAndFreeNowFlipOnlyReorders() {
        val base = listOf(
            ProviderDetailModel("a", PriceState.PAID.name),
            ProviderDetailModel("b", PriceState.PAID.name),
            ProviderDetailModel("c", PriceState.UNKNOWN.name),
        )
        val sorted = sortDetailModels(base)
        assertEquals(base.map { it.modelId }.toSet(), sorted.map { it.modelId }.toSet())
        assertUniqueNonBlankIds(sorted)

        val flipped = listOf(
            ProviderDetailModel("a", PriceState.PAID.name),
            ProviderDetailModel("b", PriceState.FREE_NOW.name),
            ProviderDetailModel("c", PriceState.UNKNOWN.name),
        )
        val resorted = sortDetailModels(flipped)
        // Never drops/duplicates; promoted row first, rest keep relative order.
        assertEquals(listOf("b", "a", "c"), resorted.map { it.modelId })
        assertUniqueNonBlankIds(resorted)
    }

    // Landed-contract pin: contentType value production passes to items(...).
    @Test
    fun modelContentTypeContractValue() {
        assertEquals("providerDetailModel", PROVIDER_DETAIL_MODEL_CONTENT_TYPE)
    }
}
