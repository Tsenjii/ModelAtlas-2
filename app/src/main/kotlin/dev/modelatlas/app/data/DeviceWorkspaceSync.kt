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
    private fun stored(kind: String,id: String): JsonObject? {
        val row=db.workspaceOne("SELECT kind,id,revision,dirty,pendingProjection FROM workspace_sync_records WHERE kind=? AND id=?",kind,id) ?: return null
        return JsonObject(row + workspaceMap("localJson" to db.workspaceText("workspace_sync_records","localJson","kind=? AND id=?",kind,id),
            "baseJson" to db.workspaceText("workspace_sync_records","baseJson","kind=? AND id=?",kind,id)))
    }
    private fun current(kind: String,id: String): WorkspaceRecord? =
        db.workspaceText("workspace_sync_records","localJson","kind=? AND id=?",kind,id)?.let(::workspaceRecord)
    private fun write(v: WorkspaceRecord,base: WorkspaceRecord,dirty: Boolean,pending: Boolean) {
        db.execSQL("INSERT OR REPLACE INTO workspace_sync_records VALUES(?,?,?,?,?,?,?)",
            arrayOf(v.kind,v.id,base.revision,base.encode(),v.encode(),if(dirty)1 else 0,if(pending)1 else 0))
    }
    private fun enqueue(v: WorkspaceRecord) {
        val row=stored(v.kind,v.id)
        val base=row?.text("baseJson")?.let(::workspaceRecord) ?: WorkspaceRecord(v.kind,v.id)
        val local=v.copy(baseRevision=base.revision)
        write(local,base,base.revision==0L || !local.same(base),false)
    }

    suspend fun initialize()=withContext(Dispatchers.IO) { lock.withLock { refresh() } }
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
        transaction { db.execSQL("DELETE FROM workspace_sync_state WHERE `key`='connection'") }
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
                val row=db.workspaceOne("SELECT * FROM `$table` WHERE rowid=?",rowId)
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
                db.execSQL("INSERT OR REPLACE INTO workspace_sync_locators VALUES(?,?,?,?,?)",arrayOf(table,rowId,version,row?.toString().orEmpty(),refs))
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

    private fun envelope(c: WorkspaceConnection): WorkspaceExchange {
        val order="CASE kind WHEN 'providers' THEN 0 WHEN 'accounts' THEN 1 WHEN 'endpoint_profiles' THEN 2 WHEN 'endpoint_bindings' THEN 3 WHEN 'credentials' THEN 4 WHEN 'models' THEN 5 WHEN 'my_models' THEN 6 ELSE 20 END"
        val rows=db.workspaceRows("SELECT r.kind,r.id FROM workspace_sync_records r WHERE r.dirty=1 AND NOT EXISTS(SELECT 1 FROM workspace_sync_conflicts c WHERE c.kind=r.kind AND c.recordId=r.id AND c.state='OPEN') ORDER BY $order,r.id LIMIT 100")
        val changes=mutableListOf<WorkspaceRecord>(); val secrets=linkedMapOf<String,WorkspaceSecret>()
        var size=0
        for(row in rows) {
            val snapshot=current(row.text("kind"),row.text("id")) ?: continue
            var v=snapshot
            var secret: WorkspaceSecret?=null
            if(v.kind=="credentials" && !v.deleted && c.shareSecrets) {
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
            val bytes=v.encode().toByteArray().size+(secret?.key?.toByteArray()?.size?:0)+1024
            check(bytes<=32*1024*1024)
            if(size+bytes>32*1024*1024) break
            size+=bytes; changes+=v
            if(secret!=null) secrets[v.key]=secret
        }
        val fetch=db.workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`='fetch'")?.text("valueJson")
            ?.let { listOf(workspaceRecord(it)) }.orEmpty()
        return WorkspaceExchange(cursor=c.cursor,changes=changes,secrets=secrets,fetch=fetch)
    }
    suspend fun syncNow(): Boolean=withContext(Dispatchers.IO) { lock.withLock {
        val first=connection() ?: return@withLock false
        mutable.value=mutable.value.copy(busy=true,problem=false)
        try {
            installWorkspaceJournal(db)
            val token=vault.bearerToken(first.tokenRef,first.url)
            repeat(20) {
                capture()
                val c=connection() ?: error("Disconnected")
                val request=envelope(c)
                val reply=transport.exchange(c.url,token,request)
                check(reply.workspaceId==c.workspace)
                consume(c,request,reply)
                if(!reply.hasMore && request.changes.isEmpty() && request.fetch.isEmpty()) {
                    refresh(); return@withLock true
                }
            }
            refresh(); true
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { refresh(problem=true); false }
        finally { if(mutable.value.busy) refresh(problem=true) }
    } }

    private fun conflict(local: WorkspaceRecord,remote: WorkspaceRecord) {
        if(db.workspaceOne("SELECT id FROM workspace_sync_conflicts WHERE kind=? AND recordId=? AND localJson=? AND remoteJson=? AND state='OPEN'",local.kind,local.id,local.encode(),remote.encode())!=null) return
        db.execSQL("INSERT INTO workspace_sync_conflicts VALUES(?,?,?,?,?,?,?)",arrayOf(UUID.randomUUID().toString(),local.kind,local.id,local.encode(),remote.encode(),"OPEN",System.currentTimeMillis()))
    }
    private fun consume(c: WorkspaceConnection,request: WorkspaceExchange,reply: WorkspaceReply) {
        val incoming=reply.accepted+reply.records
        val sealed=mutableMapOf<String,WorkspaceVaultCiphertext>()
        val usable=mutableSetOf<String>()
        for(v in incoming.filter { it.kind=="credentials" && !it.deleted }) {
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
            captureNative()
            setImporting(true)
            for(remote in reply.conflicts) current(remote.kind,remote.id)?.let { conflict(it,remote) }
            val sent=request.changes.associateBy { it.key }
            val accepted=reply.accepted.associateBy { it.key }
            for(remote in incoming.distinctBy { it.key+"/"+it.revision }.sortedBy { it.seq }) {
                val row=stored(remote.kind,remote.id)
                val old=row?.text("baseJson")?.takeIf { it!="{}" }?.let(::workspaceRecord)
                val fetched=request.fetch.any { it.key==remote.key }
                if(old!=null && old.revision>=remote.revision && !fetched) continue
                val local=row?.text("localJson")?.let(::workspaceRecord)
                val own=accepted[remote.key]?.revision==remote.revision && sent[remote.key]?.let { local?.same(it) }==true
                if(!own && row?.number("dirty")==1L && local!=null && !local.same(remote)) {
                    conflict(local,remote); continue
                }
                // If another local edit followed the request, acknowledge only
                // the baseline. It remains queued for the next compare-and-set.
                if(accepted[remote.key]?.revision==remote.revision && !own && local!=null) {
                    write(local.copy(baseRevision=remote.revision),remote,true,false); continue
                }
                write(remote.copy(baseRevision=remote.revision),remote,false,true)
                sealed[remote.key]?.let { vault.putSyncedCiphertext(db,it) }
                if(request.fetch.any { it.key==remote.key }) db.execSQL("DELETE FROM workspace_sync_state WHERE `key`='fetch'")
            }
            projectPending(usable)
            save(c.copy(cursor=reply.cursor,lastSuccess=System.currentTimeMillis()))
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
                check(workspaceDigest(local.encode())==localHash)
                val remote=workspaceRecord(db.workspaceText("workspace_sync_conflicts","remoteJson","id=?",id)!!)
                if(keepLocal) write(local.copy(baseRevision=remote.revision),remote,true,false)
                else {
                    write(remote.copy(baseRevision=remote.revision),remote,false,false)
                    db.execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES('fetch',?)",arrayOf(remote.encode()))
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
            val local=current(row.text("kind"),row.text("recordId")) ?: workspaceRecord(db.workspaceText("workspace_sync_conflicts","localJson","id=?",row.text("id"))!!)
            val remote=workspaceRecord(db.workspaceText("workspace_sync_conflicts","remoteJson","id=?",row.text("id"))!!)
            DeviceWorkspaceConflict(row.text("id"),row.text("kind"),local.data.toString().take(5000),remote.data.toString().take(5000),workspaceDigest(local.encode()))
        }
        mutable.value=DeviceWorkspaceStatus(c!=null,c?.url.orEmpty(),c?.shareSecrets?:false,
            pending=counts.number("pending").toInt(),stored=counts.number("stored").toInt(),pendingProjection=counts.number("projections").toInt(),
            lastSuccess=c?.lastSuccess?:0,problem=problem || db.workspaceOne("SELECT `key` FROM workspace_sync_state WHERE `key`='projection-failure'")!=null,conflicts=conflicts)
        } catch(_: Exception) { mutable.value=mutable.value.copy(busy=false,problem=true) }
    }
    private fun originOf(raw: String): String {
        val url=raw.toHttpUrl()
        require(url.scheme=="https" && url.username.isEmpty() && url.password.isEmpty())
        return dev.modelatlas.core.network.EndpointPolicy.origin(url)
    }
}
