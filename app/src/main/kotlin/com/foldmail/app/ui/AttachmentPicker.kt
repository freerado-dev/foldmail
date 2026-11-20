package com.foldmail.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.foldmail.app.Attachment
import java.io.File

/** Entry points for the four ways of adding an attachment. */
class AttachmentActions(
    val pickMedia: () -> Unit,
    val takePhoto: () -> Unit,
    val recordVideo: () -> Unit,
    val pickFile: () -> Unit,
)

@Composable
fun rememberAttachmentActions(onPicked: (List<Attachment>) -> Unit): AttachmentActions {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onPicked)
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMime by rememberSaveable { mutableStateOf("image/jpeg") }

    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) callback(uris.map { describe(context, it, null) })
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) callback(uris.map { describe(context, it, null) })
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingCapture
        if (ok && u != null) callback(listOf(describe(context, Uri.parse(u), pendingMime)))
        pendingCapture = null
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val u = pendingCapture
        if (ok && u != null) callback(listOf(describe(context, Uri.parse(u), pendingMime)))
        pendingCapture = null
    }

    return remember {
        AttachmentActions(
            pickMedia = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            pickFile = { files.launch(arrayOf("*/*")) },
            takePhoto = {
                val uri = newCaptureUri(context, "IMG_", ".jpg")
                pendingCapture = uri.toString(); pendingMime = "image/jpeg"
                photo.launch(uri)
            },
            recordVideo = {
                val uri = newCaptureUri(context, "VID_", ".mp4")
                pendingCapture = uri.toString(); pendingMime = "video/mp4"
                video.launch(uri)
            },
        )
    }
}

private fun newCaptureUri(context: Context, prefix: String, suffix: String): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File.createTempFile(prefix, suffix, dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

/** Reads display name, size and MIME type of a content Uri. */
private fun describe(context: Context, uri: Uri, fallbackMime: String?): Attachment {
    var name: String? = null
    var size: Long? = null
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
    } catch (_: Exception) {
    }
    val mime = context.contentResolver.getType(uri) ?: fallbackMime ?: "application/octet-stream"
    return Attachment(name ?: uri.lastPathSegment ?: "príloha", uri.toString(), mime, size)
}
