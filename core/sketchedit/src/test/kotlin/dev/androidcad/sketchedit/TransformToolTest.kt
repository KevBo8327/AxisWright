package dev.androidcad.sketchedit

import dev.androidcad.sketch.LengthDim
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.SketchText
import dev.androidcad.sketch.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransformToolTest {
    private fun editor(sketch: Sketch = Sketch()): SketchEditor {
        val e = SketchEditor(sketch)
        e.setView(ViewTransform(10.0, 400.0, 300.0))
        e.gridSnap = false
        return e
    }

    private fun SketchEditor.click(x: Double, y: Double) {
        val p = view.toScreen(Vec2(x, y))
        pointerDown(p, PointerKind.TOUCH)
        pointerUp(p)
    }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-5) =
        assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    @Test
    fun actionOptionTransformAvailableOnlyWhenGeometryIsSelected() {
        val s = Sketch()
        val e = editor(s)
        assertEquals(emptyList(), e.actionOptions().filter { it.kind == ActionKind.TRANSFORM })

        s.addLine(0.0, 0.0, 20.0, 0.0)
        e.click(10.0, 0.0)
        assertTrue(e.actionOptions().any { it.kind == ActionKind.TRANSFORM })

        e.clearSelection()
        assertFalse(e.actionOptions().any { it.kind == ActionKind.TRANSFORM })
    }

    @Test
    fun translateSelectionWithUndo() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val e = editor(s)
        e.selectAll()

        // Starten über Aktion
        e.requestAction(ActionKind.TRANSFORM)
        assertEquals(Tool.TRANSFORM, e.tool)
        assertTrue(e.transformPending)

        // Bezugspunkt antippen
        e.click(0.0, 0.0)
        val pt = assertNotNull(e.pendingTransform)
        near(0.0, pt.pivot.x); near(0.0, pt.pivot.y)

        // Verschiebung bestätigen
        val ok = e.confirmTransform(TransformMode.TRANSLATE, dx = 15.0, dy = 10.0)
        assertTrue(ok)
        assertNull(e.pendingTransform)
        assertEquals(Tool.SELECT, e.tool)

        val p0 = s.point(rect.corners[0])
        near(15.0, p0.x); near(10.0, p0.y)

        // Ein einzelner Rückgängig-Schritt stellt den Ursprungszustand wieder her
        assertTrue(e.canUndo)
        e.undo()
        val p0AfterUndo = s.point(rect.corners[0])
        near(0.0, p0AfterUndo.x); near(0.0, p0AfterUndo.y)
    }

    @Test
    fun rotateSelectionAroundPivotWithUndo() {
        val s = Sketch()
        val p1 = s.addPoint(0.0, 0.0)
        val p2 = s.addPoint(20.0, 0.0)
        s.addLine(p1, p2)
        val e = editor(s)
        e.selectAll()

        e.startTransform()
        // Bezugspunkt bei (0, 0)
        e.click(0.0, 0.0)
        assertNotNull(e.pendingTransform)

        e.confirmTransform(TransformMode.ROTATE, angleDegrees = 90.0)
        val pt2 = s.point(p2)
        near(0.0, pt2.x); near(20.0, pt2.y)

        assertTrue(e.canUndo)
        e.undo()
        val pt2Undo = s.point(p2)
        near(20.0, pt2Undo.x); near(0.0, pt2Undo.y)
    }

    @Test
    fun scaleSelectionWithDimensionAndRejectNegativeFactor() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val len = s.add(LengthDim(rect.edges[0], 20.0))
        val e = editor(s)
        e.selectAll()

        e.startTransform()
        e.click(0.0, 0.0)
        assertNotNull(e.pendingTransform)

        // Skalierungsfaktor <= 0 abgelehnt
        val fail = e.confirmTransform(TransformMode.SCALE, scale = -1.0)
        assertFalse(fail)
        assertEquals(SketchText.SCALE_NOT_POSITIVE, e.message?.key)

        // Nochmal starten und gültig skalieren
        e.selectAll()
        e.startTransform()
        e.click(0.0, 0.0)
        val ok = e.confirmTransform(TransformMode.SCALE, scale = 2.0)
        assertTrue(ok)

        near(40.0, (s.constraint(len) as LengthDim).value)
        val farCorner = s.point(rect.corners[2])
        near(40.0, farCorner.x); near(20.0, farCorner.y)

        assertTrue(e.canUndo)
        e.undo()
        near(20.0, (s.constraint(len) as LengthDim).value)
    }

    @Test
    fun copySelectionCreatesDuplicateWithUndo() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 10.0, 10.0)
        val e = editor(s)
        e.selectAll()

        e.startTransform()
        e.click(0.0, 0.0)
        assertNotNull(e.pendingTransform)

        val ok = e.confirmTransform(TransformMode.TRANSLATE, dx = 30.0, dy = 0.0, asCopy = true)
        assertTrue(ok)
        assertEquals(8, s.lineIds.size)

        assertTrue(e.canUndo)
        e.undo()
        assertEquals(4, s.lineIds.size)
    }

    @Test
    fun cancelingTransformReturnsToSelect() {
        val s = Sketch()
        s.addLine(0.0, 0.0, 10.0, 0.0)
        val e = editor(s)
        e.selectAll()

        e.startTransform()
        assertEquals(Tool.TRANSFORM, e.tool)
        e.cancelOperation()
        assertEquals(Tool.SELECT, e.tool)
        assertNull(e.pendingTransform)

        // Nach Pivot-Wahl abbrechen
        e.selectAll()
        e.startTransform()
        e.click(0.0, 0.0)
        assertNotNull(e.pendingTransform)
        e.cancelPendingTransform()
        assertNull(e.pendingTransform)
        assertEquals(Tool.SELECT, e.tool)
    }
}
