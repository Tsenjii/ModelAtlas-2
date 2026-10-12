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

/** 以已納管的 v1 schema 建庫，再由 Room 正式開庫執行並驗證 migration 到 v14。Fixture 只在 androidTest。 */
@RunWith(AndroidJUnit4::class)
class AtlasMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    @Test
    fun migrate1To2_preservesConnectionAndTelemetry_singleAccountBindsCheckin() = runBlocking {
        val dbName = "mig-single.db"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(dbName)
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO providers (providerId, name, officialHealth, observedHealth, checkinUrl, requiresDailyCheckin) VALUES ('p1', 'Seek', 'UNKNOWN', 'UNKNOWN', NULL, 0)")
            execSQL("INSERT INTO accounts (accountId, providerId) VALUES ('a1', 'p1')")
            execSQL("INSERT INTO credentials (credentialId, accountId, label, enabled) VALUES ('c1', 'a1', '主要憑證', 1)")
            execSQL("INSERT INTO endpoints (endpointId, providerId, baseUrl, endpointUrl, protocol) VALUES ('e1', 'p1', 'https://seekai.cc/v1', 'https://seekai.cc/v1/chat/completions', 'CHAT_COMPLETIONS')")
            execSQL("INSERT INTO offerings (id, providerId, endpointId, modelId, manual, source) VALUES ('off-1', 'p1', 'e1', 'm1', 1, 'MANUAL')")
            execSQL("INSERT INTO credential_availability (id, offeringId, accountId, credentialId, priceState, quotaState) VALUES ('off-1', 'off-1', 'a1', 'c1', 'UNKNOWN', 'UNKNOWN')")
            execSQL("INSERT INTO samples (id, providerId, accountId, safeCredentialId, endpointId, modelId, operation, source, startedAt, streaming, usageEstimated, outcome, retryCount, rateLimitHeaders, priceState, quotaState, inputTokens, outputTokens) VALUES ('s1', 'p1', 'a1', 'c1', 'e1', 'm1', 'CHAT_COMPLETIONS', 'PLAYGROUND', 1000, 1, 0, 'RUNNING', 0, '', 'FREE_NOW', 'UNKNOWN', 3, 7)")
            execSQL("INSERT INTO evidence (id, requestId, providerId, modelId, outcome, status, timestamp, source, version) VALUES ('sample:s1', 's1', 'p1', 'm1', 'RUNNING', NULL, 1000, 'PLAYGROUND', 1)")
            execSQL("INSERT INTO checkins (providerId, date, completedAt, source) VALUES ('p1', '2026-09-27', 10, 'USER')")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, dbName).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15).allowMainThreadQueries().build()
        try {
            val repo = AtlasRepository(db)
            val rows = repo.connections.first()
            assertEquals(1, rows.size)
            assertEquals("off-1", rows[0].id)
            assertEquals("off-1", rows[0].offeringId)
            assertEquals(true, rows[0].credentialEnabled)
            assertEquals("https://seekai.cc/v1", rows[0].baseUrl)
            val sample = db.sampleDao().getById("s1")
            assertNotNull(sample)
            assertEquals("FREE_NOW", sample?.priceState)
            assertEquals(7L, sample?.outputTokens)
            assertNotNull(db.evidenceDao().byRequest("s1"))
            val bound = db.checkinDao().get("p1", "2026-09-27", "a1")
            assertNotNull(bound)
            assertEquals(10L, bound?.completedAt)
            assertNull(db.checkinDao().get("p1", "2026-09-27", ""))
            assertNull(db.canonicalModelDao().getById("missing"))
            assertEquals(0, db.modelClassificationDao().byCanonical("missing").size)
            // v7 追加三欄：舊列 hidden=false（可見未驗證），另兩欄 null（未知不補零）。
            val legacyOffering = db.offeringDao().getById("off-1")!!
            assertEquals(false, legacyOffering.hidden)
            assertNull(legacyOffering.verificationEvidenceId)
            assertNull(db.availabilityDao().getById("off-1")!!.group)
            // 全鏈到 v14；既有連線、telemetry、evidence、checkin 仍保留。
            assertEquals(15, db.openHelper.writableDatabase.version)
            assertV8TablesEmptyAndTyped(db)
            assertV10TablesEmptyAndTyped(db)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migrate1To2_multiAccountKeepsLegacyCheckin() = runBlocking {
        val dbName = "mig-multi.db"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(dbName)
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO providers (providerId, name, officialHealth, observedHealth, checkinUrl, requiresDailyCheckin) VALUES ('pm', 'M', 'UNKNOWN', 'UNKNOWN', NULL, 0)")
            execSQL("INSERT INTO accounts (accountId, providerId) VALUES ('am1', 'pm')")
            execSQL("INSERT INTO accounts (accountId, providerId) VALUES ('am2', 'pm')")
            execSQL("INSERT INTO checkins (providerId, date, completedAt, source) VALUES ('pm', '2026-09-27', 11, 'USER')")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, dbName).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15).allowMainThreadQueries().build()
        try {
            val legacy = db.checkinDao().get("pm", "2026-09-27", "")
            assertNotNull(legacy)
            assertEquals(11L, legacy?.completedAt)
            assertNull(db.checkinDao().get("pm", "2026-09-27", "am1"))
            assertNull(db.checkinDao().get("pm", "2026-09-27", "am2"))
            // v7 三欄存在：空表仍可查（缺欄即拋），Room 開庫已驗 schema。
            db.openHelper.writableDatabase.query("SELECT `hidden`, `verificationEvidenceId` FROM `offerings` LIMIT 0").use { }
            db.openHelper.writableDatabase.query("SELECT `group` FROM `credential_availability` LIMIT 0").use { }
            assertNull(db.offeringDao().getById("missing"))
            assertNull(db.availabilityDao().getById("missing"))
            // 全鏈到 v14；多帳號 legacy checkin 不被重新歸因。
            assertEquals(15, db.openHelper.writableDatabase.version)
            assertV8TablesEmptyAndTyped(db)
            assertV10TablesEmptyAndTyped(db)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    /**
     * v8（LMS-003／LMS-004）只加 external_observations／external_mappings 兩表：
     * 兩表為空、欄位／型別／nullable／PK 位置全釘住、unique＋一般索引全在、無 FK。
     * 舊列全等由上方 connections／sample／evidence／checkin 斷言覆蓋。
     */
    private suspend fun assertV8TablesEmptyAndTyped(db: AtlasDatabase) {
        assertEquals(0, db.externalObservationDao().count())
        assertEquals(0, db.externalMappingDao().count())
        val sql = db.openHelper.writableDatabase
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
     * 四表為空、欄位／型別／nullable／PK 位置全釘住、索引全在、無 FK。
     * 舊列全等由上方 connections／sample／evidence／checkin 斷言覆蓋。
     */
    private suspend fun assertV10TablesEmptyAndTyped(db: AtlasDatabase) {
        assertEquals(0, db.providerNoticeDao().count())
        assertEquals(0, db.providerEventDao().count())
        assertEquals(0, db.priceEventDao().count())
        assertEquals(0, db.quotaEventDao().count())
        val sql = db.openHelper.writableDatabase
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
}
