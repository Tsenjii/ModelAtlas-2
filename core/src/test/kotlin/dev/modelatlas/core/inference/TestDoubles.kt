package dev.modelatlas.core.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

const val PROMPT_MARKER = "PROMPT_MARKER_MUST_NOT_BE_PERSISTED"
const val CREDENTIAL_FIXTURE = "fixture-credential-value-must-not-leak"
const val LEAKY_MESSAGE_MARKER = "LEAKY_MESSAGE_MUST_NOT_ESCAPE"

internal fun fixtureMessages(): List<PromptMessage> = listOf(
    PromptMessage("system", "SYSTEM_$PROMPT_MARKER"),
    PromptMessage("user", "USER_$PROMPT_MARKER"),
)

internal fun fixtureTarget(endpointUrl: String = "https://api.fixture.invalid/v1/chat/completions") = InferenceTarget(
    providerId = "provider-fixture",
    accountId = "account-fixture",
    credentialId = "safe-credential-fixture",
    endpointId = "endpoint-fixture",
    endpointUrl = endpointUrl,
    modelId = "fixture-model",
    canonicalModelId = "canonical-fixture-model",
    group = "group-fixture",
)

internal class RecordingTelemetry : TelemetrySink {
    val saves: MutableList<PerformanceSample> = mutableListOf()

    override suspend fun save(sample: PerformanceSample) {
        saves += sample
    }

    fun last(): PerformanceSample = saves.last()

    fun first(): PerformanceSample = saves.first()

    fun persistedText(): String = saves.joinToString(separator = "\n")
}

internal class StepClock(private val step: Long = 10L, private val epochBase: Long = 1_000L) : Clock {
    var monotonic: Long = 0L
        private set
    var epoch: Long = epochBase
        private set

    override fun monotonicMillis(): Long {
        monotonic += step
        return monotonic
    }

    override fun epochMillis(): Long {
        epoch += step
        return epoch
    }
}

internal class ScriptedTransport(private val script: List<StreamEvent>) : InferenceTransport {
    var calls: Int = 0
        private set
    val delivered: MutableList<StreamEvent> = mutableListOf()

    override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
        calls++
        for (event in script) {
            delivered += event
            emit(event)
        }
    }
}

internal class FailingTransport(
    private val before: List<StreamEvent>,
    private val failure: Throwable,
) : InferenceTransport {
    override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
        for (event in before) emit(event)
        throw failure
    }
}

internal class HangingTransport(private val before: List<StreamEvent>) : InferenceTransport {
    val streaming: CompletableDeferred<Unit> = CompletableDeferred()

    override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
        for (event in before) emit(event)
        streaming.complete(Unit)
        awaitCancellation()
    }
}

internal suspend inline fun captureInferenceFailure(block: () -> Unit): InferenceFailure? = try {
    block()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: InferenceFailure) {
    e
}

internal suspend inline fun captureIllegalArgument(block: () -> Unit): IllegalArgumentException? = try {
    block()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: IllegalArgumentException) {
    e
}
