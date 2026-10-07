package dev.androidcad.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Die Kontur der Skizze als Linienpaare im Raum (`x1 y1 z1 x2 y2 z2 …`), gelegt auf ihre [SketchFeature.plane]: für die
 * Anzeige der Skizze im 3D-Bild. Konstruktionselemente werden ausgelassen; Kreise und Bögen werden mit [segmentsPerCircle]
 * Segmenten pro Vollkreis angenähert.
 */
fun SketchFeature.worldSegments(segmentsPerCircle: Int = 64): FloatArray {
    require(segmentsPerCircle >= 8) { "Mindestens 8 Segmente je Kreis" }
    val s = sketch
    val out = ArrayList<Float>()
    fun add(x1: Double, y1: Double, x2: Double, y2: Double) {
        val a = plane.toWorld(x1, y1); val b = plane.toWorld(x2, y2)
        out += listOf(a.x.toFloat(), a.y.toFloat(), a.z.toFloat(), b.x.toFloat(), b.y.toFloat(), b.z.toFloat())
    }
    fun polyline(cx: Double, cy: Double, r: Double, from: Double, sweep: Double) {
        val n = max(2, ceil(sweep / (2 * PI) * segmentsPerCircle).toInt())
        for (k in 0 until n) {
            val a0 = from + sweep * k / n; val a1 = from + sweep * (k + 1) / n
            add(cx + r * cos(a0), cy + r * sin(a0), cx + r * cos(a1), cy + r * sin(a1))
        }
    }
    for (id in s.lineIds) {
        val l = s.line(id)
        if (l.construction) continue
        val a = s.point(l.p1); val b = s.point(l.p2)
        add(a.x, a.y, b.x, b.y)
    }
    for (id in s.circleIds) {
        val c = s.circle(id)
        if (c.construction) continue
        val m = s.point(c.center)
        polyline(m.x, m.y, c.radius, 0.0, 2 * PI)
    }
    for (id in s.arcIds) {
        val a = s.arc(id)
        if (a.construction) continue
        val m = s.point(a.center); val p = s.point(a.start); val q = s.point(a.end)
        val a0 = atan2(p.y - m.y, p.x - m.x)
        var a1 = atan2(q.y - m.y, q.x - m.x)
        if (a1 <= a0) a1 += 2 * PI // Bögen laufen gegen den Uhrzeigersinn von Start nach Ende
        polyline(m.x, m.y, hypot(p.x - m.x, p.y - m.y), a0, a1 - a0)
    }
    return out.toFloatArray()
}
