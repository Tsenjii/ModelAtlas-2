package dev.modelatlas.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.modelatlas.app.background.SyncRunner
import dev.modelatlas.app.background.SyncWorkerLogic
import dev.modelatlas.app.background.SyncWorkerResult

/**
 * X-011 背景同步 worker（全 repo 唯一 `SyncWorker`）。
 *
 * 失敗語意 fail-closed（BACKLOG X-011 驗收 2–4，X-006）：
 * - 委派 [SyncWorkerLogic]：成功才回 `Result.success()`，失敗回 `Result.retry()`。
 * - sync pipeline 尚未接線前，runner 直接拋錯 → 永遠 `Result.retry()`，
 *   **絕不回報假成功**（E1 修復：未做工不得稱成功）。
 * - 任何路徑皆不讀寫 `snapshot_facts`／`offerings`／`sample_facts`，
 *   不呼叫 provider：一次 failure 不清空 last-good、不刪資料。
 * X-011a interim: periodic scheduling disabled at the enqueue site; this stub is
 * retained intact so the wired runner slice can re-enable without re-proving fail-closed.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        when (SyncWorkerLogic(SyncRunner { throw IllegalStateException("sync pipeline not wired") }).doWork()) {
            SyncWorkerResult.SUCCESS -> Result.success()
            SyncWorkerResult.RETRY -> Result.retry()
        }
}
