package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foldmail.app.Attachment
import com.foldmail.app.MailUiState
import com.foldmail.app.formatChatStamp
import com.foldmail.app.nameOf
import com.foldmail.app.title
import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.model.normalizeAddress
import com.foldmail.domain.threading.PersonConversation
import com.foldmail.domain.threading.QuoteSplitter

private const val STAMP_GAP_MS = 60 * 60 * 1000L

@Composable
fun ChatPane(
    state: ChatScrollState,
    conversation: PersonConversation,
    ui: MailUiState,
    onBack: (() -> Unit)?,
    onOpened: () -> Unit,
    onReply: (String, List<Attachment>, String?) -> Unit,
    onAction: (MessageAction, EmailMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedId by rememberSaveable(conversation.key) { mutableStateOf<Long?>(null) }
    val title = conversation.title(ui.names, ui.selfAddress)
    val messages = conversation.messages
    // Header-based thread index per message -> the subject heading is shown where a thread starts.
    val threadOf = remember(conversation) {
        conversation.threads.flatMapIndexed { i, g -> g.messages.map { it.localId to i } }.toMap()
    }
    val listState = state.listStates.getOrPut(conversation.key) { androidx.compose.foundation.lazy.LazyListState() }
    // Measured heights of rows already seen: when a row scrolls back into view it gets its old height
    // at once, so older messages no longer make the list jump while their content loads.
    val heights = state.heights.getOrPut(conversation.key) { HashMap<Long, Int>() }
    val density = androidx.compose.ui.platform.LocalDensity.current

    LaunchedEffect(conversation.key, conversation.unreadCount) { onOpened() }
    LaunchedEffect(conversation.key, messages.size) {
        // Jump to the newest message only when the chat is first opened or gets new mail; coming back from a full email keeps the position.
        if (messages.isNotEmpty() && state.seenCounts[conversation.key] != messages.size) {
            state.seenCounts[conversation.key] = messages.size
            listState.scrollToItem(messages.size - 1)
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.background).safeTop(minimum = 4, cutout = false).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 0.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť") }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val lastMsg = conversation.last
                Text(
                    com.foldmail.app.fromLine(lastMsg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    com.foldmail.app.toLine(lastMsg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.localId }) { index, m ->
                val prev = messages.getOrNull(index - 1)
                val known = heights[m.localId]
                val reserve = when {
                    known != null -> with(density) { known.toDp() }
                    else -> 0.dp
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(min = reserve)
                        .onSizeChanged { if (it.height > 0) heights[m.localId] = it.height },
                ) {
                    if (prev == null || m.sentAtMillis - prev.sentAtMillis > STAMP_GAP_MS) {
                        Text(
                            formatChatStamp(m.sentAtMillis),
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val startsThread = prev == null || threadOf[prev.localId] != threadOf[m.localId]
                    MessageRow(
                        m = m,
                        subject = null,
                        body = ui.bodies[m.localId].orEmpty(),
                        attachments = ui.attachments[m.localId].orEmpty(),
                        senderName = nameOf(normalizeAddress(m.from), ui.names),
                        selfInitial = ui.selfAddress.take(1).uppercase().ifEmpty { "J" },
                        onOpenFull = { onAction(MessageAction.ShowFull, m) },
                        selected = selectedId == m.localId,
                        onClick = { selectedId = if (selectedId == m.localId) null else m.localId },
                    )
                }
            }
        }

        MessageActionBar(onAction = { a ->
            val target = messages.firstOrNull { it.localId == selectedId } ?: messages.lastOrNull()
            if (target != null) onAction(a, target)
            if (a == MessageAction.Delete) selectedId = null
        })
        ReplyBar(conversation.key, title, conversation.last.subject, onSend = onReply)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    m: EmailMessage,
    subject: String?,
    body: String,
    attachments: List<Attachment>,
    senderName: String,
    selfInitial: String,
    onOpenFull: () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val outgoing = m.isOutgoing
    val preview = remember(body) { QuoteSplitter.split(body).fresh.trim().ifEmpty { body.trim() } }
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.TopEnd else Alignment.TopStart) {
    Row(
        Modifier.widthIn(max = maxWidth * 0.84f),
        verticalAlignment = Alignment.Top,
    ) {
        if (!outgoing) {
            Avatar(senderName, size = 24.dp, address = normalizeAddress(m.from))
            Spacer(Modifier.width(6.dp))
        }
        // One box per email: subject and time, a 4-line preview, a strip of attachment thumbnails. Tap = full email.
        Surface(
            color = when {
                selected -> MaterialTheme.colorScheme.primaryContainer
                outgoing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.background)
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier.weight(1f, fill = false).clip(shape)
                .combinedClickable(onClick = onOpenFull, onLongClick = onClick),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.subject.ifBlank { "(bez predmetu)" },
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        formatChatStamp(m.sentAtMillis).substringAfterLast(", "),
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.isNotEmpty()) {
                    Text(preview, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                if (attachments.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        attachments.forEach { AttachmentMini(it, m.localId, siblings = attachments) }
                    }
                }
            }
        }
        if (outgoing) {
            Spacer(Modifier.width(6.dp))
            Avatar(selfInitial, size = 24.dp, address = normalizeAddress(m.from))
        }
    }
    }
}

@Composable
private fun ReplyBar(key: String, title: String, replySubject: String, onSend: (String, List<Attachment>, String?) -> Unit) {
    var text by rememberSaveable(key) { mutableStateOf("") }
    var replying by rememberSaveable(key) { mutableStateOf(true) }
    var newSubject by rememberSaveable(key) { mutableStateOf("") }
    val pending = remember(key) { mutableStateListOf<Attachment>() }
    val actions = rememberAttachmentActions { pending.addAll(it) }
    val canSend = text.isNotBlank() || pending.isNotEmpty()

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        // Subject strip: shows what we reply to; the cross turns it into a new email with its own subject.
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp, top = 0.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (replying) {
                Text(replySubject, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { replying = false }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Zrušiť odpoveď", Modifier.size(18.dp))
                }
            } else {
                androidx.compose.foundation.text.BasicTextField(
                    value = newSubject, onValueChange = { newSubject = it },
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (newSubject.isEmpty()) Text("Bez predmetu – napíš predmet", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    },
                )
                IconButton(onClick = { replying = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Vrátiť odpoveď", Modifier.size(18.dp))
                }
            }
        }
        PendingAttachments(pending, onRemove = { pending.remove(it) })
        Row(Modifier.fillMaxWidth().padding(start = 0.dp, end = 8.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AttachMenuButton(actions)
            Box(
                Modifier.weight(1f).padding(horizontal = 2.dp).heightIn(min = 38.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(19.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(
                            (if (replying) "Odpovedať: " else "Napísať: ") + title,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        inner()
                    },
                )
            }
            FilledIconButton(
                enabled = canSend,
                onClick = {
                    onSend(text.trim(), pending.toList(), if (replying) null else newSubject.trim())
                    text = ""; pending.clear()
                    if (!replying) { newSubject = ""; replying = true }
                },
                modifier = Modifier.padding(start = 6.dp).size(38.dp),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Odoslať", Modifier.size(20.dp)) }
        }
    }
}

@Composable
fun EmptyDetailPane(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text("Vyberte chat", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

/** Scroll positions and measured row heights of the chats, kept while a chat is replaced by a full email. */
class ChatScrollState {
    val listStates = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    val heights = HashMap<String, HashMap<Long, Int>>()
    val seenCounts = HashMap<String, Int>()
}
