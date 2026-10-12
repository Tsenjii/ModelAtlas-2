package dev.modelatlas.app.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.core.onboarding.batch.BatchSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 批次匯入 UI bridge（無 UI 實作，純狀態橋接），風格沿用 [OnboardingViewModel]。
 *
 * 固定 UI API：
 * - `state: StateFlow<BatchImportUiState>` 含 `batches`（Room 觀察到的全部批次，含逐筆狀態）、
 *   `busy`、`error`（布林，不攜帶訊息，避免洩漏 URL / key / server body）。
 * - `start(rawInput)`、`resume(batchId)`、`retryItem(batchId, itemId)`、
 *   `cancel()`、`dismissError()`、`close()`。
 *
 * 執行語意：
 * - 持有 `job` 在 IO 跑；`CancellationException` 重新拋出，任何其他錯誤只設 `error=true`。
 * - `cancel()` 走 [BatchImportService.requestCancel] 的協作式停排：目前這筆跑完後批次轉 `PAUSED`，
 *   不硬中斷已在飛的 request（不留下半跑的探測狀態）；恢復由 `resume` 從停手處續跑。
 * - `close()` 才取消 job、清 busy：只是離開畫面，批次狀態仍在 Room，可再次 `resume`。
 * - 逐筆的 `workflowId` 由 `batches` 的 item 帶出，UI 交給 [OnboardingViewModel.resume] 補 key / commit。
 *
 * 邊界：匯入輸入只有非機密 JSON，`rawInput` 不進 state、不 log、不進 Bundle（只在使用者操作當下存在）。
 */
data class BatchImportUiState(
    val batches: List<BatchSnapshot> = emptyList(),
    val busy: Boolean = false,
    val error: Boolean = false,
)

class BatchImportViewModel(application: Application) : AndroidViewModel(application) {
    private var service: BatchImportService = createService(application)

    /**
     * 測試用：注入 loopback service（真 DB + MockWebServer）。
     * 產品 UI 一律用主建構子 `BatchImportViewModel(application)`。
     */
    constructor(application: Application, service: BatchImportService) : this(application) {
        observeJob?.cancel()
        this.service = service
        observeJob = startObserving(service)
    }

    private val _state = MutableStateFlow(BatchImportUiState())
    val state: StateFlow<BatchImportUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var observeJob: Job? = null

    init {
        observeJob = startObserving(service)
    }

    private fun startObserving(svc: BatchImportService): Job = viewModelScope.launch {
        try {
            svc.snapshots
                .catch {
                    // 解碼錯誤固定安全訊息已在 store 轉換；此處只設布林，不洩漏內容。
                    if (it is CancellationException) throw it
                    _state.update { s -> s.copy(error = true) }
                }
                .collect { list -> _state.update { s -> s.copy(batches = list) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { s -> s.copy(error = true) }
        }
    }

    /** 嚴格解析後逐筆探測；解析或單筆失敗只設 `error=true`，不顯示原始輸入。 */
    fun start(rawInput: String) {
        if (rawInput.isBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        perform { service.start(rawInput) }
    }

    /** 從 Room 續跑未完成批次；批次不存在即 `error=true`。 */
    fun resume(batchId: String) {
        if (batchId.isBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        perform { service.resume(batchId) }
    }

    /** 重驅動單筆（已跑到 Review 的筆由匯入器忽略）。 */
    fun retryItem(batchId: String, itemId: String) {
        if (batchId.isBlank() || itemId.isBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        perform { service.retryItem(batchId, itemId) }
    }

    /**
     * 請求停排（協作式）：目前這筆跑完後不再排新筆，批次轉 `PAUSED` 並可 `resume`。
     * 不取消 job，避免在飛的探測被硬中斷。
     */
    fun cancel() {
        service.requestCancel()
    }

    fun dismissError() {
        _state.update { it.copy(error = false) }
    }

    /**
     * 只離開畫面：取消 job、清 busy。批次狀態仍在 Room，可再次 `resume`。
     * 中斷中的單筆會由匯入器落成 `CANCELLED`（可恢復），不當成已完成。
     */
    fun close() {
        job?.cancel()
        job = null
        _state.update { it.copy(busy = false) }
    }

    private fun perform(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = false) }
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = true) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        fun createService(app: Application): BatchImportService {
            val atlas = app as AtlasApplication
            return BatchImportService(
                vault = atlas.vault,
                engine = atlas.engine,
                database = atlas.database,
            )
        }
    }
}
