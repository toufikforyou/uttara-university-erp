package bd.edu.uttarauniversity.erp.download

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

/**
 * File name and MIME type resolved for a downloaded file.
 */
data class FileMetadata(val fileName: String, val mimeType: String) {
    val isPdf: Boolean get() = mimeType == DownloadUtils.PDF_MIME_TYPE
}

/**
 * Decoded contents of a `data:` URL.
 */
class DataUrl(val mimeType: String?, val bytes: ByteArray)

/**
 * Pure helpers used by the download pipeline. Kept free of Android APIs so they can be unit tested.
 */
object DownloadUtils {
    const val PDF_MIME_TYPE = "application/pdf"
    const val DEFAULT_MIME_TYPE = "application/octet-stream"
    const val DEFAULT_FILE_NAME = "download"
    private const val MAX_FILE_NAME_LENGTH = 120
    private const val PDF_SIGNATURE_SEARCH_LIMIT = 1024

    private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)

    /** Generic MIME types that tell us nothing about the real content. */
    private val GENERIC_MIME_TYPES = setOf(
        DEFAULT_MIME_TYPE, "binary/octet-stream", "application/download", "application/force-download",
        "application/x-download", "application/unknown"
    )

    /** Extensions of server-side scripts that should not end up as the extension of a saved file. */
    private val SCRIPT_EXTENSIONS = setOf(
        "php", "asp", "aspx", "jsp", "jspx", "cgi", "pl", "do", "action", "htm", "html", "py"
    )

    private val MIME_TO_EXTENSION = mapOf(
        PDF_MIME_TYPE to "pdf",
        "application/x-pdf" to "pdf",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/zip" to "zip",
        "text/csv" to "csv",
        "text/plain" to "txt",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp"
    )

    private val EXTENSION_TO_MIME = mapOf(
        "pdf" to PDF_MIME_TYPE,
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip",
        "csv" to "text/csv",
        "txt" to "text/plain",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp"
    )

    private val ENCODED_FILE_NAME_REGEX =
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val QUOTED_FILE_NAME_REGEX =
        Regex("""filename\s*=\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
    private val PLAIN_FILE_NAME_REGEX =
        Regex("""filename\s*=\s*([^;"\s]+)""", RegexOption.IGNORE_CASE)
    private val ILLEGAL_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\x00-\x1F]""")

    /** Strips parameters (e.g. `; charset=utf-8`) and lowercases a MIME type. */
    fun normalizeMimeType(mimeType: String?): String? {
        val value = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return if (value.isNullOrEmpty()) null else value
    }

    fun isGenericMimeType(mimeType: String?): Boolean {
        val normalized = normalizeMimeType(mimeType)
        return normalized == null || normalized in GENERIC_MIME_TYPES
    }

    fun extensionForMimeType(mimeType: String?): String? = MIME_TO_EXTENSION[normalizeMimeType(mimeType)]

    fun mimeTypeForFileName(fileName: String?): String? =
        extensionOf(fileName)?.let { EXTENSION_TO_MIME[it] }

    fun extensionOf(fileName: String?): String? {
        if (fileName == null) return null
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** Extracts the file name from a `Content-Disposition` header, preferring RFC 5987 `filename*`. */
    fun parseContentDispositionFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null

        ENCODED_FILE_NAME_REGEX.find(contentDisposition)?.let { match ->
            val charset = match.groupValues[1].ifBlank { "UTF-8" }
            val decoded = runCatching {
                URLDecoder.decode(match.groupValues[2].trim().replace("+", "%2B"), charset)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }

        QUOTED_FILE_NAME_REGEX.find(contentDisposition)?.let { match ->
            val value = match.groupValues[1].replace(Regex("""\\(.)"""), "$1")
            if (value.isNotBlank()) return value
        }

        return PLAIN_FILE_NAME_REGEX.find(contentDisposition)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /** Returns the last path segment of [url], percent-decoded, or null when there is none. */
    fun fileNameFromUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("data:") || url.startsWith("blob:")) return null
        val path = runCatching { URI(url).path }.getOrNull()
            ?: url.substringBefore('?').substringBefore('#').substringAfter("://", "")
                .substringAfter('/', "")
        return path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    /** Replaces characters that are not allowed in file names and limits the length. */
    fun sanitizeFileName(fileName: String?): String {
        val cleaned = (fileName ?: "")
            .replace(ILLEGAL_FILE_NAME_CHARS, "_")
            .trim()
            .trim('.', ' ')
        if (cleaned.isEmpty()) return DEFAULT_FILE_NAME
        if (cleaned.length <= MAX_FILE_NAME_LENGTH) return cleaned

        val extension = extensionOf(cleaned)
        return if (extension != null && extension.length < 10) {
            cleaned.substring(0, MAX_FILE_NAME_LENGTH - extension.length - 1) + "." + extension
        } else {
            cleaned.substring(0, MAX_FILE_NAME_LENGTH)
        }
    }

    /**
     * Picks a file name for a download from the `Content-Disposition` header, falling back to the URL,
     * and makes sure its extension matches the MIME type.
     */
    fun resolveFileName(url: String?, contentDisposition: String?, mimeType: String?): String {
        val rawName = parseContentDispositionFileName(contentDisposition) ?: fileNameFromUrl(url)
        val name = sanitizeFileName(rawName)
        val expectedExtension = extensionForMimeType(mimeType) ?: return name
        val currentExtension = extensionOf(name)

        return when {
            currentExtension == expectedExtension -> name
            expectedExtension == "jpg" && currentExtension == "jpeg" -> name
            currentExtension == null -> "$name.$expectedExtension"
            currentExtension in SCRIPT_EXTENSIONS ->
                name.substring(0, name.length - currentExtension.length) + expectedExtension
            else -> "$name.$expectedExtension"
        }
    }

    /** Best guess, before downloading, whether the file is a PDF. */
    fun isPdf(url: String?, contentDisposition: String?, mimeType: String?): Boolean {
        val normalized = normalizeMimeType(mimeType)
        if (normalized == PDF_MIME_TYPE || normalized == "application/x-pdf") return true
        if (!isGenericMimeType(normalized)) return false
        val name = parseContentDispositionFileName(contentDisposition) ?: fileNameFromUrl(url)
        return extensionOf(name) == "pdf"
    }

    /** Returns true when the bytes start (allowing for leading junk) with the `%PDF-` signature. */
    fun hasPdfSignature(head: ByteArray): Boolean {
        val limit = minOf(head.size, PDF_SIGNATURE_SEARCH_LIMIT) - PDF_SIGNATURE.size
        for (start in 0..limit) {
            var matches = true
            for (i in PDF_SIGNATURE.indices) {
                if (head[start + i] != PDF_SIGNATURE[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    /**
     * Combines what WebView reported for a download with the actual response headers and the first
     * bytes of the content to decide the final file name and MIME type.
     */
    fun resolveMetadata(
        url: String?,
        requestContentDisposition: String?,
        requestMimeType: String?,
        responseContentDisposition: String? = null,
        responseMimeType: String? = null,
        head: ByteArray = ByteArray(0)
    ): FileMetadata {
        val contentDisposition = responseContentDisposition?.takeIf { it.isNotBlank() }
            ?: requestContentDisposition
        val reportedMimeType = listOf(responseMimeType, requestMimeType)
            .map(::normalizeMimeType)
            .firstOrNull { !isGenericMimeType(it) }

        val mimeType = when {
            hasPdfSignature(head) -> PDF_MIME_TYPE
            reportedMimeType != null -> reportedMimeType
            else -> mimeTypeForFileName(resolveFileName(url, contentDisposition, null))
                ?: DEFAULT_MIME_TYPE
        }
        return FileMetadata(resolveFileName(url, contentDisposition, mimeType), mimeType)
    }

    /** Decodes a `data:[<mime>][;base64],<data>` URL. Returns null if it is not a valid data URL. */
    fun parseDataUrl(dataUrl: String?): DataUrl? {
        if (dataUrl == null || !dataUrl.startsWith("data:", ignoreCase = true)) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null

        val header = dataUrl.substring(5, comma)
        val payload = dataUrl.substring(comma + 1)
        val parts = header.split(';')
        val isBase64 = parts.drop(1).any { it.trim().equals("base64", ignoreCase = true) }
        val mimeType = normalizeMimeType(parts.firstOrNull())

        val bytes = runCatching {
            if (isBase64) {
                Base64.getDecoder().decode(payload.filterNot { it.isWhitespace() })
            } else {
                URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null

        return DataUrl(mimeType, bytes)
    }

    /** Encodes [value] as a JavaScript string literal, safe to embed in injected scripts. */
    fun toJsStringLiteral(value: String?): String {
        if (value == null) return "null"
        val builder = StringBuilder(value.length + 2).append('"')
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '<' -> builder.append("\\u003C")
                '>' -> builder.append("\\u003E")
                ' ' -> builder.append("\\u2028")
                ' ' -> builder.append("\\u2029")
                else -> if (ch < ' ') {
                    builder.append(String.format(Locale.ROOT, "\\u%04X", ch.code))
                } else {
                    builder.append(ch)
                }
            }
        }
        return builder.append('"').toString()
    }

    /**
     * Builds a script that reads a `blob:` URL inside the page (only the page can access it) and hands
     * the contents to the JavaScript interface named [bridgeName] as a data URL.
     */
    fun buildBlobDownloadScript(
        blobUrl: String, contentDisposition: String?, mimeType: String?, bridgeName: String
    ): String {
        val url = toJsStringLiteral(blobUrl)
        val disposition = toJsStringLiteral(contentDisposition ?: "")
        val mime = toJsStringLiteral(mimeType ?: "")
        return """
            (function() {
              var bridge = window.$bridgeName;
              if (!bridge) { return; }
              fetch($url).then(function(response) {
                return response.blob();
              }).then(function(blob) {
                var reader = new FileReader();
                reader.onloadend = function() {
                  bridge.onBlobData(String(reader.result), $disposition, blob.type || $mime);
                };
                reader.onerror = function() {
                  bridge.onBlobError('Unable to read the file');
                };
                reader.readAsDataURL(blob);
              }).catch(function(error) {
                bridge.onBlobError(String(error));
              });
            })();
        """.trimIndent()
    }

    /** Returns [fileName], or `name (n).ext` for the first n that does not [exists]. */
    fun uniqueFileName(fileName: String, exists: (String) -> Boolean): String {
        if (!exists(fileName)) return fileName
        val extension = extensionOf(fileName)
        val base = if (extension != null) fileName.dropLast(extension.length + 1) else fileName
        val suffix = if (extension != null) ".$extension" else ""
        var index = 1
        while (true) {
            val candidate = "$base ($index)$suffix"
            if (!exists(candidate)) return candidate
            index++
        }
    }
}
