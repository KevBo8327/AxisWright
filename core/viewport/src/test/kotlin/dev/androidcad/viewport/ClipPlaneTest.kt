package dev.androidcad.viewport

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipPlaneTest {

    @Test
    fun pointAndNormalConstruction() {
        val plane = ClipPlane.fromPointAndNormal(0f, 0f, 10f, 0f, 0f, 1f)
        assertEquals(0f, plane.nx)
        assertEquals(0f, plane.ny)
        assertEquals(1f, plane.nz)
        assertEquals(-10f, plane.d)

        assertTrue(plane.isVisible(0f, 0f, 15f))
        assertTrue(plane.isVisible(0f, 0f, 10f))
        assertFalse(plane.isVisible(0f, 0f, 5f))

        assertFalse(plane.isClipped(0f, 0f, 15f))
        assertFalse(plane.isClipped(0f, 0f, 10f))
        assertTrue(plane.isClipped(0f, 0f, 5f))

        val arr = plane.toFloatArray()
        assertEquals(4, arr.size)
        assertEquals(0f, arr[0])
        assertEquals(0f, arr[1])
        assertEquals(1f, arr[2])
        assertEquals(-10f, arr[3])
    }

    @Test
    fun invertedPlane() {
        // Z <= 10 sichtbar (Normale zeigt nach -Z)
        val plane = ClipPlane.fromPointAndNormal(0f, 0f, 10f, 0f, 0f, -1f)
        assertTrue(plane.isVisible(0f, 0f, 5f))
        assertTrue(plane.isVisible(0f, 0f, 10f))
        assertTrue(plane.isClipped(0f, 0f, 15f))
    }

    @Test
    fun facePickerWithClipPlaneRevealsInteriorFaces() {
        // Würfel [0,10]³; z=0 (Fläche 1), z=10 (Fläche 2), y=0 (3), y=10 (4), x=0 (5), x=10 (6)
        val corners = (0..7).map { floatArrayOf(if (it and 1 != 0) 10f else 0f, if (it and 2 != 0) 10f else 0f, if (it and 4 != 0) 10f else 0f) }
        val positions = FloatArray(24).also { p -> corners.forEachIndexed { i, c -> c.copyInto(p, 3 * i) } }
        val triangles = intArrayOf(0, 2, 1, 1, 2, 3, 4, 5, 6, 5, 7, 6, 0, 1, 4, 1, 5, 4, 2, 6, 3, 3, 6, 7, 0, 4, 2, 2, 4, 6, 1, 3, 5, 3, 7, 5)
        val triangleFace = IntArray(12) { it / 2 + 1 }
        // Kamera von oben/isometrisch, blickt auf z=10
        val camera = OrthoCamera(-0.6f, 0.5f, 1f, SceneFrame.of(positions), 800, 600)

        // Kamera direkt von oben: blickt senkrecht nach unten (-Z) bei pitch = PI/2
        val topCamera = OrthoCamera(0f, (Math.PI / 2).toFloat(), 1f, SceneFrame.of(positions), 800, 600)
        val topCenter = floatArrayOf(5f, 5f, 10f)
        val topProj = topCamera.project(topCenter[0], topCenter[1], topCenter[2])

        // Ohne Schnittebene trifft Tipp auf die Oberseite (Fläche 2: z=10)
        val hitUnclipped = FacePicker.pick(topCamera, topProj.x, topProj.y, positions, triangles, triangleFace)
        assertNotNull(hitUnclipped)
        assertEquals(2, hitUnclipped.faceId)

        // Schnittebene bei z <= 5 (z > 5 wird weggeschnitten, Normale zeigt nach -Z)
        val cutPlane = ClipPlane.fromPointAndNormal(0f, 0f, 5f, 0f, 0f, -1f)
        val hitClipped = FacePicker.pick(topCamera, topProj.x, topProj.y, positions, triangles, triangleFace, cutPlane)
        // Die Oberseite (z=10) ist weggeschnitten; der Strahl trifft stattdessen den Boden des Würfels (z=0, Fläche 1)
        assertNotNull(hitClipped)
        assertEquals(1, hitClipped.faceId)
        assertTrue(hitClipped.point[2] < 1e-3f, "Boden z=0 getroffen")

        // Schnittebene schneidet den gesamten Würfel weg (z >= 20 sichtbar, Würfel liegt bei [0, 10])
        val cutAll = ClipPlane.fromPointAndNormal(0f, 0f, 20f, 0f, 0f, 1f)
        val hitNothing = FacePicker.pick(topCamera, topProj.x, topProj.y, positions, triangles, triangleFace, cutAll)
        assertNull(hitNothing, "Vollständig weggeschnittener Würfel darf nicht getroffen werden")
    }

    @Test
    fun edgePickerWithClipPlaneIgnoresClippedEdgesAndUnoccludesInnerEdges() {
        // Würfel [0,10]³
        val corners = (0..7).map { floatArrayOf(if (it and 1 != 0) 10f else 0f, if (it and 2 != 0) 10f else 0f, if (it and 4 != 0) 10f else 0f) }
        val positions = FloatArray(24).also { p -> corners.forEachIndexed { i, c -> c.copyInto(p, 3 * i) } }
        val triangles = intArrayOf(0, 2, 1, 1, 2, 3, 4, 5, 6, 5, 7, 6, 0, 1, 4, 1, 5, 4, 2, 6, 3, 3, 6, 7, 0, 4, 2, 2, 4, 6, 1, 3, 5, 3, 7, 5)

        // Zwei Kanten: Kante 0 oben bei z=10 (von (0,0,10) nach (10,0,10)), Kante 1 unten bei z=0 (von (0,0,0) nach (10,0,0))
        val polylines = floatArrayOf(
            0f, 0f, 10f, 10f, 0f, 10f, // Kante 0 (oben)
            0f, 0f, 0f, 10f, 0f, 0f,   // Kante 1 (unten)
        )
        val counts = intArrayOf(2, 2)
        val camera = OrthoCamera(-0.6f, 0.5f, 1f, SceneFrame.of(positions), 800, 600)

        val topEdgeMid = floatArrayOf(5f, 0f, 10f)
        val projTop = camera.project(topEdgeMid[0], topEdgeMid[1], topEdgeMid[2])

        // Ohne Schnitt wird obere Kante 0 gewählt
        val pickedTop = EdgePicker.pick(camera, projTop.x, projTop.y, polylines, counts, positions, triangles)
        assertEquals(0, pickedTop)

        // Schnittebene bei z <= 5 (z > 5 weggeschnitten): obere Kante 0 ist weggeschnitten
        val cutTop = ClipPlane.fromPointAndNormal(0f, 0f, 5f, 0f, 0f, -1f)
        val pickedAfterCut = EdgePicker.pick(camera, projTop.x, projTop.y, polylines, counts, positions, triangles, clipPlane = cutTop)
        // Kante 0 ist weggeschnitten und darf nicht gewählt werden
        assertTrue(pickedAfterCut != 0, "Weggeschnittene Kante darf nicht getroffen werden")

        // Untere Kante bei z=0
        val bottomMid = floatArrayOf(5f, 0f, 0f)
        val projBottom = camera.project(bottomMid[0], bottomMid[1], bottomMid[2])
        val pickedBottom = EdgePicker.pick(camera, projBottom.x, projBottom.y, polylines, counts, positions, triangles, clipPlane = cutTop)
        assertEquals(1, pickedBottom, "Untere Kante im sichtbaren Bereich wird getroffen")
    }
}
