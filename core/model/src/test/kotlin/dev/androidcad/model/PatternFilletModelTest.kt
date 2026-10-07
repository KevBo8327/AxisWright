package dev.androidcad.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Welche Verrundungen ein Muster mitnimmt, und dass die Option gespeichert wird – ohne Geometriekern. */
class PatternFilletModelTest {
    private class Fixture {
        val doc = Document()
        val base = doc.addSketch("Platte")
        val plate = doc.addExtrude("Platte", base, 8.0)
        val bossSketch = doc.addSketchOnFace("Zapfen", FaceRef(plate.id, FaceRole.TOP), Workplane.XY.offset(8.0))
        val boss = doc.addExtrude("Zapfen", bossSketch, 5.0)
        val bossTop = FaceRef(boss.id, FaceRole.TOP)
        val bossSide = FaceRef(boss.id, FaceRole.SIDE, 7)
        val plateTop = FaceRef(plate.id, FaceRole.TOP)
        // an der Quelle: Oberkante des Zapfens (Zapfen/Zapfen) und Fuß (Zapfen/Platte)
        val round = doc.addFillet("Rundung", listOf(EdgeRef.of(bossTop, bossSide)), 1.0)
        val foot = doc.addFillet("Hohlkehle", listOf(EdgeRef.of(plateTop, bossSide)), 0.5)
        // nicht an der Quelle: Kante der Platte
        val plateEdge = doc.addFillet("Plattenkante", listOf(EdgeRef.of(plateTop, FaceRef(plate.id, FaceRole.SIDE, 3))), 2.0)
    }

    @Test
    fun onlyFilletsOfTheSourceBetweenSourceAndPatternAreTaken() {
        val f = Fixture()
        val p = f.doc.addPattern("Reihe", f.boss.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0), withFillets = true)
        // nach dem Muster angelegt: gehört nicht dazu
        f.doc.addFillet("Später", listOf(EdgeRef.of(f.bossTop, f.bossSide)), 0.3)
        assertEquals(listOf(f.round.id, f.foot.id), f.doc.patternFillets(p).map { it.id })
        assertEquals(f.boss.id, f.doc.patternRoot(p))
    }

    @Test
    fun aNestedPatternTakesTheFilletsOfTheOriginalFeature() {
        val f = Fixture()
        val row = f.doc.addPattern("Reihe", f.boss.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0), withFillets = true)
        // zwischen innerem und äußerem Muster: nicht für das Raster (würde sonst nur am Original doppelt wirken)
        f.doc.addFillet("Zwischendurch", listOf(EdgeRef.of(f.bossTop, f.bossSide)), 0.2)
        val grid = f.doc.addPattern("Raster", row.id, PatternKind.Linear(Vec3(0.0, 1.0, 0.0), 2, 20.0), withFillets = true)
        assertEquals(f.boss.id, f.doc.patternRoot(grid))
        assertEquals(listOf(f.round.id, f.foot.id), f.doc.patternFillets(grid).map { it.id })
        assertTrue(f.doc.patternFillets(PatternFeature(FeatureId(999), "x", FeatureId(998), PatternKind.Mirror(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)))).isEmpty())
    }

    @Test
    fun theOptionRoundTripsAndOldFilesStayWithout() {
        val f = Fixture()
        val with = f.doc.addPattern("Mit", f.boss.id, PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 2, 20.0), withFillets = true)
        val without = f.doc.addPattern("Ohne", f.boss.id, PatternKind.Linear(Vec3(0.0, 1.0, 0.0), 2, 20.0))
        val loaded = DocumentCodec.decode(DocumentCodec.encode(f.doc))
        assertTrue((loaded.feature(with.id) as PatternFeature).withFillets)
        assertFalse((loaded.feature(without.id) as PatternFeature).withFillets)
        // die Option steht nur in der Datei, wenn sie an ist (ältere Dateien kennen sie nicht)
        assertFalse(DocumentCodec.encode(f.doc).substringAfter("\"Ohne\"").substringBefore("}").contains("withFillets"))
    }
}
