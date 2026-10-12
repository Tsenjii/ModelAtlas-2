package dev.modelatlas.app.data

import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.normalizeModelId

/**
 * LMS-002 外部模型名 → canonical 純函式（TRACEABILITY LMS-002）。
 *
 * 語意（對齊 `core/models/ModelIdentity.kt` 解析優先序
 * `exact→alias→normalized`、`core/domain/Domain.kt` 正規化、
 * `app/data/CanonicalStore` 查詢 API、`app/data/ExternalStore`
 * UNKNOWN 語意）：
 * - Step 0：空白直接 UNKNOWN（不猜）。
 * - Step 1：exact alias（原始字串完全命中）→ 回傳對應 canonical（HIGH）。
 * - Step 2：alias（正規化後命中 `normalizedAliasIndex`）→ 回傳對應 canonical（MEDIUM）。
 * - Step 3：正規化回退（[normalizeModelId] 後命中 `knownCanonicalIds`）→ 回傳正規化名（LOW）。
 *   `knownCanonicalIds` 由呼叫端以 `CanonicalStore` 查詢 API（`getById`／`getByNormalized`）
 *   查到的 canonicalId 集合傳入；本函式不做 IO、不查 DB。
 * - Step 4：以上皆 miss → UNKNOWN（回傳 null），絕不合成新的 canonical（不硬判）。
 *
 * 純函式、無 IO、無網路、無 secret、無 mock 資料。
 *
 * @param externalModelId 外部原始模型名（不透明字串，不解析 URL／endpoint）。
 * @param exactAliasIndex 原始字串 → canonicalId（完全比對，大小寫敏感）。
 * @param normalizedAliasIndex 正規化名 → canonicalId（大小寫／分隔符／latest 後綴不敏感）。
 * @param knownCanonicalIds 已知 canonicalId 集合（即正規化名集合）。
 * @return canonicalId；null 表 UNKNOWN（尚未解析，不猜）。
 */
fun mapExternalToCanonical(
    externalModelId: String,
    exactAliasIndex: Map<String, String> = emptyMap(),
    normalizedAliasIndex: Map<String, String> = emptyMap(),
    knownCanonicalIds: Set<String> = emptySet(),
): String? = mapExternalToCanonicalDecision(
    externalModelId = externalModelId,
    exactAliasIndex = exactAliasIndex,
    normalizedAliasIndex = normalizedAliasIndex,
    knownCanonicalIds = knownCanonicalIds,
).canonicalId

/**
 * LMS-002 映射決策（含來源／信心結構；TRACEABILITY LMS-002／LMS-005）。
 *
 * 與 [mapExternalToCanonical] 同解析優先序（exact→alias→normalized，對齊
 * `ModelIdentity.resolveCanonicalId`），另帶 `source`／`confidence` 供
 * [ExternalStore.saveMapping] 寫回（呼叫端以 [source] 透傳外部來源名，
 * 不寫 URL／endpoint；confidence 取 [MappingConfidence] 名）。
 * - exact 命中 → HIGH；alias 命中 → MEDIUM；正規化回退 → LOW；miss／空白 → UNKNOWN。
 * - 純函式、無 IO；`source` 空白由 [ExternalStore.saveMapping] 拒收，本函式不拋錯。
 */
data class ExternalCanonicalDecision(
    val canonicalId: String?,
    val source: String,
    val confidence: String,
)

fun mapExternalToCanonicalDecision(
    externalModelId: String,
    exactAliasIndex: Map<String, String> = emptyMap(),
    normalizedAliasIndex: Map<String, String> = emptyMap(),
    knownCanonicalIds: Set<String> = emptySet(),
    source: String = "LMSPEED",
): ExternalCanonicalDecision {
    if (externalModelId.isBlank()) return ExternalCanonicalDecision(null, source, MappingConfidence.UNKNOWN.name)
    exactAliasIndex[externalModelId]?.let { return ExternalCanonicalDecision(it, source, MappingConfidence.HIGH.name) }
    val normalized = normalizeModelId(externalModelId)
    if (normalized.isBlank()) return ExternalCanonicalDecision(null, source, MappingConfidence.UNKNOWN.name)
    normalizedAliasIndex[normalized]?.let { return ExternalCanonicalDecision(it, source, MappingConfidence.MEDIUM.name) }
    if (knownCanonicalIds.contains(normalized)) return ExternalCanonicalDecision(normalized, source, MappingConfidence.LOW.name)
    return ExternalCanonicalDecision(null, source, MappingConfidence.UNKNOWN.name)
}
