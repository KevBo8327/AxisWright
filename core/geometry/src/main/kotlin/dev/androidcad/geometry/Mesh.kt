package dev.androidcad.geometry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Dreiecksnetz: [positions] = x,y,z je Punkt; [triangles] = drei Punktindizes je Dreieck
 * (gegen den Uhrzeigersinn von außen gesehen, wie von Manifold geliefert).
 */
class Mesh(val positions: FloatArray, val triangles: IntArray) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = triangles.size / 3
    val isEmpty: Boolean get() = triangles.isEmpty()

    /** Volumen nach dem Divergenzsatz (Summe signierter Tetraeder-Volumina), in mm³. */
    fun volume(): Double {
        var v = 0.0
        for (t in 0 until triangleCount) {
            val a = 3 * triangles[3 * t]; val b = 3 * triangles[3 * t + 1]; val c = 3 * triangles[3 * t + 2]
            val ax = positions[a].toDouble(); val ay = positions[a + 1].toDouble(); val az = positions[a + 2].toDouble()
            val bx = positions[b].toDouble(); val by = positions[b + 1].toDouble(); val bz = positions[b + 2].toDouble()
            val cx = positions[c].toDouble(); val cy = positions[c + 1].toDouble(); val cz = positions[c + 2].toDouble()
            v += ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)
        }
        return v / 6.0
    }

    /** Achsenparallele Hülle als (min, max) je Achse; leeres Netz → `null`. */
    fun bounds(): Pair<DoubleArray, DoubleArray>? {
        if (positions.isEmpty()) return null
        val lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (i in positions.indices) {
            val k = i % 3
            val p = positions[i].toDouble()
            if (p < lo[k]) lo[k] = p
            if (p > hi[k]) hi[k] = p
        }
        return lo to hi
    }

    /** Binärer STL-Export (80-Byte-Header, little-endian). */
    fun toBinaryStl(): ByteArray {
        val buf = ByteBuffer.allocate(84 + 50 * triangleCount).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80)
        buf.putInt(triangleCount)
        for (t in 0 until triangleCount) {
            val a = 3 * triangles[3 * t]; val b = 3 * triangles[3 * t + 1]; val c = 3 * triangles[3 * t + 2]
            val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0f) { nx /= len; ny /= len; nz /= len }
            buf.putFloat(nx); buf.putFloat(ny); buf.putFloat(nz)
            for (i in intArrayOf(a, b, c)) { buf.putFloat(positions[i]); buf.putFloat(positions[i + 1]); buf.putFloat(positions[i + 2]) }
            buf.putShort(0)
        }
        return buf.array()
    }
}
