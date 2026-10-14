package de.konan.hiresplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFE8B04B)
val BitPerfectGreen = Color(0xFF5BD68A)
val NativeBlue = Color(0xFF6CB4FF)
val Muted = Color(0xFF8A93A3)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1B1405),
    secondary = NativeBlue,
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1A1F27),
    onSurfaceVariant = Color(0xFFB4BCC8),
    surfaceContainer = Color(0xFF151A21),
    surfaceContainerHigh = Color(0xFF1C222B),
    outline = Color(0xFF39414D),
)

@Composable
fun HiResTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
