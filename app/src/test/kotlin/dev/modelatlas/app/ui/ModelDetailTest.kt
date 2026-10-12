package dev.modelatlas.app.ui

import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 session L2：MODEL-009 model detail JVM 單測（pure Kotlin，無 Android 依賴）。
 *
 * TRACEABILITY：MODEL-009（price 歷史＋quota pools＋performance 摘要＋history 事件合成；
 * 價格區／配額區／歷史區；FREE 徽章只吃 priceState；dim 非免費）。
 * 對應 PRODUCT_SPEC §4／§10／§11。
 *
 * 測試 fixture 只存在於本 test sourceSet，且以 `detailFixture*` 明示命名，不作產品資料。
 */
class ModelDetailTest {

    private fun detailFixturePrice(
        observedAt: Long,
        input: Double? = 1.0,
        state: PriceState = PriceState.PAID,
    ) = DetailPricePoint(
        observedAt = observedAt,
        inputPrice = input,
        outputPrice = 2.0,
        state = state,
        sourceLabel = "official",
    )

    private fun detailFixturePool(
        kind: DetailQuotaKind = DetailQuotaKind.ACCOUNT_QUOTA,
        state: QuotaState = QuotaState.QUOTA_AVAILABLE,
        observedAt: Long? = 10L,
    ) = DetailQuotaPool(
        kind = kind,
        providerId = "p1",
        state = state,
        observedAt = observedAt,
        sourceLabel = "official",
    )

    @Test
    fun freeBadgeOnlyEatsPriceState() {
        assertEquals(R.string.price_free_now, detailPriceBadgeText(PriceState.FREE_NOW))
        assertEquals(R.string.price_paid, detailPriceBadgeText(PriceState.PAID))
        assertEquals(R.string.value_unknown, detailPriceBadgeText(PriceState.UNKNOWN))
    }

    @Test
    fun quotaBadgeUnknownIsNotAvailable() {
        assertEquals(R.string.value_unknown, detailQuotaBadgeText(QuotaState.UNKNOWN))
        assertEquals(R.string.quota_available, detailQuotaBadgeText(QuotaState.QUOTA_AVAILABLE))
    }

    @Test
    fun dimAppliesToNonFreeOnly() {
        val free = ModelDetailStateBuilder.build("m1", priceState = PriceState.FREE_NOW)
        val paid = ModelDetailStateBuilder.build("m1", priceState = PriceState.PAID)
        val unknown = ModelDetailStateBuilder.build("m1", priceState = PriceState.UNKNOWN)
        assertEquals(false, shouldDimDetail(free))
        assertEquals(true, shouldDimDetail(paid))
        assertEquals(true, shouldDimDetail(unknown))
    }

    @Test
    fun missingDataStaysUnknownNotZero() {
        assertEquals("輸入：未知", detailPriceLineText("輸入", null, "%1\$s：%2\$s", "未知"))
        assertEquals("剩餘：未知", detailQuotaLongText("剩餘", null, "%1\$s：%2\$s", "未知"))
        assertEquals("成功率：未知", detailPerfDoubleText("成功率", null, "%1\$s：%2\$s", "未知"))
        val built = ModelDetailStateBuilder.build("m1")
        assertEquals(PriceState.UNKNOWN, built.priceState)
        assertEquals(QuotaState.UNKNOWN, built.quotaState)
        assertNull(built.priceChangeCount)
        assertNull(built.priceChangedAt)
        assertNull(built.freeSinceAt)
        assertTrue(built.priceHistory.isEmpty())
        assertTrue(built.history.isEmpty())
    }

    @Test
    fun priceHistorySortedOldToNewAndHistoryNewToOld() {
        val built = ModelDetailStateBuilder.build(
            canonicalId = "m1",
            priceHistory = listOf(
                detailFixturePrice(30L),
                detailFixturePrice(10L),
                detailFixturePrice(20L),
            ),
        )
        assertEquals(listOf(10L, 20L, 30L), built.priceHistory.map { it.observedAt })
        assertEquals(listOf(30L, 20L, 10L), built.history.map { it.observedAt })
    }

    @Test
    fun priceChangeCountCountsTupleChange() {
        val built = ModelDetailStateBuilder.build(
            canonicalId = "m1",
            priceHistory = listOf(
                detailFixturePrice(10L, input = 1.0),
                detailFixturePrice(20L, input = 1.0),
                detailFixturePrice(30L, input = 5.0),
            ),
        )
        assertEquals(1, built.priceChangeCount)
        assertEquals(30L, built.priceChangedAt)
    }

    @Test
    fun freeSinceOnlyWhenTrailingFreeSegment() {
        val trailing = ModelDetailStateBuilder.build(
            canonicalId = "m1",
            priceState = PriceState.FREE_NOW,
            priceHistory = listOf(
                detailFixturePrice(10L, state = PriceState.PAID),
                detailFixturePrice(20L, state = PriceState.FREE_NOW),
                detailFixturePrice(30L, state = PriceState.FREE_NOW),
            ),
        )
        assertEquals(20L, trailing.freeSinceAt)
        val notFree = ModelDetailStateBuilder.build(
            canonicalId = "m1",
            priceState = PriceState.PAID,
            priceHistory = listOf(detailFixturePrice(10L, state = PriceState.FREE_NOW)),
        )
        assertNull(notFree.freeSinceAt)
    }

    @Test
    fun quotaPoolsKeptSeparateAndSorted() {
        val built = ModelDetailStateBuilder.build(
            canonicalId = "m1",
            quotaPools = listOf(
                detailFixturePool(DetailQuotaKind.TOKEN_QUOTA, observedAt = 5L),
                detailFixturePool(DetailQuotaKind.ACCOUNT_QUOTA, observedAt = 7L),
            ),
        )
        assertEquals(2, built.quotaPools.size)
        assertEquals(DetailQuotaKind.ACCOUNT_QUOTA, built.quotaPools[0].kind)
        assertEquals(DetailQuotaKind.TOKEN_QUOTA, built.quotaPools[1].kind)
    }

    @Test
    fun consensusDivergenceFallsBackToUnknown() {
        assertEquals(
            PriceState.UNKNOWN,
            ModelDetailStateBuilder.consensusPriceState(listOf("FREE_NOW", "PAID")),
        )
        assertEquals(
            PriceState.FREE_NOW,
            ModelDetailStateBuilder.consensusPriceState(listOf("FREE_NOW", " free_now ")),
        )
        assertEquals(
            QuotaState.UNKNOWN,
            ModelDetailStateBuilder.consensusQuotaState(emptyList()),
        )
        assertEquals(
            QuotaState.QUOTA_AVAILABLE,
            ModelDetailStateBuilder.consensusQuotaState(listOf("QUOTA_AVAILABLE")),
        )
    }

    @Test
    fun titleFallsBackToCanonicalId() {
        val blank = ModelDetailStateBuilder.build("canon-1", displayName = "  ")
        assertEquals("canon-1", modelDetailTitle(blank))
        val named = ModelDetailStateBuilder.build("canon-1", displayName = "My Model")
        assertEquals("My Model", modelDetailTitle(named))
    }

    @Test
    fun sourceLabelBlankIsUnknown() {
        assertEquals("未知", detailSourceLabel("", "未知"))
        assertEquals("official", detailSourceLabel("official", "未知"))
    }

    @Test
    fun historyMergesPriceQuotaAndExtra() {
        val built = ModelDetailStateBuilder.build(
            canonicalId = "m1",
            priceHistory = listOf(detailFixturePrice(10L)),
            quotaPools = listOf(detailFixturePool(observedAt = 20L)),
            extraEvents = listOf(DetailHistoryEvent(15L, "PERF", "UNKNOWN", "", "observed")),
        )
        assertEquals(listOf(20L, 15L, 10L), built.history.map { it.observedAt })
        assertTrue(built.history.any { it.section == "PERF" })
    }
}
