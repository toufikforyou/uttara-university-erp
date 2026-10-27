package bd.edu.uttarauniversity.erp.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DownloadUtilsTest {

    private val pdfBytes = "%PDF-1.7\n%âãÏÓ\n1 0 obj".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun normalizeMimeType_stripsParametersAndLowercases() {
        assertEquals("application/pdf", DownloadUtils.normalizeMimeType("Application/PDF; charset=binary"))
        assertNull(DownloadUtils.normalizeMimeType(""))
        assertNull(DownloadUtils.normalizeMimeType(null))
    }

    @Test
    fun parseContentDisposition_quotedFileName() {
        assertEquals(
            "Result Sheet.pdf",
            DownloadUtils.parseContentDispositionFileName("attachment; filename=\"Result Sheet.pdf\"")
        )
    }

    @Test
    fun parseContentDisposition_unquotedFileName() {
        assertEquals(
            "admit_card.pdf",
            DownloadUtils.parseContentDispositionFileName("inline; filename=admit_card.pdf; size=1200")
        )
    }

    @Test
    fun parseContentDisposition_prefersEncodedFileName() {
        assertEquals(
            "পরীক্ষা ফলাফল.pdf",
            DownloadUtils.parseContentDispositionFileName(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E0%A6%AA%E0%A6%B0%E0%A7%80%E0%A6%95" +
                    "%E0%A7%8D%E0%A6%B7%E0%A6%BE%20%E0%A6%AB%E0%A6%B2%E0%A6%BE%E0%A6%AB%E0%A6%B2.pdf"
            )
        )
    }

    @Test
    fun parseContentDisposition_missingFileName_returnsNull() {
        assertNull(DownloadUtils.parseContentDispositionFileName("attachment"))
        assertNull(DownloadUtils.parseContentDispositionFileName(null))
    }

    @Test
    fun fileNameFromUrl_decodesLastPathSegment() {
        assertEquals(
            "Payment Slip.pdf",
            DownloadUtils.fileNameFromUrl("https://erp.uttarauniversity.edu.bd/files/Payment%20Slip.pdf?token=1#x")
        )
        assertNull(DownloadUtils.fileNameFromUrl("https://erp.uttarauniversity.edu.bd/"))
        assertNull(DownloadUtils.fileNameFromUrl("blob:https://erp.uttarauniversity.edu.bd/1234"))
        assertNull(DownloadUtils.fileNameFromUrl("data:application/pdf;base64,AAAA"))
    }

    @Test
    fun sanitizeFileName_replacesIllegalCharacters() {
        assertEquals("a_b_c_.pdf", DownloadUtils.sanitizeFileName("a/b\\c?.pdf"))
        assertEquals(DownloadUtils.DEFAULT_FILE_NAME, DownloadUtils.sanitizeFileName("  ..  "))
        assertEquals(DownloadUtils.DEFAULT_FILE_NAME, DownloadUtils.sanitizeFileName(null))
    }

    @Test
    fun sanitizeFileName_limitsLengthAndKeepsExtension() {
        val name = DownloadUtils.sanitizeFileName("x".repeat(300) + ".pdf")
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun resolveFileName_usesContentDispositionFirst() {
        assertEquals(
            "transcript.pdf",
            DownloadUtils.resolveFileName(
                "https://erp.uttarauniversity.edu.bd/download.php?id=4",
                "attachment; filename=\"transcript.pdf\"",
                "application/pdf"
            )
        )
    }

    @Test
    fun resolveFileName_replacesScriptExtensionWithMimeExtension() {
        assertEquals(
            "print_result.pdf",
            DownloadUtils.resolveFileName(
                "https://erp.uttarauniversity.edu.bd/print_result.php?id=4", null, "application/pdf"
            )
        )
    }

    @Test
    fun resolveFileName_appendsMissingExtension() {
        assertEquals(
            "report.pdf",
            DownloadUtils.resolveFileName("https://erp.uttarauniversity.edu.bd/report", null, "application/pdf")
        )
        assertEquals("download.pdf", DownloadUtils.resolveFileName(null, null, "application/pdf"))
    }

    @Test
    fun resolveFileName_keepsNameForUnknownMimeType() {
        assertEquals(
            "notes.docx",
            DownloadUtils.resolveFileName(
                "https://erp.uttarauniversity.edu.bd/notes.docx", null, "application/octet-stream"
            )
        )
    }

    @Test
    fun isPdf_detectsByMimeTypeOrFileName() {
        assertTrue(DownloadUtils.isPdf("https://x/a", null, "application/pdf"))
        assertTrue(DownloadUtils.isPdf("https://x/a", "attachment; filename=\"a.PDF\"", "application/octet-stream"))
        assertTrue(DownloadUtils.isPdf("https://x/a.pdf", null, null))
        assertFalse(DownloadUtils.isPdf("https://x/a.pdf", null, "image/png"))
        assertFalse(DownloadUtils.isPdf("https://x/a.docx", null, null))
    }

    @Test
    fun hasPdfSignature_detectsPdfHeader() {
        assertTrue(DownloadUtils.hasPdfSignature(pdfBytes))
        assertTrue(DownloadUtils.hasPdfSignature(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + pdfBytes))
        assertFalse(DownloadUtils.hasPdfSignature("<html><body>Login</body></html>".toByteArray()))
        assertFalse(DownloadUtils.hasPdfSignature(ByteArray(0)))
        assertFalse(DownloadUtils.hasPdfSignature("%PD".toByteArray()))
    }

    @Test
    fun resolveMetadata_sniffsPdfServedAsOctetStream() {
        val metadata = DownloadUtils.resolveMetadata(
            url = "https://erp.uttarauniversity.edu.bd/student/download.php?id=7",
            requestContentDisposition = null,
            requestMimeType = "application/octet-stream",
            responseMimeType = "application/octet-stream",
            head = pdfBytes
        )
        assertEquals(FileMetadata("download.pdf", "application/pdf"), metadata)
        assertTrue(metadata.isPdf)
    }

    @Test
    fun resolveMetadata_prefersResponseHeaders() {
        val metadata = DownloadUtils.resolveMetadata(
            url = "https://erp.uttarauniversity.edu.bd/export",
            requestContentDisposition = null,
            requestMimeType = "application/octet-stream",
            responseContentDisposition = "attachment; filename=\"courses.xlsx\"",
            responseMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; charset=binary",
            head = "PK".toByteArray()
        )
        assertEquals("courses.xlsx", metadata.fileName)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", metadata.mimeType)
        assertFalse(metadata.isPdf)
    }

    @Test
    fun resolveMetadata_fallsBackToExtensionThenDefault() {
        assertEquals(
            FileMetadata("syllabus.pdf", "application/pdf"),
            DownloadUtils.resolveMetadata("https://x/syllabus.pdf", null, null)
        )
        assertEquals(
            FileMetadata("archive.bin", DownloadUtils.DEFAULT_MIME_TYPE),
            DownloadUtils.resolveMetadata("https://x/archive.bin", null, "application/force-download")
        )
    }

    @Test
    fun parseDataUrl_decodesBase64() {
        val encoded = Base64.getEncoder().encodeToString(pdfBytes)
        val data = DownloadUtils.parseDataUrl("data:application/pdf;base64,$encoded")!!
        assertEquals("application/pdf", data.mimeType)
        assertArrayEquals(pdfBytes, data.bytes)
    }

    @Test
    fun parseDataUrl_decodesPercentEncodedText() {
        val data = DownloadUtils.parseDataUrl("data:text/plain;charset=utf-8,Hello%20World")!!
        assertEquals("text/plain", data.mimeType)
        assertEquals("Hello World", String(data.bytes))
    }

    @Test
    fun parseDataUrl_rejectsInvalidInput() {
        assertNull(DownloadUtils.parseDataUrl(null))
        assertNull(DownloadUtils.parseDataUrl("https://x/a.pdf"))
        assertNull(DownloadUtils.parseDataUrl("data:application/pdf;base64"))
        assertNull(DownloadUtils.parseDataUrl("data:application/pdf;base64,***"))
    }

    @Test
    fun toJsStringLiteral_escapesSpecialCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003C/script\\u003E\"", DownloadUtils.toJsStringLiteral("a\"b\\c\n</script>"))
        assertEquals("null", DownloadUtils.toJsStringLiteral(null))
        assertEquals("\"\\u0001\"", DownloadUtils.toJsStringLiteral("\u0001"))
    }

    @Test
    fun buildBlobDownloadScript_embedsEscapedValues() {
        val script = DownloadUtils.buildBlobDownloadScript(
            "blob:https://erp.uttarauniversity.edu.bd/abc'\"", "attachment; filename=\"a.pdf\"",
            "application/pdf", "UUAndroidDownloader"
        )
        assertTrue(script.contains("window.UUAndroidDownloader"))
        assertTrue(script.contains("fetch(\"blob:https://erp.uttarauniversity.edu.bd/abc'\\\"\")"))
        assertTrue(script.contains("\"attachment; filename=\\\"a.pdf\\\"\""))
        assertTrue(script.contains("bridge.onBlobData"))
    }

    @Test
    fun uniqueFileName_addsCounterWhenTaken() {
        val taken = setOf("result.pdf", "result (1).pdf", "notes")
        assertEquals("result (2).pdf", DownloadUtils.uniqueFileName("result.pdf") { it in taken })
        assertEquals("notes (1)", DownloadUtils.uniqueFileName("notes") { it in taken })
        assertEquals("new.pdf", DownloadUtils.uniqueFileName("new.pdf") { it in taken })
    }

    private val payslipForm = FormSubmission(
        action = "https://erp.uttarauniversity.edu.bd/student/manual-payslip",
        body = "_token=abc&amount=5000",
        timestampMillis = 1_000L
    )

    @Test
    fun shouldReplayFormSubmission_matchesSameUrl() {
        assertTrue(
            DownloadUtils.shouldReplayFormSubmission(
                payslipForm, "https://ERP.uttarauniversity.edu.bd:443/student/manual-payslip#top", 5_000L
            )
        )
    }

    @Test
    fun shouldReplayFormSubmission_rejectsOtherUrlsAndStaleForms() {
        assertFalse(
            DownloadUtils.shouldReplayFormSubmission(
                payslipForm, "https://erp.uttarauniversity.edu.bd/files/payslip.pdf", 5_000L
            )
        )
        assertFalse(
            DownloadUtils.shouldReplayFormSubmission(
                payslipForm, "https://erp.uttarauniversity.edu.bd/student/manual-payslip?id=2", 5_000L
            )
        )
        assertFalse(
            DownloadUtils.shouldReplayFormSubmission(
                payslipForm, payslipForm.action,
                payslipForm.timestampMillis + DownloadUtils.FORM_SUBMISSION_MAX_AGE_MILLIS + 1
            )
        )
        assertFalse(DownloadUtils.shouldReplayFormSubmission(null, payslipForm.action, 5_000L))
        assertFalse(DownloadUtils.shouldReplayFormSubmission(payslipForm, null, 5_000L))
    }

    @Test
    fun normalizeUrlForComparison_handlesPortsAndInvalidUrls() {
        assertEquals("https://x.edu/a?b=1", DownloadUtils.normalizeUrlForComparison("HTTPS://X.edu:443/a?b=1#c"))
        assertEquals("http://x.edu:8080/", DownloadUtils.normalizeUrlForComparison("http://x.edu:8080"))
        assertNull(DownloadUtils.normalizeUrlForComparison("not a url"))
    }

    @Test
    fun looksLikeHtml_detectsByMimeTypeOrContent() {
        assertTrue(DownloadUtils.looksLikeHtml("text/html; charset=UTF-8"))
        assertTrue(DownloadUtils.looksLikeHtml(null, "\uFEFF\n  <!DOCTYPE html><html>".toByteArray()))
        assertTrue(DownloadUtils.looksLikeHtml("application/octet-stream", "<HTML><body>".toByteArray()))
        assertFalse(DownloadUtils.looksLikeHtml("application/pdf", pdfBytes))
        assertFalse(DownloadUtils.looksLikeHtml(null))
    }

    @Test
    fun buildFormCaptureScript_recordsPostForms() {
        val script = DownloadUtils.buildFormCaptureScript("UUAndroidDownloader")
        assertTrue(script.contains("window.UUAndroidDownloader"))
        assertTrue(script.contains("bridge.onFormSubmit(action, params.toString())"))
        assertTrue(script.contains("HTMLFormElement.prototype.submit"))
        assertTrue(script.contains("'submit'"))
    }
}
