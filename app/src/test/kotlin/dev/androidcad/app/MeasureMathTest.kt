package dev.androidcad.app

import dev.androidcad.model.Vec3
import kotlin.math.abs
import org.junit.Test
import kotlin.test.assertTrue

class MeasureMathTest {
    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-9, "erwartet $expected, erhalten $actual")

    @Test
    fun anglesIgnoreDirectionAndStayBetween0And90() {
        val x = Vec3(1.0, 0.0, 0.0); val z = Vec3(0.0, 0.0, 1.0)
        near(90.0, MeasureMath.angle(x, z))
        near(0.0, MeasureMath.angle(z, Vec3(0.0, 0.0, -3.0))) // Deckel und Boden: parallel
        near(45.0, MeasureMath.angle(x, Vec3(-1.0, 1.0, 0.0)))
        near(0.0, MeasureMath.lineToPlane(x, z)) // Kante liegt in der Ebene
        near(90.0, MeasureMath.lineToPlane(z, z)) // Kante steht senkrecht auf der Ebene
    }
}
