package dev.modelatlas.core.network

import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.captureIllegalArgument
import dev.modelatlas.core.inference.fixtureMessages
import dev.modelatlas.core.inference.fixtureTarget
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TEST_TIMEOUT_MS = 20_000L
private const val JOIN_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_SECONDS = 2L
private const val CHAT_PATH = "/v1/chat/completions"
private const val VAULT_REFUSAL = "VAULT_REFUSED_THIS_REF_IS_BOUND_TO_ANOTHER_ORIGIN"

private const val BEARER_REF = "safe-credential-bearer-fixture"
private const val HEADER_REF = "safe-credential-header-fixture"
private const val QUERY_REF = "safe-credential-query-fixture"
private const val AUTH_REF = "safe-credential-authorization-fixture"
private const val MALFORMED_REF = "safe-credential-malformed-fixture"
private const val MAX_REF = "safe-credential-max-fixture"
private const val ECHO_HEADER_REF = "safe-credential-echo-header-fixture"
private const val ECHO_QUERY_REF = "safe-credential-echo-query-fixture"
private const val ECHO_QUERY2_REF = "safe-credential-echo-query-2-fixture"

private const val BEARER_SECRET = "fixture-bearer-secret-ZZ99XX88"
private const val HEADER_SECRET = "fixture-header-secret-A1B2C3D4"
/** Contains a space, a slash and an equals sign: raw concatenation would corrupt the request line. */
private const val QUERY_SECRET = "fixture/query secret=ABCDEF0123456789"
private const val AUTH_SECRET = "fixture-authorization-secret-KK77LL88"
private const val ECHO_HEADER_SECRET = "fixture-echo-header-secret=ABCDEF0123456789"
private const val ECHO_QUERY_SECRET = "fixture/first-secret=ABCDEF0123456789"
private const val ECHO_QUERY2_SECRET = "fixture+second-secret-ZZ99YY88XX"

private const val MAX_FIELD_CHARS = 8192
private const val REDACTED = "[REDACTED]"

/**
 * Custom auth fields must stay a safe-ref boundary: the transport resolves every ref against the
 * endpoint origin, sends the value as a header or an encoded query parameter, refuses names and
 * values that could forge framing, and never follows a redirect that would carry a secret away.
 *
 * Every request in this class is loopback-only and reaches [MockWebServer]; no real provider and no
 * real key is ever used, and every fixture value is a synthetic constant defined above.
 */
class CustomAuthTransportTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Origin-bound safe-ref vault double. It records the (ref, origin) pairs the transport asked for
     * and refuses any other origin, which is what a real origin-bound vault does.
     */
    private class Vault(
        private val values: Map<String, String>,
        private val boundOrigin: String,
    ) : CredentialAccess {
        val asked: MutableList<String> = mutableListOf()

        override fun bearerToken(credentialId: String, origin: String): String {
            asked += "$credentialId@$origin"
            if (origin != boundOrigin) throw IllegalStateException(VAULT_REFUSAL)
            return values[credentialId] ?: throw IllegalStateException(VAULT_REFUSAL)
        }
    }

    private data class Attempt(val events: List<StreamEvent>, val failure: Throwable?)

    private fun serverOrigin(mock: MockWebServer = server): String = "http://127.0.0.1:${mock.port}"

    private fun loopbackUrl(path: String = CHAT_PATH, mock: MockWebServer = server): String =
        "${serverOrigin(mock)}$path"

    private fun transport(vault: CredentialAccess, loopbackEscapeHatch: Boolean = true) =
        OpenAiTransport(vault, allowLoopbackHttpForTests = loopbackEscapeHatch)

    private fun target(
        authFields: List<InferenceAuthField>,
        credentialId: String? = null,
        url: String = loopbackUrl(),
    ) = fixtureTarget(url).copy(credentialId = credentialId, authFields = authFields)

    private fun headerField(ref: String) = InferenceAuthField("X-Api-Key", ref)

    private fun queryField(ref: String, name: String = "api-key") = InferenceAuthField(name, ref, query = true)

    private fun sse(vararg payloads: String): String =
        payloads.joinToString(separator = "") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun streamResponse(vararg payloads: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(sse(*payloads))

    private fun hi(): String = """{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""

    private suspend fun eventsOf(flow: Flow<StreamEvent>): List<StreamEvent> =
        withTimeout(TEST_TIMEOUT_MS) { flow.toList() }

    private suspend fun thrownBy(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e
    }

    private suspend fun attempt(flow: Flow<StreamEvent>): Attempt = withTimeout(TEST_TIMEOUT_MS) {
        val events = mutableListOf<StreamEvent>()
        Attempt(events, thrownBy { flow.collect { events += it } })
    }

    private fun recorded(mock: MockWebServer = server): RecordedRequest =
        requireNotNull(mock.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "no request reached the server"
        }

    private fun List<StreamEvent>.headerEvent(): StreamEvent.Headers =
        filterIsInstance<StreamEvent.Headers>().single()

    private fun List<StreamEvent>.text(): String =
        filterIsInstance<StreamEvent.Delta>().joinToString(separator = "") { it.text }

    /** Bounded wait, so a request that never arrives fails the test instead of hanging the suite. */
    private suspend fun awaitRequest(count: Int = 1) {
        val deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS
        while (server.requestCount < count && System.currentTimeMillis() < deadline) delay(25L)
        assertEquals(count, server.requestCount)
    }

    @Test
    fun customHeaderFieldIsSentFromItsOwnSafeRefAndStaysOutOfTheQuery() = runBlocking {
        val vault = Vault(mapOf(HEADER_REF to HEADER_SECRET), serverOrigin())
        server.enqueue(streamResponse(hi()))

        val events = eventsOf(transport(vault).stream(target(listOf(headerField(HEADER_REF))), fixtureMessages()))
        val request = recorded()

        assertEquals("Hi", events.text())
        assertEquals(StreamEvent.Finished, events.last())
        assertEquals(HEADER_SECRET, request.getHeader("X-Api-Key"))
        assertNull(request.requestUrl!!.queryParameter("X-Api-Key"))
        assertEquals(CHAT_PATH, request.requestUrl!!.encodedPath)
        // The safe ref was resolved against the endpoint origin, and the ref is all we ever pass.
        assertEquals(listOf("$HEADER_REF@${serverOrigin()}"), vault.asked)
    }

    @Test
    fun customQueryFieldIsPercentEncodedAndRoundTripsToTheOriginalValue() = runBlocking {
        val vault = Vault(mapOf(QUERY_REF to QUERY_SECRET), serverOrigin())
        server.enqueue(streamResponse(hi()))

        eventsOf(transport(vault).stream(target(listOf(queryField(QUERY_REF))), fixtureMessages()))
        val request = recorded()

        assertEquals(QUERY_SECRET, request.requestUrl!!.queryParameter("api-key"))
        val encoded = request.requestUrl!!.encodedQuery.orEmpty()
        // Encoding, not raw concatenation: reserved bytes are escaped in the request line.
        assertTrue(encoded.contains("%2F"))
        assertTrue(encoded.contains("%3D"))
        assertTrue(encoded.contains("%20"))
        assertTrue(request.path!!.startsWith("$CHAT_PATH?api-key="))
        assertFalse(request.path!!.contains(" "))
        assertFalse(request.path!!.contains(QUERY_SECRET))
        assertNull(request.getHeader("api-key"))
        assertNull(request.getHeader("X-Api-Key"))
    }

    @Test
    fun bearerCredentialAndCustomFieldsAreSentSideBySideWithoutOverwritingEachOther() = runBlocking {
        val vault = Vault(
            mapOf(BEARER_REF to BEARER_SECRET, HEADER_REF to HEADER_SECRET, QUERY_REF to QUERY_SECRET),
            serverOrigin(),
        )
        server.enqueue(streamResponse(hi()))

        eventsOf(
            transport(vault).stream(
                target(listOf(headerField(HEADER_REF), queryField(QUERY_REF)), credentialId = BEARER_REF),
                fixtureMessages(),
            ),
        )
        val request = recorded()

        assertEquals("Bearer $BEARER_SECRET", request.getHeader("Authorization"))
        assertEquals(1, request.headers.values("Authorization").size)
        assertEquals(HEADER_SECRET, request.getHeader("X-Api-Key"))
        assertEquals(QUERY_SECRET, request.requestUrl!!.queryParameter("api-key"))
        // The custom value is its own secret: it neither borrows nor is borrowed from the bearer.
        assertFalse(request.getHeader("X-Api-Key").orEmpty().contains(BEARER_SECRET))
        assertFalse(request.requestUrl!!.queryParameter("api-key").orEmpty().contains(BEARER_SECRET))
        assertEquals(
            listOf(
                "$BEARER_REF@${serverOrigin()}",
                "$HEADER_REF@${serverOrigin()}",
                "$QUERY_REF@${serverOrigin()}",
            ),
            vault.asked,
        )
    }

    @Test
    fun authorizationFieldRequiresExplicitConfirmationHeaderPlacementAndNoBearer() = runBlocking {
        val vault = Vault(mapOf(AUTH_REF to AUTH_SECRET, BEARER_REF to BEARER_SECRET), serverOrigin())
        // Confirmed and anonymous is the single allowed spelling; see
        // confirmedAuthorizationFieldSendsTheBareValueOnlyWhenNoBearerIsPresent.
        val rejected = listOf(
            InferenceAuthField("Authorization", AUTH_REF),
            // Case-insensitive detection: a lower case spelling is the same header.
            InferenceAuthField("authorization", AUTH_REF),
            // Confirmed, but a query parameter is not a header slot.
            InferenceAuthField("Authorization", AUTH_REF, query = true, overrideConfirmed = true),
        )
        for ((index, field) in rejected.withIndex()) {
            val anonymous = target(listOf(field))
            assertNotNull("case $index must be rejected", captureIllegalArgument {
                eventsOf(transport(vault).stream(anonymous, fixtureMessages()))
            })
            val withBearer = target(listOf(field), credentialId = BEARER_REF)
            assertNotNull("case $index with a bearer must be rejected", captureIllegalArgument {
                eventsOf(transport(vault).stream(withBearer, fixtureMessages()))
            })
        }
        // Confirmed Authorization with no bearer is the single allowed spelling
        // (see confirmedAuthorizationFieldSendsTheBareValueOnlyWhenNoBearerIsPresent);
        // with a bearer it must still be rejected since the header is owned.
        val confirmed = InferenceAuthField("Authorization", AUTH_REF, overrideConfirmed = true)
        val withBearer = target(listOf(confirmed), credentialId = BEARER_REF)
        assertNotNull("confirmed with a bearer must be rejected", captureIllegalArgument {
            eventsOf(transport(vault).stream(withBearer, fixtureMessages()))
        })
        assertEquals(0, server.requestCount)
        // A rejected field is rejected before its own ref is resolved: only the bearer was read.
        assertTrue(vault.asked.isNotEmpty())
        assertTrue(vault.asked.all { it.startsWith("$BEARER_REF@") })
    }

    @Test
    fun confirmedAuthorizationFieldSendsTheBareValueOnlyWhenNoBearerIsPresent() = runBlocking {
        val vault = Vault(mapOf(AUTH_REF to AUTH_SECRET), serverOrigin())
        server.enqueue(streamResponse(hi()))

        val field = InferenceAuthField("Authorization", AUTH_REF, overrideConfirmed = true)
        eventsOf(transport(vault).stream(target(listOf(field)), fixtureMessages()))
        val request = recorded()

        assertEquals(AUTH_SECRET, request.getHeader("Authorization"))
        assertFalse(request.getHeader("Authorization").orEmpty().startsWith("Bearer "))
        assertEquals(1, request.headers.values("Authorization").size)
        assertEquals(listOf("$AUTH_REF@${serverOrigin()}"), vault.asked)
    }

    @Test
    fun anonymousTargetWithCustomAuthSendsNoAuthorizationHeaderAtAll() = runBlocking {
        val vault = Vault(mapOf(HEADER_REF to HEADER_SECRET, QUERY_REF to QUERY_SECRET), serverOrigin())
        server.enqueue(streamResponse(hi()))

        val fields = listOf(headerField(HEADER_REF), queryField(QUERY_REF))
        eventsOf(transport(vault).stream(target(fields), fixtureMessages()))
        val request = recorded()

        assertNull(request.getHeader("Authorization"))
        assertEquals(HEADER_SECRET, request.getHeader("X-Api-Key"))
        assertEquals(QUERY_SECRET, request.requestUrl!!.queryParameter("api-key"))
    }

    @Test
    fun forbiddenHeaderNamesAreRejectedBeforeAnyRequestAndBeforeAnyRefIsResolved() = runBlocking {
        val vault = Vault(mapOf(MALFORMED_REF to HEADER_SECRET), serverOrigin())
        val forbidden = listOf(
            "Cookie", "cookie", "Host", "host", "Content-Type", "Content-Length",
            "Transfer-Encoding", "Connection", "Proxy-Authorization", "Proxy-Connection",
            "Accept", "Te", "Trailer", "Upgrade",
        )

        for (name in forbidden) {
            val forbiddenTarget = target(listOf(InferenceAuthField(name, MALFORMED_REF)))
            assertNotNull("expected $name to be rejected", captureIllegalArgument {
                eventsOf(transport(vault).stream(forbiddenTarget, fixtureMessages()))
            })
        }
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), vault.asked)
    }

    @Test
    fun unsafeHeaderNamesAreRejectedBeforeAnyRequestAndBeforeAnyRefIsResolved() = runBlocking {
        val vault = Vault(mapOf(MALFORMED_REF to HEADER_SECRET), serverOrigin())
        val unsafe = listOf(
            "", " ", "X Api Key", "X-Api-Key: Injected", "X-Api-Key\r\nX-Injected: 1",
            "X-Api-Key\nX-Injected: 1", "X/Api-Key", "X-Api-Key\u0000", "X-Api-K\u00e9y",
        )

        for (name in unsafe) {
            val unsafeTarget = target(listOf(InferenceAuthField(name, MALFORMED_REF)))
            assertNotNull("expected '$name' to be rejected", captureIllegalArgument {
                eventsOf(transport(vault).stream(unsafeTarget, fixtureMessages()))
            })
        }
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), vault.asked)
    }

    @Test
    fun controlCharactersBlankAndOversizedValuesAreRejectedBeforeAnyRequest() = runBlocking {
        val crlf = "fixture-value\r\nX-Injected: 1"
        val newline = "fixture-value\nX-Injected: 1"
        val nul = "fixture-value\u0000tail"
        val values = mapOf(
            HEADER_REF to crlf,
            QUERY_REF to newline,
            AUTH_REF to nul,
            MALFORMED_REF to "   ",
            MAX_REF to "x".repeat(MAX_FIELD_CHARS + 1),
        )
        for ((ref, value) in values) {
            val vault = Vault(mapOf(ref to value), serverOrigin())
            val field = if (ref == QUERY_REF) queryField(ref) else headerField(ref)
            assertNotNull("expected the value of $ref to be rejected", captureIllegalArgument {
                eventsOf(transport(vault).stream(target(listOf(field)), fixtureMessages()))
            })
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun aValueAtTheLengthCeilingIsStillSent() = runBlocking {
        val value = "y".repeat(MAX_FIELD_CHARS)
        val vault = Vault(mapOf(MAX_REF to value), serverOrigin())
        server.enqueue(streamResponse(hi()))

        eventsOf(transport(vault).stream(target(listOf(headerField(MAX_REF))), fixtureMessages()))

        assertEquals(value, recorded().getHeader("X-Api-Key"))
    }

    @Test
    fun duplicateHeaderTargetIsRejectedBeforeTheRefIsResolved() = runBlocking {
        val vault = Vault(mapOf(HEADER_REF to HEADER_SECRET), serverOrigin())
        // Same placement, spelled differently: the last one would silently win.
        val fields = listOf(headerField(HEADER_REF), InferenceAuthField("x-api-key", HEADER_REF))

        val failure = captureIllegalArgument {
            eventsOf(transport(vault).stream(target(fields), fixtureMessages()))
        }

        assertNotNull(failure)
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), vault.asked)
    }

    @Test
    fun theSameNameInHeaderAndQueryPlacementIsSentToBothPlaces() = runBlocking {
        val vault = Vault(mapOf(HEADER_REF to HEADER_SECRET, QUERY_REF to QUERY_SECRET), serverOrigin())
        server.enqueue(streamResponse(hi()))
        val fields = listOf(InferenceAuthField("X-Api-Key", HEADER_REF), queryField(QUERY_REF, "X-Api-Key"))

        eventsOf(transport(vault).stream(target(fields), fixtureMessages()))
        val request = recorded()

        assertEquals(HEADER_SECRET, request.getHeader("X-Api-Key"))
        assertEquals(QUERY_SECRET, request.requestUrl!!.queryParameter("X-Api-Key"))
    }

    @Test
    fun everySafeRefIsResolvedAgainstTheEndpointOriginOnly() = runBlocking {
        val vault = Vault(
            mapOf(BEARER_REF to BEARER_SECRET, HEADER_REF to HEADER_SECRET, QUERY_REF to QUERY_SECRET),
            serverOrigin(),
        )
        server.enqueue(streamResponse(hi()))

        val fields = listOf(queryField(QUERY_REF), headerField(HEADER_REF))
        eventsOf(transport(vault).stream(target(fields, credentialId = BEARER_REF), fixtureMessages()))

        val origin = serverOrigin()
        assertEquals(3, vault.asked.size)
        assertTrue(vault.asked.all { it.endsWith("@$origin") })
    }

    @Test
    fun aRefBoundToAnotherOriginSendsNoRequestAtAll() = runBlocking {
        // Same loopback host but a different port, and a different host: both are foreign origins.
        val foreign = listOf("http://127.0.0.1:1", "http://other.fixture.invalid", "https://127.0.0.1:443")
        for (boundOrigin in foreign) {
            val vault = Vault(mapOf(BEARER_REF to BEARER_SECRET, HEADER_REF to HEADER_SECRET), boundOrigin)
            val fields = listOf(headerField(HEADER_REF))

            val failure = thrownBy {
                eventsOf(transport(vault).stream(target(fields, credentialId = BEARER_REF), fixtureMessages()))
            }

            assertTrue(failure is IllegalStateException)
            assertEquals(VAULT_REFUSAL, failure?.message)
            assertEquals(0, server.requestCount)
            assertTrue(vault.asked.isNotEmpty())
            assertTrue(vault.asked.all { it.endsWith("@${serverOrigin()}") })
            assertFalse(failure?.message.orEmpty().contains(BEARER_SECRET))
            assertFalse(failure?.message.orEmpty().contains(HEADER_SECRET))
        }
    }

    @Test
    fun redirectIsNotFollowedAndNoCustomSecretReachesTheRedirectTarget() = runBlocking {
        val redirectTarget = MockWebServer()
        redirectTarget.start()
        try {
            val vault = Vault(
                mapOf(BEARER_REF to BEARER_SECRET, HEADER_REF to HEADER_SECRET, QUERY_REF to QUERY_SECRET),
                serverOrigin(),
            )
            server.enqueue(MockResponse()
                .setResponseCode(302)
                .setHeader("Location", loopbackUrl(CHAT_PATH, redirectTarget)))

            val result = attempt(
                transport(vault).stream(
                    target(listOf(headerField(HEADER_REF), queryField(QUERY_REF)), credentialId = BEARER_REF),
                    fixtureMessages(),
                ),
            )
            val request = recorded()

            assertTrue(result.failure is InferenceFailure)
            assertEquals(Outcome.OTHER, (result.failure as InferenceFailure).outcome)
            assertEquals(302, (result.failure as InferenceFailure).status)
            assertEquals(302, result.events.headerEvent().status)
            assertEquals(1, server.requestCount)
            assertEquals(0, redirectTarget.requestCount)
            // The secrets did reach the origin the user configured, and nowhere else.
            assertEquals(HEADER_SECRET, request.getHeader("X-Api-Key"))
            assertEquals(QUERY_SECRET, request.requestUrl!!.queryParameter("api-key"))
        } finally {
            redirectTarget.shutdown()
        }
    }

    @Test
    fun everyResolvedSecretIsRedactedFromEchoedDeltasModelIdAndRateLimitHeaders() = runBlocking {
        val urlEncoded = URLEncoder.encode(ECHO_QUERY_SECRET, Charsets.UTF_8.name())
        val base64 = Base64.getEncoder().encodeToString(ECHO_QUERY2_SECRET.toByteArray(Charsets.UTF_8))
        assertTrue(urlEncoded != ECHO_QUERY_SECRET && base64 != ECHO_QUERY2_SECRET)
        val vault = Vault(
            mapOf(
                BEARER_REF to BEARER_SECRET,
                ECHO_HEADER_REF to ECHO_HEADER_SECRET,
                ECHO_QUERY_REF to ECHO_QUERY_SECRET,
                ECHO_QUERY2_REF to ECHO_QUERY2_SECRET,
            ),
            serverOrigin(),
        )
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setHeader("Retry-After", ECHO_HEADER_SECRET)
            .setHeader("X-RateLimit-Remaining-Tokens", "7")
            .setHeader("X-Provider-Debug", BEARER_SECRET)
            .setBody(sse("""{"model":"$ECHO_QUERY_SECRET","choices":[{"index":0,"delta":{"content":"raw=$ECHO_HEADER_SECRET encoded=$urlEncoded wrapped=$base64"}}]}""")))
        val fields = listOf(
            headerField(ECHO_HEADER_REF),
            queryField(ECHO_QUERY_REF, "api-key"),
            queryField(ECHO_QUERY2_REF, "token"),
        )

        val events = eventsOf(transport(vault).stream(target(fields, credentialId = BEARER_REF), fixtureMessages()))
        val rendered = events.toString()

        val secrets = listOf(BEARER_SECRET, ECHO_HEADER_SECRET, ECHO_QUERY_SECRET, ECHO_QUERY2_SECRET, urlEncoded, base64)
        for (secret in secrets) assertFalse("leaked: $secret", rendered.contains(secret))
        assertTrue(rendered.contains(REDACTED))
        assertEquals(REDACTED, events.filterIsInstance<StreamEvent.ResponseModel>().single().id)
        val header = events.headerEvent()
        assertEquals(REDACTED, header.rateLimits["retry-after"])
        assertEquals("7", header.rateLimits["x-ratelimit-remaining-tokens"])
        assertEquals(2, header.rateLimits.size)
        assertTrue(events.text().contains(REDACTED))
    }

    @Test
    fun unauthorizedResponseNeverCarriesAnyResolvedSecret() = runBlocking {
        val vault = Vault(
            mapOf(
                BEARER_REF to BEARER_SECRET,
                ECHO_HEADER_REF to ECHO_HEADER_SECRET,
                ECHO_QUERY_REF to ECHO_QUERY_SECRET,
            ),
            serverOrigin(),
        )
        val body = """{"error":{"message":"bearer=$BEARER_SECRET header=$ECHO_HEADER_SECRET query=$ECHO_QUERY_SECRET"}}"""
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setHeader("Retry-After", ECHO_QUERY_SECRET)
            .setHeader("X-Provider-Debug", ECHO_HEADER_SECRET)
            .setBody(body))
        val fields = listOf(headerField(ECHO_HEADER_REF), queryField(ECHO_QUERY_REF, "api-key"))

        val result = attempt(transport(vault).stream(target(fields, credentialId = BEARER_REF), fixtureMessages()))

        assertTrue(result.failure is InferenceFailure)
        val failure = result.failure as InferenceFailure
        assertEquals(Outcome.AUTH, failure.outcome)
        assertEquals(401, failure.status)
        val secrets = listOf(BEARER_SECRET, ECHO_HEADER_SECRET, ECHO_QUERY_SECRET)
        for (secret in secrets) {
            assertFalse(failure.message.orEmpty().contains(secret))
            assertFalse(result.events.toString().contains(secret))
        }
        val header = result.events.headerEvent()
        assertEquals(401, header.status)
        assertEquals(REDACTED, header.rateLimits["retry-after"])
        assertEquals(1, header.rateLimits.size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun loopbackEscapeHatchStillRejectsARemoteHostWithCustomAuth() = runBlocking {
        val vault = Vault(mapOf(HEADER_REF to HEADER_SECRET), "https://api.fixture.invalid")
        val remote = target(listOf(headerField(HEADER_REF)), url = "http://api.fixture.invalid$CHAT_PATH")

        val failure = captureIllegalArgument {
            eventsOf(transport(vault).stream(remote, fixtureMessages()))
        }

        assertNotNull(failure)
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), vault.asked)
    }

    @Test
    fun withoutTheEscapeHatchEvenLoopbackHttpIsRejected() = runBlocking {
        val vault = Vault(mapOf(HEADER_REF to HEADER_SECRET), serverOrigin())
        val fields = listOf(headerField(HEADER_REF))

        val failure = captureIllegalArgument {
            eventsOf(transport(vault, loopbackEscapeHatch = false).stream(target(fields), fixtureMessages()))
        }

        assertNotNull(failure)
        assertEquals("HTTPS required", failure?.message)
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), vault.asked)
    }

    @Test
    fun cancellingARequestWithCustomAuthUnblocksPromptly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val vault = Vault(mapOf(HEADER_REF to HEADER_SECRET), serverOrigin())
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            transport(vault).stream(target(listOf(headerField(HEADER_REF))), fixtureMessages()).collect { }
        }
        awaitRequest()

        job.cancel()
        withTimeout(JOIN_TIMEOUT_MS) { job.join() }

        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }
}
