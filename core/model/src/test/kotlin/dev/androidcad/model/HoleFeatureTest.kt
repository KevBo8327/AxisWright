package dev.androidcad.model

import dev.androidcad.json.Json
import dev.androidcad.json.JsonException
import dev.androidcad.json.asObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HoleFeatureTest {
    private class Setup {
        val doc = Document()
        val sketch = doc.addSketch("Grundriss").also { it.sketch.addRectangle(0.0, 0.0, 40.0, 20.0) }
        val ext = doc.addExtrude("Block", sketch, 10.0)
        val top = FaceRef(ext.id, FaceRole.TOP)
    }

    @Test
    fun addHoleValidatesTheFaceReference() {
        val s = Setup()
        assertFailsWith<IllegalArgumentException> { s.doc.addHole("fremd", FaceRef(FeatureId(99), FaceRole.TOP), 0.0, 0.0, 0.0, 5.0) }
        val h = s.doc.addHole("Bohrung", s.top, 10.0, 10.0, 10.0, 5.0, depth = 4.0)
        assertEquals(listOf(s.sketch.id, s.ext.id, h.id), s.doc.features.map { it.id })
        assertEquals(setOf(s.ext.id), h.references)
    }

    @Test
    fun removingTheBodyFeatureRemovesTheHolesInIt() {
        val s = Setup()
        s.doc.addHole("B1", s.top, 5.0, 5.0, 10.0, 3.0)
        s.doc.addHole("B2", s.top, 15.0, 5.0, 10.0, 3.0, 2.0)
        val other = s.doc.addSketch("andere")
        s.doc.remove(s.ext.id)
        assertEquals(listOf(s.sketch.id, other.id), s.doc.features.map { it.id })
    }

    @Test
    fun aHoleOnTheWallOfAnEarlierHoleDependsOnIt() {
        val s = Setup()
        val h1 = s.doc.addHole("B1", s.top, 5.0, 5.0, 10.0, 3.0)
        s.doc.addHole("B2", FaceRef(h1.id, FaceRole.TOP), 5.0, 5.0, 2.0, 1.0) // Boden der ersten
        s.doc.remove(h1.id)
        assertEquals(listOf(s.sketch.id, s.ext.id), s.doc.features.map { it.id })
    }

    @Test
    fun renameWorksAndFileRoundTripKeepsEverything() {
        val s = Setup()
        val through = s.doc.addHole("Durchgang", s.top, 10.0, 5.0, 10.0, 6.5)
        val blind = s.doc.addHole("Sackloch", s.top, 30.0, 5.0, 10.0, 4.0, depth = 3.5)
        s.doc.rename(through.id, "Kabeldurchführung")
        val back = DocumentCodec.decode(DocumentCodec.encode(s.doc))
        val holes = back.features.filterIsInstance<HoleFeature>()
        assertEquals("Kabeldurchführung", holes[0].name)
        assertEquals(null, holes[0].depth)
        assertEquals(blind, holes[1])
    }

    private fun anchorsOf(s: Setup): Pair<EdgeDistance, EdgeDistance> {
        val front = FaceRef(s.ext.id, FaceRole.SIDE, s.sketch.sketch.lineIds[0].value)
        val right = FaceRef(s.ext.id, FaceRole.SIDE, s.sketch.sketch.lineIds[1].value)
        return EdgeDistance(EdgeRef.of(s.top, front), 12.0) to EdgeDistance(EdgeRef.of(s.top, right), -10.0)
    }

    @Test
    fun anchoredHoleRoundTripsAndDependsOnTheFeaturesOfItsEdges() {
        val s = Setup()
        val anchors = anchorsOf(s)
        val h = s.doc.addHole("verankert", s.top, 30.0, 12.0, 10.0, 5.0, anchors = anchors)
        assertEquals(setOf(s.ext.id), h.references)
        val back = DocumentCodec.decode(DocumentCodec.encode(s.doc)).features.filterIsInstance<HoleFeature>().single()
        assertEquals(h, back)
        assertEquals(anchors, back.anchors)

        // Referenzkante aus einem späteren Feature: Löschen dieses Features entfernt die Bohrung mit
        val s2 = Setup()
        val sk2 = s2.doc.addSketch("Zapfen").also { it.sketch.addRectangle(0.0, 0.0, 5.0, 5.0) }
        val ext2 = s2.doc.addExtrude("Zapfen", sk2, 3.0)
        val edge = EdgeRef.of(FaceRef(ext2.id, FaceRole.TOP), FaceRef(ext2.id, FaceRole.SIDE, sk2.sketch.lineIds[0].value))
        val anch = EdgeDistance(edge, 1.0) to EdgeDistance(edge, 2.0)
        s2.doc.addHole("an Zapfen", s2.top, 1.0, 1.0, 10.0, 2.0, anchors = anch)
        s2.doc.remove(ext2.id)
        assertEquals(listOf(s2.sketch.id, s2.ext.id, sk2.id), s2.doc.features.map { it.id })
    }

    @Test
    fun addHoleRejectsAnchorsOnUnknownFeatures() {
        val s = Setup()
        val ghost = EdgeRef.of(FaceRef(FeatureId(99), FaceRole.TOP), FaceRef(FeatureId(99), FaceRole.BOTTOM))
        assertFailsWith<IllegalArgumentException> {
            s.doc.addHole("x", s.top, 0.0, 0.0, 0.0, 3.0, anchors = EdgeDistance(ghost, 1.0) to EdgeDistance(ghost, 2.0))
        }
    }

    @Test
    fun brokenAnchorEntriesAreRejectedOnLoad() {
        val s = Setup()
        s.doc.addHole("B", s.top, 5.0, 5.0, 10.0, 3.0, anchors = anchorsOf(s))
        val good = LinkedHashMap(Json.parse(DocumentCodec.encode(s.doc)).asObject())
        @Suppress("UNCHECKED_CAST")
        fun withAnchors(anchors: Any?): String {
            val features = (good["features"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }.toMutableList()
            features.last()["anchors"] = anchors
            return Json.write(LinkedHashMap(good).also { it["features"] = features })
        }
        assertFailsWith<JsonException> { DocumentCodec.decode(withAnchors(listOf(mapOf("a" to listOf(2, 3, 0), "b" to listOf(2, 1, 1), "distance" to 1)))) } // nur eine
        assertFailsWith<JsonException> { DocumentCodec.decode(withAnchors("kaputt")) }
        assertFailsWith<JsonException> {
            val e = mapOf("a" to listOf(2, 3, 0), "b" to listOf(2, 1, 1))
            DocumentCodec.decode(withAnchors(listOf(e + ("distance" to "x"), e + ("distance" to 1))))
        }
        assertFailsWith<JsonException> { // Referenz auf ein Feature, das es nicht gibt
            val e = mapOf("a" to listOf(88, 3, 0), "b" to listOf(88, 1, 1), "distance" to 1)
            DocumentCodec.decode(withAnchors(listOf(e, e)))
        }
    }

    private fun mutated(mutate: (MutableMap<String, Any?>) -> Unit): String {
        val s = Setup()
        s.doc.addHole("B", s.top, 5.0, 5.0, 10.0, 3.0, 2.0)
        val json = LinkedHashMap(Json.parse(DocumentCodec.encode(s.doc)).asObject())
        @Suppress("UNCHECKED_CAST")
        val features = (json["features"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }.toMutableList()
        mutate(features.last())
        json["features"] = features
        return Json.write(json)
    }

    @Test
    fun rejectsBrokenHoleEntries() {
        val cases: Map<String, (MutableMap<String, Any?>) -> Unit> = mapOf(
            "Punkt fehlt" to { f -> f.remove("point") },
            "Punkt zu kurz" to { f -> f["point"] = listOf(1, 2) },
            "Durchmesser fehlt" to { f -> f.remove("diameter") },
            "Tiefe kein Zahlenwert" to { f -> f["depth"] = "tief" },
            "Fläche unbekannte Rolle" to { f -> f["face"] = listOf(2, 9, 0) },
            "Fläche zeigt auf nicht vorhandenes Feature" to { f -> f["face"] = listOf(77, 3, 0) },
        )
        for ((name, m) in cases) assertFailsWith<JsonException>("sollte scheitern: $name") { DocumentCodec.decode(mutated(m)) }
    }

    @Test
    fun counterboreAndCountersinkRoundTripThroughDocumentCodec() {
        val s = Setup()
        val cb = s.doc.addHole(
            "Senkbohrung", s.top, 10.0, 10.0, 10.0, 6.6, depth = 15.0,
            kind = HoleKind.COUNTERBORE, counterboreDiameter = 11.0, counterboreDepth = 6.4,
        )
        val cs = s.doc.addHole(
            "Kegelsenkung", s.top, 25.0, 10.0, 10.0, 6.6,
            kind = HoleKind.COUNTERSINK, countersinkDiameter = 13.7, countersinkAngle = 90.0,
        )
        val decoded = DocumentCodec.decode(DocumentCodec.encode(s.doc))
        val holes = decoded.features.filterIsInstance<HoleFeature>()
        assertEquals(2, holes.size)
        assertEquals(cb, holes[0])
        assertEquals(cs, holes[1])
        assertEquals(HoleKind.COUNTERBORE, holes[0].kind)
        assertEquals(11.0, holes[0].counterboreDiameter)
        assertEquals(6.4, holes[0].counterboreDepth)
        assertEquals(HoleKind.COUNTERSINK, holes[1].kind)
        assertEquals(13.7, holes[1].countersinkDiameter)
        assertEquals(90.0, holes[1].countersinkAngle)
    }

    @Test
    fun parameterBindingControlsHoleDiameter() {
        val s = Setup()
        val h = s.doc.addHole("Bohrung", s.top, 10.0, 10.0, 10.0, 6.0)
        s.doc.setParameters(listOf(Parameter("d", "8.5")))
        s.doc.bind(ValueRef.FeatureValue(h.id), "d")
        s.doc.applyParameters()
        val updated = s.doc.feature(h.id) as HoleFeature
        assertEquals(8.5, updated.diameter)
    }
}
