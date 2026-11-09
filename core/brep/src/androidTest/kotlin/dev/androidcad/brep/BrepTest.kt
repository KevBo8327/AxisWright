package dev.androidcad.brep

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrepTest {
    private fun assertVolume(expected: Double, actual: Double, relTol: Double = 1e-6) {
        assertTrue(abs(expected - actual) <= relTol * abs(expected), "Volumen erwartet $expected, erhalten $actual")
    }

    @Test
    fun extrudedRectangleHasExactVolumeAndBoxTopology() {
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 20.0, 30.0), 10.0).use { box ->
            assertVolume(6000.0, box.volume())
            assertEquals(6, box.faceCount)
            assertEquals(12, box.edgeCount)
            assertEquals(12, box.edges().count { it.isLine })
        }
    }

    @Test
    fun profileWithCircularHole() {
        val profile = Profile.rectangle(0.0, 0.0, 20.0, 30.0).withHole(listOf(ProfileSegment.Circle(10.0, 15.0, 4.0)))
        BrepShape.extrude(profile, 10.0).use { s ->
            assertVolume(6000.0 - PI * 16 * 10, s.volume())
            assertEquals(7, s.faceCount) // 6 + Bohrungswand
        }
    }

    @Test
    fun arcsBuildASlot() {
        // Langloch: Rechteck 30 x 10 mit Halbkreisen (r = 5) an beiden Enden
        val slot = Profile(
            listOf(
                ProfileSegment.Line(0.0, -5.0, 30.0, -5.0),
                ProfileSegment.Arc(30.0, -5.0, 30.0, 5.0, 30.0, 0.0, ccw = true),
                ProfileSegment.Line(30.0, 5.0, 0.0, 5.0),
                ProfileSegment.Arc(0.0, 5.0, 0.0, -5.0, 0.0, 0.0, ccw = true),
            ),
        )
        BrepShape.extrude(slot, 4.0).use { s -> assertVolume(4.0 * (300.0 + PI * 25.0), s.volume()) }
    }

    @Test
    fun clockwiseArcsAreSupported() {
        // gleiche Kontur im Uhrzeigersinn durchlaufen
        val slot = Profile(
            listOf(
                ProfileSegment.Line(0.0, 5.0, 30.0, 5.0),
                ProfileSegment.Arc(30.0, 5.0, 30.0, -5.0, 30.0, 0.0, ccw = false),
                ProfileSegment.Line(30.0, -5.0, 0.0, -5.0),
                ProfileSegment.Arc(0.0, -5.0, 0.0, 5.0, 0.0, 0.0, ccw = false),
            ),
        )
        BrepShape.extrude(slot, 4.0).use { s -> assertVolume(4.0 * (300.0 + PI * 25.0), s.volume()) }
    }

    @Test
    fun booleansUnionCutIntersect() {
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 10.0, 10.0), 10.0).use { a ->
            BrepShape.extrude(Profile.rectangle(0.0, 0.0, 10.0, 10.0), 10.0).translate(5.0, 0.0, 0.0).use { b ->
                a.union(b).use { assertVolume(1500.0, it.volume()) }
                a.cut(b).use { assertVolume(500.0, it.volume()) }
                a.intersect(b).use { assertVolume(500.0, it.volume()) }
            }
        }
    }

    @Test
    fun cuttingACylinderEqualsProfileHole() {
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 20.0, 30.0), 10.0).use { box ->
            BrepShape.extrude(Profile.circle(10.0, 15.0, 4.0), 10.0).use { cyl ->
                box.cut(cyl).use { assertVolume(6000.0 - PI * 16 * 10, it.volume()) }
            }
        }
    }

    @Test
    fun filletOfVerticalEdges() {
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 20.0, 30.0), 10.0).use { box ->
            val vertical = box.edges().filter { it.isLine && abs(it.end.third - it.start.third) > 5.0 }.map { it.id }
            assertEquals(4, vertical.size)
            val r = 2.0
            box.fillet(vertical, r).use { rounded ->
                assertVolume(6000.0 - 4 * (1 - PI / 4) * r * r * 10.0, rounded.volume(), 1e-5)
                assertEquals(10, rounded.faceCount) // 6 + 4 Rundungen
            }
        }
    }

    @Test
    fun tessellationCarriesFaceAndEdgeIds() {
        val profile = Profile.rectangle(0.0, 0.0, 20.0, 30.0).withHole(listOf(ProfileSegment.Circle(10.0, 15.0, 4.0)))
        BrepShape.extrude(profile, 10.0).use { s ->
            val mesh = s.tessellate(linearDeflection = 0.01, angularDeflection = 0.2)
            assertTrue(mesh.triangleCount > 12)
            assertEquals(mesh.triangleCount, mesh.triangleFace.size)
            // jede Fläche liefert Dreiecke, alle IDs liegen in 1..faceCount
            assertEquals((1..s.faceCount).toSet(), mesh.triangleFace.toSet())
            assertEquals(s.edgeCount, mesh.edgeIds.size)
            assertTrue(mesh.edgePointCounts.all { it >= 2 })
            assertEquals(mesh.edgePointCounts.sum(), mesh.edgePositions.size / 3)
            // Netzvolumen ≈ exaktes Volumen (Facettierung unterschätzt gekrümmte Flächen minimal)
            assertVolume(s.volume(), mesh.volume(), 2e-3)
        }
    }

    @Test
    fun boxMeshVolumeIsExact() {
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 20.0, 30.0), 10.0).use { s ->
            val mesh = s.tessellate()
            assertEquals(12, mesh.triangleCount)
            assertVolume(6000.0, mesh.volume(), 1e-5)
        }
    }

    @Test
    fun invalidInputsThrowBrepException() {
        // offene Kontur
        val open = Profile(
            listOf(
                ProfileSegment.Line(0.0, 0.0, 10.0, 0.0),
                ProfileSegment.Line(10.0, 0.0, 10.0, 10.0),
                ProfileSegment.Line(10.0, 10.0, 0.0, 10.0),
            ),
        )
        assertFailsWith<BrepException> { BrepShape.extrude(open, 5.0) }
        // nicht zusammenhängende Segmente
        val gap = Profile(listOf(ProfileSegment.Line(0.0, 0.0, 10.0, 0.0), ProfileSegment.Line(20.0, 0.0, 20.0, 10.0)))
        assertFailsWith<BrepException> { BrepShape.extrude(gap, 5.0) }
        // zu großer Radius
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 10.0, 10.0), 2.0).use { s ->
            val ids = s.edges().filter { it.isLine }.map { it.id }
            assertFailsWith<BrepException> { s.fillet(ids, 50.0) }
            assertFailsWith<BrepException> { s.fillet(listOf(999), 1.0) }
        }
    }

    /** Gate M0: typisches Teil (Platte, vier Bohrungen, verrundete Ecken) einmal komplett erzeugen und tessellieren. */
    @Test
    fun typicalPartIsFastEnough() {
        fun build(): Pair<Long, Int> {
            val t0 = System.nanoTime()
            var profile = Profile.rectangle(0.0, 0.0, 80.0, 50.0)
            for ((x, y) in listOf(10.0 to 10.0, 70.0 to 10.0, 10.0 to 40.0, 70.0 to 40.0)) {
                profile = profile.withHole(listOf(ProfileSegment.Circle(x, y, 3.0)))
            }
            BrepShape.extrude(profile, 8.0).use { plate ->
                val vertical = plate.edges().filter { it.isLine && abs(it.end.third - it.start.third) > 5.0 }.map { it.id }
                plate.fillet(vertical, 4.0).use { rounded ->
                    val mesh = rounded.tessellate(linearDeflection = 0.05, angularDeflection = 0.3)
                    return (System.nanoTime() - t0) / 1_000_000 to mesh.triangleCount
                }
            }
        }
        build() // Aufwärmen
        val runs = List(10) { build() }
        val avg = runs.map { it.first }.average()
        android.util.Log.i("BrepPerf", "Platte mit 4 Bohrungen + Verrundung + Tessellierung: Ø %.1f ms (max %d ms), %d Dreiecke".format(avg, runs.maxOf { it.first }, runs.last().second))
        assertTrue(avg < 200, "Zu langsam: Ø $avg ms")
    }

    private fun zRange(s: BrepShape): Pair<Double, Double> {
        val p = s.tessellate().positions
        val zs = (2 until p.size step 3).map { p[it].toDouble() }
        return zs.min() to zs.max()
    }

    @Test
    fun extrusionDirectionsUpDownAndSymmetric() {
        val rect = Profile.rectangle(0.0, 0.0, 10.0, 10.0)
        BrepShape.extrude(rect, 5.0).use { assertVolume(500.0, it.volume()); assertEquals(0.0 to 5.0, zRange(it)) }
        BrepShape.extrude(rect, -5.0).use { assertVolume(500.0, it.volume()); assertEquals(-5.0 to 0.0, zRange(it)) }
        BrepShape.extrude(rect, 5.0, symmetric = true).use { assertVolume(500.0, it.volume()); assertEquals(-2.5 to 2.5, zRange(it)) }
        assertFailsWith<BrepException> { BrepShape.extrude(rect, 0.0) }
    }

    @Test
    fun rigidTransformRotatesAndMoves() {
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 20.0, 30.0), 10.0).use { box ->
            // 90° um Z: (x, y) → (−y, x); danach um (100, 0, 5) verschieben
            val m = doubleArrayOf(0.0, -1.0, 0.0, 100.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 5.0)
            box.transform(m).use { moved ->
                assertVolume(6000.0, moved.volume())
                val pos = moved.tessellate().positions
                fun range(k: Int) = (k until pos.size step 3).map { pos[it].toDouble() }.let { it.min() to it.max() }
                assertEquals(70.0 to 100.0, range(0))   // x: 100 − 30 … 100
                assertEquals(0.0 to 20.0, range(1))
                assertEquals(5.0 to 15.0, range(2))
            }
            // keine echte Drehung (Skalierung) → Fehler
            assertFailsWith<BrepException> { box.transform(doubleArrayOf(2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0)).close() }
        }
    }

    @Test
    fun closedShapeCannotBeUsedAndDoubleCloseIsHarmless() {
        val s = BrepShape.extrude(Profile.rectangle(0.0, 0.0, 1.0, 1.0), 1.0)
        s.close()
        s.close()
        assertFailsWith<IllegalStateException> { s.volume() }
    }

    @Test
    fun extrudeWithSecondSide() {
        // Rechteck 20 x 30, height = 10, height2 = 5 -> Gesamthöhe 15, z von -5 bis 10
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 20.0, 30.0), 10.0, height2 = 5.0).use { box ->
            assertVolume(9000.0, box.volume())
            val b = box.bounds()
            assertEquals(-5.0, b[2], 1e-4)
            assertEquals(10.0, b[5], 1e-4)
        }
    }

    @Test
    fun extrudeWithSecondSideNegativeDistance() {
        // Rechteck 20 x 30, height = -10, height2 = 5 -> Gesamthöhe 15, z von -10 bis 5
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 20.0, 30.0), -10.0, height2 = 5.0).use { box ->
            assertVolume(9000.0, box.volume())
            val b = box.bounds()
            assertEquals(-10.0, b[2], 1e-4)
            assertEquals(5.0, b[5], 1e-4)
        }
    }
}
