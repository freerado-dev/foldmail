package com.foldmail.app

import com.foldmail.domain.model.EmailMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A file attached to a message. [uri] is null for demo attachments that have no real content. */
data class Attachment(
    val name: String,
    val uri: String? = null,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long? = null,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isPdf: Boolean get() = mimeType == "application/pdf"
}

/** A mailbox account shown in the drawer. [colorArgb] is the accent bar colour. */
data class Account(val id: Long, val name: String, val email: String, val colorArgb: Long)

/** A message together with its body text (the domain model carries headers only). */
data class MailItem(val email: EmailMessage, val body: String, val attachments: List<Attachment> = emptyList())

/**
 * The seam where the real data layer (Room + Gmail/IMAP sync) plugs in later.
 * The UI only talks to this interface.
 */
interface MailRepository {
    val items: StateFlow<List<MailItem>>
    val accountAddress: String
    val accounts: List<Account>
    fun send(to: List<String>, subject: String, body: String, inReplyTo: String? = null, attachments: List<Attachment> = emptyList(), accountId: Long = 1)
    fun markRead(localIds: Collection<Long>)
}

/** In-memory implementation backed by [DemoData]; nothing is persisted or sent over the network. */
class InMemoryMailRepository(
    seed: List<MailItem> = DemoData.items(System.currentTimeMillis()),
    private val clock: () -> Long = System::currentTimeMillis,
) : MailRepository {

    override val accountAddress: String = DemoData.ME
    override val accounts: List<Account> = DemoData.accounts
    private val state = MutableStateFlow(seed)
    override val items: StateFlow<List<MailItem>> = state

    override fun send(to: List<String>, subject: String, body: String, inReplyTo: String?, attachments: List<Attachment>, accountId: Long) {
        state.update { list ->
            val id = (list.maxOfOrNull { it.email.localId } ?: 0L) + 1
            val parent = inReplyTo?.let { ref -> list.firstOrNull { it.email.messageId == ref }?.email }
            val mail = EmailMessage(
                localId = id,
                accountId = accountId,
                messageId = "<local-$id@foldmail.app>",
                inReplyTo = inReplyTo,
                references = parent?.let { it.references + listOfNotNull(it.messageId) } ?: emptyList(),
                subject = subject.ifBlank { "(bez predmetu)" },
                sentAtMillis = clock(),
                accountAddress = accountAddress,
                from = accountAddress,
                to = to,
                isRead = true,
            )
            list + MailItem(mail, body, attachments)
        }
    }

    override fun markRead(localIds: Collection<Long>) {
        if (localIds.isEmpty()) return
        state.update { list ->
            list.map { if (it.email.localId in localIds && !it.email.isRead) it.copy(email = it.email.copy(isRead = true)) else it }
        }
    }
}
