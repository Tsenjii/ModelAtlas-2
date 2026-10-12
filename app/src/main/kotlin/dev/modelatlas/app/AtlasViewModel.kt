package dev.modelatlas.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.CanonicalStore
import dev.modelatlas.app.data.CheckinEntity
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.app.data.buildPlaygroundInferenceTarget
import dev.modelatlas.app.data.CompareKeyChoice
import dev.modelatlas.app.data.CompareKeySelection
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.MyModelBundleResult
import dev.modelatlas.app.data.MyModelMetadataRetryPolicy
import dev.modelatlas.app.data.MyModelsStore
import dev.modelatlas.app.data.MyUptimeAggregator
import dev.modelatlas.app.data.MyUptimeObservedRates
import dev.modelatlas.app.data.MyUptimePreset
import dev.modelatlas.app.data.MyUptimeScope
import dev.modelatlas.app.data.MyUptimeSummary
import dev.modelatlas.app.data.NewApiSessionPassport
import dev.modelatlas.app.data.PassportSnapshot
import dev.modelatlas.app.data.ProviderPerfKind
import dev.modelatlas.app.data.RefreshAttemptEvent
import dev.modelatlas.app.data.RefreshAttemptObserver
import dev.modelatlas.app.data.RefreshOrigin
import dev.modelatlas.app.data.RefreshOutcome
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.app.data.SnapshotKind
import dev.modelatlas.app.data.customAuthCleanupRefs
import dev.modelatlas.app.data.parseScopeKey
import dev.modelatlas.app.data.toManualConfig
import dev.modelatlas.app.data.toSample
import dev.modelatlas.app.notifications.NotificationRule
import dev.modelatlas.app.ui.AliasRef
import dev.modelatlas.app.ui.AvailabilityRef
import dev.modelatlas.app.data.RoomCompareLiveSampleFeed
import dev.modelatlas.app.ui.CanonicalRef
import dev.modelatlas.app.ui.ClassificationRef
import dev.modelatlas.app.ui.DetailPerformance
import dev.modelatlas.app.ui.ModelDetailState
import dev.modelatlas.app.ui.ModelDetailStateBuilder
import dev.modelatlas.app.ui.ModelRow
import dev.modelatlas.app.ui.ModelRowMapper
import dev.modelatlas.app.ui.OfferingRef
import dev.modelatlas.app.ui.PriceQuotaHead
import dev.modelatlas.app.ui.SendStreamFlushController
import dev.modelatlas.core.domain.CheckinRecord
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.domain.canonicalIdFor
import dev.modelatlas.core.domain.isCheckinPending
import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.models.CompareLiveRowResult
import dev.modelatlas.core.models.CompareLiveRunner
import dev.modelatlas.core.models.CompareLiveOffering
import dev.modelatlas.app.ui.CompareLiveWiring
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialLocation
import dev.modelatlas.core.onboarding.CredentialRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/** F28：取消截斷標記（UI-only，不進 history；不捏造助理內容）。 */
const val CANCEL_TRUNCATION_MARK = "［已截斷］"

data class ConnectionItem(
    val id: String,
    val providerId: String,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val credentialLabel: String,
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val officialHealth: String = "UNKNOWN",
    val observedHealth: String = "UNKNOWN",
    val checkinUrl: String? = null,
    val checkinPending: Boolean = false,
    val accountId: String = "",
    val credentialId: String = "",
    val enabled: Boolean = true,
    /** P1：是否支援 New API 手動同步（有 ACCOUNT PAT 的 provider 才 true，UI 據此顯示同步按鈕）。 */
    val supportsSync: Boolean = false,
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
)

data class SampleItem(
    val id: String,
    val providerId: String,
    val modelId: String,
    val outcome: String,
    val startedAt: Long,
    val ttftMs: Long? = null,
    val ttfbMs: Long? = null,
    val latencyMs: Long? = null,
    val outputTokens: Long? = null,
    val tps: Double? = null,
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
)

data class AtlasUiState(
    val connections: List<ConnectionItem> = emptyList(),
    val managedConnections: List<ConnectionItem> = emptyList(),
    val selectedId: String? = null,
    val busy: Boolean = false,
    val saving: Boolean = false,
    /** P1 手動 New API 同步進行集合（providerId 級）：只由同步按鈕觸發，不做背景自動；失敗寫 `error`。 */
    val syncingProviders: Set<String> = emptySet(),
    /** Incremented on every successful save; the form clears inputs on change. Never carries secrets. */
    val formClearToken: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val samples: List<SampleItem> = emptyList(),
    val error: String? = null,
    val connectionsLoading: Boolean = true,
)

/** UI 契約的唯一 ViewModel：AndroidViewModel，依賴一律取自 AtlasApplication。 */
class AtlasViewModel @JvmOverloads constructor(
    application: Application,
    private val providerModelTestEngine: InferenceEngine? = null,
    private val compareTestEngine: InferenceEngine? = null,
    private val credentialEnableTestWriter: (suspend (String, Boolean) -> Unit)? = null,
) : AndroidViewModel(application) {
    private val app: AtlasApplication = application as AtlasApplication

    private fun string(id: Int): String = getApplication<Application>().getString(id)

    // X-013 / D-005: app-owned durable choices; no permission request or automatic opt-in.
    val notificationSettings get() = app.notificationSettings.state
    private val _notificationSettingsSaving = MutableStateFlow(false)
    val notificationSettingsSaving = _notificationSettingsSaving.asStateFlow()
    private val _notificationSettingsSaveFailed = MutableStateFlow(false)
    val notificationSettingsSaveFailed = _notificationSettingsSaveFailed.asStateFlow()

    fun setNotificationsEnabled(enabled: Boolean) = saveNotificationSettings {
        app.notificationSettings.setMasterEnabled(enabled)
    }

    fun setNotificationRuleEnabled(rule: NotificationRule, enabled: Boolean) = saveNotificationSettings {
        app.notificationSettings.setRuleEnabled(rule, enabled)
    }

    private fun saveNotificationSettings(write: suspend () -> Boolean) {
        if (_notificationSettingsSaving.value) return
        _notificationSettingsSaving.value = true
        _notificationSettingsSaveFailed.value = false
        viewModelScope.launch {
            try {
                _notificationSettingsSaveFailed.value = !write()
            } finally {
                _notificationSettingsSaving.value = false
            }
        }
    }

    private val _state = MutableStateFlow(AtlasUiState())
    val state: StateFlow<AtlasUiState> = _state.asStateFlow()

    private var sendJob: Job? = null
    private var readyCache: Boolean = false
    private var initErrorCache: Boolean = false
    private var connectionCache: List<ConnectionRecord> = emptyList()
    private var connectionsFirstLoaded: Boolean = false
    private var managementCache: List<ConnectionRecord> = emptyList()
    private var sampleCache: List<PerformanceSample> = emptyList()
    private var checkinCache: List<CheckinEntity> = emptyList()
    private val pendingById: MutableMap<String, ConnectionRecord> = mutableMapOf()
    private val historyByConnection: MutableMap<String, MutableList<PromptMessage>> = mutableMapOf()
    // Room Flow 可稍晚才送出變更；本機先封鎖整把 credential，不能只清單一 offering。
    private val blockedCredentialIds = mutableSetOf<String>()
    // P1：支援 New API sync 的 providerId 快取（有 PAT_ACCOUNT config 才視為支援，UI 據此顯示按鈕）。
    // 不做背景自動同步，只做按鈕可見性判定；載入失敗 fail-closed 視為不支援。
    private var syncSupportedCache: Set<String> = emptySet()
    // Remember evaluated negatives too: an unsupported provider is not a request to retry forever.
    private var syncEvaluatedCache: Set<String> = emptySet()
    private var syncSupportJob: Job? = null
    private var currentDate: LocalDate = LocalDate.now()
    private var dateReceiver: BroadcastReceiver? = null
    private var dateTicker: Job? = null

    // P6-C／INT-2 狀態必須早於 init 初始化（refresh() 由 init 收集器觸發，
    // 會經 refreshModelRows 預設參數讀 _selected*；晚初始化即
    // refreshModelRows$default MutableStateFlow.getValue on null NPE）。
    /** P6-C：Dedicated provider 級同步中集合（provider 級，可並行不同 provider）。 */
    private val _dedicatedSyncing = MutableStateFlow<Set<String>>(emptySet())
    val dedicatedSyncing: StateFlow<Set<String>> = _dedicatedSyncing.asStateFlow()

    /** P6-C：Dedicated provider 級錯誤分類（值只取 AUTH／NETWORK／UNSUPPORTED／UNKNOWN）。 */
    private val _dedicatedSyncError = MutableStateFlow<Map<String, String?>>(emptyMap())
    val dedicatedSyncError: StateFlow<Map<String, String?>> = _dedicatedSyncError.asStateFlow()

    /** P6-C：Dedicated 成功後裝置本地觸發時間（millis；UI 明示「同步觸發時間」，不冒充觀測時間）。 */
    private val _dedicatedLastSynced = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dedicatedLastSynced: StateFlow<Map<String, Long>> = _dedicatedLastSynced.asStateFlow()

    /** P6-C MAJOR10：本次成功觀測的最大 observedAt（資料時間；null 表無成功觀測）。 */
    private val _dedicatedLatestObserved = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dedicatedLatestObserved: StateFlow<Map<String, Long>> = _dedicatedLatestObserved.asStateFlow()

    /** P6-C BLOCKER7：單帳戶 fallback 註記（true 表本次用了單一帳戶 fallback）。 */
    private val _dedicatedAccountFallback = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val dedicatedAccountFallback: StateFlow<Map<String, Boolean>> = _dedicatedAccountFallback.asStateFlow()

    /** P6-C MINOR1：single-flight 原子認領鎖（check-then-add 改 Mutex，不留競態）。 */
    private val dedicatedSingleFlightMutex = kotlinx.coroutines.sync.Mutex()

    // CR-002 A3/A4：dedicated op 鍵級狀態（provider×account×credential×group exact scope）。
    // 鍵由 [dedicatedOpKey] 長度前綴編碼產生；per-card 錯誤／觸發時間／觀測時間只讀
    // 這四個 flow，不得以 provider 聚合（上方 P6-C provider 級 map）冒充 exact scope。
    // provider 級 map 依任務指示 coexist 保留（既有 UI／既有測試契約），僅作 provider 聚合。

    /** CR-002 A3/A4：dedicated op 鍵級同步中集合（不同 op 鍵互不折疊、互不吞併）。 */
    private val _dedicatedOpSyncing = MutableStateFlow<Set<String>>(emptySet())
    val dedicatedOpSyncing: StateFlow<Set<String>> = _dedicatedOpSyncing.asStateFlow()

    /** CR-002 A3/A4：dedicated op 鍵級錯誤分類（值只取 AUTH／NETWORK／UNSUPPORTED／UNKNOWN）。 */
    private val _dedicatedOpError = MutableStateFlow<Map<String, String?>>(emptyMap())
    val dedicatedOpError: StateFlow<Map<String, String?>> = _dedicatedOpError.asStateFlow()

    /** CR-002 A3/A4：dedicated op 鍵級成功後裝置本地觸發時間（millis；與資料時間分離）。 */
    private val _dedicatedOpLastSynced = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dedicatedOpLastSynced: StateFlow<Map<String, Long>> = _dedicatedOpLastSynced.asStateFlow()

    /** CR-002 A3/A4：dedicated op 鍵級本次成功觀測的最大 observedAt（資料時間；null 表無成功觀測）。 */
    private val _dedicatedOpLatestObserved = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dedicatedOpLatestObserved: StateFlow<Map<String, Long>> = _dedicatedOpLatestObserved.asStateFlow()

    // CR-002 A2-A4：New API op 鍵級狀態（provider×account×credential exact scope，**無 group**）。
    // 鍵由 [newApiSyncKey] 長度前綴編碼產生；與 dedicated op 鍵級 flow 同一契約，
    // 但兩 lane 的 claim／error／lastSynced 各自獨立（互不折疊、互不吞併）。
    // provider 級聚合沿用既有 [AtlasUiState.syncingProviders]；不得用 provider 聚合冒充 exact scope。

    /** CR-002 A2-A4：New API op 鍵級同步中集合（不同 op 鍵互不覆蓋）。 */
    private val _newApiOpSyncing = MutableStateFlow<Set<String>>(emptySet())
    val newApiOpSyncing: StateFlow<Set<String>> = _newApiOpSyncing.asStateFlow()

    /** PK-26：My Models toggle per-key in-flight／error（membership 真值仍由 store Flow 驅動）。 */
    private val _myModelToggleInFlight = MutableStateFlow<Set<MyModelKey>>(emptySet())
    val myModelToggleInFlight: StateFlow<Set<MyModelKey>> = _myModelToggleInFlight.asStateFlow()
    private val _myModelToggleError = MutableStateFlow<Map<MyModelKey, String>>(emptyMap())
    val myModelToggleError: StateFlow<Map<MyModelKey, String>> = _myModelToggleError.asStateFlow()

    /** CR-002 A2-A4：New API op 鍵級錯誤分類（值只取 AUTH／NETWORK／UNSUPPORTED／UNKNOWN／BUSY）。 */
    private val _newApiOpError = MutableStateFlow<Map<String, String?>>(emptyMap())
    val newApiOpError: StateFlow<Map<String, String?>> = _newApiOpError.asStateFlow()

    /**
     * CR-002 A2-A4：New API op 鍵級成功後裝置本地觸發時間（millis；與資料時間分離）。
     * A2-A4 error/callback 骨架片**尚未**寫入此欄：durable-fact 判定（prices／accountQuota／
     * tokenQuota 至少一項）屬 A4 wave slice。null 表「尚無成功觸發紀錄」，
     * 不得以觸發時間冒充資料時間，也不得以 0 填補（Unknown != 0）。
     */
    private val _newApiOpLastSynced = MutableStateFlow<Map<String, Long>>(emptyMap())
    val newApiOpLastSynced: StateFlow<Map<String, Long>> = _newApiOpLastSynced.asStateFlow()

    /**
     * CR-002 A2-A4：New API op 鍵級本次成功觀測的最大 observedAt（資料時間；非觸發時間）。
     * 與 [newApiOpLastSynced] 同屬 A4 wave slice；目前恆 null。
     */
    private val _newApiOpLatestObserved = MutableStateFlow<Map<String, Long>>(emptyMap())
    val newApiOpLatestObserved: StateFlow<Map<String, Long>> = _newApiOpLatestObserved.asStateFlow()

    /** CR-002 A2-A4：New API op 鍵級 single-flight 原子認領鎖（獨立於 dedicated lane 的鎖）。 */
    private val newApiSingleFlightMutex = kotlinx.coroutines.sync.Mutex()

    /** P6 INT-2：Models 列表全量列（offerings＋availability＋classification＋price／quota heads）。 */
    private val _allModelRows = MutableStateFlow<List<ModelRow>>(emptyList())
    val allModelRows: StateFlow<List<ModelRow>> = _allModelRows.asStateFlow()

    // ── FR-002：使用者明確加入 My Models、但 offering 仍 hidden（未驗證）的列 ──
    //
    // 只影響 My Models 清單；`_allModelRows` 仍只裝 visible 列（Compare／detail 既有
    // 真相不動）。兩欄由同一次 load 的同一組 joins 產生（單一
    // `ModelRowMapper.buildPartitioned` 呼叫，見 `loadModelRows`），並在 token／scope
    // 雙重核對通過後一起回填；任一失敗則兩欄都不寫（last-good 對稱，無新旗標）。
    private val _pendingMemberRows = MutableStateFlow<List<ModelRow>>(emptyList())
    val pendingMemberRows: StateFlow<List<ModelRow>> = _pendingMemberRows.asStateFlow()

    // 上一次**成功回填**所使用的 membership 集合。用途僅兩端：
    //   (1) membership collector 的比較基準，避免每次 emission 都無腦重跑；
    //   (2) 讓「初始 emission 尚未到達」不會造成漏重跑。
    // 刻意用 @Volatile var 而非 MutableStateFlow：membership collector 不收集它；
    // 若改成可收集的 flow 會形成 load → 標記寫入 → emission → load 的自觸發迴圈。
    @Volatile
    private var modelRowsMembershipUsed: Set<MyModelKey> = emptySet()

    /** P6 INT-2：目前選擇的 account（null＝未選，投影 fail-closed 回 UNKNOWN）。 */
    private val _selectedAccountId = MutableStateFlow<String?>(null)
    val selectedAccountId: StateFlow<String?> = _selectedAccountId.asStateFlow()

    /** P6 INT-2：目前選擇的 credential。 */
    private val _selectedCredentialId = MutableStateFlow<String?>(null)
    val selectedCredentialId: StateFlow<String?> = _selectedCredentialId.asStateFlow()

    /** P6 INT-2：目前選擇的 group（null＝未指定，不與具體群組互相污染）。 */
    private val _selectedGroup = MutableStateFlow<String?>(null)
    val selectedGroup: StateFlow<String?> = _selectedGroup.asStateFlow()

    // refresh() 的初始化收集器會使用這些狀態；必須在 init 之前建立。
    private var explicitGroup: String? = null
    private var explicitGroupKey: Pair<String?, String?>? = null
    private var modelRowsJob: Job? = null
    private var modelRowsToken: Long = 0L
    private var modelRowsScope: SelectedContext? = null
    private var modelRowsDirty: Boolean = false
    private var modelRowsPendingAnnounce: Boolean = false
    private val _modelRowsLoading = MutableStateFlow(false)
    val modelRowsLoading: StateFlow<Boolean> = _modelRowsLoading.asStateFlow()
    private val _modelRowsLoadFailed = MutableStateFlow(false)
    val modelRowsLoadFailed: StateFlow<Boolean> = _modelRowsLoadFailed.asStateFlow()
    private val _compareRows = MutableStateFlow<List<CompareLiveRowResult>>(emptyList())
    val compareRows: StateFlow<List<CompareLiveRowResult>> = _compareRows.asStateFlow()
    private val _compareRunning = MutableStateFlow(false)
    val compareRunning: StateFlow<Boolean> = _compareRunning.asStateFlow()
    private val _compareError = MutableStateFlow<String?>(null)
    val compareError: StateFlow<String?> = _compareError.asStateFlow()
    private val _modelDetailRow = MutableStateFlow<ModelRow?>(null)
    val modelDetailRow: StateFlow<ModelRow?> = _modelDetailRow.asStateFlow()
    private val _detailEvidenceVersion = MutableStateFlow(0L)
    val detailEvidenceVersion: StateFlow<Long> = _detailEvidenceVersion.asStateFlow()
    private val _compareChoices = MutableStateFlow<List<CompareKeyChoice>>(emptyList())
    val compareChoices = _compareChoices.asStateFlow()
    private val _compareSelections = MutableStateFlow<Set<CompareKeySelection>>(emptySet())
    val compareSelections = _compareSelections.asStateFlow()
    private val _compareChoicesLoading = MutableStateFlow(false)
    val compareChoicesLoading = _compareChoicesLoading.asStateFlow()
    private val _compareChoicesLoadFailed = MutableStateFlow(false)
    val compareChoicesLoadFailed = _compareChoicesLoadFailed.asStateFlow()
    private var compareChoicesToken = 0
    private var compareSelectionInitialized = false
    private var compareChoiceContext: CompareContextStamp? = null
    private var compareRequestSelections: Set<CompareKeySelection> = emptySet()
    private data class CompareContextStamp(
        val selectedId: String?, val accountId: String?, val credentialId: String?, val group: String?,
    )
    private var compareJob: Job? = null
    private var compareToken: Long = 0L

    private val _providerModelAction = MutableStateFlow(ProviderModelActionState())
    val providerModelAction: StateFlow<ProviderModelActionState> = _providerModelAction.asStateFlow()
    private var providerModelJob: Job? = null

    // D-028 F1 in-memory registries — MUST be declared before the init block:
    // the session hook launched from init reads them immediately (Kotlin
    // property initialization order is textual; declaring them here avoids
    // the null-registry NPE when the hook wins the Main dispatcher).
    private val myModelRefreshPolicy = MyModelMetadataRetryPolicy()
    private val refreshFlightMutex = kotlinx.coroutines.sync.Mutex()
    private val refreshFlights = mutableMapOf<MyModelMetadataRetryPolicy.RefreshWorkScope, CompletableDeferred<RefreshOutcome>>()
    private val refreshProviderLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    // Bundle flight registries — same textual-order requirement: the F1 seam's
    // bundle-in-flight peek (and the membership collector launched from init)
    // touches bundleFlightMutex before late declarations would initialize.
    private val bundleFlightMutex = kotlinx.coroutines.sync.Mutex()
    private val bundleFlights = mutableMapOf<BundleEnrichmentKey, CompletableDeferred<MyModelBundleResult>>()

    init {
        viewModelScope.launch {
            app.ready.collect { readyCache = it }
        }
        viewModelScope.launch {
            app.initializationError.collect { failed ->
                initErrorCache = failed
                if (failed) _state.update { it.copy(error = string(R.string.error_initialization)) }
            }
        }
        viewModelScope.launch {
            app.repository.connections
                .catch {
                    connectionsFirstLoaded = true
                    _state.update { it.copy(error = string(R.string.error_load), connectionsLoading = false) }
                }
                .collect {
                    connectionCache = it
                    syncEvaluatedCache = emptySet()
                    connectionsFirstLoaded = true
                    refresh()
                }
        }
        viewModelScope.launch {
            app.repository.managementConnections
                .catch { _state.update { it.copy(error = string(R.string.error_load)) } }
                .collect {
                    managementCache = it
                    syncEvaluatedCache = emptySet()
                    refresh()
                }
        }
        viewModelScope.launch {
            app.repository.samples
                .catch { _state.update { it.copy(error = string(R.string.error_load)) } }
                .collect {
                    sampleCache = it
                    refresh()
                }
        }
        viewModelScope.launch {
            app.repository.checkins
                .catch { _state.update { it.copy(error = string(R.string.error_load)) } }
                .collect {
                    checkinCache = it
                    refresh()
                }
        }
        registerDateObservers()
        // D-028 F1 V2 session hook（H1 形狀）：啟動時對持久化成員做一次 bounded
        // SESSION_AUTO pass（生產預設：無 leg ports、無 observer、無 reader）。
        // 預算在 [myModelRefreshPolicy] 內 per-exact-W；follower 併入、spent 抑制，
        // 永不二次 charge。best-effort：失敗靜默，後續 membership emission 補跑（H2/H3）。
        viewModelScope.launch {
            try {
                val keys = myModelsStore.myModelKeys()
                for (k in keys) {
                    requestMyModelRefresh(
                        providerId = k.providerId,
                        providerModelId = k.providerModelId,
                        origin = RefreshOrigin.SESSION_AUTO,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Session pass best-effort：DAO 失敗 fail-closed，不在此重試。
            }
        }
    }

    fun saveConnection(name: String, baseUrl: String, modelId: String, secret: String) {
        if (_state.value.saving) return
        if (_state.value.busy) {
            _state.update { it.copy(error = string(R.string.error_busy)) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            var selectId: String? = null
            try {
                val cleanName = name.trim()
                val cleanBase = baseUrl.trim()
                val cleanModel = modelId.trim()
                if (cleanName.isEmpty() || cleanBase.isEmpty() || cleanModel.isEmpty() || secret.isBlank()) {
                    failure = string(R.string.error_required_fields)
                    return@launch
                }
                if (secret.length > 8192 || '\r' in secret || '\n' in secret) {
                    failure = string(R.string.error_credential_format)
                    return@launch
                }
                val validated = try {
                    EndpointPolicy.validate(cleanBase)
                } catch (_: Exception) {
                    failure = string(R.string.error_https)
                    return@launch
                }
                val origin = try {
                    EndpointPolicy.origin(validated)
                } catch (_: Exception) {
                    failure = string(R.string.error_https)
                    return@launch
                }
                val endpointUrl = try {
                    EndpointPolicy.chatEndpoint(cleanBase)
                } catch (_: Exception) {
                    failure = string(R.string.error_https)
                    return@launch
                }
                val record = ConnectionRecord(
                    id = UUID.randomUUID().toString(),
                    providerId = UUID.randomUUID().toString(),
                    accountId = UUID.randomUUID().toString(),
                    credentialId = UUID.randomUUID().toString(),
                    endpointId = UUID.randomUUID().toString(),
                    name = cleanName,
                    baseUrl = cleanBase,
                    endpointUrl = endpointUrl,
                    modelId = cleanModel,
                )
                try {
                    withContext(Dispatchers.IO + NonCancellable) {
                        app.vault.store(record.credentialId, origin, secret)
                        try {
                            app.repository.addConnection(record)
                        } catch (e: Exception) {
                            try {
                                app.vault.remove(record.credentialId)
                            } catch (_: Exception) {
                            }
                            throw e
                        }
                    }
                } catch (_: Exception) {
                    failure = string(R.string.error_save)
                    return@launch
                }
                pendingById[record.id] = record
                selectId = record.id
            } finally {
                _state.update {
                    it.copy(
                        saving = false,
                        error = failure ?: it.error,
                        selectedId = selectId ?: it.selectedId,
                        formClearToken = if (selectId != null) it.formClearToken + 1 else it.formClearToken,
                        messages = if (selectId != null) emptyList() else it.messages,
                    )
                }
                // MODEL-007／008 review fix：本路徑直接指定 selectedId（未經 selectInternal），
                // 補上 context 推導，避免新連線被選中卻沒有 selected key。
                if (selectId != null) applyContextFromSelection(recompute = true)
                // MYM-003：連線儲存成功即其模型正式入會（failure／selectId==null 絕不加）。
                selectId?.let { sid ->
                    pendingById[sid]?.let { saved ->
                        addMyModelExplicit(
                            saved.providerId, saved.modelId, "USER",
                            saved.accountId, saved.credentialId, null,
                        )
                    }
                }
            }
        }
    }

    fun selectConnection(id: String) {
        val disabled = managementCache.firstOrNull { it.id == id }?.let {
            if (it.anonymous) false else runCatching { it.credentialEnabled }.getOrDefault(true).not()
        } ?: false
        if (disabled) {
            _state.update { it.copy(error = string(R.string.error_connection_disabled)) }
            return
        }
        return selectInternal(id)
    }

    fun saveManualConnection(
        name: String,
        baseUrl: String,
        modelId: String,
        secret: String,
        checkinUrl: String = "",
    ) {
        if (_state.value.saving) return
        if (_state.value.busy) {
            _state.update { it.copy(error = string(R.string.error_busy)) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            var selectId: String? = null
            try {
                val cleanName = name.trim()
                val cleanBase = baseUrl.trim()
                val cleanModel = modelId.trim()
                if (cleanName.isEmpty() || cleanBase.isEmpty() || cleanModel.isEmpty() || secret.isBlank()) {
                    failure = string(R.string.error_required_fields)
                    return@launch
                }
                if (secret.length > 8192 || '\r' in secret || '\n' in secret) {
                    failure = string(R.string.error_credential_format)
                    return@launch
                }
                val validated = try {
                    EndpointPolicy.validate(cleanBase)
                } catch (_: Exception) {
                    failure = string(R.string.error_https)
                    return@launch
                }
                val origin = try {
                    EndpointPolicy.origin(validated)
                } catch (_: Exception) {
                    failure = string(R.string.error_https)
                    return@launch
                }
                val endpointUrl = try {
                    EndpointPolicy.chatEndpoint(cleanBase)
                } catch (_: Exception) {
                    failure = string(R.string.error_https)
                    return@launch
                }
                val cleanCheckin = checkinUrl.trim()
                val validatedCheckin: String? = if (cleanCheckin.isEmpty()) {
                    null
                } else {
                    try {
                        val v = EndpointPolicy.validate(cleanCheckin)
                        EndpointPolicy.origin(v)
                        cleanCheckin
                    } catch (_: Exception) {
                        failure = string(R.string.error_https)
                        return@launch
                    }
                }
                val record = ConnectionRecord(
                    id = UUID.randomUUID().toString(),
                    providerId = UUID.randomUUID().toString(),
                    accountId = UUID.randomUUID().toString(),
                    credentialId = UUID.randomUUID().toString(),
                    endpointId = UUID.randomUUID().toString(),
                    name = cleanName,
                    baseUrl = cleanBase,
                    endpointUrl = endpointUrl,
                    modelId = cleanModel,
                    checkinUrl = validatedCheckin,
                    requiresDailyCheckin = validatedCheckin != null,
                )
                try {
                    withContext(Dispatchers.IO + NonCancellable) {
                        app.vault.store(record.credentialId, origin, secret)
                        try {
                            app.repository.addConnection(record)
                        } catch (e: Exception) {
                            try {
                                app.vault.remove(record.credentialId)
                            } catch (_: Exception) {
                            }
                            throw e
                        }
                    }
                } catch (_: Exception) {
                    failure = string(R.string.error_save)
                    return@launch
                }
                pendingById[record.id] = record
                selectId = record.id
            } finally {
                _state.update {
                    it.copy(
                        saving = false,
                        error = failure ?: it.error,
                        selectedId = selectId ?: it.selectedId,
                        formClearToken = if (selectId != null) it.formClearToken + 1 else it.formClearToken,
                        messages = if (selectId != null) emptyList() else it.messages,
                    )
                }
                // MODEL-007／008 review fix：同 saveConnection，自動選取後補 context 推導。
                if (selectId != null) applyContextFromSelection(recompute = true)
                // MYM-003：連線儲存成功即其模型正式入會（failure／selectId==null 絕不加）。
                selectId?.let { sid ->
                    pendingById[sid]?.let { saved ->
                        addMyModelExplicit(
                            saved.providerId, saved.modelId, "USER",
                            saved.accountId, saved.credentialId, null,
                        )
                    }
                }
            }
        }
    }

    private fun selectInternal(id: String) {
        val previous = _state.value.selectedId
        if (previous == id) return
        cancel()
        cancelProviderModelAction()
        cancelCompareLive()
        resetCompareChoices()
        if (previous != null) historyByConnection.remove(previous)
        _state.update { it.copy(selectedId = id, messages = emptyList(), error = null) }
        // MODEL-007／008 review fix：使用者切換連線時 selected context 立刻跟著換，
        // 不留在舊 key 上（舊 key 的比測結果不得掛在新 key 名下）。
        applyContextFromSelection(recompute = true)
    }

    fun send(prompt: String) {
        if (_state.value.busy) return
        if (_providerModelAction.value.busy || _compareRunning.value) {
            _state.update { it.copy(error = string(R.string.error_busy)) }
            return
        }
        if (initErrorCache) {
            _state.update { it.copy(error = string(R.string.error_initialization)) }
            return
        }
        if (!readyCache || _state.value.saving) {
            _state.update { it.copy(error = string(R.string.error_preparing)) }
            return
        }
        val text = prompt.trim()
        if (text.isEmpty()) return
        val current = _state.value
        val record = connectionCache.firstOrNull { it.id == current.selectedId }
            ?: pendingById[current.selectedId]
        if (record == null) {
            val managedDisabled = managementCache.firstOrNull { it.id == current.selectedId }
            if (managedDisabled != null && !managedDisabled.anonymous && !managedDisabled.credentialEnabled) {
                _state.update { it.copy(error = string(R.string.error_connection_disabled)) }
            } else {
                _state.update { it.copy(error = string(R.string.error_select_connection)) }
            }
            return
        }
        // 匿名列用 anonymous 旗標判斷，不用 credentialId（匿名 credentialId=""，永不進 blocked 集）。
        val isAnonymousTarget = record.anonymous || record.credentialId.isEmpty()
        val isLocallyBlocked = !isAnonymousTarget && record.credentialId in blockedCredentialIds
        val isRemoteDisabled = !isAnonymousTarget && !record.credentialEnabled
        if (isRemoteDisabled || isLocallyBlocked) {
            pendingById.remove(record.id)
            historyByConnection.remove(record.id)
            _state.update { it.copy(error = string(R.string.error_connection_disabled)) }
            return
        }
        val requestConnectionId = record.id
        // Capture at send time: later UI scope changes must not mutate an in-flight request.
        val selectedGroupAtSend = _selectedGroup.value
        val requestMessages = (historyByConnection[requestConnectionId]?.takeLast(20).orEmpty() +
            PromptMessage("user", text))
        val userMessage = ChatMessage(id = UUID.randomUUID().toString(), role = "user", text = text)
        val assistantId = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                busy = true,
                error = null,
                messages = it.messages + userMessage +
                    ChatMessage(id = assistantId, role = "assistant", text = ""),
            )
        }
        var myJob: Job? = null
        myJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var success = false
            var wasCancelled = false
            val flusher = SendStreamFlushController(
                canPublish = { sendJob === myJob && _state.value.selectedId == requestConnectionId },
            ) { text ->
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map { m ->
                            if (m.id == assistantId) m.copy(text = text) else m
                        },
                    )
                }
            }
            try {
                // P0-4 fail-closed：provider_config 載入失敗一律拒發，不以空 customFields 蒙混；
                // API_KEY / CUSTOM_HEADER 取回空 customFields 亦拒發，不送無憑證請求。
                // 匿名：credentialId=null，不送 Authorization；custom-only（無 INFERENCE）亦不送 Bearer，只送 custom auth refs。
                // SEC-001: provider-wide auth metadata is NOT evidence that the selected
                // credential is an enabled INFERENCE ref. Reuse the selected-record
                // validator used by manual probes to reject ACCOUNT PAT/foreign refs.
                val sendAuth = withContext(Dispatchers.IO) { loadProviderModelAuth(record) }
                if (sendAuth.loadFailed) {
                    if (sendJob === myJob && _state.value.selectedId == requestConnectionId) {
                        _state.update { it.copy(error = string(R.string.error_load)) }
                    }
                    return@launch
                }
                if ((sendAuth.authMode == AuthMode.API_KEY || sendAuth.authMode == AuthMode.CUSTOM_HEADER) &&
                    sendAuth.customFields.isEmpty()
                ) {
                    if (sendJob === myJob && _state.value.selectedId == requestConnectionId) {
                        _state.update { it.copy(error = string(R.string.error_auth)) }
                    }
                    return@launch
                }
                val anonymousTarget = record.anonymous || record.credentialId.isEmpty()
                if (!record.anonymous && record.credentialId.isBlank()) {
                    // 非匿名列缺 ref：資料損毀，fail-closed 拒發，不退化成匿名。
                    if (sendJob === myJob && _state.value.selectedId == requestConnectionId) {
                        _state.update { it.copy(error = string(R.string.error_auth)) }
                    }
                    return@launch
                }
                val safeCredentialId: String? = when {
                    anonymousTarget -> null
                    (sendAuth.authMode == AuthMode.API_KEY || sendAuth.authMode == AuthMode.CUSTOM_HEADER) &&
                        !sendAuth.hasInference -> null
                    else -> record.credentialId
                }
                val customFields = sendAuth.customFields
                val target = buildPlaygroundInferenceTarget(
                    record = record,
                    bearerCredentialId = safeCredentialId,
                    authFields = customFields,
                    selectedGroup = selectedGroupAtSend,
                )
                app.engine.execute(
                    target,
                    requestMessages,
                    priceState = record.priceState.ifBlank { "UNKNOWN" },
                    quotaState = record.quotaState.ifBlank { "UNKNOWN" },
                ).collect { event ->
                    if (sendJob !== myJob || _state.value.selectedId != requestConnectionId) return@collect
                    if (event is StreamEvent.Delta && !event.reasoning && event.text.isNotEmpty()) {
                        flusher.onDelta(event.text)
                    }
                }
                flusher.flushPending()
                success = true
            } catch (e: CancellationException) {
                wasCancelled = true
                throw e
            } catch (e: InferenceFailure) {
                flusher.flushPending()
                if (sendJob === myJob && _state.value.selectedId == requestConnectionId) {
                    _state.update { it.copy(error = messageFor(e.outcome)) }
                }
            } catch (_: Exception) {
                flusher.flushPending()
                if (sendJob === myJob && _state.value.selectedId == requestConnectionId) {
                    _state.update { it.copy(error = string(R.string.error_request)) }
                }
            } finally {
                if (wasCancelled && sendJob === myJob && _state.value.selectedId == requestConnectionId) {
                    flusher.flushPending()
                    // F28：取消只標記截斷，不捏造內容；history 只收成功 pair，不在此寫入。
                    _state.update { s ->
                        s.copy(
                            messages = s.messages.map { m ->
                                if (m.id == assistantId && !m.text.endsWith(CANCEL_TRUNCATION_MARK)) {
                                    m.copy(text = m.text + CANCEL_TRUNCATION_MARK)
                                } else {
                                    m
                                }
                            },
                        )
                    }
                } else if (success && sendJob === myJob && _state.value.selectedId == requestConnectionId) {
                    val finalText = _state.value.messages.firstOrNull { it.id == assistantId }?.text.orEmpty()
                    val bucket = historyByConnection.getOrPut(requestConnectionId) { mutableListOf() }
                    bucket.add(PromptMessage("user", text))
                    bucket.add(PromptMessage("assistant", finalText))
                    while (bucket.size > 20) bucket.removeAt(0)
                }
                if (sendJob === myJob) {
                    sendJob = null
                    _state.update { it.copy(busy = false) }
                }
            }
        }
        sendJob = myJob
        myJob.start()
    }

    fun cancel() {
        sendJob?.cancel()
        // 保留 job 至 finally：後續刪除必須等 socket 與 telemetry 真正結束。
    }

    /** Only the explicitly selected connection may supply account / key / endpoint. */
    fun addProviderModel(providerId: String, modelId: String) =
        startProviderModelAction(providerId, modelId, test = false)

    fun testProviderModel(providerId: String, modelId: String) =
        startProviderModelAction(providerId, modelId, test = true)

    fun cancelProviderModelAction() {
        providerModelJob?.cancel()
        // Keep ownership until finally: socket close and final telemetry must finish first.
    }

    fun leaveProviderDetail(providerId: String) {
        if (_providerModelAction.value.providerId == providerId) cancelProviderModelAction()
    }

    private suspend fun cancelAndJoinProviderModelAction() {
        val job = providerModelJob ?: return
        job.cancel()
        job.join()
    }

    /**
     * 本次動作的 selected scope 是否仍是目前選取。
     * [recordFor] 解析不到（Room re-emission 瞬間或連線已刪）一律回 false（fail-closed）：
     * 呼叫端在 `ensureActive()` 之後、送 request 之前檢查，因此真的被刪／被換也一定會取消。
     */
    private fun providerModelScopeIsCurrent(record: ConnectionRecord, group: String?): Boolean {
        val selected = recordFor(_state.value.selectedId) ?: return false
        return selected.id == record.id && selected.providerId == record.providerId &&
            selected.accountId == record.accountId && selected.credentialId == record.credentialId &&
            selected.endpointId == record.endpointId && selected.endpointUrl == record.endpointUrl &&
            selected.protocol == record.protocol && _selectedGroup.value == group &&
            (selected.anonymous || (selected.credentialEnabled && selected.credentialId !in blockedCredentialIds))
    }

    private fun startProviderModelAction(providerId: String, modelId: String, test: Boolean) {
        if (providerModelJob != null) return
        val model = modelId.trim()
        val record = recordFor(_state.value.selectedId)
        val before = _providerModelAction.value
        fun reject(message: String) {
            _providerModelAction.value = before.copy(
                providerId = providerId, modelId = model, connectionId = record?.id,
                group = _selectedGroup.value,
                kind = if (test) "TEST" else "ADD", status = "FAILURE", message = message,
            )
        }
        if (initErrorCache) { reject(string(R.string.error_initialization)); return }
        if (!readyCache || _state.value.saving) { reject(string(R.string.error_preparing)); return }
        if (_state.value.busy || _compareRunning.value) { reject(string(R.string.error_busy)); return }
        if (model.isEmpty() || model.length > 512 || model.any { it.isISOControl() }) {
            reject(string(R.string.error_required_fields)); return
        }
        if (record == null || record.providerId != providerId) {
            reject(string(R.string.error_select_connection)); return
        }
        if (!record.anonymous && (record.credentialId.isBlank() || !record.credentialEnabled ||
                record.credentialId in blockedCredentialIds)) {
            reject(string(R.string.error_connection_disabled)); return
        }
        val group = _selectedGroup.value
        _providerModelAction.value = before.copy(
            providerId = providerId, modelId = model, connectionId = record.id,
            group = group,
            kind = if (test) "TEST" else "ADD", status = "RUNNING", message = null,
        )
        var myJob: Job? = null
        myJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var result = "FAILURE"
            var message: String? = string(R.string.error_request)
            var savedCandidate = false
            try {
                val offeringId = withContext(Dispatchers.IO) {
                    app.repository.addProviderModelCandidate(record, model, group)
                }
                savedCandidate = true
                ensureActive()
                if (!providerModelScopeIsCurrent(record, group)) throw CancellationException()
                if (!test) {
                    result = "SUCCESS"
                    message = string(R.string.provider_detail_unverified)
                    return@launch
                }
                // Reuse the existing auth metadata boundary; never read raw credential values.
                val auth = withContext(Dispatchers.IO) { loadProviderModelAuth(record) }
                ensureActive()
                if (!providerModelScopeIsCurrent(record, group)) throw CancellationException()
                if (auth.loadFailed ||
                    ((auth.authMode == AuthMode.API_KEY || auth.authMode == AuthMode.CUSTOM_HEADER) &&
                        auth.customFields.isEmpty()) ||
                    (record.anonymous && auth.authMode != AuthMode.NONE)) {
                    message = string(R.string.error_auth)
                    return@launch
                }
                val credentialId = when {
                    record.anonymous -> null
                    (auth.authMode == AuthMode.API_KEY || auth.authMode == AuthMode.CUSTOM_HEADER) &&
                        !auth.hasInference -> null
                    else -> record.credentialId
                }
                val target = InferenceTarget(
                    providerId = record.providerId, accountId = record.accountId,
                    credentialId = credentialId, endpointId = record.endpointId,
                    endpointUrl = record.endpointUrl, modelId = model,
                    canonicalModelId = record.canonicalModelId.takeIf { model == record.modelId },
                    group = group, operation = record.protocol, maxOutputTokens = 32,
                    authFields = auth.customFields,
                )
                var sampleId: String? = null
                (providerModelTestEngine ?: app.engine).execute(
                    target, listOf(PromptMessage("user", "Reply OK.")),
                    priceState = if (model == record.modelId) record.priceState else "UNKNOWN",
                    quotaState = if (model == record.modelId) record.quotaState else "UNKNOWN",
                    source = TelemetrySource.MANUAL_TEST,
                ).collect { event ->
                    if (event is StreamEvent.RequestSent) sampleId = event.sampleId
                }
                // execute's finally has durably finalized the sample before collect returns.
                ensureActive()
                if (!providerModelScopeIsCurrent(record, group)) throw CancellationException()
                val exactId = sampleId ?: return@launch
                val verified = withContext(Dispatchers.IO) {
                    app.repository.verifyProviderModelCandidate(offeringId, record, target, exactId)
                }
                ensureActive()
                if (!providerModelScopeIsCurrent(record, group)) throw CancellationException()
                if (verified) {
                    result = "SUCCESS"
                    message = string(R.string.provider_detail_verified)
                }
            } catch (e: CancellationException) {
                result = "CANCELLED"
                message = null
                throw e
            } catch (e: InferenceFailure) {
                message = messageFor(e.outcome)
            } catch (_: Exception) {
                // Only localized, fixed error text crosses the UI boundary.
                message = string(R.string.error_request)
            } finally {
                if (providerModelJob === myJob) {
                    providerModelJob = null
                    _providerModelAction.update { action ->
                        action.copy(
                            status = result, message = message,
                            clearToken = action.clearToken + if (!test && result == "SUCCESS") 1 else 0,
                            refreshToken = action.refreshToken + if (savedCandidate) 1 else 0,
                        )
                    }
                    // MYM-003：手動新增成功即正式入會（不二次 toggle、不以 Test 作 gate；
                    // CANCELLED／FAILURE 絕不加）。scope 取本次真 record，不猜；
                    // credential 推導與 TEST 共用同一 auth 邊界（匿名／custom-only 無
                    // inference 即 null），load 失敗即 null（enrichment 走無 Bearer
                    // scope，不阻擋入會）。
                    if (!test && result == "SUCCESS" && savedCandidate) {
                        val auth = runCatching {
                            withContext(Dispatchers.IO) { loadProviderModelAuth(record) }
                        }.getOrNull()
                        val enrichCredential = when {
                            record.anonymous -> null
                            auth == null || auth.loadFailed -> null
                            (auth.authMode == AuthMode.API_KEY || auth.authMode == AuthMode.CUSTOM_HEADER) &&
                                !auth.hasInference -> null
                            else -> record.credentialId
                        }
                        addMyModelExplicit(
                            record.providerId, model, "USER",
                            record.accountId, enrichCredential, group,
                        )
                    }
                    refreshModelRows()
                }
            }
        }
        providerModelJob = myJob
        myJob.invokeOnCompletion { cause ->
            // A LAZY job cancelled before its body starts never reaches the body's finally.
            // Release that claim too, without inventing a successful save or a clear token.
            if (providerModelJob === myJob) {
                providerModelJob = null
                _providerModelAction.update { action ->
                    action.copy(
                        status = if (cause is CancellationException) "CANCELLED" else "FAILURE",
                        message = if (cause is CancellationException) null else string(R.string.error_request),
                    )
                }
            }
        }
        myJob.start()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * 手動同步入口：供同步按鈕觸發。同步中 providerId 進 `syncingProviders`（provider 級，可並行不同 provider；
     * 同一 provider 重入直接返回），失敗寫 `error`；
     * 不做背景自動（`init` / `refresh` / `refreshLocalDate` 永不呼叫）。
     * secret 只以 ref 經 adapter 在 HTTP 邊界取值，不進 ViewModel。
     */
    fun syncNewApi(providerId: String) {
        if (providerId.isBlank()) {
            _state.update { it.copy(error = string(R.string.error_sync_failed)) }
            return
        }
        if (providerId in _state.value.syncingProviders) return
        if (_state.value.busy || _state.value.saving) {
            _state.update { it.copy(error = string(R.string.error_busy)) }
            return
        }
        _state.update { it.copy(syncingProviders = it.syncingProviders + providerId, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            try {
                // CR-002 A2-A4：結果改由 typed outcome 分派，舊 lane 的可見行為**逐一不變**
                // （UNSUPPORTED→error_sync_unavailable；其餘→error_sync_failed；取消重拋；
                //  finally 仍只釋放自己的 provider claim，error 沿用 failure ?: 原值）。
                when (val outcome = runNewApiSyncCore(providerId, null, null)) {
                    is NewApiSyncOutcome.Failed -> failure = string(
                        if (outcome.code == "UNSUPPORTED") R.string.error_sync_unavailable
                        else R.string.error_sync_failed
                    )
                    is NewApiSyncOutcome.Succeeded -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { it.copy(syncingProviders = it.syncingProviders - providerId, error = failure ?: it.error) }
            }
        }
    }

    /**
     * CR-002 A2-A4：scoped 手動同步入口（provider×account×credential exact scope，**無 group**）。
     *
     * 與 [syncNewApi] 的 arity 互斥（1／3／4 三個不同 JVM 描述子），故既有
     * `viewModel::syncNewApi` method reference（provider 級、arity 1）在任何編譯器行為下都唯一解析。
     * 空白 provider 直接返回：**永不建立 scoped 鍵**（與 dedicated MINOR9 同規則）。
     *
     * lane 邊界（與舊 provider 級 lane 互不干擾）：本入口**只**寫 op 鍵級 flow，
     * 永不寫 `state.error`／`syncingProviders`；舊 lane 反之亦然。
     * 錯誤只寫代碼（[newApiSyncErrorCodes] 的 closed set），永不寫 localized 字串／URL／body／secret。
     * 取消一律重拋，且 finally 只釋放自己的 scope claim。
     */
    fun syncNewApi(providerId: String, accountId: String?, credentialId: String?) {
        if (providerId.isBlank()) return
        val account = accountId?.takeIf { it.isNotBlank() }
        val rawCredential = credentialId?.takeIf { it.isNotBlank() }
        val scope = newApiSyncKey(providerId, account, rawCredential)
        if (scope in _newApiOpSyncing.value) return
        if (_state.value.busy || _state.value.saving) {
            // 只記「被嘗試的 scope」，不污染同 provider 其他 op 鍵。
            _newApiOpError.update { it + (scope to "BUSY") }
            return
        }
        viewModelScope.launch {
            val claimed = newApiSingleFlightMutex.withLock {
                if (scope in _newApiOpSyncing.value) {
                    false
                } else {
                    _newApiOpSyncing.update { it + scope }
                    true
                }
            }
            if (!claimed) return@launch
            _newApiOpError.update { it - scope }
            try {
                val keyRef = resolveScopedInferenceKeyRef(providerId, rawCredential)
                when (val outcome = runNewApiSyncCore(providerId, account, keyRef)) {
                    is NewApiSyncOutcome.Failed -> _newApiOpError.update { it + (scope to outcome.code) }
                    // A4 wave slice 才依 durable-fact 判定寫 lastSynced／latestObserved；
                    // 本骨架片不寫＝無成功時間可顯示，屬誠實 Unknown，不得以觸發時間冒充。
                    is NewApiSyncOutcome.Succeeded -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _newApiOpSyncing.update { it - scope }
            }
        }
    }

    /**
     * CR-002 A2-A4：scoped 同步的測試 port（注入 adapter factory，與 arity 1／3 描述子互斥）。
     * 同步規則與 [syncNewApi] 完全相同；production UI **不得**使用本多載（呼叫端必須顯式 lambda）。
     */
    fun syncNewApi(
        providerId: String,
        accountId: String?,
        credentialId: String?,
        adapterFactory: (() -> NewApiAdapter)?,
    ) {
        if (providerId.isBlank()) return
        val account = accountId?.takeIf { it.isNotBlank() }
        val rawCredential = credentialId?.takeIf { it.isNotBlank() }
        val scope = newApiSyncKey(providerId, account, rawCredential)
        if (scope in _newApiOpSyncing.value) return
        if (_state.value.busy || _state.value.saving) {
            _newApiOpError.update { it + (scope to "BUSY") }
            return
        }
        viewModelScope.launch {
            val claimed = newApiSingleFlightMutex.withLock {
                if (scope in _newApiOpSyncing.value) {
                    false
                } else {
                    _newApiOpSyncing.update { it + scope }
                    true
                }
            }
            if (!claimed) return@launch
            _newApiOpError.update { it - scope }
            try {
                val keyRef = resolveScopedInferenceKeyRef(providerId, rawCredential)
                when (val outcome = runNewApiSyncCore(providerId, account, keyRef, adapterFactory)) {
                    is NewApiSyncOutcome.Failed -> _newApiOpError.update { it + (scope to outcome.code) }
                    is NewApiSyncOutcome.Succeeded -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _newApiOpSyncing.update { it - scope }
            }
        }
    }

    /**
     * CR-002 A2-A4：New API exact scope 操作狀態選擇器（純讀取四個 op 鍵級 flow）。
     * 與 [dedicatedOpState] 同一契約：per-card／狀態行必須經 op 鍵級 flow 或本選擇器讀取，
     * 不得以 provider 聚合（`syncingProviders` 等）冒充 exact scope。
     */
    fun newApiOpState(
        providerId: String,
        accountId: String?,
        credentialId: String?,
    ): NewApiOpState {
        val key = newApiSyncKey(providerId, accountId, credentialId)
        return NewApiOpState(
            syncing = key in _newApiOpSyncing.value,
            error = _newApiOpError.value[key],
            lastSynced = _newApiOpLastSynced.value[key],
            latestObserved = _newApiOpLatestObserved.value[key],
        )
    }

    /**
     * CR-002 A2-A4：scoped New API 支援探針（唯讀 metadata；不碰 vault、不發請求）。
     *
     * 刻意**不**在此片做 per-account ownership 收窄（AI8 §3／R5 未拍板）：直接委派既有
     * [isSyncSupported]，因此不放寬、不收窄既有 `supportsSync` 語意，也不在 error/callback
     * 骨架片憑空發明帳號歸屬規則。`supportsSync_onlyPatAccountProviderIsSyncCapable` 等既有
     * 期望因此不需任何修改。
     */
    suspend fun newApiScopedSyncSupported(providerId: String): Boolean = isSyncSupported(providerId)

    /** PK-12（H2 v2）：per-provider capability 快取（StateFlow 同步讀——無首幀閃失；DB 讀
     * 失敗不寫入＝維持未解析，重試 token 觸發重解析，不把暫態錯誤凍結成 false）。 */
    private val _newApiSyncCapableCache = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val newApiSyncCapableCache: StateFlow<Map<String, Boolean>> = _newApiSyncCapableCache.asStateFlow()

    fun newApiSyncCapableCached(providerId: String): Boolean? =
        _newApiSyncCapableCache.value[providerId]

    suspend fun newApiSyncCapable(providerId: String): Boolean {
        if (providerId.isBlank()) return false
        val supported = newApiScopedSyncSupported(providerId)
        val present = if (!supported) false else {
            try {
                withContext(Dispatchers.IO) { app.repository.newApiSyncSetupPresent(providerId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // DB 讀失敗＝未解析：不寫入快取（不把暫態錯誤凍結成 false）。
                return _newApiSyncCapableCache.value[providerId] ?: false
            }
        }
        _newApiSyncCapableCache.value = _newApiSyncCapableCache.value + (providerId to present)
        return present
    }

    /**
     * CR-002 A2-A4：New API 同步核心（provider 級與 scoped lane 共用）。
     * 同步規則不變：secret 只以 safe ref 經 adapter 在 HTTP 邊界取值，不進 ViewModel；
     * 取消一律重拋（呼叫端 finally 只清自己的 claim）。
     */
    private suspend fun runNewApiSyncCore(
        providerId: String,
        accountId: String?,
        credentialId: String?,
        adapterFactory: (() -> NewApiAdapter)? = null,
    ): NewApiSyncOutcome = try {
        val adapter = adapterFactory?.invoke() ?: NewApiAdapter(app.vault)
        withContext(Dispatchers.IO) {
            app.repository.syncNewApi(providerId, adapter, accountId, credentialId)
        }
        NewApiSyncOutcome.Succeeded
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NewApiSyncOutcome.Failed(classifyNewApiSyncError(e))
    }

    /**
     * CR-002 A2-A4：New API scoped lane 失敗分類（只回代碼；不攜帶 URL／server body／secret）。
     *
     * 與舊 provider 級 lane 語意**逐一對應**：repo 的 scope／ownership／credential 前置驗證失敗
     * 皆為 IllegalArgumentException，舊 lane 一律顯示 `error_sync_unavailable`；故先判
     * IllegalArgumentException → `UNSUPPORTED`，其餘委給既有 [classifyDedicatedError]
     * （AUTH／NETWORK／UNSUPPORTED／UNKNOWN 共用詞彙；dedicated lane 本身零改動）。
     *
     * 不可直接沿用 classifyDedicatedError 的結果做 scoped lane 的映射：selector 的
     * `"Credential role unavailable"`／`"Credential ownership mismatch"` 含 "credential"，
     * 會被判成 `AUTH`（→`error_auth`），與舊 lane 的 `error_sync_unavailable` 相反——
     * **嚴格性不足不是憑證失效**。這是 F2 修復與舊語意同時成立的關鍵。
     */
    private fun classifyNewApiSyncError(e: Throwable): String = when (e) {
        is IllegalArgumentException -> "UNSUPPORTED"
        else -> classifyDedicatedError(e)
    }

    /**
     * CR-002 A2-A4：卡片 credential ref → repo INFERENCE key ref 的角色正規化（唯讀 metadata）。
     *
     * 卡片帶的是「這列連線綁定的 credential」（[scopedNewApiSyncFor] 傳入的
     * `connection.credentialId`），而 repo `syncNewApi` 的 credentialId 契約是
     * 「inference key safe ref」，[dev.modelatlas.app.data.NewApiCredentialSelector] 對非純
     * INFERENCE 直接 `IllegalArgumentException("Credential role unavailable")`，
     * 使該卡永久 `UNSUPPORTED`（F2）。
     *
     * 本 helper **不解密、不借 PAT、不跨帳號**：只在 provider config metadata 內比對該 ref 的角色，
     * 恰為 {INFERENCE} 才原樣回傳，否則回 null 交由 selector 走合法 fallback（絕不猜另一把 key）。
     * ref 比對**不 trim**（byte-exact，與 selector 的 `it.safeCredentialId == credentialId` 同一規則）；
     * config 讀取失敗／缺 config → null（fail-closed，寧可走 selector，不在此猜）。
     */
    private suspend fun resolveScopedInferenceKeyRef(providerId: String, credentialId: String?): String? {
        val raw = credentialId?.takeIf { it.isNotBlank() } ?: return null
        val roles = try {
            withContext(Dispatchers.IO) {
                app.database.providerConfigDao().getByProvider(providerId)
                    ?.toManualConfig()
                    ?.credentials
                    ?.filter { it.safeCredentialId == raw }
                    ?.map { it.role }
                    ?.toSet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return raw.takeIf { roles == setOf(CredentialRole.INFERENCE) }
    }

    /** 前景恢復由主 UI 呼叫；日期改變即重算待簽到，不猜測、不攜帶 secret。 */
    fun refreshLocalDate() {
        currentDate = LocalDate.now()
        refresh()
    }

    private fun findManagedRecord(connectionId: String): ConnectionRecord? =
        managementCache.firstOrNull { it.id == connectionId }
            ?: pendingById[connectionId]
            ?: connectionCache.firstOrNull { it.id == connectionId }

    private suspend fun cancelAndJoinSend() {
        val job = sendJob ?: run {
            _state.update { if (it.busy) it.copy(busy = false) else it }
            return
        }
        job.cancel()
        job.join()
        if (sendJob === job) sendJob = null
        _state.update { if (it.busy) it.copy(busy = false) else it }
    }

    private fun registerDateObservers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshLocalDate()
            }
        }
        try {
            getApplication<Application>().registerReceiver(receiver, filter)
            dateReceiver = receiver
        } catch (_: Exception) {
            dateReceiver = null
        }
        dateTicker = viewModelScope.launch {
            while (true) {
                val now = java.time.ZonedDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                val delayMs = java.time.Duration.between(now, nextMidnight).toMillis().coerceAtLeast(60_000L)
                try {
                    delay(delayMs)
                } catch (_: CancellationException) {
                    break
                }
                refreshLocalDate()
            }
        }
    }

    override fun onCleared() {
        try {
            dateReceiver?.let { getApplication<Application>().unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        dateReceiver = null
        dateTicker?.cancel()
        dateTicker = null
        super.onCleared()
    }

    fun completeCheckin(connectionId: String) {
        if (connectionId.isBlank()) return
        val record = managementCache.firstOrNull { it.id == connectionId }
            ?: connectionCache.firstOrNull { it.id == connectionId }
            ?: pendingById[connectionId]
        if (record == null || !record.requiresDailyCheckin) {
            _state.update { it.copy(error = string(R.string.error_checkin_not_required)) }
            return
        }
        viewModelScope.launch {
            try {
                val date = LocalDate.now().toString()
                val completedAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    app.repository.completeCheckin(record.providerId, date, completedAt, record.accountId)
                }
            } catch (_: Exception) {
                _state.update { it.copy(error = string(R.string.error_checkin_save)) }
            }
        }
    }

    private suspend fun credentialEnableWriter(id: String, enabled: Boolean) { credentialEnableTestWriter?.invoke(id, enabled) ?: app.credentialLifecycle.setEnabled(id, enabled) }

    fun setCredentialEnabled(connectionId: String, enabled: Boolean) {
        if (connectionId.isBlank()) return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            try {
                val record = findManagedRecord(connectionId)
                if (record == null) {
                    failure = string(R.string.error_connection_not_found)
                    return@launch
                }
                // 匿名列無 vault 憑證：直接拒絕，不進 vault、不碰 blocked 集。
                if (record.anonymous || record.credentialId.isBlank()) {
                    failure = string(R.string.error_credential_update)
                    return@launch
                }
                cancelAndJoinSend()
                cancelAndJoinProviderModelAction()
                cancelCompareForCredential(record.credentialId)
                try {
                    withContext(Dispatchers.IO + NonCancellable) {
                        credentialEnableWriter(record.credentialId, enabled)
                    }
                } catch (_: Exception) {
                    failure = string(R.string.error_credential_update)
                    return@launch
                }
                if (enabled) blockedCredentialIds.remove(record.credentialId)
                else blockCredentialLocally(record.credentialId)
            } finally {
                _state.update { it.copy(saving = false, error = failure ?: it.error) }
                refresh()
            }
        }
    }

    fun deleteCredential(connectionId: String) {
        if (connectionId.isBlank()) return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            try {
                val record = findManagedRecord(connectionId)
                if (record == null) {
                    failure = string(R.string.error_connection_not_found)
                    return@launch
                }
                // 匿名列無 vault 憑證：no-op 拒絕，不進 vault、不碰 blocked 集。
                if (record.anonymous || record.credentialId.isBlank()) {
                    failure = string(R.string.error_credential_delete)
                    return@launch
                }
                cancelAndJoinSend()
                cancelAndJoinProviderModelAction()
                cancelCompareForCredential(record.credentialId)
                blockCredentialLocally(record.credentialId)
                try {
                    withContext(Dispatchers.IO + NonCancellable) {
                        // Custom-auth 同單其他 vault refs 一併清理（`customAuthCleanupRefs`），
                        // 避免 anchor 刪了、兄弟 field secrets 變孤兒；PAT 永不列入。
                        // 先刪兄弟、再刪主 ref：中途失敗主 ref 仍在，可重試。
                        for (sibling in customSiblingRefs(record.providerId, record.credentialId)) {
                            app.credentialLifecycle.delete(sibling)
                        }
                        app.credentialLifecycle.delete(record.credentialId)
                    }
                } catch (_: Exception) {
                    failure = string(R.string.error_credential_delete)
                    return@launch
                }
            } finally {
                _state.update { it.copy(saving = false, error = failure ?: it.error) }
                refresh()
            }
        }
    }

    fun configureCheckin(connectionId: String, url: String) {
        if (connectionId.isBlank()) return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            try {
                cancelAndJoinSend()
                val record = findManagedRecord(connectionId)
                if (record == null) {
                    failure = string(R.string.error_connection_not_found)
                    return@launch
                }
                val clean = url.trim()
                val newUrl: String? = if (clean.isEmpty()) {
                    null
                } else {
                    try {
                        val validated = EndpointPolicy.validate(clean)
                        EndpointPolicy.origin(validated)
                        clean
                    } catch (_: Exception) {
                        failure = string(R.string.error_https)
                        return@launch
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        app.repository.setProviderCheckinConfig(record.providerId, newUrl, newUrl != null)
                    }
                } catch (_: Exception) {
                    failure = string(R.string.error_checkin_config)
                    return@launch
                }
            } finally {
                _state.update { it.copy(saving = false, error = failure ?: it.error) }
                refresh()
            }
        }
    }

    private fun blockCredentialLocally(credentialId: String) {
        if (credentialId.isBlank()) return
        blockedCredentialIds += credentialId
        val affected = (connectionCache + managementCache + pendingById.values)
            .filter { !it.anonymous && it.credentialId == credentialId }.map { it.id }.toSet()
        affected.forEach {
            pendingById.remove(it)
            historyByConnection.remove(it)
        }
        _state.update {
            if (it.selectedId in affected) it.copy(selectedId = null, messages = emptyList()) else it
        }
        refresh()
    }

    private fun refresh() {
        val activeIds = connectionCache.map { it.id }.toSet()
        val managedBaseIds = managementCache.map { it.id }.toSet()
        // pending 待 active／management 兩個 cache 都已 publish 才移除（AND）：兩邊
        // emission 獨立到達，任一先到不得提前移除；下方 per-list bridging 已 dedup，
        // 保留較久不會在列表重複。
        pendingById.keys.removeAll { it in activeIds && it in managedBaseIds }
        val today = LocalDate.now()
        currentDate = today
        val todayStr = today.toString()
        // P1：supportsSync 取自 syncSupportedCache（PAT_ACCOUNT 才 true）；新 provider 預設 false，待 ensureSyncSupport 回填。
        val capable = syncSupportedCache
        fun toItem(r: ConnectionRecord): ConnectionItem {
            val record = checkinCache
                .firstOrNull { c -> c.providerId == r.providerId && c.date == todayStr && c.accountId == r.accountId }
                ?.let {
                    runCatching {
                        CheckinRecord(
                            providerId = it.providerId,
                            accountId = it.accountId.takeIf { acc -> acc.isNotEmpty() },
                            localDate = LocalDate.parse(it.date),
                            completedAt = it.completedAt,
                            source = it.source,
                        )
                    }.getOrNull()
                }
            return ConnectionItem(
                id = r.id,
                providerId = r.providerId,
                name = r.name,
                baseUrl = r.baseUrl,
                modelId = r.modelId,
                credentialLabel = r.credentialLabel,
                priceState = safePriceState(r.priceState),
                quotaState = safeQuotaState(r.quotaState),
                officialHealth = safeHealth(r.officialHealth),
                observedHealth = safeHealth(r.observedHealth),
                checkinUrl = r.checkinUrl,
                checkinPending = r.requiresDailyCheckin && !r.checkinUrl.isNullOrBlank() &&
                    isCheckinPending(today, record),
                accountId = r.accountId,
                credentialId = r.credentialId,
                // 匿名列用 anonymous 旗標：恆啟用，不查 credentialId / blocked 集。
                enabled = if (r.anonymous) true else r.credentialEnabled && r.credentialId !in blockedCredentialIds,
                supportsSync = r.providerId in capable,
            )
        }
        val pendingForActive = pendingById.values.filter { p -> p.id !in activeIds }
        val managedBase = if (managementCache.isNotEmpty()) managementCache else connectionCache
        val managedBaseIdSet = managedBase.map { it.id }.toSet()
        val pendingForManaged = pendingById.values.filter { p -> p.id !in managedBaseIdSet }
        val connections = (connectionCache + pendingForActive)
            .filter { it.anonymous || (it.credentialEnabled && it.credentialId !in blockedCredentialIds) }.map(::toItem)
        val managedConnections = managedBase.map(::toItem) + pendingForManaged.map(::toItem)
        val samples = sampleCache.sortedByDescending { it.requestStartedAt }.map { it.toItem() }
        _state.update { s ->
            val selected = when {
                s.selectedId != null &&
                    (connections.any { it.id == s.selectedId } || pendingById.containsKey(s.selectedId)) -> s.selectedId
                connections.isNotEmpty() -> connections.first().id
                else -> null
            }
            s.copy(
                connections = connections,
                connectionsLoading = !connectionsFirstLoaded,
                managedConnections = managedConnections,
                samples = samples,
                selectedId = selected,
                messages = if (selected != s.selectedId) emptyList() else s.messages,
            )
        }
        // MODEL-007／008 review fix：selected context 由「實際被選中的連線」推導，涵蓋
        // 首次 auto-select、save 後自動選取、停用／刪除後的 fallback；必須在
        // refreshModelRows() 之前套用，否則 rows 仍用舊 key 計算。
        applyContextFromSelection(recompute = false)
        // 注意：refresh() 不再在這裡主動取消在途模型動作。所有合法取消都已有直連路徑
        // （selectInternal 切換／setCredentialEnabled／deleteCredential join／
        // leaveProviderDetail／applySelectedContext 真實 context 變更），而動作體內的
        // ensureActive＋providerModelScopeIsCurrent 檢查點守住了每一個副作用之前
        // （送 request 前、promote／availability 寫入前、回報 SUCCESS 前），fail-closed。
        // 之前這裡的 backstop 會把 Room re-emission 瞬間的「證據不足」誤當 scope 改變，
        // 在 CI device 上造成毫秒級 spurious cancel（calls=0／refreshToken=0）。
        refreshModelRows()
        ensureSyncSupport(
            (connectionCache + managementCache + pendingById.values).map { it.providerId }.toSet(),
        )
    }

    /**
     * P1：判定 provider 是否支援 New API 手動同步（provider_config.authMode==PAT_ACCOUNT 且含 ACCOUNT ref）。
     * 無 config（saveConnection 舊列 / pending）、decode 失敗、非 PAT_ACCOUNT 一律 false（fail-closed 隱藏按鈕）。
     * 只讀 metadata，不碰 vault。
     */
    private suspend fun isSyncSupported(providerId: String): Boolean {
        if (providerId.isBlank()) return false
        val entity = try {
            app.database.providerConfigDao().getByProvider(providerId)
        } catch (_: Exception) {
            return false
        } ?: return false
        val config = try {
            entity.toManualConfig()
        } catch (_: Exception) {
            return false
        }
        if (config.authMode != AuthMode.PAT_ACCOUNT) return false
        return config.credentials.any { it.role == CredentialRole.ACCOUNT && it.safeCredentialId.isNotBlank() }
    }

    /**
     * P1：按可見 providerIds 增量回填 syncSupportedCache；缺失者 IO 查詢，失效者直接剔除。
     * 完成後觸發一次 refresh() 以更新 supportsSync（此時缺失已補齊，不會迴圈）。
     * 只由同步按鈕可見性使用，不做背景自動同步。
     */
    private fun ensureSyncSupport(providerIds: Set<String>) {
        val cleanIds = providerIds.filter { it.isNotBlank() }.toSet()
        val stale = syncSupportedCache - cleanIds
        syncEvaluatedCache = syncEvaluatedCache.intersect(cleanIds)
        val missing = cleanIds - syncEvaluatedCache
        if (stale.isNotEmpty()) {
            syncSupportedCache = syncSupportedCache - stale
        }
        if (missing.isEmpty()) {
            if (stale.isNotEmpty()) refresh()
            return
        }
        syncSupportJob?.cancel()
        syncSupportJob = viewModelScope.launch {
            val capable = syncSupportedCache.toMutableSet()
            for (pid in missing) {
                try {
                    val ok = withContext(Dispatchers.IO) { isSyncSupported(pid) }
                    if (ok) capable.add(pid) else capable.remove(pid)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // fail-closed：查詢失敗視為不支援，不顯示按鈕。
                }
            }
            syncSupportedCache = capable
            syncEvaluatedCache = syncEvaluatedCache + missing
            refresh()
        }
    }

    /**
     * P0-4 send 用 auth 載入：讀 provider_config，decode 後回傳 authMode + customFields + hasInference。
     * Custom auth 只傳 refs（safeCredentialId + location + name + overrideConfirmed），ACCOUNT role 排除；
     * 值永不讀出（不碰 vault）。load 失敗（DB 例外 / decode 錯配）以 loadFailed=true 回傳，
     * 由 send fail-closed 拒發，不以空 list 蒙混。Legacy 無 config（saveConnection 舊列）視為
     * authMode=null、custom 空、hasInference=true（允許既有 Bearer 行為），不阻擋。
     */
    private data class SendAuth(
        val authMode: AuthMode?,
        val customFields: List<InferenceAuthField>,
        val hasInference: Boolean,
        val loadFailed: Boolean,
    )

    /**
     * 同 provider custom-auth 兄弟 refs（排除主 ref 與 PAT）：供刪除時一併清理 vault，
     * 使用 `customAuthCleanupRefs`（PAT 永不列入）。config 缺失／decode 失敗回空集合，
     * 呼叫端視為無兄弟可清（主 ref 照常刪除），不因清理查詢失敗而阻擋刪除。
     */
    private suspend fun customSiblingRefs(providerId: String, mainRefId: String): List<String> {
        if (providerId.isBlank() || mainRefId.isBlank()) return emptyList()
        return try {
            val entity = app.database.providerConfigDao().getByProvider(providerId) ?: return emptyList()
            val config = try {
                entity.toManualConfig()
            } catch (_: Exception) {
                return emptyList()
            }
            customAuthCleanupRefs(config).filter { it != mainRefId }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun loadSendAuth(providerId: String): SendAuth {
        if (providerId.isBlank()) return SendAuth(null, emptyList(), false, true)
        val entity = try {
            app.database.providerConfigDao().getByProvider(providerId)
        } catch (_: Exception) {
            return SendAuth(null, emptyList(), false, true)
        } ?: return SendAuth(null, emptyList(), true, false)
        val config = try {
            entity.toManualConfig()
        } catch (_: Exception) {
            return SendAuth(null, emptyList(), false, true)
        }
        val accountIds = config.credentials
            .filter { it.role == CredentialRole.ACCOUNT }
            .map { it.safeCredentialId.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        // ACCOUNT role 排除（legacy 重疊 config 的防線；新 commit 已由 committer 拒絕重疊）。
        // 外來 ref 由 commit 端擋（未列入 snapshot.credentials 的 custom ref 不可在 DB 已存在，
        // 見 RoomCommitter）；此處只認本單是避免 legacy 髒資料把他人 PAT 送進推理。
        val fields = config.customAuthFields.mapNotNull { ref ->
            val refId = ref.safeCredentialId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (refId in accountIds) return@mapNotNull null
            val cleanName = ref.name.trim()
            if (cleanName.isEmpty()) return@mapNotNull null
            InferenceAuthField(
                name = cleanName,
                credentialId = refId,
                query = ref.location == CredentialLocation.QUERY,
                overrideConfirmed = ref.overrideConfirmed,
            )
        }.distinctBy { (if (it.query) "q:" else "h:") + it.name.lowercase() }
        val hasInference = config.credentials.any { it.role == CredentialRole.INFERENCE }
        return SendAuth(config.authMode, fields, hasInference, false)
    }

    /** Manual probes must not borrow an ACCOUNT PAT or disabled custom-auth sibling. */
    private suspend fun loadProviderModelAuth(record: ConnectionRecord): SendAuth {
        val auth = loadSendAuth(record.providerId)
        if (auth.loadFailed) return auth
        try {
            val entity = app.database.providerConfigDao().getByProvider(record.providerId)
                ?: return if (record.anonymous) auth.copy(loadFailed = true) else auth
            val config = entity.toManualConfig()
            val accountRefs = config.credentials.filter { it.role == CredentialRole.ACCOUNT }
                .map { it.safeCredentialId }.toSet()
            if (record.credentialId in accountRefs) return auth.copy(loadFailed = true)
            if (record.anonymous) {
                return if (config.authMode == AuthMode.NONE && auth.customFields.isEmpty()) auth
                else auth.copy(loadFailed = true)
            }
            val inferenceRefs = config.credentials.filter { it.role == CredentialRole.INFERENCE }
                .map { it.safeCredentialId }.toSet()
            val customOnly = (config.authMode == AuthMode.API_KEY || config.authMode == AuthMode.CUSTOM_HEADER) &&
                !auth.hasInference
            if (customOnly) {
                if (auth.customFields.none { it.credentialId == record.credentialId }) {
                    return auth.copy(loadFailed = true)
                }
            } else if (record.credentialId !in inferenceRefs) {
                return auth.copy(loadFailed = true)
            }
            for (field in auth.customFields) {
                if (field.credentialId in accountRefs) return auth.copy(loadFailed = true)
                val credential = app.database.credentialDao().getById(field.credentialId)
                    ?: return auth.copy(loadFailed = true)
                val owner = app.database.accountDao().getById(credential.accountId)
                    ?: return auth.copy(loadFailed = true)
                if (!credential.enabled || owner.providerId != record.providerId) {
                    return auth.copy(loadFailed = true)
                }
            }
            return auth
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return auth.copy(loadFailed = true)
        }
    }

    /** Persisted states are free-form strings; unknown values must render as UNKNOWN, never raw. */
    private fun safePriceState(raw: String): String =
        runCatching { PriceState.valueOf(raw) }.getOrDefault(PriceState.UNKNOWN).name

    private fun safeQuotaState(raw: String): String =
        runCatching { QuotaState.valueOf(raw) }.getOrDefault(QuotaState.UNKNOWN).name

    private fun safeHealth(raw: String): String =
        runCatching { HealthState.valueOf(raw) }.getOrDefault(HealthState.UNKNOWN).name

    private fun PerformanceSample.toItem(): SampleItem = SampleItem(
        id = id,
        providerId = providerId,
        modelId = providerModelId,
        outcome = outcome.name,
        startedAt = requestStartedAt,
        ttftMs = ttftMs,
        ttfbMs = ttfbMs,
        latencyMs = totalLatencyMs,
        outputTokens = usage.outputTokens,
        tps = tps,
        priceState = safePriceState(priceState),
        quotaState = safeQuotaState(quotaState),
    )

    private fun messageFor(outcome: Outcome): String = when (outcome) {
        Outcome.AUTH, Outcome.CREDENTIAL_SCOPE -> string(R.string.error_auth)
        Outcome.RATE_LIMIT -> string(R.string.error_rate_limit)
        Outcome.TIMEOUT -> string(R.string.error_timeout)
        Outcome.NETWORK -> string(R.string.error_network)
        Outcome.MODEL_UNAVAILABLE -> string(R.string.error_model_unavailable)
        Outcome.SERVER -> string(R.string.error_server)
        Outcome.CANCELLED -> string(R.string.error_cancelled)
        Outcome.PARSER -> string(R.string.error_parser)
        Outcome.INTERRUPTED -> string(R.string.error_interrupted)
        Outcome.SUCCESS -> string(R.string.error_request)
        Outcome.RUNNING, Outcome.OTHER -> string(R.string.error_request)
    }

    // ---- P6 worker C（B1 解除條件）：Dedicated 產品流（追加；既有 syncNewApi 語意不動） ----
    //
    // TRACEABILITY：P5 blocker B1＋MODEL-009。§9（capability 獨立缺失；single-flight
    // 與 syncNewApi 一致：同 provider 重入直接返回現行狀態）、§10／§11（失敗保留
    // last-good：本層不寫 snapshot，只收 Repository 摘要；錯誤只記分類代碼）。
    // 錯誤只存 AUTH／NETWORK／UNSUPPORTED／UNKNOWN 代碼（禁 URL／server body／
    // secret；secret 只以 ref 經 vault，不進 ViewModel）。成功記裝置本地時間
    // （device-local millis，UI 以裝置時區顯示）。不做背景自動同步。

    // P6-C 狀態已上移至 init 之前（初始化順序修正；此處僅保留說明，不重複聲明）。
    // 見頂部 _dedicatedSyncing／_dedicatedSyncError／_dedicatedLastSynced／
    // _dedicatedLatestObserved／_dedicatedAccountFallback／dedicatedSingleFlightMutex。

    /**
     * P6-C：同步鈕可見性 gating（查 Repository adapter capability；未知回 false，
     * fail-closed 不拋錯；純本地判定，不碰網路／vault）。
     * UUID 家族判定走 [dedicatedSupportsSyncResolved]（DB 維度）。
     */
    fun dedicatedSupportsSync(providerId: String): Boolean {
        if (providerId.isBlank()) return false
        return runCatching { app.repository.dedicatedSupportsSync(providerId) }.getOrDefault(false)
    }

    /**
     * P6-C BLOCKER1：UUID 家族 gating（suspend，讀 endpoint／provider_config，只讀）。
     * 空 id 直接 false 不寫鍵（MINOR9）；例外 fail-closed false。
     */
    suspend fun dedicatedSupportsSyncResolved(providerId: String): Boolean {
        if (providerId.isBlank()) return false
        return try {
            withContext(Dispatchers.IO) { app.repository.dedicatedSupportsSyncResolved(providerId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * P6-C：手動同步入口（供 Providers／Models 卡片按鈕觸發）。
     * MINOR1 single-flight：Mutex 內原子認領，已在集合直接返回；finally 移出集合。
     * MINOR9：空 providerId 直接返回，不寫 "" 錯誤鍵。
     * BLOCKER7：accountId／credentialId／group 全透傳至 Repository（多帳戶缺參由
     * Repository 拋錯不猜；單帳戶 fallback 由摘要註記）。
     * MAJOR5：Failure 在 Repository 層拋錯（不計 done）；本層僅成功觀測計 done
     * （unsupported／0 筆不計 done），兩維皆失敗才記錯誤，任一成功即記觸發時間＋
     * 最新觀測時間。MAJOR10：成功記 latestObservedAt（資料時間）與觸發時間分離。
     */
    fun syncDedicated(
        providerId: String,
        accountId: String? = null,
        credentialId: String? = null,
        group: String? = null,
    ) {
        if (providerId.isBlank()) return
        // CR-002 A3/A4：認領單位由 providerId 改為 op 鍵；同鍵重入即返，
        // 不同 scope（同 provider 不同 account／credential／group）不折疊、不吞併。
        // provider 聚合 busy（_dedicatedSyncing）由 op 鍵集合唯讀推導，僅供 face spinner。
        val opKey = dedicatedOpKey(providerId, accountId, credentialId, group)
        if (opKey in _dedicatedOpSyncing.value) return
        if (_state.value.busy || _state.value.saving) {
            _dedicatedSyncError.update { it + (providerId to "UNKNOWN") }
            // CR-002：busy 只記「被嘗試的 scope」，不污染其他 op 鍵。
            _dedicatedOpError.update { it + (opKey to "UNKNOWN") }
            return
        }
        viewModelScope.launch {
            val claimed = dedicatedSingleFlightMutex.withLock {
                if (opKey in _dedicatedOpSyncing.value) {
                    false
                } else {
                    _dedicatedOpSyncing.update { it + opKey }
                    refreshDedicatedProviderBusyAggregate()
                    true
                }
            }
            if (!claimed) return@launch
            _dedicatedSyncError.update { it - providerId }
            _dedicatedOpError.update { it - opKey }
            try {
                var pricingCode: String? = null
                var quotaCode: String? = null
                var pricingDone = false
                var quotaDone = false
                var pricingUnsupportedFlag = false
                var quotaUnsupportedFlag = false
                var observedAt: Long? = null
                var fallbackUsed = false
                try {
                    // F1（REVIEW-MYM004 finding）：pricing scope 永不帶 credential
                    //（repository 先行拒絕）；quota 維度才用 credential。此處剝掉，
                    // 否則每次帶 credential 的自動 enrichment pricing 維度在發請求
                    // 前即被拒，pricing 永遠抓不到。
                    val summary = withContext(Dispatchers.IO) {
                        app.repository.syncDedicatedPricing(providerId, accountId, null, group)
                    }
                    fallbackUsed = fallbackUsed || summary.accountFallbackUsed
                    summary.latestObservedAtMillis?.let {
                        observedAt = maxOf(observedAt ?: it, it)
                    }
                    // MAJOR5／MAJOR7：unsupported 或 0 筆不計 done（不謊稱 synced）。
                    // Unsupported 為能力降級（跳過），非錯誤：不設 code，免得單維成功卻顯示錯誤。
                    if (!summary.pricingUnsupported && summary.pricingSynced > 0) {
                        pricingDone = true
                    } else if (summary.pricingUnsupported) {
                        pricingUnsupportedFlag = true
                    } else {
                        pricingCode = "UNKNOWN"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pricingCode = classifyDedicatedError(e)
                }
                try {
                    val summary = withContext(Dispatchers.IO) {
                        app.repository.syncDedicatedQuotaPools(providerId, accountId, credentialId, group)
                    }
                    fallbackUsed = fallbackUsed || summary.accountFallbackUsed
                    summary.latestObservedAtMillis?.let {
                        observedAt = maxOf(observedAt ?: it, it)
                    }
                    if (!summary.quotaUnsupported && summary.quotaSynced > 0) {
                        quotaDone = true
                    } else if (summary.quotaUnsupported) {
                        quotaUnsupportedFlag = true
                    } else {
                        if (quotaCode == null) quotaCode = "UNKNOWN"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    quotaCode = classifyDedicatedError(e)
                }
                if (pricingDone || quotaDone) {
                    val triggerMillis = System.currentTimeMillis()
                    // CR-002：op 鍵級寫入（per-card 狀態；不跨 scope 冒充）。
                    _dedicatedOpLastSynced.update { it + (opKey to triggerMillis) }
                    observedAt?.let { ts ->
                        _dedicatedOpLatestObserved.update { it + (opKey to ts) }
                    }
                    // provider 級 map coexist（既有契約保留；僅 provider 聚合語意，
                    // 不得被 per-card 讀取冒充 exact scope）。
                    _dedicatedLastSynced.update { it + (providerId to triggerMillis) }
                    observedAt?.let { ts ->
                        _dedicatedLatestObserved.update { it + (providerId to ts) }
                    }
                    _dedicatedAccountFallback.update { it + (providerId to fallbackUsed) }
                    refreshModelRows(announceLoading = false)
                    _detailEvidenceVersion.update { it + 1L }
                }
                val code = pricingCode ?: quotaCode
                if (!pricingDone && !quotaDone) {
                    // 兩維皆降級（無失敗）即 UNSUPPORTED；否則為失敗碼。
                    val bothUnsupported = pricingUnsupportedFlag && quotaUnsupportedFlag
                    val finalCode = code ?: if (bothUnsupported) "UNSUPPORTED" else "UNKNOWN"
                    if (code != null || bothUnsupported) {
                        _dedicatedSyncError.update { it + (providerId to finalCode) }
                        _dedicatedOpError.update { it + (opKey to finalCode) }
                    }
                } else if (code != null) {
                    // 部分成功仍保留可見分類（UI 同時顯示錯誤行與觸發時間，不掩蓋）。
                    // Unsupported 降級不計入 code（上已排除），此處僅真失敗才顯示。
                    _dedicatedSyncError.update { it + (providerId to code) }
                    _dedicatedOpError.update { it + (opKey to code) }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                // CR-002：取消／結束只清自己的 op 鍵；同 provider 其他 op 不受影響，
                // provider 聚合 busy 由剩餘 op 鍵唯讀重導（仍有他 op 時 face spinner 保持 busy）。
                // 取消路徑在兩維寫入前即拋出，不寫本鍵 error／lastSynced／latestObserved。
                _dedicatedOpSyncing.update { it - opKey }
                refreshDedicatedProviderBusyAggregate()
            }
        }
    }

    /**
     * CR-002 A3/A4：exact scope 操作狀態選擇器（純讀取四個 op 鍵級 flow）。
     * per-card／sheet／狀態行必須經此（或對應 op 鍵 flow）讀取；
     * 不得以 provider 聚合（[dedicatedSyncing]／[dedicatedSyncError] 等）冒充 exact scope。
     */
    fun dedicatedOpState(
        providerId: String,
        accountId: String?,
        credentialId: String?,
        group: String?,
    ): DedicatedOpState {
        val key = dedicatedOpKey(providerId, accountId, credentialId, group)
        return DedicatedOpState(
            syncing = key in _dedicatedOpSyncing.value,
            error = _dedicatedOpError.value[key],
            lastSynced = _dedicatedOpLastSynced.value[key],
            latestObserved = _dedicatedOpLatestObserved.value[key],
        )
    }

    /**
     * CR-002 A3/A4：provider 聚合 busy（face spinner 用「該 provider 是否有任何 op 在跑」）。
     * 唯讀推導自 op 鍵集合；錯誤／lastSynced／latestObserved 不得使用此聚合。
     */
    private fun refreshDedicatedProviderBusyAggregate() {
        _dedicatedSyncing.value = _dedicatedOpSyncing.value
            .map { dedicatedOpKeyProvider(it) }
            .filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    /**
     * P6-C：失敗分類（只回代碼，不攜帶 URL／server body／secret）。
     * 缺 scope／未知能力→UNSUPPORTED（對應「尚未設定同步範圍，無法同步」）；
     * 憑證歸屬→AUTH；傳輸層→NETWORK；其餘→UNKNOWN。
     */
    private fun classifyDedicatedError(e: Throwable): String {
        val msg = (e.message.orEmpty() + " " + e::class.java.simpleName).lowercase()
        if (e is java.io.IOException) return "NETWORK"
        if ("auth" in msg || "credential" in msg || "401" in msg || "403" in msg) return "AUTH"
        if ("network" in msg || "timeout" in msg || "unreachable" in msg || "tls" in msg ||
            "unknownhost" in msg || "connect" in msg
        ) {
            return "NETWORK"
        }
        if ("unsupported" in msg || "capability" in msg || "required" in msg ||
            "not found" in msg || "no provider" in msg || "missing" in msg || "scope" in msg
        ) {
            return "UNSUPPORTED"
        }
        return "UNKNOWN"
    }

    // ---- P6 INT-2（UI 接線）：Models 全量列＋selected-key 投影（追加；既有 state／sync 語意不動） ----
    //
    // TRACEABILITY：MODEL-001…007／009。資料只讀既有 store／DAO／snapshot head；缺失維度由
    // ModelRowMapper 填 UNKNOWN／空集合，不捏造。FREE 判定只吃 priceState（PriceState enum），
    // 不由 model 名稱／id 推導。refresh 失敗保留 last-good（不清空），與 sync last-good 一致。

    // P6 INT-2 狀態已上移至 init 之前（初始化順序修正；此處僅保留說明）。
    // 見頂部 _allModelRows／_selectedAccountId／_selectedCredentialId／_selectedGroup。

    /**
     * MODEL-007／008 review fix：selected context 只由真實 [ConnectionRecord] 推導。
     *
     * 單一真相是 `_state.selectedId`：context 是它的投影，不再是各自獨立的 flow。
     * - `credentialId` 只放真實 safe ref；匿名列（anonymous 或 credentialId 空白）與
     *   已停用／本機封鎖的 key 一律回 null（不捏造、不補 0、不拿別的 key 頂替），
     *   Compare／usable 投影因此維持 fail-closed。
     * - 連線未帶 group 時保持 null；明確指定的 group 只在同一 key 內保留，不是 wildcard。
     */
    private data class SelectedContext(
        val accountId: String?,
        val credentialId: String?,
        val group: String?,
    ) {
        fun sameAs(other: SelectedContext): Boolean =
            accountId == other.accountId && credentialId == other.credentialId && group == other.group
    }

    /**
     * `selectModelKey` 明確指定的 group（連線層記錄沒有 group 維度）。
     *
     * 同一個 (account, credential) 內沿用；key 一旦改變或被清空即丟棄，
     * 避免 refresh 時把使用者的明確選擇靜默抹成 null。null 一律代表「未指定」，
     * 不得當成跨 group wildcard。
     */

    private fun setExplicitGroup(accountId: String?, credentialId: String?, group: String?) {
        val clean = group?.takeIf { it.isNotBlank() }
        if (clean == null) {
            clearExplicitGroup()
            return
        }
        explicitGroup = clean
        explicitGroupKey = accountId to credentialId
    }

    private fun clearExplicitGroup() {
        explicitGroup = null
        explicitGroupKey = null
    }

    /** 依 id 找真實連線記錄（managed 優先；pending 為尚未落 Room 的新連線）。 */
    private fun recordFor(connectionId: String?): ConnectionRecord? {
        val id = connectionId?.takeIf { it.isNotBlank() } ?: return null
        return managementCache.firstOrNull { it.id == id }
            ?: connectionCache.firstOrNull { it.id == id }
            ?: pendingById[id]
    }

    /** 是否有可用的真實 credential；匿名連線不捏造 selected credential。 */
    private fun isContextCredentialUsable(record: ConnectionRecord): Boolean {
        if (record.anonymous || record.credentialId.isBlank()) return false
        return record.credentialEnabled && record.credentialId !in blockedCredentialIds
    }

    /** 由已選連線推導 context；查無連線或不可用即全 null（clear，並清掉 explicit group）。 */
    private fun contextForSelection(): SelectedContext {
        val record = recordFor(_state.value.selectedId)
        if (record == null || !isContextCredentialUsable(record)) {
            clearExplicitGroup()
            return SelectedContext(null, null, null)
        }
        val account = record.accountId.takeIf { it.isNotBlank() }
        val credential = record.credentialId
        val stickyKey = explicitGroupKey
        val group = if (stickyKey != null && stickyKey == (account to credential)) {
            explicitGroup
        } else {
            // 換 key（含由 null 開始）即不沿用舊 group。
            clearExplicitGroup()
            null
        }
        return SelectedContext(accountId = account, credentialId = credential, group = group)
    }

    private fun currentContext(): SelectedContext =
        SelectedContext(_selectedAccountId.value, _selectedCredentialId.value, _selectedGroup.value)

    /**
     * 套用 context；內容有變才動作。變更即取消在途比測並清空舊結果／錯誤：
     * 舊 key 的 async 結果不得出現在新 key 的對照表（MODEL-008 scope 隔離）。
     * 同時清掉舊 key 的 rows：不同 key 的列不得被當成當前 key 的可用資料。
     */
    private fun applySelectedContext(context: SelectedContext, recompute: Boolean) {
        if (currentContext().sameAs(context)) return
        cancelProviderModelAction()
        _selectedAccountId.value = context.accountId
        _selectedCredentialId.value = context.credentialId
        _selectedGroup.value = context.group
        compareToken += 1
        compareJob?.cancel()
        compareJob = null
        _compareRows.value = emptyList()
        _compareRunning.value = false
        _compareError.value = null
        resetCompareChoices()
        // 換 key 即丟棄上一把 key 算出的列（last-good 只在同一 key 內保留）。
        invalidateModelRows()
        if (recompute) refreshModelRows()
    }

    /** 由 `_state.selectedId` 同步 context（auto-select／使用者切換／save／停用刪除共用）。 */
    private fun applyContextFromSelection(recompute: Boolean) = applySelectedContext(contextForSelection(), recompute)

    /**
     * P6 INT-2（review fix）：切換 selected key（account／credential／group）。
     *
     * - 只接受對應真實 [ConnectionRecord] 的 (account, credential)；查無、匿名或已停用的 key
     *   不建立 context（fail-closed，不捏造 safe ref），退回由已選連線推導的值。
     * - 命中時一併切換到該連線，使 context 與 selectedId 保持同一真相。
     * - group 明確指定後，於同一個 key 內被後續 refresh 沿用；換 key 即清（見 [setExplicitGroup]）。
     * - 切換即取消在途比測並清空舊結果，避免舊 key 的結果污染新 key。
     */
    fun selectModelKey(accountId: String?, credentialId: String?, group: String? = null) {
        val wantAccount = accountId?.takeIf { it.isNotBlank() }
        val wantCredential = credentialId?.takeIf { it.isNotBlank() }
        val known = (managementCache + connectionCache + pendingById.values).distinctBy { it.id }
        val match = known.firstOrNull { record ->
            wantCredential != null && record.credentialId == wantCredential &&
                (wantAccount == null || record.accountId == wantAccount) &&
                isContextCredentialUsable(record)
        }
        if (match != null) {
            // 先記住 group 再切換，讓這一次套用就帶上 group（不需二次寫入）。
            setExplicitGroup(
                match.accountId.takeIf { it.isNotBlank() },
                match.credentialId,
                group,
            )
            selectInternal(match.id)
            // selectInternal 在同一 id 時會早退，這裡補一次（內容未變時為 no-op）。
            applyContextFromSelection(recompute = true)
        } else {
            // 不可驗證的 key 不進 context，也不保留 group：退回已選連線推導值。
            clearExplicitGroup()
            applyContextFromSelection(recompute = true)
        }
    }

    /** 作廢目前 rows（含在途查詢）：換 key 時不得沿用上一把 key 的列。 */
    private fun invalidateModelRows() {
        modelRowsToken += 1
        modelRowsJob?.cancel()
        modelRowsJob = null
        modelRowsScope = null
        modelRowsDirty = false
        modelRowsPendingAnnounce = false
        _allModelRows.value = emptyList()
        // FR-002：pending 列同樣帶 `group = selected.group` 與 `selectedKeyUsable`
        // （ModelRowMapper buildRows 分流點），是 scope 相關資料；不清就是上一把 key
        // 的投影外洩到新 key。`modelRowsMembershipUsed` **不清**：membership 未變，
        // 清了會讓下一次 collector 比較誤判而多跑一次無謂 load。
        _pendingMemberRows.value = emptyList()
        _modelRowsLoading.value = false
        _modelRowsLoadFailed.value = false
    }

    /**
     * P6 INT-2（review fix）：重算 [allModelRows]。
     *
     * - 同時只允許一次在途查詢：scope 改變時取消前一個 job 並使前一個 token 失效；
     *   scope 相同時記一次 dirty、於前一次完成後重跑（不被無關的 Room emission 反覆重啟而
     *   永不回填，也不會因查詢期間新增的 offering 而漏算）。
     * - 回填前雙重核對：token 仍是最新，且請求 scope 仍等於目前 selected key；
     *   任一不符即丟棄該批 rows，避免 A→B 切換後 A 的慢查詢覆寫 B 的列。
     * - 失敗保留 last-good（不清空既有列）；取消（[CancellationException]）一律傳播，
     *   不以失敗處理，也不回填。
     */
    fun refreshModelRows(
        accountId: String? = _selectedAccountId.value,
        credentialId: String? = _selectedCredentialId.value,
        group: String? = _selectedGroup.value,
        announceLoading: Boolean = true,
    ) {
        val scope = SelectedContext(accountId, credentialId, group)
        val running = modelRowsJob
        if (running != null && running.isActive) {
            if (modelRowsScope == scope) {
                modelRowsDirty = true
                if (announceLoading) modelRowsPendingAnnounce = true
                return
            }
            // scope 改變：取消在途查詢並立即使舊 token 失效（舊結果不得回填）。
            modelRowsToken += 1
            running.cancel()
        }
        startModelRowsLoad(scope, announceLoading)
    }

    private fun startModelRowsLoad(scope: SelectedContext, announceLoading: Boolean) {
        val token = ++modelRowsToken
        modelRowsScope = scope
        modelRowsDirty = false
        modelRowsPendingAnnounce = false
        if (announceLoading) {
            _modelRowsLoading.value = true
            _modelRowsLoadFailed.value = false
        }
        modelRowsJob = viewModelScope.launch {
            try {
                val load = withContext(Dispatchers.IO) {
                    loadModelRows(scope.accountId, scope.credentialId, scope.group)
                }
                if (token != modelRowsToken) return@launch
                if (scope != currentContext()) return@launch
                // Main(immediate) 單線程：以下三行之間無 suspension point，對任何
                // collector 而言不可分割——不會出現「pending 已回填、all 還沒有」
                // （或反向）的中間狀態。失敗時三行都不執行：兩清單 last-good 對稱。
                _allModelRows.value = load.visible
                _pendingMemberRows.value = load.pendingMember
                modelRowsMembershipUsed = load.membershipUsed
                // S10: refresh only the still-open full identity; absence keeps its last row.
                val detailRow = _modelDetailRow.value
                if (detailRow != null) {
                    val freshRow = (load.visible + load.pendingMember).firstOrNull {
                        it.providerId == detailRow.providerId &&
                            it.canonicalId == detailRow.canonicalId &&
                            it.providerModelId == detailRow.providerModelId
                    }
                    if (freshRow != null && freshRow != detailRow) _modelDetailRow.value = freshRow
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失敗保留 last-good（不清空既有列）。
                if (announceLoading && token == modelRowsToken && scope == currentContext()) {
                    _modelRowsLoadFailed.value = true
                }
            } finally {
                // 查詢期間又有同 scope 的請求：完成後補跑一次，吸收期間的變更。
                if (modelRowsDirty && token == modelRowsToken && scope == modelRowsScope) {
                    val announce = modelRowsPendingAnnounce
                    // End this run's spinner before handing off to a silent rerun.
                    if (announceLoading && !announce) _modelRowsLoading.value = false
                    startModelRowsLoad(scope, announce)
                } else if (announceLoading && token == modelRowsToken) {
                    _modelRowsLoading.value = false
                }
            }
        }
    }

    /** FR-002：單次 load 的結果（含本次實際使用的 membership 集合）。 */
    private data class ModelRowsLoad(
        val visible: List<ModelRow>,
        val pendingMember: List<ModelRow>,
        val membershipUsed: Set<MyModelKey>,
    )

    private suspend fun loadModelRows(
        accountId: String?,
        credentialId: String?,
        group: String?,
    ): ModelRowsLoad {
        val db = app.database
        val records = (connectionCache + managementCache).distinctBy { it.id }
        val providerIds = records.map { it.providerId }.filter { it.isNotBlank() }.distinct()
        val offerings = providerIds.flatMap { db.offeringDao().byProvider(it) }.distinctBy { it.id }
        val credentialIds = buildSet {
            records.map { it.credentialId }.filter { it.isNotBlank() }.forEach { add(it) }
            credentialId?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val availabilities = credentialIds
            .flatMap { db.availabilityDao().byCredential(it) }
            .distinctBy { it.id }

        val canonicalStore = CanonicalStore(db)
        val offeringRefs = ArrayList<OfferingRef>(offerings.size)
        val canonicals = LinkedHashMap<String, CanonicalRef>()
        val aliases = LinkedHashSet<AliasRef>()
        val classifications = ArrayList<ClassificationRef>()
        for (offering in offerings) {
            val protocol = runCatching { db.endpointDao().getById(offering.endpointId)?.protocol }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "CHAT_COMPLETIONS"
            offeringRefs += OfferingRef(
                id = offering.id,
                providerId = offering.providerId,
                endpointId = offering.endpointId,
                providerModelId = offering.providerModelId,
                operation = protocol,
                manual = offering.manual,
                source = offering.source,
                hidden = offering.hidden,
                verified = offering.verificationEvidenceId != null,
            )
            val alias = runCatching {
                db.providerModelAliasDao().get(offering.providerId, offering.providerModelId)
            }.getOrNull()
            val canonicalId = alias?.canonicalModelId ?: canonicalIdFor(offering.providerModelId)
            runCatching { db.canonicalModelDao().getById(canonicalId) }.getOrNull()?.let {
                canonicals.putIfAbsent(it.canonicalModelId, CanonicalRef(it.canonicalModelId, it.displayName))
            }
            runCatching { db.providerModelAliasDao().byCanonical(canonicalId) }.getOrNull()
                ?.forEach { aliases += AliasRef(it.providerId, it.providerModelId, it.canonicalModelId) }
            runCatching { canonicalStore.classificationsOf(canonicalId) }.getOrNull()
                ?.forEach { details ->
                    classifications += ClassificationRef(
                        canonicalId = details.classification.canonicalModelId,
                        source = details.classification.source,
                        primaryCategory = details.classification.primaryCategory,
                        capabilities = details.capabilities,
                        modalities = details.modalities,
                    )
                }
        }

        val offeringRefsById = offeringRefs.associateBy { it.id }
        // FR-002 membership 來源：**同一個 IO 區塊內的點讀**，不是 `myModelKeys.value`。
        // Room observe 是 StateFlow(Eagerly)，VM 剛建構時 `.value` 可能仍是 emptySet
        // （初始 emission 未到達）——用它會讓首批 pending 列缺失且無補跑機會。
        // `all()` 是當下 DB 真值，與同區塊內的 offering 讀取構成同一次 load 的輸入。
        //
        // 讀取順序 **offerings 先、membership 後**（LOCK-B 鎖定形狀）：與既有 visible
        // 列的讀取位置完全同位，兩清單的 point-in-time staleness 語意對稱。ADD 兩段
        // 寫入（offering＋evidence 先、membership 後）若整段落在本讀取與 offerings
        // 點讀之間，會出現「membership 已見、offering 未見」的一次 load 視窗——與
        // 既有 visible 列對新增 offering 的 staleness 同型，由 ADD 路徑自身的
        // `refreshModelRows()`（VM:938）收斂；collector 不收斂它（marker 已含該 key、
        // Room emission 集合與 marker 相同）。本讀取**零寫入**：load 不得寫
        // `my_models`，否則 collector 會被自己的 load 觸發（自觸發迴圈），也違反
        // Catalog ≠ My Models。
        val memberKeys: Set<MyModelKey> = db.myModelsDao().all()
            .mapTo(LinkedHashSet()) { MyModelKey(it.providerId, it.providerModelId) }

        val partition = ModelRowMapper.buildPartitioned(
            offerings = offeringRefs,
            availabilities = availabilities.map {
                val matchedOffering = offeringRefsById[it.offeringId]
                AvailabilityRef(
                    id = it.id,
                    offeringId = it.offeringId,
                    accountId = it.accountId,
                    credentialId = it.credentialId,
                    group = it.group,
                    endpointId = matchedOffering?.endpointId,
                    operation = matchedOffering?.operation,
                    priceState = it.priceState,
                    quotaState = it.quotaState,
                )
            },
            selected = SelectedKey(accountId ?: "", credentialId ?: "", group),
            canonicals = canonicals.values.toList(),
            aliases = aliases.toList(),
            classifications = classifications,
            priceQuotaHeads = loadPriceQuotaHeads(db),
            includeHiddenFor = memberKeys,
        )
        return ModelRowsLoad(partition.visible, partition.pendingMember, memberKeys)
    }

    private suspend fun loadPriceQuotaHeads(db: AtlasDatabase): List<PriceQuotaHead> {
        val kinds = SnapshotKind.entries.map { it.name }
        val priceKinds = kinds.filter {
            it == SnapshotKind.PRICE.name || it == SnapshotKind.MANUAL_PRICE.name ||
                it == SnapshotKind.DEDICATED_PRICE.name
        }
        check(kinds.isNotEmpty() && priceKinds.isNotEmpty()) // Production enum sets are non-empty.
        val rows = db.snapshotFactDao().priceQuotaHeadProjections(kinds, priceKinds)
        val out = ArrayList<PriceQuotaHead>(rows.size)
        for (row in rows) {
            val parts = parseScopeKey(row.headScopeKey) ?: continue
            out += PriceQuotaHead(
                kind = row.headKind,
                providerId = parts[0] ?: "",
                accountId = parts[1],
                credentialId = parts[2],
                modelId = parts[3] ?: "",
                group = parts[4],
                endpointId = parts[5],
                operation = parts[6],
                state = row.latestState,
                inputPrice = row.lastGoodInput?.toBigDecimalOrNull()?.toDouble(),
                outputPrice = row.lastGoodOutput?.toBigDecimalOrNull()?.toDouble(),
                cachePrice = row.lastGoodCacheRead?.toBigDecimalOrNull()?.toDouble(),
                observedAt = row.latestFactObservedAt,
            )
        }
        // CR-011: slice enumeration merged in Kotlin. CR-010 guarantees preserved:
        // no LIKE / no scope pruning / no GROUP BY / no kind winner; parseScopeKey
        // fail-closed per row; price legs stay null (price gate: quota heads never
        // take price numbers); source separation untouched (consensus policy in
        // ModelRowMapper.build unchanged). Slice rows appended in
        // (scopeKey, kind, quotaSlice) order.
        val quotaKinds = listOf(
            SnapshotKind.ACCOUNT_QUOTA.name,
            SnapshotKind.TOKEN_QUOTA.name,
            SnapshotKind.DEDICATED_ACCOUNT_QUOTA.name,
            SnapshotKind.DEDICATED_TOKEN_QUOTA.name,
        )
        val sliceRows = db.snapshotFactDao().slicePriceQuotaHeadProjections(quotaKinds)
            .sortedWith(compareBy({ it.headScopeKey }, { it.headKind }, { it.headQuotaSlice }))
        for (row in sliceRows) {
            val parts = parseScopeKey(row.headScopeKey) ?: continue
            out += PriceQuotaHead(
                kind = row.headKind,
                providerId = parts[0] ?: "",
                accountId = parts[1],
                credentialId = parts[2],
                modelId = parts[3] ?: "",
                group = parts[4],
                endpointId = parts[5],
                operation = parts[6],
                state = row.latestState,
                inputPrice = null,
                outputPrice = null,
                cachePrice = null,
                observedAt = row.headLatestObservedAt,
                quotaSlice = row.headQuotaSlice,
            )
        }
        return out
    }

    // ---- P6 session DC（UI 入口接線）：detail/compare 最小 state（追加；既有 sync/search/filter 語意不動） ----
    //
    // TRACEABILITY：MODEL-008（Compare Live 入口）／MODEL-009（model detail 入口）。
    // Spec：PRODUCT_SPEC §8（Compare Live）、§10（FREE 判定／未知明示）。
    // - 只存 UI 選擇＋比測顯示態；不讀 snapshot_heads／facts、不做 DB 映射
    //   （完整餵入由後續 worker 補，見 ModelDetailUi／CompareLiveUi 插入點建議）。
    // - FREE 只吃 ModelRow.priceState 透傳；UNKNOWN 維持 UNKNOWN，不推導、不填 0。
    // - 不做背景自動；compare 不偽造結果（初始空列＋未運行）。

    /** P6-DC：目前點選的 detail 列（null＝列表態；非 null＝detail 態，UI 顯示返回）。 */

    /** P6-DC：列點選進 detail；切換 canonical 即清空舊比測列，避免跨模型污染。 */
    fun openModelDetail(row: ModelRow) {
        cancelCompareLive()
        _modelDetailRow.value = row
        _compareRows.value = emptyList()
        _compareError.value = null
        resetCompareChoices()
        refreshCompareChoices(row)
    }

    /** P6-DC：detail 返回列表；比測顯示態一併清空。 */
    fun closeModelDetail() {
        cancelCompareLive()
        _modelDetailRow.value = null
        _compareRows.value = emptyList()
        _compareError.value = null
        resetCompareChoices()
    }

    /**
     * P6-DC：detail 畫面合成態。只透傳 row 的 price／quota／效能欄；
     * 價格歷史／配額池／歷史事件為空＝未知（未知明示，不捏造、不填零）。
     */
    fun modelDetailStateFor(row: ModelRow): ModelDetailState = ModelDetailStateBuilder.build(
        canonicalId = row.canonicalId,
        displayName = row.displayName,
        priceState = row.priceState,
        quotaState = row.quotaState,
        performance = DetailPerformance(
            success1h = row.success1h,
            success24h = row.success24h,
            tps = row.tps,
            ttftMs = row.ttftMs,
            latencyMs = row.latencyMs,
        ),
    )

    /**
     * P6 repair：Compare Live 真執行入口（adverse audit 空函式修復）。
     * 同一 canonical＋相同小 prompt 經 [CompareLiveRunner.run]（唯一 [InferenceEngine] 入口，
     * source＝COMPARE_LIVE），逐列回填 [compareRows]；Room feed 讀回 final samples 帶 TTFT/TPS，
     * 對不上為 null＝未知。取消傳播（不收斂為列失敗）；其餘失敗記分類碼。重入取消前一次。
     * selected key 由真實 ConnectionRecord 推導（見 [applySelectedContext]）；比測期間 key 被換掉
     * 則丟棄結果，不把舊 key 的對照掛到新 key。
     */
    private fun compareContextStamp(): CompareContextStamp = CompareContextStamp(
        _state.value.selectedId, _selectedAccountId.value, _selectedCredentialId.value, _selectedGroup.value,
    )

    private fun resetCompareChoices() {
        compareChoicesToken++
        _compareChoices.value = emptyList()
        _compareSelections.value = emptySet()
        _compareChoicesLoading.value = false
        _compareChoicesLoadFailed.value = false
        compareSelectionInitialized = false
        compareChoiceContext = null
    }

    fun refreshCompareChoices(row: ModelRow) {
        if (row.canonicalId.isBlank()) return
        val token = ++compareChoicesToken
        val stamp = compareContextStamp()
        val primary = recordFor(stamp.selectedId)
        val modelRows = _allModelRows.value.toList()
        _compareChoicesLoading.value = true
        _compareChoicesLoadFailed.value = false
        viewModelScope.launch {
            try {
                val choices = withContext(Dispatchers.IO) {
                    CompareLiveWiring.loadChoicesFromDb(app.database, row, modelRows)
                }.map { choice ->
                    if (primary != null && stamp.group != null &&
                        choice.selection.providerId == primary.providerId &&
                        choice.selection.accountId == primary.accountId &&
                        choice.selection.credentialId == primary.credentialId) {
                        choice.copy(selection = choice.selection.copy(group = stamp.group))
                    } else choice
                }.distinctBy { it.selection }
                if (token != compareChoicesToken || stamp != compareContextStamp() ||
                    _modelDetailRow.value?.canonicalId != row.canonicalId) return@launch
                val allowed = choices.map { it.selection }.toSet()
                val previous = _compareSelections.value
                val selected = if (!compareSelectionInitialized || compareChoiceContext != stamp) {
                    // Seed only the already-selected real connection. No foreign-key fallback.
                    choices.firstOrNull { choice -> primary != null &&
                        choice.selection.providerId == primary.providerId &&
                        choice.selection.accountId == primary.accountId &&
                        choice.selection.credentialId == primary.credentialId.takeIf { it.isNotBlank() }
                    }?.let { setOf(it.selection) }.orEmpty()
                } else previous.intersect(allowed)
                if (compareSelectionInitialized && selected != previous) {
                    cancelCompareLive()
                    _compareRows.value = emptyList()
                }
                _compareChoices.value = choices
                _compareSelections.value = selected
                compareSelectionInitialized = true
                compareChoiceContext = stamp
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == compareChoicesToken) _compareChoicesLoadFailed.value = true
            } finally {
                if (token == compareChoicesToken) _compareChoicesLoading.value = false
            }
        }
    }

    // ---- ③-1 My Models membership（D-008／Contract §23.1；Catalog ≠ My Models） ----
    //
    // 只有明確加入動作才寫 my_models 表；背景 catalog／sync 永不寫入。移除只刪
    // membership 列，不碰 offering／telemetry／history。UI reactive 讀取本 Flow。
    private val myModelsStore = MyModelsStore(app.database)
    val myModelKeys: StateFlow<Set<MyModelKey>> =
        myModelsStore.myModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /**
     * MYM-003 Catalog ≠ My Models：主 Models 列表只顯示明確加入的 My Models。
     * 以 (providerId, providerModelId) 精確匹配 membership；allModelRows（全量，
     * 含 catalog）保留給 compare／detail 既有邏輯與既有 tests，不動。
     *
     * FR-002：加入 `_pendingMemberRows`，讓「使用者明確加入但 offering 仍 hidden」
     * 的成員列在 My Models 可見（FR-002 V2；CORRECTNESS_REPAIR_AUDIT.md:130）。
     * 對既有行為的影響為零：任何沒有 hidden offering 的鍵，pending 不貢獻任何列，
     * 故本 combine 對既有鍵逐列等同舊版 `_allModelRows.filter { key in keys }`。
     * 移除仍由 live `myModelKeys` 即時過濾，不必等下一次 rows load。
     */
    val myModelRows: StateFlow<List<ModelRow>> =
        combine(_allModelRows, _pendingMemberRows, myModelKeys) { rows, pending, keys ->
            (rows + pending).filter { MyModelKey(it.providerId, it.providerModelId) in keys }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * FR-002 badge 來源（out-of-band，**不塞進 [ModelRow]**）。
     *
     * 語意（mapper-draft-review §7.3 鎖定）：badge ⟺ 「membership 內、且
     * `_allModelRows` 無任何 visible 列」的鍵。mixed key（同鍵一 visible 一 hidden）
     * **不** badge——key 級 badge 會把健康的 visible 列誤標成 candidate；badge 缺席
     * 只讓 pending 列無 badge，不誤標任何東西。移除 membership 後 badge 立即消失
     * （keys gate，不等 rows load）；promotion 後下一次 load 把列移到 visible，
     * 兩欄由同一次單一 pass 重建，無 pending+promoted 重複。
     */
    val pendingCandidateKeys: StateFlow<Set<MyModelKey>> =
        combine(_allModelRows, _pendingMemberRows, myModelKeys) { visible, pending, keys ->
            val visibleKeys = visible.mapTo(mutableSetOf()) { MyModelKey(it.providerId, it.providerModelId) }
            pending.mapNotNullTo(mutableSetOf()) { row ->
                MyModelKey(row.providerId, row.providerModelId)
                    .takeIf { k -> k in keys && k !in visibleKeys }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // ── FR-002：membership 變更 → 重跑 rows load（自觸發與初始 emission 的解法） ──
    //
    // 為什麼需要：`_pendingMemberRows` 只在 `loadModelRows` 跑完後才更新，而
    // `toggleMyModel`（:2227）不呼叫 `refreshModelRows()`。既有可見列不會暴露這件事
    // （visible offering 早已在 `_allModelRows`，combine 立刻接得住），hidden 成員列
    // 會——這正是 FR-002 V2。
    //
    // 為什麼不用 `myModelKeys.drop(1)`：`drop(1)` 會把「collector 啟動瞬間的當前值」
    // 當成初始值丟掉。若 membership 在首次 `all()` 讀取之後、collector 附加之前變更，
    // 該次變更即被吞掉，pending 列永久缺一筆。StateFlow conflates＋replays，無
    // drop 的 collector 附加時立即拿到當前值，與 marker 比較後自行決定是否補跑。
    //
    // 解法（write-back 比較，無 drop）：
    //   - load 成功回填時把「本次實際使用的 membership」寫回 `modelRowsMembershipUsed`；
    //   - collector 只在 `keys != modelRowsMembershipUsed` 時重跑；
    //   - `modelRowsMembershipUsed` 是 `@Volatile var`，**collector 不收集它**，故
    //     load 寫它不會產生 emission，不存在 load → 寫標記 → emission → load 的
    //     迴圈（這是本設計唯一的自觸發陷阱，必須維持 var，不得改成 flow）。
    //
    // 時序結果（無 drop 漏洞、無無條件雙跑）：
    //   · membership 空：首次 emission == 標記(emptySet) → 不重跑（零額外成本）。
    //   · membership 非空且首次 load 已完成：標記 == emission → 不重跑（零額外成本）。
    //   · membership 非空且首次 load 尚未完成：標記仍是 emptySet → 重跑一次；既有
    //     `modelRowsDirty`（:1925-1927）讓在途 load 於 finally（:1957-1960）補跑，
    //     token 守門丟棄先到的那批 → 收斂為同一結果。**至多一次**額外 load，且只在
    //     使用者真的已有成員時發生。
    //
    // collect 經 suspension point 傳播取消（viewModelScope 於 VM 清除時自動取消）；
    // 不得加 catch-and-swallow（會吞掉 scope 取消）。
    init {
        viewModelScope.launch {
            myModelKeys.collect { keys ->
                if (keys != modelRowsMembershipUsed) refreshModelRows()
                // D-028 F1 V2 membership-emission gating（H2/H3）：每 session 可多次
                // pass；每次 emission 逐 key 經 [requestMyModelRefresh] 的 per-W 預算機
                // 器 gating（SESSION_AUTO）。不另起第二個 collector，不用 drop(1)；
                // 本 load 不寫 membership，故無自觸發迴圈（A7）。
                for (k in keys) {
                    requestMyModelRefresh(
                        providerId = k.providerId,
                        providerModelId = k.providerModelId,
                        origin = RefreshOrigin.SESSION_AUTO,
                    )
                }
            }
        }
    }

    fun toggleMyModel(
        providerId: String,
        providerModelId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (providerId.isBlank() || providerModelId.isBlank()) return
        val key = MyModelKey(providerId, providerModelId)
        viewModelScope.launch {
            // L2 修復（v2）：in-flight 只作 UI 回饋訊號，絕不吞 toggle——Flow emission
            //（測試/皆消費者可見）與 finally 清 in-flight 有 race，把 gate 放這裡會
            // 在連續 toggle 序列中丟掉合法操作（ClockFlowTest 實證）。連點去重交給
            // store 冪等性（addToMyModels REPLACE／removeFromMyModels 冪等）。
            try {
                _myModelToggleInFlight.value += key
                _myModelToggleError.value -= key
                try {
                    if (myModelsStore.isMyModel(providerId, providerModelId)) {
                        myModelsStore.removeFromMyModels(providerId, providerModelId)
                    } else {
                        val selected = recordFor(_state.value.selectedId)
                        val scope = if (selected != null && selected.providerId == providerId) {
                            Triple(selected.accountId, selected.credentialId.takeIf { it.isNotBlank() }, _selectedGroup.value)
                        } else {
                            Triple(null, null, null)
                        }
                        // PK-26（H1 修復）：membership 寫入直接走 store（不經 addMyModelExplicit
                        // ——它吞掉寫入失敗）；寫入成功後 enrichment fail-open（其失敗絕不讀作
                        // toggle 失敗：runCatching 隔離，AGENTS 鐵律）。
                        myModelsStore.addToMyModels(providerId, providerModelId, "USER", nowMillis)
                        runCatching {
                            maybeEnrichMyModel(
                                providerId, scope.first, scope.second, scope.third,
                                providerModelId,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // PK-26：失敗誠實回饋（不改 membership 真值——仍由 Flow 驅動）。
                    _myModelToggleError.value = (_myModelToggleError.value + (key to "WRITE_FAILED"))
                }
            } finally {
                _myModelToggleInFlight.value -= key
            }
        }
    }

    /**
     * MYM-003 單一 membership 入口（D-008／Contract §23.1–23.3）。
     *
     * User 手動新增／toggle／（未來）Assistant／Explore 都走這裡；source 僅
     * USER／ASSISTANT／EXPLORE（store 白名單 fail-closed）。scope 是呼叫端已知的
     * exact account／credential／group，未知即 null（repository 顯式 single-account
     * 規則處理，不猜 fallback）。membership 寫入失敗則不觸發 enrichment。
     */
    private suspend fun addMyModelExplicit(
        providerId: String,
        providerModelId: String,
        source: String,
        accountId: String? = null,
        credentialId: String? = null,
        group: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (providerId.isBlank() || providerModelId.isBlank()) return
        try {
            myModelsStore.addToMyModels(providerId, providerModelId, source, nowMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        maybeEnrichMyModel(providerId, accountId, credentialId, group, providerModelId)
    }

    /**
     * ③-2 enrichment 觸發點（D-008／Contract §23.3 第一片＋MYM-004 bundle＋repair）。
     *
     * provider-only 舊路徑（`providerModelId` 缺席）：語意鎖定不變——false＝空白或
     * 不支援而靜默跳過（不寫 `_dedicatedSyncError`，不觸碰 membership）；支援時走
     * 既有 `syncDedicated`（single-flight、失敗分類、last-good 語意不變）。
     * 支援判定走 DB 維度的 resolved（UUID／custom 家族經 endpoint／config 亦可支援）。
     *
     * MYM-004 bundle 路徑（`providerModelId` 提供）：走 repository
     * `enrichMyModelBundle`（canonical/alias attach＋pricing/quota＋external 挂接＋
     * FREE evidence，全鏈 fail-open；provider perf 無既有路徑記 Unknown）。成功有
     * 實際同步筆數才記 `_dedicatedLastSynced`；失敗靜默（自動 enrichment 不寫紅錯，
     * 手動 `syncDedicated` 照常報錯）。Enrichment 任一來源失敗不得影響 membership。
     *
     * Repair（exact-key coalesce＋排隊不丟）：併發以 [BundleEnrichmentKey]（5 維 safe
     * refs，null 與具名保留差異）為單位——同 key 第二個 caller await 同一 work 真結果
     * （不重燒 quota、不假 true）；不同 key 同 provider 經 per-provider 鎖序列化執行
     * （排隊不丟）；不同 provider 並行。leader 唯一清理 registry（try／finally＋
     * NonCancellable）；cancel 重拋；follower 只 await，自己的 cancel 不碰 leader。
     * `_dedicated*` 三表維持 provider-level 最後同步狀態（既有 UI 語意），per-model
     * 證據以 bundle result／alias／FREE 為準。
     *
     * `lmspeedFetcher` 為 controlled port（測試 deterministic barrier 用；production
     * caller 不傳即 null，不觸網）。
     *
     * M2 New API 特權 port（Contract §6.1／6.3／§18／§23.3／§23.9）：
     * - 只有「已被既有 New API 官方 namespace head（PRICE／ACCOUNT_QUOTA／
     *   TOKEN_QUOTA）證明」的 provider 才注入 `NewApiAdapter`；未證明者零探測、
     *   零請求（bootstrap 仍是既有手動同步入口，本片不動）。這是「無 evidence
     *   head 不探測」的零燒額守衛 (a)。
     * - `endpointId` 只取 DB `OfferingEntity` 真相（該 provider×model 恰一顆
     *   offering）；多顆＝無唯一端點即 null，不跨端點借值。`operation` 只在
     *   endpoint protocol 與 mapper certified operation 同名空間時傳（見
     *   [newApiCertifiedOperations]），否則 null；不用 `ConnectionRecord.protocol`
     *   冒充 operation。
     * - CR-002：同一 (provider, account, credential) wave 只跑一次 provider-wide New API 腿：
     *   waveScopeKey（newApiSyncKey 三維）generation 由早先 leader 完成時遞增，後進 leader 取得 provider 鎖即跳過
     *   adapter 注入（`syncNewApi` 一次已涵蓋全 offerings；endpoint／operation
     *   證據仍傳），同 account wave 的 N 模型只燒 1 次 PAT；不同 account/key 完全分開。
     * - Provider perf 使用獨立 port，每個 exact model 都執行；pricing wave 去重
     *   不得丟掉第二個模型的 perf。repository 依持久官方證據認證版本後才發 GET，
     *   產品預設 168h（7d），不與本機 rolling-60s/My Uptime 混算。
     * - `newApiAdapterFactory` 為測試 controlled port；production caller 不傳，
     *   由 leader 內建真 `NewApiAdapter(app.vault)`，secret 邊界不變。
     */
    suspend fun maybeEnrichMyModel(
        providerId: String,
        accountId: String? = null,
        credentialId: String? = null,
        group: String? = null,
        providerModelId: String? = null,
        lmspeedFetcher: dev.modelatlas.core.lmspeed.LmspeedFetcher? = null,
        newApiAdapterFactory: (() -> NewApiAdapter)? = null,
        newApiPerfAdapterFactory: (() -> NewApiAdapter?)? = null,
    ): Boolean {
        if (providerId.isBlank()) return false
        val model = providerModelId?.takeIf { it.isNotBlank() } ?: run {
            if (!dedicatedSupportsSyncResolved(providerId)) return false
            syncDedicated(providerId, accountId, credentialId, group)
            return true
        }
        val key = BundleEnrichmentKey(providerId, model, accountId, credentialId, group)
        // CR-002 A2-A4: wave 世代按 (provider, account, credential) 分離——不同 account/key
        // 各自跑自己的 wave，不被 provider-global 序數吞掉（v10 scoped-lane 已驗證正確）;
        // 同一 account wave 內 N 模型仍共享 generation，避免重燒 PAT。
        val waveScopeKey = newApiSyncKey(providerId, accountId, credentialId)
        // CR-002：wave-級 generation 必須在認領 leader 之前捕捉；同一 wave 內早先
        // 完成的 provider-wide New API 腿會遞增它，後進 leader 取得 provider 鎖後
        // 比對即跳過 adapter 注入，不重燒 PAT 流量。
        val newApiGenerationAtEntry = newApiLegGenerations[waveScopeKey] ?: 0L
        // 原子認領（單次 mutex）：首個 caller 建 Deferred 成 leader；後到同 key 拿既有
        // Deferred 成 follower。认领与判断一次完成，不存在双 leader 窗口。
        val claim = bundleFlightMutex.withLock {
            val cur = bundleFlights[key]
            if (cur == null) {
                val fresh = CompletableDeferred<MyModelBundleResult>()
                bundleFlights[key] = fresh
                fresh to true
            } else {
                cur to false
            }
        }
        val deferred = claim.first
        if (!claim.second) {
            // Follower：await 同一 work 真結果；自己的 cancel 只斷自己，不碰 registry／leader。
            return try {
                deferred.await()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        // Leader：同 provider 不同 key 序列化（排隊不丟）；不同 provider 並行。
        val providerLock = bundleProviderLocks.getOrPut(providerId) { kotlinx.coroutines.sync.Mutex() }
        try {
            providerLock.withLock {
                try {
                    // M2：特權 New API port 只由 leader 解析／建構（follower 早退）；
                    // 同一 provider wave 只注入一次 adapter，但 endpoint／operation
                    // 證據照傳（後進模型仍能從前一個 leader 剛寫入的 head 取得 FREE）。
                    // CR-002 A2-A4：scope 內 N 模型共享本 wave port（不重燒 PAT)，
                    // 不同 account/key 由 caller waveScopeKey 帶來完全分開的 generation。
                    val newApiPort = resolveNewApiBundlePort(
                        providerId, model, waveScopeKey, newApiGenerationAtEntry, newApiAdapterFactory,
                        newApiPerfAdapterFactory,
                    )
                    val result = withContext(Dispatchers.IO) {
                        app.repository.enrichMyModelBundle(
                            providerId = providerId,
                            providerModelId = model,
                            accountId = accountId,
                            credentialId = credentialId,
                            group = group,
                            lmspeedFetcher = lmspeedFetcher,
                            newApiAdapter = newApiPort?.adapter,
                            newApiPerfAdapter = newApiPort?.perfAdapter,
                            endpointId = newApiPort?.endpointId,
                            operation = newApiPort?.operation,
                            // Earlier leader's observed caps + summary memo (null for
                            // the first leader / standalone callers => legacy path).
                            newApiPassport = bundlePassports[providerId],
                        )
                    }
                    // Remember the wave passport while still holding the provider
                    // lock: the next same-provider leader reads it after acquiring
                    // the lock. A cancelled/failed call leaves the registry as-is
                    // (committed observations stay; nothing deleted; next wave
                    // refetches only after drain-clear).
                    result.sessionPassport?.let { bundlePassports[providerId] = it }
                    // An HTTP pricing failure can return an empty prices list without
                    // throwing; zero also means no model price was attached. Do not
                    // mark that wave complete: the next model must get a retry chance.
                    if ((result.newApiPricingSynced ?: 0) > 0) {
                        newApiLegGenerations[waveScopeKey] = newApiGenerationAtEntry + 1
                    }
                    deferred.complete(result)
                    val synced = (result.pricingSummary?.pricingSynced ?: 0) +
                        (result.quotaSummary?.quotaSynced ?: 0)
                    if (synced > 0) {
                        _dedicatedLastSynced.update { it + (providerId to System.currentTimeMillis()) }
                        listOfNotNull(
                            result.pricingSummary?.latestObservedAtMillis,
                            result.quotaSummary?.latestObservedAtMillis,
                        ).maxOrNull()?.let { ts ->
                            _dedicatedLatestObserved.update { it + (providerId to ts) }
                        }
                        val fallback = (result.pricingSummary?.accountFallbackUsed == true) ||
                            (result.quotaSummary?.accountFallbackUsed == true)
                        _dedicatedAccountFallback.update { it + (providerId to fallback) }
                        refreshModelRows(announceLoading = false)
                        _detailEvidenceVersion.update { it + 1L }
                    }
                } catch (e: CancellationException) {
                    deferred.cancel(e)
                    throw e
                } catch (e: Exception) {
                    // 輸入 fail-closed（illegal group／unsafe ref 等）：membership 已由呼叫端
                    // 落庫，此處靜默；follower 經 completeExceptionally 得知失敗。
                    deferred.completeExceptionally(e)
                    return false
                } finally {
                    withContext(NonCancellable) {
                        bundleFlightMutex.withLock {
                            if (bundleFlights[key] === deferred) bundleFlights.remove(key)
                            // Structural drain: no flight left for this provider =>
                            // wave over => clear its passport (anti-TTL guarantee).
                            // Same critical section as flight removal.
                            if (bundleFlights.keys.none { it.providerId == providerId }) {
                                bundlePassports.remove(providerId)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 排隊中（尚未进入 provider 锁）即被 cancel：内层 finally 未跑，此处补清理＋
            // 给 deferred 交代，避免 follower 永等＋stale key。已交代则跳过（幂等）。
            if (!deferred.isCompleted) deferred.cancel(e)
            withContext(NonCancellable) {
                bundleFlightMutex.withLock {
                    if (bundleFlights[key] === deferred) bundleFlights.remove(key)
                    if (bundleFlights.keys.none { it.providerId == providerId }) {
                        bundlePassports.remove(providerId)
                    }
                }
            }
            throw e
        }
        return true
    }

    // ---- D-028 F1 V1/V2/V3：My Models metadata bounded-retry seam ----
    //
    // 行為契約（spec §2 binding）：member check → await in-flight + re-read
    // passport → classify → reserve → dispatch（charge 在 refresh provider-lock
    // 入口，D2-YES）→ terminal AUTO_SPENT。follower 只 await 後重讀（Joined，
    // 永不 charge／永不 cancel leader）；queued-unstarted cancel 釋放回 IDLE
    //（ReleasedNoCharge，預算 intact）；started-cancel 計次（T10）並重拋；
    // UNKNOWN_READ 永不 charge／不持久化；USER_EXPLICIT 走 per-press 預算，
    // 永不碰 auto 機器（D3-YES；[MyModelMetadataRetryPolicy.onUserExplicit]
    // no-op 即分離見證）。
    //
    // 調度經既有 [maybeEnrichMyModel]（bundle 真工作，不另起第二套 enrichment）；
    // charge 點是本 seam 的 refresh provider-lock 入口（嚴格先於第一次
    // network-capable 呼叫）。與 bundle 內鎖不同一把（Mutex 不可重入；全域順序
    // refresh-lock → bundle-lock 一致，無環、無自死鎖）。
    //
    // VM，maybeEnrichMyModel 之後的新公開 API（F1 切片；USER_EXPLICIT UI 接線
    // 為後續 Main-owned 切片，此處只備 seam＋per-press 預算）。
    suspend fun requestMyModelRefresh(
        providerId: String,
        providerModelId: String,
        accountId: String? = null,
        credentialId: String? = null,
        group: String? = null,
        origin: RefreshOrigin,
        lmspeedFetcher: dev.modelatlas.core.lmspeed.LmspeedFetcher? = null,
        newApiAdapterFactory: (() -> NewApiAdapter)? = null,
        newApiPerfAdapterFactory: (() -> NewApiAdapter?)? = null,
        attemptObserver: RefreshAttemptObserver? = null,
        passportReader: (suspend () -> PassportSnapshot)? = null,
    ): RefreshOutcome {
        if (providerId.isBlank() || providerModelId.isBlank()) return RefreshOutcome.FAILED_INPUT
        // E5 endpoint/operation 真相：DB 唯一 offering（singleOrNull；多顆即 null，
        // 不跨端點借值）；operation 只取 certified 同源，否則 null。
        val endpointId = resolveOfferingEndpointId(providerId, providerModelId)
        val operation = endpointId?.let { id ->
            try {
                app.database.endpointDao().getById(id)?.takeIf { it.providerId == providerId }?.protocol
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }?.trim()?.takeIf { it in newApiCertifiedOperations }
        val w = MyModelMetadataRetryPolicy.RefreshWorkScope(
            providerId = providerId,
            providerModelId = providerModelId,
            accountId = accountId,
            credentialId = credentialId,
            group = group,
            endpointId = endpointId,
            operation = operation,
        )
        fun emit(e: RefreshAttemptEvent) {
            try {
                attemptObserver?.onEvent(e)
            } catch (_: Exception) {
                // Observer 永不影響預算／流程（P-OBS O4 精神；生產預設 null）。
            }
        }
        // T1 member check：非成員零網路、零 membership 寫入（E3/H4；本 seam 永不
        // 加 membership——Catalog ≠ My Models）。成員點讀失敗 fail-closed 走
        // UNKNOWN_READ（Δchg==0，後續可恢復），不冒充非成員。
        val member = try {
            myModelsStore.isMyModel(providerId, providerModelId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val outcome = RefreshOutcome.SKIPPED_UNKNOWN_READ
            emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
            return outcome
        }
        if (!member) {
            val outcome = RefreshOutcome.SKIPPED_NOT_MEMBER
            emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
            return outcome
        }
        // E1 橋接：add-path（maybeEnrichMyModel）同 BundleEnrichmentKey 在途 → 本 seam
        // 併入該 bundle flight（join-burns-budget：await 真結果＋重讀，永不
        // reserve／charge；bundle 層 follower 本就純 await，無鎖巢狀／反轉）。
        val bundleKey = BundleEnrichmentKey(providerId, providerModelId, accountId, credentialId, group)
        val bundleInFlight = bundleFlightMutex.withLock { bundleFlights[bundleKey] }
        if (bundleInFlight != null) {
            try {
                bundleInFlight.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // add-path leader 失敗：不承接（C3a：預算仍開）。
            }
            try {
                readRefreshPassport(w, passportReader, lmspeedFetcher, newApiAdapterFactory, newApiPerfAdapterFactory)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            emit(RefreshAttemptEvent.Joined(w, origin))
            val outcome = RefreshOutcome.JOINED_EXISTING_FLIGHT
            emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
            return outcome
        }
        // 快路：同 W 已有在途 flight → follower（T6/T12，O3）。await leader 真結果
        // 後重讀一次 passport 再評估（await-then-re-evaluate），永不 reserve／
        // charge；自己的 cancel 只斷自己（重拋），不碰 leader。
        val existing = refreshFlightMutex.withLock { refreshFlights[w] }
        if (existing != null) {
            try {
                existing.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leader 失敗：follower 不承接（C3a：預算仍開，留給後續直接 pass）。
            }
            try {
                readRefreshPassport(w, passportReader, lmspeedFetcher, newApiAdapterFactory, newApiPerfAdapterFactory)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 重讀失敗不改變 follower 語意（仍只 Joined）。
            }
            emit(RefreshAttemptEvent.Joined(w, origin))
            val outcome = RefreshOutcome.JOINED_EXISTING_FLIGHT
            emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
            return outcome
        }
        // Leader：讀 passport＋classify（T2；§2 rule 7 TTL-free，exact kind＋exact
        // scope；quota 四 kind 取 slice 併集——見 readRefreshPassport）。
        val snap = readRefreshPassport(w, passportReader, lmspeedFetcher, newApiAdapterFactory, newApiPerfAdapterFactory)
        val pc = myModelRefreshPolicy.classify(
            hasLastGood = snap.hasLastGood,
            hasAnyHeadOrFact = snap.hasAnyHeadOrFact,
            readOk = snap.readOk,
            legCapable = snap.legCapable,
        )
        if (!snap.readOk || pc == MyModelMetadataRetryPolicy.PassportClass.UNKNOWN_READ) {
            // A8/J4：UNKNOWN_READ 永不 reserve／charge／持久化（含否定 verdict），
            // 後續 pass 可恢復。
            val outcome = RefreshOutcome.SKIPPED_UNKNOWN_READ
            emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
            return outcome
        }
        if (pc == MyModelMetadataRetryPolicy.PassportClass.HAS_LAST_GOOD) {
            // T3：lastGood（any age，ancient ≡ recent）即本 session skip forever，
            // 零 age 評估。
            val outcome = RefreshOutcome.SKIPPED_HAS_LAST_GOOD
            emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
            return outcome
        }
        if (!snap.legCapable) {
            // F1/F4：無能力腿可跑（zero-burn 守衛）；不 reserve，預算仍開。
            val outcome = RefreshOutcome.SKIPPED_UNCAPABLE
            emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
            return outcome
        }
        if (origin == RefreshOrigin.SESSION_AUTO) {
            // T5 原子認領：第二個同 W 即拒（C1 單 charge）；拒否 → spent 抑制。
            if (!myModelRefreshPolicy.reserve(w)) {
                val st = myModelRefreshPolicy.autoStateOf(w)
                val outcome = if (st == MyModelMetadataRetryPolicy.AutoState.RESERVED_AUTO ||
                    st == MyModelMetadataRetryPolicy.AutoState.CHARGED_RUNNING
                ) {
                    RefreshOutcome.SUPPRESSED_ALREADY_RUNNING
                } else {
                    RefreshOutcome.SUPPRESSED_BUDGET
                }
                emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
                return outcome
            }
        } else {
            // T13/D3-YES：per-press user 預算，不碰 auto 機器（no-op 見證分離）。
            myModelRefreshPolicy.onUserExplicit(w)
        }
        // Flight 認領（與 reserve 同一 W；中間 suspend 期間若同 W flight 出現則併入
        // follower——auto 的 reserve 經 onQueuedCancel 釋放，T11，預算 intact）。
        val flight = CompletableDeferred<RefreshOutcome>()
        val dup = refreshFlightMutex.withLock {
            val cur = refreshFlights[w]
            if (cur == null) {
                refreshFlights[w] = flight
                null
            } else {
                cur
            }
        }
        if (dup != null) {
            if (origin == RefreshOrigin.SESSION_AUTO) myModelRefreshPolicy.onQueuedCancel(w)
            try {
                dup.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            emit(RefreshAttemptEvent.Joined(w, origin))
            val outcome = RefreshOutcome.JOINED_EXISTING_FLIGHT
            emit(RefreshAttemptEvent.Evaluated(w, origin, outcome))
            return outcome
        }
        emit(RefreshAttemptEvent.Reserved(w, origin))
        // Leader dispatch：同 provider 序列化（排隊不丟）；charge 在鎖入口（T7，
        // D2-YES：onStart＋ChargedAtStart 嚴格先於 maybeEnrichMyModel 內第一次
        // network-capable 呼叫）。reserve→start 同一 leader，中間無他者可變動 auto
        // 態（reserve 拒他者、terminal 需先 charged、queued-cancel 只走本 cancel 路）。
        val providerLock = refreshProviderLocks.getOrPut(providerId) { kotlinx.coroutines.sync.Mutex() }
        var started = false
        var cancelledAfterStart = false
        var anyGoodRow = false
        var failedInput = false
        try {
            providerLock.withLock {
                if (origin == RefreshOrigin.SESSION_AUTO) myModelRefreshPolicy.onStart(w)
                started = true
                emit(RefreshAttemptEvent.ChargedAtStart(w, origin))
                try {
                    val ok = maybeEnrichMyModel(
                        providerId = providerId,
                        accountId = accountId,
                        credentialId = credentialId,
                        group = group,
                        providerModelId = providerModelId,
                        lmspeedFetcher = lmspeedFetcher,
                        newApiAdapterFactory = newApiAdapterFactory,
                        newApiPerfAdapterFactory = newApiPerfAdapterFactory,
                    )
                    if (!ok) {
                        // 輸入 fail-closed（靜默）：未產生好列（D2 下 started 即花費）。
                        failedInput = true
                    } else {
                        // T8 成功判定：dispatch 後重讀 passport，exact W 出現 lastGood
                        // 即 ≥1 好列（重讀零寫入）。
                        val post = readRefreshPassport(
                            w, passportReader,
                            lmspeedFetcher, newApiAdapterFactory, newApiPerfAdapterFactory,
                        )
                        anyGoodRow = post.hasLastGood
                    }
                } catch (e: CancellationException) {
                    cancelledAfterStart = true
                    throw e
                }
            }
        } catch (e: CancellationException) {
            if (!started) {
                // T11 queued-unstarted cancel（內層 finally 未跑）：auto 釋放回 IDLE
                //（預算 intact）；flight 交代＋移除在 finally；重拋。
                if (origin == RefreshOrigin.SESSION_AUTO) myModelRefreshPolicy.onQueuedCancel(w)
                if (!flight.isCompleted) flight.cancel(e)
                emit(RefreshAttemptEvent.ReleasedNoCharge(w, origin))
            } else {
                // T10 started-cancel 計次（D2-YES）→ AUTO_SPENT；重拋。
                // Flight 以 cancel 交代（follower 同步取消，鏡射 bundle 語意）。
                if (origin == RefreshOrigin.SESSION_AUTO) {
                    myModelRefreshPolicy.onTerminal(w, anyGoodRow = false, cancelledAfterStart = true)
                }
                if (!flight.isCompleted) flight.cancel(e)
                emit(RefreshAttemptEvent.Terminal(w, origin, RefreshOutcome.CANCELLED_AFTER_START))
            }
            throw e
        } catch (e: Exception) {
            // Leader 非 cancel 失敗（非 CE 從 dispatch 傳播）：flight 必須交代，
            // 否則 follower 的 await 永遠懸掛（C3a：follower 不承接、只 Joined；
            // 預算由 leader 終結路徑處置）。終結態=FAILED_NO_GOOD（started 已 charge）。
            if (origin == RefreshOrigin.SESSION_AUTO) {
                myModelRefreshPolicy.onTerminal(w, anyGoodRow = anyGoodRow, cancelledAfterStart = false)
            }
            if (!flight.isCompleted) {
                flight.completeExceptionally(e)
            }
            emit(RefreshAttemptEvent.Terminal(w, origin, RefreshOutcome.FAILED_NO_GOOD))
            emit(RefreshAttemptEvent.Evaluated(w, origin, RefreshOutcome.FAILED_NO_GOOD))
            throw e
        } finally {
            withContext(NonCancellable) {
                refreshFlightMutex.withLock {
                    if (refreshFlights[w] === flight) refreshFlights.remove(w)
                }
            }
        }
        // T8/T9 terminal（D2-YES：started 即花費；zeroHttp 由 policy 忽略——P-PORT2）。
        // Cancel 重拋路徑不經此處（無 Evaluated；Terminal 已在上方發出）。
        val terminalOutcome = when {
            anyGoodRow -> RefreshOutcome.SUCCEEDED
            failedInput -> RefreshOutcome.FAILED_INPUT
            else -> RefreshOutcome.FAILED_NO_GOOD
        }
        if (origin == RefreshOrigin.SESSION_AUTO) {
            myModelRefreshPolicy.onTerminal(w, anyGoodRow = anyGoodRow, cancelledAfterStart = false)
        }
        flight.complete(terminalOutcome)
        emit(RefreshAttemptEvent.Terminal(w, origin, terminalOutcome))
        emit(RefreshAttemptEvent.Evaluated(w, origin, terminalOutcome))
        return terminalOutcome
    }

    /**
     * D-028 F1 V3 passportReader port（P-UNK，DECIDED）。
     * 非 null＝測試注入（含 throwing reader→UNKNOWN_READ，A8）；null＝生產預設：
     * 既有 DAO 唯讀（snapshot heads facts:128/136/155＋slice heads facts:149＋
     * perf heads perffacts:130/133/136），行為零變更。讀取失敗 fail-closed→
     * readOk=false（UNKNOWN_READ，永不 charge／不持久化／後續可恢復）。
     * Cancellation 照拋；永不寫 Room（G3）。
     */
    private suspend fun readRefreshPassport(
        w: MyModelMetadataRetryPolicy.RefreshWorkScope,
        passportReader: (suspend () -> PassportSnapshot)?,
        lmspeedFetcher: dev.modelatlas.core.lmspeed.LmspeedFetcher?,
        newApiAdapterFactory: (() -> NewApiAdapter)?,
        newApiPerfAdapterFactory: (() -> NewApiAdapter?)?,
    ): PassportSnapshot {
        passportReader?.let {
            try {
                return it()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return PassportSnapshot(
                    hasLastGood = false,
                    hasAnyHeadOrFact = false,
                    readOk = false,
                    legCapable = false,
                )
            }
        }
        return try {
            val factDao = app.database.snapshotFactDao()
            var hasLastGood = false
            var hasAny = false
            for (kind in SnapshotKind.entries) {
                val heads = factDao.headsByKind(kind.name)
                for (h in heads) {
                    if (!refreshScopeMatches(h.scopeKey, w)) continue
                    hasAny = true
                    if (!h.lastGoodSnapshotId.isNullOrBlank()) hasLastGood = true
                }
                // SLICE-UNION（DECIDED）：四種 quota kind 取 slice heads 併集；一個 W
                // 只一 charge（不 per-slice）。unsliced 命名空間仍以 snapshot_heads
                // 為準，兩表互不冒充。Slice heads 只貢獻 hasAny（eligibility 的
                // ABSENT 判定基礎）；slice 的 lastGood 不構成 W 的 HAS_LAST_GOOD——
                // mixed good+absent slices 仍 dispatch exactly once（D-030 ruling 4）。
                if (kind == SnapshotKind.ACCOUNT_QUOTA || kind == SnapshotKind.TOKEN_QUOTA ||
                    kind == SnapshotKind.DEDICATED_ACCOUNT_QUOTA || kind == SnapshotKind.DEDICATED_TOKEN_QUOTA
                ) {
                    val sliceHeads = factDao.sliceHeadsByKind(kind.name)
                    for (sh in sliceHeads) {
                        if (!refreshScopeMatches(sh.scopeKey, w)) continue
                        hasAny = true
                    }
                }
            }
            // Provider perf（E1：scope 無 account 維，合法滿足雙邊——此處不 split；
            // per-kind OR 的一部分；hours 維不做新鮮度解讀）。
            val perfDao = app.database.providerPerfDao()
            for (kind in ProviderPerfKind.entries) {
                val heads = perfDao.headsByKind(kind.name)
                for (h in heads) {
                    if (!refreshPerfScopeMatches(h.scopeKey, w)) continue
                    hasAny = true
                    if (!h.lastGoodEvidenceId.isNullOrBlank()) hasLastGood = true
                }
            }
            // legCapable：有腿會跑（New API evidence 守衛＋注入 ports＋dedicated 解析
            // 支援）。生產預設（ports 全 null）下無 evidence／不支援即 SKIPPED_UNCAPABLE。
            val capable = hasNewApiEvidenceHead(w.providerId) ||
                lmspeedFetcher != null ||
                newApiAdapterFactory != null ||
                newApiPerfAdapterFactory != null ||
                dedicatedSupportsSyncResolved(w.providerId)
            PassportSnapshot(
                hasLastGood = hasLastGood,
                hasAnyHeadOrFact = hasAny,
                readOk = true,
                legCapable = capable,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PassportSnapshot(
                hasLastGood = false,
                hasAnyHeadOrFact = false,
                readOk = false,
                legCapable = false,
            )
        }
    }

    /** Exact-7-dim W↔snapshot head 匹配（storageKey 精確解析；null/"" 保留差異；malformed 列 fail-closed 跳過）。 */
    private fun refreshScopeMatches(scopeKey: String, w: MyModelMetadataRetryPolicy.RefreshWorkScope): Boolean {
        val parts = parseScopeKey(scopeKey) ?: return false
        return w.providerId == parts[0] &&
            w.accountId == parts[1] &&
            w.credentialId == parts[2] &&
            w.providerModelId == parts[3] &&
            w.group == parts[4] &&
            w.endpointId == parts[5] &&
            w.operation == parts[6]
    }

    /** Perf head 匹配（E1：provider×model×group；account/credential 無維不 split；hours 任意——TTL-free）。 */
    private fun refreshPerfScopeMatches(scopeKey: String, w: MyModelMetadataRetryPolicy.RefreshWorkScope): Boolean {
        val parts = parsePerfScopeKey(scopeKey) ?: return false
        return w.providerId == parts[0] &&
            w.providerModelId == parts[1] &&
            w.group == parts[2]
    }

    /** providerPerfScopeKey 4 維精確解析（provider, model, group, hours）；格式不合回 null。 */
    private fun parsePerfScopeKey(key: String): List<String?>? {
        val out = ArrayList<String?>(4)
        var pos = 0
        repeat(4) {
            val colon = key.indexOf(':', pos)
            if (colon < 0) return null
            val len = key.substring(pos, colon).toIntOrNull() ?: return null
            pos = colon + 1
            if (len == -1) {
                out += null
            } else {
                if (len < 0 || pos + len > key.length) return null
                out += key.substring(pos, pos + len)
                pos += len
            }
        }
        if (pos != key.length) return null
        return out
    }

    /**
     * Repair：bundle enrichment exact work key（immutable；5 維 safe refs）。
     * 只放 safe credential reference，絕不放 raw key；null 與具名 scope 保留差異。
     */
    private data class BundleEnrichmentKey(
        val providerId: String,
        val providerModelId: String,
        val accountId: String?,
        val credentialId: String?,
        val group: String?,
    )

    /** Repair：exact-key registry（add／remove／read 全由同一 mutex 保護）＋per-provider 序列化鎖。 */
    private val bundleProviderLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    /**
     * CR-009 DRAFT: wave session passport registry (providerId -> passport).
     * VM-lifetime, in-memory only; written only by the provider-lock leader,
     * cleared on structural wave drain. Never persisted, never lastGood.
     */
    private val bundlePassports = java.util.concurrent.ConcurrentHashMap<String, NewApiSessionPassport>()

    // ---- M2 New API 特權 port（Contract §6.1／6.3／§18／§23.3／§23.9；唯讀 DB 真相） ----

    /** wave-級 New API 腿 generation；按 `newApiSyncKey(providerId, accountId, credentialId)`
     *  分離——同 account wave 的 N 模型共用序數去重，不同 account/key 不互相吞掉。 */
    private val newApiLegGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * New API certified operation 命名空間。mapper（`NewApiDomainMapper.applicableOperations`）
     * 只會產 `EndpointType.name` 或 UNKNOWN；`NewApiSnapshotStore.isEndpointCertified` 只在
     * `endpoint.protocol == operation`（text 別名除外）時認證，故 protocol 落在此集合即與
     * 已寫入 head 的 operation 同源；CUSTOM／UNKNOWN／其他 protocol 一律 null，不借維。
     */
    private val newApiCertifiedOperations = setOf(
        "CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES",
        "GEMINI_NATIVE", "IMAGES_GENERATION", "EMBEDDINGS", "RERANK",
    )

    /**
     * leader 專用 New API bundle port。`adapter=null`＝本 wave 不再注入
     * （provider-wide 腿已跑過）；endpoint／operation 仍為 DB 證據，供 FREE
     * 推導使用；整個 port 為 null＝provider 未被證明，零請求零燒額。
     */
    private data class NewApiBundlePort(
        val adapter: NewApiAdapter?,
        val endpointId: String?,
        val operation: String?,
        val perfAdapter: NewApiAdapter?,
    )

    /**
     * M2 零燒額守衛＋粒度去重＋DB 真相（只在 leader 的 provider 鎖內呼叫）：
     * - 無任何 New API 官方 namespace head（PRICE／ACCOUNT_QUOTA／TOKEN_QUOTA）
     *   ＝ provider 未被證明為 New API：零探測、零請求回 null；bootstrap 仍是
     *   既有手動同步入口（本片不動）。
     * - generation 已被其他 leader 推進＝本 wave 的 provider-wide 腿已跑過
     *   （`syncNewApi` 一次涵蓋全 offerings）→ 不再注入 adapter（不重燒 PAT），
     *   但 endpoint／operation 照傳，讓後進模型仍能讀前一個 leader 剛寫的 head。
     * - endpointId 只取 DB `OfferingEntity` 唯一真相；operation 只在 protocol
     *   與 mapper operation 同源（[newApiCertifiedOperations]）時傳。
     * - `adapterFactory` 為測試 controlled port；production 不傳，leader 內建
     *   `NewApiAdapter(app.vault)`（secret 只在 adapter HTTP 邊界取值）。
     */
    private suspend fun resolveNewApiBundlePort(
        providerId: String,
        providerModelId: String,
        legScopeKey: String,
        generationAtEntry: Long,
        adapterFactory: (() -> NewApiAdapter)?,
        perfAdapterFactory: (() -> NewApiAdapter?)?,
    ): NewApiBundlePort? {
        val legAlreadyRan = (newApiLegGenerations[legScopeKey] ?: 0L) != generationAtEntry
        val endpointId = resolveOfferingEndpointId(providerId, providerModelId)
        val operation = endpointId?.let { id ->
            try {
                app.database.endpointDao().getById(id)?.takeIf { it.providerId == providerId }?.protocol
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }?.trim()?.takeIf { it in newApiCertifiedOperations }
        val hasEvidence = hasNewApiEvidenceHead(providerId)
        val adapter = if (legAlreadyRan || !hasEvidence) {
            null
        } else {
            adapterFactory?.invoke() ?: NewApiAdapter(app.vault)
        }
        // Provider-wide pricing has already covered all offerings; per-model perf has not.
        // The separate controlled port keeps pricing-only tests isolated without changing
        // the production default or sending either test's requests outside its transport.
        val perfAdapter = when {
            !hasEvidence -> null
            perfAdapterFactory != null -> perfAdapterFactory()
            else -> adapter ?: adapterFactory?.invoke() ?: NewApiAdapter(app.vault)
        }
        return NewApiBundlePort(adapter, endpointId, operation, perfAdapter)
    }

    /** 官方 namespace head 存在＝此 provider 已被 New API 同步證明；讀取失敗 fail-closed false。 */
    private suspend fun hasNewApiEvidenceHead(providerId: String): Boolean = try {
        listOf(SnapshotKind.PRICE, SnapshotKind.ACCOUNT_QUOTA, SnapshotKind.TOKEN_QUOTA).any { kind ->
            app.database.snapshotFactDao().headsByKind(kind.name).any { head ->
                parseScopeKey(head.scopeKey)?.firstOrNull() == providerId
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Bundle 粒度可證明的 endpoint 真相：provider×model 恰一顆 persisted offering
     * 才回其 endpointId；多顆＝多端點無唯一真相回 null（不跨端點借 FREE 值），
     * 讀取失敗 fail-closed null。offering 是 provider／endpoint／model 維度，與
     * account／credential／group 不互相覆蓋。
     */
    private suspend fun resolveOfferingEndpointId(providerId: String, providerModelId: String): String? {
        val matches = try {
            app.database.offeringDao().byProvider(providerId).filter { it.providerModelId == providerModelId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return matches.singleOrNull()?.endpointId?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setCompareSelection(selection: CompareKeySelection, selected: Boolean) {
        if (_compareChoices.value.none { it.selection == selection }) return
        val old = _compareSelections.value
        val next = if (selected) old + selection else old - selection
        if (next == old || next.size > CompareLiveWiring.COMPARE_MAX_OFFERINGS) return
        cancelCompareLive()
        _compareRows.value = emptyList()
        _compareError.value = null
        _compareSelections.value = next
        compareSelectionInitialized = true
    }

    private suspend fun prepareCompareOffering(
        offering: CompareLiveOffering, canonicalId: String,
    ): CompareLiveOffering? {
        val db = app.database
        val account = db.accountDao().getById(offering.accountId) ?: return null
        if (account.providerId != offering.providerId) return null
        val provider = db.providerDao().getById(offering.providerId) ?: return null
        val endpoint = db.endpointDao().getById(offering.endpointId) ?: return null
        if (endpoint.providerId != offering.providerId || endpoint.endpointUrl != offering.endpointUrl ||
            endpoint.protocol != offering.operation) return null
        val stored = db.offeringDao().byProvider(offering.providerId)
            .firstOrNull { it.id == offering.offeringId && !it.hidden } ?: return null
        if (stored.endpointId != offering.endpointId || stored.providerModelId != offering.providerModelId) return null
        val canonical = db.providerModelAliasDao().get(offering.providerId, offering.providerModelId)
            ?.canonicalModelId ?: canonicalIdFor(offering.providerModelId)
        if (canonical != canonicalId) return null
        val selectedRef = offering.selectedCredentialId
        val credential = selectedRef?.let { db.credentialDao().getById(it) }
        if (selectedRef != null && (credential == null || !credential.enabled ||
            credential.accountId != offering.accountId)) return null
        if (selectedRef == null) {
            val config = db.providerConfigDao().getByProvider(offering.providerId)?.toManualConfig() ?: return null
            if (config.authMode != AuthMode.NONE || !config.anonymousConfirmed) return null
        }
        // A projection of actual persisted facts, not an invented credential/availability row.
        val scoped = ConnectionRecord(
            id = offering.comparisonId, providerId = offering.providerId, accountId = offering.accountId,
            credentialId = selectedRef.orEmpty(), endpointId = endpoint.endpointId,
            name = provider.name, baseUrl = endpoint.baseUrl, endpointUrl = endpoint.endpointUrl,
            modelId = stored.providerModelId, protocol = endpoint.protocol,
            anonymous = selectedRef == null, credentialEnabled = credential?.enabled ?: true,
        )
        val auth = loadProviderModelAuth(scoped)
        if (auth.loadFailed ||
            ((auth.authMode == AuthMode.API_KEY || auth.authMode == AuthMode.CUSTOM_HEADER) &&
                auth.customFields.isEmpty()) || (selectedRef == null && auth.authMode != AuthMode.NONE)) return null
        for (field in auth.customFields) {
            val sibling = db.credentialDao().getById(field.credentialId) ?: return null
            if (!sibling.enabled || sibling.accountId != offering.accountId) return null
        }
        val bearer = when {
            selectedRef == null -> null
            (auth.authMode == AuthMode.API_KEY || auth.authMode == AuthMode.CUSTOM_HEADER) && !auth.hasInference -> null
            else -> selectedRef
        }
        return offering.copy(credentialId = bearer, selectedCredentialId = selectedRef, authFields = auth.customFields)
    }

    fun requestCompareLive(row: ModelRow) {
        if (providerModelJob != null || _state.value.busy || _state.value.saving || _compareRunning.value) {
            _compareError.value = "BUSY"
            return
        }
        val selections = _compareSelections.value.toSet()
        val contextAtStart = compareContextStamp()
        val modelRows = _allModelRows.value.toList()
        val token = ++compareToken
        compareJob?.cancel()
        _compareRows.value = emptyList()
        _compareError.value = null
        if (row.canonicalId.isBlank() || selections.isEmpty()) {
            _compareRunning.value = false
            _compareError.value = "NO_COMPARABLE_OFFERINGS"
            return
        }
        _compareRunning.value = true
        compareRequestSelections = selections
        compareJob = viewModelScope.launch {
            try {
                // 以進入當下的 context 建 request（不是事後再讀），回填前比對同一份 context。
                val request = CompareLiveWiring.buildRequestFromDb(
                    app.database,
                    row,
                    modelRows,
                    selections.toList(),
                )
                if (request == null) {
                    if (token == compareToken && compareContextStamp() == contextAtStart) {
                        _compareError.value = "NO_COMPARABLE_OFFERINGS"
                    }
                    return@launch
                }
                val prepared = withContext(Dispatchers.IO) {
                    request.offerings.map { prepareCompareOffering(it, request.canonicalId) }
                }
                if (prepared.any { it == null }) {
                    if (token == compareToken) _compareError.value = "AUTH"
                    return@launch
                }
                val boundRequest = request.copy(offerings = prepared.filterNotNull())
                val rows = withContext(Dispatchers.IO) {
                    CompareLiveRunner.run(
                        compareTestEngine ?: app.engine, boundRequest, RoomCompareLiveSampleFeed(app.database),
                        beforeOffering = { offering ->
                            withContext(Dispatchers.Main.immediate) {
                                ensureActive()
                                if (token != compareToken || compareContextStamp() != contextAtStart ||
                                    _compareSelections.value != selections ||
                                    offering.selectedCredentialId in blockedCredentialIds) {
                                    throw CancellationException("COMPARE_SCOPE_CHANGED")
                                }
                            }
                            val current = prepareCompareOffering(offering, request.canonicalId)
                            if (current == null || current.credentialId != offering.credentialId ||
                                current.authFields != offering.authFields) throw CancellationException("COMPARE_SCOPE_CHANGED")
                        },
                    )
                }
                // MODEL-007／008 review fix：比測期間 selected key 被換掉或清空時丟棄結果，
                // 舊 key 的對照表不得掛到新 key 上（scope 不互相覆蓋）。
                if (token != compareToken || compareContextStamp() != contextAtStart ||
                    _compareSelections.value != selections) return@launch
                _compareRows.value = rows
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == compareToken) _compareError.value = "COMPARE_FAILED"
            } finally {
                if (token == compareToken) {
                    _compareRunning.value = false
                    compareJob = null
                }
            }
        }
    }

    /** P6 repair：取消在途比測（取消傳播，不收斂為列失敗）。 */
    fun cancelCompareLive() {
        val token = ++compareToken
        val job = compareJob
        job?.cancel()
        if (job != null && !job.isCompleted) {
            _compareRunning.value = true
            _compareError.value = "CANCELLED"
            viewModelScope.launch {
                job.join()
                if (token == compareToken) {
                    compareJob = null
                    _compareRunning.value = false
                }
            }
        } else {
            compareJob = null
            _compareRunning.value = false
        }
    }

    private suspend fun cancelCompareForCredential(credentialId: String) {
        if (compareRequestSelections.none { it.credentialId == credentialId }) return
        val job = compareJob
        cancelCompareLive()
        job?.join()
        _compareRows.value = emptyList()
        _compareSelections.value = _compareSelections.value.filterNot { it.credentialId == credentialId }.toSet()
    }

    // ---- P7 repair LMSpeed enrichment 真 product flow（追加；既有 dedicated／NewApi／Models 語意不動） ----
    //
    // TRACEABILITY LMS-001／LMS-002／LMS-003／LMS-005。Spec PRODUCT_SPEC §13／§4、
    // RESEARCH_POLICY §7、X-006（失敗保舊：本層不寫 snapshot，只收 Repository 摘要）。
    // - 全域 single-flight（Mutex 原子認領＋Boolean 旗標雙保險，與 syncDedicated 同語意；
    //   重入直接返回，不拋錯、不疊加）。
    // - 狀態三件：[lmspeedSyncing]（全域進行旗標）、[lmspeedSyncError]（LMSpeed 專用分類：
    //   AUTH／NETWORK／RATE_LIMIT／UNSUPPORTED／EMPTY／UNKNOWN，禁 URL／body／secret；
    //   UNSUPPORTED＝未配置／不支援不可同步，EMPTY＝成功但真空 0 筆）、
    //   [lmspeedLastSynced]（成功且 saved>0 後裝置本地觸發時間 millis，不冒充觀測時間；
    //   真空 0 筆不更新，未證實不冒充同步過）。
    // - 真 composition：未傳 fetcher 即組產品鏈（`ProductLmspeedFetcher`＋vault transient key＋
    //   `LmspeedOkHttpTransport` 單 request 真打 HTTP＋持久化 budget）；fake 只供測試顯式注入，
    //   產品路徑禁 fallback Empty 成功。無 credential（vault 無 safe ref）即 UNSUPPORTED
    //   不可同步，不碰網路、不扣配額、不更新 lastSynced。
    // - 不做背景自動同步；busy／saving 中觸發記 UNKNOWN 錯誤並返回。

    /** P7-B2：LMSpeed 全域同步中（true＝進行中，UI 顯示 spinner＋禁重按）。 */
    private val _lmspeedSyncing = MutableStateFlow(false)
    val lmspeedSyncing: StateFlow<Boolean> = _lmspeedSyncing.asStateFlow()

    /** P7 repair：LMSpeed 錯誤分類（null＝無錯誤；新碼只增不改舊碼語意）。 */
    private val _lmspeedSyncError = MutableStateFlow<String?>(null)
    val lmspeedSyncError: StateFlow<String?> = _lmspeedSyncError.asStateFlow()

    /** P7 repair：LMSpeed 成功（saved>0）後裝置本地觸發時間（millis；UI 明示觸發時間，不冒充觀測時間）。 */
    private val _lmspeedLastSynced = MutableStateFlow<Long?>(null)
    val lmspeedLastSynced: StateFlow<Long?> = _lmspeedLastSynced.asStateFlow()

    /** P7-B2：single-flight 原子認領鎖（與 dedicated 同模式，不留競態）。 */
    private val lmspeedSingleFlightMutex = kotlinx.coroutines.sync.Mutex()
    /** Single-flight 世代：queued joiner 不得在 leader 完成後 reclaim 新 flight（back-to-back 雙跑 race）。 */
    private val lmspeedFlightGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * P7 repair：手動同步入口（供 ModelsTab／Settings 按鈕觸發）。
     * - 未傳 fetcher 即走真產品鏈（真打 HTTP）；fake 只供測試顯式注入。
     * - 無 credential 即 UNSUPPORTED 不可同步（不碰網路、不扣配額、不更新時間）。
     * - 成功 saved>0 記觸發時間；成功 saved=0（真空）不更新時間並記 EMPTY，
     *   與無配置（UNSUPPORTED）、失敗（分類碼）三態區分；失敗保留舊時間（失敗保舊）。
     */
    fun syncLmspeed(
        fetcher: dev.modelatlas.core.lmspeed.LmspeedFetcher? = null,
        nowMillis: Long = System.currentTimeMillis(),
        ttlMillis: Long = dev.modelatlas.core.lmspeed.LMSPEED_DEFAULT_TTL_MILLIS,
        exactAliasIndex: Map<String, String> = emptyMap(),
        normalizedAliasIndex: Map<String, String> = emptyMap(),
        knownCanonicalIds: Set<String> = emptySet(),
    ) {
        // Single-flight 併入語義：入口即見他人在跑 → 併入不重跑（UI 即時 caller）。
        // 世代守衛：在 mutex 排隊的 joiner，若等待期間曾有 flight 起跑（世代推進），
        // 即使 leader 已完成並清掉 flag 也不得 reclaim（消除 back-to-back 雙跑 race）。
        val genAtEntry = lmspeedFlightGeneration.get()
        if (_lmspeedSyncing.value) return
        if (_state.value.busy || _state.value.saving) {
            _lmspeedSyncError.value = "UNKNOWN"
            return
        }
        viewModelScope.launch {
            val claimed = lmspeedSingleFlightMutex.withLock {
                if (_lmspeedSyncing.value || lmspeedFlightGeneration.get() != genAtEntry) {
                    false
                } else {
                    lmspeedFlightGeneration.incrementAndGet()
                    _lmspeedSyncing.value = true
                    true
                }
            }
            if (!claimed) return@launch
            _lmspeedSyncError.value = null
            try {
                // 產品路徑：未傳 fetcher 即組真鏈；無 credential 即不可同步（禁 Empty fallback 成功）。
                // vault／client 組裝走 IO（Keystore 讀取不上主線）。
                val effective = fetcher ?: withContext(Dispatchers.IO) { buildProductionLmspeedFetcher() } ?: run {
                    _lmspeedSyncError.value = "UNSUPPORTED"
                    return@launch
                }
                val summary = withContext(Dispatchers.IO) {
                    app.repository.syncLmspeedEnrichment(
                        fetcher = effective,
                        nowMillis = nowMillis,
                        ttlMillis = ttlMillis,
                        exactAliasIndex = exactAliasIndex,
                        normalizedAliasIndex = normalizedAliasIndex,
                        knownCanonicalIds = knownCanonicalIds,
                    )
                }
                if (summary.saved == 0) {
                    _lmspeedSyncError.value = "EMPTY"
                } else {
                    _lmspeedLastSynced.value = summary.lastSyncedAtMillis
                }
                if (summary.saved > 0) {
                    refreshModelRows(announceLoading = false)
                    _detailEvidenceVersion.update { it + 1L }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lmspeedSyncError.value = runCatching { classifyLmspeedError(e) }.getOrDefault("UNKNOWN")
            } finally {
                _lmspeedSyncing.value = false
            }
        }
    }

    /**
     * P7 repair 真 composition（LMS-001）：`ProductLmspeedFetcher`＋vault transient key＋
     * `LmspeedOkHttpTransport`＋持久化 budget。vault 無 safe ref 即回 null（未配置不可同步）。
     * raw key 只在 fetch 當下經 vault 解析為區域變數，不存欄位、不進 log／exception。
     */
    private fun buildProductionLmspeedFetcher(): dev.modelatlas.core.lmspeed.LmspeedFetcher? {
        val configured = runCatching {
            app.vault.has(dev.modelatlas.core.lmspeed.LMSPEED_SAFE_CREDENTIAL_ID)
        }.getOrDefault(false)
        if (!configured) return null
        return dev.modelatlas.core.lmspeed.ProductLmspeedFetcher(
            safeCredentialId = dev.modelatlas.core.lmspeed.LMSPEED_SAFE_CREDENTIAL_ID,
            credentials = app.vault,
            transport = dev.modelatlas.core.lmspeed.LmspeedOkHttpTransport(),
            budget = dev.modelatlas.app.data.SharedPrefsLmspeedDailyBudget(
                getApplication(),
                dev.modelatlas.core.lmspeed.LMSPEED_SAFE_CREDENTIAL_ID,
            ),
        )
    }

    /**
     * P7 repair LMSpeed 專用錯誤 mapping（取代 `classifyDedicatedError` 混用；只回代碼，
     * 不攜帶 URL／server body／secret）。Repository 以 `IllegalStateException(error, cause)`
     * 包裝，故沿 cause 鏈找 [LmspeedFetchException]；找不到即按訊息啟發式（無 credential／
     * 未配置→UNSUPPORTED），兜底 UNKNOWN。
     */
    private fun classifyLmspeedError(e: Throwable): String {
        var cursor: Throwable? = e
        while (cursor != null) {
            val fetchError = cursor as? dev.modelatlas.core.lmspeed.LmspeedFetchException
            if (fetchError != null) {
                return when (fetchError.category) {
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.AUTH -> "AUTH"
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.RATE_LIMIT,
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.DAILY_BUDGET_EXHAUSTED,
                    -> "RATE_LIMIT"
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.CREDENTIAL_UNAVAILABLE,
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.NOT_FOUND,
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.CLIENT,
                    -> "UNSUPPORTED"
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.NETWORK,
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.TIMEOUT,
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.TLS,
                    -> "NETWORK"
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.SERVER,
                    dev.modelatlas.core.lmspeed.LmspeedFetchFailure.UNKNOWN,
                    -> "UNKNOWN"
                }
            }
            cursor = cursor.cause
        }
        val msg = (e.message.orEmpty() + " " + e::class.java.simpleName).lowercase()
        if (e is java.io.IOException) return "NETWORK"
        if ("credential" in msg || "unsupported" in msg || "not configured" in msg ||
            "required" in msg || "no provider" in msg || "not found" in msg
        ) {
            return "UNSUPPORTED"
        }
        if ("auth" in msg || "401" in msg || "403" in msg) return "AUTH"
        if ("network" in msg || "timeout" in msg || "tls" in msg || "unknownhost" in msg ||
            "connect" in msg
        ) {
            return "NETWORK"
        }
        if ("rate" in msg || "429" in msg || "budget" in msg || "quota" in msg) return "RATE_LIMIT"
        return "UNKNOWN"
    }

    // ---- MYM-005 My Uptime（Contract §23.4／§23.5；D-009；DATA 聚合器唯讀 samples） ----
    //
    // 本片只出 state，UI 由主 AI 後續片接線。state 以 selected connection 六維 scope
    // （provider／account／credential／group／model／endpoint）＋時間窗驅動；切窗／切連線／
    // samples 寫入即自動重算（Flow）。純讀本地 samples，不發 inference、不燒 quota；
    // Provider Success Rate／External Availability 不經此 state 冒充 My Uptime。
    // 空窗：summary 計數為事實 0、比率／速率／分位全 null（Unknown≠0）；無可驗證 scope
    // 或查詢失敗一律 state=null，不以 stale 冒充 current。

    /** D-009：Performance 窗選擇；預設 7d（唯一集中常數 [MyUptimePreset.DEFAULT_7D]）。 */
    private val _myUptimePreset = MutableStateFlow(MyUptimePreset.DEFAULT_7D)
    val myUptimePreset: StateFlow<MyUptimePreset> = _myUptimePreset.asStateFlow()

    /** CUSTOM 窗長（millis）；非 CUSTOM 時為 null，不與 preset 混用。 */
    private val _myUptimeCustomWindowMillis = MutableStateFlow<Long?>(null)
    val myUptimeCustomWindowMillis: StateFlow<Long?> = _myUptimeCustomWindowMillis.asStateFlow()

    /**
     * 窗選擇唯一入口（切窗自動觸發重算）。CUSTOM 缺有效窗長即 fail-closed 保持原狀；
     * 切離 CUSTOM 先換 preset 再清 custom，避免中間態出現 CUSTOM＋null 的非法組合。
     */
    fun selectMyUptimeWindow(preset: MyUptimePreset, customWindowMillis: Long? = null) {
        if (preset == MyUptimePreset.CUSTOM) {
            val custom = customWindowMillis?.takeIf { it > 0L } ?: return
            _myUptimeCustomWindowMillis.value = custom
            _myUptimePreset.value = preset
        } else {
            _myUptimePreset.value = preset
            _myUptimeCustomWindowMillis.value = null
        }
    }

    /** My Uptime 聚合輸入（scope＝null 表無可驗證連線，對外 state 回 null）。 */
    private data class MyUptimeLoadRequest(
        val scope: MyUptimeScope?,
        val preset: MyUptimePreset,
        val customWindowMillis: Long?,
    )

    /** 同一批樣本同時供長窗 summary 與 rolling-60s rates，避免兩次 DB 讀取。 */
    private data class MyUptimeLoaded(
        val summary: MyUptimeSummary,
        val current: MyUptimeObservedRates,
    )

    /**
     * 由已選連線推導六維 scope：credential 用實際 inference 會寫入 telemetry 的 safe ref
     * （匿名＝空字串，即 samples 的 safeCredentialId）；非匿名但已停用／封鎖／缺 ref
     * 一律回 null（fail-closed，不拿別的 key 頂替）。group 取目前 selected group，
     * null 只匹配 null，不當萬用字元。
     */
    private fun myUptimeScopeFor(selectedId: String?, group: String?): MyUptimeScope? {
        val record = recordFor(selectedId) ?: return null
        val credential = when {
            record.anonymous -> ""
            isContextCredentialUsable(record) -> record.credentialId
            else -> return null
        }
        return MyUptimeScope(
            providerId = record.providerId,
            accountId = record.accountId,
            safeCredentialId = credential,
            modelId = record.modelId,
            endpointId = record.endpointId,
            group = group,
        )
    }

    private val myUptimeRequest: Flow<MyUptimeLoadRequest> = combine(
        _state,
        _selectedGroup,
        _myUptimePreset,
        _myUptimeCustomWindowMillis,
    ) { state, group, preset, custom ->
        MyUptimeLoadRequest(
            scope = myUptimeScopeFor(state.selectedId, group),
            preset = preset,
            customWindowMillis = custom,
        )
    }.distinctUntilChanged()

    private val myUptimeLoaded: StateFlow<MyUptimeLoaded?> = myUptimeRequest
        .flatMapLatest { request ->
            if (request.scope == null) {
                flowOf<MyUptimeLoaded?>(null)
            } else {
                // app.repository.samples 只是 Room 表級 invalidation tickler：其
                // observeLatest() 內容（僅最新 100 筆）一律丟棄，長窗（7d／30d／All）
                // 不得靠它當資料來源而被 LIMIT 靜默截斷；每次觸發都用精確 scope＋窗
                // suspend 重查全窗（loadMyUptime 經 IO 呼叫 myUptimeSamples）再餵聚合器。
                app.repository.samples.map { _ -> loadMyUptime(request) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun loadMyUptime(request: MyUptimeLoadRequest): MyUptimeLoaded? {
        val scope = request.scope ?: return null
        val custom = request.customWindowMillis?.takeIf { it > 0L }
        if (request.preset == MyUptimePreset.CUSTOM && custom == null) return null
        val nowMillis = System.currentTimeMillis()
        val window = request.preset.windowFor(nowMillis, custom)
        return try {
            withContext(Dispatchers.IO) {
                val samples = app.database.sampleDao().myUptimeSamples(
                    providerId = scope.providerId,
                    accountId = scope.accountId,
                    safeCredentialId = scope.safeCredentialId,
                    modelId = scope.modelId,
                    endpointId = scope.endpointId,
                    group = scope.group,
                    startInclusive = window.startInclusive,
                    endExclusive = window.endExclusive,
                ).map { it.toSample() }
                MyUptimeLoaded(
                    summary = MyUptimeAggregator.summarize(samples, scope, window),
                    current = MyUptimeAggregator.currentObservedRates(samples, scope, nowMillis),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 讀取失敗回 Unknown：不以舊值冒充目前 scope／窗的資料。
            null
        }
    }

    /**
     * My Uptime 目前 scope＋窗的聚合（null＝無可驗證 scope 或查詢失敗，Unknown）。
     * 與 Provider Success Rate、External Availability 分離，永不互相平均。
     */
    val myUptime: StateFlow<MyUptimeSummary?> =
        myUptimeLoaded.map { it?.summary }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Current Observed RPM／TPM（D-009：現在往前 rolling 60s；具名與長窗平均區分）。
     * 空窗或查詢失敗為 null；不補 0、不與 [MyUptimeSummary.observedRpm] 互相冒充。
     */
    val myUptimeCurrentObservedRates: StateFlow<MyUptimeObservedRates?> =
        myUptimeLoaded.map { it?.current }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * CR-002 A3/A4：dedicated op 鍵級操作狀態（exact scope 讀取結果）。
 * [syncing] 表該 op 鍵在跑；[error] 只取 AUTH／NETWORK／UNSUPPORTED／UNKNOWN 代碼；
 * [lastSynced] 為裝置本地觸發時間；[latestObserved] 為資料時間（可 null）。
 */
data class DedicatedOpState(
    val syncing: Boolean,
    val error: String?,
    val lastSynced: Long?,
    val latestObserved: Long?,
)

/**
 * CR-002 A3/A4：dedicated op 鍵（provider×account×credential×group exact scope）。
 * 純函式（無 Android 依賴，可純 JVM 測試）。長度前綴編碼 `"${len}:${value}"`、
 * null／blank → `-1:`，以 `|` 串接——與 `SelectedKey.storageKey` 同一防碰撞方案；
 * 不得用原始 `::` 串接（provider／account／credential 不保證不含 `::`）。
 * 正規化：null 與空白字串同義（皆為 unknown scope，映射相同鍵）；非空白值 trim 後
 * 精確編碼（不摺疊大小寫；group 的 `::` 禁則由 [dev.modelatlas.app.data.AtlasRepository] upstream 把關）。
 * providerId 由呼叫端保證非空白（[AtlasViewModel.syncDedicated] MINOR9 先行返回，空白 provider 永不入鍵）。
 */
internal fun dedicatedOpKey(
    providerId: String,
    accountId: String?,
    credentialId: String?,
    group: String?,
): String {
    fun enc(value: String?): String {
        if (value.isNullOrBlank()) return "-1:"
        val trimmed = value.trim()
        return "${trimmed.length}:$trimmed"
    }
    return listOf(
        enc(providerId),
        enc(accountId),
        enc(credentialId),
        enc(group),
    ).joinToString("|")
}

/**
 * CR-002 A3/A4：自 op 鍵唯讀解析 providerId（長度前綴第一欄；null 編碼回 ""）。
 * 僅供 provider 聚合 busy 推導；不承擔一般解析契約。
 */
private fun dedicatedOpKeyProvider(key: String): String {
    val head = key.substringBefore('|')
    val sep = head.indexOf(':')
    if (sep <= 0) return ""
    val len = head.substring(0, sep).toIntOrNull() ?: return ""
    if (len < 0) return ""
    return head.substring(sep + 1).take(len)
}

/**
 * CR-002 A2-A4：New API op 鍵級操作狀態（exact scope 讀取結果）。
 * [error] 只取 [newApiSyncErrorCodes] 內的**代碼**（永不 localized 字串）；[lastSynced] 為
 * 裝置本地觸發時間；[latestObserved] 為資料時間（可 null）。欄位與 [DedicatedOpState] 對稱，
 * 故 `toUiOpState` 式投影可沿用（新增欄位不得為 null-default 以外的破壞性變更）。
 */
data class NewApiOpState(
    val syncing: Boolean,
    val error: String?,
    val lastSynced: Long?,
    val latestObserved: Long?,
)

/**
 * CR-002 A2-A4：New API scoped lane 的錯誤代碼 closed set。
 *
 * 與 `ScopedSyncStatusLines` 的渲染臂一一對應：`AUTH→error_auth`、`NETWORK→error_network`、
 * `UNSUPPORTED→error_sync_unavailable`、`BUSY→error_busy`、`UNKNOWN→error_sync_failed`。
 * **新增任何代碼必須在同一片補上渲染臂**，否則會靜默落入 `else→error_sync_failed`
 * （LMSpeed lane 掉 `RATE_LIMIT`／`EMPTY` 即為該缺陷）。純 JVM 可測（詞彙釘選用）。
 */
internal val newApiSyncErrorCodes: Set<String> = setOf("AUTH", "NETWORK", "UNSUPPORTED", "UNKNOWN", "BUSY")

/**
 * CR-002 A2-A4：New API op 鍵（provider×account×credential exact scope；**無 group**）。
 *
 * 無 group 的契約理由：[dev.modelatlas.app.data.AtlasRepository.syncNewApi] 不接受 group，
 * UI port `newApiOpStateFor` 亦無 group 參數；group 屬 dedicated／quota-pool 維度。
 * ⇒ **New API 的 group 差異永不 fork op 狀態**（by design，非遺漏）。
 *
 * 編碼沿用 [dedicatedOpKey] 同一防碰撞方案（長度前綴 `"${len}:${value}"`、null／blank → `-1:`、
 * 以 `|` 串接）；不得用原始 `::` 串接（provider／account／credential 不保證不含 `::`）。
 * 純函式、無 Android／DB 依賴，可純 JVM 測試。
 */
internal fun newApiSyncKey(
    providerId: String,
    accountId: String?,
    credentialId: String?,
): String {
    fun enc(value: String?): String {
        if (value.isNullOrBlank()) return "-1:"
        val trimmed = value.trim()
        return "${trimmed.length}:$trimmed"
    }
    return listOf(enc(providerId), enc(accountId), enc(credentialId)).joinToString("|")
}

/**
 * CR-002 A2-A4：New API 同步的 typed outcome（provider 級與 scoped lane 共用同一核心結果）。
 * 僅兩種結果：失敗已帶代碼；blank／busy／已被 claim 皆在進核心**之前**由各 lane 自行把關，
 * 故不進此型別（避免把 UI 語意漏進同步核心）。不攜帶 URL／server body／secret。
 */
private sealed class NewApiSyncOutcome {
    /** 同步成功（durable-fact 判定與 lastSynced／latestObserved 寫入屬 A4 wave slice）。 */
    object Succeeded : NewApiSyncOutcome()

    /** 失敗；[code] 只取 [newApiSyncErrorCodes] 內的代碼。 */
    class Failed(val code: String) : NewApiSyncOutcome()
}
