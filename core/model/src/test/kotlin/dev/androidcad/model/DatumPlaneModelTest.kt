package dev.androidcad.model

import dev.androidcad.brep.PlanarFace
import dev.androidcad.json.JsonException
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Arbeitsebenen ohne Geometriekern: Mathematik, Validierung, Kaskade, Speichern/Laden. */
class DatumPlaneModelTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-9) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")
    private fun near(expected: Vec3, actual: Vec3, tol: Double = 1e-9) { near(expected.x, actual.x, tol); near(expected.y, actual.y, tol); near(expected.z, actual.z, tol) }

    @Test
    fun offsetMovesTheFacePlaneAlongItsOutwardNormal() {
        val top = PlanarFace(Triple(30.0, 20.0, 8.0), Triple(0.0, 0.0, 1.0))
        val p = DatumPlane.offset(top, 10.0)
        near(Vec3(0.0, 0.0, 18.0), p.origin); near(Vec3(0.0, 0.0, 1.0), p.normal); near(Vec3(1.0, 0.0, 0.0), p.xDir)
        near(-2.0, DatumPlane.offset(top, -10.0).origin.z) // negativ = in den Körper
    }

    @Test
    fun edgePlaneRotatesAboutTheEdgeAndStartsAtItsCanonicalStart() {
        val n = Vec3(0.0, 0.0, 1.0)
        // Hinterkante des Deckels, absichtlich „falsch herum“ angegeben: Ursprung ist trotzdem der kanonische Anfang (0, 50, 8)
        val a = Vec3(80.0, 50.0, 8.0); val b = Vec3(0.0, 50.0, 8.0)
        val flat = DatumPlane.atEdge(n, a, b, 0.0)
        near(Vec3(0.0, 50.0, 8.0), flat.origin); near(Vec3(1.0, 0.0, 0.0), flat.xDir); near(n, flat.normal)
        // +90°: Normale kippt zu Kantenrichtung × Normale = X × Z = −Y
        near(Vec3(0.0, -1.0, 0.0), DatumPlane.atEdge(n, a, b, 90.0).normal, 1e-12)
        near(Vec3(0.0, 1.0, 0.0), DatumPlane.atEdge(n, a, b, -90.0).normal, 1e-12)
        val tilted = DatumPlane.atEdge(n, a, b, 45.0)
        val h = sqrt(0.5)
        near(Vec3(0.0, -h, h), tilted.normal, 1e-12)
        // rechtshändig, x bleibt die Kante
        near(0.0, tilted.xDir.dot(tilted.normal)); near(Vec3(1.0, 0.0, 0.0), tilted.xDir)
        near(1.0, (tilted.xDir cross tilted.yDir).dot(tilted.normal))
        // Punkte der Kante liegen in der Ebene
        near(0.0, (Vec3(40.0, 50.0, 8.0) + tilted.origin * -1.0).dot(tilted.normal), 1e-9)
    }

    @Test
    fun validationRejectsUnusableValues() {
        val face = FaceRef(FeatureId(2), FaceRole.TOP)
        val edge = EdgeRef.of(face, FaceRef(FeatureId(2), FaceRole.SIDE, 3))
        assertNull(DatumPlane.validate(PlaneDef.Offset(face, -5.0)))
        assertNotNull(DatumPlane.validate(PlaneDef.Offset(face, Double.NaN)))
        assertNull(DatumPlane.validate(PlaneDef.AtEdge(face, edge, -120.0)))
        assertNotNull(DatumPlane.validate(PlaneDef.AtEdge(face, edge, 180.0)))
        assertNotNull(DatumPlane.validate(PlaneDef.AtEdge(face, edge, Double.POSITIVE_INFINITY)))
    }

    private class Fixture {
        val doc = Document()
        val base = doc.addSketch("Basis")
        val ext = doc.addExtrude("Block", base, 10.0)
        val top = FaceRef(ext.id, FaceRole.TOP)
        val edge = EdgeRef.of(top, FaceRef(ext.id, FaceRole.SIDE, 3))
        val offset = doc.addPlane("Ebene 1", PlaneDef.Offset(top, 15.0), PlaneSpec(BasePlane.XY, 25.0).toWorkplane())
        val tilted = doc.addPlane("Ebene 2", PlaneDef.AtEdge(top, edge, 30.0), Workplane.XY)
        val onOffset = doc.addSketchOnPlane("Skizze auf Ebene", offset.id).also { it.sketch.addCircle(10.0, 10.0, 3.0) }
    }

    @Test
    fun sketchesOnAPlaneTakeItsPositionAndFollowItsRemoval() {
        val f = Fixture()
        assertEquals(f.offset.id, f.onOffset.onPlane)
        near(25.0, f.onOffset.plane.origin.z)
        assertTrue(f.onOffset.planeIsDerived)
        assertFailsWith<IllegalArgumentException> { f.doc.addSketchOnPlane("x", f.ext.id) } // keine Ebene
        assertFailsWith<IllegalArgumentException> { f.doc.addPlane("x", PlaneDef.Offset(FaceRef(FeatureId(99), FaceRole.TOP), 1.0), Workplane.XY) }
        assertFailsWith<IllegalArgumentException> { f.doc.addPlane("x", PlaneDef.AtEdge(f.top, f.edge, 200.0), Workplane.XY) }
        // Ebene löschen → Skizze darauf verschwindet mit; der Körper bleibt
        f.doc.remove(f.offset.id)
        assertTrue(f.doc.features.none { it.id == f.offset.id || it.id == f.onOffset.id })
        assertTrue(f.doc.features.any { it.id == f.ext.id })
        // Körper löschen → beide Ebenen verschwinden
        f.doc.remove(f.ext.id)
        assertTrue(f.doc.features.none { it is PlaneFeature })
    }

    @Test
    fun planesRoundTripThroughJsonRenameAndUpdate() {
        val f = Fixture()
        f.doc.rename(f.tilted.id, "Schräge")
        f.doc.updatePlane(f.offset.id, PlaneDef.Offset(f.top, 20.0))
        val loaded = DocumentCodec.decode(DocumentCodec.encode(f.doc))
        val planes = loaded.features.filterIsInstance<PlaneFeature>()
        assertEquals(listOf(PlaneDef.Offset(f.top, 20.0), PlaneDef.AtEdge(f.top, f.edge, 30.0)), planes.map { it.def })
        assertEquals("Schräge", planes[1].name)
        near(25.0, planes[0].plane.origin.z) // letzte bekannte Lage wird mitgespeichert
        assertEquals(f.offset.id, loaded.sketch(f.onOffset.id).onPlane)
        assertFailsWith<IllegalArgumentException> { f.doc.updatePlane(f.ext.id, PlaneDef.Offset(f.top, 1.0)) }
    }

    @Test
    fun invalidPlanesAreRejectedOnLoad() {
        val f = Fixture()
        @Suppress("UNCHECKED_CAST")
        fun features(j: Map<String, Any?>) = j["features"] as List<MutableMap<String, Any?>>

        val badType = DocumentCodec.toJson(f.doc).also { features(it).first { m -> m["kind"] == "datum" }["type"] = "spirale" }
        assertFailsWith<JsonException> { DocumentCodec.fromJson(badType) }
        val badAngle = DocumentCodec.toJson(f.doc).also { features(it).first { m -> m["type"] == "edge" }["angle"] = 999.0 }
        assertFailsWith<JsonException> { DocumentCodec.fromJson(badAngle) }
        // Skizze zeigt auf ein Feature, das keine Ebene ist
        val notAPlane = DocumentCodec.toJson(f.doc).also { features(it).first { m -> m["name"] == "Skizze auf Ebene" }["onPlane"] = f.ext.id.value }
        assertFailsWith<JsonException> { DocumentCodec.fromJson(notAPlane) }
    }
}
