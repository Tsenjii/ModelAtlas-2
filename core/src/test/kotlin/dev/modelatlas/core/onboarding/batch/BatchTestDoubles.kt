package dev.modelatlas.core.onboarding.batch

import dev.modelatlas.core.onboarding.MemoryWorkflowStore
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.OnboardingStepId
import dev.modelatlas.core.onboarding.OnboardingStepStatus
import dev.modelatlas.core.onboarding.RequestGrant
import dev.modelatlas.core.onboarding.SafeCredentialRef
import dev.modelatlas.core.onboarding.StepExecutor
import dev.modelatlas.core.onboarding.StepResult
import dev.modelatlas.core.onboarding.approval
import dev.modelatlas.core.onboarding.happyPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield

/**
 * 測試用批次狀態儲存：**只在測試**存在，不是持久化。
 *
 * 刻意放在 test source set：core 的 main 不提供任何 in-memory 實作冒充持久化（契約 §3.2），
 * 產品路徑必須是 Room adapter。
 */
internal class MemoryBatchStateStore : BatchStateStore {
    private val snapshots = mutableMapOf<String, BatchSnapshot>()
    val saves = mutableListOf<BatchSnapshot>()

    override suspend fun load(batchId: String): BatchSnapshot? = snapshots[batchId]

    override suspend fun save(snapshot: BatchSnapshot) {
        saves += snapshot
        snapshots[snapshot.batchId] = snapshot
    }

    override suspend fun listBatchIds(): List<String> = snapshots.keys.toList()

    fun put(snapshot: BatchSnapshot) {
        snapshots[snapshot.batchId] = snapshot
    }

    fun current(batchId: String): BatchSnapshot? = snapshots[batchId]
}

/** 固定序號的 id 產生器，讓 batchId 可斷言。 */
internal class SequentialBatchIds(private val prefix: String = "gen") : BatchIdGenerator {
    private var counter = 0

    override fun newId(): String = "$prefix-${++counter}"
}

/**
 * 可控的批次用 step 執行器：記錄每筆 workflow 的每個步驟、追蹤最大同時執行數、可卡住單一步。
 * 只回固定 [StepResult] 或 handler 指定的結果，沒有任何真實網路行為。
 */
internal class BatchScriptedExecutor(
    private val scripted: Map<OnboardingStepId, StepResult> = happyPath(),
) : StepExecutor {
    val executions = mutableListOf<Pair<String, OnboardingStepId>>()
    var maxInFlight = 0
        private set

    private var inFlight = 0

    var handler: (suspend (String, OnboardingStepId, RequestGrant) -> StepResult?)? = null

    val blocked = CompletableDeferred<Unit>()
    var blockOn: Pair<String, OnboardingStepId>? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun execute(
        snapshot: OnboardingSnapshot,
        step: OnboardingStepId,
        grant: RequestGrant,
    ): StepResult {
        val key = snapshot.workflowId to step
        executions += key
        inFlight += 1
        if (inFlight > maxInFlight) maxInFlight = inFlight
        try {
            yield()
            if (key == blockOn) {
                blocked.complete(Unit)
                gate?.await() ?: awaitCancellation()
            }
            handler?.let { h -> h(snapshot.workflowId, step, grant)?.let { return it } }
            val scriptedResult = scripted[step]
            if (scriptedResult != null) {
                return scriptedResult.copy(
                    requestsSpent = scriptedResult.requestsSpent.coerceAtMost(grant.requestLimit),
                )
            }
            return StepResult(
                step = step,
                status = OnboardingStepStatus.SUCCESS,
                requestsSpent = if (grant.requestLimit > 0) 1 else 0,
            )
        } finally {
            inFlight -= 1
        }
    }

    fun callCountFor(workflowId: String): Int = executions.count { it.first == workflowId }

    fun callCount(workflowId: String, step: OnboardingStepId): Int =
        executions.count { it.first == workflowId && it.second == step }
}

/** 正常匯入 draft：非 secret 欄位 + safe credential ref，workflowId 由測試指定。 */
internal fun batchDraft(
    itemId: String,
    baseUrl: String = "https://$itemId.fixture.invalid/v1",
    workflowId: String = "wf-$itemId",
    suggestedModelIds: List<String> = listOf("model-a"),
    credentials: List<SafeCredentialRef> = listOf(SafeCredentialRef("safe-credential-fixture", "main")),
    extra: (OnboardingSnapshot) -> OnboardingSnapshot = { it },
): BatchItemDraft = BatchItemDraft(
    itemId = itemId,
    draft = extra(
        OnboardingSnapshot(
            workflowId = workflowId,
            flowType = OnboardingFlowType.QUICK,
            baseUrl = baseUrl,
            websiteUrl = "https://$itemId.fixture.invalid",
            suggestedModelIds = suggestedModelIds,
            safeCredentialIds = credentials.map { it.safeCredentialId },
            credentials = credentials,
        ),
    ),
)

internal fun batchOf(vararg items: BatchItemDraft) = BatchDraft(items.toList())

internal fun preApprovedDraft(itemId: String) = batchDraft(itemId) { it.copy(reviewApproval = approval(true)) }

/** 模擬進程重啟：workflow 快照在 app 端是 Room，這裡只是把最後狀態搬到新 store。 */
internal fun copyOf(source: MemoryWorkflowStore): MemoryWorkflowStore {
    val target = MemoryWorkflowStore()
    source.saves.map { it.workflowId }.distinct().forEach { id ->
        source.current(id)?.let { target.put(it) }
    }
    return target
}
