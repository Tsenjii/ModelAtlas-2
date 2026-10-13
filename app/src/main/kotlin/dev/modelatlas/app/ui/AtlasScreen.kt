@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.modelatlas.app.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.DedicatedOpState
import dev.modelatlas.app.dedicatedOpKey
import dev.modelatlas.app.ProviderModelActionState
import dev.modelatlas.app.R
import dev.modelatlas.app.notifications.NotificationPermission
import dev.modelatlas.app.onboarding.BatchImportViewModel
import dev.modelatlas.app.onboarding.OnboardingViewModel
import dev.modelatlas.app.data.CompareKeyChoice
import dev.modelatlas.app.data.CompareKeySelection
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.core.models.CompareLiveRowResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtlasScreen(
  viewModel: AtlasViewModel,
  onboardingViewModel: OnboardingViewModel,
  batchImportViewModel: BatchImportViewModel = viewModel(),
) {
  AtlasTheme {
    AtlasScreenContent(viewModel, onboardingViewModel, batchImportViewModel)
  }
}

/**
 * Provider Detail 選定 scope 的型別化鍵（provider＋selected connection＋group）。
 *
 * 匿名連線的 id 內嵌 offering／account／model 等使用者可見字串，因此不可用字串拼接
 * 比較；此鍵以 data class 型別相等判定，避免 id 內容造成假相等。
 */
private data class ProviderDetailScopeKey(
  val providerId: String,
  val selectedId: String?,
  val group: String?,
)

private data class DetailIdentity(
  val providerId: String,
  val canonicalId: String,
  val providerModelId: String,
)

private data class DetailOwnershipKey(
  val identity: DetailIdentity,
  val selectedKey: SelectedKey?,
  val offeringScope: ModelDetailLoader.SelectedOfferingScope?,
)

/** Immutable, scope-owned UI input. Missing state is not provider success. */
data class OpState(
  val syncing: Boolean = false,
  val error: String? = null,
  val lastSynced: Long? = null,
  val latestObserved: Long? = null,
)

internal fun DedicatedOpState.toUiOpState(): OpState =
  OpState(syncing, error, lastSynced, latestObserved)

/** Capture the card's safe scope without selecting it or changing the leaf callback. */
internal fun scopedNewApiSyncFor(
  connection: ConnectionItem,
  onScopedSync: (String, String?, String?) -> Unit,
): (String) -> Unit = { providerId ->
  onScopedSync(providerId, connection.accountId.takeIf { it.isNotBlank() },
    connection.credentialId.takeIf { it.isNotBlank() })
}

/** The dispatched group and the status selector must use this same exact value. */
internal fun dedicatedSyncGroupFor(
  target: ConnectionItem,
  selectedConnection: ConnectionItem?,
  selectedGroup: String?,
): String? = selectedGroup.takeIf {
  selectedConnection != null &&
    target.providerId == selectedConnection.providerId &&
    target.accountId == selectedConnection.accountId &&
    target.credentialId == selectedConnection.credentialId
}

/** Reserve both the progress slot and longest label so async changes do not move focus/bounds. */
@Composable
internal fun SyncOperationButtonContent(label: String, syncing: Boolean, syncingLabel: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(20.dp)) {
      if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    Box {
      Text("$label · $syncingLabel", Modifier.alpha(0f).clearAndSetSemantics { })
      Text(if (syncing) "$label · $syncingLabel" else label)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AtlasScreenContent(
  viewModel: AtlasViewModel,
  onboardingViewModel: OnboardingViewModel,
  batchImportViewModel: BatchImportViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshLocalDate()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val notificationSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()
  val notificationSettingsSaving by viewModel.notificationSettingsSaving.collectAsStateWithLifecycle()
  val notificationSettingsSaveFailed by viewModel.notificationSettingsSaveFailed.collectAsStateWithLifecycle()
  val onboarding by onboardingViewModel.state.collectAsStateWithLifecycle()
  val batch by batchImportViewModel.state.collectAsStateWithLifecycle()
  // Provider busy is face-only. Results come from collected operation-key snapshots.
  val dedicatedSyncing by viewModel.dedicatedSyncing.collectAsStateWithLifecycle()
  val dedicatedOpSyncing by viewModel.dedicatedOpSyncing.collectAsStateWithLifecycle()
  val dedicatedOpError by viewModel.dedicatedOpError.collectAsStateWithLifecycle()
  val dedicatedOpLastSynced by viewModel.dedicatedOpLastSynced.collectAsStateWithLifecycle()
  val dedicatedOpLatestObserved by viewModel.dedicatedOpLatestObserved.collectAsStateWithLifecycle()
  // P7-B2：LMSpeed 全域 enrichment 狀態（進行旗標／錯誤分類／觸發時間；只讀，不做背景自動）。
  val lmspeedSyncing by viewModel.lmspeedSyncing.collectAsStateWithLifecycle()
  val lmspeedSyncError by viewModel.lmspeedSyncError.collectAsStateWithLifecycle()
  val lmspeedLastSynced by viewModel.lmspeedLastSynced.collectAsStateWithLifecycle()
  // P6 INT-2：Models 全量列（搜尋＋篩選＋結果的唯一資料來源）。
  val allModelRows by viewModel.allModelRows.collectAsStateWithLifecycle()
  val modelRowsLoading by viewModel.modelRowsLoading.collectAsStateWithLifecycle()
  val modelRowsLoadFailed by viewModel.modelRowsLoadFailed.collectAsStateWithLifecycle()
  // P6 repair worker B：selected-key 真值（Compare scope／Filter selected-key 維度用）。
  val selectedAccountId by viewModel.selectedAccountId.collectAsStateWithLifecycle()
  val selectedCredentialId by viewModel.selectedCredentialId.collectAsStateWithLifecycle()
  val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
  val selectedSyncConnection = (state.connections + state.managedConnections)
    .firstOrNull { it.id == state.selectedId }
    ?.takeIf {
      it.accountId.takeIf { id -> id.isNotBlank() } == selectedAccountId &&
        it.credentialId.takeIf { id -> id.isNotBlank() } == selectedCredentialId
    }
  val syncGroupFor: (ConnectionItem) -> String? = { connection ->
    dedicatedSyncGroupFor(connection, selectedSyncConnection, selectedGroup)
  }
  val scopedDedicatedStateFor: (ConnectionItem, String?) -> OpState = { connection, group ->
    val opKey = dedicatedOpKey(
      connection.providerId,
      connection.accountId.takeIf { it.isNotBlank() },
      connection.credentialId.takeIf { it.isNotBlank() },
      group,
    )
    DedicatedOpState(
      syncing = opKey in dedicatedOpSyncing,
      error = dedicatedOpError[opKey],
      lastSynced = dedicatedOpLastSynced[opKey],
      latestObserved = dedicatedOpLatestObserved[opKey],
    ).toUiOpState()
  }
  val scopedNewApiStateFor: (ConnectionItem) -> OpState = { connection ->
    val st = viewModel.newApiOpState(connection.providerId, connection.accountId, connection.credentialId)
    OpState(syncing = st.syncing, error = st.error, lastSynced = st.lastSynced, latestObserved = st.latestObserved)
  }
  // Hoist the existing local capability gate; leaves only inspect this immutable set.
  val dedicatedSupportedProviders = remember(state.connections, state.managedConnections, viewModel) {
    (state.connections + state.managedConnections).map { it.providerId }.toSet()
      .filterTo(mutableSetOf()) { viewModel.dedicatedSupportsSync(it) }
  }
  val dedicatedSupported: (String) -> Boolean = { it in dedicatedSupportedProviders }
  // PROV-001／MODEL-006：Provider Detail 模型動作單一狀態（新增／立即測試／取消）。
  val providerModelAction by viewModel.providerModelAction.collectAsStateWithLifecycle()
  // P6-DC（UI 入口接線）：detail 選擇態（VM 為單一真相；搜尋／篩選／同步行為不動）。
  val modelDetailRow by viewModel.modelDetailRow.collectAsStateWithLifecycle()
  val detailEvidenceVersion by viewModel.detailEvidenceVersion.collectAsStateWithLifecycle()
  // P6 repair 主 AI 整合：Compare 唯一產品路徑為 VM（requestCompareLive→runner→回填
  // compareRows；B 的 Screen-local 實現已退役，删除 local state，改 collect VM）。
  // 切換 canonical 經 viewModel.openModelDetail 清空舊比測列（VM 單一真相），避免跨模型污染。
  val compareRows by viewModel.compareRows.collectAsStateWithLifecycle()
  val compareRunning by viewModel.compareRunning.collectAsStateWithLifecycle()
  val compareError by viewModel.compareError.collectAsStateWithLifecycle()
  val compareChoices by viewModel.compareChoices.collectAsStateWithLifecycle()
  val compareSelections by viewModel.compareSelections.collectAsStateWithLifecycle()
  val compareChoicesLoading by viewModel.compareChoicesLoading.collectAsStateWithLifecycle()
  val compareChoicesLoadFailed by viewModel.compareChoicesLoadFailed.collectAsStateWithLifecycle()
  val myModelKeys by viewModel.myModelKeys.collectAsStateWithLifecycle()
  // MYM-003 Catalog ≠ My Models：主 Models 列表只顯示明確加入的 My Models。
  // allModelRows（全量，含 catalog）保留給 compare／detail 既有邏輯，不動。
  val myModelRows by viewModel.myModelRows.collectAsStateWithLifecycle()
  val pendingCandidateKeys by viewModel.pendingCandidateKeys.collectAsStateWithLifecycle()
  val myModelToggleInFlight by viewModel.myModelToggleInFlight.collectAsStateWithLifecycle()
  val myModelToggleError by viewModel.myModelToggleError.collectAsStateWithLifecycle()
  val app = LocalContext.current.applicationContext as AtlasApplication
  // Detail loads DB evidence; loader-owned fail-closed results remain authoritative.
  val detailSelectedKey = selectedSyncConnection
    ?.takeIf { it.providerId == modelDetailRow?.providerId && it.enabled }
    ?.let { connection ->
      val account = selectedAccountId
      val credential = selectedCredentialId
      if (account == null || credential == null) null else SelectedKey(account, credential, selectedGroup)
    }
  // Concrete row identity and selected scope own the loaded data; another provider/key
  // must never see a prior scope's rich state while its new read is in progress.
  val rowOfferingScope = modelDetailRow?.let { ModelDetailLoader.SelectedOfferingScope(it.endpointId, it.operation) }
  val ownershipKey = modelDetailRow?.let {
    DetailOwnershipKey(
      DetailIdentity(it.providerId, it.canonicalId, it.providerModelId),
      detailSelectedKey,
      rowOfferingScope,
    )
  }
  var richDetailState by remember(ownershipKey) { mutableStateOf<ModelDetailState?>(null) }
  LaunchedEffect(ownershipKey, modelDetailRow, detailEvidenceVersion) {
    val row = modelDetailRow
    if (row == null || ownershipKey == null) {
      richDetailState = null
    } else {
      val loaded = withContext(Dispatchers.IO) {
        try {
          ModelDetailLoader.loadDetail(app.database, row, detailSelectedKey, ModelDetailLoader.SelectedOfferingScope(row.endpointId, row.operation))
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        }
      }
      // Escaping exceptions retain same-owner stale state (caught->rethrow skips this
      // assignment only on CancellationException; generic catch sets null and falls
      // through to the VM fallback below). Loader non-null results always replace;
      // null (require-fail or fail-closed throw) clears — never retains prior
      // successful detail (M1: fail-open regression fixed).
      richDetailState = loaded
    }
  }
  val modelDetailState = richDetailState ?: remember(modelDetailRow) {
    modelDetailRow?.let { viewModel.modelDetailStateFor(it) }
  }
  // PROV-001 repair PD：Provider Detail Screen-local 導航（卡片點選→detail＋返回；VM 不動）。
  // 仿 ModelsTab modelDetailRow 模式：Screen-local 選擇態＋LaunchedEffect 經 ProviderDetailLoader 載入。
  // 載入三態明確：loading＝spinner（不捏造）；loadFailed＝error_load＋action_retry＋返回；
  // provider 切換／重試先清舊內容；CancellationException 一律 rethrow（不吞取消）；
  // 同步狀態變化（syncingProviders／dedicatedSyncing）更新 effect key 使 syncing 顯示跟上。
  var selectedProviderId by remember { mutableStateOf<String?>(null) }
  var providerDetailTab by remember { mutableStateOf(ProviderDetailTab.OVERVIEW) }
  var providerDetailState by remember { mutableStateOf<ProviderDetailState?>(null) }
  // 載入三態：state==null 且 !loadFailed＝讀取中（spinner，不捏造）；loadFailed＝錯誤畫面。
  var providerDetailLoadFailed by remember { mutableStateOf(false) }
  var providerDetailStale by remember { mutableStateOf(false) }
  var providerDetailRetryToken by remember { mutableIntStateOf(0) }
  // 已套用之選定 scope（connectionId＋group）。與目前 scope 不同即代表畫面上的
  // 驗證／動作範圍屬於舊金鑰，必須降級為「未載入 scope」而不是繼續顯示。
  var providerDetailAppliedScope by remember { mutableStateOf<ProviderDetailScopeKey?>(null) }
  // 每次載入取一個 token；結果只在仍是最新 token 時套用（換 scope／重入的過期結果丟棄）。
  var providerDetailLoadToken by remember { mutableIntStateOf(0) }
  val providerDetailSelection = selectedProviderId
  // 選定 scope 的**型別化**鍵：provider＋selectedId＋group。匿名連線 id 內含 model id
  // 等使用者字串，故不可用字串拼接比較，必須以型別相等判定。
  val currentDetailScopeKey = providerDetailSelection?.let { pid ->
    ProviderDetailScopeKey(
      providerId = pid,
      selectedId = state.selectedId,
      group = selectedGroup,
    )
  }
  // 離開 detail（返回／換 provider／離開 tab／dispose）一律經 VM 取消在途模型動作，
  // 不留孤兒請求；key 切換由 VM 的 selectConnection／selected-context 變更自行取消。
  DisposableEffect(providerDetailSelection) {
    val leavingProviderId = providerDetailSelection
    onDispose {
      if (leavingProviderId != null) viewModel.leaveProviderDetail(leavingProviderId)
    }
  }
  LaunchedEffect(
    providerDetailSelection,
    state.managedConnections,
    state.syncingProviders,
    dedicatedSyncing,
    providerDetailRetryToken,
    // 已完成的模型動作會遞增 refreshToken；selectedId／group 變動代表驗證 scope 改變，
    // 兩者都必須重讀，否則會顯示另一把 key 的驗證狀態。
    providerModelAction.refreshToken,
    currentDetailScopeKey,
  ) {
    val pid = providerDetailSelection
    val scopeKey = currentDetailScopeKey
    val myToken = providerDetailLoadToken + 1
    providerDetailLoadToken = myToken
    if (pid == null) {
      providerDetailState = null
      providerDetailLoadFailed = false
      providerDetailStale = false
      providerDetailAppliedScope = null
    } else {
      val managed = state.managedConnections.filter { it.providerId == pid }
      // PK-27：exact-scope 語義（測試契約：無所選/停用 → 不顯示 pending/complete，不借 sibling）。
      val selectedItem = managed.firstOrNull { it.id == state.selectedId && it.enabled }
      // 同一 provider 重新整理時保留 last-good，避免同步旗標變動讓整頁閃成 spinner。
      if (providerDetailState?.header?.providerId != pid) {
        providerDetailState = null
        providerDetailStale = false
        providerDetailAppliedScope = null
      }
      providerDetailLoadFailed = false
      try {
        val loaded = withContext(Dispatchers.IO) {
          ProviderDetailLoader.loadDetail(
            db = app.database,
            providerId = pid,
            connections = managed,
            syncing = pid in state.syncingProviders || pid in dedicatedSyncing,
            // Scope-owned sync times are rendered reactively below, not borrowed from a provider map.
            lastSyncedLabel = null,
            checkinPending = selectedItem?.checkinPending,
            selectedConnectionId = state.selectedId,
            selectedGroup = selectedGroup,
          )
        }
        // Scope token guard（型別化 scope 鍵）：任何新的 scope 載入都會遞增 token，
        // 舊 token 的結果一律丟棄，涵蓋換 key／換 group／重新進入本頁／被取消的載入。
        if (myToken != providerDetailLoadToken) return@LaunchedEffect
        if (loaded.loadFailed) {
          // 載入失敗保留同 provider 的 provider 事實（history／models／效能），
          // 但 scope 投影一律失效並停用動作，不 advertise 舊金鑰的驗證／範圍。
          providerDetailAppliedScope = null
          providerDetailStale = providerDetailState != null
          providerDetailLoadFailed = providerDetailState == null
        } else {
          providerDetailState = loaded
          providerDetailStale = false
          providerDetailAppliedScope = scopeKey
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        providerDetailAppliedScope = null
        providerDetailStale = providerDetailState != null
        providerDetailLoadFailed = providerDetailState == null
      }
    }
  }
  // P6 repair 主 AI 整合：Compare 唯一產品路徑為 VM（B 的 Screen-local 實現已退役删除）。
  // 同 prompt 經 runner（source 恆 COMPARE_LIVE）；scope 不全→VM 記 NO_COMPARABLE_OFFERINGS；
  // 取消由 VM 傳播；逐列失敗由 runner 隔離收斂；錯誤只顯示分類碼。
  fun startCompareLive() {
    val row = modelDetailRow ?: return
    if (compareRunning) return
    viewModel.requestCompareLive(row)
  }
  // P6 repair worker B：selected-key 可用性（Filter selected-key 維度）＋可比列數
  //（Compare 空態 "尚無可比 offering" vs "尚無比測結果" 的誠實區分）。
  val hasSelectedKey = !selectedAccountId.isNullOrBlank() && !selectedCredentialId.isNullOrBlank()
  val compareOfferingsCount = remember(modelDetailRow, allModelRows) {
    val canonical = modelDetailRow?.canonicalId ?: return@remember 0
    allModelRows.count { it.canonicalId == canonical }
  }
  var showQuick by remember { mutableStateOf(false) }
  var showAdvanced by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf(false) }
  var showWorkflow by remember { mutableStateOf(false) }
  var showBatch by remember { mutableStateOf(false) }
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var showSettings by remember { mutableStateOf(false) }
  var showConnections by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val keyboardVisible = WindowInsets.isImeVisible
  val compactChrome = keyboardVisible || LocalConfiguration.current.screenHeightDp < 480 ||
    LocalDensity.current.fontScale > 1.3f
  val selectedConnection = remember(state.connections, state.selectedId) {
    state.connections.firstOrNull { it.id == state.selectedId }
  }
  fun selectTab(destination: Int) {
    focusManager.clearFocus()
    keyboard?.hide()
    // 離開 Provider Detail 一律取消在途模型動作，不留孤兒請求。
    selectedProviderId?.let { viewModel.leaveProviderDetail(it) }
    tab = destination
    showConnections = false
    showQuick = false
    showAdvanced = false
    showWorkflow = false
    showBatch = false
    onboardingViewModel.close()
  }
  val snackbarHostState = remember { SnackbarHostState() }
  val onboardingError = stringResource(R.string.onboarding_error)
  LaunchedEffect(onboarding.error) {
    if (onboarding.error) {
      snackbarHostState.showSnackbar(onboardingError)
      onboardingViewModel.dismissError()
    }
  }
  val batchError = stringResource(R.string.batch_error)
  LaunchedEffect(batch.error) {
    if (batch.error) {
      snackbarHostState.showSnackbar(batchError)
      batchImportViewModel.dismissError()
    }
  }
  LaunchedEffect(onboarding.committedProviderId, state.managedConnections) {
    val providerId = onboarding.committedProviderId
    val connection = state.managedConnections.firstOrNull { it.providerId == providerId }
    if (providerId != null && connection != null) {
      viewModel.selectConnection(connection.id)
      showWorkflow = false
      showQuick = false
      showAdvanced = false
      showBatch = false
      editing = false
      tab = 1
      onboardingViewModel.close()
    }
  }
  val errorText = state.error
  LaunchedEffect(errorText) {
    if (errorText != null) {
      snackbarHostState.showSnackbar(errorText)
      viewModel.dismissError()
    }
  }
  val title = when (tab) {
    1 -> stringResource(R.string.title_providers)
    2 -> stringResource(R.string.title_models)
    else -> stringResource(R.string.title_playground)
  }
  val showingPlayground = tab == 0 && !showQuick && !showAdvanced && !showWorkflow && !showBatch
  // B5：provider detail 有自帶 bottomBar（返回／同步），外層 tab bar 於 detail 期間隱藏。
  val providerDetailActive = tab == 1 && selectedProviderId != null
  Scaffold(
    modifier = Modifier.fillMaxSize().imePadding(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = {
          if (showingPlayground) {
            PlaygroundTopBarTitle(
              connection = selectedConnection,
              compact = compactChrome,
              onSelect = { focusManager.clearFocus(); keyboard?.hide(); showConnections = true },
            )
          } else {
            Column {
              if (!compactChrome) Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
        ),
        actions = {
          IconButton(
            onClick = { showSettings = true },
            modifier = Modifier.size(AtlasSpace.touch),
          ) {
            Icon(
              Icons.Filled.Settings,
              contentDescription = stringResource(R.string.action_settings),
            )
          }
        },
      )
    },
    bottomBar = {
      if (!providerDetailActive) {
        AtlasBottomNavigation(selectedTab = tab, compact = compactChrome, onSelect = ::selectTab)
      }
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { inner ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(inner)
        .consumeWindowInsets(inner),
    ) {
      val activeWorkflow = onboarding.snapshot
      when {
        showWorkflow && activeWorkflow != null -> OnboardingProgressScreen(
          snapshot = activeWorkflow,
          busy = onboarding.busy,
          onCancel = onboardingViewModel::cancel,
          onRetry = onboardingViewModel::retry,
          onSkip = onboardingViewModel::skip,
          onCommit = onboardingViewModel::commit,
          onAbandon = { onboardingViewModel.abandon(); showWorkflow = false },
          onEdit = {
            showWorkflow = false
            editing = true
            showQuick = activeWorkflow.flowType == dev.modelatlas.core.onboarding.OnboardingFlowType.QUICK
            showAdvanced = !showQuick
          },
          onClose = { onboardingViewModel.close(); showWorkflow = false },
          onUpdateReview = onboardingViewModel::updateReview,
        )
        showWorkflow && onboarding.busy -> Column(
          Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CircularProgressIndicator()
          Text(stringResource(R.string.onboarding_title))
          OutlinedButton(onClick = onboardingViewModel::cancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.onboarding_cancel_probe))
          }
        }
        showQuick -> QuickAddForm(
          busy = onboarding.busy,
          onStart = { base, website, secret, model ->
            if (editing) onboardingViewModel.editQuick(base, website, secret, model)
            else onboardingViewModel.startQuick(base, website, secret, model)
            showWorkflow = true
          },
          onClose = { onboardingViewModel.close(); showQuick = false; showWorkflow = false },
          initial = activeWorkflow.takeIf { editing },
        )
        showAdvanced -> AdvancedAddForm(
          busy = onboarding.busy,
          onReview = { metadata, credentials, fields ->
            if (editing) onboardingViewModel.editAdvanced(metadata, credentials, fields)
            else onboardingViewModel.startAdvanced(metadata, credentials, fields)
            showWorkflow = true
          },
          onClose = { onboardingViewModel.close(); showAdvanced = false; showWorkflow = false },
          initial = activeWorkflow.takeIf { editing },
        )
        showBatch -> BatchImportScreen(
          batches = batch.batches,
          busy = batch.busy,
          onStart = batchImportViewModel::start,
          onResume = batchImportViewModel::resume,
          onOpen = { workflowId ->
            // 單筆交棒：補 key / 改 Review / commit 都在既有單筆流程內做，批次層不碰 vault。
            batchImportViewModel.close()
            showBatch = false
            onboardingViewModel.resume(workflowId)
            showWorkflow = true
          },
          onCancel = batchImportViewModel::cancel,
          onClose = { batchImportViewModel.close(); showBatch = false },
        )
        tab == 0 -> PlaygroundTab(
          state = state,
          onSelect = viewModel::selectConnection,
          onSend = viewModel::send,
          onSendVision = viewModel::sendWithImage,
          canAttachVision = state.connections.firstOrNull { it.id == state.selectedId }?.let { connection ->
            selectedVisionCapabilities(
              rows = allModelRows,
              providerId = connection.providerId,
              providerModelId = connection.modelId,
              endpointId = connection.endpointId,
              operation = connection.operation,
              selectedGroup = selectedGroup,
            ).isNotEmpty()
          } ?: false,
          selectedGroup = selectedGroup,
          onCancel = viewModel::cancel,
          onNewConversation = viewModel::startNewConversation,
          onGotoProviders = { tab = 1 },
          showConnections = showConnections,
          onShowConnections = { showConnections = it },
        )
        // PROV-001 repair PD：Screen-local detail 態（VM 搜尋／篩選／同步行為不動）。
        tab == 1 -> {
          val detailPid = selectedProviderId
          if (detailPid != null) {
            // PK-12（H2 v2）：capability 由 VM 快取 StateFlow 同步供應（無首幀 false 閃失；
            // 未解析＝沿用 unknown→false 的隱藏，不閃 disabled）。effect 只負責刷新快取。
            val newApiSyncCapableCache by viewModel.newApiSyncCapableCache.collectAsStateWithLifecycle()
            val detailNewApiSupported = newApiSyncCapableCache[detailPid] ?: false
            LaunchedEffect(detailPid, state.managedConnections, providerDetailRetryToken) {
              viewModel.newApiSyncCapable(detailPid)
            }
            // B5：系統返回退出 detail（不退出 App）——含 load/error 畫面（error 畫面另有
            // action_back）。QuickAdd/Advanced/Batch/onboarding 的 BackHandler 位於其他
            // when 分支，與 detail 不同時組合，優先序不受影響。返回同時取消在途模型動作。
            BackHandler {
              viewModel.leaveProviderDetail(detailPid)
              selectedProviderId = null
              providerDetailState = null
              providerDetailLoadFailed = false
              providerDetailStale = false
              providerDetailAppliedScope = null
            }
            // 選定 scope 尚未重讀（或讀取失敗）時，只失效「選定 scope 的投影」：
            // 動作範圍與驗證徽章一律降級，provider 事實／歷史／模型 last-good 保留。
            val detailScopeFresh = providerDetailAppliedScope != null &&
              providerDetailAppliedScope == currentDetailScopeKey
            ProviderDetailHost(
              providerId = detailPid,
              detailState = providerDetailState?.let { detail ->
                // 只有同 provider、同選定連線、同 group 的動作才顯示；換 key 或換 group
                // 後，舊 scope 的 SUCCESS／FAILURE／token 一律不外洩到新範圍的畫面。
                val action = providerModelAction.takeIf {
                  it.providerId == detailPid &&
                    it.connectionId == state.selectedId &&
                    it.group == selectedGroup
                } ?: ProviderModelActionState()
                providerDetailScopeProjection(detail, detailScopeFresh).copy(
                  header = detail.header.copy(
                    syncing = detailPid in state.syncingProviders || detailPid in dedicatedSyncing,
                  ),
                  action = action,
                  staleLoad = providerDetailStale,
                )
              },
              loadFailed = providerDetailLoadFailed,
              onRetry = { providerDetailRetryToken++ },
              selectedTab = providerDetailTab,
              onTab = { providerDetailTab = it },
              onBack = {
                viewModel.leaveProviderDetail(detailPid)
                selectedProviderId = null
                providerDetailState = null
                providerDetailLoadFailed = false
                providerDetailStale = false
                providerDetailAppliedScope = null
              },
              onSync = viewModel::syncNewApi,
              newApiSupported = detailNewApiSupported,
              newApiOpStateFor = scopedNewApiStateFor,
              onScopedNewApiSync = { providerId, accountId, credentialId ->
                viewModel.syncNewApi(providerId, accountId, credentialId)
              },
              onCompleteCheckinByConnection = viewModel::completeCheckin,
              connections = state.managedConnections,
              selectedConnectionId = state.selectedId,
              onAddModel = viewModel::addProviderModel,
              onTestModel = viewModel::testProviderModel,
              onCancelModelAction = viewModel::cancelProviderModelAction,
              onSelectConnection = viewModel::selectConnection,
              dedicatedOpStateFor = scopedDedicatedStateFor,
              dedicatedSyncGroupFor = syncGroupFor,
              saving = state.saving,
            )
          } else {
            ProvidersTab(
          connections = state.managedConnections,
          selectedId = state.selectedId,
          saving = state.saving,
          syncingProviders = state.syncingProviders,
          dedicatedSyncing = dedicatedSyncing,
          clearToken = state.formClearToken,
          onSave = viewModel::saveConnection,
          onSelect = viewModel::selectConnection,
          onCompleteCheckin = viewModel::completeCheckin,
          onSetEnabled = viewModel::setCredentialEnabled,
          onDelete = viewModel::deleteCredential,
          onConfigureCheckin = viewModel::configureCheckin,
          onSync = viewModel::syncNewApi,
          newApiOpStateFor = scopedNewApiStateFor,
          onScopedNewApiSync = { providerId, accountId, credentialId ->
            viewModel.syncNewApi(providerId, accountId, credentialId)
          },
          onDedicatedSync = { providerId, accountId, credentialId, group ->
            viewModel.syncDedicated(providerId, accountId, credentialId, group)
          },
          isDedicatedSupported = dedicatedSupported,
          dedicatedOpStateFor = scopedDedicatedStateFor,
          dedicatedSyncGroupFor = syncGroupFor,
          onDetailClick = { pid ->
            providerDetailTab = ProviderDetailTab.OVERVIEW
            providerDetailState = null
            providerDetailLoadFailed = false
            providerDetailStale = false
            providerDetailAppliedScope = null
            selectedProviderId = pid
          },
          onboardingActions = {
            Button(onClick = { editing = false; showQuick = true }, enabled = !onboarding.busy, modifier = Modifier.heightIn(min = 48.dp)) {
              Text(stringResource(R.string.onboarding_quick))
            }
            OutlinedButton(onClick = { editing = false; showAdvanced = true }, enabled = !onboarding.busy, modifier = Modifier.heightIn(min = 48.dp)) {
              Text(stringResource(R.string.onboarding_advanced))
            }
            onboarding.resumable.forEach { draft ->
              OutlinedButton(
                onClick = { onboardingViewModel.resume(draft.workflowId); showWorkflow = true },
                enabled = !onboarding.busy,
                modifier = Modifier.heightIn(min = 48.dp),
              ) {
                Text(stringResource(R.string.onboarding_resume) + " · " +
                  (draft.providerName ?: draft.workflowId.take(8)))
              }
            }
            OutlinedButton(
              onClick = { editing = false; showBatch = true },
              enabled = !onboarding.busy && !batch.busy,
              modifier = Modifier.heightIn(min = 48.dp),
            ) {
              Text(stringResource(R.string.batch_entry))
            }
          },
          emptyStateQuickAdd = {
            Button(
              onClick = { editing = false; showQuick = true },
              enabled = !onboarding.busy,
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("provider_empty_quick_add"),
            ) {
              Text(stringResource(R.string.onboarding_quick))
            }
          },
            )
          }
        }
        else -> ModelsTab(
          modelRows = myModelRows,
          pendingCandidateKeys = pendingCandidateKeys,
          toggleInFlight = modelDetailRow?.let { MyModelKey(it.providerId, it.providerModelId) }?.let { it in myModelToggleInFlight } == true,
          toggleError = modelDetailRow?.let { MyModelKey(it.providerId, it.providerModelId) }?.let { it in myModelToggleError } == true,
          rowsLoading = modelRowsLoading,
          rowsLoadFailed = modelRowsLoadFailed,
          onRetryModelRows = { viewModel.refreshModelRows() },
          connections = state.connections,
          selectedConnectionId = state.selectedId,
          dedicatedSyncing = dedicatedSyncing,
          onDedicatedSync = { providerId, accountId, credentialId, group ->
            viewModel.syncDedicated(providerId, accountId, credentialId, group)
          },
          isDedicatedSupported = dedicatedSupported,
          dedicatedOpStateFor = scopedDedicatedStateFor,
          dedicatedSyncGroupFor = syncGroupFor,
          saving = state.saving,
          lmspeedSyncing = lmspeedSyncing,
          lmspeedSyncError = lmspeedSyncError,
          lmspeedLastSynced = lmspeedLastSynced,
          onLmspeedSync = { viewModel.syncLmspeed() },
          detailState = modelDetailState,
          compareRows = compareRows,
          compareRunning = compareRunning,
          compareError = compareError,
          hasCompareOfferings = compareOfferingsCount > 0 && hasSelectedKey,
          hasSelectedKey = hasSelectedKey,
          onDetailOpen = viewModel::openModelDetail,
          onDetailClose = viewModel::closeModelDetail,
          onCompareRun = { startCompareLive() },
          onCompareCancel = { viewModel.cancelCompareLive() },
          compareChoices = compareChoices,
          compareSelections = compareSelections,
          compareChoicesLoading = compareChoicesLoading,
          compareChoicesLoadFailed = compareChoicesLoadFailed,
          onCompareSelectionChange = viewModel::setCompareSelection,
          onCompareChoicesRetry = { modelDetailRow?.let { viewModel.refreshCompareChoices(it) } },
          isMyModel = modelDetailRow?.let {
            myModelKeys.contains(MyModelKey(it.providerId, it.providerModelId))
          } == true,
          onToggleMyModel = {
            modelDetailRow?.let { viewModel.toggleMyModel(it.providerId, it.providerModelId) }
          },
        )
      }
    }
  }
  if (showSettings) {
    // X-013 / D-005: production Settings → VM → app-owned durable store.
    val context = LocalContext.current
    val notificationManager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    fun notificationsBlocked(): Boolean =
      !NotificationPermission.hasPermission(context) ||
        notificationManager?.areNotificationsEnabled() != true ||
        notificationManager?.getNotificationChannel(NotificationPermission.CHANNEL_ID)?.importance ==
        NotificationManager.IMPORTANCE_NONE
    var permissionDenied by remember(context) { mutableStateOf(notificationsBlocked()) }
    // Only an explicit OFF → ON arms this; restoration and rule saves never do.
    var pendingMasterEnable by remember { mutableStateOf(false) }
    val onPermissionResult: (Boolean) -> Unit = { _ ->
      // The OS is authoritative, including callbacks supplied by a test registry.
      if (NotificationPermission.hasPermission(context)) NotificationPermission.ensureChannel(context)
      permissionDenied = notificationsBlocked()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.RequestPermission(),
      onPermissionResult,
    )
    DisposableEffect(lifecycleOwner, context) {
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) permissionDenied = notificationsBlocked()
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(
      pendingMasterEnable, notificationSettings.masterEnabled,
      notificationSettingsSaving, notificationSettingsSaveFailed,
    ) {
      // Wait for the existing commit-confirmed state; no VM/store API changes.
      if (pendingMasterEnable && !viewModel.notificationSettingsSaving.value) {
        if (viewModel.notificationSettingsSaveFailed.value) {
          pendingMasterEnable = false
        } else if (viewModel.notificationSettings.value.masterEnabled) {
          pendingMasterEnable = false
          if (NotificationPermission.shouldRequestOnMasterEnable(
              Build.VERSION.SDK_INT, context.checkSelfPermission(NotificationPermission.POST_NOTIFICATIONS),
            )) {
            permissionLauncher.launch(NotificationPermission.POST_NOTIFICATIONS)
          } else {
            onPermissionResult(true)
          }
        }
      }
    }
    AlertDialog(
      onDismissRequest = { showSettings = false },
      confirmButton = {
        TextButton(
          onClick = { showSettings = false },
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text(stringResource(R.string.action_close))
        }
      },
      title = { Text(stringResource(R.string.settings_title)) },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(stringResource(R.string.settings_body))
          NotificationSettingsSection(
            state = notificationSettings,
            onMasterChanged = { on ->
              viewModel.setNotificationsEnabled(on)
              pendingMasterEnable = on
            },
            onRuleChanged = viewModel::setNotificationRuleEnabled,
            enabled = !notificationSettingsSaving,
            permissionBlocked = permissionDenied,
            onOpenSystemSettings = {
              context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
              })
            },
          )
          if (notificationSettingsSaving) {
            val savingLabel = stringResource(R.string.notification_settings_saving)
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp).semantics { contentDescription = savingLabel },
              strokeWidth = 2.dp,
            )
          }
          if (notificationSettingsSaveFailed) {
            Text(
              stringResource(R.string.notification_settings_save_failed),
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
          }
          // P7-B2：Settings 可見觸發入口（全域 enrichment；失敗保舊，不寫本地 telemetry）。
          OutlinedButton(
            onClick = { viewModel.syncLmspeed() },
            enabled = !lmspeedSyncing,
            modifier = Modifier.heightIn(min = 48.dp),
          ) {
            if (lmspeedSyncing) {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.status_syncing))
            } else {
              Text(stringResource(R.string.action_sync) + " · LMSpeed")
            }
          }
          LmspeedSyncStatusLines(
            syncing = lmspeedSyncing,
            error = lmspeedSyncError,
            lastSynced = lmspeedLastSynced,
          )
        }
      },
    )
  }
}

/** PLAY-001：頂欄與 composer 共用同一張連線選單，不另建模型選擇真相。 */
@Composable
private fun PlaygroundTopBarTitle(connection: ConnectionItem?, compact: Boolean, onSelect: () -> Unit) {
  val switchLabel = stringResource(R.string.playground_switch_model)
  val selectedContext = connection?.let { "${it.modelId} · ${it.name} · ${it.credentialLabel}" }
    ?: stringResource(R.string.connection_none)
  TextButton(
    onClick = onSelect,
    modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch)
      .semantics { contentDescription = switchLabel; stateDescription = selectedContext },
    contentPadding = PaddingValues(end = AtlasSpace.sm),
    shape = AtlasRadius.field,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
      if (!compact) Text(
        stringResource(R.string.title_playground),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        connection?.modelId ?: stringResource(R.string.connection_choose),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(AtlasSpace.icon))
  }
}

/** IME／短視窗／大字模式保留三個至少 48dp 的分頁，把垂直空間還給聊天與表單。 */
@Composable
private fun AtlasBottomNavigation(selectedTab: Int, compact: Boolean, onSelect: (Int) -> Unit) {
  val labels = listOf(
    stringResource(R.string.tab_playground),
    stringResource(R.string.tab_providers),
    stringResource(R.string.tab_models),
  )
  val icons = listOf(Icons.AutoMirrored.Filled.Send, Icons.Filled.AccountCircle, Icons.Filled.Search)
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      if (compact) {
        Row(
          modifier = Modifier.fillMaxWidth().windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(horizontal = AtlasSpace.md, vertical = AtlasSpace.xs),
          horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
        ) {
          labels.forEachIndexed { index, label ->
            val isSelected = selectedTab == index
            Surface(
              onClick = { onSelect(index) },
              modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.touch)
                .semantics { selected = isSelected; role = Role.Tab; contentDescription = label },
              shape = AtlasRadius.pill,
              color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
              contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(AtlasSpace.xs)) {
                Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
              }
            }
          }
        }
      } else {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
          labels.forEachIndexed { index, label ->
            NavigationBarItem(
              selected = selectedTab == index,
              onClick = { onSelect(index) },
              icon = { Icon(icons[index], contentDescription = null, modifier = Modifier.size(AtlasSpace.icon)) },
              label = { Text(label) },
              colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
              ),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProvidersTab(
  connections: List<ConnectionItem>,
  selectedId: String?,
  saving: Boolean,
  syncingProviders: Set<String>,
  dedicatedSyncing: Set<String> = emptySet(),
  dedicatedSyncError: Map<String, String?> = emptyMap(),
  dedicatedLastSynced: Map<String, Long> = emptyMap(),
  dedicatedLatestObserved: Map<String, Long> = emptyMap(),
  clearToken: Int,
  onSave: (String, String, String, String) -> Unit,
  onSelect: (String) -> Unit,
  onCompleteCheckin: (String) -> Unit,
  onSetEnabled: (String, Boolean) -> Unit,
  onDelete: (String) -> Unit,
  onConfigureCheckin: (String, String) -> Unit,
  onSync: (String) -> Unit,
  onDedicatedSync: (String, String?, String?, String?) -> Unit = { _, _, _, _ -> },
  isDedicatedSupported: (String) -> Boolean = { false },
  // PROV-001 repair PD：卡片點選→detail（Screen-local；既有儲存／選擇／同步行為不動）。
  onDetailClick: (String) -> Unit = {},
  onboardingActions: @Composable () -> Unit,
  dedicatedOpStateFor: (ConnectionItem, String?) -> OpState = { _, _ -> OpState() },
  dedicatedSyncGroupFor: (ConnectionItem) -> String? = { null },
  newApiOpStateFor: (ConnectionItem) -> OpState = { OpState() },
  // Additive adapter port; bind only after the VM scoped New API entry has landed.
  onScopedNewApiSync: ((String, String?, String?) -> Unit)? = null,
  emptyStateQuickAdd: (@Composable () -> Unit)? = null,
) {
  var name by rememberSaveable { mutableStateOf("") }
  var baseUrl by rememberSaveable { mutableStateOf("") }
  var modelId by rememberSaveable { mutableStateOf("") }
  var secret by remember { mutableStateOf("") }
  // One-shot clear flag: saving success is the only trigger. Never keyed on the
  // global error channel, so an unrelated error cannot leave the secret in memory.
  LaunchedEffect(clearToken) {
    if (clearToken > 0) {
      name = ""
      baseUrl = ""
      modelId = ""
      secret = ""
    }
  }
  val canSave = name.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank() &&
    secret.isNotBlank() && !saving
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("providers_tab_list"),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = PaddingValues(16.dp),
  ) {
    item(key = "header_actions") {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        onboardingActions()
      }
    }
    item(key = "form") {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = stringResource(R.string.form_title),
          style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.label_name)) },
          placeholder = { Text(stringResource(R.string.hint_name)) },
          enabled = !saving,
          singleLine = true,
        )
        OutlinedTextField(
          value = baseUrl,
          onValueChange = { baseUrl = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.label_base_url)) },
          placeholder = { Text(stringResource(R.string.hint_base_url)) },
          supportingText = { Text(stringResource(R.string.helper_base_url)) },
          enabled = !saving,
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
          value = modelId,
          onValueChange = { modelId = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.label_model)) },
          placeholder = { Text(stringResource(R.string.hint_model)) },
          enabled = !saving,
          singleLine = true,
        )
        OutlinedTextField(
          value = secret,
          onValueChange = { secret = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.label_key)) },
          placeholder = { Text(stringResource(R.string.hint_key)) },
          supportingText = { Text(stringResource(R.string.helper_key)) },
          enabled = !saving,
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(
          onClick = { onSave(name, baseUrl, modelId, secret) },
          enabled = canSave,
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          if (saving) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.status_saving))
          } else {
            Text(stringResource(R.string.action_save))
          }
        }
      }
    }
    if (connections.isEmpty()) {
      item(key = "empty") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = stringResource(R.string.empty_providers),
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text = stringResource(R.string.empty_providers_body),
            style = MaterialTheme.typography.bodyMedium,
          )
          if (emptyStateQuickAdd != null) {
            Spacer(modifier = Modifier.height(8.dp))
            emptyStateQuickAdd()
          }
        }
      }
    } else {
      items(connections, key = { it.id }) { connection ->
        val syncGroup = dedicatedSyncGroupFor(connection)
        ConnectionCard(
          item = connection,
          isSelected = connection.id == selectedId,
          onSelect = onSelect,
          onCompleteCheckin = onCompleteCheckin,
          saving = saving,
          syncingProviders = syncingProviders,
          dedicatedSyncing = dedicatedSyncing,
          onSetEnabled = onSetEnabled,
          onDelete = onDelete,
          onConfigureCheckin = onConfigureCheckin,
          onSync = onScopedNewApiSync?.let { scopedNewApiSyncFor(connection, it) } ?: onSync,
          onDedicatedSync = onDedicatedSync,
          isDedicatedSupported = isDedicatedSupported,
          onDetailClick = onDetailClick,
          dedicatedOpState = dedicatedOpStateFor(connection, syncGroup),
          dedicatedSyncGroup = syncGroup,
          newApiOpState = if (onScopedNewApiSync != null) newApiOpStateFor(connection) else null,
        )
      }
    }
  }
}

/**
 * PROV-001 repair PD：Provider Detail 掛載容器（Screen-local 導航；VM 不動）。
 * - detailState null 且 loading＝讀取中（只顯示 spinner，不捏造）。
 * - loadFailed＝error_load＋action_retry＋返回，不無限讀取中；retry 經 retry token 重觸發。
 * - onSync 走既有 New API 產品流；onCompleteCheckin 走既有 checkin 流；
 *   onOpenCheckin 只開 plain https（與 ConnectionCard 同規則）。
 * - PROV-001／MODEL-006：模型動作只有在**已選連線屬於本 provider 且可用**時才接線；
 *   未選／跨 provider／停用時傳 null（畫面顯示選擇指引並保持 disabled），不猜另一把 key。
 */
@Composable
private fun ProviderDetailHost(
  providerId: String,
  detailState: ProviderDetailState?,
  loadFailed: Boolean,
  onRetry: () -> Unit,
  selectedTab: ProviderDetailTab,
  onTab: (ProviderDetailTab) -> Unit,
  onBack: () -> Unit,
  onSync: (String) -> Unit,
  onCompleteCheckinByConnection: (String) -> Unit,
  connections: List<ConnectionItem>,
  selectedConnectionId: String? = null,
  onAddModel: ((providerId: String, modelId: String) -> Unit)? = null,
  onTestModel: ((providerId: String, modelId: String) -> Unit)? = null,
  onCancelModelAction: (() -> Unit)? = null,
  onSelectConnection: ((String) -> Unit)? = null,
  dedicatedOpStateFor: (ConnectionItem, String?) -> OpState = { _, _ -> OpState() },
  dedicatedSyncGroupFor: (ConnectionItem) -> String? = { null },
  saving: Boolean = false,
  newApiOpStateFor: (ConnectionItem) -> OpState = { OpState() },
  onScopedNewApiSync: ((String, String?, String?) -> Unit)? = null,
  newApiSupported: Boolean = false,
) {
  val context = LocalContext.current
  if (detailState == null) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (loadFailed) {
        Text(
          text = stringResource(R.string.error_load),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
          Text(stringResource(R.string.action_retry))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
          Text(stringResource(R.string.action_back))
        }
      } else {
        CircularProgressIndicator()
      }
    }
    return
  }
  // 已選連線必須屬於本 provider 且可用（auth=NONE 的真實匿名列恆可用）才供應 scope。
  val selectedForDetail = connections
    .firstOrNull { it.id == selectedConnectionId && it.providerId == providerId && it.enabled }
  val checkinTarget = selectedForDetail
  // 畫面已載入的 scope 必須仍是目前選定連線；換 key／換 group 後未重讀前一律停用動作。
  val scopeUsable = selectedForDetail != null &&
    detailState.actionScope?.connectionId == selectedForDetail.id
  val testModel = onTestModel
  val addModel = onAddModel
  val syncConnection = selectedForDetail
  val syncGroup = syncConnection?.let(dedicatedSyncGroupFor)
  val scopedNewApiConnection = syncConnection.takeIf { onScopedNewApiSync != null }
  val syncScopeLabel = syncConnection?.let {
    val credential = when {
      it.credentialId.isBlank() -> stringResource(R.string.connection_card_anonymous)
      it.credentialLabel.isBlank() -> stringResource(R.string.connection_card_unnamed_credential)
      else -> it.credentialLabel
    }
    stringResource(R.string.connection_card_scope, credential, it.modelId) +
      (syncGroup?.let { group -> " · " + stringResource(R.string.provider_detail_group, group) } ?: "")
  }
  ProviderDetailScreen(
    state = detailState.copy(selectedTab = selectedTab),
    onBack = onBack,
    onTab = onTab,
    onSync = {
      val scopedSync = onScopedNewApiSync
      if (scopedSync != null) {
        scopedNewApiConnection?.let { scopedNewApiSyncFor(it, scopedSync)(providerId) }
      } else {
        onSync(providerId)
      }
    },
    syncEnabled = onScopedNewApiSync == null || scopedNewApiConnection != null,
    newApiSyncSupported = newApiSupported,
    newApiOpState = scopedNewApiConnection?.let(newApiOpStateFor),
    dedicatedOpState = syncConnection?.let { dedicatedOpStateFor(it, syncGroup) },
    dedicatedSyncScopeLabel = syncScopeLabel,
    saving = saving,
    // VM 端點是 (providerId, modelId)；providerId 由本頁綁定，modelId 由列／輸入帶入。
    onTestNow = if (scopeUsable && testModel != null) {
      { modelId: String -> testModel(providerId, modelId) }
    } else {
      null
    },
    onAddModel = if (scopeUsable && addModel != null) {
      { modelId: String -> addModel(providerId, modelId) }
    } else {
      null
    },
    onCancelAction = { onCancelModelAction?.invoke() },
    onSelectConnection = onSelectConnection,
    onOpenCheckin = { url ->
      val safeUri = runCatching { Uri.parse(url) }.getOrNull()
        ?.takeIf { it.scheme == "https" }
      if (safeUri != null) {
        try {
          context.startActivity(Intent(Intent.ACTION_VIEW, safeUri))
        } catch (_: Exception) {
        }
      }
    },
    onCompleteCheckin = checkinTarget?.let { target ->
      { onCompleteCheckinByConnection(target.id) }
    },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionCard(
  item: ConnectionItem,
  isSelected: Boolean,
  onSelect: (String) -> Unit,
  onCompleteCheckin: (String) -> Unit,
  saving: Boolean,
  syncingProviders: Set<String>,
  onSync: (String) -> Unit,
  dedicatedSyncing: Set<String> = emptySet(),
  dedicatedSyncError: Map<String, String?> = emptyMap(),
  dedicatedLastSynced: Map<String, Long> = emptyMap(),
  dedicatedLatestObserved: Map<String, Long> = emptyMap(),
  onDedicatedSync: (String, String?, String?, String?) -> Unit = { _, _, _, _ -> },
  isDedicatedSupported: (String) -> Boolean = { false },
  onSetEnabled: (String, Boolean) -> Unit,
  onDelete: (String) -> Unit,
  onConfigureCheckin: (String, String) -> Unit,
  // Navigation is provider-only; selecting a credential remains an explicit sheet action.
  onDetailClick: (String) -> Unit = {},
  dedicatedOpState: OpState = OpState(),
  dedicatedSyncGroup: String? = null,
  // null denotes the existing provider-only New API operation, with no scoped result claims.
  newApiOpState: OpState? = null,
) {
  val context = LocalContext.current
  val checkinUrl = item.checkinUrl
  var confirmDelete by remember(item.id) { mutableStateOf(false) }
  var editCheckin by remember(item.id) { mutableStateOf(false) }
  var checkinInput by remember(item.id) { mutableStateOf(item.checkinUrl.orEmpty()) }
  var showActions by remember(item.id) { mutableStateOf(false) }
  var moreWidthPixels by remember(item.id) { mutableIntStateOf(0) }
  val credentialText = when {
    item.credentialId.isBlank() -> stringResource(R.string.connection_card_anonymous)
    item.credentialLabel.isBlank() -> stringResource(R.string.connection_card_unnamed_credential)
    else -> item.credentialLabel
  }
  val identity = stringResource(R.string.connection_card_identity, item.name, credentialText, item.modelId)
  val openLabel = stringResource(R.string.connection_card_open_label, item.name)
  val moreLabel = stringResource(R.string.connection_card_more_label, item.name, credentialText, item.modelId)
  val syncInProgress = item.providerId in syncingProviders || item.providerId in dedicatedSyncing
  val syncFailed = dedicatedOpState.error != null || newApiOpState?.error != null
  val stateText = listOfNotNull(
    stringResource(R.string.status_selected).takeIf { isSelected },
    stringResource(R.string.credential_disabled).takeIf { !item.enabled },
    stringResource(R.string.status_syncing).takeIf { syncInProgress },
    stringResource(R.string.connection_card_sync_failed).takeIf { syncFailed },
    stringResource(R.string.connection_card_saving).takeIf { saving },
  ).joinToString("，")
  val knownHealth = setOf("HEALTHY", "DEGRADED", "UNSTABLE", "OFFLINE")
  val health = when {
    item.officialHealth in knownHealth -> item.officialHealth
    item.observedHealth in knownHealth -> item.observedHealth
    else -> null
  }
  val healthText = when (health) {
    "HEALTHY" -> stringResource(R.string.connection_card_health_healthy)
    "DEGRADED" -> stringResource(R.string.connection_card_health_degraded)
    "UNSTABLE" -> stringResource(R.string.connection_card_health_unstable)
    "OFFLINE" -> stringResource(R.string.connection_card_health_offline)
    else -> null
  }
  val moreWidth = with(LocalDensity.current) { moreWidthPixels.toDp() }
  val knownQuota = compareQuotaStateOf(item.quotaState)
  Card(modifier = Modifier.fillMaxWidth()) {
    // The card face and More are siblings: no nested clickable and no key selection on nav.
    Box(Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.fillMaxWidth()
          .testTag("connection_card_${item.id}")
          .clickable(enabled = !saving, role = Role.Button, onClickLabel = openLabel) {
            onDetailClick(item.providerId)
          }
          .semantics(mergeDescendants = true) {
            contentDescription = identity
            selected = isSelected
            stateDescription = stateText
          }
          .padding(AtlasSpace.lg),
        verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(end = maxOf(moreWidth, AtlasSpace.touch) + AtlasSpace.sm),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
        ) {
          Surface(
            modifier = Modifier.size(40.dp).clearAndSetSemantics { }, shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ) {
            Box(contentAlignment = Alignment.Center) {
              val name = item.name.trim()
              val glyph = if (name.isEmpty()) "?" else String(Character.toChars(name.codePointAt(0)))
              Text(glyph, style = MaterialTheme.typography.labelLarge)
            }
          }
          Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(R.string.connection_card_scope, credentialText, item.modelId),
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.xs),
          verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
          if (isSelected) AtlasBadge(stringResource(R.string.status_selected), AtlasTone.ACCENT)
          if (!item.enabled) AtlasBadge(stringResource(R.string.credential_disabled))
          if (healthText != null) AtlasBadge(stringResource(
            if (item.officialHealth in knownHealth) R.string.connection_card_official_health
            else R.string.connection_card_local_health, healthText))
          if (item.checkinPending) AtlasBadge(stringResource(R.string.notification_rule_pending_checkin), AtlasTone.CAUTION)
          if (knownQuota.name != "UNKNOWN") AtlasBadge(stringResource(quotaDisplayLabel(knownQuota)))
        }
        if (syncInProgress) Row(verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
          CircularProgressIndicator(Modifier.size(18.dp).testTag("connection_sync_progress_${item.id}"), strokeWidth = 2.dp)
          Text(stringResource(R.string.status_syncing), style = MaterialTheme.typography.labelMedium)
        }
        if (syncFailed) Text(stringResource(R.string.connection_card_sync_failed),
          Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
      }
      TextButton(
        onClick = { showActions = true },
        // This sibling owns its full rectangular hit region, including corners.
        // A rounded clip would let corner taps reach the primary card underneath.
        shape = RectangleShape,
        modifier = Modifier.align(Alignment.TopEnd).padding(AtlasSpace.xs)
          .onSizeChanged { moreWidthPixels = it.width }
          .heightIn(min = AtlasSpace.touch)
          .testTag("connection_more_${item.id}")
          .semantics { contentDescription = moreLabel },
      ) {
        Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(AtlasSpace.icon))
        Text(stringResource(R.string.connection_card_more))
      }
    }
  }
  if (showActions) ModalBottomSheet(
    onDismissRequest = { showActions = false },
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.testTag("connection_menu_${item.id}"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(AtlasSpace.lg),
      verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
    ) {
      Text(stringResource(R.string.connection_card_actions), style = MaterialTheme.typography.titleLarge)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = item.name,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        if (isSelected) {
          Text(
            text = stringResource(R.string.status_selected),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
      Text(text = item.baseUrl, style = MaterialTheme.typography.bodySmall)
      Text(
        text = stringResource(R.string.label_model) + ": " + item.modelId,
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text = stringResource(R.string.label_credential) + ": " + credentialText,
        style = MaterialTheme.typography.bodySmall,
      )
      dedicatedSyncGroup?.let {
        Text(stringResource(R.string.provider_detail_group, it), style = MaterialTheme.typography.bodySmall)
      }
      Text(
        text = stringResource(if (item.enabled) R.string.credential_enabled else R.string.credential_disabled),
        style = MaterialTheme.typography.labelLarge,
        color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = stringResource(R.string.label_price) + ": " + stringResource(priceBadgeText(comparePriceStateOf(item.priceState))),
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text = stringResource(R.string.label_quota) + ": " + stringResource(quotaDisplayLabel(compareQuotaStateOf(item.quotaState))),
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text = stringResource(R.string.label_official_health) + ": " + item.officialHealth,
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text = stringResource(R.string.label_observed_health) + ": " + item.observedHealth,
        style = MaterialTheme.typography.bodySmall,
      )
      if (!isSelected || checkinUrl != null || item.supportsSync || isDedicatedSupported(item.providerId)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (!isSelected && item.enabled) {
            OutlinedButton(
              onClick = { onSelect(item.id) },
              enabled = !saving,
              modifier = Modifier.heightIn(min = 48.dp).testTag("connection_select_${item.id}"),
            ) {
              Text(stringResource(R.string.action_select))
            }
          }
          // Scoped New API uses its own busy state once supplied. The legacy entry is
          // still provider-only until the separately owned VM ports land.
          if (item.supportsSync) {
            val syncingNewApi = newApiOpState?.syncing ?: (item.providerId in syncingProviders)
            val operationLabel = stringResource(R.string.action_sync_new_api, stringResource(R.string.action_sync))
            val syncingLabel = stringResource(R.string.status_syncing)
            OutlinedButton(
              onClick = { onSync(item.providerId) },
              enabled = !saving && !syncingNewApi,
              modifier = Modifier.heightIn(min = 48.dp).testTag("connection_sync_${item.id}")
                .semantics {
                  contentDescription = operationLabel
                  if (syncingNewApi) stateDescription = syncingLabel
                },
            ) {
              SyncOperationButtonContent(operationLabel, syncingNewApi, syncingLabel)
            }
          }
          // Read, dispatch, and disable the same exact operation scope.
          if (isDedicatedSupported(item.providerId)) {
            val syncingDedicated = dedicatedOpState.syncing
            val operationLabel = stringResource(R.string.connection_card_dedicated_sync)
            val syncingLabel = stringResource(R.string.status_syncing)
            OutlinedButton(
              onClick = {
                onDedicatedSync(
                  item.providerId,
                  item.accountId.takeIf { it.isNotBlank() },
                  item.credentialId.takeIf { it.isNotBlank() },
                  dedicatedSyncGroup,
                )
              },
              enabled = !saving && !syncingDedicated,
              modifier = Modifier.heightIn(min = 48.dp).testTag("connection_dedicated_sync_${item.id}")
                .semantics {
                  contentDescription = operationLabel
                  if (syncingDedicated) stateDescription = syncingLabel
                },
            ) {
              SyncOperationButtonContent(operationLabel, syncingDedicated, syncingLabel)
            }
          }
          if (checkinUrl != null) {
            // External content is untrusted: only plain https links, never custom schemes.
            val safeUri = runCatching { Uri.parse(checkinUrl) }.getOrNull()
              ?.takeIf { it.scheme == "https" }
            if (safeUri != null) {
              Button(
                onClick = {
                  try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, safeUri))
                  } catch (_: Exception) {
                  }
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("connection_checkin_open_${item.id}"),
              ) {
                Text(stringResource(R.string.action_open_checkin))
              }
            }
            if (item.checkinPending) {
              OutlinedButton(
                onClick = { onCompleteCheckin(item.id) },
                enabled = !saving,
                modifier = Modifier.heightIn(min = 48.dp).testTag("connection_checkin_complete_${item.id}"),
              ) {
                Text(stringResource(R.string.action_complete_checkin))
              }
            }
          }
        }
      }
      newApiOpState?.let {
        NewApiSyncStatusLines(it, Modifier.testTag("connection_newapi_status_${item.id}"))
      }
      DedicatedSyncStatusLines(
        opState = dedicatedOpState,
        modifier = Modifier.testTag("connection_dedicated_status_${item.id}"),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.credentialId.isNotBlank()) {
          TextButton(
            onClick = { onSetEnabled(item.id, !item.enabled) },
            enabled = !saving,
            modifier = Modifier.heightIn(min = 48.dp).testTag("connection_toggle_enabled_${item.id}"),
          ) { Text(stringResource(if (item.enabled) R.string.action_disable_credential else R.string.action_enable_credential)) }
        }
        TextButton(
          onClick = { checkinInput = item.checkinUrl.orEmpty(); showActions = false; editCheckin = true },
          enabled = !saving,
          modifier = Modifier.heightIn(min = 48.dp).testTag("connection_configure_checkin_${item.id}"),
        ) { Text(stringResource(R.string.action_configure_checkin)) }
        if (item.credentialId.isNotBlank()) {
          TextButton(
            onClick = { showActions = false; confirmDelete = true },
            enabled = !saving,
            modifier = Modifier.heightIn(min = 48.dp).testTag("connection_delete_${item.id}"),
          ) { Text(stringResource(R.string.action_delete_credential), color = MaterialTheme.colorScheme.error) }
        }
      }
    }
  }
  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(stringResource(R.string.action_delete_credential)) },
      text = { Text(stringResource(R.string.connection_card_delete_target, item.name, credentialText)) },
      confirmButton = {
        TextButton(
          onClick = { confirmDelete = false; onDelete(item.id) },
          enabled = !saving,
          modifier = Modifier.heightIn(min = 48.dp).testTag("connection_delete_confirm_${item.id}"),
        ) {
          Text(stringResource(R.string.action_delete_credential))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { confirmDelete = false },
          modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }
  if (editCheckin) {
    AlertDialog(
      onDismissRequest = { editCheckin = false },
      title = { Text(stringResource(R.string.action_configure_checkin)) },
      text = {
        // B6：簽到 URL 輸入＋鍵盤可能遮擋——imePadding＋有界可捲動文字區（小屏不卡輸入）。
        Column(
          modifier = Modifier
            .imePadding()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(stringResource(R.string.connection_card_checkin_shared))
          OutlinedTextField(
            value = checkinInput,
            onValueChange = { checkinInput = it },
            label = { Text(stringResource(R.string.label_checkin_url)) },
            supportingText = { Text(stringResource(R.string.helper_checkin_url)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.testTag("connection_checkin_input_${item.id}"),
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = { onConfigureCheckin(item.id, checkinInput); editCheckin = false },
          enabled = !saving,
          modifier = Modifier.heightIn(min = 48.dp).testTag("connection_checkin_save_${item.id}"),
        ) {
          Text(stringResource(R.string.action_save_checkin))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { editCheckin = false },
          modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }
}

/**
 * P6-C MAJOR10：Dedicated 同步狀態行（Providers／Models 卡片共用）。
 * 錯誤只顯示分類訊息（AUTH／NETWORK／UNSUPPORTED／UNKNOWN→既有字串；禁 URL／body／secret）；
 * 資料時間顯示 latestObservedAt（觀測時間）；觸發時間明示「同步觸發時間」（裝置時區）。
 */
@Composable
private fun DedicatedSyncStatusLines(
  providerId: String,
  dedicatedSyncError: Map<String, String?>,
  dedicatedLastSynced: Map<String, Long>,
  dedicatedLatestObserved: Map<String, Long> = emptyMap(),
) {
  DedicatedSyncStatusLines(OpState(
    error = dedicatedSyncError[providerId],
    lastSynced = dedicatedLastSynced[providerId],
    latestObserved = dedicatedLatestObserved[providerId],
  ))
}

@Composable
internal fun DedicatedSyncStatusLines(opState: OpState, modifier: Modifier = Modifier) {
  ScopedSyncStatusLines(opState, modifier)
}

@Composable
internal fun NewApiSyncStatusLines(opState: OpState, modifier: Modifier = Modifier) {
  ScopedSyncStatusLines(opState, modifier)
}

@Composable
private fun ScopedSyncStatusLines(opState: OpState, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
    val code = opState.error
    if (code != null) {
      val message = when (code) {
        "AUTH" -> stringResource(R.string.error_auth)
        "NETWORK" -> stringResource(R.string.error_network)
        "UNSUPPORTED" -> stringResource(R.string.error_sync_unavailable)
        // CR-002 A2-A4：New API scoped lane 的 BUSY（被拒的觸發，非失敗）。
        // 純加臂：dedicated lane 仍寫 "UNKNOWN"，故 dedicated 行為零改變；
        // 沿用既有 error_busy 字串（無新字串、無新 locale 檔）。
        "BUSY" -> stringResource(R.string.error_busy)
        else -> stringResource(R.string.error_sync_failed)
      }
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val latestObserved = opState.latestObserved
    if (latestObserved != null) {
      Text(
        text = stringResource(R.string.label_observed_health) + ": " +
          remember(latestObserved) { dateFormat.format(Date(latestObserved)) },
        style = MaterialTheme.typography.bodySmall,
      )
    }
    val lastSyncedAt = opState.lastSynced
    if (lastSyncedAt != null) {
      Text(
        text = stringResource(
          R.string.sync_triggered_at_format,
          remember(lastSyncedAt) { dateFormat.format(Date(lastSyncedAt)) },
        ),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

/** Stable row + chosen connection hook, not rank/index or an ambiguous delimiter join. */
internal fun modelDedicatedSyncTag(row: ModelRow, connection: ConnectionItem): String =
  "model_dedicated_sync_" + listOf(row.providerId, row.providerModelId, row.canonicalId, connection.id)
    .joinToString("|") { "${it.length}:$it" }

@Composable
private fun ModelsTab(
  modelRows: List<ModelRow>,
  rowsLoading: Boolean,
  rowsLoadFailed: Boolean,
  onRetryModelRows: () -> Unit,
  connections: List<ConnectionItem> = emptyList(),
  // PROV-009：selected credential 對應——trailing sync 優先 selected ConnectionItem，再明確 fallback。
  selectedConnectionId: String? = null,
  dedicatedSyncing: Set<String> = emptySet(),
  dedicatedSyncError: Map<String, String?> = emptyMap(),
  dedicatedLastSynced: Map<String, Long> = emptyMap(),
  dedicatedLatestObserved: Map<String, Long> = emptyMap(),
  onDedicatedSync: (String, String?, String?, String?) -> Unit = { _, _, _, _ -> },
  isDedicatedSupported: (String) -> Boolean = { false },
  // P7-B2：LMSpeed 全域 enrichment 觸發＋狀態（追加；既有 dedicated／搜尋／篩選行為不動）。
  lmspeedSyncing: Boolean = false,
  lmspeedSyncError: String? = null,
  lmspeedLastSynced: Long? = null,
  onLmspeedSync: () -> Unit = {},
  // P6-DC（UI 入口接線）：detail／compare 接線參數（全預設值；既有同步鈕／搜尋／篩選行為不動）。
  // detailState 非 null＝detail 態（VM 的 modelDetailRow 為單一真相；內容由 Screen 經
  // ModelDetailLoader 從 DB 真載入，失敗退回 VM 透傳）；compare 區塊掛於歷史區下方。
  // P6 repair worker B：compareError＝頂層錯誤分類碼（null＝無錯誤；逐列失敗在卡片內）；
  // hasSelectedKey 來自已選連線（Filter selected-key 維度可用性）。
  detailState: ModelDetailState? = null,
  compareRows: List<CompareLiveRowResult> = emptyList(),
  compareRunning: Boolean = false,
  compareError: String? = null,
  hasCompareOfferings: Boolean = false,
  hasSelectedKey: Boolean = false,
  onDetailOpen: (ModelRow) -> Unit = {},
  onDetailClose: () -> Unit = {},
  onCompareRun: () -> Unit = {},
  onCompareCancel: () -> Unit = {},
  compareChoices: List<CompareKeyChoice> = emptyList(),
  compareSelections: Set<CompareKeySelection> = emptySet(),
  compareChoicesLoading: Boolean = false,
  compareChoicesLoadFailed: Boolean = false,
  onCompareSelectionChange: (CompareKeySelection, Boolean) -> Unit = { _, _ -> },
  onCompareChoicesRetry: () -> Unit = {},
  isMyModel: Boolean = false,
  onToggleMyModel: () -> Unit = {},
  dedicatedOpStateFor: (ConnectionItem, String?) -> OpState = { _, _ -> OpState() },
  dedicatedSyncGroupFor: (ConnectionItem) -> String? = { null },
  saving: Boolean = false,
  pendingCandidateKeys: Set<MyModelKey> = emptySet(),
  toggleInFlight: Boolean = false,
  toggleError: Boolean = false,
) {
  var query by rememberSaveable { mutableStateOf("") }
  var filterState by remember { mutableStateOf(FilterState()) }
  var showFilterSheet by remember { mutableStateOf(false) }
  // P6 repair worker B：FilterSheet 真 availability（從當前 modelRows 推導；無資料 disabled 不造假）。
  val filterAvailability = remember(modelRows, hasSelectedKey) {
    FilterAvailabilityWiring.derive(modelRows, hasSelectedKey)
  }
  val hasActiveFilter = activeFilterCount(filterState) > 0
  val filtered = remember(modelRows, filterState) {
    filterModels(modelRows, filterState.toModelFilter())
  }
  val ranked = remember(query, filtered) {
    rankResults(query, filtered)
  }
  // PROV-009：同 provider 多憑證時，先取 selected（id == selectedConnectionId），
  // 再明確 fallback 同 provider 第一筆（列表順序決定，不用 associateBy 的任意最後一筆）。
  val connectionByProvider = remember(connections, selectedConnectionId) {
    connections.groupBy { it.providerId }.mapValues { (_, candidates) ->
      candidates.firstOrNull { it.id == selectedConnectionId } ?: candidates.first()
    }
  }
  if (detailState != null) {
    // B5：系統返回退出 model detail（不退出 App），與 Provider Detail :635-645 同模式。
    BackHandler {
      onDetailClose()
    }
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(
        onClick = onDetailClose,
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text(stringResource(R.string.action_back))
      }
      ModelDetailScreen(state = detailState)
      ModelDetailProvenanceSection(state = detailState)
      // ③-1 My Models 明確加入／移除（controlled：狀態由 VM Flow 真值驅動）。
      MyModelsToggle(
        isMyModel = isMyModel,
        onToggle = onToggleMyModel,
        inFlight = toggleInFlight,
        error = toggleError,
      )
      if (compareChoicesLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      if (compareChoicesLoadFailed) {
        OutlinedButton(
          onClick = onCompareChoicesRetry,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
          Text(stringResource(R.string.compare_key_load_failed_retry))
        }
      } else if (compareChoices.isNotEmpty()) {
        CompareKeyPicker(
          choices = compareChoices,
          selections = compareSelections,
          onSelectionChange = onCompareSelectionChange,
          enabled = !compareRunning,
        )
      }
      CompareLiveTable(
        canonicalId = detailState.canonicalId,
        rows = compareRows,
        onRunClick = onCompareRun,
        running = compareRunning,
        hasOfferings = hasCompareOfferings,
        error = compareError,
        onCancelClick = onCompareCancel,
      )
    }
  } else {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // P7-B2：ModelsTab 可見觸發入口（全域 enrichment；只寫 external 槽，失敗保舊）。
    OutlinedButton(
      onClick = onLmspeedSync,
      enabled = !lmspeedSyncing,
      modifier = Modifier.heightIn(min = 48.dp),
    ) {
      if (lmspeedSyncing) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.status_syncing))
      } else {
        Text(stringResource(R.string.action_sync) + " · LMSpeed")
      }
    }
    LmspeedSyncStatusLines(
      syncing = lmspeedSyncing,
      error = lmspeedSyncError,
      lastSynced = lmspeedLastSynced,
    )
    ModelSearchBar(
      query = query,
      onQueryChange = { query = it },
      onFilterClick = { showFilterSheet = true },
      activeFilterCount = activeFilterCount(filterState),
    )
    Box(modifier = Modifier.weight(1f)) {
      if (modelRows.isEmpty() && rowsLoading) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
          LinearProgressIndicator(Modifier.fillMaxWidth())
          Text(stringResource(R.string.models_loading), style = MaterialTheme.typography.bodyMedium)
        }
      } else if (modelRows.isEmpty() && rowsLoadFailed) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
          Text(stringResource(R.string.error_load), color = MaterialTheme.colorScheme.error)
          OutlinedButton(onClick = onRetryModelRows, modifier = Modifier.heightIn(min = AtlasSpace.touch)) {
            Text(stringResource(R.string.action_retry))
          }
        }
      } else if (modelRows.isEmpty() || ranked.isEmpty()) {
        // MODEL-005：搜尋／篩選後空態提供既有 state 的清除動作（query＋filter 一鍵重設）。
        ModelSearchEmpty(
          hasActiveFilter = hasActiveFilter,
          action = {
            OutlinedButton(
              onClick = { query = ""; filterState = FilterState() },
              modifier = Modifier.heightIn(min = AtlasSpace.touch),
            ) { Text(stringResource(R.string.action_reset)) }
          },
        )
      } else {
        ModelSearchResults(
          rows = ranked,
          resultsCapped = ranked.size >= 100, // Matches the rankResults default cap of 100.
          onRowClick = onDetailOpen,
          pendingCandidateKeys = pendingCandidateKeys,
          hasActiveFilter = hasActiveFilter,
          trailingContent = { row ->
            val connection = connectionByProvider[row.providerId]
            if (connection != null) {
              if (isDedicatedSupported(row.providerId)) {
                val syncGroup = dedicatedSyncGroupFor(connection)
                val opState = dedicatedOpStateFor(connection, syncGroup)
                val syncingDedicated = opState.syncing
                val operationLabel = stringResource(R.string.connection_card_dedicated_sync)
                val syncingLabel = stringResource(R.string.status_syncing)
                val credentialText = when {
                  connection.credentialId.isBlank() -> stringResource(R.string.connection_card_anonymous)
                  connection.credentialLabel.isBlank() -> stringResource(R.string.connection_card_unnamed_credential)
                  else -> connection.credentialLabel
                }
                Text(stringResource(R.string.connection_card_scope, credentialText, connection.modelId),
                  style = MaterialTheme.typography.bodySmall)
                syncGroup?.let {
                  Text(stringResource(R.string.provider_detail_group, it), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                  onClick = {
                    onDedicatedSync(
                      row.providerId,
                      connection.accountId.takeIf { it.isNotBlank() },
                      connection.credentialId.takeIf { it.isNotBlank() },
                      syncGroup,
                    )
                  },
                  enabled = !saving && !syncingDedicated,
                  modifier = Modifier.heightIn(min = 48.dp).testTag(modelDedicatedSyncTag(row, connection))
                    .semantics {
                      contentDescription = operationLabel + " · " + credentialText
                      if (syncingDedicated) stateDescription = syncingLabel
                    },
                ) {
                  SyncOperationButtonContent(operationLabel, syncingDedicated, syncingLabel)
                }
                DedicatedSyncStatusLines(opState,
                  Modifier.testTag(modelDedicatedSyncTag(row, connection) + "_status"))
              }
            }
          },
        )
      }
    }
  }
  }
  if (showFilterSheet) {
    ModelFilterSheet(
      state = filterState,
      availability = filterAvailability,
      onChange = { filterState = it },
      onDismissRequest = { showFilterSheet = false },
      onReset = { filterState = FilterState() },
    )
  }
}

/**
 * P7-B2：LMSpeed 全域同步狀態行（ModelsTab／Settings 共用；追加）。
 * 錯誤只顯示分類訊息（AUTH／NETWORK／UNSUPPORTED／UNKNOWN→既有字串；禁 URL／body／secret）；
 * 觸發時間明示裝置本地時間（不冒充觀測時間）；進行中由按鈕 spinner 表達，本行不重複。
 */
@Composable
private fun LmspeedSyncStatusLines(
  syncing: Boolean = false,
  error: String? = null,
  lastSynced: Long? = null,
) {
  if (error != null) {
    val message = when (error) {
      "AUTH" -> stringResource(R.string.error_auth)
      "NETWORK" -> stringResource(R.string.error_network)
      "UNSUPPORTED" -> stringResource(R.string.error_sync_unavailable)
      else -> stringResource(R.string.error_sync_failed)
    }
    Text(
      text = message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
    )
  }
  if (lastSynced != null) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Text(
      text = stringResource(R.string.label_lmspeed_sync_triggered_at) +
        remember(lastSynced) { dateFormat.format(Date(lastSynced)) },
      style = MaterialTheme.typography.bodySmall,
    )
  }
  if (syncing) {
    Text(
      text = stringResource(R.string.status_syncing),
      style = MaterialTheme.typography.bodySmall,
    )
  }
}
