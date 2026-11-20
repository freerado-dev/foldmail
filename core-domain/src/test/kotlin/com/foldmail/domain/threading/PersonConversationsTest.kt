package com.foldmail.domain.threading

import com.foldmail.domain.model.EmailMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonConversationsTest {
    private val me = "freerado@gmail.com"
    private val marek = "Ing. Marek Strnisko <strniskom@seznam.cz>"

    private fun inbound(id: Long, from: String, subject: String, t: Long, to: List<String> = listOf(me), cc: List<String> = emptyList(), account: Long = 1, read: Boolean = true) =
        EmailMessage(id, account, "<m$id@x>", null, emptyList(), subject, t, me, from, to, cc, read)

    private fun outbound(id: Long, to: List<String>, subject: String, t: Long, account: Long = 1) =
        EmailMessage(id, account, "<m$id@x>", null, emptyList(), subject, t, me, "Rado H <$me>", to)

    @Test
    fun different_subjects_from_one_person_form_one_chat_with_my_replies() {
        val chats = PersonConversations.build(listOf(
            inbound(1, marek, "OSVETLENIE", 100),
            outbound(2, listOf("strniskom@seznam.cz"), "Re: OSVETLENIE", 200),
            outbound(3, listOf(marek), "Navrh na vklad", 300),
            inbound(4, marek, "Navrh na vklad", 400),
        ))
        assertEquals(1, chats.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), chats[0].messages.map { it.localId })
        assertFalse(chats[0].isGroup)
    }

    @Test
    fun different_people_are_different_chats_newest_first() {
        val chats = PersonConversations.build(listOf(
            inbound(1, "a@x.com", "s", 100),
            inbound(2, "b@x.com", "s", 300),
        ))
        assertEquals(listOf("b@x.com", "a@x.com"), chats.map { it.key })
    }

    @Test
    fun mail_to_several_people_is_a_group_chat() {
        val chats = PersonConversations.build(listOf(
            inbound(1, "alena@x.com", "Kunov", 100, cc = listOf("krcek@x.com")),
            inbound(2, "alena@x.com", "Hi", 200),
        ))
        assertEquals(2, chats.size)
        assertTrue(chats.first { it.participants.size == 2 }.isGroup)
    }

    @Test
    fun unified_merges_accounts_but_single_account_view_keeps_them_apart() {
        val msgs = listOf(inbound(1, "a@x.com", "s", 100, account = 1), inbound(2, "a@x.com", "s", 200, account = 2))
        assertEquals(1, PersonConversations.build(msgs, mergeAccounts = true).size)
        assertEquals(2, PersonConversations.build(msgs, mergeAccounts = false).size)
    }

    @Test
    fun unread_counts_only_incoming() {
        val chat = PersonConversations.build(listOf(
            inbound(1, "a@x.com", "s", 100, read = false),
            inbound(2, "a@x.com", "s", 200, read = true),
            outbound(3, listOf("a@x.com"), "s", 300),
        )).single()
        assertEquals(1, chat.unreadCount)
    }

    @Test
    fun address_case_and_display_name_do_not_split_a_chat() {
        val chats = PersonConversations.build(listOf(
            inbound(1, "Marek <STRNISKOM@seznam.cz>", "a", 100),
            inbound(2, "strniskom@seznam.cz", "b", 200),
        ))
        assertEquals(1, chats.size)
    }

    @Test
    fun different_subjects_become_separate_subthreads_inside_the_chat() {
        val chat = PersonConversations.build(listOf(
            inbound(1, "a@x.com", "OSVETLENIE", 100),
            inbound(2, "a@x.com", "Navrh na vklad", 200),
        )).single()
        assertEquals(2, chat.threads.size)
    }
}
