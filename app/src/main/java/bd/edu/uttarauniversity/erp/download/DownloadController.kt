package bd.edu.uttarauniversity.erp.download

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles every download the ERP portal starts: fetches the file, opens PDFs in the in-app viewer
 * and saves everything else to the Downloads folder.
 */
class DownloadController(
    private val context: Context,
    private val scope: CoroutineScope,
    val snackbarHostState: SnackbarHostState
) {
    /** Launches the system storage permission dialog; set by the UI that owns the launcher. */
    var requestStoragePermission: () -> Unit = {}

    private val downloader = FileDownloader(context)
    private var downloadJob: Job? = null
    private var pendingSave: DownloadedFile? = null

    /** Last POST form the page submitted; written from the JavaScript bridge thread. */
    @Volatile
    private var lastFormSubmission: FormSubmission? = null

    /** Name of the file being downloaded, or null when idle. */
    var inProgressFileName by mutableStateOf<String?>(null)
        private set

    /** The PDF currently shown in the in-app viewer. */
    var openPdf by mutableStateOf<DownloadedFile?>(null)
        private set

    /** Called from WebView's DownloadListener. */
    fun onDownloadStart(
        webView: WebView, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?
    ) {
        when {
            url.startsWith("blob:") -> {
                inProgressFileName = DownloadUtils.resolveFileName(null, contentDisposition, mimeType)
                webView.evaluateJavascript(
                    DownloadUtils.buildBlobDownloadScript(url, contentDisposition, mimeType, BRIDGE_NAME), null
                )
            }

            url.startsWith("data:") -> {
                val data = DownloadUtils.parseDataUrl(url)
                if (data == null) {
                    showMessage("This file could not be downloaded.")
                } else {
                    saveInPageFile(data.bytes, contentDisposition, data.mimeType ?: mimeType)
                }
            }

            else -> {
                val form = lastFormSubmission
                val replayForm = DownloadUtils.shouldReplayFormSubmission(form, url, System.currentTimeMillis())
                if (replayForm) lastFormSubmission = null
                startDownload(
                    DownloadRequest(
                        url, userAgent, contentDisposition, mimeType,
                        referer = webView.url,
                        formBody = if (replayForm) form?.body else null
                    )
                )
            }
        }
    }

    /** Installs the form recorder; call when every page finishes loading. */
    fun onPageFinished(webView: WebView) {
        webView.evaluateJavascript(DownloadUtils.buildFormCaptureScript(BRIDGE_NAME), null)
    }

    private fun startDownload(request: DownloadRequest) {
        downloadJob?.cancel()
        inProgressFileName = DownloadUtils.resolveFileName(request.url, request.contentDisposition, request.mimeType)
        downloadJob = scope.launch {
            try {
                handleDownloaded(downloader.download(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnexpectedWebPageException) {
                Log.e(TAG, "Download returned a web page", e)
                showMessage("The file couldn't be generated. Please go back and try again.")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                showMessage("Download failed. Please check your connection and try again.")
            } finally {
                inProgressFileName = null
            }
        }
    }

    private fun saveInPageFile(bytes: ByteArray, contentDisposition: String?, mimeType: String?) {
        scope.launch {
            try {
                handleDownloaded(downloader.save(bytes, null, contentDisposition, mimeType))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Saving in-page file failed", e)
                showMessage("This file could not be downloaded.")
            } finally {
                inProgressFileName = null
            }
        }
    }

    private suspend fun handleDownloaded(downloaded: DownloadedFile) {
        if (downloaded.isPdf) {
            openPdf = downloaded
        } else {
            saveToDownloads(downloaded)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        inProgressFileName = null
    }

    fun closePdf() {
        openPdf = null
    }

    /** Saves [downloaded] to the public Downloads folder, asking for permission on old Android versions. */
    fun save(downloaded: DownloadedFile) {
        scope.launch { saveToDownloads(downloaded) }
    }

    private suspend fun saveToDownloads(downloaded: DownloadedFile) {
        if (FileSaver.needsStoragePermission(context)) {
            pendingSave = downloaded
            requestStoragePermission()
            return
        }

        val uri = try {
            FileSaver.saveToDownloads(context, downloaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Saving to Downloads failed", e)
            showMessage("Couldn't save ${downloaded.fileName} to Downloads.")
            return
        }

        val result = snackbarHostState.showSnackbar(
            message = "Saved ${downloaded.fileName} to ${FileSaver.displayLocation}",
            actionLabel = "Open",
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) {
            if (downloaded.isPdf) {
                openPdf = downloaded
            } else if (!FileSaver.openWithExternalApp(context, uri, downloaded.mimeType)) {
                showMessage("No app found to open this file.")
            }
        }
    }

    /** Result of the storage permission request started by [saveToDownloads]. */
    fun onStoragePermissionResult(granted: Boolean) {
        val downloaded = pendingSave ?: return
        pendingSave = null
        if (granted) {
            save(downloaded)
        } else {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Storage permission is needed to save files.",
                    actionLabel = "Share",
                    withDismissAction = true
                )
                if (result == SnackbarResult.ActionPerformed) share(downloaded)
            }
        }
    }

    fun share(downloaded: DownloadedFile) {
        try {
            FileSaver.share(context, downloaded)
        } catch (e: Exception) {
            Log.e(TAG, "Sharing failed", e)
            showMessage("Couldn't share ${downloaded.fileName}.")
        }
    }

    fun openExternally(downloaded: DownloadedFile) {
        val opened = try {
            FileSaver.openWithExternalApp(context, FileSaver.contentUri(context, downloaded.file), downloaded.mimeType)
        } catch (e: Exception) {
            Log.e(TAG, "Opening externally failed", e)
            false
        }
        if (!opened) showMessage("No app found to open this file.")
    }

    private fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) }
    }

    /** JavaScript interface for the page: `blob:` download contents and form submissions. */
    inner class PageBridge {
        @JavascriptInterface
        fun onFormSubmit(action: String?, body: String?) {
            if (action.isNullOrBlank() || body == null) return
            lastFormSubmission = FormSubmission(action, body, System.currentTimeMillis())
        }

        @JavascriptInterface
        fun onBlobData(dataUrl: String?, contentDisposition: String?, mimeType: String?) {
            val data = DownloadUtils.parseDataUrl(dataUrl)
            scope.launch {
                if (data == null) {
                    inProgressFileName = null
                    showMessage("This file could not be downloaded.")
                } else {
                    saveInPageFile(
                        data.bytes, contentDisposition?.ifBlank { null }, mimeType?.ifBlank { null } ?: data.mimeType
                    )
                }
            }
        }

        @JavascriptInterface
        fun onBlobError(message: String?) {
            Log.e(TAG, "Blob download failed: $message")
            scope.launch {
                inProgressFileName = null
                showMessage("This file could not be downloaded.")
            }
        }
    }

    companion object {
        const val BRIDGE_NAME = "UUAndroidDownloader"
        private const val TAG = "DownloadController"
    }
}
