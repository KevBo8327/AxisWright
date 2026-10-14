package dev.androidcad.sketchedit

import dev.androidcad.json.Json
import dev.androidcad.json.asObject
import dev.androidcad.sketch.PointLineDistanceDim
import dev.androidcad.sketch.PointOnLine
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.SketchCodec
import dev.androidcad.sketch.Vec2
import dev.androidcad.sketch.regions
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Werkzeug „Punkt“: Einzelpunkte setzen, behalten, speichern und bemaßen. */
class PointToolTest {
    private val sketch = Sketch()
    private val e = SketchEditor(sketch).apply { setView(ViewTransform(10.0, 400.0, 300.0)); setTool(Tool.POINT) }

    private fun tap(x: Double, y: Double) {
        val s = e.view.toScreen(Vec2(x, y))
        e.pointerDown(s, PointerKind.TOUCH); e.pointerUp(s)
    }

    private fun standalone() = sketch.standalonePointIds

    @Test
    fun pointStaysAfterCleanupAndCodecRoundTrip() {
        tap(25.0, 30.0)
        val p = standalone().single()
        assertTrue(abs(sketch.point(p).x - 25.0) < 1e-6 && abs(sketch.point(p).y - 30.0) < 1e-6)
        sketch.removeOrphanPoints()
        assertTrue(sketch.exists(p), "Einzelpunkt ist keine Waise")
        val loaded = SketchCodec.fromJson(Json.parse(Json.write(SketchCodec.toJson(sketch))).asObject())
        assertEquals(listOf(p), loaded.standalonePointIds)
        loaded.removeOrphanPoints()
        assertTrue(loaded.exists(p))
        assertTrue(loaded.regions().regions.isEmpty(), "Einzelpunkte bilden kein Profil")
        // ältere Dateien ohne das Feld bleiben lesbar
        val old = SketchCodec.toJson(sketch) - "standalonePoints"
        assertTrue(SketchCodec.fromJson(old).standalonePointIds.isEmpty())
        // Löschen entfernt den Punkt samt Markierung
        e.setTool(Tool.SELECT)
        tap(25.0, 30.0)
        assertEquals(setOf<Sel>(Sel.P(p)), e.selection, "Einzelpunkt ist wählbar")
        e.deleteSelection()
        assertTrue(!sketch.exists(p) && standalone().isEmpty())
    }

    @Test
    fun pointOnLineIsBoundAndCanBeDimensioned() {
        val line = sketch.addLine(10.0, 10.0, 40.0, 10.0)
        tap(20.0, 10.0)
        val onLine = standalone().single()
        assertTrue(sketch.constraintEntries.any { it.constraint == PointOnLine(onLine, line) }, "Fangen bindet den Punkt an die Linie")

        e.setTool(Tool.POINT)
        tap(25.0, 30.0)
        val free = standalone().single { it != onLine }
        e.setTool(Tool.DIMENSION)
        tap(25.0, 30.0); tap(30.0, 10.0)
        assertEquals(ActionKind.DISTANCE, e.pendingAction?.kind)
        e.confirmPendingAction(15.0)
        assertTrue(sketch.constraintEntries.any { (it.constraint as? PointLineDistanceDim)?.point == free })
        assertTrue(abs(abs(sketch.signedDistance(free, line)) - 15.0) < 1e-6)
    }

    @Test
    fun tapOnExistingPointAddsNothing() {
        sketch.addLine(10.0, 10.0, 40.0, 10.0)
        val before = sketch.pointIds.size
        tap(10.0, 10.0)
        assertEquals(before, sketch.pointIds.size)
        assertTrue(standalone().isEmpty())
    }
}
