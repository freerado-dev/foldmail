package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.Account
import com.foldmail.app.Attachment
import com.foldmail.app.ImapConfig
import com.foldmail.app.InMemoryMailRepository
import com.foldmail.app.MailItem
import com.foldmail.app.MailRepository
import com.foldmail.app.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Shows the demo mailbox until the first real account is added, then only real mail (Gmail + IMAP/POP). */
class AppMailRepository(context: Context) : MailRepository {
    private val appContext = context.applicationContext
    private val demo = InMemoryMailRepository()
    private val gmail = GmailRepository.get(context.applicationContext)
    private val imap = ImapRepository.get(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val real get() = gmail.accounts.value.isNotEmpty() || imap.accounts.value.isNotEmpty()

    override val items: StateFlow<List<MailItem>> =
        combine(gmail.accounts, imap.accounts, gmail.items, imap.items, demo.items) { g, i, gi, ii, di ->
            if (g.isEmpty() && i.isEmpty()) di else if (ii.isEmpty()) gi else if (gi.isEmpty()) ii else gi + ii
        }.stateIn(scope, SharingStarted.Eagerly, if (!real) demo.items.value else gmail.items.value + imap.items.value)

    override val accounts: StateFlow<List<Account>> =
        combine(gmail.accounts, imap.accounts, demo.accounts) { g, i, d -> if (g.isEmpty() && i.isEmpty()) d else g + i }
            .stateIn(scope, SharingStarted.Eagerly, if (!real) demo.accounts.value else gmail.accounts.value + imap.accounts.value)

    override val status: StateFlow<SyncStatus> = combine(gmail.status, imap.status) { a, b ->
        SyncStatus(a.syncing || b.syncing, a.error ?: b.error, a.progress ?: b.progress)
    }.stateIn(scope, SharingStarted.Eagerly, SyncStatus())

    override val accountAddress: String
        get() = gmail.accounts.value.firstOrNull()?.email ?: imap.accounts.value.firstOrNull()?.email ?: demo.accountAddress

    override fun removeAccount(accountId: Long) {
        if (isImap(accountId)) imap.removeAccount(accountId) else gmail.removeAccount(accountId)
    }

    private fun isImap(accountId: Long) = imap.accounts.value.any { it.id == accountId }

    override fun send(to: List<String>, subject: String, body: String, inReplyTo: String?, attachments: List<Attachment>, accountId: Long, cc: List<String>, bcc: List<String>) {
        val signed = if (real) Signature.apply(appContext, accountId, body, isReply = inReplyTo != null) else body
        when {
            !real -> demo.send(to, subject, body, inReplyTo, attachments, accountId, cc, bcc)
            isImap(accountId) -> imap.send(to, subject, signed, inReplyTo, attachments, accountId, cc, bcc)
            else -> gmail.send(to, subject, signed, inReplyTo, attachments, accountId, cc, bcc)
        }
    }

    override fun markRead(localIds: Collection<Long>) {
        if (!real) { demo.markRead(localIds); return }
        gmail.markRead(localIds.filter { !imap.owns(it) })
        imap.markRead(localIds.filter { imap.owns(it) })
    }

    override fun moveMessages(localIds: Collection<Long>, folder: com.foldmail.app.Folder) {
        if (!real) { demo.moveMessages(localIds, folder); return }
        gmail.moveMessages(localIds.filter { !imap.owns(it) }, folder)
        imap.moveMessages(localIds.filter { imap.owns(it) }, folder)
    }

    override suspend fun fetchAttachment(localId: Long, attachment: Attachment) =
        if (imap.owns(localId)) imap.fetchAttachment(localId, attachment) else if (real) gmail.fetchAttachment(localId, attachment) else null

    override suspend fun resolveHtml(localId: Long) =
        if (imap.owns(localId)) imap.resolveHtml(localId) else if (real) gmail.resolveHtml(localId) else null

    override fun setStarred(localIds: Collection<Long>, starred: Boolean) {
        if (real) gmail.setStarred(localIds.filter { !imap.owns(it) }, starred)
    }

    override fun refresh() { gmail.refresh(); imap.refresh() }

    override suspend fun addGoogleAccount(accessToken: String) = gmail.addAccount(accessToken)

    override suspend fun addImapAccount(config: ImapConfig) = imap.addAccount(config)
}
