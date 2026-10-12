package dev.modelatlas.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * X-015 safe backup-export（BACKLOG 群組 C）。
 *
 * - 匯出範圍：provider definitions／account／endpoint profiles／canonical models／
 *   aliases／classifications／notes（含 tags）／checkin 設定／notification settings／
 *   app settings／research knowledge（P10 recipes，optional）。
 * - secrets 一律只出 safe refs（[BackupCredentialRef.safeCredentialId]），禁明文：
 *   本檔 DTO 刻意沒有任何 secret value 欄位；export／import 皆經 secret-guard，
 *   疑似含 secret 即 fail-closed。
 * - `android:allowBackup="false"` 必須維持（OS 全量備份會連 vault 一起捲走）；
 *   本匯出是使用者可觸發的 JSON（建議 UI 用 `ACTION_CREATE_DOCUMENT` 分享），
 *   與 OS 自動備份無關。
 * - Unknown 不等於 0／Free：未知一律 null（不補零、不猜）；manual／official／
 *   observed／external 不互相冒充（manual pricing provenance 恆為 `USER`）。
 * - PricingScope 不縮水：[BackupManualPrice] 保留七維
 *   provider／account／safeCredential／model／group／endpoint／operation。
 */
const val BACKUP_SCHEMA_VERSION: Int = 1

/** Backup 編解碼用 JSON：null 省略（P10 未落地時 recipes 欄省略，不寫空字串冒充）。 */
internal val BackupJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    prettyPrint = true
}

@Serializable
data class BackupProvider(
    val providerId: String,
    val name: String,
    val websiteUrl: String? = null,
    val group: String? = null,
    val tags: List<String> = emptyList(),
    val authMode: String = "BEARER",
    val checkinUrl: String? = null,
    val requiresDailyCheckin: Boolean = false,
)

@Serializable
data class BackupAccount(
    val accountId: String,
    val providerId: String,
)

@Serializable
data class BackupEndpointProfile(
    val endpointId: String,
    val providerId: String,
    val baseUrl: String,
    val endpointUrl: String,
    val protocol: String = "CHAT_COMPLETIONS",
)

@Serializable
data class BackupCanonicalModel(
    val canonicalModelId: String,
    val normalizedName: String,
    val displayName: String? = null,
)

@Serializable
data class BackupAlias(
    val aliasId: String,
    val providerId: String,
    val providerModelId: String,
    val canonicalModelId: String,
    val source: String,
    val confidence: String = "LOW",
    val verified: Boolean = false,
    val evidenceId: String? = null,
)

@Serializable
data class BackupClassification(
    val id: String,
    val canonicalModelId: String,
    val source: String,
    val confidence: String = "LOW",
    val primaryCategory: String? = null,
    val capabilities: List<String> = emptyList(),
    val modalities: List<String> = emptyList(),
    val endpointTypes: List<String> = emptyList(),
)

/**
 * 憑證佔位：只出 safe ref，匯入後需使用者重新輸入 secret。
 * 刻意無任何 value／ciphertext／iv 欄位。
 */
@Serializable
data class BackupCredentialRef(
    val safeCredentialId: String,
    val label: String,
    val role: String = "INFERENCE",
    val accountId: String? = null,
)

/**
 * 手填價格：provenance 恆為 `USER`，永不升格 official。
 * 七維 scope 全保留（null＝未知，不補零）。
 */
@Serializable
data class BackupManualPrice(
    val priceId: String,
    val providerId: String,
    val accountId: String? = null,
    val safeCredentialId: String? = null,
    val modelId: String? = null,
    val group: String? = null,
    val endpointId: String? = null,
    val operation: String? = null,
    val decimalAmount: String,
    val currency: String,
    val perUnit: String = "INPUT_TOKEN",
    val provenance: String = "USER",
    val evidenceId: String? = null,
    val observedAtMillis: Long = 0L,
    val note: String? = null,
)

@Serializable
data class BackupCheckinConfig(
    val providerId: String,
    val accountId: String = "",
    val date: String? = null,
    val checkinUrl: String? = null,
    val requiresDailyCheckin: Boolean = false,
    val completedAtMillis: Long? = null,
)

/**
 * 通知設定：`enabled = null`＝未決（X-013 護欄），匯出／匯入必須原樣保留，
 * 不得以 `?: false` 塌縮。
 */
@Serializable
data class BackupNotificationSetting(
    val ruleId: String,
    val enabled: Boolean? = null,
    val threshold: String? = null,
    val scope: String? = null,
)

@Serializable
data class BackupNote(
    val noteId: String,
    val providerId: String? = null,
    val canonicalModelId: String? = null,
    val accountId: String? = null,
    val text: String,
    val updatedAtMillis: Long = 0L,
)

@Serializable
data class BackupAppSettings(
    val rawSampleWindowDays: Int? = null,
    val deletesRawEvidence: Boolean = false,
)

@Serializable
data class BackupRecipe(
    val recipeId: String,
    val version: Int = 1,
    val description: String? = null,
    val source: String = "USER",
)

/**
 * P10 recipes／research knowledge 槽（optional）。
 * P10 未落地時整個物件為 null 並在 JSON 省略，不寫空字串冒充「有 recipes」。
 */
@Serializable
data class BackupResearchKnowledge(
    val strategyProfileId: String? = null,
    val summary: String? = null,
    val recipes: List<BackupRecipe>? = null,
)

@Serializable
data class AtlasBackup(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAtMillis: Long = 0L,
    val providers: List<BackupProvider> = emptyList(),
    val accounts: List<BackupAccount> = emptyList(),
    val endpointProfiles: List<BackupEndpointProfile> = emptyList(),
    val canonicalModels: List<BackupCanonicalModel> = emptyList(),
    val aliases: List<BackupAlias> = emptyList(),
    val classifications: List<BackupClassification> = emptyList(),
    val credentialRefs: List<BackupCredentialRef> = emptyList(),
    val manualPricing: List<BackupManualPrice> = emptyList(),
    val checkinConfigs: List<BackupCheckinConfig> = emptyList(),
    val notificationSettings: List<BackupNotificationSetting> = emptyList(),
    val notes: List<BackupNote> = emptyList(),
    val appSettings: BackupAppSettings? = null,
    val researchKnowledge: BackupResearchKnowledge? = null,
)

class BackupSecretRefusedException :
    IllegalStateException("backup 疑似含有 secret 字樣，拒絕匯出／匯入")

private val BACKUP_BARE_TOKENS: List<String> = listOf(
    "credential_ciphertexts",
    "ciphertext",
    "password",
    "passwd",
    "private_key",
    "client_secret",
    "access_token",
    "refresh_token",
    "secrettransient",
    "valuetransient",
    "rawkey",
    "rawsecret",
)

private val BACKUP_KEY_PATTERN: Regex =
    Regex(""""[^"]*(api[_-]?key|authorization|bearer|token)[^"]*"\s*:""", RegexOption.IGNORE_CASE)

private val BACKUP_VALUE_PATTERN: Regex = Regex("""sk-[A-Za-z0-9_-]{8,}""")

internal fun ensureBackupJsonHasNoSecrets(backupJsonString: String) {
    val lowered = backupJsonString.lowercase()
    for (token in BACKUP_BARE_TOKENS) {
        if (lowered.contains(token)) throw BackupSecretRefusedException()
    }
    // vault iv 只擋 key 位置（"iv":），避免誤傷英文單字。
    if (lowered.contains("\"iv\"")) throw BackupSecretRefusedException()
    if (BACKUP_KEY_PATTERN.containsMatchIn(backupJsonString)) throw BackupSecretRefusedException()
    if (BACKUP_VALUE_PATTERN.containsMatchIn(backupJsonString)) throw BackupSecretRefusedException()
}

internal fun validateAtlasBackup(backup: AtlasBackup) {
    require(backup.schemaVersion == BACKUP_SCHEMA_VERSION) {
        "unsupported backup schemaVersion: ${backup.schemaVersion}"
    }
    for (p in backup.providers) {
        require(p.providerId.isNotBlank()) { "providerId must not be blank" }
        require(p.name.isNotBlank()) { "provider name must not be blank" }
    }
    for (a in backup.accounts) {
        require(a.accountId.isNotBlank()) { "accountId must not be blank" }
        require(a.providerId.isNotBlank()) { "account providerId must not be blank" }
    }
    for (e in backup.endpointProfiles) {
        require(e.endpointId.isNotBlank()) { "endpointId must not be blank" }
        require(e.providerId.isNotBlank()) { "endpoint providerId must not be blank" }
        require(e.baseUrl.isNotBlank() && e.endpointUrl.isNotBlank()) { "endpoint urls must not be blank" }
    }
    for (c in backup.credentialRefs) {
        require(c.safeCredentialId.isNotBlank()) { "safeCredentialId must not be blank" }
    }
    for (m in backup.manualPricing) {
        require(m.priceId.isNotBlank()) { "priceId must not be blank" }
        require(m.providerId.isNotBlank()) { "manual price providerId must not be blank" }
        require(m.decimalAmount.isNotBlank()) { "manual price amount must not be blank" }
        require(m.currency.isNotBlank()) { "manual price currency must not be blank" }
        require(m.provenance == "USER") { "manual pricing must stay USER, never OFFICIAL" }
    }
    for (n in backup.notificationSettings) {
        require(n.ruleId.isNotBlank()) { "ruleId must not be blank" }
    }
    for (n in backup.notes) {
        require(n.noteId.isNotBlank()) { "noteId must not be blank" }
        require(n.text.isNotBlank()) { "note text must not be blank" }
    }
}

/**
 * Safe backup 純函式出入口（X-015 Scope：`AtlasBackup` → JSON）。
 *
 * UI 層建議用 `ACTION_CREATE_DOCUMENT` 分享此處 [export] 產生的字串；
 * 本物件不碰 vault、不讀 telemetry、不寫 DB。
 */
object SafeBackup {
    fun export(backup: AtlasBackup): String {
        validateAtlasBackup(backup)
        val json = BackupJson.encodeToString(AtlasBackup.serializer(), backup)
        ensureBackupJsonHasNoSecrets(json)
        return json
    }

    fun parse(jsonString: String): AtlasBackup {
        require(jsonString.isNotBlank()) { "backup json must not be blank" }
        ensureBackupJsonHasNoSecrets(jsonString)
        val parsed = BackupJson.decodeFromString(AtlasBackup.serializer(), jsonString)
        validateAtlasBackup(parsed)
        return parsed
    }
}

/** BACKLOG X-015 命名別名：`BackupExporter` 與 [SafeBackup] 同義（純函式 AtlasBackup → JSON）。 */
object BackupExporter {
    fun export(backup: AtlasBackup): String = SafeBackup.export(backup)
    fun parse(jsonString: String): AtlasBackup = SafeBackup.parse(jsonString)
}
