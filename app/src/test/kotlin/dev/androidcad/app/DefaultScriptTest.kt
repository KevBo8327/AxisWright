package dev.androidcad.app

import dev.androidcad.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import androidx.compose.ui.text.input.TextFieldValue

/** Das beim ersten Start angelegte Standard-Skript erscheint erst nach einer Änderung in den Listen. */
class DefaultScriptTest {
    @Test
    fun untouchedDefaultScriptIsNotListed() {
        val repo = ProjectRepository(Files.createTempDirectory("projects").toFile())
        val state = CadState(repo, CoroutineScope(Job().apply { cancel() }), "Box", "cube(10);\n")
        assertEquals(1, repo.list().size, "angelegt ist es (der Editor braucht ein Projekt)")
        assertEquals(emptyList(), state.projects.map { it.name }, "aber nicht gelistet")
        state.newProject("Halter", "cube(3);\n")
        assertEquals(listOf("Halter"), state.projects.map { it.name })
    }

    /** Ein Autosave, das im Hintergrund erst nach flush() fertig wird, darf dessen neueren Stand nicht überschreiben. */
    @Test
    fun aLateBackgroundSaveNeverOverwritesANewerFlush() {
        val repo = ProjectRepository(Files.createTempDirectory("projects").toFile())
        val state = CadState(repo, CoroutineScope(Job().apply { cancel() }), "Box", "cube(10);\n")
        val id = state.project.id
        state.onEditorChange(TextFieldValue("cube(1);")) // Stand 1: das Autosave hat ihn erfasst, schreibt aber noch nicht
        state.onEditorChange(TextFieldValue("cube(2);"))
        state.flush() // App verlassen: Stand 2 wird sofort gesichert
        assertNull(state.saveIfNewer(1, id, "cube(1);", emptyMap(), state.project.name), "der ältere Stand wird übersprungen")
        assertEquals("cube(2);", repo.load(id)!!.source)
    }
}
