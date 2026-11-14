package dev.androidcad.brep

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LifecycleTest {
    @Test
    fun concurrentCloseAndReadsNeverCrashOrLeakNativeError() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(20) {
                val shape = BrepShape.extrude(Profile.rectangle(0.0, 0.0, 10.0, 20.0), 5.0)
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val f1 = pool.submit {
                    ready.countDown()
                    go.await(5, TimeUnit.SECONDS)
                    try {
                        assertEquals(1000.0, shape.volume(), 1e-6)
                    } catch (_: IllegalStateException) {
                        // Erwartet, falls close() zuerst lief
                    }
                }
                val f2 = pool.submit {
                    ready.countDown()
                    go.await(5, TimeUnit.SECONDS)
                    shape.close()
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                go.countDown()
                f1.get(5, TimeUnit.SECONDS)
                f2.get(5, TimeUnit.SECONDS)
                assertFailsWith<IllegalStateException> { shape.volume() }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun deepCopyRemainsUsableAfterOriginalIsClosed() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "copy_export.step")
        try {
            val orig = BrepShape.extrude(Profile.rectangle(0.0, 0.0, 10.0, 10.0), 5.0)
            val copy = orig.copy()
            orig.close()
            assertFailsWith<IllegalStateException> { orig.volume() }
            assertTrue(abs(copy.volume() - 500.0) < 1e-6)
            copy.writeStep(file)
            assertTrue(file.readText().contains("MANIFOLD_SOLID_BREP"))
            copy.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun tessellateRejectsInvalidDeflectionsAndExcessiveMeshes() {
        BrepShape.extrude(Profile.circle(0.0, 0.0, 50.0), 50.0).use { cyl ->
            assertFailsWith<BrepException> { cyl.tessellate(linearDeflection = 0.0, angularDeflection = 0.5) }
            assertFailsWith<BrepException> { cyl.tessellate(linearDeflection = -0.1, angularDeflection = 0.5) }
            assertFailsWith<BrepException> { cyl.tessellate(linearDeflection = Double.NaN, angularDeflection = 0.5) }
            assertFailsWith<BrepException> { cyl.tessellate(linearDeflection = 1e-6, angularDeflection = 0.5) }
            assertFailsWith<BrepException> { cyl.tessellate(linearDeflection = 0.1, angularDeflection = 1e-5) }
        }
    }
}
