package dev.androidcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplitTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")
    private fun near(expected: Vec2, actual: Vec2, tol: Double = 1e-6) { near(expected.x, actual.x, tol); near(expected.y, actual.y, tol) }
    private fun Sketch.solved() = assertTrue(solve(SolveOptions(analyze = true)).isSolved, "Skizze muss lösbar bleiben")

    @Test
    fun splittingALineGivesTwoLinesWithASharedPointAndKeepsHorizontal() {
        val s = Sketch()
        val h = s.addLine(0.0, 0.0, 20.0, 0.0)
        s.add(Horizontal(h))
        s.add(LengthDim(h, 20.0))
        assertTrue(SketchOps.split(s, h, Vec2(8.0, 0.3)).ok)
        assertEquals(2, s.lineIds.size)
        val second = s.lineIds.single { it != h }
        assertEquals(s.line(h).p2, s.line(second).p1) // gemeinsamer Punkt
        near(Vec2(8.0, 0.0), s.point(s.line(h).p2))
        near(20.0, s.length(h) + s.length(second))
        assertTrue(s.constraintEntries.any { (it.constraint as? Horizontal)?.line == h })
        assertTrue(s.constraintEntries.any { (it.constraint as? Horizontal)?.line == second })
        assertTrue(s.constraintEntries.none { it.constraint is LengthDim })
        s.solved()
        near(Vec2(20.0, 0.0), s.point(s.line(second).p2))
    }

    @Test
    fun aSlantedLineStaysStraightAndPointsMoveToTheMatchingPart() {
        val s = Sketch()
        val l = s.addLine(0.0, 0.0, 10.0, 10.0)
        val p = s.addPoint(8.0, 8.0)
        s.add(PointOnLine(p, l))
        assertTrue(SketchOps.split(s, l, Vec2(5.0, 5.0)).ok)
        val second = s.lineIds.single { it != l }
        assertTrue(s.constraintEntries.any { it.constraint == Collinear(l, second) })
        assertTrue(s.constraintEntries.any { it.constraint == PointOnLine(p, second) })
        s.solved()
        // Ziehen am gemeinsamen Punkt knickt die Linie nicht ab
        s.dragPoint(s.line(l).p2, 5.0, 6.0)
        val a = s.point(s.line(l).p1); val m = s.point(s.line(l).p2); val b = s.point(s.line(second).p2)
        near(0.0, (m.x - a.x) * (b.y - a.y) - (m.y - a.y) * (b.x - a.x), 1e-6)
    }

    @Test
    fun splittingAtTheEndFails() {
        val s = Sketch()
        val l = s.addLine(0.0, 0.0, 10.0, 0.0)
        assertFalse(SketchOps.split(s, l, Vec2(-1.0, 0.0)).ok)
    }

    @Test
    fun splittingAnArcGivesTwoArcsOnTheSameCircle() {
        val s = Sketch()
        val a = s.addArc(0.0, 0.0, 10.0, 0.0, -10.0, 0.0) // oberer Halbkreis
        s.add(RadiusDim(a, 10.0))
        assertTrue(SketchOps.split(s, a, Vec2(0.0, 12.0)).ok)
        assertEquals(2, s.arcIds.size)
        val second = s.arcIds.single { it != a }
        assertEquals(s.arc(a).end, s.arc(second).start)
        near(Vec2(0.0, 10.0), s.point(s.arc(a).end))
        near(Vec2(-10.0, 0.0), s.point(s.arc(second).end))
        s.solved()
        near(10.0, s.radiusOf(second))
    }

    @Test
    fun splittingACircleGivesTwoHalfArcsAndKeepsTheRadiusAndTangents() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 5.0)
        s.add(DiameterDim(c, 10.0))
        val t = s.addLine(-10.0, -5.0, 10.0, -5.0) // berührt unten
        s.add(TangentLineRound(t, c))
        assertTrue(SketchOps.split(s, c, Vec2(5.0, 0.0)).ok)
        assertTrue(s.circleIds.isEmpty())
        assertEquals(2, s.arcIds.size)
        val lower = s.arcIds.single { s.point(s.arc(it).start).x < 0 } // von links gegen den Uhrzeigersinn nach rechts
        assertTrue(s.constraintEntries.any { it.constraint == TangentLineRound(t, lower) })
        assertTrue(s.constraintEntries.any { it.constraint is DiameterDim })
        s.solved()
        val r = s.regions()
        assertEquals(1, r.regions.size, r.problems.toString())
        near(PI * 25, r.regions[0].area, 1e-6)
    }

    @Test
    fun splittingARectangleEdgeKeepsOneClosedRegion() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        assertTrue(SketchOps.split(s, rect.edges[0], Vec2(15.0, 0.0)).ok)
        assertEquals(5, s.lineIds.size)
        s.solved()
        val r = s.regions()
        assertEquals(1, r.regions.size, r.problems.toString())
        near(800.0, r.regions[0].area)
        assertEquals(5, r.regions[0].outer.segments.size)
    }
}
