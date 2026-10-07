package dev.androidcad.sketchedit

import dev.androidcad.sketch.AngleDim
import dev.androidcad.sketch.Coincident
import dev.androidcad.sketch.Collinear
import dev.androidcad.sketch.Concentric
import dev.androidcad.sketch.ConstraintId
import dev.androidcad.sketch.DiameterDim
import dev.androidcad.sketch.Dimension
import dev.androidcad.sketch.DistanceDim
import dev.androidcad.sketch.EqualLength
import dev.androidcad.sketch.EqualRadius
import dev.androidcad.sketch.CircleId
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
import dev.androidcad.sketch.Rotated
import dev.androidcad.sketch.Translated
import dev.androidcad.sketch.TangentLineRound
import dev.androidcad.sketch.TangentRoundRound
import dev.androidcad.sketch.Vec2
import dev.androidcad.sketch.Vertical
import dev.androidcad.sketch.VerticalDistanceDim
import dev.androidcad.sketch.VerticalPoints
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Maßetikett: [center] ist die Bildschirmmitte, [anchor] der Punkt am Element, an den es gehört (für Hilfslinie). */
public data class DimLabel(val id: ConstraintId, val text: String, val center: Vec2, val anchor: Vec2) {
    /** Näherung der Etikettgröße in px (für Treffertests und Hintergrund). */
    public val halfWidth: Double get() = 4.5 * text.length + 8
    public val halfHeight: Double get() = 12.0
    public fun contains(p: Vec2): Boolean = kotlin.math.abs(p.x - center.x) <= halfWidth && kotlin.math.abs(p.y - center.y) <= halfHeight
}

/** Symbol eines geometrischen Constraints an der Stelle [center] (Bildschirm). */
public data class Badge(val id: ConstraintId, val symbol: String, val center: Vec2)

public fun formatValue(v: Double): String {
    val r = Math.round(v * 100) / 100.0
    return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e9) r.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, r).trimEnd('0').trimEnd('.')
}

internal object Annotations {
    private const val OFFSET = 18.0

    /** Ob [p] nur zu festen Referenzen gehört (projizierte Körperkanten, Achsen) – dort sind „F“ und Maße nur Rauschen. */
    private fun isReferencePoint(sketch: Sketch, p: PointId): Boolean {
        val users = sketch.elementsUsing(p)
        return users.isNotEmpty() && users.all { u ->
            when (u) {
                is LineId -> sketch.line(u).construction
                is CircleId -> sketch.circle(u).construction
                else -> false
            }
        }
    }

    private fun isReferenceRound(sketch: Sketch, r: RoundId): Boolean =
        r is CircleId && sketch.circle(r).construction && sketch.constraintEntries.any { (it.constraint as? Fixed)?.point == sketch.circle(r).center }

    fun labels(sketch: Sketch, view: ViewTransform): List<DimLabel> {
        val out = ArrayList<DimLabel>()
        for ((id, c) in sketch.constraintEntries) {
            if (c !is Dimension) continue
            // Radius einer projizierten Kreiskante: wird bei jedem Aufbau aus dem Körper gesetzt, kein Maß zum Bearbeiten
            if (c is RadiusDim && isReferenceRound(sketch, c.round)) continue
            label(sketch, view, id, c)?.let { out += it }
        }
        return out
    }

    private fun label(sketch: Sketch, view: ViewTransform, id: ConstraintId, c: Dimension): DimLabel? {
        fun s(p: PointId) = view.toScreen(sketch.point(p))
        /** Etikett auf halber Strecke a–b, seitlich versetzt. */
        fun along(a: Vec2, b: Vec2, text: String, offset: Double = OFFSET): DimLabel {
            val mid = Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(1e-9)
            val n = Vec2(-dy / len, dx / len)
            // Etikett soll über/neben der Linie stehen: Normale so wählen, dass sie nach oben (kleineres y) zeigt
            val sign = if (n.y > 0) -1.0 else 1.0
            return DimLabel(id, text, Vec2(mid.x + n.x * offset * sign, mid.y + n.y * offset * sign), mid)
        }
        return when (c) {
            is LengthDim -> sketch.line(c.line).let { l -> along(s(l.p1), s(l.p2), formatValue(c.value)) }
            is DistanceDim -> along(s(c.a), s(c.b), formatValue(c.value))
            // Das Vorzeichen (Richtung von a nach b) steckt in der Geometrie; angezeigt wird der Betrag.
            is HorizontalDistanceDim -> {
                val a = s(c.a); val b = s(c.b)
                val low = if (a.y > b.y) a else b
                val mid = Vec2((a.x + b.x) / 2, low.y)
                DimLabel(id, formatValue(kotlin.math.abs(c.value)), Vec2(mid.x, mid.y + OFFSET), mid)
            }
            is VerticalDistanceDim -> {
                val a = s(c.a); val b = s(c.b)
                val right = if (a.x > b.x) a else b
                val mid = Vec2(right.x, (a.y + b.y) / 2)
                DimLabel(id, formatValue(kotlin.math.abs(c.value)), Vec2(mid.x + OFFSET + 6, mid.y), mid)
            }
            is RadiusDim, is DiameterDim -> {
                val round: RoundId = if (c is RadiusDim) c.round else (c as DiameterDim).round
                val center = view.toScreen(sketch.point(sketch.centerOf(round)))
                val r = sketch.radiusOf(round) * view.scale
                val anchor = Vec2(center.x + r * cos(Math.PI / 4), center.y - r * sin(Math.PI / 4))
                val text = (if (c is RadiusDim) "R" else "Ø") + formatValue(c.value)
                DimLabel(id, text, Vec2(anchor.x + 22, anchor.y - 14), anchor)
            }
            is AngleDim -> {
                val a = sketch.line(c.a)
                val anchor = s(a.p1)
                DimLabel(id, formatValue(c.value) + "°", Vec2(anchor.x + 30, anchor.y - 22), anchor)
            }
            is PointLineDistanceDim -> {
                val p = s(c.point)
                val l = sketch.line(c.line)
                val a = s(l.p1); val b = s(l.p2)
                val q = nearestOnSegment(p, a, b).point
                along(p, q, formatValue(c.value), offset = 12.0)
            }
        }
    }

    fun badges(sketch: Sketch, view: ViewTransform): List<Badge> {
        val out = ArrayList<Badge>()
        val counts = HashMap<Pair<Int, Int>, Int>() // gleiche Stelle → nebeneinander statt übereinander
        fun place(id: ConstraintId, symbol: String, at: Vec2) {
            val key = Pair(Math.round(at.x / 20).toInt(), Math.round(at.y / 20).toInt())
            val k = counts.merge(key, 1, Int::plus)!! - 1
            out += Badge(id, symbol, Vec2(at.x + k * 20.0, at.y))
        }
        fun lineBadge(id: ConstraintId, symbol: String, line: LineId) {
            val l = sketch.line(line)
            val a = view.toScreen(sketch.point(l.p1)); val b = view.toScreen(sketch.point(l.p2))
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(1e-9)
            var nx = -dy / len; var ny = dx / len
            if (ny > 0) { nx = -nx; ny = -ny }
            place(id, symbol, Vec2((a.x + b.x) / 2 - nx * 14, (a.y + b.y) / 2 - ny * 14))
        }
        fun pointBadge(id: ConstraintId, symbol: String, p: PointId) {
            val s = view.toScreen(sketch.point(p))
            place(id, symbol, Vec2(s.x + 12, s.y - 12))
        }
        fun roundBadge(id: ConstraintId, symbol: String, r: RoundId) {
            val s = view.toScreen(sketch.point(sketch.centerOf(r)))
            place(id, symbol, Vec2(s.x + 12, s.y - 12))
        }
        for ((id, c) in sketch.constraintEntries) when (c) {
            is Horizontal -> lineBadge(id, "—", c.line)
            is Vertical -> lineBadge(id, "|", c.line)
            is HorizontalPoints -> pointBadge(id, "—", c.b)
            is VerticalPoints -> pointBadge(id, "|", c.b)
            is Parallel -> { lineBadge(id, "∥", c.a); lineBadge(id, "∥", c.b) }
            is Perpendicular -> { lineBadge(id, "⊥", c.a); lineBadge(id, "⊥", c.b) }
            is Collinear -> { lineBadge(id, "≡", c.a); lineBadge(id, "≡", c.b) }
            is EqualLength -> { lineBadge(id, "=", c.a); lineBadge(id, "=", c.b) }
            is EqualRadius -> { roundBadge(id, "=", c.a); roundBadge(id, "=", c.b) }
            is Coincident -> pointBadge(id, "●", c.b)
            is Concentric -> roundBadge(id, "◎", c.b)
            is PointOnLine -> pointBadge(id, "∈", c.point)
            is PointOnRound -> pointBadge(id, "∈", c.point)
            is Midpoint -> pointBadge(id, "M", c.point)
            is TangentLineRound -> lineBadge(id, "T", c.line)
            is TangentRoundRound -> roundBadge(id, "T", c.b)
            is Symmetric -> { pointBadge(id, "⇔", c.a); pointBadge(id, "⇔", c.b) }
            is Fixed -> if (c.point != sketch.origin && !SketchOps.isAxisPoint(sketch, c.point) && !isReferencePoint(sketch, c.point)) pointBadge(id, "F", c.point)
            is Translated, is Rotated -> Unit // Kopien eines Musters: die Hilfslinie zeigt den Zusammenhang
            is Dimension -> Unit
        }
        return out
    }
}

