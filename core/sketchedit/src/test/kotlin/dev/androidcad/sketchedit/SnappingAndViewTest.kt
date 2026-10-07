package dev.androidcad.sketchedit

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnappingAndViewTest {
    private val view = ViewTransform(10.0, 0.0, 0.0)  // 10 px/mm, Toleranz 12 px = 1,2 mm

    private fun near(expected: Double, actual: Double, tol: Double = 1e-9) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    @Test
    fun screenAndWorldRoundTrip() {
        val v = ViewTransform(7.5, 120.0, 340.0)
        val p = Vec2(12.5, -3.25)
        val back = v.toWorld(v.toScreen(p))
        near(p.x, back.x); near(p.y, back.y)
        near(340.0 - (-3.25) * 7.5, v.toScreen(p).y)        // y wird gespiegelt
    }

    @Test
    fun zoomKeepsThePointUnderTheCursorFixed() {
        val v = ViewTransform(4.0, 100.0, 200.0)
        val focus = Vec2(300.0, 250.0)
        val before = v.toWorld(focus)
        val zoomed = v.zoomAt(focus, 2.5)
        val after = zoomed.toWorld(focus)
        near(before.x, after.x); near(before.y, after.y)
        near(10.0, zoomed.scale)
        // Grenzen
        assertEquals(ViewTransform.MAX_SCALE, v.zoomAt(focus, 1e9).scale)
        assertEquals(ViewTransform.MIN_SCALE, v.zoomAt(focus, 1e-9).scale)
    }

    @Test
    fun gridStepFollowsTheOneTwoFiveSeries() {
        // mindestens 24 px zwischen Rasterlinien: Rasterweite = erster Wert der 1-2-5-Reihe ≥ 24 / Maßstab
        near(5.0, ViewTransform(10.0).gridStep())      // 2,4 mm → 5
        near(20.0, ViewTransform(2.0).gridStep())      // 12 mm → 20
        near(0.5, ViewTransform(50.0).gridStep())      // 0,48 mm → 0,5
        near(0.5, ViewTransform(100.0).gridStep())     // 0,24 mm → 0,5
        near(200.0, ViewTransform(0.2).gridStep())     // 120 mm → 200
        near(1.0, ViewTransform(24.0).gridStep())      // genau 1 mm → 1
    }

    @Test
    fun existingPointsWinOverEverythingElse() {
        val s = Sketch()
        val a = s.addPoint(10.0, 10.0); val b = s.addPoint(30.0, 10.0)
        s.addLine(a, b)
        val r = Snapping.snap(s, Vec2(10.5, 10.4), view)
        assertEquals(SnapKind.POINT, r.kind); assertEquals(a, r.point)
        assertEquals(Vec2(10.0, 10.0), r.position)
    }

    @Test
    fun midpointThenCurveThenAxisThenGridThenFree() {
        val s = Sketch()
        val line = s.addLine(10.0, 20.0, 30.0, 20.0)
        assertEquals(SnapKind.MIDPOINT, Snapping.snap(s, Vec2(20.2, 20.3), view).kind)
        val onLine = Snapping.snap(s, Vec2(15.0, 20.8), view)
        assertEquals(SnapKind.ON_LINE, onLine.kind); assertEquals(line, onLine.line); near(20.0, onLine.position.y)
        s.addCircle(60.0, 60.0, 10.0)
        val onCircle = Snapping.snap(s, Vec2(70.5, 60.0), view)
        assertEquals(SnapKind.ON_ROUND, onCircle.kind); near(70.0, onCircle.position.x)
        assertEquals(SnapKind.AXIS_X, Snapping.snap(s, Vec2(45.0, 0.5), view).kind)
        assertEquals(SnapKind.AXIS_Y, Snapping.snap(s, Vec2(0.4, 45.0), view).kind)
        val grid = Snapping.snap(s, Vec2(41.0, 33.0), view)
        assertEquals(SnapKind.GRID, grid.kind); assertEquals(Vec2(40.0, 35.0), grid.position)
        val free = Snapping.snap(s, Vec2(41.0, 33.0), view, SnapOptions(grid = false))
        assertEquals(SnapKind.FREE, free.kind); assertEquals(Vec2(41.0, 33.0), free.position)
    }

    @Test
    fun arcSnapRespectsTheArcRange() {
        val s = Sketch()
        val c = s.addPoint(50.0, 50.0); val st = s.addPoint(60.0, 50.0); val en = s.addPoint(50.0, 60.0)
        s.addArc(c, st, en)   // oberer rechter Viertelkreis
        assertEquals(SnapKind.ON_ROUND, Snapping.snap(s, Vec2(57.2, 57.2), view).kind)
        // gegenüberliegende Seite des Kreises gehört nicht zum Bogen
        assertTrue(Snapping.snap(s, Vec2(42.8, 42.8), view).kind != SnapKind.ON_ROUND)
    }

    @Test
    fun excludedPointsAreNotSnapTargets() {
        val s = Sketch()
        val a = s.addPoint(10.0, 10.0)
        val r = Snapping.snap(s, Vec2(10.2, 10.2), view, exclude = setOf(a))
        assertTrue(r.kind != SnapKind.POINT)
    }

    @Test
    fun hitTestPrefersPointsAndRespectsTolerance() {
        val s = Sketch()
        val a = s.addPoint(10.0, 10.0); val b = s.addPoint(40.0, 10.0)
        val line = s.addLine(a, b)
        val circle = s.addCircle(60.0, 60.0, 10.0)
        assertEquals(Sel.P(a), HitTest.hit(s, Vec2(10.2, 10.1), 1.0))
        assertEquals(Sel.L(line), HitTest.hit(s, Vec2(25.0, 10.5), 1.0))
        assertEquals(Sel.C(circle), HitTest.hit(s, Vec2(69.8, 60.0), 1.0))
        assertNull(HitTest.hit(s, Vec2(25.0, 13.0), 1.0))
        // Innenfläche des Kreises zählt nicht
        assertNull(HitTest.hit(s, Vec2(60.0, 60.0), 1.0).takeIf { it !is Sel.P })
    }

    @Test
    fun boxSelectionRequiresFullContainment() {
        val s = Sketch()
        val line = s.addLine(10.0, 10.0, 30.0, 10.0)
        val circle = s.addCircle(60.0, 60.0, 10.0)
        val inBox = HitTest.inBox(s, Vec2(0.0, 0.0), Vec2(40.0, 20.0))
        assertTrue(Sel.L(line) in inBox)
        assertTrue(Sel.C(circle) !in inBox)
        assertTrue(Sel.C(circle) in HitTest.inBox(s, Vec2(40.0, 40.0), Vec2(80.0, 80.0)))
        assertNotNull(inBox.firstOrNull { it is Sel.P })
    }

    @Test
    fun fitIncludesExtraPointsEvenForAnEmptySketch() {
        val editor = SketchEditor(Sketch())
        editor.fit(800.0, 600.0, listOf(Vec2(0.0, 0.0), Vec2(80.0, 50.0)))
        // 80 × 50 mm Umriss: Skalierung begrenzt durch die Breite, Mitte (40, 25) liegt in der Fenstermitte
        assertEquals(minOf((800.0 - 96) / 80.0, (600.0 - 96) / 50.0), editor.view.scale, 1e-9)
        val c = editor.view.toScreen(Vec2(40.0, 25.0))
        assertEquals(400.0, c.x, 1e-9); assertEquals(300.0, c.y, 1e-9)
        // ohne Zusatzpunkte bleibt das Verhalten wie bisher: Ursprung mittig
        val plain = SketchEditor(Sketch())
        plain.fit(800.0, 600.0)
        assertEquals(4.0, plain.view.scale, 1e-9)
        assertEquals(400.0, plain.view.toScreen(Vec2(0.0, 0.0)).x, 1e-9)
    }
}
