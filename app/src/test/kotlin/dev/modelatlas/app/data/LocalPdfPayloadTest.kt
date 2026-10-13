package dev.modelatlas.app.data

import dev.modelatlas.core.multimodal.FileRefPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPdfPayloadTest {
    private val pdf = "%PDF-1.7\nfixture".toByteArray(Charsets.US_ASCII)

    @Test fun localPdfIsBoundedSanitizedAndReadyForWire() {
        val file = LocalPdfPayload.prepare(pdf, "../private\\notes.pdf")
        assertEquals("notes.pdf", file.filename)
        assertEquals(null, file.fileId)
        assertTrue(file.fileDataUrl!!.startsWith("data:application/pdf;base64,"))
        assertTrue(LocalPdfPayload.isValidForSend(file))
    }

    @Test fun rejectNonPdfAndOversizedContentBeforeEncoding() {
        assertTrue(runCatching { LocalPdfPayload.prepare("not pdf".toByteArray(), "f.pdf") }.isFailure)
        val huge = ByteArray(LocalPdfPayload.MAX_BYTES + 1)
        "%PDF-".toByteArray().copyInto(huge)
        assertTrue(runCatching { LocalPdfPayload.prepare(huge, "f.pdf") }.isFailure)
    }

    @Test fun vmCannotSendRemoteFileIdArbitraryMimeOrInvalidBase64() {
        assertFalse(LocalPdfPayload.isValidForSend(FileRefPart(fileId = "file-external")))
        assertFalse(LocalPdfPayload.isValidForSend(FileRefPart(filename = "a.pdf", fileDataUrl = "data:text/plain;base64,JVBERi0=")))
        assertFalse(LocalPdfPayload.isValidForSend(FileRefPart(filename = "a.pdf", fileDataUrl = "data:application/pdf;base64,@@@@")))
        assertFalse(LocalPdfPayload.isValidForSend(FileRefPart(filename = "secret/path.pdf", fileDataUrl = LocalPdfPayload.prepare(pdf, "a.pdf").fileDataUrl)))
        assertFalse(LocalPdfPayload.isValidForSend(FileRefPart(filename = "fake.pdf", fileDataUrl = "data:application/pdf;base64,bm90LXBkZg==")))
    }
}
