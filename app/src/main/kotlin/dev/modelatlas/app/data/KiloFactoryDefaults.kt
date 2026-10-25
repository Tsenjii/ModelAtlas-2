package dev.modelatlas.app.data

import android.content.Context
import androidx.room.withTransaction
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.modelatlas.core.network.KiloFactoryPolicy
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.AdapterType
import kotlinx.serialization.json.*

/** Offline, atomic product defaults explicitly requested by the user. No vault,
 * socket, inference, health sample or verified/free state is synthesized. */
class KiloFactoryDefaults(private val context: Context, private val room: AtlasDatabase, private val freshMarkerRequired: Boolean = false) {
    companion object {
        /** Stored by Room only when creating the database, so an existing empty
         * workspace is not mistaken for a fresh install. PENDING survives a
         * process death before the installer commits its metadata. */
        val creationCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS factory_defaults(id TEXT PRIMARY KEY,state TEXT NOT NULL,applied_at INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO factory_defaults VALUES('kilo-v1','PENDING',0)")
            }
        }
    }
    suspend fun info(): KiloFactoryState = room.withTransaction {
        val db = room.openHelper.writableDatabase
        db.execSQL("CREATE TABLE IF NOT EXISTS factory_defaults(id TEXT PRIMARY KEY,state TEXT NOT NULL,applied_at INTEGER NOT NULL)")
        val installed = db.query("SELECT state FROM factory_defaults WHERE id='kilo-v1'").use { it.moveToFirst() && it.getString(0) == "APPLIED" }
        val manifest = Json.parseToJsonElement(context.assets.open("kilo-anonymous.json").bufferedReader().use { it.readText() }).jsonObject
        KiloFactoryState(loading = false, installed = installed, models = manifest["models"]!!.jsonArray.size)
    }
    suspend fun install(automatic: Boolean): Boolean {
        val manifest = Json.parseToJsonElement(context.assets.open("kilo-anonymous.json").bufferedReader().use { it.readText() }).jsonObject
        val id = KiloFactoryPolicy.PROVIDER
        require(manifest["version"]?.jsonPrimitive?.int == 1 && manifest["provider_id"]?.jsonPrimitive?.content == id &&
            manifest["base_url"]?.jsonPrimitive?.content == KiloFactoryPolicy.BASE_URL)
        val models = manifest["models"]?.jsonArray ?: error("Bundled catalog missing")
        require(models.size in 1..64)
        val ids = models.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        require(ids.toSet().size == ids.size && ids.none { it.isBlank() || it.length > 240 })
        return room.withTransaction {
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS factory_defaults(id TEXT PRIMARY KEY,state TEXT NOT NULL,applied_at INTEGER NOT NULL)")
            val state = db.query("SELECT state FROM factory_defaults WHERE id='kilo-v1'").use { if (it.moveToFirst()) it.getString(0) else null }
            if (state == "APPLIED" || automatic && state == "AVAILABLE") return@withTransaction false
            if (automatic && (room.providerDao().all().isNotEmpty() || freshMarkerRequired && state == null)) {
                db.execSQL("INSERT OR IGNORE INTO factory_defaults VALUES('kilo-v1','AVAILABLE',0)")
                return@withTransaction false
            }
            check(room.providerDao().getById(id) == null) { "Existing Kilo profile kept" }
            val at = System.currentTimeMillis()
            val endpointId = "$id-chat"
            room.providerDao().insert(ProviderEntity(id, "Kilo · 免Key"))
            room.accountDao().insert(AccountEntity(id, id))
            room.endpointDao().insert(EndpointEntity(endpointId, id, KiloFactoryPolicy.BASE_URL, KiloFactoryPolicy.BASE_URL + "/chat/completions"))
            room.providerConfigDao().insert(ProviderManualConfig(
                providerId = id, workflowId = "factory-kilo-v1", authMode = AuthMode.NONE,
                authModeOverride = AuthMode.NONE, anonymousConfirmed = true,
                resolvedAdapter = AdapterType.OPENAI_COMPATIBLE,
                websiteUrl = "https://kilo.ai", notes = KiloFactoryPolicy.NOTICE + "\n" +
                    manifest["catalog_url"]!!.jsonPrimitive.content + " · " + manifest["observed_at"]!!.jsonPrimitive.content,
                updatedAtMillis = at,
            ).toEntity())
            for (item in models) {
                val model = item.jsonObject
                val price = model["pricing"]!!.jsonObject
                require(listOf("prompt", "completion").all { price[it]?.jsonPrimitive?.content?.toBigDecimalOrNull()?.signum() == 0 })
                val modelId = model["id"]!!.jsonPrimitive.content
                room.offeringDao().insert(OfferingEntity("$id/$modelId", id, endpointId, modelId, manual = false, source = "BUNDLED_KILO_CATALOG"))
                val canonical = "kilo-factory:$modelId"
                val classification = "$id/$modelId/catalog"
                room.canonicalModelDao().insert(CanonicalModelEntity(canonical, modelId, model["name"]!!.jsonPrimitive.content, at, at))
                room.providerModelAliasDao().insert(ProviderModelAliasEntity("$id/$modelId/alias", id, modelId, canonical, "CATALOG", "LOW", false, at))
                room.modelClassificationDao().upsert(ModelClassificationEntity(classification, canonical, "CATALOG", "LOW", "CHAT_REASONING", at))
                val parameters = model["supported_parameters"]!!.jsonArray.map { it.jsonPrimitive.content }
                val capabilities = mutableSetOf<String>()
                if ("tools" in parameters) capabilities += "TOOL_CALLING"
                if ("reasoning" in parameters) capabilities += "REASONING"
                if ("structured_outputs" in parameters) capabilities += "STRUCTURED_OUTPUT"
                val modalities = mutableListOf<String>()
                val architecture = model["architecture"]!!.jsonObject
                for (mode in architecture["input_modalities"]!!.jsonArray.map { it.jsonPrimitive.content }) {
                    if (mode in setOf("text", "image", "audio")) modalities += mode.uppercase() + "_INPUT"
                    if (mode == "image") capabilities += "VISION"
                }
                modalities += "TEXT_OUTPUT"
                room.classificationCapabilityDao().insertAll(capabilities.map { ClassificationCapabilityEntity(classification, it) })
                room.classificationModalityDao().insertAll(modalities.map { ClassificationModalityEntity(classification, it) })
                room.classificationEndpointTypeDao().insertAll(listOf(ClassificationEndpointTypeEntity(classification, "CHAT_COMPLETIONS")))
                // Membership is this explicit product default, not a background
                // catalog promotion. Hidden/removed members are never re-added.
                room.myModelsDao().put(MyModelEntity(id, modelId, at, "USER"))
            }
            db.execSQL("INSERT OR REPLACE INTO factory_defaults VALUES('kilo-v1','APPLIED',?)", arrayOf(at))
            true
        }
    }
}

data class KiloFactoryState(val loading: Boolean = true, val installed: Boolean = false, val models: Int? = null, val problem: Boolean = false)
