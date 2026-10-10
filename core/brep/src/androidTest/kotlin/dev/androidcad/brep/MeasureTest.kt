package dev.androidcad.brep

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertTrue

class MeasureTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    @Test
    fun distanceAndAreaOnABoxWithAHole() {
        // Quader 40 × 20 × 10, Boden 1, Deckel 2; Bohrung r = 3 um (10, 10) mit Wand 3
        val profile = Profile.rectangle(0.0, 0.0, 40.0, 20.0).withHole(listOf(ProfileSegment.Circle(10.0, 10.0, 3.0, 3)))
        BrepShape.extrude(profile, 10.0, bottomTag = 1, topTag = 2).use { s ->
            val bottom = BrepShape.Part.Face(s.facesWithTag(1).single())
            val top = BrepShape.Part.Face(s.facesWithTag(2).single())
            val wall = BrepShape.Part.Face(s.facesWithTag(3).single())
            near(10.0, s.distance(bottom, top).value)
            near(40.0 * 20 - PI * 9, s.faceArea(top.id))
            near(2 * PI * 3 * 10, s.faceArea(wall.id), 1e-6 * 200)
            // von der Bohrungswand zur rechten Außenseite (x = 40): 40 − 13 = 27
            val right = s.planarFaces().withIndex().first { (_, p) -> p != null && p.normal.first > 0.99 }.index + 1
            val d = s.distance(wall, BrepShape.Part.Face(right))
            near(27.0, d.value)
            near(40.0, d.onB.first)
            // Kante zu Fläche: eine Deckelkante liegt in der Deckelfläche → Abstand 0
            val topEdge = s.edges().first { it.isLine && abs(it.start.third - 10.0) < 1e-9 && abs(it.end.third - 10.0) < 1e-9 }
            near(0.0, s.distance(BrepShape.Part.Edge(topEdge.id), top).value)
        }
    }
}
