package dev.androidcad.model

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SketchOverlayTest {
    private fun point(a: FloatArray, i: Int) = Triple(a[3 * i], a[3 * i + 1], a[3 * i + 2])

    @Test
    fun rectangleBecomesFourSegmentsOnTheOffsetPlane() {
        val doc = Document()
        val sk = doc.addSketch("R", PlaneSpec(BasePlane.XY, 8.0).toWorkplane())
        sk.sketch.addRectangle(0.0, 0.0, 40.0, 20.0)
        val seg = sk.worldSegments()
        assertEquals(4 * 6, seg.size)
        for (i in 0 until seg.size / 3) assertEquals(8f, point(seg, i).third) // alle auf z = 8
        val xs = (0 until seg.size / 3).map { point(seg, it).first }
        assertEquals(0f, xs.min()); assertEquals(40f, xs.max())
    }

    @Test
    fun sketchCoordinatesFollowThePlaneOrientation() {
        val doc = Document()
        val sk = doc.addSketch("F", PlaneSpec(BasePlane.XZ, 5.0).toWorkplane()) // Normale −Y: Offset 5 → y = −5
        sk.sketch.addLine(1.0, 2.0, 3.0, 4.0)
        val seg = sk.worldSegments()
        assertEquals(Triple(1f, -5f, 2f), point(seg, 0)) // (x, y) → (X, −5, Z)
        assertEquals(Triple(3f, -5f, 4f), point(seg, 1))
        val yz = doc.addSketch("S", Workplane.YZ)
        yz.sketch.addLine(1.0, 2.0, 3.0, 4.0)
        assertEquals(Triple(0f, 1f, 2f), point(yz.worldSegments(), 0)) // x → Y, y → Z
    }

    @Test
    fun circlesAndArcsAreSampledOnTheirRadius() {
        val doc = Document()
        val sk = doc.addSketch("K")
        sk.sketch.addCircle(10.0, 5.0, 3.0)
        val seg = sk.worldSegments(32)
        assertEquals(32 * 6, seg.size)
        for (i in 0 until seg.size / 3) {
            val p = point(seg, i)
            assertTrue(abs(hypot((p.first - 10f).toDouble(), (p.second - 5f).toDouble()) - 3.0) < 1e-4)
        }
        // Viertelbogen gegen den Uhrzeigersinn von (r,0) nach (0,r)
        val arcSketch = doc.addSketch("B")
        arcSketch.sketch.addArc(0.0, 0.0, 4.0, 0.0, 0.0, 4.0)
        val a = arcSketch.worldSegments(64)
        assertEquals(16, a.size / 6) // ein Viertel von 64
        assertEquals(4f, point(a, 0).first); assertEquals(0f, point(a, 0).second)
        val last = point(a, a.size / 3 - 1)
        assertTrue(abs(last.first) < 1e-4 && abs(last.second - 4f) < 1e-4)
    }

    @Test
    fun arcWrapsAroundWhenEndAngleIsSmallerThanStartAngle() {
        val doc = Document()
        val sk = doc.addSketch("B")
        // von (0,r) nach (r,0) gegen den Uhrzeigersinn = Dreiviertelkreis
        sk.sketch.addArc(0.0, 0.0, 0.0, 4.0, 4.0, 0.0)
        assertEquals(48, sk.worldSegments(64).size / 6)
    }

    @Test
    fun constructionElementsAndEmptySketchesGiveNothing() {
        val doc = Document()
        val sk = doc.addSketch("K")
        assertEquals(0, sk.worldSegments().size)
        sk.sketch.addLine(0.0, 0.0, 5.0, 0.0, construction = true)
        sk.sketch.addCircle(0.0, 0.0, 2.0, construction = true)
        assertEquals(0, sk.worldSegments().size)
        assertTrue(PI > 3)
    }
}
