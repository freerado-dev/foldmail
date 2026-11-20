package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foldmail.app.MailUiState
import com.foldmail.app.formatTimestamp
import com.foldmail.app.previewOf
import com.foldmail.app.title

/** Spike-style list: one row per person (or group), newest first. */
@Composable
fun ConversationListPane(
    ui: MailUiState,
    selectedKey: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.statusBarsPadding()) {
        Text(
            "Chaty",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.conversations, key = { it.key }) { c ->
                val title = c.title(ui.names, ui.selfAddress)
                val selected = c.key == selectedKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                        .clickable { onOpen(c.key) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Avatar(title)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (c.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                formatTimestamp(c.last.sentAtMillis),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val prefix = if (c.last.isOutgoing) "Ty: " else ""
                            Text(
                                prefix + previewOf(ui.bodies[c.last.localId].orEmpty()).ifEmpty { c.last.subject },
                                Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (c.unreadCount > 0) {
                                Box(
                                    Modifier.padding(start = 8.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        c.unreadCount.toString(),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 82.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}
