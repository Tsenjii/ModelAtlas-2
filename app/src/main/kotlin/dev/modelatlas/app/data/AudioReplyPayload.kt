package dev.modelatlas.app.data
import java.util.Base64

internal object AudioReplyPayload {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_BASE64_CHARS = ((MAX_BYTES + 2) / 3) * 4

    class Prepared internal constructor(val dataBase64: String, val extension: String) {
        override fun toString() = "AudioReply(redacted)"
        fun decodeBytes(): ByteArray = Base64.getDecoder().decode(dataBase64)
    }

    fun fromWire(encoded: String): Prepared {
        require(encoded.length in 1..MAX_BASE64_CHARS && encoded.none { it.isWhitespace() })
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val format = LocalAudioPayload.detectFormat(bytes) ?: throw IllegalArgumentException("Invalid audio")
        require(Base64.getEncoder().encodeToString(bytes) == encoded)
        return Prepared(encoded, format)
    }
}
