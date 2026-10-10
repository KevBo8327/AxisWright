package dev.androidcad.geometry

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

    /**
     * 3MF-Export (3D-Druck): ZIP mit dem Netz als Modell in Millimetern. Punkte mit gleichen Koordinaten werden zusammengeführt – das
     * Netz aus OpenCascade hat je Fläche eigene Punkte, Slicer sähen sonst an jeder Flächengrenze offene Kanten.
     */
    fun toThreeMf(): ByteArray {
        val index = HashMap<Triple<Float, Float, Float>, Int>()
        val vertices = StringBuilder()
        val ids = IntArray(vertexCount) { i ->
            val key = Triple(positions[3 * i], positions[3 * i + 1], positions[3 * i + 2])
            index.getOrPut(key) {
                vertices.append("<vertex x=\"${key.first}\" y=\"${key.second}\" z=\"${key.third}\"/>\n")
                index.size
            }
        }
        val model = StringBuilder()
        model.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        model.append("<model unit=\"millimeter\" xml:lang=\"de-DE\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
        model.append("<resources><object id=\"1\" type=\"model\"><mesh><vertices>\n").append(vertices).append("</vertices><triangles>\n")
        for (t in 0 until triangleCount) {
            val a = ids[triangles[3 * t]]; val b = ids[triangles[3 * t + 1]]; val c = ids[triangles[3 * t + 2]]
            if (a == b || b == c || a == c) continue // durch das Zusammenführen entartet
            model.append("<triangle v1=\"$a\" v2=\"$b\" v3=\"$c\"/>\n")
        }
        model.append("</triangles></mesh></object></resources>\n<build><item objectid=\"1\"/></build>\n</model>\n")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>\n",
            )
            entry(
                "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>\n",
            )
            entry("3D/3dmodel.model", model.toString())
        }
        return out.toByteArray()
    }
}
