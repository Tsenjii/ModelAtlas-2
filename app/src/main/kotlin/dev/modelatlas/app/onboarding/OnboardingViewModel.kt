package dev.modelatlas.app.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.OnboardingStatus
import dev.modelatlas.core.onboarding.OnboardingStepId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Onboarding UI bridge（無 UI 實作，純狀態橋接）。
 *
 * 固定 UI API（主 session 實作畫面時依此介面）：
 * - `state: StateFlow<OnboardingUiState>` 含 `snapshot`（當前單）、`resumable`（未完成單供 resume）、
 *   `busy`、`error`（布林，不攜帶訊息，避免洩漏 URL / key / server body）、`committedProviderId`。
 * - `startQuick(baseUrl, websiteUrl, secret, modelId)`、`resume(id)`、`retry(step)`、`skip(step)`、
 *   `cancel()`、`commit()`、`abandon()`、`dismissError()`、`close()`。
 *
 * 執行語意：
 * - 持有 `job` 在 IO 跑；`CancellationException` 重新拋出，任何其他錯誤只設 `error=true`，不洩漏 `message`。
 * - 取消後 snapshot 可恢復：`cancel()` 只取消 job，不清 `currentId`，Room observe 會送出 CANCELLED 狀態。
 * - `close()` 不放棄單、只離開：取消 job、清當前選擇，不呼叫 `abandon`，Room 仍保留 workflow 供 resume。
 * - Room observe 更新進度：`service.snapshots` 收集，未完成單（非 COMMITTED/ABANDONED）供 resume。
 *
 * Secret 邊界：`secret` 只在呼叫期間持有，轉交 service 後即丟，不進 state、不 log、不進 Bundle。
 */
data class OnboardingUiState(
    val snapshot: OnboardingSnapshot? = null,
    val resumable: List<OnboardingSnapshot> = emptyList(),
    val busy: Boolean = false,
    val error: Boolean = false,
    val committedProviderId: String? = null,
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private var service: QuickAddService = createService(application)
    private val advancedService by lazy {
        val atlas = getApplication<AtlasApplication>()
        AdvancedAddService(atlas.vault, atlas.engine, atlas.database)
    }

    /**
     * 測試用：注入 loopback service（真 DB + MockWebServer）。
     * 產品 UI 一律用主建構子 `OnboardingViewModel(application)`。
     */
    constructor(application: Application, service: QuickAddService) : this(application) {
        observeJob?.cancel()
        this.service = service
        observeJob = startObserving(service)
    }

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    @Volatile
    private var currentId: String? = null

    private var job: Job? = null
    private var observeJob: Job? = null

    init {
        observeJob = startObserving(service)
    }

    private fun startObserving(svc: QuickAddService): Job = viewModelScope.launch {
        try {
            svc.snapshots
                .catch {
                    // 解碼錯誤固定安全訊息已在 service 轉換；此處只設布林，不洩漏內容。
                    if (it is CancellationException) throw it
                    _state.update { s -> s.copy(error = true) }
                }
                .collect { list ->
                    val resumable = list.filter {
                        it.status != OnboardingStatus.COMMITTED && it.status != OnboardingStatus.ABANDONED
                    }
                    val id = currentId
                    val current = id?.let { wanted -> list.firstOrNull { it.workflowId == wanted } }
                    _state.update { s -> s.copy(snapshot = current, resumable = resumable) }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { s -> s.copy(error = true) }
        }
    }

    /** 開始 Quick Add；`websiteUrl`/`secret`/`modelId` 空即無。 */
    fun startQuick(baseUrl: String, websiteUrl: String, secret: String, modelId: String) {
        if (_state.value.busy) return
        val startingId = UUID.randomUUID().toString()
        currentId = startingId
        _state.update { it.copy(snapshot = null, busy = true, error = false, committedProviderId = null) }
        val j = viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = service.startQuickAdd(
                    baseUrl = baseUrl,
                    websiteUrl = websiteUrl.takeIf { it.isNotBlank() },
                    secretTransient = secret.takeIf { it.isNotBlank() },
                    suggestedModelId = modelId.takeIf { it.isNotBlank() },
                    workflowId = startingId,
                )
                currentId = id
                try {
                    val loaded = service.loadSnapshot(id)
                    if (loaded != null && currentId == id) {
                        _state.update { s -> s.copy(snapshot = loaded) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 觀察流會補上；此處不設 error，避免掩蓋 start 成功。
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        job = j
    }

    fun startAdvanced(metadata: OnboardingSnapshot, credentials: List<AdvancedCredentialInput>, fields: List<AdvancedAuthInput>) {
        if (_state.value.busy) return
        val id = UUID.randomUUID().toString()
        currentId = id
        perform {
            advancedService.start(metadata, credentials, fields, workflowId = id)
            val loaded = service.loadSnapshot(id)
            if (currentId == id) _state.update { it.copy(snapshot = loaded) }
        }
    }

    fun editQuick(baseUrl: String, websiteUrl: String, secret: String, modelId: String) {
        val id = currentId ?: return
        perform { service.editQuick(id, baseUrl, websiteUrl, secret.takeIf { it.isNotBlank() }, modelId) }
    }

    fun editAdvanced(metadata: OnboardingSnapshot, credentials: List<AdvancedCredentialInput>, fields: List<AdvancedAuthInput>) {
        val id = currentId ?: return
        perform { advancedService.edit(id, metadata, credentials, fields) }
    }

    fun rename(name: String) {
        val id = currentId ?: return
        perform { service.editQuick(id, providerName = name) }
    }

    fun updateReview(name: String, selectedModels: List<String>, manualModelId: String) {
        val id = currentId ?: return
        perform { service.editReview(id, name, selectedModels, manualModelId) }
    }

    private fun perform(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = false, committedProviderId = null) }
        job = viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = true) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    /** 從 Room 恢復未完成單；不存在即 `error=true`。 */
    fun resume(id: String) {
        if (_state.value.busy) return
        if (id.isBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        currentId = id
        _state.update { it.copy(busy = true, error = false, committedProviderId = null) }
        val j = viewModelScope.launch(Dispatchers.IO) {
            try {
                service.resume(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (currentId == id) currentId = null
                _state.update { it.copy(error = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        job = j
    }

    /** 重跑單步；無當前單即 `error=true`。 */
    fun retry(step: OnboardingStepId) {
        val id = currentId
        if (id.isNullOrBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = false) }
        val j = viewModelScope.launch(Dispatchers.IO) {
            try {
                service.retryStep(id, step)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        job = j
    }

    /** 跳過單步；無當前單即 `error=true`。 */
    fun skip(step: OnboardingStepId) {
        val id = currentId
        if (id.isNullOrBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = false) }
        val j = viewModelScope.launch(Dispatchers.IO) {
            try {
                service.skipStep(id, step)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        job = j
    }

    /** 取消當前 job；保留 `currentId`，snapshot 經 Room observe 可恢復。 */
    fun cancel() {
        job?.cancel()
    }

    /** 人工確認後 commit；成功設 `committedProviderId`，失敗只設 `error=true`。 */
    fun commit() {
        val id = currentId
        if (id.isNullOrBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = false) }
        val j = viewModelScope.launch(Dispatchers.IO) {
            try {
                val receipt = service.approveAndCommit(id)
                _state.update { s -> s.copy(committedProviderId = receipt.providerId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        job = j
    }

    /** 放棄當前單（destructive，需 UI 先確認）；成功後清當前選擇。 */
    fun abandon() {
        val id = currentId
        if (id.isNullOrBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = false) }
        val j = viewModelScope.launch(Dispatchers.IO) {
            try {
                service.abandon(id)
                if (currentId == id) currentId = null
                _state.update { s -> s.copy(snapshot = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
        job = j
    }

    fun dismissError() {
        _state.update { it.copy(error = false) }
    }

    /**
     * 只離開、不放棄單：取消 job、清當前選擇，不呼叫 `abandon`。
     * Room 仍保留 workflow，`resumable` 可再次 `resume`。
     */
    fun close() {
        job?.cancel()
        currentId = null
        _state.update { it.copy(snapshot = null, committedProviderId = null) }
    }

    private companion object {
        fun createService(app: Application): QuickAddService {
            val atlas = app as AtlasApplication
            return QuickAddService(
                vault = atlas.vault,
                engine = atlas.engine,
                database = atlas.database,
            )
        }
    }
}
