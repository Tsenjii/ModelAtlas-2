package dev.modelatlas.core.network

import dev.modelatlas.core.inference.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAY-004: transcript is visible, while encoded audio stays out of chat. */
class AudioTranscriptProjectionTest {
    private val parser = OpenAiTransport({ _, _ -> "fixture" })

    @Test fun transcriptOnlyAudioProjectsIntoText() {
        val events = parser.parse(
            """{"choices":[{"index":0,"delta":{"audio":{"transcript":"你好，世界","data":"AQIDBA=="}}}]}""",
        )
        assertEquals("你好，世界", events.filterIsInstance<StreamEvent.AudioDelta>().single().transcriptFragment)
        assertEquals("你好，世界", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
        assertTrue(events.filterIsInstance<StreamEvent.Delta>().none { it.text.contains("AQIDBA==") })
    }

    @Test fun binaryOnlyAudioNeverBecomesText() {
        val events = parser.parse(
            """{"choices":[{"index":0,"delta":{"audio":{"data":"AQIDBA=="}}}]}""",
        )
        assertEquals(1, events.filterIsInstance<StreamEvent.AudioDelta>().size)
        assertTrue(events.none { it is StreamEvent.Delta })
    }

    @Test fun parallelContentDoesNotDuplicateTranscript() {
        val events = parser.parse(
            """{"choices":[{"index":0,"delta":{"content":"文字回覆","audio":{"transcript":"文字回覆","data":"AQIDBA=="}}}]}""",
        )
        assertEquals("文字回覆", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
    }
}
