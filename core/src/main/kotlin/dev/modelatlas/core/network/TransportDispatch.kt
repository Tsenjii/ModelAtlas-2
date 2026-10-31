package dev.modelatlas.core.network

import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.multimodal.MultimodalKind
import kotlinx.coroutines.flow.Flow

/**
 * `EndpointType` → transport 的可執行分派（PROV-010 repair）。
 *
 * Spec：`PRODUCT_SPEC.md` §7（EndpointProfile 必須承載 9 種型別＋Custom）／
 * §9（禁止 giant switch；adapter capability 獨立缺失）；
 * `PROVIDER_ONBOARDING_CONTRACT.md` §2.1（`endpointType ∈ CHAT_COMPLETIONS／RESPONSES／
 * ANTHROPIC_MESSAGES／GEMINI_NATIVE／IMAGES_GENERATION／IMAGES_EDIT／AUDIO／
 * EMBEDDINGS／RERANK／CUSTOM`）。
 * TRACEABILITY：PROV-010。
 *
 * - 已宣告可執行不只 enum：[declaredTypes] 即 `EndpointType` 全員，每一型別都是
 *   分派對象；路由走 map 查表，不寫 when／switch（§9 禁 giant switch）。
 * - 缺失獨立 Unsupported：未註冊 transport 的型別各回自己的
 *   [TransportRoute.Unsupported]（帶型別＋原因），不互相拖累、不共用單一失敗分支；
 *   未知 operation 字串同樣獨立 Unsupported（不斷言、不崩潰）。
 * - 本分派本身即 [InferenceTransport]，可直接交給 `InferenceEngine` 當唯一 transport：
 *   命中走子 transport（含完整 telemetry），缺失拋 `InferenceFailure(Outcome.OTHER)`
 *   （engine 照常落 telemetry 後分類，不靜默吞掉）。
 * - Secret 邊界：分派層不碰憑證，憑證解析只在子 transport 的 HTTP 邊界發生。
 */
interface EndpointTransport : InferenceTransport {
    /** 本 transport 宣告可執行的 endpoint 型別（capability 宣告；缺席即不支援）。 */
    val supportedTypes: Set<EndpointType>
}

/** 分派決策：已路由／該型別獨立不支援。 */
sealed interface TransportRoute {
    data class Routed(val transport: InferenceTransport) : TransportRoute

    data class Unsupported(val endpointType: EndpointType?, val reason: String) : TransportRoute
}

class TransportDispatch(transports: List<EndpointTransport>) : InferenceTransport {
    private val table: Map<EndpointType, InferenceTransport> = buildTable(transports)

    /** 已宣告型別集合：`EndpointType` 全員皆為分派對象，不只 enum 值。 */
    val declaredTypes: Set<EndpointType> = EndpointType.values().toSet()

    /**
     * 純決策函式（無 IO、可單測）：operation 字串→[TransportRoute]。
     * `InferenceTarget.operation` 沿用 `EndpointType.name`（預設 `CHAT_COMPLETIONS`）。
     * 字串→型別直接對 `EndpointType` 全員解析（含 CUSTOM；單一查表、禁 when／switch；
     * 未知回獨立 Unsupported，不崩）。
     */
    fun route(operation: String): TransportRoute {
        val type = runCatching { EndpointType.valueOf(operation.trim().uppercase()) }.getOrNull()
            ?: return TransportRoute.Unsupported(null, REASON_UNKNOWN_OPERATION)
        val transport = table[type]
            ?: return TransportRoute.Unsupported(type, REASON_NO_TRANSPORT)
        return TransportRoute.Routed(transport)
    }

    /**
     * PLAY-004 modality kind→路由（與 [route] 同一查表；image／audio 缺 transport 時
     * 各回自己的獨立 Unsupported，不互相拖累）。
     */
    fun route(kind: MultimodalKind): TransportRoute = route(kind.endpointType.name)

    override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> {
        KiloFactoryPolicy.requireScope(target)
        return when (val route = route(target.operation)) {
            is TransportRoute.Routed -> route.transport.stream(target, messages)
            is TransportRoute.Unsupported -> throw InferenceFailure(Outcome.OTHER)
        }
    }

    companion object {
        const val REASON_UNKNOWN_OPERATION = "unknown-operation"
        const val REASON_NO_TRANSPORT = "no-transport-registered"

        /** 先宣告先贏（putIfAbsent），順序確定、可測；重複宣告不覆蓋。 */
        private fun buildTable(transports: List<EndpointTransport>): Map<EndpointType, InferenceTransport> {
            val table = LinkedHashMap<EndpointType, InferenceTransport>()
            for (transport in transports) {
                for (type in transport.supportedTypes) {
                    table.putIfAbsent(type, transport)
                }
            }
            return table
        }
    }
}
