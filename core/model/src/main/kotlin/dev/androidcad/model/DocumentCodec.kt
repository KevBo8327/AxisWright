package dev.androidcad.model

import dev.androidcad.json.Json
import dev.androidcad.json.JsonException
import dev.androidcad.json.JsonObject
import dev.androidcad.json.asList
import dev.androidcad.json.asObject
import dev.androidcad.json.bool
import dev.androidcad.json.double
import dev.androidcad.json.doubleOrNull
import dev.androidcad.json.int
import dev.androidcad.json.list
import dev.androidcad.json.obj
import dev.androidcad.json.string
import dev.androidcad.sketch.CircleId
import dev.androidcad.sketch.LineId
import dev.androidcad.sketch.SketchCodec
import dev.androidcad.sketch.Vec2

/**
 * Speicherformat des Modells (`model.json` im Projektordner): Feature-Historie mit Skizzen, Ebenen und
 * Extrusionsparametern. Versioniert, damit spätere Features (Bohrung, Verrundung …) alte Dateien nicht entwerten.
 */
object DocumentCodec {
    const val FORMAT = "androidcad-model"
    const val VERSION = 1

    fun encode(doc: Document): String = Json.write(toJson(doc), pretty = true)

    /** Liest ein Dokument. Wirft [JsonException] bei ungültigem JSON, fehlenden Feldern oder inkonsistenten Referenzen. */
    fun decode(text: String): Document = fromJson(Json.parse(text).asObject("Modelldatei"))

    fun toJson(doc: Document): JsonObject = linkedMapOf(
        "format" to FORMAT,
        "version" to VERSION,
        "nextId" to doc.nextIdValue,
        "features" to doc.features.map(::encodeFeature),
    )

    fun fromJson(json: JsonObject): Document {
        if (json["format"] != FORMAT) throw JsonException("Keine Modelldatei (Feld 'format')")
        val version = json.int("version")
        if (version > VERSION) throw JsonException("Modelldatei stammt aus einer neueren App-Version ($version > $VERSION)")
        val features = json.list("features").map { decodeFeature(it.asObject("Feature")) }
        val doc = Document()
        try {
            doc.load(json.int("nextId"), features)
        } catch (e: IllegalArgumentException) {
            throw JsonException("Modell ungültig: ${e.message}")
        }
        return doc
    }

    // ---- Features ---------------------------------------------------------------------------------

    private fun encodeFeature(f: Feature): JsonObject = when (f) {
        is SketchFeature -> linkedMapOf(
            "kind" to "sketch", "id" to f.id.value, "name" to f.name,
            "plane" to encodePlane(f.plane), "sketch" to SketchCodec.toJson(f.sketch),
        ).also { m ->
            f.onFace?.let { m["onFace"] = encodeFace(it) }
            f.onPlane?.let { m["onPlane"] = it.value }
            if (f.projections.isNotEmpty()) m["projections"] = f.projections.map { p ->
                linkedMapOf<String, Any?>("a" to encodeFace(p.edge.a), "b" to encodeFace(p.edge.b), "line" to p.line?.value, "circle" to p.circle?.value)
            }
        }
        is ExtrudeFeature -> linkedMapOf(
            "kind" to "extrude", "id" to f.id.value, "name" to f.name, "sketch" to f.sketch.value,
            "distance" to f.distance, "op" to f.op.name, "symmetric" to f.symmetric,
            "regionPoints" to f.regionPoints.map { listOf(it.x, it.y) },
        )
        is RevolveFeature -> linkedMapOf(
            "kind" to "revolve", "id" to f.id.value, "name" to f.name, "sketch" to f.sketch.value,
            "axis" to when (val a = f.axis) {
                RevolveAxis.SketchX -> linkedMapOf("type" to "x")
                RevolveAxis.SketchY -> linkedMapOf("type" to "y")
                is RevolveAxis.Line -> linkedMapOf("type" to "line", "line" to a.line.value)
            },
            "angle" to f.angle, "op" to f.op.name, "symmetric" to f.symmetric,
            "regionPoints" to f.regionPoints.map { listOf(it.x, it.y) },
        )
        is HoleFeature -> encodeHole(f)
        is PlaneFeature -> linkedMapOf<String, Any?>(
            "kind" to "datum", "id" to f.id.value, "name" to f.name, "plane" to encodePlane(f.plane),
        ).also { m ->
            when (val d = f.def) {
                is PlaneDef.Offset -> { m["type"] = "offset"; m["face"] = encodeFace(d.face); m["distance"] = d.distance }
                is PlaneDef.AtEdge -> {
                    m["type"] = "edge"; m["face"] = encodeFace(d.face); m["angle"] = d.angle
                    m["edge"] = linkedMapOf("a" to encodeFace(d.edge.a), "b" to encodeFace(d.edge.b))
                }
            }
        }
        is MirrorBodyFeature -> linkedMapOf<String, Any?>(
            "kind" to "mirrorBody", "id" to f.id.value, "name" to f.name, "origin" to vec(f.origin), "normal" to vec(f.normal),
        ).also { m -> f.datum?.let { m["datum"] = it.value } }
        is PatternFeature -> linkedMapOf<String, Any?>(
            "kind" to "pattern", "id" to f.id.value, "name" to f.name, "source" to f.source.value,
        ).also { it.putAll(encodePattern(f.kind)); if (f.withFillets) it["withFillets"] = true }
        is FilletFeature -> linkedMapOf(
            "kind" to "fillet", "id" to f.id.value, "name" to f.name, "mode" to f.kind.name, "size" to f.size,
            "edges" to f.edges.map { linkedMapOf("a" to encodeFace(it.a), "b" to encodeFace(it.b)) },
        )
    }

    private fun encodeHole(f: HoleFeature): JsonObject = linkedMapOf(
        "kind" to "hole", "id" to f.id.value, "name" to f.name, "face" to encodeFace(f.face),
        "point" to listOf(f.x, f.y, f.z), "diameter" to f.diameter, "depth" to f.depth,
        "anchors" to f.anchors?.toList()?.map { linkedMapOf("a" to encodeFace(it.edge.a), "b" to encodeFace(it.edge.b), "distance" to it.distance) },
    )

    private fun encodePattern(k: PatternKind): Map<String, Any?> = when (k) {
        is PatternKind.Linear -> linkedMapOf<String, Any?>("type" to "linear", "direction" to vec(k.direction), "count" to k.count, "spacing" to k.spacing).also {
            if (k.direction2 != null) { it["direction2"] = vec(k.direction2); it["count2"] = k.count2; it["spacing2"] = k.spacing2 }
        }
        is PatternKind.Circular -> linkedMapOf(
            "type" to "circular", "origin" to vec(k.origin), "axis" to vec(k.axis), "count" to k.count, "angle" to k.angle,
        )
        is PatternKind.Mirror -> linkedMapOf("type" to "mirror", "origin" to vec(k.origin), "normal" to vec(k.normal))
    }

    private fun decodePattern(o: JsonObject): PatternKind {
        val kind = when (val type = o.string("type")) {
            "linear" ->
                if (o["direction2"] == null) PatternKind.Linear(readVec(o, "direction"), o.int("count"), o.double("spacing"))
                else PatternKind.Linear(readVec(o, "direction"), o.int("count"), o.double("spacing"), readVec(o, "direction2"), o.int("count2"), o.double("spacing2"))
            "circular" -> PatternKind.Circular(readVec(o, "origin"), readVec(o, "axis"), o.int("count"), o.double("angle"))
            "mirror" -> PatternKind.Mirror(readVec(o, "origin"), readVec(o, "normal"))
            else -> throw JsonException("Muster: unbekannte Art '$type'")
        }
        PatternGeometry.validate(kind)?.let { throw JsonException("Muster ungültig: $it") }
        return kind
    }

    private fun encodeFace(r: FaceRef) = listOf(r.feature.value, r.role.code, r.element)

    private fun decodeFace(v: Any?): FaceRef {
        val t = v.asList("Flächenreferenz")
        if (t.size != 3 || t.any { it !is Number }) throw JsonException("Flächenreferenz: [Feature, Rolle, Element] erwartet")
        val role = FaceRole.entries.firstOrNull { it.code == (t[1] as Number).toInt() } ?: throw JsonException("Flächenreferenz: unbekannte Rolle ${t[1]}")
        val element = (t[2] as Number).toDouble()
        if (element != Math.floor(element) || element < 0 || element > FaceRef.ELEMENT_MASK) throw JsonException("Flächenreferenz: Element ungültig")
        return FaceRef(FeatureId((t[0] as Number).toInt()), role, element.toLong())
    }

    private fun decodeProjection(o: JsonObject): ProjectedEdge {
        val line = if (o["line"] == null) null else LineId(o.int("line"))
        val circle = if (o["circle"] == null) null else CircleId(o.int("circle"))
        if ((line == null) == (circle == null)) throw JsonException("Projektion: genau eine von 'line' und 'circle' erwartet")
        return ProjectedEdge(EdgeRef(decodeFace(o["a"]), decodeFace(o["b"])), line, circle)
    }

    private fun decodeFeature(o: JsonObject): Feature = when (val kind = o.string("kind")) {
        "sketch" -> SketchFeature(
            FeatureId(o.int("id")), o.string("name"), decodePlane(o.obj("plane")), SketchCodec.fromJson(o.obj("sketch")),
            o["onFace"]?.let(::decodeFace),
            o["projections"]?.asList("Projektionen")?.map { decodeProjection(it.asObject("Projektion")) } ?: emptyList(),
            if (o["onPlane"] == null) null else FeatureId(o.int("onPlane")),
        )
        "extrude" -> {
            val d = o.double("distance")
            if (!d.isFinite()) throw JsonException("Extrusion: Länge ungültig")
            val op = BodyOp.entries.firstOrNull { it.name == o.string("op") } ?: throw JsonException("Extrusion: unbekannte Operation '${o["op"]}'")
            ExtrudeFeature(FeatureId(o.int("id")), o.string("name"), FeatureId(o.int("sketch")), d, op, regionPoints(o), o.bool("symmetric", false))
        }
        "revolve" -> {
            val angle = o.double("angle")
            if (!angle.isFinite() || angle == 0.0 || kotlin.math.abs(angle) > 360.0) throw JsonException("Drehung: Winkel ungültig")
            val op = BodyOp.entries.firstOrNull { it.name == o.string("op") } ?: throw JsonException("Drehung: unbekannte Operation '${o["op"]}'")
            val a = o.obj("axis")
            val axis = when (val type = a.string("type")) {
                "x" -> RevolveAxis.SketchX
                "y" -> RevolveAxis.SketchY
                "line" -> RevolveAxis.Line(LineId(a.int("line")))
                else -> throw JsonException("Drehung: unbekannte Achse '$type'")
            }
            RevolveFeature(FeatureId(o.int("id")), o.string("name"), FeatureId(o.int("sketch")), axis, angle, op, regionPoints(o), o.bool("symmetric", false))
        }
        "fillet" -> {
            val size = o.double("size")
            if (!size.isFinite()) throw JsonException("Verrundung: Größe ungültig")
            val mode = EdgeKind.entries.firstOrNull { it.name == o.string("mode") } ?: throw JsonException("Verrundung: unbekannter Modus '${o["mode"]}'")
            val edges = o.list("edges").map { e -> e.asObject("Kante").let { EdgeRef(decodeFace(it["a"]), decodeFace(it["b"])) } }
            FilletFeature(FeatureId(o.int("id")), o.string("name"), edges, size, mode)
        }
        "hole" -> {
            val pt = o.list("point")
            if (pt.size != 3 || pt.any { it !is Number || !it.toDouble().isFinite() }) throw JsonException("Bohrung: Punkt [x, y, z] erwartet")
            val diameter = o.double("diameter")
            if (!diameter.isFinite()) throw JsonException("Bohrung: Durchmesser ungültig")
            val depth = o.doubleOrNull("depth")
            if (depth != null && !depth.isFinite()) throw JsonException("Bohrung: Tiefe ungültig")
            val anchors = o["anchors"]?.let { raw ->
                val list = raw.asList("Bohrung: Referenzkanten").map { e ->
                    val eo = e.asObject("Referenzkante")
                    val dist = eo.double("distance")
                    if (!dist.isFinite()) throw JsonException("Referenzkante: Abstand ungültig")
                    EdgeDistance(EdgeRef(decodeFace(eo["a"]), decodeFace(eo["b"])), dist)
                }
                if (list.size != 2) throw JsonException("Bohrung: genau zwei Referenzkanten erwartet")
                list[0] to list[1]
            }
            HoleFeature(
                FeatureId(o.int("id")), o.string("name"), decodeFace(o["face"]),
                (pt[0] as Number).toDouble(), (pt[1] as Number).toDouble(), (pt[2] as Number).toDouble(), diameter, depth, anchors,
            )
        }
        "datum" -> {
            val def = when (val type = o.string("type")) {
                "offset" -> PlaneDef.Offset(decodeFace(o["face"]), o.double("distance"))
                "edge" -> PlaneDef.AtEdge(
                    decodeFace(o["face"]),
                    o.obj("edge").let { EdgeRef(decodeFace(it["a"]), decodeFace(it["b"])) },
                    o.double("angle"),
                )
                else -> throw JsonException("Arbeitsebene: unbekannte Art '$type'")
            }
            DatumPlane.validate(def)?.let { throw JsonException("Arbeitsebene ungültig: $it") }
            PlaneFeature(FeatureId(o.int("id")), o.string("name"), def, decodePlane(o.obj("plane")))
        }
        "mirrorBody" -> MirrorBodyFeature(
            FeatureId(o.int("id")), o.string("name"), readVec(o, "origin"), readVec(o, "normal"),
            if (o["datum"] == null) null else FeatureId(o.int("datum")),
        )
        "pattern" -> PatternFeature(FeatureId(o.int("id")), o.string("name"), FeatureId(o.int("source")), decodePattern(o), o.bool("withFillets", false))
        else -> throw JsonException("Unbekannter Feature-Typ '$kind'")
    }

    private fun regionPoints(o: JsonObject): List<Vec2> = o.list("regionPoints").map {
        val t = it.asList("Auswahlpunkt")
        if (t.size != 2 || t.any { v -> v !is Number }) throw JsonException("Auswahlpunkt: [x, y] erwartet")
        Vec2((t[0] as Number).toDouble(), (t[1] as Number).toDouble())
    }

    // ---- Ebene ------------------------------------------------------------------------------------

    private fun encodePlane(p: Workplane): JsonObject = linkedMapOf(
        "origin" to vec(p.origin), "xDir" to vec(p.xDir), "normal" to vec(p.normal),
    )

    private fun vec(v: Vec3) = listOf(v.x, v.y, v.z)

    private fun readVec(o: JsonObject, key: String): Vec3 {
        val t = o.list(key)
        if (t.size != 3 || t.any { it !is Number || !it.toDouble().isFinite() }) throw JsonException("Feld '$key': drei endliche Zahlen erwartet")
        return Vec3((t[0] as Number).toDouble(), (t[1] as Number).toDouble(), (t[2] as Number).toDouble())
    }

    private fun decodePlane(o: JsonObject): Workplane = try {
        Workplane(readVec(o, "origin"), readVec(o, "xDir"), readVec(o, "normal"))
    } catch (e: IllegalArgumentException) {
        throw JsonException("Ebene ungültig: ${e.message}")
    }
}
