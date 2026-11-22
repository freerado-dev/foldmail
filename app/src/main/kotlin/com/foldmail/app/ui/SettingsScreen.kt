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
import com.foldmail.app.data.Signature
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings.get(context) }
    val dark by settings.dark.collectAsState()
    val notifications by settings.notifications.collectAsState()
    val sound by settings.sound.collectAsState()
    val sigOn by settings.signatureEnabled.collectAsState()
    val sigText by settings.signatureText.collectAsState()
    val sigReplies by settings.signatureOnReplies.collectAsState()
    val preview by settings.notificationPreview.collectAsState()
    val remoteImages by settings.remoteImages.collectAsState()
    val syncMinutes by settings.syncMinutes.collectAsState()
    var photoVersion by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val photoBitmap = remember(photoVersion) {
        Signature.photoFile(context).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (Signature.savePhoto(context, uri)) photoVersion++
            else android.widget.Toast.makeText(context, "Fotku sa nepodarilo načítať", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

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
            Header("Podpis")
            SwitchRow("Pridávať podpis k odoslaným emailom", null, sigOn) { settings.setSignatureEnabled(it) }
            if (sigOn) {
                androidx.compose.material3.OutlinedTextField(
                    sigText, { settings.setSignatureText(it) },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    label = { Text("Text podpisu") }, minLines = 3, maxLines = 8,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (photoBitmap != null) {
                        androidx.compose.foundation.Image(
                            photoBitmap.asImageBitmap(), "Fotka v podpise",
                            Modifier.height(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    androidx.compose.material3.OutlinedButton(onClick = { pickPhoto.launch("image/*") }) {
                        Text(if (photoBitmap == null) "Pridať fotku" else "Zmeniť fotku")
                    }
                    if (photoBitmap != null) {
                        androidx.compose.material3.TextButton(onClick = { Signature.removePhoto(context); photoVersion++ }) { Text("Odstrániť") }
                    }
                }
                SwitchRow("Podpis aj v odpovediach", "Vypni, ak chceš podpis len v nových emailoch.", sigReplies) { settings.setSignatureOnReplies(it) }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            Header("Čítanie emailov")
            SwitchRow("Načítavať obrázky z internetu", "Vypnutím sa skryjú aj sledovacie pixely; obrázky v prílohách ostanú.", remoteImages) { settings.setRemoteImages(it) }

            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            Header("Kontrola pošty na pozadí")
            listOf(15, 30, 60, 120).forEach { m ->
                ChoiceRow(if (m < 60) "Každých $m minút" else if (m == 60) "Každú hodinu" else "Každé 2 hodiny", selected = syncMinutes == m) {
                    settings.setSyncMinutes(m)
                    if (notifications) MailSyncWorker.schedule(context, true, m)
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            Header("Upozornenia")
            SwitchRow("Ukázať náhľad správy v upozornení", "Vypnuté: upozornenie len oznámi nový email, bez odosielateľa a textu na zamknutej obrazovke.", preview) { settings.setNotificationPreview(it) }
            Row(
                Modifier.fillMaxWidth().clickable { setNotifications(!notifications) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Upozorniť na nový email")
                    Text(
                        "Pošta sa kontroluje na pozadí v intervale podľa nastavenia vyššie.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = notifications, onCheckedChange = { setNotifications(it) })
            }

            Row(
                Modifier.fillMaxWidth().clickable {
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + context.packageName),
                            ),
                        )
                    } catch (_: Exception) {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Povoliť prácu na pozadí")
                    Text(
                        "Bez toho Samsung môže kontrolu pošty pozdržať, keď je apka zatvorená. V nastaveniach batérie ju tiež nedávaj medzi „spiace aplikácie“.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
