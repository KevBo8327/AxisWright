package dev.androidcad.sketchedit

import dev.androidcad.sketch.Vec2
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Abbildung zwischen Skizzenkoordinaten (mm, y nach oben) und Bildschirm (px, y nach unten).
 * Unveränderlich; Zoomen/Verschieben liefert eine neue Instanz.
 */
public class ViewTransform(
    /** Pixel pro Millimeter. */
    public val scale: Double = 4.0,
    /** Bildschirmposition des Skizzenursprungs. */
    public val originX: Double = 0.0,
    public val originY: Double = 0.0,
) {
    public fun toScreen(p: Vec2): Vec2 = Vec2(originX + p.x * scale, originY - p.y * scale)
    public fun toWorld(s: Vec2): Vec2 = Vec2((s.x - originX) / scale, (originY - s.y) / scale)

    /** Zoom um den Bildschirmpunkt [focus]; der Skizzenpunkt darunter bleibt an Ort und Stelle. */
    public fun zoomAt(focus: Vec2, factor: Double): ViewTransform {
        val ns = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val f = ns / scale
        return ViewTransform(ns, focus.x - (focus.x - originX) * f, focus.y - (focus.y - originY) * f)
    }

    public fun panned(dx: Double, dy: Double): ViewTransform = ViewTransform(scale, originX + dx, originY + dy)

    /** Rasterweite in mm (1-2-5-Reihe), so dass benachbarte Rasterlinien mindestens [minPx] auseinanderliegen. */
    public fun gridStep(minPx: Double = 24.0): Double {
        val raw = minPx / scale
        val base = 10.0.pow(floor(log10(raw)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * base }.first { it >= raw - 1e-12 }
    }

    /** Pixel → Millimeter. */
    public fun worldLength(px: Double): Double = px / scale

    public companion object {
        public const val MIN_SCALE: Double = 0.05
        public const val MAX_SCALE: Double = 400.0

        /** Ansicht, die den Bereich [min]…[max] in ein [width] × [height] großes Fenster einpasst. */
        public fun fit(min: Vec2, max: Vec2, width: Double, height: Double, marginPx: Double = 48.0): ViewTransform {
            val w = (max.x - min.x).coerceAtLeast(1.0)
            val h = (max.y - min.y).coerceAtLeast(1.0)
            val s = minOf((width - 2 * marginPx) / w, (height - 2 * marginPx) / h).coerceIn(MIN_SCALE, MAX_SCALE)
            val cx = (min.x + max.x) / 2; val cy = (min.y + max.y) / 2
            return ViewTransform(s, width / 2 - cx * s, height / 2 + cy * s)
        }
    }
}
