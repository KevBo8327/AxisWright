package dev.androidcad.sketch

import dev.androidcad.text.Msg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Ergebnis einer Skizzenoperation: [ok] oder ein Grund ([message]), warum sie nicht möglich war. Bei Fehlern bleibt die Skizze unverändert. */
public class OpResult private constructor(public val ok: Boolean, public val message: Msg?) {
    public companion object {
        public fun ok(message: Msg? = null): OpResult = OpResult(true, message)
        public fun ok(key: SketchText, vararg args: Any): OpResult = ok(Msg(key, *args))
        public fun fail(message: Msg): OpResult = OpResult(false, message)
        public fun fail(key: SketchText, vararg args: Any): OpResult = fail(Msg(key, *args))
    }
}

/**
 * Bearbeitungsoperationen an der Skizze: Trimmen, Verlängern, Versatz, Eckenverrundung, Spiegeln und Muster. Rein geometrisch, ohne
 * Oberfläche. Die Operationen ändern die Skizze **direkt**; der Aufrufer löst sie danach und macht die Änderung bei einem Widerspruch
 * rückgängig (z. B. über [Sketch.copy] und [Sketch.restore]).
 *
 * Neu entstehende Punkte werden – wenn möglich – an vorhandene Punkte gebunden (derselbe Punkt) oder per Constraint auf die schneidende
 * Kurve gelegt, damit die Skizze auch nach dem Bearbeiten geschlossen und parametrisch bleibt.
 */
public object SketchOps {
    private const val EPS = 1e-9
    private const val SAME_POINT = 1e-6
    private const val TWO_PI = 2 * PI

    // ---- Kurven und Schnittpunkte ---------------------------------------------------------------

    private sealed interface Curve {
        class Seg(val a: Vec2, val b: Vec2) : Curve
        class Circ(val c: Vec2, val r: Double) : Curve
        class ArcC(val c: Vec2, val r: Double, val a0: Double, val sweep: Double) : Curve
    }

    /** Das Element, an dem geschnitten wird (für die Constraints der neuen Punkte). */
    private sealed interface Other {
        class OnLine(val id: LineId) : Other
        class OnRound(val id: RoundId) : Other
    }

    private class Cut(val p: Vec2, val other: Other)

    private fun curveOf(s: Sketch, id: LineId): Curve.Seg = s.line(id).let { Curve.Seg(s.point(it.p1), s.point(it.p2)) }

    private fun curveOf(s: Sketch, id: RoundId): Curve = when (id) {
        is CircleId -> s.circle(id).let { Curve.Circ(s.point(it.center), it.radius) }
        is ArcId -> s.arc(id).let {
            val c = s.point(it.center); val st = s.point(it.start); val en = s.point(it.end)
            val a0 = atan2(st.y - c.y, st.x - c.x)
            Curve.ArcC(c, hypot(st.x - c.x, st.y - c.y), a0, ccw(a0, atan2(en.y - c.y, en.x - c.x)))
        }
    }

    /** Winkel von [from] nach [to] gegen den Uhrzeigersinn in (0, 2π]. */
    private fun ccw(from: Double, to: Double): Double {
        var d = to - from
        while (d <= 0) d += TWO_PI
        while (d > TWO_PI) d -= TWO_PI
        return d
    }

    private fun angleOf(c: Vec2, p: Vec2) = atan2(p.y - c.y, p.x - c.x)
    private fun dist(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)
    private fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx

    private fun onArc(p: Vec2, arc: Curve.ArcC): Boolean {
        val s = ccw(arc.a0, angleOf(arc.c, p))
        return s <= arc.sweep + 1e-9 || s >= TWO_PI - 1e-9
    }

    private fun segSeg(a: Curve.Seg, b: Curve.Seg): List<Vec2> {
        val d1x = a.b.x - a.a.x; val d1y = a.b.y - a.a.y
        val d2x = b.b.x - b.a.x; val d2y = b.b.y - b.a.y
        val den = cross(d1x, d1y, d2x, d2y)
        if (abs(den) < 1e-12 * hypot(d1x, d1y) * hypot(d2x, d2y)) return emptyList() // parallel
        val ex = b.a.x - a.a.x; val ey = b.a.y - a.a.y
        val t = cross(ex, ey, d2x, d2y) / den
        val u = cross(ex, ey, d1x, d1y) / den
        if (t < -EPS || t > 1 + EPS || u < -EPS || u > 1 + EPS) return emptyList()
        return listOf(Vec2(a.a.x + d1x * t, a.a.y + d1y * t))
    }

    private fun segCircle(s: Curve.Seg, c: Vec2, r: Double): List<Vec2> {
        val dx = s.b.x - s.a.x; val dy = s.b.y - s.a.y
        val fx = s.a.x - c.x; val fy = s.a.y - c.y
        val a = dx * dx + dy * dy
        if (a < 1e-18) return emptyList()
        val b = 2 * (fx * dx + fy * dy)
        val cc = fx * fx + fy * fy - r * r
        val disc = b * b - 4 * a * cc
        if (disc < -1e-9 * max(1.0, abs(b * b))) return emptyList()
        val sq = sqrt(max(0.0, disc))
        val out = ArrayList<Vec2>()
        for (t in listOf((-b - sq) / (2 * a), (-b + sq) / (2 * a)).distinctBy { Math.round(it * 1e9) }) {
            if (t >= -EPS && t <= 1 + EPS) out += Vec2(s.a.x + dx * t, s.a.y + dy * t)
        }
        return out
    }

    private fun circleCircle(c1: Vec2, r1: Double, c2: Vec2, r2: Double): List<Vec2> {
        val d = dist(c1, c2)
        if (d < EPS || d > r1 + r2 + 1e-9 || d < abs(r1 - r2) - 1e-9) return emptyList()
        val a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
        val h = sqrt(max(0.0, r1 * r1 - a * a))
        val bx = c1.x + (c2.x - c1.x) * a / d; val by = c1.y + (c2.y - c1.y) * a / d
        val px = -(c2.y - c1.y) / d; val py = (c2.x - c1.x) / d
        return if (h < 1e-9) listOf(Vec2(bx, by)) else listOf(Vec2(bx + px * h, by + py * h), Vec2(bx - px * h, by - py * h))
    }

    private fun intersect(a: Curve, b: Curve): List<Vec2> {
        fun center(x: Curve): Pair<Vec2, Double>? = when (x) {
            is Curve.Circ -> x.c to x.r
            is Curve.ArcC -> x.c to x.r
            is Curve.Seg -> null
        }
        fun keep(x: Curve, p: Vec2) = x !is Curve.ArcC || onArc(p, x)
        return when {
            a is Curve.Seg && b is Curve.Seg -> segSeg(a, b)
            a is Curve.Seg -> center(b)!!.let { (c, r) -> segCircle(a, c, r).filter { keep(b, it) } }
            b is Curve.Seg -> center(a)!!.let { (c, r) -> segCircle(b, c, r).filter { keep(a, it) } }
            else -> {
                val (c1, r1) = center(a)!!; val (c2, r2) = center(b)!!
                circleCircle(c1, r1, c2, r2).filter { keep(a, it) && keep(b, it) }
            }
        }
    }

    /** Schnittpunkte der Kurve [me] mit allen anderen Elementen der Skizze (außer [skipLine]/[skipRound]). */
    private fun cuts(s: Sketch, me: Curve, skipLine: LineId?, skipRound: RoundId?): List<Cut> {
        val out = ArrayList<Cut>()
        for (id in s.lineIds) if (id != skipLine) intersect(me, curveOf(s, id)).forEach { out += Cut(it, Other.OnLine(id)) }
        for (id in s.circleIds) if (id != skipRound) intersect(me, curveOf(s, id)).forEach { out += Cut(it, Other.OnRound(id)) }
        for (id in s.arcIds) if (id != skipRound) intersect(me, curveOf(s, id)).forEach { out += Cut(it, Other.OnRound(id)) }
        return out
    }

    /** Ein Punkt an der Schnittstelle: ein vorhandener Punkt, wenn dort schon einer liegt, sonst ein neuer, auf die schneidende Kurve gelegt. */
    private fun pointAt(s: Sketch, cut: Cut): PointId {
        val existing = s.pointIds.firstOrNull { dist(s.point(it), cut.p) < SAME_POINT }
        val p = existing ?: s.addPoint(cut.p.x, cut.p.y)
        // Gehört der Punkt nicht ohnehin zum schneidenden Element (Endpunkt, Mittelpunkt …), muss er darauf gebunden werden.
        val own = when (val o = cut.other) {
            is Other.OnLine -> s.line(o.id).let { it.p1 == p || it.p2 == p }
            is Other.OnRound -> when (val id = o.id) {
                is CircleId -> s.circle(id).center == p
                is ArcId -> s.arc(id).let { it.center == p || it.start == p || it.end == p }
            }
        }
        if (!own) {
            val c: Constraint = when (val o = cut.other) {
                is Other.OnLine -> PointOnLine(p, o.id)
                is Other.OnRound -> PointOnRound(p, o.id)
            }
            if (s.constraintEntries.none { it.constraint == c }) s.add(c)
        }
        return p
    }

    /** Nach dem Kürzen ungültige Maße der Linie entfernen (Länge, Gleichlänge, Mittelpunkt). */
    internal fun dropLengthConstraints(s: Sketch, line: LineId) {
        for (e in s.constraintEntries) {
            val c = e.constraint
            val bad = (c is LengthDim && c.line == line) || (c is EqualLength && (c.a == line || c.b == line)) || (c is Midpoint && c.line == line)
            if (bad) s.remove(e.id)
        }
    }

    /** Überträgt die längenunabhängigen Eigenschaften (waagerecht, senkrecht, parallel …) von [from] auf [to]. */
    internal fun copyShape(s: Sketch, from: LineId, to: LineId) {
        for (e in s.constraintEntries.toList()) {
            when (val c = e.constraint) {
                is Horizontal -> if (c.line == from) s.add(Horizontal(to))
                is Vertical -> if (c.line == from) s.add(Vertical(to))
                is Parallel -> if (c.a == from) s.add(Parallel(to, c.b)) else if (c.b == from) s.add(Parallel(c.a, to))
                is Perpendicular -> if (c.a == from) s.add(Perpendicular(to, c.b)) else if (c.b == from) s.add(Perpendicular(c.a, to))
                is Collinear -> if (c.a == from) s.add(Collinear(to, c.b)) else if (c.b == from) s.add(Collinear(c.a, to))
                else -> Unit
            }
        }
    }

    // ---- Trimmen --------------------------------------------------------------------------------

    /**
     * Trimmt [line] an der Stelle [at]: Der Teil zwischen den beiden nächsten Schnittpunkten mit anderen Elementen (bzw. dem Ende) verschwindet.
     * Liegt die Stelle zwischen zwei Schnittpunkten, wird die Linie geteilt (die erste Hälfte behält die Kennung, die zweite ist neu).
     * Ohne Schnittpunkte wird die Linie gelöscht.
     */
    public fun trim(s: Sketch, line: LineId, at: Vec2): OpResult {
        val l = s.line(line)
        val a = s.point(l.p1); val b = s.point(l.p2)
        val len2 = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)
        if (len2 < 1e-18) return OpResult.fail(SketchText.LINE_NO_LENGTH)
        fun tOf(p: Vec2) = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / len2
        val all = cuts(s, curveOf(s, line), line, null).map { tOf(it.p) to it }.filter { it.first > 1e-7 && it.first < 1 - 1e-7 }.sortedBy { it.first }
        val ts = ArrayList<Pair<Double, Cut>>()
        for (c in all) if (ts.isEmpty() || c.first - ts.last().first > 1e-7) ts += c
        val tc = tOf(at).coerceIn(0.0, 1.0)
        val low = ts.lastOrNull { it.first <= tc }?.second
        val up = ts.firstOrNull { it.first > tc }?.second
        when {
            low == null && up == null -> { s.remove(line); s.removeOrphanPoints(); return OpResult.ok(SketchText.LINE_DELETED) }
            low == null -> { s.setLineEndpoints(line, pointAt(s, up!!), l.p2); dropLengthConstraints(s, line) }
            up == null -> { s.setLineEndpoints(line, l.p1, pointAt(s, low)); dropLengthConstraints(s, line) }
            else -> {
                val n1 = pointAt(s, low); val n2 = pointAt(s, up)
                if (n1 == n2) return OpResult.fail(SketchText.INTERSECTIONS_COINCIDE)
                val second = s.addLine(n2, l.p2, l.construction)
                s.setLineEndpoints(line, l.p1, n1)
                dropLengthConstraints(s, line)
                copyShape(s, line, second)
            }
        }
        s.removeOrphanPoints()
        return OpResult.ok()
    }

    /** Trimmt einen Kreis (der Rest wird ein Bogen) oder Bogen an der Stelle [at]; siehe [trim] für Linien. */
    public fun trim(s: Sketch, round: RoundId, at: Vec2): OpResult = when (round) {
        is CircleId -> trimCircle(s, round, at)
        is ArcId -> trimArc(s, round, at)
    }

    private fun trimCircle(s: Sketch, id: CircleId, at: Vec2): OpResult {
        val data = s.circle(id)
        val c = s.point(data.center)
        val angled = cuts(s, curveOf(s, id), null, id).map { angleOf(c, it.p).let { a -> (if (a < 0) a + TWO_PI else a) to it } }.sortedBy { it.first }
        val ts = ArrayList<Pair<Double, Cut>>()
        for (x in angled) if (ts.isEmpty() || x.first - ts.last().first > 1e-7) ts += x
        if (ts.size > 1 && TWO_PI - ts.last().first + ts.first().first < 1e-7) ts.removeAt(ts.size - 1) // Schnittpunkt bei 0 doppelt
        if (ts.isEmpty()) { s.remove(id); s.removeOrphanPoints(); return OpResult.ok(SketchText.CIRCLE_DELETED) }
        if (ts.size == 1) return OpResult.fail(SketchText.TRIM_CIRCLE_NEEDS_TWO)
        val ac = angleOf(c, at).let { if (it < 0) it + TWO_PI else it }
        val prev = ts.lastOrNull { it.first <= ac } ?: ts.last()
        val next = ts.firstOrNull { it.first > ac } ?: ts.first()
        // Es bleibt der Bogen von „next“ gegen den Uhrzeigersinn bis „prev“.
        val start = pointAt(s, next.second); val end = pointAt(s, prev.second)
        if (start == end) return OpResult.fail(SketchText.INTERSECTIONS_COINCIDE)
        val arc = s.addArc(data.center, start, end, data.construction)
        s.remapRound(id, arc)
        s.remove(id)
        s.removeOrphanPoints()
        return OpResult.ok()
    }

    private fun trimArc(s: Sketch, id: ArcId, at: Vec2): OpResult {
        val data = s.arc(id)
        val curve = curveOf(s, id) as Curve.ArcC
        val offs = cuts(s, curve, null, id).map { ccw(curve.a0, angleOf(curve.c, it.p)) to it }
            .filter { it.first > 1e-7 && it.first < curve.sweep - 1e-7 }.sortedBy { it.first }
        val ts = ArrayList<Pair<Double, Cut>>()
        for (x in offs) if (ts.isEmpty() || x.first - ts.last().first > 1e-7) ts += x
        var sc = ccw(curve.a0, angleOf(curve.c, at))
        if (sc > curve.sweep) sc = if (sc - curve.sweep < TWO_PI - sc) curve.sweep else 0.0
        val low = ts.lastOrNull { it.first <= sc }?.second
        val up = ts.firstOrNull { it.first > sc }?.second
        when {
            low == null && up == null -> { s.remove(id); s.removeOrphanPoints(); return OpResult.ok(SketchText.ARC_DELETED) }
            low == null -> s.setArcEnds(id, pointAt(s, up!!), data.end)
            up == null -> s.setArcEnds(id, data.start, pointAt(s, low))
            else -> {
                val n1 = pointAt(s, low); val n2 = pointAt(s, up)
                if (n1 == n2) return OpResult.fail(SketchText.INTERSECTIONS_COINCIDE)
                val second = s.addArc(data.center, n2, data.end, data.construction)
                s.setArcEnds(id, data.start, n1)
                s.add(EqualRadius(id, second))
            }
        }
        s.removeOrphanPoints()
        return OpResult.ok()
    }

    // ---- Verlängern -----------------------------------------------------------------------------

    /** Verlängert den Endpunkt von [line], der [at] näher liegt, bis zum nächsten Element in Verlängerungsrichtung. */
    public fun extend(s: Sketch, line: LineId, at: Vec2): OpResult {
        val l = s.line(line)
        val a = s.point(l.p1); val b = s.point(l.p2)
        val endIsP2 = dist(at, b) <= dist(at, a)
        val e = if (endIsP2) l.p2 else l.p1
        val from = if (endIsP2) a else b
        val ep = s.point(e)
        if (s.elementsUsing(e).any { it != line }) return OpResult.fail(SketchText.END_CONNECTED)
        val len = dist(from, ep)
        if (len < EPS) return OpResult.fail(SketchText.LINE_NO_LENGTH)
        val dx = (ep.x - from.x) / len; val dy = (ep.y - from.y) / len
        val ray = Curve.Seg(ep, Vec2(ep.x + dx * RAY, ep.y + dy * RAY))
        val hit = cuts(s, ray, line, null).map { (it.p.x - ep.x) * dx + (it.p.y - ep.y) * dy to it }.filter { it.first > 1e-7 }.minByOrNull { it.first }
            ?: return OpResult.fail(SketchText.NOTHING_TO_EXTEND_TO)
        s.movePoint(e, hit.second.p.x, hit.second.p.y)
        when (val o = hit.second.other) {
            is Other.OnLine -> s.add(PointOnLine(e, o.id))
            is Other.OnRound -> s.add(PointOnRound(e, o.id))
        }
        dropLengthConstraints(s, line)
        return OpResult.ok()
    }

    /** Verlängert den Endpunkt von [arc], der [at] näher liegt, entlang seines Kreises bis zum nächsten Element. */
    public fun extend(s: Sketch, arc: ArcId, at: Vec2): OpResult {
        val data = s.arc(arc)
        val curve = curveOf(s, arc) as Curve.ArcC
        val st = s.point(data.start); val en = s.point(data.end)
        val endIsEnd = dist(at, en) <= dist(at, st)
        val moving = if (endIsEnd) data.end else data.start
        if (s.elementsUsing(moving).any { it != arc }) return OpResult.fail(SketchText.END_CONNECTED)
        val base = if (endIsEnd) curve.a0 + curve.sweep else curve.a0
        // freier Bereich außerhalb des Bogens: vom Ende weiter gegen den Uhrzeigersinn bzw. vom Start im Uhrzeigersinn
        val free = TWO_PI - curve.sweep
        val full = Curve.Circ(curve.c, curve.r)
        val found = cuts(s, full, null, arc).map { (if (endIsEnd) ccw(base, angleOf(curve.c, it.p)) else ccw(angleOf(curve.c, it.p), base)) to it }
            .filter { it.first > 1e-7 && it.first < free - 1e-7 }.minByOrNull { it.first }
            ?: return OpResult.fail(SketchText.NOTHING_TO_EXTEND_TO)
        s.movePoint(moving, found.second.p.x, found.second.p.y)
        when (val o = found.second.other) {
            is Other.OnLine -> s.add(PointOnLine(moving, o.id))
            is Other.OnRound -> s.add(PointOnRound(moving, o.id))
        }
        return OpResult.ok()
    }

    private const val RAY = 1e7

    // ---- Versatz --------------------------------------------------------------------------------

    /**
     * Versatz eines Kreises oder Bogens um [distance] auf die Seite von [side] (außen bzw. innen); der neue Kreis teilt den Mittelpunkt
     * mit dem alten und bekommt den neuen Radius als Maß.
     */
    public fun offset(s: Sketch, round: RoundId, side: Vec2, distance: Double): OpResult {
        if (!(distance > 0) || !distance.isFinite()) return OpResult.fail(SketchText.DISTANCE_NOT_POSITIVE)
        val curve = curveOf(s, round)
        val (c, r, construction) = when (round) {
            is CircleId -> Triple(s.point(s.circle(round).center), s.circle(round).radius, s.circle(round).construction)
            is ArcId -> Triple(s.point(s.arc(round).center), (curve as Curve.ArcC).r, s.arc(round).construction)
        }
        val outside = dist(side, c) > r
        val r2 = if (outside) r + distance else r - distance
        if (r2 < 1e-6) return OpResult.fail(SketchText.OFFSET_EXCEEDS_RADIUS)
        when (round) {
            is CircleId -> {
                val n = s.addCircle(s.circle(round).center, r2, construction)
                s.add(RadiusDim(n, r2))
            }
            is ArcId -> {
                val data = s.arc(round)
                val st = s.point(data.start); val en = s.point(data.end)
                val k = r2 / r
                val ns = s.addPoint(c.x + (st.x - c.x) * k, c.y + (st.y - c.y) * k)
                val ne = s.addPoint(c.x + (en.x - c.x) * k, c.y + (en.y - c.y) * k)
                val n = s.addArc(data.center, ns, ne, construction)
                s.add(RadiusDim(n, r2))
            }
        }
        return OpResult.ok()
    }

    private class Oriented(val line: LineId, val from: PointId, val to: PointId)

    /** Verbundene Linien ab [start] (an Knoten mit genau zwei Linien und ohne Bögen/Kreise); `closed`, wenn sich die Kette schließt. */
    private fun chainOf(s: Sketch, start: LineId): Pair<List<Oriented>, Boolean> {
        val first = s.line(start)
        val chain = ArrayDeque<Oriented>()
        chain.addLast(Oriented(start, first.p1, first.p2))
        fun next(at: PointId, current: LineId): Oriented? {
            val users = s.elementsUsing(at)
            if (users.size != 2 || users.any { it !is LineId }) return null
            val other = users.first { it != current } as LineId
            val d = s.line(other)
            return if (d.p1 == at) Oriented(other, d.p1, d.p2) else Oriented(other, d.p2, d.p1)
        }
        var closed = false
        // vorwärts
        while (true) {
            val last = chain.last()
            val n = next(last.to, last.line) ?: break
            if (n.line == start) { closed = true; break }
            if (chain.any { it.line == n.line }) break
            chain.addLast(n)
        }
        // rückwärts
        if (!closed) while (true) {
            val firstO = chain.first()
            val n = next(firstO.from, firstO.line) ?: break
            if (chain.any { it.line == n.line }) break
            // gegen die Richtung der Kette: Anfang der neuen Linie ist der Knoten
            chain.addFirst(Oriented(n.line, n.to, n.from))
        }
        return chain.toList() to closed
    }

    /**
     * Versatz von [line] samt der daran anschließenden Linienkette (z. B. ein ganzes Rechteck) um [distance] auf die Seite von [side].
     * Die neuen Linien sind zu den alten parallel und haben den Abstand als Maß; Ecken liegen im Schnittpunkt benachbarter Versatzlinien.
     * Eine einzelne Linie bekommt zusätzlich gleiche Länge; die Enden einer offenen Kette können noch entlang ihrer Linie gleiten.
     */
    public fun offset(s: Sketch, line: LineId, side: Vec2, distance: Double): OpResult {
        if (!(distance > 0) || !distance.isFinite()) return OpResult.fail(SketchText.DISTANCE_NOT_POSITIVE)
        val (chain, closed) = chainOf(s, line)
        val clicked = chain.first { it.line == line }
        val cu = s.point(clicked.from); val cv = s.point(clicked.to)
        val sign = if (cross(cv.x - cu.x, cv.y - cu.y, side.x - cu.x, side.y - cu.y) >= 0) 1.0 else -1.0

        class Off(val ax: Double, val ay: Double, val dx: Double, val dy: Double) // Gerade durch (ax, ay) mit Richtung (dx, dy)
        val offs = chain.map {
            val u = s.point(it.from); val v = s.point(it.to)
            val len = dist(u, v)
            if (len < EPS) return OpResult.fail(SketchText.CHAIN_LINE_NO_LENGTH)
            val dx = (v.x - u.x) / len; val dy = (v.y - u.y) / len
            Off(u.x + sign * -dy * distance, u.y + sign * dx * distance, dx, dy)
        }
        // Eckpunkte: Schnitt der Versatzlinien i und i+1 (parallel: Fußpunkt der Linie i)
        fun corner(i: Int, j: Int): Vec2 {
            val p = offs[i]; val q = offs[j]
            val den = cross(p.dx, p.dy, q.dx, q.dy)
            if (abs(den) < 1e-9) return Vec2(p.ax + p.dx * dist(s.point(chain[i].from), s.point(chain[i].to)), p.ay + p.dy * dist(s.point(chain[i].from), s.point(chain[i].to)))
            val t = cross(q.ax - p.ax, q.ay - p.ay, q.dx, q.dy) / den
            return Vec2(p.ax + p.dx * t, p.ay + p.dy * t)
        }
        val n = chain.size
        val verts = ArrayList<Vec2>() // Anfang jeder Linie (bei offener Kette plus Ende der letzten)
        for (i in 0 until n) {
            verts += if (i == 0 && !closed) Vec2(offs[0].ax, offs[0].ay) else corner((i - 1 + n) % n, i)
        }
        if (!closed) verts += Vec2(offs[n - 1].ax + offs[n - 1].dx * dist(s.point(chain[n - 1].from), s.point(chain[n - 1].to)), offs[n - 1].ay + offs[n - 1].dy * dist(s.point(chain[n - 1].from), s.point(chain[n - 1].to)))
        val pts = verts.map { s.addPoint(it.x, it.y) }
        for (i in 0 until n) {
            val p1 = pts[i]; val p2 = if (closed) pts[(i + 1) % n] else pts[i + 1]
            if (p1 == p2) continue
            val nl = s.addLine(p1, p2, s.line(chain[i].line).construction)
            s.add(Parallel(chain[i].line, nl))
            s.addPointLineDistance(p1, chain[i].line, distance)
            // Nur eine einzelne Linie behält ihre Länge; an Ecken ändern sich die Längen der Versatzlinien.
            if (n == 1) s.add(EqualLength(chain[i].line, nl))
        }
        return OpResult.ok()
    }

    // ---- Eckenverrundung ------------------------------------------------------------------------

    /**
     * Rundet die Ecke [corner], an der genau zwei Linien enden, mit einem Bogen vom Radius [radius]: Die Linien werden um die Tangentenlänge
     * gekürzt, der Bogen ist tangential zu beiden und hat den Radius als Maß. Der Eckpunkt entfällt (samt seiner Maße).
     */
    public fun fillet(s: Sketch, corner: PointId, radius: Double): OpResult {
        if (!(radius > 0) || !radius.isFinite()) return OpResult.fail(SketchText.RADIUS_NOT_POSITIVE)
        val users = s.elementsUsing(corner)
        if (users.size != 2 || users.any { it !is LineId }) return OpResult.fail(SketchText.CORNER_NEEDS_TWO_LINES)
        val l1 = users[0] as LineId; val l2 = users[1] as LineId
        val d1 = s.line(l1); val d2 = s.line(l2)
        val a1 = if (d1.p1 == corner) d1.p2 else d1.p1
        val a2 = if (d2.p1 == corner) d2.p2 else d2.p1
        val c = s.point(corner); val p1 = s.point(a1); val p2 = s.point(a2)
        val len1 = dist(c, p1); val len2 = dist(c, p2)
        if (len1 < EPS || len2 < EPS) return OpResult.fail(SketchText.A_LINE_NO_LENGTH)
        val u1x = (p1.x - c.x) / len1; val u1y = (p1.y - c.y) / len1
        val u2x = (p2.x - c.x) / len2; val u2y = (p2.y - c.y) / len2
        val theta = acos((u1x * u2x + u1y * u2y).coerceIn(-1.0, 1.0)) // Winkel zwischen den Linien
        if (theta < 1e-3 || theta > PI - 1e-3) return OpResult.fail(SketchText.LINES_COLLINEAR)
        val t = radius / tan(theta / 2)
        if (t >= len1 - 1e-9 || t >= len2 - 1e-9) return OpResult.fail(SketchText.RADIUS_TOO_LARGE)
        val t1 = Vec2(c.x + u1x * t, c.y + u1y * t); val t2 = Vec2(c.x + u2x * t, c.y + u2y * t)
        val bx = u1x + u2x; val by = u1y + u2y
        val bl = hypot(bx, by)
        val ctr = Vec2(c.x + bx / bl * radius / sin(theta / 2), c.y + by / bl * radius / sin(theta / 2))

        val pt1 = s.addPoint(t1.x, t1.y); val pt2 = s.addPoint(t2.x, t2.y); val po = s.addPoint(ctr.x, ctr.y)
        s.setLineEndpoints(l1, if (d1.p1 == corner) pt1 else d1.p1, if (d1.p2 == corner) pt1 else d1.p2)
        s.setLineEndpoints(l2, if (d2.p1 == corner) pt2 else d2.p1, if (d2.p2 == corner) pt2 else d2.p2)
        // kurzer Bogen: gegen den Uhrzeigersinn vom Anfang zum Ende
        val ang1 = angleOf(ctr, t1); val ang2 = angleOf(ctr, t2)
        val arc = if (ccw(ang1, ang2) <= PI) s.addArc(po, pt1, pt2) else s.addArc(po, pt2, pt1)
        dropLengthConstraints(s, l1); dropLengthConstraints(s, l2)
        s.remove(corner) // Maße und Constraints am Eckpunkt entfallen
        s.add(TangentLineRound(l1, arc)); s.add(TangentLineRound(l2, arc)); s.add(RadiusDim(arc, radius))
        return OpResult.ok()
    }

    // ---- Spiegeln -------------------------------------------------------------------------------

    /** Die Gerade durch den Ursprung entlang der X- bzw. Y-Achse als feste Konstruktionslinie (eine vorhandene wird wiederverwendet). */
    public fun axisLine(s: Sketch, horizontal: Boolean): LineId {
        existingAxisLine(s, horizontal)?.let { return it }
        val end = if (horizontal) s.addPoint(100.0, 0.0) else s.addPoint(0.0, 100.0)
        s.add(Fixed(end, s.point(end).x, s.point(end).y))
        return s.addLine(s.origin, end, construction = true)
    }

    /** Die vorhandene Achslinie (siehe [axisLine]), ohne eine neue anzulegen. */
    public fun existingAxisLine(s: Sketch, horizontal: Boolean): LineId? =
        s.lineIds.firstOrNull { axisOf(s, it) == horizontal }

    /**
     * `true` für die X-Achse, `false` für die Y-Achse, `null` für jede andere Linie. Achslinien sind feste Konstruktionslinien vom
     * Ursprung aus; die Oberfläche zeichnet sie nicht als Linie, sondern hebt die ganze Achse hervor.
     */
    public fun axisOf(s: Sketch, id: LineId): Boolean? {
        val l = s.line(id)
        if (!l.construction || l.p1 != s.origin) return null
        val fixed = s.constraintEntries.any { (it.constraint as? Fixed)?.let { f -> f.point == l.p2 && f.x != null && f.y != null } == true }
        if (!fixed) return null
        val p = s.point(l.p2)
        return when {
            abs(p.y) < EPS && p.x > 0 -> true
            abs(p.x) < EPS && p.y > 0 -> false
            else -> null
        }
    }

    /** Ob [p] der Endpunkt einer Achslinie ist (wird nicht als Punkt gezeichnet und nicht mit ausgewählt). */
    public fun isAxisPoint(s: Sketch, p: PointId): Boolean = s.lineIds.any { s.line(it).p2 == p && axisOf(s, it) != null }

    // ---- Lage zum Ursprung ----------------------------------------------------------------------

    /** Ob die Punkte [pts] schon über „Zusammenfallend“ oder eine Mittelpunkt-Hilfslinie am Ursprung hängen (oder ihn enthalten). */
    private fun attachedToOrigin(s: Sketch, pts: Collection<PointId>): Boolean = s.origin in pts || s.constraintEntries.any { (_, c) ->
        when (c) {
            is Coincident -> (c.a == s.origin && c.b in pts) || (c.b == s.origin && c.a in pts)
            is Midpoint -> c.point == s.origin && s.exists(c.line) && s.line(c.line).let { it.p1 in pts || it.p2 in pts }
            else -> false
        }
    }

    /** Punkte der Elemente ohne Achslinien. */
    private fun shapePoints(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>): List<PointId> =
        pointsOf(s, shapeLines(s, lines), shapeRounds(s, rounds)).filter { it != s.origin }

    /** Ob [p] ganz fest liegt (Fixed in x und y) – z. B. Enden projizierter Körperkanten. */
    private fun pinned(s: Sketch, p: PointId): Boolean =
        p == s.origin || s.constraintEntries.any { (it.constraint as? Fixed)?.let { f -> f.point == p && f.x != null && f.y != null } == true }

    /** Linien der Form: ohne Achsen und ohne feste Referenzen (projizierte Kanten), die beim Ausrichten nicht mitwandern können. */
    private fun shapeLines(s: Sketch, lines: Collection<LineId>): List<LineId> =
        lines.filter { axisOf(s, it) == null && !(s.line(it).construction && pinned(s, s.line(it).p1) && pinned(s, s.line(it).p2)) }

    /** Kreise/Bögen der Form: ohne feste Konstruktionskreise (projizierte Kreiskanten). */
    private fun shapeRounds(s: Sketch, rounds: Collection<RoundId>): List<RoundId> =
        rounds.filter { r -> !(r is CircleId && s.circle(r).construction && pinned(s, s.circle(r).center)) }

    /**
     * Legt den Punkt der Elemente, der dem Ursprung am nächsten liegt (bei einem Rechteck in der Regel eine Ecke), auf den Ursprung
     * (Zusammenfallend). Die Form verschiebt sich dabei, ihre Maße bleiben.
     */
    public fun cornerToOrigin(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>): OpResult {
        val pts = shapePoints(s, lines, rounds)
        if (pts.isEmpty()) return OpResult.fail(SketchText.SELECT_SHAPE_FOR_ORIGIN)
        if (attachedToOrigin(s, pointsOf(s, shapeLines(s, lines), shapeRounds(s, rounds)))) return OpResult.fail(SketchText.SHAPE_ALREADY_AT_ORIGIN)
        val nearest = pts.minBy { s.point(it).let { p -> hypot(p.x, p.y) } }
        s.add(Coincident(s.origin, nearest))
        return OpResult.ok()
    }

    /** Ob [id] eine feste Referenzlinie ist (projizierte Körperkante: Konstruktionslinie mit fixierten Enden), aber keine Achse. */
    public fun isReferenceLine(s: Sketch, id: LineId): Boolean =
        axisOf(s, id) == null && s.line(id).let { it.construction && pinned(s, it.p1) && pinned(s, it.p2) }

    /** Ob [id] ein fester Referenzkreis ist (projizierte Bohrung bzw. Kreiskante). */
    public fun isReferenceCircle(s: Sketch, id: RoundId): Boolean =
        id is CircleId && s.circle(id).let { it.construction && pinned(s, it.center) }

    /**
     * Mittelpunkt der Form als Punkt: bei einem einzelnen Kreis sein Mittelpunkt, sonst ein neuer Punkt in der Mitte einer neuen
     * Hilfsdiagonale (links unten → rechts oben, siehe [centerOnOrigin]). `null` mit Grund, wenn die Form keine Ausdehnung hat.
     */
    private fun shapeCenter(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>): Pair<PointId?, SketchText?> {
        val myLines = shapeLines(s, lines)
        val myRounds = shapeRounds(s, rounds)
        if (myLines.isEmpty() && myRounds.size == 1 && myRounds.first() is CircleId) return s.circle(myRounds.first() as CircleId).center to null
        val pts = shapePoints(s, myLines, myRounds)
        if (pts.size < 2) return null to SketchText.SELECT_SHAPE_TO_CENTER
        val lo = pts.minBy { s.point(it).let { p -> p.x + p.y } }
        val hi = pts.maxBy { s.point(it).let { p -> p.x + p.y } }
        if (lo == hi || dist(s.point(lo), s.point(hi)) < SAME_POINT) return null to SketchText.SHAPE_NO_EXTENT
        val diagonal = s.addLine(lo, hi, construction = true)
        val a = s.point(lo); val b = s.point(hi)
        val m = s.addPoint((a.x + b.x) / 2, (a.y + b.y) / 2)
        s.add(Midpoint(m, diagonal))
        return m to null
    }

    /**
     * Richtet die Form **mittig zur Kante** [edge] aus: ihre Mitte liegt auf der Mittelsenkrechten der Kante (eine Hilfslinie von der
     * Kantenmitte, senkrecht zur Kante). Der Abstand zur Kante bleibt frei – z. B. für ein Langloch mittig an einer Plattenkante. Liegt
     * die Mitte schon auf der Kante, kommt sie auf die Kantenmitte.
     */
    public fun centerOnEdge(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>, edge: LineId): OpResult {
        val l = s.line(edge)
        val a = s.point(l.p1); val b = s.point(l.p2)
        if (dist(a, b) < SAME_POINT) return OpResult.fail(SketchText.EDGE_NO_LENGTH)
        val (m, why) = shapeCenter(s, lines.filter { it != edge }, rounds)
        if (m == null) return OpResult.fail(why ?: SketchText.NOT_POSSIBLE)
        val mid = Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)
        val q = s.point(m)
        val ux = (b.x - a.x) / dist(a, b); val uy = (b.y - a.y) / dist(a, b)
        val off = (q.x - mid.x) * -uy + (q.y - mid.y) * ux // Abstand der Mitte von der Kante (vorzeichenbehaftet)
        if (abs(off) < 1e-6) { s.add(Midpoint(m, edge)); return OpResult.ok() }
        val e = s.addPoint(mid.x, mid.y)
        s.add(Midpoint(e, edge))
        val normal = s.addLine(e, m, construction = true)
        s.add(Perpendicular(normal, edge))
        return OpResult.ok(SketchText.CENTERED_ON_EDGE)
    }

    /** Wohin [centerOn] die Mitte einer Form legt: auf einen Punkt oder in die Mitte der Strecke zwischen zwei Punkten. */
    public sealed interface CenterTarget {
        public data class Point(val point: PointId) : CenterTarget

        /** Mitte der Strecke [a]–[b], z. B. die Diagonale zwischen zwei gegenüberliegenden Ecken einer projizierten Fläche. */
        public data class Diagonal(val a: PointId, val b: PointId) : CenterTarget
    }

    /**
     * Setzt die Elemente mittig auf den Ursprung: einen einzelnen Kreis mit seinem Mittelpunkt, sonst die Mitte der Diagonale zwischen
     * dem Punkt links unten und dem Punkt rechts oben (bei einem Rechteck seine Mitte) – über eine Hilfslinie, deren Mittelpunkt der
     * Ursprung ist. Parametrisch: Ändern sich die Maße, bleibt die Form mittig.
     */
    public fun centerOnOrigin(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>): OpResult {
        if (attachedToOrigin(s, pointsOf(s, shapeLines(s, lines), shapeRounds(s, rounds))))
            return OpResult.fail(if (shapeLines(s, lines).isEmpty()) SketchText.CIRCLE_ALREADY_AT_ORIGIN else SketchText.SHAPE_ALREADY_AT_ORIGIN)
        return centerOn(s, lines, rounds, CenterTarget.Point(s.origin))
    }

    /**
     * Setzt die Mitte der Elemente auf [target] (siehe [centerOnOrigin], wie dort bestimmt). Bei einer Diagonale als Ziel entstehen zwei
     * Hilfslinien (Ziel- und Formdiagonale) mit einem gemeinsamen Mittelpunkt – die Form bleibt mittig, auch wenn sich das Ziel bewegt
     * (z. B. die projizierte Fläche bei Maßänderungen am Körper).
     */
    public fun centerOn(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>, target: CenterTarget): OpResult {
        val myLines = shapeLines(s, lines)
        val myRounds = shapeRounds(s, rounds)
        // die Mitte der Form: ein einzelner Kreis mit seinem Mittelpunkt, sonst eine Hilfsdiagonale
        val single = if (myLines.isEmpty() && myRounds.size == 1 && myRounds.first() is CircleId) s.circle(myRounds.first() as CircleId).center else null
        val shapeDiagonal: LineId?
        if (single == null) {
            val pts = shapePoints(s, myLines, myRounds)
            if (pts.size < 2) return OpResult.fail(SketchText.SELECT_SHAPE_TO_CENTER)
            val lo = pts.minBy { s.point(it).let { p -> p.x + p.y } }
            val hi = pts.maxBy { s.point(it).let { p -> p.x + p.y } }
            if (lo == hi || dist(s.point(lo), s.point(hi)) < SAME_POINT) return OpResult.fail(SketchText.SHAPE_NO_EXTENT)
            shapeDiagonal = s.addLine(lo, hi, construction = true)
        } else shapeDiagonal = null
        when (target) {
            is CenterTarget.Point -> if (single != null) s.add(Coincident(target.point, single)) else s.add(Midpoint(target.point, shapeDiagonal!!))
            is CenterTarget.Diagonal -> {
                if (target.a == target.b || dist(s.point(target.a), s.point(target.b)) < SAME_POINT) return OpResult.fail(SketchText.FACE_NO_EXTENT)
                val targetDiagonal = s.addLine(target.a, target.b, construction = true)
                if (single != null) s.add(Midpoint(single, targetDiagonal))
                else {
                    val a = s.point(target.a); val b = s.point(target.b)
                    val m = s.addPoint((a.x + b.x) / 2, (a.y + b.y) / 2)
                    s.add(Midpoint(m, targetDiagonal))
                    s.add(Midpoint(m, shapeDiagonal!!))
                }
            }
        }
        return OpResult.ok()
    }

    /**
     * Ob nur noch die **Lage** der Skizze frei ist: 2, wenn die Form bestimmt ist und sich nur noch verschieben lässt, 3, wenn sie sich
     * zusätzlich drehen lässt, sonst `null`. [dof] sind die Freiheitsgrade der gelösten Skizze. Arbeitet auf einer Kopie.
     */
    public fun placementFreedom(s: Sketch, dof: Int): Int? {
        if (dof != 2 && dof != 3) return null
        // Probe: alle beweglichen Punkte gemeinsam verschieben bzw. drehen – bleiben dabei alle Bedingungen erfüllt, ist genau diese
        // Bewegung frei (feste Punkte wie Ursprung, Achsen und projizierte Kanten bleiben, wo sie sind)
        val free = s.pointIds.filter { !pinned(s, it) && !isAxisPoint(s, it) }
        if (free.isEmpty()) return null
        fun keeps(move: (Vec2) -> Vec2): Boolean {
            val c = s.copy()
            val target = free.associateWith { move(c.point(it)) }
            target.forEach { (id, q) -> c.movePoint(id, q.x, q.y) }
            if (!c.solve().isSolved) return false
            return target.all { (id, q) -> dist(c.point(id), q) < 1e-6 }
        }
        if (!keeps { Vec2(it.x + 1.0, it.y) } || !keeps { Vec2(it.x, it.y + 1.0) }) return null
        if (dof == 2) return 2
        val a = s.point(free.first())
        val t = 0.2; val cos = cos(t); val sin = sin(t)
        return if (keeps { Vec2(a.x + cos * (it.x - a.x) - sin * (it.y - a.y), a.y + sin * (it.x - a.x) + cos * (it.y - a.y)) }) 3 else null
    }

    /**
     * Spiegelt die gewählten Elemente an der Linie [axis]. Die Kopien sind über Symmetrie-Constraints an die Originale gebunden (ändert sich das
     * Original, folgt das Spiegelbild); Punkte auf der Achse bleiben gemeinsame Punkte. Kreise und Bögen bekommen zusätzlich gleichen Radius.
     */
    public fun mirror(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>, axis: LineId): OpResult {
        val srcLines = lines.filter { it != axis }
        if (srcLines.isEmpty() && rounds.isEmpty()) return OpResult.fail(SketchText.NOTHING_TO_MIRROR)
        val ad = s.line(axis)
        val a = s.point(ad.p1); val b = s.point(ad.p2)
        val alen = dist(a, b)
        if (alen < EPS) return OpResult.fail(SketchText.MIRROR_AXIS_NO_LENGTH)
        val ux = (b.x - a.x) / alen; val uy = (b.y - a.y) / alen
        fun reflect(p: Vec2): Vec2 {
            val dx = p.x - a.x; val dy = p.y - a.y
            val along = dx * ux + dy * uy
            return Vec2(a.x + 2 * along * ux - dx, a.y + 2 * along * uy - dy)
        }
        val map = LinkedHashMap<PointId, PointId>()
        fun mirrored(p: PointId): PointId = map.getOrPut(p) {
            val q = s.point(p)
            val r = reflect(q)
            if (dist(q, r) < 1e-7) p // auf der Achse: gemeinsamer Punkt
            else s.addPoint(r.x, r.y).also { s.add(Symmetric(p, it, axis)) }
        }
        for (id in srcLines) {
            val l = s.line(id)
            val p1 = mirrored(l.p1); val p2 = mirrored(l.p2)
            if (p1 != p2) s.addLine(p1, p2, l.construction)
        }
        for (id in rounds) when (id) {
            is CircleId -> {
                val c = s.circle(id)
                val n = s.addCircle(mirrored(c.center), c.radius, c.construction)
                s.add(EqualRadius(id, n))
            }
            is ArcId -> {
                val d = s.arc(id)
                // gespiegelt dreht sich der Umlaufsinn um: Start und Ende tauschen die Rollen
                val n = s.addArc(mirrored(d.center), mirrored(d.end), mirrored(d.start), d.construction)
                s.add(EqualRadius(id, n))
            }
        }
        return OpResult.ok()
    }

    // ---- Muster ---------------------------------------------------------------------------------

    /** Alle Punkte der gewählten Elemente, in einer festen Reihenfolge (der erste ist der Bezugspunkt eines Linearmusters). */
    private fun pointsOf(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>): List<PointId> {
        val out = LinkedHashSet<PointId>()
        for (id in lines) s.line(id).let { out += it.p1; out += it.p2 }
        for (id in rounds) when (id) {
            is CircleId -> out += s.circle(id).center
            is ArcId -> s.arc(id).let { out += it.center; out += it.start; out += it.end }
        }
        return out.toList()
    }

    /**
     * Legt eine Kopie der Elemente an; [map] ordnet jedem Punkt der Vorlage seinen Punkt in der Kopie zu. Kreise bekommen den Radius
     * ihres Vorgängers ([prevCircles]: Kreis der Vorlage → Kreis der vorigen Kopie) – eine Kette statt eines Sterns, damit sich die
     * Symbole „=“ nicht alle am Original sammeln. Zurück kommt die Zuordnung für diese Kopie.
     */
    private fun copyElements(
        s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>, map: Map<PointId, PointId>, prevCircles: Map<CircleId, CircleId>,
    ): Map<CircleId, CircleId> {
        val circles = LinkedHashMap<CircleId, CircleId>()
        for (id in lines) {
            val l = s.line(id)
            s.addLine(map.getValue(l.p1), map.getValue(l.p2), l.construction)
        }
        for (id in rounds) when (id) {
            is CircleId -> {
                val c = s.circle(id)
                val n = s.addCircle(map.getValue(c.center), c.radius, c.construction)
                s.add(EqualRadius(prevCircles[id] ?: id, n))
                circles[id] = n
            }
            // der Radius eines Bogens folgt aus seinen Punkten
            is ArcId -> s.arc(id).let { a -> s.addArc(map.getValue(a.center), map.getValue(a.start), map.getValue(a.end), a.construction) }
        }
        return circles
    }

    /**
     * Lineares Muster: [count] Exemplare (mit Original) der gewählten Elemente im Abstand [spacing] mm. Die Richtung ist [direction]
     * (beliebige Länge); mit [along] bleibt sie parallel zu dieser Linie, sonst waagrecht bzw. senkrecht (je nachdem, welcher Achse
     * [direction] näher liegt).
     *
     * Parametrisch: Vom ersten Punkt der Auswahl geht eine Hilfslinie (Konstruktionslinie) mit Längenbemaßung = Abstand; jede Kopie ist
     * über [Translated] an ihren Vorgänger gebunden. Ändert sich das Original oder die Bemaßung, folgen alle Kopien.
     */
    public fun linearPattern(
        s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>, direction: Vec2, count: Int, spacing: Double, along: LineId? = null,
    ): OpResult {
        if (lines.isEmpty() && rounds.isEmpty()) return OpResult.fail(SketchText.NOTHING_TO_PATTERN)
        if (count < 2) return OpResult.fail(SketchText.COUNT_AT_LEAST_2)
        if (!spacing.isFinite() || spacing <= 0.0) return OpResult.fail(SketchText.SPACING_NOT_POSITIVE)
        val len = hypot(direction.x, direction.y)
        if (!len.isFinite() || len < EPS) return OpResult.fail(SketchText.DIRECTION_MISSING)
        val dx = direction.x / len * spacing; val dy = direction.y / len * spacing
        val src = pointsOf(s, lines, rounds)
        val anchor = src.first()
        val a = s.point(anchor)
        val tip = s.addPoint(a.x + dx, a.y + dy)
        val vector = s.addLine(anchor, tip, construction = true)
        when {
            along != null -> s.add(Parallel(vector, along))
            abs(dx) >= abs(dy) -> s.add(Horizontal(vector))
            else -> s.add(Vertical(vector))
        }
        s.addLength(vector, spacing)
        var prev = src.associateWith { it }
        var prevCircles = emptyMap<CircleId, CircleId>()
        for (k in 1 until count) {
            val map = src.associateWith { p ->
                val q = s.point(p)
                s.addPoint(q.x + dx * k, q.y + dy * k).also { s.add(Translated(prev.getValue(p), it, anchor, tip)) }
            }
            prevCircles = copyElements(s, lines, rounds, map, prevCircles)
            prev = map
        }
        return OpResult.ok(SketchText.LINEAR_COPIES, count - 1)
    }

    /**
     * Kreismuster: [count] Exemplare (mit Original) der gewählten Elemente um den Punkt [center] über [angle] Grad (360 = voller Kreis,
     * gleichmäßig verteilt; sonst liegt das letzte Exemplar genau bei [angle]). Jede Kopie ist über [Rotated] an ihren Vorgänger gebunden;
     * der Mittelpunkt selbst bleibt ein gemeinsamer Punkt.
     */
    public fun circularPattern(s: Sketch, lines: Collection<LineId>, rounds: Collection<RoundId>, center: PointId, count: Int, angle: Double = 360.0): OpResult {
        if (lines.isEmpty() && rounds.isEmpty()) return OpResult.fail(SketchText.NOTHING_TO_PATTERN)
        if (count < 2) return OpResult.fail(SketchText.COUNT_AT_LEAST_2)
        if (!angle.isFinite() || angle == 0.0 || abs(angle) > 360.0) return OpResult.fail(SketchText.ANGLE_RANGE_360)
        val step = if (abs(angle) >= 360.0 - 1e-9) angle / count else angle / (count - 1)
        val src = pointsOf(s, lines, rounds)
        if (src.all { it == center }) return OpResult.fail(SketchText.CENTER_ONLY_ELEMENT)
        val c = s.point(center)
        var prev = src.associateWith { it }
        var prevCircles = emptyMap<CircleId, CircleId>()
        for (k in 1 until count) {
            val t = Math.toRadians(step * k)
            val map = src.associateWith { p ->
                if (p == center) p
                else {
                    val q = s.point(p)
                    val x = c.x + cos(t) * (q.x - c.x) - sin(t) * (q.y - c.y)
                    val y = c.y + sin(t) * (q.x - c.x) + cos(t) * (q.y - c.y)
                    s.addPoint(x, y).also { s.add(Rotated(prev.getValue(p), it, center, step)) }
                }
            }
            prevCircles = copyElements(s, lines, rounds, map, prevCircles)
            prev = map
        }
        return OpResult.ok(SketchText.CIRCULAR_COPIES, count - 1)
    }

    // ---- Muster nachträglich ändern ---------------------------------------------------------------

    /**
     * Ein vorhandenes Skizzenmuster, erkannt an seinen [Translated]- bzw. [Rotated]-Ketten. [chains] enthält je Punkt der Vorlage die
     * Punkte aller Exemplare (Index 0 = Original); beim Kreismuster ist der Mittelpunkt [center] kein Teil der Ketten.
     */
    public class SketchPattern internal constructor(
        public val chains: List<List<PointId>>,
        public val center: PointId?,
        /** Drehwinkel zwischen zwei Exemplaren (nur Kreismuster). */
        public val step: Double,
    ) {
        public val circular: Boolean get() = center != null
        public val count: Int get() = chains.first().size
        /** Voller Kreis: die Exemplare sind gleichmäßig über 360° verteilt (sonst liegt das letzte bei einem festen Winkel). */
        public val fullCircle: Boolean get() = circular && abs(abs(step * count) - 360.0) < 1e-6
    }

    /** Schlüssel einer Musterkette: Hilfslinie (Reihe) bzw. Mittelpunkt und Winkel (Kreismuster). */
    private fun patternKey(c: Constraint): Any? = when (c) {
        is Translated -> c.from to c.to
        is Rotated -> c.center to c.degrees
        else -> null
    }

    /**
     * Das Muster, zu dem die gewählten Elemente gehören (Original oder Kopie), oder `null`. Gehören sie zu mehreren Mustern oder ist die
     * Kettenstruktur nicht eindeutig (etwa nach Handarbeit), gibt es kein Ergebnis.
     */
    public fun patternOf(s: Sketch, points: Collection<PointId>, lines: Collection<LineId>, rounds: Collection<RoundId>): SketchPattern? {
        val links = s.constraintEntries.mapNotNull { e -> patternKey(e.constraint)?.let { it to e.constraint } }
        if (links.isEmpty()) return null
        val selected = LinkedHashSet<PointId>(points).apply { addAll(pointsOf(s, lines, rounds)) }
        fun ends(c: Constraint) = when (c) { is Translated -> c.a to c.b; is Rotated -> c.a to c.b; else -> error("kein Muster") }
        val keys = selected.flatMap { p -> links.filter { (_, c) -> ends(c).let { (a, b) -> a == p || b == p } }.map { it.first } }.toSet()
        if (keys.size != 1) return null
        val key = keys.single()
        val next = HashMap<PointId, PointId>(); val prev = HashMap<PointId, PointId>()
        for ((k, c) in links) if (k == key) {
            val (a, b) = ends(c)
            if (next.put(a, b) != null || prev.put(b, a) != null) return null
        }
        val chains = next.keys.filter { it !in prev }.map { root ->
            val chain = mutableListOf(root)
            while (true) { val n = next[chain.last()] ?: break; if (n in chain) return null; chain += n }
            chain.toList()
        }
        if (chains.isEmpty() || chains.any { it.size != chains.first().size }) return null
        val first = links.first { it.first == key }.second
        return if (first is Rotated) SketchPattern(chains, first.center, first.degrees) else SketchPattern(chains, null, 0.0)
    }

    /** Elemente eines Exemplars: alle, deren Punkte nur aus [layer] (und dem Mittelpunkt) stammen. */
    private fun layerElements(s: Sketch, layer: Set<PointId>, center: PointId?): Pair<List<LineId>, List<RoundId>> {
        fun inside(ps: List<PointId>) = ps.all { it in layer || it == center } && ps.any { it in layer }
        val lines = s.lineIds.filter { s.line(it).let { l -> inside(listOf(l.p1, l.p2)) } }
        val rounds = s.circleIds.filter { inside(listOf(s.circle(it).center)) }.map { it as RoundId } +
            s.arcIds.filter { s.arc(it).let { a -> inside(listOf(a.center, a.start, a.end)) } }
        return lines to rounds
    }

    /**
     * Ändert die Anzahl der Exemplare eines Musters auf [count] (mit Original). Weniger: die letzten Kopien entfallen samt ihrer
     * Bedingungen. Mehr: neue Kopien hängen sich an die letzte an. Beim Kreismuster über 360° wird der Winkelschritt neu verteilt,
     * bei einem Teilkreis bleibt der Gesamtwinkel gleich.
     */
    public fun setPatternCount(s: Sketch, pattern: SketchPattern, count: Int): OpResult {
        if (count < 2) return OpResult.fail(SketchText.COUNT_AT_LEAST_2)
        if (count > MAX_PATTERN_COUNT) return OpResult.fail(SketchText.MAX_INSTANCES, MAX_PATTERN_COUNT)
        val old = pattern.count
        if (count == old) return OpResult.fail(SketchText.PATTERN_ALREADY_HAS, count)
        val chains = pattern.chains
        fun layer(k: Int) = chains.map { it[k] }.toSet()
        // Vorlage: Elemente der ersten Kopie, zurückgeführt auf das Original (so zählen nur Elemente, die wirklich kopiert wurden)
        val back = chains.associate { it[1] to it[0] }
        val (copyLines, copyRounds) = layerElements(s, layer(1), pattern.center)
        val (origLines, origRounds) = layerElements(s, layer(0), pattern.center)
        fun orig(p: PointId) = back[p] ?: p
        val srcLines = copyLines.mapNotNull { id ->
            val l = s.line(id)
            origLines.firstOrNull { o -> s.line(o).let { it.p1 == orig(l.p1) && it.p2 == orig(l.p2) } }
        }
        fun circlesAt(all: List<RoundId>, center: PointId) = all.filterIsInstance<CircleId>().filter { s.circle(it).center == center }
        val srcRounds = copyRounds.mapNotNull { id ->
            when (id) {
                is CircleId -> s.circle(id).center.let { c -> circlesAt(origRounds, orig(c)).getOrNull(circlesAt(copyRounds, c).indexOf(id)) }
                is ArcId -> s.arc(id).let { a ->
                    origRounds.filterIsInstance<ArcId>().firstOrNull { o -> s.arc(o).let { it.center == orig(a.center) && it.start == orig(a.start) && it.end == orig(a.end) } }
                }
            }
        }
        if (srcLines.isEmpty() && srcRounds.isEmpty()) return OpResult.fail(SketchText.PATTERN_SOURCE_INCOMPLETE)

        if (count < old) for (k in count until old) layer(k).forEach { s.remove(it) }

        val step = when {
            !pattern.circular -> 0.0
            pattern.fullCircle -> pattern.step * old / count
            else -> pattern.step * (old - 1) / (count - 1)
        }
        if (pattern.circular && step != pattern.step) {
            val c = s.point(pattern.center!!)
            val tail = chains.flatMap { it.drop(1).take(minOf(count, old) - 1) }.toSet()
            for (e in s.constraintEntries) {
                val r = e.constraint as? Rotated ?: continue
                if (r.center == pattern.center && r.degrees == pattern.step && r.b in tail) s.replace(e.id, r.copy(degrees = step))
            }
            // Kopien gleich an ihre neue Stelle legen, damit der Löser nur noch nachfasst
            for (chain in chains) {
                val q = s.point(chain[0])
                for (k in 1 until minOf(count, old)) {
                    val t = Math.toRadians(step * k)
                    s.movePoint(chain[k], c.x + cos(t) * (q.x - c.x) - sin(t) * (q.y - c.y), c.y + sin(t) * (q.x - c.x) + cos(t) * (q.y - c.y))
                }
            }
        }

        if (count > old) {
            val lastRounds = layerElements(s, layer(old - 1), pattern.center).second
            var prevCircles = srcRounds.filterIsInstance<CircleId>().associateWith { src ->
                val c = s.circle(src).center
                val last = chains.first { it[0] == c }.last()
                circlesAt(lastRounds, last)[circlesAt(origRounds, c).indexOf(src)]
            }
            var prev = chains.associate { it[0] to it.last() }
            val vector = if (pattern.circular) null
            else s.constraintEntries.firstNotNullOf { e -> (e.constraint as? Translated)?.takeIf { it.a == chains[0][0] } }
            for (k in old until count) {
                val map = HashMap<PointId, PointId>()
                pattern.center?.let { map[it] = it }
                for (chain in chains) {
                    val from = prev.getValue(chain[0])
                    val q = s.point(from)
                    val n = if (vector != null) {
                        val f = s.point(vector.from); val to = s.point(vector.to)
                        s.addPoint(q.x + to.x - f.x, q.y + to.y - f.y).also { s.add(Translated(from, it, vector.from, vector.to)) }
                    } else {
                        val c = s.point(pattern.center!!); val t = Math.toRadians(step)
                        s.addPoint(c.x + cos(t) * (q.x - c.x) - sin(t) * (q.y - c.y), c.y + sin(t) * (q.x - c.x) + cos(t) * (q.y - c.y))
                            .also { s.add(Rotated(from, it, pattern.center, step)) }
                    }
                    map[chain[0]] = n
                }
                prevCircles = copyElements(s, srcLines, srcRounds, map, prevCircles)
                prev = chains.associate { it[0] to map.getValue(it[0]) }
            }
        }
        val added = count - old
        return if (added > 0) OpResult.ok(SketchText.INSTANCES_ADDED, added, count) else OpResult.ok(SketchText.INSTANCES_REMOVED, -added, count)
    }

    /** Obergrenze für die Anzahl der Exemplare eines Skizzenmusters (die Skizze bleibt damit lösbar und bedienbar). */
    public const val MAX_PATTERN_COUNT: Int = 100
}
