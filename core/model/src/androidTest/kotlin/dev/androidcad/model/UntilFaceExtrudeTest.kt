package dev.androidcad.model

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extrusion „bis Fläche“: endet an einer gewählten parallelen ebenen Zielfläche und folgt Modelländerungen.
 */
class UntilFaceExtrudeTest {
    private fun near(expected: Double, actual: Double, relTol: Double = 1e-4) =
        assertTrue(abs(expected - actual) <= relTol * abs(expected), "erwartet $expected, erhalten $actual")

    @Test
    fun extrudeUntilParallelFaceAndFollowChanges() {
        val doc = Document()
        // Block 1 auf XY, Höhe 20 mm
        val sk1 = doc.addSketch("Basis")
        sk1.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val box = doc.addExtrude("BasisBlock", sk1, 20.0)

        // Skizze 2 auf einer parallelen Ebene bei z = 5 mm
        val sk2 = doc.addSketch("ZweiteSkizze", Workplane.XY.offset(5.0))
        sk2.sketch.addRectangle(50.0, 0.0, 10.0, 10.0)
        val topFace = FaceRef(box.id, FaceRole.TOP)

        // Extrusion bis zur Oberseite von Block 1 (z = 20 mm): Abstand = 20 - 5 = 15 mm
        val f2 = doc.addExtrude("BisOberflaeche", sk2, 1.0, untilFace = topFace)

        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            // Block 1: 40 * 30 * 20 = 24000; Block 2: 10 * 10 * 15 = 1500; Summe = 25500
            near(24000.0 + 1500.0, res.body!!.volume())
        }

        // Modelländerung: Block 1 wächst von 20 auf 50 mm Höhe
        doc.update(box.copy(distance = 50.0))
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            // Block 1: 40 * 30 * 50 = 60000; Block 2: 10 * 10 * (50 - 5) = 4500; Summe = 64500
            near(60000.0 + 4500.0, res.body!!.volume())
        }
    }

    @Test
    fun extrudeUntilFaceWithSecondSide() {
        val doc = Document()
        val sk1 = doc.addSketch("Basis")
        sk1.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val box = doc.addExtrude("BasisBlock", sk1, 20.0)

        val sk2 = doc.addSketch("ZweiteSkizze", Workplane.XY.offset(5.0))
        sk2.sketch.addRectangle(50.0, 0.0, 10.0, 10.0)
        val topFace = FaceRef(box.id, FaceRole.TOP)

        // Seite 1: bis z = 20 (Abstand 15 mm). Seite 2: Gegenseite 5 mm (in -z). Gesamthöhe = 20 mm.
        doc.addExtrude("BisFlaecheMitGegenseite", sk2, 1.0, untilFace = topFace, distance2 = 5.0)

        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            near(24000.0 + 10.0 * 10.0 * 20.0, res.body!!.volume())
        }
    }

    @Test
    fun nonParallelFaceIsRejected() {
        val doc = Document()
        val sk1 = doc.addSketch("Basis")
        val r = sk1.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val box = doc.addExtrude("BasisBlock", sk1, 20.0)

        val sk2 = doc.addSketch("ZweiteSkizze", Workplane.XY.offset(5.0))
        sk2.sketch.addRectangle(50.0, 0.0, 10.0, 10.0)

        // Seitenfläche von Block 1 ist senkrecht zur Skizzenebene XY (also nicht parallel)
        val sideFace = FaceRef(box.id, FaceRole.SIDE, r.edges[0].value)
        val f = doc.addExtrude("Schraeg", sk2, 1.0, untilFace = sideFace)

        doc.build().use { res ->
            assertEquals(ModelText.FACE_NOT_PARALLEL, res.errors[f.id]?.key)
        }
    }

    @Test
    fun untilFaceWithoutBodyIsRejected() {
        val doc = Document()
        val sk = doc.addSketch("Skizze")
        sk.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        val fakeFace = FaceRef(FeatureId(999), FaceRole.TOP)
        val f = doc.addExtrude("OhneKoerper", sk, 1.0, untilFace = fakeFace)

        doc.build().use { res ->
            assertEquals(ModelText.NO_BODY, res.errors[f.id]?.key)
        }
    }
}
