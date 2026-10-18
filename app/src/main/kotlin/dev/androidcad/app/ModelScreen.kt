package dev.androidcad.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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

/** Unter dieser Breite ist die Kopfzeile kompakt: Volumen und „STL exportieren“ wandern ins ⋮-Menü (sonst läuft sie z. B. auf dem 7″-Tablet hoch über). */
private val COMPACT_WIDTH = 720.dp

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
                                    ViewMenu(state, Modifier.align(Alignment.TopEnd))
                                    // unten; das Achsenkreuz sitzt darüber
                                    Timeline(state, Modifier.align(Alignment.BottomStart))
                                }
                            }
                            DismissLayer(openGroup != null) { openGroup = null }
                        }
                        // so hoch, dass die Einstellungen meist ganz passen; dem 3D-Bild bleiben mindestens 260 dp
                        val sheet = Modifier.fillMaxWidth().heightIn(max = maxOf(areaHeight * 0.5f, areaHeight - 260.dp)).padding(horizontal = 6.dp, vertical = 4.dp)
                        if (state.parametersOpen) ParameterCard(state, sheet) else if (state.panelVisible) SettingsCard(state, sheet)
                    }
                } else {
                    // das Modell zentriert sich im freien Bereich neben der Werkzeugleiste und den offenen Karten
                    val cad = CadTheme.colors
                    val left = if (state.parametersOpen) 372.dp else 76.dp
                    val right = if (state.panelVisible) CARD_WIDTH + 16.dp else 0.dp
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cad.viewportTop, cad.viewportBottom)))) {
                        ModelViewport(state, Modifier.fillMaxSize().padding(start = left, end = right))
                    }
                    Column(Modifier.fillMaxSize()) {
                        ErrorBanner(state)
                        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                            // offene Karte: das Ansicht-Menü rückt links neben sie, damit die Karte die ganze Höhe nutzen kann
                            val cardSpace = if (state.panelVisible) CARD_WIDTH + 8.dp else 0.dp
                            ViewMenu(state, Modifier.align(Alignment.BottomEnd).padding(end = cardSpace))
                            // unten: Zeitleiste zwischen Werkzeugleiste und Ansicht-Menü
                            Timeline(state, Modifier.align(Alignment.BottomStart).padding(start = 76.dp, end = cardSpace + 140.dp))
                            if (state.parametersOpen) ParameterCard(state, Modifier.align(Alignment.TopStart).padding(start = 76.dp).width(280.dp).heightIn(max = areaHeight - 80.dp))
                            // Karte über die ganze Höhe; Zeitleiste und Ansicht-Menü liegen links davon
                            if (state.panelVisible) SettingsCard(state, Modifier.align(Alignment.TopEnd).width(CARD_WIDTH).heightIn(max = areaHeight - 16.dp))
                            DismissLayer(openGroup != null) { openGroup = null }
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
            onFace = if (state.output?.mesh != null) ({ newSketch = false; state.startFaceSketchPick() }) else null,
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
        // kompakt ohne Zwischenräume: die Knöpfe bringen eigenen Innenabstand mit, der Platz geht an den Projektnamen
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp),
    ) {
        // nimmt den freien Platz und kürzt einen langen Namen, damit ⋮ auch auf schmalen Smartphones ganz sichtbar bleibt
        ProjectMenu(hub, Modifier.weight(1f))
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
        if (!compact) TextButton(onClick = { exporter.launch("${state.projectName}.stl") }, enabled = out?.mesh != null) { Text("STL exportieren") }
        Box {
            TextButton(onClick = { menu = true }, Modifier.width(44.dp), contentPadding = PaddingValues(0.dp)) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (compact) {
                    out?.takeIf { it.mesh != null }?.let { o ->
                        DropdownMenuItem(text = { Text("%.2f cm³ · %d Flächen".format(o.volumeMm3 / 1000.0, o.faceCount)) }, onClick = { menu = false }, enabled = false)
                    }
                    DropdownMenuItem(text = { Text("STL exportieren") }, onClick = { menu = false; exporter.launch("${state.projectName}.stl") }, enabled = out?.mesh != null)
                }
                DropdownMenuItem(text = { Text("STEP exportieren (CAD-Austausch)") }, onClick = { menu = false; stepExporter.launch("${state.projectName}.step") }, enabled = out?.body != null)
                DropdownMenuItem(text = { Text("3MF exportieren (3D-Druck)") }, onClick = { menu = false; threeMfExporter.launch("${state.projectName}.3mf") }, enabled = out?.mesh != null)
                DropdownMenuItem(text = { Text("Projekt exportieren …") }, onClick = { menu = false; projectExporter.launch("${state.projectName}.paramworker.json") })
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
            ToolButton(g.label, g.icon, g.active, size = 52.dp, outlined = open == g.label) { onOpen(if (open == g.label) null else g.label) }
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
        // mittig mit festem Abstand statt über die ganze Breite verteilt (sonst große Lücken auf breiteren Geräten)
        Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) { groups.forEach { group(it) } }
    }
}

/**
 * Solange eine Werkzeuggruppe aufgeklappt ist: Tippen ins Leere (Bild, Zeichenfläche) schließt sie, ohne dort etwas auszulösen.
 * Muss vor der Werkzeugleiste im selben [Box] stehen, damit die Leiste darüber bedienbar bleibt.
 */
@Composable
internal fun BoxScope.DismissLayer(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    Box(Modifier.matchParentSize().pointerInput(Unit) {
        awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume(); onDismiss() }
    })
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
    // deckend statt Glas: die Leiste liegt über Karten und Hinweisen, deren Text sonst durchscheint
    Row(
        // scrollt seitlich, wenn die Gruppe breiter ist als der Bildschirm (Smartphone: „Zeichnen“ hat sieben Werkzeuge)
        Modifier.shadow(8.dp, shape).background(colors.surfaceContainerHigh, shape).border(1.dp, colors.outlineVariant, shape)
            .horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        g.tools.forEach { t -> ToolButton(t.label, t.icon, t.active, enabled = t.enabled, size = 56.dp) { onDone(); t.onClick() } }
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
    // Bearbeiten eines Features: ✓ übernimmt, ✕ verwirft; Werkzeuge (Kanten wählen …) haben eigene Knöpfe, dort schließt ✕ nur
    val editing = state.editing
    FloatingCard(
        modifier, title, icon, onClose = if (editing) state::cancelEdit else state::closePanel, collapsed = collapsed, onToggle = { collapsed = !collapsed },
        onConfirm = if (editing) state::closePanel else null,
    ) {
        FeatureControls(state)
    }
}

/**
 * Schwebende Karte über dem 3D-Bild: Kopfzeile mit Symbol, Titel, Einklappen (falls [onToggle]) und ✕; darunter scrollender
 * Inhalt. Mit [onConfirm] (Bearbeiten) statt ✕ eine Fußzeile „✕ Abbrechen“ ([onClose]) / „✓ Fertig“. Die Höhe begrenzt der Aufrufer über [modifier].
 */
@Composable
private fun FloatingCard(
    modifier: Modifier, title: String, icon: CadIcon?, onClose: () -> Unit,
    collapsed: Boolean = false, onToggle: (() -> Unit)? = null, onConfirm: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.glass(shape)) {
        Row(
            Modifier.fillMaxWidth().let { if (onToggle != null) it.clickable(onClick = onToggle) else it }.padding(start = 14.dp, end = if (onConfirm != null) 14.dp else 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) CadIconView(icon, colors.primary, 18.dp)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.primary, maxLines = 1)
            if (onToggle != null) Text(if (collapsed) "▸" else "▾", color = colors.primary)
            if (onConfirm == null) TextButton(onClick = onClose) { Text("✕") }
        }
        if (!collapsed) {
            HorizontalDivider()
            // weight: der Inhalt scrollt, die Fußzeile mit ✕/✓ bleibt sichtbar
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
        }
        if (onConfirm != null) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("✕ Abbrechen") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("✓ Fertig") }
            }
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
                // Gitter aus Hintergrund und Hilfslinienfarbe gemischt: sichtbar über den ganzen Verlauf, aber zurückhaltend
                grid = state.grid, gridColor = androidx.compose.ui.graphics.lerp(cad.viewportBottom, cad.construction, 0.3f),
                gridMajorColor = androidx.compose.ui.graphics.lerp(cad.viewportBottom, cad.construction, 0.55f),
                edges = out?.edges, highlight = state.highlight, sketchLines = state.sketchOverlay,
                pickEdges = if ((state.edgeMode != null || state.measureMode != null) && out != null) EdgePickData(out.polylines, out.edgeCounts) else null,
                onPickEdge = state::pickEdge,
                pickFaces = if ((state.holeMode != null || state.shellMode != null || state.measureMode != null || state.faceSketchPick || state.planePick) && out != null) FacePickData(out.triangleFace) else null,
                onPickFace = state::pickFace,
            )
        }
        if (mesh == null) {
            Text(
                when {
                    state.features.none { it is dev.androidcad.model.ExtrudeFeature || it is dev.androidcad.model.RevolveFeature } ->
                        "Noch kein Körper – wähle eine Skizze, dann Körper › Extrudieren oder Drehen."
                    // erster Aufbau läuft noch (z. B. gleich nach dem Öffnen): nicht von einem Fehler sprechen
                    state.building || out == null -> "Körper wird berechnet …"
                    else -> "Kein Körper – siehe die Meldung oben."
                },
                // mit Skizzenlinien im Bild unten (über der Zeitleiste), damit der Hinweis sie nicht überdeckt
                Modifier.align(if (state.sketchOverlay != null) Alignment.BottomCenter else Alignment.Center)
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = if (state.sketchOverlay != null) 72.dp else 24.dp),
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        // über der Zeitleiste
        AxisTriad(state.camera, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 64.dp).size(72.dp))
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
            DropdownMenuItem(text = { Text("Gitter" + if (state.gridOn) "  ✓" else "") }, onClick = { open = false; state.gridOn = !state.gridOn })
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
