package dev.modelatlas.app

import dev.modelatlas.app.data.AvailabilityInput
import dev.modelatlas.app.data.MANUAL_OFFERING_CONFIDENCE
import dev.modelatlas.app.data.MANUAL_OFFERING_SOURCE_MANUAL
import dev.modelatlas.app.data.ManualOfferingStore
import dev.modelatlas.app.data.OfferingInput
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.app.data.UsableProjection
import dev.modelatlas.app.ui.AliasRef
import dev.modelatlas.app.ui.AvailabilityRef
import dev.modelatlas.app.ui.CanonicalRef
import dev.modelatlas.app.ui.ModelFilter
import dev.modelatlas.app.ui.ModelRow
import dev.modelatlas.app.ui.ModelRowMapper
import dev.modelatlas.app.ui.OfferingRef
import dev.modelatlas.app.ui.SearchTier
import dev.modelatlas.app.ui.build
import dev.modelatlas.app.ui.filterModels
import dev.modelatlas.app.ui.rankResults
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.ProviderModelAlias
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.domain.canonicalIdFor
import dev.modelatlas.core.domain.isManualMappingSource
import dev.modelatlas.core.domain.isOfficialMappingSource
import dev.modelatlas.core.domain.normalizeModelId
import dev.modelatlas.core.models.deduplicateCanonical
import dev.modelatlas.core.models.isSameCanonical
import dev.modelatlas.core.models.resolveCanonicalId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 session AD：canonical → alias → search → usable 全鏈跨層 JVM 單測。
 *
 * TRACEABILITY：MODEL-001（CanonicalModel）、MODEL-002（ProviderModelAlias）、
 * MODEL-004（exact/fuzzy/alias/normalized search）、MODEL-005（Filter 維度）、
 * MODEL-006（manual hidden offering）、MODEL-007（selected-key usable projection）。
 * 對應 PRODUCT_SPEC §6（同一 canonical 不因不同 key 重複建立；原始 ID 永遠保存）、
 * §7（正規化與映射來源分欄）、§8（Global Search 四層＋Filter 維度＋hidden offering 需實測 evidence）、
 * §10（manual/official/observed/external 不互冒；Unknown≠0/Free）。
 *
 * 走的是真實接線縫 `ModelRowMapper.build`（offerings ＋ availability ＋ canonical ＋ alias
 * ＋ classification → `ModelRow`），下游再交給 `rankResults`／`filterModels`，
 * 上游 canonical 與 alias 語意取自 `core/models/ModelIdentity` 的純函式。
 * 本檔不碰 Room、不碰網路、不含 secret（只有 credentialId 字串）。
 *
 * 測試 fixture 只存在於本 test sourceSet，以 `chain*` 明示命名，不作產品資料。
 * 本檔僅新建，未修改任何既有檔。
 */
class P6CrossLayerTest {

    // ---- fixture 輔助（形狀對齊 production 接線層，非產品資料） ----

    private fun chainOffering(
        id: String = "off-1",
        providerId: String = "prov-a",
        endpointId: String = "ep-chat",
        providerModelId: String = "gpt-4o",
        operation: String = "CHAT_COMPLETIONS",
        manual: Boolean = true,
        source: String = MANUAL_OFFERING_SOURCE_MANUAL,
        hidden: Boolean = false,
        verified: Boolean = false,
    ) = OfferingRef(
        id = id,
        providerId = providerId,
        endpointId = endpointId,
        providerModelId = providerModelId,
        operation = operation,
        manual = manual,
        source = source,
        hidden = hidden,
        verified = verified,
    )

    private fun chainAvailability(
        id: String = "av-1",
        offeringId: String = "off-1",
        accountId: String = "acct-1",
        credentialId: String = "cred-a",
        group: String? = null,
        endpointId: String? = "ep-chat",
        operation: String? = "CHAT_COMPLETIONS",
        priceState: String = "FREE_NOW",
        quotaState: String = "QUOTA_AVAILABLE",
        source: String = MANUAL_OFFERING_SOURCE_MANUAL,
    ) = AvailabilityRef(
        id = id,
        offeringId = offeringId,
        accountId = accountId,
        credentialId = credentialId,
        group = group,
        endpointId = endpointId,
        operation = operation,
        priceState = priceState,
        quotaState = quotaState,
        source = source,
    )

    /** 與 `ModelRowMapper.build` 內部相同的 Ref → Input 映射（避免在此複製投影邏輯）。 */
    private fun OfferingRef.toInput(): OfferingInput = OfferingInput(
        id = id,
        providerId = providerId,
        endpointId = endpointId,
        providerModelId = providerModelId,
        manual = manual,
        source = source,
        operation = operation,
    )

    private fun AvailabilityRef.toInput(): AvailabilityInput = AvailabilityInput(
        id = id,
        offeringId = offeringId,
        accountId = accountId,
        credentialId = credentialId,
        group = group,
        endpointId = endpointId,
        operation = operation,
        priceState = priceState,
        quotaState = quotaState,
        source = source,
    )

    private fun chainRow(
        canonicalId: String,
        providerModelId: String = canonicalId,
        aliasNames: List<String> = emptyList(),
        priceState: PriceState = PriceState.UNKNOWN,
    ) = ModelRow(
        providerId = "prov-a",
        providerModelId = providerModelId,
        canonicalId = canonicalId,
        aliasNames = aliasNames,
        priceState = priceState,
    )

    // ---- MODEL-001：canonical 跨 Provider／key 形狀不重複建立 ----

    @Test
    fun canonicalCollapsesProviderKeyShapesIntoOneIdentity() {
        val first = deduplicateCanonical(emptyList(), "GPT_4o", "GPT-4o")
        val second = deduplicateCanonical(listOf(first), "gpt.4o", "gpt.4o")
        assertEquals("gpt-4o", first.canonicalId)
        assertEquals("gpt-4o", first.normalizedName)
        assertEquals(first.canonicalId, second.canonicalId)
        // 去重命中既有列時不覆寫既有顯示名（外部別名不得洗掉官方顯示名）。
        assertEquals("GPT-4o", second.displayName)
        assertTrue(isSameCanonical("GPT_4o", "gpt.4o"))
        assertTrue(isSameCanonical("gpt-4o", "gpt-4o:latest"))

        // 接線層：兩家 provider 的不同 key 形狀落到同一 canonical，原始 ID 仍各自保留。
        val rows = ModelRowMapper.build(
            offerings = listOf(
                chainOffering(id = "off-a", providerId = "prov-a", providerModelId = "GPT_4o"),
                chainOffering(id = "off-b", providerId = "prov-b", providerModelId = "gpt.4o"),
            ),
            availabilities = emptyList(),
            selected = SelectedKey("acct-1", "cred-a", null),
            canonicals = listOf(CanonicalRef("gpt-4o", "GPT-4o")),
        )
        assertEquals(2, rows.size)
        assertEquals(setOf("gpt-4o"), rows.map { it.canonicalId }.toSet())
        assertEquals(setOf("GPT_4o", "gpt.4o"), rows.map { it.providerModelId }.toSet())
        assertTrue(rows.all { it.displayName == "GPT-4o" })
    }

    @Test
    fun aliasWinsOverNormalizedFallbackAndRawIdSurvivesToSearchRow() {
        val rawId = "gpt-4o-turbo-preview"
        val withAlias = ModelRowMapper.build(
            offerings = listOf(chainOffering(id = "off-b", providerId = "prov-b", providerModelId = rawId)),
            availabilities = emptyList(),
            selected = SelectedKey("acct-1", "cred-a", null),
            canonicals = listOf(CanonicalRef("gpt-4o", "GPT-4o")),
            aliases = listOf(AliasRef("prov-b", rawId, "gpt-4o")),
        ).single()
        assertEquals("gpt-4o", withAlias.canonicalId)
        assertEquals(rawId, withAlias.providerModelId)
        assertEquals("GPT-4o", withAlias.displayName)

        // 沒有 alias 時回退正規化名（＝正規化後的原始 ID），不臆測其他 canonical。
        val withoutAlias = ModelRowMapper.build(
            offerings = listOf(chainOffering(id = "off-b", providerId = "prov-b", providerModelId = rawId)),
            availabilities = emptyList(),
            selected = SelectedKey("acct-1", "cred-a", null),
        ).single()
        assertEquals(canonicalIdFor(rawId), withoutAlias.canonicalId)
        assertEquals(rawId, withoutAlias.canonicalId)
    }

    // ---- MODEL-002：alias 解析優先序 ----

    @Test
    fun exactAliasBeatsNormalizedAliasBeatsCanonicalFallback() {
        val exactIndex = mapOf("GPT-4o" to "canonical:openai-official")
        val normalizedIndex = mapOf(
            normalizeModelId("gpt_4o") to "canonical:openai-metadata",
        )
        assertEquals(
            "canonical:openai-official",
            resolveCanonicalId("GPT-4o", exactIndex, normalizedIndex),
        )
        assertEquals(
            "canonical:openai-metadata",
            resolveCanonicalId("gpt_4o", exactIndex, normalizedIndex),
        )
        // 兩個索引都沒有 → 回退正規化 canonical，不捏造不存在的映射。
        assertEquals("claude-3-5-sonnet", resolveCanonicalId("Claude_3.5-Sonnet", exactIndex, normalizedIndex))
        assertEquals("claude-3-5-sonnet", resolveCanonicalId("claude-3-5-sonnet", emptyMap(), emptyMap()))
    }

    @Test
    fun aliasOfOtherProviderDrivesSearchTierWithoutSplittingCanonical() {
        val rows = ModelRowMapper.build(
            offerings = listOf(
                chainOffering(id = "off-a", providerId = "prov-a", providerModelId = "gpt-4o"),
                chainOffering(id = "off-b", providerId = "prov-b", providerModelId = "openai/gpt-4o"),
            ),
            availabilities = emptyList(),
            selected = SelectedKey("acct-1", "cred-a", null),
            canonicals = listOf(CanonicalRef("gpt-4o", "GPT-4o")),
            aliases = listOf(
                AliasRef("prov-a", "gpt-4o", "gpt-4o"),
                AliasRef("prov-b", "openai/gpt-4o", "gpt-4o"),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(setOf("gpt-4o"), rows.map { it.canonicalId }.toSet())
        // 別名只列同 canonical 的「其他」原始 ID，不把自己的 ID 當別名。
        assertEquals(listOf("openai/gpt-4o"), rows[0].aliasNames)
        assertEquals(listOf("gpt-4o"), rows[1].aliasNames)

        val ranked = rankResults("openai/gpt-4o", rows)
        assertEquals(listOf(SearchTier.EXACT, SearchTier.ALIAS), ranked.map { it.tier })
        assertEquals(listOf("openai/gpt-4o", "gpt-4o"), ranked.map { it.row.providerModelId })
        assertTrue(ranked.all { it.row.canonicalId == "gpt-4o" })

        // 同一模型同一 canonical：收藏／最近／監看以 canonicalId 為鍵時不會分裂成兩個身份。
        val favorites = ModelFilter(onlyFavorites = true, favoriteIds = setOf("gpt-4o"))
        assertEquals(2, filterModels(rows, favorites).size)
    }

    // ---- MODEL-004：搜尋四層與有界 fuzzy ----

    @Test
    fun searchRanksExactAliasNormalizedBeforeFuzzy() {
        val rows = listOf(
            chainRow("gpt-4"), // FUZZY（與查詢差 1 字，且非子字串）
            chainRow("gpt-4o-mini"), // NORMALIZED（子字串）
            chainRow("claude-3-5", providerModelId = "anthropic/claude-3-5", aliasNames = listOf("gpt-4o")), // ALIAS
            chainRow("gpt-4o"), // EXACT
        )
        val ranked = rankResults("gpt-4o", rows)
        assertEquals(
            listOf(SearchTier.EXACT, SearchTier.ALIAS, SearchTier.NORMALIZED, SearchTier.FUZZY),
            ranked.map { it.tier },
        )
        assertEquals(listOf("gpt-4o", "claude-3-5", "gpt-4o-mini", "gpt-4"), ranked.map { it.row.canonicalId })
        assertEquals(listOf(0, 0, 0, 1), ranked.map { it.distance })
    }

    @Test
    fun fuzzyStaysBoundedAndShortQueriesNeverGuess() {
        val rows = listOf(
            chainRow("claude-sonnet-four-five"),
            chainRow("zzbc"),
        )
        val typo = rankResults("claude sonnet four fXve", rows)
        assertEquals(1, typo.size)
        assertEquals(SearchTier.FUZZY, typo.single().tier)
        assertEquals(1, typo.single().distance)

        // 距離遠超界線 → 不回傳（不得為湊結果放寬門檻）。
        assertTrue(rankResults("claude sonnet four five plus a long unrelated tail phrase here", rows).isEmpty())
        // 短查詢門檻為 0：只准前綴，不准 fuzzy 猜。
        assertTrue(rankResults("abc", rows).isEmpty())
        // 空集合／空結果不崩潰。
        assertTrue(rankResults("gpt-4o", emptyList()).isEmpty())
    }

    @Test
    fun blankQueryKeepsEveryRowWithFreeNowFirstAndUnknownNeverBecomesFree() {
        val rows = listOf(
            chainRow("a-totally-free-model", priceState = PriceState.UNKNOWN),
            chainRow("z-paid-model", priceState = PriceState.FREE_NOW),
        )
        val ranked = rankResults("   ", rows)
        assertEquals(2, ranked.size)
        assertTrue(ranked.all { it.tier == null })
        // 免費徽章只吃 priceState：名稱含 free 不構成免費。
        assertEquals("z-paid-model", ranked[0].row.canonicalId)
        assertEquals(listOf("z-paid-model"), filterModels(rows, ModelFilter(freeNowOnly = true)).map { it.canonicalId })
        // 空查詢＋上限仍回全部列（不因查詢為空而丟資料）。
        assertEquals(2, rankResults("", rows, maxResults = 2).size)
    }

    // ---- MODEL-005：Filter 維度與 Unknown 語意 ----

    @Test
    fun unknownPriceAndTelemetryBandsExcludeRowInsteadOfTreatingItAsZero() {
        val unknown = chainRow("m-unknown")
        val known = chainRow("m-known", priceState = PriceState.PAID).copy(inputPrice = 0.5)

        // 有下界而值未知 → 無法證明符合，不通過（Unknown≠0）。
        assertTrue(filterModels(listOf(unknown), ModelFilter(minInputPrice = 0.0)).isEmpty())
        assertTrue(filterModels(listOf(unknown), ModelFilter(maxInputPrice = 10.0)).isEmpty())
        assertTrue(filterModels(listOf(unknown), ModelFilter(minSuccess1h = 0.9)).isEmpty())
        assertTrue(filterModels(listOf(unknown), ModelFilter(minTps = 1.0)).isEmpty())

        assertEquals(listOf("m-known"), filterModels(listOf(known), ModelFilter(minInputPrice = 0.0)).map { it.canonicalId })
        assertTrue(filterModels(listOf(known), ModelFilter(maxInputPrice = 0.1)).isEmpty())
        // 無任何條件 → 未知值照樣顯示（不明示猜測）。
        assertEquals(2, filterModels(listOf(unknown, known), ModelFilter()).size)
    }

    @Test
    fun selectedKeyUsableAndGroupSwitchesNeverPolluteEachOther() {
        val offering = chainOffering()
        val availabilities = listOf(
            chainAvailability(id = "av-c1", credentialId = "cred-a", priceState = "FREE_NOW", quotaState = "QUOTA_AVAILABLE"),
            chainAvailability(id = "av-c2", credentialId = "cred-b", priceState = "PAID", quotaState = "QUOTA_EXHAUSTED"),
            chainAvailability(id = "av-g1", credentialId = "cred-a", group = "vip", priceState = "PARTIALLY_FREE", quotaState = "QUOTA_LOW"),
        )
        val forCredA = ModelRowMapper.build(listOf(offering), availabilities, SelectedKey("acct-1", "cred-a", null)).single()
        val forCredB = ModelRowMapper.build(listOf(offering), availabilities, SelectedKey("acct-1", "cred-b", null)).single()
        val forVip = ModelRowMapper.build(listOf(offering), availabilities, SelectedKey("acct-1", "cred-a", "vip")).single()
        val forOther = ModelRowMapper.build(listOf(offering), availabilities, SelectedKey("acct-1", "cred-z", null)).single()

        assertEquals(PriceState.FREE_NOW, forCredA.priceState)
        assertEquals(QuotaState.QUOTA_AVAILABLE, forCredA.quotaState)
        assertEquals(PriceState.PAID, forCredB.priceState)
        assertEquals(QuotaState.QUOTA_EXHAUSTED, forCredB.quotaState)
        assertEquals(PriceState.PARTIALLY_FREE, forVip.priceState)
        assertEquals(QuotaState.QUOTA_LOW, forVip.quotaState)
        // 未選中的 key → 該列仍存在但狀態 UNKNOWN、不可用（Unknown≠0/Free、不隱藏）。
        assertEquals(PriceState.UNKNOWN, forOther.priceState)
        assertEquals(QuotaState.UNKNOWN, forOther.quotaState)
        // 三種 (account, credential, group) 複合鍵互不併組。
        val grouped = UsableProjection.groupBySelectedKey(availabilities.map { it.toInput() })
        assertEquals(3, grouped.size)
        assertTrue(grouped.values.all { it.size == 1 })

        assertTrue(forCredA.selectedKeyUsable)
        assertTrue(forCredB.selectedKeyUsable)
        assertTrue(forVip.selectedKeyUsable)
        assertFalse(forOther.selectedKeyUsable)

        assertEquals(1, filterModels(listOf(forCredA), ModelFilter(usableOnly = true)).size)
        assertEquals(1, filterModels(listOf(forCredA), ModelFilter(freeNowOnly = true)).size)
        assertTrue(filterModels(listOf(forCredB), ModelFilter(freeNowOnly = true)).isEmpty())
        assertTrue(filterModels(listOf(forOther), ModelFilter(usableOnly = true)).isEmpty())
    }

    // ---- MODEL-006：hidden manual offering 的可見性鏈 ----

    @Test
    fun hiddenManualOfferingStaysInvisibleAndUnusableUntilVerified() {
        val draft = ManualOfferingStore.createHiddenOffering(
            providerId = "prov-a",
            endpointId = "ep-chat",
            providerModelId = "hidden-free-model",
            source = MANUAL_OFFERING_SOURCE_MANUAL,
            evidenceId = "ev-catalog-probe",
            observedAt = 1_000L,
        )
        assertTrue(draft.hidden)
        assertFalse(draft.removed)
        assertFalse(ManualOfferingStore.isVisible(draft))
        assertFalse(ManualOfferingStore.isUsable(draft))

        val draftRef = chainOffering(
            id = draft.id,
            providerId = draft.providerId,
            endpointId = draft.endpointId,
            providerModelId = draft.providerModelId,
            manual = draft.manual,
            source = draft.source,
            hidden = draft.hidden,
        )
        // 未驗證 hidden：搜尋與 usable 投影都看不到（但資料未被刪除，draft 仍在手上）。
        assertTrue(
            ModelRowMapper.build(
                listOf(draftRef),
                listOf(chainAvailability(offeringId = draft.id)),
                SelectedKey("acct-1", "cred-a", null),
            ).isEmpty(),
        )

        // 名稱含 free 或空白 evidence 都不得 promote（§8：需實測成功 evidence）。
        assertFalse(ManualOfferingStore.canPromote(draft, "   "))
        assertThrows(IllegalArgumentException::class.java) {
            ManualOfferingStore.promoteOnVerified(draft, "   ")
        }

        val promoted = ManualOfferingStore.promoteOnVerified(draft, "ev-probe-ok")
        assertTrue(ManualOfferingStore.isUsable(promoted))
        assertEquals("LOW", MANUAL_OFFERING_CONFIDENCE)
        assertEquals("MANUAL", promoted.source)
        assertTrue(promoted.manual)
        assertEquals("ev-catalog-probe", promoted.evidenceId)

        val rows = ModelRowMapper.build(
            listOf(draftRef.copy(hidden = promoted.hidden, verified = true)),
            listOf(chainAvailability(offeringId = draft.id)),
            SelectedKey("acct-1", "cred-a", null),
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].verified)
        assertEquals("hidden-free-model", rows[0].providerModelId)
        assertEquals("hidden-free-model", rows[0].canonicalId)
        assertEquals(1, rankResults("hidden-free", rows).size)

        // 一次失敗不清空 last-good：已驗證者仍可見可用，只追加資訊性失敗記錄。
        val failed = ManualOfferingStore.downgradeOnFailure(promoted, "ev-probe-fail", "timeout")
        assertFalse(failed.removed)
        assertFalse(failed.hidden)
        assertTrue(ManualOfferingStore.isUsable(failed))
        assertEquals("ev-probe-fail", failed.lastFailureEvidenceId)
    }

    // ---- manual / official / observed / external 不互冒 ----

    @Test
    fun manualSourceNeverMasqueradesAsOfficialAnywhereInTheChain() {
        val manualAlias = ProviderModelAlias(
            aliasId = "alias:6:prov-a:12:hidden-model",
            providerId = "prov-a",
            providerModelId = "hidden-model",
            canonicalModelId = "gpt-4o",
            source = MappingSource.USER_OVERRIDE,
            confidence = MappingConfidence.LOW,
            verified = false,
        )
        assertTrue(isManualMappingSource(manualAlias.source))
        assertFalse(isOfficialMappingSource(manualAlias.source))
        assertEquals(MappingConfidence.LOW, manualAlias.confidence)
        assertEquals("LOW", MANUAL_OFFERING_CONFIDENCE)
        assertFalse(manualAlias.verified)

        // manual 建立路徑直接拒絕非 manual 家族來源（不建立會冒充 official 的 manual 列）。
        for (forged in listOf("OFFICIAL", "OBSERVED", "EXTERNAL", "LMSPEED")) {
            assertThrows(IllegalArgumentException::class.java) {
                ManualOfferingStore.createHiddenOffering(
                    providerId = "prov-a",
                    endpointId = "ep-chat",
                    providerModelId = "hidden-model",
                    source = forged,
                    evidenceId = "ev-1",
                    observedAt = 1_000L,
                )
            }
        }

        // 接線層與投影層的來源標籤：manual 一律 manual/MANUAL，official 不被合成出來。
        val manualRow = chainSourceRow(MANUAL_OFFERING_SOURCE_MANUAL, manual = true)
        val userRow = chainSourceRow("USER", manual = true)
        val officialRow = chainSourceRow("OFFICIAL", manual = false)
        val unknownRow = chainSourceRow("VENDOR_FEED", manual = false)

        assertEquals("manual", manualRow.sourceLabel)
        assertEquals("manual", userRow.sourceLabel)
        assertNotEquals("official", manualRow.sourceLabel)
        assertEquals("official", officialRow.sourceLabel)
        assertEquals("", unknownRow.sourceLabel)

        assertEquals("MANUAL", chainProjectedSourceLabel(MANUAL_OFFERING_SOURCE_MANUAL))
        assertEquals("USER", chainProjectedSourceLabel("USER"))
        assertEquals("OFFICIAL", chainProjectedSourceLabel("OFFICIAL"))
        assertEquals("UNKNOWN", chainProjectedSourceLabel("VENDOR_FEED"))
        assertNotEquals("OFFICIAL", chainProjectedSourceLabel(MANUAL_OFFERING_SOURCE_MANUAL))
    }

    // ---- MODEL-007：候選分歧時不挑一列冒充，且不丟列 ----

    @Test
    fun conflictingOrAbsentAvailabilityIsUnknownAndNoRowIsEverDropped() {
        val offering = chainOffering()
        val conflicting = listOf(
            chainAvailability(id = "av-1", priceState = "FREE_NOW", quotaState = "QUOTA_AVAILABLE"),
            chainAvailability(id = "av-2", priceState = "PAID", quotaState = "QUOTA_EXHAUSTED"),
        )
        val rows = ModelRowMapper.build(listOf(offering), conflicting, SelectedKey("acct-1", "cred-a", null))
        assertEquals(1, rows.size)
        assertEquals(PriceState.UNKNOWN, rows[0].priceState)
        assertEquals(QuotaState.UNKNOWN, rows[0].quotaState)
        // 有 availability 只是狀態分歧 → 仍算該 selected key 有資料，但不冒充免費。
        assertTrue(rows[0].selectedKeyUsable)
        assertTrue(filterModels(rows, ModelFilter(usableOnly = true)).isNotEmpty())
        assertTrue(filterModels(rows, ModelFilter(freeNowOnly = true)).isEmpty())

        // 零 availability → 列仍在、狀態 UNKNOWN、不可用，且搜尋仍找得到（不因缺資料消失）。
        val absent = ModelRowMapper.build(listOf(offering), emptyList(), SelectedKey("acct-1", "cred-a", null))
        assertEquals(1, absent.size)
        assertEquals(PriceState.UNKNOWN, absent.single().priceState)
        assertEquals(QuotaState.UNKNOWN, absent.single().quotaState)
        assertFalse(absent.single().selectedKeyUsable)
        assertEquals(1, rankResults("gpt-4o", absent).size)

        // scope 不合（endpoint／operation）→ 不跨維度污染。
        val otherEndpoint = ModelRowMapper.build(
            listOf(offering),
            listOf(chainAvailability(id = "av-3", endpointId = "ep-other", priceState = "FREE_NOW")),
            SelectedKey("acct-1", "cred-a", null),
        ).single()
        assertEquals(PriceState.UNKNOWN, otherEndpoint.priceState)
        assertFalse(otherEndpoint.selectedKeyUsable)
        val otherOperation = ModelRowMapper.build(
            listOf(offering),
            listOf(chainAvailability(id = "av-4", operation = "EMBEDDINGS", priceState = "FREE_NOW")),
            SelectedKey("acct-1", "cred-a", null),
        ).single()
        assertEquals(PriceState.UNKNOWN, otherOperation.priceState)
        assertFalse(otherOperation.selectedKeyUsable)

        // 兩個 offering 各一列，不合併、不吞列。
        val twoOfferings = listOf(
            chainOffering(id = "off-1", providerModelId = "gpt-4o"),
            chainOffering(id = "off-2", providerModelId = "claude-3-5"),
        )
        val projected = UsableProjection.usableOfferings(
            offerings = twoOfferings.map { it.toInput() },
            availabilities = conflicting.map { it.toInput() },
            selected = SelectedKey("acct-1", "cred-a", null),
        )
        assertEquals(2, projected.size)
        assertEquals(listOf("off-1", "off-2"), projected.map { it.offeringId })
        assertTrue(projected.all { it.priceState == "UNKNOWN" })
        assertEquals(listOf("MANUAL", "MANUAL"), projected.map { it.sourceLabel })
    }

    private fun chainSourceRow(source: String, manual: Boolean = true): ModelRow = ModelRowMapper.build(
        offerings = listOf(
            chainOffering(manual = manual, source = source, hidden = false),
        ),
        availabilities = listOf(chainAvailability()),
        selected = SelectedKey("acct-1", "cred-a", null),
    ).single()

    private fun chainProjectedSourceLabel(source: String, manual: Boolean = true): String =
        UsableProjection.usableOfferings(
            offerings = listOf(chainOffering(manual = manual, source = source).toInput()),
            availabilities = listOf(chainAvailability().toInput()),
            selected = SelectedKey("acct-1", "cred-a", null),
        ).single().sourceLabel
}
