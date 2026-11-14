package dev.androidcad.app

import dev.androidcad.brep.BrepException
import dev.androidcad.brep.BrepShape
import dev.androidcad.brep.PlanarFace
import dev.androidcad.model.ModelSession
import dev.androidcad.project.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureAsyncTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun measureRunsOffCallerThreadAndDiscardsStaleResults() = runBlocking {
        val callerThread = Thread.currentThread().name
        val workerThread = AtomicReference<String?>()
        val uiDispatcher = newSingleThreadContext("ui-test")
        val workerDispatcher = newSingleThreadContext("worker-test")
        val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
        try {
            val ws = WorkspaceState(
                scope,
                ModelSession(ModelRepository(Files.createTempDirectory("measure-test").toFile())),
                workspaceTestStrings,
            )
            ws.measureDispatcher = workerDispatcher

            val out = ModelOutput(
                mesh = null,
                edges = null,
                volumeMm3 = 100.0,
                faceCount = 6,
                errors = emptyList(),
                millis = 1L,
                facePlanes = listOf(PlanarFace(Triple(0.0, 0.0, 0.0), Triple(0.0, 0.0, 1.0))),
            )
            val picks = listOf(BrepShape.Part.Face(1))
            ws.setMeasureStateForTest(out, picks)

            val job = ws.launchMeasure(
                out,
                picks,
                faceArea = {
                    workerThread.set(Thread.currentThread().name)
                    42.5
                },
                distance = { _, _ -> error("nicht erwartet") },
            )
            job.join()

            assertNotEquals(callerThread, workerThread.get(), "faceArea darf nicht auf dem Aufrufer-Thread laufen")
            assertEquals("worker-test", workerThread.get()?.substringBefore(" "))
            assertEquals(1, ws.measureMode?.lines?.size)

            // Abbruch / Moduswechsel verwirft späte Ergebnisse
            val entered = CountDownLatch(1)
            val allowFinish = CountDownLatch(1)
            val staleJob = ws.launchMeasure(
                out,
                picks,
                faceArea = {
                    entered.countDown()
                    allowFinish.await(5, TimeUnit.SECONDS)
                    99.0
                },
                distance = { _, _ -> error("nicht erwartet") },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ws.cancelMeasure()
            allowFinish.countDown()
            staleJob.join()
            assertNull(ws.measureMode, "Abgebrochene Messung darf measureMode nicht wiederherstellen")

            // BrepException wird sauber als Meldung dargestellt
            ws.setMeasureStateForTest(out, picks)
            val failJob = ws.launchMeasure(
                out,
                picks,
                faceArea = { throw BrepException("Fehler im Test") },
                distance = { _, _ -> error("nicht erwartet") },
            )
            failJob.join()
            assertEquals(1, ws.measureMode?.lines?.size)
        } finally {
            scope.cancel()
            uiDispatcher.close()
            workerDispatcher.close()
        }
    }
}
