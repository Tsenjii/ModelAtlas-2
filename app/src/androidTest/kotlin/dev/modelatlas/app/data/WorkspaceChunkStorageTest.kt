package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.workspace.*
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceChunkStorageTest {
    @Test fun splitUtf8ProductWriteAndReadPreserveAllBytesBeyondCursorWindow() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val room=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        try {
            val db=room.openHelper.writableDatabase
            val record=WorkspaceRecord("messages","unicode",data=buildJsonObject { put("content","界😀".repeat(1_000_000));put("tokens",9007199254740993L) })
            val expected=workspaceContentDigest(record)
            room.runInTransaction(Runnable {
                db.workspaceWithRecordJSON(record) { encoded ->
                    db.execSQL("INSERT INTO workspace_sync_records VALUES('messages','unicode',0,${encoded.expression},${encoded.expression},1,0)",
                        (encoded.args+encoded.args).toTypedArray())
                }
            })
            val restored=db.workspaceReadRecord("localJson","messages","unicode")!!
            assertEquals(expected,workspaceContentDigest(restored))
            assertEquals(9007199254740993L,restored.data.getValue("tokens").jsonPrimitive.long)
            assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM temp.workspace_json_write_parts")!!.number("n"))
        } finally { room.close() }
    }
    @Test fun sameSizeSqlEditInvalidatesCachedReceiptBeforeCompareAndSet() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val room=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        try {
            val db=room.openHelper.writableDatabase;installWorkspaceHashJournal(db)
            val first=WorkspaceRecord("messages","edit",data=buildJsonObject { put("content","old!") })
            val next=first.copy(data=buildJsonObject { put("content","new!") })
            db.execSQL("INSERT INTO workspace_sync_records VALUES('messages','edit',1,?,?,1,0)",arrayOf(first.encode(),first.encode()))
            val old=workspaceContentDigest(first);db.workspaceSaveRecordHashes(first.kind,first.id,old,old)
            db.execSQL("UPDATE workspace_sync_records SET localJson=? WHERE kind='messages' AND id='edit'",arrayOf(next.encode()))
            assertNull(db.workspaceRecordHashes("messages","edit"))
            assertEquals(workspaceContentDigest(next),db.workspaceLocalDigest("messages","edit"))
            assertNotEquals(old,db.workspaceLocalDigest("messages","edit"))
        } finally { room.close() }
    }
    @Test fun keystoreFragmentRejectsChangedContextAndCredentialDecryptionDomain() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=AndroidCredentialVault(context);val ref="ws-frag-"+UUID.randomUUID()
        val bytes=ByteArray(256*1024) { (it%251).toByte() };val binding="a".repeat(64)
        val sealed=vault.sealWorkspaceChunk(ref,"https://atlas.example",binding,bytes)
        assertArrayEquals(bytes,vault.openWorkspaceChunk(sealed,binding))
        assertFalse(sealed.ciphertext.contains("matlas_device_"))
        try { vault.openWorkspaceChunk(sealed,"b".repeat(64));fail("Changed fragment context decrypted") } catch(_: Exception) { }
        try { vault.openWorkspaceChunk(sealed.copy(origin="https://another.example"),binding);fail("Changed origin decrypted") } catch(_: Exception) { }
    }
}
