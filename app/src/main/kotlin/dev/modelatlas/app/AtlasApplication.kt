package dev.modelatlas.app

import android.app.Application
import android.util.Log
import androidx.work.WorkManager
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.app.data.EventPipeline
import dev.modelatlas.app.data.EventStore
import dev.modelatlas.app.data.QuotaLowDeliverySink
import dev.modelatlas.app.data.StoreEventSink
import dev.modelatlas.app.notifications.NotificationManagerDeliverySink
import dev.modelatlas.app.notifications.NotificationRule
import dev.modelatlas.app.notifications.NotificationSettingsStore
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.app.security.CredentialLifecycle
import dev.modelatlas.app.work.SyncScheduler
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.network.OpenAiAudioTranscriptionTransport
import dev.modelatlas.core.network.OpenAiImageEditTransport
import dev.modelatlas.core.network.OpenAiImageGenerationTransport
import dev.modelatlas.core.network.OpenAiTransport
import dev.modelatlas.core.network.TransportDispatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * F29 啟動恢復結果。
 *
 * TRACEABILITY：FND-006（unified telemetry recorder）／PLAY-002
 *（streaming text inference + cancel + error classification）精神——
 * RUNNING telemetry 不得丟失、不得靜默改寫為成功，失敗必須可觀察、可重試。
 */
data class StartupRecoveryResult(val success: Boolean, val attempts: Int)

/**
 * F29 有限重試執行器（純邏輯，不碰 DB、不刪 telemetry）。
 *
 * - 有限：最多 [maxAttempts] 次，超過即回 `success=false`，絕不無限重試。
 * - 不靜默 skipping：失敗只回結果並記 `Log.w`（類別名，不記 secrets），由呼叫端
 *   明確標 degraded；絕不把失敗當成功、絕不刪除 RUNNING。
 * - `CancellationException` 直接重拋、不計為可重試失敗。
 */
object StartupRecovery {
    suspend fun run(
        recover: suspend () -> Unit,
        maxAttempts: Int,
        delayMsForAttempt: (attempt: Int) -> Long = { 0L },
        onAttempt: (attempt: Int) -> Unit = {},
    ): StartupRecoveryResult {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        var attempt = 0
        while (true) {
            attempt++
            onAttempt(attempt)
            try {
                recover()
                return StartupRecoveryResult(success = true, attempts = attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AtlasStartup", "recoverInterrupted attempt $attempt/$maxAttempts failed: ${e::class.java.simpleName}")
                if (attempt >= maxAttempts) return StartupRecoveryResult(success = false, attempts = attempt)
                val waitMs = delayMsForAttempt(attempt).coerceAtLeast(0L)
                if (waitMs > 0L) delay(waitMs)
            }
        }
    }
}

/**
 * 依賴組裝擁有者：vault / repository / engine。
 *
 * F29（BACKLOG F29）：啟動時 IO 執行 `recoverInterrupted`，有限重試＋降級啟動。
 * - 成功：`ready=true`，無 degraded、無 error。
 * - 有限重試耗盡仍失敗：RUNNING 原樣保留（不刪、不改 SUCCESS、不補 completedAt/latency，
 *   以 FND-006／PLAY-002 精神保存 telemetry），`ready` 仍為 true（降級啟動，不再永遠 false），
 *   `recoveryDegraded=true`＋`initializationError=true` 讓失敗可觀察（備份標記＝degraded 旗標＋
 *   未動的 RUNNING 列），待 [retryRecovery] 自癒。不靜默 skipping：失敗一定標記＋Log，
 *   絕不假裝成功。
 */
class AtlasApplication : Application() {
    companion object {
        /** F29 有限重試上限：初次＋2 次重試，共 3 次。 */
        const val MAX_RECOVERY_ATTEMPTS = 3

        /** F29 有限退避：總等待 < 1s，不耗電、不卡主線（IO scope 執行）。 */
        fun recoveryDelayForAttempt(attempt: Int): Long = when (attempt) {
            1 -> 150L
            2 -> 300L
            else -> 0L
        }
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var vault: AndroidCredentialVault
        private set
    lateinit var database: AtlasDatabase
        private set
    lateinit var repository: AtlasRepository
        private set
    lateinit var engine: InferenceEngine
        private set
    lateinit var notificationSettings: NotificationSettingsStore
        private set

    lateinit var credentialLifecycle: CredentialLifecycle
        private set

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _initializationError = MutableStateFlow(false)
    val initializationError: StateFlow<Boolean> = _initializationError.asStateFlow()

    /** F29：累計已執行的恢復嘗試次數（單調遞增，可觀察有限重試是否失控）。 */
    private val _recoveryAttempts = MutableStateFlow(0)
    val recoveryAttempts: StateFlow<Int> = _recoveryAttempts.asStateFlow()

    /**
     * F29 備份標記：true 表示降級啟動——RUNNING telemetry 原樣保留待重試，
     * 本次啟動未完成 recover。false 表示已恢復或無需恢復。
     */
    private val _recoveryDegraded = MutableStateFlow(false)
    val recoveryDegraded: StateFlow<Boolean> = _recoveryDegraded.asStateFlow()

    private val recoveryLock = Any()
    private var startupJob: Job? = null
    private var retryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationSettings = NotificationSettingsStore(this)
        vault = AndroidCredentialVault(this)
        database = AtlasDatabase.create(this)
        // X-014: OUTAGE_RECOVERY／PRICE_CHANGE／QUOTA_LOW 各用獨立事件管線（共用 append-only 事件庫），
        // 並各自在使用者開啟對應通知規則後才投遞。
        repository = AtlasRepository(
            database,
            pipeline = EventPipeline(
                sink = StoreEventSink(EventStore(database)),
                delivery = NotificationManagerDeliverySink(this, notificationSettings),
            ),
            pricePipeline = EventPipeline(
                sink = StoreEventSink(EventStore(database)),
                delivery = NotificationManagerDeliverySink(
                    this,
                    notificationSettings,
                    NotificationRule.PRICE_CHANGE,
                ),
            ),
            quotaPipeline = EventPipeline(
                sink = StoreEventSink(EventStore(database)),
                delivery = QuotaLowDeliverySink(
                    NotificationManagerDeliverySink(this, notificationSettings, NotificationRule.QUOTA_LOW),
                    EventStore(database),
                ),
            ),
        )
        credentialLifecycle = CredentialLifecycle(repository, vault)
        // PLAY-004／PROV-010 真接線：唯一 inference 入口經 TransportDispatch，
        // 已註冊 transport 真執行，未註冊型別獨立 Unsupported（禁 giant switch）。
        engine = InferenceEngine(
            TransportDispatch(
                listOf(
                    OpenAiTransport(vault),
                    OpenAiImageGenerationTransport(vault),
                    OpenAiImageEditTransport(vault),
                    OpenAiAudioTranscriptionTransport(vault),
                ),
            ),
            repository,
        )
        // X-011a interim: periodic pipeline not wired — do not enqueue; cancel any
        // live loop from earlier installs. A cancelled worker's retry() does not reschedule.
        // Flip isPeriodicSchedulingEnabled() only when the wired runner lands (X-011 S0/S1).
        if (SyncScheduler.isPeriodicSchedulingEnabled()) {
            SyncScheduler.schedulePeriodic(WorkManager.getInstance(this))
        } else {
            SyncScheduler.cancelPeriodic(WorkManager.getInstance(this))
        }
        synchronized(recoveryLock) {
            startupJob = applicationScope.launch {
                runStartupRecovery(recover = { repository.recoverInterrupted() })
            }
        }
    }

    /**
     * F29 核心狀態機（可測試）：執行有限重試並更新可觀察狀態。
     * 成功則清除 degraded/error 並 `ready=true`；失敗則降級啟動
     *（`ready=true`＋degraded＋error，RUNNING 不動，待自癒）。
     */
    suspend fun runStartupRecovery(
        recover: suspend () -> Unit,
        maxAttempts: Int = MAX_RECOVERY_ATTEMPTS,
        delayMsForAttempt: (Int) -> Long = { attempt -> Companion.recoveryDelayForAttempt(attempt) },
    ) {
        val result = StartupRecovery.run(
            recover = recover,
            maxAttempts = maxAttempts,
            delayMsForAttempt = delayMsForAttempt,
            onAttempt = { _recoveryAttempts.update { it + 1 } },
        )
        if (result.success) {
            _recoveryDegraded.value = false
            _initializationError.value = false
            _ready.value = true
        } else {
            _recoveryDegraded.value = true
            _initializationError.value = true
            _ready.value = true
        }
    }

    /**
     * F29 自癒入口：降級後可再次重試 recover（single-flight，同時間只跑一個；
     * 若啟動恢復仍在跑，直接回傳它，不併發打 DB）。成功即清除 degraded/error。
     */
    fun retryRecovery(): Job {
        synchronized(recoveryLock) {
            startupJob?.takeIf { it.isActive }?.let { return it }
            retryJob?.takeIf { it.isActive }?.let { return it }
            val job = applicationScope.launch {
                runStartupRecovery(recover = { repository.recoverInterrupted() })
            }
            retryJob = job
            return job
        }
    }

    /** F29 可測試的 single-flight 包裝：device test 用 fake recover 驗證，不碰真 DB。 */
    fun retryRecoveryWith(recover: suspend () -> Unit): Job {
        synchronized(recoveryLock) {
            retryJob?.takeIf { it.isActive }?.let { return it }
            val job = applicationScope.launch {
                runStartupRecovery(recover = recover, delayMsForAttempt = { 0L })
            }
            retryJob = job
            return job
        }
    }
}
