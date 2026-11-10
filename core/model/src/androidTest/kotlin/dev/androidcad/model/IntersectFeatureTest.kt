package dev.androidcad.model

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Schnittmenge (BodyOp.INTERSECT) als dritte Verknüpfungsart für Extrusion und Drehung.
 */
class IntersectFeatureTest {
    private fun near(expected: Double, actual: Double, relTol: Double = 1e-4) =
        assertTrue(abs(expected - actual) <= relTol * abs(expected), "erwartet $expected, erhalten $actual")

    @Test
    fun extrudeIntersectionWithExistingBody() {
        val doc = Document()
        // Block 1: 0..40 x 0..40 x 0..20 -> Volumen = 32000
        val sk1 = doc.addSketch("Block1")
        sk1.sketch.addRectangle(0.0, 0.0, 40.0, 40.0)
        doc.addExtrude("Basis", sk1, 20.0)

        // Block 2: 20..60 x 0..40 x 0..20 mit Schnittmenge
        val sk2 = doc.addSketch("Block2")
        sk2.sketch.addRectangle(20.0, 0.0, 40.0, 40.0) // 20..60 in x
        val intersect = doc.addExtrude("Schnitt", sk2, 20.0, BodyOp.INTERSECT)

        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            // Schnittmenge: 20..40 x 0..40 x 0..20 -> 20 * 40 * 20 = 16000
            near(16000.0, res.body!!.volume())
            assertEquals(6, res.body.faceCount)
        }
    }

    @Test
    fun revolveIntersectionWithExistingBody() {
        val doc = Document()
        // Block 40 x 40 x 20 von x = -20..20, y = -20..20, z = 0..20
        val sk1 = doc.addSketch("Basis")
        sk1.sketch.addRectangle(-20.0, -20.0, 40.0, 40.0)
        doc.addExtrude("Basis", sk1, 20.0)

        // Zylinder per Drehung um SketchY (Radius 15, Höhe 20) als Schnittmenge
        val sk2 = doc.addSketch("Profil", Workplane.XZ)
        sk2.sketch.addRectangle(0.0, 0.0, 15.0, 20.0)
        doc.addRevolve("ZylinderSchnitt", sk2, RevolveAxis.SketchY, 360.0, BodyOp.INTERSECT)

        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            // Schnittmenge eines Zylinders (r=15, h=20) komplett innerhalb des Blocks (-20..20) ist der Zylinder selbst
            near(PI * 15 * 15 * 20, res.body!!.volume())
        }
    }

    @Test
    fun firstFeatureWithIntersectIsRejected() {
        val doc = Document()
        val sk = doc.addSketch("Skizze")
        sk.sketch.addRectangle(0.0, 0.0, 20.0, 20.0)
        val f = doc.addExtrude("SchnittOhneKoerper", sk, 10.0, BodyOp.INTERSECT)

        doc.build().use { res ->
            assertFeatureFailedWithReason(res.errors[f.id], ModelText.NO_BODY_TO_INTERSECT)
            assertEquals(null, res.body)
        }
    }

    @Test
    fun firstRevolveWithIntersectIsRejected() {
        val doc = Document()
        val sk = doc.addSketch("Skizze")
        sk.sketch.addRectangle(5.0, 0.0, 10.0, 20.0)
        val f = doc.addRevolve("DrehungOhneKoerper", sk, RevolveAxis.SketchY, 360.0, BodyOp.INTERSECT)

        doc.build().use { res ->
            assertFeatureFailedWithReason(res.errors[f.id], ModelText.NO_BODY_TO_INTERSECT)
            assertEquals(null, res.body)
        }
    }
}
