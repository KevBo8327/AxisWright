package dev.androidcad.sketchedit

import dev.androidcad.sketch.AngleDim
import dev.androidcad.sketch.DiameterDim
import dev.androidcad.sketch.LengthDim
import dev.androidcad.sketch.PointLineDistanceDim
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Werkzeug „Bemaßen“: das Maß ergibt sich aus den angetippten Elementen, der Wert wird gleich abgefragt. */
class DimensionToolTest {
    private val sketch = Sketch()
    private val e = SketchEditor(sketch).apply { setView(ViewTransform(10.0, 400.0, 300.0)); setTool(Tool.DIMENSION) }

    private fun tap(x: Double, y: Double, additive: Boolean = false) {
        val s = e.view.toScreen(Vec2(x, y))
        e.pointerDown(s, PointerKind.TOUCH, additive = additive); e.pointerUp(s)
    }

    private inline fun <reified T> count() = sketch.constraintEntries.count { it.constraint is T }

    @Test
    fun oneTapOnALineAsksForItsLength() {
        sketch.addLine(10.0, 10.0, 40.0, 10.0)
        tap(25.0, 10.0)
        assertEquals(ActionKind.LENGTH, e.pendingAction?.kind, "ein Tipp genügt")
        assertTrue(abs(e.pendingAction!!.initial - 30.0) < 1e-6)
        e.confirmPendingAction(50.0)
        assertEquals(1, count<LengthDim>())
        assertTrue(e.selection.isEmpty() && e.tool == Tool.DIMENSION, "Werkzeug bleibt für das nächste Maß")
    }

    @Test
    fun circleAsksForTheDiameterRightAway() {
        sketch.addCircle(0.0, 0.0, 6.0)
        tap(6.0, 0.0)
        assertEquals(ActionKind.DIAMETER, e.pendingAction?.kind)
        assertTrue(abs(e.pendingAction!!.initial - 12.0) < 1e-6)
        e.confirmPendingAction(20.0)
        assertEquals(1, count<DiameterDim>())
    }

    @Test
    fun twoLinesGiveAngleOrDistance() {
        sketch.addLine(10.0, 10.0, 40.0, 10.0)
        sketch.addLine(10.0, 30.0, 40.0, 30.0)
        sketch.addLine(60.0, 0.0, 80.0, 20.0)
        tap(25.0, 10.0, additive = true); tap(25.0, 30.0, additive = true)
        assertEquals(ActionKind.DISTANCE, e.pendingAction?.kind, "parallel → Abstand")
        assertTrue(abs(e.pendingAction!!.initial - 20.0) < 1e-6)
        e.confirmPendingAction(25.0)
        assertEquals(1, count<PointLineDistanceDim>())

        tap(25.0, 10.0, additive = true); tap(70.0, 10.0, additive = true)
        assertEquals(ActionKind.ANGLE, e.pendingAction?.kind, "schräg → Winkel")
        e.cancelPendingAction()
        assertTrue(e.selection.isEmpty())
        assertEquals(0, count<AngleDim>())
    }

    @Test
    fun endpointAndItsOwnLineGiveNoZeroDistance() {
        sketch.addLine(10.0, 10.0, 40.0, 10.0)
        tap(40.0, 10.0) // Endpunkt
        tap(25.0, 10.0) // seine eigene Linie
        assertNull(e.pendingAction, "kein Dialog mit 0")
        assertTrue(e.selection.isEmpty())
    }

    @Test
    fun pointToLineDistanceIsPrefilled() {
        sketch.addLine(10.0, 10.0, 40.0, 10.0)
        sketch.addLine(10.0, 30.0, 40.0, 30.0)
        tap(10.0, 30.0) // Punkt der oberen Linie
        tap(25.0, 10.0) // untere Linie
        assertEquals(ActionKind.DISTANCE, e.pendingAction?.kind)
        assertTrue(abs(e.pendingAction!!.initial - 20.0) < 1e-6, "vorbelegt mit dem aktuellen Abstand: ${e.pendingAction}")
    }
}
