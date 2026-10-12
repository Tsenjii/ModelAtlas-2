package dev.modelatlas.app.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * X-011（PRODUCT_SPEC §16 Background）WorkManager 排程唯一入口（single source of truth）。
 *
 * D2/D3 修復：`background/SyncScheduler.kt`（15 分鐘版）與
 * `background/WorkManagerSync.kt`（重複 `SyncWorker`＋空 runner）已刪除；
 * 全 repo 只留本物件＋[SyncWorker]。unique periodic 名稱統一為
 * [UNIQUE_PERIODIC_WORK_NAME]，週期統一 [PERIODIC_INTERVAL_MINUTES]（30 分鐘，
 * 遠高於 WorkManager 15 分鐘下限，不再有 15 vs 30 互相覆寫）。
 *
 * 排程由 [dev.modelatlas.app.AtlasApplication.onCreate] 顯式啟動一次；
 * 重複呼叫以 `KEEP` 收斂為同一週期，不重置。
 *
 * 設計護欄（§16「不要承諾 24/7 每分鐘可靠」）：
 * - 週期以 [PERIODIC_INTERVAL_MINUTES] 為間隔，遠高於 WorkManager 15 分下限，務實不激進。
 * - 約束為 `CONNECTED` 網路 ＋ `batteryNotLow`；**刻意不要求 UNMETERED 或 charging**，
 *   否則在熱點／未充電時永不執行，違背 §16 的務實語意。
 * - unique work 一律用 `KEEP`：連續排程不重置既有週期，避免每次前景化都重排。
 */
object SyncScheduler {

    /** unique periodic work 名稱（穩定，供測試與除錯辨識）。 */
    const val UNIQUE_PERIODIC_WORK_NAME = "modelatlas.sync.periodic"

    /** unique one-time work 名稱（穩定，供「立即同步一次」入口使用）。 */
    const val UNIQUE_ONE_TIME_WORK_NAME = "modelatlas.sync.oneoff"

    /** 所有 sync request 共用的標籤，方便 WorkManager 查詢。 */
    const val TAG_SYNC = "modelatlas.sync"

    /** 週期間隔（分鐘）。WorkManager 週期下限為 15 分鐘（見 [PeriodicWorkRequest]）。 */
    const val PERIODIC_INTERVAL_MINUTES = 30L

    /**
     * X-011a interim: periodic pipeline not wired — scheduling disabled.
     * Fresh installs never enqueue; upgrades cancel the live loop at startup.
     * Flip to true only as part of the wired-runner slice (X-011 S0/S1).
     */
    fun isPeriodicSchedulingEnabled(): Boolean = false

    /** Cancel the unique periodic loop (stops wakeups on upgraded installs). */
    fun cancelPeriodic(workManager: WorkManager) {
        workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
    }

    /** 連續排程時保留既有週期，不重置（X-011 acceptance）。 */
    fun periodicPolicy(): ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP

    /** one-time 入口的重複政策；同名 work 已存在時保留不覆蓋。 */
    fun oneTimePolicy(): ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    /**
     * sync 共用約束：需要可連線網路且電量非低。
     * 未知／無網路時 WorkManager 會延後，這是刻意的（sync failure 保留 last-good）。
     */
    fun constraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

    /** 建構週期性同步 request（不排程）。 */
    fun buildPeriodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .addTag(TAG_SYNC)
            .build()

    /** 建構一次性同步 request（不排程）。 */
    fun buildOneTimeRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints())
            .addTag(TAG_SYNC)
            .build()

    /**
     * 可呼叫排程入口：以 unique periodic work ＋ `KEEP` 排程背景同步。
     * 由呼叫端（未來的 Application／設定頁）決定何時呼叫；本函式不自我觸發。
     * X-011a interim: periodic scheduling disabled at the enqueue site via [isPeriodicSchedulingEnabled].
     */
    fun schedulePeriodic(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            periodicPolicy(),
            buildPeriodicRequest(),
        )
    }

    /**
     * 可呼叫排程入口：排程一次性的「立即同步」；同名 work 以 `KEEP` 去重。
     */
    fun scheduleOneTime(workManager: WorkManager) {
        workManager.enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK_NAME,
            oneTimePolicy(),
            buildOneTimeRequest(),
        )
    }
}
