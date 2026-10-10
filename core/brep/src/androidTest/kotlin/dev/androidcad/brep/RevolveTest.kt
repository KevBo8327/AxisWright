package dev.androidcad.brep

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RevolveTest {
    private fun assertVolume(expected: Double, actual: Double, relTol: Double = 1e-6) {
        assertTrue(abs(expected - actual) <= relTol * abs(expected), "Volumen erwartet $expected, erhalten $actual")
    }

    /** Kleinstes und größtes z der Netzpunkte. */
    private fun zRange(s: BrepShape): Pair<Double, Double> {
        val p = s.tessellate().positions
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 2 until p.size step 3) { lo = minOf(lo, p[i].toDouble()); hi = maxOf(hi, p[i].toDouble()) }
        return lo to hi
    }

    private fun tagged(x: Double, y: Double, w: Double, h: Double, first: Long) = Profile(
        listOf(
            ProfileSegment.Line(x, y, x + w, y, first),
            ProfileSegment.Line(x + w, y, x + w, y + h, first + 1),
            ProfileSegment.Line(x + w, y + h, x, y + h, first + 2),
            ProfileSegment.Line(x, y + h, x, y, first + 3),
        ),
    )

    @Test
    fun rectangleOnTheAxisBecomesACylinder() {
        // Rechteck 10 × 20 mit der linken Kante auf der Y-Achse, voll gedreht: Zylinder r = 10, h = 20
        BrepShape.revolve(Profile.rectangle(0.0, 0.0, 10.0, 20.0), 0.0, 0.0, 0.0, 1.0, 360.0).use { s ->
            assertVolume(PI * 100 * 20, s.volume())
            assertEquals(3, s.faceCount) // Mantel, Boden, Deckel
        }
    }

    @Test
    fun rectangleAwayFromTheAxisBecomesATube() {
        BrepShape.revolve(tagged(5.0, 0.0, 5.0, 20.0, 11), 0.0, 0.0, 0.0, 1.0, 360.0).use { s ->
            assertVolume(PI * (100 - 25) * 20, s.volume())
            // jedes Profilsegment erzeugt eine benannte Fläche (Boden, Außenmantel, Deckel, Innenmantel)
            for (t in 11L..14L) assertEquals(1, s.facesWithTag(t).size, "Fläche $t, Namen: ${s.faceTags().toList()}")
        }
    }

    @Test
    fun circleBecomesATorus() {
        val profile = Profile(listOf(ProfileSegment.Circle(20.0, 0.0, 5.0)))
        BrepShape.revolve(profile, 0.0, 0.0, 0.0, 1.0, 360.0).use { s -> assertVolume(2 * PI * PI * 20 * 25, s.volume(), 1e-5) }
    }

    @Test
    fun partialRevolveHasNamedEndFaces() {
        BrepShape.revolve(tagged(5.0, 0.0, 5.0, 20.0, 11), 0.0, 0.0, 0.0, 1.0, 90.0, startTag = 101, endTag = 102).use { s ->
            assertVolume(PI * (100 - 25) * 20 / 4, s.volume())
            assertEquals(1, s.facesWithTag(101).size)
            assertEquals(1, s.facesWithTag(102).size)
            assertEquals(6, s.faceCount)
            // Anfangsfläche liegt in der Profilebene (z = 0)
            val start = s.planarFaces()[s.facesWithTag(101).single() - 1]!!
            assertTrue(abs(start.normal.third) > 0.999 && abs(start.origin.third) < 1e-9)
        }
    }

    @Test
    fun directionAndSymmetry() {
        val profile = Profile.rectangle(5.0, 0.0, 5.0, 20.0)
        // +90° um +Y dreht +X nach −Z
        BrepShape.revolve(profile, 0.0, 0.0, 0.0, 1.0, 90.0).use { s ->
            val (lo, hi) = zRange(s)
            assertTrue(lo < -9.9 && hi < 1e-6, "z von $lo bis $hi")
        }
        BrepShape.revolve(profile, 0.0, 0.0, 0.0, 1.0, -90.0).use { s ->
            val (lo, hi) = zRange(s)
            assertTrue(lo > -1e-6 && hi > 9.9, "z von $lo bis $hi")
            assertVolume(PI * 75 * 20 / 4, s.volume())
        }
        BrepShape.revolve(profile, 0.0, 0.0, 0.0, 1.0, 90.0, symmetric = true).use { s ->
            val (lo, hi) = zRange(s)
            assertTrue(abs(lo + hi) < 1e-3 && hi > 7.0, "z von $lo bis $hi")
            assertVolume(PI * 75 * 20 / 4, s.volume())
        }
    }

    @Test
    fun axisAnywhereInThePlane() {
        // Achse parallel zu X durch y = −5: Rechteck x 0..10, y 0..5 → Rohr um diese Achse (Innenradius 5, außen 10, Länge 10)
        BrepShape.revolve(Profile.rectangle(0.0, 0.0, 10.0, 5.0), 3.0, -5.0, 1.0, 0.0, 360.0).use { s ->
            assertVolume(PI * (100 - 25) * 10, s.volume())
        }
    }

    @Test
    fun revolvedBodiesCombine() {
        BrepShape.revolve(Profile.rectangle(0.0, 0.0, 10.0, 20.0), 0.0, 0.0, 0.0, 1.0, 360.0).use { a ->
            BrepShape.extrude(Profile.rectangle(-20.0, -20.0, 40.0, 40.0), 5.0).use { plate ->
                // Platte in der XZ-Ebene (dreht die Extrusion so, dass sie quer zum Zylinder liegt)
                plate.transform(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0, 0.0, 0.0)).use { p ->
                    a.union(p).use { u -> assertTrue(u.volume() > a.volume()) }
                }
            }
        }
    }

    @Test
    fun invalidParametersAreRejected() {
        val profile = Profile.rectangle(5.0, 0.0, 5.0, 20.0)
        assertFailsWith<BrepException> { BrepShape.revolve(profile, 0.0, 0.0, 0.0, 1.0, 0.0) }
        assertFailsWith<BrepException> { BrepShape.revolve(profile, 0.0, 0.0, 0.0, 1.0, 400.0) }
        assertFailsWith<BrepException> { BrepShape.revolve(profile, 0.0, 0.0, 0.0, 0.0, 90.0) }
    }
}
