package dev.androidcad.model

import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spiegeln des ganzen Körpers an der Beispielplatte (80 × 50 × 8 mm, von x = 0 bis 80). */
class MirrorBodyTest {
    private fun near(expected: Double, actual: Double, tol: Double) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    private fun top(m: PlateModel) = FaceRef(m.extrusion.id, FaceRole.TOP)

    /** Seitenfläche der Plattenkante bei x = Breite (rechte Kante). */
    private fun rightSide(m: PlateModel, width: Double = 80.0): FaceRef {
        m.solveSketch()
        val s = m.sketch.sketch
        val right = s.lineIds.first { id -> s.line(id).let { abs(s.point(it.p1).x - width) < 1e-6 && abs(s.point(it.p2).x - width) < 1e-6 } }
        return FaceRef(m.extrusion.id, FaceRole.SIDE, right.value)
    }

    @Test
    fun mirroringAtTheLeftEdgeDoublesThePlateAndNamesTheMirrorImage() {
        val m = PlateModel()
        val right = rightSide(m)
        val mirror = m.document.addMirrorBody("Spiegelung", Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(2 * PlateParams().expectedVolume, r.body!!.volume(), 0.5)
            // die gespiegelte rechte Kante liegt bei x = −80; das Original behält seinen Namen
            assertEquals(1, r.faces(FaceRef(mirror.id, FaceRole.SIDE, MirrorNaming.mirroredElement(right))).size)
            assertEquals(1, r.faces(right).size)
            // keine Naht: Deckfläche ist eine einzige Fläche, auffindbar unter beiden Namen
            assertEquals(1, r.faces(top(m)).size)
            assertEquals(r.faces(top(m)), r.faces(FaceRef(mirror.id, FaceRole.TOP, MirrorNaming.mirroredElement(top(m)))))
        }
    }

    @Test
    fun aFilletOnTheMirrorImageSurvivesParameterChanges() {
        val m = PlateModel()
        val right = rightSide(m)
        val mirror = m.document.addMirrorBody("Spiegelung", Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        val edge = EdgeRef.of(
            FaceRef(mirror.id, FaceRole.TOP, MirrorNaming.mirroredElement(top(m))),
            FaceRef(mirror.id, FaceRole.SIDE, MirrorNaming.mirroredElement(right)),
        )
        val round = m.document.addFillet("Rundung", listOf(edge), 3.0)
        for (p in listOf(PlateParams(), PlateParams(thickness = 12.0))) {
            m.apply(p)
            m.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                val sharp = 2 * p.expectedVolume
                val loss = (9 - Math.PI * 9 / 4) * p.height // Viertelkreis-Zwickel entlang der 50 mm langen Kante
                near(sharp - loss, r.body!!.volume(), 0.5)
                assertEquals(1, r.faces(FaceRef(round.id, FaceRole.ROUND, 0)).size)
            }
        }
    }

    @Test
    fun mirroringAtADatumPlaneFollowsThePlate() {
        val m = PlateModel()
        val plane = m.document.addPlane("Rechts", PlaneDef.Offset(rightSide(m), 5.0), PlaneSpec(BasePlane.YZ, 85.0).toWorkplane())
        m.document.addMirrorBody("Spiegelung", Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), datum = plane.id)
        for (p in listOf(PlateParams(), PlateParams(width = 60.0))) {
            m.apply(p)
            m.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                // 10 mm Lücke zwischen Original und Spiegelbild: zwei getrennte Platten
                near(2 * p.expectedVolume, r.body!!.volume(), 0.5)
                val xs = r.body.tessellate().positions.filterIndexed { i, _ -> i % 3 == 0 }
                near(2 * p.width + 10.0, (xs.max() - xs.min()).toDouble(), 1e-3)
            }
        }
    }

    @Test
    fun withoutABodyTheMirrorReportsAnError() {
        val doc = Document()
        val f = doc.addMirrorBody("Spiegelung", Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        doc.build().use { r -> assertTrue(f.id in r.errors) }
    }
}
