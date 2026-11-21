package com.foldmail.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.foldmail.app.data.AppMailRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.threading.PersonConversation
import com.foldmail.domain.threading.PersonConversations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Which set of chats the list shows. */
enum class ListView { All, Notes, Starred, Archived, Trash }

/** Offered for 5 seconds after archiving or trashing. */
data class UndoInfo(val id: Long, val text: String)

/** Per-conversation user state, keyed by conversation key. */
data class Flags(
    val pinned: Set<String> = setOf("anna@example.com"), // demo: one pinned chat so the section is visible
    val starred: Set<String> = emptySet(),
    val muted: Set<String> = emptySet(),
    val priority: Set<String> = emptySet(),
    val autoArchive: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
    /** conversation key -> time it was archived; new mail after that brings the chat back. */
    val archived: Map<String, Long> = emptyMap(),
    val snoozed: Map<String, Long> = emptyMap(),
    /** Messages in the trash (still on the server until deleted from the trash). */
    val trashed: Set<Long> = emptySet(),
)

/** Immutable snapshot the UI renders. */
data class MailUiState(
    val conversations: List<PersonConversation> = emptyList(),
    val bodies: Map<Long, String> = emptyMap(),
    val attachments: Map<Long, List<Attachment>> = emptyMap(),
    /** Messages that are shown rendered as HTML (the HTML is loaded on demand). */
    val richIds: Set<Long> = emptySet(),
    val syncProgress: String? = null,
    /** Pre-computed one-line previews of each conversation's last message. */
    val previews: Map<Long, String> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    /** Everyone seen in the mailbox, most recently used first (suggestions for new mail). */
    val contacts: List<String> = emptyList(),
    val selfAddress: String = "",
    val accounts: List<Account> = emptyList(),
    val selectedAccount: Long? = null,
    val unreadByAccount: Map<Long, Int> = emptyMap(),
    val view: ListView = ListView.All,
    val flags: Flags = Flags(),
    val syncing: Boolean = false,
    val syncError: String? = null,
    val message: String? = null,
    val undo: UndoInfo? = null,
) {
    val accountCount: Int get() = accounts.size
    val pinned: Set<String> get() = flags.pinned
    val starred: Set<String> get() = flags.starred
    val totalUnread: Int get() = unreadByAccount.values.sum()
    fun accountColor(id: Long): Long = accounts.firstOrNull { it.id == id }?.colorArgb ?: 0xFF8E8E93
}

private data class ViewState(val account: Long? = null, val view: ListView = ListView.All)

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: MailRepository = AppMailRepository(app)
    private val clock: () -> Long = System::currentTimeMillis

    private val prefs = app.getSharedPreferences("foldmail_flags", android.content.Context.MODE_PRIVATE)
    private val flags = MutableStateFlow(
        Flags(
            trashed = prefs.getStringSet("trashed", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
            archived = prefs.getStringSet("archived", emptySet()).orEmpty().mapNotNull {
                val i = it.lastIndexOf('|'); if (i < 0) null else it.substring(0, i) to (it.substring(i + 1).toLongOrNull() ?: return@mapNotNull null)
            }.toMap(),
        ),
    )
    private var undoAction: (() -> Unit)? = null
    private val undoInfo = MutableStateFlow<UndoInfo?>(null)
    private var undoCounter = 0L
    private val viewState = MutableStateFlow(ViewState())
    private val message = MutableStateFlow<String?>(null)

    init {
        // Keep trash and archive across restarts.
        viewModelScope.launch {
            flags.collect { f ->
                prefs.edit()
                    .putStringSet("trashed", f.trashed.mapTo(HashSet()) { it.toString() })
                    .putStringSet("archived", f.archived.entries.mapTo(HashSet()) { "${it.key}|${it.value}" })
                    .apply()
            }
        }
        // Pull new mail once a minute while the app is alive (no-op without a real account).
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                repo.refresh()
            }
        }
    }

    private val statusAndMessage = combine(repo.status, message, undoInfo) { s, m, u -> Triple(s, m, u) }

    val state: StateFlow<MailUiState> = combine(repo.items, flags, viewState, repo.accounts, statusAndMessage) { items, f, vs, accounts, sm ->
        val acc = vs.account
        val view = vs.view
        val allEmails: List<EmailMessage> = items.map { it.email }
        val inTrash = view == ListView.Trash
        val emails: List<EmailMessage> = allEmails.filter { it.localId !in f.trashed }
        val pool = if (inTrash) allEmails.filter { it.localId in f.trashed } else emails
        val scoped = if (acc == null) pool else pool.filter { it.accountId == acc }
        // Keys stay person-based in every account view so flags survive switching accounts.
        val all = PersonConversations.build(scoped, mergeAccounts = true)
        val now = clock()
        val visible = all.filter { c ->
            val k = c.key
            val archivedAt = f.archived[k]
            val isArchived = archivedAt != null && c.last.sentAtMillis <= archivedAt
            if (inTrash) true
            else if (view == ListView.Archived) isArchived
            else !isArchived && k !in f.blocked && (f.snoozed[k] ?: 0L) <= now
        }
        val bodies = items.associate { it.email.localId to it.body }
        MailUiState(
            conversations = visible,
            bodies = bodies,
            attachments = items.associate { it.email.localId to it.attachments },
            previews = visible.associate { it.last.localId to previewOf(bodies[it.last.localId].orEmpty()) },
            richIds = items.filter { it.rich }.mapTo(HashSet()) { it.email.localId },
            names = nameDirectory(allEmails),
            contacts = contactList(allEmails, accounts.map { it.email.lowercase() }.toSet()),
            selfAddress = repo.accountAddress,
            accounts = accounts,
            selectedAccount = acc,
            unreadByAccount = emails.filter { !it.isRead && !it.isOutgoing }.groupingBy { it.accountId }.eachCount(),
            view = view,
            flags = f,
            syncing = sm.first.syncing,
            syncError = sm.first.error,
            syncProgress = sm.first.progress,
            message = sm.second,
            undo = sm.third,
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailUiState())

    /** [accountId] null = the account currently selected in the drawer, else the first one. */
    fun send(
        to: List<String>, subject: String, body: String, inReplyTo: String? = null,
        attachments: List<Attachment> = emptyList(), accountId: Long? = null,
        cc: List<String> = emptyList(), bcc: List<String> = emptyList(),
    ) = repo.send(
        to, subject, body, inReplyTo, attachments,
        accountId ?: viewState.value.account ?: repo.accounts.value.firstOrNull()?.id ?: 1L,
        cc, bcc,
    )

    fun refresh() = repo.refresh()

    fun addGoogleAccount(accessToken: String) {
        viewModelScope.launch {
            try {
                repo.addGoogleAccount(accessToken)
                message.value = "Účet pridaný, sťahujem poštu…"
            } catch (e: Exception) {
                message.value = "Pridanie účtu zlyhalo: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Throws with a readable message when the login does not work. */
    suspend fun addImapAccount(config: ImapConfig) {
        repo.addImapAccount(config)
        message.value = "Účet pridaný, sťahujem poštu…"
    }

    fun removeAccount(id: Long) {
        if (viewState.value.account == id) selectAccount(null)
        repo.removeAccount(id)
        message.value = "Účet odstránený"
    }

    fun reportMessage(text: String) { message.value = text }
    fun consumeMessage() { message.value = null }

    fun markRead(conversation: PersonConversation) =
        repo.markRead(conversation.messages.filter { !it.isRead }.map { it.localId })

    fun selectAccount(id: Long?) { viewState.value = ViewState(id, ListView.All) }
    fun setView(v: ListView) { viewState.update { it.copy(view = v) } }

    fun togglePin(key: String) = flags.update { it.copy(pinned = it.pinned.toggle(key)) }
    fun toggleStar(key: String) {
        val nowStarred = key !in flags.value.starred
        flags.update { it.copy(starred = it.starred.toggle(key)) }
        state.value.conversations.firstOrNull { it.key == key }?.messages?.lastOrNull()
            ?.let { repo.setStarred(listOf(it.localId), nowStarred) }
    }

    suspend fun fetchAttachment(localId: Long, a: Attachment): Attachment? = repo.fetchAttachment(localId, a)
    suspend fun resolveHtml(localId: Long): String? = repo.resolveHtml(localId)
    fun toggleMute(key: String) = flags.update { it.copy(muted = it.muted.toggle(key)) }
    fun togglePriority(key: String) = flags.update { it.copy(priority = it.priority.toggle(key)) }
    fun toggleAutoArchive(key: String) = flags.update { it.copy(autoArchive = it.autoArchive.toggle(key)) }
    fun block(key: String) = flags.update { it.copy(blocked = it.blocked + key) }
    fun snooze(key: String, untilMillis: Long) = flags.update { it.copy(snoozed = it.snoozed + (key to untilMillis)) }

    private fun offerUndo(text: String, action: () -> Unit) {
        undoAction = action
        undoInfo.value = UndoInfo(++undoCounter, text)
    }

    fun undo() {
        val a = undoAction
        undoAction = null
        undoInfo.value = null
        a?.invoke()
    }

    fun dismissUndo(id: Long) {
        if (undoInfo.value?.id == id) { undoInfo.value = null; undoAction = null }
    }

    private fun idsOf(key: String): List<Long> =
        state.value.conversations.firstOrNull { it.key == key }?.messages?.map { it.localId }.orEmpty()

    /** Moves to the trash (undoable); from inside the trash it deletes for real. */
    private fun trash(ids: List<Long>, text: String) {
        if (ids.isEmpty()) return
        if (viewState.value.view == ListView.Trash) {
            repo.moveMessages(ids, Folder.Trash)
            flags.update { it.copy(trashed = it.trashed - ids.toSet()) }
            return
        }
        flags.update { it.copy(trashed = it.trashed + ids) }
        offerUndo(text) { flags.update { it.copy(trashed = it.trashed - ids.toSet()) } }
    }

    fun moveTo(key: String, folder: Folder) {
        val ids = idsOf(key)
        if (ids.isEmpty()) return
        when (folder) {
            Folder.Trash -> trash(ids, "Presunuté do koša")
            Folder.Archive -> {
                val before = flags.value
                repo.moveMessages(ids, Folder.Archive)
                flags.update { it.copy(archived = it.archived + (key to clock()), snoozed = it.snoozed - key, trashed = it.trashed - ids.toSet()) }
                offerUndo("Archivované") {
                    repo.moveMessages(ids, Folder.Inbox)
                    flags.update {
                        it.copy(
                            archived = before.archived[key]?.let { t -> it.archived + (key to t) } ?: (it.archived - key),
                            trashed = it.trashed + ids.filter { id -> id in before.trashed },
                        )
                    }
                }
            }
            Folder.Inbox -> {
                repo.moveMessages(ids, Folder.Inbox)
                flags.update { it.copy(archived = it.archived - key, snoozed = it.snoozed - key, trashed = it.trashed - ids.toSet()) }
            }
        }
    }

    /** Deletes one message: to the trash, or for real when already in the trash. */
    fun deleteMessage(localId: Long) = trash(listOf(localId), "Správa presunutá do koša")

    /** Deletes everything shown in the trash for real. */
    fun emptyTrash() {
        val ids = state.value.conversations.flatMap { c -> c.messages.map { it.localId } }
        if (ids.isEmpty()) return
        repo.moveMessages(ids, Folder.Trash)
        flags.update { it.copy(trashed = it.trashed - ids.toSet()) }
    }

    private fun Set<String>.toggle(k: String) = if (k in this) this - k else this + k
}

/** Addresses from all mail, newest first, without the user's own accounts. */
private fun contactList(emails: List<EmailMessage>, own: Set<String>): List<String> {
    val last = HashMap<String, Long>()
    for (m in emails) {
        for (raw in listOf(m.from) + m.to + m.cc) {
            val a = com.foldmail.domain.model.normalizeAddress(raw)
            if ('@' !in a || a in own) continue
            val t = last[a]
            if (t == null || m.sentAtMillis > t) last[a] = m.sentAtMillis
        }
    }
    return last.entries.sortedByDescending { it.value }.take(3000).map { it.key }
}
