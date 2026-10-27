package bd.edu.uttarauniversity.erp.download

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * A file the app has downloaded into its private cache, ready to be viewed, saved or shared.
 */
data class DownloadedFile(val file: File, val fileName: String, val mimeType: String) {
    val isPdf: Boolean get() = mimeType == DownloadUtils.PDF_MIME_TYPE
}

/**
 * What WebView reported when the page started a download.
 */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val referer: String? = null,
    /** URL-encoded form body; when set the file is requested with a POST. */
    val formBody: String? = null
)

/**
 * The server answered with a web page although WebView reported a file, e.g. because the file is
 * only returned for the original form submission.
 */
class UnexpectedWebPageException : IOException("The server returned a web page instead of the file")

/**
 * Downloads files the ERP portal serves. Requests carry the WebView's cookies so that files behind
 * the student login (results, admit cards, payment slips...) can be fetched.
 */
class FileDownloader(private val context: Context) {

    private val downloadsDir: File get() = File(context.cacheDir, DOWNLOADS_DIR)

    suspend fun download(request: DownloadRequest): DownloadedFile = withContext(Dispatchers.IO) {
        cleanUpOldFiles()
        val connection = openConnection(request)
        try {
            val target = newTargetFile(DownloadUtils.DEFAULT_FILE_NAME)
            connection.inputStream.use { input ->
                target.outputStream().use { output -> copy(input, output) }
            }

            val head = readHead(target)
            if (DownloadUtils.looksLikeHtml(connection.contentType, head) &&
                !DownloadUtils.looksLikeHtml(request.mimeType)
            ) {
                target.parentFile?.deleteRecursively()
                throw UnexpectedWebPageException()
            }

            val metadata = DownloadUtils.resolveMetadata(
                url = connection.url.toString(),
                requestContentDisposition = request.contentDisposition,
                requestMimeType = request.mimeType,
                responseContentDisposition = connection.getHeaderField("Content-Disposition"),
                responseMimeType = connection.contentType,
                head = head
            )
            moveToFinalName(target, metadata)
        } finally {
            connection.disconnect()
        }
    }

    /** Stores bytes that were produced inside the page (`blob:` and `data:` URLs). */
    suspend fun save(
        bytes: ByteArray, sourceUrl: String?, contentDisposition: String?, mimeType: String?
    ): DownloadedFile = withContext(Dispatchers.IO) {
        cleanUpOldFiles()
        val metadata = DownloadUtils.resolveMetadata(
            url = sourceUrl,
            requestContentDisposition = contentDisposition,
            requestMimeType = mimeType,
            head = bytes.copyOf(minOf(bytes.size, HEAD_SIZE))
        )
        val target = newTargetFile(metadata.fileName)
        target.writeBytes(bytes)
        DownloadedFile(target, metadata.fileName, metadata.mimeType)
    }

    private fun openConnection(request: DownloadRequest): HttpURLConnection {
        var url = URL(request.url)
        var formBody = request.formBody
        repeat(MAX_REDIRECTS + 1) {
            if (url.protocol != "http" && url.protocol != "https") {
                throw IOException("Unsupported download address")
            }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                request.userAgent?.let { setRequestProperty("User-Agent", it) }
                request.referer?.let { setRequestProperty("Referer", it) }
                CookieManager.getInstance().getCookie(url.toString())?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            formBody?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.setRequestProperty("Origin", "${url.protocol}://${url.authority}")
                connection.outputStream.use { it.write(bytes) }
            }

            when (val code = connection.responseCode) {
                in 200..299 -> return connection
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER, HTTP_TEMPORARY_REDIRECT, HTTP_PERMANENT_REDIRECT -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw IOException("Server returned HTTP $code")
                    url = URL(url, location)
                    // Like browsers, only 307 and 308 repeat the POST; other redirects become a GET.
                    if (code != HTTP_TEMPORARY_REDIRECT && code != HTTP_PERMANENT_REDIRECT) formBody = null
                }

                else -> {
                    connection.disconnect()
                    throw IOException("Server returned HTTP $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(HEAD_SIZE)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        buffer.copyOf(total)
    }

    private fun newTargetFile(fileName: String): File {
        val dir = File(downloadsDir, UUID.randomUUID().toString())
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Unable to create download folder")
        return File(dir, fileName)
    }

    private fun moveToFinalName(file: File, metadata: FileMetadata): DownloadedFile {
        val target = File(file.parentFile, metadata.fileName)
        val result = if (target == file || file.renameTo(target)) target else file
        return DownloadedFile(result, metadata.fileName, metadata.mimeType)
    }

    private fun cleanUpOldFiles() {
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MILLIS
        downloadsDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val DOWNLOADS_DIR = "downloads"
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MILLIS = 30_000
        private const val HEAD_SIZE = 1024
        private const val HTTP_TEMPORARY_REDIRECT = 307
        private const val HTTP_PERMANENT_REDIRECT = 308
        private const val MAX_CACHE_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}
