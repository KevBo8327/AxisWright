package dev.androidcad.model

import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertTrue

/** Arbeitsebenen an der Beispielplatte (80 × 50 × 8 mm): mit Abstand zum Deckel und gedreht an der Hinterkante. */
class DatumPlaneTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    private fun top(m: PlateModel) = FaceRef(m.extrusion.id, FaceRole.TOP)

    /** Seitenfläche der Plattenkante bei y = Höhe (Hinterkante); der Name kommt aus der Linie der Plattenskizze. */
    private fun backSide(m: PlateModel): FaceRef {
        m.solveSketch() // die Beispielskizze ist absichtlich „verwackelt“ gezeichnet
        val s = m.sketch.sketch
        val back = s.lineIds.first { id -> s.line(id).let { abs(s.point(it.p1).y - 50.0) < 1e-6 && abs(s.point(it.p2).y - 50.0) < 1e-6 } }
        return FaceRef(m.extrusion.id, FaceRole.SIDE, back.value)
    }

    @Test
    fun offsetPlaneFollowsTheTopFaceAndCarriesABossSketch() {
        val m = PlateModel()
        val plane = m.document.addPlane("Ebene", PlaneDef.Offset(top(m), 10.0), PlaneSpec(BasePlane.XY, 18.0).toWorkplane())
        val sk = m.document.addSketchOnPlane("Zapfen", plane.id).also { it.sketch.addCircle(40.0, 25.0, 5.0) }
        // von der Ebene nach unten bis auf den Deckel: Zapfen 10 mm hoch
        m.document.addExtrude("Zapfen", sk, -10.0)
        for (p in listOf(PlateParams(), PlateParams(thickness = 12.0))) {
            m.apply(p)
            m.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                near(p.thickness + 10.0, r.datumPlanes.getValue(plane.id).origin.z)
                near(p.thickness + 10.0, r.sketchPlanes.getValue(sk.id).origin.z)
                near(p.expectedVolume + Math.PI * 25 * 10, r.body!!.volume(), p.expectedVolume * 1e-3)
            }
        }
    }

    @Test
    fun edgePlaneHingesOnTheBackEdgeAndFollowsIt() {
        val m = PlateModel()
        val edge = EdgeRef.of(top(m), backSide(m))
        val plane = m.document.addPlane("Schräge", PlaneDef.AtEdge(top(m), edge, 45.0), Workplane.XY)
        for (p in listOf(PlateParams(), PlateParams(height = 70.0, thickness = 10.0))) {
            m.apply(p)
            m.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                val wp = r.datumPlanes.getValue(plane.id)
                // Drehachse = Hinterkante (y = Höhe, z = Dicke), x entlang der Kante
                near(0.0, wp.origin.x); near(p.height, wp.origin.y); near(p.thickness, wp.origin.z)
                near(1.0, wp.xDir.x)
                val h = sqrt(0.5)
                near(-h, wp.normal.y, 1e-9); near(h, wp.normal.z, 1e-9)
            }
        }
    }

    @Test
    fun aCutFromATiltedPlaneRemovesTheExpectedWedge() {
        // Ebene an der Hinterkante um −45° gedreht: Normale (0, h, h) zeigt nach hinten oben; die y-Achse der Ebene nach hinten unten,
        // −y also schräg nach vorn oben über den Deckel. Ein Rechteck x ∈ [0, 80], y ∈ [−5, 0] wird entlang −Normale (nach vorn unten)
        // abgetragen: Die Platte (Dicke 8) schneidet daraus eine schräge Scheibe, Querschnitt = Parallelogramm mit Breite 5 und
        // Länge 8/h (Weg durch die Dicke unter 45°) → Volumen 80 · 5 · 8/h. Randabstand 20 hält die Bohrungen aus dem Schnitt.
        val params = PlateParams(margin = 20.0)
        val m = PlateModel(params)
        val edge = EdgeRef.of(top(m), backSide(m))
        val plane = m.document.addPlane("Schräge", PlaneDef.AtEdge(top(m), edge, -45.0), Workplane.XY)
        val sk = m.document.addSketchOnPlane("Schnitt", plane.id).also { it.sketch.addRectangle(0.0, -5.0, 80.0, 0.0) }
        m.document.addExtrude("Schnitt", sk, -30.0, BodyOp.CUT)
        m.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            val wp = r.datumPlanes.getValue(plane.id)
            val h = sqrt(0.5)
            near(h, wp.normal.y, 1e-9); near(h, wp.normal.z, 1e-9)
            near(params.expectedVolume - 80.0 * 5.0 * 8.0 / h, r.body!!.volume(), 1.0)
        }
    }

    @Test
    fun aMissingFaceReportsAnErrorButKeepsTheLastPlaneForItsSketches() {
        val m = PlateModel()
        val ghost = FaceRef(m.extrusion.id, FaceRole.SIDE, 99999)
        val plane = m.document.addPlane("Geist", PlaneDef.Offset(ghost, 5.0), PlaneSpec(BasePlane.XY, 20.0).toWorkplane())
        val sk = m.document.addSketchOnPlane("Zapfen", plane.id).also { it.sketch.addCircle(40.0, 25.0, 5.0) }
        m.document.addExtrude("Zapfen", sk, -12.0)
        m.build().use { r ->
            assertTrue(plane.id in r.errors.keys, r.errors.toString())
            near(20.0, r.sketchPlanes.getValue(sk.id).origin.z) // Rückfall auf die gespeicherte Lage
            near(PlateParams().expectedVolume + Math.PI * 25 * 12, r.body!!.volume(), 1.0)
        }
    }
}
