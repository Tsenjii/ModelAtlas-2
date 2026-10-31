package dev.modelatlas.core.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WorkspaceChunksTest {
    @Test fun escapedNearLimitStringsUseBoundedEncoderAllocation() {
        val text="\n".repeat(((WorkspaceChunkLimits.RECORD-1024)/2).toInt())
        val value=WorkspaceRecord("external_observations","large",data=buildJsonObject { put("payloadJson",text) })
        var bytes=0L;var largest=0
        val target=object: java.io.OutputStream() {
            override fun write(value: Int) { bytes++ }
            override fun write(value: ByteArray,offset: Int,length: Int) { bytes+=length;largest=maxOf(largest,length) }
        }
        workspaceRecordBytes(WorkspaceRecord("warmup","small"))
        val meter=java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue("HotSpot must expose real allocation accounting",meter.isThreadAllocatedMemorySupported)
        meter.isThreadAllocatedMemoryEnabled=true
        val thread=Thread.currentThread().id
        val before=meter.getThreadAllocatedBytes(thread)
        val count=workspaceEncodeToStream(WorkspaceRecord.serializer(),value,target,WorkspaceChunkLimits.RECORD)
        val allocated=meter.getThreadAllocatedBytes(thread)-before
        assertEquals(bytes,count)
        assertTrue("Must actually encode a near-64 MiB record",count>WorkspaceChunkLimits.RECORD-1024)
        assertTrue("UTF-8 writes must stay bounded: $largest",largest<=8192)
        assertTrue("Encoder allocated $allocated bytes for one existing String",allocated in 0..8L*1024*1024)
        println("MODELATLAS_BOUNDED_JSON_ALLOCATION bytes=$count allocated=$allocated largest_write=$largest")
    }
    @Test fun boundedWriterPreservesLegacyEnvelopesEscapesDefaultsAndUtf8Boundaries() {
        val text="a".repeat(8191)+"😀界\u2028\u2029<>&"+(0..31).map { it.toChar() }.joinToString("")+"\"\\\ud800x\udc00"
        val record=WorkspaceRecord("messages","unicode",data=buildJsonObject {
            put("text",text);put("integer",9007199254740993L)
            put("exponent",WorkspaceJson.parseToJsonElement("1e1"));put("unsigned",WorkspaceJson.parseToJsonElement("18446744073709551615"))
            put("values",JsonArray(listOf(JsonPrimitive(1.0),JsonNull,JsonPrimitive(false),JsonPrimitive("null"))))
            put("nested",buildJsonObject { put("quote\"\n界",text) })
        })
        val secrets=mapOf("credential" to WorkspaceSecret("credential",listOf(WorkspaceAuthField("Authorization",false,text))),"empty" to WorkspaceSecret("empty"))
        fun <T> check(serializer: kotlinx.serialization.SerializationStrategy<T>,value: T,limit: Long) {
            val output=ByteArrayOutputStream();workspaceEncodeToStream(serializer,value,output,limit)
            assertArrayEquals(WorkspaceJson.encodeToString(serializer,value).toByteArray(Charsets.UTF_8),output.toByteArray())
        }
        check(WorkspaceRecord.serializer(),record,WorkspaceChunkLimits.RECORD)
        check(WorkspaceExchange.serializer(),WorkspaceExchange(changes=listOf(record),secrets=secrets,fetch=listOf(record)),WorkspaceChunkLimits.REQUEST)
        check(WorkspaceReply.serializer(),WorkspaceReply(1,"workspace",4,false,listOf(record),listOf(record),listOf(record),secrets),WorkspaceChunkLimits.REPLY)
        check(WorkspaceExchange.serializer(),WorkspaceExchange(),WorkspaceChunkLimits.REQUEST)
    }
    @Test fun contentFingerprintRetainsExactLiteralsAndPersistedHashMatchesActualWire() {
        fun record(value: JsonElement)=WorkspaceRecord("kind","id",data=buildJsonObject { put("value",value) })
        val variants=listOf(WorkspaceJson.parseToJsonElement("1e1"),JsonPrimitive(10.0),JsonPrimitive("1e1"),JsonNull,JsonPrimitive("null"),JsonPrimitive("\ud800"),JsonPrimitive("\udc00"),JsonPrimitive("?"))
        val hashes=variants.map { workspaceContentDigest(record(it)) }
        assertEquals(variants.size,hashes.toSet().size)
        for (value in variants) {
            val original=record(value)
            val persisted=WorkspaceJson.decodeFromString(WorkspaceRecord.serializer(),String(ByteArrayOutputStream().also {
                workspaceEncodeToStream(WorkspaceRecord.serializer(),original,it,WorkspaceChunkLimits.RECORD)
            }.toByteArray(),Charsets.UTF_8))
            assertEquals(workspaceContentDigest(persisted),workspacePersistedContentDigest(original))
        }
    }
    @OptIn(ExperimentalSerializationApi::class)
    @Test fun streamEncodingKeepsLegacyBytesAndLargeIntegers() {
        val value=WorkspaceRecord("android:samples","retained",revision=9007199254740993L,
            data=buildJsonObject { put("text","界😀".repeat(400_000));put("count",9007199254740993L);put("group",JsonNull) })
        val out=ByteArrayOutputStream()
        val n=workspaceEncodeToStream(WorkspaceRecord.serializer(),value,out,WorkspaceChunkLimits.RECORD)
        val bytes=out.toByteArray()
        assertEquals(bytes.size.toLong(),n)
        assertArrayEquals(WorkspaceJson.encodeToString(WorkspaceRecord.serializer(),value).toByteArray(Charsets.UTF_8),bytes)
        assertEquals(value,WorkspaceJson.decodeFromStream(WorkspaceRecord.serializer(),ByteArrayInputStream(bytes)))
        assertEquals(workspaceDigest(WorkspaceJson.encodeToString(WorkspaceRecord.serializer(),value)),workspaceEncodedRecordDigest(value))
    }
    @Test fun contentReceiptMatchesNativeEqualityWithoutDroppingNumericLiteralOrDeletion() {
        fun data(reverse: Boolean): JsonObject=if(reverse) buildJsonObject { put("b",JsonArray(listOf(JsonNull,JsonPrimitive("界"))));put("a",1) }
            else buildJsonObject { put("a",1);put("b",JsonArray(listOf(JsonNull,JsonPrimitive("界")))) }
        val a=WorkspaceRecord("kind","id",data=data(false));val b=a.copy(revision=99,baseRevision=44,seq=90,data=data(true))
        assertEquals(a.data,b.data);assertEquals(workspaceContentDigest(a),workspaceContentDigest(b))
        assertNotEquals(workspaceContentDigest(a),workspaceContentDigest(a.copy(deleted=true)))
        assertNotEquals(workspaceContentDigest(a),workspaceContentDigest(a.copy(data=JsonObject(a.data+("a" to JsonPrimitive(1.0))))))
    }
    @Test fun utf8RecordLimitRejectsTooLargeUnicodeWithoutBuildingAFullWireBody() {
        val text="界".repeat((WorkspaceChunkLimits.RECORD/3).toInt()+1)
        try { workspaceRecordBytes(WorkspaceRecord("messages","bound",data=buildJsonObject { put("content",text) }));fail("Accepted more than 64 MiB of UTF-8") }
        catch(_: WorkspacePageLimitException) { }
        val out=ByteArrayOutputStream()
        val bounded=WorkspaceCountingOutput(out,3)
        bounded.write(byteArrayOf(1,2,3))
        try { bounded.write(4);fail("Wrote beyond bound") } catch(_: WorkspacePageLimitException) { }
        assertArrayEquals(byteArrayOf(1,2,3),out.toByteArray())
    }
    @Test fun capabilityNegotiationCannotWidenRecordOrReplyLimits() {
        val c=WorkspaceChunkCapabilities(1,"workspace",1,WorkspaceChunkLimits.CHUNK,WorkspaceChunkLimits.RECORD,WorkspaceChunkLimits.REQUEST,WorkspaceChunkLimits.REPLY)
        c.validate("workspace")
        for(bad in listOf(c.copy(workspaceId="other"),c.copy(chunkBytes=Int.MAX_VALUE),c.copy(recordMaxBytes=Long.MAX_VALUE),c.copy(replyMaxBytes=0))) {
            try { bad.validate("workspace");fail("Accepted changed scope or unbounded limit") } catch(_: IllegalArgumentException) { }
        }
        val pair="""{"protocol":1,"device_id":"device","workspace_id":"workspace","token":"matlas_device_fixture","share_secrets":false,"chunked_exchange":1}"""
        try { WorkspaceJson.decodeFromString(WorkspacePairResult.serializer(),pair);fail("Changed strict v1 pair contract") } catch(_: kotlinx.serialization.SerializationException) { }
    }
    @Test fun remoteReceiptRequiresExactIdHashBytesAndImmutableReply() {
        val hash="a".repeat(64);val reply="b".repeat(64)
        val v=WorkspaceTransferReceipt("stable","DONE",hash,100,100,reply,200,0,false,"")
        v.validate("stable",hash,100,reply,200)
        for(bad in listOf(v.copy(id="another"),v.copy(requestHash="c".repeat(64)),v.copy(receivedBytes=99),v.copy(replyBytes=WorkspaceChunkLimits.REPLY+1),v.copy(state="UNKNOWN"))) {
            try { bad.validate("stable",hash,100,reply,200);fail("Accepted a changed receipt") } catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun transportRetainsHttpStatusWithoutExposingResponseSecrets() {
        val secret="synthetic-response-secret"
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(413).message("Too Large")
                .body(secret.toResponseBody("text/plain".toMediaType())).build()
        }.build()
        try { WorkspaceTransport(client).chunk("https://atlas.example","capabilities","matlas_device_fixture",JsonObject(emptyMap()));fail("Accepted rejected page") }
        catch(e: WorkspaceHttpException) { assertEquals(413,e.status);assertFalse(e.toString().contains(secret)) }
    }
}
