package dev.modelatlas.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ③-1 My Models membership（D-008／Contract §23.1；Catalog ≠ My Models）。
 *
 * - 只有使用者／Assistant／Explore 的明確加入動作才建 membership；背景 catalog、
 *   discovery、sync 永不寫入本表（不自動污染）。
 * - 鍵為 (providerId, providerModelId)：同一 canonical 在不同 provider 是不同
 *   My Model，不互相冒充、不自動跟隨。
 * - 與 MODEL-006 hidden／verification 正交：membership 只管「是不是我的模型」，
 *   可見性／驗證事實由既有欄位決定。移除 membership 永不刪除 offering、
 *   telemetry、evidence、history（D-006 永久保留）。
 * - 無 FK：catalog offering 可能尚未落庫；缺席不阻擋加入，補齊後自動對應。
 * - Room v11，只加表（MIGRATION_10_11）；舊庫全列預設非成員（空表）。
 */
@Entity(
    tableName = "my_models",
    primaryKeys = ["providerId", "providerModelId"],
    indices = [Index(value = ["addedAt"], name = "index_my_models_added")],
)
data class MyModelEntity(
    val providerId: String,
    val providerModelId: String,
    val addedAt: Long = 0L,
    val source: String = "USER",
)

data class MyModelKey(
    val providerId: String,
    val providerModelId: String,
)

@Dao
interface MyModelsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: MyModelEntity)

    @Query("DELETE FROM my_models WHERE providerId = :providerId AND providerModelId = :providerModelId")
    suspend fun delete(providerId: String, providerModelId: String): Int

    @Query("SELECT * FROM my_models ORDER BY addedAt DESC, providerId ASC, providerModelId ASC")
    fun observe(): Flow<List<MyModelEntity>>

    @Query("SELECT * FROM my_models ORDER BY addedAt DESC, providerId ASC, providerModelId ASC")
    suspend fun all(): List<MyModelEntity>

    @Query("SELECT COUNT(*) FROM my_models WHERE providerId = :providerId AND providerModelId = :providerModelId")
    suspend fun count(providerId: String, providerModelId: String): Int
}

val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `my_models` (`providerId` TEXT NOT NULL, `providerModelId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`providerId`, `providerModelId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_my_models_added` ON `my_models` (`addedAt`)")
    }
}

/**
 * My Models membership store：明確加入／移除＋ reactive 查詢。
 *
 * - 空白 providerId／providerModelId 直接拒絕（require），不寫無意義列。
 * - 重複加入冪等（REPLACE 同鍵，只更新 addedAt／source）。
 * - source 僅 USER／ASSISTANT／EXPLORE（明確加入來源）；其他字串 fail-closed 拒絕，
 *   不寫入、不冒充。
 * - 只回鍵集合，無 secret、無網路。
 */
class MyModelsStore(private val db: AtlasDatabase) {

    val myModels: Flow<Set<MyModelKey>> =
        db.myModelsDao().observe().map { rows -> rows.map { MyModelKey(it.providerId, it.providerModelId) }.toSet() }

    suspend fun myModelKeys(): Set<MyModelKey> =
        db.myModelsDao().all().map { MyModelKey(it.providerId, it.providerModelId) }.toSet()

    suspend fun isMyModel(providerId: String, providerModelId: String): Boolean =
        db.myModelsDao().count(providerId, providerModelId) > 0

    /** 明確加入；回傳加入後是否為成員。 */
    suspend fun addToMyModels(
        providerId: String,
        providerModelId: String,
        source: String = "USER",
        nowMillis: Long = 0L,
    ): Boolean = db.withTransaction {
        require(providerId.isNotBlank() && providerModelId.isNotBlank()) { "provider/model must not be blank" }
        require(source == "USER" || source == "ASSISTANT" || source == "EXPLORE") { "unknown membership source" }
        db.myModelsDao().put(MyModelEntity(providerId, providerModelId, nowMillis, source))
        true
    }

    /** 明確移除；只刪 membership 列，不碰 offering／telemetry／history。回傳移除後是否仍為成員。 */
    suspend fun removeFromMyModels(providerId: String, providerModelId: String): Boolean = db.withTransaction {
        db.myModelsDao().delete(providerId, providerModelId)
        false
    }
}
