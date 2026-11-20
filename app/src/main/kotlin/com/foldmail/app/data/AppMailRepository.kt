package com.foldmail.app.data

import android.content.Context
import com.foldmail.app.Account
import com.foldmail.app.Attachment
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

/** Shows the demo mailbox until the first real Google account is added, then only real mail. */
class AppMailRepository(context: Context) : MailRepository {
    private val demo = InMemoryMailRepository()
    private val gmail = GmailRepository(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val real get() = gmail.accounts.value.isNotEmpty()

    override val items: StateFlow<List<MailItem>> = combine(gmail.accounts, gmail.items, demo.items) { g, gi, di ->
        if (g.isEmpty()) di else gi
    }.stateIn(scope, SharingStarted.Eagerly, if (gmail.accounts.value.isEmpty()) demo.items.value else gmail.items.value)

    override val accounts: StateFlow<List<Account>> = combine(gmail.accounts, demo.accounts) { g, d ->
        if (g.isEmpty()) d else g
    }.stateIn(scope, SharingStarted.Eagerly, if (gmail.accounts.value.isEmpty()) demo.accounts.value else gmail.accounts.value)

    override val status: StateFlow<SyncStatus> = gmail.status

    override val accountAddress: String get() = gmail.accounts.value.firstOrNull()?.email ?: demo.accountAddress

    override fun send(to: List<String>, subject: String, body: String, inReplyTo: String?, attachments: List<Attachment>, accountId: Long) {
        if (real) gmail.send(to, subject, body, inReplyTo, attachments, accountId)
        else demo.send(to, subject, body, inReplyTo, attachments, accountId)
    }

    override fun markRead(localIds: Collection<Long>) {
        if (real) gmail.markRead(localIds) else demo.markRead(localIds)
    }

    override fun refresh() = gmail.refresh()

    override suspend fun addGoogleAccount(accessToken: String) = gmail.addAccount(accessToken)
}
