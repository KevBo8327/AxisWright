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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxScope
import dev.androidcad.sketchedit.ActionKind
import dev.androidcad.sketchedit.PendingTransform
import dev.androidcad.sketchedit.TransformMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.runtime.CompositionLocalProvider
import dev.androidcad.sketchedit.RectMode
import dev.androidcad.sketchedit.CircleMode
import dev.androidcad.sketchedit.ArcMode
import dev.androidcad.sketchedit.CornerMode
import dev.androidcad.sketchedit.LineMode
import dev.androidcad.sketchedit.PolygonMode
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
            Text(n.text(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { state.notice = null }) { Text(stringResource(R.string.common_ok)) }
        }
    }
}

// ---- Skizze -----------------------------------------------------------------------------------------

/**
 * Skizzenmodus: Die Zeichenfläche füllt den ganzen Bildschirm; alles andere erscheint nur, wenn es gebraucht wird.
 *
 * - **Kopfzeile**: Skizze, Rückgängig/Wiederholen/Löschen, „Fertig“; Ebene, Kanten projizieren, Raster und Einpassen stehen breit
 *   als Knöpfe, schmal im ⋮-Menü.
 * - **Werkzeuge** in Gruppen (Bemaßen, Zeichnen, Bearbeiten, Muster) – breit links, schmal unten (wie im Modellmodus). Ohne aktives
 *   Werkzeug wird ausgewählt: Antippen fügt hinzu, erneut antippen nimmt heraus, ins Leere tippen hebt die Auswahl auf.
 * - **Status** als kleine Plakette oben im Bild, Meldungen des Editors als Band darüber.
 * - **Kontextleiste** ([ContextBar]): Bedingungen und Maße zur Auswahl schweben an der Auswahl; Hinweise und Felder eines
 *   Werkzeugs unten im Bild – nur solange sie gebraucht werden.
 */
@Composable
private fun SketchPanel(state: WorkspaceState, modifier: Modifier) {
    val handle = state.handle
    var planeDialog by remember { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    // Glas-Flächen über der Zeichenfläche bekommen echte Unschärfe: die Zeichenfläche ist ihre Quelle
    val glassSource = rememberHazeState()
    CompositionLocalProvider(LocalGlassSource provides glassSource) {
    BoxWithConstraints(modifier) {
        // Kopfzeile kompakt nur auf Smartphones; Werkzeuge unten auch auf Tablets im Hochformat (dort ist unten mehr Platz)
        val narrow = maxWidth < SKETCH_COMPACT_WIDTH
        val compact = maxWidth < BOTTOM_TOOLS_WIDTH || maxWidth < maxHeight || maxHeight < SKETCH_TOOLS_HEIGHT
        val density = LocalDensity.current
        val topGap = with(density) { 12.dp.toPx().toDouble() }
        handle.editor.uiScale = density.density.toDouble()
        handle.insetLeft = if (compact) 0.0 else with(density) { 80.dp.toPx().toDouble() }
        val e = handle.editor
        handle.version // Beobachtung
        val groups = sketchToolGroups(e, LocalResources.current)
        Column(Modifier.fillMaxSize()) {
            SketchTopBar(state, narrow, onPlaneDialog = { planeDialog = true })
            HorizontalDivider()
            NoticeRow(state)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SketchCanvas(handle, Modifier.fillMaxSize().hazeSource(glassSource), reference = state.referenceLines)
                // Plakette und Meldung gemessen: „Einpassen“ hält genau ihre Höhe frei (die Meldung kann mehrzeilig sein).
                // Direkt im Messrückruf statt über Zustand, damit schon das erste Einpassen die Höhe kennt
                Box(Modifier.align(Alignment.TopStart).fillMaxWidth().onSizeChanged { handle.insetTop = it.height + topGap }) {
                    StatusPill(handle, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                    // Meldungen des Editors oben im Zeichenfeld – wie die Fehlerzeile im Modellmodus
                    MessageBand(handle, Modifier.align(Alignment.TopStart).fillMaxWidth())
                }
                ContextBar(handle, state.prefs, bottomGap = if (compact) 12.dp else 16.dp)
                DismissLayer(openGroup != null) { openGroup = null }
                if (!compact) GroupBar(groups, vertical = true, openGroup, { openGroup = it }, Modifier.align(Alignment.TopStart).padding(8.dp).padding(top = 40.dp))
            }
            if (compact) {
                HorizontalDivider()
                GroupBar(groups, vertical = false, openGroup, { openGroup = it }, Modifier.fillMaxWidth())
            }
        }
        if (compact) GroupFlyout(groups, openGroup, { openGroup = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp))
    }
    }
    handle.version // Beobachtung: bei jeder Änderung des Editors neu zusammensetzen
    ValueDialogs(state, handle.editor, handle.editor.pendingAction, handle.editor.valueEdit)
    TextEditDialog(handle.editor, handle.editor.textEdit)
    TransformDialog(handle.editor, handle.editor.pendingTransform)
    if (planeDialog || state.askPlane) {
        PlaneDialog(
            title = stringResource(if (state.askPlane) R.string.ws_plane_title_start else R.string.ws_plane_title_change),
            initial = PlaneSpec.from(state.sketch.plane) ?: PlaneSpec(BasePlane.XY),
            onConfirm = { planeDialog = false; state.askPlane = false; state.changePlane(it) },
            onDismiss = { planeDialog = false; state.askPlane = false },
        )
    }
}

/** Unter dieser Breite gilt das Gerät als schmal (Smartphone): Kopfzeile kompakt. */
private val SKETCH_COMPACT_WIDTH = 600.dp

/** Niedriger (Smartphone quer) passt die senkrechte Leiste der Skizze nicht: Kopfzeile + sechs Werkzeuggruppen à 56 dp. */
private val SKETCH_TOOLS_HEIGHT = 460.dp

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
    val doneText = stringResource(R.string.common_done)
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CadIconView(CadIcon.SKETCH, colors.primary, 22.dp)
            Text(state.sketch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) {
                Text(planeLabel(state.sketch), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                if (!state.sketch.planeIsDerived) TextButton(onClick = onPlaneDialog) { Text(stringResource(R.string.ws_top_plane_button)) }
                else if (state.sketch.onFace != null) TextButton(onClick = state::projectFaceEdges) { Text(stringResource(R.string.ws_top_project_edges)) }
            }
        }
        // Parameter schon beim Skizzieren anlegen und in Maßen verwenden
        if (!compact) TextButton(onClick = { if (Pro.allows()) state.parametersOpen = true }) { Text(stringResource(R.string.common_parameters)); ProMark() }
        if (state.parametersOpen) ParameterDialog(state) { state.parametersOpen = false }
        ActionButton(CadIcon.UNDO, stringResource(R.string.ws_top_undo), enabled = e.canUndo) { e.undo() }
        ActionButton(CadIcon.REDO, stringResource(R.string.ws_top_redo), enabled = e.canRedo) { e.redo() }
        ActionButton(CadIcon.DELETE, stringResource(R.string.ws_top_delete_selection), enabled = e.selection.isNotEmpty()) { e.deleteSelection() }
        if (!compact) {
            ActionButton(CadIcon.GRID, stringResource(R.string.ws_top_grid_snap), selected = e.gridSnap) { e.gridSnap = !e.gridSnap }
            ActionButton(CadIcon.FIT, stringResource(R.string.ws_top_fit_all)) { fit() }
        } else {
            val moreOptions = stringResource(R.string.common_more_options)
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = moreOptions }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ws_top_plane_current, planeLabel(state.sketch))) }, onClick = { menu = false }, enabled = false)
                    if (!state.sketch.planeIsDerived) DropdownMenuItem(text = { Text(stringResource(R.string.ws_top_change_plane)) }, onClick = { menu = false; onPlaneDialog() })
                    else if (state.sketch.onFace != null) DropdownMenuItem(text = { Text(stringResource(R.string.ws_top_project_edges)) }, onClick = { menu = false; state.projectFaceEdges() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ws_top_parameters_menu)) }, trailingIcon = { ProMark() }, onClick = { menu = false; if (Pro.allows()) state.parametersOpen = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ws_top_grid_snap) + if (e.gridSnap) " ✓" else "") }, onClick = { menu = false; e.gridSnap = !e.gridSnap })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ws_top_fit_all)) }, onClick = { menu = false; fit() })
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        // kompakt nur das Häkchen: der Name kommt dann aus der Beschreibung (Screenreader)
        Button(onClick = state::finishSketch, modifier = if (compact) Modifier.semantics { contentDescription = doneText } else Modifier) {
            CadIconView(CadIcon.DONE, colors.onPrimary, 18.dp)
            if (!compact) { Spacer(Modifier.width(6.dp)); Text(doneText) }
        }
    }
}

/**
 * Werkzeuge der Skizze in Gruppen: **Bemaßen** als direkter Knopf, **Zeichnen** (Linie, Rechteck, Kreis, Bogen),
 * **Bearbeiten** (Trimmen, Verlängern, Versatz, Ecke, Spiegeln) und **Muster** (Reihe, Kreis) zum Aufklappen.
 */
private fun sketchToolGroups(e: SketchEditor, res: android.content.res.Resources): List<ToolGroup> {
    // das aktive Werkzeug erneut antippen beendet es (zurück zum Auswählen)
    fun tool(label: Int, icon: CadIcon, t: Tool) = ToolDef(res.getString(label), icon, e.tool == t, true) { e.setTool(if (e.tool == t) Tool.SELECT else t) }
    val drawIcon = when (e.tool) {
        Tool.RECTANGLE -> CadIcon.RECTANGLE
        Tool.CIRCLE -> CadIcon.CIRCLE
        Tool.ARC -> CadIcon.ARC
        Tool.SLOT -> CadIcon.SLOT
        Tool.POLYGON -> CadIcon.POLYGON
        Tool.POINT -> CadIcon.POINT
        Tool.TEXT -> CadIcon.TEXT
        else -> CadIcon.LINE
    }
    return listOf(
        ToolGroup(res.getString(R.string.ws_group_dimension), CadIcon.DIMENSION, listOf(tool(R.string.ws_group_dimension, CadIcon.DIMENSION, Tool.DIMENSION)), direct = true),
        ToolGroup(res.getString(R.string.ws_group_draw), drawIcon, listOf(
            tool(R.string.ws_tool_line, CadIcon.LINE, Tool.LINE), tool(R.string.ws_tool_rectangle, CadIcon.RECTANGLE, Tool.RECTANGLE),
            tool(R.string.ws_tool_circle, CadIcon.CIRCLE, Tool.CIRCLE), tool(R.string.ws_tool_arc, CadIcon.ARC, Tool.ARC),
            tool(R.string.ws_tool_slot, CadIcon.SLOT, Tool.SLOT), tool(R.string.ws_tool_polygon, CadIcon.POLYGON, Tool.POLYGON),
            tool(R.string.ws_tool_point, CadIcon.POINT, Tool.POINT), tool(R.string.ws_tool_text, CadIcon.TEXT, Tool.TEXT),
        )),
        ToolGroup(res.getString(R.string.ws_group_edit), CadIcon.TRIM, listOf(
            tool(R.string.ws_tool_trim, CadIcon.TRIM, Tool.TRIM), tool(R.string.ws_tool_split, CadIcon.SPLIT, Tool.SPLIT), tool(R.string.ws_tool_extend, CadIcon.EXTEND, Tool.EXTEND),
            tool(R.string.ws_tool_offset, CadIcon.OFFSET, Tool.OFFSET), tool(R.string.ws_tool_corner, CadIcon.FILLET, Tool.FILLET),
            ToolDef(res.getString(R.string.ws_tool_mirror), CadIcon.MIRROR, e.tool == Tool.MIRROR, e.selection.isNotEmpty() || e.mirrorPending) {
                if (e.mirrorPending) e.cancelOperation() else e.startMirror()
            },
            ToolDef(res.getString(R.string.ws_tool_transform), CadIcon.TRANSFORM, e.tool == Tool.TRANSFORM, e.selection.isNotEmpty() || e.transformPending) {
                if (e.transformPending) e.cancelOperation() else e.startTransform()
            },
        )),
        ToolGroup(res.getString(R.string.ws_group_pattern), CadIcon.PATTERN_LINEAR, listOf(
            ToolDef(res.getString(R.string.ws_tool_pattern_linear), CadIcon.PATTERN_LINEAR, e.tool == Tool.PATTERN_LINEAR, e.selection.isNotEmpty() || e.tool == Tool.PATTERN_LINEAR) {
                if (e.tool == Tool.PATTERN_LINEAR) e.cancelOperation() else e.startPattern(circular = false)
            },
            ToolDef(res.getString(R.string.ws_tool_pattern_circular), CadIcon.PATTERN_CIRCULAR, e.tool == Tool.PATTERN_CIRCULAR, e.selection.isNotEmpty() || e.tool == Tool.PATTERN_CIRCULAR) {
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
    val text = e.statusText()?.text() ?: return
    val conflict = e.status?.isSolved == false
    val shape = RoundedCornerShape(50)
    Text(
        text, (if (conflict) modifier.background(colors.errorContainer.copy(alpha = 0.92f), shape).border(0.5.dp, colors.outlineVariant, shape)
        else modifier.glass(shape)).padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = if (conflict) colors.onErrorContainer else colors.primary, maxLines = 1,
    )
}

/** Meldung des Editors (Fehler rot, Erfolg neutral) als Band oben im Zeichenfeld. */
@Composable
private fun MessageBand(handle: EditorHandle, modifier: Modifier) {
    val e = handle.editor
    handle.version // Beobachtung
    val colors = MaterialTheme.colorScheme
    val m = e.message?.text() ?: return
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
internal fun ToolButton(
    label: String, icon: CadIcon, selected: Boolean, enabled: Boolean = true, size: androidx.compose.ui.unit.Dp = 60.dp,
    /** Nur umrandet (z. B. aufgeklappte Gruppe) – gefüllt ist allein das aktive Werkzeug. */
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .size(size)
            .background(if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.4f), shape)
            .then(if (selected || outlined) Modifier.border(2.dp, colors.primary, shape) else Modifier)
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
private fun BoxScope.ContextBar(handle: EditorHandle, prefs: UiPrefs, bottomGap: androidx.compose.ui.unit.Dp) {
    val e = handle.editor
    handle.version // Beobachtung
    val colors = MaterialTheme.colorScheme
    val endToolText = stringResource(R.string.ws_ctx_cd_end_tool)
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
        val place = if (box != null) contextBarPlace(
            box, size.width.toFloat(), size.height.toFloat(), w, h, margin, gap, handle.insetTop.toFloat(), with(density) { 160.dp.toPx() },
        ) else Rect(Offset(((w - size.width) / 2f).coerceAtLeast(margin), (h - size.height - with(density) { bottomGap.toPx() }).coerceAtLeast(margin)), androidx.compose.ui.geometry.Size(w - 2 * margin, 0f))
        val x = place.left; val y = place.top
        Column(
            Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.onSizeChanged { size = it }
                // Tipps auf Hinweis oder Hintergrund der Leiste dürfen nicht auf der Zeichenfläche landen (sonst entstünden dort Punkte):
                // eine eigene Eingabe genügt, Compose gibt die Berührung dann nicht an die Zeichenfläche darunter weiter. Nicht verbrauchen –
                // sonst bricht das seitliche Scrollen der Chips ab (es prüft, ob die Bewegung schon verbraucht ist)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .widthIn(max = with(density) { place.width.toDp() })
                .glass(shape)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hintFor(e), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2)
                // Werkzeug beenden (zurück zum Auswählen); bricht auch ein wartendes Spiegeln/Muster ab
                TextButton(onClick = { e.cancelOperation(); e.setTool(Tool.SELECT) }, modifier = Modifier.semantics { contentDescription = endToolText }) { Text("✕") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    e.tool.isModify -> when (e.tool) {
                        Tool.OFFSET -> ToolNumberField(stringResource(R.string.ws_ctx_field_distance), e.offsetDistance) { e.offsetDistance = it }
                        Tool.FILLET -> {
                            DrawVariants(handle, prefs)
                            if (e.cornerMode == CornerMode.CHAMFER) ToolNumberField(stringResource(R.string.ws_ctx_field_distance), e.chamferDistance) { e.chamferDistance = it }
                            else ToolNumberField(stringResource(R.string.ws_ctx_field_radius), e.filletRadius) { e.filletRadius = it }
                        }
                        Tool.MIRROR -> {
                            OutlinedButton(onClick = { e.mirrorAcrossAxis(horizontal = true) }) { Text(stringResource(R.string.ws_ctx_mirror_x)) }
                            OutlinedButton(onClick = { e.mirrorAcrossAxis(horizontal = false) }) { Text(stringResource(R.string.ws_ctx_mirror_y)) }
                            OutlinedButton(onClick = { e.cancelOperation() }) { Text(stringResource(R.string.common_cancel)) }
                        }
                        Tool.TRANSFORM -> {
                            OutlinedButton(onClick = { e.transformPivot(e.sketch.point(e.sketch.origin)) }) { Text(stringResource(R.string.ws_ctx_transform_origin)) }
                            OutlinedButton(onClick = { e.cancelOperation() }) { Text(stringResource(R.string.common_cancel)) }
                        }
                        Tool.PATTERN_LINEAR -> {
                            ToolNumberField(stringResource(R.string.ws_ctx_field_count), e.patternCount.toDouble()) { e.patternCount = Math.round(it).toInt() }
                            ToolNumberField(stringResource(R.string.ws_ctx_field_spacing), e.patternSpacing) { e.patternSpacing = it }
                            OutlinedButton(onClick = { e.patternAlongAxis(horizontal = true) }) { Text(stringResource(R.string.ws_ctx_pattern_along_x)) }
                            OutlinedButton(onClick = { e.patternAlongAxis(horizontal = false) }) { Text(stringResource(R.string.ws_ctx_pattern_along_y)) }
                            OutlinedButton(onClick = { e.cancelOperation() }) { Text(stringResource(R.string.common_cancel)) }
                        }
                        Tool.PATTERN_CIRCULAR -> {
                            ToolNumberField(stringResource(R.string.ws_ctx_field_count), e.patternCount.toDouble()) { e.patternCount = Math.round(it).toInt() }
                            ToolNumberField(stringResource(R.string.ws_ctx_field_angle), e.patternAngle) { if (it <= 360.0) e.patternAngle = it }
                            OutlinedButton(onClick = { e.patternAroundOrigin() }) { Text(stringResource(R.string.ws_ctx_pattern_around_origin)) }
                            OutlinedButton(onClick = { e.cancelOperation() }) { Text(stringResource(R.string.common_cancel)) }
                        }
                        else -> Unit
                    }
                    !selecting -> {
                        DrawVariants(handle, prefs)
                        if (e.drawingInProgress) OutlinedButton(onClick = {
                            e.cancelOperation()
                            if (e.drawingChain) e.setTool(Tool.SELECT) // Zug beendet = Linie fertig
                        }) { Text(stringResource(if (e.drawingChain) R.string.ws_ctx_finish_chain else R.string.common_cancel)) }
                    }
                    e.selection.singleOrNull() is Sel.T -> {
                        val textId = (e.selection.single() as Sel.T).id
                        AssistChip(
                            onClick = { e.requestTextEdit(textId) },
                            label = { Text(stringResource(R.string.ws_ctx_edit_text)) },
                        )
                        options.filter { it.kind.group == ActionGroup.OTHER }.forEach { opt ->
                            AssistChip(
                                onClick = { e.requestAction(opt.kind) },
                                label = { Text("${opt.kind.symbol} ${coreLabel(opt.kind)}") },
                            )
                        }
                    }
                    options.isEmpty() -> Text(
                        stringResource(R.string.ws_ctx_no_constraints),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    else -> {
                        for (group in listOf(ActionGroup.GEOMETRY, ActionGroup.DIMENSION, ActionGroup.OTHER)) {
                            options.filter { it.kind.group == group && it.kind !in ALIGN_ACTIONS }.forEach { opt ->
                                AssistChip(
                                    onClick = { e.requestAction(opt.kind) },
                                    label = { Text("${opt.kind.symbol} ${coreLabel(opt.kind)}" + (opt.defaultValue?.let { "  ${formatValue(it)}" } ?: "")) },
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

/**
 * Platz der Kontextleiste ([barW]×[barH], gemessen) zur Auswahl [box] in der Zeichenfläche [w]×[h]: links oben die Lage, die
 * Breite ist die höchstens erlaubte. Bevorzugt unter der Auswahl (die Form bleibt sichtbar, die Leiste ist nah am Daumen), sonst
 * darüber (unterhalb von [top], dem Streifen der Plakette), sonst daneben auf der Seite mit mehr Platz – dort schmaler bis
 * [minSide], die Chips scrollen dann –, sonst am unteren Rand.
 */
internal fun contextBarPlace(box: Rect, barW: Float, barH: Float, w: Float, h: Float, margin: Float, gap: Float, top: Float, minSide: Float): Rect {
    val full = w - 2 * margin
    fun at(x: Float, y: Float, maxW: Float = full) = Rect(x, y, x + maxW, y + barH)
    val centeredX = (box.center.x - barW / 2f).coerceIn(margin, (w - barW - margin).coerceAtLeast(margin))
    val below = box.bottom + gap
    val above = box.top - barH - gap
    if (below + barH <= h - margin) return at(centeredX, below)
    if (above >= top) return at(centeredX, above)
    val leftSide = box.left > w - box.right
    val space = if (leftSide) box.left - gap - margin else w - margin - box.right - gap
    if (space >= minOf(barW, minSide) && barH <= h - top - margin) {
        val sideW = minOf(barW, space)
        val y = (box.center.y - barH / 2f).coerceIn(top, h - barH - margin)
        return at(if (leftSide) box.left - gap - sideW else box.right + gap, y, space)
    }
    return at(centeredX, (h - barH - margin).coerceAtLeast(margin))
}

/**
 * Hülle der gewählten Elemente samt ihrer Maße und Bedingungssymbole in Bildschirmkoordinaten der Zeichenfläche
 * (`null` ohne Auswahl) – die Leiste soll auch diese nicht verdecken.
 */
private fun selectionBox(e: SketchEditor): Rect? {
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
        is Sel.T -> if (sk.exists(sel.id)) sk.text(sel.id).let { t ->
            val m = sk.point(t.location)
            val rad = Math.toRadians(t.angle)
            val w = maxOf(t.height * 0.5, t.text.length * t.height * 0.6)
            val h = t.height
            val cosA = kotlin.math.cos(rad); val sinA = kotlin.math.sin(rad)
            fun corner(lx: Double, ly: Double) = dev.androidcad.sketch.Vec2(m.x + lx * cosA - ly * sinA, m.y + lx * sinA + ly * cosA)
            pts += corner(0.0, -h * 0.25)
            pts += corner(w, -h * 0.25)
            pts += corner(w, h)
            pts += corner(0.0, h)
        }
        is Sel.K -> Unit
    }
    val screen = pts.mapTo(ArrayList()) { e.view.toScreen(it) }
    // Maße und Symbole an genau den Stellen, an denen die Zeichenfläche sie zeichnet; dazu gehört, was ein gewähltes Element
    // referenziert oder selbst gewählt ist
    val ids = e.selection.mapNotNullTo(HashSet<Any>()) { when (it) { is Sel.P -> it.id; is Sel.L -> it.id; is Sel.C -> it.id; is Sel.A -> it.id; is Sel.T -> it.id; is Sel.K -> null } }
    fun belongs(c: dev.androidcad.sketch.ConstraintId) = Sel.K(c) in e.selection || sk.constraint(c).refs.any { it in ids }
    for (l in e.dimensionLabels()) if (belongs(l.id)) {
        l.lines.forEach { (a, b) -> screen += a; screen += b }
        screen += dev.androidcad.sketch.Vec2(l.center.x - l.halfWidth, l.center.y - l.halfHeight)
        screen += dev.androidcad.sketch.Vec2(l.center.x + l.halfWidth, l.center.y + l.halfHeight)
    }
    // Symbole: Kreis mit 9 px Radius (siehe SketchCanvas)
    for (b in e.badges()) if (belongs(b.id)) {
        screen += dev.androidcad.sketch.Vec2(b.center.x - 9, b.center.y - 9); screen += dev.androidcad.sketch.Vec2(b.center.x + 9, b.center.y + 9)
    }
    if (screen.isEmpty()) return null
    return Rect(screen.minOf { it.x }.toFloat(), screen.minOf { it.y }.toFloat(), screen.maxOf { it.x }.toFloat(), screen.maxOf { it.y }.toFloat())
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
        AssistChip(onClick = { open = true }, label = { Text(stringResource(R.string.ws_ctx_align)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            kinds.forEach { k ->
                DropdownMenuItem(text = { Text("${k.symbol}  ${coreLabel(k)}") }, onClick = { open = false; onPick(k) })
            }
        }
    }
}

@Composable
private fun hintFor(e: SketchEditor): String = when (e.tool) {
    Tool.SELECT -> stringResource(R.string.ws_hint_select)
    Tool.LINE -> when (e.lineMode) {
        LineMode.NORMAL -> stringResource(R.string.ws_hint_line)
        LineMode.TANGENT -> stringResource(R.string.ws_hint_line_tangent)
    }
    Tool.RECTANGLE -> when (e.rectMode) {
        RectMode.CORNERS -> stringResource(R.string.ws_hint_rect_corners)
        RectMode.CENTER -> stringResource(if (e.drawStep == 0) R.string.ws_hint_rect_center_first else R.string.ws_hint_rect_center_corner)
        RectMode.THREE_POINTS -> stringResource(listOf(R.string.ws_hint_rect_three_start, R.string.ws_hint_rect_three_end, R.string.ws_hint_rect_three_height)[e.drawStep])
    }
    Tool.CIRCLE -> when (e.circleMode) {
        CircleMode.CENTER -> stringResource(R.string.ws_hint_circle_center)
        CircleMode.TWO_POINTS -> stringResource(R.string.ws_hint_circle_two_points)
        CircleMode.THREE_POINTS -> stringResource(R.string.ws_hint_circle_three_points, e.drawStep)
        CircleMode.TANGENT2 -> if (e.drawStep < 2) stringResource(R.string.ws_hint_circle_tangent2_pick, e.drawStep)
        else stringResource(R.string.ws_hint_circle_tangent2_place)
        CircleMode.TANGENT3 -> stringResource(R.string.ws_hint_circle_tangent3, e.drawStep)
    }
    Tool.SLOT -> stringResource(listOf(R.string.ws_hint_slot_first, R.string.ws_hint_slot_second, R.string.ws_hint_slot_width)[e.drawStep])
    Tool.POLYGON -> when (e.polygonMode) {
        PolygonMode.INSCRIBED -> stringResource(if (e.drawStep == 0) R.string.ws_hint_polygon_center else R.string.ws_hint_polygon_inscribed_corner)
        PolygonMode.CIRCUMSCRIBED -> stringResource(if (e.drawStep == 0) R.string.ws_hint_polygon_center else R.string.ws_hint_polygon_circumscribed_side)
    }
    Tool.ARC -> when (e.arcMode) {
        ArcMode.CENTER -> stringResource(R.string.ws_hint_arc_center)
        ArcMode.THREE_POINTS -> stringResource(listOf(R.string.ws_hint_arc_three_start, R.string.ws_hint_arc_three_end, R.string.ws_hint_arc_three_through)[e.drawStep])
        ArcMode.TANGENT -> stringResource(if (e.drawStep == 0) R.string.ws_hint_arc_tangent_start else R.string.ws_hint_arc_tangent_end)
    }
    Tool.POINT -> stringResource(R.string.ws_hint_point)
    Tool.TEXT -> stringResource(R.string.ws_hint_text)
    Tool.TRIM -> stringResource(R.string.ws_hint_trim)
    Tool.SPLIT -> stringResource(R.string.ws_hint_split)
    Tool.EXTEND -> stringResource(R.string.ws_hint_extend)
    Tool.OFFSET -> stringResource(if (e.offsetSource == null) R.string.ws_hint_offset_element else R.string.ws_hint_offset_side)
    Tool.DIMENSION -> stringResource(R.string.ws_hint_dimension)
    Tool.FILLET -> stringResource(if (e.cornerMode == CornerMode.CHAMFER) R.string.ws_hint_corner_chamfer else R.string.ws_hint_corner_fillet)
    Tool.MIRROR -> stringResource(R.string.ws_hint_mirror)
    Tool.PATTERN_LINEAR -> stringResource(R.string.ws_hint_pattern_linear)
    Tool.PATTERN_CIRCULAR -> stringResource(R.string.ws_hint_pattern_circular)
    Tool.TRANSFORM -> stringResource(R.string.ws_hint_transform)
}

/** Auswahl der Variante bei Linie, Rechteck, Kreis, Bogen und Ecke; die Wahl bleibt über einen Neustart erhalten ([applyDrawModes]). */
@Composable
private fun DrawVariants(handle: EditorHandle, prefs: UiPrefs) {
    val e = handle.editor
    handle.version // Beobachtung: der Editor bleibt dasselbe Objekt, nur die gewählte Variante ändert sich
    @Composable
    fun <T : Enum<T>> chips(modes: List<T>, current: T, onPick: (T) -> Unit) = modes.forEach { m ->
        FilterChip(selected = m == current, onClick = { onPick(m) }, label = { Text(coreLabel(m)) })
    }
    when (e.tool) {
        Tool.LINE -> chips(LineMode.entries, e.lineMode) { e.lineMode = it; prefs.putText(PREF_LINE, it.name) }
        Tool.RECTANGLE -> chips(RectMode.entries, e.rectMode) { e.rectMode = it; prefs.putText(PREF_RECT, it.name) }
        Tool.CIRCLE -> chips(CircleMode.entries, e.circleMode) { e.circleMode = it; prefs.putText(PREF_CIRCLE, it.name) }
        Tool.ARC -> chips(ArcMode.entries, e.arcMode) { e.arcMode = it; prefs.putText(PREF_ARC, it.name) }
        Tool.FILLET -> chips(CornerMode.entries, e.cornerMode) { e.cornerMode = it; prefs.putText(PREF_CORNER, it.name) }
        Tool.POLYGON -> {
            ToolNumberField(stringResource(R.string.ws_ctx_field_sides), e.polygonSides.toDouble()) { e.polygonSides = Math.round(it).toInt(); prefs.putText(PREF_POLYGON_SIDES, e.polygonSides.toString()) }
            chips(PolygonMode.entries, e.polygonMode) { e.polygonMode = it; prefs.putText(PREF_POLYGON, it.name) }
        }
        else -> Unit
    }
}

private const val PREF_LINE = "sketch.line"
private const val PREF_POLYGON = "sketch.polygon"
private const val PREF_POLYGON_SIDES = "sketch.polygon.sides"
private const val PREF_RECT = "sketch.rect"
private const val PREF_CIRCLE = "sketch.circle"
private const val PREF_ARC = "sketch.arc"
private const val PREF_CORNER = "sketch.corner"

/** Übernimmt die zuletzt gewählten Zeichenvarianten in einen (neuen) Editor. */
internal fun applyDrawModes(e: SketchEditor, prefs: UiPrefs) {
    prefs.text(PREF_POLYGON)?.let { v -> PolygonMode.entries.firstOrNull { it.name == v } }?.let { e.polygonMode = it }
    prefs.text(PREF_POLYGON_SIDES)?.toIntOrNull()?.let { e.polygonSides = it }
    prefs.text(PREF_LINE)?.let { v -> LineMode.entries.firstOrNull { it.name == v } }?.let { e.lineMode = it }
    prefs.text(PREF_RECT)?.let { v -> RectMode.entries.firstOrNull { it.name == v } }?.let { e.rectMode = it }
    prefs.text(PREF_CIRCLE)?.let { v -> CircleMode.entries.firstOrNull { it.name == v } }?.let { e.circleMode = it }
    prefs.text(PREF_ARC)?.let { v -> ArcMode.entries.firstOrNull { it.name == v } }?.let { e.arcMode = it }
    prefs.text(PREF_CORNER)?.let { v -> CornerMode.entries.firstOrNull { it.name == v } }?.let { e.cornerMode = it }
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
private fun ValueDialogs(state: WorkspaceState, e: SketchEditor, pending: PendingAction?, edit: ValueEdit?) {
    pending?.let { p ->
        // neues Maß mit Ausdruck: nach dem Anlegen an den Parameter binden (das neue Maß ist die jüngste Bedingung)
        ValueDialog(state, p.title.text(), p.initial, null, onConfirm = { v, expr ->
            val before = e.sketch.constraintEntries.map { it.id }.toSet()
            if (e.confirmPendingAction(v) && expr != null && p.kind.group == dev.androidcad.sketchedit.ActionGroup.DIMENSION) {
                e.sketch.constraintEntries.lastOrNull { it.id !in before }?.let { state.setBinding(dev.androidcad.model.ValueRef.Dim(state.sketch.id, it.id), expr) }
            }
        }, onDismiss = { e.cancelPendingAction() })
    }
    edit?.let { v ->
        // ein Maß darf aus einem Ausdruck der Parameter-Tabelle kommen; eine Zahl macht es wieder fest
        val ref = dev.androidcad.model.ValueRef.Dim(state.sketch.id, v.constraint)
        ValueDialog(state, v.title.text(), v.initial, state.bindings[ref], onConfirm = { value, expr ->
            if (e.confirmValueEdit(value)) state.setBinding(ref, expr)
        }, onDismiss = { e.cancelValueEdit() })
    }
}

@Composable
private fun TextEditDialog(e: SketchEditor, state: dev.androidcad.sketchedit.TextDialogState?) {
    if (state == null) return
    var text by remember(state) { mutableStateOf(state.initialText) }
    var heightStr by remember(state) { mutableStateOf(if (state.initialHeight > 0) formatValue(state.initialHeight) else "10") }
    var angleStr by remember(state) { mutableStateOf(formatValue(state.initialAngle)) }

    val heightVal = heightStr.replace(',', '.').toDoubleOrNull()
    val angleVal = angleStr.replace(',', '.').toDoubleOrNull()
    val valid = text.isNotBlank() && heightVal != null && heightVal > 0 && angleVal != null

    val isNew = state.id == null
    val title = stringResource(if (isNew) R.string.ws_text_dialog_title_new else R.string.ws_text_dialog_title_edit)

    AlertDialog(
        onDismissRequest = { e.cancelText() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ws_text_dialog_label_text)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = heightStr,
                    onValueChange = { heightStr = it },
                    singleLine = true,
                    isError = heightVal == null || heightVal <= 0,
                    label = { Text(stringResource(R.string.ws_text_dialog_label_height)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = angleStr,
                    onValueChange = { angleStr = it },
                    singleLine = true,
                    isError = angleVal == null,
                    label = { Text(stringResource(R.string.ws_text_dialog_label_rotation)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (heightVal != null && angleVal != null && valid) e.confirmText(text.trim(), heightVal, angleVal)
                },
                enabled = valid,
            ) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { e.cancelText() }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Zahl oder Ausdruck eingeben: eine Zahl (auch mit Komma) gilt fest; alles andere wird als Ausdruck mit den Parametern ausgewertet
 * und [onConfirm] bekommt ihn mit (sonst `null`). Ohne [expression] (Vorgabe für neue Maße) nur der Wert.
 */
@Composable
private fun ValueDialog(state: WorkspaceState, title: String, initial: Double, expression: String?, onConfirm: (Double, String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember(title, initial) { mutableStateOf(expression ?: formatValue(initial)) }
    val number = text.replace(',', '.').toDoubleOrNull()
    val value = number ?: state.evaluate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                isError = value == null, label = { Text(stringResource(R.string.ws_value_label)) },
                supportingText = {
                    Text(
                        when {
                            value == null -> stringResource(R.string.ws_value_invalid)
                            number == null -> stringResource(R.string.ws_value_follows, formatValue(value))
                            state.parameters.isNotEmpty() -> stringResource(R.string.ws_value_example, state.parameters.first().name)
                            else -> ""
                        },
                    )
                },
            )
        },
        confirmButton = { TextButton(onClick = { value?.let { onConfirm(it, if (number == null) text else null) } }, enabled = value != null) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun TransformDialog(e: SketchEditor, pending: PendingTransform?) {
    if (pending == null) return
    var mode by remember { mutableStateOf(TransformMode.TRANSLATE) }
    var dxText by remember { mutableStateOf("0") }
    var dyText by remember { mutableStateOf("0") }
    var angleText by remember { mutableStateOf("90") }
    var scaleText by remember { mutableStateOf("2") }
    var asCopy by remember { mutableStateOf(false) }

    val dx = dxText.replace(',', '.').toDoubleOrNull()
    val dy = dyText.replace(',', '.').toDoubleOrNull()
    val angle = angleText.replace(',', '.').toDoubleOrNull()
    val scale = scaleText.replace(',', '.').toDoubleOrNull()

    val valid = when (mode) {
        TransformMode.TRANSLATE -> dx != null && dy != null
        TransformMode.ROTATE -> angle != null
        TransformMode.SCALE -> scale != null && scale > 0.0
    }

    AlertDialog(
        onDismissRequest = { e.cancelPendingTransform() },
        title = { Text(stringResource(R.string.ws_transform_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == TransformMode.TRANSLATE,
                        onClick = { mode = TransformMode.TRANSLATE },
                        label = { Text(stringResource(R.string.ws_transform_mode_translate)) },
                    )
                    FilterChip(
                        selected = mode == TransformMode.ROTATE,
                        onClick = { mode = TransformMode.ROTATE },
                        label = { Text(stringResource(R.string.ws_transform_mode_rotate)) },
                    )
                    FilterChip(
                        selected = mode == TransformMode.SCALE,
                        onClick = { mode = TransformMode.SCALE },
                        label = { Text(stringResource(R.string.ws_transform_mode_scale)) },
                    )
                }

                when (mode) {
                    TransformMode.TRANSLATE -> {
                        OutlinedTextField(
                            value = dxText,
                            onValueChange = { dxText = it },
                            label = { Text(stringResource(R.string.ws_transform_dx)) },
                            singleLine = true,
                            isError = dx == null,
                        )
                        OutlinedTextField(
                            value = dyText,
                            onValueChange = { dyText = it },
                            label = { Text(stringResource(R.string.ws_transform_dy)) },
                            singleLine = true,
                            isError = dy == null,
                        )
                    }
                    TransformMode.ROTATE -> {
                        OutlinedTextField(
                            value = angleText,
                            onValueChange = { angleText = it },
                            label = { Text(stringResource(R.string.ws_transform_angle)) },
                            singleLine = true,
                            isError = angle == null,
                        )
                    }
                    TransformMode.SCALE -> {
                        OutlinedTextField(
                            value = scaleText,
                            onValueChange = { scaleText = it },
                            label = { Text(stringResource(R.string.ws_transform_scale)) },
                            singleLine = true,
                            isError = scale == null || scale <= 0.0,
                        )
                    }
                }

                FilterChip(
                    selected = asCopy,
                    onClick = { asCopy = !asCopy },
                    label = { Text(stringResource(R.string.ws_transform_copy)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    e.confirmTransform(
                        mode = mode,
                        dx = dx ?: 0.0,
                        dy = dy ?: 0.0,
                        angleDegrees = angle ?: 0.0,
                        scale = scale ?: 1.0,
                        asCopy = asCopy,
                    )
                },
                enabled = valid,
            ) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { e.cancelPendingTransform() }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

