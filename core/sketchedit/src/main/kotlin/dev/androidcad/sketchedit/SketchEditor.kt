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
import dev.androidcad.sketch.EqualLength
import dev.androidcad.sketch.EqualRadius
import dev.androidcad.sketch.Parallel
import dev.androidcad.sketch.Perpendicular
import dev.androidcad.sketch.TangentLineRound
import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.chamfer
import dev.androidcad.sketch.split
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

    /** Langloch: Mittelpunkte der beiden Rundungen, dann die Breite. */
    SLOT,

    /** Regelmäßiges Vieleck: Mittelpunkt, dann eine Ecke bzw. Seitenmitte (siehe [PolygonMode]). */
    POLYGON,

    /** Einzelpunkt durch Tippen (mit Fangen), z. B. als Bezug für Bohrungsmitten. */
    POINT,

    /** Element an der angetippten Stelle bis zu den nächsten Schnittpunkten kürzen. */
    TRIM,

    /** Linie, Bogen oder Kreis an der angetippten Stelle in zwei Teile trennen (Kreis: zwei Halbkreise). */
    SPLIT,

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

    /**
     * Bemaßen: Kreis → Durchmesser, Bogen → Radius; Linie oder Punkt, dann ein zweites Element → Abstand bzw. Winkel,
     * oder nach einer Linie ins Leere tippen → Länge. Der Wert wird gleich abgefragt ([SketchEditor.pendingAction]).
     */
    DIMENSION,
    ;

    /** Werkzeuge, die vorhandene Geometrie bearbeiten (Tippen statt Zeichnen). */
    public val isModify: Boolean get() = this == TRIM || this == SPLIT || this == EXTEND || this == OFFSET || this == FILLET || this == MIRROR || this == DIMENSION || isPattern

    /** Die beiden Musterwerkzeuge. */
    public val isPattern: Boolean get() = this == PATTERN_LINEAR || this == PATTERN_CIRCULAR
}

/** Varianten des Rechteck-Werkzeugs. */
public enum class RectMode(public val label: String) {
    /** Zwei gegenüberliegende Ecken (achsparallel). */
    CORNERS("Ecke–Ecke"),

    /** Mittelpunkt, dann eine Ecke – das Rechteck bleibt über eine Hilfsdiagonale mittig auf dem Punkt. */
    CENTER("Mittelpunkt"),

    /** Zwei Punkte einer Kante (beliebig schräg), dann die Höhe. */
    THREE_POINTS("3 Punkte"),
}

/** Varianten des Kreis-Werkzeugs. */
public enum class CircleMode(public val label: String) {
    /** Mittelpunkt, dann ein Punkt auf dem Kreis. */
    CENTER("Mittelpunkt"),

    /** Zwei gegenüberliegende Punkte (Durchmesser). */
    TWO_POINTS("2 Punkte"),

    /** Drei Punkte auf dem Kreis. */
    THREE_POINTS("3 Punkte"),

    /** Berührt zwei Elemente (Linien, Kreise, Bögen); ein dritter Tipp gibt die Lage und damit die Größe vor. */
    TANGENT2("2 Tangenten"),

    /** Berührt drei Elemente – Lage und Größe folgen daraus. */
    TANGENT3("3 Tangenten"),
    ;

    /** Anzahl der Elemente, die der Kreis berühren soll (0 = keine Tangenten-Variante). */
    public val tangents: Int get() = when (this) { TANGENT2 -> 2; TANGENT3 -> 3; else -> 0 }
}

/** Varianten des Vieleck-Werkzeugs: wie das Vieleck an seinem (Hilfs-)Kreis liegt. */
public enum class PolygonMode(public val label: String) {
    /** Ecken auf dem Kreis (Kreis = Umkreis); der zweite Tipp setzt eine Ecke. */
    INSCRIBED("Ecken auf Kreis"),

    /** Seiten berühren den Kreis (Kreis = Inkreis, Durchmesser = Schlüsselweite); der zweite Tipp setzt eine Seitenmitte. */
    CIRCUMSCRIBED("Seiten am Kreis"),
}

/** Varianten des Linien-Werkzeugs. */
public enum class LineMode(public val label: String) {
    /** Gerade Linie(n) von Punkt zu Punkt. */
    NORMAL("Linie"),

    /** Endet die Linie auf einem Kreis oder Bogen, berührt sie ihn dort (an einem Ende oder an beiden: Tangente zwischen zwei Kreisen). */
    TANGENT("Tangential"),
}

/** Varianten des Bogen-Werkzeugs. */
public enum class ArcMode(public val label: String) {
    /** Mittelpunkt, Start, dann die Richtung des Endes (gegen den Uhrzeigersinn). */
    CENTER("Mittelpunkt"),

    /** Start, Ende, dann ein Punkt auf dem Bogen. */
    THREE_POINTS("3 Punkte"),

    /** Beginnt am Ende einer Linie oder eines Bogens und setzt sie tangential fort (Zug wie beim Linienwerkzeug). */
    TANGENT("Tangential"),
}

/** Varianten des Ecken-Werkzeugs. */
public enum class CornerMode(public val label: String) {
    /** Bogen mit Radius zwischen den beiden Linien. */
    ROUND("Runden"),

    /** Gerade Verbindungslinie, beide Linien um denselben Abstand gekürzt. */
    CHAMFER("Fase"),
}

public enum class PointerKind { MOUSE, TOUCH, STYLUS }

public enum class PointerButton { PRIMARY, SECONDARY, MIDDLE, ERASER }

/** Vorschau der gerade gezeichneten Geometrie (Skizzenkoordinaten). */
public sealed interface Preview {
    public data class Line(val a: Vec2, val b: Vec2) : Preview
    public data class Rect(val a: Vec2, val b: Vec2) : Preview

    /** Geschlossener Linienzug (z. B. schräges Rechteck). */
    public data class Polygon(val points: List<Vec2>) : Preview

    /** Mehrere Teile auf einmal (z. B. Langloch: zwei Linien und zwei Bögen). */
    public data class Group(val parts: List<Preview>) : Preview
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

    /** Bildschirmpixel je dp (Bildschirmdichte): Trefferradien, Fangradius und Maßabstände sind in dp gedacht. */
    public var uiScale: Double = 1.0

    /** Variante des Vieleck-Werkzeugs. */
    public var polygonMode: PolygonMode = PolygonMode.INSCRIBED
        set(v) { if (field != v) { field = v; resetDrawing() }; changed() }

    /** Anzahl der Ecken des Vieleck-Werkzeugs (3 … [MAX_POLYGON_SIDES]). */
    public var polygonSides: Int = 6
        set(v) { field = v.coerceIn(3, MAX_POLYGON_SIDES); changed() }

    /** Variante des Linien-Werkzeugs; ein Wechsel bricht einen angefangenen Zug ab. */
    public var lineMode: LineMode = LineMode.NORMAL
        set(v) { if (field != v) { field = v; resetDrawing() }; changed() }

    /** Variante des Rechteck-Werkzeugs; ein Wechsel bricht eine angefangene Form ab. */
    public var rectMode: RectMode = RectMode.CORNERS
        set(v) { if (field != v) { field = v; resetDrawing() }; changed() }

    /** Variante des Kreis-Werkzeugs. */
    public var circleMode: CircleMode = CircleMode.CENTER
        set(v) { if (field != v) { field = v; resetDrawing() }; changed() }

    /** Variante des Bogen-Werkzeugs. */
    public var arcMode: ArcMode = ArcMode.CENTER
        set(v) { if (field != v) { field = v; resetDrawing() }; changed() }

    /** Abstand (mm) des Versatz-Werkzeugs. */
    public var offsetDistance: Double = 5.0
        set(v) { field = v; changed() }

    /** Radius (mm) des Eckenverrundungs-Werkzeugs. */
    public var filletRadius: Double = 3.0
        set(v) { field = v; changed() }

    /** Variante des Ecken-Werkzeugs (Runden oder Fase). */
    public var cornerMode: CornerMode = CornerMode.ROUND
        set(v) { field = v; changed() }

    /** Abstand (mm) der Fase von der Ecke, auf beiden Linien gleich. */
    public var chamferDistance: Double = 2.0
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
    private var pending: SnapResult? = null   // erster Punkt (beim Bogen um den Mittelpunkt: der Mittelpunkt)
    private var second: SnapResult? = null    // zweiter Punkt bei Formen aus drei Punkten
    private var tangentPicks: List<Pair<Sel, Vec2>> = emptyList()  // Kreis an Tangenten: gewählte Elemente und Tippstellen

    /** `true`, solange eine mehrteilige Zeichenaktion läuft (z. B. Linienzug) – dann bietet die Oberfläche „Fertig“ an. */
    public val drawingInProgress: Boolean get() = pending != null || tangentPicks.isNotEmpty()

    /** Wie viele Punkte bzw. Elemente der laufenden Form schon gesetzt sind (0 … 3) – für den Hinweis der Oberfläche. */
    public val drawStep: Int get() = when {
        tool == Tool.CIRCLE && circleMode.tangents > 0 -> tangentPicks.size
        pending == null -> 0
        second == null -> 1
        else -> 2
    }

    /** `true`, wenn die laufende Aktion ein Zug ist, der mit „Fertig“ endet (Linien, tangentiale Bögen). */
    public val drawingChain: Boolean get() = tool == Tool.LINE || (tool == Tool.ARC && arcMode == ArcMode.TANGENT)

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
            val d = Down(screen, world, kind, additive, null, false, snapAt(world))
            down = d
            // Ein Finger schwebt nicht über der Fläche: die Vorschau erscheint schon beim Aufsetzen und folgt ihm, gesetzt wird beim Loslassen
            if (!tool.isModify) { hover = d.snap; updateDrawPreview(d.snap!!, d) }
        }
        changed()
    }

    /** Maß der Vorschau zum Anzeigen beim Zeichnen („25 mm“, „30 × 20 mm“, „Ø 12 mm“); `null`, wenn es nichts zu messen gibt. */
    public fun previewLabel(): String? = when (val p = preview) {
        is Preview.Line -> dist(p.a, p.b).takeIf { it > MIN_SIZE }?.let { "${formatValue(it)} mm" }
        is Preview.Rect -> (abs(p.b.x - p.a.x) to abs(p.b.y - p.a.y)).takeIf { (w, h) -> w > MIN_SIZE && h > MIN_SIZE }
            ?.let { (w, h) -> "${formatValue(w)} × ${formatValue(h)} mm" }
        is Preview.Circle -> p.radius.takeIf { it > MIN_SIZE }?.let { "Ø ${formatValue(2 * it)} mm" }
        is Preview.Arc -> dist(p.center, p.start).takeIf { it > MIN_SIZE }?.let { "R ${formatValue(it)} mm" }
        else -> null
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
            hoverHit = HitTest.hit(sketch, world, view.worldLength(HIT_TOLERANCE_PX * uiScale))
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
            hoverHit = if (tool.isModify) HitTest.hit(sketch, world, view.worldLength(HIT_TOLERANCE_PX * uiScale))
            else labelAt(screen)?.let { Sel.K(it.id) } ?: HitTest.hit(sketch, world, view.worldLength(HIT_TOLERANCE_PX * uiScale))
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
        second = null
        if (tangentPicks.isNotEmpty()) { tangentPicks = emptyList(); selection = emptySet() }
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

    private fun snapAt(world: Vec2): SnapResult = Snapping.snap(sketch, world, view, SnapOptions(grid = gridSnap, tolerancePx = SNAP_TOLERANCE_PX * uiScale))

    private fun updateDrawPreview(snap: SnapResult, d: Down?) {
        val start = pending ?: d?.snap
        preview = when (tool) {
            Tool.LINE -> start?.let { a ->
                if (lineMode == LineMode.TANGENT) trial { s -> tangentLine(s, a, snap) }?.let { (s, id) -> s.line(id).let { Preview.Line(s.point(it.p1), s.point(it.p2)) } }
                    ?: Preview.Line(a.position, snap.position)
                else Preview.Line(a.position, snap.position)
            }
            Tool.RECTANGLE -> when (rectMode) {
                RectMode.CORNERS -> start?.let { Preview.Rect(it.position, snap.position) }
                RectMode.CENTER -> start?.let { c -> Preview.Rect(Vec2(2 * c.position.x - snap.position.x, 2 * c.position.y - snap.position.y), snap.position) }
                RectMode.THREE_POINTS -> when (val b = second) {
                    null -> start?.let { Preview.Line(it.position, snap.position) }
                    else -> rect3(pending!!.position, b.position, snap.position)?.let { Preview.Polygon(it) } ?: Preview.Line(pending!!.position, b.position)
                }
            }
            Tool.CIRCLE -> when (circleMode) {
                CircleMode.CENTER -> start?.let { Preview.Circle(it.position, dist(it.position, snap.position)) }
                CircleMode.TWO_POINTS -> start?.let { Preview.Circle(mid(it.position, snap.position), dist(it.position, snap.position) / 2) }
                CircleMode.TANGENT2 -> if (tangentPicks.size == 2) {
                    trial { s -> tangentCircle(s, tangentPicks.map { it.first }, snap.position) }?.let { (s, id) -> s.circle(id).let { Preview.Circle(s.point(it.center), it.radius) } }
                } else null
                CircleMode.TANGENT3 -> null
                CircleMode.THREE_POINTS -> when (val b = second) {
                    null -> start?.let { Preview.Line(it.position, snap.position) }
                    else -> circumcenter(pending!!.position, b.position, snap.position)?.let { Preview.Circle(it, dist(it, snap.position)) }
                        ?: Preview.Line(pending!!.position, b.position)
                }
            }
            Tool.SLOT -> when (val b = second) {
                null -> start?.let { Preview.Line(it.position, snap.position) }
                else -> slot(pending!!.position, b.position, snap.position)?.let { g ->
                    Preview.Group(listOf(Preview.Line(g[0], g[1]), Preview.Line(g[2], g[3]), Preview.Arc(b.position, g[2], g[1]), Preview.Arc(pending!!.position, g[0], g[3])))
                } ?: Preview.Line(pending!!.position, b.position)
            }
            Tool.POLYGON -> start?.let { c ->
                polygon(c.position, snap.position)?.let { Preview.Group(listOf(Preview.Polygon(it), Preview.Circle(c.position, dist(c.position, snap.position)))) }
            }
            Tool.ARC -> if (arcMode == ArcMode.THREE_POINTS) {
                val a = pending; val b = second
                when {
                    a != null && b != null -> arc3(a.position, b.position, snap.position)?.let { (c, s, e) -> Preview.Arc(c, s, e) } ?: Preview.Line(a.position, b.position)
                    a != null -> Preview.Line(a.position, snap.position)
                    else -> null
                }
            } else if (arcMode == ArcMode.TANGENT) {
                pending?.point?.let { p -> tangentAt(p)?.let { (t, _) -> tangentArc(sketch.point(p), t, snap.position) } }
                    ?.let { (c, s, e) -> Preview.Arc(c, s, e) }
                    ?: pending?.let { Preview.Line(it.position, snap.position) }
            } else {
                val c = pending
                val s = second
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
            Tool.SELECT, Tool.POINT, Tool.TRIM, Tool.SPLIT, Tool.EXTEND, Tool.OFFSET, Tool.FILLET, Tool.MIRROR, Tool.PATTERN_LINEAR, Tool.PATTERN_CIRCULAR, Tool.DIMENSION -> null
        }
    }

    private fun drawUp(d: Down, up: SnapResult) {
        val dragged = d.moved
        val first = d.snap ?: return
        when (tool) {
            Tool.LINE -> when {
                dragged && pending == null -> { finishAnyLine(first, up); pending = null }   // Stift: ziehen = eine Linie
                pending == null -> pending = first
                else -> finishAnyLine(pending!!, up)?.let { pending = SnapResult(sketch.point(it), SnapKind.POINT, point = it) }
            }
            Tool.RECTANGLE -> if (rectMode == RectMode.THREE_POINTS) threeTaps(first, up, dragged) { a, b, c -> finishRect3(a, b, c) }
            else when {
                dragged && pending == null -> { finishTwoPointRect(first, up); pending = null }
                pending == null -> pending = first
                else -> { finishTwoPointRect(pending!!, up); pending = null }
            }
            Tool.CIRCLE -> if (circleMode.tangents > 0) tangentCircleTap(d)
            else if (circleMode == CircleMode.THREE_POINTS) threeTaps(first, up, dragged) { a, b, c -> finishCircle3(a, b, c) }
            else when {
                dragged && pending == null -> { finishTwoPointCircle(first, up); pending = null }
                pending == null -> pending = first
                else -> { finishTwoPointCircle(pending!!, up); pending = null }
            }
            Tool.SLOT -> threeTaps(first, up, dragged) { a, b, c -> finishSlot(a, b, c) }
            Tool.POINT -> finishPoint(up)
            Tool.POLYGON -> when {
                dragged && pending == null -> { finishPolygon(first, up); pending = null }
                pending == null -> pending = first
                else -> { finishPolygon(pending!!, up); pending = null }
            }
            Tool.ARC -> when (arcMode) {
                ArcMode.THREE_POINTS -> threeTaps(first, up, dragged) { a, b, c -> finishArc3(a, b, c) }
                ArcMode.TANGENT -> {
                    val start = pending
                    when {
                        start == null -> {
                            val p = first.point
                            if (p != null && tangentAt(p) != null) pending = first
                            else message = "Tangential: am Ende einer Linie oder eines Bogens beginnen."
                        }
                        else -> finishTangentArc(start.point!!, up)?.let { pending = SnapResult(sketch.point(it), SnapKind.POINT, point = it) }
                    }
                }
                ArcMode.CENTER -> {
                    val c = pending
                    val s = second
                    when {
                        c == null -> pending = first
                        s == null -> if (dist(c.position, first.position) > MIN_SIZE) second = first
                        else -> { finishArc(c, s, up.position); pending = null; second = null }
                    }
                }
            }
            Tool.SELECT, Tool.TRIM, Tool.SPLIT, Tool.EXTEND, Tool.OFFSET, Tool.FILLET, Tool.MIRROR, Tool.PATTERN_LINEAR, Tool.PATTERN_CIRCULAR, Tool.DIMENSION -> Unit
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
        val hit = if (tool == Tool.DIMENSION) HitTest.hit(sketch, world, view.worldLength(tolerancePx)) ?: axisAt(world, view.worldLength(tolerancePx))
        else if (tool == Tool.FILLET || tool == Tool.PATTERN_CIRCULAR) HitTest.hit(sketch, world, view.worldLength(tolerancePx))
        else HitTest.hitCurve(sketch, world, view.worldLength(tolerancePx))
            ?: if (tool == Tool.MIRROR || tool == Tool.PATTERN_LINEAR) axisAt(world, view.worldLength(tolerancePx)) else null
        when (tool) {
            Tool.TRIM -> when (hit) {
                is Sel.L -> runOp("Trimmen") { SketchOps.trim(sketch, hit.id, world) }
                is Sel.C -> runOp("Trimmen") { SketchOps.trim(sketch, hit.id, world) }
                is Sel.A -> runOp("Trimmen") { SketchOps.trim(sketch, hit.id, world) }
                else -> { message = "Tippe auf eine Linie, einen Kreis oder Bogen."; }
            }
            Tool.SPLIT -> when (hit) {
                is Sel.L -> runOp("Teilen") { SketchOps.split(sketch, hit.id, world) }
                is Sel.C -> runOp("Teilen") { SketchOps.split(sketch, hit.id, world) }
                is Sel.A -> runOp("Teilen") { SketchOps.split(sketch, hit.id, world) }
                else -> { message = "Tippe auf eine Linie, einen Kreis oder Bogen." }
            }
            Tool.EXTEND -> when (hit) {
                is Sel.L -> runOp("Verlängern") { SketchOps.extend(sketch, hit.id, world) }
                is Sel.A -> runOp("Verlängern") { SketchOps.extend(sketch, hit.id, world) }
                else -> { message = "Tippe auf das Ende einer Linie oder eines Bogens." }
            }
            Tool.OFFSET -> offsetTap(hit, world)
            Tool.DIMENSION -> dimensionTap(hit)
            Tool.FILLET -> when (hit) {
                is Sel.P -> if (cornerMode == CornerMode.CHAMFER) runOp("Ecke fasen") { SketchOps.chamfer(sketch, hit.id, chamferDistance) }
                    else runOp("Ecke runden") { SketchOps.fillet(sketch, hit.id, filletRadius) }
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

    /** Bemaßen: sammelt bis zu zwei Elemente in [selection] und fragt dann den Wert ab (siehe [Tool.DIMENSION]). */
    private fun dimensionTap(hit: Sel?) {
        val first = selection.singleOrNull()
        val kind: ActionKind = when {
            hit is Sel.C && first == null -> { selection = setOf(hit); ActionKind.DIAMETER }
            hit is Sel.A && first == null -> { selection = setOf(hit); ActionKind.RADIUS }
            hit == null || hit == first -> {
                if (first is Sel.L) ActionKind.LENGTH
                else { selection = emptySet(); return }
            }
            first == null -> {
                selection = setOf(hit)
                message = if (hit is Sel.L) "Zweites Element antippen – oder eine freie Stelle für die Länge." else "Zweites Element antippen (Punkt oder Linie)."
                messageIsInfo = true
                return
            }
            first is Sel.L && hit is Sel.L -> {
                if (abs(kotlin.math.sin(Math.toRadians(sketch.angleBetween(first.id, hit.id)))) > 1e-3) {
                    selection = setOf(first, hit); ActionKind.ANGLE
                } else {
                    // parallele Linien: Abstand eines Endpunkts der ersten zur zweiten
                    selection = setOf(Sel.P(sketch.line(first.id).p1), hit); ActionKind.DISTANCE
                }
            }
            else -> { selection = setOf(first, hit); ActionKind.DISTANCE }
        }
        requestAction(kind)
        if (pendingAction == null) { selection = emptySet(); message = "Diese Elemente lassen sich nicht miteinander bemaßen." }
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
        if (near < view.worldLength(HIT_TOLERANCE_PX * uiScale)) { message = "Tippe weiter vom Element entfernt, um die Seite zu wählen."; return }
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

    /** Punkt-Werkzeug: Einzelpunkt mit den Bedingungen aus dem Fangen (auf Linie, Kreis, Achse …). */
    private fun finishPoint(at: SnapResult) {
        if (at.point != null) { message = "Hier liegt schon ein Punkt."; return }
        val extra = ArrayList<Constraint>()
        val p = materialize(at, extra)
        sketch.markStandalone(p)
        extra.forEach(::tryAdd)
        commit("Punkt", analyze = true)
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

    /**
     * Probiert eine Konstruktion auf einer Kopie der Skizze aus ([build] legt an und gibt das neue Element zurück) und löst sie –
     * für die Vorschau von Formen, deren Lage erst der Löser findet. `null`, wenn es nicht geht.
     */
    private fun <T> trial(build: (Sketch) -> T?): Pair<Sketch, T>? {
        val copy = sketch.copy()
        return placeNew(copy, build)?.let { copy to it }
    }

    /**
     * Legt mit [build] neue Elemente in [s] an und löst so, dass sich möglichst nur die neuen bewegen: Punkte und Kreisradien, die es
     * vorher gab, werden dafür kurz festgehalten. Geht es so nicht, darf auch Vorhandenes nachgeben. `null`, wenn es gar nicht lösbar
     * ist (die Skizze ist dann verändert – der Aufrufer stellt sie wieder her).
     */
    private fun <T> placeNew(s: Sketch, build: (Sketch) -> T?): T? {
        val points = s.pointIds.toSet()
        val circles = s.circleIds.toSet()
        val id = try { build(s) } catch (e: IllegalArgumentException) { null } ?: return null
        val pins = points.map { p -> s.point(p).let { v -> s.add(Fixed(p, v.x, v.y)) } } + circles.map { c -> s.add(RadiusDim(c, s.circle(c).radius)) }
        s.solve(SolveOptions(analyze = false))
        pins.forEach { s.remove(it) }
        return if (s.solve(SolveOptions(analyze = false)).isSolved) id else null
    }

    /** Linie oder (Variante „Tangential“) tangentiale Linie. */
    private fun finishAnyLine(a: SnapResult, b: SnapResult): PointId? = if (lineMode == LineMode.TANGENT) finishTangentLine(a, b) else finishLine(a, b)

    /** Ein Kreis bzw. Bogen, auf dem ein Linienende gefangen wurde – nur dort kann die Linie ihn berühren. */
    private fun roundOf(s: SnapResult): dev.androidcad.sketch.RoundId? = if (s.kind == SnapKind.ON_ROUND) s.round else null

    /**
     * Legt in [s] eine Linie von [a] nach [b] an, die jeden Kreis/Bogen berührt, auf dem eines ihrer Enden gefangen wurde (Ende auf der
     * Kurve + [TangentLineRound]); der Löser schiebt die Enden an die Berührstellen. `null`, wenn kein Ende auf einem Kreis liegt.
     */
    private fun tangentLine(s: Sketch, a: SnapResult, b: SnapResult): LineId? {
        val rounds = listOfNotNull(roundOf(a), roundOf(b)).distinct()
        if (rounds.isEmpty() || dist(a.position, b.position) < MIN_SIZE) return null
        fun end(p: SnapResult): PointId = p.point ?: s.addPoint(p.position.x, p.position.y).also { id ->
            when (p.kind) {
                SnapKind.ON_ROUND -> s.add(PointOnRound(id, p.round!!))
                else -> constraintsFor(id, p).forEach { s.add(it) }
            }
        }
        val pa = end(a); val pb = end(b)
        if (pa == pb) return null
        val line = s.addLine(pa, pb)
        rounds.forEach { s.add(TangentLineRound(line, it)) }
        return line
    }

    private fun finishTangentLine(a: SnapResult, b: SnapResult): PointId? {
        if (roundOf(a) == null && roundOf(b) == null) { message = "Tangential: Anfang oder Ende auf einen Kreis oder Bogen legen."; return null }
        val before = sketch.copy()
        val line = placeNew(sketch) { s -> tangentLine(s, a, b) }
        if (line == null) {
            sketch.restore(before); message = "Hier gibt es keine Tangente."; return null
        }
        commit("Tangente", analyze = true)
        return sketch.line(line).p2
    }

    /** Abstand von [p] zu einem Element (Linie: Gerade; Kreis/Bogen: Vollkreis). */
    private fun distanceTo(s: Sketch, sel: Sel, p: Vec2): Double = when (sel) {
        is Sel.L -> abs(s.signedDistanceTo(sel.id, p))
        is Sel.C -> s.circle(sel.id).let { abs(dist(s.point(it.center), p) - it.radius) }
        is Sel.A -> abs(dist(s.point(s.arc(sel.id).center), p) - s.radiusOf(sel.id))
        else -> 0.0
    }

    private fun Sketch.signedDistanceTo(line: LineId, p: Vec2): Double {
        val l = line(line); val a = point(l.p1); val b = point(l.p2)
        val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-12)
        return ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / len
    }

    /**
     * Legt in [s] einen Kreis um [center] an, der alle [elements] berührt (Startradius = mittlerer Abstand; innen/außen ergibt sich aus
     * der Lage, [Sketch.addTangent]); der Löser findet die genaue Lage. `null` ohne sinnvollen Startradius.
     */
    private fun tangentCircle(s: Sketch, elements: List<Sel>, center: Vec2): CircleId? {
        val r = elements.map { distanceTo(s, it, center) }.average()
        if (!r.isFinite() || r < MIN_SIZE) return null
        val circle = s.addCircle(s.addPoint(center.x, center.y), r)
        for (e in elements) when (e) {
            is Sel.L -> s.add(TangentLineRound(e.id, circle))
            is Sel.C -> s.addTangent(e.id, circle)
            is Sel.A -> s.addTangent(e.id, circle)
            else -> return null
        }
        return circle
    }

    /** Ein Tipp beim Kreis an Tangenten: Element wählen bzw. (bei zwei Tangenten) zuletzt die Lage. */
    private fun tangentCircleTap(d: Down) {
        val need = circleMode.tangents
        if (tangentPicks.size < need) {
            val tol = view.worldLength(hitTolerancePx(d.kind))
            val hit = HitTest.hitCurve(sketch, d.world, tol) ?: axisAt(d.world, tol)
            if (hit == null || hit is Sel.P || hit is Sel.K) { message = "Tippe eine Linie, einen Kreis oder Bogen an."; return }
            if (tangentPicks.any { it.first == hit }) { message = "Dieses Element ist schon gewählt."; return }
            tangentPicks = tangentPicks + (hit to d.world)
            selection = tangentPicks.map { it.first }.toSet()
            if (need == 3 && tangentPicks.size == 3) {
                // Startlage: Kreis durch die drei Tippstellen (sie liegen meist nahe den Berührpunkten)
                val (p, q, r) = tangentPicks.map { it.second }
                finishTangentCircle(circumcenter(p, q, r) ?: Vec2((p.x + q.x + r.x) / 3, (p.y + q.y + r.y) / 3))
            }
        } else finishTangentCircle(d.world)
    }

    private fun finishTangentCircle(center: Vec2) {
        val elements = tangentPicks.map { it.first }
        tangentPicks = emptyList(); selection = emptySet()
        val before = sketch.copy()
        val circle = placeNew(sketch) { s -> tangentCircle(s, elements, center) }
        if (circle == null || sketch.circle(circle).radius < MIN_SIZE) {
            sketch.restore(before); message = "Kein Kreis gefunden, der alle gewählten Elemente berührt."; return
        }
        commit("Kreis", analyze = true)
    }

    /**
     * Ecken eines Langlochs mit den Rundungsmitten [c1], [c2] und der halben Breite = Abstand von [w] zur Mittellinie:
     * oben bei c1, oben bei c2, unten bei c2, unten bei c1 – oder `null`, wenn es zu klein ist.
     */
    private fun slot(c1: Vec2, c2: Vec2, w: Vec2): List<Vec2>? {
        val len = dist(c1, c2)
        if (len < MIN_SIZE) return null
        val nx = -(c2.y - c1.y) / len; val ny = (c2.x - c1.x) / len
        val r = abs((w.x - c1.x) * nx + (w.y - c1.y) * ny)
        if (r < MIN_SIZE) return null
        return listOf(Vec2(c1.x + nx * r, c1.y + ny * r), Vec2(c2.x + nx * r, c2.y + ny * r), Vec2(c2.x - nx * r, c2.y - ny * r), Vec2(c1.x - nx * r, c1.y - ny * r))
    }

    /**
     * Langloch: zwei Halbkreise (gleicher Radius) und zwei Linien, die sie berühren; dazu eine Hilfslinie zwischen den Mittelpunkten
     * (für die Länge). Frei bleiben Lage, Richtung, Länge und Breite – 5 Freiheitsgrade.
     */
    private fun finishSlot(a: SnapResult, b: SnapResult, w: SnapResult) {
        val g = slot(a.position, b.position, w.position) ?: return
        val extra = ArrayList<Constraint>()
        val c1 = materialize(a, extra)
        val c2 = materialize(b, extra)
        if (c1 == c2) { sketch.removeOrphanPoints(); return }
        val p = g.map { sketch.addPoint(it.x, it.y) }
        val top = sketch.addLine(p[0], p[1]); val bottom = sketch.addLine(p[2], p[3])
        val end2 = sketch.addArc(c2, p[2], p[1]); val end1 = sketch.addArc(c1, p[0], p[3])
        val axis = sketch.addLine(c1, c2, construction = true)
        for (l in listOf(top, bottom)) for (r in listOf(end1, end2)) sketch.add(TangentLineRound(l, r))
        sketch.add(EqualRadius(end1, end2))
        extra.forEach(::tryAdd)
        autoHorizontalVertical(axis)
        commit("Langloch", analyze = true)
    }

    /** Ecken des Vielecks um [center]; [at] ist je nach [polygonMode] eine Ecke oder eine Seitenmitte. `null`, wenn zu klein. */
    private fun polygon(center: Vec2, at: Vec2): List<Vec2>? {
        val r = dist(center, at)
        if (r < MIN_SIZE) return null
        val n = polygonSides
        val step = 2 * Math.PI / n
        val base = angleOf(center, at)
        val (start, radius) = if (polygonMode == PolygonMode.INSCRIBED) base to r else (base - step / 2) to r / kotlin.math.cos(step / 2)
        return List(n) { k -> Vec2(center.x + radius * kotlin.math.cos(start + k * step), center.y + radius * kotlin.math.sin(start + k * step)) }
    }

    /**
     * Regelmäßiges Vieleck mit Hilfskreis: gleich lange Seiten, Ecken auf dem Umkreis. Bei „Seiten am Kreis“ kommt der Inkreis dazu (eine
     * Seite berührt ihn – die übrigen folgen); nur Seiten am Kreis reichten nicht, ein gleichseitiges Vieleck mit gerader Eckenzahl
     * könnte sonst wie eine Raute kippen. Frei bleiben Mitte, Größe (Durchmesser bzw. Schlüsselweite) und Drehung – 4 Freiheitsgrade.
     */
    private fun finishPolygon(center: SnapResult, at: SnapResult) {
        val pts = polygon(center.position, at.position) ?: return
        val extra = ArrayList<Constraint>()
        val c = materialize(center, extra)
        val r = dist(center.position, at.position)
        val outer = sketch.addCircle(c, if (polygonMode == PolygonMode.INSCRIBED) r else dist(center.position, pts[0]), construction = true)
        val ids = pts.map { sketch.addPoint(it.x, it.y) }
        val sides = ids.indices.map { k -> sketch.addLine(ids[k], ids[(k + 1) % ids.size]) }
        // Kette statt Stern: die Symbole „=“ verteilen sich auf die Seiten, statt sich an der ersten zu sammeln
        for (k in 1 until sides.size) sketch.add(EqualLength(sides[k - 1], sides[k]))
        ids.forEach { sketch.add(PointOnRound(it, outer)) }
        if (polygonMode == PolygonMode.CIRCUMSCRIBED) sketch.add(TangentLineRound(sides[0], sketch.addCircle(c, r, construction = true)))
        extra.forEach(::tryAdd)
        commit("Vieleck", analyze = true)
    }

    /** Drei Punkte nacheinander (der erste darf auch gezogen werden: Ziehen setzt die ersten beiden). */
    private fun threeTaps(first: SnapResult, up: SnapResult, dragged: Boolean, finish: (SnapResult, SnapResult, SnapResult) -> Unit) {
        val a = pending; val b = second
        when {
            a == null && dragged -> if (dist(first.position, up.position) > MIN_SIZE) { pending = first; second = up }
            a == null -> pending = first
            b == null -> if (dist(a.position, up.position) > MIN_SIZE) second = up
            else -> { finish(a, b, up); pending = null; second = null }
        }
    }

    private fun resetDrawing() {
        pending = null; second = null; preview = null
        if (tangentPicks.isNotEmpty()) { tangentPicks = emptyList(); selection = emptySet() }
    }

    private fun finishTwoPointRect(a: SnapResult, b: SnapResult) = if (rectMode == RectMode.CENTER) finishCenterRect(a, b) else finishRect(a, b)

    private fun finishTwoPointCircle(a: SnapResult, b: SnapResult) = if (circleMode == CircleMode.TWO_POINTS) finishCircle2(a, b) else finishCircle(a, b)

    /** Rechteck um den Mittelpunkt [center]: Hilfsdiagonale mit Mittelpunkt-Bedingung, die Ecke bei [corner] folgt dem Fang. */
    private fun finishCenterRect(center: SnapResult, corner: SnapResult) {
        val c = center.position; val q = corner.position
        val dx = abs(q.x - c.x); val dy = abs(q.y - c.y)
        if (dx < MIN_SIZE / 2 || dy < MIN_SIZE / 2) return
        val r = sketch.addRectangle(c.x - dx, c.y - dy, c.x + dx, c.y + dy)
        val diagonal = sketch.addLine(r.corners[0], r.corners[2], construction = true)
        val extra = ArrayList<Constraint>()
        val m = materialize(center, extra)
        sketch.add(Midpoint(m, diagonal))
        extra.forEach(::tryAdd)
        val near = r.corners.minBy { dist(sketch.point(it), q) }
        constraintsFor(near, corner).forEach(::tryAdd)
        commit("Rechteck", analyze = true)
    }

    /** Ecken eines Rechtecks mit der Kante [a]–[b] und der Höhe bis [c] (senkrecht zur Kante), oder `null`, wenn es zu klein ist. */
    private fun rect3(a: Vec2, b: Vec2, c: Vec2): List<Vec2>? {
        val len = dist(a, b)
        if (len < MIN_SIZE) return null
        val nx = -(b.y - a.y) / len; val ny = (b.x - a.x) / len
        val h = (c.x - a.x) * nx + (c.y - a.y) * ny
        if (abs(h) < MIN_SIZE) return null
        return listOf(a, b, Vec2(b.x + nx * h, b.y + ny * h), Vec2(a.x + nx * h, a.y + ny * h))
    }

    /** Schräges Rechteck: Kante [a]–[b], Höhe bis [c]. Gegenüberliegende Seiten parallel, eine Ecke rechtwinklig. */
    private fun finishRect3(a: SnapResult, b: SnapResult, c: SnapResult) {
        val pts = rect3(a.position, b.position, c.position) ?: return
        val extra = ArrayList<Constraint>()
        val p0 = materialize(a, extra)
        val p1 = materialize(b, extra)
        if (p0 == p1) { sketch.removeOrphanPoints(); return }
        val p2 = sketch.addPoint(pts[2].x, pts[2].y)
        val p3 = sketch.addPoint(pts[3].x, pts[3].y)
        val e0 = sketch.addLine(p0, p1); val e1 = sketch.addLine(p1, p2); val e2 = sketch.addLine(p2, p3); val e3 = sketch.addLine(p3, p0)
        sketch.add(Perpendicular(e0, e1)); sketch.add(Parallel(e0, e2)); sketch.add(Parallel(e1, e3))
        extra.forEach(::tryAdd)
        autoHorizontalVertical(e0)
        commit("Rechteck", analyze = true)
    }

    /** Gefangene vorhandene Punkte liegen danach auf der Kurve (Kreis durch zwei bzw. drei Punkte, Bogen durch einen Punkt). */
    private fun bindOnRound(s: SnapResult, round: dev.androidcad.sketch.RoundId) {
        if (s.kind == SnapKind.POINT && s.point != null) tryAdd(PointOnRound(s.point, round))
    }

    private fun finishCircle2(a: SnapResult, b: SnapResult) {
        val r = dist(a.position, b.position) / 2
        if (r < MIN_SIZE / 2) return
        val m = mid(a.position, b.position)
        val circle = sketch.addCircle(sketch.addPoint(m.x, m.y), r)
        bindOnRound(a, circle); bindOnRound(b, circle)
        commit("Kreis", analyze = true)
    }

    private fun finishCircle3(a: SnapResult, b: SnapResult, c: SnapResult) {
        val m = circumcenter(a.position, b.position, c.position) ?: run { message = "Die drei Punkte liegen auf einer Geraden."; return }
        val r = dist(m, a.position)
        if (r < MIN_SIZE) return
        val circle = sketch.addCircle(sketch.addPoint(m.x, m.y), r)
        bindOnRound(a, circle); bindOnRound(b, circle); bindOnRound(c, circle)
        commit("Kreis", analyze = true)
    }

    /** Bogen von [s] nach [e] durch [m]: Mittelpunkt, Start, Ende (gegen den Uhrzeigersinn) – oder `null`, wenn alles auf einer Geraden liegt. */
    private fun arc3(s: Vec2, e: Vec2, m: Vec2): Triple<Vec2, Vec2, Vec2>? {
        val c = circumcenter(s, m, e) ?: return null
        val tau = 2 * Math.PI
        fun turn(p: Vec2) = ((angleOf(c, p) - angleOf(c, s)) % tau + tau) % tau
        return if (turn(m) < turn(e)) Triple(c, s, e) else Triple(c, e, s)
    }

    private fun finishArc3(s: SnapResult, e: SnapResult, through: SnapResult) {
        val (c, start, _) = arc3(s.position, e.position, through.position) ?: run { message = "Die drei Punkte liegen auf einer Geraden."; return }
        if (dist(c, s.position) < MIN_SIZE) return
        val extra = ArrayList<Constraint>()
        val ps = materialize(s, extra)
        val pe = materialize(e, extra)
        if (ps == pe) { sketch.removeOrphanPoints(); return }
        val center = sketch.addPoint(c.x, c.y)
        val arc = if (start == s.position) sketch.addArc(center, ps, pe) else sketch.addArc(center, pe, ps)
        extra.forEach(::tryAdd)
        bindOnRound(through, arc)
        commit("Bogen", analyze = true)
    }

    /**
     * Richtung, in der eine Kontur am Endpunkt [p] weiterläuft (Einheitsvektor), und das Element, das dort endet – die jüngste Linie
     * bzw. der jüngste Bogen (beim Weiterzeichnen eines Zugs das zuletzt gezeichnete). `null`, wenn dort nichts endet.
     */
    private fun tangentAt(p: PointId): Pair<Vec2, Any>? {
        val here = sketch.point(p)
        val lines = sketch.lineIds.filter { id -> SketchOps.axisOf(sketch, id) == null && sketch.line(id).let { it.p1 == p || it.p2 == p } }
        val arcs = sketch.arcIds.filter { id -> sketch.arc(id).let { it.start == p || it.end == p } }
        val newest = (lines.map { it.value to (it as Any) } + arcs.map { it.value to (it as Any) }).maxByOrNull { it.first }?.second ?: return null
        val t = when (newest) {
            is LineId -> sketch.line(newest).let { l -> sketch.point(if (l.p1 == p) l.p2 else l.p1) }.let { o -> Vec2(here.x - o.x, here.y - o.y) }
            is ArcId -> {
                val c = sketch.point(sketch.arc(newest).center)
                val rx = here.x - c.x; val ry = here.y - c.y
                // am Ende läuft der Bogen gegen den Uhrzeigersinn weiter, am Anfang in Gegenrichtung
                if (sketch.arc(newest).end == p) Vec2(-ry, rx) else Vec2(ry, -rx)
            }
            else -> return null
        }
        val len = hypot(t.x, t.y)
        if (len < 1e-12) return null
        return Vec2(t.x / len, t.y / len) to newest
    }

    /** Tangentialer Bogen ab [s] in Richtung [t] bis [e]: Mittelpunkt, Start, Ende (gegen den Uhrzeigersinn) – `null`, wenn [e] geradeaus liegt. */
    private fun tangentArc(s: Vec2, t: Vec2, e: Vec2): Triple<Vec2, Vec2, Vec2>? {
        val dx = e.x - s.x; val dy = e.y - s.y
        val nx = -t.y; val ny = t.x
        val side = 2 * (dx * nx + dy * ny)
        val d2 = dx * dx + dy * dy
        if (d2 < MIN_SIZE * MIN_SIZE || abs(side) < 1e-6 * kotlin.math.sqrt(d2)) return null
        val r = d2 / side
        if (abs(r) > MAX_TANGENT_RADIUS) return null
        val c = Vec2(s.x + nx * r, s.y + ny * r)
        return if (r > 0) Triple(c, s, e) else Triple(c, e, s)
    }

    /** Setzt die Kontur am Punkt [start] mit einem tangentialen Bogen bis [end] fort; zurück kommt der Endpunkt (für den Zug). */
    private fun finishTangentArc(start: PointId, end: SnapResult): PointId? {
        val (t, source) = tangentAt(start) ?: return null
        val s = sketch.point(start)
        val (c, from, _) = tangentArc(s, t, end.position) ?: run { message = "Das Ende liegt geradeaus – dafür das Linienwerkzeug nehmen."; return null }
        val extra = ArrayList<Constraint>()
        val pe = materialize(end, extra)
        if (pe == start) return null
        val center = sketch.addPoint(c.x, c.y)
        val arc = if (from == s) sketch.addArc(center, start, pe) else sketch.addArc(center, pe, start)
        when (source) {
            is LineId -> sketch.add(TangentLineRound(source, arc))
            is ArcId -> sketch.addTangent(source, arc)
        }
        extra.forEach(::tryAdd)
        commit("Bogen", analyze = true)
        return pe
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
        val hit = HitTest.hit(sketch, view.toWorld(screen), view.worldLength(HIT_TOLERANCE_PX * uiScale)) ?: return
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
        if (!validDimension(p.kind, value)) {
            message = if (p.kind == ActionKind.PATTERN_COUNT) "Anzahl: ganze Zahl von 2 bis ${SketchOps.MAX_PATTERN_COUNT}" else "Ungültiger Wert"
            if (tool == Tool.DIMENSION) selection = emptySet()
            changed(); return false
        }
        return applyAction(p.kind, value).also { if (tool == Tool.DIMENSION) { selection = emptySet(); changed() } }
    }

    public fun cancelPendingAction() {
        pendingAction = null
        if (tool == Tool.DIMENSION) selection = emptySet()
        changed()
    }

    private fun validDimension(kind: ActionKind, v: Double): Boolean = v.isFinite() && when (kind) {
        ActionKind.LENGTH, ActionKind.DISTANCE, ActionKind.RADIUS, ActionKind.DIAMETER -> v > 0
        ActionKind.PATTERN_COUNT -> v >= 2 && v <= SketchOps.MAX_PATTERN_COUNT && v == Math.rint(v)
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
        if (kind == ActionKind.PATTERN_COUNT) return runOp("Anzahl im Muster") {
            val pattern = SketchOps.patternOf(sketch, info.points, info.lines, info.rounds)
                ?: return@runOp dev.androidcad.sketch.OpResult.fail("Die Auswahl gehört zu keinem Muster.")
            SketchOps.setPatternCount(sketch, pattern, value!!.toInt())
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

    public fun dimensionLabels(): List<DimLabel> = Annotations.labels(sketch, view, uiScale)

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

    private fun mid(a: Vec2, b: Vec2) = Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)

    /** Mittelpunkt des Kreises durch drei Punkte; `null`, wenn sie (fast) auf einer Geraden liegen. */
    private fun circumcenter(a: Vec2, b: Vec2, c: Vec2): Vec2? {
        val d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        val scale = maxOf(dist(a, b), dist(b, c), dist(a, c))
        if (abs(d) < 1e-6 * scale * scale) return null
        val a2 = a.x * a.x + a.y * a.y; val b2 = b.x * b.x + b.y * b.y; val c2 = c.x * c.x + c.y * c.y
        return Vec2((a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d, (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d)
    }

    private fun angleOf(from: Vec2, to: Vec2) = atan2(to.y - from.y, to.x - from.x)

    private fun hitTolerancePx(kind: PointerKind): Double = (if (kind == PointerKind.TOUCH) 16.0 else HIT_TOLERANCE_PX) * uiScale

    private fun dragThresholdPx(kind: PointerKind): Double = (if (kind == PointerKind.TOUCH) 12.0 else 6.0) * uiScale

    public companion object {
        public const val HIT_TOLERANCE_PX: Double = 9.0
        public const val SNAP_TOLERANCE_PX: Double = 12.0

        /** Höchstzahl der Exemplare eines Skizzenmusters. */
        public const val MAX_PATTERN: Int = 50
        private const val AUTO_ANGLE_DEG = 3.0
        private const val MIN_SIZE = 1e-6

        /** Höchste Eckenzahl des Vieleck-Werkzeugs. */
        public const val MAX_POLYGON_SIDES: Int = 64

        /** Größter Radius eines tangentialen Bogens (mm); darüber gilt das Ende als geradeaus. */
        private const val MAX_TANGENT_RADIUS = 1e5
    }
}
