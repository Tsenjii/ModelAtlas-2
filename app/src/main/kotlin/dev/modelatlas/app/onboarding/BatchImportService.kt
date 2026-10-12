package dev.modelatlas.app.onboarding

import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.RoomWorkflowStore
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.network.CatalogProbe
import dev.modelatlas.core.onboarding.ProbeStepExecutor
import dev.modelatlas.core.onboarding.batch.BatchIdGenerator
import dev.modelatlas.core.onboarding.batch.BatchImportParser
import dev.modelatlas.core.onboarding.batch.BatchLimits
import dev.modelatlas.core.onboarding.batch.BatchOnboardingImporter
import dev.modelatlas.core.onboarding.batch.BatchSnapshot
import dev.modelatlas.core.onboarding.batch.StrictBatchJsonParser
import dev.modelatlas.core.onboarding.batch.UuidBatchIdGenerator
import dev.modelatlas.core.onboarding.batch.defaultBatchWorkflowFactory
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * 批次匯入應用層組裝（無 UI；`docs/PROVIDER_ONBOARDING_CONTRACT.md` §0.2 / §3）。
 *
 * 流程：
 * - `start(rawInput)` 先以 [StrictBatchJsonParser] 嚴格解析（只收 `baseUrl` / `websiteUrl` /
 *   `suggestedModelId` / `providerName`，未知欄位與疑似 secret 一律拒絕），
 *   再交 [BatchOnboardingImporter.start] 逐筆排程，每筆跑到 `Review` 停下等人類確認。
 * - 逐筆共用 [dev.modelatlas.core.onboarding.OnboardingWorkflow]：
 *   真 [RoomWorkflowStore] 持久化（與 Quick Add 同一張表、同一 JSON 格式）、
 *   真 [ProbeStepExecutor]（[CatalogProbe] + 完整 [InferenceEngine]，探測用 testModel 照樣寫 telemetry）、
 *   固定 [dev.modelatlas.core.onboarding.batch.BatchNoCommitCommitter]（匯入路徑絕不寫產品資料）。
 * - 批次狀態以 [RoomBatchStateStore] 保存；`start` 內部先落盤 seeded snapshot 再排第一筆，
 *   因此「開始後、探測前」即有可觀察狀態（取消不丟已保存的批次）。
 * - `snapshots` Flow 來自 Room `observe()`，UI 進度與恢復清單都只讀這一份狀態。
 * - `requestCancel` 為協作式停排：目前這筆跑完後不再排新筆，批次轉 `PAUSED`。
 * - 每筆的 `workflowId` 由匯入器固定指派並存進 workflow 快照，UI 可交給
 *   [OnboardingViewModel.resume] 接手補 key / 改 Review / commit。
 *
 * Vault 邊界（不簡化）：
 * - 匯入路徑**永不寫入、永不刪除** vault：沒有 `store`、沒有 `remove`，
 *   匯入 draft 的 `credentials` / `safeCredentialIds` 恆為空（parser 不接受任何憑證欄位）。
 * - [vault] 僅以唯讀 [CredentialAccess] 傳給真探測器；因 draft 無憑證 ref，實務上不會被讀取。
 * - 補 key 只在接手該筆的 [OnboardingViewModel] 流程內做（走既有 vault 邊界），批次層不碰。
 *
 * Loopback 測試旗標：`allowLoopbackHttpForTests` 預設 `false`（產品永遠 `false`），
 * 與 [QuickAddService] 同義：測試開啟時 loopback http origin 對應回 https 等價 origin 解析憑證。
 *
 * 已知邊界：
 * - [defaultBatchWorkflowFactory] 每次 `create` 產生新的 workflow（因此各筆不共用同一份
 *   single-flight gate）；實際併發由 [BatchOnboardingImporter] 的 gate 序列化為逐筆，
 *   且 UI 只在批次非 busy 時才允許交棒給 [OnboardingViewModel]。
 * - [RoomBatchStateStore] 的 secret-guard 會掃描整份編碼後 JSON；`BatchFailure` 以列舉名序列化，
 *   故 `SECRET_INPUT_REFUSED` 這個分類名本身含 "secret"，被判定疑似 secret 時該筆的落盤會被
 *   拒（fail-closed，該次 `start` 直接失敗、`error=true`，不留半筆）。若要讓「疑似 secret 拒絕」
 *   這條路徑可被保存，需要為該列舉值加 `@SerialName(wireName)` 或放寬 guard（core / store，非本檔範圍）。
 *
 * @param vault 唯讀提供給探測器；本 service 不寫、不刪。
 * @param engine 完整 inference / telemetry pipeline（含探測用 testModel）。
 * @param database [RoomWorkflowStore] / [RoomBatchStateStore] 用的 [AtlasDatabase]。
 * @param probeClient 探測用 OkHttpClient（null 則預設）。
 * @param allowLoopbackHttpForTests 僅測試 `true`，產品永遠 `false`。
 * @param parser 匯入解析器（預設嚴格只收允許欄位）。
 * @param limits 集中技術限制；產品常數未定，呼叫端不得自行放寬。
 * @param ids 批次與單筆 workflow id 產生器（測試可注入固定序號）。
 */
class BatchImportService(
    private val vault: AndroidCredentialVault,
    private val engine: InferenceEngine,
    private val database: AtlasDatabase,
    private val probeClient: OkHttpClient? = null,
    private val allowLoopbackHttpForTests: Boolean = false,
    private val parser: BatchImportParser = StrictBatchJsonParser,
    private val limits: BatchLimits = BatchLimits.DEFAULT,
    private val ids: BatchIdGenerator = UuidBatchIdGenerator,
) {
    private val stateStore: RoomBatchStateStore by lazy { RoomBatchStateStore(database.batchSnapshotDao()) }

    /** 全部批次狀態的 Flow（Room `observe` 解碼後）；UI 進度與恢復清單的唯一來源。 */
    val snapshots: Flow<List<BatchSnapshot>> by lazy { stateStore.snapshots }

    /**
     * 長生命週期單一 importer：批次與 workflow 的 gate 都在此一份，
     * `requestCancel` 也指向同一實例（換實例會讓停排旗標失效）。
     */
    private val importer: BatchOnboardingImporter by lazy {
        val store = RoomWorkflowStore(database)
        val probe = CatalogProbe(
            loopbackAwareCredentials(),
            probeClient ?: OkHttpClient(),
            allowLoopbackHttpForTests,
        )
        BatchOnboardingImporter(
            store = stateStore,
            workflows = defaultBatchWorkflowFactory(
                store = store,
                executor = ProbeStepExecutor(probe, engine, allowLoopbackHttpForTests),
            ),
            limits = limits,
            ids = ids,
        )
    }

    /**
     * 嚴格解析後建立批次並逐筆跑到終結，回終態快照。
     *
     * 解析失敗（[dev.modelatlas.core.onboarding.batch.BatchParseException]）在發任何 request 前就拋出；
     * 逐筆驗證失敗不中斷整批，由匯入器記固定分類後續跑其他筆。
     */
    suspend fun start(rawInput: String): BatchSnapshot = importer.start(parser.parse(rawInput))

    /** 續跑未完成批次；終結（`COMPLETED`）的筆不重跑，批次已 `COMPLETED` 直接回現況。 */
    suspend fun resume(batchId: String): BatchSnapshot {
        if (batchId.isBlank()) throw IllegalStateException(ERR_BATCH_ID_INVALID)
        return importer.resume(batchId)
    }

    /** 重驅動單筆（`NEEDS_INPUT` / `BLOCKED` / `FAILED` / `RATE_LIMITED` / `CANCELLED`）。 */
    suspend fun retryItem(batchId: String, itemId: String): BatchSnapshot {
        if (batchId.isBlank() || itemId.isBlank()) throw IllegalStateException(ERR_BATCH_ID_INVALID)
        return importer.retryItem(batchId, itemId)
    }

    /** 讀取單一批次；不存在回 null（交給 UI 決定，不當成錯誤）。 */
    suspend fun load(batchId: String): BatchSnapshot? {
        if (batchId.isBlank()) return null
        return stateStore.load(batchId)
    }

    /** App 重啟後可續跑的批次識別字（供 UI 提示恢復）。 */
    suspend fun listBatchIds(): List<String> = stateStore.listBatchIds()

    /** 協作式停排：目前這筆跑完後不再排新筆；不中斷已在飛的 request（契約 §3.2 / §3.5）。 */
    fun requestCancel() {
        importer.requestCancel()
    }

    private fun loopbackAwareCredentials(): CredentialAccess {
        if (!allowLoopbackHttpForTests) return vault
        return CredentialAccess { credentialId, origin ->
            val mapped = if (isLoopbackHttpOrigin(origin)) httpsEquivalentOrigin(origin) else origin
            vault.bearerToken(credentialId, mapped)
        }
    }

    private fun isLoopbackHttpOrigin(origin: String): Boolean {
        val parsed = origin.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "http") return false
        if (parsed.host !in LOOPBACK_HOSTS) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (parsed.query != null || parsed.fragment != null) return false
        if (parsed.encodedPath != "/") return false
        return true
    }

    private fun httpsEquivalentOrigin(httpOrigin: String): String {
        val parsed = httpOrigin.toHttpUrlOrNull() ?: throw IllegalStateException(ERR_ORIGIN_INVALID)
        return "https://${parsed.host}:${parsed.port}"
    }

    private companion object {
        const val ERR_BATCH_ID_INVALID = "批次識別字無效"
        const val ERR_ORIGIN_INVALID = "origin 無效"
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
