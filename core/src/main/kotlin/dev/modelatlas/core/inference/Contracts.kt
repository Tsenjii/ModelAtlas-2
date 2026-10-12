package dev.modelatlas.core.inference

import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.multimodal.AudioOperationPayload
import dev.modelatlas.core.multimodal.ChatToolsPayload
import dev.modelatlas.core.multimodal.ContentBlock
import dev.modelatlas.core.multimodal.GeneratedImage
import dev.modelatlas.core.multimodal.ImageEditPayload
import dev.modelatlas.core.multimodal.ImageGenerationPayload
import dev.modelatlas.core.multimodal.MAX_AUDIO_B64_CHARS
import dev.modelatlas.core.multimodal.MAX_TEXT_CHARS
import dev.modelatlas.core.multimodal.MAX_TOOL_ARGS_CHARS
import dev.modelatlas.core.multimodal.MAX_TOOL_ID_CHARS
import dev.modelatlas.core.multimodal.TextPart
import dev.modelatlas.core.multimodal.ToolCallFragment
import dev.modelatlas.core.multimodal.ToolDefinition
import dev.modelatlas.core.multimodal.missingCapabilities
import dev.modelatlas.core.multimodal.requiredCapabilities
import dev.modelatlas.core.multimodal.requiredEndpointTypes
import dev.modelatlas.core.multimodal.validateToolChoice
import dev.modelatlas.core.multimodal.validateToolDefinitions
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Safe references only; never put a credential value or conversation content in telemetry. */
data class InferenceTarget(
    val providerId: String,
    val accountId: String,
    /** Null disables default Bearer auth; custom auth may still resolve its own safe refs. */
    val credentialId: String? = null,
    val endpointId: String,
    val endpointUrl: String,
    val modelId: String,
    val canonicalModelId: String? = null,
    val group: String? = null,
    val operation: String = "CHAT_COMPLETIONS",
    val maxOutputTokens: Int = 512,
    /** Custom auth is resolved only at the transport boundary; values never enter this type. */
    val authFields: List<InferenceAuthField> = emptyList(),
    /**
     * P4R PLAY-004 tool-calls operation payload（仍走 CHAT_COMPLETIONS，頂層 `tools`）。
     * 空即無 tools（向下相容）；有值時 transport 真送，不假裝。
     * 驗證單一來源見 `validateToolDefinitions`／`validateToolChoice`。
     */
    val tools: List<ToolDefinition> = emptyList(),
    /**
     * OpenAI `tool_choice` 字串形（`auto`／`none`／`required`）。
     * 有值時 transport 隨 `tools` 真送；無 tools 時設此值直接拋錯（fail-closed）。
     */
    val toolChoice: String? = null,
    /**
     * P4R PLAY-004 capability gate 來源：此 target 背後模型實際具備的 capability。
     * transport 在送出任何 request 前以 [missingCapabilitiesFor] 檢查，
     * 缺失直接拋錯（product-layer gate，不等 provider 400）。
     * 空集合即純文字模型（預設，向下相容；純文字訊息恆通過）。
     */
    val offeredCapabilities: Set<Capability> = emptySet(),
    /**
     * P4R PLAY-004 image／audio operation payload（與 [operation] 同路由）。
     * 同時最多一個非空；payload 存在時 operation 必須對應，否則建構即拋錯。
     * 反向（operation 為媒體型別但無 payload）允許建構：分派層走獨立
     * Unsupported（`TransportDispatch`），transport 層 fail-closed，不靜默。
     * （此單向校验是刻意相容：既有路由測試以無 payload 的媒體 operation
     * 驗證分派，不得因建構拋錯而破壞。）
     */
    val imageGeneration: ImageGenerationPayload? = null,
    val imageEdit: ImageEditPayload? = null,
    val audioOperation: AudioOperationPayload? = null,
    /**
     * Privileged caller's selected safe credential anchor for telemetry only (MODEL-008).
     * Custom-only auth can have no Bearer ref while still belonging to a selected key.
     * Never resolved by transport; null preserves legacy attribution via [credentialId].
     */
    val telemetryCredentialId: String? = null,
) {
    init {
        require(telemetryCredentialId == null || telemetryCredentialId.isNotBlank()) {
            "Telemetry credential anchor must be a nonblank safe reference"
        }
        require(maxOutputTokens > 0) { "Output token budget must be positive" }
        require(toolChoice == null || tools.isNotEmpty()) { "tool_choice without tools" }
        validateToolDefinitions(tools)
        validateToolChoice(toolChoice)
        val payloadCount = listOf(imageGeneration, imageEdit, audioOperation).count { it != null }
        require(payloadCount <= 1) { "At most one media payload" }
        if (imageGeneration != null) require(operation == EndpointType.IMAGES_GENERATION.name) {
            "imageGeneration payload needs IMAGES_GENERATION"
        }
        if (imageEdit != null) require(operation == EndpointType.IMAGES_EDIT.name) {
            "imageEdit payload needs IMAGES_EDIT"
        }
        if (audioOperation != null) require(operation == EndpointType.AUDIO.name) {
            "audioOperation payload needs AUDIO"
        }
    }
}

data class InferenceAuthField(
    val name: String,
    val credentialId: String,
    val query: Boolean = false,
    val overrideConfirmed: Boolean = false,
)

/**
 * P4R PLAY-004 multimodal message（TRACEABILITY PLAY-004，PRODUCT_SPEC §3）。
 *
 * - `blocks` 空即 legacy：wire 走 `{"content": String}`，與舊行為完全一致；
 *   既有 `PromptMessage("user", text)` 呼叫保持編譯（首二參數不變，blocks 預設空）。
 * - `blocks` 非空即 multimodal：transport 走 `{"content": [...]}` content array 真送
 *   （text / image_url / file / input_audio；tool 另走 `tool_calls` / `role=tool`）。
 * - `content` 在 multimodal 時為文字 fallback（顯示/相容用），wire 以 blocks 為準。
 * - 對話內容永不進 telemetry（`InferenceEngine` 已保證）。
 */
@Serializable
data class PromptMessage(val role: String, val content: String, val blocks: List<ContentBlock> = emptyList()) {
    /** blocks 便利建構：content 由 TextPart 拼接為 fallback。 */
    constructor(role: String, blocks: List<ContentBlock>) : this(
        role = role,
        content = blocks.filterIsInstance<TextPart>().joinToString(separator = "") { it.text },
        blocks = blocks.toList(),
    )

    fun hasMultimodal(): Boolean = blocks.isNotEmpty()

    /** 此訊息所需 capability（gating 用；純文字回空集合）。 */
    fun requiredCapabilities(): Set<Capability> = blocks.requiredCapabilities()

    /** 此訊息相對 offered 的缺失 capability（空即 gate 通過；元素各自獨立）。 */
    fun missingCapabilities(offered: Set<Capability>): Set<Capability> = blocks.missingCapabilities(offered)
}

/** 多訊息所需 capability 聯集（gating 用）。 */
fun List<PromptMessage>.requiredCapabilities(): Set<Capability> =
    flatMapTo(LinkedHashSet()) { it.requiredCapabilities() }

fun List<PromptMessage>.missingCapabilities(offered: Set<Capability>): Set<Capability> =
    requiredCapabilities() - offered

/** 多訊息所需 endpoint 型別（blocks 皆走 CHAT_COMPLETIONS；空訊息回空集合）。 */
fun List<PromptMessage>.requiredEndpointTypes(): Set<EndpointType> =
    flatMapTo(LinkedHashSet()) { it.blocks.requiredEndpointTypes() }

@Serializable
enum class TelemetrySource { PLAYGROUND, MANUAL_TEST, BACKGROUND_PROBE, CRAWLER_VERIFY, ASSISTANT_TEST, COMPARE_LIVE, UNKNOWN }
@Serializable
enum class Outcome { RUNNING, SUCCESS, CANCELLED, AUTH, RATE_LIMIT, TIMEOUT, SERVER, NETWORK, PARSER, MODEL_UNAVAILABLE, INTERRUPTED, CREDENTIAL_SCOPE, OTHER }

@Serializable
data class Usage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val estimated: Boolean = false,
)

sealed interface StreamEvent {
    /**
     * 本機 sampleId 由 InferenceEngine 填入，不是 provider 的 HTTP request id 或 credential。
     * 僅供精確關聯 durable sample，不代表請求成功；collect 完成後仍須核對 final outcome / scope。
     */
    data class RequestSent(
        val epochMillis: Long,
        val monotonicMillis: Long,
        val sampleId: String? = null,
    ) : StreamEvent
    data class Headers(val status: Int, val rateLimits: Map<String, String>, val observedMonotonicMs: Long? = null) : StreamEvent
    data class Delta(val text: String, val reasoning: Boolean = false, val observedMonotonicMs: Long? = null) : StreamEvent
    data class TokenUsage(val usage: Usage) : StreamEvent
    data class ResponseModel(val id: String) : StreamEvent
    /**
     * P4R PLAY-004 入方向 tool call 碎片（`delta.tool_calls[]` 一項的一次 SSE 切片）。
     * `id`／`name` 只在首片出現，後續片僅帶 `argumentsFragment`；
     * 呼叫端以 [toFragment]＋`assembleToolCalls` 組成 `ToolCallPart`（round-trip 後半）。
     * 內容永不進 telemetry（`InferenceEngine` 只轉發，不持久化事件）。
     */
    data class ToolCallDelta(
        val callIndex: Int,
        val id: String?,
        val name: String?,
        val argumentsFragment: String,
        val observedMonotonicMs: Long? = null,
    ) : StreamEvent {
        init {
            require(callIndex >= 0) { "Bad tool call index" }
            if (id != null) require(id.length <= MAX_TOOL_ID_CHARS) { "Tool call id too large" }
            require(argumentsFragment.length <= MAX_TOOL_ARGS_CHARS) { "Tool arguments too large" }
        }
    }
    /**
     * P4R PLAY-004 response-side audio 輸出碎片（`delta.audio`：transcript＋base64 音訊）。
     * 至少一邊非空；同樣只轉發、不進 telemetry。
     */
    data class AudioDelta(
        val transcriptFragment: String = "",
        val audioBase64Fragment: String = "",
        val observedMonotonicMs: Long? = null,
    ) : StreamEvent {
        init {
            require(transcriptFragment.isNotEmpty() || audioBase64Fragment.isNotEmpty()) {
                "Empty audio delta"
            }
            require(transcriptFragment.length <= MAX_TEXT_CHARS) { "Audio transcript too large" }
            require(audioBase64Fragment.length <= MAX_AUDIO_B64_CHARS) { "Audio data too large" }
        }
    }
    /** P4R PLAY-004 image generation／edit 非串流結果（`data[]` 全量；無 bytes 落 telemetry）。 */
    data class GeneratedImages(val images: List<GeneratedImage>) : StreamEvent {
        init {
            require(images.isNotEmpty()) { "Empty image result" }
        }
    }
    /** P4R PLAY-004 audio transcription 非串流結果（全文；只轉發、不進 telemetry）。 */
    data class Transcription(val text: String, val observedMonotonicMs: Long? = null) : StreamEvent {
        init {
            require(text.isNotEmpty() && text.length <= MAX_TEXT_CHARS) { "Bad transcription" }
        }
    }
    data object Finished : StreamEvent
}

/** [StreamEvent.ToolCallDelta] → 組裝用碎片（見 `assembleToolCalls`）。 */
fun StreamEvent.ToolCallDelta.toFragment(): ToolCallFragment =
    ToolCallFragment(callIndex, id, name, argumentsFragment)

/**
 * P4R PLAY-004 capability gate（真強制點共用；transport 在送 request 前呼叫）。
 * 訊息 blocks 所需＋（有 tools 時）`TOOL_CALLING`＋（有媒體 payload 時）對應 capability，
 * 減去 [InferenceTarget.offeredCapabilities]。
 * 空即 gate 通過；非空時呼叫端必須 fail-closed（不送 request，直接拋錯）。
 * 缺失元素各自獨立（Set 元素即獨立 Unsupported 依據，不共用失敗分支）。
 */
fun InferenceTarget.missingCapabilitiesFor(messages: List<PromptMessage>): Set<Capability> {
    val need = messages.requiredCapabilities().toMutableSet()
    if (tools.isNotEmpty()) need += Capability.TOOL_CALLING
    if (imageGeneration != null) need += Capability.IMAGE_GENERATION
    if (imageEdit != null) need += Capability.IMAGE_EDITING
    if (audioOperation != null) need += Capability.AUDIO_INPUT
    return need - offeredCapabilities
}

/**
 * ChatTools 歸位：`InferenceTarget.tools`／[InferenceTarget.toolChoice] 即
 * `ChatToolsPayload` 的唯一正本；空 tools 回 null（wire 省略 `tools` 鍵，向下相容）。
 * `OpenAiTransport` 以此產生頂層 `tools`＋`tool_choice`，不再直寫欄位。
 */
fun InferenceTarget.chatToolsPayload(): ChatToolsPayload? =
    if (tools.isEmpty()) null else ChatToolsPayload(tools, toolChoice)

class InferenceFailure(val outcome: Outcome, val status: Int? = null) : Exception(outcome.name)

interface InferenceTransport {
    fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent>
}

/** Implemented only by the privileged local layer. Values must never be logged. */
fun interface CredentialAccess {
    fun bearerToken(credentialId: String, origin: String): String
}

@Serializable
data class PerformanceSample(
    val id: String,
    val providerId: String,
    val accountId: String,
    val safeCredentialId: String,
    val endpointId: String,
    val providerModelId: String,
    val canonicalModelId: String?,
    val group: String?,
    val operation: String,
    val source: TelemetrySource,
    val requestStartedAt: Long,
    val firstTokenAt: Long? = null,
    val completedAt: Long? = null,
    val streaming: Boolean = true,
    val ttftMs: Long? = null,
    val ttfbMs: Long? = null,
    val totalLatencyMs: Long? = null,
    val tps: Double? = null,
    val usage: Usage = Usage(),
    val httpStatus: Int? = null,
    val outcome: Outcome = Outcome.RUNNING,
    val retryCount: Int = 0,
    val responseModelId: String? = null,
    val rateLimitHeaders: Map<String, String> = emptyMap(),
    val priceState: String = "UNKNOWN",
    val quotaState: String = "UNKNOWN",
    val pricingSnapshotId: String? = null,
    val quotaSnapshotBeforeId: String? = null,
    val quotaSnapshotAfterId: String? = null,
    val actualCost: String? = null,
    val estimatedCost: String? = null,
)

interface TelemetrySink {
    suspend fun save(sample: PerformanceSample)
}

interface Clock {
    fun epochMillis(): Long
    fun monotonicMillis(): Long
}

object SystemClock : Clock {
    override fun epochMillis() = System.currentTimeMillis()
    override fun monotonicMillis() = System.nanoTime() / 1_000_000
}
