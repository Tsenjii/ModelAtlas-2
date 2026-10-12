package dev.modelatlas.app.ui

import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.Modality
import dev.modelatlas.core.domain.ModelCategory
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import java.util.Locale
import kotlin.math.abs

/**
 * P6 worker B：Models 搜尋邏輯（純 Kotlin，無 Android 依賴）。
 *
 * TRACEABILITY：MODEL-004（exact/fuzzy/alias/normalized search）、MODEL-005（Filter Sheet 邏輯層）。
 * 對應 PRODUCT_SPEC §8（Global Search 四層；Filter Sheet 維度）、§2（Collect everything, personalize display）。
 *
 * - 輸入一律為自定的 [ModelRow]，不依賴 Room entity（避免與 worker A 的 schema 變動耦合）。
 * - FREE 徽章只吃傳入的 [ModelRow.priceState]，絕不依 model id / 名稱含 free 判斷。
 * - 價格 / 成功率未知（null）不得當成 0 / Free；有上、下限條件時，未知值一律視為「無法證明符合」而不通過。
 * - 收藏 / 最近 / 監看以 canonicalId 為鍵，id 集由呼叫端傳入。
 */
object ModelSearchDefaults {
    /** 搜尋結果預設上限，避免無界回傳。 */
    const val MAX_RESULTS_DEFAULT = 100

    /** fuzzy 編輯距離絕對上限（見 [fuzzyThreshold] 的分級）。 */
    const val FUZZY_MAX_DISTANCE = 2
}

/**
 * UI 層模型列。由接線層（主 AI）做 entity → Row 映射；Room entity 不得漏進本檔。
 *
 * - [sourceLabel] 僅透傳顯示（manual / official / observed / external），不得互冒；空白代表未知。
 * - [verified] 為 false 時 UI 不得標 VERIFIED。
 * - 價格相關 null = 未知（Unknown），UI 顯示「未知」，禁 $0 / 免費字樣。
 */
data class ModelRow(
    val providerId: String,
    val providerModelId: String,
    val canonicalId: String,
    val aliasNames: List<String> = emptyList(),
    val displayName: String? = null,
    val priceState: PriceState = PriceState.UNKNOWN,
    val quotaState: QuotaState = QuotaState.UNKNOWN,
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val cachePrice: Double? = null,
    val success1h: Double? = null,
    val success24h: Double? = null,
    val tps: Double? = null,
    val ttftMs: Long? = null,
    val latencyMs: Long? = null,
    val contextTokens: Long? = null,
    val group: String? = null,
    val vendor: String? = null,
    val primaryCategory: ModelCategory? = null,
    val capabilities: Set<Capability> = emptySet(),
    val modalities: Set<Modality> = emptySet(),
    val hasTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsFiles: Boolean = false,
    val supportsReasoning: Boolean = false,
    val sourceLabel: String = "",
    val verified: Boolean = false,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    val priceChangedAt: Long? = null,
    val priceChangeCount: Int? = null,
    val freeSinceAt: Long? = null,
    val selectedKeyUsable: Boolean = false,
    val endpointId: String? = null,
    val operation: String? = null,
)

/**
 * 篩選條件 struct。全部可空，null（集合則 null 或空集合）= 該維度不過濾。
 * §8 維度全覆蓋：FREE NOW / input-output-cache 價 / success 1h-24h / TPS-TTFT-latency /
 * context-group-provider-vendor / primaryCategory-capabilities-modalities /
 * tools-vision-files-reasoning / 新增-異動-價格波動-free uptime / 收藏-最近-監看 / selected-key usable。
 */
data class ModelFilter(
    val freeNowOnly: Boolean? = null,
    val minInputPrice: Double? = null,
    val maxInputPrice: Double? = null,
    val minOutputPrice: Double? = null,
    val maxOutputPrice: Double? = null,
    val minCachePrice: Double? = null,
    val maxCachePrice: Double? = null,
    val minSuccess1h: Double? = null,
    val minSuccess24h: Double? = null,
    val minTps: Double? = null,
    val maxTps: Double? = null,
    val minTtftMs: Long? = null,
    val maxTtftMs: Long? = null,
    val minLatencyMs: Long? = null,
    val maxLatencyMs: Long? = null,
    val minContextTokens: Long? = null,
    val maxContextTokens: Long? = null,
    val groups: Set<String>? = null,
    val providerIds: Set<String>? = null,
    val vendors: Set<String>? = null,
    val primaryCategories: Set<ModelCategory>? = null,
    val capabilities: Set<Capability>? = null,
    /** true = 列出的 capability 需全部具備；false = 任一具備即可。 */
    val capabilityMatchAll: Boolean = false,
    val modalities: Set<Modality>? = null,
    val requireTools: Boolean? = null,
    val requireVision: Boolean? = null,
    val requireFiles: Boolean? = null,
    val requireReasoning: Boolean? = null,
    val addedAfter: Long? = null,
    val addedBefore: Long? = null,
    val updatedAfter: Long? = null,
    val updatedBefore: Long? = null,
    val priceChangedAfter: Long? = null,
    val priceChangedBefore: Long? = null,
    val minPriceChanges: Int? = null,
    /** 最小免費持續時數；以 [freeSinceAt] 與呼叫端傳入的 nowMs 計算。 */
    val minFreeUptimeHours: Double? = null,
    val onlyFavorites: Boolean? = null,
    val favoriteIds: Set<String>? = null,
    val onlyRecent: Boolean? = null,
    val recentIds: Set<String>? = null,
    val onlyWatched: Boolean? = null,
    val watchlistIds: Set<String>? = null,
    val usableOnly: Boolean? = null,
)

/** 搜尋四層。排序優先序：EXACT > ALIAS > NORMALIZED > FUZZY。 */
enum class SearchTier {
    EXACT,
    ALIAS,
    NORMALIZED,
    FUZZY,
}

/**
 * 排序後的結果列。
 * @param tier null = 空查詢 passthrough（未做文字比對；呼叫端已套用 FREE NOW 優先預設排序）。
 */
data class RankedRow(
    val row: ModelRow,
    val tier: SearchTier?,
    val distance: Int = 0,
)

/**
 * 正規化查詢字串：小寫（Locale.ROOT）＋ 非字母數字（含分隔符 `_-. /:|()+[]{}...`）一律轉單一空白 ＋
 * 連續空白合併 ＋ 前後 trim。例：「GPT-4o」→「gpt 4o」；「Llama_3.1」→「llama 3 1」。
 */
fun normalizeQuery(raw: String): String {
    val lower = raw.lowercase(Locale.ROOT)
    val sb = StringBuilder(lower.length)
    var prevSpace = true
    for (c in lower) {
        if (c.isLetterOrDigit()) {
            sb.append(c)
            prevSpace = false
        } else if (!prevSpace) {
            sb.append(' ')
            prevSpace = true
        }
    }
    val len = sb.length
    if (len > 0 && sb[len - 1] == ' ') sb.setLength(len - 1)
    return sb.toString()
}

/** 依查詢字長分級的 fuzzy 距離上限：短查詢只允許前綴（避免雜訊），長查詢最多 2。 */
internal fun fuzzyThreshold(queryLength: Int): Int = when {
    queryLength <= 3 -> 0
    queryLength <= 6 -> 1
    else -> ModelSearchDefaults.FUZZY_MAX_DISTANCE
}

private fun tierOrder(tier: SearchTier?): Int = when (tier) {
    SearchTier.EXACT -> 0
    SearchTier.ALIAS -> 1
    SearchTier.NORMALIZED -> 2
    SearchTier.FUZZY -> 3
    null -> 4
}

private val rankComparator = compareBy<RankedRow>(
    { tierOrder(it.tier) },
    { it.distance },
    { if (it.row.priceState == PriceState.FREE_NOW) 0 else 1 },
    { it.row.canonicalId },
)

/**
 * 四層排序搜尋。空集合 / 空查詢 / maxResults <= 0 皆不崩潰。
 * 空查詢回傳全部（截斷至 maxResults），以 [sortForDefaultView] 排 FREE NOW 優先，tier 為 null。
 * 複雜度：每列 O(q²) 上界（q = 查詢字長；長度差預檢＋早退 Levenshtein），語料線性，結果截斷。
 */
fun rankResults(
    query: String,
    rows: List<ModelRow>,
    maxResults: Int = ModelSearchDefaults.MAX_RESULTS_DEFAULT,
): List<RankedRow> {
    if (maxResults <= 0) return emptyList()
    val q = normalizeQuery(query)
    if (q.isEmpty()) {
        return sortForDefaultView(rows).take(maxResults).map { RankedRow(it, null, 0) }
    }
    val out = ArrayList<RankedRow>(rows.size)
    for (row in rows) {
        matchRow(q, row)?.let { out.add(it) }
    }
    out.sortWith(rankComparator)
    return if (out.size > maxResults) out.subList(0, maxResults) else out
}

private fun normalizedTargets(row: ModelRow): List<String> {
    val out = ArrayList<String>(2 + row.aliasNames.size)
    out.add(normalizeQuery(row.canonicalId))
    out.add(normalizeQuery(row.providerModelId))
    for (alias in row.aliasNames) out.add(normalizeQuery(alias))
    return out
}

private fun matchRow(q: String, row: ModelRow): RankedRow? {
    val canon = normalizeQuery(row.canonicalId)
    val prov = normalizeQuery(row.providerModelId)
    if (q == canon || q == prov) return RankedRow(row, SearchTier.EXACT, 0)
    val aliasNorm = row.aliasNames.map { normalizeQuery(it) }
    if (aliasNorm.any { it == q }) return RankedRow(row, SearchTier.ALIAS, 0)
    if (canon.contains(q) || prov.contains(q) || aliasNorm.any { it.contains(q) }) {
        return RankedRow(row, SearchTier.NORMALIZED, 0)
    }
    val threshold = fuzzyThreshold(q.length)
    var best = Int.MAX_VALUE
    val targets = ArrayList<String>(2 + aliasNorm.size)
    targets.add(canon)
    targets.add(prov)
    targets.addAll(aliasNorm)
    for (t in targets) {
        if (t.isEmpty()) continue
        if (t.startsWith(q)) {
            best = 0
            break
        }
        // MAJOR4 修法 (a)：刪除無界 isSubsequence 捷徑，只留有界 Levenshtein，避免任意間隙旁路 threshold。
        val d = boundedLevenshtein(q, t, threshold)
        if (d < best) best = d
        if (best == 0) break
    }
    return if (best <= threshold) RankedRow(row, SearchTier.FUZZY, best) else null
}

/**
 * 有界 Levenshtein：長度差預檢＋列最小值早退，DP 規模被查詢字長與 max 上界限制，
 * 不做無上限 O(n²)。回傳值 > max 表示「超過界線」，呼叫端視為不匹配。
 */
private fun boundedLevenshtein(a: String, b: String, max: Int): Int {
    if (a == b) return 0
    if (max < 0) return Int.MAX_VALUE
    if (a.isEmpty()) return if (b.length <= max) b.length else max + 1
    if (b.isEmpty()) return if (a.length <= max) a.length else max + 1
    if (abs(a.length - b.length) > max) return max + 1
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        var rowMin = curr[0]
        val ca = a[i - 1]
        for (j in 1..b.length) {
            val cost = if (ca == b[j - 1]) 0 else 1
            val v = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            curr[j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > max) return max + 1
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
}

/** 預設視圖排序：FREE NOW 優先，其餘依 canonicalId 穩定排列（非免費由 UI dim＋下沉）。 */
fun sortForDefaultView(rows: List<ModelRow>): List<ModelRow> =
    rows.sortedWith(
        compareBy(
            { if (it.priceState == PriceState.FREE_NOW) 0 else 1 },
            { it.canonicalId },
        ),
    )

private fun inRange(value: Double?, min: Double?, max: Double?): Boolean {
    if (min == null && max == null) return true
    if (value == null) return false
    if (min != null && value < min) return false
    if (max != null && value > max) return false
    return true
}

private fun inRange(value: Long?, min: Long?, max: Long?): Boolean {
    if (min == null && max == null) return true
    if (value == null) return false
    if (min != null && value < min) return false
    if (max != null && value > max) return false
    return true
}

/** 時間窗為閉區間；任一端有界而 row 值未知（null）時視為不通過。 */
private fun inWindow(value: Long?, after: Long?, before: Long?): Boolean = inRange(value, after, before)

private fun rowHasTools(row: ModelRow): Boolean =
    row.hasTools || Capability.TOOL_CALLING in row.capabilities || Capability.FUNCTION_CALLING in row.capabilities

private fun rowHasVision(row: ModelRow): Boolean =
    row.supportsVision || Capability.VISION in row.capabilities

private fun rowHasFiles(row: ModelRow): Boolean =
    row.supportsFiles || Capability.FILES in row.capabilities

private fun rowHasReasoning(row: ModelRow): Boolean =
    row.supportsReasoning || Capability.REASONING in row.capabilities

private fun matchesFilter(row: ModelRow, filter: ModelFilter, nowMs: Long): Boolean {
    if (filter.freeNowOnly == true && row.priceState != PriceState.FREE_NOW) return false
    if (!inRange(row.inputPrice, filter.minInputPrice, filter.maxInputPrice)) return false
    if (!inRange(row.outputPrice, filter.minOutputPrice, filter.maxOutputPrice)) return false
    if (!inRange(row.cachePrice, filter.minCachePrice, filter.maxCachePrice)) return false
    if (filter.minSuccess1h != null && (row.success1h == null || row.success1h < filter.minSuccess1h)) return false
    if (filter.minSuccess24h != null && (row.success24h == null || row.success24h < filter.minSuccess24h)) return false
    if (!inRange(row.tps, filter.minTps, filter.maxTps)) return false
    if (!inRange(row.ttftMs, filter.minTtftMs, filter.maxTtftMs)) return false
    if (!inRange(row.latencyMs, filter.minLatencyMs, filter.maxLatencyMs)) return false
    if (!inRange(row.contextTokens, filter.minContextTokens, filter.maxContextTokens)) return false
    if (!filter.groups.isNullOrEmpty() && (row.group == null || row.group !in filter.groups)) return false
    if (!filter.providerIds.isNullOrEmpty() && row.providerId !in filter.providerIds) return false
    if (!filter.vendors.isNullOrEmpty() && (row.vendor == null || row.vendor !in filter.vendors)) return false
    if (!filter.primaryCategories.isNullOrEmpty() &&
        (row.primaryCategory == null || row.primaryCategory !in filter.primaryCategories)
    ) {
        return false
    }
    if (!filter.capabilities.isNullOrEmpty()) {
        val wanted = filter.capabilities
        if (filter.capabilityMatchAll) {
            if (!row.capabilities.containsAll(wanted)) return false
        } else {
            if (row.capabilities.intersect(wanted).isEmpty()) return false
        }
    }
    if (!filter.modalities.isNullOrEmpty() && row.modalities.intersect(filter.modalities).isEmpty()) return false
    if (filter.requireTools == true && !rowHasTools(row)) return false
    if (filter.requireVision == true && !rowHasVision(row)) return false
    if (filter.requireFiles == true && !rowHasFiles(row)) return false
    if (filter.requireReasoning == true && !rowHasReasoning(row)) return false
    if (!inWindow(row.addedAt, filter.addedAfter, filter.addedBefore)) return false
    if (!inWindow(row.updatedAt, filter.updatedAfter, filter.updatedBefore)) return false
    if (!inWindow(row.priceChangedAt, filter.priceChangedAfter, filter.priceChangedBefore)) return false
    if (filter.minPriceChanges != null &&
        (row.priceChangeCount == null || row.priceChangeCount < filter.minPriceChanges)
    ) {
        return false
    }
    if (filter.minFreeUptimeHours != null) {
        val since = row.freeSinceAt ?: return false
        val uptimeHours = (nowMs - since).toDouble() / 3_600_000.0
        if (uptimeHours < filter.minFreeUptimeHours) return false
    }
    // 收藏 / 最近 / 監看：以 canonicalId 為鍵；要求開啟但 id 集缺失或為空時不通過（不明示猜測）。
    if (filter.onlyFavorites == true && filter.favoriteIds?.contains(row.canonicalId) != true) return false
    if (filter.onlyRecent == true && filter.recentIds?.contains(row.canonicalId) != true) return false
    if (filter.onlyWatched == true && filter.watchlistIds?.contains(row.canonicalId) != true) return false
    if (filter.usableOnly == true && !row.selectedKeyUsable) return false
    return true
}

/** 多維 AND 篩選；空集合直接回傳空；nowMs 預設取系統時間（測試傳固定值）。 */
fun filterModels(
    rows: List<ModelRow>,
    filter: ModelFilter,
    nowMs: Long = System.currentTimeMillis(),
): List<ModelRow> = rows.filter { matchesFilter(it, filter, nowMs) }
