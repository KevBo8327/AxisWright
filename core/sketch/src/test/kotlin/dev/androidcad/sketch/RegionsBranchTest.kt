package dev.androidcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verzweigte Konturen: gemeinsame Wände, Teilungslinien und lose Enden ergeben die elementaren Teilflächen. */
class RegionsBranchTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    private fun Sketch.chain(vararg xy: Double) {
        for (i in 0 until xy.size / 2 - 1) addLine(xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3])
    }

    private fun areas(s: Sketch) = s.regions().regions.map { it.area }

    @Test
    fun twoRectanglesWithSharedWallAreTwoRegions() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.addRectangle(10.0, 0.0, 30.0, 10.0)
        val r = s.regions()
        assertEquals(2, r.regions.size, r.problems.toString())
        near(200.0, r.regions[0].area); near(100.0, r.regions[1].area)
        assertTrue(r.problems.isEmpty(), r.problems.toString())
    }

    @Test
    fun dividingLineSplitsRectangle() {
        val s = Sketch()
        s.chain(0.0, 0.0, 15.0, 0.0, 40.0, 0.0, 40.0, 20.0, 15.0, 20.0, 0.0, 20.0, 0.0, 0.0)
        s.addLine(15.0, 0.0, 15.0, 20.0)
        val a = areas(s)
        assertEquals(2, a.size)
        near(500.0, a[0]); near(300.0, a[1])
        assertEquals(500.0, s.regionAt(30.0, 10.0)!!.area, 1e-6)
    }

    @Test
    fun dividingLineAfterSplittingEdges() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        val bottom = rect.edges.first { s.point(s.line(it).p1).y == 0.0 && s.point(s.line(it).p2).y == 0.0 }
        val top = rect.edges.first { s.point(s.line(it).p1).y == 20.0 && s.point(s.line(it).p2).y == 20.0 }
        assertTrue(SketchOps.split(s, bottom, Vec2(15.0, 0.0)).ok)
        assertTrue(SketchOps.split(s, top, Vec2(15.0, 20.0)).ok)
        assertTrue(s.solve().isSolved)
        s.addLine(15.0, 0.0, 15.0, 20.0)
        val a = areas(s)
        assertEquals(2, a.size)
        near(500.0, a[0]); near(300.0, a[1])
    }

    @Test
    fun looseAppendixDoesNotPreventRegion() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.addLine(10.0, 10.0, 15.0, 15.0)           // außen angehängt
        s.chain(0.0, 0.0, 3.0, 2.0, 4.0, 5.0)        // innen angehängt, zweigliedrig
        val r = s.regions()
        assertEquals(1, r.regions.size, r.problems.toString())
        near(100.0, r.regions[0].area)
        assertEquals(4, r.regions[0].outer.segments.size)
        assertTrue(r.problems.any { it.key == SketchText.OPEN_END }, r.problems.toString())
    }

    @Test
    fun slotSharingEdgeInsideRectangle() {
        // Rechteck 40 x 20, unten bei x = 10 und 30 geteilt; Langloch (r = 5) sitzt mit seiner Unterkante auf diesem Stück
        val s = Sketch()
        s.chain(0.0, 0.0, 10.0, 0.0, 30.0, 0.0, 40.0, 0.0, 40.0, 20.0, 0.0, 20.0, 0.0, 0.0)
        s.addLine(10.0, 10.0, 30.0, 10.0)
        s.addLine(30.0, 0.0, 10.0, 0.0)
        s.addArc(30.0, 5.0, 30.0, 0.0, 30.0, 10.0)  // rechte Rundung
        s.addArc(10.0, 5.0, 10.0, 10.0, 10.0, 0.0)  // linke Rundung: tangential zur Rechteckkante bei (10, 0)
        val slot = 200.0 + PI * 25
        val a = areas(s)
        assertEquals(2, a.size, s.regions().problems.toString())
        near(800.0 - slot, a[0]); near(slot, a[1])
        near(slot, assertNotNull(s.regionAt(20.0, 5.0)).area)
    }

    @Test
    fun slotSharingEdgeOutsideRectangle() {
        val s = Sketch()
        s.chain(0.0, 0.0, 40.0, 0.0, 40.0, 20.0, 30.0, 20.0, 10.0, 20.0, 0.0, 20.0, 0.0, 0.0)
        s.addLine(10.0, 20.0, 30.0, 20.0)
        s.addLine(30.0, 30.0, 10.0, 30.0)
        s.addArc(30.0, 25.0, 30.0, 20.0, 30.0, 30.0)
        s.addArc(10.0, 25.0, 10.0, 30.0, 10.0, 20.0)
        val a = areas(s)
        assertEquals(2, a.size)
        near(800.0, a[0]); near(200.0 + PI * 25, a[1])
    }

    @Test
    fun holeGoesToTheFaceContainingIt() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.addRectangle(10.0, 0.0, 30.0, 10.0)
        s.addCircle(5.0, 5.0, 2.0)
        val r = s.regions().regions
        assertEquals(2, r.size)
        assertEquals(0, r[0].holes.size)
        assertEquals(1, r[1].holes.size)
        near(100.0 - PI * 4, r[1].area)
    }

    @Test
    fun bridgeBetweenTwoRectanglesIsIgnored() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.addRectangle(20.0, 0.0, 30.0, 10.0)
        s.addLine(10.0, 10.0, 20.0, 10.0)
        val a = areas(s)
        assertEquals(2, a.size)
        near(100.0, a[0]); near(100.0, a[1])
    }
}
