package dev.modelatlas.app.data

import dev.modelatlas.core.multimodal.FileRefPart
import java.util.Base64

/**
 * PLAY-004 / Files first production slice: local PDF only.
 * Do not trust picker MIME, display names or arbitrary file IDs supplied by callers.
 * Raw content is held only for a single inference request; no DB / telemetry persistence.
 */
internal object LocalPdfPayload {
    const val MAX_BYTES: Int = 4 * 1024 * 1024
    private const val PREFIX = "data:application/pdf;base64,"
    private val MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-

    fun prepare(bytes: ByteArray, filename: String?): FileRefPart {
        require(isPdf(bytes)) { "File is not a PDF" }
        require(bytes.size <= MAX_BYTES) { "PDF exceeds 4 MiB" }
        val name = filename?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.filter { ch -> !Character.isISOControl(ch) && Character.getType(ch) != Character.FORMAT.toInt() }
            ?.trim()?.take(75)?.takeIf { it.isNotBlank() } ?: "document.pdf"
        val safeName = if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"
        return FileRefPart(
            filename = safeName,
            fileDataUrl = PREFIX + Base64.getEncoder().encodeToString(bytes),
        )
    }

    /** ViewModel boundary: GetContent is not the only possible caller. Fail before HTTP. */
    fun isValidForSend(file: FileRefPart): Boolean {
        if (file.fileId != null) return false
        val name = file.filename ?: return false
        if (!name.endsWith(".pdf", ignoreCase = true) || name.any { it == '/' || it == '\\' || Character.isISOControl(it) }) return false
        val url = file.fileDataUrl ?: return false
        if (!url.startsWith(PREFIX)) return false
        val size = url.length - PREFIX.length
        if (size == 0 || size > ((MAX_BYTES + 2) / 3) * 4) return false
        return try {
            val bytes = Base64.getDecoder().decode(url.substring(PREFIX.length))
            isPdf(bytes) && bytes.size <= MAX_BYTES
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.indices.take(MAGIC.size).all { i -> bytes[i] == MAGIC[i] }
}
