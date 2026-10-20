package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v11 → v12 真 upgrade 驗證（TRACEABILITY NINT-004；PRODUCT_SPEC §18／
 * STORAGE_CONTRACT；Contract §2／§6）。
 *
 * - 以已納管 11.json 建 legacy v11 庫，seed 最小代表 fixture（provider／account／
 *   credential／my_models／sample／evidence 等；完整 preservation 案 seed 全部
 *   v11 表各一列，皆為本地 fixture，非 live 產品）。
 * - 保存所有既有表 rows／DDL（參考 AtlasMigration4To5Test）；先以已納管 12.json 驗
 *   MIGRATION_11_12 原契約，再以編譯產生的 AtlasDatabase_Impl 開到最新 v14 驗證；
 *   不依賴尚未 export 的 14.json，避免 KSP assets 第一次 CI race。
 * - 舊 rows／DDL 字字相同；新 provider_perf 兩表為空、型別／nullable／PK／
 *   indices／FK RESTRICT 與 Entity 吻合；原 samples 不增（來源分離）；
 *   新表經正式 DAO 可寫／讀（綁 evidence FK）。
 * - Store 同步語意（last-good／empty／version gate）留給專用 store tests，
 *   本檔不鏡像 production、不猜 store ports。
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration11To12Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    @Test
    fun migrate11To12_preservesEveryLegacyRowAndSchema_addsOnlyPerfTables() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-11-12-perf-schema.db"
        context.deleteDatabase(name)
        val legacySchema: Map<String, String?>
        val legacyRows: Map<String, List<List<String?>>>
        val legacyTables: Set<String>
        helper.createDatabase(name, 11).use { old ->
            seedFullLegacyV11(old)
            legacyTables = tables(old)
            legacySchema = schemaObjects(old)
            legacyRows = legacyTables.associateWith { rows(old, it) }
            assertFalse(legacyTables.contains("provider_perf_facts"))
            assertFalse(legacyTables.contains("provider_perf_heads"))
        }
        // Pin the original 11→12 contract before opening the current Room version.
        // Managed 12.json exists; new 13.json is exported by CI and is not a test prerequisite.
        helper.runMigrationsAndValidate(name, 12, true, MIGRATION_11_12).use { v12 ->
            assertEquals(12, v12.version)
            assertEquals(legacyTables + setOf("provider_perf_facts", "provider_perf_heads"), tables(v12))
            assertPerfTablesTyped(v12)
            for ((table, before) in legacyRows) assertEquals(table, before, rows(v12, table))
        }
        var room: AtlasDatabase? = null
        try {
            val opened = Room.databaseBuilder(context, AtlasDatabase::class.java, name)
                .addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, dev.modelatlas.app.data.MIGRATION_15_16)
                .allowMainThreadQueries()
                .build()
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(16, sql.version)
            // The original perf upgrade adds only its two tables; the current upgrades add usage separately
            // and CR-003 v14 adds availability groupIdentity + swaps the unique index;
            // CR-011 v15 adds snapshot_slice_heads (additive-only).
            assertEquals(
                legacyTables + setOf("provider_perf_facts", "provider_perf_heads",
                    "provider_account_usage_facts", "provider_account_usage_heads",
                    "snapshot_slice_heads") + workspaceSyncTables,
                tables(sql),
            )
            val currentSchema = schemaObjects(sql)
            for ((objectName, ddl) in legacySchema) {
                // v14 intended：舊三欄 availability unique 索引退役，改由下方四欄新索引驗證。
                if (objectName == OLD_AVAILABILITY_UNIQUE_INDEX) continue
                if (objectName == "credential_availability") {
                    val original = requireNotNull(ddl)
                    val pkIndex = original.indexOf(", PRIMARY KEY(")
                    assertTrue("$objectName DDL 應含 PRIMARY KEY", pkIndex >= 0)
                    assertEquals(
                        objectName,
                        original.substring(0, pkIndex) +
                            ", `groupIdentity` TEXT NOT NULL DEFAULT 'n'" + original.substring(pkIndex),
                        currentSchema[objectName],
                    )
                    continue
                }
                assertEquals(objectName, ddl, currentSchema[objectName])
            }
            assertFalse(currentSchema.containsKey(OLD_AVAILABILITY_UNIQUE_INDEX))
            assertUniqueIndex(
                sql,
                "credential_availability",
                NEW_AVAILABILITY_UNIQUE_INDEX,
                listOf("offeringId", "accountId", "credentialId", "groupIdentity"),
            )
            for ((table, before) in legacyRows) {
                if (table == "credential_availability") {
                    // v14 只 append groupIdentity（欄序尾端）；seed 的 group 為 null → 'n'。
                    val after = rows(sql, table)
                    assertEquals(table, before, after.map { it.dropLast(1) })
                    assertEquals(listOf("n"), after.map { it.last() })
                } else {
                    assertEquals(table, before, rows(sql, table))
                }
            }
            // 新表為空、型別釘住、索引／FK 與 Entity 吻合。
            assertEquals(emptyList<List<String?>>(), rows(sql, "provider_perf_facts"))
            assertEquals(emptyList<List<String?>>(), rows(sql, "provider_perf_heads"))
            assertPerfTablesTyped(sql)
            // Legacy Unknown／精確值一字不動。
            assertEquals("Seek", opened.providerDao().getById("p12")!!.name)
            assertTrue(opened.myModelsDao().all().any { it.providerId == "p12" && it.providerModelId == "fixture-model" })
            assertEquals("FREE_NOW", opened.sampleDao().getById("legacy-sample-12")!!.priceState)
            assertEquals(9_007_199_254_740_993L, opened.sampleDao().getById("legacy-sample-12")!!.inputTokens)
            assertEquals("0.000000000000000000123456789", opened.priceDao().getById("legacy-price-12")!!.input)
            assertNull(opened.quotaDao().getById("legacy-quota-12")!!.state)
            // 來源分離：原 samples／snapshot_facts 列數不增。
            assertEquals(legacyRows["samples"]!!.size, rows(sql, "samples").size)
            assertEquals(legacyRows["snapshot_facts"]!!.size, rows(sql, "snapshot_facts").size)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migratedPerfTables_writableViaDaoAndSourceSeparated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-11-12-perf-writable.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 11).use { old ->
            seedMinimalLegacyV11(old)
        }
        helper.runMigrationsAndValidate(name, 12, true, MIGRATION_11_12).close()
        var room: AtlasDatabase? = null
        try {
            val opened = Room.databaseBuilder(context, AtlasDatabase::class.java, name)
                .addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, dev.modelatlas.app.data.MIGRATION_15_16)
                .allowMainThreadQueries()
                .build()
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(16, sql.version)
            val samplesBefore = rows(sql, "samples").size
            // 缺 evidence 的 fact 不得寫入（FK RESTRICT 前置）。
            var missingRejected = false
            try {
                opened.providerPerfDao().insertFact(
                    ProviderPerfFactEntity(
                        evidenceId = "perf:missing-evidence",
                        scopeKey = providerPerfScopeKey("p12", "fixture-model", null, 168),
                        kind = ProviderPerfKind.SUMMARY.name,
                        observedAt = 2000L,
                        empty = false,
                        status = "OK",
                        providerVersion = "v1.0.0-rc.25",
                        httpStatus = 200,
                        modelId = "fixture-model",
                        groupName = null,
                        hours = 168,
                        accountId = null,
                        credentialId = null,
                        avgTtftMs = null,
                        avgLatencyMs = 120L,
                        successRate = 99.5,
                        avgTps = 10.0,
                        recentSuccessRates = null,
                        seriesSchema = null,
                        seriesJson = null,
                        seriesPointCount = null,
                    ),
                )
            } catch (_: SQLiteConstraintException) {
                missingRejected = true
            }
            assertTrue(missingRejected)
            // 綁定 evidence 後可寫／可讀；head 指標可寫。
            opened.evidenceDao().insert(
                EvidenceEntity(
                    id = "perf:ev-1",
                    requestId = "perf:ev-1",
                    providerId = "p12",
                    modelId = "fixture-model",
                    outcome = "SUCCESS",
                    status = 200,
                    timestamp = 2000L,
                    source = "newapi/api/perf-metrics/summary",
                ),
            )
            val scopeKey = providerPerfScopeKey("p12", "fixture-model", null, 168)
            opened.providerPerfDao().insertFact(
                ProviderPerfFactEntity(
                    evidenceId = "perf:ev-1",
                    scopeKey = scopeKey,
                    kind = ProviderPerfKind.SUMMARY.name,
                    observedAt = 2000L,
                    empty = false,
                    status = "OK",
                    providerVersion = "v1.0.0-rc.25",
                    httpStatus = 200,
                    modelId = "fixture-model",
                    groupName = null,
                    hours = 168,
                    accountId = null,
                    credentialId = null,
                    avgTtftMs = null,
                    avgLatencyMs = 120L,
                    successRate = 99.5,
                    avgTps = 10.0,
                    recentSuccessRates = null,
                    seriesSchema = null,
                    seriesJson = null,
                    seriesPointCount = null,
                ),
            )
            opened.providerPerfDao().upsertHead(
                ProviderPerfHeadEntity(
                    scopeKey = scopeKey,
                    kind = ProviderPerfKind.SUMMARY.name,
                    latestEvidenceId = "perf:ev-1",
                    latestObservedAt = 2000L,
                    lastGoodEvidenceId = "perf:ev-1",
                    lastGoodObservedAt = 2000L,
                ),
            )
            val fact = opened.providerPerfDao().fact("perf:ev-1")!!
            assertEquals(scopeKey, fact.scopeKey)
            assertEquals(120L, fact.avgLatencyMs)
            assertEquals(99.5, fact.successRate!!, 0.0)
            assertEquals(1, opened.providerPerfDao().history(scopeKey, ProviderPerfKind.SUMMARY.name).size)
            assertEquals("perf:ev-1", opened.providerPerfDao().head(scopeKey, ProviderPerfKind.SUMMARY.name)!!.latestEvidenceId)
            // 父 evidence 被 fact 引用時不得刪除（RESTRICT）；原 samples 不增。
            var parentDeleteRejected = false
            try {
                sql.execSQL("DELETE FROM evidence WHERE id = 'perf:ev-1'")
            } catch (_: SQLiteConstraintException) {
                parentDeleteRejected = true
            }
            assertTrue(parentDeleteRejected)
            assertEquals(samplesBefore, rows(sql, "samples").size)
            assertEquals("legacy-sample-12", opened.sampleDao().getById("legacy-sample-12")!!.id)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    /** 全部 v11 表各一列：證明 upgrade 保 ALL 舊 rows／schema（皆 fixture）。 */
    private fun seedFullLegacyV11(db: SupportSQLiteDatabase) {
        seedMinimalLegacyV11(db)
        db.execSQL("INSERT INTO onboarding_snapshots (workflowId,updatedAt,snapshotJson) VALUES ('workflow-12',1500,'{}')")
        db.execSQL("INSERT INTO onboarding_commits (workflowId,providerId,committedAt) VALUES ('workflow-12','p12',1600)")
        db.execSQL("INSERT INTO provider_configurations (providerId,workflowId,authMode,snapshotJson) VALUES ('p12','workflow-12','BEARER','{}')")
        db.execSQL("INSERT INTO onboarding_batches (batchId,updatedAt,snapshotJson) VALUES ('batch-12',1700,'{}')")
        db.execSQL("INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,state,status,confidence) VALUES ('legacy-evidence-12','scope-12','PRICE',2000,'UNKNOWN','OK','OFFICIAL')")
        db.execSQL("INSERT INTO snapshot_heads (scopeKey,kind,latestEvidenceId,latestObservedAt) VALUES ('scope-12','PRICE','legacy-evidence-12',2000)")
        db.execSQL("INSERT INTO canonical_models (canonicalModelId, normalizedName, displayName, createdAt, updatedAt, version) VALUES ('c12', 'fixture-model', 'Fixture', 1, 1, 1)")
        db.execSQL("INSERT INTO provider_model_aliases (aliasId, providerId, providerModelId, canonicalModelId, source, confidence, verified, observedAt, version, evidenceId) VALUES ('alias:1:p12:fixture-model', 'p12', 'fixture-model', 'c12', 'METADATA', 'MEDIUM', 0, 2, 1, NULL)")
        db.execSQL("INSERT INTO model_classifications (id, canonicalModelId, source, confidence, primaryCategory, observedAt, version) VALUES ('classification:c12:MANUAL', 'c12', 'MANUAL', 'LOW', 'CODING', 3, 1)")
        db.execSQL("INSERT INTO classification_capabilities (classificationId, capability) VALUES ('classification:c12:MANUAL', 'CODING')")
        db.execSQL("INSERT INTO classification_modalities (classificationId, modality) VALUES ('classification:c12:MANUAL', 'TEXT_INPUT')")
        db.execSQL("INSERT INTO classification_endpoint_types (classificationId, endpointType) VALUES ('classification:c12:MANUAL', 'CHAT_COMPLETIONS')")
        db.execSQL("INSERT INTO external_observations (observationId, source, externalModelId, canonicalModelId, payloadJson, fetchedAt, ttlMillis, confidence) VALUES ('obs-12', 'fixture-source', 'ext-m', NULL, '{\"hint\":\"fixture\"}', 1000, 1000, 'LOW')")
        db.execSQL("INSERT INTO external_mappings (mappingId, externalId, canonicalModelId, source, confidence, verified, version, observedAt) VALUES ('map-12', 'ext-m1', NULL, 'fixture-source', 'LOW', 0, 1, 2000)")
        db.execSQL("INSERT INTO favorite_models (canonicalModelId, addedAt, source) VALUES ('c12', 1000, 'USER')")
        db.execSQL("INSERT INTO watchlist_models (canonicalModelId, addedAt, note) VALUES ('c12', 1000, NULL)")
        db.execSQL("INSERT INTO recent_models (canonicalModelId, lastUsedAt, useCount) VALUES ('c12', 2000, 1)")
        db.execSQL("INSERT INTO provider_notices (noticeId, providerId, observedAt, expiresAt, source, evidenceId, summary, ref) VALUES ('notice-12', 'p12', 2000, NULL, 'OFFICIAL', NULL, 'fixture summary', NULL)")
        db.execSQL("INSERT INTO provider_events (eventId, providerId, kind, observedAt, source, evidenceId, detail) VALUES ('event-12', 'p12', 'RECOVERY', 2100, 'OBSERVED', NULL, NULL)")
        db.execSQL("INSERT INTO price_events (eventId, scopeKey, fromState, toState, fromInput, toInput, fromOutput, toOutput, observedAt, source, evidenceId) VALUES ('price-ev-12', 'scope-12', NULL, NULL, NULL, '0.2', NULL, NULL, 2200, 'OFFICIAL', NULL)")
        db.execSQL("INSERT INTO quota_events (eventId, scopeKey, fromState, toState, observedAt, source, evidenceId) VALUES ('quota-ev-12', 'scope-12', 'QUOTA_AVAILABLE', 'QUOTA_LOW', 2300, 'OBSERVED', NULL)")
    }

    /** 最小代表：provider／account／credential／endpoint／offering／my_models／sample／evidence 等。 */
    private fun seedMinimalLegacyV11(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO providers (providerId,name,officialHealth,observedHealth,checkinUrl,requiresDailyCheckin) VALUES ('p12','Seek','UNKNOWN','HEALTHY','https://fixture.invalid/checkin',1)")
        db.execSQL("INSERT INTO accounts (accountId,providerId) VALUES ('a12','p12')")
        db.execSQL("INSERT INTO credentials (credentialId,accountId,label,enabled) VALUES ('c12','a12','safe reference',1)")
        db.execSQL("INSERT INTO endpoints (endpointId,providerId,baseUrl,endpointUrl,protocol) VALUES ('e12','p12','https://fixture.invalid/v1','https://fixture.invalid/v1/chat/completions','CHAT_COMPLETIONS')")
        db.execSQL("INSERT INTO offerings (id,providerId,endpointId,modelId,manual,source) VALUES ('offering-12','p12','e12','fixture-model',1,'MANUAL')")
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState) VALUES ('availability-12','offering-12','a12','c12','UNKNOWN','UNKNOWN')")
        db.execSQL("INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) VALUES ('legacy-evidence-12','legacy-sample-12','p12','fixture-model','SUCCESS',200,1200,'PLAYGROUND',1)")
        db.execSQL("INSERT INTO samples (id,providerId,accountId,safeCredentialId,endpointId,modelId,operation,source,startedAt,streaming,usageEstimated,outcome,retryCount,rateLimitHeaders,priceState,quotaState,inputTokens,outputTokens) VALUES ('legacy-sample-12','p12','a12','c12','e12','fixture-model','CHAT_COMPLETIONS','PLAYGROUND',1000,1,0,'SUCCESS',0,'','FREE_NOW','UNKNOWN',9007199254740993,7)")
        db.execSQL("INSERT INTO checkins (providerId,date,completedAt,source,accountId) VALUES ('p12','2026-09-27',17,'USER','a12')")
        db.execSQL("INSERT INTO price_snapshots (id,providerId,accountId,credentialId,modelId,\"group\",endpointId,operation,observedAt,input,output,fixedRequest,fixedRequestKnownAbsent,cacheRead,cacheWrite,source,evidenceId) VALUES ('legacy-price-12','p12','a12','c12','fixture-model',NULL,'e12','CHAT_COMPLETIONS',1300,'0.000000000000000000123456789',NULL,NULL,0,NULL,NULL,'USER','legacy-evidence-12')")
        db.execSQL("INSERT INTO quota_snapshots (id,providerId,accountId,credentialId,modelId,\"group\",endpointId,operation,observedAt,state,source,evidenceId) VALUES ('legacy-quota-12','p12','a12',NULL,'fixture-model','','e12','CHAT_COMPLETIONS',1400,NULL,'PROBE','legacy-evidence-12')")
        db.execSQL("INSERT INTO my_models (providerId,providerModelId,addedAt,source) VALUES ('p12','fixture-model',1500,'USER')")
    }

    /**
     * 新 perf 兩表：空表已由上層斷言；此處釘住欄序／型別／nullable／PK、
     * 唯一索引欄序、FK RESTRICT 與 Entity 吻合。
     */
    private fun assertPerfTablesTyped(db: SupportSQLiteDatabase) {
        assertEquals(
            mapOf(
                "evidenceId" to Triple("TEXT", 1, 1),
                "scopeKey" to Triple("TEXT", 1, 0),
                "kind" to Triple("TEXT", 1, 0),
                "observedAt" to Triple("INTEGER", 1, 0),
                "empty" to Triple("INTEGER", 1, 0),
                "status" to Triple("TEXT", 1, 0),
                "providerVersion" to Triple("TEXT", 0, 0),
                "httpStatus" to Triple("INTEGER", 0, 0),
                "modelId" to Triple("TEXT", 0, 0),
                "groupName" to Triple("TEXT", 0, 0),
                "hours" to Triple("INTEGER", 1, 0),
                "accountId" to Triple("TEXT", 0, 0),
                "credentialId" to Triple("TEXT", 0, 0),
                "avgTtftMs" to Triple("INTEGER", 0, 0),
                "avgLatencyMs" to Triple("INTEGER", 0, 0),
                "successRate" to Triple("REAL", 0, 0),
                "avgTps" to Triple("REAL", 0, 0),
                "recentSuccessRates" to Triple("TEXT", 0, 0),
                "seriesSchema" to Triple("TEXT", 0, 0),
                "seriesJson" to Triple("TEXT", 0, 0),
                "seriesPointCount" to Triple("INTEGER", 0, 0),
            ),
            tableColumns(db, "provider_perf_facts"),
        )
        assertEquals(
            mapOf(
                "scopeKey" to Triple("TEXT", 1, 1),
                "kind" to Triple("TEXT", 1, 2),
                "latestEvidenceId" to Triple("TEXT", 1, 0),
                "latestObservedAt" to Triple("INTEGER", 1, 0),
                "lastGoodEvidenceId" to Triple("TEXT", 0, 0),
                "lastGoodObservedAt" to Triple("INTEGER", 0, 0),
            ),
            tableColumns(db, "provider_perf_heads"),
        )
        assertEquals(
            listOf("scopeKey", "kind", "observedAt"),
            indexColumns(db, "index_provider_perf_facts_scopeKey_kind_observedAt"),
        )
        db.query("PRAGMA foreign_key_list(`provider_perf_facts`)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("evidence", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("evidenceId", cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")))
            assertEquals("RESTRICT", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            assertEquals("NO ACTION", cursor.getString(cursor.getColumnIndexOrThrow("on_update")))
            assertFalse(cursor.moveToNext())
        }
        db.query("PRAGMA foreign_key_list(`provider_perf_heads`)").use {
            assertFalse(it.moveToFirst())
        }
    }

    private fun tables(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").use {
            while (it.moveToNext()) add(it.getString(0))
        }
    }

    private fun schemaObjects(db: SupportSQLiteDatabase): Map<String, String?> = buildMap {
        db.query("SELECT name, sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").use {
            while (it.moveToNext()) put(it.getString(0), if (it.isNull(1)) null else it.getString(1))
        }
    }

    private fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> = buildList {
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                if (cursor.isNull(it)) null else cursor.getString(it)
            })
        }
    }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Map<String, Triple<String, Int, Int>> = buildMap {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                put(
                    cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    Triple(
                        cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                    ),
                )
            }
        }
    }

    private fun indexColumns(db: SupportSQLiteDatabase, index: String): List<String> = buildList {
        db.query("PRAGMA index_info(`$index`)").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private fun assertUniqueIndex(db: SupportSQLiteDatabase, table: String, index: String, columns: List<String>) {
        assertEquals(columns, indexColumns(db, index))
        var unique = -1
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == index) {
                    unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                }
            }
        }
        assertEquals("$index must be UNIQUE", 1, unique)
    }

    private companion object {
        const val OLD_AVAILABILITY_UNIQUE_INDEX = "index_credential_availability_offering_account_credential"
        const val NEW_AVAILABILITY_UNIQUE_INDEX = "index_credential_availability_offering_account_credential_group"
    }
}
