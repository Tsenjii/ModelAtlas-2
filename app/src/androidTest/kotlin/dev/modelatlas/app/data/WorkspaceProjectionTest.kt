package dev.modelatlas.app.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.workspace.WorkspaceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceProjectionTest {
    private fun room()=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,AtlasDatabase::class.java).build()
    private fun retain(db: SupportSQLiteDatabase,v: WorkspaceRecord) {
        db.execSQL("INSERT OR REPLACE INTO workspace_sync_records VALUES(?,?,1,?,?,0,1)",arrayOf(v.kind,v.id,v.encode(),v.encode()))
        assertTrue("Must project ${v.kind}",WorkspaceProjection(db).apply(v,true))
    }
    private fun provider()=WorkspaceRecord("providers",workspaceID("p"),data=workspaceMap("id" to "p","name" to "Provider",
        "base_url" to "https://a.example/v1","auth_mode" to "BEARER","description" to "","created_at" to 7))
    private fun account(a: String)=WorkspaceRecord("accounts",workspaceID("p",a),data=workspaceMap("provider_id" to "p","id" to a,"label" to a))
    private fun credential(a: String,base: String)=WorkspaceRecord("credentials",workspaceID("p",a,"same-key"),data=workspaceMap(
        "provider_id" to "p","account_id" to a,"id" to "same-key","label" to "Same Key","role" to "INFERENCE","enabled" to 1,"created_at" to 7,"_auth_mode" to "","_base_url" to base))
    private fun member(a: String)=WorkspaceRecord("my_models",workspaceID("p",a,"same-key","m","","CHAT_COMPLETIONS"),data=workspaceMap(
        "provider_id" to "p","account_id" to a,"credential_id" to "same-key","model_id" to "m","group_id" to "","endpoint" to "CHAT_COMPLETIONS","created_at" to 7))

    @Test fun sameNamedKeysKeepDifferentAccountOriginsAndRemovingOneScopeKeepsTheOther() {
        val room=room()
        try {
            val db=room.openHelper.writableDatabase
            retain(db,provider())
            for((a,base) in listOf("a" to "https://a.example/v1","b" to "https://b.example/v1")) {
                retain(db,account(a)); retain(db,credential(a,base)); retain(db,member(a))
            }
            val connections=db.workspaceRows("SELECT a.accountId,a.credentialId,a.groupIdentity,e.baseUrl,o.id AS offering FROM credential_availability a JOIN offerings o ON o.id=a.offeringId JOIN endpoints e ON e.endpointId=o.endpointId")
            assertEquals(2,connections.size)
            assertEquals(2,connections.map{it.text("credentialId")}.toSet().size)
            assertEquals(2,connections.map{it.text("offering")}.toSet().size)
            assertEquals(setOf("https://a.example/v1","https://b.example/v1"),connections.map{it.text("baseUrl")}.toSet())
            assertTrue(connections.all{it.text("groupIdentity")=="v"})
            val credentials=db.workspaceRows("SELECT * FROM credentials").flatMap { WorkspaceProjection(db).export("credentials",it) }.filter{it.kind=="credentials"}
            assertEquals(setOf(workspaceID("p","a","same-key"),workspaceID("p","b","same-key")),credentials.map{it.id}.toSet())
            assertEquals(setOf("https://a.example/v1","https://b.example/v1"),credentials.map{it.data.text("_base_url")}.toSet())
            val victim=WorkspaceProjection(db).nativeID("credentials",credential("a","https://a.example/v1").id)
            val rebound=credential("b","https://b.example/v1").copy(id=workspaceID("p","b","spoofed"),data=JsonObject(
                credential("b","https://b.example/v1").data+workspaceMap("id" to "spoofed","_native_android" to workspaceMap("native_id" to victim))))
            try { WorkspaceProjection(db).apply(rebound,true); fail("Must not reuse another account's native credential") } catch(_:IllegalStateException) { }
            assertEquals(WorkspaceProjection(db).nativeID("accounts",workspaceID("p","a")),db.workspaceOne("SELECT accountId FROM credentials WHERE credentialId=?",victim)!!.text("accountId"))
            retain(db,member("a").copy(deleted=true,data=JsonObject(emptyMap())))
            assertEquals(1L,db.workspaceOne("SELECT COUNT(*) AS n FROM credential_availability")!!.number("n"))
            assertEquals(1L,db.workspaceOne("SELECT COUNT(*) AS n FROM my_models")!!.number("n"))
            assertEquals("https://b.example/v1",db.workspaceOne("SELECT e.baseUrl FROM credential_availability a JOIN offerings o ON o.id=a.offeringId JOIN endpoints e ON e.endpointId=o.endpointId")!!.text("baseUrl"))
            retain(db,member("b").copy(deleted=true,data=JsonObject(emptyMap())))
            assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM my_models")!!.number("n"))
        } finally { room.close() }
    }

    @Test fun explicitAnonymousMembershipUsesProductionAnonymousConnectionWithoutBearerAvailability()=runBlocking {
        val room=room()
        try {
            val db=room.openHelper.writableDatabase
            retain(db,provider().copy(data=JsonObject(provider().data+("auth_mode" to JsonPrimitive("NONE")))))
            retain(db,account("a")); retain(db,credential("a","https://a.example/v1")); retain(db,member("a"))
            val connections=room.connectionDao().observe().first()
            assertEquals(1,connections.size)
            assertTrue(connections.single().anonymous)
            assertEquals("",connections.single().credentialId)
            assertEquals("UNKNOWN",connections.single().priceState)
            assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM credential_availability")!!.number("n"))
            assertEquals(0L,db.workspaceOne("SELECT enabled FROM credentials")!!.number("enabled"))
            val config=decodeProviderManualConfig(db.workspaceOne("SELECT snapshotJson FROM provider_configurations")!!.text("snapshotJson"))
            assertTrue(config.anonymousConfirmed)
            val exports=WorkspaceProjection(db).export("my_models",db.workspaceOne("SELECT * FROM my_models")!!)
            assertEquals(member("a").id,exports.single{it.kind=="my_models"}.id)
            val key=WorkspaceProjection(db).export("credentials",db.workspaceOne("SELECT * FROM credentials")!!).single{it.kind=="credentials"}
            assertEquals(1L,key.data.number("enabled"))
        } finally { room.close() }
    }
}
