package dev.modelatlas.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.modelatlas.core.domain.PriceSnapshot
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaSnapshot

/**
 * 官方與手填分槽；LOW 永遠不能成為官方 last-good。
 *
 * v7 追加三個 dedicated 命名空間 kind（MAJOR8：dedicated 與 NewApi 觀測互不覆蓋）：
 * 同七維 scopeKey 下 `PRICE`／`ACCOUNT_QUOTA`／`TOKEN_QUOTA` 屬 NewApi 官方入口，
 * dedicated 各家觀測改寫入 `DEDICATED_*`，兩者 head／last-good／evidence 互不覆蓋
 * （X-004 全 scope 隔離），且不同 dedicated 維度仍各自保有 latest 與 lastGood。
 * 純追加：新 kind 只多一組字串，無新表、無新欄、無 migration。
 */
enum class SnapshotKind {
    PRICE,
    ACCOUNT_QUOTA,
    TOKEN_QUOTA,
    MANUAL_PRICE,
    MANUAL_QUOTA,
    DEDICATED_PRICE,
    DEDICATED_ACCOUNT_QUOTA,
    DEDICATED_TOKEN_QUOTA,
}

/** 不可變觀測／PriceEvent：evidenceId 串回共用 evidence，失敗也保留證據。 */
@Entity(
    tableName = "snapshot_facts",
    foreignKeys = [ForeignKey(
        entity = EvidenceEntity::class, parentColumns = ["id"], childColumns = ["evidenceId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["scopeKey", "kind", "observedAt"])],
)
data class SnapshotFactEntity(
    @PrimaryKey val evidenceId: String,
    val scopeKey: String,
    val kind: String,
    val observedAt: Long,
    val snapshotId: String?,
    val state: String,
    val status: String,
    val confidence: String,
    val providerVersion: String?,
    val pricingVersion: String?,
    val itemPricingVersion: String?,
    val quotaPerUnit: String?,
    val groupRatio: String?,
    val statusObservedAt: Long?,
    val remainingRaw: Long?,
    val usedRaw: Long?,
    val grantedRaw: Long?,
    val unlimited: Boolean?,
    val expiresAtRaw: Long?,
    val expiryMeaning: String?,
    // 不保存站方可控 expression 原文，僅雜湊與語義標記。
    val expressionHash: String?,
    val priceUnit: String?,
    // v6（B3/H3）：quota 維度落庫＋估算標記，只加欄不改舊欄。
    // quotaScopeKind：QuotaScopeKind.name（僅 ACCOUNT_QUOTA／TOKEN_QUOTA，PRICE 為 null）。
    // quotaProjectId：QuotaPool.projectId（Gemini project，必須等於 scope.group）。
    // quotaSlice：QuotaPoolSlice.name（STANDARD_INTERACTIVE／PRIORITY／BATCH）。
    // isEstimated：true＝估算／上界／policy 非 ceiling（upperBound／requiresLogin／
    //   appliesToMostModels／OpenRouter exempt），false＝精確值，null＝舊列／不適用。
    val quotaScopeKind: String? = null,
    val quotaProjectId: String? = null,
    val quotaSlice: String? = null,
    val isEstimated: Boolean? = null,
)

/** 同七維 scope upsert 指標；歷史 snapshot / evidence 永不被指標更新覆蓋。 */
@Entity(tableName = "snapshot_heads", primaryKeys = ["scopeKey", "kind"])
data class SnapshotHeadEntity(
    val scopeKey: String,
    val kind: String,
    val latestEvidenceId: String,
    val latestObservedAt: Long,
    val lastGoodSnapshotId: String?,
    val lastGoodObservedAt: Long?,
)

/**
 * CR-011：quota-slice head（additive-only）。Unsliced 命名空間
 * （fact.quotaSlice null/''）永遠只走 `snapshot_heads`（identity
 * (scopeKey, kind)，byte-identical）；legal slice（QuotaPoolSlice.name：
 * STANDARD_INTERACTIVE／PRIORITY／BATCH，僅四種 quota kind）每一切片在此表
 * 獨立 strict-advance（I1 collision fix：同 7-dim scope＋kind 的三切片得三個
 * head row）。`quotaSlice TEXT NOT NULL`（storageKey 七維編碼不動；
 * 禁止把 slice 拼進 scopeKey——parseScopeKey 要求恰 7 維且 pos==key.length；
 * 禁止編進 kind——破壞 headsByKind 枚舉）。
 * 無 FK（沿 snapshot_heads precedent）；kind 單列 INDEX。
 * 舊 snapshot_heads 列（含歷史 mixed collision artifact）一律 verbatim
 * carry、永不 recompute（見 MIGRATION_14_15 KDoc）。
 */
@Entity(
    tableName = "snapshot_slice_heads",
    primaryKeys = ["scopeKey", "kind", "quotaSlice"],
    indices = [Index("kind")],
)
data class SnapshotSliceHeadEntity(
    val scopeKey: String,
    val kind: String,
    val quotaSlice: String,
    val latestEvidenceId: String,
    val latestObservedAt: Long,
    val lastGoodSnapshotId: String?,
    val lastGoodObservedAt: Long?,
)

@Dao
abstract class SnapshotFactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(fact: SnapshotFactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertHead(head: SnapshotHeadEntity)

    @Query("SELECT * FROM snapshot_heads WHERE scopeKey = :scopeKey AND kind = :kind")
    abstract suspend fun head(scopeKey: String, kind: String): SnapshotHeadEntity?

    /**
     * 同 kind 全部 heads（呼叫端在 Kotlin 精確解析 scopeKey 後過濾）。
     * 禁止 `LIKE` 模糊投影與 `LIMIT 1` 挑列：UI 列缺 group／operation 維度時，
     * 候選集合 state 一致才取值、不一致回 `UNKNOWN`。
     */
    @Query("SELECT * FROM snapshot_heads WHERE kind = :kind")
    abstract suspend fun headsByKind(kind: String): List<SnapshotHeadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSliceHead(head: SnapshotSliceHeadEntity)

    @Query("SELECT * FROM snapshot_slice_heads WHERE scopeKey = :scopeKey AND kind = :kind AND quotaSlice = :quotaSlice")
    abstract suspend fun sliceHead(scopeKey: String, kind: String, quotaSlice: String): SnapshotSliceHeadEntity?

    /**
     * 同 kind 全部 slice heads（呼叫端在 Kotlin 精確解析 scopeKey 後過濾，
     * 與 headsByKind 同契約）。禁止 `LIKE` 模糊投影與 `LIMIT 1` 挑列。
     */
    @Query("SELECT * FROM snapshot_slice_heads WHERE kind = :kind")
    abstract suspend fun sliceHeadsByKind(kind: String): List<SnapshotSliceHeadEntity>

    @Query("SELECT * FROM snapshot_facts WHERE evidenceId = :id")
    abstract suspend fun evidence(id: String): SnapshotFactEntity?

    @Query("SELECT * FROM snapshot_facts WHERE scopeKey = :scopeKey AND kind = :kind ORDER BY observedAt DESC, evidenceId ASC")
    abstract suspend fun history(scopeKey: String, kind: String): List<SnapshotFactEntity>

    /**
     * H5 跨呼叫去重：同 scope＋kind＋observedAt 的既有列（重試／重放時去重用）。
     * 呼叫端在 mutex＋transaction 內比對 state／status；價格另比 priceUnit／source／legs，
     * 無值觀測只匹配同 source 的無值列；quota 另比 quotaScopeKind／quotaProjectId／quotaSlice／
     * isEstimated，key quota 另比 raws／estimated。完整 value-aware key 相同才跳過寫入。
     */
    @Query("SELECT * FROM snapshot_facts WHERE scopeKey = :scopeKey AND kind = :kind AND observedAt = :observedAt")
    abstract suspend fun byScopeKindObserved(scopeKey: String, kind: String, observedAt: Long): List<SnapshotFactEntity>

    /**
     * CR-006（D5／D6）：以 provider 前綴範圍只取該 provider 的中間 facts。
     * 先在 Kotlin 用 [providerScopeRange] 算邊界，再走 annotated range port；
     * 不用 `LIKE`，舊 entities／index／key 全不動。Ordering observedAt DESC,
     * evidenceId ASC。範圍內仍可能有 malformed scopeKey，caller 必須再以
     * [parseScopeKey] 精確過濾（見 ProviderDetailLoader facts history）。
     */
    suspend fun recentFactsForProviderKind(
        providerId: String,
        kind: String,
        limit: Int,
    ): List<SnapshotFactEntity> {
        val (lo, hi) = providerScopeRange(providerId)
        return recentFactsForProviderRange(lo = lo, hi = hi, kind = kind, limit = limit)
    }

    @Query(
        "SELECT * FROM snapshot_facts WHERE scopeKey >= :lo AND scopeKey < :hi " +
            "AND kind = :kind ORDER BY observedAt DESC, evidenceId ASC LIMIT :limit",
    )
    protected abstract suspend fun recentFactsForProviderRange(
        lo: String,
        hi: String,
        kind: String,
        limit: Int,
    ): List<SnapshotFactEntity>

    /**
     * CR-010：一次 join 讀取 price/quota head 投影（8 次全 kind 掃描 + N 次逐 head
     * fact/price 讀取 → 單一 statement）。`latestState`/`latestFactObservedAt` 只來自
     * `h.latestEvidenceId` join 的 snapshot_facts（與舊 `evidence(id) ?: continue`
     * (`AtlasViewModel.kt:2342`) 等價）；數字腿只來自 `h.lastGoodSnapshotId` join 的
     * price_snapshots，且 gate `h.kind IN (:priceKinds)` 必須留在 price join（quota
     * head 不得取到 price 數字/source，也不 join quota_snapshots）。
     * `snapshot_facts` 無 source 欄：`latestEvidenceSource` 來自 evidence 列
     * (`EvidenceEntity.source`, `Entities.kt:255`)、`lastGoodPriceSource` 來自 price 列
     * (`PriceSnapshotEntity.source`, `Entities.kt:290`)，兩欄分離、不排名、不互推。
     * 七維解析與 match policy 全部留在 Kotlin（`parseScopeKey` `:180-198` + 既有
     * `ModelRowMapper`）；不加 SQL LIKE/SUBSTR/scope pruning，不 GROUP BY、不挑
     * kind winner。
     */
    @Query(
        "SELECT h.scopeKey AS headScopeKey, h.kind AS headKind, " +
            "h.latestEvidenceId AS latestEvidenceId, f.state AS latestState, " +
            "f.observedAt AS latestFactObservedAt, e.source AS latestEvidenceSource, " +
            "h.lastGoodSnapshotId AS lastGoodSnapshotId, p.input AS lastGoodInput, " +
            "p.output AS lastGoodOutput, p.cacheRead AS lastGoodCacheRead, " +
            "p.source AS lastGoodPriceSource " +
            "FROM snapshot_heads AS h " +
            "INNER JOIN snapshot_facts AS f ON f.evidenceId = h.latestEvidenceId " +
            "LEFT JOIN evidence AS e ON e.id = f.evidenceId " +
            "LEFT JOIN price_snapshots AS p ON p.id = h.lastGoodSnapshotId " +
            "AND h.kind IN (:priceKinds) " +
            "WHERE h.kind IN (:kinds) " +
            "ORDER BY h.scopeKey ASC, h.kind ASC",
    )
    abstract suspend fun priceQuotaHeadProjections(
        kinds: List<String>,
        priceKinds: List<String>,
    ): List<PriceQuotaHeadProjectionRow>

    @Query(
        "SELECT s.scopeKey AS headScopeKey, s.kind AS headKind, " +
            "s.quotaSlice AS headQuotaSlice, " +
            "s.latestEvidenceId AS latestEvidenceId, " +
            "s.latestObservedAt AS headLatestObservedAt, " +
            "f.state AS latestState, " +
            "f.observedAt AS latestFactObservedAt, " +
            "e.source AS latestEvidenceSource " +
            "FROM snapshot_slice_heads AS s " +
            "INNER JOIN snapshot_facts AS f ON f.evidenceId = s.latestEvidenceId " +
            "LEFT JOIN evidence AS e ON e.id = f.evidenceId " +
            "WHERE s.kind IN (:kinds) " +
            "ORDER BY s.scopeKey ASC, s.kind ASC, s.quotaSlice ASC",
    )
    abstract suspend fun slicePriceQuotaHeadProjections(
        kinds: List<String>,
    ): List<SlicePriceQuotaHeadProjectionRow>
}

/** CR-010 單次 join 的投影列（Room/KSP alias/nullability 以 CI 編譯為準）。 */
data class PriceQuotaHeadProjectionRow(
    val headScopeKey: String,
    val headKind: String,
    val latestEvidenceId: String,
    val latestState: String,
    val latestFactObservedAt: Long,
    val latestEvidenceSource: String?,
    val lastGoodSnapshotId: String?,
    val lastGoodInput: String?,
    val lastGoodOutput: String?,
    val lastGoodCacheRead: String?,
    val lastGoodPriceSource: String?,
)

data class SlicePriceQuotaHeadProjectionRow(
    val headScopeKey: String,
    val headKind: String,
    val headQuotaSlice: String,
    val latestEvidenceId: String,
    val headLatestObservedAt: Long,
    val latestState: String,
    val latestFactObservedAt: Long,
    val latestEvidenceSource: String?,
)

/** latest.state 為本次狀態；lastGood 僅為歷史事實，不能拿來冒充本次成功。 */
data class StoredPrice(val latest: SnapshotFactEntity?, val lastGood: PriceSnapshot?)
data class StoredQuota(val latest: SnapshotFactEntity?, val lastGood: QuotaSnapshot?)

/** 長度前綴編碼：null、空字串、分隔符皆不碰撞，不使用 nullable SQL UNIQUE。 */
internal fun PricingScope.storageKey(): String = listOf(
    providerId, accountId, credentialId, modelId, group, endpointId, operation,
).joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }

/**
 * CR-006（D6）：該 provider 在 [PricingScope.storageKey] 下的 scopeKey 前綴範圍。
 * lo 就是第一維的長度前綴片段，hi 為 lo 加 U+10FFFF（最高 code point，
 * 最大 UTF-8 TEXT 邊界，SQLite BINARY 下的通用安全上界）。長度前綴讓只有
 * 同 provider 的 key 會以 lo 開頭；解析仍要回 [parseScopeKey]，不能用範圍
 * 當精確過濾。真正的 Unicode/BINARY 排序證據必須是 device 上的真 SQLite
 * （T9 device leg）；JVM `String.compareTo` 是 UTF-16 碼元順序、遇到
 * supplementary char 與 UTF-8 byte order 不同，不能當 SQL 比較 oracle。
 */
internal fun providerScopeRange(providerId: String): Pair<String, String> {
    val lo = "${providerId.length}:$providerId"
    return lo to lo + "\uDBFF\uDFFF"
}

/**
 * 精確解析 [PricingScope.storageKey] 為 7 維
 * `[providerId, accountId, credentialId, modelId, group, endpointId, operation]`。
 * 格式不合回 null（呼叫端 fail-closed 忽略該列，絕不猜）。
 */
internal fun parseScopeKey(key: String): List<String?>? {
    val out = ArrayList<String?>(7)
    var pos = 0
    repeat(7) {
        val colon = key.indexOf(':', pos)
        if (colon < 0) return null
        val len = key.substring(pos, colon).toIntOrNull() ?: return null
        pos = colon + 1
        if (len == -1) {
            out += null
        } else {
            if (len < 0 || len > key.length - pos) return null
            out += key.substring(pos, pos + len)
            pos += len
        }
    }
    if (pos != key.length) return null
    return out
}
