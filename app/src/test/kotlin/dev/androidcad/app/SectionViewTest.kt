package dev.androidcad.app

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SectionViewTest {

    @Test
    fun defaultStateIsDisabled() {
        val s = SectionViewState()
        assertFalse(s.enabled)
        assertEquals(SectionPlaneType.XY, s.planeType)
        assertEquals(0.0, s.offset)
        assertFalse(s.flipped)
        assertNull(s.toClipPlane(), "Deaktivierte Schnittansicht liefert keine ClipPlane")
    }

    @Test
    fun standardCoordinatePlanes() {
        val s = SectionViewState().apply { enabled = true }

        // XY-Ebene: Normale (0, 0, 1)
        s.planeType = SectionPlaneType.XY
        s.offset = 12.0
        val clipXy = assertNotNull(s.toClipPlane())
        assertEquals(0f, clipXy.nx)
        assertEquals(0f, clipXy.ny)
        assertEquals(1f, clipXy.nz)
        assertEquals(-12f, clipXy.d)
        assertTrue(clipXy.isVisible(0f, 0f, 15f))
        assertTrue(clipXy.isClipped(0f, 0f, 5f))

        // Seite umkehren
        s.flipped = true
        val clipXyFlipped = assertNotNull(s.toClipPlane())
        assertEquals(0f, clipXyFlipped.nx)
        assertEquals(0f, clipXyFlipped.ny)
        assertEquals(-1f, clipXyFlipped.nz)
        assertEquals(12f, clipXyFlipped.d)
        assertTrue(clipXyFlipped.isVisible(0f, 0f, 5f))
        assertTrue(clipXyFlipped.isClipped(0f, 0f, 15f))

        // XZ-Ebene: Normale (0, 1, 0)
        s.flipped = false
        s.planeType = SectionPlaneType.XZ
        s.offset = -5.0
        val clipXz = assertNotNull(s.toClipPlane())
        assertEquals(0f, clipXz.nx)
        assertEquals(1f, clipXz.ny)
        assertEquals(0f, clipXz.nz)
        assertEquals(5f, clipXz.d)
        assertTrue(clipXz.isVisible(0f, 0f, 0f))
        assertTrue(clipXz.isClipped(0f, -10f, 0f))

        // YZ-Ebene: Normale (1, 0, 0)
        s.planeType = SectionPlaneType.YZ
        s.offset = 20.0
        val clipYz = assertNotNull(s.toClipPlane())
        assertEquals(1f, clipYz.nx)
        assertEquals(0f, clipYz.ny)
        assertEquals(0f, clipYz.nz)
        assertEquals(-20f, clipYz.d)
        assertTrue(clipYz.isVisible(25f, 0f, 0f))
        assertTrue(clipYz.isClipped(10f, 0f, 0f))
    }

    @Test
    fun customPlanarFace() {
        val s = SectionViewState().apply {
            enabled = true
            planeType = SectionPlaneType.FACE
            customPlaneOrigin = Triple(10.0, 20.0, 30.0)
            customPlaneNormal = Triple(0.0, 0.0, 1.0)
            offset = 5.0
        }

        val clip = assertNotNull(s.toClipPlane())
        // Stützpunkt (10, 20, 30) + 5 * (0, 0, 1) = (10, 20, 35)
        assertEquals(0f, clip.nx)
        assertEquals(0f, clip.ny)
        assertEquals(1f, clip.nz)
        assertEquals(-35f, clip.d)
        assertTrue(clip.isVisible(10f, 20f, 40f))
        assertTrue(clip.isClipped(10f, 20f, 30f))
    }
}
