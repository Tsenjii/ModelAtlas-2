package dev.modelatlas.core.network

import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.SystemClock
import dev.modelatlas.core.inference.missingCapabilitiesFor
import dev.modelatlas.core.multimodal.decodeDataUriBytes
import dev.modelatlas.core.multimodal.formFields
import dev.modelatlas.core.multimodal.parseImageDataArray
import dev.modelatlas.core.multimodal.parseTranscriptionText
import dev.modelatlas.core.multimodal.toWireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PLAY-004 image／audio 真 multipart transport（RV5 §3／§6 P0 修復，選項 a）。
 *
 * - 三個獨立 [EndpointTransport]：[OpenAiImageGenerationTransport]（IMAGES_GENERATION）、
 *   [OpenAiImageEditTransport]（IMAGES_EDIT）、[OpenAiAudioTranscriptionTransport]（AUDIO）。
 *   各自只宣告一種型別，缺失各自獨立 Unsupported（不共用失敗分支；`TransportDispatch`
 *   先宣告先贏）。
 * - Wire 走既有 multimodal helper 正本，不另起形狀：
 *   生成 JSON 走 `ImageGenerationPayload.toWireJson`；
 *   編輯／轉寫 multipart 文字欄走 `ImageEditPayload.formFields`／`AudioOperationPayload.formFields`，
 *   bytes 走 `decodeDataUriBytes`（只收 base64 data URI；遠端 URL 不在此 fetch，避免 SSRF）。
 *   `ImageEditPayload.maskRef` 有值即以 `mask` 欄真送（過去零 pathway，已接通）。
 * - 非串流回傳：`parseImageDataArray` → `StreamEvent.GeneratedImages`；
 *   `parseTranscriptionText` → `StreamEvent.Transcription`；形狀錯誤轉 `Outcome.PARSER`
 *  （不靜默、不猜）。事件序列恆為 RequestSent → Headers → 結果 → Finished（engine
 *   以 Finished 判定 SUCCESS；只轉發、不進 telemetry 由 engine 保證）。
 * - Fail-closed 順序（零 request 保證）：capability gate → operation／payload／messages
 *   檢查 → data URI 解碼 → URL 驗證 → 憑證解析 → 發送。gate 失敗與 operation 錯配皆在
 *   request 發出前拋 `IllegalArgumentException`，憑證尚未讀取（L1 語意）。
 * - Secret 邊界：只在 transport 邊界解析憑證；回應 body 先過 `redactSecrets` 再解析；
 *   對話內容／bytes 永不進 telemetry（只經 Flow 轉發）。
 * - `messages` 必須為空（prompt 在 payload 內；非空即拒，避免靜默丟棄）。
 * - 本檔不碰 `InferenceTarget` 建構與 app 接線（`TransportDispatch` 由呼叫端組裝；
 *   生產接線屬 app 範圍，不在此冒充完成）。
 */
class OpenAiImageGenerationTransport(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    private val allowLoopbackHttpForTests: Boolean = false,
) : EndpointTransport {
    override val supportedTypes: Set<EndpointType> = setOf(EndpointType.IMAGES_GENERATION)
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).build()

    override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
        val missing = target.missingCapabilitiesFor(messages)
        require(missing.isEmpty()) {
            "Model lacks capabilities: ${missing.sortedBy { it.name }.joinToString(separator = ",") { it.name }}"
        }
        require(target.operation == EndpointType.IMAGES_GENERATION.name) {
            "Image generation transport cannot carry ${target.operation}"
        }
        val payload = requireNotNull(target.imageGeneration) {
            "IMAGES_GENERATION needs imageGeneration payload"
        }
        require(target.imageEdit == null && target.audioOperation == null) {
            "Image generation transport cannot carry other media payload"
        }
        require(messages.isEmpty()) {
            "Media operation carries prompt in payload, not messages"
        }
        val url = resolveMediaUrl(target.endpointUrl, allowLoopbackHttpForTests)
        val bodyJson = payload.toWireJson(target.modelId)
        val auth = attachMediaAuth(target, url, credentials)
        val request = auth.builder.url(auth.urlBuilder.build())
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        coroutineScope {
            val guard = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                emit(StreamEvent.RequestSent(SystemClock.epochMillis(), SystemClock.monotonicMillis()))
                call.execute().use { response ->
                    val rateHeaders = collectMediaRateHeaders(response, auth.secrets)
                    emit(StreamEvent.Headers(response.code, rateHeaders, SystemClock.monotonicMillis()))
                    if (!response.isSuccessful) throw InferenceFailure(mediaHttpOutcome(response.code), response.code)
                    if (response.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") {
                        throw InferenceFailure(Outcome.PARSER, response.code)
                    }
                    val raw = response.body?.string() ?: throw InferenceFailure(Outcome.PARSER, response.code)
                    if (raw.isBlank()) throw InferenceFailure(Outcome.PARSER, response.code)
                    val safe = auth.secrets.fold(raw) { text, secret -> redactSecrets(text, secret) }
                    val images = try {
                        parseImageDataArray(safe)
                    } catch (_: IllegalArgumentException) {
                        throw InferenceFailure(Outcome.PARSER, response.code)
                    }
                    // GeneratedImage 建構子已複驗（url／b64 至少一欄）；空列表由 parser 拒絕。
                    emit(StreamEvent.GeneratedImages(images))
                    emit(StreamEvent.Finished)
                }
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) throw CancellationException("Inference cancelled")
                throw e
            } finally { guard.cancel() }
        }
    }.map<StreamEvent, Result<StreamEvent>> { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .flowOn(Dispatchers.IO)
        .map { it.getOrThrow() }
}

class OpenAiImageEditTransport(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    private val allowLoopbackHttpForTests: Boolean = false,
) : EndpointTransport {
    override val supportedTypes: Set<EndpointType> = setOf(EndpointType.IMAGES_EDIT)
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).build()

    override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
        val missing = target.missingCapabilitiesFor(messages)
        require(missing.isEmpty()) {
            "Model lacks capabilities: ${missing.sortedBy { it.name }.joinToString(separator = ",") { it.name }}"
        }
        require(target.operation == EndpointType.IMAGES_EDIT.name) {
            "Image edit transport cannot carry ${target.operation}"
        }
        val payload = requireNotNull(target.imageEdit) {
            "IMAGES_EDIT needs imageEdit payload"
        }
        require(target.imageGeneration == null && target.audioOperation == null) {
            "Image edit transport cannot carry other media payload"
        }
        require(messages.isEmpty()) {
            "Media operation carries prompt in payload, not messages"
        }
        // bytes 在 request 發出前解析；形狀錯誤即 IllegalArgumentException（零 request）。
        val image = decodeDataUriBytes(payload.imageRef, "image/")
        val mask = payload.maskRef?.let { decodeDataUriBytes(it, "image/") }
        val fields = payload.formFields(target.modelId)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            for ((key, value) in fields) addFormDataPart(key, value)
            addFormDataPart(
                "image",
                "image.${mediaExtension(image.mimeType)}",
                image.bytes.toRequestBody(image.mimeType.toMediaType()),
            )
            if (mask != null) {
                addFormDataPart(
                    "mask",
                    "mask.${mediaExtension(mask.mimeType)}",
                    mask.bytes.toRequestBody(mask.mimeType.toMediaType()),
                )
            }
        }.build()
        val url = resolveMediaUrl(target.endpointUrl, allowLoopbackHttpForTests)
        val auth = attachMediaAuth(target, url, credentials)
        val request = auth.builder.url(auth.urlBuilder.build()).post(multipart).build()
        val call = client.newCall(request)
        coroutineScope {
            val guard = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                emit(StreamEvent.RequestSent(SystemClock.epochMillis(), SystemClock.monotonicMillis()))
                call.execute().use { response ->
                    val rateHeaders = collectMediaRateHeaders(response, auth.secrets)
                    emit(StreamEvent.Headers(response.code, rateHeaders, SystemClock.monotonicMillis()))
                    if (!response.isSuccessful) throw InferenceFailure(mediaHttpOutcome(response.code), response.code)
                    if (response.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") {
                        throw InferenceFailure(Outcome.PARSER, response.code)
                    }
                    val raw = response.body?.string() ?: throw InferenceFailure(Outcome.PARSER, response.code)
                    if (raw.isBlank()) throw InferenceFailure(Outcome.PARSER, response.code)
                    val safe = auth.secrets.fold(raw) { text, secret -> redactSecrets(text, secret) }
                    val images = try {
                        parseImageDataArray(safe)
                    } catch (_: IllegalArgumentException) {
                        throw InferenceFailure(Outcome.PARSER, response.code)
                    }
                    emit(StreamEvent.GeneratedImages(images))
                    emit(StreamEvent.Finished)
                }
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) throw CancellationException("Inference cancelled")
                throw e
            } finally { guard.cancel() }
        }
    }.map<StreamEvent, Result<StreamEvent>> { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .flowOn(Dispatchers.IO)
        .map { it.getOrThrow() }
}

class OpenAiAudioTranscriptionTransport(
    private val credentials: CredentialAccess,
    client: OkHttpClient = OkHttpClient(),
    private val allowLoopbackHttpForTests: Boolean = false,
) : EndpointTransport {
    override val supportedTypes: Set<EndpointType> = setOf(EndpointType.AUDIO)
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).build()

    override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
        val missing = target.missingCapabilitiesFor(messages)
        require(missing.isEmpty()) {
            "Model lacks capabilities: ${missing.sortedBy { it.name }.joinToString(separator = ",") { it.name }}"
        }
        require(target.operation == EndpointType.AUDIO.name) {
            "Audio transport cannot carry ${target.operation}"
        }
        val payload = requireNotNull(target.audioOperation) {
            "AUDIO needs audioOperation payload"
        }
        require(target.imageGeneration == null && target.imageEdit == null) {
            "Audio transport cannot carry other media payload"
        }
        require(messages.isEmpty()) {
            "Media operation carries prompt in payload, not messages"
        }
        val audio = decodeDataUriBytes(payload.audioRef, "audio/")
        val fields = payload.formFields(target.modelId)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            for ((key, value) in fields) addFormDataPart(key, value)
            addFormDataPart(
                "file",
                "audio.${mediaExtension(audio.mimeType)}",
                audio.bytes.toRequestBody(audio.mimeType.toMediaType()),
            )
        }.build()
        val url = resolveMediaUrl(target.endpointUrl, allowLoopbackHttpForTests)
        val auth = attachMediaAuth(target, url, credentials)
        val request = auth.builder.url(auth.urlBuilder.build()).post(multipart).build()
        val call = client.newCall(request)
        coroutineScope {
            val guard = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                emit(StreamEvent.RequestSent(SystemClock.epochMillis(), SystemClock.monotonicMillis()))
                call.execute().use { response ->
                    val rateHeaders = collectMediaRateHeaders(response, auth.secrets)
                    emit(StreamEvent.Headers(response.code, rateHeaders, SystemClock.monotonicMillis()))
                    if (!response.isSuccessful) throw InferenceFailure(mediaHttpOutcome(response.code), response.code)
                    if (response.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") {
                        throw InferenceFailure(Outcome.PARSER, response.code)
                    }
                    val raw = response.body?.string() ?: throw InferenceFailure(Outcome.PARSER, response.code)
                    if (raw.isBlank()) throw InferenceFailure(Outcome.PARSER, response.code)
                    val safe = auth.secrets.fold(raw) { text, secret -> redactSecrets(text, secret) }
                    val text = try {
                        parseTranscriptionText(safe)
                    } catch (_: IllegalArgumentException) {
                        throw InferenceFailure(Outcome.PARSER, response.code)
                    }
                    emit(StreamEvent.Transcription(text, SystemClock.monotonicMillis()))
                    emit(StreamEvent.Finished)
                }
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) throw CancellationException("Inference cancelled")
                throw e
            } finally { guard.cancel() }
        }
    }.map<StreamEvent, Result<StreamEvent>> { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .flowOn(Dispatchers.IO)
        .map { it.getOrThrow() }
}

// ---------------------------------------------------------------------------
// 內部共用（本檔私有；安全 posture 與 OpenAiTransport 對齊，不另起規則）。
// ---------------------------------------------------------------------------

private data class MediaAuth(
    val builder: Request.Builder,
    val urlBuilder: HttpUrl.Builder,
    val secrets: List<String>,
)

private val MEDIA_AUTH_FIELD_NAME = Regex("[A-Za-z0-9!#\$%&'*+.^_`|~-]+")
private val MEDIA_FORBIDDEN_AUTH_FIELDS = setOf(
    "cookie", "host", "content-length", "content-type", "accept", "transfer-encoding", "connection",
    "proxy-authorization", "proxy-connection", "te", "trailer", "upgrade",
)
private val MEDIA_SAFE_RATE_HEADERS = setOf(
    "retry-after", "x-ratelimit-limit-requests", "x-ratelimit-remaining-requests", "x-ratelimit-reset-requests",
    "x-ratelimit-limit-tokens", "x-ratelimit-remaining-tokens", "x-ratelimit-reset-tokens",
    "ratelimit-limit", "ratelimit-remaining", "ratelimit-reset",
)

private fun resolveMediaUrl(endpointUrl: String, allowLoopbackHttpForTests: Boolean): HttpUrl {
    if (allowLoopbackHttpForTests) {
        val parsed = endpointUrl.toHttpUrlOrNull() ?: throw InferenceFailure(Outcome.OTHER)
        require(parsed.host in setOf("localhost", "127.0.0.1", "::1"))
        return parsed
    }
    return EndpointPolicy.validate(endpointUrl)
}

private fun attachMediaAuth(
    target: InferenceTarget,
    url: HttpUrl,
    credentials: CredentialAccess,
): MediaAuth {
    val token = target.credentialId?.let {
        credentials.bearerToken(it, EndpointPolicy.origin(url)).also { resolved ->
            require(resolved.isNotBlank() && '\r' !in resolved && '\n' !in resolved)
        }
    }
    val secrets = mutableListOf<String>()
    if (token != null) secrets += token
    val urlBuilder = url.newBuilder()
    val builder = Request.Builder()
        .header("Accept", "application/json")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
    require(target.authFields.map { it.query to it.name.lowercase() }.distinct().size == target.authFields.size)
    target.authFields.forEach { field ->
        require(MEDIA_AUTH_FIELD_NAME.matches(field.name) && field.name.lowercase() !in MEDIA_FORBIDDEN_AUTH_FIELDS)
        if (field.name.equals("Authorization", true)) require(!field.query && field.overrideConfirmed && token == null)
        val value = credentials.bearerToken(field.credentialId, EndpointPolicy.origin(url))
        require(value.isNotBlank() && value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == '\u0000' })
        secrets += value
        if (field.query) urlBuilder.addQueryParameter(field.name, value) else builder.header(field.name, value)
    }
    return MediaAuth(builder, urlBuilder, secrets)
}

private fun collectMediaRateHeaders(response: okhttp3.Response, secrets: List<String>): Map<String, String> =
    response.headers.names()
        .filter { it.lowercase() in MEDIA_SAFE_RATE_HEADERS }
        .associate { key ->
            key.lowercase() to secrets.fold(response.header(key).orEmpty()) { text, secret ->
                redactSecrets(text, secret)
            }.take(128)
        }

private fun mediaHttpOutcome(code: Int) = when (code) {
    401, 403 -> Outcome.AUTH
    404 -> Outcome.MODEL_UNAVAILABLE
    429 -> Outcome.RATE_LIMIT
    in 500..599 -> Outcome.SERVER
    else -> Outcome.OTHER
}

/** `image/png` → `png`（檔名副檔名；非法回 `bin`，不攜帶路徑分隔）。 */
private fun mediaExtension(mimeType: String): String {
    val sub = mimeType.substringAfter('/', "").substringBefore(';').trim().lowercase()
    return if (Regex("[a-z0-9]{1,16}").matches(sub)) sub else "bin"
}
