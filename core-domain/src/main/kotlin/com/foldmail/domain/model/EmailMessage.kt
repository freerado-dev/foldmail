package com.foldmail.domain.model

/**
 * Provider-independent view of a message. [messageId], [inReplyTo] and [references]
 * hold ids with or without angle brackets. Addresses may be "Name <a@b.c>" or bare.
 */
data class EmailMessage(
    val localId: Long,
    val accountId: Long,
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val subject: String,
    val sentAtMillis: Long,
    val accountAddress: String = "",
    val from: String = "",
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val isRead: Boolean = true,
) {
    val isOutgoing: Boolean
        get() = accountAddress.isNotBlank() && normalizeAddress(from) == normalizeAddress(accountAddress)
}

/** "Rado H <FreeRado@Gmail.com>" -> "freerado@gmail.com". */
fun normalizeAddress(raw: String): String {
    val inner = raw.substringAfterLast('<', raw).substringBefore('>')
    return inner.trim().lowercase()
}
