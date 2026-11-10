package dev.androidcad.model

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoleGeometryTest {
    private fun assertVolume(expected: Double, actual: Double, relTol: Double = 1e-4) {
        assertTrue(
            abs(expected - actual) <= relTol * abs(expected),
            "Volumen erwartet $expected, erhalten $actual (Abweichung ${abs(expected - actual)})",
        )
    }

    private class BlockSetup {
        val doc = Document()
        val sketch = doc.addSketch("Grundriss").also { it.sketch.addRectangle(0.0, 0.0, 40.0, 40.0) }
        val ext = doc.addExtrude("Block", sketch, 20.0)
        val top = FaceRef(ext.id, FaceRole.TOP)
    }

    @Test
    fun counterboreThroughHoleCreatesExpectedFacesAndVolume() {
        val s = BlockSetup()
        // M6 Durchgangsbohrung mit Senkung: D = 6.6 mm, D_cb = 11.0 mm, T_cb = 6.4 mm
        val h = s.doc.addHole(
            "Senkbohrung", s.top, 20.0, 20.0, 20.0, 6.6,
            kind = HoleKind.COUNTERBORE, counterboreDiameter = 11.0, counterboreDepth = 6.4,
        )
        s.doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            val body = res.body!!
            val r1 = 3.3
            val r2 = 5.5
            val tCb = 6.4
            val blockVolume = 40.0 * 40.0 * 20.0
            val cutVolume = PI * r2 * r2 * tCb + PI * r1 * r1 * (20.0 - tCb)
            assertVolume(blockVolume - cutVolume, body.volume())

            // Flächennamen prüfen: Senkfläche, Senkboden, Bohrungswand
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.COUNTERBORE, 1).tag).size)
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.COUNTERBORE_BOTTOM, 1).tag).size)
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.SIDE, 1).tag).size)
            assertEquals(0, body.facesWithTag(FaceRef(h.id, FaceRole.TOP).tag).size)
            assertEquals(9, body.faceCount)
        }
    }

    @Test
    fun counterboreBlindHoleCreatesExpectedFacesAndVolume() {
        val s = BlockSetup()
        // M6 Sackloch mit Senkung: D = 6.6 mm, D_cb = 11.0 mm, T_cb = 6.4 mm, Bohrtiefe = 15.0 mm
        val h = s.doc.addHole(
            "SacklochSenkung", s.top, 20.0, 20.0, 20.0, 6.6, depth = 15.0,
            kind = HoleKind.COUNTERBORE, counterboreDiameter = 11.0, counterboreDepth = 6.4,
        )
        s.doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            val body = res.body!!
            val r1 = 3.3
            val r2 = 5.5
            val tCb = 6.4
            val blockVolume = 40.0 * 40.0 * 20.0
            val cutVolume = PI * r2 * r2 * tCb + PI * r1 * r1 * (15.0 - tCb)
            assertVolume(blockVolume - cutVolume, body.volume())

            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.COUNTERBORE, 1).tag).size)
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.COUNTERBORE_BOTTOM, 1).tag).size)
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.SIDE, 1).tag).size)
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.TOP).tag).size)
            assertEquals(10, body.faceCount)
        }
    }

    @Test
    fun countersinkThroughHoleCreatesExpectedFacesAndVolume() {
        val s = BlockSetup()
        // M6 Durchgangsbohrung mit 90°-Kegelsenkung: D = 6.6 mm, D_cs = 13.7 mm, Winkel = 90°
        val h = s.doc.addHole(
            "Kegelsenkung", s.top, 20.0, 20.0, 20.0, 6.6,
            kind = HoleKind.COUNTERSINK, countersinkDiameter = 13.7, countersinkAngle = 90.0,
        )
        s.doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            val body = res.body!!
            val r1 = 3.3
            val r2 = 6.85
            val hCs = r2 - r1 // bei 90° Spitzenwinkel: tan(45°) = 1, h = r2 - r1 = 3.55
            val blockVolume = 40.0 * 40.0 * 20.0
            val frustumVolume = (PI * hCs / 3.0) * (r2 * r2 + r2 * r1 + r1 * r1)
            val cylVolume = PI * r1 * r1 * (20.0 - hCs)
            assertVolume(blockVolume - (frustumVolume + cylVolume), body.volume())

            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.COUNTERSINK, 1).tag).size)
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.SIDE, 1).tag).size)
            assertEquals(0, body.facesWithTag(FaceRef(h.id, FaceRole.TOP).tag).size)
            assertEquals(8, body.faceCount)
        }
    }

    @Test
    fun countersinkBlindHoleCreatesExpectedFacesAndVolume() {
        val s = BlockSetup()
        // M6 Sackloch mit 90°-Kegelsenkung: D = 6.6 mm, D_cs = 13.7 mm, Bohrtiefe = 12.0 mm
        val h = s.doc.addHole(
            "SacklochKegel", s.top, 20.0, 20.0, 20.0, 6.6, depth = 12.0,
            kind = HoleKind.COUNTERSINK, countersinkDiameter = 13.7, countersinkAngle = 90.0,
        )
        s.doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            val body = res.body!!
            val r1 = 3.3
            val r2 = 6.85
            val hCs = r2 - r1
            val blockVolume = 40.0 * 40.0 * 20.0
            val frustumVolume = (PI * hCs / 3.0) * (r2 * r2 + r2 * r1 + r1 * r1)
            val cylVolume = PI * r1 * r1 * (12.0 - hCs)
            assertVolume(blockVolume - (frustumVolume + cylVolume), body.volume())

            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.COUNTERSINK, 1).tag).size)
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.SIDE, 1).tag).size)
            assertEquals(1, body.facesWithTag(FaceRef(h.id, FaceRole.TOP).tag).size)
            assertEquals(9, body.faceCount)
        }
    }

    @Test
    fun edgesOfCounterboreAndCountersinkCanBeFilleted() {
        val s = BlockSetup()
        val cb = s.doc.addHole(
            "Senkung", s.top, 12.0, 20.0, 20.0, 6.6,
            kind = HoleKind.COUNTERBORE, counterboreDiameter = 11.0, counterboreDepth = 6.4,
        )
        val cs = s.doc.addHole(
            "Kegel", s.top, 28.0, 20.0, 20.0, 6.6,
            kind = HoleKind.COUNTERSINK, countersinkDiameter = 13.7, countersinkAngle = 90.0,
        )
        // Verrundung der Kante zwischen Plattenoberseite und Senkung
        val cbTopEdge = EdgeRef.of(s.top, FaceRef(cb.id, FaceRole.COUNTERBORE, 1))
        val csTopEdge = EdgeRef.of(s.top, FaceRef(cs.id, FaceRole.COUNTERSINK, 1))
        s.doc.addFillet("Rundung", listOf(cbTopEdge, csTopEdge), 1.0)

        s.doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            val body = res.body!!
            // Rundungsflächen sind entstanden
            assertTrue(body.faceCount > 10)
        }
    }

    @Test
    fun validationRejectsInvalidCounterboreAndCountersinkInputs() {
        val s = BlockSetup()
        // Senkdurchmesser <= Bohrungsdurchmesser
        val h1 = s.doc.addHole(
            "ZuKlein", s.top, 20.0, 20.0, 20.0, 8.0, depth = 10.0,
            kind = HoleKind.COUNTERBORE, counterboreDiameter = 8.0, counterboreDepth = 3.0,
        )
        s.doc.build().use { res ->
            assertFeatureFailedWithReason(res.errors[h1.id], ModelText.HOLE_SINK_DIAMETER_TOO_SMALL)
        }
        s.doc.remove(h1.id)

        // Senktiefe >= Bohrtiefe bei Sackloch
        val h2 = s.doc.addHole(
            "ZuTief", s.top, 20.0, 20.0, 20.0, 6.0, depth = 5.0,
            kind = HoleKind.COUNTERBORE, counterboreDiameter = 10.0, counterboreDepth = 5.0,
        )
        s.doc.build().use { res ->
            assertFeatureFailedWithReason(res.errors[h2.id], ModelText.HOLE_SINK_DEPTH_TOO_LARGE)
        }
        s.doc.remove(h2.id)

        // Kegelsenkung: Kegeltiefe >= Bohrtiefe bei Sackloch
        // D = 6.0, D_cs = 16.0, alpha = 90° -> h_cs = (8 - 3)/tan(45°) = 5.0 mm. depth = 4.0 mm
        val h3 = s.doc.addHole(
            "KegelZuTief", s.top, 20.0, 20.0, 20.0, 6.0, depth = 4.0,
            kind = HoleKind.COUNTERSINK, countersinkDiameter = 16.0, countersinkAngle = 90.0,
        )
        s.doc.build().use { res ->
            assertFeatureFailedWithReason(res.errors[h3.id], ModelText.HOLE_SINK_DEPTH_TOO_LARGE)
        }
        s.doc.remove(h3.id)

        // Kegelwinkel ungültig (<= 0 oder >= 180)
        val h4 = s.doc.addHole(
            "WinkelSchlecht", s.top, 20.0, 20.0, 20.0, 6.0,
            kind = HoleKind.COUNTERSINK, countersinkDiameter = 10.0, countersinkAngle = 180.0,
        )
        s.doc.build().use { res ->
            assertFeatureFailedWithReason(res.errors[h4.id], ModelText.HOLE_SINK_ANGLE_RANGE)
        }
    }
}
