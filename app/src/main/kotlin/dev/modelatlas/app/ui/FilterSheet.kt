@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.Modality
import dev.modelatlas.core.domain.ModelCategory

/**
 * P6 worker B：Filter Sheet UI（Compose ModalBottomSheet）。
 *
 * TRACEABILITY：MODEL-005（Filter Sheet）。對應 PRODUCT_SPEC §8（篩選清單每一項）、
 * §2（Collect everything, personalize display；Unknown 明示）。
 *
 * - 狀態 hoist：[FilterState] ＋ onChange 回呼；本 Sheet 不持有篩選真相。
 * - 未實作資料來源的區段：顯示標題 ＋「尚無資料」＋ disabled 控制，不隱藏該區段。
 * - 字串暫為硬編碼繁中；接線時主 AI 搬入 values/strings.xml（本 worker 不可改既有檔）。
 */

private const val MILLIS_PER_DAY = 24L * 3600L * 1000L

/** UI 層篩選狀態（hoisted）。布林 false / null / 空集合 = 該維度不過濾。 */
data class FilterState(
    val freeNowOnly: Boolean = false,
    val minInputPrice: Double? = null,
    val maxInputPrice: Double? = null,
    val minOutputPrice: Double? = null,
    val maxOutputPrice: Double? = null,
    val minCachePrice: Double? = null,
    val maxCachePrice: Double? = null,
    /** 0..1；null = 不過濾。 */
    val minSuccess1h: Double? = null,
    /** 0..1；null = 不過濾。 */
    val minSuccess24h: Double? = null,
    val minTps: Double? = null,
    val maxTtftMs: Long? = null,
    val maxLatencyMs: Long? = null,
    val minContextTokens: Long? = null,
    val groups: Set<String> = emptySet(),
    val providerIds: Set<String> = emptySet(),
    val vendors: Set<String> = emptySet(),
    val primaryCategories: Set<ModelCategory> = emptySet(),
    val capabilities: Set<Capability> = emptySet(),
    val capabilityMatchAll: Boolean = false,
    val modalities: Set<Modality> = emptySet(),
    val requireTools: Boolean = false,
    val requireVision: Boolean = false,
    val requireFiles: Boolean = false,
    val requireReasoning: Boolean = false,
    /** 最近 N 天；null = 不過濾。 */
    val addedWithinDays: Int? = null,
    val updatedWithinDays: Int? = null,
    val priceChangedWithinDays: Int? = null,
    val minPriceChanges: Int? = null,
    val minFreeUptimeHours: Double? = null,
    val onlyFavorites: Boolean = false,
    val onlyRecent: Boolean = false,
    val onlyWatched: Boolean = false,
    val usableOnly: Boolean = false,
)

/**
 * 各篩選區段的資料可用性。null 清單 / false = 該區段顯示「尚無資料」disabled。
 * 接線時主 AI 依實際資料來源（MODEL-003/006/007 落地進度）逐項開啟。
 */
data class FilterAvailability(
    val hasPriceData: Boolean = false,
    val hasTelemetryData: Boolean = false,
    val hasContextData: Boolean = false,
    val providers: List<String>? = null,
    val groups: List<String>? = null,
    val vendors: List<String>? = null,
    val hasClassificationData: Boolean = false,
    val hasTimestampData: Boolean = false,
    val hasPriceHistoryData: Boolean = false,
    val hasFreeUptimeData: Boolean = false,
    val hasCollectionData: Boolean = false,
    val hasKeyAvailabilityData: Boolean = false,
)

/** [FilterState] → 邏輯層 [ModelFilter]。時間預設（天）以 nowMs 換算為 epoch 閉區間起點。 */
fun FilterState.toModelFilter(nowMs: Long = System.currentTimeMillis()): ModelFilter {
    fun windowStart(days: Int?): Long? = days?.let { nowMs - it * MILLIS_PER_DAY }
    return ModelFilter(
        freeNowOnly = freeNowOnly.takeIf { it },
        minInputPrice = minInputPrice,
        maxInputPrice = maxInputPrice,
        minOutputPrice = minOutputPrice,
        maxOutputPrice = maxOutputPrice,
        minCachePrice = minCachePrice,
        maxCachePrice = maxCachePrice,
        minSuccess1h = minSuccess1h,
        minSuccess24h = minSuccess24h,
        minTps = minTps,
        maxTtftMs = maxTtftMs,
        maxLatencyMs = maxLatencyMs,
        minContextTokens = minContextTokens,
        groups = groups.takeIf { it.isNotEmpty() },
        providerIds = providerIds.takeIf { it.isNotEmpty() },
        vendors = vendors.takeIf { it.isNotEmpty() },
        primaryCategories = primaryCategories.takeIf { it.isNotEmpty() },
        capabilities = capabilities.takeIf { it.isNotEmpty() },
        capabilityMatchAll = capabilityMatchAll,
        modalities = modalities.takeIf { it.isNotEmpty() },
        requireTools = requireTools.takeIf { it },
        requireVision = requireVision.takeIf { it },
        requireFiles = requireFiles.takeIf { it },
        requireReasoning = requireReasoning.takeIf { it },
        addedAfter = windowStart(addedWithinDays),
        updatedAfter = windowStart(updatedWithinDays),
        priceChangedAfter = windowStart(priceChangedWithinDays),
        minPriceChanges = minPriceChanges,
        minFreeUptimeHours = minFreeUptimeHours,
        onlyFavorites = onlyFavorites.takeIf { it },
        onlyRecent = onlyRecent.takeIf { it },
        onlyWatched = onlyWatched.takeIf { it },
        usableOnly = usableOnly.takeIf { it },
    )
}

/** 啟用的篩選維度計數（篩選按鈕徽章用）。每個非預設欄位計 1。 */
fun activeFilterCount(state: FilterState): Int {
    var n = 0
    if (state.freeNowOnly) n++
    if (state.minInputPrice != null) n++
    if (state.maxInputPrice != null) n++
    if (state.minOutputPrice != null) n++
    if (state.maxOutputPrice != null) n++
    if (state.minCachePrice != null) n++
    if (state.maxCachePrice != null) n++
    if (state.minSuccess1h != null) n++
    if (state.minSuccess24h != null) n++
    if (state.minTps != null) n++
    if (state.maxTtftMs != null) n++
    if (state.maxLatencyMs != null) n++
    if (state.minContextTokens != null) n++
    if (state.groups.isNotEmpty()) n++
    if (state.providerIds.isNotEmpty()) n++
    if (state.vendors.isNotEmpty()) n++
    if (state.primaryCategories.isNotEmpty()) n++
    if (state.capabilities.isNotEmpty()) n++
    if (state.modalities.isNotEmpty()) n++
    if (state.requireTools) n++
    if (state.requireVision) n++
    if (state.requireFiles) n++
    if (state.requireReasoning) n++
    if (state.addedWithinDays != null) n++
    if (state.updatedWithinDays != null) n++
    if (state.priceChangedWithinDays != null) n++
    if (state.minPriceChanges != null) n++
    if (state.minFreeUptimeHours != null) n++
    if (state.onlyFavorites) n++
    if (state.onlyRecent) n++
    if (state.onlyWatched) n++
    if (state.usableOnly) n++
    return n
}

/** 主分類繁中標籤（F11：enum 名不得直出；exhaustive when，entries 增減即編譯提醒）。 */
@StringRes
fun modelCategoryLabel(category: ModelCategory): Int = when (category) {
    ModelCategory.CHAT_REASONING -> R.string.category_chat_reasoning
    ModelCategory.CODING -> R.string.category_coding
    ModelCategory.IMAGE -> R.string.category_image
    ModelCategory.AUDIO -> R.string.category_audio
    ModelCategory.EMBEDDING -> R.string.category_embedding
    ModelCategory.RERANK -> R.string.category_rerank
    ModelCategory.SAFETY -> R.string.category_safety
    ModelCategory.OTHER -> R.string.category_other
}

/** Capability 繁中標籤（F11：exhaustive when）。 */
@StringRes
fun capabilityLabel(capability: Capability): Int = when (capability) {
    Capability.REASONING -> R.string.capability_reasoning
    Capability.CODING -> R.string.capability_coding
    Capability.TOOL_CALLING -> R.string.capability_tool_calling
    Capability.FUNCTION_CALLING -> R.string.capability_function_calling
    Capability.STRUCTURED_OUTPUT -> R.string.capability_structured_output
    Capability.JSON_MODE -> R.string.capability_json_mode
    Capability.VISION -> R.string.capability_vision
    Capability.FILES -> R.string.capability_files
    Capability.IMAGE_GENERATION -> R.string.capability_image_generation
    Capability.IMAGE_EDITING -> R.string.capability_image_editing
    Capability.AUDIO_INPUT -> R.string.capability_audio_input
    Capability.AUDIO_OUTPUT -> R.string.capability_audio_output
    Capability.WEB_SEARCH -> R.string.capability_web_search
    Capability.COMPUTER_USE -> R.string.capability_computer_use
    Capability.LONG_CONTEXT -> R.string.capability_long_context
    Capability.EMBEDDING -> R.string.capability_embedding
    Capability.RERANK -> R.string.capability_rerank
    Capability.SAFETY -> R.string.capability_safety
    Capability.OTHER -> R.string.capability_other
}

/** Modality 繁中標籤（F11：exhaustive when）。 */
@StringRes
fun modalityLabel(modality: Modality): Int = when (modality) {
    Modality.TEXT_INPUT -> R.string.modality_text_input
    Modality.TEXT_OUTPUT -> R.string.modality_text_output
    Modality.IMAGE_INPUT -> R.string.modality_image_input
    Modality.IMAGE_OUTPUT -> R.string.modality_image_output
    Modality.AUDIO_INPUT -> R.string.modality_audio_input
    Modality.AUDIO_OUTPUT -> R.string.modality_audio_output
    Modality.FILE_INPUT -> R.string.modality_file_input
}

/**
 * Filter Sheet 本體。插入點建議（主 AI 接線）：AtlasScreen 的 ModelsTab 頂部工具列，
 * 與 [ModelSearchBar] 同列的篩選按鈕開啟；state 由主 AI 持有並同時餵給 filterModels。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelFilterSheet(
    state: FilterState,
    availability: FilterAvailability = FilterAvailability(),
    onChange: (FilterState) -> Unit,
    onDismissRequest: () -> Unit,
    onReset: () -> Unit = { onChange(FilterState()) },
    modifier: Modifier = Modifier,
) = AtlasTheme {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = AtlasRadius.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding(),
        ) {
            Column(Modifier.padding(horizontal = AtlasSpace.xl, vertical = AtlasSpace.sm)) {
                AtlasSectionHeading(stringResource(R.string.filter_sheet_heading), stringResource(R.string.filter_active_format, activeFilterCount(state)))
            }
            Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AtlasSpace.lg),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // FREE NOW：priceState 恆有值，永遠可用。
            SwitchRow(
                label = stringResource(R.string.filter_free_now_only),
                checked = state.freeNowOnly,
                onCheckedChange = { onChange(state.copy(freeNowOnly = it)) },
            )

            // 價格區間。
            if (availability.hasPriceData) {
                SectionTitle(stringResource(R.string.section_price_unit))
                NullableDoubleField(stringResource(R.string.price_input_min), state.minInputPrice, { onChange(state.copy(minInputPrice = it)) })
                NullableDoubleField(stringResource(R.string.price_input_max), state.maxInputPrice, { onChange(state.copy(maxInputPrice = it)) })
                NullableDoubleField(stringResource(R.string.price_output_min), state.minOutputPrice, { onChange(state.copy(minOutputPrice = it)) })
                NullableDoubleField(stringResource(R.string.price_output_max), state.maxOutputPrice, { onChange(state.copy(maxOutputPrice = it)) })
                NullableDoubleField(stringResource(R.string.price_cache_min), state.minCachePrice, { onChange(state.copy(minCachePrice = it)) })
                NullableDoubleField(stringResource(R.string.price_cache_max), state.maxCachePrice, { onChange(state.copy(maxCachePrice = it)) })
            } else {
                UnavailableBlock(stringResource(R.string.section_price_unit))
            }

            // 成功率。
            if (availability.hasTelemetryData) {
                SectionTitle(stringResource(R.string.section_success_rate))
                PercentSlider(stringResource(R.string.success_1h_min), state.minSuccess1h, { onChange(state.copy(minSuccess1h = it)) })
                PercentSlider(stringResource(R.string.success_24h_min), state.minSuccess24h, { onChange(state.copy(minSuccess24h = it)) })
                NullableDoubleField(stringResource(R.string.tps_min), state.minTps, { onChange(state.copy(minTps = it)) })
                NullableLongField(stringResource(R.string.ttft_max), state.maxTtftMs, { onChange(state.copy(maxTtftMs = it)) })
                NullableLongField(stringResource(R.string.latency_max), state.maxLatencyMs, { onChange(state.copy(maxLatencyMs = it)) })
            } else {
                UnavailableBlock(stringResource(R.string.unavailable_perf))
            }

            // Context。
            if (availability.hasContextData) {
                SectionTitle(stringResource(R.string.section_context))
                NullableLongField(stringResource(R.string.context_min), state.minContextTokens, { onChange(state.copy(minContextTokens = it)) })
            } else {
                UnavailableBlock(stringResource(R.string.section_context))
            }

            // Provider / Group / Vendor 選單。
            OptionSection(
                title = "Provider",
                options = availability.providers,
                selected = state.providerIds,
                onToggle = { id ->
                    val next = if (id in state.providerIds) state.providerIds - id else state.providerIds + id
                    onChange(state.copy(providerIds = next))
                },
            )
            OptionSection(
                title = "Group",
                options = availability.groups,
                selected = state.groups,
                onToggle = { id ->
                    val next = if (id in state.groups) state.groups - id else state.groups + id
                    onChange(state.copy(groups = next))
                },
            )
            OptionSection(
                title = "Vendor",
                options = availability.vendors,
                selected = state.vendors,
                onToggle = { id ->
                    val next = if (id in state.vendors) state.vendors - id else state.vendors + id
                    onChange(state.copy(vendors = next))
                },
            )

            // 分類：primary category / capabilities / modalities。
            if (availability.hasClassificationData) {
                SectionTitle(stringResource(R.string.section_category))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    ModelCategory.entries.forEach { category ->
                        FilterChip(
                            selected = category in state.primaryCategories,
                            onClick = {
                                val next = if (category in state.primaryCategories) {
                                    state.primaryCategories - category
                                } else {
                                    state.primaryCategories + category
                                }
                                onChange(state.copy(primaryCategories = next))
                            },
                            label = { Text(stringResource(modelCategoryLabel(category))) },
                            modifier = Modifier.heightIn(min = AtlasSpace.touch),
                        )
                    }
                }
                SectionTitle(stringResource(R.string.section_capabilities_any))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    FilterChip(
                        selected = !state.capabilityMatchAll,
                        onClick = { onChange(state.copy(capabilityMatchAll = false)) },
                        label = { Text(stringResource(R.string.filter_match_any)) },
                        modifier = Modifier.heightIn(min = AtlasSpace.touch),
                    )
                    FilterChip(
                        selected = state.capabilityMatchAll,
                        onClick = { onChange(state.copy(capabilityMatchAll = true)) },
                        label = { Text(stringResource(R.string.filter_match_all)) },
                        modifier = Modifier.heightIn(min = AtlasSpace.touch),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    Capability.entries.forEach { capability ->
                        FilterChip(
                            selected = capability in state.capabilities,
                            onClick = {
                                val next = if (capability in state.capabilities) {
                                    state.capabilities - capability
                                } else {
                                    state.capabilities + capability
                                }
                                onChange(state.copy(capabilities = next))
                            },
                            label = { Text(stringResource(capabilityLabel(capability))) },
                            modifier = Modifier.heightIn(min = AtlasSpace.touch),
                        )
                    }
                }
                SectionTitle(stringResource(R.string.section_modalities))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    Modality.entries.forEach { modality ->
                        FilterChip(
                            selected = modality in state.modalities,
                            onClick = {
                                val next = if (modality in state.modalities) {
                                    state.modalities - modality
                                } else {
                                    state.modalities + modality
                                }
                                onChange(state.copy(modalities = next))
                            },
                            label = { Text(stringResource(modalityLabel(modality))) },
                            modifier = Modifier.heightIn(min = AtlasSpace.touch),
                        )
                    }
                }
            } else {
                UnavailableBlock(stringResource(R.string.unavailable_category))
            }

            // Tools / Vision / Files / Reasoning。
            if (availability.hasClassificationData) {
                SectionTitle(stringResource(R.string.section_features))
                SwitchRow(stringResource(R.string.capability_tool_calling), state.requireTools, { onChange(state.copy(requireTools = it)) })
                SwitchRow(stringResource(R.string.capability_vision), state.requireVision, { onChange(state.copy(requireVision = it)) })
                SwitchRow(stringResource(R.string.capability_files), state.requireFiles, { onChange(state.copy(requireFiles = it)) })
                SwitchRow(stringResource(R.string.capability_reasoning), state.requireReasoning, { onChange(state.copy(requireReasoning = it)) })
            } else {
                UnavailableBlock(stringResource(R.string.unavailable_features))
            }

            // 新增 / 異動時間窗。
            if (availability.hasTimestampData) {
                DayWindowChips(stringResource(R.string.added_within), state.addedWithinDays, { onChange(state.copy(addedWithinDays = it)) })
                DayWindowChips(stringResource(R.string.updated_within), state.updatedWithinDays, { onChange(state.copy(updatedWithinDays = it)) })
            } else {
                UnavailableBlock(stringResource(R.string.unavailable_time))
            }

            // 價格波動。
            if (availability.hasPriceHistoryData) {
                DayWindowChips(
                    stringResource(R.string.price_change_time),
                    state.priceChangedWithinDays,
                    { onChange(state.copy(priceChangedWithinDays = it)) },
                )
                NullableIntField(stringResource(R.string.price_changes_min), state.minPriceChanges, { onChange(state.copy(minPriceChanges = it)) })
            } else {
                UnavailableBlock(stringResource(R.string.unavailable_price_changes))
            }

            // Free uptime。
            if (availability.hasFreeUptimeData) {
                SectionTitle(stringResource(R.string.section_free_persistence))
                NullableDoubleField(
                    stringResource(R.string.free_persistence_min),
                    state.minFreeUptimeHours,
                    { onChange(state.copy(minFreeUptimeHours = it)) },
                )
            } else {
                UnavailableBlock(stringResource(R.string.section_free_persistence))
            }

            // 收藏 / 最近 / 監看。
            if (availability.hasCollectionData) {
                SectionTitle(stringResource(R.string.section_personal))
                SwitchRow(stringResource(R.string.only_favorites), state.onlyFavorites, { onChange(state.copy(onlyFavorites = it)) })
                SwitchRow(stringResource(R.string.only_recent), state.onlyRecent, { onChange(state.copy(onlyRecent = it)) })
                SwitchRow(stringResource(R.string.only_watched), state.onlyWatched, { onChange(state.copy(onlyWatched = it)) })
            } else {
                UnavailableBlock(stringResource(R.string.unavailable_personal))
            }

            // Selected-key usable。
            if (availability.hasKeyAvailabilityData) {
                SwitchRow(
                    label = stringResource(R.string.only_key_usable),
                    checked = state.usableOnly,
                    onCheckedChange = { onChange(state.copy(usableOnly = it)) },
                )
            } else {
                UnavailableBlock(stringResource(R.string.unavailable_key_usable))
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(AtlasSpace.lg),
                    horizontalArrangement = Arrangement.spacedBy(AtlasSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onReset, modifier = Modifier.heightIn(min = AtlasSpace.action)) { Text(stringResource(R.string.action_reset)) }
                    Button(onClick = onDismissRequest, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action)) { Text(stringResource(R.string.filter_done)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.padding(top = AtlasSpace.md), verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AtlasSectionHeading(text)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.action)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = AtlasSpace.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

/** 無資料來源區段：標題＋「尚無資料」＋ disabled 控制，不隱藏。 */
@Composable
private fun UnavailableBlock(title: String) {
    Surface(shape = AtlasRadius.field, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AtlasSpace.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.models_empty_no_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = false, onCheckedChange = {}, enabled = false)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionSection(
    title: String,
    options: List<String>?,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (options == null) {
        UnavailableBlock(title)
        return
    }
    SectionTitle(title)
    if (options.isEmpty()) {
        AtlasEmptyStateCard(
            title = stringResource(R.string.models_empty_no_data),
            description = stringResource(R.string.filter_no_options_desc),
            icon = Icons.Filled.Info,
            compact = true,
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
        options.forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onToggle(option) },
                label = { Text(option) },
                modifier = Modifier.heightIn(min = AtlasSpace.touch),
            )
        }
    }
}

@Composable
private fun PercentSlider(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.filter_no_filter),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { onChange(null) },
                modifier = Modifier.heightIn(min = AtlasSpace.touch),
            ) {
                Text(stringResource(R.string.action_clear))
            }
        }
        Slider(
            value = (value ?: 0.0).toFloat().coerceIn(0f, 1f),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun NullableDoubleField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
    enabled: Boolean = true,
) {
    // F9：不以 value 為 remember key（小數點/負號等中間態 toDoubleOrNull 回 null
    // 即被重建清空）；僅當外部值真正變化且文字解析結果與新值不一致時同步。
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    var lastExternal by remember { mutableStateOf(value) }
    if (value != lastExternal) {
        if (text.toDoubleOrNull() != value) {
            text = value?.toString().orEmpty()
        }
        lastExternal = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.toDoubleOrNull())
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = AtlasRadius.field,
    )
}

@Composable
private fun NullableLongField(
    label: String,
    value: Long?,
    onChange: (Long?) -> Unit,
    enabled: Boolean = true,
) {
    // F9：同 NullableDoubleField，不以 value 為 remember key。
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    var lastExternal by remember { mutableStateOf(value) }
    if (value != lastExternal) {
        if (text.toLongOrNull() != value) {
            text = value?.toString().orEmpty()
        }
        lastExternal = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.toLongOrNull())
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = AtlasRadius.field,
    )
}

@Composable
private fun NullableIntField(
    label: String,
    value: Int?,
    onChange: (Int?) -> Unit,
    enabled: Boolean = true,
) {
    // F9：同 NullableDoubleField，不以 value 為 remember key。
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    var lastExternal by remember { mutableStateOf(value) }
    if (value != lastExternal) {
        if (text.toIntOrNull() != value) {
            text = value?.toString().orEmpty()
        }
        lastExternal = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.toIntOrNull())
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = AtlasRadius.field,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayWindowChips(
    label: String,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    SectionTitle(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
        DayOption(null, stringResource(R.string.filter_no_filter), selected, onSelect)
        DayOption(1, stringResource(R.string.filter_recent_1d), selected, onSelect)
        DayOption(7, stringResource(R.string.filter_recent_7d), selected, onSelect)
        DayOption(30, stringResource(R.string.filter_recent_30d), selected, onSelect)
    }
}

@Composable
private fun DayOption(
    days: Int?,
    text: String,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    FilterChip(
        selected = selected == days,
        onClick = { onSelect(days) },
        label = { Text(text) },
        modifier = Modifier.heightIn(min = AtlasSpace.touch),
    )
}
