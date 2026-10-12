package dev.modelatlas.app.data

import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample

/**
 * MYM-005 My Uptime scope（Contract §23.4；D-009；PRODUCT_SPEC §11）。
 *
 * My Uptime 只代表同一 Provider × Account × Credential × Group × Model × Endpoint 的
 * 真實可用歷史；六維全必填、精確全等才算同 scope。與 [PerformanceScope] 的
 * 「null 即不篩該維度」不同：[group] 為 null 只等於 SQL NULL／樣本 group null，
 * **不當萬用字元**（跨 group 匯總請逐 group 呼叫後自行合併，不得靠 null 通配）。
 * operation／source 不隔離：Manual Test／Playground／Compare Live／Assistant 真
 * inference 同 scope 累積（Contract §23.4）。
 */
data class MyUptimeScope(
    val providerId: String,
    val accountId: String,
    val safeCredentialId: String,
    val modelId: String,
    val endpointId: String,
    val group: String? = null,
) {
    /** 六維精確匹配；null group 只匹配 null group（非萬用字元）。 */
    fun matches(sample: PerformanceSample): Boolean =
        sample.providerId == providerId &&
            sample.accountId == accountId &&
            sample.safeCredentialId == safeCredentialId &&
            sample.providerModelId == modelId &&
            sample.endpointId == endpointId &&
            sample.group == group
}

/**
 * MYM-005 My Uptime 時間窗 preset（D-009：預設 7d；快捷 1h／24h／7d／30d／custom）。
 *
 * [windowMillis] 為固定 preset 的窗長；CUSTOM 由呼叫端提供、本檔不硬編碼 custom 值；
 * ALL 以 0 表示「全期」（沿用 `SampleDao.healthAggregates` sinceMillis=0 慣例），
 * 解析為 `[0, now)`。
 */
enum class MyUptimePreset(val windowMillis: Long?) {
    LAST_1H(60L * 60_000L),
    LAST_24H(24L * 60 * 60_000L),
    LAST_7D(7L * 24 * 60 * 60_000L),
    LAST_30D(30L * 24 * 60 * 60_000L),
    CUSTOM(null),
    ALL(0L),
    ;

    /**
     * 解析為半開窗 [startInclusive, endExclusive)（落 start 計、落 end 不計），
     * 複用 [PerformanceWindow] 語意與 [PerformanceWindow.endingAt]，不重寫窗數學。
     * 時鐘由呼叫端注入 [nowMillis]，本型別不讀系統時鐘。
     */
    fun windowFor(nowMillis: Long, customWindowMillis: Long? = null): PerformanceWindow = when (this) {
        CUSTOM -> PerformanceWindow.endingAt(
            nowMillis,
            requireNotNull(customWindowMillis) { "CUSTOM preset requires customWindowMillis" },
        )
        ALL -> PerformanceWindow(startInclusive = 0L, endExclusive = nowMillis)
        else -> PerformanceWindow.endingAt(nowMillis, requireNotNull(windowMillis))
    }

    companion object {
        /** D-009：預設 Performance 視窗 = 7d；集中一處，不散落 hard-code。 */
        val DEFAULT_7D = LAST_7D
    }
}

/**
 * MYM-005 My Uptime 聚合結果（Contract §23.4／§23.5；D-009；PRODUCT_SPEC §11）。
 *
 * 只算 My Uptime（同 scope 真實可用歷史）；Provider Success Rate 與 External
 * Availability 是獨立參考，永不經此物件冒充 My Uptime（D-009）。
 *
 * Unknown 語意（X-001 鐵律）：空窗／無 decided 樣本時 [successRate]、各分位、
 * [observedRpm]、[observedTpm] 全為 null，不補 0／Free；計數類（[sampleCount]／
 * [decided]／[successes]）為事實計數可為 0。
 *
 * Confidence 語意：成功率分母是 [decided]；[sampleCount] 為窗內非 CANCELLED 觀測數
 * （含在途 RUNNING）。兩數一併暴露，下游 UI 可依 sample 數呈現信心（如「98% (n=42)」）；
 * 本物件不發明新 confidence 檔位。
 */
data class MyUptimeSummary(
    val scope: MyUptimeScope,
    val window: PerformanceWindow,
    val sampleCount: Int,
    val decided: Int,
    val successes: Int,
    val successRate: Double?,
    val ttftMsAvg: Double?,
    val ttftMsP50: Double?,
    val ttftMsP90: Double?,
    val ttftMsP95: Double?,
    val tpsAvg: Double?,
    val tpsP50: Double?,
    val tpsP90: Double?,
    val tpsP95: Double?,
    val latencyMsAvg: Double?,
    val latencyMsP50: Double?,
    val latencyMsP90: Double?,
    val latencyMsP95: Double?,
    val observedRpm: Double?,
    val observedTpm: Double?,
    val lastObservedAt: Long?,
)

/**
 * MYM-005 Current Observed RPM/TPM（D-009：現在往前 rolling 60s）。
 * 命名與長窗平均（[MyUptimeSummary.observedRpm]／[observedTpm]）區分：
 * `current*` 一律指 rolling-60s，長窗一律指 window average；不得互相冒充。
 */
data class MyUptimeObservedRates(
    val currentRpm: Double?,
    val currentTpm: Double?,
)

/**
 * MYM-005 My Uptime 聚合純函式（不碰 DB／網路；模式對標 [PerformanceSummaryAggregator]）。
 *
 * 鐵律：
 * - CANCELLED 分子分母全排除（Contract §23.4／D-009）：User Stop／Cancel 不進
 *   numerator 也不進 denominator。SQL 層（`SampleDao.myUptimeSamples`）已不回
 *   CANCELLED 列；本層對 list 輸入再濾一次，兩處同守才閉合，單靠一處即留分母污染。
 * - RUNNING 不計入分母（沿 X-016 `decided` 語意）：[MyUptimeSummary.decided]＝
 *   非 RUNNING 且非 CANCELLED；成功率＝successes／decided。
 * - INTERRUPTED 保留在分母：進程被殺的真實歷史（開機 recover 寫入），非使用者取消，
 *   不在排除範圍；不得順手排除。
 * - 空窗回 null 不回 0／Free（X-001）：見 [MyUptimeSummary]／[MyUptimeObservedRates]。
 * - TTFT／TPS／latency 沿用 [PerformanceSummaryAggregator.quantileSorted] 既有
 *   分位數學，不重寫；平均用標準 mean，不另發明。
 * - secret 不進 log／結果：本物件只聚合 scope 維度與計數，永不輸出 credential 原始值。
 */
object MyUptimeAggregator {
    /** D-009：Current Observed RPM/TPM ＝ 現在往前 rolling 60s；集中常數。 */
    const val ROLLING_WINDOW_MILLIS = 60_000L

    private const val MILLIS_PER_MINUTE = 60_000.0

    private fun windowed(
        samples: List<PerformanceSample>,
        scope: MyUptimeScope,
        window: PerformanceWindow,
    ): List<PerformanceSample> = samples.filter {
        window.contains(it.requestStartedAt) && scope.matches(it) && it.outcome != Outcome.CANCELLED
    }

    /**
     * 以 My Uptime scope＋半開窗聚合 samples。
     * 以 [PerformanceSample.requestStartedAt] 落窗（沿 X-016 既有語意）。
     */
    fun summarize(
        samples: List<PerformanceSample>,
        scope: MyUptimeScope,
        window: PerformanceWindow,
    ): MyUptimeSummary {
        val scoped = windowed(samples, scope, window)
        val decidedSamples = scoped.filter { it.outcome != Outcome.RUNNING }
        val decided = decidedSamples.size
        val successes = decidedSamples.count { it.outcome == Outcome.SUCCESS }
        val successRate = if (decided > 0) successes.toDouble() / decided.toDouble() else null

        val ttftSorted = decidedSamples.mapNotNull { it.ttftMs?.toDouble() }.sorted()
        val tpsSorted = decidedSamples.mapNotNull { it.tps }.sorted()
        val latencySorted = decidedSamples.mapNotNull { it.totalLatencyMs?.toDouble() }.sorted()

        val hasTraffic = scoped.isNotEmpty() && window.durationMillis > 0L
        val minutes = window.durationMillis / MILLIS_PER_MINUTE
        val observedRpm = if (hasTraffic) scoped.size / minutes else null
        val totalKnown = scoped.mapNotNull { it.usage.totalTokens?.toDouble() }
        val observedTpm = if (hasTraffic && totalKnown.isNotEmpty()) totalKnown.sum() / minutes else null
        val lastObservedAt = scoped.maxOfOrNull { it.requestStartedAt }

        return MyUptimeSummary(
            scope = scope,
            window = window,
            sampleCount = scoped.size,
            decided = decided,
            successes = successes,
            successRate = successRate,
            ttftMsAvg = ttftSorted.takeIf { it.isNotEmpty() }?.average(),
            ttftMsP50 = PerformanceSummaryAggregator.quantileSorted(ttftSorted, 0.50),
            ttftMsP90 = PerformanceSummaryAggregator.quantileSorted(ttftSorted, 0.90),
            ttftMsP95 = PerformanceSummaryAggregator.quantileSorted(ttftSorted, 0.95),
            tpsAvg = tpsSorted.takeIf { it.isNotEmpty() }?.average(),
            tpsP50 = PerformanceSummaryAggregator.quantileSorted(tpsSorted, 0.50),
            tpsP90 = PerformanceSummaryAggregator.quantileSorted(tpsSorted, 0.90),
            tpsP95 = PerformanceSummaryAggregator.quantileSorted(tpsSorted, 0.95),
            latencyMsAvg = latencySorted.takeIf { it.isNotEmpty() }?.average(),
            latencyMsP50 = PerformanceSummaryAggregator.quantileSorted(latencySorted, 0.50),
            latencyMsP90 = PerformanceSummaryAggregator.quantileSorted(latencySorted, 0.90),
            latencyMsP95 = PerformanceSummaryAggregator.quantileSorted(latencySorted, 0.95),
            observedRpm = observedRpm,
            observedTpm = observedTpm,
            lastObservedAt = lastObservedAt,
        )
    }

    /**
     * Current Observed RPM/TPM（D-009）：現在往前 rolling [ROLLING_WINDOW_MILLIS]（60s）。
     * 與長窗平均（[summarize] 的 observedRpm／observedTpm）命名區分，不得互相冒充；
     * 也不得拿 FilterSheet 搜尋 recency（1/7/30d）充當此窗。
     * CANCELLED 全排除；RUNNING 為已發出的觀測流量、計入 RPM（沿 X-016
     * observedRpm 計入在途的既有語意），TPM 只取非 null usage。
     * 窗內無觀測即 Unknown：currentRpm／currentTpm 回 null，不補 0。
     */
    fun currentObservedRates(
        samples: List<PerformanceSample>,
        scope: MyUptimeScope,
        nowMillis: Long,
    ): MyUptimeObservedRates {
        val window = PerformanceWindow.endingAt(nowMillis, ROLLING_WINDOW_MILLIS)
        val scoped = windowed(samples, scope, window)
        val minutes = window.durationMillis / MILLIS_PER_MINUTE
        val currentRpm = if (scoped.isNotEmpty()) scoped.size / minutes else null
        val totalKnown = scoped.mapNotNull { it.usage.totalTokens?.toDouble() }
        val currentTpm = if (totalKnown.isNotEmpty()) totalKnown.sum() / minutes else null
        return MyUptimeObservedRates(currentRpm = currentRpm, currentTpm = currentTpm)
    }
}
