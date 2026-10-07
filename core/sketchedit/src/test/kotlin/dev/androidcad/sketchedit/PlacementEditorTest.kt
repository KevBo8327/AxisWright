package dev.androidcad.sketchedit

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Achsen als Bezug, „Ecke/Mittig auf Ursprung“ und der Hinweis „Lage noch frei“ – über echte Zeigereingaben. */
class PlacementEditorTest {
    private fun editor(sketch: Sketch = Sketch()): SketchEditor {
        val e = SketchEditor(sketch)
        e.setView(ViewTransform(10.0, 400.0, 300.0))
        e.gridSnap = false
        return e
    }

    private fun SketchEditor.click(x: Double, y: Double, additive: Boolean = false) {
        val p = view.toScreen(Vec2(x, y)); pointerDown(p, additive = additive); pointerUp(p)
    }

    private fun SketchEditor.drag(x0: Double, y0: Double, x1: Double, y1: Double) {
        val a = view.toScreen(Vec2(x0, y0)); val b = view.toScreen(Vec2(x1, y1))
        pointerDown(a); pointerMove(Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)); pointerMove(b); pointerUp(b)
    }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-5) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    /** Rechteck 40 × 20 mit Breite und Höhe. */
    private fun sized(s: Sketch): Sketch.Rectangle =
        s.addRectangle(10.0, 10.0, 50.0, 30.0).also { s.addLength(it.edges[0], 40.0); s.addLength(it.edges[1], 20.0) }

    @Test
    fun tappingAnAxisSelectsItAsAReferenceForConstraints() {
        val s = Sketch()
        val c = s.addCircle(30.0, 12.0, 4.0)
        val e = editor(s)
        e.click(-60.0, 0.0) // irgendwo auf der X-Achse
        val axis = e.selection.single() as Sel.L
        assertEquals(true, SketchOps.axisOf(s, axis.id))
        // Mittelpunkt dazu: „Auf Kurve“ und „Abstand“ werden angeboten
        e.click(30.0, 12.0, additive = true)
        val kinds = e.actionOptions().map { it.kind }
        assertTrue(ActionKind.POINT_ON in kinds && ActionKind.DISTANCE in kinds, kinds.toString())
        assertTrue(ActionKind.FIX !in kinds && ActionKind.MIDPOINT !in kinds, "nichts, was die Achse selbst ändern würde: $kinds")
        e.requestAction(ActionKind.POINT_ON)
        near(0.0, s.point(s.circle(c).center).y)
    }

    @Test
    fun aBoxStartingOnAnAxisStillSelectsAndSkipsTheReferences() {
        val s = Sketch()
        sized(s)
        val e = editor(s)
        e.click(0.0, -30.0) // Y-Achse anlegen und wählen
        e.drag(0.0, 40.0, 60.0, 5.0) // Rahmen beginnt auf der Y-Achse
        assertEquals(4, e.selection.count { it is Sel.L }, "nur das Rechteck, nicht die Achse")
        e.selectAll()
        assertTrue(e.selection.none { it is Sel.L && SketchOps.axisOf(s, it.id) != null })
        assertTrue(e.selection.none { it is Sel.P && it.id == s.origin })
    }

    @Test
    fun theStatusTellsWhenOnlyThePositionIsFreeAndTheQuickActionsFixIt() {
        val s = Sketch()
        sized(s)
        val e = editor(s)
        e.requestAction(ActionKind.LENGTH) // ohne Auswahl: nichts
        e.click(30.0, 10.0) // untere Kante – irgendwas, damit der Editor neu löst
        e.clearSelection()
        assertTrue(e.statusText().startsWith("Form bestimmt – Lage noch frei"), e.statusText())
        e.selectAll()
        assertTrue(ActionKind.ORIGIN_CENTER in e.actionOptions().map { it.kind }, e.actionOptions().toString())
        e.requestAction(ActionKind.ORIGIN_CENTER)
        assertEquals(null, e.message)
        assertEquals("Vollständig bestimmt", e.statusText())
        // rückgängig, dann mit der Ecke
        e.undo()
        e.selectAll()
        e.requestAction(ActionKind.ORIGIN_CORNER)
        assertEquals("Vollständig bestimmt", e.statusText())
        val xs = s.pointIds.filter { it != s.origin && !SketchOps.isAxisPoint(s, it) }.map { s.point(it).x }
        near(0.0, xs.min()); near(40.0, xs.max())
    }
    @Test
    fun centeringOnAFaceIsOnlyOfferedWithAFaceAndUsesItsMiddle() {
        val s = Sketch()
        val r = sized(s)
        val e = editor(s)
        e.selectAll()
        assertTrue(ActionKind.FACE_CENTER !in e.actionOptions().map { it.kind }, "ohne Fläche nicht")
        // Fläche 0…100 × 0…60 als zwei feste Punkte (wie projizierte Ecken)
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(100.0, 60.0)
        s.add(dev.androidcad.sketch.Fixed(a, 0.0, 0.0)); s.add(dev.androidcad.sketch.Fixed(b, 100.0, 60.0))
        e.faceCenter = { SketchOps.CenterTarget.Diagonal(a, b) }
        e.selectAll()
        assertTrue(ActionKind.FACE_CENTER in e.actionOptions().map { it.kind })
        e.requestAction(ActionKind.FACE_CENTER)
        near(30.0, s.point(r.corners[0]).x); near(20.0, s.point(r.corners[0]).y)
        assertEquals("Vollständig bestimmt", e.statusText())
    }
    @Test
    fun holeAndEdgeCenteringNeedTheShapeAndExactlyOneProjectedReference() {
        val s = Sketch()
        val r = sized(s)
        // projizierte Bohrung und Kante (feste Konstruktionsgeometrie)
        val hc = s.addPoint(100.0, 40.0); s.add(dev.androidcad.sketch.Fixed(hc, 100.0, 40.0))
        val hole = s.addCircle(hc, 8.0, construction = true)
        val a = s.addPoint(0.0, -20.0); val b = s.addPoint(160.0, -20.0)
        s.add(dev.androidcad.sketch.Fixed(a, 0.0, -20.0)); s.add(dev.androidcad.sketch.Fixed(b, 160.0, -20.0))
        val edge = s.addLine(a, b, construction = true)
        val e = editor(s)
        e.setView(ViewTransform(3.0, 100.0, 400.0))
        e.drag(5.0, 35.0, 55.0, 5.0) // nur das Rechteck
        assertTrue(r.edges.all { Sel.L(it) in e.selection })
        var kinds = e.actionOptions().map { it.kind }
        assertTrue(ActionKind.HOLE_CENTER !in kinds && ActionKind.EDGE_CENTER !in kinds, "ohne Bezug nicht: $kinds")
        e.click(108.0, 40.0, additive = true) // Bohrung dazu
        kinds = e.actionOptions().map { it.kind }
        assertTrue(ActionKind.HOLE_CENTER in kinds && ActionKind.EDGE_CENTER !in kinds, kinds.toString())
        e.requestAction(ActionKind.HOLE_CENTER)
        near(80.0, s.point(r.corners[0]).x); near(30.0, s.point(r.corners[0]).y)
        e.undo()
        e.drag(5.0, 35.0, 55.0, 5.0)
        e.click(130.0, -20.0, additive = true) // Kante dazu
        assertTrue(ActionKind.EDGE_CENTER in e.actionOptions().map { it.kind })
        e.requestAction(ActionKind.EDGE_CENTER)
        near(60.0, s.point(r.corners[0]).x); near(100.0, s.point(r.corners[2]).x)
        assertTrue(hole.value > 0)
    }
}
