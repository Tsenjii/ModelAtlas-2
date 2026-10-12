package dev.modelatlas.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * NINT-004 provider-wide perf 專用槽（candidate NEWAPI-001；Contract §2／§3／§6；
 * PRODUCT_SPEC §11／§12；STORAGE Option B；Room v12 只加表）。
 *
 * 來源分離（Contract §2＋鐵律）：
 * - provider-wide perf 只落本兩表；永不寫 `samples`／`snapshot_facts`／
 *   `snapshot_heads`／`external_observations`／telemetry；不得併入 My Uptime。
 * - rc.25 的 `success_rate` 把 client-cancel 計為 success（PERF-SHAPE-B §8），
 *   是 provider-relay 完成率，不是 D-009 排除後的個人成功率；本表只逐值保存，
 *   不做任何 local-health 計算。
 *
 * Scope 精確（provider×model×group×hours）：
 * - SUMMARY kind：`summary` 的每 model 一列（scope model＝站方回的 model 名、
 *   group＝null）。站方 summary 沒有全站總列，本片**不做跨模型平均**
 *   （無 `request_count` 可加權，平均即造假）；同 provider 多 hours 窗口各有 scope。
 * - GROUP kind：per-model `query` 的每 group 一列（array 形狀逐元素保存，不併 map、
 *   不跨 group 覆寫）。group 缺席≠零流量（站方過濾 retired group 即消失）。
 * - 空窗（summary `models:[]`、query `groups:[]`）為官方 authoritative empty：
 *   以 `empty=true` 標記列 append 歷史（latest 照 newer 推進），**不碰 lastGood**，
 *   不當錯誤、不補 0。Unknown≠0／Free：缺欄 null（`request_count` 永不序列化，
 *   故本表無該欄）；`success_rate` 為 percent 0–100 逐值保存，永不除以 100。
 * - 認證 context（account／PAT safe ref）僅為 provenance 欄，不進 scopeKey：
 *   同一站方事實不因換 key 而分裂／覆寫；亦**不得**用站方指標反推 key 可用性。
 *
 * last-good 語意鏡射 [NewApiSnapshotStore.record]：失敗／空／malformed 只 append
 * 歷史、照 newer 推進 latest，唯有嚴格更新的 good 觀測才推進 lastGood。
 */
enum class ProviderPerfKind {
    /** `GET /api/perf-metrics/summary`：每 model 列（group 恆 null）或 provider 級空標記。 */
    SUMMARY,

    /** `GET /api/perf-metrics?model=…`：每 group 列或該 model 級空標記。 */
    GROUP,
}

/** rc.25 perf 事實：`evidenceId` 同為 evidence 主鍵（FK RESTRICT）；失敗／空亦 append。 */
@Entity(
    tableName = "provider_perf_facts",
    foreignKeys = [ForeignKey(
        entity = EvidenceEntity::class, parentColumns = ["id"], childColumns = ["evidenceId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["scopeKey", "kind", "observedAt"])],
)
data class ProviderPerfFactEntity(
    @PrimaryKey val evidenceId: String,
    val scopeKey: String,
    val kind: String,
    val observedAt: Long,
    /** authoritative-empty 標記（空窗期 200 空陣列）：true 即不計入 last-good。 */
    val empty: Boolean,
    /** [NewApiStatus.name]：成功為 `OK`，其餘為失敗分類（靜態 enum 名，無 body／secret）。 */
    val status: String,
    /** 本次觀測的站方版本（safe 字串或 null）；certified 閘門見 store。 */
    val providerVersion: String?,
    val httpStatus: Int?,
    /** scope：summary 列為站方 `model_name`；summary 空標記為 null。 */
    val modelId: String?,
    /** scope：GROUP 列為站方 group 名；SUMMARY 列與空標記為 null（≠零流量）。 */
    val groupName: String?,
    /** scope：請求的 hours 窗口（產品預設 168＝7d，見 store；rc.25 server 預設 24）。 */
    val hours: Int,
    /** provenance only（非 scope）：實際使用的管理帳號 safe ref；匿名為 null。 */
    val accountId: String?,
    /** provenance only（非 scope）：實際使用的 ACCOUNT PAT safe ref；匿名為 null。 */
    val credentialId: String?,
    /** GROUP 列：整數毫秒；站方無 TTFT 樣本給 0（歧義保留，不猜 Unknown／0）。 */
    val avgTtftMs: Long?,
    /** 整數毫秒（Go int64 除法截斷）；0 保留為 0。 */
    val avgLatencyMs: Long?,
    /** percent 0–100 逐值保存（summary 兩位小數、query 未四捨五入，皆原樣）。 */
    val successRate: Double?,
    /** output tokens／生成秒；無 token 資料時站方給 `0.0`，保留原值。 */
    val avgTps: Double?,
    /**
     * SUMMARY 列：`recent_success_rates`（最近 3 bucket，升冪、無 timestamp）。
     * null＝key 缺席／null（Unknown）；""＝顯式空陣列；其餘為逗點分隔 finite double
     * （Kotlin `Double.toString` 精確 round-trip）。GROUP 的完整 series 另存 [seriesJson]；
     * [seriesPointCount] 只為點數輔助，不能當作 request 樣本數。
     */
    val recentSuccessRates: String?,
    /** GROUP 列：站方 `series_schema` opaque marker 原值（bounded；超長／不安全即 null）。 */
    val seriesSchema: String?,
    /**
     * GROUP 列：該 group 本次 `series[]` 的 normalized numeric-only JSON
     * （Contract §6.2 每 group 時間序列＋D-006 永久保留；F7：非每 point 單獨建表）。
     *
     * 編碼（見 `encodePerfSeries`）：依 `tsEpochSeconds` 升冪、固定鍵序
     * `tsEpochSeconds／avgTtftMs／avgLatencyMs／successRate／avgTps`、數字精確
     * round-trip；只含 typed numeric 欄，**不含 raw body**。null＝未觀測
     * （SUMMARY 列）；`"[]"`＝觀測到顯式空序列。`seriesPointCount` 僅為點數輔助，
     * **不是** request 樣本數（rc.25 不暴露 `request_count`，不得作樣本／信心推導）。
     */
    val seriesJson: String?,
    /** GROUP 列：該 group 本次 `series[]` 點數（派生計數；bucket 缺洞不補 0）。 */
    val seriesPointCount: Int?,
)

/** 同 (scopeKey, kind) upsert 指標；歷史 fact／evidence 永不被指標更新覆蓋。 */
@Entity(tableName = "provider_perf_heads", primaryKeys = ["scopeKey", "kind"])
data class ProviderPerfHeadEntity(
    val scopeKey: String,
    val kind: String,
    val latestEvidenceId: String,
    val latestObservedAt: Long,
    val lastGoodEvidenceId: String?,
    val lastGoodObservedAt: Long?,
)

@Dao
interface ProviderPerfDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFact(fact: ProviderPerfFactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHead(head: ProviderPerfHeadEntity)

    @Query("SELECT * FROM provider_perf_heads WHERE scopeKey = :scopeKey AND kind = :kind")
    suspend fun head(scopeKey: String, kind: String): ProviderPerfHeadEntity?

    @Query("SELECT * FROM provider_perf_heads WHERE kind = :kind")
    suspend fun headsByKind(kind: String): List<ProviderPerfHeadEntity>

    @Query("SELECT * FROM provider_perf_facts WHERE scopeKey = :scopeKey AND kind = :kind ORDER BY observedAt DESC, evidenceId ASC")
    suspend fun history(scopeKey: String, kind: String): List<ProviderPerfFactEntity>

    @Query("SELECT * FROM provider_perf_facts WHERE evidenceId = :id")
    suspend fun fact(id: String): ProviderPerfFactEntity?
}

/**
 * 精確 scope key（provider×model×group×hours）：長度前綴編碼，null、空字串、
 * 分隔符皆不碰撞；認證 provenance（account／credential）刻意不進 key。
 */
internal fun providerPerfScopeKey(
    providerId: String,
    modelId: String?,
    group: String?,
    hours: Int,
): String = listOf(providerId, modelId, group, hours.toString()).joinToString("") {
    if (it == null) "-1:" else "${it.length}:$it"
}
