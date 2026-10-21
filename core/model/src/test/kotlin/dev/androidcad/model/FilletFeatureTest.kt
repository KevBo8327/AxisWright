package dev.androidcad.model

import dev.androidcad.json.Json
import dev.androidcad.json.JsonException
import dev.androidcad.json.asObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilletFeatureTest {
    private class Setup {
        val doc = Document()
        val sketch = doc.addSketch("Grundriss").also { it.sketch.addRectangle(0.0, 0.0, 40.0, 20.0) }
        val ext = doc.addExtrude("Block", sketch, 10.0)
        val edge = EdgeRef.of(FaceRef(ext.id, FaceRole.TOP), FaceRef(ext.id, FaceRole.SIDE, sketch.sketch.lineIds[1].value))
    }

    @Test
    fun edgeRefHasCanonicalOrder() {
        val a = FaceRef(FeatureId(1), FaceRole.TOP)
        val b = FaceRef(FeatureId(1), FaceRole.SIDE, 5)
        assertEquals(EdgeRef.of(a, b), EdgeRef.of(b, a))
        assertTrue(EdgeRef.of(a, b).a.tag <= EdgeRef.of(a, b).b.tag)
    }

    @Test
    fun addFilletValidatesItsInput() {
        val s = Setup()
        assertFailsWith<IllegalArgumentException> { s.doc.addFillet("leer", emptyList(), 2.0) }
        val unknown = EdgeRef.of(FaceRef(FeatureId(99), FaceRole.TOP), FaceRef(FeatureId(99), FaceRole.BOTTOM))
        assertFailsWith<IllegalArgumentException> { s.doc.addFillet("fremd", listOf(unknown), 2.0) }
        val f = s.doc.addFillet("Rundung", listOf(s.edge), 2.0)
        assertEquals(listOf(s.sketch.id, s.ext.id, f.id), s.doc.features.map { it.id })
        assertEquals(setOf(s.ext.id), f.references)
    }

    @Test
    fun removingAFeatureRemovesEverythingThatDependsOnIt() {
        // Skizze entfernen → Extrusion → Verrundung
        run {
            val s = Setup()
            val f = s.doc.addFillet("R", listOf(s.edge), 2.0)
            val other = s.doc.addSketch("andere")
            assertEquals(listOf(s.ext.id, f.id), s.doc.dependents(s.sketch.id), "die Warnung nennt genau, was mitgeht")
            s.doc.remove(s.sketch.id)
            assertEquals(listOf(other.id), s.doc.features.map { it.id }, "Verrundung ${f.id} muss mit gehen")
        }
        // nur die Extrusion entfernen → Verrundung geht mit, Skizze bleibt
        run {
            val s = Setup()
            s.doc.addFillet("R", listOf(s.edge), 2.0)
            s.doc.remove(s.ext.id)
            assertEquals(listOf(s.sketch.id), s.doc.features.map { it.id })
        }
        // nur die Verrundung entfernen → nichts sonst
        run {
            val s = Setup()
            val f = s.doc.addFillet("R", listOf(s.edge), 2.0)
            s.doc.remove(f.id)
            assertEquals(listOf(s.sketch.id, s.ext.id), s.doc.features.map { it.id })
        }
        // Kette: zweite Verrundung hängt von den Flächen der ersten ab
        run {
            val s = Setup()
            val f1 = s.doc.addFillet("R1", listOf(s.edge), 2.0)
            val onRound = EdgeRef.of(FaceRef(f1.id, FaceRole.ROUND, 0), FaceRef(s.ext.id, FaceRole.TOP))
            s.doc.addFillet("R2", listOf(onRound), 1.0)
            s.doc.remove(f1.id)
            assertEquals(listOf(s.sketch.id, s.ext.id), s.doc.features.map { it.id })
        }
    }

    @Test
    fun renameWorksForFillets() {
        val s = Setup()
        val f = s.doc.addFillet("R", listOf(s.edge), 2.0)
        s.doc.rename(f.id, "Kantenrundung")
        assertEquals("Kantenrundung", (s.doc.feature(f.id) as FilletFeature).name)
    }

    @Test
    fun fileRoundTripKeepsEdgesSizeAndKind() {
        val s = Setup()
        val e2 = EdgeRef.of(FaceRef(s.ext.id, FaceRole.BOTTOM), FaceRef(s.ext.id, FaceRole.SIDE, s.sketch.sketch.lineIds[2].value))
        s.doc.addFillet("Rundung", listOf(s.edge, e2), 2.5)
        s.doc.addFillet("Fase", listOf(EdgeRef.of(FaceRef(s.ext.id, FaceRole.TOP), FaceRef(s.ext.id, FaceRole.SIDE, s.sketch.sketch.lineIds[3].value))), 1.0, EdgeKind.CHAMFER)
        val back = DocumentCodec.decode(DocumentCodec.encode(s.doc))
        assertEquals(s.doc.features.filterIsInstance<FilletFeature>(), back.features.filterIsInstance<FilletFeature>())
        assertEquals(EdgeKind.CHAMFER, back.features.filterIsInstance<FilletFeature>().last().kind)
    }

    private fun mutatedFillet(mutate: (MutableMap<String, Any?>) -> Unit): String {
        val s = Setup()
        s.doc.addFillet("R", listOf(s.edge), 2.0)
        val json = LinkedHashMap(Json.parse(DocumentCodec.encode(s.doc)).asObject())
        @Suppress("UNCHECKED_CAST")
        val features = (json["features"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }.toMutableList()
        mutate(features.last())
        json["features"] = features
        return Json.write(json)
    }

    @Test
    fun rejectsBrokenFilletEntries() {
        val cases: Map<String, (MutableMap<String, Any?>) -> Unit> = mapOf(
            "unbekannter Modus" to { f -> f["mode"] = "BEVEL" },
            "keine Kanten" to { f -> f["edges"] = emptyList<Any?>() },
            "Referenz auf unbekanntes Feature" to { f -> f["edges"] = listOf(mapOf("a" to listOf(77, 3, 0), "b" to listOf(77, 2, 0))) },
            "unbekannte Rolle" to { f -> f["edges"] = listOf(mapOf("a" to listOf(2, 9, 0), "b" to listOf(2, 3, 0))) },
            "Referenz falsch geformt" to { f -> f["edges"] = listOf(mapOf("a" to listOf(2, 3), "b" to listOf(2, 3, 0))) },
            "Größe fehlt" to { f -> f.remove("size") },
        )
        for ((name, m) in cases) assertFailsWith<JsonException>("sollte scheitern: $name") { DocumentCodec.decode(mutatedFillet(m)) }
    }
}
