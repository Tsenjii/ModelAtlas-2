package dev.modelatlas.app.data

import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import kotlin.time.Duration

/**
 * X-017 rollup bucket 規格（PRODUCT_SPEC §16；BACKLOG X-017；TRACEABILITY P11）。
 *
 * D-2026-09-30-006（永久保留）：[spanMillis] 為單一 bucket 的時間跨度（毫秒），由呼叫端注入，
 * 僅供聚合查詢效能用；[retainBuckets] 即便非 null 亦不得觸發刪除（只做查詢視窗，不剪資料）。
 * 本檔不提供任何預設跨度。
 *
 * [spanMillis] 為單一 bucket 的時間跨度（毫秒），由呼叫端注入；本檔不提供任何預設跨度。
 * [retainBuckets] 為保留桶數，null＝全留（未知即保留，不猜、不刪）。
 */
data class RollupBucketSpec(
    val spanMillis: Long,
    val retainBuckets: Int? = null,
) {
    init {
        require(spanMillis > 0L) { "spanMillis must be > 0" }
        require(retainBuckets == null || retainBuckets >= 0) { "retainBuckets must be >= 0" }
    }
}

/**
 * X-017 RetentionPolicy（PRODUCT_SPEC §16「Telemetry retention 要有 abstraction／rollup；
 * 不可未經決策 hard-code raw sample 刪除天數」；IMPLEMENTATION_PLAN「暫不自動刪除 raw telemetry」）。
 *
 * D-2026-09-30-006（永久保留，ACTIVE）：samples／evidence／snapshots／history／research
 * 一律永久保留，不做定時刪除。本 policy 僅保留 rollup 抽象（聚合查詢效能用），purge 路徑
 * 不得啟用於產品資料。
 *
 * 相容說明：
 * - [rawSampleWindow] 歷史欄位，保留僅為原始碼相容，一律忽略（null 與非 null 皆不刪）。
 *   不得在本 repo 任何 production 程式碼內以裸數字寫死天數。
 * - [deletesRawEvidence] 歷史旗標，保留僅為原始碼相容，一律忽略；[RetentionRunner] 永為 no-op。
 * - **不實作自動刪除排程**：本檔只交付 abstraction 與可注入 policy，無 WorkManager／定時器。
 */
data class RetentionPolicy(
    val rawSampleWindow: Duration? = null,
    val rollupBuckets: List<RollupBucketSpec> = emptyList(),
    val deletesRawEvidence: Boolean = false,
)

/**
 * X-017 rollup 成果列（`performance_rollups` 的純函式鏡像；落庫表由未來 Room migration 追加，
 * 只加表、不改既有欄）。
 *
 * 以 scope（provider／account／credential／model）＋時間桶聚合；[successRate] 沿用 X-016 語意，
 * decided＝0 時為 null（Unknown 不等於 0）。
 */
data class PerformanceRollupBucket(
    val providerId: String,
    val accountId: String,
    val safeCredentialId: String,
    val modelId: String,
    val bucketStartInclusive: Long,
    val bucketEndExclusive: Long,
    val requestCount: Int,
    val decided: Int,
    val successes: Int,
    val successRate: Double?,
)

/**
 * X-017 剪枝計畫：只描述「可刪 id 集合」，不執行刪除。
 * D-2026-09-30-006（永久保留）：[idsToDelete] 恆為空集合，僅保留型別相容。
 * [providerId] 為作用域擁有者；本計畫永不含其他 provider 的樣本（禁止無條件全表刪）。
 */
data class PrunePlan(
    val providerId: String,
    val idsToDelete: Set<String>,
    val retainedRunning: Int,
    val retainedEvidence: Int,
)

/**
 * X-017 RollupStore 純函式（不碰 DB／網路）。
 *
 * D-2026-09-30-006（永久保留）：rollup 僅為聚合查詢效能抽象，永不觸發 raw 刪除；
 * 無「先寫 rollup 再剪 raw」路徑，桶本身亦永久保留。
 *
 * - [buildBuckets]：把樣本按 scope＋epoch 對齊的半開時間桶聚合成 [PerformanceRollupBucket]；
 *   RUNNING 計入 [PerformanceRollupBucket.requestCount] 但不計入 decided／successes。
 * - 落庫契約（未來 DAO 實作必須遵守）：只追加 rollup 桶，不剪 raw（D-2026-09-30-006）。
 * - RUNNING 與 evidence 關聯列永久保留（FND-006／F29 語意＋D-2026-09-30-006），見 [RetentionRunner]。
 */
object RollupStore {
    /**
     * 建桶：epoch 對齊的半開桶 [k*span, (k+1)*span)，按 scope 分組。
     * 以 [PerformanceSample.requestStartedAt] 落桶（RUNNING 尚無 completedAt，統一用開始時間）。
     */
    fun buildBuckets(
        samples: List<PerformanceSample>,
        bucketSpanMillis: Long,
    ): List<PerformanceRollupBucket> {
        require(bucketSpanMillis > 0L) { "bucketSpanMillis must be > 0" }
        return samples.groupBy { sample ->
            BucketKey(
                providerId = sample.providerId,
                accountId = sample.accountId,
                safeCredentialId = sample.safeCredentialId,
                modelId = sample.providerModelId,
                bucketStart = (sample.requestStartedAt / bucketSpanMillis) * bucketSpanMillis,
            )
        }.map { (key, rows) ->
            val decided = rows.count { it.outcome != Outcome.RUNNING }
            val successes = rows.count { it.outcome == Outcome.SUCCESS }
            PerformanceRollupBucket(
                providerId = key.providerId,
                accountId = key.accountId,
                safeCredentialId = key.safeCredentialId,
                modelId = key.modelId,
                bucketStartInclusive = key.bucketStart,
                bucketEndExclusive = key.bucketStart + bucketSpanMillis,
                requestCount = rows.size,
                decided = decided,
                successes = successes,
                successRate = if (decided > 0) successes.toDouble() / decided.toDouble() else null,
            )
        }.sortedWith(
            compareBy({ it.providerId }, { it.bucketStartInclusive }, { it.accountId }, { it.safeCredentialId }, { it.modelId }),
        )
    }

    /** 桶覆蓋的請求總數：rollup 保留性驗證用（刪 raw 前後等值比對）。 */
    fun requestCount(buckets: List<PerformanceRollupBucket>): Int = buckets.sumOf { it.requestCount }

    /** 桶覆蓋的已判定總數：同上。 */
    fun decidedCount(buckets: List<PerformanceRollupBucket>): Int = buckets.sumOf { it.decided }

    /** 桶覆蓋的成功總數：同上。 */
    fun successCount(buckets: List<PerformanceRollupBucket>): Int = buckets.sumOf { it.successes }

    private data class BucketKey(
        val providerId: String,
        val accountId: String,
        val safeCredentialId: String,
        val modelId: String,
        val bucketStart: Long,
    )
}

/**
 * X-017 RetentionRunner 純函式（不碰 DB／網路；不排程）。
 *
 * D-2026-09-30-006（永久保留）：本 runner 恆為 no-op，不產出任何刪除計畫。
 * 歷史欄位 [RetentionPolicy.rawSampleWindow]／[RetentionPolicy.deletesRawEvidence] 一律忽略，
 * 即便 opt-in 亦不刪。samples／evidence 永久保留。
 *
 * 鐵律：
 * - [isNoOp]：恆回 true（永久保留；policy 欄位僅相容保留）。
 * - 作用域：[planPrune] 只處理傳入的單一 [providerId]（回傳計畫仍標註該 provider，
 *   但 [PrunePlan.idsToDelete] 恆為空；他 provider 樣本永不進入計畫）。
 * - 證據保留：RUNNING 與 [evidenceSampleIds] 關聯列永久保留（FND-006／F29＋D-2026-09-30-006）。
 * - 時鐘：[nowMillis] 由呼叫端注入，僅供統計口徑，不觸發刪除。
 */
object RetentionRunner {
    /** 是否為 no-op：D-2026-09-30-006 永久保留，恆為 true（policy 欄位僅相容保留，一律忽略）。 */
    fun isNoOp(@Suppress("UNUSED_PARAMETER") policy: RetentionPolicy): Boolean = true

    /**
     * 產出單 provider 剪枝計畫。
     *
     * D-2026-09-30-006（永久保留）：恆回空刪除集合，不計算 cutoff、不篩選舊樣本。
     * [retainedRunning]／[retainedEvidence] 仍照傳入樣本統計，僅供可觀測性，不觸發刪除。
     *
     * @param evidenceSampleIds 與 evidence 表關聯的 sample id（terminal evidence 亦永久保留）。
     */
    fun planPrune(
        providerId: String,
        samples: List<PerformanceSample>,
        @Suppress("UNUSED_PARAMETER") policy: RetentionPolicy,
        @Suppress("UNUSED_PARAMETER") nowMillis: Long,
        evidenceSampleIds: Set<String> = emptySet(),
    ): PrunePlan {
        // D-2026-09-30-006（永久保留）：定時刪除路徑已移除，此處恆為 no-op。
        // 不計算 cutoff、不篩選舊樣本；idsToDelete 恆為空。
        val owned = samples.filter { it.providerId == providerId }
        val retainedRunning = owned.count { it.outcome == Outcome.RUNNING }
        val retainedEvidence = owned.count { it.id in evidenceSampleIds }
        return PrunePlan(
            providerId = providerId,
            idsToDelete = emptySet(),
            retainedRunning = retainedRunning,
            retainedEvidence = retainedEvidence,
        )
    }
}
