package dev.androidcad.model

import dev.androidcad.sketch.LengthDim
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

class SnapshotTest {
    @Test
    fun snapshotIsIndependentOfTheOriginal() {
        val doc = Document()
        val sk = doc.addSketch("S")
        val r = sk.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        val dim = sk.sketch.addLength(r.edges[0], 10.0)
        doc.addExtrude("E", sk, 5.0)

        val snap = doc.snapshot()
        sk.sketch.setDimension(dim, 40.0)        // Original ändern …
        snap.build().use { assertTrue(abs(it.body!!.volume() - 500.0) < 1e-6) }      // … die Kopie bleibt bei 10 × 10 × 5
        doc.build().use { assertTrue(abs(it.body!!.volume() - 40.0 * 10.0 * 5.0) < 1e-6) }

        // gleiche Skizzen-IDs, aber andere Objekte
        val copySketch = snap.features.filterIsInstance<SketchFeature>().single()
        assertTrue(copySketch.id == sk.id && copySketch.sketch !== sk.sketch)
        assertTrue((copySketch.sketch.constraint(dim) as LengthDim).value == 10.0)
    }
}
