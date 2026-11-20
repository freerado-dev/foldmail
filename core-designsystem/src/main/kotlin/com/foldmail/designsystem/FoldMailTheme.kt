package com.foldmail.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

/** Apple-like palette: white surfaces, soft grey panels, one blue accent. */
object FoldColors {
    val Accent = Color(0xFF0071E3)
    val Background = Color(0xFFFFFFFF)
    val Panel = Color(0xFFF5F5F7)
    val OnSurface = Color(0xFF1D1D1F)
    val Muted = Color(0xFF6E6E73)
}

private val Light = lightColorScheme(
    primary = FoldColors.Accent,
    background = FoldColors.Background,
    surface = FoldColors.Background,
    surfaceVariant = FoldColors.Panel,
    onSurface = FoldColors.OnSurface,
    onSurfaceVariant = FoldColors.Muted,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF2997FF),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF1C1C1E),
)

private val FoldShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
)

/** Everything is ~13% smaller than Material defaults (never below 11sp) so more text fits on the Fold. */
private const val FontScale = 0.87f

private fun TextStyle.scaled(): TextStyle {
    val size = (fontSize.value * FontScale).coerceAtLeast(11f)
    return copy(fontSize = size.sp, lineHeight = (lineHeight.value * size / fontSize.value).sp)
}

private val FoldTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.scaled(), displayMedium = t.displayMedium.scaled(), displaySmall = t.displaySmall.scaled(),
        headlineLarge = t.headlineLarge.scaled(), headlineMedium = t.headlineMedium.scaled(), headlineSmall = t.headlineSmall.scaled(),
        titleLarge = t.titleLarge.scaled(), titleMedium = t.titleMedium.scaled(), titleSmall = t.titleSmall.scaled(),
        bodyLarge = t.bodyLarge.scaled(), bodyMedium = t.bodyMedium.scaled(), bodySmall = t.bodySmall.scaled(),
        labelLarge = t.labelLarge.scaled(), labelMedium = t.labelMedium.scaled(), labelSmall = t.labelSmall.scaled(),
    )
}

@Composable
fun FoldMailTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        shapes = FoldShapes,
        typography = FoldTypography,
        content = content,
    )
}
