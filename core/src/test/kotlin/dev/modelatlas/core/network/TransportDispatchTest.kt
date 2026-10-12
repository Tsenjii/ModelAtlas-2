package dev.modelatlas.core.network

import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROV-010 repair 測試：transport dispatch 可測性。
 *
 * Spec：`PRODUCT_SPEC.md` §7（9 種 endpoint 型別）／§9（禁 giant switch、capability 獨立缺失）。
 *
 * - 已宣告型別全員可路由（不只 enum）：[TransportDispatch.declaredTypes] 即全集。
 * - 缺失各自獨立 Unsupported（型別＋原因自帶，不共用失敗分支、不拖累他型）。
 * - 先宣告先贏；未知 operation 字串不斷言崩潰；engine 路徑缺失轉安全分類。
 */
class TransportDispatchTest {

    private class ScriptedEndpointTransport(
        override val supportedTypes: Set<EndpointType>,
        private val script: List<StreamEvent> = listOf(StreamEvent.Finished),
    ) : EndpointTransport {
        var calls = 0
            private set

        override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> {
            calls++
            return flowOf(*script.toTypedArray())
        }
    }

    private fun target(operation: String): InferenceTarget = InferenceTarget(
        providerId = "p",
        accountId = "a",
        endpointId = "e",
        endpointUrl = "https://fixture.invalid/v1",
        modelId = "m",
        operation = operation,
    )

    @Test
    fun declaredTypesCoverEveryEndpointType() {
        val dispatch = TransportDispatch(emptyList())
        assertEquals(EndpointType.values().toSet(), dispatch.declaredTypes)
    }

    @Test
    fun chatCompletionsRoutesToOpenAiTransport() {
        val openAi = OpenAiTransport(CredentialAccess { _, _ -> "fixture" })
        val dispatch = TransportDispatch(listOf(openAi))
        val route = dispatch.route("CHAT_COMPLETIONS")
        assertTrue(route is TransportRoute.Routed)
        assertSame(openAi, (route as TransportRoute.Routed).transport)
    }

    @Test
    fun missingTypesAreIndependentlyUnsupported() {
        val dispatch = TransportDispatch(
            listOf(OpenAiTransport(CredentialAccess { _, _ -> "fixture" })),
        )
        val missing = listOf(
            EndpointType.RESPONSES,
            EndpointType.ANTHROPIC_MESSAGES,
            EndpointType.GEMINI_NATIVE,
            EndpointType.IMAGES_GENERATION,
            EndpointType.IMAGES_EDIT,
            EndpointType.AUDIO,
            EndpointType.EMBEDDINGS,
            EndpointType.RERANK,
            EndpointType.CUSTOM,
        )
        for (type in missing) {
            val route = dispatch.route(type.name)
            assertTrue("expected Unsupported for $type", route is TransportRoute.Unsupported)
            val unsupported = route as TransportRoute.Unsupported
            assertEquals(type, unsupported.endpointType)
            assertEquals(TransportDispatch.REASON_NO_TRANSPORT, unsupported.reason)
        }
    }

    @Test
    fun unknownOperationIsUnsupportedWithoutCrashing() {
        val dispatch = TransportDispatch(emptyList())
        val route = dispatch.route("NOT_A_REAL_OPERATION")
        assertTrue(route is TransportRoute.Unsupported)
        val unsupported = route as TransportRoute.Unsupported
        assertEquals(null, unsupported.endpointType)
        assertEquals(TransportDispatch.REASON_UNKNOWN_OPERATION, unsupported.reason)
    }

    @Test
    fun firstDeclarationWins() {
        val first = ScriptedEndpointTransport(setOf(EndpointType.AUDIO))
        val second = ScriptedEndpointTransport(setOf(EndpointType.AUDIO))
        val dispatch = TransportDispatch(listOf(first, second))
        val route = dispatch.route("AUDIO")
        assertTrue(route is TransportRoute.Routed)
        assertSame(first, (route as TransportRoute.Routed).transport)
    }

    @Test
    fun routedStreamDelegatesToDeclaringTransport() {
        val audio = ScriptedEndpointTransport(
            setOf(EndpointType.AUDIO),
            listOf(StreamEvent.ResponseModel("fixture-model"), StreamEvent.Finished),
        )
        val dispatch = TransportDispatch(listOf(audio))
        val events = runBlocking {
            dispatch.stream(target("AUDIO"), emptyList()).toList()
        }
        assertEquals(1, audio.calls)
        assertEquals(2, events.size)
        assertEquals(StreamEvent.ResponseModel("fixture-model"), events[0])
    }

    @Test
    fun unsupportedStreamFailsWithSafeOutcome() {
        val dispatch: InferenceTransport = TransportDispatch(emptyList())
        try {
            runBlocking { dispatch.stream(target("RERANK"), emptyList()).toList() }
            throw AssertionError("expected InferenceFailure for unsupported endpoint type")
        } catch (e: InferenceFailure) {
            assertEquals(Outcome.OTHER, e.outcome)
        }
    }
}
