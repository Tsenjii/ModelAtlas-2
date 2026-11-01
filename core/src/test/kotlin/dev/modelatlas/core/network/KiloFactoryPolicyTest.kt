package dev.modelatlas.core.network

import dev.modelatlas.core.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import dev.modelatlas.core.domain.EndpointType
import kotlinx.coroutines.flow.flowOf

class KiloFactoryPolicyTest {
    private val row = """{"id":"model:free","isFree":true,"pricing":{"prompt":"0","completion":"0"}}"""
    private fun catalog(value: String = row) = """{"data":[$value]}"""
    private fun target(server: MockWebServer) = fixtureTarget("http://127.0.0.1:${server.port}/api/v1/chat/completions").copy(
        providerId = KiloFactoryPolicy.PROVIDER, accountId = KiloFactoryPolicy.PROVIDER, credentialId = null,
        telemetryCredentialId = null, modelId = "model:free", group = null,
    )

    @Test fun publicClaimsNeedExactFreeFlagEveryPriceAndUniqueUnexpiredModel() {
        fun permit(raw: String) = KiloFactoryPolicy.permits(Json.parseToJsonElement(raw).jsonObject, "model:free", System.currentTimeMillis())
        assertTrue(permit(catalog()))
        assertFalse(permit(catalog(row.replace("\"completion\":\"0\"", "\"completion\":\"0.1\""))))
        assertFalse(permit(catalog(row.replace("\"completion\":\"0\"", "\"completion\":\"0\",\"request\":\"1\""))))
        assertFalse(permit(catalog(row.replace("true", "\"true\""))))
        assertFalse(permit(catalog(row.dropLast(1) + ",\"expiration_date\":\"2020-01-01\"}")))
        assertFalse(permit(catalog("$row,$row")))
        assertFalse(permit(catalog("")))
        assertFalse(permit(catalog("""{"id":"model:free","isFree":true,"pricing":{"prompt":"0"}}""")))
    }

    @Test fun realTransportReadsCatalogThenStreamsWithoutAnyCredentialAccess() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(catalog()))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"factory OK\"}}]}\n\ndata: [DONE]\n\n"))
            val transport = OpenAiTransport({ _, _ -> error("anonymous target must not access vault") }, allowLoopbackHttpForTests = true)
            val events = transport.stream(target(server), fixtureMessages()).toList()
            assertEquals("factory OK", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertEquals(2, server.requestCount)
            val get = server.takeRequest(); val post = server.takeRequest()
            assertEquals("GET", get.method); assertEquals("/api/v1/models", get.path); assertEquals(0L, get.bodySize)
            assertEquals("POST", post.method)
            for (request in listOf(get, post)) { assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie")) }
            assertEquals(1, events.filterIsInstance<StreamEvent.RequestSent>().size)
        } finally { server.shutdown() }
    }

    @Test fun changedMissingRedirectedAndOfflineCatalogsNeverIssueInferenceOrFallback() = runBlocking {
        for (response in listOf(
            MockResponse().setBody(catalog(row.replace("true", "false"))),
            MockResponse().setBody(catalog("")),
            MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/paid"),
            MockResponse().setResponseCode(503),
            MockResponse().setBody("malformed JSON"),
        )) {
            val server = MockWebServer(); server.start()
            try {
                server.enqueue(response)
                val failure = runCatching { OpenAiTransport({ _, _ -> error("vault must not be used") }, allowLoopbackHttpForTests = true).stream(target(server), fixtureMessages()).toList() }.exceptionOrNull()
                assertTrue(failure is InferenceFailure)
                assertEquals(1, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
            } finally { server.shutdown() }
        }
    }

    @Test fun cancellingCatalogReadClosesTheSocketAndNeverStartsModelRequest() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val flow = OpenAiTransport({ _, _ -> error("vault must not be used") }, allowLoopbackHttpForTests = true)
            val job = launch(Dispatchers.IO) { flow.stream(target(server), fixtureMessages()).toList() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(3000) { job.cancelAndJoin() }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun personalProvidersKeepTheirOriginalRequestWorkload() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
            val transport = OpenAiTransport({ _, _ -> "synthetic-private-fixture-key" }, allowLoopbackHttpForTests = true)
            transport.stream(fixtureTarget("http://127.0.0.1:${server.port}/v1/chat/completions"), fixtureMessages()).toList()
            assertEquals(1, server.requestCount); assertEquals("POST", server.takeRequest().method)
        } finally { server.shutdown() }
    }

    @Test fun changingFactoryProtocolAccountAuthOrOriginCannotBypassGuardThroughAnotherTransport() = runBlocking {
        var calls = 0
        val dispatch = TransportDispatch(listOf(object : EndpointTransport {
            override val supportedTypes = setOf(EndpointType.RESPONSES, EndpointType.CHAT_COMPLETIONS)
            override fun stream(target: InferenceTarget, messages: List<PromptMessage>) = flowOf<StreamEvent>(StreamEvent.Finished).also { calls++ }
        }))
        val original = fixtureTarget(KiloFactoryPolicy.BASE_URL + "/chat/completions").copy(
            providerId = KiloFactoryPolicy.PROVIDER, accountId = KiloFactoryPolicy.PROVIDER,
            credentialId = null, telemetryCredentialId = null, group = null,
        )
        for (changed in listOf(
            original.copy(operation = "RESPONSES"), original.copy(accountId = "another-account"),
            original.copy(credentialId = "private-key"), original.copy(group = "paid"),
            original.copy(endpointUrl = "https://example.com/chat/completions"),
        )) {
            val error = runCatching { dispatch.stream(changed, fixtureMessages()).toList() }.exceptionOrNull()
            assertTrue(error is InferenceFailure); assertEquals(Outcome.CREDENTIAL_SCOPE, (error as InferenceFailure).outcome)
        }
        assertEquals(0, calls)
    }
}
