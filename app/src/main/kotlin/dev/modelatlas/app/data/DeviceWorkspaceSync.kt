package dev.modelatlas.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.modelatlas.app.security.AndroidCredentialVault
import dev.modelatlas.app.security.WorkspaceVaultCiphertext
import dev.modelatlas.core.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
internal data class WorkspaceConnection(
    val url: String, val device: String, val workspace: String, val tokenRef: String,
    val shareSecrets: Boolean, val cursor: Long=0, val lastSuccess: Long=0,
)
data class DeviceWorkspaceStatus(
    val connected: Boolean=false, val center: String="", val shareSecrets: Boolean=false,
    val busy: Boolean=false, val pending: Int=0, val stored: Int=0,
    val pendingProjection: Int=0, val lastSuccess: Long=0, val problem: Boolean=false,
    val conflicts: List<DeviceWorkspaceConflict> = emptyList(),
    val paused: Boolean=false,val transferStage: String="",val uploadedBytes: Long=0,val uploadBytes: Long=0,
    val downloadedBytes: Long=0,val downloadBytes: Long=0,
)
data class DeviceWorkspaceConflict(val id: String,val kind: String,val local: String,val remote: String,val localHash: String)

/** Product callers are Settings and the opt-in WorkspaceSyncWorker. Every cursor
 * checkpoint, conflict and destination ciphertext commits with its imported data.
 * Network requests and Keystore operations are outside Room transactions. */
class DeviceWorkspaceSync(
    private val room: AtlasDatabase,
    private val vault: AndroidCredentialVault,
    private val transport: WorkspaceTransport = WorkspaceTransport(),
) {
    private val lock=Mutex()
    private val mutable=MutableStateFlow(DeviceWorkspaceStatus())
    val status=mutable.asStateFlow()
    private val db: SupportSQLiteDatabase get()=room.openHelper.writableDatabase
    private fun connection(): WorkspaceConnection? = db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='connection'")
        ?.text("valueJson")?.let { WorkspaceJson.decodeFromString(WorkspaceConnection.serializer(),it) }
    private fun save(value: WorkspaceConnection) {
        db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('connection',?)",
            arrayOf(WorkspaceJson.encodeToString(WorkspaceConnection.serializer(),value)))
    }
    private fun transaction(action: () -> Unit)=room.runInTransaction(Runnable { action() })
    private fun setImporting(active: Boolean) = db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('importing',?)",arrayOf(if(active) "1" else "0"))
    private fun current(kind: String,id: String): WorkspaceRecord? = db.workspaceReadRecord("localJson",kind,id)
    private fun write(v: WorkspaceRecord,base: WorkspaceRecord,dirty: Boolean,pending: Boolean) {
        val localHash=workspaceContentDigest(v)
        val baseHash=if(v.same(base))localHash else workspaceContentDigest(base)
        db.workspaceWithRecordJSON(v) { local ->
            db.workspaceWithRecordJSON(base) { baseline ->
                db.execSQL("INSERT OR REPLACE INTO workspace_sync_records VALUES(?,?,?,${baseline.expression},${local.expression},?,?)",
                    (listOf(v.kind,v.id,base.revision)+baseline.args+local.args+listOf(if(dirty)1 else 0,if(pending)1 else 0)).toTypedArray())
            }
        }
        db.workspaceSaveRecordHashes(v.kind,v.id,localHash,baseHash)
    }
    private fun enqueue(v: WorkspaceRecord) {
        val row=db.workspaceOne("SELECT revision FROM workspace_sync_records WHERE kind=? AND id=?",v.kind,v.id)
        val revision=row?.number("revision") ?: 0L
        val empty=WorkspaceRecord(v.kind,v.id)
        val baseHash=db.workspaceRecordHashes(v.kind,v.id)?.second ?:
            workspaceContentDigest(db.workspaceReadRecord("baseJson",v.kind,v.id) ?: empty)
        val local=v.copy(baseRevision=revision);val localHash=workspaceContentDigest(local)
        db.workspaceWithRecordJSON(local) { json ->
            db.execSQL("INSERT OR IGNORE INTO workspace_sync_records VALUES(?,?,0,?,?,0,0)",arrayOf(v.kind,v.id,empty.encode(),empty.encode()))
            db.execSQL("UPDATE workspace_sync_records SET localJson=${json.expression},dirty=?,pendingProjection=0 WHERE kind=? AND id=?",
                (json.args+listOf(if(revision==0L || localHash!=baseHash)1 else 0,v.kind,v.id)).toTypedArray())
        }
        db.workspaceSaveRecordHashes(v.kind,v.id,localHash,baseHash)
    }

    suspend fun initialize()=withContext(Dispatchers.IO) { lock.withLock { installWorkspaceHashJournal(db); refresh() } }
    suspend fun connect(url: String,label: String,code: String): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        mutable.value=mutable.value.copy(busy=true,problem=false)
        try {
            check(connection()==null)
            val center=WorkspaceTransport.origin(url)
            val paired=transport.pair(center,code,label)
            val ref="workspace-token-"+UUID.randomUUID()
            val sealed=vault.sealForSync(ref,center,paired.token)
            transaction {
                installWorkspaceJournal(db)
                vault.putSyncedCiphertext(db,sealed)
                // Keep all imported records and histories across re-pairing.
                // A fresh baseline must be negotiated rather than reusing a
                // cursor from another workspace.
                for(row in db.workspaceRows("SELECT kind,id FROM workspace_sync_records")) {
                    val empty=WorkspaceRecord(row.text("kind"),row.text("id"))
                    db.execSQL("UPDATE workspace_sync_records SET revision=0,baseJson=?,dirty=1 WHERE kind=? AND id=?",
                        arrayOf(empty.encode(),empty.kind,empty.id))
                }
                db.execSQL("DELETE FROM workspace_sync_locators")
                db.execSQL("DELETE FROM workspace_sync_state WHERE `key`='fetch'")
                db.execSQL("UPDATE workspace_sync_conflicts SET state='DISCONNECTED' WHERE state='OPEN'")
                save(WorkspaceConnection(center,paired.deviceId,paired.workspaceId,ref,paired.shareSecrets))
            }
            refresh(); true
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { refresh(problem=true); false }
        finally { if(mutable.value.busy) refresh(problem=true) }
    } }
    suspend fun disconnect()=withContext(Dispatchers.IO) { lock.withLock {
        val previous=connection()
        transaction {
            WorkspaceChunkStore.read(db)?.let { WorkspaceChunkStore.clear(db,it.copy(state="ABORTED")) }
            db.execSQL("DELETE FROM workspace_sync_state WHERE `key` IN ('connection','chunk-paused','fetch')")
        }
        // Ciphertext may remain as an inaccessible tombstone if cleanup fails;
        // no transport reads it after the durable connection is removed.
        previous?.let { vault.remove(it.tokenRef) }
        refresh()
    } }

    /** Journal pages bound RAM without dropping histories or mirroring the
     * implementation's row count into a hard product truncation. */
    private fun captureNative(): Int {
        val projector=WorkspaceProjection(db)
        var captured=0
        for(table in workspaceNativeTables) {
            val changed=db.workspaceRows("SELECT v.rowId,v.version FROM workspace_sync_versions v LEFT JOIN workspace_sync_locators l ON l.tableName=v.tableName AND l.rowId=v.rowId WHERE v.tableName=? AND (l.version IS NULL OR v.version!=l.version) ORDER BY v.rowId LIMIT ?",table,100-captured)
            for(change in changed) {
                val rowId=change.number("rowId"); val version=change.number("version")
                val row=db.workspaceNativeRow(table,rowId)
                val previous=db.workspaceOne("SELECT * FROM workspace_sync_locators WHERE tableName=? AND rowId=?",table,rowId)
                val exports=if(row!=null) projector.export(table,row) else {
                    val refs=previous?.text("linksJson")?.takeIf{it.isNotEmpty()}?.let { WorkspaceJson.parseToJsonElement(it).jsonArray }.orEmpty()
                    refs.mapNotNull { value ->
                        val ref=value.jsonObject
                        current(ref.text("kind"),ref.text("id"))?.let {
                            if(table in WorkspaceProjection.immutableNative) null else it.copy(deleted=true,data=JsonObject(emptyMap()))
                        }
                    }
                }
                for(record in exports) enqueue(record)
                val refs=JsonArray(exports.map { workspaceMap("kind" to it.kind,"id" to it.id) }).toString()
                db.execSQL("INSERT OR REPLACE INTO workspace_sync_locators VALUES(?,?,?,?,?)",arrayOf(table,rowId,version,row?.let { "sha256:"+workspaceContentDigest(WorkspaceRecord("android:$table","native-row",data=it)) }.orEmpty(),refs))
                captured++
                if(captured>=100) return captured
            }
        }
        return captured
    }
    private fun capture()=transaction { captureNative() }
    private fun nativeCredential(v: WorkspaceRecord): String =
        db.workspaceOne("SELECT nativeId FROM workspace_sync_links WHERE kind='credentials' AND wireId=?",v.id)?.text("nativeId")
            ?: (v.data["_native_android"] as? JsonObject)?.text("native_id")?.takeIf{it.matches(Regex("[A-Za-z0-9-]{1,128}"))}
            ?: WorkspaceProjection(db).nativeID(v.kind,v.id)

    private fun envelope(c: WorkspaceConnection,recordLimit: Long=32L*1024*1024,pageLimit: Long=recordLimit): WorkspaceExchange {
        val order="CASE kind WHEN 'providers' THEN 0 WHEN 'accounts' THEN 1 WHEN 'endpoint_profiles' THEN 2 WHEN 'endpoint_bindings' THEN 3 WHEN 'credentials' THEN 4 WHEN 'models' THEN 5 WHEN 'my_models' THEN 6 ELSE 20 END"
        val rows=db.workspaceRows("SELECT r.kind,r.id FROM workspace_sync_records r WHERE r.dirty=1 AND NOT EXISTS(SELECT 1 FROM workspace_sync_conflicts c WHERE c.kind=r.kind AND c.recordId=r.id AND c.state='OPEN') ORDER BY $order,r.id LIMIT 100")
        val changes=mutableListOf<WorkspaceRecord>(); val secrets=linkedMapOf<String,WorkspaceSecret>()
        var size=0L
        for(row in rows) {
            val snapshot=current(row.text("kind"),row.text("id")) ?: continue
            var v=snapshot
            var secret: WorkspaceSecret?=null
            if(v.kind=="credentials" && !v.deleted && !v.unmappedAuth() && c.shareSecrets) {
                val ref=nativeCredential(v)
                val origin=vault.originOf(ref)
                if(origin!=null && origin==originOf(v.data.text("_base_url"))) {
                    secret=WorkspaceSecret(vault.bearerToken(ref,origin))
                    val digest=secret.digest()
                    val changed=v.data.text("_secret_digest")!=digest
                    v=v.copy(data=JsonObject(v.data+workspaceMap("_secret_digest" to digest,
                        "_credential_version" to maxOf(1L,v.data.number("_credential_version")+if(changed)1 else 0))))
                    // CAS against the captured row, never replace a concurrent
                    // edit while preparing the privileged envelope.
                    transaction {
                        val live=current(v.kind,v.id)
                        if(live?.same(snapshot)==true) enqueue(v)
                    }
                }
            }
            val recordBytes=workspaceRecordBytes(v)
            val bytes=recordBytes+(secret?.key?.toByteArray()?.size?:0)+1024
            if(recordBytes>recordLimit || bytes>pageLimit) throw WorkspacePageLimitException()
            if(size+bytes>pageLimit) break
            size+=bytes; changes+=v
            if(secret!=null) secrets[v.key]=secret
        }
        val fetch=db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='fetch'")?.text("valueJson")
            ?.let { listOf(workspaceRecord(it)) }.orEmpty()
        return WorkspaceExchange(cursor=c.cursor,changes=changes,secrets=secrets,fetch=fetch)
    }
    private fun paused(): Boolean = db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='chunk-paused'")?.text("valueJson")=="1"
    suspend fun pause(value: Boolean)=withContext(Dispatchers.IO) {
        db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('chunk-paused',?)",arrayOf(if(value)"1" else "0"))
        if(value) transport.cancelChunkCalls()
        refresh()
    }
    suspend fun discardPending(): Boolean=withContext(Dispatchers.IO) { lock.withLock {
        try {
            val c=connection() ?: return@withLock false
            val token=vault.bearerToken(c.tokenRef,c.url);val context=currentCoroutineContext()
            WorkspaceChunkStore(room,{db},vault,transport,::connection,::paused,{context.ensureActive()}) {}.discard(c,token)
            refresh();true
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { refresh(problem=true);false }
    } }
    private suspend fun chunkedPage(c: WorkspaceConnection,token: String): WorkspaceChunkImport {
        val context=currentCoroutineContext()
        val capabilities=WorkspaceJson.decodeFromJsonElement(WorkspaceChunkCapabilities.serializer(),transport.chunk(c.url,"capabilities",token,JsonObject(emptyMap())))
        capabilities.validate(c.workspace)
        val chunks=WorkspaceChunkStore(room,{db},vault,transport,::connection,::paused,{context.ensureActive()}) { journal ->
            mutable.value=mutable.value.copy(busy=true,transferStage=journal.state,uploadedBytes=journal.uploaded,uploadBytes=journal.requestBytes,
                downloadedBytes=journal.replyParts.sumOf { it.bytes.toLong() },downloadBytes=journal.replyBytes)
        }
        val journal=chunks.stage(c,token) { envelope(c,WorkspaceChunkLimits.RECORD,WorkspaceChunkLimits.REQUEST-2048) }
        return chunks.resume(c,token,journal) { pending,reply ->
            consumeReceipt(c,pending.request,reply,before={
                val stored=WorkspaceChunkStore.read(db) ?: error("同步收據遺失")
                check(stored.id==pending.id && stored.context==pending.context && stored.requestHash==pending.requestHash && stored.replyHash==pending.replyHash)
                !stored.consumed
            },committed={
                val stored=WorkspaceChunkStore.read(db)!!;check(stored.id==pending.id && !stored.consumed)
                WorkspaceChunkStore.save(db,stored.copy(state="CONSUMED",consumed=true))
            })
        }
    }
    private fun negotiateChunks(c: WorkspaceConnection,token: String): Boolean {
        try {
            val caps=WorkspaceJson.decodeFromJsonElement(WorkspaceChunkCapabilities.serializer(),transport.chunk(c.url,"capabilities",token,JsonObject(emptyMap())))
            if(caps.protocol==1 && caps.workspaceId==c.workspace && caps.version==0) return false
            caps.validate(c.workspace);return true
        } catch(e: WorkspaceHttpException) { if(e.status==404)return false;throw e }
    }
    private suspend fun syncPage(c: WorkspaceConnection,token: String,preferChunks: Boolean): WorkspaceChunkImport {
        if(preferChunks || WorkspaceChunkStore.read(db)!=null) return chunkedPage(c,token)
        try {
            val request=envelope(c)
            val reply=transport.exchange(c.url,token,request)
            check(reply.workspaceId==c.workspace)
            consume(c,request,reply)
            return WorkspaceChunkImport(reply.accepted.size,reply.records.size,reply.cursor,reply.hasMore || request.changes.isNotEmpty() || request.fetch.isNotEmpty())
        } catch(_: WorkspacePageLimitException) { return chunkedPage(c,token) }
        catch(e: WorkspaceHttpException) { if(e.status==413) return chunkedPage(c,token);throw e }
    }
    suspend fun syncNow(): Boolean=withContext(Dispatchers.IO) { lock.withLock {
        val first=connection() ?: return@withLock false
        if(paused()) { refresh();return@withLock false }
        mutable.value=mutable.value.copy(busy=true,problem=false)
        try {
            installWorkspaceJournal(db)
            val token=vault.bearerToken(first.tokenRef,first.url)
            val chunked=negotiateChunks(first,token)
            repeat(20) {
                currentCoroutineContext().ensureActive();check(!paused())
                capture()
                val c=connection() ?: error("Disconnected")
                val reply=syncPage(c,token,chunked)
                val pending=db.workspaceOne("SELECT COUNT(*) AS n FROM workspace_sync_records WHERE dirty=1 AND NOT EXISTS(SELECT 1 FROM workspace_sync_conflicts c WHERE c.kind=workspace_sync_records.kind AND c.recordId=workspace_sync_records.id AND c.state='OPEN')")!!.number("n")
                if(!reply.more && pending==0L) { refresh();return@withLock true }
            }
            refresh();true
        } catch(e: CancellationException) { refresh();throw e }
        catch(_: Exception) { refresh(problem=!paused());false }
        finally { if(mutable.value.busy) refresh(problem=!paused()) }
    } }

    private fun conflict(local: WorkspaceRecord,remote: WorkspaceRecord) {
        db.workspaceWithRecordJSON(local) { left ->
            db.workspaceWithRecordJSON(remote) { right ->
                val args=listOf(local.kind,local.id)+left.args+right.args
                if(db.workspaceOne("SELECT id FROM workspace_sync_conflicts WHERE kind=? AND recordId=? AND localJson=${left.expression} AND remoteJson=${right.expression} AND state='OPEN'",*args.toTypedArray())==null) {
                    db.execSQL("INSERT INTO workspace_sync_conflicts VALUES(?,?,?,${left.expression},${right.expression},'OPEN',?)",
                        (listOf(UUID.randomUUID().toString(),local.kind,local.id)+left.args+right.args+System.currentTimeMillis()).toTypedArray())
                }
            }
        }
    }
    private fun consume(c: WorkspaceConnection,request: WorkspaceExchange,reply: WorkspaceReply) {
        consumeReceipt(c,WorkspaceRequestReceipt.capture(request),reply)
    }
    internal fun consumeReceipt(c: WorkspaceConnection,request: WorkspaceRequestReceipt,reply: WorkspaceReply,
        before: () -> Boolean = { true },committed: () -> Unit = {}) {
        check(reply.protocol==1 && reply.workspaceId==c.workspace && reply.cursor>=request.cursor &&
            reply.records.size<=200 && reply.accepted.size<=100 && reply.conflicts.size<=100)
        val requested=request.changes.associateBy { it.key }
        check(reply.accepted.all { it.key in requested } && reply.conflicts.all { it.key in requested })
        val incoming=reply.accepted+reply.records
        val sealed=mutableMapOf<String,WorkspaceVaultCiphertext>()
        val usable=mutableSetOf<String>()
        for(v in incoming.filter { it.kind=="credentials" && !it.deleted && !it.unmappedAuth() }) {
            val ref=nativeCredential(v); val origin=originOf(v.data.text("_base_url"))
            val secret=reply.secrets[v.key]
            if(c.shareSecrets && secret!=null && secret.digest()==v.data.text("_secret_digest")) {
                // Custom auth needs its typed recipe and separate origin-bound
                // refs. Preserve it in the hub, never activate a partial mapping.
                if(secret.fields.isEmpty()) { sealed[v.key]=vault.sealForSync(ref,origin,secret.key); usable+=v.key }
            } else if(vault.originOf(ref)==origin && vault.has(ref)) {
                val local=WorkspaceSecret(vault.bearerToken(ref,origin))
                if(local.digest()==v.data.text("_secret_digest")) usable+=v.key
            }
        }
        transaction {
            check(!paused()) { "同步已暫停，本機資料已保留" }
            if(!before()) return@transaction
            check(connection()?.let { it.url==c.url && it.device==c.device && it.workspace==c.workspace && it.tokenRef==c.tokenRef && it.shareSecrets==c.shareSecrets }==true)
            captureNative()
            setImporting(true)
            for(remote in reply.conflicts) current(remote.kind,remote.id)?.let { conflict(it,remote) }
            val sent=request.changes.associateBy { it.key }
            val accepted=reply.accepted.associateBy { it.key }
            for(remote in incoming.distinctBy { it.key+"/"+it.revision }.sortedBy { it.seq }) {
                val row=db.workspaceOne("SELECT revision,dirty FROM workspace_sync_records WHERE kind=? AND id=?",remote.kind,remote.id)
                val fetched=remote.key in request.fetch
                if(row!=null && row.number("revision")>=remote.revision && !fetched) continue
                val localHash=if(row!=null)db.workspaceLocalDigest(remote.kind,remote.id) else null
                val remoteHash=workspaceContentDigest(remote)
                val own=accepted[remote.key]?.revision==remote.revision && sent[remote.key]?.digest==localHash && localHash!=null
                if(!own && row?.number("dirty")==1L && localHash!=null && localHash!=remoteHash) {
                    current(remote.kind,remote.id)?.let { conflict(it,remote) };continue
                }
                if(accepted[remote.key]?.revision==remote.revision && !own && row!=null) {
                    current(remote.kind,remote.id)?.let { write(it.copy(baseRevision=remote.revision),remote,true,false) };continue
                }
                val nativeAcknowledgement=remote.kind.startsWith("android:") && sent[remote.key]?.digest==remoteHash
                write(remote.copy(baseRevision=remote.revision),remote,false,!nativeAcknowledgement)
                sealed[remote.key]?.let { vault.putSyncedCiphertext(db,it) }
                if(remote.key in request.fetch) db.execSQL("DELETE FROM workspace_sync_state WHERE `key`='fetch'")
            }
            projectPending(usable)
            save(c.copy(cursor=reply.cursor,lastSuccess=System.currentTimeMillis()))
            committed()
            setImporting(false)
        }
    }
    private fun projectPending(usable: Set<String>) {
        val order="CASE kind WHEN 'providers' THEN 0 WHEN 'accounts' THEN 1 WHEN 'endpoint_profiles' THEN 2 WHEN 'credentials' THEN 3 ELSE 20 END"
        val kinds=(listOf("providers","accounts","credentials","endpoint_profiles","models","my_models")+
            workspaceNativeTables.filter { it !in WorkspaceProjection.immutableNative }.map { "android:$it" }).joinToString(","){"'$it'"}
        val rows=db.workspaceRows("SELECT kind,id FROM workspace_sync_records WHERE pendingProjection=1 AND dirty=0 AND kind IN ($kinds) ORDER BY $order,kind,id LIMIT 200")
        db.execSQL("DELETE FROM workspace_sync_state WHERE `key`='projection-failure'")
        for(row in rows) {
            val record=current(row.text("kind"),row.text("id")) ?: continue
            // Append-only measurements from another device are stored with their
            // provenance. Do not present them as this device's local test results.
            if(record.kind.removePrefix("android:") in WorkspaceProjection.immutableNative) continue
            db.execSQL("SAVEPOINT workspace_projection")
            try {
                val done=WorkspaceProjection(db).apply(record,record.key in usable)
                if(done) db.execSQL("UPDATE workspace_sync_records SET pendingProjection=0 WHERE kind=? AND id=?",arrayOf(record.kind,record.id))
                db.execSQL("RELEASE workspace_projection")
            } catch(error: Exception) {
                db.execSQL("ROLLBACK TO workspace_projection"); db.execSQL("RELEASE workspace_projection")
                // Never store SQL exception messages or bind arguments: they can
                // contain user data. This diagnostic identifies code only.
                val location=error.stackTrace.firstOrNull { it.className.startsWith("dev.modelatlas.") }
                val diagnostic=workspaceMap("kind" to record.kind,"type" to error.javaClass.simpleName,
                    "method" to location?.methodName,"line" to location?.lineNumber)
                db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('projection-failure',?)",arrayOf(diagnostic.toString()))
            }
        }
    }
    suspend fun resolve(id: String,localHash: String,keepLocal: Boolean): Boolean=withContext(Dispatchers.IO) { lock.withLock {
        try {
            transaction {
                captureNative()
                val row=db.workspaceOne("SELECT id,kind,recordId FROM workspace_sync_conflicts WHERE id=? AND state='OPEN'",id) ?: error("Conflict gone")
                val local=current(row.text("kind"),row.text("recordId")) ?: error("Missing local")
                check(workspaceEncodedRecordDigest(local)==localHash)
                val remote=db.workspaceReadConflict("remoteJson",id)!!
                if(keepLocal) write(local.copy(baseRevision=remote.revision),remote,true,false)
                else {
                    write(remote.copy(baseRevision=remote.revision),remote,false,false)
                    db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('fetch',?)",arrayOf(WorkspaceRecord(remote.kind,remote.id).encode()))
                }
                db.execSQL("UPDATE workspace_sync_conflicts SET state=? WHERE id=?",arrayOf(if(keepLocal)"LOCAL" else "REMOTE",id))
            }
            refresh(); true
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { refresh(problem=true); false }
    } }
    private fun refresh(problem: Boolean=false) {
        try {
        val c=connection()
        val counts=db.workspaceOne("SELECT COUNT(*) AS stored,COALESCE(SUM(dirty),0) AS pending,COALESCE(SUM(pendingProjection),0) AS projections FROM workspace_sync_records")!!
        val conflicts=db.workspaceRows("SELECT id,kind,recordId FROM workspace_sync_conflicts WHERE state='OPEN' ORDER BY createdAt LIMIT 100").map { row ->
            fun localSummary(): Pair<String,String> {
                val local=current(row.text("kind"),row.text("recordId")) ?: db.workspaceReadConflict("localJson",row.text("id"))!!
                return workspacePreview(local.data) to workspaceEncodedRecordDigest(local)
            }
            val local=localSummary()
            val remote=db.workspaceReadConflict("remoteJson",row.text("id"))!!
            DeviceWorkspaceConflict(row.text("id"),row.text("kind"),local.first,workspacePreview(remote.data),local.second)
        }
        val journal=WorkspaceChunkStore.read(db)
        mutable.value=DeviceWorkspaceStatus(c!=null,c?.url.orEmpty(),c?.shareSecrets?:false,
            pending=counts.number("pending").toInt(),stored=counts.number("stored").toInt(),pendingProjection=counts.number("projections").toInt(),
            lastSuccess=c?.lastSuccess?:0,problem=problem || db.workspaceOne("SELECT `key` FROM workspace_sync_state WHERE `key`='projection-failure'")!=null,conflicts=conflicts,paused=paused(),
            transferStage=journal?.state.orEmpty(),uploadedBytes=journal?.uploaded?:0,uploadBytes=journal?.requestBytes?:0,
            downloadedBytes=journal?.replyParts?.sumOf { it.bytes.toLong() }?:0,downloadBytes=journal?.replyBytes?:0)
        } catch(_: Exception) { mutable.value=mutable.value.copy(busy=false,problem=true) }
    }
    private fun originOf(raw: String): String {
        val url=raw.toHttpUrl()
        require(url.scheme=="https" && url.username.isEmpty() && url.password.isEmpty())
        return dev.modelatlas.core.network.EndpointPolicy.origin(url)
    }
}
