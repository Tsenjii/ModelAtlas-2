package dev.modelatlas.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import dev.modelatlas.core.domain.canonicalIdFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * MODEL-005／§8 收藏／關注／最近使用 DAO（Room v9，只讀寫三張新表）。
 * 一律以 canonicalModelId 為鍵（與 `ModelSearchLogic` 的 favoriteIds／watchlistIds／recentIds
 * 同一命名空間）；不碰 offerings／availability／snapshots。
 */
@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFavorite(entity: FavoriteModelEntity)

    @Query("DELETE FROM favorite_models WHERE canonicalModelId = :canonicalModelId")
    suspend fun deleteFavorite(canonicalModelId: String): Int

    @Query("SELECT * FROM favorite_models ORDER BY addedAt DESC, canonicalModelId ASC")
    fun observeFavorites(): Flow<List<FavoriteModelEntity>>

    @Query("SELECT * FROM favorite_models ORDER BY addedAt DESC, canonicalModelId ASC")
    suspend fun favorites(): List<FavoriteModelEntity>

    @Query("SELECT canonicalModelId FROM favorite_models")
    suspend fun favoriteIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putWatchlist(entity: WatchlistModelEntity)

    @Query("DELETE FROM watchlist_models WHERE canonicalModelId = :canonicalModelId")
    suspend fun deleteWatchlist(canonicalModelId: String): Int

    @Query("SELECT * FROM watchlist_models ORDER BY addedAt DESC, canonicalModelId ASC")
    fun observeWatchlist(): Flow<List<WatchlistModelEntity>>

    @Query("SELECT canonicalModelId FROM watchlist_models")
    suspend fun watchlistIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecent(entity: RecentModelEntity)

    @Query("SELECT * FROM recent_models WHERE canonicalModelId = :canonicalModelId")
    suspend fun recent(canonicalModelId: String): RecentModelEntity?

    @Query("SELECT * FROM recent_models ORDER BY lastUsedAt DESC, canonicalModelId ASC")
    fun observeRecent(): Flow<List<RecentModelEntity>>

    @Query("SELECT canonicalModelId FROM recent_models ORDER BY lastUsedAt DESC, canonicalModelId ASC")
    suspend fun recentIds(): List<String>

    @Query("DELETE FROM recent_models")
    suspend fun clearRecent(): Int
}

/**
 * MODEL-005 收藏 store（favorites／watchlist／recently used 持久化；PRODUCT_SPEC §8）。
 *
 * - 鍵為 canonical 正規化 id（`canonicalIdFor`），同一模型不同 provider model ID 收斂為一鍵。
 * - 空 id 直接拒絕（require），不靜默寫入無意義列。
 * - 使用者狀態與目錄分離：收藏不寫 official／observed／availability，不冒充 provider 事實。
 * - recent 以 write-read-write 於 transaction 內遞增 useCount，並更新 lastUsedAt。
 * - 只回鍵集合／列，無 secret、無網路。
 */
class FavoriteStore(private val db: AtlasDatabase) {

    val favorites: Flow<Set<String>> =
        db.favoriteDao().observeFavorites().map { rows -> rows.map { it.canonicalModelId }.toSet() }

    val watchlist: Flow<Set<String>> =
        db.favoriteDao().observeWatchlist().map { rows -> rows.map { it.canonicalModelId }.toSet() }

    val recent: Flow<List<String>> =
        db.favoriteDao().observeRecent().map { rows -> rows.map { it.canonicalModelId } }

    suspend fun favoriteIds(): Set<String> = db.favoriteDao().favoriteIds().toSet()

    suspend fun watchlistIds(): Set<String> = db.favoriteDao().watchlistIds().toSet()

    suspend fun recentIds(): List<String> = db.favoriteDao().recentIds()

    suspend fun isFavorite(rawModelId: String): Boolean =
        db.favoriteDao().favoriteIds().contains(canonicalKey(rawModelId))

    suspend fun isWatched(rawModelId: String): Boolean =
        db.favoriteDao().watchlistIds().contains(canonicalKey(rawModelId))

    /** 設定收藏狀態；回傳設定後是否為收藏。重複設定冪等（REPLACE 同鍵）。 */
    suspend fun setFavorite(rawModelId: String, favorite: Boolean, nowMillis: Long = 0L): Boolean =
        db.withTransaction {
            val key = canonicalKey(rawModelId)
            if (favorite) {
                db.favoriteDao().putFavorite(FavoriteModelEntity(canonicalModelId = key, addedAt = nowMillis))
                true
            } else {
                db.favoriteDao().deleteFavorite(key)
                false
            }
        }

    suspend fun toggleFavorite(rawModelId: String, nowMillis: Long = 0L): Boolean =
        setFavorite(rawModelId, !isFavorite(rawModelId), nowMillis)

    suspend fun setWatchlist(rawModelId: String, watched: Boolean, note: String? = null, nowMillis: Long = 0L): Boolean =
        db.withTransaction {
            val key = canonicalKey(rawModelId)
            if (watched) {
                db.favoriteDao().putWatchlist(WatchlistModelEntity(canonicalModelId = key, addedAt = nowMillis, note = note))
                true
            } else {
                db.favoriteDao().deleteWatchlist(key)
                false
            }
        }

    suspend fun toggleWatchlist(rawModelId: String, nowMillis: Long = 0L): Boolean =
        setWatchlist(rawModelId, !isWatched(rawModelId), nowMillis = nowMillis)

    /** 記錄一次使用：lastUsedAt 更新為 now，useCount 於 transaction 內加一（不覆蓋為 1）。 */
    suspend fun recordUse(rawModelId: String, nowMillis: Long = 0L) {
        val key = canonicalKey(rawModelId)
        db.withTransaction {
            val existing = db.favoriteDao().recent(key)
            val next = existing?.copy(lastUsedAt = nowMillis, useCount = existing.useCount + 1)
                ?: RecentModelEntity(canonicalModelId = key, lastUsedAt = nowMillis, useCount = 1)
            db.favoriteDao().putRecent(next)
        }
    }

    suspend fun recentOf(rawModelId: String): RecentModelEntity? =
        db.favoriteDao().recent(canonicalKey(rawModelId))

    suspend fun clearRecent(): Int = db.favoriteDao().clearRecent()

    /** canonical 鍵：空白即拒絕（Unknown 不猜，不寫無意義列）。 */
    internal fun canonicalKey(rawModelId: String): String {
        val key = canonicalIdFor(rawModelId)
        require(key.isNotBlank()) { "model id must not be blank" }
        return key
    }
}
