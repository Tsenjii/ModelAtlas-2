package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 session X2：Search / Filter 邊界測試（純 Kotlin，無 Android 依賴）。
 *
 * TRACEABILITY：MODEL-004（exact/fuzzy/alias/normalized search）、MODEL-005（Filter Sheet 邏輯層）。
 * - fixture 只存在於本 test sourceSet（[edgeFixtureRow]），不作產品資料。
 * - Unknown 不等於 0 / Free；價格 null 在有界條件下視為不通過。
 */
class SearchFilterEdgeTest {

    private fun edgeFixtureRow(
        canonicalId: String,
        providerModelId: String = canonicalId,
        inputPrice: Double? = null,
        priceState: PriceState = PriceState.UNKNOWN,
    ) = ModelRow(
        providerId = "p1",
        providerModelId = providerModelId,
        canonicalId = canonicalId,
        priceState = priceState,
        inputPrice = inputPrice,
    )

    // ---- 空查詢 ----

    @Test
    fun emptyQueryEmptyStringReturnsAllWithNullTier() {
        val rows = listOf(edgeFixtureRow("b-model"), edgeFixtureRow("a-model"))
        val ranked = rankResults("", rows)
        assertEquals(2, ranked.size)
        assertTrue(ranked.all { it.tier == null })
        // 預設視圖 FREE NOW 優先，其餘依 canonicalId；此處皆 UNKNOWN 故依 id 排序。
        assertEquals("a-model", ranked[0].row.canonicalId)
        assertEquals("b-model", ranked[1].row.canonicalId)
    }

    @Test
    fun emptyQueryWhitespaceOnlyReturnsAllWithNullTier() {
        val rows = listOf(edgeFixtureRow("m1"))
        val ranked = rankResults("   \t  ", rows)
        assertEquals(1, ranked.size)
        assertEquals(null, ranked[0].tier)
    }

    @Test
    fun emptyQuerySeparatorsOnlyNormalizesToEmptyPassthrough() {
        assertEquals("", normalizeQuery(" _-. /:|()+[]{} "))
        val rows = listOf(edgeFixtureRow("m1"), edgeFixtureRow("m2"))
        val ranked = rankResults(" _-. /:|()+[]{} ", rows)
        assertEquals(2, ranked.size)
        assertTrue(ranked.all { it.tier == null })
    }

    @Test
    fun emptyRowsNeverCrashForRankAndFilter() {
        assertTrue(rankResults("gpt", emptyList()).isEmpty())
        assertTrue(rankResults("", emptyList()).isEmpty())
        assertTrue(filterModels(emptyList(), ModelFilter(), nowMs = 0L).isEmpty())
    }

    @Test
    fun maxResultsZeroReturnsEmpty() {
        val rows = listOf(edgeFixtureRow("m1"))
        assertTrue(rankResults("m1", rows, maxResults = 0).isEmpty())
        assertTrue(rankResults("", rows, maxResults = 0).isEmpty())
    }

    @Test
    fun maxResultsNegativeReturnsEmpty() {
        val rows = listOf(edgeFixtureRow("m1"))
        assertTrue(rankResults("m1", rows, maxResults = -5).isEmpty())
        assertTrue(rankResults("", rows, maxResults = -1).isEmpty())
    }

    // ---- 超長查詢 ----

    @Test
    fun overlongQueryDoesNotCrashAndRespectsLengthDiffGuard() {
        val rows = listOf(edgeFixtureRow("m1"), edgeFixtureRow("gpt-4o"))
        val overlong = "a".repeat(5000)
        val ranked = rankResults(overlong, rows)
        // 長度差遠大於 fuzzy 上限 2，應無匹配但不崩潰。
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun overlongQueryWithNoMatchReturnsEmptyWithoutTruncationSideEffect() {
        val rows = listOf(edgeFixtureRow("short"))
        val overlong = "x".repeat(3000) + "!" + "y".repeat(2000)
        val ranked = rankResults(overlong, rows, maxResults = 100)
        assertTrue(ranked.isEmpty())
    }

    // ---- 特殊字元 ----

    @Test
    fun specialCharSeparatorsAreEquivalentForExactMatch() {
        val rows = listOf(edgeFixtureRow("GPT-4o"))
        assertEquals(SearchTier.EXACT, rankResults("GPT-4o", rows)[0].tier)
        assertEquals(SearchTier.EXACT, rankResults("GPT/4o", rows)[0].tier)
        assertEquals(SearchTier.EXACT, rankResults("GPT:4o", rows)[0].tier)
        assertEquals(SearchTier.EXACT, rankResults("GPT_4o", rows)[0].tier)
        assertEquals(SearchTier.EXACT, rankResults("gpt 4o", rows)[0].tier)
    }

    @Test
    fun specialCharsOnlyQueryBehavesAsEmptyPassthrough() {
        val rows = listOf(edgeFixtureRow("m1"))
        val ranked = rankResults("!!!@@@###", rows)
        assertEquals(1, ranked.size)
        assertEquals(null, ranked[0].tier)
    }

    // ---- 全過濾器空 ----

    @Test
    fun emptyFilterReturnsAllRows() {
        val rows = listOf(
            edgeFixtureRow("m1", inputPrice = null, priceState = PriceState.UNKNOWN),
            edgeFixtureRow("m2", inputPrice = 0.0, priceState = PriceState.FREE_NOW),
            edgeFixtureRow("m3", inputPrice = 5.0, priceState = PriceState.PAID),
        )
        val out = filterModels(rows, ModelFilter(), nowMs = 0L)
        assertEquals(3, out.size)
    }

    // ---- 價格邊界：零 / 負值 / 未知 ----

    @Test
    fun priceZeroBoundaryExactZeroPasses() {
        val rows = listOf(
            edgeFixtureRow("zero", inputPrice = 0.0),
            edgeFixtureRow("tiny", inputPrice = 0.0001),
            edgeFixtureRow("neg", inputPrice = -1.0),
        )
        val out = filterModels(rows, ModelFilter(minInputPrice = 0.0, maxInputPrice = 0.0), nowMs = 0L)
        assertEquals(listOf("zero"), out.map { it.canonicalId })
    }

    @Test
    fun priceUnknownNullIsNotZero() {
        val rows = listOf(
            edgeFixtureRow("unknown", inputPrice = null),
            edgeFixtureRow("zero", inputPrice = 0.0),
        )
        // 有下界時未知不通過；無界時未知通過（不過濾）。
        assertEquals(listOf("zero"), filterModels(rows, ModelFilter(minInputPrice = 0.0), nowMs = 0L).map { it.canonicalId })
        assertEquals(listOf("zero"), filterModels(rows, ModelFilter(maxInputPrice = 0.0), nowMs = 0L).map { it.canonicalId })
        assertEquals(2, filterModels(rows, ModelFilter(), nowMs = 0L).size)
    }

    @Test
    fun negativePriceBoundsHandledWithoutCrash() {
        val rows = listOf(
            edgeFixtureRow("neg3", inputPrice = -3.0),
            edgeFixtureRow("zero", inputPrice = 0.0),
            edgeFixtureRow("unknown", inputPrice = null),
        )
        val out = filterModels(rows, ModelFilter(minInputPrice = -5.0, maxInputPrice = -1.0), nowMs = 0L)
        assertEquals(listOf("neg3"), out.map { it.canonicalId })
    }
}
