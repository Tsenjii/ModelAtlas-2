package dev.modelatlas.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.ProviderModelActionState
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.PriceState

/**
 * Provider Detail 畫面（PROV-001 repair）。
 *
 * Spec：`PRODUCT_SPEC.md` §4（Provider card／Detail：Overview／Models／Performance／
 * History；Models FREE NOW 優先、非免費 dim＋下沉；不要常駐空欄位）。
 * TRACEABILITY：PROV-001。
 *
 * - 純狀態提升：本畫面不讀 DB、不碰 ViewModel／Repository；所有資料由掛載端
 *   （主 AI 接 `AtlasScreen` 掛載點）映射傳入，見各 state 的「掛載映射」註記。
 * - 動作全部回調：`onSync／onTestNow／onAddModel／onCancelAction／onSelectConnection／
 *   onOpenCheckin／onCompleteCheckin`；`onTestNow／onAddModel` 為 nullable：null＝掛載端
 *   尚無產品路徑，對應動作 disabled 並顯示 `provider_detail_actions_unavailable`，
 *   本畫面不另起旁路。已選連線不在本 provider 或不可用時，掛載端不接動作。
 * - 單一 action 狀態（PROV-001／MODEL-006）：`state.action` 是唯一進行中／結果來源，
 *   busy 時新增／測試全部 disabled，只留取消；draft 只在成功新增（`clearToken` 遞增）
 *   清空，失敗／取消保留輸入。
 * - Unknown 明示：缺資料顯示「未知」，不以 0／免費／空列冒充；Overview 只渲染
 *   有資料的段落（無常駐空欄位）。
 * - Secret 邊界：只顯示 safe metadata（label／group／enabled），絕無原始值。
 *
 * 掛載映射（主 AI 接線用）：
 * - header：`ConnectionRecord` 同 provider 首列（name／baseUrl／officialHealth／
 *   observedHealth／checkinUrl／requiresDailyCheckin）＋聚合（credential 數、
 *   FREE NOW 數、`DedicatedSyncFlow` 的 syncing／lastSynced）。
 * - endpoints：`EndpointEntity`（endpointType／url／protocol／enabled）。
 * - accounts：`CredentialEntity`＋`AccountEntity`（label／group／enabled／usable／
 *   last success／failure；僅 safe metadata）。
 * - models：`OfferingEntity` 逐 offering（modelId／priceState／manual／hidden）＋
 *   **選定 scope 的驗證狀態**（`verified` 只代表目前選取的 key／endpoint／group 上
 *   有真實成功 sample；provider 層級的 evidence 不足以標已驗證，見
 *   `ProviderDetailLoader.modelsOf`）。
 * - performance：本地 telemetry 摘要（ttft／tps／latency／success；缺值 null）。
 * - history：price／quota／outage／recovery 事件列（一次 sync failure 不抹 last-good，
 *   事件只追加展示）。
 */

/** Detail 四分頁（順序固定；掛載端不得增減）。 */
enum class ProviderDetailTab { OVERVIEW, MODELS, PERFORMANCE, HISTORY }

/** Header：單一 provider（掛載映射見檔頭 KDoc）。 */
data class ProviderDetailHeader(
    val providerId: String,
    val name: String,
    val baseUrl: String,
    val websiteUrl: String? = null,
    val adapterType: String = "UNKNOWN",
    val officialHealth: String = HealthState.UNKNOWN.name,
    val observedHealth: String = HealthState.UNKNOWN.name,
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val credentialCount: Int = 0,
    val freeNowCount: Int? = null,
    val checkinUrl: String? = null,
    val requiresDailyCheckin: Boolean = false,
    val checkinPending: Boolean? = null,
    val lastSyncedLabel: String? = null,
    val syncing: Boolean = false,
)

/** 端點列（`EndpointEntity` 投影；url 只展示，不可編輯）。 */
data class ProviderDetailEndpoint(
    val endpointId: String,
    val endpointType: String,
    val url: String,
    val protocol: String,
    val enabled: Boolean = true,
)

/** 真實帳號 identity；憑證各自保留 metadata，不將 credentialId 冒充 accountId。 */
data class ProviderDetailAccount(
    val accountId: String,
    val credentials: List<ProviderDetailCredential> = emptyList(),
)

/** 帳號下的憑證列（safe metadata only；絕無原始值）。 */
data class ProviderDetailCredential(
    val credentialId: String,
    val label: String,
    val group: String? = null,
    val enabled: Boolean = true,
    val usableModels: Int? = null,
    val lastSuccessLabel: String? = null,
    val lastFailureLabel: String? = null,
)

/**
 * 模型列（`ConnectionRecord` 逐 offering 投影）。
 *
 * @param verified 僅代表「**目前選取的** 金鑰／endpoint／group 上已有真實成功紀錄」
 *   （`ProviderDetailLoader` 以精確 scope 的 persisted sample 判定），不是 provider 層級
 *   事實：換一把 key 不會沿用另一把 key 的驗證（§6 不同 Credential 資料不互相覆蓋）。
 * @param hidden hidden/manual candidate：已持久化但尚未驗證，畫面只標示待測試，
 *   不得當成可用 offering 宣稱。
 */
data class ProviderDetailModel(
    val modelId: String,
    val priceState: String = PriceState.UNKNOWN.name,
    val verified: Boolean = false,
    val manual: Boolean = false,
    val hidden: Boolean = false,
)

/** Overview 段落（全 null／空＝未知；畫面只渲染有資料者）。 */
data class ProviderDetailOverview(
    val description: String? = null,
    val freeMechanism: String? = null,
    val registration: String? = null,
    val regionLimits: String? = null,
    val verificationLimits: String? = null,
    val stabilityNote: String? = null,
    val notices: List<String> = emptyList(),
    val userNotes: String? = null,
    val assistantSummary: String? = null,
)

/** 效能摘要（全 null＝未知；未知不得當成 0）。 */
data class ProviderDetailPerformance(
    val tps: Double? = null,
    val ttftMs: Long? = null,
    val latencyMs: Long? = null,
    val success1h: Double? = null,
    val success24h: Double? = null,
    val requestCount: Long? = null,
)

/** 歷史事件列（展示用；只追加、不改寫 last-good 語意）。 */
data class ProviderDetailHistoryEvent(
    val observedAtLabel: String,
    val section: String,
    val stateLabel: String,
    val sourceLabel: String = "",
    val statusLabel: String = "",
    val scopeLabel: String = "",
)

/** Detail 畫面狀態（掛載端組裝；tab 由掛載端持有，經 [onTab] 切換）。 */
data class ProviderDetailState(
    val header: ProviderDetailHeader,
    val endpoints: List<ProviderDetailEndpoint> = emptyList(),
    val accounts: List<ProviderDetailAccount> = emptyList(),
    val overview: ProviderDetailOverview = ProviderDetailOverview(),
    val models: List<ProviderDetailModel> = emptyList(),
    val performance: ProviderDetailPerformance? = null,
    val history: List<ProviderDetailHistoryEvent> = emptyList(),
    val selectedTab: ProviderDetailTab = ProviderDetailTab.OVERVIEW,
    /** 正在 TestNow 的 modelId（spinner／禁用同列按鈕；null＝無進行中）。 */
    val testingModelId: String? = null,
    /**
     * PROV-001／MODEL-006 模型動作單一狀態（VM `ProviderModelActionState` 透傳）。
     * 只有 `providerId` 等於本 provider 的動作才會被掛載端傳入，別 provider 的
     * 訊息／token 不得出現在此頁。
     */
    val action: ProviderModelActionState = ProviderModelActionState(),
    /** 已選連線的安全 metadata；null＝尚未有可用連線（畫面顯示選擇指引，動作 disabled）。 */
    val actionScope: ProviderDetailActionScope? = null,
    /** 本 provider 可明確選取的連線；不自動選、不猜另一把 key。 */
    val connectionChoices: List<ProviderDetailConnectionChoice> = emptyList(),
    /** 上次載入失敗但仍顯示同 provider last-good；true 時畫面明示資料非最新。 */
    val staleLoad: Boolean = false,
    /**
     * 本次載入未取得可信結果（loader 遇未預期錯誤）。掛載端據此**保留**同 provider 的
     * last-good 並明示資料非最新，而不是用未知空殼覆蓋已顯示的真實資料。
     */
    val loadFailed: Boolean = false,
)

/**
 * 模型動作使用的已選連線（safe metadata only：id／名稱／憑證 label／group）。
 * 絕無原始 key／PAT；只供畫面明示「用哪一把」與 scope。
 */
data class ProviderDetailActionScope(
    val connectionId: String,
    val connectionName: String,
    val credentialLabel: String = "",
    val accountId: String? = null,
    val credentialId: String? = null,
    val group: String? = null,
    /** auth=NONE 的真實匿名連線：無憑證仍可測試（不捏造 credential）。 */
    val anonymous: Boolean = false,
)

/**
 * Detail 內可明確選取的連線（使用者主動點選才切換，不自動挑另一把 key）。
 *
 * 同一把 key 可能有多個 model 列；去重後以 [modelCount] 標示涵蓋數量，並顯示
 * credential label／model／endpoint／operation 讓使用者分得出差異。
 */
data class ProviderDetailConnectionChoice(
    val connectionId: String,
    val name: String,
    val credentialLabel: String = "",
    val modelId: String = "",
    val endpointId: String = "",
    val operation: String = "",
    val modelCount: Int = 1,
    val group: String? = null,
    val enabled: Boolean = true,
    val anonymous: Boolean = false,
)

/**
 * Models 排序（純函式、可單測）：FREE NOW 優先，其餘穩定下沉（dim＋下沉，§4）。
 * 同組內保持原順序（stable），不臆測次序。
 */
internal fun sortDetailModels(models: List<ProviderDetailModel>): List<ProviderDetailModel> {
    val (free, rest) = models.partition { it.priceState == PriceState.FREE_NOW.name }
    return free + rest
}

/** 四分頁固定順序（純函式、可單測）。 */
internal fun detailTabOrder(): List<ProviderDetailTab> = ProviderDetailTab.values().toList()

internal const val PROVIDER_MODEL_ACTION_ADD = "ADD"
internal const val PROVIDER_MODEL_ACTION_TEST = "TEST"
internal const val PROVIDER_DETAIL_MODEL_CONTENT_TYPE = "providerDetailModel"

/**
 * 純函式：這次新增模型是否該清掉 draft。
 *
 * 契約（PROV-001／MODEL-006）：draft **只在** VM 回報「成功新增模型」時清空，
 * 且以 [ProviderModelActionState.clearToken] 的一次性遞增為依據；點按鈕本身不清，
 * 失敗／取消一律保留輸入（與 `UI_CONTRACT` 表單清除同一原則：不以 error 通道判斷）。
 */
internal fun shouldClearModelDraft(
    previousClearToken: Int,
    action: ProviderModelActionState,
): Boolean =
    action.kind == PROVIDER_MODEL_ACTION_ADD &&
        action.status == "SUCCESS" &&
        action.clearToken != previousClearToken

/** 純函式：單一 action 狀態下是否已有進行中的模型動作（阻擋重複送出）。 */
internal fun providerModelActionBusy(action: ProviderModelActionState): Boolean = action.busy

/** 連線選擇清單的最大高度（超出改捲動，不讓按鈕被壓成不可點的小塊）。 */
private val CHOICE_LIST_MAX_HEIGHT = 320.dp

/**
 * 連線選擇列的辨識文字（純函式、可單測）：明示 credential／model／endpoint／操作，
 * 避免多把同名金鑰在看板上無法分辨。空白一律顯示未知，不以其他欄位冒充。
 */
@Suppress("LongParameterList")
internal fun connectionChoiceDetail(
    credentialLabel: String,
    modelId: String,
    modelCount: Int,
    endpointId: String,
    operation: String,
    anonymous: Boolean,
    unknown: String,
    anonymousLabel: String,
    credentialPrefix: String,
    modelPrefix: String,
    endpointPrefix: String,
    operationPrefix: String,
): String {
    val parts = ArrayList<String>(4)
    parts += credentialPrefix + credentialLabel.ifBlank {
        if (anonymous) anonymousLabel else unknown
    }
    val modelText = modelId.ifBlank { unknown }
    parts += modelPrefix + if (modelCount > 1) "$modelText（+$modelCount）" else modelText
    parts += endpointPrefix + endpointId.ifBlank { unknown }
    parts += operationPrefix + operation.ifBlank { unknown }
    return parts.joinToString("\n")
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderDetailScreen(
    state: ProviderDetailState,
    onBack: () -> Unit,
    onTab: (ProviderDetailTab) -> Unit,
    onSync: () -> Unit,
    onTestNow: ((modelId: String) -> Unit)?,
    onAddModel: ((modelId: String) -> Unit)?,
    onOpenCheckin: (url: String) -> Unit,
    onCompleteCheckin: (() -> Unit)?,
    onCancelAction: () -> Unit,
    onSelectConnection: ((connectionId: String) -> Unit)? = null,
    dedicatedOpState: OpState? = null,
    dedicatedSyncScopeLabel: String? = null,
    newApiOpState: OpState? = null,
    saving: Boolean = false,
    syncEnabled: Boolean = true,
    newApiSyncSupported: Boolean = false,
) = AtlasTheme {
    val unknown = stringResource(R.string.value_unknown)
    val syncingLabel = stringResource(R.string.provider_detail_syncing)
    val newApiLabel = stringResource(R.string.action_sync_new_api, stringResource(R.string.provider_detail_sync))
    val syncingNewApi = newApiOpState?.syncing ?: state.header.syncing
    var draft by remember(state.header.providerId) { mutableStateOf("") }
    // draft 只依 clearToken 的一次性成功回填清空；點按鈕、失敗、取消都不清。
    var seenClearToken by remember(state.header.providerId) { mutableIntStateOf(state.action.clearToken) }
    LaunchedEffect(state.action.clearToken, state.action.kind, state.action.status) {
        if (shouldClearModelDraft(seenClearToken, state.action)) draft = ""
        seenClearToken = state.action.clearToken
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(AtlasSpace.lg)) {
                    DetailActionGroup {
                        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action)) {
                            Text(stringResource(R.string.provider_detail_back))
                        }
                        // PK-12：capability 已解析為 true 才渲染 CTA（VM 快取同步供應——
                        // 無首幀閃失；未解析/不支援＝隱藏，維持單鈕版面與測試契約）。
                        if (newApiSyncSupported) {
                            Button(onClick = onSync, enabled = syncEnabled && !saving && !syncingNewApi,
                                modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action)
                                    .testTag("provider_detail_newapi_sync")
                                    .semantics {
                                        contentDescription = newApiLabel
                                        if (syncingNewApi) stateDescription = syncingLabel
                                    }) {
                                SyncOperationButtonContent(newApiLabel, syncingNewApi, syncingLabel)
                            }
                        }
                    }
                }
            }
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(AtlasSpace.lg),
            verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg),
        ) {
            item { DetailHeaderCard(state.header, unknown, state.staleLoad, onOpenCheckin, onCompleteCheckin) }
            if (newApiOpState != null) item {
                NewApiSyncStatusLines(newApiOpState, Modifier.testTag("provider_detail_newapi_status"))
            }
            if (dedicatedOpState != null && (dedicatedOpState.error != null ||
                dedicatedOpState.lastSynced != null || dedicatedOpState.latestObserved != null)) item {
                Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    dedicatedSyncScopeLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    DedicatedSyncStatusLines(dedicatedOpState, Modifier.testTag("provider_detail_dedicated_status"))
                }
            }
            stickyHeader {
                val tabs = detailTabOrder()
                ScrollableTabRow(
                    selectedTabIndex = tabs.indexOf(state.selectedTab),
                    containerColor = MaterialTheme.colorScheme.background,
                    edgePadding = 0.dp,
                ) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab == state.selectedTab,
                            onClick = { onTab(tab) },
                            modifier = Modifier.heightIn(min = AtlasSpace.action),
                            text = { Text(stringResource(detailTabLabel(tab)), style = MaterialTheme.typography.labelLarge, softWrap = false) },
                        )
                    }
                }
            }
            when (state.selectedTab) {
                ProviderDetailTab.OVERVIEW -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg)) {
                        if (state.endpoints.isNotEmpty()) DetailEndpointsCard(state.endpoints, unknown)
                        if (state.accounts.isNotEmpty()) DetailAccountsCard(state.accounts, unknown)
                        DetailOverviewTab(state.overview)
                    }
                }
                ProviderDetailTab.MODELS -> detailModelsTab(
                    state = state,
                    models = sortDetailModels(state.models),
                    draft = draft,
                    onDraft = { draft = it },
                    onAddModel = onAddModel,
                    onTestNow = onTestNow,
                    onCancelAction = onCancelAction,
                    onSelectConnection = onSelectConnection,
                )
                ProviderDetailTab.PERFORMANCE -> item { DetailPerformanceTab(state.performance) }
                ProviderDetailTab.HISTORY -> detailHistoryTab(state.history)
            }
        }
    }
}

private fun detailTabLabel(tab: ProviderDetailTab): Int = when (tab) {
    ProviderDetailTab.OVERVIEW -> R.string.provider_detail_overview
    ProviderDetailTab.MODELS -> R.string.provider_detail_models
    ProviderDetailTab.PERFORMANCE -> R.string.provider_detail_performance
    ProviderDetailTab.HISTORY -> R.string.provider_detail_history
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeaderCard(
    header: ProviderDetailHeader,
    unknown: String,
    staleLoad: Boolean,
    onOpenCheckin: (url: String) -> Unit,
    onCompleteCheckin: (() -> Unit)?,
) {
    val typeLabel = if (header.adapterType == "UNKNOWN") unknown else header.adapterType
    AtlasCard {
        Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
            AtlasBadge("PROVIDER", AtlasTone.ACCENT)
            DetailIdentityText(
                value = header.name.ifBlank { unknown },
                label = stringResource(R.string.label_provider),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
            )
            DetailIdentityText(
                value = header.baseUrl,
                label = stringResource(R.string.label_base_url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            header.websiteUrl?.takeIf { it.isNotBlank() }?.let {
                DetailIdentityText(
                    value = stringResource(R.string.provider_detail_website, it),
                    label = stringResource(R.string.provider_detail_website_label),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                AtlasBadge(typeLabel)
                PriceBadge(comparePriceStateOf(header.priceState))
                AtlasBadge(stringResource(R.string.label_quota_prefix) + stringResource(quotaDisplayLabel(compareQuotaStateOf(header.quotaState))))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 官方健康與觀測健康各自獨立（不得混為同一欄），計數另成一組；
            // AtlasMetrics 會在窄容器／大字自動退回單欄，不截斷數字。
            AtlasMetrics(listOf(
                stringResource(R.string.label_official_health) to (if (header.officialHealth == "UNKNOWN") unknown else header.officialHealth),
                stringResource(R.string.label_local_health) to (if (header.observedHealth == "UNKNOWN") unknown else header.observedHealth),
            ))
            AtlasMetrics(listOf(
                stringResource(R.string.label_credential) to header.credentialCount.toString(),
                stringResource(R.string.label_free_now_models) to (header.freeNowCount?.toString() ?: unknown),
            ))
            header.lastSyncedLabel?.let {
                Text(stringResource(R.string.provider_detail_last_synced, it), style = MaterialTheme.typography.bodySmall)
            }
            if (header.requiresDailyCheckin && header.checkinUrl != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (header.checkinPending == true) AtlasBadge(stringResource(R.string.provider_detail_checkin_pending), AtlasTone.CAUTION)
                // checkinUrl 先收攏為本地 val：跨 lambda 捕捉安全，smart cast 可進 onClick。
                val checkinUrl = header.checkinUrl
                DetailActionGroup {
                    OutlinedButton(
                        onClick = { onOpenCheckin(checkinUrl) },
                        modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action),
                    ) { Text(stringResource(R.string.action_open_checkin)) }
                    if (onCompleteCheckin != null) {
                        OutlinedButton(
                            onClick = onCompleteCheckin,
                            modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.action),
                        ) { Text(stringResource(R.string.action_complete_checkin)) }
                    }
                }
            }
            if (header.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            // 一次載入失敗不清同 provider 的 last-good；明示目前不是最新讀取結果。
            if (staleLoad) {
                AtlasBadge(stringResource(R.string.provider_detail_stale_data), AtlasTone.CAUTION)
            }
        }
    }
}

@Composable
private fun DetailEndpointsCard(endpoints: List<ProviderDetailEndpoint>, unknown: String) {
    AtlasCard {
        Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg)) {
            AtlasSectionHeading(stringResource(R.string.provider_detail_endpoints))
            endpoints.forEachIndexed { index, endpoint ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    Text(
                        endpoint.endpointType.ifBlank { unknown } + " · " + endpoint.protocol.ifBlank { unknown },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    // URL 可能很長：只省略視覺行數，完整原文可點開捲動選取。
                    DetailIdentityText(
                        value = endpoint.url.ifBlank { unknown },
                        label = stringResource(R.string.advanced_endpoint_url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!endpoint.enabled) Text(stringResource(R.string.provider_detail_disabled))
                }
            }
        }
    }
}

@Composable
private fun DetailAccountsCard(accounts: List<ProviderDetailAccount>, unknown: String) {
    AtlasCard {
        Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg)) {
            AtlasSectionHeading(stringResource(R.string.provider_detail_accounts))
            accounts.forEachIndexed { index, account ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                key(account.accountId) {
                    Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
                        // AccountEntity 沒有顯示名稱：只展示真實 safe accountId，不借憑證 label。
                        DetailIdentityText(
                            value = stringResource(R.string.playground_account) + " · " + account.accountId.ifBlank { unknown },
                            label = stringResource(R.string.playground_account),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(start = AtlasSpace.md),
                            verticalArrangement = Arrangement.spacedBy(AtlasSpace.md),
                        ) {
                            account.credentials.forEachIndexed { childIndex, credential ->
                                if (childIndex > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                key(credential.credentialId) {
                                    Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                                        DetailIdentityText(
                                            value = credential.label.ifBlank { unknown },
                                            label = stringResource(R.string.label_credential),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        credential.group?.takeIf { it.isNotBlank() }?.let {
                                            DetailIdentityText(
                                                value = stringResource(R.string.provider_detail_group, it),
                                                label = stringResource(R.string.label_group),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (!credential.enabled) Text(stringResource(R.string.provider_detail_disabled))
                                        credential.usableModels?.let {
                                            Text(stringResource(R.string.provider_detail_usable_models, it), style = MaterialTheme.typography.bodySmall)
                                        }
                                        credential.lastSuccessLabel?.let {
                                            Text(stringResource(R.string.provider_detail_last_success, it), style = MaterialTheme.typography.bodySmall)
                                        }
                                        credential.lastFailureLabel?.let {
                                            Text(stringResource(R.string.provider_detail_last_failure, it), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailOverviewTab(overview: ProviderDetailOverview) {
    val sections = buildList {
        overview.description?.takeIf { it.isNotBlank() }?.let { add(R.string.provider_detail_description to it) }
        overview.freeMechanism?.takeIf { it.isNotBlank() }?.let { add(R.string.provider_detail_free_mechanism to it) }
        overview.registration?.takeIf { it.isNotBlank() }?.let { add(R.string.provider_detail_registration to it) }
        overview.regionLimits?.takeIf { it.isNotBlank() }?.let { add(R.string.provider_detail_region_limits to it) }
        overview.verificationLimits?.takeIf { it.isNotBlank() }?.let { add(R.string.provider_detail_verification_limits to it) }
        overview.stabilityNote?.takeIf { it.isNotBlank() }?.let { add(R.string.provider_detail_stability to it) }
        if (overview.notices.isNotEmpty()) add(R.string.provider_detail_notices to overview.notices.joinToString("\n"))
        overview.userNotes?.takeIf { it.isNotBlank() }?.let { add(R.string.provider_detail_user_notes to it) }
        overview.assistantSummary?.takeIf { it.isNotBlank() }?.let { add(R.string.provider_detail_assistant_summary to it) }
    }
    if (sections.isEmpty()) {
        DetailEmptyState(stringResource(R.string.provider_detail_overview_empty), stringResource(R.string.provider_detail_overview_body), Icons.Filled.Info)
        return
    }
    sections.forEach { (label, body) ->
        AtlasCard {
            Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                AtlasSectionHeading(stringResource(label))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Models 分頁（PROV-001 §4 Models ／ MODEL-006 §8 manual model id）。
 *
 * - 先顯示「動作會用哪一把連線」：已選 scope 或明確的連線選擇清單；沒有可用連線時
 *   顯示選擇指引並保持所有動作 disabled（不猜另一把 key、不自動挑）。
 * - 單一 action 狀態：進行中時新增／測試全部 disabled，只留取消。
 * - 失敗／取消保留 draft；只有成功新增（clearToken 遞增）才清空。
 * - hidden candidate 顯示為待測試，不當成可用 offering 宣稱。
 */
private fun LazyListScope.detailModelsTab(
    state: ProviderDetailState,
    models: List<ProviderDetailModel>,
    draft: String,
    onDraft: (String) -> Unit,
    onAddModel: ((modelId: String) -> Unit)?,
    onTestNow: ((modelId: String) -> Unit)?,
    onCancelAction: () -> Unit,
    onSelectConnection: ((connectionId: String) -> Unit)?,
) {
    val action = state.action
    val busy = providerModelActionBusy(action)
    val scopeReady = state.actionScope != null
    val testingModelId = action.modelId.takeIf { busy && action.kind == PROVIDER_MODEL_ACTION_TEST }

    item {
        DetailActionScopeCard(
            state = state,
            busy = busy,
            onSelectConnection = onSelectConnection,
        )
    }
    item {
        AtlasCard {
            AtlasSectionHeading(stringResource(R.string.provider_detail_add_models_heading), stringResource(R.string.provider_detail_add_models_desc))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                label = { Text(stringResource(R.string.provider_detail_model_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                shape = AtlasRadius.field,
            )
            if (onAddModel == null) {
                Text(
                    stringResource(if (scopeReady) R.string.provider_detail_actions_unavailable
                        else R.string.provider_detail_action_scope_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { draft.trim().takeIf { it.isNotEmpty() }?.let { id -> onAddModel?.invoke(id) } },
                enabled = onAddModel != null && draft.trim().isNotEmpty() && !busy && scopeReady,
                modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.action),
            ) {
                Text(
                    stringResource(
                        if (busy && action.kind == PROVIDER_MODEL_ACTION_ADD) {
                            R.string.provider_detail_adding_model
                        } else {
                            R.string.provider_detail_add_model
                        },
                    ),
                )
            }
        }
    }
    if (action.status != "IDLE") {
        item { DetailActionStatusCard(action = action, onCancelAction = onCancelAction) }
    }
    if (models.isEmpty()) {
        item { DetailEmptyState(stringResource(R.string.provider_detail_no_models), stringResource(R.string.provider_detail_no_models_body), Icons.Filled.List) }
        return
    }
    items(
        items = models,
        key = { it.modelId },
        contentType = { PROVIDER_DETAIL_MODEL_CONTENT_TYPE },
    ) { model ->
        val free = model.priceState == PriceState.FREE_NOW.name
        AtlasCard(subdued = !free) {
            Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
                DetailIdentityText(
                    value = model.modelId,
                    label = stringResource(R.string.label_model),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (free) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PriceBadge(comparePriceStateOf(model.priceState))
                Text(
                    stringResource(
                        if (model.verified) R.string.provider_detail_verified
                        else R.string.provider_detail_unverified,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (model.manual) Text(stringResource(R.string.provider_detail_manual), style = MaterialTheme.typography.bodySmall)
                // hidden candidate：已持久化但未驗證，只標示待測試，不宣稱可用。
                if (model.hidden) AtlasBadge(stringResource(R.string.provider_detail_candidate), AtlasTone.CAUTION)
                val testing = testingModelId == model.modelId
                if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (onTestNow == null) {
                    Text(
                        stringResource(if (scopeReady) R.string.provider_detail_actions_unavailable
                            else R.string.provider_detail_action_scope_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { onTestNow?.invoke(model.modelId) },
                    enabled = onTestNow != null && !busy && scopeReady,
                    modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch),
                ) {
                    Text(
                        stringResource(
                            if (testing) R.string.provider_detail_testing
                            else R.string.provider_detail_test_now,
                        ),
                    )
                }
            }
        }
    }
}

/** 動作範圍卡：明示使用中的連線／key／group；無可用連線時顯示選擇指引。 */
@Composable
private fun DetailActionScopeCard(
    state: ProviderDetailState,
    busy: Boolean,
    onSelectConnection: ((connectionId: String) -> Unit)?,
) {
    val scope = state.actionScope
    val unknownLabel = stringResource(R.string.value_unknown)
    val anonymousChoiceLabel = stringResource(R.string.provider_detail_action_scope_anonymous_label)
    val credentialPrefix = stringResource(R.string.provider_detail_choice_credential_prefix)
    val modelPrefix = stringResource(R.string.provider_detail_choice_model_prefix)
    val endpointPrefix = stringResource(R.string.provider_detail_choice_endpoint_prefix)
    val operationPrefix = stringResource(R.string.provider_detail_choice_operation_prefix)
    AtlasCard {
        Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
            AtlasSectionHeading(
                stringResource(R.string.provider_detail_action_scope_title),
                stringResource(R.string.provider_detail_action_scope_desc),
            )
            if (scope != null) {
                DetailIdentityText(
                    value = scope.connectionName,
                    label = stringResource(R.string.provider_detail_action_scope_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        R.string.provider_detail_action_scope_credential,
                        scope.credentialLabel.ifBlank {
                            stringResource(R.string.provider_detail_action_scope_anonymous_label)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                scope.group?.takeIf { it.isNotBlank() }?.let { group ->
                    Text(
                        stringResource(R.string.provider_detail_group, group),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (scope.anonymous) {
                    Text(
                        stringResource(R.string.provider_detail_action_scope_anonymous),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.provider_detail_action_scope_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.connectionChoices.isNotEmpty() && onSelectConnection != null) {
                // 同一 provider 可能有很多 key／model 列：改用可捲動直欄，每列顯示
                // credential／model／endpoint／operation 差異，不再壓成一排等寬小按鈕。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = CHOICE_LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
                ) {
                    state.connectionChoices.forEach { choice ->
                        val isCurrent = choice.connectionId == scope?.connectionId
                        OutlinedButton(
                            onClick = { onSelectConnection(choice.connectionId) },
                            enabled = !busy && choice.enabled && !isCurrent,
                            modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch),
                            shape = AtlasRadius.field,
                            contentPadding = PaddingValues(AtlasSpace.md),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.provider_detail_action_use_connection, choice.name),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = connectionChoiceDetail(
                                        credentialLabel = choice.credentialLabel,
                                        modelId = choice.modelId,
                                        modelCount = choice.modelCount,
                                        endpointId = choice.endpointId,
                                        operation = choice.operation,
                                        anonymous = choice.anonymous,
                                        unknown = unknownLabel,
                                        anonymousLabel = anonymousChoiceLabel,
                                        credentialPrefix = credentialPrefix,
                                        modelPrefix = modelPrefix,
                                        endpointPrefix = endpointPrefix,
                                        operationPrefix = operationPrefix,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isCurrent) {
                                    Text(
                                        text = stringResource(R.string.status_selected),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (!choice.enabled) {
                                    Text(
                                        text = stringResource(R.string.provider_detail_disabled),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 進行中／結果卡：spinner、safe 結果訊息與取消（不顯示 URL、key 或伺服器 body）。 */
@Composable
private fun DetailActionStatusCard(
    action: ProviderModelActionState,
    onCancelAction: () -> Unit,
) {
    val unknown = stringResource(R.string.value_unknown)
    AtlasCard {
        Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
            AtlasSectionHeading(stringResource(R.string.provider_detail_action_status_title))
            if (action.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val kindLabel = if (action.kind == PROVIDER_MODEL_ACTION_TEST) {
                stringResource(R.string.provider_detail_test_now)
            } else {
                stringResource(R.string.provider_detail_add_model)
            }
            Text(
                stringResource(R.string.provider_detail_action_status_line, kindLabel, action.status),
                style = MaterialTheme.typography.bodyMedium,
            )
            // message 由 VM 以本地安全字串提供；null／空白時明示未知，不自補詳細內容。
            Text(
                action.message?.takeIf { it.isNotBlank() } ?: unknown,
                style = MaterialTheme.typography.bodySmall,
                color = if (action.status == "FAILURE") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (action.busy) {
                OutlinedButton(
                    onClick = onCancelAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch),
                ) { Text(stringResource(R.string.provider_detail_action_cancel)) }
            }
        }
    }
}

@Composable
private fun DetailPerformanceTab(performance: ProviderDetailPerformance?) {
    if (performance == null) {
        DetailEmptyState(stringResource(R.string.provider_detail_no_performance), stringResource(R.string.provider_detail_no_performance_body), Icons.Filled.Info)
        return
    }
    val unknown = stringResource(R.string.value_unknown)
    AtlasCard {
        AtlasSectionHeading(stringResource(R.string.provider_detail_performance), stringResource(R.string.provider_detail_performance_desc))
        AtlasMetrics(listOf(
            stringResource(R.string.label_tps) to (performance.tps?.toString() ?: unknown),
            stringResource(R.string.label_ttft) to (performance.ttftMs?.let { "$it ms" } ?: unknown),
            stringResource(R.string.label_total_latency) to (performance.latencyMs?.let { "$it ms" } ?: unknown),
            stringResource(R.string.label_request_count) to (performance.requestCount?.toString() ?: unknown),
            stringResource(R.string.label_success_1h) to (ProviderDetailLoader.successRatePercentLabel(performance.success1h) ?: unknown),
            stringResource(R.string.label_success_24h) to (ProviderDetailLoader.successRatePercentLabel(performance.success24h) ?: unknown),
        ))
    }
}

/** Full evidence presentation identity, with a stable occurrence number for identical events. */
internal fun providerHistoryKeys(history: List<ProviderDetailHistoryEvent>): List<String> {
    val occurrences = HashMap<String, Int>()
    return history.map { event ->
        val identity = listOf(event.observedAtLabel, event.section, event.stateLabel,
            event.sourceLabel, event.statusLabel, event.scopeLabel)
            .joinToString("") { "${it.length}:$it" }
        val occurrence = occurrences[identity] ?: 0
        occurrences[identity] = occurrence + 1
        "$identity|$occurrence"
    }
}

private fun LazyListScope.detailHistoryTab(history: List<ProviderDetailHistoryEvent>) {
    if (history.isEmpty()) {
        item { DetailEmptyState(stringResource(R.string.provider_detail_no_history), stringResource(R.string.provider_detail_no_history_body), Icons.Filled.DateRange) }
        return
    }
    val keys = providerHistoryKeys(history)
    itemsIndexed(history, key = { index, _ -> keys[index] }) { _, event ->
        AtlasCard {
            Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                Text(event.observedAtLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(event.section, style = MaterialTheme.typography.titleSmall)
                Text(event.stateLabel, style = MaterialTheme.typography.bodyMedium)
                if (event.statusLabel.isNotBlank()) Text(event.statusLabel, style = MaterialTheme.typography.bodySmall)
                if (event.scopeLabel.isNotBlank()) Text(
                    event.scopeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (event.sourceLabel.isNotBlank()) AtlasBadge(event.sourceLabel)
            }
        }
    }
}
