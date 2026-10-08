package dev.androidcad.sketch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tangential-Bedingungen an einer gemeinsamen Berührstelle (Bogenende bzw. Punkt auf dem Kreis) dürfen keine scheinbaren Freiheitsgrade
 * lassen: das Ende kann nicht entlang der Tangente gleiten.
 */
class TangencyDofTest {
    private fun dof(s: Sketch) = s.solve(SolveOptions(analyze = true)).also { assertTrue(it.isSolved) }.degreesOfFreedom

    @Test
    fun aRoundedCornerOfADefinedRectangleStaysDefined() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 40.0, 20.0)
        // über zwei feste Ecken bestimmt (die Verrundung entfernt Längenmaße der Linien an ihrer Ecke)
        s.add(Fixed(r.corners[0], 0.0, 0.0)); s.add(Fixed(r.corners[2], 40.0, 20.0))
        assertEquals(0, dof(s))
        assertTrue(SketchOps.fillet(s, r.corners[1], 5.0).ok)
        assertEquals(0, dof(s), "Rundung mit Radiusmaß an bestimmter Ecke")
    }

    @Test
    fun twoArcsMeetingTangentiallyAtAPointCountCorrectly() {
        // zwei Bögen mit gemeinsamem Punkt (0, 0), Mittelpunkte auf der x-Achse: S-Kurve
        val s = Sketch()
        val p = s.addPoint(0.0, 0.0)
        val a = s.addArc(s.addPoint(-5.0, 0.0), s.addPoint(-5.0, 5.0), p)
        val b = s.addArc(s.addPoint(4.0, 0.0), p, s.addPoint(4.0, -4.0))
        val before = dof(s)
        s.addTangent(a, b)
        assertEquals(before - 1, dof(s), "die Berührung nimmt genau einen Freiheitsgrad")
    }

    @Test
    fun aLineEndingOnACircleAndTouchingItLosesTwoFreedoms() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 5.0)
        val end = s.addPoint(0.0, 5.0)
        val line = s.addLine(s.addPoint(-10.0, 5.0), end)
        val free = dof(s)
        s.add(PointOnRound(end, c)); s.add(TangentLineRound(line, c))
        assertEquals(free - 2, dof(s))
    }
}
