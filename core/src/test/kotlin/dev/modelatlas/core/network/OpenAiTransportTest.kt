package dev.modelatlas.core.network

import dev.modelatlas.core.inference.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiTransportTest {
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

    private fun transport(value: String = credentialValue) =
        OpenAiTransport({ _, _ -> value }, allowLoopbackHttpForTests = true)

    private fun loopbackUrl(mockServer: MockWebServer = server, path: String = "/v1/chat/completions"): String =
        "http://127.0.0.1:${mockServer.port}$path"

    private fun target(url: String = loopbackUrl()): InferenceTarget = fixtureTarget(url)

    private fun sse(vararg payloads: String): String = payloads.joinToString(separator = "") { "data: $it\n\n" }

    private fun completion(): String = "data: [DONE]\n\n"

    private fun streamResponse(vararg payloads: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(sse(*payloads) + completion())

    private suspend fun collect(flow: Flow<StreamEvent>): List<StreamEvent> = flow.toList()

    private suspend fun collectCatching(flow: Flow<StreamEvent>): Pair<List<StreamEvent>, InferenceFailure?> {
        val events = mutableListOf<StreamEvent>()
        val failure = captureInferenceFailure { flow.collect { events += it } }
        return events to failure
    }

    private fun List<StreamEvent>.text(): String =
        filterIsInstance<StreamEvent.Delta>().joinToString(separator = "") { it.text }

    private fun List<StreamEvent>.deltas(): List<StreamEvent.Delta> = filterIsInstance<StreamEvent.Delta>()

    private fun List<StreamEvent>.usage(): Usage? = filterIsInstance<StreamEvent.TokenUsage>().singleOrNull()?.usage

    private fun List<StreamEvent>.headers(): StreamEvent.Headers = filterIsInstance<StreamEvent.Headers>().single()

    @Test
    fun streamStartsWithRequestSentAndStampsMonotonicTimes() = runBlocking {
        server.enqueue(streamResponse("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))
        val events = collect(transport().stream(target(), fixtureMessages()))

        val sent = events.first() as StreamEvent.RequestSent
        assertTrue(sent.epochMillis > 0L)
        assertTrue(sent.monotonicMillis > 0L)
        assertEquals(200, events.headers().status)
        assertNotNull(events.headers().observedMonotonicMs)
        assertTrue(events.headers().observedMonotonicMs!! >= sent.monotonicMillis)
        assertEquals(listOf("Hi"), events.deltas().map { it.text })
        assertTrue(events.deltas().all { it.observedMonotonicMs != null })
        assertEquals(StreamEvent.Finished, events.last())
        assertEquals(4, events.size)
    }

    @Test
    fun contentModelAndUsageAreMappedFromTheSameChunk() = runBlocking {
        server.enqueue(streamResponse(
            """{"model":"fixture-model-resolved","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"}}],"usage":{"prompt_tokens":9,"completion_tokens":12,"total_tokens":21}}""",
        ))
        val events = collect(transport().stream(target(), fixtureMessages()))

        assertEquals("Hello", events.text())
        assertEquals("fixture-model-resolved", events.filterIsInstance<StreamEvent.ResponseModel>().single().id)
        assertEquals(Usage(9L, 12L, 21L), events.usage())
        assertTrue(events.indexOfFirst { it is StreamEvent.TokenUsage } < events.indexOfFirst { it is StreamEvent.Finished })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun roleOnlyChunkProducesNoDelta() = runBlocking {
        server.enqueue(streamResponse(
            """{"choices":[{"index":0,"delta":{"role":"assistant"}}]}""",
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"Hi"}}]}""",
        ))
        val events = collect(transport().stream(target(), fixtureMessages()))

        assertEquals(listOf("Hi"), events.deltas().map { it.text })
        assertTrue(events.none { it is StreamEvent.Delta && it.text.isEmpty() })
    }

    @Test
    fun multiLineDataIsJoinedBeforeParsing() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                ": keep-alive\n\nevent: message\ndata: {\"choices\":[{\"index\":0,\n" +
                    "data: \"delta\":{\"content\":\"split\"}}]}\n\n" + completion(),
            ))
        val events = collect(transport().stream(target(), fixtureMessages()))

        assertEquals("split", events.text())
    }

    @Test
    fun onlyIndexZeroChoiceIsStreamed() = runBlocking {
        server.enqueue(streamResponse(
            """{"choices":[{"index":1,"delta":{"content":"second"}},{"index":0,"delta":{"content":"first"}}]}""",
        ))
        val events = collect(transport().stream(target(), fixtureMessages()))

        assertEquals("first", events.text())
    }

    @Test
    fun reasoningAndCacheUsageAreMappedToSeparateFields() = runBlocking {
        server.enqueue(streamResponse(
            """{"choices":[{"index":0,"delta":{"reasoning_content":"pondering","content":"answer"}}]}""",
            """{"usage":{"prompt_tokens":30,"completion_tokens":8,"total_tokens":38,"completion_tokens_details":{"reasoning_tokens":5},"prompt_tokens_details":{"cached_tokens":20},"cache_creation_input_tokens":4}}""",
        ))
        val events = collect(transport().stream(target(), fixtureMessages()))

        assertEquals(listOf("pondering", "answer"), events.deltas().map { it.text })
        assertEquals(listOf(true, false), events.deltas().map { it.reasoning })
        assertEquals(Usage(30L, 8L, 38L, 5L, 20L, 4L), events.usage())
    }

    @Test
    fun usageWithMissingCountsStaysUnknown() = runBlocking {
        server.enqueue(streamResponse("""{"usage":{"prompt_tokens":12}}"""))

        assertEquals(Usage(inputTokens = 12L), collect(transport().stream(target(), fixtureMessages())).usage())
    }

    @Test
    fun invalidTokenCountsFallBackToUnknown() = runBlocking {
        server.enqueue(streamResponse("""{"usage":{"prompt_tokens":-5,"completion_tokens":"many","total_tokens":null}}"""))

        assertEquals(Usage(), collect(transport().stream(target(), fixtureMessages())).usage())
    }

    @Test
    fun malformedJsonIsParserFailure() = runBlocking {
        server.enqueue(streamResponse("{not-json"))

        val failure = captureInferenceFailure { transport().stream(target(), fixtureMessages()).toList() }

        assertEquals(Outcome.PARSER, failure?.outcome)
    }

    @Test
    fun errorObjectIsFailureWithoutLeakingProviderMessage() = runBlocking {
        val message = "fixture-error-message-must-not-leak"
        server.enqueue(streamResponse("""{"error":{"message":"$message","type":"invalid_request_error"}}"""))

        val failure = captureInferenceFailure { transport().stream(target(), fixtureMessages()).toList() }

        assertEquals(Outcome.OTHER, failure?.outcome)
        assertFalse(failure?.message.orEmpty().contains(message))
    }

    @Test
    fun emptyStreamIsParserFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(""))

        val failure = captureInferenceFailure { transport().stream(target(), fixtureMessages()).toList() }

        assertEquals(Outcome.PARSER, failure?.outcome)
    }

    @Test
    fun truncatedStreamWithoutDoneIsParserFailureAfterPartialOutput() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("""data: {"choices":[{"index":0,"delta":{"content":"half"}}]}

"""))
        val (events, failure) = collectCatching(transport().stream(target(), fixtureMessages()))

        assertEquals(Outcome.PARSER, failure?.outcome)
        assertEquals("half", events.text())
        assertTrue(events.none { it is StreamEvent.Finished })
    }

    @Test
    fun slowCollectorRetainsPartialOutputAndTelemetryBeforeParserFailure() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setHeader("X-RateLimit-Remaining-Tokens", "7")
            .setBody(sse("""{"choices":[{"index":0,"delta":{"content":"partial"}}]}""", "{broken")))
        val sink = RecordingTelemetry()
        val events = mutableListOf<StreamEvent>()

        val failure = withTimeout(5_000L) {
            captureInferenceFailure {
                InferenceEngine(transport(), sink, SystemClock)
                    .execute(target(), fixtureMessages())
                    .collect {
                        // Let the producer reach its failure while events queue up.
                        if (it is StreamEvent.RequestSent) delay(100L)
                        events += it
                    }
            }
        }

        assertEquals(Outcome.PARSER, failure?.outcome)
        assertEquals("partial", events.text())
        assertEquals(200, events.headers().status)
        assertTrue(events.none { it is StreamEvent.Finished })
        assertEquals(2, sink.saves.size)
        assertEquals(Outcome.PARSER, sink.last().outcome)
        assertEquals(200, sink.last().httpStatus)
        assertEquals("7", sink.last().rateLimitHeaders["x-ratelimit-remaining-tokens"])
        assertNotNull(sink.last().ttfbMs)
        assertNotNull(sink.last().ttftMs)
        assertNull(sink.last().usage.outputTokens)
        assertNull(sink.last().tps)
    }

    @Test
    fun nonEventStreamContentTypeIsParserFailure() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[]}"""))

        val failure = captureInferenceFailure { transport().stream(target(), fixtureMessages()).toList() }

        assertEquals(Outcome.PARSER, failure?.outcome)
    }

    @Test
    fun eventStreamContentTypeWithCharsetIsAccepted() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
            .setBody(sse("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}""") + completion()))

        assertEquals("Hi", collect(transport().stream(target(), fixtureMessages())).text())
    }

    @Test
    fun unauthorizedKeepsStatusAndDropsBodyAndUnknownHeaders() = runBlocking {
        val body = """{"error":{"message":"incorrect api key: $credentialValue","code":"invalid_api_key"}}"""
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setHeader("WWW-Authenticate", "Bearer realm=\"fixture\"")
            .setHeader("X-Provider-Debug", credentialValue)
            .setBody(body))
        val (events, failure) = collectCatching(transport().stream(target(), fixtureMessages()))

        assertEquals(Outcome.AUTH, failure?.outcome)
        assertEquals(401, failure?.status)
        assertEquals(401, events.headers().status)
        assertEquals(emptyMap<String, String>(), events.headers().rateLimits)
        assertTrue(events.none { it is StreamEvent.Delta || it is StreamEvent.TokenUsage || it is StreamEvent.Finished })
        assertFalse(failure?.message.orEmpty().contains(credentialValue))
    }

    @Test
    fun httpStatusesMapToOutcomes() = runBlocking {
        val cases = listOf(
            400 to Outcome.OTHER,
            401 to Outcome.AUTH,
            403 to Outcome.AUTH,
            404 to Outcome.MODEL_UNAVAILABLE,
            429 to Outcome.RATE_LIMIT,
            500 to Outcome.SERVER,
            503 to Outcome.SERVER,
        )
        for ((status, expected) in cases) {
            server.enqueue(MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"fixture"}"""))
            val failure = captureInferenceFailure { transport().stream(target(), fixtureMessages()).toList() }

            assertEquals(expected, failure?.outcome)
            assertEquals(status, failure?.status)
        }
    }

    @Test
    fun rateLimitRetryAfterIsRetained() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Content-Type", "application/json")
            .setHeader("Retry-After", "30")
            .setHeader("X-RateLimit-Remaining-Tokens", "0")
            .setHeader("X-Provider-Debug", credentialValue)
            .setBody("""{"error":"slow down"}"""))
        val (events, failure) = collectCatching(transport().stream(target(), fixtureMessages()))

        assertEquals(Outcome.RATE_LIMIT, failure?.outcome)
        assertEquals(429, failure?.status)
        assertEquals("30", events.headers().rateLimits["retry-after"])
        assertEquals("0", events.headers().rateLimits["x-ratelimit-remaining-tokens"])
        assertEquals(2, events.headers().rateLimits.size)
    }

    @Test
    fun redirectIsNotFollowedAndCredentialIsNotForwarded() = runBlocking {
        val redirectTarget = MockWebServer()
        redirectTarget.start()
        try {
            server.enqueue(MockResponse()
                .setResponseCode(302)
                .setHeader("Location", loopbackUrl(redirectTarget, "/v1/chat/completions")))
            val (events, failure) = collectCatching(transport().stream(target(), fixtureMessages()))
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)

            assertEquals(Outcome.OTHER, failure?.outcome)
            assertEquals(302, failure?.status)
            val header = events.filterIsInstance<StreamEvent.Headers>().singleOrNull()
                ?: error("No Headers event for 302; events=$events failure=$failure")
            assertEquals(302, header.status)
            assertEquals(1, server.requestCount)
            assertEquals(0, redirectTarget.requestCount)
            assertEquals("Bearer $credentialValue", recorded?.getHeader("Authorization"))
        } finally {
            redirectTarget.shutdown()
        }
    }

    @Test
    fun blankCredentialIsRejectedBeforeAnyRequest() = runBlocking {
        val failure = captureIllegalArgument { transport("   ").stream(target(), fixtureMessages()).toList() }

        assertNotNull(failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun secretEchoIsRedactedInRawAndEncodedForms() {
        val token = "fix+ture/secret=ABCDEF1234567890"
        val urlEncoded = java.net.URLEncoder.encode(token, "UTF-8")
        val base64 = java.util.Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))
        val base64Url = java.util.Base64.getUrlEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))
        assertTrue(urlEncoded != token && base64 != token && base64Url != token)
        val text = "token=$token&token=$urlEncoded&token=$base64&token=$base64Url"
        val redacted = redactSecrets(text, token)

        assertFalse(redacted.contains(token))
        assertFalse(redacted.contains(urlEncoded))
        assertFalse(redacted.contains(base64))
        assertFalse(redacted.contains(base64Url))
        assertFalse(redacted.contains(base64.trimEnd('=')))
        assertTrue(redacted.contains("[REDACTED]"))
        assertEquals(text, redactSecrets(text, null))
        assertEquals("short", redactSecrets("short", "short"))
    }

    @Test
    fun anonymousTargetSendsNoAuthorizationHeader() = runBlocking {
        server.enqueue(streamResponse("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))
        val events = collect(transport().stream(target().copy(credentialId = null), fixtureMessages()))

        assertEquals("Hi", events.text())
        assertEquals(StreamEvent.Finished, events.last())
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun credentialWithNewlineIsRejectedBeforeAnyRequest() = runBlocking {
        val failure = captureIllegalArgument {
            transport("fixture-token\r\nX-Injected: 1").stream(target(), fixtureMessages()).toList()
        }

        assertNotNull(failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun loopbackEscapeHatchRejectsRemoteHost() = runBlocking {
        val remoteTarget = fixtureTarget("http://api.fixture.invalid/v1/chat/completions")

        val failure = captureIllegalArgument { transport().stream(remoteTarget, fixtureMessages()).toList() }

        assertNotNull(failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellingTheCollectorStopsAStreamThatNeverCompletes() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val events = mutableListOf<StreamEvent>()
        val job = launch {
            transport().stream(target(), fixtureMessages()).collect { events += it }
        }
        // Wait until the request actually reaches the server, then cancel.
        val deadline = System.currentTimeMillis() + 5_000L
        while (server.requestCount == 0 && System.currentTimeMillis() < deadline) delay(50L)
        assertEquals(1, server.requestCount)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        // At most RequestSent escapes before the socket blocks; never an error event.
        assertTrue(events.size <= 1)
        assertTrue(events.all { it is StreamEvent.RequestSent })
    }

    @Test
    fun probeUsesExplicitTokenBudgetAndStillRecordsTelemetry() = runBlocking {
        server.enqueue(streamResponse("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))
        val sink = RecordingTelemetry()
        InferenceEngine(transport(), sink, SystemClock)
            .execute(target().copy(maxOutputTokens = 1), fixtureMessages(), source = TelemetrySource.BACKGROUND_PROBE)
            .toList()
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(1, body["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, sink.saves.size)
        assertEquals(TelemetrySource.BACKGROUND_PROBE, sink.last().source)
        assertEquals(Outcome.SUCCESS, sink.last().outcome)
        assertNull(sink.last().usage.outputTokens)
    }

    @Test
    fun requestCarriesEndpointScopeAndCredentialHeaderOnly() = runBlocking {
        server.enqueue(streamResponse("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))
        collect(transport().stream(target(), fixtureMessages()))
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer $credentialValue", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals("fixture-model", body["model"]?.jsonPrimitive?.content)
        assertEquals(true, body["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.booleanOrNull)
        assertEquals(512, body["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals(
            listOf("system", "user"),
            body["messages"]?.jsonArray?.map { it.jsonObject["role"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun engineRetainsUnauthorizedStatusWithoutRawBodyOrCredential() = runBlocking {
        val rawBody = """{"error":{"message":"invalid api key $credentialValue"}}"""
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody(rawBody))
        val sink = RecordingTelemetry()

        val failure = captureInferenceFailure {
            InferenceEngine(transport(), sink, SystemClock)
                .execute(target(), fixtureMessages())
                .collect { }
        }
        val sample = sink.last()

        assertEquals(Outcome.AUTH, failure?.outcome)
        assertEquals(401, failure?.status)
        assertEquals(2, sink.saves.size)
        assertEquals(Outcome.RUNNING, sink.first().outcome)
        assertEquals(Outcome.AUTH, sample.outcome)
        assertEquals(401, sample.httpStatus)
        assertNull(sample.usage.outputTokens)
        assertNull(sample.tps)
        assertNotNull(sample.completedAt)
        assertFalse(sink.persistedText().contains(credentialValue))
        assertFalse(sink.persistedText().contains(rawBody))
        assertFalse(sink.persistedText().contains(PROMPT_MARKER))
    }

    @Test
    fun engineRecordsSuccessWithStampedTimingsAndUnknownUsage() = runBlocking {
        server.enqueue(streamResponse(
            """{"choices":[{"index":0,"delta":{"role":"assistant"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"Hello"}}]}""",
        ))
        val sink = RecordingTelemetry()
        val events = mutableListOf<StreamEvent>()

        InferenceEngine(transport(), sink, SystemClock)
            .execute(target(), fixtureMessages())
            .collect { events += it }
        val sample = sink.last()

        assertEquals(Outcome.SUCCESS, sample.outcome)
        assertEquals(200, sample.httpStatus)
        assertEquals(Usage(), sample.usage)
        assertNull(sample.tps)
        assertNotNull(sample.ttftMs)
        assertNotNull(sample.ttfbMs)
        assertTrue(sample.ttftMs!! >= sample.ttfbMs!!)
        assertEquals("safe-credential-fixture", sample.safeCredentialId)
        assertTrue(events.any { it is StreamEvent.RequestSent })
        assertEquals(StreamEvent.Finished, events.last())
    }

    @Test
    fun engineRecordsRateLimitWithoutRetryingTheProvider() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Content-Type", "application/json")
            .setHeader("Retry-After", "30")
            .setBody("""{"error":"rate limited"}"""))
        val sink = RecordingTelemetry()

        val failure = captureInferenceFailure {
            InferenceEngine(transport(), sink, SystemClock)
                .execute(target(), fixtureMessages())
                .collect { }
        }
        val sample = sink.last()

        assertEquals(Outcome.RATE_LIMIT, failure?.outcome)
        assertEquals(429, sample.httpStatus)
        assertEquals("30", sample.rateLimitHeaders["retry-after"])
        assertEquals(0, sample.retryCount)
        assertEquals(1, server.requestCount)
    }
}
