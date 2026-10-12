package dev.modelatlas.app.data

/**
 * MODEL-007 selected-key usable projection 邏輯層（純函式，不碰 DB/Room/網路）。
 *
 * TRACEABILITY: MODEL-007（PRODUCT_SPEC §6、§10；IMPLEMENTATION_PLAN 驗收矩陣價格隔離）。
 *
 * - 同 Provider 不同 Credential/Group 的模型-quota-pricing-availability 不同：
 *   本投影只取 selected (accountId, credentialId, group) 複合鍵（見 [SelectedKey]）
 *   相關的 availability；切換 key/group 的結果互不污染。
 * - 無 availability 的 offering 標 UNKNOWN，不隱藏（Unknown≠0/Free；缺資料不明示為不用）。
 * - 候選 availability state 不一致時回 UNKNOWN（絕不挑一列冒充；同 AtlasRepository
 *   resolvePriceState 的「不一致 UNKNOWN」語意）。
 * - 七維 scope 隔離：比對鍵含 account/credential/group/endpoint/operation；
 *   非空 endpointId/operation 不合的 availability 直接忽略；null（未知 scope）
 *   僅匹配 offering 端同為未知（UNKNOWN/空白）的 legacy 同列，絕不匹配具體值，
 *   不跨 endpoint/operation 污染（MAJOR3 fail-closed）。
 * - manual/official/observed/external 分離：[UsableRow.sourceLabel] 沿用 offering 來源，
 *   manual 結果永遠 MANUAL/USER，不合成 OFFICIAL；來源字串經白名單校验，
 *   未知字串回 UNKNOWN（MINOR2）。
 * - 唯讀參數、無 log、無 secret（只收 credentialId 字串）。
 *
 * 現 schema 說明（CR-003 B3 更新）：[CredentialAvailabilityEntity] 自 v7 起有 group 欄位
 * （null＝未知 scope；v14 追加非空衍生 groupIdentity 供 DAO exact 讀取，本檔不用該衍生欄、
 * 只比對 group 原值精確比對 selectedGroup）；endpoint/operation 欄位仍不存在
 * （不加無證據 denormalized 欄），接線層經 [CredentialAvailabilityEntity.toUsableInput]
 * 傳入（group 預設取 entity.group 原值；endpoint/operation 由 persisted offering／
 * endpoint protocol 傳真值；該兩維 null 僅匹配 offering 同為未知（UNKNOWN/空白）的
 * legacy 列，不匹配具體值，null 與具體值嚴格不等、永不當 wildcard）。
 * 本檔比對邏輯不依賴 schema 欄位增減，比對語意不變。
 */
data class SelectedKey(
    val accountId: String,
    val credentialId: String,
    val group: String? = null,
) {
    /** 複合鍵字串：分組/去重/測試證明隔離用；長度前綴編碼，null 與空字串不碰撞。 */
    fun storageKey(): String = listOf(accountId, credentialId, group)
        .joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
}

/** 投影輸入：offering 維度（對應 [OfferingEntity] 的可用子集＋ operation）。 */
data class OfferingInput(
    val id: String,
    val providerId: String,
    val endpointId: String,
    val providerModelId: String,
    val manual: Boolean = true,
    val source: String = MANUAL_OFFERING_SOURCE_MANUAL,
    val operation: String = "CHAT_COMPLETIONS",
)

/**
 * 投影輸入：availability 維度（對應 [CredentialAvailabilityEntity]＋可選 scope 維度）。
 * `group` 預設取 entity 自身的 group 原值（v7 起該欄位存在，不丟失維度）；
 * endpoint/operation 仍由 persisted offering／endpoint protocol 傳入，不猜測。
 */
data class AvailabilityInput(
    val id: String,
    val offeringId: String,
    val accountId: String,
    val credentialId: String,
    val group: String? = null,
    val endpointId: String? = null,
    val operation: String? = null,
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val source: String = MANUAL_OFFERING_SOURCE_MANUAL,
)

/** 投影輸出列：每 offering 恰一列（無 availability 也不隱藏）。 */
data class UsableRow(
    val offeringId: String,
    val providerId: String,
    val endpointId: String,
    val providerModelId: String,
    val accountId: String,
    val credentialId: String,
    val group: String?,
    val priceState: String,
    val quotaState: String,
    val sourceLabel: String,
    val hasAvailability: Boolean,
)

fun OfferingEntity.toUsableInput(operation: String = "CHAT_COMPLETIONS"): OfferingInput =
    OfferingInput(
        id = id,
        providerId = providerId,
        endpointId = endpointId,
        providerModelId = providerModelId,
        manual = manual,
        source = source,
        operation = operation,
    )

fun CredentialAvailabilityEntity.toUsableInput(
    group: String? = this.group,
    endpointId: String? = null,
    operation: String? = null,
): AvailabilityInput = AvailabilityInput(
    id = id,
    offeringId = offeringId,
    accountId = accountId,
    credentialId = credentialId,
    group = group,
    endpointId = endpointId,
    operation = operation,
    priceState = priceState,
    quotaState = quotaState,
)

object UsableProjection {

    private val KNOWN_PRICE_STATES = setOf("FREE_NOW", "PAID", "PARTIALLY_FREE", "UNKNOWN")
    private val KNOWN_QUOTA_STATES = setOf(
        "QUOTA_AVAILABLE",
        "QUOTA_LOW",
        "QUOTA_EXHAUSTED",
        "RATE_LIMIT_ONLY",
        "PUBLIC_POOL",
        "UNLIMITED_CLAIMED",
        "NO_KNOWN_CONSUMABLE_QUOTA",
        "UNKNOWN",
    )

    /** 來源標籤白名單（MINOR2）：未知字串一律回 UNKNOWN，永不透傳。 */
    private val KNOWN_SOURCE_LABELS = setOf(
        "MANUAL",
        "USER",
        "OFFICIAL",
        "OBSERVED",
        "EXTERNAL",
    )

    fun selectedKey(accountId: String, credentialId: String, group: String?): SelectedKey =
        SelectedKey(accountId = accountId, credentialId = credentialId, group = group)

    /**
     * selected-key 可用投影：每 offering 恰一列，保持 [offerings] 輸入順序。
     * 空 [offerings] 回空表；空 [availabilities] 全列 UNKNOWN；永不拋錯（空輸入不崩）。
     */
    fun usableOfferings(
        offerings: List<OfferingInput>,
        availabilities: List<AvailabilityInput>,
        selectedAccountId: String,
        selectedCredentialId: String,
        selectedGroup: String?,
    ): List<UsableRow> = usableOfferings(
        offerings = offerings,
        availabilities = availabilities,
        selected = selectedKey(selectedAccountId, selectedCredentialId, selectedGroup),
    )

    fun usableOfferings(
        offerings: List<OfferingInput>,
        availabilities: List<AvailabilityInput>,
        selected: SelectedKey,
    ): List<UsableRow> {
        if (offerings.isEmpty()) return emptyList()
        return offerings.map { offering ->
            val matches = availabilities.filter { matchesSelected(it, offering, selected) }
            val prices = matches.map { normalizePrice(it.priceState) }
            val quotas = matches.map { normalizeQuota(it.quotaState) }
            UsableRow(
                offeringId = offering.id,
                providerId = offering.providerId,
                endpointId = offering.endpointId,
                providerModelId = offering.providerModelId,
                accountId = selected.accountId,
                credentialId = selected.credentialId,
                group = selected.group,
                priceState = consensusOrUnknown(prices),
                quotaState = consensusOrUnknown(quotas),
                sourceLabel = sourceLabelFor(offering),
                hasAvailability = matches.isNotEmpty(),
            )
        }
    }

    /**
     * 複合鍵分組證明用（僅證明用，非生產 join 鍵）：把 availability 按
     * (accountId, credentialId, group, offeringId) 分組。
     * 不同 key 組互不相交；呼叫端/測試以此證明切換 Credential/Group 互不污染，
     * 且同 key 下不同 offering 列亦不合併（MINOR3：分組鍵已納入 offering 維度）。
     */
    fun groupBySelectedKey(availabilities: List<AvailabilityInput>): Map<String, List<AvailabilityInput>> =
        availabilities.groupBy {
            selectedKey(it.accountId, it.credentialId, it.group).storageKey() +
                "off${it.offeringId.length}:${it.offeringId}"
        }

    private fun matchesSelected(
        availability: AvailabilityInput,
        offering: OfferingInput,
        selected: SelectedKey,
    ): Boolean {
        if (availability.offeringId != offering.id) return false
        if (availability.accountId != selected.accountId) return false
        if (availability.credentialId != selected.credentialId) return false
        if (availability.group != selected.group) return false
        // MAJOR3 fail-closed：null（未知 scope）僅匹配 offering 端同為未知
        // （UNKNOWN/空白）的 legacy 同列；絕不匹配具體值，不跨維度污染。
        val endpoint = availability.endpointId
        if (endpoint == null) {
            if (!isUnknownScopeValue(offering.endpointId)) return false
        } else if (endpoint != offering.endpointId) {
            return false
        }
        val operation = availability.operation
        if (operation == null) {
            if (!isUnknownScopeValue(offering.operation)) return false
        } else if (operation != offering.operation) {
            return false
        }
        return true
    }

    /** scope 未知值判定：空白或 UNKNOWN（大小寫無關）視為未知；其餘皆為具體值。 */
    private fun isUnknownScopeValue(raw: String): Boolean {
        val normalized = raw.trim().uppercase()
        return normalized.isEmpty() || normalized == "UNKNOWN"
    }

    /** 候選全一致才取值；空候選或任一分歧即 UNKNOWN（絕不挑一列冒充）。 */
    private fun consensusOrUnknown(states: List<String>): String {
        if (states.isEmpty()) return "UNKNOWN"
        val first = states.first()
        return if (states.all { it == first }) first else "UNKNOWN"
    }

    /** 未知字串 fail-closed 歸 UNKNOWN；永不把 UNKNOWN 寫成 FREE/PAID/數值。 */
    internal fun normalizePrice(raw: String): String {
        val normalized = raw.trim().uppercase()
        return if (normalized in KNOWN_PRICE_STATES) normalized else "UNKNOWN"
    }

    internal fun normalizeQuota(raw: String): String {
        val normalized = raw.trim().uppercase()
        return if (normalized in KNOWN_QUOTA_STATES) normalized else "UNKNOWN"
    }

    /**
     * 沿用 offering 來源；manual 家族空白回 MANUAL；來源字串經白名單校验
     * （MANUAL/USER/OFFICIAL/OBSERVED/EXTERNAL），未知一律回 UNKNOWN；
     * 永不合成 OFFICIAL，永不透傳任意字串（MINOR2）。
     */
    internal fun sourceLabelFor(offering: OfferingInput): String {
        val raw = offering.source.trim().uppercase()
        if (raw.isEmpty()) return if (offering.manual) MANUAL_OFFERING_SOURCE_MANUAL else "UNKNOWN"
        return if (raw in KNOWN_SOURCE_LABELS) raw else "UNKNOWN"
    }
}
