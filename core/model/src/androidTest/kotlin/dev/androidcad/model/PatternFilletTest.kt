package dev.androidcad.model

import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Muster, die die Verrundungen und Fasen ihrer Quelle mitnehmen (Beispielplatte 80 × 50 × 8 mm). */
class PatternFilletTest {
    private fun near(expected: Double, actual: Double, tol: Double) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    private class Bosses {
        val m = PlateModel()
        val top = FaceRef(m.extrusion.id, FaceRole.TOP)
        val sketch = m.document.addSketchOnFace("Zapfen", top, PlaneSpec(BasePlane.XY, 8.0).toWorkplane())
        val circle = sketch.sketch.addCircle(20.0, 25.0, 4.0)
        val boss = m.document.addExtrude("Zapfen", sketch, 6.0)
        val rounded = m.document.addFillet(
            "Rundung", listOf(EdgeRef.of(FaceRef(boss.id, FaceRole.TOP), FaceRef(boss.id, FaceRole.SIDE, circle.value))), 1.5,
        )
    }

    @Test
    fun eachCopyOfABossGetsTheRoundedTopEdge() {
        val b = Bosses()
        val single = b.m.build().use { it.body!!.volume() } // Platte + ein verrundeter Zapfen
        val loss = PlateParams().expectedVolume + Math.PI * 16 * 6 - single // was die Rundung wegnimmt
        assertTrue(loss > 0.1, "Rundung nimmt Material weg")
        val p = b.m.document.addPattern("Reihe", b.boss.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0), withFillets = true)
        b.m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(single + 2 * (Math.PI * 16 * 6 - loss), r.body!!.volume(), 0.5)
            for (k in 1..2) assertEquals(1, r.faces(FaceRef(p.id, FaceRole.ROUND, PatternGeometry.copyElement(k, 0))).size, "Rundung an Kopie $k")
        }
        // ohne Option bleiben die Kopien scharfkantig
        b.m.document.update(p.copy(withFillets = false))
        b.m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(single + 2 * Math.PI * 16 * 6, r.body!!.volume(), 0.5)
        }
    }

    @Test
    fun aGridOfCountersunkHolesChamfersEveryHoleAndFollowsTheThickness() {
        val m = PlateModel()
        val top = FaceRef(m.extrusion.id, FaceRole.TOP)
        val hole = m.document.addHole("Bohrung", top, 20.0, 15.0, 8.0, 4.0)
        val sink = m.document.addFillet("Senkung", listOf(EdgeRef.of(top, FaceRef(hole.id, FaceRole.SIDE, 1))), 0.8, EdgeKind.CHAMFER)
        val single = m.build().use { it.body!!.volume() }
        val perHole = PlateParams().expectedVolume - single // Bohrung + Senkung
        val row = m.document.addPattern("Reihe", hole.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0), withFillets = true)
        val grid = m.document.addPattern("Raster", row.id, PatternKind.Linear(Vec3(0.0, 1.0, 0.0), 2, 20.0), withFillets = true)
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume - 6 * perHole, r.body!!.volume(), 0.5)
            assertEquals(1, r.faces(FaceRef(sink.id, FaceRole.ROUND, 0)).size)
            for (k in 1..2) assertEquals(1, r.faces(FaceRef(row.id, FaceRole.ROUND, PatternGeometry.copyElement(k, 0))).size)
            for (k in 0..2) assertEquals(1, r.faces(FaceRef(grid.id, FaceRole.ROUND, PatternGeometry.nestedElement(1, k, 0))).size)
        }
        m.apply(PlateParams(thickness = 12.0))
        m.build().use { r -> assertTrue(r.isOk, r.errors.toString()) }
    }

    @Test
    fun copiesOutsideTheBodyAreSkippedWithoutError() {
        val b = Bosses()
        val single = b.m.build().use { it.body!!.volume() }
        // zweite Kopie bei x = 20 + 2 · 200: weit außerhalb, steht frei (Zapfen ohne Platte) – erste Kopie ebenso
        val p = b.m.document.addPattern("Weit", b.boss.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 2, 200.0), withFillets = true)
        b.m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            assertTrue(r.body!!.volume() > single, "frei stehende Kopie kommt hinzu")
            assertEquals(1, r.faces(FaceRef(p.id, FaceRole.ROUND, PatternGeometry.copyElement(1, 0))).size, "auch sie wird verrundet")
        }
    }
}
