package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Anzahl eines Skizzenmusters nachträglich ändern: Muster erkennen, Kopien ergänzen oder entfernen. */
class SketchPatternCountTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-5) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")
    private fun near(expected: Vec2, actual: Vec2, tol: Double = 1e-5) { near(expected.x, actual.x, tol); near(expected.y, actual.y, tol) }
    private fun Sketch.solved(analyze: Boolean = false) = solve(SolveOptions(analyze = analyze)).also { assertTrue(it.isSolved, "Skizze muss lösbar bleiben") }

    private fun centers(s: Sketch) = s.circleIds.map { s.point(s.circle(it).center) }.sortedWith(compareBy({ it.x }, { it.y }))

    @Test
    fun aRowIsFoundFromTheOriginalAndFromEveryCopy() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 2.0)
        val other = s.addCircle(0.0, 30.0, 1.0)
        SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(1.0, 0.0), 4, 10.0)
        s.circleIds.filter { it != other }.forEach { id ->
            val p = assertNotNull(SketchOps.patternOf(s, emptyList(), emptyList(), listOf(id)))
            assertEquals(4, p.count); assertFalse(p.circular)
        }
        assertNull(SketchOps.patternOf(s, emptyList(), emptyList(), listOf(other)), "Kreis außerhalb des Musters")
    }

    @Test
    fun aRowGrowsAtItsEndAndKeepsFollowingTheSpacing() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 2.0)
        SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(1.0, 0.0), 3, 10.0)
        s.solved()
        val p = SketchOps.patternOf(s, emptyList(), emptyList(), listOf(c))!!
        assertTrue(SketchOps.setPatternCount(s, p, 5).ok)
        s.solved()
        assertEquals(5, s.circleIds.size)
        centers(s).forEachIndexed { i, v -> near(Vec2(10.0 * i, 0.0), v) }
        assertEquals(5, SketchOps.patternOf(s, emptyList(), emptyList(), listOf(c))!!.count)
        // die neuen Kopien hängen am Abstand und am Radius des Originals
        s.setDimension(s.constraintEntries.single { it.constraint is LengthDim }.id, 7.0)
        s.add(Fixed(s.circle(c).center, 0.0, 0.0))
        s.addRadius(c, 3.0)
        s.solved()
        near(Vec2(28.0, 0.0), centers(s).last())
        s.circleIds.forEach { near(3.0, s.circle(it).radius) }
    }

    @Test
    fun aRowShrinksWithoutLeavingPointsOrConstraintsBehind() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 10.0, 6.0)
        s.add(Fixed(rect.corners[0], 0.0, 0.0))
        s.addLength(rect.edges[0], 10.0); s.addLength(rect.edges[1], 6.0)
        SketchOps.linearPattern(s, rect.edges, emptyList(), Vec2(1.0, 0.0), 2, 15.0)
        val small = s.copy()
        val big = s
        // erst auf 4 erweitern, dann zurück auf 2: wieder dieselbe Skizze wie vorher (bis auf die Ids)
        val p = SketchOps.patternOf(big, emptyList(), listOf(rect.edges[0]), emptyList())!!
        assertTrue(SketchOps.setPatternCount(big, p, 4).ok)
        assertEquals(0, big.solved(analyze = true).degreesOfFreedom)
        assertEquals(4, big.regions().regions.size)
        assertTrue(SketchOps.setPatternCount(big, SketchOps.patternOf(big, emptyList(), listOf(rect.edges[0]), emptyList())!!, 2).ok)
        big.solved()
        assertEquals(small.pointIds.size, big.pointIds.size)
        assertEquals(small.lineIds.size, big.lineIds.size)
        assertEquals(small.constraintEntries.size, big.constraintEntries.size)
        assertEquals(2, big.regions().regions.size)
    }

    @Test
    fun aFullCircleRedistributesTheCopies() {
        val s = Sketch()
        val hub = s.origin
        val spoke = s.addLine(hub, s.addPoint(10.0, 0.0))
        val hole = s.addCircle(20.0, 0.0, 2.0)
        SketchOps.circularPattern(s, listOf(spoke), listOf(hole), hub, 4)
        s.solved()
        val p = SketchOps.patternOf(s, emptyList(), listOf(spoke), emptyList())!!
        assertTrue(p.circular && p.fullCircle); assertEquals(4, p.count)
        assertTrue(SketchOps.setPatternCount(s, p, 6).ok)
        s.solved()
        assertEquals(6, s.circleIds.size); assertEquals(6, s.lineIds.size)
        val angles = s.circleIds.map { Math.toDegrees(kotlin.math.atan2(s.point(s.circle(it).center).y, s.point(s.circle(it).center).x)).let { a -> (a + 360) % 360 } }.sorted()
        angles.forEachIndexed { i, a -> near(60.0 * i, a, 1e-4) }
        assertTrue(s.lineIds.all { s.line(it).p1 == hub })
        // und wieder weniger
        assertTrue(SketchOps.setPatternCount(s, SketchOps.patternOf(s, emptyList(), emptyList(), listOf(hole))!!, 3).ok)
        s.solved()
        assertEquals(3, s.circleIds.size)
        near(Vec2(-10.0, 20.0 * kotlin.math.sin(Math.toRadians(120.0))), s.point(s.circle(s.circleIds[1]).center))
    }

    @Test
    fun aPartialArcKeepsItsTotalAngle() {
        val s = Sketch()
        val c = s.addCircle(10.0, 0.0, 1.0)
        SketchOps.circularPattern(s, emptyList(), listOf(c), s.origin, 3, 90.0)
        val p = SketchOps.patternOf(s, emptyList(), emptyList(), listOf(c))!!
        assertFalse(p.fullCircle)
        assertTrue(SketchOps.setPatternCount(s, p, 4).ok)
        s.solved()
        near(Vec2(0.0, 10.0), s.point(s.circle(s.circleIds.last()).center))
        near(Vec2(10.0 * kotlin.math.cos(Math.toRadians(30.0)), 10.0 * kotlin.math.sin(Math.toRadians(30.0))), s.point(s.circle(s.circleIds[1]).center))
    }

    @Test
    fun invalidCountsAreRejected() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 1.0)
        SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(1.0, 0.0), 3, 5.0)
        val p = SketchOps.patternOf(s, emptyList(), emptyList(), listOf(c))!!
        assertFalse(SketchOps.setPatternCount(s, p, 1).ok)
        assertFalse(SketchOps.setPatternCount(s, p, 3).ok)
        assertFalse(SketchOps.setPatternCount(s, p, SketchOps.MAX_PATTERN_COUNT + 1).ok)
        assertEquals(3, s.circleIds.size)
    }
}
