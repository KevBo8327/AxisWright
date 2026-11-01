package dev.androidcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DragAndRegionsTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6, msg: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$msg erwartet $expected, erhalten $actual")

    // ---- Ziehen ---------------------------------------------------------------------------------

    @Test
    fun dragRectangleCornerKeepsFixedWidth() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        s.add(LengthDim(r.edges[0], 40.0))
        assertTrue(s.solve().isSolved)
        val res = s.dragPoint(r.corners[2], 60.0, 35.0) // Ziel liegt außerhalb dessen, was die Breite erlaubt
        assertTrue(res.isSolved, res.toString())
        // x ist durch die Breite festgelegt, y folgt der Maus
        near(40.0, s.point(r.corners[2]).x); near(35.0, s.point(r.corners[2]).y)
        near(0.0, s.point(r.corners[3]).x); near(35.0, s.point(r.corners[3]).y)
        near(40.0, s.point(r.corners[1]).x); near(0.0, s.point(r.corners[1]).y)
    }

    @Test
    fun freePointFollowsTheMouseExactly() {
        val s = Sketch()
        val p = s.addPoint(1.0, 1.0)
        assertTrue(s.dragPoint(p, 3.0, 4.0).isSolved)
        near(3.0, s.point(p).x); near(4.0, s.point(p).y)
    }

    @Test
    fun crankStaysOnItsCircleWhileDragging() {
        val s = Sketch()
        val p = s.addPoint(10.0, 0.0)
        s.add(DistanceDim(s.origin, p, 10.0))
        val drag = s.beginDrag()
        // Maus fährt auf einer Bahn außerhalb des Kreises; der Punkt muss immer genau 10 mm vom Ursprung bleiben
        for (step in 0..40) {
            val angle = step * PI / 20
            val res = drag.dragTo(mapOf(p to Vec2(25 * kotlin.math.cos(angle), 25 * kotlin.math.sin(angle))))
            assertTrue(res.isSolved, "Schritt $step: $res")
            near(10.0, hypot(s.point(p).x, s.point(p).y), 1e-7, "Schritt $step Abstand")
            // und er zeigt zur Maus: Winkel zwischen Punkt- und Mausvektor ≈ 0
            val mx = 25 * kotlin.math.cos(angle); val my = 25 * kotlin.math.sin(angle)
            val cross = s.point(p).x * my - s.point(p).y * mx
            val dot = s.point(p).x * mx + s.point(p).y * my
            near(0.0, kotlin.math.atan2(cross, dot), 1e-4, "Schritt $step Richtung")
        }
        drag.finish()
    }

    @Test
    fun dragChainKeepsLinkLengths() {
        // Zweigliedriger Arm: Ursprung – A – B, beide Glieder 10 mm; B wird gezogen
        val s = Sketch()
        val a = s.addPoint(10.0, 0.0); val b = s.addPoint(20.0, 0.0)
        s.add(DistanceDim(s.origin, a, 10.0)); s.add(DistanceDim(a, b, 10.0))
        val drag = s.beginDrag()
        for ((tx, ty) in listOf(15.0 to 5.0, 12.0 to 12.0, 0.0 to 15.0, -8.0 to 8.0)) {
            val res = drag.dragTo(mapOf(b to Vec2(tx, ty)))
            assertTrue(res.isSolved, res.toString())
            near(10.0, hypot(s.point(a).x, s.point(a).y), 1e-7)
            near(10.0, hypot(s.point(b).x - s.point(a).x, s.point(b).y - s.point(a).y), 1e-7)
            // Ziel liegt in Reichweite (≤ 20 mm) → Punkt erreicht es
            near(tx, s.point(b).x, 1e-4); near(ty, s.point(b).y, 1e-4)
        }
        drag.finish()
    }

    @Test
    fun cancelRestoresStartPositions() {
        val s = Sketch()
        val p = s.addPoint(5.0, 5.0)
        val drag = s.beginDrag()
        drag.dragTo(mapOf(p to Vec2(9.0, 9.0)))
        drag.cancel()
        assertEquals(Vec2(5.0, 5.0), s.point(p))
    }

    @Test
    fun finishReportsDegreesOfFreedom() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        val drag = s.beginDrag()
        drag.dragTo(mapOf(r.corners[2] to Vec2(50.0, 30.0)))
        val res = drag.finish()
        assertEquals(2, res.degreesOfFreedom) // Breite und Höhe frei
    }

    // ---- Regionen -------------------------------------------------------------------------------

    @Test
    fun rectangleIsOneRegion() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 40.0, 20.0)
        val r = s.regions()
        assertEquals(1, r.regions.size, r.problems.toString())
        near(800.0, r.regions[0].area)
        assertEquals(4, r.regions[0].outer.segments.size)
        assertTrue(r.regions[0].holes.isEmpty())
        assertClosed(r.regions[0].outer)
    }

    @Test
    fun circleInsideRectangleIsAHole() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 40.0, 20.0)
        s.addCircle(20.0, 10.0, 4.0)
        val r = s.regions()
        assertEquals(1, r.regions.size)
        assertEquals(1, r.regions[0].holes.size)
        near(800.0 - PI * 16, r.regions[0].area, 1e-9)
    }

    @Test
    fun nestedLoopsAlternateRegionsAndHoles() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 100.0, 100.0)
        s.addRectangle(10.0, 10.0, 90.0, 90.0)   // Loch
        s.addRectangle(30.0, 30.0, 70.0, 70.0)   // Insel im Loch → eigene Region
        val r = s.regions()
        assertEquals(2, r.regions.size, r.problems.toString())
        assertEquals(1, r.regions[0].holes.size)
        assertEquals(0, r.regions[1].holes.size)
        near(10000.0 - 6400.0, r.regions[0].area, 1e-9)
        near(1600.0, r.regions[1].area, 1e-9)
    }

    @Test
    fun disjointShapesAreSeparateRegions() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.addRectangle(50.0, 0.0, 60.0, 10.0)
        s.addCircle(100.0, 5.0, 3.0)
        assertEquals(3, s.regions().regions.size)
    }

    @Test
    fun slotWithArcsFormsAClosedLoop() {
        val s = Sketch()
        val a = s.addPoint(0.0, -5.0); val b = s.addPoint(30.0, -5.0)
        val c = s.addPoint(30.0, 5.0); val d = s.addPoint(0.0, 5.0)
        val e = s.addPoint(30.0, 0.0); val f = s.addPoint(0.0, 0.0)
        s.addLine(a, b); s.addLine(c, d)
        s.addArc(e, b, c)  // rechter Halbkreis (gegen den Uhrzeigersinn von unten nach oben)
        s.addArc(f, d, a)  // linker Halbkreis
        val r = s.regions()
        assertEquals(1, r.regions.size, r.problems.toString())
        near(300.0 + PI * 25, r.regions[0].area, 1e-9)
        assertEquals(4, r.regions[0].outer.segments.size)
        assertClosed(r.regions[0].outer)
        assertEquals(2, r.regions[0].outer.segments.filterIsInstance<LoopSegment.Arc>().size)
    }

    @Test
    fun arcTraversedBackwardIsClockwise() {
        val s = Sketch()
        // Halbkreis-Bogen und Sehne, die den Bogen von „hinten“ anlaufen: Reihenfolge der Kanten erzwingt Rückwärtslauf
        val a = s.addPoint(-5.0, 0.0); val b = s.addPoint(5.0, 0.0); val c = s.addPoint(0.0, 0.0)
        s.addLine(a, b)
        s.addArc(c, b, a) // von (5,0) nach (-5,0) gegen den Uhrzeigersinn = oberer Halbkreis
        val r = s.regions()
        assertEquals(1, r.regions.size, r.problems.toString())
        near(PI * 25 / 2, r.regions[0].area, 1e-9)
        assertClosed(r.regions[0].outer)
    }

    @Test
    fun openChainsAndBranchesAreReported() {
        val s = Sketch()
        val q = s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.remove(q.edges[3])                     // linke Kante entfernen → offen
        val open = s.regions()
        assertTrue(open.regions.isEmpty())
        assertTrue(open.problems.any { it.key == SketchText.OPEN_END }, open.problems.toString())

        val t = Sketch()
        val r = t.addRectangle(0.0, 0.0, 10.0, 10.0)
        t.addLine(t.addPoint(5.0, 0.0).also { t.add(PointOnLine(it, r.edges[0])) }, t.addPoint(5.0, -5.0))
        val branch = t.regions()
        assertTrue(branch.problems.isNotEmpty())
    }

    @Test
    fun coincidentConstraintsJoinSeparateLines() {
        val s = Sketch()
        val p = listOf(s.addLine(0.0, 0.0, 10.0, 0.0), s.addLine(10.0, 0.0, 10.0, 10.0), s.addLine(10.0, 10.0, 0.0, 10.0), s.addLine(0.0, 10.0, 0.0, 0.0))
        // Endpunkte liegen aufeinander (Abstand 0) → Knoten werden automatisch zusammengefasst
        assertEquals(1, s.regions().regions.size)
        // mit leichtem Versatz zählt nur ein Coincident-Constraint
        val t = Sketch()
        val l = listOf(t.addLine(0.0, 0.0, 10.0, 0.0), t.addLine(10.001, 0.0, 10.0, 10.0), t.addLine(10.0, 10.0, 0.0, 10.0), t.addLine(0.0, 10.0, 0.0, 0.0))
        assertTrue(t.regions().regions.isEmpty())
        t.add(Coincident(t.line(l[0]).p2, t.line(l[1]).p1))
        assertEquals(1, t.regions().regions.size)
        assertEquals(4, p.size)
    }

    @Test
    fun constructionElementsAreIgnored() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.addLine(0.0, 0.0, 10.0, 10.0, construction = true) // Diagonale als Hilfslinie
        s.addCircle(5.0, 5.0, 2.0, construction = true)
        val r = s.regions()
        assertEquals(1, r.regions.size, r.problems.toString())
        assertTrue(r.regions[0].holes.isEmpty())
    }

    @Test
    fun regionAtPicksTheInnermostRegionContainingThePoint() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 100.0, 100.0)
        s.addRectangle(10.0, 10.0, 90.0, 90.0)   // Loch
        s.addRectangle(30.0, 30.0, 70.0, 70.0)   // Insel
        near(10000.0 - 6400.0, s.regionAt(5.0, 5.0)!!.area, 1e-9)    // Rand zwischen außen und Loch
        near(1600.0, s.regionAt(50.0, 50.0)!!.area, 1e-9)            // Insel
        assertEquals(null, s.regionAt(20.0, 20.0))                   // im Loch, außerhalb der Insel
        assertEquals(null, s.regionAt(200.0, 0.0))                   // außerhalb von allem
    }

    @Test
    fun regionAtStaysValidWhenDimensionsChange() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        val w = s.addLength(r.edges[0]); s.addLength(r.edges[1])
        assertTrue(s.solve().isSolved)
        assertTrue(s.regionAt(10.0, 10.0) != null)
        s.setDimension(w, 80.0)
        assertTrue(s.solve().isSolved)
        near(80.0 * 20.0, s.regionAt(10.0, 10.0)!!.area, 1e-6)
        assertTrue(s.regionAt(60.0, 10.0) != null) // Punkt, der erst durch die Änderung hineinfällt
    }

    @Test
    fun solvedSketchProducesExactRegion() {
        val s = Sketch()
        val r = s.addRectangle(1.0, 1.0, 38.0, 22.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0)); s.add(LengthDim(r.edges[0], 40.0)); s.add(LengthDim(r.edges[1], 20.0))
        assertTrue(s.solve().isSolved)
        near(800.0, s.regions().regions.single().area, 1e-6)
    }

    private fun assertClosed(loop: Loop) {
        fun start(s: LoopSegment): Vec2 = when (s) {
            is LoopSegment.Line -> Vec2(s.x1, s.y1)
            is LoopSegment.Arc -> Vec2(s.x1, s.y1)
            is LoopSegment.Circle -> Vec2(s.cx + s.r, s.cy)
        }
        fun end(s: LoopSegment): Vec2 = when (s) {
            is LoopSegment.Line -> Vec2(s.x2, s.y2)
            is LoopSegment.Arc -> Vec2(s.x2, s.y2)
            is LoopSegment.Circle -> Vec2(s.cx + s.r, s.cy)
        }
        val segs = loop.segments
        for (i in segs.indices) {
            val e = end(segs[i]); val st = start(segs[(i + 1) % segs.size])
            near(e.x, st.x, 1e-9, "Segment $i→${i + 1} x"); near(e.y, st.y, 1e-9, "Segment $i→${i + 1} y")
        }
    }
}
