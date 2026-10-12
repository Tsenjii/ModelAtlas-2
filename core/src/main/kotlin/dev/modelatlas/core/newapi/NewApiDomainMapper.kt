package dev.modelatlas.core.newapi

import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot
import dev.modelatlas.core.domain.QuotaState
import java.math.BigDecimal
import java.math.MathContext
import java.security.MessageDigest

/**
 * New API → domain typed mapping layer（Phase 4）。
 *
 * 研究依據：`docs/ADAPTER_NEWAPI_RESEARCH.md`（2026-09-27，New API `v1.0.0-rc.25`）。
 *
 * 設計原則（全部是純函式，無 IO、無 vault、無 clock、無網路）：
 * - 只吃 adapter 已清洗的 typed safe results（[NewApiResult]），絕不吃 raw JSON / raw body /
 *   server `message` / secret / PII。`username`/`email` 在 adapter 已排除，本層連欄位都不收。
 * - **絕不 eval `billing_expr`**：`billing_mode == "tiered_expr"` 且 expr 非空時，所有價格腿一律
 *   null（[NewApiPriceUnpricedReason.BILLING_EXPR_PRESENT]），只在 detail 存原文 + SHA-256，
 *   絕不呼叫 [PricingExpressionParser]。無論 expr 長得像 `1+1` 也不求值。
 * - **Unknown 保持 null**：缺欄位、非數值 ratio、未知 `quota_type`、未知 `quota_per_unit`、
 *   未知 group ratio 一律 null legs，絕不用 0 代替。0 只在官方數字確實算出 0 時才出現。
 * - **PAT/account 與 inference key 角色分離**：pricing / account quota 只收 [NewApiAccountScope]
 *  （有 `accountId`、無 `credentialId`，產出 scope 的 `credentialId` 必為 null）；
 *   token quota 只收 [NewApiKeyScope]（有 `credentialId`、無 `accountId`，產出 scope 的
 *   `accountId` 必為 null）。編譯期型別即強制分離，誤傳會在參數型別層面被擋下。
 * - **pricing envelope variants 嚴格處理**：
 *   `Success(data:[])` 是官方權威空清單（[NewApiPricingMapping.Mapped.authoritativeEmpty]），
 *   與 `Failure(401/403/…)`（[NewApiPricingMapping.Unmappable]，呼叫端必須保留 last-good、
 *   不得清空）明確區分；`pricing_version` 頂層與 `data[0]` 內各存各、不合併；
 *   `group_ratio` number / string（`auto` 等在地化字串）以 [NewApiRatio.nonNumeric] 區分，
 *   字串一律當 UNKNOWN 不猜數值；`enable_groups` 含 `all` 展開為 `usable_group`（`usable` 空時
 *   只產單一 `group=null` 的 UNKNOWN 快照，不捏造群組）；`supported_endpoint_types` 空時只產
 *   單一 `operation="UNKNOWN"` 快照，不猜 `CHAT_COMPLETIONS`。
 * - **完整 [PricingScope]**：Provider × Account/Credential × Model × Group × Endpoint ×
 *   Operation × Time（時間在 `observedAt`）。不同 group / operation 即不同 scope，絕不互相覆蓋。
 *   價格換算只用同一次 session 的 `/api/status.quota_per_unit`；未知時不用預設 500000 硬塞。
 * - **Evidence metadata**：`snapshot.observedAt`、`snapshot.source` 取自 [NewApiEvidence]；
 *   `snapshot.evidenceId` 為衍生確定性 id（`newapi:ev:…`），完整 version / pricingVersion /
 *   httpStatus 保留在 mapping result 的 `evidence` 與 detail，不進 snapshot 欄位也不丟失。
 * - **現有 domain 不足處以 safe DTO 補齊，不假寫 Room**：[PriceSnapshot] 無 image/audio 腿、
 *   無 billing/vendor/pricingVersion/groupRatio 欄；[QuotaSnapshot] 只有 `state` 無整數餘額欄。
 *   這些全部保留在 [NewApiPriceDetail] / [NewApiAccountQuotaDetail] / [NewApiTokenQuotaDetail]，
 *   只含安全數字與 safe refs，不含 secret / raw body / PII。
 *
 * 單位（文件化，避免誤讀）：
 * - per-token 腿（`input`/`output`/`cacheRead`/`cacheWrite`）：USD / 1K tokens，
 *   公式 `ratioLeg × groupRatio × 1000 / quotaPerUnit`（研究 §4 等價式），全程 BigDecimal(String)
 *   精確運算，除法用 DECIMAL128，不經 Double/float。
 * - `fixedRequest`（`quota_type=1`）：USD / 次，公式 `model_price × groupRatio`
 *  （`model_price` 本就是 USD/次，研究 §4.2；`quotaPerUnit` 在此式中約掉，故 Q 未知時 fixed 仍可知）。
 * - 正負：負值照實保留（由 `PricingPolicy` 判 UNKNOWN，不在此鉗制）；0 照實保留
 *  （由 `PricingPolicy` 依完整 scope 判 FREE_NOW，絕不只看 `model_ratio==0`）。
 */
object NewApiDomainMapper {
    private val THOUSAND = BigDecimal("1000")
    private val DIV_CTX: MathContext = MathContext.DECIMAL128
    private const val TIERED_EXPR_MODE = "tiered_expr"
    private const val ALL_GROUPS = "all"

    // ---- safe scope contexts（角色分離） ----

    // ---- pricing ----

    /**
     * `GET /api/pricing`（PAT）→ domain。`quotaPerUnit` 必須是同一次 session 的
     * `/api/status` 值；null / <=0 即該次 ratio 腿全 UNKNOWN（fixed 腿不受 Q 影響）。
     * 絕不用預設 500000。
     */
    fun mapPricing(
        pricingResult: NewApiResult<NewApiPricing>,
        quotaPerUnit: BigDecimal?,
        scope: NewApiAccountScope,
    ): NewApiPricingMapping {
        requireSafeId(scope.providerId, "providerId")
        requireSafeId(scope.accountId, "accountId")
        requireSafeId(scope.endpointId, "endpointId")
        val q = quotaPerUnit?.takeIf { it.signum() > 0 }
        return when (pricingResult) {
            is NewApiResult.Failure -> NewApiPricingMapping.Unmappable(
                status = pricingResult.status,
                evidence = pricingResult.evidence,
            )
            is NewApiResult.Success -> {
                val evidence = pricingResult.evidence
                val pricing = pricingResult.value
                val usable = pricing.usableGroups.distinct()
                val usableEmpty = usable.isEmpty()
                if (pricing.items.isEmpty()) {
                    return NewApiPricingMapping.Mapped(
                        snapshots = emptyList(),
                        details = emptyList(),
                        evidence = evidence,
                        authoritativeEmpty = true,
                        usableGroupsEmpty = usableEmpty,
                        skippedItems = pricing.skippedItems,
                        usableGroups = usable,
                        supportedEndpoints = pricing.supportedEndpoint,
                        autoGroups = pricing.autoGroups,
                        topPricingVersion = pricing.pricingVersion,
                    )
                }
                val snapshots = ArrayList<PriceSnapshot>(pricing.items.size * 2)
                val details = ArrayList<NewApiPriceDetail>(pricing.items.size * 2)
                val evidenceId = pricingEvidenceId(evidence)
                pricing.items.forEachIndexed { itemIndex, item ->
                    val groups = applicableGroups(item.enableGroups, usable)
                    val operations = applicableOperations(item.supportedEndpointTypes)
                    for (group in groups) {
                        val groupResolution = resolveGroupRatio(group, pricing.groupRatio)
                        for ((rawType, mappedType, operation) in operations) {
                            val computed = computeLegs(item, groupResolution, q)
                            val snapshotScope = PricingScope(
                                providerId = scope.providerId,
                                accountId = scope.accountId,
                                credentialId = null,
                                modelId = item.modelName,
                                group = group,
                                endpointId = scope.endpointId,
                                operation = operation,
                            )
                            val snapshotId = priceSnapshotId(
                                providerId = scope.providerId,
                                endpointId = scope.endpointId,
                                modelId = item.modelName,
                                group = group,
                                operation = operation,
                                observedAt = evidence.observedAt,
                                index = itemIndex,
                            )
                            snapshots += PriceSnapshot(
                                id = snapshotId,
                                scope = snapshotScope,
                                observedAt = evidence.observedAt,
                                input = computed.input,
                                output = computed.output,
                                fixedRequest = computed.fixedRequest,
                                fixedRequestKnownAbsent = computed.fixedKnownAbsent,
                                cacheRead = computed.cacheRead,
                                cacheWrite = computed.cacheWrite,
                                source = evidence.source,
                                evidenceId = evidenceId,
                            )
                            details += NewApiPriceDetail(
                                snapshotId = snapshotId,
                                scope = snapshotScope,
                                quotaType = item.quotaType,
                                modelRatio = item.modelRatio,
                                modelPrice = item.modelPrice,
                                completionRatio = item.completionRatio,
                                cacheRatio = item.cacheRatio,
                                createCacheRatio = item.createCacheRatio,
                                imageRatio = item.imageRatio,
                                audioRatio = item.audioRatio,
                                audioCompletionRatio = item.audioCompletionRatio,
                                billingMode = item.billingMode,
                                billingExpression = item.billingExpression,
                                billingExpressionHash = sha256HexOrNull(item.billingExpression),
                                vendorId = item.vendorId,
                                itemPricingVersion = item.pricingVersion,
                                topPricingVersion = pricing.pricingVersion,
                                groupRatioExact = groupResolution.exact,
                                groupRatioNonNumeric = groupResolution.nonNumeric,
                                endpointTypeRaw = rawType,
                                endpointTypeMapped = mappedType,
                                operation = operation,
                                quotaPerUnitUsed = if (item.quotaType == 1) null else q,
                                unpricedReason = computed.reason,
                            )
                        }
                    }
                }
                NewApiPricingMapping.Mapped(
                    snapshots = snapshots,
                    details = details,
                    evidence = evidence,
                    authoritativeEmpty = false,
                    usableGroupsEmpty = usableEmpty,
                    skippedItems = pricing.skippedItems,
                    usableGroups = usable,
                    supportedEndpoints = pricing.supportedEndpoint,
                    autoGroups = pricing.autoGroups,
                    topPricingVersion = pricing.pricingVersion,
                )
            }
        }
    }

    /**
     * `GET /api/user/self`（PAT）→ domain。帳號層級：`scope.accountId` 必有值、
     * `scope.credentialId` 必為 null（與 key 層級永不混用）。`modelId=""`、`operation="UNKNOWN"`
     * 表示全域（非單模型 / 非單 operation），`group` 取帳號預設群組（可 null）。
     *
     * State 為保守啟發式並文件化：`quota==null → UNKNOWN`；`quota<=0 → QUOTA_EXHAUSTED`；
     * 其餘正整數 → `QUOTA_AVAILABLE`。`used_quota` / `request_count` 只保存在 detail，不參與判定，
     * 避免猜測剩餘語義。0 與 null 絕不混同。
     */
    fun mapAccountQuota(
        result: NewApiResult<NewApiAccountQuota>,
        scope: NewApiAccountScope,
    ): NewApiAccountQuotaMapping {
        requireSafeId(scope.providerId, "providerId")
        requireSafeId(scope.accountId, "accountId")
        requireSafeId(scope.endpointId, "endpointId")
        return when (result) {
            is NewApiResult.Failure -> NewApiAccountQuotaMapping.Unmappable(
                status = result.status,
                evidence = result.evidence,
            )
            is NewApiResult.Success -> {
                val evidence = result.evidence
                val value = result.value
                val state = when {
                    value.quota == null -> QuotaState.UNKNOWN
                    value.quota <= 0L -> QuotaState.QUOTA_EXHAUSTED
                    else -> QuotaState.QUOTA_AVAILABLE
                }
                val snapshotScope = PricingScope(
                    providerId = scope.providerId,
                    accountId = scope.accountId,
                    credentialId = null,
                    modelId = "",
                    group = value.group,
                    endpointId = scope.endpointId,
                    operation = OPERATION_GLOBAL,
                )
                val snapshotId = accountQuotaSnapshotId(
                    providerId = scope.providerId,
                    accountId = scope.accountId,
                    observedAt = evidence.observedAt,
                )
                val snapshot = QuotaSnapshot(
                    id = snapshotId,
                    scope = snapshotScope,
                    observedAt = evidence.observedAt,
                    state = state,
                    source = evidence.source,
                    evidenceId = quotaEvidenceId(evidence, "account"),
                )
                val detail = NewApiAccountQuotaDetail(
                    snapshotId = snapshotId,
                    scope = snapshotScope,
                    quota = value.quota,
                    usedQuota = value.usedQuota,
                    requestCount = value.requestCount,
                    group = value.group,
                    role = value.role,
                    status = value.status,
                )
                NewApiAccountQuotaMapping.Mapped(
                    snapshot = snapshot,
                    detail = detail,
                    evidence = evidence,
                )
            }
        }
    }

    /**
     * `GET /api/usage/token/`（inference key）→ domain。Key 層級：`scope.credentialId` 必有值、
     * `scope.accountId` 必為 null（與 PAT/account 層級永不混用）。`modelId=""`、
     * `operation="UNKNOWN"` 表示全域。`state` 直接沿用 adapter 依研究 §3.10 的推導
     *（`UNLIMITED_CLAIMED` / `QUOTA_EXHAUSTED` / `QUOTA_AVAILABLE` / `UNKNOWN`），本層不重算；
     * `expires_at==0` 保持 [NewApiExpiryMeaning.ZERO_AMBIGUOUS]，不猜永不過期。
     */
    fun mapTokenQuota(
        result: NewApiResult<NewApiTokenQuota>,
        scope: NewApiKeyScope,
    ): NewApiTokenQuotaMapping {
        requireSafeId(scope.providerId, "providerId")
        requireSafeId(scope.credentialId, "credentialId")
        requireSafeId(scope.endpointId, "endpointId")
        return when (result) {
            is NewApiResult.Failure -> NewApiTokenQuotaMapping.Unmappable(
                status = result.status,
                evidence = result.evidence,
            )
            is NewApiResult.Success -> {
                val evidence = result.evidence
                val value = result.value
                val snapshotScope = PricingScope(
                    providerId = scope.providerId,
                    accountId = null,
                    credentialId = scope.credentialId,
                    modelId = "",
                    group = null,
                    endpointId = scope.endpointId,
                    operation = OPERATION_GLOBAL,
                )
                val snapshotId = tokenQuotaSnapshotId(
                    providerId = scope.providerId,
                    credentialId = scope.credentialId,
                    observedAt = evidence.observedAt,
                )
                val snapshot = QuotaSnapshot(
                    id = snapshotId,
                    scope = snapshotScope,
                    observedAt = evidence.observedAt,
                    state = value.state,
                    source = evidence.source,
                    evidenceId = quotaEvidenceId(evidence, "token"),
                )
                val detail = NewApiTokenQuotaDetail(
                    snapshotId = snapshotId,
                    scope = snapshotScope,
                    totalGranted = value.totalGranted,
                    totalUsed = value.totalUsed,
                    totalAvailable = value.totalAvailable,
                    unlimitedQuota = value.unlimitedQuota,
                    modelLimitsEnabled = value.modelLimitsEnabled,
                    expiresAtRaw = value.expiresAtRaw,
                    expiryMeaning = value.expiryMeaning,
                    expired = value.expired,
                )
                NewApiTokenQuotaMapping.Mapped(
                    snapshot = snapshot,
                    detail = detail,
                    evidence = evidence,
                )
            }
        }
    }

    /**
     * `/api/status` 成功結果的 `quota_per_unit` 安全取出器。Failure / 缺欄位即 null，
     * 絕不回預設 500000。呼叫端必須保證傳給 [mapPricing] 的是同一次 session 的值。
     */
    fun quotaPerUnitOf(capabilities: NewApiResult<NewApiCapabilities>): BigDecimal? =
        when (capabilities) {
            is NewApiResult.Failure -> null
            is NewApiResult.Success -> capabilities.value.quotaPerUnit?.takeIf { it.signum() > 0 }
        }

    /**
     * New API endpoint type → [EndpointType]（研究 §3.7，rc.25 已確認 8 列，大小寫敏感、精確匹配）。
     * 未列（`image-edit`、audio 相關、`openai-alpha-search` 等在 rc.25 預設 path 表未見者）一律
     * [EndpointType.CUSTOM]，不猜 [EndpointType.IMAGES_EDIT] / [EndpointType.AUDIO]。
     * `/api/pricing.supported_endpoint` 的 path 解析是呼叫端的 endpoint 選路責任，本函數只負責
     * operation 維度的分類。
     */
    fun mapEndpointType(raw: String): EndpointType = when (raw) {
        "openai" -> EndpointType.CHAT_COMPLETIONS
        "openai-response", "openai-response-compact" -> EndpointType.RESPONSES
        "anthropic" -> EndpointType.ANTHROPIC_MESSAGES
        "gemini" -> EndpointType.GEMINI_NATIVE
        "image-generation" -> EndpointType.IMAGES_GENERATION
        "embeddings" -> EndpointType.EMBEDDINGS
        "jina-rerank" -> EndpointType.RERANK
        else -> EndpointType.CUSTOM
    }

    // ---- internals ----

    private data class GroupResolution(
        val exact: BigDecimal?,
        val nonNumeric: Boolean,
        val reason: NewApiPriceUnpricedReason?,
    )

    private data class ComputedLegs(
        val input: BigDecimal?,
        val output: BigDecimal?,
        val fixedRequest: BigDecimal?,
        val fixedKnownAbsent: Boolean,
        val cacheRead: BigDecimal?,
        val cacheWrite: BigDecimal?,
        val reason: NewApiPriceUnpricedReason?,
    )

    private data class OperationSlot(
        val raw: String?,
        val mapped: EndpointType?,
        val operation: String,
    )

    private fun applicableGroups(enableGroups: List<String>, usableGroups: List<String>): List<String?> {
        val enable = enableGroups.distinct()
        val usable = usableGroups.distinct()
        return when {
            enable.contains(ALL_GROUPS) -> if (usable.isEmpty()) listOf(null) else usable
            enable.isNotEmpty() -> enable
            else -> listOf(null)
        }
    }

    private fun applicableOperations(rawTypes: List<String>): List<OperationSlot> {
        val distinct = rawTypes.distinct()
        if (distinct.isEmpty()) return listOf(OperationSlot(null, null, OPERATION_UNKNOWN))
        return distinct.map { raw ->
            val mapped = mapEndpointType(raw)
            OperationSlot(raw, mapped, mapped.name)
        }
    }

    private fun resolveGroupRatio(group: String?, ratios: Map<String, NewApiRatio>): GroupResolution {
        if (group == null) {
            return GroupResolution(null, false, NewApiPriceUnpricedReason.GROUP_RATIO_UNKNOWN)
        }
        val ratio = ratios[group]
            ?: return GroupResolution(null, false, NewApiPriceUnpricedReason.GROUP_RATIO_UNKNOWN)
        if (ratio.nonNumeric || ratio.exact == null) {
            return GroupResolution(null, true, NewApiPriceUnpricedReason.GROUP_RATIO_NON_NUMERIC)
        }
        if (ratio.exact.signum() < 0) {
            return GroupResolution(null, false, NewApiPriceUnpricedReason.GROUP_RATIO_NEGATIVE)
        }
        return GroupResolution(ratio.exact, false, null)
    }

    private fun computeLegs(
        item: NewApiPricingItem,
        group: GroupResolution,
        quotaPerUnit: BigDecimal?,
    ): ComputedLegs {
        // tiered_expr 永不求值（即使 expr 看似 `1+1`）。
        if (item.billingMode == TIERED_EXPR_MODE) {
            return if (item.billingExpression != null) {
                ComputedLegs(null, null, null, false, null, null, NewApiPriceUnpricedReason.BILLING_EXPR_PRESENT)
            } else {
                ComputedLegs(null, null, null, false, null, null, NewApiPriceUnpricedReason.BILLING_EXPR_MISSING)
            }
        }
        return when (item.quotaType) {
            0 -> computeRatioLegs(item, group, quotaPerUnit)
            1 -> computeFixedLegs(item, group)
            else -> ComputedLegs(null, null, null, false, null, null, NewApiPriceUnpricedReason.QUOTA_TYPE_UNKNOWN)
        }
    }

    private fun computeRatioLegs(
        item: NewApiPricingItem,
        group: GroupResolution,
        quotaPerUnit: BigDecimal?,
    ): ComputedLegs {
        if (quotaPerUnit == null) {
            return ComputedLegs(null, null, null, true, null, null, NewApiPriceUnpricedReason.QUOTA_PER_UNIT_UNKNOWN)
        }
        if (group.reason != null || group.exact == null) {
            return ComputedLegs(
                null, null, null, true, null, null,
                group.reason ?: NewApiPriceUnpricedReason.GROUP_RATIO_UNKNOWN,
            )
        }
        val modelRatio = item.modelRatio
            ?: return ComputedLegs(null, null, null, true, null, null, NewApiPriceUnpricedReason.MODEL_RATIO_MISSING)
        val g = group.exact
        // input 缺 model_ratio 即 null；output 另需 completion_ratio；cache 腿各需其 ratio。
        // 單腿缺失不連累其他已知腿（input 可知而 output 未知是合法的 UNKNOWN 狀態）。
        val input = safeRatioPrice(modelRatio, g, quotaPerUnit)
        val output = item.completionRatio?.let { safeRatioPrice(modelRatio.multiply(it), g, quotaPerUnit) }
        val cacheRead = item.cacheRatio?.let { safeRatioPrice(modelRatio.multiply(it), g, quotaPerUnit) }
        val cacheWrite = item.createCacheRatio?.let { safeRatioPrice(modelRatio.multiply(it), g, quotaPerUnit) }
        val reason = if (item.completionRatio == null) {
            NewApiPriceUnpricedReason.COMPLETION_RATIO_MISSING
        } else {
            null
        }
        return ComputedLegs(input, output, null, true, cacheRead, cacheWrite, reason)
    }

    private fun computeFixedLegs(item: NewApiPricingItem, group: GroupResolution): ComputedLegs {
        if (group.reason != null || group.exact == null) {
            return ComputedLegs(
                null, null, null, false, null, null,
                group.reason ?: NewApiPriceUnpricedReason.GROUP_RATIO_UNKNOWN,
            )
        }
        val price = item.modelPrice
            ?: return ComputedLegs(null, null, null, false, null, null, NewApiPriceUnpricedReason.MODEL_PRICE_MISSING)
        // USD/次 = model_price × groupRatio；quotaPerUnit 在此約掉，故 Q 未知時仍可定價。
        // 負值照實保留（PricingPolicy 會判 UNKNOWN，不在此鉗制）。
        val fixed = price.multiply(group.exact)
        // quota_type=1 確定無 per-token 計費：input/output/cache 一律 null（非 0），
        // fixedKnownAbsent=false（有值或未知，不宣稱 absent）。
        return ComputedLegs(null, null, fixed, false, null, null, null)
    }

    private fun safeRatioPrice(scaledRatio: BigDecimal, groupRatio: BigDecimal, quotaPerUnit: BigDecimal): BigDecimal {
        // USD/1K = scaledRatio × groupRatio × 1000 / quotaPerUnit
        return scaledRatio.multiply(groupRatio).multiply(THOUSAND).divide(quotaPerUnit, DIV_CTX)
    }

    private fun priceSnapshotId(
        providerId: String,
        endpointId: String,
        modelId: String,
        group: String?,
        operation: String,
        observedAt: Long,
        index: Int,
    ): String = buildString {
        append("newapi:price:")
        append(providerId)
        append(':')
        append(endpointId)
        append(':')
        append(modelId)
        append(':')
        append(group ?: "_")
        append(':')
        append(operation)
        append(':')
        append(observedAt)
        append(':')
        append(index)
    }

    private fun accountQuotaSnapshotId(providerId: String, accountId: String, observedAt: Long): String =
        "newapi:quota:account:$providerId:$accountId:$observedAt"

    private fun tokenQuotaSnapshotId(providerId: String, credentialId: String, observedAt: Long): String =
        "newapi:quota:token:$providerId:$credentialId:$observedAt"

    private fun pricingEvidenceId(evidence: NewApiEvidence): String =
        "newapi:ev:pricing:${evidence.observedAt}:${evidence.pricingVersion ?: "_"}"

    private fun quotaEvidenceId(evidence: NewApiEvidence, kind: String): String =
        "newapi:ev:quota:$kind:${evidence.observedAt}"

    private fun sha256HexOrNull(raw: String?): String? {
        if (raw == null) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val MAX_SAFE_ID_CHARS = 256

    private fun requireSafeId(value: String, field: String) {
        require(value.isNotBlank()) { "$field must not be blank" }
        require(value.length <= MAX_SAFE_ID_CHARS) { "$field too long" }
        require(value.none { it.code < 0x20 || it.code == 0x7F }) { "$field must not contain control characters" }
    }

    const val OPERATION_GLOBAL = "UNKNOWN"
    const val OPERATION_UNKNOWN = "UNKNOWN"
}

/**
 * PAT / account 角色的安全 scope（`/api/pricing`、`/api/user/self` 用）。
 * 只帶 safe refs（DB 的 safeCredentialId / label 對應的 account 維度），絕不帶 secret。
 * 產出 snapshot 的 `credentialId` 恆為 null，以此與 inference key 層級物理隔離。
 */
data class NewApiAccountScope(
    val providerId: String,
    val accountId: String,
    val endpointId: String,
) {
    init {
        require(providerId.isNotBlank() && accountId.isNotBlank() && endpointId.isNotBlank()) {
            "NewApiAccountScope ids must not be blank"
        }
        for ((field, value) in listOf("providerId" to providerId, "accountId" to accountId, "endpointId" to endpointId)) {
            require(value.length <= 256) { "$field too long" }
            require(value.none { it.code < 0x20 || it.code == 0x7F }) { "$field must not contain control characters" }
        }
    }
}

/**
 * Inference key 角色的安全 scope（`/api/usage/token/` 用）。
 * 只帶 safe key ref，絕不帶 secret。產出 snapshot 的 `accountId` 恆為 null，
 * 以此與 PAT/account 層級物理隔離。
 */
data class NewApiKeyScope(
    val providerId: String,
    val credentialId: String,
    val endpointId: String,
) {
    init {
        require(providerId.isNotBlank() && credentialId.isNotBlank() && endpointId.isNotBlank()) {
            "NewApiKeyScope ids must not be blank"
        }
        for ((field, value) in listOf("providerId" to providerId, "credentialId" to credentialId, "endpointId" to endpointId)) {
            require(value.length <= 256) { "$field too long" }
            require(value.none { it.code < 0x20 || it.code == 0x7F }) { "$field must not contain control characters" }
        }
    }
}

/** 價格腿為 null 的機器可讀原因（UNKNOWN 的細分，絕不等於 0）。 */
enum class NewApiPriceUnpricedReason {
    QUOTA_PER_UNIT_UNKNOWN,
    GROUP_RATIO_UNKNOWN,
    GROUP_RATIO_NON_NUMERIC,
    GROUP_RATIO_NEGATIVE,
    QUOTA_TYPE_UNKNOWN,
    MODEL_RATIO_MISSING,
    COMPLETION_RATIO_MISSING,
    MODEL_PRICE_MISSING,
    /** `billing_expr` 存在：絕不求值，只存原文 + hash。 */
    BILLING_EXPR_PRESENT,
    /** `billing_mode` 為 tiered 卻無 expr：無法定價，不猜。 */
    BILLING_EXPR_MISSING,
}

/**
 * [PriceSnapshot] 裝不下的安全補充欄（不假寫 Room）。
 * 含原始精確 ratios、billing 原文 + hash、vendor、item/top 雙 `pricing_version`、
 * group ratio 原值、endpoint 原始字串與映射、所用 `quotaPerUnit` 與 [unpricedReason]。
 * 無 secret、無 raw body、無 server message、無 PII。
 */
data class NewApiPriceDetail(
    val snapshotId: String,
    val scope: PricingScope,
    val quotaType: Int?,
    val modelRatio: BigDecimal?,
    val modelPrice: BigDecimal?,
    val completionRatio: BigDecimal?,
    val cacheRatio: BigDecimal?,
    val createCacheRatio: BigDecimal?,
    val imageRatio: BigDecimal?,
    val audioRatio: BigDecimal?,
    val audioCompletionRatio: BigDecimal?,
    val billingMode: String?,
    /** 原始 `billing_expr`，僅傳遞事實，永不求值。 */
    val billingExpression: String?,
    /** `billingExpression` 的 SHA-256 hex（null 當 expr 缺失），供變更偵測，不含 secret。 */
    val billingExpressionHash: String?,
    val vendorId: Int?,
    /** `data[]` 內版本（僅第一筆會帶，與頂層值不同，不合併）。 */
    val itemPricingVersion: String?,
    /** 頂層 `pricing_version`（adapter evidence 亦持有一份，兩者分別保存）。 */
    val topPricingVersion: String?,
    val groupRatioExact: BigDecimal?,
    val groupRatioNonNumeric: Boolean,
    val endpointTypeRaw: String?,
    val endpointTypeMapped: EndpointType?,
    val operation: String,
    /** 實際參與換算的 `quotaPerUnit`；`quota_type=1` fixed 腿不需 Q，故為 null。 */
    val quotaPerUnitUsed: BigDecimal?,
    val unpricedReason: NewApiPriceUnpricedReason?,
)

/** 帳號層級整數餘額補充欄（[QuotaSnapshot] 只有 `state`，整數原值存此，不 destructive 改寫）。 */
data class NewApiAccountQuotaDetail(
    val snapshotId: String,
    val scope: PricingScope,
    val quota: Long?,
    val usedQuota: Long?,
    val requestCount: Long?,
    val group: String?,
    val role: Int?,
    val status: Int?,
)

/** Key 層級整數餘額與到期語義補充欄（`expires_at==0` 保持 ambiguous，不猜永不過期）。 */
data class NewApiTokenQuotaDetail(
    val snapshotId: String,
    val scope: PricingScope,
    val totalGranted: Long?,
    val totalUsed: Long?,
    val totalAvailable: Long?,
    val unlimitedQuota: Boolean?,
    val modelLimitsEnabled: Boolean?,
    val expiresAtRaw: Long?,
    val expiryMeaning: NewApiExpiryMeaning,
    val expired: Boolean?,
)

/**
 * Pricing 映射結果。`Mapped`（含權威空清單）與 `Unmappable`（401/403/網路/解析失敗，
 * 呼叫端必須保留 last-good、不得清空）嚴格區分；`data:[]` 的空與失敗的無絕不混同。
 */
sealed interface NewApiPricingMapping {
    data class Mapped(
        val snapshots: List<PriceSnapshot>,
        val details: List<NewApiPriceDetail>,
        val evidence: NewApiEvidence,
        /** `true` = 官方 `data:[]` 權威空（`skippedItems==0` 的空），非 UNKNOWN。 */
        val authoritativeEmpty: Boolean,
        /** `true` = `usable_group` 空（此時官方 `data` 必為 `[]`，見研究 §3.4，不當「無模型」）。 */
        val usableGroupsEmpty: Boolean,
        val skippedItems: Int,
        val usableGroups: List<String>,
        val supportedEndpoints: Map<String, NewApiSupportedEndpoint>,
        val autoGroups: List<String>,
        val topPricingVersion: String?,
    ) : NewApiPricingMapping

    data class Unmappable(
        val status: NewApiStatus,
        val evidence: NewApiEvidence,
    ) : NewApiPricingMapping
}

/** 帳號層級 quota 映射結果（PAT 角色，`credentialId` 永為 null）。 */
sealed interface NewApiAccountQuotaMapping {
    data class Mapped(
        val snapshot: QuotaSnapshot,
        val detail: NewApiAccountQuotaDetail,
        val evidence: NewApiEvidence,
    ) : NewApiAccountQuotaMapping

    data class Unmappable(
        val status: NewApiStatus,
        val evidence: NewApiEvidence,
    ) : NewApiAccountQuotaMapping
}

/** Key 層級 quota 映射結果（inference key 角色，`accountId` 永為 null）。 */
sealed interface NewApiTokenQuotaMapping {
    data class Mapped(
        val snapshot: QuotaSnapshot,
        val detail: NewApiTokenQuotaDetail,
        val evidence: NewApiEvidence,
    ) : NewApiTokenQuotaMapping

    data class Unmappable(
        val status: NewApiStatus,
        val evidence: NewApiEvidence,
    ) : NewApiTokenQuotaMapping
}
