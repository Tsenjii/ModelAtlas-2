package dev.modelatlas.app.data

import dev.modelatlas.core.multimodal.AudioInputPart
import java.util.Base64

/**
 * PLAY-004 Audio input: one short, local-only wav/mp3 clip per chat turn.
 * Never trust a ContentResolver MIME or suffix as proof of media type.
 * No URI, path, raw content, or base64 enters telemetry or persistent storage.
 */
internal object LocalAudioPayload {
    const val MAX_BYTES: Int = 4 * 1024 * 1024
    private const val MAX_NAME_CHARS = 80
    private const val MAX_ENCODED_CHARS = ((MAX_BYTES + 2) / 3) * 4

    class Prepared(val part: AudioInputPart, val filename: String) {
        override fun toString(): String = "PreparedAudio(redacted)"
    }

    fun prepare(bytes: ByteArray, filename: String?): Prepared {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Audio exceeds 4 MiB or is empty" }
        val format = detectFormat(bytes) ?: throw IllegalArgumentException("Unsupported audio signature")
        val rawName = filename?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.filter { c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT.toInt() }
            ?.trim()?.take(72)?.takeIf { it.isNotEmpty() } ?: "audio." + format
        val name = if (rawName.endsWith("." + format, ignoreCase = true)) rawName
            else rawName.substringBeforeLast('.', rawName) + "." + format
        return Prepared(AudioInputPart(Base64.getEncoder().encodeToString(bytes), format), name)
    }

    /** Reject forged direct-ViewModel calls before HTTP or credential resolution. */
    fun isValidForSend(clip: Prepared): Boolean {
        val name = clip.filename
        val part = clip.part
        if (name.isBlank() || name.length > MAX_NAME_CHARS || !name.endsWith("." + part.format, true) ||
            name.any { it == '/' || it == '\\' || Character.isISOControl(it) ||
                Character.getType(it) == Character.FORMAT.toInt() }) return false
        if (part.format !in setOf("wav", "mp3")) return false
        if (part.dataBase64.length !in 1..MAX_ENCODED_CHARS) return false
        return try {
            val bytes = Base64.getDecoder().decode(part.dataBase64)
            bytes.isNotEmpty() && bytes.size <= MAX_BYTES && detectFormat(bytes) == part.format
        } catch (_: IllegalArgumentException) { false }
    }

    private fun matches(bytes: ByteArray, offset: Int, magic: String): Boolean =
        bytes.size >= offset + magic.length && magic.indices.all { i ->
            bytes[offset + i] == magic[i].code.toByte()
        }

    internal fun detectFormat(bytes: ByteArray): String? {
        if (matches(bytes, 0, "RIFF") && matches(bytes, 8, "WAVE")) return "wav"
        if (bytes.size >= 10 && matches(bytes, 0, "ID3") && (bytes[3].toInt() and 0xFF) in 2..4) return "mp3"
        if (bytes.size >= 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val version = (b1 shr 3) and 0x03
            val layer = (b1 shr 1) and 0x03
            val bitrate = (b2 shr 4) and 0x0F
            val sampleRate = (b2 shr 2) and 0x03
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && version != 1 && layer != 0 &&
                bitrate in 1..14 && sampleRate != 3) return "mp3"
        }
        return null
    }
}
