package dev.modelatlas.core.inference

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceEngineTest {
    @Test
    fun cancelledSocketIOExceptionIsStillRecordedAsCancellation() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sink = RecordingTelemetry()
        val transport = object : InferenceTransport {
            override fun stream(target: InferenceTarget, messages: List<PromptMessage>) =
                kotlinx.coroutines.flow.flow<StreamEvent> {
                    started.complete(Unit)
                    try { kotlinx.coroutines.awaitCancellation() }
                    catch (_: kotlinx.coroutines.CancellationException) { throw IOException("Socket closed") }
                }
        }
        val job = launch { InferenceEngine(transport, sink).execute(fixtureTarget(), fixtureMessages()).toList() }
        started.await()
        job.cancelAndJoin()
        assertEquals(Outcome.CANCELLED, sink.last().outcome)
    }

    private val clock = StepClock(step = 10L)
    private val telemetry = RecordingTelemetry()
    private val target = fixtureTarget()
    private val sent = StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L)

    private fun engine(transport: InferenceTransport, sink: TelemetrySink = telemetry) =
        InferenceEngine(transport, sink, clock)

    private fun successScript(): List<StreamEvent> = listOf(
        StreamEvent.Headers(200, emptyMap(), 1_100L),
        StreamEvent.Delta("Hello", observedMonotonicMs = 1_200L),
        StreamEvent.Delta(" world", observedMonotonicMs = 1_300L),
        StreamEvent.TokenUsage(Usage(inputTokens = 9L, outputTokens = 12L, totalTokens = 21L)),
        StreamEvent.Finished,
    )

    @Test
    fun finalSaveFailureSurfacesOnTheSuccessPathAfterOneRetry() = runTest {
        var calls = 0
        val sink = object : TelemetrySink {
            override suspend fun save(sample: PerformanceSample) {
                calls++
                // Durable start (call 1) succeeds; the finalized save (call 2) and its
                // one degraded retry (call 3) fail.
                if (calls >= 2) throw IOException("storage locked")
            }
        }
        val error = try {
            engine(ScriptedTransport(successScript()), sink).execute(target, fixtureMessages()).toList()
            null
        } catch (e: IOException) {
            e
        }

        assertNotNull(error)
        assertEquals(3, calls)
    }

    @Test
    fun finalSaveFailureDoesNotMaskTheInferenceOutcome() = runTest {
        var calls = 0
        val sink = object : TelemetrySink {
            override suspend fun save(sample: PerformanceSample) {
                calls++
                if (calls >= 2) throw IOException("storage locked")
            }
        }
        val failure = captureInferenceFailure {
            engine(FailingTransport(emptyList(), InferenceFailure(Outcome.AUTH, 401)), sink)
                .execute(target, fixtureMessages()).toList()
        }

        assertNotNull(failure)
        assertEquals(Outcome.AUTH, failure?.outcome)
        assertEquals(401, failure?.status)
        assertTrue(failure?.suppressed?.any { it is IOException } == true)
        assertEquals(3, calls)
    }

    @Test
    fun freeNowAndPaidRequestsBothReachTelemetry() = runTest {
        val freeSink = RecordingTelemetry()
        engine(ScriptedTransport(successScript()), freeSink).execute(
            target = target,
            messages = fixtureMessages(),
            source = TelemetrySource.PLAYGROUND,
            priceState = "FREE_NOW",
            quotaState = "AVAILABLE",
            pricingSnapshotId = "price-free-fixture",
        ).toList()
        val paidSink = RecordingTelemetry()
        engine(ScriptedTransport(successScript()), paidSink).execute(
            target = target,
            messages = fixtureMessages(),
            source = TelemetrySource.PLAYGROUND,
            priceState = "PAID",
            quotaState = "LIMITED",
            pricingSnapshotId = "price-paid-fixture",
            quotaSnapshotBeforeId = "quota-paid-fixture",
        ).toList()

        assertEquals(Outcome.SUCCESS, freeSink.last().outcome)
        assertEquals(Outcome.SUCCESS, paidSink.last().outcome)
        assertEquals("FREE_NOW", freeSink.last().priceState)
        assertEquals("PAID", paidSink.last().priceState)
        assertEquals("price-free-fixture", freeSink.last().pricingSnapshotId)
        assertEquals("price-paid-fixture", paidSink.last().pricingSnapshotId)
        assertEquals("quota-paid-fixture", paidSink.last().quotaSnapshotBeforeId)
        assertEquals(2, freeSink.saves.size)
        assertEquals(2, paidSink.saves.size)
        assertNotEquals(freeSink.last().id, paidSink.last().id)
    }

    @Test
    fun everyStreamEventIsForwardedToTheCollector() = runTest {
        val script = successScript()
        val events = engine(ScriptedTransport(script)).execute(target, fixtureMessages()).toList()
        assertEquals(script, events)
    }

    @Test
    fun durableStartIsSavedBeforeStreamingAndReusedForFinalization() = runTest {
        val failure = captureInferenceFailure {
            engine(FailingTransport(emptyList(), InferenceFailure(Outcome.AUTH, 401)))
                .execute(target, fixtureMessages()).toList()
        }

        assertEquals(Outcome.AUTH, failure?.outcome)
        assertEquals(2, telemetry.saves.size)
        assertEquals(Outcome.RUNNING, telemetry.first().outcome)
        assertNull(telemetry.first().completedAt)
        assertNull(telemetry.first().firstTokenAt)
        assertEquals(telemetry.first().id, telemetry.last().id)
        assertEquals(Outcome.AUTH, telemetry.last().outcome)
        assertEquals(401, telemetry.last().httpStatus)
    }

    @Test
    fun requestSentRebasesTtfbAndTtftToTheWireBaseline() = runTest {
        val script = listOf(
            sent,
            StreamEvent.Headers(200, mapOf("x-ratelimit-remaining-requests" to "999"), 1_120L),
            StreamEvent.Delta("Hi", observedMonotonicMs = 1_350L),
            StreamEvent.Delta(" there", observedMonotonicMs = 1_500L),
            StreamEvent.ResponseModel("resolved-fixture-model"),
            StreamEvent.TokenUsage(Usage(inputTokens = 20L, outputTokens = 12L, totalTokens = 32L)),
            StreamEvent.Finished,
        )
        val events = engine(ScriptedTransport(script)).execute(target, fixtureMessages()).toList()
        val sample = telemetry.last()

        assertEquals(Outcome.SUCCESS, sample.outcome)
        assertEquals(5_000L, sample.requestStartedAt)
        assertEquals(120L, sample.ttfbMs)
        assertEquals(350L, sample.ttftMs)
        assertEquals(5_350L, sample.firstTokenAt)
        assertEquals(200, sample.httpStatus)
        assertEquals(mapOf("x-ratelimit-remaining-requests" to "999"), sample.rateLimitHeaders)
        assertEquals("resolved-fixture-model", sample.responseModelId)
        assertEquals(Usage(20L, 12L, 32L), sample.usage)
        assertEquals(11.0 * 1000.0 / 150.0, sample.tps!!, 1e-9)
        // Wire baseline 保持不變；唯一新增欄位必須精確指向同一個 durable/final sample。
        assertEquals(sent.copy(sampleId = sample.id), events.first())
        assertEquals(telemetry.first().id, sample.id)
    }

    @Test
    fun roleOnlyChunksDoNotStartFirstToken() = runTest {
        val script = listOf(
            sent,
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.Delta("", observedMonotonicMs = 1_200L),
            StreamEvent.Delta("", reasoning = true, observedMonotonicMs = 1_250L),
            StreamEvent.Delta("Hello", observedMonotonicMs = 1_400L),
            StreamEvent.Finished,
        )
        engine(ScriptedTransport(script)).execute(target, fixtureMessages()).toList()
        val sample = telemetry.last()

        assertEquals(400L, sample.ttftMs)
        assertEquals(5_400L, sample.firstTokenAt)
        assertNull(sample.tps)
    }

    @Test
    fun roleOnlyChunksWithoutTransportTimestampsAlsoDoNotStartFirstToken() = runTest {
        engine(ScriptedTransport(listOf(
            StreamEvent.Headers(200, emptyMap()),
            StreamEvent.Delta(""),
            StreamEvent.Delta(""),
            StreamEvent.Delta("Hello"),
            StreamEvent.Finished,
        ))).execute(target, fixtureMessages()).toList()
        val withRoleOnly = telemetry.last().ttftMs

        val controlSink = RecordingTelemetry()
        engine(ScriptedTransport(listOf(
            StreamEvent.Headers(200, emptyMap()),
            StreamEvent.Delta("Hello"),
            StreamEvent.Finished,
        )), controlSink).execute(target, fixtureMessages()).toList()
        val control = controlSink.last().ttftMs

        assertEquals(40L, withRoleOnly)
        assertEquals(20L, control)
        assertTrue(withRoleOnly!! > control!!)
    }

    @Test
    fun timingsFallBackToTheLocalClockWhenTheTransportDoesNotStampThem() = runTest {
        engine(ScriptedTransport(listOf(
            StreamEvent.Headers(200, emptyMap()),
            StreamEvent.Delta("a"),
            StreamEvent.Delta("b"),
            StreamEvent.TokenUsage(Usage(inputTokens = 4L, outputTokens = 12L)),
            StreamEvent.Finished,
        ))).execute(target, fixtureMessages()).toList()
        val sample = telemetry.last()

        assertEquals(1_010L, sample.requestStartedAt)
        assertEquals(10L, sample.ttfbMs)
        assertEquals(20L, sample.ttftMs)
        // TokenUsage / Finished 亦各讀一次 local clock，總延遲為 70 - 10。
        assertEquals(60L, sample.totalLatencyMs)
        assertEquals(1_020L, sample.completedAt)
        assertEquals(11.0 * 1000.0 / 10.0, sample.tps!!, 1e-9)
    }

    @Test
    fun timestampsBeforeTheRequestBaselineAreClampedToZero() = runTest {
        engine(ScriptedTransport(listOf(
            sent,
            StreamEvent.Headers(200, emptyMap(), observedMonotonicMs = 400L),
            StreamEvent.Delta("Hello", observedMonotonicMs = 900L),
            StreamEvent.Finished,
        ))).execute(target, fixtureMessages()).toList()
        val sample = telemetry.last()

        assertEquals(0L, sample.ttfbMs)
        assertEquals(0L, sample.ttftMs)
        assertEquals(5_000L, sample.firstTokenAt)
    }

    @Test
    fun partialUsageKeepsAbsentTokenCountsUnknownAndTpsUnknown() = runTest {
        engine(ScriptedTransport(listOf(
            sent,
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.Delta("Hello", observedMonotonicMs = 1_400L),
            StreamEvent.Delta(" world", observedMonotonicMs = 1_500L),
            StreamEvent.TokenUsage(Usage(inputTokens = 20L)),
            StreamEvent.Finished,
        ))).execute(target, fixtureMessages()).toList()
        val usage = telemetry.last().usage

        assertEquals(20L, usage.inputTokens)
        assertNull(usage.outputTokens)
        assertNull(usage.totalTokens)
        assertNull(usage.reasoningTokens)
        assertNull(telemetry.last().tps)
    }

    @Test
    fun missingUsageEventLeavesEveryTokenCountUnknown() = runTest {
        engine(ScriptedTransport(listOf(
            sent,
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.Delta("Hello", observedMonotonicMs = 1_400L),
            StreamEvent.Delta(" world", observedMonotonicMs = 1_500L),
            StreamEvent.Finished,
        ))).execute(target, fixtureMessages()).toList()

        assertEquals(Usage(), telemetry.last().usage)
        assertNull(telemetry.last().tps)
    }

    @Test
    fun aSingleGeneratedDeltaNeverProducesTps() = runTest {
        engine(ScriptedTransport(listOf(
            sent,
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.Delta("Hello", observedMonotonicMs = 1_400L),
            StreamEvent.TokenUsage(Usage(inputTokens = 20L, outputTokens = 12L)),
            StreamEvent.Finished,
        ))).execute(target, fixtureMessages()).toList()
        val sample = telemetry.last()

        assertEquals(Outcome.SUCCESS, sample.outcome)
        assertEquals(400L, sample.ttftMs)
        assertNull(sample.tps)
    }

    @Test
    fun completionWithoutGeneratedContentHasNoFirstTokenMetrics() = runTest {
        engine(ScriptedTransport(listOf(
            sent,
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.TokenUsage(Usage(inputTokens = 8L, outputTokens = 0L)),
            StreamEvent.Finished,
        ))).execute(target, fixtureMessages()).toList()
        val sample = telemetry.last()

        assertEquals(Outcome.SUCCESS, sample.outcome)
        assertNull(sample.ttftMs)
        assertNull(sample.firstTokenAt)
        assertNull(sample.tps)
        assertNotNull(sample.ttfbMs)
        assertEquals(0L, sample.usage.outputTokens)
    }

    @Test
    fun unauthorizedIsRetainedWithStatusAndSafeHeaders() = runTest {
        val failure = captureInferenceFailure {
            engine(FailingTransport(
                listOf(StreamEvent.Headers(401, mapOf("retry-after" to "0"), 1_120L)),
                InferenceFailure(Outcome.AUTH, 401),
            )).execute(target, fixtureMessages()).toList()
        }
        val sample = telemetry.last()

        assertEquals(Outcome.AUTH, failure?.outcome)
        assertEquals(401, failure?.status)
        assertEquals(Outcome.AUTH, sample.outcome)
        assertEquals(401, sample.httpStatus)
        assertEquals(mapOf("retry-after" to "0"), sample.rateLimitHeaders)
        assertNull(sample.usage.outputTokens)
        assertNull(sample.tps)
        assertNotNull(sample.completedAt)
    }

    @Test
    fun rateLimitedIsRetainedWithStatus() = runTest {
        val failure = captureInferenceFailure {
            engine(FailingTransport(
                listOf(StreamEvent.Headers(429, mapOf("retry-after" to "30"), 1_120L)),
                InferenceFailure(Outcome.RATE_LIMIT, 429),
            )).execute(target, fixtureMessages()).toList()
        }
        val sample = telemetry.last()

        assertEquals(Outcome.RATE_LIMIT, failure?.outcome)
        assertEquals(429, failure?.status)
        assertEquals(Outcome.RATE_LIMIT, sample.outcome)
        assertEquals(429, sample.httpStatus)
        assertEquals("30", sample.rateLimitHeaders["retry-after"])
        assertNull(sample.usage.outputTokens)
        assertEquals(0, sample.retryCount)
    }

    @Test
    fun transportExceptionsAreClassifiedWithoutLeakingMessages() = runTest {
        val cases = listOf(
            Triple(SocketTimeoutException("$LEAKY_MESSAGE_MARKER-timeout"), Outcome.TIMEOUT, 200),
            Triple(IOException("$LEAKY_MESSAGE_MARKER-network"), Outcome.NETWORK, 200),
            Triple(IllegalStateException("$LEAKY_MESSAGE_MARKER-$CREDENTIAL_FIXTURE"), Outcome.OTHER, 200),
        )
        for ((thrown, expected, status) in cases) {
            val sink = RecordingTelemetry()
            val failure = captureInferenceFailure {
                engine(FailingTransport(listOf(StreamEvent.Headers(200, emptyMap(), 1_120L)), thrown), sink)
                    .execute(target, fixtureMessages()).toList()
            }

            assertEquals(expected, failure?.outcome)
            assertEquals(status, failure?.status)
            assertEquals(expected, sink.last().outcome)
            assertEquals(status, sink.last().httpStatus)
            assertNotNull(sink.last().completedAt)
            assertFalse(sink.persistedText().contains(LEAKY_MESSAGE_MARKER))
            assertFalse(sink.persistedText().contains(CREDENTIAL_FIXTURE))
            assertFalse(failure?.message.orEmpty().contains(LEAKY_MESSAGE_MARKER))
            assertFalse(failure?.message.orEmpty().contains(CREDENTIAL_FIXTURE))
        }
    }

    @Test
    fun vaultScopeMismatchIsClassifiedAsCredentialScope() = runTest {
        // F4：AndroidCredentialVault "Credential scope mismatch" 不再落 OTHER。
        val sink = RecordingTelemetry()
        val failure = captureInferenceFailure {
            engine(FailingTransport(
                listOf(StreamEvent.Headers(200, emptyMap(), 1_120L)),
                IllegalStateException("Credential scope mismatch"),
            ), sink).execute(target, fixtureMessages()).toList()
        }

        assertEquals(Outcome.CREDENTIAL_SCOPE, failure?.outcome)
        assertEquals(Outcome.CREDENTIAL_SCOPE, sink.last().outcome)
        assertNotNull(sink.last().completedAt)
        assertFalse(sink.persistedText().contains(CREDENTIAL_FIXTURE))
    }

    @Test
    fun parserFailureKeepsPartialMetricsAndLeavesUsageUnknown() = runTest {
        val failure = captureInferenceFailure {
            engine(FailingTransport(
                listOf(
                    sent,
                    StreamEvent.Headers(200, emptyMap(), 1_100L),
                    StreamEvent.Delta("partial", observedMonotonicMs = 1_400L),
                ),
                InferenceFailure(Outcome.PARSER),
            )).execute(target, fixtureMessages()).toList()
        }
        val sample = telemetry.last()

        assertEquals(Outcome.PARSER, failure?.outcome)
        assertEquals(Outcome.PARSER, sample.outcome)
        assertEquals(400L, sample.ttftMs)
        assertEquals(Usage(), sample.usage)
        assertNull(sample.tps)
        assertNotNull(sample.completedAt)
        assertEquals(2, telemetry.saves.size)
    }

    @Test
    fun streamWithoutCompletionMarkerIsParserFailure() = runTest {
        val failure = captureInferenceFailure {
            engine(ScriptedTransport(listOf(
                sent,
                StreamEvent.Headers(200, emptyMap(), 1_100L),
                StreamEvent.Delta("partial", observedMonotonicMs = 1_400L),
            ))).execute(target, fixtureMessages()).toList()
        }

        assertEquals(Outcome.PARSER, failure?.outcome)
        assertEquals(Outcome.PARSER, telemetry.last().outcome)
        assertEquals(400L, telemetry.last().ttftMs)
    }

    @Test
    fun cancellationFinalizesTelemetryAsCancelled() = runTest {
        val sink = RecordingTelemetry()
        val transport = HangingTransport(listOf(
            sent,
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.Delta("partial", observedMonotonicMs = 1_400L),
        ))
        val job = launch { engine(transport, sink).execute(target, fixtureMessages()).toList() }
        transport.streaming.await()
        job.cancelAndJoin()
        val sample = sink.last()

        assertEquals(2, sink.saves.size)
        assertEquals(Outcome.RUNNING, sink.first().outcome)
        assertEquals(Outcome.CANCELLED, sample.outcome)
        assertEquals(sink.first().id, sample.id)
        assertEquals(400L, sample.ttftMs)
        assertNull(sample.usage.outputTokens)
        assertNull(sample.tps)
        assertNotNull(sample.completedAt)
        assertNotNull(sample.totalLatencyMs)
    }

    @Test
    fun promptContentAndCredentialValuesNeverEnterTelemetry() = runTest {
        captureInferenceFailure {
            engine(FailingTransport(
                listOf(StreamEvent.Headers(401, emptyMap(), 1_120L)),
                InferenceFailure(Outcome.AUTH, 401),
            )).execute(target, fixtureMessages()).toList()
        }
        val persisted = telemetry.persistedText()
        val sample = telemetry.last()

        assertFalse(persisted.contains(PROMPT_MARKER))
        assertFalse(persisted.contains(CREDENTIAL_FIXTURE))
        assertFalse(persisted.contains(target.endpointUrl))
        assertEquals("safe-credential-fixture", sample.safeCredentialId)
        assertEquals("endpoint-fixture", sample.endpointId)
        assertTrue(persisted.contains("safe-credential-fixture"))
    }

    @Test
    fun independentScopesProduceIndependentSamples() = runTest {
        val firstSink = RecordingTelemetry()
        val secondSink = RecordingTelemetry()
        val otherTarget = target.copy(
            accountId = "account-other",
            credentialId = "safe-credential-other",
            endpointId = "endpoint-other",
            modelId = "other-fixture-model",
            canonicalModelId = "canonical-other-fixture-model",
            group = "group-other",
        )
        engine(ScriptedTransport(successScript()), firstSink)
            .execute(target, fixtureMessages(), source = TelemetrySource.CRAWLER_VERIFY).toList()
        engine(ScriptedTransport(successScript()), secondSink)
            .execute(otherTarget, fixtureMessages(), source = TelemetrySource.BACKGROUND_PROBE, priceState = "PAID").toList()
        val first = firstSink.last()
        val second = secondSink.last()

        assertNotEquals(first.id, second.id)
        assertEquals("safe-credential-fixture", first.safeCredentialId)
        assertEquals("safe-credential-other", second.safeCredentialId)
        assertEquals("group-other", second.group)
        assertEquals("other-fixture-model", second.providerModelId)
        assertEquals("canonical-other-fixture-model", second.canonicalModelId)
        assertEquals(TelemetrySource.CRAWLER_VERIFY, first.source)
        assertEquals(TelemetrySource.BACKGROUND_PROBE, second.source)
        assertEquals("CHAT_COMPLETIONS", first.operation)
        assertEquals("CHAT_COMPLETIONS", second.operation)
    }
}
