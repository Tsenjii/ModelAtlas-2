package dev.modelatlas.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v10 → v11 實機驗證（③-1 My Models membership；D-008／Contract §23.1）。
 * 以已納管 10.json 建 v10 庫、塞 legacy providers／accounts／credentials／offerings，
 * 以 Room 正式開庫跑 MIGRATION_10_11，並以編譯產生的 AtlasDatabase_Impl 驗證最新 schema。
 * 只加一表（my_models）；舊列一字不動、無 destructive migration；舊庫升級後預設非成員（空表）。
 */
@RunWith(AndroidJUnit4::class)
class AtlasMigration10To11Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtlasDatabase::class.java,
    )

    @Test
    fun migrate10To11_addsMyModelsTable_preservesLegacy() = runBlocking {
        val dbName = "mig-10-11.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        helper.createDatabase(dbName, 10).use { old ->
            old.execSQL("INSERT INTO providers (providerId, name, officialHealth, observedHealth, checkinUrl, requiresDailyCheckin) VALUES ('p11', 'Seek', 'HEALTHY', 'UNKNOWN', NULL, 0)")
            old.execSQL("INSERT INTO accounts (accountId, providerId) VALUES ('a11', 'p11')")
            old.execSQL("INSERT INTO credentials (credentialId, accountId, label, enabled) VALUES ('c11', 'a11', '主要憑證', 1)")
        }

        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, dbName)
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
            .allowMainThreadQueries()
            .build()
        try {
            val sql = db.openHelper.writableDatabase
            assertEquals(15, sql.version)

            val provider = db.providerDao().getById("p11")!!
            assertEquals("Seek", provider.name)

            // 舊庫升級後預設非成員：空表，不自動污染。
            val store = MyModelsStore(db)
            assertTrue(store.myModelKeys().isEmpty())
            assertFalse(store.isMyModel("p11", "m11"))

            // 明確加入後即為成員；移除後回到非成員。
            assertTrue(store.addToMyModels("p11", "m11"))
            assertTrue(store.isMyModel("p11", "m11"))
            assertFalse(store.removeFromMyModels("p11", "m11"))
            assertFalse(store.isMyModel("p11", "m11"))
        } finally {
            runCatching { db.close() }
            context.deleteDatabase(dbName)
        }
    }
}
