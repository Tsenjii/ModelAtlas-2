package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.ModelCategory
import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 repair worker B：FilterAvailability 真推導單測（JVM，pure Kotlin）。
 *
 * TRACEABILITY：MODEL-005（PRODUCT_SPEC §8 Filter Sheet 每一維度）。
 * 測試 fixture 只存在於本 test sourceSet，且以 `availabilityFixture*` 明示命名，不作產品資料。
 */
class FilterAvailabilityDerivationTest {

    private fun availabilityFixtureRow(
        canonicalId: String,
        providerId: String = "p1",
    ) = ModelRow(
        providerId = providerId,
        providerModelId = canonicalId,
        canonicalId = canonicalId,
        priceState = PriceState.UNKNOWN,
    )

    @Test
    fun emptyRowsYieldsAllUnavailable() {
        val availability = FilterAvailabilityWiring.derive(emptyList(), hasSelectedKey = true)
        assertFalse(availability.hasPriceData)
        assertFalse(availability.hasTelemetryData)
        assertFalse(availability.hasContextData)
        assertNull(availability.providers)
        assertNull(availability.groups)
        assertNull(availability.vendors)
        assertFalse(availability.hasClassificationData)
        assertFalse(availability.hasTimestampData)
        assertFalse(availability.hasPriceHistoryData)
        assertFalse(availability.hasFreeUptimeData)
        assertFalse(availability.hasCollectionData)
        assertFalse(availability.hasKeyAvailabilityData)
    }

    @Test
    fun priceDataRequiresNumericValues() {
        val withoutNumbers = listOf(availabilityFixtureRow("a"))
        assertFalse(FilterAvailabilityWiring.derive(withoutNumbers, true).hasPriceData)
        val withNumbers = listOf(availabilityFixtureRow("a").copy(inputPrice = 0.0))
        // 0.0 仍是真數值（Unknown≠0；有數值即有資料）。
        assertTrue(FilterAvailabilityWiring.derive(withNumbers, true).hasPriceData)
    }

    @Test
    fun providersGroupsVendorsDerivedFromRows() {
        val rows = listOf(
            availabilityFixtureRow("a", "p2").copy(group = "g1", vendor = "v1"),
            availabilityFixtureRow("b", "p1").copy(group = "", vendor = null),
        )
        val availability = FilterAvailabilityWiring.derive(rows, true)
        assertEquals(listOf("p1", "p2"), availability.providers)
        assertEquals(listOf("g1"), availability.groups)
        assertEquals(listOf("v1"), availability.vendors)
    }

    @Test
    fun classificationRequiresRealSignal() {
        val plain = listOf(availabilityFixtureRow("a"))
        assertFalse(FilterAvailabilityWiring.derive(plain, true).hasClassificationData)
        val classified = listOf(
            availabilityFixtureRow("a").copy(
                primaryCategory = ModelCategory.CODING,
                capabilities = setOf(Capability.CODING),
            ),
        )
        assertTrue(FilterAvailabilityWiring.derive(classified, true).hasClassificationData)
    }

    @Test
    fun keyAvailabilityFollowsSelectedKey() {
        val rows = listOf(availabilityFixtureRow("a").copy(selectedKeyUsable = true))
        assertTrue(FilterAvailabilityWiring.derive(rows, true).hasKeyAvailabilityData)
        assertFalse(FilterAvailabilityWiring.derive(rows, false).hasKeyAvailabilityData)
    }

    @Test
    fun collectionsStayDisabledWithoutPersistence() {
        val rows = listOf(availabilityFixtureRow("a"))
        assertFalse(FilterAvailabilityWiring.derive(rows, true).hasCollectionData)
    }

    @Test
    fun telemetryContextTimestampHistoryUptimeGatedByValues() {
        val full = listOf(
            availabilityFixtureRow("a").copy(
                tps = 10.0,
                contextTokens = 128000L,
                updatedAt = 123L,
                priceChangeCount = 2,
                freeSinceAt = 100L,
            ),
        )
        val availability = FilterAvailabilityWiring.derive(full, true)
        assertTrue(availability.hasTelemetryData)
        assertTrue(availability.hasContextData)
        assertTrue(availability.hasTimestampData)
        assertTrue(availability.hasPriceHistoryData)
        assertTrue(availability.hasFreeUptimeData)
    }
}
