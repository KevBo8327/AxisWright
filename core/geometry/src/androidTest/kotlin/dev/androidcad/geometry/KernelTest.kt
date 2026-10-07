package dev.androidcad.geometry

import dev.androidcad.lang.CadLang
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KernelTest {
    private fun mesh(src: String, overrides: Map<String, String> = emptyMap()): Mesh =
        GeometryKernel.build(CadLang.render(src, overrides).root)

    private fun assertVolume(expected: Double, actual: Double, relTol: Double = 1e-4) {
        assertTrue(abs(expected - actual) <= relTol * abs(expected), "Volumen erwartet $expected, erhalten $actual")
    }

    @Test
    fun cubeVolumeAndBounds() {
        val m = mesh("cube([10, 20, 30]);")
        assertVolume(6000.0, m.volume())
        val (lo, hi) = m.bounds()!!
        assertEquals(listOf(0.0, 0.0, 0.0), lo.toList())
        assertEquals(listOf(10.0, 20.0, 30.0), hi.toList())
    }

    @Test
    fun differenceWithCylinderMatchesPolygonArea() {
        // n-Eck mit Umkreisradius r hat die Fläche n/2 * r² * sin(2π/n)
        val n = 32; val r = 4.0; val h = 10.0
        val expected = 20.0 * 20.0 * h - n / 2.0 * r * r * sin(2 * PI / n) * h
        val m = mesh("difference() { cube([20, 20, 10]); translate([10, 10, -1]) cylinder(r = 4, h = 12, ${'$'}fn = 32); }")
        // Zylinder ragt 1 mm unten und oben heraus: Schnittvolumen nur für h = 10
        assertVolume(expected, m.volume(), 1e-3)
    }

    @Test
    fun unionOfOverlappingCubes() {
        // 1000 + 500 - Überlappung (5 x 10 x 5 = 250)
        assertVolume(1250.0, mesh("cube(10); translate([5, 0, 0]) cube([10, 10, 5]);").volume(), 1e-6)
    }

    @Test
    fun intersectionAndHull() {
        assertVolume(125.0, mesh("intersection() { cube(10); translate([5, 5, 5]) cube(10); }").volume())
        // Hülle zweier Würfel mit Abstand = Quader
        assertVolume(30.0 * 10.0 * 10.0, mesh("hull() { cube(10); translate([20, 0, 0]) cube(10); }").volume(), 1e-6)
    }

    @Test
    fun linearExtrudeOfSquareAndCenter() {
        val m = mesh("linear_extrude(height = 7, center = true) square([4, 5]);")
        assertVolume(140.0, m.volume())
        val (lo, hi) = m.bounds()!!
        assertEquals(-3.5, lo[2], 1e-4); assertEquals(3.5, hi[2], 1e-4)
    }

    @Test
    fun linearExtrudeWithScaleIsFrustum() {
        // Quadratischer Pyramidenstumpf: h/3 * (A1 + sqrt(A1*A2) + A2), A2 = (a*s)²
        val a = 10.0; val s = 0.5; val h = 6.0
        val a1 = a * a; val a2 = (a * s) * (a * s)
        assertVolume(h / 3 * (a1 + kotlin.math.sqrt(a1 * a2) + a2), mesh("linear_extrude(height = 6, scale = 0.5) square(10);").volume(), 1e-4)
    }

    @Test
    fun offsetAndPolygonWithHole() {
        // Quadrat 10x10 mit 4x4-Loch (EvenOdd), Höhe 2
        val src = "linear_extrude(2) polygon(points=[[0,0],[10,0],[10,10],[0,10],[3,3],[7,3],[7,7],[3,7]], paths=[[0,1,2,3],[4,5,6,7]]);"
        assertVolume((100.0 - 16.0) * 2, mesh(src).volume())
        // delta-Offset eines Quadrats bleibt ein Quadrat
        assertVolume(14.0 * 14.0, mesh("linear_extrude(1) offset(delta = 2) square(10);").volume())
    }

    @Test
    fun transformsAndMirrorKeepVolumePositive() {
        val src = "mirror([1, 0, 0]) rotate([0, 0, 30]) translate([1, 2, 3]) scale([2, 1, 1]) cube(5);"
        assertVolume(250.0, mesh(src).volume())
    }

    @Test
    fun polyhedronTetrahedronFollowsOpenScadWinding() {
        // OpenSCAD-Konvention: Flächen im Uhrzeigersinn von außen gesehen.
        val src = "polyhedron(points=[[0,0,0],[6,0,0],[0,6,0],[0,0,6]], faces=[[0,1,2],[0,3,1],[1,3,2],[2,3,0]]);"
        assertVolume(36.0, mesh(src).volume(), 1e-4)
    }

    @Test
    fun rotateExtrudeMakesTorus() {
        // Quadrat 2x2 bei x in [4,6] um z: Pappus V = Fläche * 2π * Schwerpunktradius (hier mit 64 Segmenten ≈ 1 %)
        val m = mesh("${'$'}fn = 64;\nrotate_extrude() translate([4, 0]) square(2);")
        assertVolume(4.0 * 2 * PI * 5.0, m.volume(), 0.01)
    }

    @Test
    fun errorsAreReportedAsExceptions() {
        assertFailsWith<GeometryException> { mesh("square(5);") } // 2D auf oberster Ebene
        assertFailsWith<GeometryException> { mesh("union() { cube(1); square(1); }") }
    }

    @Test
    fun emptyResultIsEmptyMesh() {
        assertTrue(mesh("difference() { cube(1); cube(2); }").isEmpty)
        assertTrue(mesh("").isEmpty)
    }

    @Test
    fun stlExportHasCorrectSize() {
        val m = mesh("cube(1);")
        assertEquals(84 + 50 * m.triangleCount, m.toBinaryStl().size)
        assertEquals(12, m.triangleCount)
    }
}
