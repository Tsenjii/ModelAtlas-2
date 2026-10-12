package dev.modelatlas.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v9 → v10 實機驗證（X-018 事件層；TRACEABILITY X-018）。
 * 以已納管 9.json 建 v9 庫、塞 legacy providers／accounts／credentials，
 * 以 Room 正式開庫跑 MIGRATION_9_10，並以編譯產生的 AtlasDatabase_Impl 驗證最新 schema。
 * 只加四表（provider_notices／provider_events／price_events／quota_events）；
 * 舊列一字不動、無 destructive migration。
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration9To10Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    @Test
    fun migrate9To10_addsEventTables_preservesLegacy() = runBlocking {
        val dbName = "mig-9-10.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        helper.createDatabase(dbName, 9).use { old ->
            old.execSQL("INSERT INTO providers (providerId, name, officialHealth, observedHealth, checkinUrl, requiresDailyCheckin) VALUES ('p10', 'Seek', 'HEALTHY', 'UNKNOWN', NULL, 0)")
            old.execSQL("INSERT INTO accounts (accountId, providerId) VALUES ('a10', 'p10')")
            old.execSQL("INSERT INTO credentials (credentialId, accountId, label, enabled) VALUES ('c10', 'a10', '主要憑證', 1)")
        }

        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, dbName)
            .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
            .allowMainThreadQueries()
            .build()
        try {
            val sql = db.openHelper.writableDatabase
            assertEquals(15, sql.version)

            // 舊列保留（一次 sync failure 不清空 last-good 的 migration 側：舊資料一字不動）。
            val provider = db.providerDao().getById("p10")!!
            assertEquals("Seek", provider.name)
            assertEquals("HEALTHY", provider.officialHealth)
            assertEquals(0L, provider.officialHealthAt)
            val credential = db.credentialDao().getById("c10")!!
            assertEquals("主要憑證", credential.label)
            assertTrue(credential.enabled)
            assertEquals("UNKNOWN", credential.authMode)
            assertNull(db.providerDao().getById("missing"))

            // 四張新表存在且為空、欄位／型別／nullable／PK 位置全釘住、索引全在、無 FK。
            assertV10TablesEmptyAndTyped(db)

            // 新表可經正式 EventStore 讀寫。
            val store = EventStore(db)
            store.saveNotice("notice-mig-1", "p10", "fixture summary", ref = "ev-mig", source = "OFFICIAL", observedAt = 1000L)
            assertEquals(1, store.noticesOf("p10").size)
            assertEquals(0, store.noticesOf("p10-missing").size)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

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
