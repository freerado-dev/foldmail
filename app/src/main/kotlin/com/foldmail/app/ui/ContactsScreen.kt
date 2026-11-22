package com.foldmail.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) addresses else addresses.filter { q in it.lowercase() || q in nameOf(it, ui.names).lowercase() }

    Column(modifier.safeTop()) {
        Text(
            "Kontakty", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        androidx.compose.material3.TextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Hľadať kontakt", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { query = "" }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Zmazať")
                }
            },
            singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        if (shown.isEmpty()) Text("Nič sa nenašlo.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it }) { address ->
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
                    Avatar(name, size = 30.dp, address = address)
                    Column {
                        Text(name, fontWeight = FontWeight.SemiBold)
                        Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
