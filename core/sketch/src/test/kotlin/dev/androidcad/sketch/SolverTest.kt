package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolverTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-7, msg: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$msg erwartet $expected, erhalten $actual")

    private fun Sketch.at(p: PointId, x: Double, y: Double, tol: Double = 1e-7) {
        near(x, point(p).x, tol, "x von $p"); near(y, point(p).y, tol, "y von $p")
    }

    @Test
    fun sloppyRectangleBecomesExactlyDimensioned() {
        val s = Sketch()
        val r = s.addRectangle(1.0, 1.0, 38.0, 22.0) // schief gezeichnet
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        s.add(LengthDim(r.edges[0], 40.0))
        s.add(LengthDim(r.edges[1], 20.0))
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        assertEquals(0, res.degreesOfFreedom)
        assertTrue(res.isFullyConstrained)
        s.at(r.corners[0], 0.0, 0.0); s.at(r.corners[1], 40.0, 0.0); s.at(r.corners[2], 40.0, 20.0); s.at(r.corners[3], 0.0, 20.0)
        assertTrue(res.constrainedPoints.containsAll(r.corners))
    }

    @Test
    fun freeRectangleHasFourDegreesOfFreedom() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 10.0, 5.0)
        val res = s.solve()
        assertTrue(res.isSolved)
        assertEquals(4, res.degreesOfFreedom) // x, y, Breite, Höhe
        assertFalse(res.isFullyConstrained)
    }

    @Test
    fun redundantDimensionIsReportedAndStillSolved() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        s.add(LengthDim(r.edges[0], 40.0))
        s.add(LengthDim(r.edges[1], 20.0))
        val extra = s.add(LengthDim(r.edges[2], 40.0)) // Oberkante = Unterkante ist schon durch H/V bestimmt
        val res = s.solve()
        assertTrue(res.isSolved)
        assertEquals(listOf(extra), res.redundant)
        assertEquals(0, res.degreesOfFreedom)
    }

    @Test
    fun conflictingDimensionsAreReportedAndSketchIsLeftUntouched() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        val a = s.add(LengthDim(r.edges[0], 40.0))
        val b = s.add(LengthDim(r.edges[2], 50.0)) // Oberkante muss gleich lang wie Unterkante sein
        val before = s.pointIds.map { s.point(it) }
        val res = s.solve()
        assertEquals(SolveStatus.CONFLICT, res.status)
        assertTrue(res.conflicting.any { it == a || it == b }, res.toString())
        assertEquals(before, s.pointIds.map { s.point(it) })
    }

    @Test
    fun lineTangentToCircleWithRadius() {
        val s = Sketch()
        val p1 = s.addPoint(0.0, 0.0); val p2 = s.addPoint(50.0, 0.0)
        val line = s.addLine(p1, p2)
        s.add(Fixed(p1, 0.0, 0.0)); s.add(Fixed(p2, 50.0, 0.0))
        val center = s.addPoint(25.0, 8.0)
        val circle = s.addCircle(center, 5.0)
        s.add(Fixed(center, 25.0, null))
        s.add(TangentLineRound(line, circle))
        s.add(RadiusDim(circle, 7.0))
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        s.at(center, 25.0, 7.0)
        near(7.0, s.circle(circle).radius)
        assertEquals(0, res.degreesOfFreedom)
    }

    @Test
    fun angleDimensionBetweenLines() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(10.0, 0.0)
        val l1 = s.addLine(a, b)
        s.add(Fixed(a, 0.0, 0.0)); s.add(Fixed(b, 10.0, 0.0))
        val c = s.addPoint(0.0, 0.0); val d = s.addPoint(8.0, 5.0)
        val l2 = s.addLine(c, d)
        s.add(Coincident(a, c))
        s.add(AngleDim(l1, l2, 30.0))
        s.add(LengthDim(l2, 10.0))
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        s.at(d, 10 * cos(Math.toRadians(30.0)), 10 * sin(Math.toRadians(30.0)))
        near(30.0, s.angleBetween(l1, l2), 1e-6)
    }

    @Test
    fun parallelEqualLengthAndPerpendicular() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(10.0, 0.0)
        val l1 = s.addLine(a, b)
        s.add(Fixed(a, 0.0, 0.0)); s.add(Fixed(b, 10.0, 0.0))
        val c = s.addPoint(0.0, 5.0); val d = s.addPoint(6.0, 6.0)
        val l2 = s.addLine(c, d)
        s.add(Fixed(c, 0.0, 5.0)); s.add(Parallel(l1, l2)); s.add(EqualLength(l1, l2))
        val e = s.addPoint(10.0, 0.0); val f = s.addPoint(11.0, 4.0)
        val l3 = s.addLine(e, f)
        s.add(Coincident(b, e)); s.add(Perpendicular(l1, l3)); s.add(LengthDim(l3, 3.0))
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        s.at(d, 10.0, 5.0)
        s.at(f, 10.0, 3.0)
    }

    @Test
    fun symmetricAndMidpoint() {
        val s = Sketch()
        val ax1 = s.addPoint(5.0, -10.0); val ax2 = s.addPoint(5.0, 10.0)
        val axis = s.addLine(ax1, ax2)
        s.add(Fixed(ax1, 5.0, -10.0)); s.add(Fixed(ax2, 5.0, 10.0))
        val a = s.addPoint(2.0, 3.0); val b = s.addPoint(7.0, 2.0)
        s.add(Fixed(a, 2.0, 3.0)); s.add(Symmetric(a, b, axis))
        val q1 = s.addPoint(0.0, 20.0); val q2 = s.addPoint(10.0, 20.0)
        val base = s.addLine(q1, q2)
        s.add(Fixed(q1, 0.0, 20.0)); s.add(Fixed(q2, 10.0, 20.0))
        val m = s.addPoint(4.0, 21.0)
        s.add(Midpoint(m, base))
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        s.at(b, 8.0, 3.0)
        s.at(m, 5.0, 20.0)
    }

    @Test
    fun equalRadiusAndConcentric() {
        val s = Sketch()
        val c1 = s.addCircle(0.0, 0.0, 5.0); val c2 = s.addCircle(3.0, 1.0, 9.0)
        s.add(Fixed(s.circle(c1).center, 0.0, 0.0))
        s.add(RadiusDim(c1, 6.0)); s.add(EqualRadius(c1, c2)); s.add(Concentric(c1, c2))
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        near(6.0, s.circle(c2).radius)
        s.at(s.circle(c2).center, 0.0, 0.0)
        assertEquals(0, res.degreesOfFreedom)
    }

    @Test
    fun arcKeepsStartAndEndEquidistantFromCenter() {
        val s = Sketch()
        val c = s.addPoint(0.0, 0.0); val st = s.addPoint(10.0, 0.0); val en = s.addPoint(0.0, 12.0)
        val arc = s.addArc(c, st, en)
        s.add(Fixed(c, 0.0, 0.0)); s.add(Fixed(st, 10.0, 0.0))
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        near(10.0, hypot(s.point(en).x, s.point(en).y))
        assertEquals(1, res.degreesOfFreedom) // Endwinkel bleibt frei
        near(10.0, s.radiusOf(arc))
    }

    @Test
    fun tangentCirclesInternalAndExternal() {
        val s = Sketch()
        val big = s.addCircle(0.0, 0.0, 10.0)
        val inner = s.addCircle(6.0, 0.0, 3.0)
        val outer = s.addCircle(16.0, 0.0, 5.0)
        s.add(Fixed(s.circle(big).center, 0.0, 0.0)); s.add(RadiusDim(big, 10.0))
        s.add(RadiusDim(inner, 3.0)); s.add(Fixed(s.circle(inner).center, null, 0.0))
        s.add(RadiusDim(outer, 5.0)); s.add(Fixed(s.circle(outer).center, null, 0.0))
        s.addTangent(big, inner)  // von innen
        s.addTangent(big, outer)  // von außen
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        near(7.0, s.point(s.circle(inner).center).x)
        near(15.0, s.point(s.circle(outer).center).x)
    }

    @Test
    fun equilateralTriangleFromSketchyStart() {
        val s = Sketch()
        val p1 = s.addPoint(0.0, 0.0); val p2 = s.addPoint(9.0, 1.0); val p3 = s.addPoint(4.0, 7.0)
        val l1 = s.addLine(p1, p2); val l2 = s.addLine(p2, p3); val l3 = s.addLine(p3, p1)
        s.add(Fixed(p1, 0.0, 0.0)); s.add(Horizontal(l1)); s.add(LengthDim(l1, 10.0))
        s.add(EqualLength(l1, l2)); s.add(EqualLength(l2, l3))
        val res = s.solve()
        assertTrue(res.isSolved, res.toString())
        s.at(p2, 10.0, 0.0)
        s.at(p3, 5.0, 10 * sin(Math.toRadians(60.0)))
        assertTrue(res.isFullyConstrained)
    }

    @Test
    fun pointLineDistanceKeepsTheSide() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(20.0, 0.0)
        val line = s.addLine(a, b)
        s.add(Fixed(a, 0.0, 0.0)); s.add(Fixed(b, 20.0, 0.0))
        val p = s.addPoint(3.0, -4.0)
        s.add(Fixed(p, 3.0, null))
        val dim = s.addPointLineDistance(p, line)
        s.setDimension(dim, 6.0)
        assertTrue(s.solve().isSolved)
        s.at(p, 3.0, -6.0)
    }

    @Test
    fun horizontalPlusVerticalOnOneLineIsAConflictNotACollapsedLine() {
        val s = Sketch()
        val l = s.addLine(10.0, 10.0, 50.0, 10.0)
        s.add(Horizontal(l))
        assertTrue(s.solve().isSolved)
        val v = s.add(Vertical(l))
        val res = s.solve()
        assertEquals(SolveStatus.CONFLICT, res.status)
        assertTrue(v in res.conflicting)
        // die Linie blieb unverändert
        near(40.0, s.length(l))
    }

    @Test
    fun collapsingACircleToZeroRadiusIsAConflict() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 5.0)
        s.add(Fixed(s.circle(c).center, 0.0, 0.0))
        s.add(PointOnRound(s.origin, c)) // der Ursprung liegt auf dem Kreis um sich selbst → nur mit Radius 0 erfüllbar
        assertEquals(SolveStatus.CONFLICT, s.solve().status)
    }

    @Test
    fun partiallyRedundantConstraintIsNotReportedAsRedundant() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(10.0, 0.0)
        val l = s.addLine(a, b)
        s.add(Fixed(a, 0.0, 0.0)); s.add(Horizontal(l))
        val fixB = s.add(Fixed(b, 10.0, 0.0)) // y von b war durch „horizontal“ schon bestimmt, x ist neu
        val res = s.solve()
        assertTrue(res.isSolved)
        assertFalse(fixB in res.redundant)
        // vollständig überflüssig: noch einmal dasselbe
        val again = s.add(Fixed(b, 10.0, 0.0))
        assertTrue(again in s.solve().redundant)
    }

    @Test
    fun solvingTwiceNeedsNoIterationsTheSecondTime() {
        val s = Sketch()
        val r = s.addRectangle(1.0, 1.0, 38.0, 22.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0)); s.add(LengthDim(r.edges[0], 40.0)); s.add(LengthDim(r.edges[1], 20.0))
        assertTrue(s.solve().iterations > 0)
        assertEquals(0, s.solve().iterations)
    }

    @Test
    fun analysisMarksOnlyDeterminedElements() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        s.add(LengthDim(r.edges[0], 40.0))
        val res = s.solve()
        assertTrue(res.isSolved)
        assertEquals(1, res.degreesOfFreedom) // nur die Höhe fehlt
        assertTrue(r.corners[0] in res.constrainedPoints && r.corners[1] in res.constrainedPoints)
        assertFalse(r.corners[2] in res.constrainedPoints)
        assertFalse(r.corners[3] in res.constrainedPoints)
        assertTrue(r.edges[0] in res.constrainedLines)
        assertFalse(r.edges[1] in res.constrainedLines)
    }

    @Test
    fun deletingPointsRemovesDependentElementsAndConstraints() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.add(LengthDim(r.edges[0], 10.0))
        val before = s.constraintEntries.size
        s.remove(r.corners[0])
        assertFalse(s.exists(r.edges[0])); assertFalse(s.exists(r.edges[3]))
        assertTrue(s.exists(r.edges[1]) && s.exists(r.edges[2]))
        assertTrue(s.constraintEntries.size < before)
        s.remove(s.origin)
        assertTrue(s.exists(s.origin)) // Ursprung bleibt
        assertTrue(s.solve().isSolved)
    }

    @Test
    fun defaultDimensionValuesFollowTheDrawing() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(3.0, 4.0)
        val l = s.addLine(a, b)
        val d = s.constraint(s.addLength(l)) as LengthDim
        near(5.0, d.value)
        near(5.0, (s.constraint(s.addDistance(a, b)) as DistanceDim).value)
        near(3.0, (s.constraint(s.addHorizontalDistance(a, b)) as HorizontalDistanceDim).value)
        near(4.0, s.measure(VerticalDistanceDim(a, b, 0.0)))
        val c = s.addCircle(0.0, 0.0, 2.5)
        near(5.0, (s.constraint(s.addDiameter(c)) as DiameterDim).value)
    }

    @Test
    fun copyIsIndependent() {
        val s = Sketch()
        val p = s.addPoint(1.0, 2.0)
        val c = s.copy()
        c.movePoint(p, 9.0, 9.0)
        assertEquals(Vec2(1.0, 2.0), s.point(p))
        assertEquals(s.constraintEntries.size, c.constraintEntries.size)
    }
}
