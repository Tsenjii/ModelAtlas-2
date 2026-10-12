package dev.modelatlas.app.data

/**
 * MODEL-006 manual hidden offering 邏輯層。
 *
 * TRACEABILITY: MODEL-006（PRODUCT_SPEC §8、§10；IMPLEMENTATION_PLAN 驗收矩陣價格隔離）。
 *
 * - 純參數式：不拿 db 單例、不碰 Room/DAO、不做網路 I/O、不記 log、不碰 secret 原始值
 *  （只經 credentialId 字串，且本檔連 credentialId 都不收）。
 * - PRODUCT_SPEC §8：catalog 沒列但實測成功 → 可建 hidden/manual offering，並保存
 *   Evidence（[OfferingDraft.evidenceId]）與來源（[OfferingDraft.source]）。
 * - PRODUCT_SPEC §10 / NewApiSnapshotStore kind 分槽語意：manual 結果永遠
 *   source=MANUAL/USER、confidence=LOW（見 [MANUAL_OFFERING_CONFIDENCE]），永不寫入
 *   official 槽；LOW confidence 永不升級為 official last-good（由 Repository 接線時
 *   寫入 MANUAL_PRICE / MANUAL_QUOTA kind 落實，本層只保證標記正確）。
 * - Unknown≠0/Free：本層不做任何價格/ quota 數值推斷；名稱含 free 不代表可用。
 * - 冪等鍵為 (providerId, endpointId, providerModelId) 三元組：同 provider 下不同
 *   endpoint 的同名 model 是不同 offering，不互相覆蓋（七維 scope 隔離的 offering 維度）。
 */
const val MANUAL_OFFERING_SOURCE_MANUAL = "MANUAL"

const val MANUAL_OFFERING_SOURCE_USER = "USER"

/** manual 槽固定 confidence；呼叫端不得把此值寫成官方 last-good。 */
const val MANUAL_OFFERING_CONFIDENCE = "LOW"

/**
 * manual offering 冪等 id 推導。僅供接線層（Repository）映射到
 * [OfferingEntity.id] 的預設值參考；冪等比對以欄位三元組為準，不以字串解析為準。
 */
fun manualOfferingIdFor(providerId: String, endpointId: String, providerModelId: String): String =
    "manual:$providerId:$endpointId:$providerModelId"

/**
 * 待落庫的 manual offering 草稿。hidden=true 為預設；只有 [ManualOfferingStore.promoteOnVerified]
 * 能翻成可見，[ManualOfferingStore.downgradeOnFailure] 永不刪除、不標 REMOVED。
 *
 * @param evidenceId 建立依據的 evidence（§8 要求保存 Evidence；不可空白）。
 * @param verificationEvidenceId 實測成功 evidence；null/空白＝未驗證＝不可見、不可用。
 * @param lastFailureEvidenceId / lastFailureReason 僅資訊性失敗記錄；不影響可見性與留存。
 * @param removed 恆 false；本層不存在刪除路徑，一次失敗不清空 last-good。
 */
data class OfferingDraft(
    val id: String,
    val providerId: String,
    val endpointId: String,
    val providerModelId: String,
    val manual: Boolean = true,
    val source: String = MANUAL_OFFERING_SOURCE_MANUAL,
    val evidenceId: String,
    val observedAt: Long,
    val hidden: Boolean = true,
    val verificationEvidenceId: String? = null,
    val lastFailureEvidenceId: String? = null,
    val lastFailureReason: String? = null,
    val removed: Boolean = false,
)

object ManualOfferingStore {

    /**
     * 建 hidden/manual offering。重複 (providerId, endpointId, providerModelId)
     * 回傳 [existing] 內既有列（冪等，不新建 id）。
     *
     * @param source 僅接受 MANUAL/USER；OFFICIAL/OBSERVED/EXTERNAL 一律拒絕
     *  （manual 與 official 分槽，不互相冒充）。
     * @throws IllegalArgumentException 任一 id 空白、source 非 manual 家族、
     *   evidenceId 空白、observedAt < 0。
     */
    fun createHiddenOffering(
        providerId: String,
        endpointId: String,
        providerModelId: String,
        source: String = MANUAL_OFFERING_SOURCE_MANUAL,
        evidenceId: String,
        observedAt: Long,
        existing: List<OfferingDraft> = emptyList(),
    ): OfferingDraft {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(endpointId.isNotBlank()) { "endpointId must not be blank" }
        require(providerModelId.isNotBlank()) { "providerModelId must not be blank" }
        val normalizedSource = source.trim().uppercase()
        require(
            normalizedSource == MANUAL_OFFERING_SOURCE_MANUAL ||
                normalizedSource == MANUAL_OFFERING_SOURCE_USER,
        ) { "Manual offering source must be MANUAL or USER" }
        require(evidenceId.isNotBlank()) { "Manual offering requires evidenceId" }
        require(observedAt >= 0) { "Invalid observation time" }
        existing.firstOrNull {
            it.providerId == providerId &&
                it.endpointId == endpointId &&
                it.providerModelId == providerModelId
        }?.let { return it }
        return OfferingDraft(
            id = manualOfferingIdFor(providerId, endpointId, providerModelId),
            providerId = providerId,
            endpointId = endpointId,
            providerModelId = providerModelId,
            manual = true,
            source = normalizedSource,
            evidenceId = evidenceId,
            observedAt = observedAt,
            hidden = true,
        )
    }

    /**
     * hidden→visible 條件函式：只有非空白實測成功 evidence 才可 promote。
     * 名稱含 free、一次性贈金等皆不構成條件（§8：需實測成功 evidence 才 promote）。
     */
    fun canPromote(draft: OfferingDraft, verificationEvidenceId: String): Boolean =
        verificationEvidenceId.isNotBlank() && !draft.removed

    /**
     * 驗證成功才可見：回傳 hidden=false 的副本，保留建立 evidence、manual/source 標記不變。
     *
     * 空 evidence fail-closed：本函式保留拋錯語意（不回傳半成品、不預設可見）；
     * 接線層（Repository/ViewModel）必須 catch 此 IllegalArgumentException 並轉為錯誤態
     * （error state）呈現給使用者，不得讓例外崩潰流程或誤標可用。
     *
     * @throws IllegalArgumentException verificationEvidenceId 空白（未驗證不可見）。
     */
    fun promoteOnVerified(draft: OfferingDraft, verificationEvidenceId: String): OfferingDraft {
        require(canPromote(draft, verificationEvidenceId)) { "Verification evidence required to promote" }
        return draft.copy(
            hidden = false,
            verificationEvidenceId = verificationEvidenceId,
            removed = false,
        )
    }

    /**
     * 失敗不刪除：保留 last-good，不標 REMOVED，不翻可見性（已驗證可見者遇一次失敗
     * 仍保持可見；未驗證者仍保持 hidden）。只追加資訊性失敗記錄。
     * 本函式永不拋錯（空 evidence/reason 視為無資訊，不影響留存）。
     */
    fun downgradeOnFailure(
        draft: OfferingDraft,
        failureEvidenceId: String? = null,
        failureReason: String? = null,
    ): OfferingDraft = draft.copy(
        lastFailureEvidenceId = failureEvidenceId?.takeIf { it.isNotBlank() }
            ?: draft.lastFailureEvidenceId,
        lastFailureReason = failureReason?.takeIf { it.isNotBlank() }
            ?: draft.lastFailureReason,
        removed = false,
    )

    /** 可見 ⇔ 已 promote（hidden=false）。未驗證 hidden 永不標可用。 */
    fun isVisible(draft: OfferingDraft): Boolean = !draft.hidden && !draft.removed

    /**
     * 可用 ⇔ 可見＋帶非空白驗證 evidence＋未被移除。
     * hidden 未驗證一律 false；名稱含 free 不影響此判定（只看 evidence）。
     */
    fun isUsable(draft: OfferingDraft): Boolean =
        isVisible(draft) && !draft.verificationEvidenceId.isNullOrBlank()
}
