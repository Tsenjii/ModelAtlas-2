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
