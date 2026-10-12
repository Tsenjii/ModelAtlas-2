package dev.modelatlas.app.onboarding

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.modelatlas.app.data.ensureSnapshotJsonHasNoSecretRefs
import dev.modelatlas.core.onboarding.batch.BatchSnapshot
import dev.modelatlas.core.onboarding.batch.BatchStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Entity(tableName = "onboarding_batches")
data class BatchSnapshotEntity(
    @PrimaryKey val batchId: String,
    val updatedAt: Long,
    val snapshotJson: String,
)

@Dao
interface BatchSnapshotDao {
    @Query("SELECT * FROM onboarding_batches WHERE batchId = :batchId")
    suspend fun get(batchId: String): BatchSnapshotEntity?

    @Query("SELECT batchId FROM onboarding_batches ORDER BY updatedAt DESC")
    suspend fun ids(): List<String>

    @Query("SELECT * FROM onboarding_batches ORDER BY updatedAt DESC")
    fun observe(): Flow<List<BatchSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: BatchSnapshotEntity)
}

/** 每個排程轉移以 Room 單列原子保存；只存通過 parser 的非機密 draft。 */
class RoomBatchStateStore(private val dao: BatchSnapshotDao) : BatchStateStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val snapshots: Flow<List<BatchSnapshot>> = dao.observe().map { rows -> rows.map(::decode) }

    override suspend fun load(batchId: String): BatchSnapshot? = dao.get(batchId)?.let(::decode)
    override suspend fun listBatchIds(): List<String> = dao.ids()
    override suspend fun save(snapshot: BatchSnapshot) {
        val encoded = json.encodeToString(BatchSnapshot.serializer(), snapshot)
        ensureSnapshotJsonHasNoSecretRefs(encoded)
        dao.save(BatchSnapshotEntity(snapshot.batchId, snapshot.updatedAtMillis, encoded))
    }

    private fun decode(row: BatchSnapshotEntity): BatchSnapshot = try {
        json.decodeFromString(BatchSnapshot.serializer(), row.snapshotJson).also { require(it.batchId == row.batchId) }
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException("批次資料無法解析")
    }
}
