package dev.modelatlas.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7（MODEL-001…003／BACKLOG F1／MODEL-006／MODEL-007）：只 CREATE TABLE／INDEX
 * 與 ALTER TABLE ADD COLUMN，不改舊欄型別、不刪資料、不重建既有表。
 *
 * 新增 `canonical_models`／`provider_model_aliases`／`model_classifications`＋
 * 三組分類關聯表（`classification_capabilities`／`classification_modalities`／
 * `classification_endpoint_types`）。alias／mapping 表保留 source／version 欄，
 * P7 LMSpeed enrichment 可追加，不改 schema 語意。
 *
 * v7 追加三個可空欄（MODEL-006 hidden offering／MODEL-007 selected-key group 維度）：
 * - `offerings.hidden`：NOT NULL DEFAULT 0，既有列一律 0＝可見（不被升格為已驗證）；
 *   DEFAULT 0 是唯一能讓 SQLite 對既有列安全 ADD COLUMN 的寫法，實體端
 *   `@ColumnInfo(defaultValue = "0")` 與之對齊。
 * - `offerings.verificationEvidenceId`：TEXT 可空，null＝未驗證。
 * - `credential_availability.group`：TEXT 可空，null＝未知 scope（不補零值、不自造群組）。
 *
 * 三欄皆只加不改：既有 offering／availability 資料一字不動，價格與配額狀態語意不變。
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `canonical_models` (`canonicalModelId` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `displayName` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`canonicalModelId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_canonical_models_normalizedName` ON `canonical_models` (`normalizedName`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_model_aliases` (`aliasId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `providerModelId` TEXT NOT NULL, `canonicalModelId` TEXT NOT NULL, `source` TEXT NOT NULL, `confidence` TEXT NOT NULL, `verified` INTEGER NOT NULL, `observedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `evidenceId` TEXT, PRIMARY KEY(`aliasId`), FOREIGN KEY(`canonicalModelId`) REFERENCES `canonical_models`(`canonicalModelId`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_model_aliases_canonical` ON `provider_model_aliases` (`canonicalModelId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_provider_model_aliases_provider_model` ON `provider_model_aliases` (`providerId`, `providerModelId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `model_classifications` (`id` TEXT NOT NULL, `canonicalModelId` TEXT NOT NULL, `source` TEXT NOT NULL, `confidence` TEXT NOT NULL, `primaryCategory` TEXT, `observedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`canonicalModelId`) REFERENCES `canonical_models`(`canonicalModelId`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_model_classifications_canonical` ON `model_classifications` (`canonicalModelId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_model_classifications_canonical_source` ON `model_classifications` (`canonicalModelId`, `source`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `classification_capabilities` (`classificationId` TEXT NOT NULL, `capability` TEXT NOT NULL, PRIMARY KEY(`classificationId`, `capability`), FOREIGN KEY(`classificationId`) REFERENCES `model_classifications`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_classification_capabilities_classification` ON `classification_capabilities` (`classificationId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `classification_modalities` (`classificationId` TEXT NOT NULL, `modality` TEXT NOT NULL, PRIMARY KEY(`classificationId`, `modality`), FOREIGN KEY(`classificationId`) REFERENCES `model_classifications`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_classification_modalities_classification` ON `classification_modalities` (`classificationId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `classification_endpoint_types` (`classificationId` TEXT NOT NULL, `endpointType` TEXT NOT NULL, PRIMARY KEY(`classificationId`, `endpointType`), FOREIGN KEY(`classificationId`) REFERENCES `model_classifications`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_classification_endpoint_types_classification` ON `classification_endpoint_types` (`classificationId`)")
        // MODEL-006／MODEL-007：只加欄。`hidden` 以 DEFAULT 0 追加，既有列預設可見且未驗證。
        db.execSQL("ALTER TABLE `offerings` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `offerings` ADD COLUMN `verificationEvidenceId` TEXT")
        db.execSQL("ALTER TABLE `credential_availability` ADD COLUMN `group` TEXT")
    }
}

/**
 * v8（LMS-003／LMS-004）：只 CREATE TABLE／INDEX，不改舊欄型別、不刪資料、
 * 不重建既有表、不碰 snapshot_facts／snapshot_heads／samples／telemetry。
 *
 * 新增 `external_observations`（外部觀測快取：source／externalModelId／
 * canonicalModelId 可空／payloadJson／fetchedAt／ttlMillis／confidence）與
 * `external_mappings`（外部映射槽：externalId／canonicalModelId 可空／source／
 * confidence／verified／version／observedAt，unique(externalId, source)）。
 * source-agnostic：不寫任何來源 URL／endpoint。external 與 local 永遠分開。
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `external_observations` (`observationId` TEXT NOT NULL, `source` TEXT NOT NULL, `externalModelId` TEXT NOT NULL, `canonicalModelId` TEXT, `payloadJson` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, `ttlMillis` INTEGER NOT NULL, `confidence` TEXT NOT NULL, PRIMARY KEY(`observationId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_external_observations_source_model_fetched` ON `external_observations` (`source`, `externalModelId`, `fetchedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_observations_canonical` ON `external_observations` (`canonicalModelId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_observations_source` ON `external_observations` (`source`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_observations_fetched` ON `external_observations` (`fetchedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `external_mappings` (`mappingId` TEXT NOT NULL, `externalId` TEXT NOT NULL, `canonicalModelId` TEXT, `source` TEXT NOT NULL, `confidence` TEXT NOT NULL, `verified` INTEGER NOT NULL, `version` INTEGER NOT NULL, `observedAt` INTEGER NOT NULL, PRIMARY KEY(`mappingId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_mappings_canonical` ON `external_mappings` (`canonicalModelId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_external_mappings_external_source` ON `external_mappings` (`externalId`, `source`)")
    }
}

/**
 * v9（MODEL-005 收藏／PROV-006 憑證 metadata／PROV-006 health 快照）：只 CREATE TABLE／INDEX
 * 與 ALTER TABLE ADD COLUMN，不改舊欄型別、不刪資料、不重建既有表。
 *
 * - 新表 `favorite_models`／`watchlist_models`／`recent_models`：使用者狀態，無 FK（可先於
 *   canonical 存在），以 canonicalModelId 為主鍵與 ModelSearchLogic 同一命名空間。
 * - `providers` 追加 health 快照欄 `officialHealthAt`／`observedHealthAt`：NOT NULL DEFAULT 0
 *   ＝從未觀測（Unknown≠0，不以 0 冒充已知）；official 與 observed 分欄，不互相冒充。
 * - `credentials` 追加 metadata：`note`／`group`／`priority`／`authMode`／`headerName`／
 *   `headerPrefix`／`isSecret`／`lastSuccessAt`／`lastFailureAt`。`headerPrefix` 為非敏感顯示
 *   前綴，secret value 仍只在 vault；未知一律 null／0／UNKNOWN（不自造 group、不猜 authMode；
 *   舊列 authMode 以 DEFAULT 'UNKNOWN' 保持未知，新列已知值由 call site 顯式傳入）。
 *
 * ALTER 欄序與對應 entity 宣告序一致（Room DDL 尾段比對依賴此序）。
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `favorite_models` (`canonicalModelId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`canonicalModelId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorite_models_added` ON `favorite_models` (`addedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `watchlist_models` (`canonicalModelId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `note` TEXT, PRIMARY KEY(`canonicalModelId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watchlist_models_added` ON `watchlist_models` (`addedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `recent_models` (`canonicalModelId` TEXT NOT NULL, `lastUsedAt` INTEGER NOT NULL, `useCount` INTEGER NOT NULL, PRIMARY KEY(`canonicalModelId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recent_models_last_used` ON `recent_models` (`lastUsedAt`)")
        db.execSQL("ALTER TABLE `providers` ADD COLUMN `officialHealthAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `providers` ADD COLUMN `observedHealthAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `note` TEXT")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `group` TEXT")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `authMode` TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `headerName` TEXT")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `headerPrefix` TEXT")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `isSecret` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `lastSuccessAt` INTEGER")
        db.execSQL("ALTER TABLE `credentials` ADD COLUMN `lastFailureAt` INTEGER")
    }
}
