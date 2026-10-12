package dev.modelatlas.app.data

/**
 * 安全連線投影：只含 refs 與狀態字串，絕無 secret 原始值與聊天內容。
 *
 * - `anonymous=true` 為 offering-direct 匿名列：`credentialId=""`（空字串作缺值投影，
 *   不寫 `credentials` 表），產品 `InferenceTarget` 需轉 `null`（由主 session 負責）。
 *   僅 `provider_configurations.authMode='NONE'` 的已 commit provider 才有匿名列；
 *   刪 key 後不退化成匿名。
 * - `protocol` 取自 `endpoints.protocol`（非 `HTTPS`），`manual` 取自 `offerings.manual`；
 *   兩查詢分支（availability / 匿名 UNION）欄位對齊。
 * - `priceState/quotaState` 由 `AtlasRepository` 做 app 層 enrichment（Room validator
 *   不接受 SELECT 子查詢，故不在 DAO JOIN）：price 取同 provider／account／model／
 *   endpoint 的官方 PRICE heads 候選集合，state 一致才取值、不一致 `UNKNOWN`
 *  （絕不挑一列冒充）；quota 取 TOKEN head 優先、ACCOUNT head 次之；
 *   無對應 head 時沿用 DAO 舊值（匿名沿用 `UNKNOWN`），不把 `UNKNOWN` 當成已知值、
 *   不拿 `lastGood` 冒充本次成功。
 * - MODEL-003 分類快照（`canonicalModelId`／`classificationSource`／
 *   `classificationConfidence`／`primaryCategory`）：全部 nullable，缺席即未知
 *   （Unknown≠0，不填零值）。v9 起 `ConnectionDao.observe`／`observeManagement`
 *   已以 LEFT JOIN `provider_model_aliases`＋`model_classifications` 真 SELECT 這四欄
 *   （classificationSource 明示命中槽 CATALOG＞OBSERVED＞MANUAL，不合成、不冒充）；
 *   無 alias／無分類列時為 null。official／observed health 仍各自取自 providers 欄。
 *   完整 capabilities／modalities／endpointTypes 經 `CanonicalStore.getClassification`
 *   以 canonical＋source 讀取。
 */
data class ConnectionRecord(
    val id: String,
    val providerId: String,
    val accountId: String,
    val credentialId: String,
    val endpointId: String,
    val name: String,
    val baseUrl: String,
    val endpointUrl: String,
    val modelId: String,
    val credentialLabel: String = "主要憑證",
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val officialHealth: String = "UNKNOWN",
    val observedHealth: String = "UNKNOWN",
    val checkinUrl: String? = null,
    val requiresDailyCheckin: Boolean = false,
    val offeringId: String = id,
    val credentialEnabled: Boolean = true,
    val anonymous: Boolean = false,
    val protocol: String = "CHAT_COMPLETIONS",
    val manual: Boolean = true,
    val canonicalModelId: String? = null,
    val classificationSource: String? = null,
    val classificationConfidence: String? = null,
    val primaryCategory: String? = null,
)
