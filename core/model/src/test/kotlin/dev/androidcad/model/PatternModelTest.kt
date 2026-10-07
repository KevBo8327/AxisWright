package dev.androidcad.model

import dev.androidcad.json.JsonException
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Muster und Spiegelung ohne Geometriekern: Geometrie, Validierung, Namen, Kaskade, Speichern/Laden. */
class PatternModelTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-9) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    private fun apply(m: DoubleArray, p: Vec3) = Vec3(
        m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3],
        m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7],
        m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11],
    )

    private val x = Vec3(1.0, 0.0, 0.0)
    private val z = Vec3(0.0, 0.0, 1.0)

    @Test
    fun linearPatternPlacesCopiesAtMultiplesOfTheSpacing() {
        val kind = PatternKind.Linear(Vec3(0.0, 2.0, 0.0), 4, 7.5) // Richtung wird normiert
        val p = PatternGeometry.placements(kind)
        assertEquals(3, p.size)
        p.forEachIndexed { i, pl ->
            pl as Placement.Translate
            near(0.0, pl.dx); near(7.5 * (i + 1), pl.dy); near(0.0, pl.dz)
        }
        // negativer Abstand = Gegenrichtung
        (PatternGeometry.placements(PatternKind.Linear(x, 2, -3.0)).single() as Placement.Translate).let { near(-3.0, it.dx) }
    }

    @Test
    fun circularPatternRotatesAboutTheAxisAndKeepsTheAxisFixed() {
        val kind = PatternKind.Circular(Vec3(10.0, 0.0, 0.0), z, 4)
        near(90.0, PatternGeometry.angleStep(kind)) // volle 360° gleichmäßig auf 4 Exemplare
        val m = (PatternGeometry.placements(kind)[0] as Placement.Rotate).matrix3x4
        val q = apply(m, Vec3(20.0, 0.0, 5.0)) // 10 mm von der Achse, 90° gegen den Uhrzeigersinn
        near(10.0, q.x, 1e-9); near(10.0, q.y, 1e-9); near(5.0, q.z, 1e-9)
        val onAxis = apply(m, Vec3(10.0, 0.0, 3.0))
        near(10.0, onAxis.x); near(0.0, onAxis.y); near(3.0, onAxis.z)
        // dritte Kopie = 270°
        val third = apply((PatternGeometry.placements(kind)[2] as Placement.Rotate).matrix3x4, Vec3(20.0, 0.0, 0.0))
        near(10.0, third.x, 1e-9); near(-10.0, third.y, 1e-9)
        // Teilkreis: das letzte Exemplar liegt genau bei 180°
        val half = PatternKind.Circular(Vec3(0.0, 0.0, 0.0), z, 3, 180.0)
        near(90.0, PatternGeometry.angleStep(half))
        val last = apply((PatternGeometry.placements(half).last() as Placement.Rotate).matrix3x4, x)
        near(-1.0, last.x, 1e-9); near(0.0, last.y, 1e-9)
    }

    @Test
    fun mirrorHasExactlyOneCopy() {
        val p = PatternGeometry.placements(PatternKind.Mirror(Vec3(5.0, 0.0, 0.0), x)).single() as Placement.Reflect
        assertEquals(Vec3(5.0, 0.0, 0.0), p.origin)
        assertEquals(1, PatternKind.Mirror(Vec3(0.0, 0.0, 0.0), x).copies)
    }

    @Test
    fun validationRejectsUnusableParameters() {
        assertNull(PatternGeometry.validate(PatternKind.Linear(x, 2, 5.0)))
        assertNotNull(PatternGeometry.validate(PatternKind.Linear(x, 1, 5.0)))
        assertNotNull(PatternGeometry.validate(PatternKind.Linear(x, PatternGeometry.MAX_COUNT + 1, 5.0)))
        assertNotNull(PatternGeometry.validate(PatternKind.Linear(x, 3, 0.0)))
        assertNotNull(PatternGeometry.validate(PatternKind.Linear(Vec3(0.0, 0.0, 0.0), 3, 5.0)))
        assertNotNull(PatternGeometry.validate(PatternKind.Linear(x, 3, Double.NaN)))
        assertNull(PatternGeometry.validate(PatternKind.Circular(Vec3(0.0, 0.0, 0.0), z, 6)))
        assertNotNull(PatternGeometry.validate(PatternKind.Circular(Vec3(0.0, 0.0, 0.0), z, 1)))
        assertNotNull(PatternGeometry.validate(PatternKind.Circular(Vec3(0.0, 0.0, 0.0), z, 4, 0.0)))
        assertNotNull(PatternGeometry.validate(PatternKind.Circular(Vec3(0.0, 0.0, 0.0), z, 4, 400.0)))
        assertNotNull(PatternGeometry.validate(PatternKind.Circular(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), 4)))
        assertNull(PatternGeometry.validate(PatternKind.Mirror(Vec3(0.0, 0.0, 0.0), x)))
        assertNotNull(PatternGeometry.validate(PatternKind.Mirror(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0))))
    }

    @Test
    fun copyTagsKeepRoleAndElementAndNameTheCopy() {
        val src = FeatureId(3); val pattern = FeatureId(9)
        val side = FaceRef(src, FaceRole.SIDE, 42)
        val first = FaceRef.fromTag(PatternGeometry.copyTag(side.tag, pattern, 1))!!
        assertEquals(pattern, first.feature); assertEquals(FaceRole.SIDE, first.role)
        assertEquals(PatternGeometry.copyElement(1, 42), first.element)
        val second = FaceRef.fromTag(PatternGeometry.copyTag(side.tag, pattern, 2))!!
        assertTrue(first != second && first.tag != second.tag)
        // Stirnflächen (Element 0) und unbenannte Flächen
        assertEquals(PatternGeometry.copyElement(1, 0), FaceRef.fromTag(PatternGeometry.copyTag(FaceRef(src, FaceRole.TOP).tag, pattern, 1))!!.element)
        assertEquals(0L, PatternGeometry.copyTag(0L, pattern, 1))
        // höchste erlaubte Kopienzahl bleibt eindeutig
        assertTrue(PatternGeometry.copyTag(side.tag, pattern, PatternGeometry.MAX_COUNT) != 0L)
    }

    private class Fixture {
        val doc = Document()
        val sketch = doc.addSketch("Basis")
        val extrude = doc.addExtrude("Block", sketch, 10.0)
        val boss = doc.addSketchOnFace("Aufsatz", FaceRef(extrude.id, FaceRole.TOP), Workplane.XY.offset(10.0))
        val bossExtrude = doc.addExtrude("Zapfen", boss, 5.0)
        val hole = doc.addHole("Bohrung", FaceRef(extrude.id, FaceRole.TOP), 5.0, 5.0, 10.0, 4.0)
    }

    @Test
    fun patternsCanOnlyDuplicateExtrusionsAndHoles() {
        val f = Fixture()
        f.doc.addPattern("Muster", f.bossExtrude.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 10.0))
        f.doc.addPattern("Muster 2", f.hole.id, PatternKind.Mirror(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)))
        assertFailsWith<IllegalArgumentException> { f.doc.addPattern("x", f.sketch.id, PatternKind.Mirror(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))) }
        assertFailsWith<IllegalArgumentException> { f.doc.addPattern("x", FeatureId(999), PatternKind.Mirror(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))) }
        assertFailsWith<IllegalArgumentException> { f.doc.addPattern("x", f.hole.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 1, 10.0)) }
        // ein einfaches Muster lässt sich vervielfältigen (Muster von Mustern), ein verschachteltes nicht mehr
        val p = f.doc.features.filterIsInstance<PatternFeature>().first()
        val nested = f.doc.addPattern("Raster", p.id, PatternKind.Linear(Vec3(0.0, 1.0, 0.0), 2, 15.0))
        assertFailsWith<IllegalArgumentException> { f.doc.addPattern("x", nested.id, PatternKind.Mirror(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))) }
    }

    @Test
    fun nestedCopyTagsCarryOuterAndInnerCopyAndStayDistinct() {
        val src = FeatureId(3); val inner = FeatureId(5); val outer = FeatureId(9)
        val e = 42L
        // Flächen im Werkzeug des inneren Musters: Original (Feature 3) und innere Kopie 2 (Feature 5)
        val fromOriginal = FaceRef(src, FaceRole.SIDE, e).tag
        val fromInnerCopy = FaceRef.fromTag(PatternGeometry.copyTag(fromOriginal, inner, 2))!!.tag
        val a = FaceRef.fromTag(PatternGeometry.nestedCopyTag(fromOriginal, outer, 1))!!
        val b = FaceRef.fromTag(PatternGeometry.nestedCopyTag(fromInnerCopy, outer, 1))!!
        val c = FaceRef.fromTag(PatternGeometry.nestedCopyTag(fromInnerCopy, outer, 3))!!
        assertEquals(outer, a.feature); assertEquals(FaceRole.SIDE, a.role)
        assertEquals(PatternGeometry.nestedElement(1, 0, e), a.element)
        assertEquals(PatternGeometry.nestedElement(1, 2, e), b.element)
        assertEquals(PatternGeometry.nestedElement(3, 2, e), c.element)
        assertEquals(3, setOf(a, b, c).size)
        // höchste Kopienzahlen passen in den 40-Bit-Namen
        val big = FaceRef.fromTag(PatternGeometry.copyTag(FaceRef(src, FaceRole.SIDE, (1L shl 20) - 1).tag, inner, PatternGeometry.MAX_COUNT))!!.tag
        assertEquals(PatternGeometry.nestedElement(PatternGeometry.MAX_COUNT, PatternGeometry.MAX_COUNT, (1L shl 20) - 1), FaceRef.fromTag(PatternGeometry.nestedCopyTag(big, outer, PatternGeometry.MAX_COUNT))!!.element)
        // ein schon verschachtelter Name lässt sich nicht noch einmal verschachteln
        assertFailsWith<IllegalArgumentException> { PatternGeometry.nestedCopyTag(FaceRef(outer, FaceRole.SIDE, PatternGeometry.nestedElement(2, 1, e)).tag, FeatureId(11), 1) }
    }

    @Test
    fun removingTheInnerPatternRemovesTheOuterAndLargeElementsRoundTrip() {
        val f = Fixture()
        val row = f.doc.addPattern("Reihe", f.hole.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 10.0))
        val grid = f.doc.addPattern("Raster", row.id, PatternKind.Linear(Vec3(0.0, 1.0, 0.0), 2, 10.0))
        // Verrundung an einer Kante einer verschachtelten Kopie: großer Elementwert muss Speichern/Laden überstehen
        val top = FaceRef(f.extrude.id, FaceRole.TOP)
        val wall = FaceRef(grid.id, FaceRole.SIDE, PatternGeometry.nestedElement(1, 2, 1))
        val fillet = f.doc.addFillet("Fase", listOf(EdgeRef.of(top, wall)), 0.5, EdgeKind.CHAMFER)
        val loaded = DocumentCodec.decode(DocumentCodec.encode(f.doc))
        assertEquals(listOf(EdgeRef.of(top, wall)), (loaded.feature(fillet.id) as FilletFeature).edges)
        f.doc.remove(row.id)
        assertTrue(f.doc.features.none { it.id == row.id || it.id == grid.id || it.id == fillet.id })
        assertTrue(f.doc.features.any { it.id == f.hole.id })
    }

    @Test
    fun removingTheSourceRemovesThePatternAndWhatReferencesItsFaces() {
        val f = Fixture()
        val pattern = f.doc.addPattern("Muster", f.bossExtrude.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 10.0))
        // Verrundung an einer Kante der ersten Kopie des Musters
        val top = FaceRef(pattern.id, FaceRole.TOP, 1 shl PatternGeometry.INSTANCE_SHIFT)
        val side = FaceRef(pattern.id, FaceRole.SIDE, (1 shl PatternGeometry.INSTANCE_SHIFT) or 7)
        val fillet = f.doc.addFillet("Rundung", listOf(EdgeRef.of(top, side)), 0.5)
        f.doc.remove(f.bossExtrude.id)
        assertTrue(f.doc.features.none { it.id == pattern.id || it.id == fillet.id })
        assertTrue(f.doc.features.any { it.id == f.hole.id }, "unabhängige Features bleiben")
    }

    @Test
    fun patternsRoundTripThroughJsonAndRename() {
        val f = Fixture()
        val kinds = listOf(
            PatternKind.Linear(Vec3(1.0, 0.5, 0.0), 4, 12.5),
            PatternKind.Circular(Vec3(10.0, 20.0, 0.0), Vec3(0.0, 0.0, 1.0), 6, 270.0),
            PatternKind.Mirror(Vec3(40.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)),
        )
        val added = kinds.mapIndexed { i, k -> f.doc.addPattern("Muster $i", if (i == 1) f.hole.id else f.bossExtrude.id, k) }
        f.doc.rename(added[0].id, "Umbenannt")
        val loaded = DocumentCodec.decode(DocumentCodec.encode(f.doc))
        val patterns = loaded.features.filterIsInstance<PatternFeature>()
        assertEquals(3, patterns.size)
        assertEquals("Umbenannt", patterns[0].name)
        assertEquals(kinds, patterns.map { it.kind })
        assertEquals(added.map { it.source }, patterns.map { it.source })
    }

    @Test
    fun invalidPatternsAreRejectedOnLoad() {
        val f = Fixture()
        f.doc.addPattern("Muster", f.bossExtrude.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 10.0))
        fun json() = DocumentCodec.toJson(f.doc)
        @Suppress("UNCHECKED_CAST")
        fun pattern(j: Map<String, Any?>) = (j["features"] as List<MutableMap<String, Any?>>).first { it["kind"] == "pattern" }

        val badCount = json().also { pattern(it)["count"] = 1 }
        assertFailsWith<JsonException> { DocumentCodec.fromJson(badCount) }
        val badType = json().also { pattern(it)["type"] = "spiral" }
        assertFailsWith<JsonException> { DocumentCodec.fromJson(badType) }
        val badSource = json().also { pattern(it)["source"] = f.sketch.id.value } // eine Skizze
        assertFailsWith<JsonException> { DocumentCodec.fromJson(badSource) }
        val missingSource = json().also { pattern(it)["source"] = 999 }
        assertFailsWith<JsonException> { DocumentCodec.fromJson(missingSource) }
    }
}
