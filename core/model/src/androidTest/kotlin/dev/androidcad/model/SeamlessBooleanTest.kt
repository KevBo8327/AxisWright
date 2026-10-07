package dev.androidcad.model

import dev.androidcad.sketch.Fixed
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Nach Hinzufügen/Abtragen werden Flächen in derselben Ebene eine Fläche – ohne Naht, unter allen Namen auffindbar. */
class SeamlessBooleanTest {
    private fun near(expected: Double, actual: Double, tol: Double) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    private class Blocks(val doc: Document, val a: ExtrudeFeature, val b: ExtrudeFeature, val aFront: FaceRef, val bFront: FaceRef)

    /** Block A 40 × 20 × 10 und daneben bündig Block B 30 × 20 × 10 (x = 40 … 70) als zweite Extrusion. */
    private fun blocks(): Blocks {
        val doc = Document()
        fun block(name: String, x0: Double, width: Double): Pair<ExtrudeFeature, FaceRef> {
            val sk = doc.addSketch(name)
            val s = sk.sketch
            val r = s.addRectangle(x0, 0.0, x0 + width, 20.0)
            s.add(Fixed(r.corners[0], x0, 0.0))
            s.addLength(r.edges[0], width); s.addLength(r.edges[1], 20.0)
            val ex = doc.addExtrude(name, sk, 10.0)
            // edges[0] ist die untere Kante (y = 0): die Vorderseite
            return ex to FaceRef(ex.id, FaceRole.SIDE, r.edges[0].value)
        }
        val (a, aFront) = block("A", 0.0, 40.0)
        val (b, bFront) = block("B", 40.0, 30.0)
        return Blocks(doc, a, b, aFront, bFront)
    }

    @Test
    fun flushBlocksBecomeOneBoxWithoutSeams() {
        val k = blocks()
        k.doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(70.0 * 20 * 10, r.body!!.volume(), 1e-6)
            assertEquals(6, r.body.faceCount, "ein Quader: Deckel, Boden und vier Seiten")
            // Deckfläche und Vorderseite: eine Fläche, unter den Namen beider Extrusionen
            assertEquals(1, r.faces(FaceRef(k.a.id, FaceRole.TOP)).size)
            assertEquals(r.faces(FaceRef(k.a.id, FaceRole.TOP)), r.faces(FaceRef(k.b.id, FaceRole.TOP)))
            assertEquals(r.faces(k.aFront), r.faces(k.bFront))
        }
    }

    @Test
    fun aRoundAlongTheJoinedEdgeRunsTheFullLength() {
        val k = blocks()
        // gewählt an Block B – die Rundung läuft trotzdem über die ganzen 70 mm
        val round = k.doc.addFillet("Rundung", listOf(EdgeRef.of(FaceRef(k.b.id, FaceRole.TOP), k.bFront)), 2.0)
        k.doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(70.0 * 20 * 10 - (4 - PI) * 70, r.body!!.volume(), 1e-3)
            assertEquals(1, r.faces(FaceRef(round.id, FaceRole.ROUND, 0)).size)
        }
    }

    @Test
    fun aSketchOnTheYoungerTopFaceStillFindsItsPlane() {
        val k = blocks()
        val top = k.doc.addSketchOnFace("Auf B", FaceRef(k.b.id, FaceRole.TOP), Workplane.XY.offset(10.0))
        top.sketch.addCircle(55.0, 10.0, 3.0)
        k.doc.addExtrude("Zapfen", top, 5.0)
        k.doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(70.0 * 20 * 10 + PI * 9 * 5, r.body!!.volume(), 1e-3)
        }
    }

    @Test
    fun aCutAcrossTheWholeFaceLeavesNoSplitBottom() {
        val k = blocks()
        // Nut quer über die Oberseite: die Deckfläche zerfällt in zwei Teile, der Boden bleibt eine Fläche
        val sk = k.doc.addSketch("Nut", Workplane.XY.offset(10.0))
        sk.sketch.addRectangle(30.0, -1.0, 34.0, 21.0)
        k.doc.addExtrude("Nut", sk, -4.0, BodyOp.CUT)
        k.doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(70.0 * 20 * 10 - 4.0 * 20 * 4, r.body!!.volume(), 1e-6)
            assertEquals(2, r.faces(FaceRef(k.a.id, FaceRole.TOP)).size, "zwei Teile neben der Nut")
            assertEquals(1, r.faces(FaceRef(k.a.id, FaceRole.BOTTOM)).size)
        }
    }
}
