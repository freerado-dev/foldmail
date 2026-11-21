package com.foldmail.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foldmail.app.ListView
import com.foldmail.app.MailUiState

/** Slide-in panel (swipe right anywhere): shortcuts and all accounts, like Spike. */
@Composable
fun AccountsDrawer(
    ui: MailUiState,
    onSelectAccount: (Long?) -> Unit,
    onView: (ListView) -> Unit,
    onAddAccount: () -> Unit,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onAddImap: () -> Unit = {},
    onRemoveAccount: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    var showFolders by remember { mutableStateOf(false) }
    var removeFor by remember { mutableStateOf<com.foldmail.app.Account?>(null) }
    removeFor?.let { a ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text("Odstrániť účet?") },
            text = { Text("${a.email} sa odstráni z aplikácie a jeho stiahnutá pošta sa zmaže z tohto zariadenia. Schránka na serveri ostane nezmenená.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { removeFor = null; onRemoveAccount(a.id) }) { Text("Odstrániť", color = Color(0xFFE53935)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removeFor = null }) { Text("Zrušiť") } },
        )
    }
    fun soon() = Toast.makeText(context, "Táto funkcia príde neskôr", Toast.LENGTH_SHORT).show()

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("FoldMail", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Nastavenia") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Unified inbox
            Row(
                Modifier.fillMaxWidth().clickable { onSelectAccount(null) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Email, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Zjednotené", fontWeight = FontWeight.SemiBold)
                    Text("Účtov: ${ui.accountCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Badge(ui.totalUnread)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Shortcuts
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                DrawerItem(Icons.Filled.Star, "S hviezdičkou") { onView(ListView.Starred) }
                DrawerItem(Icons.Filled.StickyNote2, "Poznámky") { onView(ListView.Notes) }
                DrawerItem(Icons.Filled.CheckBox, "Úlohy", soon = true) { soon() }
                DrawerItem(Icons.Filled.Archive, "Archív") { onView(ListView.Archived) }
                DrawerItem(Icons.Filled.Delete, "Kôš") { onView(ListView.Trash) }
                DrawerItem(Icons.Filled.Schedule, "Naplánované", soon = true) { soon() }
                DrawerItem(Icons.Filled.Edit, "Koncepty", soon = true) { soon() }
                DrawerItem(if (showFolders) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (showFolders) "Skryť priečinky" else "Zobraziť priečinky") {
                    showFolders = !showFolders
                }
                if (showFolders) {
                    DrawerItem(Icons.Filled.Inbox, "Doručené") { onView(ListView.All) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Accounts
            ui.accounts.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectAccount(a.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 4.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(Color(a.colorArgb)))
                    Box(Modifier.padding(start = 8.dp).size(34.dp), contentAlignment = Alignment.Center) { Avatar(a.name, size = 34.dp) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold)
                        Text(a.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Badge(ui.unreadByAccount[a.id] ?: 0)
                    IconButton(onClick = { removeFor = a }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Odstrániť účet", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            DrawerItem(Icons.Filled.Add, "Pridať Google účet", onClick = onAddAccount)
            DrawerItem(Icons.Filled.Add, "Pridať IMAP / POP účet", onClick = onAddImap)
            DrawerItem(Icons.Filled.Sync, "Synchronizovať", onClick = onSync)
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, soon: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (soon) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Text(
            label, Modifier.weight(1f).padding(start = 16.dp),
            color = if (soon) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Badge(count: Int) {
    if (count <= 0) return
    Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(count.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
