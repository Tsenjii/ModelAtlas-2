package dev.modelatlas.core.models

import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySink
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 session J：MODEL-008 Compare Live 測試。
 * TRACEABILITY：MODEL-008（同 prompt／失敗隔離／取消不污染／免費付費皆 telemetry）。
 */
class CompareLiveTest {

    private val prompt = listOf(PromptMessage("user", "ping-compare-live"))

    private fun offering(
        id: String,
        modelId: String = "model-$id",
        priceState: String = "UNKNOWN",
        quotaState: String = "UNKNOWN",
    ) = CompareLiveOffering(
        offeringId = id,
        providerId = "provider-$id",
        accountId = "account-$id",
        credentialId = "safe-credential-$id",
        endpointId = "endpoint-$id",
        endpointUrl = "https://api.fixture.invalid/$id/chat/completions",
        providerModelId = modelId,
        priceState = priceState,
        quotaState = quotaState,
    )

    private fun successScript(): List<StreamEvent> = listOf(
        StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L),
        StreamEvent.Headers(200, emptyMap(), 1_100L),
        StreamEvent.Delta("Hi", observedMonotonicMs = 1_400L),
        StreamEvent.Delta(" there", observedMonotonicMs = 1_500L),
        StreamEvent.TokenUsage(Usage(inputTokens = 8L, outputTokens = 12L, totalTokens = 20L)),
        StreamEvent.Finished,
    )

    private sealed interface Behavior {
        data class Script(val events: List<StreamEvent>) : Behavior
        data class Fail(val before: List<StreamEvent>, val error: Throwable) : Behavior
        data class Hang(val before: List<StreamEvent>, val started: CompletableDeferred<Unit>) : Behavior
    }

    private inner class RoutingTransport(
        private val behaviors: Map<String, Behavior>,
        private val fallback: Behavior = Behavior.Script(emptyList()),
    ) : InferenceTransport {
        val receivedTargets = mutableListOf<InferenceTarget>()
        val receivedMessages = mutableListOf<List<PromptMessage>>()

        override fun stream(target: InferenceTarget, messages: List<PromptMessage>) = flow {
            receivedTargets += target
            receivedMessages += messages
            when (val behavior = behaviors[target.endpointId] ?: fallback) {
                is Behavior.Script -> behavior.events.forEach { emit(it) }
                is Behavior.Fail -> {
                    behavior.before.forEach { emit(it) }
                    throw behavior.error
                }
                is Behavior.Hang -> {
                    behavior.before.forEach { emit(it) }
                    behavior.started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
    }

    private class RecordingFeed : TelemetrySink, CompareLiveSampleFeed {
        val saves = mutableListOf<PerformanceSample>()
        override suspend fun save(sample: PerformanceSample) {
            saves += sample
        }

        override fun finalSamples(): List<PerformanceSample> = saves.toList()

        fun finalsFor(endpointId: String): List<PerformanceSample> =
            saves.filter { it.endpointId == endpointId && it.completedAt != null }
    }

    private fun engineOf(transport: InferenceTransport, feed: RecordingFeed): InferenceEngine =
        InferenceEngine(transport, feed)

    private fun requestOf(vararg offerings: CompareLiveOffering, maxTokens: Int = 64) =
        CompareLiveRequest(
            canonicalId = "canonical-fixture",
            offerings = offerings.toList(),
            messages = prompt,
            maxOutputTokens = maxTokens,
        )

    @Test
    fun samePromptDeliveredToEveryOffering() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Script(successScript()),
                "endpoint-b" to Behavior.Script(successScript()),
            ),
        )
        CompareLiveRunner.run(engineOf(transport, feed), requestOf(offering("a"), offering("b")))

        assertEquals(2, transport.receivedMessages.size)
        assertEquals(prompt, transport.receivedMessages[0])
        assertEquals(prompt, transport.receivedMessages[1])
        assertEquals(transport.receivedMessages[0], transport.receivedMessages[1])
    }

    @Test
    fun sameMaxOutputTokensAppliedToEveryTarget() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Script(successScript()),
                "endpoint-b" to Behavior.Script(successScript()),
            ),
        )
        CompareLiveRunner.run(
            engineOf(transport, feed),
            requestOf(offering("a"), offering("b"), maxTokens = 32),
        )

        assertEquals(listOf(32, 32), transport.receivedTargets.map { it.maxOutputTokens })
    }

    @Test
    fun canonicalIdStampedOnEveryTarget() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf("endpoint-a" to Behavior.Script(successScript())),
        )
        CompareLiveRunner.run(engineOf(transport, feed), requestOf(offering("a")))

        assertEquals("canonical-fixture", transport.receivedTargets.single().canonicalModelId)
    }

    @Test
    fun authFailureIsIsolatedFromOtherOfferings() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Fail(
                    listOf(StreamEvent.Headers(401, mapOf("retry-after" to "0"), 1_120L)),
                    InferenceFailure(Outcome.AUTH, 401),
                ),
                "endpoint-b" to Behavior.Script(successScript()),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            requestOf(offering("a"), offering("b")),
        )

        assertEquals(2, rows.size)
        assertEquals("a", rows[0].offeringId)
        assertFalse(rows[0].success)
        assertEquals(Outcome.AUTH, rows[0].outcome)
        assertEquals(401, rows[0].httpStatus)
        assertEquals("AUTH", rows[0].errorKind)
        assertTrue(rows[1].success)
        assertEquals(Outcome.SUCCESS, rows[1].outcome)
        assertNull(rows[1].errorKind)
        // 失敗列的 telemetry 照常保存（不遺失）。
        assertEquals(Outcome.AUTH, feed.finalsFor("endpoint-a").single().outcome)
        assertEquals(Outcome.SUCCESS, feed.finalsFor("endpoint-b").single().outcome)
    }

    @Test
    fun rateLimitFailureIsIsolatedFromOtherOfferings() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Fail(
                    listOf(StreamEvent.Headers(429, mapOf("retry-after" to "30"), 1_120L)),
                    InferenceFailure(Outcome.RATE_LIMIT, 429),
                ),
                "endpoint-b" to Behavior.Script(successScript()),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            requestOf(offering("a"), offering("b")),
        )

        assertFalse(rows[0].success)
        assertEquals(Outcome.RATE_LIMIT, rows[0].outcome)
        assertEquals(429, rows[0].httpStatus)
        assertTrue(rows[1].success)
        assertEquals(Outcome.RATE_LIMIT, feed.finalsFor("endpoint-a").single().outcome)
    }

    @Test
    fun timeoutFailureIsIsolatedFromOtherOfferings() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Fail(
                    listOf(StreamEvent.Headers(200, emptyMap(), 1_120L)),
                    SocketTimeoutException("socket timed out"),
                ),
                "endpoint-b" to Behavior.Script(successScript()),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            requestOf(offering("a"), offering("b")),
        )

        assertFalse(rows[0].success)
        assertEquals(Outcome.TIMEOUT, rows[0].outcome)
        assertTrue(rows[1].success)
        assertEquals(Outcome.TIMEOUT, feed.finalsFor("endpoint-a").single().outcome)
    }

    @Test
    fun freeAndPaidRequestsBothReachTelemetry() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Script(successScript()),
                "endpoint-b" to Behavior.Script(successScript()),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            requestOf(
                offering("a", priceState = "FREE_NOW"),
                offering("b", priceState = "PAID"),
            ),
        )

        assertTrue(rows.all { it.success })
        assertEquals("FREE_NOW", feed.finalsFor("endpoint-a").single().priceState)
        assertEquals("PAID", feed.finalsFor("endpoint-b").single().priceState)
        assertEquals("FREE_NOW", rows[0].priceState)
        assertEquals("PAID", rows[1].priceState)
    }

    @Test
    fun metricsAreSurfacedFromFinalSamplesAndUnknownStaysNull() = runTest {
        val feed = RecordingFeed()
        val noUsageNoSecondDelta = listOf(
            StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L),
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.Delta("only", observedMonotonicMs = 1_400L),
            StreamEvent.Finished,
        )
        val noDelta = listOf(
            StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L),
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.TokenUsage(Usage(inputTokens = 8L, outputTokens = 0L)),
            StreamEvent.Finished,
        )
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Script(successScript()),
                "endpoint-b" to Behavior.Script(noUsageNoSecondDelta),
                "endpoint-c" to Behavior.Script(noDelta),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            requestOf(offering("a"), offering("b"), offering("c")),
            feed,
        )

        assertEquals(400L, rows[0].ttftMs)
        assertEquals(11.0 * 1000.0 / 100.0, rows[0].tps!!, 1e-9)
        assertNotNull(rows[0].totalLatencyMs)
        assertEquals(8L, rows[0].inputTokens)
        assertEquals(12L, rows[0].outputTokens)
        // 單一 delta 不產 TPS：未知維持 null，不補 0。
        assertNull(rows[1].tps)
        assertEquals(400L, rows[1].ttftMs)
        // 無生成內容：TTFT unavailable＝null，不補 0。
        assertNull(rows[2].ttftMs)
        assertNull(rows[2].tps)
        assertTrue(rows.all { it.success })
    }

    @Test
    fun unknownPriceNeverDerivedFromModelId() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf("endpoint-a" to Behavior.Script(successScript())),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            requestOf(offering("a", modelId = "SomeVendor-Free-Unlimited", priceState = "UNKNOWN")),
        )

        assertEquals("UNKNOWN", rows.single().priceState)
        assertEquals("UNKNOWN", feed.finalsFor("endpoint-a").single().priceState)
        assertEquals("SomeVendor-Free-Unlimited", transport.receivedTargets.single().modelId)
    }

    @Test
    fun cancellationPropagatesAndCompletedRowsStayIntact() = runTest {
        val feed = RecordingFeed()
        val hangStarted = CompletableDeferred<Unit>()
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Script(successScript()),
                "endpoint-b" to Behavior.Hang(
                    listOf(
                        StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L),
                        StreamEvent.Headers(200, emptyMap(), 1_100L),
                    ),
                    hangStarted,
                ),
            ),
        )
        var thrown: Throwable? = null
        val job = launch {
            try {
                CompareLiveRunner.run(
                    engineOf(transport, feed),
                    requestOf(offering("a"), offering("b")),
                )
            } catch (e: CancellationException) {
                thrown = e
            }
        }
        // 等慢列確實在途後再取消：快列應已成功，其 telemetry 不得被污染。
        hangStarted.await()
        // 快列完成是併發的：輪到其 final 出現為止（runTest 內有界等待）。
        var spins = 0
        while (feed.finalsFor("endpoint-a").isEmpty() && spins < 1_000) {
            spins++
            kotlinx.coroutines.yield()
        }
        job.cancelAndJoin()

        assertTrue(thrown is CancellationException)
        // 快列 SUCCESS final 完整保留（取消不污染已完成列）。
        val fastFinal = feed.finalsFor("endpoint-a").single()
        assertEquals(Outcome.SUCCESS, fastFinal.outcome)
        assertEquals(400L, fastFinal.ttftMs)
        // 慢列取消收斂為 CANCELLED final（不遺失）。
        assertEquals(Outcome.CANCELLED, feed.finalsFor("endpoint-b").single().outcome)
    }

    @Test
    fun emptyOfferingsReturnEmptyWithoutEngineCalls() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(emptyOf())
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            CompareLiveRequest(
                canonicalId = "canonical-fixture",
                offerings = emptyList(),
                messages = prompt,
            ),
        )

        assertTrue(rows.isEmpty())
        assertTrue(transport.receivedTargets.isEmpty())
        assertTrue(feed.saves.isEmpty())
    }

    @Test
    fun telemetrySourceIsCompareLiveForEverySample() = runTest {
        val feed = RecordingFeed()
        val transport = RoutingTransport(
            mapOf(
                "endpoint-a" to Behavior.Script(successScript()),
                "endpoint-b" to Behavior.Fail(
                    listOf(StreamEvent.Headers(429, emptyMap(), 1_120L)),
                    InferenceFailure(Outcome.RATE_LIMIT, 429),
                ),
                "endpoint-c" to Behavior.Fail(
                    listOf(StreamEvent.Headers(200, emptyMap(), 1_120L)),
                    IOException("connection reset"),
                ),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            requestOf(offering("a"), offering("b"), offering("c")),
        )

        assertEquals(3, rows.size)
        assertTrue(feed.saves.isNotEmpty())
        assertTrue(feed.saves.all { it.source == TelemetrySource.COMPARE_LIVE })
        assertEquals(Outcome.NETWORK, rows[2].outcome)
    }

    private fun emptyOf(): Map<String, Behavior> = emptyMap()
}
