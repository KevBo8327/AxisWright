package dev.androidcad.brep

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class StepTest {
    @Test
    fun boxWithHoleIsWrittenAsSolidInMillimetres() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "test.step")
        val profile = Profile.rectangle(0.0, 0.0, 40.0, 20.0).withHole(listOf(ProfileSegment.Circle(10.0, 10.0, 3.0)))
        BrepShape.extrude(profile, 10.0).use { it.writeStep(file) }
        val text = file.readText()
        assertTrue(text.startsWith("ISO-10303-21;"), text.take(40))
        assertTrue(text.contains("MANIFOLD_SOLID_BREP"), "ein Volumenkörper")
        assertTrue(text.contains("CYLINDRICAL_SURFACE"), "die Bohrung als exakter Zylinder")
        assertTrue(text.contains(".MILLI."), "Einheit Millimeter")
        file.delete()
    }

    @Test
    fun concurrentStepExportsAreThreadSafe() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val threads = 4
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val files = List(threads) { File(dir, "concurrent_$it.step") }
        try {
            val futures = files.map { file ->
                pool.submit {
                    start.await(5, TimeUnit.SECONDS)
                    BrepShape.extrude(Profile.rectangle(0.0, 0.0, 20.0, 10.0), 5.0).use { shape ->
                        shape.writeStep(file)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            files.forEach { file ->
                val text = file.readText()
                assertTrue(text.contains("MANIFOLD_SOLID_BREP"), "Gültige STEP-Datei erwartet: ${file.name}")
            }
        } finally {
            files.forEach { it.delete() }
            pool.shutdownNow()
        }
    }
}
