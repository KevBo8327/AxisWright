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
    fun aPlainNumberMayUseADecimalComma() {
        val r = evaluateParameters(listOf(Parameter("wand", "2,5"), Parameter("innen", "max(wand, 1) * 2"), Parameter("k", "-0,8")))
        assertEquals(2.5, r.values["wand"])
        assertEquals(5.0, r.values["innen"], "in Formeln trennt das Komma Argumente")
        assertEquals(-0.8, r.values["k"])
        assertTrue(r.errors.isEmpty(), r.errors.toString())
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
    fun secondDistanceCanBeDrivenByParameter() {
        val p = plate()
        p.doc.update((p.doc.feature(p.extrude.id) as ExtrudeFeature).copy(distance2 = 5.0))
        p.doc.setParameters(listOf(Parameter("d2", "12.5")))
        p.doc.bind(ValueRef.FeatureValue(p.extrude.id, 1), "d2")
        val r = p.doc.applyParameters()
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(12.5, (p.doc.feature(p.extrude.id) as ExtrudeFeature).distance2)
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

    @Test
    fun decimalCommaWorksInBoundExpressionsAndFormulas() {
        val p = plate()
        p.doc.setParameters(listOf(Parameter("wand", "2,5"), Parameter("doppel", "wand * 1,5")))
        p.doc.bind(ValueRef.Dim(p.sketch.id, p.width), "wand * 1,5 + 2,5")
        p.doc.bind(ValueRef.FeatureValue(p.extrude.id), "2,5")
        val r = p.doc.applyParameters()
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(3.75, r.values["doppel"])
        assertEquals(6.25, p.widthValue())
        assertEquals(-2.5, (p.doc.feature(p.extrude.id) as ExtrudeFeature).distance)
    }

    @Test
    fun patternPlaneAndHoleDepthCanBeDrivenByParameters() {
        val p = plate()
        val patLin = p.doc.addPattern(
            "Raster",
            p.extrude.id,
            PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 15.0, direction2 = Vec3(0.0, 1.0, 0.0), count2 = 2, spacing2 = 10.0),
        )
        val patCirc = p.doc.addPattern("Kreis", p.extrude.id, PatternKind.Circular(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), 4, 180.0))
        val top = FaceRef(p.extrude.id, FaceRole.TOP)
        val planeOffset = p.doc.addPlane("Versatz", PlaneDef.Offset(top, 5.0), Workplane.XY)
        val edge = EdgeRef.of(top, FaceRef(p.extrude.id, FaceRole.SIDE, 1))
        val planeEdge = p.doc.addPlane("Kante", PlaneDef.AtEdge(top, edge, 30.0), Workplane.XY)
        val hole = p.doc.addHole("Sackloch", top, 10.0, 10.0, 0.0, 6.0, depth = 4.0)

        p.doc.setParameters(listOf(Parameter("s", "22,5"), Parameter("w", "45")))
        p.doc.bind(ValueRef.FeatureValue(patLin.id, 0), "s")
        p.doc.bind(ValueRef.FeatureValue(patLin.id, 1), "s * 2")
        p.doc.bind(ValueRef.FeatureValue(patCirc.id), "w * 2")
        p.doc.bind(ValueRef.FeatureValue(planeOffset.id), "-s")
        p.doc.bind(ValueRef.FeatureValue(planeEdge.id), "w")
        p.doc.bind(ValueRef.FeatureValue(hole.id, 1), "12,5")

        val r = p.doc.applyParameters()
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val lin = (p.doc.feature(patLin.id) as PatternFeature).kind as PatternKind.Linear
        assertEquals(22.5, lin.spacing)
        assertEquals(45.0, lin.spacing2)
        val circ = (p.doc.feature(patCirc.id) as PatternFeature).kind as PatternKind.Circular
        assertEquals(90.0, circ.angle)
        assertEquals(-22.5, ((p.doc.feature(planeOffset.id) as PlaneFeature).def as PlaneDef.Offset).distance)
        assertEquals(45.0, ((p.doc.feature(planeEdge.id) as PlaneFeature).def as PlaneDef.AtEdge).angle)
        assertEquals(12.5, (p.doc.feature(hole.id) as HoleFeature).depth)
    }
}
