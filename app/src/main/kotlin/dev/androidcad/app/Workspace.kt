package dev.androidcad.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.androidcad.brep.BrepMesh
import dev.androidcad.geometry.Mesh
import dev.androidcad.model.BodyOp
import dev.androidcad.model.Document
import dev.androidcad.model.DocumentCodec
import dev.androidcad.model.EdgeKind
import dev.androidcad.model.EdgeProjection
import dev.androidcad.model.MirrorBodyFeature
import dev.androidcad.model.MirrorNaming
import dev.androidcad.model.PatternFeature
import dev.androidcad.model.PlaneFeature
import dev.androidcad.model.PlaneDef
import dev.androidcad.model.DatumPlane
import dev.androidcad.model.PatternGeometry
import dev.androidcad.model.PatternKind
import dev.androidcad.model.ProjectedEdge
import dev.androidcad.model.EdgeRef
import dev.androidcad.model.ExtrudeFeature
import dev.androidcad.model.RevolveAxis
import dev.androidcad.model.RevolveFeature
import dev.androidcad.model.ShellFeature
import dev.androidcad.model.RevolveGeometry
import dev.androidcad.model.FaceRef
import dev.androidcad.model.FilletFeature
import dev.androidcad.model.EdgeDistance
import dev.androidcad.model.FacePlane
import dev.androidcad.model.Workplane
import dev.androidcad.model.HolePlacement
import dev.androidcad.model.HoleFeature
import dev.androidcad.model.Vec3
import dev.androidcad.sketch.SketchOps
import dev.androidcad.brep.PlanarFace
import dev.androidcad.viewport.FaceHit
import dev.androidcad.model.Feature
import dev.androidcad.model.FeatureId
import dev.androidcad.model.PlaneSpec
import dev.androidcad.model.ModelSession
import dev.androidcad.model.SketchFeature
import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.worldSegments
import dev.androidcad.project.ProjectInfo
import dev.androidcad.sketch.regions
import dev.androidcad.sketchedit.SketchEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ergebnis eines Modell-Neuaufbaus. */
class ModelOutput(
    val mesh: Mesh?,
    /** Kanten als Linienpaare (x1 y1 z1 x2 y2 z2 …). */
    val edges: FloatArray?,
    val volumeMm3: Double,
    val faceCount: Int,
    val errors: List<String>,
    val millis: Long,
    /** Kanten als Polylinien (Punkte hintereinander, je Kante [edgeCounts] Punkte) – für das Antippen im Viewer. */
    val polylines: FloatArray = FloatArray(0),
    val edgeCounts: IntArray = IntArray(0),
    /** Je Kante die Namen ihrer Nachbarflächen; `null`, wenn die Kante nicht wählbar ist (unbenannte Fläche, Zylindernaht). */
    val edgeRefs: List<EdgeRef?> = emptyList(),
    /** Flächen-ID je Dreieck des Netzes (1-basiert). */
    val triangleFace: IntArray = IntArray(0),
    /** Je Fläche (Index = ID − 1) ihr Name; `null`, wenn unbenannt. */
    val faceRefs: List<FaceRef?> = emptyList(),
    /** Je Fläche (Index = ID − 1) die Ebene, wenn sie eben ist. */
    val facePlanes: List<PlanarFace?> = emptyList(),
    /** Je Kante (wie [edgeRefs]) die Endpunkte, wenn sie gerade ist (als Referenz für Bohrungsabstände). */
    val edgeLines: List<HolePlacement.Line?> = emptyList(),
    /** Die beim Aufbau verwendete Ebene jeder Skizze (bei Skizzen auf Flächen aus der Fläche berechnet). */
    val sketchPlanes: Map<FeatureId, Workplane> = emptyMap(),
    /** Je Skizze auf einer Fläche die Kanten der Fläche, die sich projizieren lassen. */
    val projectable: Map<FeatureId, List<EdgeProjection.Geometry>> = emptyMap(),
    /** Aktuelle Lage der projizierten Kanten je Skizze (zum Nachführen der lebenden Skizzen). */
    val projected: Map<FeatureId, List<Pair<ProjectedEdge, EdgeProjection.Geometry>>> = emptyMap(),
    /** Features mit Fehler beim letzten Aufbau (für die Markierung im Feature-Baum). */
    val errorIds: Set<FeatureId> = emptySet(),
    /** Die beim Aufbau berechnete Lage jeder Arbeitsebene. */
    val datumPlanes: Map<FeatureId, Workplane> = emptyMap(),
    /** Hüllbox des Netzes (kleinste, größte Ecke); `null` ohne Körper. Einmal beim Aufbau berechnet, nicht bei jeder Anzeige. */
    val bounds: Pair<Vec3, Vec3>? = null,
)

/** Standardansichten des 3D-Viewers. */
enum class StdView(val label: String) { ISO("Iso"), TOP("Oben"), FRONT("Vorne"), RIGHT("Rechts") }

/** Art der Vervielfältigung beim Anlegen (siehe [WorkspaceState.createPattern]). */
enum class PatternType { LINEAR, CIRCULAR, MIRROR }

/** Arbeitsmodus: Skizze zeichnen (2D, ganzer Bildschirm) oder am Körper arbeiten (3D, ganzer Bildschirm). */
enum class WorkMode { SKETCH, MODEL }

/** Bohrungsmodus: Der Nutzer tippt eine ebene Fläche an; [face] ist die gewählte Fläche, [point] der Tippunkt, [normal] ihre Außennormale. */
class HoleMode(
    val face: FaceRef?,
    val point: DoubleArray?,
    val normal: DoubleArray?,
    val plane: PlanarFace? = null,
    /** Parametrische Position (Abstände zu zwei geraden Randkanten der Fläche); `null`, wenn sich keine zwei passenden Kanten fanden. */
    val anchors: Pair<EdgeDistance, EdgeDistance>? = null,
    val lines: Pair<HolePlacement.Line, HolePlacement.Line>? = null,
)

/** Schalenmodus: Der Nutzer tippt die Flächen an, die offen bleiben sollen ([selected]). */
class ShellMode(val selected: List<FaceRef>)

/** Kantenmodus: Der Nutzer tippt Kanten im 3D-Bild an, um sie zu verrunden oder anzufasen. */
class EdgeMode(val kind: EdgeKind, val selected: List<EdgeRef>)

/**
 * Macht den [SketchEditor] für Compose beobachtbar: jede Zustandsänderung erhöht [version]; Composables, die [version]
 * lesen, werden neu gezeichnet. Der Editor selbst bleibt frei von Compose.
 */
class EditorHandle(editor: SketchEditor) {
    /** Ränder (px) der Zeichenfläche, über denen schwebende Bedienelemente liegen; „Einpassen“ hält sie frei. */
    var insetLeft = 0.0
    var insetTop = 0.0

    /** Passt die Skizze in den freien Teil der Zeichenfläche ein ([reference]: zusätzlich sichtbare Punkte, z. B. Flächenumriss). */
    fun fit(reference: List<dev.androidcad.sketch.Vec2>) = editor.fit(width, height, reference, insetLeft, insetTop)

    var editor by mutableStateOf(editor)
        private set
    var version by mutableIntStateOf(0)
        private set

    /** Größe der Zeichenfläche in px (für „Einpassen“). */
    var width = 0.0
    var height = 0.0
    var fitted = false

    init {
        bind(editor)
    }

    fun replace(newEditor: SketchEditor) {
        editor = newEditor
        fitted = false
        bind(newEditor)
        version++
    }

    private fun bind(e: SketchEditor) {
        e.onChange = { version++ }
    }
}

/**
 * Zustand der Arbeitsfläche: ein Dokument mit Feature-Historie (Skizzen und Extrusionen). Der Skizzen-Editor arbeitet
 * immer an der **aktiven Skizze** – das ist die gewählte Skizze oder die Skizze der gewählten Extrusion. Jede Änderung
 * baut den Körper auf einem Hintergrund-Thread neu (aus einer Momentaufnahme des Dokuments, damit die Bearbeitung nie
 * blockiert oder mit dem Aufbau kollidiert).
 *
 * Änderungen werden entprellt automatisch im Projektspeicher gesichert ([session]); [flush] sichert sofort (onStop).
 * Beim Start wird das zuletzt geöffnete Modell wiederhergestellt.
 */
class WorkspaceState(private val scope: CoroutineScope, private val session: ModelSession, val prefs: UiPrefs = UiPrefs()) {
    private var doc = Document()
    private var activeSketch: SketchFeature = doc.addSketch("Skizze 1")

    val handle = EditorHandle(SketchEditor(activeSketch.sketch))

    /** Alle Features in Aufbau-Reihenfolge (für die Feature-Leiste). */
    var features by mutableStateOf<List<Feature>>(emptyList())
        private set
    var selectedId by mutableStateOf<FeatureId?>(null)
        private set
    /** Die gewählte Extrusion (`null`, wenn eine Skizze gewählt ist). */
    var extrusion by mutableStateOf<ExtrudeFeature?>(null)
        private set
    /** Die gewählte Drehung (`null`, wenn ein anderes Feature gewählt ist). */
    var revolve by mutableStateOf<RevolveFeature?>(null)
        private set
    /** Die gewählte Schale (`null`, wenn ein anderes Feature gewählt ist). */
    var shell by mutableStateOf<ShellFeature?>(null)
        private set
    /** Aktiver Schalenmodus (`null` = aus) und die eingestellte Wandstärke (mm). */
    var shellMode by mutableStateOf<ShellMode?>(null)
        private set
    var shellThickness by mutableStateOf(2.0)
    /** Die gewählte Verrundung/Fase (`null`, wenn ein anderes Feature gewählt ist). */
    var fillet by mutableStateOf<FilletFeature?>(null)
        private set
    /** Skizzenmodus (2D-Zeichenfläche) oder Modellmodus (3D); ein neues, leeres Modell beginnt in der Skizze. */
    var mode by mutableStateOf(WorkMode.SKETCH)
        private set
    /** Blickrichtung des 3D-Viewers; beim Wechsel Skizze → Modell schwenkt sie von der Skizzenebene in die isometrische Ansicht. */
    val camera = ViewCamera()
    /** Linienpaare der Skizzen im Raum (aktive und noch nicht extrudierte) für die 3D-Ansicht. */
    var sketchOverlay by mutableStateOf<FloatArray?>(null)
        private set
    /** Die gewählte Bohrung (`null`, wenn ein anderes Feature gewählt ist). */
    var hole by mutableStateOf<HoleFeature?>(null)
        private set
    /** Das gewählte Muster bzw. die gewählte Spiegelung (`null`, wenn ein anderes Feature gewählt ist). */
    var pattern by mutableStateOf<PatternFeature?>(null)
        private set
    /** Aktiver Bohrungsmodus (`null` = aus) und die eingestellten Maße: Durchmesser, Tiefe (mm), durchgehend. */
    var holeMode by mutableStateOf<HoleMode?>(null)
        private set
    var holeDiameter by mutableStateOf(6.0)
    var holeDepth by mutableStateOf(10.0)
    var holeThrough by mutableStateOf(true)
    /** Die gewählte Arbeitsebene (`null`, wenn ein anderes Feature gewählt ist). */
    var datum by mutableStateOf<PlaneFeature?>(null)
        private set
    /** Die gewählte Spiegelung des ganzen Körpers (`null`, wenn ein anderes Feature gewählt ist). */
    var mirrorBody by mutableStateOf<MirrorBodyFeature?>(null)
        private set
    /** Fläche antippen, aus der eine neue Arbeitsebene entsteht (Auswahlmodus an/aus). */
    var planePick by mutableStateOf(false)
        private set
    /** Fläche antippen, auf die eine neue Skizze gelegt werden soll (Auswahlmodus an/aus). */
    var faceSketchPick by mutableStateOf(false)
        private set
    /**
     * Mitte der Fläche, auf der die aktive Skizze liegt, für „Mittig auf Fläche“: projiziert bei Bedarf ihre Kanten (fester, mitwandernder
     * Umriss) und nimmt die Diagonale zwischen der Ecke links unten und rechts oben; bei einer runden Fläche den Mittelpunkt des größten
     * Kreises. `null`, solange der Körper noch berechnet wird.
     */
    private fun faceCenterTarget(): SketchOps.CenterTarget? {
        val f = activeSketch
        if (f.onFace == null) return null
        val edges = output?.projectable?.get(f.id) ?: return null
        doc.addProjections(f.id, edges)
        val now = doc.sketch(f.id)
        activeSketch = now
        val sk = now.sketch
        val live = now.projections.filter { EdgeProjection.isLive(sk, it) }
        val ends = live.mapNotNull { it.line }.flatMap { sk.line(it).let { l -> listOf(l.p1, l.p2) } }
        if (ends.size >= 2) {
            val lo = ends.minBy { sk.point(it).let { p -> p.x + p.y } }
            val hi = ends.maxBy { sk.point(it).let { p -> p.x + p.y } }
            return SketchOps.CenterTarget.Diagonal(lo, hi)
        }
        val circle = live.mapNotNull { it.circle }.maxByOrNull { sk.circle(it).radius } ?: return null
        return SketchOps.CenterTarget.Point(sk.circle(circle).center)
    }

    /** Umriss des Körpers in der Ebene der aktiven Skizze als 2D-Linienpaare (x1 y1 x2 y2 …) – Orientierung beim Zeichnen. */
    var referenceLines by mutableStateOf<FloatArray?>(null)
        private set
    /** Aktiver Kantenmodus (`null` = aus) und die gerade eingestellte Größe (Radius bzw. Fasenabstand in mm). */
    var edgeMode by mutableStateOf<EdgeMode?>(null)
        private set
    var edgeSize by mutableStateOf(2.0)
    /** Linienpaare der gewählten Kanten für die Hervorhebung im Viewer. */
    var highlight by mutableStateOf<FloatArray?>(null)
        private set
    /** Die aktive Skizze samt Ebene (immer vorhanden). */
    var sketch by mutableStateOf(activeSketch)
        private set
    var output by mutableStateOf<ModelOutput?>(null)
        private set
    var building by mutableStateOf(false)
        private set
    /** Anzahl geschlossener Flächen in der aktiven Skizze (Voraussetzung für die Extrusion). */
    var regionCount by mutableIntStateOf(0)
        private set
    /** Ist gerade eine Skizze gewählt (Voraussetzung für „Extrudieren“ und „Abtragen“)? */
    val sketchSelected: Boolean get() = features.firstOrNull { it.id == selectedId } is SketchFeature

    /** Schwenkt die 3D-Ansicht in eine Standardansicht (der Zoom wird dabei auf „eingepasst“ zurückgesetzt). */
    fun showView(view: StdView) {
        val (yaw, pitch) = when (view) {
            StdView.ISO -> ViewCamera.ISO_YAW to ViewCamera.ISO_PITCH
            StdView.TOP -> ViewCamera.planeView(Workplane.XY)
            StdView.FRONT -> ViewCamera.planeView(Workplane.XZ)
            StdView.RIGHT -> ViewCamera.planeView(Workplane.YZ)
        }
        cameraJob?.cancel()
        cameraJob = scope.launch { camera.animateTo(yaw, pitch, 400) }
    }

    /** Passt das Modell wieder in die Ansicht ein (Blickrichtung bleibt). */
    fun fitView() {
        cameraJob?.cancel()
        cameraJob = scope.launch { camera.animateTo(camera.yaw, camera.pitch, 250) }
    }

    /** Gibt es schon Material, von dem man abtragen könnte (eine „Hinzufügen“-Extrusion)? */
    val canCut: Boolean get() = features.any { (it is ExtrudeFeature && it.op == BodyOp.ADD) || (it is RevolveFeature && it.op == BodyOp.ADD) }

    /** Name des aktuellen Modells und die Liste aller gespeicherten (für das Projektmenü). */
    var projectName by mutableStateOf(session.name)
        private set
    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
        private set
    var currentProjectId by mutableStateOf<String?>(null)
        private set
    /** Hinweis an den Nutzer (z. B. „beschädigt, Sicherung geöffnet“, „Speichern fehlgeschlagen“). */
    var notice by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var cameraJob: Job? = null
    private var saveJob: Job? = null

    /**
     * Stand der Bearbeitung: [editVersion] zählt jede Änderung, [savedVersion] ist der zuletzt gesicherte Stand. Beide werden nur unter
     * [saveLock] verglichen und gesichert, damit ein verspäteter Schreibvorgang im Hintergrund nie einen neueren Stand überschreibt.
     */
    @Volatile private var editVersion = 0L
    @Volatile private var savedVersion = 0L
    private val saveLock = Any()
    private val dirty: Boolean get() = editVersion != savedVersion

    /** Ein Thread für Neuaufbauten: Ein laufender nativer Aufbau lässt sich nicht abbrechen; neue warten statt parallel zu rechnen. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val buildDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Ein Thread für das Schreiben der Modelldatei (Autosave). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        val restored = session.restoreLast()
        adopt(restored?.document ?: doc)
        notice = restored?.notice
        syncProject()
    }

    private fun bindEditor() {
        handle.editor.onSketchChanged = { onSketchChanged() }
        handle.editor.faceCenter = if (activeSketch.onFace != null) ::faceCenterTarget else null
        applyDrawModes(handle.editor, prefs)
        updateRegions()
    }

    private fun onSketchChanged() {
        updateRegions()
        markDirty()
        if (hasExtrusions()) scheduleBuild()
    }

    /** Gibt es Features, die Körper erzeugen (dann lohnt ein Aufbau)? */
    private fun hasExtrusions() = doc.features.any { it is ExtrudeFeature || it is RevolveFeature }

    private fun updateRegions() {
        regionCount = handle.editor.sketch.regions().regions.size
    }

    // ---- Speichern / Projekte ---------------------------------------------------------------------

    private fun markDirty() {
        editVersion++
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(AUTOSAVE_DELAY_MS)
            // Das erste Speichern legt das Modell an (einmalig, ändert den Sitzungszustand) – das bleibt auf dem UI-Thread
            val id = session.currentId ?: return@launch flush()
            val version = editVersion
            val text = DocumentCodec.encode(doc) // auf dem UI-Thread: das Dokument ändert sich nur hier
            val result = withContext(saveDispatcher) {
                runCatching {
                    synchronized(saveLock) {
                        // inzwischen hat flush() einen neueren Stand gesichert: dieser hier ist veraltet
                        if (version > savedVersion) {
                            session.write(id, text)
                            savedVersion = version
                        }
                    }
                }
            }
            result.onFailure { notice = "Speichern fehlgeschlagen: ${it.message}" }
        }
    }

    /**
     * Sichert ausstehende Änderungen sofort und synchron (Aufruf aus `onStop`, bevor Android den Prozess beenden darf, und vor einem
     * Projektwechsel). Ein gerade laufendes Autosave wird abgewartet.
     */
    fun flush() {
        saveJob?.cancel()
        if (!dirty) return
        try {
            synchronized(saveLock) {
                val version = editVersion
                session.save(doc)
                savedVersion = version
            }
            syncProject()
        } catch (e: Exception) {
            notice = "Speichern fehlgeschlagen: ${e.message}"
        }
    }

    /** Verwirft ausstehende Änderungen als „gesichert“ (anderes Modell geöffnet, Modell gelöscht); ein laufendes Autosave entfällt. */
    private fun markClean() {
        saveJob?.cancel()
        synchronized(saveLock) { savedVersion = editVersion }
    }

    /** Name und ID des aktuellen Modells; mit [list] auch die Liste aller Modelle (liest jeden Projektordner – nicht bei jedem Speichern). */
    private fun syncProject(list: Boolean = true) {
        projectName = session.name
        currentProjectId = session.currentId
        if (list) projects = session.list()
    }

    /** Liest die Liste aller Modelle neu (z. B. beim Öffnen des Projektmenüs: das Autosave aktualisiert sie nicht). */
    fun refreshProjects() = syncProject()

    /** Übernimmt ein Dokument: das letzte Feature wird gewählt, der Körper wird neu gebaut. */
    private fun adopt(newDoc: Document) {
        doc = newDoc
        if (doc.features.none { it is SketchFeature }) doc.addSketch("Skizze 1")
        job?.cancel()
        output = null
        building = false
        select(doc.features.last().id)
        mode = if (isPristine()) WorkMode.SKETCH else WorkMode.MODEL
        cameraJob?.cancel()
        camera.snapTo(ViewCamera.ISO_YAW, ViewCamera.ISO_PITCH)
        if (hasExtrusions()) scheduleBuild(0)
    }

    // ---- Auswahl ----------------------------------------------------------------------------------

    private fun refresh() {
        features = doc.features
        val sel = selectedId?.let { id -> features.firstOrNull { it.id == id } }
        extrusion = sel as? ExtrudeFeature
        revolve = sel as? RevolveFeature
        shell = sel as? ShellFeature
        fillet = sel as? FilletFeature
        hole = sel as? HoleFeature
        pattern = sel as? PatternFeature
        datum = sel as? PlaneFeature
        mirrorBody = sel as? MirrorBodyFeature
        sketch = when (sel) {
            is SketchFeature -> sel
            is ExtrudeFeature -> doc.sketch(sel.sketch)
            is RevolveFeature -> doc.sketch(sel.sketch)
            is FilletFeature, is HoleFeature, is ShellFeature, is PatternFeature, is PlaneFeature, is MirrorBodyFeature, null -> activeSketch
        }
        refreshOverlay()
        refreshHighlight()
        refreshReference()
    }

    /** Ob die Einstellungskarte gebraucht wird: ein Feature ist gewählt oder ein Werkzeug läuft. */
    val panelVisible: Boolean
        get() = selectedId != null || edgeMode != null || holeMode != null || shellMode != null || planePick || faceSketchPick

    /** Schließt die Einstellungskarte: bricht ein laufendes Werkzeug ab, sonst hebt es die Auswahl auf. */
    fun closePanel() {
        when {
            edgeMode != null -> cancelEdgeMode()
            shellMode != null -> cancelShellMode()
            holeMode != null -> cancelHoleMode()
            planePick -> cancelPlanePick()
            faceSketchPick -> cancelFaceSketchPick()
            else -> { selectedId = null; refreshHighlight(); refresh() }
        }
    }

    fun select(id: FeatureId) {
        selectedId = id
        edgeMode = null
        holeMode = null
        shellMode = null
        faceSketchPick = false
        planePick = false
        refreshHighlight()
        val sel = doc.feature(id)
        val target = when (sel) {
            is ExtrudeFeature -> doc.sketch(sel.sketch)
            is RevolveFeature -> doc.sketch(sel.sketch)
            is SketchFeature -> sel
            // Verrundungen haben keine eigene Skizze: die bisher aktive behalten – sofern sie zum Dokument gehört (nach dem Laden
            // ist es noch der Platzhalter), sonst die letzte Skizze des Dokuments
            is FilletFeature, is HoleFeature, is ShellFeature, is PatternFeature, is PlaneFeature, is MirrorBodyFeature -> {
                val sketches = doc.features.filterIsInstance<SketchFeature>()
                sketches.firstOrNull { it.sketch === activeSketch.sketch } ?: sketches.last()
            }
        }
        if (target.sketch !== handle.editor.sketch) handle.replace(SketchEditor(target.sketch))
        activeSketch = target
        bindEditor()
        refresh()
    }

    /** Öffnet ein gespeichertes Modell (das aktuelle wird vorher gesichert). */
    fun openProject(id: String) {
        if (id == session.currentId) return
        flush()
        val r = session.open(id)
        r.document?.let { adopt(it) }
        notice = r.notice
        markClean()
        syncProject()
    }

    fun renameProject(name: String) {
        flush()
        session.rename(name)
        syncProject()
    }

    fun duplicateProject() {
        flush()
        val r = session.duplicate()
        r.document?.let { adopt(it) }
        notice = r.notice
        markClean()
        syncProject()
    }

    fun deleteProject() {
        markClean()
        session.deleteCurrent()
        adopt(Document())
        notice = null
        syncProject()
    }

    /** Löscht ein gespeichertes Modell, das gerade nicht geöffnet ist. */
    fun deleteProjectById(id: String) {
        if (id == session.currentId) { deleteProject(); return }
        session.delete(id)
        syncProject()
    }

    /** Neues, leeres Modell mit einer leeren Skizze auf der XY-Ebene (das aktuelle wird vorher gesichert). */
    fun newDocument() {
        flush()
        session.startNew()
        adopt(Document())
        notice = null
        syncProject()
    }

    /** Das aktuelle Modell als Datei-Inhalt (Projekt exportieren). */
    fun exportText(): String = DocumentCodec.encode(doc)

    /**
     * Öffnet eine exportierte Modelldatei als neues Modell namens [name] (das aktuelle wird vorher gesichert). Unlesbare Dateien und
     * Dateien aus einer neueren App-Version ändern nichts und werden gemeldet.
     */
    fun importModel(name: String, text: String) {
        val imported = try {
            DocumentCodec.decode(text)
        } catch (e: dev.androidcad.json.JsonException) {
            notice = "Import fehlgeschlagen: ${e.message}"
            return
        }
        flush()
        session.startNew()
        session.rename(if (session.list().any { it.name == name }) "$name (Import)" else name)
        adopt(imported)
        notice = null
        markDirty()
        syncProject()
    }

    /** Lädt das parametrische Beispiel (vollständig bemaßte Platte mit vier Bohrungen) als neues Modell. */
    fun loadPlateExample() {
        flush()
        session.startNew()
        val m = PlateModel()
        m.solveSketch()
        adopt(m.document)
        notice = null
        markDirty()
        syncProject()
    }

    // ---- Features bearbeiten ----------------------------------------------------------------------

    private fun nextName(prefix: String): String {
        var n = 1
        while (doc.features.any { it.name == "$prefix $n" }) n++
        return "$prefix $n"
    }

    /** Neue leere Skizze auf [plane]; sie wird aktiv. */
    fun addSketch(plane: PlaneSpec) {
        val f = doc.addSketch(nextName("Skizze"), plane.toWorkplane())
        select(f.id)
        markDirty()
        mode = WorkMode.SKETCH
    }

    /** Legt die aktive Skizze auf eine andere Ebene. */
    fun changePlane(plane: PlaneSpec) {
        doc.setPlane(activeSketch.id, plane.toWorkplane())
        activeSketch = doc.sketch(activeSketch.id)
        refresh()
        markDirty()
        if (hasExtrusions()) scheduleBuild(0)
    }

    /** Extrudiert alle geschlossenen Flächen der aktiven Skizze: hinzufügen oder (von vorhandenem Material) abtragen. */
    fun createExtrusion(op: BodyOp = BodyOp.ADD, distance: Double = 10.0) {
        if (regionCount == 0) return
        val name = nextName(if (op == BodyOp.ADD) "Extrusion" else "Abtrag")
        // Abtragen läuft standardmäßig gegen die Ebenennormale, also in das Material hinein, wenn die Skizze auf der Oberseite liegt
        val f = doc.addExtrude(name, activeSketch, if (op == BodyOp.CUT) -distance else distance, op)
        select(f.id)
        markDirty()
        scheduleBuild(0)
    }

    fun updateExtrusion(distance: Double, symmetric: Boolean, op: BodyOp? = null) {
        val f = extrusion ?: return
        doc.update(f.copy(distance = distance, symmetric = symmetric, op = op ?: f.op))
        refresh()
        markDirty()
        scheduleBuild()
    }

    /**
     * Dreht alle geschlossenen Flächen der aktiven Skizze voll um eine Achse: die erste Hilfslinie der Skizze, sonst die senkrechte bzw.
     * waagrechte Skizzenachse – die, die das Profil nicht schneidet. Achse und Winkel lassen sich danach einstellen.
     */
    fun createRevolve(op: BodyOp = BodyOp.ADD) {
        if (regionCount == 0) return
        val sk = activeSketch.sketch
        val regions = sk.regions().regions
        val candidates = revolveAxisOptions(activeSketch.id) + listOf(RevolveAxis.SketchY, RevolveAxis.SketchX)
        val axis = candidates.firstOrNull { a -> RevolveGeometry.resolve(sk, a)?.let { RevolveGeometry.problem(regions, it) } == null }
            ?: candidates.first()
        val f = doc.addRevolve(nextName(if (op == BodyOp.ADD) "Drehung" else "Drehabtrag"), activeSketch, axis, 360.0, op)
        select(f.id)
        markDirty()
        scheduleBuild(0)
    }

    /** Ändert die gewählte Drehung (Achse, Winkel, Operation …); unbrauchbare Winkel werden ignoriert. */
    fun updateRevolve(f: RevolveFeature) {
        if (revolve?.id != f.id || f.angle == 0.0 || !f.angle.isFinite() || kotlin.math.abs(f.angle) > 360.0) return
        doc.update(f)
        refresh()
        markDirty()
        scheduleBuild()
    }

    /** Hilfslinien der Skizze [sketch] – mögliche Drehachsen neben den beiden Skizzenachsen. */
    fun revolveAxisOptions(sketch: FeatureId): List<RevolveAxis> {
        val sk = doc.sketch(sketch).sketch
        return sk.lineIds.filter { sk.line(it).construction }.map { RevolveAxis.Line(it) }
    }

    // ---- Skizzen- und Modellmodus -----------------------------------------------------------------

    private fun isPristine() = doc.features.none { it is ExtrudeFeature || it is RevolveFeature || it is FilletFeature } &&
        doc.features.filterIsInstance<SketchFeature>().all { it.sketch.lineIds.isEmpty() && it.sketch.circleIds.isEmpty() && it.sketch.arcIds.isEmpty() }

    /** Öffnet die aktive Skizze zum Zeichnen (2D, ganzer Bildschirm). */
    fun editSketch() {
        if (mode == WorkMode.SKETCH) return
        edgeMode = null
        refreshHighlight()
        mode = WorkMode.SKETCH
    }

    /**
     * „Fertig“: zurück zum Körper. Die Kamera steht zunächst senkrecht auf der Skizzenebene (die Skizze sieht aus wie eben in 2D)
     * und schwenkt dann in die isometrische Ansicht; die Skizze bleibt als Linien auf ihrer Ebene sichtbar.
     */
    fun finishSketch() {
        if (mode != WorkMode.SKETCH) return
        handle.editor.cancelOperation()
        refreshOverlay()
        mode = WorkMode.MODEL
        val (yaw, pitch) = ViewCamera.planeView(activeSketch.plane)
        camera.snapTo(yaw, pitch)
        cameraJob?.cancel()
        cameraJob = scope.launch { camera.animateTo(ViewCamera.ISO_YAW, ViewCamera.ISO_PITCH) }
    }

    private fun refreshOverlay() {
        val extruded = doc.features.mapNotNull { (it as? ExtrudeFeature)?.sketch ?: (it as? RevolveFeature)?.sketch }.toSet()
        val parts = doc.features.filterIsInstance<SketchFeature>()
            .filter { it.id == selectedId || it.id !in extruded } // gewählte Skizze oder noch nicht extrudierte
            .map { it.worldSegments() }
        val planes = doc.features.filterIsInstance<PlaneFeature>().map { datumFrame(it.plane, it.id == selectedId) }
        // Spiegelebene der gewählten Körperspiegelung (bei einer Arbeitsebene ist deren Rahmen schon da)
        val mirror = mirrorBody?.takeIf { it.datum == null }?.let { listOf(datumFrame(mirrorPlane(it), true)) } ?: emptyList()
        val axis = revolve?.let { revolveAxisLine(it) }?.let(::listOf) ?: emptyList()
        val all = parts + planes + mirror + axis
        val total = all.sumOf { it.size }
        sketchOverlay = if (total == 0) null else FloatArray(total).also { out ->
            var offset = 0
            for (part in all) { part.copyInto(out, offset); offset += part.size }
        }
    }

    /** Drehachse der gewählten Drehung als Linie im Raum (über die Größe des Körpers hinaus verlängert); `null`, wenn sie fehlt. */
    private fun revolveAxisLine(f: RevolveFeature): FloatArray? {
        val sk = doc.features.firstOrNull { it.id == f.sketch } as? SketchFeature ?: return null
        val a = RevolveGeometry.resolve(sk.sketch, f.axis) ?: return null
        val plane = output?.sketchPlanes?.get(sk.id) ?: sk.plane
        // Fußpunkt der Körpermitte auf der Achse, von dort in beide Richtungen
        val c = bodyCenter()
        val p0 = plane.toWorld(a.point.x, a.point.y)
        val dir = plane.xDir * a.direction.x + plane.yDir * a.direction.y
        val t = Vec3(c.x - p0.x, c.y - p0.y, c.z - p0.z).dot(dir)
        val s = bodyExtent() * 0.8
        val from = p0 + dir * (t - s); val to = p0 + dir * (t + s)
        return floatArrayOf(from.x.toFloat(), from.y.toFloat(), from.z.toFloat(), to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
    }

    /**
     * Rahmen einer Arbeitsebene als Linienpaare: ein Rechteck in der Ebene um den Fußpunkt der Körpermitte (Größe nach dem Körper),
     * dazu ein kurzer Normalenpfeil; die gewählte Ebene mit Diagonalen, damit sie auffällt.
     */
    private fun datumFrame(p: Workplane, selected: Boolean): FloatArray {
        val c = bodyCenter()
        val toC = Vec3(c.x - p.origin.x, c.y - p.origin.y, c.z - p.origin.z)
        // Fußpunkt der Körpermitte auf der Ebene, in Ebenenkoordinaten
        val u = toC.dot(p.xDir); val v = toC.dot(p.yDir)
        val s = bodyExtent() * 0.6
        fun pt(a: Double, b: Double) = p.toWorld(u + a, v + b)
        val corners = listOf(pt(-s, -s), pt(s, -s), pt(s, s), pt(-s, s))
        val segs = ArrayList<Float>()
        fun line(a: Vec3, b: Vec3) { segs += a.x.toFloat(); segs += a.y.toFloat(); segs += a.z.toFloat(); segs += b.x.toFloat(); segs += b.y.toFloat(); segs += b.z.toFloat() }
        for (i in 0..3) line(corners[i], corners[(i + 1) % 4])
        val mid = pt(0.0, 0.0)
        line(mid, mid + p.normal * (s * 0.4))
        if (selected) { line(corners[0], corners[2]); line(corners[1], corners[3]) }
        return segs.toFloatArray()
    }

    /** Größte Ausdehnung des Körpers (Hüllbox); 40 mm ohne Körper. */
    private fun bodyExtent(): Double {
        val (lo, hi) = bodyBounds() ?: return 40.0
        return maxOf(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z).coerceAtLeast(10.0)
    }

    // ---- Verrunden / Anfasen ----------------------------------------------------------------------

    /** Schaltet den Kantenmodus ein: Kanten im 3D-Bild antippen, dann „Anwenden“. */
    fun startEdgeMode(kind: EdgeKind) {
        if (output?.mesh == null) return
        planePick = false
        holeMode = null
        faceSketchPick = false
        shellMode = null
        edgeMode = EdgeMode(kind, emptyList())
        refreshHighlight()
    }

    fun setEdgeKind(kind: EdgeKind) {
        edgeMode?.let { edgeMode = EdgeMode(kind, it.selected) }
    }

    fun cancelEdgeMode() {
        edgeMode = null
        refreshHighlight()
    }

    /** Verarbeitet einen Tipp im 3D-Bild: [index] = Index der getroffenen Kante in [ModelOutput.edgeRefs] oder −1. */
    fun pickEdge(index: Int) {
        val mode = edgeMode ?: return
        if (index < 0) return
        val ref = output?.edgeRefs?.getOrNull(index)
        if (ref == null) {
            notice = "Diese Kante lässt sich nicht wählen (Naht oder noch unbenannte Fläche)."
            return
        }
        edgeMode = EdgeMode(mode.kind, if (ref in mode.selected) mode.selected - ref else mode.selected + ref)
        refreshHighlight()
    }

    /** Legt die Verrundung/Fase mit den gewählten Kanten und [edgeSize] an und wählt sie. */
    fun applyEdgeMode() {
        val mode = edgeMode ?: return
        if (mode.selected.isEmpty()) return
        val f = doc.addFillet(nextName(if (mode.kind == EdgeKind.ROUND) "Verrundung" else "Fase"), mode.selected, edgeSize, mode.kind)
        select(f.id) // beendet den Kantenmodus
        markDirty()
        scheduleBuild(0)
    }

    fun updateFillet(size: Double, kind: EdgeKind) {
        val f = fillet ?: return
        doc.update(f.copy(size = size, kind = kind))
        refresh()
        markDirty()
        scheduleBuild()
    }

    // ---- Muster und Spiegelung --------------------------------------------------------------------

    /** Kann das gewählte Feature vervielfältigt werden (Extrusion oder Bohrung)? */
    val canPattern: Boolean
        get() = extrusion != null || revolve != null || hole != null ||
            // Muster von Mustern: nur ein einfaches Muster (dessen Quelle selbst kein Muster ist)
            pattern?.let { p -> doc.features.firstOrNull { it.id == p.source } !is PatternFeature && !PatternGeometry.isTwoWay(p.kind) } == true ||
            // Verrundung/Fase gewählt: Muster ihres Features samt Rundung
            fillet?.let(::filletOwner) != null

    /**
     * Das Feature (Extrusion oder Bohrung), an dessen Flächen alle Kanten einer Verrundung hängen – die Quelle für ein Muster mit
     * Rundung. Hängen die Kanten an mehreren (Senkung: Platte und Bohrung), ist es das jüngste – das Detail, nicht der Grundkörper.
     */
    private fun filletOwner(fl: FilletFeature): FeatureId? {
        val touched = fl.edges.flatMap { listOf(it.a.feature, it.b.feature) }.toSet()
        return doc.features.takeWhile { it.id != fl.id }
            .lastOrNull { f -> (f is ExtrudeFeature || f is RevolveFeature || f is HoleFeature) && f.id in touched && fl.edges.all { it.a.feature == f.id || it.b.feature == f.id } }?.id
    }

    /** Wie viele Verrundungen/Fasen das gewählte Muster von seiner Quelle mitnehmen kann. */
    fun patternFilletCount(p: PatternFeature): Int = doc.patternFillets(p).size

    /** Schaltet „Verrundungen der Quelle mitnehmen“ des gewählten Musters um. */
    fun setPatternWithFillets(on: Boolean) {
        val f = pattern ?: return
        doc.update(f.copy(withFillets = on))
        refresh()
        markDirty()
        scheduleBuild()
    }

    /** Mitte des Körpers (Mitte der Hüllbox des Netzes) – sinnvolle Vorgabe für Drehachse und Spiegelebene. */
    private fun bodyCenter(): Vec3 {
        val (lo, hi) = bodyBounds() ?: return Vec3(0.0, 0.0, 0.0)
        return Vec3((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2)
    }

    // ---- Körper spiegeln ---------------------------------------------------------------------------

    /** Hüllbox des Körpers (min, max); `null` ohne Körper. */
    private fun bodyBounds(): Pair<Vec3, Vec3>? = output?.bounds

    /** Ebene einer Körperspiegelung als [Workplane] (für die Anzeige). */
    private fun mirrorPlane(f: MirrorBodyFeature): Workplane {
        f.datum?.let { id -> (output?.datumPlanes?.get(id) ?: (doc.features.firstOrNull { it.id == id } as? PlaneFeature)?.plane)?.let { return it } }
        val n = f.normal.normalized()
        val x = if (kotlin.math.abs(n.x) > 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val d = x.dot(n)
        val xDir = Vec3(x.x - n.x * d, x.y - n.y * d, x.z - n.z * d).normalized()
        return Workplane(f.origin, xDir, n)
    }

    /** Arbeitsebenen, an denen die Spiegelung [f] (bzw. eine neue am Ende) spiegeln kann – sie müssen davor liegen. */
    fun mirrorDatumOptions(f: MirrorBodyFeature?): List<PlaneFeature> =
        (if (f == null) doc.features else doc.features.takeWhile { it.id != f.id }).filterIsInstance<PlaneFeature>()

    /**
     * Spiegelt den ganzen Körper: an der gewählten Arbeitsebene, sonst an der YZ-Ebene durch die linke Körperseite (das Spiegelbild
     * schließt dann direkt an). Ebene und Lage lassen sich danach einstellen.
     */
    fun createMirrorBody() {
        val (lo, hi) = bodyBounds() ?: return
        val onDatum = datum
        val origin = Vec3(lo.x, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2)
        val f = doc.addMirrorBody(nextName("Körperspiegelung"), origin, Vec3(1.0, 0.0, 0.0), onDatum?.id)
        select(f.id)
        markDirty()
        scheduleBuild(0)
    }

    /** Wandelt die gewählte Spiegelung eines Features in eine Spiegelung des ganzen Körpers an derselben Ebene um. */
    fun mirrorWholeBodyInstead() {
        val p = pattern ?: return
        val k = p.kind as? PatternKind.Mirror ?: return
        val f = MirrorBodyFeature(p.id, p.name, k.origin, k.normal)
        doc.update(f)
        select(f.id)
        markDirty()
        scheduleBuild(0)
    }

    /** Ändert die Ebene der gewählten Körperspiegelung; unbrauchbare Werte werden ignoriert. */
    fun updateMirrorBody(f: MirrorBodyFeature) {
        if (mirrorBody?.id != f.id || MirrorNaming.validate(f) != null) return
        doc.update(f)
        refresh()
        markDirty()
        scheduleBuild()
    }

    /** Vervielfältigt die gewählte Extrusion/Bohrung mit sinnvollen Vorgaben; die Maße lassen sich danach einstellen. */
    fun createPattern(type: PatternType) {
        val selected = selectedId?.let { id -> doc.features.firstOrNull { it.id == id } } ?: return
        // Verrundung gewählt: vervielfältigt wird ihr Feature, die Rundung kommt über „mitnehmen“ dazu
        val src = if (selected is FilletFeature) filletOwner(selected)?.let(doc::feature) ?: return else selected
        if (src !is ExtrudeFeature && src !is RevolveFeature && src !is HoleFeature && src !is PatternFeature) return
        val c = bodyCenter()
        val kind = when (type) {
            PatternType.LINEAR -> PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 20.0)
            PatternType.CIRCULAR -> PatternKind.Circular(c, Vec3(0.0, 0.0, 1.0), 4)
            PatternType.MIRROR -> PatternKind.Mirror(c, Vec3(1.0, 0.0, 0.0))
        }
        var f = doc.addPattern(nextName(if (type == PatternType.MIRROR) "Spiegelung" else "Muster"), src.id, kind)
        // Hat die Quelle Verrundungen/Fasen, nehmen die Kopien sie standardmäßig mit
        if (doc.patternFillets(f).isNotEmpty()) { f = f.copy(withFillets = true); doc.update(f) }
        select(f.id)
        markDirty()
        scheduleBuild(0)
    }

    /** Ändert die Parameter des gewählten Musters; unbrauchbare Werte (z. B. Anzahl 1) werden ignoriert. */
    fun updatePattern(kind: PatternKind) {
        val f = pattern ?: return
        if (PatternGeometry.validate(kind) != null) return
        doc.update(f.copy(kind = kind))
        refresh()
        markDirty()
        scheduleBuild()
    }

    /** Ob ein Linearmuster eine zweite Richtung bekommen darf (nicht, wenn es selbst ein Muster vervielfältigt). */
    fun canTwoWay(p: PatternFeature): Boolean = p.kind is PatternKind.Linear && doc.features.firstOrNull { it.id == p.source } !is PatternFeature

    /** Name der Quelle des gewählten Musters (für die Anzeige). */
    fun patternSourceName(p: PatternFeature): String = doc.features.firstOrNull { it.id == p.source }?.name ?: "?"

    // ---- Arbeitsebenen ----------------------------------------------------------------------------

    /** Schaltet den Auswahlmodus ein: eine ebene Fläche antippen, daraus entsteht eine Arbeitsebene (zunächst 10 mm Abstand). */
    fun startPlanePick() {
        if (output?.mesh == null) return
        edgeMode = null
        holeMode = null
        faceSketchPick = false
        shellMode = null
        planePick = true
        refreshHighlight()
    }

    fun cancelPlanePick() {
        planePick = false
    }

    private fun createPlane(face: FaceRef, planar: PlanarFace) {
        planePick = false
        val def = PlaneDef.Offset(face, 10.0)
        val f = doc.addPlane(nextName("Ebene"), def, DatumPlane.offset(planar, 10.0))
        select(f.id)
        markDirty()
        scheduleBuild(0)
    }

    /** Ändert die gewählte Arbeitsebene; die neue Lage kommt mit dem nächsten Aufbau. Ungültige Werte werden ignoriert. */
    fun updateDatum(def: PlaneDef) {
        val f = datum ?: return
        if (DatumPlane.validate(def) != null) return
        doc.updatePlane(f.id, def)
        refresh()
        markDirty()
        scheduleBuild()
    }

    /** Gerade Kanten der Fläche [face] im aktuellen Körper (Drehachsen für eine gedrehte Arbeitsebene), ohne Doppelte. */
    fun planeEdgeOptions(face: FaceRef): List<dev.androidcad.model.EdgeRef> {
        val out = output ?: return emptyList()
        val planar = out.faceRefs.indexOfFirst { it == face }.takeIf { it >= 0 }?.let { out.facePlanes.getOrNull(it) } ?: return emptyList()
        return out.edgeRefs.indices.mapNotNull { i ->
            val r = out.edgeRefs[i]; val l = out.edgeLines.getOrNull(i)
            if (r == null || l == null || (r.a != face && r.b != face)) return@mapNotNull null
            val inPlane = kotlin.math.abs(planar.distanceTo(l.start.x, l.start.y, l.start.z)) < 1e-3 && kotlin.math.abs(planar.distanceTo(l.end.x, l.end.y, l.end.z)) < 1e-3
            if (inPlane) r else null
        }.distinct()
    }

    /** Neue Skizze auf der gewählten Arbeitsebene (öffnet den Skizzenmodus). */
    fun createSketchOnDatum() {
        val f = datum ?: return
        val sk = doc.addSketchOnPlane(nextName("Skizze"), f.id)
        select(sk.id)
        markDirty()
        mode = WorkMode.SKETCH
        scheduleBuild(0)
    }

    // ---- Skizze auf einer Fläche ------------------------------------------------------------------

    /** Schaltet den Auswahlmodus ein: eine ebene Fläche im 3D-Bild antippen, auf die die neue Skizze gelegt wird. */
    fun startFaceSketchPick() {
        if (output?.mesh == null) return
        planePick = false
        edgeMode = null
        holeMode = null
        shellMode = null
        faceSketchPick = true
        refreshHighlight()
    }

    fun cancelFaceSketchPick() {
        faceSketchPick = false
    }

    /** Legt eine neue Skizze auf die ebene Fläche [face] und öffnet sie zum Zeichnen. */
    private fun createFaceSketch(face: FaceRef, plane: PlanarFace) {
        val f = doc.addSketchOnFace(nextName("Skizze"), face, FacePlane.workplane(plane))
        select(f.id)
        markDirty()
        mode = WorkMode.SKETCH
        scheduleBuild(0) // damit der Aufbau die neue Skizze kennt (projizierbare Kanten)
    }

    /** Übernimmt die beim Aufbau berechneten Ebenen der Flächen-Skizzen ins Dokument (ohne neu zu bauen). */
    private fun syncSketchPlanes() {
        val planes = output?.sketchPlanes ?: return
        var changed = false
        for ((id, plane) in planes) {
            val sk = doc.features.firstOrNull { it.id == id } as? SketchFeature ?: continue
            if (!sk.planeIsDerived) continue
            val a = sk.plane.matrix3x4(); val b = plane.matrix3x4()
            if (a.indices.any { kotlin.math.abs(a[it] - b[it]) > 1e-9 }) {
                doc.setPlane(id, plane)
                changed = true
            }
        }
        for ((id, plane) in output?.datumPlanes ?: emptyMap()) {
            val p = doc.features.firstOrNull { it.id == id } as? PlaneFeature ?: continue
            val a = p.plane.matrix3x4(); val b = plane.matrix3x4()
            if (a.indices.any { kotlin.math.abs(a[it] - b[it]) > 1e-9 }) {
                doc.updatePlane(id, p.def, plane)
                changed = true
            }
        }
        if (changed) {
            activeSketch = doc.sketch(activeSketch.id)
            refresh()
            markDirty()
        }
    }

    /**
     * Führt die projizierten Kanten der Skizzen nach, nachdem sich der Körper geändert hat (der Aufbau lief auf einer Kopie),
     * und löst die Skizze im Editor neu, damit daran gebundene Geometrie mitwandert.
     */
    private fun syncProjections() {
        val out = output ?: return
        if (!doc.applyProjections(out.projected)) return
        activeSketch = doc.sketch(activeSketch.id)
        handle.editor.refreshExternal()
        refresh()
        markDirty()
    }

    /** Projiziert alle Kanten der Fläche, auf der die aktive Skizze liegt (Umriss und Löcher), als feste Referenz in die Skizze. */
    fun projectFaceEdges() {
        val f = activeSketch
        if (f.onFace == null) return
        val edges = output?.projectable?.get(f.id)
        if (edges == null) {
            notice = if (building) "Der Körper wird noch berechnet." else "Von dieser Fläche lassen sich keine Kanten projizieren."
            return
        }
        if (doc.addProjections(f.id, edges) == 0) {
            notice = "Alle Kanten dieser Fläche sind schon projiziert."
            return
        }
        activeSketch = doc.sketch(f.id)
        handle.editor.recordExternalChange("Kanten projizieren")
        refresh()
    }

    /** Umriss des Körpers in der Ebene der aktiven Skizze (Kanten, die ganz in der Ebene liegen), in Skizzenkoordinaten. */
    private fun refreshReference() {
        val out = output
        if (out == null || out.edgeCounts.isEmpty()) { referenceLines = null; return }
        val plane = activeSketch.plane
        val o = plane.origin; val x = plane.xDir; val y = plane.yDir; val n = plane.normal
        fun dist(i: Int) = (out.polylines[i] - o.x) * n.x + (out.polylines[i + 1] - o.y) * n.y + (out.polylines[i + 2] - o.z) * n.z
        val segs = ArrayList<Float>()
        var offset = 0
        for (count in out.edgeCounts) {
            val inPlane = (0 until count).all { kotlin.math.abs(dist(3 * (offset + it))) < 1e-3 }
            if (inPlane) for (k in 0 until count - 1) {
                for (j in intArrayOf(offset + k, offset + k + 1)) {
                    val px = out.polylines[3 * j] - o.x; val py = out.polylines[3 * j + 1] - o.y; val pz = out.polylines[3 * j + 2] - o.z
                    segs += (px * x.x + py * x.y + pz * x.z).toFloat()
                    segs += (px * y.x + py * y.y + pz * y.z).toFloat()
                }
            }
            offset += count
        }
        referenceLines = if (segs.isEmpty()) null else segs.toFloatArray()
    }

    /** Schaltet den Bohrungsmodus ein: eine ebene Fläche im 3D-Bild antippen, dann „Anwenden“. */
    fun startHoleMode() {
        if (output?.mesh == null) return
        planePick = false
        edgeMode = null
        faceSketchPick = false
        shellMode = null
        holeMode = HoleMode(null, null, null)
        refreshHighlight()
    }

    fun cancelHoleMode() {
        holeMode = null
        refreshHighlight()
    }

    // ---- Schale --------------------------------------------------------------------------------------

    /** Schaltet den Schalenmodus ein: die Flächen antippen, die offen bleiben sollen, dann „Anwenden“. */
    fun startShellMode() {
        if (output?.mesh == null) return
        planePick = false
        edgeMode = null
        holeMode = null
        faceSketchPick = false
        selectedId = null // die Schale wirkt auf den ganzen Körper; sonst zeigte die Karte das gewählte Feature
        shellMode = ShellMode(emptyList())
        refresh()
    }

    fun cancelShellMode() {
        shellMode = null
        refreshHighlight()
    }

    /** Legt die Schale mit den gewählten Öffnungen und [shellThickness] an und wählt sie. */
    fun applyShellMode() {
        val mode = shellMode ?: return
        if (mode.selected.isEmpty()) return
        val f = doc.addShell(nextName("Schale"), mode.selected, shellThickness)
        select(f.id) // beendet den Schalenmodus
        markDirty()
        scheduleBuild(0)
    }

    fun updateShell(thickness: Double) {
        val f = shell ?: return
        if (!(thickness > 0) || !thickness.isFinite()) return
        doc.update(f.copy(thickness = thickness))
        refresh()
        markDirty()
        scheduleBuild()
    }

    /** Verarbeitet einen Tipp im 3D-Bild in den Flächenmodi (Bohrung, Skizze/Ebene auf Fläche, Schale). */
    fun pickFace(hit: FaceHit?) {
        if ((holeMode == null && !faceSketchPick && !planePick && shellMode == null) || hit == null) return
        val out = output ?: return
        val ref = out.faceRefs.getOrNull(hit.faceId - 1)
        shellMode?.let { mode ->
            // Öffnungen dürfen auch gekrümmt sein; erneut tippen hebt die Wahl auf
            if (ref == null) { notice = "Diese Fläche hat noch keinen Namen und lässt sich nicht referenzieren."; return }
            shellMode = ShellMode(if (ref in mode.selected) mode.selected - ref else mode.selected + ref)
            refreshHighlight()
            return
        }
        val plane = out.facePlanes.getOrNull(hit.faceId - 1)
        if (plane == null) {
            notice = "Das geht nur mit ebenen Flächen."
            return
        }
        if (ref == null) {
            notice = "Diese Fläche hat noch keinen Namen und lässt sich nicht referenzieren."
            return
        }
        if (faceSketchPick) {
            createFaceSketch(ref, plane)
            return
        }
        if (planePick) {
            createPlane(ref, plane)
            return
        }
        val n = Vec3(plane.normal.first, plane.normal.second, plane.normal.third)
        val p = Vec3(hit.point[0].toDouble(), hit.point[1].toDouble(), hit.point[2].toDouble())
        // Position als Abstände zu den zwei nächsten, nicht parallelen geraden Randkanten dieser Fläche (liegen in ihrer Ebene)
        class Cand(val ref: dev.androidcad.model.EdgeRef, val line: HolePlacement.Line, val dist: Double, val dir: Vec3)
        val cands = out.edgeRefs.indices.mapNotNull { i ->
            val r = out.edgeRefs[i]; val l = out.edgeLines.getOrNull(i)
            if (r == null || l == null || (r.a != ref && r.b != ref)) return@mapNotNull null
            val onPlane = kotlin.math.abs(plane.distanceTo(l.start.x, l.start.y, l.start.z)) < 1e-3 && kotlin.math.abs(plane.distanceTo(l.end.x, l.end.y, l.end.z)) < 1e-3
            if (!onPlane) null else Cand(r, l, HolePlacement.signedDistance(n, p, l.start, l.end), HolePlacement.canonical(l.start, l.end).second)
        }
        val first = cands.minByOrNull { kotlin.math.abs(it.dist) }
        val second = first?.let { f -> cands.filter { it !== f && (f.dir cross it.dir).length > 0.3 }.minByOrNull { kotlin.math.abs(it.dist) } }
        val anchors = if (first != null && second != null) EdgeDistance(first.ref, first.dist) to EdgeDistance(second.ref, second.dist) else null
        if (anchors == null) notice = "Keine zwei geraden Randkanten gefunden – die Position der Bohrung bleibt ein fester Punkt."
        holeMode = HoleMode(
            ref, doubleArrayOf(p.x, p.y, p.z), doubleArrayOf(n.x, n.y, n.z), plane, anchors,
            if (first != null && second != null) first.line to second.line else null,
        )
        refreshHighlight()
    }

    /** Ändert Durchmesser der Bohrung (Markierung im Bild folgt). */
    fun changeHoleDiameter(d: Double) {
        holeDiameter = d
        refreshHighlight()
    }

    /** Legt die Bohrung mit den eingestellten Maßen an der getippten Stelle an und wählt sie. */
    fun applyHoleMode() {
        val mode = holeMode ?: return
        val face = mode.face ?: return
        val p = mode.point ?: return
        val f = doc.addHole(nextName("Bohrung"), face, p[0], p[1], p[2], holeDiameter, if (holeThrough) null else holeDepth, mode.anchors)
        select(f.id) // beendet den Bohrungsmodus
        markDirty()
        scheduleBuild(0)
    }

    fun updateHole(diameter: Double, depth: Double?) {
        val f = hole ?: return
        doc.update(f.copy(diameter = diameter, depth = depth))
        refresh()
        markDirty()
        scheduleBuild()
    }

    /** Setzt den Abstand zur Referenzkante [index] (0/1) im Bohrungsmodus; die Bohrung wandert, das Vorzeichen (Seite) bleibt. */
    fun setAnchorDistance(index: Int, value: Double) {
        val mode = holeMode ?: return
        val anchors = mode.anchors ?: return
        val lines = mode.lines ?: return
        val plane = mode.plane ?: return
        val old = if (index == 0) anchors.first else anchors.second
        val signed = (if (old.distance < 0) -1.0 else 1.0) * kotlin.math.abs(value)
        val updated = if (index == 0) anchors.first.copy(distance = signed) to anchors.second else anchors.first to anchors.second.copy(distance = signed)
        val n = Vec3(plane.normal.first, plane.normal.second, plane.normal.third)
        val point = HolePlacement.solve(Vec3(plane.origin.first, plane.origin.second, plane.origin.third), n, lines.first, updated.first.distance, lines.second, updated.second.distance) ?: return
        holeMode = HoleMode(mode.face, doubleArrayOf(point.x, point.y, point.z), mode.normal, plane, updated, lines)
        refreshHighlight()
    }

    /** Ändert den Abstand [index] (0/1) der gewählten Bohrung zu ihrer Referenzkante (Vorzeichen bleibt). */
    fun updateHoleAnchor(index: Int, value: Double) {
        val f = hole ?: return
        val anchors = f.anchors ?: return
        val old = if (index == 0) anchors.first else anchors.second
        val signed = (if (old.distance < 0) -1.0 else 1.0) * kotlin.math.abs(value)
        val updated = if (index == 0) anchors.first.copy(distance = signed) to anchors.second else anchors.first to anchors.second.copy(distance = signed)
        doc.update(f.copy(anchors = updated))
        refresh()
        markDirty()
        scheduleBuild()
    }

    private fun refreshHighlight() {
        val out = output
        val segs = ArrayList<Float>()
        // gewählte Kanten (Kantenmodus) und die Referenzkanten der Bohrung (Bohrungsmodus bzw. gewählte Bohrung)
        val selected = edgeMode?.selected.orEmpty() + (holeMode?.anchors ?: hole?.anchors)?.toList().orEmpty().map { it.edge } +
            listOfNotNull((datum?.def as? PlaneDef.AtEdge)?.edge)
        // Öffnungen der Schale (Modus bzw. gewählte Schale): ihre Randkanten
        val openFaces = (shellMode?.selected ?: shell?.openFaces).orEmpty()
        if (out != null && (selected.isNotEmpty() || openFaces.isNotEmpty())) {
            var offset = 0
            for ((i, n) in out.edgeCounts.withIndex()) {
                val r = out.edgeRefs.getOrNull(i)
                if (r in selected || (r != null && (r.a in openFaces || r.b in openFaces))) {
                    for (k in 0 until n - 1) {
                        val a = 3 * (offset + k); val b = 3 * (offset + k + 1)
                        for (d in 0..2) segs += out.polylines[a + d]
                        for (d in 0..2) segs += out.polylines[b + d]
                    }
                }
                offset += n
            }
        }
        // Markierung der geplanten Bohrung: Kreis mit Fadenkreuz auf der Fläche (knapp über der Fläche, damit nichts flackert)
        val mode = holeMode
        val p = mode?.point
        val n = mode?.normal
        if (p != null && n != null) {
            val helper = if (kotlin.math.abs(n[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
            var ux = n[1] * helper[2] - n[2] * helper[1]; var uy = n[2] * helper[0] - n[0] * helper[2]; var uz = n[0] * helper[1] - n[1] * helper[0]
            val ul = kotlin.math.sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul
            val vx = n[1] * uz - n[2] * uy; val vy = n[2] * ux - n[0] * uz; val vz = n[0] * uy - n[1] * ux
            val r = holeDiameter / 2
            val cx = p[0] + n[0] * 0.05; val cy = p[1] + n[1] * 0.05; val cz = p[2] + n[2] * 0.05
            fun pt(a: Double, rr: Double) = doubleArrayOf(
                cx + rr * (kotlin.math.cos(a) * ux + kotlin.math.sin(a) * vx),
                cy + rr * (kotlin.math.cos(a) * uy + kotlin.math.sin(a) * vy),
                cz + rr * (kotlin.math.cos(a) * uz + kotlin.math.sin(a) * vz),
            )
            fun line(a: DoubleArray, b: DoubleArray) { for (v in a) segs += v.toFloat(); for (v in b) segs += v.toFloat() }
            val steps = 48
            for (i in 0 until steps) line(pt(2 * Math.PI * i / steps, r), pt(2 * Math.PI * (i + 1) / steps, r))
            for (k in 0..1) { val a = k * Math.PI / 2; line(pt(a, r * 0.6), pt(a + Math.PI, r * 0.6)) } // Fadenkreuz
        }
        highlight = if (segs.isEmpty()) null else segs.toFloatArray()
    }

    fun renameSelected(name: String) {
        val id = selectedId ?: return
        if (name.isBlank()) return
        doc.rename(id, name.trim())
        activeSketch = doc.sketch(activeSketch.id)
        refresh()
        markDirty()
    }

    /** Löscht das gewählte Feature (bei einer Skizze samt ihrer Extrusionen). Mindestens eine Skizze bleibt immer erhalten. */
    fun deleteSelected() {
        val id = selectedId ?: return
        val f = doc.feature(id)
        doc.remove(id)
        if (doc.features.none { it is SketchFeature }) doc.addSketch("Skizze 1")
        val sketchOf = (f as? ExtrudeFeature)?.sketch ?: (f as? RevolveFeature)?.sketch
        val next = sketchOf?.takeIf { s -> doc.features.any { it.id == s } }
        select(next ?: doc.features.last().id)
        markDirty()
        if (hasExtrusions()) scheduleBuild(0) else { job?.cancel(); output = null; building = false }
    }

    private fun scheduleBuild(delayMs: Long = 30) {
        job?.cancel()
        val snapshot = doc.snapshot() // auf dem UI-Thread: danach arbeitet der Hintergrund-Thread nur mit der Kopie
        job = scope.launch {
            delay(delayMs)
            building = true
            output = withContext(buildDispatcher) {
                // Fehler einzelner Features sammelt build(); was darüber hinaus scheitert (Tessellierung, Stacküberlauf), darf die App
                // nicht beenden, sondern wird als Meldung gezeigt
                try {
                    compute(snapshot)
                } catch (e: dev.androidcad.brep.BrepException) {
                    ModelOutput(null, null, 0.0, 0, listOf("Körper konnte nicht dargestellt werden: ${e.message}"), 0)
                } catch (_: StackOverflowError) {
                    ModelOutput(null, null, 0.0, 0, listOf("Das Modell ist zu tief verschachtelt, um es aufzubauen"), 0)
                }
            }
            syncSketchPlanes()
            syncProjections()
            refreshOverlay() // Rahmen der Arbeitsebenen richten sich nach dem Körper
            refreshHighlight()
            refreshReference()
            building = false
        }
    }

    private fun compute(d: Document): ModelOutput {
        val t0 = System.nanoTime()
        d.build().use { r ->
            val errors = r.errors.values.toList()
            val body = r.body ?: return ModelOutput(
                null, null, 0.0, 0, errors, (System.nanoTime() - t0) / 1_000_000, sketchPlanes = r.sketchPlanes, errorIds = r.errors.keys,
            )
            val bm = body.tessellate(linearDeflection = 0.05, angularDeflection = 0.3)
            val pairs = body.edgeFaceTags()
            val refs = bm.edgeIds.map { id ->
                pairs.getOrNull(id - 1)?.let { (x, y) ->
                    val a = FaceRef.fromTag(x); val b = FaceRef.fromTag(y)
                    if (a != null && b != null && x != y) EdgeRef.of(a, b) else null
                }
            }
            val faceRefs = body.faceTags().map { FaceRef.fromTag(it) }
            val edgeInfos = body.edges()
            val edgeLines = bm.edgeIds.map { id ->
                // edges() liefert die Kanten in ID-Reihenfolge (ID = Index + 1)
                edgeInfos.getOrNull(id - 1)?.takeIf { it.isLine }?.let {
                    HolePlacement.Line(Vec3(it.start.first, it.start.second, it.start.third), Vec3(it.end.first, it.end.second, it.end.third))
                }
            }
            return ModelOutput(
                mesh = Mesh(bm.positions, bm.triangles),
                edges = bm.edgeSegments(),
                volumeMm3 = body.volume(),
                faceCount = body.faceCount,
                errors = errors,
                millis = (System.nanoTime() - t0) / 1_000_000,
                polylines = bm.edgePositions,
                edgeCounts = bm.edgePointCounts,
                edgeRefs = refs,
                triangleFace = bm.triangleFace,
                faceRefs = faceRefs,
                facePlanes = body.planarFaces(),
                edgeLines = edgeLines,
                sketchPlanes = r.sketchPlanes,
                projectable = r.projectable,
                projected = r.projected,
                errorIds = r.errors.keys,
                datumPlanes = r.datumPlanes,
                bounds = bounds(bm.positions),
            )
        }
    }

    /** Hüllbox der Punkte (x y z hintereinander); `null` ohne Punkte. */
    private fun bounds(p: FloatArray): Pair<Vec3, Vec3>? {
        if (p.isEmpty()) return null
        val lo = DoubleArray(3) { Double.MAX_VALUE }; val hi = DoubleArray(3) { -Double.MAX_VALUE }
        for (i in p.indices) { val d = i % 3; lo[d] = minOf(lo[d], p[i].toDouble()); hi[d] = maxOf(hi[d], p[i].toDouble()) }
        return Vec3(lo[0], lo[1], lo[2]) to Vec3(hi[0], hi[1], hi[2])
    }
}

private const val AUTOSAVE_DELAY_MS = 700L

/** Polylinien der Kanten → einzelne Liniensegmente für `GL_LINES`. */
internal fun BrepMesh.edgeSegments(): FloatArray {
    val out = ArrayList<Float>()
    var offset = 0
    for (count in edgePointCounts) {
        for (i in 0 until count - 1) {
            val a = 3 * (offset + i); val b = 3 * (offset + i + 1)
            out += edgePositions[a]; out += edgePositions[a + 1]; out += edgePositions[a + 2]
            out += edgePositions[b]; out += edgePositions[b + 1]; out += edgePositions[b + 2]
        }
        offset += count
    }
    return out.toFloatArray()
}
