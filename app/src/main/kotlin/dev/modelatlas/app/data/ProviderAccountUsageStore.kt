package dev.modelatlas.app.data

import androidx.room.withTransaction
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.newapi.NewApiEvidence
import dev.modelatlas.core.newapi.NewApiResult
import dev.modelatlas.core.newapi.NewApiStatus
import dev.modelatlas.core.newapi.NewApiVersion
import dev.modelatlas.core.newapi.NewApiVersionSupport
import dev.modelatlas.core.onboarding.CredentialRole
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NINT-005A `PROVIDER_ACCOUNT_USAGE` 專用 store（Contract §2／§6.4／§23.3；
 * PRODUCT_SPEC §10／§12／§18；RESEARCH_POLICY §8 evidence-first）。
 *
 * 資料語意（違反即 reject）：
 * - 本槽是 **provider-reported 帳號層級使用量**：`/api/user/self` 的
 *   `request_count` 只逐值保存。它補的是 ModelAtlas 看不到的其他 CLI／裝置流量，
 *   與 MY_KEY_LOCAL（My Uptime／samples）**永不混算**，也不代表某把 key、某個 model
 *   或某個 group 的用量。`used_quota` 不在此重存（維持 ACCOUNT_QUOTA.usedRaw 權威槽）。
 * - 欄位語意只到站方原值為止：`counterSemantics` 恆 `UNSPECIFIED`，本層不猜累計／
 *   視窗／rate／delta，不提供任何衍生欄位。counter 重設（新版較小值）照實保存，
 *   不視為錯誤、不回推 delta。
 * - Unknown≠0／Free：無 `request_count` 欄為 null（no-data，仍是本次 history／latest），
 *   **永不補 0、永不當 Free**；非 certified 版本／失敗／負值一律 null count 且不推進
 *   last-good。負值另記 [NewApiStatus.MALFORMED]，與 no-data 的 `OK` 明確區分。
 * - 失敗／absent 欄位只 append 歷史，last-good 保留（sync failure 保留 last-good）。
 *
 * 來源／認證誠實（缺一即零寫入）：
 * - caps 失敗（帳號端點根本未被打）時，fact 沿用**原始 status source**
 *   `newapi/api/status` 與 caps 原始 status，**不改寫成 account source** 冒充一次
 *   未發生的 account 200；count 恆 null。
 * - caps 成功時 evidence source 必須是 `newapi/api/user/self`，且 caps／result 的
 *   `providerVersion` 與 `versionSupport` 必須同 session 相符；認證只認本次 session 的
 *   rc.25，**不查其他 head 的認證證據**。
 * - [SelectedEvidence.accountEndpointObserved] 只在 caps 成功且 result evidence 有真實
 *   HTTP status（真的收到 account 回應）時為 true；network／unknown-version 等
 *   httpStatus=null 標記不算一次成功觀測。
 *
 * 隔離與隱私：
 * - 只寫 `provider_account_usage_facts/heads`＋共用 `evidence`；永不寫
 *   `snapshot_facts`／`snapshot_heads`／`quotas`／perf／samples／My Models／telemetry。
 * - `modelId` 恆空字串、`source` 恆靜態常數、`status` 恆靜態 enum 名：無 body、無 PII、
 *   無 username／email／id、無 group／role／status 整數 metadata。
 * - PAT 只以 safe ref 作 provenance；raw 值永不進本層。落庫前重驗 provider／account／
 *   credential(enabled)／config 純 ACCOUNT role／單一 origin 與擷取時 validated base
 *   origin 一致，**不借 INFERENCE credential、不做匿名降級**。
 *
 * 兩腿獨立：本 store 不發任何網路請求、不重入 quota sync；持久化失敗由
 * [NewApiSnapshotStore.syncAccountQuota] 的 observer hook 做 fail-open，
 * [CancellationException] 永遠往上传。
 */
class ProviderAccountUsageStore(private val db: AtlasDatabase) : AccountQuotaObserver {

    private val recordMutex = Mutex()

    /** Observer 實作：只轉呼叫 [record]；例外交由上游 hook 判 ordinary／cancel。 */
    override suspend fun onObserved(observation: AccountQuotaObservation) {
        record(observation)
    }

    /**
     * 記錄本次帳號觀測並回傳 typed 讀取結果（latest／last-good）。
     *
     * 全程零網路；所有 guard 在寫入前完成，任一 guard 不合即零寫入（fail-closed）。
     * 讀回 [StoredProviderAccountUsage.lastGood] 只會是本輪（或更早）真正 good 的觀測，
     * 本輪失敗／absent／malformed 永不冒充 last-good。
     */
    suspend fun record(observation: AccountQuotaObservation): StoredProviderAccountUsage = recordMutex.withLock {
        val providerId = observation.providerId
        val accountId = observation.accountId
        val credentialId = observation.patRef.value
        requireSafeUsageScopeId(providerId, "providerId")
        requireSafeUsageScopeId(accountId, "accountId")
        requireSafeAccountUsageRef(credentialId)
        val scopeKey = providerAccountUsageScopeKey(providerId, accountId)
        db.withTransaction {
            val selected = selectEvidence(observation)
            require(selected.observedAt >= 0L) { "Invalid observation time" }
            requireOwnedAccountUsageScope(providerId, accountId, credentialId)
            requireSingleCapturedOrigin(providerId, observation.validatedBaseUrl)
            val id = newUsageId()
            val fact = ProviderAccountUsageFactEntity(
                evidenceId = id,
                scopeKey = scopeKey,
                kind = USAGE_KIND,
                providerId = providerId,
                accountId = accountId,
                credentialId = credentialId,
                observedAt = selected.observedAt,
                status = selected.status,
                providerVersion = safeUsageVersion(selected.providerVersion),
                httpStatus = selected.httpStatus,
                source = selected.source,
                requestCountRaw = selected.requestCount,
                counterSemantics = COUNTER_SEMANTICS_UNSPECIFIED,
                accountEndpointObserved = selected.accountEndpointObserved,
            )
            db.evidenceDao().insert(EvidenceEntity(
                id = id,
                requestId = id,
                providerId = providerId,
                modelId = "",
                outcome = fact.status,
                status = fact.httpStatus,
                timestamp = fact.observedAt,
                source = fact.source,
            ))
            appendFact(fact, selected.good)
            readStored(scopeKey)
        }
    }

    /** Typed 讀取：同 provider×account 的 latest／last-good（lastGood 絕非本輪失敗結果）。 */
    suspend fun observation(providerId: String, accountId: String): StoredProviderAccountUsage {
        requireSafeUsageScopeId(providerId, "providerId")
        requireSafeUsageScopeId(accountId, "accountId")
        return readStored(providerAccountUsageScopeKey(providerId, accountId))
    }

    /** Typed 歷史：新→舊（含 failure／no-data／malformed 標記；last-good 證據查 head）。 */
    suspend fun history(providerId: String, accountId: String): List<ProviderAccountUsageFactEntity> {
        requireSafeUsageScopeId(providerId, "providerId")
        requireSafeUsageScopeId(accountId, "accountId")
        return db.providerAccountUsageDao().history(
            providerAccountUsageScopeKey(providerId, accountId), USAGE_KIND,
        )
    }

    /**
     * 選證與 certified／count 閘門（純函式；所有 require 皆在寫入前）。
     *
     * - caps 失敗 → 原始 status source＋原始 status，count null、observed false、非 good。
     * - caps 成功 → 必須為 account source 且版本／support 同 session 相符（零寫入否則）。
     * - account 成功且 certified：count ≥ 0 為 good（0 是真值；quota 缺席不影響）、
     *   null 為 no-data `OK`（不推進 last-good）、負值為 MALFORMED（count 仍 null）。
     * - account 失敗 → 靜態 status 名、count null、非 good；HTTP status 仍取自真實 evidence。
     */
    private fun selectEvidence(observation: AccountQuotaObservation): SelectedEvidence {
        val caps = observation.capabilities
        val result = observation.result
        if (caps is NewApiResult.Failure) {
            // caps 未過 → adapter 未打 account 端點；不可宣稱觀測到 account 回應。
            require(result is NewApiResult.Failure) { "Incoherent account usage observation" }
            require(caps.evidence.source == CAPS_SOURCE) { "Unexpected evidence source" }
            return SelectedEvidence(
                source = CAPS_SOURCE,
                observedAt = caps.evidence.observedAt,
                status = caps.status.name,
                providerVersion = caps.evidence.providerVersion,
                httpStatus = caps.evidence.httpStatus,
                requestCount = null,
                accountEndpointObserved = false,
                good = false,
            )
        }
        val successfulCaps = caps as? NewApiResult.Success
            ?: throw IllegalArgumentException("Inconsistent capabilities")
        val capsVersion = successfulCaps.value.version
        val capsEvidence = successfulCaps.evidence
        require(capsEvidence.source == CAPS_SOURCE) { "Unexpected capabilities source" }
        require(capsEvidence.providerVersion == capsVersion.raw &&
            capsEvidence.versionSupport == capsVersion.support) { "Capabilities version mismatch" }
        require(capsEvidence.httpStatus in 200..299) { "Invalid capabilities evidence" }
        val accountEvidence = result.usageEvidence()
        require(accountEvidence.source == NewApiSnapshotStore.ACCOUNT_SOURCE) { "Unexpected evidence source" }
        require(accountEvidence.providerVersion == capsVersion.raw &&
            accountEvidence.versionSupport == capsVersion.support) { "Evidence version mismatch" }
        val accountEndpointObserved = accountEvidence.httpStatus != null
        val certified = accountEvidence.usageCertified()
        val failure = result as? NewApiResult.Failure
        if (failure != null) {
            return SelectedEvidence(
                source = NewApiSnapshotStore.ACCOUNT_SOURCE,
                observedAt = accountEvidence.observedAt,
                status = failure.status.name,
                providerVersion = accountEvidence.providerVersion,
                httpStatus = accountEvidence.httpStatus,
                requestCount = null,
                accountEndpointObserved = accountEndpointObserved,
                good = false,
            )
        }
        val account = (result as? NewApiResult.Success)?.value
            ?: throw IllegalArgumentException("Inconsistent account usage result")
        val reported = account.requestCount
        // 負值是站方矛盾資料：記 MALFORMED 標記、count 仍 null，不改正 last-good。
        val malformed = reported != null && reported < 0L
        // 數值 payload 只在 certified 成功（rc.25＋真 2xx）落庫；其餘一律 null count。
        val stored = if (certified && reported != null && reported >= 0L) reported else null
        return SelectedEvidence(
            source = NewApiSnapshotStore.ACCOUNT_SOURCE,
            observedAt = accountEvidence.observedAt,
            status = if (malformed) NewApiStatus.MALFORMED.name else NewApiStatus.OK.name,
            providerVersion = accountEvidence.providerVersion,
            httpStatus = accountEvidence.httpStatus,
            requestCount = stored,
            accountEndpointObserved = accountEndpointObserved,
            good = stored != null,
        )
    }

    /**
     * head／last-good 維護（鏡射 `NewApiSnapshotStore.record`／perf store 語意）：
     * 每次觀測都 append fact＋evidence；latest 僅嚴格更新推進，同時點衝突只入歷史不搶
     * head；唯有嚴格更新的 good 觀測才推進 last-good。
     */
    private suspend fun appendFact(fact: ProviderAccountUsageFactEntity, good: Boolean) {
        val dao = db.providerAccountUsageDao()
        dao.insertFact(fact)
        val old = dao.head(fact.scopeKey, fact.kind)
        val latest = old == null || fact.observedAt > old.latestObservedAt
        val newerGood = good && (old?.lastGoodObservedAt == null || fact.observedAt > old.lastGoodObservedAt)
        dao.upsertHead(ProviderAccountUsageHeadEntity(
            fact.scopeKey,
            fact.kind,
            if (latest) fact.evidenceId else old!!.latestEvidenceId,
            if (latest) fact.observedAt else old!!.latestObservedAt,
            if (newerGood) fact.evidenceId else old?.lastGoodEvidenceId,
            if (newerGood) fact.observedAt else old?.lastGoodObservedAt,
        ))
    }

    /** 只讀同 scope／kind 的 head 兩端；lastGood 永不回落成本輪失敗事實。 */
    private suspend fun readStored(scopeKey: String): StoredProviderAccountUsage {
        val dao = db.providerAccountUsageDao()
        val head = dao.head(scopeKey, USAGE_KIND) ?: return StoredProviderAccountUsage(null, null)
        return StoredProviderAccountUsage(
            dao.fact(head.latestEvidenceId),
            head.lastGoodEvidenceId?.let { dao.fact(it) },
        )
    }

    /**
     * 本地歸屬＋role guard（寫入前重驗，不信上游已驗）：provider 存在、account 屬該
     * provider、credential 存在且 enabled 且屬該 account、config 對同 ref 的角色集合
     * **恰為** ACCOUNT（INFERENCE／混合角色／config 缺席一律拒，絕不匿名降級）。
     */
    private suspend fun requireOwnedAccountUsageScope(providerId: String, accountId: String, credentialId: String) {
        require(db.providerDao().getById(providerId) != null) { "Provider not found" }
        require(db.accountDao().getById(accountId)?.providerId == providerId) { "Account ownership mismatch" }
        requireSafeAccountUsageRef(credentialId)
        val credential = db.credentialDao().getById(credentialId)
        require(credential != null && credential.enabled && credential.accountId == accountId) {
            "Credential ownership mismatch"
        }
        val entity = db.providerConfigDao().getByProvider(providerId)
            ?: throw IllegalArgumentException("Provider config unavailable")
        val config = try {
            entity.toManualConfig()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalArgumentException("Provider config unavailable")
        }
        require(config.credentials.filter { it.safeCredentialId == credentialId }.map { it.role }.toSet() ==
            setOf(CredentialRole.ACCOUNT)) { "Credential role unavailable" }
    }

    /** Provider 全部端點同 origin，且等於本次擷取時的 validated base origin（不跨 origin 記錄）。 */
    private suspend fun requireSingleCapturedOrigin(providerId: String, validatedBaseUrl: String) {
        val endpoints = db.endpointDao().byProvider(providerId)
        require(endpoints.isNotEmpty()) { "Endpoint required" }
        val origins = endpoints.map { EndpointPolicy.origin(EndpointPolicy.validate(it.baseUrl)) }.toSet()
        require(origins.size == 1) { "Provider origin mismatch" }
        val captured = EndpointPolicy.origin(EndpointPolicy.validate(validatedBaseUrl))
        require(origins.first() == captured) { "Validated base origin mismatch" }
    }

    /** 本次觀測的落庫決定（source／time／status／version／count／good 全在此定型）。 */
    private data class SelectedEvidence(
        val source: String,
        val observedAt: Long,
        val status: String,
        val providerVersion: String?,
        val httpStatus: Int?,
        val requestCount: Long?,
        val accountEndpointObserved: Boolean,
        val good: Boolean,
    )

    companion object {
        /** capabilities（`GET /api/status`）source；caps 失敗的事實來源（不冒充 account source）。 */
        const val CAPS_SOURCE = "newapi/api/status"

        /** counter 語意恆未確認：本層不推導累計／視窗／rate／delta。 */
        const val COUNTER_SEMANTICS_UNSPECIFIED = "UNSPECIFIED"

        /** 本槽只有一種 kind。 */
        private val USAGE_KIND = ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name
    }
}

/** 本 scope 的 typed 讀取結果：latest 為本次狀態；lastGood 只含真正 good 的觀測。 */
data class StoredProviderAccountUsage(
    val latest: ProviderAccountUsageFactEntity?,
    val lastGood: ProviderAccountUsageFactEntity?,
)

private fun NewApiResult<*>.usageEvidence(): NewApiEvidence = when (this) {
    is NewApiResult.Success -> evidence
    is NewApiResult.Failure -> evidence
}

/** certified 只認 rc.25＋真的 2xx；未知版本／無 HTTP 一律非 certified。 */
private fun NewApiEvidence.usageCertified(): Boolean = providerVersion == NewApiVersion.RC25 &&
    versionSupport == NewApiVersionSupport.CERTIFIED_RC25 && httpStatus in 200..299

/** provider version 落庫前硬化：bounded／安全字元；不合即 null（不抄任意 raw 文字）。 */
private fun safeUsageVersion(value: String?): String? = value?.takeIf {
    it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._-]+")) && !it.startsWith("sk-")
}

private fun requireSafeUsageScopeId(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= 256) { "$label too long" }
    require(value.none { it.code < 0x20 || it.code == 0x7F }) { "$label must not contain control characters" }
    require(!value.startsWith("sk-")) { "$label must not carry secret" }
    require(value.none { it.isWhitespace() }) { "$label must not contain whitespace" }
}

private fun newUsageId(): String = "usage:" + UUID.randomUUID()
