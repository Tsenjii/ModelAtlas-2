package dev.modelatlas.core.network

import dev.modelatlas.core.inference.CredentialAccess
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * Probe step outcomes. Only observable, persistable facts; no body, header or exception text.
 * [CANCELLED] is recorded by the caller: cancelling the coroutine throws, so no result is returned.
 */
enum class ProbeStatus {
    OK,
    HTTP_UNAUTHORIZED,
    HTTP_FORBIDDEN,
    HTTP_TOO_MANY_REQUESTS,
    HTTP_NOT_FOUND,
    HTTP_UNSUPPORTED,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    TLS_FAILED,
    CANCELLED,
    INPUT_INVALID,
    RESPONSE_TOO_LARGE,
    CATALOG_UNSUPPORTED,
    CATALOG_MALFORMED,
    CREDENTIAL_UNAVAILABLE,
    UNKNOWN,
}

/**
 * Server type detection. `UNKNOWN` is the only default; every non-UNKNOWN value needs an explicit
 * wire signal (a parsed OpenAI catalog shape). Host, path or model name text is not evidence.
 */
enum class ProviderTypeFingerprint {
    UNKNOWN,
    OPENAI_COMPATIBLE,
}

enum class CatalogStatus {
    OK,
    HTTP_UNAUTHORIZED,
    HTTP_FORBIDDEN,
    HTTP_TOO_MANY_REQUESTS,
    HTTP_NOT_FOUND,
    HTTP_UNSUPPORTED,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    TLS_FAILED,
    CANCELLED,
    INPUT_INVALID,
    RESPONSE_TOO_LARGE,
    CATALOG_UNSUPPORTED,
    CATALOG_MALFORMED,
    CREDENTIAL_UNAVAILABLE,
    UNKNOWN,
}

/**
 * Reachability / TLS / type summary for the `inspect` probe step. It carries no credential and
 * proves nothing about auth: [ProbeStatus.HTTP_UNAUTHORIZED] means "reachable, unauthenticated".
 */
data class ProbeResult(
    val status: ProbeStatus,
    val httpStatus: Int? = null,
    val providerType: ProviderTypeFingerprint = ProviderTypeFingerprint.UNKNOWN,
    val typeEvidence: String? = null,
    val authVerified: Boolean = false,
)

/**
 * Catalog listing. [totalCount] is null when the count is unknown; null never means 0.
 * [freeModelsVerified] stays false for every successful listing: listing proves nothing about price.
 */
data class CatalogResult(
    val status: CatalogStatus,
    val httpStatus: Int? = null,
    val modelIds: List<String> = emptyList(),
    val totalCount: Int? = null,
    val truncated: Boolean = false,
    val providerType: ProviderTypeFingerprint = ProviderTypeFingerprint.UNKNOWN,
    val typeEvidence: String? = null,
    val freeModelsVerified: Boolean = false,
)

/**
 * Read-only OpenAI-compatible catalog probe used by onboarding `inspect` and `listModels`.
 * Every call performs at most one request, never retries, and never follows redirects, so a
 * credential can not be forwarded to a redirect target.
 */
class CatalogProbe(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    // This escape hatch is only for loopback unit tests; application never enables it.
    private val allowLoopbackHttpForTests: Boolean = false,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun inspect(baseUrl: String): ProbeResult {
        val url = endpoint(baseUrl, PROBE_PATH) ?: return ProbeResult(ProbeStatus.INPUT_INVALID)
        return when (val response = exchange(Request.Builder().url(url).head().build())) {
            is Exchange.Failed -> ProbeResult(response.status, httpStatus = response.httpStatus)
            is Exchange.Body -> {
                val fingerprint = CatalogFingerprint.fromContentType(response.contentType)
                ProbeResult(
                    status = failureStatus(response.httpCode),
                    httpStatus = response.httpCode,
                    providerType = fingerprint.type,
                    typeEvidence = fingerprint.evidence,
                )
            }
        }
    }

    suspend fun listModels(baseUrl: String, safeCredentialId: String?): CatalogResult {
        val url = endpoint(baseUrl, MODELS_PATH) ?: return CatalogResult(CatalogStatus.INPUT_INVALID)
        val origin = EndpointPolicy.origin(url)
        val token = if (safeCredentialId == null) {
            null
        } else {
            resolveToken(safeCredentialId, origin) ?: return CatalogResult(CatalogStatus.CREDENTIAL_UNAVAILABLE)
        }
        val request = Request.Builder().url(url).get().apply {
            header("Accept", "application/json")
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        return when (val response = exchange(request, MAX_RESPONSE_BYTES)) {
            is Exchange.Failed -> CatalogResult(catalogStatus(response.status), httpStatus = response.httpStatus)
            is Exchange.Body -> parseCatalog(response, token)
        }
    }

    private fun resolveToken(safeCredentialId: String, origin: String): String? = try {
        val token = credentials.bearerToken(safeCredentialId, origin)
        if (token.isNotBlank() && '\r' !in token && '\n' !in token) token else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A vault refusal must not reach the caller as an exception carrying vault internals.
        null
    }

    private fun endpoint(baseUrl: String, suffix: String): HttpUrl? {
        val trimmed = baseUrl.trim()
        val safe = if (allowLoopbackHttpForTests) loopbackOnly(trimmed) else validated(trimmed)
        if (safe == null) return null
        val path = safe.encodedPath.trimEnd('/')
        val base = when {
            path.endsWith(CHAT_COMPLETIONS_SUFFIX) -> path.removeSuffix(CHAT_COMPLETIONS_SUFFIX)
            else -> path
        }
        val resolved = (base.trimEnd('/') + suffix).ifEmpty { "/" }
        return safe.newBuilder().encodedPath(resolved).build()
    }

    private fun validated(url: String): HttpUrl? = runCatching { EndpointPolicy.validate(url) }.getOrNull()

    private fun loopbackOnly(url: String): HttpUrl? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host !in LOOPBACK_HOSTS) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.query != null || parsed.fragment != null) return null
        return parsed
    }

    /**
     * One request, one attempt. Runs off the caller thread and cancels the socket when the
     * coroutine is cancelled, so a cancelled probe never keeps the connection open.
     */
    private suspend fun exchange(request: Request, limitBytes: Long = INSPECT_BODY_BYTES): Exchange =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                // Exactly-once delivery with stable API only: resume() on a cancelled
                // continuation safely drops the value, and the flag stops double delivery.
                val delivered = AtomicBoolean(false)
                fun deliver(value: Exchange) {
                    if (delivered.compareAndSet(false, true)) continuation.resume(value, null)
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        deliver(Exchange.Failed(transportStatus(e)))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        val outcome = response.use { read(it, limitBytes) }
                        deliver(outcome)
                    }
                })
            }
        }

    /**
     * Reads at most [limitBytes] plus one byte so an oversized or endless body is classified
     * instead of buffered. Error bodies are never read: their content is not evidence we keep.
     */
    private fun read(response: Response, limitBytes: Long): Exchange {
        if (!response.isSuccessful) return Exchange.Failed(failureStatus(response.code), response.code)
        val contentType = response.header("Content-Type")
        val source = response.body?.source() ?: return Exchange.Body(response.code, contentType, "")
        val buffer = Buffer()
        // A single read may return one segment only; loop until exhausted or over the cap.
        // Never buffers more than limitBytes + 1, so oversized bodies cannot OOM us.
        while (buffer.size <= limitBytes) {
            if (source.read(buffer, limitBytes + 1 - buffer.size) < 0L) break
        }
        if (buffer.size > limitBytes) return Exchange.Failed(ProbeStatus.RESPONSE_TOO_LARGE, response.code)
        return Exchange.Body(response.code, contentType, buffer.readUtf8())
    }

    private fun transportStatus(e: IOException): ProbeStatus = when (e) {
        is SSLException -> ProbeStatus.TLS_FAILED
        is UnknownHostException -> ProbeStatus.NETWORK_UNREACHABLE
        is SocketTimeoutException -> ProbeStatus.TIMEOUT
        is InterruptedIOException -> ProbeStatus.TIMEOUT
        else -> ProbeStatus.NETWORK_UNREACHABLE
    }

    private fun failureStatus(code: Int): ProbeStatus = when (code) {
        in 200..299 -> ProbeStatus.OK
        401 -> ProbeStatus.HTTP_UNAUTHORIZED
        403 -> ProbeStatus.HTTP_FORBIDDEN
        404 -> ProbeStatus.HTTP_NOT_FOUND
        429 -> ProbeStatus.HTTP_TOO_MANY_REQUESTS
        in 400..499 -> ProbeStatus.HTTP_CLIENT_ERROR
        in 500..599 -> ProbeStatus.HTTP_SERVER_ERROR
        else -> ProbeStatus.HTTP_UNSUPPORTED
    }

    /**
     * Only the OpenAI catalog shape is accepted: a `data` array of objects with string `id`.
     * Any other successful payload means "enumeration unsupported", never a guessed model list.
     */
    private fun parseCatalog(response: Exchange.Body, token: String?): CatalogResult {
        val rejection = CatalogResult(status = CatalogStatus.CATALOG_MALFORMED, httpStatus = response.httpCode)
        val data = runCatching { json.parseToJsonElement(response.content) }.getOrNull() as? JsonObject
            ?: return rejection
        val entries = data["data"] as? JsonArray
            ?: return rejection.copy(status = CatalogStatus.CATALOG_UNSUPPORTED)
        val fingerprint = CatalogFingerprint.OPENAI_CATALOG
        val redacted = CredentialEcho.of(token)
        val modelIds = LinkedHashSet<String>()
        var unusable = 0
        for (entry in entries) {
            if (modelIds.size >= MAX_MODELS) break
            val id = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id == null || id.isBlank() || id.length > MAX_MODEL_ID_CHARS || redacted(id)) {
                unusable++
                continue
            }
            modelIds += id
        }
        return CatalogResult(
            status = CatalogStatus.OK,
            httpStatus = response.httpCode,
            modelIds = modelIds.toList(),
            // Unknown stays unknown: a payload we could not fully read is not "count 0".
            totalCount = totalCount(entries.size, unusable),
            truncated = modelIds.size >= MAX_MODELS || unusable > 0,
            providerType = fingerprint.type,
            typeEvidence = fingerprint.evidence,
            // A listing never proves a price; only verified pricing sources can claim free.
            freeModelsVerified = false,
        )
    }

    private fun totalCount(reported: Int, unusable: Int): Int? = if (unusable == 0) reported else null

    private fun catalogStatus(status: ProbeStatus): CatalogStatus = when (status) {
        ProbeStatus.OK -> CatalogStatus.OK
        ProbeStatus.HTTP_UNAUTHORIZED -> CatalogStatus.HTTP_UNAUTHORIZED
        ProbeStatus.HTTP_FORBIDDEN -> CatalogStatus.HTTP_FORBIDDEN
        ProbeStatus.HTTP_TOO_MANY_REQUESTS -> CatalogStatus.HTTP_TOO_MANY_REQUESTS
        ProbeStatus.HTTP_NOT_FOUND -> CatalogStatus.HTTP_NOT_FOUND
        ProbeStatus.HTTP_UNSUPPORTED -> CatalogStatus.HTTP_UNSUPPORTED
        ProbeStatus.HTTP_CLIENT_ERROR -> CatalogStatus.HTTP_CLIENT_ERROR
        ProbeStatus.HTTP_SERVER_ERROR -> CatalogStatus.HTTP_SERVER_ERROR
        ProbeStatus.NETWORK_UNREACHABLE -> CatalogStatus.NETWORK_UNREACHABLE
        ProbeStatus.TIMEOUT -> CatalogStatus.TIMEOUT
        ProbeStatus.TLS_FAILED -> CatalogStatus.TLS_FAILED
        ProbeStatus.CANCELLED -> CatalogStatus.CANCELLED
        ProbeStatus.INPUT_INVALID -> CatalogStatus.INPUT_INVALID
        ProbeStatus.RESPONSE_TOO_LARGE -> CatalogStatus.RESPONSE_TOO_LARGE
        ProbeStatus.CATALOG_UNSUPPORTED -> CatalogStatus.CATALOG_UNSUPPORTED
        ProbeStatus.CATALOG_MALFORMED -> CatalogStatus.CATALOG_MALFORMED
        ProbeStatus.CREDENTIAL_UNAVAILABLE -> CatalogStatus.CREDENTIAL_UNAVAILABLE
        ProbeStatus.UNKNOWN -> CatalogStatus.UNKNOWN
    }

    private sealed interface Exchange {
        data class Failed(val status: ProbeStatus, val httpStatus: Int? = null) : Exchange
        data class Body(val httpCode: Int, val contentType: String?, val content: String) : Exchange
    }

    /**
     * Type detection needs an explicit wire signal. Host, path, model names and body keywords
     * are never used: those are guesses. A reachable HTTPS origin only proves reachability, so
     * `inspect` reports UNKNOWN unless the server itself states a content type.
     */
    private object CatalogFingerprint {
        fun fromContentType(contentType: String?): Fingerprint {
            // Server text stays out of evidence unless it is a short, plain media type token.
            val type = contentType?.substringBefore(';')?.trim()?.lowercase()
                ?.takeIf { value -> value.length <= MAX_EVIDENCE_CHARS && value.all(::isEvidenceChar) }
            return Fingerprint(ProviderTypeFingerprint.UNKNOWN, type?.let { "content-type:$it" })
        }

        private fun isEvidenceChar(char: Char): Boolean =
            char in 'a'..'z' || char in '0'..'9' || char in "-+./*"

        /** A parsed `data` array of string ids is the OpenAI catalog shape: a protocol fact. */
        val OPENAI_CATALOG = Fingerprint(ProviderTypeFingerprint.OPENAI_COMPATIBLE, "catalog-shape:openai-v1")
    }

    private data class Fingerprint(val type: ProviderTypeFingerprint, val evidence: String?)

    /**
     * A server must never make a credential reachable through a model id. Besides the raw
     * value, the common encodings are treated as the same secret: percent-encoded, URL-safe
     * base64, standard base64, and the reversed token a sloppy gateway can echo back.
     */
    private object CredentialEcho {
        private fun variants(secret: String): Set<String> = buildSet {
            add(secret)
            add(secret.reversed())
            // Charset overload needs API 33+; minSdk is 26, so use the String overload.
            add(URLEncoder.encode(secret, Charsets.UTF_8.name()))
            listOf(secret, secret.reversed()).forEach { value ->
                add(base64(value, Base64.getUrlEncoder().withoutPadding()))
                add(base64(value, Base64.getEncoder()))
            }
        }

        private fun base64(value: String, encoder: Base64.Encoder): String =
            encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

        fun of(token: String?): (String) -> Boolean {
            if (token.isNullOrBlank()) return { false }
            val secrets = variants(token).filter { it.length >= MIN_SECRET_CHARS }
            if (secrets.isEmpty()) return { false }
            return { candidate -> secrets.any { it in candidate } }
        }
    }

    private companion object {
        /** `inspect` is a HEAD reachability probe, so it appends no path at all. */
        const val PROBE_PATH = ""
        const val MODELS_PATH = "/models"
        const val CHAT_COMPLETIONS_SUFFIX = "/chat/completions"
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L
        /** HEAD inspect reads no body; only a stray error page could arrive here. */
        const val INSPECT_BODY_BYTES = 8_192L
        /** Hard ceiling so a hostile or endless catalog can not exhaust memory. */
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val MAX_MODELS = 1_000
        const val MAX_MODEL_ID_CHARS = 256
        const val MIN_SECRET_CHARS = 8
        const val MAX_EVIDENCE_CHARS = 64
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
