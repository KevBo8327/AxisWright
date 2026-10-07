package dev.androidcad.model

import dev.androidcad.brep.BrepShape
import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Linear-, Kreismuster und Spiegelung an der Beispielplatte (80 × 50 × 8 mm, vier Bohrungen Ø6). */
class PatternFeatureTest {
    private val shift = PatternGeometry.INSTANCE_SHIFT

    private fun near(expected: Double, actual: Double, tol: Double = 1e-3) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

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

    private val boss = Math.PI * 9 * 5 // Zapfen Ø6, 5 mm hoch

    /** Platte mit einem Zapfen Ø6 bei (x, 25) auf dem Deckel. */
    private class WithBoss(x: Double) {
        val model = PlateModel()
        val top = FaceRef(model.extrusion.id, FaceRole.TOP)
        val sketch = model.document.addSketchOnFace("Zapfen", top, PlaneSpec(BasePlane.XY, 8.0).toWorkplane())
        val circle = sketch.sketch.addCircle(x, 25.0, 3.0)
        val extrude = model.document.addExtrude("Zapfen", sketch, 5.0)
        val doc get() = model.document
    }

    @Test
    fun linearPatternAddsEvenlySpacedCopiesWithOwnFaceNames() {
        val w = WithBoss(20.0)
        val p = w.doc.addPattern("Reihe", w.extrude.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0))
        w.doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume + 3 * boss, r.body!!.volume(), 0.05)
            for (k in 1..2) {
                val wall = FaceRef(p.id, FaceRole.SIDE, (k shl shift) or w.circle.value)
                val c = center(r.body!!, r.faces(wall).single())
                near(20.0 + 20.0 * k, c[0], 0.05); near(25.0, c[1], 0.05)
                val top = FaceRef(p.id, FaceRole.TOP, k shl shift)
                near(13.0, center(r.body!!, r.faces(top).single())[2], 0.01)
            }
            // das Original behält seine Namen
            near(20.0, center(r.body!!, r.faces(FaceRef(w.extrude.id, FaceRole.SIDE, w.circle.value)).single())[0], 0.05)
        }
    }

    @Test
    fun mirrorCopiesAcrossAPlaneAndFollowsParameterChanges() {
        val w = WithBoss(65.0)
        val p = w.doc.addPattern("Spiegel", w.extrude.id, PatternKind.Mirror(Vec3(40.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)))
        for (params in listOf(PlateParams(), PlateParams(width = 100.0, height = 50.0), PlateParams(thickness = 12.0))) {
            w.model.apply(params)
            w.doc.build().use { r ->
                assertTrue(r.isOk, "$params: ${r.errors}")
                near(params.expectedVolume + 2 * boss, r.body!!.volume(), 0.1)
                val wall = FaceRef(p.id, FaceRole.SIDE, (1 shl shift) or w.circle.value)
                near(15.0, center(r.body!!, r.faces(wall).single())[0], 0.05) // 80 − 65 gespiegelt an x = 40
                near(params.thickness + 5.0, center(r.body!!, r.faces(FaceRef(p.id, FaceRole.TOP, 1 shl shift)).single())[2], 0.01)
            }
        }
    }

    @Test
    fun circularPatternOfAHoleCutsEveryCopy() {
        val model = PlateModel()
        val hole = model.document.addHole("Bohrung", FaceRef(model.extrusion.id, FaceRole.TOP), 55.0, 25.0, 8.0, 4.0)
        val p = model.document.addPattern("Kranz", hole.id, PatternKind.Circular(Vec3(40.0, 25.0, 0.0), Vec3(0.0, 0.0, 1.0), 6))
        model.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume - 6 * Math.PI * 4 * 8, r.body!!.volume(), 0.1)
            for (k in 1..5) {
                val wall = FaceRef(p.id, FaceRole.SIDE, (k shl shift) or 1)
                val c = center(r.body!!, r.faces(wall).single())
                near(15.0, hypot(c[0] - 40.0, c[1] - 25.0), 0.05) // alle auf dem Kreis um (40, 25)
            }
            // gleichmäßig: die Kopie 3 liegt gegenüber dem Original
            val opposite = center(r.body!!, r.faces(FaceRef(p.id, FaceRole.SIDE, (3 shl shift) or 1)).single())
            near(25.0, opposite[0], 0.05); near(25.0, opposite[1], 0.05)
        }
    }

    @Test
    fun aPartialArcPlacesTheLastCopyAtTheEndAngle() {
        val model = PlateModel()
        val hole = model.document.addHole("Bohrung", FaceRef(model.extrusion.id, FaceRole.TOP), 55.0, 25.0, 8.0, 4.0)
        val p = model.document.addPattern("Bogen", hole.id, PatternKind.Circular(Vec3(40.0, 25.0, 0.0), Vec3(0.0, 0.0, 1.0), 3, 180.0))
        model.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            val last = center(r.body!!, r.faces(FaceRef(p.id, FaceRole.SIDE, (2 shl shift) or 1)).single())
            near(25.0, last[0], 0.05); near(25.0, last[1], 0.05) // 180°: gegenüber
            val middle = center(r.body!!, r.faces(FaceRef(p.id, FaceRole.SIDE, (1 shl shift) or 1)).single())
            near(40.0, middle[0], 0.05); near(40.0, middle[1], 0.05) // 90°: oben
        }
    }

    @Test
    fun edgesOfACopyAreNamedStablyAndCanBeFilleted() {
        val w = WithBoss(20.0)
        val p = w.doc.addPattern("Reihe", w.extrude.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0))
        val top = FaceRef(p.id, FaceRole.TOP, 2 shl shift)
        val side = FaceRef(p.id, FaceRole.SIDE, (2 shl shift) or w.circle.value)
        w.doc.build().use { before ->
            assertTrue(before.isOk, before.errors.toString())
            val fillet = w.doc.addFillet("Rundung", listOf(EdgeRef.of(top, side)), 0.5)
            w.doc.build().use { r ->
                assertTrue(r.isOk, r.errors.toString())
                assertTrue(r.body!!.volume() < before.body!!.volume() - 1e-3, "Rundung entfernt Material")
                assertEquals(1, r.faces(FaceRef(fillet.id, FaceRole.ROUND, 0)).size)
            }
        }
        // nach einer Parameteränderung wird dieselbe Kante der Kopie wiedergefunden
        w.model.apply(PlateParams(thickness = 10.0))
        w.doc.build().use { r -> assertTrue(r.isOk, r.errors.toString()) }
    }

    @Test
    fun copiesOutsideTheBodyAreHarmlessAndABrokenSourceFailsTheWholePattern() {
        val model = PlateModel()
        val hole = model.document.addHole("Bohrung", FaceRef(model.extrusion.id, FaceRole.TOP), 10.0, 25.0, 8.0, 4.0)
        model.document.addPattern("Weit weg", hole.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 500.0))
        model.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume - Math.PI * 4 * 8, r.body!!.volume(), 0.1) // nur das Original schneidet
        }
        // Quelle zeigt auf eine nicht vorhandene Fläche: Bohrung und Muster melden Fehler, der Körper bleibt unverändert
        val broken = PlateModel()
        val ghost = broken.document.addHole("Geisterbohrung", FaceRef(broken.extrusion.id, FaceRole.SIDE, 99999), 10.0, 25.0, 8.0, 4.0)
        val p = broken.document.addPattern("Muster", ghost.id, PatternKind.Mirror(Vec3(40.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)))
        broken.build().use { r ->
            assertTrue(ghost.id in r.errors.keys && p.id in r.errors.keys, r.errors.toString())
            near(PlateParams().expectedVolume, r.body!!.volume(), 0.1)
        }
    }

    @Test
    fun patternsSurviveSaveAndLoad() {
        val w = WithBoss(20.0)
        w.doc.addPattern("Reihe", w.extrude.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0))
        w.doc.addPattern("Spiegel", w.extrude.id, PatternKind.Mirror(Vec3(40.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)))
        val expected = w.doc.build().use { it.body!!.volume() }
        val loaded = DocumentCodec.decode(DocumentCodec.encode(w.doc))
        loaded.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(expected, r.body!!.volume(), 1e-6)
        }
    }
}
