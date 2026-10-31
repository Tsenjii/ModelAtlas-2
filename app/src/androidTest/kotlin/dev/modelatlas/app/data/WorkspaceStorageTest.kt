package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.security.AndroidCredentialVault
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceStorageTest {
    @Test fun credentialMapperUpgradeReexportsCommittedRolesExactlyOnceWithoutUserEdits() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val room=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        try {
            val db=room.openHelper.writableDatabase;installWorkspaceJournal(db)
            db.execSQL("INSERT INTO providers VALUES('p','Provider','UNKNOWN','UNKNOWN',NULL,0,0,0)")
            db.execSQL("INSERT INTO accounts VALUES('a','p')")
            db.execSQL("INSERT INTO credentials(credentialId,accountId,label,enabled) VALUES('c','a','Key',1)")
            db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('credential-mapper-version','1')")
            val before=db.workspaceOne("SELECT version FROM workspace_sync_versions WHERE tableName='credentials'")!!.number("version")
            installWorkspaceJournal(db)
            assertEquals(before+1,db.workspaceOne("SELECT version FROM workspace_sync_versions WHERE tableName='credentials'")!!.number("version"))
            installWorkspaceJournal(db)
            assertEquals(before+1,db.workspaceOne("SELECT version FROM workspace_sync_versions WHERE tableName='credentials'")!!.number("version"))
            assertEquals(1L,db.workspaceOne("SELECT enabled FROM credentials")!!.number("enabled"))
        } finally { room.close() }
    }
    @Test fun retainedLargeUnicodeRecordDoesNotDependOnCursorWindowSize() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val room=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        try {
            val db=room.openHelper.writableDatabase
            val payload="保留😀".repeat(400_000)
            db.execSQL("INSERT INTO workspace_sync_records VALUES('messages','large',1,?,?,0,1)",arrayOf(payload,payload))
            assertEquals(payload,db.workspaceText("workspace_sync_records","localJson","kind=? AND id=?","messages","large"))
            assertEquals(payload,db.workspaceText("workspace_sync_records","baseJson","kind=? AND id=?","messages","large"))
        } finally { room.close() }
    }
    @Test fun journalSurvivesReplaceAndUpsertWithoutBreakingProductWritesAndPreservesNullGroups() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val room=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        try {
            val db=room.openHelper.writableDatabase
            installWorkspaceJournal(db)
            db.execSQL("INSERT INTO providers VALUES('p','first','UNKNOWN','UNKNOWN',NULL,0,0,0)")
            db.execSQL("INSERT OR REPLACE INTO providers VALUES('p','second','UNKNOWN','UNKNOWN',NULL,0,0,0)")
            db.execSQL("UPDATE providers SET name='third' WHERE providerId='p'")
            assertEquals("third",db.workspaceOne("SELECT name FROM providers")!!.text("name"))
            assertTrue(db.workspaceOne("SELECT version FROM workspace_sync_versions WHERE tableName='providers'")!!.number("version")>=2)
            db.execSQL("INSERT INTO accounts VALUES('a','p')")
            db.execSQL("INSERT INTO credentials(credentialId,accountId,label,enabled,authMode) VALUES('c','a','key',1,'BEARER')")
            val row=db.workspaceOne("SELECT * FROM credentials WHERE credentialId='c'")!!
            assertEquals(JsonNull,row["group"])
            db.workspacePut("credentials",JsonObject(row+workspaceMap("label" to "updated","group" to "known")))
            assertEquals("known",db.workspaceOne("SELECT * FROM credentials WHERE credentialId='c'")!!.text("group"))
            db.workspacePut("credentials",JsonObject(row+workspaceMap("label" to "retained","group" to null)))
            assertEquals(JsonNull,db.workspaceOne("SELECT * FROM credentials WHERE credentialId='c'")!!["group"])
            db.workspacePut("credentials",JsonObject(row+workspaceMap("credentialId" to "inserted","group" to "")))
            assertEquals("",db.workspaceOne("SELECT * FROM credentials WHERE credentialId='inserted'")!!.text("group"))
            val record=WorkspaceProjection(db).export("credentials",row).first()
            assertEquals(JsonNull,record.data["group"])
            assertFalse(record.encode().contains("encrypted_key"))
            db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('importing','1')")
            val before=db.workspaceOne("SELECT version FROM workspace_sync_versions WHERE tableName='providers'")!!.number("version")
            db.execSQL("UPDATE providers SET name='remote' WHERE providerId='p'")
            assertEquals(before,db.workspaceOne("SELECT version FROM workspace_sync_versions WHERE tableName='providers'")!!.number("version"))
        } finally { room.close() }
    }
    @Test fun destinationVaultAndMetadataCommitTogetherAndRollbackTogether() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val room=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        val vault=AndroidCredentialVault(context)
        vault.attachSyncDatabase { room.openHelper.writableDatabase }
        val ref=UUID.randomUUID().toString()
        val sealed=vault.sealForSync(ref,"https://atlas.example","synthetic-key")
        try {
            try {
                room.runInTransaction(Runnable {
                    val db=room.openHelper.writableDatabase
                    vault.putSyncedCiphertext(db,sealed)
                    db.execSQL("INSERT INTO providers VALUES('p','synced','UNKNOWN','UNKNOWN',NULL,0,0,0)")
                    error("Simulated interrupted import")
                })
                fail("Should roll back")
            } catch(_:IllegalStateException) { }
            assertFalse(vault.has(ref))
            assertNull(room.openHelper.writableDatabase.workspaceOne("SELECT * FROM providers"))
            room.runInTransaction(Runnable { vault.putSyncedCiphertext(room.openHelper.writableDatabase,sealed) })
            assertEquals("synthetic-key",vault.bearerToken(ref,"https://atlas.example"))
            val raw=room.openHelper.writableDatabase.workspaceOne("SELECT * FROM workspace_sync_vault")!!.toString()
            assertFalse(raw.contains("synthetic-key"))
            try { vault.bearerToken(ref,"https://wrong.example"); fail("Scope mismatch must fail") } catch(_:IllegalStateException) { }
            vault.remove(ref)
            assertFalse(vault.has(ref))
        } finally { room.close() }
    }
}
