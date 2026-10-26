package dev.androidcad.model

import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.split
import dev.androidcad.sketch.Vec2
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertTrue

/** Verzweigte Skizzen (gemeinsame Wand, Teilungslinie, Langloch auf geteilter Kante): Teilflächen einzeln extrudierbar. */
class BranchedSketchTest {
    private fun assertVolume(doc: Document, expected: Double) = doc.build().use { res ->
        assertTrue(res.isOk, res.errors.toString())
        assertTrue(abs(res.body!!.volume() - expected) < 1e-3, "Volumen ${res.body.volume()} statt $expected")
    }

    private fun sharedWall(): Pair<Document, SketchFeature> {
        val doc = Document()
        val sk = doc.addSketch("Zwei")
        sk.sketch.addRectangle(0.0, 0.0, 10.0, 10.0)
        sk.sketch.addRectangle(10.0, 0.0, 30.0, 10.0)
        return doc to sk
    }

    @Test
    fun sharedWallAllRegions() {
        val (doc, sk) = sharedWall()
        doc.addExtrude("Alles", sk, 5.0)
        assertVolume(doc, (200.0 + 100.0) * 5)
    }

    @Test
    fun sharedWallSmallRegionOnly() {
        val (doc, sk) = sharedWall()
        doc.addExtrude("Klein", sk, 5.0, regionPoints = listOf(Vec2(5.0, 5.0)))
        assertVolume(doc, 100.0 * 5)
    }

    @Test
    fun dividedRectangleHalvesIndividually() {
        for ((p, area) in listOf(Vec2(5.0, 10.0) to 300.0, Vec2(30.0, 10.0) to 500.0)) {
            val doc = Document()
            val sk = doc.addSketch("Geteilt")
            val s = sk.sketch
            val rect = s.addRectangle(0.0, 0.0, 40.0, 20.0)
            val bottom = rect.edges.first { s.point(s.line(it).p1).y == 0.0 && s.point(s.line(it).p2).y == 0.0 }
            val top = rect.edges.first { s.point(s.line(it).p1).y == 20.0 && s.point(s.line(it).p2).y == 20.0 }
            assertTrue(SketchOps.split(s, bottom, Vec2(15.0, 0.0)).ok)
            assertTrue(SketchOps.split(s, top, Vec2(15.0, 20.0)).ok)
            assertTrue(s.solve().isSolved)
            s.addLine(15.0, 0.0, 15.0, 20.0)
            doc.addExtrude("Hälfte", sk, 5.0, regionPoints = listOf(p))
            assertVolume(doc, area * 5)
        }
    }

    @Test
    fun slotOnSplitEdge() {
        val slot = 200.0 + PI * 25
        for ((p, area) in listOf(Vec2(20.0, 5.0) to slot, Vec2(20.0, 15.0) to 800.0 - slot)) {
            val doc = Document()
            val sk = doc.addSketch("Langloch")
            val s = sk.sketch
            val xy = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 30.0, 0.0, 40.0, 0.0, 40.0, 20.0, 0.0, 20.0, 0.0, 0.0)
            for (i in 0 until xy.size / 2 - 1) s.addLine(xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3])
            s.addLine(10.0, 10.0, 30.0, 10.0)
            s.addLine(30.0, 0.0, 10.0, 0.0)
            s.addArc(30.0, 5.0, 30.0, 0.0, 30.0, 10.0)
            s.addArc(10.0, 5.0, 10.0, 10.0, 10.0, 0.0)
            doc.addExtrude("Teil", sk, 5.0, regionPoints = listOf(p))
            assertVolume(doc, area * 5)
        }
        val doc = Document()
        val sk = doc.addSketch("Alle")
        val s = sk.sketch
        val xy = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 30.0, 0.0, 40.0, 0.0, 40.0, 20.0, 0.0, 20.0, 0.0, 0.0)
        for (i in 0 until xy.size / 2 - 1) s.addLine(xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3])
        s.addLine(10.0, 10.0, 30.0, 10.0)
        s.addLine(30.0, 0.0, 10.0, 0.0)
        s.addArc(30.0, 5.0, 30.0, 0.0, 30.0, 10.0)
        s.addArc(10.0, 5.0, 10.0, 10.0, 10.0, 0.0)
        doc.addExtrude("Alle", sk, 5.0)
        assertVolume(doc, 800.0 * 5)
    }
}
