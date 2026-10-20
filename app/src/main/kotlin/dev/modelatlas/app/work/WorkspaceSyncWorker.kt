package dev.modelatlas.app.work

import android.content.Context
import androidx.work.*
import dev.modelatlas.app.AtlasApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Separate from the older provider polling worker: this runner calls the real
 * workspace protocol and never probes providers or spends inference tokens. */
class WorkspaceSyncWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as AtlasApplication
        app.ready.first { it }
        app.workspaceSync.initialize()
        if(!app.workspaceSync.status.value.connected) return Result.success()
        return if(app.workspaceSync.syncNow()) Result.success()
            else if(runAttemptCount<5) Result.retry() else Result.failure()
    }
    companion object {
        private const val NAME="modelatlas-workspace-sync"
        fun schedule(context: Context) {
            val request=PeriodicWorkRequestBuilder<WorkspaceSyncWorker>(15,TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
