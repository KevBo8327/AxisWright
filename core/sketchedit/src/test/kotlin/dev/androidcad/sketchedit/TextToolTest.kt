package dev.androidcad.sketchedit

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextToolTest {
    private val sketch = Sketch()
    private val e = SketchEditor(sketch).apply {
        setView(ViewTransform(10.0, 400.0, 300.0))
        setTool(Tool.TEXT)
    }

    private fun tap(x: Double, y: Double) {
        val s = e.view.toScreen(Vec2(x, y))
        e.pointerDown(s, PointerKind.TOUCH)
        e.pointerUp(s)
    }

    @Test
    fun tapOpensTextDialogAndConfirms() {
        tap(15.0, 20.0)
        val dialog = assertNotNull(e.textEdit)
        assertNull(dialog.id)
        assertEquals(15.0, dialog.position.x, 1e-6)
        assertEquals(20.0, dialog.position.y, 1e-6)

        assertTrue(e.confirmText("TestCAD", 12.0, 30.0))
        assertNull(e.textEdit)
        assertEquals(Tool.SELECT, e.tool, "Nach Eingabe zurück zum Auswählen")

        assertEquals(1, sketch.textIds.size)
        val tId = sketch.textIds.single()
        val text = sketch.text(tId)
        assertEquals("TestCAD", text.text)
        assertEquals(12.0, text.height)
        assertEquals(30.0, text.angle)
        assertEquals(12.0, e.lastTextHeight, "Zuletzt genutzte Texthöhe gemerkt")
    }

    @Test
    fun cancelTextResetsState() {
        tap(10.0, 10.0)
        assertNotNull(e.textEdit)
        e.cancelText()
        assertNull(e.textEdit)
        assertEquals(Tool.SELECT, e.tool)
        assertTrue(sketch.textIds.isEmpty())
    }

    @Test
    fun tapExistingTextOpensEditDialog() {
        val id = sketch.addText(20.0, 30.0, "Alt", 10.0, 0.0)
        e.setTool(Tool.SELECT)

        // Antippen im Textbereich (z. B. bei x = 25, y = 35)
        tap(25.0, 35.0)
        val edit = assertNotNull(e.textEdit)
        assertEquals(id, edit.id)
        assertEquals("Alt", edit.initialText)
        assertEquals(10.0, edit.initialHeight)
        assertEquals(0.0, edit.initialAngle)

        assertTrue(e.confirmText("Neu", 14.0, 45.0))
        assertNull(e.textEdit)
        assertEquals("Neu", sketch.text(id).text)
        assertEquals(14.0, sketch.text(id).height)
        assertEquals(45.0, sketch.text(id).angle)
    }

    @Test
    fun selectAndDeleteText() {
        val id = sketch.addText(10.0, 10.0, "Weg", 8.0)
        e.setTool(Tool.SELECT)
        tap(14.0, 14.0)
        assertEquals(setOf<Sel>(Sel.T(id)), e.selection)

        e.deleteSelection()
        assertTrue(sketch.textIds.isEmpty())
        assertTrue(e.selection.isEmpty())
    }
}
