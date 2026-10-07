package dev.androidcad.model

import dev.androidcad.json.JsonException
import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.RadiusDim
import dev.androidcad.sketch.Vec2
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Modell der projizierten Kanten ohne Geometriekern: Anlegen, Speichern/Laden, Kaskade, Aktualisieren. */
class ProjectionModelTest {
    private class Fixture {
        val doc = Document()
        val base = doc.addSketch("Basis")
        val ext = doc.addExtrude("Block", base, 10.0)
        val top = FaceRef(ext.id, FaceRole.TOP)
        val side = FaceRef(ext.id, FaceRole.SIDE, 1)
        val onTop = doc.addSketchOnFace("Aufsatz", top, Workplane.XY.offset(10.0))
        val lineEdge = EdgeRef.of(top, side)
        val circleEdge = EdgeRef.of(top, FaceRef(ext.id, FaceRole.SIDE, 2))
        val line = EdgeProjection.Geometry(lineEdge, line = Vec2(0.0, 0.0) to Vec2(40.0, 0.0))
        val circle = EdgeProjection.Geometry(circleEdge, circle = Vec2(10.0, 10.0) to 3.0)
    }

    @Test
    fun addingProjectionsCreatesFixedConstructionElementsOnce() {
        val f = Fixture()
        assertEquals(2, f.doc.addProjections(f.onTop.id, listOf(f.line, f.circle)))
        val sk = f.doc.sketch(f.onTop.id)
        val s = sk.sketch
        val projLine = sk.projections.single { it.line != null }.line!!
        assertTrue(s.line(projLine).construction)
        // beide Endpunkte fest
        val ends = listOf(s.line(projLine).p1, s.line(projLine).p2)
        assertTrue(ends.all { p -> s.constraintEntries.any { (it.constraint as? Fixed)?.point == p } })
        val projCircle = sk.projections.single { it.circle != null }.circle!!
        assertTrue(s.circle(projCircle).construction)
        assertTrue(s.constraintEntries.any { (it.constraint as? RadiusDim)?.round == projCircle })
        assertEquals(0, f.doc.addProjections(f.onTop.id, listOf(f.line, f.circle)), "keine Doppelten")
        assertEquals(2, f.doc.sketch(f.onTop.id).projections.size)
    }

    @Test
    fun updateMovesTheElementsAndReportsMissingOnes() {
        val f = Fixture()
        f.doc.addProjections(f.onTop.id, listOf(f.line, f.circle))
        val sk = f.doc.sketch(f.onTop.id)
        val (pl, pc) = sk.projections.partition { it.line != null }.let { it.first.single() to it.second.single() }
        assertTrue(EdgeProjection.update(sk.sketch, pl, EdgeProjection.Geometry(f.lineEdge, line = Vec2(0.0, 0.0) to Vec2(60.0, 0.0))))
        assertEquals(Vec2(60.0, 0.0), sk.sketch.point(sk.sketch.line(pl.line!!).p2))
        assertTrue(EdgeProjection.update(sk.sketch, pc, EdgeProjection.Geometry(f.circleEdge, circle = Vec2(12.0, 8.0) to 5.0)))
        assertEquals(5.0, sk.sketch.circle(pc.circle!!).radius)
        assertEquals(Vec2(12.0, 8.0), sk.sketch.point(sk.sketch.circle(pc.circle!!).center))
        // Art stimmt nicht (Kreis statt Linie) → false
        assertFalse(EdgeProjection.update(sk.sketch, pl, f.circle))
        // gelöschtes Element → false
        sk.sketch.remove(pl.line!!)
        assertFalse(EdgeProjection.update(sk.sketch, pl, f.line))
    }

    @Test
    fun applyProjectionsOnlyReportsRealChanges() {
        val f = Fixture()
        f.doc.addProjections(f.onTop.id, listOf(f.line, f.circle))
        val p = f.doc.sketch(f.onTop.id).projections
        val same = mapOf(f.onTop.id to listOf(p[0] to f.line, p[1] to f.circle))
        assertFalse(f.doc.applyProjections(same))
        val moved = mapOf(f.onTop.id to listOf(p[0] to EdgeProjection.Geometry(f.lineEdge, line = Vec2(0.0, 0.0) to Vec2(45.0, 0.0)), p[1] to f.circle))
        assertTrue(f.doc.applyProjections(moved))
        assertFalse(f.doc.applyProjections(moved))
    }

    @Test
    fun deletedElementsAreForgottenOnApply() {
        val f = Fixture()
        f.doc.addProjections(f.onTop.id, listOf(f.line, f.circle))
        val sk = f.doc.sketch(f.onTop.id)
        val p = sk.projections
        sk.sketch.remove(p[0].line!!) // Nutzer löscht die projizierte Linie
        assertTrue(f.doc.applyProjections(mapOf(f.onTop.id to listOf(p[1] to f.circle))))
        assertEquals(listOf(p[1]), f.doc.sketch(f.onTop.id).projections)
        assertFalse(f.doc.applyProjections(mapOf(f.onTop.id to listOf(p[1] to f.circle))))
        // gelöschte Kante lässt sich erneut projizieren
        assertEquals(1, f.doc.addProjections(f.onTop.id, listOf(f.line, f.circle)))
    }

    @Test
    fun projectionsRoundTripThroughJsonAndSurviveSnapshots() {
        val f = Fixture()
        f.doc.addProjections(f.onTop.id, listOf(f.line, f.circle))
        val loaded = DocumentCodec.decode(DocumentCodec.encode(f.doc))
        assertEquals(f.doc.sketch(f.onTop.id).projections, loaded.sketch(f.onTop.id).projections)
        assertEquals(f.doc.sketch(f.onTop.id).projections, f.doc.snapshot().sketch(f.onTop.id).projections)
        // alte Dateien ohne das Feld bleiben lesbar
        val plain = Fixture()
        assertTrue(DocumentCodec.decode(DocumentCodec.encode(plain.doc)).sketch(plain.onTop.id).projections.isEmpty())
    }

    @Test
    fun invalidProjectionsAreRejectedOnLoad() {
        val f = Fixture()
        f.doc.addProjections(f.onTop.id, listOf(f.line))
        // weder Linie noch Kreis
        val neither = DocumentCodec.toJson(f.doc)
        @Suppress("UNCHECKED_CAST")
        val withProjection = (neither["features"] as List<MutableMap<String, Any?>>).first { "projections" in it }
        @Suppress("UNCHECKED_CAST")
        (withProjection["projections"] as List<MutableMap<String, Any?>>)[0]["line"] = null
        assertFailsWith<JsonException> { DocumentCodec.fromJson(neither) }
        // Verweis auf ein Feature, das nicht davor liegt
        val bad = Document()
        val sk = bad.addSketch("A")
        val json = DocumentCodec.toJson(bad)
        @Suppress("UNCHECKED_CAST")
        val features = (json["features"] as List<MutableMap<String, Any?>>)
        features[0]["projections"] = listOf(mapOf("a" to listOf(99, 1, 0), "b" to listOf(98, 1, 0), "line" to 2, "circle" to null))
        assertFailsWith<JsonException> { DocumentCodec.fromJson(json) }
        assertEquals("A", sk.name)
    }

    @Test
    fun removingAReferencedFeatureDropsTheProjectionButKeepsTheSketch() {
        val f = Fixture()
        // zweites Feature, dessen Fläche projiziert wird
        val other = f.doc.addSketch("Zweite")
        val otherExt = f.doc.addExtrude("Zweiter Block", other, 3.0)
        val sk = f.doc.addSketchOnFace("Auf Zweitem", FaceRef(otherExt.id, FaceRole.TOP), Workplane.XY.offset(3.0))
        val mixed = EdgeProjection.Geometry(EdgeRef.of(FaceRef(otherExt.id, FaceRole.TOP), f.side), line = Vec2(0.0, 0.0) to Vec2(1.0, 0.0))
        f.doc.addProjections(sk.id, listOf(mixed))
        assertEquals(1, f.doc.sketch(sk.id).projections.size)
        // das Feature der anderen Fläche (Block) entfernen: Skizze bleibt, Projektion fällt weg, Element bleibt
        f.doc.remove(f.ext.id)
        val after = f.doc.sketch(sk.id)
        assertTrue(after.projections.isEmpty())
        assertEquals(1, after.sketch.lineIds.size)
    }
}
