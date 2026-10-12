package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState

/**
 * P6 worker B：Models 搜尋列＋結果列 UI。
 *
 * TRACEABILITY：MODEL-004（搜尋結果呈現）、MODEL-005（Filter Sheet 入口）。
 * 對應 PRODUCT_SPEC §8（Global Search；FREE NOW 優先，非免費 dim＋下沉）、§2（空狀態明示）。
 *
 * - 全部走傳入的 List（[ModelRow] / [RankedRow]），本檔不讀 DB、不做 entity 映射。
 * - 價格徽章只吃 [ModelRow.priceState]：UNKNOWN 顯示「未知」，禁 $0 / 免費字樣。
 * - [ModelRow.sourceLabel] 透傳顯示（manual / official / observed / external 不互冒）。
 * - 未驗證（verified=false）不顯示 VERIFIED。
 * - 字串暫為硬編碼繁中；接線時主 AI 搬入 values/strings.xml（本 worker 不可改既有檔）。
 */

/** 價格徽章文字。UNKNOWN 一律「未知」，不得出現免費 / $ / 0 字樣。 */
@StringRes
fun priceBadgeText(priceState: PriceState): Int = when (priceState) {
    PriceState.FREE_NOW -> R.string.price_free_now
    PriceState.PAID -> R.string.price_paid
    PriceState.PARTIALLY_FREE -> R.string.price_partially_free
    PriceState.UNKNOWN -> R.string.value_unknown
}

/** 非免費 offering 是否 dim＋下沉（預設 FREE NOW 優先視圖）。 */
fun shouldDimRow(row: ModelRow, dimNonFree: Boolean = true): Boolean =
    dimNonFree && row.priceState != PriceState.FREE_NOW

/** 來源標籤透傳；空白 = 未知（明示，不猜）。 */
fun sourceDisplayLabel(sourceLabel: String, unknown: String): String =
    if (sourceLabel.isBlank()) unknown else sourceLabel

/** 驗證標籤；未驗證回傳 null（呼叫端不渲染）。 */
fun verificationLabel(verified: Boolean): String? = if (verified) "VERIFIED" else null

/** 配額中文標籤；UNKNOWN 一律「未知」（F17；exhaustive when，未知字串不透傳）。 */
@StringRes
fun quotaDisplayLabel(quotaState: QuotaState): Int = when (quotaState) {
    QuotaState.QUOTA_AVAILABLE -> R.string.quota_available
    QuotaState.QUOTA_LOW -> R.string.quota_low
    QuotaState.QUOTA_EXHAUSTED -> R.string.quota_exhausted_search
    QuotaState.RATE_LIMIT_ONLY -> R.string.quota_rate_limit_only
    QuotaState.PUBLIC_POOL -> R.string.quota_public_pool
    QuotaState.UNLIMITED_CLAIMED -> R.string.quota_unlimited_claimed
    QuotaState.NO_KNOWN_CONSUMABLE_QUOTA -> R.string.quota_none_search
    QuotaState.UNKNOWN -> R.string.value_unknown
}

/** 價格數字文字；null 一律「未知」，不用 0 冒充（F17）。 */
fun priceValueText(value: Double?, unknown: String): String = value?.toString() ?: unknown

/** 顯示名稱；未提供時退回 canonicalId。 */
fun modelDisplayName(row: ModelRow): String =
    row.displayName?.takeIf { it.isNotBlank() } ?: row.canonicalId

/** 空狀態文字（列表與 UI 測試共用，避免 UI 與測試各說各話）。 */
@StringRes
fun searchEmptyText(hasActiveFilter: Boolean): Int =
    if (hasActiveFilter) R.string.models_empty_no_data_relax else R.string.models_empty_no_data

/**
 * 搜尋列。插入點建議（主 AI 接線）：AtlasScreen.ModelsTab 頂部；
 * 篩選按鈕開啟 [ModelFilterSheet]，計數吃 [activeFilterCount]。
 */
@Composable
fun ModelSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
    activeFilterCount: Int = 0,
    modifier: Modifier = Modifier,
) = AtlasTheme {
    val searchField: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = fieldModifier,
            label = { Text(stringResource(R.string.search_models_label)) },
            placeholder = { Text(stringResource(R.string.search_models_hint)) },
            shape = AtlasRadius.field,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear_desc))
                    }
                }
            },
            singleLine = true,
        )
    }
    val filterButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        FilledTonalButton(
            onClick = onFilterClick,
            modifier = buttonModifier.heightIn(min = AtlasSpace.action),
            shape = AtlasRadius.field,
        ) {
            Text(
                if (activeFilterCount > 0) stringResource(R.string.filter_with_count_format, activeFilterCount)
                else stringResource(R.string.filter),
            )
        }
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background, shape = AtlasRadius.field) {
        BoxWithConstraints {
            if (maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    searchField(Modifier.fillMaxWidth())
                    filterButton(Modifier.align(Alignment.End))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                    searchField(Modifier.weight(1f))
                    filterButton(Modifier)
                }
            }
        }
    }
}

/**
 * 單列結果卡。
 * @param dimNonFree true 時非免費列以低層級表面下沉，維持文字對比。
 * @param trailingContent 下沉樣式預留 slot（後續 Compare / 監看入口由主 AI 掛載）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelRowCard(
    row: ModelRow,
    dimNonFree: Boolean = true,
    pending: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) = AtlasTheme {
    val dimmed = shouldDimRow(row, dimNonFree)
    AtlasCard(modifier = modifier, subdued = dimmed, onClick = onClick) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
            PriceBadge(row.priceState)
            verificationLabel(row.verified)?.let { AtlasBadge(it, AtlasTone.ACCENT) }
            if (pending) AtlasBadge(stringResource(R.string.provider_detail_candidate), AtlasTone.CAUTION)
        }
        Text(
            text = modelDisplayName(row),
            style = MaterialTheme.typography.titleMedium,
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (row.displayName?.isNotBlank() == true && row.displayName != row.canonicalId) {
            Text(text = row.canonicalId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.aliasNames.isNotEmpty()) {
            Text(
                text = stringResource(R.string.alias_prefix) + row.aliasNames.joinToString(" / "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
            AtlasBadge(row.primaryCategory?.let { stringResource(modelCategoryLabel(it)) } ?: stringResource(R.string.category_unknown))
            AtlasBadge(stringResource(R.string.label_quota_prefix) + stringResource(quotaDisplayLabel(row.quotaState)))
        }
        // @Composable 不可在非 composable lambda（joinToString）內呼叫：先解析再 join。
        val capabilitiesText = row.capabilities.takeIf { it.isNotEmpty() }
            ?.map { stringResource(capabilityLabel(it)) }?.joinToString(" / ")
            ?: stringResource(R.string.value_unknown)
        AtlasFact(stringResource(R.string.label_capabilities), capabilitiesText)
        AtlasMetrics(
            listOf(
                stringResource(R.string.label_input_price) to priceValueText(row.inputPrice, stringResource(R.string.value_unknown)),
                stringResource(R.string.label_output_price) to priceValueText(row.outputPrice, stringResource(R.string.value_unknown)),
                stringResource(R.string.label_cache_price) to priceValueText(row.cachePrice, stringResource(R.string.value_unknown)),
                stringResource(R.string.label_group) to (row.group?.takeIf { it.isNotBlank() } ?: stringResource(R.string.value_unknown)),
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
            Text(stringResource(R.string.source_prefix) + sourceDisplayLabel(row.sourceLabel, stringResource(R.string.value_unknown)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.selected_key_prefix) + (if (row.selectedKeyUsable) stringResource(R.string.key_usable) else stringResource(R.string.key_unusable)), style = MaterialTheme.typography.bodySmall)
        }
        if (trailingContent != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            trailingContent()
        }
    }
}

@Composable
internal fun PriceBadge(priceState: PriceState) {
    // F18：四態各配一色，PARTIALLY_FREE 與 UNKNOWN 不得與 PAID 共用底色。
    AtlasBadge(stringResource(priceBadgeText(priceState)), when (priceState) {
        PriceState.FREE_NOW -> AtlasTone.ACCENT
        PriceState.PAID -> AtlasTone.INFO
        PriceState.PARTIALLY_FREE -> AtlasTone.CAUTION
        PriceState.UNKNOWN -> AtlasTone.NEUTRAL
    })
}

/** 結果清單；空集合顯示 [ModelSearchEmpty]（不明示崩潰、不造假資料）。 */
@Composable
fun ModelSearchResults(
    rows: List<RankedRow>,
    onRowClick: (ModelRow) -> Unit = {},
    dimNonFree: Boolean = true,
    hasActiveFilter: Boolean = false,
    trailingContent: (@Composable (ModelRow) -> Unit)? = null,
    pendingCandidateKeys: Set<MyModelKey> = emptySet(),
    modifier: Modifier = Modifier,
    resultsCapped: Boolean = false,
) = AtlasTheme {
    if (rows.isEmpty()) {
        ModelSearchEmpty(hasActiveFilter = hasActiveFilter, modifier = modifier)
        return@AtlasTheme
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(AtlasSpace.md),
    ) {
        // F16：同一 provider＋canonical 下可能有多個 offering（不同 endpoint/operation），
        // 舊 key（canonicalId@providerId）會重複而 crash；ModelRow 無 offeringId 可用，
        // 故 key 納入 providerModelId 區分並以 index 尾綴保證唯一。
        itemsIndexed(
            rows,
            key = { index, ranked ->
                ranked.row.providerId + "/" + ranked.row.providerModelId + "/" +
                    ranked.row.canonicalId + "/" + index
            },
        ) { _, ranked ->
            val pending = MyModelKey(ranked.row.providerId, ranked.row.providerModelId) in pendingCandidateKeys
            ModelRowCard(
                row = ranked.row,
                dimNonFree = dimNonFree,
                pending = pending,
                onClick = { onRowClick(ranked.row) },
                trailingContent = trailingContent?.let { slot -> { slot(ranked.row) } },
            )
        }
        if (resultsCapped) {
            item {
                Text(
                    text = stringResource(R.string.my_models_cap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 空狀態：一律明示「尚無資料」，不渲染假列。
 * [action] 為動作按鈕 slot（例：放寬篩選 / 清除搜尋）；null 即不顯示。
 */
@Composable
fun ModelSearchEmpty(
    hasActiveFilter: Boolean = false,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) = AtlasTheme {
    AtlasCard(modifier.padding(vertical = AtlasSpace.lg), subdued = true) {
        AtlasEmptyStateCard(
            title = stringResource(searchEmptyText(hasActiveFilter)),
            description = if (hasActiveFilter) stringResource(R.string.models_search_empty_desc_filter) else stringResource(R.string.models_search_empty_desc),
            icon = Icons.Filled.Search,
            action = action,
        )
    }
}
