package dev.modelatlas.core.multimodal

import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySink
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import dev.modelatlas.core.inference.chatToolsPayload
import dev.modelatlas.core.inference.missingCapabilitiesFor
import dev.modelatlas.core.inference.requiredCapabilities
import dev.modelatlas.core.inference.requiredEndpointTypes
import dev.modelatlas.core.inference.toFragment
import dev.modelatlas.core.network.OpenAiTransport
import dev.modelatlas.core.network.TransportDispatch
import dev.modelatlas.core.network.TransportRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * P4R PLAY-004 單一連貫測試（TRACEABILITY PLAY-004，PRODUCT_SPEC §3／§7）。
 *
 * 覆蓋（每項獨立 @Test，缺失各自獨立斷言，不共用失敗分支）：
 * 1. blocks 形狀／序列化 2. PromptMessage 向下相容 3. capability gating
 * 4. InferenceTarget 校验 5. tool_calls 組裝 6. wire builders
 * 7. transport 入方向解析（tool_calls／audio） 8. response 解析（image／transcription）
 * 9. InferenceEngine 新分支轉發＋TTFT 10. 缺失獨立 Unsupported。
 *
 * Fixture 合成值＋loopback MockWebServer；無真 key、無產品 mock 資料。
 */
class MultimodalP4RTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun loopbackUrl(path: String = "/v1/chat/completions"): String =
        "http://127.0.0.1:${server.port}$path"

    private fun chatTarget(
        offered: Set<Capability> = setOf(
            Capability.VISION,
            Capability.FILES,
            Capability.AUDIO_INPUT,
            Capability.TOOL_CALLING,
        ),
        operation: String = "CHAT_COMPLETIONS",
    ): InferenceTarget = InferenceTarget(
        providerId = "p-fixture",
        accountId = "a-fixture",
        credentialId = "safe-credential-fixture",
        endpointId = "e-fixture",
        endpointUrl = loopbackUrl(),
        modelId = "fixture-model",
        operation = operation,
        offeredCapabilities = offered,
    )

    private fun chatTransport() =
        OpenAiTransport({ _, _ -> "fixture-bearer-token-do-not-log" }, allowLoopbackHttpForTests = true)

    private fun sseDone(vararg payloads: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString(separator = "") { "data: $it\n\n" } + "data: [DONE]\n\n")

    // ---- 1. blocks 形狀／序列化 ----

    @Test
    fun textBlockRoundTrips() {
        val block: ContentBlock = TextPart("hello-fixture")
        assertEquals(block, json.decodeFromString<ContentBlock>(json.encodeToString(block)))
    }

    @Test
    fun imageUrlBlockRoundTrips() {
        val block: ContentBlock = ImageUrlPart("https://fixture.invalid/img.png", "low")
        assertEquals(block, json.decodeFromString<ContentBlock>(json.encodeToString(block)))
    }

    @Test
    fun imageUrlRejectsBadDetail() {
        try {
            ImageUrlPart("https://fixture.invalid/img.png", "ultra")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun fileRefNeedsExactlyOneSource() {
        FileRefPart(fileId = "file-fixture-1", filename = "doc.pdf")
        try {
            FileRefPart(fileId = "a", fileDataUrl = "data:application/pdf;base64,QQ==")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
        try {
            FileRefPart(filename = "doc.pdf")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun audioBlockRoundTrips() {
        val block: ContentBlock = AudioInputPart("RkFLRV9BVURJTw==", "wav")
        assertEquals(block, json.decodeFromString<ContentBlock>(json.encodeToString(block)))
    }

    @Test
    fun audioRejectsUnknownFormat() {
        try {
            AudioInputPart("RkFL", "ogg")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun toolBlocksRoundTrip() {
        val call: ContentBlock = ToolCallPart("call-1", "get_weather", """{"city":"Taipei"}""")
        val result: ContentBlock = ToolResultPart("call-1", "sunny-fixture")
        assertEquals(call, json.decodeFromString<ContentBlock>(json.encodeToString(call)))
        assertEquals(result, json.decodeFromString<ContentBlock>(json.encodeToString(result)))
    }

    @Test
    fun toolCallRejectsNonObjectArguments() {
        try {
            ToolCallPart("call-1", "get_weather", """["not","object"]""")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    // ---- 2. PromptMessage 向下相容 ----

    @Test
    fun legacyPromptMessageHasEmptyBlocks() {
        val message = PromptMessage("user", "hi-fixture")
        assertEquals(emptyList<ContentBlock>(), message.blocks)
        assertFalse(message.hasMultimodal())
        assertEquals(emptySet<Capability>(), message.requiredCapabilities())
    }

    @Test
    fun legacyJsonWithoutBlocksStillDecodes() {
        val decoded = json.decodeFromString<PromptMessage>("""{"role":"user","content":"hi-fixture"}""")
        assertEquals("user", decoded.role)
        assertEquals("hi-fixture", decoded.content)
        assertEquals(emptyList<ContentBlock>(), decoded.blocks)
    }

    @Test
    fun blocksConstructorBuildsTextFallback() {
        val message = PromptMessage("user", listOf(TextPart("a-fixture"), TextPart("b-fixture")))
        assertEquals("a-fixtureb-fixture", message.content)
        assertTrue(message.hasMultimodal())
    }

    // ---- 3. capability gating（缺失各自獨立） ----

    @Test
    fun visionBlocksRequireVisionOnly() {
        val message = PromptMessage("user", listOf(TextPart("hi"), ImageUrlPart("https://fixture.invalid/a.png")))
        assertEquals(setOf(Capability.VISION), message.requiredCapabilities())
        assertEquals(setOf(Capability.VISION), message.missingCapabilities(emptySet()))
        assertEquals(emptySet<Capability>(), message.missingCapabilities(setOf(Capability.VISION)))
    }

    @Test
    fun fileAndAudioAndToolMissingAreIndependent() {
        val fileMsg = PromptMessage("user", listOf(FileRefPart(fileId = "file-fixture-1")))
        val audioMsg = PromptMessage("user", listOf(AudioInputPart("RkFL", "mp3")))
        val toolMsg = PromptMessage("assistant", listOf(ToolCallPart("c1", "do_work", """{"x":1}""")))
        assertEquals(setOf(Capability.FILES), fileMsg.missingCapabilities(emptySet()))
        assertEquals(setOf(Capability.AUDIO_INPUT), audioMsg.missingCapabilities(emptySet()))
        assertEquals(setOf(Capability.TOOL_CALLING), toolMsg.missingCapabilities(emptySet()))
        // 各自獨立：補 vision 不影響 files／audio／tool。
        val offered = setOf(Capability.VISION)
        assertEquals(setOf(Capability.FILES), fileMsg.missingCapabilities(offered))
        assertEquals(setOf(Capability.AUDIO_INPUT), audioMsg.missingCapabilities(offered))
        assertEquals(setOf(Capability.TOOL_CALLING), toolMsg.missingCapabilities(offered))
    }

    @Test
    fun targetToolsAddToolCallingRequirement() {
        val target = chatTarget(offered = emptySet()).copy(
            tools = listOf(ToolDefinition("get_weather")),
        )
        val missing = target.missingCapabilitiesFor(listOf(PromptMessage("user", "hi")))
        assertEquals(setOf(Capability.TOOL_CALLING), missing)
    }

    @Test
    fun pureTextPassesEmptyOffered() {
        val target = chatTarget(offered = emptySet())
        assertEquals(emptySet<Capability>(), target.missingCapabilitiesFor(listOf(PromptMessage("user", "hi"))))
    }

    // ---- 4. InferenceTarget 校验 ----

    @Test
    fun toolChoiceWithoutToolsIsRejected() {
        try {
            chatTarget().copy(toolChoice = "auto")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun duplicateToolNamesAreRejected() {
        try {
            chatTarget().copy(tools = listOf(ToolDefinition("dup"), ToolDefinition("dup")))
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun badToolChoiceIsRejected() {
        try {
            chatTarget().copy(tools = listOf(ToolDefinition("ok")), toolChoice = "sometimes")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun chatToolsPayloadIsNullWhenNoTools() {
        assertNull(chatTarget().chatToolsPayload())
        val payload = chatTarget().copy(tools = listOf(ToolDefinition("get_weather")), toolChoice = "auto").chatToolsPayload()
        assertNotNull(payload)
        assertEquals("auto", payload!!.toolChoice)
    }

    @Test
    fun mediaPayloadOperationMismatchIsRejected() {
        try {
            chatTarget(operation = "CHAT_COMPLETIONS").copy(imageGeneration = ImageGenerationPayload("a-fixture"))
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
        try {
            chatTarget(operation = "AUDIO").copy(
                imageGeneration = ImageGenerationPayload("a"),
                audioOperation = AudioOperationPayload("data:audio/wav;base64,QQ=="),
            )
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun mediaOperationWithoutPayloadStaysConstructibleForDispatch() {
        // 相容既有路由測試：operation 為媒體型別但無 payload 時允許建構，
        // 分派層回獨立 Unsupported（見下方），不因建構拋錯而破壞。
        val target = chatTarget(operation = "AUDIO")
        assertNull(target.audioOperation)
    }

    // ---- 5. tool_calls 組裝 ----

    @Test
    fun fragmentsAssembleInIndexOrder() {
        val parts = listOf(
            ToolCallFragment(0, "call-0", "first_tool", """{"a":1"""),
            ToolCallFragment(0, null, null, """}"""),
            ToolCallFragment(1, "call-1", "second_tool", """{"b":2}"""),
        ).assembleToolCalls()
        assertEquals(2, parts.size)
        assertEquals("call-0", parts[0].id)
        assertEquals("first_tool", parts[0].name)
        assertEquals("""{"a":1}""", parts[0].argumentsJson)
        assertEquals("second_tool", parts[1].name)
    }

    @Test
    fun assembleRequiresIdAndName() {
        try {
            listOf(ToolCallFragment(0, null, "some_tool", """{"a":1}""")).assembleToolCalls()
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
        try {
            listOf(ToolCallFragment(0, "call-0", null, """{"a":1}""")).assembleToolCalls()
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun emptyFragmentsAssembleToEmpty() {
        assertEquals(emptyList<ToolCallPart>(), emptyList<ToolCallFragment>().assembleToolCalls())
    }

    @Test
    fun toolDeltaConvertsToFragment() {
        val delta = StreamEvent.ToolCallDelta(2, "call-2", "some_tool", """{"k":"v"}""")
        assertEquals(ToolCallFragment(2, "call-2", "some_tool", """{"k":"v"}"""), delta.toFragment())
    }

    // ---- 6. wire builders ----

    @Test
    fun legacyMessageWiresAsStringContent() {
        val wire = chatTransport().buildMessageJson(PromptMessage("user", "hi-fixture"))
        assertEquals("hi-fixture", wire["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun multimodalMessageWiresAsContentArray() {
        val wire = chatTransport().buildMessageJson(
            PromptMessage("user", listOf(TextPart("hi-fixture"), ImageUrlPart("https://fixture.invalid/a.png"))),
        )
        val parts = wire["content"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolResultRequiresToolRoleAndSingleBlock() {
        val wire = chatTransport().buildMessageJson(
            PromptMessage("tool", listOf(ToolResultPart("call-1", "ok-fixture"))),
        )
        assertEquals("call-1", wire["tool_call_id"]!!.jsonPrimitive.content)
        try {
            chatTransport().buildMessageJson(
                PromptMessage("user", listOf(ToolResultPart("call-1", "ok-fixture"))),
            )
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun assistantToolCallsWireWithFallbackAndCalls() {
        val wire = chatTransport().buildMessageJson(
            PromptMessage("assistant", listOf(TextPart("working-fixture"), ToolCallPart("c1", "do_work", """{"x":1}"""))),
        )
        assertEquals("working-fixture", wire["content"]!!.jsonPrimitive.content)
        assertEquals(1, wire["tool_calls"]!!.jsonArray.size)
    }

    // ---- 7. transport 入方向解析（tool_calls／audio） ----

    @Test
    fun sseToolCallsParseToDeltas() {
        // 同 index 續接：首片帶 id／name＋首段 arguments，續片僅帶 arguments；
        // 兩片拼接後 JSON 完整才算一通呼叫（不放水：不以單片不完整即算）。
        val first = chatTransport().parse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"get_weather","arguments":"{\"city\":\""}}]}}]}""",
        ).filterIsInstance<StreamEvent.ToolCallDelta>()
        val second = chatTransport().parse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"Taipei\"}"}}]}}]}""",
        ).filterIsInstance<StreamEvent.ToolCallDelta>()
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(0, first[0].callIndex)
        assertEquals("call-1", first[0].id)
        assertEquals("get_weather", first[0].name)
        val assembled = (first + second).map { it.toFragment() }.assembleToolCalls()
        assertEquals(1, assembled.size)
        assertEquals("call-1", assembled[0].id)
        assertEquals("get_weather", assembled[0].name)
        assertEquals("""{"city":"Taipei"}""", assembled[0].argumentsJson)
    }

    @Test
    fun sseNonArrayToolCallsIsParserFailure() {
        try {
            chatTransport().parse("""{"choices":[{"index":0,"delta":{"tool_calls":{"index":0}}}]}""")
            fail("expected parser failure")
        } catch (e: InferenceFailure) {
            assertEquals(Outcome.PARSER, e.outcome)
        }
    }

    @Test
    fun sseLegacyFunctionCallParsesToDelta() {
        val events = chatTransport().parse(
            """{"choices":[{"index":0,"delta":{"function_call":{"name":"old_tool","arguments":"{\"a\":1}"}}}]}""",
        ).filterIsInstance<StreamEvent.ToolCallDelta>()
        assertEquals(1, events.size)
        assertEquals("old_tool", events[0].name)
    }

    @Test
    fun sseAudioDeltaParses() {
        val events = chatTransport().parse(
            """{"choices":[{"index":0,"delta":{"audio":{"transcript":"hello-fixture","data":"RkFL"}}}]}""",
        ).filterIsInstance<StreamEvent.AudioDelta>()
        assertEquals(1, events.size)
        assertEquals("hello-fixture", events[0].transcriptFragment)
    }

    @Test
    fun sseEmptyAudioIsIgnored() {
        val events = chatTransport().parse(
            """{"choices":[{"index":0,"delta":{"audio":{"transcript":"","data":""}}}]}""",
        )
        assertTrue(events.none { it is StreamEvent.AudioDelta })
    }

    @Test
    fun audioDeltaRequiresNonEmpty() {
        try {
            StreamEvent.AudioDelta("", "")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    // ---- 8. response 解析（image／transcription） ----

    @Test
    fun imageDataArrayParsesUrlAndB64() {
        val images = parseImageDataArray(
            """{"data":[{"url":"https://fixture.invalid/out.png","revised_prompt":"rev-fixture"},{"b64_json":"RkFL"}]}""",
        )
        assertEquals(2, images.size)
        assertEquals("https://fixture.invalid/out.png", images[0].url)
        assertEquals("rev-fixture", images[0].revisedPrompt)
        assertEquals("RkFL", images[1].b64Json)
    }

    @Test
    fun imageDataArrayRejectsEmptyAndMissing() {
        try {
            parseImageDataArray("""{"data":[]}""")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
        try {
            parseImageDataArray("""{"nope":1}""")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun transcriptionParsesText() {
        assertEquals("hello-fixture", parseTranscriptionText("""{"text":"hello-fixture"}"""))
        try {
            parseTranscriptionText("""{"text":""}""")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
        try {
            parseTranscriptionText("""{"nope":1}""")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun generatedImagesRequireNonEmpty() {
        try {
            StreamEvent.GeneratedImages(emptyList())
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    // ---- 9. InferenceEngine 新分支轉發＋TTFT ----

    private class RecordingSink : TelemetrySink {
        val saves = mutableListOf<PerformanceSample>()
        override suspend fun save(sample: PerformanceSample) {
            saves += sample
        }
    }

    private fun scriptedTransport(script: List<StreamEvent>): dev.modelatlas.core.inference.InferenceTransport =
        object : dev.modelatlas.core.inference.InferenceTransport {
            override fun stream(
                target: InferenceTarget,
                messages: List<PromptMessage>,
            ): Flow<StreamEvent> = flow {
                for (event in script) emit(event)
            }
        }

    private fun engineTarget(): InferenceTarget = InferenceTarget(
        providerId = "p-fixture",
        accountId = "a-fixture",
        credentialId = "safe-credential-fixture",
        endpointId = "e-fixture",
        endpointUrl = "https://api.fixture.invalid/v1/chat/completions",
        modelId = "fixture-model",
    )

    @Test
    fun engineForwardsToolAndAudioDeltasAndStartsTtft() = runTest {
        val sink = RecordingSink()
        val script = listOf(
            StreamEvent.Headers(200, emptyMap()),
            StreamEvent.ToolCallDelta(0, "call-1", "get_weather", """{"city":""""),
            StreamEvent.ToolCallDelta(0, null, null, """"Taipei"}"""),
            StreamEvent.AudioDelta("hello-fixture", ""),
            StreamEvent.TokenUsage(Usage(inputTokens = 3L, outputTokens = 4L)),
            StreamEvent.Finished,
        )
        val events = InferenceEngine(scriptedTransport(script), sink)
            .execute(engineTarget(), listOf(PromptMessage("user", "hi"))).toList()
        assertEquals(script, events)
        val sample = sink.saves.last()
        assertEquals(Outcome.SUCCESS, sample.outcome)
        assertNotNull(sample.ttftMs)
        assertNotNull(sample.firstTokenAt)
        // tool／audio 內容不污染 usage 以外的 telemetry（無 prompt／secret 字樣可斷言處即 sample 本身）。
        assertEquals(3L, sample.usage.inputTokens)
    }

    @Test
    fun engineForwardsImageAndTranscriptionResults() = runTest {
        val sink = RecordingSink()
        val script = listOf(
            StreamEvent.Headers(200, emptyMap()),
            StreamEvent.GeneratedImages(listOf(GeneratedImage(url = "https://fixture.invalid/out.png"))),
            StreamEvent.Transcription("hello-fixture"),
            StreamEvent.Finished,
        )
        val events = InferenceEngine(scriptedTransport(script), sink)
            .execute(engineTarget(), listOf(PromptMessage("user", "hi"))).toList()
        assertEquals(script, events)
        assertEquals(Outcome.SUCCESS, sink.saves.last().outcome)
        assertNotNull(sink.saves.last().ttftMs)
    }

    @Test
    fun engineEmptyToolFragmentDoesNotStartTtft() = runTest {
        val sink = RecordingSink()
        val script = listOf(
            StreamEvent.Headers(200, emptyMap()),
            StreamEvent.ToolCallDelta(0, null, null, ""),
            StreamEvent.Finished,
        )
        InferenceEngine(scriptedTransport(script), sink)
            .execute(engineTarget(), listOf(PromptMessage("user", "hi"))).toList()
        // 空碎片非生成內容：TTFT 維持 unavailable（null），與空 Delta 語意一致。
        assertNull(sink.saves.last().ttftMs)
        assertNull(sink.saves.last().firstTokenAt)
    }

    // ---- 10. 缺失獨立 Unsupported＋gating 零 request ----

    @Test
    fun missingTransportsAreIndependentlyUnsupported() {
        val dispatch = TransportDispatch(listOf(chatTransport()))
        val missing = listOf(
            EndpointType.IMAGES_GENERATION,
            EndpointType.IMAGES_EDIT,
            EndpointType.AUDIO,
            EndpointType.RESPONSES,
        )
        for (type in missing) {
            val route = dispatch.route(type.name)
            assertTrue("expected Unsupported for $type", route is TransportRoute.Unsupported)
            val unsupported = route as TransportRoute.Unsupported
            assertEquals(type, unsupported.endpointType)
            assertEquals(TransportDispatch.REASON_NO_TRANSPORT, unsupported.reason)
        }
        // 已註冊型別獨立路由成功，不被缺失拖累。
        val routed = dispatch.route("CHAT_COMPLETIONS")
        assertTrue(routed is TransportRoute.Routed)
    }

    @Test
    fun unknownOperationIsIndependentUnsupported() {
        val dispatch = TransportDispatch(listOf(chatTransport()))
        val route = dispatch.route("NOT_A_REAL_OPERATION")
        assertTrue(route is TransportRoute.Unsupported)
        assertEquals(null, (route as TransportRoute.Unsupported).endpointType)
        assertEquals(TransportDispatch.REASON_UNKNOWN_OPERATION, route.reason)
    }

    @Test
    fun capabilityGateFailsBeforeAnyRequest() = runBlocking {
        server.enqueue(sseDone("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))
        val target = chatTarget(offered = emptySet())
        val messages = listOf(
            PromptMessage("user", listOf(ImageUrlPart("https://fixture.invalid/a.png"))),
        )
        try {
            chatTransport().stream(target, messages).toList()
            fail("expected gate rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun gateMissingListsEachCapabilityIndependently() {
        val target = chatTarget(offered = emptySet())
        val messages = listOf(
            PromptMessage("user", listOf(ImageUrlPart("https://fixture.invalid/a.png"))),
            PromptMessage("user", listOf(FileRefPart(fileId = "file-fixture-1"))),
        )
        val missing = target.missingCapabilitiesFor(messages)
        assertEquals(setOf(Capability.VISION, Capability.FILES), missing)
        assertTrue(missing.contains(Capability.VISION))
        assertTrue(missing.contains(Capability.FILES))
    }

    @Test
    fun multimodalChatRoundTripsOverLoopback() = runBlocking {
        server.enqueue(sseDone("""{"choices":[{"index":0,"delta":{"content":"Hi-fixture"}}]}"""))
        val events = chatTransport().stream(
            chatTarget(),
            listOf(PromptMessage("user", listOf(TextPart("hi-fixture"), ImageUrlPart("https://fixture.invalid/a.png")))),
        ).toList()
        assertEquals("Hi-fixture", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
        val recorded = requireNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val first = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals(2, first["content"]!!.jsonArray.size)
    }

    @Test
    fun toolsAreSentWhenDeclared() = runBlocking {
        server.enqueue(sseDone("""{"choices":[{"index":0,"delta":{"content":"ok-fixture"}}]}"""))
        chatTransport().stream(
            chatTarget().copy(tools = listOf(ToolDefinition("get_weather")), toolChoice = "auto"),
            listOf(PromptMessage("user", "hi-fixture")),
        ).toList()
        val recorded = requireNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(1, body["tools"]!!.jsonArray.size)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun messagesEndpointTypesStayChatForBlocks() {
        val messages = listOf(PromptMessage("user", listOf(TextPart("a"), ImageUrlPart("https://fixture.invalid/a.png"))))
        assertEquals(setOf(EndpointType.CHAT_COMPLETIONS), messages.requiredEndpointTypes())
        assertEquals(emptySet<EndpointType>(), emptyList<PromptMessage>().requiredEndpointTypes())
    }
}
