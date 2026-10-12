package dev.modelatlas.core.network

import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseDecoderTest {
    @Test
    fun dataIsEmittedOnTheBlankLineBoundary() {
        val decoder = SseDecoder()
        assertNull(decoder.line("""data: {"a":1}"""))
        assertEquals("""{"a":1}""", decoder.line(""))
    }

    @Test
    fun aDataLineIsHeldUntilTheBlankLineArrives() {
        val decoder = SseDecoder()
        assertNull(decoder.line("data: first"))
        assertNull(decoder.line("data: second"))
        assertEquals("first\nsecond", decoder.line(""))
    }

    @Test
    fun consecutiveEventsAreNotMerged() {
        val decoder = SseDecoder()
        assertNull(decoder.line("data: one"))
        assertEquals("one", decoder.line(""))
        assertNull(decoder.line("data: two"))
        assertEquals("two", decoder.line(""))
    }

    @Test
    fun multipleDataLinesAreJoinedWithNewline() {
        val decoder = SseDecoder()
        assertNull(decoder.line("""data: {"choices":[{"index":0,"""))
        assertNull(decoder.line("""data: "delta":{"content":"split"}}]}"""))
        assertEquals("{\"choices\":[{\"index\":0,\n\"delta\":{\"content\":\"split\"}}]}", decoder.line(""))
    }

    @Test
    fun onlyOneLeadingSpaceIsStripped() {
        val decoder = SseDecoder()
        assertNull(decoder.line("data:  two-spaces"))
        assertEquals(" two-spaces", decoder.line(""))
        assertNull(decoder.line("data: none"))
        assertEquals("none", decoder.line(""))
    }

    @Test
    fun dataWithoutSpaceKeepsItsPayload() {
        val decoder = SseDecoder()
        assertNull(decoder.line("""data:{"a":1}"""))
        assertEquals("""{"a":1}""", decoder.line(""))
    }

    @Test
    fun nonDataFieldsAreIgnored() {
        val decoder = SseDecoder()
        assertNull(decoder.line(": keep-alive"))
        assertNull(decoder.line("event: message"))
        assertNull(decoder.line("id: 42"))
        assertNull(decoder.line("retry: 1000"))
        assertNull(decoder.line(""))
        assertNull(decoder.line("data: payload"))
        assertEquals("payload", decoder.line(""))
    }

    @Test
    fun fieldNamesAreCaseSensitive() {
        val decoder = SseDecoder()
        assertNull(decoder.line("Data: payload"))
        assertNull(decoder.line("DATA: payload"))
        assertNull(decoder.line(""))
        assertNull(decoder.line("data: real"))
        assertEquals("real", decoder.line(""))
    }

    @Test
    fun blankLineWithoutDataEmitsNothing() {
        val decoder = SseDecoder()
        assertNull(decoder.line(""))
        assertNull(decoder.line(""))
        assertNull(decoder.line("data: after-empty"))
        assertEquals("after-empty", decoder.line(""))
    }

    @Test
    fun anEventAtTheSizeLimitIsAccepted() {
        val decoder = SseDecoder(maxEventChars = 8)
        assertNull(decoder.line("data: 12345678"))
        assertEquals("12345678", decoder.line(""))
    }

    @Test
    fun anEventOverTheSizeLimitIsAParserFailure() {
        val decoder = SseDecoder(maxEventChars = 8)
        val result = runCatching { decoder.line("data: 123456789") }
        assertEquals(Outcome.PARSER, (result.exceptionOrNull() as InferenceFailure).outcome)
    }

    @Test
    fun aCompletionMarkerIsDeliveredLikeAnyOtherData() {
        val decoder = SseDecoder()
        assertNull(decoder.line("data: [DONE]"))
        assertEquals("[DONE]", decoder.line(""))
        assertNull(decoder.line(""))
    }
}
