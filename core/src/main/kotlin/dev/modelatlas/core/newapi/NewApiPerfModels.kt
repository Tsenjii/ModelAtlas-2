package dev.modelatlas.core.newapi

/**
 * New API provider-wide performance 端點（rc.25）。
 *
 * 研究依據：
 * - `docs/PROVIDER_MODEL_INTELLIGENCE_CONTRACT.md` §6.1–§6.2、§2（來源分離）
 * - PERF-SHAPE-A `docs/ADAPTER_NEWAPI_PERF_RESEARCH.md`「路由與認證」（路由／條件認證／查詢參數）
 * - PERF-SHAPE-B 同 doc「精確 rc.25 JSON shape」（response shape）
 * - 二者為 rc.25 **source research**；deployment 未實測＝Unknown（同 doc「Version drift / Unknown」）
 * - 上游 `QuantumNous/new-api` tag `v1.0.0-rc.25` = `f1164142`
 *
 * **GAP-V1**：[NewApiVersion.certifiedEndpoints] 由 `NewApiEndpoint.entries` 自動推導，
 * 若把 perf path 塞進 [NewApiEndpoint]，既有 5 端點認證 map 會被靜默擴張。因此 perf
 * 路徑刻意獨立於 [NewApiEndpoint]，另以本 enum + [NewApiVersion.certifiedPerfEndpoints]
 * 顯式認證。
 *
 * 認證角色：兩個 perf 路由共用 `HeaderNavModulePublicOrUserAuth("pricing")`；dashboard
 * credential（PAT / login session）為**條件式**，站方可開放匿名。adapter 端以選用
 * [AccountPatRef] 表達：有 PAT 就帶、沒有就匿名；inference key 永不進 `/api/…`。
 */
enum class NewApiPerfEndpoint(val path: String) {
    /** `GET /api/perf-metrics/summary?hours={H}`。 */
    SUMMARY("/api/perf-metrics/summary"),

    /** `GET /api/perf-metrics?model={M}&hours={H}&group={G?}`。 */
    QUERY("/api/perf-metrics"),
}

/**
 * Client 端 `hours` 正規化，鏡射 rc.25 server 行為（PERF-SHAPE-B §2.1／§2.2）：
 * `<= 0` 回 [DEFAULT_HOURS]（24）；`> [MAX_HOURS]`（720）夾為 720；其餘保留原值。
 *
 * Adapter 一律送明確數字，不重現 server 對不可解析字串的 silent fallback
 * （client 型別是 `Int`，不存在不可解析輸入）。
 */
object NewApiPerfWindow {
    /** rc.25 `hours` 缺省／無效時 server 使用的預設值。 */
    const val DEFAULT_HOURS = 24

    /** rc.25 `hours` 上限（`24 * 30`）。 */
    const val MAX_HOURS = 720

    fun normalize(hours: Int): Int = when {
        hours <= 0 -> DEFAULT_HOURS
        hours > MAX_HOURS -> MAX_HOURS
        else -> hours
    }
}

/**
 * `GET /api/perf-metrics/summary` 的 `data`（rc.25 `SummaryAllResult`）。
 *
 * 這是**站方全站 relay 統計**（PROVIDER_WIDE），不是 MY_KEY_LOCAL；不得與 My Uptime
 * 混算或平均。
 *
 * rc.25 固定只有 `models`（永不為 null，空窗期為 `[]`）；main-only 的
 * `summary` / `window_start` / `window_end` 不在本型別，parser 忽略未知 key。
 */
data class NewApiPerfSummary(
    val models: List<NewApiPerfModelSummary>,
)

/**
 * `summary.data.models[]` 元素（rc.25 `ModelSummary`）。
 *
 * 單位與語意（PERF-SHAPE-B §4.1／§4.3）：
 * - [avgLatencyMs]：整數毫秒（Go int64 除法截斷）。`0` 保留為 `0`，
 *   不猜成 Unknown（rc.25 對「無樣本」與「真 0 ms」不可區分，歧義保留給上層）。
 * - [successRate]：**percent 0–100**，永不除以 100。
 * - [avgTps]：output tokens / 生成秒數，`0.0` 保留原值。
 *
 * **Provider-relay 語意**：rc.25 的 `success_rate` 是 relay 完成率，且把
 * user-stop / client-cancel 計為 success（PERF-SHAPE-B §8）。它**不是** My Uptime、
 * **不是** D-009 排除後的個人成功率；本 adapter 只逐值傳遞，不做任何 local-health
 * 計算，也不得併入 My Uptime。
 */
data class NewApiPerfModelSummary(
    val modelName: String,
    val avgLatencyMs: Long,
    val successRate: Double,
    val avgTps: Double,
    /**
     * `recent_success_rates`：最近 3 個 bucket 的 success percent，升冪、無 timestamp。
     * `omitempty`：key 不存在或為 null ⇒ `null`；顯式 `[]` ⇒ 空清單。兩者不同。
     */
    val recentSuccessRates: List<Double>?,
    /**
     * rc.25 `ModelSummary.request_count` 標記 `json:"-"`，**永不序列化**；因此實務上
     * 恆為 `null`（sample count Unknown），**絕不補 0**。保留欄位以 forward-tolerant
     * 讀取未來可能出現的值。
     */
    val requestCount: Long?,
)

/**
 * `GET /api/perf-metrics?model=…` 的 `data`（rc.25 `QueryResult`）。
 *
 * 未知 model 在 rc.25 是 **200 + `groups: []`**（typed empty），不是 error。
 * [seriesSchema] 為 upstream 的 opaque cache/schema marker，原值 passthrough，
 * 不驗證、不寫死常數。
 */
data class NewApiPerfQuery(
    val modelName: String,
    val seriesSchema: String,
    val groups: List<NewApiPerfGroup>,
)

/**
 * `groups[]` 元素（rc.25 `GroupResult`）。
 *
 * [group] 是獨立 routing / entitlement dimension；group 名稱**不是** Free 證據。
 * 有效 group 由站方過濾，某 group 缺席不等於零流量（PERF-SHAPE-B §5.1）。
 * [successRate] 同 [NewApiPerfModelSummary.successRate]：provider-relay 語意
 * （含 client-cancel 計為 success），**不是** My Uptime、**不是** D-009 排除後值。
 */
data class NewApiPerfGroup(
    val group: String,
    /** 整數毫秒；無 TTFT 樣本時 server 給 `0`，歧義保留不猜。 */
    val avgTtftMs: Long,
    val avgLatencyMs: Long,
    /** percent 0–100；此端點 rc.25 不四捨五入。 */
    val successRate: Double,
    /** tokens / second；無 token 資料時 `0.0`。 */
    val avgTps: Double,
    /** 每 group 自己的時間序列；group 有資料時至少一點，缺 bucket 不補 0。 */
    val series: List<NewApiPerfPoint>,
)

/**
 * `groups[].series[]` 元素（rc.25 `BucketPoint`）。
 *
 * [tsEpochSeconds] 是 **Unix epoch 秒（UTC）** 的 bucket 起點；bucket 寬度不由 API
 * 提供，只能由相鄰 `ts` 差值推得或保持 Unknown；序列有缺洞，parser 不內插。
 */
data class NewApiPerfPoint(
    val tsEpochSeconds: Long,
    val avgTtftMs: Long,
    val avgLatencyMs: Long,
    val successRate: Double,
    val avgTps: Double,
)
