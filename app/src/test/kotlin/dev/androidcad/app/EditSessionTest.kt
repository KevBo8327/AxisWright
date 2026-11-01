package dev.androidcad.app

import dev.androidcad.model.ExtrudeFeature
import dev.androidcad.model.ModelSession
import dev.androidcad.project.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditSessionTest {
    // abgebrochener Scope: kein Aufbau, kein Autosave – nur der Zustand
    private fun workspaceWithRectangle(): WorkspaceState {
        val ws = WorkspaceState(CoroutineScope(Job().apply { cancel() }), ModelSession(ModelRepository(Files.createTempDirectory("models").toFile())), workspaceTestStrings)
        ws.handle.editor.sketch.addRectangle(0.0, 0.0, 20.0, 10.0)
        ws.handle.editor.recordExternalChange("Rechteck")
        return ws
    }

    private fun WorkspaceState.extrusion() = features.filterIsInstance<ExtrudeFeature>().singleOrNull()

    @Test
    fun aTapInTheTimelineSelectsWithoutOpeningTheCard() {
        val ws = workspaceWithRectangle()
        ws.createExtrusion()
        assertTrue(ws.panelVisible, "ein neuer Schritt öffnet seine Einstellungen")
        ws.closePanel()
        val id = ws.extrusion()!!.id
        ws.select(id, openCard = false)
        assertEquals(id, ws.selectedId)
        assertTrue(!ws.panelVisible, "nur gewählt, nicht zum Bearbeiten geöffnet")
        ws.openCard()
        assertTrue(ws.panelVisible)
    }

    @Test
    fun cancellingANewFeatureRemovesIt() {
        val ws = workspaceWithRectangle()
        ws.createExtrusion()
        assertTrue(ws.editing)
        ws.cancelEdit()
        assertNull(ws.extrusion(), "die eben angelegte Extrusion ist wieder weg")
        assertNull(ws.selectedId)
        assertEquals(4, ws.sketch.sketch.lineIds.size, "die Skizze bleibt")
    }

    @Test
    fun cancelRevertsChangesAndDoneKeepsThem() {
        val ws = workspaceWithRectangle()
        ws.createExtrusion(distance = 10.0)
        ws.closePanel() // ✓ Fertig
        val id = ws.extrusion()!!.id

        ws.select(id)
        ws.updateExtrusion(25.0, symmetric = true)
        ws.cancelEdit()
        assertEquals(10.0, ws.extrusion()!!.distance, "✕ nimmt die Änderung zurück")
        assertEquals(false, ws.extrusion()!!.symmetric)

        ws.select(id)
        ws.updateExtrusion(30.0, symmetric = false)
        ws.closePanel()
        assertEquals(30.0, ws.extrusion()!!.distance, "✓ behält sie")
    }
}
