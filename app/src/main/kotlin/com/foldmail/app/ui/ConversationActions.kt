package com.foldmail.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foldmail.app.Folder
import com.foldmail.app.MailUiState
import com.foldmail.app.snoozeOptions
import com.foldmail.domain.threading.PersonConversation
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

/** Everything a chat row can do; wired to the view model in [FoldMailApp]. */
class RowActions(
    val togglePin: (String) -> Unit,
    val toggleStar: (String) -> Unit,
    val toggleMute: (String) -> Unit,
    val togglePriority: (String) -> Unit,
    val toggleAutoArchive: (String) -> Unit,
    val block: (String) -> Unit,
    val snooze: (String, Long) -> Unit,
    val moveTo: (String, Folder) -> Unit,
    val trashMessage: (Long) -> Unit = {},
)

// --------------------------------------------------------------------------------------------
// Swipe-left reveal
// --------------------------------------------------------------------------------------------

/**
 * Row that slides left to reveal [actions] behind it. A right-swipe on a closed row calls
 * [onSwipeRight] (used to open the accounts drawer), because this row consumes horizontal drags.
 */
@Composable
fun SwipeRevealRow(
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    actionsWidth: Dp,
    onSwipeRight: () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { actionsWidth.toPx() }
    val rightThresholdPx = with(density) { 60.dp.toPx() }
    var x by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var total by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(revealed, widthPx) {
        if (!dragging) animate(x, if (revealed) -widthPx else 0f) { v, _ -> x = v }
    }
    val dragState = rememberDraggableState { dx ->
        total += dx
        x = (x + dx).coerceIn(-widthPx, 0f)
    }

    Box(modifier.clipToBounds()) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) { actions() }
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { dragging = true; total = 0f },
                    onDragStopped = { velocity ->
                        dragging = false
                        if (!revealed && x == 0f && total > rightThresholdPx) {
                            onSwipeRight()
                        } else {
                            val open = x < -widthPx / 2f || (velocity < -1500f && x < 0f)
                            onRevealedChange(open)
                            animate(x, if (open) -widthPx else 0f) { v, _ -> x = v }
                        }
                    },
                ),
        ) { content() }
    }
}

/** Opens the drawer on a right swipe anywhere a child did not already consume the drag. */
fun Modifier.openOnRightSwipe(thresholdDp: Float = 70f, onOpen: () -> Unit): Modifier = this.pointerInput(Unit) {
    val threshold = thresholdDp * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var total = 0f
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if (over > 0f) { change.consume(); total += over }
        }
        if (drag != null) {
            horizontalDrag(drag.id) { change -> total += change.positionChange().x; change.consume() }
            if (total > threshold) onOpen()
        }
    }
}

@Composable
fun SwipeAction(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(color).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

val MoreColor = Color(0xFFFF8A00)
val TrashColor = Color(0xFFFF0000)
val ArchiveColor = Color(0xFF2EC02E)

/** The three buttons Spike shows after a left swipe: More · Trash · Archive. */
@Composable
fun SwipeActionButtons(onMore: () -> Unit, onTrash: () -> Unit, onArchive: () -> Unit) {
    SwipeAction("Viac", Icons.Filled.MoreHoriz, MoreColor, onMore)
    SwipeAction("Kôš", Icons.Filled.Delete, TrashColor, onTrash)
    SwipeAction("Archív", Icons.Filled.Archive, ArchiveColor, onArchive)
}

// --------------------------------------------------------------------------------------------
// "More" menu
// --------------------------------------------------------------------------------------------

@Composable
fun MoreMenuDialog(
    conversation: PersonConversation,
    ui: MailUiState,
    actions: RowActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val key = conversation.key
    val flags = ui.flags
    var picking by remember { mutableStateOf(false) }
    val address = conversation.participants.firstOrNull().orEmpty()

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dial(context, address)
    }

    fun done(block: () -> Unit) { block(); onDismiss() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            if (picking) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("Presunúť do", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
                    MenuRow(Icons.Filled.Inbox, "Doručené") { done { actions.moveTo(key, Folder.Inbox) } }
                    MenuRow(Icons.Filled.Archive, "Archív") { done { actions.moveTo(key, Folder.Archive) } }
                    MenuRow(Icons.Filled.Delete, "Kôš") { done { actions.moveTo(key, Folder.Trash) } }
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    val options = remember { snoozeOptions(System.currentTimeMillis()) }
                    MenuRow(Icons.Filled.Timer, options[0].label, options[0].detail) { done { actions.snooze(key, options[0].atMillis) } }
                    MenuRow(Icons.Filled.Schedule, options[1].label, options[1].detail) { done { actions.snooze(key, options[1].atMillis) } }
                    MenuRow(Icons.Filled.Today, options[2].label, options[2].detail) { done { actions.snooze(key, options[2].atMillis) } }
                    MenuRow(Icons.Filled.CalendarMonth, "Vybrať čas…") {
                        pickDateTime(context) { millis -> actions.snooze(key, millis); onDismiss() }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Folder, "Presunúť do…") { picking = true }
                    MenuRow(Icons.Filled.PushPin, if (key in flags.pinned) "Odopnúť" else "Pripnúť") { done { actions.togglePin(key) } }
                    MenuRow(Icons.Filled.PriorityHigh, if (key in flags.priority) "Odstrániť z prioritných" else "Presunúť do prioritných") { done { actions.togglePriority(key) } }
                    MenuRow(Icons.Filled.NotificationsOff, if (key in flags.muted) "Zrušiť stlmenie upozornení" else "Stlmiť upozornenia") { done { actions.toggleMute(key) } }
                    MenuRow(Icons.Filled.Archive, if (key in flags.autoArchive) "Vypnúť automatický archív" else "Automaticky archivovať") { done { actions.toggleAutoArchive(key) } }
                    MenuRow(Icons.Filled.Block, "Zablokovať") { done { actions.block(key) } }
                    if (!conversation.isGroup && address.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Zavolať teraz", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            RoundButton(Icons.Filled.Videocam, "Videohovor") {
                                openUrl(context, "https://meet.google.com/new"); onDismiss()
                            }
                            Box(Modifier.width(10.dp))
                            RoundButton(Icons.Filled.Call, "Telefonát") {
                                if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                    dial(context, address); onDismiss()
                                } else {
                                    contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(20.dp), tint = Color.White) }
}

private fun pickDateTime(context: Context, onPicked: (Long) -> Unit) {
    val now = Calendar.getInstance()
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            val c = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }
            onPicked(c.timeInMillis)
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
    }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/** Looks the e-mail address up in the phone's contacts and opens the dialer. */
private fun dial(context: Context, email: String) {
    val phone = try { findPhone(context, email) } catch (_: SecurityException) { null }
    if (phone == null) Toast.makeText(context, "Telefónne číslo kontaktu sa nenašlo", Toast.LENGTH_SHORT).show()
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (phone ?: ""))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

private fun findPhone(context: Context, email: String): String? {
    val cr = context.contentResolver
    val id = cr.query(
        Email.CONTENT_URI, arrayOf(Email.CONTACT_ID), "${Email.ADDRESS} = ?", arrayOf(email), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
    return cr.query(
        Phone.CONTENT_URI, arrayOf(Phone.NUMBER), "${Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
}
