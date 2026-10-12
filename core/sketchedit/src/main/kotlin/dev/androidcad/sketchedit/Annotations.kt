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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Maß wie in der technischen Zeichnung (alles in Bildschirm-px): [lines] sind Strecken (Maßhilfslinien, Maßlinie bzw. Maßbogen),
 * [arrows] Maßpfeile als (Spitze, Richtung der Spitze als Einheitsvektor); die Maßzahl steht mittig auf [center].
 */
public data class DimLabel(
    val id: ConstraintId,
    val text: String,
    val center: Vec2,
    val lines: List<Pair<Vec2, Vec2>> = emptyList(),
    val arrows: List<Pair<Vec2, Vec2>> = emptyList(),
    /** px je dp (siehe [SketchEditor.uiScale]). */
    val scale: Double = 1.0,
) {
    /** Näherung der Etikettgröße in px (für Treffertests und Hintergrund). */
    public val halfWidth: Double get() = (4.5 * text.length + 8) * scale
    public val halfHeight: Double get() = 12.0 * scale
    public fun contains(p: Vec2): Boolean = kotlin.math.abs(p.x - center.x) <= halfWidth && kotlin.math.abs(p.y - center.y) <= halfHeight
}

/** Symbol eines geometrischen Constraints an der Stelle [center] (Bildschirm). */
public data class Badge(val id: ConstraintId, val symbol: String, val center: Vec2)

public fun formatValue(v: Double): String {
    val r = Math.round(v * 100) / 100.0
    return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e9) r.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, r).trimEnd('0').trimEnd('.')
}

internal object Annotations {
    /** Abstand der Maßlinie vom Element. */
    private const val OFFSET = 28.0

    /** Lücke zwischen Element und Maßhilfslinie bzw. Überstand der Hilfslinie über die Maßlinie hinaus. */
    private const val GAP = 4.0
    private const val OVERSHOOT = 6.0

    /** Radius des Maßbogens bei Winkeln. */
    private const val ANGLE_RADIUS = 46.0

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

    /** Maße der Skizze; [k] = px je dp. */
    fun labels(sketch: Sketch, view: ViewTransform, k: Double = 1.0): List<DimLabel> {
        val out = ArrayList<DimLabel>()
        for ((id, c) in sketch.constraintEntries) {
            if (c !is Dimension) continue
            // Radius einer projizierten Kreiskante: wird bei jedem Aufbau aus dem Körper gesetzt, kein Maß zum Bearbeiten
            if (c is RadiusDim && isReferenceRound(sketch, c.round)) continue
            out += label(sketch, view, id, c, k).copy(scale = k)
        }
        return out
    }

    private fun label(sketch: Sketch, view: ViewTransform, id: ConstraintId, c: Dimension, k: Double): DimLabel {
        fun s(p: PointId) = view.toScreen(sketch.point(p))
        val text = formatValue(abs(c.value))

        /** Maß zwischen [a] und [b]; die Maßlinie liegt [offsetA] bzw. [offsetB] in Richtung [n] von den Punkten entfernt. */
        fun linear(a: Vec2, b: Vec2, n: Vec2, offsetA: Double, offsetB: Double): DimLabel {
            val a2 = at(a, n, offsetA); val b2 = at(b, n, offsetB)
            val dir = unit(a2, b2)
            val ext = listOf(a to offsetA, b to offsetB).filter { it.second > GAP * k }.map { (p, o) -> at(p, n, GAP * k) to at(p, n, o + OVERSHOOT * k) }
            return DimLabel(id, text, mid(a2, b2), ext + (a2 to b2), listOf(a2 to neg(dir), b2 to dir))
        }

        /** Parallel zur Strecke a–b, auf der Seite „oben“ (kleineres y). */
        fun aligned(a: Vec2, b: Vec2): DimLabel {
            val d = unit(a, b)
            val n = Vec2(-d.y, d.x).let { if (it.y > 0) neg(it) else it }
            return linear(a, b, n, OFFSET * k, OFFSET * k)
        }
        return when (c) {
            is LengthDim -> sketch.line(c.line).let { l -> aligned(s(l.p1), s(l.p2)) }
            is DistanceDim -> aligned(s(c.a), s(c.b))
            // Das Vorzeichen (Richtung von a nach b) steckt in der Geometrie; angezeigt wird der Betrag.
            is HorizontalDistanceDim -> {
                val a = s(c.a); val b = s(c.b)
                val y = maxOf(a.y, b.y) + OFFSET * k // unterhalb des tieferen Punkts
                linear(a, b, Vec2(0.0, 1.0), y - a.y, y - b.y)
            }
            is VerticalDistanceDim -> {
                val a = s(c.a); val b = s(c.b)
                val x = maxOf(a.x, b.x) + OFFSET * k // rechts vom äußeren Punkt
                linear(a, b, Vec2(1.0, 0.0), x - a.x, x - b.x)
            }
            is PointLineDistanceDim -> {
                // Maßlinie parallel zum Lot, entlang der Linie nach außen versetzt (sonst läge sie z. B. bei einer Ecke auf der Kante)
                val p = s(c.point)
                val l = sketch.line(c.line)
                val l1 = s(l.p1); val l2 = s(l.p2)
                val q = nearestOnSegment(p, l1, l2).point
                val u = unit(l1, l2)
                val m = mid(l1, l2)
                val n = if ((q.x - m.x) * u.x + (q.y - m.y) * u.y >= 0) u else neg(u)
                linear(p, q, n, OFFSET * k, OFFSET * k)
            }
            is RadiusDim, is DiameterDim -> {
                val round: RoundId = if (c is RadiusDim) c.round else (c as DiameterDim).round
                val center = s(sketch.centerOf(round))
                val r = sketch.radiusOf(round) * view.scale
                val d = Vec2(cos(Math.PI / 4), -sin(Math.PI / 4)) // nach rechts oben
                val tip = at(center, d, r)
                val outer = at(center, d, r + 34 * k)
                // Radius: vom Mittelpunkt zum Kreis; Durchmesser: quer durch den Kreis. Die Zahl steht außen an der Verlängerung.
                val from = if (c is RadiusDim) center else at(center, d, -r)
                val arrows = if (c is RadiusDim) listOf(tip to d) else listOf(tip to d, from to neg(d))
                DimLabel(id, (if (c is RadiusDim) "R" else "Ø") + text, at(outer, d, 18.0 * k), listOf(from to outer), arrows)
            }
            is AngleDim -> angle(sketch, view, id, c, k)
        }
    }

    /**
     * Winkelmaß: Maßbogen um den Schnittpunkt der beiden Linien, von der Richtung von a um den Wert gedreht.
     * shortcut: liegt der Schnittpunkt weit außerhalb des Bilds, liegt auch der Bogen dort; bei Bedarf an das nähere Linienende legen.
     */
    private fun angle(sketch: Sketch, view: ViewTransform, id: ConstraintId, c: AngleDim, k: Double): DimLabel {
        val la = sketch.line(c.a); val lb = sketch.line(c.b)
        val a1 = sketch.point(la.p1); val a2 = sketch.point(la.p2); val b1 = sketch.point(lb.p1); val b2 = sketch.point(lb.p2)
        val dax = a2.x - a1.x; val day = a2.y - a1.y; val dbx = b2.x - b1.x; val dby = b2.y - b1.y
        val den = dax * dby - day * dbx
        val pivot = if (abs(den) < 1e-12) a1 else {
            val t = ((b1.x - a1.x) * dby - (b1.y - a1.y) * dbx) / den
            Vec2(a1.x + dax * t, a1.y + day * t)
        }
        val r = ANGLE_RADIUS * k / view.scale
        val start = atan2(day, dax)
        val sweep = Math.toRadians(c.value)
        val steps = 24
        val pts = (0..steps).map { i -> val t = start + sweep * i / steps; view.toScreen(Vec2(pivot.x + r * cos(t), pivot.y + r * sin(t))) }
        val arrows = listOf(pts[0] to unit(pts[1], pts[0]), pts[steps] to unit(pts[steps - 1], pts[steps]))
        val m = start + sweep / 2
        val rl = r + 22 * k / view.scale
        return DimLabel(id, formatValue(abs(c.value)) + "°", view.toScreen(Vec2(pivot.x + rl * cos(m), pivot.y + rl * sin(m))), pts.zipWithNext(), arrows)
    }

    private fun at(p: Vec2, d: Vec2, k: Double) = Vec2(p.x + d.x * k, p.y + d.y * k)
    private fun mid(a: Vec2, b: Vec2) = Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)
    private fun neg(v: Vec2) = Vec2(-v.x, -v.y)
    private fun unit(a: Vec2, b: Vec2): Vec2 {
        val l = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-9)
        return Vec2((b.x - a.x) / l, (b.y - a.y) / l)
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

