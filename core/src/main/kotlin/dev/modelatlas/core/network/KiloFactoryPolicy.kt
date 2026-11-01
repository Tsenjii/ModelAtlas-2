package dev.modelatlas.core.network

import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.Outcome
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Applies only to the shipped anonymous profile; personal accounts are separate. */
object KiloFactoryPolicy {
    const val PROVIDER = "builtin-kilo-anonymous"
    const val BASE_URL = "https://ai-gateway.kilo.ai/api/v1"
    const val DEFAULT_MODEL = "nvidia/nemotron-3.5-lightning:free"
    const val NOTICE = "Kilo免Key來源可能保存對話並用於改善模型；請勿輸入個人或機密資料。預載資料不是實測，送出前會核對公開目錄。"

    fun permits(root: JsonObject, modelId: String, nowMillis: Long): Boolean {
        val rows = root["data"] as? JsonArray ?: return false
        if (rows.size > 4096) return false
        val matches = rows.mapNotNull { it as? JsonObject }.filter { (it["id"] as? JsonPrimitive)?.content == modelId }
        if (matches.size != 1) return false
        val model = matches.single()
        val free = model["isFree"] as? JsonPrimitive ?: return false
        if (free.isString || free.booleanOrNull != true) return false
        val price = model["pricing"] as? JsonObject ?: return false
        if (price.size > 16) return false
        for ((name, field) in price) {
            if (name == "discount") continue
            val text = (field as? JsonPrimitive)?.content ?: return false
            if (text.length > 80 || text.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) != 0) return false
        }
        for (name in listOf("prompt", "completion")) {
            val value = (price[name] as? JsonPrimitive)?.content?.toBigDecimalOrNull() ?: return false
            if (value.compareTo(BigDecimal.ZERO) != 0) return false
        }
        val expiry = (model["expiration_date"] as? JsonPrimitive)?.contentOrNull
        if (!expiry.isNullOrEmpty()) {
            val deadline = runCatching { LocalDate.parse(expiry).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: return false
            if (nowMillis >= deadline) return false
        }
        return true
    }

    internal fun requireScope(target: InferenceTarget, url: HttpUrl? = null, testLoopback: Boolean = false) {
        if (target.providerId != PROVIDER) return
        if (target.accountId != PROVIDER || target.credentialId != null || target.authFields.isNotEmpty() ||
            !target.group.isNullOrEmpty() || target.operation != "CHAT_COMPLETIONS" ||
            (!testLoopback && (url?.toString() ?: target.endpointUrl) != "$BASE_URL/chat/completions")) {
            throw InferenceFailure(Outcome.CREDENTIAL_SCOPE)
        }
    }

    internal suspend fun verify(target: InferenceTarget, url: HttpUrl, client: OkHttpClient, testLoopback: Boolean) {
        if (target.providerId != PROVIDER) return
        requireScope(target, url, testLoopback)
        val catalog = url.newBuilder().encodedPath("/api/v1/models").build()
        val bounded = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        val call = bounded.newCall(Request.Builder().url(catalog).header("Accept", "application/json").get().build())
        coroutineScope {
            val cancel = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    if (response.code != 200) throw InferenceFailure(Outcome.MODEL_UNAVAILABLE)
                    val source = response.body?.source() ?: throw InferenceFailure(Outcome.PARSER)
                    source.request(4L * 1024 * 1024 + 1)
                    if (source.buffer.size > 4L * 1024 * 1024) throw InferenceFailure(Outcome.PARSER)
                    val root = runCatching { Json.parseToJsonElement(source.readUtf8()) as? JsonObject }.getOrNull()
                    if (root == null || !permits(root, target.modelId, System.currentTimeMillis())) throw InferenceFailure(Outcome.MODEL_UNAVAILABLE)
                }
            } finally { cancel.cancelAndJoin() }
        }
    }
}
