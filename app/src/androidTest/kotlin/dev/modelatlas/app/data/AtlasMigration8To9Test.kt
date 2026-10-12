package dev.modelatlas.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v8 → v9 實機驗證（MODEL-005／PROV-006／PROV-009）：
 * 以已納管 8.json 建 v8 庫、塞 legacy providers／credentials，
 * 以 Room 正式開庫跑 MIGRATION_8_9，並以編譯產生的 AtlasDatabase_Impl 驗證最新 schema。
 * 只加三表＋providers 兩欄＋credentials 九欄；舊列套預設、資料一字不動。
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration8To9Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    @After
    fun tearDown() {
        // 每個測試自建自刪，見各 test finally。
    }

    @Test
    fun migrate8To9_addsTablesAndColumns_preservesLegacy() = runBlocking {
        val dbName = "mig-8-9.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        helper.createDatabase(dbName, 8).use { old ->
            old.execSQL("INSERT INTO providers (providerId, name, officialHealth, observedHealth, checkinUrl, requiresDailyCheckin) VALUES ('p9', 'Seek', 'HEALTHY', 'UNKNOWN', NULL, 0)")
            old.execSQL("INSERT INTO accounts (accountId, providerId) VALUES ('a9', 'p9')")
            old.execSQL("INSERT INTO credentials (credentialId, accountId, label, enabled) VALUES ('c9', 'a9', '主要憑證', 1)")
        }

        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, dbName)
            .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
            .allowMainThreadQueries()
            .build()
        try {
            val sql = db.openHelper.writableDatabase
            assertEquals(15, sql.version)

            // 舊列保留。
            val provider = db.providerDao().getById("p9")!!
            assertEquals("Seek", provider.name)
            assertEquals("HEALTHY", provider.officialHealth)
            // health 快照新欄：既有列未觀測 → 0（不猜已知）。
            assertEquals(0L, provider.officialHealthAt)
            assertEquals(0L, provider.observedHealthAt)

            val credential = db.credentialDao().getById("c9")!!
            assertEquals("主要憑證", credential.label)
            assertTrue(credential.enabled)
            assertNull(credential.note)
            assertNull(credential.group)
            assertEquals(0, credential.priority)
            // RV1：舊列 authMode 未知保持未知（UNKNOWN），不得回填 API_KEY 冒充已知。
            assertEquals("UNKNOWN", credential.authMode)
            assertNull(credential.headerName)
            assertNull(credential.headerPrefix)
            assertTrue(credential.isSecret)
            assertNull(credential.lastSuccessAt)
            assertNull(credential.lastFailureAt)

            // 三張新表存在且為空。
            assertEquals(0, db.favoriteDao().favorites().size)
            assertEquals(0, db.favoriteDao().watchlistIds().size)
            assertEquals(0, db.favoriteDao().recentIds().size)
            assertNull(db.favoriteDao().recent("missing"))
            assertNull(db.providerDao().getById("missing"))

            // 新表可經正式 store 讀寫；新欄可寫。
            val favorites = FavoriteStore(db)
            assertTrue(favorites.setFavorite("gpt-4o", true, 1000L))
            assertTrue(favorites.isFavorite("gpt-4o"))
            favorites.recordUse("gpt-4o", 2000L)
            assertEquals(1, favorites.recentOf("gpt-4o")!!.useCount)

            val manager = CredentialManager(db)
            val audit = manager.registerCustomHeader("c9x", "a9", "custom", "X-Api-Key", "Bearer ", group = "g9")
            assertEquals("Bearer ", audit.headerPrefix)
            assertTrue(audit.isSecret)
            assertTrue(manager.setEnabled("c9x", false))
            assertFalse(db.credentialDao().getById("c9x")!!.enabled)

            // observed 快照可寫回，official 不受影響。
            db.providerDao().updateObservedHealth("p9", "DEGRADED", 1234L)
            val updated = db.providerDao().getById("p9")!!
            assertEquals("DEGRADED", updated.observedHealth)
            assertEquals(1234L, updated.observedHealthAt)
            assertEquals("HEALTHY", updated.officialHealth)
            assertEquals(0L, updated.officialHealthAt)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
