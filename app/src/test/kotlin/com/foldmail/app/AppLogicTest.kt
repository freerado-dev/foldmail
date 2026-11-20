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
        assertEquals(6, c.size) // Marek, Anna, Peter, Lucia, group Alfa+Peter, notes-to-self
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

    @Test fun sentAttachmentsAreKept() {
        val r = repo()
        val file = Attachment("foto.jpg", "content://x/1", "image/jpeg", 2_500_000)
        r.send(listOf("anna@example.com"), "Foto", "", attachments = listOf(file))
        val sent = r.items.value.last()
        assertEquals(listOf(file), sent.attachments)
        assertTrue(file.isImage)
        assertEquals("2.5 MB", formatSize(file.sizeBytes))
    }

    @Test fun snoozeOptionsAreInTheFutureAndAtNineAm() {
        val z = ZoneId.of("UTC")
        val o = snoozeOptions(now, z)
        assertEquals(3, o.size)
        assertTrue(o.all { it.atMillis > now })
        val tomorrow = java.time.Instant.ofEpochMilli(o[1].atMillis).atZone(z)
        assertEquals(9, tomorrow.hour)
        assertEquals(java.time.DayOfWeek.MONDAY, java.time.Instant.ofEpochMilli(o[2].atMillis).atZone(z).dayOfWeek)
    }

    @Test fun demoAccountsCoverEveryMessage() {
        val ids = DemoData.accounts.map { it.id }.toSet()
        assertTrue(DemoData.items(now).all { it.email.accountId in ids })
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
        assertEquals("Dnes", sectionOf(now - 60_000L, now, z))
        assertEquals("Včera", sectionOf(now - 24 * 3_600_000L, now, z))
        assertEquals("Tento týždeň", sectionOf(now - 3 * 24 * 3_600_000L, now, z))
        assertEquals("Staršie", sectionOf(now - 20 * 24 * 3_600_000L, now, z))
        assertEquals("previewskipsquote", previewOf("> quoted\npreviewskipsquote"))
    }
}
