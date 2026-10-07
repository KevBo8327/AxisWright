package dev.androidcad.model

import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.Vec2
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentTest {
    private fun assertVolume(expected: Double, actual: Double, relTol: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= relTol * abs(expected), "Volumen erwartet $expected, erhalten $actual")

    /** Achsenparallele Hülle des tessellierten Körpers. */
    private fun bounds(body: dev.androidcad.brep.BrepShape): Pair<DoubleArray, DoubleArray> {
        val pos = body.tessellate().positions
        val lo = DoubleArray(3) { Double.MAX_VALUE }; val hi = DoubleArray(3) { -Double.MAX_VALUE }
        for (i in pos.indices) { val k = i % 3; lo[k] = minOf(lo[k], pos[i].toDouble()); hi[k] = maxOf(hi[k], pos[i].toDouble()) }
        return lo to hi
    }

    private class Plate(val doc: Document, val sketch: SketchFeature, val widthDim: dev.androidcad.sketch.ConstraintId, val extrude: ExtrudeFeature)

    /** Platte 80 × 50 mit Bohrung (Ø 6) in der Mitte, 8 mm dick – vollständig bemaßt. */
    private fun plate(): Plate {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        val s = sk.sketch
        val r = s.addRectangle(1.0, 1.0, 78.0, 51.0) // schief gezeichnet
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        val w = s.addLength(r.edges[0], 80.0); s.addLength(r.edges[1], 50.0)
        val hole = s.addCircle(38.0, 26.0, 2.0)
        s.addHorizontalDistance(r.corners[0], s.circle(hole).center, 40.0)
        s.addVerticalDistance(r.corners[0], s.circle(hole).center, 25.0)
        s.addDiameter(hole, 6.0)
        val ex = doc.addExtrude("Platte", sk, 8.0)
        return Plate(doc, sk, w, ex)
    }

    @Test
    fun plateWithHoleHasExactVolume() {
        val p = plate()
        p.doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            assertVolume((80.0 * 50.0 - PI * 9.0) * 8.0, assertNotNull(r.body).volume())
            assertEquals(7, r.body!!.faceCount) // Boden, Deckel, 4 Seiten, Bohrung
        }
    }

    @Test
    fun changingADimensionRebuildsTheBody() {
        val p = plate()
        p.doc.build().use { assertVolume((4000.0 - PI * 9) * 8, it.body!!.volume()) }
        p.sketch.sketch.setDimension(p.widthDim, 100.0)
        // Bohrung bleibt 40 mm vom Rand: sie bleibt in der Platte, Volumen wächst um 20 × 50 × 8
        p.doc.build().use { assertVolume((100.0 * 50.0 - PI * 9) * 8, it.body!!.volume()) }
        p.doc.update(p.extrude.copy(distance = 12.0))
        p.doc.build().use { assertVolume((100.0 * 50.0 - PI * 9) * 12, it.body!!.volume()) }
    }

    @Test
    fun cuttingAPocketFromTheTopFace() {
        val p = plate()
        // zweite Skizze auf der Oberseite: Kreis Ø 20, 3 mm tief abtragen
        val top = p.doc.addSketch("Tasche", Workplane.XY.offset(8.0))
        top.sketch.addCircle(60.0, 25.0, 10.0)
        p.doc.addExtrude("Tasche abtragen", top, -3.0, BodyOp.CUT)
        p.doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            assertVolume((80.0 * 50.0 - PI * 9.0) * 8.0 - PI * 100.0 * 3.0, r.body!!.volume())
        }
    }

    @Test
    fun sketchOnFrontPlaneExtrudesTowardsMinusY() {
        val doc = Document()
        val sk = doc.addSketch("Front", Workplane.XZ)
        sk.sketch.addRectangle(0.0, 0.0, 20.0, 30.0) // x = X, y = Z
        doc.addExtrude("Block", sk, 10.0)
        doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            val (lo, hi) = bounds(r.body!!)
            assertEquals(0.0, lo[0], 1e-6); assertEquals(20.0, hi[0], 1e-6)   // X
            assertEquals(-10.0, lo[1], 1e-6); assertEquals(0.0, hi[1], 1e-6)  // Y: Normale −Y
            assertEquals(0.0, lo[2], 1e-6); assertEquals(30.0, hi[2], 1e-6)   // Z
            assertVolume(6000.0, r.body!!.volume())
        }
    }

    @Test
    fun symmetricAndNegativeExtrusion() {
        fun body(distance: Double, symmetric: Boolean): Pair<DoubleArray, DoubleArray> {
            val doc = Document()
            val sk = doc.addSketch("S")
            sk.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
            doc.addExtrude("E", sk, distance, symmetric = symmetric)
            return doc.build().use { bounds(it.body!!) }
        }
        body(8.0, symmetric = true).let { (lo, hi) -> assertEquals(-4.0, lo[2], 1e-6); assertEquals(4.0, hi[2], 1e-6) }
        body(-8.0, symmetric = false).let { (lo, hi) -> assertEquals(-8.0, lo[2], 1e-6); assertEquals(0.0, hi[2], 1e-6) }
    }

    @Test
    fun regionPointsSelectOnlyTheChosenFaces() {
        val doc = Document()
        val sk = doc.addSketch("Zwei Flächen")
        sk.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        sk.sketch.addRectangle(50.0, 0.0, 70.0, 10.0)
        doc.addExtrude("Nur die linke", sk, 5.0, regionPoints = listOf(Vec2(5.0, 5.0)))
        doc.build().use { assertVolume(500.0, it.body!!.volume()) }
        // Alle Flächen: beide
        val doc2 = Document()
        val sk2 = doc2.addSketch("Zwei Flächen")
        sk2.sketch.addRectangle(0.0, 0.0, 10.0, 10.0); sk2.sketch.addRectangle(50.0, 0.0, 70.0, 10.0)
        doc2.addExtrude("Beide", sk2, 5.0)
        doc2.build().use { assertVolume(500.0 + 1000.0, it.body!!.volume()) }
    }

    @Test
    fun nestedRegionsExtrudeAsIslandsAndHoles() {
        val doc = Document()
        val sk = doc.addSketch("Rahmen und Insel")
        sk.sketch.addRectangle(0.0, 0.0, 100.0, 100.0)
        sk.sketch.addRectangle(10.0, 10.0, 90.0, 90.0)
        sk.sketch.addRectangle(30.0, 30.0, 70.0, 70.0)
        doc.addExtrude("Alles", sk, 5.0)
        doc.build().use { assertVolume((10000.0 - 6400.0 + 1600.0) * 5, it.body!!.volume()) }
    }

    @Test
    fun errorsAreReportedPerFeatureWithoutAbortingTheBuild() {
        val doc = Document()
        val empty = doc.addSketch("Leer")
        val e1 = doc.addExtrude("Ohne Fläche", empty, 5.0)
        val sk = doc.addSketch("Block")
        sk.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        val cutFirst = doc.addExtrude("Abtragen ohne Körper", sk, -2.0, BodyOp.CUT)
        val ok = doc.addExtrude("Block", sk, 5.0)
        val zero = doc.addExtrude("Null", sk, 0.0)
        doc.build().use { r ->
            assertEquals(setOf(e1.id, cutFirst.id, zero.id), r.errors.keys)
            assertTrue(ok.id !in r.errors)
            assertVolume(500.0, r.body!!.volume()) // der gültige Block wurde trotzdem gebaut
        }
    }

    @Test
    fun conflictingSketchIsReportedButStillUsed() {
        val doc = Document()
        val sk = doc.addSketch("Widerspruch")
        val r = sk.sketch.addRectangle(0.0, 0.0, 40.0, 20.0)
        sk.sketch.add(Fixed(r.corners[0], 0.0, 0.0))
        sk.sketch.addLength(r.edges[0], 40.0); sk.sketch.addLength(r.edges[2], 50.0)
        doc.addExtrude("Block", sk, 5.0)
        doc.build().use { res ->
            assertTrue(sk.id in res.errors)
            assertNotNull(res.body)
        }
    }

    @Test
    fun removingASketchRemovesItsExtrusions() {
        val p = plate()
        p.doc.remove(p.sketch.id)
        assertTrue(p.doc.features.isEmpty())
        p.doc.build().use { assertEquals(null, it.body); assertTrue(it.isOk) }
    }
}
