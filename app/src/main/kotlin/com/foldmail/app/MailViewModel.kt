package com.foldmail.app

import androidx.lifecycle.ViewModel
import com.foldmail.domain.threading.PersonConversation
import com.foldmail.domain.threading.PersonConversations
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/** Immutable snapshot the UI renders: chats (newest first), body lookup and name directory. */
data class MailUiState(
    val conversations: List<PersonConversation> = emptyList(),
    val bodies: Map<Long, String> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val selfAddress: String = "",
)

class MailViewModel(
    private val repo: MailRepository = InMemoryMailRepository(),
) : ViewModel() {

    val state: StateFlow<MailUiState> = repo.items
        .map { items ->
            val emails = items.map { it.email }
            MailUiState(
                conversations = PersonConversations.build(emails),
                bodies = items.associate { it.email.localId to it.body },
                names = nameDirectory(emails),
                selfAddress = repo.accountAddress,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailUiState())

    fun send(to: List<String>, subject: String, body: String, inReplyTo: String? = null) =
        repo.send(to, subject, body, inReplyTo)

    fun markRead(conversation: PersonConversation) =
        repo.markRead(conversation.messages.filter { !it.isRead }.map { it.localId })
}
