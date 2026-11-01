package dev.modelatlas.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtlasMigration15To16Test {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),AtlasDatabase::class.java)
    @Test fun addsOnlySyncTablesWithoutRewritingAnyProductTable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-workspace-15-16.db"
        context.deleteDatabase(name)
        val schema: List<kotlinx.serialization.json.JsonObject>
        helper.createDatabase(name,15).use { old ->
            old.execSQL("INSERT INTO providers VALUES('p','existing','UNKNOWN','HEALTHY',NULL,0,0,55)")
            old.execSQL("INSERT INTO accounts VALUES('a','p')")
            old.execSQL("INSERT INTO credentials(credentialId,accountId,label,enabled,authMode) VALUES('c','a','metadata only',1,'BEARER')")
            schema=old.workspaceRows("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT IN('room_master_table','android_metadata') ORDER BY name")
        }
        val room=AtlasDatabase.create(context,name)
        try {
            val db=room.openHelper.writableDatabase
            assertEquals(16,db.version)
            for(item in schema) {
                val now=db.workspaceOne("SELECT type,name,sql FROM sqlite_master WHERE name=?",item.text("name"))
                assertEquals("Legacy schema changed: "+item.text("name"),item,now)
            }
            assertEquals("existing",db.workspaceOne("SELECT name FROM providers WHERE providerId='p'")!!.text("name"))
            assertEquals(55L,db.workspaceOne("SELECT observedHealthAt FROM providers")!!.number("observedHealthAt"))
            assertEquals(kotlinx.serialization.json.JsonNull,db.workspaceOne("SELECT * FROM credentials")!!["group"])
            val tables=db.workspaceRows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN('room_master_table','android_metadata')").map{it.text("name")}.toSet()
            val legacy=schema.filter{it.text("type")=="table"}.map{it.text("name")}.toSet()
            assertEquals(legacy+workspaceSyncTables,tables)
            for(table in workspaceSyncTables) assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM `$table`")!!.number("n"))
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { room.close(); context.deleteDatabase(name) }
    }
}
