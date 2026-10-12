package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.Modality
import dev.modelatlas.core.domain.ModelCategory
import dev.modelatlas.core.domain.PriceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 worker B：ModelSearchLogic JVM 單測（pure Kotlin，無 Android 依賴）。
 *
 * TRACEABILITY：MODEL-004（四層排序／fuzzy 上限）、MODEL-005（各 filter 維度）。
 * 測試 fixture 只存在於本 test sourceSet，且以 `*FixtureRow` 明示命名，不作產品資料。
 */
class ModelSearchLogicTest {

    private fun searchFixtureRow(
        canonicalId: String,
        providerModelId: String = canonicalId,
        aliases: List<String> = emptyList(),
        priceState: PriceState = PriceState.UNKNOWN,
    ) = ModelRow(
        providerId = "p1",
        providerModelId = providerModelId,
        canonicalId = canonicalId,
        aliasNames = aliases,
        priceState = priceState,
    )

    private fun filterFixtureRow(
        canonicalId: String,
        priceState: PriceState = PriceState.UNKNOWN,
    ) = ModelRow(
        providerId = "p1",
        providerModelId = canonicalId,
        canonicalId = canonicalId,
        priceState = priceState,
    )

    // ---- normalize ----

    @Test
    fun normalizeLowercasesUnifiesSeparatorsAndCollapsesWhitespace() {
        assertEquals("gpt 4o", normalizeQuery("GPT-4o"))
        assertEquals("llama 3 1", normalizeQuery("Llama_3.1"))
        assertEquals("deepseek v4", normalizeQuery("  DeepSeek   V4  "))
        assertEquals("qwen plus", normalizeQuery("Qwen/Plus"))
        assertEquals("", normalizeQuery("   "))
    }

    // ---- rank：四層優先序 ----

    @Test
    fun exactBeatsAliasBeatsNormalizedBeatsFuzzy() {
        val rows = listOf(
            searchFixtureRow("gpt-4p"), // FUZZY（1 字差）
            searchFixtureRow("gpt-4o-mini"), // NORMALIZED（子字串）
            searchFixtureRow("Unrelated Model", aliases = listOf("gpt-4o")), // ALIAS
            searchFixtureRow("GPT-4o"), // EXACT
        )
        val ranked = rankResults("gpt-4o", rows)
        assertEquals(4, ranked.size)
        assertEquals(SearchTier.EXACT, ranked[0].tier)
        assertEquals(SearchTier.ALIAS, ranked[1].tier)
        assertEquals(SearchTier.NORMALIZED, ranked[2].tier)
        assertEquals(SearchTier.FUZZY, ranked[3].tier)
    }

    @Test
    fun aliasTierMatchesNormalizedAlias() {
        val rows = listOf(searchFixtureRow("Some Other Model", aliases = listOf("GPT-4o")))
        val ranked = rankResults("gpt 4o", rows)
        assertEquals(1, ranked.size)
        assertEquals(SearchTier.ALIAS, ranked[0].tier)
    }

    @Test
    fun normalizedTierMatchesSubstring() {
        val rows = listOf(searchFixtureRow("gpt-4o-mini"))
        val ranked = rankResults("gpt-4o", rows)
        assertEquals(1, ranked.size)
        assertEquals(SearchTier.NORMALIZED, ranked[0].tier)
    }

    @Test
    fun fuzzyTierMatchesTypoWithinBound() {
        val rows = listOf(searchFixtureRow("deepseek-v4"))
        val ranked = rankResults("deepsesk v4", rows)
        assertEquals(1, ranked.size)
        assertEquals(SearchTier.FUZZY, ranked[0].tier)
    }

    @Test
    fun farStringIsExcluded() {
        val rows = listOf(searchFixtureRow("gpt-4o"))
        assertTrue(rankResults("completely different query xyz", rows).isEmpty())
    }

    @Test
    fun shortQueryDoesNotFuzzyMatchUnrelated() {
        val rows = listOf(searchFixtureRow("zzz qqq"))
        assertTrue(rankResults("ab", rows).isEmpty())
    }

    // ---- MAJOR4 (a)：刪除子序列捷徑，只留有界 Levenshtein ----

    @Test
    fun longGapSubsequenceIsRejected() {
        // 舊 isSubsequence 會命中（字元依序出現、總間隙 >= 3）；有界 Levenshtein 距離 > threshold 應拒絕。
        // query "gpt4o" 正規化後長度 5 → threshold 1；候選正規化為 "g p t 4 o x y z"，總間隙遠超上限。
        val rows = listOf(searchFixtureRow("g___p___t___4___o___x___y___z"))
        assertTrue(rankResults("gpt4o", rows).isEmpty())
        val longerGap = listOf(searchFixtureRow("g---p---t---4---o---x---y---z---w---v"))
        assertTrue(rankResults("gpt4o", longerGap).isEmpty())
    }

    @Test
    fun shortFuzzyTypoStillMatches() {
        // 單字元替代（距離 1 ≤ threshold 2），有界 Levenshtein 仍命中 FUZZY。
        val rows = listOf(searchFixtureRow("qwen-plus"))
        val ranked = rankResults("qwen pluz", rows)
        assertEquals(1, ranked.size)
        assertEquals(SearchTier.FUZZY, ranked[0].tier)
    }

    @Test
    fun longCandidateEarlyExitStaysEmptyAndFast() {
        // 長候選（5000 字元）靠長度差預檢＋列最小值早退，不命中且快速返回。
        val longId = "x".repeat(5000)
        val rows = listOf(searchFixtureRow(longId))
        val startNs = System.nanoTime()
        val ranked = rankResults("gpt 4o", rows)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue(ranked.isEmpty())
        assertTrue("fuzzy 長候選早退超時：${elapsedMs}ms", elapsedMs < 1000)
    }

    @Test
    fun exactCanMatchViaProviderModelId() {
        val rows = listOf(searchFixtureRow("X", providerModelId = "Qwen-Plus"))
        val ranked = rankResults("qwen plus", rows)
        assertEquals(1, ranked.size)
        assertEquals(SearchTier.EXACT, ranked[0].tier)
    }

    @Test
    fun sameTierFreeNowFirst() {
        val rows = listOf(
            searchFixtureRow("gpt-4o-mini-paid", priceState = PriceState.PAID),
            searchFixtureRow("gpt-4o-mini-free", priceState = PriceState.FREE_NOW),
        )
        val ranked = rankResults("gpt-4o", rows)
        assertEquals(2, ranked.size)
        assertEquals("gpt-4o-mini-free", ranked[0].row.canonicalId)
    }

    // ---- rank：上限與空集合 ----

    @Test
    fun maxResultsTruncates() {
        val rows = (1..150).map { searchFixtureRow("test model %03d".format(it)) }
        val ranked = rankResults("test model", rows, maxResults = 10)
        assertEquals(10, ranked.size)
    }

    @Test
    fun blankQueryReturnsAllCappedWithFreeFirstAndNullTier() {
        val rows = listOf(
            searchFixtureRow("b", priceState = PriceState.PAID),
            searchFixtureRow("a", priceState = PriceState.FREE_NOW),
            searchFixtureRow("c", priceState = PriceState.UNKNOWN),
        )
        val ranked = rankResults("   ", rows)
        assertEquals(3, ranked.size)
        assertTrue(ranked.all { it.tier == null })
        assertEquals(listOf("a", "b", "c"), ranked.map { it.row.canonicalId })
    }

    @Test
    fun emptyRowsAndNonPositiveMaxDoNotCrash() {
        assertTrue(rankResults("gpt", emptyList()).isEmpty())
        assertTrue(rankResults("", emptyList()).isEmpty())
        val rows = listOf(searchFixtureRow("gpt-4o"))
        assertTrue(rankResults("gpt", rows, maxResults = 0).isEmpty())
        assertTrue(rankResults("gpt", rows, maxResults = -1).isEmpty())
    }

    // ---- filter：FREE NOW / 價格 / 成功率 ----

    @Test
    fun freeNowOnlyKeepsOnlyFreeNow() {
        val rows = listOf(
            filterFixtureRow("free", PriceState.FREE_NOW),
            filterFixtureRow("paid", PriceState.PAID),
            filterFixtureRow("partial", PriceState.PARTIALLY_FREE),
            filterFixtureRow("unknown", PriceState.UNKNOWN),
        )
        val out = filterModels(rows, ModelFilter(freeNowOnly = true))
        assertEquals(listOf("free"), out.map { it.canonicalId })
    }

    @Test
    fun maxInputPriceExcludesOverBudgetAndUnknown() {
        val rows = listOf(
            filterFixtureRow("cheap").copy(inputPrice = 0.1),
            filterFixtureRow("expensive").copy(inputPrice = 5.0),
            filterFixtureRow("unknown-price"),
        )
        val out = filterModels(rows, ModelFilter(maxInputPrice = 1.0))
        assertEquals(listOf("cheap"), out.map { it.canonicalId })
    }

    @Test
    fun minInputPriceBoundExcludesUnknown() {
        val rows = listOf(
            filterFixtureRow("cheap").copy(inputPrice = 0.1),
            filterFixtureRow("expensive").copy(inputPrice = 5.0),
            filterFixtureRow("unknown-price"),
        )
        val out = filterModels(rows, ModelFilter(minInputPrice = 1.0))
        assertEquals(listOf("expensive"), out.map { it.canonicalId })
    }

    @Test
    fun successBoundsExcludeUnknownWhenSet() {
        val rows = listOf(
            filterFixtureRow("good").copy(success24h = 0.95),
            filterFixtureRow("bad").copy(success24h = 0.5),
            filterFixtureRow("unknown-success"),
        )
        val out = filterModels(rows, ModelFilter(minSuccess24h = 0.9))
        assertEquals(listOf("good"), out.map { it.canonicalId })
    }

    // ---- filter：效能 / context ----

    @Test
    fun tpsBoundsExcludeUnknownWhenSet() {
        val rows = listOf(
            filterFixtureRow("fast").copy(tps = 100.0),
            filterFixtureRow("slow").copy(tps = 10.0),
            filterFixtureRow("unknown-tps"),
        )
        assertEquals(listOf("fast"), filterModels(rows, ModelFilter(minTps = 50.0)).map { it.canonicalId })
        assertEquals(
            listOf("slow"),
            filterModels(rows, ModelFilter(maxTps = 50.0)).map { it.canonicalId },
        )
    }

    @Test
    fun ttftAndLatencyBoundsExcludeUnknownWhenSet() {
        val rows = listOf(
            filterFixtureRow("quick").copy(ttftMs = 1000L, latencyMs = 5000L),
            filterFixtureRow("sluggish").copy(ttftMs = 5000L, latencyMs = 30000L),
            filterFixtureRow("unknown-perf"),
        )
        assertEquals(
            listOf("quick"),
            filterModels(rows, ModelFilter(maxTtftMs = 2000L)).map { it.canonicalId },
        )
        assertEquals(
            listOf("quick"),
            filterModels(rows, ModelFilter(maxLatencyMs = 10000L)).map { it.canonicalId },
        )
    }

    @Test
    fun contextBoundsExcludeUnknownWhenSet() {
        val rows = listOf(
            filterFixtureRow("long").copy(contextTokens = 200_000L),
            filterFixtureRow("short").copy(contextTokens = 32_000L),
            filterFixtureRow("unknown-ctx"),
        )
        assertEquals(
            listOf("long"),
            filterModels(rows, ModelFilter(minContextTokens = 100_000L)).map { it.canonicalId },
        )
        assertEquals(
            listOf("short"),
            filterModels(rows, ModelFilter(maxContextTokens = 100_000L)).map { it.canonicalId },
        )
    }

    // ---- filter：分面集合 ----

    @Test
    fun groupProviderVendorSetsAndEmptySetMeansNoFilter() {
        val rows = listOf(
            filterFixtureRow("r1").copy(group = "g1", vendor = "v1"),
            filterFixtureRow("r2").copy(group = "g2", vendor = "v2"),
            filterFixtureRow("r3"),
        )
        assertEquals(listOf("r1"), filterModels(rows, ModelFilter(groups = setOf("g1"))).map { it.canonicalId })
        // providerId fixture 全為 p1，故集合命中時三列皆通過。
        assertEquals(3, filterModels(rows, ModelFilter(providerIds = setOf("p1"))).size)
        assertTrue(filterModels(rows, ModelFilter(providerIds = setOf("no-such-provider"))).isEmpty())
        assertEquals(listOf("r2"), filterModels(rows, ModelFilter(vendors = setOf("v2"))).map { it.canonicalId })
        assertEquals(3, filterModels(rows, ModelFilter(groups = emptySet())).size)
    }

    @Test
    fun primaryCategorySetFiltersNullOut() {
        val rows = listOf(
            filterFixtureRow("code").copy(primaryCategory = ModelCategory.CODING),
            filterFixtureRow("chat").copy(primaryCategory = ModelCategory.CHAT_REASONING),
            filterFixtureRow("uncategorized"),
        )
        val out = filterModels(rows, ModelFilter(primaryCategories = setOf(ModelCategory.CODING)))
        assertEquals(listOf("code"), out.map { it.canonicalId })
    }

    @Test
    fun capabilitiesAnyMatchesEither() {
        val rows = listOf(
            filterFixtureRow("coder").copy(capabilities = setOf(Capability.CODING)),
            filterFixtureRow("plain"),
        )
        val out = filterModels(
            rows,
            ModelFilter(capabilities = setOf(Capability.CODING, Capability.VISION)),
        )
        assertEquals(listOf("coder"), out.map { it.canonicalId })
    }

    @Test
    fun capabilitiesAllRequiresEveryListed() {
        val rows = listOf(
            filterFixtureRow("both").copy(capabilities = setOf(Capability.CODING, Capability.VISION)),
            filterFixtureRow("one").copy(capabilities = setOf(Capability.CODING)),
        )
        val out = filterModels(
            rows,
            ModelFilter(
                capabilities = setOf(Capability.CODING, Capability.VISION),
                capabilityMatchAll = true,
            ),
        )
        assertEquals(listOf("both"), out.map { it.canonicalId })
    }

    @Test
    fun modalitiesAnyMatch() {
        val rows = listOf(
            filterFixtureRow("visual").copy(modalities = setOf(Modality.IMAGE_INPUT)),
            filterFixtureRow("text").copy(modalities = setOf(Modality.TEXT_INPUT)),
        )
        val out = filterModels(rows, ModelFilter(modalities = setOf(Modality.IMAGE_INPUT)))
        assertEquals(listOf("visual"), out.map { it.canonicalId })
    }

    // ---- filter：tools / vision / files / reasoning ----

    @Test
    fun requireVisionPassesOnFlagOrCapability() {
        val rows = listOf(
            filterFixtureRow("flagged").copy(supportsVision = true),
            filterFixtureRow("classified").copy(capabilities = setOf(Capability.VISION)),
            filterFixtureRow("blind"),
        )
        val out = filterModels(rows, ModelFilter(requireVision = true))
        assertEquals(listOf("flagged", "classified"), out.map { it.canonicalId })
    }

    @Test
    fun requireToolsPassesOnFlagOrToolCapabilities() {
        val rows = listOf(
            filterFixtureRow("flagged").copy(hasTools = true),
            filterFixtureRow("classified").copy(capabilities = setOf(Capability.TOOL_CALLING)),
            filterFixtureRow("func").copy(capabilities = setOf(Capability.FUNCTION_CALLING)),
            filterFixtureRow("plain"),
        )
        val out = filterModels(rows, ModelFilter(requireTools = true))
        assertEquals(listOf("flagged", "classified", "func"), out.map { it.canonicalId })
    }

    @Test
    fun requireFilesPassesOnFlagOrCapability() {
        val rows = listOf(
            filterFixtureRow("flagged").copy(supportsFiles = true),
            filterFixtureRow("classified").copy(capabilities = setOf(Capability.FILES)),
            filterFixtureRow("plain"),
        )
        val out = filterModels(rows, ModelFilter(requireFiles = true))
        assertEquals(listOf("flagged", "classified"), out.map { it.canonicalId })
    }

    @Test
    fun requireReasoningPassesOnFlagOrCapability() {
        val rows = listOf(
            filterFixtureRow("flagged").copy(supportsReasoning = true),
            filterFixtureRow("classified").copy(capabilities = setOf(Capability.REASONING)),
            filterFixtureRow("plain"),
        )
        val out = filterModels(rows, ModelFilter(requireReasoning = true))
        assertEquals(listOf("flagged", "classified"), out.map { it.canonicalId })
    }

    // ---- filter：時間窗 / 波動 / uptime ----

    @Test
    fun addedAndUpdatedWindowsAreInclusiveAndRejectUnknown() {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val rows = listOf(
            filterFixtureRow("fresh").copy(addedAt = now - day, updatedAt = now - day),
            filterFixtureRow("edge").copy(addedAt = now - 7 * day, updatedAt = now - 7 * day),
            filterFixtureRow("stale").copy(addedAt = now - 30 * day, updatedAt = now - 30 * day),
            filterFixtureRow("unknown-time"),
        )
        val added = filterModels(
            rows,
            ModelFilter(addedAfter = now - 7 * day, addedBefore = now),
            nowMs = now,
        )
        assertEquals(listOf("fresh", "edge"), added.map { it.canonicalId })
        val updated = filterModels(rows, ModelFilter(updatedAfter = now - 7 * day), nowMs = now)
        assertEquals(listOf("fresh", "edge"), updated.map { it.canonicalId })
    }

    @Test
    fun priceChangedWindowAndMinCount() {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val rows = listOf(
            filterFixtureRow("volatile").copy(priceChangedAt = now - day, priceChangeCount = 5),
            filterFixtureRow("calm").copy(priceChangedAt = now - day, priceChangeCount = 1),
            filterFixtureRow("old").copy(priceChangedAt = now - 30 * day, priceChangeCount = 9),
            filterFixtureRow("unknown-history"),
        )
        val windowed = filterModels(
            rows,
            ModelFilter(priceChangedAfter = now - 7 * day),
            nowMs = now,
        )
        assertEquals(listOf("volatile", "calm"), windowed.map { it.canonicalId })
        val counted = filterModels(rows, ModelFilter(minPriceChanges = 3), nowMs = now)
        assertEquals(listOf("volatile", "old"), counted.map { it.canonicalId })
    }

    @Test
    fun minFreeUptimeHoursUsesFixedNow() {
        val now = 1_700_000_000_000L
        val hour = 3_600_000L
        val rows = listOf(
            filterFixtureRow("long-free").copy(freeSinceAt = now - 48 * hour),
            filterFixtureRow("brief-free").copy(freeSinceAt = now - 1 * hour),
            filterFixtureRow("unknown-free"),
        )
        val out = filterModels(rows, ModelFilter(minFreeUptimeHours = 24.0), nowMs = now)
        assertEquals(listOf("long-free"), out.map { it.canonicalId })
    }

    // ---- filter：個人清單 / key / 組合 ----

    @Test
    fun onlyFavoritesMatchesCanonicalIdSet() {
        val rows = listOf(filterFixtureRow("a"), filterFixtureRow("b"))
        val out = filterModels(
            rows,
            ModelFilter(onlyFavorites = true, favoriteIds = setOf("a")),
        )
        assertEquals(listOf("a"), out.map { it.canonicalId })
    }

    @Test
    fun requiredCollectionWithMissingSetPassesNothing() {
        val rows = listOf(filterFixtureRow("a"))
        assertTrue(filterModels(rows, ModelFilter(onlyFavorites = true)).isEmpty())
        assertTrue(filterModels(rows, ModelFilter(onlyRecent = true, recentIds = emptySet())).isEmpty())
        assertTrue(filterModels(rows, ModelFilter(onlyWatched = true)).isEmpty())
    }

    @Test
    fun onlyRecentAndOnlyWatched() {
        val rows = listOf(filterFixtureRow("a"), filterFixtureRow("b"), filterFixtureRow("c"))
        assertEquals(
            listOf("b"),
            filterModels(rows, ModelFilter(onlyRecent = true, recentIds = setOf("b"))).map { it.canonicalId },
        )
        assertEquals(
            listOf("c"),
            filterModels(rows, ModelFilter(onlyWatched = true, watchlistIds = setOf("c"))).map { it.canonicalId },
        )
    }

    @Test
    fun usableOnlyKeepsSelectedKeyUsable() {
        val rows = listOf(
            filterFixtureRow("usable").copy(selectedKeyUsable = true),
            filterFixtureRow("locked"),
        )
        val out = filterModels(rows, ModelFilter(usableOnly = true))
        assertEquals(listOf("usable"), out.map { it.canonicalId })
    }

    @Test
    fun combinedFiltersAreAnded() {
        val rows = listOf(
            filterFixtureRow("match").copy(
                priceState = PriceState.FREE_NOW,
                primaryCategory = ModelCategory.CODING,
                selectedKeyUsable = true,
            ),
            filterFixtureRow("wrong-cat").copy(
                priceState = PriceState.FREE_NOW,
                primaryCategory = ModelCategory.IMAGE,
                selectedKeyUsable = true,
            ),
            filterFixtureRow("locked").copy(
                priceState = PriceState.FREE_NOW,
                primaryCategory = ModelCategory.CODING,
                selectedKeyUsable = false,
            ),
        )
        val out = filterModels(
            rows,
            ModelFilter(
                freeNowOnly = true,
                primaryCategories = setOf(ModelCategory.CODING),
                usableOnly = true,
            ),
        )
        assertEquals(listOf("match"), out.map { it.canonicalId })
    }

    @Test
    fun emptyFilterPassesEverythingIncludingUnknowns() {
        val rows = listOf(
            filterFixtureRow("free", PriceState.FREE_NOW),
            filterFixtureRow("paid", PriceState.PAID),
            filterFixtureRow("unknown", PriceState.UNKNOWN),
        )
        assertEquals(3, filterModels(rows, ModelFilter()).size)
    }
}
