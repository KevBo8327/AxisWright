package dev.androidcad.model

import dev.androidcad.json.JsonException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShellModelTest {
    private fun doc(): Pair<Document, ShellFeature> {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        sk.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val box = doc.addExtrude("Block", sk, 20.0)
        return doc to doc.addShell("Schale", listOf(FaceRef(box.id, FaceRole.TOP)), 2.0)
    }

    @Test
    fun shellRoundTripsAndFallsWithItsBody() {
        val (doc, shell) = doc()
        val back = DocumentCodec.decode(DocumentCodec.encode(doc))
        assertEquals(shell, back.features.last())
        back.remove(back.features.first { it is ExtrudeFeature }.id)
        assertTrue(back.features.none { it is ShellFeature }, "ohne den Körper fällt die Schale weg")
    }

    @Test
    fun shellWithoutOpeningIsRejected() {
        val (doc, _) = doc()
        assertFailsWith<IllegalArgumentException> { doc.addShell("leer", emptyList(), 2.0) }
        val text = DocumentCodec.encode(doc).replace(Regex(""""openFaces":\s*\[[^\]]*\]\s*\]"""), "\"openFaces\": []")
        assertFailsWith<JsonException> { DocumentCodec.decode(text) }
    }

    @Test
    fun innerNamesAreStableAndFitTheNameRange() {
        val tag = FaceRef(FeatureId(3), FaceRole.SIDE, 7).tag
        assertEquals(ShellFeature.innerElement(tag), ShellFeature.innerElement(tag))
        assertTrue(ShellFeature.innerElement(tag) in 0..FaceRef.ELEMENT_MASK)
        assertTrue(ShellFeature.innerElement(tag) != ShellFeature.innerElement(FaceRef(FeatureId(3), FaceRole.SIDE, 8).tag))
    }
}
