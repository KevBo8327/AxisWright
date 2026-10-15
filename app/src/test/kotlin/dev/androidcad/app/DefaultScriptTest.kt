package dev.androidcad.app

import dev.androidcad.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals

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
}
