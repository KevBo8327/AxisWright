package dev.androidcad.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spiegeln des ganzen Körpers – Namen, Prüfungen, Speichern und Löschen, ohne Geometriekern. */
class MirrorBodyModelTest {
    @Test
    fun mirroredNamesKeepRoleFeatureAndElementAndCanBeReversed() {
        val original = FaceRef(FeatureId(7), FaceRole.SIDE, 12)
        val tag = MirrorNaming.mirrorTag(original.tag, FeatureId(30))
        val named = FaceRef.fromTag(tag)!!
        assertEquals(FeatureId(30), named.feature)
        assertEquals(FaceRole.SIDE, named.role)
        assertEquals(MirrorNaming.mirroredElement(original), named.element)
        assertEquals(original, MirrorNaming.original(named))
        // Kopie eines einfachen Musters passt hinein, verschachtelte Kopien nicht (unbenannt statt Fehler)
        val copy = FaceRef(FeatureId(7), FaceRole.TOP, PatternGeometry.copyElement(3, 1))
        assertEquals(copy, MirrorNaming.original(FaceRef.fromTag(MirrorNaming.mirrorTag(copy.tag, FeatureId(30)))!!))
        assertEquals(0L, MirrorNaming.mirrorTag(FaceRef(FeatureId(7), FaceRole.TOP, PatternGeometry.nestedElement(2, 3, 1)).tag, FeatureId(30)))
        assertEquals(0L, MirrorNaming.mirrorTag(0L, FeatureId(30)))
    }

    @Test
    fun aDatumPlaneMirrorIsRemovedWithItsPlaneAndRoundTrips() {
        val doc = Document()
        val plate = doc.addExtrude("Platte", doc.addSketch("Platte"), 8.0)
        val plane = doc.addPlane("Ebene", PlaneDef.Offset(FaceRef(plate.id, FaceRole.TOP), 5.0), Workplane.XY.offset(13.0))
        val atPlane = doc.addMirrorBody("An Ebene", Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), datum = plane.id)
        val atAxis = doc.addMirrorBody("An YZ", Vec3(-5.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        val loaded = DocumentCodec.decode(DocumentCodec.encode(doc))
        assertEquals(atPlane, loaded.feature(atPlane.id))
        assertEquals(atAxis, loaded.feature(atAxis.id))
        doc.remove(plane.id)
        assertTrue(doc.features.none { it.id == atPlane.id }, "Spiegelung an der gelöschten Ebene fällt mit weg")
        assertTrue(doc.features.any { it.id == atAxis.id })
    }

    @Test
    fun invalidPlanesAreRejected() {
        val doc = Document()
        val sketch = doc.addSketch("S")
        assertFailsWith<IllegalArgumentException> { doc.addMirrorBody("x", Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { doc.addMirrorBody("x", Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), datum = sketch.id) }
        assertNull(MirrorNaming.validate(MirrorBodyFeature(FeatureId(5), "ok", Vec3(1.0, 2.0, 3.0), Vec3(0.0, 1.0, 0.0))))
        assertNotNull(MirrorNaming.validate(MirrorBodyFeature(FeatureId(5), "nan", Vec3(Double.NaN, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))))
    }
}
