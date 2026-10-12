package dev.modelatlas.core.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

/** All inference entry points share this recorder, including zero-priced requests. */
class InferenceEngine(
    private val transport: InferenceTransport,
    private val telemetry: TelemetrySink,
    private val clock: Clock = SystemClock,
) {
    fun execute(
        target: InferenceTarget,
        messages: List<PromptMessage>,
        source: TelemetrySource = TelemetrySource.PLAYGROUND,
        priceState: String = "UNKNOWN",
        quotaState: String = "UNKNOWN",
        pricingSnapshotId: String? = null,
        quotaSnapshotBeforeId: String? = null,
    ): Flow<StreamEvent> = flow {
        val started = clock.epochMillis()
        var tick = clock.monotonicMillis()
        var firstTick: Long? = null
        var lastGeneratedTick: Long? = null
        var finished = false
        var sample = PerformanceSample(
            id = UUID.randomUUID().toString(), providerId = target.providerId,
            accountId = target.accountId,
            safeCredentialId = (target.telemetryCredentialId ?: target.credentialId).orEmpty(),
            endpointId = target.endpointId, providerModelId = target.modelId,
            canonicalModelId = target.canonicalModelId, group = target.group,
            operation = target.operation, source = source, requestStartedAt = started,
            priceState = priceState, quotaState = quotaState,
            pricingSnapshotId = pricingSnapshotId, quotaSnapshotBeforeId = quotaSnapshotBeforeId,
        )
        // Durable start before spending quota. A killed process leaves an INTERRUPTED-able record.
        withContext(NonCancellable) { telemetry.save(sample) }
        var pending: Throwable? = null
        try {
            transport.stream(target, messages).collect { event ->
                // P4R：時間戳分支窮舉全部 StreamEvent 子型別（新增多模態子型別即編譯提醒，
                // 不靠 else 吞掉； RequestSent/TokenUsage/ResponseModel/GeneratedImages/Finished
                // 無事件內時間戳，走本地 clock）。
                val now = when (event) {
                    is StreamEvent.RequestSent -> null
                    is StreamEvent.Headers -> event.observedMonotonicMs
                    is StreamEvent.Delta -> event.observedMonotonicMs
                    is StreamEvent.TokenUsage -> null
                    is StreamEvent.ResponseModel -> null
                    is StreamEvent.ToolCallDelta -> event.observedMonotonicMs
                    is StreamEvent.AudioDelta -> event.observedMonotonicMs
                    is StreamEvent.GeneratedImages -> null
                    is StreamEvent.Transcription -> event.observedMonotonicMs
                    StreamEvent.Finished -> null
                } ?: clock.monotonicMillis()
                // P4R：telemetry 分支同樣窮舉全部子型別（無 else；新增子型別未處理即編譯失敗）。
                // 多模態事件只轉發、不污染 telemetry：不寫 usage 以外的 sample 欄位，
                // 不持久化任何對話內容／tool 參數／音訊／圖片 bytes（secret／隱私邊界）。
                when (event) {
                    is StreamEvent.RequestSent -> {
                        tick = event.monotonicMillis
                        sample = sample.copy(requestStartedAt = event.epochMillis)
                    }
                    is StreamEvent.Headers -> sample = sample.copy(
                        httpStatus = event.status, ttfbMs = (now - tick).coerceAtLeast(0),
                        rateLimitHeaders = event.rateLimits,
                    )
                    is StreamEvent.Delta -> if (event.text.isNotEmpty()) {
                        if (firstTick == null) {
                            firstTick = now
                            sample = sample.copy(firstTokenAt = sample.requestStartedAt + (now - tick).coerceAtLeast(0), ttftMs = (now - tick).coerceAtLeast(0))
                        }
                        lastGeneratedTick = now
                    }
                    is StreamEvent.TokenUsage -> sample = sample.copy(usage = event.usage)
                    is StreamEvent.ResponseModel -> sample = sample.copy(responseModelId = event.id)
                    // P4R 多模態：以下四分支皆為「首個生成內容」候選（TTFT 語意與 Delta 一致：
                    // 空碎片不啟動 first token；role-only 空 Delta 既有行為不變）。
                    is StreamEvent.ToolCallDelta -> {
                        if (event.id?.isNotEmpty() == true ||
                            event.name?.isNotEmpty() == true ||
                            event.argumentsFragment.isNotEmpty()
                        ) {
                            if (firstTick == null) {
                                firstTick = now
                                sample = sample.copy(firstTokenAt = sample.requestStartedAt + (now - tick).coerceAtLeast(0), ttftMs = (now - tick).coerceAtLeast(0))
                            }
                            lastGeneratedTick = now
                        }
                    }
                    is StreamEvent.AudioDelta -> {
                        // 建構子已保證至少一邊非空。
                        if (firstTick == null) {
                            firstTick = now
                            sample = sample.copy(firstTokenAt = sample.requestStartedAt + (now - tick).coerceAtLeast(0), ttftMs = (now - tick).coerceAtLeast(0))
                        }
                        lastGeneratedTick = now
                    }
                    is StreamEvent.GeneratedImages -> {
                        if (firstTick == null) {
                            firstTick = now
                            sample = sample.copy(firstTokenAt = sample.requestStartedAt + (now - tick).coerceAtLeast(0), ttftMs = (now - tick).coerceAtLeast(0))
                        }
                        lastGeneratedTick = now
                    }
                    is StreamEvent.Transcription -> {
                        if (firstTick == null) {
                            firstTick = now
                            sample = sample.copy(firstTokenAt = sample.requestStartedAt + (now - tick).coerceAtLeast(0), ttftMs = (now - tick).coerceAtLeast(0))
                        }
                        lastGeneratedTick = now
                    }
                    StreamEvent.Finished -> finished = true
                }
                // 精確關聯本次 durable sample；不可相信 transport 提供的 id，或以「最新一筆」
                // 猜測手動驗證 evidence。時間戳與其餘事件原樣轉發，不持久化 payload。
                emit(if (event is StreamEvent.RequestSent) event.copy(sampleId = sample.id) else event)
            }
            if (!finished) throw InferenceFailure(Outcome.PARSER)
            sample = sample.copy(outcome = Outcome.SUCCESS)
        } catch (e: CancellationException) {
            sample = sample.copy(outcome = Outcome.CANCELLED)
            pending = e
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                sample = sample.copy(outcome = Outcome.CANCELLED)
                val cancelled = CancellationException("Inference cancelled")
                pending = cancelled
                throw cancelled
            }
            val category = when (e) {
                is InferenceFailure -> e.outcome
                is SocketTimeoutException -> Outcome.TIMEOUT
                is IOException -> Outcome.NETWORK
                is IllegalStateException ->
                    if (e.message?.contains("Credential scope mismatch") == true) Outcome.CREDENTIAL_SCOPE
                    else Outcome.OTHER
                else -> Outcome.OTHER
            }
            sample = sample.copy(outcome = category, httpStatus = (e as? InferenceFailure)?.status ?: sample.httpStatus)
            // Deliberately no remote exception messages or raw payload in persisted data / UI.
            val failure = InferenceFailure(category, sample.httpStatus)
            pending = failure
            throw failure
        } finally {
            val generationMs = if (firstTick != null && lastGeneratedTick != null) lastGeneratedTick!! - firstTick!! else null
            val output = sample.usage.outputTokens
            sample = sample.copy(
                completedAt = clock.epochMillis(), totalLatencyMs = (clock.monotonicMillis() - tick).coerceAtLeast(0),
                tps = if (sample.outcome == Outcome.SUCCESS && generationMs != null && generationMs > 0 && output != null && output > 1)
                    (output - 1) * 1000.0 / generationMs else null,
            )
            // A failing sink must never mask the inference outcome: degrade to one best-effort
            // retry (transient DB contention is the common cause), then attach as suppressed
            // so the caller's original failure — or success path error — is preserved.
            val inFlight = pending
            try {
                withContext(NonCancellable) { telemetry.save(sample) }
            } catch (saveError: Exception) {
                // An already-propagating outcome is never replaced: save errors attach as
                // suppressed. Only the success path surfaces persistence failure (as error),
                // and cancellation is only rethrown when nothing else is in flight.
                if (saveError is CancellationException && inFlight == null) throw saveError
                try {
                    withContext(NonCancellable) { telemetry.save(sample) }
                } catch (retryError: Exception) {
                    if (inFlight != null) {
                        inFlight.addSuppressed(saveError)
                        inFlight.addSuppressed(retryError)
                    } else {
                        if (retryError is CancellationException) throw retryError
                        saveError.addSuppressed(retryError)
                        throw saveError
                    }
                }
            }
        }
    }
}
