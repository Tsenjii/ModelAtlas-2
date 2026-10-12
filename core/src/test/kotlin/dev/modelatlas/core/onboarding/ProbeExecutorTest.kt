package dev.modelatlas.core.onboarding

import dev.modelatlas.core.domain.HealthState
import dev.modelatlas.core.domain.QuotaState
import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.HangingTransport
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.RecordingTelemetry
import dev.modelatlas.core.inference.ScriptedTransport
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.network.CatalogProbe
import dev.modelatlas.core.network.OpenAiTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
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

class ProbeExecutorTest {
    private lateinit var server: MockWebServer
    private val credentialValue = "fixture-probe-token-must-not-leak"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.port}/v1"

    private fun probeWithToken(value: String = credentialValue) = CatalogProbe(
        credentials = CredentialAccess { _, _ -> value },
        allowLoopbackHttpForTests = true,
    )

    private fun probeRefusingVault() = CatalogProbe(
        credentials = CredentialAccess { _, _ -> error("inspect must not resolve a credential") },
        allowLoopbackHttpForTests = true,
    )

    private fun dummyEngine(): InferenceEngine {
        val transport = ScriptedTransport(emptyList())
        return InferenceEngine(transport, RecordingTelemetry())
    }

    private fun snapshotWithBase(url: String) = quickDraft().copy(
        baseUrl = url,
        suggestedModelIds = emptyList(),
        manualModelIds = emptyList(),
    )

    private fun catalogBody(vararg ids: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            buildString {
                append("""{"object":"list","data":[""")
                ids.forEachIndexed { index, id ->
                    if (index > 0) append(',')
                    append("""{"id":"$id","object":"model"}""")
                }
                append("]}")
            },
        )

    private fun sse(vararg payloads: String): String =
        payloads.joinToString(separator = "") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun streamOk(vararg payloads: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(sse(*payloads))

    @Test
    fun inspectUsesHeadWithoutCredential() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"))
        val executor = ProbeStepExecutor(probeRefusingVault(), dummyEngine())
        val snapshot = snapshotWithBase(baseUrl())
        val grant = RequestGrant(OnboardingStepId.INSPECT, 1, 1, 3, false)

        val result = executor.execute(snapshot, OnboardingStepId.INSPECT, grant)
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals(OnboardingStepStatus.SUCCESS, result.status)
        assertEquals(1, result.requestsSpent)
        assertEquals("HEAD", recorded.method)
        assertEquals(null, recorded.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
        assertFalse(result.toString().contains(credentialValue))
    }

    @Test
    fun detectTypeNeverGuessesWithoutFingerprint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Server", "nginx"))
        val executor = ProbeStepExecutor(probeWithToken(), dummyEngine())
        val snapshot = snapshotWithBase(baseUrl())
        val grant = RequestGrant(OnboardingStepId.DETECT_TYPE, 1, 1, 3, false)

        val result = executor.execute(snapshot, OnboardingStepId.DETECT_TYPE, grant)

        assertEquals(OnboardingStepStatus.SUCCESS, result.status)
        assertEquals(AdapterType.UNKNOWN, result.adapterType)
        assertEquals(EvidenceConfidence.LOW, result.confidence)
        assertEquals(1, result.requestsSpent)
    }

    @Test
    fun credentialTestMaps401ToAuthFailedAnd429ToRateLimited() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"bad key"}}"""),
        )
        val executor = ProbeStepExecutor(probeWithToken(), dummyEngine())
        val snapshot = quickDraft().copy(baseUrl = baseUrl(), suggestedModelIds = emptyList())
        val unauth = executor.execute(
            snapshot,
            OnboardingStepId.CREDENTIAL_TEST,
            RequestGrant(OnboardingStepId.CREDENTIAL_TEST, 1, 1, 3, false),
        )

        assertEquals(OnboardingStepStatus.FAILED, unauth.status)
        assertEquals(OnboardingFailureReason.AUTH_FAILED, unauth.reason)
        assertEquals(401, unauth.credentialTest?.httpStatus)
        assertEquals(1, unauth.requestsSpent)
        assertFalse(unauth.toString().contains(credentialValue))

        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
                .setBody("""{"error":"slow down"}"""),
        )
        val limited = executor.execute(
            snapshot,
            OnboardingStepId.CREDENTIAL_TEST,
            RequestGrant(OnboardingStepId.CREDENTIAL_TEST, 1, 1, 3, false),
        )

        assertEquals(OnboardingFailureReason.RATE_LIMITED, limited.reason)
        assertEquals(429, limited.credentialTest?.httpStatus)
        assertEquals(1, limited.requestsSpent)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun listModelsKeepsNullVsZeroAndTruncated() = runBlocking {
        val executor = ProbeStepExecutor(probeWithToken(), dummyEngine())
        val snapshot = quickDraft().copy(baseUrl = baseUrl(), suggestedModelIds = emptyList())

        server.enqueue(catalogBody("fixture-model-a", "fixture-model-b"))
        val ok = executor.execute(
            snapshot,
            OnboardingStepId.LIST_MODELS,
            RequestGrant(OnboardingStepId.LIST_MODELS, 1, 1, 3, false),
        )
        assertEquals(OnboardingStepStatus.SUCCESS, ok.status)
        assertEquals(true, ok.catalog?.supported)
        assertEquals(2, ok.catalog?.models?.size)
        assertEquals(false, ok.catalog?.truncated)
        assertEquals(1, ok.requestsSpent)

        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"fixture-model-a"},{"id":7},{"object":"model"},{"id":"  "},{"id":"fixture-model-b"}]}"""),
        )
        val partial = executor.execute(
            snapshot,
            OnboardingStepId.LIST_MODELS,
            RequestGrant(OnboardingStepId.LIST_MODELS, 1, 1, 3, false),
        )
        assertEquals(OnboardingStepStatus.SUCCESS, partial.status)
        assertEquals(listOf("fixture-model-a", "fixture-model-b"), partial.catalog?.models?.map { it.modelId })
        assertEquals(true, partial.catalog?.truncated)

        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"models":["fixture-model-a"]}"""),
        )
        val unsupported = executor.execute(
            snapshot,
            OnboardingStepId.LIST_MODELS,
            RequestGrant(OnboardingStepId.LIST_MODELS, 1, 1, 3, false),
        )
        assertEquals(OnboardingStepStatus.SKIPPED, unsupported.status)
        assertEquals(OnboardingFailureReason.NOT_APPLICABLE, unsupported.reason)
        assertEquals(false, unsupported.catalog?.supported)

        server.enqueue(catalogBody())
        val empty = executor.execute(
            snapshot,
            OnboardingStepId.LIST_MODELS,
            RequestGrant(OnboardingStepId.LIST_MODELS, 1, 1, 3, false),
        )
        assertEquals(OnboardingStepStatus.SUCCESS, empty.status)
        assertEquals(true, empty.catalog?.supported)
        assertEquals(0, empty.catalog?.models?.size)
    }

    @Test
    fun testModelRespectsBudgetCap() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                StreamEvent.Headers(200, emptyMap()),
                StreamEvent.Delta("Hi"),
                StreamEvent.Finished,
            ),
        )
        val engine = InferenceEngine(transport, RecordingTelemetry())
        val executor = ProbeStepExecutor(probeWithToken(), engine, allowLoopbackHttpForTests = true)
        val snapshot = quickDraft().copy(
            baseUrl = baseUrl(),
            suggestedModelIds = emptyList(),
            manualModelIds = listOf("probe-m-1", "probe-m-2", "probe-m-3"),
        )
        val grant = RequestGrant(OnboardingStepId.TEST_MODEL, 1, 1, 1, false)

        val result = executor.execute(snapshot, OnboardingStepId.TEST_MODEL, grant)

        assertEquals(1, result.requestsSpent)
        assertEquals(1, result.modelTests?.findings?.size)
        assertEquals(1, result.modelTests?.testedTotal)
        assertEquals(1, transport.calls)
        assertEquals(OnboardingStepStatus.SUCCESS, result.status)
    }

    @Test
    fun testModelStopsOn429AndMarksFlag() = runBlocking {
        server.enqueue(streamOk("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setBody("""{"error":"slow"}"""))
        server.enqueue(streamOk("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))

        val transport = OpenAiTransport({ _, _ -> credentialValue }, allowLoopbackHttpForTests = true)
        val engine = InferenceEngine(transport, RecordingTelemetry())
        val executor = ProbeStepExecutor(probeWithToken(), engine, allowLoopbackHttpForTests = true)
        val snapshot = quickDraft().copy(
            baseUrl = baseUrl(),
            suggestedModelIds = emptyList(),
            manualModelIds = listOf("probe-model-a", "probe-model-b", "probe-model-c"),
        )
        val grant = RequestGrant(OnboardingStepId.TEST_MODEL, 1, 3, 3, false)

        val result = executor.execute(snapshot, OnboardingStepId.TEST_MODEL, grant)

        assertEquals(OnboardingStepStatus.PARTIAL, result.status)
        assertEquals(OnboardingFailureReason.RATE_LIMITED, result.reason)
        assertEquals(true, result.modelTests?.stoppedByRateLimit)
        assertEquals(2, result.requestsSpent)
        assertEquals(2, result.modelTests?.findings?.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun missingCredentialSendsNoRequest() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                StreamEvent.Headers(200, emptyMap()),
                StreamEvent.Delta("Hi"),
                StreamEvent.Finished,
            ),
        )
        val engine = InferenceEngine(transport, RecordingTelemetry())
        val executor = ProbeStepExecutor(probeWithToken(), engine)
        val snapshot = quickDraft().copy(
            baseUrl = baseUrl(),
            safeCredentialIds = emptyList(),
            credentials = emptyList(),
            suggestedModelIds = listOf("probe-model-a"),
            manualModelIds = emptyList(),
        )

        val auth = executor.execute(snapshot, OnboardingStepId.AUTH, RequestGrant(OnboardingStepId.AUTH, 1, 0, 3, false))
        assertEquals(OnboardingStepStatus.NEEDS_INPUT, auth.status)
        assertEquals(OnboardingFailureReason.AUTH_MISSING_CREDENTIAL, auth.reason)
        assertEquals(0, auth.requestsSpent)

        val cred = executor.execute(
            snapshot,
            OnboardingStepId.CREDENTIAL_TEST,
            RequestGrant(OnboardingStepId.CREDENTIAL_TEST, 1, 1, 3, false),
        )
        assertEquals(OnboardingStepStatus.NEEDS_INPUT, cred.status)
        assertEquals(0, cred.requestsSpent)

        val listed = executor.execute(
            snapshot,
            OnboardingStepId.LIST_MODELS,
            RequestGrant(OnboardingStepId.LIST_MODELS, 1, 1, 3, false),
        )
        assertEquals(OnboardingStepStatus.NEEDS_INPUT, listed.status)
        assertEquals(0, listed.requestsSpent)

        val tested = executor.execute(
            snapshot,
            OnboardingStepId.TEST_MODEL,
            RequestGrant(OnboardingStepId.TEST_MODEL, 1, 3, 3, false),
        )
        assertEquals(OnboardingStepStatus.NEEDS_INPUT, tested.status)
        assertEquals(0, tested.requestsSpent)

        assertEquals(0, server.requestCount)
        assertEquals(0, transport.calls)
    }

    @Test
    fun cancellationPropagates() = runBlocking {
        val transport = HangingTransport(emptyList())
        val engine = InferenceEngine(transport, RecordingTelemetry())
        val executor = ProbeStepExecutor(probeWithToken(), engine, allowLoopbackHttpForTests = true)
        val snapshot = quickDraft().copy(
            baseUrl = baseUrl(),
            suggestedModelIds = emptyList(),
            manualModelIds = listOf("probe-model-a"),
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            executor.execute(snapshot, OnboardingStepId.TEST_MODEL, RequestGrant(OnboardingStepId.TEST_MODEL, 1, 3, 3, false))
        }
        transport.streaming.await()
        job.cancel()
        withTimeout(5_000L) { job.join() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellingProbeUnblocksPromptly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val executor = ProbeStepExecutor(probeWithToken(), dummyEngine())
        val snapshot = snapshotWithBase(baseUrl())
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            executor.execute(snapshot, OnboardingStepId.INSPECT, RequestGrant(OnboardingStepId.INSPECT, 1, 1, 3, false))
        }
        val deadline = System.currentTimeMillis() + 5_000L
        while (server.requestCount == 0 && System.currentTimeMillis() < deadline) delay(50L)
        assertEquals(1, server.requestCount)
        job.cancel()
        withTimeout(5_000L) { job.join() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun pricingNoticeStaysUnknownWithoutGuessing() = runBlocking {
        val executor = ProbeStepExecutor(probeWithToken(), dummyEngine())
        val snapshot = quickDraft().copy(baseUrl = baseUrl())
        val result = executor.execute(
            snapshot,
            OnboardingStepId.PRICING_QUOTA_NOTICE,
            RequestGrant(OnboardingStepId.PRICING_QUOTA_NOTICE, 1, 3, 3, false),
        )

        assertEquals(0, result.requestsSpent)
        assertEquals(dev.modelatlas.core.onboarding.PriceStatus.UNKNOWN, result.pricingQuota?.pricingStatus)
        assertEquals(QuotaState.UNKNOWN, result.pricingQuota?.quotaStatus)
        assertEquals(HealthState.UNKNOWN, result.pricingQuota?.officialHealth)
        assertEquals(HealthState.UNKNOWN, result.pricingQuota?.observedHealth)
        assertTrue(result.evidenceIds.isNotEmpty())
        assertFalse(result.toString().contains(credentialValue))
    }

    @Test
    fun unreachableInspectMapsToSafeNetworkClass() = runBlocking {
        val closed = MockWebServer()
        closed.start()
        val port = closed.port
        closed.shutdown()
        val executor = ProbeStepExecutor(probeWithToken(), dummyEngine())
        val snapshot = snapshotWithBase("http://127.0.0.1:$port/v1")
        val result = executor.execute(snapshot, OnboardingStepId.INSPECT, RequestGrant(OnboardingStepId.INSPECT, 1, 1, 3, false))

        assertEquals(OnboardingStepStatus.FAILED, result.status)
        assertEquals(OnboardingFailureReason.NETWORK_UNREACHABLE, result.reason)
        assertEquals(1, result.requestsSpent)
        assertFalse(result.toString().contains(credentialValue))
    }
}
