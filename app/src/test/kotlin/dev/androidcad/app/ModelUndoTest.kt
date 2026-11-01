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
}
