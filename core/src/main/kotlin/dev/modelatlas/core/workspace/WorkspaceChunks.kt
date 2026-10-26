package dev.modelatlas.core.workspace

import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.*

private val workspaceDiscardOutput=object: OutputStream() {
    override fun write(value: Int) {}
    override fun write(value: ByteArray,offset: Int,length: Int) {}
}

object WorkspaceChunkLimits {
    const val CHUNK = 256 * 1024
    const val RECORD = 64L * 1024 * 1024
    const val REQUEST = 65L * 1024 * 1024
    const val REPLY = 72L * 1024 * 1024
}

class WorkspaceHttpException(val status: Int) : IllegalStateException(
    when(status) {
        401,403 -> "裝置權限已撤銷，或配對碼已過期"
        409 -> "同步進度或資料有衝突，請先讀取持久化狀態"
        else -> "同步中心暫時無法接受資料（HTTP $status），本機修改已保留"
    }
)
class WorkspacePageLimitException : IllegalArgumentException("同步頁面過大，本機資料已保留")

@Serializable
data class WorkspaceChunkCapabilities(
    val protocol: Int,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("chunked_exchange") val version: Int,
    @SerialName("chunk_bytes") val chunkBytes: Int,
    @SerialName("record_max_bytes") val recordMaxBytes: Long,
    @SerialName("request_max_bytes") val requestMaxBytes: Long,
    @SerialName("reply_max_bytes") val replyMaxBytes: Long,
) {
    fun validate(workspace: String) {
        require(protocol==1 && workspaceId==workspace && version==1 && chunkBytes==WorkspaceChunkLimits.CHUNK &&
            recordMaxBytes==WorkspaceChunkLimits.RECORD && requestMaxBytes==WorkspaceChunkLimits.REQUEST &&
            replyMaxBytes==WorkspaceChunkLimits.REPLY) { "同步中心不支援此大資料協定，本機資料已保留" }
    }
}

@Serializable
data class WorkspaceTransferReceipt(
    val id: String,
    val state: String,
    @SerialName("request_sha256") val requestHash: String,
    @SerialName("request_bytes") val requestBytes: Long,
    @SerialName("received_bytes") val receivedBytes: Long,
    @SerialName("reply_sha256") val replyHash: String,
    @SerialName("reply_bytes") val replyBytes: Long,
    @SerialName("progress_bytes") val progressBytes: Long,
    val consumed: Boolean,
    val problem: String,
) {
    fun validate(id: String,hash: String,bytes: Long,replyHash: String="",replyBytes: Long=0) {
        require(this.id==id && requestHash==hash && requestBytes==bytes && bytes in 1..WorkspaceChunkLimits.REQUEST &&
            receivedBytes in 0..bytes && (receivedBytes==bytes || receivedBytes%WorkspaceChunkLimits.CHUNK==0L) &&
            state in setOf("UPLOADING","UPLOADED","DONE","ACKED","REJECTED","ABORTED")) { "持久化同步收據不符，本機資料已保留" }
        if(state=="DONE" || state=="ACKED") require(receivedBytes==bytes && this.replyBytes in 1..WorkspaceChunkLimits.REPLY && this.replyHash.matches(Regex("[a-f0-9]{64}")))
        if(replyHash.isNotEmpty()) require(this.replyHash==replyHash && this.replyBytes==replyBytes) { "已完成的同步結果被更改，本機資料已保留" }
    }
}

@Serializable
data class WorkspaceChangeReceipt(val key: String,val digest: String,val base: Long)
@Serializable
data class WorkspaceRequestReceipt(val cursor: Long,val changes: List<WorkspaceChangeReceipt>,val fetch: List<String>) {
    companion object {
        fun capture(value: WorkspaceExchange) = WorkspaceRequestReceipt(value.cursor,
            value.changes.map { WorkspaceChangeReceipt(it.key,workspaceContentDigest(it),it.baseRevision) },value.fetch.map { it.key })
    }
}

class WorkspaceCountingOutput(private val target: OutputStream,private val limit: Long) : OutputStream() {
    var count: Long=0;private set
    override fun write(value: Int) { requireRoom(1);target.write(value);count++ }
    override fun write(value: ByteArray,offset: Int,length: Int) { requireRoom(length);target.write(value,offset,length);count+=length }
    private fun requireRoom(length: Int) { if(length<0 || count>limit-length) throw WorkspacePageLimitException() }
    override fun flush() = target.flush()
}

@OptIn(ExperimentalSerializationApi::class)
fun <T> workspaceEncodeToStream(serializer: SerializationStrategy<T>,value: T,output: OutputStream,limit: Long): Long {
    val bounded=WorkspaceCountingOutput(output,limit)
    val writer=WorkspaceBoundedJson(bounded)
    when {
        value is WorkspaceRecord && serializer.descriptor.serialName==WorkspaceRecord.serializer().descriptor.serialName -> writer.record(value)
        value is WorkspaceExchange && serializer.descriptor.serialName==WorkspaceExchange.serializer().descriptor.serialName -> writer.exchange(value)
        value is WorkspaceReply && serializer.descriptor.serialName==WorkspaceReply.serializer().descriptor.serialName -> writer.reply(value)
        value is JsonElement && serializer.descriptor.serialName==JsonElement.serializer().descriptor.serialName -> writer.element(value)
        else -> error("不支援的同步編碼，本機資料已保留")
    }
    writer.finish();return bounded.count
}
fun workspaceRecordBytes(value: WorkspaceRecord): Long =
    workspaceEncodeToStream(WorkspaceRecord.serializer(),value,workspaceDiscardOutput,WorkspaceChunkLimits.RECORD)

private fun canonicalWorkspaceData(value: JsonElement,depth: Int=0): JsonElement {
    require(depth<=256) { "同步資料巢狀過深，本機資料已保留" }
    return when(value) {
        is JsonObject -> JsonObject(value.keys.sorted().associateWith { canonicalWorkspaceData(value.getValue(it),depth+1) })
        is JsonArray -> JsonArray(value.map { canonicalWorkspaceData(it,depth+1) })
        else -> value
    }
}
fun workspaceHexDigest(bytes: ByteArray): String=bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
fun workspaceBytesDigest(bytes: ByteArray): String=workspaceHexDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
fun workspaceEncodedRecordDigest(value: WorkspaceRecord): String {
    val hash=MessageDigest.getInstance("SHA-256")
    workspaceEncodeToStream(WorkspaceRecord.serializer(),value,DigestOutputStream(workspaceDiscardOutput,hash),WorkspaceChunkLimits.RECORD)
    return workspaceHexDigest(hash.digest())
}
/** Exactly the native record.same relation: object ordering is irrelevant,
 * numeric literals, null, deletion and every data field remain significant. */
fun workspaceContentDigest(value: WorkspaceRecord): String {
    return workspaceDataDigest(value,fingerprint=true)
}
/** Hash the actual SQLite representation after native number normalization and
 * UTF-8 replacement, rather than caching the input's unpersisted spelling. */
fun workspacePersistedContentDigest(value: WorkspaceRecord): String = workspaceDataDigest(value,fingerprint=false)
private fun workspaceDataDigest(value: WorkspaceRecord,fingerprint: Boolean): String {
    val hash=MessageDigest.getInstance("SHA-256")
    val out=DigestOutputStream(workspaceDiscardOutput,hash)
    out.write(if(value.deleted)1 else 0)
    val bounded=WorkspaceCountingOutput(out,WorkspaceChunkLimits.RECORD)
    val writer=WorkspaceBoundedJson(bounded,fingerprint)
    writer.element(canonicalWorkspaceData(value.data));writer.finish()
    return workspaceHexDigest(hash.digest())
}
