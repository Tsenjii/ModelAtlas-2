package dev.modelatlas.core.network

import dev.modelatlas.core.inference.CredentialAccess
import dev.modelatlas.core.inference.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** PLAY-004: real HTTP parser -> production dispatch -> chat text events. */
class AudioTranscriptProjectionTest {
    private val server = MockWebServer()

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    private fun events(chunk: String): List<StreamEvent> = runBlocking {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: $chunk\n\ndata: [DONE]\n\n"))
        val transport = OpenAiTransport(
            CredentialAccess { _, _ -> "fixture" },
            allowLoopbackHttpForTests = true,
        )
        TransportDispatch(listOf(transport)).stream(
            fixtureTarget("http://127.0.0.1:${server.port}/v1/chat/completions"),
            fixtureMessages(),
        ).toList()
    }

    @Test fun audioTranscriptBecomesChatText() {
        val events = events("""{"choices":[{"index":0,"delta":{"audio":{"transcript":"你好，世界","data":"AQIDBA=="}}}]}""")
        assertEquals("你好，世界", events.filterIsInstance<StreamEvent.AudioDelta>().single().transcriptFragment)
        assertEquals("你好，世界", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
        assertTrue(events.filterIsInstance<StreamEvent.Delta>().none { it.text.contains("AQIDBA==") })
    }

    @Test fun binaryOnlyAudioNeverBecomesText() {
        val events = events("""{"choices":[{"index":0,"delta":{"audio":{"data":"AQIDBA=="}}}]}""")
        assertEquals(1, events.filterIsInstance<StreamEvent.AudioDelta>().size)
        assertTrue(events.none { it is StreamEvent.Delta })
    }

    @Test fun parallelContentDoesNotDuplicateTranscript() {
        val events = events("""{"choices":[{"index":0,"delta":{"content":"文字回覆","audio":{"transcript":"文字回覆","data":"AQIDBA=="}}}]}""")
        assertEquals("文字回覆", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
    }
}
