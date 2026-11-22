package com.foldmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldmail.app.initialOf

private val AvatarColors = listOf(
    Color(0xFF0071E3), Color(0xFF34C759), Color(0xFFFF9500), Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFF5AC8FA),
)

@Composable
fun Avatar(title: String, size: Dp = 32.dp, modifier: Modifier = Modifier, isGroup: Boolean = false, address: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled by remember(context) { com.foldmail.app.data.AppSettings.get(context).senderPhotos }.collectAsState()
    val photo by produceState<android.graphics.Bitmap?>(null, address, enabled) {
        value = if (enabled && address != null && !isGroup) com.foldmail.app.data.AvatarImages.load(context, address) else null
    }
    val color = AvatarColors[(title.hashCode() and Int.MAX_VALUE) % AvatarColors.size]
    Box(
        modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        val img = photo
        if (img != null) {
            androidx.compose.foundation.Image(
                img.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.White), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else if (isGroup) {
            Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        } else {
            Text(initialOf(title), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
        }
    }
}
