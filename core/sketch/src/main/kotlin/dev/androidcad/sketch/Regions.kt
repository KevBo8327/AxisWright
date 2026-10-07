package dev.androidcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Ein Segment einer geschlossenen Kontur in Durchlaufrichtung (Endpunkt = Startpunkt des nächsten Segments). */
public sealed interface LoopSegment {
    /** ID des Skizzenelements (Linie, Bogen, Kreis), aus dem das Segment stammt; Grundlage für stabile Flächennamen am Körper. */
    public val source: Int

    public data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, override val source: Int = 0) : LoopSegment

    /** Bogen von (x1,y1) nach (x2,y2) um (cx,cy); [ccw] = gegen den Uhrzeigersinn. */
    public data class Arc(
        val x1: Double, val y1: Double, val x2: Double, val y2: Double,
        val cx: Double, val cy: Double, val ccw: Boolean, override val source: Int = 0,
    ) : LoopSegment

    public data class Circle(val cx: Double, val cy: Double, val r: Double, override val source: Int = 0) : LoopSegment
}

/** Geschlossene Kontur; [area] ist die (positive) umschlossene Fläche in mm². */
public data class Loop(val segments: List<LoopSegment>, val area: Double)

/** Fläche, die man extrudieren kann: eine Außenkontur und die Löcher darin. */
public data class Region(val outer: Loop, val holes: List<Loop>) {
    /** Nettofläche ohne Löcher (mm²). */
    public val area: Double get() = outer.area - holes.sumOf { it.area }
}

public data class RegionResult(
    val regions: List<Region>,
    /** Hinweise auf Elemente, die keine geschlossene Kontur bilden (offene Enden, Verzweigungen). */
    val problems: List<String>,
)

private class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    fun find(a: Int): Int {
        var r = a
        while (parent[r] != r) r = parent[r]
        var c = a
        while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
        return r
    }
    fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
}

private sealed interface Edge {
    val a: Int // Knoten (Punktklasse) am Anfang
    val b: Int
    val source: Int
    data class L(override val a: Int, override val b: Int, override val source: Int) : Edge
    data class A(override val a: Int, override val b: Int, val center: Int, override val source: Int) : Edge
}

/**
 * Findet die geschlossenen Konturen der Skizze und ordnet Löcher ihren Außenkonturen zu.
 *
 * Unterstützt werden einfache Konturen (jeder Knoten hat genau zwei Kanten) und Vollkreise; Verzweigungen
 * und offene Ketten werden in [RegionResult.problems] gemeldet. Konstruktionselemente zählen nicht.
 * Punkte, die über [Coincident] verbunden sind oder näher als [tolerance] beieinander liegen, gelten als ein Knoten.
 */
public fun Sketch.regions(tolerance: Double = 1e-6): RegionResult {
    val ids = pointIds
    val index = ids.withIndex().associate { it.value to it.index }
    val uf = UnionFind(ids.size)
    for ((_, c) in constraintEntries) if (c is Coincident) uf.union(index.getValue(c.a), index.getValue(c.b))
    for (i in ids.indices) for (j in i + 1 until ids.size) {
        val p = point(ids[i]); val q = point(ids[j])
        if (hypot(p.x - q.x, p.y - q.y) <= tolerance) uf.union(i, j)
    }
    fun node(p: PointId) = uf.find(index.getValue(p))

    // Knotenposition = Mittelwert seiner Punkte → Konturen schließen exakt
    val sum = HashMap<Int, DoubleArray>()
    for (id in ids) {
        val n = node(id); val p = point(id)
        val s = sum.getOrPut(n) { DoubleArray(3) }
        s[0] += p.x; s[1] += p.y; s[2] += 1.0
    }
    fun pos(n: Int): Vec2 = sum.getValue(n).let { Vec2(it[0] / it[2], it[1] / it[2]) }

    val edges = ArrayList<Edge>()
    for (id in lineIds) {
        val l = line(id)
        if (l.construction) continue
        val a = node(l.p1); val b = node(l.p2)
        if (a != b) edges += Edge.L(a, b, id.value)
    }
    for (id in arcIds) {
        val arc = arc(id)
        if (arc.construction) continue
        val a = node(arc.start); val b = node(arc.end)
        if (a != b) edges += Edge.A(a, b, node(arc.center), id.value)
    }

    val problems = ArrayList<String>()
    val loops = ArrayList<Loop>()
    val samples = ArrayList<List<Vec2>>()

    // --- Ketten aus Linien/Bögen ---
    val incident = HashMap<Int, MutableList<Int>>()
    edges.forEachIndexed { i, e -> incident.getOrPut(e.a) { ArrayList() } += i; incident.getOrPut(e.b) { ArrayList() } += i }
    val seen = BooleanArray(edges.size)
    for (start in edges.indices) {
        if (seen[start]) continue
        // Komponente einsammeln
        val comp = ArrayList<Int>()
        val stack = ArrayDeque<Int>().apply { add(start) }
        seen[start] = true
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            comp += e
            for (n in listOf(edges[e].a, edges[e].b)) for (o in incident.getValue(n)) if (!seen[o]) { seen[o] = true; stack.add(o) }
        }
        val nodes = comp.flatMap { listOf(edges[it].a, edges[it].b) }.toSet()
        val bad = nodes.firstOrNull { n -> incident.getValue(n).size != 2 }
        if (bad != null) {
            val p = pos(bad)
            val deg = incident.getValue(bad).size
            problems += if (deg == 1) "Offenes Ende bei (%.2f, %.2f)".format(java.util.Locale.ROOT, p.x, p.y)
            else "Verzweigung bei (%.2f, %.2f)".format(java.util.Locale.ROOT, p.x, p.y)
            continue
        }
        // Kontur ablaufen
        val segs = ArrayList<LoopSegment>()
        val used = BooleanArray(edges.size)
        var cur = edges[comp[0]].a
        var ei = comp[0]
        while (true) {
            used[ei] = true
            val e = edges[ei]
            val forward = e.a == cur
            val from = pos(if (forward) e.a else e.b); val to = pos(if (forward) e.b else e.a)
            segs += when (e) {
                is Edge.L -> LoopSegment.Line(from.x, from.y, to.x, to.y, e.source)
                is Edge.A -> pos(e.center).let { c -> LoopSegment.Arc(from.x, from.y, to.x, to.y, c.x, c.y, ccw = forward, source = e.source) }
            }
            cur = if (forward) e.b else e.a
            ei = incident.getValue(cur).firstOrNull { !used[it] && it in comp } ?: break
        }
        if (segs.size != comp.size) { problems += "Kontur konnte nicht vollständig durchlaufen werden"; continue }
        loops += Loop(segs, exactArea(segs))
        samples += sample(segs)
    }

    // --- Vollkreise ---
    for (id in circleIds) {
        val c = circle(id)
        if (c.construction) continue
        val ctr = point(c.center)
        loops += Loop(listOf(LoopSegment.Circle(ctr.x, ctr.y, c.radius, id.value)), PI * c.radius * c.radius)
        samples += sample(listOf(LoopSegment.Circle(ctr.x, ctr.y, c.radius)))
    }

    // --- Verschachtelung: kleinste umschließende Kontur ist der Elternteil ---
    val parent = IntArray(loops.size) { -1 }
    for (i in loops.indices) {
        var best = -1
        for (j in loops.indices) {
            if (i == j || loops[j].area <= loops[i].area) continue
            if (contains(samples[j], samples[i]) && (best == -1 || loops[j].area < loops[best].area)) best = j
        }
        parent[i] = best
    }
    fun depth(i: Int): Int { var d = 0; var p = parent[i]; while (p != -1) { d++; p = parent[p] }; return d }

    val regions = loops.indices.filter { depth(it) % 2 == 0 }
        .map { o -> Region(loops[o], loops.indices.filter { parent[it] == o && depth(it) % 2 == 1 }.map { loops[it] }) }
        .sortedByDescending { it.outer.area }
    return RegionResult(regions, problems)
}

/** `true`, wenn (x, y) innerhalb der Außenkontur und außerhalb aller Löcher liegt. */
public fun Region.contains(x: Double, y: Double): Boolean =
    inside(sample(outer.segments), x, y) && holes.none { inside(sample(it.segments), x, y) }

/**
 * Die innerste Region, die (x, y) enthält. Ein Auswahlpunkt bleibt stabil, wenn Maße die Skizze verändern –
 * anders als ein Listenindex der Regionen.
 */
public fun Sketch.regionAt(x: Double, y: Double): Region? =
    regions().regions.filter { it.contains(x, y) }.minByOrNull { it.outer.area }

private fun sample(segs: List<LoopSegment>): List<Vec2> {
    val out = ArrayList<Vec2>()
    for (s in segs) when (s) {
        is LoopSegment.Line -> out += Vec2(s.x1, s.y1)
        is LoopSegment.Circle -> for (k in 0 until 64) out += Vec2(s.cx + s.r * cos(2 * PI * k / 64), s.cy + s.r * sin(2 * PI * k / 64))
        is LoopSegment.Arc -> {
            val r = hypot(s.x1 - s.cx, s.y1 - s.cy)
            val a0 = atan2(s.y1 - s.cy, s.x1 - s.cx); val a1 = atan2(s.y2 - s.cy, s.x2 - s.cx)
            var delta = a1 - a0
            if (s.ccw) { while (delta <= 0) delta += 2 * PI } else { while (delta >= 0) delta -= 2 * PI }
            val steps = max(4, ceil(abs(delta) / (PI / 24)).toInt())
            for (k in 0 until steps) out += Vec2(s.cx + r * cos(a0 + delta * k / steps), s.cy + r * sin(a0 + delta * k / steps))
        }
    }
    return out
}

/**
 * Exakte Fläche einer Kontur (Greensche Formel): Linien tragen ½(x₁y₂ − x₂y₁) bei, Bögen
 * ½(r²·Δθ + cx·(y₂−y₁) − cy·(x₂−x₁)) mit Δθ als überstrichenem Winkel (positiv = gegen den Uhrzeigersinn).
 */
private fun exactArea(segs: List<LoopSegment>): Double {
    var a = 0.0
    for (s in segs) when (s) {
        is LoopSegment.Line -> a += s.x1 * s.y2 - s.x2 * s.y1
        is LoopSegment.Circle -> a += 2 * PI * s.r * s.r
        is LoopSegment.Arc -> {
            val r = hypot(s.x1 - s.cx, s.y1 - s.cy)
            var delta = atan2(s.y2 - s.cy, s.x2 - s.cx) - atan2(s.y1 - s.cy, s.x1 - s.cx)
            if (s.ccw) { while (delta <= 0) delta += 2 * PI } else { while (delta >= 0) delta -= 2 * PI }
            a += r * r * delta + s.cx * (s.y2 - s.y1) - s.cy * (s.x2 - s.x1)
        }
    }
    return abs(a) / 2
}

private fun inside(poly: List<Vec2>, x: Double, y: Double): Boolean {
    var c = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val pi = poly[i]; val pj = poly[j]
        if ((pi.y > y) != (pj.y > y) && x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x) c = !c
        j = i
    }
    return c
}

/** `outer` umschließt `inner`, wenn die Mehrzahl der Stützpunkte von `inner` innerhalb liegt. */
private fun contains(outer: List<Vec2>, inner: List<Vec2>): Boolean = inner.count { inside(outer, it.x, it.y) } * 2 > inner.size
