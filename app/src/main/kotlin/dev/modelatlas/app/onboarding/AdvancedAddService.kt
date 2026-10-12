package dev.modelatlas.app.onboarding

import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.RoomCommitter
import dev.modelatlas.app.data.RoomWorkflowStore
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.network.CatalogProbe
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.newapi.NewApiAdapter
import dev.modelatlas.core.onboarding.*
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 不用 data class，避免自動 toString()/copy() 將原始憑證回顯到診斷資料。 */
class AdvancedCredentialInput(
    val label: String,
    val secretTransient: String,
    val role: CredentialRole = CredentialRole.INFERENCE,
    val accountRef: String? = null,
)

class AdvancedAuthInput(
    val location: CredentialLocation,
    val name: String,
    val valueTransient: String,
    val authorizationOverrideConfirmed: Boolean = false,
)

/**
 * Advanced 的安全輸入邊界。metadata 可進 Room；原始值僅 vault store 時使用。
 * 預設只建 Review，不暗中進行任何付費探測。
 */
class AdvancedAddService(
    private val vault: AndroidCredentialVault,
    private val engine: InferenceEngine,
    private val database: AtlasDatabase,
) {
    private val gate = Mutex()
    private val store = RoomWorkflowStore(database)
    // PRICING_QUOTA_NOTICE 真嘗試（PROV-002）：已知 adapter 走能力介面；
    // Advanced 的 PAT_ACCOUNT 憑證（ACCOUNT 角色）可走帳號 quota＋官方 pricing，
    // inference 憑證走 key 層級 quota；缺 PAT 時 pricing 記不支援，不猜價格。
    private val workflow = OnboardingWorkflow(
        store,
        ProbeStepExecutor(
            CatalogProbe(vault),
            engine,
            noticeSource = NewApiPricingQuotaNoticeSource(NewApiAdapter(vault)),
        ),
        RoomCommitter(database),
    )

    suspend fun start(
        metadata: OnboardingSnapshot,
        credentials: List<AdvancedCredentialInput>,
        fields: List<AdvancedAuthInput> = emptyList(),
        workflowId: String = UUID.randomUUID().toString(),
    ): String = save(metadata, credentials, fields, workflowId, editing = false)

    suspend fun edit(workflowId: String, metadata: OnboardingSnapshot, credentials: List<AdvancedCredentialInput>, fields: List<AdvancedAuthInput>): String =
        save(metadata, credentials, fields, workflowId, editing = true)

    private suspend fun save(metadata: OnboardingSnapshot, credentials: List<AdvancedCredentialInput>, fields: List<AdvancedAuthInput>, workflowId: String, editing: Boolean): String {
        check(gate.tryLock()) { "新增流程執行中" }
        val createdRefs = mutableListOf<String>()
        try {
            val previous = if (editing) requireNotNull(store.load(workflowId)) else null
            require(previous == null || previous.flowType == OnboardingFlowType.ADVANCED)
            require(previous?.status !in setOf(OnboardingStatus.COMMITTED, OnboardingStatus.ABANDONED))
            val origin = EndpointPolicy.origin(EndpointPolicy.validate(requireNotNull(metadata.baseUrl)))
            val sameOrigin = previous?.baseUrl?.let { EndpointPolicy.origin(EndpointPolicy.validate(it)) == origin } == true
            val reusable = previous?.takeIf { sameOrigin }
            validate(metadata, credentials, fields, reusable)
            val safeCredentials = credentials.map { input ->
                val existing = reusable?.credentials?.firstOrNull { it.label == input.label.trim() && it.role == input.role && it.accountRef == input.accountRef?.trim()?.takeIf { value -> value.isNotEmpty() } }
                if (input.secretTransient.isBlank() && existing != null) return@map existing
                val id = UUID.randomUUID().toString()
                vault.store(id, origin, input.secretTransient)
                createdRefs += id
                SafeCredentialRef(id, input.label.trim(), input.role, input.accountRef?.trim()?.takeIf { it.isNotEmpty() })
            }
            val safeFields = fields.map { input ->
                val existing = reusable?.customAuthFields?.firstOrNull { it.location == input.location && it.name == input.name.trim() }
                if (input.valueTransient.isBlank() && existing?.safeCredentialId != null) return@map existing.copy(overrideConfirmed = input.authorizationOverrideConfirmed)
                val id = UUID.randomUUID().toString()
                vault.store(id, origin, input.valueTransient)
                createdRefs += id
                CustomAuthFieldRef(input.location, input.name.trim(), id, input.authorizationOverrideConfirmed)
            }
            val draft = metadata.copy(
                workflowId = workflowId,
                flowType = OnboardingFlowType.ADVANCED,
                credentials = safeCredentials,
                safeCredentialIds = safeCredentials.filter { it.role == CredentialRole.INFERENCE }.map { it.safeCredentialId },
                customAuthFields = safeFields,
                manualModelIds = metadata.manualModelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                tags = metadata.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
                group = metadata.group?.trim()?.takeIf { it.isNotEmpty() },
            )
            if (editing) workflow.editAndRerun(workflowId, draft, OnboardingStepId.REVIEW, runDetection = false)
            else workflow.start(draft, runDetection = false)
            val retained = safeCredentials.map { it.safeCredentialId } + safeFields.mapNotNull { it.safeCredentialId }
            val obsolete = previous?.credentials.orEmpty().map { it.safeCredentialId } + previous?.customAuthFields.orEmpty().mapNotNull { it.safeCredentialId }
            obsolete.filter { it !in retained }.forEach { runCatching { vault.remove(it) } }
            return workflowId
        } catch (e: Exception) {
            // 已保存的 snapshot 需要 refs 才能 resume；未保存才回收孤兒。
            withContext(NonCancellable) {
                val stored = runCatching { store.load(workflowId) }
                if (stored.isSuccess) {
                    val s = stored.getOrNull()
                    val used = s?.credentials.orEmpty().map { it.safeCredentialId } + s?.safeCredentialIds.orEmpty() +
                        s?.customAuthFields.orEmpty().mapNotNull { it.safeCredentialId }
                    createdRefs.filter { it !in used }.forEach { runCatching { vault.remove(it) } }
                }
            }
            if (e is CancellationException) throw e
            throw IllegalArgumentException("進階新增輸入無效或無法儲存")
        } finally {
            gate.unlock()
        }
    }

    private fun validate(s: OnboardingSnapshot, credentials: List<AdvancedCredentialInput>, fields: List<AdvancedAuthInput>, reusable: OnboardingSnapshot?) {
        require(!s.providerName.isNullOrBlank())
        val base = EndpointPolicy.validate(requireNotNull(s.baseUrl))
        val origin = EndpointPolicy.origin(base)
        s.websiteUrl?.takeIf { it.isNotBlank() }?.let { EndpointPolicy.validate(it) }
        s.checkinUrl?.takeIf { it.isNotBlank() }?.let { EndpointPolicy.validate(it) }
        require(s.endpointProfiles.any { it.enabled })
        s.endpointProfiles.forEach { endpoint ->
            require(endpoint.url.isNotBlank())
            val url = if (endpoint.url.startsWith("https://")) endpoint.url
                else base.toString().trimEnd('/') + "/" + endpoint.url.trimStart('/')
            val validated = EndpointPolicy.validate(url)
            require(EndpointPolicy.origin(validated) == origin)
            require(endpoint.protocol == endpoint.endpointType.name)
        }
        require(s.manualModelIds.any { it.isNotBlank() })
        require(credentials.map { it.label.trim() }.distinct().size == credentials.size)
        credentials.forEach { input ->
            require(input.label.isNotBlank())
            if (input.secretTransient.isBlank()) require(reusable?.credentials?.any { it.label == input.label.trim() && it.role == input.role && it.accountRef == input.accountRef?.trim()?.takeIf { value -> value.isNotEmpty() } } == true)
            else validSecret(input.secretTransient)
        }
        val mode = requireNotNull(s.authModeOverride)
        when (mode) {
            AuthMode.NONE -> require(s.anonymousConfirmed && credentials.isEmpty() && fields.isEmpty())
            AuthMode.BEARER -> require(credentials.any { it.role == CredentialRole.INFERENCE } && fields.isEmpty())
            AuthMode.PAT_ACCOUNT -> {
                require(credentials.any { it.role == CredentialRole.ACCOUNT })
                require(credentials.any { it.role == CredentialRole.INFERENCE })
                require(fields.isEmpty())
            }
            AuthMode.API_KEY -> require(fields.size == 1)
            AuthMode.CUSTOM_HEADER -> require(fields.isNotEmpty())
        }
        require(fields.map { it.location to it.name.lowercase() }.distinct().size == fields.size)
        fields.forEach {
            require(AUTH_NAME.matches(it.name))
            require(it.name.lowercase() !in FORBIDDEN_FIELDS)
            if (it.name.equals("Authorization", ignoreCase = true)) {
                require(mode == AuthMode.CUSTOM_HEADER && it.authorizationOverrideConfirmed && it.location == CredentialLocation.HEADER)
            }
            if (it.valueTransient.isBlank()) require(reusable?.customAuthFields?.any { ref -> ref.location == it.location && ref.name == it.name.trim() && ref.safeCredentialId != null } == true)
            else validSecret(it.valueTransient)
        }
        s.manualPricing.forEach {
            require(BigDecimal(it.decimalAmount).signum() >= 0)
            require(it.currency.isNotBlank() && it.perUnit != PricePerUnit.UNKNOWN)
            require(it.source == EvidenceSource.USER_MANUAL)
        }
        if (s.manualQuotaState != null && s.manualQuotaState != dev.modelatlas.core.domain.QuotaState.UNKNOWN) {
            require(!s.manualQuotaNote.isNullOrBlank())
        }
    }

    private fun validSecret(value: String) {
        require(value.isNotBlank() && value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == '\u0000' })
    }

    private companion object {
        val AUTH_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
        val FORBIDDEN_FIELDS = setOf("cookie", "host", "content-length", "content-type", "accept", "transfer-encoding", "connection", "proxy-authorization", "proxy-connection", "te", "trailer", "upgrade")
    }
}
