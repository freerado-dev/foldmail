package com.foldmail.app

import androidx.lifecycle.ViewModel
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
) {
    val accountCount: Int get() = accounts.size
    val pinned: Set<String> get() = flags.pinned
    val starred: Set<String> get() = flags.starred
    val totalUnread: Int get() = unreadByAccount.values.sum()
    fun accountColor(id: Long): Long = accounts.firstOrNull { it.id == id }?.colorArgb ?: 0xFF8E8E93
}

class MailViewModel(
    private val repo: MailRepository = InMemoryMailRepository(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val flags = MutableStateFlow(Flags())
    private val selectedAccount = MutableStateFlow<Long?>(null)
    private val listView = MutableStateFlow(ListView.All)

    val state: StateFlow<MailUiState> = combine(repo.items, flags, selectedAccount, listView) { items, f, acc, view ->
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
            accounts = repo.accounts,
            selectedAccount = acc,
            unreadByAccount = emails.filter { !it.isRead && !it.isOutgoing }.groupingBy { it.accountId }.eachCount(),
            view = view,
            flags = f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailUiState())

    fun send(to: List<String>, subject: String, body: String, inReplyTo: String? = null, attachments: List<Attachment> = emptyList()) =
        repo.send(to, subject, body, inReplyTo, attachments, selectedAccount.value ?: 1L)

    fun markRead(conversation: PersonConversation) =
        repo.markRead(conversation.messages.filter { !it.isRead }.map { it.localId })

    fun selectAccount(id: Long?) { selectedAccount.value = id; listView.value = ListView.All }
    fun setView(v: ListView) { listView.value = v }

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
