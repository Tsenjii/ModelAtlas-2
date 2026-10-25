package dev.modelatlas.app.data

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.core.workspace.*
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceChunkRuntimeTest {
    private val context: Context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(val client: OkHttpClient,val control: String,val admin: String): AutoCloseable {
        val identity=call(control,"/new",JsonObject(emptyMap()))
        val id=identity.text("id");val url=identity.text("url")
        fun api(path: String,body: JsonObject?=null)=call(url,"/api/v1$path",body)
        fun helper(path: String,body: JsonObject?=null)=call(url,"/__chunks/$path",body)
        private fun call(base: String,path: String,body: JsonObject?): JsonObject {
            val request=Request.Builder().url(base+path).header("Authorization",Credentials.basic("admin",admin))
            if(body!=null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
            return client.newCall(request.build()).execute().use {
                assertEquals("Real isolated Go fixture response",200,it.code)
                WorkspaceJson.parseToJsonElement(it.body!!.string()).jsonObject
            }
        }
        fun pair()=api("/workspace/pair",buildJsonObject { put("share_secrets",true) }).text("code")
        override fun close() { call(control,"/close",buildJsonObject { put("id",id) }) }
    }
    private fun fixture(): Fixture {
        val args=InstrumentationRegistry.getArguments()
        val url=args.getString("workspaceChunksFixture").orEmpty()
        assumeTrue("Started by the real isolated Go fixture in device CI",url.isNotEmpty())
        assertEquals("These actual large transfers run at the original ART heap cap",536870912L,Runtime.getRuntime().maxMemory())
        val certificate=CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(args.getString("workspaceCertificate"),Base64.DEFAULT).inputStream())
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null);setCertificateEntry("fixture",certificate) }
        val factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val manager=factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls=SSLContext.getInstance("TLS").apply { init(null,arrayOf(manager),null) }
        val client=OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,manager).build()
        return Fixture(client,url,args.getString("workspaceAdmin").orEmpty())
    }
    private fun room(name: String)=Room.databaseBuilder(context,AtlasDatabase::class.java,name).build()
    private fun vault(room: AtlasDatabase)=AndroidCredentialVault(context).apply { attachSyncDatabase { room.openHelper.writableDatabase } }
    private fun assertSafe(room: AtlasDatabase) {
        val db=room.openHelper.writableDatabase
        assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM workspace_sync_state WHERE instr(valueJson,'top-secret-example-key')>0 OR instr(valueJson,'matlas_device_')>0")!!.number("n"))
        assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM workspace_sync_vault WHERE instr(ciphertext,'top-secret-example-key')>0 OR instr(ciphertext,'matlas_device_')>0")!!.number("n"))
        assertEquals(1L,db.workspaceOne("SELECT COUNT(*) AS n FROM my_models")!!.number("n"))
        assertEquals(0L,db.workspaceOne("SELECT COUNT(*) AS n FROM samples")!!.number("n"))
    }

    @Test fun nativeNearRecordLimitRetainsPermanentHistoryWithinReal512MiBHeap()=runBlocking {
        fixture().use { f ->
            val source=f.helper("seed",buildJsonObject { put("id",UUID.randomUUID().toString());put("bytes",WorkspaceChunkLimits.RECORD-4096) })
            val name="chunk-limit-"+UUID.randomUUID()+".db";val room=room(name);val vault=vault(room)
            val sync=DeviceWorkspaceSync(room,vault,WorkspaceTransport(f.client))
            try {
                assertTrue(sync.connect(f.url,"Near 64 MiB native client",f.pair()))
                assertTrue("Real native manager must finish the full near-limit history",sync.syncNow())
                val record=room.openHelper.writableDatabase.workspaceReadRecord("localJson","messages",source.text("id"))!!
                val content=record.data.getValue("content")
                val actual=workspaceContentDigest(WorkspaceRecord("fixture","content",data=buildJsonObject { put("content",content) }))
                assertEquals("Every retained UTF-8 content byte must match the actual Go source",source.text("data_fingerprint"),actual)
                assertTrue(workspaceRecordBytes(record)>32L*1024*1024)
                assertTrue(record.data.text("content").endsWith("/尾界"))
                assertNull(WorkspaceChunkStore.read(room.openHelper.writableDatabase))
                assertSafe(room)
                val state=f.helper("state");assertEquals(0L,state.number("telemetry"));assertEquals(0L,state.number("workspace_transfer_parts"));assertTrue(state.number("acked")>0)
                sync.disconnect()
            } finally { room.close();context.deleteDatabase(name) }
        }
    }
    @Test fun lostFinishRestartsFromDatabaseAndReadsImmutableReceipt()=runBlocking {
        fixture().use { f ->
            val finishes=ConcurrentHashMap<String,AtomicInteger>();val dropped=AtomicBoolean();var firstID=""
            val network=f.client.newBuilder().addInterceptor { chain ->
                val request=chain.request();val response=chain.proceed(request)
                if(request.url.encodedPath.endsWith("/transfers/finish")) {
                    val body=Buffer();request.body!!.writeTo(body);val id=WorkspaceJson.parseToJsonElement(body.readUtf8()).jsonObject.text("id")
                    finishes.computeIfAbsent(id){AtomicInteger()}.incrementAndGet()
                    if(!dropped.getAndSet(true)) { firstID=id;response.close();throw IOException("Fixture lost finish response after real commit") }
                }
                response
            }.build()
            val name="chunk-finish-"+UUID.randomUUID()+".db";var room=room(name)
            try {
                var sync=DeviceWorkspaceSync(room,vault(room),WorkspaceTransport(network))
                assertTrue(sync.connect(f.url,"Lost finish",f.pair()));assertFalse(sync.syncNow());assertTrue(dropped.get())
                val pending=WorkspaceChunkStore.read(room.openHelper.writableDatabase)!!
                assertEquals(firstID,pending.id);assertFalse(pending.consumed)
                assertEquals(0L,room.openHelper.writableDatabase.workspaceOne("SELECT COUNT(*) AS n FROM my_models")!!.number("n"))
                assertEquals(1L,f.helper("state").number("done"))
                room.close();room=room(name)
                sync=DeviceWorkspaceSync(room,vault(room),WorkspaceTransport(network))
                assertTrue("Fresh manager must recover from durable receipt",sync.syncNow())
                assertEquals("Original stable ID was committed once",1,finishes.getValue(firstID).get())
                assertNull(WorkspaceChunkStore.read(room.openHelper.writableDatabase))
                assertTrue(room.openHelper.writableDatabase.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`=?","chunk-receipt/$firstID")!!.text("valueJson").contains("\"consumed\":true"))
                assertSafe(room);assertEquals(0L,f.helper("state").number("telemetry"));sync.disconnect()
            } finally { room.close();context.deleteDatabase(name) }
        }
    }
    @Test fun nativeNearLimitExternalObservationUploadsWithoutChangingItsProvenance()=runBlocking {
        fixture().use { f ->
            val name="chunk-outbound-"+UUID.randomUUID()+".db";val room=room(name);val vault=vault(room)
            val sync=DeviceWorkspaceSync(room,vault,WorkspaceTransport(f.client))
            try {
                assertTrue(sync.connect(f.url,"Native large external history",f.pair()));assertTrue(sync.syncNow())
                val id=UUID.randomUUID().toString();val db=room.openHelper.writableDatabase
                fun insertOwnedObservation(): String {
                    val payload="{\"retained\":\""+"x".repeat((WorkspaceChunkLimits.RECORD-8192).toInt())+"/尾界\"}"
                    db.execSQL("INSERT INTO external_observations VALUES(?,'EXTERNAL_FIXTURE','retained-external',NULL,?,9007199254740993,0,'UNKNOWN')",arrayOf(id,payload))
                    val rowId=db.workspaceOne("SELECT rowid AS n FROM external_observations WHERE observationId=?",id)!!.number("n")
                    val data=db.workspaceNativeRow("external_observations",rowId)!!
                    return workspaceContentDigest(WorkspaceRecord("android:external_observations",workspaceID(id),data=data))
                }
                val expected=insertOwnedObservation()
                assertTrue("Native capture, encrypted upload, reply and CAS must finish at the original heap limit",sync.syncNow())
                val state=f.helper("state?observation=$id")
                assertEquals(expected,state.text("observation_fingerprint"))
                assertTrue(state.number("observation_bytes")>32L*1024*1024)
                assertEquals(0L,state.number("telemetry"));assertEquals(0L,state.number("workspace_transfer_parts"))
                assertEquals(1L,db.workspaceOne("SELECT COUNT(*) AS n FROM external_observations WHERE observationId=? AND source='EXTERNAL_FIXTURE' AND canonicalModelId IS NULL AND confidence='UNKNOWN' AND fetchedAt=9007199254740993",id)!!.number("n"))
                assertSafe(room);assertNull(WorkspaceChunkStore.read(db));sync.disconnect()
            } finally { room.close();context.deleteDatabase(name) }
        }
    }
    @Test fun lostAckReopensRoomAndNeverImportsConsumedReplyAgain()=runBlocking {
        fixture().use { f ->
            val dropped=AtomicBoolean()
            val network=f.client.newBuilder().addInterceptor { chain ->
                val response=chain.proceed(chain.request())
                if(chain.request().url.encodedPath.endsWith("/transfers/ack") && !dropped.getAndSet(true)) {
                    response.close();throw IOException("Fixture lost ACK response after real commit")
                };response
            }.build()
            val name="chunk-ack-"+UUID.randomUUID()+".db";var room=room(name)
            try {
                var vault=vault(room);val sync=DeviceWorkspaceSync(room,vault,WorkspaceTransport(network))
                assertTrue(sync.connect(f.url,"Lost ACK",f.pair()));assertFalse(sync.syncNow());assertTrue(dropped.get())
                val first=WorkspaceChunkStore.read(room.openHelper.writableDatabase)!!;assertTrue(first.consumed)
                assertSafe(room)
                room.close();room=room(name);vault=vault(room)
                val db=room.openHelper.writableDatabase
                fun connection()=WorkspaceJson.decodeFromString(WorkspaceConnection.serializer(),db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='connection'")!!.text("valueJson"))
                val c=connection();val token=vault.bearerToken(c.tokenRef,c.url);val stored=WorkspaceChunkStore.read(db)!!
                val manager=WorkspaceChunkStore(room,{db},vault,WorkspaceTransport(f.client),::connection,{false},{}) {}
                manager.resume(c,token,stored) { _,_ -> fail("CONSUMED reply must never invoke destination import again") }
                assertNull(WorkspaceChunkStore.read(db));assertSafe(room)
                assertEquals(0L,f.helper("state").number("workspace_transfer_parts"))
                assertTrue(db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`=?","chunk-receipt/${first.id}")!!.text("valueJson").contains("\"consumed\":true"))
                DeviceWorkspaceSync(room,vault,WorkspaceTransport(f.client)).disconnect()
            } finally { room.close();context.deleteDatabase(name) }
        }
    }
    @Test fun pauseAndExplicitCacheResetKeepOfflineEditsAndLastGood()=runBlocking {
        fixture().use { f ->
            val name="chunk-pause-"+UUID.randomUUID()+".db";val room=room(name);val vault=vault(room)
            val interrupted=AtomicBoolean();lateinit var sync: DeviceWorkspaceSync
            val network=f.client.newBuilder().addInterceptor { chain ->
                val response=chain.proceed(chain.request())
                if(chain.request().url.encodedPath.endsWith("/transfers/part") && !interrupted.getAndSet(true)) {
                    runBlocking { sync.pause(true) };response.close();throw IOException("Fixture stopped after durable real part")
                };response
            }.build()
            sync=DeviceWorkspaceSync(room,vault,WorkspaceTransport(network))
            try {
                assertTrue(sync.connect(f.url,"Pause and reset",f.pair()))
                val id=UUID.randomUUID().toString();val db=room.openHelper.writableDatabase
                db.execSQL("INSERT INTO providers VALUES(?,'本機離線修改','UNKNOWN','UNKNOWN',NULL,0,0,0)",arrayOf(id))
                assertFalse(sync.syncNow());assertTrue(interrupted.get());assertTrue(sync.status.value.paused)
                assertNotNull(WorkspaceChunkStore.read(db))
                assertTrue(sync.discardPending());assertNull(WorkspaceChunkStore.read(db))
                assertEquals("本機離線修改",db.workspaceOne("SELECT name FROM providers WHERE providerId=?",id)!!.text("name"))
                assertTrue(db.workspaceOne("SELECT COUNT(*) AS n FROM workspace_sync_records WHERE dirty=1")!!.number("n")>0)
                val fresh=DeviceWorkspaceSync(room,vault,WorkspaceTransport(f.client))
                fresh.pause(false);assertTrue(fresh.syncNow());assertSafe(room)
                assertEquals("本機離線修改",db.workspaceOne("SELECT name FROM providers WHERE providerId=?",id)!!.text("name"))
                assertEquals(0L,f.helper("state").number("telemetry"));fresh.disconnect()
            } finally { room.close();context.deleteDatabase(name) }
        }
    }
    @Test fun legacyCapability404StillUsesRealV1Exchange()=runBlocking {
        fixture().use { f ->
            val network=f.client.newBuilder().addInterceptor { chain ->
                if(chain.request().url.encodedPath.endsWith("/capabilities")) Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(404).message("Legacy center").body("".toResponseBody("text/plain".toMediaType())).build()
                else chain.proceed(chain.request())
            }.build()
            val name="chunk-v1-"+UUID.randomUUID()+".db";val room=room(name)
            val sync=DeviceWorkspaceSync(room,vault(room),WorkspaceTransport(network))
            try {
                assertTrue(sync.connect(f.url,"Strict legacy client",f.pair()));assertTrue(sync.syncNow());assertSafe(room)
                assertNull(WorkspaceChunkStore.read(room.openHelper.writableDatabase))
                val state=f.helper("state");assertEquals(0L,state.number("acked"));assertEquals(0L,state.number("done"))
                sync.disconnect()
            } finally { room.close();context.deleteDatabase(name) }
        }
    }
    @Test fun corruptedReplyRetainsLastGoodAndRecoversTheSameCommittedReceipt()=runBlocking {
        fixture().use { f ->
            val name="chunk-corrupt-"+UUID.randomUUID()+".db";val room=room(name);val vault=vault(room)
            val initial=DeviceWorkspaceSync(room,vault,WorkspaceTransport(f.client))
            try {
                assertTrue(initial.connect(f.url,"Reply integrity",f.pair()));assertTrue(initial.syncNow())
                val last=initial.status.value.lastSuccess
                val source=f.helper("seed",buildJsonObject { put("id",UUID.randomUUID().toString());put("bytes",4096) })
                val corrupt=AtomicBoolean()
                val network=f.client.newBuilder().addInterceptor { chain ->
                    val response=chain.proceed(chain.request())
                    if(chain.request().url.encodedPath.endsWith("/transfers/reply") && !corrupt.getAndSet(true)) {
                        val raw=WorkspaceJson.parseToJsonElement(response.body!!.string()).jsonObject
                        val bytes=Base64.decode(raw.text("data"),Base64.NO_WRAP);bytes[0]=(bytes[0].toInt() xor 1).toByte()
                        val changed=JsonObject(raw+("data" to JsonPrimitive(Base64.encodeToString(bytes,Base64.NO_WRAP))))
                        response.newBuilder().body(changed.toString().toResponseBody("application/json".toMediaType())).build()
                    } else response
                }.build()
                val broken=DeviceWorkspaceSync(room,vault,WorkspaceTransport(network))
                assertFalse(broken.syncNow());assertTrue(corrupt.get())
                val db=room.openHelper.writableDatabase;val pending=WorkspaceChunkStore.read(db)!!
                assertFalse(pending.consumed)
                assertEquals(last,broken.status.value.lastSuccess)
                assertNull(db.workspaceReadRecord("localJson","messages",source.text("id")));assertSafe(room)
                val restored=DeviceWorkspaceSync(room,vault,WorkspaceTransport(f.client))
                assertTrue(restored.syncNow());assertNotNull(db.workspaceReadRecord("localJson","messages",source.text("id")))
                assertNull(WorkspaceChunkStore.read(db))
                assertNotNull(db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`=?","chunk-receipt/${pending.id}"))
                assertSafe(room);assertEquals(0L,f.helper("state").number("telemetry"));restored.disconnect()
            } finally { room.close();context.deleteDatabase(name) }
        }
    }
    @Test fun rePairClearsOldFetchAndTransportIntentButKeepsProductAndVault()=runBlocking {
        fixture().use { first -> fixture().use { second ->
            val name="chunk-repair-"+UUID.randomUUID()+".db";val room=room(name);val vault=vault(room)
            val sync=DeviceWorkspaceSync(room,vault,WorkspaceTransport(first.client))
            try {
                assertTrue(sync.connect(first.url,"First lineage",first.pair()));assertTrue(sync.syncNow())
                val db=room.openHelper.writableDatabase
                val old=WorkspaceJson.decodeFromString(WorkspaceConnection.serializer(),db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='connection'")!!.text("valueJson"))
                val credential=db.workspaceOne("SELECT credentialId FROM credentials")!!.text("credentialId")
                db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('fetch',?)",arrayOf(WorkspaceRecord("providers",workspaceID("p")).encode()))
                sync.disconnect();assertFalse(sync.status.value.connected)
                assertNull(db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='fetch'"))
                assertFalse(vault.has(old.tokenRef));assertEquals("top-secret-example-key",vault.bearerToken(credential,"https://provider.example"))
                assertTrue(sync.connect(second.url,"Second lineage",second.pair()))
                val fresh=WorkspaceJson.decodeFromString(WorkspaceConnection.serializer(),db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='connection'")!!.text("valueJson"))
                assertNotEquals(old.workspace,fresh.workspace);assertNotEquals(old.tokenRef,fresh.tokenRef);assertEquals(0L,fresh.cursor)
                assertNull(WorkspaceChunkStore.read(db))
                assertNull(db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='fetch'"));assertSafe(room)
                sync.disconnect()
            } finally { room.close();context.deleteDatabase(name) }
        } }
    }
}
