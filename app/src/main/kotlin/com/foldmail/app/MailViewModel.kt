package com.foldmail.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foldmail.domain.threading.PersonConversation
import com.foldmail.domain.threading.PersonConversations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Immutable snapshot the UI renders: chats (newest first), body lookup and name directory. */
data class MailUiState(
    val conversations: List<PersonConversation> = emptyList(),
    val bodies: Map<Long, String> = emptyMap(),
    val attachments: Map<Long, List<String>> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val selfAddress: String = "",
    val accountCount: Int = 0,
    val pinned: Set<String> = emptySet(),
    val starred: Set<String> = emptySet(),
)

class MailViewModel(
    private val repo: MailRepository = InMemoryMailRepository(),
) : ViewModel() {

    // Demo: start with one pinned chat so the "Pripnuté" section is visible.
    private val pinned = MutableStateFlow(setOf("anna@example.com"))
    private val starred = MutableStateFlow(emptySet<String>())

    val state: StateFlow<MailUiState> = combine(repo.items, pinned, starred) { items, pin, star ->
        val emails = items.map { it.email }
        MailUiState(
            conversations = PersonConversations.build(emails),
            bodies = items.associate { it.email.localId to it.body },
            attachments = items.associate { it.email.localId to it.attachments },
            names = nameDirectory(emails),
            selfAddress = repo.accountAddress,
            accountCount = emails.map { it.accountId }.distinct().size,
            pinned = pin,
            starred = star,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailUiState())

    fun send(to: List<String>, subject: String, body: String, inReplyTo: String? = null) =
        repo.send(to, subject, body, inReplyTo)

    fun markRead(conversation: PersonConversation) =
        repo.markRead(conversation.messages.filter { !it.isRead }.map { it.localId })

    fun togglePin(key: String) = pinned.update { if (key in it) it - key else it + key }
    fun toggleStar(key: String) = starred.update { if (key in it) it - key else it + key }
}
