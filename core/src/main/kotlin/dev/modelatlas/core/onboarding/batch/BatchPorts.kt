package dev.modelatlas.core.onboarding.batch

import dev.modelatlas.core.onboarding.CommitReceipt
import dev.modelatlas.core.onboarding.Committer
import dev.modelatlas.core.onboarding.OnboardingClock
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.OnboardingWorkflow
import dev.modelatlas.core.onboarding.ProbeBudget
import dev.modelatlas.core.onboarding.StepExecutor
import dev.modelatlas.core.onboarding.SystemOnboardingClock
import dev.modelatlas.core.onboarding.WorkflowStore
import java.util.UUID

/**
 * 批次狀態持久化 port。可注入、附序列化 model（[BatchSnapshot] + [BatchJson]）。
 *
 * 語意：
 * - `load` 回 null 代表沒有可恢復的批次。
 * - `save` 整份覆寫同 `batchId`，只寫非 secret 欄位。
 * - `listBatchIds` 讓 app 重啟後列出可續跑的批次（UI「Resume batch?」）。
 *
 * 實作責任在 app 層（Room，`exportSchema=true`、無 destructive migration）。
 * core **不**提供任何 in-memory 實作：記憶體版不算持久化，冒稱會讓「已保存」語意失真。
 * app 端另須沿用既有 secret-guard，編碼後再檢查一次。
 */
interface BatchStateStore {
    suspend fun load(batchId: String): BatchSnapshot?

    suspend fun save(snapshot: BatchSnapshot)

    suspend fun listBatchIds(): List<String>
}

/**
 * 逐筆 workflow 組裝 port。core 只認 id，不自己發 request、不自己建 Room 依賴。
 *
 * 契約：
 * - 必須以 `workflowId` 建立可恢復的 [OnboardingWorkflow]（同一 `WorkflowStore`）。
 * - 必須保證 import 路徑下 commit 不可被自動觸發；用 [defaultBatchWorkflowFactory] 即內建拒絕。
 */
fun interface BatchWorkflowFactory {
    fun create(workflowId: String): OnboardingWorkflow
}

/** 批次執行器的 id 產生器（測試可注入固定序號以斷言復原行為）。 */
fun interface BatchIdGenerator {
    fun newId(): String
}

object UuidBatchIdGenerator : BatchIdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/**
 * 拒絕 commit 的 [Committer]：批次匯入永不自動 commit。
 *
 * 這是防禦性第二道牆 —— 即使上游 approval 意外存在、workflow 被驅動到 `commit`，
 * 產品資料也不會被寫入，而是回 [BatchCommitRefusedException] 讓該筆落到
 * [BatchFailure.UNEXPECTED_COMMIT]，由人發現。
 */
class BatchNoCommitCommitter : Committer {
    override suspend fun commit(snapshot: OnboardingSnapshot): CommitReceipt =
        throw BatchCommitRefusedException()
}

/**
 * 預設 factory：以注入的 [WorkflowStore] / [StepExecutor] 組 workflow，committer 固定為
 * [BatchNoCommitCommitter]。app 傳入 Room store 與真實 [StepExecutor] 即可。
 */
fun defaultBatchWorkflowFactory(
    store: WorkflowStore,
    executor: StepExecutor,
    clock: OnboardingClock = SystemOnboardingClock,
    budget: ProbeBudget = ProbeBudget.DEFAULT,
): BatchWorkflowFactory = BatchWorkflowFactory { workflowId ->
    OnboardingWorkflow(store, executor, BatchNoCommitCommitter(), clock, budget)
}

/** 批次層固定訊息例外；`failure` 是可辨識分類，訊息不帶 raw 輸入。 */
class BatchException(val failure: BatchFailure) : IllegalStateException(BatchMessages.of(failure))

/** 有人嘗試在 import 路徑 commit；訊息固定，不含 workflowId / URL。 */
class BatchCommitRefusedException :
    IllegalStateException(BatchMessages.of(BatchFailure.UNEXPECTED_COMMIT))
