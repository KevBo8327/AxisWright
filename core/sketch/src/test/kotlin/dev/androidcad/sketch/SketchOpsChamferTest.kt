package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SketchOpsChamferTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    @Test
    fun chamferingARectangleCornerAddsALineOfLengthASqrt2AndStaysClosed() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        s.add(LengthDim(rect.edges[0], 20.0))
        val r = SketchOps.chamfer(s, rect.corners[1], 3.0)
        assertTrue(r.ok, r.message ?: "")
        assertFalse(s.exists(rect.corners[1]))
        assertEquals(5, s.lineIds.size)
        assertTrue(s.solve().isSolved, "Skizze muss lösbar bleiben")
        val edge = s.lineIds.single { it !in rect.edges }
        val (a, b) = s.line(edge).let { s.point(it.p1) to s.point(it.p2) }
        near(3.0 * sqrt(2.0), hypot(a.x - b.x, a.y - b.y))
        // gekürztes Längenmaß entfällt, die Fase hat ihr eigenes
        assertTrue(s.constraintEntries.none { (it.constraint as? LengthDim)?.line == rect.edges[0] })
        assertTrue(s.constraintEntries.any { (it.constraint as? LengthDim)?.line == edge })
        // geschlossene Kontur: Fläche des Rechtecks ohne das abgeschnittene Dreieck
        near(200.0 - 4.5, s.regions().regions.single().area, 1e-6)
    }

    @Test
    fun chamferFailures() {
        val big = Sketch()
        val rect = big.addRectangle(0.0, 0.0, 20.0, 10.0)
        assertFalse(SketchOps.chamfer(big, rect.corners[0], 10.0).ok, "Abstand zu groß")
        assertFalse(SketchOps.chamfer(big, rect.corners[0], 0.0).ok)
        assertEquals(4, big.lineIds.size)
        val straight = Sketch()
        val m = straight.addPoint(0.0, 0.0)
        straight.addLine(m, straight.addPoint(5.0, 0.0)); straight.addLine(m, straight.addPoint(-5.0, 0.0))
        assertFalse(SketchOps.chamfer(straight, m, 1.0).ok, "gerade Linie hat keine Ecke")
    }
}
