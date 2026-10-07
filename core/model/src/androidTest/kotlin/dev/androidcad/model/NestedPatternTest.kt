package dev.androidcad.model

import dev.androidcad.brep.BrepShape
import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Muster von Mustern an der Beispielplatte (80 × 50 × 8 mm, vier Eckbohrungen Ø6 mit Randabstand 10). */
class NestedPatternTest {
    private fun near(expected: Double, actual: Double, tol: Double) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    private fun center(body: BrepShape, faceId: Int): DoubleArray {
        val m = body.tessellate(0.02, 0.2)
        val c = DoubleArray(3)
        var n = 0
        for (t in 0 until m.triangleCount) {
            if (m.triangleFace[t] != faceId) continue
            for (k in 0..2) { for (d in 0..2) c[d] += m.positions[3 * m.triangles[3 * t + k] + d].toDouble(); n++ }
        }
        return DoubleArray(3) { c[it] / n }
    }

    private val hole4 = Math.PI * 4 * 8 // Bohrung Ø4 durch 8 mm

    @Test
    fun aRowOfARowGivesAGridOfHolesWithDistinctNames() {
        val m = PlateModel()
        val hole = m.document.addHole("Bohrung", FaceRef(m.extrusion.id, FaceRole.TOP), 20.0, 15.0, 8.0, 4.0)
        val row = m.document.addPattern("Reihe", hole.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0))
        val grid = m.document.addPattern("Raster", row.id, PatternKind.Linear(Vec3(0.0, 1.0, 0.0), 2, 20.0))
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume - 6 * hole4, r.body!!.volume(), 0.1)
            // obere Reihe = äußere Kopie 1; innere Kopie 0 = Original der Reihe, 1 und 2 = Kopien der Reihe
            for (k in 0..2) {
                val wall = FaceRef(grid.id, FaceRole.SIDE, PatternGeometry.nestedElement(1, k, 1))
                val c = center(r.body!!, r.faces(wall).single())
                near(20.0 + 20.0 * k, c[0], 0.05); near(35.0, c[1], 0.05)
            }
            // untere Reihe behält ihre Namen aus Bohrung und Reihe
            near(40.0, center(r.body!!, r.faces(FaceRef(row.id, FaceRole.SIDE, PatternGeometry.copyElement(1, 1))).single())[0], 0.05)
            near(20.0, center(r.body!!, r.faces(FaceRef(hole.id, FaceRole.SIDE, 1)).single())[0], 0.05)
        }
        // Abstand der inneren Reihe ändern: das ganze Raster folgt
        m.document.update(row.copy(kind = PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 15.0)))
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            val last = center(r.body!!, r.faces(FaceRef(grid.id, FaceRole.SIDE, PatternGeometry.nestedElement(1, 2, 1))).single())
            near(50.0, last[0], 0.05); near(35.0, last[1], 0.05)
        }
    }

    @Test
    fun mirroringARowOfBossesAddsTheMirroredRow() {
        val m = PlateModel()
        val top = FaceRef(m.extrusion.id, FaceRole.TOP)
        val sk = m.document.addSketchOnFace("Zapfen", top, PlaneSpec(BasePlane.XY, 8.0).toWorkplane())
        val circle = sk.sketch.addCircle(25.0, 18.0, 3.0)
        val boss = m.document.addExtrude("Zapfen", sk, 5.0)
        val row = m.document.addPattern("Reihe", boss.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 15.0))
        val mirror = m.document.addPattern("Spiegel", row.id, PatternKind.Mirror(Vec3(0.0, 25.0, 0.0), Vec3(0.0, 1.0, 0.0)))
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume + 6 * Math.PI * 9 * 5, r.body!!.volume(), 0.2)
            for (k in 0..2) {
                val wall = FaceRef(mirror.id, FaceRole.SIDE, PatternGeometry.nestedElement(1, k, circle.value.toLong()))
                val c = center(r.body!!, r.faces(wall).single())
                near(25.0 + 15.0 * k, c[0], 0.05); near(32.0, c[1], 0.05) // 18 gespiegelt an y = 25
            }
        }
    }

    @Test
    fun aChamferOnANestedCopySurvivesParameterChanges() {
        val m = PlateModel()
        val top = FaceRef(m.extrusion.id, FaceRole.TOP)
        val hole = m.document.addHole("Bohrung", top, 20.0, 15.0, 8.0, 4.0)
        val row = m.document.addPattern("Reihe", hole.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0))
        val grid = m.document.addPattern("Raster", row.id, PatternKind.Linear(Vec3(0.0, 1.0, 0.0), 2, 20.0))
        val wall = FaceRef(grid.id, FaceRole.SIDE, PatternGeometry.nestedElement(1, 2, 1))
        val before = m.build().use { it.body!!.volume() }
        val chamfer = m.document.addFillet("Senkung", listOf(EdgeRef.of(top, wall)), 0.8, EdgeKind.CHAMFER)
        for (p in listOf(PlateParams(), PlateParams(thickness = 12.0))) {
            m.apply(p)
            m.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                assertEquals(1, r.faces(FaceRef(chamfer.id, FaceRole.ROUND, 0)).size)
                if (p == PlateParams()) assertTrue(r.body!!.volume() < before - 1e-3, "Fase nimmt Material weg")
            }
        }
    }
}
