package com.foldmail.app

import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.model.normalizeAddress
import com.foldmail.domain.threading.PersonConversation
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val Sk = Locale("sk", "SK")

/** normalised address -> display name, harvested from "Name <addr>" headers of all messages. */
fun nameDirectory(emails: List<EmailMessage>): Map<String, String> {
    val out = HashMap<String, String>()
    for (m in emails) {
        for (raw in listOf(m.from) + m.to + m.cc) {
            if ('<' !in raw) continue
            val name = raw.substringBefore('<').trim().trim('"')
            if (name.isNotEmpty()) out.putIfAbsent(normalizeAddress(raw), name)
        }
    }
    return out
}

fun nameOf(address: String, names: Map<String, String>): String =
    names[address] ?: address.substringBefore('@')

/** A chat with yourself, judged by the account the messages belong to (works with several accounts). */
fun PersonConversation.isSelfNote(selfAddress: String = ""): Boolean {
    val own = messages.firstOrNull()?.accountAddress?.takeIf { it.isNotBlank() } ?: selfAddress
    return participants == setOf(normalizeAddress(own))
}

fun PersonConversation.title(names: Map<String, String>, selfAddress: String): String =
    if (isSelfNote(selfAddress)) "Poznámky pre seba" else participants.joinToString(", ") { nameOf(it, names) }

fun initialOf(title: String): String = title.trim().firstOrNull()?.uppercase() ?: "?"

/** Body text without quoted lines, flattened to one string so the list can wrap it over two lines. */
fun previewOf(body: String): String =
    body.take(3000).lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(">") }
        .joinToString(" ").take(240)

private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** List timestamp: "14:05" today, short weekday within the last week, otherwise "3. 11.". */
fun formatTimestamp(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val date = day(millis, zone)
    val today = day(now, zone)
    return when {
        date == today -> DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(millis))
        !date.isBefore(today.minusDays(6)) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Sk)
        else -> "${date.dayOfMonth}. ${date.monthValue}."
    }
}

/** Separator inside a chat: "10. 7., 13:58". */
fun formatChatStamp(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    return "${dt.dayOfMonth}. ${dt.monthValue}., " + DateTimeFormatter.ofPattern("HH:mm").format(dt)
}

/** Section heading of the chat list. */
fun sectionOf(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val date = day(millis, zone)
    val today = day(now, zone)
    return when {
        date == today -> "Dnes"
        date == today.minusDays(1) -> "Včera"
        !date.isBefore(today.minusDays(6)) -> "Tento týždeň"
        else -> "Staršie"
    }
}

fun formatSize(bytes: Long?): String = when {
    bytes == null -> ""
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} kB"
    else -> "$bytes B"
}

/** "Re: " / "Fwd: " prefix that is not added twice. */
fun prefixSubject(subject: String, prefix: String): String {
    val s = subject.trim()
    return if (s.lowercase().startsWith(prefix.trim().lowercase())) s else prefix + s
}

/** Recipients for a reply (sender) or reply-all (sender + everybody else), never including yourself. */
fun replyRecipients(m: EmailMessage, replyAll: Boolean): List<String> {
    val own = normalizeAddress(m.accountAddress)
    fun norm(l: List<String>) = l.map(::normalizeAddress).filter { it.isNotEmpty() }
    val base = if (m.isOutgoing) norm(m.to) else listOf(normalizeAddress(m.from))
    val extra = if (replyAll) norm(m.to) + norm(m.cc) else emptyList()
    val all = (base + extra).distinct()
    val others = all.filter { it != own }
    return if (others.isEmpty()) all else others
}

fun quoteForReply(m: EmailMessage, body: String, names: Map<String, String>): String {
    val who = nameOf(normalizeAddress(m.from), names)
    val quoted = body.trim().lines().joinToString("\n") { "> $it" }
    return "\n\nDňa ${formatChatStamp(m.sentAtMillis)}, $who napísal(a):\n$quoted"
}

fun forwardBody(m: EmailMessage, body: String): String =
    "\n\n---------- Preposlaná správa ----------\nOd: ${m.from}\nDátum: ${formatChatStamp(m.sentAtMillis)}\n" +
        "Predmet: ${m.subject}\nKomu: ${m.to.joinToString(", ")}\n\n${body.trim()}"

/** Full HTML document for the message viewer (readable on a phone, images fit the width). */
fun wrapHtml(html: String?, plain: String): String {
    val inner = html?.takeIf { it.isNotBlank() } ?: run {
        val esc = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        "<pre style=\"white-space:pre-wrap;font-family:sans-serif;margin:0\">$esc</pre>"
    }
    return "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>body{margin:12px;word-wrap:break-word;font-family:sans-serif;font-size:15px;color:#1d1d1f;background:#fff}" +
        "img{max-width:100%;height:auto}table{max-width:100%}</style></head><body>$inner</body></html>"
}

/** One entry of the "later" part of the More menu. */
data class SnoozeOption(val label: String, val detail: String, val atMillis: Long)

/** Later today (+3 h, at most 23:59), tomorrow 9:00 and next Monday 9:00. */
fun snoozeOptions(now: Long, zone: ZoneId = ZoneId.systemDefault()): List<SnoozeOption> {
    val dt = Instant.ofEpochMilli(now).atZone(zone)
    var later = dt.plusHours(3).withSecond(0).withNano(0)
    if (later.toLocalDate() != dt.toLocalDate()) later = dt.withHour(23).withMinute(59).withSecond(0).withNano(0)
    val tomorrow = dt.toLocalDate().plusDays(1).atTime(9, 0).atZone(zone)
    val monday = dt.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0).atZone(zone)
    fun day(z: java.time.ZonedDateTime) = z.dayOfWeek.getDisplayName(TextStyle.FULL, Sk)
    return listOf(
        SnoozeOption("Neskôr dnes", DateTimeFormatter.ofPattern("HH:mm").format(later), later.toInstant().toEpochMilli()),
        SnoozeOption("Zajtra", "${day(tomorrow)}, 9:00", tomorrow.toInstant().toEpochMilli()),
        SnoozeOption("Budúci týždeň", "${day(monday)}, 9:00", monday.toInstant().toEpochMilli()),
    )
}
