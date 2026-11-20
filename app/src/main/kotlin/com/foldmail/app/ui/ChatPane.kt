package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foldmail.app.MailUiState
import com.foldmail.app.formatTimestamp
import com.foldmail.app.title
import com.foldmail.domain.threading.PersonConversation
import com.foldmail.domain.threading.QuoteSplitter

@Composable
fun ChatPane(
    conversation: PersonConversation,
    ui: MailUiState,
    onBack: (() -> Unit)?,
    onOpened: () -> Unit,
    onReply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = conversation.title(ui.names, ui.selfAddress)
    val messages = conversation.messages
    // Header-based thread index per message -> subject dividers where the thread changes.
    val threadOf = remember(conversation) {
        conversation.threads.flatMapIndexed { i, g -> g.messages.map { it.localId to i } }.toMap()
    }
    val listState = rememberLazyListState()

    LaunchedEffect(conversation.key, conversation.unreadCount) { onOpened() }
    LaunchedEffect(conversation.key, messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    Column(modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť") }
            } else {
                Box(Modifier.padding(start = 12.dp))
            }
            Avatar(title, size = 36.dp)
            Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.localId }) { index, m ->
                val prev = messages.getOrNull(index - 1)
                Column(Modifier.fillMaxWidth()) {
                    if (prev == null || threadOf[prev.localId] != threadOf[m.localId]) {
                        Text(
                            m.subject,
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Bubble(
                        body = ui.bodies[m.localId].orEmpty(),
                        outgoing = m.isOutgoing,
                        time = formatTimestamp(m.sentAtMillis),
                    )
                }
            }
        }

        ReplyBar(onSend = onReply)
    }
}

@Composable
private fun Bubble(body: String, outgoing: Boolean, time: String) {
    val split = remember(body) { QuoteSplitter.split(body) }
    var showQuote by rememberSaveable(body) { mutableStateOf(false) }
    val bg = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(split.fresh.trim().ifEmpty { body.trim() })
                if (split.quoted.isNotBlank()) {
                    Text(
                        if (showQuote) "Skryť citáciu" else "… zobraziť citáciu",
                        Modifier.clickable { showQuote = !showQuote }.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (showQuote) Text(split.quoted.trim(), style = MaterialTheme.typography.bodySmall)
                }
                Text(time, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ReplyBar(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Odpovedať…") },
            shape = MaterialTheme.shapes.large,
            maxLines = 5,
        )
        IconButton(
            enabled = text.isNotBlank(),
            onClick = { onSend(text.trim()); text = "" },
        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Odoslať", tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun EmptyDetailPane(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text("Vyberte chat", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}
