package dev.androidcad.app

import dev.androidcad.model.ModelSession
import dev.androidcad.model.Parameter
import dev.androidcad.project.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals

class WorkspaceEvaluateTest {
    private fun workspace() = WorkspaceState(
        CoroutineScope(Job().apply { cancel() }),
        ModelSession(ModelRepository(Files.createTempDirectory("models").toFile())),
        workspaceTestStrings,
    )

    @Test
    fun evaluateHandlesDecimalCommaInPlainNumbersAndFormulas() {
        val ws = workspace()
        ws.updateParameters(listOf(Parameter("wand", "2,5")))
        assertEquals(2.5, ws.evaluate("2,5"))
        assertEquals(3.75, ws.evaluate("wand * 1,5"))
        assertEquals(5.0, ws.evaluate("max(wand, 1) * 2"))
    }
}
