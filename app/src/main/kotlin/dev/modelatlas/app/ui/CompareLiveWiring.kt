package dev.modelatlas.app.ui

import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.CompareKeyChoice
import dev.modelatlas.app.data.CompareKeySelection
import dev.modelatlas.app.data.toManualConfig
import dev.modelatlas.core.domain.canonicalIdFor
import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.models.CompareLiveOffering
import dev.modelatlas.core.models.CompareLiveRequest
import dev.modelatlas.core.models.CompareLiveRowResult
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialLocation
import dev.modelatlas.core.onboarding.CredentialRole
import kotlinx.coroutines.CancellationException

/**
 * P6 repair worker B：MODEL-008 Compare Live 真接線（UI 側）。
 *
 * TRACEABILITY：MODEL-008（PRODUCT_SPEC §8 Compare Live、§3 統一 telemetry、§10 FREE 判定）。
 * - 同一 canonical 的多 offering 共用同一份小 prompt（[comparePrompt]），經
 *   [CompareLiveRunner.run]（唯一 inference 入口，source 恆 COMPARE_LIVE）執行，
 *   全部走正常 telemetry；免費／付費皆記錄，無 bypass。
 * - Scope 隔離 fail-closed：providerId／endpointId／endpointUrl／providerModelId 任一空白
 *   即略過該列（不發請求、不捏造 scope）；comparisonId 重複即整批拒絕（runner 亦要求 distinct）。
 *   Legacy 預設 comparisonId == offeringId，故舊重複 offeringId 行為保留拒絕。
 * - 失敗隔離由 runner 逐列收斂（outcome＋httpStatus）；取消（CancellationException）直接傳播。
 * - 價格／配額字串只透傳（runner 內 fail-closed 正規化），本檔不依 model id／group 名稱推導。
 *   多 key 新路徑一律用 availability 精確 (offering/account/credential/group) 匹配取值，
 *   缺席回 UNKNOWN，絕不沿用另一把 key 的 ModelRow 旗標。
 * - Quota 保護：單次比測上限 [COMPARE_MAX_OFFERINGS] 列（輸入順序截斷，不重排）。
 * - 只經手 safe refs；不讀 vault、不輸出 raw key／PAT、不碰 secret 值。
 * - In-memory DTO only：不改 schema、不改 DAO、不寫 DB；查詢失敗 fail-closed，
 *   sync failure 不抹 last-good（本檔無寫入）。
 *
 * VM/Repo hook（本 worker 不可改 VM/Repo，由主 AI 接；見回報）：
 * - 理想位置是 AtlasViewModel.requestCompareLive() 內建 request→run→回填 compareRows；
 *   在 VM 接線前，本檔由 AtlasScreen 直接呼叫（engine／db 取自 AtlasApplication）。
 */
object CompareLiveWiring {

    /** 單次比測上限列數（quota 保護；輸入順序截斷）。 */
    const val COMPARE_MAX_OFFERINGS = 6

    /** 比測共用小 prompt 的 maxOutputTokens（小量，不燒 quota）。 */
    const val COMPARE_MAX_OUTPUT_TOKENS = 64

    /**
     * 同一 canonical 共用的小 prompt（固定內容，與 canonical 無關，確保可比性；
     * 不含任何 secret／key／PAT）。
     */
    fun comparePrompt(): List<PromptMessage> =
        listOf(PromptMessage("user", "用繁體中文一句話介紹你自己。"))

    /**
     * 純輸入（供 JVM 測試與 Screen DB 映射共用；只收 safe id，不碰 raw key）。
     *
     * - [comparisonId] 為 scoped 比測關聯鍵：null／空白即預設為 [offeringId]
     *   （legacy 相容，符合 core `comparisonId = offeringId` 預設；舊重複 offeringId 仍整批拒絕）。
     *   新多 key 路徑必須填入 [scopedComparisonId]（length-prefixed，含 actual offering +
     *   provider/account/selected-key/group），actual [offeringId] 保持不變。
     * - [selectedCredentialId] 為 UI／selection anchor：null 即預設為 [credentialId]
     *   （符合 core `selectedCredentialId = credentialId` 預設；匿名／custom-only 時兩者皆可為 null，
     *   custom-only 另以 [authFields] 攜帶可信 refs）。
     * - [authFields] 只帶 safe refs（name＋credentialId＋query＋overrideConfirmed），值永不進此型別；
     *   來自已 commit provider config 的 customAuthFields（排除 ACCOUNT／PAT），legacy 無 config 即空。
     */
    data class CompareOfferingInput(
        val offeringId: String,
        val providerId: String,
        val accountId: String,
        val credentialId: String?,
        val endpointId: String,
        val endpointUrl: String,
        val providerModelId: String,
        val group: String? = null,
        val operation: String = "CHAT_COMPLETIONS",
        val priceState: String = "UNKNOWN",
        val quotaState: String = "UNKNOWN",
        val comparisonId: String? = null,
        val selectedCredentialId: String? = null,
        val authFields: List<InferenceAuthField> = emptyList(),
    )

    /** 有效 comparisonId：空白／null 即回 offeringId（legacy 相容）。 */
    fun effectiveComparisonId(input: CompareOfferingInput): String {
        val scoped = input.comparisonId?.takeIf { it.isNotBlank() }
        return scoped ?: input.offeringId
    }

    /** 有效 selection anchor：null 即回 credentialId（core 預設語意）。 */
    fun effectiveSelectedCredentialId(input: CompareOfferingInput): String? =
        input.selectedCredentialId ?: input.credentialId

    /**
     * Collision-safe scoped comparisonId（length-prefixed，含 actual offering + 全 scope）。
     *
     * - 格式：各段 `${length}:$value` 直接串接，null 以 `-1:` 編碼（與空字串 `0:` 區分）。
     * - 含 actual offeringId＋providerId＋accountId＋selected credential＋group（空白 group 歸一為 null）。
     * - actual offeringId 本身不變；此 ID 只作 runner 關聯／去重／結果 key，不寫 DB。
     */
    fun scopedComparisonId(
        actualOfferingId: String,
        selection: CompareKeySelection,
    ): String {
        fun part(s: String?): String = if (s == null) "-1:" else "${s.length}:$s"
        val group = selection.group?.takeIf { it.isNotBlank() }
        return part(actualOfferingId) +
            part(selection.providerId) +
            part(selection.accountId) +
            part(selection.credentialId) +
            part(group)
    }

    /**
     * Collision-safe scope key（供 UI `compare_key_` tag；不含 offering）。
     * 與 [scopedComparisonId] 同一 length-prefix 編碼，null 與空字串不碰撞。
     */
    fun scopeKey(selection: CompareKeySelection): String {
        fun part(s: String?): String = if (s == null) "-1:" else "${s.length}:$s"
        val group = selection.group?.takeIf { it.isNotBlank() }
        return part(selection.providerId) +
            part(selection.accountId) +
            part(selection.credentialId) +
            part(group)
    }

    /**
     * 純建構（無 DB／無網路，可單測）。
     *
     * - canonicalId 空白、inputs 為空、或全被 scope 過濾掉 → 回 null（呼叫端顯示
     *   「尚無可比 offering」，不偽造結果）。
     * - 逐列 fail-closed：任一 scope 必填空白即略過該列；comparisonId 重複（去重後數量
     *   變少）→ 整批回 null（避免 runner 關聯錯亂）。Legacy 預設 comparisonId==offeringId，
     *   故舊重複 offeringId 行為保留拒絕；新 scoped inputs 只要 comparisonId distinct，
     *   同一 actual offeringId 可出現多次（跨 key／跨 group）。
     * - 截斷至 [COMPARE_MAX_OFFERINGS]（輸入順序保留）。
     */
    fun buildRequest(
        canonicalId: String,
        inputs: List<CompareOfferingInput>,
        maxOfferings: Int = COMPARE_MAX_OFFERINGS,
    ): CompareLiveRequest? {
        if (canonicalId.isBlank()) return null
        if (inputs.isEmpty()) return null
        val clean = inputs.filter {
            it.offeringId.isNotBlank() &&
                it.providerId.isNotBlank() &&
                it.endpointId.isNotBlank() &&
                it.endpointUrl.isNotBlank() &&
                it.providerModelId.isNotBlank() &&
                it.accountId.isNotBlank()
        }
        if (clean.isEmpty()) return null
        val scopedIds = clean.map { effectiveComparisonId(it) }
        if (scopedIds.size != scopedIds.toSet().size) return null
        val capped = if (clean.size > maxOfferings) clean.subList(0, maxOfferings) else clean
        return CompareLiveRequest(
            canonicalId = canonicalId,
            offerings = capped.map {
                CompareLiveOffering(
                    offeringId = it.offeringId,
                    comparisonId = effectiveComparisonId(it),
                    providerId = it.providerId,
                    accountId = it.accountId,
                    credentialId = it.credentialId,
                    selectedCredentialId = effectiveSelectedCredentialId(it),
                    endpointId = it.endpointId,
                    endpointUrl = it.endpointUrl,
                    providerModelId = it.providerModelId,
                    group = it.group?.takeIf { g -> g.isNotBlank() },
                    operation = it.operation.takeIf { op ->
                        op.isNotBlank() && op.length <= 64 && op.all { c -> c.isLetterOrDigit() || c == '_' }
                    } ?: "CHAT_COMPLETIONS",
                    priceState = it.priceState,
                    quotaState = it.quotaState,
                    authFields = it.authFields,
                )
            },
            messages = comparePrompt(),
            maxOutputTokens = COMPARE_MAX_OUTPUT_TOKENS,
        )
    }

    /**
     * DB 真建構（suspend，IO 執行）。舊單 key 相容入口：只走 primary provider。
     *
     * - 同 canonical 的 offering：經 alias 表（providerId＋providerModelId → canonical）
     *   或 [canonicalIdFor] fallback 判定；hidden 或空白 modelId 略過。
     * - endpoint 經 endpointDao 真值（protocol → operation）；缺 endpoint／url 空白即略過。
     * - credential／account／group 取 selected-key，先驗證真實 account / credential 歸屬；
     *   不把單一 provider 的 credential 套到另一個 provider。跨 provider 比測仍需各自的
     *   已選憑證，不能用 fallback 猜 key。
     *   selected credential 空白 → 回 null（fail-closed，不猜 key、不發匿名請求；
     *   匿名 provider 由主 AI 在 VM 層另行處理）。
     * - priceState／quotaState 取 allModelRows 同列真值，缺席回 UNKNOWN。
     * - 查詢失敗 fail-closed 回 null；取消直接傳播。
     *
     * 本舊入口保留相容，不改行為；新多 provider 多 key 請用
     * [buildRequestFromDb] selections overload。
     */
    suspend fun buildRequestFromDb(
        db: AtlasDatabase,
        detailRow: ModelRow,
        allRows: List<ModelRow>,
        selectedAccountId: String?,
        selectedCredentialId: String?,
        selectedGroup: String?,
    ): CompareLiveRequest? {
        if (detailRow.canonicalId.isBlank()) return null
        val credential = selectedCredentialId?.takeIf { it.isNotBlank() } ?: return null
        val account = selectedAccountId?.takeIf { it.isNotBlank() } ?: return null
        try {
            val credentialRecord = db.credentialDao().getById(credential) ?: return null
            if (!credentialRecord.enabled || credentialRecord.accountId != account) return null
            val accountRecord = db.accountDao().getById(account) ?: return null
            val rowsByKey = allRows
                .filter { it.canonicalId == detailRow.canonicalId }
                .associateBy { it.providerId + "\u0000" + it.providerModelId }
            if (rowsByKey.isEmpty()) return null
            val providerIds = rowsByKey.values.map { it.providerId }
                .filter { it.isNotBlank() }.distinct()
            if (providerIds.isEmpty()) return null
            val inputs = ArrayList<CompareOfferingInput>()
            for (providerId in providerIds) {
                if (providerId != accountRecord.providerId) continue
                val offerings = try {
                    db.offeringDao().byProvider(providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                for (offering in offerings) {
                    if (offering.hidden) continue
                    if (offering.providerModelId.isBlank()) continue
                    val canonical = try {
                        db.providerModelAliasDao()
                            .get(offering.providerId, offering.providerModelId)
                            ?.canonicalModelId
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: runCatching { canonicalIdFor(offering.providerModelId) }.getOrNull()
                        ?: continue
                    if (canonical != detailRow.canonicalId) continue
                    val endpoint = try {
                        db.endpointDao().getById(offering.endpointId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (endpoint.providerId != providerId || endpoint.endpointUrl.isBlank()) continue
                    val row = rowsByKey[offering.providerId + "\u0000" + offering.providerModelId]
                    // account 維度：selected account 優先；缺席時不猜（fail-closed）。
                    val resolvedAccount = account
                    inputs += CompareOfferingInput(
                        offeringId = offering.id,
                        providerId = offering.providerId,
                        accountId = resolvedAccount,
                        credentialId = credential,
                        endpointId = endpoint.endpointId,
                        endpointUrl = endpoint.endpointUrl,
                        providerModelId = offering.providerModelId,
                        group = selectedGroup?.takeIf { it.isNotBlank() },
                        operation = endpoint.protocol
                            .takeIf { it.isNotBlank() && it.length <= 64 }
                            ?: "CHAT_COMPLETIONS",
                        priceState = row?.priceState?.name ?: "UNKNOWN",
                        quotaState = row?.quotaState?.name ?: "UNKNOWN",
                    )
                }
            }
            return buildRequest(detailRow.canonicalId, inputs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * DB 真建構（suspend，IO 執行）。新多 provider 多 key 入口。
     *
     * TRACEABILITY: MODEL-008（PRODUCT_SPEC §3／§6／§8／§10、contract §23 scope／source 分離）。
     *
     * - [detailRow.canonicalId] 空白、[selections] 為空、全被 scope／角色過濾掉 → 回 null
     *  （未知明示，不偽造結果）。
     * - 顯式多 provider 多 key：每個 [CompareKeySelection] 獨立校验其 provider／account／
     *   credential 歸屬＋enabled＋PAT 角色；絕不拿第一把 key／foreign key 套到另一 provider，
     *   絕不跨 provider／跨 account 猜測。無效 selection 逐個略過（fail-closed），全無效即 null。
     * - null credential 僅限真實已確認 NONE：已 commit `provider_configurations` 的
     *   `authMode='NONE'`＋`anonymousConfirmed=true`＋同 provider 真 account；否則拒絕
     *   （不捏造 anonymous，不把刪 key 後退化成匿名）。
     * - 具名 credential：DB 真值存在＋enabled＋`credential.accountId == selection.accountId`＋
     *   owner provider 一致；有 config 時必須是單一 INFERENCE 角色（ACCOUNT／PAT 永不進 inference），
     *   custom-only（API_KEY／CUSTOM_HEADER 無 INFERENCE）僅接受同 provider 已 commit
     *   customAuthFields 內的可信 ref（此時 offering credentialId＝null，anchor 進
     *   selectedCredentialId＋authFields）；無 config 即 generic legacy 允許（已驗歸屬＋enabled）。
     *   有 config 且 `authMode==NONE` 卻帶具名 credential 即拒絕。
     * - Canonical／offering／endpoint／protocol 一律取 DB 真值，且僅收 visible matching rows
     *   （[allRows] 同 canonical＋同 provider／model 有列才收；hidden／空白 modelId 略過）。
     * - 同 key＋同一 actual offering＋同 scope 重複即去重；distinct scoped [comparisonId]
     *   （length-prefixed，含 actual offering／account／selected-key／group，actual offeringId 不變）
     *   才允許同一 actual offering 多次出現（跨 key／跨 group）。scoped 重複（不應發生，去重後
     *   仍重複）由 [buildRequest] 整批拒絕。
     * - Price／quota 一律用 availability 精確 (offering／account／credential／group) 匹配取值，
     *   缺席回 UNKNOWN；絕不沿用另一把 key 的 ModelRow 旗標，絕不依 group 名稱／model id／0 推 Free。
     *   匿名（null credential）無 availability，直接 UNKNOWN。
     * - Group 只用 selection 顯式值＋DB saved group（choices loader 暴露 saved，不發明）；
     *   格式非法（空白視為 null；含 `::`／控制字元／>256）即略過該 selection。
     * - In-memory DTO only：只讀 SELECT，不寫 DB；查詢失敗 fail-closed（略過該列／回 null），
     *   取消直接傳播；不讀 vault、不輸出 raw key／PAT。
     */
    suspend fun buildRequestFromDb(
        db: AtlasDatabase,
        detailRow: ModelRow,
        allRows: List<ModelRow>,
        selections: List<CompareKeySelection>,
    ): CompareLiveRequest? {
        if (detailRow.canonicalId.isBlank()) return null
        if (selections.isEmpty()) return null
        try {
            val distinctSelections = selections.distinct()
            if (distinctSelections.isEmpty()) return null
            val visibleKeys = allRows
                .filter { it.canonicalId == detailRow.canonicalId }
                .map { it.providerId + "\u0000" + it.providerModelId }
                .toSet()
            if (visibleKeys.isEmpty()) return null
            val inputs = ArrayList<CompareOfferingInput>()
            val seenScoped = LinkedHashSet<String>()
            for (selection in distinctSelections) {
                if (inputs.size >= COMPARE_MAX_OFFERINGS) break
                val validated = validateSelection(db, selection) ?: continue
                val offerings = try {
                    db.offeringDao().byProvider(selection.providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                for (offering in offerings) {
                    if (inputs.size >= COMPARE_MAX_OFFERINGS) break
                    if (offering.hidden) continue
                    if (offering.providerId != selection.providerId) continue
                    if (offering.providerModelId.isBlank()) continue
                    val canonical = try {
                        db.providerModelAliasDao()
                            .get(offering.providerId, offering.providerModelId)
                            ?.canonicalModelId
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: runCatching { canonicalIdFor(offering.providerModelId) }.getOrNull()
                        ?: continue
                    if (canonical != detailRow.canonicalId) continue
                    if ((offering.providerId + "\u0000" + offering.providerModelId) !in visibleKeys) continue
                    val endpoint = try {
                        db.endpointDao().getById(offering.endpointId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (endpoint.providerId != selection.providerId) continue
                    if (endpoint.endpointUrl.isBlank()) continue
                    val scoped = scopedComparisonId(offering.id, validated.effectiveSelection)
                    if (!seenScoped.add(scoped)) continue
                    val (priceState, quotaState) = exactAvailabilityStates(
                        db = db,
                        offeringId = offering.id,
                        accountId = validated.effectiveSelection.accountId,
                        credentialId = validated.actualCredentialId,
                        group = validated.effectiveSelection.group?.takeIf { it.isNotBlank() },
                    )
                    inputs += CompareOfferingInput(
                        offeringId = offering.id,
                        comparisonId = scoped,
                        providerId = offering.providerId,
                        accountId = validated.effectiveSelection.accountId,
                        credentialId = validated.actualCredentialId,
                        selectedCredentialId = validated.effectiveSelection.credentialId,
                        endpointId = endpoint.endpointId,
                        endpointUrl = endpoint.endpointUrl,
                        providerModelId = offering.providerModelId,
                        group = validated.effectiveSelection.group?.takeIf { it.isNotBlank() },
                        operation = endpoint.protocol
                            .takeIf { it.isNotBlank() && it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c == '_' } }
                            ?: "CHAT_COMPLETIONS",
                        priceState = priceState,
                        quotaState = quotaState,
                        authFields = validated.authFields,
                    )
                }
            }
            if (inputs.isEmpty()) return null
            return buildRequest(detailRow.canonicalId, inputs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Choices loader（suspend，IO 執行）：列出同 canonical 可比的顯式 key 選項。
     *
     * - 僅收 [allRows] 中同 canonical 的 visible providers；無 visible 即回空表（未知明示）。
     * - 每 provider 列出其真 accounts×已啟用 credentials（ownership＋enabled＋PAT 角色同上校验；
     *   ACCOUNT／PAT 永不列入；custom-only 僅列已 commit 可信 refs；legacy 無 config 即 generic 允許），
     *   group 一律暴露 DB saved group（credential.saved group，空白即 null），絕不發明。
     * - null credential（匿名）僅在真實已確認 NONE（authMode NONE＋anonymousConfirmed＋真 account）
     *   時每 account 一列，group 一律 null（未知，不發明），label 為 `匿名`。
     * - 只回 safe metadata（providerName＋credentialLabel），不讀 vault、不含 raw key／PAT。
     * - In-memory 唯讀；查詢失敗 fail-closed 回空表；取消直接傳播。
     */
    suspend fun loadChoicesFromDb(
        db: AtlasDatabase,
        detailRow: ModelRow,
        allRows: List<ModelRow>,
    ): List<CompareKeyChoice> {
        if (detailRow.canonicalId.isBlank()) return emptyList()
        try {
            val visibleProviders = allRows
                .filter { it.canonicalId == detailRow.canonicalId && it.providerId.isNotBlank() }
                .map { it.providerId }
                .distinct()
            if (visibleProviders.isEmpty()) return emptyList()
            // 確認每 provider 至少有一個 visible matching offering＋endpoint 真值，否則不列 choices。
            val usableProviders = ArrayList<String>()
            for (providerId in visibleProviders) {
                val hasUsable = try {
                    hasVisibleOffering(db, providerId, detailRow.canonicalId, allRows)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (hasUsable) usableProviders += providerId
            }
            if (usableProviders.isEmpty()) return emptyList()
            val out = ArrayList<CompareKeyChoice>()
            val seen = LinkedHashSet<CompareKeySelection>()
            for (providerId in usableProviders) {
                val providerName = try {
                    db.providerDao().getById(providerId)?.name?.takeIf { it.isNotBlank() } ?: providerId
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    providerId
                }
                val accounts = try {
                    db.accountDao().byProvider(providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                // 匿名 choices：真實已確認 NONE 才列。
                val noneConfirmed = try {
                    isConfirmedNone(db, providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (noneConfirmed) {
                    for (account in accounts) {
                        if (account.providerId != providerId) continue
                        val sel = CompareKeySelection(
                            providerId = providerId,
                            accountId = account.accountId,
                            credentialId = null,
                            group = null,
                        )
                        if (seen.add(sel)) {
                            out += CompareKeyChoice(
                                selection = sel,
                                providerName = providerName,
                                credentialLabel = "匿名",
                            )
                        }
                    }
                }
                for (account in accounts) {
                    if (account.providerId != providerId) continue
                    val credentials = try {
                        db.credentialDao().byAccount(account.accountId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        continue
                    }
                    for (cred in credentials) {
                        if (!cred.enabled) continue
                        if (cred.accountId != account.accountId) continue
                        val selGroup = cred.group?.takeIf { it.isNotBlank() }
                        val probe = CompareKeySelection(
                            providerId = providerId,
                            accountId = account.accountId,
                            credentialId = cred.credentialId,
                            group = selGroup,
                        )
                        val validated = try {
                            validateSelection(db, probe)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            continue
                        } ?: continue
                        // 用校验后 effective selection（group 已正規化），避免發明 group。
                        val sel = validated.effectiveSelection
                        if (!seen.add(sel)) continue
                        val label = cred.label.takeIf { it.isNotBlank() } ?: cred.credentialId
                        out += CompareKeyChoice(
                            selection = sel,
                            providerName = providerName,
                            credentialLabel = label,
                        )
                    }
                }
            }
            return out.sortedWith(
                compareBy<CompareKeyChoice>(
                    { it.selection.providerId },
                    { it.selection.accountId },
                    { it.selection.credentialId ?: "" },
                    { it.selection.group ?: "" },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /** 結果摘要（成功／失敗計數；空列回空態，不捏造）。 */
    fun summaryText(rows: List<CompareLiveRowResult>, format: String): String {
        if (rows.isEmpty()) return ""
        val ok = rows.count { it.success }
        return String.format(format, ok, rows.size - ok, rows.size)
    }

    /** 頂層失敗分類（只回代碼，不攜帶 URL／body／secret；逐列失敗已由 runner 收斂，此處僅程式／傳輸層）。 */
    fun classifyCompareError(e: Throwable): String {
        if (e is CancellationException) return "CANCELLED"
        val msg = (e.message.orEmpty() + " " + e::class.java.simpleName).lowercase()
        return when {
            "auth" in msg || "credential" in msg || "401" in msg || "403" in msg -> "AUTH"
            "network" in msg || "timeout" in msg || "unreachable" in msg ||
                "unknownhost" in msg || "connect" in msg || e is java.io.IOException -> "NETWORK"
            else -> "UNKNOWN"
        }
    }

    // ---- internal validation (suspend, DB truth only; safe refs only) ----

    private data class ValidatedSelection(
        /** 正規化後 effective selection（group 空白歸 null；其餘原值）。 */
        val effectiveSelection: CompareKeySelection,
        /** 實際 Bearer ref：具名即 selection.credentialId；匿名／custom-only 即 null。 */
        val actualCredentialId: String?,
        /** 該 provider 已 commit custom fields（排除 ACCOUNT／PAT；legacy 即空）。 */
        val authFields: List<InferenceAuthField>,
    )

    private fun validGroupOrNull(raw: String?): String? {
        if (raw == null) return null
        if (raw.isBlank()) return null
        if (raw.length > 256) return null
        if ("::" in raw) return null
        if (raw.any { it.code < 0x20 || it.code == 0x7F }) return null
        return raw
    }

    private suspend fun isConfirmedNone(db: AtlasDatabase, providerId: String): Boolean {
        if (providerId.isBlank()) return false
        val cfg = db.providerConfigDao().getByProvider(providerId) ?: return false
        val manual = try {
            cfg.toManualConfig()
        } catch (_: Exception) {
            return false
        }
        return manual.authMode == AuthMode.NONE && manual.anonymousConfirmed
    }

    private suspend fun loadCommittedAuthFields(
        db: AtlasDatabase,
        providerId: String,
    ): List<InferenceAuthField> {
        val entity = db.providerConfigDao().getByProvider(providerId) ?: return emptyList()
        val config = try {
            entity.toManualConfig()
        } catch (_: Exception) {
            throw IllegalArgumentException("Credential role unavailable")
        }
        val accountRefs = config.credentials
            .filter { it.role == CredentialRole.ACCOUNT }
            .map { it.safeCredentialId.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val fields = ArrayList<InferenceAuthField>()
        val seen = LinkedHashSet<String>()
        for (ref in config.customAuthFields) {
            val refId = ref.safeCredentialId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (refId in accountRefs) throw IllegalArgumentException("Credential role unavailable")
            val cleanName = ref.name.trim()
            if (cleanName.isEmpty()) continue
            val key = ((if (ref.location == CredentialLocation.QUERY) "q:" else "h:") + cleanName.lowercase())
            if (!seen.add(key)) continue
            val credential = db.credentialDao().getById(refId)
                ?: throw IllegalArgumentException("Credential not found")
            val owner = db.accountDao().getById(credential.accountId)
                ?: throw IllegalArgumentException("Account not found")
            if (!credential.enabled || owner.providerId != providerId) {
                throw IllegalArgumentException("Custom auth ownership mismatch")
            }
            fields += InferenceAuthField(
                name = cleanName,
                credentialId = refId,
                query = ref.location == CredentialLocation.QUERY,
                overrideConfirmed = ref.overrideConfirmed,
            )
        }
        return fields
    }

    private suspend fun validateSelection(
        db: AtlasDatabase,
        selection: CompareKeySelection,
    ): ValidatedSelection? {
        if (selection.providerId.isBlank() || selection.accountId.isBlank()) return null
        val group = if (selection.group == null) {
            null
        } else {
            if (selection.group.isBlank()) null else validGroupOrNull(selection.group) ?: return null
        }
        val provider = db.providerDao().getById(selection.providerId) ?: return null
        if (provider.providerId != selection.providerId) return null
        val account = db.accountDao().getById(selection.accountId) ?: return null
        if (account.providerId != selection.providerId) return null
        val credId = selection.credentialId
        if (credId == null) {
            // 匿名僅限真實已確認 NONE。
            if (!isConfirmedNone(db, selection.providerId)) return null
            return ValidatedSelection(
                effectiveSelection = CompareKeySelection(
                    providerId = selection.providerId,
                    accountId = selection.accountId,
                    credentialId = null,
                    group = null,
                ),
                actualCredentialId = null,
                authFields = emptyList(),
            )
        }
        if (credId.isBlank()) return null
        val credential = db.credentialDao().getById(credId) ?: return null
        if (!credential.enabled) return null
        if (credential.accountId != selection.accountId) return null
        val configEntity = db.providerConfigDao().getByProvider(selection.providerId)
            ?: return ValidatedSelection(
                effectiveSelection = CompareKeySelection(
                    providerId = selection.providerId,
                    accountId = selection.accountId,
                    credentialId = credId,
                    group = group,
                ),
                actualCredentialId = credId,
                authFields = emptyList(),
            )
        val config = try {
            configEntity.toManualConfig()
        } catch (_: Exception) {
            return null
        }
        if (config.authMode == AuthMode.NONE) return null
        val roles = config.credentials
            .filter { it.safeCredentialId == credId }
            .map { it.role }
            .toSet()
        if (CredentialRole.ACCOUNT in roles) return null
        val customOnly = (config.authMode == AuthMode.API_KEY || config.authMode == AuthMode.CUSTOM_HEADER) &&
            config.credentials.none { it.role == CredentialRole.INFERENCE }
        if (customOnly) {
            val trusted = config.customAuthFields.any { it.safeCredentialId == credId }
            if (!trusted) return null
            val fields = try {
                loadCommittedAuthFields(db, selection.providerId)
            } catch (_: Exception) {
                return null
            }
            if (fields.none { it.credentialId == credId }) return null
            return ValidatedSelection(
                effectiveSelection = CompareKeySelection(
                    providerId = selection.providerId,
                    accountId = selection.accountId,
                    credentialId = credId,
                    group = group,
                ),
                actualCredentialId = null,
                authFields = fields,
            )
        }
        if (roles != setOf(CredentialRole.INFERENCE)) return null
        val fields = try {
            loadCommittedAuthFields(db, selection.providerId)
        } catch (_: Exception) {
            return null
        }
        return ValidatedSelection(
            effectiveSelection = CompareKeySelection(
                providerId = selection.providerId,
                accountId = selection.accountId,
                credentialId = credId,
                group = group,
            ),
            actualCredentialId = credId,
            authFields = fields,
        )
    }

    private suspend fun exactAvailabilityStates(
        db: AtlasDatabase,
        offeringId: String,
        accountId: String,
        credentialId: String?,
        group: String?,
    ): Pair<String, String> {
        if (credentialId == null) return "UNKNOWN" to "UNKNOWN"
        val rows = try {
            db.availabilityDao().byCredential(credentialId)
        } catch (_: Exception) {
            return "UNKNOWN" to "UNKNOWN"
        }
        val normGroup = group?.takeIf { it.isNotBlank() }
        val matches = rows.filter {
            it.offeringId == offeringId &&
                it.accountId == accountId &&
                it.credentialId == credentialId &&
                (it.group?.takeIf { g -> g.isNotBlank() } == normGroup)
        }
        if (matches.isEmpty()) return "UNKNOWN" to "UNKNOWN"
        val prices = matches.map { normalizePriceState(it.priceState) }
        val quotas = matches.map { normalizeQuotaState(it.quotaState) }
        val price = if (prices.all { it == prices.first() }) prices.first() else "UNKNOWN"
        val quota = if (quotas.all { it == quotas.first() }) quotas.first() else "UNKNOWN"
        return price to quota
    }

    private fun normalizePriceState(raw: String): String {
        val n = raw.trim().uppercase()
        return if (n == "FREE_NOW" || n == "PAID" || n == "PARTIALLY_FREE" || n == "UNKNOWN") n else "UNKNOWN"
    }

    private fun normalizeQuotaState(raw: String): String {
        val n = raw.trim().uppercase()
        return if (
            n == "QUOTA_AVAILABLE" || n == "QUOTA_LOW" || n == "QUOTA_EXHAUSTED" ||
            n == "RATE_LIMIT_ONLY" || n == "PUBLIC_POOL" || n == "UNLIMITED_CLAIMED" ||
            n == "NO_KNOWN_CONSUMABLE_QUOTA" || n == "UNKNOWN"
        ) {
            n
        } else {
            "UNKNOWN"
        }
    }

    private suspend fun hasVisibleOffering(
        db: AtlasDatabase,
        providerId: String,
        canonicalId: String,
        allRows: List<ModelRow>,
    ): Boolean {
        val visibleForProvider = allRows.any {
            it.canonicalId == canonicalId && it.providerId == providerId
        }
        if (!visibleForProvider) return false
        val offerings = db.offeringDao().byProvider(providerId)
        for (offering in offerings) {
            if (offering.hidden) continue
            if (offering.providerModelId.isBlank()) continue
            val canonical = try {
                db.providerModelAliasDao().get(offering.providerId, offering.providerModelId)?.canonicalModelId
            } catch (_: Exception) {
                null
            } ?: runCatching { canonicalIdFor(offering.providerModelId) }.getOrNull()
                ?: continue
            if (canonical != canonicalId) continue
            if ((offering.providerId + "\u0000" + offering.providerModelId) !in allRows
                    .filter { it.canonicalId == canonicalId }
                    .map { it.providerId + "\u0000" + it.providerModelId }
                    .toSet()
            ) {
                continue
            }
            val endpoint = try {
                db.endpointDao().getById(offering.endpointId)
            } catch (_: Exception) {
                null
            } ?: continue
            if (endpoint.providerId != providerId || endpoint.endpointUrl.isBlank()) continue
            return true
        }
        return false
    }
}
