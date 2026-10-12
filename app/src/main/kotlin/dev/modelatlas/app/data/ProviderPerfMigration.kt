package dev.modelatlas.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v12 只加表（NINT-004 provider-wide perf 專用槽；STORAGE Option B）。
 * [ProviderPerfFactEntity]／[ProviderPerfHeadEntity] 對應 Room 實體 DDL；
 * 欄順序／型別／nullability／PK／FK／索引命名須與實體一致（CI 驗證）。
 * 只加表、不動既有表；舊列零影響。
 */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_perf_facts` (`evidenceId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, `empty` INTEGER NOT NULL, `status` TEXT NOT NULL, `providerVersion` TEXT, `httpStatus` INTEGER, `modelId` TEXT, `groupName` TEXT, `hours` INTEGER NOT NULL, `accountId` TEXT, `credentialId` TEXT, `avgTtftMs` INTEGER, `avgLatencyMs` INTEGER, `successRate` REAL, `avgTps` REAL, `recentSuccessRates` TEXT, `seriesSchema` TEXT, `seriesJson` TEXT, `seriesPointCount` INTEGER, PRIMARY KEY(`evidenceId`), FOREIGN KEY(`evidenceId`) REFERENCES `evidence`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_perf_facts_scopeKey_kind_observedAt` ON `provider_perf_facts` (`scopeKey`, `kind`, `observedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_perf_heads` (`scopeKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `latestEvidenceId` TEXT NOT NULL, `latestObservedAt` INTEGER NOT NULL, `lastGoodEvidenceId` TEXT, `lastGoodObservedAt` INTEGER, PRIMARY KEY(`scopeKey`, `kind`))")
    }
}
