package dev.androidcad.sketchedit

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Trimmen, Verlängern, Versatz, Eckenverrundung und Spiegeln über echte Zeigereingaben des Editors. */
class ModifyToolsTest {
    private fun editor(sketch: Sketch = Sketch()): SketchEditor {
        val e = SketchEditor(sketch)
        e.setView(ViewTransform(10.0, 400.0, 300.0))
        e.gridSnap = false
        return e
    }

    private fun SketchEditor.click(x: Double, y: Double) {
        val p = view.toScreen(Vec2(x, y)); pointerDown(p); pointerUp(p)
    }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-5) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    @Test
    fun trimToolShortensTheTappedPartAndIsUndoable() {
        val s = Sketch()
        val h = s.addLine(-10.0, 0.0, 10.0, 0.0)
        s.addLine(0.0, -10.0, 0.0, 10.0)
        val e = editor(s)
        e.setTool(Tool.TRIM)
        e.click(-5.0, 0.0)
        near(0.0, s.point(s.line(h).p1).x); near(10.0, s.point(s.line(h).p2).x)
        assertTrue(e.canUndo)
        e.undo()
        near(-10.0, s.point(s.line(h).p1).x)
    }

    @Test
    fun splitToolDividesTheTappedLine() {
        val s = Sketch()
        val h = s.addLine(-10.0, 0.0, 10.0, 0.0)
        val e = editor(s)
        e.setTool(Tool.SPLIT)
        e.click(4.0, 0.0)
        assertEquals(2, s.lineIds.size)
        near(4.0, s.point(s.line(h).p2).x)
        e.undo()
        assertEquals(1, s.lineIds.size)
    }

    @Test
    fun aFailedOperationLeavesTheSketchAndHistoryUntouched() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 10.0)
        s.addLine(0.0, 0.0, 20.0, 0.0) // schneidet den Kreis nur einmal
        val e = editor(s)
        val undoBefore = e.canUndo
        e.setTool(Tool.TRIM)
        e.click(0.0, 10.0)
        assertTrue(s.exists(c))
        assertNotNull(e.message)
        assertEquals(undoBefore, e.canUndo)
        // Tippen ins Leere gibt einen Hinweis statt etwas zu tun
        e.click(50.0, 50.0)
        assertTrue(s.circleIds.size == 1 && s.lineIds.size == 1)
    }

    @Test
    fun extendToolReachesTheWall() {
        val s = Sketch()
        val l = s.addLine(0.0, 0.0, 5.0, 0.0)
        s.addLine(10.0, -5.0, 10.0, 5.0)
        val e = editor(s)
        e.setTool(Tool.EXTEND)
        e.click(5.0, 0.0)
        near(10.0, s.point(s.line(l).p2).x)
    }

    @Test
    fun offsetNeedsTheElementFirstThenTheSide() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val e = editor(s)
        e.offsetDistance = 2.0
        e.setTool(Tool.OFFSET)
        e.click(10.0, 0.0) // untere Kante wählen
        assertNotNull(e.offsetSource)
        assertEquals(4, s.lineIds.size)
        e.click(10.0, 0.1) // zu nah an der Kante: mehrdeutig
        assertEquals(4, s.lineIds.size)
        assertNotNull(e.offsetSource)
        e.click(10.0, -5.0) // darunter = außen
        assertEquals(8, s.lineIds.size)
        assertTrue(e.offsetSource == null)
        val xs = s.pointIds.map { s.point(it).x }
        near(-2.0, xs.min()); near(22.0, xs.max())
        assertEquals(4, rect.edges.size)
    }

    @Test
    fun cancelDropsAHalfFinishedOffset() {
        val s = Sketch()
        s.addLine(0.0, 0.0, 10.0, 0.0)
        val e = editor(s)
        e.setTool(Tool.OFFSET)
        e.click(5.0, 0.0)
        assertNotNull(e.offsetSource)
        e.cancelOperation()
        assertTrue(e.offsetSource == null && e.selection.isEmpty())
    }

    @Test
    fun filletToolRoundsATappedCornerWithTheConfiguredRadius() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val e = editor(s)
        e.filletRadius = 3.0
        e.setTool(Tool.FILLET)
        e.click(20.0, 0.0)
        assertEquals(1, s.arcIds.size)
        near(3.0, s.radiusOf(s.arcIds.single()), 1e-5)
        assertFalse(s.exists(rect.corners[1]))
        // zu großer Radius: Hinweis, keine Änderung
        e.filletRadius = 50.0
        e.click(0.0, 0.0)
        assertEquals(1, s.arcIds.size)
        assertNotNull(e.message)
    }

    @Test
    fun cornerToolInChamferModeCutsTheTappedCorner() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val e = editor(s)
        e.cornerMode = CornerMode.CHAMFER
        e.chamferDistance = 2.0
        e.setTool(Tool.FILLET)
        e.click(20.0, 0.0)
        assertEquals(5, s.lineIds.size)
        assertEquals(0, s.arcIds.size)
        assertFalse(s.exists(rect.corners[1]))
        e.undo()
        assertEquals(4, s.lineIds.size)
    }

    @Test
    fun mirrorWaitsForTheAxisAndThenMirrorsTheSelection() {
        val s = Sketch()
        val l = s.addLine(2.0, 1.0, 6.0, 4.0)
        val axis = s.addLine(0.0, -10.0, 0.0, 10.0)
        val e = editor(s)
        // ohne Auswahl: Hinweis
        e.startMirror()
        assertFalse(e.mirrorPending)
        e.click(4.0, 2.5) // Linie wählen
        assertTrue(e.selection.isNotEmpty())
        e.startMirror()
        assertTrue(e.mirrorPending)
        e.click(0.0, 5.0) // die senkrechte Linie ist die Achse
        assertFalse(e.mirrorPending)
        assertEquals(3, s.lineIds.size)
        val m = s.lineIds.single { it != l && it != axis }
        near(-2.0, s.point(s.line(m).p1).x); near(-6.0, s.point(s.line(m).p2).x)
    }

    @Test
    fun mirrorAcrossTheCoordinateAxesNeedsNoAxisElement() {
        val s = Sketch()
        val l = s.addLine(2.0, 1.0, 6.0, 4.0)
        val e = editor(s)
        e.click(4.0, 2.5)
        e.startMirror()
        e.mirrorAcrossAxis(horizontal = true) // an der X-Achse: y → −y
        assertFalse(e.mirrorPending)
        val m = s.lineIds.single { it != l && s.line(it).let { d -> !d.construction } }
        near(-1.0, s.point(s.line(m).p1).y); near(-4.0, s.point(s.line(m).p2).y)
        assertTrue(s.lineIds.any { s.line(it).construction }, "die Achse ist eine feste Konstruktionslinie")
        // Esc während des Wartens bricht ab
        e.click(4.0, 2.5)
        e.startMirror()
        e.cancelOperation()
        assertFalse(e.mirrorPending)
    }
    @Test
    fun aRowGrowsTowardsTheTappedSideOfTheDirectionLine() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 1.0)
        val guide = s.addLine(-20.0, -5.0, 20.0, -5.0)
        val e = editor(s)
        e.click(1.0, 0.0) // Kreis wählen
        e.patternCount = 3
        e.patternSpacing = 4.0
        e.startPattern(circular = false)
        assertTrue(e.patternPending)
        e.click(-15.0, -5.0) // links der Auswahl auf die Linie tippen: die Reihe wächst nach links
        assertFalse(e.patternPending)
        assertEquals(3, s.circleIds.size)
        near(-8.0, s.point(s.circle(s.circleIds.last()).center).x)
        assertTrue(s.lineIds.any { it != guide && s.line(it).construction }, "Hilfslinie für den Abstand")
        e.undo()
        assertEquals(1, s.circleIds.size)
        assertEquals(c, s.circleIds.single())
    }

    @Test
    fun aCircularPatternAroundTheOriginOrATappedPoint() {
        val s = Sketch()
        s.addCircle(10.0, 0.0, 1.0)
        val e = editor(s)
        e.click(11.0, 0.0)
        e.patternCount = 6
        e.startPattern(circular = true)
        e.patternAroundOrigin()
        assertFalse(e.patternPending)
        assertEquals(6, s.circleIds.size)
        near(-10.0, s.point(s.circle(s.circleIds[3]).center).x)
        // um einen angetippten Punkt; Abbrechen ohne Änderung
        e.click(11.0, 0.0)
        e.startPattern(circular = true)
        e.cancelOperation()
        assertFalse(e.patternPending)
        assertEquals(6, s.circleIds.size)
        val p = s.addPoint(30.0, 0.0)
        e.click(11.0, 0.0)
        e.patternCount = 2
        e.startPattern(circular = true)
        e.click(30.0, 0.0)
        assertEquals(7, s.circleIds.size)
        near(50.0, s.point(s.circle(s.circleIds.last()).center).x)
        assertTrue(s.exists(p))
    }
}
