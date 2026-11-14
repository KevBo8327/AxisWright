package dev.androidcad.geometry

import dev.androidcad.lang.CadLang
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KernelBoundsTest {
    @Test
    fun negativeAndExcessiveCountsFailWithExceptionInsteadOfOverflow() {
        assertFailsWith<IllegalStateException> {
            NativeKernel.nativeBuild(doubleArrayOf(Op.UNION, -1.0))
        }
        assertFailsWith<IllegalStateException> {
            NativeKernel.nativeBuild(doubleArrayOf(Op.POLYHEDRON, -5.0))
        }
        assertFailsWith<IllegalStateException> {
            NativeKernel.nativeBuild(doubleArrayOf(Op.POLYHEDRON, 1.0, 0.0, 0.0, 0.0, 1.0, -3.0))
        }
        assertFailsWith<IllegalStateException> {
            NativeKernel.nativeBuild(doubleArrayOf(Op.POLYGON, -2.0))
        }
        assertFailsWith<IllegalStateException> {
            NativeKernel.nativeBuild(doubleArrayOf(Op.POLYGON, 1.0, -4.0))
        }
        assertFailsWith<IllegalStateException> {
            NativeKernel.nativeBuild(doubleArrayOf(Op.SPHERE, 5.0, Double.NaN))
        }
        assertFailsWith<IllegalStateException> {
            NativeKernel.nativeBuild(doubleArrayOf(Op.SPHERE, 5.0, -8.0))
        }
        assertFailsWith<GeometryException> {
            GeometryKernel.build(CadLang.render("sphere(r = 5, ${'$'}fn = 1000000);").root)
        }
    }

    @Test
    fun emptyMeshAllocatesZeroLengthArraysSafely() {
        val m = GeometryKernel.build(CadLang.render("").root)
        assertEquals(0, m.triangleCount)
        assertEquals(0, m.positions.size)
    }
}
