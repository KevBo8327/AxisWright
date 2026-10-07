package dev.androidcad.sketch

import dev.androidcad.json.Json
import dev.androidcad.json.JsonException
import dev.androidcad.json.asObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SketchCodecTest {
    private fun roundTrip(s: Sketch, pretty: Boolean = true): Sketch =
        SketchCodec.fromJson(Json.parse(Json.write(SketchCodec.toJson(s), pretty)).asObject())

    private fun assertSame(a: Sketch, b: Sketch) {
        assertEquals(a.pointIds, b.pointIds)
        a.pointIds.forEach { assertEquals(a.point(it), b.point(it)) }
        assertEquals(a.lineIds, b.lineIds)
        a.lineIds.forEach { assertEquals(a.line(it), b.line(it)) }
        assertEquals(a.circleIds, b.circleIds)
        a.circleIds.forEach { assertEquals(a.circle(it), b.circle(it)) }
        assertEquals(a.arcIds, b.arcIds)
        a.arcIds.forEach { assertEquals(a.arc(it), b.arc(it)) }
        assertEquals(a.constraintEntries, b.constraintEntries)
    }

    /** Skizze, die jeden Constraint-Typ mindestens einmal enthält. */
    private fun everything(): Sketch {
        val s = Sketch()
        val p = (0..5).map { s.addPoint(it * 1.1 + 0.1 / 3, it * 0.7 - 1.0 / 7) }
        val l1 = s.addLine(p[0], p[1]); val l2 = s.addLine(p[2], p[3], construction = true); val l3 = s.addLine(p[4], p[5])
        val c1 = s.addCircle(p[0], 12.345678901234567); val c2 = s.addCircle(p[1], 3.0, construction = true)
        val a1 = s.addArc(p[2], p[3], p[4])
        listOf(
            Coincident(p[0], p[1]), PointOnLine(p[2], l1), PointOnRound(p[3], c1), PointOnRound(p[3], a1), Midpoint(p[4], l1),
            Horizontal(l1), Vertical(l2), HorizontalPoints(p[0], p[2]), VerticalPoints(p[1], p[3]), Parallel(l1, l2),
            Perpendicular(l1, l3), Collinear(l1, l3), EqualLength(l1, l2), EqualRadius(c1, a1), Concentric(c1, c2),
            TangentLineRound(l1, c1), TangentRoundRound(c1, c2, internal = true, sign = -1), Symmetric(p[0], p[1], l3),
            Fixed(p[2], 1.5, null), Fixed(p[3], null, -2.25),
            DistanceDim(p[0], p[1], 10.0), HorizontalDistanceDim(p[0], p[2], -3.5), VerticalDistanceDim(p[1], p[3], 4.0),
            LengthDim(l1, 7.25), PointLineDistanceDim(p[4], l1, 2.0, -1), RadiusDim(c1, 12.0), RadiusDim(a1, 1.0),
            DiameterDim(c2, 6.0), AngleDim(l1, l3, 33.3333333333),
        ).forEach { s.add(it) }
        return s
    }

    @Test
    fun roundTripKeepsEverythingIncludingIds() {
        val s = everything()
        assertSame(s, roundTrip(s))
        assertSame(s, roundTrip(s, pretty = false))
    }

    @Test
    fun allConstraintTypesAreCovered() {
        val types = everything().constraintEntries.map { it.constraint::class }.toSet()
        // alle Unterklassen von Constraint (abgesehen vom Standard-Fixed des Ursprungs) kommen vor
        assertEquals(26, types.size, "Neuer Constraint-Typ? Codec und Test ergänzen: $types")
    }

    @Test
    fun loadedSketchContinuesWithFreshIds() {
        val s = everything()
        val back = roundTrip(s)
        val newId = back.addPoint(1.0, 2.0)
        assertTrue(newId.value > s.pointIds.maxOf { it.value } && back.constraintEntries.none { it.id.value == newId.value })
        val again = s.addPoint(1.0, 2.0)
        assertEquals(again, newId)
    }

    @Test
    fun solvedSketchStaysSolvedAfterReload() {
        val s = Sketch()
        val r = s.addRectangle(0.0, 0.0, 40.0, 25.0)
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        s.addLength(r.edges[0], 42.0)
        s.addLength(r.edges[1], 26.0)
        s.add(Horizontal(r.edges[0])); s.add(Vertical(r.edges[1]))
        s.add(Horizontal(r.edges[2])); s.add(Vertical(r.edges[3]))
        assertEquals(SolveStatus.SOLVED, s.solve().status)
        val back = roundTrip(s)
        assertSame(s, back)
        val regions = back.regions().regions
        assertEquals(1, regions.size)
        assertEquals(42.0 * 26.0, regions.single().area, 1e-6)
    }

    @Test
    fun emptySketchRoundTrips() {
        assertSame(Sketch(), roundTrip(Sketch()))
    }

    private fun json(mutate: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
        val m = LinkedHashMap(SketchCodec.toJson(everything()))
        mutate(m)
        return m
    }

    @Test
    fun rejectsBrokenFiles() {
        @Suppress("UNCHECKED_CAST")
        fun listOf(m: Map<String, Any?>, k: String) = (m[k] as List<Any?>).toMutableList()
        val cases: Map<String, (MutableMap<String, Any?>) -> Unit> = mapOf(
            "Linie mit unbekanntem Punkt" to { m -> m["lines"] = listOf(m, "lines") + mapOf("id" to 900, "p1" to 1, "p2" to 777) ; m["nextId"] = 1000 },
            "doppelte ID" to { m -> m["points"] = listOf(m, "points") + listOf(listOf(1, 5.0, 5.0)) },
            "nextId zu klein" to { m -> m["nextId"] = 3 },
            "Ursprung fehlt" to { m -> m["points"] = listOf(m, "points").drop(1) },
            "Constraint auf fehlende Linie" to { m -> m["constraints"] = listOf(m, "constraints") + mapOf("id" to 901, "type" to "Horizontal", "line" to 999); m["nextId"] = 1000 },
            "unbekannter Typ" to { m -> m["constraints"] = listOf(m, "constraints") + mapOf("id" to 902, "type" to "Gibtsnicht"); m["nextId"] = 1000 },
            "falsche Rundungs-Referenz" to { m -> m["constraints"] = listOf(m, "constraints") + mapOf("id" to 903, "type" to "RadiusDim", "round" to "x5", "value" to 1); m["nextId"] = 1000 },
            "Feld fehlt" to { m -> m.remove("circles") },
            "Punkt falsch geformt" to { m -> m["points"] = listOf(listOf(1, 2)) },
        )
        for ((name, mutate) in cases) {
            assertFailsWith<JsonException>("sollte scheitern: $name") { SketchCodec.fromJson(json(mutate)) }
        }
    }
}
