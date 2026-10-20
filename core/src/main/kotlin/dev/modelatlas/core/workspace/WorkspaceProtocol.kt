package dev.modelatlas.core.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

val WorkspaceJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }

@Serializable
data class WorkspaceRecord(
    val kind: String,
    val id: String,
    val revision: Long = 0,
    @SerialName("base_revision") val baseRevision: Long = 0,
    val data: JsonObject = JsonObject(emptyMap()),
    val deleted: Boolean = false,
    @SerialName("origin_device") val originDevice: String = "",
    val seq: Long = 0,
) {
    val key: String get() = "$kind/$id"
}

@Serializable
data class WorkspaceAuthField(val name: String, val query: Boolean = false, val secret: String) {
    override fun toString(): String = "WorkspaceAuthField([redacted])"
}

@Serializable
data class WorkspaceSecret(val key: String, val fields: List<WorkspaceAuthField> = emptyList()) {
    override fun toString(): String = "WorkspaceSecret([redacted])"
    /** Same byte representation as Go encoding/json for its typed secret envelope. */
    fun digest(): String {
        val data = buildJsonObject {
            put("key", key)
            if (fields.isNotEmpty()) put("fields", JsonArray(fields.map { f ->
                buildJsonObject { put("name", f.name); put("query", f.query); put("secret", f.secret) }
            }))
        }
        val canonical = data.toString().replace("<", "\\u003c").replace(">", "\\u003e")
            .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return workspaceDigest(canonical)
    }
}

fun workspaceDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

@Serializable
data class WorkspaceExchange(
    val protocol: Int = 1,
    val cursor: Long = 0,
    val changes: List<WorkspaceRecord> = emptyList(),
    val secrets: Map<String, WorkspaceSecret> = emptyMap(),
    val fetch: List<WorkspaceRecord> = emptyList(),
)

@Serializable
data class WorkspaceReply(
    val protocol: Int,
    @SerialName("workspace_id") val workspaceId: String,
    val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    val records: List<WorkspaceRecord>,
    val accepted: List<WorkspaceRecord>,
    val conflicts: List<WorkspaceRecord>,
    val secrets: Map<String, WorkspaceSecret> = emptyMap(),
)

@Serializable
data class WorkspacePairRequest(val code: String, val label: String, val platform: String = "Android") {
    override fun toString(): String = "WorkspacePairRequest([redacted])"
}
@Serializable
data class WorkspacePairResult(
    val protocol: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val token: String,
    @SerialName("share_secrets") val shareSecrets: Boolean,
) {
    override fun toString(): String = "WorkspacePairResult([redacted])"
}

class WorkspaceTransport(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(35, TimeUnit.SECONDS).build()

    companion object {
        const val MAX_BYTES = 40 * 1024 * 1024
        fun origin(raw: String): String {
            val url = raw.toHttpUrl()
            require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
                url.encodedPath == "/" && url.query == null && url.fragment == null) {
                "同步中心必須是 HTTPS 網址，不能包含登入資訊或路徑"
            }
            return url.toString().trimEnd('/')
        }
    }
    fun pair(base: String, code: String, label: String): WorkspacePairResult {
        require(code.length == 43 && code.matches(Regex("[A-Za-z0-9_-]+")))
        require(label.isNotBlank() && label.length <= 120 && label.none { it.code < 32 })
        val raw = call(base, "pair", null,
            WorkspaceJson.encodeToString(WorkspacePairRequest.serializer(), WorkspacePairRequest(code, label)))
        return WorkspaceJson.decodeFromString(WorkspacePairResult.serializer(), raw).also {
            require(it.protocol == 1 && it.token.startsWith("matlas_device_") && it.token.length <= 128)
            require(it.deviceId.isNotBlank() && it.workspaceId.isNotBlank())
        }
    }
    fun exchange(base: String, token: String, envelope: WorkspaceExchange): WorkspaceReply {
        require(envelope.changes.size <= 100 && envelope.fetch.size <= 100 && envelope.cursor >= 0)
        val raw = call(base, "exchange", token,
            WorkspaceJson.encodeToString(WorkspaceExchange.serializer(), envelope))
        return WorkspaceJson.decodeFromString(WorkspaceReply.serializer(), raw).also {
            require(it.protocol == 1 && it.cursor >= envelope.cursor && it.records.size <= 200 &&
                it.accepted.size <= 100 && it.conflicts.size <= 100) { "同步回應版本或範圍不符" }
        }
    }
    private fun call(base: String, path: String, token: String?, body: String): String {
        require(body.toByteArray().size <= 33 * 1024 * 1024) { "同步頁面過大，本機資料已保留" }
        val builder = Request.Builder().url(origin(base) + "/api/workspace/" + path)
            .post(body.toRequestBody("application/json".toMediaType())).header("Accept", "application/json")
        if (token != null) {
            require(token.startsWith("matlas_device_") && token.length <= 128 && token.none { it.code < 32 })
            builder.header("Authorization", "Bearer $token")
        }
        // Never include response bodies, URLs with credentials or tokens in errors.
        client.newCall(builder.build()).execute().use { response ->
            check(response.code == 200) {
                when (response.code) {
                    401, 403 -> "裝置權限已撤銷，或配對碼已過期"
                    409 -> "同步資料有衝突，請先檢視"
                    else -> "同步中心暫時無法接受資料（HTTP ${response.code}），本機修改已保留"
                }
            }
            val source = response.body ?: error("同步回應不完整")
            require(source.contentLength() <= MAX_BYTES) { "同步回應過大" }
            val out = ByteArrayOutputStream()
            source.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= MAX_BYTES) { "同步回應過大" }
                    out.write(buffer, 0, n)
                }
            }
            return out.toString("UTF-8")
        }
    }
}
