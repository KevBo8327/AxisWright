package dev.androidcad.app

import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidTextOutlinesTest {
    @Test
    fun simplifyClosedContourRemovesMicroSegmentsAndKeepsClosedLoopWithoutGaps() {
        // Realistische Polylinie mit kurzen Segmenten (< 1e-6 und < minSegLen), kollinearen Punkten und doppeltem Endpunkt
        val pts = ArrayList<Pair<Double, Double>>()
        pts += 0.0 to 0.0
        pts += 0.0 to 5e-7      // < 1e-6: darf keine Lücke hinterlassen
        pts += 0.0 to 5.0       // kollinear auf linker Kante
        pts += 0.0 to 10.0
        pts += 4.0 to 10.0
        for (i in 0..20) {
            val a = PI / 2 - PI * i / 20
            pts += (4.0 + 3.0 * cos(a)) to (7.0 + 3.0 * sin(a))
        }
        pts += 4.0001 to 4.0    // kurzer Rücksprung / winziges Segment am Bogenende
        pts += 4.0 to 4.0
        pts += 2.0 to 4.0       // kollinear auf unterer Kante
        pts += 0.0 to 4.0
        pts += 0.0 to 0.0002    // fast am Startpunkt (Abstand < minSegmentLength)

        val segs = simplifyClosedContour(pts, minSegmentLength = 5e-3, collinearTolerance = 1e-3)
        assertTrue(segs.size >= 3)
        // Lückenlos geschlossen: Ende jedes Segments == Start des nächsten Segments (und letztes Ende == erster Start)
        for (i in segs.indices) {
            val cur = segs[i]
            val next = segs[(i + 1) % segs.size]
            assertEquals(cur.x2, next.x1, 0.0, "Lücke nach Segment $i")
            assertEquals(cur.y2, next.y1, 0.0, "Lücke nach Segment $i")
            assertTrue(hypot(cur.x2 - cur.x1, cur.y2 - cur.y1) > 5e-3, "Segment $i zu kurz")
        }
        // Kollineare Zwischenpunkte auf (0,5) und (2,4) wurden vereinfacht
        assertTrue(segs.none { it.x1 == 0.0 && it.y1 == 5.0 })
        assertTrue(segs.none { it.x1 == 2.0 && it.y1 == 4.0 })
    }

    @Test
    fun simplifyClosedContourRemovesSpikeFoldback() {
        val pts = listOf(
            0.0 to 0.0,
            10.0 to 0.0,
            10.002 to 0.0005, // 180°-Spike am Eckpunkt
            10.0 to 0.001,
            10.0 to 10.0,
            0.0 to 10.0,
        )
        val segs = simplifyClosedContour(pts, minSegmentLength = 5e-3, collinearTolerance = 1e-3)
        assertEquals(4, segs.size, "Rechteck mit Spike muss wieder 4 Kanten haben")
    }
}
