package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROV-001 repair 測試：Provider Detail 狀態純函式。
 *
 * Spec：`PRODUCT_SPEC.md` §4（Detail 四分頁；Models FREE NOW 優先、非免費 dim＋下沉）。
 *
 * - 四分頁固定 OVERVIEW／MODELS／PERFORMANCE／HISTORY（缺一不可）。
 * - FREE NOW 穩定置頂，其餘保持原順序下沉（不臆測次序、不丟列）。
 * - 預設狀態全 Unknown／空集合（不捏造、不填零）。
 */
class ProviderDetailStateTest {

    @Test
    fun detailTabsAreExactlyFourInFixedOrder() {
        assertEquals(
            listOf(
                ProviderDetailTab.OVERVIEW,
                ProviderDetailTab.MODELS,
                ProviderDetailTab.PERFORMANCE,
                ProviderDetailTab.HISTORY,
            ),
            detailTabOrder(),
        )
    }

    @Test
    fun freeNowModelsSortFirstStably() {
        val paid = ProviderDetailModel("paid-model", PriceState.PAID.name, verified = true)
        val unknown = ProviderDetailModel("unknown-model")
        val freeB = ProviderDetailModel("free-b", PriceState.FREE_NOW.name, verified = true)
        val freeA = ProviderDetailModel("free-a", PriceState.FREE_NOW.name)
        val manual = ProviderDetailModel("manual-model", manual = true)
        val input = listOf(paid, unknown, freeB, freeA, manual)

        val sorted = sortDetailModels(input)

        assertEquals(
            listOf("free-b", "free-a", "paid-model", "unknown-model", "manual-model"),
            sorted.map { it.modelId },
        )
    }

    @Test
    fun sortingNeverDropsOrDuplicatesRows() {
        val input = listOf(
            ProviderDetailModel("a", PriceState.PAID.name),
            ProviderDetailModel("b"),
            ProviderDetailModel("c", PriceState.FREE_NOW.name),
        )
        val sorted = sortDetailModels(input)
        assertEquals(3, sorted.size)
        assertEquals(input.map { it.modelId }.toSet(), sorted.map { it.modelId }.toSet())
        assertEquals("c", sorted.first().modelId)
    }

    @Test
    fun emptyModelsStayEmpty() {
        assertTrue(sortDetailModels(emptyList()).isEmpty())
    }

    @Test
    fun defaultStateIsUnknownAndEmpty() {
        val header = ProviderDetailHeader(providerId = "p", name = "n", baseUrl = "https://fixture.invalid/v1")
        assertEquals(HealthState.UNKNOWN.name, header.officialHealth)
        assertEquals(HealthState.UNKNOWN.name, header.observedHealth)
        assertEquals("UNKNOWN", header.priceState)
        assertEquals("UNKNOWN", header.quotaState)

        val state = ProviderDetailState(header = header)
        assertTrue(state.endpoints.isEmpty())
        assertTrue(state.accounts.isEmpty())
        assertTrue(state.models.isEmpty())
        assertTrue(state.history.isEmpty())
        assertEquals(null, state.performance)
        assertEquals(ProviderDetailTab.OVERVIEW, state.selectedTab)
        assertEquals(null, state.testingModelId)
    }
}
