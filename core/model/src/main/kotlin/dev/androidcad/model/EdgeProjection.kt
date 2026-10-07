package dev.androidcad.model

import dev.androidcad.brep.BrepShape
import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.PointId
import dev.androidcad.sketch.RadiusDim
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import kotlin.math.abs

/**
 * Projektion von Körperkanten in eine Skizzenebene: Geraden werden zu festen Konstruktionslinien, Kreise und Kreisbögen zu festen
 * Konstruktionskreisen (Mittelpunkt und Radius). So lassen sich Maße und Constraints an den Körper binden.
 *
 * Eine Kante wird – wie bei Verrundungen und Bohrungen – über ihre **beiden Nachbarflächen** gefunden ([EdgeRef]) und bei jedem
 * Neuaufbau neu berechnet. Geraden haben eine kanonische Richtung (kleinerer Endpunkt zuerst, siehe [HolePlacement.canonical]),
 * damit Maße an den Endpunkten nicht davon abhängen, wie OpenCascade die Kante gerade orientiert.
 */
object EdgeProjection {
    private const val TOL = 1e-4

    /** Die in die Ebene gelegte Kante: entweder [line] (Anfang, Ende) oder [circle] (Mittelpunkt, Radius), in Skizzenkoordinaten. */
    class Geometry(val edge: EdgeRef, val line: Pair<Vec2, Vec2>? = null, val circle: Pair<Vec2, Double>? = null)

    /** Alle Kanten, die an die Fläche [face] grenzen und ganz in [plane] liegen (d. h. der Umriss der Fläche samt Löchern). */
    fun edgesOf(body: BrepShape, plane: Workplane, face: FaceRef): List<Geometry> =
        collect(body, plane) { a, b -> a == face.tag || b == face.tag }

    /** Die Kante [edge] in [plane]; `null`, wenn es sie nicht (mehr) gibt, sie nicht ganz in der Ebene liegt oder nicht eindeutig ist. */
    fun find(body: BrepShape, plane: Workplane, edge: EdgeRef): Geometry? =
        collect(body, plane) { a, b -> a == minOf(edge.a.tag, edge.b.tag) && b == maxOf(edge.a.tag, edge.b.tag) }.firstOrNull()

    private fun collect(body: BrepShape, plane: Workplane, wanted: (Long, Long) -> Boolean): List<Geometry> {
        val pairs = body.edgeFaceTags()
        val candidates = pairs.withIndex().filter { (_, p) -> p.first != 0L && p.second != 0L && p.first != p.second && wanted(p.first, p.second) }
        if (candidates.isEmpty()) return emptyList()
        val infos = body.edges().associateBy { it.id }
        fun v(t: Triple<Double, Double, Double>) = Vec3(t.first, t.second, t.third)
        fun inPlane(p: Vec3) = abs((p - plane.origin).dot(plane.normal)) < TOL
        fun local(p: Vec3): Vec2 = (p - plane.origin).let { Vec2(it.dot(plane.xDir), it.dot(plane.yDir)) }

        val byRef = LinkedHashMap<Pair<Long, Long>, MutableList<Geometry>>()
        for ((index, pair) in candidates) {
            val info = infos[index + 1] ?: continue
            val a = FaceRef.fromTag(pair.first) ?: continue
            val b = FaceRef.fromTag(pair.second) ?: continue
            val edge = EdgeRef.of(a, b)
            val s = v(info.start); val e = v(info.end)
            val center = info.center?.let(::v)
            val g = when {
                info.isLine && inPlane(s) && inPlane(e) -> {
                    val (first, _) = HolePlacement.canonical(s, e)
                    val second = if (first === s) e else s
                    Geometry(edge, line = local(first) to local(second))
                }
                info.isCircle && center != null && inPlane(s) && inPlane(e) && inPlane(center) ->
                    Geometry(edge, circle = local(center) to info.radius)
                else -> null
            }
            if (g != null) byRef.getOrPut(pair) { ArrayList() } += g
        }
        // Nur eindeutige Kanten: gehören mehrere Kanten zu denselben Nachbarflächen, ließe sich nicht sagen, welche gemeint ist.
        return byRef.values.filter { it.size == 1 }.map { it.single() }
    }

    /** Legt für [g] ein festes Konstruktionselement in [sketch] an. */
    fun add(sketch: Sketch, g: Geometry): ProjectedEdge {
        g.line?.let { (p, q) ->
            val a = sketch.addPoint(p.x, p.y); val b = sketch.addPoint(q.x, q.y)
            val line = sketch.addLine(a, b, construction = true)
            sketch.add(Fixed(a, p.x, p.y)); sketch.add(Fixed(b, q.x, q.y))
            return ProjectedEdge(g.edge, line = line)
        }
        val (c, r) = g.circle ?: error("Geometrie ohne Linie und Kreis")
        val center = sketch.addPoint(c.x, c.y)
        val circle = sketch.addCircle(center, r, construction = true)
        sketch.add(Fixed(center, c.x, c.y)); sketch.add(RadiusDim(circle, r))
        return ProjectedEdge(g.edge, circle = circle)
    }

    /** Existiert das Konstruktionselement von [p] noch in [sketch]? (Der Nutzer kann es löschen oder per „Rückgängig“ entfernen.) */
    fun isLive(sketch: Sketch, p: ProjectedEdge): Boolean =
        (p.line != null && sketch.exists(p.line)) || (p.circle != null && sketch.exists(p.circle))

    /** Setzt das Konstruktionselement von [p] auf die neue Lage [g]. `false`, wenn das Element in der Skizze nicht mehr existiert. */
    fun update(sketch: Sketch, p: ProjectedEdge, g: Geometry): Boolean {
        if (p.line != null && g.line != null) {
            if (!sketch.exists(p.line)) return false
            val l = sketch.line(p.line)
            pin(sketch, l.p1, g.line.first); pin(sketch, l.p2, g.line.second)
            return true
        }
        if (p.circle != null && g.circle != null) {
            if (!sketch.exists(p.circle)) return false
            pin(sketch, sketch.circle(p.circle).center, g.circle.first)
            sketch.setRadius(p.circle, g.circle.second)
            val dims = sketch.constraintEntries.filter { (it.constraint as? RadiusDim)?.round == p.circle }
            if (dims.isEmpty()) sketch.add(RadiusDim(p.circle, g.circle.second))
            else dims.forEach { sketch.setDimension(it.id, g.circle.second) }
            return true
        }
        return false // Art der Kante hat sich geändert (Linie ↔ Kreis)
    }

    private fun pin(sketch: Sketch, point: PointId, to: Vec2) {
        sketch.movePoint(point, to.x, to.y)
        val fixed = sketch.constraintEntries.filter { (it.constraint as? Fixed)?.point == point }
        if (fixed.isEmpty()) sketch.add(Fixed(point, to.x, to.y)) else fixed.forEach { sketch.replace(it.id, Fixed(point, to.x, to.y)) }
    }
}
