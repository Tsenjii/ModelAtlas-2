package dev.modelatlas.core.network

import dev.modelatlas.core.inference.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTranscriptProjectionTest {
    private val transport = OpenAiTransport({ _, _ -> "fixture" })

    @Test fun transcriptProjectsIntoText() {
        val events = transport.parse("""{"choices":[{"delta":{"audio":{"transcript":"hello","data":"AQID"}}}]}""")
        assertEquals("hello", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
        assertTrue(events.none { it is StreamEvent.Delta && it.text.contains("AQID") })
    }
}
