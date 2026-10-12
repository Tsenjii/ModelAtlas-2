package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.models.CompareLiveRowResult
import java.util.Locale

/**
 * P6 session J：MODEL-008 Compare Live 結果對照表 UI。
 *
 * TRACEABILITY：MODEL-008（PRODUCT_SPEC §8 結果對照、§10 FREE 判定、§2 空狀態明示）。
 * 對應 app/ui/ModelSearchUi.kt 的徽章／空態慣例（唯讀引用，不改既有檔）。
 *
 * - 只吃傳入的 [CompareLiveRowResult] 列表；不讀 DB、不做 entity 映射、不碰 key。
 * - 價格徽章只吃 row.priceState（經 [comparePriceStateOf] fail-closed 轉 PriceState）：
 *   UNKNOWN 顯示「未知」，禁 $0／免費字樣；絕不依 model id 推導。
 * - 指標 null＝未知（telemetry 無該值），一律顯示「未知」，禁 0。
 * - 來源字串不在本表顯示 quota 歸因猜測；quota 欄只呈現 quotaState（經 [compareQuotaStateOf]
 *   fail-closed 轉 QuotaState，文字共用 [detailQuotaBadgeText]）＋rate-limit 標頭有無。
 */

/** 字串 PriceState fail-closed 轉 enum；未知字串回 UNKNOWN（永不回 FREE）。 */
fun comparePriceStateOf(raw: String): PriceState = when (raw.trim().uppercase()) {
    "FREE_NOW" -> PriceState.FREE_NOW
    "PAID" -> PriceState.PAID
    "PARTIALLY_FREE" -> PriceState.PARTIALLY_FREE
    else -> PriceState.UNKNOWN
}

/** 結果標籤：成功顯示成功；失敗顯示 outcome 名（不含遠端訊息）。 */
@Composable
fun compareOutcomeText(row: CompareLiveRowResult): String =
    if (row.success) stringResource(R.string.compare_success) else stringResource(R.string.compare_failed_format, row.outcome.name)

/** 指標文字：null 一律「未知」，永不顯示 0 冒充。 */
fun compareMetricText(value: Long?, unit: String, unknown: String): String =
    if (value == null) unknown else "$value$unit"

/** TPS 文字：null 一律「未知」。 */
fun compareTpsText(tps: Double?, unknown: String): String =
    if (tps == null) unknown else String.format(Locale.ROOT, "%.1f tokens/s", tps)

/** 字串 QuotaState fail-closed 轉 enum；未知字串回 UNKNOWN（永不回可用配額）。 */
fun compareQuotaStateOf(raw: String): QuotaState = when (raw.trim().uppercase()) {
    "QUOTA_AVAILABLE" -> QuotaState.QUOTA_AVAILABLE
    "QUOTA_LOW" -> QuotaState.QUOTA_LOW
    "QUOTA_EXHAUSTED" -> QuotaState.QUOTA_EXHAUSTED
    "RATE_LIMIT_ONLY" -> QuotaState.RATE_LIMIT_ONLY
    "PUBLIC_POOL" -> QuotaState.PUBLIC_POOL
    "UNLIMITED_CLAIMED" -> QuotaState.UNLIMITED_CLAIMED
    "NO_KNOWN_CONSUMABLE_QUOTA" -> QuotaState.NO_KNOWN_CONSUMABLE_QUOTA
    else -> QuotaState.UNKNOWN
}

/**
 * Quota 欄文字：只呈現白名單內的 state（文字共用 detailQuotaBadgeText，未知一律「未知」，
 * 不原樣顯示 enum／原始字串）；rate-limit 標頭僅標示有無，不猜剩餘量。
 */
@Composable
fun compareQuotaText(row: CompareLiveRowResult): String {
    val base = stringResource(detailQuotaBadgeText(compareQuotaStateOf(row.quotaState)))
    return if (row.rateLimitHeaders.isEmpty()) base else base + stringResource(R.string.compare_rate_limit_suffix)
}

/** 空狀態文字（列表與 UI 測試共用）。 */
@StringRes
fun compareEmptyText(hasOfferings: Boolean): Int =
    if (hasOfferings) R.string.compare_empty_no_results else R.string.compare_empty_no_offerings

/**
 * P6 repair worker B：比測結果摘要（成功／失敗計數；空列回 ""，呼叫端顯示空態）。
 * 只吃傳入 rows 的 success 旗標，不讀遠端訊息。
 */
@Composable
fun compareSummaryText(rows: List<CompareLiveRowResult>): String =
    CompareLiveWiring.summaryText(rows, stringResource(R.string.compare_summary_format))

/**
 * P6 repair worker B：頂層錯誤分類文字（只顯示分類，不含 URL／body／secret）。
 * null＝無錯誤，不渲染。
 */
@StringRes
fun compareErrorText(code: String?): Int? = when (code) {
    null -> null
    "CANCELLED" -> R.string.compare_status_cancelled
    "AUTH" -> R.string.compare_fail_auth
    "NETWORK" -> R.string.compare_fail_network
    // 可比 offering 不足是狀態而非故障；不得顯示成通用「比測失敗」誤導使用者。
    "NO_COMPARABLE_OFFERINGS" -> R.string.compare_no_comparable_offerings
    else -> R.string.compare_fail_generic
}

/** 長識別字只限制視覺行數；整列可點開、捲動與複製完整值，TalkBack 仍讀原文。 */
@Composable
private fun CompareIdentity(
    value: String,
    label: String,
    style: TextStyle,
) {
    var showFullValue by remember(value) { mutableStateOf(false) }
    TextButton(
        onClick = { showFullValue = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch),
        shape = AtlasRadius.field,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(AtlasSpace.sm),
    ) {
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = null,
            modifier = Modifier.size(AtlasSpace.icon),
        )
    }
    if (showFullValue) {
        AlertDialog(
            onDismissRequest = { showFullValue = false },
            title = { Text(label) },
            text = {
                SelectionContainer {
                    Text(
                        text = value,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = COMPARE_ROWS_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showFullValue = false },
                    modifier = Modifier.heightIn(min = AtlasSpace.touch),
                ) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/**
 * 逐列結果卡：同一 canonical 下各 offering 的 TTFT／TPS／latency／成功／錯誤／
 * quota／PriceState 對照。未知欄位明示「未知」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompareLiveRowCard(
    row: CompareLiveRowResult,
    modifier: Modifier = Modifier,
) = AtlasTheme {
    AtlasCard(modifier) {
        CompareIdentity(
            value = row.providerModelId,
            label = stringResource(R.string.label_model),
            style = MaterialTheme.typography.titleMedium,
        )
        AtlasFact(stringResource(R.string.label_provider), row.providerId)
        // 這些是唯讀狀態徽章；集合換行而不截斷成功／錯誤與價格文字。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
            verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
        ) {
            AtlasBadge(compareOutcomeText(row), if (row.success) AtlasTone.ACCENT else AtlasTone.ERROR)
            PriceBadge(comparePriceStateOf(row.priceState))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AtlasMetrics(
            listOf(
                stringResource(R.string.label_ttft) to compareMetricText(row.ttftMs, " ms", stringResource(R.string.value_unknown)),
                stringResource(R.string.label_tps) to compareTpsText(row.tps, stringResource(R.string.value_unknown)),
            ),
        )
        AtlasFact(
            stringResource(R.string.label_total_latency),
            compareMetricText(row.totalLatencyMs, " ms", stringResource(R.string.value_unknown)),
            Modifier.fillMaxWidth(),
        )
        AtlasFact(stringResource(R.string.label_quota_short), compareQuotaText(row), Modifier.fillMaxWidth())
        if (!row.success && row.httpStatus != null) {
            Text("HTTP ${row.httpStatus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 結果列表有界高度：LazyColumn 置於 Column 內若無界高度，實機會 crash。 */
private val COMPARE_ROWS_MAX_HEIGHT = 320.dp

/**
 * 對照表。空 rows 時顯示空態（未知明示，不留白表讓人誤判為全成功）。
 *
 * 插入點建議（主 AI 接線）：Model detail（MODEL-009）頁內的「Compare Live」區塊；
 * [onRunClick] 觸發 ViewModel.compareLive（ViewModel 經 Repository 取 selected-key
 * 下同 canonical offerings → 組 CompareLiveRequest → 跑 CompareLiveRunner.run）；
 * running 時按鈕 disabled 並顯示「比測中」。
 */
@Composable
fun CompareLiveTable(
    canonicalId: String,
    rows: List<CompareLiveRowResult>,
    onRunClick: () -> Unit,
    running: Boolean = false,
    hasOfferings: Boolean = false,
    error: String? = null,
    onCancelClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) = AtlasTheme {
    AtlasCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
            AtlasSectionHeading(stringResource(R.string.compare_title))
            CompareIdentity(
                value = canonicalId,
                label = stringResource(R.string.label_model),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(stringResource(R.string.compare_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        val summary = compareSummaryText(rows)
        if (summary.isNotEmpty()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        compareErrorText(error)?.let {
            AtlasBadge(stringResource(it), AtlasTone.ERROR, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (rows.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                shape = AtlasRadius.field,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                AtlasEmptyStateCard(
                    title = if (running) stringResource(R.string.compare_running_title) else stringResource(compareEmptyText(hasOfferings)),
                    description = if (running) stringResource(R.string.compare_running_desc)
                    else if (hasOfferings) stringResource(R.string.compare_ready_desc) else stringResource(R.string.compare_choose_desc),
                    icon = Icons.Filled.List,
                    compact = true,
                    // 動作按鈕由本卡底部「執行比測 / 取消」區負責，空態不重複按鈕。
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = COMPARE_ROWS_MAX_HEIGHT),
                contentPadding = PaddingValues(vertical = AtlasSpace.xs),
                verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg),
            ) {
                // PK-29：多 key 比測下同 offeringId 可出現兩列（scoped comparisonId 唯一），
                // key 用 runner 保證唯一的 comparisonId（legacy 單 key 時 == offeringId，行為不變）。
                items(rows, key = { it.comparisonId }) { row ->
                    CompareLiveRowCard(row = row)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // 全寬直排讓窄螢幕／大字不必擠兩個按鈕，執行中把可操作的取消放在最下方。
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
            Button(
                onClick = onRunClick,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.action),
            ) {
                Text(
                    if (running) stringResource(R.string.compare_running_button) else stringResource(R.string.compare_run_button),
                    textAlign = TextAlign.Center,
                )
            }
            if (running) {
                OutlinedButton(
                    onClick = onCancelClick,
                    modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.action),
                ) {
                    Text(stringResource(R.string.action_cancel), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
