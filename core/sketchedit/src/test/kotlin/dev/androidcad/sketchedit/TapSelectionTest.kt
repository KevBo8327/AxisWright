package dev.androidcad.sketchedit

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ohne eigenes Auswahl-Werkzeug: Antippen fügt hinzu, erneutes Antippen nimmt heraus, ins Leere tippen hebt die Auswahl auf. */
class TapSelectionTest {
    private val sketch = Sketch()
    private val e = SketchEditor(sketch).apply { setView(ViewTransform(10.0, 400.0, 300.0)) }

    private fun tap(x: Double, y: Double) {
        val s = e.view.toScreen(Vec2(x, y))
        e.pointerDown(s, PointerKind.TOUCH); e.pointerUp(s)
    }

    @Test
    fun tapsAddToggleAndClear() {
        val a = sketch.addLine(10.0, 10.0, 40.0, 10.0)
        val b = sketch.addLine(10.0, 30.0, 40.0, 30.0)
        tap(25.0, 10.0); tap(25.0, 30.0)
        assertEquals(setOf<Sel>(Sel.L(a), Sel.L(b)), e.selection, "zwei Tipps, zwei Elemente – ohne „Mehrfach“")
        assertTrue(e.actionOptions().any { it.kind == ActionKind.PARALLEL }, "Bedingungen für beide")
        assertTrue(e.actionOptions().none { it.kind == ActionKind.ANGLE }, "kein Winkel zwischen parallelen Linien")
        tap(25.0, 10.0)
        assertEquals(setOf<Sel>(Sel.L(b)), e.selection, "erneut antippen nimmt heraus")
        tap(25.0, 60.0)
        assertTrue(e.selection.isEmpty(), "ins Leere tippen hebt auf")
    }
}
