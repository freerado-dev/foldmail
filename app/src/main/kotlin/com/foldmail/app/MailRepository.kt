package com.foldmail.app

import com.foldmail.domain.model.EmailMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A message together with its body text (the domain model carries headers only). */
data class MailItem(val email: EmailMessage, val body: String)

/**
 * The seam where the real data layer (Room + Gmail/IMAP sync) plugs in later.
 * The UI only talks to this interface.
 */
interface MailRepository {
    val items: StateFlow<List<MailItem>>
    val accountAddress: String
    fun send(to: List<String>, subject: String, body: String, inReplyTo: String? = null)
    fun markRead(localIds: Collection<Long>)
}

/** In-memory implementation backed by [DemoData]; nothing is persisted or sent over the network. */
class InMemoryMailRepository(
    seed: List<MailItem> = DemoData.items(System.currentTimeMillis()),
    private val clock: () -> Long = System::currentTimeMillis,
) : MailRepository {

    override val accountAddress: String = DemoData.ME
    private val state = MutableStateFlow(seed)
    override val items: StateFlow<List<MailItem>> = state

    override fun send(to: List<String>, subject: String, body: String, inReplyTo: String?) {
        state.update { list ->
            val id = (list.maxOfOrNull { it.email.localId } ?: 0L) + 1
            val parent = inReplyTo?.let { ref -> list.firstOrNull { it.email.messageId == ref }?.email }
            val mail = EmailMessage(
                localId = id,
                accountId = 1,
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
            list + MailItem(mail, body)
        }
    }

    override fun markRead(localIds: Collection<Long>) {
        if (localIds.isEmpty()) return
        state.update { list ->
            list.map { if (it.email.localId in localIds && !it.email.isRead) it.copy(email = it.email.copy(isRead = true)) else it }
        }
    }
}
