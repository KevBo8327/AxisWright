package dev.androidcad.app

import androidx.compose.ui.text.input.TextFieldValue
import dev.androidcad.model.BasePlane
import dev.androidcad.model.ModelSession
import dev.androidcad.model.PlaneSpec
import dev.androidcad.project.ModelRepository
import dev.androidcad.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaveFlushTest {
    @Test
    fun stopFlushAsyncDoesNotBlockMainThreadWhenSaveLockIsContendedAndSavesBothEditors() {
        val dir = Files.createTempDirectory("flush-test").toFile()
        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val modelRepo = ModelRepository(dir.resolve("models"))
        val scriptRepo = ProjectRepository(dir.resolve("scripts"))
        val ws = WorkspaceState(activityScope, ModelSession(modelRepo), workspaceTestStrings)
        val script = CadState(scriptRepo, activityScope, "Box", "cube(10);\n")
        val hub = ProjectHub(ws, script, dir.resolve("active"))

        ws.addSketch(PlaneSpec(BasePlane.XZ))
        script.onEditorChange(TextFieldValue("cube(42);"))

        val lockEntered = CountDownLatch(1)
        val holder = Thread {
            synchronized(ws.saveLock) {
                lockEntered.countDown()
                Thread.sleep(400)
            }
        }
        holder.start()
        assertTrue(lockEntered.await(2, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        val jobs = hub.flushAsync()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs < 150, "onStop-Sicherung blockierte Aufrufer-Thread ${elapsedMs}ms lang auf saveLock")

        holder.join(2000)
        runBlocking { jobs.forEach { it.join() } }

        val reloadedModel = ModelSession(modelRepo).restoreLast()?.document
        assertNotNull(reloadedModel, "Modell muss nach flushAsync gespeichert sein")
        assertEquals(2, reloadedModel.features.size)
        assertEquals("cube(42);", scriptRepo.load(script.project.id)!!.source)
    }

    @Test
    fun stopFlushAsyncCompletesWithoutDataLossEvenIfActivityScopeCancelsImmediately() {
        val dir = Files.createTempDirectory("flush-destroy-test").toFile()
        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val modelRepo = ModelRepository(dir.resolve("models"))
        val scriptRepo = ProjectRepository(dir.resolve("scripts"))
        val ws = WorkspaceState(activityScope, ModelSession(modelRepo), workspaceTestStrings)
        val script = CadState(scriptRepo, activityScope, "Box", "cube(10);\n")
        val hub = ProjectHub(ws, script, dir.resolve("active"))

        ws.addSketch(PlaneSpec(BasePlane.YZ))
        script.onEditorChange(TextFieldValue("sphere(15);"))

        val jobs = hub.flushAsync()
        activityScope.cancel() // simuliert sofortiges onDestroy nach onStop
        assertTrue(jobs.isNotEmpty())

        // Ein sofort neu gestartetes WorkspaceState/CadState wartet über SaveCoordinator auf den Abschluss
        val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val freshWs = WorkspaceState(freshScope, ModelSession(modelRepo), workspaceTestStrings)
        val freshScript = CadState(scriptRepo, freshScope, "Box", "cube(10);\n")
        assertEquals(2, freshWs.features.size, "Alle Skizzen müssen trotz sofortigem onDestroy erhalten bleiben")
        assertEquals("sphere(15);", freshScript.editor.text)
        runBlocking { jobs.forEach { it.join() } }
    }
}
