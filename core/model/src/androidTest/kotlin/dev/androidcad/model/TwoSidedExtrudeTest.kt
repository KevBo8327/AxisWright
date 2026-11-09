package dev.androidcad.model

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extrusion auf zwei Seiten mit unabhängigen Längen (distance und distance2).
 */
class TwoSidedExtrudeTest {
    private fun near(expected: Double, actual: Double, relTol: Double = 1e-4) =
        assertTrue(abs(expected - actual) <= relTol * abs(expected), "erwartet $expected, erhalten $actual")

    @Test
    fun positiveDistanceWithSecondSide() {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        val r = sk.sketch.addRectangle(0.0, 0.0, 20.0, 30.0)
        // distance = 10.0 (in +z), distance2 = 5.0 (in -z) -> Gesamthöhe 15.0
        val ex = doc.addExtrude("ZweiSeiten", sk, 10.0, distance2 = 5.0)

        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            near(20.0 * 30.0 * 15.0, res.body!!.volume())
            val b = res.body.bounds()
            assertEquals(-5.0, b[2], 1e-3)
            assertEquals(10.0, b[5], 1e-3)
            assertEquals(1, res.faces(FaceRef(ex.id, FaceRole.TOP)).size)
            assertEquals(1, res.faces(FaceRef(ex.id, FaceRole.BOTTOM)).size)
        }
    }

    @Test
    fun negativeDistanceWithSecondSide() {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        val r = sk.sketch.addRectangle(0.0, 0.0, 20.0, 30.0)
        // distance = -10.0 (in -z), distance2 = 5.0 (in +z) -> Gesamthöhe 15.0
        val ex = doc.addExtrude("ZweiSeitenNegativ", sk, -10.0, distance2 = 5.0)

        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            near(20.0 * 30.0 * 15.0, res.body!!.volume())
            val b = res.body.bounds()
            assertEquals(-10.0, b[2], 1e-3)
            assertEquals(5.0, b[5], 1e-3)
            assertEquals(1, res.faces(FaceRef(ex.id, FaceRole.BOTTOM)).size)
            assertEquals(1, res.faces(FaceRef(ex.id, FaceRole.TOP)).size)
        }
    }
}
