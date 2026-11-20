package com.foldmail.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.foldmail.app.data.GoogleAuth
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
import androidx.compose.runtime.key
import com.foldmail.app.forwardBody
import com.foldmail.app.prefixSubject
import com.foldmail.app.quoteForReply
import com.foldmail.app.replyRecipients
import com.foldmail.domain.model.EmailMessage
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
    var composeSubject by rememberSaveable { mutableStateOf("") }
    var composeBody by rememberSaveable { mutableStateOf("") }
    var composeReplyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var composeAccount by rememberSaveable { mutableStateOf(-1L) }
    var draftVersion by rememberSaveable { mutableStateOf(0) }
    var viewingId by rememberSaveable { mutableStateOf<Long?>(null) }

    val open = ui.conversations.firstOrNull { it.key == openKey }
    val viewing = open?.messages?.firstOrNull { it.localId == viewingId }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val rowActions = RowActions(
        togglePin = vm::togglePin, toggleStar = vm::toggleStar, toggleMute = vm::toggleMute,
        togglePriority = vm::togglePriority, toggleAutoArchive = vm::toggleAutoArchive,
        block = vm::block, snooze = vm::snooze, moveTo = vm::moveTo,
    )
    val context = LocalContext.current
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            vm.addGoogleAccount(GoogleAuth.tokenFromIntent(context, result.data))
        } catch (e: Exception) {
            vm.reportMessage("Prihlásenie zlyhalo: ${e.message ?: e.javaClass.simpleName}")
        }
    }
    fun addAccount() {
        scope.launch {
            try {
                val r = GoogleAuth.authorize(context, null)
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    vm.addGoogleAccount(r.accessToken ?: error("Google nevrátil prístupový token"))
                }
            } catch (e: Exception) {
                vm.reportMessage("Prihlásenie zlyhalo: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.consumeMessage() }
    }
    fun showList() { tab = MainTab.Chats; openKey = null; composing = false; viewingId = null; scope.launch { drawerState.close() } }

    fun startCompose(to: String = "") {
        composeTo = to; composeSubject = ""; composeBody = ""; composeReplyTo = null; composeAccount = -1L
        draftVersion++; composing = true; tab = MainTab.Chats
    }
    fun openChat(key: String) { openKey = key; composing = false; viewingId = null; tab = MainTab.Chats }
    fun onMessageAction(a: MessageAction, m: EmailMessage) {
        val body = ui.bodies[m.localId].orEmpty()
        when (a) {
            MessageAction.Reply, MessageAction.ReplyAll -> {
                composeTo = replyRecipients(m, a == MessageAction.ReplyAll).joinToString(", ")
                composeSubject = prefixSubject(m.subject, "Re: ")
                composeBody = quoteForReply(m, body, ui.names)
                composeReplyTo = m.messageId; composeAccount = m.accountId
                draftVersion++; composing = true; viewingId = null; tab = MainTab.Chats
            }
            MessageAction.Forward -> {
                composeTo = ""; composeSubject = prefixSubject(m.subject, "Fwd: ")
                composeBody = forwardBody(m, body)
                composeReplyTo = null; composeAccount = m.accountId
                draftVersion++; composing = true; viewingId = null; tab = MainTab.Chats
            }
            MessageAction.Delete -> { vm.deleteMessage(m.localId); viewingId = null }
            MessageAction.ShowFull -> viewingId = m.localId
        }
    }

    var listWidthDp by rememberSaveable { mutableFloatStateOf(360f) }
    val density = LocalDensity.current.density

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Closed: our own detector opens it (so rows can use horizontal drags); open: the drawer handles closing swipes.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            AccountsDrawer(
                ui,
                onSelectAccount = { vm.selectAccount(it); showList() },
                onView = { vm.setView(it); showList() },
                onAddAccount = { scope.launch { drawerState.close() }; addAccount() },
                onSync = { vm.refresh(); scope.launch { drawerState.close() } },
            )
        },
    ) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .openOnRightSwipe { scope.launch { drawerState.open() } },
    ) {
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
                composing -> key(draftVersion) {
                    ComposePane(
                        initialTo = composeTo,
                        initialSubject = composeSubject,
                        initialBody = composeBody,
                        onClose = { composing = false },
                        onSend = { to, subject, body, files ->
                            vm.send(to, subject, body, composeReplyTo, files, composeAccount.takeIf { it >= 0 })
                            composing = false
                        },
                        modifier = mod,
                    )
                }
                viewing != null -> MessageViewPane(
                    message = viewing, ui = ui,
                    onBack = { viewingId = null },
                    onAction = { a -> onMessageAction(a, viewing) },
                    modifier = mod,
                )
                open != null -> ChatPane(
                    conversation = open,
                    ui = ui,
                    onBack = if (expanded) null else ({ openKey = null }),
                    onOpened = { vm.markRead(open) },
                    onAction = ::onMessageAction,
                    onReply = { text, files ->
                        val subject = open.last.subject.let { if (it.startsWith("Re:", true)) it else "Re: $it" }
                        vm.send(open.participants.toList(), subject, text, open.last.messageId, files, open.last.accountId)
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
            BackHandler(enabled = fullScreen) {
                if (composing) composing = false else if (viewingId != null) viewingId = null else openKey = null
            }
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
