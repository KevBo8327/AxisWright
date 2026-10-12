package dev.androidcad.sketchedit

import dev.androidcad.sketch.LengthDim
import dev.androidcad.sketch.RadiusDim
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
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

    /** Platte wie im Beispiel: Breite an der Unterkante, Lage einer Bohrung waagrecht zur Ecke. */
    private fun plate(): Pair<Sketch, Sketch.Rectangle> {
        val sketch = Sketch()
        val r = sketch.addRectangle(0.0, 0.0, 80.0, 50.0)
        sketch.add(LengthDim(r.edges[0], 80.0))
        val hole = sketch.addCircle(10.0, 10.0, 3.0)
        sketch.add(dev.androidcad.sketch.HorizontalDistanceDim(r.corners[0], sketch.circle(hole).center, 10.0))
        return sketch to r
    }

    @Test
    fun dimensionsSitOutsideTheShape() {
        val (sketch, _) = plate()
        val e = SketchEditor(sketch).apply { setView(ViewTransform(10.0, 100.0, 700.0)) }
        val bottomY = 700.0 // Unterkante y = 0
        val (width, offset) = e.dimensionLabels().partition { it.text == "80" }
        assertTrue(width.single().center.y > bottomY, "Breite unter der Unterkante, nicht in der Platte: ${width.single().center}")
        assertTrue(offset.single().center.y > bottomY, "Lage der Bohrung unten (sie liegt in der unteren Hälfte): ${offset.single().center}")
    }

    @Test
    fun fitKeepsDimensionsInView() {
        val (sketch, _) = plate()
        val e = SketchEditor(sketch)
        e.fit(800.0, 600.0)
        for (l in e.dimensionLabels()) {
            val pts = l.lines.flatMap { listOf(it.first, it.second) } + listOf(Vec2(l.center.x, l.center.y + l.halfHeight))
            assertTrue(pts.all { it.x in 0.0..800.0 && it.y in 0.0..600.0 }, "Maß „${l.text}“ ragt aus dem Bild: $pts")
        }
    }
}
