package com.foldmail.app

import com.foldmail.domain.model.EmailMessage
import com.foldmail.domain.model.normalizeAddress
import com.foldmail.domain.threading.PersonConversation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

fun PersonConversation.title(names: Map<String, String>, selfAddress: String): String {
    val n = participants.map { nameOf(it, names) }
    return if (participants == setOf(normalizeAddress(selfAddress))) "Poznámky pre seba" else n.joinToString(", ")
}

fun initialOf(title: String): String = title.trim().firstOrNull()?.uppercase() ?: "?"

fun previewOf(body: String): String =
    body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith(">") } ?: ""

/** "14:05" for today, "včera" for yesterday, otherwise "3. 11.". */
fun formatTimestamp(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        date == today -> DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(millis))
        date == today.minusDays(1) -> "včera"
        else -> "${date.dayOfMonth}. ${date.monthValue}."
    }
}
