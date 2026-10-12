package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState

/**
 * P6 session L2：MODEL-009 model detail 狀態合成（純 Kotlin，無 Android／Room 依賴）。
 *
 * TRACEABILITY：MODEL-009（price／quota／history model detail）。
 * 對應 PRODUCT_SPEC §4（價格／配額呈現）、§10（歷史／證據可追溯）、§11（未知明示）。
 *
 * - 輸入一律為自定的合成 struct（接線層由 snapshot_heads／snapshot_facts／price／quota
 *   真表映射），本檔不讀 DB、不依賴 Room entity。
 * - 單一 canonical 合成：price 歷史＋quota pools＋performance 摘要＋history 事件。
 * - 缺資料一律 UNKNOWN／null／空集合，不捏造、不填零值、不拿 0／Free 冒充未知。
 * - FREE 判定只吃 [priceState]（見 ModelDetailUi），本檔不由名稱／id 推論免費。
 * - 不同 Provider／Account／Credential／Model／Group／Endpoint／Operation 的觀測
 *   不互相覆蓋：pools 與 history 保留各自 scope 維度，只做展示排序，不做跨 scope 平均。
 */
enum class DetailQuotaKind {
    ACCOUNT_QUOTA,
    TOKEN_QUOTA,
    MANUAL_QUOTA,
    DEDICATED_ACCOUNT_QUOTA,
    DEDICATED_TOKEN_QUOTA,
}

/** 價格歷史點（單一觀測＋完整 scope；數值 null＝未知，不猜）。 */
data class DetailPricePoint(
    val observedAt: Long,
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val fixedPrice: Double? = null,
    val cachePrice: Double? = null,
    val state: PriceState = PriceState.UNKNOWN,
    val sourceLabel: String = "",
    // FX2 scope collapse 修復（rv1 scope collapse FAIL）：
    // 原先無任何 scope 欄，loader 只以 modelId IN 取點，不同 Provider／Account／
    // Credential／Group／Endpoint／Operation 被併成一條序列，跨 scope 跳動誤算變動。
    // 現補齊七維 scope（與 PricingScope／price_snapshots 同一命名空間；null＝未知 scope，
    // 不補零值、不自造），builder 先依 scope 分組再算變動／免費起算，不跨 scope 平均。
    val providerId: String = "",
    val accountId: String? = null,
    val credentialRef: String? = null,
    val modelId: String = "",
    val group: String? = null,
    val endpointId: String = "",
    val operation: String = "",
)

/** 七維 scope 分組鍵（長度前綴編碼；null／空字串／分隔符不碰撞，不猜）。 */
fun DetailPricePoint.scopeKey(): String = detailScopeKey(
    listOf(providerId, accountId, credentialRef, modelId, group, endpointId, operation),
)

/** 七維 scope 分組鍵編碼（與 PricingScope.storageKey 同語意，本檔獨立實作以免 UI 依賴 data 層）。 */
internal fun detailScopeKey(parts: List<String?>): String =
    parts.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }

/** 配額池（單一 scope；整數 null＝未知；unlimited null＝未知）。 */
data class DetailQuotaPool(
    val kind: DetailQuotaKind,
    val providerId: String = "",
    val accountId: String? = null,
    val credentialRef: String? = null,
    val state: QuotaState = QuotaState.UNKNOWN,
    val remainingRaw: Long? = null,
    val usedRaw: Long? = null,
    val grantedRaw: Long? = null,
    val unlimited: Boolean? = null,
    val expiresAtRaw: Long? = null,
    val observedAt: Long? = null,
    val sourceLabel: String = "",
    // FX2 scope collapse 修復：原先丟掉 group／endpointId／operation（＋modelId），
    // 與「保留七維 scope」宣告不符。現補齊（null＝未知 scope，不自造），排序含全維度，
    // 不合併、不平均、不同 scope 不互相覆蓋。
    val modelId: String = "",
    val group: String? = null,
    val endpointId: String = "",
    val operation: String = "",
)

/** 配額池 scope 分組鍵（含 kind；同 kind 下再按七維分組）。 */
fun DetailQuotaPool.scopeKey(): String = kind.name + "|" + detailScopeKey(
    listOf(providerId, accountId, credentialRef, modelId, group, endpointId, operation),
)

/** 效能摘要（全 null＝未知；未知不得當成 0）。 */
data class DetailPerformance(
    val success1h: Double? = null,
    val success24h: Double? = null,
    val tps: Double? = null,
    val ttftMs: Long? = null,
    val latencyMs: Long? = null,
)

/** 歷史事件（price／quota／performance 觀測統一排序展示用）。 */
data class DetailHistoryEvent(
    val observedAt: Long,
    val section: String,
    val stateLabel: String,
    val statusLabel: String = "",
    val sourceLabel: String = "",
)

/** 單 canonical 的 detail 合成結果。 */
data class ModelDetailState(
    val canonicalId: String,
    val displayName: String? = null,
    val priceState: PriceState = PriceState.UNKNOWN,
    val quotaState: QuotaState = QuotaState.UNKNOWN,
    val priceHistory: List<DetailPricePoint> = emptyList(),
    val quotaPools: List<DetailQuotaPool> = emptyList(),
    val performance: DetailPerformance = DetailPerformance(),
    val history: List<DetailHistoryEvent> = emptyList(),
    val priceChangeCount: Int? = null,
    val priceChangedAt: Long? = null,
    val freeSinceAt: Long? = null,
    /**
     * FR-003：exact selected scope（provider＋account＋key＋group＋offering
     * endpoint／operation）的最新 price point。
     * - null＝該 selected scope 無可判定報價（Unknown／unavailable）——UI 必須明示
     *   未知，不得回退 priceHistory.lastOrNull()、不得借 sibling scope、不得用
     *   lastGood 冒充 current。
     * - 只由 loader 以 selected scope 選出；直接建構 state 的呼叫端若不確定 scope，
     *   保持 null（fail-closed）。
     * - [priceHistory] 仍是完整 7D 全 scope 保留歷史，不得因此縮水。
     */
    val selectedQuote: DetailPricePoint? = null,
)

object ModelDetailStateBuilder {

    /**
     * 單 canonical 合成。
     *
     * - priceHistory 依 observedAt 升序（舊→新），同時間按 scopeKey＋sourceLabel 穩定排序；
     *   history 依 observedAt 降序（新→舊）。
     * - [priceState]/[quotaState] 由呼叫端（接線層 consensus）傳入，本函數不重算、不覆寫。
     * - priceChangeCount／priceChangedAt／freeSinceAt 先依 scope 分組再推導（FX2 修復）；
     *   跨 scope 跳動不計變動，全 UNKNOWN 或單 scope 無變動時一律 null（不捏造起算點）。
     * - quotaPools 穩定排序（kind→七維 scope→observedAt 新→舊），不合併、
     *   不平均、不同 scope 不互相覆蓋。
     * - history 由 priceHistory＋quotaPools＋傳入的 extraEvents 合成，不吃原始 body／secret。
     * - [selectedQuote] 只透傳 loader 選出的 exact selected-scope price point；
     *   null＝Unknown／unavailable，不由 history 推導、不借 sibling／lastGood。
     */
    fun build(
        canonicalId: String,
        displayName: String? = null,
        priceState: PriceState = PriceState.UNKNOWN,
        quotaState: QuotaState = QuotaState.UNKNOWN,
        priceHistory: List<DetailPricePoint> = emptyList(),
        quotaPools: List<DetailQuotaPool> = emptyList(),
        performance: DetailPerformance = DetailPerformance(),
        extraEvents: List<DetailHistoryEvent> = emptyList(),
        selectedQuote: DetailPricePoint? = null,
    ): ModelDetailState {
        require(canonicalId.isNotBlank()) { "canonicalId must not be blank" }
        val sortedPrices = priceHistory.sortedWith(
            compareBy<DetailPricePoint> { it.observedAt }
                .thenBy { it.scopeKey() }
                .thenBy { it.sourceLabel },
        )
        val sortedPools = quotaPools.sortedWith(
            compareBy<DetailQuotaPool> { it.kind.ordinal }
                .thenBy { it.providerId }
                .thenBy { it.accountId }
                .thenBy { it.credentialRef }
                .thenBy { it.modelId }
                .thenBy { it.group }
                .thenBy { it.endpointId }
                .thenBy { it.operation }
                .thenByDescending { it.observedAt ?: Long.MIN_VALUE },
        )
        val derivedChanges = derivePriceChanges(sortedPrices)
        val derivedFreeSince = deriveFreeSince(sortedPrices, priceState)
        val history = synthesizeHistory(sortedPrices, sortedPools, extraEvents)
        return ModelDetailState(
            canonicalId = canonicalId,
            displayName = displayName?.takeIf { it.isNotBlank() },
            priceState = priceState,
            quotaState = quotaState,
            priceHistory = sortedPrices,
            quotaPools = sortedPools,
            performance = performance,
            history = history,
            priceChangeCount = derivedChanges?.first,
            priceChangedAt = derivedChanges?.second,
            freeSinceAt = derivedFreeSince,
            selectedQuote = selectedQuote,
        )
    }

    /**
     * 價格變動次數＋最後變動時間（FX2 scope 隔離修復）。
     * 先依 [DetailPricePoint.scopeKey] 分組，同 scope 內才比對連續點的
     * (input，output，fixed，cache) 四元組＋state；任一端含 null 即視為
     * 「無法證明相等」而計一次變動（fail-closed）。跨 scope 跳動不計變動；
     * 總變動＝各組變動之和，最後變動＝各組最後變動之最大值；全零回 null。
     */
    internal fun derivePriceChanges(sorted: List<DetailPricePoint>): Pair<Int, Long>? {
        if (sorted.size < 2) return null
        var changes = 0
        var lastChangeAt: Long? = null
        val byScope = sorted.groupBy { it.scopeKey() }
        for ((_, points) in byScope) {
            if (points.size < 2) continue
            val ordered = points.sortedBy { it.observedAt }
            for (i in 1 until ordered.size) {
                val prev = ordered[i - 1]
                val curr = ordered[i]
                if (!priceTupleEquals(prev, curr)) {
                    changes++
                    val at = curr.observedAt
                    if (lastChangeAt == null || at > lastChangeAt) lastChangeAt = at
                }
            }
        }
        if (changes == 0) return null
        return changes to (lastChangeAt ?: sorted.last().observedAt)
    }

    private fun priceTupleEquals(a: DetailPricePoint, b: DetailPricePoint): Boolean =
        a.inputPrice == b.inputPrice &&
            a.outputPrice == b.outputPrice &&
            a.fixedPrice == b.fixedPrice &&
            a.cachePrice == b.cachePrice &&
            a.state == b.state

    /**
     * 免費起算點（FX2 scope 隔離修復）：僅當最終 [priceState] 為 FREE_NOW 時，
     * 按 scope 分組取每組尾段連續 FREE_NOW 起點；任一有歷史的組尾段非 FREE 即回 null
     * （不斷言、不回填、不拿單 scope 冒充全體、不拿跨 provider 片段當連續免費期）；
     * 全組皆 FREE 時回各組起點之最大值（全體皆 FREE 的時刻），fail-closed。
     */
    internal fun deriveFreeSince(sorted: List<DetailPricePoint>, finalState: PriceState): Long? {
        if (finalState != PriceState.FREE_NOW) return null
        if (sorted.isEmpty()) return null
        val byScope = sorted.groupBy { it.scopeKey() }
        var overallStart: Long? = null
        for ((_, points) in byScope) {
            val ordered = points.sortedBy { it.observedAt }
            var start: Long? = null
            for (point in ordered) {
                if (point.state == PriceState.FREE_NOW) {
                    if (start == null) start = point.observedAt
                } else {
                    start = null
                }
            }
            // 該 scope 尾段非 FREE（start 被清掉）→ 整體未知，不冒充。
            val groupStart = start ?: return null
            if (overallStart == null || groupStart > overallStart) overallStart = groupStart
        }
        return overallStart
    }

    private fun synthesizeHistory(
        prices: List<DetailPricePoint>,
        pools: List<DetailQuotaPool>,
        extra: List<DetailHistoryEvent>,
    ): List<DetailHistoryEvent> {
        val out = ArrayList<DetailHistoryEvent>(prices.size + pools.size + extra.size)
        for (p in prices) {
            out.add(
                DetailHistoryEvent(
                    observedAt = p.observedAt,
                    section = "PRICE",
                    stateLabel = p.state.name,
                    sourceLabel = p.sourceLabel,
                ),
            )
        }
        for (q in pools) {
            val at = q.observedAt ?: continue
            out.add(
                DetailHistoryEvent(
                    observedAt = at,
                    section = "QUOTA:" + q.kind.name,
                    stateLabel = q.state.name,
                    sourceLabel = q.sourceLabel,
                ),
            )
        }
        out.addAll(extra)
        out.sortWith(
            compareByDescending<DetailHistoryEvent> { it.observedAt }
                .thenBy { it.section }
                .thenBy { it.stateLabel },
        )
        return out
    }

    /**
     * 多候選 head state 共識：0 候選或任一分歧回 UNKNOWN（fail-closed）。
     * 接線層在映射為 [priceState]／[quotaState] 前呼叫；大小寫／空白正規化後比對。
     */
    fun consensusPriceState(states: List<String>): PriceState {
        if (states.isEmpty()) return PriceState.UNKNOWN
        val normalized = states.map { it.trim().uppercase() }
        if (!normalized.all { it == normalized.first() }) return PriceState.UNKNOWN
        return runCatching { enumValueOf<PriceState>(normalized.first()) }.getOrDefault(PriceState.UNKNOWN)
    }

    fun consensusQuotaState(states: List<String>): QuotaState {
        if (states.isEmpty()) return QuotaState.UNKNOWN
        val normalized = states.map { it.trim().uppercase() }
        if (!normalized.all { it == normalized.first() }) return QuotaState.UNKNOWN
        return runCatching { enumValueOf<QuotaState>(normalized.first()) }.getOrDefault(QuotaState.UNKNOWN)
    }
}
