package dev.androidcad.model

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RevolveFeatureTest {
    private fun near(expected: Double, actual: Double, relTol: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= relTol * abs(expected), "erwartet $expected, erhalten $actual")

    @Test
    fun tubeAroundTheSketchYAxis() {
        val doc = Document()
        val sk = doc.addSketch("Profil", Workplane.XZ)
        val r = sk.sketch.addRectangle(5.0, 0.0, 10.0, 20.0)
        val f = doc.addRevolve("Rohr", sk, RevolveAxis.SketchY)
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            near(PI * 75 * 20, res.body!!.volume())
            // Mantelflächen tragen die Namen der Skizzenkanten: außen = rechte Kante (edges[1]), innen = linke (edges[3])
            assertEquals(1, res.faces(FaceRef(f.id, FaceRole.SIDE, r.edges[1].value)).size)
            assertEquals(1, res.faces(FaceRef(f.id, FaceRole.SIDE, r.edges[3].value)).size)
        }
    }

    @Test
    fun partialRevolveAroundAConstructionLineCutsAGroove() {
        val doc = Document()
        // Block 40 × 40 × 20, darin eine Viertelkreis-Nut um eine senkrechte Achse an der Ecke (0, 0)
        val base = doc.addSketch("Block")
        base.sketch.addRectangle(0.0, 0.0, 40.0, 40.0)
        doc.addExtrude("Block", base, 20.0)
        val sk = doc.addSketch("Nut", Workplane.XZ)
        sk.sketch.addRectangle(10.0, 15.0, 20.0, 25.0) // reicht über die Oberkante z = 20 hinaus
        val axis = sk.sketch.addLine(0.0, 0.0, 0.0, 30.0, construction = true)
        // XZ-Skizze: Skizzen-y ist Welt-z, die Normale zeigt nach −y; +90° um die Achse dreht +x nach −Normale = +y, in den Block
        val cut = doc.addRevolve("Nut", sk, RevolveAxis.Line(axis), 90.0, BodyOp.CUT)
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            near(40.0 * 40 * 20 - PI * (400 - 100) / 4 * 5, res.body!!.volume())
        }
    }

    @Test
    fun crossingTheAxisIsReportedAtTheFeature() {
        val doc = Document()
        val sk = doc.addSketch("Profil")
        sk.sketch.addRectangle(-5.0, 0.0, 10.0, 20.0)
        val f = doc.addRevolve("Welle", sk, RevolveAxis.SketchY)
        doc.build().use { res ->
            assertEquals(ModelText.REGION_CROSSES_AXIS, res.errors[f.id]?.key)
            assertEquals(null, res.body)
        }
    }

    @Test
    fun missingAxisLineIsReported() {
        val doc = Document()
        val sk = doc.addSketch("Profil")
        sk.sketch.addRectangle(5.0, 0.0, 10.0, 20.0)
        val axis = sk.sketch.addLine(0.0, 0.0, 0.0, 30.0, construction = true)
        val f = doc.addRevolve("Welle", sk, RevolveAxis.Line(axis))
        sk.sketch.remove(axis)
        doc.build().use { res -> assertEquals(ModelText.REVOLVE_AXIS_GONE, res.errors[f.id]?.key) }
    }

    @Test
    fun revolveCanBePatterned() {
        val doc = Document()
        val base = doc.addSketch("Platte")
        base.sketch.addRectangle(-50.0, -50.0, 50.0, 50.0)
        doc.addExtrude("Platte", base, 5.0)
        // kleiner Knopf: Halbschnitt um eine senkrechte Achse bei x = 30 (XZ-Ebene), sitzt auf der Platte
        val sk = doc.addSketch("Knopf", Workplane.XZ)
        sk.sketch.addRectangle(30.0, 5.0, 34.0, 10.0)
        val axis = sk.sketch.addLine(30.0, 0.0, 30.0, 20.0, construction = true)
        val knob = doc.addRevolve("Knopf", sk, RevolveAxis.Line(axis))
        doc.addPattern("Muster", knob.id, PatternKind.Circular(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), 4))
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            near(100.0 * 100 * 5 + 4 * PI * 16 * 5, res.body!!.volume())
        }
    }
}
