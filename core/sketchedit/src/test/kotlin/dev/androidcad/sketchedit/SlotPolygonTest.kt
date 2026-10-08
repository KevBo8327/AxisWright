package dev.androidcad.sketchedit

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.SolveOptions
import dev.androidcad.sketch.Vec2
import dev.androidcad.sketch.regions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Zeichenvarianten Gruppe 3: Langloch und regelmäßiges Vieleck. */
class SlotPolygonTest {
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
    fun aSlotIsOneClosedRegionWithFiveFreedoms() {
        val e = editor()
        e.setTool(Tool.SLOT)
        e.click(10.0, 10.0); e.click(40.0, 10.0)
        assertEquals(2, e.drawStep)
        e.click(25.0, 16.0) // halbe Breite 6
        val s = e.sketch
        assertEquals(2, s.arcIds.size)
        assertEquals(2, s.lineIds.count { !s.line(it).construction })
        assertEquals(1, s.lineIds.count { s.line(it).construction }, "Mittellinie")
        assertEquals(1, s.regions().regions.size)
        s.arcIds.forEach { near(6.0, s.radiusOf(it)) }
        // Mittellinie waagrecht gefangen: 5 − 1 = 4 Freiheitsgrade (Lage, Länge, Breite)
        assertEquals(4, s.solve(SolveOptions(analyze = true)).degreesOfFreedom)
        // Fläche: Rechteck 30 × 12 plus Kreis r = 6
        near(30.0 * 12 + PI * 36, s.regions().regions.single().area, 1e-3)
    }

    @Test
    fun aSlotFollowsItsWidth() {
        val e = editor()
        e.setTool(Tool.SLOT)
        e.click(0.0, 0.0); e.click(20.0, 0.0); e.click(10.0, 5.0)
        val s = e.sketch
        s.addRadius(s.arcIds.first(), 8.0)
        assertTrue(s.solve().isSolved)
        s.arcIds.forEach { near(8.0, s.radiusOf(it)) }
        // die Linien berühren beide Bögen weiterhin
        val top = s.lineIds.first { !s.line(it).construction }
        s.arcIds.forEach { a ->
            val c = s.point(s.arc(a).center); val l = s.line(top); val p = s.point(l.p1); val q = s.point(l.p2)
            near(8.0, abs((q.x - p.x) * (c.y - p.y) - (q.y - p.y) * (c.x - p.x)) / hypot(q.x - p.x, q.y - p.y), 1e-6)
        }
    }

    @Test
    fun aHexagonWithCornersOnTheCircle() {
        val e = editor()
        e.setTool(Tool.POLYGON)
        assertEquals(6, e.polygonSides)
        e.click(0.0, 0.0); e.click(10.0, 0.0)
        val s = e.sketch
        assertEquals(6, s.lineIds.size)
        val circle = s.circle(s.circleIds.single())
        assertTrue(circle.construction)
        near(10.0, circle.radius)
        s.lineIds.forEach { near(10.0, s.length(it)) }   // Sechseck: Seite = Umkreisradius
        assertEquals(1, s.regions().regions.size)
        // Mitte im Ursprung: Größe und Drehung bleiben frei
        assertEquals(2, s.solve(SolveOptions(analyze = true)).degreesOfFreedom)
    }

    @Test
    fun aHexagonAcrossFlatsFollowsTheWrenchSize() {
        val e = editor()
        e.setTool(Tool.POLYGON); e.polygonMode = PolygonMode.CIRCUMSCRIBED
        e.click(0.0, 0.0); e.click(0.0, 6.5) // Schlüsselweite 13, eine Seite oben waagrecht
        val s = e.sketch
        assertEquals(2, s.circleIds.size, "Um- und Inkreis")
        val circle = s.circleIds.minBy { s.circle(it).radius }
        near(6.5, s.circle(circle).radius)
        // Mitte im Ursprung: nur Größe und Drehung frei – kein Kippen wie bei einer Raute
        assertEquals(2, s.solve(SolveOptions(analyze = true)).degreesOfFreedom)
        s.lineIds.forEach { near(13.0 * tan(PI / 6), s.length(it), 1e-6) }
        // Schlüsselweite per Durchmesser ändern: das Sechseck wächst mit
        s.addDiameter(circle, 17.0)
        assertTrue(s.solve().isSolved)
        s.lineIds.forEach { near(17.0 * tan(PI / 6), s.length(it), 1e-5) }
        val far = s.pointIds.filter { it != s.origin }.maxOf { hypot(s.point(it).x, s.point(it).y) }
        near(8.5 / cos(PI / 6), far, 1e-5)
    }

    @Test
    fun theNumberOfSidesIsLimited() {
        val e = editor()
        e.setTool(Tool.POLYGON)
        e.polygonSides = 2; assertEquals(3, e.polygonSides)
        e.polygonSides = 500; assertEquals(SketchEditor.MAX_POLYGON_SIDES, e.polygonSides)
        e.polygonSides = 5
        e.click(0.0, 0.0); e.click(0.0, 10.0)
        assertEquals(5, e.sketch.lineIds.size)
        // Quadrat mit Seiten am Kreis: bleibt ein Quadrat (keine Raute)
        val q = editor(); q.setTool(Tool.POLYGON); q.polygonMode = PolygonMode.CIRCUMSCRIBED; q.polygonSides = 4
        q.click(0.0, 0.0); q.click(5.0, 0.0)
        assertEquals(2, q.sketch.solve(SolveOptions(analyze = true)).degreesOfFreedom)
    }
}
