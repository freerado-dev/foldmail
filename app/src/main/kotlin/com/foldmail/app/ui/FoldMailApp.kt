package com.foldmail.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldmail.app.MailViewModel
import kotlinx.coroutines.launch

enum class MainTab(val label: String, val icon: ImageVector) {
    Chats("Chaty", Icons.Filled.ChatBubble),
    Calendar("Kalendár", Icons.Filled.CalendarMonth),
    Groups("Skupiny", Icons.Filled.Groups),
    Contacts("Kontakty", Icons.Filled.Person),
    ;

    val isChatList: Boolean get() = this == Chats || this == Groups
}

private const val RailWidthDp = 80f
private const val MinListDp = 240f
private const val MinDetailDp = 320f

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

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val rowActions = RowActions(
        togglePin = vm::togglePin, toggleStar = vm::toggleStar, toggleMute = vm::toggleMute,
        togglePriority = vm::togglePriority, toggleAutoArchive = vm::toggleAutoArchive,
        block = vm::block, snooze = vm::snooze, moveTo = vm::moveTo,
    )
    fun showList() { tab = MainTab.Chats; openKey = null; composing = false; scope.launch { drawerState.close() } }

    fun startCompose(to: String = "") { composeTo = to; composing = true; tab = MainTab.Chats }
    fun openChat(key: String) { openKey = key; composing = false; tab = MainTab.Chats }

    var listWidthDp by rememberSaveable { mutableFloatStateOf(360f) }
    val density = LocalDensity.current.density

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AccountsDrawer(
                ui,
                onSelectAccount = { vm.selectAccount(it); showList() },
                onView = { vm.setView(it); showList() },
            )
        },
    ) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val expanded = maxWidth >= ExpandedBreakpoint

        val listPane: @Composable (Modifier) -> Unit = { mod ->
            ConversationListPane(
                ui, selectedKey = if (expanded) openKey else null, onOpen = ::openChat,
                actions = rowActions,
                onView = vm::setView,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                modifier = mod,
                heading = if (tab == MainTab.Groups) "Skupiny" else null,
                groupsOnly = tab == MainTab.Groups,
            )
        }
        val detailPane: @Composable (Modifier) -> Unit = { mod ->
            when {
                composing -> ComposePane(
                    initialTo = composeTo,
                    onClose = { composing = false },
                    onSend = { to, subject, body, files -> vm.send(to, subject, body, attachments = files); composing = false },
                    modifier = mod,
                )
                open != null -> ChatPane(
                    conversation = open,
                    ui = ui,
                    onBack = if (expanded) null else ({ openKey = null }),
                    onOpened = { vm.markRead(open) },
                    onReply = { text, files ->
                        val subject = open.last.subject.let { if (it.startsWith("Re:", true)) it else "Re: $it" }
                        vm.send(open.participants.toList(), subject, text, open.last.messageId, files)
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
            // Left pane is user-resizable by dragging the divider; the right pane takes the rest.
            val maxList = (maxWidth.value - RailWidthDp - MinDetailDp).coerceAtLeast(MinListDp)
            val leftWidth = listWidthDp.coerceIn(MinListDp, maxList)
            val dragger: (Float) -> Unit = { px -> listWidthDp = (leftWidth + px / density).coerceIn(MinListDp, maxList) }
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
                        listPane(Modifier.width(leftWidth.dp).fillMaxHeight())
                        PaneDivider(onDrag = dragger)
                        detailPane(Modifier.weight(1f).fillMaxHeight())
                    }
                    MainTab.Contacts -> {
                        contacts(Modifier.width(leftWidth.dp).fillMaxHeight())
                        PaneDivider(onDrag = dragger)
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

/** Draggable divider between list and detail pane, with a grab handle at the bottom (as in Samsung Email). */
@Composable
private fun PaneDivider(onDrag: (Float) -> Unit) {
    Box(
        Modifier
            .width(20.dp)
            .fillMaxHeight()
            .draggable(state = rememberDraggableState(onDrag), orientation = Orientation.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).size(width = 5.dp, height = 56.dp)
                .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
        )
    }
}
