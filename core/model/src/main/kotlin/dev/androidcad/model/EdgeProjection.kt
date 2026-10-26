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

    /**
     * Alle Kanten, die an die Fläche [face] grenzen und ganz in [plane] liegen (d. h. der Umriss der Fläche samt Löchern). [face] darf
     * auch ein Zweitname sein (Fläche mit einer anderen zusammengeführt); die Kanten heißen nach den Hauptnamen ihrer Nachbarflächen.
     */
    fun edgesOf(body: BrepShape, plane: Workplane, face: FaceRef): List<Geometry> {
        val tags = body.faceTags()
        val names = body.facesWithTag(face.tag).map { tags[it - 1] }.toSet()
        return collect(body, plane, body.edgeFaceTags().withIndex().mapNotNull { (i, p) ->
            val a = FaceRef.fromTag(p.first); val b = FaceRef.fromTag(p.second)
            if (a != null && b != null && p.first != p.second && (p.first in names || p.second in names)) i + 1 to EdgeRef.of(a, b) else null
        })
    }

    /**
     * Die Kante [edge] in [plane]; `null`, wenn es sie nicht (mehr) gibt, sie nicht ganz in der Ebene liegt oder nicht eindeutig ist.
     * Gesucht wird auch über Zweitnamen (wie bei Verrundungen): führt eine Maßänderung die Nachbarfläche mit einer anderen zusammen,
     * bleibt die Kante auffindbar.
     */
    fun find(body: BrepShape, plane: Workplane, edge: EdgeRef): Geometry? =
        if (edge.a == edge.b) null else collect(body, plane, body.edgesBetween(edge.a.tag, edge.b.tag).map { it to edge }).firstOrNull()

    /** Geometrie der Kanten [candidates] (Kanten-ID, Name), soweit sie ganz in [plane] liegen und ihr Name genau eine solche Kante trifft. */
    private fun collect(body: BrepShape, plane: Workplane, candidates: List<Pair<Int, EdgeRef>>): List<Geometry> {
        if (candidates.isEmpty()) return emptyList()
        val infos = body.edges().associateBy { it.id }
        fun v(t: Triple<Double, Double, Double>) = Vec3(t.first, t.second, t.third)
        fun inPlane(p: Vec3) = abs((p - plane.origin).dot(plane.normal)) < TOL
        fun local(p: Vec3): Vec2 = (p - plane.origin).let { Vec2(it.dot(plane.xDir), it.dot(plane.yDir)) }

        val byRef = LinkedHashMap<EdgeRef, MutableList<Geometry>>()
        for ((id, edge) in candidates) {
            val info = infos[id] ?: continue
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
            if (g != null) byRef.getOrPut(edge) { ArrayList() } += g
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
