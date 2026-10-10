package dev.androidcad.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.androidcad.model.BasePlane
import dev.androidcad.model.BodyOp
import dev.androidcad.model.EdgeKind
import dev.androidcad.model.PlaneSpec
import dev.androidcad.viewport.OrthoCamera
import dev.androidcad.viewport.SceneFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Unter dieser Breite gilt das Gerät als schmal (Smartphone): Werkzeuge unten, Einstellungen als Blatt am unteren Rand. */
private val COMPACT_WIDTH = 600.dp

/** Breite der schwebenden Einstellungskarte auf breiten Bildschirmen. */
private val CARD_WIDTH = 330.dp

/**
 * Modellmodus: Das 3D-Bild füllt die ganze Fläche; alles andere erscheint nur, wenn es gebraucht wird.
 *
 * - **Werkzeuge** in vier Gruppen (Skizze, Körper, Kanten, Muster); ein Tipp auf eine Gruppe klappt ihre Werkzeuge daneben auf.
 *   Breit: schmale Leiste links; schmal: Leiste unten (mit dem Daumen erreichbar).
 * - **Einstellungen** des gewählten Features bzw. des laufenden Werkzeugs als schwebende Karte (breit: oben rechts; schmal: Blatt
 *   unten), einklappbar und mit ✕ zu schließen; ohne Auswahl und ohne Werkzeug ist sie weg.
 * - **Verlauf**: im Bild nur ein Chip mit dem aktuellen Schritt (‹ › blättert); der ganze Modellaufbau als Karte auf Wunsch.
 * - **Ansicht**: ein Knopf mit den Standardansichten.
 */
@Composable
internal fun ModelScreen(hub: ProjectHub) {
    val state = hub.workspace
    var newSketch by remember { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Kopfzeile kompakt nur auf Smartphones; Werkzeuge unten und Einstellungen als Blatt auch auf Tablets im Hochformat
        val narrow = maxWidth < COMPACT_WIDTH
        val compact = maxWidth < BOTTOM_TOOLS_WIDTH || maxWidth < maxHeight
        Column(Modifier.fillMaxSize()) {
            ModelTopBar(hub, narrow)
            HorizontalDivider()
            NoticeRow(state)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val areaHeight = maxHeight
                if (compact) {
                    // schmal: Verlauf bzw. Einstellungen als Blatt unter dem Bild – das Bild wird kleiner statt überdeckt
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            ModelViewport(state, Modifier.fillMaxSize())
                            Column(Modifier.fillMaxSize()) {
                                ErrorBanner(state)
                                Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                                    StepChip(state, Modifier.align(Alignment.TopStart))
                                    ViewMenu(state, Modifier.align(Alignment.TopEnd))
                                }
                            }
                        }
                        val sheet = Modifier.fillMaxWidth().heightIn(max = areaHeight * 0.5f).padding(horizontal = 6.dp, vertical = 4.dp)
                        if (state.parametersOpen) ParameterCard(state, sheet)
                        else if (state.prefs.historyOpen) HistoryCard(state, sheet) else if (state.panelVisible) SettingsCard(state, sheet)
                    }
                } else {
                    // das Modell zentriert sich im freien Bereich neben der Werkzeugleiste und den offenen Karten
                    val cad = CadTheme.colors
                    val left = if (state.prefs.historyOpen || state.parametersOpen) 372.dp else 76.dp
                    val right = if (state.panelVisible) CARD_WIDTH + 16.dp else 0.dp
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cad.viewportTop, cad.viewportBottom)))) {
                        ModelViewport(state, Modifier.fillMaxSize().padding(start = left, end = right))
                    }
                    Column(Modifier.fillMaxSize()) {
                        ErrorBanner(state)
                        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                            StepChip(state, Modifier.align(Alignment.TopStart).padding(start = 76.dp))
                            ViewMenu(state, Modifier.align(Alignment.BottomEnd))
                            // links: Parameter-Tabelle oder Modellaufbau (die Tabelle hat Vorrang)
                            val leftCard = Modifier.align(Alignment.TopStart).padding(start = 76.dp, top = 48.dp).width(280.dp).heightIn(max = areaHeight - 72.dp)
                            if (state.parametersOpen) ParameterCard(state, leftCard)
                            else if (state.prefs.historyOpen) HistoryCard(state, leftCard)
                            if (state.panelVisible) SettingsCard(state, Modifier.align(Alignment.TopEnd).width(CARD_WIDTH).heightIn(max = areaHeight - 80.dp))
                            // zuletzt: die aufgeklappte Werkzeugleiste liegt über allen Karten
                            GroupBar(toolGroups(state) { newSketch = true }, vertical = true, openGroup, { openGroup = it }, Modifier.align(Alignment.TopStart))
                        }
                    }
                }
            }
            if (compact) {
                HorizontalDivider()
                GroupBar(toolGroups(state) { newSketch = true }, vertical = false, openGroup, { openGroup = it }, Modifier.fillMaxWidth())
            }
        }
        // schmal: die aufgeklappte Gruppe schwebt über der unteren Leiste (verschiebt nichts)
        if (compact) {
            GroupFlyout(toolGroups(state) { newSketch = true }, openGroup, { openGroup = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp))
        }
    }
    if (newSketch) {
        PlaneDialog(
            title = "Neue Skizze – Ebene wählen",
            initial = PlaneSpec(BasePlane.XY),
            onConfirm = { newSketch = false; state.addSketch(it) },
            onDismiss = { newSketch = false },
        )
    }
}

// ---- App-Leiste -------------------------------------------------------------------------------------

@Composable
private fun ModelTopBar(hub: ProjectHub, compact: Boolean) {
    val state = hub.workspace
    val out = state.output
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri ->
        val mesh = state.output?.mesh
        if (uri != null && mesh != null) scope.launch(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(mesh.toBinaryStl()) } }
    }
    val threeMfExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        val mesh = state.output?.mesh
        if (uri != null && mesh != null) scope.launch(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(mesh.toThreeMf()) } }
    }
    val stepExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/step")) { uri ->
        val body = state.output?.body
        if (uri != null && body != null) scope.launch(Dispatchers.IO) {
            // OpenCascade schreibt nur in Dateipfade: erst in den Cache, dann an den gewählten Ort kopieren
            val tmp = java.io.File(context.cacheDir, "export.step")
            try {
                body.writeStep(tmp)
                context.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            } catch (e: dev.androidcad.brep.BrepException) {
                withContext(Dispatchers.Main) { state.notice = "STEP-Export fehlgeschlagen: ${e.message}" }
            } finally {
                tmp.delete()
            }
        }
    }
    val projectExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val text = state.exportText()
            scope.launch(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
        }
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProjectMenu(hub)
        Spacer(Modifier.weight(1f))
        if (state.building) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        if (out?.mesh != null && !compact) {
            Text(
                "%.2f cm³ · %d Flächen".format(out.volumeMm3 / 1000.0, out.faceCount),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ActionButton(CadIcon.UNDO, "Rückgängig", enabled = state.canUndo, onClick = state::undo)
        ActionButton(CadIcon.REDO, "Wiederholen", enabled = state.canRedo, onClick = state::redo)
        TextButton(onClick = { state.parametersOpen = !state.parametersOpen }) { Text(if (state.parametersOpen) "Parameter ✕" else "Parameter") }
        TextButton(onClick = { state.prefs.historyOpen = !state.prefs.historyOpen }) {
            Text(if (state.prefs.historyOpen) "Verlauf ✕" else "Verlauf")
        }
        if (!compact) TextButton(onClick = { exporter.launch("${state.projectName}.stl") }, enabled = out?.mesh != null) { Text("STL exportieren") }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (compact) {
                    out?.takeIf { it.mesh != null }?.let { o ->
                        DropdownMenuItem(text = { Text("%.2f cm³ · %d Flächen".format(o.volumeMm3 / 1000.0, o.faceCount)) }, onClick = { menu = false }, enabled = false)
                    }
                    DropdownMenuItem(text = { Text("STL exportieren") }, onClick = { menu = false; exporter.launch("${state.projectName}.stl") }, enabled = out?.mesh != null)
                }
                DropdownMenuItem(text = { Text("STEP exportieren (CAD-Austausch)") }, onClick = { menu = false; stepExporter.launch("${state.projectName}.step") }, enabled = out?.body != null)
                DropdownMenuItem(text = { Text("3MF exportieren (3D-Druck)") }, onClick = { menu = false; threeMfExporter.launch("${state.projectName}.3mf") }, enabled = out?.mesh != null)
                DropdownMenuItem(text = { Text("Projekt exportieren …") }, onClick = { menu = false; projectExporter.launch("${state.projectName}.partwright.json") })
                DropdownMenuItem(text = { Text("Zum Skript-Editor (OpenSCAD)") }, onClick = { menu = false; hub.switchTo(ProjectKind.SCRIPT) })
            }
        }
    }
}

// ---- Werkzeuge --------------------------------------------------------------------------------------

/** Ein Werkzeug der Leiste: Beschriftung, Symbol, ob es gerade läuft, ob es verfügbar ist, und was es tut. */
internal class ToolDef(val label: String, val icon: CadIcon, val active: Boolean, val enabled: Boolean, val onClick: () -> Unit)

/**
 * Eine Gruppe der Werkzeugleiste. Mit genau einem Werkzeug und [direct] ist sie ein einfacher Knopf (z. B. „Auswahl“), sonst
 * klappt ein Tipp ihre Werkzeuge auf.
 */
internal class ToolGroup(val label: String, val icon: CadIcon, val tools: List<ToolDef>, val direct: Boolean = false) {
    val active: Boolean get() = tools.any { it.active }
}

/** Alle 3D-Befehle in vier Gruppen; nicht verfügbare sind ausgegraut, laufende Modi hervorgehoben (erneut tippen beendet sie). */
private fun toolGroups(state: WorkspaceState, onNewSketch: () -> Unit): List<ToolGroup> {
    val hasBody = state.output?.mesh != null
    val canExtrude = state.sketchSelected && state.regionCount > 0
    val canPattern = hasBody && state.canPattern
    return listOf(
        ToolGroup("Skizze", CadIcon.SKETCH, listOf(
            ToolDef("Neu", CadIcon.SKETCH, false, true, onNewSketch),
            ToolDef("Auf Fläche", CadIcon.SKETCH_ON_FACE, state.faceSketchPick, hasBody) {
                if (state.faceSketchPick) state.cancelFaceSketchPick() else state.startFaceSketchPick()
            },
            ToolDef("Ebene", CadIcon.PLANE, state.planePick, hasBody) { if (state.planePick) state.cancelPlanePick() else state.startPlanePick() },
        )),
        ToolGroup("Körper", CadIcon.EXTRUDE, listOf(
            ToolDef("Extrudieren", CadIcon.EXTRUDE, false, canExtrude) { state.createExtrusion(BodyOp.ADD) },
            ToolDef("Abtragen", CadIcon.CUT, false, canExtrude && state.canCut) { state.createExtrusion(BodyOp.CUT) },
            ToolDef("Drehen", CadIcon.REVOLVE, false, canExtrude) { state.createRevolve() },
            ToolDef("Schale", CadIcon.SHELL, state.shellMode != null, hasBody) { if (state.shellMode != null) state.cancelShellMode() else state.startShellMode() },
            ToolDef("Bohrung", CadIcon.HOLE, state.holeMode != null, hasBody) { if (state.holeMode != null) state.cancelHoleMode() else state.startHoleMode() },
        )),
        ToolGroup("Kanten", CadIcon.ROUND, listOf(
            ToolDef("Verrunden", CadIcon.ROUND, state.edgeMode?.kind == EdgeKind.ROUND, hasBody) {
                if (state.edgeMode?.kind == EdgeKind.ROUND) state.cancelEdgeMode() else state.startEdgeMode(EdgeKind.ROUND)
            },
            ToolDef("Fase", CadIcon.CHAMFER, state.edgeMode?.kind == EdgeKind.CHAMFER, hasBody) {
                if (state.edgeMode?.kind == EdgeKind.CHAMFER) state.cancelEdgeMode() else state.startEdgeMode(EdgeKind.CHAMFER)
            },
        )),
        ToolGroup("Messen", CadIcon.MEASURE, listOf(
            ToolDef("Messen", CadIcon.MEASURE, state.measureMode != null, state.output?.body != null) {
                if (state.measureMode != null) state.cancelMeasure() else state.startMeasure()
            },
        ), direct = true),
        ToolGroup("Muster", CadIcon.PATTERN_LINEAR, listOf(
            ToolDef("Reihe", CadIcon.PATTERN_LINEAR, false, canPattern) { state.createPattern(PatternType.LINEAR) },
            ToolDef("Kreis", CadIcon.PATTERN_CIRCULAR, false, canPattern) { state.createPattern(PatternType.CIRCULAR) },
            // Spiegeln: ein gewähltes Feature (Extrusion, Bohrung, Muster, Verrundung) – sonst den ganzen Körper
            ToolDef("Spiegeln", CadIcon.MIRROR, false, hasBody) { if (canPattern) state.createPattern(PatternType.MIRROR) else state.createMirrorBody() },
        )),
    )
}

/**
 * Werkzeuggruppen als schwebende Leiste ([vertical]: links am Rand; sonst waagrecht unten). Ein Tipp auf eine Gruppe klappt ihre
 * Werkzeuge daneben auf ([open] = Name der offenen Gruppe); ein Werkzeug wählen oder die Gruppe erneut antippen klappt sie zu.
 * Auf der waagrechten Leiste zeichnet [GroupFlyout] die aufgeklappten Werkzeuge (schwebend, damit nichts verrutscht).
 */
@Composable
internal fun GroupBar(groups: List<ToolGroup>, vertical: Boolean, open: String?, onOpen: (String?) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val group: @Composable (ToolGroup) -> Unit = { g ->
        if (g.direct) {
            val t = g.tools.single()
            ToolButton(g.label, g.icon, t.active, enabled = t.enabled, size = 52.dp) { onOpen(null); t.onClick() }
        } else {
            ToolButton(g.label, g.icon, open == g.label || g.active, size = 52.dp) { onOpen(if (open == g.label) null else g.label) }
        }
    }
    if (vertical) {
        Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                Modifier.glass(shape).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { groups.forEach { group(it) } }
            groups.firstOrNull { it.label == open && !it.direct }?.let { g ->
                // Leiste auf Höhe ihrer Gruppe
                Box(Modifier.padding(top = (groups.indexOf(g) * 56).dp)) { ToolStrip(g) { onOpen(null) } }
            }
        }
    } else {
        Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) { groups.forEach { group(it) } }
    }
}

/** Schmal: die Werkzeuge der aufgeklappten Gruppe (schwebend über der unteren Leiste). */
@Composable
internal fun GroupFlyout(groups: List<ToolGroup>, open: String?, onOpen: (String?) -> Unit, modifier: Modifier) {
    val g = groups.firstOrNull { it.label == open && !it.direct } ?: return
    Box(modifier) { ToolStrip(g) { onOpen(null) } }
}

/** Die Werkzeuge einer Gruppe als Reihe; ein Werkzeug wählen klappt die Gruppe zu ([onDone]). */
@Composable
private fun ToolStrip(g: ToolGroup, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.glass(shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        g.tools.forEach { t -> ToolButton(t.label, t.icon, t.active, enabled = t.enabled, size = 56.dp) { onDone(); t.onClick() } }
    }
}

// ---- Verlauf ----------------------------------------------------------------------------------------

/** Chip mit dem aktuellen Schritt des Modellaufbaus: ‹ › blättert, ein Tipp auf den Namen öffnet bzw. schließt den ganzen Verlauf. */
@Composable
private fun StepChip(state: WorkspaceState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val features = state.features
    val index = features.indexOfFirst { it.id == state.selectedId }
    val errors = state.output?.errorIds ?: emptySet()
    val shape = RoundedCornerShape(50)
    Row(
        modifier.glass(shape).height(40.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { features.getOrNull(index - 1)?.let { state.select(it.id) } }, enabled = index > 0) {
            Text("‹", style = MaterialTheme.typography.titleLarge)
        }
        val f = features.getOrNull(index)
        Row(
            Modifier.clickable { state.prefs.historyOpen = !state.prefs.historyOpen }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (f != null) CadIconView(f.icon(), colors.onPrimaryContainer, 18.dp)
            Text(
                f?.name ?: "Modellaufbau · ${features.size} ${if (features.size == 1) "Schritt" else "Schritte"}",
                style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer, maxLines = 1,
            )
            if (errors.isNotEmpty()) Text("⚠ ${errors.size}", color = colors.error, style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = { features.getOrNull(index + 1)?.let { state.select(it.id) } }, enabled = index < features.size - 1) {
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Der ganze Modellaufbau als Karte (nur auf Wunsch). */
@Composable
private fun HistoryCard(state: WorkspaceState, modifier: Modifier) {
    FloatingCard(modifier, title = "Modellaufbau · ${state.features.size} Schritte", icon = null, onClose = { state.prefs.historyOpen = false }) {
        FeatureList(state)
    }
}

/** Die Parameter-Tabelle als Karte (nur auf Wunsch). */
@Composable
private fun ParameterCard(state: WorkspaceState, modifier: Modifier) {
    FloatingCard(modifier, title = "Parameter · ${state.parameters.size}", icon = null, onClose = { state.parametersOpen = false }) {
        ParameterTable(state)
    }
}

// ---- Einstellungen ----------------------------------------------------------------------------------

/** Einstellungen des gewählten Features bzw. des laufenden Werkzeugs als schwebende, einklappbare Karte. */
@Composable
private fun SettingsCard(state: WorkspaceState, modifier: Modifier) {
    val selected = state.features.firstOrNull { it.id == state.selectedId }
    val (title, icon) = when {
        state.faceSketchPick -> "Skizze auf Fläche" to CadIcon.SKETCH_ON_FACE
        state.planePick -> "Neue Arbeitsebene" to CadIcon.PLANE
        state.holeMode != null -> "Bohrung setzen" to CadIcon.HOLE
        state.shellMode != null -> "Aushöhlen" to CadIcon.SHELL
        state.measureMode != null -> "Messen" to CadIcon.MEASURE
        state.edgeMode != null -> if (state.edgeMode?.kind == EdgeKind.ROUND) "Verrunden" to CadIcon.ROUND else "Fase" to CadIcon.CHAMFER
        selected != null -> selected.name to selected.icon()
        else -> "Eigenschaften" to null
    }
    var collapsed by remember { mutableStateOf(false) }
    FloatingCard(modifier, title, icon, onClose = state::closePanel, collapsed = collapsed, onToggle = { collapsed = !collapsed }) {
        FeatureControls(state)
    }
}

/**
 * Schwebende Karte über dem 3D-Bild: Kopfzeile mit Symbol, Titel, Einklappen (falls [onToggle]) und ✕; darunter scrollender
 * Inhalt. Die Höhe begrenzt der Aufrufer über [modifier].
 */
@Composable
private fun FloatingCard(
    modifier: Modifier, title: String, icon: CadIcon?, onClose: () -> Unit,
    collapsed: Boolean = false, onToggle: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.glass(shape)) {
        Row(
            Modifier.fillMaxWidth().let { if (onToggle != null) it.clickable(onClick = onToggle) else it }.padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) CadIconView(icon, colors.primary, 18.dp)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.primary, maxLines = 1)
            if (onToggle != null) Text(if (collapsed) "▸" else "▾", color = colors.primary)
            TextButton(onClick = onClose) { Text("✕") }
        }
        if (!collapsed) {
            HorizontalDivider()
            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
        }
    }
}

@Composable
internal fun RailLabel(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- 3D-Bild ----------------------------------------------------------------------------------------

@Composable
private fun ModelViewport(state: WorkspaceState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val cad = CadTheme.colors
    val out = state.output
    Box(modifier.background(Brush.verticalGradient(listOf(cad.viewportTop, cad.viewportBottom)))) {
        val mesh = out?.mesh?.takeIf { !it.isEmpty }
        if (mesh != null || state.sketchOverlay != null) {
            MeshViewport(
                mesh, state.camera, base = cad.model, background = cad.viewportTop, backgroundBottom = cad.viewportBottom,
                lineColor = cad.modelEdge, highlightColor = cad.selection, sketchColor = cad.sketchInSpace,
                edges = out?.edges, highlight = state.highlight, sketchLines = state.sketchOverlay,
                pickEdges = if ((state.edgeMode != null || state.measureMode != null) && out != null) EdgePickData(out.polylines, out.edgeCounts) else null,
                onPickEdge = state::pickEdge,
                pickFaces = if ((state.holeMode != null || state.shellMode != null || state.measureMode != null || state.faceSketchPick || state.planePick) && out != null) FacePickData(out.triangleFace) else null,
                onPickFace = state::pickFace,
            )
        }
        if (mesh == null) {
            Text(
                if (state.features.none { it is dev.androidcad.model.ExtrudeFeature || it is dev.androidcad.model.RevolveFeature }) "Noch kein Körper – wähle eine Skizze, dann Körper › Extrudieren oder Drehen."
                else "Kein Körper – siehe die Meldung oben.",
                // mit Skizzenlinien im Bild unten, damit der Hinweis sie nicht überdeckt
                Modifier.align(if (state.sketchOverlay != null) Alignment.BottomCenter else Alignment.Center).padding(24.dp),
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        AxisTriad(state.camera, Modifier.align(Alignment.BottomStart).padding(12.dp).size(72.dp))
    }
}

/** Fehler des letzten Aufbaus als Band oben im Bild (über allen schwebenden Elementen). */
@Composable
private fun ErrorBanner(state: WorkspaceState) {
    val colors = MaterialTheme.colorScheme
    val errors = state.output?.errors
    if (errors.isNullOrEmpty()) return
    Column(Modifier.fillMaxWidth().background(colors.errorContainer.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer) }
    }
}

/** Knopf „Ansicht“ mit den Standardansichten und „Alles einpassen“ als Menü. */
@Composable
private fun ViewMenu(state: WorkspaceState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.glass(shape),
        ) { Text("Ansicht ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StdView.entries.forEach { v -> DropdownMenuItem(text = { Text(v.label) }, onClick = { open = false; state.showView(v) }) }
            DropdownMenuItem(text = { Text("Alles einpassen") }, onClick = { open = false; state.fitView() })
        }
    }
}

/** Achsenkreuz: zeigt die Richtungen von X (rot), Y (grün) und Z (blau) in der aktuellen Blickrichtung. */
@Composable
private fun AxisTriad(camera: ViewCamera, modifier: Modifier) {
    val density = LocalDensity.current
    val labelColor = MaterialTheme.colorScheme.onSurface
    val cad = CadTheme.colors
    val axisColors = listOf(cad.axisX, cad.axisY, cad.axisZ)
    val yaw = camera.yaw; val pitch = camera.pitch // gelesen → bei jeder Drehung neu zeichnen
    val view = remember(yaw, pitch) { OrthoCamera(yaw, pitch, 1f, SceneFrame(FloatArray(3), 1f), 1, 1).view }
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val len = size.minDimension * 0.36f
        val paint = android.graphics.Paint().apply {
            textSize = with(density) { 12.dp.toPx() }
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        // hintere Achsen zuerst, damit die vorderen darüber liegen
        val order = (0..2).sortedBy { view[it * 4 + 2] }
        for (i in order) {
            val dx = view[i * 4]; val dy = -view[i * 4 + 1] // Sichtraum-y zeigt nach oben, Bildschirm-y nach unten
            val end = Offset(c.x + dx * len, c.y + dy * len)
            drawLine(axisColors[i], c, end, strokeWidth = with(density) { 2.5.dp.toPx() }, cap = StrokeCap.Round)
            paint.color = axisColors[i].toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                listOf("X", "Y", "Z")[i], c.x + dx * len * 1.3f, c.y + dy * len * 1.3f + paint.textSize / 3, paint,
            )
        }
        drawCircle(labelColor.copy(alpha = 0.5f), radius = with(density) { 2.dp.toPx() }, center = c)
    }
}
