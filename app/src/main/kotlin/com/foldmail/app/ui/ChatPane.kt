package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    val listState = rememberLazyListState()
    // Measured heights of rows already seen: when a row scrolls back into view it gets its old height
    // at once, so older messages no longer make the list jump while their content loads.
    val heights = remember(conversation.key) { HashMap<Long, Int>() }
    val density = androidx.compose.ui.platform.LocalDensity.current

    LaunchedEffect(conversation.key, conversation.unreadCount) { onOpened() }
    LaunchedEffect(conversation.key, messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    Column(modifier.background(MaterialTheme.colorScheme.background).safeTop().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť") }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    conversation.participants.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.localId }) { index, m ->
                val prev = messages.getOrNull(index - 1)
                val known = heights[m.localId]
                val reserve = when {
                    known != null -> with(density) { known.toDp() }
                    m.localId in ui.richIds -> 260.dp
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
                        subject = if (startsThread) m.subject.removePrefix("Re: ").removePrefix("RE: ") else null,
                        body = ui.bodies[m.localId].orEmpty(),
                        attachments = ui.attachments[m.localId].orEmpty(),
                        senderName = nameOf(normalizeAddress(m.from), ui.names),
                        selfInitial = ui.selfAddress.take(1).uppercase().ifEmpty { "J" },
                        rich = m.localId in ui.richIds,
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

@Composable
private fun MessageRow(
    m: EmailMessage,
    subject: String?,
    body: String,
    attachments: List<Attachment>,
    senderName: String,
    selfInitial: String,
    rich: Boolean,
    onOpenFull: () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val outgoing = m.isOutgoing
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!outgoing) {
            Avatar(senderName, size = 24.dp)
            Spacer(Modifier.width(6.dp))
        }
        if (rich) {
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!subject.isNullOrBlank()) Text(subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                RichHtmlCard(m, "", body, selected, onOpen = onOpenFull, onSelect = onClick)
                attachments.forEach { AttachmentView(it, Modifier.padding(top = 4.dp), m.localId) }
            }
        } else {
            Bubble(m.localId, subject, body, attachments, selected, onOpenFull, onClick, Modifier.weight(1f, fill = false))
        }
        if (outgoing) {
            Spacer(Modifier.width(6.dp))
            Avatar(selfInitial, size = 24.dp)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(localId: Long, subject: String?, body: String, attachments: List<Attachment>, selected: Boolean, onOpen: () -> Unit, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val split = remember(body) { QuoteSplitter.split(body) }
    var showQuote by rememberSaveable(body) { mutableStateOf(false) }

    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onOpen, onLongClick = onSelect),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            if (!subject.isNullOrBlank()) {
                Text(subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(2.dp))
            }
            val text = split.fresh.trim().ifEmpty { body.trim() }
            if (text.isNotEmpty()) Text(text)
            attachments.forEach { AttachmentView(it, Modifier.padding(top = 8.dp), localId) }
            if (split.quoted.isNotBlank()) {
                Text(
                    if (showQuote) "Skryť pôvodný email" else "… pôvodný email",
                    Modifier.clickable { showQuote = !showQuote }.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (showQuote) Text(split.quoted.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AttachMenuButton(actions)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                placeholder = { Text((if (replying) "Odpovedať: " else "Napísať: ") + title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = MaterialTheme.shapes.large,
                maxLines = 5,
            )
            FilledIconButton(
                enabled = canSend,
                onClick = {
                    onSend(text.trim(), pending.toList(), if (replying) null else newSubject.trim())
                    text = ""; pending.clear()
                    if (!replying) { newSubject = ""; replying = true }
                },
                modifier = Modifier.padding(end = 8.dp),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Odoslať") }
        }
    }
}

@Composable
fun EmptyDetailPane(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text("Vyberte chat", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}
