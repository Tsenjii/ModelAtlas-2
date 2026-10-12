package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.onboarding.AdapterType
import dev.modelatlas.core.onboarding.CommitReceipt
import dev.modelatlas.core.onboarding.CommitRejectedException
import dev.modelatlas.core.onboarding.Committer
import dev.modelatlas.core.onboarding.OnboardingClock
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.EndpointRef
import dev.modelatlas.core.onboarding.OnboardingFailureReason
import dev.modelatlas.core.onboarding.OnboardingSnapshot
import dev.modelatlas.core.onboarding.SafeCredentialRef
import dev.modelatlas.core.onboarding.SystemOnboardingClock
import dev.modelatlas.core.onboarding.WorkflowStore
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Phase 3 onboarding 持久化與提交（`docs/PROVIDER_ONBOARDING_CONTRACT.md` §3.2 / §3.4 / §6）。
 *
 * 範圍：workflow snapshot 整份覆寫保存、commit 時把已核可 workflow 落成
 * Provider / Account / Credential-metadata / Endpoint / Offering / Availability。
 * 不做 UI、不做探測、不碰 vault Secrets（原始 Key / PAT 只走 vault 路徑）。
 */

/** Workflow 快照列：整份 [OnboardingSnapshot] JSON 覆寫保存，只含 safe refs，不含 secret。 */
@Entity(tableName = "onboarding_snapshots")
data class OnboardingSnapshotEntity(
    @androidx.room.PrimaryKey val workflowId: String,
    val updatedAt: Long,
    val snapshotJson: String,
)

/**
 * Commit 去重鍵列：同一 `workflowId` 只對應一個 `providerId`。
 * 重放 commit 先讀此列；`ABORT` 語意保證不產生第二個 provider。
 */
@Entity(tableName = "onboarding_commits")
data class OnboardingCommitEntity(
    @androidx.room.PrimaryKey val workflowId: String,
    val providerId: String,
    val committedAt: Long,
)

@Dao
interface OnboardingSnapshotDao {
    @Query("SELECT * FROM onboarding_snapshots ORDER BY updatedAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<OnboardingSnapshotEntity>>

    @Query("SELECT * FROM onboarding_snapshots WHERE workflowId = :workflowId")
    suspend fun getByWorkflow(workflowId: String): OnboardingSnapshotEntity?

    /** 整份覆寫：同 workflowId 的舊 snapshot 直接取代，不留歷史版本。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OnboardingSnapshotEntity)
}

@Dao
interface OnboardingCommitDao {
    @Query("SELECT * FROM onboarding_commits WHERE workflowId = :workflowId")
    suspend fun getByWorkflow(workflowId: String): OnboardingCommitEntity?

    /** 去重鍵必須 `ABORT`：重複 workflowId 代表併發重放，整筆 transaction  rollback。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OnboardingCommitEntity)
}

/**
 * Snapshot JSON 疑似含有 secret 字樣時拒絕寫入。
 * 訊息固定、不攜帶 JSON 內容，避免把疑似 secret 洩漏進例外 / log。
 */
class OnboardingSecretRefusedException :
    IllegalStateException("onboarding snapshot 疑似含有 secret 字樣，拒絕寫入")

/** Snapshot 編解碼用 JSON：全部欄位具現、未知欄位忽略（前向相容）。 */
internal val OnboardingJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/**
 * Secret-guard：fail-closed，擋三類樣式（大小寫無關）。
 *
 * 1. 裸字樣：在合法 snapshot 永不出現的欄位 / 值名稱：
 *    `secret`、`password`、`passwd`、`private_key`、`client_secret`、
 *    `access_token`、`refresh_token`、`secrettransient`、`valuetransient`、`rawkey`。
 * 2. Key 位置：JSON key 含 `api_key` / `authorization` / `bearer` / `token` 且後接 `:`。
 *    只擋 key 位置，因為 `AuthMode.API_KEY` / `AuthMode.BEARER` 列舉值是合法 metadata，
 *    它們出現在值位置（後接 `,` / `}`），不會誤擋。
 * 3. 值樣式：`sk-` 開頭後接 8 碼以上（外洩 key 常見前綴）；合法 modelId / ref 不用此前綴。
 */
internal fun ensureSnapshotJsonHasNoSecretRefs(snapshotJson: String) {
    val lowered = snapshotJson.lowercase()
    for (token in SECRET_BARE_TOKENS) {
        if (lowered.contains(token)) throw OnboardingSecretRefusedException()
    }
    if (SECRET_KEY_PATTERN.containsMatchIn(snapshotJson)) throw OnboardingSecretRefusedException()
    if (SECRET_VALUE_PATTERN.containsMatchIn(snapshotJson)) throw OnboardingSecretRefusedException()
}

private val SECRET_BARE_TOKENS: List<String> = listOf(
    "secret",
    "password",
    "passwd",
    "private_key",
    "client_secret",
    "access_token",
    "refresh_token",
    "secrettransient",
    "valuetransient",
    "rawkey",
)

private val SECRET_KEY_PATTERN: Regex =
    Regex(""""[^"]*(api[_-]?key|authorization|bearer|token)[^"]*"\s*:""", RegexOption.IGNORE_CASE)

private val SECRET_VALUE_PATTERN: Regex = Regex("""sk-[A-Za-z0-9_-]{8,}""")

/**
 * Room 版 [WorkflowStore]：每步完成即整份覆寫保存，可中斷恢復（契約 §3.2）。
 * `save` 前先做 secret-guard，疑似含 secret 字樣即拒絕寫入並拋出，
 * 不寫半筆、不留舊 snapshot 被污染（`upsert` 只在檢查通過後執行）。
 */
class RoomWorkflowStore(
    private val db: AtlasDatabase,
    private val json: Json = OnboardingJson,
) : WorkflowStore {
    override suspend fun load(workflowId: String): OnboardingSnapshot? {
        require(workflowId.isNotBlank()) { "workflowId must not be blank" }
        val row = db.onboardingSnapshotDao().getByWorkflow(workflowId) ?: return null
        return try {
            json.decodeFromString(OnboardingSnapshot.serializer(), row.snapshotJson)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("onboarding snapshot 解析失敗: $workflowId", e)
        }
    }

    override suspend fun save(snapshot: OnboardingSnapshot) {
        require(snapshot.workflowId.isNotBlank()) { "workflowId must not be blank" }
        val encoded = json.encodeToString(OnboardingSnapshot.serializer(), snapshot)
        ensureSnapshotJsonHasNoSecretRefs(encoded)
        db.onboardingSnapshotDao().upsert(
            OnboardingSnapshotEntity(
                workflowId = snapshot.workflowId,
                updatedAt = snapshot.updatedAtMillis,
                snapshotJson = encoded,
            ),
        )
    }
}

/**
 * Room 版 [Committer]：把已核可 workflow 落成產品資料（契約 §3.4 / §6）。
 *
 * - 重檢：`reviewApproval` 缺失或未核可即拋 [CommitRejectedException]
 *  （`REVIEW_NOT_APPROVED`）；`manualModelIds` 與 catalog 皆無可用模型亦拋
 *  （`NO_MODELS_TO_TEST`）。不要求所有模型測試成功。
 * - 單一 transaction（`ABORT`，禁止靜默部分成功）依序寫入
 *   Provider → Account → Credential-metadata → Endpoint × N → Offering × N×M
 *   → 模型 canonical／alias（+ 手填分類）→ Availability，最後寫
 *   [OnboardingCommitEntity] 去重鍵。失敗即整筆 rollback，不留半筆。
 * - 多 endpoint：保存所有 `enabled=true` 的 [EndpointRef]（各一 [EndpointEntity]）；
 *   每個 endpoint × 每個 model 建一 offering（`source=ONBOARDING`，手填模型 `manual=true`）；
 *   `enabled=false` 的 profile 不寫 Endpoint / Offering / Availability，不變推理 connection
 *   （仍保留於 workflow snapshot，可後續編輯重跑）。profiles 為空時沿用舊最小路徑：
 *   由 `baseUrl` 預設單 `CHAT_COMPLETIONS` endpoint（QuickAdd / `saveConnection` 接軌）。
 *   非空但零 `enabled` 即拋 `IllegalArgumentException`，不靜默 fallback 到 disabled。
 * - Strict URL：`baseUrl` 與各 endpoint 經 [EndpointPolicy] 嚴格檢驗（HTTPS only、
 *   無 userinfo / query / fragment）；相對路徑以 `baseUrl` join 後再檢驗；
 *   含 `://` 卻非 `http(s)://` 前綴、或 `http://` 非 HTTPS 一律拒絕。
 *   所有 enabled endpoint 的 origin 必須與 base origin 一致，否則拋
 *   `IllegalArgumentException(Cross-origin endpoint rejected)`（不攜帶 URL），
 *   避免 vault 單 origin 綁定被跨 origin 憑證誤用；檢查置於 transaction 之前。
 * - PAT 隔離：`SafeCredentialRef.role == ACCOUNT` 只寫 [CredentialEntity] metadata，
 *   絕不建 availability；只有 `INFERENCE` 才掛載到每個 offering。
 *   同一 `safeCredentialId` 同時出現兩種 role 即拋 `IllegalArgumentException`（fail-closed）。
 *   匿名（零 `INFERENCE` 憑證，含純 PAT / 完全無憑證）仍寫 Provider / Endpoint / Offering，
 *   但 availability 為空，故 `observe()` / `observeManagement()` 無對應 connection；
 *   不合成假 key（見回報：需新增 offering-direct 匿名 target 查詢）。
 * - Custom anchor（P0-3）：`API_KEY / CUSTOM_HEADER` 以 `customAuthFields[].safeCredentialId`
 *   建 inference anchor availability，secret 只留 vault（只寫 metadata，不讀值）。
 *   每個 custom field 需有非空白 `safeCredentialId`，缺 ref 即拋
 *   `IllegalArgumentException(custom auth ref required)`（fail-closed，不產不可用 commit）。
 *   Custom id 不可與 `ACCOUNT` role id 重疊，重疊即拋 `Credential role conflict`
 *  （避免 PAT 誤進 inference anchor）。Anchor 綁定首個 account（custom-only 多為單一預設帳號），
 *   label 取 `自訂授權：LOCATION name`（僅欄位名，不含值）。
 *   `INFERENCE` 非空時沿用既有行為（只用 inference，不另增 custom availability，
 *   避免混合快照出現重複 connection；推理時仍經 provider_config 取全部 custom fields）。
 *   零 `INFERENCE` 且有 custom anchor 時，每個 offering × 每個 anchor 建一 availability，
 *   故 `API_KEY`（單欄）每 model 恰一可用 connection；`CUSTOM_HEADER` 多欄時每 anchor 一列，
 *   與多 inference 憑證既有語意一致。`ACCOUNT` 排除不變。
 * - Adapter：`adapterTypeOverride` 優先於 `detection`（手填優先，自動值僅 evidence 對照）。
 * - Checkin：手填 `checkinUrl` 優先於探測值（手填非法即拋，探測非法則忽略）；
 *   手填存在即 `requiresDailyCheckin=true`，否則沿用探測旗標；無則 null/false。
 * - 未支援欄位（既有 schema 無損保存不可行，不假支援、不擅自 migration）：
 *   `manualPricing`（金額 `currency` / `perUnit` / `effectiveFrom` / `note` 無對應欄；
 *   `PriceSnapshotEntity` 僅 `input/output/fixedRequest/cache*`，無幣別與單位）、
 *   `manualQuotaNote`（`QuotaSnapshotEntity` 無 note 欄）、`group`
 *   （provider 層 group 落 `provider_configurations.snapshotJson`；offering／availability
 *   的 group 欄本檔不臆測填入，null＝未知 scope）、`tags` / `notes` / `websiteUrl` /
 *   `customAuthFields` / `authModeOverride`（Provider / Account 無對應欄）。以上輸入保留於
 *   workflow snapshot，commit 仍以 `UNKNOWN` 落地，不以他組價格覆蓋、不寫 `$0`。
 * - 模型映射（MODEL-001…003／BACKLOG F1）：offerings 寫入後於同一 transaction 落
 *   canonical／alias（手填 `USER_OVERRIDE`+`LOW`、catalog `METADATA`+`MEDIUM`，
 *   永不冒充 `OFFICIAL`）；`manualClassification` 在目標模型可唯一判定時落 `MANUAL`／`LOW`
 *   分類真列（含三關聯），多模型且無 manual 標記時不挑模型冒充（見 [persistModelMappings]）。
 *   commit 產出的 offering 一律可見（`hidden=false`）且未驗證（`verificationEvidenceId=null`）。
 * - 冪等重放：同 `workflowId` 已有 commit 列即回既有 receipt，不建第二個 provider；
 *   併發重放導致去重鍵衝突時，本 transaction 已整筆 rollback，再回讀既有 receipt。
 * - 只寫 credential metadata（`safeCredentialId` + label），絕不碰 vault Secrets。
 *   若 commit 拋出（拒絕 / 中斷 / 衝突），呼叫端已寫入 vault 的孤兒 secret ref
 *   由呼叫端自行清理，本 committer 不刪除 vault 內容。
 *
 * Receipt：`commitId = "commit-" + workflowId`，`providerId` 為新建 provider，
 * `committedAtMillis` 為去重鍵列的 `committedAt`（重放回傳同一時間）。
 */
class RoomCommitter(
    private val db: AtlasDatabase,
    private val clock: OnboardingClock = SystemOnboardingClock,
) : Committer {
    override suspend fun commit(snapshot: OnboardingSnapshot): CommitReceipt {
        require(snapshot.workflowId.isNotBlank()) { "workflowId must not be blank" }
        val approval = snapshot.reviewApproval
        if (approval == null || !approval.approved) {
            throw CommitRejectedException(listOf(OnboardingFailureReason.REVIEW_NOT_APPROVED))
        }
        val modelIds = commitModelIds(snapshot)
        if (modelIds.isEmpty()) {
            throw CommitRejectedException(listOf(OnboardingFailureReason.NO_MODELS_TO_TEST))
        }
        val plan = resolvePlan(snapshot, modelIds)
        try {
            return db.withTransaction {
                db.onboardingCommitDao().getByWorkflow(snapshot.workflowId)?.let { existing ->
                    return@withTransaction receiptOf(existing)
                }
                // 外來 custom ref 检查：未列入 snapshot.credentials 的 custom ref
                // 不可在 DB 已存在（避免他人 PAT／舊憑證被走私成 anchor）。
                // AdvancedAddService 只產生 fresh UUID，此檢查只擋錯誤呼叫端。
                val knownRefIds: Set<String> = snapshot.credentials.map { it.safeCredentialId }.toSet()
                for (field in snapshot.customAuthFields) {
                    val refId = field.safeCredentialId?.trim().orEmpty()
                    if (refId.isNotEmpty() && refId !in knownRefIds) {
                        require(db.credentialDao().getById(refId) == null) {
                            "custom auth ref conflict"
                        }
                    }
                }
                db.providerDao().insert(plan.provider)
                for (account in plan.accounts) {
                    db.accountDao().insert(account)
                }
                for (credential in plan.credentials) {
                    val existing = db.credentialDao().getById(credential.credentialId)
                    if (existing != null) {
                        // 同 vault ref 重用：保留既有 label 不覆寫；跨帳號綁定拒絕，
                        // 檢查置於 transaction 內，拒絕時整筆 rollback。
                        require(existing.accountId == credential.accountId) {
                            "Cross-account credential rejected"
                        }
                    } else {
                        db.credentialDao().insert(credential)
                    }
                }
                for (endpoint in plan.endpoints) {
                    db.endpointDao().insert(endpoint)
                }
                for (offering in plan.offerings) {
                    db.offeringDao().insert(offering)
                }
                // MODEL-001…003／BACKLOG F1：offerings 寫入後於同一 transaction 落
                // canonical／alias／手填分類真列（失敗即整筆 rollback，不留半套模型資料）。
                persistModelMappings(snapshot, plan.provider.providerId, plan.offerings)
                for (availability in plan.availabilities) {
                    db.availabilityDao().insert(availability)
                }
                db.providerConfigDao().insert(manualConfigOf(snapshot, plan.provider.providerId, clock.nowMillis()).toEntity())
                val now = clock.nowMillis()
                db.onboardingCommitDao().insert(
                    OnboardingCommitEntity(
                        workflowId = snapshot.workflowId,
                        providerId = plan.provider.providerId,
                        committedAt = now,
                    ),
                )
                CommitReceipt(
                    commitId = commitIdOf(snapshot.workflowId),
                    providerId = plan.provider.providerId,
                    committedAtMillis = now,
                )
            }
        } catch (e: SQLiteConstraintException) {
            db.onboardingCommitDao().getByWorkflow(snapshot.workflowId)?.let { return receiptOf(it) }
            throw e
        }
    }

    /** 至少一 manual / catalog 模型（去重、去空白）；suggested 僅為候選，不計入。 */
    private fun commitModelIds(snapshot: OnboardingSnapshot): List<String> {
        val manual = snapshot.manualModelIds
        val catalog = snapshot.catalog?.models.orEmpty().map { it.modelId }
        return (manual + catalog).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 純解析：把 snapshot 換算成待寫入列（UUID 新 id）。
     * URL 非法 / 缺 baseUrl / 缺 enabled endpoint / 空 credential id / role 衝突 /
     * 跨 origin endpoint 在此拋出，發生於 transaction 之前，不寫半筆。
     * `manualPricing` / `manualQuotaNote` / `group` 等無對應欄位者不在此物化（保留於
     * snapshot，見類別 KDoc），不假寫 snapshots。模型 canonical／alias／分類由
     * [persistModelMappings] 在 offerings 之後同 transaction 落庫。
     */
    private fun resolvePlan(snapshot: OnboardingSnapshot, modelIds: List<String>): CommitPlan {
        require(snapshot.credentials.none { it.safeCredentialId.isBlank() }) {
            "safeCredentialId must not be blank"
        }
        val providerId = UUID.randomUUID().toString()
        // 手填優先：override 勝於自動偵測（契約 §2.3）。
        val adapterName =
            (snapshot.adapterTypeOverride ?: snapshot.detection?.adapterType ?: AdapterType.UNKNOWN).name

        val configuredBase = snapshot.baseUrl?.trim()?.takeIf { it.isNotEmpty() }
        val enabledProfiles = snapshot.endpointProfiles.filter { it.enabled }
        if (snapshot.endpointProfiles.isNotEmpty()) {
            require(enabledProfiles.isNotEmpty()) { "at least one enabled endpoint required" }
        }
        // 同一 vault ref 不可分屬兩種 role（fail-closed，避免 PAT 誤進 inference）。
        val rolesByCredentialId: Map<String, Set<CredentialRole>> = snapshot.credentials
            .groupBy(keySelector = { it.safeCredentialId }, valueTransform = { it.role })
            .mapValues { it.value.toSet() }
        require(rolesByCredentialId.values.all { it.size <= 1 }) {
            "Credential role conflict"
        }

        val resolvedEndpoints: List<ResolvedEndpoint> = if (snapshot.endpointProfiles.isEmpty()) {
            val baseRaw = configuredBase ?: throw IllegalArgumentException("baseUrl required")
            val baseNormalized = EndpointPolicy.validate(baseRaw).toString()
            listOf(
                ResolvedEndpoint(
                    endpointId = UUID.randomUUID().toString(),
                    baseUrl = baseNormalized,
                    endpointUrl = EndpointPolicy.chatEndpoint(baseNormalized),
                    protocol = EndpointType.CHAT_COMPLETIONS.name,
                ),
            )
        } else {
            val baseRawForJoin = configuredBase
                ?: enabledProfiles.map { it.url.trim() }
                    .firstOrNull { it.isNotEmpty() && looksAbsolute(it) }
                ?: throw IllegalArgumentException("baseUrl required for relative endpoint")
            val baseNormalized = EndpointPolicy.validate(baseRawForJoin).toString()
            val baseOrigin = EndpointPolicy.origin(EndpointPolicy.validate(baseNormalized))
            enabledProfiles.map { ref ->
                resolveEnabledEndpoint(ref, baseNormalized, baseOrigin)
            }
        }

        val baseForName = resolvedEndpoints.first().baseUrl
        val providerName = snapshot.providerName?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultProviderName(baseForName, adapterName)

        // Checkin 手填優先（契約 §2.3）：手填非法即拋，探測非法則忽略。
        val probeCheckin = snapshot.pricingQuota?.checkinUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { EndpointPolicy.validate(it).toString() }.getOrNull() }
        val manualCheckin = snapshot.checkinUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { EndpointPolicy.validate(it).toString() }
        val resolvedCheckin = manualCheckin ?: probeCheckin
        val requiresCheckin = if (manualCheckin != null) {
            true
        } else {
            snapshot.pricingQuota?.requiresDailyCheckin ?: (probeCheckin != null)
        }

        val provider = ProviderEntity(
            providerId = providerId,
            name = providerName,
            officialHealth = snapshot.pricingQuota?.officialHealth?.name ?: HealthState.UNKNOWN.name,
            observedHealth = snapshot.pricingQuota?.observedHealth?.name ?: HealthState.UNKNOWN.name,
            checkinUrl = resolvedCheckin,
            requiresDailyCheckin = requiresCheckin,
        )

        val groups: Map<String, List<SafeCredentialRef>> = snapshot.credentials.groupBy { ref ->
            ref.accountRef?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        }
        val accountByGroup: Map<String, String> = if (groups.isEmpty()) {
            mapOf("" to UUID.randomUUID().toString())
        } else {
            groups.keys.associateWith { UUID.randomUUID().toString() }
        }
        val accounts = accountByGroup.values.map { accountId ->
            AccountEntity(accountId = accountId, providerId = providerId)
        }
        // P0-3 custom anchor 前檢：缺 ref 即 fail-closed，不產不可用 commit。
        // `safeCredentialId == null`（未綁 vault）或空白一律拒絕。
        snapshot.customAuthFields.forEach { field ->
            require(!field.safeCredentialId.isNullOrBlank()) {
                "custom auth ref required"
            }
        }
        val customIds: List<String> = snapshot.customAuthFields
            .map { it.safeCredentialId!!.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        // Custom id 不可與 ACCOUNT role 重疊（避免 PAT 誤進 inference anchor）。
        val accountIds: Set<String> = snapshot.credentials
            .filter { it.role == CredentialRole.ACCOUNT }
            .map { it.safeCredentialId }
            .toSet()
        require(customIds.none { it in accountIds }) {
            "Credential role conflict"
        }
        val anchorAccountId: String = accountByGroup.values.first()
        val customCredentials: List<CredentialEntity> = customIds
            .filter { id -> snapshot.credentials.none { it.safeCredentialId == id } }
            .map { id ->
                val field = snapshot.customAuthFields.first { it.safeCredentialId?.trim() == id }
                val fieldName = field.name.trim().takeIf { it.isNotEmpty() } ?: "custom"
                CredentialEntity(
                    credentialId = id,
                    accountId = anchorAccountId,
                    label = "自訂授權：" + field.location.name + " " + fieldName,
                )
            }
        val credentials = groups.flatMap { (group, groupRefs) ->
            val accountId = accountByGroup.getValue(group)
            groupRefs.distinctBy { it.safeCredentialId }.map { ref ->
                CredentialEntity(
                    credentialId = ref.safeCredentialId,
                    accountId = accountId,
                    label = ref.label.trim().takeIf { it.isNotEmpty() } ?: DEFAULT_CREDENTIAL_LABEL,
                )
            }
        } + customCredentials
        // 同一 vault ref 不可分屬兩個帳號（呼叫端錯誤）。
        val accountsByCredential: Map<String, Set<String>> = credentials.groupBy(
            keySelector = { it.credentialId },
            valueTransform = { it.accountId },
        ).mapValues { it.value.toSet() }
        require(accountsByCredential.values.all { it.size == 1 }) {
            "Cross-account credential rejected"
        }

        // PAT 隔離：只有 INFERENCE 才建 availability；ACCOUNT 僅保留 metadata。
        // P0-3：零 INFERENCE 但有 custom anchor 時，以 custom anchor 建 availability，
        // 使 API_KEY / CUSTOM_HEADER commit 產出可用 connection；有 INFERENCE 時沿用既有行為。
        val inferenceIds: Set<String> = snapshot.credentials
            .filter { it.role == CredentialRole.INFERENCE }
            .map { it.safeCredentialId }
            .toSet()
        val inferenceCredentials = credentials.filter { it.credentialId in inferenceIds }
        val customAnchors = credentials.filter { it.credentialId in customIds }
        val anchorCredentials: List<CredentialEntity> = if (inferenceCredentials.isNotEmpty()) {
            inferenceCredentials
        } else {
            customAnchors
        }

        val endpoints = resolvedEndpoints.map { resolved ->
            EndpointEntity(
                endpointId = resolved.endpointId,
                providerId = providerId,
                baseUrl = resolved.baseUrl,
                endpointUrl = resolved.endpointUrl,
                protocol = resolved.protocol,
            )
        }

        val manualSet = snapshot.manualModelIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val offerings = resolvedEndpoints.flatMap { resolved ->
            modelIds.map { modelId ->
                OfferingEntity(
                    id = UUID.randomUUID().toString(),
                    providerId = providerId,
                    endpointId = resolved.endpointId,
                    providerModelId = modelId,
                    manual = modelId in manualSet,
                    source = ONBOARDING_SOURCE,
                )
            }
        }
        // 匿名（零 INFERENCE 且零 custom anchor）時 availability 為空，不合成假 key；
        // 推理 connection 無列（NONE provider 由匿名 UNION 提供）。custom-only 由 anchor 提供列。
        val availabilities = offerings.flatMap { offering ->
            anchorCredentials.map { credential ->
                CredentialAvailabilityEntity(
                    id = UUID.randomUUID().toString(),
                    offeringId = offering.id,
                    accountId = credential.accountId,
                    credentialId = credential.credentialId,
                    priceState = UNKNOWN_STATE,
                    quotaState = UNKNOWN_STATE,
                )
            }
        }
        return CommitPlan(
            provider = provider,
            accounts = accounts,
            credentials = credentials,
            endpoints = endpoints,
            offerings = offerings,
            availabilities = availabilities,
        )
    }

    /**
     * 手填來源 metadata：commit 同 transaction 與產品資料一起寫入，不只靠 workflow snapshot。
     * `authMode` 無 override 時按 INFERENCE 憑證有無推導（`BEARER`／`NONE`）；手填一律 LOW，
     * 不展開成官方 `price_snapshots`。只含 safe refs，編碼前過 secret-guard。
     */
    private fun manualConfigOf(snapshot: OnboardingSnapshot, providerId: String, now: Long): ProviderManualConfig {
        val detected = snapshot.detection?.adapterType
        return ProviderManualConfig(
            providerId = providerId,
            workflowId = snapshot.workflowId,
            // 無 override（QuickAdd／舊測試快照）時按憑證推導：有 INFERENCE 即 BEARER；
            // 僅 ACCOUNT（純 PAT）記 PAT_ACCOUNT（不進匿名 UNION，不可推理）；全空才記 NONE。
            authMode = snapshot.authModeOverride
                ?: when {
                    snapshot.credentials.any { it.role == CredentialRole.INFERENCE } ->
                        dev.modelatlas.core.onboarding.AuthMode.BEARER
                    snapshot.credentials.isNotEmpty() ->
                        dev.modelatlas.core.onboarding.AuthMode.PAT_ACCOUNT
                    else -> dev.modelatlas.core.onboarding.AuthMode.NONE
                },
            authModeOverride = snapshot.authModeOverride,
            websiteUrl = snapshot.websiteUrl,
            group = snapshot.group,
            tags = snapshot.tags,
            notes = snapshot.notes,
            manualPricing = snapshot.manualPricing,
            manualQuotaState = snapshot.manualQuotaState,
            manualQuotaNote = snapshot.manualQuotaNote,
            manualClassification = snapshot.manualClassification,
            adapterOverride = snapshot.adapterTypeOverride,
            detectedAdapter = detected,
            resolvedAdapter = snapshot.adapterTypeOverride ?: detected ?: AdapterType.UNKNOWN,
            credentials = snapshot.credentials,
            customAuthFields = snapshot.customAuthFields,
            anonymousConfirmed = snapshot.anonymousConfirmed,
            updatedAtMillis = now,
        )
    }

    /**
     * MODEL-001／002／003＋BACKLOG F1：commit 同一 transaction 內把 offerings 的原始
     * provider model ID 落成 canonical／alias 真列（手填分類有明確目標時另落 MANUAL 分類）。
     *
     * - canonical：[CanonicalStore.saveCanonical] 以正規化名去重，同一 model 不因大小寫／
     *   分隔符差異重複建立；displayName 用原始 providerModelId，不臆測顯示名。
     * - alias：鍵為 `(providerId, providerModelId)`，多 endpoint 的同一 model 去重後只寫一次
     *   （同鍵重寫即修正，冪等）。來源分槽不互冒（X-005）：手填模型 `manual=true` →
     *   `USER_OVERRIDE` 恆 `LOW`（[CanonicalStore.saveAlias] 強制）；catalog 模型 →
     *   `METADATA`／`MEDIUM`，**不寫 `OFFICIAL`**：commit 未經官方映射驗證。
     *   `verified=false`：不把未驗證映射標成已驗證。
     * - 分類：`manualClassification` 為表單層級單一輸入（合約 §3 欄位表），快照未指明
     *   屬於哪個 modelId，故只在目標可唯一判定時落 `MANUAL`／`LOW` 真列
     *   （有 manual 模型 → 這些模型；無 manual 模型且模型唯一 → 該模型）；多模型且
     *   無 manual 標記時不挑一個模型冒充，輸入仍保留於 `provider_configurations.snapshotJson`。
     * - 不碰 vault Secrets：只寫 refs、enum 名與時間戳。
     */
    private suspend fun persistModelMappings(
        snapshot: OnboardingSnapshot,
        providerId: String,
        offerings: List<OfferingEntity>,
    ) {
        if (offerings.isEmpty()) return
        val now = clock.nowMillis()
        val store = CanonicalStore(db)
        val manualSet = snapshot.manualModelIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val modelIds = offerings.map { it.providerModelId }.distinct()
        val manualIds = modelIds.filter { it in manualSet }
        val classifiedIds = when {
            manualIds.isNotEmpty() -> manualIds
            modelIds.size == 1 -> modelIds
            else -> emptyList()
        }
        for (modelId in modelIds) {
            val canonical = store.saveCanonical(
                rawModelId = modelId,
                displayName = modelId,
                nowMillis = now,
            )
            val manual = modelId in manualSet
            store.saveAlias(
                providerId = providerId,
                providerModelId = modelId,
                canonicalModelId = canonical.canonicalModelId,
                source = if (manual) MappingSource.USER_OVERRIDE.name else MappingSource.METADATA.name,
                confidence = if (manual) MappingConfidence.LOW.name else MappingConfidence.MEDIUM.name,
                verified = false,
                observedAt = now,
                version = 1,
            )
            if (modelId in classifiedIds) {
                store.classificationFromManualSnapshot(
                    canonicalModelId = canonical.canonicalModelId,
                    input = snapshot.manualClassification,
                    observedAt = now,
                )
            }
        }
    }

    private fun receiptOf(row: OnboardingCommitEntity): CommitReceipt = CommitReceipt(
        commitId = commitIdOf(row.workflowId),
        providerId = row.providerId,
        committedAtMillis = row.committedAt,
    )

    private fun commitIdOf(workflowId: String): String = "commit-$workflowId"

    private fun defaultProviderName(baseNormalized: String, adapterName: String): String {
        val host = runCatching { EndpointPolicy.validate(baseNormalized).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "未命名供應商"
        return "$host ($adapterName)"
    }

    private fun looksAbsolute(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /**
     * 單一 enabled endpoint 解析（transaction 之前呼叫，拋出即不寫半筆）。
     * 相對路徑以 base join 後嚴格檢驗；絕對 URL 直接嚴格檢驗；
     * origin 與 base 不一致即拋 `Cross-origin endpoint rejected`（不攜帶 URL）。
     */
    private fun resolveEnabledEndpoint(
        ref: EndpointRef,
        baseNormalized: String,
        baseOrigin: String,
    ): ResolvedEndpoint {
        val raw = ref.url.trim()
        require(raw.isNotEmpty()) { "endpoint url must not be blank" }
        if (raw.contains("://") && !looksAbsolute(raw)) {
            throw IllegalArgumentException("endpoint url invalid")
        }
        val absolute = if (looksAbsolute(raw)) {
            EndpointPolicy.validate(raw).toString()
        } else {
            EndpointPolicy.validate(baseNormalized.trimEnd('/') + '/' + raw.trimStart('/')).toString()
        }
        val endpointOrigin = EndpointPolicy.origin(EndpointPolicy.validate(absolute))
        require(endpointOrigin == baseOrigin) { "Cross-origin endpoint rejected" }
        return ResolvedEndpoint(
            endpointId = UUID.randomUUID().toString(),
            baseUrl = baseNormalized,
            endpointUrl = absolute,
            protocol = normalizeProtocol(ref),
        )
    }

    /** `protocol` 為 `HTTPS` 或空白時退回 `endpointType`，避免寫入非推理協議字串。 */
    private fun normalizeProtocol(ref: EndpointRef): String {
        val raw = ref.protocol.trim()
        if (raw.isEmpty() || raw.equals("HTTPS", ignoreCase = true)) {
            return ref.endpointType.name
        }
        return raw
    }

    private data class ResolvedEndpoint(
        val endpointId: String,
        val baseUrl: String,
        val endpointUrl: String,
        val protocol: String,
    )

    private data class CommitPlan(
        val provider: ProviderEntity,
        val accounts: List<AccountEntity>,
        val credentials: List<CredentialEntity>,
        val endpoints: List<EndpointEntity>,
        val offerings: List<OfferingEntity>,
        val availabilities: List<CredentialAvailabilityEntity>,
    )

    private companion object {
        const val ONBOARDING_SOURCE = "ONBOARDING"
        const val UNKNOWN_STATE = "UNKNOWN"
        const val DEFAULT_CREDENTIAL_LABEL = "主要憑證"
    }
}
