package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SketchOpsTransformTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-5) =
        assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    @Test
    fun translateMovesGeometryAndCleansUpContradictingConstraints() {
        val s = Sketch()
        val rect = s.addRectangle(10.0, 10.0, 30.0, 20.0)
        s.add(LengthDim(rect.edges[0], 20.0))
        // Externe Bedingung zum Ursprung
        val distToOrigin = s.add(DistanceDim(rect.corners[0], s.origin, 14.1421356))
        // Fixierung an einem Eckpunkt
        val fixedCorner = s.add(Fixed(rect.corners[1], 30.0, 10.0))

        val r = SketchOps.translate(s, lines = rect.edges, dx = 15.0, dy = -5.0)
        assertTrue(r.ok, r.message.toString())
        assertEquals(SketchText.CONSTRAINTS_REMOVED, r.message?.key)

        // Externe Bedingungen müssen entfernt worden sein
        assertFalse(s.constraintEntries.any { it.id == distToOrigin })
        assertFalse(s.constraintEntries.any { it.id == fixedCorner })

        // Interne Bedingungen müssen erhalten bleiben
        assertTrue(s.constraintEntries.any { it.constraint is Horizontal })
        assertTrue(s.constraintEntries.any { it.constraint is LengthDim })

        // Punkte müssen verschoben sein
        val p0 = s.point(rect.corners[0])
        near(25.0, p0.x); near(5.0, p0.y)

        assertTrue(s.solve().isSolved, "Skizze muss nach Verschiebung lösbar sein")
    }

    @Test
    fun rotateBy90DegreesSwapsHorizontalAndVertical() {
        val s = Sketch()
        val p1 = s.addPoint(0.0, 0.0)
        val p2 = s.addPoint(10.0, 0.0)
        val l = s.addLine(p1, p2)
        s.add(Horizontal(l))
        s.add(LengthDim(l, 10.0))
        s.add(HorizontalDistanceDim(p1, p2, 10.0))

        val r = SketchOps.rotate(s, lines = listOf(l), pivot = Vec2(0.0, 0.0), angleDegrees = 90.0)
        assertTrue(r.ok, r.message.toString())

        // Horizontal wurde zu Vertical
        assertTrue(s.constraintEntries.any { it.constraint is Vertical })
        assertFalse(s.constraintEntries.any { it.constraint is Horizontal })
        // HorizontalDistanceDim wurde zu VerticalDistanceDim
        assertTrue(s.constraintEntries.any { it.constraint is VerticalDistanceDim })
        assertFalse(s.constraintEntries.any { it.constraint is HorizontalDistanceDim })

        val pt2 = s.point(p2)
        near(0.0, pt2.x); near(10.0, pt2.y)
        assertTrue(s.solve().isSolved)
    }

    @Test
    fun rotateBy180DegreesNegatesDistanceDim() {
        val s = Sketch()
        val p1 = s.addPoint(0.0, 0.0)
        val p2 = s.addPoint(10.0, 0.0)
        val l = s.addLine(p1, p2)
        s.add(HorizontalDistanceDim(p1, p2, 10.0))

        val r = SketchOps.rotate(s, lines = listOf(l), pivot = Vec2(0.0, 0.0), angleDegrees = 180.0)
        assertTrue(r.ok)

        val dim = s.constraintEntries.map { it.constraint }.filterIsInstance<HorizontalDistanceDim>().single()
        near(-10.0, dim.value)

        val pt2 = s.point(p2)
        near(-10.0, pt2.x); near(0.0, pt2.y)
        assertTrue(s.solve().isSolved)
    }

    @Test
    fun rotateByArbitraryAngleRemovesAxisAlignments() {
        val s = Sketch()
        val p1 = s.addPoint(0.0, 0.0)
        val p2 = s.addPoint(10.0, 0.0)
        val l = s.addLine(p1, p2)
        s.add(Horizontal(l))
        val fixPivot = s.add(Fixed(p1, 0.0, 0.0))
        val fixEnd = s.add(Fixed(p2, 10.0, 0.0))

        val r = SketchOps.rotate(s, lines = listOf(l), pivot = Vec2(0.0, 0.0), angleDegrees = 45.0)
        assertTrue(r.ok)

        // Horizontal entfernt
        assertFalse(s.constraintEntries.any { it.constraint is Horizontal })
        // Fixierung am Drehpunkt bleibt erhalten, am gedrehten Punkt entfernt
        assertTrue(s.constraintEntries.any { it.id == fixPivot })
        assertFalse(s.constraintEntries.any { it.id == fixEnd })

        val pt2 = s.point(p2)
        near(10.0 * kotlin.math.cos(Math.toRadians(45.0)), pt2.x)
        near(10.0 * kotlin.math.sin(Math.toRadians(45.0)), pt2.y)
        assertTrue(s.solve().isSolved)
    }

    @Test
    fun scaleExpandsGeometryAndDimensions() {
        val s = Sketch()
        val rect = s.addRectangle(10.0, 10.0, 30.0, 20.0)
        val lenDim = s.add(LengthDim(rect.edges[0], 20.0))
        val cCenter = s.addPoint(15.0, 15.0)
        val circle = s.addCircle(cCenter, 4.0)
        val rDim = s.add(RadiusDim(circle, 4.0))

        val r = SketchOps.scale(s, lines = rect.edges, rounds = listOf(circle), pivot = Vec2(10.0, 10.0), factor = 2.0)
        assertTrue(r.ok, r.message.toString())

        // Maße verdoppelt
        val updatedLen = (s.constraint(lenDim) as LengthDim).value
        near(40.0, updatedLen)
        val updatedRadius = (s.constraint(rDim) as RadiusDim).value
        near(8.0, updatedRadius)
        near(8.0, s.circle(circle).radius)

        // Eckpunkt (30, 20) relativ zu (10, 10) skaliert -> (50, 30)
        val farCorner = s.point(rect.corners[2])
        near(50.0, farCorner.x); near(30.0, farCorner.y)
        assertTrue(s.solve().isSolved)
    }

    @Test
    fun scaleWithFactorZeroOrNegativeIsRejected() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 10.0, 10.0)
        val r0 = SketchOps.scale(s, lines = rect.edges, pivot = Vec2(0.0, 0.0), factor = 0.0)
        assertFalse(r0.ok)
        assertEquals(SketchText.SCALE_NOT_POSITIVE, r0.message?.key)

        val rNeg = SketchOps.scale(s, lines = rect.edges, pivot = Vec2(0.0, 0.0), factor = -2.0)
        assertFalse(rNeg.ok)
        assertEquals(SketchText.SCALE_NOT_POSITIVE, rNeg.message?.key)

        // Kopieren mit ungültigem Faktor ebenfalls abgelehnt
        val rCopy = SketchOps.copy(s, lines = rect.edges, scale = -1.0)
        assertFalse(rCopy.ok)
        assertEquals(SketchText.SCALE_NOT_POSITIVE, rCopy.message?.key)
    }

    @Test
    fun copyCreatesDuplicateWithTransformedGeometryAndCopiedConstraints() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 10.0, 10.0)
        s.add(LengthDim(rect.edges[0], 10.0))
        s.add(DistanceDim(rect.corners[0], s.origin, 0.0)) // Extern: darf nicht mitkopiert werden

        val r = SketchOps.copy(s, lines = rect.edges, dx = 25.0, dy = 5.0)
        assertTrue(r.ok)
        assertEquals(8, s.lineIds.size, "8 Linien (Original + Kopie)")

        // Kopierte Constraints: Horizontal (4 vom Rechteck: 2 original + 2 kopiert) und LengthDim existieren nun doppelt
        assertEquals(4, s.constraintEntries.count { it.constraint is Horizontal })
        assertEquals(2, s.constraintEntries.count { it.constraint is LengthDim })
        // Externe Bedingung zum Ursprung existiert weiterhin nur 1x
        assertEquals(1, s.constraintEntries.count { it.constraint is DistanceDim })

        assertTrue(s.solve().isSolved)
    }

    @Test
    fun transformSelectionContainingTextMovesLocationAdaptsAngleAndHeightAndCopiesText() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val textId = s.addText(10.0, 5.0, "CAD", 6.0, 0.0)
        val originTextId = s.addText(s.origin, "Origin", 4.0, 10.0)

        // 1. Verschieben: Text-Lage mitbewegen (sowohl freier Punkt als auch Ursprung)
        val rTrans = SketchOps.translate(s, lines = rect.edges, texts = listOf(textId, originTextId), dx = 15.0, dy = -5.0)
        assertTrue(rTrans.ok)
        val pos1 = s.point(s.text(textId).location)
        near(25.0, pos1.x); near(0.0, pos1.y)
        assertEquals("CAD", s.text(textId).text)
        assertEquals(6.0, s.text(textId).height)
        assertEquals(0.0, s.text(textId).angle)

        val posOrigin = s.point(s.text(originTextId).location)
        near(15.0, posOrigin.x); near(-5.0, posOrigin.y)
        assertEquals("Origin", s.text(originTextId).text)

        // 2. Drehen: Text-Lage drehen und Textwinkel anpassen
        val rRot = SketchOps.rotate(s, texts = listOf(textId), pivot = Vec2(25.0, 0.0), angleDegrees = 90.0)
        assertTrue(rRot.ok)
        val posRot = s.point(s.text(textId).location)
        near(25.0, posRot.x); near(0.0, posRot.y) // liegt genau auf dem Pivot
        near(90.0, s.text(textId).angle)

        // Text außerhalb des Pivots drehen
        val rRot2 = SketchOps.rotate(s, texts = listOf(originTextId), pivot = Vec2(10.0, -5.0), angleDegrees = 90.0)
        assertTrue(rRot2.ok)
        val posRot2 = s.point(s.text(originTextId).location)
        // (15, -5) relativ zu (10, -5) ist (5, 0) -> um 90° gedreht -> (0, 5) -> (10, 0)
        near(10.0, posRot2.x); near(0.0, posRot2.y)
        near(100.0, s.text(originTextId).angle)

        // 3. Skalieren: Text-Lage skalieren und Texthöhe multiplizieren
        val rScale = SketchOps.scale(s, texts = listOf(textId), pivot = Vec2(20.0, 0.0), factor = 2.0)
        assertTrue(rScale.ok)
        val posScale = s.point(s.text(textId).location)
        // (25, 0) relativ zu (20, 0) mit Faktor 2 -> (30, 0)
        near(30.0, posScale.x); near(0.0, posScale.y)
        near(12.0, s.text(textId).height)
        near(90.0, s.text(textId).angle)

        // 4. Kopieren: Text mitkopieren inkl. transformierter Lage, Höhe und Drehung
        val rCopy = SketchOps.copy(
            s,
            lines = rect.edges,
            texts = listOf(textId),
            dx = 10.0,
            dy = 20.0,
            pivot = Vec2(30.0, 0.0),
            angleDegrees = 45.0,
            scale = 0.5,
        )
        assertTrue(rCopy.ok)
        assertEquals(3, s.textIds.size)

        // Original bleibt unverändert
        near(30.0, s.point(s.text(textId).location).x)
        near(0.0, s.point(s.text(textId).location).y)
        near(12.0, s.text(textId).height)
        near(90.0, s.text(textId).angle)

        // Kopie prüfen
        val copyTextId = s.textIds.first { it != textId && it != originTextId }
        val copyText = s.text(copyTextId)
        assertEquals("CAD", copyText.text)
        near(6.0, copyText.height) // 12.0 * 0.5
        near(135.0, copyText.angle) // 90.0 + 45.0
        val posCopy = s.point(copyText.location)
        // (30, 0) relativ zu (30, 0) ist (0, 0) -> skaliert 0, gedreht 0 -> + (10, 20) -> (40, 20)
        near(40.0, posCopy.x); near(20.0, posCopy.y)
    }

    @Test
    fun emptySelectionFails() {
        val s = Sketch()
        assertFalse(SketchOps.translate(s, dx = 10.0, dy = 0.0).ok)
        assertFalse(SketchOps.rotate(s, pivot = Vec2(0.0, 0.0), angleDegrees = 90.0).ok)
        assertFalse(SketchOps.scale(s, pivot = Vec2(0.0, 0.0), factor = 2.0).ok)
        assertFalse(SketchOps.copy(s, dx = 10.0).ok)
    }
}
