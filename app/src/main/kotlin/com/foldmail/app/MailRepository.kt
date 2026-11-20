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
    /** Gmail attachment id: the file is downloaded on demand when [uri] is null. */
    val remoteId: String? = null,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isPdf: Boolean get() = mimeType == "application/pdf"
}

/** A mailbox account shown in the drawer. [colorArgb] is the accent bar colour. */
data class Account(val id: Long, val name: String, val email: String, val colorArgb: Long)

/** A message together with its body text (the domain model carries headers only). */
data class MailItem(
    val email: EmailMessage,
    val body: String,
    val attachments: List<Attachment> = emptyList(),
    /** Original HTML of the mail (null for plain-text mails), shown in the full message viewer. */
    val html: String? = null,
    /** Images referenced from the HTML as cid:... */
    val inline: List<InlinePart> = emptyList(),
)

/** [data] is standard base64 when the image is embedded in the message, else [attachmentId] must be downloaded. */
data class InlinePart(val cid: String, val mime: String, val data: String?, val attachmentId: String?)

/** Target of "Move to" / Trash / Archive. */
enum class Folder { Inbox, Archive, Trash }

/** Progress/error of background synchronisation, shown as a thin bar and a message in the list. */
data class SyncStatus(val syncing: Boolean = false, val error: String? = null)

/**
 * The seam where the data layer plugs in. The UI only talks to this interface.
 * Implementations: [InMemoryMailRepository] (demo) and the Gmail-backed one in `data/`.
 */
interface MailRepository {
    val items: StateFlow<List<MailItem>>
    val accounts: StateFlow<List<Account>>
    val status: StateFlow<SyncStatus>
    val accountAddress: String
    fun send(to: List<String>, subject: String, body: String, inReplyTo: String? = null, attachments: List<Attachment> = emptyList(), accountId: Long = 1)
    fun markRead(localIds: Collection<Long>)
    /** Trash removes the messages for real (on the Gmail server too); other folders are local only. */
    fun moveMessages(localIds: Collection<Long>, folder: Folder)
    /** Downloads a Gmail attachment to the cache; null when it is not downloadable. */
    suspend fun fetchAttachment(localId: Long, attachment: Attachment): Attachment? = null
    /** HTML of the message with cid: images replaced by real data. */
    suspend fun resolveHtml(localId: Long): String? = null
    /** Stars/unstars the messages on the server (no-op for the demo). */
    fun setStarred(localIds: Collection<Long>, starred: Boolean) {}
    /** Re-downloads mail for all real accounts (no-op for the demo). */
    fun refresh()
    /** Stores a Google account authorised with [accessToken] and starts syncing it. */
    suspend fun addGoogleAccount(accessToken: String)
}

/** In-memory implementation backed by [DemoData]; nothing is persisted or sent over the network. */
class InMemoryMailRepository(
    seed: List<MailItem> = DemoData.items(System.currentTimeMillis()),
    private val clock: () -> Long = System::currentTimeMillis,
) : MailRepository {

    override val accountAddress: String = DemoData.ME
    override val accounts: StateFlow<List<Account>> = MutableStateFlow(DemoData.accounts)
    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus())
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

    override fun refresh() = Unit

    override fun moveMessages(localIds: Collection<Long>, folder: Folder) {
        if (folder != Folder.Trash || localIds.isEmpty()) return
        state.update { list -> list.filter { it.email.localId !in localIds } }
    }

    override suspend fun addGoogleAccount(accessToken: String) {
        throw UnsupportedOperationException("Demo repozitár nepodporuje pridanie účtu")
    }

    override fun markRead(localIds: Collection<Long>) {
        if (localIds.isEmpty()) return
        state.update { list ->
            list.map { if (it.email.localId in localIds && !it.email.isRead) it.copy(email = it.email.copy(isRead = true)) else it }
        }
    }
}
