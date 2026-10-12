package dev.modelatlas.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.app.R
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.core.domain.ModelCategory
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6 INT-2：ModelRowMapper 裝置測試（掛載＋篩選＋空態）。
 *
 * TRACEABILITY：MODEL-001／002／003（canonical／alias／classification 合成）、
 * MODEL-004（搜尋結果列）、MODEL-005（篩選）、MODEL-006（hidden 排除）、MODEL-007（selected-key）、
 * MODEL-009（price／quota head）。
 * fixture 只存在本 test sourceSet，以 `*Fixture` 明示，不作產品資料。
 */
@RunWith(AndroidJUnit4::class)
class ModelRowMapperDeviceTest {

    private fun offeringFixture(
        id: String = "o1",
        providerId: String = "p1",
        modelId: String = "gpt-4o",
        endpointId: String = "e1",
        hidden: Boolean = false,
        verified: Boolean = false,
        source: String = "MANUAL",
    ) = OfferingRef(
        id = id,
        providerId = providerId,
        endpointId = endpointId,
        providerModelId = modelId,
        operation = "CHAT_COMPLETIONS",
        manual = true,
        source = source,
        hidden = hidden,
        verified = verified,
    )

    private fun priceHead(
        providerId: String = "p1",
        accountId: String? = "a1",
        credentialId: String? = null,
        modelId: String = "gpt-4o",
        endpointId: String? = "e1",
        state: String = "FREE_NOW",
        input: Double? = 0.0,
        output: Double? = 0.0,
    ) = PriceQuotaHead(
        kind = ModelRowMapper.KIND_PRICE,
        providerId = providerId,
        accountId = accountId,
        credentialId = credentialId,
        modelId = modelId,
        endpointId = endpointId,
        operation = "CHAT_COMPLETIONS",
        state = state,
        inputPrice = input,
        outputPrice = output,
        observedAt = 1_700_000_000_000L,
    )

    private fun selected(
        accountId: String = "a1",
        credentialId: String = "",
        group: String? = null,
    ) = SelectedKey(accountId, credentialId, group)

    // ---- 掛載（合成） ----

    @Test
    fun mapsOfferingWithFreePriceFromHeadOnly() {
        val rows = ModelRowMapper.build(
            offerings = listOf(offeringFixture()),
            availabilities = emptyList(),
            selected = selected(),
            priceQuotaHeads = listOf(priceHead(state = "FREE_NOW")),
        )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("p1", row.providerId)
        assertEquals("gpt-4o", row.providerModelId)
        assertEquals(PriceState.FREE_NOW, row.priceState)
        assertEquals(0.0, row.inputPrice!!, 0.0)
        assertFalse(shouldDimRow(row))
        assertEquals(R.string.price_free_now, priceBadgeText(row.priceState))
    }

    @Test
    fun missingPriceAndQuotaAreUnknownNeverZeroOrFree() {
        val rows = ModelRowMapper.build(
            offerings = listOf(offeringFixture()),
            availabilities = emptyList(),
            selected = selected(),
        )
        val row = rows.single()
        assertEquals(PriceState.UNKNOWN, row.priceState)
        assertEquals(QuotaState.UNKNOWN, row.quotaState)
        assertEquals(R.string.value_unknown, priceBadgeText(row.priceState))
        assertTrue(shouldDimRow(row))
    }

    @Test
    fun hiddenOfferingExcludedFromMount() {
        val rows = ModelRowMapper.build(
            offerings = listOf(offeringFixture(id = "o1", modelId = "a"), offeringFixture(id = "o2", modelId = "b", hidden = true)),
            availabilities = emptyList(),
            selected = selected(),
        )
        assertEquals(1, rows.size)
        assertEquals("a", rows.single().providerModelId)
    }

    @Test
    fun aliasDisplayNameAndClassificationComposeWithoutCrossSourceMerge() {
        val rows = ModelRowMapper.build(
            offerings = listOf(offeringFixture(modelId = "gpt-4o-2024")),
            availabilities = emptyList(),
            selected = selected(),
            canonicals = listOf(CanonicalRef("gpt4o", "GPT-4o")),
            aliases = listOf(
                AliasRef("p1", "gpt-4o-2024", "gpt4o"),
                AliasRef("p2", "gpt-4o-mini", "gpt4o"),
            ),
            classifications = listOf(
                ClassificationRef("gpt4o", "MANUAL", ModelCategory.OTHER.name, setOf("CODING")),
                ClassificationRef("gpt4o", "CATALOG", ModelCategory.CHAT_REASONING.name, setOf("VISION", "TOOL_CALLING")),
            ),
        )
        val row = rows.single()
        assertEquals("gpt4o", row.canonicalId)
        assertEquals("GPT-4o", row.displayName)
        assertEquals(listOf("gpt-4o-mini"), row.aliasNames)
        assertEquals(ModelCategory.CHAT_REASONING, row.primaryCategory)
        assertTrue(row.supportsVision)
        assertTrue(row.hasTools)
        assertFalse(row.supportsFiles)
        assertEquals(emptySet<String>(), row.modalities.map { it.name }.toSet())
    }

    @Test
    fun divergentPriceHeadsFailClosedToUnknown() {
        val rows = ModelRowMapper.build(
            offerings = listOf(offeringFixture()),
            availabilities = emptyList(),
            selected = selected(),
            priceQuotaHeads = listOf(
                priceHead(state = "FREE_NOW"),
                priceHead(state = "PAID", input = 1.0, output = 2.0),
            ),
        )
        assertEquals(PriceState.UNKNOWN, rows.single().priceState)
    }

    @Test
    fun sourceLabelNeverImpersonates() {
        val unverified = ModelRowMapper.build(
            offerings = listOf(offeringFixture(source = "OFFICIAL")),
            availabilities = emptyList(),
            selected = selected(),
        ).single()
        assertEquals("official", unverified.sourceLabel)
        assertEquals("official", sourceDisplayLabel(unverified.sourceLabel, "未知"))
        assertEquals(null, verificationLabel(unverified.verified))

        val unknown = ModelRowMapper.build(
            offerings = listOf(offeringFixture(source = "WAT")),
            availabilities = emptyList(),
            selected = selected(),
        ).single()
        assertEquals("", unknown.sourceLabel)
        assertEquals("未知", sourceDisplayLabel(unknown.sourceLabel, "未知"))
    }

    @Test
    fun selectedKeyAvailabilityProjectionDrivesFallbackState() {
        val match = ModelRowMapper.build(
            offerings = listOf(offeringFixture()),
            availabilities = listOf(
                AvailabilityRef("av1", "o1", "a1", "k1", endpointId = "e1", operation = "CHAT_COMPLETIONS", priceState = "PAID", quotaState = "QUOTA_LOW"),
            ),
            selected = selected(credentialId = "k1"),
        ).single()
        assertEquals(PriceState.PAID, match.priceState)
        assertEquals(QuotaState.QUOTA_LOW, match.quotaState)
        assertTrue(match.selectedKeyUsable)

        val otherKey = ModelRowMapper.build(
            offerings = listOf(offeringFixture()),
            availabilities = listOf(
                AvailabilityRef("av1", "o1", "a1", "k1", endpointId = "e1", operation = "CHAT_COMPLETIONS", priceState = "PAID", quotaState = "QUOTA_LOW"),
            ),
            selected = selected(credentialId = "k2"),
        ).single()
        assertEquals(PriceState.UNKNOWN, otherKey.priceState)
        assertFalse(otherKey.selectedKeyUsable)
    }

    // ---- 篩選 ----

    @Test
    fun freeNowFilterKeepsOnlyFreeAndSortsFreeFirst() {
        val rows = ModelRowMapper.build(
            offerings = listOf(
                offeringFixture(id = "o1", modelId = "paid"),
                offeringFixture(id = "o2", modelId = "free"),
            ),
            availabilities = emptyList(),
            selected = selected(),
            priceQuotaHeads = listOf(priceHead(modelId = "free", state = "FREE_NOW")),
        )
        val freeOnly = filterModels(rows, ModelFilter(freeNowOnly = true))
        assertEquals(listOf("free"), freeOnly.map { it.providerModelId })
        assertEquals(listOf("free", "paid"), sortForDefaultView(rows).map { it.providerModelId })
    }

    @Test
    fun searchFindsByAliasAndCanonicalNormalized() {
        val rows = ModelRowMapper.build(
            offerings = listOf(offeringFixture(modelId = "gpt-4o-2024")),
            availabilities = emptyList(),
            selected = selected(),
            canonicals = listOf(CanonicalRef("gpt4o", "GPT-4o")),
            aliases = listOf(
                AliasRef("p1", "gpt-4o-2024", "gpt4o"),
                AliasRef("p2", "gpt-4o-mini", "gpt4o"),
            ),
        )
        assertTrue(rankResults("gpt-4o-mini", rows).isNotEmpty())
        assertTrue(rankResults("gpt 4o", rows).isNotEmpty())
        assertTrue(rankResults("zzzz-not-a-model", rows).isEmpty())
    }

    // ---- 空態 ----

    @Test
    fun emptyMountYieldsEmptyStateAndNoRows() {
        val rows = ModelRowMapper.build(
            offerings = emptyList(),
            availabilities = emptyList(),
            selected = selected(),
        )
        assertTrue(rows.isEmpty())
        assertTrue(rankResults("anything", rows).isEmpty())
        assertEquals(R.string.models_empty_no_data, searchEmptyText(false))
        assertEquals(R.string.models_empty_no_data_relax, searchEmptyText(true))
    }
}
