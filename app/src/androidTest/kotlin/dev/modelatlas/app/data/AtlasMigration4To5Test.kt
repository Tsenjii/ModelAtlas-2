package dev.modelatlas.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 使用已匯出的 v4 schema 建庫；Room 開庫執行 MIGRATION_4_5 並以編譯產生的
 * AtlasDatabase_Impl 驗證完整最新 schema，不要求測試 assets 已有 5.json。
 * 驗證鏈已延伸到 v12（v5 加 snapshot 兩表；v6 只加 snapshot_facts 四欄；
 * MIGRATION_6_7 只加 canonical／alias／分類六表＋offerings／availability 三欄；
 * MIGRATION_7_8 只加 external_observations／external_mappings 兩表；
 * MIGRATION_8_9 只加收藏三表＋providers／credentials 追加欄；
 * MIGRATION_9_10 只加 provider_notices／provider_events／price_events／quota_events 四表；
 * v12 perf、v13 usage 只加表，v14 只加 availability `groupIdentity` 並換四欄唯一索引）。
 * 所有資料均為本地 fixture。
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration4To5Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AtlasDatabase::class.java)

    @Test
    fun migrate4To5_validatesGeneratedSchema_addsOnlyTwoTablesAndPreservesEveryLegacyRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-4-5-snapshot-facts.db"
        context.deleteDatabase(name)
        var room: AtlasDatabase? = null
        try {
            val legacySchema: Map<String, String?>
            val legacyRows: Map<String, List<List<String?>>>
            val legacyTables: Set<String>
            helper.createDatabase(name, 4).use { old ->
                seedLegacy(old)
                legacyTables = tables(old)
                legacySchema = schemaObjects(old)
                legacyRows = legacyTables.associateWith { rows(old, it) }
                assertFalse(legacyTables.contains("snapshot_facts"))
                assertFalse(legacyTables.contains("snapshot_heads"))
            }
            val opened = Room.databaseBuilder(context, AtlasDatabase::class.java, name)
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                .allowMainThreadQueries()
                .build()
            room = opened
            // 強制正式開库，觸發 migration 與 generated schema validation。
            val sql = opened.openHelper.writableDatabase
            assertEquals(15, sql.version)
            val v7Tables = setOf(
                "canonical_models", "provider_model_aliases", "model_classifications",
                "classification_capabilities", "classification_modalities", "classification_endpoint_types",
            )
            val v8Tables = setOf("external_observations", "external_mappings")
            val v9Tables = setOf("favorite_models", "watchlist_models", "recent_models")
            val v10Tables = setOf("provider_notices", "provider_events", "price_events", "quota_events")
            val v11Tables = setOf("my_models")
            val v12Tables = setOf("provider_perf_facts", "provider_perf_heads")
            val v13Tables = setOf("provider_account_usage_facts", "provider_account_usage_heads")
            assertEquals(legacyTables + setOf("snapshot_facts", "snapshot_heads") + v7Tables + v8Tables + v9Tables + v10Tables + v11Tables + v12Tables + v13Tables + setOf("snapshot_slice_heads"), tables(sql))
            for (table in v12Tables + v13Tables) {
                sql.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("$table must start empty after additive upgrade", 0L, cursor.getLong(0))
                }
            }
            val currentSchema = schemaObjects(sql)
            for ((objectName, ddl) in legacySchema) {
                // v14 intended：舊三欄 availability unique 索引退役，改由下方四欄新索引驗證。
                if (objectName == OLD_AVAILABILITY_UNIQUE_INDEX) continue
                // v7 只加欄：兩張舊表的 DDL 差異必須精確等於追加的欄位定義尾段，
                // 其餘物件（表／索引）DDL 逐字相同（只加不改）。
                val appended = ADDED_COLUMN_DDL[objectName]
                val expected = if (appended != null) {
                    val nonNullDdl = requireNotNull(ddl) { "$objectName DDL 缺失" }
                    assertTrue("$objectName DDL 應以 ) 結尾", nonNullDdl.endsWith(")"))
                    // Room 產生的實際 DDL 把新 entity 欄放在 PRIMARY KEY 之前（見 7.json offerings createSql），
                    // 故 appended 插在 ", PRIMARY KEY(" 之前；無該子句才 fallback 尾拼。v4 既有約束語意不動。
                    val pkMarker = ", PRIMARY KEY("
                    val pkIndex = nonNullDdl.indexOf(pkMarker)
                    if (pkIndex >= 0) {
                        nonNullDdl.substring(0, pkIndex) + appended + nonNullDdl.substring(pkIndex)
                    } else {
                        nonNullDdl.dropLast(1) + appended + ")"
                    }
                } else {
                    ddl
                }
                assertEquals(objectName, expected, currentSchema[objectName])
            }
            assertFalse(currentSchema.containsKey(OLD_AVAILABILITY_UNIQUE_INDEX))
            assertUniqueIndex(
                sql,
                "credential_availability",
                NEW_AVAILABILITY_UNIQUE_INDEX,
                listOf("offeringId", "accountId", "credentialId", "groupIdentity"),
            )
            for ((table, before) in legacyRows) {
                val after = rows(sql, table)
                val added = ADDED_COLUMN_VALUES[table]
                assertEquals(
                    table,
                    if (added == null) before else before.map { it + added },
                    after,
                )
            }
            assertEquals(emptyList<List<String?>>(), rows(sql, "snapshot_facts"))
            assertEquals(emptyList<List<String?>>(), rows(sql, "snapshot_heads"))
            assertNewColumns(sql)
            assertAddedColumns(sql)
            assertFactIndex(sql)
            assertV7TablesEmptyAndTyped(sql)
            assertV8TablesEmptyAndTyped(sql)
            assertV9TablesEmptyAndTyped(sql)
            assertV10TablesEmptyAndTyped(sql)
            assertV11TablesEmptyAndTyped(sql)

            // Legacy Unknown/精確小數不能被升格為官方事實或補零。
            val price = opened.priceDao().getById("legacy-price")!!
            assertEquals("0.000000000000000000123456789", price.input)
            assertNull(price.output)
            assertNull(price.group)
            assertEquals("USER", price.source)
            assertNull(opened.quotaDao().getById("legacy-quota")!!.state)
            assertEquals("FREE_NOW", opened.sampleDao().getById("legacy-sample")!!.priceState)
            assertEquals(9_007_199_254_740_993L, opened.sampleDao().getById("legacy-sample")!!.inputTokens)
            assertEquals(17L, opened.checkinDao().get("p", "2026-09-27", "a")!!.completedAt)
            assertAddedColumnsWritable(opened)
            assertNewTablesWritableAndForeignKeyEnforced(opened)
            assertV7TablesWritableAndForeignKeyEnforced(opened)
            assertV8TablesWritable(opened)
            assertV9TablesWritable(opened)
            assertV10TablesWritable(opened)
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            room?.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * 追加欄位可經正式 entity 層讀寫：舊列 `hidden=false`（既有列未被升格為已驗證）、
     * `verificationEvidenceId=null`／`group=null`（未知＝null，不補零值）；
     * 新列可寫 `hidden=true` 與具名 `group`，priceState／quotaState 語意不變。
     */
    private suspend fun assertAddedColumnsWritable(db: AtlasDatabase) {
        val legacy = db.offeringDao().getById("offering")!!
        assertEquals(false, legacy.hidden)
        assertNull(legacy.verificationEvidenceId)
        assertNull(db.availabilityDao().getById("availability")!!.group)
        assertEquals("n", db.availabilityDao().getById("availability")!!.groupIdentity)
        db.offeringDao().insert(
            OfferingEntity(
                id = "offering-hidden",
                providerId = "p",
                endpointId = "e",
                providerModelId = "model-hidden",
                manual = true,
                source = "MANUAL",
                hidden = true,
                verificationEvidenceId = "sample:legacy-sample",
            ),
        )
        val hidden = db.offeringDao().getById("offering-hidden")!!
        assertEquals(true, hidden.hidden)
        assertEquals("sample:legacy-sample", hidden.verificationEvidenceId)
        assertNull(db.availabilityDao().getById("availability")!!.group)
    }

    private fun seedLegacy(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO providers (providerId,name,officialHealth,observedHealth,checkinUrl,requiresDailyCheckin) VALUES ('p','本地 fixture','UNKNOWN','HEALTHY','https://fixture.invalid/checkin',1)")
        db.execSQL("INSERT INTO accounts (accountId,providerId) VALUES ('a','p')")
        db.execSQL("INSERT INTO credentials (credentialId,accountId,label,enabled) VALUES ('c','a','safe reference',1)")
        db.execSQL("INSERT INTO endpoints (endpointId,providerId,baseUrl,endpointUrl,protocol) VALUES ('e','p','https://fixture.invalid/v1','https://fixture.invalid/v1/chat/completions','CHAT_COMPLETIONS')")
        db.execSQL("INSERT INTO offerings (id,providerId,endpointId,modelId,manual,source) VALUES ('offering','p','e','model',1,'MANUAL')")
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState) VALUES ('availability','offering','a','c','UNKNOWN','UNKNOWN')")
        db.execSQL("INSERT INTO samples (id,providerId,accountId,safeCredentialId,endpointId,modelId,operation,source,startedAt,streaming,usageEstimated,outcome,retryCount,rateLimitHeaders,priceState,quotaState,inputTokens,outputTokens) VALUES ('legacy-sample','p','a','c','e','model','CHAT_COMPLETIONS','PLAYGROUND',1000,1,0,'SUCCESS',0,'','FREE_NOW','UNKNOWN',9007199254740993,7)")
        db.execSQL("INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) VALUES ('legacy-evidence','legacy-sample','p','model','SUCCESS',200,1200,'PLAYGROUND',1)")
        db.execSQL("INSERT INTO checkins (providerId,date,completedAt,source,accountId) VALUES ('p','2026-09-27',17,'USER','a')")
        db.execSQL("INSERT INTO price_snapshots (id,providerId,accountId,credentialId,modelId,\"group\",endpointId,operation,observedAt,input,output,fixedRequest,fixedRequestKnownAbsent,cacheRead,cacheWrite,source,evidenceId) VALUES ('legacy-price','p','a','c','model',NULL,'e','CHAT_COMPLETIONS',1300,'0.000000000000000000123456789',NULL,NULL,0,NULL,NULL,'USER','legacy-evidence')")
        db.execSQL("INSERT INTO quota_snapshots (id,providerId,accountId,credentialId,modelId,\"group\",endpointId,operation,observedAt,state,source,evidenceId) VALUES ('legacy-quota','p','a',NULL,'model','','e','CHAT_COMPLETIONS',1400,NULL,'PROBE','legacy-evidence')")
        db.execSQL("INSERT INTO onboarding_snapshots (workflowId,updatedAt,snapshotJson) VALUES ('workflow',1500,'{}')")
        db.execSQL("INSERT INTO onboarding_commits (workflowId,providerId,committedAt) VALUES ('workflow','p',1600)")
        db.execSQL("INSERT INTO provider_configurations (providerId,workflowId,authMode,snapshotJson) VALUES ('p','workflow','BEARER','{}')")
        db.execSQL("INSERT INTO onboarding_batches (batchId,updatedAt,snapshotJson) VALUES ('batch',1700,'{}')")
    }

    private fun assertNewColumns(db: SupportSQLiteDatabase) {
        val requiredFact = setOf("evidenceId", "scopeKey", "kind", "observedAt", "state", "status", "confidence")
        val integerFact = setOf("observedAt", "statusObservedAt", "remainingRaw", "usedRaw", "grantedRaw", "unlimited", "expiresAtRaw", "isEstimated")
        val allFact = requiredFact + setOf("snapshotId", "providerVersion", "pricingVersion", "itemPricingVersion",
            "quotaPerUnit", "groupRatio", "statusObservedAt", "remainingRaw", "usedRaw", "grantedRaw", "unlimited",
            "expiresAtRaw", "expiryMeaning", "expressionHash", "priceUnit",
            "quotaScopeKind", "quotaProjectId", "quotaSlice", "isEstimated")
        assertColumns(db, "snapshot_facts", allFact.associateWith {
            Column(if (it in integerFact) "INTEGER" else "TEXT", it in requiredFact, if (it == "evidenceId") 1 else 0)
        })
        assertColumns(db, "snapshot_heads", mapOf(
            "scopeKey" to Column("TEXT", true, 1),
            "kind" to Column("TEXT", true, 2),
            "latestEvidenceId" to Column("TEXT", true, 0),
            "latestObservedAt" to Column("INTEGER", true, 0),
            "lastGoodSnapshotId" to Column("TEXT", false, 0),
            "lastGoodObservedAt" to Column("INTEGER", false, 0),
        ))
    }

    private data class Column(
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int,
        val defaultValue: String? = null,
    )

    /**
     * 舊表追加欄位的 DDL 尾段（`ALTER TABLE ADD COLUMN` 由 SQLite 插在 PRIMARY KEY 子句前）：
     * v7 offerings／providers／credentials 三張，加 v14 availability `groupIdentity`；
     * 舊表除這些 documented 追加外一律逐字相同，故差異比對只需比對這段字串。
     */
    private val ADDED_COLUMN_DDL: Map<String, String> = mapOf(
        "offerings" to ", `hidden` INTEGER NOT NULL DEFAULT 0, `verificationEvidenceId` TEXT",
        "credential_availability" to ", `group` TEXT, `groupIdentity` TEXT NOT NULL DEFAULT 'n'",
        "providers" to ", `officialHealthAt` INTEGER NOT NULL DEFAULT 0, `observedHealthAt` INTEGER NOT NULL DEFAULT 0",
        "credentials" to ", `note` TEXT, `group` TEXT, `priority` INTEGER NOT NULL DEFAULT 0, `authMode` TEXT NOT NULL DEFAULT 'UNKNOWN', `headerName` TEXT, `headerPrefix` TEXT, `isSecret` INTEGER NOT NULL DEFAULT 1, `lastSuccessAt` INTEGER, `lastFailureAt` INTEGER",
    )

    /** 追加欄位在既有列上的實際值：hidden 預設 0（可見、未驗證），其餘 null（未知）；v14 groupIdentity null→'n'。 */
    private val ADDED_COLUMN_VALUES: Map<String, List<String?>> = mapOf(
        "offerings" to listOf("0", null),
        "credential_availability" to listOf(null, "n"),
        "providers" to listOf("0", "0"),
        "credentials" to listOf(null, null, "0", "UNKNOWN", null, null, "1", null, null),
    )

    /**
     * MODEL-006／MODEL-007 追加三欄＋v14 availability `groupIdentity`：欄序、型別、nullable、
     * default 與 pk 位置全釘住。`hidden` 為 NOT NULL DEFAULT 0（既有列不得被升格為已驗證），
     * 其餘可空＝未知；`groupIdentity` NOT NULL DEFAULT 'n'（未知 identity，不與空字串混淆）。
     */
    private fun assertAddedColumns(db: SupportSQLiteDatabase) {
        val offerings = columnsOf(db, "offerings")
        assertEquals(
            listOf("id", "providerId", "endpointId", "modelId", "manual", "source", "hidden", "verificationEvidenceId"),
            offerings.keys.toList(),
        )
        assertEquals(Column("TEXT", true, 1), offerings.getValue("id"))
        assertEquals(Column("TEXT", true, 0), offerings.getValue("providerId"))
        assertEquals(Column("TEXT", true, 0), offerings.getValue("endpointId"))
        assertEquals(Column("TEXT", true, 0), offerings.getValue("modelId"))
        assertEquals(Column("INTEGER", true, 0), offerings.getValue("manual"))
        assertEquals(Column("TEXT", true, 0), offerings.getValue("source"))
        assertEquals(Column("INTEGER", true, 0, "0"), offerings.getValue("hidden"))
        assertEquals(Column("TEXT", false, 0), offerings.getValue("verificationEvidenceId"))
        val availability = columnsOf(db, "credential_availability")
        assertEquals(
            listOf("id", "offeringId", "accountId", "credentialId", "priceState", "quotaState", "group", "groupIdentity"),
            availability.keys.toList(),
        )
        assertEquals(Column("TEXT", true, 1), availability.getValue("id"))
        assertEquals(Column("TEXT", true, 0), availability.getValue("offeringId"))
        assertEquals(Column("TEXT", true, 0), availability.getValue("accountId"))
        assertEquals(Column("TEXT", true, 0), availability.getValue("credentialId"))
        assertEquals(Column("TEXT", true, 0), availability.getValue("priceState"))
        assertEquals(Column("TEXT", true, 0), availability.getValue("quotaState"))
        assertEquals(Column("TEXT", false, 0), availability.getValue("group"))
        assertEquals(Column("TEXT", true, 0, "'n'"), availability.getValue("groupIdentity"))
    }

    /** 讀 PRAGMA table_info（含 dflt_value），不對預設值做任何假設。 */
    private fun columnsOf(db: SupportSQLiteDatabase, table: String): Map<String, Column> = buildMap {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                put(
                    name,
                    Column(
                        cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                        cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                        if (cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))) {
                            null
                        } else {
                            cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                        },
                    ),
                )
            }
        }
    }

    private fun assertColumns(db: SupportSQLiteDatabase, table: String, expected: Map<String, Column>) {
        val actual = mutableMapOf<String, Column>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                actual[name] = Column(cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                    cursor.getInt(cursor.getColumnIndexOrThrow("pk")))
                assertTrue("$table.$name 不應猜預設值", cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value")))
            }
        }
        assertEquals(table, expected, actual)
    }

    private fun assertFactIndex(db: SupportSQLiteDatabase) {
        val columns = mutableListOf<String>()
        db.query("PRAGMA index_info(`index_snapshot_facts_scopeKey_kind_observedAt`)").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        assertEquals(listOf("scopeKey", "kind", "observedAt"), columns)
        db.query("PRAGMA foreign_key_list(`snapshot_facts`)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("evidence", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("evidenceId", cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")))
            assertEquals("RESTRICT", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            assertEquals("NO ACTION", cursor.getString(cursor.getColumnIndexOrThrow("on_update")))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertV7TablesEmptyAndTyped(db: SupportSQLiteDatabase) {
        val v7 = listOf(
            "canonical_models", "provider_model_aliases", "model_classifications",
            "classification_capabilities", "classification_modalities", "classification_endpoint_types",
        )
        for (table in v7) assertEquals(table, emptyList<List<String?>>(), rows(db, table))
        assertColumns(db, "canonical_models", mapOf(
            "canonicalModelId" to Column("TEXT", true, 1),
            "normalizedName" to Column("TEXT", true, 0),
            "displayName" to Column("TEXT", false, 0),
            "createdAt" to Column("INTEGER", true, 0),
            "updatedAt" to Column("INTEGER", true, 0),
            "version" to Column("INTEGER", true, 0),
        ))
        assertColumns(db, "provider_model_aliases", mapOf(
            "aliasId" to Column("TEXT", true, 1),
            "providerId" to Column("TEXT", true, 0),
            "providerModelId" to Column("TEXT", true, 0),
            "canonicalModelId" to Column("TEXT", true, 0),
            "source" to Column("TEXT", true, 0),
            "confidence" to Column("TEXT", true, 0),
            "verified" to Column("INTEGER", true, 0),
            "observedAt" to Column("INTEGER", true, 0),
            "version" to Column("INTEGER", true, 0),
            "evidenceId" to Column("TEXT", false, 0),
        ))
        assertColumns(db, "model_classifications", mapOf(
            "id" to Column("TEXT", true, 1),
            "canonicalModelId" to Column("TEXT", true, 0),
            "source" to Column("TEXT", true, 0),
            "confidence" to Column("TEXT", true, 0),
            "primaryCategory" to Column("TEXT", false, 0),
            "observedAt" to Column("INTEGER", true, 0),
            "version" to Column("INTEGER", true, 0),
        ))
        assertColumns(db, "classification_capabilities", mapOf(
            "classificationId" to Column("TEXT", true, 1),
            "capability" to Column("TEXT", true, 2),
        ))
        assertColumns(db, "classification_modalities", mapOf(
            "classificationId" to Column("TEXT", true, 1),
            "modality" to Column("TEXT", true, 2),
        ))
        assertColumns(db, "classification_endpoint_types", mapOf(
            "classificationId" to Column("TEXT", true, 1),
            "endpointType" to Column("TEXT", true, 2),
        ))
        assertUniqueIndex(db, "canonical_models", "index_canonical_models_normalizedName", listOf("normalizedName"))
        assertUniqueIndex(db, "provider_model_aliases", "index_provider_model_aliases_provider_model", listOf("providerId", "providerModelId"))
        assertUniqueIndex(db, "model_classifications", "index_model_classifications_canonical_source", listOf("canonicalModelId", "source"))
        assertForeignKey(db, "provider_model_aliases", "canonicalModelId", "canonical_models", "canonicalModelId")
        assertForeignKey(db, "model_classifications", "canonicalModelId", "canonical_models", "canonicalModelId")
        assertForeignKey(db, "classification_capabilities", "classificationId", "model_classifications", "id")
        assertForeignKey(db, "classification_modalities", "classificationId", "model_classifications", "id")
        assertForeignKey(db, "classification_endpoint_types", "classificationId", "model_classifications", "id")
    }

    private fun assertIndexColumns(db: SupportSQLiteDatabase, index: String, columns: List<String>) {
        val actual = mutableListOf<String>()
        db.query("PRAGMA index_info(`$index`)").use { cursor ->
            while (cursor.moveToNext()) actual += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        assertEquals(index, columns, actual)
    }

    /**
     * P7-B2 MINOR：舊 `assertUniqueIndex` 只讀 `PRAGMA index_info`（欄序），
     * 未驗 unique 旗標卻套用到非 unique 索引。現拆分：欄序一律經 [assertIndexColumns]；
     * 唯一性經 `PRAGMA index_list(table)` 的 `unique` 欄驗證（1＝UNIQUE）。
     */
    private fun assertUniqueIndex(db: SupportSQLiteDatabase, table: String, index: String, columns: List<String>) {
        assertIndexColumns(db, index, columns)
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

    private fun assertForeignKey(db: SupportSQLiteDatabase, table: String, from: String, refTable: String, to: String) {
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(refTable, cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals(from, cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals(to, cursor.getString(cursor.getColumnIndexOrThrow("to")))
            assertEquals("RESTRICT", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            assertFalse(cursor.moveToNext())
        }
    }

    private suspend fun assertV7TablesWritableAndForeignKeyEnforced(db: AtlasDatabase) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO canonical_models (canonicalModelId, normalizedName, displayName, createdAt, updatedAt, version) VALUES ('c7', 'c7', 'Fixture', 1, 1, 1)")
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("INSERT INTO provider_model_aliases (aliasId, providerId, providerModelId, canonicalModelId, source, confidence, verified, observedAt, version, evidenceId) VALUES ('alias:1:p:3:m07', 'p', 'm07', 'missing', 'METADATA', 'MEDIUM', 0, 2, 1, NULL)")
        }
        sql.execSQL("INSERT INTO provider_model_aliases (aliasId, providerId, providerModelId, canonicalModelId, source, confidence, verified, observedAt, version, evidenceId) VALUES ('alias:1:p:2:m7', 'p', 'm7', 'c7', 'METADATA', 'MEDIUM', 0, 2, 1, NULL)")
        val alias = db.providerModelAliasDao().get("p", "m7")!!
        assertEquals("c7", alias.canonicalModelId)
        assertEquals(false, alias.verified)
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("DELETE FROM canonical_models WHERE canonicalModelId = 'c7'")
        }
        sql.execSQL("INSERT INTO model_classifications (id, canonicalModelId, source, confidence, primaryCategory, observedAt, version) VALUES ('classification:c7:MANUAL', 'c7', 'MANUAL', 'LOW', 'CODING', 3, 1)")
        sql.execSQL("INSERT INTO classification_capabilities (classificationId, capability) VALUES ('classification:c7:MANUAL', 'CODING')")
        val details = CanonicalStore(db).getClassification("c7", "MANUAL")!!
        assertEquals("CODING", details.classification.primaryCategory)
        assertEquals(setOf("CODING"), details.capabilities)
    }

    /**
     * v8（LMS-003／LMS-004）只加 external_observations／external_mappings 兩表：
     * migration 後兩表為空、欄序／型別／nullable／PK 位置全釘住、unique＋一般索引全在、
     * 無 FK（seed 可先於 canonical 存在）。舊列全等已由上方 legacyRows 比對覆蓋。
     */
    private fun assertV8TablesEmptyAndTyped(db: SupportSQLiteDatabase) {
        assertEquals(emptyList<List<String?>>(), rows(db, "external_observations"))
        assertEquals(emptyList<List<String?>>(), rows(db, "external_mappings"))
        assertColumns(db, "external_observations", mapOf(
            "observationId" to Column("TEXT", true, 1),
            "source" to Column("TEXT", true, 0),
            "externalModelId" to Column("TEXT", true, 0),
            "canonicalModelId" to Column("TEXT", false, 0),
            "payloadJson" to Column("TEXT", true, 0),
            "fetchedAt" to Column("INTEGER", true, 0),
            "ttlMillis" to Column("INTEGER", true, 0),
            "confidence" to Column("TEXT", true, 0),
        ))
        assertColumns(db, "external_mappings", mapOf(
            "mappingId" to Column("TEXT", true, 1),
            "externalId" to Column("TEXT", true, 0),
            "canonicalModelId" to Column("TEXT", false, 0),
            "source" to Column("TEXT", true, 0),
            "confidence" to Column("TEXT", true, 0),
            "verified" to Column("INTEGER", true, 0),
            "version" to Column("INTEGER", true, 0),
            "observedAt" to Column("INTEGER", true, 0),
        ))
        assertUniqueIndex(db, "external_observations", "index_external_observations_source_model_fetched", listOf("source", "externalModelId", "fetchedAt"))
        assertUniqueIndex(db, "external_mappings", "index_external_mappings_external_source", listOf("externalId", "source"))
        assertIndexColumns(db, "index_external_observations_canonical", listOf("canonicalModelId"))
        assertIndexColumns(db, "index_external_observations_source", listOf("source"))
        assertIndexColumns(db, "index_external_observations_fetched", listOf("fetchedAt"))
        assertIndexColumns(db, "index_external_mappings_canonical", listOf("canonicalModelId"))
        db.query("PRAGMA foreign_key_list(`external_observations`)").use { assertFalse(it.moveToFirst()) }
        db.query("PRAGMA foreign_key_list(`external_mappings`)").use { assertFalse(it.moveToFirst()) }
    }

    /**
     * v9（MODEL-005／PROV-006）三新表＋providers／credentials 追加欄：
     * 三表為空、欄序／型別／nullable／PK／索引全釘住、無 FK；追加欄以正確 default 存在
     * （舊列值已由上方 legacyRows 全等比對覆蓋）。
     */
    private fun assertV9TablesEmptyAndTyped(db: SupportSQLiteDatabase) {
        assertEquals(emptyList<List<String?>>(), rows(db, "favorite_models"))
        assertEquals(emptyList<List<String?>>(), rows(db, "watchlist_models"))
        assertEquals(emptyList<List<String?>>(), rows(db, "recent_models"))
        assertColumns(db, "favorite_models", mapOf(
            "canonicalModelId" to Column("TEXT", true, 1),
            "addedAt" to Column("INTEGER", true, 0),
            "source" to Column("TEXT", true, 0),
        ))
        assertColumns(db, "watchlist_models", mapOf(
            "canonicalModelId" to Column("TEXT", true, 1),
            "addedAt" to Column("INTEGER", true, 0),
            "note" to Column("TEXT", false, 0),
        ))
        assertColumns(db, "recent_models", mapOf(
            "canonicalModelId" to Column("TEXT", true, 1),
            "lastUsedAt" to Column("INTEGER", true, 0),
            "useCount" to Column("INTEGER", true, 0),
        ))
        assertIndexColumns(db, "index_favorite_models_added", listOf("addedAt"))
        assertIndexColumns(db, "index_watchlist_models_added", listOf("addedAt"))
        assertIndexColumns(db, "index_recent_models_last_used", listOf("lastUsedAt"))
        db.query("PRAGMA foreign_key_list(`favorite_models`)").use { assertFalse(it.moveToFirst()) }
        db.query("PRAGMA foreign_key_list(`watchlist_models`)").use { assertFalse(it.moveToFirst()) }
        db.query("PRAGMA foreign_key_list(`recent_models`)").use { assertFalse(it.moveToFirst()) }
        assertTrue(columnsOf(db, "providers").containsKey("officialHealthAt"))
        assertTrue(columnsOf(db, "providers").containsKey("observedHealthAt"))
        for (col in listOf("note", "group", "priority", "authMode", "headerName", "headerPrefix", "isSecret", "lastSuccessAt", "lastFailureAt")) {
            assertTrue("credentials 應含 $col", columnsOf(db, "credentials").containsKey(col))
        }
    }

    /** v9 三表經正式 store 層可讀寫；providers／credentials 新欄可寫、default 正確。 */
    private suspend fun assertV9TablesWritable(db: AtlasDatabase) {
        val favorites = FavoriteStore(db)
        assertTrue(favorites.setFavorite("gpt-4o", true, 1000L))
        assertTrue(favorites.isFavorite("gpt-4o"))
        favorites.recordUse("gpt-4o", 2000L)
        assertEquals(1, favorites.recentOf("gpt-4o")!!.useCount)
        val legacy = db.credentialDao().getById("c")!!
        // RV1：舊列 authMode 未知保持 UNKNOWN，不得冒充 API_KEY；未知不行經 API_KEY 篩出。
        assertEquals("UNKNOWN", legacy.authMode)
        assertTrue(legacy.isSecret)
        assertEquals(0, legacy.priority)
        assertNull(legacy.headerPrefix)
        assertEquals(1, db.providerDao().updateObservedHealth("p", "DEGRADED", 999L))
        val provider = db.providerDao().getById("p")!!
        assertEquals("DEGRADED", provider.observedHealth)
        assertEquals(999L, provider.observedHealthAt)
    }

    /**
     * v8 兩表經正式 entity 層可讀寫：canonicalModelId=null 保持 null（未知不猜）、
     * unique(source, externalModelId, fetchedAt) 與 unique(externalId, source) 強制執行、
     * 同一 externalId 跨來源各一列互不覆蓋。
     */
    private suspend fun assertV8TablesWritable(db: AtlasDatabase) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO external_observations (observationId, source, externalModelId, canonicalModelId, payloadJson, fetchedAt, ttlMillis, confidence) VALUES ('obs-1', 'fixture-source', 'ext-m', NULL, '{\"hint\":\"fixture\"}', 1000, 1000, 'LOW')")
        val obs = db.externalObservationDao().getById("obs-1")!!
        assertEquals("fixture-source", obs.source)
        assertEquals("ext-m", obs.externalModelId)
        assertNull(obs.canonicalModelId)
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("INSERT INTO external_observations (observationId, source, externalModelId, canonicalModelId, payloadJson, fetchedAt, ttlMillis, confidence) VALUES ('obs-dup', 'fixture-source', 'ext-m', NULL, '{}', 1000, 1000, 'LOW')")
        }
        sql.execSQL("INSERT INTO external_mappings (mappingId, externalId, canonicalModelId, source, confidence, verified, version, observedAt) VALUES ('map-1', 'ext-m1', NULL, 'fixture-source', 'LOW', 0, 1, 2000)")
        val mapping = db.externalMappingDao().resolve("ext-m1", "fixture-source")!!
        assertNull(mapping.canonicalModelId)
        assertEquals(false, mapping.verified)
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("INSERT INTO external_mappings (mappingId, externalId, canonicalModelId, source, confidence, verified, version, observedAt) VALUES ('map-dup', 'ext-m1', 'c7', 'fixture-source', 'MEDIUM', 0, 2, 3000)")
        }
        sql.execSQL("INSERT INTO external_mappings (mappingId, externalId, canonicalModelId, source, confidence, verified, version, observedAt) VALUES ('map-2', 'ext-m1', 'c7', 'other-source', 'MEDIUM', 0, 1, 2000)")
        assertEquals("c7", db.externalMappingDao().resolve("ext-m1", "other-source")!!.canonicalModelId)
        assertNull(db.externalMappingDao().resolve("ext-missing", "fixture-source"))
    }

    private suspend fun assertNewTablesWritableAndForeignKeyEnforced(db: AtlasDatabase) {
        val sql = db.openHelper.writableDatabase
        sql.query("PRAGMA foreign_keys").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        val insert = "INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,snapshotId,state,status,confidence,remainingRaw) VALUES (?, 'fixture-scope', 'ACCOUNT_QUOTA', 2000, NULL, 'UNKNOWN', 'OK', 'OFFICIAL', 9007199254740993)"
        assertThrows(SQLiteConstraintException::class.java) { sql.execSQL(insert, arrayOf<Any>("missing-evidence")) }
        sql.execSQL(insert, arrayOf<Any>("legacy-evidence"))
        val fact = db.snapshotFactDao().evidence("legacy-evidence")!!
        assertEquals(9_007_199_254_740_993L, fact.remainingRaw)
        assertNull(fact.snapshotId)
        assertNull(fact.providerVersion)
        assertNull(fact.usedRaw)
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("DELETE FROM evidence WHERE id = 'legacy-evidence'")
        }
        assertThrows(SQLiteConstraintException::class.java) { sql.execSQL(insert, arrayOf<Any>("legacy-evidence")) }
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity("fixture-scope", "ACCOUNT_QUOTA", "legacy-evidence", 2000, null, null))
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity("fixture-scope", "ACCOUNT_QUOTA", "legacy-evidence", 3000, null, null))
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity("fixture-scope", "MANUAL_QUOTA", "legacy-evidence", 4000, null, null))
        assertEquals(2, rows(sql, "snapshot_heads").size)
        assertEquals(3000L, db.snapshotFactDao().head("fixture-scope", "ACCOUNT_QUOTA")!!.latestObservedAt)
        assertEquals(4000L, db.snapshotFactDao().head("fixture-scope", "MANUAL_QUOTA")!!.latestObservedAt)
        assertEquals(listOf(fact), db.snapshotFactDao().history("fixture-scope", "ACCOUNT_QUOTA"))
    }

    /**
     * v10（X-018 事件層）只加 provider_notices／provider_events／price_events／quota_events 四表：
     * migration 後四表為空、欄序／型別／nullable／PK 位置全釘住、索引全在、無 FK
     * （notice／event 可先於 provider／evidence 存在，刪 provider 不連帶清歷史）。
     * 舊列全等已由上方 legacyRows 比對覆蓋。
     */
    private fun assertV10TablesEmptyAndTyped(db: SupportSQLiteDatabase) {
        assertEquals(emptyList<List<String?>>(), rows(db, "provider_notices"))
        assertEquals(emptyList<List<String?>>(), rows(db, "provider_events"))
        assertEquals(emptyList<List<String?>>(), rows(db, "price_events"))
        assertEquals(emptyList<List<String?>>(), rows(db, "quota_events"))
        assertColumns(db, "provider_notices", mapOf(
            "noticeId" to Column("TEXT", true, 1),
            "providerId" to Column("TEXT", true, 0),
            "observedAt" to Column("INTEGER", true, 0),
            "expiresAt" to Column("INTEGER", false, 0),
            "source" to Column("TEXT", true, 0),
            "evidenceId" to Column("TEXT", false, 0),
            "summary" to Column("TEXT", true, 0),
            "ref" to Column("TEXT", false, 0),
        ))
        assertColumns(db, "provider_events", mapOf(
            "eventId" to Column("TEXT", true, 1),
            "providerId" to Column("TEXT", true, 0),
            "kind" to Column("TEXT", true, 0),
            "observedAt" to Column("INTEGER", true, 0),
            "source" to Column("TEXT", true, 0),
            "evidenceId" to Column("TEXT", false, 0),
            "detail" to Column("TEXT", false, 0),
        ))
        assertColumns(db, "price_events", mapOf(
            "eventId" to Column("TEXT", true, 1),
            "scopeKey" to Column("TEXT", true, 0),
            "fromState" to Column("TEXT", false, 0),
            "toState" to Column("TEXT", false, 0),
            "fromInput" to Column("TEXT", false, 0),
            "toInput" to Column("TEXT", false, 0),
            "fromOutput" to Column("TEXT", false, 0),
            "toOutput" to Column("TEXT", false, 0),
            "observedAt" to Column("INTEGER", true, 0),
            "source" to Column("TEXT", true, 0),
            "evidenceId" to Column("TEXT", false, 0),
        ))
        assertColumns(db, "quota_events", mapOf(
            "eventId" to Column("TEXT", true, 1),
            "scopeKey" to Column("TEXT", true, 0),
            "fromState" to Column("TEXT", false, 0),
            "toState" to Column("TEXT", false, 0),
            "observedAt" to Column("INTEGER", true, 0),
            "source" to Column("TEXT", true, 0),
            "evidenceId" to Column("TEXT", false, 0),
        ))
        assertIndexColumns(db, "index_provider_notices_provider", listOf("providerId"))
        assertIndexColumns(db, "index_provider_notices_observed", listOf("observedAt"))
        assertIndexColumns(db, "index_provider_events_provider", listOf("providerId"))
        assertIndexColumns(db, "index_provider_events_observed", listOf("observedAt"))
        assertIndexColumns(db, "index_price_events_scope", listOf("scopeKey"))
        assertIndexColumns(db, "index_price_events_observed", listOf("observedAt"))
        assertIndexColumns(db, "index_quota_events_scope", listOf("scopeKey"))
        assertIndexColumns(db, "index_quota_events_observed", listOf("observedAt"))
        db.query("PRAGMA foreign_key_list(`provider_notices`)").use { assertFalse(it.moveToFirst()) }
        db.query("PRAGMA foreign_key_list(`provider_events`)").use { assertFalse(it.moveToFirst()) }
        db.query("PRAGMA foreign_key_list(`price_events`)").use { assertFalse(it.moveToFirst()) }
        db.query("PRAGMA foreign_key_list(`quota_events`)").use { assertFalse(it.moveToFirst()) }
    }

    /** v11（③-1 My Models membership）：只加 my_models 一表；空表、型別正確、無 FK。 */
    private fun assertV11TablesEmptyAndTyped(db: SupportSQLiteDatabase) {
        assertEquals(emptyList<List<String?>>(), rows(db, "my_models"))
        assertColumns(db, "my_models", mapOf(
            "providerId" to Column("TEXT", true, 1),
            "providerModelId" to Column("TEXT", true, 2),
            "addedAt" to Column("INTEGER", true, 0),
            "source" to Column("TEXT", true, 0),
        ))
        assertIndexColumns(db, "index_my_models_added", listOf("addedAt"))
        db.query("PRAGMA foreign_key_list(`my_models`)").use { assertFalse(it.moveToFirst()) }
    }

    /** v10 四表經正式 DAO 層可讀寫；跨 provider／scope 精確隔離（X-004），未知保持 null（X-001）。 */
    private suspend fun assertV10TablesWritable(db: AtlasDatabase) {
        db.providerNoticeDao().insert(
            ProviderNoticeEntity(
                noticeId = "notice-v10-1",
                providerId = "p",
                observedAt = 2000L,
                source = "OFFICIAL",
                summary = "fixture summary",
                ref = "legacy-evidence",
            ),
        )
        assertEquals(1, db.providerNoticeDao().count())
        assertEquals(1, db.providerNoticeDao().byProvider("p").size)
        assertEquals(0, db.providerNoticeDao().byProvider("p-missing").size)
        db.providerEventDao().insert(
            ProviderEventEntity(
                eventId = "provider-v10-1",
                providerId = "p",
                kind = "RECOVERY",
                observedAt = 2100L,
                source = "OBSERVED",
            ),
        )
        assertEquals(1, db.providerEventDao().count())
        assertEquals(0, db.providerEventDao().byProvider("p-missing").size)
        db.priceEventDao().insert(
            PriceEventEntity(
                eventId = "price-v10-1",
                scopeKey = "scope-v10",
                toInput = "0.2",
                observedAt = 2200L,
                source = "OFFICIAL",
            ),
        )
        assertEquals(1, db.priceEventDao().count())
        assertNull(db.priceEventDao().getById("price-v10-1")!!.fromInput)
        assertEquals(0, db.priceEventDao().history("scope-v10-missing").size)
        db.quotaEventDao().insert(
            QuotaEventEntity(
                eventId = "quota-v10-1",
                scopeKey = "scope-v10",
                fromState = "QUOTA_AVAILABLE",
                toState = "QUOTA_LOW",
                observedAt = 2300L,
                source = "OBSERVED",
            ),
        )
        assertEquals(1, db.quotaEventDao().count())
        assertEquals(0, db.quotaEventDao().history("scope-v10-missing").size)
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

    private companion object {
        const val OLD_AVAILABILITY_UNIQUE_INDEX = "index_credential_availability_offering_account_credential"
        const val NEW_AVAILABILITY_UNIQUE_INDEX = "index_credential_availability_offering_account_credential_group"
    }
}
