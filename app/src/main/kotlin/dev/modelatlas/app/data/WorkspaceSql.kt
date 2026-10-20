package dev.modelatlas.app.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.modelatlas.core.workspace.*
import kotlinx.serialization.json.*
import java.util.UUID

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun workspaceID(vararg keys: String): String = JsonArray(keys.map(::JsonPrimitive)).toString()
internal fun WorkspaceRecord.encode(): String = WorkspaceJson.encodeToString(WorkspaceRecord.serializer(), this)
internal fun workspaceRecord(raw: String): WorkspaceRecord = WorkspaceJson.decodeFromString(WorkspaceRecord.serializer(), raw)
internal fun WorkspaceRecord.same(other: WorkspaceRecord): Boolean = deleted == other.deleted && data == other.data
internal fun workspaceMap(vararg values: Pair<String, Any?>): JsonObject = JsonObject(values.associate { (k,v) ->
    k to when(v) { null -> JsonNull; is Boolean -> JsonPrimitive(if(v) 1 else 0)
        is Number -> JsonPrimitive(v); is JsonElement -> v; else -> JsonPrimitive(v.toString()) }
})

/** No floating point conversion for INTEGER columns, including counters > 2^53. */
internal fun Cursor.workspaceRow(): JsonObject = buildJsonObject {
    for (i in 0 until columnCount) put(getColumnName(i), when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(i))
        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(getString(i))
        else -> error("不支援的同步欄位，本機資料已保留")
    })
}
internal fun SupportSQLiteDatabase.workspaceRows(sql: String, vararg args: Any?): List<JsonObject> =
    query(sql, args).use { c -> buildList { while(c.moveToNext()) add(c.workspaceRow()) } }
internal fun SupportSQLiteDatabase.workspaceOne(sql: String, vararg args: Any?): JsonObject? =
    workspaceRows(sql, *args).firstOrNull()

/** Android CursorWindow has a bounded per-window size. Fetch retained large
 * messages in bounded UTF-8 text pieces instead of selecting a multi-MiB row. */
internal fun SupportSQLiteDatabase.workspaceText(table: String,column: String,where: String,vararg args: Any?): String? {
    require(table in setOf("workspace_sync_records","workspace_sync_conflicts"))
    require(column in setOf("localJson","baseJson","remoteJson"))
    val length=workspaceOne("SELECT length(`$column`) AS n FROM `$table` WHERE $where",*args)?.number("n") ?: return null
    require(length<=40L*1024*1024)
    return buildString {
        var start=1L
        while(start<=length) {
            append(workspaceOne("SELECT substr(`$column`,?,131072) AS part FROM `$table` WHERE $where",start,*args)!!.text("part"))
            start+=131072
        }
    }
}

internal fun SupportSQLiteDatabase.workspacePK(table: String): List<String> {
    require(table in workspaceNativeTables)
    return workspaceRows("PRAGMA table_info(`$table`)").filter { it.number("pk") > 0 }
        .sortedBy { it.number("pk") }.map { it.text("name") }
}

/** UPDATE + INSERT is compatible with API 26 SQLite and never performs REPLACE's
 * implicit DELETE of rows referenced by permanent history. */
internal fun SupportSQLiteDatabase.workspacePut(table: String, data: JsonObject) {
    require(table in workspaceNativeTables)
    val columns = workspaceRows("PRAGMA table_info(`$table`)").map { it.text("name") }.toSet()
    require(data.keys.all { it in columns }) { "同步欄位版本不符，原始資料已保留" }
    val pk = workspacePK(table)
    require(pk.all { data[it] is JsonPrimitive && data[it] != JsonNull })
    val values = data.values.map { value ->
        val p = value as? JsonPrimitive ?: error("同步欄位格式不符")
        when { p == JsonNull -> null
            p.isString -> p.content
            p.longOrNull != null -> p.long
            p.doubleOrNull != null -> p.double
            else -> error("同步欄位格式不符") }
    }
    val where = pk.joinToString(" AND ") { "`$it`=?" }
    // Framework SQLiteDatabase.update does not quote ContentValues keys. Native
    // schemas include reserved words such as `group`; quote every identifier.
    fun execute(sql: String, args: List<Any?>, update: Boolean): Int = compileStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> when(value) {
            null -> statement.bindNull(index+1)
            is Long -> statement.bindLong(index+1,value)
            is Double -> statement.bindDouble(index+1,value)
            else -> statement.bindString(index+1,value.toString())
        } }
        if(update) statement.executeUpdateDelete() else { statement.executeInsert(); 1 }
    }
    val assignments=data.keys.joinToString(",") { "`$it`=?" }
    val changed=execute("UPDATE `$table` SET $assignments WHERE $where", values+pk.map { data.text(it) },true)
    if(changed==0) execute("INSERT INTO `$table` ("+data.keys.joinToString(","){"`$it`"}+") VALUES ("+data.keys.joinToString(","){"?"}+")",values,false)
}

internal class WorkspaceProjection(private val db: SupportSQLiteDatabase) {
    fun nativeID(kind: String, wire: String): String {
        db.workspaceOne("SELECT nativeId FROM workspace_sync_links WHERE kind=? AND wireId=?",kind,wire)
            ?.text("nativeId")?.let { return it }
        return UUID.nameUUIDFromBytes(("ModelAtlas/workspace/$kind/$wire").toByteArray()).toString()
    }
    private fun link(kind: String, wire: String, native: String, table: String, keys: JsonObject) {
        db.execSQL("INSERT OR REPLACE INTO workspace_sync_links VALUES(?,?,?,?,?)",
            arrayOf(kind,wire,native,table,keys.toString()))
    }
    private fun wireID(kind: String, native: String, fallback: String): String =
        db.workspaceOne("SELECT wireId FROM workspace_sync_links WHERE kind=? AND nativeId=?",kind,native)
            ?.text("wireId") ?: fallback
    private fun record(kind: String, id: String, data: JsonObject): WorkspaceRecord {
        val old = db.workspaceOne("SELECT localJson FROM workspace_sync_records WHERE kind=? AND id=?",kind,id)
            ?.text("localJson")?.let(::workspaceRecord)
        val preserve=when(kind) {
            "providers"->setOf("description","created_at","base_url","auth_mode")
            "accounts"->setOf("label")
            "credentials"->setOf("created_at","role","_auth_mode","_base_url")
            "models"->setOf("display_name","price_state","quota_state","provenance","updated_at")
            "my_models"->setOf("created_at")
            else->emptySet()
        }
        return WorkspaceRecord(kind,id,data=JsonObject(old?.data.orEmpty() + data + old?.data.orEmpty().filterKeys { it in preserve }))
    }
    private fun importedID(v: WorkspaceRecord): String {
        db.workspaceOne("SELECT nativeId FROM workspace_sync_links WHERE kind=? AND wireId=?",v.kind,v.id)?.text("nativeId")?.let { return it }
        val native=(v.data["_native_android"] as? JsonObject)?.text("native_id").orEmpty()
        return if(native.matches(Regex("[A-Za-z0-9-]{1,128}"))) native else nativeID(v.kind,v.id)
    }
    private fun provider(p: String): WorkspaceRecord? {
        val row = db.workspaceOne("SELECT * FROM providers WHERE providerId=?",p) ?: return null
        val endpoint = db.workspaceOne("SELECT * FROM endpoints WHERE providerId=? ORDER BY endpointId LIMIT 1",p) ?: return null
        val mode = db.workspaceOne("SELECT authMode FROM provider_configurations WHERE providerId=?",p)?.text("authMode")
        val id = wireID("providers",p,workspaceID(p))
        val wireP = (WorkspaceJson.parseToJsonElement(id) as JsonArray)[0].jsonPrimitive.content
        return record("providers",id,workspaceMap("id" to wireP,"name" to row.text("name"),
            "base_url" to endpoint.text("baseUrl"),"auth_mode" to if(mode=="NONE") "NONE" else "BEARER",
            "description" to "","created_at" to 0))
    }
    private fun providerWire(p: String): String =
        (WorkspaceJson.parseToJsonElement(wireID("providers",p,workspaceID(p))) as JsonArray)[0].jsonPrimitive.content
    private fun accountWire(a: String,p: String): String = wireID("accounts",a,workspaceID(p,a))
    private fun credential(row: JsonObject): WorkspaceRecord? {
        val a = row.text("accountId")
        val pNative = db.workspaceOne("SELECT providerId FROM accounts WHERE accountId=?",a)?.text("providerId") ?: return null
        val p = providerWire(pNative)
        val aID = (WorkspaceJson.parseToJsonElement(accountWire(a,p)) as JsonArray)[1].jsonPrimitive.content
        val c = row.text("credentialId")
        val id = wireID("credentials",c,workspaceID(p,aID,c))
        val cID = (WorkspaceJson.parseToJsonElement(id) as JsonArray)[2].jsonPrimitive.content
        val endpoint = db.workspaceOne("SELECT e.baseUrl FROM endpoints e JOIN offerings o ON o.endpointId=e.endpointId JOIN credential_availability a ON a.offeringId=o.id WHERE a.credentialId=? ORDER BY e.endpointId LIMIT 1",c)
            ?: db.workspaceOne("SELECT baseUrl FROM endpoints WHERE providerId=? ORDER BY endpointId LIMIT 1",pNative) ?: return null
        // Native custom headers/PAT retain their typed recipe and roles in the
        // android namespace. Never silently downgrade those to a Bearer key.
        val mode = row.text("authMode")
        if (mode !in listOf("NONE","BEARER","UNKNOWN")) return null
        return record("credentials",id,workspaceMap("provider_id" to p,"account_id" to aID,"id" to cID,
            "label" to row.text("label"),"role" to "INFERENCE","enabled" to row.number("enabled"),
            "created_at" to 0,"_base_url" to endpoint.text("baseUrl"),"_auth_mode" to if(mode=="NONE") "NONE" else ""))
    }
    private fun membership(provider: String,model: String): List<WorkspaceRecord> {
        val member=db.workspaceOne("SELECT * FROM my_models WHERE providerId=? AND providerModelId=?",provider,model) ?: return emptyList()
        val rows=db.workspaceRows("SELECT a.*,o.endpointId,e.protocol FROM credential_availability a JOIN offerings o ON o.id=a.offeringId JOIN endpoints e ON e.endpointId=o.endpointId WHERE o.providerId=? AND o.modelId=?",provider,model)
        val p=providerWire(provider)
        return rows.mapNotNull { row ->
            // Go's existing model uses an explicit string group; unknown native
            // null groups remain in the lossless Android namespace, unborrowed.
            val group=(row["group"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val aWire=accountWire(row.text("accountId"),p)
            val a=WorkspaceJson.parseToJsonElement(aWire).jsonArray[1].jsonPrimitive.content
            val cWire=wireID("credentials",row.text("credentialId"),workspaceID(p,a,row.text("credentialId")))
            val c=WorkspaceJson.parseToJsonElement(cWire).jsonArray[2].jsonPrimitive.content
            val eWire=wireID("endpoint_profiles",row.text("endpointId"),workspaceID(p,row.text("endpointId")))
            val e=WorkspaceJson.parseToJsonElement(eWire).jsonArray[1].jsonPrimitive.content
            val wire=wireID("my_models",row.text("id"),workspaceID(p,a,c,model,group,e))
            val wireEndpoint=WorkspaceJson.parseToJsonElement(wire).jsonArray[5].jsonPrimitive.content
            record("my_models",wire,workspaceMap("provider_id" to p,"account_id" to a,
                "credential_id" to c,"model_id" to model,"group_id" to group,"endpoint" to wireEndpoint,"created_at" to member.number("addedAt")))
        }
    }
    fun export(table: String, row: JsonObject): List<WorkspaceRecord> {
        val id = JsonArray(db.workspacePK(table).map { row.getValue(it) }).toString()
        val result = mutableListOf(WorkspaceRecord("android:$table",id,data=row))
        when(table) {
            "providers" -> provider(row.text("providerId"))?.let(result::add)
            "accounts" -> {
                val p = providerWire(row.text("providerId")); val a = row.text("accountId")
                val wire = accountWire(a,p)
                val aID = (WorkspaceJson.parseToJsonElement(wire) as JsonArray)[1].jsonPrimitive.content
                result += record("accounts",wire,workspaceMap("provider_id" to p,"id" to aID,"label" to a))
            }
            "endpoints" -> {
                val pNative = row.text("providerId"); val p = providerWire(pNative)
                provider(pNative)?.let(result::add)
                val e = row.text("endpointId"); val idWire=wireID("endpoint_profiles",e,workspaceID(p,e))
                if(db.workspaceOne("SELECT wireId FROM workspace_sync_links WHERE kind='selection_endpoint' AND nativeId=?",e)==null) {
                    val eID=(WorkspaceJson.parseToJsonElement(idWire) as JsonArray)[1].jsonPrimitive.content
                    result += record("endpoint_profiles",idWire,workspaceMap("provider_id" to p,"id" to eID,
                        "operation" to row.text("protocol"),"url" to row.text("endpointUrl")))
                }
            }
            "credentials" -> credential(row)?.let(result::add)
            "offerings" -> {
                val p=providerWire(row.text("providerId")); val m=row.text("modelId")
                result += record("models",workspaceID(p,m),workspaceMap("provider_id" to p,"id" to m,
                    "display_name" to m,"price_state" to "UNKNOWN","quota_state" to "UNKNOWN",
                    "provenance" to "MANUAL","updated_at" to 0))
            }
            "my_models" -> result += membership(row.text("providerId"),row.text("providerModelId"))
            "credential_availability" -> {
                val offering=db.workspaceOne("SELECT providerId,modelId FROM offerings WHERE id=?",row.text("offeringId"))
                if(offering!=null) result += membership(offering.text("providerId"),offering.text("modelId"))
            }
        }
        return result.map { v ->
            val binding=when(v.kind) {
                "providers" -> Triple("providers",row.text("providerId"),"providerId")
                "accounts" -> Triple("accounts",row.text("accountId"),"accountId")
                "credentials" -> Triple("credentials",row.text("credentialId"),"credentialId")
                "endpoint_profiles" -> Triple("endpoints",row.text("endpointId"),"endpointId")
                else -> null
            }
            if(binding==null || binding.second.isEmpty()) v else {
                link(v.kind,v.id,binding.second,binding.first,workspaceMap(binding.third to binding.second))
                v.copy(data=JsonObject(v.data+("_native_android" to workspaceMap("native_id" to binding.second))))
            }
        }
    }
    /** Receives byte-preserved foreign records even when this build cannot yet
     * project their feature. The durable pending flag never discards a page. */
    fun apply(v: WorkspaceRecord, hasSecret: Boolean) : Boolean {
        if (v.kind.startsWith("android:")) {
            val table=v.kind.removePrefix("android:")
            require(table in workspaceNativeTables)
            val pk=db.workspacePK(table)
            val keys=WorkspaceJson.parseToJsonElement(v.id).jsonArray
            require(keys.size==pk.size)
            if(v.deleted) {
                require(table !in immutableNative)
                db.execSQL("DELETE FROM `$table` WHERE " + pk.joinToString(" AND "){"`$it`=?"},
                    keys.map { it.jsonPrimitive.content }.toTypedArray())
            } else {
                require(pk.indices.all { v.data[pk[it]]==keys[it] })
                if(table=="credentials" || table=="accounts") {
                    val owner=if(table=="credentials") "accountId" else "providerId"
                    val existing=db.workspaceOne("SELECT `$owner` FROM `$table` WHERE `${pk.first()}`=?",v.data.text(pk.first()))
                    check(existing==null || existing[owner]==v.data[owner]) { "既有帳號或憑證不可跨來源重新綁定" }
                }
                var data=if(table=="credentials" && !hasSecret && v.data.text("authMode")!="NONE")
                    JsonObject(v.data + ("enabled" to JsonPrimitive(0))) else v.data
                val canonical=when(table) { "providers"->"providers"; "accounts"->"accounts"; "credentials"->"credentials"; "endpoints"->"endpoint_profiles"; else->null }
                if(canonical!=null) {
                    val native=data.text(pk.first())
                    val bound=db.workspaceOne("SELECT nativeId FROM workspace_sync_links WHERE kind=? AND nativeId=?",canonical,native)
                    if(bound!=null) {
                        val current=db.workspaceOne("SELECT * FROM `$table` WHERE `${pk.first()}`=?",native)
                        val managed=when(table) { "providers"->listOf("name"); "credentials"->listOf("label","enabled","authMode"); "endpoints"->listOf("baseUrl","endpointUrl","protocol"); else->emptyList() }
                        if(current!=null) data=JsonObject(data+current.filterKeys{it in managed})
                    }
                }
                if(table=="provider_configurations") ensureSnapshotJsonHasNoSecretRefs(data.text("snapshotJson"))
                db.workspacePut(table,data)
            }
            return true
        }
        val d=v.data
        val pWire=d.text("provider_id").ifEmpty { if(v.kind=="providers") d.text("id") else "" }
        val p=if(v.kind=="providers") importedID(v) else nativeID("providers",workspaceID(pWire))
        if(v.deleted) {
            // Product FK restrictions apply. Missing projections remain queued;
            // they never delete samples, messages, evidence or last-good facts.
            val binding=db.workspaceOne("SELECT nativeTable,nativeKeys FROM workspace_sync_links WHERE kind=? AND wireId=?",v.kind,v.id) ?: return true
            val table=binding.text("nativeTable")
            if(table !in listOf("providers","accounts","credentials","endpoints","my_models","credential_availability")) return true
            val keys=WorkspaceJson.parseToJsonElement(binding.text("nativeKeys")).jsonObject
            db.execSQL("DELETE FROM `$table` WHERE "+keys.keys.joinToString(" AND "){"`$it`=?"},keys.values.map{it.jsonPrimitive.content}.toTypedArray())
            return true
        }
        when(v.kind) {
            "providers" -> {
                val old=db.workspaceOne("SELECT * FROM providers WHERE providerId=?",p)
                val initial=workspaceMap("providerId" to p,"name" to d.text("name"),
                    "officialHealth" to "UNKNOWN","observedHealth" to "UNKNOWN","checkinUrl" to null,
                    "requiresDailyCheckin" to 0,"officialHealthAt" to 0,"observedHealthAt" to 0)
                db.workspacePut("providers",JsonObject((old?:initial)+("name" to JsonPrimitive(d.text("name")))))
                link(v.kind,v.id,p,"providers",workspaceMap("providerId" to p))
            }
            "accounts" -> {
                val a=importedID(v)
                db.workspacePut("accounts",workspaceMap("accountId" to a,"providerId" to p))
                link(v.kind,v.id,a,"accounts",workspaceMap("accountId" to a))
            }
            "credentials" -> {
                val c=importedID(v); val a=nativeID("accounts",workspaceID(pWire,d.text("account_id")))
                val old=db.workspaceOne("SELECT * FROM credentials WHERE credentialId=?",c)
                val mode=if(d.text("_auth_mode")=="NONE") "NONE" else "BEARER"
                db.workspacePut("credentials",JsonObject(old.orEmpty()+workspaceMap("credentialId" to c,"accountId" to a,
                    "label" to d.text("label"),"enabled" to if(d.text("role")=="INFERENCE" && (hasSecret||mode=="NONE")) d.number("enabled") else 0,
                    "note" to old?.get("note"),"group" to old?.get("group"),"priority" to (old?.number("priority")?:0),
                    "authMode" to mode,"headerName" to old?.get("headerName"),"headerPrefix" to old?.get("headerPrefix"),
                    "isSecret" to if(mode=="NONE") 0 else 1,"lastSuccessAt" to old?.get("lastSuccessAt"),"lastFailureAt" to old?.get("lastFailureAt"))))
                link(v.kind,v.id,c,"credentials",workspaceMap("credentialId" to c))
            }
            "endpoint_profiles" -> {
                val e=importedID(v)
                val providerRecord=db.workspaceOne("SELECT localJson FROM workspace_sync_records WHERE kind='providers' AND id=?",workspaceID(pWire))?.text("localJson")?.let(::workspaceRecord) ?: return false
                db.workspacePut("endpoints",workspaceMap("endpointId" to e,"providerId" to p,"baseUrl" to providerRecord.data.text("base_url"),
                    "endpointUrl" to d.text("url"),"protocol" to d.text("operation")))
                link(v.kind,v.id,e,"endpoints",workspaceMap("endpointId" to e))
            }
            "models" -> return true // Catalog is kept in the shared record store; membership below makes a usable offering.
            "my_models" -> {
                val account=nativeID("accounts",workspaceID(pWire,d.text("account_id")))
                val credential=nativeID("credentials",workspaceID(pWire,d.text("account_id"),d.text("credential_id")))
                val model=d.text("model_id"); val endpoint=d.text("endpoint")
                var nativeEndpoint=nativeID("endpoint_profiles",workspaceID(pWire,endpoint))
                val credentialRecord=db.workspaceOne("SELECT localJson FROM workspace_sync_records WHERE kind='credentials' AND id=?",workspaceID(pWire,d.text("account_id"),d.text("credential_id")))?.text("localJson")?.let(::workspaceRecord) ?: return false
                val credentialBase=credentialRecord.data.text("_base_url").trimEnd('/')
                val currentEndpoint=db.workspaceOne("SELECT * FROM endpoints WHERE endpointId=?",nativeEndpoint)
                if(currentEndpoint!=null && currentEndpoint.text("baseUrl").trimEnd('/')!=credentialBase) {
                    nativeEndpoint=nativeID("selection_endpoint",v.id)
                }
                if(db.workspaceOne("SELECT endpointId FROM endpoints WHERE endpointId=?",nativeEndpoint)==null) {
                    val providerRecord=db.workspaceOne("SELECT localJson FROM workspace_sync_records WHERE kind='providers' AND id=?",workspaceID(pWire))?.text("localJson")?.let(::workspaceRecord) ?: return false
                    val base=credentialBase.ifEmpty { providerRecord.data.text("base_url").trimEnd('/') }
                    val suffix=mapOf("CHAT_COMPLETIONS" to "/chat/completions","RESPONSES" to "/responses","ANTHROPIC_MESSAGES" to "/messages","EMBEDDINGS" to "/embeddings","RERANK" to "/rerank","IMAGES_GENERATION" to "/images/generations","AUDIO" to "/audio/transcriptions")[endpoint] ?: return false
                    db.workspacePut("endpoints",workspaceMap("endpointId" to nativeEndpoint,"providerId" to p,"baseUrl" to base,"endpointUrl" to base+suffix,"protocol" to endpoint))
                    if(currentEndpoint==null) link("endpoint_profiles",workspaceID(pWire,endpoint),nativeEndpoint,"endpoints",workspaceMap("endpointId" to nativeEndpoint))
                    else link("selection_endpoint",v.id,nativeEndpoint,"endpoints",workspaceMap("endpointId" to nativeEndpoint))
                }
                val offering=nativeID("offering",workspaceID(pWire,model,endpoint))
                db.workspacePut("offerings",workspaceMap("id" to offering,"providerId" to p,"endpointId" to nativeEndpoint,
                    "modelId" to model,"manual" to 1,"source" to "SYNC","hidden" to 0,"verificationEvidenceId" to null))
                val availability=nativeID("availability",v.id)
                db.workspacePut("credential_availability",workspaceMap("id" to availability,"offeringId" to offering,"accountId" to account,
                    "credentialId" to credential,"priceState" to "UNKNOWN","quotaState" to "UNKNOWN","group" to d.text("group_id"),
                    "groupIdentity" to "v"+d.text("group_id")))
                // This record is explicit membership from the same user; catalog
                // discovery alone never creates a My Models row.
                val member=db.workspaceOne("SELECT * FROM my_models WHERE providerId=? AND providerModelId=?",p,model)
                if(member==null) db.workspacePut("my_models",workspaceMap("providerId" to p,"providerModelId" to model,"addedAt" to d.number("created_at"),"source" to "USER"))
                link(v.kind,v.id,availability,"credential_availability",workspaceMap("id" to availability))
            }
            // Catalog, research, chat and scoped facts remain complete in the
            // shared record store. Their native views must opt into typed mappings.
            else -> return false
        }
        return true
    }
    companion object {
        val immutableNative=setOf("samples","evidence","price_snapshots","quota_snapshots","snapshot_facts",
            "external_observations","provider_perf_facts","provider_account_usage_facts","provider_notices",
            "provider_events","price_events","quota_events")
    }
}
