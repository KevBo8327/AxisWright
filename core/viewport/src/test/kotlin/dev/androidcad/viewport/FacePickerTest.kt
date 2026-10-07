package dev.androidcad.viewport

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacePickerTest {
    // Würfel [0,10]³; je Fläche zwei Dreiecke, Flächen-IDs 1..6: z=0, z=10, y=0, y=10, x=0, x=10
    private val corners = (0..7).map { floatArrayOf(if (it and 1 != 0) 10f else 0f, if (it and 2 != 0) 10f else 0f, if (it and 4 != 0) 10f else 0f) }
    private val positions = FloatArray(24).also { p -> corners.forEachIndexed { i, c -> c.copyInto(p, 3 * i) } }
    private val triangles = intArrayOf(0, 2, 1, 1, 2, 3, 4, 5, 6, 5, 7, 6, 0, 1, 4, 1, 5, 4, 2, 6, 3, 3, 6, 7, 0, 4, 2, 2, 4, 6, 1, 3, 5, 3, 7, 5)
    private val triangleFace = IntArray(12) { it / 2 + 1 }
    private val centers = listOf(floatArrayOf(5f, 5f, 0f), floatArrayOf(5f, 5f, 10f), floatArrayOf(5f, 0f, 5f), floatArrayOf(5f, 10f, 5f), floatArrayOf(0f, 5f, 5f), floatArrayOf(10f, 5f, 5f))
    private val normals = listOf(floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(1f, 0f, 0f))
    private val camera = OrthoCamera(-0.6f, 0.5f, 1f, SceneFrame.of(positions), 800, 600)

    private fun pickCenter(face: Int): FaceHit? {
        val c = centers[face]
        val s = camera.project(c[0], c[1], c[2])
        return FacePicker.pick(camera, s.x, s.y, positions, triangles, triangleFace)
    }

    @Test
    fun rayIsTheInverseOfProject() {
        for (p in listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(3f, 7f, 2f), floatArrayOf(10f, 10f, 10f))) {
            val s = camera.project(p[0], p[1], p[2])
            val r = camera.ray(s.x, s.y)
            // p liegt auf dem Strahl: Abstand von p zur Geraden ≈ 0
            val ox = p[0] - r.origin[0]; val oy = p[1] - r.origin[1]; val oz = p[2] - r.origin[2]
            val t = ox * r.direction[0] + oy * r.direction[1] + oz * r.direction[2]
            val rx = ox - t * r.direction[0]; val ry = oy - t * r.direction[1]; val rz = oz - t * r.direction[2]
            assertTrue(rx * rx + ry * ry + rz * rz < 1e-4f, "Punkt ${p.toList()} liegt nicht auf dem Strahl")
            assertTrue(t > 0f, "Der Strahl beginnt vor der Szene")
        }
        val r = camera.ray(400f, 300f)
        val toViewer = camera.towardViewer
        assertTrue(
            abs(r.direction[0] + toViewer[0]) < 1e-4f && abs(r.direction[1] + toViewer[1]) < 1e-4f && abs(r.direction[2] + toViewer[2]) < 1e-4f,
            "Richtung = Gegenrichtung zum Betrachter",
        )
    }

    @Test
    fun picksExactlyTheFacesThatFaceTheViewer() {
        var visible = 0
        for (f in 0..5) {
            val n = normals[f]
            val facing = n[0] * camera.towardViewer[0] + n[1] * camera.towardViewer[1] + n[2] * camera.towardViewer[2] > 0.01f
            val hit = pickCenter(f)
            if (facing) {
                visible++
                val h = assertNotNull(hit, "Fläche ${f + 1} ist sichtbar")
                assertEquals(f + 1, h.faceId)
                val c = centers[f]
                val d = h.point[0] * n[0] + h.point[1] * n[1] + h.point[2] * n[2] - (c[0] * n[0] + c[1] * n[1] + c[2] * n[2])
                assertTrue(abs(d) < 1e-3f, "Treffpunkt liegt auf der Fläche")
            } else {
                assertTrue(hit == null || hit.faceId != f + 1, "Rückseite ${f + 1} darf nicht vor der Vorderseite getroffen werden")
            }
        }
        assertEquals(3, visible) // von schräg oben sieht man drei Würfelflächen
    }

    @Test
    fun tapOutsideTheModelHitsNothing() {
        assertNull(FacePicker.pick(camera, 5f, 5f, positions, triangles, triangleFace))
        assertNull(FacePicker.pick(camera, 795f, 595f, positions, triangles, triangleFace))
    }

    @Test
    fun nearestSurfaceWinsWhenSeveralAreBehindEachOther() {
        // Tipp auf die Mitte des Deckels: der Strahl trifft danach noch den Boden – gewinnen muss der Deckel
        val hit = assertNotNull(pickCenter(1))
        assertEquals(2, hit.faceId)
        assertTrue(hit.distance > 0f)
    }
}
