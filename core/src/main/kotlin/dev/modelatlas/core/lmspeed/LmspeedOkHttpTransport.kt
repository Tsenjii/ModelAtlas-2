package dev.modelatlas.core.lmspeed

import dev.modelatlas.core.network.EndpointPolicy
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * Production [LmspeedHttpTransport]（TRACEABILITY LMS-001／LMS-005；PRODUCT_SPEC §13；RESEARCH_POLICY §7）。
 *
 * 來源依據（只讀，不含 key／PAT）：
 * - `docs/LMSPEED_API_CONTRACT.md` §1（`x-api-key` 公開 key、1000/day、backlink、自行快取）
 *   ／§2（只取 `GET /api/v1/models`；`benchmarks`／`pricing`／`provenance` 在 T1–T3 schema
 *   補齊前不得寫入）／§2.1（`page_size` 1..100、`sort`／`locale`／`include`）／§2.3（未帶 key
 *   一律 401 `{"error":{"code","message","details"}}`）／§3（`data.models[]` 欄位、
 *   `stats`／`performance_summary` 速度子物件）／§4（detail `pricing_summary`／
 *   `benchmark_summary` 為 benchmark-derived，不當 official）／§6 T1–T13 Unknown 清單。
 *
 * 鐵律：
 * - 只打公開端點：單次 `GET /api/v1/models?page=1&page_size<=100&sort=popular&locale=en|zh|ru
 *   &include=capabilities`；不打 `benchmarks`／`pricing`／`provenance`，不用
 *   `lmspeed.com`／`.co`，不切換 PATH 繞配額，不直爬 `robots.txt Disallow: /api/`。
 * - 不做 load test：單 request、禁 redirect（跟隨即洩 key，故 `followRedirects=false`）、
 *   禁重試、單頁（不翻頁掃全量）、不 fan-out detail（全量 detail 掃描屬 P8 crawler，不在裝置同步做）。
 * - 有界讀取：body 上限 [MAX_RESPONSE_BYTES]＋1 探測，超限即 `RESPONSE_TOO_LARGE` 分類
 *   （由呼叫端轉 Failure，保舊）；models 上限 [MAX_MODELS]，欄位逐項截斷。
 * - raw key 只 transient：[apiKey] 只進 `x-api-key` header，不存欄位、不進 log／exception／
 *   toString；HTTP 非 2xx 即丟 [LmspeedFetchException]（只帶分類＋status）；憑證回聲列即剔除該筆。
 * - Unknown 不猜：缺 slug 即略過該筆；`stats`／`performance_summary` 單位未證實（T8），
 *   只搬運原始值不換算；`pricing_summary` 只存不透明 [pricingRef] 片段，不當 official 價格；
 *   `benchmark_summary` 只存不透明 [benchmarkRef]，不當 authenticity 證據；`capabilities`
 *   schema 未證實（T5）只搬運字串陣列。
 */
class LmspeedOkHttpTransport(
    client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = LMSPEED_PRODUCTION_BASE_URL,
    private val pageSize: Int = LMSPEED_DEFAULT_PAGE_SIZE,
    private val locale: String = "en",
    private val allowLoopbackHttpForTests: Boolean = false,
) : LmspeedHttpTransport {

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be 1..100" }
        require(locale in ALLOWED_LOCALES) { "locale must be en|zh|ru" }
    }

    override suspend fun fetch(apiKey: String): List<LmspeedRawModel> {
        if (apiKey.isBlank() || '\r' in apiKey || '\n' in apiKey) {
            throw LmspeedFetchException(LmspeedFetchFailure.CREDENTIAL_UNAVAILABLE)
        }
        val url = buildListUrl() ?: throw LmspeedFetchException(LmspeedFetchFailure.UNKNOWN)
        val request = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .header("x-api-key", apiKey)
            .build()
        return when (val response = exchange(request)) {
            is Exchange.Failed -> throw LmspeedFetchException(
                response.probeStatus ?: ProductLmspeedFetcher.classifyHttpStatus(response.httpStatus ?: -1).let {
                    // 300–399 在禁 redirect 下即為 UNKNOWN（不跟隨、不轉送憑證）。
                    if (response.httpStatus != null && response.httpStatus in 300..399) {
                        LmspeedFetchFailure.UNKNOWN
                    } else it
                },
                response.httpStatus,
            )
            is Exchange.Body -> parseModels(response.content, apiKey)
        }
    }

    private fun buildListUrl(): okhttp3.HttpUrl? {
        val trimmed = baseUrl.trim()
        val origin = if (allowLoopbackHttpForTests) {
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            if (parsed.host !in LOOPBACK_HOSTS) return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            parsed
        } else {
            val validated = runCatching { EndpointPolicy.validate(trimmed) }.getOrNull() ?: return null
            // 正主唯一：只允許 https://lmspeed.net（禁 lmspeed.com／.co）。
            if (validated.host != LMSPEED_HOST) return null
            validated
        }
        return origin.newBuilder()
            .encodedPath("/api/v1/models")
            .addQueryParameter("page", "1")
            .addQueryParameter("page_size", pageSize.toString())
            .addQueryParameter("sort", "popular")
            .addQueryParameter("locale", locale)
            .addQueryParameter("include", "capabilities")
            .build()
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
                    deliver(Exchange.Failed(ProductLmspeedFetcher.classifyTransportError(e), null))
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

    private fun read(response: Response): Exchange {
        if (!response.isSuccessful) return Exchange.Failed(null, response.code)
        val source = response.body?.source() ?: return Exchange.Body("")
        val buffer = Buffer()
        while (buffer.size <= MAX_RESPONSE_BYTES) {
            if (source.read(buffer, MAX_RESPONSE_BYTES + 1 - buffer.size) < 0L) break
        }
        if (buffer.size > MAX_RESPONSE_BYTES) {
            return Exchange.Failed(LmspeedFetchFailure.UNKNOWN, response.code, tooLarge = true)
        }
        return Exchange.Body(buffer.readUtf8())
    }

    private fun parseModels(content: String, apiKey: String): List<LmspeedRawModel> {
        val root = runCatching { json.parseToJsonElement(content) }.getOrNull() as? JsonObject
            ?: throw LmspeedFetchException(LmspeedFetchFailure.UNKNOWN)
        val data = root["data"] as? JsonObject
            ?: throw LmspeedFetchException(LmspeedFetchFailure.UNKNOWN)
        val models = data["models"] as? JsonArray
            ?: throw LmspeedFetchException(LmspeedFetchFailure.UNKNOWN)
        val echo = credentialEcho(apiKey)
        val out = ArrayList<LmspeedRawModel>(models.size.coerceAtMost(MAX_MODELS))
        for (element in models) {
            if (out.size >= MAX_MODELS) break
            val obj = element as? JsonObject ?: continue
            val slug = (obj["slug"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_SLUG_CHARS } ?: continue
            if (echo(slug)) continue
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_CHARS)?.takeIf { !echo(it) }
            val developer = (obj["developer"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_CHARS)?.takeIf { !echo(it) }
            val lmspeedUrl = (obj["lmspeed_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_CHARS }?.takeIf { !echo(it) }
            val description = (obj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() }?.take(MAX_DESCRIPTION_TRANSPORT_CHARS)?.takeIf { !echo(it) }
            val contextWindow = (obj["context_window_tokens"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            val maxOutput = (obj["max_output_tokens"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            val capabilities = (obj["capabilities"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?.filter { !echo(it) }
                ?.take(MAX_CAPABILITIES_TRANSPORT)?.takeIf { it.isNotEmpty() }
            // 速度：list `stats` 優先，否則 `performance_summary`；單位未證實，只搬運。
            val stats = obj["stats"] as? JsonObject
            val perf = obj["performance_summary"] as? JsonObject
            val tps = (stats?.get("average_tokens_per_second") as? JsonPrimitive)?.doubleOrNull
                ?: (perf?.get("average_tokens_per_second") as? JsonPrimitive)?.doubleOrNull
            val latency = (stats?.get("average_first_token_latency") as? JsonPrimitive)?.doubleOrNull
                ?: (perf?.get("average_first_token_latency") as? JsonPrimitive)?.doubleOrNull
            // benchmark／pricing：只存不透明引用片段（截斷），不解析為事實。
            val benchmarkRef = (obj["benchmark_summary"] as? JsonArray)?.firstOrNull()?.toString()
                ?.take(MAX_REF_TRANSPORT_CHARS)?.takeIf { it.isNotBlank() && !echo(it) }
            val pricingRef = (obj["pricing_summary"] as? JsonObject)?.toString()
                ?.take(MAX_REF_TRANSPORT_CHARS)?.takeIf { it.isNotBlank() && !echo(it) }
            out += LmspeedRawModel(
                slug = slug,
                name = name,
                developer = developer,
                lmspeedUrl = lmspeedUrl,
                sourceEndpoint = LMSPEED_MODELS_ENDPOINT,
                description = description,
                contextWindowTokens = contextWindow,
                maxOutputTokens = maxOutput,
                capabilities = capabilities,
                externalTps = tps?.takeIf { it.isFinite() && it > 0.0 },
                externalFirstTokenLatency = latency?.takeIf { it.isFinite() && it >= 0.0 },
                benchmarkRef = benchmarkRef,
                pricingRef = pricingRef,
            )
        }
        return out
    }

    private fun credentialEcho(token: String): (String) -> Boolean {
        if (token.isBlank()) return { false }
        val secrets = buildSet {
            add(token)
            add(token.reversed())
            add(URLEncoder.encode(token, Charsets.UTF_8.name()))
            listOf(token, token.reversed()).forEach { value ->
                add(runCatching { Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) }.getOrNull().orEmpty())
                add(runCatching { Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) }.getOrNull().orEmpty())
            }
        }.filter { it.length >= MIN_SECRET_CHARS }
        if (secrets.isEmpty()) return { false }
        return { candidate -> secrets.any { it in candidate } }
    }

    private sealed interface Exchange {
        data class Failed(
            val probeStatus: LmspeedFetchFailure? = null,
            val httpStatus: Int? = null,
            val tooLarge: Boolean = false,
        ) : Exchange
        data class Body(val content: String) : Exchange
    }

    companion object {
        const val LMSPEED_PRODUCTION_BASE_URL = "https://lmspeed.net"
        const val LMSPEED_HOST = "lmspeed.net"
        const val LMSPEED_DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
        val ALLOWED_LOCALES = setOf("en", "zh", "ru")
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val MAX_MODELS = 1_000
        const val MAX_SLUG_CHARS = 256
        const val MAX_NAME_CHARS = 256
        const val MAX_URL_CHARS = 512
        const val MAX_DESCRIPTION_TRANSPORT_CHARS = 4_096
        const val MAX_CAPABILITIES_TRANSPORT = 64
        const val MAX_REF_TRANSPORT_CHARS = 4_096
        const val MIN_SECRET_CHARS = 8
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
