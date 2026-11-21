package com.foldmail.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foldmail.app.data.AppSettings
import com.foldmail.app.data.MailSyncWorker
import com.foldmail.app.data.NotificationSounds
import com.foldmail.app.data.Notifier

@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings.get(context) }
    val dark by settings.dark.collectAsState()
    val notifications by settings.notifications.collectAsState()
    val sound by settings.sound.collectAsState()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun setNotifications(on: Boolean) {
        settings.setNotifications(on)
        MailSyncWorker.schedule(context, on)
        if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.hasPermission(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(modifier.background(MaterialTheme.colorScheme.background).safeTop()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť") }
            Text("Nastavenia", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Header("Vzhľad")
            ChoiceRow("Svetlá téma", selected = !dark) { settings.setDark(false) }
            ChoiceRow("Tmavá téma", selected = dark) { settings.setDark(true) }

            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            Header("Upozornenia")
            Row(
                Modifier.fillMaxWidth().clickable { setNotifications(!notifications) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Upozorniť na nový email")
                    Text(
                        "Pošta sa kontroluje na pozadí zhruba každých 15 minút.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = notifications, onCheckedChange = { setNotifications(it) })
            }

            Header("Zvuk upozornenia")
            NotificationSounds.forEach { s ->
                ChoiceRow(s.label, selected = s.key == sound) {
                    settings.setSound(s.key)
                    Notifier.preview(context, s)
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 4.dp))
    }
}
