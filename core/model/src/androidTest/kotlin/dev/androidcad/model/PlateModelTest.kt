package dev.androidcad.model

import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlateModelTest {
    private fun assertVolume(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) <= 1e-6 * expected, "Volumen erwartet $expected, erhalten $actual")

    @Test
    fun defaultPlateIsFullyConstrainedWithExactVolume() {
        val model = PlateModel()
        val solved = model.solveSketch()
        assertTrue(solved.isSolved, solved.toString())
        assertEquals(0, solved.degreesOfFreedom)
        model.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            assertVolume(PlateParams().expectedVolume, r.body!!.volume())
            assertEquals(2 + 4 + 4, r.body!!.faceCount) // Boden, Deckel, 4 Seiten, 4 Bohrungen
        }
    }

    @Test
    fun anyParameterCombinationRebuildsToTheExpectedVolume() {
        val model = PlateModel()
        val cases = listOf(
            PlateParams(120.0, 70.0, 15.0, 10.0, 20.0),
            PlateParams(40.0, 30.0, 6.0, 3.0, 2.0),
            PlateParams(100.0, 100.0, 12.0, 8.0, 5.0),
            PlateParams(), // zurück zum Anfang
        )
        for (p in cases) {
            model.apply(p)
            model.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                assertVolume(p.expectedVolume, r.body!!.volume())
            }
        }
    }

    @Test
    fun rebuildIsFast() {
        val model = PlateModel()
        repeat(3) { model.build().close() } // Aufwärmen
        val t0 = System.nanoTime()
        val n = 20
        repeat(n) { i ->
            model.apply(PlateParams(width = 60.0 + i * 3, thickness = 4.0 + i))
            model.build().use { r ->
                val mesh = r.body!!.tessellate(linearDeflection = 0.05, angularDeflection = 0.3)
                assertTrue(mesh.triangleCount > 0)
            }
        }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        android.util.Log.i("ModelPerf", "Parameteränderung → Lösen → Körper → Netz: Ø %.1f ms".format(ms))
        assertTrue(ms < 200, "Zu langsam: Ø $ms ms")
    }
}
