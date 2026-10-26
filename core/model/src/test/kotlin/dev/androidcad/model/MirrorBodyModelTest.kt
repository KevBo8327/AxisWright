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
        // Kopie eines einfachen Musters passt hinein und lässt sich umkehren
        val copy = FaceRef(FeatureId(7), FaceRole.TOP, PatternGeometry.copyElement(3, 1))
        assertEquals(copy, MirrorNaming.original(FaceRef.fromTag(MirrorNaming.mirrorTag(copy.tag, FeatureId(30)))!!))
        assertEquals(0L, MirrorNaming.mirrorTag(0L, FeatureId(30)))
    }

    @Test
    fun existingMirrorNamesStayBitIdentical() {
        // erhoben mit der Kodierung vor der Erweiterung um gestreute Namen – gespeicherte Modelle referenzieren genau diese Werte
        val known = listOf(
            Triple(0x701000000000cL, 30, 0x1e01000e00000cL),
            Triple(0x7030000300001L, 30, 0x1e03000e300001L),
            Triple(0x1020000000000L, 2, 0x2020002000000L),
            Triple(0x7fff040001ffffffL, 0x7FFF, 0x7fff04ffffffffffL),
            Triple(0x301000000ffc7L, 9, 0x901000600ffc7L),
        )
        for ((tag, owner, mirrored) in known) assertEquals(mirrored, MirrorNaming.mirrorTag(tag, FeatureId(owner)), "Name zu 0x${tag.toString(16)}")
    }

    @Test
    fun largeElementsLikeShellInnerFacesGetAStableSeparateName() {
        val shell = FeatureId(5); val mirror = FeatureId(9)
        val inner = FaceRef(shell, FaceRole.SIDE, ShellFeature.innerElement(FaceRef(FeatureId(1), FaceRole.BOTTOM).tag))
        val tag = MirrorNaming.mirrorTag(inner.tag, mirror)
        assertEquals(0x9010000784d14L, tag, "fester Wert: ändert er sich, verlieren gespeicherte Referenzen ihre Fläche")
        val named = FaceRef.fromTag(tag)!!
        assertEquals(mirror, named.feature)
        assertEquals(FaceRole.SIDE, named.role)
        assertEquals(named.element, MirrorNaming.mirroredElement(inner))
        // eigener Bereich: unter 2²⁵ (Feature-Teil 0), den die bisherige Kodierung nie belegt
        assertTrue(named.element < (1L shl MirrorNaming.ELEMENT_BITS))
        assertNull(MirrorNaming.original(named), "gestreute Namen lassen sich nicht umkehren")
        // verschachtelte Musterkopien und Spiegelbilder einer Spiegelung ebenfalls
        assertTrue(MirrorNaming.mirrorTag(FaceRef(FeatureId(7), FaceRole.TOP, PatternGeometry.nestedElement(2, 3, 1)).tag, mirror) != 0L)
        assertTrue(MirrorNaming.mirrorTag(MirrorNaming.mirrorTag(inner.tag, mirror), FeatureId(11)) != 0L)
        val twice = MirrorNaming.mirrorTag(FaceRef(FeatureId(3), FaceRole.SIDE, 4).tag, mirror)
        assertTrue(MirrorNaming.mirrorTag(twice, FeatureId(11)) != 0L)
    }

    @Test
    fun hashedNamesOfManyShellInnerFacesDoNotCollide() {
        // Innenflächen einer Schale zu 40 Extrusionen × 50 Seitenflächen + Deck- und Bodenflächen
        val outer = (1..40).flatMap { f ->
            (1..50).map { FaceRef(FeatureId(f), FaceRole.SIDE, it).tag } + FaceRef(FeatureId(f), FaceRole.TOP).tag + FaceRef(FeatureId(f), FaceRole.BOTTOM).tag
        }
        val inner = outer.map { FaceRef(FeatureId(41), FaceRole.SIDE, ShellFeature.innerElement(it)).tag }
        val mirrored = inner.map { MirrorNaming.mirrorTag(it, FeatureId(42)) }
        assertEquals(mirrored.size, mirrored.toSet().size)
        assertTrue(mirrored.none { it == 0L })
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
