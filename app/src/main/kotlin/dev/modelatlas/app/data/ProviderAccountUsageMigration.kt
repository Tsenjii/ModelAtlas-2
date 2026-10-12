package dev.modelatlas.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v13 只加表（NINT-005A provider-reported account usage 專用槽；Contract §2／§6.4／§23.3）。
 * [ProviderAccountUsageFactEntity]／[ProviderAccountUsageHeadEntity] 對應 Room 實體 DDL；
 * 欄順序／型別／nullability／PK／FK／索引命名須與實體一致（CI 以 13.json 驗證）。
 * 只加表與索引、不動既有表、無 SQL default；舊列零影響（`usedQuota` 仍以 ACCOUNT_QUOTA 為權威）。
 */
val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_account_usage_facts` (`evidenceId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `providerId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `credentialId` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `providerVersion` TEXT, `httpStatus` INTEGER, `source` TEXT NOT NULL, `requestCountRaw` INTEGER, `counterSemantics` TEXT NOT NULL, `accountEndpointObserved` INTEGER NOT NULL, PRIMARY KEY(`evidenceId`), FOREIGN KEY(`evidenceId`) REFERENCES `evidence`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_account_usage_facts_scopeKey_kind_observedAt` ON `provider_account_usage_facts` (`scopeKey`, `kind`, `observedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_account_usage_heads` (`scopeKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `latestEvidenceId` TEXT NOT NULL, `latestObservedAt` INTEGER NOT NULL, `lastGoodEvidenceId` TEXT, `lastGoodObservedAt` INTEGER, PRIMARY KEY(`scopeKey`, `kind`))")
    }
}