package com.example.tiny_computer.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg

/** Palet yang sama dengan DroidDeck (ui/Theme.kt): permukaan gelap + satu warna sinyal untuk yang aktif. */
class Palette(
    val id: String, @StringRes val label: Int,
    val background: Color, val surface: Color, val surfaceVariant: Color, val line: Color, val line2: Color,
    val onBackground: Color, val onSurfaceVariant: Color,
    val primary: Color, val onPrimary: Color, val signal: Color,
    val good: Color, val error: Color = Color(0xFFFF8A80),
)

object Themes {
    const val GRAPHITE = "graphite"
    const val PAPER = "paper"
    const val ICON_BLUE = "blue"
    const val ELECTRIC = "electric"

    val all: List<Palette> = listOf(
        Palette(
            GRAPHITE, R.string.tc_theme_graphite,
            background = Color(0xFF0A0B0D), surface = Color(0xFF121417), surfaceVariant = Color(0xFF1A1D22),
            line = Color(0xFF262A31), line2 = Color(0xFF343A43),
            onBackground = Color(0xFFF2F4F7), onSurfaceVariant = Color(0xFF9AA3AF),
            primary = Color(0xFF1A9FFF), onPrimary = Color(0xFF03111F), signal = Color(0xFF1A9FFF), good = Color(0xFF4CD37F),
        ),
        Palette(
            PAPER, R.string.tc_theme_paper,
            background = Color(0xFF000000), surface = Color(0xFF0F0F10), surfaceVariant = Color(0xFF191A1C),
            line = Color(0xFF262729), line2 = Color(0xFF343638),
            onBackground = Color(0xFFF9F9F9), onSurfaceVariant = Color(0xFF8E9196),
            primary = Color(0xFFF9F9F9), onPrimary = Color(0xFF000000), signal = Color(0xFF136CE0), good = Color(0xFF4CD37F),
        ),
        Palette(
            ICON_BLUE, R.string.tc_theme_icon_blue,
            background = Color(0xFF050608), surface = Color(0xFF0E1116), surfaceVariant = Color(0xFF161B23),
            line = Color(0xFF1F2630), line2 = Color(0xFF2A3340),
            onBackground = Color(0xFFF2F4F7), onSurfaceVariant = Color(0xFF8A93A0),
            primary = Color(0xFF136CE0), onPrimary = Color(0xFFFFFFFF), signal = Color(0xFF136CE0), good = Color(0xFF4CD37F),
        ),
        Palette(
            ELECTRIC, R.string.tc_theme_electric,
            background = Color(0xFF070A10), surface = Color(0xFF0F1522), surfaceVariant = Color(0xFF16203A),
            line = Color(0xFF1E2A47), line2 = Color(0xFF2A3A5E),
            onBackground = Color(0xFFEEF3FF), onSurfaceVariant = Color(0xFF8B9AB8),
            primary = Color(0xFF2E86FF), onPrimary = Color(0xFFFFFFFF), signal = Color(0xFF2E86FF), good = Color(0xFF4CD37F),
        ),
    )

    fun byId(id: String): Palette = all.firstOrNull { it.id == id } ?: all[0]
}

/** Tema aktif sebagai state Compose supaya ganti tema di Pengaturan langsung terlihat. */
object ThemeState {
    var id by mutableStateOf(Cfg.theme)
}

val LocalPalette = staticCompositionLocalOf { Themes.byId(Themes.GRAPHITE) }

/** Teks dan ikon di atas isian [Palette.signal]: hampir hitam bila biru terang, putih bila biru pekat. */
val Palette.onSignal: Color get() = if (signal.luminance() > 0.18f) Color(0xFF03111F) else Color.White

private val TinyTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

/** Tema aplikasi: selalu gelap, palet dipilih di Pengaturan (bawaan Graphite, sama dengan DroidDeck). */
@Composable
fun TinyTheme(themeId: String = ThemeState.id, content: @Composable () -> Unit) {
    val p = Themes.byId(themeId)
    val scheme = darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, secondary = p.onSurfaceVariant,
        background = p.background, onBackground = p.onBackground,
        surface = p.surface, onSurface = p.onBackground,
        surfaceVariant = p.surfaceVariant, onSurfaceVariant = p.onSurfaceVariant,
        error = p.error, outline = p.line2, outlineVariant = p.line,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = TinyTypography, content = content)
    }
}
