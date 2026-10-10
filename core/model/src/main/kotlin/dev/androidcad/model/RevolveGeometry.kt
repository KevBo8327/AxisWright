package dev.androidcad.model

import dev.androidcad.sketch.Loop
import dev.androidcad.sketch.LoopSegment
import dev.androidcad.sketch.Region
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** Geometrie zum Drehen ([RevolveFeature]): Lage der Achse in der Skizze und die Prüfung, dass das Profil sie nicht schneidet. */
object RevolveGeometry {
    /** Achse in Skizzenkoordinaten: ein Punkt und die Richtung (Einheitsvektor). */
    data class Axis(val point: Vec2, val direction: Vec2)

    /** Lage der Achse in der (gelösten) Skizze; `null`, wenn die Linie nicht mehr existiert oder die Länge 0 hat. */
    fun resolve(sketch: Sketch, axis: RevolveAxis): Axis? = when (axis) {
        RevolveAxis.SketchX -> Axis(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        RevolveAxis.SketchY -> Axis(Vec2(0.0, 0.0), Vec2(0.0, 1.0))
        is RevolveAxis.Line -> {
            if (axis.line !in sketch.lineIds) null
            else {
                val l = sketch.line(axis.line)
                val a = sketch.point(l.p1); val b = sketch.point(l.p2)
                val len = hypot(b.x - a.x, b.y - a.y)
                if (len < 1e-9) null else Axis(Vec2(a.x, a.y), Vec2((b.x - a.x) / len, (b.y - a.y) / len))
            }
        }
    }

    /**
     * Warum sich [regions] nicht um [axis] drehen lassen (`null` = geht): Jede Fläche muss ganz auf einer Seite der Achse liegen –
     * berühren (eine Kante oder ein Punkt auf der Achse) ist erlaubt, schneiden nicht. Verschiedene Flächen dürfen auf verschiedenen
     * Seiten liegen.
     */
    fun problem(regions: List<Region>, axis: Axis): String? {
        for (r in regions) {
            val (lo, hi) = range(r.outer, axis)
            if (lo < -EPS && hi > EPS) return "Die Fläche schneidet die Drehachse – sie muss ganz auf einer Seite liegen (berühren ist erlaubt)"
            if (hi - lo <= EPS) return "Die Fläche liegt auf der Drehachse"
        }
        return null
    }

    /** Kleinster und größter vorzeichenbehafteter Abstand der Kontur von der Achse (positiv links der Achsrichtung). */
    private fun range(loop: Loop, axis: Axis): Pair<Double, Double> {
        val nx = -axis.direction.y; val ny = axis.direction.x
        fun d(x: Double, y: Double) = (x - axis.point.x) * nx + (y - axis.point.y) * ny
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        fun take(v: Double) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        for (s in loop.segments) when (s) {
            is LoopSegment.Line -> { take(d(s.x1, s.y1)); take(d(s.x2, s.y2)) }
            is LoopSegment.Circle -> { val c = d(s.cx, s.cy); take(c - s.r); take(c + s.r) }
            is LoopSegment.Arc -> {
                take(d(s.x1, s.y1)); take(d(s.x2, s.y2))
                // Extremwerte im Inneren des Bogens: dort, wo der Radius parallel zur Achsnormalen steht
                val r = hypot(s.x1 - s.cx, s.y1 - s.cy)
                val a1 = atan2(s.y1 - s.cy, s.x1 - s.cx); val a2 = atan2(s.y2 - s.cy, s.x2 - s.cx)
                for (t in doubleArrayOf(atan2(ny, nx), atan2(-ny, -nx))) {
                    if (onArc(t, a1, a2, s.ccw)) take(d(s.cx + r * kotlin.math.cos(t), s.cy + r * kotlin.math.sin(t)))
                }
            }
        }
        return lo to hi
    }

    /** Liegt der Winkel [t] auf dem Bogen von [a1] nach [a2] (in Richtung [ccw])? */
    private fun onArc(t: Double, a1: Double, a2: Double, ccw: Boolean): Boolean {
        fun norm(a: Double) = ((a % (2 * PI)) + 2 * PI) % (2 * PI)
        return if (ccw) norm(t - a1) <= norm(a2 - a1) else norm(a1 - t) <= norm(a1 - a2)
    }

    private const val EPS = 1e-6
}
