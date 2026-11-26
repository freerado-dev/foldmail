package com.foldmail.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as UnitColorProvider
import com.foldmail.app.MainActivity
import com.foldmail.app.R
import com.foldmail.app.data.AvatarImages
import com.foldmail.app.data.WidgetData
import com.foldmail.app.formatTimestamp

// Light and dark palettes; the widget follows the system theme.
private val bg = ColorProvider(day = Color(0xFFF4F6FA), night = Color(0xFF1E2330))
private val card = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF2A3042))
private val text = ColorProvider(day = Color(0xFF1B1F27), night = Color(0xFFE9ECF2))
private val sub = ColorProvider(day = Color(0xFF626B7A), night = Color(0xFFA3ABBB))
private val accent = ColorProvider(day = Color(0xFF2F6FED), night = Color(0xFF8AB4FF))
private val onAccent = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF10234A))
private val avatarColors = listOf(0xFF3F7CE0, 0xFFE0566B, 0xFF4FA36B, 0xFFE0953F, 0xFF8B5FCF, 0xFF2FA8B8)

private fun openIntent(context: Context, key: String?, compose: Boolean = false): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setData(Uri.parse("foldmail://widget/" + (if (compose) "compose" else if (key != null) "chat/" + Uri.encode(key) else "list")))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .apply {
            if (compose) putExtra("fm_compose", true)
            if (key != null) putExtra("fm_open", key)
        }

abstract class BaseWidget(private val kind: Kind) : GlanceAppWidget() {
    enum class Kind { Small, Strip, Medium, Large }

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val data = WidgetData.read(context)
            when (kind) {
                Kind.Small -> SmallLayout(context, data)
                Kind.Strip -> StripLayout(context, data)
                Kind.Medium -> ListLayout(context, data, rows = 3)
                Kind.Large -> ListLayout(context, data, rows = 7)
            }
        }
    }
}

class SmallWidget : BaseWidget(Kind.Small)
class StripWidget : BaseWidget(Kind.Strip)
class MediumWidget : BaseWidget(Kind.Medium)
class LargeWidget : BaseWidget(Kind.Large)

class SmallWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SmallWidget() }
class StripWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = StripWidget() }
class MediumWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MediumWidget() }
class LargeWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LargeWidget() }

object Widgets {
    /** Redraws every FoldMail widget on the home screen. */
    suspend fun updateAll(context: Context) {
        for (w in listOf(SmallWidget(), StripWidget(), MediumWidget(), LargeWidget())) {
            try { w.updateAll(context) } catch (_: Exception) { }
        }
    }
}

@Composable
private fun Frame(context: Context, padding: Int = 12, content: @Composable androidx.glance.layout.ColumnScope.() -> Unit) {
    Column(
        GlanceModifier.fillMaxSize().background(bg).cornerRadius(28.dp).padding(padding.dp)
            .clickable(actionStartActivity(openIntent(context, null))),
    ) { content() }
}

@Composable
private fun ComposeButton(context: Context, size: Int = 34) {
    Box(
        GlanceModifier.size(size.dp).background(accent).cornerRadius((size / 2).dp).clickable(actionStartActivity(openIntent(context, null, compose = true))),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(R.drawable.ic_widget_edit), "Nový email", GlanceModifier.size((size * 0.52f).dp), colorFilter = androidx.glance.ColorFilter.tint(onAccent))
    }
}

@Composable
private fun Badge(n: Int) {
    if (n <= 0) return
    Box(GlanceModifier.background(accent).cornerRadius(11.dp).padding(horizontal = 8.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(if (n > 99) "99+" else n.toString(), style = TextStyle(color = onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun SmallLayout(context: Context, d: WidgetData.Snapshot) {
    Frame(context, padding = 14) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Doručené", GlanceModifier.defaultWeight(), style = TextStyle(color = sub, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            ComposeButton(context, 32)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(d.unread.toString(), style = TextStyle(color = text, fontSize = 38.sp, fontWeight = FontWeight.Bold))
        Text(if (d.unread == 1) "neprečítaný" else "neprečítané", style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        d.rows.firstOrNull()?.let {
            Spacer(GlanceModifier.height(4.dp))
            Text(it.name, maxLines = 1, style = TextStyle(color = sub, fontSize = 12.sp))
        }
    }
}

@Composable
private fun StripLayout(context: Context, d: WidgetData.Snapshot) {
    Frame(context, padding = 10) {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val first = d.rows.firstOrNull()
            if (first != null) AvatarView(context, first, 36) else Spacer(GlanceModifier.size(36.dp))
            Column(GlanceModifier.defaultWeight().padding(horizontal = 10.dp)) {
                Text(first?.name ?: "Žiadne správy", maxLines = 1, style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Text(first?.subject ?: "", maxLines = 1, style = TextStyle(color = sub, fontSize = 12.sp))
            }
            Badge(d.unread)
            Spacer(GlanceModifier.width(8.dp))
            ComposeButton(context, 36)
        }
    }
}

@Composable
private fun ListLayout(context: Context, d: WidgetData.Snapshot, rows: Int) {
    Frame(context, padding = 12) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Doručené", style = TextStyle(color = text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(8.dp))
            Badge(d.unread)
            Spacer(GlanceModifier.defaultWeight())
            ComposeButton(context, 34)
        }
        if (d.rows.isEmpty()) {
            Text("Žiadne správy", GlanceModifier.padding(8.dp), style = TextStyle(color = sub, fontSize = 13.sp))
        }
        d.rows.take(rows).forEach { r ->
            Row(
                GlanceModifier.fillMaxWidth().background(card).cornerRadius(18.dp).padding(horizontal = 10.dp, vertical = 8.dp)
                    .clickable(actionStartActivity(openIntent(context, r.key))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarView(context, r, 34)
                Column(GlanceModifier.defaultWeight().padding(horizontal = 10.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.name, GlanceModifier.defaultWeight(), maxLines = 1,
                            style = TextStyle(color = text, fontSize = 13.sp, fontWeight = if (r.unread) FontWeight.Bold else FontWeight.Medium),
                        )
                        Text(formatTimestamp(r.time), style = TextStyle(color = sub, fontSize = 11.sp))
                    }
                    Text(r.subject.ifBlank { r.preview }, maxLines = 1, style = TextStyle(color = if (r.unread) text else sub, fontSize = 12.sp))
                }
                if (r.unread) Box(GlanceModifier.size(9.dp).background(accent).cornerRadius(5.dp)) { }
            }
            Spacer(GlanceModifier.height(5.dp))
        }
    }
}

@Composable
private fun AvatarView(context: Context, r: WidgetData.Row, size: Int) {
    val bmp = r.address?.let { a -> AvatarImages.localFile(context, a)?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() } }
    if (bmp != null) {
        Image(ImageProvider(bmp), null, GlanceModifier.size(size.dp).cornerRadius((size / 2).dp))
    } else {
        val c = Color(avatarColors[(r.name.hashCode() and 0x7fffffff) % avatarColors.size])
        Box(GlanceModifier.size(size.dp).background(UnitColorProvider(c)).cornerRadius((size / 2).dp), contentAlignment = Alignment.Center) {
            Text(r.name.trim().firstOrNull()?.uppercase() ?: "?", style = TextStyle(color = UnitColorProvider(Color.White), fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Bold))
        }
    }
}
