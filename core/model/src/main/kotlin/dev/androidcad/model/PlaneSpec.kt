package dev.androidcad.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/** Die drei Grundebenen, aus denen der Nutzer eine Skizzenebene wählt (Beschriftung „XY · Draufsicht“: App, `CoreText.kt`). */
enum class BasePlane(val plane: Workplane) {
    XY(Workplane.XY),
    XZ(Workplane.XZ),
    YZ(Workplane.YZ),
}

/** Skizzenebene als Auswahl: Grundebene, entlang ihrer Normalen um [offset] mm verschoben. */
data class PlaneSpec(val base: BasePlane, val offset: Double = 0.0) {
    fun toWorkplane(): Workplane = if (offset == 0.0) base.plane else base.plane.offset(offset)

    /** Kurzbeschreibung, z. B. „XY“ oder „XZ +12,5 mm“; das Dezimalzeichen folgt [locale] (Gerätesprache). */
    fun label(locale: Locale = Locale.getDefault()): String =
        if (abs(offset) < 1e-9) base.name else "${base.name} ${if (offset > 0) "+" else "−"}${formatMm(abs(offset), locale)} mm"

    companion object {
        /** Gegenstück zu [toWorkplane]; `null` für Ebenen, die keine verschobene Grundebene sind. */
        fun from(w: Workplane): PlaneSpec? {
            for (b in BasePlane.entries) {
                val p = b.plane
                if (!near(w.xDir, p.xDir) || !near(w.normal, p.normal)) continue
                val offset = w.origin.dot(p.normal)
                if (near(w.origin, p.normal * offset)) return PlaneSpec(b, offset)
            }
            return null
        }

        private fun near(a: Vec3, b: Vec3) = abs(a.x - b.x) < 1e-9 && abs(a.y - b.y) < 1e-9 && abs(a.z - b.z) < 1e-9

        private fun formatMm(v: Double, locale: Locale): String = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).apply { roundingMode = java.math.RoundingMode.HALF_UP }.format(v)
    }
}
