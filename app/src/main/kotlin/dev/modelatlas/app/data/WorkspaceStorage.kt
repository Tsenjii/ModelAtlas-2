package dev.modelatlas.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val workspaceSyncTables=setOf("workspace_sync_state","workspace_sync_records","workspace_sync_conflicts",
    "workspace_sync_versions","workspace_sync_locators","workspace_sync_links","workspace_sync_vault")

@Entity(tableName = "workspace_sync_state")
data class WorkspaceStateEntity(@PrimaryKey val key: String, val valueJson: String)
@Entity(tableName = "workspace_sync_records", primaryKeys = ["kind", "id"])
data class WorkspaceRecordEntity(
    val kind: String, val id: String, val revision: Long, val baseJson: String,
    val localJson: String, val dirty: Boolean, val pendingProjection: Boolean,
)
@Entity(tableName = "workspace_sync_conflicts")
data class WorkspaceConflictEntity(
    @PrimaryKey val id: String, val kind: String, val recordId: String,
    val localJson: String, val remoteJson: String, val state: String, val createdAt: Long,
)
@Entity(tableName = "workspace_sync_versions", primaryKeys = ["tableName", "rowId"])
data class WorkspaceVersionEntity(val tableName: String, val rowId: Long, val version: Long)
@Entity(tableName = "workspace_sync_locators", primaryKeys = ["tableName", "rowId"])
data class WorkspaceLocatorEntity(
    val tableName: String, val rowId: Long, val version: Long,
    val signature: String, val linksJson: String,
)
@Entity(tableName = "workspace_sync_links", primaryKeys = ["kind", "wireId"])
data class WorkspaceLinkEntity(
    val kind: String, val wireId: String, val nativeId: String,
    val nativeTable: String, val nativeKeys: String,
)
/** Only Keystore ciphertext enters this vault, never raw secrets or a sync export. */
@Entity(tableName = "workspace_sync_vault")
data class WorkspaceVaultEntity(
    @PrimaryKey val ref: String, val origin: String, val nonce: String,
    val ciphertext: String, val deleted: Boolean,
)

val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `workspace_sync_state` (`key` TEXT NOT NULL, `valueJson` TEXT NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `workspace_sync_records` (`kind` TEXT NOT NULL, `id` TEXT NOT NULL, `revision` INTEGER NOT NULL, `baseJson` TEXT NOT NULL, `localJson` TEXT NOT NULL, `dirty` INTEGER NOT NULL, `pendingProjection` INTEGER NOT NULL, PRIMARY KEY(`kind`, `id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `workspace_sync_conflicts` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `recordId` TEXT NOT NULL, `localJson` TEXT NOT NULL, `remoteJson` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `workspace_sync_versions` (`tableName` TEXT NOT NULL, `rowId` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`tableName`, `rowId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `workspace_sync_locators` (`tableName` TEXT NOT NULL, `rowId` INTEGER NOT NULL, `version` INTEGER NOT NULL, `signature` TEXT NOT NULL, `linksJson` TEXT NOT NULL, PRIMARY KEY(`tableName`, `rowId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `workspace_sync_links` (`kind` TEXT NOT NULL, `wireId` TEXT NOT NULL, `nativeId` TEXT NOT NULL, `nativeTable` TEXT NOT NULL, `nativeKeys` TEXT NOT NULL, PRIMARY KEY(`kind`, `wireId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `workspace_sync_vault` (`ref` TEXT NOT NULL, `origin` TEXT NOT NULL, `nonce` TEXT NOT NULL, `ciphertext` TEXT NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`ref`))")
    }
}

val workspaceNativeTables = listOf(
    "providers", "accounts", "credentials", "endpoints", "provider_configurations",
    "canonical_models", "offerings", "credential_availability", "provider_model_aliases",
    "model_classifications", "classification_capabilities", "classification_modalities",
    "classification_endpoint_types", "my_models", "favorite_models", "watchlist_models",
    "recent_models", "samples", "evidence", "price_snapshots", "quota_snapshots",
    "snapshot_facts", "snapshot_heads", "snapshot_slice_heads", "external_observations",
    "external_mappings", "checkins", "provider_notices", "provider_events", "price_events",
    "quota_events", "provider_perf_facts", "provider_perf_heads",
    "provider_account_usage_facts", "provider_account_usage_heads",
)

/** Installed only after sync is explicitly configured. No JSON1 or modern UPSERT
 * dependency: this journal also runs on the app's oldest supported SQLite. */
fun installWorkspaceJournal(db: SupportSQLiteDatabase) {
    installWorkspaceHashJournal(db)
    for (table in workspaceNativeTables) {
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            val row = if (operation == "DELETE") "OLD.rowid" else "NEW.rowid"
            db.execSQL("CREATE TRIGGER IF NOT EXISTS workspace_journal_${table}_${operation.lowercase()} AFTER $operation ON `$table` WHEN NOT EXISTS(SELECT 1 FROM workspace_sync_state WHERE `key`='importing' AND valueJson='1') BEGIN " +
                "UPDATE workspace_sync_versions SET version=version+1 WHERE tableName='$table' AND rowId=$row; " +
                "INSERT INTO workspace_sync_versions SELECT '$table',$row,1 WHERE NOT EXISTS(SELECT 1 FROM workspace_sync_versions WHERE tableName='$table' AND rowId=$row); END")
        }
        db.execSQL("INSERT OR IGNORE INTO workspace_sync_versions SELECT '$table',rowid,0 FROM `$table`")
    }
    val mapper=db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='credential-mapper-version'")?.text("valueJson")?.toIntOrNull() ?: 0
    if(mapper<2) {
        // Re-export roles from committed typed configuration after upgrading an
        // earlier sync candidate; changing a mapper must not require a user edit.
        db.execSQL("UPDATE workspace_sync_versions SET version=version+1 WHERE tableName='credentials'")
        db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('credential-mapper-version','2')")
    }
}
