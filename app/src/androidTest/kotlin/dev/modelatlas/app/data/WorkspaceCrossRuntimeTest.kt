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
            db.execSQL("UPDATE providers SET name='Android edited' WHERE providerId=?",arrayOf(p))
            assertTrue(sync.syncNow())
            assertEquals("Android edited",admin("/providers").jsonArray.single().jsonObject.text("name"))
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
            assertEquals(1L,db.workspaceOne("SELECT COUNT(*) AS n FROM my_models")!!.number("n"))
            assertEquals("top-secret-example-key",vault.bearerToken(credential.text("credentialId"),"https://provider.example"))
            assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM workspace_sync_records WHERE instr(localJson,?)>0 OR instr(baseJson,?)>0","top-secret-example-key","top-secret-example-key")!!.number("n"))
            assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM workspace_sync_conflicts WHERE instr(localJson,?)>0 OR instr(remoteJson,?)>0","top-secret-example-key","top-secret-example-key")!!.number("n"))
            sync.disconnect()
            assertFalse(sync.status.value.connected)
        } finally { room.close() }
    }
}
