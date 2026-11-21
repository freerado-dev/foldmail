package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foldmail.app.Attachment
import com.foldmail.app.nameOf

private fun splitAddresses(s: String) = s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/** One recipient line (To / Cc / Bcc) with suggestions from the mailbox contacts. */
@Composable
private fun RecipientField(
    label: String, value: String, onChange: (String) -> Unit,
    contacts: List<String>, names: Map<String, String>,
    trailing: @Composable (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val typed = value.substringAfterLast(',').substringAfterLast(';').trim().lowercase()
    val already = splitAddresses(value).map { it.lowercase() }.toSet()
    val suggestions = remember(typed, contacts, names, value, focused) {
        if (!focused) emptyList()
        else contacts.asSequence()
            .filter { it !in already }
            .filter { typed.isEmpty() || typed in it || typed in nameOf(it, names).lowercase() }
            .take(if (typed.isEmpty()) 6 else 5).toList()
    }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        label = { Text(label) }, singleLine = true, trailingIcon = trailing,
    )
    if (suggestions.isNotEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            suggestions.forEach { address ->
                val name = nameOf(address, names)
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val cut = maxOf(value.lastIndexOf(','), value.lastIndexOf(';'))
                        onChange((if (cut >= 0) value.substring(0, cut + 1) + " " else "") + address + ", ")
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, size = 32.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** New-email form with Cc/Bcc and attachments. Recipients are comma/semicolon separated. */
@Composable
fun ComposePane(
    initialTo: String,
    initialSubject: String = "",
    initialBody: String = "",
    initialCc: String = "",
    initialAttachments: List<Attachment> = emptyList(),
    contacts: List<String> = emptyList(),
    names: Map<String, String> = emptyMap(),
    onClose: () -> Unit,
    onSend: (to: List<String>, subject: String, body: String, attachments: List<Attachment>, cc: List<String>, bcc: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var to by rememberSaveable(initialTo) { mutableStateOf(initialTo) }
    var cc by rememberSaveable { mutableStateOf(initialCc) }
    var bcc by rememberSaveable { mutableStateOf("") }
    var showCc by rememberSaveable { mutableStateOf(initialCc.isNotBlank()) }
    var subject by rememberSaveable { mutableStateOf(initialSubject) }
    var body by rememberSaveable { mutableStateOf(initialBody) }
    val pending = remember { mutableStateListOf<Attachment>().apply { addAll(initialAttachments) } }
    val actions = rememberAttachmentActions { pending.addAll(it) }

    val recipients = splitAddresses(to)
    val ccList = splitAddresses(cc)
    val bccList = splitAddresses(bcc)
    val canSend = recipients.isNotEmpty() && (recipients + ccList + bccList).all { '@' in it } &&
        (body.isNotBlank() || pending.isNotEmpty())

    Column(modifier.background(MaterialTheme.colorScheme.background).safeTop().imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Zavrieť") }
            Text("Nový email", Modifier.padding(start = 4.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
            AttachMenuButton(actions)
            IconButton(enabled = canSend, onClick = { onSend(recipients, subject.trim(), body.trim(), pending.toList(), ccList, bccList) }) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = "Odoslať",
                    tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RecipientField("Komu", to, { to = it }, contacts, names, trailing = {
                IconButton(onClick = { showCc = !showCc }) {
                    Icon(if (showCc) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = "Kópia a skrytá kópia")
                }
            })
            if (showCc) {
                RecipientField("Kópia", cc, { cc = it }, contacts, names)
                RecipientField("Skrytá kópia", bcc, { bcc = it }, contacts, names)
            }
            OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("Predmet") }, singleLine = true)
            PendingAttachments(pending, onRemove = { pending.remove(it) }, modifier = Modifier.padding(horizontal = 0.dp))
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("Správa") })
        }
    }
}
