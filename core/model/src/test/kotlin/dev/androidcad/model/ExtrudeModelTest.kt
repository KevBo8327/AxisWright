package dev.androidcad.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtrudeModelTest {
    private class Setup {
        val doc = Document()
        val sk1 = doc.addSketch("Grundriss").also { it.sketch.addRectangle(0.0, 0.0, 40.0, 20.0) }
        val ext1 = doc.addExtrude("Basis", sk1, 10.0)
        val sk2 = doc.addSketch("Aufbau").also { it.sketch.addRectangle(5.0, 5.0, 15.0, 15.0) }
    }

    @Test
    fun referencesIncludesSketchAndUntilFace() {
        val s = Setup()
        val topFace = FaceRef(s.ext1.id, FaceRole.TOP)
        val ext2 = s.doc.addExtrude("BisFlaeche", s.sk2, 1.0, untilFace = topFace)
        assertEquals(setOf(s.sk2.id, s.ext1.id), ext2.references)
    }

    @Test
    fun removingTargetFeatureRemovesDependentExtrude() {
        val s = Setup()
        val topFace = FaceRef(s.ext1.id, FaceRole.TOP)
        val ext2 = s.doc.addExtrude("BisFlaeche", s.sk2, 1.0, untilFace = topFace)
        val deps = s.doc.dependents(s.ext1.id)
        assertTrue(ext2.id in deps, "Extrusion bis Fläche muss von der Zielfläche abhängen")

        s.doc.remove(s.ext1.id)
        assertTrue(s.doc.features.none { it.id == ext2.id })
    }

    @Test
    fun loadRejectsExtrudeWithUntilFaceNotBeforeIt() {
        val doc = Document()
        val sk1 = SketchFeature(FeatureId(1), "S1", Workplane.XY, dev.androidcad.sketch.Sketch())
        val sk2 = SketchFeature(FeatureId(2), "S2", Workplane.XY, dev.androidcad.sketch.Sketch())
        // Extrude 3 references target face from Feature 4 (which comes after it)
        val ext = ExtrudeFeature(FeatureId(3), "E1", sk1.id, 10.0, untilFace = FaceRef(FeatureId(4), FaceRole.TOP))
        val targetExt = ExtrudeFeature(FeatureId(4), "E2", sk2.id, 10.0)

        val err = assertFailsWith<IllegalArgumentException> {
            doc.load(5, listOf(sk1, sk2, ext, targetExt))
        }
        assertTrue(err.message!!.contains("verweist auf ein Feature, das nicht davor liegt"))
    }

    @Test
    fun addExtrudeRejectsInvalidCombinationsWithUntilFace() {
        val s = Setup()
        val topFace = FaceRef(s.ext1.id, FaceRole.TOP)

        assertFailsWith<IllegalArgumentException> {
            s.doc.addExtrude("Invalid1", s.sk2, 1.0, symmetric = true, untilFace = topFace)
        }
        assertFailsWith<IllegalArgumentException> {
            s.doc.addExtrude("Invalid2", s.sk2, 1.0, throughAll = true, untilFace = topFace)
        }
        assertFailsWith<IllegalArgumentException> {
            s.doc.addExtrude("Invalid3", s.sk2, 1.0, distance2 = 5.0, untilFace = topFace)
        }
    }

    @Test
    fun addExtrudeRejectsNonPositiveDistance2() {
        val s = Setup()
        assertFailsWith<IllegalArgumentException> {
            s.doc.addExtrude("InvalidZero", s.sk2, 10.0, distance2 = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            s.doc.addExtrude("InvalidNeg", s.sk2, 10.0, distance2 = -5.0)
        }
        assertFailsWith<IllegalArgumentException> {
            s.doc.addExtrude("InvalidNaN", s.sk2, 10.0, distance2 = Double.NaN)
        }
    }
}
