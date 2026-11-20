package com.foldmail.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldmail.app.MailViewModel

enum class MainTab(val label: String, val icon: ImageVector) {
    Chats("Chaty", Icons.Filled.ChatBubble),
    Calendar("Kalendár", Icons.Filled.CalendarMonth),
    Groups("Skupiny", Icons.Filled.Groups),
    Contacts("Kontakty", Icons.Filled.Person),
    ;

    val isChatList: Boolean get() = this == Chats || this == Groups
}

/** Width from which we switch to the two-pane layout (Z Fold 7 unfolded is well above this). */
private val ExpandedBreakpoint = 600.dp

/**
 * Adaptive shell. The decision is made from the *current window width*, so folding/unfolding
 * (or split-screen / resizing) re-lays-out live:
 *  - compact  (cover screen):  bottom bar, one pane at a time
 *  - expanded (inner screen):  navigation rail + chat list + open chat side by side
 */
@Composable
fun FoldMailApp(vm: MailViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(MainTab.Chats) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var composeTo by rememberSaveable { mutableStateOf("") }

    val open = ui.conversations.firstOrNull { it.key == openKey }

    fun startCompose(to: String = "") { composeTo = to; composing = true; tab = MainTab.Chats }
    fun openChat(key: String) { openKey = key; composing = false; tab = MainTab.Chats }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val expanded = maxWidth >= ExpandedBreakpoint

        val listPane: @Composable (Modifier) -> Unit = { mod ->
            ConversationListPane(
                ui, selectedKey = if (expanded) openKey else null, onOpen = ::openChat,
                onTogglePin = vm::togglePin, onToggleStar = vm::toggleStar,
                modifier = mod,
                heading = if (tab == MainTab.Groups) "Skupiny" else "Zjednotené",
                groupsOnly = tab == MainTab.Groups,
            )
        }
        val detailPane: @Composable (Modifier) -> Unit = { mod ->
            when {
                composing -> ComposePane(
                    initialTo = composeTo,
                    onClose = { composing = false },
                    onSend = { to, subject, body -> vm.send(to, subject, body); composing = false },
                    modifier = mod,
                )
                open != null -> ChatPane(
                    conversation = open,
                    ui = ui,
                    onBack = if (expanded) null else ({ openKey = null }),
                    onOpened = { vm.markRead(open) },
                    onReply = { text ->
                        val subject = open.last.subject.let { if (it.startsWith("Re:", true)) it else "Re: $it" }
                        vm.send(open.participants.toList(), subject, text, open.last.messageId)
                    },
                    modifier = mod,
                )
                else -> EmptyDetailPane(mod)
            }
        }
        val contacts: @Composable (Modifier) -> Unit = { mod ->
            ContactsScreen(ui, onOpenChat = ::openChat, onCompose = { startCompose(it) }, modifier = mod)
        }
        val calendar: @Composable (Modifier) -> Unit = { mod -> CalendarScreen(mod) }

        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                    FloatingActionButton(
                        onClick = { startCompose() },
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) { Icon(Icons.Filled.Edit, contentDescription = "Nový email") }
                    MainTab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t && !composing,
                            onClick = { tab = t; composing = false },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
                when (tab) {
                    MainTab.Chats, MainTab.Groups -> {
                        listPane(Modifier.width(360.dp).fillMaxHeight())
                        VerticalDivider()
                        detailPane(Modifier.weight(1f).fillMaxHeight())
                    }
                    MainTab.Contacts -> {
                        contacts(Modifier.width(360.dp).fillMaxHeight())
                        VerticalDivider()
                        detailPane(Modifier.weight(1f).fillMaxHeight())
                    }
                    MainTab.Calendar -> calendar(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            val fullScreen = composing || (tab.isChatList && open != null)
            BackHandler(enabled = fullScreen) { if (composing) composing = false else openKey = null }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (!fullScreen) CompactBottomBar(tab, onTab = { tab = it }, onCompose = { startCompose() })
                },
            ) { padding ->
                val mod = Modifier.fillMaxSize().padding(padding)
                when {
                    fullScreen -> detailPane(mod)
                    tab.isChatList -> listPane(mod)
                    tab == MainTab.Contacts -> contacts(mod)
                    else -> calendar(mod)
                }
            }
        }
    }
}

/** Spike-like bar, icons only: Chats · Calendar · [New email, accent] · Groups · Contacts. */
@Composable
private fun CompactBottomBar(tab: MainTab, onTab: (MainTab) -> Unit, onCompose: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
        BarItem(MainTab.Chats, tab, onTab)
        BarItem(MainTab.Calendar, tab, onTab)
        // Always "selected" so the accent circle is always drawn; it is an action, not a destination.
        NavigationBarItem(
            selected = true, onClick = onCompose,
            icon = { Icon(Icons.Filled.Edit, contentDescription = "Nový email") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                indicatorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        BarItem(MainTab.Groups, tab, onTab)
        BarItem(MainTab.Contacts, tab, onTab)
    }
}

@Composable
private fun RowScope.BarItem(t: MainTab, current: MainTab, onTab: (MainTab) -> Unit) {
    NavigationBarItem(
        selected = current == t, onClick = { onTab(t) },
        icon = { Icon(t.icon, contentDescription = t.label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}
