package dev.modelatlas.app.data

import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import kotlin.math.ceil
import kotlin.math.floor

/**
 * X-016 PerformanceSummary 篩選範圍（PRODUCT_SPEC §11；BACKLOG X-016；TRACEABILITY P11）。
 *
 * 以 provider 為錨點的 scope 子集：[providerId] 必填，其餘維度為 null 即「此呼叫不篩該維度」。
 * 跨帳號／跨憑證隔離（X-004）由 [matches] 與 [PerformanceSummaryAggregator.summarizeGrouped] 保證：
 * 需要嚴格隔離時請用 grouped API，其分組鍵必含 account 與 credential，不會跨 scope 混合。
 */
data class PerformanceScope(
    val providerId: String,
    val accountId: String? = null,
    val safeCredentialId: String? = null,
    val modelId: String? = null,
    val group: String? = null,
    val operation: String? = null,
) {
    /**
     * Scope 命中判定：非 null 維度必須全等；null 維度不篩。
     * null 只可用於「呼叫端已明確要求跨 scope 匯總」的情境，不得當成萬用字元預設。
     */
    fun matches(sample: PerformanceSample): Boolean =
        sample.providerId == providerId &&
            (accountId == null || sample.accountId == accountId) &&
            (safeCredentialId == null || sample.safeCredentialId == safeCredentialId) &&
            (modelId == null || sample.providerModelId == modelId) &&
            (group == null || sample.group == group) &&
            (operation == null || sample.operation == operation)
}

/**
 * X-016 聚合時間窗（BACKLOG X-016 驗收第 2 項）：半開區間 [startInclusive, endExclusive)。
 *
 * - 邊界樣本歸屬無歧義：落在 start 計入，落在 end 不計入。
 * - 時鐘由呼叫端注入（[endingAt] 取 nowMillis），測試可注入假 clock；本型別不讀系統時鐘。
 */
data class PerformanceWindow(
    val startInclusive: Long,
    val endExclusive: Long,
) {
    init {
        require(startInclusive <= endExclusive) { "Window must satisfy startInclusive <= endExclusive" }
    }

    /** 半開區間命中判定。 */
    fun contains(timestampMillis: Long): Boolean =
        timestampMillis >= startInclusive && timestampMillis < endExclusive

    /** 窗長（毫秒）。<=0 時所有 observed 速率回 null（不除零、不回 0 冒充已知）。 */
    val durationMillis: Long get() = endExclusive - startInclusive

    companion object {
        /**
         * 以注入時鐘錨定窗：[nowMillis - windowMillis, nowMillis)。
         * [windowMillis] 由呼叫端提供，本檔不設任何預設窗長。
         */
        fun endingAt(nowMillis: Long, windowMillis: Long): PerformanceWindow {
            require(windowMillis >= 0L) { "windowMillis must be >= 0" }
            return PerformanceWindow(startInclusive = nowMillis - windowMillis, endExclusive = nowMillis)
        }
    }
}

/**
 * X-016 聚合結果（PRODUCT_SPEC §11；BACKLOG X-016）。
 *
 * 本地觀測口徑（observed），永不標示為 provider 配額：
 * 欄位名／型別不含配額語意，下游 UI 呈現 observed 速率時必須標示
 * [PerformanceSummaryAggregator.OBSERVED_LABEL]（「本地觀測」）。
 *
 * Unknown 語意（X-001）：樣本缺席的指標一律 null，不補 0。
 * - 空窗：[successRate]、各分位、observed 速率、[lastObservedAt] 全為 null；
 *   計數類（[requestCount]／[decided]／[successes]／[cancelled] 等）為事實計數可為 0。
 * - token 未知（null）不補零：全列未知時對應 TPM 為 null。
 *
 * SQL 對應契約（未來 PerformanceSummaryDao／SampleDao.aggregate 實作時必須遵守，
 * 本物件為其 Kotlin 純函式鏡像）：GROUP BY provider／account／credential／model＋時間窗，
 * 在 SQL 層聚合，不拉全表；**不得先 LIMIT 截斷再算比率**（錯誤分母，驗收負向項）。
 */
data class PerformanceSummary(
    val scope: PerformanceScope,
    val window: PerformanceWindow,
    val requestCount: Int,
    val decided: Int,
    val successes: Int,
    val cancelled: Int,
    val successRate: Double?,
    val httpErrorCount: Int,
    val rate429Count: Int,
    val rate429: Double?,
    val ttftMsP50: Double?,
    val ttftMsP90: Double?,
    val ttftMsP95: Double?,
    val tpsP50: Double?,
    val tpsP90: Double?,
    val tpsP95: Double?,
    val latencyMsP50: Double?,
    val latencyMsP90: Double?,
    val latencyMsP95: Double?,
    val observedRpm: Double?,
    val observedInputTpm: Double?,
    val observedOutputTpm: Double?,
    val observedTotalTpm: Double?,
    val lastObservedAt: Long?,
)

/**
 * X-016 grouped 分組鍵（X-004 隔離）：必含 account 與 credential，
 * 同一鍵內的樣本才可互相聚合，跨鍵永不混合。
 */
data class PerformanceScopeKey(
    val providerId: String,
    val accountId: String,
    val safeCredentialId: String,
    val modelId: String,
    val group: String?,
    val operation: String,
) {
    fun toScope(): PerformanceScope = PerformanceScope(
        providerId = providerId,
        accountId = accountId,
        safeCredentialId = safeCredentialId,
        modelId = modelId,
        group = group,
        operation = operation,
    )
}

/**
 * X-016 samples 聚合純函式（不碰 DB／網路；模式對標 [HealthRollup]）。
 *
 * 鐵律：
 * - RUNNING 不計入分母：[decided]＝非 RUNNING；成功率＝successes／decided（X-001 精神）。
 * - CANCELLED 單獨分類：計入 decided 與 [cancelled]，永不計入 [successes]。
 * - 空窗回 null 不回 0（X-001）：見 [PerformanceSummary]。
 * - 指標取自 decided 樣本的非 null 值；RUNNING 的 TTFT／TPS／latency 一律忽略。
 */
object PerformanceSummaryAggregator {
    /** 下游 UI 呈現 observed 速率時必須標示的字樣（本地觀測，非 provider 配額）。 */
    const val OBSERVED_LABEL = "本地觀測"

    private const val HTTP_429 = 429
    private const val MILLIS_PER_MINUTE = 60_000.0

    /**
     * 以 scope＋半開窗聚合 samples。
     * 以 [PerformanceSample.requestStartedAt] 落窗（RUNNING 尚無 completedAt，統一用開始時間）。
     */
    fun summarize(
        samples: List<PerformanceSample>,
        scope: PerformanceScope,
        window: PerformanceWindow,
    ): PerformanceSummary {
        val scoped = samples.filter { window.contains(it.requestStartedAt) && scope.matches(it) }
        val decidedSamples = scoped.filter { it.outcome != Outcome.RUNNING }
        val decided = decidedSamples.size
        val successes = decidedSamples.count { it.outcome == Outcome.SUCCESS }
        val cancelled = decidedSamples.count { it.outcome == Outcome.CANCELLED }
        val successRate = if (decided > 0) successes.toDouble() / decided.toDouble() else null
        val rate429Count = scoped.count { it.httpStatus == HTTP_429 }
        val rate429 = if (decided > 0) rate429Count.toDouble() / decided.toDouble() else null
        val httpErrorCount = scoped.count { val status = it.httpStatus; status != null && status in 400..599 }

        val ttftSorted = decidedSamples.mapNotNull { it.ttftMs?.toDouble() }.sorted()
        val tpsSorted = decidedSamples.mapNotNull { it.tps }.sorted()
        val latencySorted = decidedSamples.mapNotNull { it.totalLatencyMs?.toDouble() }.sorted()

        val hasTraffic = scoped.isNotEmpty() && window.durationMillis > 0L
        val minutes = window.durationMillis / MILLIS_PER_MINUTE
        val observedRpm = if (hasTraffic) scoped.size / minutes else null
        val inputKnown = scoped.mapNotNull { it.usage.inputTokens?.toDouble() }
        val outputKnown = scoped.mapNotNull { it.usage.outputTokens?.toDouble() }
        val totalKnown = scoped.mapNotNull { it.usage.totalTokens?.toDouble() }
        val observedInputTpm = if (hasTraffic && inputKnown.isNotEmpty()) inputKnown.sum() / minutes else null
        val observedOutputTpm = if (hasTraffic && outputKnown.isNotEmpty()) outputKnown.sum() / minutes else null
        val observedTotalTpm = if (hasTraffic && totalKnown.isNotEmpty()) totalKnown.sum() / minutes else null
        val lastObservedAt = scoped.maxOfOrNull { it.requestStartedAt }

        return PerformanceSummary(
            scope = scope,
            window = window,
            requestCount = scoped.size,
            decided = decided,
            successes = successes,
            cancelled = cancelled,
            successRate = successRate,
            httpErrorCount = httpErrorCount,
            rate429Count = rate429Count,
            rate429 = rate429,
            ttftMsP50 = quantileSorted(ttftSorted, 0.50),
            ttftMsP90 = quantileSorted(ttftSorted, 0.90),
            ttftMsP95 = quantileSorted(ttftSorted, 0.95),
            tpsP50 = quantileSorted(tpsSorted, 0.50),
            tpsP90 = quantileSorted(tpsSorted, 0.90),
            tpsP95 = quantileSorted(tpsSorted, 0.95),
            latencyMsP50 = quantileSorted(latencySorted, 0.50),
            latencyMsP90 = quantileSorted(latencySorted, 0.90),
            latencyMsP95 = quantileSorted(latencySorted, 0.95),
            observedRpm = observedRpm,
            observedInputTpm = observedInputTpm,
            observedOutputTpm = observedOutputTpm,
            observedTotalTpm = observedTotalTpm,
            lastObservedAt = lastObservedAt,
        )
    }

    /**
     * 隔離式分組聚合（X-004）：先落窗，再按 [scopeKeyOf] 分組逐組 [summarize]。
     * 跨帳號／跨憑證／跨模型永不混合；呼叫端不得先截斷串列再分組。
     */
    fun summarizeGrouped(
        samples: List<PerformanceSample>,
        window: PerformanceWindow,
    ): Map<PerformanceScopeKey, PerformanceSummary> {
        val windowed = samples.filter { window.contains(it.requestStartedAt) }
        return windowed.groupBy(::scopeKeyOf).mapValues { (key, rows) ->
            summarize(rows, key.toScope(), window)
        }
    }

    /** 分組鍵萃取：provider／account／credential／model／group／operation 全維度。 */
    fun scopeKeyOf(sample: PerformanceSample): PerformanceScopeKey = PerformanceScopeKey(
        providerId = sample.providerId,
        accountId = sample.accountId,
        safeCredentialId = sample.safeCredentialId,
        modelId = sample.providerModelId,
        group = sample.group,
        operation = sample.operation,
    )

    /**
     * 已排序串列的分位數（線性內插，兩端點夾取）。
     * 空串列回 null（缺席即 null，不補零）；q 必須在 [0, 1]。
     */
    fun quantileSorted(sorted: List<Double>, q: Double): Double? {
        require(q in 0.0..1.0) { "q must be in [0, 1]" }
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lower = floor(pos).toInt()
        val upper = ceil(pos).toInt()
        if (lower == upper) return sorted[lower]
        val weight = pos - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
    }
}
