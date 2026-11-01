package dev.androidcad.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Linearmuster mit zweiter Richtung (Raster) – Lage, Namen, Prüfungen und Speichern, ohne Geometriekern. */
class TwoWayPatternModelTest {
    private val x = Vec3(1.0, 0.0, 0.0)
    private val y = Vec3(0.0, 1.0, 0.0)
    private val grid = PatternKind.Linear(x, 3, 20.0, y, 2, 15.0)

    @Test
    fun placementsCoverTheGridRowByRowWithoutTheOriginal() {
        assertEquals(5, grid.copies)
        val moves = PatternGeometry.placements(grid).map { (it as Placement.Translate).let { t -> t.dx to t.dy } }
        assertEquals(listOf(20.0 to 0.0, 40.0 to 0.0, 0.0 to 15.0, 20.0 to 15.0, 40.0 to 15.0), moves)
        // ohne zweite Richtung wie bisher
        assertEquals(2, PatternKind.Linear(x, 3, 20.0).copies)
        assertEquals(2, PatternGeometry.placements(PatternKind.Linear(x, 3, 20.0, count2 = 5)).size)
    }

    @Test
    fun firstRowKeepsTheNamesOfASimpleRowAndOtherRowsDoNotCollide() {
        val ids = PatternGeometry.instances(grid)
        assertEquals(listOf(1L, 2L, 256L, 257L, 258L), ids)
        assertEquals(PatternGeometry.instances(PatternKind.Linear(x, 3, 20.0)), ids.take(2))
        // mehr Zeilen: die vorhandenen Kopien behalten ihre Nummer
        assertEquals(ids, PatternGeometry.instances(grid.copy(count2 = 4)).take(5))
        val tag = FaceRef(FeatureId(2), FaceRole.SIDE, 1).tag
        val names = ids.map { FaceRef.fromTag(PatternGeometry.copyTag(tag, FeatureId(9), it))!!.element }
        assertEquals(names.size, names.toSet().size)
        assertEquals(PatternGeometry.nestedElement(1, 2, 1), names.last())
    }

    @Test
    fun invalidSecondDirectionsAreRejected() {
        assertNull(PatternGeometry.validate(grid))
        assertNotNull(PatternGeometry.validate(grid.copy(direction2 = Vec3(-2.0, 0.0, 0.0))), "parallel")
        assertNotNull(PatternGeometry.validate(grid.copy(count2 = 1)))
        assertNotNull(PatternGeometry.validate(grid.copy(spacing2 = 0.0)))
        assertNotNull(PatternGeometry.validate(grid.copy(count = 20, count2 = 11)), "mehr als 200 Exemplare")
        assertNull(PatternGeometry.validate(grid.copy(count = 20, count2 = 10)))
    }

    @Test
    fun gridsDoNotNest() {
        val doc = Document()
        val plate = doc.addExtrude("Platte", doc.addSketch("Platte"), 8.0)
        val g = doc.addPattern("Raster", plate.id, grid)
        val e = assertFailsWith<IllegalArgumentException> { doc.addPattern("Mehr", g.id, PatternKind.Linear(Vec3(0.0, 0.0, 1.0), 2, 10.0)) }
        assertEquals(ModelText.GRID_NOT_PATTERNABLE, (e as ModelException).msg.key)
        val row = doc.addPattern("Reihe", plate.id, PatternKind.Linear(x, 2, 30.0))
        assertFailsWith<IllegalArgumentException> { doc.addPattern("Raster von Reihen", row.id, grid) }
    }

    @Test
    fun theSecondDirectionRoundTripsAndOldFilesStayOneWay() {
        val doc = Document()
        val plate = doc.addExtrude("Platte", doc.addSketch("Platte"), 8.0)
        val g = doc.addPattern("Raster", plate.id, grid)
        val row = doc.addPattern("Reihe", plate.id, PatternKind.Linear(x, 4, 10.0))
        val text = DocumentCodec.encode(doc)
        val loaded = DocumentCodec.decode(text)
        assertEquals(grid, (loaded.feature(g.id) as PatternFeature).kind)
        assertEquals(PatternKind.Linear(x, 4, 10.0), (loaded.feature(row.id) as PatternFeature).kind)
        assertTrue(!text.substringAfter("\"Reihe\"").contains("direction2"))
    }
}
