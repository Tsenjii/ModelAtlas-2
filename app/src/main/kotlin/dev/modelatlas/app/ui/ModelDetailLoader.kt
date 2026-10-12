package dev.modelatlas.app.ui

import android.database.Cursor
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.ExternalStore
import dev.modelatlas.app.data.RoomCompareLiveSampleFeed
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.app.data.toSample
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.domain.canonicalIdFor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * P6 repair worker B：MODEL-009 Detail 真載入（UI 側，只讀）。
 *
 * TRACEABILITY：MODEL-009（PRODUCT_SPEC §6/§7/§8 Detail、§10 provenance、§11 telemetry/history；
 * Intelligence Contract §13 Model Detail、§23.3、§23.4、§23.11）。
 * CR-006（C5/D1/D2/D3/H1）scoped detail/history/bounded reads：
 * - 從 DB 真表載入：price_snapshots（價格歷史點）／quota_snapshots（配額池）／
 *   snapshot_facts（state 真相：price state／quota state 按 snapshotId 關聯）／
 *   samples（本地 telemetry：performance＋history events，source=observed）／
 *   external_observations（外部 enrichment，source=external）。
 * - Requesting-provider identity（D2）：Detail 從具體 [ModelRow] 開啟即 provider-owned；
 *   alias 以（providerId, providerModelId）pair 收集且限定 row.providerId，canonical 只用於
 *   alias mapping 與 telemetry canonical leg；另一 provider 的 alias 即使 canonical 相同
 *   也不得捐 facts/samples；canonical 字串絕不當全域 raw modelId。
 * - Bounded reads（D3/H1）：raw SQL 每次查詢 <=400 個 IN binds；price／quota 每 chunk
 *   `ORDER BY observedAt DESC, id ASC LIMIT 500`（取各 chunk 最新 500），合併去重後取
 *   整體最新 500；顯示 ASC 由 builder 一次完成。facts state join 只吃 bounded 查詢回傳的
 *   snapshotId（<=400 分塊、first-wins），不全表掃 snapshot_facts。telemetry 走 scoped DAO
 *   legs＋dedup by sample.id、最新 200 封頂；production 載入不呼叫 finalSamples。
 * - Quota pool 相關性（D1）：provider-global（account＋credential 皆 NULL/空）、selected
 *   account 無 credential 池、或 exact selected account/key；空 selected credential 即匿名
 *   exact scope；不借 account 缺席時的非空外來 credential、不暴露 sibling account/key 池；
 *   SelectedKey.group 不使其他 scope 變 wildcard。七維 scope 與 source 每點／每池完整
 *   保留，不合併、不平均。
 * - 只讀：全部經既有 DAO／openHelper 唯讀 query；不 INSERT／UPDATE／DELETE，不動
 *   last-good、不寫 membership/history。
 * - Fail-closed：任一查詢失敗 → 該維度空（Unknown 明示）；全失敗 → 回只有 row 透傳的
 *   空歷史 state。取消直接傳播：新 query/openCursor 明確先接 CancellationException
 *   再接一般例外，chunk／leg 之間 ensureActive，不引入吞取消的 runCatching。
 * - manual／official／observed／external 不互冒：source 白名單映射，未知回 ""。
 * - 舊 public helpers（collectModelIds／loadPricePoints／loadQuotaPools／
 *   loadTelemetryEvents／loadExternalEvents）簽名保留給 legacy 呼叫端；production
 *   loadDetail 一律走 *Scoped 系列與 bounded joins。
 * - FR-003 primary quote port（本片新增）：`selectedQuote` 只取 exact selected
 *   account/key/group/endpoint/operation 的最新 price point（`matchesPrimaryPriceScope`
 *   ＋`selectPrimaryQuote`，與 CR-004 B1 row policy `matchesPriceHead` 逐分支同語意）；
 *   retained 完整 7D `priceHistory` 不因此過濾／縮水；缺 selected quote → null
 *   （Unknown），不借 sibling scope、不以 lastGood 冒充 current（quote 埠不讀
 *   snapshot_heads.lastGoodSnapshotId）；fixedPrice 欄位保留但目前 UI 未渲染
 *   （本片不加新 fixedPrice UI surface）。
 */
object ModelDetailLoader {

    /** 快照來源分類（白名單 enum；未知一律 UNKNOWN，不冒充）。 */
    enum class DetailSnapshotSource {
        MANUAL,
        OFFICIAL,
        UNKNOWN,
    }

    /** 官方來源精確白名單（snapshot source 原值；大小寫敏感，與寫入端一致）。 */
    private val OFFICIAL_EXACT_SOURCES = setOf(
        "newapi/api/pricing",
        "newapi/api/user/self",
        "newapi/api/usage/token",
    )

    /** 官方來源前綴白名單（dedicated／網關觀測；僅此六前綴，非前綴匹配不算官方）。 */
    private val OFFICIAL_SOURCE_PREFIXES = listOf(
        "nvidia-hosted/",
        "nvidia-selfhosted/",
        "amd/",
        "openrouter/",
        "gemini/",
        "groq/",
    )

    /** 快照來源 → 分類 enum（白名單；不在表即 UNKNOWN）。 */
    fun snapshotSourceOf(source: String): DetailSnapshotSource {
        val s = source.trim()
        if (s.isEmpty()) return DetailSnapshotSource.UNKNOWN
        if (s == "USER") return DetailSnapshotSource.MANUAL
        if (s in OFFICIAL_EXACT_SOURCES) return DetailSnapshotSource.OFFICIAL
        for (prefix in OFFICIAL_SOURCE_PREFIXES) {
            if (s.startsWith(prefix)) return DetailSnapshotSource.OFFICIAL
        }
        return DetailSnapshotSource.UNKNOWN
    }

    /** 快照來源 → Detail sourceLabel 白名單（未知一律 ""，不冒充）。 */
    fun priceSourceLabel(source: String): String = when (snapshotSourceOf(source)) {
        DetailSnapshotSource.MANUAL -> "manual"
        DetailSnapshotSource.OFFICIAL -> "official"
        DetailSnapshotSource.UNKNOWN -> ""
    }

    /** snapshot kind → DetailQuotaKind（未知 kind 回 null＝略過該列，不猜）。 */
    fun quotaKindFor(kind: String): DetailQuotaKind? = when (kind.trim().uppercase()) {
        "ACCOUNT_QUOTA" -> DetailQuotaKind.ACCOUNT_QUOTA
        "TOKEN_QUOTA" -> DetailQuotaKind.TOKEN_QUOTA
        "MANUAL_QUOTA" -> DetailQuotaKind.MANUAL_QUOTA
        "DEDICATED_ACCOUNT_QUOTA" -> DetailQuotaKind.DEDICATED_ACCOUNT_QUOTA
        "DEDICATED_TOKEN_QUOTA" -> DetailQuotaKind.DEDICATED_TOKEN_QUOTA
        else -> null
    }

    /** 價格類 kind 白名單（snapshot_facts）。 */
    fun isPriceKind(kind: String): Boolean {
        val k = kind.trim().uppercase()
        return k == "PRICE" || k == "MANUAL_PRICE" || k == "DEDICATED_PRICE"
    }

    /** 配額類 kind 白名單（snapshot_facts）。 */
    fun isQuotaKind(kind: String): Boolean {
        val k = kind.trim().uppercase()
        return k == "ACCOUNT_QUOTA" || k == "TOKEN_QUOTA" || k == "MANUAL_QUOTA" ||
            k == "DEDICATED_ACCOUNT_QUOTA" || k == "DEDICATED_TOKEN_QUOTA"
    }

    /** state 字串 fail-closed 轉 PriceState（未知回 UNKNOWN，永不回 FREE）。 */
    fun priceStateOf(raw: String?): PriceState =
        runCatching { PriceState.valueOf(raw?.trim()?.uppercase() ?: "") }
            .getOrDefault(PriceState.UNKNOWN)

    /** state 字串 fail-closed 轉 QuotaState（未知回 UNKNOWN）。 */
    fun quotaStateOf(raw: String?): QuotaState =
        runCatching { QuotaState.valueOf(raw?.trim()?.uppercase() ?: "") }
            .getOrDefault(QuotaState.UNKNOWN)

    /** CR-006 scoped telemetry 上限（dedup＋provider defense 後最新 200 封頂）。 */
    const val TELEMETRY_LIMIT = 200

    /** 單次 raw SQL 最多 IN binds（<999；provider 前置＋ids 皆在限內）。 */
    private const val SQL_CHUNK = 400

    /** price／quota 每 chunk `ORDER BY observedAt DESC, id ASC LIMIT 500`，合併去重後取整體最新 500。 */
    private const val SNAPSHOT_LIMIT = 500

    /**
     * CR-006（D2）requesting-provider scoped model identity：alias pair。
     * Detail 從具體 ModelRow 開啟即 provider-owned；不同 provider 的 alias 即使
     * canonical 相同也不得捐 facts/samples。pair 內 providerModelId 必非空白。
     */
    data class ScopedModelPair(val providerId: String, val providerModelId: String)

    /**
     * FR-003 primary quote 的 selected offering scope（endpoint／operation）。
     *
     * - 值必須來自實際 persisted offering／endpoint protocol（AtlasViewModel 既有
     *   `matchedOffering.endpointId` ＋ `endpointDao.getById(...).protocol` 模式），loader
     *   不自行猜測、不挑第一個 offering。
     * - null／blank／"UNKNOWN"＝未知 scope，只匹配 price point 同為未知 scope 者；
     *   未知 ≠ wildcard（與 `ModelRowMapper.matchesPriceHead` CR-004 B1 同語意）。
     * - 未由 UI consumer 傳入時視為未知 scope（fail-closed，不借具體值）。
     */
    data class SelectedOfferingScope(
        val endpointId: String? = null,
        val operation: String? = null,
    )

    /** FR-003 未知 scope 維度：null／blank／"UNKNOWN"（trim＋uppercase，CR-004 B1 同語意）。 */
    internal fun isUnknownPriceScopeDim(raw: String?): Boolean =
        raw == null || isUnknownPriceScopeText(raw)

    internal fun isUnknownPriceScopeText(raw: String): Boolean {
        val normalized = raw.trim().uppercase()
        return normalized.isEmpty() || normalized == "UNKNOWN"
    }

    /**
     * FR-003 exact selected-scope price predicate（純函式；與 `ModelRowMapper.matchesPriceHead`
     * 逐分支對齊的 detached copy，讓本檔不依賴 UI mapper 私有函式）：
     * - provider exact；modelId 必須在 provider-owned [scopedModelIds]（空 modelId 排除）。
     * - group raw exact：null==null；null ≠ "" ≠ 具體值（不正常化）。
     * - endpoint／operation：point 未知 scope 只配 offering 未知 scope；具體值須完全相等。
     *   未知 ≠ wildcard。
     * - credential：null（官方 PAT 定價）恆可，與 selected key 無關；非 null 須等於
     *   selected credentialId。
     * - account：selected 非空須 exact；selected 空只允許 point 未知（NULL／空）。
     * 本函式不看 observedAt、不做 source ranking（時間與 tie 由 [selectPrimaryQuote] 處理）。
     */
    @Suppress("LongParameterList")
    internal fun matchesPrimaryPriceScope(
        providerId: String,
        accountId: String?,
        credentialRef: String?,
        modelId: String,
        group: String?,
        endpointId: String?,
        operation: String?,
        requestingProviderId: String,
        scopedModelIds: Set<String>,
        selected: SelectedKey,
        offeringScope: SelectedOfferingScope?,
    ): Boolean {
        if (providerId != requestingProviderId) return false
        if (modelId.isBlank() || modelId !in scopedModelIds) return false
        if (group != selected.group) return false
        if (!priceDimensionMatches(endpointId, offeringScope?.endpointId)) return false
        if (!priceDimensionMatches(operation, offeringScope?.operation)) return false
        val credential = credentialRef
        if (credential != null && credential != selected.credentialId) return false
        val account = accountId
        if (selected.accountId.isNotBlank()) {
            if (account != selected.accountId) return false
        } else if (account != null && account.isNotBlank()) {
            return false
        }
        return true
    }

    private fun priceDimensionMatches(pointDim: String?, offeringDim: String?): Boolean =
        if (isUnknownPriceScopeDim(pointDim)) isUnknownPriceScopeDim(offeringDim)
        else pointDim == offeringDim

    /**
     * FR-003 primary quote selector（純函式）：
     * - [candidates] 必須已通過 [matchesPrimaryPriceScope]（不得在此重新做 scope 過濾）。
     * - 空候選→null；唯一最新 observedAt→該點；同一最新 observedAt 多於一個候選
     *   （跨 sourceLabel／id）＝無可排名依據，回 null（Unknown，不猜）。
     * - 絕不以 builder 顯示排序（scopeKey／sourceLabel）當選擇語意，亦不回退 lastGood。
     */
    internal fun selectPrimaryQuote(candidates: List<DetailPricePoint>): DetailPricePoint? {
        if (candidates.isEmpty()) return null
        val newestObservedAt = candidates.maxOf { it.observedAt }
        return candidates.filter { it.observedAt == newestObservedAt }.singleOrNull()
    }

    /**
     * DB 真載入（suspend，IO 執行）。legacy 兩參呼叫端：無 selected scope，
     * 只暴露 provider-global pools（委派 [loadDetail] 三參版本 selected=null）。
     *
     * @param row 目前點選列（canonicalId＋priceState／quotaState 共識透傳＋performance 透傳）。
     */
    suspend fun loadDetail(db: AtlasDatabase, row: ModelRow): ModelDetailState =
        loadDetail(db, row, null)

    /**
     * CR-006 production 載入（requesting-provider scoped＋bounded reads）。
     *
     * @param row 具體點選列：row.providerId 擁有本次讀取；row.providerModelId／
     *   canonicalId 構成 requesting pair fallback；canonicalId 不得空白。
     * @param selected 明確選取的 account/key（safe id only）；null＝只暴露
     *   provider-global pools；非 null＝用實際 selected account/key 取值，
     *   絕不借另一 account/key。空白 credentialId＝匿名 exact scope；
     *   [SelectedKey.group] 不使其他 scope 變 wildcard。
     *
     * FR-003：此 legacy 三參入口不帶 offering scope（endpoint／operation 視為未知
     * scope）。完整精確報價請用新增的四參重載並傳入 persisted offering scope。
     */
    suspend fun loadDetail(
        db: AtlasDatabase,
        row: ModelRow,
        selected: SelectedKey?,
    ): ModelDetailState = loadDetail(db, row, selected, null)

    /**
     * FR-003 production 載入（primary quote 精確 selected scope）。
     *
     * @param selectedOfferingScope persisted offering 的 endpoint／operation（由 UI
     *   consumer 提供；null＝未知 scope，只允許未知 scope 的 price point 成候選，
     *   未知 ≠ wildcard）。loader 不查 offering 表猜測、不挑第一個 offering。
     *
     * 輸出 [ModelDetailState.selectedQuote]：
     * - selected == null 或 selected scope 無可判定候選 → null（Unknown／unavailable）。
     * - 唯一最新 observedAt 的 exact selected-scope 點 → 該點（含七維 scope＋state＋
     *   source＋observedAt；數值原樣，null＝未知，不填零）。
     * - 同最新 observedAt 多候選／讀取失敗 → null（fail-closed，不借 sibling、不回退
     *   lastGood；完整 7D priceHistory 不受影響）。
     */
    suspend fun loadDetail(
        db: AtlasDatabase,
        row: ModelRow,
        selected: SelectedKey?,
        selectedOfferingScope: SelectedOfferingScope?,
    ): ModelDetailState {
        require(row.canonicalId.isNotBlank()) { "canonicalId must not be blank" }
        require(row.providerId.isNotBlank()) { "providerId must not be blank" }
        try {
            coroutineContext.ensureActive()
            val scopedPairs = collectScopedModelPairs(db, row)
            // Pair 已限定 requesting provider；再防禦性過濾一次（exact provider defense）。
            val scopedModelIds = scopedPairs
                .filter { it.providerId == row.providerId }
                .map { it.providerModelId }
                .toSet()
            coroutineContext.ensureActive()
            val pricePoints = loadPricePointsScoped(db, row.providerId, scopedModelIds)
            coroutineContext.ensureActive()
            // FR-003：只選 exact selected scope 的報價；retained history 不動。
            val selectedQuote = if (selected == null) {
                null
            } else {
                loadSelectedQuoteScoped(
                    db = db,
                    providerId = row.providerId,
                    modelIds = scopedModelIds,
                    selected = selected,
                    offeringScope = selectedOfferingScope,
                )
            }
            coroutineContext.ensureActive()
            val pools = loadQuotaPoolsScoped(db, row.providerId, scopedModelIds, selected)
            coroutineContext.ensureActive()
            val extras = ArrayList<DetailHistoryEvent>()
            extras += loadTelemetryEventsScoped(db, row.canonicalId, row.providerId, scopedModelIds)
            extras += loadExternalEvents(db, row.canonicalId)
            coroutineContext.ensureActive()
            return ModelDetailStateBuilder.build(
                canonicalId = row.canonicalId,
                displayName = row.displayName,
                priceState = row.priceState,
                quotaState = row.quotaState,
                priceHistory = pricePoints,
                quotaPools = pools,
                performance = DetailPerformance(
                    success1h = row.success1h,
                    success24h = row.success24h,
                    tps = row.tps,
                    ttftMs = row.ttftMs,
                    latencyMs = row.latencyMs,
                ),
                extraEvents = extras,
                selectedQuote = selectedQuote,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fail-closed：全失敗回 row 透傳空歷史（與 VM 舊行為一致）。
            // selectedQuote 由 builder 預設 null＝Unknown，絕不借既有資料冒充。
            coroutineContext.ensureActive()
            return ModelDetailStateBuilder.build(
                canonicalId = row.canonicalId,
                displayName = row.displayName,
                priceState = row.priceState,
                quotaState = row.quotaState,
                performance = DetailPerformance(
                    success1h = row.success1h,
                    success24h = row.success24h,
                    tps = row.tps,
                    ttftMs = row.ttftMs,
                    latencyMs = row.latencyMs,
                ),
            )
        }
    }

    /**
     * CR-006（D2）requesting-provider scoped alias pairs（production identity 來源）。
     *
     * - alias 表真值＋requesting pair fallback，全部限定 [ModelRow.providerId]；
     *   另一 provider 的 alias（即使 canonical 相同）不入集合。
     * - 只收 mapped-to-canonical 的 alias＋explicit requested pair；canonical 字串
     *   不當 raw modelId fallback（legacy collectModelIds 的行為不適用 production）。
     * - 失敗 fail-closed：回 requesting pair（或空集合，若連 providerModelId 都空白）。
     */
    suspend fun collectScopedModelPairs(db: AtlasDatabase, row: ModelRow): Set<ScopedModelPair> {
        try {
            val out = LinkedHashSet<ScopedModelPair>()
            val aliases = try {
                db.providerModelAliasDao().byCanonical(row.canonicalId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            for (a in aliases) {
                // Requesting-provider filter：另一 provider 的 alias 不得捐 identity。
                if (a.providerId != row.providerId) continue
                if (a.providerModelId.isBlank()) continue
                out += ScopedModelPair(row.providerId, a.providerModelId)
            }
            if (row.providerModelId.isNotBlank()) {
                val anchor = try {
                    db.providerModelAliasDao().get(row.providerId, row.providerModelId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // Preserve the explicit row fallback, but a known newer mapping to another
                // canonical must not donate that model's facts to a stale detail row.
                if (anchor == null || anchor.canonicalModelId == row.canonicalId) {
                    out += ScopedModelPair(row.providerId, row.providerModelId)
                }
            }
            return out
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return if (row.providerModelId.isBlank()) {
                emptySet()
            } else {
                setOf(ScopedModelPair(row.providerId, row.providerModelId))
            }
        }
    }

    /**
     * CR-006（D2/D3）scoped 價格歷史點：provider exact＋modelId IN（<=400 分塊），
     * 每 chunk 最新 500（DESC,id ASC），合併去重後取整體最新 500；facts state join
     * 只吃回傳的 snapshotId。七維 scope＋source 每點保留；顯示 ASC 由 builder 排序。
     */
    suspend fun loadPricePointsScoped(
        db: AtlasDatabase,
        providerId: String,
        modelIds: Set<String>,
    ): List<DetailPricePoint> {
        if (modelIds.isEmpty()) return emptyList()
        return try {
            val snapshots = queryPriceSnapshotsScoped(db, providerId, modelIds)
            coroutineContext.ensureActive()
            val states = queryPriceFactStatesBySnapshotIds(db, snapshots.map { it.id }.toSet())
            snapshots.map { s ->
                DetailPricePoint(
                    observedAt = s.observedAt,
                    inputPrice = s.input,
                    outputPrice = s.output,
                    fixedPrice = s.fixed,
                    cachePrice = s.cache,
                    state = states[s.id]?.let { priceStateOf(it) } ?: PriceState.UNKNOWN,
                    sourceLabel = priceSourceLabel(s.source),
                    // 七維 scope 全保留（不同 scope 不互相覆蓋、不合併、不平均）。
                    providerId = s.providerId,
                    accountId = s.accountId,
                    credentialRef = s.credentialId,
                    modelId = s.modelId,
                    group = s.group,
                    endpointId = s.endpointId,
                    operation = s.operation,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * FR-003 exact selected-scope primary quote（唯讀、fail-closed）。
     *
     * - 專用查詢（[queryNewestPrimaryPriceRows]）在 SQL 先套 selected relevance
     *   （relevance BEFORE LIMIT，與 CR-006 quota D1 修正同型），再由
     *   [matchesPrimaryPriceScope] 逐列防禦重驗（與 SQL 同 policy，防未來漂移）。
     * - state 只由 bounded snapshot_facts join 取得（缺 fact＝UNKNOWN）；完全不讀
     *   snapshot_heads／lastGoodSnapshotId，絕不以 lastGood 冒充 current。
     * - 候選為空、SQL／facts 失敗、或最新 observedAt 多候選（跨 source／id）→ null
     *   （Unknown／unavailable），不借 sibling account/key/group/endpoint/operation。
     * - 完整 7D 歷史由 [loadPricePointsScoped] 另行保留，本函式不過濾歷史。
     */
    suspend fun loadSelectedQuoteScoped(
        db: AtlasDatabase,
        providerId: String,
        modelIds: Set<String>,
        selected: SelectedKey,
        offeringScope: SelectedOfferingScope?,
    ): DetailPricePoint? {
        return try {
            val rows = queryNewestPrimaryPriceRows(db, providerId, modelIds, selected, offeringScope)
                .filter { row ->
                    matchesPrimaryPriceScope(
                        providerId = row.providerId,
                        accountId = row.accountId,
                        credentialRef = row.credentialId,
                        modelId = row.modelId,
                        group = row.group,
                        endpointId = row.endpointId,
                        operation = row.operation,
                        requestingProviderId = providerId,
                        scopedModelIds = modelIds,
                        selected = selected,
                        offeringScope = offeringScope,
                    )
                }
            if (rows.isEmpty()) return null
            coroutineContext.ensureActive()
            val states = queryPriceFactStatesBySnapshotIds(db, rows.map { it.id }.toSet())
            selectPrimaryQuote(rows.map { row -> row.toDetailPricePoint(states[row.id]) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * CR-006（D1/D3）scoped 配額池：model leg（provider exact＋IN 分塊）＋空 model
     * pool leg（provider exact），每 leg 最新 500（DESC,id ASC），合併去重後取整體
     * 最新 500；row 級相關性由 [isRelevantQuotaScope] 在 Kotlin 精確過濾（SQL 不表達
     * NULL/空＋selected 組合）。facts state join 只吃回傳的 snapshotId；七維 scope
     * ＋source 每池保留，不合併、不平均；顯示排序由 builder 一次完成。
     */
    suspend fun loadQuotaPoolsScoped(
        db: AtlasDatabase,
        providerId: String,
        modelIds: Set<String>,
        selected: SelectedKey?,
    ): List<DetailQuotaPool> {
        return try {
            val snapshots = queryQuotaSnapshotsScoped(db, providerId, modelIds, selected)
            coroutineContext.ensureActive()
            val factBySnapshot = queryQuotaFactStatesBySnapshotIds(db, snapshots.map { it.id }.toSet())
            val out = ArrayList<DetailQuotaPool>()
            for (s in snapshots) {
                val fact = factBySnapshot[s.id]
                val kind = fact?.kind?.let { quotaKindFor(it) } ?: continue
                out += DetailQuotaPool(
                    kind = kind,
                    providerId = s.providerId,
                    accountId = s.accountId,
                    credentialRef = s.credentialId,
                    state = quotaStateOf(fact.state),
                    remainingRaw = fact.remaining,
                    usedRaw = fact.used,
                    grantedRaw = fact.granted,
                    unlimited = fact.unlimited,
                    expiresAtRaw = fact.expires,
                    observedAt = s.observedAt,
                    sourceLabel = priceSourceLabel(s.source),
                    modelId = s.modelId,
                    group = s.group,
                    endpointId = s.endpointId,
                    operation = s.operation,
                )
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * CR-006（D1）quota row 的 selected-scope 相關性（純函式，row 級精確過濾）。
     *
     * - provider 必等於 requesting provider（SQL 已限定，此為 exact provider defense）。
     * - model 維度：非空 modelId 必須在 scoped ids 內（alias pair 已 provider 限定）；
     *   空 modelId＝pool 列（provider／account 級池），仍須通過 account/key 相關性。
     * - selected == null：只允許 provider-global（account＋credential 皆 NULL/空）。
     * - selected != null，僅三種相關：
     *   1) provider-global（account＋credential 皆 NULL/空）；
     *   2) selected account 無 credential 池（account 相等＋credential NULL/空）；
     *   3) exact selected account/key（account 相等＋credential 相等；空白 selected
     *      credential 即匿名 exact scope，精確相等）。
     * - account 缺席時的非空 credential＝外來 credential，不借；account 相異＝
     *   sibling account 池，不暴露；同 account 相異非空 credential＝sibling key 池，
     *   不暴露。SelectedKey.group 不參與本判定（不使其他 scope 變 wildcard）。
     */
    @Suppress("LongParameterList")
    internal fun isRelevantQuotaScope(
        providerId: String,
        accountId: String?,
        credentialId: String?,
        modelId: String,
        scopedModelIds: Set<String>,
        selected: SelectedKey?,
        requestingProviderId: String,
    ): Boolean {
        if (providerId != requestingProviderId) return false
        if (modelId.isNotEmpty() && modelId !in scopedModelIds) return false
        val accountAbsent = accountId.isNullOrEmpty()
        val credentialAbsent = credentialId.isNullOrEmpty()
        if (selected == null) return accountAbsent && credentialAbsent
        if (accountAbsent) return credentialAbsent
        if (accountId != selected.accountId) return false
        return credentialAbsent || credentialId == selected.credentialId
    }

    /**
     * CR-006（H1）scoped 本地 telemetry 事件：新 DAO legs（provider exact＋modelIds、
     * provider exact＋canonical）→ 合併 dedup by sample.id → 最新 200 封頂 → 真
     * [SampleEntity.toSample] 映射。exact provider defense 逐列把關；production
     * 載入不經 finalSamples／舊 feed。
     */
    suspend fun loadTelemetryEventsScoped(
        db: AtlasDatabase,
        canonicalId: String,
        providerId: String,
        modelIds: Set<String>,
    ): List<DetailHistoryEvent> {
        return try {
            coroutineContext.ensureActive()
            val byModels = ArrayList<SampleEntity>()
            for (chunk in modelIds.filter { it.isNotBlank() }.chunked(SQL_CHUNK - 2)) {
                coroutineContext.ensureActive()
                byModels += daoLegOrEmpty {
                    db.sampleDao().recentFinalsForProviderModels(providerId, chunk, TELEMETRY_LIMIT)
                }
            }
            coroutineContext.ensureActive()
            val byCanonical = daoLegOrEmpty {
                db.sampleDao().recentFinalsForCanonical(providerId, canonicalId, TELEMETRY_LIMIT)
            }
            coroutineContext.ensureActive()
            // Exact provider defense＋dedup by sample.id（first-wins；兩 leg 皆 DESC 新→舊）。
            val seen = LinkedHashSet<String>()
            val merged = ArrayList<SampleEntity>(byModels.size + byCanonical.size)
            for (s in byModels) {
                if (s.providerId != providerId) continue
                if (seen.add(s.id)) merged += s
            }
            for (s in byCanonical) {
                if (s.providerId != providerId) continue
                if (seen.add(s.id)) merged += s
            }
            // Bounded newest200：startedAt DESC、id ASC 穩定取最新，再映射真 sample。
            merged.sortedWith(
                compareByDescending<SampleEntity> { it.requestStartedAt }.thenBy { it.id },
            ).take(TELEMETRY_LIMIT).map { s ->
                val sample = s.toSample()
                DetailHistoryEvent(
                    observedAt = sample.completedAt ?: sample.requestStartedAt,
                    section = "TELEMETRY",
                    stateLabel = sample.outcome.name,
                    statusLabel = sample.httpStatus?.let { "HTTP $it" } ?: "",
                    sourceLabel = "observed",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 該 canonical 的全部 providerModelId（alias 表真值＋canonicalIdFor fallback）。
     * 失敗回僅含 row 自身映射（fail-closed，不清空）。
     *
     * Legacy helper：簽保留給既有純／device 呼叫端；production loadDetail 不使用
     * （其 modelId 集合跨 provider 合併，屬 D2 舊行為）。
     */
    suspend fun collectModelIds(db: AtlasDatabase, canonicalId: String): Set<String> {
        try {
            val out = LinkedHashSet<String>()
            val aliases = try {
                db.providerModelAliasDao().byCanonical(canonicalId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            for (a in aliases) {
                if (a.providerModelId.isNotBlank()) out += a.providerModelId
            }
            if (out.isEmpty()) {
                // Fallback：無 alias 列時至少能命中同名 offering（不猜新模型）。
                runCatching { canonicalIdFor(canonicalId) }
            }
            return out.ifEmpty { setOf(canonicalId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return setOf(canonicalId)
        }
    }

    /**
     * 價格歷史點（price_snapshots 真列；state 經 facts 按 snapshotId 關聯，缺席回 UNKNOWN）。
     *
     * Legacy helper：簽保留給既有呼叫端；production loadDetail 使用
     * [loadPricePointsScoped]（requesting-provider＋bounded）。
     */
    suspend fun loadPricePoints(db: AtlasDatabase, modelIds: Set<String>): List<DetailPricePoint> {
        if (modelIds.isEmpty()) return emptyList()
        return try {
            val snapshots = queryPriceSnapshots(db, modelIds)
            val states = queryFactStatesBySnapshot(db)
            snapshots.map { s ->
                DetailPricePoint(
                    observedAt = s.observedAt,
                    inputPrice = s.input,
                    outputPrice = s.output,
                    fixedPrice = s.fixed,
                    cachePrice = s.cache,
                    state = states[s.id]?.let { priceStateOf(it) } ?: PriceState.UNKNOWN,
                    sourceLabel = priceSourceLabel(s.source),
                    // FX2 精確查詢：七維 scope 全保留（不同 scope 不互相覆蓋，
                    // builder 依 scopeKey 分組再算變動／免費起算）。
                    providerId = s.providerId,
                    accountId = s.accountId,
                    credentialRef = s.credentialId,
                    modelId = s.modelId,
                    group = s.group,
                    endpointId = s.endpointId,
                    operation = s.operation,
                )
            }.sortedBy { it.observedAt }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 配額池（quota_snapshots 真列＋facts state；scope 維度全保留，不合併）。
     *
     * Legacy helper：簽保留給既有呼叫端；production loadDetail 使用
     * [loadQuotaPoolsScoped]（requesting-provider＋pool 相關性＋bounded）。
     */
    suspend fun loadQuotaPools(db: AtlasDatabase, modelIds: Set<String>): List<DetailQuotaPool> {
        return try {
            val snapshots = queryQuotaSnapshots(db, modelIds)
            val factBySnapshot = queryQuotaFactsBySnapshot(db)
            val out = ArrayList<DetailQuotaPool>()
            for (s in snapshots) {
                val fact = factBySnapshot[s.id]
                val kind = fact?.kind?.let { quotaKindFor(it) } ?: continue
                out += DetailQuotaPool(
                    kind = kind,
                    providerId = s.providerId,
                    accountId = s.accountId,
                    credentialRef = s.credentialId,
                    state = fact.state.let { quotaStateOf(it) },
                    remainingRaw = fact.remaining,
                    usedRaw = fact.used,
                    grantedRaw = fact.granted,
                    unlimited = fact.unlimited,
                    expiresAtRaw = fact.expires,
                    observedAt = s.observedAt,
                    sourceLabel = priceSourceLabel(s.source),
                    // FX2 精確查詢：quota 亦補齊 group／endpoint／operation（＋modelId），
                    // 排序含全維度，不合併不同 scope。
                    modelId = s.modelId,
                    group = s.group,
                    endpointId = s.endpointId,
                    operation = s.operation,
                )
            }
            out.sortedWith(
                compareBy<DetailQuotaPool> { it.kind.ordinal }
                    .thenBy { it.providerId }
                    .thenBy { it.accountId }
                    .thenBy { it.credentialRef }
                    .thenBy { it.modelId }
                    .thenBy { it.group }
                    .thenBy { it.endpointId }
                    .thenBy { it.operation }
                    .thenByDescending { it.observedAt ?: Long.MIN_VALUE },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 本地 telemetry 事件（samples 真列，source=observed）。
     * 經 RoomCompareLiveSampleFeed（唯讀，不干擾保存路徑）；對不上 canonical／modelIds 的列略過。
     *
     * Legacy helper：簽保留給既有呼叫端；production loadDetail 使用
     * [loadTelemetryEventsScoped]（bounded DAO legs，不呼叫 finalSamples）。
     */
    suspend fun loadTelemetryEvents(
        db: AtlasDatabase,
        canonicalId: String,
        modelIds: Set<String>,
    ): List<DetailHistoryEvent> {
        return try {
            val feed = RoomCompareLiveSampleFeed(db)
            val samples = try {
                feed.finalSamples()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return emptyList()
            }
            samples.asSequence()
                .filter { s ->
                    (s.canonicalModelId == canonicalId) ||
                        (s.providerModelId in modelIds)
                }
                .map { s ->
                    DetailHistoryEvent(
                        observedAt = s.completedAt ?: s.requestStartedAt,
                        section = "TELEMETRY",
                        stateLabel = s.outcome.name,
                        statusLabel = s.httpStatus?.let { "HTTP $it" } ?: "",
                        sourceLabel = "observed",
                    )
                }.toList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 外部 enrichment 事件（經 external_mappings join 真列，source=external；含直接掛 canonical 列）。 */
    suspend fun loadExternalEvents(db: AtlasDatabase, canonicalId: String): List<DetailHistoryEvent> {
        return try {
            // FX2 外部欄永久空修復（rv1 UI button no-op FAIL）：產品 importer 恆
            // canonicalModelId=null，直查 byCanonical 永空；改走 ExternalStore
            // .observationsForCanonical（經 external_mappings join＋直接掛 canonical 列），
            // includeExpired=true（歷史不剔除過期，只呈現）。只讀外部槽，不碰本地表。
            // CR-006：external 層維持 canonical 維度、separately labelled external
            // （importer/defaults 不變），不入 requesting-provider pair 過濾。
            val rows = try {
                ExternalStore(db).observationsForCanonical(
                    canonicalModelId = canonicalId,
                    nowMillis = System.currentTimeMillis(),
                    includeExpired = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return emptyList()
            }
            rows.map { r ->
                DetailHistoryEvent(
                    observedAt = r.fetchedAt,
                    section = "EXTERNAL",
                    stateLabel = r.confidence.takeIf { it.isNotBlank() } ?: "UNKNOWN",
                    statusLabel = "",
                    sourceLabel = "external",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- 唯讀 raw query（price/quota/facts；失敗回空，不拋） ----

    private data class PriceRow(
        val id: String,
        val providerId: String,
        val accountId: String?,
        val credentialId: String?,
        val modelId: String,
        val group: String?,
        val endpointId: String,
        val operation: String,
        val observedAt: Long,
        val input: Double?,
        val output: Double?,
        val fixed: Double?,
        val cache: Double?,
        val source: String,
    )

    private data class QuotaRow(
        val id: String,
        val providerId: String,
        val accountId: String?,
        val credentialId: String?,
        val modelId: String,
        val group: String?,
        val endpointId: String,
        val operation: String,
        val observedAt: Long,
        val source: String,
    )

    private data class FactState(
        val kind: String,
        val state: String,
        val remaining: Long?,
        val used: Long?,
        val granted: Long?,
        val unlimited: Boolean?,
        val expires: Long?,
    )

    /** Legacy：model-only IN（無 provider 限定）、ASC LIMIT 500（D2/D3 舊行為，保留給 legacy 呼叫端）。 */
    private fun queryPriceSnapshots(db: AtlasDatabase, modelIds: Set<String>): List<PriceRow> {
        if (modelIds.isEmpty()) return emptyList()
        val placeholders = modelIds.joinToString(",") { "?" }
        // FX2 精確查詢：補齊七維 scope 欄（group／endpointId／operation＋既有四維），
        // 不同 scope 各自成點，builder 依 scopeKey 分組，不跨 scope 合併。
        val sql = "SELECT \"id\", \"providerId\", \"accountId\", \"credentialId\", \"modelId\", " +
            "\"group\", \"endpointId\", \"operation\", " +
            "\"observedAt\", \"input\", \"output\", \"fixedRequest\", \"cacheRead\", \"source\" " +
            "FROM \"price_snapshots\" WHERE \"modelId\" IN ($placeholders) " +
            "ORDER BY \"observedAt\" ASC LIMIT 500"
        val cursor = runCatching {
            db.openHelper.readableDatabase.query(sql, bindArgsOf(modelIds))
        }.getOrNull() ?: return emptyList()
        return try {
            cursor.use { c ->
                val out = ArrayList<PriceRow>()
                while (c.moveToNext()) {
                    val id = c.optString("id") ?: continue
                    val modelId = c.optString("modelId") ?: continue
                    val observedAt = c.optLong("observedAt") ?: continue
                    out += PriceRow(
                        id = id,
                        providerId = c.optString("providerId") ?: "",
                        accountId = c.optString("accountId"),
                        credentialId = c.optString("credentialId"),
                        modelId = modelId,
                        group = c.optString("group"),
                        endpointId = c.optString("endpointId") ?: "",
                        operation = c.optString("operation") ?: "",
                        observedAt = observedAt,
                        input = c.optString("input")?.toDoubleOrNull(),
                        output = c.optString("output")?.toDoubleOrNull(),
                        fixed = c.optString("fixedRequest")?.toDoubleOrNull(),
                        cache = c.optString("cacheRead")?.toDoubleOrNull(),
                        source = c.optString("source") ?: "",
                    )
                }
                out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Legacy：model-only IN、ASC LIMIT 500、無 pool leg（D1/D2/D3 舊行為，保留給 legacy 呼叫端）。 */
    private fun queryQuotaSnapshots(db: AtlasDatabase, modelIds: Set<String>): List<QuotaRow> {
        // quota modelId 通常為空（provider/account 級池）；同時查「空 modelId 全 provider 池」
        // 太寬會污染 scope，故只查 modelIds 命中的模型級列＋呼叫端 provider 的空列由 facts 補。
        val ids = modelIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        // FX2 精確查詢：補齊 group／endpointId／operation（＋modelId），scope 全保留。
        val sql = "SELECT \"id\", \"providerId\", \"accountId\", \"credentialId\", \"modelId\", " +
            "\"group\", \"endpointId\", \"operation\", " +
            "\"observedAt\", \"source\" FROM \"quota_snapshots\" " +
            "WHERE \"modelId\" IN ($placeholders) ORDER BY \"observedAt\" ASC LIMIT 500"
        val cursor = runCatching {
            db.openHelper.readableDatabase.query(sql, bindArgsOf(ids))
        }.getOrNull() ?: return emptyList()
        return try {
            cursor.use { c ->
                val out = ArrayList<QuotaRow>()
                while (c.moveToNext()) {
                    val id = c.optString("id") ?: continue
                    val observedAt = c.optLong("observedAt") ?: continue
                    out += QuotaRow(
                        id = id,
                        providerId = c.optString("providerId") ?: "",
                        accountId = c.optString("accountId"),
                        credentialId = c.optString("credentialId"),
                        modelId = c.optString("modelId") ?: "",
                        group = c.optString("group"),
                        endpointId = c.optString("endpointId") ?: "",
                        operation = c.optString("operation") ?: "",
                        observedAt = observedAt,
                        source = c.optString("source") ?: "",
                    )
                }
                out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Legacy：全表 snapshot_facts 掃描（僅 legacy 呼叫端；production 走 snapshotId bounded 版本）。 */
    private fun queryFactStatesBySnapshot(db: AtlasDatabase): Map<String, String> {
        val sql = "SELECT \"snapshotId\", \"state\" FROM \"snapshot_facts\" " +
            "WHERE \"snapshotId\" IS NOT NULL AND \"kind\" IN ('PRICE','MANUAL_PRICE','DEDICATED_PRICE')"
        val cursor = runCatching {
            db.openHelper.readableDatabase.query(sql, emptyArray<Any?>())
        }.getOrNull() ?: return emptyMap()
        return try {
            cursor.use { c ->
                val out = LinkedHashMap<String, String>()
                while (c.moveToNext()) {
                    val sid = c.optString("snapshotId") ?: continue
                    val state = c.optString("state") ?: continue
                    // 同一 snapshot 多 fact（重試列）：首見保留，不後寫覆蓋。
                    out.putIfAbsent(sid, state)
                }
                out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Legacy：全表 snapshot_facts 掃描（僅 legacy 呼叫端；production 走 snapshotId bounded 版本）。 */
    private fun queryQuotaFactsBySnapshot(db: AtlasDatabase): Map<String, FactState> {
        val sql = "SELECT \"snapshotId\", \"kind\", \"state\", \"remainingRaw\", \"usedRaw\", " +
            "\"grantedRaw\", \"unlimited\", \"expiresAtRaw\" FROM \"snapshot_facts\" " +
            "WHERE \"snapshotId\" IS NOT NULL AND \"kind\" IN " +
            "('ACCOUNT_QUOTA','TOKEN_QUOTA','MANUAL_QUOTA','DEDICATED_ACCOUNT_QUOTA','DEDICATED_TOKEN_QUOTA')"
        val cursor = runCatching {
            db.openHelper.readableDatabase.query(sql, emptyArray<Any?>())
        }.getOrNull() ?: return emptyMap()
        return try {
            cursor.use { c ->
                val out = LinkedHashMap<String, FactState>()
                while (c.moveToNext()) {
                    val sid = c.optString("snapshotId") ?: continue
                    val kind = c.optString("kind") ?: continue
                    val state = c.optString("state") ?: continue
                    out.putIfAbsent(
                        sid,
                        FactState(
                            kind = kind,
                            state = state,
                            remaining = c.optLong("remainingRaw"),
                            used = c.optLong("usedRaw"),
                            granted = c.optLong("grantedRaw"),
                            unlimited = c.optBoolean("unlimited"),
                            expires = c.optLong("expiresAtRaw"),
                        ),
                    )
                }
                out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---- CR-006 scoped／bounded raw query（production loadDetail 專用） ----

    /**
     * 新 query/openCursor：取消一律傳播（明確先接 CancellationException 再接一般例外），
     * 一般失敗回 null（呼叫端 fail-closed）。不使用吞取消的 runCatching。
     */
    private fun openCursorOrNull(db: AtlasDatabase, sql: String, args: Array<Any?>): Cursor? =
        try {
            db.openHelper.readableDatabase.query(sql, args)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** 逐列讀取：取消一律傳播，一般失敗回空；cursor 一律 use 關閉。 */
    private inline fun <T> readCursorRows(cursor: Cursor, readRow: (Cursor) -> T?): List<T> =
        try {
            cursor.use { c ->
                val out = ArrayList<T>()
                while (c.moveToNext()) {
                    readRow(c)?.let { out += it }
                }
                out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    /** DAO scoped leg 包裝：取消一律傳播，一般失敗回空（fail-closed，不吞取消）。 */
    private suspend fun <T> daoLegOrEmpty(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * CR-006（D2/D3）bounded price 讀取：provider exact＋modelId IN（<=400 分塊），
     * 每 chunk `ORDER BY observedAt DESC, id ASC LIMIT 500`，chunk 間 ensureActive；
     * 合併去重後取整體最新 500（DESC,id ASC）。
     */
    private suspend fun queryPriceSnapshotsScoped(
        db: AtlasDatabase,
        providerId: String,
        modelIds: Set<String>,
    ): List<PriceRow> {
        if (providerId.isEmpty()) return emptyList()
        val ids = modelIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyList()
        val merged = LinkedHashMap<String, PriceRow>()
        for (chunk in ids.chunked(SQL_CHUNK - 1)) {
            coroutineContext.ensureActive()
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = "SELECT \"id\", \"providerId\", \"accountId\", \"credentialId\", \"modelId\", " +
                "\"group\", \"endpointId\", \"operation\", " +
                "\"observedAt\", \"input\", \"output\", \"fixedRequest\", \"cacheRead\", \"source\" " +
                "FROM \"price_snapshots\" WHERE \"providerId\" = ? AND \"modelId\" IN ($placeholders) " +
                "ORDER BY \"observedAt\" DESC, \"id\" ASC LIMIT $SNAPSHOT_LIMIT"
            val rows = readCursorRows(openCursorOrNull(db, sql, bindArgsOf(listOf(providerId) + chunk)) ?: continue) { c ->
                val id = c.optString("id") ?: return@readCursorRows null
                val modelId = c.optString("modelId") ?: return@readCursorRows null
                val observedAt = c.optLong("observedAt") ?: return@readCursorRows null
                PriceRow(
                    id = id,
                    providerId = c.optString("providerId") ?: "",
                    accountId = c.optString("accountId"),
                    credentialId = c.optString("credentialId"),
                    modelId = modelId,
                    group = c.optString("group"),
                    endpointId = c.optString("endpointId") ?: "",
                    operation = c.optString("operation") ?: "",
                    observedAt = observedAt,
                    input = c.optString("input")?.toDoubleOrNull(),
                    output = c.optString("output")?.toDoubleOrNull(),
                    fixed = c.optString("fixedRequest")?.toDoubleOrNull(),
                    cache = c.optString("cacheRead")?.toDoubleOrNull(),
                    source = c.optString("source") ?: "",
                )
            }
            for (r in rows) merged.putIfAbsent(r.id, r)
        }
        return merged.values
            .sortedWith(compareByDescending<PriceRow> { it.observedAt }.thenBy { it.id })
            .take(SNAPSHOT_LIMIT)
    }

    /**
     * FR-003 SQL 前置 predicate（relevance BEFORE LIMIT；與 [matchesPrimaryPriceScope]
     * 分支對齊）：
     * - group：null→IS NULL；非 null→= ?（null ≠ "" ≠ 具體值，raw exact）。
     * - account：selected 非空→= ?；selected 空→IS NULL OR = ''（未知 scope）。
     * - credential：IS NULL OR = ?（null 恆可＝官方 PAT 定價；非 null exact）。
     * - endpoint／operation：offering 未知→point 只允許未知（NULL／blank／"UNKNOWN"）；
     *   offering 具體→= ?（未知值配不到具體值）。
     * 不做 LIKE／不做 wildcard、不碰 modelId 以外的任何來源標籤。
     */
    private fun primaryPriceSqlPredicate(
        selected: SelectedKey,
        offeringScope: SelectedOfferingScope?,
    ): Pair<String, List<String>> {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        val group = selected.group
        if (group == null) {
            clauses += "\"group\" IS NULL"
        } else {
            clauses += "\"group\" = ?"
            args += group
        }
        if (selected.accountId.isNotBlank()) {
            clauses += "\"accountId\" = ?"
            args += selected.accountId
        } else {
            clauses += "(\"accountId\" IS NULL OR \"accountId\" = '')"
        }
        clauses += "(\"credentialId\" IS NULL OR \"credentialId\" = ?)"
        args += selected.credentialId
        appendUnknownAwarePriceDimension(clauses, args, "endpointId", offeringScope?.endpointId)
        appendUnknownAwarePriceDimension(clauses, args, "operation", offeringScope?.operation)
        return clauses.joinToString(" AND ") to args
    }

    private fun appendUnknownAwarePriceDimension(
        clauses: MutableList<String>,
        args: MutableList<String>,
        column: String,
        offeringDim: String?,
    ) {
        val dim = offeringDim
        if (dim == null || isUnknownPriceScopeText(dim)) {
            clauses += "(\"$column\" IS NULL OR UPPER(TRIM(\"$column\")) IN ('', 'UNKNOWN'))"
        } else {
            clauses += "\"$column\" = ?"
            args += dim
        }
    }

    /**
     * FR-003 exact-scope 最新 price rows 讀取（唯讀、bounded）：
     * - provider exact＋modelId IN（<=400 分塊含 provider／observedAt／predicate binds）；
     * - 每 chunk 先取該 exact scope 的 MAX(observedAt)（LIMIT 1），再取該 observedAt
     *   的列（LIMIT 2；1＝唯一，>=2＝tie 交 [selectPrimaryQuote] 判 Unknown）；
     * - 合併去重 by snapshot id；不讀 heads／facts（state 由呼叫端 bounded join）。
     * - 失敗（非取消）回空；取消一律傳播。
     */
    private suspend fun queryNewestPrimaryPriceRows(
        db: AtlasDatabase,
        providerId: String,
        modelIds: Set<String>,
        selected: SelectedKey,
        offeringScope: SelectedOfferingScope?,
    ): List<PriceRow> {
        if (providerId.isEmpty()) return emptyList()
        val ids = modelIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyList()
        val (scopeWhere, scopeArgs) = primaryPriceSqlPredicate(selected, offeringScope)
        // provider(1) + chunk(n) + scopeArgs + observedAt(1) <= SQL_CHUNK(400)
        val chunkBudget = SQL_CHUNK - 2 - scopeArgs.size
        if (chunkBudget < 1) return emptyList()
        val columns = "\"id\", \"providerId\", \"accountId\", \"credentialId\", \"modelId\", " +
            "\"group\", \"endpointId\", \"operation\", " +
            "\"observedAt\", \"input\", \"output\", \"fixedRequest\", \"cacheRead\", \"source\""
        val out = LinkedHashMap<String, PriceRow>()
        for (chunk in ids.chunked(chunkBudget)) {
            coroutineContext.ensureActive()
            val placeholders = chunk.joinToString(",") { "?" }
            val where = "\"providerId\" = ? AND \"modelId\" IN ($placeholders) AND ($scopeWhere)"
            val baseArgs: List<Any?> = listOf<Any?>(providerId) + chunk + scopeArgs
            val maxSql = "SELECT \"observedAt\" FROM \"price_snapshots\" WHERE $where " +
                "ORDER BY \"observedAt\" DESC, \"id\" ASC LIMIT 1"
            val newestObservedAt = readCursorRows(
                openCursorOrNull(db, maxSql, baseArgs.toTypedArray()) ?: continue,
            ) { c -> c.optLong("observedAt") }.firstOrNull() ?: continue
            coroutineContext.ensureActive()
            val rowsSql = "SELECT $columns FROM \"price_snapshots\" WHERE $where AND \"observedAt\" = ? " +
                "ORDER BY \"id\" ASC LIMIT 2"
            val rows = readCursorRows(
                openCursorOrNull(db, rowsSql, (baseArgs + newestObservedAt).toTypedArray()) ?: continue,
            ) { c -> readPrimaryPriceRow(c) }
            for (r in rows) out.putIfAbsent(r.id, r)
        }
        return out.values.toList()
    }

    /** FR-003 專用 row mapping（欄位與既有 queryPriceSnapshotsScoped 相同；不改既有函式）。 */
    private fun readPrimaryPriceRow(c: Cursor): PriceRow? {
        val id = c.optString("id") ?: return null
        val modelId = c.optString("modelId") ?: return null
        val observedAt = c.optLong("observedAt") ?: return null
        return PriceRow(
            id = id,
            providerId = c.optString("providerId") ?: "",
            accountId = c.optString("accountId"),
            credentialId = c.optString("credentialId"),
            modelId = modelId,
            group = c.optString("group"),
            endpointId = c.optString("endpointId") ?: "",
            operation = c.optString("operation") ?: "",
            observedAt = observedAt,
            input = c.optString("input")?.toDoubleOrNull(),
            output = c.optString("output")?.toDoubleOrNull(),
            fixed = c.optString("fixedRequest")?.toDoubleOrNull(),
            cache = c.optString("cacheRead")?.toDoubleOrNull(),
            source = c.optString("source") ?: "",
        )
    }

    private fun PriceRow.toDetailPricePoint(factState: String?): DetailPricePoint = DetailPricePoint(
        observedAt = observedAt,
        inputPrice = input,
        outputPrice = output,
        fixedPrice = fixed,
        cachePrice = cache,
        state = factState?.let { priceStateOf(it) } ?: PriceState.UNKNOWN,
        sourceLabel = priceSourceLabel(source),
        providerId = providerId,
        accountId = accountId,
        credentialRef = credentialId,
        modelId = modelId,
        group = group,
        endpointId = endpointId,
        operation = operation,
    )

    /**
     * CR-006（D1/D3）bounded quota 讀取：兩 leg——
     * 1) model leg：provider exact＋modelId IN（<=400 分塊），每 chunk 最新 500；
     * 2) pool leg：provider exact＋modelId NULL/空（provider/account 級池），最新 500。
     * row 級以 [isRelevantQuotaScope] 精確過濾（provider-global／selected account 池／
     * exact selected account+key；不借外來 credential、不暴露 sibling account/key），
     * 合併去重後取整體最新 500（DESC,id ASC）。chunk／leg 間 ensureActive。
     */
    private suspend fun queryQuotaSnapshotsScoped(
        db: AtlasDatabase,
        providerId: String,
        modelIds: Set<String>,
        selected: SelectedKey?,
    ): List<QuotaRow> {
        if (providerId.isEmpty()) return emptyList()
        val merged = LinkedHashMap<String, QuotaRow>()
        val ids = modelIds.filter { it.isNotBlank() }
        val accountAbsent = "(\"accountId\" IS NULL OR \"accountId\" = '')"
        val credentialAbsent = "(\"credentialId\" IS NULL OR \"credentialId\" = '')"
        val global = "($accountAbsent AND $credentialAbsent)"
        val relevance: String
        val relevanceArgs: List<String>
        if (selected == null || selected.accountId.isBlank()) {
            relevance = global
            relevanceArgs = emptyList()
        } else {
            relevance = "$global OR (\"accountId\" = ? AND ($credentialAbsent OR \"credentialId\" = ?))"
            relevanceArgs = listOf(selected.accountId, selected.credentialId)
        }
        // Apply relevance BEFORE LIMIT: newer sibling rows must not crowd out an older,
        // relevant pool from the bounded result set. Keep Kotlin filtering as defense.
        for (chunk in ids.chunked(SQL_CHUNK - 1 - relevanceArgs.size)) {
            coroutineContext.ensureActive()
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = "SELECT \"id\", \"providerId\", \"accountId\", \"credentialId\", \"modelId\", " +
                "\"group\", \"endpointId\", \"operation\", " +
                "\"observedAt\", \"source\" FROM \"quota_snapshots\" " +
                "WHERE \"providerId\" = ? AND \"modelId\" IN ($placeholders) AND ($relevance) " +
                "ORDER BY \"observedAt\" DESC, \"id\" ASC LIMIT $SNAPSHOT_LIMIT"
            val rows = readCursorRows(openCursorOrNull(db, sql, bindArgsOf(listOf(providerId) + chunk + relevanceArgs)) ?: continue) { c ->
                val id = c.optString("id") ?: return@readCursorRows null
                val observedAt = c.optLong("observedAt") ?: return@readCursorRows null
                QuotaRow(
                    id = id,
                    providerId = c.optString("providerId") ?: "",
                    accountId = c.optString("accountId"),
                    credentialId = c.optString("credentialId"),
                    modelId = c.optString("modelId") ?: "",
                    group = c.optString("group"),
                    endpointId = c.optString("endpointId") ?: "",
                    operation = c.optString("operation") ?: "",
                    observedAt = observedAt,
                    source = c.optString("source") ?: "",
                )
            }
            for (r in rows) {
                if (!isRelevantQuotaScope(
                        r.providerId, r.accountId, r.credentialId, r.modelId,
                        modelIds, selected, providerId,
                    )
                ) {
                    continue
                }
                merged.putIfAbsent(r.id, r)
            }
        }
        coroutineContext.ensureActive()
        // Pool leg：provider exact 的空 modelId 列（entity modelId NOT NULL，NULL 條件僅防禦）。
        val poolSql = "SELECT \"id\", \"providerId\", \"accountId\", \"credentialId\", \"modelId\", " +
            "\"group\", \"endpointId\", \"operation\", " +
            "\"observedAt\", \"source\" FROM \"quota_snapshots\" " +
            "WHERE \"providerId\" = ? AND (\"modelId\" IS NULL OR \"modelId\" = '') AND ($relevance) " +
            "ORDER BY \"observedAt\" DESC, \"id\" ASC LIMIT $SNAPSHOT_LIMIT"
        val poolRows = openCursorOrNull(db, poolSql, bindArgsOf(listOf(providerId) + relevanceArgs))?.let { cursor ->
            readCursorRows(cursor) { c ->
                val id = c.optString("id") ?: return@readCursorRows null
                val observedAt = c.optLong("observedAt") ?: return@readCursorRows null
                QuotaRow(
                    id = id,
                    providerId = c.optString("providerId") ?: "",
                    accountId = c.optString("accountId"),
                    credentialId = c.optString("credentialId"),
                    modelId = c.optString("modelId") ?: "",
                    group = c.optString("group"),
                    endpointId = c.optString("endpointId") ?: "",
                    operation = c.optString("operation") ?: "",
                    observedAt = observedAt,
                    source = c.optString("source") ?: "",
                )
            }
        }.orEmpty()
        for (r in poolRows) {
            if (!isRelevantQuotaScope(
                    r.providerId, r.accountId, r.credentialId, r.modelId,
                    modelIds, selected, providerId,
                )
            ) {
                continue
            }
            merged.putIfAbsent(r.id, r)
        }
        return merged.values
            .sortedWith(compareByDescending<QuotaRow> { it.observedAt }.thenBy { it.id })
            .take(SNAPSHOT_LIMIT)
    }

    /**
     * CR-006 bounded facts join（price state）：只吃 bounded 查詢回傳的 snapshotId
     * （<=400 分塊、first-wins），不全表掃 snapshot_facts。chunk 間 ensureActive。
     */
    private suspend fun queryPriceFactStatesBySnapshotIds(
        db: AtlasDatabase,
        snapshotIds: Set<String>,
    ): Map<String, String> {
        val ids = snapshotIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (chunk in ids.chunked(SQL_CHUNK)) {
            coroutineContext.ensureActive()
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = "SELECT \"snapshotId\", \"state\" FROM \"snapshot_facts\" " +
                "WHERE \"snapshotId\" IS NOT NULL AND \"kind\" IN ('PRICE','MANUAL_PRICE','DEDICATED_PRICE') " +
                "AND \"snapshotId\" IN ($placeholders)"
            val rows = readCursorRows(openCursorOrNull(db, sql, bindArgsOf(chunk)) ?: continue) { c ->
                val sid = c.optString("snapshotId") ?: return@readCursorRows null
                val state = c.optString("state") ?: return@readCursorRows null
                // 同一 snapshot 多 fact（重試列）：首見保留，不後寫覆蓋。
                sid to state
            }
            for ((sid, state) in rows) out.putIfAbsent(sid, state)
        }
        return out
    }

    /**
     * CR-006 bounded facts join（quota state＋維度）：只吃 bounded 查詢回傳的 snapshotId
     * （<=400 分塊、first-wins），不全表掃 snapshot_facts。chunk 間 ensureActive。
     */
    private suspend fun queryQuotaFactStatesBySnapshotIds(
        db: AtlasDatabase,
        snapshotIds: Set<String>,
    ): Map<String, FactState> {
        val ids = snapshotIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, FactState>()
        for (chunk in ids.chunked(SQL_CHUNK)) {
            coroutineContext.ensureActive()
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = "SELECT \"snapshotId\", \"kind\", \"state\", \"remainingRaw\", \"usedRaw\", " +
                "\"grantedRaw\", \"unlimited\", \"expiresAtRaw\" FROM \"snapshot_facts\" " +
                "WHERE \"snapshotId\" IS NOT NULL AND \"kind\" IN " +
                "('ACCOUNT_QUOTA','TOKEN_QUOTA','MANUAL_QUOTA','DEDICATED_ACCOUNT_QUOTA','DEDICATED_TOKEN_QUOTA') " +
                "AND \"snapshotId\" IN ($placeholders)"
            val rows = readCursorRows(openCursorOrNull(db, sql, bindArgsOf(chunk)) ?: continue) { c ->
                val sid = c.optString("snapshotId") ?: return@readCursorRows null
                val kind = c.optString("kind") ?: return@readCursorRows null
                val state = c.optString("state") ?: return@readCursorRows null
                sid to FactState(
                    kind = kind,
                    state = state,
                    remaining = c.optLong("remainingRaw"),
                    used = c.optLong("usedRaw"),
                    granted = c.optLong("grantedRaw"),
                    unlimited = c.optBoolean("unlimited"),
                    expires = c.optLong("expiresAtRaw"),
                )
            }
            for ((sid, fact) in rows) out.putIfAbsent(sid, fact)
        }
        return out
    }

    /** String 集合 → SQLite bind 參數（Array<String> 與 Array<Any?> 不可變，需顯式轉換）。 */
    private fun bindArgsOf(values: Collection<String>): Array<Any?> =
        values.map { it as Any? }.toTypedArray()

    private fun Cursor.optString(column: String): String? {        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.optLong(column: String): Long? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getLong(i)
    }

    private fun Cursor.optBoolean(column: String): Boolean? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getInt(i) != 0
    }
}
