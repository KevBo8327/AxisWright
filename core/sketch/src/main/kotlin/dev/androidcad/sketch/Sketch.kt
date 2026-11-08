package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

public data class LineData(val p1: PointId, val p2: PointId, val construction: Boolean = false)
public data class CircleData(val center: PointId, val radius: Double, val construction: Boolean = false)

/** Kreisbogen um [center] von [start] nach [end], **gegen den Uhrzeigersinn**. Der Radius ergibt sich aus [start]. */
public data class ArcData(val center: PointId, val start: PointId, val end: PointId, val construction: Boolean = false)

/** Text mit Basispunkt [location], Texthöhe [height] (mm) und Drehung [angle] in Grad. */
public data class TextData(val location: PointId, val text: String, val height: Double, val angle: Double = 0.0, val construction: Boolean = false)

public data class ConstraintEntry(val id: ConstraintId, val constraint: Constraint)

/**
 * Eine 2D-Skizze: Punkte, Linien, Kreise, Bögen und Constraints.
 *
 * Das Modell ist rein geometrisch und unabhängig von der Oberfläche. Koordinaten sind die **Startwerte** für
 * den Solver ([solve], [beginDrag]); er verschiebt Elemente minimal, bis alle Constraints erfüllt sind.
 * Der [origin] ist ein fest verankerter Punkt bei (0, 0).
 */
public class Sketch {
    private var nextId = 1
    private val points = LinkedHashMap<Int, Vec2>()
    private val lines = LinkedHashMap<Int, LineData>()
    private val circles = LinkedHashMap<Int, CircleData>()
    private val arcs = LinkedHashMap<Int, ArcData>()
    private val texts = LinkedHashMap<Int, TextData>()
    private val constraints = LinkedHashMap<Int, Constraint>()
    private val standalone = LinkedHashSet<Int>()   // gewollte Einzelpunkte (Werkzeug „Punkt“)

    public val origin: PointId

    init {
        origin = addPoint(0.0, 0.0)
        add(Fixed(origin, 0.0, 0.0))
    }

    // ---- Abfragen -------------------------------------------------------------------------------

    public val pointIds: List<PointId> get() = points.keys.map(::PointId)
    public val lineIds: List<LineId> get() = lines.keys.map(::LineId)
    public val circleIds: List<CircleId> get() = circles.keys.map(::CircleId)
    public val arcIds: List<ArcId> get() = arcs.keys.map(::ArcId)
    public val textIds: List<TextId> get() = texts.keys.map(::TextId)
    /** Gewollte Einzelpunkte (Werkzeug „Punkt“); sie bleiben auch ohne Element erhalten ([removeOrphanPoints]). */
    public val standalonePointIds: List<PointId> get() = standalone.map(::PointId)
    public val constraintEntries: List<ConstraintEntry> get() = constraints.map { ConstraintEntry(ConstraintId(it.key), it.value) }

    public fun point(id: PointId): Vec2 = points[id.value] ?: error("Unbekannter Punkt $id")
    public fun line(id: LineId): LineData = lines[id.value] ?: error("Unbekannte Linie $id")
    public fun circle(id: CircleId): CircleData = circles[id.value] ?: error("Unbekannter Kreis $id")
    public fun arc(id: ArcId): ArcData = arcs[id.value] ?: error("Unbekannter Bogen $id")
    public fun text(id: TextId): TextData = texts[id.value] ?: error("Unbekannter Text $id")
    public fun constraint(id: ConstraintId): Constraint = constraints[id.value] ?: error("Unbekanntes Constraint $id")

    public fun exists(id: PointId): Boolean = id.value in points
    public fun exists(id: LineId): Boolean = id.value in lines
    public fun exists(id: TextId): Boolean = id.value in texts
    public fun isStandalone(id: PointId): Boolean = id.value in standalone
    public fun exists(id: RoundId): Boolean = when (id) {
        is CircleId -> id.value in circles
        is ArcId -> id.value in arcs
    }

    /** Länge der Linie. */
    public fun length(id: LineId): Double {
        val l = line(id)
        return dist(point(l.p1), point(l.p2))
    }

    /** Radius eines Kreises (gespeicherter Wert) bzw. Bogens (Abstand Mittelpunkt–Start). */
    public fun radiusOf(id: RoundId): Double = when (id) {
        is CircleId -> circle(id).radius
        is ArcId -> arc(id).let { dist(point(it.center), point(it.start)) }
    }

    public fun centerOf(id: RoundId): PointId = when (id) {
        is CircleId -> circle(id).center
        is ArcId -> arc(id).center
    }

    // ---- Elemente anlegen -----------------------------------------------------------------------

    public fun addPoint(x: Double, y: Double): PointId {
        val id = nextId++
        points[id] = Vec2(x, y)
        return PointId(id)
    }

    public fun addLine(p1: PointId, p2: PointId, construction: Boolean = false): LineId {
        require(exists(p1) && exists(p2)) { "Punkt existiert nicht" }
        require(p1 != p2) { "Linie braucht zwei verschiedene Punkte" }
        val id = nextId++
        lines[id] = LineData(p1, p2, construction)
        return LineId(id)
    }

    /** Legt zwei neue Endpunkte und die Linie dazwischen an. */
    public fun addLine(x1: Double, y1: Double, x2: Double, y2: Double, construction: Boolean = false): LineId =
        addLine(addPoint(x1, y1), addPoint(x2, y2), construction)

    public fun addCircle(center: PointId, radius: Double, construction: Boolean = false): CircleId {
        require(exists(center)) { "Mittelpunkt existiert nicht" }
        require(radius > 0) { "Radius muss positiv sein" }
        val id = nextId++
        circles[id] = CircleData(center, radius, construction)
        return CircleId(id)
    }

    public fun addCircle(cx: Double, cy: Double, radius: Double, construction: Boolean = false): CircleId =
        addCircle(addPoint(cx, cy), radius, construction)

    public fun addArc(center: PointId, start: PointId, end: PointId, construction: Boolean = false): ArcId {
        require(exists(center) && exists(start) && exists(end)) { "Punkt existiert nicht" }
        require(center != start && center != end) { "Bogen: Mittelpunkt muss sich von Start/Ende unterscheiden" }
        val id = nextId++
        arcs[id] = ArcData(center, start, end, construction)
        return ArcId(id)
    }

    public fun addArc(cx: Double, cy: Double, sx: Double, sy: Double, ex: Double, ey: Double, construction: Boolean = false): ArcId =
        addArc(addPoint(cx, cy), addPoint(sx, sy), addPoint(ex, ey), construction)

    public fun addText(location: PointId, text: String, height: Double, angle: Double = 0.0, construction: Boolean = false): TextId {
        require(exists(location)) { "Punkt existiert nicht" }
        require(text.isNotEmpty()) { "Text darf nicht leer sein" }
        require(height > 0 && height.isFinite()) { "Texthöhe muss positiv sein" }
        require(angle.isFinite()) { "Winkel ungültig" }
        val id = nextId++
        texts[id] = TextData(location, text, height, angle, construction)
        return TextId(id)
    }

    public fun addText(x: Double, y: Double, text: String, height: Double, angle: Double = 0.0, construction: Boolean = false): TextId =
        addText(addPoint(x, y), text, height, angle, construction)

    /** Vier Linien mit gemeinsamen Eckpunkten, horizontal/vertikal ausgerichtet. Ecken: links unten, rechts unten, rechts oben, links oben. */
    public fun addRectangle(x1: Double, y1: Double, x2: Double, y2: Double): Rectangle {
        val bl = addPoint(x1, y1); val br = addPoint(x2, y1); val tr = addPoint(x2, y2); val tl = addPoint(x1, y2)
        val bottom = addLine(bl, br); val right = addLine(br, tr); val top = addLine(tr, tl); val left = addLine(tl, bl)
        add(Horizontal(bottom)); add(Vertical(right)); add(Horizontal(top)); add(Vertical(left))
        return Rectangle(listOf(bl, br, tr, tl), listOf(bottom, right, top, left))
    }

    public class Rectangle(public val corners: List<PointId>, public val edges: List<LineId>)

    // ---- Elemente ändern / entfernen ------------------------------------------------------------

    /** Markiert [id] als gewollten Einzelpunkt, der nicht als Waise entfernt wird. */
    public fun markStandalone(id: PointId) {
        require(exists(id)) { "Unbekannter Punkt" }
        standalone += id.value
    }

    public fun movePoint(id: PointId, x: Double, y: Double) {
        require(exists(id)) { "Unbekannter Punkt" }
        points[id.value] = Vec2(x, y)
    }

    public fun setRadius(id: CircleId, radius: Double) {
        require(radius > 0) { "Radius muss positiv sein" }
        circles[id.value] = circle(id).copy(radius = radius)
    }

    /** Setzt die Endpunkte einer Linie neu (z. B. nach dem Trimmen); die Constraints der Linie bleiben. */
    public fun setLineEndpoints(id: LineId, p1: PointId, p2: PointId) {
        require(exists(p1) && exists(p2) && p1 != p2) { "Linie braucht zwei verschiedene, vorhandene Punkte" }
        lines[id.value] = line(id).copy(p1 = p1, p2 = p2)
    }

    /** Setzt Start- und Endpunkt eines Bogens neu (der Mittelpunkt bleibt). */
    public fun setArcEnds(id: ArcId, start: PointId, end: PointId) {
        val a = arc(id)
        require(exists(start) && exists(end) && start != a.center && end != a.center && start != end) { "Ungültige Bogenpunkte" }
        arcs[id.value] = a.copy(start = start, end = end)
    }

    /** Setzt den Textinhalt, die Höhe und Drehung neu. */
    public fun setText(id: TextId, text: String, height: Double, angle: Double) {
        require(exists(id)) { "Unbekannter Text $id" }
        require(text.isNotEmpty()) { "Text darf nicht leer sein" }
        require(height > 0 && height.isFinite()) { "Texthöhe muss positiv sein" }
        require(angle.isFinite()) { "Winkel ungültig" }
        texts[id.value] = text(id).copy(text = text, height = height, angle = angle)
    }

    /** Setzt den Basispunkt eines Textes neu. */
    public fun setTextLocation(id: TextId, location: PointId) {
        require(exists(id)) { "Unbekannter Text $id" }
        require(exists(location)) { "Punkt existiert nicht" }
        val oldLoc = text(id).location
        texts[id.value] = text(id).copy(location = location)
        removeOrphanPoints()
    }

    /** Alle Linien, Kreise, Bögen und Texte, die [point] als Endpunkt, Mittelpunkt, Start, Ende oder Basispunkt verwenden. */
    public fun elementsUsing(point: PointId): List<Any> =
        lines.filterValues { it.p1 == point || it.p2 == point }.keys.map(::LineId) +
            circles.filterValues { it.center == point }.keys.map(::CircleId) +
            arcs.filterValues { it.center == point || it.start == point || it.end == point }.keys.map(::ArcId) +
            texts.filterValues { it.location == point }.keys.map(::TextId)

    /** Hängt alle Constraints, die [old] verwenden, an [new] um (z. B. wenn ein Kreis durch einen Bogen ersetzt wird). */
    public fun remapRound(old: RoundId, new: RoundId) {
        for (e in constraints.entries.toList()) {
            val mapped = e.value.withRound(old, new)
            if (mapped !== e.value) constraints[e.key] = mapped
        }
    }

    public fun setConstruction(id: LineId, construction: Boolean) { lines[id.value] = line(id).copy(construction = construction) }
    public fun setConstruction(id: CircleId, construction: Boolean) { circles[id.value] = circle(id).copy(construction = construction) }
    public fun setConstruction(id: ArcId, construction: Boolean) { arcs[id.value] = arc(id).copy(construction = construction) }
    public fun setConstruction(id: TextId, construction: Boolean) {
        require(exists(id)) { "Unbekannter Text $id" }
        texts[id.value] = text(id).copy(construction = construction)
    }

    /** Entfernt den Punkt samt aller Elemente und Constraints, die ihn verwenden. Der Ursprung bleibt. */
    public fun remove(id: PointId) {
        if (id == origin || !exists(id)) return
        lines.filterValues { it.p1 == id || it.p2 == id }.keys.toList().forEach { remove(LineId(it)) }
        circles.filterValues { it.center == id }.keys.toList().forEach { remove(CircleId(it)) }
        arcs.filterValues { it.center == id || it.start == id || it.end == id }.keys.toList().forEach { remove(ArcId(it)) }
        texts.filterValues { it.location == id }.keys.toList().forEach { remove(TextId(it)) }
        removeConstraintsReferencing(id)
        points.remove(id.value)
        standalone.remove(id.value)
    }

    public fun remove(id: LineId) {
        if (lines.remove(id.value) != null) removeConstraintsReferencing(id)
    }

    public fun remove(id: RoundId) {
        val removed = when (id) {
            is CircleId -> circles.remove(id.value)
            is ArcId -> arcs.remove(id.value)
        }
        if (removed != null) removeConstraintsReferencing(id)
    }

    public fun remove(id: TextId) {
        texts.remove(id.value)
    }

    public fun remove(id: ConstraintId) {
        constraints.remove(id.value)
    }

    /** Löscht Punkte, die von keinem Element und keinem Constraint verwendet werden (außer dem Ursprung und Einzelpunkten). */
    public fun removeOrphanPoints() {
        val used = HashSet<Any>()
        lines.values.forEach { used += it.p1; used += it.p2 }
        circles.values.forEach { used += it.center }
        arcs.values.forEach { used += it.center; used += it.start; used += it.end }
        texts.values.forEach { used += it.location }
        constraints.values.forEach { used.addAll(it.refs) }
        standalone.forEach { used += PointId(it) }
        pointIds.filter { it != origin && it !in used }.forEach { points.remove(it.value) }
    }

    private fun removeConstraintsReferencing(ref: Any) {
        constraints.entries.removeAll { entry -> ref in entry.value.refs }
    }

    // ---- Constraints ----------------------------------------------------------------------------

    public fun add(constraint: Constraint): ConstraintId {
        for (r in constraint.refs) {
            val ok = when (r) {
                is PointId -> exists(r)
                is LineId -> exists(r)
                is RoundId -> exists(r)
                else -> false
            }
            require(ok) { "Constraint verweist auf ein nicht existierendes Element: $r" }
        }
        val id = nextId++
        constraints[id] = constraint
        return ConstraintId(id)
    }

    /** Ersetzt ein Constraint (z. B. um den Wert einer Bemaßung zu ändern). */
    public fun replace(id: ConstraintId, constraint: Constraint) {
        require(id.value in constraints) { "Unbekanntes Constraint" }
        for (r in constraint.refs) require(refExists(r)) { "Constraint verweist auf ein nicht existierendes Element: $r" }
        constraints[id.value] = constraint
    }

    /** Setzt den Wert einer Bemaßung. */
    public fun setDimension(id: ConstraintId, value: Double) {
        val c = constraint(id) as? Dimension ?: error("Constraint $id ist keine Bemaßung")
        constraints[id.value] = c.withValue(value)
    }

    private fun refExists(r: Any): Boolean = when (r) {
        is PointId -> exists(r)
        is LineId -> exists(r)
        is RoundId -> exists(r)
        else -> false
    }

    // Bemaßungen/Tangenten mit Startwert aus der aktuellen Zeichnung; `value = null` = so wie gezeichnet.

    public fun addDistance(a: PointId, b: PointId, value: Double? = null): ConstraintId =
        add(DistanceDim(a, b, value ?: dist(point(a), point(b))))

    public fun addHorizontalDistance(a: PointId, b: PointId, value: Double? = null): ConstraintId =
        add(HorizontalDistanceDim(a, b, value ?: (point(b).x - point(a).x)))

    public fun addVerticalDistance(a: PointId, b: PointId, value: Double? = null): ConstraintId =
        add(VerticalDistanceDim(a, b, value ?: (point(b).y - point(a).y)))

    public fun addLength(line: LineId, value: Double? = null): ConstraintId = add(LengthDim(line, value ?: length(line)))

    public fun addRadius(round: RoundId, value: Double? = null): ConstraintId = add(RadiusDim(round, value ?: radiusOf(round)))

    public fun addDiameter(round: RoundId, value: Double? = null): ConstraintId = add(DiameterDim(round, value ?: (2 * radiusOf(round))))

    public fun addAngle(a: LineId, b: LineId, degrees: Double? = null): ConstraintId =
        add(AngleDim(a, b, degrees ?: angleBetween(a, b)))

    public fun addPointLineDistance(p: PointId, line: LineId, value: Double? = null): ConstraintId {
        val signed = signedDistance(p, line)
        val side = if (signed < 0) -1 else 1
        return add(PointLineDistanceDim(p, line, value ?: abs(signed), side))
    }

    /** Tangente zwischen zwei Kreisen/Bögen; „innen/außen“ und Radienverhältnis ergeben sich aus der Zeichnung. */
    public fun addTangent(a: RoundId, b: RoundId): ConstraintId {
        val d = dist(point(centerOf(a)), point(centerOf(b)))
        val ra = radiusOf(a); val rb = radiusOf(b)
        val internal = abs(d - abs(ra - rb)) < abs(d - (ra + rb))
        return add(TangentRoundRound(a, b, internal, if (ra >= rb) 1 else -1))
    }

    /** Aktueller Wert einer Bemaßung nach der Zeichnung (zum Anzeigen „referenzierter“ Maße). */
    public fun measure(dimension: Dimension): Double = when (dimension) {
        is DistanceDim -> dist(point(dimension.a), point(dimension.b))
        is HorizontalDistanceDim -> point(dimension.b).x - point(dimension.a).x
        is VerticalDistanceDim -> point(dimension.b).y - point(dimension.a).y
        is LengthDim -> length(dimension.line)
        is PointLineDistanceDim -> dimension.side * signedDistance(dimension.point, dimension.line)
        is RadiusDim -> radiusOf(dimension.round)
        is DiameterDim -> 2 * radiusOf(dimension.round)
        is AngleDim -> angleBetween(dimension.a, dimension.b)
    }

    /** Vorzeichenbehafteter Abstand des Punktes von der Geraden durch die Linie (links der Richtung positiv). */
    public fun signedDistance(p: PointId, line: LineId): Double {
        val l = line(line)
        val a = point(l.p1); val b = point(l.p2); val q = point(p)
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy)
        return if (len == 0.0) dist(a, q) else (dx * (q.y - a.y) - dy * (q.x - a.x)) / len
    }

    /** Winkel von Linie [a] zu Linie [b] in Grad, normiert auf (−180, 180]. */
    public fun angleBetween(a: LineId, b: LineId): Double {
        val la = line(a); val lb = line(b)
        val d1x = point(la.p2).x - point(la.p1).x; val d1y = point(la.p2).y - point(la.p1).y
        val d2x = point(lb.p2).x - point(lb.p1).x; val d2y = point(lb.p2).y - point(lb.p1).y
        return Math.toDegrees(atan2(d1x * d2y - d1y * d2x, d1x * d2x + d1y * d2y))
    }

    // ---- Lösen ----------------------------------------------------------------------------------

    /** Löst alle Constraints und schreibt bei Erfolg die neuen Koordinaten zurück (siehe [SolveResult]). */
    public fun solve(options: SolveOptions = SolveOptions()): SolveResult = SketchSolver.solve(this, options)

    /** Startet das Ziehen von Punkten mit laufendem Solver (siehe [DragSession]). */
    public fun beginDrag(): DragSession = DragSession(this)

    /** Einmaliges Ziehen eines Punktes zu (x, y) mit anschließendem Lösen. */
    public fun dragPoint(id: PointId, x: Double, y: Double): SolveResult =
        beginDrag().let { it.dragTo(mapOf(id to Vec2(x, y))); it.finish() }

    // ---- intern ---------------------------------------------------------------------------------

    internal fun setPointInternal(id: PointId, x: Double, y: Double) { points[id.value] = Vec2(x, y) }
    internal fun setRadiusInternal(id: CircleId, r: Double) { circles[id.value] = circle(id).copy(radius = r) }

    internal val nextIdValue: Int get() = nextId

    /** Ersetzt den gesamten Inhalt (Laden aus einer Datei). Prüft IDs und Referenzen; bei Fehler bleibt die Skizze unverändert. */
    internal fun load(
        nextId: Int,
        points: Map<Int, Vec2>,
        lines: Map<Int, LineData>,
        circles: Map<Int, CircleData>,
        arcs: Map<Int, ArcData>,
        constraints: Map<Int, Constraint>,
        standalone: Set<Int> = emptySet(),
        texts: Map<Int, TextData> = emptyMap(),
    ) {
        val all = points.keys + lines.keys + circles.keys + arcs.keys + constraints.keys + texts.keys
        require(all.size == points.size + lines.size + circles.size + arcs.size + constraints.size + texts.size) { "IDs sind nicht eindeutig" }
        require(all.all { it >= 1 && it < nextId }) { "ID außerhalb des gültigen Bereichs" }
        require(origin.value in points) { "Ursprungspunkt fehlt" }
        fun pt(id: PointId) = require(id.value in points) { "Unbekannter Punkt $id" }
        fun round(id: RoundId) = require(
            when (id) { is CircleId -> id.value in circles; is ArcId -> id.value in arcs },
        ) { "Unbekannter Kreis/Bogen $id" }
        lines.values.forEach { pt(it.p1); pt(it.p2) }
        circles.values.forEach { pt(it.center); require(it.radius.isFinite()) { "Radius ungültig" } }
        arcs.values.forEach { pt(it.center); pt(it.start); pt(it.end) }
        texts.values.forEach {
            pt(it.location)
            require(it.text.isNotEmpty()) { "Text darf nicht leer sein" }
            require(it.height > 0 && it.height.isFinite()) { "Texthöhe ungültig" }
            require(it.angle.isFinite()) { "Winkel ungültig" }
        }
        standalone.forEach { pt(PointId(it)) }
        points.values.forEach { require(it.x.isFinite() && it.y.isFinite()) { "Koordinate ungültig" } }
        for (c in constraints.values) for (r in c.refs) when (r) {
            is PointId -> pt(r)
            is LineId -> require(r.value in lines) { "Unbekannte Linie $r" }
            is RoundId -> round(r)
            else -> throw IllegalArgumentException("Unbekannte Referenz $r")
        }
        this.points.clear(); this.lines.clear(); this.circles.clear(); this.arcs.clear(); this.texts.clear(); this.constraints.clear()
        this.points.putAll(points); this.lines.putAll(lines); this.circles.putAll(circles); this.arcs.putAll(arcs); this.texts.putAll(texts)
        this.constraints.putAll(constraints)
        this.standalone.clear(); this.standalone.addAll(standalone)
        this.nextId = nextId
    }

    /**
     * Stellt den Zustand von [from] **in diesem Objekt** wieder her (Rückgängig/Wiederholen). Das Objekt bleibt dasselbe,
     * damit Referenzen darauf (z. B. aus einem Feature) gültig bleiben.
     */
    public fun restore(from: Sketch) {
        points.clear(); lines.clear(); circles.clear(); arcs.clear(); texts.clear(); constraints.clear()
        points.putAll(from.points); lines.putAll(from.lines); circles.putAll(from.circles); arcs.putAll(from.arcs); texts.putAll(from.texts)
        constraints.putAll(from.constraints)
        standalone.clear(); standalone.addAll(from.standalone)
        nextId = from.nextId
    }

    /** Kopie der Skizze (für Rückgängig/Wiederholen und Probe-Lösen). IDs bleiben erhalten. */
    public fun copy(): Sketch {
        val s = Sketch()
        s.points.clear(); s.lines.clear(); s.circles.clear(); s.arcs.clear(); s.texts.clear(); s.constraints.clear()
        s.points.putAll(points); s.lines.putAll(lines); s.circles.putAll(circles); s.arcs.putAll(arcs); s.texts.putAll(texts)
        s.constraints.putAll(constraints)
        s.standalone.clear(); s.standalone.addAll(standalone)
        s.nextId = nextId
        return s
    }

    private companion object {
        fun dist(a: Vec2, b: Vec2): Double = hypot(a.x - b.x, a.y - b.y)
    }
}
