package dev.modelatlas.app.data

import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.ProviderHealth

/**
 * §11 health 聚合列：以本地 `samples` 為唯一來源的 observed 事實。
 * `total` 含進行中樣本；`decided`＝非 RUNNING 且非 CANCELLED（User Stop/Cancel 全排除；已判定），健康判定只用 decided，
 * 避免把進行中誤判為成功或失敗。AVG 對 null／未完成自動忽略（缺席即 null，不補零）。
 * 本型別永不承載 official health——official 只由 providers 表官方來源寫入。
 */
data class HealthAggregateRow(
    val providerId: String,
    val modelId: String,
    val total: Int,
    val decided: Int,
    val successes: Int,
    val rateLimited: Int,
    val avgTtftMs: Double?,
    val avgTps: Double?,
    val avgLatencyMs: Double?,
    val lastObservedAt: Long?,
)

/**
 * §11 health rollup 純函式（不碰 DB／網路）：把 samples 聚合結果映射為 observed [HealthState]。
 * 門檻為產品定義（非官方事實），且**未知不猜**：
 * - 無已判定樣本（decided<=0）→ UNKNOWN（不因樣本為 0 就當健康或離線）。
 * - 成功率 >=0.95 且無 429 → HEALTHY。
 * - >=0.90 → DEGRADED；>=0.50 → UNSTABLE；<0.50 → OFFLINE。
 * 成功率 = successes / decided；429 為額外不穩定訊號（成功率佳時不予降級）。
 */
object HealthRollup {
    const val HEALTHY_THRESHOLD = 0.95
    const val DEGRADED_THRESHOLD = 0.90
    const val UNSTABLE_THRESHOLD = 0.50

    fun observedHealth(decided: Int, successes: Int, rateLimited: Int): HealthState {
        if (decided <= 0) return HealthState.UNKNOWN
        val safeSuccesses = successes.coerceIn(0, decided)
        val successRate = safeSuccesses.toDouble() / decided.toDouble()
        return when {
            successRate >= HEALTHY_THRESHOLD && rateLimited <= 0 -> HealthState.HEALTHY
            successRate >= DEGRADED_THRESHOLD -> HealthState.DEGRADED
            successRate >= UNSTABLE_THRESHOLD -> HealthState.UNSTABLE
            else -> HealthState.OFFLINE
        }
    }

    /** 同 provider 多模型列合併：decided／successes／rateLimited 加總後套同一門檻；無列即 UNKNOWN。 */
    fun observedHealth(rows: List<HealthAggregateRow>): HealthState =
        observedHealth(
            decided = rows.sumOf { it.decided },
            successes = rows.sumOf { it.successes },
            rateLimited = rows.sumOf { it.rateLimited },
        )

    /** 最新觀測時間：全列 lastObservedAt 最大值；無列回 0（從未觀測，非已知時間）。 */
    fun observedAt(rows: List<HealthAggregateRow>): Long =
        rows.mapNotNull { it.lastObservedAt }.maxOrNull() ?: 0L
}

/**
 * §11 health rollup store：讀 samples 聚合 → 映射 observed health →（可選）寫回 providers.observedHealth。
 *
 * 鐵律：
 * - official／observed 分離：本 store 只碰 observed 欄；official 由官方來源另行寫入。
 * - Unknown 不猜：聚合為 UNKNOWN 時不寫回（保留 last-good），不把 0 樣本當健康。
 * - 一次聚合失敗不清空 last-good：無資料時 `refreshObserved` 回 UNKNOWN 且不更新欄位。
 * - 只讀 samples＋寫 providers.observedHealth／observedHealthAt 兩欄，不碰 telemetry 其他表。
 */
class HealthRollupStore(private val db: AtlasDatabase) {

    /** 單 provider 各模型 observed health（不寫回）；只讀。 */
    suspend fun modelHealth(providerId: String, sinceMillis: Long = 0L): Map<String, HealthState> {
        val rows = db.sampleDao().healthAggregates(providerId, sinceMillis)
        return rows.associate { it.modelId to HealthRollup.observedHealth(it.decided, it.successes, it.rateLimited) }
    }

    /** provider 級 observed health（不寫回）；official 由 [ProviderHealth.official] 另填。 */
    suspend fun observedHealth(providerId: String, sinceMillis: Long = 0L): HealthState =
        HealthRollup.observedHealth(db.sampleDao().healthAggregates(providerId, sinceMillis))

    /** 完整 ProviderHealth：official 讀 providers 表，observed 由 samples 聚合；checkedAt 取最新觀測時間。 */
    suspend fun providerHealth(providerId: String, sinceMillis: Long = 0L): ProviderHealth {
        val row = db.providerDao().getById(providerId)
        val rows = db.sampleDao().healthAggregates(providerId, sinceMillis)
        return ProviderHealth(
            providerId = providerId,
            official = parseHealth(row?.officialHealth),
            observed = HealthRollup.observedHealth(rows),
            checkedAt = HealthRollup.observedAt(rows),
        )
    }

    /**
     * 聚合並在結果非 UNKNOWN 時寫回 observedHealth／observedHealthAt；回傳本次聚合結果。
     * UNKNOWN（無已判定樣本）不寫回，保留 last-good。nowMillis 為裝置時間，由呼叫端提供。
     */
    suspend fun refreshObserved(providerId: String, nowMillis: Long, sinceMillis: Long = 0L): HealthState {
        val rows = db.sampleDao().healthAggregates(providerId, sinceMillis)
        val state = HealthRollup.observedHealth(rows)
        if (state != HealthState.UNKNOWN) {
            db.providerDao().updateObservedHealth(providerId, state.name, nowMillis)
        }
        return state
    }

    private fun parseHealth(name: String?): HealthState =
        if (name == null) HealthState.UNKNOWN
        else runCatching { HealthState.valueOf(name) }.getOrDefault(HealthState.UNKNOWN)
}
