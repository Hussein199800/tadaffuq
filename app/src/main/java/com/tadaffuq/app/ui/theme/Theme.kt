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

/** درجات ثيم جاهز لوضع واحد (فاتح أو داكن). كل نص فوق خلفيته بتباين 4.5:1 على الأقل. */
@Immutable
data class Tones(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val background: Color, val card: Color, val low: Color, val high: Color, val highest: Color,
    val onSurface: Color, val secondaryText: Color, val hairline: Color, val outline: Color,
    val secondaryContainer: Color, val onSecondaryContainer: Color,
)

/** الثيمات الجاهزة (الأساس)، ولون التمييز يُطبَّق فوقها للرسوم والتقدم. */
fun paletteTones(p: com.tadaffuq.data.Palette, dark: Boolean): Tones = when (p) {
    com.tadaffuq.data.Palette.NAVY_MINT -> if (dark) Tones(
        Mint, Color(0xFF04261D), Color(0xFF12352E), Color(0xFFB8F5E3), DarkBg, Color(0xFF161B22), Color(0xFF12161D), Color(0xFF1C222B), Color(0xFF2D333B),
        Color(0xFFE6EDF3), Color(0xFF9AA4AF), Color(0xFF262C34), Color(0xFF6E7681), Color(0xFF1C2E47), Color(0xFFD3E3FA),
    ) else Tones(
        Navy, Color.White, Color(0xFFD6F7EE), Color(0xFF0A3B30), LightBg, Color.White, Color(0xFFF0F3F7), Color.White, Color(0xFFE3E8EE),
        Color(0xFF111827), Color(0xFF5B6573), Color(0xFFE5E9EF), Color(0xFF8A94A3), Color(0xFFE3EAF3), Navy,
    )
    // أخضر عميق وذهبي على عاجي نهاراً، فحمي بإضاءة خفيفة ليلاً (من هوية «طاقتي»)
    com.tadaffuq.data.Palette.GREEN_GOLD -> if (dark) Tones(
        Color(0xFF2FC77F), Color(0xFF04130B), Color(0xFF143326), Color(0xFFBFF0D6), Color(0xFF07080A), Color(0xFF15171B), Color(0xFF0E1013), Color(0xFF1D2025), Color(0xFF2A2E34),
        Color(0xFFF3F5F1), Color(0xFF8E959C), Color(0xFF23272C), Color(0xFF6B7178), Color(0xFF2B2513), Color(0xFFF7DDA0),
    ) else Tones(
        Color(0xFF0F5F3E), Color.White, Color(0xFFDCEFE4), Color(0xFF0A3B26), Color(0xFFF4F2EB), Color(0xFFFFFDF8), Color(0xFFEFECE3), Color(0xFFFFFFFF), Color(0xFFE6E2D6),
        Color(0xFF10241A), Color(0xFF5B665D), Color(0xFFE4E0D4), Color(0xFF88907F), Color(0xFFF6EBCF), Color(0xFF5C3D00),
    )
    com.tadaffuq.data.Palette.ORANGE_VIOLET -> if (dark) Tones(
        Color(0xFFFF9A5C), Color(0xFF2A1200), Color(0xFF3A2316), Color(0xFFFFD9C2), Color(0xFF120E18), Color(0xFF1C1724), Color(0xFF16121D), Color(0xFF241E2E), Color(0xFF332B3F),
        Color(0xFFF2EDF7), Color(0xFFA69DB2), Color(0xFF2A2433), Color(0xFF746A82), Color(0xFF2C2240), Color(0xFFE3D6FF),
    ) else Tones(
        Color(0xFFB4470F), Color.White, Color(0xFFFFE3D2), Color(0xFF4A1A00), Color(0xFFFBF7F4), Color.White, Color(0xFFF5EFEB), Color.White, Color(0xFFEDE5E0),
        Color(0xFF1F1430), Color(0xFF625A6E), Color(0xFFECE4E0), Color(0xFF948A9E), Color(0xFFEDE6FB), Color(0xFF3B2470),
    )
    com.tadaffuq.data.Palette.IVORY_BLACK -> if (dark) Tones(
        Color(0xFFF5F0E6), Color(0xFF141414), Color(0xFF2A2824), Color(0xFFF5F0E6), Color(0xFF000000), Color(0xFF121212), Color(0xFF0A0A0A), Color(0xFF1C1C1C), Color(0xFF2A2A2A),
        Color(0xFFF5F0E6), Color(0xFFA39E94), Color(0xFF262626), Color(0xFF6F6B64), Color(0xFF24221E), Color(0xFFEDE6D6),
    ) else Tones(
        Color(0xFF141414), Color.White, Color(0xFFECE6D8), Color(0xFF141414), Color(0xFFF7F3EA), Color(0xFFFFFDF7), Color(0xFFF0EBDF), Color(0xFFFFFFFF), Color(0xFFE6E0D3),
        Color(0xFF141414), Color(0xFF5F5A50), Color(0xFFE6E0D3), Color(0xFF8C867A), Color(0xFFECE6D8), Color(0xFF141414),
    )
}

/** لون التمييز المقترن بكل ثيم جاهز (يُختار تلقائياً عند تبديل الثيم، ويبقى قابلاً للتغيير). */
fun pairedAccent(p: com.tadaffuq.data.Palette) = when (p) {
    com.tadaffuq.data.Palette.NAVY_MINT -> com.tadaffuq.data.Accent.MINT
    com.tadaffuq.data.Palette.GREEN_GOLD -> com.tadaffuq.data.Accent.AMBER
    com.tadaffuq.data.Palette.ORANGE_VIOLET -> com.tadaffuq.data.Accent.VIOLET
    com.tadaffuq.data.Palette.IVORY_BLACK -> com.tadaffuq.data.Accent.BLUE
}

@Composable
fun TadaffuqTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    fontScale: Float = 1f,
    dynamic: Boolean = false,
    accent: com.tadaffuq.data.Accent = com.tadaffuq.data.Accent.MINT,
    palette: com.tadaffuq.data.Palette = com.tadaffuq.data.Palette.NAVY_MINT,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val (bright, deep) = accentPair(accent)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val useDynamic = dynamic && android.os.Build.VERSION.SDK_INT >= 31
    val t = paletteTones(palette, dark)
    val base = if (dark) darkColorScheme(
        primary = t.primary, onPrimary = t.onPrimary,
        primaryContainer = t.primaryContainer, onPrimaryContainer = t.onPrimaryContainer,
        secondary = bright, onSecondary = t.background,
        secondaryContainer = t.secondaryContainer, onSecondaryContainer = t.onSecondaryContainer,
        background = t.background, onBackground = t.onSurface,
        surface = t.background, onSurface = t.onSurface,
        surfaceVariant = t.high, onSurfaceVariant = t.secondaryText,
        surfaceContainer = t.card, surfaceContainerHigh = t.high, surfaceContainerLow = t.low,
        surfaceContainerHighest = t.highest,
        outline = t.outline, outlineVariant = t.hairline,
        error = Color(0xFFFF7B72), onError = Color(0xFF3D0A07),
    ) else lightColorScheme(
        primary = t.primary, onPrimary = t.onPrimary,
        primaryContainer = t.primaryContainer, onPrimaryContainer = t.onPrimaryContainer,
        secondary = deep, onSecondary = Color.White,
        secondaryContainer = t.secondaryContainer, onSecondaryContainer = t.onSecondaryContainer,
        background = t.background, onBackground = t.onSurface,
        surface = t.background, onSurface = t.onSurface,
        surfaceVariant = t.highest, onSurfaceVariant = t.secondaryText,
        surfaceContainer = t.card, surfaceContainerHigh = t.high, surfaceContainerLow = t.low,
        surfaceContainerHighest = t.highest,
        outline = t.outline, outlineVariant = t.hairline,
        error = Color(0xFFC92A2F), onError = Color.White,
    )
    val scheme = when {
        useDynamic && dark -> androidx.compose.material3.dynamicDarkColorScheme(ctx).copy(background = t.background, surface = t.background)
        useDynamic -> androidx.compose.material3.dynamicLightColorScheme(ctx).copy(background = t.background, surface = t.background)
        // الافتراضي كما كان: في الداكن يصير لون التمييز هو الأساسي
        dark && palette == com.tadaffuq.data.Palette.NAVY_MINT -> base.copy(primary = bright)
        else -> base
    }
    val accentDark = if (useDynamic) scheme.primary else bright
    val accentLight = if (useDynamic) scheme.primary else deep
    // النجاح والتحذير والخطر ثابتة دلالياً في كل الثيمات
    val extra = if (dark) Extra(
        secondaryText = t.secondaryText, hairline = t.hairline, success = Mint, warning = Color(0xFFF2B35B),
        danger = Color(0xFFFF7B72), paused = Color(0xFF8B949E), card = t.card,
        chartLine = accentDark, chartFill = accentDark.copy(alpha = 0.18f),
    ) else Extra(
        secondaryText = t.secondaryText, hairline = t.hairline, success = Color(0xFF0B7F63), warning = Color(0xFF9A5B00),
        danger = Color(0xFFC92A2F), paused = Color(0xFF6B7280), card = t.card,
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
