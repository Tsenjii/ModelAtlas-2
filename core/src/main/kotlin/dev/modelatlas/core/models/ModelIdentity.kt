package dev.modelatlas.core.models

import dev.modelatlas.core.domain.CanonicalModel
import dev.modelatlas.core.domain.canonicalIdFor
import dev.modelatlas.core.domain.normalizeModelId

/**
 * MODEL-001／002 alias 解析與 canonical 去重（PRODUCT_SPEC §6／§7）。
 *
 * - 解析優先序：exact（原始 ID 完全命中）→ alias（正規化後命中 alias 索引）→
 *   normalized（回退為正規化名即 canonical）。
 * - 去重：同一 canonical（正規化名相同）不因不同 key／不同大小寫／分隔符重複建立。
 * - 純函式、無 IO；Room 寫入層的冪等與來源守衛見 `CanonicalStore`。
 */
fun resolveCanonicalId(
    rawModelId: String,
    exactAliasIndex: Map<String, String> = emptyMap(),
    normalizedAliasIndex: Map<String, String> = emptyMap(),
): String {
    exactAliasIndex[rawModelId]?.let { return it }
    normalizedAliasIndex[normalizeModelId(rawModelId)]?.let { return it }
    return canonicalIdFor(rawModelId)
}

/** 兩筆原始 ID 是否屬同一 canonical（大小寫／分隔符／latest 後綴差異視為相同）。 */
fun isSameCanonical(aRawId: String, bRawId: String): Boolean =
    normalizeModelId(aRawId) == normalizeModelId(bRawId)

/**
 * 去重：既有集合已有同正規化名（或同 canonicalId）即回傳既有，不新建；
 * 否則以正規化名產生新的 [CanonicalModel]。正規化為空即拋，不猜。
 */
fun deduplicateCanonical(
    existing: Collection<CanonicalModel>,
    candidateRawId: String,
    candidateDisplayName: String? = null,
): CanonicalModel {
    val normalized = normalizeModelId(candidateRawId)
    require(normalized.isNotBlank()) { "model id normalizes to blank" }
    existing.firstOrNull { it.normalizedName == normalized }?.let { return it }
    val id = canonicalIdFor(candidateRawId)
    existing.firstOrNull { it.canonicalId == id }?.let { return it }
    return CanonicalModel(
        canonicalId = id,
        normalizedName = normalized,
        displayName = candidateDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: candidateRawId.trim(),
    )
}
