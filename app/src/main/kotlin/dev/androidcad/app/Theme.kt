package dev.androidcad.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Darstellung: dem System folgen oder fest dunkel/hell. Standard ist Dunkel (CAD-Gewohnheit: starke Kontraste). */
enum class ThemeMode(val label: String) { DARK("Dunkel"), LIGHT("Hell"), SYSTEM("Wie das System") }

/**
 * Farben jenseits von Material: 3D-Ansicht, Skizze (Zustandsfarben) und Glas-Flächen. Teil des Farbkonzepts „Blaupause“
 * (Vorschau: docs/design/farbkonzept-*.png, erzeugt von scripts/theme-mockup.py).
 */
@Immutable
data class CadColors(
    val isDark: Boolean,
    // 3D-Ansicht
    val viewportTop: Color, val viewportBottom: Color, val model: Color, val modelEdge: Color, val selection: Color, val sketchInSpace: Color,
    // Skizze
    val sketchBackground: Color, val grid: Color, val gridMajor: Color,
    val underDefined: Color, val fullyDefined: Color, val construction: Color, val conflict: Color, val dimension: Color,
    // Achsen (X, Y, Z)
    val axisX: Color, val axisY: Color, val axisZ: Color,
    // Glas-Flächen: Tönung, feiner Rand, Lichtkante oben
    val glassTint: Color, val glassBorder: Color, val glassHighlight: Color,
)

private val BlueprintDark = CadColors(
    isDark = true,
    viewportTop = Color(0xFF0E1A36), viewportBottom = Color(0xFF1D3160), model = Color(0xFFB4C8E6), modelEdge = Color(0xFF0A1226),
    selection = Color(0xFFFF9A2E), sketchInSpace = Color(0xFF34D1F0),
    sketchBackground = Color(0xFF0C1730), grid = Color(0xFF15284A), gridMajor = Color(0xFF1F3A66),
    underDefined = Color(0xFF34D1F0), fullyDefined = Color(0xFFF4F8FF), construction = Color(0xFF6F86B0),
    conflict = Color(0xFFFF5D6C), dimension = Color(0xFFFFD66B),
    axisX = Color(0xFFFF6B7A), axisY = Color(0xFF5FD39A), axisZ = Color(0xFF6FA8FF),
    glassTint = Color(0xFF16244A).copy(alpha = 0.74f), glassBorder = Color(0xFF9FDFFF).copy(alpha = 0.28f),
    glassHighlight = Color.White.copy(alpha = 0.07f),
)

private val BlueprintLight = CadColors(
    isDark = false,
    viewportTop = Color(0xFFF1F5FB), viewportBottom = Color(0xFFCCD8EB), model = Color(0xFF7C9DCC), modelEdge = Color(0xFF0E1A33),
    selection = Color(0xFFF57C00), sketchInSpace = Color(0xFF0A7FA6),
    sketchBackground = Color(0xFFF7F9FD), grid = Color(0xFFE3E9F3), gridMajor = Color(0xFFC9D5E8),
    underDefined = Color(0xFF1E6FE0), fullyDefined = Color(0xFF0E1A33), construction = Color(0xFF7A8AA6),
    conflict = Color(0xFFD32F2F), dimension = Color(0xFF8A5A00),
    axisX = Color(0xFFD32F2F), axisY = Color(0xFF2E7D32), axisZ = Color(0xFF1565C0),
    glassTint = Color.White.copy(alpha = 0.76f), glassBorder = Color(0xFF0E1A33).copy(alpha = 0.12f),
    glassHighlight = Color.White.copy(alpha = 0.35f),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF34D1F0), onPrimary = Color(0xFF00202A),
    primaryContainer = Color(0xFF1C6E8C), onPrimaryContainer = Color(0xFFD6F6FF),
    secondary = Color(0xFF8FB4E8), onSecondary = Color(0xFF0A1C3A),
    secondaryContainer = Color(0xFF22355E), onSecondaryContainer = Color(0xFFDCE7FF),
    tertiary = Color(0xFFFF9A2E), onTertiary = Color(0xFF2A1400),
    tertiaryContainer = Color(0xFF6B3A00), onTertiaryContainer = Color(0xFFFFDDBF),
    background = Color(0xFF0A1226), onBackground = Color(0xFFE8F1FF),
    surface = Color(0xFF101B36), onSurface = Color(0xFFE8F1FF),
    surfaceVariant = Color(0xFF16244A), onSurfaceVariant = Color(0xFF93A8CC),
    surfaceContainerLowest = Color(0xFF08101F), surfaceContainerLow = Color(0xFF0E1830), surfaceContainer = Color(0xFF132042),
    surfaceContainerHigh = Color(0xFF1A2850), surfaceContainerHighest = Color(0xFF213260),
    surfaceBright = Color(0xFF253764), surfaceDim = Color(0xFF0A1226),
    outline = Color(0xFF4A6290), outlineVariant = Color(0xFF2A3C66),
    error = Color(0xFFFF5D6C), onError = Color(0xFF2A0008), errorContainer = Color(0xFF5A1A24), onErrorContainer = Color(0xFFFFD9DD),
    inverseSurface = Color(0xFFE8F1FF), inverseOnSurface = Color(0xFF101B36), inversePrimary = Color(0xFF0A6E8C),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0A6E8C), onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F1FB), onPrimaryContainer = Color(0xFF002733),
    secondary = Color(0xFF3D5A8A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F9), onSecondaryContainer = Color(0xFF0E1E3D),
    tertiary = Color(0xFFC25E00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2), onTertiaryContainer = Color(0xFF3A1C00),
    background = Color(0xFFF3F6FB), onBackground = Color(0xFF0E1A33),
    surface = Color(0xFFFBFCFE), onSurface = Color(0xFF0E1A33),
    surfaceVariant = Color(0xFFE3EAF4), onSurfaceVariant = Color(0xFF4A5B78),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F8FC), surfaceContainer = Color(0xFFEEF2F8),
    surfaceContainerHigh = Color(0xFFE8EDF5), surfaceContainerHighest = Color(0xFFE2E8F2),
    outline = Color(0xFF7A8AA6), outlineVariant = Color(0xFFC6D0E0),
    error = Color(0xFFC62839), onError = Color.White, errorContainer = Color(0xFFFFDADC), onErrorContainer = Color(0xFF410009),
)

val LocalCadColors = staticCompositionLocalOf { BlueprintDark }

/** Zugriff auf die CAD-Farben des aktuellen Themas: `CadTheme.colors.selection` … */
object CadTheme {
    val colors: CadColors @Composable get() = LocalCadColors.current
}

/** Ob das Thema dunkel ist (für Systemleisten und das Thema selbst). */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** Thema „Blaupause“: Material-Farben und CAD-Farben passend zu [mode]. */
@Composable
fun PartwrightTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        CompositionLocalProvider(LocalCadColors provides if (dark) BlueprintDark else BlueprintLight, content = content)
    }
}
