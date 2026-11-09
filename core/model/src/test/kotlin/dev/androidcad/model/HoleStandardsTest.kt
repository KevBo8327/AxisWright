package dev.androidcad.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HoleStandardsTest {
    @Test
    fun standardTableContainsAllRequiredSizesM3ToM10() {
        assertEquals(listOf(HoleStandardSize.M3, HoleStandardSize.M4, HoleStandardSize.M5, HoleStandardSize.M6, HoleStandardSize.M8, HoleStandardSize.M10), HoleStandards.ENTRIES.map { it.size })
    }

    @Test
    fun throughHolesMatchIso273Medium() {
        // ISO 273 Reihe mittel
        assertEquals(3.4, HoleStandards.forSize(HoleStandardSize.M3).diameter)
        assertEquals(4.5, HoleStandards.forSize(HoleStandardSize.M4).diameter)
        assertEquals(5.5, HoleStandards.forSize(HoleStandardSize.M5).diameter)
        assertEquals(6.6, HoleStandards.forSize(HoleStandardSize.M6).diameter)
        assertEquals(9.0, HoleStandards.forSize(HoleStandardSize.M8).diameter)
        assertEquals(11.0, HoleStandards.forSize(HoleStandardSize.M10).diameter)
    }

    @Test
    fun counterboresMatchDin974Part1() {
        // DIN 974-1 für Zylinderschrauben ISO 4762
        val m3 = HoleStandards.forSize(HoleStandardSize.M3)
        assertEquals(6.0, m3.counterboreDiameter); assertEquals(3.4, m3.counterboreDepth)

        val m4 = HoleStandards.forSize(HoleStandardSize.M4)
        assertEquals(8.0, m4.counterboreDiameter); assertEquals(4.4, m4.counterboreDepth)

        val m5 = HoleStandards.forSize(HoleStandardSize.M5)
        assertEquals(10.0, m5.counterboreDiameter); assertEquals(5.4, m5.counterboreDepth)

        val m6 = HoleStandards.forSize(HoleStandardSize.M6)
        assertEquals(11.0, m6.counterboreDiameter); assertEquals(6.4, m6.counterboreDepth)

        val m8 = HoleStandards.forSize(HoleStandardSize.M8)
        assertEquals(15.0, m8.counterboreDiameter); assertEquals(8.6, m8.counterboreDepth)

        val m10 = HoleStandards.forSize(HoleStandardSize.M10)
        assertEquals(18.0, m10.counterboreDiameter); assertEquals(10.6, m10.counterboreDepth)
    }

    @Test
    fun countersinksMatchIso10642() {
        // ISO 10642 / DIN 74 Form F (90°)
        val m3 = HoleStandards.forSize(HoleStandardSize.M3)
        assertEquals(7.0, m3.countersinkDiameter); assertEquals(90.0, m3.countersinkAngle)

        val m4 = HoleStandards.forSize(HoleStandardSize.M4)
        assertEquals(9.2, m4.countersinkDiameter); assertEquals(90.0, m4.countersinkAngle)

        val m5 = HoleStandards.forSize(HoleStandardSize.M5)
        assertEquals(11.5, m5.countersinkDiameter); assertEquals(90.0, m5.countersinkAngle)

        val m6 = HoleStandards.forSize(HoleStandardSize.M6)
        assertEquals(13.7, m6.countersinkDiameter); assertEquals(90.0, m6.countersinkAngle)

        val m8 = HoleStandards.forSize(HoleStandardSize.M8)
        assertEquals(18.3, m8.countersinkDiameter); assertEquals(90.0, m8.countersinkAngle)

        val m10 = HoleStandards.forSize(HoleStandardSize.M10)
        assertEquals(22.7, m10.countersinkDiameter); assertEquals(90.0, m10.countersinkAngle)
    }

    @Test
    fun findByLabelWorks() {
        assertNotNull(HoleStandards.find("m6"))
        assertEquals(HoleStandardSize.M6, HoleStandards.find("M6")?.size)
        assertEquals(null, HoleStandards.find("M12"))
    }
}
