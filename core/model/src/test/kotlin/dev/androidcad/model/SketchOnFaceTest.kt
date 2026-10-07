package dev.androidcad.model

import dev.androidcad.brep.PlanarFace
import dev.androidcad.json.Json
import dev.androidcad.json.JsonException
import dev.androidcad.json.asObject
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchOnFaceTest {
    private fun near(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-9, "erwartet $a, erhalten $b")
    private fun assertSamePlane(expected: Workplane, actual: Workplane) {
        for (i in 0 until 12) near(expected.matrix3x4()[i], actual.matrix3x4()[i])
    }

    private fun face(origin: Triple<Double, Double, Double>, normal: Triple<Double, Double, Double>) = PlanarFace(origin, normal)

    @Test
    fun facePlaneRuleReproducesTheBasePlanes() {
        // Deckel eines Körpers (z = 8) = XY um 8 verschoben; der Fußpunkt des Ursprungs ist (0, 0, 8)
        assertSamePlane(PlaneSpec(BasePlane.XY, 8.0).toWorkplane(), FacePlane.workplane(face(Triple(33.0, 21.0, 8.0), Triple(0.0, 0.0, 1.0))))
        // Vorderseite (y = −5, Außennormale −Y) = XZ um 5
        assertSamePlane(PlaneSpec(BasePlane.XZ, 5.0).toWorkplane(), FacePlane.workplane(face(Triple(7.0, -5.0, 3.0), Triple(0.0, -1.0, 0.0))))
        // rechte Seite (x = 12, Normale +X) = YZ um 12
        assertSamePlane(PlaneSpec(BasePlane.YZ, 12.0).toWorkplane(), FacePlane.workplane(face(Triple(12.0, 4.0, 9.0), Triple(1.0, 0.0, 0.0))))
    }

    @Test
    fun facePlaneDependsOnlyOnThePlaneNotOnTheSamplePointOrFacing() {
        // beliebiger Punkt auf derselben Ebene → dieselbe Skizzenebene
        val a = FacePlane.workplane(face(Triple(0.0, 0.0, 8.0), Triple(0.0, 0.0, 1.0)))
        val b = FacePlane.workplane(face(Triple(-40.0, 70.0, 8.0), Triple(0.0, 0.0, 1.0)))
        assertSamePlane(a, b)
        // Unterseite: Normale −Z, x bleibt +X, y zeigt nach −Y (Blick von unten)
        val bottom = FacePlane.workplane(face(Triple(0.0, 0.0, 0.0), Triple(0.0, 0.0, -1.0)))
        assertEquals(Vec3(1.0, 0.0, 0.0), bottom.xDir)
        near(-1.0, bottom.yDir.y)
        // schräge Fläche: x waagerecht, y „bergauf“, rechtshändig
        val slanted = FacePlane.workplane(face(Triple(0.0, 0.0, 0.0), Triple(0.0, -0.6, 0.8)))
        near(0.0, slanted.xDir.z)
        assertTrue(slanted.yDir.z > 0.5, "y zeigt bergauf")
        near(0.0, slanted.normal.dot(slanted.xDir)); near(1.0, slanted.xDir.length)
    }

    private class Setup {
        val doc = Document()
        val sketch = doc.addSketch("Grundriss").also { it.sketch.addRectangle(0.0, 0.0, 40.0, 20.0) }
        val ext = doc.addExtrude("Block", sketch, 10.0)
        val top = FaceRef(ext.id, FaceRole.TOP)
        val topPlane = PlaneSpec(BasePlane.XY, 10.0).toWorkplane()
    }

    @Test
    fun faceSketchKeepsItsFaceThroughSnapshotRenameAndSetPlane() {
        val s = Setup()
        val onTop = s.doc.addSketchOnFace("Aufsatz", s.top, s.topPlane)
        assertEquals(s.top, onTop.onFace)
        assertEquals(s.top, s.doc.snapshot().sketch(onTop.id).onFace)
        s.doc.rename(onTop.id, "Zapfen")
        assertEquals(s.top, s.doc.sketch(onTop.id).onFace)
        s.doc.setPlane(onTop.id, PlaneSpec(BasePlane.XY, 12.0).toWorkplane())
        assertEquals(s.top, s.doc.sketch(onTop.id).onFace)
        assertNull(s.doc.sketch(s.sketch.id).onFace)
        assertFailsWith<IllegalArgumentException> { s.doc.addSketchOnFace("fremd", FaceRef(FeatureId(99), FaceRole.TOP), s.topPlane) }
    }

    @Test
    fun removingTheFeatureOfTheFaceRemovesTheSketchAndWhatBuildsOnIt() {
        val s = Setup()
        val onTop = s.doc.addSketchOnFace("Aufsatz", s.top, s.topPlane).also { it.sketch.addCircle(5.0, 5.0, 2.0) }
        s.doc.addExtrude("Zapfen", onTop, 3.0)
        val other = s.doc.addSketch("andere")
        s.doc.remove(s.ext.id)
        assertEquals(listOf(s.sketch.id, other.id), s.doc.features.map { it.id })
    }

    @Test
    fun fileRoundTripKeepsTheFaceAndRejectsForwardReferences() {
        val s = Setup()
        val onTop = s.doc.addSketchOnFace("Aufsatz", s.top, s.topPlane).also { it.sketch.addCircle(5.0, 5.0, 2.0) }
        val back = DocumentCodec.decode(DocumentCodec.encode(s.doc))
        val loaded = back.features.filterIsInstance<SketchFeature>().single { it.id == onTop.id }
        assertEquals(s.top, loaded.onFace)
        assertSamePlane(s.topPlane, loaded.plane)
        assertNull(back.features.filterIsInstance<SketchFeature>().first().onFace)

        // eine Skizze darf nicht auf einer Fläche eines Features liegen, das erst nach ihr kommt
        val json = LinkedHashMap(Json.parse(DocumentCodec.encode(s.doc)).asObject())
        @Suppress("UNCHECKED_CAST")
        val features = (json["features"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }.toMutableList()
        features[0]["onFace"] = listOf(s.ext.id.value, 3, 0) // erste Skizze auf der Deckfläche der Extrusion, die nach ihr kommt
        json["features"] = features
        assertFailsWith<JsonException> { DocumentCodec.decode(Json.write(json)) }
        // kaputte Referenz
        features[0]["onFace"] = listOf(1, 2)
        assertFailsWith<JsonException> { DocumentCodec.decode(Json.write(json)) }
        assertNotNull(DocumentCodec.decode(DocumentCodec.encode(s.doc)))
    }
}
