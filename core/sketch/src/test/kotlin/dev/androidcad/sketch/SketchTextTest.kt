package dev.androidcad.sketch

import dev.androidcad.json.Json
import dev.androidcad.json.asObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SketchTextTest {
    @Test
    fun addAndModifyText() {
        val s = Sketch()
        val p = s.addPoint(10.0, 20.0)
        val id = s.addText(p, "Hallo", 5.0, 45.0)

        assertTrue(s.exists(id))
        assertEquals("Hallo", s.text(id).text)
        assertEquals(5.0, s.text(id).height)
        assertEquals(45.0, s.text(id).angle)
        assertEquals(p, s.text(id).location)
        assertFalse(s.text(id).construction)

        s.setText(id, "Welt", 8.0, 90.0)
        assertEquals("Welt", s.text(id).text)
        assertEquals(8.0, s.text(id).height)
        assertEquals(90.0, s.text(id).angle)

        s.setConstruction(id, true)
        assertTrue(s.text(id).construction)

        s.removeOrphanPoints()
        assertTrue(s.exists(p), "Basispunkt des Texts bleibt erhalten")

        s.remove(id)
        assertFalse(s.exists(id))
        s.removeOrphanPoints()
        assertFalse(s.exists(p), "Nach Löschen des Texts wird der Punkt als Waise entfernt")
    }

    @Test
    fun removingPointRemovesText() {
        val s = Sketch()
        val p = s.addPoint(5.0, 5.0)
        val id = s.addText(p, "ABC", 10.0)
        assertTrue(s.exists(id))
        s.remove(p)
        assertFalse(s.exists(id))
    }

    @Test
    fun copyAndRestore() {
        val s = Sketch()
        val id = s.addText(1.0, 2.0, "Kopie", 12.0, 15.0)
        val copy = s.copy()
        assertTrue(copy.exists(id))
        assertEquals("Kopie", copy.text(id).text)

        s.setText(id, "Geändert", 15.0, 30.0)
        assertEquals("Geändert", s.text(id).text)

        s.restore(copy)
        assertEquals("Kopie", s.text(id).text)
    }

    @Test
    fun codecRoundTripAndBackwardCompatibility() {
        val s = Sketch()
        val t1 = s.addText(10.0, 20.0, "ParamWorker", 10.0, 0.0)
        val t2 = s.addText(30.0, 40.0, "CAD", 6.0, 45.0, construction = true)

        val json = SketchCodec.toJson(s)
        val loaded = SketchCodec.fromJson(json)

        assertEquals(s.textIds, loaded.textIds)
        assertEquals(s.text(t1), loaded.text(t1))
        assertEquals(s.text(t2), loaded.text(t2))

        // Ältere Dateien ohne "texts"
        val oldJson = LinkedHashMap(json).apply { remove("texts") }
        val loadedOld = SketchCodec.fromJson(oldJson)
        assertTrue(loadedOld.textIds.isEmpty(), "Alte Dateien ohne Texte laden fehlerfrei")
    }

    @Test
    fun regionsWithTextSingleLetter() {
        val s = Sketch()
        s.addText(0.0, 0.0, "I", 10.0)

        // Mock: "I" als Rechteck 2x10
        val mockOutlines = TextOutlines { text, height ->
            when (text) {
                "I" -> listOf(rectLoop(0.0, 0.0, 2.0, height))
                else -> emptyList()
            }
        }

        val res = s.regions(textOutlines = mockOutlines)
        assertEquals(1, res.regions.size)
        assertEquals(20.0, res.regions[0].area, 1e-6)
        assertTrue(res.regions[0].holes.isEmpty())
    }

    @Test
    fun regionsWithTextLetterWithHole() {
        val s = Sketch()
        s.addText(0.0, 0.0, "O", 10.0)

        // Mock: "O" als Außenrechteck 10x10, Innenrechteck 6x6 (Loch)
        val mockOutlines = TextOutlines { text, height ->
            when (text) {
                "O" -> listOf(
                    rectLoop(0.0, 0.0, height, height),
                    rectLoop(2.0, 2.0, height - 2.0, height - 2.0),
                )
                else -> emptyList()
            }
        }

        val res = s.regions(textOutlines = mockOutlines)
        assertEquals(1, res.regions.size)
        assertEquals(1, res.regions[0].holes.size)
        // Außen = 100, Loch = 36 -> Netto = 64
        assertEquals(64.0, res.regions[0].area, 1e-6)
    }

    @Test
    fun regionsWithTextLetterWithTwoHoles() {
        val s = Sketch()
        s.addText(0.0, 0.0, "8", 10.0)

        // Mock: "8" als Außenrechteck 6x10, zwei Innenlöcher (oben 2x3, unten 2x3)
        val mockOutlines = TextOutlines { text, height ->
            when (text) {
                "8" -> listOf(
                    rectLoop(0.0, 0.0, 6.0, 10.0), // Fläche 60
                    rectLoop(2.0, 6.0, 4.0, 9.0),  // Fläche 2x3 = 6
                    rectLoop(2.0, 1.0, 4.0, 4.0),   // Fläche 2x3 = 6
                )
                else -> emptyList()
            }
        }

        val res = s.regions(textOutlines = mockOutlines)
        assertEquals(1, res.regions.size)
        assertEquals(2, res.regions[0].holes.size)
        assertEquals(48.0, res.regions[0].area, 1e-6)
    }

    @Test
    fun regionsWithTextRotationAndTranslation() {
        val s = Sketch()
        // Platziert bei (100, 50), gedreht um 90 Grad
        s.addText(100.0, 50.0, "I", 10.0, angle = 90.0)

        val mockOutlines = TextOutlines { text, height ->
            listOf(rectLoop(0.0, 0.0, 2.0, height))
        }

        val res = s.regions(textOutlines = mockOutlines)
        assertEquals(1, res.regions.size)
        assertEquals(20.0, res.regions[0].area, 1e-6)

        // Bei 90 Grad Drehung: (0,0)->(100, 50), (2,0)->(100, 52), (2,10)->(90, 52), (0,10)->(90, 50)
        // Punkt (95, 51) muss in der Region liegen
        assertTrue(res.regions[0].contains(95.0, 51.0))
        assertFalse(res.regions[0].contains(105.0, 51.0))
    }

    @Test
    fun textInsidePlateYieldsHoleAndIsland() {
        val s = Sketch()
        // Grundplatte 40x40
        s.addRectangle(0.0, 0.0, 40.0, 40.0)
        // Buchstabe "O" innen bei (10, 10)
        s.addText(10.0, 10.0, "O", 10.0)

        val mockOutlines = TextOutlines { text, height ->
            listOf(
                rectLoop(0.0, 0.0, height, height),
                rectLoop(2.0, 2.0, height - 2.0, height - 2.0),
            )
        }

        val res = s.regions(textOutlines = mockOutlines)
        // Es gibt 2 Regionen:
        // 1. Die Platte mit dem Buchstaben-Außenumriss als Loch (Fläche 1600 - 100 = 1500)
        // 2. Die Insel im Inneren des O (Fläche 36)
        assertEquals(2, res.regions.size)
        val plate = res.regions.first { it.area > 1000 }
        val island = res.regions.first { it.area < 100 }

        assertEquals(1, plate.holes.size)
        assertEquals(1500.0, plate.area, 1e-6)
        assertEquals(36.0, island.area, 1e-6)
    }

    private fun rectLoop(x1: Double, y1: Double, x2: Double, y2: Double): Loop {
        val segs = listOf(
            LoopSegment.Line(x1, y1, x2, y1),
            LoopSegment.Line(x2, y1, x2, y2),
            LoopSegment.Line(x2, y2, x1, y2),
            LoopSegment.Line(x1, y2, x1, y1),
        )
        val area = kotlin.math.abs((x2 - x1) * (y2 - y1))
        return Loop(segs, area)
    }
}
