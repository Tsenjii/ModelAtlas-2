package dev.modelatlas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VisionImagePayloadTest {
    private fun png() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x01, 0x02, 0x03,
    )

    @Test fun imageBytesUseSignatureNotClaimedFilename() {
        val result = VisionImagePayload.prepare(png(), "fake.jpeg")
        assertTrue(result.url.startsWith("data:image/png;base64,"))
        assertEquals("fake.jpeg", result.filename)
        val encoded = result.url.substringAfter("base64,")
        assertTrue(png().contentEquals(Base64.getDecoder().decode(encoded)))
    }

    @Test fun jpegAndWebpSignaturesAreRecognized() {
        assertEquals("image/jpeg", VisionImagePayload.detectedMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        val webp = "RIFF0000WEBP".toByteArray(Charsets.US_ASCII)
        assertEquals("image/webp", VisionImagePayload.detectedMime(webp))
    }

    @Test fun plainTextSpoofingImageExtensionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionImagePayload.prepare("not an image".toByteArray(), "cat.png")
        }
    }

    @Test fun oversizedImageRejectedBeforeEncoding() {
        val tooBig = ByteArray(VisionImagePayload.MAX_IMAGE_BYTES + 1)
        tooBig[0] = 0xFF.toByte()
        tooBig[1] = 0xD8.toByte()
        tooBig[2] = 0xFF.toByte()
        assertThrows(IllegalArgumentException::class.java) { VisionImagePayload.prepare(tooBig, "big.jpg") }
    }

    @Test fun emptyImageRejected() {
        assertThrows(IllegalArgumentException::class.java) { VisionImagePayload.prepare(byteArrayOf(), "empty.png") }
    }

    @Test fun filenameCannotSmuggleNewlinesOrPaths() {
        val result = VisionImagePayload.prepare(png(), "folder/name\r\nsecret.png")
        assertEquals("namesecret.png", result.filename)
    }
}
