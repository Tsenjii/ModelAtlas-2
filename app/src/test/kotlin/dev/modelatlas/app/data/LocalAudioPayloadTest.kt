package dev.modelatlas.app.data

import dev.modelatlas.core.multimodal.AudioInputPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioPayloadTest {
    private fun wav(): ByteArray = ByteArray(44).also { bytes ->
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WAVE".toByteArray().copyInto(bytes, 8)
        "fmt ".toByteArray().copyInto(bytes, 12)
    }

    @Test fun preparesBoundedLocalWavAndSanitizedFilename() {
        val result = LocalAudioPayload.prepare(wav(), "../private\\lesson.wav")
        assertEquals("lesson.wav", result.filename)
        assertEquals("wav", result.part.format)
        assertTrue(LocalAudioPayload.isValidForSend(result))
        assertEquals("PreparedAudio(redacted)", result.toString())
    }

    @Test fun acceptsMp3ByHeaderButDoesNotTrustExtension() {
        val bytes = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val result = LocalAudioPayload.prepare(bytes, "lecture.wav")
        assertEquals("mp3", result.part.format)
        assertTrue(result.filename.endsWith(".mp3"))
        assertTrue(LocalAudioPayload.isValidForSend(result))
    }

    @Test fun rejectsForgedOversizedAndMismatchedFormat() {
        assertTrue(runCatching { LocalAudioPayload.prepare("not audio".toByteArray(), "file.mp3") }.isFailure)
        val huge = ByteArray(LocalAudioPayload.MAX_BYTES + 1)
        "RIFF".toByteArray().copyInto(huge)
        "WAVE".toByteArray().copyInto(huge, 8)
        assertTrue(runCatching { LocalAudioPayload.prepare(huge, "file.wav") }.isFailure)
        val original = LocalAudioPayload.prepare(wav(), "file.wav")
        assertFalse(LocalAudioPayload.isValidForSend(LocalAudioPayload.Prepared(AudioInputPart("bm90LWF1ZGlv", "wav"), "audio.wav")))
        assertFalse(LocalAudioPayload.isValidForSend(LocalAudioPayload.Prepared(AudioInputPart("@@@@", "wav"), "audio.wav")))
        assertFalse(LocalAudioPayload.isValidForSend(LocalAudioPayload.Prepared(original.part, "../secret.wav")))
        assertFalse(LocalAudioPayload.isValidForSend(LocalAudioPayload.Prepared(AudioInputPart(original.part.dataBase64, "mp3"), "audio.mp3")))
    }
}
