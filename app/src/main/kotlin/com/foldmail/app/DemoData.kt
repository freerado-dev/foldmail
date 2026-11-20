package com.foldmail.app

import com.foldmail.domain.model.EmailMessage

/** Sample mailbox so the UI is usable before any real account/sync exists. */
object DemoData {
    const val ME = "me@foldmail.app"

    val accounts = listOf(
        Account(1, "Rado H", "freerado@gmail.com", 0xFFFF3B30),
        Account(2, "Radoslav Halabrin", "info@omegareality.sk", 0xFF0A28F0),
        Account(3, "dokonaly zivot", "mam@dokonalyzivot.sk", 0xFF34C759),
    )
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    fun items(now: Long): List<MailItem> {
        var id = 0L
        fun mail(
            from: String, to: List<String>, subject: String, ago: Long, body: String,
            messageId: String? = null, inReplyTo: String? = null, read: Boolean = true,
            account: Long = 1, attachments: List<Attachment> = emptyList(),
        ): MailItem {
            id++
            val mid = messageId ?: "<m$id@demo>"
            return MailItem(
                EmailMessage(
                    localId = id, accountId = account, messageId = mid, inReplyTo = inReplyTo,
                    references = listOfNotNull(inReplyTo), subject = subject,
                    sentAtMillis = now - ago, accountAddress = ME, from = from, to = to, isRead = read,
                ),
                body, attachments,
            )
        }

        val anna = "Anna Nováková <anna@example.com>"
        val peter = "Peter Kováč <peter@example.com>"
        val lucia = "Lucia Horváthová <lucia@example.com>"
        val team = "Tím Alfa <alfa@example.com>"
        val marek = "Ing. Marek Strnisko <strniskom@seznam.cz>"

        return listOf(
            mail(marek, listOf(ME), "OSVETLENIE", 9 * DAY,
                "Ahoj Radi,\n\n52 Svetiel+ 26 Senzorov ........ 1.670 € s DPH\nRobota ........ 430 € s DPH\nSPOLU ........ 2.100 € s DPH\n\nTakúto cenu vieme dať my",
                "<k1@demo>", account = 2),
            mail(ME, listOf(marek), "Re: OSVETLENIE", 9 * DAY - 5 * MIN, "Super", "<k2@demo>", "<k1@demo>", account = 2),
            mail(ME, listOf(marek), "Návrh na vklad", 8 * DAY,
                "stači len prepísať číslo bytu a dátumy", "<k3@demo>", account = 2,
                attachments = listOf(Attachment("navrh na vklad_senica_705.pdf", mimeType = "application/pdf", sizeBytes = 184_000))),
            mail(marek, listOf(ME), "Návrh na vklad", 8 * DAY - HOUR,
                "díkes\n---------- Původní e-mail ----------\nOd: Rado H <freerado@gmail.com>\nKomu: strniskom@seznam.cz\nDatum: 11. 8. 2026 17:11:08\nPředmět: Navrh na vklad",
                "<k4@demo>", "<k3@demo>", account = 2),
            mail(marek, listOf(ME), "Exspirácia služieb", 2 * DAY, "Prosím o zaslanie faktúry za doménové služby.", account = 2),

            mail(anna, listOf(ME), "Víkend v Tatrách", 12 * DAY, "Ahoj! Ideme na víkend do Tatier, ideš s nami?", "<a1@demo>"),
            mail(ME, listOf(anna), "Re: Víkend v Tatrách", 12 * DAY - 40 * MIN, "Rád! Kedy vyrážame?", "<a2@demo>", "<a1@demo>"),
            mail(anna, listOf(ME), "Re: Víkend v Tatrách", 11 * DAY, "V piatok o 7:00.\n\nOn Tue, Anna wrote:\n> Rád! Kedy vyrážame?", "<a3@demo>", "<a2@demo>"),
            mail(anna, listOf(ME), "Faktúra 2026-114", 3 * HOUR, "Posielam faktúru, prosím o potvrdenie prijatia.", read = false,
                attachments = listOf(Attachment("faktura_2026-114.pdf", mimeType = "application/pdf", sizeBytes = 92_000))),
            mail(anna, listOf(ME), "Faktúra 2026-114", 2 * HOUR, "A ešte prílohu som zabudla pridať, pošlem ju zvlášť.", read = false),

            mail(peter, listOf(ME), "Review PR #42", 5 * HOUR, "Pozrel by si sa na môj pull request? Dík.", read = false, account = 3),
            mail(ME, listOf(peter), "Re: Review PR #42", 4 * HOUR + 30 * MIN, "Pozriem večer.", account = 3),

            mail(lucia, listOf(ME), "Kalendár na december", DAY + 6 * HOUR, "Pridala som termíny porád do spoločného kalendára.", account = 2),

            mail(team, listOf(ME, peter), "Štvrtkový míting", 6 * HOUR, "Míting presúvame na 10:30, zasadačka B.", read = false),
            mail(ME, listOf(team, peter), "Re: Štvrtkový míting", 5 * HOUR + 10 * MIN, "OK, budem tam."),

            mail(ME, listOf(ME), "Nákupný zoznam", 4 * DAY, "mlieko, chlieb, káva", account = 3),
        )
    }
}
