package dev.modelatlas.app.data

import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.onboarding.CredentialRole
import kotlinx.coroutines.CancellationException

/**
 * CR-001 A5 共用 credential-selection foundation（CR-001／X-003·004·006／
 * NAPI-002·007／NINT-004·005A；PRODUCT_SPEC §6／§10／§12／§18；
 * Contract §23.3；CORRECTNESS_REPAIR_AUDIT A1／A5）。
 *
 * 只做 provider／config metadata 與 enabled DB ownership 解析，回傳 safe refs；
 * 零網路請求、零寫入（不 fetch、不 persist、不開 transaction）。
 * `CancellationException` 永遠重拋，不吞成 fail-closed。
 *
 * 鐵律（違反即 `IllegalArgumentException` 靜態訊息，絕不夾帶 config／raw／URL）：
 * - PAT＝純 ACCOUNT 角色，key＝純 INFERENCE 角色（exact `setOf(...)` 比對，
 *   dual-role／混合一律視為無效，不借用）。
 * - PAT 選擇逐候選掃描至第一個「有效且屬 resolved SAME account」的 PAT；
 *   disabled／stale（DB 無列）／dual-role／foreign／control／`sk-`／空白超長
 *   候選只跳過、不遮蔽後續同帳號合法 PAT；永不借他帳號 PAT。
 * - 無 PAT（config 缺 ACCOUNT refs）時 sync 一律失敗；refs 全無效亦失敗，
 *   絕不靜默降級匿名、絕不借 INFERENCE。
 * - Legacy fallback（account／key 皆省略）僅在該 provider 恰有一帳號時允許；
 *   多帳號歧義一律 fail-closed，永不取首帳號。
 * - Explicit account（non-null）必須存在且屬該 provider；explicit key
 *   （non-null）必須嚴格 safe-ref＋純 INFERENCE＋enabled＋同 provider／account，
 *   account 省略時由 exact key 反推 owner。
 * - 缺 key 時沿用 legacy「同帳號唯一 valid key」相容：零把→null key；
 *   多把 valid→歧義拒絕（不任意取首把）；其他帳號的 key 永不借用。
 * - Stored／perf 身份沿用既有 provider-reported key；此處只定 ACCOUNT
 *   provenance，不改任何 scope／status／summary／cert 語意。
 */
data class NewApiResolvedSyncScope(
    val accountId: String,
    val pat: AccountPatRef,
    val key: InferenceKeyRef?,
)

/** Perf 專用 ACCOUNT provenance（匿名即 null，由呼叫端映射為既有 `PerfProvenance`）。 */
data class NewApiAccountPatSelection(
    val accountId: String,
    val pat: AccountPatRef,
)

class NewApiCredentialSelector(private val db: AtlasDatabase) {

    /**
     * Sync 精確 scope 解析（`AtlasRepository.syncNewApi` 唯一 selection 來源）。
     *
     * @param providerId provider 主鍵，不可空白。
     * @param accountId 請求帳號（null 表 legacy／exact-key 反推；多帳號歧義即拋錯）。
     * @param credentialId 請求 inference key 的 safe ref（null 表同帳號唯一-key fallback）。
     */
    suspend fun resolveSync(
        providerId: String,
        accountId: String? = null,
        credentialId: String? = null,
    ): NewApiResolvedSyncScope {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        val provider = db.providerDao().getById(providerId)
            ?: throw IllegalArgumentException("Provider not found")
        check(provider.providerId == providerId) { "Provider mismatch" }
        if (accountId != null) {
            requireRequestedAccount(providerId, accountId)
        }
        val config = loadConfig(providerId)
        val resolvedAccountId: String
        var resolvedKey: InferenceKeyRef? = null
        if (credentialId != null) {
            val validated = requireOwnedInferenceKey(providerId, config, credentialId)
            if (accountId != null) {
                require(validated.accountId == accountId) { "Credential ownership mismatch" }
                resolvedAccountId = accountId
            } else {
                resolvedAccountId = validated.accountId
            }
            resolvedKey = validated.ref
        } else if (accountId != null) {
            resolvedAccountId = accountId
            val candidates = collectValidInferenceKeys(providerId, config, resolvedAccountId)
            require(candidates.size <= 1) { "Credential ownership mismatch" }
            resolvedKey = candidates.singleOrNull()?.let { InferenceKeyRef(it) }
        } else {
            resolvedAccountId = requireSingleProviderAccount(providerId).accountId
            val candidates = collectValidInferenceKeys(providerId, config, resolvedAccountId)
            require(candidates.size <= 1) { "Credential ownership mismatch" }
            resolvedKey = candidates.singleOrNull()?.let { InferenceKeyRef(it) }
        }
        val pat = selectPatForAccount(providerId, config, resolvedAccountId)
        return NewApiResolvedSyncScope(resolvedAccountId, pat, resolvedKey)
    }

    /**
     * Perf 專用 PAT 解析（`ProviderPerfStore.syncPerf` 唯一 PAT 來源）。
     *
     * - 無 config，或 config 完全無 ACCOUNT role entries→null（匿名既有契約保留，
     *   與是否指定帳號無關；requested account 本身不是 PAT provenance）。
     * - 只要 config 含任何 ACCOUNT role entry（含空／invalid safe ref），就必須選出
     *   valid 同帳號 PAT 或拋錯；configured ACCOUNT refs 絕不降級匿名／inference。
     * - 未指定帳號且有 ACCOUNT refs 時，要求該 provider 恰有一帳號
     *   （legacy 單帳號相容；多帳號歧義即拋錯）。
     * - 指定帳號只選其 PAT；他帳號 PAT 永不借用。
     *
     * @param providerId provider 主鍵，不可空白。
     * @param accountId 請求帳號（null 表 legacy 單帳號 fallback／匿名）。
     */
    suspend fun resolvePerfPat(
        providerId: String,
        accountId: String? = null,
    ): NewApiAccountPatSelection? {
        require(providerId.isNotBlank()) { "Provider required" }
        require(db.providerDao().getById(providerId) != null) { "Provider not found" }
        if (accountId != null) {
            requireRequestedAccount(providerId, accountId)
        }
        val configEntity = db.providerConfigDao().getByProvider(providerId) ?: return null
        val config = try {
            configEntity.toManualConfig()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("Provider config unavailable")
        }
        val hasAccountRefs = config.credentials.any { it.role == CredentialRole.ACCOUNT }
        if (!hasAccountRefs) return null
        val resolvedAccountId = if (accountId != null) {
            accountId
        } else {
            requireSingleProviderAccount(providerId).accountId
        }
        val pat = selectPatForAccount(providerId, config, resolvedAccountId)
        return NewApiAccountPatSelection(resolvedAccountId, pat)
    }

    /** Config 讀取：缺席／解析失敗皆靜態 fail-closed（cancel 重拋）。 */
    private suspend fun loadConfig(providerId: String): ProviderManualConfig {
        val entity = db.providerConfigDao().getByProvider(providerId)
            ?: throw IllegalArgumentException("Provider config not found")
        try {
            return entity.toManualConfig()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("Provider config unavailable")
        }
    }

    /** 請求帳號必須存在且屬該 provider（靜態訊息，不洩漏 DB 內容）。 */
    private suspend fun requireRequestedAccount(providerId: String, accountId: String): AccountEntity {
        require(accountId.isNotBlank()) { "Account required" }
        val account = db.accountDao().getById(accountId)
            ?: throw IllegalArgumentException("Account not found")
        require(account.providerId == providerId) { "Cross-provider refs rejected" }
        return account
    }

    /**
     * Legacy 單帳號 fallback：該 provider 恰有一帳號才回傳；
     * 零帳號／多帳號一律 fail-closed（永不取首帳號）。
     */
    private suspend fun requireSingleProviderAccount(providerId: String): AccountEntity {
        val all = db.accountDao().byProvider(providerId).sortedBy { it.accountId }
        require(all.size == 1) { "Account required" }
        return all.first()
    }

    /** Strict safe-ref 檢查（與 repo／perf 既有規則同字串；靜態訊息）。 */
    private fun requireSafeSelectorRef(ref: String) {
        require(ref.isNotBlank()) { "Credential ref must not be blank" }
        require(ref.length <= 256) { "Credential ref too long" }
        require(ref.none { it.code < 0x20 || it.code == 0x7F }) { "Credential ref must not contain control characters" }
        require(!ref.startsWith("sk-")) { "Credential ref must not carry secret" }
        require(ref.none { it.isWhitespace() }) { "Credential ref must not contain whitespace" }
    }

    /** [requireSafeSelectorRef] 的 boolean 版（fallback 掃描用，不拋錯）。 */
    private fun isSafeSelectorRef(ref: String): Boolean {
        if (ref.isBlank()) return false
        if (ref.length > 256) return false
        if (ref.any { it.code < 0x20 || it.code == 0x7F }) return false
        if (ref.startsWith("sk-")) return false
        if (ref.any { it.isWhitespace() }) return false
        return true
    }

    /** 已驗證的 explicit INFERENCE key（附其 owner 帳號；任一不合即靜態拋錯）。 */
    private data class ValidatedKey(val accountId: String, val ref: InferenceKeyRef)

    private suspend fun requireOwnedInferenceKey(
        providerId: String,
        config: ProviderManualConfig,
        credentialId: String,
    ): ValidatedKey {
        requireSafeSelectorRef(credentialId)
        val roles = config.credentials
            .filter { it.safeCredentialId == credentialId }
            .map { it.role }
            .toSet()
        require(roles == setOf(CredentialRole.INFERENCE)) { "Credential role unavailable" }
        val credential = db.credentialDao().getById(credentialId)
            ?: throw IllegalArgumentException("Credential ownership mismatch")
        require(credential.enabled) { "Credential ownership mismatch" }
        val owner = db.accountDao().getById(credential.accountId)
            ?: throw IllegalArgumentException("Account not found")
        require(owner.providerId == providerId) { "Cross-provider refs rejected" }
        return ValidatedKey(credential.accountId, InferenceKeyRef(credentialId))
    }

    /**
     * 同帳號 valid INFERENCE keys 全收集（config 順序、去重；無效只跳過）。
     * 由呼叫端判 0→null／1→沿用／多→歧義拒絕；他帳號 key 永不列入。
     */
    private suspend fun collectValidInferenceKeys(
        providerId: String,
        config: ProviderManualConfig,
        resolvedAccountId: String,
    ): List<String> {
        val out = mutableListOf<String>()
        val seen = LinkedHashSet<String>()
        for (entry in config.credentials) {
            if (entry.role != CredentialRole.INFERENCE) continue
            val ref = entry.safeCredentialId
            if (!seen.add(ref)) continue
            if (config.credentials.filter { it.safeCredentialId == ref }.map { it.role }.toSet() !=
                setOf(CredentialRole.INFERENCE)
            ) {
                continue
            }
            if (!isSafeSelectorRef(ref)) continue
            val credential = db.credentialDao().getById(ref) ?: continue
            if (!credential.enabled) continue
            if (credential.accountId != resolvedAccountId) continue
            val owner = db.accountDao().getById(credential.accountId) ?: continue
            if (owner.providerId != providerId) continue
            out += ref
        }
        return out
    }

    /**
     * 同帳號 PAT 選擇：逐候選掃描至第一個有效 PAT。
     * disabled／stale／dual-role／foreign／非法 ref 只跳過（記首錯），
     * 永不借他帳號；全無效時拋首錯、無候選時拋 `Account PAT required`。
     */
    private suspend fun selectPatForAccount(
        providerId: String,
        config: ProviderManualConfig,
        resolvedAccountId: String,
    ): AccountPatRef {
        var firstError: IllegalArgumentException? = null
        fun remember(message: String) {
            if (firstError == null) firstError = IllegalArgumentException(message)
        }
        val seen = LinkedHashSet<String>()
        for (entry in config.credentials) {
            if (entry.role != CredentialRole.ACCOUNT) continue
            val ref = entry.safeCredentialId
            if (!seen.add(ref)) continue
            try {
                requireSafeSelectorRef(ref)
            } catch (e: IllegalArgumentException) {
                remember(e.message ?: "Credential ref must not be blank")
                continue
            }
            if (config.credentials.filter { it.safeCredentialId == ref }.map { it.role }.toSet() !=
                setOf(CredentialRole.ACCOUNT)
            ) {
                remember("Credential role unavailable")
                continue
            }
            val credential = db.credentialDao().getById(ref)
            if (credential == null) {
                remember("Credential ownership mismatch")
                continue
            }
            if (!credential.enabled) {
                remember("Credential ownership mismatch")
                continue
            }
            val owner = db.accountDao().getById(credential.accountId)
            if (owner == null) {
                remember("Account not found")
                continue
            }
            if (owner.providerId != providerId) {
                remember("Cross-provider refs rejected")
                continue
            }
            if (credential.accountId != resolvedAccountId) {
                remember("Credential ownership mismatch")
                continue
            }
            return AccountPatRef(ref)
        }
        throw firstError ?: IllegalArgumentException("Account PAT required")
    }
}
