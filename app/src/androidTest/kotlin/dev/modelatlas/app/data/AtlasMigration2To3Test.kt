package dev.modelatlas.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2 → v3 實機驗證：以 repo 已納管的 `app/schemas/.../2.json` 建 v2 庫並塞入
 * 連線 + checkin + telemetry sample，再跑 MIGRATION_2_3。
 *
 * 驗收重點：v3 只加 `onboarding_snapshots` / `onboarding_commits` 兩表且初始為空、
 * 舊連線 / telemetry / checkin 全數保留、Room 正式開庫可讀寫。
 * 驗證鏈已延伸到 v14（經 v4→v5 snapshot 兩表、v6 加欄、v7 canonical／alias／分類、
 * v8 external_observations／external_mappings、v9 收藏三表＋providers／credentials 欄、
 * v10 事件四表、v12 perf、v13 usage 只加表，v14 availability 加 groupIdentity／換唯一索引；
 * 舊列全等，documented availability 欄位另驗）。
     * Fixture 只在 androidTest，不進產品資料。
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration2To3Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    /** Room 正式開庫路徑（AtlasDatabase.create 同一組 migration）：v2 → v3 後資料保留 + 新表可讀寫。 */
    @Test
    fun migrate2To3_roomOfficialOpen_keepsConnectionTelemetryCheckin_andNewTablesWritable() = runBlocking {
        val dbName = "mig-2-3-room.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        createSeededV2(dbName)

        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, dev.modelatlas.app.data.MIGRATION_15_16)
            .allowMainThreadQueries()
            .build()
        try {
            assertNewOnboardingTablesExistAndEmpty(db)
            assertOldConnectionPreserved(db)
            assertOldTelemetryPreserved(db)
            assertOldCheckinPreserved(db)
            assertOldQuotaSnapshotPreserved(db)
            assertV7AddedColumnsWithDefaults(db)
            assertV8TablesEmptyAndTyped(db)
            assertV10TablesEmptyAndTyped(db)
            assertNewTablesReadableAndWritable(db)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    /** MigrationTestHelper 驗證路徑：以 Room 產生的 v3 schema 全量比對，欄位 / PK / 索引都要對上。 */
    @Test
    fun migrate2To3_helperValidatesFullV3Schema() = runBlocking {
        val dbName = "mig-2-3-helper.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        createSeededV2(dbName)

        helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12).close()

        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, dev.modelatlas.app.data.MIGRATION_15_16)
            .allowMainThreadQueries()
            .build()
        try {
            assertNewOnboardingTablesExistAndEmpty(db)
            assertOldConnectionPreserved(db)
            assertOldTelemetryPreserved(db)
            assertOldCheckinPreserved(db)
            assertOldQuotaSnapshotPreserved(db)
            assertV7AddedColumnsWithDefaults(db)
            assertV8TablesEmptyAndTyped(db)
            assertV10TablesEmptyAndTyped(db)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    /** v2 庫由已納管 schema 2.json 建立，並塞入單一連線、簽到與一筆 FREE_NOW telemetry。 */
    private fun createSeededV2(dbName: String) {
        helper.createDatabase(dbName, 2).apply {
            seedV2Fixture(this)
            assertNull(
                "v2 schema 不應已有 onboarding_snapshots",
                tryQueryCountOrNull(this, "onboarding_snapshots"),
            )
            assertNull(
                "v2 schema 不應已有 onboarding_commits",
                tryQueryCountOrNull(this, "onboarding_commits"),
            )
            close()
        }
    }

    private fun seedV2Fixture(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO providers (providerId, name, officialHealth, observedHealth, checkinUrl, requiresDailyCheckin) VALUES ('p2', 'Seek', 'UNKNOWN', 'UNKNOWN', 'https://seekai.cc/checkin', 1)")
        db.execSQL("INSERT INTO accounts (accountId, providerId) VALUES ('a2', 'p2')")
        db.execSQL("INSERT INTO credentials (credentialId, accountId, label, enabled) VALUES ('c2', 'a2', '主要憑證', 1)")
        db.execSQL("INSERT INTO endpoints (endpointId, providerId, baseUrl, endpointUrl, protocol) VALUES ('e2', 'p2', 'https://seekai.cc/v1', 'https://seekai.cc/v1/chat/completions', 'CHAT_COMPLETIONS')")
        db.execSQL("INSERT INTO offerings (id, providerId, endpointId, modelId, manual, source) VALUES ('off-2', 'p2', 'e2', 'm2', 1, 'MANUAL')")
        db.execSQL("INSERT INTO credential_availability (id, offeringId, accountId, credentialId, priceState, quotaState) VALUES ('off-2', 'off-2', 'a2', 'c2', 'UNKNOWN', 'UNKNOWN')")
        db.execSQL("INSERT INTO samples (id, providerId, accountId, safeCredentialId, endpointId, modelId, canonicalModelId, \"group\", operation, source, startedAt, firstTokenAt, completedAt, streaming, ttftMs, totalLatencyMs, usageEstimated, httpStatus, outcome, retryCount, responseModelId, rateLimitHeaders, priceState, quotaState, inputTokens, outputTokens, totalTokens) VALUES ('s2', 'p2', 'a2', 'c2', 'e2', 'm2', NULL, NULL, 'CHAT_COMPLETIONS', 'PLAYGROUND', 2000, 2100, 2400, 1, 100, 400, 0, 200, 'SUCCESS', 0, 'm2', 'x-ratelimit-remaining=9', 'FREE_NOW', 'UNKNOWN', 5, 11, 16)")
        db.execSQL("INSERT INTO evidence (id, requestId, providerId, modelId, outcome, status, timestamp, source, version) VALUES ('sample:s2', 's2', 'p2', 'm2', 'SUCCESS', 200, 2400, 'PLAYGROUND', 1)")
        db.execSQL("INSERT INTO checkins (providerId, date, completedAt, source, accountId) VALUES ('p2', '2026-09-27', 20, 'USER', 'a2')")
        db.execSQL("INSERT INTO quota_snapshots (id, providerId, accountId, credentialId, modelId, \"group\", endpointId, operation, observedAt, state, source, evidenceId) VALUES ('q2', 'p2', 'a2', 'c2', 'm2', NULL, 'e2', 'CHAT_COMPLETIONS', 2500, NULL, 'PROBE', 'sample:s2')")
    }

    /** v3 兩張新表存在且為空（0 列，不是 unknown）。 */
    private suspend fun assertNewOnboardingTablesExistAndEmpty(db: AtlasDatabase) {
        assertNull(db.onboardingSnapshotDao().getByWorkflow("wf-2-3"))
        assertNull(db.onboardingCommitDao().getByWorkflow("wf-2-3"))
        assertEquals(0, countRows(db, "onboarding_snapshots"))
        assertEquals(0, countRows(db, "onboarding_commits"))
    }

    private suspend fun assertOldConnectionPreserved(db: AtlasDatabase) {
        val rows = AtlasRepository(db).connections.first()
        assertEquals(1, rows.size)
        assertEquals("off-2", rows[0].id)
        assertEquals("off-2", rows[0].offeringId)
        assertEquals("p2", rows[0].providerId)
        assertEquals("a2", rows[0].accountId)
        assertEquals("c2", rows[0].credentialId)
        assertEquals("e2", rows[0].endpointId)
        assertEquals("Seek", rows[0].name)
        assertEquals("https://seekai.cc/v1", rows[0].baseUrl)
        assertEquals("https://seekai.cc/v1/chat/completions", rows[0].endpointUrl)
        assertEquals("m2", rows[0].modelId)
        assertEquals("主要憑證", rows[0].credentialLabel)
        assertEquals(true, rows[0].credentialEnabled)
        assertEquals("UNKNOWN", rows[0].priceState)
        assertEquals("UNKNOWN", rows[0].quotaState)
        assertEquals("https://seekai.cc/checkin", rows[0].checkinUrl)
        assertEquals(true, rows[0].requiresDailyCheckin)
    }

    private suspend fun assertOldTelemetryPreserved(db: AtlasDatabase) {
        val sample = db.sampleDao().getById("s2")
        assertNotNull(sample)
        assertEquals("p2", sample?.providerId)
        assertEquals("c2", sample?.safeCredentialId)
        assertEquals("m2", sample?.providerModelId)
        assertEquals("CHAT_COMPLETIONS", sample?.operation)
        assertEquals("PLAYGROUND", sample?.source)
        assertEquals(2000L, sample?.requestStartedAt)
        assertEquals(2400L, sample?.completedAt)
        assertEquals(true, sample?.streaming)
        assertEquals(false, sample?.usageEstimated)
        assertEquals("SUCCESS", sample?.outcome)
        assertEquals(0, sample?.retryCount)
        assertEquals("x-ratelimit-remaining=9", sample?.rateLimitHeaders)
        assertEquals("FREE_NOW", sample?.priceState)
        assertEquals("UNKNOWN", sample?.quotaState)
        assertEquals(5L, sample?.inputTokens)
        assertEquals(11L, sample?.outputTokens)
        assertEquals(16L, sample?.totalTokens)
        val evidence = db.evidenceDao().byRequest("s2")
        assertNotNull(evidence)
        assertEquals("sample:s2", evidence?.id)
        assertEquals(200, evidence?.status)
        assertEquals(2400L, evidence?.timestamp)
        assertEquals("SUCCESS", evidence?.outcome)
    }

    private suspend fun assertOldCheckinPreserved(db: AtlasDatabase) {
        val bound = db.checkinDao().get("p2", "2026-09-27", "a2")
        assertNotNull(bound)
        assertEquals(20L, bound?.completedAt)
        assertEquals("USER", bound?.source)
        assertEquals(1, db.checkinDao().listByProviderDate("p2", "2026-09-27").size)
    }

    private suspend fun assertOldQuotaSnapshotPreserved(db: AtlasDatabase) {
        val quota = db.quotaDao().getById("q2")
        assertNotNull(quota)
        assertEquals("p2", quota?.providerId)
        assertEquals("a2", quota?.accountId)
        assertEquals("c2", quota?.credentialId)
        assertEquals(2500L, quota?.observedAt)
        assertNull(quota?.state)
        assertEquals("PROBE", quota?.source)
    }

    /**
     * v7 追加三欄：舊列 hidden=false（可見未驗證），verificationEvidenceId／group 為 null（未知不補零）。
     * DAO 讀回即證明欄位存在（缺欄 Room 開庫即敗）；顯式 SELECT 再釘一次欄名。
     */
    private suspend fun assertV7AddedColumnsWithDefaults(db: AtlasDatabase) {
        val offering = db.offeringDao().getById("off-2")!!
        assertEquals(false, offering.hidden)
        assertNull(offering.verificationEvidenceId)
        assertNull(db.availabilityDao().getById("off-2")!!.group)
        // v14 documented：null group 回填 identity 'n'（raw group 仍 null）。
        assertEquals("n", db.availabilityDao().getById("off-2")!!.groupIdentity)
        db.openHelper.writableDatabase.query("SELECT `hidden`, `verificationEvidenceId` FROM `offerings` LIMIT 0").use { }
        db.openHelper.writableDatabase.query("SELECT `group` FROM `credential_availability` LIMIT 0").use { }
    }

    /**
     * v8（LMS-003／LMS-004）只加 external_observations／external_mappings 兩表：
     * 版本==10、兩表為空、欄位／型別／nullable／PK 位置全釘住、unique＋一般索引全在、
     * 無 FK。舊列全等由 assertOld* 系列覆蓋。v10 四表見 [assertV10TablesEmptyAndTyped]。
     */
    private fun assertV8TablesEmptyAndTyped(db: AtlasDatabase) {
        val sql = db.openHelper.writableDatabase
        assertEquals(16, sql.version)
        assertEquals(0, countRows(db, "external_observations"))
        assertEquals(0, countRows(db, "external_mappings"))
        assertEquals(
            mapOf(
                "observationId" to Triple("TEXT", 1, 1),
                "source" to Triple("TEXT", 1, 0),
                "externalModelId" to Triple("TEXT", 1, 0),
                "canonicalModelId" to Triple("TEXT", 0, 0),
                "payloadJson" to Triple("TEXT", 1, 0),
                "fetchedAt" to Triple("INTEGER", 1, 0),
                "ttlMillis" to Triple("INTEGER", 1, 0),
                "confidence" to Triple("TEXT", 1, 0),
            ),
            tableColumns(sql, "external_observations"),
        )
        assertEquals(
            mapOf(
                "mappingId" to Triple("TEXT", 1, 1),
                "externalId" to Triple("TEXT", 1, 0),
                "canonicalModelId" to Triple("TEXT", 0, 0),
                "source" to Triple("TEXT", 1, 0),
                "confidence" to Triple("TEXT", 1, 0),
                "verified" to Triple("INTEGER", 1, 0),
                "version" to Triple("INTEGER", 1, 0),
                "observedAt" to Triple("INTEGER", 1, 0),
            ),
            tableColumns(sql, "external_mappings"),
        )
        assertEquals(listOf("source", "externalModelId", "fetchedAt"), indexColumns(sql, "index_external_observations_source_model_fetched"))
        assertEquals(listOf("canonicalModelId"), indexColumns(sql, "index_external_observations_canonical"))
        assertEquals(listOf("source"), indexColumns(sql, "index_external_observations_source"))
        assertEquals(listOf("fetchedAt"), indexColumns(sql, "index_external_observations_fetched"))
        assertEquals(listOf("canonicalModelId"), indexColumns(sql, "index_external_mappings_canonical"))
        assertEquals(listOf("externalId", "source"), indexColumns(sql, "index_external_mappings_external_source"))
        sql.query("PRAGMA foreign_key_list(`external_observations`)").use { assertTrue(!it.moveToFirst()) }
        sql.query("PRAGMA foreign_key_list(`external_mappings`)").use { assertTrue(!it.moveToFirst()) }
    }

    /**
     * v10（X-018 事件層）只加 provider_notices／provider_events／price_events／quota_events 四表：
     * 版本==10、四表為空、欄位／型別／nullable／PK 位置全釘住、索引全在、無 FK。
     * 舊列全等由 assertOld* 系列覆蓋。
     */
    private suspend fun assertV10TablesEmptyAndTyped(db: AtlasDatabase) {
        val sql = db.openHelper.writableDatabase
        assertEquals(16, sql.version)
        assertEquals(0, db.providerNoticeDao().count())
        assertEquals(0, db.providerEventDao().count())
        assertEquals(0, db.priceEventDao().count())
        assertEquals(0, db.quotaEventDao().count())
        assertEquals(
            mapOf(
                "noticeId" to Triple("TEXT", 1, 1),
                "providerId" to Triple("TEXT", 1, 0),
                "observedAt" to Triple("INTEGER", 1, 0),
                "expiresAt" to Triple("INTEGER", 0, 0),
                "source" to Triple("TEXT", 1, 0),
                "evidenceId" to Triple("TEXT", 0, 0),
                "summary" to Triple("TEXT", 1, 0),
                "ref" to Triple("TEXT", 0, 0),
            ),
            tableColumns(sql, "provider_notices"),
        )
        assertEquals(
            mapOf(
                "eventId" to Triple("TEXT", 1, 1),
                "providerId" to Triple("TEXT", 1, 0),
                "kind" to Triple("TEXT", 1, 0),
                "observedAt" to Triple("INTEGER", 1, 0),
                "source" to Triple("TEXT", 1, 0),
                "evidenceId" to Triple("TEXT", 0, 0),
                "detail" to Triple("TEXT", 0, 0),
            ),
            tableColumns(sql, "provider_events"),
        )
        assertEquals(
            mapOf(
                "eventId" to Triple("TEXT", 1, 1),
                "scopeKey" to Triple("TEXT", 1, 0),
                "fromState" to Triple("TEXT", 0, 0),
                "toState" to Triple("TEXT", 0, 0),
                "fromInput" to Triple("TEXT", 0, 0),
                "toInput" to Triple("TEXT", 0, 0),
                "fromOutput" to Triple("TEXT", 0, 0),
                "toOutput" to Triple("TEXT", 0, 0),
                "observedAt" to Triple("INTEGER", 1, 0),
                "source" to Triple("TEXT", 1, 0),
                "evidenceId" to Triple("TEXT", 0, 0),
            ),
            tableColumns(sql, "price_events"),
        )
        assertEquals(
            mapOf(
                "eventId" to Triple("TEXT", 1, 1),
                "scopeKey" to Triple("TEXT", 1, 0),
                "fromState" to Triple("TEXT", 0, 0),
                "toState" to Triple("TEXT", 0, 0),
                "observedAt" to Triple("INTEGER", 1, 0),
                "source" to Triple("TEXT", 1, 0),
                "evidenceId" to Triple("TEXT", 0, 0),
            ),
            tableColumns(sql, "quota_events"),
        )
        assertEquals(listOf("providerId"), indexColumns(sql, "index_provider_notices_provider"))
        assertEquals(listOf("observedAt"), indexColumns(sql, "index_provider_notices_observed"))
        assertEquals(listOf("providerId"), indexColumns(sql, "index_provider_events_provider"))
        assertEquals(listOf("observedAt"), indexColumns(sql, "index_provider_events_observed"))
        assertEquals(listOf("scopeKey"), indexColumns(sql, "index_price_events_scope"))
        assertEquals(listOf("observedAt"), indexColumns(sql, "index_price_events_observed"))
        assertEquals(listOf("scopeKey"), indexColumns(sql, "index_quota_events_scope"))
        assertEquals(listOf("observedAt"), indexColumns(sql, "index_quota_events_observed"))
        sql.query("PRAGMA foreign_key_list(`provider_notices`)").use { assertTrue(!it.moveToFirst()) }
        sql.query("PRAGMA foreign_key_list(`provider_events`)").use { assertTrue(!it.moveToFirst()) }
        sql.query("PRAGMA foreign_key_list(`price_events`)").use { assertTrue(!it.moveToFirst()) }
        sql.query("PRAGMA foreign_key_list(`quota_events`)").use { assertTrue(!it.moveToFirst()) }
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

    /** 新表在正式開庫後可讀寫：snapshot 覆寫、commit 去重鍵寫入，且既有表仍可寫。 */
    private suspend fun assertNewTablesReadableAndWritable(db: AtlasDatabase) {
        db.onboardingSnapshotDao().upsert(
            OnboardingSnapshotEntity(workflowId = "wf-rw", updatedAt = 3000L, snapshotJson = "{}"),
        )
        db.onboardingSnapshotDao().upsert(
            OnboardingSnapshotEntity(workflowId = "wf-rw", updatedAt = 4000L, snapshotJson = "{\"step\":2}"),
        )
        val snapshot = db.onboardingSnapshotDao().getByWorkflow("wf-rw")
        assertNotNull(snapshot)
        assertEquals(4000L, snapshot?.updatedAt)
        assertEquals("{\"step\":2}", snapshot?.snapshotJson)
        assertEquals(1, countRows(db, "onboarding_snapshots"))

        db.onboardingCommitDao().insert(
            OnboardingCommitEntity(workflowId = "wf-rw", providerId = "p2", committedAt = 4100L),
        )
        val commit = db.onboardingCommitDao().getByWorkflow("wf-rw")
        assertNotNull(commit)
        assertEquals("p2", commit?.providerId)
        assertEquals(4100L, commit?.committedAt)
        assertEquals(1, countRows(db, "onboarding_commits"))

        db.checkinDao().upsert(
            CheckinEntity(providerId = "p2", date = "2026-09-28", completedAt = 5000L, accountId = "a2"),
        )
        assertEquals(5000L, db.checkinDao().get("p2", "2026-09-28", "a2")?.completedAt)
        assertEquals(20L, db.checkinDao().get("p2", "2026-09-27", "a2")?.completedAt)
    }

    private fun countRows(db: AtlasDatabase, table: String): Long =
        db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0)
            }

    private fun tryQueryCountOrNull(db: SupportSQLiteDatabase, table: String): Long? =
        try {
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: RuntimeException) {
            null
        }
}
