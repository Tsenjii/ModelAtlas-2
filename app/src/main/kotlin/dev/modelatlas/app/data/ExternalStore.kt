package dev.modelatlas.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.lmspeed.LMSPEED_MAX_REQUESTS_PER_DAY
import dev.modelatlas.core.lmspeed.LmspeedDailyBudget

/**
 * LMS-003／LMS-004 外部觀測＋映射寫入／查詢層（Room v8；TRACEABILITY LMS-003／LMS-004、
 * PRODUCT_SPEC §13、RESEARCH_POLICY §7）。
 *
 * 鐵律（讀寫分離，違者 CI 擋）：
 * - external 資料絕不寫入 snapshot_facts／snapshot_heads／samples／telemetry。
 *   本 store 只觸碰 external_observations／external_mappings 兩表。
 * - 本 store 不做網路：import／fetch 是 S2 的事，此處只做已取得承載的落庫＋TTL＋映射。
 * - source-agnostic：source／externalId 皆為不透明字串，不寫任何來源 URL／endpoint
 *  （來源清單等 R 研究）。
 * - UNKNOWN 語意：canonicalModelId null＝尚未解析，不猜；confidence 缺席即 UNKNOWN，
 *   不填零值；resolve 缺席回 null。
 * - manual／official／observed／external 分離：external 槽獨立，與
 *   provider_model_aliases／model_classifications 互不覆蓋、不互相冒充。
 */
@Dao
interface ExternalObservationDao {
    /** 冪等 upsert：同 observationId 覆寫，不增列。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ExternalObservationEntity)

    /** 批次冪等 upsert。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ExternalObservationEntity>)

    @Query("SELECT * FROM external_observations WHERE observationId = :observationId")
    suspend fun getById(observationId: String): ExternalObservationEntity?

    @Query("SELECT * FROM external_observations WHERE source = :source AND externalModelId = :externalModelId ORDER BY fetchedAt DESC, observationId ASC")
    suspend fun byExternal(source: String, externalModelId: String): List<ExternalObservationEntity>

    @Query("SELECT * FROM external_observations WHERE canonicalModelId = :canonicalModelId ORDER BY fetchedAt DESC, observationId ASC")
    suspend fun byCanonical(canonicalModelId: String): List<ExternalObservationEntity>

    /**
     * TTL 過期查詢：fetchedAt＋ttlMillis 已屆（<= now）即過期。
     * ttlMillis＝0 即寫入當下過期；呼叫端以 nowMillis 傳入裝置時間，不猜時間。
     * D-2026-09-30-006：此查詢僅供「重抓新鮮度」觸發（是否重抓），不得作為刪除依據；
     * 回傳列仍是觀測歷史，永久保留。
     */
    @Query("SELECT * FROM external_observations WHERE fetchedAt + ttlMillis <= :nowMillis ORDER BY fetchedAt ASC, observationId ASC")
    suspend fun expired(nowMillis: Long): List<ExternalObservationEntity>

    @Query("SELECT COUNT(*) FROM external_observations")
    suspend fun count(): Int

    /** 過期清理：D-2026-09-30-006 永久保留，已停用。保留此 DAO 方法僅為原始碼相容，禁止呼叫。
     * external_observations 皆為觀測歷史，永不得刪；TTL 只做重抓新鮮度觸發。 */
    @Deprecated(
        message = "D-2026-09-30-006 永久保留：禁止刪除觀測歷史，ExternalStore.purgeExpired 已改為 no-op。",
        level = DeprecationLevel.WARNING,
    )
    @Query("DELETE FROM external_observations WHERE fetchedAt + ttlMillis <= :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int
}

@Dao
interface ExternalMappingDao {
    /** 同槽覆寫（同 externalId＋同 source，以同 mappingId 冪等 REPLACE）；跨槽各列獨立。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ExternalMappingEntity)

    /** 唯一鍵解析：缺席回 null（UNKNOWN，不猜）。 */
    @Query("SELECT * FROM external_mappings WHERE externalId = :externalId AND source = :source")
    suspend fun resolve(externalId: String, source: String): ExternalMappingEntity?

    @Query("SELECT * FROM external_mappings WHERE canonicalModelId = :canonicalModelId ORDER BY source ASC, externalId ASC")
    suspend fun byCanonical(canonicalModelId: String): List<ExternalMappingEntity>

    @Query("SELECT COUNT(*) FROM external_mappings")
    suspend fun count(): Int
}

/**
 * 外部快取 store：只讀寫 external_* 兩表（見本檔頂鐵律）。
 */
class ExternalStore(private val db: AtlasDatabase) {

    /**
     * 批次保存外部觀測（冪等）。
     * 同 observationId 覆寫；呼叫端以 [observationIdOf] 產生決定性 ID 時，
     * 同 source＋externalModelId＋fetchedAt 重送即覆寫，不增列。
     */
    suspend fun saveObservations(rows: List<ExternalObservationEntity>): List<ExternalObservationEntity> =
        db.withTransaction {
            rows.forEach { requireValidObservation(it) }
            if (rows.isNotEmpty()) db.externalObservationDao().upsertAll(rows)
            rows
        }

    suspend fun saveObservation(row: ExternalObservationEntity): ExternalObservationEntity =
        saveObservations(listOf(row)).single()

    /**
     * 外部映射保存（冪等，同 externalId＋同 source 覆寫）。
     * mappingId 由 [mappingIdOf] 決定性編碼；verified=true 僅 HIGH／MEDIUM
     *（弱 evidence 不得宣稱已驗證）；canonicalModelId null＝尚未解析。
     */
    suspend fun saveMapping(
        externalId: String,
        canonicalModelId: String?,
        source: String,
        confidence: String = MappingConfidence.UNKNOWN.name,
        verified: Boolean = false,
        version: Int = 1,
        observedAt: Long = 0L,
    ): ExternalMappingEntity = db.withTransaction {
        require(externalId.isNotBlank()) { "externalId must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        require(version >= 1) { "version must be >= 1" }
        val mappingConfidence = enumValueOrThrow(confidence, "Unknown mapping confidence")
        if (verified) {
            require(
                mappingConfidence == MappingConfidence.HIGH ||
                    mappingConfidence == MappingConfidence.MEDIUM,
            ) { "Only HIGH/MEDIUM mappings may be marked verified" }
        }
        if (!canonicalModelId.isNullOrBlank()) require(canonicalModelId.isNotBlank()) { "canonicalModelId must not be blank" }
        val row = ExternalMappingEntity(
            mappingId = mappingIdOf(externalId, source),
            externalId = externalId,
            canonicalModelId = canonicalModelId?.takeIf { it.isNotBlank() },
            source = source,
            confidence = mappingConfidence.name,
            verified = verified,
            version = version,
            observedAt = observedAt,
        )
        db.externalMappingDao().upsert(row)
        db.externalMappingDao().resolve(externalId, source)
            ?: throw IllegalStateException("External mapping write did not persist")
    }

    /** 唯一鍵解析；缺席回 null，不猜（UNKNOWN）。 */
    suspend fun resolve(externalId: String, source: String): ExternalMappingEntity? =
        db.externalMappingDao().resolve(externalId, source)

    suspend fun mappingsForCanonical(canonicalModelId: String): List<ExternalMappingEntity> =
        db.externalMappingDao().byCanonical(canonicalModelId)

    suspend fun observationsOf(source: String, externalModelId: String): List<ExternalObservationEntity> =
        db.externalObservationDao().byExternal(source, externalModelId)

    /** TTL 過期查詢（只讀 external_observations）。D-2026-09-30-006：僅供重抓新鮮度觸發，不刪除。 */
    suspend fun expiredObservations(nowMillis: Long): List<ExternalObservationEntity> =
        db.externalObservationDao().expired(nowMillis)

    /**
     * 過期清理——D-2026-09-30-006（永久保留）no-op。
     *
     * 本 store 沒有「明確可丟棄的暫存」：external_observations／external_mappings 皆為
     * 觀測／evidence 歷史，永不得刪；TTL 只作為「重抓新鮮度」觸發。為保原始碼相容保留
     * [purgeExpired] 簽章，但恆回 0、不觸碰 DB。若未來出現真正可丟棄的暫存（非觀測歷史），
     * 須另立決策並新增專屬方法，不得重用此路徑刪除觀測歷史。
     */
    suspend fun purgeExpired(@Suppress("UNUSED_PARAMETER") nowMillis: Long): Int = 0

    /**
     * P7-B2 映射決策寫回（追加；既有 [saveMapping] 語意不動）。
     * TRACEABILITY LMS-002／LMS-005：承接 [ExternalCanonicalDecision]
     * （來源／信心結構），轉交 [saveMapping] 落 `external_mappings` 槽；
     * 只寫外部槽，不碰 snapshot／samples／telemetry。
     */
    suspend fun saveDecision(
        externalId: String,
        decision: ExternalCanonicalDecision,
        verified: Boolean = false,
        version: Int = 1,
        observedAt: Long = 0L,
    ): ExternalMappingEntity = saveMapping(
        externalId = externalId,
        canonicalModelId = decision.canonicalId,
        source = decision.source,
        confidence = decision.confidence,
        verified = verified,
        version = version,
        observedAt = observedAt,
    )

    /** 記憶體側 TTL 判斷（與 SQL `fetchedAt + ttlMillis <= now` 同語意）。D-2026-09-30-006：僅供重抓新鮮度觸發，不刪除。 */
    fun isExpired(row: ExternalObservationEntity, nowMillis: Long): Boolean =
        row.fetchedAt + row.ttlMillis <= nowMillis

    /**
     * P7 repair（LMS-003 read consumer；追加只讀，不寫任何表）。
     * 給 Models／Detail 外部分欄用（UI 由 B 接線）：以 canonical 反查全部外部映射
     * （`external_mappings.byCanonical`）→ 逐組取觀測（`byExternal`）＋直接掛 canonical
     * 的觀測（`byCanonical`），去重後按 TTL 過濾（預設剔除過期；`includeExpired=true`
     * 才保留，呼叫端明示），`fetchedAt` 倒序。只讀 external 兩表，不碰本地表。
     */
    suspend fun observationsForCanonical(
        canonicalModelId: String,
        nowMillis: Long,
        includeExpired: Boolean = false,
    ): List<ExternalObservationEntity> {
        if (canonicalModelId.isBlank()) return emptyList()
        val seen = LinkedHashMap<String, ExternalObservationEntity>()
        for (mapping in mappingsForCanonical(canonicalModelId)) {
            for (row in observationsOf(mapping.source, mapping.externalId)) {
                seen.putIfAbsent(row.observationId, row)
            }
        }
        for (row in db.externalObservationDao().byCanonical(canonicalModelId)) {
            seen.putIfAbsent(row.observationId, row)
        }
        return seen.values
            .filter { includeExpired || !isExpired(it, nowMillis) }
            .sortedWith(compareByDescending<ExternalObservationEntity> { it.fetchedAt }.thenBy { it.observationId })
    }

    /** P7 repair：同 source＋external 取最新觀測（無即 null，不猜）；只讀外部槽。 */
    suspend fun latestObservationOf(source: String, externalModelId: String): ExternalObservationEntity? =
        observationsOf(source, externalModelId).maxWithOrNull(
            compareBy<ExternalObservationEntity> { it.fetchedAt }.thenBy { it.observationId },
        )

    private fun requireValidObservation(row: ExternalObservationEntity) {
        require(row.observationId.isNotBlank()) { "observationId must not be blank" }
        require(row.source.isNotBlank()) { "source must not be blank" }
        require(row.externalModelId.isNotBlank()) { "externalModelId must not be blank" }
        require(row.payloadJson.isNotBlank()) { "payloadJson must not be blank" }
        require(row.ttlMillis >= 0L) { "ttlMillis must be >= 0" }
        enumValueOrThrow(row.confidence, "Unknown observation confidence")
    }

    private fun enumValueOrThrow(name: String, message: String): MappingConfidence =
        runCatching { enumValueOf<MappingConfidence>(name) }.getOrElse { throw IllegalArgumentException(message) }
}

/** 決定性觀測 ID（長度前綴，source／model 含分隔符也不碰撞）。 */
internal fun observationIdOf(source: String, externalModelId: String, fetchedAt: Long): String =
    "ext-obs:${source.length}:$source:${externalModelId.length}:$externalModelId:$fetchedAt"

/** 同 externalId 同 source 恰一列；跨來源各列獨立。 */
internal fun mappingIdOf(externalId: String, source: String): String =
    "ext-map:${externalId.length}:$externalId:${source.length}:$source"

/**
 * P7 repair 持久化單日帳本（LMS-001／LMS-005；TRACEABILITY budget 重啟不丟）。
 *
 * 為何不用 Room 表：`Entities`／`Daos`／Migration 禁碰（A 委派範圍外），新增 Room 表需
 * bump version＋migration；配額計數器以 `SharedPreferences` 持久化即滿足「重啟不丟」，
 * 不進 Room、不碰 snapshot／telemetry、不存任何 secret（只存計數＋日期）。
 * - 每 safe ref 一本：prefs key 以 ref 的 `hashCode` 命名空間隔離（X-004 跨 key 不共用）；
 *   ref 本身非 secret，可進 key，但為防非法字元／長度只存 hash。
 * - device-local 日期換日（與 `InMemoryLmspeedDailyBudget` 同語意；server 端 1000/day
 *   重置點未證實，本地只做保守限流，不宣稱與 server 對齊——reset 語義不猜）。
 * - `tryConsume` 成功即 `apply()` 持久化（程序被殺亦不丟已扣額度）；已扣不退。
 */
class SharedPrefsLmspeedDailyBudget(
    context: android.content.Context,
    safeCredentialId: String,
    private val maxPerDay: Int = LMSPEED_MAX_REQUESTS_PER_DAY,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) : LmspeedDailyBudget {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val keyDay = "lmspeed_budget_day_${safeCredentialId.hashCode()}"
    private val keyUsed = "lmspeed_budget_used_${safeCredentialId.hashCode()}"

    init {
        require(safeCredentialId.isNotBlank()) { "safeCredentialId must not be blank" }
        require(maxPerDay > 0) { "maxPerDay must be > 0" }
    }

    @Synchronized
    override fun remaining(nowMillis: Long): Int {
        require(nowMillis >= 0L) { "nowMillis must be >= 0" }
        maybeRoll(nowMillis)
        return (maxPerDay - prefs.getInt(keyUsed, 0)).coerceAtLeast(0)
    }

    @Synchronized
    override fun tryConsume(nowMillis: Long): Boolean {
        require(nowMillis >= 0L) { "nowMillis must be >= 0" }
        maybeRoll(nowMillis)
        val used = prefs.getInt(keyUsed, 0)
        if (used >= maxPerDay) return false
        prefs.edit().putInt(keyUsed, used + 1).apply()
        return true
    }

    private fun maybeRoll(nowMillis: Long) {
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()
        if (prefs.getString(keyDay, null) != today) {
            prefs.edit().putString(keyDay, today).putInt(keyUsed, 0).apply()
        }
    }

    companion object {
        const val PREFS_NAME = "lmspeed_budget"
    }
}
