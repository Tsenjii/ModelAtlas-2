package dev.modelatlas.core.network

import dev.modelatlas.core.domain.Capability
import dev.modelatlas.core.domain.EndpointType
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.captureIllegalArgument
import dev.modelatlas.core.inference.fixtureMessages
import dev.modelatlas.core.inference.fixtureTarget
import dev.modelatlas.core.multimodal.AudioOperationPayload
import dev.modelatlas.core.multimodal.ImageEditPayload
import dev.modelatlas.core.multimodal.ImageGenerationPayload
import dev.modelatlas.core.multimodal.decodeDataUriBytes
import dev.modelatlas.core.multimodal.formFields
import dev.modelatlas.core.multimodal.toWireJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * PLAY-004 image／audio 真 transport 測試（RV5 §3／§6 P0，選項 a）。
 *
 * - Wire helper 正本（`toWireJson`／`formFields`×2／`decodeDataUriBytes`）皆有直接斷言
 *   （過去 0 呼叫者＋0 測試，W2）。
 * - 三 transport 皆走 loopback `MockWebServer` 斷真實 request（JSON／multipart）＋
 *   非串流回應（`GeneratedImages`／`Transcription`＋`Finished`），無真 key、無產品 mock。
 * - Fail-closed 鎖定：gate／operation 錯配／缺 payload／messages 非空／data URI 非法皆
 *   零 request＋零憑證讀取（計數 fake）。
 */
class OpenAiMediaTransportsTest {
    private lateinit var server: MockWebServer
    private val credentialValue = "fixture-bearer-token-do-not-log"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun loopbackUrl(path: String): String = "http://127.0.0.1:${server.port}$path"

    private fun countingCredentials(counter: IntArray, value: String = credentialValue): CredentialAccess =
        CredentialAccess { _, _ ->
            counter[0]++
            value
        }

    private fun generationTransport(counter: IntArray? = null): OpenAiImageGenerationTransport {
        val reads = counter ?: IntArray(1)
        return OpenAiImageGenerationTransport(countingCredentials(reads), allowLoopbackHttpForTests = true)
    }

    private fun editTransport(counter: IntArray? = null): OpenAiImageEditTransport {
        val reads = counter ?: IntArray(1)
        return OpenAiImageEditTransport(countingCredentials(reads), allowLoopbackHttpForTests = true)
    }

    private fun audioTransport(counter: IntArray? = null): OpenAiAudioTranscriptionTransport {
        val reads = counter ?: IntArray(1)
        return OpenAiAudioTranscriptionTransport(countingCredentials(reads), allowLoopbackHttpForTests = true)
    }

    private fun chatTransport(): OpenAiTransport =
        OpenAiTransport({ _, _ -> credentialValue }, allowLoopbackHttpForTests = true)

    private fun generationTarget(url: String = loopbackUrl("/v1/images/generations")): InferenceTarget =
        InferenceTarget(
            providerId = "p-fixture",
            accountId = "a-fixture",
            credentialId = "safe-credential-fixture",
            endpointId = "e-fixture",
            endpointUrl = url,
            modelId = "fixture-model",
            operation = EndpointType.IMAGES_GENERATION.name,
            imageGeneration = ImageGenerationPayload("a-fixture", size = "1024x1024", n = 1),
            offeredCapabilities = setOf(Capability.IMAGE_GENERATION),
        )

    private fun editTarget(
        url: String = loopbackUrl("/v1/images/edits"),
        mask: Boolean = true,
    ): InferenceTarget {
        val imageRef = "data:image/png;base64,iVBORw0KGgo="
        val maskRef = if (mask) "data:image/png;base64,iVBORw0KGgo=" else null
        return InferenceTarget(
            providerId = "p-fixture",
            accountId = "a-fixture",
            credentialId = "safe-credential-fixture",
            endpointId = "e-fixture",
            endpointUrl = url,
            modelId = "fixture-model",
            operation = EndpointType.IMAGES_EDIT.name,
            imageEdit = ImageEditPayload(imageRef, "edit-fixture", maskRef, size = "1024x1024"),
            offeredCapabilities = setOf(Capability.IMAGE_EDITING),
        )
    }

    private fun audioTarget(url: String = loopbackUrl("/v1/audio/transcriptions")): InferenceTarget =
        InferenceTarget(
            providerId = "p-fixture",
            accountId = "a-fixture",
            credentialId = "safe-credential-fixture",
            endpointId = "e-fixture",
            endpointUrl = url,
            modelId = "fixture-model",
            operation = EndpointType.AUDIO.name,
            audioOperation = AudioOperationPayload("data:audio/wav;base64,QQ==", language = "en", responseFormat = "json"),
            offeredCapabilities = setOf(Capability.AUDIO_INPUT),
        )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    // ---- wire helper 正本 ----

    @Test
    fun imageGenerationWireContainsModelPromptSizeN() {
        val wire = ImageGenerationPayload("a-fixture", size = "1024x1024", n = 2).toWireJson("fixture-model")
        assertEquals("fixture-model", wire["model"]!!.jsonPrimitive.content)
        assertEquals("a-fixture", wire["prompt"]!!.jsonPrimitive.content)
        assertEquals("1024x1024", wire["size"]!!.jsonPrimitive.content)
        assertEquals(2, wire["n"]!!.jsonPrimitive.intOrNull)
        assertFalse(wire.containsKey("messages"))
    }

    @Test
    fun imageEditFormFieldsContainModelPromptSizeOnly() {
        val fields = ImageEditPayload(
            "data:image/png;base64,QQ==",
            "edit-fixture",
            maskRef = "data:image/png;base64,QQ==",
            size = "1024x1024",
        ).formFields("fixture-model")
        assertEquals("fixture-model", fields["model"])
        assertEquals("edit-fixture", fields["prompt"])
        assertEquals("1024x1024", fields["size"])
        // bytes（image／mask）另以 decodeDataUriBytes 解析，不在文字欄內。
        assertFalse(fields.keys.any { it == "image" || it == "mask" })
    }

    @Test
    fun audioFormFieldsContainModelLanguageFormatOnly() {
        val fields = AudioOperationPayload(
            "data:audio/wav;base64,QQ==",
            language = "en",
            responseFormat = "json",
        ).formFields("fixture-model")
        assertEquals("fixture-model", fields["model"])
        assertEquals("en", fields["language"])
        assertEquals("json", fields["response_format"])
        assertFalse(fields.keys.any { it == "file" })
    }

    @Test
    fun decodeImageDataUriReturnsMimeAndBytes() {
        val decoded = decodeDataUriBytes("data:image/png;base64,iVBORw0KGgo=", "image/")
        assertEquals("image/png", decoded.mimeType)
        assertEquals(8, decoded.bytes.size)
        val audio = decodeDataUriBytes("data:audio/wav;base64,QQ==", "audio/")
        assertEquals("audio/wav", audio.mimeType)
        assertEquals(1, audio.bytes.size)
    }

    @Test
    fun decodeRejectsRemoteUrl() {
        try {
            decodeDataUriBytes("https://fixture.invalid/a.png", "image/")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun decodeRejectsWrongMimePrefix() {
        try {
            decodeDataUriBytes("data:text/plain;base64,QQ==", "image/")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
        try {
            decodeDataUriBytes("data:image/png;base64,QQ==", "audio/")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun decodeRejectsNonBase64AndBadPayload() {
        try {
            decodeDataUriBytes("data:image/png,QQ==", "image/")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
        try {
            decodeDataUriBytes("data:image/png;base64,!!!not-base64!!!", "image/")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
        try {
            decodeDataUriBytes("data:image/png;base64,QQ==\n", "image/")
            fail("expected reject")
        } catch (_: IllegalArgumentException) { }
    }

    // ---- 生成：JSON＋非串流 ----

    @Test
    fun generationPostsJsonAndEmitsImagesThenFinished() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[{"url":"https://fixture.invalid/out.png","revised_prompt":"rev-fixture"}]}"""))
        val events = generationTransport().stream(generationTarget(), emptyList()).toList()
        assertTrue(events.first() is StreamEvent.RequestSent)
        assertEquals(200, events.filterIsInstance<StreamEvent.Headers>().single().status)
        val images = events.filterIsInstance<StreamEvent.GeneratedImages>().single().images
        assertEquals(1, images.size)
        assertEquals("https://fixture.invalid/out.png", images[0].url)
        assertEquals("rev-fixture", images[0].revisedPrompt)
        assertEquals(StreamEvent.Finished, events.last())
        assertEquals(1, server.requestCount)
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("Bearer $credentialValue", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = kotlinx.serialization.json.Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("fixture-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("a-fixture", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("1024x1024", body["size"]!!.jsonPrimitive.content)
        assertEquals(1, body["n"]!!.jsonPrimitive.intOrNull)
    }

    @Test
    fun generationB64ResponseEmitsB64Image() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[{"b64_json":"RkFL"}]}"""))
        val events = generationTransport().stream(generationTarget(), emptyList()).toList()
        assertEquals("RkFL", events.filterIsInstance<StreamEvent.GeneratedImages>().single().images[0].b64Json)
        assertEquals(StreamEvent.Finished, events.last())
    }

    // ---- 編輯：multipart（含 mask pathway） ----

    @Test
    fun editPostsMultipartWithImageAndMaskAndEmitsImages() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[{"b64_json":"RkFL"}]}"""))
        val events = editTransport().stream(editTarget(mask = true), emptyList()).toList()
        assertEquals("RkFL", events.filterIsInstance<StreamEvent.GeneratedImages>().single().images[0].b64Json)
        assertEquals(StreamEvent.Finished, events.last())
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue(recorded.getHeader("Content-Type").orEmpty().contains("multipart/form-data"))
        assertEquals("Bearer $credentialValue", recorded.getHeader("Authorization"))
        val text = recorded.body.readUtf8()
        assertTrue(text.contains("fixture-model"))
        assertTrue(text.contains("edit-fixture"))
        assertTrue(text.contains("1024x1024"))
        assertTrue(text.contains("name=\"image\""))
        assertTrue(text.contains("filename=\"image.png\""))
        // maskRef 過去零 pathway；此刻必須真送。
        assertTrue(text.contains("name=\"mask\""))
        assertTrue(text.contains("filename=\"mask.png\""))
    }

    @Test
    fun editWithoutMaskOmitsMaskPart() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[{"url":"https://fixture.invalid/out.png"}]}"""))
        editTransport().stream(editTarget(mask = false), emptyList()).toList()
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val text = recorded.body.readUtf8()
        assertTrue(text.contains("name=\"image\""))
        assertFalse(text.contains("name=\"mask\""))
    }

    // ---- 轉寫：multipart＋非串流 ----

    @Test
    fun transcriptionPostsMultipartAndEmitsTextThenFinished() = runBlocking {
        server.enqueue(jsonResponse("""{"text":"hello-fixture"}"""))
        val events = audioTransport().stream(audioTarget(), emptyList()).toList()
        assertEquals("hello-fixture", events.filterIsInstance<StreamEvent.Transcription>().single().text)
        assertNotNull(events.filterIsInstance<StreamEvent.Transcription>().single().observedMonotonicMs)
        assertEquals(StreamEvent.Finished, events.last())
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue(recorded.getHeader("Content-Type").orEmpty().contains("multipart/form-data"))
        val text = recorded.body.readUtf8()
        assertTrue(text.contains("fixture-model"))
        assertTrue(text.contains("en"))
        assertTrue(text.contains("json"))
        assertTrue(text.contains("name=\"file\""))
        assertTrue(text.contains("filename=\"audio.wav\""))
    }

    // ---- fail-closed：gate／operation／payload／messages／解碼 ----

    @Test
    fun mediaGateFailsBeforeAnyRequestAndReadsNoCredential() = runBlocking {
        for (setup in listOf(
            generationTarget().copy(offeredCapabilities = emptySet()) to generationTransport(),
            editTarget().copy(offeredCapabilities = emptySet()) to editTransport(),
            audioTarget().copy(offeredCapabilities = emptySet()) to audioTransport(),
        )) {
            val reads = IntArray(1)
            val (target, _) = setup
            // 以計數憑證重建，确保 gate 前零讀取可觀測。
            val transport = when (target.operation) {
                EndpointType.IMAGES_GENERATION.name -> OpenAiImageGenerationTransport(countingCredentials(reads), allowLoopbackHttpForTests = true)
                EndpointType.IMAGES_EDIT.name -> OpenAiImageEditTransport(countingCredentials(reads), allowLoopbackHttpForTests = true)
                else -> OpenAiAudioTranscriptionTransport(countingCredentials(reads), allowLoopbackHttpForTests = true)
            }
            val failure = captureIllegalArgument { transport.stream(target, emptyList()).toList() }
            assertNotNull("gate must reject for ${target.operation}", failure)
            assertEquals("gate must read no credential for ${target.operation}", 0, reads[0])
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun chatTransportRejectsMediaOperationBeforeAnyRequest() = runBlocking {
        val reads = IntArray(1)
        val chat = OpenAiTransport(countingCredentials(reads), allowLoopbackHttpForTests = true)
        // gate 會通過（offered 正確），但 operation 必須先拒，不可靜默送 chat body。
        val failure = captureIllegalArgument {
            chat.stream(generationTarget(), emptyList()).toList()
        }
        assertNotNull(failure)
        assertEquals(0, server.requestCount)
        assertEquals(0, reads[0])
        val audioWithoutPayload = fixtureTarget(loopbackUrl("/v1/audio/transcriptions"))
            .copy(operation = EndpointType.AUDIO.name, offeredCapabilities = setOf(Capability.AUDIO_INPUT))
        val second = captureIllegalArgument { chat.stream(audioWithoutPayload, emptyList()).toList() }
        assertNotNull(second)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mediaTransportsRejectWrongOperationBeforeAnyRequest() = runBlocking {
        // 注意：帶 payload 的錯配在 InferenceTarget 建構即拒（契約），此處以無 payload 的
        // 媒體 operation 測 transport 的 operation 守衛（先於 payload 檢查，零 request）。
        fun bareMediaTarget(operation: String): InferenceTarget = InferenceTarget(
            providerId = "p-fixture",
            accountId = "a-fixture",
            credentialId = "safe-credential-fixture",
            endpointId = "e-fixture",
            endpointUrl = loopbackUrl("/v1/fixture"),
            modelId = "fixture-model",
            operation = operation,
            offeredCapabilities = setOf(Capability.IMAGE_GENERATION, Capability.IMAGE_EDITING, Capability.AUDIO_INPUT),
        )
        val cases: List<Pair<dev.modelatlas.core.inference.InferenceTransport, InferenceTarget>> = listOf(
            generationTransport() to bareMediaTarget(EndpointType.IMAGES_EDIT.name),
            editTransport() to bareMediaTarget(EndpointType.IMAGES_GENERATION.name),
            audioTransport() to bareMediaTarget(EndpointType.CHAT_COMPLETIONS.name),
        )
        for ((transport, target) in cases) {
            val failure = captureIllegalArgument { transport.stream(target, emptyList()).toList() }
            assertNotNull("wrong operation must reject for ${target.operation}", failure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mediaTransportsRequirePayloadBeforeAnyRequest() = runBlocking {
        val genNoPayload = generationTarget().copy(imageGeneration = null)
        assertNotNull(captureIllegalArgument { generationTransport().stream(genNoPayload, emptyList()).toList() })
        val editNoPayload = editTarget().copy(imageEdit = null)
        assertNotNull(captureIllegalArgument { editTransport().stream(editNoPayload, emptyList()).toList() })
        val audioNoPayload = audioTarget().copy(audioOperation = null)
        assertNotNull(captureIllegalArgument { audioTransport().stream(audioNoPayload, emptyList()).toList() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mediaTransportsRejectNonEmptyMessagesBeforeAnyRequest() = runBlocking {
        val messages = fixtureMessages()
        assertNotNull(
            captureIllegalArgument {
                generationTransport().stream(
                    generationTarget().copy(offeredCapabilities = setOf(Capability.IMAGE_GENERATION)),
                    messages,
                ).toList()
            },
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun editRejectsRemoteImageRefBeforeAnyRequest() = runBlocking {
        val reads = IntArray(1)
        val bad = editTarget().copy(
            imageEdit = ImageEditPayload("https://fixture.invalid/a.png", "edit-fixture"),
        )
        val failure = captureIllegalArgument {
            OpenAiImageEditTransport(countingCredentials(reads), allowLoopbackHttpForTests = true)
                .stream(bad, emptyList()).toList()
        }
        assertNotNull(failure)
        assertEquals(0, server.requestCount)
        assertEquals(0, reads[0])
    }

    // ---- 錯誤分類＋解析 ----

    @Test
    fun mediaHttpErrorsMapToOutcomes() = runBlocking {
        val cases = listOf(401 to Outcome.AUTH, 429 to Outcome.RATE_LIMIT, 500 to Outcome.SERVER)
        for ((status, expected) in cases) {
            server.enqueue(
                MockResponse().setResponseCode(status)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"fixture"}"""),
            )
            var failure: InferenceFailure? = null
            try {
                generationTransport().stream(generationTarget(), emptyList()).toList()
                fail("expected $expected")
            } catch (e: InferenceFailure) {
                failure = e
            }
            assertEquals(expected, failure?.outcome)
            assertEquals(status, failure?.status)
        }
    }

    @Test
    fun mediaParserFailureOnBadJson() = runBlocking {
        server.enqueue(jsonResponse("""{"nope":1}"""))
        try {
            generationTransport().stream(generationTarget(), emptyList()).toList()
            fail("expected parser failure")
        } catch (e: InferenceFailure) {
            assertEquals(Outcome.PARSER, e.outcome)
        }
        server.enqueue(jsonResponse("""{"text":""}"""))
        try {
            audioTransport().stream(audioTarget(), emptyList()).toList()
            fail("expected parser failure")
        } catch (e: InferenceFailure) {
            assertEquals(Outcome.PARSER, e.outcome)
        }
    }

    @Test
    fun mediaWrongContentTypeIsParserFailure() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: hi\n\n"),
        )
        try {
            generationTransport().stream(generationTarget(), emptyList()).toList()
            fail("expected parser failure")
        } catch (e: InferenceFailure) {
            assertEquals(Outcome.PARSER, e.outcome)
        }
    }

    // ---- 分派獨立＋匿名＋憑證邊界 ----

    @Test
    fun dispatchRoutesMediaIndependentlyWithoutInterference() {
        val chat = chatTransport()
        val gen = generationTransport()
        val edit = editTransport()
        val audio = audioTransport()
        val dispatch = TransportDispatch(listOf(chat, gen, edit, audio))
        val routedGen = dispatch.route(EndpointType.IMAGES_GENERATION.name)
        val routedEdit = dispatch.route(EndpointType.IMAGES_EDIT.name)
        val routedAudio = dispatch.route(EndpointType.AUDIO.name)
        val routedChat = dispatch.route(EndpointType.CHAT_COMPLETIONS.name)
        assertTrue(routedGen is TransportRoute.Routed)
        assertSame(gen, (routedGen as TransportRoute.Routed).transport)
        assertTrue(routedEdit is TransportRoute.Routed)
        assertSame(edit, (routedEdit as TransportRoute.Routed).transport)
        assertTrue(routedAudio is TransportRoute.Routed)
        assertSame(audio, (routedAudio as TransportRoute.Routed).transport)
        assertTrue(routedChat is TransportRoute.Routed)
        assertSame(chat, (routedChat as TransportRoute.Routed).transport)
        // 未註冊型別仍各自獨立 Unsupported，不被媒體拖累。
        val missing = dispatch.route(EndpointType.RESPONSES.name)
        assertTrue(missing is TransportRoute.Unsupported)
        assertEquals(TransportDispatch.REASON_NO_TRANSPORT, (missing as TransportRoute.Unsupported).reason)
    }

    @Test
    fun anonymousGenerationSendsNoAuthorizationHeader() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[{"url":"https://fixture.invalid/out.png"}]}"""))
        val events = generationTransport()
            .stream(generationTarget().copy(credentialId = null), emptyList()).toList()
        assertEquals(StreamEvent.Finished, events.last())
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun blankCredentialIsRejectedBeforeAnyMediaRequest() = runBlocking {
        val bad = OpenAiImageGenerationTransport({ _, _ -> "   " }, allowLoopbackHttpForTests = true)
        var failure: IllegalArgumentException? = null
        try {
            bad.stream(generationTarget(), emptyList()).toList()
        } catch (e: IllegalArgumentException) {
            failure = e
        }
        assertNotNull(failure)
        assertEquals(0, server.requestCount)
    }
}
