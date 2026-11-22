package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** Rounded grey box without outline, like the message boxes in a chat. */
@Composable
private fun BoxField(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String, singleLine: Boolean = false, trailing: @Composable (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    androidx.compose.material3.TextField(
        value, onChange, modifier,
        placeholder = { Text(placeholder, color = c.onSurfaceVariant) },
        singleLine = singleLine, trailingIcon = trailing,
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceVariant, unfocusedContainerColor = c.surfaceVariant,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        ),
    )
}

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
    BoxField(
        value, onChange, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        placeholder = label, singleLine = true, trailing = trailing,
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
    accounts: List<com.foldmail.app.Account> = emptyList(),
    initialAccountId: Long = -1L,
    onClose: () -> Unit,
    onSend: (to: List<String>, subject: String, body: String, attachments: List<Attachment>, cc: List<String>, bcc: List<String>, accountId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var to by rememberSaveable(initialTo) { mutableStateOf(initialTo) }
    var cc by rememberSaveable { mutableStateOf(initialCc) }
    var bcc by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf(initialAccountId) }
    var pickAccount by remember { mutableStateOf(false) }
    val from = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Zavrieť") }
            Text("Nový email", Modifier.padding(start = 4.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
            AttachMenuButton(actions)
            IconButton(enabled = canSend, onClick = { onSend(recipients, subject.trim(), body.trim(), pending.toList(), ccList, bccList, from?.id ?: accountId) }) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = "Odoslať",
                    tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (from != null) {
                Box {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = accounts.size > 1) { pickAccount = true }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Z", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                        Box(Modifier.size(width = 4.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(Color(from.colorArgb)))
                        Text(from.email, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, fontWeight = FontWeight.Medium)
                        if (accounts.size > 1) Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Vybrať účet")
                    }
                    DropdownMenu(expanded = pickAccount, onDismissRequest = { pickAccount = false }) {
                        accounts.forEach { a ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(width = 4.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(Color(a.colorArgb)))
                                        Column(Modifier.padding(start = 10.dp)) {
                                            Text(a.email, fontWeight = if (a.id == from.id) FontWeight.SemiBold else FontWeight.Normal)
                                            if (a.name.isNotBlank() && a.name != a.email.substringBefore('@')) Text(a.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = { accountId = a.id; pickAccount = false },
                            )
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            RecipientField("Komu", to, { to = it }, contacts, names, trailing = {
                IconButton(onClick = { showCc = !showCc }) {
                    Icon(if (showCc) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = "Kópia a skrytá kópia")
                }
            })
            if (showCc) {
                RecipientField("Kópia", cc, { cc = it }, contacts, names)
                RecipientField("Skrytá kópia", bcc, { bcc = it }, contacts, names)
            }
            BoxField(subject, { subject = it }, Modifier.fillMaxWidth(), placeholder = "Predmet", singleLine = true)
            PendingAttachments(pending, onRemove = { pending.remove(it) }, modifier = Modifier.padding(horizontal = 0.dp))
            BoxField(body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 220.dp), placeholder = "Správa")
            Spacer(Modifier.height(12.dp))
        }
    }
}
