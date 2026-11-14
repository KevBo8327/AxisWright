package dev.androidcad.app

import dev.androidcad.model.BasePlane
import dev.androidcad.model.ModelSession
import dev.androidcad.model.PlaneSpec
import dev.androidcad.model.SketchFeature
import dev.androidcad.project.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelUndoTest {
    // abgebrochener Scope: kein Aufbau, kein Autosave – nur der Zustand
    private fun workspace() = WorkspaceState(
        CoroutineScope(Job().apply { cancel() }),
        ModelSession(ModelRepository(Files.createTempDirectory("models").toFile())),
        workspaceTestStrings,
    )

    private fun WorkspaceState.names() = features.map { it.name }

    @Test
    fun undoAndRedoWalkThroughModelChanges() {
        val ws = workspace()
        assertFalse(ws.canUndo)
        ws.addSketch(PlaneSpec(BasePlane.XZ))
        ws.select(ws.features.first().id)
        ws.renameSelected("Grundriss")
        assertEquals(listOf("Grundriss", "Skizze 2"), ws.names())

        ws.undo()
        assertEquals(listOf("Skizze 1", "Skizze 2"), ws.names())
        ws.undo()
        assertEquals(listOf("Skizze 1"), ws.names())
        assertFalse(ws.canUndo)

        ws.redo(); ws.redo()
        assertEquals(listOf("Grundriss", "Skizze 2"), ws.names())
        assertFalse(ws.canRedo)
    }

    @Test
    fun deletedFeatureComesBackWithItsSketchContent() {
        val ws = workspace()
        ws.addSketch(PlaneSpec(BasePlane.XY))
        val sk = ws.features.last() as SketchFeature
        ws.handle.editor.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        ws.handle.editor.recordExternalChange("Rechteck") // wie eine Zeichenaktion: der Editor meldet die Änderung
        ws.deleteSelected()
        assertTrue(ws.features.none { it.id == sk.id })
        ws.undo()
        val back = ws.features.first { it.id == sk.id } as SketchFeature
        assertEquals(4, back.sketch.lineIds.size, "das Rechteck ist wieder da")
    }

    @Test
    fun slowSliderDragProducesOneUndoStepPerDrag() {
        var now = 0L
        val ws = workspace().apply { clock = { now } }
        ws.handle.editor.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        ws.handle.editor.recordExternalChange("Rechteck")
        ws.createExtrusion(distance = 10.0)

        for (i in 1..5) {
            now += 900L // langsamer als 800ms Coalesce-Fenster
            ws.onSliderChange { ws.updateExtrusion(10.0 + i * 2.0, symmetric = false) }
        }
        ws.finishSliderDrag()
        assertEquals(20.0, ws.extrusion!!.distance)

        now += 100L // schnell hintereinander: zweiter Zug muss eigener Schritt sein
        ws.onSliderChange { ws.updateExtrusion(30.0, symmetric = false) }
        ws.finishSliderDrag()
        assertEquals(30.0, ws.extrusion!!.distance)

        ws.undo()
        assertEquals(20.0, ws.extrusion!!.distance, "Zweiter Zug rückgängig")
        ws.undo()
        assertEquals(10.0, ws.extrusion!!.distance, "Erster langsamer Zug komplett als ein Schritt rückgängig")
    }

    @Test
    fun abortedSliderDragWithoutFinishKeepsValueAndSplitsFollowingEdit() {
        var now = 0L
        val ws = workspace().apply { clock = { now } }
        ws.handle.editor.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        ws.handle.editor.recordExternalChange("Rechteck")
        ws.createExtrusion(distance = 10.0)

        ws.onSliderChange { ws.updateExtrusion(22.0, symmetric = false) }
        now += 10L
        ws.onSliderChange { ws.updateExtrusion(25.0, symmetric = false) }
        // finishSliderDrag wird NICHT aufgerufen (abgebrochener Zug / Panelwechsel)
        now += 10L
        ws.updateExtrusion(25.0, symmetric = true)
        assertEquals(25.0, ws.extrusion!!.distance)
        assertTrue(ws.extrusion!!.symmetric)

        ws.undo()
        assertFalse(ws.extrusion!!.symmetric, "Schalter-Klick ist eigener Schritt")
        assertEquals(25.0, ws.extrusion!!.distance, "Letzter Slider-Wert darf nicht verloren gehen")
        ws.undo()
        assertEquals(10.0, ws.extrusion!!.distance, "Abgebrochener Slider-Zug hat eigenen Undo-Schritt")
    }

    @Test
    fun markDirtyDoesNotEncodeDocumentSynchronously() {
        val ws = workspace()
        ws.handle.editor.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        ws.handle.editor.recordExternalChange("Rechteck")
        ws.createExtrusion(distance = 10.0)
        val before = ws.syncEncodeCount

        for (i in 1..10) {
            ws.onSliderChange { ws.updateExtrusion(10.0 + i, symmetric = false) }
        }
        ws.finishSliderDrag()
        assertEquals(before, ws.syncEncodeCount, "Schieberegler-Ticks dürfen DocumentCodec.encode nicht synchron auf dem Main-Thread aufrufen")
    }
}
