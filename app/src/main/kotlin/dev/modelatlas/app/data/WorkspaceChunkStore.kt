package dev.modelatlas.app.data

import android.util.Base64
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.app.security.WorkspaceVaultCiphertext
import dev.modelatlas.core.workspace.*
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class WorkspaceStoredPart(val offset: Long,val hash: String,val bytes: Int)
@Serializable
internal data class WorkspaceChunkJournal(
    val id: String,val context: String,val origin: String,
    val state: String="BUILDING",val requestHash: String="",val requestBytes: Long=0,
    val request: WorkspaceRequestReceipt=WorkspaceRequestReceipt(0,emptyList(),emptyList()),
    val requestParts: List<WorkspaceStoredPart> = emptyList(),val uploaded: Long=0,
    val replyHash: String="",val replyBytes: Long=0,val replyParts: List<WorkspaceStoredPart> = emptyList(),
    val consumed: Boolean=false,
)
internal data class WorkspaceChunkImport(val accepted: Int,val records: Int,val cursor: Long,val more: Boolean)

/** The existing local-only state and Keystore vault hold the journal and
 * encrypted fragments. No execution grant, token or spool enters a portable
 * export. Every vault operation finishes before a Room transaction starts. */
internal class WorkspaceChunkStore(
    private val room: RoomDatabase,
    private val db: () -> SupportSQLiteDatabase,
    private val vault: AndroidCredentialVault,
    private val transport: WorkspaceTransport,
    private val connection: () -> WorkspaceConnection?,
    private val paused: () -> Boolean,
    private val cancellation: () -> Unit,
    private val progress: (WorkspaceChunkJournal) -> Unit,
) {
    companion object {
        const val ACTIVE="chunk-active"
        fun read(db: SupportSQLiteDatabase): WorkspaceChunkJournal? =
            db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`=?",ACTIVE)?.text("valueJson")?.let {
                WorkspaceJson.decodeFromString(WorkspaceChunkJournal.serializer(),it)
            }
        fun save(db: SupportSQLiteDatabase,value: WorkspaceChunkJournal) {
            require(value.id.matches(Regex("[A-Za-z0-9-]{1,120}")) && value.requestParts.size<=260 && value.replyParts.size<=288)
            db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES(?,?)",arrayOf(ACTIVE,WorkspaceJson.encodeToString(WorkspaceChunkJournal.serializer(),value)))
        }
        private fun ref(id: String,side: String,offset: Long)="ws-frag-$id-${side.first()}-$offset"
        fun clear(db: SupportSQLiteDatabase,value: WorkspaceChunkJournal) {
            require(value.id.matches(Regex("[A-Za-z0-9-]{1,120}")))
            for((side,parts) in listOf("request" to value.requestParts,"reply" to value.replyParts)) for(part in parts) {
                db.execSQL("DELETE FROM workspace_sync_vault WHERE ref=? AND origin=?",arrayOf(ref(value.id,side,part.offset),value.origin))
            }
            // Metadata receipts outlive transport-cache deletion.
            db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES(?,?)",arrayOf("chunk-receipt/${value.id}",
                buildJsonObject { put("id",value.id);put("state",value.state);put("consumed",value.consumed);put("request_sha256",value.requestHash);put("request_bytes",value.requestBytes);put("reply_sha256",value.replyHash);put("reply_bytes",value.replyBytes) }.toString()))
            db.execSQL("DELETE FROM workspace_sync_state WHERE `key`=?",arrayOf(ACTIVE))
        }
    }
    private fun transaction(action: () -> Unit)=room.runInTransaction(Runnable { action() })
    private fun context(c: WorkspaceConnection,token: String): String=workspaceDigest(buildJsonObject {
        put("origin",c.url);put("device",c.device);put("workspace",c.workspace);put("token_ref",c.tokenRef)
        put("token_hash",workspaceDigest(token));put("share",c.shareSecrets)
    }.toString())
    private fun check(c: WorkspaceConnection,token: String,j: WorkspaceChunkJournal,allowPaused: Boolean=false) {
        cancellation()
        check(allowPaused || !paused()) { "同步已暫停，本機資料已保留" }
        val current=connection() ?: error("同步連線已中斷，本機資料已保留")
        check(context(current,token)==j.context && current.url==c.url) { "配對已更改，本機資料已保留" }
    }
    private fun binding(j: WorkspaceChunkJournal,side: String,part: WorkspaceStoredPart): String=workspaceDigest(buildJsonObject {
        put("domain","native-workspace-fragment-v1");put("context",j.context);put("id",j.id);put("side",side)
        put("offset",part.offset);put("sha256",part.hash);put("bytes",part.bytes)
        if(side=="reply") put("complete_sha256",j.replyHash)
    }.toString())
    private fun put(c: WorkspaceConnection,token: String,j: WorkspaceChunkJournal,side: String,offset: Long,data: ByteArray): WorkspaceChunkJournal {
        check(c,token,j)
        val part=WorkspaceStoredPart(offset,workspaceBytesDigest(data),data.size)
        val sealed=vault.sealWorkspaceChunk(ref(j.id,side,offset),c.url,binding(j,side,part),data)
        val next=if(side=="request")j.copy(requestParts=j.requestParts+part,requestBytes=offset+data.size)
            else j.copy(replyParts=j.replyParts+part)
        transaction {
            val active=read(db());check(active?.id==j.id && !active.consumed)
            vault.putSyncedCiphertext(db(),sealed)
            save(db(),next)
        }
        progress(next);return next
    }
    private fun open(j: WorkspaceChunkJournal,side: String,part: WorkspaceStoredPart): ByteArray {
        require(part.offset>=0 && part.offset%WorkspaceChunkLimits.CHUNK==0L && part.bytes in 1..WorkspaceChunkLimits.CHUNK)
        val id=ref(j.id,side,part.offset)
        val raw=db().workspaceOne("SELECT origin,nonce,ciphertext,deleted FROM workspace_sync_vault WHERE ref=?",id) ?: error("加密同步片段遺失，本機資料已保留")
        check(raw.text("origin")==j.origin && raw.number("deleted")==0L)
        val data=vault.openWorkspaceChunk(WorkspaceVaultCiphertext(id,j.origin,raw.text("nonce"),raw.text("ciphertext"),false),binding(j,side,part))
        check(data.size==part.bytes && workspaceBytesDigest(data)==part.hash) { "同步片段雜湊不符，本機資料已保留" }
        return data
    }
    private fun input(j: WorkspaceChunkJournal,side: String): InputStream {
        val parts=if(side=="request")j.requestParts else j.replyParts
        val total=if(side=="request")j.requestBytes else j.replyBytes
        var expected=0L
        for(part in parts) { check(part.offset==expected && part.bytes.toLong()==minOf(WorkspaceChunkLimits.CHUNK.toLong(),total-expected));expected+=part.bytes }
        check(expected==total)
        return object: InputStream() {
            var index=0;var buffer=ByteArray(0);var position=0
            override fun read(): Int { val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255 }
            override fun read(out: ByteArray,start: Int,length: Int): Int {
                if(length==0) return 0
                if(position==buffer.size) {
                    buffer.fill(0);if(index==parts.size) return -1
                    buffer=open(j,side,parts[index++]);position=0
                }
                val n=minOf(length,buffer.size-position);buffer.copyInto(out,start,position,position+n);position+=n;return n
            }
            override fun close() { buffer.fill(0) }
        }
    }
    private fun verify(j: WorkspaceChunkJournal,side: String) {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(32768);var count=0L
        input(j,side).use { stream -> while(true) { val n=stream.read(buffer);if(n<0)break;hash.update(buffer,0,n);count+=n } }
        buffer.fill(0)
        check(count==(if(side=="request")j.requestBytes else j.replyBytes) && workspaceHexDigest(hash.digest())==(if(side=="request")j.requestHash else j.replyHash)) { "完整同步資料雜湊不符，本機資料已保留" }
    }
    fun stage(c: WorkspaceConnection,token: String,makeRequest: () -> WorkspaceExchange): WorkspaceChunkJournal {
        val previous=read(db())
        if(previous!=null) {
            check(c,token,previous)
            if(previous.state!="BUILDING") return previous
            // BUILDING has never been sent. A crash here can discard only its
            // encrypted cache and capture the current product outbox afresh.
            transaction { clear(db(),previous.copy(state="ABORTED")) }
        }
        val request=makeRequest()
        require(request.changes.size<=100 && request.fetch.size<=100 && request.cursor>=0)
        request.changes.forEach { workspaceRecordBytes(it) }
        var j=WorkspaceChunkJournal(UUID.randomUUID().toString(),context(c,token),c.url,request=WorkspaceRequestReceipt.capture(request))
        transaction { check(read(db())==null);save(db(),j) }
        val hash=MessageDigest.getInstance("SHA-256")
        val buffer=ByteArray(WorkspaceChunkLimits.CHUNK);var used=0;var offset=0L
        fun flushPart() {
            if(used==0) return
            val data=buffer.copyOf(used)
            try { j=put(c,token,j,"request",offset,data);offset+=used;used=0 } finally { data.fill(0) }
        }
        val output=object: OutputStream() {
            override fun write(value: Int) { val one=byteArrayOf(value.toByte());write(one,0,1) }
            override fun write(value: ByteArray,start: Int,length: Int) {
                var cursor=start;var remaining=length
                hash.update(value,start,length)
                while(remaining>0) {
                    if(used==buffer.size)flushPart()
                    val n=minOf(remaining,buffer.size-used);value.copyInto(buffer,used,cursor,cursor+n);used+=n;cursor+=n;remaining-=n
                }
            }
        }
        try {
            val bytes=workspaceEncodeToStream(WorkspaceExchange.serializer(),request,output,WorkspaceChunkLimits.REQUEST)
            flushPart();check(bytes==offset)
            j=j.copy(state="READY",requestHash=workspaceHexDigest(hash.digest()),requestBytes=bytes)
            transaction { save(db(),j) };progress(j);return j
        } finally { buffer.fill(0) }
    }
    fun discard(c: WorkspaceConnection,token: String) {
        val j=read(db()) ?: return
        check(c,token,j,true);check(!j.consumed) { "資料已儲存，請先恢復確認同步收據" }
        if(j.state!="BUILDING") {
            var remote: WorkspaceTransferReceipt?=null
            try {
                remote=WorkspaceJson.decodeFromJsonElement(WorkspaceTransferReceipt.serializer(),
                    transport.chunk(c.url,"transfers/status",token,buildJsonObject { put("id",j.id) }))
            } catch(e: WorkspaceHttpException) { if(e.status!=404 || j.state!="READY") throw e }
            if(remote!=null) {
                remote.validate(j.id,j.requestHash,j.requestBytes,j.replyHash,j.replyBytes)
                check(remote.state!="DONE" && remote.state!="ACKED") { "同步中心已完成此筆資料，請恢復讀取既有結果" }
                check(c,token,j,true)
                val stopped=WorkspaceJson.decodeFromJsonElement(WorkspaceTransferReceipt.serializer(),
                    transport.chunk(c.url,"transfers/abort",token,buildJsonObject { put("id",j.id) }))
                stopped.validate(j.id,j.requestHash,j.requestBytes,j.replyHash,j.replyBytes)
                check(stopped.state=="ABORTED")
            }
        }
        transaction { val stored=read(db())!!;check(stored.id==j.id && !stored.consumed);clear(db(),stored.copy(state="ABORTED")) }
    }
    @OptIn(ExperimentalSerializationApi::class)
    fun resume(c: WorkspaceConnection,token: String,value: WorkspaceChunkJournal,
        import: (WorkspaceChunkJournal,WorkspaceReply) -> Unit): WorkspaceChunkImport {
        var j=value
        fun call(path: String,body: JsonObject): JsonObject {
            check(c,token,j);return transport.chunk(c.url,path,token,body)
        }
        fun receipt(raw: JsonObject): WorkspaceTransferReceipt =
            WorkspaceJson.decodeFromJsonElement(WorkspaceTransferReceipt.serializer(),raw).also {
                it.validate(j.id,j.requestHash,j.requestBytes,j.replyHash,j.replyBytes)
            }
        var remote=receipt(call("transfers/begin",buildJsonObject { put("id",j.id);put("bytes",j.requestBytes);put("sha256",j.requestHash) }))
        check(remote.state !in setOf("REJECTED","ABORTED")) { "待同步資料遭拒，本機修改與暫存均已保留" }
        var outcome=WorkspaceChunkImport(0,0,c.cursor,false)
        if(!j.consumed) {
            check(remote.state!="ACKED") { "同步結果已確認但本機缺少收據，資料已保留" }
            verify(j,"request")
            for(part in j.requestParts.filter { it.offset>=remote.receivedBytes }) {
                val data=open(j,"request",part)
                try {
                    val next=receipt(call("transfers/part",buildJsonObject { put("id",j.id);put("offset",part.offset);put("sha256",part.hash);put("data",Base64.encodeToString(data,Base64.NO_WRAP)) }))
                    check(next.receivedBytes==part.offset+part.bytes)
                    j=j.copy(state="UPLOADING",uploaded=next.receivedBytes);transaction { save(db(),j) };progress(j)
                } finally { data.fill(0) }
            }
            if(remote.state!="DONE") remote=receipt(call("transfers/finish",buildJsonObject { put("id",j.id) }))
            check(remote.state=="DONE")
            j=j.copy(state="DOWNLOADING",replyHash=remote.replyHash,replyBytes=remote.replyBytes)
            transaction { save(db(),j) };progress(j)
            var offset=j.replyParts.sumOf { it.bytes.toLong() }
            while(offset<j.replyBytes) {
                val part=call("transfers/reply",buildJsonObject { put("id",j.id);put("offset",offset) })
                val data=Base64.decode(part.text("data"),Base64.NO_WRAP)
                try {
                    check(part.text("id")==j.id && part.number("offset")==offset && part.text("reply_sha256")==j.replyHash && part.number("reply_bytes")==j.replyBytes &&
                        data.size.toLong()==minOf(WorkspaceChunkLimits.CHUNK.toLong(),j.replyBytes-offset) && workspaceBytesDigest(data)==part.text("sha256"))
                    j=put(c,token,j,"reply",offset,data);offset+=data.size
                } finally { data.fill(0) }
            }
            try { verify(j,"reply") } catch(e: Exception) {
                transaction {
                    val stored=read(db())!!;check(!stored.consumed && stored.id==j.id)
                    for(part in stored.replyParts) db().execSQL("DELETE FROM workspace_sync_vault WHERE ref=?",arrayOf(ref(j.id,"reply",part.offset)))
                    save(db(),stored.copy(replyParts=emptyList()))
                };throw e
            }
            check(c,token,j)
            val reply=input(j,"reply").use { WorkspaceJson.decodeFromStream(WorkspaceReply.serializer(),it) }
            import(j,reply) // product, vault, cursor and CONSUMED marker share one Room transaction
            j=read(db()) ?: error("目的端同步收據遺失")
            check(j.consumed)
            outcome=WorkspaceChunkImport(reply.accepted.size,reply.records.size,reply.cursor,reply.hasMore || j.request.changes.isNotEmpty() || j.request.fetch.isNotEmpty())
        }
        val acknowledged=receipt(call("transfers/ack",buildJsonObject { put("id",j.id);put("reply_sha256",j.replyHash) }))
        check(acknowledged.state=="ACKED")
        transaction { val stored=read(db())!!;check(stored.id==j.id && stored.consumed);clear(db(),stored.copy(state="ACKED")) }
        return outcome
    }
}
