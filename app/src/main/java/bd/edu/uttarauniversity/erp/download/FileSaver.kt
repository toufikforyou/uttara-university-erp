package bd.edu.uttarauniversity.erp.download

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves downloaded files to the public Downloads folder and hands them to other apps.
 */
object FileSaver {
    const val FOLDER_NAME = "Uttara University"

    /** Where saved files end up, for messages shown to the user. */
    val displayLocation: String get() = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME"

    /** Android 8 and 9 need the storage permission to write to the public Downloads folder. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED

    /** Copies [downloaded] to `Downloads/Uttara University` and returns a URI other apps can open. */
    suspend fun saveToDownloads(context: Context, downloaded: DownloadedFile): Uri =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(context, downloaded)
            } else {
                saveToLegacyStorage(context, downloaded)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, downloaded: DownloadedFile): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, downloaded.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, downloaded.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, displayLocation)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create the file in Downloads")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                downloaded.file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Unable to write the file to Downloads")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyStorage(context: Context, downloaded: DownloadedFile): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME
        )
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Unable to create the Downloads folder")

        val name = DownloadUtils.uniqueFileName(downloaded.fileName) { File(dir, it).exists() }
        val target = File(dir, name)
        downloaded.file.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(downloaded.mimeType), null)
        return contentUri(context, target)
    }

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Opens the share sheet for [downloaded]. */
    fun share(context: Context, downloaded: DownloadedFile) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = downloaded.mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri(context, downloaded.file))
            putExtra(Intent.EXTRA_SUBJECT, downloaded.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share ${downloaded.fileName}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens [uri] in another app. Returns false when no installed app can open it. */
    fun openWithExternalApp(context: Context, uri: Uri, mimeType: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
