package dev.modelatlas.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import java.math.BigDecimal

/**
 * v9 追加 health 快照欄（PROV-006／§11）：official 與 observed 分開記觀測時間，
 * 0＝從未觀測（Unknown≠0，不以 0 冒充已知）。official 只由官方來源寫入，
 * observed 由 [HealthRollup] 從 samples 本地聚合寫入，兩者不互相冒充。
 */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val providerId: String,
    val name: String,
    val officialHealth: String = "UNKNOWN",
    val observedHealth: String = "UNKNOWN",
    val checkinUrl: String? = null,
    val requiresDailyCheckin: Boolean = false,
    @ColumnInfo(defaultValue = "0") val officialHealthAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val observedHealthAt: Long = 0L,
)

@Entity(
    tableName = "accounts",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["providerId"],
        childColumns = ["providerId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("providerId")],
)
data class AccountEntity(
    @PrimaryKey val accountId: String,
    val providerId: String,
)

/**
 * v9 追加 credential metadata（PROV-006／PROV-009／§6）：
 * note／group／priority／authMode／headerName／headerPrefix／isSecret／lastSuccessAt／lastFailureAt。
 * `headerPrefix` 為非敏感顯示前綴；`isSecret=true` 表示真正 secret value 在 privileged vault，
 * 本表永不存 secret 原始值。未知一律 null／0／UNKNOWN（Unknown≠0，不自造 group、不猜 authMode；
 * 舊列 authMode 未知保持 UNKNOWN，不回填 API_KEY；新列已知值由 call site 顯式傳入）。
 */
@Entity(
    tableName = "credentials",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["accountId"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("accountId")],
)
data class CredentialEntity(
    @PrimaryKey val credentialId: String,
    val accountId: String,
    val label: String = "主要憑證",
    val enabled: Boolean = true,
    val note: String? = null,
    val group: String? = null,
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(defaultValue = "UNKNOWN") val authMode: String = "UNKNOWN",
    val headerName: String? = null,
    val headerPrefix: String? = null,
    @ColumnInfo(defaultValue = "1") val isSecret: Boolean = true,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
)

@Entity(
    tableName = "endpoints",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["providerId"],
        childColumns = ["providerId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("providerId")],
)
data class EndpointEntity(
    @PrimaryKey val endpointId: String,
    val providerId: String,
    val baseUrl: String,
    val endpointUrl: String,
    val protocol: String = "CHAT_COMPLETIONS",
)

@Entity(
    tableName = "offerings",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["providerId"],
        childColumns = ["providerId"],
        onDelete = ForeignKey.RESTRICT,
    ), ForeignKey(
        entity = EndpointEntity::class,
        parentColumns = ["endpointId"],
        childColumns = ["endpointId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("providerId"), Index("endpointId"), Index("modelId")],
)
/**
 * 非 key 專屬：只綁 provider / endpoint / model；account / key / 價格走 availability。
 *
 * v7 追加兩欄（MODEL-006，PRODUCT_SPEC §8；既有列預設可見、未驗證）：
 * - `hidden`：true＝catalog 沒列的手動 offering 尚不可見（預設 0＝可見）。
 *   `defaultValue="0"` 是刻意的既有列預設（migration 以 `ADD COLUMN … DEFAULT 0` 相容），
 *   讓「舊列不被升格為已驗證」與「新列可見」同時成立。
 * - `verificationEvidenceId`：實測成功 evidence id；null＝未驗證（不可見亦不可用）。
 *   與 hidden 分欄：hidden 只管可見性，驗證事實以 evidence 為準，兩者不互相冒充。
 */
data class OfferingEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val endpointId: String,
    @ColumnInfo(name = "modelId") val providerModelId: String,
    val manual: Boolean = true,
    val source: String = "MANUAL",
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    val verificationEvidenceId: String? = null,
)

/** account / key 綁定與價格狀態；首 slice id 與 offering id 同值；v2 起 UNIQUE(offeringId,accountId,credentialId)，surrogate id 保留。 */
@Entity(
    tableName = "credential_availability",
    foreignKeys = [ForeignKey(
        entity = OfferingEntity::class,
        parentColumns = ["id"],
        childColumns = ["offeringId"],
        onDelete = ForeignKey.RESTRICT,
    ), ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["accountId"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.RESTRICT,
    ), ForeignKey(
        entity = CredentialEntity::class,
        parentColumns = ["credentialId"],
        childColumns = ["credentialId"],
        onDelete = ForeignKey.RESTRICT,
    )    ],
    indices = [
        Index("offeringId"),
        Index("accountId"),
        Index("credentialId"),
        Index(
            value = ["offeringId", "accountId", "credentialId", "groupIdentity"],
            unique = true,
            name = "index_credential_availability_offering_account_credential_group",
        ),
    ],
)
/**
 * account / key 綁定與價格狀態；首 slice id 與 offering id 同值；
 * v2 起 UNIQUE(offeringId,accountId,credentialId)，surrogate id 保留。
 *
 * v7 追加 `group`（MODEL-007，PRODUCT_SPEC §6／§10 七維 scope 的 group 維度）：
 * null＝未知 scope（既有列全部為 null，不補零值、不自造 group 名）；
 * 非 null 即與 `snapshot_facts`／`price_snapshots` 的 `group` 同一命名空間，
 * 供 selected-key 投影做精確比對。本欄只記維度，不改既有 priceState／quotaState 語意。
 *
 * v14 追加 `groupIdentity`（CR-003 B3，ACTIVE_TASK CR-003 ports 鎖定）：
 * 非空 derived 欄，由 `availabilityGroupIdentity(group)` 計算（null→"n"、非 null→"v"+原group），
 * defaultValue='n' 只是既有列的初始 placeholder（正好涵蓋 null rows）；migration 必須
 * 回填所有非 null raw group 為 'v'+原值，才能使舊列 identity 正確；
 * `group` nullable 原值保留（Unknown != 0，不猜新 group）。UNIQUE 索引從舊三欄
 * `(offeringId,accountId,credentialId)` 換成含 `groupIdentity` 四欄
 * `index_credential_availability_offering_account_credential_group`，使 null／""／具名 group
 * 各自獨立不互覆、不混 scope。寫入一律經 AvailabilityDao.insert 正規化，防 copy(group=…)
 * 沿用舊 identity key。
 */
data class CredentialAvailabilityEntity(
    @PrimaryKey val id: String,
    val offeringId: String,
    val accountId: String,
    val credentialId: String,
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val group: String? = null,
    @ColumnInfo(defaultValue = "'n'") val groupIdentity: String = availabilityGroupIdentity(group),
)

/** 全欄位保存；query cols：id / providerId / modelId / startedAt / outcome。 */
@Entity(
    tableName = "samples",
    indices = [Index("providerId"), Index("modelId"), Index("startedAt"), Index("outcome")],
)
data class SampleEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val accountId: String,
    val safeCredentialId: String,
    val endpointId: String,
    @ColumnInfo(name = "modelId") val providerModelId: String,
    val canonicalModelId: String? = null,
    val group: String? = null,
    val operation: String = "CHAT_COMPLETIONS",
    val source: String = "PLAYGROUND",
    @ColumnInfo(name = "startedAt") val requestStartedAt: Long = 0L,
    val firstTokenAt: Long? = null,
    val completedAt: Long? = null,
    val streaming: Boolean = true,
    val ttftMs: Long? = null,
    val ttfbMs: Long? = null,
    val totalLatencyMs: Long? = null,
    val tps: Double? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val usageEstimated: Boolean = false,
    val httpStatus: Int? = null,
    val outcome: String = "RUNNING",
    val retryCount: Int = 0,
    val responseModelId: String? = null,
    val rateLimitHeaders: String = "",
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val pricingSnapshotId: String? = null,
    val quotaSnapshotBeforeId: String? = null,
    val quotaSnapshotAfterId: String? = null,
    val actualCost: String? = null,
    val estimatedCost: String? = null,
)

/** 簡短本地證據：request id / outcome / status / timestamp，不存 prompt 與回覆。 */
@Entity(
    tableName = "evidence",
    indices = [Index("requestId"), Index("timestamp")],
)
data class EvidenceEntity(
    @PrimaryKey val id: String,
    val requestId: String,
    val providerId: String,
    val modelId: String,
    val outcome: String,
    val status: Int? = null,
    val timestamp: Long = 0L,
    val source: String = "",
    val version: Int = 1,
)

/** 日期唯一鍵 (providerId, date, accountId)，date 為裝置本地 ISO yyyy-MM-dd。completedAt 為 null 表示尚未完成。accountId="" 為 legacy，不當 wildcard；migration 僅在該 provider 恰有一帳號時綁到該帳號。 */
@Entity(tableName = "checkins", primaryKeys = ["providerId", "date", "accountId"])
data class CheckinEntity(
    val providerId: String,
    val date: String,
    val completedAt: Long? = null,
    val source: String = "USER",
    @ColumnInfo(defaultValue = "''") val accountId: String = "",
)

/** 精確 decimal 字串＋完整 scope；時間在 observedAt。 */
@Entity(
    tableName = "price_snapshots",
    indices = [Index("providerId"), Index("modelId"), Index("observedAt")],
)
data class PriceSnapshotEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val accountId: String? = null,
    val credentialId: String? = null,
    val modelId: String = "",
    val group: String? = null,
    val endpointId: String = "",
    val operation: String = "CHAT_COMPLETIONS",
    val observedAt: Long = 0L,
    val input: String? = null,
    val output: String? = null,
    val fixedRequest: String? = null,
    val fixedRequestKnownAbsent: Boolean = false,
    val cacheRead: String? = null,
    val cacheWrite: String? = null,
    val source: String = "",
    val evidenceId: String? = null,
)

/** state 可 null；null 即 UNKNOWN。 */
@Entity(
    tableName = "quota_snapshots",
    indices = [Index("providerId"), Index("modelId"), Index("observedAt")],
)
data class QuotaSnapshotEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val accountId: String? = null,
    val credentialId: String? = null,
    val modelId: String = "",
    val group: String? = null,
    val endpointId: String = "",
    val operation: String = "CHAT_COMPLETIONS",
    val observedAt: Long = 0L,
    val state: String? = null,
    val source: String = "",
    val evidenceId: String? = null,
)

internal fun PerformanceSample.toEntity(): SampleEntity = SampleEntity(
    id = id,
    providerId = providerId,
    accountId = accountId,
    safeCredentialId = safeCredentialId,
    endpointId = endpointId,
    providerModelId = providerModelId,
    canonicalModelId = canonicalModelId,
    group = group,
    operation = operation,
    source = source.name,
    requestStartedAt = requestStartedAt,
    firstTokenAt = firstTokenAt,
    completedAt = completedAt,
    streaming = streaming,
    ttftMs = ttftMs,
    ttfbMs = ttfbMs,
    totalLatencyMs = totalLatencyMs,
    tps = tps,
    inputTokens = usage.inputTokens,
    outputTokens = usage.outputTokens,
    totalTokens = usage.totalTokens,
    reasoningTokens = usage.reasoningTokens,
    cacheReadTokens = usage.cacheReadTokens,
    cacheWriteTokens = usage.cacheWriteTokens,
    usageEstimated = usage.estimated,
    httpStatus = httpStatus,
    outcome = outcome.name,
    retryCount = retryCount,
    responseModelId = responseModelId,
    rateLimitHeaders = encodeHeaders(rateLimitHeaders),
    priceState = priceState,
    quotaState = quotaState,
    pricingSnapshotId = pricingSnapshotId,
    quotaSnapshotBeforeId = quotaSnapshotBeforeId,
    quotaSnapshotAfterId = quotaSnapshotAfterId,
    actualCost = actualCost,
    estimatedCost = estimatedCost,
)

internal fun SampleEntity.toSample(): PerformanceSample = PerformanceSample(
    id = id,
    providerId = providerId,
    accountId = accountId,
    safeCredentialId = safeCredentialId,
    endpointId = endpointId,
    providerModelId = providerModelId,
    canonicalModelId = canonicalModelId,
    group = group,
    operation = operation,
    source = safeSource(source),
    requestStartedAt = requestStartedAt,
    firstTokenAt = firstTokenAt,
    completedAt = completedAt,
    streaming = streaming,
    ttftMs = ttftMs,
    ttfbMs = ttfbMs,
    totalLatencyMs = totalLatencyMs,
    tps = tps,
    usage = Usage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        reasoningTokens = reasoningTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        estimated = usageEstimated,
    ),
    httpStatus = httpStatus,
    outcome = safeOutcome(outcome),
    retryCount = retryCount,
    responseModelId = responseModelId,
    rateLimitHeaders = decodeHeaders(rateLimitHeaders),
    priceState = priceState,
    quotaState = quotaState,
    pricingSnapshotId = pricingSnapshotId,
    quotaSnapshotBeforeId = quotaSnapshotBeforeId,
    quotaSnapshotAfterId = quotaSnapshotAfterId,
    actualCost = actualCost,
    estimatedCost = estimatedCost,
)

internal fun PerformanceSample.toEvidence(): EvidenceEntity = EvidenceEntity(
    id = "sample:" + id,
    requestId = id,
    providerId = providerId,
    modelId = providerModelId,
    outcome = outcome.name,
    status = httpStatus,
    timestamp = completedAt ?: requestStartedAt,
    source = source.name,
)

internal fun PriceSnapshot.toEntity(): PriceSnapshotEntity = PriceSnapshotEntity(
    id = id,
    providerId = scope.providerId,
    accountId = scope.accountId,
    credentialId = scope.credentialId,
    modelId = scope.modelId,
    group = scope.group,
    endpointId = scope.endpointId,
    operation = scope.operation,
    observedAt = observedAt,
    input = input?.toPlainString(),
    output = output?.toPlainString(),
    fixedRequest = fixedRequest?.toPlainString(),
    fixedRequestKnownAbsent = fixedRequestKnownAbsent,
    cacheRead = cacheRead?.toPlainString(),
    cacheWrite = cacheWrite?.toPlainString(),
    source = source,
    evidenceId = evidenceId,
)

internal fun PriceSnapshotEntity.toDomain(): PriceSnapshot = PriceSnapshot(
    id = id,
    scope = PricingScope(
        providerId = providerId,
        accountId = accountId,
        credentialId = credentialId,
        modelId = modelId,
        group = group,
        endpointId = endpointId,
        operation = operation,
    ),
    observedAt = observedAt,
    input = input?.toBigDecimalOrNull(),
    output = output?.toBigDecimalOrNull(),
    fixedRequest = fixedRequest?.toBigDecimalOrNull(),
    fixedRequestKnownAbsent = fixedRequestKnownAbsent,
    cacheRead = cacheRead?.toBigDecimalOrNull(),
    cacheWrite = cacheWrite?.toBigDecimalOrNull(),
    source = source,
    evidenceId = evidenceId,
)

internal fun QuotaSnapshot.toEntity(): QuotaSnapshotEntity = QuotaSnapshotEntity(
    id = id,
    providerId = scope.providerId,
    accountId = scope.accountId,
    credentialId = scope.credentialId,
    modelId = scope.modelId,
    group = scope.group,
    endpointId = scope.endpointId,
    operation = scope.operation,
    observedAt = observedAt,
    state = if (state == QuotaState.UNKNOWN) null else state.name,
    source = source,
    evidenceId = evidenceId,
)

internal fun QuotaSnapshotEntity.toDomain(): QuotaSnapshot = QuotaSnapshot(
    id = id,
    scope = PricingScope(
        providerId = providerId,
        accountId = accountId,
        credentialId = credentialId,
        modelId = modelId,
        group = group,
        endpointId = endpointId,
        operation = operation,
    ),
    observedAt = observedAt,
    state = safeQuotaState(state),
    source = source,
    evidenceId = evidenceId,
)

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

private fun safeOutcome(name: String): Outcome =
    runCatching { Outcome.valueOf(name) }.getOrDefault(Outcome.OTHER)

/** 未知來源不偽裝成 PLAYGROUND；主 session 會在 contracts 補上 UNKNOWN。 */
private fun safeSource(name: String): TelemetrySource =
    runCatching { TelemetrySource.valueOf(name) }.getOrDefault(TelemetrySource.UNKNOWN)

private fun safeQuotaState(name: String?): QuotaState =
    if (name == null) QuotaState.UNKNOWN
    else runCatching { QuotaState.valueOf(name) }.getOrDefault(QuotaState.UNKNOWN)

internal fun encodeHeaders(headers: Map<String, String>): String =
    headers.toSortedMap().entries.joinToString(";") { (k, v) -> escapeHeader(k) + "=" + escapeHeader(v) }

private fun escapeHeader(s: String): String =
    s.replace("\\", "\\\\").replace(";", "\\;").replace("=", "\\=")

internal fun decodeHeaders(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    val key = StringBuilder()
    val value = StringBuilder()
    var target: StringBuilder = key
    var escaped = false
    for (c in raw) {
        if (escaped) {
            target.append(c)
            escaped = false
            continue
        }
        when {
            c == '\\' -> escaped = true
            c == '=' && target === key -> target = value
            c == ';' -> {
                out[key.toString()] = value.toString()
                key.clear()
                value.clear()
                target = key
            }
            else -> target.append(c)
        }
    }
    out[key.toString()] = value.toString()
    return out
}

/**
 * MODEL-001 canonical 身份表（Room v7，只加表）。
 * 同一 canonical 不因不同 key 重複建立：normalizedName 唯一，寫入前先查 id 再查正規化名。
 */
@Entity(
    tableName = "canonical_models",
    indices = [Index(value = ["normalizedName"], unique = true, name = "index_canonical_models_normalizedName")],
)
data class CanonicalModelEntity(
    @PrimaryKey val canonicalModelId: String,
    val normalizedName: String,
    val displayName: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Int = 1,
)

/**
 * MODEL-002 alias 映射表（Room v7，只加表）。
 * 原始 provider model ID 永遠保存；mapping 來源與信心分欄；manual 恆 LOW；
 * verified=false 即 tentative。source／version 欄保留供 P7 LMSpeed enrichment 追加。
 */
@Entity(
    tableName = "provider_model_aliases",
    foreignKeys = [ForeignKey(
        entity = CanonicalModelEntity::class,
        parentColumns = ["canonicalModelId"],
        childColumns = ["canonicalModelId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["canonicalModelId"], name = "index_provider_model_aliases_canonical"),
        Index(
            value = ["providerId", "providerModelId"],
            unique = true,
            name = "index_provider_model_aliases_provider_model",
        ),
    ],
)
data class ProviderModelAliasEntity(
    @PrimaryKey val aliasId: String,
    val providerId: String,
    val providerModelId: String,
    val canonicalModelId: String,
    val source: String,
    val confidence: String,
    val verified: Boolean = false,
    val observedAt: Long = 0L,
    val version: Int = 1,
    val evidenceId: String? = null,
)

/**
 * MODEL-003 分類表（Room v7，只加表，關 BACKLOG F1）。
 * 同一 canonical 按來源分列（CATALOG／OBSERVED／MANUAL），confidence 分離；
 * 手填恆 LOW。primaryCategory 為 null 即未知（Unknown≠0，不填零值、不拿 OTHER 冒充）。
 */
@Entity(
    tableName = "model_classifications",
    foreignKeys = [ForeignKey(
        entity = CanonicalModelEntity::class,
        parentColumns = ["canonicalModelId"],
        childColumns = ["canonicalModelId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["canonicalModelId"], name = "index_model_classifications_canonical"),
        Index(
            value = ["canonicalModelId", "source"],
            unique = true,
            name = "index_model_classifications_canonical_source",
        ),
    ],
)
data class ModelClassificationEntity(
    @PrimaryKey val id: String,
    val canonicalModelId: String,
    val source: String,
    val confidence: String,
    val primaryCategory: String? = null,
    val observedAt: Long = 0L,
    val version: Int = 1,
)

/** 分類 capabilities 關聯（複合 PK＋FK RESTRICT，只加表）。 */
@Entity(
    tableName = "classification_capabilities",
    foreignKeys = [ForeignKey(
        entity = ModelClassificationEntity::class,
        parentColumns = ["id"],
        childColumns = ["classificationId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["classificationId"], name = "index_classification_capabilities_classification")],
    primaryKeys = ["classificationId", "capability"],
)
data class ClassificationCapabilityEntity(
    val classificationId: String,
    val capability: String,
)

/** 分類 modalities 關聯（複合 PK＋FK RESTRICT，只加表）。 */
@Entity(
    tableName = "classification_modalities",
    foreignKeys = [ForeignKey(
        entity = ModelClassificationEntity::class,
        parentColumns = ["id"],
        childColumns = ["classificationId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["classificationId"], name = "index_classification_modalities_classification")],
    primaryKeys = ["classificationId", "modality"],
)
data class ClassificationModalityEntity(
    val classificationId: String,
    val modality: String,
)

/** 分類 endpointTypes 關聯（複合 PK＋FK RESTRICT，只加表）。 */
@Entity(
    tableName = "classification_endpoint_types",
    foreignKeys = [ForeignKey(
        entity = ModelClassificationEntity::class,
        parentColumns = ["id"],
        childColumns = ["classificationId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["classificationId"], name = "index_classification_endpoint_types_classification")],
    primaryKeys = ["classificationId", "endpointType"],
)
data class ClassificationEndpointTypeEntity(
    val classificationId: String,
    val endpointType: String,
)

/**
 * LMS-003 外部觀測快取（Room v8，只加表；TRACEABILITY LMS-003／PRODUCT_SPEC §13／RESEARCH_POLICY §7）。
 * source-agnostic：source 為不透明邏輯來源名，不寫任何來源 URL／endpoint（來源清單等 R 研究）。
 * enrichment＋discovery seed，非 ground truth；external 與 local 永遠分開——本表絕不寫入
 * snapshot_facts／snapshot_heads／samples／telemetry（讀寫分離，違者 CI 擋）。
 * canonicalModelId 可空：null＝尚未對應 canonical（UNKNOWN 語意，不猜、不填零值）；
 * 無 FK：seed 可先於 canonical 存在。confidence 含 UNKNOWN；payloadJson 為外部原始承載。
 */
@Entity(
    tableName = "external_observations",
    indices = [
        Index(
            value = ["source", "externalModelId", "fetchedAt"],
            unique = true,
            name = "index_external_observations_source_model_fetched",
        ),
        Index(value = ["canonicalModelId"], name = "index_external_observations_canonical"),
        Index(value = ["source"], name = "index_external_observations_source"),
        Index(value = ["fetchedAt"], name = "index_external_observations_fetched"),
    ],
)
data class ExternalObservationEntity(
    @PrimaryKey val observationId: String,
    val source: String,
    val externalModelId: String,
    val canonicalModelId: String? = null,
    val payloadJson: String,
    val fetchedAt: Long = 0L,
    val ttlMillis: Long = 0L,
    val confidence: String = "UNKNOWN",
)

/**
 * LMS-003 外部映射槽（Room v8，只加表；TRACEABILITY LMS-003／LMS-004／PRODUCT_SPEC §13）。
 * external 獨立槽：與 provider_model_aliases／model_classifications 三槽互不覆蓋、
 * 不互相冒充（manual／official／observed／external 分離）。unique(externalId, source)：
 * 同一外部 ID 在不同來源各一列；同來源修正以同 mappingId 覆寫（冪等）。
 * canonicalModelId 可空：null＝尚未解析（UNKNOWN，不猜）。無 FK：seed 可先於 canonical。
 * source-agnostic：不寫任何來源 URL／endpoint。
 */
@Entity(
    tableName = "external_mappings",
    indices = [
        Index(value = ["canonicalModelId"], name = "index_external_mappings_canonical"),
        Index(
            value = ["externalId", "source"],
            unique = true,
            name = "index_external_mappings_external_source",
        ),
    ],
)
data class ExternalMappingEntity(
    @PrimaryKey val mappingId: String,
    val externalId: String,
    val canonicalModelId: String? = null,
    val source: String,
    val confidence: String = "UNKNOWN",
    val verified: Boolean = false,
    val version: Int = 1,
    val observedAt: Long = 0L,
)

/**
 * MODEL-005 收藏（Room v9，只加表；PRODUCT_SPEC §8 favorites）。
 * 以 canonicalModelId 為主鍵（與 [ModelSearchLogic] 的 favoriteIds 同一命名空間）。
 * 無 FK：收藏是使用者狀態，不因 canonical 尚未建立而拒絕；canonical 永不刪除。
 */
@Entity(
    tableName = "favorite_models",
    indices = [Index(value = ["addedAt"], name = "index_favorite_models_added")],
)
data class FavoriteModelEntity(
    @PrimaryKey val canonicalModelId: String,
    val addedAt: Long = 0L,
    val source: String = "USER",
)

/** MODEL-005 關注清單（Room v9，只加表；PRODUCT_SPEC §8 watchlist）。note 可空＝無備註，不填零值。 */
@Entity(
    tableName = "watchlist_models",
    indices = [Index(value = ["addedAt"], name = "index_watchlist_models_added")],
)
data class WatchlistModelEntity(
    @PrimaryKey val canonicalModelId: String,
    val addedAt: Long = 0L,
    val note: String? = null,
)

/**
 * MODEL-005 最近使用（Room v9，只加表；PRODUCT_SPEC §8 recently used）。
 * 以 lastUsedAt 排序；useCount 為本地使用次數，只增不減。
 */
@Entity(
    tableName = "recent_models",
    indices = [Index(value = ["lastUsedAt"], name = "index_recent_models_last_used")],
)
data class RecentModelEntity(
    @PrimaryKey val canonicalModelId: String,
    val lastUsedAt: Long = 0L,
    val useCount: Int = 1,
)

/**
 * X-018 ProviderNotice（Room v10，只加表；TRACEABILITY X-018／PRODUCT_SPEC §10末端
 * `… → Snapshot → Event`／§11 History／§14 Notices可讀／IMPLEMENTATION_PLAN「隨階段增加
 * ProviderNotice/Event」）。
 *
 * - 來源文字永不存 raw response body：只存截斷摘要 [summary]（[EventStore] 截斷至
 *   [EventStore.MAX_SUMMARY_CHARS]）＋外部 ref [ref]（evidenceId／條件請求標籤等，
 *   非 secret、非 URL 白名單來源）。
 * - 無 FK：notice 可先於 provider／evidence 存在（seed／快取語意），刪 provider 不連帶清歷史；
 *   呼叫端以 providerId 精確查詢（X-004），絕不跨 provider 混合。
 * - `expiresAt` null＝無到期（不猜）；`evidenceId` null＝無關聯證據（UNKNOWN，不填零值）。
 */
@Entity(
    tableName = "provider_notices",
    indices = [
        Index(value = ["providerId"], name = "index_provider_notices_provider"),
        Index(value = ["observedAt"], name = "index_provider_notices_observed"),
    ],
)
data class ProviderNoticeEntity(
    @PrimaryKey val noticeId: String,
    val providerId: String,
    val observedAt: Long = 0L,
    val expiresAt: Long? = null,
    val source: String,
    val evidenceId: String? = null,
    val summary: String,
    val ref: String? = null,
)

/**
 * X-018 ProviderEvent（Room v10，只加表；同上 spec）。
 * 種類僅 OUTAGE／RECOVERY／NOTICE（[EventStore.PROVIDER_EVENT_KINDS] 校验）；
 * OUTAGE 需健康證據（多個已判定樣本），單次失敗不產 OUTAGE（見 [EventStore]）。
 * append-only：DAO 以 ABORT 插入，不覆寫歷史。無 FK（理由同 notices）。
 */
@Entity(
    tableName = "provider_events",
    indices = [
        Index(value = ["providerId"], name = "index_provider_events_provider"),
        Index(value = ["observedAt"], name = "index_provider_events_observed"),
    ],
)
data class ProviderEventEntity(
    @PrimaryKey val eventId: String,
    val providerId: String,
    val kind: String,
    val observedAt: Long = 0L,
    val source: String,
    val evidenceId: String? = null,
    val detail: String? = null,
)

/**
 * X-018 PriceEvent（Room v10，只加表；同上 spec＋§10 PricingScope 七維）。
 * - `scopeKey` 為 [PricingScope.storageKey] 七維（provider／account／credential／model／
 *   group／endpoint／operation），時間在 [observedAt]；查詢一律精確 `=` 比對，
 *   不同 scope 永不互相覆蓋（X-004）。
 * - from／to 全可空：null＝UNKNOWN（X-001，永不填 0／"0" 冒充已知）。
 * - append-only：DAO 以 ABORT 插入。無 FK。
 */
@Entity(
    tableName = "price_events",
    indices = [
        Index(value = ["scopeKey"], name = "index_price_events_scope"),
        Index(value = ["observedAt"], name = "index_price_events_observed"),
    ],
)
data class PriceEventEntity(
    @PrimaryKey val eventId: String,
    val scopeKey: String,
    val fromState: String? = null,
    val toState: String? = null,
    val fromInput: String? = null,
    val toInput: String? = null,
    val fromOutput: String? = null,
    val toOutput: String? = null,
    val observedAt: Long = 0L,
    val source: String,
    val evidenceId: String? = null,
)

/**
 * X-018 QuotaEvent（Room v10，只加表；同上 spec）。
 * - `scopeKey` 同 PriceEvent 七維；`fromState`／`toState` 為 QuotaState name，
 *   null＝UNKNOWN（X-001）。
 * - append-only：DAO 以 ABORT 插入。無 FK。
 */
@Entity(
    tableName = "quota_events",
    indices = [
        Index(value = ["scopeKey"], name = "index_quota_events_scope"),
        Index(value = ["observedAt"], name = "index_quota_events_observed"),
    ],
)
data class QuotaEventEntity(
    @PrimaryKey val eventId: String,
    val scopeKey: String,
    val fromState: String? = null,
    val toState: String? = null,
    val observedAt: Long = 0L,
    val source: String,
    val evidenceId: String? = null,
)
