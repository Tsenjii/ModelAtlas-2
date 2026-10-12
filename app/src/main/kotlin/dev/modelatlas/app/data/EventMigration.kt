package dev.modelatlas.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10（X-018 事件層；TRACEABILITY X-018／PRODUCT_SPEC §10末端 `… → Snapshot → Event`／
 * §11 History／§14 Notices-Events可讀／IMPLEMENTATION_PLAN「隨階段增加 PriceEvent／
 * ProviderNotice／Event」）：只 CREATE TABLE／INDEX，不改舊欄型別、不刪資料、
 * 不重建既有表、無 destructive。
 *
 * 新增四表（無 FK，理由見 [ProviderNoticeEntity]）：
 * - `provider_notices`（noticeId／providerId／observedAt／expiresAt／source／
 *   evidenceId／summary／ref）
 * - `provider_events`（eventId／providerId／kind／observedAt／source／evidenceId／detail）
 * - `price_events`（eventId／scopeKey／fromState／toState／fromInput／toInput／
 *   fromOutput／toOutput／observedAt／source／evidenceId）
 * - `quota_events`（eventId／scopeKey／fromState／toState／observedAt／source／evidenceId）
 *
 * 欄序與 `Entities.kt` 對應 entity 宣告序一致（Room DDL 尾段比對依賴此序）。
 * 型別對應：String→TEXT（非空 NOT NULL，可空 nullable），Long→INTEGER NOT NULL，
 * Long?→INTEGER nullable。
 */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_notices` (`noticeId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, `expiresAt` INTEGER, `source` TEXT NOT NULL, `evidenceId` TEXT, `summary` TEXT NOT NULL, `ref` TEXT, PRIMARY KEY(`noticeId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_notices_provider` ON `provider_notices` (`providerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_notices_observed` ON `provider_notices` (`observedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_events` (`eventId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `kind` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, `evidenceId` TEXT, `detail` TEXT, PRIMARY KEY(`eventId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_events_provider` ON `provider_events` (`providerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_events_observed` ON `provider_events` (`observedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `price_events` (`eventId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `fromState` TEXT, `toState` TEXT, `fromInput` TEXT, `toInput` TEXT, `fromOutput` TEXT, `toOutput` TEXT, `observedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, `evidenceId` TEXT, PRIMARY KEY(`eventId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_price_events_scope` ON `price_events` (`scopeKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_price_events_observed` ON `price_events` (`observedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `quota_events` (`eventId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `fromState` TEXT, `toState` TEXT, `observedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, `evidenceId` TEXT, PRIMARY KEY(`eventId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quota_events_scope` ON `quota_events` (`scopeKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quota_events_observed` ON `quota_events` (`observedAt`)")
    }
}
