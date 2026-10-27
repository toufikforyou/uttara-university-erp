package bd.edu.uttarauniversity.erp.download

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

private const val MAX_ZOOM = 5f
private const val MAX_PAGE_BITMAP_WIDTH = 2048

/**
 * Wraps [PdfRenderer], which may only render one page at a time, behind a mutex.
 */
private class PdfDocument(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }
    private val mutex = Mutex()
    private var closed = false

    /** Width / height of every page. */
    val pageAspectRatios: List<Float> = (0 until renderer.pageCount).map { index ->
        renderer.openPage(index).use { page -> page.width.toFloat() / page.height.coerceAtLeast(1) }
    }

    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val width = widthPx.coerceIn(1, MAX_PAGE_BITMAP_WIDTH)
                val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    override fun close() {
        // Wait for a render in progress so the renderer is never closed underneath it.
        while (!mutex.tryLock()) Thread.yield()
        try {
            closed = true
            renderer.close()
            descriptor.close()
        } finally {
            mutex.unlock()
        }
    }
}

private sealed class PdfLoadState {
    object Loading : PdfLoadState()
    class Ready(val document: PdfDocument) : PdfLoadState()
    class Failed(val message: String) : PdfLoadState()
}

/**
 * Full screen, in-app viewer for a downloaded PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdf: DownloadedFile,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onClose)

    val loadState by produceState<PdfLoadState>(PdfLoadState.Loading, pdf.file) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfLoadState.Ready(PdfDocument(pdf.file))
            } catch (e: Exception) {
                PdfLoadState.Failed(
                    if (e is SecurityException) "This PDF is password protected." else "This PDF could not be opened."
                )
            }
        }
    }

    DisposableEffect(loadState) {
        val document = (loadState as? PdfLoadState.Ready)?.document
        onDispose { document?.close() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(text = pdf.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close PDF")
                    }
                },
                actions = {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Default.Download, contentDescription = "Save PDF to Downloads")
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Share PDF")
                    }
                    IconButton(onClick = onOpenExternally) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open PDF in another app")
                    }
                }
            )

            when (val state = loadState) {
                is PdfLoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Opening PDF" }
                    )
                }

                is PdfLoadState.Failed -> PdfErrorContent(state.message, onOpenExternally)
                is PdfLoadState.Ready -> PdfPages(state.document)
            }
        }
    }
}

@Composable
private fun PdfPages(document: PdfDocument) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
            .pointerInput(Unit) {
                // Pinch to zoom with two fingers; single finger scrolling is left to the list.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            val newOffset = offset + event.calculatePan()
                            offset = Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val pageWidthPx = with(LocalDensity.current) { (maxWidth - 16.dp).roundToPx() }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(document.pageAspectRatios.size) { index ->
                PdfPage(
                    document = document,
                    index = index,
                    pageCount = document.pageAspectRatios.size,
                    aspectRatio = document.pageAspectRatios[index],
                    // Render sharper than the screen so zooming in stays readable.
                    widthPx = pageWidthPx * 2
                )
            }
        }
    }
}

@Composable
private fun PdfPage(document: PdfDocument, index: Int, pageCount: Int, aspectRatio: Float, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(null, document, index, widthPx) {
        value = runCatching { document.renderPage(index, widthPx) }.getOrNull()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(0.1f, 10f)),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        val image = bitmap
        if (image == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Page ${index + 1} of $pageCount",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun PdfErrorContent(message: String, onOpenExternally: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onOpenExternally) {
                Text("Open in another app")
            }
        }
    }
}
