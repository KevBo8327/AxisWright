package dev.androidcad.model

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertTrue

/** Abtragen „durch alles“: reicht immer durch den ganzen Körper, auch wenn er später wächst. */
class ThroughAllTest {
    @Test
    fun holeStaysThroughWhenTheBlockGrows() {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        sk.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val box = doc.addExtrude("Block", sk, 20.0)
        val hole = doc.addSketch("Loch")
        hole.sketch.addCircle(20.0, 15.0, 5.0)
        val cut = doc.addExtrude("Abtrag", hole, 1.0, BodyOp.CUT)
        doc.update(cut.copy(throughAll = true))
        fun assertVolume(h: Double) = doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            val expected = 40.0 * 30 * h - PI * 25 * h
            assertTrue(abs(res.body!!.volume() - expected) < 1e-3, "Volumen ${res.body.volume()} statt $expected")
        }
        assertVolume(20.0)
        doc.update(box.copy(distance = 50.0))
        assertVolume(50.0)
    }

    @Test
    fun symmetricGoesThroughBothSides() {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        sk.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        doc.addExtrude("Block", sk, 20.0)
        val mid = doc.addSketch("Mitte", Workplane.XY.offset(10.0))
        mid.sketch.addCircle(20.0, 15.0, 5.0)
        val cut = doc.addExtrude("Abtrag", mid, 1.0, BodyOp.CUT, symmetric = true)
        doc.update(cut.copy(throughAll = true))
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            assertTrue(abs(res.body!!.volume() - (40.0 * 30 * 20 - PI * 25 * 20)) < 1e-3, "Volumen ${res.body.volume()}")
        }
    }
}
