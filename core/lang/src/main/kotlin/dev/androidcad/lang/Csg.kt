package dev.androidcad.lang

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 4x4-Matrix, zeilenweise (row-major), Spaltenvektor-Konvention: `p' = M * p`. */
public class Mat4(public val m: DoubleArray) {
    init { require(m.size == 16) }

    public operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (i in 0..3) for (j in 0..3) {
            var s = 0.0
            for (k in 0..3) s += m[i * 4 + k] * o.m[k * 4 + j]
            r[i * 4 + j] = s
        }
        return Mat4(r)
    }

    public fun apply(x: Double, y: Double, z: Double): Triple<Double, Double, Double> = Triple(
        m[0] * x + m[1] * y + m[2] * z + m[3],
        m[4] * x + m[5] * y + m[6] * z + m[7],
        m[8] * x + m[9] * y + m[10] * z + m[11],
    )

    override fun equals(other: Any?): Boolean = other is Mat4 && m.contentEquals(other.m)
    override fun hashCode(): Int = m.contentHashCode()
    override fun toString(): String = "Mat4(${m.joinToString(",")})"

    public companion object {
        public val IDENTITY: Mat4 get() = Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))

        public fun translation(x: Double, y: Double, z: Double): Mat4 =
            Mat4(doubleArrayOf(1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z, 0.0, 0.0, 0.0, 1.0))

        public fun scaling(x: Double, y: Double, z: Double): Mat4 =
            Mat4(doubleArrayOf(x, 0.0, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 0.0, z, 0.0, 0.0, 0.0, 0.0, 1.0))

        /** Spiegelung an der Ebene durch den Ursprung mit Normale (nx, ny, nz). */
        public fun mirror(nx: Double, ny: Double, nz: Double): Mat4 {
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len == 0.0) return IDENTITY
            val a = nx / len; val b = ny / len; val c = nz / len
            return Mat4(doubleArrayOf(
                1 - 2 * a * a, -2 * a * b, -2 * a * c, 0.0,
                -2 * a * b, 1 - 2 * b * b, -2 * b * c, 0.0,
                -2 * a * c, -2 * b * c, 1 - 2 * c * c, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ))
        }

        /** OpenSCAD-`rotate([x,y,z])`: erst um X, dann Y, dann Z (Grad). */
        public fun eulerDegrees(x: Double, y: Double, z: Double): Mat4 =
            rotZ(z) * rotY(y) * rotX(x)

        public fun rotX(deg: Double): Mat4 = rad(deg).let { Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, cos(it), -sin(it), 0.0, 0.0, sin(it), cos(it), 0.0, 0.0, 0.0, 0.0, 1.0)) }
        public fun rotY(deg: Double): Mat4 = rad(deg).let { Mat4(doubleArrayOf(cos(it), 0.0, sin(it), 0.0, 0.0, 1.0, 0.0, 0.0, -sin(it), 0.0, cos(it), 0.0, 0.0, 0.0, 0.0, 1.0)) }
        public fun rotZ(deg: Double): Mat4 = rad(deg).let { Mat4(doubleArrayOf(cos(it), -sin(it), 0.0, 0.0, sin(it), cos(it), 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)) }

        /** Rotation um beliebige Achse (Rodrigues), Winkel in Grad. */
        public fun axisAngleDegrees(deg: Double, ax: Double, ay: Double, az: Double): Mat4 {
            val len = sqrt(ax * ax + ay * ay + az * az)
            if (len == 0.0) return IDENTITY
            val x = ax / len; val y = ay / len; val z = az / len
            val c = cos(rad(deg)); val s = sin(rad(deg)); val t = 1 - c
            return Mat4(doubleArrayOf(
                t * x * x + c, t * x * y - s * z, t * x * z + s * y, 0.0,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x, 0.0,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ))
        }

        private fun rad(d: Double) = Math.toRadians(d)
    }
}

public enum class BoolOp { UNION, DIFFERENCE, INTERSECTION }

/** Ergebnisbaum der Auswertung. Reine Daten – der Geometriekern (Manifold) arbeitet ihn später ab. */
public sealed interface Node

// 3D-Primitive. [fragments] ist die bereits aufgelöste Segmentanzahl ($fn/$fa/$fs).
public data class Cube(val size: Triple<Double, Double, Double>, val center: Boolean) : Node
public data class Sphere(val radius: Double, val fragments: Int) : Node
public data class Cylinder(
    val height: Double, val r1: Double, val r2: Double, val center: Boolean, val fragments: Int,
) : Node
public data class Polyhedron(val points: List<Triple<Double, Double, Double>>, val faces: List<List<Int>>) : Node

// 2D-Primitive
public data class Circle(val radius: Double, val fragments: Int) : Node
public data class Square(val size: Pair<Double, Double>, val center: Boolean) : Node
public data class Polygon2D(val points: List<Pair<Double, Double>>, val paths: List<List<Int>>?) : Node

// Operationen
public data class Transform(val matrix: Mat4, val children: List<Node>) : Node
public data class Boolean3D(val op: BoolOp, val children: List<Node>) : Node
public data class Hull(val children: List<Node>) : Node
public data class Minkowski(val children: List<Node>) : Node
public data class LinearExtrude(
    val height: Double, val center: Boolean, val twist: Double, val slices: Int, val scaleX: Double, val scaleY: Double,
    val children: List<Node>,
) : Node
public data class RotateExtrude(val angle: Double, val fragments: Int, val children: List<Node>) : Node
public data class Offset(
    val amount: Double, val round: Boolean, val chamfer: Boolean, val fragments: Int, val children: List<Node>,
) : Node
public data class Colored(val rgba: List<Double>, val children: List<Node>) : Node

/** Implizite Vereinigung der Kinder. */
public data class Group(val children: List<Node>) : Node
