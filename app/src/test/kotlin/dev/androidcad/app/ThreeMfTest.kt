package dev.androidcad.app

import dev.androidcad.geometry.Mesh
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreeMfTest {
    /** Würfel 10 mm wie aus OpenCascade: jede der 6 Flächen mit eigenen 4 Punkten (24 Punkte, 12 Dreiecke). */
    private fun cube(): Mesh {
        val pos = ArrayList<Float>(); val tri = ArrayList<Int>()
        // je Fläche: Achse, Seite (0/10); Ecken gegen den Uhrzeigersinn von außen
        for (axis in 0..2) for (side in listOf(0f, 10f)) {
            val u = (axis + 1) % 3; val v = (axis + 2) % 3
            val corners = listOf(0f to 0f, 10f to 0f, 10f to 10f, 0f to 10f).let { if (side == 0f) it.reversed() else it }
            val base = pos.size / 3
            for ((a, b) in corners) { val p = FloatArray(3); p[axis] = side; p[u] = a; p[v] = b; pos += p.toList() }
            tri += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        return Mesh(pos.toFloatArray(), tri.toIntArray())
    }

    @Test
    fun packageHasModelInMillimetresWithWeldedVertices() {
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(cube().toThreeMf())).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; entries[e.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(listOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model"), entries.keys.toList())
        val model = entries.getValue("3D/3dmodel.model")
        assertTrue(model.contains("unit=\"millimeter\""))
        assertEquals(8, Regex("<vertex ").findAll(model).count(), "gleiche Punkte zusammengeführt")
        assertEquals(12, Regex("<triangle ").findAll(model).count())
        // geschlossen: jede Kante gehört zu genau zwei Dreiecken, in entgegengesetzter Richtung
        val edges = HashMap<Pair<Int, Int>, Int>()
        Regex("""v1="(\d+)" v2="(\d+)" v3="(\d+)"""").findAll(model).forEach { m ->
            val (a, b, c) = m.destructured.toList().map { it.toInt() }
            for ((p, q) in listOf(a to b, b to c, c to a)) edges.merge(p to q, 1, Int::plus)
        }
        assertTrue(edges.all { (k, n) -> n == 1 && edges[k.second to k.first] == 1 }, "Netz ist geschlossen und gleich orientiert")
    }
}
