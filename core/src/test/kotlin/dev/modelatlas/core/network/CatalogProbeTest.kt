package dev.modelatlas.core.network

import dev.modelatlas.core.inference.CredentialAccess
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CatalogProbeTest {
    private lateinit var server: MockWebServer
    private val credentialValue = "fixture-catalog-token-must-not-leak"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun probe(
        value: String? = credentialValue,
        origins: MutableList<String> = mutableListOf(),
    ) = CatalogProbe(
        credentials = CredentialAccess { credentialId, origin ->
            origins += "$credentialId@$origin"
            value ?: ""
        },
        allowLoopbackHttpForTests = true,
    )

    private fun baseUrl(path: String = "/v1"): String = "http://127.0.0.1:${server.port}$path"

    private fun catalog(vararg ids: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            buildString {
                append("""{"object":"list","data":[""")
                ids.forEachIndexed { index, id ->
                    if (index > 0) append(',')
                    append("""{"id":${quote(id)},"object":"model"}""")
                }
                append("]}")
            },
        )

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(char)
            }
        }
        append('"')
    }

    /** Waits until the request has actually reached the server, so cancellation is not a race. */
    private suspend fun awaitRequest() {
        val deadline = System.currentTimeMillis() + 5_000L
        while (server.requestCount == 0 && System.currentTimeMillis() < deadline) delay(50L)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun inspectIssuesHeadWithoutCredentialAndStaysUnauthenticated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"))

        val result = probe().inspect(baseUrl())
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals(ProbeStatus.OK, result.status)
        assertEquals(200, result.httpStatus)
        assertEquals("HEAD", recorded.method)
        assertEquals("/v1", recorded.path)
        assertNull(recorded.getHeader("Authorization"))
        assertFalse(result.authVerified)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun inspectUnauthorizedMeansReachableButNotAuthenticated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer realm=\"fixture\""))

        val result = probe().inspect(baseUrl())

        assertEquals(ProbeStatus.HTTP_UNAUTHORIZED, result.status)
        assertEquals(401, result.httpStatus)
        assertFalse(result.authVerified)
    }

    @Test
    fun inspectNotFoundIsStillReachable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = probe().inspect(baseUrl())

        assertEquals(ProbeStatus.HTTP_NOT_FOUND, result.status)
        assertEquals(404, result.httpStatus)
        assertFalse(result.authVerified)
    }

    @Test
    fun inspectNeverClaimsAProviderTypeWithoutAnExplicitSignal() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Server", "nginx"))

        val result = probe().inspect(baseUrl())

        assertEquals(ProviderTypeFingerprint.UNKNOWN, result.providerType)
    }

    @Test
    fun inspectKeepsOnlyAShortPlainMediaTypeAsEvidence() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/html; charset=utf-8"))

        val result = probe().inspect(baseUrl())

        assertEquals("content-type:text/html", result.typeEvidence)
        assertEquals(ProviderTypeFingerprint.UNKNOWN, result.providerType)
    }

    @Test
    fun inspectDropsEvidenceThatIsNotAPlainMediaType() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/html \"$credentialValue\""))

        val result = probe().inspect(baseUrl())

        assertNull(result.typeEvidence)
        assertFalse(result.toString().contains(credentialValue))
    }

    @Test
    fun nonHttpsInputIsRejectedByEndpointPolicy() = runBlocking {
        val result = CatalogProbe({ _, _ -> "" }).inspect("http://api.fixture.invalid/v1")

        assertEquals(ProbeStatus.INPUT_INVALID, result.status)
        assertNull(result.httpStatus)
    }

    @Test
    fun loopbackEscapeHatchRejectsRemoteHost() = runBlocking {
        val result = probe().inspect("http://api.fixture.invalid/v1")

        assertEquals(ProbeStatus.INPUT_INVALID, result.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun listModelsReadsTheOpenAiDataArrayAndBindsCredentialToOrigin() = runBlocking {
        server.enqueue(catalog("fixture-model-a", "fixture-model-b"))
        val origins = mutableListOf<String>()

        val result = probe(origins = origins).listModels(baseUrl(), "safe-credential-fixture")
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals(CatalogStatus.OK, result.status)
        assertEquals(200, result.httpStatus)
        assertEquals(listOf("fixture-model-a", "fixture-model-b"), result.modelIds)
        assertEquals(2, result.totalCount)
        assertFalse(result.truncated)
        assertEquals(ProviderTypeFingerprint.OPENAI_COMPATIBLE, result.providerType)
        assertEquals("safe-credential-fixture@http://127.0.0.1:${server.port}", origins.single())
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer $credentialValue", recorded.getHeader("Authorization"))
    }

    @Test
    fun listModelsDerivesModelsPathFromABaseEndingInChatCompletions() = runBlocking {
        server.enqueue(catalog("fixture-model-a"))

        probe().listModels(baseUrl("/v1/chat/completions"), "safe-credential-fixture")
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals("/v1/models", recorded.path)
    }

    @Test
    fun listModelsKeepsTheBasePathAndDoesNotDoubleIt() = runBlocking {
        server.enqueue(catalog("fixture-model-a"))

        probe().listModels("${baseUrl("/v1/")}chat/completions", null)
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals("/v1/models", recorded.path)
    }

    @Test
    fun anonymousListModelsSendsNoCredentialHeader() = runBlocking {
        server.enqueue(catalog("fixture-model-a"))
        val origins = mutableListOf<String>()

        val result = probe(origins = origins).listModels(baseUrl(), null)
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals(CatalogStatus.OK, result.status)
        assertNull(recorded.getHeader("Authorization"))
        assertEquals(emptyList<String>(), origins)
    }

    @Test
    fun blankCredentialFromTheVaultIsCredentialUnavailableAndSendsNoRequest() = runBlocking {
        val result = probe(value = "   ").listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.CREDENTIAL_UNAVAILABLE, result.status)
        assertNull(result.totalCount)
        assertTrue(result.modelIds.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun listModelsRejectsUnsafeInputBeforeAnyRequest() = runBlocking {
        val insecure = CatalogProbe({ _, _ -> "" }, allowLoopbackHttpForTests = true)

        val httpBase = insecure.listModels("http://api.fixture.invalid/v1", "safe-credential-fixture")
        val queryBase = insecure.listModels("https://api.fixture.invalid/v1?api-key=fixture", null)
        val userInfoBase = insecure.listModels("https://user:fixture@api.fixture.invalid/v1", null)

        assertEquals(CatalogStatus.INPUT_INVALID, httpBase.status)
        assertEquals(CatalogStatus.INPUT_INVALID, queryBase.status)
        assertEquals(CatalogStatus.INPUT_INVALID, userInfoBase.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun vaultFailureIsClassifiedWithoutEscapingItsMessage() = runBlocking {
        val leaky = "VAULT_FAILURE_MESSAGE_MUST_NOT_ESCAPE"
        val refusingVault = CatalogProbe({ _, _ -> throw IllegalStateException(leaky) }, allowLoopbackHttpForTests = true)

        val result = refusingVault.listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.CREDENTIAL_UNAVAILABLE, result.status)
        assertFalse(result.toString().contains(leaky))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun httpStatusesMapToSafeClassesAndKeepTheCode() = runBlocking {
        val cases = listOf(
            401 to CatalogStatus.HTTP_UNAUTHORIZED,
            403 to CatalogStatus.HTTP_FORBIDDEN,
            404 to CatalogStatus.HTTP_NOT_FOUND,
            429 to CatalogStatus.HTTP_TOO_MANY_REQUESTS,
            400 to CatalogStatus.HTTP_CLIENT_ERROR,
            422 to CatalogStatus.HTTP_CLIENT_ERROR,
            500 to CatalogStatus.HTTP_SERVER_ERROR,
            503 to CatalogStatus.HTTP_SERVER_ERROR,
        )
        for ((index, case) in cases.withIndex()) {
            val (status, expected) = case
            server.enqueue(MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"$credentialValue"}}"""))

            val result = probe().listModels(baseUrl(), "safe-credential-fixture")

            assertEquals(expected, result.status)
            assertEquals(status, result.httpStatus)
            // A failure is not an empty catalog: unknown never collapses to 0.
            assertNull(result.totalCount)
            assertTrue(result.modelIds.isEmpty())
            // No status is retried, so each case costs exactly one request.
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test
    fun unauthorizedBodyAndHeadersNeverReachTheResult() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setHeader("X-Provider-Debug", credentialValue)
            .setBody("""{"error":{"message":"incorrect api key: $credentialValue"}}"""))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")
        val rendered = result.toString()

        assertEquals(CatalogStatus.HTTP_UNAUTHORIZED, result.status)
        assertEquals(401, result.httpStatus)
        assertEquals(ProviderTypeFingerprint.UNKNOWN, result.providerType)
        assertFalse(rendered.contains(credentialValue))
        assertFalse(rendered.contains("incorrect api key"))
    }

    @Test
    fun rateLimitedCatalogIsNotRetried() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "30")
            .setHeader("X-RateLimit-Remaining-Tokens", "0")
            .setBody("""{"error":"slow down $credentialValue"}"""))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.HTTP_TOO_MANY_REQUESTS, result.status)
        assertEquals(429, result.httpStatus)
        assertEquals(1, server.requestCount)
        assertFalse(result.toString().contains(credentialValue))
    }

    @Test
    fun redirectIsNotFollowedAndCredentialIsNotForwarded() = runBlocking {
        val redirectTarget = MockWebServer()
        redirectTarget.start()
        try {
            server.enqueue(MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://127.0.0.1:${redirectTarget.port}/v1/models"))

            val result = probe().listModels(baseUrl(), "safe-credential-fixture")

            assertEquals(CatalogStatus.HTTP_UNSUPPORTED, result.status)
            assertEquals(302, result.httpStatus)
            assertEquals(1, server.requestCount)
            assertEquals(0, redirectTarget.requestCount)
        } finally {
            redirectTarget.shutdown()
        }
    }

    @Test
    fun serverErrorIsNotRetriedAutomatically() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.HTTP_SERVER_ERROR, result.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun emptyCatalogIsZeroAndIsNotAFailure() = runBlocking {
        server.enqueue(catalog())

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.OK, result.status)
        assertEquals(0, result.totalCount)
        assertTrue(result.modelIds.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun successfulListingNeverClaimsFreeModels() = runBlocking {
        server.enqueue(catalog("fixture-model-free", "fixture-model-paid"))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertFalse(result.freeModelsVerified)
    }

    @Test
    fun malformedJsonIsCatalogMalformed() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"id":"fixture-model-a" """))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.CATALOG_MALFORMED, result.status)
        assertEquals(200, result.httpStatus)
        assertNull(result.totalCount)
        assertTrue(result.modelIds.isEmpty())
    }

    @Test
    fun payloadWithoutDataArrayIsUnsupportedNotEmpty() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"models":["fixture-model-a"]}"""))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.CATALOG_UNSUPPORTED, result.status)
        assertNull(result.totalCount)
        assertTrue(result.modelIds.isEmpty())
    }

    @Test
    fun htmlBodyIsNotMistakenForACatalog() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body>Not Found</body></html>"))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.CATALOG_MALFORMED, result.status)
        assertNull(result.totalCount)
    }

    @Test
    fun partiallyInvalidEntriesAreSkippedAndTheCountStaysUnknown() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"data":[{"id":"fixture-model-a"},{"id":7},{"object":"model"},""" +
                    """{"id":"  "},{"id":"fixture-model-b"}]}""",
            ))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.OK, result.status)
        assertEquals(listOf("fixture-model-a", "fixture-model-b"), result.modelIds)
        // Unread entries mean the catalog size is unknown, not 5 and not 2.
        assertNull(result.totalCount)
        assertTrue(result.truncated)
    }

    @Test
    fun duplicateIdsAreCollapsedWithoutLosingTheRawCount() = runBlocking {
        server.enqueue(catalog("fixture-model-a", "fixture-model-a", "fixture-model-b"))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(listOf("fixture-model-a", "fixture-model-b"), result.modelIds)
        assertEquals(3, result.totalCount)
    }

    @Test
    fun overlongModelIdIsSkippedInsteadOfTruncatingIntoAFakeId() = runBlocking {
        val overlong = "x".repeat(257)
        server.enqueue(catalog(overlong, "fixture-model-a"))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(listOf("fixture-model-a"), result.modelIds)
        assertTrue(result.modelIds.none { it == overlong.take(256) })
        assertNull(result.totalCount)
    }

    @Test
    fun catalogIsTruncatedAtTheModelCapAndFlagsIt() = runBlocking {
        val ids = (1..1_200).map { "fixture-model-$it" }
        server.enqueue(catalog(*ids.toTypedArray()))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.OK, result.status)
        assertEquals(1_000, result.modelIds.size)
        assertEquals(1_200, result.totalCount)
        assertTrue(result.truncated)
        assertEquals("fixture-model-1", result.modelIds.first())
    }

    @Test
    fun oversizedCatalogIsRejectedWithoutBufferingIt() = runBlocking {
        val filler = "y".repeat(60)
        val ids = (1..15_000).map { "fixture-model-$it-$filler" }
        server.enqueue(catalog(*ids.toTypedArray()))

        val result = probe().listModels(baseUrl(), "safe-credential-fixture")

        assertEquals(CatalogStatus.RESPONSE_TOO_LARGE, result.status)
        assertEquals(200, result.httpStatus)
        assertTrue(result.modelIds.isEmpty())
        assertNull(result.totalCount)
    }

    @Test
    fun modelIdsNeverEchoTheCredentialOrItsCommonEncodings() = runBlocking {
        // A token with URL-unsafe characters, so the encoded forms are genuinely different text.
        val token = "fixture+secret/token=value"
        val urlBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray())
        val standardBase64 = Base64.getEncoder().encodeToString(token.toByteArray())
        val percentEncoded = URLEncoder.encode(token, Charsets.UTF_8)
        server.enqueue(
            catalog(
                token,
                "model-$token",
                "model-$urlBase64",
                "model-$standardBase64",
                "model-$percentEncoded",
                token.reversed(),
                "fixture-model-safe",
            ),
        )

        val result = probe(value = token).listModels(baseUrl(), "safe-credential-fixture")
        val rendered = result.toString()

        assertEquals(listOf("fixture-model-safe"), result.modelIds)
        assertFalse(rendered.contains(token))
        assertFalse(rendered.contains(urlBase64))
        assertFalse(rendered.contains(standardBase64))
        assertFalse(rendered.contains(percentEncoded))
        // A payload we had to redact cannot be counted honestly.
        assertNull(result.totalCount)
        assertTrue(result.truncated)
    }

    @Test
    fun inspectNeverConsultsTheCredentialVault() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"))
        val noVault = CatalogProbe({ _, _ -> error("inspect must not resolve a credential") }, allowLoopbackHttpForTests = true)

        val result = noVault.inspect(baseUrl())

        assertEquals(ProbeStatus.OK, result.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellingListModelsUnblocksPromptlyAndAbortsTheSocket() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { probe().listModels(baseUrl(), "safe-credential-fixture") }
        awaitRequest()

        job.cancel()
        // A cancelled probe may not stay parked on the socket; joining has to finish.
        withTimeout(5_000L) { job.join() }

        assertTrue(job.isCancelled)
        // The cancelled call released its connection, so a later probe still works.
        server.enqueue(catalog("fixture-model-after-cancel"))
        assertEquals(CatalogStatus.OK, probe().listModels(baseUrl(), "safe-credential-fixture").status)
    }

    @Test
    fun cancellingInspectUnblocksPromptlyToo() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { probe().inspect(baseUrl()) }
        awaitRequest()

        job.cancel()
        withTimeout(5_000L) { job.join() }

        assertTrue(job.isCancelled)
    }

    @Test
    fun unreachablePortIsClassifiedAsNetworkAndNotRetried() = runBlocking {
        val closed = MockWebServer()
        closed.start()
        val port = closed.port
        closed.shutdown()

        val result = probe().inspect("http://127.0.0.1:$port/v1")

        assertEquals(ProbeStatus.NETWORK_UNREACHABLE, result.status)
        assertNull(result.httpStatus)
    }
}
