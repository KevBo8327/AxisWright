package dev.androidcad.model

import kotlin.math.abs

/**
 * Position einer Bohrung über **zwei Maße von geraden Kanten** der Fläche, statt als fester Raumpunkt: „[distance] mm von dieser Kante“.
 * Ändert sich das Bauteil, wandert die Bohrung mit den Kanten mit.
 *
 * Der Abstand ist **vorzeichenbehaftet** und bezieht sich auf die Richtung `Außennormale × Kantenrichtung`, wobei die Kantenrichtung
 * kanonisch festgelegt ist (vom lexikografisch kleineren zum größeren Endpunkt) – so hängt das Vorzeichen nicht davon ab, wie
 * OpenCascade die Kante gerade orientiert.
 */
data class EdgeDistance(val edge: EdgeRef, val distance: Double)

/** Reine Geometrie für [EdgeDistance]; ohne native Abhängigkeiten testbar. */
object HolePlacement {
    private const val EPS = 1e-9

    private fun minus(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)

    /** Kantenanfang und Einheitsrichtung in kanonischer Orientierung (kleinerer Endpunkt zuerst: x, dann y, dann z). */
    fun canonical(start: Vec3, end: Vec3): Pair<Vec3, Vec3> {
        val swap = when {
            abs(start.x - end.x) > EPS -> start.x > end.x
            abs(start.y - end.y) > EPS -> start.y > end.y
            else -> start.z > end.z
        }
        val a = if (swap) end else start
        val b = if (swap) start else end
        return a to minus(b, a).normalized()
    }

    /** In der Ebene liegende Einheitsnormale der Kante (zeigt „nach links“ zur Kantenrichtung, von außen auf die Fläche gesehen). */
    fun inPlaneNormal(planeNormal: Vec3, edgeDir: Vec3): Vec3 = (planeNormal cross edgeDir).normalized()

    /** Vorzeichenbehafteter Abstand von [point] zur Geraden durch [edgeStart]–[edgeEnd] in der Ebene mit [planeNormal]. */
    fun signedDistance(planeNormal: Vec3, point: Vec3, edgeStart: Vec3, edgeEnd: Vec3): Double {
        val (a, dir) = canonical(edgeStart, edgeEnd)
        return minus(point, a).dot(inPlaneNormal(planeNormal, dir))
    }

    /** Gerade Kante mit ihren Endpunkten. */
    class Line(val start: Vec3, val end: Vec3)

    /**
     * Punkt in der Ebene ([planeOrigin], [planeNormal]), der von [e1] den Abstand [d1] und von [e2] den Abstand [d2] hat
     * (Vorzeichen wie bei [signedDistance]). `null`, wenn die Kanten (nahezu) parallel sind.
     */
    fun solve(planeOrigin: Vec3, planeNormal: Vec3, e1: Line, d1: Double, e2: Line, d2: Double): Vec3? {
        val n = planeNormal.normalized()
        val helper = if (abs(n.z) < 0.9) Vec3(0.0, 0.0, 1.0) else Vec3(1.0, 0.0, 0.0)
        val u = (n cross helper).normalized()
        val v = n cross u
        val (a1, dir1) = canonical(e1.start, e1.end)
        val (a2, dir2) = canonical(e2.start, e2.end)
        val m1 = inPlaneNormal(n, dir1); val m2 = inPlaneNormal(n, dir2)
        // m_i · (o + s·u + t·v − a_i) = d_i
        val r1 = d1 - m1.dot(minus(planeOrigin, a1)); val r2 = d2 - m2.dot(minus(planeOrigin, a2))
        val m11 = m1.dot(u); val m12 = m1.dot(v); val m21 = m2.dot(u); val m22 = m2.dot(v)
        val det = m11 * m22 - m12 * m21
        if (abs(det) < 1e-6) return null
        val s = (r1 * m22 - m12 * r2) / det
        val t = (m11 * r2 - m21 * r1) / det
        return planeOrigin + u * s + v * t
    }
}
