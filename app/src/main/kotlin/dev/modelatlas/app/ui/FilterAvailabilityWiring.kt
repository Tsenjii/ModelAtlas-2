package dev.modelatlas.app.ui

/**
 * P6 repair worker B：MODEL-005 FilterAvailability 真推導（純 Kotlin，可單測）。
 *
 * TRACEABILITY：MODEL-005（PRODUCT_SPEC §8 Filter Sheet 每一維度）。
 * - 從 ModelsTab 當前 modelRows 真值推導各維度可用性；有真資料才開啟，無資料一律
 *   disabled 顯示「尚無資料」，不造假、不隱藏區段。
 * - 收藏／最近／監看（hasCollectionData）恆 false：無 persistence（建表屬 VM/Repo/schema，
 *   本 worker 不可碰 Entities/Daos/Migration），誠實 disabled；由主 AI 建表後開啟。
 * - selected-key（hasKeyAvailabilityData）：呼叫端傳入是否有已選 key（selectedId 非 null）；
 *   無 key 時 usableOnly 維度 disabled（不讓使用者開一個必空的篩選）。
 */
object FilterAvailabilityWiring {

    /**
     * 真推導（純函式）。
     *
     * @param modelRows Models 全量列（搜尋＋篩選前的唯一資料來源）。
     * @param hasSelectedKey 是否有已選連線（selectedId 非 null）。
     */
    fun derive(modelRows: List<ModelRow>, hasSelectedKey: Boolean): FilterAvailability {
        if (modelRows.isEmpty()) {
            return FilterAvailability(
                hasPriceData = false,
                hasTelemetryData = false,
                hasContextData = false,
                providers = null,
                groups = null,
                vendors = null,
                hasClassificationData = false,
                hasTimestampData = false,
                hasPriceHistoryData = false,
                hasFreeUptimeData = false,
                hasCollectionData = false,
                hasKeyAvailabilityData = false,
            )
        }
        val providers = modelRows.map { it.providerId }
            .filter { it.isNotBlank() }.distinct().sorted()
        val groups = modelRows.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }
            .distinct().sorted()
        val vendors = modelRows.mapNotNull { it.vendor?.takeIf { v -> v.isNotBlank() } }
            .distinct().sorted()
        return FilterAvailability(
            hasPriceData = modelRows.any {
                it.inputPrice != null || it.outputPrice != null || it.cachePrice != null
            },
            hasTelemetryData = modelRows.any {
                it.success1h != null || it.success24h != null || it.tps != null ||
                    it.ttftMs != null || it.latencyMs != null
            },
            hasContextData = modelRows.any { it.contextTokens != null },
            providers = providers,
            groups = groups,
            vendors = vendors,
            hasClassificationData = modelRows.any {
                it.primaryCategory != null || it.capabilities.isNotEmpty() ||
                    it.modalities.isNotEmpty() || it.hasTools || it.supportsVision ||
                    it.supportsFiles || it.supportsReasoning
            },
            hasTimestampData = modelRows.any { it.addedAt != null || it.updatedAt != null },
            hasPriceHistoryData = modelRows.any {
                it.priceChangedAt != null || it.priceChangeCount != null
            },
            hasFreeUptimeData = modelRows.any { it.freeSinceAt != null },
            hasCollectionData = false,
            hasKeyAvailabilityData = hasSelectedKey,
        )
    }
}
