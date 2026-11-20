package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldmail.app.Folder
import com.foldmail.app.ListView
import com.foldmail.app.MailUiState
import com.foldmail.app.formatTimestamp
import com.foldmail.app.isSelfNote
import com.foldmail.app.previewOf
import com.foldmail.app.sectionOf
import com.foldmail.app.title
import com.foldmail.domain.threading.PersonConversation

/** Icon-only tabs so they never run out of room when the list pane is dragged narrow. */
private enum class Tab(val view: ListView, val label: String, val icon: ImageVector) {
    All(ListView.All, "Všetky", Icons.Filled.Inbox),
    Notes(ListView.Notes, "Poznámky", Icons.Filled.StickyNote2),
    Starred(ListView.Starred, "S hviezdičkou", Icons.Filled.Star),
}

/** Compact Spike-style list: tabs, pinned block, day sections, three text lines per chat. */
@Composable
fun ConversationListPane(
    ui: MailUiState,
    selectedKey: String?,
    onOpen: (String) -> Unit,
    actions: RowActions,
    onView: (ListView) -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    heading: String? = null,
    groupsOnly: Boolean = false,
) {
    var pinnedOpen by rememberSaveable { mutableStateOf(true) }
    var revealedKey by remember { mutableStateOf<String?>(null) }
    var moreFor by remember { mutableStateOf<PersonConversation?>(null) }

    val view = ui.view
    val visible = remember(ui, view, groupsOnly) {
        ui.conversations
            .filter { !groupsOnly || it.isGroup }
            .filter {
                when (view) {
                    ListView.Notes -> it.isSelfNote(ui.selfAddress)
                    ListView.Starred -> it.key in ui.starred
                    else -> true
                }
            }
    }
    val pinned = if (view == ListView.Archived) emptyList() else visible.filter { it.key in ui.pinned }
    val sections = remember(visible, pinned) { visible.filter { it !in pinned }.groupBy { sectionOf(it.last.sentAtMillis) } }

    val account = ui.accounts.firstOrNull { it.id == ui.selectedAccount }
    val title = heading ?: when {
        view == ListView.Archived -> "Archív"
        account != null -> account.name
        else -> "Zjednotené"
    }
    val subtitle = account?.email ?: "Účtov: ${ui.accountCount}"

    Column(modifier.safeTop()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onOpenDrawer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Email, "Účty", tint = Color.White, modifier = Modifier.size(16.dp)) }
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Tab.entries.forEach { t ->
                val active = t.view == view
                Column(
                    Modifier.weight(1f).clickable { onView(t.view) }.padding(top = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        t.icon, contentDescription = t.label, modifier = Modifier.size(20.dp),
                        tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        Modifier.padding(top = 2.dp).height(2.dp).fillMaxWidth(0.4f)
                            .background(if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        if (ui.syncing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        ui.syncError?.let {
            Text(
                it, Modifier.fillMaxWidth().background(Color(0xFFFFF0F0)).padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color(0xFFB00020), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (pinned.isNotEmpty()) {
                item(key = "hdr-pinned") {
                    SectionHeader("Pripnuté (${pinned.size})", expandable = true, open = pinnedOpen) { pinnedOpen = !pinnedOpen }
                }
                if (pinnedOpen) items(pinned, key = { "p-" + it.key }) { c ->
                    ConversationRow(c, ui, c.key == selectedKey, revealedKey == c.key, { revealedKey = if (it) c.key else null }, onOpen, actions, onOpenDrawer) { moreFor = c }
                }
            }
            sections.forEach { (name, list) ->
                item(key = "hdr-$name") { SectionHeader(name) }
                items(list, key = { it.key }) { c ->
                    ConversationRow(c, ui, c.key == selectedKey, revealedKey == c.key, { revealedKey = if (it) c.key else null }, onOpen, actions, onOpenDrawer) { moreFor = c }
                }
            }
        }
    }

    moreFor?.let { c ->
        MoreMenuDialog(c, ui, actions, onDismiss = { moreFor = null; revealedKey = null })
    }
}

@Composable
private fun SectionHeader(text: String, expandable: Boolean = false, open: Boolean = true, onToggle: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = expandable, onClick = onToggle).padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
        if (expandable) Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: PersonConversation,
    ui: MailUiState,
    selected: Boolean,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    actions: RowActions,
    onSwipeRight: () -> Unit,
    onMore: () -> Unit,
) {
    val title = remember(c, ui.names, ui.selfAddress) { c.title(ui.names, ui.selfAddress) }
    val unread = c.unreadCount > 0
    val last = c.last
    val hasAttachment = ui.attachments[last.localId].orEmpty().isNotEmpty()
    val base = MaterialTheme.typography.bodyMedium
    val titleStyle = base.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold)
    val subjectStyle = base.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
    val previewStyle: TextStyle = base.copy(fontSize = 11.sp, lineHeight = 14.sp)

    SwipeRevealRow(
        revealed = revealed,
        onRevealedChange = onRevealedChange,
        actionsWidth = 216.dp,
        onSwipeRight = onSwipeRight,
        actions = {
            SwipeActionButtons(
                onMore = onMore,
                onTrash = { actions.moveTo(c.key, Folder.Trash) },
                onArchive = { actions.moveTo(c.key, Folder.Archive) },
            )
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                .combinedClickable(onClick = { if (revealed) onRevealedChange(false) else onOpen(c.key) }, onLongClick = onMore)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(title, size = 36.dp, isGroup = c.isGroup)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 4.dp, height = 13.dp).clip(RoundedCornerShape(2.dp)).background(Color(ui.accountColor(last.accountId))))
                    Text(title, Modifier.weight(1f, fill = false).padding(start = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = titleStyle)
                    if (c.key in ui.flags.muted) Icon(Icons.Filled.NotificationsOff, null, Modifier.padding(start = 4.dp).size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.key in ui.flags.priority) Icon(Icons.Filled.PriorityHigh, null, Modifier.padding(start = 2.dp).size(12.dp), tint = Color(0xFFFF3B30))
                    Box(Modifier.weight(1f))
                    Text(formatTimestamp(last.sentAtMillis), fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(last.subject, maxLines = 1, overflow = TextOverflow.Ellipsis, style = subjectStyle)
                val preview = (if (hasAttachment) "[Prílohy] " else "") +
                    (if (last.isOutgoing) "Ty: " else "") + (ui.previews[last.localId] ?: previewOf(ui.bodies[last.localId].orEmpty()))
                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = previewStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (unread) {
                Box(
                    Modifier.padding(top = 2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) { Text(c.unreadCount.toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp, lineHeight = 12.sp) }
            }
        }
    }
}
