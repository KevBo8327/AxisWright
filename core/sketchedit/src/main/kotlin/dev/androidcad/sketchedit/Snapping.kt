package dev.androidcad.sketchedit

import dev.androidcad.sketch.ArcId
import dev.androidcad.sketch.CircleId
import dev.androidcad.sketch.ConstraintId
import dev.androidcad.sketch.LineId
import dev.androidcad.sketch.PointId
import dev.androidcad.sketch.RoundId
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin

/** Auswählbares Element der Skizze (oder ein Maß, das über sein Etikett angetippt wurde). */
public sealed interface Sel {
    public data class P(val id: PointId) : Sel
    public data class L(val id: LineId) : Sel
    public data class C(val id: CircleId) : Sel
    public data class A(val id: ArcId) : Sel
    public data class K(val id: ConstraintId) : Sel
}

public enum class SnapKind { POINT, MIDPOINT, ON_LINE, ON_ROUND, AXIS_X, AXIS_Y, GRID, FREE }

/**
 * Ergebnis des Fangens. [position] ist der Skizzenpunkt; die Zielverweise sagen, woran gefangen wurde
 * (daraus entstehen später die automatischen Constraints).
 */
public data class SnapResult(
    val position: Vec2,
    val kind: SnapKind,
    val point: PointId? = null,
    val line: LineId? = null,
    val round: RoundId? = null,
)

public data class SnapOptions(val grid: Boolean = true, val tolerancePx: Double = 10.0)

internal data class Nearest(val distance: Double, val point: Vec2)

internal fun nearestOnSegment(p: Vec2, a: Vec2, b: Vec2): Nearest {
    val dx = b.x - a.x; val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
    val q = Vec2(a.x + t * dx, a.y + t * dy)
    return Nearest(hypot(p.x - q.x, p.y - q.y), q)
}

internal fun nearestOnCircle(p: Vec2, c: Vec2, r: Double): Nearest {
    val d = hypot(p.x - c.x, p.y - c.y)
    if (d < 1e-12) return Nearest(r, Vec2(c.x + r, c.y))
    return Nearest(kotlin.math.abs(d - r), Vec2(c.x + (p.x - c.x) / d * r, c.y + (p.y - c.y) / d * r))
}

/** Winkel von [from] nach [to] gegen den Uhrzeigersinn in (0, 2π]. */
internal fun ccwSweep(from: Double, to: Double): Double {
    var d = to - from
    while (d <= 0) d += 2 * PI
    while (d > 2 * PI) d -= 2 * PI
    return d
}

internal fun nearestOnArc(p: Vec2, c: Vec2, start: Vec2, end: Vec2): Nearest {
    val r = hypot(start.x - c.x, start.y - c.y)
    val a0 = atan2(start.y - c.y, start.x - c.x)
    val sweep = ccwSweep(a0, atan2(end.y - c.y, end.x - c.x))
    val ap = atan2(p.y - c.y, p.x - c.x)
    if (ccwSweep(a0, ap) <= sweep) return nearestOnCircle(p, c, r)
    // außerhalb des Bogenbereichs: näherer Endpunkt
    val ds = hypot(p.x - start.x, p.y - start.y); val de = hypot(p.x - end.x, p.y - end.y)
    return if (ds <= de) Nearest(ds, start) else Nearest(de, end)
}

public object Snapping {
    /**
     * Fängt [world] an der Skizze. Reihenfolge: vorhandene Punkte → Linienmitten → Kurven → Achsen → Raster.
     * @param exclude Punkte, die nicht als Ziel gelten (z. B. gerade gezogene).
     */
    public fun snap(
        sketch: Sketch,
        world: Vec2,
        view: ViewTransform,
        options: SnapOptions = SnapOptions(),
        exclude: Set<PointId> = emptySet(),
    ): SnapResult {
        val tol = options.tolerancePx / view.scale

        // 1. vorhandene Punkte
        var bestPoint: PointId? = null; var bestD = tol
        for (id in sketch.pointIds) {
            if (id in exclude) continue
            val p = sketch.point(id)
            val d = hypot(p.x - world.x, p.y - world.y)
            if (d <= bestD) { bestD = d; bestPoint = id }
        }
        bestPoint?.let { return SnapResult(sketch.point(it), SnapKind.POINT, point = it) }

        // 2. Linienmitten
        var bestMid: LineId? = null; bestD = tol
        for (id in sketch.lineIds) {
            val l = sketch.line(id)
            if (l.p1 in exclude || l.p2 in exclude) continue
            val a = sketch.point(l.p1); val b = sketch.point(l.p2)
            val m = Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)
            val d = hypot(m.x - world.x, m.y - world.y)
            if (d <= bestD) { bestD = d; bestMid = id }
        }
        bestMid?.let {
            val l = sketch.line(it); val a = sketch.point(l.p1); val b = sketch.point(l.p2)
            return SnapResult(Vec2((a.x + b.x) / 2, (a.y + b.y) / 2), SnapKind.MIDPOINT, line = it)
        }

        // 3. auf Kurven
        var curve: SnapResult? = null; bestD = tol
        for (id in sketch.lineIds) {
            val l = sketch.line(id)
            if (l.p1 in exclude || l.p2 in exclude) continue
            val n = nearestOnSegment(world, sketch.point(l.p1), sketch.point(l.p2))
            if (n.distance <= bestD) { bestD = n.distance; curve = SnapResult(n.point, SnapKind.ON_LINE, line = id) }
        }
        for (id in sketch.circleIds) {
            val c = sketch.circle(id)
            if (c.center in exclude) continue
            val n = nearestOnCircle(world, sketch.point(c.center), c.radius)
            if (n.distance <= bestD) { bestD = n.distance; curve = SnapResult(n.point, SnapKind.ON_ROUND, round = id) }
        }
        for (id in sketch.arcIds) {
            val a = sketch.arc(id)
            if (a.center in exclude || a.start in exclude || a.end in exclude) continue
            val n = nearestOnArc(world, sketch.point(a.center), sketch.point(a.start), sketch.point(a.end))
            if (n.distance <= bestD) { bestD = n.distance; curve = SnapResult(n.point, SnapKind.ON_ROUND, round = id) }
        }
        curve?.let { return it }

        val step = if (options.grid) view.gridStep() else null
        fun grid(v: Double) = if (step != null) (v / step).roundToLong() * step else v

        // 4. Achsen durch den Ursprung
        if (kotlin.math.abs(world.y) <= tol) return SnapResult(Vec2(grid(world.x), 0.0), SnapKind.AXIS_X)
        if (kotlin.math.abs(world.x) <= tol) return SnapResult(Vec2(0.0, grid(world.y)), SnapKind.AXIS_Y)

        // 5. Raster
        return if (step != null) SnapResult(Vec2(grid(world.x), grid(world.y)), SnapKind.GRID)
        else SnapResult(world, SnapKind.FREE)
    }
}

public object HitTest {
    /**
     * Das Element unter [world] (innerhalb [tolerance] mm): Punkte haben Vorrang vor Linien/Kreisen/Bögen.
     * Maßetiketten werden vom Editor vorher geprüft.
     */
    public fun hit(sketch: Sketch, world: Vec2, tolerance: Double): Sel? {
        var bestP: PointId? = null; var best = tolerance
        // Endpunkte der Achslinien sind unsichtbar und nicht wählbar
        val hidden = sketch.lineIds.filter { SketchOps.axisOf(sketch, it) != null }.map { sketch.line(it).p2 }.toSet()
        for (id in sketch.pointIds) {
            if (id in hidden) continue
            val p = sketch.point(id)
            val d = hypot(p.x - world.x, p.y - world.y)
            if (d <= best) { best = d; bestP = id }
        }
        bestP?.let { return Sel.P(it) }
        hitCurve(sketch, world, tolerance)?.let { return it }
        // Der Ursprung ist ein wichtiger Bezug: wo sonst nichts liegt, etwas großzügiger treffen
        val o = sketch.point(sketch.origin)
        return if (hypot(o.x - world.x, o.y - world.y) <= ORIGIN_REACH * tolerance) Sel.P(sketch.origin) else null
    }

    private const val ORIGIN_REACH = 1.8

    /** Wie [hit], aber nur Linien, Kreise und Bögen – auch dort, wo ein Punkt liegt (z. B. beim Antippen eines Linienendes). */
    public fun hitCurve(sketch: Sketch, world: Vec2, tolerance: Double): Sel? {
        var result: Sel? = null; var best = tolerance
        for (id in sketch.lineIds) {
            val l = sketch.line(id)
            val d = nearestOnSegment(world, sketch.point(l.p1), sketch.point(l.p2)).distance
            if (d <= best) { best = d; result = Sel.L(id) }
        }
        for (id in sketch.circleIds) {
            val c = sketch.circle(id)
            val d = nearestOnCircle(world, sketch.point(c.center), c.radius).distance
            if (d <= best) { best = d; result = Sel.C(id) }
        }
        for (id in sketch.arcIds) {
            val a = sketch.arc(id)
            val d = nearestOnArc(world, sketch.point(a.center), sketch.point(a.start), sketch.point(a.end)).distance
            if (d <= best) { best = d; result = Sel.A(id) }
        }
        return result
    }

    /** Alle Elemente, die vollständig im Rechteck liegen (Fensterauswahl). */
    public fun inBox(sketch: Sketch, a: Vec2, b: Vec2): Set<Sel> {
        val x0 = minOf(a.x, b.x); val x1 = maxOf(a.x, b.x); val y0 = minOf(a.y, b.y); val y1 = maxOf(a.y, b.y)
        fun inside(p: Vec2) = p.x in x0..x1 && p.y in y0..y1
        val out = LinkedHashSet<Sel>()
        for (id in sketch.pointIds) if (inside(sketch.point(id))) out += Sel.P(id)
        for (id in sketch.lineIds) sketch.line(id).let { if (inside(sketch.point(it.p1)) && inside(sketch.point(it.p2))) out += Sel.L(id) }
        for (id in sketch.circleIds) sketch.circle(id).let {
            val c = sketch.point(it.center)
            if (inside(Vec2(c.x - it.radius, c.y - it.radius)) && inside(Vec2(c.x + it.radius, c.y + it.radius))) out += Sel.C(id)
        }
        for (id in sketch.arcIds) sketch.arc(id).let {
            if (inside(sketch.point(it.center)) && inside(sketch.point(it.start)) && inside(sketch.point(it.end))) out += Sel.A(id)
        }
        return out
    }
}

/** Punkt auf dem Kreis um [c] mit Radius [r] im Winkel [angle] (Bogenmaß). */
internal fun onCircle(c: Vec2, r: Double, angle: Double): Vec2 = Vec2(c.x + r * cos(angle), c.y + r * sin(angle))
