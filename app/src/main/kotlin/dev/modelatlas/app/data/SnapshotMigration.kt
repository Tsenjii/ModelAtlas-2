package dev.modelatlas.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v5 只加表與索引；舊快照不推定為官方，不猜版本／證據。 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `snapshot_facts` (`evidenceId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, `snapshotId` TEXT, `state` TEXT NOT NULL, `status` TEXT NOT NULL, `confidence` TEXT NOT NULL, `providerVersion` TEXT, `pricingVersion` TEXT, `itemPricingVersion` TEXT, `quotaPerUnit` TEXT, `groupRatio` TEXT, `statusObservedAt` INTEGER, `remainingRaw` INTEGER, `usedRaw` INTEGER, `grantedRaw` INTEGER, `unlimited` INTEGER, `expiresAtRaw` INTEGER, `expiryMeaning` TEXT, `expressionHash` TEXT, `priceUnit` TEXT, PRIMARY KEY(`evidenceId`), FOREIGN KEY(`evidenceId`) REFERENCES `evidence`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_snapshot_facts_scopeKey_kind_observedAt` ON `snapshot_facts` (`scopeKey`, `kind`, `observedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `snapshot_heads` (`scopeKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `latestEvidenceId` TEXT NOT NULL, `latestObservedAt` INTEGER NOT NULL, `lastGoodSnapshotId` TEXT, `lastGoodObservedAt` INTEGER, PRIMARY KEY(`scopeKey`, `kind`))")
    }
}

/**
 * v6 只加欄（B3/H3）：`snapshot_facts` 加 `quotaScopeKind`／`quotaProjectId`／
 * `quotaSlice`／`isEstimated`（全 nullable，舊列為 null，不改舊欄、不刪資料）。
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `snapshot_facts` ADD COLUMN `quotaScopeKind` TEXT")
        db.execSQL("ALTER TABLE `snapshot_facts` ADD COLUMN `quotaProjectId` TEXT")
        db.execSQL("ALTER TABLE `snapshot_facts` ADD COLUMN `quotaSlice` TEXT")
        db.execSQL("ALTER TABLE `snapshot_facts` ADD COLUMN `isEstimated` INTEGER")
    }
}
