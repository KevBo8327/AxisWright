package dev.androidcad.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.androidcad.sketchedit.Sel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxScope
import dev.androidcad.sketchedit.ActionKind
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidcad.model.BasePlane
import dev.androidcad.model.PlaneSpec
import dev.androidcad.sketchedit.ActionGroup
import dev.androidcad.sketchedit.PendingAction
import dev.androidcad.sketchedit.ValueEdit
import dev.androidcad.sketchedit.SketchEditor
import dev.androidcad.sketchedit.Tool
import dev.androidcad.sketchedit.formatValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Arbeitsfläche mit zwei Modi, die je den ganzen Bildschirm nutzen:
 * **Skizze** (2D-Zeichenfläche mit Werkzeug- und Constraint-Leiste, „✓ Fertig“) und **Modell** (siehe [ModelScreen]).
 * „Fertig“ schwenkt die Kamera von der Skizzenebene in die isometrische Ansicht.
 */
@Composable
fun WorkspaceScreen(hub: ProjectHub) {
    val state = hub.workspace
    when (state.mode) {
        WorkMode.SKETCH -> SketchPanel(state, Modifier.fillMaxSize())
        WorkMode.MODEL -> ModelScreen(hub)
    }
}

@Composable
internal fun NoticeRow(state: WorkspaceState) {
    state.notice?.let { n ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { state.notice = null }) { Text("OK") }
        }
    }
}

// ---- Skizze -----------------------------------------------------------------------------------------

/**
 * Skizzenmodus: Die Zeichenfläche füllt den ganzen Bildschirm; alles andere erscheint nur, wenn es gebraucht wird.
 *
 * - **Kopfzeile**: Skizze, Rückgängig/Wiederholen/Löschen, „Fertig“; Ebene, Kanten projizieren, Raster und Einpassen stehen breit
 *   als Knöpfe, schmal im ⋮-Menü.
 * - **Werkzeuge** in Gruppen (Auswahl, Mehrfach, Zeichnen, Bearbeiten, Muster) – breit links, schmal unten (wie im Modellmodus).
 * - **Status** als kleine Plakette oben im Bild, Meldungen des Editors als Band darüber.
 * - **Kontextleiste** ([ContextBar]): Bedingungen und Maße zur Auswahl schweben an der Auswahl; Hinweise und Felder eines
 *   Werkzeugs unten im Bild – nur solange sie gebraucht werden.
 */
@Composable
private fun SketchPanel(state: WorkspaceState, modifier: Modifier) {
    val handle = state.handle
    var multiSelect by rememberSaveable { mutableStateOf(false) }
    var planeDialog by remember { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier) {
        // Kopfzeile kompakt nur auf Smartphones; Werkzeuge unten auch auf Tablets im Hochformat (dort ist unten mehr Platz)
        val narrow = maxWidth < SKETCH_COMPACT_WIDTH
        val compact = maxWidth < BOTTOM_TOOLS_WIDTH || maxWidth < maxHeight
        val density = LocalDensity.current
        handle.insetTop = with(density) { 44.dp.toPx().toDouble() }
        handle.insetLeft = if (compact) 0.0 else with(density) { 80.dp.toPx().toDouble() }
        val e = handle.editor
        handle.version // Beobachtung
        val groups = sketchToolGroups(e, multiSelect) { multiSelect = it }
        Column(Modifier.fillMaxSize()) {
            SketchTopBar(state, narrow, onPlaneDialog = { planeDialog = true })
            HorizontalDivider()
            NoticeRow(state)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SketchCanvas(handle, Modifier.fillMaxSize(), multiSelect, state.referenceLines)
                StatusPill(handle, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                // Meldungen des Editors oben im Zeichenfeld – wie die Fehlerzeile im Modellmodus
                MessageBand(handle, Modifier.align(Alignment.TopStart).fillMaxWidth())
                ContextBar(handle, bottomGap = if (compact) 12.dp else 16.dp)
                if (!compact) GroupBar(groups, vertical = true, openGroup, { openGroup = it }, Modifier.align(Alignment.TopStart).padding(8.dp).padding(top = 40.dp))
            }
            if (compact) {
                HorizontalDivider()
                GroupBar(groups, vertical = false, openGroup, { openGroup = it }, Modifier.fillMaxWidth())
            }
        }
        if (compact) GroupFlyout(groups, openGroup, { openGroup = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp))
    }
    handle.version // Beobachtung: bei jeder Änderung des Editors neu zusammensetzen
    ValueDialogs(handle.editor, handle.editor.pendingAction, handle.editor.valueEdit)
    if (planeDialog) {
        PlaneDialog(
            title = "Ebene der Skizze ändern",
            initial = PlaneSpec.from(state.sketch.plane) ?: PlaneSpec(BasePlane.XY),
            onConfirm = { planeDialog = false; state.changePlane(it) },
            onDismiss = { planeDialog = false },
        )
    }
}

/** Unter dieser Breite gilt das Gerät als schmal (Smartphone): Kopfzeile kompakt. */
private val SKETCH_COMPACT_WIDTH = 600.dp

/** Unter dieser Breite (oder im Hochformat) stehen die Werkzeuge unten statt links. */
internal val BOTTOM_TOOLS_WIDTH = 720.dp

/** Kopfzeile der Skizze; schmal wandern Ebene/Kanten projizieren, Raster und Einpassen ins ⋮-Menü. */
@Composable
private fun SketchTopBar(state: WorkspaceState, compact: Boolean, onPlaneDialog: () -> Unit) {
    val handle = state.handle
    val e = handle.editor
    handle.version // Beobachtung: Rückgängig/Löschen hängen am Editorzustand, nicht an den Parametern
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val fit = { handle.fit(referencePoints(state.referenceLines)) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CadIconView(CadIcon.SKETCH, colors.primary, 22.dp)
            Text(state.sketch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) {
                Text(planeLabel(state.sketch), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                if (!state.sketch.planeIsDerived) TextButton(onClick = onPlaneDialog) { Text("Ebene …") }
                else if (state.sketch.onFace != null) TextButton(onClick = state::projectFaceEdges) { Text("Kanten projizieren") }
            }
        }
        ActionButton(CadIcon.UNDO, "Rückgängig", enabled = e.canUndo) { e.undo() }
        ActionButton(CadIcon.REDO, "Wiederholen", enabled = e.canRedo) { e.redo() }
        ActionButton(CadIcon.DELETE, "Auswahl löschen", enabled = e.selection.isNotEmpty()) { e.deleteSelection() }
        if (!compact) {
            ActionButton(CadIcon.GRID, "Am Raster fangen", selected = e.gridSnap) { e.gridSnap = !e.gridSnap }
            ActionButton(CadIcon.FIT, "Alles einpassen") { fit() }
        } else {
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ebene: ${planeLabel(state.sketch)}") }, onClick = { menu = false }, enabled = false)
                    if (!state.sketch.planeIsDerived) DropdownMenuItem(text = { Text("Ebene ändern …") }, onClick = { menu = false; onPlaneDialog() })
                    else if (state.sketch.onFace != null) DropdownMenuItem(text = { Text("Kanten projizieren") }, onClick = { menu = false; state.projectFaceEdges() })
                    DropdownMenuItem(text = { Text(if (e.gridSnap) "Am Raster fangen ✓" else "Am Raster fangen") }, onClick = { menu = false; e.gridSnap = !e.gridSnap })
                    DropdownMenuItem(text = { Text("Alles einpassen") }, onClick = { menu = false; fit() })
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Button(onClick = state::finishSketch) {
            CadIconView(CadIcon.DONE, colors.onPrimary, 18.dp)
            if (!compact) { Spacer(Modifier.width(6.dp)); Text("Fertig") }
        }
    }
}

/**
 * Werkzeuge der Skizze in Gruppen: **Auswahl** und **Mehrfach** als direkte Knöpfe, **Zeichnen** (Linie, Rechteck, Kreis, Bogen),
 * **Bearbeiten** (Trimmen, Verlängern, Versatz, Ecke, Spiegeln) und **Muster** (Reihe, Kreis) zum Aufklappen.
 */
private fun sketchToolGroups(e: SketchEditor, multiSelect: Boolean, onMultiSelect: (Boolean) -> Unit): List<ToolGroup> {
    fun tool(label: String, icon: CadIcon, t: Tool) = ToolDef(label, icon, e.tool == t, true) { e.setTool(t) }
    val drawIcon = when (e.tool) {
        Tool.RECTANGLE -> CadIcon.RECTANGLE
        Tool.CIRCLE -> CadIcon.CIRCLE
        Tool.ARC -> CadIcon.ARC
        else -> CadIcon.LINE
    }
    return listOf(
        ToolGroup("Auswahl", CadIcon.SELECT, listOf(tool("Auswahl", CadIcon.SELECT, Tool.SELECT)), direct = true),
        ToolGroup("Mehrfach", CadIcon.MULTI, listOf(ToolDef("Mehrfach", CadIcon.MULTI, multiSelect, true) { onMultiSelect(!multiSelect) }), direct = true),
        ToolGroup("Zeichnen", drawIcon, listOf(
            tool("Linie", CadIcon.LINE, Tool.LINE), tool("Rechteck", CadIcon.RECTANGLE, Tool.RECTANGLE),
            tool("Kreis", CadIcon.CIRCLE, Tool.CIRCLE), tool("Bogen", CadIcon.ARC, Tool.ARC),
        )),
        ToolGroup("Bearbeiten", CadIcon.TRIM, listOf(
            tool("Trimmen", CadIcon.TRIM, Tool.TRIM), tool("Verlängern", CadIcon.EXTEND, Tool.EXTEND),
            tool("Versatz", CadIcon.OFFSET, Tool.OFFSET), tool("Ecke", CadIcon.FILLET, Tool.FILLET),
            ToolDef("Spiegeln", CadIcon.MIRROR, e.tool == Tool.MIRROR, e.selection.isNotEmpty() || e.mirrorPending) {
                if (e.mirrorPending) e.cancelOperation() else e.startMirror()
            },
        )),
        ToolGroup("Muster", CadIcon.PATTERN_LINEAR, listOf(
            ToolDef("Reihe", CadIcon.PATTERN_LINEAR, e.tool == Tool.PATTERN_LINEAR, e.selection.isNotEmpty() || e.tool == Tool.PATTERN_LINEAR) {
                if (e.tool == Tool.PATTERN_LINEAR) e.cancelOperation() else e.startPattern(circular = false)
            },
            ToolDef("Kreis", CadIcon.PATTERN_CIRCULAR, e.tool == Tool.PATTERN_CIRCULAR, e.selection.isNotEmpty() || e.tool == Tool.PATTERN_CIRCULAR) {
                if (e.tool == Tool.PATTERN_CIRCULAR) e.cancelOperation() else e.startPattern(circular = true)
            },
        )),
    )
}

/** Status der Skizze („Vollständig bestimmt“, „2 Freiheitsgrade“, Widerspruch …) als kleine Plakette. */
@Composable
private fun StatusPill(handle: EditorHandle, modifier: Modifier) {
    val e = handle.editor
    handle.version // Beobachtung: der Editor bleibt dasselbe Objekt, nur sein Zustand ändert sich
    val colors = MaterialTheme.colorScheme
    val text = e.statusText()
    if (text.isEmpty()) return
    val conflict = e.status?.isSolved == false
    val shape = RoundedCornerShape(50)
    Text(
        text, modifier.background((if (conflict) colors.errorContainer else colors.surface).copy(alpha = 0.92f), shape)
            .border(0.5.dp, colors.outlineVariant, shape).padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = if (conflict) colors.onErrorContainer else colors.primary, maxLines = 1,
    )
}

/** Meldung des Editors (Fehler rot, Erfolg neutral) als Band oben im Zeichenfeld. */
@Composable
private fun MessageBand(handle: EditorHandle, modifier: Modifier) {
    val e = handle.editor
    handle.version // Beobachtung
    val colors = MaterialTheme.colorScheme
    val m = e.message ?: return
    val info = e.messageIsInfo
    Text(
        m, modifier.background((if (info) colors.secondaryContainer else colors.errorContainer).copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = if (info) colors.onSecondaryContainer else colors.onErrorContainer,
    )
}

/** Kleiner quadratischer Symbolknopf für die Kopfzeile; die Beschreibung erscheint als Tooltip (Maus: Zeigen, Touch: lange drücken). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionButton(icon: CadIcon, description: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(if (selected) colors.primaryContainer else Color.Transparent, shape)
                .then(if (selected) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
                .semantics { contentDescription = description }
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            CadIconView(icon, if (!enabled) colors.onSurface.copy(alpha = 0.3f) else if (selected) colors.onPrimaryContainer else colors.onSurface)
        }
    }
}

/** Werkzeugknopf mit Symbol und Beschriftung (Skizzen- und 3D-Werkzeugleiste). */
@Composable
internal fun ToolButton(label: String, icon: CadIcon, selected: Boolean, enabled: Boolean = true, size: androidx.compose.ui.unit.Dp = 60.dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .size(size)
            .background(if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.4f), shape)
            .then(if (selected) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = if (!enabled) colors.onSurface.copy(alpha = 0.3f) else if (selected) colors.onPrimaryContainer else colors.onSurface
        CadIconView(icon, tint, 24.dp)
        Text(label, fontSize = if (size < 56.dp) 9.sp else 10.sp, color = tint, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * Schwebende Kontextleiste im Zeichenfeld, nur wenn es etwas anzubieten gibt:
 * - Auswahl-Werkzeug mit Auswahl: Bedingungen und Maße (Lage-Aktionen unter „Ausrichten ▾“) – **unter der Auswahl**, ohne Platz darüber.
 * - Zeichen- und Bearbeitungswerkzeuge: kurzer Hinweis und die Felder bzw. Knöpfe des Werkzeugs – unten mittig.
 * Muss in einer [Box] stehen, die genauso groß ist wie die Zeichenfläche (deren Koordinaten nutzt die Platzierung).
 */
@Composable
private fun BoxScope.ContextBar(handle: EditorHandle, bottomGap: androidx.compose.ui.unit.Dp) {
    val e = handle.editor
    handle.version // Beobachtung
    val colors = MaterialTheme.colorScheme
    val options = e.actionOptions()
    val selecting = e.tool == Tool.SELECT
    if (selecting && e.selection.isEmpty()) return
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shape = RoundedCornerShape(16.dp)
    val box = if (selecting) selectionBox(e) else null
    BoxWithConstraints(Modifier.matchParentSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val margin = with(density) { 8.dp.toPx() }
        val gap = with(density) { 18.dp.toPx() }
        val x: Float; val y: Float
        if (box != null) {
            x = (box.center.x - size.width / 2f).coerceIn(margin, (w - size.width - margin).coerceAtLeast(margin))
            // bevorzugt unter der Auswahl (die Form bleibt sichtbar, die Leiste ist nah am Daumen), sonst darüber
            val below = box.bottom + gap
            val above = box.top - size.height - gap
            y = when {
                below + size.height <= h - margin -> below
                above >= margin + with(density) { 36.dp.toPx() } -> above
                else -> (h - size.height - margin).coerceAtLeast(margin)
            }
        } else {
            x = ((w - size.width) / 2f).coerceAtLeast(margin)
            y = (h - size.height - with(density) { bottomGap.toPx() }).coerceAtLeast(margin)
        }
        Column(
            Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.onSizeChanged { size = it }
                .widthIn(max = with(density) { (w - 2 * margin).toDp() })
                .background(colors.surface.copy(alpha = 0.97f), shape).border(0.5.dp, colors.outlineVariant, shape)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!selecting) Text(hintFor(e), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    e.tool.isModify -> when (e.tool) {
                        Tool.OFFSET -> ToolNumberField("Abstand (mm)", e.offsetDistance) { e.offsetDistance = it }
                        Tool.FILLET -> ToolNumberField("Radius (mm)", e.filletRadius) { e.filletRadius = it }
                        Tool.MIRROR -> {
                            OutlinedButton(onClick = { e.mirrorAcrossAxis(horizontal = true) }) { Text("⇋ an der X-Achse") }
                            OutlinedButton(onClick = { e.mirrorAcrossAxis(horizontal = false) }) { Text("⇋ an der Y-Achse") }
                            OutlinedButton(onClick = { e.cancelOperation() }) { Text("Abbrechen") }
                        }
                        Tool.PATTERN_LINEAR -> {
                            ToolNumberField("Anzahl", e.patternCount.toDouble()) { e.patternCount = Math.round(it).toInt() }
                            ToolNumberField("Abstand (mm)", e.patternSpacing) { e.patternSpacing = it }
                            OutlinedButton(onClick = { e.patternAlongAxis(horizontal = true) }) { Text("→ entlang X") }
                            OutlinedButton(onClick = { e.patternAlongAxis(horizontal = false) }) { Text("↑ entlang Y") }
                            OutlinedButton(onClick = { e.cancelOperation() }) { Text("Abbrechen") }
                        }
                        Tool.PATTERN_CIRCULAR -> {
                            ToolNumberField("Anzahl", e.patternCount.toDouble()) { e.patternCount = Math.round(it).toInt() }
                            ToolNumberField("Winkel (°)", e.patternAngle) { if (it <= 360.0) e.patternAngle = it }
                            OutlinedButton(onClick = { e.patternAroundOrigin() }) { Text("◎ um den Ursprung") }
                            OutlinedButton(onClick = { e.cancelOperation() }) { Text("Abbrechen") }
                        }
                        else -> Unit
                    }
                    !selecting -> if (e.drawingInProgress) OutlinedButton(onClick = { e.cancelOperation() }) { Text("✓ Zug beenden") }
                    options.isEmpty() -> Text(
                        "Keine Bedingungen für diese Auswahl – z. B. zwei Linien oder einen Kreis wählen.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    else -> {
                        for (group in listOf(ActionGroup.GEOMETRY, ActionGroup.DIMENSION, ActionGroup.OTHER)) {
                            options.filter { it.kind.group == group && it.kind !in ALIGN_ACTIONS }.forEach { opt ->
                                AssistChip(
                                    onClick = { e.requestAction(opt.kind) },
                                    label = { Text("${opt.kind.symbol} ${opt.kind.label}" + (opt.defaultValue?.let { "  ${formatValue(it)}" } ?: "")) },
                                )
                            }
                        }
                        // Lage-Aktionen gesammelt hinter einem Chip
                        val align = options.filter { it.kind in ALIGN_ACTIONS }
                        if (align.isNotEmpty()) AlignChip(align.map { it.kind }) { e.requestAction(it) }
                    }
                }
            }
        }
    }
}

/** Hülle der gewählten Elemente in Bildschirmkoordinaten der Zeichenfläche (`null` ohne geometrische Auswahl). */
private fun selectionBox(e: SketchEditor): androidx.compose.ui.geometry.Rect? {
    val sk = e.sketch
    val pts = ArrayList<dev.androidcad.sketch.Vec2>()
    for (sel in e.selection) when (sel) {
        is Sel.P -> if (sk.exists(sel.id)) pts += sk.point(sel.id)
        is Sel.L -> if (sk.exists(sel.id)) sk.line(sel.id).let { pts += sk.point(it.p1); pts += sk.point(it.p2) }
        is Sel.C -> if (sk.exists(sel.id)) sk.circle(sel.id).let { c ->
            val m = sk.point(c.center)
            pts += dev.androidcad.sketch.Vec2(m.x - c.radius, m.y - c.radius); pts += dev.androidcad.sketch.Vec2(m.x + c.radius, m.y + c.radius)
        }
        is Sel.A -> if (sk.exists(sel.id)) sk.arc(sel.id).let { a ->
            val m = sk.point(a.center); val r = sk.radiusOf(sel.id)
            pts += dev.androidcad.sketch.Vec2(m.x - r, m.y - r); pts += dev.androidcad.sketch.Vec2(m.x + r, m.y + r)
        }
        is Sel.K -> Unit
    }
    if (pts.isEmpty()) return null
    val screen = pts.map { e.view.toScreen(it) }
    return androidx.compose.ui.geometry.Rect(
        screen.minOf { it.x }.toFloat(), screen.minOf { it.y }.toFloat(), screen.maxOf { it.x }.toFloat(), screen.maxOf { it.y }.toFloat(),
    )
}

/** Aktionen, die die Lage einer Form festlegen – in der Leiste unter „Ausrichten ▾“ zusammengefasst. */
private val ALIGN_ACTIONS = setOf(
    ActionKind.ORIGIN_CORNER, ActionKind.ORIGIN_CENTER, ActionKind.FACE_CENTER, ActionKind.HOLE_CENTER, ActionKind.EDGE_CENTER,
)

/** Chip „Ausrichten ▾“ mit den möglichen Lage-Aktionen als Menü. */
@Composable
private fun AlignChip(kinds: List<ActionKind>, onPick: (ActionKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("⊕ Ausrichten ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            kinds.forEach { k ->
                DropdownMenuItem(text = { Text("${k.symbol}  ${k.label}") }, onClick = { open = false; onPick(k) })
            }
        }
    }
}

private fun hintFor(e: SketchEditor): String = when (e.tool) {
    Tool.SELECT -> "Tippe Elemente an oder ziehe ein Rechteck auf. Ziehen verschiebt – die Bedingungen bleiben erhalten."
    Tool.LINE -> "Linie: Punkte nacheinander antippen (Stift: ziehen). „Fertig“ oder Esc beendet den Linienzug."
    Tool.RECTANGLE -> "Rechteck: zwei gegenüberliegende Ecken antippen oder aufziehen."
    Tool.CIRCLE -> "Kreis: Mittelpunkt, dann einen Punkt auf dem Kreis."
    Tool.ARC -> "Bogen: Mittelpunkt, Startpunkt, dann die Richtung des Endes (gegen den Uhrzeigersinn)."
    Tool.TRIM -> "Trimmen: Tippe auf den Teil einer Linie, eines Kreises oder Bogens, der bis zu den nächsten Schnittpunkten wegfallen soll."
    Tool.EXTEND -> "Verlängern: Tippe auf das Ende einer Linie oder eines Bogens – es reicht dann bis zum nächsten Element."
    Tool.OFFSET ->
        if (e.offsetSource == null) "Versatz: Tippe das Element an (bei Linien samt angrenzender Kette), dann die Seite."
        else "Versatz: Tippe jetzt auf die Seite, auf die der Versatz soll."
    Tool.FILLET -> "Ecke runden: Tippe auf die Ecke – den Punkt, an dem zwei Linien enden."
    Tool.MIRROR -> "Spiegeln: Tippe eine Linie als Achse an oder wähle die X- bzw. Y-Achse."
    Tool.PATTERN_LINEAR -> "Reihe: Tippe eine Linie als Richtung an – auf der Seite, in die die Reihe wachsen soll – oder wähle X bzw. Y."
    Tool.PATTERN_CIRCULAR -> "Kreismuster: Tippe einen Punkt oder Kreis als Mittelpunkt an oder nimm den Ursprung. 360° = voller Kreis."
}

/** Kleines Zahlenfeld für Werkzeugparameter (Versatzabstand, Rundungsradius); ungültige oder nicht positive Eingaben werden ignoriert. */
@Composable
private fun ToolNumberField(label: String, value: Double, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatValue(value)) }
    LaunchedEffect(value) { if (text.replace(',', '.').toDoubleOrNull() != value) text = formatValue(value) }
    val parsed = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    OutlinedTextField(
        value = text, onValueChange = { text = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v.isFinite() && v > 0 }?.let(onValue) },
        singleLine = true, modifier = Modifier.width(150.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = parsed == null, label = { Text(label) },
    )
}

@Composable
private fun ValueDialogs(e: SketchEditor, pending: PendingAction?, edit: ValueEdit?) {
    pending?.let { p ->
        ValueDialog(p.title, p.initial, onConfirm = { e.confirmPendingAction(it) }, onDismiss = { e.cancelPendingAction() })
    }
    edit?.let { v ->
        ValueDialog(v.title, v.initial, onConfirm = { e.confirmValueEdit(it) }, onDismiss = { e.cancelValueEdit() })
    }
}

@Composable
private fun ValueDialog(title: String, initial: Double, onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember(title, initial) { mutableStateOf(formatValue(initial)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = parsed == null, label = { Text("Wert") },
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

