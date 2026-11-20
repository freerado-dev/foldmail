package com.foldmail.domain.threading

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteSplitterTest {
    @Test
    fun czech_original_mail_marker_from_the_screenshot() {
        val body = "díkes\n---------- Původní e-mail ----------\nOd: Rado H <freerado@gmail.com>\nPředmět: Navrh na vklad"
        val s = QuoteSplitter.split(body)
        assertEquals("díkes", s.fresh)
        assertEquals(true, s.quoted.startsWith("---------- Původní e-mail"))
    }

    @Test
    fun english_on_wrote_and_gt_quotes() {
        val s = QuoteSplitter.split("Sounds good\n\nOn Mon, 1 Jan 2026, Bob wrote:\n> hi")
        assertEquals("Sounds good", s.fresh)
    }

    @Test
    fun text_without_quote_is_untouched() {
        val s = QuoteSplitter.split("Ahoj Radi,\n\nSpolu 2.100 €")
        assertEquals("Ahoj Radi,\n\nSpolu 2.100 €", s.fresh)
        assertEquals("", s.quoted)
    }

    @Test
    fun message_that_is_only_a_quote_is_not_emptied() {
        val s = QuoteSplitter.split("> only quoted")
        assertEquals("> only quoted", s.fresh)
    }
}
