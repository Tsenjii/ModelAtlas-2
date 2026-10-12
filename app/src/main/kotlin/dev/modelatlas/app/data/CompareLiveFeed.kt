package dev.modelatlas.app.data

import android.database.Cursor
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.models.CompareLiveSampleFeed
import kotlinx.coroutines.CancellationException

/**
 * P6 session G：MODEL-008 Compare Live 產品 feed（Room telemetry 讀回）。
 *
 * TRACEABILITY：MODEL-008（PRODUCT_SPEC §8 Compare Live、§3 統一 telemetry、§10 FREE 判定）。
 *
 * - 唯讀：只做 `samples` 表 SELECT，不 INSERT／UPDATE／DELETE，不干擾
 *   [AtlasRepository.save] 正常保存路徑（InferenceEngine 唯一寫入入口）。
 * - 對應 [CompareLiveSampleFeed] 接線建議：以 providerId＋endpointId＋
 *   providerModelId（欄位 `modelId`）＋requestStartedAt（欄位 `startedAt`）
 *   取最新 `completedAt` 非 null 列；對不上回 null＝未知，永不補 0／不捏造。
 * - [finalFor] 精確 id 查詢：`WHERE id = ? AND completedAt IS NOT NULL`，高效唯讀，
 *   缺席／RUNNING／查詢失敗一律 null＝未知（永不補 0／不捏造 Free）；取消直接傳播。
 *   不改 legacy triple query（[latestFinalFor]／[finalSamples]）行為與測試。
 * - RUNNING（`completedAt` null）不是 final，一律排除。
 * - 只經手 safe id（`safeCredentialId`）；不碰 raw key／PAT。
 * - 映射沿用既有 [SampleEntity.toSample]（safeOutcome／safeSource／header 編解碼），
 *   Unknown 維持 Unknown，不依 model id 推導。
 * - 查詢失敗 fail-closed 回空／null（未知），不掩蓋 inference outcome；
 *   取消（CancellationException）直接傳播，不收斂。
 */
class RoomCompareLiveSampleFeed(
    private val db: AtlasDatabase,
) : CompareLiveSampleFeed {

    override fun finalSamples(): List<PerformanceSample> {
        return try {
            queryEntities(SELECT_ALL_SQL, emptyArray()).map { it.toSample() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun latestFinalFor(
        providerId: String,
        endpointId: String,
        providerModelId: String,
    ): PerformanceSample? {
        if (providerId.isEmpty() || endpointId.isEmpty() || providerModelId.isEmpty()) return null
        return try {
            queryEntities(SELECT_ONE_SQL, arrayOf<Any?>(providerId, endpointId, providerModelId))
                .map { it.toSample() }
                .firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 精確 id 查詢（MODEL-008 多 key runner 用；core `CompareLiveSampleFeed.finalFor`）。
     * `WHERE id = ? AND completedAt IS NOT NULL LIMIT 1`，唯讀高效；
     * 空 id／缺席／RUNNING／失敗一律 null，取消傳播。
     */
    override fun finalFor(sampleId: String): PerformanceSample? {
        if (sampleId.isEmpty()) return null
        return try {
            queryEntities(SELECT_BY_ID_SQL, arrayOf<Any?>(sampleId))
                .map { it.toSample() }
                .firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun queryEntities(sql: String, args: Array<Any?>): List<SampleEntity> {
        val cursor: Cursor = try {
            db.openHelper.readableDatabase.query(sql, args)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            cursor.use { c ->
                val out = ArrayList<SampleEntity>()
                while (c.moveToNext()) {
                    c.toEntityOrNull()?.let { out += it }
                }
                out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun Cursor.toEntityOrNull(): SampleEntity? {
        // 必填欄缺值即跳過該列（fail-closed，不捏造）。
        val id = optString(COL_ID) ?: return null
        val providerId = optString(COL_PROVIDER_ID) ?: return null
        val accountId = optString(COL_ACCOUNT_ID) ?: return null
        val safeCredentialId = optString(COL_SAFE_CREDENTIAL_ID) ?: return null
        val endpointId = optString(COL_ENDPOINT_ID) ?: return null
        val providerModelId = optString(COL_MODEL_ID) ?: return null
        val operation = optString(COL_OPERATION) ?: return null
        val source = optString(COL_SOURCE) ?: return null
        val requestStartedAt = optLong(COL_STARTED_AT) ?: return null
        val outcome = optString(COL_OUTCOME) ?: return null
        val rateLimitHeaders = optString(COL_RATE_LIMIT_HEADERS) ?: return null
        val priceState = optString(COL_PRICE_STATE) ?: return null
        val quotaState = optString(COL_QUOTA_STATE) ?: return null
        val streaming = optBoolean(COL_STREAMING) ?: return null
        val usageEstimated = optBoolean(COL_USAGE_ESTIMATED) ?: return null
        val retryCount = optInt(COL_RETRY_COUNT) ?: return null
        return SampleEntity(
            id = id,
            providerId = providerId,
            accountId = accountId,
            safeCredentialId = safeCredentialId,
            endpointId = endpointId,
            providerModelId = providerModelId,
            canonicalModelId = optString(COL_CANONICAL_MODEL_ID),
            group = optString(COL_GROUP),
            operation = operation,
            source = source,
            requestStartedAt = requestStartedAt,
            firstTokenAt = optLong(COL_FIRST_TOKEN_AT),
            completedAt = optLong(COL_COMPLETED_AT),
            streaming = streaming,
            ttftMs = optLong(COL_TTFT_MS),
            ttfbMs = optLong(COL_TTFB_MS),
            totalLatencyMs = optLong(COL_TOTAL_LATENCY_MS),
            tps = optDouble(COL_TPS),
            inputTokens = optLong(COL_INPUT_TOKENS),
            outputTokens = optLong(COL_OUTPUT_TOKENS),
            totalTokens = optLong(COL_TOTAL_TOKENS),
            reasoningTokens = optLong(COL_REASONING_TOKENS),
            cacheReadTokens = optLong(COL_CACHE_READ_TOKENS),
            cacheWriteTokens = optLong(COL_CACHE_WRITE_TOKENS),
            usageEstimated = usageEstimated,
            httpStatus = optInt(COL_HTTP_STATUS),
            outcome = outcome,
            retryCount = retryCount,
            responseModelId = optString(COL_RESPONSE_MODEL_ID),
            rateLimitHeaders = rateLimitHeaders,
            priceState = priceState,
            quotaState = quotaState,
            pricingSnapshotId = optString(COL_PRICING_SNAPSHOT_ID),
            quotaSnapshotBeforeId = optString(COL_QUOTA_SNAPSHOT_BEFORE_ID),
            quotaSnapshotAfterId = optString(COL_QUOTA_SNAPSHOT_AFTER_ID),
            actualCost = optString(COL_ACTUAL_COST),
            estimatedCost = optString(COL_ESTIMATED_COST),
        )
    }

    private fun Cursor.optString(column: String): String? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.optLong(column: String): Long? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getLong(i)
    }

    private fun Cursor.optInt(column: String): Int? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getInt(i)
    }

    private fun Cursor.optDouble(column: String): Double? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getDouble(i)
    }

    private fun Cursor.optBoolean(column: String): Boolean? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getInt(i) != 0
    }

    companion object {
        private const val COL_ID = "id"
        private const val COL_PROVIDER_ID = "providerId"
        private const val COL_ACCOUNT_ID = "accountId"
        private const val COL_SAFE_CREDENTIAL_ID = "safeCredentialId"
        private const val COL_ENDPOINT_ID = "endpointId"
        private const val COL_MODEL_ID = "modelId"
        private const val COL_CANONICAL_MODEL_ID = "canonicalModelId"
        private const val COL_GROUP = "group"
        private const val COL_OPERATION = "operation"
        private const val COL_SOURCE = "source"
        private const val COL_STARTED_AT = "startedAt"
        private const val COL_FIRST_TOKEN_AT = "firstTokenAt"
        private const val COL_COMPLETED_AT = "completedAt"
        private const val COL_STREAMING = "streaming"
        private const val COL_TTFT_MS = "ttftMs"
        private const val COL_TTFB_MS = "ttfbMs"
        private const val COL_TOTAL_LATENCY_MS = "totalLatencyMs"
        private const val COL_TPS = "tps"
        private const val COL_INPUT_TOKENS = "inputTokens"
        private const val COL_OUTPUT_TOKENS = "outputTokens"
        private const val COL_TOTAL_TOKENS = "totalTokens"
        private const val COL_REASONING_TOKENS = "reasoningTokens"
        private const val COL_CACHE_READ_TOKENS = "cacheReadTokens"
        private const val COL_CACHE_WRITE_TOKENS = "cacheWriteTokens"
        private const val COL_USAGE_ESTIMATED = "usageEstimated"
        private const val COL_HTTP_STATUS = "httpStatus"
        private const val COL_OUTCOME = "outcome"
        private const val COL_RETRY_COUNT = "retryCount"
        private const val COL_RESPONSE_MODEL_ID = "responseModelId"
        private const val COL_RATE_LIMIT_HEADERS = "rateLimitHeaders"
        private const val COL_PRICE_STATE = "priceState"
        private const val COL_QUOTA_STATE = "quotaState"
        private const val COL_PRICING_SNAPSHOT_ID = "pricingSnapshotId"
        private const val COL_QUOTA_SNAPSHOT_BEFORE_ID = "quotaSnapshotBeforeId"
        private const val COL_QUOTA_SNAPSHOT_AFTER_ID = "quotaSnapshotAfterId"
        private const val COL_ACTUAL_COST = "actualCost"
        private const val COL_ESTIMATED_COST = "estimatedCost"

        private const val COLUMN_LIST =
            "\"id\", \"providerId\", \"accountId\", \"safeCredentialId\", \"endpointId\", " +
                "\"modelId\", \"canonicalModelId\", \"group\", \"operation\", \"source\", " +
                "\"startedAt\", \"firstTokenAt\", \"completedAt\", \"streaming\", " +
                "\"ttftMs\", \"ttfbMs\", \"totalLatencyMs\", \"tps\", " +
                "\"inputTokens\", \"outputTokens\", \"totalTokens\", \"reasoningTokens\", " +
                "\"cacheReadTokens\", \"cacheWriteTokens\", \"usageEstimated\", \"httpStatus\", " +
                "\"outcome\", \"retryCount\", \"responseModelId\", \"rateLimitHeaders\", " +
                "\"priceState\", \"quotaState\", \"pricingSnapshotId\", " +
                "\"quotaSnapshotBeforeId\", \"quotaSnapshotAfterId\", \"actualCost\", \"estimatedCost\""

        private const val SELECT_ALL_SQL =
            "SELECT " + COLUMN_LIST + " FROM \"samples\" " +
                "WHERE \"completedAt\" IS NOT NULL ORDER BY \"startedAt\" DESC"

        private const val SELECT_ONE_SQL =
            "SELECT " + COLUMN_LIST + " FROM \"samples\" " +
                "WHERE \"providerId\" = ? AND \"endpointId\" = ? AND \"modelId\" = ? " +
                "AND \"completedAt\" IS NOT NULL ORDER BY \"startedAt\" DESC LIMIT 1"

        private const val SELECT_BY_ID_SQL =
            "SELECT " + COLUMN_LIST + " FROM \"samples\" " +
                "WHERE \"id\" = ? AND \"completedAt\" IS NOT NULL LIMIT 1"
    }
}
