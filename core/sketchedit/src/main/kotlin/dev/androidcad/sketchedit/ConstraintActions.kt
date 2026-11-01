package dev.androidcad.sketchedit

import dev.androidcad.sketch.AngleDim
import dev.androidcad.sketch.ArcId
import dev.androidcad.sketch.CircleId
import dev.androidcad.sketch.Coincident
import dev.androidcad.sketch.Collinear
import dev.androidcad.sketch.Concentric
import dev.androidcad.sketch.Constraint
import dev.androidcad.sketch.ConstraintId
import dev.androidcad.sketch.DiameterDim
import dev.androidcad.sketch.DistanceDim
import dev.androidcad.sketch.EqualLength
import dev.androidcad.sketch.EqualRadius
import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.Horizontal
import dev.androidcad.sketch.HorizontalDistanceDim
import dev.androidcad.sketch.HorizontalPoints
import dev.androidcad.sketch.LengthDim
import dev.androidcad.sketch.LineId
import dev.androidcad.sketch.Midpoint
import dev.androidcad.sketch.Parallel
import dev.androidcad.sketch.Perpendicular
import dev.androidcad.sketch.PointId
import dev.androidcad.sketch.PointLineDistanceDim
import dev.androidcad.sketch.PointOnLine
import dev.androidcad.sketch.PointOnRound
import dev.androidcad.sketch.RadiusDim
import dev.androidcad.sketch.RoundId
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.Symmetric
import dev.androidcad.sketch.TangentLineRound
import dev.androidcad.sketch.TangentRoundRound
import dev.androidcad.sketch.Vertical
import dev.androidcad.sketch.VerticalDistanceDim
import dev.androidcad.sketch.VerticalPoints
import kotlin.math.abs

public enum class ActionGroup { GEOMETRY, DIMENSION, OTHER }

/** Eine Aktion der Constraint-Leiste (Beschriftung: App, `CoreText.kt`). [needsValue]: Maß, das nach einem Zahlenwert fragt. */
public enum class ActionKind(public val symbol: String, public val group: ActionGroup, public val needsValue: Boolean = false) {
    HORIZONTAL("—", ActionGroup.GEOMETRY),
    VERTICAL("|", ActionGroup.GEOMETRY),
    PARALLEL("∥", ActionGroup.GEOMETRY),
    PERPENDICULAR("⊥", ActionGroup.GEOMETRY),
    COLLINEAR("≡", ActionGroup.GEOMETRY),
    EQUAL("=", ActionGroup.GEOMETRY),
    COINCIDENT("●", ActionGroup.GEOMETRY),
    CONCENTRIC("◎", ActionGroup.GEOMETRY),
    POINT_ON("∈", ActionGroup.GEOMETRY),
    MIDPOINT("M", ActionGroup.GEOMETRY),
    TANGENT("T", ActionGroup.GEOMETRY),
    SYMMETRIC("⇔", ActionGroup.GEOMETRY),
    FIX("F", ActionGroup.GEOMETRY),
    LENGTH("↔", ActionGroup.DIMENSION, true),
    DISTANCE("↔", ActionGroup.DIMENSION, true),
    HORIZONTAL_DISTANCE("↔x", ActionGroup.DIMENSION, true),
    VERTICAL_DISTANCE("↕y", ActionGroup.DIMENSION, true),
    RADIUS("R", ActionGroup.DIMENSION, true),
    DIAMETER("Ø", ActionGroup.DIMENSION, true),
    ANGLE("∠", ActionGroup.DIMENSION, true),
    CONSTRUCTION("⋯", ActionGroup.OTHER),
    ORIGIN_CORNER("⌞", ActionGroup.OTHER),
    ORIGIN_CENTER("⊕", ActionGroup.OTHER),
    FACE_CENTER("⊡", ActionGroup.OTHER),
    HOLE_CENTER("◎", ActionGroup.OTHER),
    EDGE_CENTER("⊥", ActionGroup.OTHER),
    PATTERN_COUNT("#", ActionGroup.OTHER, true),
}

/** Verfügbare Aktion für die aktuelle Auswahl; [defaultValue] ist bei Maßen der Wert der Zeichnung. */
public data class ActionOption(val kind: ActionKind, val defaultValue: Double? = null)

/** Aufgeschlüsselte Auswahl; Reihenfolge = Reihenfolge der Auswahl. */
internal class SelInfo(val points: List<PointId>, val lines: List<LineId>, val rounds: List<RoundId>, val dims: List<ConstraintId>) {
    val onlyLines: Boolean get() = lines.isNotEmpty() && points.isEmpty() && rounds.isEmpty()
    val onlyPoints: Boolean get() = points.isNotEmpty() && lines.isEmpty() && rounds.isEmpty()
    val onlyRounds: Boolean get() = rounds.isNotEmpty() && points.isEmpty() && lines.isEmpty()
    val isEmpty: Boolean get() = points.isEmpty() && lines.isEmpty() && rounds.isEmpty()
}

internal fun selInfo(sel: Collection<Sel>): SelInfo = SelInfo(
    sel.filterIsInstance<Sel.P>().map { it.id },
    sel.filterIsInstance<Sel.L>().map { it.id },
    sel.mapNotNull { when (it) { is Sel.C -> it.id as RoundId; is Sel.A -> it.id as RoundId; else -> null } },
    sel.filterIsInstance<Sel.K>().map { it.id },
)

internal object ConstraintActions {
    /** Aktionen, die mit einer gewählten Achse keinen Sinn ergeben (sie hat keine Länge und liegt fest). */
    private val NOT_FOR_AXES = setOf(
        ActionKind.HORIZONTAL, ActionKind.VERTICAL, ActionKind.EQUAL, ActionKind.LENGTH, ActionKind.MIDPOINT,
        ActionKind.FIX, ActionKind.CONSTRUCTION,
    )

    /** Alle für die Auswahl möglichen Aktionen (mit Vorgabewerten). */
    fun options(sketch: Sketch, i: SelInfo): List<ActionOption> =
        ActionKind.entries.filter { kind -> i.lines.none { SketchOps.axisOf(sketch, it) != null } || kind !in NOT_FOR_AXES }.mapNotNull { kind ->
            if (kind == ActionKind.CONSTRUCTION) return@mapNotNull if (i.lines.isNotEmpty() || i.rounds.isNotEmpty()) ActionOption(kind) else null
            val built = build(sketch, kind, i, value = null) ?: return@mapNotNull null
            val default = when {
                kind == ActionKind.PATTERN_COUNT -> SketchOps.patternOf(sketch, i.points, i.lines, i.rounds)?.count?.toDouble()
                kind.needsValue -> defaultValue(sketch, built.firstOrNull())
                else -> null
            }
            ActionOption(kind, default)
        }

    private fun defaultValue(sketch: Sketch, c: Constraint?): Double? =
        (c as? dev.androidcad.sketch.Dimension)?.let { sketch.measure(it) }

    /** Baut die Constraints der Aktion; `null`, wenn sie für die Auswahl nicht passt. [value] ersetzt bei Maßen den Zeichnungswert. */
    fun build(sketch: Sketch, kind: ActionKind, i: SelInfo, value: Double?): List<Constraint>? {
        fun two(block: (PointId, PointId) -> Constraint): List<Constraint>? =
            if (i.onlyPoints && i.points.size == 2) listOf(block(i.points[0], i.points[1])) else null

        return when (kind) {
            ActionKind.HORIZONTAL -> when {
                i.onlyLines -> i.lines.map { Horizontal(it) }
                i.onlyPoints && i.points.size == 2 -> listOf(HorizontalPoints(i.points[0], i.points[1]))
                else -> null
            }
            ActionKind.VERTICAL -> when {
                i.onlyLines -> i.lines.map { Vertical(it) }
                i.onlyPoints && i.points.size == 2 -> listOf(VerticalPoints(i.points[0], i.points[1]))
                else -> null
            }
            ActionKind.PARALLEL -> if (i.onlyLines && i.lines.size >= 2) i.lines.drop(1).map { Parallel(i.lines[0], it) } else null
            ActionKind.PERPENDICULAR -> if (i.onlyLines && i.lines.size == 2) listOf(Perpendicular(i.lines[0], i.lines[1])) else null
            ActionKind.COLLINEAR -> if (i.onlyLines && i.lines.size == 2) listOf(Collinear(i.lines[0], i.lines[1])) else null
            ActionKind.EQUAL -> when {
                i.onlyLines && i.lines.size >= 2 -> i.lines.drop(1).map { EqualLength(i.lines[0], it) }
                i.onlyRounds && i.rounds.size >= 2 -> i.rounds.drop(1).map { EqualRadius(i.rounds[0], it) }
                else -> null
            }
            ActionKind.COINCIDENT -> if (i.onlyPoints && i.points.size >= 2) i.points.drop(1).map { Coincident(i.points[0], it) } else null
            ActionKind.CONCENTRIC -> if (i.onlyRounds && i.rounds.size == 2) listOf(Concentric(i.rounds[0], i.rounds[1])) else null
            ActionKind.POINT_ON -> when {
                i.points.size == 1 && i.lines.size == 1 && i.rounds.isEmpty() -> listOf(PointOnLine(i.points[0], i.lines[0]))
                i.points.size == 1 && i.rounds.size == 1 && i.lines.isEmpty() -> listOf(PointOnRound(i.points[0], i.rounds[0]))
                else -> null
            }
            ActionKind.MIDPOINT -> if (i.points.size == 1 && i.lines.size == 1 && i.rounds.isEmpty()) listOf(Midpoint(i.points[0], i.lines[0])) else null
            ActionKind.TANGENT -> when {
                i.lines.size == 1 && i.rounds.size == 1 && i.points.isEmpty() -> listOf(TangentLineRound(i.lines[0], i.rounds[0]))
                i.onlyRounds && i.rounds.size == 2 -> listOf(tangentRounds(sketch, i.rounds[0], i.rounds[1]))
                else -> null
            }
            ActionKind.SYMMETRIC -> if (i.points.size == 2 && i.lines.size == 1 && i.rounds.isEmpty()) listOf(Symmetric(i.points[0], i.points[1], i.lines[0])) else null
            ActionKind.FIX -> fix(sketch, i)
            ActionKind.LENGTH -> if (i.onlyLines && i.lines.size == 1) listOf(LengthDim(i.lines[0], value ?: sketch.length(i.lines[0]))) else null
            ActionKind.DISTANCE -> when {
                i.onlyPoints && i.points.size == 2 -> listOf(DistanceDim(i.points[0], i.points[1], value ?: dist(sketch, i.points[0], i.points[1])))
                i.points.size == 1 && i.lines.size == 1 && i.rounds.isEmpty() -> {
                    val signed = sketch.signedDistance(i.points[0], i.lines[0])
                    listOf(PointLineDistanceDim(i.points[0], i.lines[0], value ?: abs(signed), if (signed < 0) -1 else 1))
                }
                else -> null
            }
            ActionKind.HORIZONTAL_DISTANCE -> two { a, b -> HorizontalDistanceDim(a, b, value ?: (sketch.point(b).x - sketch.point(a).x)) }
            ActionKind.VERTICAL_DISTANCE -> two { a, b -> VerticalDistanceDim(a, b, value ?: (sketch.point(b).y - sketch.point(a).y)) }
            ActionKind.RADIUS -> if (i.onlyRounds && i.rounds.size == 1) listOf(RadiusDim(i.rounds[0], value ?: sketch.radiusOf(i.rounds[0]))) else null
            ActionKind.DIAMETER -> if (i.onlyRounds && i.rounds.size == 1) listOf(DiameterDim(i.rounds[0], value ?: (2 * sketch.radiusOf(i.rounds[0])))) else null
            // parallele Linien: ein Winkel von 0° bzw. ±180° ist kein sinnvolles Maß (dafür gibt es „Parallel“ und den Abstand)
            ActionKind.ANGLE -> if (i.onlyLines && i.lines.size == 2 && abs(kotlin.math.sin(Math.toRadians(sketch.angleBetween(i.lines[0], i.lines[1])))) > 1e-3)
                listOf(AngleDim(i.lines[0], i.lines[1], value ?: sketch.angleBetween(i.lines[0], i.lines[1]))) else null
            ActionKind.CONSTRUCTION -> emptyList()
            // Lage zum Ursprung: legt ggf. Hilfsgeometrie an, daher im Editor über SketchOps ausgeführt; hier nur „passt / passt nicht“
            // Form + genau eine projizierte Bohrung bzw. Kante als Bezug
            ActionKind.HOLE_CENTER -> referenceTarget(sketch, i)?.let { (l, r) -> if (r != null && l == null) emptyList() else null }
            ActionKind.EDGE_CENTER -> referenceTarget(sketch, i)?.let { (l, r) -> if (l != null && r == null) emptyList() else null }
            // ganzes Muster: Kopien ergänzen oder entfernen (im Editor über SketchOps)
            ActionKind.PATTERN_COUNT -> if (SketchOps.patternOf(sketch, i.points, i.lines, i.rounds) != null) emptyList() else null
            ActionKind.ORIGIN_CORNER, ActionKind.ORIGIN_CENTER, ActionKind.FACE_CENTER -> {
                val shape = i.lines.filter { SketchOps.axisOf(sketch, it) == null }
                if (shape.isNotEmpty() || i.rounds.isNotEmpty()) emptyList() else null
            }
        }
    }

    /**
     * Der Bezug für „Mittig auf Bohrung/zur Kante“: genau ein fester Referenzkreis oder genau eine feste Referenzlinie in der Auswahl
     * (projizierte Körperkante), dazu mindestens ein Element der Form. `null`, wenn das nicht passt.
     */
    fun referenceTarget(sketch: Sketch, i: SelInfo): Pair<LineId?, RoundId?>? {
        val refLines = i.lines.filter { SketchOps.isReferenceLine(sketch, it) }
        val refRounds = i.rounds.filter { SketchOps.isReferenceCircle(sketch, it) }
        if (refLines.size + refRounds.size != 1) return null
        val shape = i.lines.count { it !in refLines && SketchOps.axisOf(sketch, it) == null } + i.rounds.count { it !in refRounds }
        if (shape == 0) return null
        return refLines.firstOrNull() to refRounds.firstOrNull()
    }

    private fun dist(sketch: Sketch, a: PointId, b: PointId): Double =
        sketch.point(a).let { p -> sketch.point(b).let { q -> kotlin.math.hypot(p.x - q.x, p.y - q.y) } }

    /** Tangente zwischen zwei Rundungen; innen/außen und Radienverhältnis folgen der Zeichnung (wie `Sketch.addTangent`). */
    private fun tangentRounds(sketch: Sketch, a: RoundId, b: RoundId): Constraint {
        val d = dist(sketch, sketch.centerOf(a), sketch.centerOf(b))
        val ra = sketch.radiusOf(a); val rb = sketch.radiusOf(b)
        val internal = abs(d - abs(ra - rb)) < abs(d - (ra + rb))
        return TangentRoundRound(a, b, internal, if (ra >= rb) 1 else -1)
    }

    /** Fixiert alle Punkte der Auswahl (auch Endpunkte gewählter Linien, Mittelpunkte/Radien gewählter Kreise). */
    private fun fix(sketch: Sketch, i: SelInfo): List<Constraint>? {
        if (i.isEmpty) return null
        val pts = LinkedHashSet<PointId>()
        pts += i.points
        i.lines.forEach { sketch.line(it).let { l -> pts += l.p1; pts += l.p2 } }
        val out = ArrayList<Constraint>()
        for (r in i.rounds) when (r) {
            is CircleId -> { pts += sketch.circle(r).center; out += RadiusDim(r, sketch.circle(r).radius) }
            is ArcId -> sketch.arc(r).let { pts += it.center; pts += it.start; pts += it.end }
        }
        pts.forEach { out += Fixed(it, sketch.point(it).x, sketch.point(it).y) }
        return out
    }
}
