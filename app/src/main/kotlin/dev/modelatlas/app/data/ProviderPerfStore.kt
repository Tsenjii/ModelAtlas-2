package dev.modelatlas.app.data

import androidx.room.withTransaction
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.newapi.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * NINT-004 provider-wide perf 專用 store（candidate NEWAPI-001；Contract §2／§3／§6；
 * PRODUCT_SPEC §11／§12；PERF-SHAPE-A/B rc.25 `f1164142`）。
 *
 * 本 store 只讀已認證 adapter 輸出、只做 scope／role 驗證、certified 閘門、evidence 鏈
 * 與 head／last-good 維護：不做任何 USD 換算、不平均、不重算、不 eval。
 *
 * 鐵律（違反即 reject）：
 * - cert gate：[certifiedProviderVersion] 只認「已持久化的官方 New API certified
 *   evidence」（PRICE／ACCOUNT_QUOTA／TOKEN_QUOTA 的 last-good fact：kind／scopeKey
 *   與 head 一致、`OK`＋HTTP 2xx＋官方 source＋evidence `providerId` 相符＋
 *   evidence timestamp 與 fact 觀測一致＋`providerVersion == v1.0.0-rc.25`，取最新
 *   官方 good 觀測的版本）。port／factory 存在本身**不是**版本證據；stored gate
 *   未過即零請求、零寫入。通過後仍先走一次新鮮 `fetchCapabilities`，站方版本漂移
 *   （非 rc.25）即零 perf 請求、只記 gate 標記（lastGood 不動）。
 * - 來源分離：只寫 `provider_perf_*` 兩表＋共用 `evidence`；永不寫 `samples`／
 *   `snapshot_facts`／`snapshot_heads`／`external_observations`／telemetry。
 * - 失敗／空窗／malformed 皆 append 歷史（latest 照嚴格 newer 推進），唯嚴格更新的
 *   good 觀測才推進 lastGood（鏡射 `NewApiSnapshotStore.record` newerGood 語意）。
 * - Unknown≠0／Free：缺欄 null（`request_count` 無欄，即使 DTO 非 null 亦不採信為
 *   verified samples）；空陣列為 authoritative empty 標記，不當錯誤、不推進
 *   lastGood；`success_rate` percent 0–100 逐值保存。
 * - 落庫閘門（F1／F2／F3，同一 transaction 存 data 前全部驗證）：
 *   全部數值 finite；success_rate（含 recent／series 每點）∈ 0..100；TPS ≥ 0；
 *   latency／TTFT ≥ 0；query 的 `modelName` 必須與 requested exact model 相等
 *   （core trim 後不等即 fail-closed，不偷改名）；requested group 非 null 時所有
 *   group 必須等於 requested group；同 surface 內重複 model／group／point
 *   timestamp 有分歧即整個 surface MALFORMED（不寫 good；不靜默改歷史）。
 *   無效 surface 只 append 靜態 MALFORMED 標記（lastGood／fetched 不動）。
 * - 系列保存（Contract §6.2＋D-006）：GROUP 列以 [encodePerfSeries] 存全部合法
 *   points 的 normalized numeric-only JSON（非 raw body；排序＋固定鍵序＋數字精確
 *   round-trip；null＝未觀測、`"[]"`＝真 empty）。`seriesPointCount` 僅為點數輔助，
 *   **不是** request 樣本數，不得作樣本／信心推導；不編造 provider aggregate。
 * - PAT 為選用管理憑證：該 provider config 的 ACCOUNT safe ref（嚴格 safe-ref 檢查，
 *   不以 trim 掩飾 raw 輸入；角色必須純 ACCOUNT）；無 PAT 即匿名（站方設定決定是否
 *   要求；匿名不需要 inference AuthMode）。錯 ref／foreign provider／account 一律
 *   fail-closed 拋出（首次網路請求前），**永不借 INFERENCE credential**；raw 值只在
 *   adapter HTTP 邊界取值，不進本層。
 * - 產品 enrichment 預設窗口為 168h（Contract §3 預設 7d）；core 的 server 預設 24
 *   保持不動（`NewApiPerfWindow.DEFAULT_HOURS`）。
 * - 兩腿獨立：summary 失敗／例外不阻 query（typed Failure 照跑 query；persist 例外
     *   per-leg fail-open，以 failed 動作旗標回傳；CancellationException 永遠重拋，不吞）。
 *   HTTP status 一律取自真實 evidence，不從語意編造。
 *
 * 併發：本 store 不建新 mutex；生產呼叫路徑（`enrichMyModelBundle`）處於既有
 * bundle per-provider single-flight 之下（MYM-004 exact-key／per-provider 序列化）。
 */
class ProviderPerfStore(private val db: AtlasDatabase) {

    /**
     * 站方已持久化的當前已知版本。
     *
     * 只讀 repo 已存 head／evidence：官方三 kind 的 last-good fact 中 observedAt
     * 最新的那筆；其 `providerVersion == v1.0.0-rc.25` 才回認證版，其餘（無證據、
     * 版本不合、證據非 OK／非 2xx／source 非官方／kind-scope-evidence 任一錯配）
     * 一律 [NewApiVersion.UNKNOWN]。純 DB 讀取，零網路請求。
     */
    suspend fun certifiedProviderVersion(providerId: String): NewApiVersion {
        require(providerId.isNotBlank()) { "Provider required" }
        var latest: Pair<Long, String?>? = null
        for ((kind, source) in OFFICIAL_VERSION_SOURCES) {
            for (head in db.snapshotFactDao().headsByKind(kind)) {
                val parts = parseScopeKey(head.scopeKey) ?: continue
                if (parts.getOrNull(0) != providerId) continue
                val goodId = head.lastGoodSnapshotId ?: continue
                val fact = db.snapshotFactDao().evidence(goodId) ?: continue
                // 另一 provider／錯 scope／cross-kind 冒充一律排除。
                if (fact.kind != kind) continue
                if (fact.scopeKey != head.scopeKey) continue
                if (fact.status != "OK") continue
                val evidence = db.evidenceDao().getById(goodId) ?: continue
                if (evidence.providerId != providerId) continue
                if (evidence.outcome != "OK") continue
                if (evidence.timestamp != fact.observedAt) continue
                if (evidence.source != source) continue
                val http = evidence.status ?: continue
                if (http !in 200..299) continue
                val version = fact.providerVersion ?: continue
                if (latest == null || fact.observedAt > latest.first) {
                    latest = fact.observedAt to version
                }
            }
        }
        val known = latest?.second ?: return NewApiVersion.UNKNOWN
        return if (known == NewApiVersion.RC25) NewApiVersion.of(known) else NewApiVersion.UNKNOWN
    }

    /**
     * 一次 provider-wide perf 同步：`summary`（全站多模型）＋該 model 的 per-model
     * `query`（全 group 逐元素），各自獨立 fail-open 落庫。
     *
     * - version gate 未過（stored 證據非認證版）：零請求、零寫入。
     * - 新鮮 capabilities 失敗或版本漂移：只記 session-gate 標記（1 個 status 請求，
     *   零 perf 請求；lastGood 不動）。
     * - summary／query 各自 `Success` 經 F1／F2／F3 全面驗證後記行列（無效即整面
     *   MALFORMED 標記）、`Failure` 記 failure 標記；空陣列記 authoritative-empty 標記。
     * - 回傳 [ProviderPerfSync.fetched] 僅在「本次 requested model 的 good 資料已持久化」
     *   時為 true（empty／failure／invalid 不 true；不只看 HTTP 200）。
     * - persist 例外 per-leg fail-open（另一腿照跑），以 failed 供呼叫端記靜態標籤；
     *   CancellationException 立即重拋。
     */
    suspend fun syncPerf(
        adapter: NewApiAdapter,
        providerId: String,
        providerModelId: String,
        hours: Int = DEFAULT_ENRICHMENT_HOURS,
        group: String? = null,
        accountId: String? = null,
        /**
         * CR-009 DRAFT: wave passport. Null = legacy path (fresh caps at :135).
         * Non-null + exact match = reuse passport.caps (skip 1 status GET);
         * mismatch = miss = fresh fetch (cross-account/origin reuse impossible).
         * Version gate :136-142 UNCHANGED: passport version must still be
         * CERTIFIED_RC25 or the gate-marker path runs. Provenance resolution
         * :134 stays (anon vs ACCOUNT_PAT derived from resolved selection;
         * passport.authRole must equal it or the leg misses).
         */
        passport: NewApiSessionPassport? = null,
    ): ProviderPerfSync {
        require(providerId.isNotBlank()) { "Provider required" }
        require(providerModelId.isNotBlank()) { "Model required" }
        require(db.providerDao().getById(providerId) != null) { "Provider not found" }
        val effectiveHours = NewApiPerfWindow.normalize(hours)
        val stored = certifiedProviderVersion(providerId)
        if (stored.support != NewApiVersionSupport.CERTIFIED_RC25) {
            return ProviderPerfSync(stored, certified = false, summaryFacts = 0, queryFacts = 0, fetched = false)
        }
        // 先驗本地歸屬＋PAT（燒網路前 fail-fast）；基址 origin 一致性同 syncNewApi。
        val base = resolveProviderBase(providerId)
        val provenance = resolveOptionalPerfPat(providerId, accountId)
        val origin = EndpointPolicy.origin(EndpointPolicy.validate(base))
        val legAuthRole = if (provenance.ref != null) NewApiSessionAuthRole.ACCOUNT_PAT
            else NewApiSessionAuthRole.ANON
        val caps = if (passport != null && passport.matchesLeg(
                providerId = providerId,
                baseUrl = base,
                origin = origin,
                authRole = legAuthRole,
                pat = provenance.ref,
                accountId = provenance.accountId,
                hours = effectiveHours,
            )) passport.caps else adapter.fetchCapabilities(base)
        val fresh = (caps as? NewApiResult.Success)?.value?.version
        if (fresh == null || fresh.support != NewApiVersionSupport.CERTIFIED_RC25) {
            val summaryFacts = persistSessionGateMarker(providerId, null, effectiveHours, provenance, caps, ProviderPerfKind.SUMMARY)
            val queryFacts = persistSessionGateMarker(providerId, providerModelId, effectiveHours, provenance, caps, ProviderPerfKind.GROUP)
            val version = fresh ?: NewApiVersion.UNKNOWN
            return ProviderPerfSync(version, certified = false, summaryFacts, queryFacts, fetched = false, failed = true)
        }
        var summaryFacts = 0
        var summaryGood = 0
        var queryFacts = 0
        var queryGood = 0
        var failed = false
        // CR-009 DRAFT: G2 memo. Hit (exact identity incl. normalized window +
        // certified version raw + remembered certified-good summary) => skip
        // fetchPerfSummary AND skip persist (rows already persisted wave-wide by
        // the first leader): summaryFacts = 0, summaryGood = membership of the
        // REQUESTED model. Failure/empty-rejected/malformed/cancel were never
        // remembered, so a hit always replays a good observation. Per-model
        // query below still runs for every member (standing rule, untouched).
        var summaryObservation: WaveSummary? = null
        val memo = if (passport != null && passport.matchesSummary(
            providerId = providerId,
            baseUrl = base,
            origin = origin,
            authRole = legAuthRole,
            pat = provenance.ref,
            accountId = provenance.accountId,
            hours = effectiveHours,
            // `fresh` already passed the CERTIFIED_RC25 gate at :136-142: the
            // memo additionally requires the passport's certified raw to equal
            // the version that gated this leg (drift across waves => no passport
            // existed at all; mid-wave drift impossible since the leg consumed
            // the wave's own caps).
            versionRaw = fresh.raw ?: "",
        )) passport.summary else null
        try {
            if (memo != null) {
                summaryFacts = 0
                summaryGood = memo.memberGoodCount(providerModelId)
            } else when (val summary = adapter.fetchPerfSummary(base, fresh, provenance.ref, effectiveHours)) {
                is NewApiResult.Success -> {
                    require(summary.evidence.source == PERF_SUMMARY_SOURCE) { "Unexpected evidence source" }
                    if (!summarySurfaceValid(summary.value)) failed = true
                    val persisted = persistSummaryRows(providerId, effectiveHours, provenance, summary.evidence, summary.value)
                    summaryFacts = persisted.first
                    // The summary covers the whole catalog. Preserve other models' data,
                    // but do not report enrichment of this model solely because another
                    // model appeared in the provider's summary.
                    summaryGood = if (summary.value.models.any { it.modelName == providerModelId }) {
                        persisted.second
                    } else 0
                    // Remember ONLY certified+surface-valid Success for the wave
                    // memo (empty `models: []` remembered per P-empty lock; failure/
                    // invalid/cancel never remembered). No DB write for the memo;
                    // persist-once per fetch is enforced by persistSummaryRows.
                    summaryObservation = WaveSummary(summary.evidence, summary.value.models)
                        .takeIf { summarySurfaceValid(summary.value) }
                }
                is NewApiResult.Failure -> {
                    failed = true
                    require(summary.evidence.source == PERF_SUMMARY_SOURCE) { "Unexpected evidence source" }
                    summaryFacts = persistFailureMarker(providerId, null, effectiveHours, provenance, summary.evidence, summary.status, ProviderPerfKind.SUMMARY)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
        try {
            when (val query = adapter.fetchPerfQuery(base, providerModelId, fresh, provenance.ref, effectiveHours, group)) {
                is NewApiResult.Success -> {
                    require(query.evidence.source == PERF_QUERY_SOURCE) { "Unexpected evidence source" }
                    if (!querySurfaceValid(query.value, providerModelId, group)) failed = true
                    val persisted = persistQueryRows(providerId, effectiveHours, provenance, query.evidence, query.value, providerModelId, group)
                    queryFacts = persisted.first
                    queryGood = persisted.second
                }
                is NewApiResult.Failure -> {
                    failed = true
                    require(query.evidence.source == PERF_QUERY_SOURCE) { "Unexpected evidence source" }
                    queryFacts = persistFailureMarker(providerId, providerModelId, effectiveHours, provenance, query.evidence, query.status, ProviderPerfKind.GROUP)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
        return ProviderPerfSync(fresh, certified = true, summaryFacts, queryFacts,
            fetched = summaryGood + queryGood > 0, failed = failed,
            summaryObservation = summaryObservation)
    }

    /** 型別化讀取：同 scope 的 latest／lastGood（failure／empty／invalid 只會出現在 latest）。 */
    suspend fun observation(
        providerId: String,
        modelId: String?,
        group: String?,
        hours: Int,
        kind: ProviderPerfKind,
    ): StoredProviderPerf {
        val key = providerPerfScopeKey(providerId, modelId, group, NewApiPerfWindow.normalize(hours))
        val head = db.providerPerfDao().head(key, kind.name) ?: return StoredProviderPerf(null, null)
        val dao = db.providerPerfDao()
        return StoredProviderPerf(
            dao.fact(head.latestEvidenceId),
            head.lastGoodEvidenceId?.let { dao.fact(it) },
        )
    }

    /** 型別化歷史：同 scope 新→舊（含 empty／failure／invalid 標記；lastGood 證據另查 head）。 */
    suspend fun history(
        providerId: String,
        modelId: String?,
        group: String?,
        hours: Int,
        kind: ProviderPerfKind,
    ): List<ProviderPerfFactEntity> =
        db.providerPerfDao().history(
            providerPerfScopeKey(providerId, modelId, group, NewApiPerfWindow.normalize(hours)),
            kind.name,
        )

    /** 型別化系列讀取：fact 的 `seriesJson` 解回 points（null＝未觀測；格式不合回 null，不猜）。 */
    fun seriesPoints(fact: ProviderPerfFactEntity): List<NewApiPerfPoint>? =
        decodePerfSeries(fact.seriesJson)

    /**
     * Session-gate 標記（capabilities 失敗或新鮮版本非認證版）：append 歷史，lastGood 不動。
     *
     * 來源誠實：perf GET 根本沒發生，evidence 的 source／httpStatus／version 一律沿用
     * caps 真實 evidence（`newapi/api/status`），絕不改寫成 perf source 冒充一次未打的
     * perf 200；kind 仍按 SUMMARY／GROUP 記 scope，fact.status 以 UNKNOWN_VERSION／
     * transport 分類定位「perf 流程被 gate」。cert／version raw 未知照實保歷史，不 VERIFIED。
     */
    private suspend fun persistSessionGateMarker(
        providerId: String,
        modelId: String?,
        hours: Int,
        provenance: PerfProvenance,
        caps: NewApiResult<NewApiCapabilities>,
        kind: ProviderPerfKind,
    ): Int = db.withTransaction {
        val evidence: NewApiEvidence = when (caps) {
            is NewApiResult.Success -> caps.evidence
            is NewApiResult.Failure -> caps.evidence
        }
        val status = when (caps) {
            is NewApiResult.Success -> NewApiStatus.UNKNOWN_VERSION
            is NewApiResult.Failure -> caps.status
        }
        record(providerId, evidence.source, ProviderPerfFactEntity(
            evidenceId = newPerfId(),
            scopeKey = providerPerfScopeKey(providerId, modelId, null, hours),
            kind = kind.name,
            observedAt = evidence.observedAt,
            empty = false,
            status = status.name,
            providerVersion = safePerfVersion(evidence.providerVersion),
            httpStatus = evidence.httpStatus,
            modelId = modelId,
            groupName = null,
            hours = hours,
            accountId = provenance.accountId,
            credentialId = provenance.ref?.value,
            avgTtftMs = null,
            avgLatencyMs = null,
            successRate = null,
            avgTps = null,
            recentSuccessRates = null,
            seriesSchema = null,
            seriesJson = null,
            seriesPointCount = null,
        ), certified = false, usable = false)
        1
    }

    /**
     * Summary 成功：先全面驗證（F1／F2／分歧去重），無效即整面 MALFORMED 標記；
     * 有效時 models 各一列（不做跨模型平均），空陣列記 provider 級 authoritative-empty 標記。
     */
    private suspend fun persistSummaryRows(
        providerId: String,
        hours: Int,
        provenance: PerfProvenance,
        evidence: NewApiEvidence,
        summary: NewApiPerfSummary,
    ): Pair<Int, Int> = db.withTransaction {
        if (!summarySurfaceValid(summary)) {
            writeInvalidMarker(providerId, null, hours, provenance, evidence, ProviderPerfKind.SUMMARY)
            return@withTransaction 1 to 0
        }
        if (summary.models.isEmpty()) {
            record(providerId, PERF_SUMMARY_SOURCE, ProviderPerfFactEntity(
                evidenceId = newPerfId(),
                scopeKey = providerPerfScopeKey(providerId, null, null, hours),
                kind = ProviderPerfKind.SUMMARY.name,
                observedAt = evidence.observedAt,
                empty = true,
                status = "OK",
                providerVersion = safePerfVersion(evidence.providerVersion),
                httpStatus = evidence.httpStatus,
                modelId = null,
                groupName = null,
                hours = hours,
                accountId = provenance.accountId,
                credentialId = provenance.ref?.value,
                avgTtftMs = null,
                avgLatencyMs = null,
                successRate = null,
                avgTps = null,
                recentSuccessRates = null,
                seriesSchema = null,
                seriesJson = null,
                seriesPointCount = null,
            ), evidence.perfCertified(), usable = false)
            return@withTransaction 1 to 0
        }
        // 全站總成功率不得平均（無 request_count 可加權）：每 model 獨立列。
        val certified = evidence.perfCertified()
        var good = 0
        for (row in summary.models) {
            record(providerId, PERF_SUMMARY_SOURCE, ProviderPerfFactEntity(
                evidenceId = newPerfId(),
                scopeKey = providerPerfScopeKey(providerId, row.modelName, null, hours),
                kind = ProviderPerfKind.SUMMARY.name,
                observedAt = evidence.observedAt,
                empty = false,
                status = "OK",
                providerVersion = safePerfVersion(evidence.providerVersion),
                httpStatus = evidence.httpStatus,
                modelId = row.modelName,
                groupName = null,
                hours = hours,
                accountId = provenance.accountId,
                credentialId = provenance.ref?.value,
                avgTtftMs = null,
                avgLatencyMs = row.avgLatencyMs,
                successRate = row.successRate,
                avgTps = row.avgTps,
                recentSuccessRates = encodePerfRates(row.recentSuccessRates),
                seriesSchema = null,
                seriesJson = null,
                seriesPointCount = null,
            ), certified, usable = true)
            if (certified) good++
        }
        summary.models.size to good
    }

    /**
     * Query 成功：先全面驗證（F1／F2／F3／F11：model echo 相等、group 過濾一致、
     * 數值範圍、分歧去重），無效即整面 MALFORMED 標記；有效時 groups 逐元素各一列
     * （含該 group 全部合法 points 的 normalized 系列），空陣列記該 model 級
     * authoritative-empty 標記。
     */
    private suspend fun persistQueryRows(
        providerId: String,
        hours: Int,
        provenance: PerfProvenance,
        evidence: NewApiEvidence,
        query: NewApiPerfQuery,
        requestedModel: String,
        requestedGroup: String?,
    ): Pair<Int, Int> = db.withTransaction {
        if (!querySurfaceValid(query, requestedModel, requestedGroup)) {
            writeInvalidMarker(providerId, requestedModel, hours, provenance, evidence, ProviderPerfKind.GROUP)
            return@withTransaction 1 to 0
        }
        if (query.groups.isEmpty()) {
            record(providerId, PERF_QUERY_SOURCE, ProviderPerfFactEntity(
                evidenceId = newPerfId(),
                scopeKey = providerPerfScopeKey(providerId, query.modelName, null, hours),
                kind = ProviderPerfKind.GROUP.name,
                observedAt = evidence.observedAt,
                empty = true,
                status = "OK",
                providerVersion = safePerfVersion(evidence.providerVersion),
                httpStatus = evidence.httpStatus,
                modelId = query.modelName,
                groupName = null,
                hours = hours,
                accountId = provenance.accountId,
                credentialId = provenance.ref?.value,
                avgTtftMs = null,
                avgLatencyMs = null,
                successRate = null,
                avgTps = null,
                recentSuccessRates = null,
                seriesSchema = null,
                seriesJson = null,
                seriesPointCount = null,
            ), evidence.perfCertified(), usable = false)
            return@withTransaction 1 to 0
        }
        val certified = evidence.perfCertified()
        val schema = safeSeriesSchema(query.seriesSchema)
        var good = 0
        for (element in query.groups) {
            record(providerId, PERF_QUERY_SOURCE, ProviderPerfFactEntity(
                evidenceId = newPerfId(),
                scopeKey = providerPerfScopeKey(providerId, query.modelName, element.group, hours),
                kind = ProviderPerfKind.GROUP.name,
                observedAt = evidence.observedAt,
                empty = false,
                status = "OK",
                providerVersion = safePerfVersion(evidence.providerVersion),
                httpStatus = evidence.httpStatus,
                modelId = query.modelName,
                groupName = element.group,
                hours = hours,
                accountId = provenance.accountId,
                credentialId = provenance.ref?.value,
                avgTtftMs = element.avgTtftMs,
                avgLatencyMs = element.avgLatencyMs,
                successRate = element.successRate,
                avgTps = element.avgTps,
                recentSuccessRates = null,
                seriesSchema = schema,
                seriesJson = encodePerfSeries(element.series),
                seriesPointCount = element.series.size,
            ), certified, usable = true)
            if (certified) good++
        }
        query.groups.size to good
    }

    /** Endpoint 失敗標記：append 歷史（latest 照 newer 推進），lastGood 不動。 */
    private suspend fun persistFailureMarker(
        providerId: String,
        modelId: String?,
        hours: Int,
        provenance: PerfProvenance,
        evidence: NewApiEvidence,
        status: NewApiStatus,
        kind: ProviderPerfKind,
    ): Int = db.withTransaction {
        val source = if (kind == ProviderPerfKind.SUMMARY) PERF_SUMMARY_SOURCE else PERF_QUERY_SOURCE
        record(providerId, source, ProviderPerfFactEntity(
            evidenceId = newPerfId(),
            scopeKey = providerPerfScopeKey(providerId, modelId, null, hours),
            kind = kind.name,
            observedAt = evidence.observedAt,
            empty = false,
            status = status.name,
            providerVersion = safePerfVersion(evidence.providerVersion),
            httpStatus = evidence.httpStatus,
            modelId = modelId,
            groupName = null,
            hours = hours,
            accountId = provenance.accountId,
            credentialId = provenance.ref?.value,
            avgTtftMs = null,
            avgLatencyMs = null,
            successRate = null,
            avgTps = null,
            recentSuccessRates = null,
            seriesSchema = null,
            seriesJson = null,
            seriesPointCount = null,
        ), certified = false, usable = false)
        1
    }

    /**
     * 無效 surface 標記（F1／F2／F3／F11 驗證失敗）：靜態 MALFORMED，不寫任何 good 列，
     * lastGood／fetched 不動。HTTP status 取自真實 evidence，不編造。
     * 僅在外層 transaction 內呼叫（不另開 transaction）。
     */
    private suspend fun writeInvalidMarker(
        providerId: String,
        modelId: String?,
        hours: Int,
        provenance: PerfProvenance,
        evidence: NewApiEvidence,
        kind: ProviderPerfKind,
    ) {
        val source = if (kind == ProviderPerfKind.SUMMARY) PERF_SUMMARY_SOURCE else PERF_QUERY_SOURCE
        record(providerId, source, ProviderPerfFactEntity(
            evidenceId = newPerfId(),
            scopeKey = providerPerfScopeKey(providerId, modelId, null, hours),
            kind = kind.name,
            observedAt = evidence.observedAt,
            empty = false,
            status = NewApiStatus.MALFORMED.name,
            providerVersion = safePerfVersion(evidence.providerVersion),
            httpStatus = evidence.httpStatus,
            modelId = modelId,
            groupName = null,
            hours = hours,
            accountId = provenance.accountId,
            credentialId = provenance.ref?.value,
            avgTtftMs = null,
            avgLatencyMs = null,
            successRate = null,
            avgTps = null,
            recentSuccessRates = null,
            seriesSchema = null,
            seriesJson = null,
            seriesPointCount = null,
        ), certified = false, usable = false)
    }

    /**
     * head／last-good 維護（鏡射 `NewApiSnapshotStore.record` newerGood 語意）：
     * 每個觀測（含失敗／空／malformed／invalid）都 append fact＋evidence；latest 僅嚴格
     * 更新推進；唯有嚴格更新的 good 觀測（certified 成功可用列）才推進 lastGood。
     */
    private suspend fun record(
        providerId: String,
        source: String,
        fact: ProviderPerfFactEntity,
        certified: Boolean,
        usable: Boolean,
    ) {
        require(fact.observedAt >= 0) { "Invalid observation time" }
        val good = certified && usable
        db.evidenceDao().insert(EvidenceEntity(
            fact.evidenceId, fact.evidenceId, providerId, fact.modelId ?: "",
            fact.status, fact.httpStatus, fact.observedAt, source,
        ))
        db.providerPerfDao().insertFact(fact)
        val old = db.providerPerfDao().head(fact.scopeKey, fact.kind)
        val latest = old == null || fact.observedAt > old.latestObservedAt
        val newerGood = good && (old?.lastGoodObservedAt == null || fact.observedAt > old.lastGoodObservedAt)
        db.providerPerfDao().upsertHead(ProviderPerfHeadEntity(
            fact.scopeKey, fact.kind,
            if (latest) fact.evidenceId else old!!.latestEvidenceId,
            if (latest) fact.observedAt else old!!.latestObservedAt,
            if (newerGood) fact.evidenceId else old?.lastGoodEvidenceId,
            if (newerGood) fact.observedAt else old?.lastGoodObservedAt,
        ))
    }

    /**
     * 選用 PAT 解析：委託 shared [NewApiCredentialSelector.resolvePerfPat]。
     * 選出同帳號 valid PAT→Provenance(accountId, ref)。
     * 匿名雙 null 僅限 config 缺席或完全無 ACCOUNT role entries；config 含任何
     * ACCOUNT entry（含空／invalid ref）即必須選出 valid 同帳號 PAT，否則拋錯，
     * 絕不降級匿名／inference。
     */
    private suspend fun resolveOptionalPerfPat(providerId: String, accountId: String?): PerfProvenance {
        val selection = credentialSelector().resolvePerfPat(providerId, accountId)
        return PerfProvenance(selection?.accountId, selection?.pat)
    }

    /** Shared credential-selection foundation（與 repo `syncNewApi` 同一實例語意）。 */
    private fun credentialSelector(): NewApiCredentialSelector = NewApiCredentialSelector(db)

    /** Provider 單一 origin 基址（端點缺席／origin 分歧即 fail-closed，不燒額度）。 */
    private suspend fun resolveProviderBase(providerId: String): String {
        val endpoints = db.endpointDao().byProvider(providerId)
        require(endpoints.isNotEmpty()) { "Endpoint required" }
        require(endpoints.map { EndpointPolicy.origin(EndpointPolicy.validate(it.baseUrl)) }.distinct().size == 1) {
            "Provider origin mismatch"
        }
        return endpoints.first().baseUrl
    }

    companion object {
        /** `GET /api/perf-metrics/summary`（adapter SOURCE_PERF_SUMMARY 真值）。 */
        const val PERF_SUMMARY_SOURCE = "newapi/api/perf-metrics/summary"

        /** `GET /api/perf-metrics`（adapter SOURCE_PERF_QUERY 真值）。 */
        const val PERF_QUERY_SOURCE = "newapi/api/perf-metrics"

        /**
         * 產品 enrichment 預設窗口＝168h（Contract §3 預設 7d）。
         * core 的 server 預設 24（`NewApiPerfWindow.DEFAULT_HOURS`）保持不動。
         */
        const val DEFAULT_ENRICHMENT_HOURS = 168

        /** Version gate 讀取的官方 namespace（last-good 事实＋官方 source）。 */
        private val OFFICIAL_VERSION_SOURCES = listOf(
            SnapshotKind.PRICE.name to NewApiSnapshotStore.PRICE_SOURCE,
            SnapshotKind.ACCOUNT_QUOTA.name to NewApiSnapshotStore.ACCOUNT_SOURCE,
            SnapshotKind.TOKEN_QUOTA.name to NewApiSnapshotStore.TOKEN_SOURCE,
        )
    }
}

/** 認證帳號上下文（provenance only；匿名即雙 null）。 */
private data class PerfProvenance(val accountId: String?, val ref: AccountPatRef?)

/** 一次 perf 同步結果：`fetched` 只代表 requested model 本輪已有 good summary/group 資料。 */
data class ProviderPerfSync(
    val version: NewApiVersion,
    val certified: Boolean,
    val summaryFacts: Int,
    val queryFacts: Int,
    val fetched: Boolean,
    /** 動作分類：任一實際嘗試腿失敗；empty／未嘗試不是 failure，不保存例外／body。 */
    val failed: Boolean = false,
    /**
     * CR-009 DRAFT: this call's certified+surface-valid summary observation for
     * the wave memo (null on memo hit / failure / invalid / gate / cancel).
     * Carrier only; the memo is in-memory, never persisted, never lastGood.
     */
    val summaryObservation: WaveSummary? = null,
)

/** latest 為本次狀態（含 failure／empty／invalid 標記）；lastGood 僅歷史可用事實。 */
data class StoredProviderPerf(
    val latest: ProviderPerfFactEntity?,
    val lastGood: ProviderPerfFactEntity?,
)

/**
 * F1／F2 數值閘門（落庫前；rc.25 實測形狀）：
 * - millis（Long）：≥ 0（站方 0 歧義保留，不猜 Unknown）。
 * - percent（Double）：finite 且 ∈ 0..100（含 `recent_success_rates` 每點）。
 * - tps（Double）：finite 且 ≥ 0。
 */
private fun Long.isPerfMillisValid(): Boolean = this >= 0L

private fun Double.isPerfPercentValid(): Boolean = this.isFinite() && this in 0.0..100.0

private fun Double.isPerfTpsValid(): Boolean = this.isFinite() && this >= 0.0

private fun modelRowMetricsValid(row: NewApiPerfModelSummary): Boolean {
    if (!row.avgLatencyMs.isPerfMillisValid()) return false
    if (!row.successRate.isPerfPercentValid()) return false
    if (!row.avgTps.isPerfTpsValid()) return false
    val recent = row.recentSuccessRates ?: return true
    return recent.all { it.isPerfPercentValid() }
}

private fun pointMetricsValid(point: NewApiPerfPoint): Boolean {
    if (point.avgTtftMs < 0L || point.avgLatencyMs < 0L) return false
    if (!point.successRate.isPerfPercentValid()) return false
    if (!point.avgTps.isPerfTpsValid()) return false
    return true
}

private fun groupRowMetricsValid(element: NewApiPerfGroup): Boolean {
    if (element.avgTtftMs < 0L || element.avgLatencyMs < 0L) return false
    if (!element.successRate.isPerfPercentValid()) return false
    if (!element.avgTps.isPerfTpsValid()) return false
    val seen = HashMap<Long, NewApiPerfPoint>()
    for (point in element.series) {
        if (!pointMetricsValid(point)) return false
        val prev = seen.putIfAbsent(point.tsEpochSeconds, point)
        if (prev != null && prev != point) return false
    }
    return true
}

/**
 * Summary surface 驗證（F1／F2＋分歧去重）：任一列數值不合或重複 model 名 payload
 * 有分歧即整面無效（MALFORMED，不寫 good）。
 */
private fun summarySurfaceValid(summary: NewApiPerfSummary): Boolean {
    val seen = HashMap<String, NewApiPerfModelSummary>()
    for (row in summary.models) {
        if (!modelRowMetricsValid(row)) return false
        val prev = seen.putIfAbsent(row.modelName, row)
        if (prev != null && prev != row) return false
    }
    return true
}

/**
 * Query surface 驗證（F1／F2／F3／F11）：`modelName` 必須與 requested exact model
 * 相等（core trim 後不等即無效，不偷改名）；requested group 非 null 時所有 group
 * 必須等於 requested group；數值範圍＋分歧去重同上。任一不合即整面無效。
 */
private fun querySurfaceValid(
    query: NewApiPerfQuery,
    requestedModel: String,
    requestedGroup: String?,
): Boolean {
    if (query.modelName != requestedModel) return false
    val seen = HashMap<String, NewApiPerfGroup>()
    for (element in query.groups) {
        if (requestedGroup != null && element.group != requestedGroup) return false
        if (!groupRowMetricsValid(element)) return false
        val prev = seen.putIfAbsent(element.group, element)
        if (prev != null && prev != element) return false
    }
    return true
}

/** `recent_success_rates` 編碼：null＝缺席／null；""＝顯式空陣列；其餘逗點分隔。 */
internal fun encodePerfRates(rates: List<Double>?): String? {
    if (rates == null) return null
    if (rates.isEmpty()) return ""
    return rates.joinToString(",") { it.toString() }
}

/** [encodePerfRates] 逆操作：格式不合回 null（呼叫端 fail-closed 忽略，不猜）。 */
internal fun decodePerfRates(encoded: String?): List<Double>? {
    if (encoded == null) return null
    if (encoded.isEmpty()) return emptyList()
    return encoded.split(",").map { it.toDoubleOrNull() ?: return null }
}

/**
 * 系列 normalized numeric-only JSON：依 `tsEpochSeconds` 升冪（stable）、固定鍵序、
 * 數字精確 round-trip；只含 typed numeric 欄，不含 raw body。空序列記 `"[]"`。
 */
internal fun encodePerfSeries(points: List<NewApiPerfPoint>): String {
    if (points.isEmpty()) return "[]"
    return points.sortedBy { it.tsEpochSeconds }.joinToString(",", "[", "]") { point ->
        """{"tsEpochSeconds":${point.tsEpochSeconds},"avgTtftMs":${point.avgTtftMs},"avgLatencyMs":${point.avgLatencyMs},"successRate":${point.successRate},"avgTps":${point.avgTps}}"""
    }
}

/**
 * [encodePerfSeries] 逆操作：嚴格形狀（5 鍵全在、數字非字串）才回 typed points；
 * null＝未觀測；格式不合回 null（呼叫端 fail-closed 忽略，不猜）。讀取不重做範圍閘門
 * （範圍是寫入閘門的責任）。
 */
internal fun decodePerfSeries(encoded: String?): List<NewApiPerfPoint>? {
    if (encoded == null) return null
    val array = runCatching { Json.parseToJsonElement(encoded) }.getOrNull() as? JsonArray ?: return null
    if (array.isEmpty()) return emptyList()
    return array.map { item ->
        val obj = item as? JsonObject ?: return null
        fun num(name: String): JsonPrimitive? =
            (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }
        val ts = num("tsEpochSeconds")?.longOrNull ?: return null
        val ttft = num("avgTtftMs")?.longOrNull ?: return null
        val latency = num("avgLatencyMs")?.longOrNull ?: return null
        val rate = num("successRate")?.doubleOrNull ?: return null
        val tps = num("avgTps")?.doubleOrNull ?: return null
        NewApiPerfPoint(ts, ttft, latency, rate, tps)
    }
}

private fun NewApiEvidence.perfCertified(): Boolean = providerVersion == NewApiVersion.RC25 &&
    versionSupport == NewApiVersionSupport.CERTIFIED_RC25 && httpStatus in 200..299

private fun safePerfVersion(value: String?): String? = value?.takeIf {
    it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._-]+")) && !it.startsWith("sk-")
}

/**
 * `series_schema` opaque marker 落庫前硬化：站方常量極短（rc.25 `dbcd0a3c01b55203`）；
 * 超長或含控制／非 ASCII 即 null（Unknown），保 metrics 不失、不捏造 marker。
 */
private fun safeSeriesSchema(value: String?): String? {
    val trimmed = value?.trim() ?: return null
    if (trimmed.isEmpty() || trimmed.length > MAX_SERIES_SCHEMA_CHARS) return null
    if (trimmed.any { it.code < 0x20 || it.code == 0x7F || it.code > 0x7E }) return null
    return trimmed
}

private const val MAX_SERIES_SCHEMA_CHARS = 64

private fun newPerfId(): String = "perf:" + UUID.randomUUID()
