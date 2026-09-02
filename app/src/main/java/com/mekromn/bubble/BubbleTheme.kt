package com.mekromn.bubble

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val BubbleDarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF071629),
    primaryContainer = Color(0xFF183A62),
    onPrimaryContainer = Color(0xFFD7E7FF),
    secondary = Color(0xFFC4C7CF),
    onSecondary = Color(0xFF282A30),
    secondaryContainer = Color(0xFF30343C),
    onSecondaryContainer = Color(0xFFE5E7ED),
    tertiary = Color(0xFFB8C8E8),
    onTertiary = Color(0xFF1C2B42),
    tertiaryContainer = Color(0xFF293A52),
    onTertiaryContainer = Color(0xFFDCE8FF),
    background = Color(0xFF090B0F),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF090B0F),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF25282F),
    onSurfaceVariant = Color(0xFFB9BDC7),
    surfaceContainerLowest = Color(0xFF07090C),
    surfaceContainerLow = Color(0xFF0D0F13),
    surfaceContainer = Color(0xFF11141A),
    surfaceContainerHigh = Color(0xFF181C23),
    surfaceContainerHighest = Color(0xFF20242C),
    outline = Color(0xFF626773),
    outlineVariant = Color(0xFF343842),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5A1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
internal fun BubbleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BubbleDarkColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
