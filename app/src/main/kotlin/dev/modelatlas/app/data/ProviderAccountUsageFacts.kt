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
 * NINT-005A provider-reported account usage 專用槽（Contract §2／§2.2／§6.4／§23.3；
 * PRODUCT_SPEC §10／§12／§18；RESEARCH_POLICY evidence 分級；Room v13 只加表）。
 *
 * 來源分離（Contract §2 ＋永久鐵律）：
 * - 本槽是 **PROVIDER_REPORTED**：站方對「整個帳號」的 request_count 原值觀測，
 *   可能包含 ModelAtlas 之外的 CLI / OpenCode / 其他裝置流量（Contract §6.4）。
 * - 永不寫 `samples`／`snapshot_facts`／`snapshot_heads`／`provider_perf_*`／
 *   telemetry；**不得**混入 My Uptime（MY_KEY_LOCAL）或 external availability。
 *   本機看不到的流量不可推給 local 來源，local 觀測也不得補本槽的 null。
 * - 站方 account total **不等於** 選定 key / model / group 的 usage：scope 固定
 *   provider×account，`credentialId` 只作 provenance，不做 per-key / per-model /
 *   per-group 切分（rc.25 `/api/user/self` 也沒有這種切分）。
 *
 * 資料槽權威分工：
 * - 本表新 datum 只有 [requestCountRaw]（站方 `request_count`）。
 * - `usedQuota` 已存在 `ACCOUNT_QUOTA.usedRaw`，維持原權威槽，**不在此重存／搬移／
 *   改寫**；因此 quota 缺漏不得讓本槽的 requestCount good 消失，反之亦然。
 *
 * Unknown ≠ 0（永久鐵律）：
 * - `request_count` 缺席／非 Long → null（Unknown），不是 0、不是 Free、不是可用性判斷。
 * - 站方回 0 是真實 0，保留為 0；`Long` 精度逐值保存，不經 double。
 * - 負值 → `MALFORMED` marker＋null count（由 store 決定），只 append 歷史、不推 lastGood。
 *
 * last-good 語意鏡射 `NewApiSnapshotStore.record`／[ProviderPerfHeadEntity]：
 * 失敗／空／malformed／Unknown 只 append 歷史、照 newer 推進 latest，
 * 唯有嚴格更新的 good 觀測才推進 lastGood。
 */
enum class ProviderAccountUsageKind {
    /** `GET /api/user/self` 的帳號級使用量；本片只有這一種 kind（不含 token/window 層）。 */
    PROVIDER_ACCOUNT_USAGE,
}

/**
 * rc.25 account usage 事實：`evidenceId` 同為 evidence 主鍵（FK RESTRICT）；
 * 失敗／Unknown／malformed 亦 append，歷史永不被 head 更新覆蓋。
 *
 * 無 body、無 prompt、無 PII、無 station user id、無 raw key（AGENTS 永久鐵律）。
 */
@Entity(
    tableName = "provider_account_usage_facts",
    foreignKeys = [ForeignKey(
        entity = EvidenceEntity::class, parentColumns = ["id"], childColumns = ["evidenceId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["scopeKey", "kind", "observedAt"])],
)
data class ProviderAccountUsageFactEntity(
    @PrimaryKey val evidenceId: String,
    val scopeKey: String,
    /** [ProviderAccountUsageKind.name]；非 null 靜態 enum 名，不存任意文字。 */
    val kind: String,
    /** scope：本槽唯一 provider 維度。 */
    val providerId: String,
    /** scope：本地 ACCOUNT credential 所屬 account ref；不宣稱 server-side user-id 認證。 */
    val accountId: String,
    /** provenance only（非 scope）：實際使用的 ACCOUNT PAT safe ref；絕不為 raw secret。 */
    val credentialId: String,
    val observedAt: Long,
    /** [dev.modelatlas.core.newapi.NewApiStatus.name]；成功為 `OK`，其餘為失敗分類。 */
    val status: String,
    /** 本次觀測的站方版本（safe 字串或 null）；certified 閘門與 quota/perf 同一 session 語意。 */
    val providerVersion: String?,
    /** 真實 account HTTP 回應狀態碼；null＝無 HTTP 回應（network failure／未知版本）。 */
    val httpStatus: Int?,
    /** 真實使用的 evidence source（如 `newapi/api/user/self`）；不得竄改為他來源。 */
    val source: String,
    /**
     * 站方 `request_count` 的**原始逐值**（exact `Long`，0 保留為 0）。
     * null＝Unknown（欄位缺席／非 Long）／malformed／failure 標記；**永不補 0**。
     * 不得推導成 per-key／per-model／per-group usage，也不得與 quota／price 互換。
     */
    val requestCountRaw: Long?,
    /**
     * 計數語意恆為 `UNSPECIFIED`（Contract §23.3 不猜語意）。
     *
     * 站方未揭露 `request_count` 是累計、視窗內、rate 還是會 reset 的 delta，因此本欄
     * 固定記錄 `UNSPECIFIED`，**不得**存成 cumulative／window／rate／delta 標籤，
     * 也不得做差值推導。寫入此字面值由 `ProviderAccountUsageStore` 擁有（避免重複宣告）。
     */
    val counterSemantics: String,
    /**
     * 是否真的取得 account HTTP 回應（certified capabilities Success 且 result evidence
     * 的 `httpStatus != null`）。false＝僅 adapter 被呼叫／network failure／未知版本，
     * 不可當成「已觀測到帳號端點」；無實際 account response 時不得標 true。
     */
    val accountEndpointObserved: Boolean,
)

/** 同 (scopeKey, kind) upsert 指標；歷史 fact／evidence 永不被指標更新覆蓋。 */
@Entity(tableName = "provider_account_usage_heads", primaryKeys = ["scopeKey", "kind"])
data class ProviderAccountUsageHeadEntity(
    val scopeKey: String,
    val kind: String,
    val latestEvidenceId: String,
    val latestObservedAt: Long,
    val lastGoodEvidenceId: String?,
    val lastGoodObservedAt: Long?,
)

/**
 * 純 persistence 介面：**無 network、無 delete**。寫入與守衛在
 * `ProviderAccountUsageStore` 自己的 transaction 內重複驗證（不只信上游）。
 */
@Dao
interface ProviderAccountUsageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFact(fact: ProviderAccountUsageFactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHead(head: ProviderAccountUsageHeadEntity)

    @Query("SELECT * FROM provider_account_usage_heads WHERE scopeKey = :scopeKey AND kind = :kind")
    suspend fun head(scopeKey: String, kind: String): ProviderAccountUsageHeadEntity?

    @Query("SELECT * FROM provider_account_usage_facts WHERE evidenceId = :id")
    suspend fun fact(id: String): ProviderAccountUsageFactEntity?

    @Query("SELECT * FROM provider_account_usage_facts WHERE scopeKey = :scopeKey AND kind = :kind ORDER BY observedAt DESC, evidenceId ASC")
    suspend fun history(scopeKey: String, kind: String): List<ProviderAccountUsageFactEntity>
}

/**
 * 精確 scope key（provider×account）：長度前綴編碼，空字串／分隔符皆不碰撞；
 * 帳號總量不因換 PAT 而分裂或互相覆蓋，但 PAT safe ref 仍逐列保存為 provenance。
 *
 * 本 encoder 屬本槽自有 namespace（含 [ProviderAccountUsageKind] 名稱前綴），
 * **不是**共用 `PricingScope` encoder，不與其他 scope 槽互相碰撞。
 * `providerId`／`accountId` 的 blank／長度／控制字元守衛由 store 負責（ID 進 DB 前驗證）。
 */
internal fun providerAccountUsageScopeKey(
    providerId: String,
    accountId: String,
): String = listOf(ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name, providerId, accountId)
    .joinToString("") { "${it.length}:$it" }
