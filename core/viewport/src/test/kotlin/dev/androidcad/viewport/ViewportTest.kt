package dev.androidcad.viewport

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViewportTest {
    /** Würfel [0, 10]³: 8 Ecken, 12 Dreiecke, 12 Kanten als Zwei-Punkt-Polylinien. */
    private class Cube {
        val corners = (0..7).map { floatArrayOf(if (it and 1 != 0) 10f else 0f, if (it and 2 != 0) 10f else 0f, if (it and 4 != 0) 10f else 0f) }
        val positions = FloatArray(24).also { p -> corners.forEachIndexed { i, c -> c.copyInto(p, 3 * i) } }
        val triangles = intArrayOf(
            0, 2, 1, 1, 2, 3, // z = 0
            4, 5, 6, 5, 7, 6, // z = 10
            0, 1, 4, 1, 5, 4, // y = 0
            2, 6, 3, 3, 6, 7, // y = 10
            0, 4, 2, 2, 4, 6, // x = 0
            1, 3, 5, 3, 7, 5, // x = 10
        )
        val edgePairs = listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
        val polylines = FloatArray(edgePairs.size * 6).also { out ->
            edgePairs.forEachIndexed { i, (a, b) -> corners[a].copyInto(out, 6 * i); corners[b].copyInto(out, 6 * i + 3) }
        }
        val counts = IntArray(edgePairs.size) { 2 }
        fun mid(edge: Int) = FloatArray(3) { (corners[edgePairs[edge].first][it] + corners[edgePairs[edge].second][it]) / 2 }
    }

    private val cube = Cube()
    private val camera = OrthoCamera(-0.6f, 0.5f, 1f, SceneFrame.of(cube.positions), 800, 600)

    private fun pickAt(edge: Int, dx: Float = 0f, dy: Float = 0f, radius: Float = 28f): Int {
        val m = cube.mid(edge)
        val s = camera.project(m[0], m[1], m[2])
        return EdgePicker.pick(camera, s.x + dx, s.y + dy, cube.polylines, cube.counts, cube.positions, cube.triangles, radius)
    }

    @Test
    fun sceneFrameCentersAndFitsTheModel() {
        val f = SceneFrame.of(cube.positions)
        assertEquals(listOf(5f, 5f, 5f), f.center.toList())
        assertTrue(abs(f.radius - 8.660254f) < 1e-4f)
        val empty = SceneFrame.of(FloatArray(0))
        assertEquals(1f, empty.radius)
    }

    @Test
    fun modelCenterProjectsToTheScreenCenter() {
        val s = camera.project(5f, 5f, 5f)
        assertTrue(abs(s.x - 400f) < 1e-3f && abs(s.y - 300f) < 1e-3f, "${s.x}, ${s.y}")
        assertTrue(abs(s.depth) < 1e-4f)
    }

    @Test
    fun screenAxesFollowYawAndPitch() {
        // Draufsicht (pitch = π/2), yaw 0: +x nach rechts, +y nach oben auf dem Bildschirm
        val top = OrthoCamera(0f, (Math.PI / 2).toFloat(), 1f, SceneFrame.of(cube.positions), 800, 800)
        val o = top.project(5f, 5f, 5f); val px = top.project(10f, 5f, 5f); val py = top.project(5f, 10f, 5f)
        assertTrue(px.x > o.x && abs(px.y - o.y) < 1e-3f)
        assertTrue(py.y < o.y && abs(py.x - o.x) < 1e-3f) // Bildschirm-y zeigt nach unten
        // Betrachter sitzt über dem Modell
        assertTrue(top.towardViewer[2] > 0.99f)
    }

    @Test
    fun towardViewerPointsToTheCameraAndIsAUnitVector() {
        assertTrue(abs(EdgePicker.length(camera.towardViewer) - 1f) < 1e-5f)
        val o = camera.project(5f, 5f, 5f)
        val t = camera.towardViewer
        val closer = camera.project(5f + t[0], 5f + t[1], 5f + t[2])
        assertTrue(closer.depth < o.depth, "Punkt in Richtung Betrachter muss näher sein")
        assertTrue(abs(closer.x - o.x) < 1e-3f && abs(closer.y - o.y) < 1e-3f, "…aber an derselben Bildschirmstelle")
    }

    @Test
    fun picksTheTappedVisibleEdgeAndIgnoresFarTaps() {
        // Obere Vorderkante (y = 0, z = 10): Edge 2 = (4,5); sichtbar von oben vorn
        assertEquals(2, pickAt(2, 3f, -2f))
        assertEquals(-1, EdgePicker.pick(camera, 795f, 595f, cube.polylines, cube.counts, cube.positions, cube.triangles)) // Bildecke, weit weg
        // enger Radius trifft nur direkt
        assertEquals(2, pickAt(2, 0f, 0f, radius = 4f))
        assertEquals(-1, EdgePicker.pick(camera, 5f, 5f, cube.polylines, cube.counts, cube.positions, cube.triangles, 4f)) // leere Ecke des Bildes
    }

    @Test
    fun hiddenEdgesAreNotPickable() {
        // Untere Rückkante (y = 10, z = 0): Edge 1 = (2,3) liegt von oben vorn gesehen hinter dem Körper
        assertNotEquals(1, pickAt(1))
        // Sichtbarkeit selbst: Punkt auf der verdeckten Kante ist verdeckt, auf der sichtbaren nicht
        val eps = 0.15f
        assertTrue(EdgePicker.isOccluded(cube.mid(1), camera.towardViewer, cube.positions, cube.triangles, eps))
        assertTrue(!EdgePicker.isOccluded(cube.mid(2), camera.towardViewer, cube.positions, cube.triangles, eps))
    }

    @Test
    fun picksTheClosestOfSeveralEdges() {
        // Tippen genau auf eine Kante, die einen Nachbarn in der Nähe hat: nächste gewinnt
        for (edge in listOf(2, 3, 6, 7, 0, 4)) {
            val picked = pickAt(edge, 0f, 0f)
            // die Mitte einer sichtbaren Kante muss diese Kante liefern (andere sichtbare Kanten sind weiter weg)
            if (!EdgePicker.isOccluded(cube.mid(edge), camera.towardViewer, cube.positions, cube.triangles, 0.15f)) assertEquals(edge, picked, "Kante $edge")
        }
    }

    @Test
    fun zoomScalesTheProjection() {
        val a = camera.project(10f, 5f, 5f)
        val zoomed = OrthoCamera(-0.6f, 0.5f, 2f, SceneFrame.of(cube.positions), 800, 600).project(10f, 5f, 5f)
        val c = camera.project(5f, 5f, 5f)
        assertTrue(abs((zoomed.x - c.x) - 2 * (a.x - c.x)) < 1e-2f)
    }

    @Test
    fun portraitWindowsFitTheShorterAxis() {
        val tall = OrthoCamera(0f, (Math.PI / 2).toFloat(), 1f, SceneFrame.of(cube.positions), 400, 800)
        val wide = OrthoCamera(0f, (Math.PI / 2).toFloat(), 1f, SceneFrame.of(cube.positions), 800, 400)
        val tp = tall.project(10f, 5f, 5f); val wp = wide.project(5f, 10f, 5f)
        assertTrue(tp.x - 200f in 1f..199f, "im Hochformat bleibt die Breite maßgebend")
        assertTrue(abs(wp.y - 200f) in 1f..199f, "im Querformat die Höhe")
    }
}
