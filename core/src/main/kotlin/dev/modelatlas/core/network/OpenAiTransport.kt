package dev.modelatlas.core.network

import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.inference.*
import dev.modelatlas.core.multimodal.MAX_AUDIO_B64_CHARS
import dev.modelatlas.core.multimodal.MAX_TEXT_CHARS
import dev.modelatlas.core.multimodal.MAX_TOOL_ARGS_CHARS
import dev.modelatlas.core.multimodal.MAX_TOOL_ID_CHARS
import dev.modelatlas.core.multimodal.TextPart
import dev.modelatlas.core.multimodal.ToolCallPart
import dev.modelatlas.core.multimodal.isContentArrayPart
import dev.modelatlas.core.multimodal.textFallback
import dev.modelatlas.core.multimodal.toWirePart
import dev.modelatlas.core.multimodal.toWireTool
import dev.modelatlas.core.multimodal.toWireToolCall
import dev.modelatlas.core.multimodal.toolCalls
import dev.modelatlas.core.multimodal.toolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.EOFException
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

object EndpointPolicy {
    fun validate(url: String): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid endpoint")
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty()) { "HTTPS required" }
        require(parsed.query == null && parsed.fragment == null) { "Query and fragment not allowed" }
        return parsed
    }

    fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"

    /** Idempotent: pasting a full chat URL must not double the path segment. */
    fun chatEndpoint(baseUrl: String): String {
        val validated = validate(baseUrl.trim())
        val path = validated.encodedPath.trimEnd('/')
        if (path.endsWith("/chat/completions")) return validated.newBuilder().encodedPath(path).build().toString()
        return validated.newBuilder().encodedPath("$path/chat/completions").build().toString()
    }
}

/**
 * Redacts a credential and its common encoded echoes (URL-encoded, base64 standard/url,
 * padding-stripped) from persisted/observed text. Exact match alone misses echoes such as
 * `?api_key=<url-encoded>` or base64-wrapped debug headers. Short variants (< 8 chars) are
 * skipped to avoid nuking ordinary text; real tokens are far longer.
 */
internal fun redactSecrets(text: String, token: String?): String {
    if (token.isNullOrEmpty()) return text
    var out = text
    for (variant in secretVariants(token)) {
        if (variant.length >= MIN_SECRET_VARIANT_CHARS) out = out.replace(variant, REDACTED_MARK)
    }
    return out
}

private fun secretVariants(token: String): List<String> {
    val bytes = token.toByteArray(Charsets.UTF_8)
    val base64 = runCatching { Base64.getEncoder().encodeToString(bytes) }.getOrNull()
    val base64Url = runCatching { Base64.getUrlEncoder().encodeToString(bytes) }.getOrNull()
    val urlEncoded = runCatching { URLEncoder.encode(token, Charsets.UTF_8.name()) }.getOrNull()
    return listOfNotNull(
        token,
        urlEncoded,
        base64,
        base64?.trimEnd('=').takeIf { it != base64 },
        base64Url,
        base64Url?.trimEnd('=').takeIf { it != base64Url },
    ).distinct()
}

private const val REDACTED_MARK = "[REDACTED]"
private const val MIN_SECRET_VARIANT_CHARS = 8
private val AUTH_FIELD_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
private val FORBIDDEN_AUTH_FIELDS = setOf(
    "cookie", "host", "content-length", "content-type", "accept", "transfer-encoding", "connection",
    "proxy-authorization", "proxy-connection", "te", "trailer", "upgrade",
)

/** SSE framing is separated from JSON parsing for boundary tests and protocol reuse. */
class SseDecoder(private val maxEventChars: Int = 1_048_576) {
    private val data = StringBuilder()
    fun line(line: String): String? {
        if (line.isEmpty()) {
            if (data.isEmpty()) return null
            return data.toString().also { data.setLength(0) }
        }
        if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substring(5).removePrefix(" "))
            if (data.length > maxEventChars) throw InferenceFailure(Outcome.PARSER)
        }
        return null
    }
}

class OpenAiTransport(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    // This escape hatch is only for loopback unit tests; application never enables it.
    private val allowLoopbackHttpForTests: Boolean = false,
) : EndpointTransport {
    /** PROV-010：本 transport 只宣告 CHAT_COMPLETIONS；其餘型別由分派獨立 Unsupported。 */
    override val supportedTypes: Set<EndpointType> = setOf(EndpointType.CHAT_COMPLETIONS)
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
        // P4R PLAY-004 fail-closed（RV5 §3 P0）：本 transport 只承載 CHAT_COMPLETIONS。
        // 非 CHAT operation（IMAGES_GENERATION／IMAGES_EDIT／AUDIO 等）一律在任何
        // request 發出前拒絕，不靜默丟棄 media payload（過去曾把 chat body POST 到
        // images endpoint 的 fail-open 已封堵；媒體走專用 transport＋TransportDispatch）。
        require(target.operation == EndpointType.CHAT_COMPLETIONS.name) {
            "Chat transport cannot carry ${target.operation}"
        }
        require(target.imageGeneration == null && target.imageEdit == null && target.audioOperation == null) {
            "Chat transport cannot carry media payload"
        }
        // P4R PLAY-004 capability gate（真強制）：任何 request 發出前先檢查，
        // 缺失直接拋錯（product-layer gate，不等 provider 400；零 request、零憑證讀取）。
        // 缺失以 Set 元素各自獨立表示（呼叫端可逐項轉獨立 Unsupported，不共用分支）。
        val missing = target.missingCapabilitiesFor(messages)
        require(missing.isEmpty()) {
            "Model lacks capabilities: ${missing.sortedBy { it.name }.joinToString(separator = ",") { it.name }}"
        }
        // blocks 若需 image／audio endpoint，不歸 chat transport 送（路由去專用 transport；
        // 現階段 blocks 皆為 CHAT_COMPLETIONS，此檢查恆通過，未來非 chat block 即 fail-closed）。
        require(messages.requiredEndpointTypes().all { it == EndpointType.CHAT_COMPLETIONS }) {
            "Chat transport cannot carry image/audio endpoint blocks"
        }
        val url = if (allowLoopbackHttpForTests) {
            val parsed = target.endpointUrl.toHttpUrlOrNull() ?: throw InferenceFailure(Outcome.OTHER)
            require(parsed.host in setOf("localhost", "127.0.0.1", "::1"))
            parsed
        } else EndpointPolicy.validate(target.endpointUrl)
        KiloFactoryPolicy.verify(target, url, client, allowLoopbackHttpForTests)
        val payload = buildJsonObject {
            put("model", target.modelId)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            put("max_tokens", target.maxOutputTokens)
            putJsonArray("messages") {
                messages.forEach { message -> add(buildMessageJson(message)) }
            }
            // P4R ChatTools 歸位：tools＋tool_choice 以 ChatToolsPayload 為正本真送
            //（空 tools 省略兩鍵，向下相容；有 tools 即真送，不假裝）。
            target.chatToolsPayload()?.let { payload ->
                putJsonArray("tools") { payload.tools.forEach { add(it.toWireTool()) } }
                payload.toolChoice?.let { put("tool_choice", it) }
            }
        }
        val credentialId = target.credentialId
        // Anonymous targets skip the vault entirely and send no Authorization header.
        val token = credentialId?.let {
            credentials.bearerToken(it, EndpointPolicy.origin(url)).also { resolved ->
                require(resolved.isNotBlank() && '\r' !in resolved && '\n' !in resolved)
            }
        }
        val authSecrets = mutableListOf<String>()
        if (token != null) authSecrets += token
        val requestUrl = url.newBuilder()
        val requestBuilder = Request.Builder()
            .header("Accept", "text/event-stream")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
        require(target.authFields.map { it.query to it.name.lowercase() }.distinct().size == target.authFields.size)
        target.authFields.forEach { field ->
            require(AUTH_FIELD_NAME.matches(field.name) && field.name.lowercase() !in FORBIDDEN_AUTH_FIELDS)
            if (field.name.equals("Authorization", true)) require(!field.query && field.overrideConfirmed && token == null)
            val value = credentials.bearerToken(field.credentialId, EndpointPolicy.origin(url))
            require(value.isNotBlank() && value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == '\u0000' })
            authSecrets += value
            if (field.query) requestUrl.addQueryParameter(field.name, value) else requestBuilder.header(field.name, value)
        }
        val request = requestBuilder.url(requestUrl.build())
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        coroutineScope {
            // Cancel the blocking socket immediately when the collector is cancelled.
            val guard = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                emit(StreamEvent.RequestSent(SystemClock.epochMillis(), SystemClock.monotonicMillis()))
                call.execute().use { response ->
                    val rateHeaders = response.headers.names()
                        .filter { it.lowercase() in SAFE_RATE_HEADERS }
                        .associate { key ->
                            key.lowercase() to authSecrets.fold(response.header(key).orEmpty()) { text, secret -> redactSecrets(text, secret) }.take(128)
                        }
                    emit(StreamEvent.Headers(response.code, rateHeaders, SystemClock.monotonicMillis()))
                    if (!response.isSuccessful) throw InferenceFailure(httpOutcome(response.code), response.code)
                    if (response.header("Content-Type")?.substringBefore(';')?.trim() != "text/event-stream") {
                        throw InferenceFailure(Outcome.PARSER, response.code)
                    }
                    val source = response.body?.source() ?: throw InferenceFailure(Outcome.PARSER)
                    val decoder = SseDecoder()
                    var done = false
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = try { source.readUtf8LineStrict(1_048_576) }
                        catch (_: EOFException) { throw InferenceFailure(Outcome.PARSER) }
                        val data = decoder.line(line) ?: continue
                        if (data == "[DONE]") { done = true; emit(StreamEvent.Finished); break }
                        val received = SystemClock.monotonicMillis()
                        parse(authSecrets.fold(data) { text, secret -> redactSecrets(text, secret) }).forEach { event ->
                            // P4R：時間戳回填覆蓋全部可戳事件（Delta／ToolCallDelta／AudioDelta／
                            // Transcription；其餘沿用 engine 本地 clock）。
                            emit(when (event) {
                                is StreamEvent.Delta -> event.copy(observedMonotonicMs = received)
                                is StreamEvent.ToolCallDelta -> event.copy(observedMonotonicMs = received)
                                is StreamEvent.AudioDelta -> event.copy(observedMonotonicMs = received)
                                is StreamEvent.Transcription -> event.copy(observedMonotonicMs = received)
                                else -> event
                            })
                        }
                    }
                    if (!done) throw InferenceFailure(Outcome.PARSER)
                }
            } catch (e: IOException) {
                // A cancelled collector must observe cancellation, not a socket error.
                if (!currentCoroutineContext().isActive) throw CancellationException("Inference cancelled")
                throw e
            } finally { guard.cancel() }
        }
    // Carry failures through the same ordered channel as events. Throwing upstream
    // of flowOn can cancel delivery of buffered headers / partial output.
    // Flow.catch preserves cancellation and does not catch collector failures.
    }.map<StreamEvent, Result<StreamEvent>> { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .flowOn(Dispatchers.IO)
        .map { it.getOrThrow() }

    /**
     * P4R PLAY-004 message wire builder（真送路徑；legacy 與 multimodal 共用）。
     *
     * - blocks 空：`{"role":..,"content": String}`（legacy，與舊行為完全一致）。
     * - tool result：`{"role":"tool","tool_call_id":..,"content":..}`（sole block，fail-closed）。
     * - assistant tool_calls：content 走文字 fallback（空則 null）＋ `tool_calls[]` 真送。
     * - 其餘 multimodal：`{"role":..,"content": [...]}`（text/image_url/file/input_audio 真送）。
     * 形狀不合法（tool 與 multimodal 混雜、role 錯配）直接 IllegalArgumentException，
     * 在任何 request 發出前失敗，不假裝送出。
     */
    internal fun buildMessageJson(message: PromptMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        if (message.blocks.isEmpty()) {
            put("content", message.content)
            return@buildJsonObject
        }
        val blocks = message.blocks
        val result = blocks.toolResult()
        if (result != null) {
            require(message.role == "tool") { "ToolResultPart requires role=tool" }
            require(blocks.size == 1) { "Tool message must carry exactly one ToolResultPart" }
            put("tool_call_id", result.toolCallId)
            put("content", result.content)
            return@buildJsonObject
        }
        val calls = blocks.toolCalls()
        if (calls.isNotEmpty()) {
            require(message.role == "assistant") { "ToolCallPart requires role=assistant" }
            require(blocks.all { it.isContentArrayPart() || it is ToolCallPart }) {
                "Tool calls cannot mix with tool results"
            }
            // tool_calls 併送時只允許純文字＋tool calls；image/file/audio 不可混在同一 assistant 訊息。
            require(blocks.all { it is TextPart || it is ToolCallPart }) {
                "Tool calls message supports text parts only"
            }
            val fallback = blocks.textFallback()
            if (fallback.isEmpty()) put("content", JsonNull) else put("content", fallback)
            putJsonArray("tool_calls") { calls.forEach { add(it.toWireToolCall()) } }
            return@buildJsonObject
        }
        require(blocks.all { it.isContentArrayPart() }) { "Unsupported block for content array" }
        putJsonArray("content") { blocks.forEach { add(it.toWirePart()) } }
    }

    internal fun parse(data: String): List<StreamEvent> {
        try {
            val root = json.parseToJsonElement(data).jsonObject
            if (root["error"] != null) throw InferenceFailure(Outcome.OTHER)
            return buildList {
                root["model"]?.jsonPrimitive?.contentOrNull?.let { add(StreamEvent.ResponseModel(it.take(256))) }
                root["choices"]?.jsonArray?.forEach { choice ->
                    val obj = choice.jsonObject
                    // n=1: never merge multiple completion alternatives into one message.
                    if ((obj["index"]?.jsonPrimitive?.intOrNull ?: 0) != 0) return@forEach
                    val delta = obj["delta"]?.jsonObject ?: return@forEach
                    delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                        ?.let { add(StreamEvent.Delta(it, reasoning = true)) }
                    delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                        ?.let { add(StreamEvent.Delta(it)) }
                    // P4R 入方向 tool_calls：逐片轉 ToolCallDelta，
                    // 呼叫端以 toFragment＋assembleToolCalls 組成 ToolCallPart（不再靜默丟棄）。
                    // 註：tool_calls[] 內的 index 是「同回應內第幾通呼叫」，不可與 choices 的 n=1 混淆。
                    // 非陣列的 tool_calls 屬協定違規→PARSER（不靜默吞掉）；null／缺席才忽略。
                    delta["tool_calls"]?.takeUnless { it is JsonNull }?.let { rawCalls ->
                        val calls = (rawCalls as? JsonArray) ?: throw InferenceFailure(Outcome.PARSER)
                        calls.forEach { raw ->
                            val call = raw.jsonObject
                            val callIndex = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                            require(callIndex >= 0) { "Bad tool call index" }
                            val id = call["id"]?.jsonPrimitive?.contentOrNull
                            if (id != null) require(id.length <= MAX_TOOL_ID_CHARS) { "Tool call id too large" }
                            val function = call["function"] as? JsonObject
                            val name = function?.get("name")?.jsonPrimitive?.contentOrNull
                            val args = function?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
                            require(args.length <= MAX_TOOL_ARGS_CHARS) { "Tool arguments too large" }
                            add(
                                StreamEvent.ToolCallDelta(
                                    callIndex,
                                    id?.takeIf { it.isNotEmpty() },
                                    name?.takeIf { it.isNotEmpty() },
                                    args,
                                ),
                            )
                        }
                    }
                    // 舊版 function_call（單呼叫相容；同樣走碎片＋組裝；型別錯誤→PARSER）。
                    delta["function_call"]?.takeUnless { it is JsonNull }?.let { rawFunction ->
                        val function = (rawFunction as? JsonObject) ?: throw InferenceFailure(Outcome.PARSER)
                        val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val args = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        require(args.length <= MAX_TOOL_ARGS_CHARS) { "Tool arguments too large" }
                        if (name.isNotEmpty() || args.isNotEmpty()) {
                            add(StreamEvent.ToolCallDelta(0, null, name.takeIf { it.isNotEmpty() }, args))
                        }
                    }
                    // P4R response-side audio 輸出碎片（delta.audio；transcript＋base64）。
                    // Image 生成為非串流 `data[]`（見 parseImageDataArray），不走 SSE delta，
                    // 故此處只處理 audio；兩者缺失獨立、不互相拖累。
                    delta["audio"]?.takeUnless { it is JsonNull }?.let { rawAudio ->
                        val audio = (rawAudio as? JsonObject) ?: throw InferenceFailure(Outcome.PARSER)
                        val transcript = audio["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val audioData = audio["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        require(transcript.length <= MAX_TEXT_CHARS && audioData.length <= MAX_AUDIO_B64_CHARS) {
                            "Audio delta too large"
                        }
                        if (transcript.isNotEmpty() || audioData.isNotEmpty()) {
                            add(StreamEvent.AudioDelta(transcript, audioData))
                        }
                    }
                }
                (root["usage"] as? JsonObject)?.let { usage ->
                    val completionDetails = usage["completion_tokens_details"] as? JsonObject
                    val promptDetails = usage["prompt_tokens_details"] as? JsonObject
                    add(StreamEvent.TokenUsage(Usage(
                        inputTokens = usage.count("prompt_tokens"), outputTokens = usage.count("completion_tokens"),
                        totalTokens = usage.count("total_tokens"), reasoningTokens = completionDetails?.count("reasoning_tokens"),
                        cacheReadTokens = promptDetails?.count("cached_tokens"),
                        cacheWriteTokens = usage.count("cache_creation_input_tokens"),
                    )))
                }
            }
        } catch (e: InferenceFailure) { throw e }
        catch (_: Exception) { throw InferenceFailure(Outcome.PARSER) }
    }

    private fun JsonObject.count(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

    private fun httpOutcome(code: Int) = when (code) {
        401, 403 -> Outcome.AUTH
        404 -> Outcome.MODEL_UNAVAILABLE
        429 -> Outcome.RATE_LIMIT
        in 500..599 -> Outcome.SERVER
        else -> Outcome.OTHER
    }

    companion object {
        private val SAFE_RATE_HEADERS = setOf(
            "retry-after", "x-ratelimit-limit-requests", "x-ratelimit-remaining-requests", "x-ratelimit-reset-requests",
            "x-ratelimit-limit-tokens", "x-ratelimit-remaining-tokens", "x-ratelimit-reset-tokens",
            "ratelimit-limit", "ratelimit-remaining", "ratelimit-reset",
        )
    }
}
