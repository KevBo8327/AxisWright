package dev.androidcad.model

import dev.androidcad.json.JsonException
import dev.androidcad.sketch.LineId
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import dev.androidcad.sketch.regions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevolveModelTest {
    private val yAxis = RevolveGeometry.Axis(Vec2(0.0, 0.0), Vec2(0.0, 1.0))

    private fun problem(s: Sketch, axis: RevolveGeometry.Axis = yAxis) = RevolveGeometry.problem(s.regions().regions, axis)

    @Test
    fun sketchAxesAndLines() {
        val s = Sketch()
        val l = s.addLine(2.0, 1.0, 2.0, 11.0, construction = true)
        assertEquals(RevolveGeometry.Axis(Vec2(0.0, 0.0), Vec2(1.0, 0.0)), RevolveGeometry.resolve(s, RevolveAxis.SketchX))
        assertEquals(yAxis, RevolveGeometry.resolve(s, RevolveAxis.SketchY))
        assertEquals(RevolveGeometry.Axis(Vec2(2.0, 1.0), Vec2(0.0, 1.0)), RevolveGeometry.resolve(s, RevolveAxis.Line(l)))
        assertNull(RevolveGeometry.resolve(s, RevolveAxis.Line(LineId(999))), "unbekannte Linie")
    }

    @Test
    fun regionOnOneSideOrTouchingTheAxisIsFine() {
        assertNull(problem(Sketch().apply { addRectangle(5.0, 0.0, 10.0, 20.0) }))
        assertNull(problem(Sketch().apply { addRectangle(0.0, 0.0, 10.0, 20.0) }), "Kante auf der Achse")
        assertNull(problem(Sketch().apply { addRectangle(-10.0, 0.0, -5.0, 20.0) }), "links der Achse")
        assertNull(problem(Sketch().apply { addCircle(20.0, 0.0, 5.0) }))
        // zwei Flächen auf verschiedenen Seiten
        assertNull(problem(Sketch().apply { addRectangle(5.0, 0.0, 10.0, 20.0); addRectangle(-10.0, 0.0, -5.0, 20.0) }))
    }

    @Test
    fun crossingTheAxisIsAnError() {
        assertNotNull(problem(Sketch().apply { addRectangle(-5.0, 0.0, 10.0, 20.0) }))
        assertNotNull(problem(Sketch().apply { addCircle(2.0, 0.0, 5.0) }))
    }

    /** Halbkreis rechts der senkrechten Linie x = 1; [left] = der Bogen wölbt sich stattdessen nach links über die Achse. */
    private fun halfDisc(left: Boolean) = Sketch().apply {
        val top = addPoint(1.0, 3.0); val bottom = addPoint(1.0, -3.0); val c = addPoint(1.0, 0.0)
        addLine(bottom, top)
        // Bögen laufen gegen den Uhrzeigersinn: von oben nach unten über links, von unten nach oben über rechts
        if (left) addArc(c, top, bottom) else addArc(c, bottom, top)
    }

    @Test
    fun arcsAreCheckedAtTheirBulgeNotOnlyAtTheEnds() {
        assertNull(problem(halfDisc(left = false)))
        assertNotNull(problem(halfDisc(left = true)), "Bogen reicht bis x = −2")
    }

    @Test
    fun revolveRoundTripsAndFollowsItsSketch() {
        val doc = Document()
        val sk = doc.addSketch("Profil")
        sk.sketch.addRectangle(5.0, 0.0, 10.0, 20.0)
        val axis = sk.sketch.addLine(0.0, 0.0, 0.0, 30.0, construction = true)
        val a = doc.addRevolve("Welle", sk, RevolveAxis.Line(axis), 270.0, regionPoints = listOf(Vec2(7.0, 5.0)), symmetric = true)
        val b = doc.addRevolve("Nut", sk, RevolveAxis.SketchX, -45.0, BodyOp.CUT)
        val back = DocumentCodec.decode(DocumentCodec.encode(doc))
        assertEquals(listOf<Feature>(a, b), back.features.filterIsInstance<RevolveFeature>())

        back.remove(sk.id)
        assertTrue(back.features.none { it is RevolveFeature }, "Drehungen einer gelöschten Skizze fallen mit weg")
    }

    @Test
    fun invalidRevolveInFileIsRejected() {
        val doc = Document()
        val sk = doc.addSketch("Profil")
        sk.sketch.addRectangle(5.0, 0.0, 10.0, 20.0)
        doc.addRevolve("Welle", sk, RevolveAxis.SketchY, 90.0)
        val text = DocumentCodec.encode(doc)
        assertFailsWith<JsonException> { DocumentCodec.decode(text.replace(Regex(""""angle":\s*[0-9.]+"""), "\"angle\": 0")) }
        assertFailsWith<JsonException> { DocumentCodec.decode(text.replace(Regex(""""type":\s*"y""""), "\"type\": \"z\"")) }
    }
}
