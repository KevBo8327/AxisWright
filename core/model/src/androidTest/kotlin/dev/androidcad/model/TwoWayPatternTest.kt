package dev.androidcad.model

import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Raster (Linearmuster mit zwei Richtungen) auf der Beispielplatte 80 × 50 × 8 mm. */
class TwoWayPatternTest {
    private fun near(expected: Double, actual: Double, tol: Double) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    @Test
    fun aGridOfHolesInOneStepWithStableNamesAndCountersinks() {
        val m = PlateModel()
        val top = FaceRef(m.extrusion.id, FaceRole.TOP)
        val hole = m.document.addHole("Bohrung", top, 20.0, 15.0, 8.0, 4.0)
        val sink = m.document.addFillet("Senkung", listOf(EdgeRef.of(top, FaceRef(hole.id, FaceRole.SIDE, 1))), 0.8, EdgeKind.CHAMFER)
        val single = m.build().use { it.body!!.volume() }
        val perHole = PlateParams().expectedVolume - single
        val kind = PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0, Vec3(0.0, 1.0, 0.0), 2, 20.0)
        val p = m.document.addPattern("Raster", hole.id, kind, withFillets = true)
        // Verrundung an der Wand der Kopie in Spalte 2, Zeile 1 – muss Maßänderungen überstehen
        val far = FaceRef(p.id, FaceRole.SIDE, PatternGeometry.nestedElement(1, 2, 1))
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume - 6 * perHole, r.body!!.volume(), 0.5)
            assertEquals(1, r.faces(far).size)
            assertEquals(1, r.faces(FaceRef(p.id, FaceRole.SIDE, PatternGeometry.copyElement(2, 1))).size, "erste Zeile heißt wie eine Reihe")
            assertEquals(1, r.faces(FaceRef(p.id, FaceRole.ROUND, PatternGeometry.nestedElement(1, 2, 0))).size, "Senkung an der Kopie")
            assertEquals(1, r.faces(FaceRef(sink.id, FaceRole.ROUND, 0)).size)
        }
        m.document.update(p.copy(kind = kind.copy(count2 = 3, spacing2 = 12.0)))
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume - 9 * perHole, r.body!!.volume(), 0.5)
            assertEquals(1, r.faces(far).size, "Name bleibt bei mehr Zeilen")
        }
    }

    @Test
    fun aGridOfBossesAddsMaterial() {
        val m = PlateModel()
        val sk = m.document.addSketchOnFace("Zapfen", FaceRef(m.extrusion.id, FaceRole.TOP), PlaneSpec(BasePlane.XY, 8.0).toWorkplane())
        sk.sketch.addCircle(10.0, 10.0, 3.0)
        val boss = m.document.addExtrude("Zapfen", sk, 4.0)
        m.document.addPattern("Raster", boss.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 4, 20.0, Vec3(0.0, 1.0, 0.0), 3, 15.0))
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume + 12 * Math.PI * 9 * 4, r.body!!.volume(), 0.5)
        }
    }
}
