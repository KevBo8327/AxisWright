package dev.androidcad.sketch

import dev.androidcad.json.JsonException
import dev.androidcad.json.JsonObject
import dev.androidcad.json.asList
import dev.androidcad.json.asObject
import dev.androidcad.json.bool
import dev.androidcad.json.double
import dev.androidcad.json.doubleOrNull
import dev.androidcad.json.int
import dev.androidcad.json.list
import dev.androidcad.json.string

/**
 * Skizze ⇄ JSON-Wert (siehe [dev.androidcad.json.Json]). Alle IDs bleiben erhalten, damit Features, die auf Elemente
 * verweisen, nach dem Laden gültig sind. Runden/Rauschen: Doubles werden verlustfrei geschrieben.
 */
public object SketchCodec {
    public fun toJson(sketch: Sketch): JsonObject {
        val out = LinkedHashMap<String, Any?>()
        out["nextId"] = sketch.nextIdValue
        out["points"] = sketch.pointIds.map { listOf(it.value, sketch.point(it).x, sketch.point(it).y) }
        out["lines"] = sketch.lineIds.map { id ->
            val l = sketch.line(id)
            linkedMapOf("id" to id.value, "p1" to l.p1.value, "p2" to l.p2.value, "construction" to l.construction)
        }
        out["circles"] = sketch.circleIds.map { id ->
            val c = sketch.circle(id)
            linkedMapOf("id" to id.value, "center" to c.center.value, "radius" to c.radius, "construction" to c.construction)
        }
        out["arcs"] = sketch.arcIds.map { id ->
            val a = sketch.arc(id)
            linkedMapOf("id" to id.value, "center" to a.center.value, "start" to a.start.value, "end" to a.end.value, "construction" to a.construction)
        }
        out["constraints"] = sketch.constraintEntries.map { encode(it.id, it.constraint) }
        return out
    }

    /** Liest eine Skizze. Wirft [JsonException] bei fehlenden/ungültigen Feldern oder inkonsistenten Referenzen. */
    public fun fromJson(json: JsonObject): Sketch {
        try {
            val points = LinkedHashMap<Int, Vec2>()
            for (p in json.list("points")) {
                val t = p.asList("Punkt")
                if (t.size != 3) throw JsonException("Punkt: [id, x, y] erwartet")
                put(points, num(t[0]).toInt(), Vec2(num(t[1]), num(t[2])))
            }
            val lines = LinkedHashMap<Int, LineData>()
            for (l in json.list("lines")) {
                val o = l.asObject("Linie")
                put(lines, o.int("id"), LineData(PointId(o.int("p1")), PointId(o.int("p2")), o.bool("construction", false)))
            }
            val circles = LinkedHashMap<Int, CircleData>()
            for (c in json.list("circles")) {
                val o = c.asObject("Kreis")
                put(circles, o.int("id"), CircleData(PointId(o.int("center")), o.double("radius"), o.bool("construction", false)))
            }
            val arcs = LinkedHashMap<Int, ArcData>()
            for (a in json.list("arcs")) {
                val o = a.asObject("Bogen")
                put(arcs, o.int("id"), ArcData(PointId(o.int("center")), PointId(o.int("start")), PointId(o.int("end")), o.bool("construction", false)))
            }
            val constraints = LinkedHashMap<Int, Constraint>()
            for (c in json.list("constraints")) {
                val o = c.asObject("Constraint")
                put(constraints, o.int("id"), decode(o))
            }
            val sketch = Sketch()
            sketch.load(json.int("nextId"), points, lines, circles, arcs, constraints)
            return sketch
        } catch (e: IllegalArgumentException) {
            throw JsonException("Skizze ungültig: ${e.message}")
        } catch (e: IllegalStateException) {
            throw JsonException("Skizze ungültig: ${e.message}")
        }
    }

    // ---- Constraints ------------------------------------------------------------------------------

    private fun <T> put(map: MutableMap<Int, T>, id: Int, value: T) {
        if (map.put(id, value) != null) throw JsonException("ID $id kommt doppelt vor")
    }

    private fun num(v: Any?): Double = (v as? Number)?.toDouble() ?: throw JsonException("Zahl erwartet")

    private fun round(id: RoundId): String = when (id) { is CircleId -> "c${id.value}"; is ArcId -> "a${id.value}" }

    private fun round(o: JsonObject, key: String): RoundId {
        val s = o.string(key)
        val n = s.drop(1).toIntOrNull() ?: throw JsonException("Feld '$key': ungültige Kreis-/Bogen-Referenz '$s'")
        return when (s.firstOrNull()) {
            'c' -> CircleId(n)
            'a' -> ArcId(n)
            else -> throw JsonException("Feld '$key': ungültige Kreis-/Bogen-Referenz '$s'")
        }
    }

    private fun pt(o: JsonObject, key: String) = PointId(o.int(key))
    private fun ln(o: JsonObject, key: String) = LineId(o.int(key))

    private fun m(id: ConstraintId, type: String, vararg fields: Pair<String, Any?>): JsonObject {
        val out = LinkedHashMap<String, Any?>()
        out["id"] = id.value
        out["type"] = type
        for ((k, v) in fields) out[k] = v
        return out
    }

    private fun encode(id: ConstraintId, c: Constraint): JsonObject = when (c) {
        is Coincident -> m(id, "Coincident", "a" to c.a.value, "b" to c.b.value)
        is PointOnLine -> m(id, "PointOnLine", "point" to c.point.value, "line" to c.line.value)
        is PointOnRound -> m(id, "PointOnRound", "point" to c.point.value, "round" to round(c.round))
        is Midpoint -> m(id, "Midpoint", "point" to c.point.value, "line" to c.line.value)
        is Horizontal -> m(id, "Horizontal", "line" to c.line.value)
        is Vertical -> m(id, "Vertical", "line" to c.line.value)
        is HorizontalPoints -> m(id, "HorizontalPoints", "a" to c.a.value, "b" to c.b.value)
        is VerticalPoints -> m(id, "VerticalPoints", "a" to c.a.value, "b" to c.b.value)
        is Parallel -> m(id, "Parallel", "a" to c.a.value, "b" to c.b.value)
        is Perpendicular -> m(id, "Perpendicular", "a" to c.a.value, "b" to c.b.value)
        is Collinear -> m(id, "Collinear", "a" to c.a.value, "b" to c.b.value)
        is EqualLength -> m(id, "EqualLength", "a" to c.a.value, "b" to c.b.value)
        is EqualRadius -> m(id, "EqualRadius", "a" to round(c.a), "b" to round(c.b))
        is Concentric -> m(id, "Concentric", "a" to round(c.a), "b" to round(c.b))
        is TangentLineRound -> m(id, "TangentLineRound", "line" to c.line.value, "round" to round(c.round))
        is TangentRoundRound -> m(id, "TangentRoundRound", "a" to round(c.a), "b" to round(c.b), "internal" to c.internal, "sign" to c.sign)
        is Symmetric -> m(id, "Symmetric", "a" to c.a.value, "b" to c.b.value, "axis" to c.axis.value)
        is Translated -> m(id, "Translated", "a" to c.a.value, "b" to c.b.value, "from" to c.from.value, "to" to c.to.value)
        is Rotated -> m(id, "Rotated", "a" to c.a.value, "b" to c.b.value, "center" to c.center.value, "degrees" to c.degrees)
        is Fixed -> m(id, "Fixed", "point" to c.point.value, "x" to c.x, "y" to c.y)
        is DistanceDim -> m(id, "DistanceDim", "a" to c.a.value, "b" to c.b.value, "value" to c.value)
        is HorizontalDistanceDim -> m(id, "HorizontalDistanceDim", "a" to c.a.value, "b" to c.b.value, "value" to c.value)
        is VerticalDistanceDim -> m(id, "VerticalDistanceDim", "a" to c.a.value, "b" to c.b.value, "value" to c.value)
        is LengthDim -> m(id, "LengthDim", "line" to c.line.value, "value" to c.value)
        is PointLineDistanceDim -> m(id, "PointLineDistanceDim", "point" to c.point.value, "line" to c.line.value, "value" to c.value, "side" to c.side)
        is RadiusDim -> m(id, "RadiusDim", "round" to round(c.round), "value" to c.value)
        is DiameterDim -> m(id, "DiameterDim", "round" to round(c.round), "value" to c.value)
        is AngleDim -> m(id, "AngleDim", "a" to c.a.value, "b" to c.b.value, "value" to c.value)
    }

    private fun decode(o: JsonObject): Constraint = when (val type = o.string("type")) {
        "Coincident" -> Coincident(pt(o, "a"), pt(o, "b"))
        "PointOnLine" -> PointOnLine(pt(o, "point"), ln(o, "line"))
        "PointOnRound" -> PointOnRound(pt(o, "point"), round(o, "round"))
        "Midpoint" -> Midpoint(pt(o, "point"), ln(o, "line"))
        "Horizontal" -> Horizontal(ln(o, "line"))
        "Vertical" -> Vertical(ln(o, "line"))
        "HorizontalPoints" -> HorizontalPoints(pt(o, "a"), pt(o, "b"))
        "VerticalPoints" -> VerticalPoints(pt(o, "a"), pt(o, "b"))
        "Parallel" -> Parallel(ln(o, "a"), ln(o, "b"))
        "Perpendicular" -> Perpendicular(ln(o, "a"), ln(o, "b"))
        "Collinear" -> Collinear(ln(o, "a"), ln(o, "b"))
        "EqualLength" -> EqualLength(ln(o, "a"), ln(o, "b"))
        "EqualRadius" -> EqualRadius(round(o, "a"), round(o, "b"))
        "Concentric" -> Concentric(round(o, "a"), round(o, "b"))
        "TangentLineRound" -> TangentLineRound(ln(o, "line"), round(o, "round"))
        "TangentRoundRound" -> TangentRoundRound(round(o, "a"), round(o, "b"), o.bool("internal"), o.int("sign"))
        "Symmetric" -> Symmetric(pt(o, "a"), pt(o, "b"), ln(o, "axis"))
        "Translated" -> Translated(pt(o, "a"), pt(o, "b"), pt(o, "from"), pt(o, "to"))
        "Rotated" -> Rotated(pt(o, "a"), pt(o, "b"), pt(o, "center"), o.double("degrees"))
        "Fixed" -> Fixed(pt(o, "point"), o.doubleOrNull("x"), o.doubleOrNull("y"))
        "DistanceDim" -> DistanceDim(pt(o, "a"), pt(o, "b"), o.double("value"))
        "HorizontalDistanceDim" -> HorizontalDistanceDim(pt(o, "a"), pt(o, "b"), o.double("value"))
        "VerticalDistanceDim" -> VerticalDistanceDim(pt(o, "a"), pt(o, "b"), o.double("value"))
        "LengthDim" -> LengthDim(ln(o, "line"), o.double("value"))
        "PointLineDistanceDim" -> PointLineDistanceDim(pt(o, "point"), ln(o, "line"), o.double("value"), o.int("side"))
        "RadiusDim" -> RadiusDim(round(o, "round"), o.double("value"))
        "DiameterDim" -> DiameterDim(round(o, "round"), o.double("value"))
        "AngleDim" -> AngleDim(ln(o, "a"), ln(o, "b"), o.double("value"))
        else -> throw JsonException("Unbekannter Constraint-Typ '$type'")
    }
}
