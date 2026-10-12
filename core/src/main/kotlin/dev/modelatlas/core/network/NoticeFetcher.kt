package dev.modelatlas.core.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

enum class NoticeStatus {
    OK,
    NOT_MODIFIED,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    HTTP_UNSUPPORTED,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    TLS_FAILED,
    INPUT_INVALID,
    INSECURE_SCHEME,
    RESPONSE_TOO_LARGE,
    UNKNOWN,
}

/**
 * A fetched notice. [content] is null unless the server returned a readable 200 body or the
 * fetcher served a cached body on a 304; an error never carries body text. [etag] is the tag
 * the caller must keep for the next conditional request.
 */
data class NoticeResult(
    val status: NoticeStatus,
    val httpStatus: Int? = null,
    val content: String? = null,
    val etag: String? = null,
    val fromCache: Boolean = false,
)

/**
 * Pure HTTPS notice fetcher: pulls a notice document, enforces HTTPS and no redirects, bounds
 * the body, and revalidates with `If-None-Match` against an in-memory ETag cache. It never
 * writes to the database and never sends credentials; persistence is the caller's job.
 */
class NoticeFetcher(
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

    private val cache = ConcurrentHashMap<String, CachedNotice>()

    suspend fun fetch(url: String): NoticeResult {
        val parsed = validate(url) ?: return NoticeResult(NoticeStatus.INPUT_INVALID)
        val httpsOk = parsed.scheme == "https"
        val loopbackOk = allowLoopbackHttpForTests &&
            parsed.host in LOOPBACK_HOSTS &&
            parsed.username.isEmpty() &&
            parsed.password.isEmpty()
        if (!httpsOk && !loopbackOk) return NoticeResult(NoticeStatus.INSECURE_SCHEME)
        val cached = cache[url]
        val request = Request.Builder().url(parsed).get().apply {
            header("Accept", "application/json")
            if (cached != null) header("If-None-Match", cached.etag)
        }.build()
        return when (val response = exchange(request)) {
            is Exchange.Failed -> NoticeResult(response.status, httpStatus = response.httpStatus)
            is Exchange.Body -> handle(url, response, cached)
        }
    }

    private fun handle(url: String, response: Exchange.Body, cached: CachedNotice?): NoticeResult {
        if (response.httpCode == HTTP_NOT_MODIFIED) {
            if (cached == null) {
                return NoticeResult(NoticeStatus.UNKNOWN, httpStatus = response.httpCode)
            }
            return NoticeResult(
                status = NoticeStatus.NOT_MODIFIED,
                httpStatus = response.httpCode,
                content = cached.content,
                etag = cached.etag,
                fromCache = true,
            )
        }
        if (response.httpCode !in 200..299) {
            return NoticeResult(failureStatus(response.httpCode), httpStatus = response.httpCode)
        }
        if (response.overLimit) {
            return NoticeResult(NoticeStatus.RESPONSE_TOO_LARGE, httpStatus = response.httpCode)
        }
        val etag = response.etag
        if (etag != null && etag.isNotBlank()) {
            cache[url] = CachedNotice(etag, response.content)
        } else {
            cache.remove(url)
        }
        return NoticeResult(
            status = NoticeStatus.OK,
            httpStatus = response.httpCode,
            content = response.content,
            etag = etag?.takeIf { it.isNotBlank() },
            fromCache = false,
        )
    }

    /** HTTPS only; embedded credentials, query and fragment are rejected before any request. */
    private fun validate(url: String): HttpUrl? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.query != null || parsed.fragment != null) return null
        return parsed
    }

    private suspend fun exchange(request: Request): Exchange = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
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
                    val outcome = response.use { read(it) }
                    deliver(outcome)
                }
            })
        }
    }

    /**
     * Reads at most [MAX_BODY_BYTES] plus one byte so an oversized or endless body is classified
     * instead of buffered; 304 responses carry no body to read.
     */
    private fun read(response: Response): Exchange {
        val etag = response.header("ETag")
        if (response.code == HTTP_NOT_MODIFIED) {
            return Exchange.Body(response.code, etag = etag, content = "", overLimit = false)
        }
        val source = response.body?.source() ?: return Exchange.Body(response.code, etag = etag, content = "")
        val buffer = Buffer()
        while (buffer.size <= MAX_BODY_BYTES) {
            if (source.read(buffer, MAX_BODY_BYTES + 1 - buffer.size) < 0L) break
        }
        if (buffer.size > MAX_BODY_BYTES) {
            return Exchange.Body(response.code, etag = etag, content = "", overLimit = true)
        }
        return Exchange.Body(response.code, etag = etag, content = buffer.readUtf8())
    }

    private fun transportStatus(e: IOException): NoticeStatus = when (e) {
        is SSLException -> NoticeStatus.TLS_FAILED
        is UnknownHostException -> NoticeStatus.NETWORK_UNREACHABLE
        is SocketTimeoutException -> NoticeStatus.TIMEOUT
        is InterruptedIOException -> NoticeStatus.TIMEOUT
        else -> NoticeStatus.NETWORK_UNREACHABLE
    }

    private fun failureStatus(code: Int): NoticeStatus = when (code) {
        in 400..499 -> NoticeStatus.HTTP_CLIENT_ERROR
        in 500..599 -> NoticeStatus.HTTP_SERVER_ERROR
        else -> NoticeStatus.HTTP_UNSUPPORTED
    }

    private data class CachedNotice(val etag: String, val content: String)

    private sealed interface Exchange {
        data class Failed(val status: NoticeStatus, val httpStatus: Int? = null) : Exchange
        data class Body(
            val httpCode: Int,
            val etag: String? = null,
            val content: String,
            val overLimit: Boolean = false,
        ) : Exchange
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L
        const val MAX_BODY_BYTES = 262_144L
        const val HTTP_NOT_MODIFIED = 304
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
