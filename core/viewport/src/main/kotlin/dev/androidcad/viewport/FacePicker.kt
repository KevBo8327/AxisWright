package dev.androidcad.viewport

import kotlin.math.abs

/** Getroffene Fläche: [faceId] (1-basiert wie im B-Rep-Netz), Treffpunkt im Raum und Abstand vom Strahlursprung. */
public class FaceHit(public val faceId: Int, public val point: FloatArray, public val distance: Float)

/** Wählt Flächen per Antippen: Strahl durch den Tippunkt gegen das Dreiecksnetz, das nächste Dreieck gewinnt. */
public object FacePicker {
    /**
     * @param triangleFace Flächen-ID je Dreieck (Länge = `triangles.size / 3`)
     * @return die vorderste getroffene Fläche oder `null`, wenn der Tipp ins Leere geht
     */
    public fun pick(camera: OrthoCamera, tapX: Float, tapY: Float, positions: FloatArray, triangles: IntArray, triangleFace: IntArray): FaceHit? {
        val ray = camera.ray(tapX, tapY)
        val ox = ray.origin[0]; val oy = ray.origin[1]; val oz = ray.origin[2]
        val dx = ray.direction[0]; val dy = ray.direction[1]; val dz = ray.direction[2]
        var bestT = Float.MAX_VALUE
        var bestTri = -1
        for (t in triangles.indices step 3) {
            val a = 3 * triangles[t]; val b = 3 * triangles[t + 1]; val c = 3 * triangles[t + 2]
            val e1x = positions[b] - positions[a]; val e1y = positions[b + 1] - positions[a + 1]; val e1z = positions[b + 2] - positions[a + 2]
            val e2x = positions[c] - positions[a]; val e2y = positions[c + 1] - positions[a + 1]; val e2z = positions[c + 2] - positions[a + 2]
            val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12f) continue // Strahl parallel zum Dreieck
            val inv = 1f / det
            val tx = ox - positions[a]; val ty = oy - positions[a + 1]; val tz = oz - positions[a + 2]
            val u = (tx * px + ty * py + tz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
            val v = (dx * qx + dy * qy + dz * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (dist >= 0f && dist < bestT) { bestT = dist; bestTri = t / 3 }
        }
        if (bestTri < 0) return null
        return FaceHit(triangleFace[bestTri], floatArrayOf(ox + dx * bestT, oy + dy * bestT, oz + dz * bestT), bestT)
    }
}
