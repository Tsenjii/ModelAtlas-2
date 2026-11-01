package dev.modelatlas.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.modelatlas.app.onboarding.BatchSnapshotDao
import dev.modelatlas.app.onboarding.BatchSnapshotEntity

/** modelatlas.db，version 16，不做 destructive migration。v2: availability UNIQUE(offeringId,accountId,credentialId)；checkins PK 加 accountId，legacy "" 不當 wildcard。v3: onboarding_snapshots / onboarding_commits（只加表）。v4: provider_configurations / onboarding_batches（只加表）。v5: snapshot_facts / snapshot_heads（只加表）。v6: snapshot_facts 加 quotaScopeKind／quotaProjectId／quotaSlice／isEstimated（只加欄）。v7: canonical_models / provider_model_aliases / model_classifications＋三組分類關聯表（只加表，MODEL-001…003／BACKLOG F1）。v8: external_observations / external_mappings（只加表，LMS-003／LMS-004 external 槽獨立）。v9: favorite_models / watchlist_models / recent_models（只加表，MODEL-005）＋providers health 快照兩欄＋credentials metadata 九欄（只加欄，PROV-006／PROV-009）。v10: provider_notices / provider_events / price_events / quota_events（只加表，X-018 事件層）。v11: my_models（只加表，③-1 My Models membership，D-008）。v12: provider_perf_facts / provider_perf_heads（只加表，NINT-004 provider-wide perf 專用槽，NEWAPI-001 候選）。v13: provider_account_usage_facts / provider_account_usage_heads（只加表，NINT-005A 帳號 request count 專用槽）。v14: credential_availability 加非空 `groupIdentity`（CR-003 B3，`availabilityGroupIdentity(group)`：null→'n'、非 null→'v'+原值，defaultValue='n'），UNIQUE 三欄索引 `index_credential_availability_offering_account_credential` 退役（DROP INDEX IF EXISTS，缺舊索引的畸形 v13 可恢復升級；真重複仍由四欄 CREATE UNIQUE fail-closed）、換四欄 `index_credential_availability_offering_account_credential_group`；`group` nullable 原值保留、不刪列、不猜 group。v15: snapshot_slice_heads（只加表，CR-011 quota-slice heads，PK (scopeKey,kind,quotaSlice)＋kind INDEX；backfill ambiguity→absence）。 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_credential_availability_offering_account_credential` ON `credential_availability` (`offeringId`, `accountId`, `credentialId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `checkins_new` (`providerId` TEXT NOT NULL, `date` TEXT NOT NULL, `completedAt` INTEGER, `source` TEXT NOT NULL, `accountId` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`providerId`, `date`, `accountId`))")
        db.execSQL("INSERT INTO `checkins_new` (`providerId`, `date`, `completedAt`, `source`, `accountId`) SELECT `providerId`, `date`, `completedAt`, `source`, CASE WHEN (SELECT COUNT(*) FROM `accounts` WHERE `accounts`.`providerId` = `checkins`.`providerId`) = 1 THEN (SELECT `accountId` FROM `accounts` WHERE `accounts`.`providerId` = `checkins`.`providerId` LIMIT 1) ELSE '' END FROM `checkins`")
        db.execSQL("DROP TABLE `checkins`")
        db.execSQL("ALTER TABLE `checkins_new` RENAME TO `checkins`")
    }
}
/** v3：onboarding 快照與 commit 去重鍵；只加表，不動既有表。 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `onboarding_snapshots` (`workflowId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL, PRIMARY KEY(`workflowId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `onboarding_commits` (`workflowId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `committedAt` INTEGER NOT NULL, PRIMARY KEY(`workflowId`))")
    }
}
/** v4：provider_configurations / onboarding_batches；只加表，不動既有表。 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_configurations` (`providerId` TEXT NOT NULL, `workflowId` TEXT NOT NULL, `authMode` TEXT NOT NULL, `snapshotJson` TEXT NOT NULL, PRIMARY KEY(`providerId`), FOREIGN KEY(`providerId`) REFERENCES `providers`(`providerId`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_provider_configurations_workflowId` ON `provider_configurations` (`workflowId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `onboarding_batches` (`batchId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL, PRIMARY KEY(`batchId`))")
    }
}
/**
 * CR-011 v14→v15（additive-only）：只加 `snapshot_slice_heads` 表＋kind
 * INDEX；不改任何既有表／欄／PK／FK。Backfill ambiguity→absence：每組
 * (scopeKey, kind, quotaSlice) 僅當 latest_rows（MAX observedAt）恰 1 列且
 * lastGood_row（good := snapshotId IS NOT NULL AND state != 'UNKNOWN' 中
 * MAX observedAt）0-or-恰 1 列時插入（無 good fact 則 lastGood NULL）；
 * latest tie 或 lastGood tie → NO row（Unknown preserved，不挑 winner；
 * pinned evidenceId ASC tie-break REJECTED——newId() 隨機，無 write order）。
 * Backfill 範圍：snapshot_facts WHERE quotaSlice IS NOT NULL AND quotaSlice
 * != '' AND quotaSlice IN ('STANDARD_INTERACTIVE','PRIORITY','BATCH') AND
 * kind IN ('ACCOUNT_QUOTA','TOKEN_QUOTA','DEDICATED_ACCOUNT_QUOTA',
 * 'DEDICATED_TOKEN_QUOTA')。舊 snapshot_heads 列 verbatim carry、永不
 * recompute（含歷史 mixed artifact，BY DESIGN；勿「修」）。Migration 永不為
 * backfill ambiguity abort（abort 只留給真 constraint violation，如 v14
 * UNIQUE precedent）。
 */
val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `snapshot_slice_heads` (" +
                "`scopeKey` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`quotaSlice` TEXT NOT NULL, " +
                "`latestEvidenceId` TEXT NOT NULL, `latestObservedAt` INTEGER NOT NULL, " +
                "`lastGoodSnapshotId` TEXT, `lastGoodObservedAt` INTEGER, " +
                "PRIMARY KEY(`scopeKey`, `kind`, `quotaSlice`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_snapshot_slice_heads_kind` " +
                "ON `snapshot_slice_heads` (`kind`)",
        )
        // Backfill per group (ambiguity → absence). Single statement pair per
        // (scopeKey, kind, quotaSlice): insert latest pointer only when the
        // MAX(observedAt) group is unique; lastGood only when the good-row
        // MAX(observedAt) group is unique-or-absent. Ties insert nothing.
        db.execSQL(
            "INSERT INTO `snapshot_slice_heads` " +
                "(`scopeKey`, `kind`, `quotaSlice`, `latestEvidenceId`, `latestObservedAt`, " +
                "`lastGoodSnapshotId`, `lastGoodObservedAt`) " +
                "SELECT f.`scopeKey`, f.`kind`, f.`quotaSlice`, " +
                "(SELECT f2.`evidenceId` FROM `snapshot_facts` AS f2 WHERE " +
                "f2.`scopeKey` = f.`scopeKey` AND f2.`kind` = f.`kind` AND " +
                "f2.`quotaSlice` = f.`quotaSlice` AND f2.`observedAt` = " +
                "(SELECT MAX(m.`observedAt`) FROM `snapshot_facts` AS m WHERE " +
                "m.`scopeKey` = f.`scopeKey` AND m.`kind` = f.`kind` AND " +
                "m.`quotaSlice` = f.`quotaSlice`)), " +
                "MAX(f.`observedAt`), " +
                "(SELECT g2.`snapshotId` FROM `snapshot_facts` AS g2 WHERE " +
                "g2.`scopeKey` = f.`scopeKey` AND g2.`kind` = f.`kind` AND " +
                "g2.`quotaSlice` = f.`quotaSlice` AND g2.`snapshotId` IS NOT NULL AND " +
                "g2.`state` != 'UNKNOWN' AND g2.`observedAt` = " +
                "(SELECT MAX(m4.`observedAt`) FROM `snapshot_facts` AS m4 WHERE " +
                "m4.`scopeKey` = f.`scopeKey` AND m4.`kind` = f.`kind` AND " +
                "m4.`quotaSlice` = f.`quotaSlice` AND m4.`snapshotId` IS NOT NULL AND " +
                "m4.`state` != 'UNKNOWN')), " +
                "MAX(CASE WHEN f.`snapshotId` IS NOT NULL AND f.`state` != 'UNKNOWN' " +
                "THEN f.`observedAt` END) " +
                "FROM `snapshot_facts` AS f WHERE f.`quotaSlice` IS NOT NULL AND " +
                "f.`quotaSlice` != '' AND f.`quotaSlice` IN " +
                "('STANDARD_INTERACTIVE','PRIORITY','BATCH') AND f.`kind` IN " +
                "('ACCOUNT_QUOTA','TOKEN_QUOTA','DEDICATED_ACCOUNT_QUOTA'," +
                "'DEDICATED_TOKEN_QUOTA') " +
                "GROUP BY f.`scopeKey`, f.`kind`, f.`quotaSlice` " +
                "HAVING SUM(CASE WHEN f.`observedAt` = " +
                "(SELECT MAX(`observedAt`) FROM `snapshot_facts` AS m5 WHERE " +
                "m5.`scopeKey` = f.`scopeKey` AND m5.`kind` = f.`kind` AND " +
                "m5.`quotaSlice` = f.`quotaSlice`) THEN 1 ELSE 0 END) = 1 " +
                "AND (SELECT COUNT(*) FROM `snapshot_facts` AS m2 WHERE " +
                "m2.`scopeKey` = f.`scopeKey` AND m2.`kind` = f.`kind` AND " +
                "m2.`quotaSlice` = f.`quotaSlice` AND m2.`snapshotId` IS NOT NULL AND " +
                "m2.`state` != 'UNKNOWN' AND m2.`observedAt` = " +
                "(SELECT MAX(`observedAt`) FROM `snapshot_facts` AS m3 WHERE " +
                "m3.`scopeKey` = f.`scopeKey` AND m3.`kind` = f.`kind` AND " +
                "m3.`quotaSlice` = f.`quotaSlice` AND m3.`snapshotId` IS NOT NULL AND " +
                "m3.`state` != 'UNKNOWN')) <= 1",
        )
    }
}
@Database(
    entities = [
        ProviderEntity::class,
        AccountEntity::class,
        CredentialEntity::class,
        EndpointEntity::class,
        OfferingEntity::class,
        CredentialAvailabilityEntity::class,
        SampleEntity::class,
        EvidenceEntity::class,
        CheckinEntity::class,
        PriceSnapshotEntity::class,
        QuotaSnapshotEntity::class,
        OnboardingSnapshotEntity::class,
        OnboardingCommitEntity::class,
        ProviderConfigurationEntity::class,
        BatchSnapshotEntity::class,
        SnapshotFactEntity::class,
        SnapshotHeadEntity::class,
        CanonicalModelEntity::class,
        ProviderModelAliasEntity::class,
        ModelClassificationEntity::class,
        ClassificationCapabilityEntity::class,
        ClassificationModalityEntity::class,
        ClassificationEndpointTypeEntity::class,
        ExternalObservationEntity::class,
        ExternalMappingEntity::class,
        FavoriteModelEntity::class,
        WatchlistModelEntity::class,
        RecentModelEntity::class,
        ProviderNoticeEntity::class,
        ProviderEventEntity::class,
        PriceEventEntity::class,
        QuotaEventEntity::class,
        MyModelEntity::class,
        ProviderPerfFactEntity::class,
        ProviderPerfHeadEntity::class,
        ProviderAccountUsageFactEntity::class,
        ProviderAccountUsageHeadEntity::class,
        SnapshotSliceHeadEntity::class,
        WorkspaceStateEntity::class,
        WorkspaceRecordEntity::class,
        WorkspaceConflictEntity::class,
        WorkspaceVersionEntity::class,
        WorkspaceLocatorEntity::class,
        WorkspaceLinkEntity::class,
        WorkspaceVaultEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
abstract class AtlasDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun accountDao(): AccountDao
    abstract fun credentialDao(): CredentialDao
    abstract fun endpointDao(): EndpointDao
    abstract fun offeringDao(): OfferingDao
    abstract fun availabilityDao(): AvailabilityDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun sampleDao(): SampleDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun checkinDao(): CheckinDao
    abstract fun priceDao(): PriceDao
    abstract fun quotaDao(): QuotaDao
    abstract fun onboardingSnapshotDao(): OnboardingSnapshotDao
    abstract fun onboardingCommitDao(): OnboardingCommitDao
    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun batchSnapshotDao(): BatchSnapshotDao
    abstract fun snapshotFactDao(): SnapshotFactDao
    abstract fun canonicalModelDao(): CanonicalModelDao
    abstract fun providerModelAliasDao(): ProviderModelAliasDao
    abstract fun modelClassificationDao(): ModelClassificationDao
    abstract fun classificationCapabilityDao(): ClassificationCapabilityDao
    abstract fun classificationModalityDao(): ClassificationModalityDao
    abstract fun classificationEndpointTypeDao(): ClassificationEndpointTypeDao
    abstract fun externalObservationDao(): ExternalObservationDao
    abstract fun externalMappingDao(): ExternalMappingDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun providerNoticeDao(): ProviderNoticeDao
    abstract fun providerEventDao(): ProviderEventDao
    abstract fun priceEventDao(): PriceEventDao
    abstract fun quotaEventDao(): QuotaEventDao
    abstract fun myModelsDao(): MyModelsDao
    abstract fun providerPerfDao(): ProviderPerfDao
    abstract fun providerAccountUsageDao(): ProviderAccountUsageDao

    companion object {
        fun create(context: Context, name: String = "modelatlas.db"): AtlasDatabase =
            Room.databaseBuilder(context, AtlasDatabase::class.java, name).addCallback(KiloFactoryDefaults.creationCallback).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16).build()
    }
}
