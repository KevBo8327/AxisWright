package dev.androidcad.sketchedit

import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.TangentLineRound
import dev.androidcad.sketch.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Zeichenvarianten Gruppe 2: tangentiale Linie, Kreis an zwei bzw. drei Tangenten. */
class TangentDrawTest {
    private fun editor(sketch: Sketch): SketchEditor {
        val e = SketchEditor(sketch)
        e.setView(ViewTransform(10.0, 400.0, 300.0))
        e.gridSnap = false
        return e
    }

    private fun SketchEditor.click(x: Double, y: Double) {
        val p = view.toScreen(Vec2(x, y)); pointerDown(p); pointerUp(p)
    }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-5) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    /** Abstand eines Punkts von der Geraden durch die Linie. */
    private fun Sketch.distanceToLine(line: dev.androidcad.sketch.LineId, p: Vec2): Double {
        val l = line(line); val a = point(l.p1); val b = point(l.p2)
        return abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / hypot(b.x - a.x, b.y - a.y)
    }

    /** Zwei feste Kreise: r = 5 um (0, 0) und r = 3 um (30, 0). */
    private fun twoCircles(): Sketch {
        val s = Sketch()
        val a = s.addCircle(0.0, 0.0, 5.0); val b = s.addCircle(30.0, 0.0, 3.0)
        s.add(Fixed(s.circle(a).center, 0.0, 0.0)); s.add(Fixed(s.circle(b).center, 30.0, 0.0))
        s.addRadius(a, 5.0); s.addRadius(b, 3.0)
        return s
    }

    @Test
    fun aTangentLineBetweenTwoCirclesTouchesBoth() {
        val s = twoCircles()
        val e = editor(s)
        e.setTool(Tool.LINE); e.lineMode = LineMode.TANGENT
        e.click(0.5, 4.97)   // oben auf dem großen Kreis
        e.click(30.3, 2.98)  // oben auf dem kleinen
        e.cancelOperation()
        val line = s.lineIds.single()
        assertEquals(2, s.constraintEntries.count { it.constraint is TangentLineRound })
        near(5.0, s.distanceToLine(line, Vec2(0.0, 0.0)), 1e-6)
        near(3.0, s.distanceToLine(line, Vec2(30.0, 0.0)), 1e-6)
        // äußere Tangente oben: beide Berührpunkte liegen oberhalb der Mittellinie
        s.line(line).let { assertTrue(s.point(it.p1).y > 0 && s.point(it.p2).y > 0) }
    }

    @Test
    fun aLineFromAPointToACircleEndsAtTheTangentPoint() {
        val s = twoCircles()
        val start = s.addPoint(0.0, 20.0)
        s.add(Fixed(start, 0.0, 20.0))
        val e = editor(s)
        e.setTool(Tool.LINE); e.lineMode = LineMode.TANGENT
        e.click(0.0, 20.0)   // fester Punkt
        e.click(4.5, 2.2)    // rechts oben auf dem großen Kreis
        e.cancelOperation()
        val line = s.lineIds.single()
        near(5.0, s.distanceToLine(line, Vec2(0.0, 0.0)), 1e-6)
        // Berührpunkt: Abstand zum Startpunkt = √(20² − 5²)
        val end = s.point(s.line(line).p2)
        near(sqrt(400.0 - 25.0), hypot(end.x, end.y - 20.0), 1e-3) // entlang der Tangente kaum bestimmt: Lösertoleranz
        near(0.0, s.point(start).x); near(20.0, s.point(start).y)
    }

    @Test
    fun aTangentLineNeedsACircleAtOneEnd() {
        val e = editor(Sketch())
        e.setTool(Tool.LINE); e.lineMode = LineMode.TANGENT
        e.click(0.0, 0.0); e.click(10.0, 3.0)
        assertEquals(0, e.sketch.lineIds.size)
        assertEquals(EditText.TANGENT_NEEDS_ROUND, e.message?.key)
    }

    @Test
    fun aCircleBetweenTwoLinesTouchesBothNearTheTap() {
        val s = Sketch()
        val bottom = s.addLine(0.0, 0.0, 40.0, 0.0); val left = s.addLine(0.0, 0.0, 0.0, 40.0)
        s.lineIds.forEach { id -> s.line(id).let { s.add(Fixed(it.p1, s.point(it.p1).x, s.point(it.p1).y)); s.add(Fixed(it.p2, s.point(it.p2).x, s.point(it.p2).y)) } }
        val e = editor(s)
        e.setTool(Tool.CIRCLE); e.circleMode = CircleMode.TANGENT2
        e.click(20.0, 0.0); e.click(0.0, 20.0)
        assertEquals(2, e.drawStep)
        e.click(8.0, 6.0)    // Lage in der Ecke
        val c = s.circle(s.circleIds.single())
        val m = s.point(c.center)
        near(c.radius, m.x, 1e-6); near(c.radius, m.y, 1e-6)   // berührt beide Achsen-Linien
        assertTrue(c.radius in 4.0..10.0, "Größe aus der Lage: ${c.radius}")
        assertTrue(s.constraintEntries.count { it.constraint is TangentLineRound } == 2)
        assertTrue(e.selection.isEmpty(), "Auswahl-Hervorhebung wieder weg")
        assertTrue(bottom != left)
    }

    @Test
    fun aCircleTouchingThreeElementsIsFullyDetermined() {
        // Dreieck-Inkreis: Linien y = 0, x = 0 und x + y = 30
        val s = Sketch()
        val l1 = s.addLine(0.0, 0.0, 30.0, 0.0); val l2 = s.addLine(0.0, 0.0, 0.0, 30.0); val l3 = s.addLine(30.0, 0.0, 0.0, 30.0)
        for (id in listOf(l1, l2, l3)) s.line(id).let { s.add(Fixed(it.p1, s.point(it.p1).x, s.point(it.p1).y)); s.add(Fixed(it.p2, s.point(it.p2).x, s.point(it.p2).y)) }
        val e = editor(s)
        e.setTool(Tool.CIRCLE); e.circleMode = CircleMode.TANGENT3
        e.click(9.0, 0.0); e.click(0.0, 9.0); e.click(15.0, 15.0)
        val c = s.circle(s.circleIds.single())
        val r = 30.0 / (2 + sqrt(2.0))   // Inkreisradius des gleichschenklig-rechtwinkligen Dreiecks
        near(r, c.radius, 1e-5)
        near(r, s.point(c.center).x, 1e-5); near(r, s.point(c.center).y, 1e-5)
    }

    @Test
    fun aCircleTangentToTwoCirclesFromOutside() {
        val s = twoCircles()
        val e = editor(s)
        e.setTool(Tool.CIRCLE); e.circleMode = CircleMode.TANGENT2
        e.click(4.0, 3.0); e.click(29.0, 2.83)
        e.click(15.0, 8.0)
        val c = s.circle(s.circleIds.last())
        val m = s.point(c.center)
        near(5.0 + c.radius, hypot(m.x, m.y), 1e-6)
        near(3.0 + c.radius, hypot(m.x - 30.0, m.y), 1e-6)
    }

    @Test
    fun cancellingClearsThePicks() {
        val s = twoCircles()
        val e = editor(s)
        e.setTool(Tool.CIRCLE); e.circleMode = CircleMode.TANGENT2
        e.click(5.0, 0.0)
        assertTrue(e.drawingInProgress && e.selection.size == 1)
        e.cancelOperation()
        assertTrue(!e.drawingInProgress && e.selection.isEmpty())
        e.click(15.0, 15.0)  // ins Leere
        assertEquals(EditText.TAP_ELEMENT, e.message?.key)
    }

    @Test
    fun existingUndimensionedCirclesStayPut() {
        // zwei freie Kreise ohne Maße: die Tangente und der Tangentenkreis dürfen sie nicht verschieben oder vergrößern
        val s = Sketch()
        val a = s.addCircle(0.0, 0.0, 5.0); val b = s.addCircle(30.0, 0.0, 3.0)
        val e = editor(s)
        e.setTool(Tool.LINE); e.lineMode = LineMode.TANGENT
        e.click(0.5, 4.97); e.click(30.3, 2.98)
        e.cancelOperation()
        e.setTool(Tool.CIRCLE); e.circleMode = CircleMode.TANGENT2
        e.click(-5.0, 0.0); e.click(15.0, 4.0); e.click(4.0, 9.0)
        assertEquals(3, s.circleIds.size, e.message.toString())
        near(5.0, s.circle(a).radius); near(3.0, s.circle(b).radius)
        near(0.0, s.point(s.circle(a).center).x); near(30.0, s.point(s.circle(b).center).x)
    }
}
