package dev.modelatlas.app.data

import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.workspace.WorkspaceTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@RunWith(AndroidJUnit4::class)
class WorkspaceCrossRuntimeTest {
    @Test fun realGoHubPairingNativeImportBidirectionalEditConflictRevocationAndVault()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val url=args.getString("workspaceFixture").orEmpty()
        assumeTrue("Started by the Android CI cross-runtime fixture",url.isNotEmpty())
        val cert=CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(args.getString("workspaceCertificate"),Base64.DEFAULT).inputStream())
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ci-fixture",cert) }
        val factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val manager=factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl=SSLContext.getInstance("TLS").apply { init(null,arrayOf(manager),null) }
        val client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,manager).build()
        fun admin(path:String,body:JsonObject?=null): JsonElement {
            val request=Request.Builder().url(url+"/api/v1"+path)
                .header("Authorization",Credentials.basic("admin",args.getString("workspaceAdmin").orEmpty()))
            if(body!=null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
            return client.newCall(request.build()).execute().use { response ->
                assertTrue("Fixture API status",response.code in 200..299)
                kotlinx.serialization.json.Json.parseToJsonElement(response.body!!.string())
            }
        }
        fun pair():String=admin("/workspace/pair",workspaceMap("share_secrets" to JsonPrimitive(true))).jsonObject.text("code")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val room=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
        val vault=AndroidCredentialVault(context).apply { attachSyncDatabase { room.openHelper.writableDatabase } }
        val sync=DeviceWorkspaceSync(room,vault,WorkspaceTransport(client))
        try {
            assertTrue(sync.connect(url,"Android CI",pair()))
            assertTrue(sync.syncNow())
            val db=room.openHelper.writableDatabase
            val provider=db.workspaceOne("SELECT * FROM providers")!!
            val p=provider.text("providerId")
            val credential=db.workspaceOne("SELECT * FROM credentials")!!
            assertEquals("top-secret-example-key",vault.bearerToken(credential.text("credentialId"),"https://provider.example"))
            assertEquals(1L,db.workspaceOne("SELECT COUNT(*) AS n FROM my_models")!!.number("n"))
            assertEquals(1L,db.workspaceOne("SELECT COUNT(*) AS n FROM credential_availability")!!.number("n"))
            assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM samples")!!.number("n"))
            val history=workspaceRecord(db.workspaceText("workspace_sync_records","localJson","kind='messages' AND id=?",workspaceID("native-history-message"))!!)
            assertEquals(4*1024*1024,history.data.text("content").length)
            // Native-created records and secret travel through the actual Go hub
            // to an independent native database, without duplicate connections.
            val np=UUID.randomUUID().toString(); val na=UUID.randomUUID().toString(); val nc=UUID.randomUUID().toString()
            val ne=UUID.randomUUID().toString(); val no=UUID.randomUUID().toString(); val nv=UUID.randomUUID().toString()
            db.execSQL("INSERT INTO providers VALUES(?,'Native source','UNKNOWN','UNKNOWN',NULL,0,0,0)",arrayOf(np))
            db.execSQL("INSERT INTO accounts VALUES(?,?)",arrayOf(na,np))
            db.execSQL("INSERT INTO credentials(credentialId,accountId,label,enabled,authMode) VALUES(?,?,'Native key',1,'BEARER')",arrayOf(nc,na))
            vault.store(nc,"https://native.example","native-synthetic-secret")
            db.execSQL("INSERT INTO endpoints VALUES(?,?,'https://native.example/v1','https://native.example/v1/chat/completions','CHAT_COMPLETIONS')",arrayOf(ne,np))
            db.execSQL("INSERT INTO offerings VALUES(?,?,?,'native-model',1,'MANUAL',0,NULL)",arrayOf(no,np,ne))
            db.execSQL("INSERT INTO credential_availability(id,offeringId,accountId,credentialId,priceState,quotaState,`group`,groupIdentity) VALUES(?,?,?,?,'UNKNOWN','UNKNOWN','','v')",arrayOf(nv,no,na,nc))
            db.execSQL("INSERT INTO my_models VALUES(?,'native-model',7,'USER')",arrayOf(np))
            assertTrue(sync.syncNow())
            assertEquals(1L,db.workspaceOne("SELECT enabled FROM credentials WHERE credentialId=?",nc)!!.number("enabled"))
            val mirrorRoom=Room.inMemoryDatabaseBuilder(context,AtlasDatabase::class.java).build()
            val mirrorVault=AndroidCredentialVault(context).apply { attachSyncDatabase { mirrorRoom.openHelper.writableDatabase } }
            val mirror=DeviceWorkspaceSync(mirrorRoom,mirrorVault,WorkspaceTransport(client))
            try {
                assertTrue(mirror.connect(url,"Android mirror CI",pair())); assertTrue(mirror.syncNow())
                val md=mirrorRoom.openHelper.writableDatabase
                assertEquals("Native source",md.workspaceOne("SELECT name FROM providers WHERE providerId=?",np)!!.text("name"))
                assertEquals("native-synthetic-secret",mirrorVault.bearerToken(nc,"https://native.example"))
                assertEquals(1L,md.workspaceOne("SELECT enabled FROM credentials WHERE credentialId=?",nc)!!.number("enabled"))
                assertEquals(1L,md.workspaceOne("SELECT COUNT(*) AS n FROM credential_availability WHERE credentialId=?",nc)!!.number("n"))
                assertEquals(nv,md.workspaceOne("SELECT id FROM credential_availability WHERE credentialId=?",nc)!!.text("id"))
                assertEquals(no,md.workspaceOne("SELECT offeringId FROM credential_availability WHERE credentialId=?",nc)!!.text("offeringId"))
                assertEquals(2L,md.workspaceOne("SELECT COUNT(*) AS n FROM my_models")!!.number("n"))
                mirror.disconnect()
            } finally { mirrorRoom.close() }
            db.execSQL("UPDATE providers SET name='Android edited' WHERE providerId=?",arrayOf(p))
            assertTrue(sync.syncNow())
            assertEquals("Android edited",admin("/providers").jsonArray.single{it.jsonObject.text("id")=="p"}.jsonObject.text("name"))
            // A real offline local edit races a real hub edit. Neither is lost.
            db.execSQL("UPDATE providers SET name='Android offline' WHERE providerId=?",arrayOf(p))
            val preview=admin("/assistant/actions/preview",workspaceMap("kind" to "PROVIDER","provider_id" to "p",
                "patch" to workspaceMap("name" to "Hub offline"))).jsonObject
            admin("/assistant/actions/confirm",workspaceMap("id" to preview.text("id"),"confirm" to JsonPrimitive(true)))
            assertTrue(sync.syncNow())
            val conflict=sync.status.value.conflicts.first { it.kind=="providers" }
            assertEquals("Android offline",db.workspaceOne("SELECT name FROM providers WHERE providerId=?",p)!!.text("name"))
            assertTrue(sync.resolve(conflict.id,conflict.localHash,false))
            assertTrue(sync.syncNow())
            assertEquals("Hub offline",db.workspaceOne("SELECT name FROM providers WHERE providerId=?",p)!!.text("name"))
            val devices=admin("/workspace").jsonObject["devices"]!!.jsonArray
            val device=devices.single { it.jsonObject.text("label")=="Android CI" }.jsonObject.text("id")
            admin("/workspace/revoke",workspaceMap("device_id" to device,"confirm" to JsonPrimitive(true)))
            assertFalse(sync.syncNow())
            assertEquals(2L,db.workspaceOne("SELECT COUNT(*) AS n FROM my_models")!!.number("n"))
            assertEquals("top-secret-example-key",vault.bearerToken(credential.text("credentialId"),"https://provider.example"))
            assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM workspace_sync_records WHERE instr(localJson,?)>0 OR instr(baseJson,?)>0","top-secret-example-key","top-secret-example-key")!!.number("n"))
            assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM workspace_sync_conflicts WHERE instr(localJson,?)>0 OR instr(remoteJson,?)>0","top-secret-example-key","top-secret-example-key")!!.number("n"))
            sync.disconnect()
            assertFalse(sync.status.value.connected)
        } finally { room.close() }
    }
}
