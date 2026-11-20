package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foldmail.app.Attachment

/** New-email form with attachments. Recipients are comma/semicolon separated. */
@Composable
fun ComposePane(
    initialTo: String,
    initialSubject: String = "",
    initialBody: String = "",
    onClose: () -> Unit,
    onSend: (to: List<String>, subject: String, body: String, attachments: List<Attachment>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var to by rememberSaveable(initialTo) { mutableStateOf(initialTo) }
    var subject by rememberSaveable { mutableStateOf(initialSubject) }
    var body by rememberSaveable { mutableStateOf(initialBody) }
    val pending = remember { mutableStateListOf<Attachment>() }
    val actions = rememberAttachmentActions { pending.addAll(it) }

    val recipients = to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    val canSend = recipients.isNotEmpty() && recipients.all { '@' in it } && (body.isNotBlank() || pending.isNotEmpty())

    Column(modifier.background(MaterialTheme.colorScheme.background).safeTop().imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Zavrieť") }
            Text("Nový email", Modifier.padding(start = 4.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
            AttachMenuButton(actions)
            IconButton(enabled = canSend, onClick = { onSend(recipients, subject.trim(), body.trim(), pending.toList()) }) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = "Odoslať",
                    tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text("Komu") }, singleLine = true)
            OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("Predmet") }, singleLine = true)
            PendingAttachments(pending, onRemove = { pending.remove(it) }, modifier = Modifier.padding(horizontal = 0.dp))
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("Správa") })
        }
    }
}
