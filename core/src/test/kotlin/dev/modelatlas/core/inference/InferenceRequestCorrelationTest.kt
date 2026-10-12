package dev.modelatlas.core.inference

import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROV-001 手動驗證前置／FND-006：`StreamEvent.RequestSent` 必須帶著「該次請求真正的 sample id」
 * 轉發給呼叫端，否則呼叫端無法把「立即測試」的結果回填到對應的遙測／evidence。
 *
 * 契約（由 [InferenceEngine] 保證，不新增 sealed variant）：
 * - `sampleId` 一律由 durable recorder 產生的 `sample.id` 覆寫，transport 不得自帶；
 * - 覆寫只動 id，`epochMillis` / `monotonicMillis` 保持 transport 原值，且仍照原值寫進 sample；
 * - 同一個 sample 只有兩次寫入（RUNNING durable + 最終），兩者 id 相同，forwarded id 必須同時等於兩者。
 *
 * 本檔只測關聯性，不重複 media／SSE 解析測試，也不觸碰 vault／raw key／PAT。
 */
class InferenceRequestCorrelationTest {
    private val clock = StepClock(step = 10L)
    private val target = fixtureTarget()

    private fun engine(transport: InferenceTransport, sink: TelemetrySink) =
        InferenceEngine(transport, sink, clock)

    private fun successScript(): List<StreamEvent> = listOf(
        StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L),
        StreamEvent.Headers(200, emptyMap(), 1_100L),
        StreamEvent.Delta("Hello", observedMonotonicMs = 1_200L),
        StreamEvent.TokenUsage(Usage(inputTokens = 4L, outputTokens = 2L, totalTokens = 6L)),
        StreamEvent.Finished,
    )

    private fun requestSentOf(events: List<StreamEvent>): StreamEvent.RequestSent =
        events.filterIsInstance<StreamEvent.RequestSent>().single()

    @Test
    fun manualTestRequestCarriesTheRealSampleIdSharedByDurableAndFinalSaves() = runTest {
        val sink = RecordingTelemetry()

        val events = engine(ScriptedTransport(successScript()), sink)
            .execute(target, fixtureMessages(), source = TelemetrySource.MANUAL_TEST)
            .toList()

        val forwarded = requestSentOf(events).sampleId
        assertNotNull("forwarded RequestSent must expose the recorder's sample id", forwarded)
        assertEquals(2, sink.saves.size)
        assertEquals(Outcome.RUNNING, sink.first().outcome)
        assertEquals(Outcome.SUCCESS, sink.last().outcome)
        assertEquals(TelemetrySource.MANUAL_TEST, sink.last().source)
        assertEquals("durable start and final save are the same sample row", sink.first().id, sink.last().id)
        assertEquals(sink.first().id, forwarded)
        assertEquals(sink.last().id, forwarded)
        assertTrue(sink.saves.all { it.id == forwarded })
    }

    @Test
    fun twoRunsOnTheSameScopeEachLinkToTheirOwnSample() = runTest {
        val sink = RecordingTelemetry()
        val testEngine = engine(ScriptedTransport(successScript()), sink)

        val requests = testEngine.execute(target, fixtureMessages(), source = TelemetrySource.MANUAL_TEST)
        // 重收集同一 cold Flow 也代表兩次實際請求，不能覆寫同一筆 telemetry。
        val first = requests.toList()
        val second = requests.toList()

        val firstId = requestSentOf(first).sampleId
        val secondId = requestSentOf(second).sampleId
        assertNotNull(firstId)
        assertNotNull(secondId)
        assertNotEquals("two requests must not share a sample id", firstId, secondId)

        val firstSamples = sink.saves.filter { it.id == firstId }
        val secondSamples = sink.saves.filter { it.id == secondId }
        assertEquals("first request owns its own durable + final save", 2, firstSamples.size)
        assertEquals("second request owns its own durable + final save", 2, secondSamples.size)
        assertEquals(Outcome.SUCCESS, firstSamples.last().outcome)
        assertEquals(Outcome.SUCCESS, secondSamples.last().outcome)
        // 同一 scope 的兩次請求各自保留自己的 scope 欄位，不互相覆蓋。
        assertEquals(target.providerId, firstSamples.last().providerId)
        assertEquals(target.accountId, firstSamples.last().accountId)
        assertEquals(target.group, firstSamples.last().group)
        assertEquals(target.modelId, secondSamples.last().providerModelId)
        assertTrue("no request id outside the two forwarded ones may be persisted", sink.saves.all {
            it.id == firstId || it.id == secondId
        })
    }

    @Test
    fun transportSuppliedSampleIdIsOverwrittenWhileTimestampsSurvive() = runTest {
        val sink = RecordingTelemetry()
        val forged = StreamEvent.RequestSent(
            epochMillis = 7_777L,
            monotonicMillis = 8_888L,
            sampleId = "forged-sample-id-must-not-survive",
        )

        val events = engine(
            ScriptedTransport(
                listOf(
                    forged,
                    StreamEvent.Headers(200, emptyMap(), 8_900L),
                    StreamEvent.Delta("Hello", observedMonotonicMs = 9_000L),
                    StreamEvent.TokenUsage(Usage(inputTokens = 1L, outputTokens = 1L, totalTokens = 2L)),
                    StreamEvent.Finished,
                ),
            ),
            sink,
        ).execute(target, fixtureMessages(), source = TelemetrySource.MANUAL_TEST).toList()

        val forwarded = requestSentOf(events)
        assertEquals("forged-sample-id-must-not-survive", forged.sampleId)
        assertNotEquals(forged.sampleId, forwarded.sampleId)
        assertEquals(sink.last().id, forwarded.sampleId)
        assertEquals(7_777L, forwarded.epochMillis)
        assertEquals(8_888L, forwarded.monotonicMillis)
        // 原值仍照舊驅動 sample，不因覆寫 id 而改動。
        assertEquals(7_777L, sink.last().requestStartedAt)
        assertEquals(TelemetrySource.MANUAL_TEST, sink.last().source)
    }

    @Test
    fun failedRequestStillLinksForwardedIdToTheSameFailedSample() = runTest {
        val sink = RecordingTelemetry()
        val events = mutableListOf<StreamEvent>()

        val failure = captureInferenceFailure {
            engine(
                FailingTransport(
                    listOf(StreamEvent.RequestSent(epochMillis = 3_333L, monotonicMillis = 2_222L)),
                    IOException("network down"),
                ),
                sink,
            ).execute(target, fixtureMessages(), source = TelemetrySource.MANUAL_TEST)
                .collect { events += it }
        }

        assertEquals(Outcome.NETWORK, failure?.outcome)
        val forwarded = requestSentOf(events).sampleId
        assertNotNull("a failed request still owns a sample id", forwarded)
        assertEquals(Outcome.RUNNING, sink.first().outcome)
        assertEquals(Outcome.NETWORK, sink.last().outcome)
        assertEquals(sink.first().id, forwarded)
        assertEquals(sink.last().id, forwarded)
    }
}
