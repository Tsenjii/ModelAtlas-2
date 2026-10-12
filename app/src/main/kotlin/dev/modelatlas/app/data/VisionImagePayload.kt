package dev.modelatlas.app.data

import dev.modelatlas.core.multimodal.ImageUrlPart
import java.util.Base64

/** PLAY-004: bounded, local-only image serialization. No URI, file path or raw bytes enter telemetry. */
internal object VisionImagePayload {
    const val MAX_IMAGE_BYTES: Int = 4 * 1024 * 1024

    fun prepare(bytes: ByteArray, filename: String?): ImageUrlPart {
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "Image exceeds 4 MiB or is empty" }
        val mime = detectedMime(bytes) ?: throw IllegalArgumentException("Unsupported image signature")
        val safeName = filename?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.filter { it.code in 32..126 || it.code >= 160 }
            ?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "image"
        return ImageUrlPart(
            url = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes),
            filename = safeName,
        )
    }

    /** Trust decoded signatures, never untrusted ContentResolver MIME labels or suffixes. */
    internal fun detectedMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}
