package dev.androidcad.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Quelle für echte Unschärfe hinter Glas-Flächen. Gesetzt, wenn der Hintergrund in Compose gezeichnet wird (Skizze); über der
 * 3D-Ansicht (OpenGL in eigener Fläche, für Compose nicht lesbar) bleibt sie `null` – dort bildet [glass] die Optik nach.
 */
val LocalGlassSource = compositionLocalOf<HazeState?> { null }

/**
 * „Frosted glass“ für schwebende Bedienelemente: getönte, halbtransparente Fläche mit Lichtkante oben und feinem hellem Rand.
 * Mit [LocalGlassSource] liegt darunter echte Unschärfe des Hintergrunds (Haze); sonst – und wo das Gerät keine Unschärfe kann – die
 * etwas dichtere Tönung allein.
 */
@Composable
fun Modifier.glass(shape: Shape): Modifier {
    val cad = CadTheme.colors
    val source = LocalGlassSource.current
    val base = if (source != null) {
        clip(shape).hazeEffect(
            state = source,
            style = HazeStyle(
                backgroundColor = cad.sketchBackground,
                tints = listOf(HazeTint(cad.glassTint.copy(alpha = cad.glassTint.alpha * 0.7f))),
                blurRadius = 18.dp,
                noiseFactor = 0.04f,
                fallbackTint = HazeTint(cad.glassTint),
            ),
        )
    } else {
        background(cad.glassTint, shape)
    }
    return base
        .background(Brush.verticalGradient(listOf(cad.glassHighlight, Color.Transparent)), shape)
        .border(0.75.dp, cad.glassBorder, shape)
}
