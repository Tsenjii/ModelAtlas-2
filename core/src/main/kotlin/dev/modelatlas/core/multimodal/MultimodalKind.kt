package dev.modelatlas.core.multimodal

import dev.modelatlas.core.domain.EndpointType

/**
 * 多模態種類最小契約（repair 回退後的僅存部分）。
 *
 * 完整 multimodal payload（content blocks／transport／response 解析）屬 PLAY-004
 * 未完成範圍，已整批回退；本檔只保留 [TransportDispatch] 路由所需的種類→endpoint
 * 映射，不承載任何 inference 語意，不得擴充為 payload 實作。
 */
enum class MultimodalKind(val endpointType: EndpointType) {
    CHAT_TEXT(EndpointType.CHAT_COMPLETIONS),
    RESPONSES(EndpointType.RESPONSES),
    ANTHROPIC_MESSAGES(EndpointType.ANTHROPIC_MESSAGES),
    GEMINI_NATIVE(EndpointType.GEMINI_NATIVE),
    IMAGE_GENERATION(EndpointType.IMAGES_GENERATION),
    IMAGE_EDIT(EndpointType.IMAGES_EDIT),
    AUDIO(EndpointType.AUDIO),
    EMBEDDINGS(EndpointType.EMBEDDINGS),
    RERANK(EndpointType.RERANK),
}

/** operation 字串→種類；未知回 null（呼叫端轉獨立 Unsupported，不崩潰）。 */
private val OPERATION_TO_KIND: Map<String, MultimodalKind> = mapOf(
    "CHAT_COMPLETIONS" to MultimodalKind.CHAT_TEXT,
    "RESPONSES" to MultimodalKind.RESPONSES,
    "ANTHROPIC_MESSAGES" to MultimodalKind.ANTHROPIC_MESSAGES,
    "GEMINI_NATIVE" to MultimodalKind.GEMINI_NATIVE,
    "IMAGES_GENERATION" to MultimodalKind.IMAGE_GENERATION,
    "IMAGES_EDIT" to MultimodalKind.IMAGE_EDIT,
    "AUDIO" to MultimodalKind.AUDIO,
    "EMBEDDINGS" to MultimodalKind.EMBEDDINGS,
    "RERANK" to MultimodalKind.RERANK,
)

fun multimodalKindForOperation(operation: String): MultimodalKind? =
    OPERATION_TO_KIND[operation.trim().uppercase()]

/** operation 字串→endpoint 型別；未知回 null（呼叫端轉獨立 Unsupported）。 */
fun endpointTypeForOperation(operation: String): EndpointType? =
    multimodalKindForOperation(operation)?.endpointType
