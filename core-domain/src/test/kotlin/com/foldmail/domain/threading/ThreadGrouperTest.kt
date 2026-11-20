package com.foldmail.domain.threading

import com.foldmail.domain.model.EmailMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadGrouperTest {

    private fun msg(
        id: Long, mid: String?, reply: String? = null, refs: List<String> = emptyList(),
        subject: String = "Hello", t: Long = id, account: Long = 1,
    ) = EmailMessage(id, account, mid, reply, refs, subject, t)

    @Test
    fun reply_chain_is_one_conversation() {
        val groups = ThreadGrouper.group(listOf(
            msg(1, "<a@x>"),
            msg(2, "<b@x>", reply = "<a@x>", refs = listOf("<a@x>")),
            msg(3, "<c@x>", reply = "<b@x>", refs = listOf("<a@x>", "<b@x>")),
        ))
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L, 3L), groups[0].messages.map { it.localId })
    }

    @Test
    fun same_subject_without_shared_headers_stays_separate() {
        val groups = ThreadGrouper.group(listOf(
            msg(1, "<a@x>", subject = "Invoice"),
            msg(2, "<z@y>", subject = "Invoice"),
        ))
        assertEquals(2, groups.size)
    }

    @Test
    fun missing_parent_still_links_siblings() {
        val groups = ThreadGrouper.group(listOf(
            msg(1, "<b@x>", reply = "<gone@x>"),
            msg(2, "<c@x>", reply = "<gone@x>"),
        ))
        assertEquals(1, groups.size)
    }

    @Test
    fun threads_are_not_merged_across_accounts() {
        val groups = ThreadGrouper.group(listOf(
            msg(1, "<a@x>", account = 1),
            msg(2, "<b@x>", reply = "<a@x>", account = 2),
        ))
        assertEquals(2, groups.size)
    }

    @Test
    fun message_without_headers_is_its_own_conversation() {
        val groups = ThreadGrouper.group(listOf(msg(1, null), msg(2, null)))
        assertEquals(2, groups.size)
    }

    @Test
    fun newest_conversation_first_and_messages_chronological() {
        val groups = ThreadGrouper.group(listOf(
            msg(1, "<a@x>", t = 100),
            msg(2, "<b@x>", reply = "<a@x>", t = 300),
            msg(3, "<c@x>", t = 200),
        ))
        assertEquals(listOf(1L, 2L), groups[0].messages.map { it.localId })
        assertEquals(listOf(3L), groups[1].messages.map { it.localId })
    }
}
