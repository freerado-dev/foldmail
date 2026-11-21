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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
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
import com.foldmail.app.Attachment
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
    var composeCc by rememberSaveable { mutableStateOf("") }
    var composeReplyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var composeAccount by rememberSaveable { mutableStateOf(-1L) }
    var composeFiles by remember { mutableStateOf(emptyList<Attachment>()) }
    var draftVersion by rememberSaveable { mutableStateOf(0) }
    val search = remember { SearchState() }
    val chatsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val groupsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var showImap by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var viewingId by rememberSaveable { mutableStateOf<Long?>(null) }

    val open = ui.conversations.firstOrNull { it.key == openKey }
    val viewing = open?.messages?.firstOrNull { it.localId == viewingId }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val rowActions = RowActions(
        togglePin = vm::togglePin, toggleStar = vm::toggleStar, toggleMute = vm::toggleMute,
        togglePriority = vm::togglePriority, toggleAutoArchive = vm::toggleAutoArchive,
        block = vm::block, snooze = vm::snooze, moveTo = vm::moveTo, trashMessage = vm::deleteMessage, emptyTrash = vm::emptyTrash,
    )
    val context = LocalContext.current
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            vm.addGoogleAccount(GoogleAuth.tokenFromIntent(context, result.data))
        } catch (e: Exception) {
            vm.reportMessage("Prihlásenie zlyhalo: ${e.message ?: e.javaClass.simpleName}")
        }
    }
    fun authorizeAccount(email: String?) {
        scope.launch {
            try {
                val r = GoogleAuth.authorize(context, email)
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
    // Always show the account chooser so another Google account can be picked (not silently the first one again).
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        if (email != null) authorizeAccount(email)
    }
    fun addAccount() {
        try {
            val intent = com.google.android.gms.common.AccountPicker.newChooseAccountIntent(
                com.google.android.gms.common.AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(listOf("com.google"))
                    .setAlwaysShowAccountPicker(true)
                    .build(),
            )
            pickerLauncher.launch(intent)
        } catch (e: Exception) {
            vm.reportMessage("Výber účtu zlyhal: ${e.message ?: e.javaClass.simpleName}")
        }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.consumeMessage() }
    }
    fun showList() { tab = MainTab.Chats; openKey = null; composing = false; viewingId = null; showSettings = false; scope.launch { drawerState.close() } }

    fun startCompose(to: String = "") {
        composeTo = to; composeSubject = ""; composeBody = ""; composeCc = ""; composeReplyTo = null; composeAccount = -1L; composeFiles = emptyList()
        draftVersion++; composing = true; showSettings = false; tab = MainTab.Chats
    }
    fun openChat(key: String) { openKey = key; composing = false; showSettings = false; viewingId = null; tab = MainTab.Chats }
    fun onMessageAction(a: MessageAction, m: EmailMessage) {
        val body = ui.bodies[m.localId].orEmpty()
        when (a) {
            MessageAction.Reply, MessageAction.ReplyAll -> {
                composeTo = replyRecipients(m, a == MessageAction.ReplyAll).joinToString(", ")
                composeCc = ""
                composeSubject = prefixSubject(m.subject, "Re: ")
                composeBody = quoteForReply(m, body, ui.names)
                composeReplyTo = m.messageId; composeAccount = m.accountId; composeFiles = emptyList()
                draftVersion++; composing = true; viewingId = null; tab = MainTab.Chats
            }
            MessageAction.Forward -> {
                composeTo = ""; composeCc = ""; composeSubject = prefixSubject(m.subject, "Fwd: ")
                composeBody = forwardBody(m, body)
                composeReplyTo = null; composeAccount = m.accountId
                val atts = ui.attachments[m.localId].orEmpty()
                scope.launch {
                    if (atts.isNotEmpty()) Toast.makeText(context, "Pripravujem prílohy…", Toast.LENGTH_SHORT).show()
                    val files = atts.mapNotNull { a ->
                        if (a.uri != null) a else try { vm.fetchAttachment(m.localId, a) } catch (_: Exception) { null }
                    }
                    if (files.size < atts.size) Toast.makeText(context, "Niektoré prílohy sa nepodarilo stiahnuť", Toast.LENGTH_LONG).show()
                    composeFiles = files
                    draftVersion++; composing = true; viewingId = null; tab = MainTab.Chats
                }
            }
            MessageAction.Delete -> { vm.deleteMessage(m.localId); viewingId = null }
            MessageAction.ShowFull -> viewingId = m.localId
        }
    }

    var listWidthDp by rememberSaveable { mutableFloatStateOf(360f) }
    val density = LocalDensity.current.density

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAttachmentFetcher provides { id: Long, a: Attachment -> vm.fetchAttachment(id, a) },
        LocalHtmlResolver provides { id: Long -> vm.resolveHtml(id) },
    ) {
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
                onAddImap = { showImap = true; scope.launch { drawerState.close() } },
                onOpenSettings = { showSettings = true; composing = false; tab = MainTab.Chats; scope.launch { drawerState.close() } },
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
                onRefresh = vm::refresh,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                modifier = mod,
                heading = if (tab == MainTab.Groups) "Skupiny" else null,
                groupsOnly = tab == MainTab.Groups,
                listState = if (tab == MainTab.Groups) groupsListState else chatsListState,
                search = search,
            )
        }
        val detailPane: @Composable (Modifier) -> Unit = { mod ->
            when {
                showSettings -> SettingsScreen(onBack = { showSettings = false }, modifier = mod)
                composing -> key(draftVersion) {
                    ComposePane(
                        initialTo = composeTo,
                        initialSubject = composeSubject,
                        initialBody = composeBody,
                        initialCc = composeCc,
                        initialAttachments = composeFiles,
                        contacts = ui.contacts,
                        names = ui.names,
                        onClose = { composing = false },
                        onSend = { to, subject, body, files, cc, bcc ->
                            vm.send(to, subject, body, composeReplyTo, files, composeAccount.takeIf { it >= 0 }, cc, bcc)
                            composing = false
                        },
                        modifier = mod,
                    )
                }
                viewing != null -> MessageViewPane(
                    message = viewing, ui = ui,
                    onBack = { viewingId = null },
                    onAction = { a -> onMessageAction(a, viewing) },
                    resolveHtml = { vm.resolveHtml(it) },
                    modifier = mod,
                )
                open != null -> ChatPane(
                    conversation = open,
                    ui = ui,
                    onBack = if (expanded) null else ({ openKey = null }),
                    onOpened = { vm.markRead(open) },
                    onAction = ::onMessageAction,
                    onReply = { text, files, newSubject ->
                        if (newSubject == null) {
                            val subject = open.last.subject.let { if (it.startsWith("Re:", true)) it else "Re: $it" }
                            vm.send(open.participants.toList(), subject, text, open.last.messageId, files, open.last.accountId)
                        } else {
                            // Reply cancelled: a brand-new email with its own subject.
                            vm.send(open.participants.toList(), newSubject.ifBlank { "(bez predmetu)" }, text, null, files, open.last.accountId)
                        }
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
            val maxList = (maxWidth.value - MinDetailDp).coerceAtLeast(MinListDp)
            val leftWidth = listWidthDp.coerceIn(MinListDp, maxList)
            val dragger: (Float) -> Unit = { px -> listWidthDp = (leftWidth + px / density).coerceIn(MinListDp, maxList) }
            val onTab: (MainTab) -> Unit = { tab = it; composing = false; viewingId = null; showSettings = false }
            if (tab == MainTab.Calendar) {
                Column(Modifier.fillMaxSize()) {
                    calendar(Modifier.weight(1f).fillMaxWidth())
                    Box(Modifier.width(leftWidth.dp)) { CompactBottomBar(tab, onTab = onTab, onCompose = { startCompose() }) }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    // Navigation lives under the list (icons only), so the list and mail get the full height and width.
                    Column(Modifier.width(leftWidth.dp).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (tab == MainTab.Contacts) contacts(Modifier.fillMaxSize()) else listPane(Modifier.fillMaxSize())
                        }
                        CompactBottomBar(tab, onTab = onTab, onCompose = { startCompose() })
                    }
                    PaneDivider(onDrag = dragger)
                    detailPane(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            val fullScreen = showSettings || composing || (tab.isChatList && open != null)
            BackHandler(enabled = fullScreen) {
                if (showSettings) showSettings = false else if (composing) composing = false else if (viewingId != null) viewingId = null else openKey = null
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
        ui.undo?.let { u ->
            androidx.compose.runtime.LaunchedEffect(u.id) { kotlinx.coroutines.delay(5000); vm.dismissUndo(u.id) }
            androidx.compose.material3.Surface(
                color = androidx.compose.ui.graphics.Color(0xFF323232),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 84.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(u.text, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(vertical = 10.dp))
                    androidx.compose.material3.TextButton(onClick = { vm.undo() }) {
                        Text("SPÄŤ", color = androidx.compose.ui.graphics.Color(0xFF8AB4F8))
                    }
                }
            }
        }
    }
}
    }
    if (showImap) AddImapAccountDialog(onDismiss = { showImap = false }, onAdd = { vm.addImapAccount(it) })
    }

/** Slim Spike-like bar, icons only: Chats · Calendar · [New email, accent] · Groups · Contacts. */
@Composable
private fun CompactBottomBar(tab: MainTab, onTab: (MainTab) -> Unit, onCompose: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
            BarItem(MainTab.Chats, tab, onTab)
            BarItem(MainTab.Calendar, tab, onTab)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 52.dp, height = 34.dp).clip(RoundedCornerShape(17.dp))
                        .background(MaterialTheme.colorScheme.primary).clickable(onClick = onCompose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Edit, contentDescription = "Nový email", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(19.dp)) }
            }
            BarItem(MainTab.Groups, tab, onTab)
            BarItem(MainTab.Contacts, tab, onTab)
        }
    }
}

@Composable
private fun RowScope.BarItem(t: MainTab, current: MainTab, onTab: (MainTab) -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight().clickable { onTab(t) }, contentAlignment = Alignment.Center) {
        Icon(
            t.icon, contentDescription = t.label, modifier = Modifier.size(22.dp),
            tint = if (current == t) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
