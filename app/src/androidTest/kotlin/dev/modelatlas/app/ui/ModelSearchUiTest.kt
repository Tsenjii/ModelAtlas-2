package dev.modelatlas.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.ModelCategory
import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6 worker B：ModelSearch UI 測試（device 源集，可跑；需 device CI 執行）。
 *
 * TRACEABILITY：MODEL-004（徽章／空態／dim）、MODEL-005（FilterState 轉換／計數）。
 * - 本檔沿用 repo 既有 device-test 模式（AndroidJUnit4，不新增 compose-ui-test 依賴）：
 *   斷言 UI helper 契約（徽章文字／dim 規則／空態／FilterState→ModelFilter）。
 * - 完整 composition 斷言（实际渲染徽章／空態／dim slot）需 device CI＋由主 AI 補
 *   `androidx.compose.ui:ui-test-junit4` 依賴後再寫 createComposeRule 測試；此為未決事項。
 */
@RunWith(AndroidJUnit4::class)
class ModelSearchUiTest {

    private fun uiFixtureRow(
        canonicalId: String,
        priceState: PriceState = PriceState.UNKNOWN,
    ) = ModelRow(
        providerId = "p1",
        providerModelId = canonicalId,
        canonicalId = canonicalId,
        priceState = priceState,
    )

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun freeBadgeShowsFreeNow() {
        assertEquals("FREE NOW", targetContext.getString(priceBadgeText(PriceState.FREE_NOW)))
    }

    @Test
    fun unknownBadgeShowsUnknownWithoutFreeOrPriceText() {
        val badge = targetContext.getString(priceBadgeText(PriceState.UNKNOWN))
        assertEquals("未知", badge)
        assertFalse(badge.contains("免費"))
        assertFalse(badge.contains("FREE"))
        assertFalse(badge.contains("$"))
        assertFalse(badge.contains("0"))
    }

    @Test
    fun paidAndPartiallyFreeBadges() {
        assertEquals(R.string.price_paid, priceBadgeText(PriceState.PAID))
        assertEquals(R.string.price_partially_free, priceBadgeText(PriceState.PARTIALLY_FREE))
    }

    @Test
    fun dimAppliesToNonFreeOnly() {
        assertFalse(shouldDimRow(uiFixtureRow("a", PriceState.FREE_NOW)))
        assertTrue(shouldDimRow(uiFixtureRow("b", PriceState.PAID)))
        assertTrue(shouldDimRow(uiFixtureRow("c", PriceState.UNKNOWN)))
        assertTrue(shouldDimRow(uiFixtureRow("d", PriceState.PARTIALLY_FREE)))
        assertFalse(shouldDimRow(uiFixtureRow("b", PriceState.PAID), dimNonFree = false))
    }

    @Test
    fun sourceLabelPassesThroughAndBlankMeansUnknown() {
        assertEquals("manual", sourceDisplayLabel("manual", "未知"))
        assertEquals("official", sourceDisplayLabel("official", "未知"))
        assertEquals("observed", sourceDisplayLabel("observed", "未知"))
        assertEquals("external", sourceDisplayLabel("external", "未知"))
        assertEquals("未知", sourceDisplayLabel("", "未知"))
        assertEquals("未知", sourceDisplayLabel("   ", "未知"))
    }

    @Test
    fun unverifiedNeverShowsVerified() {
        assertEquals("VERIFIED", verificationLabel(true))
        assertNull(verificationLabel(false))
    }

    @Test
    fun displayNameFallsBackToCanonicalId() {
        assertEquals("pretty", modelDisplayName(uiFixtureRow("canon").copy(displayName = "pretty")))
        assertEquals("canon", modelDisplayName(uiFixtureRow("canon")))
        assertEquals("canon", modelDisplayName(uiFixtureRow("canon").copy(displayName = "  ")))
    }

    @Test
    fun emptyTextAlwaysStatesNoData() {
        assertTrue(targetContext.getString(searchEmptyText(false)).contains("尚無資料"))
        assertTrue(targetContext.getString(searchEmptyText(true)).contains("尚無資料"))
    }

    @Test
    fun activeFilterCountReflectsEnabledDims() {
        assertEquals(0, activeFilterCount(FilterState()))
        assertEquals(1, activeFilterCount(FilterState(freeNowOnly = true)))
        val full = FilterState(
            freeNowOnly = true,
            maxInputPrice = 1.0,
            minSuccess24h = 0.9,
            primaryCategories = setOf(ModelCategory.CODING),
            requireVision = true,
            addedWithinDays = 7,
            onlyFavorites = true,
            usableOnly = true,
        )
        assertEquals(8, activeFilterCount(full))
    }

    @Test
    fun filterStateConvertsToLogicFilterWithWindows() {
        val now = 1_700_000_000_000L
        val converted = FilterState(
            freeNowOnly = true,
            capabilities = setOf(dev.modelatlas.core.domain.Capability.VISION),
            addedWithinDays = 7,
            onlyRecent = true,
        ).toModelFilter(nowMs = now)
        assertEquals(true, converted.freeNowOnly)
        assertEquals(setOf(dev.modelatlas.core.domain.Capability.VISION), converted.capabilities)
        assertEquals(now - 7 * 24L * 3600L * 1000L, converted.addedAfter)
        assertEquals(true, converted.onlyRecent)
        // 空集合與 false 不產生過濾條件。
        val empty = FilterState().toModelFilter(nowMs = now)
        assertEquals(ModelFilter(), empty)
    }

    @Test
    fun detailEntryPreservesUnknownAndCompareBlockVisible() {
        // P6-DC 入口可見性：列點選進 Detail 後價格徽章仍 UNKNOWN（未知，不免費／不 $0），
        // 歷史下方 CompareLive 區塊以空態呈現（不偽造結果）。
        val row = uiFixtureRow("canon-dc", PriceState.UNKNOWN)
        val detail = ModelDetailStateBuilder.build(
            canonicalId = row.canonicalId,
            displayName = row.displayName,
            priceState = row.priceState,
            quotaState = row.quotaState,
        )
        assertEquals(R.string.value_unknown, detailPriceBadgeText(detail.priceState))
        assertEquals("canon-dc", modelDetailTitle(detail))
        assertEquals(R.string.compare_empty_no_results, compareEmptyText(true))
    }
}
