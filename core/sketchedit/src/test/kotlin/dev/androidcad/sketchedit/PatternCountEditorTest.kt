package dev.androidcad.sketchedit

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** „Anzahl im Muster“: eine Kopie antippen, Anzahl ändern, rückgängig machen. */
class PatternCountEditorTest {
    private fun SketchEditor.click(x: Double, y: Double) {
        val p = view.toScreen(Vec2(x, y)); pointerDown(p); pointerUp(p)
    }

    @Test
    fun tappingACopyOffersTheCountAndChangesIt() {
        val s = Sketch()
        val c = s.addCircle(0.0, 20.0, 3.0)
        SketchOps.linearPattern(s, emptyList(), listOf(c), Vec2(1.0, 0.0), 3, 15.0)
        val e = SketchEditor(s).apply { setView(ViewTransform(10.0, 400.0, 300.0)); gridSnap = false }
        e.click(33.0, 20.0) // Rand der dritten Kopie
        val opt = assertNotNull(e.actionOptions().firstOrNull { it.kind == ActionKind.PATTERN_COUNT }, e.actionOptions().toString())
        assertEquals(3.0, opt.defaultValue)
        e.requestAction(ActionKind.PATTERN_COUNT)
        assertEquals(3.0, e.pendingAction!!.initial)
        assertTrue(e.confirmPendingAction(5.0))
        assertEquals(5, s.circleIds.size)
        e.undo()
        assertEquals(3, e.sketch.circleIds.size)
        // keine ganze Zahl: abgelehnt, Skizze unverändert
        e.click(33.0, 20.0)
        e.requestAction(ActionKind.PATTERN_COUNT)
        assertTrue(!e.confirmPendingAction(2.5))
        assertEquals(3, e.sketch.circleIds.size)
        assertTrue(e.message!!.startsWith("Anzahl"))
    }

    @Test
    fun elementsOutsideAPatternDoNotOfferTheCount() {
        val s = Sketch()
        s.addCircle(0.0, 20.0, 3.0)
        val e = SketchEditor(s).apply { setView(ViewTransform(10.0, 400.0, 300.0)); gridSnap = false }
        e.click(3.0, 20.0)
        assertTrue(e.selection.isNotEmpty())
        assertNull(e.actionOptions().firstOrNull { it.kind == ActionKind.PATTERN_COUNT })
    }
}
