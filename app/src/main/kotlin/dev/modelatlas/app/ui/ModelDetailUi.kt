package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState

/**
 * P6 session L2：MODEL-009 model detail UI（價格區／配額區／歷史區）。
 *
 * TRACEABILITY：MODEL-009（price／quota／history model detail）。
 * 對應 PRODUCT_SPEC §4（價格／配額呈現）、§10（歷史／證據可追溯）、§11（未知明示）。
 *
 * - 全部走傳入的 [ModelDetailState]，本檔不讀 DB、不做 entity 映射。
 * - FREE 徽章只吃 [ModelDetailState.priceState]（[detailPriceBadgeText]），絕不依
 *   名稱含 free／quota／performance 推論免費。
 * - 非免費一律 dim＋下沉（[shouldDimDetail]）；UNKNOWN 顯示「未知」，禁 $0／免費字樣。
 * - 來源標籤透傳（manual／official／observed／external），空白＝未知，不互冒。
 * - 字串暫為硬編碼繁中；接線時主 AI 搬入 values/strings.xml（本 worker 不可改既有檔）。
 *
 * 插入點建議（主 AI 接線）：AtlasScreen ModelsTab 列點選 → Detail route，
 * 以接線層（snapshot_heads／facts → DetailPricePoint／DetailQuotaPool）餵入
 * [ModelDetailScreen]；Compare Live 區塊掛於歷史區下方（見 CompareLiveUi 插入點建議）。
 */

/** 價格徽章文字：只吃 priceState；UNKNOWN 一律「未知」。 */
@StringRes
fun detailPriceBadgeText(priceState: PriceState): Int = when (priceState) {
    PriceState.FREE_NOW -> R.string.price_free_now
    PriceState.PAID -> R.string.price_paid
    PriceState.PARTIALLY_FREE -> R.string.price_partially_free
    PriceState.UNKNOWN -> R.string.value_unknown
}

/** 配額徽章文字：UNKNOWN 不得當作可用。 */
@StringRes
fun detailQuotaBadgeText(quotaState: QuotaState): Int = when (quotaState) {
    QuotaState.QUOTA_AVAILABLE -> R.string.quota_available
    QuotaState.QUOTA_LOW -> R.string.quota_low
    QuotaState.QUOTA_EXHAUSTED -> R.string.quota_exhausted_detail
    QuotaState.RATE_LIMIT_ONLY -> R.string.quota_rate_limit_only
    QuotaState.PUBLIC_POOL -> R.string.quota_public_pool
    QuotaState.UNLIMITED_CLAIMED -> R.string.quota_unlimited_claimed
    QuotaState.NO_KNOWN_CONSUMABLE_QUOTA -> R.string.quota_none_detail
    QuotaState.UNKNOWN -> R.string.value_unknown
}

/** 非免費 detail 是否 dim（預設 dim；FREE NOW 才不 dim）。 */
fun shouldDimDetail(state: ModelDetailState, dimNonFree: Boolean = true): Boolean =
    dimNonFree && state.priceState != PriceState.FREE_NOW

/** 價格數值列：null＝未知（明示，不填 0）。 */
fun detailPriceLineText(label: String, value: Double?, format: String, unknown: String): String =
    if (value == null) String.format(format, label, unknown) else String.format(format, label, value.toString())

/** 配額整數列：null＝未知（明示，不填 0）。 */
fun detailQuotaLongText(label: String, value: Long?, format: String, unknown: String): String =
    if (value == null) String.format(format, label, unknown) else String.format(format, label, value.toString())

/** 效能數值列：null＝未知（明示，不填 0）。 */
fun detailPerfDoubleText(label: String, value: Double?, format: String, unknown: String): String =
    if (value == null) String.format(format, label, unknown) else String.format(format, label, value.toString())

fun detailPerfLongText(label: String, value: Long?, format: String, unknown: String): String =
    if (value == null) String.format(format, label, unknown) else String.format(format, label, value.toString())

/** 來源標籤透傳；空白＝未知。 */
fun detailSourceLabel(sourceLabel: String, unknown: String): String =
    if (sourceLabel.isBlank()) unknown else sourceLabel

/** Detail 空狀態文字（列表與 UI 測試共用）。 */
@StringRes
fun modelDetailEmptyText(hasHistory: Boolean): Int =
    if (hasHistory) R.string.models_empty_no_data_relax else R.string.models_empty_no_data

/** Detail 標題；未提供顯示名時退回 canonicalId（不拿別名冒充）。 */
fun modelDetailTitle(state: ModelDetailState): String =
    state.displayName?.takeIf { it.isNotBlank() } ?: state.canonicalId

/** 窄屏只省略識別字的視覺顯示；完整原文仍可點開、捲動及選取。 */
@Composable
internal fun DetailIdentityText(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1,
) {
    var showValue by remember(value) { mutableStateOf(false) }
    TextButton(
        onClick = { showValue = true },
        modifier = modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch),
        shape = AtlasRadius.field,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(AtlasSpace.sm),
    ) {
        Text(value, Modifier.weight(1f), style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(AtlasSpace.icon))
    }
    if (showValue) DetailValueDialog(value, label) { showValue = false }
}

@Composable
internal fun DetailValueDialog(value: String, label: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            SelectionContainer {
                Text(
                    value,
                    Modifier.fillMaxWidth().heightIn(max = HISTORY_LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = AtlasSpace.touch)) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

/** Detail／Onboarding 共用空態外觀；標題與說明仍由各自的真實狀態決定。 */
@Composable
internal fun DetailEmptyState(title: String, description: String, icon: ImageVector) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AtlasRadius.field,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AtlasEmptyStateCard(title, description, icon, compact = true)
    }
}

/** 使用局部可用寬度判斷；大字／窄容器改單欄，不截斷操作文字。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailActionGroup(content: @Composable FlowRowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) 1 else 2
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
            verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
            content = content,
        )
    }
}

@Composable
fun ModelDetailPriceSection(
    state: ModelDetailState,
    modifier: Modifier = Modifier,
) = AtlasTheme {
    AtlasCard(modifier = modifier) {
            AtlasSectionHeading(stringResource(R.string.section_price), stringResource(R.string.price_latest_desc))
            PriceBadge(state.priceState)
            val latest = state.selectedQuote
            AtlasMetrics(listOf(
                stringResource(R.string.label_input_short) to priceValueText(latest?.inputPrice, stringResource(R.string.value_unknown)),
                stringResource(R.string.label_output_short) to priceValueText(latest?.outputPrice, stringResource(R.string.value_unknown)),
                stringResource(R.string.label_cache_short) to priceValueText(latest?.cachePrice, stringResource(R.string.value_unknown)),
            ))
            val changeText = if (state.priceChangeCount == null) stringResource(R.string.price_change_unknown)
            else stringResource(R.string.price_change_format, state.priceChangeCount)
            Text(changeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ModelDetailQuotaSection(
    state: ModelDetailState,
    modifier: Modifier = Modifier,
) = AtlasTheme {
    AtlasCard(modifier = modifier) {
            AtlasSectionHeading(stringResource(R.string.section_quota), stringResource(R.string.quota_pools_desc))
            AtlasBadge(stringResource(detailQuotaBadgeText(state.quotaState)))
            if (state.quotaPools.isEmpty()) {
                DetailEmptyState(stringResource(R.string.quota_pool_unknown), stringResource(R.string.quota_pool_unknown_desc), Icons.Filled.Info)
            } else {
                for (pool in state.quotaPools) {
                    Surface(shape = AtlasRadius.field, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(AtlasSpace.md), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                            Text(pool.kind.name, style = MaterialTheme.typography.labelLarge)
                            val unknown = stringResource(R.string.value_unknown)
                            DetailIdentityText(
                                value = stringResource(
                                    R.string.quota_pool_scope_format,
                                    pool.providerId.ifBlank { unknown },
                                    pool.accountId?.takeIf { it.isNotBlank() } ?: unknown,
                                    pool.credentialRef?.takeIf { it.isNotBlank() } ?: unknown,
                                    pool.modelId.ifBlank { unknown },
                                    pool.group?.takeIf { it.isNotBlank() } ?: unknown,
                                    pool.endpointId.ifBlank { unknown },
                                    pool.operation.ifBlank { unknown },
                                ),
                                label = stringResource(R.string.quota_pool_scope_label),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                            )
                            AtlasBadge(stringResource(detailQuotaBadgeText(pool.state)))
                            Text(detailQuotaLongText(stringResource(R.string.label_remaining), pool.remainingRaw, stringResource(R.string.detail_value_format), stringResource(R.string.value_unknown)), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
    }
}

/** 歷史區列表有界高度：LazyColumn 置於 Card 的 Column 內若無界高度，實機會 crash。 */
private val HISTORY_LIST_MAX_HEIGHT = 320.dp

/** 歷史事件穩定唯一鍵：observedAt／section／stateLabel／statusLabel／sourceLabel 全欄位 + 同值序號。 */
internal fun detailHistoryKeys(events: List<DetailHistoryEvent>): List<String> {
    val seen = HashMap<String, Int>()
    return events.map { event ->
        val base = listOf(
            event.observedAt.toString(),
            event.section,
            event.stateLabel,
            event.statusLabel,
            event.sourceLabel,
        ).joinToString(KEY_SEPARATOR)
        val occurrence = seen.getOrDefault(base, 0)
        seen[base] = occurrence + 1
        base + KEY_SEPARATOR + occurrence
    }
}

private const val KEY_SEPARATOR = "\u0000"

@Composable
fun ModelDetailHistorySection(
    state: ModelDetailState,
    modifier: Modifier = Modifier,
) = AtlasTheme {
    AtlasCard(modifier = modifier) {
            AtlasSectionHeading(stringResource(R.string.section_history), stringResource(R.string.history_desc))
            if (state.history.isEmpty()) {
                DetailEmptyState(stringResource(modelDetailEmptyText(false)), stringResource(R.string.history_empty_desc), Icons.Filled.DateRange)
            } else {
                val historyKeys = remember(state.history) { detailHistoryKeys(state.history) }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = HISTORY_LIST_MAX_HEIGHT),
                    contentPadding = PaddingValues(vertical = AtlasSpace.xs),
                    verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg),
                ) {
                    itemsIndexed(
                        items = state.history,
                        key = { index, _ -> historyKeys[index] },
                    ) { _, event ->
                        DetailHistoryRow(event)
                    }
                }
            }
    }
}

@Composable
fun ModelDetailScreen(
    state: ModelDetailState,
    modifier: Modifier = Modifier,
    dimNonFree: Boolean = true,
) = AtlasTheme {
    // 外層 Detail route 已提供頁面留白，這裡不再疊加左右 padding。
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg)) {
        AtlasCard(subdued = shouldDimDetail(state, dimNonFree)) {
            Text(stringResource(R.string.model_detail_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            DetailIdentityText(
                modelDetailTitle(state),
                stringResource(R.string.model_detail_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (modelDetailTitle(state) != state.canonicalId) {
                DetailIdentityText(
                    state.canonicalId,
                    stringResource(R.string.label_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ModelDetailPriceSection(state)
        ModelDetailQuotaSection(state)
        ModelDetailHistorySection(state)
    }
}

/**
 * FX2 來源分欄修復（rv1 external/local 混合 FAIL）：
 * 原 [splitDetailHistory] 只比對字串 "external"，把 official／manual／observed／未知 ""
 * 全塞進標題「本地觀測」，manual／official／observed 混為一桶且標題失真。
 * 現以 [DetailProvenance] enum＋白名單三分欄（官方／本地／外部），不自造「本地」類別冒充官方：
 * - OFFICIAL＝"official" 精確匹配（大小寫正規化，空白 trim）；
 * - EXTERNAL＝"external" 精確匹配；
 * - LOCAL＝"manual"／"observed"／未知（""／白名單外皆 fail-closed 歸本地，永不冒充外部）。
 * 未知來源歸本地（不冒充外部），與 ModelDetailLoader 白名單同語意。
 */
enum class DetailProvenance {
    OFFICIAL,
    LOCAL,
    EXTERNAL,
}

/** 來源標籤 → 分欄 enum（白名單；非 official／external 即本地，永不猜成外部）。 */
fun detailProvenanceOf(sourceLabel: String): DetailProvenance {
    val normalized = sourceLabel.trim().lowercase()
    return when (normalized) {
        "official" -> DetailProvenance.OFFICIAL
        "external" -> DetailProvenance.EXTERNAL
        "manual", "observed" -> DetailProvenance.LOCAL
        // 未知（""／白名單外）fail-closed 歸本地，不冒充外部。
        else -> DetailProvenance.LOCAL
    }
}

/**
 * 三分欄（官方／本地／外部；純函式，可單測）。
 * 回傳 Triple(official，local，external)，呼叫端按原值呈現，不自造類別。
 */
fun splitDetailHistoryByProvenance(
    events: List<DetailHistoryEvent>,
): Triple<List<DetailHistoryEvent>, List<DetailHistoryEvent>, List<DetailHistoryEvent>> {
    val official = ArrayList<DetailHistoryEvent>()
    val local = ArrayList<DetailHistoryEvent>()
    val external = ArrayList<DetailHistoryEvent>()
    for (event in events) {
        when (detailProvenanceOf(event.sourceLabel)) {
            DetailProvenance.OFFICIAL -> official.add(event)
            DetailProvenance.LOCAL -> local.add(event)
            DetailProvenance.EXTERNAL -> external.add(event)
        }
    }
    return Triple(official, local, external)
}

/**
 * 舊二分欄（legacy，僅供既有測試相容；產品 UI 改用 [splitDetailHistoryByProvenance]）。
 * local＝official＋local（ collapsed 視圖），external 精確匹配；未知永不進 external。
 */
@Deprecated(
    "改用 splitDetailHistoryByProvenance 三分欄；本函數 official 併入 local 僅為相容",
    ReplaceWith("splitDetailHistoryByProvenance(events)"),
)
fun splitDetailHistory(events: List<DetailHistoryEvent>): Pair<List<DetailHistoryEvent>, List<DetailHistoryEvent>> {
    val (official, local, external) = splitDetailHistoryByProvenance(events)
    return (official + local) to external
}

/** 分欄空態（列表與 UI 測試共用）。 */
@StringRes
fun detailProvenanceEmptyText(isExternal: Boolean): Int =
    if (isExternal) R.string.detail_no_external else R.string.detail_no_local

/** 三分欄空態（新 UI 用；未知不填零值）。 */
@StringRes
fun detailProvenanceEmptyTextFor(provenance: DetailProvenance): Int = when (provenance) {
    DetailProvenance.OFFICIAL -> R.string.detail_no_official
    DetailProvenance.LOCAL -> detailProvenanceEmptyText(false)
    DetailProvenance.EXTERNAL -> detailProvenanceEmptyText(true)
}

/**
 * FX2 三分欄區（接在 [ModelDetailScreen] 下方；取代舊二分欄）。
 * 只吃傳入的 [ModelDetailState.history]；三欄皆空即顯示空態，不畫假圖。
 * 官方／本地／外部分欄經 [splitDetailHistoryByProvenance]，沿用 sourceLabel 原值呈現。
 */
@Composable
fun ModelDetailProvenanceSection(
    state: ModelDetailState,
    modifier: Modifier = Modifier,
) = AtlasTheme {
    val (official, local, external) = remember(state.history) { splitDetailHistoryByProvenance(state.history) }
    AtlasCard(modifier = modifier) {
        AtlasSectionHeading(stringResource(R.string.section_observed_source), stringResource(R.string.observed_source_desc))
        DetailProvenanceGroup(stringResource(R.string.group_official), official, DetailProvenance.OFFICIAL)
        DetailProvenanceGroup(stringResource(R.string.group_local), local, DetailProvenance.LOCAL)
        DetailProvenanceGroup(stringResource(R.string.group_external), external, DetailProvenance.EXTERNAL)
    }
}

@Composable
private fun DetailProvenanceGroup(title: String, events: List<DetailHistoryEvent>, provenance: DetailProvenance) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        AtlasBadge(events.size.toString())
    }
    if (events.isEmpty()) {
        DetailEmptyState(stringResource(detailProvenanceEmptyTextFor(provenance)), stringResource(R.string.provenance_empty_desc), Icons.Filled.Info)
    } else {
        val keys = remember(events) { detailHistoryKeys(events) }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = HISTORY_LIST_MAX_HEIGHT),
            contentPadding = PaddingValues(vertical = AtlasSpace.xs),
            verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg),
        ) {
            itemsIndexed(events, key = { index, _ -> keys[index] }) { _, event -> DetailHistoryRow(event) }
        }
    }
}

@Composable
private fun DetailHistoryRow(event: DetailHistoryEvent) {
    Surface(shape = AtlasRadius.field, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(AtlasSpace.md), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
            Text(event.section, style = MaterialTheme.typography.labelLarge)
            Text(event.stateLabel, style = MaterialTheme.typography.bodyMedium)
            if (event.statusLabel.isNotBlank()) Text(event.statusLabel, style = MaterialTheme.typography.bodySmall)
            AtlasBadge(stringResource(R.string.source_prefix) + detailSourceLabel(event.sourceLabel, stringResource(R.string.value_unknown)))
        }
    }
}
