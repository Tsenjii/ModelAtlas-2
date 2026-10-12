package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P6 session X2：ModelDetail 邊界測試（純 Kotlin，無 Android／Room 依賴）。
 *
 * TRACEABILITY：MODEL-009（price／quota／history model detail）。
 * - fixture 只存在於本 test sourceSet（[detailEdgePrice]／[detailEdgePool]），不作產品資料。
 * - 缺資料一律 UNKNOWN／null／空集合，不捏造、不填零值、不拿 0／Free 冒充未知。
 */
class ModelDetailEdgeTest {

    private fun detailEdgePrice(
        observedAt: Long,
        input: Double? = 1.0,
        output: Double? = 2.0,
        state: PriceState = PriceState.PAID,
    ) = DetailPricePoint(
        observedAt = observedAt,
        inputPrice = input,
        outputPrice = output,
        state = state,
        sourceLabel = "official",
    )

    private fun detailEdgePool(
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

    // ---- 空歷史 ----

    @Test
    fun emptyHistoryBuildsEmptyWithNullDerived() {
        val built = ModelDetailStateBuilder.build("edge-empty")
        assertTrue(built.priceHistory.isEmpty())
        assertTrue(built.quotaPools.isEmpty())
        assertTrue(built.history.isEmpty())
        assertNull(built.priceChangeCount)
        assertNull(built.priceChangedAt)
        assertNull(built.freeSinceAt)
        assertEquals(PriceState.UNKNOWN, built.priceState)
        assertEquals(QuotaState.UNKNOWN, built.quotaState)
    }

    @Test
    fun singlePointHistoryYieldsNullChanges() {
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-single",
            priceHistory = listOf(detailEdgePrice(100L)),
        )
        assertEquals(1, built.priceHistory.size)
        assertNull(built.priceChangeCount)
        assertNull(built.priceChangedAt)
        // 單點價格歷史仍合成一筆 PRICE 事件。
        assertEquals(1, built.history.size)
        assertEquals("PRICE", built.history[0].section)
    }

    // ---- 全 UNKNOWN ----

    @Test
    fun allUnknownMultiPointYieldsNullChanges() {
        val points = listOf(
            DetailPricePoint(observedAt = 1L, state = PriceState.UNKNOWN, sourceLabel = ""),
            DetailPricePoint(observedAt = 2L, state = PriceState.UNKNOWN, sourceLabel = ""),
        )
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-all-unknown",
            priceHistory = points,
        )
        // 四元組＋state 全相等 → 變動 0 → null，不捏造。
        assertNull(built.priceChangeCount)
        assertNull(built.priceChangedAt)
        assertNull(built.freeSinceAt)
    }

    @Test
    fun allUnknownStaysUnknownNotZeroFree() {
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-unknown",
            priceState = PriceState.UNKNOWN,
            quotaState = QuotaState.UNKNOWN,
            performance = DetailPerformance(),
        )
        assertEquals(PriceState.UNKNOWN, built.priceState)
        assertEquals(QuotaState.UNKNOWN, built.quotaState)
        assertNull(built.performance.success1h)
        assertNull(built.performance.tps)
        assertNull(built.performance.latencyMs)
        assertNull(built.freeSinceAt)
    }

    // ---- 單邊 quota ----

    @Test
    fun singleSidedQuotaPoolPreservedWithHistoryEvent() {
        val pool = DetailQuotaPool(
            kind = DetailQuotaKind.TOKEN_QUOTA,
            providerId = "p1",
            state = QuotaState.QUOTA_AVAILABLE,
            remainingRaw = 500L,
            usedRaw = null,
            grantedRaw = null,
            unlimited = null,
            observedAt = 42L,
            sourceLabel = "observed",
        )
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-single-sided",
            quotaPools = listOf(pool),
        )
        assertEquals(1, built.quotaPools.size)
        assertEquals(500L, built.quotaPools[0].remainingRaw)
        assertNull(built.quotaPools[0].usedRaw)
        assertEquals(1, built.history.size)
        assertEquals("QUOTA:TOKEN_QUOTA", built.history[0].section)
        assertEquals(42L, built.history[0].observedAt)
    }

    @Test
    fun quotaPoolWithoutObservedAtSkippedInHistoryButKeptInPools() {
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-no-observed",
            quotaPools = listOf(detailEdgePool(observedAt = null)),
        )
        assertEquals(1, built.quotaPools.size)
        assertTrue(built.history.isEmpty())
    }

    // ---- 超長列表 ----

    @Test
    fun overlongPriceListStillBuildsSortedWithoutCrash() {
        val size = 2000
        // 逆序傳入，驗證 builder 內升序排序且不截斷、不崩潰。
        val points = (size downTo 1).map { detailEdgePrice(it.toLong(), input = it.toDouble()) }
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-long-prices",
            priceHistory = points,
        )
        assertEquals(size, built.priceHistory.size)
        assertEquals(1L, built.priceHistory.first().observedAt)
        assertEquals(size.toLong(), built.priceHistory.last().observedAt)
        // history 降序（新→舊），數量與價格點一致（無 pools／extra）。
        assertEquals(size, built.history.size)
        assertEquals(size.toLong(), built.history.first().observedAt)
        assertEquals(1L, built.history.last().observedAt)
    }

    @Test
    fun overlongQuotaAndExtraListsStillBuildWithoutCrash() {
        val pools = (1..1000).map {
            detailEdgePool(
                kind = DetailQuotaKind.ACCOUNT_QUOTA,
                observedAt = it.toLong(),
            )
        }
        val extra = (1..500).map {
            DetailHistoryEvent(
                observedAt = it.toLong(),
                section = "EXTRA",
                stateLabel = "UNKNOWN",
            )
        }
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-long-pools",
            quotaPools = pools,
            extraEvents = extra,
        )
        assertEquals(1000, built.quotaPools.size)
        // 每個 pool（observedAt 非空）各一事件＋500 extra，不靜默截斷。
        assertEquals(1500, built.history.size)
        assertEquals(1000L, built.history.first().observedAt)
    }

    // ---- freeSince／共識邊界 ----

    @Test
    fun freeSinceNullWhenFinalStateNotFree() {
        val points = listOf(
            detailEdgePrice(1L, state = PriceState.FREE_NOW),
            detailEdgePrice(2L, state = PriceState.FREE_NOW),
        )
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-free-final-paid",
            priceState = PriceState.PAID,
            priceHistory = points,
        )
        assertNull(built.freeSinceAt)
    }

    @Test
    fun freeSinceNullWhenHistoryEmptyFinalFree() {
        val built = ModelDetailStateBuilder.build(
            canonicalId = "edge-free-empty",
            priceState = PriceState.FREE_NOW,
            priceHistory = emptyList(),
        )
        assertNull(built.freeSinceAt)
    }

    @Test
    fun consensusEmptyAndDivergentFailClosedToUnknown() {
        assertEquals(PriceState.UNKNOWN, ModelDetailStateBuilder.consensusPriceState(emptyList()))
        assertEquals(PriceState.UNKNOWN, ModelDetailStateBuilder.consensusPriceState(listOf("FREE_NOW", "PAID")))
        assertEquals(PriceState.FREE_NOW, ModelDetailStateBuilder.consensusPriceState(listOf("free_now", " FREE_NOW ")))
        assertEquals(QuotaState.UNKNOWN, ModelDetailStateBuilder.consensusQuotaState(emptyList()))
        assertEquals(
            QuotaState.UNKNOWN,
            ModelDetailStateBuilder.consensusQuotaState(listOf("QUOTA_AVAILABLE", "QUOTA_EXHAUSTED")),
        )
    }

    @Test
    fun blankCanonicalIdThrows() {
        try {
            ModelDetailStateBuilder.build("   ")
            fail("expected IllegalArgumentException for blank canonicalId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("canonicalId"))
        }
    }
}
