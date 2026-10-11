package dev.androidcad.sketchedit

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mit dem Finger gibt es keinen Schwebezeiger: die Vorschau muss beim Aufsetzen erscheinen und ihr Maß zeigen. */
class TouchPreviewTest {
    private fun editor(): SketchEditor {
        val e = SketchEditor(Sketch())
        e.setView(ViewTransform(10.0, 400.0, 300.0))
        e.gridSnap = false
        return e
    }

    private fun SketchEditor.screen(x: Double, y: Double) = view.toScreen(Vec2(x, y))

    @Test
    fun tappingTheNextPointShowsThePreviewBeforeLifting() {
        val e = editor()
        e.setTool(Tool.LINE)
        val a = e.screen(0.0, 0.0)
        e.pointerDown(a, PointerKind.TOUCH); e.pointerUp(a)
        e.pointerDown(e.screen(25.0, 0.0), PointerKind.TOUCH) // Finger liegt auf, noch nicht losgelassen
        val p = e.preview as Preview.Line
        assertTrue(kotlin.math.abs(p.b.x - 25.0) < 1e-6, "Vorschau reicht bis zum Finger: $p")
        assertEquals("25 mm", e.previewLabel())
    }

    @Test
    fun labelsForRectangleAndCircle() {
        val e = editor()
        e.setTool(Tool.RECTANGLE)
        val a = e.screen(0.0, 0.0)
        e.pointerDown(a, PointerKind.TOUCH); e.pointerUp(a)
        e.pointerDown(e.screen(30.0, 20.0), PointerKind.TOUCH)
        assertEquals("30 × 20 mm", e.previewLabel())
        e.pointerUp(e.screen(30.0, 20.0))
        assertNull(e.previewLabel(), "nach dem Setzen keine Vorschau mehr")

        e.setTool(Tool.CIRCLE)
        val c = e.screen(100.0, 100.0)
        e.pointerDown(c, PointerKind.TOUCH); e.pointerUp(c)
        e.pointerDown(e.screen(106.0, 100.0), PointerKind.TOUCH)
        assertEquals("Ø 12 mm", e.previewLabel())
    }
}
