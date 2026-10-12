package dev.modelatlas.app.ui

import dev.modelatlas.app.data.AvailabilityInput
import dev.modelatlas.app.data.MyModelKey
import dev.modelatlas.app.data.OfferingInput
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.app.data.UsableProjection
import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.Modality
import dev.modelatlas.core.domain.ModelCategory
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.domain.canonicalIdFor

/**
 * P6 INT-2：Models 接線映射層（純 Kotlin，無 Android／Room 依賴）。
 *
 * TRACEABILITY：MODEL-001／002／003（canonical／alias／classification 合成展示列）、
 * MODEL-004（[ModelRow] 四層搜尋輸入）、MODEL-005（Filter Sheet 維度輸入）、
 * MODEL-006（hidden offering 不顯示）、MODEL-007（selected-key usable 投影）、
 * MODEL-009（price／quota head 讀取）。對應 PRODUCT_SPEC §2／§6／§8／§10。
 *
 * 合成維度（offerings＋availability＋classification＋price／quota heads）：
 * - priceState／quotaState 只吃傳入的 head／projection，絕不由 model 名稱或 id 推論 FREE。
 * - 缺資料維度一律 UNKNOWN（價格／配額／telemetry）或空集合（capabilities／modalities／
 *   aliasNames），不填零值、不捏造。目前無資料來源而恆缺的維度：
 *   vendor、contextTokens、success1h／24h、tps、ttftMs、latencyMs、priceChangeCount、
 *   priceChangedAt（無價格歷史）、freeSinceAt（head 未保存免費起算點）。
 * - aliasNames 只列同 canonical 的其他 providerModelId；不把正規化名冒充別名。
 * - classification 依 CATALOG > OBSERVED > MANUAL 取單一來源，不跨來源合併、不降級。
 * - 一個 offering 恰一列；hidden 或空白 providerModelId 略過。
 * - price／quota head 候選 state 必須全一致才取值，任一分歧或 0 候選回 UNKNOWN
 *   （與 AtlasRepository resolvePriceState 的 fail-closed 語意一致）；價格數值只在恰一候選時填入。
 */
object ModelRowMapper {

    const val KIND_PRICE = "PRICE"
    const val KIND_MANUAL_PRICE = "MANUAL_PRICE"
    const val KIND_ACCOUNT_QUOTA = "ACCOUNT_QUOTA"
    const val KIND_TOKEN_QUOTA = "TOKEN_QUOTA"
    const val KIND_MANUAL_QUOTA = "MANUAL_QUOTA"
    const val KIND_DEDICATED_PRICE = "DEDICATED_PRICE"
    const val KIND_DEDICATED_ACCOUNT_QUOTA = "DEDICATED_ACCOUNT_QUOTA"
    const val KIND_DEDICATED_TOKEN_QUOTA = "DEDICATED_TOKEN_QUOTA"

    val PRICE_KINDS: Set<String> = setOf(KIND_PRICE, KIND_MANUAL_PRICE, KIND_DEDICATED_PRICE)
    val QUOTA_KINDS: Set<String> = setOf(
        KIND_ACCOUNT_QUOTA,
        KIND_TOKEN_QUOTA,
        KIND_MANUAL_QUOTA,
        KIND_DEDICATED_ACCOUNT_QUOTA,
        KIND_DEDICATED_TOKEN_QUOTA,
    )

    /**
     * DBG1：member 包裝（VM 不需 import extension 即可 `ModelRowMapper.build`）。
     * 真實邏輯在同檔 `buildRows`；extension `ModelRowMapper.build` 保留給既有測試 import。
     */
    fun build(
        offerings: List<OfferingRef>,
        availabilities: List<AvailabilityRef>,
        selected: SelectedKey,
        canonicals: List<CanonicalRef> = emptyList(),
        aliases: List<AliasRef> = emptyList(),
        classifications: List<ClassificationRef> = emptyList(),
        priceQuotaHeads: List<PriceQuotaHead> = emptyList(),
    ): List<ModelRow> = buildPartitioned(
        offerings = offerings,
        availabilities = availabilities,
        selected = selected,
        canonicals = canonicals,
        aliases = aliases,
        classifications = classifications,
        priceQuotaHeads = priceQuotaHeads,
        includeHiddenFor = emptySet(),
    ).visible

    /** Visible holds the historical build() output; pendingMember holds hidden members hit by includeHiddenFor. */
    fun buildPartitioned(
        offerings: List<OfferingRef>,
        availabilities: List<AvailabilityRef>,
        selected: SelectedKey,
        canonicals: List<CanonicalRef> = emptyList(),
        aliases: List<AliasRef> = emptyList(),
        classifications: List<ClassificationRef> = emptyList(),
        priceQuotaHeads: List<PriceQuotaHead> = emptyList(),
        includeHiddenFor: Set<MyModelKey> = emptySet(),
    ): ModelRowsPartition = buildRows(
        offerings = offerings,
        availabilities = availabilities,
        selected = selected,
        canonicals = canonicals,
        aliases = aliases,
        classifications = classifications,
        priceQuotaHeads = priceQuotaHeads,
        includeHiddenFor = includeHiddenFor,
    )
}

/** Mapper result split: visible rows plus hidden member rows matched by includeHiddenFor. */
data class ModelRowsPartition(
    val visible: List<ModelRow>,
    val pendingMember: List<ModelRow>,
)

/** offering 合成輸入（對應 [dev.modelatlas.app.data.OfferingEntity]）。 */
data class OfferingRef(
    val id: String,
    val providerId: String,
    val endpointId: String,
    val providerModelId: String,
    val operation: String = "CHAT_COMPLETIONS",
    val manual: Boolean = true,
    val source: String = "MANUAL",
    val hidden: Boolean = false,
    val verified: Boolean = false,
)

/** availability 合成輸入（對應 [dev.modelatlas.app.data.CredentialAvailabilityEntity]）。 */
data class AvailabilityRef(
    val id: String,
    val offeringId: String,
    val accountId: String,
    val credentialId: String,
    val group: String? = null,
    val endpointId: String? = null,
    val operation: String? = null,
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val source: String = "MANUAL",
)

/** canonical 合成輸入（displayName 缺席即 null，不拿 providerModelId 冒充）。 */
data class CanonicalRef(
    val canonicalId: String,
    val displayName: String? = null,
)

/** alias 合成輸入（原始 provider model ID 永遠保留）。 */
data class AliasRef(
    val providerId: String,
    val providerModelId: String,
    val canonicalId: String,
)

/** classification 合成輸入（單一來源；capabilities／modalities 為 enum 名集合）。 */
data class ClassificationRef(
    val canonicalId: String,
    val source: String,
    val primaryCategory: String? = null,
    val capabilities: Set<String> = emptySet(),
    val modalities: Set<String> = emptySet(),
)

/**
 * price／quota head 合成輸入（由 snapshot_heads＋evidence 解析）。
 * [kind] 取 [KIND_PRICE] 等常數；[modelId] 對 price 為 offering 模型、對 quota 通常為空字串。
 * null scope 維度＝未知 scope，只匹配 offering 端同為未知者（fail-closed）。
 */
data class PriceQuotaHead(
    val kind: String,
    val providerId: String,
    val accountId: String? = null,
    val credentialId: String? = null,
    val modelId: String = "",
    val group: String? = null,
    val endpointId: String? = null,
    val operation: String? = null,
    val state: String = "UNKNOWN",
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val cachePrice: Double? = null,
    val observedAt: Long? = null,
    val quotaSlice: String? = null,
)

/** offerings＋availability＋classification＋price／quota heads → [ModelRowsPartition]（DBG1：本體；member／extension 經 buildPartitioned 轉呼此）。 */
private fun buildRows(
    offerings: List<OfferingRef>,
    availabilities: List<AvailabilityRef>,
    selected: SelectedKey,
    canonicals: List<CanonicalRef> = emptyList(),
    aliases: List<AliasRef> = emptyList(),
    classifications: List<ClassificationRef> = emptyList(),
    priceQuotaHeads: List<PriceQuotaHead> = emptyList(),
    includeHiddenFor: Set<MyModelKey> = emptySet(),
): ModelRowsPartition {
    val usableByOffering = UsableProjection.usableOfferings(
        offerings = offerings.map {
            OfferingInput(
                id = it.id,
                providerId = it.providerId,
                endpointId = it.endpointId,
                providerModelId = it.providerModelId,
                manual = it.manual,
                source = it.source,
                operation = it.operation,
            )
        },
        availabilities = availabilities.map {
            AvailabilityInput(
                id = it.id,
                offeringId = it.offeringId,
                accountId = it.accountId,
                credentialId = it.credentialId,
                group = it.group,
                endpointId = it.endpointId,
                operation = it.operation,
                priceState = it.priceState,
                quotaState = it.quotaState,
                source = it.source,
            )
        },
        selected = selected,
    ).associateBy { it.offeringId }

    val canonicalById = canonicals.associateBy { it.canonicalId }
    val aliasesByCanonical = aliases.groupBy { it.canonicalId }
    val aliasByKey = aliases.associateBy { keyOf(it.providerId, it.providerModelId) }
    val classificationsByCanonical = classifications.groupBy { it.canonicalId }
    val priceHeads = priceQuotaHeads.filter { it.kind in ModelRowMapper.PRICE_KINDS }
    val quotaHeads = priceQuotaHeads.filter { it.kind in ModelRowMapper.QUOTA_KINDS }

    val included = offerings.asSequence()
        .filter { !it.hidden || MyModelKey(it.providerId, it.providerModelId) in includeHiddenFor }
        .filter { it.providerModelId.isNotBlank() && it.providerId.isNotBlank() }
    val visibleRows = ArrayList<ModelRow>()
    val pendingMemberRows = ArrayList<ModelRow>()
    included.forEach { offering ->
        val alias = aliasByKey[keyOf(offering.providerId, offering.providerModelId)]
        val canonicalId = alias?.canonicalId ?: canonicalIdFor(offering.providerModelId)
        val canonical = canonicalById[canonicalId]
        val aliasNames = aliasesByCanonical[canonicalId].orEmpty()
            .filterNot { it.providerId == offering.providerId && it.providerModelId == offering.providerModelId }
            .map { it.providerModelId }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val classification = pickClassification(classificationsByCanonical[canonicalId].orEmpty())
        val capabilities = classification?.capabilities.orEmpty()
            .mapNotNull { enumOrNull<Capability>(it) }
            .toSet()
        val modalities = classification?.modalities.orEmpty()
            .mapNotNull { enumOrNull<Modality>(it) }
            .toSet()
        val usable = usableByOffering[offering.id]

        val matchingPrices = priceHeads.filter { matchesPriceHead(it, offering, selected) }
        val matchingQuotas = matchingQuotaHeads(quotaHeads, offering, selected)
        val priceState = consensusState(matchingPrices.map { it.state })
            ?.let { enumOrNull<PriceState>(it) }
            ?: enumOrNull<PriceState>(usable?.priceState)
            ?: PriceState.UNKNOWN
        val quotaState = consensusState(matchingQuotas.map { it.state })
            ?.let { enumOrNull<QuotaState>(it) }
            ?: enumOrNull<QuotaState>(usable?.quotaState)
            ?: QuotaState.UNKNOWN
        val priceNumbers = matchingPrices.singleOrNull()
        val observedAt = (matchingPrices + matchingQuotas).mapNotNull { it.observedAt }.maxOrNull()

        val row = ModelRow(
            providerId = offering.providerId,
            providerModelId = offering.providerModelId,
            canonicalId = canonicalId,
            aliasNames = aliasNames,
            displayName = canonical?.displayName?.takeIf { it.isNotBlank() } ?: offering.providerModelId,
            priceState = priceState,
            quotaState = quotaState,
            inputPrice = priceNumbers?.inputPrice,
            outputPrice = priceNumbers?.outputPrice,
            cachePrice = priceNumbers?.cachePrice,
            group = selected.group,
            vendor = null,
            primaryCategory = classification?.primaryCategory?.let { enumOrNull<ModelCategory>(it) },
            capabilities = capabilities,
            modalities = modalities,
            hasTools = Capability.TOOL_CALLING in capabilities || Capability.FUNCTION_CALLING in capabilities,
            supportsVision = Capability.VISION in capabilities,
            supportsFiles = Capability.FILES in capabilities,
            supportsReasoning = Capability.REASONING in capabilities,
            sourceLabel = sourceLabel(offering.source, offering.manual),
            verified = offering.verified,
            addedAt = null,
            updatedAt = observedAt,
            priceChangedAt = null,
            priceChangeCount = null,
            freeSinceAt = null,
            selectedKeyUsable = usable?.hasAvailability == true,
            endpointId = offering.endpointId,
            operation = offering.operation,
        )
        if (offering.hidden) pendingMemberRows.add(row) else visibleRows.add(row)
    }
    return ModelRowsPartition(visibleRows, pendingMemberRows)
}

/**
 * DBG1：既有測試相容保留（`import dev.modelatlas.app.ui.build` 繼續有效）。
 * 與 member [ModelRowMapper.build] 同簽名、經 buildPartitioned 取 visible；member 優先，不影響 VM。
 */
fun ModelRowMapper.build(
    offerings: List<OfferingRef>,
    availabilities: List<AvailabilityRef>,
    selected: SelectedKey,
    canonicals: List<CanonicalRef> = emptyList(),
    aliases: List<AliasRef> = emptyList(),
    classifications: List<ClassificationRef> = emptyList(),
    priceQuotaHeads: List<PriceQuotaHead> = emptyList(),
): List<ModelRow> = buildPartitioned(
    offerings = offerings,
    availabilities = availabilities,
    selected = selected,
    canonicals = canonicals,
    aliases = aliases,
    classifications = classifications,
    priceQuotaHeads = priceQuotaHeads,
    includeHiddenFor = emptySet(),
).visible

private fun keyOf(providerId: String, providerModelId: String): String =
    providerId + "\u0000" + providerModelId

private val classificationRank = mapOf(
    "CATALOG" to 2,
    "OBSERVED" to 1,
    "MANUAL" to 0,
)

private fun pickClassification(candidates: List<ClassificationRef>): ClassificationRef? =
    candidates.maxByOrNull { classificationRank[it.source.uppercase()] ?: -1 }
        ?.takeIf { (classificationRank[it.source.uppercase()] ?: -1) >= 0 }

private fun consensusState(states: List<String>): String? {
    if (states.isEmpty()) return null
    val normalized = states.map { it.trim().uppercase() }
    val first = normalized.first()
    return if (normalized.all { it == first }) first else "UNKNOWN"
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { runCatching { enumValueOf<T>(it.trim().uppercase()) }.getOrNull() }

/**
 * 來源標籤：manual／official／observed／external 透傳（小寫）；未知回空字串
 * （UI 以「未知」明示，不冒充）。MANUAL／USER 家族一律 manual。
 */
internal fun sourceLabel(source: String, manual: Boolean): String = when (source.trim().uppercase()) {
    "MANUAL", "USER" -> "manual"
    "OFFICIAL" -> "official"
    "OBSERVED" -> "observed"
    "EXTERNAL" -> "external"
    "" -> if (manual) "manual" else ""
    else -> ""
}

/**
 * Price head 精確 scope 比對（CR-004 B1；policy 與 `AtlasRepository.resolvePriceState` 同步，
 * 兩處以本 KDoc 互相參照、共用同一 locked policy）。
 * 既有 provider/model/account/credential 檢查不變；新增／收緊：
 * - **Group exact（B1）**：`head.group != selected.group` 即排除——raw 精確比對
 *   （null==null；null ≠ "" ≠ 具體值），對 group 不做 blank/UNKNOWN 正規化，
 *   同 `AtlasRepository.deriveFreePriceState` HIGH-2 語意。selected.group 為 null
 *   （未知）時只命中 null-group head，絕不借具體 group 的 state。
 * - **Operation exact（B1）**：head.operation 未知 scope（null／blank／"UNKNOWN"）
 *   只匹配 offering.operation 同為未知 scope（blank／"UNKNOWN"）；具體值須與
 *   offering.operation（非 null String）完全相等。
 * - **Blank endpoint 收緊（B1）**：head.endpointId null **或 blank**（或 "UNKNOWN"）
 *   皆為未知 scope，只匹配未知 scope 的 offering.endpointId；具體值須完全相等。
 *   移除舊實作中 blank head endpoint 落入「具體比對被跳過」的繞過。
 * 未知 scope ≠ wildcard；比對語意同 `UsableProjection.matchesSelected` 的 fail-closed 慣例。
 */
private fun matchesPriceHead(
    head: PriceQuotaHead,
    offering: OfferingRef,
    selected: SelectedKey,
): Boolean {
    if (head.providerId != offering.providerId) return false
    if (head.modelId != offering.providerModelId) return false
    // B1 group：raw exact（null==null；null ≠ "" ≠ 具體值；不正常化）。
    if (head.group != selected.group) return false
    // B1 endpoint：未知 scope（null/blank/"UNKNOWN"）只配未知 scope；具體 exact。
    if (!headDimensionMatches(head.endpointId, offering.endpointId)) return false
    // B1 operation：未知 scope 只配未知 scope；具體 exact。
    if (!headDimensionMatches(head.operation, offering.operation)) return false
    // Price scope 的 credentialId：官方（PAT）定價恆 null，與呼叫端選擇的 key 無關
    // （同 AtlasRepository resolvePriceState）；非空者只匹配同 credential。
    val credential = head.credentialId
    if (credential != null && credential != selected.credentialId) return false
    val account = head.accountId
    if (selected.accountId.isNotBlank()) {
        if (account != selected.accountId) return false
    } else if (account != null && account.isNotBlank()) {
        return false
    }
    return true
}

/**
 * Quota head 選擇（CR-004 B2；policy 與 `AtlasRepository` 讀端同步）。
 * - **保留 token-over-account tier**：token-scope（credentialId==selected.credentialId
 *   且非 null）候選優先；僅該 tier 經維度閘門後為空才回退 credentialId==null tier。
 * - **tier 內維度閘門（B2）**：
 *   - **Declared global pool head**（真正宣告的 account/key-wide pool，寫入端慣例
 *     `DedicatedSnapshotSync.accountQuotaScope/tokenQuotaScope`＋`NewApiSnapshotStore`）：
 *     passport 簽名＝`head.modelId == ""`（空字串 marker；null ≠ ""）＋ endpoint 未知 scope
 *     （null/blank/"UNKNOWN"）＋ operation 未知 scope（含 `OPERATION_GLOBAL="UNKNOWN"`）。
 *     此類 head 在 tier 內匹配任意 offering model/endpoint/operation；其 group 為 null 表
 *     account/key-wide pool（匹配任何 selected.group）、具體 group 仍須與 selected.group
 *     raw exact（寫入端僅對 group-scoped pool 設 group）。
 *   - **Non-pool head**（pool 簽名之外的任何具體／未知維度）：一律 fail-closed exact——
 *     modelId 具體者須等於 offering.providerModelId；modelId null 排除（未知 scope 不是
 *     自動 wildcard）；group raw exact（null 只配 null selected.group）；endpoint／
 *     operation 未知 scope 只配未知 scope 的 offering 端、具體值 exact。
 * - **Pool passport 與 unknown scope 分離**：empty-model＋unknown-endpoint＋global-operation
 *   是唯一 pool 護照；parse-degraded／部分 scope head（如具體 modelId 配 null group、
 *   或 null modelId）一律 fail-closed 排除，絕不當 wildcard。
 * 兩組各自 state 一致才取值（fail-closed，見 [consensusState] 呼叫端）。
 */
private fun matchingQuotaHeads(
    heads: List<PriceQuotaHead>,
    offering: OfferingRef,
    selected: SelectedKey,
): List<PriceQuotaHead> {
    fun baseMatches(head: PriceQuotaHead): Boolean {
        if (head.providerId != offering.providerId) return false
        val account = head.accountId
        if (selected.accountId.isNotBlank()) {
            if (account != selected.accountId) return false
        } else if (account != null && account.isNotBlank()) {
            return false
        }
        return true
    }

    fun withinTierMatches(head: PriceQuotaHead): Boolean {
        if (isDeclaredGlobalPool(head)) {
            return head.group == null || head.group == selected.group
        }
        if (head.modelId != offering.providerModelId) return false
        if (head.group != selected.group) return false
        return headDimensionMatches(head.endpointId, offering.endpointId) &&
            headDimensionMatches(head.operation, offering.operation)
    }

    val tokenScoped = heads.filter {
        baseMatches(it) && it.credentialId != null && it.credentialId == selected.credentialId &&
            withinTierMatches(it)
    }
    if (tokenScoped.isNotEmpty()) return tokenScoped
    return heads.filter { baseMatches(it) && it.credentialId == null && withinTierMatches(it) }
}

/**
 * Head 單一 scope 維度（endpoint／operation）比對：head 值未知 scope（null／blank／
 * "UNKNOWN"）只匹配未知 scope 的 offering 端；具體值須與 offering 端完全相等。
 * 未知 ≠ wildcard。
 */
private fun headDimensionMatches(headDim: String?, offeringDim: String): Boolean =
    if (isUnknownScopeDim(headDim)) isUnknownScope(offeringDim) else headDim == offeringDim

/** null 亦視為未知 scope（head 端欄位可空；offering 端 endpoint/operation 為非 null String）。 */
private fun isUnknownScopeDim(raw: String?): Boolean = raw == null || isUnknownScope(raw)

/**
 * Declared global pool passport（CR-004 B2）：`modelId == ""`（寫入端 pool marker，
 * `DedicatedSnapshotSync`/`NewApiSnapshotStore` 慣例；null ≠ ""）＋ endpoint 未知 scope
 * ＋ operation 未知 scope（含 `OPERATION_GLOBAL="UNKNOWN"`）。只有此完整簽名是 pool；
 * 部分 scope／parse-degraded head 非 pool、走 fail-closed exact。
 */
private fun isDeclaredGlobalPool(head: PriceQuotaHead): Boolean =
    head.modelId == "" &&
        isUnknownScopeDim(head.endpointId) &&
        isUnknownScopeDim(head.operation)

private fun isUnknownScope(raw: String): Boolean {
    val normalized = raw.trim().uppercase()
    return normalized.isEmpty() || normalized == "UNKNOWN"
}
