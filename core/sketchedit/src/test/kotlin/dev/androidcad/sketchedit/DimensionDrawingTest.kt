package dev.androidcad.sketchedit

import dev.androidcad.sketch.LengthDim
import dev.androidcad.sketch.RadiusDim
import dev.androidcad.sketch.Sketch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Maße werden wie in der Zeichnung dargestellt: Maßhilfslinien, Maßlinie, Pfeile nach außen, Zahl auf der Maßlinie. */
class DimensionDrawingTest {
    @Test
    fun lengthHasExtensionLinesAndOutwardArrows() {
        val sketch = Sketch()
        val line = sketch.addLine(0.0, 0.0, 30.0, 0.0)
        sketch.add(LengthDim(line, 30.0))
        val e = SketchEditor(sketch).apply { setView(ViewTransform(10.0, 100.0, 300.0)) }
        val l = e.dimensionLabels().single()
        assertEquals(3, l.lines.size, "zwei Hilfslinien und die Maßlinie")
        val (dimA, dimB) = l.lines.last()
        assertTrue(dimA.y < 300 && abs(dimA.y - dimB.y) < 1e-9, "Maßlinie parallel über der Linie: $dimA $dimB")
        assertEquals(listOf(-1.0, 1.0), l.arrows.map { Math.round(it.second.x).toDouble() }, "Pfeile zeigen nach außen")
        assertTrue(abs(l.center.y - dimA.y) < 1e-9, "Maßzahl steht auf der Maßlinie")
    }

    @Test
    fun radiusArrowEndsOnTheCircle() {
        val sketch = Sketch()
        val c = sketch.addCircle(0.0, 0.0, 5.0)
        sketch.add(RadiusDim(c, 5.0))
        val e = SketchEditor(sketch).apply { setView(ViewTransform(10.0, 100.0, 300.0)) }
        val tip = e.dimensionLabels().single().arrows.single().first
        assertTrue(abs(kotlin.math.hypot(tip.x - 100, tip.y - 300) - 50) < 1e-6, "Pfeilspitze auf dem Kreis: $tip")
    }
}
