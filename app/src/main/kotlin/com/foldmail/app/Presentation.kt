package com.foldmail.app

import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.model.normalizeAddress
import com.foldmail.domain.threading.PersonConversation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

fun PersonConversation.isSelfNote(selfAddress: String): Boolean =
    participants == setOf(normalizeAddress(selfAddress))

fun PersonConversation.title(names: Map<String, String>, selfAddress: String): String =
    if (isSelfNote(selfAddress)) "Poznámky pre seba" else participants.joinToString(", ") { nameOf(it, names) }

fun initialOf(title: String): String = title.trim().firstOrNull()?.uppercase() ?: "?"

fun previewOf(body: String): String =
    body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith(">") } ?: ""

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
