package com.tadaffuq.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tadaffuq.app.R
import com.tadaffuq.data.ThemeMode

val Navy = Color(0xFF142B4A)
val Mint = Color(0xFF34D6AE)
val LightBg = Color(0xFFF5F7FA)
val DarkBg = Color(0xFF0D1117)

/** ألوان دلالية إضافية فوق Material: النص الثانوي الرمادي وحالات المهام. */
@Immutable
data class Extra(
    val secondaryText: Color,
    val hairline: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val paused: Color,
    val card: Color,
    val chartLine: Color,
    val chartFill: Color,
)

val LocalExtra = staticCompositionLocalOf {
    Extra(Color.Gray, Color.LightGray, Mint, Color(0xFFE8A33D), Color(0xFFE5484D), Color.Gray, Color.White, Mint, Mint)
}

val Plex = FontFamily(
    Font(R.font.plex_arabic_regular, FontWeight.Normal),
    Font(R.font.plex_arabic_medium, FontWeight.Medium),
    Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.plex_arabic_bold, FontWeight.Bold),
)

private fun typography(scale: Float): Typography {
    fun s(size: Int, weight: FontWeight, line: Int) =
        TextStyle(fontFamily = Plex, fontWeight = weight, fontSize = (size * scale).sp, lineHeight = (line * scale).sp)
    return Typography(
        displaySmall = s(32, FontWeight.Bold, 42),       // العنوان الكبير
        headlineSmall = s(22, FontWeight.Bold, 30),
        titleLarge = s(20, FontWeight.SemiBold, 28),
        titleMedium = s(17, FontWeight.SemiBold, 25),
        titleSmall = s(15, FontWeight.SemiBold, 22),
        bodyLarge = s(16, FontWeight.Normal, 25),
        bodyMedium = s(15, FontWeight.Normal, 23),
        bodySmall = s(13, FontWeight.Normal, 20),
        labelLarge = s(15, FontWeight.Medium, 21),
        labelMedium = s(13, FontWeight.Medium, 18),
        labelSmall = s(12, FontWeight.Medium, 16),
    )
}

/** لون التمييز: نسخة زاهية للداكن، وأغمق للفاتح لتبلغ تباين 4.5:1 مع الأبيض. */
fun accentPair(a: com.tadaffuq.data.Accent): Pair<Color, Color> = when (a) {
    com.tadaffuq.data.Accent.MINT -> Mint to Color(0xFF0B7F63)
    com.tadaffuq.data.Accent.BLUE -> Color(0xFF6CB2FF) to Color(0xFF1E5FB8)
    com.tadaffuq.data.Accent.VIOLET -> Color(0xFFB39DFF) to Color(0xFF5B3FC4)
    com.tadaffuq.data.Accent.AMBER -> Color(0xFFFFC266) to Color(0xFF9A5B00)
    com.tadaffuq.data.Accent.ROSE -> Color(0xFFFF8FB1) to Color(0xFFB3265A)
}

@Composable
fun TadaffuqTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    fontScale: Float = 1f,
    dynamic: Boolean = false,
    accent: com.tadaffuq.data.Accent = com.tadaffuq.data.Accent.MINT,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val (bright, deep) = accentPair(accent)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val useDynamic = dynamic && android.os.Build.VERSION.SDK_INT >= 31
    val base = if (dark) darkColorScheme(
        primary = Mint, onPrimary = Color(0xFF04261D),
        primaryContainer = Color(0xFF12352E), onPrimaryContainer = Color(0xFFB8F5E3),
        secondary = Color(0xFF8DB4E8), onSecondary = Color(0xFF0B1F38),
        secondaryContainer = Color(0xFF1C2E47), onSecondaryContainer = Color(0xFFD3E3FA),
        background = DarkBg, onBackground = Color(0xFFE6EDF3),
        surface = DarkBg, onSurface = Color(0xFFE6EDF3),
        surfaceVariant = Color(0xFF1C222B), onSurfaceVariant = Color(0xFF9AA4AF),
        surfaceContainer = Color(0xFF161B22), surfaceContainerHigh = Color(0xFF1C222B), surfaceContainerLow = Color(0xFF12161D),
        surfaceContainerHighest = Color(0xFF2D333B),
        outline = Color(0xFF6E7681), outlineVariant = Color(0xFF262C34),
        error = Color(0xFFFF7B72), onError = Color(0xFF3D0A07),
    ) else lightColorScheme(
        primary = Navy, onPrimary = Color.White,
        primaryContainer = Color(0xFFD6F7EE), onPrimaryContainer = Color(0xFF0A3B30),
        secondary = Color(0xFF0E8C6E), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE3EAF3), onSecondaryContainer = Navy,
        background = LightBg, onBackground = Color(0xFF111827),
        surface = LightBg, onSurface = Color(0xFF111827),
        surfaceVariant = Color(0xFFE9EDF2), onSurfaceVariant = Color(0xFF5B6573),
        surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerLow = Color(0xFFF0F3F7),
        surfaceContainerHighest = Color(0xFFE3E8EE),
        outline = Color(0xFF8A94A3), outlineVariant = Color(0xFFE5E9EF),
        error = Color(0xFFC92A2F), onError = Color.White,
    )
    val scheme = when {
        useDynamic && dark -> androidx.compose.material3.dynamicDarkColorScheme(ctx).copy(background = DarkBg, surface = DarkBg)
        useDynamic -> androidx.compose.material3.dynamicLightColorScheme(ctx).copy(background = LightBg, surface = LightBg)
        dark -> base.copy(primary = bright, secondary = bright.copy(alpha = 1f))
        else -> base.copy(secondary = deep)
    }
    val accentDark = if (useDynamic) scheme.primary else bright
    val accentLight = if (useDynamic) scheme.primary else deep
    val extra = if (dark) Extra(
        secondaryText = Color(0xFF9AA4AF), hairline = Color(0xFF262C34), success = Mint, warning = Color(0xFFF2B35B),
        danger = Color(0xFFFF7B72), paused = Color(0xFF8B949E), card = Color(0xFF161B22),
        chartLine = accentDark, chartFill = accentDark.copy(alpha = 0.18f),
    ) else Extra(
        // أخضر النص أغمق من النعناعي ليبلغ تباين 4.5:1 على الأبيض
        secondaryText = Color(0xFF5B6573), hairline = Color(0xFFE5E9EF), success = Color(0xFF0B7F63), warning = Color(0xFF9A5B00),
        danger = Color(0xFFC92A2F), paused = Color(0xFF6B7280), card = Color.White,
        chartLine = accentLight, chartFill = accentLight.copy(alpha = 0.2f),
    )
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography(fontScale),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

object T {
    val extra: Extra @Composable get() = LocalExtra.current
}
