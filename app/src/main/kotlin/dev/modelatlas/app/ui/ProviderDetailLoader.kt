package dev.modelatlas.app.ui

import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.HealthAggregateRow
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.SnapshotKind
import dev.modelatlas.app.data.parseScopeKey
import dev.modelatlas.app.data.toManualConfig
import dev.modelatlas.app.data.toSample
import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

/**
 * 驗證用的選定 scope（safe id only）。缺任一維度即無法證明該列在此金鑰／endpoint／
 * group／operation 上已驗證（回未驗證），不以 provider 層級 evidence 或其他 key 的
 * 樣本代替。維度皆為**精確相等**比對，空白不當 wildcard。
 */
data class ProviderDetailVerificationScope(
    val providerId: String,
    val accountId: String,
    val credentialId: String,
    val endpointId: String,
    val operation: String = "",
    val group: String? = null,
)

/** 驗證事實的最小鍵：同一 model 在不同 endpoint 是不同 offering／不同證據。 */
data class ProviderDetailVerifiedKey(val endpointId: String, val modelId: String)

/** Keep provider last-good facts while invalidating only an obsolete selected-scope projection. */
internal fun providerDetailScopeProjection(detail: ProviderDetailState, fresh: Boolean): ProviderDetailState =
    if (fresh) detail else detail.copy(
        actionScope = null,
        models = detail.models.map { it.copy(verified = false) },
    )

/**
 * PROV-001 repair PD：Provider Detail 組裝（UI 側，只讀）。
 *
 * TRACEABILITY：PROV-001（PRODUCT_SPEC §4 Provider card／Detail：
 * Overview／Models／Performance／History；Models FREE NOW 優先、非免費 dim＋下沉；
 * 不要常駐空欄位）＋ MODEL-006（§8 manual model id hidden offering）。
 *
 * - `providerId → ProviderDetailState`：header／endpoints／accounts＋四分頁資料。
 * - 只讀：全部經既有 DAO（provider／endpoint／offering／credential／sample／
 *   snapshotFact／evidence／providerConfig）；不 INSERT／UPDATE／DELETE，
 *   不動 last-good，不碰 vault Secrets。CR-006 起本檔 production 路徑不再經
 *   `RoomCompareLiveSampleFeed.finalSamples()`（H1：驗證 uncapped exact、
 *   歷史 bounded newest-200）。
 * - 所有缺失 UNKNOWN：缺列／缺值一律 UNKNOWN／null／空集合，不捏造、不填零值、
 *   不拿 0／免費冒充未知。
 * - **verified 只能由選定 scope 的真實手動測試樣本證明**（§6 不同 Credential／Group／
 *   Endpoint／Operation 的資料不互相覆蓋）：provider 層級的 `verificationEvidenceId`
 *   不足以標「已驗證」；必須有同 provider／account／credential／endpoint／operation／group
 *   的 `MANUAL_TEST` 最終 SUCCESS 樣本；無選定 scope 一律未驗證（不拿 detail 第一把 key
 *   猜），hidden candidate 在 promote 前一律未驗證。
 * - manual／official／observed／external 不互冒：source 白名單見
 *   [snapshotSourceLabel]，未知回 ""（畫面顯示未知）。
 * - Secret 邊界：只經手 safe metadata（label／group／enabled／counts／timestamps），
 *   絕無原始值。
 * - 導航由 `AtlasScreen` 以 Screen-local state 持有（本檔不碰 VM／Repository）。
 *
 * VM/Repo hook（由主 AI 接）：`onSync／onCompleteCheckin／onOpenCheckin` 走既有
 * VM／Intent 路徑；模型動作（新增／立即測試／取消）由 VM 單一 action 狀態驅動，
 * 本檔只提供 scope 投影與精確驗證讀取。
 */
object ProviderDetailLoader {

    const val UNKNOWN = "UNKNOWN"

    /** 歷史事件上限（只追加展示；過多截斷尾部，不改寫 last-good 語意）。 */
    const val HISTORY_LIMIT = 200

    /**
     * CR-006：evidence 批次讀取上限（每批綁定值 ≤400，遠低於 SQLite 999 變數上限）。
     * 只影響單批綁定數，不對驗證或歷史事實總量做截斷。
     */
    const val FACT_EVIDENCE_CHUNK = 400

    /**
     * 效能 24h 窗口長度（CR-005 C2；`windowStart = nowMillis - PERFORMANCE_WINDOW_MILLIS`）。
     * 不 clamp 到 0：DAO 契約中 since=0 代表 all-time。
     */
    const val PERFORMANCE_WINDOW_MILLIS: Long = 24L * 60 * 60 * 1000

    /** 輕量 telemetry 點（samples 真列投影；排序用 millis，展示才轉 label）。 */
    data class TelemetryPoint(
        val observedAt: Long,
        val outcome: String,
        val status: String = "",
    )

    /**
     * 輕量快照點（CR-006 C5/P2：snapshot_facts 中間事實真列投影；未知 kind 不進此列，
     * 由呼叫端過濾）。CR-006 追加三個 defaulted provenance 欄位：
     * - [statusLabel]：fact.status 原值 trim（空白回 ""）。
     * - [scopeLabel]：七維 scope 結構化可讀標籤（見 [scopeLabelOf]；raw 值不 trim，
     *   null／空字串各有明確 marker）；絕不是原始 encoded scopeKey。
     * - [sourceLabel]：evidence source 經 [ModelDetailLoader.priceSourceLabel]
     *   白名單映射（manual／official；未知一律 ""，不冒充）。
     */
    data class SnapshotPoint(
        val kind: String,
        val observedAt: Long,
        val state: String,
        val statusLabel: String = "",
        val scopeLabel: String = "",
        val sourceLabel: String = "",
    )

    /** state 字串 fail-closed 轉 PriceState 名（未知回 UNKNOWN，永不合成 FREE）。 */
    fun priceStateOf(raw: String?): String {
        val clean = raw?.trim()?.uppercase().orEmpty()
        if (clean.isEmpty()) return PriceState.UNKNOWN.name
        return runCatching { PriceState.valueOf(clean) }.getOrDefault(PriceState.UNKNOWN).name
    }

    /** state 字串 fail-closed 轉 HealthState 名（未知回 UNKNOWN）。 */
    fun healthOf(raw: String?): String {
        val clean = raw?.trim()?.uppercase().orEmpty()
        if (clean.isEmpty()) return HealthState.UNKNOWN.name
        return runCatching { HealthState.valueOf(clean) }.getOrDefault(HealthState.UNKNOWN).name
    }

    /**
     * state 字串 fail-closed 轉 [QuotaState] 名（CR-005 C1，型別化配額解析器）。
     * trim／uppercase 後 valueOf；空白／任何解析失敗一律回 [QuotaState.UNKNOWN.name]。
     * 不跨 coerce PriceState 值（例如 `FREE_NOW`／`PAID` 不是合法 QuotaState，
     * 一律 UNKNOWN），也絕不合成 FREE。
     */
    fun quotaStateOf(raw: String?): String {
        val clean = raw?.trim()?.uppercase().orEmpty()
        if (clean.isEmpty()) return QuotaState.UNKNOWN.name
        return runCatching { QuotaState.valueOf(clean) }.getOrDefault(QuotaState.UNKNOWN).name
    }

    /**
     * 共識取值（純函式）：全一致才取值，任一分歧或全空即 UNKNOWN（絕不挑一列冒充）。
     * 空白視為缺席忽略；若忽略後無值亦回 UNKNOWN。
     */
    fun consensusOf(values: List<String>): String {
        val present = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (present.isEmpty()) return UNKNOWN
        val first = present.first()
        return if (present.all { it == first }) first else UNKNOWN
    }

    /** 同 provider 連線列的價格／配額共識（沿用 Repository enrichment 語意，不另起算法）。 */
    fun priceConsensusOf(connections: List<ConnectionItem>): String =
        consensusOf(connections.map { it.priceState })

    fun quotaConsensusOf(connections: List<ConnectionItem>): String =
        consensusOf(connections.map { it.quotaState })

    /** 逐 model 價格共識（同 model 多列一致才取值，分歧回 UNKNOWN）。 */
    fun priceByModel(connections: List<ConnectionItem>): Map<String, String> =
        connections.groupBy { it.modelId.trim() }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, rows) -> priceStateOf(consensusOf(rows.map { it.priceState })) }

    /** Header 組裝（純函式；缺值全 UNKNOWN／null，不捏造）。 */
    @Suppress("LongParameterList")
    fun headerOf(
        providerId: String,
        name: String,
        baseUrl: String,
        websiteUrl: String? = null,
        adapterType: String? = null,
        officialHealth: String? = null,
        observedHealth: String? = null,
        priceState: String? = null,
        quotaState: String? = null,
        credentialCount: Int = 0,
        freeNowCount: Int? = null,
        checkinUrl: String? = null,
        requiresDailyCheckin: Boolean = false,
        checkinPending: Boolean? = null,
        lastSyncedLabel: String? = null,
        syncing: Boolean = false,
    ): ProviderDetailHeader = ProviderDetailHeader(
        providerId = providerId,
        name = name,
        baseUrl = baseUrl.ifBlank { UNKNOWN },
        websiteUrl = websiteUrl?.takeIf { it.isNotBlank() },
        adapterType = adapterType?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN,
        officialHealth = healthOf(officialHealth),
        observedHealth = healthOf(observedHealth),
        priceState = priceStateOf(priceState),
        quotaState = quotaStateOf(quotaState),
        credentialCount = credentialCount.coerceAtLeast(0),
        freeNowCount = freeNowCount?.coerceAtLeast(0),
        checkinUrl = checkinUrl?.takeIf { it.isNotBlank() },
        requiresDailyCheckin = requiresDailyCheckin,
        checkinPending = checkinPending,
        lastSyncedLabel = lastSyncedLabel?.takeIf { it.isNotBlank() },
        syncing = syncing,
    )

    /** 端點列（`EndpointEntity` 投影；`EndpointEntity` 無 enabled 欄，故恆 true，不臆測停用）。 */
    fun endpointsOf(endpoints: List<EndpointEntity>): List<ProviderDetailEndpoint> =
        endpoints.sortedBy { it.endpointId }.map { e ->
            ProviderDetailEndpoint(
                endpointId = e.endpointId,
                endpointType = e.protocol,
                url = e.endpointUrl.ifBlank { e.baseUrl },
                protocol = e.protocol,
                enabled = true,
            )
        }

    /** millis → 裝置本地 "yyyy-MM-dd HH:mm"（null 即 null，不補 0 時間）。 */
    fun formatTimestamp(millis: Long?): String? {
        if (millis == null) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    /**
     * 帳號／憑證列（CR-005 C4，safe metadata only；絕無原始值）。
     *
     * 以持久化 `credential.accountId` 為真實帳號 identity 分組，憑證為子列：
     * - account 依 accountId 遞增；children 依 credentialId 遞增。
     * - 零憑證的 account 仍保留（空 children），不合成憑證。
     * - orphan credential（其 accountId 不在 accounts 表）仍以持久化 accountId 呈現，
     *   不捏造 AccountEntity／label。
     * - 子列 safe metadata：`usableCounts[cid]?.coerceAtLeast(0)`，缺席即 null（未知，
     *   不填 0）；空白 group → null；`lastSuccessAt／lastFailureAt` 缺席即 null label。
     * 絕無 secret 原始值。
     */
    fun accountsOf(
        accounts: List<AccountEntity>,
        credentials: List<CredentialEntity>,
        usableCounts: Map<String, Int> = emptyMap(),
    ): List<ProviderDetailAccount> {
        val byAccount = LinkedHashMap<String, MutableList<ProviderDetailCredential>>()
        for (c in credentials.sortedBy { it.credentialId }) {
            byAccount.getOrPut(c.accountId) { ArrayList() } += ProviderDetailCredential(
                credentialId = c.credentialId,
                label = c.label,
                group = c.group?.takeIf { it.isNotBlank() },
                enabled = c.enabled,
                usableModels = usableCounts[c.credentialId]?.coerceAtLeast(0),
                lastSuccessLabel = formatTimestamp(c.lastSuccessAt),
                lastFailureLabel = formatTimestamp(c.lastFailureAt),
            )
        }
        // 真實 account identity＋orphan 憑證的 persisted accountId 一併保留；零憑證
        // account 維持空 children。全部升冪輸出（spec P5 順序契約）。
        val ids = LinkedHashSet<String>()
        for (a in accounts) ids += a.accountId
        ids += byAccount.keys
        return ids.sorted().map { accountId ->
            ProviderDetailAccount(
                accountId = accountId,
                credentials = byAccount[accountId].orEmpty(),
            )
        }
    }

    /**
     * 模型列（`OfferingEntity` 真列＋逐 model 價格共識）。
     *
     * @param verifiedKeys **選定 scope** 的驗證事實（見 [verifiedKeysOf]）。null＝
     *   未提供 scope，沿用 offering 自身的 `verificationEvidenceId`（provider 層級），
     *   僅保留給既有純函式呼叫端；產品路徑（[loadDetail]）一律傳入非 null 集合，
     *   即使為空也不會顯示任何「已驗證」。
     * @param preferredEndpointId 選定連線的 endpoint。同名 model 在多個 endpoint 有
     *   各自獨立 offering 與證據，去重時優先呈現選定 endpoint 的那筆；同一 endpoint
     *   再優先呈現有選定 scope 證據者，避免另一個 endpoint 的證據掛到本列。
     */
    @Suppress("LongParameterList")
    fun modelsOf(
        offerings: List<OfferingEntity>,
        priceStates: Map<String, String> = emptyMap(),
        verifiedKeys: Set<ProviderDetailVerifiedKey>? = null,
        preferredEndpointId: String? = null,
    ): List<ProviderDetailModel> {
        val preferred = preferredEndpointId?.takeIf { it.isNotBlank() }
        val scopeKeys = verifiedKeys
        val ordered = offerings.sortedWith(
            compareBy(
                { if (preferred != null && it.endpointId == preferred) 0 else 1 },
                { if (scopeKeys != null && it.verifiedIn(scopeKeys)) 0 else 1 },
                { it.id },
            ),
        )
        val seen = LinkedHashSet<String>()
        val out = ArrayList<ProviderDetailModel>()
        for (o in ordered) {
            val modelId = o.providerModelId.trim()
            if (modelId.isEmpty() || !seen.add(modelId)) continue
            out += ProviderDetailModel(
                modelId = modelId,
                priceState = priceStateOf(priceStates[modelId]),
                verified = verifiedKeyOf(o, verifiedKeys),
                manual = o.manual,
                hidden = o.hidden,
            )
        }
        return out
    }

    /** 選定 scope 的證據鍵（modelId 精確比對，不 trim、不當 wildcard）。 */
    private fun OfferingEntity.verifiedKey(): ProviderDetailVerifiedKey =
        ProviderDetailVerifiedKey(endpointId, providerModelId)

    /** hidden／removed 候選永遠不算已驗證（未 promote 不得當可用）。 */
    private fun OfferingEntity.verifiedIn(keys: Set<ProviderDetailVerifiedKey>): Boolean =
        !hidden && verifiedKey() in keys

    /** 單筆 offering 的 verified 判定（scope 集合優先；null 才退回 evidence 存在性）。 */
    private fun verifiedKeyOf(
        offering: OfferingEntity,
        verifiedKeys: Set<ProviderDetailVerifiedKey>?,
    ): Boolean {
        val scopeKeys = verifiedKeys
        return if (scopeKeys == null) {
            !offering.hidden && offering.verificationEvidenceId != null
        } else {
            offering.verifiedIn(scopeKeys)
        }
    }

    /**
     * 選定 scope 的驗證事實（純函式、可單測）。
     *
     * 只有同時滿足下列**全部**條件的最終樣本才算手動驗證證據：
     * 同 provider、同 account、同 credential、同 endpoint、同 operation、同 group、
     * 同 modelId，且 `source = MANUAL_TEST`、`outcome = SUCCESS`、`completedAt` 非 null。
     * 任何一個維度不同即不算（§6 不同金鑰／群組／endpoint／操作不互相覆蓋），
     * `scope` 為 null 或缺 endpoint／operation 代表沒有可用的選定連線 → 回空集合
     * （fail-closed，不猜 detail 第一把 key）。modelId／group 皆精確相等，空白不當
     * wildcard；official offering 的真實流量（PLAYGROUND 等）不冒充手動驗證。
     */
    fun verifiedKeysOf(
        samples: List<PerformanceSample>,
        scope: ProviderDetailVerificationScope?,
    ): Set<ProviderDetailVerifiedKey> {
        val target = scope?.takeIf {
            it.providerId.isNotBlank() && it.endpointId.isNotBlank() && it.operation.isNotBlank()
        } ?: return emptySet()
        val out = LinkedHashSet<ProviderDetailVerifiedKey>()
        for (s in samples) {
            if (s.providerId != target.providerId) continue
            if (s.completedAt == null || s.outcome != Outcome.SUCCESS) continue
            if (s.source != TelemetrySource.MANUAL_TEST) continue
            if (s.accountId != target.accountId || s.safeCredentialId != target.credentialId) continue
            if (s.endpointId != target.endpointId || s.operation != target.operation) continue
            if (s.group != target.group) continue
            if (s.providerModelId.isEmpty() || s.providerModelId != s.providerModelId.trim()) continue
            out += ProviderDetailVerifiedKey(s.endpointId, s.providerModelId)
        }
        return out
    }

    /**
     * 效能摘要（純函式；無已判定樣本即 null，不補零）。
     * 多模型列合併採總和成功率＋非 null 均值（與 [dev.modelatlas.app.data.HealthRollup]
     * 同語意）；success1h 無 per-hour 真值故恆 null（不拿全期冒充）。
     *
     * CR-005 C3：成功率改用雙讀分工——
     * - [rows]（all-time）：tps／ttft／latency／requestCount，語意不變；null 條件
     *   仍只看 [rows]（空或 decided<=0）。
     * - [successWindowRows]（真 24h 窗口）：window decided>0 時
     *   `success24h = window successes/decided`；window 空／decided<=0 回 null
     *   （未知，不補 0、絕不拿 all-time 成功率冒充 24h）。
     * 預設參數 `successWindowRows = rows` 保留既有純測試呼叫端語意。
     */
    fun performanceOf(
        rows: List<HealthAggregateRow>,
        successWindowRows: List<HealthAggregateRow> = rows,
    ): ProviderDetailPerformance? {
        if (rows.isEmpty()) return null
        val decided = rows.sumOf { it.decided }
        if (decided <= 0) return null
        val successes = rows.sumOf { it.successes }.coerceIn(0, decided)
        val tps = rows.mapNotNull { it.avgTps }.takeIf { it.isNotEmpty() }?.average()
        val ttft = rows.mapNotNull { it.avgTtftMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val latency = rows.mapNotNull { it.avgLatencyMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val windowDecided = successWindowRows.sumOf { it.decided }
        val success24h = if (windowDecided > 0) {
            successWindowRows.sumOf { it.successes }.coerceIn(0, windowDecided).toDouble() /
                windowDecided.toDouble()
        } else {
            null
        }
        return ProviderDetailPerformance(
            tps = tps,
            ttftMs = ttft,
            latencyMs = latency,
            success1h = null,
            success24h = success24h,
            requestCount = rows.sumOf { it.total }.toLong().coerceAtLeast(0),
        )
    }

    /**
     * 成功率 → 百分比字串（CR-005 C3，fail-closed 截斷 formatter）。
     *
     * 只接受 local-telemetry 比例（successes/decided，0..1）：
     * - null、NaN／非有限值、或超出 [0,1] → null（畫面顯示未知，不渲染數字）。
     * - 其餘以 epsilon 截斷 `((ratio*100.0)+1e-9).toInt()`，與 FilterSheet
     *   PercentSlider 的截斷慣例一致；並加 sub-1.0 上限：**ratio < 1.0 永不渲染成
     *   100%**（epsilon 不得把 0.999…9 抬成 100，一律 cap 99）；精確 1.0 → 100%。
     * Provider-reported 效能（store 內 0..100 單位）不經此 formatter，不得 rescale。
     */
    fun successRatePercentLabel(ratio: Double?): String? {
        if (ratio == null || !ratio.isFinite() || ratio < 0.0 || ratio > 1.0) return null
        val percent = ((ratio * 100.0) + 1e-9).toInt()
        val capped = if (ratio < 1.0) percent.coerceAtMost(99) else 100
        return capped.toString() + "%"
    }

    /** Overview（純函式；手填備註以外全未知，不以 tags 或他組資料冒充）。 */
    fun overviewOf(userNotes: String?): ProviderDetailOverview = ProviderDetailOverview(
        userNotes = userNotes?.takeIf { it.isNotBlank() },
    )

    /** 快照來源 → 展示 label 白名單（未知一律 ""，不冒充）。 */
    fun snapshotSourceLabel(source: String): String {
        val s = source.trim()
        if (s.isEmpty()) return ""
        if (s == "USER") return "manual"
        if (s in OFFICIAL_EXACT_SOURCES) return "official"
        for (prefix in OFFICIAL_SOURCE_PREFIXES) {
            if (s.startsWith(prefix)) return "official"
        }
        return ""
    }

    /**
     * 歷史事件組裝（純函式）：telemetry＋快照點按 observedAt 倒序合併展示，
     * 超 [HISTORY_LIMIT] 截斷尾部（merge cap 200 契約不變）；一次 sync failure
     * 不抹 last-good（本檔只展示，不寫回）。
     *
     * CR-006 P3：快照點新增的 statusLabel／scopeLabel／sourceLabel 以具名參數
     * 映射進 [ProviderDetailHistoryEvent]（欄位由 UI 層 additively 提供）；
     * telemetry 事件維持既有 outcome／observed 語意不變。
     */
    fun historyOf(
        telemetry: List<TelemetryPoint>,
        snapshots: List<SnapshotPoint>,
    ): List<ProviderDetailHistoryEvent> {
        data class Ranked(val at: Long, val event: ProviderDetailHistoryEvent)
        val ranked = ArrayList<Ranked>(telemetry.size + snapshots.size)
        for (t in telemetry) {
            val outcome = t.outcome.trim().takeIf { it.isNotEmpty() } ?: UNKNOWN
            ranked += Ranked(
                t.observedAt,
                ProviderDetailHistoryEvent(
                    observedAtLabel = formatTimestamp(t.observedAt) ?: UNKNOWN,
                    section = "TELEMETRY",
                    stateLabel = outcome,
                    sourceLabel = "observed",
                ),
            )
        }
        for (s in snapshots) {
            val kind = s.kind.trim().takeIf { it.isNotEmpty() } ?: continue
            ranked += Ranked(
                s.observedAt,
                ProviderDetailHistoryEvent(
                    observedAtLabel = formatTimestamp(s.observedAt) ?: UNKNOWN,
                    section = kind,
                    stateLabel = s.state.trim().takeIf { it.isNotEmpty() } ?: UNKNOWN,
                    sourceLabel = s.sourceLabel,
                    statusLabel = s.statusLabel,
                    scopeLabel = s.scopeLabel,
                ),
            )
        }
        return ranked.sortedByDescending { it.at }.take(HISTORY_LIMIT).map { it.event }
    }

    /**
     * DB 真載入（suspend，IO 執行；呼叫端經 `Dispatchers.IO`）。
     *
     * @param connections 該 provider 的管理連線列（Repository enrichment 真值；
     *   模型價格共識＋header 價格／配額共識的唯一來源，不另查價）。
     * @param syncing／[lastSyncedLabel] 由 Dedicated／NewApi 產品流狀態透傳（不推導）。
     * @param checkinPending 由管理連線列透傳（本檔不算日期，不捏造）。
     * @param selectedConnectionId 使用者**明確選取**的連線；null 或不屬於本 provider
     *   時，模型一律不標已驗證（不猜另一把 key），並回 `actionScope = null` 供畫面
     *   顯示選擇指引。
     * @param selectedGroup 選定連線的 explicit group（VM 為單一真相；同 key 才沿用）。
     * @param nowMillis 注入時鐘（CR-005 C2；尾參 additive，既有呼叫端不需改）：
     *   真實 24h 窗口 `windowStart = nowMillis - [PERFORMANCE_WINDOW_MILLIS]`。
     *   不 clamp 到 0——DAO 契約中 since=0 代表 all-time，clamp 會把窗口變成全期。
     *
     * 效能雙讀（DAO 不變，均為 since 下界 `startedAt >= sinceMillis`）：all-time
     * `healthAggregates(providerId, 0L)` 供 tps／ttft／latency／requestCount；
     * 窗口 `healthAggregates(providerId, windowStart)` 供 success24h。
     * **窗口讀取失敗（null）一律傳 `emptyList()` → success24h 未知（null），
     * 絕不回退 all-time、不把全期成功率改名當 24h。**
     * Upper bound 為已記錄延後項：DAO 只有下界，未來時鐘偏移的列（startedAt >
     * nowMillis）可能被計入窗口；CR-005 不改 DAO，upper bound 修正延後到允許
     * DAO 變更的 slice。
     *
     * CR-006（C5/D1/D2/D3/H1）讀取分工：
     * - **驗證**（非 customOnly）：獨立 uncapped exact
     *   `SampleDao.verifiedManualKeys`（六維精確＋MANUAL_TEST/SUCCESS/final），
     *   不經任何歷史 cap；customOnly 維持既有 anchor＋availability 路徑不變。
     * - **歷史 telemetry**：`recentFinalsByProvider` bounded newest-200 finals，
     *   只進 History 展示，永不流回驗證。
     * - **快照歷史**：snapshot_facts 中間事實（非 heads-only），每 kind 最新 200，
     *   evidence 批次 ≤400，scope 精確解析＋malformed 過濾。
     * - 本檔 production 路徑**不呼叫 `finalSamples()`**。
     *
     * 失敗語意：任何**未預期**例外都回 `loadFailed = true` 的空殼（不捏造資料）。
     * 呼叫端據此保留同 provider 的 last-good 並明示資料非最新，而不是以空殼覆蓋。
     */
    suspend fun loadDetail(
        db: AtlasDatabase,
        providerId: String,
        connections: List<ConnectionItem> = emptyList(),
        syncing: Boolean = false,
        lastSyncedLabel: String? = null,
        checkinPending: Boolean? = null,
        selectedConnectionId: String? = null,
        selectedGroup: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProviderDetailState {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        try {
            coroutineContext.ensureActive()
            val mine = connections.filter { it.providerId == providerId }
            val provider = orNull { db.providerDao().getById(providerId) }
            val endpoints = orNull { db.endpointDao().byProvider(providerId) } ?: emptyList()
            val offerings = orNull { db.offeringDao().byProvider(providerId) } ?: emptyList()
            val credentials = orNull {
                db.credentialDao().search(providerId, null, null, null, null, null)
            } ?: emptyList()
            // CR-005 C4：真實 account identity 來源（讀取失敗 → 空清單＝未知，
            // 不以 credentialId 冒充 accountId、不捏造帳號）。
            val accounts = orNull { db.accountDao().byProvider(providerId) } ?: emptyList()
            val usableCounts = HashMap<String, Int>()
            for (c in credentials) {
                orNull { db.availabilityDao().byCredential(c.credentialId).size }
                    ?.let { usableCounts[c.credentialId] = it }
            }
            val config = orNull { db.providerConfigDao().getByProvider(providerId)?.toManualConfig() }
            // 選定連線的 management 記錄（含 endpointId／operation／anonymous）。
            val managed = orNull {
                db.connectionDao().observeManagement().first().filter { it.providerId == providerId }
            } ?: emptyList()
            // 只有真實可用的選定連線才供應 scope：auth=NONE 的匿名列照實成立，
            // 其餘必須有啟用中的憑證；停用／資料毀損一律當作沒有可用 scope。
            val selected = managed.firstOrNull { it.id == selectedConnectionId }
                ?.takeIf { it.anonymous || (it.credentialId.isNotBlank() && it.credentialEnabled) }
            // Custom-only auth has no Bearer ref in the engine sample. The selected anchor
            // still needs its own persisted availability plus the exact promotion evidence.
            val customOnly = selected != null && !selected.anonymous && config != null &&
                (config.authMode == AuthMode.API_KEY || config.authMode == AuthMode.CUSTOM_HEADER) &&
                config.credentials.none { it.role == CredentialRole.INFERENCE } &&
                config.credentials.none { it.role == CredentialRole.ACCOUNT && it.safeCredentialId == selected.credentialId } &&
                config.customAuthFields.any { it.safeCredentialId == selected.credentialId } &&
                config.customAuthFields.all { field ->
                    credentials.any { it.credentialId == field.safeCredentialId && it.enabled } &&
                        config.credentials.none { it.role == CredentialRole.ACCOUNT && it.safeCredentialId == field.safeCredentialId }
                }
            val scope = selected?.let {
                ProviderDetailVerificationScope(
                    providerId = providerId,
                    accountId = it.accountId,
                    credentialId = if (customOnly) "" else it.credentialId,
                    endpointId = it.endpointId,
                    operation = it.protocol,
                    group = selectedGroup,
                )
            }
            // CR-006 H1/P1：驗證與歷史讀取分離。驗證走獨立 uncapped exact
            // `verifiedManualKeys`（不經任何 LIMIT/歷史 cap，超出最新 200 筆的
            // 舊成功樣本仍可證明已驗證）；本路徑不再呼叫 `finalSamples()`。
            // 歷史 telemetry 另走 bounded newest-200 finals（見 recentFinalSamples），
            // 只進展示、永不流回驗證。
            // customOnly 不走 exact 查詢（既有 anchor＋availability 路徑 byte-unchanged，
            // 下面整組覆寫）。
            var verifiedKeys = if (customOnly) emptySet() else exactVerifiedKeys(db, scope)
            if (customOnly && selected != null && scope != null) {
                val boundOfferings = (orNull { db.availabilityDao().byCredential(selected.credentialId) } ?: emptyList())
                    .filter { it.accountId == selected.accountId && it.group == selectedGroup }
                    .map { it.offeringId }.toSet()
                val anchoredKeys = (orNull { db.sampleDao().customAnchorVerifiedModels(
                    providerId, selected.accountId, selected.credentialId, selected.endpointId,
                    selected.protocol, selectedGroup,
                ) } ?: emptyList()).map { ProviderDetailVerifiedKey(it.endpointId, it.providerModelId) }.toSet()
                verifiedKeys = offerings.filter { offering ->
                    offering.id in boundOfferings && !offering.hidden &&
                        ProviderDetailVerifiedKey(offering.endpointId, offering.providerModelId) in anchoredKeys
                }.map { ProviderDetailVerifiedKey(it.endpointId, it.providerModelId) }.toSet()
            }
            val prices = priceByModel(mine)
            val models = modelsOf(
                offerings = offerings,
                priceStates = prices,
                verifiedKeys = verifiedKeys,
                preferredEndpointId = scope?.endpointId,
            )
            val freeNow = models.count { it.priceState == PriceState.FREE_NOW.name }
                .takeIf { models.isNotEmpty() }
            val baseUrl = endpoints.firstOrNull()?.baseUrl
                ?: mine.firstOrNull()?.baseUrl
                ?: UNKNOWN
            val header = headerOf(
                providerId = providerId,
                name = provider?.name ?: "",
                baseUrl = baseUrl,
                websiteUrl = config?.websiteUrl,
                adapterType = config?.resolvedAdapter?.name,
                officialHealth = provider?.officialHealth,
                observedHealth = provider?.observedHealth,
                priceState = priceConsensusOf(mine),
                quotaState = quotaConsensusOf(mine),
                credentialCount = credentials.size,
                freeNowCount = freeNow,
                checkinUrl = provider?.checkinUrl,
                requiresDailyCheckin = provider?.requiresDailyCheckin ?: false,
                checkinPending = checkinPending,
                lastSyncedLabel = lastSyncedLabel,
                syncing = syncing,
            )
            // CR-005 C2 雙讀：all-time 供 tps/ttft/latency/requestCount；
            // 真 24h 窗口（注入時鐘）供 success24h。窗口讀取失敗 → emptyList
            // （success24h 未知），絕不回退 all-time。
            val allTimeRows = orNull { db.sampleDao().healthAggregates(providerId, 0L) }
            val windowStart = nowMillis - PERFORMANCE_WINDOW_MILLIS
            val windowRows = orNull { db.sampleDao().healthAggregates(providerId, windowStart) }
                ?: emptyList()
            val performance = allTimeRows?.let { performanceOf(it, windowRows) }
            val telemetry = telemetryPointsOf(recentFinalSamples(db, providerId))
            val snapshots = loadSnapshotPoints(db, providerId)
            coroutineContext.ensureActive()
            return ProviderDetailState(
                header = header,
                endpoints = endpointsOf(endpoints),
                accounts = accountsOf(accounts, credentials, usableCounts),
                overview = overviewOf(config?.notes),
                models = models,
                performance = performance,
                history = historyOf(telemetry, snapshots),
                actionScope = selected?.let { record ->
                    val item = mine.firstOrNull { it.id == record.id }
                    ProviderDetailActionScope(
                        connectionId = record.id,
                        connectionName = item?.name?.takeIf { it.isNotBlank() } ?: record.name,
                        credentialLabel = item?.credentialLabel ?: record.credentialLabel,
                        accountId = record.accountId.takeIf { it.isNotBlank() },
                        credentialId = record.credentialId.takeIf { it.isNotBlank() },
                        group = selectedGroup?.takeIf { it.isNotBlank() },
                        // 只有真實匿名（auth=NONE）列才標匿名；缺 credentialId 的資料毀損
                        // 不得被說成匿名。
                        anonymous = record.anonymous,
                    )
                },
                connectionChoices = connectionChoicesOf(managed),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fail-closed：不捏造任何欄位，並標記本次載入失敗讓呼叫端保留 last-good。
            coroutineContext.ensureActive()
            return ProviderDetailState(
                header = headerOf(providerId = providerId, name = "", baseUrl = UNKNOWN),
                loadFailed = true,
            )
        }
    }

    /** 讀取失敗回 null，但取消一律傳播（不吞掉呼叫端的結構化並行）。 */
    private inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * 可明確選取的連線（safe metadata only；依 account／credential／endpoint／operation
     * 去重，同一把 key 多個 model 只呈現一列，避免清單爆炸與同名按鈕）。
     * 停用憑證仍列出但 disabled，讓使用者看得到「有這把但不能用」。
     */
    private fun connectionChoicesOf(
        managed: List<ConnectionRecord>,
    ): List<ProviderDetailConnectionChoice> {
        val byScope = LinkedHashMap<String, ProviderDetailConnectionChoice>()
        for (record in managed.sortedBy { it.id }) {
            val scopeKey = listOf(
                record.accountId,
                record.credentialId,
                record.endpointId,
                record.protocol,
                record.anonymous.toString(),
            ).joinToString("|")
            val existing = byScope[scopeKey]
            if (existing == null) {
                byScope[scopeKey] = ProviderDetailConnectionChoice(
                    connectionId = record.id,
                    name = record.name,
                    credentialLabel = record.credentialLabel,
                    modelId = record.modelId,
                    endpointId = record.endpointId,
                    operation = record.protocol,
                    enabled = record.anonymous || record.credentialEnabled,
                    anonymous = record.anonymous,
                )
            } else {
                byScope[scopeKey] = existing.copy(modelCount = existing.modelCount + 1)
            }
        }
        return byScope.values.toList()
    }

    /** 該 provider 的本地 telemetry 點（samples 真列，source=observed）。 */
    private fun telemetryPointsOf(samples: List<PerformanceSample>): List<TelemetryPoint> =
        samples.mapNotNull { s ->
            val at = s.completedAt ?: s.requestStartedAt
            TelemetryPoint(
                observedAt = at,
                outcome = s.outcome.name,
                status = s.httpStatus?.let { "HTTP $it" } ?: "",
            )
        }

    /**
     * CR-006 H1：bounded newest-200 finals 供 History telemetry 展示
     * （`recentFinalsByProvider`，`completedAt IS NOT NULL`，startedAt DESC）。
     * 輸出**只供展示**，永不流回驗證；dedup by sample.id＋精確 provider 防禦
     * （DAO 已精確過濾，此處為 defense-in-depth）。不呼叫 `finalSamples()`。
     */
    private suspend fun recentFinalSamples(db: AtlasDatabase, providerId: String): List<PerformanceSample> {
        val finals = orNull {
            db.sampleDao().recentFinalsByProvider(providerId, HISTORY_LIMIT)
        } ?: emptyList()
        return finals.asSequence()
            .filter { it.providerId == providerId }
            .map { it.toSample() }
            .distinctBy { it.id }
            .toList()
    }

    /**
     * CR-006 H1/P1：選定 scope 的驗證事實（production 路徑）。
     *
     * 獨立 uncapped exact 查詢（[SampleDao.verifiedManualKeys]，無 depth LIMIT，
     * 只回 distinct endpoint/model keys 而非完整樣本），與 bounded 歷史讀取**分離**：
     * 超出最新 200 筆歷史的舊成功樣本仍能證明已驗證（T6 契約）。
     * SQL 端已精確比對 provider/account/credential/endpoint/operation＋`group` IS
     * ＋MANUAL_TEST/SUCCESS/final；此處保留 Kotlin trim/blank 防禦
     * （SQLite TRIM 只去 0x20，loader 端重驗一次）。
     * 缺 provider／endpoint／operation 的 scope 一律 fail-closed 空集合，
     * 與 [verifiedKeysOf] 同語意（該純函式保留給既有呼叫端與單測）。
     * Normal／anonymous 都走此 exact 查詢（匿名 scope 的 credentialId 本來就是 ""，
     * 其樣本 safeCredentialId 也是 ""）；只有 customOnly 在 loadDetail 端改走
     * 既有 anchor＋availability 路徑，不經此函式。
     */
    private suspend fun exactVerifiedKeys(
        db: AtlasDatabase,
        scope: ProviderDetailVerificationScope?,
    ): Set<ProviderDetailVerifiedKey> {
        val target = scope?.takeIf {
            it.providerId.isNotBlank() && it.endpointId.isNotBlank() && it.operation.isNotBlank()
        } ?: return emptySet()
        val rows = orNull {
            db.sampleDao().verifiedManualKeys(
                target.providerId,
                target.accountId,
                target.credentialId,
                target.endpointId,
                target.operation,
                target.group,
            )
        } ?: emptyList()
        return rows.mapNotNull { m ->
            val modelId = m.providerModelId
            if (modelId.isBlank() || modelId != modelId.trim()) return@mapNotNull null
            // endpointId 只做 blank 拒絕＋精確相等（trim hygiene 只適用 providerModelId，
            // 不對 persisted endpoint identity 套用 padded 拒絕）。
            if (m.endpointId.isBlank()) return@mapNotNull null
            ProviderDetailVerifiedKey(m.endpointId, modelId)
        }.toSet()
    }

    /**
     * 該 provider 的快照歷史點（CR-006 C5/P2：**snapshot_facts 中間事實**，
     * 不再 heads-only）。
     *
     * - 8 個既有 kind 各讀最新 [HISTORY_LIMIT] 筆（`recentFactsForProviderKind`
     *   range port：provider 長度前綴邊界，無 LIKE）；合併後由 [historyOf]
     *   維持 merge cap 200。中間（非 head）事實一併進 History。
     * - evidence 以 `EvidenceDao.byIds` 批次讀取，每批 ≤[FACT_EVIDENCE_CHUNK]
     *   （綁定值遠低於 SQL 999 上限）；source 經
     *   [ModelDetailLoader.priceSourceLabel] 白名單映射，未知一律 ""。
     * - scopeKey 必須可精確 [parseScopeKey] 且第 0 維**精確等於** providerId；
     *   malformed／其他 provider 列一律略過，不猜、不當 wildcard。
     * - state 來自 fact.state（事件本身狀態），status 來自 fact.status；
     *   scope label 為七維結構化可讀標籤（[scopeLabelOf]），絕不呈現原始
     *   encoded scopeKey。
     * - kind／chunk 邊界即時 [ensureActive] 檢查取消（不 sleep、不吞取消）；
     *   查詢失敗 fail-closed 該 leg 回空（不捏造），取消一律傳播。
     */
    private suspend fun loadSnapshotPoints(db: AtlasDatabase, providerId: String): List<SnapshotPoint> {
        return try {
            val factDao = db.snapshotFactDao()
            val evidenceDao = db.evidenceDao()
            val evidenceSources = HashMap<String, String>()
            val out = ArrayList<SnapshotPoint>()
            for (kind in SnapshotKind.values()) {
                coroutineContext.ensureActive()
                val facts = orNull {
                    factDao.recentFactsForProviderKind(providerId, kind.name, HISTORY_LIMIT)
                } ?: emptyList()
                if (facts.isEmpty()) continue
                val ids = facts.map { it.evidenceId }.filter { it.isNotBlank() }.distinct()
                for (chunk in ids.chunked(FACT_EVIDENCE_CHUNK)) {
                    coroutineContext.ensureActive()
                    for (e in orNull { evidenceDao.byIds(chunk) } ?: emptyList()) {
                        evidenceSources.putIfAbsent(e.id, e.source)
                    }
                }
                for (fact in facts) {
                    // malformed／跨 provider 一律略過（exact parse＋provider 過濾）。
                    val parts = parseScopeKey(fact.scopeKey) ?: continue
                    if (parts.getOrNull(0) != providerId) continue
                    val state = fact.state.trim().takeIf { it.isNotEmpty() } ?: continue
                    out += SnapshotPoint(
                        kind = kind.name,
                        observedAt = fact.observedAt,
                        state = state,
                        statusLabel = fact.status.trim(),
                        scopeLabel = scopeLabelOf(parts),
                        sourceLabel = evidenceSources[fact.evidenceId]
                            ?.let { ModelDetailLoader.priceSourceLabel(it) }
                            .orEmpty(),
                    )
                }
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 七維 scope 結構化可讀標籤（provider/account/key/model/group/endpoint/operation）。
     *
     * Evidence History 需要可辨識的**完整七維**：每維一律呈現，**raw 值不 trim、
     * 不省略**，避免 `" vip "` 與 `"vip"`、null 與 `""` 在展示上不可區分：
     * - null 維（寫入端 `-1:`）→ 穩定 unknown marker：`group=null`。
     * - 空字串維 → 明確 empty marker（帶引號可見）：`group=''`。
     * - 其餘非 null 值 → 單引號包住 raw 值（保留 padded spaces 可見）：
     *   `group=' vip '`。
     * 值為 safe id／group 等 safe ref（scopeKey 內本就無 raw secret）；欄位間以
     * 空白分隔，值含引號時的展示歧義不影響身份：**此 label 僅供展示，不得被任何
     * 呼叫端解析回身份**。絕不回傳原始 encoded scopeKey（長度前綴編碼不得當
     * label）。`parts` 非 7 維（理論不可達）回 ""。
     */
    private fun scopeLabelOf(parts: List<String?>): String {
        if (parts.size != 7) return ""
        val names = listOf("provider", "account", "key", "model", "group", "endpoint", "operation")
        val out = ArrayList<String>(7)
        for (i in names.indices) {
            val raw = parts[i]
            val rendered = when {
                raw == null -> "null"
                raw.isEmpty() -> "''"
                else -> "'$raw'"
            }
            out += names[i] + "=" + rendered
        }
        return out.joinToString(" ")
    }

    /** 官方來源精確白名單（與寫入端一致；大小寫敏感）。 */
    private val OFFICIAL_EXACT_SOURCES = setOf(
        "newapi/api/pricing",
        "newapi/api/user/self",
        "newapi/api/usage/token",
    )

    /** 官方來源前綴白名單（dedicated／網關觀測；僅此六前綴）。 */
    private val OFFICIAL_SOURCE_PREFIXES = listOf(
        "nvidia-hosted/",
        "nvidia-selfhosted/",
        "amd/",
        "openrouter/",
        "gemini/",
        "groq/",
    )
}
