package com.foldmail.app

import com.foldmail.domain.threading.PersonConversations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AppLogicTest {
    private val now = 1_800_000_000_000L
    private fun repo() = InMemoryMailRepository(DemoData.items(now)) { now }
    private fun convs(r: InMemoryMailRepository) =
        PersonConversations.build(r.items.value.map { it.email })

    @Test fun demoDataGroupsIntoOneChatPerPersonOrGroup() {
        val c = convs(repo())
        assertEquals(5, c.size) // Anna, Peter, Lucia, group Alfa+Peter, notes-to-self
        assertEquals(1, c.count { it.participants == setOf("anna@example.com") })
    }

    @Test fun sendingAddsOutgoingMessageToExistingChat() {
        val r = repo()
        val before = convs(r).first { it.participants == setOf("peter@example.com") }.messages.size
        r.send(listOf("peter@example.com"), "Re: Review PR #42", "Hotovo")
        val after = convs(r).first { it.participants == setOf("peter@example.com") }
        assertEquals(before + 1, after.messages.size)
        assertTrue(after.last.isOutgoing)
        assertEquals(now, after.last.sentAtMillis)
    }

    @Test fun markReadClearsUnread() {
        val r = repo()
        val anna = convs(r).first { "anna@example.com" in it.participants }
        assertEquals(2, anna.unreadCount)
        r.markRead(anna.messages.map { it.localId })
        assertEquals(0, convs(r).first { "anna@example.com" in it.participants }.unreadCount)
    }

    @Test fun titlesUseDisplayNamesAndSelfNotes() {
        val r = repo(); val names = nameDirectory(r.items.value.map { it.email })
        val titles = convs(r).map { it.title(names, r.accountAddress) }
        assertTrue("Anna Nováková" in titles)
        assertTrue("Poznámky pre seba" in titles)
        assertTrue(titles.any { it.contains("Tím Alfa") && it.contains("Peter Kováč") })
    }

    @Test fun timestampFormatting() {
        val z = ZoneId.of("UTC")
        assertEquals("včera", formatTimestamp(now - 24 * 3_600_000L, now, z))
        assertEquals("previewskipsquote", previewOf("> quoted\npreviewskipsquote"))
    }
}
