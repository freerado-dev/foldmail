package com.foldmail.domain.threading

import com.foldmail.domain.model.EmailMessage

/** A group of messages believed to belong to one conversation. */
data class ConversationGroup(val messages: List<EmailMessage>)

/**
 * Groups messages into conversations using only RFC 5322 threading headers
 * (Message-ID, In-Reply-To, References). The subject is never used to merge
 * groups, so unrelated mails with the same subject stay separate.
 *
 * Messages are linked when they share any header id. A shared id that is not
 * itself a stored message (a parent we never received) still links siblings.
 * Threads are never merged across accounts.
 */
object ThreadGrouper {

    fun group(messages: List<EmailMessage>): List<ConversationGroup> {
        if (messages.isEmpty()) return emptyList()

        val parent = IntArray(messages.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // (accountId, headerId) -> first message index that mentions it
        val seen = HashMap<Pair<Long, String>, Int>()
        messages.forEachIndexed { i, m ->
            for (id in idsOf(m)) {
                val key = m.accountId to id
                val prev = seen[key]
                if (prev == null) seen[key] = i else union(prev, i)
            }
        }

        return messages.indices
            .groupBy { find(it) }
            .values
            .map { idx -> ConversationGroup(idx.map { messages[it] }.sortedBy { it.sentAtMillis }) }
            .sortedByDescending { g -> g.messages.last().sentAtMillis }
    }

    private fun idsOf(m: EmailMessage): Set<String> = buildSet {
        m.messageId?.normalize()?.let(::add)
        m.inReplyTo?.normalize()?.let(::add)
        m.references.mapNotNull { it.normalize() }.forEach(::add)
    }

    private fun String.normalize(): String? =
        trim().removePrefix("<").removeSuffix(">").trim().lowercase().ifEmpty { null }
}
