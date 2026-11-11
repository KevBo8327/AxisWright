package dev.androidcad.model

import dev.androidcad.json.Json
import dev.androidcad.json.JsonException
import dev.androidcad.json.asObject
import dev.androidcad.sketch.Vec2
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentCodecTest {
    private fun sample(): Document {
        val doc = Document()
        val sk = doc.addSketch("Grundriss", Workplane.XY.offset(2.5))
        val r = sk.sketch.addRectangle(0.0, 0.0, 40.0, 25.0)
        sk.sketch.addLength(r.edges[0], 40.0)
        val hole = doc.addSketch("Bohrung", Workplane.XZ)
        hole.sketch.addCircle(5.0, 5.0, 2.0)
        doc.addExtrude("Platte", sk, 8.0, regionPoints = listOf(Vec2(20.0, 12.0)))
        val loch = doc.addExtrude("Loch", hole, -3.0, BodyOp.CUT, symmetric = true)
        doc.update(loch.copy(throughAll = true))
        return doc
    }

    private fun assertSameDocument(a: Document, b: Document) {
        assertEquals(a.nextIdValue, b.nextIdValue)
        assertEquals(a.features.size, b.features.size)
        for ((x, y) in a.features.zip(b.features)) {
            assertEquals(x.id, y.id); assertEquals(x.name, y.name)
            when (x) {
                is ExtrudeFeature, is RevolveFeature, is ShellFeature, is FilletFeature, is HoleFeature, is PatternFeature, is PlaneFeature, is MirrorBodyFeature -> assertEquals(x, y)
                is SketchFeature -> {
                    y as SketchFeature
                    assertEquals(x.plane.origin, y.plane.origin)
                    assertEquals(x.plane.xDir, y.plane.xDir)
                    assertEquals(x.plane.normal, y.plane.normal)
                    assertEquals(x.sketch.pointIds, y.sketch.pointIds)
                    assertEquals(x.sketch.lineIds, y.sketch.lineIds)
                    assertEquals(x.sketch.circleIds, y.sketch.circleIds)
                    assertEquals(x.sketch.constraintEntries, y.sketch.constraintEntries)
                    x.sketch.pointIds.forEach { assertEquals(x.sketch.point(it), y.sketch.point(it)) }
                }
            }
        }
    }

    @Test
    fun roundTripKeepsFeaturesPlanesSketchesAndIds() {
        val doc = sample()
        val back = DocumentCodec.decode(DocumentCodec.encode(doc))
        assertSameDocument(doc, back)
    }

    @Test
    fun roundTripWithTwoSidedExtrudeIntersectAndUntilFace() {
        val doc = Document()
        val sk1 = doc.addSketch("Grundriss")
        sk1.sketch.addRectangle(0.0, 0.0, 40.0, 20.0)
        val ex1 = doc.addExtrude("Block", sk1, 10.0, distance2 = 5.0)
        val sk2 = doc.addSketch("Zweite")
        sk2.sketch.addCircle(10.0, 10.0, 5.0)
        val faceRef = FaceRef(ex1.id, FaceRole.TOP)
        val ex2 = doc.addExtrude("BisFlaeche", sk2, 10.0, op = BodyOp.INTERSECT, untilFace = faceRef)
        val rev = doc.addRevolve("DrehSchnitt", sk2, RevolveAxis.SketchY, 180.0, op = BodyOp.INTERSECT)
        doc.setParameters(listOf(Parameter("d2", "7.5")))
        doc.bind(ValueRef.FeatureValue(ex1.id, 1), "d2")

        val encoded = DocumentCodec.encode(doc)
        val back = DocumentCodec.decode(encoded)
        assertSameDocument(doc, back)
        val decodedEx1 = back.feature(ex1.id) as ExtrudeFeature
        assertEquals(5.0, decodedEx1.distance2)
        val decodedEx2 = back.feature(ex2.id) as ExtrudeFeature
        assertEquals(BodyOp.INTERSECT, decodedEx2.op)
        assertEquals(faceRef, decodedEx2.untilFace)
        assertEquals(null, decodedEx2.distance2)
        val decodedRev = back.feature(rev.id) as RevolveFeature
        assertEquals(BodyOp.INTERSECT, decodedRev.op)
        assertEquals("d2", back.bindings[ValueRef.FeatureValue(ex1.id, 1)])
    }

    @Test
    fun loadedDocumentContinuesWithFreshFeatureIds() {
        val back = DocumentCodec.decode(DocumentCodec.encode(sample()))
        val sk = back.addSketch("Neu")
        assertTrue(back.features.map { it.id }.distinct().size == back.features.size)
        assertEquals(5, sk.id.value)
    }

    @Test
    fun emptyDocumentRoundTrips() {
        val back = DocumentCodec.decode(DocumentCodec.encode(Document()))
        assertEquals(0, back.features.size)
    }

    @Test
    fun filesFromBeforeTheRenameStayReadable() {
        for (name in listOf("androidcad-model", "partwright-model", "paramworker-model")) {
            val old = DocumentCodec.encode(sample()).replace("axiswright-model", name)
            assertTrue(DocumentCodec.looksLikeModel(old), name)
            val decoded = DocumentCodec.decode(old)
            assertSameDocument(sample(), decoded)
            // Nach dem erneuten Speichern steht das neue Format drin
            val reSaved = DocumentCodec.encode(decoded)
            assertTrue(reSaved.contains("\"format\": \"axiswright-model\""))
        }
    }

    @Test
    fun fileIsHumanReadableAndVersioned() {
        val text = DocumentCodec.encode(sample())
        assertTrue(text.contains("\"format\": \"axiswright-model\""))
        assertTrue(DocumentCodec.looksLikeModel(text))
        assertTrue(!DocumentCodec.looksLikeModel("cube(10); // format"))
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(text.contains("\"kind\": \"extrude\""))
    }

    private fun mutated(mutate: (MutableMap<String, Any?>) -> Unit): String {
        val m = LinkedHashMap(Json.parse(DocumentCodec.encode(sample())).asObject())
        mutate(m)
        return Json.write(m)
    }

    @Suppress("UNCHECKED_CAST")
    private fun features(m: Map<String, Any?>) = (m["features"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }.toMutableList()

    @Test
    fun rejectsBrokenOrForeignFiles() {
        val cases: Map<String, (MutableMap<String, Any?>) -> Unit> = mapOf(
            "falsches Format" to { m -> m["format"] = "etwas-anderes" },
            "neuere Version" to { m -> m["version"] = 99 },
            "Extrusion vor ihrer Skizze" to { m -> m["features"] = features(m).let { it.add(0, it.removeAt(2)); it } },
            "Extrusion ohne Skizze" to { m -> m["features"] = features(m).also { f -> f[2]["sketch"] = 4 } },
            "doppelte Feature-ID" to { m -> m["features"] = features(m).also { f -> f[1]["id"] = 1 } },
            "nextId zu klein" to { m -> m["nextId"] = 2 },
            "unbekannte Operation" to { m -> m["features"] = features(m).also { f -> f[2]["op"] = "DRILL" } },
            "unbekannter Feature-Typ" to { m -> m["features"] = features(m).also { f -> f[2]["kind"] = "loft" } },
            "Nullvektor als Normale" to { m -> m["features"] = features(m).also { f -> f[0]["plane"] = mapOf("origin" to listOf(0, 0, 0), "xDir" to listOf(1, 0, 0), "normal" to listOf(0, 0, 0)) } },
            "Auswahlpunkt falsch" to { m -> m["features"] = features(m).also { f -> f[2]["regionPoints"] = listOf(listOf(1)) } },
            "Extrusion distance2 null" to { m -> m["features"] = features(m).also { f -> f[2]["distance2"] = 0.0 } },
            "Extrusion distance2 negativ" to { m -> m["features"] = features(m).also { f -> f[2]["distance2"] = -5.0 } },
            "Extrusion untilFace mit symmetric" to { m -> m["features"] = features(m).also { f -> f[2]["untilFace"] = listOf(2, 1, 0); f[2]["symmetric"] = true } },
            "Extrusion untilFace mit distance2" to { m -> m["features"] = features(m).also { f -> f[2]["untilFace"] = listOf(2, 1, 0); f[2]["distance2"] = 10.0 } },
            "Extrusion untilFace mit throughAll" to { m -> m["features"] = features(m).also { f -> f[2]["untilFace"] = listOf(2, 1, 0); f[2]["throughAll"] = true } },
            "Extrusion untilFace nicht davor" to { m -> m["features"] = features(m).also { f -> f[2]["untilFace"] = listOf(99, 1, 0) } },
        )
        for ((name, mutate) in cases) assertFailsWith<JsonException>("sollte scheitern: $name") { DocumentCodec.decode(mutated(mutate)) }
        assertFailsWith<JsonException> { DocumentCodec.decode("kein json") }
        assertFailsWith<JsonException> { DocumentCodec.decode("[]") }
    }
}
