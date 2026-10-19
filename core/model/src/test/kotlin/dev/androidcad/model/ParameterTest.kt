package dev.androidcad.model

import dev.androidcad.json.JsonException
import dev.androidcad.sketch.Dimension
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParameterTest {
    private class Plate(val doc: Document, val sketch: SketchFeature, val width: dev.androidcad.sketch.ConstraintId, val extrude: ExtrudeFeature)

    private fun plate(): Plate {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        val r = sk.sketch.addRectangle(0.0, 0.0, 40.0, 20.0)
        val width = sk.sketch.addLength(r.edges[0], 40.0)
        val ex = doc.addExtrude("Platte", sk, -5.0) // nach unten: das Vorzeichen muss bleiben
        return Plate(doc, sk, width, ex)
    }

    private fun Plate.widthValue() = (sketch.sketch.constraint(width) as Dimension).value

    @Test
    fun namesMayContainUmlauts() {
        assertEquals(null, Parameter.nameProblem("länge_außen"))
        assertTrue(Parameter.nameProblem("2breite") != null)
        val r = evaluateParameters(listOf(Parameter("länge", "40"), Parameter("höhe", "länge / 2 + 1")))
        assertEquals(21.0, r.values["höhe"])
    }

    @Test
    fun expressionsDriveDimensionsAndFeatureValues() {
        val p = plate()
        p.doc.setParameters(listOf(Parameter("wand", "3"), Parameter("breite", "10 * wand + 2")))
        p.doc.bind(ValueRef.Dim(p.sketch.id, p.width), "breite")
        p.doc.bind(ValueRef.FeatureValue(p.extrude.id), "wand * 2")
        val r = p.doc.applyParameters()
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(32.0, p.widthValue())
        assertEquals(-6.0, (p.doc.feature(p.extrude.id) as ExtrudeFeature).distance, "Richtung bleibt")
        // Parameter ändern: alles folgt
        p.doc.setParameters(listOf(Parameter("wand", "4"), Parameter("breite", "10 * wand + 2")))
        p.doc.applyParameters()
        assertEquals(42.0, p.widthValue())
    }

    @Test
    fun renamingAParameterRenamesItEverywhere() {
        val p = plate()
        p.doc.setParameters(listOf(Parameter("wand", "3"), Parameter("wand2", "1"), Parameter("breite", "10 * wand + wand2")))
        p.doc.bind(ValueRef.Dim(p.sketch.id, p.width), "breite + wand")
        // Buchstabe für Buchstabe wie beim Tippen: jeder gültige Zwischenname wird übernommen
        for (name in listOf("wandstärke", "wandstärke_außen")) {
            val list = p.doc.parameters.toMutableList()
            list[0] = list[0].copy(name = name)
            p.doc.setParameters(list)
        }
        assertEquals("10 * wandstärke_außen + wand2", p.doc.parameters[2].expression, "wand2 bleibt unberührt")
        assertEquals("breite + wandstärke_außen", p.doc.bindings[ValueRef.Dim(p.sketch.id, p.width)])
        assertTrue(p.doc.applyParameters().errors.isEmpty())
        assertEquals(34.0, p.widthValue())
        // Löschen ist kein Umbenennen: der Ausdruck bleibt (und meldet dann den fehlenden Namen)
        p.doc.setParameters(p.doc.parameters.drop(1))
        assertEquals("10 * wandstärke_außen + wand2", p.doc.parameters[1].expression)
    }

    @Test
    fun brokenExpressionsKeepTheOldValueAndAreReported() {
        val p = plate()
        p.doc.bind(ValueRef.Dim(p.sketch.id, p.width), "hoehe * 2")
        p.doc.bind(ValueRef.FeatureValue(p.extrude.id), "0")
        val r = p.doc.applyParameters()
        assertEquals(2, r.errors.size, r.errors.toString())
        assertEquals(40.0, p.widthValue())
        assertEquals(-5.0, (p.doc.feature(p.extrude.id) as ExtrudeFeature).distance)
    }

    @Test
    fun bindingsFollowTheModelAndRoundTrip() {
        val p = plate()
        p.doc.setParameters(listOf(Parameter("breite", "50")))
        p.doc.bind(ValueRef.Dim(p.sketch.id, p.width), "breite")
        p.doc.bind(ValueRef.FeatureValue(p.extrude.id), "breite / 10")
        val back = DocumentCodec.decode(DocumentCodec.encode(p.doc))
        assertEquals(p.doc.parameters, back.parameters)
        assertEquals(p.doc.bindings, back.bindings)
        // gelöschte Extrusion: ihre Bindung fällt weg
        back.remove(p.extrude.id)
        assertEquals(setOf<ValueRef>(ValueRef.Dim(p.sketch.id, p.width)), back.bindings.keys)
        // gelöschtes Maß: Bindung fällt beim nächsten Anwenden weg
        back.sketch(p.sketch.id).sketch.remove(p.width)
        back.applyParameters()
        assertTrue(back.bindings.isEmpty())
    }

    @Test
    fun invalidNamesAreRejected() {
        val doc = Document()
        assertFailsWith<IllegalArgumentException> { doc.setParameters(listOf(Parameter("2breite", "1"))) }
        assertFailsWith<IllegalArgumentException> { doc.setParameters(listOf(Parameter("a", "1"), Parameter("a", "2"))) }
        val p = plate()
        p.doc.setParameters(listOf(Parameter("ok", "1")))
        assertFailsWith<JsonException> { DocumentCodec.decode(DocumentCodec.encode(p.doc).replace("\"ok\"", "\"1x\"")) }
    }
}
