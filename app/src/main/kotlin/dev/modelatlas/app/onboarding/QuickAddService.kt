package dev.modelatlas.app.onboarding

import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.RoomCommitter
import dev.modelatlas.app.data.RoomWorkflowStore
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.network.CatalogProbe
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.onboarding.CommitReceipt
import dev.modelatlas.core.onboarding.NewApiPricingQuotaNoticeSource
import dev.modelatlas.core.onboarding.OnboardingFlowType
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.OnboardingStatus
import dev.modelatlas.core.onboarding.OnboardingStepId
import dev.modelatlas.core.onboarding.OnboardingStepStatus
import dev.modelatlas.core.onboarding.OnboardingWorkflow
import dev.modelatlas.core.onboarding.ProbeStepExecutor
import dev.modelatlas.core.onboarding.ReviewApproval
import dev.modelatlas.core.onboarding.ReviewItems
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Quick Add 應用層組裝（無 UI）。
 *
 * 流程（`docs/PROVIDER_ONBOARDING_CONTRACT.md` §1 / §3）：
 * - `startQuickAdd` 驗證 `baseUrl`（HTTPS，否則固定安全字串 `INPUT_INVALID` 對應錯誤）、
 *   若有 `secretTransient` 則以新 UUID 經 `vault.store` 寫入（duplicate 衝突即錯，不覆寫），
 *   組 draft（含 `safeCredentialIds`、`websiteUrl`、`suggestedModelIds`，只含 safe refs）
 *   經 `OnboardingWorkflow(RoomWorkflowStore, ProbeStepExecutor(CatalogProbe, engine, noticeSource), RoomCommitter)`
 *   跑到 REVIEW 待確認或 NEEDS_INPUT / BLOCKED，回 `workflowId`。
 *   `noticeSource`（`NewApiPricingQuotaNoticeSource`）讓 `PRICING_QUOTA_NOTICE` 對已知
 *   adapter 真嘗試 capabilities／token-quota／notice（匿名＋inference key；Quick Add
 *   無 PAT 故 pricing 記不支援），不支援／失敗才 Unknown（PROV-002）。
 * - `reviewSummary` 回傳已產生的 [ReviewItems]，無則錯。
 * - `approveAndCommit` 先寫 `reviewApproval` 再 `workflow.commit`；commit 內 vault 孤兒 ref
 *   依 `RoomCommitter` KDoc 既定語意由呼叫端自行清理，本 service 不刪 vault。
 * - `abandon` 放棄 workflow；`resume` 從 Room 恢復續跑（可中斷恢復）。
 * - 長生命週期單一 `workflow` instance + `operations` gate：所有驅動共用同一份 single-flight gate，
 *   不再每次 `newWorkflow()`（舊寫法 gate 每呼叫一份，無法防重入）。
 * - `snapshots` Flow：Room `observeAll` 解碼後，解碼錯誤固定安全訊息，不洩漏內容。
 * - `retryStep` / `skipStep`：單步重試/跳過，總預算不重置。
 * - `startQuickAdd` 在存 vault 後、探測前即 persist snapshot，可觀察；取消不刪已保存 workflow 仍需的 vault ref。
 * - `editQuick` 安全重跑：origin 變更不可沿用舊 key；不重置總預算；改輸入必清 approval/舊下游結果；
 *   被拒 auth 換 key 從 AUTH 重跑；Review 改名不重新探測。
 *
 * Secret 邊界：
 * - `secretTransient` 為簡潔接受 `String`（`String` 不可抹除，呼叫端須最小化持有：用完即丟、
 *   不 log、不進 Bundle、不拼接字串）；vault 內部以 byte[] 並 `fill(0)` 清除，
 *   snapshot 只存 `safeCredentialId`，錯誤訊息固定且不含 URL / key / server body。
 * - 所有 credential 只傳 safe refs；原始值只走 vault 路徑。
 * - `start` 失敗回滾 vault ref；`abandon` 順手清除本 workflow 的孤兒 refs（僅 pre-commit，
 *   已 commit 的 abandon 會先拋錯、不動 vault）。
 *
 * Loopback 測試旗標：
 * - `allowLoopbackHttpForTests` 預設 `false`（產品永遠 `false`，僅接受 HTTPS）。
 *   測試用 `true` 時才允許 `http://127.0.0.1|localhost|::1`（無 userinfo / query / fragment），
 *   並同時透給 `CatalogProbe` 與 `ProbeStepExecutor`（URL 組裝）。
 * - 真機 vault 只接受 HTTPS origin；`true` 時 service 以 https 等價 origin
 *  （同 host / port，scheme 換成 `https`）寫入 vault，探測時再把 http loopback origin
 *   對應回 https 等價 origin 解析，生產路徑（`false`）不做任何轉換、嚴格比對。
 * - `engine` 的 transport 旗標由呼叫端建 engine 時決定；commit 不要求 testModel 成功，
 *   故預設 engine（拒 http）仍可測 commit（testModel 記 FAILED 但可進 Review / commit）。
 *
 * 未完成項：UI（Quick Add 表單 / 進度 / Review 畫面）、batch import（沿用 workflow 逐筆，不在此實作）。
 *
 * @param vault Android Keystore-backed vault，只經 safe refs 使用。
 * @param engine 完整 inference / telemetry pipeline（含探測用 testModel；engine 持有 telemetry sink）。
 * @param database [RoomWorkflowStore] / [RoomCommitter] 用的 [AtlasDatabase]。
 * @param probeClient 探測用 OkHttpClient（null 則預設）；`CatalogProbe` 內會重設 redirect / timeout。
 * @param allowLoopbackHttpForTests 僅測試 `true`，產品永遠 `false`。
 */
class QuickAddService(
    private val vault: AndroidCredentialVault,
    private val engine: InferenceEngine,
    private val database: AtlasDatabase,
    private val probeClient: OkHttpClient? = null,
    private val allowLoopbackHttpForTests: Boolean = false,
) {
    private val store: RoomWorkflowStore by lazy { RoomWorkflowStore(database) }

    /**
     * 長生命週期單一 workflow instance。
     *
     * 過去每次呼叫都 `newWorkflow()` 會讓 `OnboardingWorkflow` 內的 `gate` Mutex
     * 變成每呼叫一份，完全失去 single-flight 效果（同時可跑兩個探測，違反契約 §3.2 / §5）。
     * 現在此 service 只建一份 `workflow`，所有驅動（start/resume/retry/skip/commit/abandon/edit）
     * 共用同一份 gate；再加上 service 層 `operations` gate 序列化 vault 短臨界區，
     * 達到單裝置同時只跑一個 onboarding 探測。
     */
    private val workflow: OnboardingWorkflow by lazy {
        val client = probeClient ?: OkHttpClient()
        val credentials = loopbackAwareCredentials()
        val probe = CatalogProbe(credentials, client, allowLoopbackHttpForTests)
        // PRICING_QUOTA_NOTICE 真嘗試（PROV-002）：已知 adapter（New API structured
        // surface）走能力介面；Quick Add 無 PAT，故 pricing 恆記不支援＋evidence，
        // quota 走 inference key 層級、notice／capabilities 走匿名。
        val noticeSource = NewApiPricingQuotaNoticeSource(
            NewApiAdapter(credentials, client, allowLoopbackHttpForTests = allowLoopbackHttpForTests),
        )
        val executor = ProbeStepExecutor(probe, engine, allowLoopbackHttpForTests, noticeSource)
        val committer = RoomCommitter(database)
        OnboardingWorkflow(store, executor, committer)
    }

    /** Service 層 operation gate：序列化 vault 寫入與 snapshot 變異短臨界區；長探測由 `workflow` gate 持有。 */
    private val operations = Mutex()

    private suspend fun <T> withOperation(block: suspend () -> T): T {
        check(operations.tryLock()) { "新增流程執行中" }
        return try { block() } finally { operations.unlock() }
    }

    private val snapshotJson: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /**
     * 全部 workflow snapshots 的 Flow（Room `observeAll` 解碼後）。
     *
     * 解碼失敗一律固定安全訊息 [ERR_SNAPSHOT_DECODE]，不攜帶 JSON 內容 / URL / key，
     * 避免把疑似 secret 洩漏進例外 / log。`CancellationException` 重新拋出。
     */
    val snapshots: Flow<List<OnboardingSnapshot>> =
        database.onboardingSnapshotDao().observeAll().map { rows ->
            rows.map { row ->
                try {
                    snapshotJson.decodeFromString(OnboardingSnapshot.serializer(), row.snapshotJson)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    throw IllegalStateException(ERR_SNAPSHOT_DECODE)
                }
            }
        }
    /**
     * 開始 Quick Add，跑到 REVIEW 待確認或 NEEDS_INPUT / BLOCKED，回 `workflowId`。
     *
     * @param baseUrl 必填，HTTPS（測試允許 http loopback）；strip 後空或非法即固定字串錯誤。
     * @param websiteUrl 選填，人類查證位址；空即 null，非法即錯。
     * @param secretTransient 選填，短生命週期 [String]；有則寫 vault，無則匿名流程。
     * @param suggestedModelId 選填，單一猜測 model ID；空即無。
     */
    suspend fun startQuickAdd(
        baseUrl: String,
        websiteUrl: String? = null,
        secretTransient: String? = null,
        suggestedModelId: String? = null,
        workflowId: String = UUID.randomUUID().toString(),
    ): String {
        val cleanBase = cleanBaseUrl(baseUrl)
        val cleanWebsite = cleanWebsiteUrl(websiteUrl)
        val suggested = cleanSuggested(suggestedModelId)
        return withOperation {
            var createdRef: String? = null
            // 先產生 workflowId：失敗時可判斷 snapshot 是否已落地，決定是否清孤兒 vault ref。
            // `workflow.start` 會在任何探測前先 persist fresh snapshot，因此存 vault 後、探測前即有 snapshot 可觀察。
            require(workflowId.isNotBlank())
            try {
                val safeIds: List<String>
                val refs: List<SafeCredentialRef>
                if (!secretTransient.isNullOrBlank()) {
                    validateSecretFormat(secretTransient)
                    val ref = UUID.randomUUID().toString()
                    val origin = vaultOriginForBase(cleanBase)
                    try {
                        vault.store(ref, origin, secretTransient)
                    } catch (e: IllegalStateException) {
                        // Vault 固定安全字串（例如 duplicate），不包裝、不附加輸入，直接傳遞。
                        throw e
                    } catch (e: IllegalArgumentException) {
                        throw e
                    }
                    createdRef = ref
                    safeIds = listOf(ref)
                    refs = listOf(SafeCredentialRef(ref, CREDENTIAL_LABEL))
                } else {
                    safeIds = emptyList()
                    refs = emptyList()
                }
                val draft = OnboardingSnapshot(
                    workflowId = workflowId,
                    flowType = OnboardingFlowType.QUICK,
                    baseUrl = cleanBase,
                    websiteUrl = cleanWebsite,
                    safeCredentialIds = safeIds,
                    credentials = refs,
                    suggestedModelIds = suggested,
                )
                workflow.start(draft)
                workflowId
            } catch (e: CancellationException) {
                // 取消不可刪除已保存 workflow 仍需用的 vault ref：只有 snapshot 尚未落地（DAO 無列）才清孤兒。
                withContext(NonCancellable) {
                    if (!snapshotRowExists(workflowId) && createdRef != null) runCatching { vault.remove(createdRef) }
                }
                throw e
            } catch (e: Exception) {
                if (!snapshotRowExists(workflowId) && createdRef != null) {
                    runCatching { vault.remove(createdRef) }
                }
                throw e
            }
        }
    }

    /**
     * 讀取單一 snapshot；解碼失敗固定安全訊息，不洩漏 JSON / URL / key。
     * 缺失回 null（呼叫端以 [ERR_WORKFLOW_NOT_FOUND] 處理）。
     */
    suspend fun loadSnapshot(workflowId: String): OnboardingSnapshot? {
        if (workflowId.isBlank()) throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        try {
            return store.load(workflowId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalStateException(ERR_SNAPSHOT_DECODE)
        }
    }

    suspend fun editReview(workflowId: String, name: String?, selectedModels: List<String>, manualModelId: String? = null) = withOperation {
        workflow.editReview(workflowId, name, selectedModels, manualModelId)
    }

    /** 回傳已產生的 Review 摘要；snapshot 缺失或 review 尚未跑出即固定字串錯誤。 */
    suspend fun reviewSummary(workflowId: String): ReviewItems {
        if (workflowId.isBlank()) throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        val snapshot = try {
            store.load(workflowId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalStateException(ERR_SNAPSHOT_DECODE)
        } ?: throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        return snapshot.reviewItems ?: throw IllegalStateException(ERR_REVIEW_NOT_READY)
    }

    /**
     * 寫入人工確認後 commit，回傳 [CommitReceipt]。
     *
     * 先經 `workflow.commit(workflowId, approval)` 寫 `reviewApproval` 再執行 commit step；
     * 孤兒 vault ref 依 `RoomCommitter` KDoc 由呼叫端清理，本方法不刪 vault。
     */
    suspend fun approveAndCommit(workflowId: String, approvedBy: String? = null): CommitReceipt {
        if (workflowId.isBlank()) throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        val approval = ReviewApproval(
            approved = true,
            approvedAtMillis = System.currentTimeMillis(),
            approvedBy = approvedBy,
        )
        val summary = withOperation { workflow.commit(workflowId, approval) }
        return summary.commitReceipt ?: throw IllegalStateException(ERR_COMMIT_INCOMPLETE)
    }

    /**
     * 放棄 workflow（destructive，確認由呼叫端負責）。
     * 成功後順手清除本 workflow 的孤兒 vault refs（UUID 新建、只屬本單）；
     * 已 commit 的 abandon 會先拋錯，不動 vault。
     */
    suspend fun abandon(workflowId: String, reason: String? = null) {
        if (workflowId.isBlank()) throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        val snapshot = withOperation {
            val loaded = try {
                store.load(workflowId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw IllegalStateException(ERR_SNAPSHOT_DECODE)
            }
            workflow.abandon(workflowId, reason)
            loaded
        }
        // 已 commit 的 abandon 會在 gate 內先拋錯，不會走到這裡、不動 vault。
        snapshot?.safeCredentialIds.orEmpty().forEach { ref ->
            runCatching { vault.remove(ref) }
        }
        snapshot?.credentials.orEmpty().forEach { ref ->
            runCatching { vault.remove(ref.safeCredentialId) }
        }
        snapshot?.customAuthFields.orEmpty().mapNotNull { it.safeCredentialId }.forEach { ref ->
            runCatching { vault.remove(ref) }
        }
    }

    /** 從 Room 恢復續跑（App 重啟後同一 `workflowId` 不重跑已成功步驟），回 `workflowId`。 */
    suspend fun resume(workflowId: String): String {
        if (workflowId.isBlank()) throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        val summary = withOperation { workflow.resume(workflowId) }
        return summary.workflowId
    }

    /** 重跑單步：舊結果標 SUPERSEDED，下游標 STALE；總預算不重置（core 語意）。 */
    suspend fun retryStep(workflowId: String, step: OnboardingStepId): String {
        if (workflowId.isBlank()) throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        val summary = withOperation { workflow.retryStep(workflowId, step) }
        return summary.workflowId
    }

    /** 跳過單步（REVIEW/COMMIT 不可跳過，core 會拋錯）；不重開下游、不清 approval。 */
    suspend fun skipStep(workflowId: String, step: OnboardingStepId, reason: String? = null): String {
        if (workflowId.isBlank()) throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        val summary = withOperation { workflow.skipStep(workflowId, step, reason) }
        return summary.workflowId
    }

    /**
     * 安全重跑已存在 workflow 的輸入（Review 的 Edit & Re-run 入口）。
     *
     * 語意（`docs/PROVIDER_ONBOARDING_CONTRACT.md` §1.4 / §3.4 / §4）：
     * - 任何參數為 null 代表不改；空字串代表清除（website/suggested/providerName），base 不接受空。
     * - origin 變更不可沿用舊 key：舊 safe refs 直接 dropped（成功保存後刪 vault 孤兒），
     *   無新 key 即以匿名繼續，停在 AUTH `NEEDS_INPUT`，不是 FAILED。
     * - 不重置總預算：沿用 `snapshot.budget`，只清 `rateLimitedAtMillis`（明確使用者動作可解除 429 停手），
     *   per-step 與總量上限仍在。
     * - 改輸入必清 `reviewApproval`；探測相關改動同時清舊下游結果（findings + stepStatuses 回 PENDING），
     *   被拒 auth（`AUTH_FAILED`）換 key 即從 AUTH 重跑。
     * - Review 改名（`providerName`）不重新探測：只更新名稱、清 approval，不動任何探測 step、不發 request。
     *
     * @param workflowId 必填，已存在且未結束（COMMITTED/ABANDONED 拒絕，固定字串）。
     * @param baseUrl null 不改，否則 HTTPS（測試允許 loopback）；非法即 `輸入無效`。
     * @param websiteUrl null 不改，空即清除，否則 HTTPS；非法即錯。
     * @param secretTransient null/blank 不換 key，否則存新 vault ref 取代舊 ref。
     * @param suggestedModelId null 不改，空即清除，否則單一 ID。
     * @param providerName null 不改，空即清除回預設命名，否則更新（不探測）。
     */
    suspend fun editQuick(
        workflowId: String,
        baseUrl: String? = null,
        websiteUrl: String? = null,
        secretTransient: String? = null,
        suggestedModelId: String? = null,
        providerName: String? = null,
    ): String = withOperation {
        if (workflowId.isBlank()) throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        val current = try {
            store.load(workflowId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalStateException(ERR_SNAPSHOT_DECODE)
        } ?: throw IllegalStateException(ERR_WORKFLOW_NOT_FOUND)
        if (current.status == OnboardingStatus.COMMITTED || current.status == OnboardingStatus.ABANDONED) {
            throw IllegalStateException(ERR_ALREADY_FINISHED)
        }
        val requestedBase = if (baseUrl == null) current.baseUrl else cleanBaseUrl(baseUrl)
        val requestedWebsite = if (websiteUrl == null) current.websiteUrl else cleanWebsiteUrl(websiteUrl)
        val requestedSuggested = if (suggestedModelId == null) current.suggestedModelIds else cleanSuggested(suggestedModelId)
        val requestedName = if (providerName == null) current.providerName else providerName.trim().takeIf { it.isNotEmpty() }
        val secretProvided = !secretTransient.isNullOrBlank()
        if (secretProvided) validateSecretFormat(secretTransient!!)
        val baseChanged = requestedBase != current.baseUrl
        val websiteChanged = requestedWebsite != current.websiteUrl
        val suggestedChanged = requestedSuggested != current.suggestedModelIds
        val nameChanged = requestedName != current.providerName
        if (!baseChanged && !websiteChanged && !suggestedChanged && !nameChanged && !secretProvided) {
            return@withOperation workflowId
        }
        val oldOrigin = current.baseUrl?.let { runCatching { vaultOriginForBase(it) }.getOrNull() }
        val newOrigin = requestedBase?.let { runCatching { vaultOriginForBase(it) }.getOrNull() }
        val originChanged = if (requestedBase != current.baseUrl) {
            oldOrigin != newOrigin
        } else {
            false
        }
        var newRef: String? = null
        var saved = false
        try {
            val newSafeIds: List<String>
            val newCreds: List<SafeCredentialRef>
            if (secretProvided) {
                val ref = UUID.randomUUID().toString()
                val origin = newOrigin ?: oldOrigin ?: requestedBase?.let { vaultOriginForBase(it) }
                    ?: throw IllegalArgumentException(ERR_INPUT_INVALID)
                vault.store(ref, origin, secretTransient!!)
                newRef = ref
                newSafeIds = listOf(ref)
                newCreds = listOf(SafeCredentialRef(ref, CREDENTIAL_LABEL))
            } else if (originChanged) {
                newSafeIds = emptyList()
                newCreds = emptyList()
            } else {
                newSafeIds = current.safeCredentialIds
                newCreds = current.credentials
            }
            val earliest: OnboardingStepId? = when {
                baseChanged -> OnboardingStepId.INSPECT
                secretProvided -> OnboardingStepId.AUTH
                suggestedChanged -> OnboardingStepId.TEST_MODEL
                websiteChanged -> OnboardingStepId.PRICING_QUOTA_NOTICE
                else -> null
            }
            val edited = current.copy(
                baseUrl = requestedBase,
                websiteUrl = requestedWebsite,
                suggestedModelIds = requestedSuggested,
                providerName = requestedName,
                safeCredentialIds = newSafeIds,
                credentials = newCreds,
                reviewApproval = null,
                reservation = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
            workflow.editAndRerun(workflowId, edited, earliest ?: OnboardingStepId.REVIEW)
            saved = true
            val oldRefs = (current.safeCredentialIds + current.credentials.map { it.safeCredentialId }).distinct()
            val keptRefs = (newSafeIds + newCreds.map { it.safeCredentialId }).toSet()
            // 成功保存後才刪不再被引用的舊孤兒 refs；新 ref 必保留。
            oldRefs.filter { it !in keptRefs }.forEach { ref ->
                runCatching { vault.remove(ref) }
            }
            workflowId
        } catch (e: CancellationException) {
            if (!saved && newRef != null) {
                removeUnreferenced(workflowId, newRef)
            }
            throw e
        } catch (e: Exception) {
            if (!saved && newRef != null) {
                removeUnreferenced(workflowId, newRef)
            }
            throw e
        }
    }

    private suspend fun removeUnreferenced(workflowId: String, ref: String) = withContext(NonCancellable) {
        val loaded = runCatching { store.load(workflowId) }
        if (loaded.isSuccess) {
            val s = loaded.getOrNull()
            val used = s?.safeCredentialIds.orEmpty() + s?.credentials.orEmpty().map { it.safeCredentialId } +
                s?.customAuthFields.orEmpty().mapNotNull { it.safeCredentialId }
            if (ref !in used) runCatching { vault.remove(ref) }
        }
    }

    private fun clearDownstreamFrom(snapshot: OnboardingSnapshot, earliest: OnboardingStepId): OnboardingSnapshot {
        var edited = snapshot
        val statuses = snapshot.stepStatuses.toMutableMap()
        fun reset(step: OnboardingStepId) {
            statuses[step] = OnboardingStepStatus.PENDING
        }
        when (earliest) {
            OnboardingStepId.INSPECT -> {
                edited = edited.copy(
                    detection = null,
                    authDecision = null,
                    credentialTest = null,
                    catalog = null,
                    modelTests = null,
                    pricingQuota = null,
                    reviewItems = null,
                    rateLimitedAtMillis = null,
                )
                listOf(
                    OnboardingStepId.INSPECT,
                    OnboardingStepId.DETECT_TYPE,
                    OnboardingStepId.AUTH,
                    OnboardingStepId.CREDENTIAL_TEST,
                    OnboardingStepId.LIST_MODELS,
                    OnboardingStepId.TEST_MODEL,
                    OnboardingStepId.PRICING_QUOTA_NOTICE,
                    OnboardingStepId.REVIEW,
                ).forEach { reset(it) }
            }
            OnboardingStepId.AUTH -> {
                edited = edited.copy(
                    authDecision = null,
                    credentialTest = null,
                    catalog = null,
                    modelTests = null,
                    pricingQuota = null,
                    reviewItems = null,
                    rateLimitedAtMillis = null,
                )
                listOf(
                    OnboardingStepId.AUTH,
                    OnboardingStepId.CREDENTIAL_TEST,
                    OnboardingStepId.LIST_MODELS,
                    OnboardingStepId.TEST_MODEL,
                    OnboardingStepId.PRICING_QUOTA_NOTICE,
                    OnboardingStepId.REVIEW,
                ).forEach { reset(it) }
            }
            OnboardingStepId.TEST_MODEL -> {
                edited = edited.copy(
                    modelTests = null,
                    pricingQuota = null,
                    reviewItems = null,
                    rateLimitedAtMillis = null,
                )
                listOf(
                    OnboardingStepId.TEST_MODEL,
                    OnboardingStepId.PRICING_QUOTA_NOTICE,
                    OnboardingStepId.REVIEW,
                ).forEach { reset(it) }
            }
            OnboardingStepId.PRICING_QUOTA_NOTICE -> {
                edited = edited.copy(
                    pricingQuota = null,
                    reviewItems = null,
                    rateLimitedAtMillis = null,
                )
                listOf(
                    OnboardingStepId.PRICING_QUOTA_NOTICE,
                    OnboardingStepId.REVIEW,
                ).forEach { reset(it) }
            }
            else -> {
                edited = edited.copy(reviewItems = null, rateLimitedAtMillis = edited.rateLimitedAtMillis)
                reset(OnboardingStepId.REVIEW)
            }
        }
        // 保留總預算（budget 不動）；只清 approval（上層已清）與下游 findings。
        return edited.copy(stepStatuses = statuses)
    }

    private suspend fun snapshotRowExists(workflowId: String): Boolean =
        runCatching { database.onboardingSnapshotDao().getByWorkflow(workflowId) != null }.getOrDefault(true)

    private fun loopbackAwareCredentials(): CredentialAccess {
        if (!allowLoopbackHttpForTests) return vault
        return CredentialAccess { credentialId, origin ->
            val mapped = if (isLoopbackHttpOrigin(origin)) httpsEquivalentOrigin(origin) else origin
            vault.bearerToken(credentialId, mapped)
        }
    }

    private fun cleanBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException(ERR_INPUT_INVALID)
        if (allowLoopbackHttpForTests && isLoopbackHttpUrl(trimmed)) return trimmed
        try {
            EndpointPolicy.validate(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException(ERR_INPUT_INVALID)
        }
        return trimmed
    }

    private fun cleanWebsiteUrl(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (allowLoopbackHttpForTests && isLoopbackHttpUrl(trimmed)) return trimmed
        try {
            EndpointPolicy.validate(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException(ERR_INPUT_INVALID)
        }
        return trimmed
    }

    private fun cleanSuggested(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        return listOf(trimmed)
    }

    private fun validateSecretFormat(secret: String) {
        if (secret.isBlank()) throw IllegalArgumentException(ERR_CREDENTIAL_FORMAT)
        if (secret.length > 8192 || '\r' in secret || '\n' in secret) {
            throw IllegalArgumentException(ERR_CREDENTIAL_FORMAT)
        }
    }

    private fun vaultOriginForBase(cleanBase: String): String {
        if (allowLoopbackHttpForTests && isLoopbackHttpUrl(cleanBase)) {
            val parsed = cleanBase.toHttpUrlOrNull() ?: throw IllegalArgumentException(ERR_INPUT_INVALID)
            return "https://${parsed.host}:${parsed.port}"
        }
        try {
            return EndpointPolicy.origin(EndpointPolicy.validate(cleanBase))
        } catch (_: Exception) {
            throw IllegalArgumentException(ERR_INPUT_INVALID)
        }
    }

    private fun isLoopbackHttpUrl(raw: String): Boolean {
        val parsed = raw.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "http") return false
        if (parsed.host !in LOOPBACK_HOSTS) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (parsed.query != null || parsed.fragment != null) return false
        return true
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
        val parsed = httpOrigin.toHttpUrlOrNull() ?: throw IllegalArgumentException(ERR_INPUT_INVALID)
        return "https://${parsed.host}:${parsed.port}"
    }

    private companion object {
        const val CREDENTIAL_LABEL = "Quick Add \u6191\u8B49"
        const val ERR_INPUT_INVALID = "\u8F38\u5165\u7121\u6548"
        const val ERR_CREDENTIAL_FORMAT = "\u6191\u8B49\u683C\u5F0F\u7121\u6548"
        const val ERR_WORKFLOW_NOT_FOUND = "workflow \u4E0D\u5B58\u5728"
        const val ERR_REVIEW_NOT_READY = "review \u5C1A\u672A\u5C31\u7DD2"
        const val ERR_COMMIT_INCOMPLETE = "commit \u672A\u5B8C\u6210"
        const val ERR_SNAPSHOT_DECODE = "snapshot \u89E3\u6790\u5931\u6557"
        const val ERR_ALREADY_FINISHED = "workflow \u5DF2\u7D50\u675F"
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
