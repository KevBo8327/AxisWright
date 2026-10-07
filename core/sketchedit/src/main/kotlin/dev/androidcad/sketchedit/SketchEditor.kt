package dev.androidcad.sketchedit

import dev.androidcad.project.UndoHistory
import dev.androidcad.sketch.ArcId
import dev.androidcad.sketch.CircleId
import dev.androidcad.sketch.Coincident
import dev.androidcad.sketch.Constraint
import dev.androidcad.sketch.ConstraintId
import dev.androidcad.sketch.Dimension
import dev.androidcad.sketch.DragSession
import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.Horizontal
import dev.androidcad.sketch.LineId
import dev.androidcad.sketch.Midpoint
import dev.androidcad.sketch.OpResult
import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.PointId
import dev.androidcad.sketch.PointOnLine
import dev.androidcad.sketch.PointOnRound
import dev.androidcad.sketch.RadiusDim
import dev.androidcad.sketch.DiameterDim
import dev.androidcad.sketch.LengthDim
import dev.androidcad.sketch.DistanceDim
import dev.androidcad.sketch.PointLineDistanceDim
import dev.androidcad.sketch.SolveOptions
import dev.androidcad.sketch.SolveResult
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import dev.androidcad.sketch.Vertical
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

public enum class Tool {
    SELECT, LINE, RECTANGLE, CIRCLE, ARC,

    /** Element an der angetippten Stelle bis zu den nächsten Schnittpunkten kürzen. */
    TRIM,

    /** Linien-/Bogenende bis zum nächsten Element verlängern. */
    EXTEND,

    /** Element antippen, dann die Seite antippen, auf die der Versatz soll. */
    OFFSET,

    /** Ecke (Punkt zwischen zwei Linien) mit einem Bogen runden. */
    FILLET,

    /** Wartet auf die Spiegelachse für die zuvor gewählten Elemente (siehe [SketchEditor.startMirror]). */
    MIRROR,

    /** Reihe: wartet auf die Richtung (Linie antippen oder X/Y) für die zuvor gewählten Elemente (siehe [SketchEditor.startPattern]). */
    PATTERN_LINEAR,

    /** Kreismuster: wartet auf den Mittelpunkt (Punkt antippen oder Ursprung). */
    PATTERN_CIRCULAR,
    ;

    /** Werkzeuge, die vorhandene Geometrie bearbeiten (Tippen statt Zeichnen). */
    public val isModify: Boolean get() = this == TRIM || this == EXTEND || this == OFFSET || this == FILLET || this == MIRROR || isPattern

    /** Die beiden Musterwerkzeuge. */
    public val isPattern: Boolean get() = this == PATTERN_LINEAR || this == PATTERN_CIRCULAR
}

public enum class PointerKind { MOUSE, TOUCH, STYLUS }

public enum class PointerButton { PRIMARY, SECONDARY, MIDDLE, ERASER }

/** Vorschau der gerade gezeichneten Geometrie (Skizzenkoordinaten). */
public sealed interface Preview {
    public data class Line(val a: Vec2, val b: Vec2) : Preview
    public data class Rect(val a: Vec2, val b: Vec2) : Preview
    public data class Circle(val center: Vec2, val radius: Double) : Preview

    /** Bogen um [center] von [start] nach [end], gegen den Uhrzeigersinn. */
    public data class Arc(val center: Vec2, val start: Vec2, val end: Vec2) : Preview

    /** Aufgezogenes Auswahlrechteck. */
    public data class Box(val a: Vec2, val b: Vec2) : Preview
}

/** Eine Aktion wartet auf einen Zahlenwert (Maß anlegen). */
public data class PendingAction(val kind: ActionKind, val title: String, val initial: Double)

/** Der Wert eines vorhandenen Maßes soll geändert werden. */
public data class ValueEdit(val constraint: ConstraintId, val title: String, val initial: Double)

/**
 * Zustandsautomat der Skizzieroberfläche, unabhängig von Compose und Android: Zeigereingaben (Maus, Stift, Touch)
 * werden je nach Werkzeug zu Auswahl, Ziehen mit Solver oder dem Zeichnen von Linien, Rechtecken, Kreisen und Bögen.
 *
 * Beim Zeichnen wird gefangen (Punkte, Mitten, Kurven, Achsen, Raster) und es entstehen automatisch Constraints
 * (zusammenfallend, auf Kurve, horizontal/vertikal …). Jede abgeschlossene Aktion löst die Skizze, hält den
 * Rückgängig-Verlauf und meldet über [onSketchChanged] die geänderte Geometrie.
 *
 * Koordinaten der `pointer…`-Funktionen sind Bildschirmpixel (y nach unten).
 */
public class SketchEditor(public val sketch: Sketch) {
    public var tool: Tool = Tool.SELECT
        private set
    public var view: ViewTransform = ViewTransform(4.0, 0.0, 0.0)
        private set
    public var selection: Set<Sel> = emptySet()
        private set
    public var hover: SnapResult? = null
        private set
    public var hoverHit: Sel? = null
        private set
    public var preview: Preview? = null
        private set
    public var message: String? = null
        private set(v) { field = v; messageIsInfo = false }

    /** `true`, wenn [message] eine Erfolgsmeldung ist (z. B. „3 Kopien …“) statt eines Hinweises oder Fehlers. */
    public var messageIsInfo: Boolean = false
        private set
    public var status: SolveResult? = null
        private set
    public var pendingAction: PendingAction? = null
        private set
    public var valueEdit: ValueEdit? = null
        private set
    public var gridSnap: Boolean = true
        set(v) { field = v; changed() }

    /** Abstand (mm) des Versatz-Werkzeugs. */
    public var offsetDistance: Double = 5.0
        set(v) { field = v; changed() }

    /** Radius (mm) des Eckenverrundungs-Werkzeugs. */
    public var filletRadius: Double = 3.0
        set(v) { field = v; changed() }

    /** Anzahl der Exemplare (mit Original) eines Skizzenmusters. */
    public var patternCount: Int = 3
        set(v) { field = v.coerceIn(2, MAX_PATTERN); changed() }

    /** Abstand (mm) einer Reihe. */
    public var patternSpacing: Double = 10.0
        set(v) { field = v; changed() }

    /** Gesamtwinkel (Grad) eines Kreismusters; 360 = gleichmäßig auf dem vollen Kreis. */
    public var patternAngle: Double = 360.0
        set(v) { field = v; changed() }

    /** Beim Versatz das zuerst angetippte Element (die Seite folgt mit dem nächsten Tipp). */
    public var offsetSource: Sel? = null
        private set

    /** Beim Spiegeln die gewählten Elemente, die auf die Achse warten. */
    private var mirrorSource: Set<Sel> = emptySet()

    /** Beim Muster die gewählten Elemente, die auf Richtung bzw. Mittelpunkt warten. */
    private var patternSource: Set<Sel> = emptySet()

    /** `true`, solange ein Muster auf Richtung bzw. Mittelpunkt wartet. */
    public val patternPending: Boolean get() = tool.isPattern

    /** `true`, solange das Spiegeln auf die Achse wartet. */
    public val mirrorPending: Boolean get() = tool == Tool.MIRROR

    /** Wird nach jeder Zustandsänderung aufgerufen (für die Neuzeichnung). */
    public var onChange: (() -> Unit)? = null

    /** Wird aufgerufen, wenn sich die Geometrie geändert hat (auch beim Ziehen) – z. B. für die 3D-Vorschau. */
    public var onSketchChanged: (() -> Unit)? = null

    private val history = UndoHistory(sketch.copy(), maxSize = 200, coalesceWindowMs = 0)
    public val canUndo: Boolean get() = history.canUndo
    public val canRedo: Boolean get() = history.canRedo

    private class Down(val screen: Vec2, val world: Vec2, val kind: PointerKind, val additive: Boolean, val hit: Sel?, val wasSelected: Boolean, val snap: SnapResult?) {
        var moved = false
    }

    private class DragState(val session: DragSession, val offsets: Map<PointId, Vec2>, var moved: Boolean = false)

    private var down: Down? = null
    private var drag: DragState? = null
    private var erasing = false
    private var pending: SnapResult? = null   // erster Punkt bei Linie/Rechteck/Kreis; Mittelpunkt beim Bogen
    private var arcStart: SnapResult? = null

    /** `true`, solange eine mehrteilige Zeichenaktion läuft (z. B. Linienzug) – dann bietet die Oberfläche „Fertig“ an. */
    public val drawingInProgress: Boolean get() = pending != null

    init {
        status = sketch.solve()
    }

    // ---- Werkzeug, Ansicht ----------------------------------------------------------------------

    public fun setTool(t: Tool) {
        cancelOperation()
        tool = t
        if (t != Tool.SELECT) selection = emptySet()
        message = null
        changed()
    }

    /**
     * Spiegeln: Die gewählten Linien, Kreise und Bögen werden an einer Achse gespiegelt. Danach wartet der Editor auf die Achse
     * (eine Linie antippen, oder [mirrorAcrossAxis] für die X-/Y-Achse).
     */
    public fun startMirror() {
        val source = selection.filter { it is Sel.L || it is Sel.C || it is Sel.A }.toSet()
        if (source.isEmpty()) { message = "Wähle zuerst die Elemente, die gespiegelt werden sollen."; changed(); return }
        cancelOperation()
        mirrorSource = source
        tool = Tool.MIRROR
        selection = source
        message = null
        changed()
    }

    /**
     * Muster: Die gewählten Linien, Kreise und Bögen werden vervielfältigt – als Reihe ([circular] = false; danach eine Linie als
     * Richtung antippen oder [patternAlongAxis]) oder im Kreis (danach einen Punkt als Mittelpunkt antippen oder [patternAroundOrigin]).
     */
    public fun startPattern(circular: Boolean) {
        val source = selection.filter { it is Sel.L || it is Sel.C || it is Sel.A }.toSet()
        if (source.isEmpty()) { message = "Wähle zuerst die Elemente, die vervielfältigt werden sollen."; changed(); return }
        cancelOperation()
        patternSource = source
        tool = if (circular) Tool.PATTERN_CIRCULAR else Tool.PATTERN_LINEAR
        selection = source
        message = null
        changed()
    }

    /** Reihe entlang der X- ([horizontal]) bzw. Y-Achse, in positiver Richtung. */
    public fun patternAlongAxis(horizontal: Boolean) {
        if (tool != Tool.PATTERN_LINEAR) return
        finishPattern { src -> SketchOps.linearPattern(sketch, src.linesOf(), src.roundsOf(), if (horizontal) Vec2(1.0, 0.0) else Vec2(0.0, 1.0), patternCount, patternSpacing) }
    }

    /** Kreismuster um den Ursprung der Skizze. */
    public fun patternAroundOrigin() {
        if (tool != Tool.PATTERN_CIRCULAR) return
        finishPattern { src -> SketchOps.circularPattern(sketch, src.linesOf(), src.roundsOf(), sketch.origin, patternCount, patternAngle) }
    }

    private fun finishPattern(op: (Set<Sel>) -> dev.androidcad.sketch.OpResult) {
        val source = patternSource
        patternSource = emptySet()
        tool = Tool.SELECT
        runOp("Muster") { op(source) }
        changed()
    }

    /** Spiegelt die beim [startMirror] gewählten Elemente an der X- ([horizontal]) bzw. Y-Achse durch den Ursprung. */
    public fun mirrorAcrossAxis(horizontal: Boolean) {
        if (!mirrorPending) return
        val source = mirrorSource
        mirrorSource = emptySet()
        runOp("Spiegeln") { SketchOps.mirror(sketch, source.linesOf(), source.roundsOf(), SketchOps.axisLine(sketch, horizontal)) }
        tool = Tool.SELECT
        changed()
    }

    /** Mittelpunkt der Punkte der gewählten Elemente (für die Richtung einer Reihe). */
    private fun centroidOf(sel: Set<Sel>): Vec2? {
        val pts = sel.flatMap { s ->
            when (s) {
                is Sel.L -> sketch.line(s.id).let { listOf(it.p1, it.p2) }
                is Sel.C -> listOf(sketch.circle(s.id).center)
                is Sel.A -> listOf(sketch.arc(s.id).center)
                else -> emptyList()
            }
        }.map { sketch.point(it) }
        if (pts.isEmpty()) return null
        return Vec2(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size)
    }

    private fun Set<Sel>.linesOf(): List<LineId> = filterIsInstance<Sel.L>().map { it.id }.filter { sketch.exists(it) }
    private fun Set<Sel>.roundsOf(): List<dev.androidcad.sketch.RoundId> = mapNotNull {
        when (it) {
            is Sel.C -> if (sketch.exists(it.id)) it.id else null
            is Sel.A -> if (sketch.exists(it.id)) it.id else null
            else -> null
        }
    }

    public fun setView(v: ViewTransform) {
        view = v
        changed()
    }

    public fun zoomAt(focus: Vec2, factor: Double) {
        setView(view.zoomAt(focus, factor))
    }

    public fun pan(dx: Double, dy: Double) {
        setView(view.panned(dx, dy))
    }

    /**
     * Passt die Ansicht an den Inhalt an (ohne Inhalt: Ursprung mittig, 4 px/mm). [extra] sind zusätzliche Punkte, die sichtbar
     * sein sollen, z. B. der Umriss der Fläche, auf der die Skizze liegt. [left] und [top] (px) halten Ränder frei, über denen
     * schwebende Bedienelemente liegen (Werkzeugleiste, Statusanzeige).
     */
    public fun fit(width: Double, height: Double, extra: List<Vec2> = emptyList(), left: Double = 0.0, top: Double = 0.0) {
        val w = (width - left).coerceAtLeast(1.0); val h = (height - top).coerceAtLeast(1.0)
        val pts = sketch.pointIds.filter { it != sketch.origin && !SketchOps.isAxisPoint(sketch, it) }.map { sketch.point(it) } + extra
        val circles = sketch.circleIds.map { sketch.circle(it).let { c -> sketch.point(c.center) to c.radius } }
        if (pts.isEmpty() && circles.isEmpty()) {
            setView(ViewTransform(4.0, left + w / 2, top + h / 2)); return
        }
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        pts.forEach { minX = minOf(minX, it.x); maxX = maxOf(maxX, it.x); minY = minOf(minY, it.y); maxY = maxOf(maxY, it.y) }
        circles.forEach { (c, r) -> minX = minOf(minX, c.x - r); maxX = maxOf(maxX, c.x + r); minY = minOf(minY, c.y - r); maxY = maxOf(maxY, c.y + r) }
        // Ursprung und Achsen mit ins Bild nehmen (Bezug zum Ausrichten) – nur, wenn die Form dadurch nicht zu klein wird
        val size = maxOf(maxX - minX, maxY - minY)
        val gap = maxOf(minX, -maxX, minY, -maxY, 0.0)
        if (gap <= size) { minX = minOf(minX, 0.0); maxX = maxOf(maxX, 0.0); minY = minOf(minY, 0.0); maxY = maxOf(maxY, 0.0) }
        setView(ViewTransform.fit(Vec2(minX, minY), Vec2(maxX, maxY), w, h).panned(left, top))
    }

    // ---- Zeigereingaben -------------------------------------------------------------------------

    public fun pointerDown(screen: Vec2, kind: PointerKind = PointerKind.MOUSE, button: PointerButton = PointerButton.PRIMARY, additive: Boolean = false) {
        when (button) {
            PointerButton.ERASER -> { erasing = true; eraseAt(screen); return }
            PointerButton.SECONDARY -> { cancelOperation(); return }
            PointerButton.MIDDLE -> return
            PointerButton.PRIMARY -> Unit
        }
        message = null
        val world = view.toWorld(screen)
        if (tool == Tool.SELECT) {
            val label = labelAt(screen)
            // Achsen zählen beim Aufsetzen als leerer Raum (Rahmen aufziehen geht auch dort); gewählt werden sie beim Antippen
            val hit: Sel? = label?.let { Sel.K(it.id) } ?: HitTest.hit(sketch, world, view.worldLength(hitTolerancePx(kind)))?.takeUnless { isReference(it) && it is Sel.L }
            val was = hit != null && hit in selection
            down = Down(screen, world, kind, additive, hit, was, null)
            if (hit != null && hit !is Sel.K && !was) select(hit, additive)
        } else {
            down = Down(screen, world, kind, additive, null, false, snapAt(world))
        }
        changed()
    }

    public fun pointerMove(screen: Vec2) {
        if (erasing) { eraseAt(screen); return }
        val d = down ?: run { hover(screen); return }
        if (!d.moved && hypot(screen.x - d.screen.x, screen.y - d.screen.y) > dragThresholdPx(d.kind)) d.moved = true
        val world = view.toWorld(screen)
        if (tool == Tool.SELECT) {
            if (d.moved) {
                if (d.hit != null && d.hit !is Sel.K) dragTo(d, world)
                else if (d.hit == null) preview = Preview.Box(d.world, world)
            }
        } else if (tool.isModify) {
            hover = null
            hoverHit = HitTest.hit(sketch, world, view.worldLength(HIT_TOLERANCE_PX))
        } else {
            hover = snapAt(world)
            updateDrawPreview(hover!!, d)
        }
        changed()
    }

    public fun pointerUp(screen: Vec2) {
        if (erasing) { erasing = false; return }
        val d = down ?: return
        down = null
        val world = view.toWorld(screen)
        if (tool == Tool.SELECT) {
            finishSelectGesture(d, world)
        } else if (tool.isModify) {
            if (!d.moved) modifyAt(world, hitTolerancePx(d.kind))
        } else {
            val up = snapAt(world)
            drawUp(d, up)
        }
        changed()
    }

    /** Mauszeiger bewegt sich ohne gedrückte Taste: Fangvorschau bzw. Hervorhebung. */
    public fun hover(screen: Vec2) {
        val world = view.toWorld(screen)
        if (tool == Tool.SELECT || tool.isModify) {
            hover = null
            hoverHit = if (tool.isModify) HitTest.hit(sketch, world, view.worldLength(HIT_TOLERANCE_PX))
            else labelAt(screen)?.let { Sel.K(it.id) } ?: HitTest.hit(sketch, world, view.worldLength(HIT_TOLERANCE_PX))
        } else {
            hoverHit = null
            hover = snapAt(world)
            updateDrawPreview(hover!!, null)
        }
        changed()
    }

    public fun pointerCancel() {
        drag?.session?.cancel()
        drag = null
        down = null
        erasing = false
        preview = null
        changed()
    }

    /** Bricht die laufende Zeichenaktion bzw. das Ziehen ab (Esc, rechte Maustaste). */
    public fun cancelOperation() {
        drag?.session?.cancel()
        drag = null
        down = null
        pending = null
        arcStart = null
        preview = null
        if (offsetSource != null) { offsetSource = null; selection = emptySet() }
        if (tool == Tool.MIRROR) { mirrorSource = emptySet(); tool = Tool.SELECT; message = null }
        if (tool.isPattern) { patternSource = emptySet(); tool = Tool.SELECT; message = null }
        changed()
    }

    // ---- Auswahl --------------------------------------------------------------------------------

    private fun select(hit: Sel, additive: Boolean) {
        selection = if (additive) selection + hit else setOf(hit)
    }

    public fun clearSelection() {
        selection = emptySet()
        changed()
    }

    public fun selectAll() {
        selection = HitTest.inBox(sketch, Vec2(-1e9, -1e9), Vec2(1e9, 1e9)).filterNot { isReference(it) }.toSet()
        changed()
    }

    private fun finishSelectGesture(d: Down, world: Vec2) {
        val dr = drag
        if (dr != null) {
            drag = null
            val res = dr.session.finish()
            status = res
            if (dr.moved) commit("Ziehen", analyze = false)
            preview = null
            return
        }
        preview = null
        when {
            d.moved && d.hit == null -> {
                val inside = HitTest.inBox(sketch, d.world, world).filterNot { isReference(it) }
                selection = if (d.additive) selection + inside else inside.toSet()
            }
            d.hit is Sel.K -> requestValueEdit(d.hit.id)
            // Tipp ins Leere: auf einer Achse wählt er die Achse (als Bezug für Bedingungen), sonst hebt er die Auswahl auf
            d.hit == null -> {
                val axis = axisAt(d.world, view.worldLength(hitTolerancePx(d.kind)))
                if (axis != null) select(axis, d.additive) else if (!d.additive) selection = emptySet()
            }
            d.additive && d.wasSelected -> selection = selection - d.hit
        }
    }

    // ---- Ziehen mit Solver ----------------------------------------------------------------------

    private fun dragTo(d: Down, world: Vec2) {
        var st = drag
        if (st == null) {
            val hit = d.hit ?: return
            val ids = LinkedHashSet<PointId>()
            val source = if (hit in selection && selection.size > 1) selection else setOf(hit)
            source.forEach { ids += pointsOf(it) }
            if (ids.isEmpty()) return
            val offsets = ids.associateWith { p -> sketch.point(p).let { Vec2(it.x - d.world.x, it.y - d.world.y) } }
            st = DragState(sketch.beginDrag(), offsets)
            drag = st
        }
        val targets = st.offsets.mapValues { (_, off) -> Vec2(world.x + off.x, world.y + off.y) }
        st.session.dragTo(targets)
        st.moved = true
        onSketchChanged?.invoke()
    }

    private fun pointsOf(s: Sel): List<PointId> = when (s) {
        is Sel.P -> listOf(s.id)
        is Sel.L -> sketch.line(s.id).let { listOf(it.p1, it.p2) }
        is Sel.C -> listOf(sketch.circle(s.id).center)
        is Sel.A -> sketch.arc(s.id).let { listOf(it.center, it.start, it.end) }
        is Sel.K -> emptyList()
    }

    // ---- Zeichnen -------------------------------------------------------------------------------

    private fun snapAt(world: Vec2): SnapResult = Snapping.snap(sketch, world, view, SnapOptions(grid = gridSnap, tolerancePx = SNAP_TOLERANCE_PX))

    private fun updateDrawPreview(snap: SnapResult, d: Down?) {
        val start = pending ?: d?.snap
        preview = when (tool) {
            Tool.LINE -> start?.let { Preview.Line(it.position, snap.position) }
            Tool.RECTANGLE -> start?.let { Preview.Rect(it.position, snap.position) }
            Tool.CIRCLE -> start?.let { Preview.Circle(it.position, dist(it.position, snap.position)) }
            Tool.ARC -> {
                val c = pending
                val s = arcStart
                when {
                    c != null && s != null -> {
                        val r = dist(c.position, s.position)
                        val dir = angleOf(c.position, snap.position)
                        Preview.Arc(c.position, s.position, Vec2(c.position.x + r * kotlin.math.cos(dir), c.position.y + r * kotlin.math.sin(dir)))
                    }
                    c != null -> Preview.Line(c.position, snap.position)
                    else -> null
                }
            }
            Tool.SELECT, Tool.TRIM, Tool.EXTEND, Tool.OFFSET, Tool.FILLET, Tool.MIRROR, Tool.PATTERN_LINEAR, Tool.PATTERN_CIRCULAR -> null
        }
    }

    private fun drawUp(d: Down, up: SnapResult) {
        val dragged = d.moved
        val first = d.snap ?: return
        when (tool) {
            Tool.LINE -> when {
                dragged && pending == null -> { finishLine(first, up); pending = null }   // Stift: ziehen = eine Linie
                pending == null -> pending = first
                else -> finishLine(pending!!, up)?.let { pending = SnapResult(sketch.point(it), SnapKind.POINT, point = it) }
            }
            Tool.RECTANGLE -> when {
                dragged && pending == null -> { finishRect(first, up); pending = null }
                pending == null -> pending = first
                else -> { finishRect(pending!!, up); pending = null }
            }
            Tool.CIRCLE -> when {
                dragged && pending == null -> { finishCircle(first, up); pending = null }
                pending == null -> pending = first
                else -> { finishCircle(pending!!, up); pending = null }
            }
            Tool.ARC -> {
                val c = pending
                val s = arcStart
                when {
                    c == null -> pending = first
                    s == null -> if (dist(c.position, first.position) > MIN_SIZE) arcStart = first
                    else -> { finishArc(c, s, up.position); pending = null; arcStart = null }
                }
            }
            Tool.SELECT, Tool.TRIM, Tool.EXTEND, Tool.OFFSET, Tool.FILLET, Tool.MIRROR, Tool.PATTERN_LINEAR, Tool.PATTERN_CIRCULAR -> Unit
        }
        if (pending == null) preview = null else updateDrawPreview(up, null)
    }

    // ---- Bearbeiten: Trimmen, Verlängern, Versatz, Ecke, Spiegeln --------------------------------

    /** Führt [op] auf der Skizze aus, löst sie und nimmt alles zurück, wenn es nicht ging oder die Bedingungen widerspricht. */
    private fun runOp(label: String, op: () -> OpResult): Boolean {
        val before = sketch.copy()
        val result = try { op() } catch (e: IllegalArgumentException) { OpResult.fail(e.message ?: "Nicht möglich") }
        if (!result.ok) { sketch.restore(before); message = result.message; changed(); return false }
        val res = sketch.solve(SolveOptions(analyze = true))
        if (!res.isSolved) { sketch.restore(before); message = "Das widerspricht den vorhandenen Bedingungen."; changed(); return false }
        selection = emptySet()
        commit(label, analyze = true, solved = res)
        result.message?.let { message = it; messageIsInfo = true; changed() }
        return true
    }

    /** Ein Tipp mit einem Bearbeitungswerkzeug an der Stelle [world]. */
    private fun modifyAt(world: Vec2, tolerancePx: Double) {
        // Beim Fillet zählt der Eckpunkt, sonst die Kurve – auch wenn man genau auf ein Linienende tippt
        val hit = if (tool == Tool.FILLET || tool == Tool.PATTERN_CIRCULAR) HitTest.hit(sketch, world, view.worldLength(tolerancePx))
        else HitTest.hitCurve(sketch, world, view.worldLength(tolerancePx))
            ?: if (tool == Tool.MIRROR || tool == Tool.PATTERN_LINEAR) axisAt(world, view.worldLength(tolerancePx)) else null
        when (tool) {
            Tool.TRIM -> when (hit) {
                is Sel.L -> runOp("Trimmen") { SketchOps.trim(sketch, hit.id, world) }
                is Sel.C -> runOp("Trimmen") { SketchOps.trim(sketch, hit.id, world) }
                is Sel.A -> runOp("Trimmen") { SketchOps.trim(sketch, hit.id, world) }
                else -> { message = "Tippe auf eine Linie, einen Kreis oder Bogen."; }
            }
            Tool.EXTEND -> when (hit) {
                is Sel.L -> runOp("Verlängern") { SketchOps.extend(sketch, hit.id, world) }
                is Sel.A -> runOp("Verlängern") { SketchOps.extend(sketch, hit.id, world) }
                else -> { message = "Tippe auf das Ende einer Linie oder eines Bogens." }
            }
            Tool.OFFSET -> offsetTap(hit, world)
            Tool.FILLET -> when (hit) {
                is Sel.P -> runOp("Ecke runden") { SketchOps.fillet(sketch, hit.id, filletRadius) }
                else -> { message = "Tippe auf die Ecke – den Punkt, an dem zwei Linien enden." }
            }
            Tool.MIRROR -> when (hit) {
                is Sel.L -> {
                    val source = mirrorSource
                    mirrorSource = emptySet()
                    runOp("Spiegeln") { SketchOps.mirror(sketch, source.linesOf(), source.roundsOf(), hit.id) }
                    tool = Tool.SELECT
                }
                else -> { message = "Tippe eine Linie als Spiegelachse an." }
            }
            Tool.PATTERN_LINEAR -> when (hit) {
                is Sel.L -> {
                    // Richtung der Linie; das Vorzeichen zeigt von der Auswahl zur angetippten Stelle
                    val l = sketch.line(hit.id)
                    val a = sketch.point(l.p1); val b = sketch.point(l.p2)
                    var d = Vec2(b.x - a.x, b.y - a.y)
                    val from = centroidOf(patternSource)
                    if (from != null && (world.x - from.x) * d.x + (world.y - from.y) * d.y < 0) d = Vec2(-d.x, -d.y)
                    finishPattern { src -> SketchOps.linearPattern(sketch, src.linesOf(), src.roundsOf(), d, patternCount, patternSpacing, along = hit.id) }
                }
                else -> { message = "Tippe eine Linie als Richtung an – auf der Seite, in die die Reihe wachsen soll." }
            }
            Tool.PATTERN_CIRCULAR -> when (hit) {
                is Sel.P -> finishPattern { src -> SketchOps.circularPattern(sketch, src.linesOf(), src.roundsOf(), hit.id, patternCount, patternAngle) }
                is Sel.C -> finishPattern { src -> SketchOps.circularPattern(sketch, src.linesOf(), src.roundsOf(), sketch.circle(hit.id).center, patternCount, patternAngle) }
                is Sel.A -> finishPattern { src -> SketchOps.circularPattern(sketch, src.linesOf(), src.roundsOf(), sketch.arc(hit.id).center, patternCount, patternAngle) }
                else -> { message = "Tippe einen Punkt (oder Kreis) als Mittelpunkt an." }
            }
            else -> Unit
        }
        changed()
    }

    /** Versatz in zwei Schritten: erst das Element wählen, dann die Seite antippen. */
    private fun offsetTap(hit: Sel?, world: Vec2) {
        val src = offsetSource
        if (src == null) {
            if (hit is Sel.L || hit is Sel.C || hit is Sel.A) {
                offsetSource = hit
                selection = setOf(hit)
            } else message = "Tippe eine Linie, einen Kreis oder Bogen an."
            return
        }
        // zweiter Tipp: Seite; zu nah am Element ist mehrdeutig
        val near = when (src) {
            is Sel.L -> nearestOnSegment(world, sketch.point(sketch.line(src.id).p1), sketch.point(sketch.line(src.id).p2)).distance
            is Sel.C -> nearestOnCircle(world, sketch.point(sketch.circle(src.id).center), sketch.circle(src.id).radius).distance
            is Sel.A -> sketch.arc(src.id).let { nearestOnArc(world, sketch.point(it.center), sketch.point(it.start), sketch.point(it.end)).distance }
            else -> Double.MAX_VALUE
        }
        if (near < view.worldLength(HIT_TOLERANCE_PX)) { message = "Tippe weiter vom Element entfernt, um die Seite zu wählen."; return }
        offsetSource = null
        selection = emptySet()
        val d = offsetDistance
        when (src) {
            is Sel.L -> runOp("Versatz") { SketchOps.offset(sketch, src.id, world, d) }
            is Sel.C -> runOp("Versatz") { SketchOps.offset(sketch, src.id, world, d) }
            is Sel.A -> runOp("Versatz") { SketchOps.offset(sketch, src.id, world, d) }
            else -> Unit
        }
    }

    /** Legt einen Punkt an (oder verwendet den gefangenen) und sammelt die Constraints, die das Fangen verlangt. */
    private fun materialize(s: SnapResult, extra: MutableList<Constraint>): PointId {
        s.point?.let { return it }
        val id = sketch.addPoint(s.position.x, s.position.y)
        extra += constraintsFor(id, s)
        return id
    }

    private fun constraintsFor(p: PointId, s: SnapResult): List<Constraint> = when (s.kind) {
        SnapKind.POINT -> listOf(Coincident(p, s.point!!))
        SnapKind.ON_LINE -> listOf(PointOnLine(p, s.line!!))
        SnapKind.ON_ROUND -> listOf(PointOnRound(p, s.round!!))
        SnapKind.MIDPOINT -> listOf(Midpoint(p, s.line!!))
        SnapKind.AXIS_X -> listOf(Fixed(p, null, 0.0))
        SnapKind.AXIS_Y -> listOf(Fixed(p, 0.0, null))
        SnapKind.GRID, SnapKind.FREE -> emptyList()
    }

    /** Fügt [c] hinzu, löst, und nimmt es bei Widerspruch still wieder zurück (automatische Constraints dürfen scheitern). */
    private fun tryAdd(c: Constraint): Boolean {
        val id = sketch.add(c)
        val r = sketch.solve(SolveOptions(analyze = false))
        if (!r.isSolved) { sketch.remove(id); return false }
        return true
    }

    private fun finishLine(a: SnapResult, b: SnapResult): PointId? {
        if (dist(a.position, b.position) < MIN_SIZE) return null
        val extra = ArrayList<Constraint>()
        val pa = materialize(a, extra)
        val pb = materialize(b, extra)
        if (pa == pb) { sketch.removeOrphanPoints(); return null }
        val line = sketch.addLine(pa, pb)
        extra.forEach(::tryAdd)
        autoHorizontalVertical(line)
        commit("Linie", analyze = true)
        return pb
    }

    private fun autoHorizontalVertical(line: LineId) {
        val l = sketch.line(line)
        val a = sketch.point(l.p1); val b = sketch.point(l.p2)
        val dx = abs(b.x - a.x); val dy = abs(b.y - a.y)
        val k = tan(Math.toRadians(AUTO_ANGLE_DEG))
        when {
            dy <= k * dx && dy > 0 -> tryAdd(Horizontal(line))
            dx <= k * dy && dx > 0 -> tryAdd(Vertical(line))
            dy == 0.0 && dx > 0 -> tryAdd(Horizontal(line))
            dx == 0.0 && dy > 0 -> tryAdd(Vertical(line))
        }
    }

    private fun finishRect(a: SnapResult, b: SnapResult) {
        val p = a.position; val q = b.position
        if (abs(p.x - q.x) < MIN_SIZE || abs(p.y - q.y) < MIN_SIZE) return
        val r = sketch.addRectangle(p.x, p.y, q.x, q.y)
        // gefangene Ecken: gegenüberliegende Ecken (links unten = a, rechts oben = b) an die Ziele binden
        constraintsFor(r.corners[0], a).forEach(::tryAdd)
        constraintsFor(r.corners[2], b).forEach(::tryAdd)
        commit("Rechteck", analyze = true)
    }

    private fun finishCircle(center: SnapResult, edge: SnapResult) {
        val radius = dist(center.position, edge.position)
        if (radius < MIN_SIZE) return
        val extra = ArrayList<Constraint>()
        val c = materialize(center, extra)
        sketch.addCircle(c, radius)
        extra.forEach(::tryAdd)
        commit("Kreis", analyze = true)
    }

    private fun finishArc(center: SnapResult, start: SnapResult, endHint: Vec2) {
        val r = dist(center.position, start.position)
        if (r < MIN_SIZE) return
        val dir = angleOf(center.position, endHint)
        val end = Vec2(center.position.x + r * kotlin.math.cos(dir), center.position.y + r * kotlin.math.sin(dir))
        if (dist(end, start.position) < MIN_SIZE) return
        val extra = ArrayList<Constraint>()
        val c = materialize(center, extra)
        val s = materialize(start, extra)
        val e = sketch.addPoint(end.x, end.y)
        sketch.addArc(c, s, e)
        extra.forEach(::tryAdd)
        commit("Bogen", analyze = true)
    }

    // ---- Löschen, Konstruktion ------------------------------------------------------------------

    public fun deleteSelection() {
        if (selection.isEmpty()) return
        for (s in selection) when (s) {
            is Sel.P -> sketch.remove(s.id)
            is Sel.L -> if (sketch.exists(s.id)) sketch.remove(s.id)
            is Sel.C -> if (sketch.exists(s.id)) sketch.remove(s.id)
            is Sel.A -> if (sketch.exists(s.id)) sketch.remove(s.id)
            is Sel.K -> sketch.remove(s.id)
        }
        sketch.removeOrphanPoints()
        selection = emptySet()
        commit("Löschen", analyze = true)
    }

    private fun eraseAt(screen: Vec2) {
        val hit = HitTest.hit(sketch, view.toWorld(screen), view.worldLength(HIT_TOLERANCE_PX)) ?: return
        when (hit) {
            is Sel.P -> if (hit.id != sketch.origin) sketch.remove(hit.id)
            is Sel.L -> sketch.remove(hit.id)
            is Sel.C -> sketch.remove(hit.id)
            is Sel.A -> sketch.remove(hit.id)
            is Sel.K -> Unit
        }
        sketch.removeOrphanPoints()
        selection = selection - hit
        commit("Radieren", analyze = true)
    }

    // ---- Constraints und Maße -------------------------------------------------------------------

    /** Aktionen, die für die aktuelle Auswahl möglich sind (Constraint-Leiste). */
    public fun actionOptions(): List<ActionOption> =
        ConstraintActions.options(sketch, selInfo(selection)).filter { it.kind != ActionKind.FACE_CENTER || faceCenter != null }

    /**
     * Ziel für „Mittig auf Fläche“ (nur bei Skizzen auf einer Körperfläche gesetzt): liefert die Mitte der Fläche – legt dafür bei
     * Bedarf ihren Umriss als projizierte Kanten in die Skizze – oder `null`, wenn es (noch) keinen Umriss gibt.
     */
    public var faceCenter: (() -> SketchOps.CenterTarget?)? = null
        set(v) { field = v; changed() }

    /** Führt die Aktion aus bzw. fragt bei Maßen zuerst nach dem Wert ([pendingAction]). */
    public fun requestAction(kind: ActionKind) {
        message = null
        if (kind.needsValue) {
            val opt = actionOptions().firstOrNull { it.kind == kind } ?: return
            pendingAction = PendingAction(kind, kind.label, opt.defaultValue ?: 0.0)
            changed()
        } else applyAction(kind, null)
    }

    public fun confirmPendingAction(value: Double): Boolean {
        val p = pendingAction ?: return false
        pendingAction = null
        if (!validDimension(p.kind, value)) { message = "Ungültiger Wert"; changed(); return false }
        return applyAction(p.kind, value)
    }

    public fun cancelPendingAction() {
        pendingAction = null
        changed()
    }

    private fun validDimension(kind: ActionKind, v: Double): Boolean = v.isFinite() && when (kind) {
        ActionKind.LENGTH, ActionKind.DISTANCE, ActionKind.RADIUS, ActionKind.DIAMETER -> v > 0
        else -> true
    }

    private fun applyAction(kind: ActionKind, value: Double?): Boolean {
        val info = selInfo(selection)
        if (kind == ActionKind.ORIGIN_CORNER) return runOp("Ecke auf Ursprung") { SketchOps.cornerToOrigin(sketch, info.lines, info.rounds) }
        if (kind == ActionKind.ORIGIN_CENTER) return runOp("Mittig auf Ursprung") { SketchOps.centerOnOrigin(sketch, info.lines, info.rounds) }
        if (kind == ActionKind.HOLE_CENTER || kind == ActionKind.EDGE_CENTER) {
            val (edge, hole) = ConstraintActions.referenceTarget(sketch, info) ?: run { message = "Wähle die Form und genau eine projizierte Bohrung bzw. Kante."; changed(); return false }
            if (hole != null) return runOp("Mittig auf Bohrung") {
                SketchOps.centerOn(sketch, info.lines, info.rounds.filter { it != hole }, SketchOps.CenterTarget.Point(sketch.centerOf(hole)))
            }
            return runOp("Mittig zur Kante") { SketchOps.centerOnEdge(sketch, info.lines, info.rounds, edge!!) }
        }
        if (kind == ActionKind.FACE_CENTER) return runOp("Mittig auf Fläche") {
            val target = faceCenter?.invoke() ?: return@runOp dev.androidcad.sketch.OpResult.fail("Der Umriss der Fläche ist noch nicht bekannt – gleich noch einmal versuchen.")
            SketchOps.centerOn(sketch, info.lines, info.rounds, target)
        }
        if (kind == ActionKind.CONSTRUCTION) {
            info.lines.forEach { sketch.setConstruction(it, !sketch.line(it).construction) }
            info.rounds.forEach {
                when (it) {
                    is CircleId -> sketch.setConstruction(it, !sketch.circle(it).construction)
                    is ArcId -> sketch.setConstruction(it, !sketch.arc(it).construction)
                }
            }
            commit("Konstruktion", analyze = false)
            return true
        }
        val constraints = ConstraintActions.build(sketch, kind, info, value)
        if (constraints.isNullOrEmpty()) { message = "Für diese Auswahl nicht möglich"; changed(); return false }
        val before = sketch.copy()
        val ids = constraints.map { sketch.add(it) }
        val res = sketch.solve()
        if (!res.isSolved) {
            sketch.restore(before)
            message = "Widerspricht vorhandenen Bedingungen"
            changed(); return false
        }
        // Abgelehnt wird nur, wenn die Aktion gar nichts Neues sagt; teilweise Überschneidung (z. B. Punkt fixieren,
        // dessen y schon bestimmt ist) ist in Ordnung.
        if (ids.all { it in res.redundant }) {
            sketch.restore(before)
            message = "Ist bereits durch andere Bedingungen bestimmt"
            changed(); return false
        }
        commit(kind.label, analyze = true, solved = res)
        return true
    }

    /** Tippen auf ein Maßetikett: Wert ändern. */
    private fun requestValueEdit(id: ConstraintId) {
        val c = sketch.constraint(id) as? Dimension ?: return
        valueEdit = ValueEdit(id, titleOf(c), if (isSignedDistance(c)) abs(c.value) else c.value)
    }

    /** Horizontale/vertikale Abstände tragen ihre Richtung im Vorzeichen; Anzeige und Eingabe nutzen den Betrag. */
    private fun isSignedDistance(c: Dimension) = c is dev.androidcad.sketch.HorizontalDistanceDim || c is dev.androidcad.sketch.VerticalDistanceDim

    public fun confirmValueEdit(value: Double): Boolean {
        val e = valueEdit ?: return false
        valueEdit = null
        val c = sketch.constraint(e.constraint) as? Dimension ?: return false
        val kind = when (c) {
            is LengthDim, is DistanceDim, is PointLineDistanceDim -> ActionKind.LENGTH
            is RadiusDim -> ActionKind.RADIUS
            is DiameterDim -> ActionKind.DIAMETER
            else -> ActionKind.ANGLE
        }
        if (!validDimension(kind, value)) { message = "Ungültiger Wert"; changed(); return false }
        val before = sketch.copy()
        // Richtung beibehalten: ein negativer Abstand bleibt negativ, auch wenn der Betrag eingegeben wird
        sketch.setDimension(e.constraint, if (isSignedDistance(c) && c.value < 0) -abs(value) else if (isSignedDistance(c)) abs(value) else value)
        val res = sketch.solve()
        if (!res.isSolved) {
            sketch.restore(before)
            message = "Mit diesem Wert nicht lösbar"
            changed(); return false
        }
        commit("Maß ändern", analyze = true, solved = res)
        return true
    }

    public fun cancelValueEdit() {
        valueEdit = null
        changed()
    }

    private fun titleOf(c: Dimension): String = when (c) {
        is LengthDim -> "Länge"
        is DistanceDim -> "Abstand"
        is dev.androidcad.sketch.HorizontalDistanceDim -> "Horizontaler Abstand"
        is dev.androidcad.sketch.VerticalDistanceDim -> "Vertikaler Abstand"
        is PointLineDistanceDim -> "Abstand"
        is RadiusDim -> "Radius"
        is DiameterDim -> "Durchmesser"
        is dev.androidcad.sketch.AngleDim -> "Winkel (°)"
    }

    // ---- Verlauf --------------------------------------------------------------------------------

    public fun undo() {
        cancelOperation()
        val snap = history.undo() ?: return
        restoreSnapshot(snap)
    }

    public fun redo() {
        cancelOperation()
        val snap = history.redo() ?: return
        restoreSnapshot(snap)
    }

    private fun restoreSnapshot(snap: Sketch) {
        sketch.restore(snap)
        selection = emptySet()
        status = sketch.solve()
        onSketchChanged?.invoke()
        changed()
    }

    /**
     * Die Skizze wurde von außen verändert (z. B. projizierte Körperkanten wurden eingefügt): löst sie neu, hält die Änderung im
     * Verlauf fest (rückgängig machbar) und meldet sie.
     */
    public fun recordExternalChange(label: String) {
        cancelOperation()
        commit(label, analyze = true)
    }

    /**
     * Die Skizze wurde von außen nachgeführt, ohne dass der Nutzer etwas getan hat (z. B. eine projizierte Kante hat sich mit dem
     * Körper bewegt): löst neu und aktualisiert die Anzeige, ohne einen Verlaufseintrag und ohne [onSketchChanged].
     */
    public fun refreshExternal() {
        status = sketch.solve(SolveOptions(analyze = true))
        changed()
    }

    /** Löst die Skizze, hält den Verlauf fest und meldet die Änderung. */
    private fun commit(label: String, analyze: Boolean, solved: SolveResult? = null) {
        status = solved ?: sketch.solve(SolveOptions(analyze = true))
        history.record(sketch.copy())
        selection = selection.filter { exists(it) }.toSet()
        message = if (status?.isSolved == false) "Skizze ist widersprüchlich" else null
        onSketchChanged?.invoke()
        changed()
    }

    private fun exists(s: Sel): Boolean = when (s) {
        is Sel.P -> sketch.exists(s.id)
        is Sel.L -> sketch.exists(s.id)
        is Sel.C -> sketch.exists(s.id)
        is Sel.A -> sketch.exists(s.id)
        is Sel.K -> runCatching { sketch.constraint(s.id) }.isSuccess
    }

    // ---- Anzeige --------------------------------------------------------------------------------

    public fun dimensionLabels(): List<DimLabel> = Annotations.labels(sketch, view)

    public fun badges(): List<Badge> = Annotations.badges(sketch, view)

    private fun labelAt(screen: Vec2): DimLabel? = dimensionLabels().lastOrNull { it.contains(screen) }

    /** Kurzer Statustext für die Statuszeile. */
    /** Ursprung und Achsen: Bezüge, die eine Rahmen- oder Gesamtauswahl nicht mit erfasst. */
    private fun isReference(s: Sel): Boolean = when (s) {
        is Sel.P -> s.id == sketch.origin || SketchOps.isAxisPoint(sketch, s.id)
        is Sel.L -> SketchOps.axisOf(sketch, s.id) != null
        else -> false
    }

    private var placementFor: SolveResult? = null
    private var placementValue: Int? = null

    /** Siehe [SketchOps.placementFreedom]; je Lösungsstand nur einmal berechnet. */
    private fun placementFree(): Int? {
        val s = status ?: return null
        if (placementFor !== s) { placementFor = s; placementValue = SketchOps.placementFreedom(sketch, s.degreesOfFreedom) }
        return placementValue
    }

    /**
     * Die X- ([world] nahe y = 0) bzw. Y-Achse als wählbare Linie (feste Konstruktionslinie, bei Bedarf angelegt) – damit gelten alle
     * Bedingungen und Maße auch gegenüber den Achsen. `null`, wenn keine Achse in der Nähe ist.
     */
    private fun axisAt(world: Vec2, tolerance: Double): Sel? {
        val horizontal = when {
            abs(world.y) <= tolerance -> true
            abs(world.x) <= tolerance -> false
            else -> return null
        }
        val existing = SketchOps.existingAxisLine(sketch, horizontal)
        val id = existing ?: SketchOps.axisLine(sketch, horizontal).also { status = sketch.solve(SolveOptions(analyze = true)) }
        return Sel.L(id)
    }

    public fun statusText(): String {
        val s = status ?: return ""
        return when {
            !s.isSolved -> "Widerspruch in den Bedingungen"
            s.degreesOfFreedom == 0 -> "Vollständig bestimmt"
            s.degreesOfFreedom < 0 -> ""
            placementFree() == 2 -> "Form bestimmt – Lage noch frei: mit Ursprung oder Achsen verbinden"
            placementFree() == 3 -> "Form bestimmt – Lage und Drehung noch frei: mit Ursprung oder Achsen verbinden"
            s.degreesOfFreedom == 1 -> "1 Freiheitsgrad"
            else -> "${s.degreesOfFreedom} Freiheitsgrade"
        }
    }

    private fun changed() {
        onChange?.invoke()
    }

    private fun dist(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)

    private fun angleOf(from: Vec2, to: Vec2) = atan2(to.y - from.y, to.x - from.x)

    private fun hitTolerancePx(kind: PointerKind): Double = if (kind == PointerKind.TOUCH) 16.0 else HIT_TOLERANCE_PX

    private fun dragThresholdPx(kind: PointerKind): Double = if (kind == PointerKind.TOUCH) 12.0 else 6.0

    public companion object {
        public const val HIT_TOLERANCE_PX: Double = 9.0
        public const val SNAP_TOLERANCE_PX: Double = 12.0

        /** Höchstzahl der Exemplare eines Skizzenmusters. */
        public const val MAX_PATTERN: Int = 50
        private const val AUTO_ANGLE_DEG = 3.0
        private const val MIN_SIZE = 1e-6
    }
}
