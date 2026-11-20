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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Which set of chats the list shows. */
enum class ListView { All, Notes, Starred, Archived }

/** Target of "Move to" / Trash / Archive. */
enum class Folder { Inbox, Archive, Trash }

/** Per-conversation user state, keyed by conversation key. */
data class Flags(
    val pinned: Set<String> = setOf("anna@example.com"), // demo: one pinned chat so the section is visible
    val starred: Set<String> = emptySet(),
    val muted: Set<String> = emptySet(),
    val priority: Set<String> = emptySet(),
    val autoArchive: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
    val archived: Set<String> = emptySet(),
    val trashed: Set<String> = emptySet(),
    val snoozed: Map<String, Long> = emptyMap(),
)

/** Immutable snapshot the UI renders. */
data class MailUiState(
    val conversations: List<PersonConversation> = emptyList(),
    val bodies: Map<Long, String> = emptyMap(),
    val attachments: Map<Long, List<Attachment>> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val selfAddress: String = "",
    val accounts: List<Account> = emptyList(),
    val selectedAccount: Long? = null,
    val unreadByAccount: Map<Long, Int> = emptyMap(),
    val view: ListView = ListView.All,
    val flags: Flags = Flags(),
    val syncing: Boolean = false,
    val syncError: String? = null,
    val message: String? = null,
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

    private val flags = MutableStateFlow(Flags())
    private val viewState = MutableStateFlow(ViewState())
    private val message = MutableStateFlow<String?>(null)

    init {
        // Pull new mail once a minute while the app is alive (no-op without a real account).
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                repo.refresh()
            }
        }
    }

    private val statusAndMessage = combine(repo.status, message) { s, m -> s to m }

    val state: StateFlow<MailUiState> = combine(repo.items, flags, viewState, repo.accounts, statusAndMessage) { items, f, vs, accounts, sm ->
        val acc = vs.account
        val view = vs.view
        val emails: List<EmailMessage> = items.map { it.email }
        val scoped = if (acc == null) emails else emails.filter { it.accountId == acc }
        // Keys stay person-based in every account view so flags survive switching accounts.
        val all = PersonConversations.build(scoped, mergeAccounts = true)
        val now = clock()
        val visible = all.filter { c ->
            val k = c.key
            if (view == ListView.Archived) k in f.archived && k !in f.trashed
            else k !in f.archived && k !in f.trashed && k !in f.blocked && (f.snoozed[k] ?: 0L) <= now
        }
        MailUiState(
            conversations = visible,
            bodies = items.associate { it.email.localId to it.body },
            attachments = items.associate { it.email.localId to it.attachments },
            names = nameDirectory(emails),
            selfAddress = repo.accountAddress,
            accounts = accounts,
            selectedAccount = acc,
            unreadByAccount = emails.filter { !it.isRead && !it.isOutgoing }.groupingBy { it.accountId }.eachCount(),
            view = view,
            flags = f,
            syncing = sm.first.syncing,
            syncError = sm.first.error,
            message = sm.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailUiState())

    /** [accountId] null = the account currently selected in the drawer, else the first one. */
    fun send(
        to: List<String>, subject: String, body: String, inReplyTo: String? = null,
        attachments: List<Attachment> = emptyList(), accountId: Long? = null,
    ) = repo.send(
        to, subject, body, inReplyTo, attachments,
        accountId ?: viewState.value.account ?: repo.accounts.value.firstOrNull()?.id ?: 1L,
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

    fun reportMessage(text: String) { message.value = text }
    fun consumeMessage() { message.value = null }

    fun markRead(conversation: PersonConversation) =
        repo.markRead(conversation.messages.filter { !it.isRead }.map { it.localId })

    fun selectAccount(id: Long?) { viewState.value = ViewState(id, ListView.All) }
    fun setView(v: ListView) { viewState.update { it.copy(view = v) } }

    fun togglePin(key: String) = flags.update { it.copy(pinned = it.pinned.toggle(key)) }
    fun toggleStar(key: String) = flags.update { it.copy(starred = it.starred.toggle(key)) }
    fun toggleMute(key: String) = flags.update { it.copy(muted = it.muted.toggle(key)) }
    fun togglePriority(key: String) = flags.update { it.copy(priority = it.priority.toggle(key)) }
    fun toggleAutoArchive(key: String) = flags.update { it.copy(autoArchive = it.autoArchive.toggle(key)) }
    fun block(key: String) = flags.update { it.copy(blocked = it.blocked + key) }
    fun snooze(key: String, untilMillis: Long) = flags.update { it.copy(snoozed = it.snoozed + (key to untilMillis)) }

    fun moveTo(key: String, folder: Folder) = flags.update {
        val archived = if (folder == Folder.Archive) it.archived + key else it.archived - key
        val trashed = if (folder == Folder.Trash) it.trashed + key else it.trashed - key
        it.copy(archived = archived, trashed = trashed, snoozed = it.snoozed - key)
    }

    private fun Set<String>.toggle(k: String) = if (k in this) this - k else this + k
}
