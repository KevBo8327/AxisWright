package dev.androidcad.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaneSpecTest {
    @Test
    fun specRoundTripsThroughWorkplane() {
        for (base in BasePlane.entries) for (offset in listOf(0.0, 5.0, -12.5, 100.0)) {
            val back = PlaneSpec.from(PlaneSpec(base, offset).toWorkplane())
            assertEquals(PlaneSpec(base, offset), back, "$base $offset")
        }
    }

    @Test
    fun customPlanesAreNotRecognized() {
        val tilted = Workplane(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.6, 0.8))
        assertNull(PlaneSpec.from(tilted))
        val shiftedSideways = Workplane(Vec3(3.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))
        assertNull(PlaneSpec.from(shiftedSideways))
    }

    @Test
    fun labelsAreShortAndReadable() {
        assertEquals("XY", PlaneSpec(BasePlane.XY).label)
        assertEquals("XZ +12,5 mm", PlaneSpec(BasePlane.XZ, 12.5).label)
        assertEquals("YZ −3 mm", PlaneSpec(BasePlane.YZ, -3.0).label)
    }

    @Test
    fun offsetFollowsThePlaneNormal() {
        // XZ hat die Normale −Y: +10 verschiebt nach −Y
        val w = PlaneSpec(BasePlane.XZ, 10.0).toWorkplane()
        assertEquals(-10.0, w.origin.y, 1e-12)
    }

    @Test
    fun documentRenameAndSetPlaneKeepTheSketchObject() {
        val doc = Document()
        val sk = doc.addSketch("A")
        val ex = doc.addExtrude("E", sk, 5.0)
        doc.rename(sk.id, "Grundriss")
        doc.rename(ex.id, "Platte")
        doc.setPlane(sk.id, PlaneSpec(BasePlane.YZ, 4.0).toWorkplane())
        val s = doc.sketch(sk.id)
        assertEquals("Grundriss", s.name)
        assertSame(sk.sketch, s.sketch)
        assertEquals(PlaneSpec(BasePlane.YZ, 4.0), PlaneSpec.from(s.plane))
        assertEquals("Platte", (doc.feature(ex.id) as ExtrudeFeature).name)
        assertTrue(doc.features.map { it.id } == listOf(sk.id, ex.id)) // Reihenfolge bleibt
    }
}
