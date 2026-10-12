package dev.androidcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ---- Teilen -------------------------------------------------------------------------------------

private const val SPLIT_EPS = 1e-7

/**
 * Teilt [line] an der Stelle [at] (auf die Linie projiziert) in zwei Linien mit gemeinsamem Punkt; die erste behält die Kennung.
 * Lage und Form bleiben: Richtungsbedingungen (waagerecht, parallel …) gelten für beide Teile, sonst hält „Kollinear“ sie auf einer
 * Geraden. Längenmaße der alten Linie entfallen (wie beim Trimmen); Punkte auf der Linie und Tangenten wandern zum passenden Teil.
 */
public fun SketchOps.split(s: Sketch, line: LineId, at: Vec2): OpResult {
    val l = s.line(line)
    val a = s.point(l.p1); val b = s.point(l.p2)
    val len2 = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)
    if (len2 < 1e-18) return OpResult.fail("Die Linie hat keine Länge")
    fun tOf(p: Vec2) = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / len2
    val t = tOf(at)
    if (t < SPLIT_EPS || t > 1 - SPLIT_EPS) return OpResult.fail("Tippe zwischen die Enden der Linie")
    val m = s.addPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    val second = s.addLine(m, l.p2, l.construction)
    s.setLineEndpoints(line, l.p1, m)
    dropLengthConstraints(s, line)
    val before = s.constraintEntries.size
    copyShape(s, line, second)
    // Ohne übertragene Richtung könnten die Teile am gemeinsamen Punkt abknicken.
    if (s.constraintEntries.size == before) s.add(Collinear(line, second))
    for (e in s.constraintEntries) {
        val moved = when (val c = e.constraint) {
            is PointOnLine -> if (c.line == line && tOf(s.point(c.point)) > t) c.copy(line = second) else null
            is TangentLineRound -> if (c.line == line && tOf(s.point(s.centerOf(c.round))) > t) c.copy(line = second) else null
            else -> null
        }
        if (moved != null) s.replace(e.id, moved)
    }
    return OpResult.ok()
}

/**
 * Teilt einen Bogen an der Stelle [at] in zwei Bögen mit gemeinsamem Punkt (gleicher Mittelpunkt, damit auch gleicher Radius).
 * Ein Kreis wird mit einem Tipp in zwei Halbkreise geteilt: an der angetippten Stelle und gegenüber.
 */
public fun SketchOps.split(s: Sketch, round: RoundId, at: Vec2): OpResult = when (round) {
    is CircleId -> splitCircle(s, round, at)
    is ArcId -> splitArc(s, round, at)
}

private fun splitArc(s: Sketch, id: ArcId, at: Vec2): OpResult {
    val data = s.arc(id)
    val c = s.point(data.center); val st = s.point(data.start)
    val r = hypot(st.x - c.x, st.y - c.y)
    val a0 = angleOf(c, st)
    val sweep = ccwAngle(a0, angleOf(c, s.point(data.end)))
    val sc = ccwAngle(a0, angleOf(c, at))
    if (sc < SPLIT_EPS || sc > sweep - SPLIT_EPS) return OpResult.fail("Tippe zwischen die Enden des Bogens")
    val m = s.addPoint(c.x + r * cos(a0 + sc), c.y + r * sin(a0 + sc))
    val second = s.addArc(data.center, m, data.end, data.construction)
    s.setArcEnds(id, data.start, m)
    // Radiusmaße bleiben am ersten Teil – der gemeinsame Punkt hält den zweiten auf demselben Radius.
    moveTouching(s, id, second)
    return OpResult.ok()
}

private fun splitCircle(s: Sketch, id: CircleId, at: Vec2): OpResult {
    val data = s.circle(id)
    val c = s.point(data.center)
    if (hypot(at.x - c.x, at.y - c.y) < 1e-12) return OpResult.fail("Tippe auf den Kreis")
    val ang = angleOf(c, at)
    val r = data.radius
    // shortcut: der Gegenpunkt ist nicht gebunden (die Teilungspunkte können auf dem Kreis wandern); bei Bedarf über eine Hilfslinie binden.
    val m1 = s.addPoint(c.x + r * cos(ang), c.y + r * sin(ang))
    val m2 = s.addPoint(c.x - r * cos(ang), c.y - r * sin(ang))
    val first = s.addArc(data.center, m1, m2, data.construction)
    val second = s.addArc(data.center, m2, m1, data.construction)
    s.remapRound(id, first)
    s.remove(id)
    moveTouching(s, first, second)
    return OpResult.ok()
}

/** Punkte auf der Kurve und Tangenten von [from] auf [to] umhängen, wenn ihr Berührpunkt auf dem Bogen [to] liegt. */
private fun moveTouching(s: Sketch, from: ArcId, to: ArcId) {
    val d = s.arc(to)
    val c = s.point(d.center)
    val r = s.radiusOf(to)
    val a0 = angleOf(c, s.point(d.start))
    val sweep = ccwAngle(a0, angleOf(c, s.point(d.end)))
    fun onTo(p: Vec2) = ccwAngle(a0, angleOf(c, p)).let { it < sweep || it > 2 * PI - SPLIT_EPS }
    fun foot(line: LineId): Vec2 {
        val l = s.line(line)
        val p = s.point(l.p1); val q = s.point(l.p2)
        val len2 = (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
        val t = if (len2 < 1e-18) 0.0 else ((c.x - p.x) * (q.x - p.x) + (c.y - p.y) * (q.y - p.y)) / len2
        return Vec2(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
    }
    // Berührpunkt zweier Kreise: der Punkt in Richtung (bzw. Gegenrichtung) des anderen Mittelpunkts, der auf dem anderen Kreis liegt
    fun touch(other: RoundId): Vec2 {
        val o = s.point(s.centerOf(other)); val ro = s.radiusOf(other)
        val ang = angleOf(c, o)
        return listOf(ang, ang + PI).map { Vec2(c.x + r * cos(it), c.y + r * sin(it)) }.minBy { abs(hypot(it.x - o.x, it.y - o.y) - ro) }
    }
    for (e in s.constraintEntries) {
        val moved = when (val k = e.constraint) {
            is PointOnRound -> if (k.round == from && onTo(s.point(k.point))) k.copy(round = to) else null
            is TangentLineRound -> if (k.round == from && onTo(foot(k.line))) k.copy(round = to) else null
            is TangentRoundRound -> when {
                k.a == from && onTo(touch(k.b)) -> k.copy(a = to)
                k.b == from && onTo(touch(k.a)) -> k.copy(b = to)
                else -> null
            }
            else -> null
        }
        if (moved != null) s.replace(e.id, moved)
    }
}

private fun angleOf(c: Vec2, p: Vec2) = atan2(p.y - c.y, p.x - c.x)

/** Winkel von [from] nach [to] gegen den Uhrzeigersinn in (0, 2π]. */
private fun ccwAngle(from: Double, to: Double): Double {
    var d = to - from
    while (d <= 0) d += 2 * PI
    while (d > 2 * PI) d -= 2 * PI
    return d
}
