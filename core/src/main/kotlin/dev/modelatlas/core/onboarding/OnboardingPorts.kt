package dev.modelatlas.core.onboarding

import kotlinx.serialization.Serializable

/**
 * 快照持久化。Room adapter 由主 session 實作（每步完成即 save，可中斷恢復）。
 * load 回 null 代表沒有可恢復的 workflow；save 必須整份覆寫且不得寫入 secret。
 */
interface WorkflowStore {
    suspend fun load(workflowId: String): OnboardingSnapshot?

    suspend fun save(snapshot: OnboardingSnapshot)
}

/**
 * 單步執行器。真實網路 / catalog / 探測由 adapter 注入，core workflow 不自己發 request。
 * 回傳只允許 safe refs、分類、計數與安全訊息；不得回傳 raw body、raw header、secret。
 */
interface StepExecutor {
    suspend fun execute(
        snapshot: OnboardingSnapshot,
        step: OnboardingStepId,
        grant: RequestGrant,
    ): StepResult
}

/**
 * Commit 契約：冪等。同一 workflowId 重放不得產生第二個 provider；
 * 以 workflowId 作去重鍵 + transaction（ABORT，不靜默部分成功）。
 */
fun interface Committer {
    suspend fun commit(snapshot: OnboardingSnapshot): CommitReceipt
}

interface OnboardingClock {
    fun nowMillis(): Long
}

object SystemOnboardingClock : OnboardingClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * 執行前已保留的額度憑證。只存在記憶體，不序列化、不持久化。
 *
 * 執行器每發一個 request 前先 [tryReserve]；workflow 以 [requestLimit] 為硬上限計費，
 * 執行器回報用量超過 grant 會被夾限，預算不會被超支。
 */
class RequestGrant(
    val step: OnboardingStepId,
    val attempt: Int,
    val requestLimit: Int,
    val maxTestModels: Int,
    val rateLimitStopped: Boolean,
) {
    private var reserved = 0

    val reservedRequests: Int get() = reserved

    val availableRequests: Int get() = (requestLimit - reserved).coerceAtLeast(0)

    fun tryReserve(count: Int = 1): Boolean {
        if (count <= 0) return true
        if (reserved + count > requestLimit) return false
        reserved += count
        return true
    }
}

@Serializable
data class OnboardingStepProgress(
    val step: OnboardingStepId,
    val status: OnboardingStepStatus,
    val attempt: Int,
    val requestsSpent: Int,
    val reason: OnboardingFailureReason? = null,
)

@Serializable
data class OnboardingRunSummary(
    val workflowId: String,
    val status: OnboardingStatus,
    val stop: OnboardingStop,
    val progress: List<OnboardingStepProgress>,
    val totalRequestsSpent: Int,
    val requestsRemaining: Int,
    val rateLimited: Boolean,
    val commitReceipt: CommitReceipt? = null,
)

class OnboardingNotFoundException(workflowId: String) :
    IllegalStateException("onboarding workflow 不存在: $workflowId")

class OnboardingStateException(message: String) : IllegalStateException(message)

class CommitRejectedException(
    val reasons: List<OnboardingFailureReason>,
) : IllegalStateException("commit 被拒: ${reasons.map { it.name }}")
