package com.foldmail.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foldmail.app.Attachment
import com.foldmail.app.MailUiState
import com.foldmail.app.nameOf
import com.foldmail.domain.model.normalizeAddress
import com.foldmail.domain.threading.PersonConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId

enum class SearchScope(val label: String) { All("Všetko"), From("Od"), To("Komu"), Subject("Predmet") }
enum class SearchTime(val label: String, val days: Int) { Today("Dnes", 0), Week("7 dní", 7), Month("30 dní", 30) }

/** Search state lives above the list so it survives opening a result and coming back. */
class SearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var scope by mutableStateOf(SearchScope.All)
    var time by mutableStateOf<SearchTime?>(null)
    var onlyAttachments by mutableStateOf(false)
    var filesExpanded by mutableStateOf(false)
}

private class SearchResult(val conversations: List<PersonConversation>, val files: List<Pair<Long, Attachment>>)

private fun runSearch(ui: MailUiState, s: SearchState): SearchResult {
    val q = s.query.trim().lowercase()
    if (q.isEmpty() && !s.onlyAttachments && s.time == null) return SearchResult(emptyList(), emptyList())
    val since = s.time?.let { t ->
        val today = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        today - t.days * 24L * 3600 * 1000
    }
    fun matches(m: com.foldmail.domain.model.EmailMessage): Boolean {
        if (since != null && m.sentAtMillis < since) return false
        val atts = ui.attachments[m.localId].orEmpty()
        if (s.onlyAttachments && atts.isEmpty()) return false
        if (q.isEmpty()) return true
        val from = m.from.lowercase() + " " + nameOf(normalizeAddress(m.from), ui.names).lowercase()
        val to = (m.to + m.cc).joinToString(" ").lowercase()
        return when (s.scope) {
            SearchScope.From -> q in from
            SearchScope.To -> q in to
            SearchScope.Subject -> q in m.subject.lowercase()
            SearchScope.All -> q in from || q in to || q in m.subject.lowercase() ||
                atts.any { q in it.name.lowercase() } || q in ui.bodies[m.localId].orEmpty().lowercase()
        }
    }
    val hits = ui.conversations.filter { c -> c.messages.any(::matches) }
    val files = ArrayList<Pair<Long, Attachment>>()
    if (q.isNotEmpty() || s.onlyAttachments) {
        for (c in hits) for (m in c.messages.asReversed()) {
            if (since != null && m.sentAtMillis < since) continue
            for (a in ui.attachments[m.localId].orEmpty()) {
                if (q.isEmpty() || q in a.name.lowercase()) files += m.localId to a
            }
        }
    }
    return SearchResult(hits.sortedByDescending { it.last.sentAtMillis }, files.take(60))
}

/** Full-screen search over the list: filters, matching files with thumbnails first, then the chats. */
@Composable
fun SearchPane(ui: MailUiState, state: SearchState, actions: RowActions, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.query.isEmpty()) try { focus.requestFocus() } catch (_: Exception) {} }
    var result by remember { mutableStateOf(SearchResult(emptyList(), emptyList())) }
    LaunchedEffect(state.query, state.scope, state.time, state.onlyAttachments, ui.conversations, ui.attachments) {
        delay(150)
        result = withContext(Dispatchers.Default) { runSearch(ui, state) }
    }

    Column(modifier.safeTop()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.open = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť") }
            BasicTextField(
                value = state.query, onValueChange = { state.query = it },
                modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (state.query.isEmpty()) Text("Hľadať", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                },
            )
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { state.query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Vymazať") }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchScope.entries.forEach { sc ->
                FilterChip(selected = state.scope == sc, onClick = { state.scope = sc }, label = { Text(sc.label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchTime.entries.forEach { t ->
                FilterChip(selected = state.time == t, onClick = { state.time = if (state.time == t) null else t }, label = { Text(t.label) })
            }
            FilterChip(selected = state.onlyAttachments, onClick = { state.onlyAttachments = !state.onlyAttachments }, label = { Text("Má prílohy") })
        }
        HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

        val searching = state.query.isNotBlank() || state.onlyAttachments || state.time != null
        if (searching && result.conversations.isEmpty() && result.files.isEmpty()) {
            Text("Nič sa nenašlo", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (result.files.isNotEmpty()) {
                item(key = "hdr-files") {
                    Text("Súbory", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.SemiBold)
                }
                val shown = if (state.filesExpanded) result.files else result.files.take(2)
                items(shown, key = { (id, a) -> "f-$id-${a.name}-${a.remoteId}" }) { (id, a) -> AttachmentRow(a, id) }
                if (result.files.size > 2) {
                    item(key = "files-more") {
                        Text(
                            if (state.filesExpanded) "ZOBRAZIŤ MENEJ" else "ZOBRAZIŤ VIAC (${result.files.size})",
                            Modifier.clickable { state.filesExpanded = !state.filesExpanded }.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(result.conversations, key = { "c-" + it.key }) { c ->
                ConversationRow(c, ui, false, false, {}, onOpen, actions, {}) {}
            }
        }
    }
}
