package dev.modelatlas.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v14（CR-003／B3）：`credential_availability` 的 group identity lossless 化；
 * 只在既有表上加欄、重算衍生欄、換唯一索引，不重建表、不刪任何一列。
 *
 * 問題（`Entities.kt` v7 `group` 欄＋v2 舊唯一索引）：v13 的
 * `UNIQUE(offeringId,accountId,credentialId)` 不含 group，因此同一 offering × account × credential
 * 最多只能存在一列，任何「同 key 不同 group」的 scope 都存不下來。直接把 nullable `group` 加進
 * 唯一鍵並不能解決：SQLite 唯一索引視多個 NULL 為彼此相異，會放行同一 key 下多列 unknown scope，
 * 等於用另一種方式製造重複 scope。所以需要一個 NOT NULL 衍生鍵 `groupIdentity` 參與唯一索引。
 * 本 migration 只補這個 identity 缺口，
 * 不改 `priceState`／`quotaState` 語意；13.json 的 37 個 entity 中只有本表改動，其餘 36 個
 * entity 的 DDL／索引／資料完全不動。
 *
 * 設計要點：
 * - **nullable raw `group` 原值保留**：不 trim、不把空字串當 null、不 coalesce、不刪、不合併。
 *   `group` 仍是 nullable（null＝未知 scope，Unknown≠0）；`groupIdentity` 只是索引用的衍生欄。
 * - **`groupIdentity` NOT NULL DEFAULT `'n'`**：`null → 'n'`；非 null → `'v' + 原 group 原字串`。
 *   前綴哨兵保證兩者不可能相撞（null 一律收斂為單一字元 `'n'`；非 null 一律以 `'v'` 起首，
 *   含空字串時就是 `'v'`，故恆不等於 `'n'`）；
 *   欄無 COLLATE 子句（預設 BINARY），故 `'vip'`／`'VIP'`／`'Vip'`、`'免费'`／`'免費'` 各自獨立。
 * - **舊索引退役、新索引唯一鍵擴為四欄**：舊三欄唯一鍵已保證舊列不可能撞新鍵
 *   （新鍵是舊鍵的嚴格超集），故任何合法 v13 庫都能無衝突升級；
 *   壞庫若真重複，`CREATE UNIQUE INDEX` 直接讓整個 migration 失敗（fail-closed）；缺舊索引的畸形 v13 庫改為可恢復升級（DROP INDEX IF EXISTS，drop 本為 no-op），真重複仍 fail-closed，
 *   Room transaction 回滾，不以刪列換綠燈。
 * - **不重建表、不關外鍵、不 destructive**：既有三個 FK（offerings／accounts／credentials，
 *   全 RESTRICT／NO ACTION）與 PK 不動；DAO 仍 `OnConflictStrategy.ABORT`，`copy(group=…)`
 *   不會靜默吃掉另一列（改由 DAO insert 端正規化 identity，見 CR-003 ports）。
 *
 * 四句皆以 backtick 引用識別字（`group` 是 SQLite 保留字，`GROUP`）。backtick 與
 * `13.json`／Room 生成 DDL 及 MIGRATION_6_7 既有的 ``ADD COLUMN `group` `` 用字一致；
 * 語意與雙引號 `"group"` 等價（雙引號在 SQLite 會解析成同一欄），此處取與 repo 一致的寫法。
 */
val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 既有列一律先落 `'n'`，UPDATE 再逐列改成 `'v'+原 group`（含空字串、Unicode、大小寫原字串）。
        db.execSQL("ALTER TABLE `credential_availability` ADD COLUMN `groupIdentity` TEXT NOT NULL DEFAULT 'n'")
        db.execSQL("UPDATE `credential_availability` SET `groupIdentity` = CASE WHEN `group` IS NULL THEN 'n' ELSE 'v'||`group` END")
        // 退役不含 group 的三欄唯一鍵；legacy-index drop 是可恢復清理（DROP INDEX IF EXISTS）：缺索引只可能出現於非 Room 產生的畸形 v13（1.json 無此索引、2.json–13.json schema 鏈全含，drop 本為 no-op）；真正的 fail-closed gate 是緊接其後無條件的 CREATE UNIQUE INDEX（真重複 → abort + rollback，不以刪列換綠燈）；Room post-migration schema validation 仍逐物件驗證 end state（與 MIGRATION_14_15 abort 只留給真 constraint violation 的 precedent 一致）；刻意不加 IF NOT EXISTS 到新索引 CREATE。
        db.execSQL("DROP INDEX IF EXISTS `index_credential_availability_offering_account_credential`")
        db.execSQL("CREATE UNIQUE INDEX `index_credential_availability_offering_account_credential_group` ON `credential_availability` (`offeringId`, `accountId`, `credentialId`, `groupIdentity`)")
    }
}