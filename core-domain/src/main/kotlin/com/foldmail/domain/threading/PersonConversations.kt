package com.foldmail.domain.threading

import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.model.normalizeAddress

/**
 * A chat-style conversation with one person or a fixed group of people.
 * Contains every message exchanged with them (any subject), oldest first.
 */
data class PersonConversation(
    val key: String,
    val participants: Set<String>,
    val messages: List<EmailMessage>,
) {
    val isGroup: Boolean get() = participants.size > 1
    val last: EmailMessage get() = messages.last()
    val unreadCount: Int get() = messages.count { !it.isRead && !it.isOutgoing }

    /** Header-based sub-threads, for showing subject dividers inside the chat. */
    val threads: List<ConversationGroup> get() = ThreadGrouper.group(messages)
}

/**
 * Builds Spike-style chats: one conversation per counterpart (or per exact set of
 * counterparts for group mails). The user's own address is never a participant.
 */
object PersonConversations {

    /**
     * @param mergeAccounts true for the unified inbox (same person across accounts is one
     * chat); false for a single account's view, where the account id is part of the key.
     */
    fun build(messages: List<EmailMessage>, mergeAccounts: Boolean = true): List<PersonConversation> =
        messages
            .groupBy { keyOf(it, mergeAccounts) }
            .map { (key, list) ->
                PersonConversation(
                    key = key,
                    participants = counterparts(list.first()),
                    messages = list.sortedBy { it.sentAtMillis },
                )
            }
            .sortedByDescending { it.last.sentAtMillis }

    fun counterparts(m: EmailMessage): Set<String> {
        val own = normalizeAddress(m.accountAddress)
        val all = if (m.isOutgoing) m.to + m.cc else listOf(m.from) + m.to + m.cc
        val others = all.map(::normalizeAddress).filter { it.isNotEmpty() && it != own }.toSortedSet()
        // A mail to oneself becomes a "notes to self" chat.
        return if (others.isEmpty() && own.isNotEmpty()) setOf(own) else others
    }

    private fun keyOf(m: EmailMessage, mergeAccounts: Boolean): String {
        val people = counterparts(m).joinToString(",")
        return if (mergeAccounts) people else "${m.accountId}:$people"
    }
}
