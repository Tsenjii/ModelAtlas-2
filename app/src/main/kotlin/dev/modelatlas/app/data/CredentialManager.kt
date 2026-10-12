package dev.modelatlas.app.data

import androidx.room.withTransaction

/**
 * §6 auth modes 白名單。未知字串不通過（fail-closed），不猜、不漏。
 * secret value 永不經此層；本檔只處理 metadata 與非敏感前綴。
 */
object CredentialAuthModes {
    const val NONE = "NONE"
    const val API_KEY = "API_KEY"
    const val BEARER = "BEARER"
    const val CUSTOM_HEADER = "CUSTOM_HEADER"
    const val PAT_ACCOUNT = "PAT_ACCOUNT"
    val ALL: Set<String> = setOf(NONE, API_KEY, BEARER, CUSTOM_HEADER, PAT_ACCOUNT)
}

/**
 * PROV-006 credential 審計視圖：只含 metadata 與非敏感欄位。
 * `headerPrefix` 為顯示用前綴（如 "Bearer "）；`isSecret=true` 表示真正 secret 在 vault，
 * 本視圖**永不**含 secret 原始值。時間缺席為 null（Unknown≠0）。
 */
data class CredentialAudit(
    val credentialId: String,
    val accountId: String,
    val providerId: String?,
    val label: String,
    val note: String?,
    val group: String?,
    val priority: Int,
    val enabled: Boolean,
    val authMode: String,
    val headerName: String?,
    val headerPrefix: String?,
    val isSecret: Boolean,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
)

/**
 * PROV-006／PROV-009 credential 管理層（Room v9）。
 *
 * - search／filter／batch enable-disable 全部走 [CredentialDao]（不在記憶體過濾）。
 * - custom credential 審計需要的 `headerPrefix`／`isSecret` 由 v9 新欄承載；
 *   secret value 仍在 privileged vault，本層不接收、不保存、不記錄任何 raw secret。
 * - 未知不猜：authMode 白名單；未指定 note／group 即 null，不自造。
 * - 一次批次寫入受影響列數即回報，不遍歷重寫。
 */
class CredentialManager(private val db: AtlasDatabase) {

    /**
     * search／filter：null 維度不篩。query 為 label／note 子字串（大小寫由 SQLite LIKE 預設，不敏感）。
     * 只回 [CredentialAudit]（含 headerPrefix／isSecret），永不回 secret。
     */
    suspend fun search(
        providerId: String? = null,
        accountId: String? = null,
        enabled: Boolean? = null,
        group: String? = null,
        authMode: String? = null,
        query: String? = null,
    ): List<CredentialAudit> {
        require(authMode == null || authMode in CredentialAuthModes.ALL) { "Unknown auth mode" }
        val provider = db.providerDao()
        val trimmed = query?.trim()?.takeIf { it.isNotEmpty() }
        return db.credentialDao()
            .search(providerId, accountId, enabled, group, authMode, trimmed)
            .map { toAudit(it, provider.getById(accountProviderId(it.accountId))?.providerId) }
    }

    /** 批次 enable／disable：回受影響列數；空清單即 0（不拋、不改任何列）。 */
    suspend fun setEnabledBatch(credentialIds: List<String>, enabled: Boolean): Int {
        if (credentialIds.isEmpty()) return 0
        return db.credentialDao().setEnabledBatch(credentialIds.distinct(), enabled)
    }

    /** 單筆啟停；回是否實際更新。 */
    suspend fun setEnabled(credentialId: String, enabled: Boolean): Boolean {
        require(credentialId.isNotBlank()) { "credentialId must not be blank" }
        return db.credentialDao().setEnabled(credentialId, enabled) > 0
    }

    /** 審計：缺席回 null（不猜）；只回非敏感 metadata。 */
    suspend fun audit(credentialId: String): CredentialAudit? {
        val entity = db.credentialDao().getById(credentialId) ?: return null
        val providerId = db.providerDao().getById(accountProviderId(entity.accountId))?.providerId
        return toAudit(entity, providerId)
    }

    /**
     * 註冊 CUSTOM_HEADER 憑證 metadata。authMode 固定 CUSTOM_HEADER；
     * 必須提供非空白 headerName 與非 null headerPrefix（顯示前綴，非 secret 本體）。
     * `isSecret` 恆 true——真正 secret value 由呼叫端寫入 vault，不經本函式。
     * 回傳審計視圖；account 不存在即拒絕（FK／語意一致）。
     */
    suspend fun registerCustomHeader(
        credentialId: String,
        accountId: String,
        label: String,
        headerName: String,
        headerPrefix: String,
        note: String? = null,
        group: String? = null,
        priority: Int = 0,
        enabled: Boolean = true,
    ): CredentialAudit {
        require(credentialId.isNotBlank() && accountId.isNotBlank() && label.isNotBlank()) {
            "credential metadata must not be blank"
        }
        require(headerName.isNotBlank()) { "custom header name must not be blank" }
        require(headerPrefix.isNotBlank()) { "custom header prefix must not be blank" }
        val providerId = accountProviderId(accountId)
        require(db.accountDao().getById(accountId) != null) { "account not found" }
        db.withTransaction {
            db.credentialDao().insert(
                CredentialEntity(
                    credentialId = credentialId,
                    accountId = accountId,
                    label = label,
                    enabled = enabled,
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    group = group?.trim()?.takeIf { it.isNotEmpty() },
                    priority = priority,
                    authMode = CredentialAuthModes.CUSTOM_HEADER,
                    headerName = headerName.trim(),
                    headerPrefix = headerPrefix,
                    isSecret = true,
                ),
            )
        }
        return audit(credentialId) ?: error("credential write did not persist")
    }

    private suspend fun accountProviderId(accountId: String): String =
        db.accountDao().getById(accountId)?.providerId ?: ""

    private fun toAudit(entity: CredentialEntity, providerId: String?): CredentialAudit = CredentialAudit(
        credentialId = entity.credentialId,
        accountId = entity.accountId,
        providerId = providerId,
        label = entity.label,
        note = entity.note,
        group = entity.group,
        priority = entity.priority,
        enabled = entity.enabled,
        authMode = entity.authMode,
        headerName = entity.headerName,
        headerPrefix = entity.headerPrefix,
        isSecret = entity.isSecret,
        lastSuccessAt = entity.lastSuccessAt,
        lastFailureAt = entity.lastFailureAt,
    )
}
