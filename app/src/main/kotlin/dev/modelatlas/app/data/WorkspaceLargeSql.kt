package dev.modelatlas.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.modelatlas.core.workspace.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/** Byte slices never depend on CursorWindow fitting the whole retained record.
 * SQLite substr on BLOB preserves UTF-8 across arbitrary byte boundaries. */
internal fun SupportSQLiteDatabase.workspaceRecordInput(column: String,kind: String,id: String): InputStream? =
    workspaceJSONInput("workspace_sync_records",column,"kind=? AND id=?",kind,id)
private fun SupportSQLiteDatabase.workspaceJSONInput(table: String,column: String,where: String,vararg args: Any?): InputStream? {
    require(table in setOf("workspace_sync_records","workspace_sync_conflicts"))
    require(column in setOf("localJson","baseJson","remoteJson"))
    require(where in setOf("kind=? AND id=?","id=?"))
    val bytes=workspaceOne("SELECT length(CAST(`$column` AS BLOB)) AS n FROM `$table` WHERE $where",*args)?.number("n") ?: return null
    require(bytes in 0..WorkspaceChunkLimits.RECORD)
    return object: InputStream() {
        var offset=0L;var pending=ByteArray(0);var index=0
        override fun read(): Int { val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255 }
        override fun read(out: ByteArray,start: Int,length: Int): Int {
            if(length==0) return 0
            if(index==pending.size) {
                if(offset==bytes) return -1
                pending=query("SELECT substr(CAST(`$column` AS BLOB),?,131072) FROM `$table` WHERE $where",arrayOf(offset+1,*args)).use { cursor ->
                    check(cursor.moveToFirst());cursor.getBlob(0)
                }
                check(pending.isNotEmpty() && pending.size.toLong()==minOf(131072L,bytes-offset))
                index=0;offset+=pending.size
            }
            val n=minOf(length,pending.size-index);pending.copyInto(out,start,index,index+n);index+=n;return n
        }
    }
}
@OptIn(ExperimentalSerializationApi::class)
internal fun SupportSQLiteDatabase.workspaceReadRecord(column: String,kind: String,id: String): WorkspaceRecord? =
    workspaceRecordInput(column,kind,id)?.use { WorkspaceJson.decodeFromStream(WorkspaceRecord.serializer(),it) }

@OptIn(ExperimentalSerializationApi::class)
internal fun SupportSQLiteDatabase.workspaceReadConflict(column: String,id: String): WorkspaceRecord? =
    workspaceJSONInput("workspace_sync_conflicts",column,"id=?",id)?.use { WorkspaceJson.decodeFromStream(WorkspaceRecord.serializer(),it) }

internal fun SupportSQLiteDatabase.workspaceNativeRow(table: String,rowId: Long): JsonObject? {
    require(table in workspaceNativeTables)
    val columns=workspaceRows("PRAGMA table_info(`$table`)").map { it.text("name") }
    val sizes=workspaceOne("SELECT "+columns.mapIndexed { index,name->"length(CAST(`$name` AS BLOB)) AS n$index" }.joinToString(",")+" FROM `$table` WHERE rowid=?",rowId) ?: return null
    if(columns.indices.sumOf { sizes.number("n$it") }<=512*1024L) return workspaceOne("SELECT * FROM `$table` WHERE rowid=?",rowId)
    return buildJsonObject {
        for((index,name) in columns.withIndex()) {
            val bytes=sizes.number("n$index")
            require(bytes<=WorkspaceChunkLimits.RECORD) { "單筆資料超出同步上限，本機資料已保留" }
            if(bytes<=131072) put(name,workspaceOne("SELECT `$name` AS value FROM `$table` WHERE rowid=?",rowId)!!.getValue("value"))
            else {
                val chars=workspaceOne("SELECT length(`$name`) AS n,typeof(`$name`) AS t FROM `$table` WHERE rowid=?",rowId)!!
                check(chars.text("t")=="text")
                val length=chars.number("n")
                // SQLite length counts code points; a supplementary character uses
                // two UTF-16 units. Reserve a safe bound to avoid doubling a
                // near-limit StringBuilder when a final emoji is appended.
                val capacity=minOf(bytes,length*2).toInt()
                val text=buildString(capacity) {
                    var start=1L
                    while(start<=length) {
                        append(workspaceOne("SELECT substr(`$name`,?,131072) AS value FROM `$table` WHERE rowid=?",start,rowId)!!.text("value"));start+=131072
                    }
                }
                put(name,text)
            }
        }
    }
}

internal fun installWorkspaceHashJournal(db: SupportSQLiteDatabase) {
    for(operation in listOf("INSERT","UPDATE","DELETE")) {
        val row=if(operation=="DELETE")"OLD" else "NEW"
        db.execSQL("CREATE TRIGGER IF NOT EXISTS workspace_record_hash_${operation.lowercase()} AFTER $operation ON workspace_sync_records BEGIN DELETE FROM workspace_sync_state WHERE `key`='record-hash/'||$row.kind||'/'||$row.id; END")
    }
}
private fun workspaceHashKey(kind: String,id: String)="record-hash/$kind/$id"
internal fun SupportSQLiteDatabase.workspaceRecordHashes(kind: String,id: String): Pair<String,String>? =
    workspaceOne("SELECT valueJson FROM workspace_sync_state WHERE `key`=?",workspaceHashKey(kind,id))?.text("valueJson")?.let {
        val parts=it.split(':');if(parts.size==2 && parts.all { value->value.matches(Regex("[a-f0-9]{64}")) }) parts[0] to parts[1] else null
    }
internal fun SupportSQLiteDatabase.workspaceSaveRecordHashes(kind: String,id: String,local: String,base: String) {
    execSQL("INSERT OR REPLACE INTO workspace_sync_state VALUES(?,?)",arrayOf(workspaceHashKey(kind,id),"$local:$base"))
}
internal fun SupportSQLiteDatabase.workspaceLocalDigest(kind: String,id: String): String? {
    workspaceRecordHashes(kind,id)?.let { return it.first }
    val local=workspaceReadRecord("localJson",kind,id) ?: return null
    // Cache installation happens only under the manager's transaction/lock.
    return workspaceContentDigest(local)
}

/** Small records retain the ordinary bound-string path. For a large record,
 * bounded metadata fragments are concatenated inside SQLite at the product
 * write, avoiding a second full JSON String in the Android heap. The temporary
 * table contains record metadata only; transport envelopes/secrets use the
 * Keystore-encrypted vault and never enter this table. */
internal data class WorkspaceSQLJson(val expression: String,val args: List<Any?>,val cleanup: () -> Unit)
internal fun SupportSQLiteDatabase.workspaceSQLRecord(value: WorkspaceRecord): WorkspaceSQLJson {
    val batch=UUID.randomUUID().toString()
    val buffer=ByteArrayOutputStream(WorkspaceChunkLimits.CHUNK)
    var fragmented=false;var offset=0L
    fun flushPart() {
        if(buffer.size()==0) return
        if(!fragmented) {
            execSQL("CREATE TEMP TABLE IF NOT EXISTS workspace_json_write_parts(batch TEXT NOT NULL,offset INTEGER NOT NULL,fragment BLOB NOT NULL,PRIMARY KEY(batch,offset))")
            fragmented=true
        }
        val part=buffer.toByteArray()
        execSQL("INSERT INTO temp.workspace_json_write_parts VALUES(?,?,?)",arrayOf(batch,offset,part))
        offset+=part.size;buffer.reset()
    }
    val output=object: OutputStream() {
        override fun write(value: Int) { if(buffer.size()==WorkspaceChunkLimits.CHUNK) flushPart();buffer.write(value) }
        override fun write(value: ByteArray,start: Int,length: Int) {
            var cursor=start;var remaining=length
            while(remaining>0) {
                if(buffer.size()==WorkspaceChunkLimits.CHUNK) flushPart()
                val n=minOf(remaining,WorkspaceChunkLimits.CHUNK-buffer.size());buffer.write(value,cursor,n);cursor+=n;remaining-=n
            }
        }
    }
    try {
        workspaceEncodeToStream(WorkspaceRecord.serializer(),value,output,WorkspaceChunkLimits.RECORD)
        if(!fragmented) return WorkspaceSQLJson("?",listOf(buffer.toString("UTF-8"))) {}
        flushPart()
        return WorkspaceSQLJson("(SELECT CAST(group_concat(fragment,'') AS TEXT) FROM (SELECT fragment FROM temp.workspace_json_write_parts WHERE batch=? ORDER BY offset))",listOf(batch)) {
            execSQL("DELETE FROM temp.workspace_json_write_parts WHERE batch=?",arrayOf(batch))
        }
    } catch(e: Throwable) {
        if(fragmented) execSQL("DELETE FROM temp.workspace_json_write_parts WHERE batch=?",arrayOf(batch))
        throw e
    }
}
internal inline fun <T> SupportSQLiteDatabase.workspaceWithRecordJSON(value: WorkspaceRecord,action: (WorkspaceSQLJson)->T): T {
    val encoded=workspaceSQLRecord(value);try { return action(encoded) } finally { encoded.cleanup() }
}

internal fun workspacePreview(value: JsonObject): String = buildString {
    for((name,field) in value) {
        if(length>=5000) break
        append(name.take(128));append(": ")
        append(when(field) { is JsonPrimitive->field.content.take((5000-length).coerceAtLeast(0));is JsonObject->"[物件]";else->"[陣列]" })
        append('\n')
    }
}.take(5000)
