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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v13 → v14 真 upgrade 驗證（CR-003／B3；ACTIVE_TASK CR-003 ports；PRODUCT_SPEC §6／§10／§18）。
 *
 * - 以已納管、committed 的 13.json 建 legacy v13 庫，seed 全部 37 表各一列；
 *   availability 每個 offering×account×credential tuple 只一列（符合 v13 舊三欄唯一鍵，
 *   同 tuple 兩個 group 在 v13 不可能存在），全部皆 test-only fixture、非產品資料。
 * - 關閉 v13 後以正式 `AtlasDatabase.create`（DB14 已註冊 [MIGRATION_13_14]）開庫，
 *   驗實際 Room migration 與 generated schema；不依賴尚未 export 的 14.json、不手寫假 JSON。
 * - 文件化差異只允許 availability：`group` nullable 原值保留（null／空字串／Unicode／大小寫
 *   不混、不 trim），`groupIdentity` 由 `availabilityGroupIdentity(group)` 回填
 *   （null→'n'、非 null→'v'+原值），舊三欄 UNIQUE 索引退役、新四欄 UNIQUE 索引上任。
 * - 其餘 36 entity 的 DDL／索引／FK／rows 一字不動；upgraded schema 與 fresh generated v14
 *   逐物件比對（migration 端新索引僅少 `IF NOT EXISTS`，正規化此語意等價差異）；
 *   `PRAGMA foreign_key_check` 乾淨、FK RESTRICT 仍生效；無 destructive migration／不關 FK／不刪列。
 * - DAO ABORT／identity 正規化細節另由 group identity 專用測試覆蓋；本檔只驗 upgrade 後真實 storage 契約。
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration13To14Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    @Test
    fun migrate13To14_preservesAllRowsAndOtherEntities_addsGroupIdentity_retiresOldUniqueIndex() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-13-14-group-identity.db"
        context.deleteDatabase(name)
        val legacyTables: Set<String>
        val legacySchema: Map<String, String?>
        val legacyRows: Map<String, List<List<String?>>>
        val legacyForeignKeys: Map<String, Set<List<String?>>>
        helper.createDatabase(name, 13).use { old ->
            seedFullLegacyV13(old)
            legacyTables = tables(old)
            legacySchema = schemaObjects(old)
            legacyRows = legacyTables.associateWith { rows(old, it) }
            legacyForeignKeys = legacyTables.associateWith { foreignKeys(old, it).toSet() }
            assertEquals(37, legacyTables.size)
            assertTrue(legacyTables.contains("provider_account_usage_facts"))
            assertEquals(
                listOf("offeringId", "accountId", "credentialId"),
                indexColumns(old, OLD_AVAILABILITY_UNIQUE_INDEX),
            )
            assertFalse(indexExists(old, NEW_AVAILABILITY_UNIQUE_INDEX))
        }
        var room: AtlasDatabase? = null
        try {
            // DB14 工廠已註冊 MIGRATION_13_14；刻意走正式 create，不另列 migration。
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(16, sql.version)
            // v14 不加任何表；CR-011 v15 只加 snapshot_slice_heads（additive-only）。
            assertEquals(legacyTables + "snapshot_slice_heads" + workspaceSyncTables, tables(sql))
            // 36 entity 逐物件保留；availability 只多 documented 欄、換 intended 索引。
            val currentSchema = schemaObjects(sql)
            for ((objectName, ddl) in legacySchema) {
                when (objectName) {
                    OLD_AVAILABILITY_UNIQUE_INDEX -> Unit // 退役，改驗下方新四欄索引。
                    AVAILABILITY_TABLE -> {
                        val original = requireNotNull(ddl)
                        val pkIndex = original.indexOf(", PRIMARY KEY(")
                        assertTrue("availability DDL 應含 PRIMARY KEY：$original", pkIndex >= 0)
                        val expected = original.substring(0, pkIndex) +
                            ", `groupIdentity` TEXT NOT NULL DEFAULT 'n'" + original.substring(pkIndex)
                        assertEquals(objectName, expected, currentSchema[objectName])
                    }
                    else -> assertEquals(objectName, ddl, currentSchema[objectName])
                }
            }
            assertEquals(
                legacySchema.keys - OLD_AVAILABILITY_UNIQUE_INDEX + NEW_AVAILABILITY_UNIQUE_INDEX +
                    setOf("snapshot_slice_heads", "index_snapshot_slice_heads_kind"),
                currentSchema.keys,
            )
            assertFalse(currentSchema.containsKey(OLD_AVAILABILITY_UNIQUE_INDEX))
            assertUniqueIndex(sql, AVAILABILITY_TABLE, NEW_AVAILABILITY_UNIQUE_INDEX, AVAILABILITY_UNIQUE_KEY)
            // groupIdentity 型別／NOT NULL／default 'n' 釘住；group 仍 nullable、原值保留。
            val availabilityColumns = columnsOf(sql, AVAILABILITY_TABLE)
            assertEquals(Column("TEXT", true, 0, "'n'"), availabilityColumns["groupIdentity"])
            assertEquals(Column("TEXT", false, 0), availabilityColumns["group"])
            // 全表 rows：除 availability 多 groupIdentity 欄外，逐列全等（surrogate id／metadata 保留）。
            val identityIndex = columnNames(sql, AVAILABILITY_TABLE).indexOf("groupIdentity")
            assertTrue(identityIndex > 0)
            for ((table, before) in legacyRows) {
                val after = rows(sql, table)
                if (table == AVAILABILITY_TABLE) {
                    assertEquals(
                        table,
                        before,
                        after.map { row -> row.take(identityIndex) + row.drop(identityIndex + 1) },
                    )
                } else {
                    assertEquals(table, before, after)
                }
            }
            // null／空字串／Unicode／大小寫 identity 回填與 raw 原值保留。
            for ((id, rawGroup) in SEEDED_AVAILABILITY_GROUPS) {
                assertEquals(rawGroup, rawValue(sql, AVAILABILITY_TABLE, "group", id))
                assertEquals(
                    availabilityGroupIdentity(rawGroup),
                    rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", id),
                )
                val entity = opened.availabilityDao().getById(id)!!
                assertEquals(rawGroup, entity.group)
                assertEquals(availabilityGroupIdentity(rawGroup), entity.groupIdentity)
            }
            // 歷史 samples／evidence／snapshot／usage／perf／price／quota／metadata 精確值保留。
            val sample = opened.sampleDao().getById("legacy-sample-13")!!
            assertEquals(9_007_199_254_740_993L, sample.inputTokens)
            assertEquals("FREE_NOW", sample.priceState)
            assertEquals("CHAT_COMPLETIONS", sample.operation)
            assertEquals("legacy-evidence-13", opened.evidenceDao().byRequest("legacy-sample-13")!!.id)
            assertEquals("0.000000000000000000123456789", opened.priceDao().getById("legacy-price-13")!!.input)
            assertNull(opened.priceDao().getById("legacy-price-13")!!.group)
            assertEquals("", opened.quotaDao().getById("legacy-quota-13")!!.group)
            assertNull(opened.quotaDao().getById("legacy-quota-13")!!.state)
            assertEquals(120L, opened.providerPerfDao().fact("legacy-evidence-13")!!.avgLatencyMs)
            val usage = opened.providerAccountUsageDao().fact("legacy-evidence-13")!!
            assertEquals(9_007_199_254_740_993L, usage.requestCountRaw)
            assertEquals("v1.0.0-rc.25", usage.providerVersion)
            assertEquals("legacy-evidence-13", opened.providerAccountUsageDao().head(usage.scopeKey, usage.kind)!!.latestEvidenceId)
            assertEquals("Seek", opened.providerDao().getById("p13")!!.name)
            assertEquals("metadata note", opened.credentialDao().getById("c13")!!.note)
            assertEquals("legacy-group", opened.credentialDao().getById("c13")!!.group)
            assertEquals("fixture-model", opened.offeringDao().getById("off13-a")!!.providerModelId)
            assertNotNull(opened.myModelsDao().all().firstOrNull { it.providerId == "p13" })
            // FK 全部與 v13 相同（availability 三個 RESTRICT 仍指向 offerings／accounts／credentials）。
            for ((table, before) in legacyForeignKeys) {
                assertEquals(table, before, foreignKeys(sql, table).toSet())
            }
            // Fresh generated v14（Room 直接建庫）vs upgraded DDL：逐物件一致；
            // migration 端新索引不含 IF NOT EXISTS，僅正規化此語意等價差異。
            val fresh = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals(
                    normalizedSchemaObjects(fresh.openHelper.writableDatabase),
                    normalizedSchemaObjects(sql),
                )
            } finally {
                fresh.close()
            }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    @Test
    fun afterUpgrade_distinctGroupsCoexistOnSameLegacyTuple_duplicatesAbort_andRestrictStillHolds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-13-14-group-coexist.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 13).use { old ->
            seedMinimalLegacyV13(old)
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(16, sql.version)
            val dao = opened.availabilityDao()
            // 舊列回填（同一 offering／account／key tuple 在 v13 只有一列）。
            assertEquals("vvip", dao.getById("availability-vip")!!.groupIdentity)
            assertEquals("n", dao.getById("availability-null")!!.groupIdentity)
            // 同一舊 tuple 新增第二個 group：升級後可並存，exact scope 讀取互不混淆。
            dao.insert(
                CredentialAvailabilityEntity(
                    id = "availability-vip2",
                    offeringId = "off13-a",
                    accountId = "a13",
                    credentialId = "c13",
                    priceState = "PARTIALLY_FREE",
                    quotaState = "QUOTA_LOW",
                    group = "vip2",
                ),
            )
            val sameTuple = dao.byCredential("c13").filter { it.offeringId == "off13-a" }
            assertEquals(2, sameTuple.size)
            assertEquals("PAID", sameTuple.first { it.group == "vip" }.priceState)
            assertEquals("PARTIALLY_FREE", sameTuple.first { it.group == "vip2" }.priceState)
            assertEquals("availability-vip", dao.byExactScope("off13-a", "a13", "c13", "vip").single().id)
            assertEquals("availability-vip2", dao.byExactScope("off13-a", "a13", "c13", "vip2").single().id)
            assertTrue(dao.byExactScope("off13-a", "a13", "c13", "VIP").isEmpty())
            // duplicate NULL／duplicate 同 group：新的四欄 unique 鍵 ABORT，舊列不動。
            expectAbort {
                dao.insert(
                    CredentialAvailabilityEntity(
                        id = "dup-null",
                        offeringId = "off13-b",
                        accountId = "a13",
                        credentialId = "c13",
                        group = null,
                    ),
                )
            }
            expectAbort {
                dao.insert(
                    CredentialAvailabilityEntity(
                        id = "dup-vip",
                        offeringId = "off13-a",
                        accountId = "a13",
                        credentialId = "c13",
                        group = "vip",
                    ),
                )
            }
            // 6 列舊 seed＋1 列 vip2；兩個 ABORT 沒有留下任何列。
            assertEquals(7, dao.byCredential("c13").size)
            assertNull(dao.getById("dup-null"))
            assertNull(dao.getById("dup-vip"))
            assertEquals("PAID", dao.getById("availability-vip")!!.priceState)
            assertEquals("FREE_NOW", dao.getById("availability-null")!!.priceState)
            assertEquals("vip", dao.getById("availability-vip")!!.group)
            assertEquals("vvip", rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", "availability-vip"))
            // FK RESTRICT 在升級後仍生效（availability 的三個 parent 不可刪）。
            expectAbort { sql.execSQL("DELETE FROM offerings WHERE id = 'off13-a'") }
            expectAbort { sql.execSQL("DELETE FROM accounts WHERE accountId = 'a13'") }
            expectAbort { sql.execSQL("DELETE FROM credentials WHERE credentialId = 'c13'") }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrate13To14_missingLegacyIndex_recoverableUpgrade_identicalEndState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-13-14-malformed-missing-index.db"
        context.deleteDatabase(name)
        val legacyTables: Set<String>
        val legacySchema: Map<String, String?>
        val legacyRows: Map<String, List<List<String?>>>
        val legacyForeignKeys: Map<String, Set<List<String?>>>
        helper.createDatabase(name, 13).use { old ->
            seedFullLegacyV13(old)
            legacyTables = tables(old)
            legacySchema = schemaObjects(old)
            legacyRows = legacyTables.associateWith { rows(old, it) }
            legacyForeignKeys = legacyTables.associateWith { foreignKeys(old, it).toSet() }
            assertEquals(37, legacyTables.size)
            assertEquals(
                listOf("offeringId", "accountId", "credentialId"),
                indexColumns(old, OLD_AVAILABILITY_UNIQUE_INDEX),
            )
            old.execSQL("DROP INDEX `" + OLD_AVAILABILITY_UNIQUE_INDEX + "`")
            assertFalse(indexExists(old, OLD_AVAILABILITY_UNIQUE_INDEX))
        }
        var room: AtlasDatabase? = null
        try {
            // 缺舊索引的畸形 v13：DROP IF EXISTS 容忍後必須可開庫（舊碼此處拋 SQLiteException → Room open 拋 IllegalStateException）。
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(16, sql.version)
            assertEquals(legacyTables + "snapshot_slice_heads" + workspaceSyncTables, tables(sql))
            val currentSchema = schemaObjects(sql)
            for ((objectName, ddl) in legacySchema) {
                when (objectName) {
                    OLD_AVAILABILITY_UNIQUE_INDEX -> Unit
                    AVAILABILITY_TABLE -> {
                        val original = requireNotNull(ddl)
                        val pkIndex = original.indexOf(", PRIMARY KEY(")
                        assertTrue("availability DDL 應含 PRIMARY KEY：$original", pkIndex >= 0)
                        val expected = original.substring(0, pkIndex) +
                            ", `groupIdentity` TEXT NOT NULL DEFAULT 'n'" + original.substring(pkIndex)
                        assertEquals(objectName, expected, currentSchema[objectName])
                    }
                    else -> assertEquals(objectName, ddl, currentSchema[objectName])
                }
            }
            assertEquals(
                legacySchema.keys - OLD_AVAILABILITY_UNIQUE_INDEX + NEW_AVAILABILITY_UNIQUE_INDEX +
                    setOf("snapshot_slice_heads", "index_snapshot_slice_heads_kind"),
                currentSchema.keys,
            )
            assertFalse(currentSchema.containsKey(OLD_AVAILABILITY_UNIQUE_INDEX))
            assertUniqueIndex(sql, AVAILABILITY_TABLE, NEW_AVAILABILITY_UNIQUE_INDEX, AVAILABILITY_UNIQUE_KEY)
            val availabilityColumns = columnsOf(sql, AVAILABILITY_TABLE)
            assertEquals(Column("TEXT", true, 0, "'n'"), availabilityColumns["groupIdentity"])
            assertEquals(Column("TEXT", false, 0), availabilityColumns["group"])
            val identityIndex = columnNames(sql, AVAILABILITY_TABLE).indexOf("groupIdentity")
            assertTrue(identityIndex > 0)
            for ((table, before) in legacyRows) {
                val after = rows(sql, table)
                if (table == AVAILABILITY_TABLE) {
                    assertEquals(
                        table,
                        before,
                        after.map { row -> row.take(identityIndex) + row.drop(identityIndex + 1) },
                    )
                } else {
                    assertEquals(table, before, after)
                }
            }
            for ((id, rawGroup) in SEEDED_AVAILABILITY_GROUPS) {
                assertEquals(rawGroup, rawValue(sql, AVAILABILITY_TABLE, "group", id))
                assertEquals(
                    availabilityGroupIdentity(rawGroup),
                    rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", id),
                )
                val entity = opened.availabilityDao().getById(id)!!
                assertEquals(rawGroup, entity.group)
                assertEquals(availabilityGroupIdentity(rawGroup), entity.groupIdentity)
            }
            for ((table, before) in legacyForeignKeys) {
                assertEquals(table, before, foreignKeys(sql, table).toSet())
            }
            val fresh = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals(
                    normalizedSchemaObjects(fresh.openHelper.writableDatabase),
                    normalizedSchemaObjects(sql),
                )
            } finally {
                fresh.close()
            }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            expectAbort {
                opened.availabilityDao().insert(
                    CredentialAvailabilityEntity(
                        id = "dup-malformed-vip",
                        offeringId = "off13-a",
                        accountId = "a13",
                        credentialId = "c13",
                        group = "vip",
                    ),
                )
            }
            assertNull(opened.availabilityDao().getById("dup-malformed-vip"))
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrate13To14_missingLegacyIndex_withRealDuplicates_failClosedPreserved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-13-14-malformed-dup-failclosed.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 13).use { old ->
            seedMinimalLegacyV13(old)
            old.execSQL("DROP INDEX `" + OLD_AVAILABILITY_UNIQUE_INDEX + "`")
            assertFalse(indexExists(old, OLD_AVAILABILITY_UNIQUE_INDEX))
            old.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\") VALUES ('dup-vip-same','off13-a','a13','c13','PAID','QUOTA_AVAILABLE','vip')")
            old.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\") VALUES ('dup-vip2-distinct','off13-a','a13','c13','PARTIALLY_FREE','QUOTA_LOW','vip2')")
        }
        var failedRoom: AtlasDatabase? = null
        try {
            val result = runCatching {
                val r = AtlasDatabase.create(context, name)
                failedRoom = r
                r.openHelper.writableDatabase
                r
            }
            assertTrue("真重複必須 fail-closed（四欄 CREATE UNIQUE abort）", result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            var cur: Throwable? = error
            var seenConstraint = false
            var seenUnique = false
            while (cur != null) {
                if (cur is SQLiteConstraintException) seenConstraint = true
                if (cur.message?.contains("UNIQUE", ignoreCase = true) == true) seenUnique = true
                cur = cur.cause
            }
            assertTrue("必須是 UNIQUE constraint 失敗：$error", seenConstraint)
            assertTrue("錯誤鏈必須含 UNIQUE：$error", seenUnique)
            runCatching { failedRoom?.close() }
            failedRoom = null
            val dbPath = context.getDatabasePath(name).absolutePath
            android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { raw ->
                raw.query("credential_availability", arrayOf("id"), "offeringId=? AND accountId=? AND credentialId=?", arrayOf("off13-a", "a13", "c13"), null, null, "id").use { c ->
                    val ids = buildList {
                        while (c.moveToNext()) add(c.getString(0))
                    }
                    assertTrue(ids.contains("availability-vip"))
                    assertTrue(ids.contains("dup-vip-same"))
                    assertTrue(ids.contains("dup-vip2-distinct"))
                }
                raw.rawQuery("PRAGMA table_info(`credential_availability`)", null).use { c ->
                    val cols = buildList {
                        while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
                    }
                    assertFalse(cols.contains("groupIdentity"))
                }
                raw.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(NEW_AVAILABILITY_UNIQUE_INDEX)).use { c ->
                    assertFalse(c.moveToFirst())
                }
                raw.rawQuery("PRAGMA user_version", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(13, c.getInt(0))
                }
            }
        } finally {
            runCatching { failedRoom?.close() }
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrate13To14_missingLegacyIndex_identityRuleExactness() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-13-14-malformed-identity.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 13).use { old ->
            seedMinimalLegacyV13(old)
            old.execSQL("DROP INDEX `" + OLD_AVAILABILITY_UNIQUE_INDEX + "`")
            assertFalse(indexExists(old, OLD_AVAILABILITY_UNIQUE_INDEX))
        }
        var room: AtlasDatabase? = null
        try {
            val opened = AtlasDatabase.create(context, name)
            room = opened
            val sql = opened.openHelper.writableDatabase
            assertEquals(16, sql.version)
            assertUniqueIndex(sql, AVAILABILITY_TABLE, NEW_AVAILABILITY_UNIQUE_INDEX, AVAILABILITY_UNIQUE_KEY)
            assertEquals("n", rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", "availability-null"))
            assertEquals("v", rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", "availability-empty"))
            assertEquals("vvip", rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", "availability-vip"))
            assertEquals("vVIP", rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", "availability-VIP"))
            assertEquals("v免费", rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", "availability-cjk-simplified"))
            assertEquals("v免費", rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", "availability-cjk-traditional"))
            assertNull(rawValue(sql, AVAILABILITY_TABLE, "group", "availability-null"))
            assertEquals("", rawValue(sql, AVAILABILITY_TABLE, "group", "availability-empty"))
            assertEquals("vip", rawValue(sql, AVAILABILITY_TABLE, "group", "availability-vip"))
            assertEquals("VIP", rawValue(sql, AVAILABILITY_TABLE, "group", "availability-VIP"))
            opened.availabilityDao().insert(
                CredentialAvailabilityEntity(
                    id = "availability-vip2-identity",
                    offeringId = "off13-a",
                    accountId = "a13",
                    credentialId = "c13",
                    priceState = "PARTIALLY_FREE",
                    quotaState = "QUOTA_LOW",
                    group = "vip2",
                ),
            )
            assertEquals("vvip2", rawValue(sql, AVAILABILITY_TABLE, "groupIdentity", "availability-vip2-identity"))
            expectAbort {
                opened.availabilityDao().insert(
                    CredentialAvailabilityEntity(
                        id = "dup-identity-vip",
                        offeringId = "off13-a",
                        accountId = "a13",
                        credentialId = "c13",
                        group = "vip",
                    ),
                )
            }
            assertNull(opened.availabilityDao().getById("dup-identity-vip"))
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            runCatching { room?.close() }
            context.deleteDatabase(name)
        }
    }

    // ---- seeds（全部 test-only fixture；不使用任何 secret-like 名稱） ----

    /** 全部 v13（37）表各一列；availability 六種 group 態各一列、tuple 互異。 */
    private fun seedFullLegacyV13(db: SupportSQLiteDatabase) {
        seedMinimalLegacyV13(db)
        db.execSQL("INSERT INTO onboarding_snapshots (workflowId,updatedAt,snapshotJson) VALUES ('workflow-13',1500,'{}')")
        db.execSQL("INSERT INTO onboarding_commits (workflowId,providerId,committedAt) VALUES ('workflow-13','p13',1600)")
        db.execSQL("INSERT INTO provider_configurations (providerId,workflowId,authMode,snapshotJson) VALUES ('p13','workflow-13','BEARER','{}')")
        db.execSQL("INSERT INTO onboarding_batches (batchId,updatedAt,snapshotJson) VALUES ('batch-13',1700,'{}')")
        db.execSQL("INSERT INTO snapshot_facts (evidenceId,scopeKey,kind,observedAt,state,status,confidence) VALUES ('legacy-evidence-13','scope-13','PRICE',2000,'UNKNOWN','OK','OFFICIAL')")
        db.execSQL("INSERT INTO snapshot_heads (scopeKey,kind,latestEvidenceId,latestObservedAt) VALUES ('scope-13','PRICE','legacy-evidence-13',2000)")
        db.execSQL("INSERT INTO canonical_models (canonicalModelId, normalizedName, displayName, createdAt, updatedAt, version) VALUES ('c13', 'fixture-model', 'Fixture', 1, 1, 1)")
        db.execSQL("INSERT INTO provider_model_aliases (aliasId, providerId, providerModelId, canonicalModelId, source, confidence, verified, observedAt, version, evidenceId) VALUES ('alias:1:p13:fixture-model', 'p13', 'fixture-model', 'c13', 'METADATA', 'MEDIUM', 0, 2, 1, NULL)")
        db.execSQL("INSERT INTO model_classifications (id, canonicalModelId, source, confidence, primaryCategory, observedAt, version) VALUES ('classification:c13:MANUAL', 'c13', 'MANUAL', 'LOW', 'CODING', 3, 1)")
        db.execSQL("INSERT INTO classification_capabilities (classificationId, capability) VALUES ('classification:c13:MANUAL', 'CODING')")
        db.execSQL("INSERT INTO classification_modalities (classificationId, modality) VALUES ('classification:c13:MANUAL', 'TEXT_INPUT')")
        db.execSQL("INSERT INTO classification_endpoint_types (classificationId, endpointType) VALUES ('classification:c13:MANUAL', 'CHAT_COMPLETIONS')")
        db.execSQL("INSERT INTO external_observations (observationId, source, externalModelId, canonicalModelId, payloadJson, fetchedAt, ttlMillis, confidence) VALUES ('obs-13', 'fixture-source', 'ext-m', NULL, '{\"hint\":\"fixture\"}', 1000, 1000, 'LOW')")
        db.execSQL("INSERT INTO external_mappings (mappingId, externalId, canonicalModelId, source, confidence, verified, version, observedAt) VALUES ('map-13', 'ext-m1', NULL, 'fixture-source', 'LOW', 0, 1, 2000)")
        db.execSQL("INSERT INTO favorite_models (canonicalModelId, addedAt, source) VALUES ('c13', 1000, 'USER')")
        db.execSQL("INSERT INTO watchlist_models (canonicalModelId, addedAt, note) VALUES ('c13', 1000, NULL)")
        db.execSQL("INSERT INTO recent_models (canonicalModelId, lastUsedAt, useCount) VALUES ('c13', 2000, 1)")
        db.execSQL("INSERT INTO provider_notices (noticeId, providerId, observedAt, expiresAt, source, evidenceId, summary, ref) VALUES ('notice-13', 'p13', 2000, NULL, 'OFFICIAL', NULL, 'fixture summary', NULL)")
        db.execSQL("INSERT INTO provider_events (eventId, providerId, kind, observedAt, source, evidenceId, detail) VALUES ('event-13', 'p13', 'RECOVERY', 2100, 'OBSERVED', NULL, NULL)")
        db.execSQL("INSERT INTO price_events (eventId, scopeKey, fromState, toState, fromInput, toInput, fromOutput, toOutput, observedAt, source, evidenceId) VALUES ('price-ev-13', 'scope-13', NULL, NULL, NULL, '0.2', NULL, NULL, 2200, 'OFFICIAL', NULL)")
        db.execSQL("INSERT INTO quota_events (eventId, scopeKey, fromState, toState, observedAt, source, evidenceId) VALUES ('quota-ev-13', 'scope-13', 'QUOTA_AVAILABLE', 'QUOTA_LOW', 2300, 'OBSERVED', NULL)")
        db.execSQL("INSERT INTO provider_perf_facts (evidenceId, scopeKey, kind, observedAt, empty, status, providerVersion, httpStatus, modelId, groupName, hours, accountId, credentialId, avgTtftMs, avgLatencyMs, successRate, avgTps, recentSuccessRates, seriesSchema, seriesJson, seriesPointCount) VALUES ('legacy-evidence-13', 'perf-scope-13', 'SUMMARY', 2000, 0, 'OK', 'v1.0.0-rc.25', 200, 'fixture-model', NULL, 168, NULL, NULL, NULL, 120, 99.5, 10.0, NULL, NULL, NULL, NULL)")
        db.execSQL("INSERT INTO provider_perf_heads (scopeKey, kind, latestEvidenceId, latestObservedAt, lastGoodEvidenceId, lastGoodObservedAt) VALUES ('perf-scope-13', 'SUMMARY', 'legacy-evidence-13', 2000, NULL, NULL)")
        db.execSQL("INSERT INTO provider_account_usage_facts (evidenceId, scopeKey, kind, providerId, accountId, credentialId, observedAt, status, providerVersion, httpStatus, source, requestCountRaw, counterSemantics, accountEndpointObserved) VALUES ('legacy-evidence-13', 'usage-scope-13', 'PROVIDER_ACCOUNT_USAGE', 'p13', 'a13', 'safe-ref-only', 2000, 'OK', 'v1.0.0-rc.25', 200, 'newapi/api/user/self', 9007199254740993, 'UNSPECIFIED', 1)")
        db.execSQL("INSERT INTO provider_account_usage_heads (scopeKey, kind, latestEvidenceId, latestObservedAt, lastGoodEvidenceId, lastGoodObservedAt) VALUES ('usage-scope-13', 'PROVIDER_ACCOUNT_USAGE', 'legacy-evidence-13', 2000, NULL, NULL)")
    }

    /** 最小 scope＋六種 group 態的 availability rows（同 tuple 只一列，符合 v13 舊唯一鍵）。 */
    private fun seedMinimalLegacyV13(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO providers (providerId,name,officialHealth,observedHealth,checkinUrl,requiresDailyCheckin) VALUES ('p13','Seek','UNKNOWN','HEALTHY','https://fixture.invalid/checkin',1)")
        db.execSQL("INSERT INTO accounts (accountId,providerId) VALUES ('a13','p13')")
        db.execSQL("INSERT INTO credentials (credentialId,accountId,label,enabled,note,\"group\",priority,authMode,isSecret) VALUES ('c13','a13','safe reference',1,'metadata note','legacy-group',1,'ACCOUNT',1)")
        db.execSQL("INSERT INTO endpoints (endpointId,providerId,baseUrl,endpointUrl,protocol) VALUES ('e13','p13','https://fixture.invalid/v1','https://fixture.invalid/v1/chat/completions','CHAT_COMPLETIONS')")
        for (suffix in listOf("a", "b", "c", "d", "e", "f")) {
            db.execSQL("INSERT INTO offerings (id,providerId,endpointId,modelId,manual,source) VALUES ('off13-$suffix','p13','e13','fixture-model',1,'MANUAL')")
        }
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\") VALUES ('availability-vip','off13-a','a13','c13','PAID','QUOTA_AVAILABLE','vip')")
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\") VALUES ('availability-null','off13-b','a13','c13','FREE_NOW','UNKNOWN',NULL)")
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\") VALUES ('availability-empty','off13-c','a13','c13','UNKNOWN','UNKNOWN','')")
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\") VALUES ('availability-VIP','off13-d','a13','c13','UNKNOWN','UNKNOWN','VIP')")
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\") VALUES ('availability-cjk-simplified','off13-e','a13','c13','UNKNOWN','UNKNOWN','免费')")
        db.execSQL("INSERT INTO credential_availability (id,offeringId,accountId,credentialId,priceState,quotaState,\"group\") VALUES ('availability-cjk-traditional','off13-f','a13','c13','UNKNOWN','UNKNOWN','免費')")
        db.execSQL("INSERT INTO evidence (id,requestId,providerId,modelId,outcome,status,timestamp,source,version) VALUES ('legacy-evidence-13','legacy-sample-13','p13','fixture-model','SUCCESS',200,1200,'PLAYGROUND',1)")
        db.execSQL("INSERT INTO samples (id,providerId,accountId,safeCredentialId,endpointId,modelId,operation,source,startedAt,streaming,usageEstimated,outcome,retryCount,rateLimitHeaders,priceState,quotaState,inputTokens,outputTokens) VALUES ('legacy-sample-13','p13','a13','c13','e13','fixture-model','CHAT_COMPLETIONS','PLAYGROUND',1000,1,0,'SUCCESS',0,'','FREE_NOW','UNKNOWN',9007199254740993,7)")
        db.execSQL("INSERT INTO checkins (providerId,date,completedAt,source,accountId) VALUES ('p13','2026-09-27',17,'USER','a13')")
        db.execSQL("INSERT INTO price_snapshots (id,providerId,accountId,credentialId,modelId,\"group\",endpointId,operation,observedAt,input,output,fixedRequest,fixedRequestKnownAbsent,cacheRead,cacheWrite,source,evidenceId) VALUES ('legacy-price-13','p13','a13','c13','fixture-model',NULL,'e13','CHAT_COMPLETIONS',1300,'0.000000000000000000123456789',NULL,NULL,0,NULL,NULL,'USER','legacy-evidence-13')")
        db.execSQL("INSERT INTO quota_snapshots (id,providerId,accountId,credentialId,modelId,\"group\",endpointId,operation,observedAt,state,source,evidenceId) VALUES ('legacy-quota-13','p13','a13',NULL,'fixture-model','','e13','CHAT_COMPLETIONS',1400,NULL,'PROBE','legacy-evidence-13')")
        db.execSQL("INSERT INTO my_models (providerId,providerModelId,addedAt,source) VALUES ('p13','fixture-model',1500,'USER')")
    }

    private suspend fun expectAbort(block: suspend () -> Unit) {
        try {
            block()
            fail("expected SQLiteConstraintException (ABORT/RESTRICT)")
        } catch (_: SQLiteConstraintException) {
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

    /** Fresh／upgraded DDL 比對用：只消除 `${TABLE_NAME}` placeholder 與 `IF NOT EXISTS`。 */
    private fun normalizedSchemaObjects(db: SupportSQLiteDatabase): Map<String, String?> =
        schemaObjects(db).mapValues { (objectName, ddl) ->
            ddl?.replace("\${TABLE_NAME}", objectName)?.replace("IF NOT EXISTS ", "")
        }

    private fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> = buildList {
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                if (cursor.isNull(it)) null else cursor.getString(it)
            })
        }
    }

    private fun foreignKeys(db: SupportSQLiteDatabase, table: String): List<List<String?>> = buildList {
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                if (cursor.isNull(it)) null else cursor.getString(it)
            })
        }
    }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): Map<String, Column> = buildMap {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                put(
                    cursor.getString(cursor.getColumnIndexOrThrow("name")),
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

    private fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> = buildList {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private fun indexColumns(db: SupportSQLiteDatabase, index: String): List<String> = buildList {
        db.query("PRAGMA index_info(`$index`)").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private fun indexExists(db: SupportSQLiteDatabase, index: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(index)).use {
            it.moveToFirst()
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

    private fun rawValue(db: SupportSQLiteDatabase, table: String, column: String, id: String): String? =
        db.query("SELECT `$column` FROM `$table` WHERE id = ?", arrayOf(id)).use { cursor ->
            assertTrue("$table/$id 應存在", cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private data class Column(
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int,
        val defaultValue: String? = null,
    )

    private companion object {
        const val AVAILABILITY_TABLE = "credential_availability"
        const val OLD_AVAILABILITY_UNIQUE_INDEX = "index_credential_availability_offering_account_credential"
        const val NEW_AVAILABILITY_UNIQUE_INDEX = "index_credential_availability_offering_account_credential_group"
        val AVAILABILITY_UNIQUE_KEY = listOf("offeringId", "accountId", "credentialId", "groupIdentity")
        val SEEDED_AVAILABILITY_GROUPS: Map<String, String?> = linkedMapOf(
            "availability-null" to null,
            "availability-empty" to "",
            "availability-vip" to "vip",
            "availability-VIP" to "VIP",
            "availability-cjk-simplified" to "免费",
            "availability-cjk-traditional" to "免費",
        )
    }
}
