package dev.androidcad.geometry

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidcad.lang.CadLang
import kotlin.math.abs
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Gate A: Jedes Teil der Streaminghalterung wird mit dem eigenen Kern + Manifold gerendert und mit
 * dem Volumen des von **OpenSCAD** erzeugten STL verglichen (samples/reference).
 */
class ReferenceModelTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun text(path: String) = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Volumen eines ASCII-STL (Divergenzsatz). */
    private fun stlVolume(path: String): Double {
        val v = ArrayList<Double>()
        assets.open(path).bufferedReader().useLines { lines ->
            for (l in lines) {
                val t = l.trim()
                if (t.startsWith("vertex")) t.split(Regex("\\s+")).drop(1).forEach { v += it.toDouble() }
            }
        }
        var vol = 0.0
        var i = 0
        while (i + 8 < v.size) {
            val (ax, ay, az) = Triple(v[i], v[i + 1], v[i + 2])
            val (bx, by, bz) = Triple(v[i + 3], v[i + 4], v[i + 5])
            val (cx, cy, cz) = Triple(v[i + 6], v[i + 7], v[i + 8])
            vol += ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)
            i += 9
        }
        return abs(vol / 6.0)
    }

    private val parts = listOf(
        "chest_plate" to "brustplatte",
        "arm" to "arm",
        "phone_base" to "phone_base",
        "phone_slider" to "phone_slider",
        "phone_bracket" to "phone_bracket",
        "joint_bolt" to "gelenkschraube_m12",
        "joint_nut" to "fluegelmutter_m12",
        "clamp_screw" to "klemmschraube_m10",
    )

    @Test
    fun everyPartMatchesOpenScadVolume() {
        val src = text("streaming_halterung.scad")
        val report = StringBuilder()
        var worst = 0.0
        for ((mode, file) in parts) {
            val t0 = System.nanoTime()
            val result = CadLang.render(src, mapOf("mode" to "\"$mode\""))
            val t1 = System.nanoTime()
            val mesh = GeometryKernel.build(result.root)
            val t2 = System.nanoTime()
            val expected = stlVolume("reference/streaming_halterung_$file.stl")
            val actual = mesh.volume()
            val dev = abs(actual - expected) / expected
            worst = maxOf(worst, dev)
            report.append(
                "%-14s OpenSCAD=%10.1f  Manifold=%10.1f  Abw=%.3f%%  eval=%d ms  geometrie=%d ms  tris=%d\n".format(
                    mode, expected, actual, dev * 100, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, mesh.triangleCount,
                ),
            )
        }
        Log.i("GateA", "\n$report")
        assertTrue(worst < 0.01, "Volumenabweichung > 1 %:\n$report")
    }

    @Test
    fun assemblyAndPrintLayoutRender() {
        val src = text("streaming_halterung.scad")
        for (mode in listOf("assembly", "print_layout")) {
            val t0 = System.nanoTime()
            val mesh = GeometryKernel.build(CadLang.render(src, mapOf("mode" to "\"$mode\"")).root)
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i("GateA", "$mode: ${mesh.triangleCount} Dreiecke, $ms ms gesamt")
            assertTrue(!mesh.isEmpty, "$mode liefert kein Netz")
        }
    }
}
