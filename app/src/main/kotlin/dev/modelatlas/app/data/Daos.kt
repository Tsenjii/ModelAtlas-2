package dev.modelatlas.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProviderEntity)
    @Query("SELECT * FROM providers WHERE providerId = :providerId")
    suspend fun getById(providerId: String): ProviderEntity?
    @Query("UPDATE providers SET checkinUrl = :url, requiresDailyCheckin = :requires WHERE providerId = :providerId")
    suspend fun updateCheckinConfig(providerId: String, url: String?, requires: Boolean): Int

    /**
     * PROV-006／§11 observed health 快照寫回：只寫 observed 欄與其觀測時間，
     * 永不觸碰 officialHealth（official／observed 分離）。UNKNOWN 由呼叫端決定是否寫回，
     * 一次失敗不得清空 last-good（呼叫端只在新鮮聚合非 UNKNOWN 時呼叫）。
     */
    @Query("UPDATE providers SET observedHealth = :health, observedHealthAt = :observedAt WHERE providerId = :providerId")
    suspend fun updateObservedHealth(providerId: String, health: String, observedAt: Long): Int

    @Query("SELECT * FROM providers ORDER BY providerId ASC")
    suspend fun all(): List<ProviderEntity>
}

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AccountEntity)
    @Query("SELECT * FROM accounts WHERE accountId = :accountId")
    suspend fun getById(accountId: String): AccountEntity?
    @Query("SELECT * FROM accounts WHERE providerId = :providerId")
    suspend fun byProvider(providerId: String): List<AccountEntity>
}

@Dao
interface CredentialDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CredentialEntity)
    @Query("SELECT * FROM credentials WHERE credentialId = :credentialId")
    suspend fun getById(credentialId: String): CredentialEntity?
    @Query("UPDATE credentials SET enabled = :enabled WHERE credentialId = :credentialId")
    suspend fun setEnabled(credentialId: String, enabled: Boolean): Int
    @Query("DELETE FROM credentials WHERE credentialId = :credentialId")
    suspend fun deleteById(credentialId: String): Int

    @Query("SELECT * FROM credentials WHERE accountId = :accountId ORDER BY priority DESC, credentialId ASC")
    suspend fun byAccount(accountId: String): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE credentialId IN (:credentialIds) ORDER BY priority DESC, credentialId ASC")
    suspend fun byIds(credentialIds: List<String>): List<CredentialEntity>

    /** PROV-006 批次 enable／disable：一次寫入，回受影響列數；不回報個別 secret。 */
    @Query("UPDATE credentials SET enabled = :enabled WHERE credentialId IN (:credentialIds)")
    suspend fun setEnabledBatch(credentialIds: List<String>, enabled: Boolean): Int

    /**
     * PROV-006／PROV-009 credential search／filter：
     * 每個參數為 null 即該維度不篩（不猜）；query 對 label／note 做 LIKE 子字串比對。
     * 只回 metadata，永不回 secret 原始值（本表本就不存）。
     */
    @Query(
        "SELECT c.* FROM credentials AS c " +
            "INNER JOIN accounts AS a ON a.accountId = c.accountId " +
            "WHERE (:providerId IS NULL OR a.providerId = :providerId) " +
            "AND (:accountId IS NULL OR c.accountId = :accountId) " +
            "AND (:enabled IS NULL OR c.enabled = :enabled) " +
            "AND (:group IS NULL OR c.`group` = :group) " +
            "AND (:authMode IS NULL OR c.authMode = :authMode) " +
            "AND (:query IS NULL OR c.label LIKE '%' || :query || '%' OR c.note LIKE '%' || :query || '%') " +
            "ORDER BY c.priority DESC, c.credentialId ASC",
    )
    suspend fun search(
        providerId: String?,
        accountId: String?,
        enabled: Boolean?,
        group: String?,
        authMode: String?,
        query: String?,
    ): List<CredentialEntity>
}

@Dao
interface EndpointDao {
    @Query("SELECT * FROM endpoints WHERE providerId = :providerId ORDER BY endpointId")
    suspend fun byProvider(providerId: String): List<EndpointEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EndpointEntity)

    @Query("SELECT * FROM endpoints WHERE endpointId = :endpointId")
    suspend fun getById(endpointId: String): EndpointEntity?
}

@Dao
interface OfferingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OfferingEntity)

    @Query("SELECT * FROM offerings WHERE id = :id")
    suspend fun getById(id: String): OfferingEntity?

    /** 同步範圍組裝用：列出該 provider 全部 offering；缺 offering 即缺 scope，呼叫端拒絕同步。 */
    @Query("SELECT * FROM offerings WHERE providerId = :providerId ORDER BY id")
    suspend fun byProvider(providerId: String): List<OfferingEntity>
}

/**
 * CR-003（B3，Room v14）：`insert` 公開入口一律把 `groupIdentity` 正規化成
 * `availabilityGroupIdentity(entity.group)`，防止 `copy(group = …)` 之類的修改沿用
 * 舊 identity key 造成 UNIQUE 錯位／scope 混寫；`group` 原值（含 null）不改。
 * 重複 exact scope（含 identity）走 `ABORT`，不做 REPLACE／destructive。
 * `byExactScope` 以正規化 identity 精確讀取，null 與空字串各自獨立、不當 wildcard。
 */
@Dao
abstract class AvailabilityDao {
    suspend fun insert(entity: CredentialAvailabilityEntity) {
        insertRow(entity.copy(groupIdentity = availabilityGroupIdentity(entity.group)))
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRow(entity: CredentialAvailabilityEntity)

    @Query("SELECT * FROM credential_availability WHERE id = :id")
    abstract suspend fun getById(id: String): CredentialAvailabilityEntity?
    @Query("SELECT * FROM credential_availability WHERE credentialId = :credentialId")
    abstract suspend fun byCredential(credentialId: String): List<CredentialAvailabilityEntity>
    @Query("DELETE FROM credential_availability WHERE id = :id")
    abstract suspend fun deleteById(id: String): Int
    @Query("DELETE FROM credential_availability WHERE credentialId = :credentialId")
    abstract suspend fun deleteByCredential(credentialId: String): Int

    suspend fun byExactScope(
        offeringId: String,
        accountId: String,
        credentialId: String,
        group: String?,
    ): List<CredentialAvailabilityEntity> =
        byExactScopeIdentity(offeringId, accountId, credentialId, availabilityGroupIdentity(group))

    @Query(
        "SELECT * FROM credential_availability WHERE offeringId = :offeringId " +
            "AND accountId = :accountId AND credentialId = :credentialId " +
            "AND groupIdentity = :groupIdentity",
    )
    protected abstract suspend fun byExactScopeIdentity(
        offeringId: String,
        accountId: String,
        credentialId: String,
        groupIdentity: String,
    ): List<CredentialAvailabilityEntity>
}

@Dao
interface ConnectionDao {
    /**
     * 推理用投影：availability（enabled 憑證）UNION 匿名 offering-direct。
     * 匿名僅 `provider_configurations.authMode='NONE'` 的已 commit offering×account，
     * `credentialId` 以空字串投影缺值（不寫 credentials 表），`anonymous=1`，
     * `priceState/quotaState` 固定 `UNKNOWN`（手填 LOW 不寫成官方價）。
     * 刪 key 後不退化：非 NONE provider 刪光 availability 即無列，不出現匿名列。
     */
    @Query(
        "SELECT v.id AS id, v.offeringId AS offeringId, o.providerId AS providerId, v.accountId AS accountId, " +
            "v.credentialId AS credentialId, o.endpointId AS endpointId, " +
            "p.name AS name, e.baseUrl AS baseUrl, e.endpointUrl AS endpointUrl, " +
            "o.modelId AS modelId, c.label AS credentialLabel, " +
            "v.priceState AS priceState, v.quotaState AS quotaState, " +
            "p.officialHealth AS officialHealth, p.observedHealth AS observedHealth, " +
            "p.checkinUrl AS checkinUrl, p.requiresDailyCheckin AS requiresDailyCheckin, " +
            "c.enabled AS credentialEnabled, 0 AS anonymous, e.protocol AS protocol, o.manual AS manual, " +
            "pa.canonicalModelId AS canonicalModelId, " +
            "CASE WHEN mc_cat.id IS NOT NULL THEN 'CATALOG' WHEN mc_obs.id IS NOT NULL THEN 'OBSERVED' WHEN mc_man.id IS NOT NULL THEN 'MANUAL' ELSE NULL END AS classificationSource, " +
            "COALESCE(mc_cat.confidence, mc_obs.confidence, mc_man.confidence) AS classificationConfidence, " +
            "COALESCE(mc_cat.primaryCategory, mc_obs.primaryCategory, mc_man.primaryCategory) AS primaryCategory " +
            "FROM credential_availability AS v " +
            "INNER JOIN offerings AS o ON o.id = v.offeringId AND o.hidden = 0 " +
            "INNER JOIN providers AS p ON p.providerId = o.providerId " +
            "INNER JOIN endpoints AS e ON e.endpointId = o.endpointId " +
            "INNER JOIN credentials AS c ON c.credentialId = v.credentialId AND c.enabled = 1 " +
            "LEFT JOIN provider_model_aliases AS pa ON pa.providerId = o.providerId AND pa.providerModelId = o.modelId " +
            "LEFT JOIN model_classifications AS mc_cat ON mc_cat.canonicalModelId = pa.canonicalModelId AND mc_cat.source = 'CATALOG' " +
            "LEFT JOIN model_classifications AS mc_obs ON mc_obs.canonicalModelId = pa.canonicalModelId AND mc_obs.source = 'OBSERVED' " +
            "LEFT JOIN model_classifications AS mc_man ON mc_man.canonicalModelId = pa.canonicalModelId AND mc_man.source = 'MANUAL' " +
            "UNION ALL " +
            "SELECT ('anon:' || o.id || ':' || a.accountId) AS id, o.id AS offeringId, o.providerId AS providerId, a.accountId AS accountId, " +
            "'' AS credentialId, o.endpointId AS endpointId, " +
            "p.name AS name, e.baseUrl AS baseUrl, e.endpointUrl AS endpointUrl, " +
            "o.modelId AS modelId, '' AS credentialLabel, " +
            "'UNKNOWN' AS priceState, 'UNKNOWN' AS quotaState, " +
            "p.officialHealth AS officialHealth, p.observedHealth AS observedHealth, " +
            "p.checkinUrl AS checkinUrl, p.requiresDailyCheckin AS requiresDailyCheckin, " +
            "1 AS credentialEnabled, 1 AS anonymous, e.protocol AS protocol, o.manual AS manual, " +
            "pa.canonicalModelId AS canonicalModelId, " +
            "CASE WHEN mc_cat.id IS NOT NULL THEN 'CATALOG' WHEN mc_obs.id IS NOT NULL THEN 'OBSERVED' WHEN mc_man.id IS NOT NULL THEN 'MANUAL' ELSE NULL END AS classificationSource, " +
            "COALESCE(mc_cat.confidence, mc_obs.confidence, mc_man.confidence) AS classificationConfidence, " +
            "COALESCE(mc_cat.primaryCategory, mc_obs.primaryCategory, mc_man.primaryCategory) AS primaryCategory " +
            "FROM offerings AS o " +
            "INNER JOIN accounts AS a ON a.providerId = o.providerId AND o.hidden = 0 " +
            "INNER JOIN providers AS p ON p.providerId = o.providerId " +
            "INNER JOIN endpoints AS e ON e.endpointId = o.endpointId " +
            "INNER JOIN provider_configurations AS cfg ON cfg.providerId = o.providerId AND cfg.authMode = 'NONE' " +
            "LEFT JOIN provider_model_aliases AS pa ON pa.providerId = o.providerId AND pa.providerModelId = o.modelId " +
            "LEFT JOIN model_classifications AS mc_cat ON mc_cat.canonicalModelId = pa.canonicalModelId AND mc_cat.source = 'CATALOG' " +
            "LEFT JOIN model_classifications AS mc_obs ON mc_obs.canonicalModelId = pa.canonicalModelId AND mc_obs.source = 'OBSERVED' " +
            "LEFT JOIN model_classifications AS mc_man ON mc_man.canonicalModelId = pa.canonicalModelId AND mc_man.source = 'MANUAL' " +
            "ORDER BY id ASC",
    )
    fun observe(): Flow<List<ConnectionRecord>>
    /** 管理用投影：同上 UNION，availability 分支含 disabled 憑證（匿名分支兩者一致）。 */
    @Query(
        "SELECT v.id AS id, v.offeringId AS offeringId, o.providerId AS providerId, v.accountId AS accountId, " +
            "v.credentialId AS credentialId, o.endpointId AS endpointId, " +
            "p.name AS name, e.baseUrl AS baseUrl, e.endpointUrl AS endpointUrl, " +
            "o.modelId AS modelId, c.label AS credentialLabel, " +
            "v.priceState AS priceState, v.quotaState AS quotaState, " +
            "p.officialHealth AS officialHealth, p.observedHealth AS observedHealth, " +
            "p.checkinUrl AS checkinUrl, p.requiresDailyCheckin AS requiresDailyCheckin, " +
            "c.enabled AS credentialEnabled, 0 AS anonymous, e.protocol AS protocol, o.manual AS manual, " +
            "pa.canonicalModelId AS canonicalModelId, " +
            "CASE WHEN mc_cat.id IS NOT NULL THEN 'CATALOG' WHEN mc_obs.id IS NOT NULL THEN 'OBSERVED' WHEN mc_man.id IS NOT NULL THEN 'MANUAL' ELSE NULL END AS classificationSource, " +
            "COALESCE(mc_cat.confidence, mc_obs.confidence, mc_man.confidence) AS classificationConfidence, " +
            "COALESCE(mc_cat.primaryCategory, mc_obs.primaryCategory, mc_man.primaryCategory) AS primaryCategory " +
            "FROM credential_availability AS v " +
            "INNER JOIN offerings AS o ON o.id = v.offeringId AND o.hidden = 0 " +
            "INNER JOIN providers AS p ON p.providerId = o.providerId " +
            "INNER JOIN endpoints AS e ON e.endpointId = o.endpointId " +
            "INNER JOIN credentials AS c ON c.credentialId = v.credentialId " +
            "LEFT JOIN provider_model_aliases AS pa ON pa.providerId = o.providerId AND pa.providerModelId = o.modelId " +
            "LEFT JOIN model_classifications AS mc_cat ON mc_cat.canonicalModelId = pa.canonicalModelId AND mc_cat.source = 'CATALOG' " +
            "LEFT JOIN model_classifications AS mc_obs ON mc_obs.canonicalModelId = pa.canonicalModelId AND mc_obs.source = 'OBSERVED' " +
            "LEFT JOIN model_classifications AS mc_man ON mc_man.canonicalModelId = pa.canonicalModelId AND mc_man.source = 'MANUAL' " +
            "UNION ALL " +
            "SELECT ('anon:' || o.id || ':' || a.accountId) AS id, o.id AS offeringId, o.providerId AS providerId, a.accountId AS accountId, " +
            "'' AS credentialId, o.endpointId AS endpointId, " +
            "p.name AS name, e.baseUrl AS baseUrl, e.endpointUrl AS endpointUrl, " +
            "o.modelId AS modelId, '' AS credentialLabel, " +
            "'UNKNOWN' AS priceState, 'UNKNOWN' AS quotaState, " +
            "p.officialHealth AS officialHealth, p.observedHealth AS observedHealth, " +
            "p.checkinUrl AS checkinUrl, p.requiresDailyCheckin AS requiresDailyCheckin, " +
            "1 AS credentialEnabled, 1 AS anonymous, e.protocol AS protocol, o.manual AS manual, " +
            "pa.canonicalModelId AS canonicalModelId, " +
            "CASE WHEN mc_cat.id IS NOT NULL THEN 'CATALOG' WHEN mc_obs.id IS NOT NULL THEN 'OBSERVED' WHEN mc_man.id IS NOT NULL THEN 'MANUAL' ELSE NULL END AS classificationSource, " +
            "COALESCE(mc_cat.confidence, mc_obs.confidence, mc_man.confidence) AS classificationConfidence, " +
            "COALESCE(mc_cat.primaryCategory, mc_obs.primaryCategory, mc_man.primaryCategory) AS primaryCategory " +
            "FROM offerings AS o " +
            "INNER JOIN accounts AS a ON a.providerId = o.providerId AND o.hidden = 0 " +
            "INNER JOIN providers AS p ON p.providerId = o.providerId " +
            "INNER JOIN endpoints AS e ON e.endpointId = o.endpointId " +
            "INNER JOIN provider_configurations AS cfg ON cfg.providerId = o.providerId AND cfg.authMode = 'NONE' " +
            "LEFT JOIN provider_model_aliases AS pa ON pa.providerId = o.providerId AND pa.providerModelId = o.modelId " +
            "LEFT JOIN model_classifications AS mc_cat ON mc_cat.canonicalModelId = pa.canonicalModelId AND mc_cat.source = 'CATALOG' " +
            "LEFT JOIN model_classifications AS mc_obs ON mc_obs.canonicalModelId = pa.canonicalModelId AND mc_obs.source = 'OBSERVED' " +
            "LEFT JOIN model_classifications AS mc_man ON mc_man.canonicalModelId = pa.canonicalModelId AND mc_man.source = 'MANUAL' " +
            "ORDER BY id ASC",
    )
    fun observeManagement(): Flow<List<ConnectionRecord>>
}

@Dao
interface SampleDao {
    /** Indexed evidence join proves which custom-only anchor authorized each exact sample. */
    @Query(
        "SELECT DISTINCT s.endpointId, s.modelId AS providerModelId FROM samples AS s " +
            "INNER JOIN evidence AS b ON b.id = ('manual-scope:' || length(s.id) || ':' || s.id || ':' || length(:credentialId) || ':' || :credentialId) " +
            "WHERE s.providerId = :providerId AND s.accountId = :accountId AND s.endpointId = :endpointId " +
            "AND s.operation = :operation AND s.`group` IS :group AND s.safeCredentialId = '' " +
            "AND s.outcome = 'SUCCESS' AND s.source = 'MANUAL_TEST' AND s.completedAt IS NOT NULL " +
            "AND b.requestId = ('manual-scope:' || s.id) AND b.providerId = s.providerId AND b.modelId = s.modelId " +
            "AND b.outcome = 'SUCCESS' AND b.source = 'MANUAL_TEST'",
    )
    suspend fun customAnchorVerifiedModels(
        providerId: String, accountId: String, credentialId: String,
        endpointId: String, operation: String, group: String?,
    ): List<ScopedVerifiedModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SampleEntity)

    @Query("SELECT * FROM samples ORDER BY startedAt DESC LIMIT 100")
    fun observeLatest(): Flow<List<SampleEntity>>

    @Query("SELECT * FROM samples WHERE outcome = 'RUNNING'")
    suspend fun running(): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE id = :id")
    suspend fun getById(id: String): SampleEntity?

    @Query("SELECT COUNT(*) FROM samples WHERE id = :id")
    suspend fun countById(id: String): Int

    /**
     * §11 health rollup 聚合查詢：只讀 samples（本地觀測），official health 永不經此。
     * `decided`＝非 RUNNING 且非 CANCELLED（User Stop/Cancel 分子分母全排除，Contract §4.2 / PERF-002；
     * cf. D-009 My Uptime analog）；total 仍為原始請求數（含 CANCELLED/RUNNING）；429 與三個 AVG 同用
     * decided base；INTERRUPTED 保留在分母（進程被殺的真實歷史 ≠ user cancel）。
     * sinceMillis=0 表全期；AVG 對未完成／null 自動忽略，缺席即 null（不補零）。
     */
    @Query(
        "SELECT providerId AS providerId, modelId AS modelId, " +
            "COUNT(*) AS total, " +
            "SUM(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' THEN 1 ELSE 0 END) AS decided, " +
            "SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) AS successes, " +
            "SUM(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' AND httpStatus = 429 THEN 1 ELSE 0 END) AS rateLimited, " +
            "AVG(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' THEN ttftMs END) AS avgTtftMs, " +
            "AVG(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' THEN tps END) AS avgTps, " +
            "AVG(CASE WHEN outcome != 'RUNNING' AND outcome != 'CANCELLED' THEN totalLatencyMs END) AS avgLatencyMs, " +
            "MAX(startedAt) AS lastObservedAt " +
            "FROM samples WHERE providerId = :providerId AND startedAt >= :sinceMillis " +
            "GROUP BY providerId, modelId ORDER BY modelId ASC",
    )
    suspend fun healthAggregates(providerId: String, sinceMillis: Long): List<HealthAggregateRow>

    /**
     * MYM-005 My Uptime windowed-samples 查詢（Contract §23.4／§23.5；D-009）：
     * 全 scope 精確過濾（含 endpoint；`group` 以 `IS` 精確比對，null 只等於 NULL，
     * 不當萬用字元）＋半開時間窗 [startInclusive, endExclusive)（落 start 計、落 end 不計）。
     * CANCELLED 分子分母全排除（SQL 層 `decided` 概念，不回 CANCELLED 列；
     * INTERRUPTED 保留，不在排除範圍）。只讀 samples（本地觀測），不回任何 secret。
     */
    @Query(
        "SELECT * FROM samples WHERE providerId = :providerId AND accountId = :accountId " +
            "AND safeCredentialId = :safeCredentialId AND modelId = :modelId AND endpointId = :endpointId " +
            "AND `group` IS :group " +
            "AND startedAt >= :startInclusive AND startedAt < :endExclusive " +
            "AND outcome != 'CANCELLED' " +
            "ORDER BY startedAt ASC",
    )
    suspend fun myUptimeSamples(
        providerId: String,
        accountId: String,
        safeCredentialId: String,
        modelId: String,
        endpointId: String,
        group: String?,
        startInclusive: Long,
        endExclusive: Long,
    ): List<SampleEntity>

    /**
     * CR-006（D1／H1；PRODUCT_SPEC §6／§11；Contract §23.3）：驗證鍵獨立精確查詢，
     * 與既有 custom-anchor join（[customAnchorVerifiedModels]）及 bounded history
     * **分離**：無 LIMIT／ORDER（只回 DISTINCT endpoint/model keys，不回完整樣本
     * 歷史；不吃 display depth 截斷，超出最新 200 筆的舊成功樣本永遠回得來）。
     * 謂詞與 ProviderDetailLoader.verifiedKeysOf 逐維一致：同 provider／account／
     * credential／endpoint／operation／group（`group` 以 `IS` 精確比對，null 只等於
     * NULL，不當萬用字元）＋`source='MANUAL_TEST'`＋`outcome='SUCCESS'`＋
     * `completedAt` 非 null；SQL `TRIM` guard 先濾空白 modelId（SQLite TRIM 只去
     * 0x20，呼叫端仍保留 Kotlin trim/blank 再過濾作 defense-in-depth）。
     * 只回 endpointId＋providerModelId safe 投影，永不回 secret；輸出為驗證事實，
     * 與 display history cap 分離。
     */
    @Query(
        "SELECT DISTINCT endpointId, modelId AS providerModelId FROM samples " +
            "WHERE providerId = :providerId AND accountId = :accountId " +
            "AND safeCredentialId = :credentialId AND endpointId = :endpointId " +
            "AND operation = :operation AND `group` IS :group " +
            "AND source = 'MANUAL_TEST' AND outcome = 'SUCCESS' AND completedAt IS NOT NULL " +
            "AND modelId != '' AND modelId = TRIM(modelId)",
    )
    suspend fun verifiedManualKeys(
        providerId: String,
        accountId: String,
        credentialId: String,
        endpointId: String,
        operation: String,
        group: String?,
    ): List<ScopedVerifiedModel>

    /**
     * CR-006（D3 顯示面；H1）：該 provider 最新 finals（`completedAt` 非 null），
     * `ORDER BY startedAt DESC, id ASC` 穩定取最新子集（同 startedAt 時以 id ASC
     * 決定性排序）；`LIMIT` 只截顯示面，歷史永不刪除。display-only：輸出**永不
     * 流入驗證**（驗證走 [verifiedManualKeys] 獨立 uncapped 查詢）。
     */
    @Query(
        "SELECT * FROM samples WHERE providerId = :providerId AND completedAt IS NOT NULL " +
            "ORDER BY startedAt DESC, id ASC LIMIT :limit",
    )
    suspend fun recentFinalsByProvider(providerId: String, limit: Int): List<SampleEntity>

    /**
     * CR-006（D2／D3）：該 provider 內精確 `modelId IN` 的最新 finals（display-only）。
     * model query 精確 provider+IN——另一 provider 的同名字串不得混入；呼叫端必須
     * 分塊（<=400 binds）且空清單先回空（不進 DB）；canonical 腿走
     * [recentFinalsForCanonical] 獨立查詢，呼叫端合併去重，不把 OR 分塊混在一起。
     * `ORDER BY startedAt DESC, id ASC` 穩定取最新子集；`LIMIT` 只截顯示面，
     * 永不流入驗證。
     */
    @Query(
        "SELECT * FROM samples WHERE providerId = :providerId AND modelId IN (:modelIds) " +
            "AND completedAt IS NOT NULL ORDER BY startedAt DESC, id ASC LIMIT :limit",
    )
    suspend fun recentFinalsForProviderModels(
        providerId: String,
        modelIds: List<String>,
        limit: Int,
    ): List<SampleEntity>

    /**
     * CR-006（D2）：精確 provider+`canonicalModelId` 的最新 finals（canonical 腿
     * 獨立查詢，display-only）。`canonicalModelId = :canonicalId` 精確相等：NULL
     * 列永不匹配，canonical 不當萬用字元、不跨 provider。`ORDER BY startedAt DESC,
     * id ASC` 穩定取最新子集；`LIMIT` 只截顯示面，永不流入驗證。
     */
    @Query(
        "SELECT * FROM samples WHERE providerId = :providerId AND canonicalModelId = :canonicalId " +
            "AND completedAt IS NOT NULL ORDER BY startedAt DESC, id ASC LIMIT :limit",
    )
    suspend fun recentFinalsForCanonical(
        providerId: String,
        canonicalId: String,
        limit: Int,
    ): List<SampleEntity>
}

@Dao
interface EvidenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EvidenceEntity)

    @Query("SELECT * FROM evidence WHERE id = :id")
    suspend fun getById(id: String): EvidenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EvidenceEntity)

    @Query("SELECT * FROM evidence WHERE requestId = :requestId")
    suspend fun byRequest(requestId: String): EvidenceEntity?

    /**
     * CR-006（C5 facts history）：批次 provenance 讀取（`snapshot_facts` 無 source 欄，
     * [EvidenceEntity.source] 是唯一來源）。呼叫端以 bounded 查詢回傳的 evidenceId
     * 分塊（<=400 binds）呼叫；exact `id IN`，永不整表掃描、不跨 evidence 混寫。
     */
    @Query("SELECT * FROM evidence WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<EvidenceEntity>
}

@Dao
interface CheckinDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CheckinEntity)

    @Query("SELECT * FROM checkins ORDER BY date DESC")
    fun observe(): Flow<List<CheckinEntity>>

    @Query("SELECT * FROM checkins WHERE providerId = :providerId AND date = :date AND accountId = :accountId")
    suspend fun get(providerId: String, date: String, accountId: String = ""): CheckinEntity?
    @Query("SELECT * FROM checkins WHERE providerId = :providerId AND date = :date")
    suspend fun listByProviderDate(providerId: String, date: String): List<CheckinEntity>
}

@Dao
interface PriceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PriceSnapshotEntity)

    @Query("SELECT * FROM price_snapshots WHERE id = :id")
    suspend fun getById(id: String): PriceSnapshotEntity?
}

@Dao
interface QuotaDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: QuotaSnapshotEntity)

    @Query("SELECT * FROM quota_snapshots WHERE id = :id")
    suspend fun getById(id: String): QuotaSnapshotEntity?
}

/**
 * X-018 ProviderNotice DAO（Room v10；TRACEABILITY X-018／PRODUCT_SPEC §10／§11／§14）。
 * append-only：[insert] 以 ABORT，歷史永不覆寫；查詢一律精確 providerId（X-004）。
 */
@Dao
interface ProviderNoticeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProviderNoticeEntity)

    @Query("SELECT * FROM provider_notices WHERE noticeId = :noticeId")
    suspend fun getById(noticeId: String): ProviderNoticeEntity?

    @Query("SELECT * FROM provider_notices WHERE providerId = :providerId ORDER BY observedAt DESC, noticeId ASC")
    suspend fun byProvider(providerId: String): List<ProviderNoticeEntity>

    @Query("SELECT COUNT(*) FROM provider_notices")
    suspend fun count(): Int
}

/**
 * X-018 ProviderEvent DAO（Room v10；同上）。
 * append-only ABORT；OUTAGE／RECOVERY／NOTICE 由 [EventStore] 校验後寫入。
 */
@Dao
interface ProviderEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProviderEventEntity)

    @Query("SELECT * FROM provider_events WHERE eventId = :eventId")
    suspend fun getById(eventId: String): ProviderEventEntity?

    @Query("SELECT * FROM provider_events WHERE providerId = :providerId ORDER BY observedAt DESC, eventId ASC")
    suspend fun byProvider(providerId: String): List<ProviderEventEntity>

    @Query("SELECT * FROM provider_events WHERE providerId = :providerId AND kind = :kind ORDER BY observedAt DESC, eventId ASC")
    suspend fun byProviderKind(providerId: String, kind: String): List<ProviderEventEntity>

    @Query("SELECT COUNT(*) FROM provider_events")
    suspend fun count(): Int
}

/**
 * X-018 PriceEvent DAO（Room v10；PRODUCT_SPEC §10 七維 scope）。
 * append-only ABORT；[history] 以 scopeKey 精確 `=` 比對（X-004，禁 LIKE／禁跨 scope 合併）。
 */
@Dao
interface PriceEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PriceEventEntity)

    @Query("SELECT * FROM price_events WHERE eventId = :eventId")
    suspend fun getById(eventId: String): PriceEventEntity?

    @Query("SELECT * FROM price_events WHERE scopeKey = :scopeKey ORDER BY observedAt DESC, eventId ASC")
    suspend fun history(scopeKey: String): List<PriceEventEntity>

    @Query("SELECT COUNT(*) FROM price_events")
    suspend fun count(): Int
}

/**
 * X-018 QuotaEvent DAO（Room v10；同上）。
 * append-only ABORT；scopeKey 精確比對（X-004）。
 */
@Dao
interface QuotaEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: QuotaEventEntity)

    @Query("SELECT * FROM quota_events WHERE eventId = :eventId")
    suspend fun getById(eventId: String): QuotaEventEntity?

    @Query("SELECT * FROM quota_events WHERE scopeKey = :scopeKey ORDER BY observedAt DESC, eventId ASC")
    suspend fun history(scopeKey: String): List<QuotaEventEntity>

    @Query("SELECT COUNT(*) FROM quota_events")
    suspend fun count(): Int
}
