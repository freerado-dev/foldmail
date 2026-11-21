package com.foldmail.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.foldmail.app.ImapConfig
import kotlinx.coroutines.launch

private data class Preset(val imap: String, val smtp: String, val smtpPort: Int, val smtpSecurity: String)

private fun presetFor(email: String): Preset {
    val d = email.substringAfter('@', "").lowercase()
    return when {
        d == "gmail.com" || d == "googlemail.com" -> Preset("imap.gmail.com", "smtp.gmail.com", 465, "ssl")
        d == "yahoo.com" || d.startsWith("yahoo.") -> Preset("imap.mail.yahoo.com", "smtp.mail.yahoo.com", 465, "ssl")
        d == "icloud.com" || d == "me.com" -> Preset("imap.mail.me.com", "smtp.mail.me.com", 587, "starttls")
        d == "outlook.com" || d == "hotmail.com" || d == "live.com" -> Preset("outlook.office365.com", "smtp.office365.com", 587, "starttls")
        d == "seznam.cz" || d == "email.cz" -> Preset("imap.seznam.cz", "smtp.seznam.cz", 465, "ssl")
        d == "zoho.com" -> Preset("imap.zoho.com", "smtp.zoho.com", 465, "ssl")
        d.isNotEmpty() -> Preset("imap.$d", "smtp.$d", 465, "ssl")
        else -> Preset("", "", 465, "ssl")
    }
}

private fun defaultPort(protocol: String, security: String): Int = when {
    protocol == "pop3" -> if (security == "ssl") 995 else 110
    else -> if (security == "ssl") 993 else 143
}

/** Form for an IMAP or POP3 account; the login is tested before the account is saved. */
@Composable
fun AddImapAccountDialog(onDismiss: () -> Unit, onAdd: suspend (ImapConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var protocol by remember { mutableStateOf("imap") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var hostEdited by remember { mutableStateOf(false) }
    var security by remember { mutableStateOf("ssl") }
    var port by remember { mutableStateOf("993") }
    var smtpHost by remember { mutableStateOf("") }
    var smtpHostEdited by remember { mutableStateOf(false) }
    var smtpPort by remember { mutableStateOf("465") }
    var smtpSecurity by remember { mutableStateOf("ssl") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyPreset(newEmail: String, newProtocol: String) {
        val p = presetFor(newEmail)
        if (!hostEdited) host = if (newProtocol == "pop3" && p.imap.startsWith("imap.")) "pop." + p.imap.removePrefix("imap.") else p.imap
        if (!smtpHostEdited) { smtpHost = p.smtp; smtpPort = p.smtpPort.toString(); smtpSecurity = p.smtpSecurity }
    }

    val valid = '@' in email && password.isNotEmpty() && host.isNotBlank() && port.toIntOrNull() != null &&
        smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Pridať IMAP / POP účet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = protocol == "imap", onClick = { protocol = "imap"; port = defaultPort("imap", security).toString(); applyPreset(email, "imap") }, label = { Text("IMAP") })
                    FilterChip(selected = protocol == "pop3", onClick = { protocol = "pop3"; port = defaultPort("pop3", security).toString(); applyPreset(email, "pop3") }, label = { Text("POP3") })
                }
                OutlinedTextField(
                    email, { email = it.trim(); applyPreset(email, protocol) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Heslo") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text("Príchodzí server", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(host, { host = it.trim(); hostEdited = true }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server") })
                SecurityRow(security) { security = it; port = defaultPort(protocol, it).toString() }
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("Odchádzajúci server (SMTP)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(smtpHost, { smtpHost = it.trim(); smtpHostEdited = true }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SMTP server") })
                SecurityRow(smtpSecurity) { smtpSecurity = it; smtpPort = if (it == "ssl") "465" else if (it == "starttls") "587" else "25" }
                OutlinedTextField(
                    smtpPort, { smtpPort = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SMTP port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "Pri Gmaile, Yahoo či iCloude treba heslo aplikácie (nie bežné heslo). Outlook.com už často vyžaduje prihlásenie cez OAuth, kde heslo nefunguje.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
            } else {
                TextButton(enabled = valid, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            onAdd(
                                ImapConfig(
                                    protocol, email, email, password, host, port.toInt(), security,
                                    smtpHost, smtpPort.toInt(), smtpSecurity,
                                ),
                            )
                            onDismiss()
                        } catch (e: Exception) {
                            error = "Prihlásenie zlyhalo: ${e.message ?: e.javaClass.simpleName}"
                            busy = false
                        }
                    }
                }) { Text("Pridať") }
            }
        },
        dismissButton = { if (!busy) TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}

@Composable
private fun SecurityRow(current: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = current == "ssl", onClick = { onChange("ssl") }, label = { Text("SSL/TLS") })
        FilterChip(selected = current == "starttls", onClick = { onChange("starttls") }, label = { Text("STARTTLS") })
        FilterChip(selected = current == "none", onClick = { onChange("none") }, label = { Text("Žiadne") })
    }
}
