package dev.androidcad.model

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolePlacementTest {
    private val up = Vec3(0.0, 0.0, 1.0)
    private fun near(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-9, "erwartet $a, erhalten $b")

    // Deckel z = 10 eines Blocks 40 × 30: vordere Kante y = 0, rechte Kante x = 40, linke x = 0
    private val front = HolePlacement.Line(Vec3(0.0, 0.0, 10.0), Vec3(40.0, 0.0, 10.0))
    private val right = HolePlacement.Line(Vec3(40.0, 0.0, 10.0), Vec3(40.0, 30.0, 10.0))
    private val left = HolePlacement.Line(Vec3(0.0, 0.0, 10.0), Vec3(0.0, 30.0, 10.0))
    private val origin = Vec3(0.0, 0.0, 10.0)

    @Test
    fun canonicalDirectionIgnoresHowTheEdgeIsOriented() {
        val (a1, d1) = HolePlacement.canonical(front.start, front.end)
        val (a2, d2) = HolePlacement.canonical(front.end, front.start)
        assertEquals(a1, a2)
        assertEquals(d1, d2)
        assertEquals(Vec3(1.0, 0.0, 0.0), d1)
    }

    @Test
    fun distanceIsSignedAndIndependentOfEdgeOrientation() {
        val p = Vec3(30.0, 12.0, 10.0)
        // vordere Kante: Normale = (0,0,1) × (1,0,0) = (0,1,0) → +12
        near(12.0, HolePlacement.signedDistance(up, p, front.start, front.end))
        near(12.0, HolePlacement.signedDistance(up, p, front.end, front.start))
        // rechte Kante: (0,0,1) × (0,1,0) = (−1,0,0) → Abstand nach links positiv: 40 − 30 = 10
        near(10.0, HolePlacement.signedDistance(up, p, right.start, right.end))
        near(10.0, HolePlacement.signedDistance(up, p, right.end, right.start))
        // Punkt auf der anderen Seite → negativ
        near(-5.0, HolePlacement.signedDistance(up, Vec3(45.0, 1.0, 10.0), right.start, right.end))
    }

    @Test
    fun solveIsTheInverseOfSignedDistance() {
        for (p in listOf(Vec3(30.0, 12.0, 10.0), Vec3(5.0, 25.0, 10.0), Vec3(-3.0, -4.0, 10.0))) {
            val d1 = HolePlacement.signedDistance(up, p, front.start, front.end)
            val d2 = HolePlacement.signedDistance(up, p, right.start, right.end)
            val back = assertNotNull(HolePlacement.solve(origin, up, front, d1, right, d2))
            near(p.x, back.x); near(p.y, back.y); near(p.z, back.z)
        }
    }

    @Test
    fun theHoleFollowsItsEdgesWhenTheBlockGrows() {
        // 10 mm von der rechten und 12 mm von der vorderen Kante
        val d1 = 12.0
        val d2 = 10.0
        val before = assertNotNull(HolePlacement.solve(origin, up, front, d1, right, d2))
        near(30.0, before.x); near(12.0, before.y)
        // Block wird 70 breit: rechte Kante rückt nach x = 70
        val wideRight = HolePlacement.Line(Vec3(70.0, 0.0, 10.0), Vec3(70.0, 30.0, 10.0))
        val wideFront = HolePlacement.Line(Vec3(0.0, 0.0, 10.0), Vec3(70.0, 0.0, 10.0))
        val after = assertNotNull(HolePlacement.solve(origin, up, wideFront, d1, wideRight, d2))
        near(60.0, after.x); near(12.0, after.y)
    }

    @Test
    fun solveWorksOnSlantedAndVerticalPlanes() {
        // senkrechte Wand x = 40 (Außennormale +x); Kanten: unten (z = 0) und vorn (y = 0)
        val n = Vec3(1.0, 0.0, 0.0)
        val bottom = HolePlacement.Line(Vec3(40.0, 0.0, 0.0), Vec3(40.0, 30.0, 0.0))
        val frontEdge = HolePlacement.Line(Vec3(40.0, 0.0, 0.0), Vec3(40.0, 0.0, 20.0))
        val p = Vec3(40.0, 8.0, 15.0)
        val d1 = HolePlacement.signedDistance(n, p, bottom.start, bottom.end)
        val d2 = HolePlacement.signedDistance(n, p, frontEdge.start, frontEdge.end)
        assertEquals(15.0, abs(d1), 1e-9); assertEquals(8.0, abs(d2), 1e-9)
        val back = assertNotNull(HolePlacement.solve(Vec3(40.0, 0.0, 0.0), n, bottom, d1, frontEdge, d2))
        near(40.0, back.x); near(8.0, back.y); near(15.0, back.z)
    }

    @Test
    fun parallelReferenceEdgesCannotLocateAPoint() {
        assertNull(HolePlacement.solve(origin, up, left, 5.0, right, 5.0))
        assertNull(HolePlacement.solve(origin, up, front, 5.0, front, 5.0))
    }
}
