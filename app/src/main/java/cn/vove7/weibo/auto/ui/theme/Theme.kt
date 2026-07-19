package cn.vove7.weibo.auto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 暖白底色配合柔和麦紫，减少长时间操作时的视觉刺激。
private val MilletPurple = Color(0xFF8062B3)
private val MilletPurpleDark = Color(0xFF60468E)
private val Ink = Color(0xFF292331)
private val InkMuted = Color(0xFF6C6575)
private val SurfaceBg = Color(0xFFFFFAF7)
private val SurfaceCard = Color(0xFFFFFFFF)
private val OutlineSoft = Color(0xFFE8E0EA)

private val LightColors = lightColorScheme(
    primary = MilletPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECDDFF),
    onPrimaryContainer = Color(0xFF2E174E),
    secondary = Color(0xFF75665D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFECDC),
    onSecondaryContainer = Color(0xFF2B170B),
    tertiary = Color(0xFF356A52),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBDEFD0),
    onTertiaryContainer = Color(0xFF002112),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = SurfaceBg,
    onBackground = Ink,
    surface = SurfaceCard,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF5EFF6),
    onSurfaceVariant = InkMuted,
    outline = OutlineSoft,
    outlineVariant = Color(0xFFD8CDDB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD7BAFF),
    onPrimary = Color(0xFF432467),
    primaryContainer = MilletPurpleDark,
    onPrimaryContainer = Color(0xFFECDDFF),
    secondary = Color(0xFFE8C5AF),
    onSecondary = Color(0xFF442A1C),
    secondaryContainer = Color(0xFF5C4030),
    onSecondaryContainer = Color(0xFFFFECDC),
    tertiary = Color(0xFFA0D8B6),
    onTertiary = Color(0xFF003920),
    tertiaryContainer = Color(0xFF145132),
    onTertiaryContainer = Color(0xFFBDEFD0),
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
