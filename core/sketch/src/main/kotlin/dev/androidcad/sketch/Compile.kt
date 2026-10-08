package dev.androidcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Wertet Residuen einer Gleichungsgruppe aus und schreibt `count` Werte ab `off` nach `out`. */
internal fun interface ResidualFn {
    fun eval(x: DoubleArray, out: DoubleArray, off: Int)
}

/**
 * Eine Gruppe von [count] Gleichungen `f(x) = 0`, die nur von den Variablen [vars] abhängen.
 * [owner] = Constraint-ID (>0) bzw. negativ für interne Gleichungen (z. B. Bogen-Konsistenz).
 */
internal class Eq(val owner: Int, vars: IntArray, val count: Int, val fn: ResidualFn) {
    /** Eindeutige Variablenindizes (doppelte würden bei der numerischen Ableitung doppelt zählen). */
    val vars: IntArray = vars.distinct().toIntArray()
}

internal const val OWNER_DRAG = -1_000_000

/** Zuordnung Skizzenelement → Index im Variablenvektor: pro Punkt (x, y), pro Kreis der Radius. */
internal class VarMap(sketch: Sketch) {
    val xIndex = HashMap<Int, Int>()
    val yIndex = HashMap<Int, Int>()
    val rIndex = HashMap<Int, Int>()
    val n: Int

    init {
        var k = 0
        for (id in sketch.pointIds) { xIndex[id.value] = k++; yIndex[id.value] = k++ }
        for (id in sketch.circleIds) rIndex[id.value] = k++
        n = k
    }

    fun x(p: PointId): Int = xIndex.getValue(p.value)
    fun y(p: PointId): Int = yIndex.getValue(p.value)

    fun initial(sketch: Sketch): DoubleArray {
        val v = DoubleArray(n)
        for (id in sketch.pointIds) { val p = sketch.point(id); v[x(id)] = p.x; v[y(id)] = p.y }
        for (id in sketch.circleIds) v[rIndex.getValue(id.value)] = sketch.circle(id).radius
        return v
    }

    fun apply(sketch: Sketch, v: DoubleArray) {
        for (id in sketch.pointIds) sketch.setPointInternal(id, v[x(id)], v[y(id)])
        for (id in sketch.circleIds) sketch.setRadiusInternal(id, max(v[rIndex.getValue(id.value)], 1e-9))
    }
}

internal class CompiledSystem(val map: VarMap, val eqs: List<Eq>) {
    val m: Int = eqs.sumOf { it.count }
}

private const val EPS = 1e-12

private fun wrapAngle(a: Double): Double {
    var r = a
    while (r > PI) r -= 2 * PI
    while (r <= -PI) r += 2 * PI
    return r
}

/** Übersetzt die Skizze in ein Gleichungssystem. Deaktivierte Constraints gibt es (noch) nicht. */
internal fun compile(sketch: Sketch): CompiledSystem {
    val map = VarMap(sketch)
    val eqs = ArrayList<Eq>()
    for (entry in sketch.constraintEntries) eqs += compileConstraint(sketch, map, entry.id.value, entry.constraint)
    // Ein Bogen hat keinen eigenen Radius: Start und Ende müssen gleich weit vom Mittelpunkt entfernt sein.
    for (id in sketch.arcIds) {
        val a = sketch.arc(id)
        val cx = map.x(a.center); val cy = map.y(a.center); val sx = map.x(a.start); val sy = map.y(a.start)
        val ex = map.x(a.end); val ey = map.y(a.end)
        eqs += Eq(-id.value, intArrayOf(cx, cy, sx, sy, ex, ey), 1) { x, o, k ->
            o[k] = hypot(x[sx] - x[cx], x[sy] - x[cy]) - hypot(x[ex] - x[cx], x[ey] - x[cy])
        }
    }
    return CompiledSystem(map, eqs)
}

/** Zugriff auf Mittelpunkt und Radius eines Kreises oder Bogens. */
private class Round(val cx: Int, val cy: Int, val extra: IntArray, val radius: (DoubleArray) -> Double) {
    val vars: IntArray get() = intArrayOf(cx, cy) + extra
}

private fun round(sketch: Sketch, map: VarMap, id: RoundId): Round = when (id) {
    is CircleId -> {
        val c = sketch.circle(id)
        val r = map.rIndex.getValue(id.value)
        Round(map.x(c.center), map.y(c.center), intArrayOf(r)) { x -> x[r] }
    }
    is ArcId -> {
        val a = sketch.arc(id)
        val cx = map.x(a.center); val cy = map.y(a.center); val sx = map.x(a.start); val sy = map.y(a.start)
        Round(cx, cy, intArrayOf(sx, sy)) { x -> hypot(x[sx] - x[cx], x[sy] - x[cy]) }
    }
}

/** Punkte, die ein Kreis/Bogen selbst trägt (Bogenenden). */
private fun roundPoints(sketch: Sketch, id: RoundId): List<PointId> = when (id) {
    is ArcId -> sketch.arc(id).let { listOf(it.start, it.end) }
    is CircleId -> sketch.constraintEntries.mapNotNull { (it.constraint as? PointOnRound)?.takeIf { c -> c.round == id }?.point }
}

/**
 * Ein Punkt aus [candidates], der auf allen [rounds] liegt – als Bogenende oder über [PointOnRound] –, also die Berührstelle einer
 * Tangential-Bedingung; `null`, wenn es keinen gibt.
 */
private fun touchPoint(sketch: Sketch, candidates: List<PointId>, rounds: List<RoundId>): PointId? {
    val onRound = sketch.constraintEntries.mapNotNull { (it.constraint as? PointOnRound)?.let { c -> c.point to c.round } }.toSet()
    fun lies(p: PointId, r: RoundId) = (r is ArcId && sketch.arc(r).let { it.start == p || it.end == p }) || (p to r) in onRound
    return candidates.firstOrNull { p -> rounds.all { lies(p, it) } }
}

private fun compileConstraint(sketch: Sketch, map: VarMap, owner: Int, c: Constraint): List<Eq> {
    fun eq(vars: IntArray, count: Int, fn: ResidualFn) = listOf(Eq(owner, vars, count, fn))

    return when (c) {
        is Coincident -> {
            val ax = map.x(c.a); val ay = map.y(c.a); val bx = map.x(c.b); val by = map.y(c.b)
            eq(intArrayOf(ax, ay, bx, by), 2) { x, o, k -> o[k] = x[ax] - x[bx]; o[k + 1] = x[ay] - x[by] }
        }
        is PointOnLine -> {
            val l = sketch.line(c.line)
            val px = map.x(c.point); val py = map.y(c.point)
            val ax = map.x(l.p1); val ay = map.y(l.p1); val bx = map.x(l.p2); val by = map.y(l.p2)
            eq(intArrayOf(px, py, ax, ay, bx, by), 1) { x, o, k ->
                val dx = x[bx] - x[ax]; val dy = x[by] - x[ay]
                o[k] = (dx * (x[py] - x[ay]) - dy * (x[px] - x[ax])) / max(hypot(dx, dy), EPS)
            }
        }
        is PointOnRound -> {
            val r = round(sketch, map, c.round)
            val px = map.x(c.point); val py = map.y(c.point)
            eq(intArrayOf(px, py) + r.vars, 1) { x, o, k -> o[k] = hypot(x[px] - x[r.cx], x[py] - x[r.cy]) - r.radius(x) }
        }
        is Midpoint -> {
            val l = sketch.line(c.line)
            val px = map.x(c.point); val py = map.y(c.point)
            val ax = map.x(l.p1); val ay = map.y(l.p1); val bx = map.x(l.p2); val by = map.y(l.p2)
            eq(intArrayOf(px, py, ax, ay, bx, by), 2) { x, o, k ->
                o[k] = x[px] - (x[ax] + x[bx]) / 2; o[k + 1] = x[py] - (x[ay] + x[by]) / 2
            }
        }
        is Horizontal -> {
            val l = sketch.line(c.line); val ay = map.y(l.p1); val by = map.y(l.p2)
            eq(intArrayOf(ay, by), 1) { x, o, k -> o[k] = x[ay] - x[by] }
        }
        is Vertical -> {
            val l = sketch.line(c.line); val ax = map.x(l.p1); val bx = map.x(l.p2)
            eq(intArrayOf(ax, bx), 1) { x, o, k -> o[k] = x[ax] - x[bx] }
        }
        is HorizontalPoints -> {
            val ay = map.y(c.a); val by = map.y(c.b)
            eq(intArrayOf(ay, by), 1) { x, o, k -> o[k] = x[ay] - x[by] }
        }
        is VerticalPoints -> {
            val ax = map.x(c.a); val bx = map.x(c.b)
            eq(intArrayOf(ax, bx), 1) { x, o, k -> o[k] = x[ax] - x[bx] }
        }
        is Parallel, is Perpendicular -> {
            val (la, lb) = if (c is Parallel) sketch.line(c.a) to sketch.line(c.b) else (c as Perpendicular).let { sketch.line(it.a) to sketch.line(it.b) }
            val a1x = map.x(la.p1); val a1y = map.y(la.p1); val a2x = map.x(la.p2); val a2y = map.y(la.p2)
            val b1x = map.x(lb.p1); val b1y = map.y(lb.p1); val b2x = map.x(lb.p2); val b2y = map.y(lb.p2)
            val parallel = c is Parallel
            eq(intArrayOf(a1x, a1y, a2x, a2y, b1x, b1y, b2x, b2y), 1) { x, o, k ->
                val d1x = x[a2x] - x[a1x]; val d1y = x[a2y] - x[a1y]; val d2x = x[b2x] - x[b1x]; val d2y = x[b2y] - x[b1y]
                val n = max(hypot(d1x, d1y) * hypot(d2x, d2y), EPS)
                o[k] = (if (parallel) d1x * d2y - d1y * d2x else d1x * d2x + d1y * d2y) / n
            }
        }
        is Collinear -> {
            val la = sketch.line(c.a); val lb = sketch.line(c.b)
            val a1x = map.x(la.p1); val a1y = map.y(la.p1); val a2x = map.x(la.p2); val a2y = map.y(la.p2)
            val b1x = map.x(lb.p1); val b1y = map.y(lb.p1); val b2x = map.x(lb.p2); val b2y = map.y(lb.p2)
            eq(intArrayOf(a1x, a1y, a2x, a2y, b1x, b1y, b2x, b2y), 2) { x, o, k ->
                val d1x = x[a2x] - x[a1x]; val d1y = x[a2y] - x[a1y]; val d2x = x[b2x] - x[b1x]; val d2y = x[b2y] - x[b1y]
                val l1 = max(hypot(d1x, d1y), EPS)
                o[k] = (d1x * d2y - d1y * d2x) / (l1 * max(hypot(d2x, d2y), EPS))
                o[k + 1] = (d1x * (x[b1y] - x[a1y]) - d1y * (x[b1x] - x[a1x])) / l1
            }
        }
        is EqualLength -> {
            val la = sketch.line(c.a); val lb = sketch.line(c.b)
            val a1x = map.x(la.p1); val a1y = map.y(la.p1); val a2x = map.x(la.p2); val a2y = map.y(la.p2)
            val b1x = map.x(lb.p1); val b1y = map.y(lb.p1); val b2x = map.x(lb.p2); val b2y = map.y(lb.p2)
            eq(intArrayOf(a1x, a1y, a2x, a2y, b1x, b1y, b2x, b2y), 1) { x, o, k ->
                o[k] = hypot(x[a2x] - x[a1x], x[a2y] - x[a1y]) - hypot(x[b2x] - x[b1x], x[b2y] - x[b1y])
            }
        }
        is EqualRadius -> {
            val ra = round(sketch, map, c.a); val rb = round(sketch, map, c.b)
            eq(ra.vars + rb.vars, 1) { x, o, k -> o[k] = ra.radius(x) - rb.radius(x) }
        }
        is Concentric -> {
            val ra = round(sketch, map, c.a); val rb = round(sketch, map, c.b)
            eq(intArrayOf(ra.cx, ra.cy, rb.cx, rb.cy), 2) { x, o, k -> o[k] = x[ra.cx] - x[rb.cx]; o[k + 1] = x[ra.cy] - x[rb.cy] }
        }
        is TangentLineRound -> {
            val l = sketch.line(c.line); val r = round(sketch, map, c.round)
            val touch = touchPoint(sketch, listOf(l.p1, l.p2), listOf(c.round))
            if (touch != null) {
                // Linie endet auf der Kurve: Radius zum Endpunkt ⟂ Linie. Gleichwertig, aber auch in erster Ordnung eindeutig – sonst
                // könnte das Ende scheinbar frei entlang der Tangente gleiten (falsche Freiheitsgrade, schlechte Konvergenz).
                val other = if (touch == l.p1) l.p2 else l.p1
                val px = map.x(touch); val py = map.y(touch); val qx = map.x(other); val qy = map.y(other)
                return listOf(Eq(owner, intArrayOf(px, py, qx, qy, r.cx, r.cy), 1) { x, o, k ->
                    val dx = x[qx] - x[px]; val dy = x[qy] - x[py]
                    o[k] = ((x[px] - x[r.cx]) * dx + (x[py] - x[r.cy]) * dy) / max(hypot(dx, dy), EPS)
                })
            }
            val ax = map.x(l.p1); val ay = map.y(l.p1); val bx = map.x(l.p2); val by = map.y(l.p2)
            eq(intArrayOf(ax, ay, bx, by) + r.vars, 1) { x, o, k ->
                val dx = x[bx] - x[ax]; val dy = x[by] - x[ay]
                val dist = abs(dx * (x[r.cy] - x[ay]) - dy * (x[r.cx] - x[ax])) / max(hypot(dx, dy), EPS)
                o[k] = dist - r.radius(x)
            }
        }
        is TangentRoundRound -> {
            val ra = round(sketch, map, c.a); val rb = round(sketch, map, c.b)
            val touch = touchPoint(sketch, roundPoints(sketch, c.a), listOf(c.a, c.b))
            if (touch != null) {
                // gemeinsamer Punkt auf beiden Kurven: Berührung ⇔ Mittelpunkte und Punkt auf einer Geraden
                val px = map.x(touch); val py = map.y(touch)
                return listOf(Eq(owner, intArrayOf(px, py, ra.cx, ra.cy, rb.cx, rb.cy), 1) { x, o, k ->
                    val ux = x[ra.cx] - x[px]; val uy = x[ra.cy] - x[py]
                    o[k] = (ux * (x[rb.cy] - x[py]) - uy * (x[rb.cx] - x[px])) / max(hypot(ux, uy), EPS)
                })
            }
            eq(ra.vars + rb.vars, 1) { x, o, k ->
                val d = hypot(x[ra.cx] - x[rb.cx], x[ra.cy] - x[rb.cy])
                val r1 = ra.radius(x); val r2 = rb.radius(x)
                o[k] = if (c.internal) d - c.sign * (r1 - r2) else d - (r1 + r2)
            }
        }
        is Symmetric -> {
            val l = sketch.line(c.axis)
            val ax = map.x(c.a); val ay = map.y(c.a); val bx = map.x(c.b); val by = map.y(c.b)
            val p1x = map.x(l.p1); val p1y = map.y(l.p1); val p2x = map.x(l.p2); val p2y = map.y(l.p2)
            eq(intArrayOf(ax, ay, bx, by, p1x, p1y, p2x, p2y), 2) { x, o, k ->
                val dx = x[p2x] - x[p1x]; val dy = x[p2y] - x[p1y]; val len = max(hypot(dx, dy), EPS)
                val mx = (x[ax] + x[bx]) / 2; val my = (x[ay] + x[by]) / 2
                o[k] = (dx * (my - x[p1y]) - dy * (mx - x[p1x])) / len          // Mittelpunkt auf der Achse
                o[k + 1] = (dx * (x[bx] - x[ax]) + dy * (x[by] - x[ay])) / len  // a→b senkrecht zur Achse
            }
        }
        is Translated -> {
            val ax = map.x(c.a); val ay = map.y(c.a); val bx = map.x(c.b); val by = map.y(c.b)
            val fx = map.x(c.from); val fy = map.y(c.from); val tx = map.x(c.to); val ty = map.y(c.to)
            eq(intArrayOf(ax, ay, bx, by, fx, fy, tx, ty), 2) { x, o, k ->
                o[k] = x[bx] - x[ax] - (x[tx] - x[fx])
                o[k + 1] = x[by] - x[ay] - (x[ty] - x[fy])
            }
        }
        is Rotated -> {
            val ax = map.x(c.a); val ay = map.y(c.a); val bx = map.x(c.b); val by = map.y(c.b)
            val cx = map.x(c.center); val cy = map.y(c.center)
            val t = Math.toRadians(c.degrees); val cos = kotlin.math.cos(t); val sin = kotlin.math.sin(t)
            eq(intArrayOf(ax, ay, bx, by, cx, cy), 2) { x, o, k ->
                val dx = x[ax] - x[cx]; val dy = x[ay] - x[cy]
                o[k] = x[bx] - (x[cx] + cos * dx - sin * dy)
                o[k + 1] = x[by] - (x[cy] + sin * dx + cos * dy)
            }
        }
        is Fixed -> {
            val px = map.x(c.point); val py = map.y(c.point)
            val fx = c.x; val fy = c.y
            val count = (if (fx != null) 1 else 0) + (if (fy != null) 1 else 0)
            if (count == 0) emptyList() else eq(intArrayOf(px, py), count) { x, o, k ->
                var i = k
                if (fx != null) o[i++] = x[px] - fx
                if (fy != null) o[i] = x[py] - fy
            }
        }
        is DistanceDim -> {
            val ax = map.x(c.a); val ay = map.y(c.a); val bx = map.x(c.b); val by = map.y(c.b)
            eq(intArrayOf(ax, ay, bx, by), 1) { x, o, k -> o[k] = hypot(x[bx] - x[ax], x[by] - x[ay]) - c.value }
        }
        is HorizontalDistanceDim -> {
            val ax = map.x(c.a); val bx = map.x(c.b)
            eq(intArrayOf(ax, bx), 1) { x, o, k -> o[k] = x[bx] - x[ax] - c.value }
        }
        is VerticalDistanceDim -> {
            val ay = map.y(c.a); val by = map.y(c.b)
            eq(intArrayOf(ay, by), 1) { x, o, k -> o[k] = x[by] - x[ay] - c.value }
        }
        is LengthDim -> {
            val l = sketch.line(c.line)
            val ax = map.x(l.p1); val ay = map.y(l.p1); val bx = map.x(l.p2); val by = map.y(l.p2)
            eq(intArrayOf(ax, ay, bx, by), 1) { x, o, k -> o[k] = hypot(x[bx] - x[ax], x[by] - x[ay]) - c.value }
        }
        is PointLineDistanceDim -> {
            val l = sketch.line(c.line)
            val px = map.x(c.point); val py = map.y(c.point)
            val ax = map.x(l.p1); val ay = map.y(l.p1); val bx = map.x(l.p2); val by = map.y(l.p2)
            eq(intArrayOf(px, py, ax, ay, bx, by), 1) { x, o, k ->
                val dx = x[bx] - x[ax]; val dy = x[by] - x[ay]
                o[k] = c.side * (dx * (x[py] - x[ay]) - dy * (x[px] - x[ax])) / max(hypot(dx, dy), EPS) - c.value
            }
        }
        is RadiusDim -> {
            val r = round(sketch, map, c.round)
            eq(r.vars, 1) { x, o, k -> o[k] = r.radius(x) - c.value }
        }
        is DiameterDim -> {
            val r = round(sketch, map, c.round)
            eq(r.vars, 1) { x, o, k -> o[k] = 2 * r.radius(x) - c.value }
        }
        is AngleDim -> {
            val la = sketch.line(c.a); val lb = sketch.line(c.b)
            val a1x = map.x(la.p1); val a1y = map.y(la.p1); val a2x = map.x(la.p2); val a2y = map.y(la.p2)
            val b1x = map.x(lb.p1); val b1y = map.y(lb.p1); val b2x = map.x(lb.p2); val b2y = map.y(lb.p2)
            val target = Math.toRadians(c.value)
            eq(intArrayOf(a1x, a1y, a2x, a2y, b1x, b1y, b2x, b2y), 1) { x, o, k ->
                val d1x = x[a2x] - x[a1x]; val d1y = x[a2y] - x[a1y]; val d2x = x[b2x] - x[b1x]; val d2y = x[b2y] - x[b1y]
                o[k] = wrapAngle(atan2(d1x * d2y - d1y * d2x, d1x * d2x + d1y * d2y) - target)
            }
        }
    }
}
