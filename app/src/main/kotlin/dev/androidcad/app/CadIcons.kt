package dev.androidcad.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Ein Strich eines Symbols: SVG-Pfad im 24×24-Raster; [dashed] = gestrichelt (z. B. weggeschnittener Teil), [filled] = gefüllt. */
private class Part(val d: String, val dashed: Boolean = false, val filled: Boolean = false)

/**
 * Einheitliche Symbole der Werkzeugleisten: dünne Linien im 24×24-Raster, Farbe vom Knopf. Statt Unicode-Zeichen, die je nach Schrift
 * unterschiedlich groß und teils kaum erkennbar sind (◜ ◸ ▭ …).
 */
enum class CadIcon(private vararg val parts: Part) {
    SELECT(Part("M6 3 L6 19 L10 15 L13 21 L15.5 20 L12.5 14 L18 14 Z")),
    LINE(Part("M5 19 L19 5"), Part("M3.5 19 a1.5 1.5 0 1 0 3 0 a1.5 1.5 0 1 0 -3 0", filled = true), Part("M17.5 5 a1.5 1.5 0 1 0 3 0 a1.5 1.5 0 1 0 -3 0", filled = true)),
    RECTANGLE(Part("M4 7 H20 V17 H4 Z")),
    CIRCLE(Part("M5 12 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0"), Part("M11 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0", filled = true)),
    ARC(Part("M5 17 A7 7 0 0 1 19 17"), Part("M11 17 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0", filled = true)),
    SLOT(Part("M8 8 H16 A4 4 0 0 1 16 16 H8 A4 4 0 0 1 8 8 Z"), Part("M8 12 H16", dashed = true)),
    POLYGON(Part("M12 3.5 L19.4 7.75 L19.4 16.25 L12 20.5 L4.6 16.25 L4.6 7.75 Z")),
    POINT(Part("M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0", filled = true), Part("M12 3 V7"), Part("M12 17 V21"), Part("M3 12 H7"), Part("M17 12 H21")),
    TEXT(Part("M5 6 H19 M12 6 V19 M9 19 H15")),
    TRIM(Part("M9 4 V20"), Part("M9 12 H20"), Part("M3 12 H8", dashed = true)),
    // Teilen: Linie, an einem Punkt in zwei Teile getrennt, mit Schnittmarke
    SPLIT(Part("M3 12 H9.5 M14.5 12 H21"), Part("M10 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0", filled = true), Part("M12 4 V8 M12 16 V20", dashed = true)),
    EXTEND(Part("M19 4 V20"), Part("M3 12 H11"), Part("M11 12 H18", dashed = true), Part("M15 9 L18 12 L15 15")),
    OFFSET(Part("M4 20 V8 H18"), Part("M8 20 V12 H18", dashed = true)),
    FILLET(Part("M5 20 V13 A8 8 0 0 1 13 5 H20"), Part("M5 9 V5 H9", dashed = true)),
    MIRROR(Part("M12 3 V21", dashed = true), Part("M9.5 7 L3.5 17 H9.5 Z"), Part("M14.5 7 L20.5 17 H14.5 Z")),
    TRANSFORM(Part("M4 8 V4 H8"), Part("M16 4 H20 V8"), Part("M20 16 V20 H16"), Part("M8 20 H4 V16"), Part("M12 7 V17 M7 12 H17")),
    // Bemaßen: Maßlinie mit Pfeilen zwischen zwei Hilfslinien
    DIMENSION(Part("M4 5 V19 M20 5 V19"), Part("M4 12 H20"), Part("M7.5 9 L4 12 L7.5 15 M16.5 9 L20 12 L16.5 15")),
    MULTI(Part("M4 4 H20 V20 H4 Z"), Part("M8 12 L11 15 L16 9")),
    DONE(Part("M5 12.5 L10 17.5 L19 7")),
    UNDO(Part("M8 7 L4 11 L8 15"), Part("M4 11 H14 A5 5 0 0 1 14 21 H10")),
    REDO(Part("M16 7 L20 11 L16 15"), Part("M20 11 H10 A5 5 0 0 0 10 21 H14")),
    // Projektmenü
    HOME(Part("M4 11 L12 4 L20 11"), Part("M6.5 9.5 V20 H17.5 V9.5"), Part("M10 20 V14 H14 V20")),
    EXAMPLES(Part("M5 4 H16 L19 7 V20 H5 Z"), Part("M16 4 V7 H19"), Part("M8 11 H16 M8 14.5 H16 M8 18 H13")),
    IMPORT(Part("M12 3 V14"), Part("M8 10 L12 14 L16 10"), Part("M4 15 V20 H20 V15")),
    LIST(Part("M4 4 H10 V10 H4 Z"), Part("M14 4 H20 V10 H14 Z"), Part("M4 14 H10 V20 H4 Z"), Part("M14 14 H20 V20 H14 Z")),
    RENAME(Part("M4 20 H20"), Part("M6 16 L6.8 12.5 L15 4.3 L18 7.3 L9.8 15.5 Z")),
    COPY(Part("M8 8 H19 V20 H8 Z"), Part("M5 16 V4 H16")),
    THEME(Part("M12 4 a8 8 0 1 0 0.01 0 Z"), Part("M12 4 A8 8 0 0 1 12 20 Z", filled = true)),
    HELP(Part("M12 4 a8 8 0 1 0 0.01 0 Z"), Part("M9.5 9.5 A2.6 2.6 0 1 1 13.2 11.8 Q12 12.4 12 14"), Part("M11.2 16.8 a0.8 0.8 0 1 0 1.6 0 a0.8 0.8 0 1 0 -1.6 0", filled = true)),
    PRO(Part("M12 3.5 L14.6 9 L20.5 9.6 L16 13.6 L17.3 19.5 L12 16.5 L6.7 19.5 L8 13.6 L3.5 9.6 L9.4 9 Z")),
    MAIL(Part("M4 6 H20 V18 H4 Z"), Part("M4 6.5 L12 13 L20 6.5")),
    INFO(Part("M12 4 a8 8 0 1 0 0.01 0 Z"), Part("M12 11 V16.5"), Part("M11.2 7.8 a0.8 0.8 0 1 0 1.6 0 a0.8 0.8 0 1 0 -1.6 0", filled = true)),
    DELETE(Part("M4 7 H20"), Part("M9.5 7 V4.5 H14.5 V7"), Part("M6.5 7 L7.5 20 H16.5 L17.5 7"), Part("M10.5 11 V16.5"), Part("M13.5 11 V16.5")),
    GRID(Part("M4 4 H20 V20 H4 Z"), Part("M9.33 4 V20 M14.67 4 V20 M4 9.33 H20 M4 14.67 H20")),
    FIT(Part("M4 9 V4 H9"), Part("M15 4 H20 V9"), Part("M20 15 V20 H15"), Part("M9 20 H4 V15"), Part("M9 9 H15 V15 H9 Z", dashed = true)),
    SCRIPT(Part("M9 4 Q6 4 6 7 V10 Q6 12 4 12 Q6 12 6 14 V17 Q6 20 9 20"), Part("M15 4 Q18 4 18 7 V10 Q18 12 20 12 Q18 12 18 14 V17 Q18 20 15 20")),
    SKETCH(Part("M4 20 L5 15 L16 4 L20 8 L9 19 Z"), Part("M13.5 6.5 L17.5 10.5")),
    PLANE(Part("M3 17 L8 8 H21 L16 17 Z"), Part("M12 12.5 V3"), Part("M10 5 L12 3 L14 5")),
    SKETCH_ON_FACE(Part("M3 18 L8 9 H21 L16 18 Z"), Part("M10.5 13.5 a2.5 2 0 1 0 5 0 a2.5 2 0 1 0 -5 0")),
    EXTRUDE(Part("M3 19 L7 15 H21 L17 19 Z"), Part("M12 13 V3"), Part("M8.5 6.5 L12 3 L15.5 6.5")),
    CUT(Part("M3 19 L7 15 H21 L17 19 Z"), Part("M12 3 V13"), Part("M8.5 9.5 L12 13 L15.5 9.5")),
    INTERSECT(Part("M4 12 a5.5 5.5 0 1 0 11 0 a5.5 5.5 0 1 0 -11 0"), Part("M9 12 a5.5 5.5 0 1 0 11 0 a5.5 5.5 0 1 0 -11 0")),
    // Drehen: Profil neben der (gestrichelten) Achse, darüber der Drehpfeil
    REVOLVE(Part("M7 3 V21", dashed = true), Part("M10 11 H16 V19 H10 Z"), Part("M3 7 A9 3.2 0 0 0 17 8.2"), Part("M14.2 5.6 L17 8.2 L13.6 9.4")),
    // Schale: offener Kasten mit innerer Wand
    SHELL(Part("M3 8 V20 H21 V8"), Part("M6.5 8 V16.5 H17.5 V8", dashed = true), Part("M3 8 H6.5 M17.5 8 H21")),
    // Messen: Lineal mit Teilstrichen
    MEASURE(Part("M3 15 L15 3 L21 9 L9 21 Z"), Part("M7 11 L9 13 M10 8 L12 10 M13 5 L15 7")),
    HOLE(Part("M4 4 H20 V20 H4 Z"), Part("M8 12 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0")),
    ROUND(Part("M5 20 V13 A8 8 0 0 1 13 5 H20"), Part("M9 20 V14 A5 5 0 0 1 14 9 H20", dashed = true)),
    CHAMFER(Part("M5 20 V11 L11 5 H20"), Part("M9 20 V13 L13 9 H20", dashed = true)),
    PATTERN_LINEAR(Part("M3 9 H8 V15 H3 Z"), Part("M9.5 9 H14.5 V15 H9.5 Z"), Part("M16 9 H21 V15 H16 Z")),
    PATTERN_CIRCULAR(
        Part("M12 12 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0", dashed = true),
        Part("M10.5 5 a1.5 1.5 0 1 0 3 0 a1.5 1.5 0 1 0 -3 0", filled = true),
        Part("M17.5 12 a1.5 1.5 0 1 0 3 0 a1.5 1.5 0 1 0 -3 0", filled = true),
        Part("M10.5 19 a1.5 1.5 0 1 0 3 0 a1.5 1.5 0 1 0 -3 0", filled = true),
        Part("M3.5 12 a1.5 1.5 0 1 0 3 0 a1.5 1.5 0 1 0 -3 0", filled = true),
    ),
    ;

    internal val paths by lazy { parts.map { Triple(PathParser().parsePathString(it.d).toPath(), it.dashed, it.filled) } }
}

/** Zeichnet [icon] in der Farbe [tint]. */
@Composable
fun CadIconView(icon: CadIcon, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val paths = remember(icon) { icon.paths }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val dashed = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2.2f)))
        scale(k, k, pivot = Offset.Zero) {
            for ((path, isDashed, filled) in paths) {
                drawPath(path, tint, style = if (filled) Fill else if (isDashed) dashed else stroke)
            }
        }
    }
}
