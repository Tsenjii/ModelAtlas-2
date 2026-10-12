package dev.modelatlas.core.multimodal

import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.EndpointType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * P4R PLAY-004 multimodal blocks（單一連貫實作）。
 *
 * TRACEABILITY：PLAY-004（PRODUCT_SPEC §3 Playground Vision/Files/Tool calls、
 * §7 Classification capabilities[] 用於 Playground feature gating）。
 *
 * - Vision / Files / Audio 以引用（URL / file_id / base64 data URI）承載，
 *   不在 domain 層持有原始 bytes；secret 只在 transport 邊界解析。
 * - Tool calls / tool results 為結構化 blocks，不混入純文字。
 * - 每個 block 以 [requiredCapabilities] 標記所需 capability，供 gating 消費；
 *   缺失以 Set 元素各自獨立表示（不共用單一失敗分支）。
 * - Wire（OpenAI Chat Completions content array）由 [toWirePart] 等產生，
 *   由 `OpenAiTransport.buildMessageJson` 真送，不假裝。
 * - 本檔不定義 `MultimodalKind`（沿用既有 `MultimodalKind.kt` 的種類→endpoint
 *   映射，避免同包重名破壞編譯）；blocks 皆走 CHAT_COMPLETIONS，image／audio
 *   操作走 `InferenceTarget` payload＋operation（見 `Contracts.kt`）。
 * - 對話內容永不進 telemetry（`InferenceEngine` 只轉發新事件，不持久化 payload）。
 */
@Serializable
sealed interface ContentBlock

/** 純文字。無額外 capability 需求。 */
@Serializable
@SerialName("text")
data class TextPart(val text: String) : ContentBlock {
    init {
        require(text.length <= MAX_TEXT_CHARS) { "Text block too large" }
    }
}

/**
 * 圖片引用：https URL 或 `data:image/...;base64,...` data URI。
 * `detail` 僅接受 OpenAI 的 auto/low/high。
 */
@Serializable
@SerialName("image_url")
data class ImageUrlPart(
    val url: String,
    val detail: String = "auto",
    val filename: String? = null,
) : ContentBlock {
    init {
        require(url.isNotBlank()) { "Image url must not be blank" }
        require(url.length <= MAX_IMAGE_URL_CHARS) { "Image url too large" }
        require(detail in IMAGE_DETAILS) { "Unknown image detail" }
        require('\r' !in url && '\n' !in url && '\u0000' !in url) { "Image url has control chars" }
        if (filename != null) {
            require(filename.isNotBlank() && filename.length <= MAX_FILENAME_CHARS) { "Bad filename" }
            require('\r' !in filename && '\n' !in filename && '\u0000' !in filename) { "Filename has control chars" }
        }
    }
}

/**
 * 檔案引用：`fileId` 與 `fileDataUrl` 恰擇一（對應 OpenAI `file.file_id` xor `file.file_data`）。
 */
@Serializable
@SerialName("file")
data class FileRefPart(
    val fileId: String? = null,
    val filename: String? = null,
    val fileDataUrl: String? = null,
) : ContentBlock {
    init {
        val hasId = !fileId.isNullOrBlank()
        val hasData = !fileDataUrl.isNullOrBlank()
        require(hasId xor hasData) { "File ref needs exactly one of fileId/fileDataUrl" }
        if (fileId != null) {
            require(fileId.isNotBlank() && fileId.length <= MAX_FILE_ID_CHARS) { "Bad fileId" }
            require(fileId.none { it.isWhitespace() || it == '\r' || it == '\n' || it == '\u0000' }) {
                "fileId has whitespace/control chars"
            }
        }
        if (fileDataUrl != null) {
            require(fileDataUrl.startsWith("data:")) { "fileDataUrl must be a data: URI" }
            require(fileDataUrl.length <= MAX_FILE_DATA_CHARS) { "fileDataUrl too large" }
            require('\r' !in fileDataUrl && '\n' !in fileDataUrl && '\u0000' !in fileDataUrl) {
                "fileDataUrl has control chars"
            }
        }
        if (filename != null) {
            require(filename.isNotBlank() && filename.length <= MAX_FILENAME_CHARS) { "Bad filename" }
            require('\r' !in filename && '\n' !in filename && '\u0000' !in filename) { "Filename has control chars" }
        }
    }
}

/**
 * 音訊輸入引用：OpenAI `input_audio`（base64 + format）。
 * 目前僅支援 wav/mp3（OpenAI Chat Completions 契約）。
 */
@Serializable
@SerialName("input_audio")
data class AudioInputPart(val dataBase64: String, val format: String) : ContentBlock {
    init {
        require(dataBase64.isNotBlank()) { "Audio data must not be blank" }
        require(dataBase64.length <= MAX_AUDIO_B64_CHARS) { "Audio data too large" }
        require(format in AUDIO_FORMATS) { "Unknown audio format" }
        require('\r' !in dataBase64 && '\n' !in dataBase64 && '\u0000' !in dataBase64) {
            "Audio data has control chars"
        }
    }
}

/** Assistant 端 tool call 請求（對應 Chat Completions `tool_calls[]` 一項）。 */
@Serializable
@SerialName("tool_call")
data class ToolCallPart(val id: String, val name: String, val argumentsJson: String) : ContentBlock {
    init {
        require(id.isNotBlank() && id.length <= MAX_TOOL_ID_CHARS) { "Bad tool call id" }
        require(TOOL_NAME.matches(name)) { "Bad tool name" }
        require(argumentsJson.isNotBlank() && argumentsJson.length <= MAX_TOOL_ARGS_CHARS) { "Bad tool arguments" }
        val parsed = runCatching { WireJson.parseToJsonElement(argumentsJson) }.getOrNull()
        require(parsed is JsonObject) { "Tool arguments must be a JSON object" }
    }
}

/** Tool 角色回結果（對應 `role=tool` + `tool_call_id`）。 */
@Serializable
@SerialName("tool_result")
data class ToolResultPart(val toolCallId: String, val content: String) : ContentBlock {
    init {
        require(toolCallId.isNotBlank() && toolCallId.length <= MAX_TOOL_ID_CHARS) { "Bad tool_call_id" }
        require(content.length <= MAX_TEXT_CHARS) { "Tool result too large" }
    }
}

/** Chat Completions `tools[]` 內的 function 定義（operation payload 一部分）。 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String? = null,
    val parametersJson: String? = null,
) {
    init {
        require(TOOL_NAME.matches(name)) { "Bad tool name" }
        if (description != null) {
            require(description.length <= MAX_TOOL_DESC_CHARS) { "Tool description too large" }
            require('\r' !in description && '\u0000' !in description) { "Tool description has control chars" }
        }
        if (parametersJson != null) {
            val params = requireNotNull(parametersJson) { "Bad tool parameters" }
            require(params.isNotBlank() && params.length <= MAX_TOOL_ARGS_CHARS) {
                "Bad tool parameters"
            }
            val parsed = runCatching { WireJson.parseToJsonElement(params) }.getOrNull()
            require(parsed is JsonObject) { "Tool parameters must be a JSON object" }
        }
    }
}

/** Image generation payload（endpoint IMAGES_GENERATION；HTTP 由專用 transport 承載，見 Contracts）。 */
@Serializable
data class ImageGenerationPayload(
    val prompt: String,
    val size: String? = null,
    val n: Int = 1,
) {
    init {
        require(prompt.isNotBlank() && prompt.length <= MAX_TEXT_CHARS) { "Bad image prompt" }
        require(n in 1..10) { "n out of range" }
        if (size != null) require(size.isNotBlank() && size.length <= 32) { "Bad image size" }
    }
}

/** Image edit payload（endpoint IMAGES_EDIT）。 */
@Serializable
data class ImageEditPayload(
    val imageRef: String,
    val prompt: String,
    val maskRef: String? = null,
    val size: String? = null,
) {
    init {
        require(imageRef.isNotBlank()) { "imageRef must not be blank" }
        require(prompt.isNotBlank() && prompt.length <= MAX_TEXT_CHARS) { "Bad image prompt" }
        if (maskRef != null) require(maskRef.isNotBlank()) { "maskRef must not be blank" }
        if (size != null) require(size.isNotBlank() && size.length <= 32) { "Bad image size" }
    }
}

/** Audio operation payload（endpoint AUDIO；轉寫類，輸入側）。 */
@Serializable
data class AudioOperationPayload(
    val audioRef: String,
    val language: String? = null,
    val responseFormat: String? = null,
) {
    init {
        require(audioRef.isNotBlank()) { "audioRef must not be blank" }
        if (language != null) require(language.isNotBlank() && language.length <= 16) { "Bad language" }
        if (responseFormat != null) require(responseFormat.isNotBlank() && responseFormat.length <= 16) {
            "Bad response format"
        }
    }
}

/** Chat with tools 的 operation payload（仍走 CHAT_COMPLETIONS，tools 放頂層）。 */
@Serializable
data class ChatToolsPayload(
    val tools: List<ToolDefinition>,
    val toolChoice: String? = null,
) {
    init {
        require(tools.isNotEmpty()) { "Tools must not be empty" }
        validateToolDefinitions(tools)
        validateToolChoice(toolChoice)
    }
}

/**
 * tools／tool_choice 驗證單一來源（`ChatToolsPayload` 與 `InferenceTarget` 共用）。
 */
fun validateToolDefinitions(tools: List<ToolDefinition>) {
    require(tools.size <= MAX_TOOLS) { "Tools out of range" }
    require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate tool names" }
}

/** `tool_choice` 只接受 OpenAI 字串形三值；物件形尚無欄位承載。 */
fun validateToolChoice(toolChoice: String?) {
    if (toolChoice != null) require(toolChoice in TOOL_CHOICES) { "Bad tool_choice" }
}

private val TOOL_CHOICES: Set<String> = setOf("auto", "none", "required")

/**
 * 入方向 tool call 碎片（SSE `delta.tool_calls[]` 切片的純資料形；
 * transport 產生、`assembleToolCalls` 消費；不碰憑證、不進 telemetry）。
 */
data class ToolCallFragment(
    val index: Int,
    val id: String?,
    val name: String?,
    val argumentsFragment: String,
)

/**
 * 碎片 → `ToolCallPart`（tool-calling round-trip 後半，純函式）。
 * 同 index 碎片按序拼接 arguments；`id`／`name` 取最後一次非空值
 * （OpenAI 首片給全量，後續片僅帶 arguments）。
 * 終態經 `ToolCallPart` 建構子複驗（JSON object 形狀＋長度上限），不猜語意。
 */
fun List<ToolCallFragment>.assembleToolCalls(): List<ToolCallPart> {
    if (isEmpty()) return emptyList()
    return groupBy { it.index }.toSortedMap().map { (index, parts) ->
        val id = parts.mapNotNull { it.id }.lastOrNull()
            ?: throw IllegalArgumentException("Tool call #$index missing id")
        val name = parts.mapNotNull { it.name?.takeIf { n -> n.isNotEmpty() } }.lastOrNull()
            ?: throw IllegalArgumentException("Tool call #$index missing name")
        ToolCallPart(id, name, parts.joinToString(separator = "") { it.argumentsFragment })
    }
}

/**
 * Image generation／edit 非串流結果一項（`data[]` 元素；只持引用或 base64，
 * 永不進 telemetry；至少一欄非空）。
 */
@Serializable
data class GeneratedImage(
    val url: String? = null,
    val b64Json: String? = null,
    val revisedPrompt: String? = null,
) {
    init {
        require(url != null || b64Json != null) { "Image result needs url or b64_json" }
        if (url != null) {
            require(url.isNotBlank() && url.length <= MAX_IMAGE_URL_CHARS) { "Bad image url" }
            require('\r' !in url && '\n' !in url && '\u0000' !in url) { "Image url has control chars" }
        }
        if (b64Json != null) {
            require(b64Json.isNotBlank() && b64Json.length <= MAX_IMAGE_B64_CHARS) { "Image data too large" }
            require('\r' !in b64Json && '\n' !in b64Json && '\u0000' !in b64Json) {
                "Image data has control chars"
            }
        }
        if (revisedPrompt != null) require(revisedPrompt.length <= MAX_TEXT_CHARS) { "Revised prompt too large" }
    }
}

/** 每個 block 所需 capability（純文字無需求；其餘各自獨立標記）。 */
val ContentBlock.requiredCapabilities: Set<Capability>
    get() = when (this) {
        is TextPart -> emptySet()
        is ImageUrlPart -> setOf(Capability.VISION)
        is FileRefPart -> setOf(Capability.FILES)
        is AudioInputPart -> setOf(Capability.AUDIO_INPUT)
        is ToolCallPart -> setOf(Capability.TOOL_CALLING)
        is ToolResultPart -> setOf(Capability.TOOL_CALLING)
    }

fun List<ContentBlock>.requiredCapabilities(): Set<Capability> =
    flatMapTo(LinkedHashSet()) { it.requiredCapabilities }

fun List<ContentBlock>.missingCapabilities(offered: Set<Capability>): Set<Capability> =
    requiredCapabilities() - offered

/** 每個 block 所需 endpoint（現階段 blocks 皆走 CHAT_COMPLETIONS；image／audio 操作走 payload＋operation）。 */
val ContentBlock.requiredEndpointType: EndpointType
    get() = when (this) {
        is TextPart -> EndpointType.CHAT_COMPLETIONS
        is ImageUrlPart -> EndpointType.CHAT_COMPLETIONS
        is FileRefPart -> EndpointType.CHAT_COMPLETIONS
        is AudioInputPart -> EndpointType.CHAT_COMPLETIONS
        is ToolCallPart -> EndpointType.CHAT_COMPLETIONS
        is ToolResultPart -> EndpointType.CHAT_COMPLETIONS
    }

fun List<ContentBlock>.requiredEndpointTypes(): Set<EndpointType> =
    mapTo(LinkedHashSet()) { it.requiredEndpointType }

// ---------------------------------------------------------------------------
// Chat Completions wire builders（OpenAI 真送形狀；由 OpenAiTransport 呼叫）。
// ---------------------------------------------------------------------------

internal val WireJson: Json = Json { ignoreUnknownKeys = true }

/** content array 內單 part（text / image_url / file / input_audio；tool 另行處理）。 */
fun ContentBlock.toWirePart(): JsonObject = when (this) {
    is TextPart -> buildJsonObject {
        put("type", "text")
        put("text", text)
    }
    is ImageUrlPart -> buildJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") {
            put("url", url)
            put("detail", detail)
        }
    }
    is FileRefPart -> buildJsonObject {
        put("type", "file")
        putJsonObject("file") {
            if (fileId != null) put("file_id", fileId)
            if (filename != null) put("filename", filename)
            if (fileDataUrl != null) put("file_data", fileDataUrl)
        }
    }
    is AudioInputPart -> buildJsonObject {
        put("type", "input_audio")
        putJsonObject("input_audio") {
            put("data", dataBase64)
            put("format", format)
        }
    }
    is ToolCallPart -> throw IllegalArgumentException("ToolCallPart is not a content-array part")
    is ToolResultPart -> throw IllegalArgumentException("ToolResultPart is not a content-array part")
}

/** 是否可放入 content array（text/image/file/audio；tool 不可）。 */
fun ContentBlock.isContentArrayPart(): Boolean = when (this) {
    is TextPart, is ImageUrlPart, is FileRefPart, is AudioInputPart -> true
    is ToolCallPart, is ToolResultPart -> false
}

fun List<ContentBlock>.toolCalls(): List<ToolCallPart> = filterIsInstance<ToolCallPart>()

fun List<ContentBlock>.toolResult(): ToolResultPart? = filterIsInstance<ToolResultPart>().singleOrNull()

/** blocks 內文字 fallback（assistant tool_calls 併送時 content 用；無文字回 ""）。 */
fun List<ContentBlock>.textFallback(): String =
    filterIsInstance<TextPart>().joinToString(separator = "") { it.text }

fun ToolDefinition.toWireTool(): JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", name)
        if (description != null) put("description", description)
        if (parametersJson != null) {
            put("parameters", WireJson.parseToJsonElement(parametersJson).jsonObject)
        }
    }
}

fun ToolCallPart.toWireToolCall(): JsonObject = buildJsonObject {
    put("id", id)
    put("type", "function")
    putJsonObject("function") {
        put("name", name)
        put("arguments", argumentsJson)
    }
}

// ---------------------------------------------------------------------------
// Image／Audio operation wire（payload 真送形狀；HTTP 由專用 transport 承載時共用）。
// 缺失 transport 時 TransportDispatch 各回獨立 Unsupported，不假裝送出。
// ---------------------------------------------------------------------------

/** `POST images/generations` JSON body（`model` 由 target 補，payload 不持模型）。 */
fun ImageGenerationPayload.toWireJson(model: String): JsonObject = buildJsonObject {
    require(model.isNotBlank()) { "Bad model" }
    put("model", model)
    put("prompt", prompt)
    if (size != null) put("size", size)
    put("n", n)
}

/** `POST images/edits` multipart 文字欄（bytes 另以 [decodeDataUriBytes] 解析）。 */
fun ImageEditPayload.formFields(model: String): Map<String, String> {
    require(model.isNotBlank()) { "Bad model" }
    val imageSize = this@formFields.size
    return buildMap {
        put("model", model)
        put("prompt", prompt)
        imageSize?.let { put("size", it) }
    }
}

/** `POST audio/transcriptions` multipart 文字欄（bytes 另以 [decodeDataUriBytes] 解析）。 */
fun AudioOperationPayload.formFields(model: String): Map<String, String> {
    require(model.isNotBlank()) { "Bad model" }
    return buildMap {
        put("model", model)
        if (language != null) put("language", language)
        if (responseFormat != null) put("response_format", responseFormat)
    }
}

/** 解析出的 data URI（mime＋bytes；呼叫端決定檔名／欄名）。 */
data class DecodedDataUri(val mimeType: String, val bytes: ByteArray)

/**
 * `data:<mime>;base64,<payload>` → bytes（image／audio multipart 上傳用，純函式）。
 * 只接受 base64 data URI（遠端 URL 不在此 fetch，避免 SSRF）。
 * 形狀不合法直接 IllegalArgumentException（request 發出前失敗）。
 */
fun decodeDataUriBytes(uri: String, expectedMimePrefix: String): DecodedDataUri {
    require('\r' !in uri && '\n' !in uri && '\u0000' !in uri) { "Data URI has control chars" }
    require(uri.startsWith("data:")) { "Not a data URI" }
    val comma = uri.indexOf(',')
    require(comma > "data:".length) { "Bad data URI" }
    val meta = uri.substring("data:".length, comma)
    val payload = uri.substring(comma + 1)
    val metaParts = meta.split(';')
    require(metaParts.size == 2 && metaParts[1] == "base64") { "Only base64 data URIs are supported" }
    val mime = metaParts[0]
    require(mime.startsWith(expectedMimePrefix) && MIME_TOKEN.matches(mime)) { "Unexpected media type" }
    require(payload.isNotBlank() && payload.length <= MAX_FILE_DATA_CHARS) { "Data URI payload out of range" }
    val bytes = runCatching { java.util.Base64.getDecoder().decode(payload) }.getOrElse {
        throw IllegalArgumentException("Bad base64 payload")
    }
    require(bytes.isNotEmpty()) { "Empty data URI payload" }
    return DecodedDataUri(mime, bytes)
}

private val MIME_TOKEN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9!#\$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#\$&^_.+-]*")

// ---------------------------------------------------------------------------
// Response 解析（入方向，純函式；transport 與測試共用，不碰憑證、不進 telemetry）。
// ---------------------------------------------------------------------------

private val ResponseJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Image 非串流回應解析：`{"data":[{"url":..,"b64_json":..,"revised_prompt":..}]}` →
 * [GeneratedImage] 列表。空 `data`、缺欄、形狀錯誤一律 IllegalArgumentException
 * （transport 層轉為 `Outcome.PARSER`，不靜默吞掉）。
 */
fun parseImageDataArray(rawJson: String): List<GeneratedImage> {
    val root = runCatching { ResponseJson.parseToJsonElement(rawJson).jsonObject }.getOrElse {
        throw IllegalArgumentException("Bad image response JSON")
    }
    val data = (root["data"] as? kotlinx.serialization.json.JsonArray)
        ?: throw IllegalArgumentException("Image response missing data[]")
    require(data.isNotEmpty() && data.size <= 10) { "Image data[] out of range" }
    return data.map { element ->
        val obj = (element as? JsonObject) ?: throw IllegalArgumentException("Bad image item")
        GeneratedImage(
            url = obj["url"]?.jsonPrimitive?.contentOrNull,
            b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull,
            revisedPrompt = obj["revised_prompt"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

/**
 * Audio 轉寫非串流回應解析：`{"text":"..."}` → 全文。空字串、缺欄、過長一律
 * IllegalArgumentException（同上，不靜默、不猜）。
 */
fun parseTranscriptionText(rawJson: String): String {
    val root = runCatching { ResponseJson.parseToJsonElement(rawJson).jsonObject }.getOrElse {
        throw IllegalArgumentException("Bad transcription JSON")
    }
    val text = root["text"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("Transcription missing text")
    require(text.isNotEmpty() && text.length <= MAX_TEXT_CHARS) { "Bad transcription text" }
    return text
}

const val MAX_TEXT_CHARS: Int = 1_000_000
const val MAX_IMAGE_URL_CHARS: Int = 20_000_000
const val MAX_IMAGE_B64_CHARS: Int = 20_000_000
const val MAX_FILE_ID_CHARS: Int = 256
const val MAX_FILE_DATA_CHARS: Int = 20_000_000
const val MAX_FILENAME_CHARS: Int = 512
const val MAX_AUDIO_B64_CHARS: Int = 20_000_000
const val MAX_TOOL_ID_CHARS: Int = 128
const val MAX_TOOL_ARGS_CHARS: Int = 100_000
const val MAX_TOOL_DESC_CHARS: Int = 10_000
const val MAX_TOOLS: Int = 128

private val IMAGE_DETAILS: Set<String> = setOf("auto", "low", "high")
private val AUDIO_FORMATS: Set<String> = setOf("wav", "mp3")
private val TOOL_NAME: Regex = Regex("[A-Za-z0-9_-]{1,64}")
