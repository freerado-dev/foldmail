package com.foldmail.app

import com.foldmail.domain.model.EmailMessage

/** Sample mailbox so the UI is usable before any real account/sync exists. */
object DemoData {
    const val ME = "me@foldmail.app"
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    fun items(now: Long): List<MailItem> {
        var id = 0L
        fun mail(
            from: String, to: List<String>, subject: String, ago: Long, body: String,
            messageId: String? = null, inReplyTo: String? = null, read: Boolean = true,
        ): MailItem {
            id++
            val mid = messageId ?: "<m$id@demo>"
            return MailItem(
                EmailMessage(
                    localId = id, accountId = 1, messageId = mid, inReplyTo = inReplyTo,
                    references = listOfNotNull(inReplyTo), subject = subject,
                    sentAtMillis = now - ago, accountAddress = ME, from = from, to = to, isRead = read,
                ),
                body,
            )
        }

        val anna = "Anna Nováková <anna@example.com>"
        val peter = "Peter Kováč <peter@example.com>"
        val lucia = "Lucia Horváthová <lucia@example.com>"
        val team = "Tím Alfa <alfa@example.com>"

        return listOf(
            mail(anna, listOf(ME), "Víkend v Tatrách", 2 * DAY, "Ahoj! Ideme na víkend do Tatier, ideš s nami?", "<a1@demo>"),
            mail(ME, listOf(anna), "Re: Víkend v Tatrách", 2 * DAY - 40 * MIN, "Rád! Kedy vyrážame?", "<a2@demo>", "<a1@demo>"),
            mail(anna, listOf(ME), "Re: Víkend v Tatrách", DAY + 3 * HOUR, "V piatok o 7:00.\n\nOn Tue, Anna wrote:\n> Rád! Kedy vyrážame?", "<a3@demo>", "<a2@demo>"),
            mail(anna, listOf(ME), "Faktúra 2026-114", 3 * HOUR, "Posielam faktúru, prosím o potvrdenie prijatia.", read = false),
            mail(anna, listOf(ME), "Faktúra 2026-114", 2 * HOUR, "A ešte prílohu som zabudla pridať, pošlem ju zvlášť.", read = false),

            mail(peter, listOf(ME), "Review PR #42", 5 * HOUR, "Pozrel by si sa na môj pull request? Dík.", read = false),
            mail(ME, listOf(peter), "Re: Review PR #42", 4 * HOUR + 30 * MIN, "Pozriem večer."),

            mail(lucia, listOf(ME), "Kalendár na december", DAY + 6 * HOUR, "Pridala som termíny porád do spoločného kalendára."),

            mail(team, listOf(ME, "Peter Kováč <peter@example.com>"), "Štvrtkový míting", 6 * HOUR, "Míting presúvame na 10:30, zasadačka B.", read = false),
            mail(ME, listOf(team, peter), "Re: Štvrtkový míting", 5 * HOUR + 10 * MIN, "OK, budem tam."),

            mail(ME, listOf(ME), "Nákupný zoznam", 4 * DAY, "mlieko, chlieb, káva"),
        )
    }
}
