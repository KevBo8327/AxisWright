package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lage der Skizze zum Ursprung: Achslinien, „Ecke/Mittig auf Ursprung“ und die Erkennung „nur die Lage ist frei“. */
class PlacementTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")
    private fun near(expected: Vec2, actual: Vec2) { near(expected.x, actual.x); near(expected.y, actual.y) }

    /** Rechteck 40 × 20 mit Breite und Höhe bemaßt, irgendwo in der Ebene. */
    private fun sizedRect(s: Sketch, x: Double = 13.0, y: Double = 7.0): Sketch.Rectangle {
        val r = s.addRectangle(x, y, x + 40.0, y + 20.0)
        s.addLength(r.edges[0], 40.0); s.addLength(r.edges[1], 20.0)
        return r
    }

    private fun dof(s: Sketch) = s.solve(SolveOptions(analyze = true)).also { assertTrue(it.isSolved) }.degreesOfFreedom

    @Test
    fun axisLinesAreRecognisedAndReused() {
        val s = Sketch()
        val x = SketchOps.axisLine(s, horizontal = true)
        val y = SketchOps.axisLine(s, horizontal = false)
        assertEquals(true, SketchOps.axisOf(s, x)); assertEquals(false, SketchOps.axisOf(s, y))
        assertEquals(x, SketchOps.axisLine(s, horizontal = true))
        assertEquals(x, SketchOps.existingAxisLine(s, true))
        assertTrue(SketchOps.isAxisPoint(s, s.line(x).p2))
        assertEquals(0, dof(s), "Achsen bringen keine Freiheitsgrade mit")
        assertNull(SketchOps.axisOf(s, s.addLine(0.0, 0.0, 50.0, 0.0, construction = true)), "nicht fixiert: keine Achse")
    }

    @Test
    fun aSizedRectangleIsOnlyFreeInPositionUntilItsCornerSitsOnTheOrigin() {
        val s = Sketch()
        val r = sizedRect(s)
        assertEquals(2, dof(s))
        assertEquals(2, SketchOps.placementFreedom(s, 2))
        assertTrue(SketchOps.cornerToOrigin(s, r.edges, emptyList()).ok)
        assertEquals(0, dof(s))
        near(Vec2(0.0, 0.0), s.point(r.corners[0]))
        near(Vec2(40.0, 20.0), s.point(r.corners[2]))
        assertFalse(SketchOps.cornerToOrigin(s, r.edges, emptyList()).ok, "hängt schon am Ursprung")
    }

    @Test
    fun centeringPutsTheMiddleOfTheRectangleOnTheOriginAndStaysCentred() {
        val s = Sketch()
        val r = sizedRect(s)
        assertTrue(SketchOps.centerOnOrigin(s, r.edges, emptyList()).ok)
        assertEquals(0, dof(s))
        near(Vec2(-20.0, -10.0), s.point(r.corners[0]))
        // Breite ändern: bleibt mittig
        val width = s.constraintEntries.first { (it.constraint as? LengthDim)?.value == 40.0 }
        s.setDimension(width.id, 60.0)
        assertTrue(s.solve().isSolved)
        near(-30.0, s.point(r.corners[0]).x); near(30.0, s.point(r.corners[2]).x)
    }

    @Test
    fun aSingleCircleIsCentredWithItsCenter() {
        val s = Sketch()
        val c = s.addCircle(12.0, -4.0, 5.0)
        assertTrue(SketchOps.centerOnOrigin(s, emptyList(), listOf(c)).ok)
        assertTrue(s.solve().isSolved)
        near(Vec2(0.0, 0.0), s.point(s.circle(c).center))
        assertFalse(SketchOps.centerOnOrigin(s, emptyList(), listOf(c)).ok)
    }

    @Test
    fun rotationIsOnlyReportedWhenTheShapeCanReallyTurn() {
        // Dreieck mit drei Seitenlängen: Form bestimmt, Lage und Drehung frei
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(30.0, 1.0); val c = s.addPoint(10.0, 20.0)
        val ab = s.addLine(a, b); val bc = s.addLine(b, c); val ca = s.addLine(c, a)
        listOf(ab, bc, ca).forEach { s.addLength(it) }
        assertEquals(3, dof(s))
        assertEquals(3, SketchOps.placementFreedom(s, 3))
        // Rechteck mit fester Breite, aber freier Höhe: auch 3 Freiheitsgrade, aber nicht „nur die Lage“
        val t = Sketch()
        val r = t.addRectangle(5.0, 5.0, 45.0, 25.0)
        t.addLength(r.edges[0], 40.0)
        assertEquals(3, dof(t))
        assertNull(SketchOps.placementFreedom(t, 3))
        // zwei getrennte Rechtecke: mehr frei als nur eine Lage
        val u = Sketch(); sizedRect(u); sizedRect(u, 80.0, 0.0)
        assertNull(SketchOps.placementFreedom(u, dof(u)))
    }

    @Test
    fun theAxesWorkWithOrdinaryConstraints() {
        val s = Sketch()
        val c = s.addCircle(12.0, 3.0, 5.0)
        val x = SketchOps.axisLine(s, horizontal = true)
        val y = SketchOps.axisLine(s, horizontal = false)
        s.add(PointOnLine(s.circle(c).center, x))                 // Mittelpunkt auf der X-Achse
        s.addPointLineDistance(s.circle(c).center, y, 25.0)       // 25 mm von der Y-Achse
        assertTrue(s.solve().isSolved)
        near(Vec2(25.0, 0.0), s.point(s.circle(c).center))
    }
    /** Umriss einer Fläche wie aus „Kanten projizieren“: feste Konstruktionslinien, jede mit eigenen, fixierten Endpunkten. */
    private fun projectedFace(s: Sketch, x0: Double, y0: Double, x1: Double, y1: Double): List<LineId> {
        val corners = listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))
        return corners.indices.map { i ->
            val a = corners[i]; val b = corners[(i + 1) % 4]
            val pa = s.addPoint(a.x, a.y); val pb = s.addPoint(b.x, b.y)
            s.add(Fixed(pa, a.x, a.y)); s.add(Fixed(pb, b.x, b.y))
            s.addLine(pa, pb, construction = true)
        }
    }

    /** Verschiebt den projizierten Umriss (wie das Nachführen nach einer Maßänderung am Körper). */
    private fun moveFace(s: Sketch, face: List<LineId>, dx: Double, dy: Double) {
        val pts = face.flatMap { s.line(it).let { l -> listOf(l.p1, l.p2) } }.toSet()
        for (e in s.constraintEntries.toList()) {
            val f = e.constraint as? Fixed ?: continue
            if (f.point in pts) { s.movePoint(f.point, f.x!! + dx, f.y!! + dy); s.replace(e.id, Fixed(f.point, f.x + dx, f.y + dy)) }
        }
    }

    private fun corners(s: Sketch, face: List<LineId>): Pair<PointId, PointId> {
        val pts = face.flatMap { s.line(it).let { l -> listOf(l.p1, l.p2) } }
        return pts.minBy { s.point(it).let { p -> p.x + p.y } } to pts.maxBy { s.point(it).let { p -> p.x + p.y } }
    }

    @Test
    fun aShapeIsCentredOnAProjectedFaceAndFollowsIt() {
        val s = Sketch()
        val face = projectedFace(s, 100.0, 20.0, 180.0, 70.0)
        val r = sizedRect(s) // 40 × 20 irgendwo
        val (lo, hi) = corners(s, face)
        // Auswahl darf den Umriss enthalten (z. B. „alles wählen“) – er bleibt fest und zählt nicht zur Form
        assertTrue(SketchOps.centerOn(s, r.edges + face, emptyList(), SketchOps.CenterTarget.Diagonal(lo, hi)).ok)
        assertEquals(0, dof(s))
        near(Vec2(120.0, 35.0), s.point(r.corners[0]))
        near(Vec2(160.0, 55.0), s.point(r.corners[2]))
        // Körper ändert sich, die Fläche wandert und wird größer: das Rechteck bleibt mittig
        moveFace(s, face, 10.0, -5.0)
        assertTrue(s.solve().isSolved)
        near(Vec2(130.0, 30.0), s.point(r.corners[0]))
    }

    @Test
    fun aCircleIsCentredOnAProjectedFaceWithItsCenter() {
        val s = Sketch()
        val face = projectedFace(s, 0.0, 0.0, 60.0, 30.0)
        val c = s.addCircle(5.0, 5.0, 4.0)
        val (lo, hi) = corners(s, face)
        assertTrue(SketchOps.centerOn(s, emptyList(), listOf(c), SketchOps.CenterTarget.Diagonal(lo, hi)).ok)
        assertTrue(s.solve().isSolved)
        near(Vec2(30.0, 15.0), s.point(s.circle(c).center))
    }
    @Test
    fun aCentredRectangleWithFreeSizeIsNotReportedAsFreeInPosition() {
        val s = Sketch()
        val face = projectedFace(s, 0.0, 0.0, 80.0, 50.0)
        val r = s.addRectangle(10.0, 10.0, 30.0, 25.0) // ohne Maße: Breite und Höhe frei
        val (lo, hi) = corners(s, face)
        assertTrue(SketchOps.centerOn(s, r.edges, emptyList(), SketchOps.CenterTarget.Diagonal(lo, hi)).ok)
        assertEquals(2, dof(s))
        assertNull(SketchOps.placementFreedom(s, 2), "frei sind Breite und Höhe, nicht die Lage")
    }
    /** Bohrung wie aus „Kanten projizieren“: fester Konstruktionskreis. */
    private fun projectedHole(s: Sketch, x: Double, y: Double, r: Double): CircleId {
        val c = s.addPoint(x, y)
        s.add(Fixed(c, x, y))
        return s.addCircle(c, r, construction = true).also { s.add(RadiusDim(it, r)) }
    }

    @Test
    fun aShapeIsCentredOnAProjectedHoleAndFollowsIt() {
        val s = Sketch()
        val hole = projectedHole(s, 40.0, 25.0, 3.0)
        val r = sizedRect(s)
        assertTrue(SketchOps.isReferenceCircle(s, hole))
        assertTrue(SketchOps.centerOn(s, r.edges, emptyList(), SketchOps.CenterTarget.Point(s.circle(hole).center)).ok)
        assertEquals(0, dof(s))
        near(Vec2(20.0, 15.0), s.point(r.corners[0]))
        // Bohrung wandert (Körper geändert)
        val pin = s.constraintEntries.first { (it.constraint as? Fixed)?.point == s.circle(hole).center }
        s.movePoint(s.circle(hole).center, 50.0, 25.0); s.replace(pin.id, Fixed(s.circle(hole).center, 50.0, 25.0))
        assertTrue(s.solve().isSolved)
        near(Vec2(30.0, 15.0), s.point(r.corners[0]))
        // ein Kreis wird konzentrisch
        val c = s.addCircle(5.0, 5.0, 1.0)
        assertTrue(SketchOps.centerOn(s, emptyList(), listOf(c), SketchOps.CenterTarget.Point(s.circle(hole).center)).ok)
        assertTrue(s.solve().isSolved)
        near(Vec2(50.0, 25.0), s.point(s.circle(c).center))
    }

    @Test
    fun centeringAlongAnEdgeLeavesOnlyTheDistanceFree() {
        val s = Sketch()
        val face = projectedFace(s, 0.0, 0.0, 80.0, 50.0)
        val bottom = face[0] // y = 0, von x = 0 bis 80
        assertTrue(SketchOps.isReferenceLine(s, bottom))
        val r = sizedRect(s, 5.0, 10.0) // 40 × 20
        assertEquals(2, dof(s))
        assertTrue(SketchOps.centerOnEdge(s, r.edges + bottom, emptyList(), bottom).ok)
        assertEquals(1, dof(s), "nur der Abstand zur Kante ist frei")
        near(20.0, s.point(r.corners[0]).x); near(60.0, s.point(r.corners[2]).x) // mittig: 20 … 60
        // Abstand bemaßen, dann wird die Platte breiter: das Rechteck bleibt mittig
        s.addVerticalDistance(s.line(bottom).p1, r.corners[0], 10.0)
        assertEquals(0, dof(s))
        moveFace(s, listOf(bottom), 0.0, 0.0)
        val ends = listOf(s.line(bottom).p1, s.line(bottom).p2)
        for (e in s.constraintEntries.toList()) {
            val f = e.constraint as? Fixed ?: continue
            if (f.point == ends[1]) { s.movePoint(f.point, 120.0, 0.0); s.replace(e.id, Fixed(f.point, 120.0, 0.0)) }
        }
        assertTrue(s.solve().isSolved)
        near(40.0, s.point(r.corners[0]).x); near(80.0, s.point(r.corners[2]).x)
        near(10.0, s.point(r.corners[0]).y)
    }
}
