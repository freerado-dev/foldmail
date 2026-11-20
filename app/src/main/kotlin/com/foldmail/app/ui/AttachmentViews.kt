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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
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

/** Attachment as shown inside a message bubble; tap opens it in another app (when it has content). */
@Composable
fun AttachmentView(a: Attachment, modifier: Modifier = Modifier, localId: Long = 0L) {
    val context = LocalContext.current
    val fetcher = LocalAttachmentFetcher.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tap = Modifier.clickable(enabled = a.uri != null || a.remoteId != null) {
        if (a.uri != null) {
            openAttachment(context, a)
        } else {
            android.widget.Toast.makeText(context, "Sťahujem ${a.name}…", android.widget.Toast.LENGTH_SHORT).show()
            scope.launch {
                val r = try { fetcher(localId, a) } catch (_: Exception) { null }
                if (r != null) openAttachment(context, r)
                else android.widget.Toast.makeText(context, "Prílohu sa nepodarilo stiahnuť", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    if (a.isImage && a.uri != null) {
        Thumbnail(a, modifier.then(tap).size(width = 280.dp, height = 200.dp).clip(RoundedCornerShape(16.dp)))
    } else {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth().then(tap)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(a), null, Modifier.size(28.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    val size = formatSize(a.sizeBytes)
                    if (size.isNotEmpty()) Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
    val bitmap by produceState<Bitmap?>(null, a.uri) { value = withContext(Dispatchers.IO) { decodeSampled(context, Uri.parse(a.uri), 640) } }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), a.name, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
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
