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
 * Room v12 → v13 真 upgrade 驗證（TRACEABILITY NINT-005A；Contract §2／§6.4／§23.3；
 * PRODUCT_SPEC §10／§12／§18；RESEARCH_POLICY）。
 *
 * - 以已納管 12.json 建 legacy v12 庫，seed 全部 v12 表各一列（含 snapshot／perf／
 *   evidence 等 preserved metadata；皆為本地 test-only fixture，非 live 產品資料）。
 * - 12→13 原契約以 committed 13.json 在 v13 驗證（全部原始 DDL／rows 斷言保持 v13）；
 *   latest target 已是 v14，v14 差異（availability groupIdentity／四欄索引）由
 *   [AtlasMigration13To14Test] 專測，本檔不重複也不放寬。
 * - 舊 rows／DDL 字字相同；新 provider_account_usage 兩表為空、型別／nullable／
 *   PK／indices／FK RESTRICT 與 Entity 吻合；原 samples／snapshot_facts／
 *   provider_perf 列數不增（來源分離）；新表經正式 [ProviderAccountUsageDao]
 *   可寫／可讀並綁 evidence FK；第二段以實際 Room 開到 v14 驗證資料仍保留。
 * - Store 的 counter semantics／last-good／guards 語意留給專用 store tests，
 *   本檔只驗 migration 與 typed DAO 邊界，不鏡像 production、不猜 store ports。
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration12To13Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    @Test
    fun migrate12To13_preservesEveryLegacyRowAndSchema_addsOnlyUsageTables() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-12-13-usage-schema.db"
        context.deleteDatabase(name)
        val legacySchema: Map<String, String?>
        val legacyRows: Map<String, List<List<String?>>>
        val legacyTables: Set<String>
        helper.createDatabase(name, 12).use { old ->
            seedFullLegacyV12(old)
            legacyTables = tables(old)
            legacySchema = schemaObjects(old)
            legacyRows = legacyTables.associateWith { rows(old, it) }
            assertFalse(legacyTables.contains("provider_account_usage_facts"))
            assertFalse(legacyTables.contains("provider_account_usage_heads"))
        }
        // 12→13 原契約：以 committed 13.json 在 v13 驗證；所有原始 schema／rows 斷言不變。
        helper.runMigrationsAndValidate(name, 13, true, MIGRATION_12_13).use { sql ->
            assertEquals(13, sql.version)
            // 只加兩表，不動既有表。
            assertEquals(
                legacyTables + setOf("provider_account_usage_facts", "provider_account_usage_heads"),
                tables(sql),
            )
            val currentSchema = schemaObjects(sql)
            for ((objectName, ddl) in legacySchema) {
                assertEquals(objectName, ddl, currentSchema[objectName])
            }
            for ((table, before) in legacyRows) {
                assertEquals(table, before, rows(sql, table))
            }
            // 新表為空、型別釘住、索引／FK 與 Entity 吻合。
            assertEquals(emptyList<List<String?>>(), rows(sql, "provider_account_usage_facts"))
            assertEquals(emptyList<List<String?>>(), rows(sql, "provider_account_usage_heads"))
            assertUsageTablesTyped(sql)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        var room: AtlasDatabase? = null
        try {
            // 第二段：實際 Room 由 v13 開到 v14，資料仍保留（v14 差異由 13→14 專測覆蓋）。
            val opened = Room.databaseBuilder(context, AtlasDatabase::class.java, name)
                .addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                .allowMainThreadQueries()
                .build()
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(15, sql.version)
            // Legacy metadata／snapshot／perf／evidence 精確值一字不動。
            assertEquals("Seek", opened.providerDao().getById("p12")!!.name)
            assertEquals("FREE_NOW", opened.sampleDao().getById("legacy-sample-12")!!.priceState)
            assertEquals(9_007_199_254_740_993L, opened.sampleDao().getById("legacy-sample-12")!!.inputTokens)
            assertEquals(120L, opened.providerPerfDao().fact("legacy-evidence-12")!!.avgLatencyMs)
            // v14 documented：null group 回填 identity 'n'，raw group 仍 null。
            val availability = opened.availabilityDao().getById("availability-12")!!
            assertNull(availability.group)
            assertEquals("n", availability.groupIdentity)
            // 來源分離：原 samples／snapshot_facts／provider_perf_facts 列數不增。
            assertEquals(legacyRows["samples"]!!.size, rows(sql, "samples").size)
            assertEquals(legacyRows["snapshot_facts"]!!.size, rows(sql, "snapshot_facts").size)
            assertEquals(legacyRows["provider_perf_facts"]!!.size, rows(sql, "provider_perf_facts").size)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migratedUsageTables_writableViaDaoAndSourceSeparated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-12-13-usage-writable.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 12).use { old ->
            seedMinimalLegacyV12(old)
        }
        var room: AtlasDatabase? = null
        try {
            val opened = Room.databaseBuilder(context, AtlasDatabase::class.java, name)
                .addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                .allowMainThreadQueries()
                .build()
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(15, sql.version)
            val samplesBefore = rows(sql, "samples").size
            val snapshotBefore = rows(sql, "snapshot_facts").size
            val perfBefore = rows(sql, "provider_perf_facts").size
            val kind = ProviderAccountUsageKind.PROVIDER_ACCOUNT_USAGE.name
            val scopeKey = providerAccountUsageScopeKey("p12", "a12")
            // 缺 evidence 的 fact 不得寫入（FK RESTRICT 前置）。
            var missingRejected = false
            try {
                opened.providerAccountUsageDao().insertFact(
                    usageFact(
                        evidenceId = "usage:missing-evidence",
                        scopeKey = scopeKey,
                        kind = kind,
                        requestCountRaw = 1L,
                        accountEndpointObserved = true,
                    ),
                )
            } catch (_: SQLiteConstraintException) {
                missingRejected = true
            }
            assertTrue(missingRejected)
            // 綁定 evidence 後可寫／可讀：精確 Long request count 與 null（Unknown≠0）皆原樣。
            opened.evidenceDao().insert(
                EvidenceEntity(
                    id = "usage:ev-good",
                    requestId = "usage:ev-good",
                    providerId = "p12",
                    modelId = "",
                    outcome = "OK",
                    status = 200,
                    timestamp = 2000L,
                    source = NewApiSnapshotStore.ACCOUNT_SOURCE,
                ),
            )
            opened.evidenceDao().insert(
                EvidenceEntity(
                    id = "usage:ev-unknown",
                    requestId = "usage:ev-unknown",
                    providerId = "p12",
                    modelId = "",
                    outcome = "UNKNOWN",
                    status = null,
                    timestamp = 2100L,
                    source = NewApiSnapshotStore.ACCOUNT_SOURCE,
                ),
            )
            opened.providerAccountUsageDao().insertFact(
                usageFact(
                    evidenceId = "usage:ev-good",
                    scopeKey = scopeKey,
                    kind = kind,
                    observedAt = 2000L,
                    status = "OK",
                    providerVersion = "v1.0.0-rc.25",
                    httpStatus = 200,
                    requestCountRaw = 9_007_199_254_740_993L,
                    accountEndpointObserved = true,
                ),
            )
            opened.providerAccountUsageDao().insertFact(
                usageFact(
                    evidenceId = "usage:ev-unknown",
                    scopeKey = scopeKey,
                    kind = kind,
                    observedAt = 2100L,
                    status = "UNKNOWN",
                    providerVersion = null,
                    httpStatus = null,
                    requestCountRaw = null,
                    accountEndpointObserved = false,
                ),
            )
            opened.providerAccountUsageDao().upsertHead(
                ProviderAccountUsageHeadEntity(
                    scopeKey = scopeKey,
                    kind = kind,
                    latestEvidenceId = "usage:ev-unknown",
                    latestObservedAt = 2100L,
                    lastGoodEvidenceId = "usage:ev-good",
                    lastGoodObservedAt = 2000L,
                ),
            )
            val good = opened.providerAccountUsageDao().fact("usage:ev-good")!!
            assertEquals(scopeKey, good.scopeKey)
            assertEquals(kind, good.kind)
            assertEquals("p12", good.providerId)
            assertEquals("a12", good.accountId)
            assertEquals("safe-ref-only", good.credentialId)
            assertEquals("UNSPECIFIED", good.counterSemantics)
            assertEquals("newapi/api/user/self", good.source)
            assertEquals(200, good.httpStatus)
            assertEquals(9_007_199_254_740_993L, good.requestCountRaw)
            assertTrue(good.accountEndpointObserved)
            val unknown = opened.providerAccountUsageDao().fact("usage:ev-unknown")!!
            assertNull(unknown.requestCountRaw)
            assertNull(unknown.providerVersion)
            assertNull(unknown.httpStatus)
            assertFalse(unknown.accountEndpointObserved)
            val history = opened.providerAccountUsageDao().history(scopeKey, kind)
            assertEquals(2, history.size)
            assertEquals("usage:ev-unknown", history[0].evidenceId)
            assertEquals("usage:ev-good", history[1].evidenceId)
            assertEquals("usage:ev-unknown", opened.providerAccountUsageDao().head(scopeKey, kind)!!.latestEvidenceId)
            assertEquals("usage:ev-good", opened.providerAccountUsageDao().head(scopeKey, kind)!!.lastGoodEvidenceId)
            // 父 evidence 被 fact 引用時不得刪除（RESTRICT）；原 samples／snapshot／perf 不增。
            var parentDeleteRejected = false
            try {
                sql.execSQL("DELETE FROM evidence WHERE id = 'usage:ev-good'")
            } catch (_: SQLiteConstraintException) {
                parentDeleteRejected = true
            }
            assertTrue(parentDeleteRejected)
            assertEquals(samplesBefore, rows(sql, "samples").size)
            assertEquals(snapshotBefore, rows(sql, "snapshot_facts").size)
            assertEquals(perfBefore, rows(sql, "provider_perf_facts").size)
            assertEquals("legacy-sample-12", opened.sampleDao().getById("legacy-sample-12")!!.id)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    private fun usageFact(
        evidenceId: String,
        scopeKey: String,
        kind: String,
        requestCountRaw: Long?,
        accountEndpointObserved: Boolean,
        observedAt: Long = 2000L,
        status: String = "OK",
        providerVersion: String? = null,
        httpStatus: Int? = null,
    ): ProviderAccountUsageFactEntity = ProviderAccountUsageFactEntity(
        evidenceId = evidenceId,
        scopeKey = scopeKey,
        kind = kind,
        providerId = "p12",
        accountId = "a12",
        credentialId = "safe-ref-only",
        observedAt = observedAt,
        status = status,
        providerVersion = providerVersion,
        httpStatus = httpStatus,
        source = NewApiSnapshotStore.ACCOUNT_SOURCE,
        requestCountRaw = requestCountRaw,
        counterSemantics = "UNSPECIFIED",
        accountEndpointObserved = accountEndpointObserved,
    )

    /** 全部 v12 表各一列：證明 upgrade 保 ALL 舊 rows／schema（皆 test-only fixture）。 */
    private fun seedFullLegacyV12(db: SupportSQLiteDatabase) {
        seedMinimalLegacyV12(db)
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
        db.execSQL("INSERT INTO provider_perf_facts (evidenceId, scopeKey, kind, observedAt, empty, status, providerVersion, httpStatus, modelId, groupName, hours, accountId, credentialId, avgTtftMs, avgLatencyMs, successRate, avgTps, recentSuccessRates, seriesSchema, seriesJson, seriesPointCount) VALUES ('legacy-evidence-12', 'perf-scope-12', 'SUMMARY', 2000, 0, 'OK', 'v1.0.0-rc.25', 200, 'fixture-model', NULL, 168, NULL, NULL, NULL, 120, 99.5, 10.0, NULL, NULL, NULL, NULL)")
        db.execSQL("INSERT INTO provider_perf_heads (scopeKey, kind, latestEvidenceId, latestObservedAt, lastGoodEvidenceId, lastGoodObservedAt) VALUES ('perf-scope-12', 'SUMMARY', 'legacy-evidence-12', 2000, NULL, NULL)")
    }

    /** 最小代表：provider／account／credential／endpoint／offering／my_models／sample／evidence 等。 */
    private fun seedMinimalLegacyV12(db: SupportSQLiteDatabase) {
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
     * 新 usage 兩表：空表已由上層斷言；此處釘住欄序／型別／nullable／PK、
     * 唯一索引欄序、FK RESTRICT 與 Entity 吻合。
     */
    private fun assertUsageTablesTyped(db: SupportSQLiteDatabase) {
        assertEquals(
            mapOf(
                "evidenceId" to Triple("TEXT", 1, 1),
                "scopeKey" to Triple("TEXT", 1, 0),
                "kind" to Triple("TEXT", 1, 0),
                "providerId" to Triple("TEXT", 1, 0),
                "accountId" to Triple("TEXT", 1, 0),
                "credentialId" to Triple("TEXT", 1, 0),
                "observedAt" to Triple("INTEGER", 1, 0),
                "status" to Triple("TEXT", 1, 0),
                "providerVersion" to Triple("TEXT", 0, 0),
                "httpStatus" to Triple("INTEGER", 0, 0),
                "source" to Triple("TEXT", 1, 0),
                "requestCountRaw" to Triple("INTEGER", 0, 0),
                "counterSemantics" to Triple("TEXT", 1, 0),
                "accountEndpointObserved" to Triple("INTEGER", 1, 0),
            ),
            tableColumns(db, "provider_account_usage_facts"),
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
            tableColumns(db, "provider_account_usage_heads"),
        )
        assertEquals(
            listOf("scopeKey", "kind", "observedAt"),
            indexColumns(db, "index_provider_account_usage_facts_scopeKey_kind_observedAt"),
        )
        db.query("PRAGMA foreign_key_list(`provider_account_usage_facts`)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("evidence", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("evidenceId", cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")))
            assertEquals("RESTRICT", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            assertEquals("NO ACTION", cursor.getString(cursor.getColumnIndexOrThrow("on_update")))
            assertFalse(cursor.moveToNext())
        }
        db.query("PRAGMA foreign_key_list(`provider_account_usage_heads`)").use {
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
}
