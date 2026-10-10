package dev.androidcad.app

import dev.androidcad.model.Vec3
import dev.androidcad.model.Workplane
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridLinesTest {
    @Test
    fun stepsAreRoundValues() {
        assertEquals(1.0, niceStep(0.7))
        assertEquals(2.0, niceStep(1.5))
        assertEquals(5.0, niceStep(4.0))
        assertEquals(10.0, niceStep(10.0))
        assertEquals(0.1, niceStep(0.0), "Untergrenze")
    }

    @Test
    fun gridCoversTheBodyLiesInThePlaneAndSnaps() {
        // Körper 100 mm um (13, 7, 50): Abstand 5 mm, Mitte rastet auf 25 mm ein, Gitter auf z = 0
        val g = gridLines(Workplane.XY, Vec3(13.0, 7.0, 50.0), 100.0)
        assertEquals(5.0, g.step)
        val all = g.minor + g.major
        assertTrue((2 until all.size step 3).all { abs(all[it]) < 1e-6 }, "alles in der Ebene z = 0")
        val xs = (0 until all.size step 3).map { all[it] }
        assertTrue(xs.min() <= 13 - 75 && xs.max() >= 13 + 75, "reicht 75 mm über die Mitte hinaus: ${xs.min()} … ${xs.max()}")
        // eingerastet: alle Koordinaten liegen auf dem 5-mm-Raster (Mitte bei x = 25, y = 0), nicht beim krummen Körpermittelpunkt
        val ys = (1 until all.size step 3).map { all[it] }
        assertTrue((xs + ys).all { abs(it / 5f - Math.round(it / 5f)) < 1e-4f })
        assertTrue(g.major.size * 4 < g.minor.size * 2, "nur jede fünfte Linie ist kräftig")
    }
}
