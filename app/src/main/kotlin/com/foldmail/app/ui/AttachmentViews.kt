package com.foldmail.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foldmail.app.Attachment
import com.foldmail.app.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads a remote (Gmail) attachment; provided by the app shell. */
val LocalAttachmentFetcher = androidx.compose.runtime.staticCompositionLocalOf<suspend (Long, Attachment) -> Attachment?> { { _, _ -> null } }

/** Paperclip button with the four ways of adding a file. */
@Composable
fun AttachMenuButton(actions: AttachmentActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Pridať prílohu", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Fotka alebo video z galérie") }, leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                onClick = { open = false; actions.pickMedia() })
            DropdownMenuItem(text = { Text("Odfotiť") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                onClick = { open = false; actions.takePhoto() })
            DropdownMenuItem(text = { Text("Natočiť video") }, leadingIcon = { Icon(Icons.Filled.Videocam, null) },
                onClick = { open = false; actions.recordVideo() })
            DropdownMenuItem(text = { Text("Súbor (PDF, dokument…)") }, leadingIcon = { Icon(Icons.Filled.InsertDriveFile, null) },
                onClick = { open = false; actions.pickFile() })
        }
    }
}

/** Row of attachments waiting to be sent, each with a remove button. */
@Composable
fun PendingAttachments(items: List<Attachment>, onRemove: (Attachment) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { a ->
            Box {
                if (a.isImage && a.uri != null) {
                    Thumbnail(a, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp).size(width = 150.dp, height = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconFor(a), null, Modifier.size(26.dp))
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                Text(formatSize(a.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape)
                        .background(Color(0xAA000000)).clickable { onRemove(a) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "Odstrániť", Modifier.size(14.dp), tint = Color.White) }
            }
        }
    }
}

/** Attachment as shown inside a message: thumbnail (images, PDF first page), tap = open, arrow = save to Downloads. */
@Composable
fun AttachmentView(a: Attachment, modifier: Modifier = Modifier, localId: Long = 0L, siblings: List<Attachment> = emptyList()) {
    val gallery = LocalImageGallery.current
    val context = LocalContext.current
    val fetcher = LocalAttachmentFetcher.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var local by remember(a) { mutableStateOf(if (a.uri != null) a else null) }
    // Thumbnails: download small images / PDFs automatically.
    androidx.compose.runtime.LaunchedEffect(a) {
        if (local == null && a.remoteId != null && (a.isImage || a.isPdf) && (a.sizeBytes ?: 0L) < 8_000_000L) {
            local = try { fetcher(localId, a) } catch (_: Exception) { null }
        }
    }
    val shown = local ?: a

    suspend fun ensure(): Attachment? = shown.takeIf { it.uri != null } ?: try { fetcher(localId, a) } catch (_: Exception) { null }?.also { local = it }
    fun toast(t: String) = android.widget.Toast.makeText(context, t, android.widget.Toast.LENGTH_SHORT).show()

    val open: () -> Unit = {
        if (a.isImage && a in siblings) { val imgs = siblings.filter { it.isImage }; gallery(localId, imgs, imgs.indexOf(a)) }
        else scope.launch {
            if (shown.uri == null) toast("Sťahujem ${a.name}…")
            val r = ensure()
            if (r != null) openAttachment(context, r) else toast("Prílohu sa nepodarilo stiahnuť")
        }
    }
    val save: () -> Unit = {
        scope.launch {
            val r = ensure()
            if (r != null && saveToDownloads(context, r)) toast("Uložené do Stiahnuté: ${a.name}") else toast("Uloženie zlyhalo")
        }
    }
    val tap = Modifier.clickable(enabled = shown.uri != null || a.remoteId != null, onClick = open)
    val hasThumb = shown.uri != null && (shown.isImage || shown.isPdf)

    // Images and PDFs keep the same size before and after the thumbnail loads, so the chat does not jump.
    val bigBox = hasThumb || ((a.isImage || a.isPdf) && (local == null && a.remoteId != null && (a.sizeBytes ?: 0L) < 8_000_000L))
    Box(modifier) {
        if (!hasThumb && bigBox) {
            Box(
                Modifier.then(tap).size(width = 280.dp, height = 200.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconFor(a), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (hasThumb) {
            Thumbnail(shown, Modifier.then(tap).size(width = 280.dp, height = 200.dp).clip(RoundedCornerShape(16.dp)))
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp).clip(CircleShape)
                    .background(Color(0x99000000)).clickable(onClick = save),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Download, "Stiahnuť", Modifier.size(18.dp), tint = Color.White) }
        } else {
            Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().then(tap)) {
                Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(a), null, Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        val size = formatSize(shown.sizeBytes ?: a.sizeBytes)
                        if (size.isNotEmpty()) Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (shown.uri != null || a.remoteId != null) {
                        IconButton(onClick = save) { Icon(Icons.Filled.Download, "Stiahnuť do zariadenia") }
                    }
                }
            }
        }
    }
}

/** Small square attachment tile (thumbnail or file icon) for the one-line strip under a message preview. */
@Composable
fun AttachmentMini(a: Attachment, localId: Long, modifier: Modifier = Modifier, siblings: List<Attachment> = emptyList()) {
    val gallery = LocalImageGallery.current
    val context = LocalContext.current
    val fetcher = LocalAttachmentFetcher.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var local by remember(a) { mutableStateOf(if (a.uri != null) a else null) }
    androidx.compose.runtime.LaunchedEffect(a) {
        if (local == null && a.remoteId != null && (a.isImage || a.isPdf) && (a.sizeBytes ?: 0L) < 8_000_000L) {
            local = try { fetcher(localId, a) } catch (_: Exception) { null }
        }
    }
    val shown = local ?: a
    val hasThumb = shown.uri != null && (shown.isImage || shown.isPdf)
    Box(
        modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.background).clickable {
            if (a.isImage && a in siblings) { val imgs = siblings.filter { it.isImage }; gallery(localId, imgs, imgs.indexOf(a)); return@clickable }
            scope.launch {
                val r = shown.takeIf { it.uri != null } ?: try { fetcher(localId, a) } catch (_: Exception) { null }
                if (r != null) { local = r; openAttachment(context, r) }
                else android.widget.Toast.makeText(context, "Prílohu sa nepodarilo stiahnuť", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (hasThumb) Thumbnail(shown, Modifier.fillMaxSize())
        else Icon(iconFor(a), contentDescription = a.name, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Attachment tile that keeps the original aspect ratio (no cropping); height fixed, width follows the picture. */
@Composable
fun AttachmentTile(a: Attachment, localId: Long, height: androidx.compose.ui.unit.Dp = 90.dp, siblings: List<Attachment> = emptyList()) {
    val gallery = LocalImageGallery.current
    val context = LocalContext.current
    val fetcher = LocalAttachmentFetcher.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var local by remember(a) { mutableStateOf(if (a.uri != null) a else null) }
    androidx.compose.runtime.LaunchedEffect(a) {
        if (local == null && a.remoteId != null && (a.isImage || a.isPdf) && (a.sizeBytes ?: 0L) < 8_000_000L) {
            local = try { fetcher(localId, a) } catch (_: Exception) { null }
        }
    }
    val shown = local ?: a
    val bitmap by produceState<Bitmap?>(null, shown.uri) {
        val u = shown.uri
        value = if (u == null || !(shown.isImage || shown.isPdf)) null else withContext(Dispatchers.IO) {
            if (shown.isPdf) renderPdf(context, Uri.parse(u), 320) else decodeSampled(context, Uri.parse(u), 320)
        }
    }
    val open = {
        if (a.isImage && a in siblings) { val imgs = siblings.filter { it.isImage }; gallery(localId, imgs, imgs.indexOf(a)) }
        else scope.launch {
            val r = shown.takeIf { it.uri != null } ?: try { fetcher(localId, a) } catch (_: Exception) { null }
            if (r != null) { local = r; openAttachment(context, r) }
            else android.widget.Toast.makeText(context, "Prílohu sa nepodarilo stiahnuť", android.widget.Toast.LENGTH_SHORT).show()
        }
        Unit
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), a.name, Modifier.height(height).clip(RoundedCornerShape(6.dp)).clickable { open() }, contentScale = ContentScale.Fit)
    } else {
        Row(
            Modifier.height(height.coerceAtMost(56.dp)).widthIn(max = 150.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).clickable { open() }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(iconFor(a), contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Compact attachment line with a small thumbnail, used in search results. */
@Composable
fun AttachmentRow(a: Attachment, localId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fetcher = LocalAttachmentFetcher.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var local by remember(a) { mutableStateOf(if (a.uri != null) a else null) }
    androidx.compose.runtime.LaunchedEffect(a) {
        if (local == null && a.remoteId != null && (a.isImage || a.isPdf) && (a.sizeBytes ?: 0L) < 8_000_000L) {
            local = try { fetcher(localId, a) } catch (_: Exception) { null }
        }
    }
    val shown = local ?: a
    val hasThumb = shown.uri != null && (shown.isImage || shown.isPdf)
    Row(
        modifier.fillMaxWidth().clickable {
            scope.launch {
                val r = shown.takeIf { it.uri != null } ?: try { fetcher(localId, a) } catch (_: Exception) { null }
                if (r != null) { local = r; openAttachment(context, r) }
                else android.widget.Toast.makeText(context, "Prílohu sa nepodarilo stiahnuť", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (hasThumb) Thumbnail(shown, Modifier.fillMaxSize()) else Icon(iconFor(a), null, Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            val kind = when { a.isImage -> "Obrázok"; a.isPdf -> "PDF"; a.isVideo -> "Video"; else -> "Súbor" }
            val size = formatSize(shown.sizeBytes ?: a.sizeBytes)
            Text(if (size.isEmpty()) kind else "$kind · $size", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Copies the file into the public Downloads folder. */
private suspend fun saveToDownloads(context: Context, a: Attachment): Boolean = withContext(Dispatchers.IO) {
    try {
        val src = context.contentResolver.openInputStream(Uri.parse(a.uri ?: return@withContext false)) ?: return@withContext false
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, a.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, a.mimeType)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        src.use { input -> context.contentResolver.openOutputStream(target)?.use { out -> input.copyTo(out) } }
        context.contentResolver.update(target, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        true
    } catch (_: Exception) {
        false
    }
}

private fun iconFor(a: Attachment) = when {
    a.isPdf -> Icons.Filled.PictureAsPdf
    a.isVideo -> Icons.Filled.Videocam
    else -> Icons.Filled.InsertDriveFile
}

@Composable
private fun Thumbnail(a: Attachment, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, a.uri) { value = withContext(Dispatchers.IO) { if (a.isPdf) renderPdf(context, Uri.parse(a.uri), 560) else decodeSampled(context, Uri.parse(a.uri), 640) } }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), a.name, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private fun renderPdf(context: Context, uri: Uri, widthPx: Int): Bitmap? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
        android.graphics.pdf.PdfRenderer(fd).use { r ->
            r.openPage(0).use { page ->
                val h = (widthPx.toFloat() / page.width * page.height).toInt()
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }
} catch (_: Exception) {
    null
}

private fun decodeSampled(context: Context, uri: Uri, maxPx: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
} catch (_: Exception) {
    null
}

private fun openAttachment(context: Context, a: Attachment) {
    val uri = a.uri ?: return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), a.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}

/** Opens the gallery: attachment owner, the images and the index to start at. */
val LocalImageGallery = androidx.compose.runtime.staticCompositionLocalOf<(Long, List<Attachment>, Int) -> Unit> { { _, _, _ -> } }

class GalleryRequest(val localId: Long, val images: List<Attachment>, val start: Int)

/** Full-screen popup with all images of a message: swipe between them, pinch to zoom, save to Downloads. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ImageGallery(req: GalleryRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val fetcher = LocalAttachmentFetcher.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = req.start.coerceIn(0, req.images.lastIndex)) { req.images.size }
    val loaded = remember { androidx.compose.runtime.mutableStateMapOf<Int, Attachment>() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val a = req.images[page]
                androidx.compose.runtime.LaunchedEffect(page) {
                    if (a.uri != null) loaded[page] = a
                    else try { fetcher(req.localId, a)?.let { loaded[page] = it } } catch (_: Exception) { }
                }
                ZoomableImage(loaded[page])
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).background(Color(0x66000000)).statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Zavrieť", tint = Color.White) }
                Text(
                    req.images[pager.currentPage].name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("${pager.currentPage + 1} / ${req.images.size}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = {
                    scope.launch {
                        val r = loaded[pager.currentPage]
                        val ok = r != null && saveToDownloads(context, r)
                        android.widget.Toast.makeText(context, if (ok) "Uložené do Stiahnuté: ${r!!.name}" else "Uloženie zlyhalo", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Filled.Download, "Stiahnuť", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun ZoomableImage(a: Attachment?) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, a?.uri) {
        value = a?.uri?.let { u -> withContext(Dispatchers.IO) { decodeSampled(context, Uri.parse(u), 2048) } }
    }
    val bmp = bitmap
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent()
                    if (ev.changes.size > 1 || scale > 1f) {
                        scale = (scale * ev.calculateZoom()).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + ev.calculatePan() else androidx.compose.ui.geometry.Offset.Zero
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp == null) androidx.compose.material3.CircularProgressIndicator(color = Color.White)
        else Image(
            bmp.asImageBitmap(), a?.name, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
            contentScale = ContentScale.Fit,
        )
    }
}
