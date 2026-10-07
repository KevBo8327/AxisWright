package dev.androidcad.model

import dev.androidcad.brep.ProfileSegment
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BridgeTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-9) =
        assertTrue("erwartet $expected, erhalten $actual", abs(expected - actual) <= tol)

    @Test
    fun rectangleRegionBecomesFourLineProfile() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 40.0, 20.0)
        val profile = s.regions().regions.single().toProfile()
        assertEquals(4, profile.outer.size)
        assertTrue(profile.outer.all { it is ProfileSegment.Line })
        assertTrue(profile.holes.isEmpty())
        // aufeinanderfolgende Segmente stoßen aneinander
        val segs = profile.outer.filterIsInstance<ProfileSegment.Line>()
        for (i in segs.indices) {
            near(segs[i].x2, segs[(i + 1) % segs.size].x1); near(segs[i].y2, segs[(i + 1) % segs.size].y1)
        }
    }

    @Test
    fun holesAndArcsAreTranslated() {
        val s = Sketch()
        s.addRectangle(0.0, 0.0, 40.0, 20.0)
        s.addCircle(20.0, 10.0, 4.0)
        val profile = s.regions().regions.single().toProfile()
        val hole = profile.holes.single().single() as ProfileSegment.Circle
        near(20.0, hole.cx); near(10.0, hole.cy); near(4.0, hole.r)

        val t = Sketch()
        val a = t.addPoint(0.0, -5.0); val b = t.addPoint(30.0, -5.0); val c = t.addPoint(30.0, 5.0); val d = t.addPoint(0.0, 5.0)
        val e = t.addPoint(30.0, 0.0); val f = t.addPoint(0.0, 0.0)
        t.addLine(a, b); t.addLine(c, d); t.addArc(e, b, c); t.addArc(f, d, a)
        val slot = t.regions().regions.single().toProfile()
        assertEquals(2, slot.outer.filterIsInstance<ProfileSegment.Arc>().size)
        assertEquals(2, slot.outer.filterIsInstance<ProfileSegment.Line>().size)
    }

    @Test
    fun standardPlanesAreRightHanded() {
        for (p in listOf(Workplane.XY, Workplane.XZ, Workplane.YZ)) {
            near(0.0, p.xDir.dot(p.yDir)); near(0.0, p.xDir.dot(p.normal)); near(0.0, p.yDir.dot(p.normal))
            val cross = p.xDir cross p.yDir
            near(1.0, cross.dot(p.normal)) // x × y = Normale
        }
        // XZ: lokales y zeigt nach +Z, Normale nach −Y
        near(1.0, Workplane.XZ.yDir.z); near(-1.0, Workplane.XZ.normal.y)
        // YZ: lokales x = +Y, lokales y = +Z, Normale +X
        near(1.0, Workplane.YZ.xDir.y); near(1.0, Workplane.YZ.yDir.z); near(1.0, Workplane.YZ.normal.x)
    }

    @Test
    fun planeMapsLocalToWorldAndOffsets() {
        val top = Workplane.XY.offset(8.0)
        val p = top.toWorld(3.0, 4.0)
        near(3.0, p.x); near(4.0, p.y); near(8.0, p.z)
        val m = Workplane.YZ.matrix3x4()
        // lokal (1, 0, 0) → Welt (0, 1, 0): erste Spalte von [R|t]
        near(0.0, m[0]); near(1.0, m[4]); near(0.0, m[8])
    }

    @Test
    fun xDirIsMadeOrthogonalToNormal() {
        val p = Workplane(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 1.0), Vec3(0.0, 0.0, 1.0)) // xDir nicht senkrecht
        near(0.0, p.xDir.dot(p.normal)); near(1.0, p.xDir.length)
    }
}
