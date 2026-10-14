package dev.androidcad.sketchedit

import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.SolveOptions
import dev.androidcad.sketch.TangentLineRound
import dev.androidcad.sketch.TangentRoundRound
import dev.androidcad.sketch.Vec2
import dev.androidcad.sketch.regions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Zeichenvarianten: Rechteck vom Mittelpunkt / aus drei Punkten, Kreis aus zwei / drei Punkten, Bogen aus drei Punkten / tangential. */
class DrawVariantsTest {
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
    private fun near(expected: Vec2, actual: Vec2, tol: Double = 1e-5) { near(expected.x, actual.x, tol); near(expected.y, actual.y, tol) }

    @Test
    fun aCenterRectangleStaysCenteredOnTheOrigin() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.rectMode = RectMode.CENTER
        e.click(0.0, 0.0)  // Ursprung (gefangen)
        e.click(20.0, 10.0)
        val s = e.sketch
        assertEquals(1, s.lineIds.count { s.line(it).construction }, "Hilfsdiagonale")
        assertEquals(4, s.lineIds.count { !s.line(it).construction })
        // Breite und Höhe ändern: die Mitte bleibt im Ursprung
        val edges = s.lineIds.filter { !s.line(it).construction }
        s.addLength(edges[0], 30.0); s.addLength(edges[1], 8.0)
        assertTrue(s.solve().isSolved)
        val xs = s.pointIds.filter { it != s.origin }.map { s.point(it) }
        near(0.0, (xs.maxOf { it.x } + xs.minOf { it.x }) / 2); near(0.0, (xs.maxOf { it.y } + xs.minOf { it.y }) / 2)
        near(30.0, xs.maxOf { it.x } - xs.minOf { it.x })
        assertEquals(0, s.solve(SolveOptions(analyze = true)).degreesOfFreedom, "Mitte + Breite + Höhe bestimmen alles")
    }

    @Test
    fun aThreePointRectangleIsTiltedAndKeepsItsRightAngles() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.rectMode = RectMode.THREE_POINTS
        e.click(5.0, 5.0); e.click(13.0, 11.0) // Kante 10 mm unter atan(6/8)
        assertEquals(2, e.drawStep)
        e.click(0.0, 20.0)
        val s = e.sketch
        assertEquals(4, s.lineIds.size)
        assertEquals(1, s.regions().regions.size)
        val lens = s.lineIds.map { s.length(it) }.sorted()
        near(10.0, lens[0]); near(10.0, lens[1])
        // Höhe = Abstand von (0, 20) zur Kante: |(−5, 15) · (−0.6, 0.8)| = 15
        near(15.0, lens[2]); near(15.0, lens[3])
        // 5 Freiheitsgrade: Lage (2), Drehung, Breite, Höhe
        assertEquals(5, s.solve(SolveOptions(analyze = true)).degreesOfFreedom)
    }

    @Test
    fun aTwoPointCircleUsesTheDistanceAsDiameterAndPassesThroughSnappedPoints() {
        val s = Sketch()
        val a = s.addPoint(-10.0, 0.0)
        s.add(Fixed(a, -10.0, 0.0))
        val e = editor(s)
        e.setTool(Tool.CIRCLE); e.circleMode = CircleMode.TWO_POINTS
        e.click(-10.0, 0.0); e.click(10.0, 0.0)
        val c = s.circle(s.circleIds.single())
        near(10.0, c.radius); near(Vec2(0.0, 0.0), s.point(c.center))
        // der gefangene Punkt bleibt auf dem Kreis, wenn der Radius sich ändert
        s.addRadius(s.circleIds.single(), 4.0)
        assertTrue(s.solve().isSolved)
        val m = s.point(s.circle(s.circleIds.single()).center)
        near(4.0, hypot(m.x + 10.0, m.y))
    }

    @Test
    fun aThreePointCircleRunsThroughAllThree() {
        val e = editor()
        e.setTool(Tool.CIRCLE); e.circleMode = CircleMode.THREE_POINTS
        e.click(10.0, 0.0); e.click(0.0, 10.0); e.click(-10.0, 0.0)
        val s = e.sketch
        val c = s.circle(s.circleIds.single())
        near(10.0, c.radius); near(Vec2(0.0, 0.0), s.point(c.center), 1e-4)
        // drei Punkte auf einer Geraden: Hinweis, kein Kreis (nach der fertigen Form ist das Werkzeug beendet: neu wählen)
        e.setTool(Tool.CIRCLE)
        e.click(0.0, 20.0); e.click(5.0, 20.0); e.click(10.0, 20.0)
        assertEquals(1, s.circleIds.size)
        assertTrue(e.message!!.contains("Geraden"))
    }

    @Test
    fun aThreePointArcGoesThroughTheMiddlePointInEitherDirection() {
        for (through in listOf(Vec2(0.0, 10.0), Vec2(0.0, -10.0))) {
            val e = editor()
            e.setTool(Tool.ARC); e.arcMode = ArcMode.THREE_POINTS
            e.click(10.0, 0.0); e.click(-10.0, 0.0); e.click(through.x, through.y)
            val s = e.sketch
            val a = s.arc(s.arcIds.single())
            near(Vec2(0.0, 0.0), s.point(a.center), 1e-4)
            // gegen den Uhrzeigersinn von start nach end muss der Bogen durch den Mittelpunkt laufen
            val st = s.point(a.start); val en = s.point(a.end)
            val ang = { p: Vec2 -> Math.atan2(p.y, p.x) }
            val tau = 2 * Math.PI
            fun turn(p: Vec2) = ((ang(p) - ang(st)) % tau + tau) % tau
            assertTrue(turn(through) < turn(en), "Bogen läuft durch $through")
        }
    }

    @Test
    fun aTangentArcContinuesALineAndChains() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(0.0, 0.0); e.click(20.0, 0.0)
        e.cancelOperation()
        e.setTool(Tool.ARC); e.arcMode = ArcMode.TANGENT
        e.click(20.0, 0.0)          // Ende der Linie
        assertTrue(e.drawingInProgress && e.drawingChain)
        e.click(20.0, 20.0)         // Halbkreis nach oben: Mittelpunkt (20, 10)
        val s = e.sketch
        val a1 = s.arcIds.single()
        near(Vec2(20.0, 10.0), s.point(s.arc(a1).center), 1e-4)
        assertTrue(s.constraintEntries.any { (it.constraint as? TangentLineRound)?.round == a1 })
        // weiter: zweiter Bogen, tangential am ersten (S-Kurve zurück nach links oben)
        e.click(20.0 - 10.0, 30.0)
        assertEquals(2, s.arcIds.size)
        val a2 = s.arcIds.last()
        assertTrue(s.constraintEntries.any { (it.constraint as? TangentRoundRound)?.let { t -> t.b == a2 } == true })
        val c1 = s.point(s.arc(a1).center); val c2 = s.point(s.arc(a2).center)
        // Berührpunkt (20, 20) liegt auf der Verbindung der Mittelpunkte
        val cross = (c2.x - c1.x) * (20.0 - c1.y) - (c2.y - c1.y) * (20.0 - c1.x)
        near(0.0, cross, 1e-4)
        assertTrue(s.solve().isSolved)
        e.cancelOperation()
        assertTrue(!e.drawingInProgress)
    }

    @Test
    fun aTangentArcNeedsALooseEnd() {
        val e = editor()
        e.setTool(Tool.ARC); e.arcMode = ArcMode.TANGENT
        e.click(5.0, 5.0)
        assertTrue(!e.drawingInProgress)
        assertTrue(e.message!!.startsWith("Tangential"))
    }

    @Test
    fun switchingTheVariantDropsAHalfDrawnShape() {
        val e = editor()
        e.setTool(Tool.CIRCLE); e.circleMode = CircleMode.THREE_POINTS
        e.click(1.0, 1.0); e.click(5.0, 1.0)
        e.circleMode = CircleMode.CENTER
        assertEquals(0, e.drawStep)
        e.click(0.0, 20.0); e.click(3.0, 20.0)
        near(3.0, e.sketch.circle(e.sketch.circleIds.single()).radius)
    }
}
