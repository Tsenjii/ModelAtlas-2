package dev.modelatlas.app.data

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioReplyPayloadTest {
    private val wav = ByteArray(44).also {
        "RIFF".toByteArray().copyInto(it, 0)
        "WAVE".toByteArray().copyInto(it, 8)
    }

    @Test fun validWavDecodesAndToStringNeverLeaksAudio() {
        val reply = AudioReplyPayload.fromWire(Base64.getEncoder().encodeToString(wav))
        assertEquals("wav", reply.extension)
        assertArrayEquals(wav, reply.decodeBytes())
        assertEquals("AudioReply(redacted)", reply.toString())
        assertFalse(reply.toString().contains(reply.dataBase64))
    }

    @Test fun validMp3HeaderAccepted() {
        val mp3 = byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, 0, 1)
        assertEquals("mp3", AudioReplyPayload.fromWire(Base64.getEncoder().encodeToString(mp3)).extension)
    }

    @Test fun rejectInvalidAndOversizedReply() {
        assertThrows(IllegalArgumentException::class.java) { AudioReplyPayload.fromWire("bm90LWF1ZGlv") }
        assertThrows(IllegalArgumentException::class.java) { AudioReplyPayload.fromWire("%%%") }
        assertThrows(IllegalArgumentException::class.java) { AudioReplyPayload.fromWire(" " + Base64.getEncoder().encodeToString(wav)) }
        assertThrows(IllegalArgumentException::class.java) { AudioReplyPayload.fromWire("A".repeat(AudioReplyPayload.MAX_BASE64_CHARS + 1)) }
    }
}
