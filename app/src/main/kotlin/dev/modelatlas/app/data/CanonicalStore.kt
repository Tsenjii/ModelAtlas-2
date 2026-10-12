package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.domain.MappingConfidence
import dev.modelatlas.core.domain.MappingSource
import dev.modelatlas.core.domain.Modality
import dev.modelatlas.core.domain.ModelCategory
import dev.modelatlas.core.domain.canonicalIdFor
import dev.modelatlas.core.domain.isManualMappingSource
import dev.modelatlas.core.domain.isOfficialMappingSource
import dev.modelatlas.core.domain.normalizeModelId
import dev.modelatlas.core.onboarding.ManualClassificationInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * MODEL-003 分類來源三槽：CATALOG（官方目錄）／OBSERVED（本地探測）／MANUAL（手填）。
 * 同一 canonical 按來源分列保存，confidence 分離，來源之間不互相冒充、不無聲覆蓋。
 */
object ClassificationSources {
    const val CATALOG = "CATALOG"
    const val OBSERVED = "OBSERVED"
    const val MANUAL = "MANUAL"

    val ALL: Set<String> = setOf(CATALOG, OBSERVED, MANUAL)
}

@Dao
interface CanonicalModelDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CanonicalModelEntity)

    @Query("SELECT * FROM canonical_models WHERE canonicalModelId = :id")
    suspend fun getById(id: String): CanonicalModelEntity?

    @Query("SELECT * FROM canonical_models WHERE normalizedName = :normalized")
    suspend fun getByNormalized(normalized: String): CanonicalModelEntity?
}

@Dao
interface ProviderModelAliasDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProviderModelAliasEntity)

    /** 同來源修正覆寫；跨來源覆寫由 [CanonicalStore.saveAlias] 先守衛，禁直接盲寫。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: ProviderModelAliasEntity)

    /**
     * MAJOR6 (a) 原子 CAS 寫入：僅當整列與預期 winner 一致才更新，否則回 0 由呼叫端重試。
     * 依賴 unique(providerId,providerModelId)＋PK aliasId；守衛仍在 Kotlin 層，SQL 只做併發檢測，不改變守衛語意。
     */
    @Query("UPDATE provider_model_aliases SET canonicalModelId = :canonicalModelId, source = :source, confidence = :confidence, verified = :verified, observedAt = :observedAt, version = :version, evidenceId = :evidenceId WHERE aliasId = :expectedAliasId AND source = :expectedSource AND canonicalModelId = :expectedCanonical AND confidence = :expectedConfidence AND verified = :expectedVerified AND observedAt = :expectedObservedAt AND version = :expectedVersion")
    suspend fun updateAliasIfUnchanged(
        canonicalModelId: String,
        source: String,
        confidence: String,
        verified: Boolean,
        observedAt: Long,
        version: Int,
        evidenceId: String?,
        expectedAliasId: String,
        expectedSource: String,
        expectedCanonical: String,
        expectedConfidence: String,
        expectedVerified: Boolean,
        expectedObservedAt: Long,
        expectedVersion: Int,
    ): Int

    @Query("SELECT * FROM provider_model_aliases WHERE providerId = :providerId AND providerModelId = :providerModelId")
    suspend fun get(providerId: String, providerModelId: String): ProviderModelAliasEntity?

    @Query("SELECT * FROM provider_model_aliases WHERE canonicalModelId = :canonicalModelId ORDER BY providerId ASC, providerModelId ASC")
    suspend fun byCanonical(canonicalModelId: String): List<ProviderModelAliasEntity>
}

@Dao
interface ModelClassificationDao {
    /** 同槽覆寫（同 canonical＋同來源修正）；跨槽各有其列。子表須先清再 REPLACE（FK RESTRICT）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ModelClassificationEntity)

    @Query("SELECT * FROM model_classifications WHERE canonicalModelId = :canonicalModelId AND source = :source")
    suspend fun get(canonicalModelId: String, source: String): ModelClassificationEntity?

    @Query("SELECT * FROM model_classifications WHERE canonicalModelId = :canonicalModelId ORDER BY source ASC")
    suspend fun byCanonical(canonicalModelId: String): List<ModelClassificationEntity>

    @Query("SELECT * FROM model_classifications WHERE canonicalModelId = :canonicalModelId AND source = :source")
    fun observe(canonicalModelId: String, source: String): Flow<ModelClassificationEntity?>
}

@Dao
interface ClassificationCapabilityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ClassificationCapabilityEntity>)

    @Query("DELETE FROM classification_capabilities WHERE classificationId = :classificationId")
    suspend fun deleteByClassification(classificationId: String): Int

    @Query("SELECT * FROM classification_capabilities WHERE classificationId = :classificationId ORDER BY capability ASC")
    suspend fun byClassification(classificationId: String): List<ClassificationCapabilityEntity>

    @Query("SELECT * FROM classification_capabilities WHERE classificationId = :classificationId ORDER BY capability ASC")
    fun observeByClassification(classificationId: String): Flow<List<ClassificationCapabilityEntity>>
}

@Dao
interface ClassificationModalityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ClassificationModalityEntity>)

    @Query("DELETE FROM classification_modalities WHERE classificationId = :classificationId")
    suspend fun deleteByClassification(classificationId: String): Int

    @Query("SELECT * FROM classification_modalities WHERE classificationId = :classificationId ORDER BY modality ASC")
    suspend fun byClassification(classificationId: String): List<ClassificationModalityEntity>

    @Query("SELECT * FROM classification_modalities WHERE classificationId = :classificationId ORDER BY modality ASC")
    fun observeByClassification(classificationId: String): Flow<List<ClassificationModalityEntity>>
}

@Dao
interface ClassificationEndpointTypeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ClassificationEndpointTypeEntity>)

    @Query("DELETE FROM classification_endpoint_types WHERE classificationId = :classificationId")
    suspend fun deleteByClassification(classificationId: String): Int

    @Query("SELECT * FROM classification_endpoint_types WHERE classificationId = :classificationId ORDER BY endpointType ASC")
    suspend fun byClassification(classificationId: String): List<ClassificationEndpointTypeEntity>

    @Query("SELECT * FROM classification_endpoint_types WHERE classificationId = :classificationId ORDER BY endpointType ASC")
    fun observeByClassification(classificationId: String): Flow<List<ClassificationEndpointTypeEntity>>
}

/** 分類列＋三組關聯的讀取投影（capabilities／modalities／endpointTypes 皆為 enum 名集合）。 */
data class ClassificationWithDetails(
    val classification: ModelClassificationEntity,
    val capabilities: Set<String> = emptySet(),
    val modalities: Set<String> = emptySet(),
    val endpointTypes: Set<String> = emptySet(),
)

/**
 * MODEL-001…003 canonical／alias／分類寫入＋查詢層（Room v7，BACKLOG F1）。
 *
 * - 只加表、不刪任何列；失敗保留 last-good（寫入皆在 transaction，衝突即整筆 rollback）。
 * - Unknown≠0／Free：未知分類以 null／空集合保存，不填零值。
 * - manual／official／observed／external 分欄不互冒：alias 同鍵跨來源不無聲覆蓋，
 *   manual（USER_OVERRIDE）恆 LOW，官方映射不被非官方降級。
 * - PricingScope 七維不動：canonicalId 永不併入 scope key。
 * - raw secret 絕不經此層（只傳 refs 與 enum 名）。
 */
class CanonicalStore(private val db: AtlasDatabase) {

    /**
     * MODEL-001：同一 canonical 不因不同 key 重複建立。
     * 先查 canonicalId 再查正規化名；命中任一即回傳既有。併發衝突回讀勝者，不拋。
     */
    suspend fun saveCanonical(
        rawModelId: String,
        displayName: String? = null,
        nowMillis: Long = 0L,
    ): CanonicalModelEntity = db.withTransaction {
        require(rawModelId.isNotBlank()) { "model id must not be blank" }
        val normalized = normalizeModelId(rawModelId)
        require(normalized.isNotBlank()) { "model id normalizes to blank" }
        val id = canonicalIdFor(rawModelId)
        db.canonicalModelDao().getById(id)?.let { return@withTransaction it }
        db.canonicalModelDao().getByNormalized(normalized)?.let { return@withTransaction it }
        val entity = CanonicalModelEntity(
            canonicalModelId = id,
            normalizedName = normalized,
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: rawModelId.trim(),
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        try {
            db.canonicalModelDao().insert(entity)
        } catch (_: SQLiteConstraintException) {
            return@withTransaction db.canonicalModelDao().getById(id)
                ?: db.canonicalModelDao().getByNormalized(normalized)
                ?: throw IllegalStateException("Canonical insert conflict without winner")
        }
        entity
    }

    /**
     * MODEL-002 alias 寫入（含 exact→alias→normalized 解析後的落庫）。
     * 冪等：完全一致重寫回傳既有；同來源視為修正可覆寫；
     * 官方映射不被非官方降級；跨來源不無聲覆蓋（官方確認升級除外）。
     *
     * MAJOR6 (a) TOCTOU 修復：依賴 DB 唯一鍵 unique(providerId,providerModelId)＋catch 重試。
     * 讀-guard-寫全在 withTransaction 內；insert 衝突在同一事務內重讀再 guard 再寫；
     * 已存在列的覆寫改用 CAS 條件 UPDATE（整列期望一致），0 列即併發搶寫→拋
     * SQLiteConstraintException 由外層新事務重試，確保併發跨源不雙雙通過守衛。
     * 守衛語意不變：official 不被 manual 降級、verified 僅 HIGH/MEDIUM、manual 恆 LOW。
     */
    suspend fun saveAlias(
        providerId: String,
        providerModelId: String,
        canonicalModelId: String,
        source: String,
        confidence: String,
        verified: Boolean = false,
        observedAt: Long = 0L,
        version: Int = 1,
    ): ProviderModelAliasEntity {
        require(providerId.isNotBlank() && providerModelId.isNotBlank() && canonicalModelId.isNotBlank()) {
            "alias ids must not be blank"
        }
        val mappingSource = enumValueOrThrow<MappingSource>(source, "Unknown mapping source")
        val mappingConfidence = enumValueOrThrow<MappingConfidence>(confidence, "Unknown mapping confidence")
        if (isManualMappingSource(mappingSource)) {
            require(mappingConfidence == MappingConfidence.LOW) { "Manual alias mapping must stay LOW" }
        }
        if (verified) {
            require(mappingConfidence == MappingConfidence.HIGH || mappingConfidence == MappingConfidence.MEDIUM) {
                "Only HIGH/MEDIUM mappings may be marked verified"
            }
        }
        val entity = ProviderModelAliasEntity(
            aliasId = aliasIdOf(providerId, providerModelId),
            providerId = providerId,
            providerModelId = providerModelId,
            canonicalModelId = canonicalModelId,
            source = mappingSource.name,
            confidence = mappingConfidence.name,
            verified = verified,
            observedAt = observedAt,
            version = version,
        )
        var attempts = 0
        while (true) {
            try {
                return db.withTransaction {
                    require(db.canonicalModelDao().getById(canonicalModelId) != null) { "Canonical model not found" }
                    val existing = db.providerModelAliasDao().get(providerId, providerModelId)
                    if (existing == null) {
                        try {
                            db.providerModelAliasDao().insert(entity)
                        } catch (_: SQLiteConstraintException) {
                            return@withTransaction guardedOverwrite(
                                db.providerModelAliasDao().get(providerId, providerModelId)
                                    ?: throw IllegalStateException("Alias insert conflict without winner"),
                                entity,
                            )
                        }
                        return@withTransaction entity
                    }
                    guardedOverwrite(existing, entity)
                }
            } catch (e: SQLiteConstraintException) {
                attempts++
                if (attempts >= 3) throw IllegalStateException("Alias concurrent conflict without winner", e)
            }
        }
    }

    private suspend fun guardedOverwrite(
        existing: ProviderModelAliasEntity,
        next: ProviderModelAliasEntity,
    ): ProviderModelAliasEntity {
        if (existing == next) return existing
        val existingSource = MappingSource.valueOf(existing.source)
        val nextSource = MappingSource.valueOf(next.source)
        if (isOfficialMappingSource(existingSource) && !isOfficialMappingSource(nextSource)) {
            throw IllegalArgumentException("Official alias mapping must not be overwritten by non-official source")
        }
        if (existing.source != next.source && !isOfficialMappingSource(nextSource)) {
            throw IllegalArgumentException("Conflicting alias mapping source must not silently overwrite")
        }
        val updated = db.providerModelAliasDao().updateAliasIfUnchanged(
            canonicalModelId = next.canonicalModelId,
            source = next.source,
            confidence = next.confidence,
            verified = next.verified,
            observedAt = next.observedAt,
            version = next.version,
            evidenceId = next.evidenceId,
            expectedAliasId = existing.aliasId,
            expectedSource = existing.source,
            expectedCanonical = existing.canonicalModelId,
            expectedConfidence = existing.confidence,
            expectedVerified = existing.verified,
            expectedObservedAt = existing.observedAt,
            expectedVersion = existing.version,
        )
        if (updated == 0) {
            throw SQLiteConstraintException("Alias concurrent modification, retry")
        }
        return next
    }

    /**
     * MODEL-003 分類寫入（含三關聯 upsert）。
     * 同槽（同 canonical＋同來源）覆寫冪等；跨槽各列獨立，confidence 分離。
     * primaryCategory 為 null 即未知，不填零值、不拿 OTHER 冒充。
     */
    suspend fun saveClassification(
        canonicalModelId: String,
        source: String,
        confidence: String,
        primaryCategory: String?,
        capabilities: Set<String> = emptySet(),
        modalities: Set<String> = emptySet(),
        endpointTypes: Set<String> = emptySet(),
        observedAt: Long = 0L,
        version: Int = 1,
    ): ModelClassificationEntity = db.withTransaction {
        require(canonicalModelId.isNotBlank()) { "canonicalModelId must not be blank" }
        require(source in ClassificationSources.ALL) { "Unknown classification source" }
        val mappingConfidence = enumValueOrThrow<MappingConfidence>(confidence, "Unknown classification confidence")
        if (source == ClassificationSources.MANUAL) {
            require(mappingConfidence == MappingConfidence.LOW) { "Manual classification must stay LOW" }
        }
        if (primaryCategory != null) enumValueOrThrow<ModelCategory>(primaryCategory, "Unknown primary category")
        capabilities.forEach { enumValueOrThrow<Capability>(it, "Unknown capability") }
        modalities.forEach { enumValueOrThrow<Modality>(it, "Unknown modality") }
        endpointTypes.forEach { enumValueOrThrow<EndpointType>(it, "Unknown endpoint type") }
        require(db.canonicalModelDao().getById(canonicalModelId) != null) { "Canonical model not found" }
        val id = classificationIdOf(canonicalModelId, source)
        db.classificationCapabilityDao().deleteByClassification(id)
        db.classificationModalityDao().deleteByClassification(id)
        db.classificationEndpointTypeDao().deleteByClassification(id)
        db.modelClassificationDao().upsert(
            ModelClassificationEntity(
                id = id,
                canonicalModelId = canonicalModelId,
                source = source,
                confidence = mappingConfidence.name,
                primaryCategory = primaryCategory,
                observedAt = observedAt,
                version = version,
            ),
        )
        if (capabilities.isNotEmpty()) {
            db.classificationCapabilityDao().insertAll(capabilities.map { ClassificationCapabilityEntity(id, it) })
        }
        if (modalities.isNotEmpty()) {
            db.classificationModalityDao().insertAll(modalities.map { ClassificationModalityEntity(id, it) })
        }
        if (endpointTypes.isNotEmpty()) {
            db.classificationEndpointTypeDao().insertAll(endpointTypes.map { ClassificationEndpointTypeEntity(id, it) })
        }
        db.modelClassificationDao().get(canonicalModelId, source)
            ?: throw IllegalStateException("Classification write did not persist")
    }

    /**
     * BACKLOG F1：snapshotJson→真表遷移函式。
     * 呼叫端先以 `decodeProviderManualConfig(snapshotJson).manualClassification` 取出手填，
     * 再傳入本函數落為 MANUAL／LOW 真列（含三關聯）。null 即無手填，不寫列、回 null。
     */
    suspend fun classificationFromManualSnapshot(
        canonicalModelId: String,
        input: ManualClassificationInput?,
        observedAt: Long = 0L,
    ): ModelClassificationEntity? {
        if (input == null) return null
        return saveClassification(
            canonicalModelId = canonicalModelId,
            source = ClassificationSources.MANUAL,
            confidence = MappingConfidence.LOW.name,
            primaryCategory = input.primaryCategory.name,
            capabilities = input.capabilities.map { it.name }.toSet(),
            modalities = input.modalities.map { it.name }.toSet(),
            endpointTypes = input.endpointTypes.map { it.name }.toSet(),
            observedAt = observedAt,
        )
    }

    suspend fun getClassification(
        canonicalModelId: String,
        source: String,
    ): ClassificationWithDetails? = db.withTransaction {
        val row = db.modelClassificationDao().get(canonicalModelId, source) ?: return@withTransaction null
        detailsOf(row.id, row)
    }

    suspend fun classificationsOf(canonicalModelId: String): List<ClassificationWithDetails> = db.withTransaction {
        db.modelClassificationDao().byCanonical(canonicalModelId).map { row -> detailsOf(row.id, row) }
    }

    /**
     * P7 repair（LMS-002 真 indices；追加只讀，不寫任何表、不改 Dao）。
     * `Daos.kt` 禁碰，故以 `openHelper` 原始查詢組裝 LMSpeed 映射三件套（DB 真值，
     * 非呼叫端傳空集合）：exact（原始字串完全命中）／normalized（正規化後命中）／
     * knownCanonicalIds（正規化名集合）。空庫即空集合（全 UNKNOWN，不猜、不失敗）。
     * Daos 增 list-all 方法屬他日重構，本輪以唯讀 SQL 滿足「建真 indices 再判定」。
     */
    suspend fun lmspeedMappingIndices(): LmspeedMappingIndices {
        val exact = LinkedHashMap<String, String>()
        val normalized = LinkedHashMap<String, String>()
        val known = LinkedHashSet<String>()
        db.openHelper.readableDatabase.query("SELECT canonicalModelId, normalizedName FROM canonical_models").use { c ->
            val idCol = c.getColumnIndex("canonicalModelId")
            val normCol = c.getColumnIndex("normalizedName")
            while (c.moveToNext()) {
                val id = if (idCol >= 0) c.getString(idCol).orEmpty() else ""
                val norm = if (normCol >= 0) c.getString(normCol).orEmpty() else ""
                if (id.isNotBlank()) known.add(id)
                if (norm.isNotBlank()) known.add(norm)
            }
        }
        db.openHelper.readableDatabase.query(
            "SELECT providerModelId, canonicalModelId FROM provider_model_aliases",
        ).use { c ->
            val modelCol = c.getColumnIndex("providerModelId")
            val canonCol = c.getColumnIndex("canonicalModelId")
            while (c.moveToNext()) {
                val model = if (modelCol >= 0) c.getString(modelCol).orEmpty() else ""
                val canon = if (canonCol >= 0) c.getString(canonCol).orEmpty() else ""
                if (model.isBlank() || canon.isBlank()) continue
                exact.putIfAbsent(model, canon)
                val norm = normalizeModelId(model)
                if (norm.isNotBlank()) normalized.putIfAbsent(norm, canon)
            }
        }
        return LmspeedMappingIndices(exact, normalized, known)
    }

    private suspend fun detailsOf(
        classificationId: String,
        row: ModelClassificationEntity,
    ): ClassificationWithDetails = ClassificationWithDetails(
        classification = row,
        capabilities = db.classificationCapabilityDao().byClassification(classificationId).map { it.capability }.toSet(),
        modalities = db.classificationModalityDao().byClassification(classificationId).map { it.modality }.toSet(),
        endpointTypes = db.classificationEndpointTypeDao().byClassification(classificationId).map { it.endpointType }.toSet(),
    )

    /**
     * 分類 Flow 查詢（單槽）：列缺席即發 null，不猜。
     * 探測寫入走 `saveClassification(source=OBSERVED)`，不在此旁路寫入。
     */
    fun observeClassification(
        canonicalModelId: String,
        source: String,
    ): Flow<ClassificationWithDetails?> =
        db.modelClassificationDao().observe(canonicalModelId, source).flatMapLatest { row ->
            if (row == null) {
                flowOf(null)
            } else {
                combine(
                    db.classificationCapabilityDao().observeByClassification(row.id),
                    db.classificationModalityDao().observeByClassification(row.id),
                    db.classificationEndpointTypeDao().observeByClassification(row.id),
                ) { caps, mods, eps ->
                    ClassificationWithDetails(
                        classification = row,
                        capabilities = caps.map { it.capability }.toSet(),
                        modalities = mods.map { it.modality }.toSet(),
                        endpointTypes = eps.map { it.endpointType }.toSet(),
                    )
                }
            }
        }
}

/** aliasId 決定性編碼（長度前綴，provider／model 含分隔符也不碰撞）。 */
internal fun aliasIdOf(providerId: String, providerModelId: String): String =
    "alias:${providerId.length}:$providerId:${providerModelId.length}:$providerModelId"

/** P7 repair：LMSpeed 映射真 indices 三件套（DB 真值；空即全 UNKNOWN）。 */
data class LmspeedMappingIndices(
    val exactAliasIndex: Map<String, String> = emptyMap(),
    val normalizedAliasIndex: Map<String, String> = emptyMap(),
    val knownCanonicalIds: Set<String> = emptySet(),
)

/** 同 canonical 同來源恰一列；跨來源各有其列（confidence 分離）。 */
internal fun classificationIdOf(canonicalModelId: String, source: String): String =
    "classification:$canonicalModelId:$source"

private inline fun <reified T : Enum<T>> enumValueOrThrow(name: String, message: String): T =
    runCatching { enumValueOf<T>(name) }.getOrElse { throw IllegalArgumentException(message) }
