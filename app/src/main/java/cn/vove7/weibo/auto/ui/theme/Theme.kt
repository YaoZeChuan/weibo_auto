package cn.vove7.weibo.auto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 微博红主色 + 干净中性灰，状态色独立不跟主题 tertiary 跑偏
private val WeiboRed = Color(0xFFE6162D)
private val WeiboRedDark = Color(0xFFC41024)
private val Ink = Color(0xFF1C1B1F)
private val InkMuted = Color(0xFF5F5E62)
private val SurfaceBg = Color(0xFFF7F6F8)
private val SurfaceCard = Color(0xFFFFFFFF)
private val OutlineSoft = Color(0xFFE4E2E6)

private val LightColors = lightColorScheme(
    primary = WeiboRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD8),
    onPrimaryContainer = Color(0xFF410006),
    secondary = Color(0xFF2E6B4F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F1D0),
    onSecondaryContainer = Color(0xFF002113),
    tertiary = Color(0xFF1B5E20),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8E6C9),
    onTertiaryContainer = Color(0xFF002106),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = SurfaceBg,
    onBackground = Ink,
    surface = SurfaceCard,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0EEF1),
    onSurfaceVariant = InkMuted,
    outline = OutlineSoft,
    outlineVariant = Color(0xFFCAC5CA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB3AE),
    onPrimary = Color(0xFF68000E),
    primaryContainer = WeiboRedDark,
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color(0xFF9CD4B5),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF0F5138),
    onSecondaryContainer = Color(0xFFB8F1D0),
    tertiary = Color(0xFFA5D6A7),
    onTertiary = Color(0xFF003910),
    tertiaryContainer = Color(0xFF1B5E20),
    onTertiaryContainer = Color(0xFFC8E6C9),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
)

@Composable
fun WeiboAutoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
