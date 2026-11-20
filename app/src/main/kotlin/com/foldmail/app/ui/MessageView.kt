package com.foldmail.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.foldmail.app.MailUiState
import com.foldmail.app.formatChatStamp
import com.foldmail.app.nameOf
import com.foldmail.app.wrapHtml
import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.model.normalizeAddress

/** Resolves cid: images of a message's HTML; provided by the app shell. */
val LocalHtmlResolver = androidx.compose.runtime.staticCompositionLocalOf<suspend (Long) -> String?> { { null } }

/** A rich mail rendered inline as the sender made it. Tap opens the full viewer, long press selects. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RichHtmlCard(
    message: EmailMessage, rawHtml: String, plain: String, selected: Boolean,
    onOpen: () -> Unit, onSelect: () -> Unit, modifier: Modifier = Modifier,
) {
    val resolver = LocalHtmlResolver.current
    val html by androidx.compose.runtime.produceState(rawHtml, message.localId) {
        value = try { resolver(message.localId) } catch (_: Exception) { null } ?: rawHtml
    }
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color.White,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.widthIn(max = 640.dp),
    ) {
        androidx.compose.foundation.layout.Box {
            HtmlView(html, plain, Modifier.fillMaxWidth(), inline = true)
            // Transparent layer on top: the WebView never steals scroll gestures from the chat list.
            androidx.compose.foundation.layout.Box(
                Modifier.matchParentSize().combinedClickable(onClick = onOpen, onLongClick = onSelect),
            )
        }
    }
}

enum class MessageAction { Reply, ReplyAll, Forward, Delete, ShowFull }

/**
 * Top padding that is never smaller than a comfortable minimum, so the content does not
 * start right under the camera/status bar when two apps share the screen.
 */
@Composable
fun Modifier.safeTop(minimum: Int = 28): Modifier {
    val insetTop = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(LocalDensity.current)
    val missing = with(LocalDensity.current) { (minimum.dp.roundToPx() - insetTop).coerceAtLeast(0).toDp() }
    return this.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).padding(top = missing)
}

/** Samsung-style action row: Reply, Reply all, Forward, Delete, More. */
@Composable
fun MessageActionBar(onAction: (MessageAction) -> Unit, modifier: Modifier = Modifier, showFullEntry: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionItem(Icons.AutoMirrored.Filled.Reply, "Odpovedať") { onAction(MessageAction.Reply) }
            ActionItem(Icons.AutoMirrored.Filled.ReplyAll, "Odpov. všetkým") { onAction(MessageAction.ReplyAll) }
            ActionItem(Icons.AutoMirrored.Filled.Forward, "Preposlať") { onAction(MessageAction.Forward) }
            ActionItem(Icons.Filled.Delete, "Odstrániť") { onAction(MessageAction.Delete) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ActionItem(Icons.Filled.MoreVert, "Ďalšie") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (showFullEntry) {
                        DropdownMenuItem(text = { Text("Zobraziť celý email") }, onClick = { menu = false; onAction(MessageAction.ShowFull) })
                    }
                    DropdownMenuItem(text = { Text("Odpovedať") }, onClick = { menu = false; onAction(MessageAction.Reply) })
                }
            }
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Renders the email as HTML (like a mail client) – falls back to the plain text. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(html: String?, plain: String, modifier: Modifier = Modifier, inline: Boolean = false) {
    val page = remember(html, plain) { wrapHtml(html, plain) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = !inline
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.loadsImagesAutomatically = true
                if (inline) { isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false; overScrollMode = android.view.View.OVER_SCROLL_NEVER }
                settings.displayZoomControls = false
                settings.allowFileAccess = false
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
            }
        },
        update = { web ->
            if (web.tag != page) {
                web.tag = page
                web.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
    )
}

/** Full-screen message viewer: header, complete HTML body, action bar. */
@Composable
fun MessageViewPane(
    message: EmailMessage,
    ui: MailUiState,
    onBack: () -> Unit,
    onAction: (MessageAction) -> Unit,
    resolveHtml: suspend (Long) -> String?,
    modifier: Modifier = Modifier,
) {
    val html by androidx.compose.runtime.produceState<String?>(ui.htmls[message.localId], message.localId) {
        value = try { resolveHtml(message.localId) } catch (_: Exception) { null } ?: ui.htmls[message.localId]
    }
    Column(modifier.background(MaterialTheme.colorScheme.background).safeTop()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť") }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    message.subject.ifBlank { "(bez predmetu)" },
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${nameOf(normalizeAddress(message.from), ui.names)} · ${formatChatStamp(message.sentAtMillis)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HtmlView(html, ui.bodies[message.localId].orEmpty(), Modifier.weight(1f).fillMaxWidth())
        MessageActionBar(onAction, Modifier.navigationBarsPadding(), showFullEntry = false)
    }
}
