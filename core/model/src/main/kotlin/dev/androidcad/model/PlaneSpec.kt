package dev.androidcad.model

import kotlin.math.abs

/** Die drei Grundebenen, aus denen der Nutzer eine Skizzenebene wählt. */
enum class BasePlane(val title: String, val plane: Workplane) {
    XY("XY · Draufsicht", Workplane.XY),
    XZ("XZ · Vorderansicht", Workplane.XZ),
    YZ("YZ · Seitenansicht", Workplane.YZ),
}

/** Skizzenebene als Auswahl: Grundebene, entlang ihrer Normalen um [offset] mm verschoben. */
data class PlaneSpec(val base: BasePlane, val offset: Double = 0.0) {
    fun toWorkplane(): Workplane = if (offset == 0.0) base.plane else base.plane.offset(offset)

    /** Kurzbeschreibung, z. B. „XY“ oder „XZ +12,5 mm“. */
    val label: String
        get() = if (abs(offset) < 1e-9) base.name else "${base.name} ${if (offset > 0) "+" else "−"}${formatMm(abs(offset))} mm"

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

        private fun formatMm(v: Double): String {
            val r = Math.round(v * 100) / 100.0
            return (if (r == Math.floor(r)) r.toLong().toString() else r.toString()).replace('.', ',')
        }
    }
}
