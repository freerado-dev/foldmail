package com.foldmail.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foldmail.app.MailUiState
import com.foldmail.app.formatTimestamp
import com.foldmail.app.isSelfNote
import com.foldmail.app.previewOf
import com.foldmail.app.sectionOf
import com.foldmail.app.title
import com.foldmail.domain.threading.PersonConversation

/** Tabs are icon-only so they never run out of room when the list pane is dragged narrow. */
enum class ListFilter(val label: String, val icon: ImageVector) {
    All("Všetky", Icons.Filled.Inbox),
    Notes("Poznámky", Icons.Filled.StickyNote2),
    Starred("S hviezdičkou", Icons.Filled.Star),
}

private val AccountColors = listOf(Color(0xFFFF3B30), Color(0xFF34C759), Color(0xFF0A28F0), Color(0xFFFF9500))
private fun accountColor(id: Long) = AccountColors[((id - 1).toInt().coerceAtLeast(0)) % AccountColors.size]

/** Spike-style list: tabs, pinned block, day sections, one row per person (or group). */
@Composable
fun ConversationListPane(
    ui: MailUiState,
    selectedKey: String?,
    onOpen: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onToggleStar: (String) -> Unit,
    modifier: Modifier = Modifier,
    heading: String = "Zjednotené",
    groupsOnly: Boolean = false,
) {
    var filter by rememberSaveable { mutableStateOf(ListFilter.All) }
    var pinnedOpen by rememberSaveable { mutableStateOf(true) }

    val visible = remember(ui, filter, groupsOnly) {
        ui.conversations
            .filter { !groupsOnly || it.isGroup }
            .filter {
                when (filter) {
                    ListFilter.All -> true
                    ListFilter.Notes -> it.isSelfNote(ui.selfAddress)
                    ListFilter.Starred -> it.key in ui.starred
                }
            }
    }
    val pinned = visible.filter { it.key in ui.pinned }
    val sections = visible.filter { it.key !in ui.pinned }.groupBy { sectionOf(it.last.sentAtMillis) }

    Column(modifier.statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Email, null, tint = Color.White) }
            Column(Modifier.padding(start = 12.dp)) {
                Text(heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Účtov: ${ui.accountCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            ListFilter.entries.forEach { f ->
                val active = f == filter
                Column(
                    Modifier.weight(1f).clickable { filter = f }.padding(top = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        f.icon, contentDescription = f.label,
                        tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        Modifier.padding(top = 3.dp).height(3.dp).fillMaxWidth(0.5f)
                            .background(if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                    )
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(Modifier.fillMaxSize()) {
            if (pinned.isNotEmpty()) {
                item(key = "hdr-pinned") {
                    SectionHeader("Pripnuté (${pinned.size})", expandable = true, open = pinnedOpen) { pinnedOpen = !pinnedOpen }
                }
                if (pinnedOpen) items(pinned, key = { "p-" + it.key }) { c ->
                    ConversationRow(c, ui, c.key == selectedKey, onOpen, onTogglePin, onToggleStar)
                }
            }
            sections.forEach { (name, list) ->
                item(key = "hdr-$name") { SectionHeader(name) }
                items(list, key = { it.key }) { c ->
                    ConversationRow(c, ui, c.key == selectedKey, onOpen, onTogglePin, onToggleStar)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, expandable: Boolean = false, open: Boolean = true, onToggle: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = expandable, onClick = onToggle).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (expandable) Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: PersonConversation,
    ui: MailUiState,
    selected: Boolean,
    onOpen: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onToggleStar: (String) -> Unit,
) {
    val title = c.title(ui.names, ui.selfAddress)
    val unread = c.unreadCount > 0
    val last = c.last
    val hasAttachment = ui.attachments[last.localId].orEmpty().isNotEmpty()
    var menu by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                .combinedClickable(onClick = { onOpen(c.key) }, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(title, size = 38.dp, isGroup = c.isGroup)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 4.dp, height = 14.dp).clip(RoundedCornerShape(3.dp)).background(accountColor(last.accountId)))
                    Text(
                        title, Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    )
                    Text(
                        formatTimestamp(last.sentAtMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    last.subject, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                )
                val preview = (if (hasAttachment) "[Prílohy] " else "") +
                    (if (last.isOutgoing) "Ty: " else "") + previewOf(ui.bodies[last.localId].orEmpty())
                Text(
                    preview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (c.key in ui.pinned) "Odopnúť" else "Pripnúť") },
                onClick = { onTogglePin(c.key); menu = false },
            )
            DropdownMenuItem(
                text = { Text(if (c.key in ui.starred) "Odstrániť hviezdičku" else "Označiť hviezdičkou") },
                onClick = { onToggleStar(c.key); menu = false },
            )
        }
    }
}
