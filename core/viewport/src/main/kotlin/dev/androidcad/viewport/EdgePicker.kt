package dev.androidcad.viewport

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Wählt Kanten per Antippen im 3D-Bild. Eine Kante ist eine Polylinie (aufeinanderfolgende Punkte in [polylines], je Kante
 * [counts] Punkte). Gewählt wird die dem Tippunkt (in Pixeln) nächste **sichtbare** Kante innerhalb von `radiusPx`;
 * verdeckte Kanten (auf der Rückseite oder hinter einer Fläche) werden mit einem Strahl gegen das Dreiecksnetz ausgeschlossen.
 */
public object EdgePicker {
    /** @return Index der Kante in [counts] oder −1, wenn nichts in Reichweite liegt. */
    public fun pick(
        camera: OrthoCamera,
        tapX: Float,
        tapY: Float,
        polylines: FloatArray,
        counts: IntArray,
        positions: FloatArray,
        triangles: IntArray,
        radiusPx: Float = 28f,
        clipPlane: ClipPlane? = null,
    ): Int {
        class Candidate(val edge: Int, val dist: Float, val point: FloatArray)
        val candidates = ArrayList<Candidate>()
        var offset = 0
        for ((edge, n) in counts.withIndex()) {
            var best = Float.MAX_VALUE
            var bestPoint: FloatArray? = null
            for (i in 0 until n - 1) {
                val a = 3 * (offset + i); val b = 3 * (offset + i + 1)
                val pa = camera.project(polylines[a], polylines[a + 1], polylines[a + 2])
                val pb = camera.project(polylines[b], polylines[b + 1], polylines[b + 2])
                val dx = pb.x - pa.x; val dy = pb.y - pa.y
                val len2 = dx * dx + dy * dy
                val t = if (len2 < 1e-9f) 0f else (((tapX - pa.x) * dx + (tapY - pa.y) * dy) / len2).coerceIn(0f, 1f)
                val d = hypot(tapX - (pa.x + t * dx), tapY - (pa.y + t * dy))
                val p = floatArrayOf(
                    polylines[a] + t * (polylines[b] - polylines[a]),
                    polylines[a + 1] + t * (polylines[b + 1] - polylines[a + 1]),
                    polylines[a + 2] + t * (polylines[b + 2] - polylines[a + 2]),
                )
                if (clipPlane != null && clipPlane.isClipped(p[0], p[1], p[2])) continue
                if (d < best) {
                    best = d
                    bestPoint = p
                }
            }
            if (bestPoint != null && best <= radiusPx) candidates += Candidate(edge, best, bestPoint)
            offset += n
        }
        candidates.sortBy { it.dist }
        val eps = 0.15f + 0.003f * camera.frame.radius // Tessellierung: Kantenpunkte liegen minimal neben den Flächendreiecken
        return candidates.firstOrNull { !isOccluded(it.point, camera.towardViewer, positions, triangles, eps, clipPlane) }?.edge ?: -1
    }

    /** `true`, wenn zwischen [p] und dem Betrachter (Richtung [toViewer]) ein Dreieck liegt. */
    public fun isOccluded(
        p: FloatArray,
        toViewer: FloatArray,
        positions: FloatArray,
        triangles: IntArray,
        eps: Float,
        clipPlane: ClipPlane? = null,
    ): Boolean {
        val dx = toViewer[0]; val dy = toViewer[1]; val dz = toViewer[2]
        for (t in triangles.indices step 3) {
            val a = 3 * triangles[t]; val b = 3 * triangles[t + 1]; val c = 3 * triangles[t + 2]
            val e1x = positions[b] - positions[a]; val e1y = positions[b + 1] - positions[a + 1]; val e1z = positions[b + 2] - positions[a + 2]
            val e2x = positions[c] - positions[a]; val e2y = positions[c + 1] - positions[a + 1]; val e2z = positions[c + 2] - positions[a + 2]
            // Möller–Trumbore
            val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12f) continue
            val inv = 1f / det
            val tx = p[0] - positions[a]; val ty = p[1] - positions[a + 1]; val tz = p[2] - positions[a + 2]
            val u = (tx * px + ty * py + tz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
            val v = (dx * qx + dy * qy + dz * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (dist > eps) {
                if (clipPlane != null) {
                    val hx = p[0] + dx * dist
                    val hy = p[1] + dy * dist
                    val hz = p[2] + dz * dist
                    if (clipPlane.isClipped(hx, hy, hz)) continue
                }
                return true
            }
        }
        return false
    }

    /** Länge eines Richtungsvektors – Hilfe für Tests. */
    internal fun length(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
