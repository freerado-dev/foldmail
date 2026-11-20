package com.foldmail.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foldmail.app.MailUiState
import com.foldmail.app.nameOf

/**
 * Contacts derived from the mailbox (every individual you have exchanged mail with).
 * Tap = open that person's chat if one exists, otherwise start a new email.
 */
@Composable
fun ContactsScreen(
    ui: MailUiState,
    onOpenChat: (key: String) -> Unit,
    onCompose: (to: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val own = ui.selfAddress.lowercase()
    val addresses = ui.conversations.flatMap { it.participants }.filter { it != own }.distinct()
        .sortedBy { nameOf(it, ui.names).lowercase() }

    Column(modifier.statusBarsPadding()) {
        Text(
            "Kontakty", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(addresses, key = { it }) { address ->
                val name = nameOf(address, ui.names)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            val direct = ui.conversations.firstOrNull { it.participants == setOf(address) }
                            if (direct != null) onOpenChat(direct.key) else onCompose(address)
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Avatar(name, size = 30.dp)
                    Column {
                        Text(name, fontWeight = FontWeight.SemiBold)
                        Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
