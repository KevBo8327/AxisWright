package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Skizzenmuster: Reihe und Kreismuster bleiben über Constraints an das Original gebunden. */
class SketchPatternTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-5) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")
    private fun near(expected: Vec2, actual: Vec2, tol: Double = 1e-5) { near(expected.x, actual.x, tol); near(expected.y, actual.y, tol) }
    private fun Sketch.solved(analyze: Boolean = false) = solve(SolveOptions(analyze = analyze)).also { assertTrue(it.isSolved, "Skizze muss lösbar bleiben") }

    @Test
    fun aRowOfCirclesFollowsTheSpacingDimensionAndTheOriginal() {
        val s = Sketch()
        val c = s.addCircle(10.0, 5.0, 2.0)
        val r = SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(1.0, 0.0), 4, 15.0)
        assertTrue(r.ok, r.message.toString())
        s.solved()
        val copies = s.circleIds.filter { it != c }
        assertEquals(3, copies.size)
        copies.forEachIndexed { i, id -> near(Vec2(10.0 + 15.0 * (i + 1), 5.0), s.point(s.circle(id).center)) }
        // Abstand über die Bemaßung der Hilfslinie ändern (Original festgehalten)
        val pin = s.add(Fixed(s.circle(c).center, 10.0, 5.0))
        val dim = s.constraintEntries.single { it.constraint is LengthDim }
        s.setDimension(dim.id, 12.0)
        s.solved()
        near(Vec2(46.0, 5.0), s.point(s.circle(copies.last()).center))
        // Original verschieben und vergrößern: alle Kopien folgen
        s.replace(pin, Fixed(s.circle(c).center, 0.0, 0.0))
        s.addRadius(c, 3.0)
        s.solved()
        near(Vec2(36.0, 0.0), s.point(s.circle(copies.last()).center))
        copies.forEach { near(3.0, s.circle(it).radius) }
    }

    @Test
    fun aPatternedRectangleStaysClosedAndFullyDefinedWhenTheOriginalIs() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 10.0, 6.0)
        s.add(Fixed(rect.corners[0], 0.0, 0.0))
        s.addLength(rect.edges[0], 10.0); s.addLength(rect.edges[1], 6.0)
        val before = s.solved(analyze = true).degreesOfFreedom
        assertEquals(0, before)
        assertTrue(SketchOps.linearPattern(s, rect.edges, emptyList(), Vec2(0.0, 1.0), 3, 10.0).ok)
        assertEquals(0, s.solved(analyze = true).degreesOfFreedom, "Kopien und Hilfslinie sind vollständig bestimmt")
        assertEquals(3, s.regions().regions.size, "drei geschlossene Flächen")
    }

    @Test
    fun alongALineTheRowKeepsItsDirection() {
        val s = Sketch()
        val guide = s.addLine(0.0, 0.0, 3.0, 4.0, construction = true)
        val c = s.addCircle(0.0, 0.0, 1.0)
        assertTrue(SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(3.0, 4.0), 3, 10.0, along = guide).ok)
        s.solved()
        val last = s.circleIds.last()
        near(Vec2(12.0, 16.0), s.point(s.circle(last).center))
        assertTrue(s.constraintEntries.any { (it.constraint as? Parallel)?.b == guide })
    }

    @Test
    fun aCircularPatternSpreadsEvenlyAndSharesTheCenter() {
        val s = Sketch()
        val hub = s.origin
        val spoke = s.addLine(hub, s.addPoint(10.0, 0.0))
        val hole = s.addCircle(20.0, 0.0, 2.0)
        val r = SketchOps.circularPattern(s, listOf(spoke), listOf(hole), hub, 4)
        assertTrue(r.ok, r.message.toString())
        s.solved()
        val spokes = s.lineIds.filter { it != spoke }
        assertEquals(3, spokes.size)
        assertTrue(spokes.all { s.line(it).p1 == hub }, "Speichen beginnen im gemeinsamen Mittelpunkt")
        near(Vec2(0.0, 10.0), s.point(s.line(spokes[0]).p2))
        near(Vec2(-10.0, 0.0), s.point(s.line(spokes[1]).p2))
        val holes = s.circleIds.filter { it != hole }
        near(Vec2(0.0, -20.0), s.point(s.circle(holes[2]).center))
        // Original wandert nach außen – Kopien folgen auf ihrem Kreis
        s.add(Fixed(s.circle(hole).center, 25.0, 0.0))
        s.solved()
        near(Vec2(0.0, 25.0), s.point(s.circle(holes[0]).center))
    }

    @Test
    fun aPartialArcPutsTheLastCopyAtTheAngle() {
        val s = Sketch()
        val c = s.addCircle(10.0, 0.0, 1.0)
        assertTrue(SketchOps.circularPattern(s, emptyList(), listOf(c), s.origin, 3, 90.0).ok)
        s.solved()
        val last = s.circleIds.last()
        near(Vec2(0.0, 10.0), s.point(s.circle(last).center))
        val mid = s.circleIds[1]
        near(Vec2(10.0 / sqrt(2.0), 10.0 / sqrt(2.0)), s.point(s.circle(mid).center))
    }

    @Test
    fun arcsAreCopiedWithTheirOrientation() {
        val s = Sketch()
        val a = s.addArc(10.0, 0.0, 12.0, 0.0, 10.0, 2.0)
        assertTrue(SketchOps.circularPattern(s, emptyList(), listOf(a), s.origin, 2).ok)
        s.solved()
        val b = s.arc(s.arcIds.single { it != a })
        near(Vec2(-10.0, 0.0), s.point(b.center)); near(Vec2(-12.0, 0.0), s.point(b.start)); near(Vec2(-10.0, -2.0), s.point(b.end))
    }

    @Test
    fun invalidInputLeavesNothingBehind() {
        val s = Sketch()
        val c = s.addCircle(1.0, 1.0, 1.0)
        assertFalse(SketchOps.linearPattern(s, emptyList(), emptyList(), Vec2(1.0, 0.0), 3, 5.0).ok)
        assertFalse(SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(1.0, 0.0), 1, 5.0).ok)
        assertFalse(SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(0.0, 0.0), 3, 5.0).ok)
        assertFalse(SketchOps.circularPattern(s, emptyList(), listOf(c), s.origin, 3, 0.0).ok)
        assertEquals(1, s.circleIds.size)
        assertEquals(0, s.constraintEntries.count { it.constraint is Translated || it.constraint is Rotated })
    }

    @Test
    fun patternConstraintsSurviveSaving() {
        val s = Sketch()
        val c = s.addCircle(10.0, 0.0, 1.0)
        SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(1.0, 0.0), 2, 5.0)
        SketchOps.circularPattern(s, emptyList(), listOf(c), s.origin, 3)
        val loaded = SketchCodec.fromJson(SketchCodec.toJson(s))
        assertEquals(s.constraintEntries.map { it.constraint }, loaded.constraintEntries.map { it.constraint })
    }
}
