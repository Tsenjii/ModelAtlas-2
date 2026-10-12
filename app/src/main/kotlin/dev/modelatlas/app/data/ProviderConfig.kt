package dev.modelatlas.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.onboarding.AdapterType
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CustomAuthFieldRef
import dev.modelatlas.core.onboarding.ManualClassificationInput
import dev.modelatlas.core.onboarding.ManualPricingInput
import dev.modelatlas.core.onboarding.SafeCredentialRef
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * P3 手填來源 metadata 正式落地（`provider_configurations`，Room v4）。
 *
 * - 正式的手填來源，非未完成 workflow 暫存：commit 同 transaction 與
 *   Provider / Account / Credential / Endpoint / Offering / Availability 一起寫入，
 *   不只靠 `onboarding_snapshots`（後者仍保留作可中斷恢復）。
 * - 全份保留：`websiteUrl / group / tags / notes / manualPricing（含幣別單位生效時間）/`
 *   `manualQuotaState + manualQuotaNote / manualClassification 四維 / adapter /`
 *   `credentials（含角色）/ customAuthFields（含 safeCredentialId，不含值）/ anonymousConfirmed`。
 * - 手填一律 `LOW`，不寫成官方價 / `FREE`：`manualPricing` 保留原 scope（model/group/endpoint/operation），
 *   不展開成 `price_snapshots` 官方快照；availability `priceState/quotaState` 仍 `UNKNOWN`。
 *   UI 由主 session 讀 typed API 呈現 LOW 原值。
 * - 只存 safe refs：原始 Key / PAT 只走 vault，`snapshotJson` 經既有
 *   `ensureSnapshotJsonHasNoSecretRefs` guard，疑似含 secret 即拒寫。
 */
@Serializable
data class ProviderManualConfig(
    val providerId: String,
    val workflowId: String,
    val authMode: AuthMode,
    val authModeOverride: AuthMode? = null,
    val websiteUrl: String? = null,
    val group: String? = null,
    val tags: List<String> = emptyList(),
    val notes: String? = null,
    val manualPricing: List<ManualPricingInput> = emptyList(),
    val manualQuotaState: QuotaState? = null,
    val manualQuotaNote: String? = null,
    val manualClassification: ManualClassificationInput? = null,
    val adapterOverride: AdapterType? = null,
    val detectedAdapter: AdapterType? = null,
    val resolvedAdapter: AdapterType = AdapterType.UNKNOWN,
    val credentials: List<SafeCredentialRef> = emptyList(),
    val customAuthFields: List<CustomAuthFieldRef> = emptyList(),
    val anonymousConfirmed: Boolean = false,
    val updatedAtMillis: Long = 0L,
)

/**
 * Provider 配置列：`providerId` PK + FK 至 `providers`（RESTRICT），`workflowId` 唯一，
 * `authMode` 為可查詢欄（`AuthMode.name`），`snapshotJson` 為全份手填 metadata。
 */
@Entity(
    tableName = "provider_configurations",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["providerId"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["workflowId"],
            unique = true,
            name = "index_provider_configurations_workflowId",
        ),
    ],
)
data class ProviderConfigurationEntity(
    @PrimaryKey val providerId: String,
    val workflowId: String,
    val authMode: String,
    val snapshotJson: String,
)

@Dao
interface ProviderConfigDao {
    /** 去重鍵必須 `ABORT`：重複 providerId / workflowId 代表併發重放，整筆 transaction rollback。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProviderConfigurationEntity)

    @Query("SELECT * FROM provider_configurations WHERE providerId = :providerId")
    suspend fun getByProvider(providerId: String): ProviderConfigurationEntity?

    @Query("SELECT * FROM provider_configurations WHERE workflowId = :workflowId")
    suspend fun getByWorkflow(workflowId: String): ProviderConfigurationEntity?

    @Query("SELECT * FROM provider_configurations WHERE providerId = :providerId")
    fun observeByProvider(providerId: String): Flow<ProviderConfigurationEntity?>

    /**
     * 測試／修復用 config 覆寫（legacy 髒資料清理）。寫入前呼叫端必須先過
     * secret-guard（見 [ProviderManualConfig.toEntity]），原始值永不進 JSON。
     */
    @Query("UPDATE provider_configurations SET snapshotJson = :snapshotJson WHERE providerId = :providerId")
    suspend fun updateSnapshotJson(providerId: String, snapshotJson: String): Int
}

/** 編碼手填 config 為 `snapshotJson`（呼叫端寫入前必經 guard）。 */
internal fun ProviderManualConfig.encodeToSnapshotJson(): String =
    OnboardingJson.encodeToString(ProviderManualConfig.serializer(), this)

/** 解析 `snapshotJson` 為 typed config（讀取路徑，未知欄位忽略）。 */
internal fun decodeProviderManualConfig(snapshotJson: String): ProviderManualConfig =
    OnboardingJson.decodeFromString(ProviderManualConfig.serializer(), snapshotJson)

/**
 * 手填 config 落庫：編碼後先過安全 guard，疑似含 secret 即拒寫。
 * 訊息固定、不攜帶 JSON，避免洩漏疑似 secret。
 */
internal fun ProviderManualConfig.toEntity(): ProviderConfigurationEntity {
    val json = encodeToSnapshotJson()
    ensureSnapshotJsonHasNoSecretRefs(json)
    require(providerId.isNotBlank() && workflowId.isNotBlank()) {
        "providerId/workflowId must not be blank"
    }
    require(authMode.name.isNotBlank()) { "authMode must not be blank" }
    return ProviderConfigurationEntity(
        providerId = providerId,
        workflowId = workflowId,
        authMode = authMode.name,
        snapshotJson = json,
    )
}

/**
 * Config 讀取：欄位與 JSON 歸屬必須一致（fail-closed）。
 * `providerId / workflowId / authMode` 欄位與 JSON 內值不一致即拋，不靜默回傳錯配 config。
 */
internal fun ProviderConfigurationEntity.toManualConfig(): ProviderManualConfig {
    val parsed = decodeProviderManualConfig(snapshotJson)
    require(parsed.providerId == providerId) { "Provider config provider mismatch" }
    require(parsed.workflowId == workflowId) { "Provider config workflow mismatch" }
    require(parsed.authMode.name == authMode) { "Provider config authMode mismatch" }
    return parsed
}

/**
 * 自訂授權清理清單：該 config 下全部 custom field `safeCredentialId`（去重、去空白），
 * 排除 PAT（`credentials` 中 `role=ACCOUNT` 的 id），禁把 PAT 列入。
 * 只回傳 refs，不碰 vault（vault 清理由呼叫端負責）。
 */
internal fun customAuthCleanupRefs(config: ProviderManualConfig): List<String> {
    val patIds: Set<String> = config.credentials
        .filter { it.role == dev.modelatlas.core.onboarding.CredentialRole.ACCOUNT }
        .map { it.safeCredentialId.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    return config.customAuthFields
        .mapNotNull { it.safeCredentialId?.trim()?.takeIf { id -> id.isNotEmpty() } }
        .distinct()
        .filter { it !in patIds }
}
