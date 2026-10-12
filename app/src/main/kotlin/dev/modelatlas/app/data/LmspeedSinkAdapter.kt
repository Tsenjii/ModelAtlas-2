package dev.modelatlas.app.data

import dev.modelatlas.core.lmspeed.LmspeedObservation
import dev.modelatlas.core.lmspeed.LmspeedObservationSink

/**
 * P7 S3 fetch 結果 → ExternalStore 落庫轉接（TRACEABILITY LMS-001 import/discovery seed、
 * LMS-003 external provenance/cache、LMS-005 free/provider seed workflow；
 * PRODUCT_SPEC §13 enrichment＋discovery seed、非 ground truth；RESEARCH_POLICY §7）。
 *
 * 對應 S2 契約：
 * - `core/lmspeed/LmspeedImporter.kt` 的 [LmspeedObservationSink]（本檔即其產品接線；
 *   欄位與 `LmspeedObservation` 一一對應，見 [toExternalEntity]）。
 * - `app/data/ExternalStore.kt` 落庫語意（只寫 `external_observations`；冪等 upsert；
 *   TTL 以 `fetchedAt + ttlMillis <= now` 判定；`canonicalModelId` null＝UNKNOWN 不猜；
 *   失敗不刪舊資料；絕不碰 `snapshot_facts`／`snapshot_heads`／`samples`／`telemetry`）。
 *
 * 鐵律（違者視為產品缺陷）：
 * - TTL／confidence 透傳：[toExternalEntity] 逐欄 1:1 搬運，不覆寫、不給預設、不正規化；
 *   `ttlMillis = 0` 即寫入當下過期（由 Store 判定），轉接層不得攔截。
 * - canonical 不猜：`canonicalModelId` 原樣透傳（S2 importer 恆 null；若未來 LMS-002
 *   在上游解析，照樣透傳，不在此合成）。
 * - 只寫外部槽：本檔唯一寫入路徑是 [ExternalStore.saveObservations]（經 [forStore]）；
 *   不做 delete／purge（過期清理是排程的顯式操作，不在 import 路徑）。
 * - 失敗即回錯：writer 丟例外即向上传播，[LmspeedImporter] 即回 `Failure` 並保留舊資料；
 *   本檔不吞錯、不重試（不燒配額）、不寫半批。
 * - 空批 no-op：空列直接返回，不進 DB transaction（與 importer「非空才呼叫」對稱，雙保險）。
 * - 無 key、無網路、無 URL：本檔只搬運已取得承載；來源 URL／endpoint 不在此出現
 *   （source-agnostic，來源清單等 R 研究）。
 */
fun LmspeedObservation.toExternalEntity(): ExternalObservationEntity =
    ExternalObservationEntity(
        observationId = observationId,
        source = source,
        externalModelId = externalModelId,
        canonicalModelId = canonicalModelId,
        payloadJson = payloadJson,
        fetchedAt = fetchedAt,
        ttlMillis = ttlMillis,
        confidence = confidence,
    )

/** 外部觀測寫入器（產品以 [ExternalStore.saveObservations] 轉接；測試以記憶體 writer 注入）。 */
fun interface ExternalObservationWriter {
    suspend fun write(rows: List<ExternalObservationEntity>)
}

/**
 * 落庫轉接器（[LmspeedObservationSink] 的產品實作）。
 *
 * 產品接線：`LmspeedSinkAdapter.forStore(externalStore)` → 傳入
 * `LmspeedImporter(fetcher, adapter)`。
 */
class LmspeedSinkAdapter(
    private val writer: ExternalObservationWriter,
) : LmspeedObservationSink {

    override suspend fun saveObservations(rows: List<LmspeedObservation>) {
        if (rows.isEmpty()) return
        writer.write(rows.map { it.toExternalEntity() })
    }

    companion object {
        /** 產品工廠：轉接至 [ExternalStore]（只寫 `external_observations`）。 */
        fun forStore(store: ExternalStore): LmspeedSinkAdapter =
            LmspeedSinkAdapter(writer = ExternalObservationWriter { rows ->
                store.saveObservations(rows)
            })
    }
}
