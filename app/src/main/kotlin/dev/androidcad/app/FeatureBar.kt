package dev.androidcad.app

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.androidcad.model.BasePlane
import dev.androidcad.model.BodyOp
import dev.androidcad.model.EdgeKind
import dev.androidcad.model.ExtrudeFeature
import dev.androidcad.model.RevolveAxis
import dev.androidcad.model.RevolveFeature
import dev.androidcad.model.ShellFeature
import dev.androidcad.model.ValueRef
import dev.androidcad.model.FilletFeature
import dev.androidcad.model.HoleFeature
import dev.androidcad.model.MirrorBodyFeature
import dev.androidcad.model.PatternFeature
import dev.androidcad.model.PatternGeometry
import dev.androidcad.model.PlaneFeature
import dev.androidcad.model.PlaneDef
import dev.androidcad.model.PatternKind
import dev.androidcad.model.Vec3
import dev.androidcad.model.Feature
import dev.androidcad.model.PlaneSpec
import dev.androidcad.model.SketchFeature
import dev.androidcad.sketchedit.formatValue

/** Symbol eines Features (wie in der Werkzeugleiste). */
internal fun Feature.icon(): CadIcon = when (this) {
    is SketchFeature -> if (onFace != null) CadIcon.SKETCH_ON_FACE else CadIcon.SKETCH
    is ExtrudeFeature -> when (op) {
        BodyOp.ADD -> CadIcon.EXTRUDE
        BodyOp.CUT -> CadIcon.CUT
        BodyOp.INTERSECT -> CadIcon.INTERSECT
    }
    is RevolveFeature -> CadIcon.REVOLVE
    is ShellFeature -> CadIcon.SHELL
    is FilletFeature -> if (kind == EdgeKind.ROUND) CadIcon.ROUND else CadIcon.CHAMFER
    is HoleFeature -> CadIcon.HOLE
    is PlaneFeature -> CadIcon.PLANE
    is MirrorBodyFeature -> CadIcon.MIRROR
    is PatternFeature -> when (kind) {
        is PatternKind.Linear -> CadIcon.PATTERN_LINEAR
        is PatternKind.Circular -> CadIcon.PATTERN_CIRCULAR
        is PatternKind.Mirror -> CadIcon.MIRROR
    }
}

/** Kurzbeschreibung der Ebene einer Skizze: „auf Fläche“, „XY +8 mm“ oder „frei“. */
@Composable
internal fun planeLabel(s: SketchFeature): String = when {
    s.onFace != null -> stringResource(R.string.feat_plane_on_face)
    s.onPlane != null -> stringResource(R.string.feat_plane_on_plane)
    else -> PlaneSpec.from(s.plane)?.label() ?: stringResource(R.string.feat_plane_free)
}

/**
 * Zeitleiste des Modellaufbaus: alle Schritte waagrecht in Aufbau-Reihenfolge (scrollt). Antippen wählt einen Schritt und hebt ihn
 * hervor; erneutes Antippen bearbeitet ihn (Skizze: zum Zeichnen öffnen, sonst die Einstellungskarte). Langes Drücken oder „⋯“ am
 * gewählten Schritt: Bearbeiten, Umbenennen, Löschen usw. ⚠ = Fehler beim Aufbau.
 */
@Composable
fun Timeline(state: WorkspaceState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val errors = state.output?.errorIds ?: emptySet()
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(14.dp)
    var menuFor by remember { mutableStateOf<dev.androidcad.model.FeatureId?>(null) }
    // neuer Schritt (kommt ans Ende): dorthin scrollen, damit er sichtbar ist
    LaunchedEffect(state.features.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier.glass(shape).height(48.dp).horizontalScroll(scroll).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.features.forEach { f ->
            val selected = f.id == state.selectedId
            val shapeItem = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .height(40.dp)
                    .background(if (selected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, shapeItem)
                    .combinedClickable(
                        onClickLabel = if (selected) stringResource(R.string.common_edit) else stringResource(R.string.feat_tl_click_select), onLongClickLabel = stringResource(R.string.feat_tl_long_click_menu),
                        onClick = {
                            when {
                                !selected -> state.select(f.id, openCard = false)
                                f is SketchFeature -> state.editSketch()
                                else -> state.openCard()
                            }
                        },
                        onLongClick = { state.select(f.id, openCard = false); menuFor = f.id },
                    )
                    .padding(start = 8.dp, end = if (selected) 0.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CadIconView(f.icon(), if (selected) colors.onPrimaryContainer else colors.primary, 18.dp)
                Text(
                    f.name, Modifier.widthIn(max = 140.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
                if (f.id in errors) Text("⚠", color = colors.error)
                if (selected) FeatureMenu(state, f, expanded = menuFor == f.id, onExpand = { menuFor = f.id }, onDismiss = { menuFor = null })
            }
        }
    }
}

/** Menü „⋯“ (oder langes Drücken) eines Schritts der Zeitleiste: Bearbeiten, Ebene ändern, Umbenennen, Löschen. */
@Composable
private fun FeatureMenu(state: WorkspaceState, f: Feature, expanded: Boolean, onExpand: () -> Unit, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var changingPlane by remember { mutableStateOf(false) }
    val menuCd = stringResource(R.string.feat_tl_cd_menu, f.name)
    Box {
        TextButton(onClick = onExpand, modifier = Modifier.semantics { contentDescription = menuCd }) { Text("⋯") }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            if (f is SketchFeature) {
                DropdownMenuItem(text = { Text(stringResource(R.string.feat_edit_sketch)) }, onClick = { onDismiss(); state.editSketch() })
                if (!f.planeIsDerived) DropdownMenuItem(text = { Text(stringResource(R.string.feat_menu_change_plane)) }, onClick = { onDismiss(); changingPlane = true })
            } else DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { onDismiss(); state.openCard() })
            DropdownMenuItem(text = { Text(stringResource(R.string.feat_menu_rename)) }, onClick = { onDismiss(); renaming = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.feat_menu_delete)) }, onClick = { onDismiss(); deleting = true })
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.common_rename)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(stringResource(R.string.common_name)) }) },
            confirmButton = { TextButton(onClick = { renaming = false; state.select(f.id); state.renameSelected(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (deleting) {
        // alles, was mitgeht – auch über mehrere Stufen (Extrusion → Bohrung → Muster); dieselbe Regel wie beim Löschen selbst
        val dependents = remember(f.id) { state.dependentNames(f.id) }
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.feat_delete_title, f.name)) },
            text = {
                val res = LocalResources.current
                Text(
                    if (dependents.isEmpty()) stringResource(R.string.feat_delete_alone)
                    else pluralStringResource(
                        R.plurals.feat_delete_with_dependents, dependents.size,
                        dependents.joinToString(", ") { res.getString(R.string.feat_quoted_name, it) },
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { deleting = false; state.select(f.id); state.deleteSelected() }) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (changingPlane && f is SketchFeature) {
        PlaneDialog(
            title = stringResource(R.string.feat_plane_change_title),
            initial = PlaneSpec.from(f.plane) ?: PlaneSpec(BasePlane.XY),
            onConfirm = { changingPlane = false; state.select(f.id); state.changePlane(it) },
            onDismiss = { changingPlane = false },
        )
    }
}


/**
 * Ebene einer Skizze wählen: Bildkacheln für XY, XZ und YZ (Würfel mit hervorgehobener Ebene, Achsen in den Farben des Achsenkreuzes),
 * dazu der Versatz entlang der Normalen. Mit [onFace] gibt es eine vierte Kachel „Auf Körperfläche“ (schließt den Dialog und startet das Antippen).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaneDialog(title: String, initial: PlaneSpec, onConfirm: (PlaneSpec) -> Unit, onDismiss: () -> Unit, onFace: (() -> Unit)? = null) {
    var base by remember { mutableStateOf(initial.base) }
    var offsetText by remember { mutableStateOf(formatValue(initial.offset)) }
    val offset = offsetText.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasePlane.entries.forEach { b ->
                        val (name, view) = coreLabel(b).split(" · ").let { it[0] to it.getOrElse(1) { "" } }
                        PlaneTile(name, view, base == b, onClick = { base = b }) { PlanePicture(b) }
                    }
                    if (onFace != null) PlaneTile(stringResource(R.string.feat_plane_tile_face), stringResource(R.string.feat_plane_tile_face_sub), false, onClick = onFace) {
                        CadIconView(CadIcon.SKETCH_ON_FACE, MaterialTheme.colorScheme.primary, 40.dp)
                    }
                }
                OutlinedTextField(
                    value = offsetText, onValueChange = { offsetText = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = offset == null, label = { Text(stringResource(R.string.feat_plane_offset)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { offset?.let { onConfirm(PlaneSpec(base, it)) } }, enabled = offset != null) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PlaneTile(name: String, view: String, selected: Boolean, onClick: () -> Unit, picture: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(104.dp)
            .background(if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.4f), shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { picture() }
        Text(name, style = MaterialTheme.typography.titleSmall, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
        Text(view, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/** Isometrischer Würfel mit der Ebene [b] als Fläche; X, Y, Z wie im Achsenkreuz der 3D-Ansicht (Z nach oben). */
@Composable
private fun PlanePicture(b: BasePlane) {
    val cad = CadTheme.colors
    val fill = MaterialTheme.colorScheme.primary
    val frame = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Canvas(Modifier.size(64.dp)) {
        // Würfel (Bildbreite 1,44 u, Höhe 1,70 u) mittig einpassen
        val u = minOf(size.width / 1.6f, size.height / 1.85f)
        val o = Offset(size.width / 2 - 0.72f * u, size.height / 2 + 0.53f * u)
        // Bildrichtungen der Achsen (wie die Standardansicht: X nach rechts unten, Y nach rechts oben, Z nach oben)
        fun p(x: Float, y: Float, z: Float) = Offset(o.x + u * (0.82f * x + 0.62f * y), o.y + u * (0.32f * x - 0.38f * y - 1f * z))
        val corners = listOf(0f, 1f)
        for (a in corners) for (c in corners) {
            drawLine(frame, p(0f, a, c), p(1f, a, c), 1.dp.toPx())
            drawLine(frame, p(a, 0f, c), p(a, 1f, c), 1.dp.toPx())
            drawLine(frame, p(a, c, 0f), p(a, c, 1f), 1.dp.toPx())
        }
        val quad = when (b) {
            BasePlane.XY -> listOf(p(0f, 0f, 0f), p(1f, 0f, 0f), p(1f, 1f, 0f), p(0f, 1f, 0f))
            BasePlane.XZ -> listOf(p(0f, 0f, 0f), p(1f, 0f, 0f), p(1f, 0f, 1f), p(0f, 0f, 1f))
            BasePlane.YZ -> listOf(p(0f, 0f, 0f), p(0f, 1f, 0f), p(0f, 1f, 1f), p(0f, 0f, 1f))
        }
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(quad[0].x, quad[0].y); quad.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(path, fill.copy(alpha = 0.45f))
        drawPath(path, fill, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        val w = 2.dp.toPx()
        drawLine(cad.axisX, p(0f, 0f, 0f), p(1.25f, 0f, 0f), w)
        drawLine(cad.axisY, p(0f, 0f, 0f), p(0f, 1.25f, 0f), w)
        drawLine(cad.axisZ, p(0f, 0f, 0f), p(0f, 0f, 1.25f), w)
    }
}

/**
 * Einstellungen in der Seitenleiste: je nach laufendem Modus (Fläche wählen, Bohrung setzen, Kanten wählen) bzw. gewähltem
 * Feature (Skizze, Extrusion, Verrundung/Fase, Bohrung, Muster). Die Befehle selbst stehen in den Werkzeuggruppen.
 */
@Composable
fun FeatureControls(state: WorkspaceState) {
    val ex = state.extrusion
    val revolve = state.revolve
    val shellMode = state.shellMode
    val measure = state.measureMode
    val shell = state.shell
    val fillet = state.fillet
    val mode = state.edgeMode
    val holeMode = state.holeMode
    val hole = state.hole
    val pattern = state.pattern
    val datum = state.datum
    val mirrorBody = state.mirrorBody
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (measure != null) {
            Text(
                if (measure.picks.isEmpty()) stringResource(R.string.feat_measure_hint_empty) else stringResource(R.string.feat_measure_hint_picks),
                style = MaterialTheme.typography.labelLarge,
            )
            measure.lines.forEach { Text(it.text(), style = MaterialTheme.typography.bodyLarge) }
            OutlinedButton(onClick = state::cancelMeasure, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_done)) }
        } else if (pattern != null && holeMode == null && mode == null && !state.faceSketchPick) {
            PatternControls(state, pattern)
        } else if (mirrorBody != null && holeMode == null && mode == null && !state.faceSketchPick && !state.planePick) {
            MirrorBodyControls(state, mirrorBody)
        } else if (state.planePick) {
            Text(stringResource(R.string.feat_plane_pick_hint), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = state::cancelPlanePick) { Text(stringResource(R.string.common_cancel)) }
        } else if (datum != null && holeMode == null && mode == null && !state.faceSketchPick) {
            DatumControls(state, datum)
        } else if (state.faceSketchPick) {
            Text(stringResource(R.string.feat_face_sketch_hint), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = state::cancelFaceSketchPick) { Text(stringResource(R.string.common_cancel)) }
        } else if (holeMode != null) {
            Text(
                if (holeMode.face == null) stringResource(R.string.feat_hole_hint_pick) else stringResource(R.string.feat_hole_hint_placed),
                style = MaterialTheme.typography.labelLarge,
            )
            AnchorFields(holeMode.anchors, state::setAnchorDistance)
            HoleSizeControls(state, state.holeDiameter, state.holeDepth, state.holeThrough, state::changeHoleDiameter, { state.holeDepth = it }, { state.holeThrough = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = state::cancelHoleMode) { Text(stringResource(R.string.common_cancel)) }
                Button(onClick = state::applyHoleMode, enabled = holeMode.face != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_apply)) }
            }
        } else if (hole != null && mode == null) {
            AnchorFields(hole.anchors, state::updateHoleAnchor)
            HoleSizeControls(
                state, hole.diameter, hole.depth ?: 10.0, hole.depth == null,
                { state.updateHole(it, hole.depth) }, { state.updateHole(hole.diameter, it) }, { state.updateHole(hole.diameter, if (it) null else (hole.depth ?: 10.0)) },
            )
            Text(
                if (hole.anchors != null) stringResource(R.string.feat_hole_anchored) else stringResource(R.string.feat_hole_fixed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (shellMode != null) {
            val n = shellMode.selected.size
            Text(
                if (n == 0) stringResource(R.string.feat_shell_hint_none) else pluralStringResource(R.plurals.feat_shell_selected, n, n),
                style = MaterialTheme.typography.labelLarge,
            )
            NumberField(stringResource(R.string.feat_shell_thickness), state.shellThickness) { if (it > 0) state.shellThickness = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = state::cancelShellMode) { Text(stringResource(R.string.common_cancel)) }
                Button(onClick = state::applyShellMode, enabled = n > 0, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_apply)) }
            }
        } else if (shell != null) {
            val bound = ExprField(state, stringResource(R.string.feat_shell_thickness), shell.thickness, ValueRef.FeatureValue(shell.id)) { if (it > 0) state.updateShell(it) }
            if (!bound) Slider(value = shell.thickness.toFloat().coerceIn(0.5f, 10f), onValueChange = { state.updateShell(Math.round(it * 2) / 2.0) }, valueRange = 0.5f..10f)
            Text(
                pluralStringResource(R.plurals.feat_shell_open_info, shell.openFaces.size, shell.openFaces.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (mode != null) {
            val n = mode.selected.size
            Text(
                if (n == 0) stringResource(R.string.feat_edges_hint_none) else pluralStringResource(R.plurals.feat_edges_selected, n, n),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = mode.kind == EdgeKind.ROUND, onClick = { state.setEdgeKind(EdgeKind.ROUND) }, label = { Text("◜ ${stringResource(R.string.feat_fillet)}") })
                FilterChip(selected = mode.kind == EdgeKind.CHAMFER, onClick = { state.setEdgeKind(EdgeKind.CHAMFER) }, label = { Text("◸ ${stringResource(R.string.feat_chamfer)}") })
            }
            NumberField(if (mode.kind == EdgeKind.ROUND) stringResource(R.string.feat_fillet_radius) else stringResource(R.string.feat_chamfer_distance), state.edgeSize) { if (it > 0) state.edgeSize = it }
            Slider(value = state.edgeSize.toFloat().coerceIn(0.5f, 20f), onValueChange = { state.edgeSize = Math.round(it * 2) / 2.0 }, valueRange = 0.5f..20f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = state::cancelEdgeMode) { Text(stringResource(R.string.common_cancel)) }
                Button(onClick = state::applyEdgeMode, enabled = n > 0, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_apply)) }
            }
        } else if (fillet != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = fillet.kind == EdgeKind.ROUND, onClick = { state.updateFillet(fillet.size, EdgeKind.ROUND) }, label = { Text("◜ ${stringResource(R.string.feat_fillet)}") })
                FilterChip(selected = fillet.kind == EdgeKind.CHAMFER, onClick = { state.updateFillet(fillet.size, EdgeKind.CHAMFER) }, label = { Text("◸ ${stringResource(R.string.feat_chamfer)}") })
            }
            val bound = ExprField(state, if (fillet.kind == EdgeKind.ROUND) stringResource(R.string.feat_fillet_radius) else stringResource(R.string.feat_chamfer_distance), fillet.size, ValueRef.FeatureValue(fillet.id)) { if (it > 0) state.updateFillet(it, fillet.kind) }
            if (!bound) Slider(value = fillet.size.toFloat().coerceIn(0.5f, 20f), onValueChange = { state.updateFillet(Math.round(it * 2) / 2.0, fillet.kind) }, valueRange = 0.5f..20f)
            Text(pluralStringResource(R.plurals.feat_fillet_edges_info, fillet.edges.size, fillet.edges.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (revolve != null) {
            RevolveControls(state, revolve)
        } else if (ex == null) {
            val n = state.regionCount
            Text(stringResource(R.string.feat_sketch_plane, planeLabel(state.sketch)), style = MaterialTheme.typography.labelLarge)
            Text(
                when {
                    n == 0 -> stringResource(R.string.feat_sketch_no_profile)
                    else -> pluralStringResource(R.plurals.feat_sketch_profiles, n, n)
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = state::editSketch, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feat_edit_sketch)) }
        } else {
            val reversed = ex.distance < 0
            val length = kotlin.math.abs(ex.distance)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = ex.op == BodyOp.ADD, onClick = { state.updateExtrusion(ex.copy(op = BodyOp.ADD)) }, label = { Text("▲ ${stringResource(R.string.feat_op_add)}") })
                FilterChip(selected = ex.op == BodyOp.CUT, onClick = { state.updateExtrusion(ex.copy(op = BodyOp.CUT)) }, label = { Text("▼ ${stringResource(R.string.feat_op_cut)}") }, enabled = ex.op == BodyOp.CUT || state.canCut)
                FilterChip(selected = ex.op == BodyOp.INTERSECT, onClick = { state.updateExtrusion(ex.copy(op = BodyOp.INTERSECT)) }, label = { Text("∩ ${stringResource(R.string.feat_op_intersect)}") }, enabled = ex.op == BodyOp.INTERSECT || state.canCut)
            }

            Text(stringResource(R.string.feat_extrude_end_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val isThroughAll = ex.throughAll
            val isToFace = ex.untilFace != null || state.extrudeFacePick
            val isLength = !isThroughAll && !isToFace
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isLength,
                    onClick = {
                        state.cancelExtrudeFacePick()
                        state.updateExtrusion(ex.copy(throughAll = false, untilFace = null))
                    },
                    label = { Text(stringResource(R.string.feat_extrude_end_length)) },
                )
                FilterChip(
                    selected = isThroughAll,
                    onClick = {
                        state.cancelExtrudeFacePick()
                        state.updateExtrusion(ex.copy(throughAll = true, untilFace = null, distance2 = null))
                    },
                    label = { Text(stringResource(R.string.feat_extrude_through_all)) },
                    enabled = state.canCut,
                )
                FilterChip(
                    selected = isToFace,
                    onClick = {
                        if (!isToFace) {
                            state.startExtrudeFacePick()
                        }
                    },
                    label = { Text(stringResource(R.string.feat_extrude_end_to_face)) },
                    enabled = state.canCut,
                )
            }

            if (isThroughAll) {
                SwitchRow(stringResource(R.string.feat_symmetric), ex.symmetric) { state.updateExtrusion(ex.copy(symmetric = it)) }
                SwitchRow(stringResource(R.string.feat_reversed), reversed) { state.updateExtrusion(ex.copy(distance = if (it) -length else length)) }
            } else if (isToFace) {
                if (state.extrudeFacePick) {
                    Text(stringResource(R.string.feat_extrude_pick_face_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = state::cancelExtrudeFacePick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
                } else if (ex.untilFace != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.feat_extrude_target_face_selected), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = state::startExtrudeFacePick) { Text(stringResource(R.string.feat_extrude_change_face)) }
                    }
                } else {
                    Button(onClick = state::startExtrudeFacePick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feat_extrude_pick_face)) }
                }
            } else {
                val bound = ExprField(state, stringResource(R.string.feat_extrude_length), length, ValueRef.FeatureValue(ex.id)) { v -> if (v > 0) state.updateExtrusion(ex.copy(distance = if (reversed) -v else v)) }
                if (!bound) Slider(value = length.toFloat().coerceIn(1f, 100f), onValueChange = { v ->
                    val d = Math.round(v).toDouble().coerceAtLeast(1.0)
                    state.updateExtrusion(ex.copy(distance = if (reversed) -d else d))
                }, valueRange = 1f..100f)
                SwitchRow(stringResource(R.string.feat_symmetric), ex.symmetric) {
                    state.updateExtrusion(ex.copy(symmetric = it, distance2 = if (it) null else ex.distance2))
                }
                SwitchRow(stringResource(R.string.feat_reversed), reversed) { state.updateExtrusion(ex.copy(distance = if (it) -length else length)) }
            }

            if (!isThroughAll) {
                val hasSecondSide = ex.distance2 != null
                SwitchRow(stringResource(R.string.feat_extrude_second_side), hasSecondSide) {
                    state.updateExtrusion(ex.copy(distance2 = if (it) (ex.distance2 ?: 10.0) else null, symmetric = if (it) false else ex.symmetric))
                }
                if (hasSecondSide) {
                    val d2 = ex.distance2 ?: 10.0
                    val bound2 = ExprField(state, stringResource(R.string.feat_extrude_length2), d2, ValueRef.FeatureValue(ex.id, 1)) { v ->
                        if (v > 0) state.updateExtrusion(ex.copy(distance2 = v))
                    }
                    if (!bound2) Slider(value = d2.toFloat().coerceIn(1f, 100f), onValueChange = { v ->
                        val d = Math.round(v).toDouble().coerceAtLeast(1.0)
                        state.updateExtrusion(ex.copy(distance2 = d))
                    }, valueRange = 1f..100f)
                }
            }

            OutlinedButton(onClick = state::editSketch, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feat_edit_sketch)) }
        }
    }
}

/** Einstellungen der gewählten Drehung: Hinzufügen/Abtragen, Drehachse, Winkel, symmetrisch, Richtung. */
@Composable
private fun RevolveControls(state: WorkspaceState, f: RevolveFeature) {
    val reversed = f.angle < 0
    val angle = kotlin.math.abs(f.angle)
    fun withAngle(a: Double) = f.copy(angle = if (reversed) -a else a)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = f.op == BodyOp.ADD, onClick = { state.updateRevolve(f.copy(op = BodyOp.ADD)) }, label = { Text("▲ ${stringResource(R.string.feat_op_add)}") })
        FilterChip(selected = f.op == BodyOp.CUT, onClick = { state.updateRevolve(f.copy(op = BodyOp.CUT)) }, label = { Text("▼ ${stringResource(R.string.feat_op_cut)}") }, enabled = f.op == BodyOp.CUT || state.canCut)
        FilterChip(selected = f.op == BodyOp.INTERSECT, onClick = { state.updateRevolve(f.copy(op = BodyOp.INTERSECT)) }, label = { Text("∩ ${stringResource(R.string.feat_op_intersect)}") }, enabled = f.op == BodyOp.INTERSECT || state.canCut)
    }
    Text(stringResource(R.string.feat_revolve_axis_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lines = state.revolveAxisOptions(f.sketch)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = f.axis == RevolveAxis.SketchY, onClick = { state.updateRevolve(f.copy(axis = RevolveAxis.SketchY)) }, label = { Text(stringResource(R.string.feat_revolve_axis_vertical)) })
        FilterChip(selected = f.axis == RevolveAxis.SketchX, onClick = { state.updateRevolve(f.copy(axis = RevolveAxis.SketchX)) }, label = { Text(stringResource(R.string.feat_revolve_axis_horizontal)) })
        lines.forEachIndexed { i, a ->
            FilterChip(selected = f.axis == a, onClick = { state.updateRevolve(f.copy(axis = a)) }, label = { Text(stringResource(R.string.feat_revolve_axis_construction, i + 1)) })
        }
        // Achse ist eine Linie, die keine Hilfslinie (mehr) ist – trotzdem als gewählt anzeigen
        if (f.axis is RevolveAxis.Line && f.axis !in lines) FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.feat_revolve_axis_line)) })
    }
    val bound = ExprField(state, stringResource(R.string.feat_revolve_angle), angle, ValueRef.FeatureValue(f.id)) { v -> if (v > 0 && v <= 360) state.updateRevolve(withAngle(v)) }
    if (!bound) Slider(value = angle.toFloat().coerceIn(5f, 360f), onValueChange = { v -> state.updateRevolve(withAngle((Math.round(v / 5) * 5.0).coerceAtLeast(5.0))) }, valueRange = 5f..360f)
    if (angle < 360) {
        SwitchRow(stringResource(R.string.feat_symmetric), f.symmetric) { state.updateRevolve(f.copy(symmetric = it)) }
        SwitchRow(stringResource(R.string.feat_reversed), reversed) { state.updateRevolve(f.copy(angle = if (it) -angle else angle)) }
    }
    Text(
        stringResource(R.string.feat_revolve_hint),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = state::editSketch, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feat_edit_sketch)) }
}

/** Einstellungen der gewählten Arbeitsebene: Art (Abstand / gedreht an Kante), Wert, Drehachse, „Skizze auf dieser Ebene“. */
@Composable
private fun DatumControls(state: WorkspaceState, p: PlaneFeature) {
    val def = p.def
    val edges = state.planeEdgeOptions(def.face)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = def is PlaneDef.Offset, onClick = {
            state.updateDatum(PlaneDef.Offset(def.face, (def as? PlaneDef.Offset)?.distance ?: 10.0))
        }, label = { Text(stringResource(R.string.feat_datum_offset)) })
        FilterChip(selected = def is PlaneDef.AtEdge, onClick = {
            if (def !is PlaneDef.AtEdge) {
                if (edges.isEmpty()) state.notice = UiText.Res(R.string.feat_datum_no_edge)
                else state.updateDatum(PlaneDef.AtEdge(def.face, edges.first(), 30.0))
            }
        }, label = { Text(stringResource(R.string.feat_datum_at_edge)) })
    }
    when (def) {
        is PlaneDef.Offset -> NumberField(stringResource(R.string.feat_datum_distance), def.distance) { state.updateDatum(def.copy(distance = it)) }
        is PlaneDef.AtEdge -> {
            NumberField(stringResource(R.string.feat_datum_angle), def.angle) { state.updateDatum(def.copy(angle = it)) }
            val i = edges.indexOf(def.edge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (i >= 0) stringResource(R.string.feat_datum_axis_edge, i + 1, edges.size) else stringResource(R.string.feat_datum_axis_missing),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { state.updateDatum(def.copy(edge = edges[(i + 1).mod(edges.size)])) }, enabled = edges.size > 1) { Text(stringResource(R.string.feat_datum_next)) }
            }
        }
    }
    Button(onClick = state::createSketchOnDatum, modifier = Modifier.fillMaxWidth()) {
        CadIconView(CadIcon.SKETCH, MaterialTheme.colorScheme.onPrimary, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.feat_datum_sketch))
    }
    Text(
        stringResource(R.string.feat_datum_follows),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

private val AXIS_NAMES = listOf("X", "Y", "Z")
private fun axisVector(i: Int) = Vec3(if (i == 0) 1.0 else 0.0, if (i == 1) 1.0 else 0.0, if (i == 2) 1.0 else 0.0)
private fun Vec3.dominantAxis(): Int = listOf(kotlin.math.abs(x), kotlin.math.abs(y), kotlin.math.abs(z)).let { a -> a.indices.maxByOrNull { a[it] } ?: 0 }
private fun Vec3.component(i: Int) = when (i) { 0 -> x; 1 -> y; else -> z }
private fun Vec3.with(i: Int, v: Double) = Vec3(if (i == 0) v else x, if (i == 1) v else y, if (i == 2) v else z)

/** Einstellungen des gewählten Musters bzw. der Spiegelung: Achse/Ebene, Anzahl, Abstand/Winkel. */
@Composable
private fun PatternControls(state: WorkspaceState, p: PatternFeature) {
    Text(stringResource(R.string.feat_pattern_source, state.patternSourceName(p)), style = MaterialTheme.typography.labelLarge)
    when (val k = p.kind) {
        is PatternKind.Linear -> {
            val axis1 = k.direction.dominantAxis()
            val axis2 = k.direction2?.dominantAxis()
            AxisChips(stringResource(R.string.feat_pattern_direction), axis1, disabled = axis2) { state.updatePattern(k.copy(direction = axisVector(it))) }
            CountSlider(k.count, 2..20) { state.updatePattern(k.copy(count = it)) }
            NumberField(stringResource(R.string.feat_pattern_spacing), k.spacing) { state.updatePattern(k.copy(spacing = it)) }
            if (state.canTwoWay(p)) {
                SwitchRow(stringResource(R.string.feat_pattern_second_direction), k.direction2 != null) { on ->
                    state.updatePattern(if (on) k.copy(direction2 = axisVector(if (axis1 == 0) 1 else 0), spacing2 = k.spacing) else k.copy(direction2 = null))
                }
            }
            if (axis2 != null) {
                AxisChips(stringResource(R.string.feat_pattern_direction2), axis2, disabled = axis1) { state.updatePattern(k.copy(direction2 = axisVector(it))) }
                CountSlider(k.count2, 2..20) { state.updatePattern(k.copy(count2 = it)) }
                NumberField(stringResource(R.string.feat_pattern_spacing2), k.spacing2) { state.updatePattern(k.copy(spacing2 = it)) }
                if (k.count * k.count2 > PatternGeometry.MAX_COUNT) {
                    Text(stringResource(R.string.feat_pattern_max, PatternGeometry.MAX_COUNT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        is PatternKind.Circular -> {
            AxisChips(stringResource(R.string.feat_pattern_rotation_axis), k.axis.dominantAxis()) { state.updatePattern(k.copy(axis = axisVector(it))) }
            CountSlider(k.count, 2..24) { state.updatePattern(k.copy(count = it)) }
            Text(stringResource(R.string.feat_pattern_axis_point), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..2) NumberField(stringResource(R.string.feat_coord_mm, AXIS_NAMES[i]), k.origin.component(i), Modifier.weight(1f)) { state.updatePattern(k.copy(origin = k.origin.with(i, it))) }
            }
            NumberField(stringResource(R.string.feat_pattern_circular_angle), k.angle) { state.updatePattern(k.copy(angle = it)) }
        }
        is PatternKind.Mirror -> {
            val axis = k.normal.dominantAxis()
            AxisChips(stringResource(R.string.feat_mirror_plane_normal), axis) { state.updatePattern(k.copy(normal = axisVector(it))) }
            NumberField(stringResource(R.string.feat_mirror_plane_position, AXIS_NAMES[axis]), k.origin.component(axis)) { state.updatePattern(k.copy(origin = k.origin.with(axis, it))) }
            OutlinedButton(onClick = state::mirrorWholeBodyInstead, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feat_pattern_mirror_body_instead)) }
        }
    }
    val rounds = state.patternFilletCount(p)
    if (rounds > 0 || p.withFillets) {
        SwitchRow(
            pluralStringResource(R.plurals.feat_pattern_with_fillets, rounds, rounds),
            p.withFillets,
        ) { state.setPatternWithFillets(it) }
    }
    Text(
        when {
            state.canPattern -> stringResource(R.string.feat_pattern_note_nestable)
            PatternGeometry.isTwoWay(p.kind) -> stringResource(R.string.feat_pattern_note_grid)
            else -> stringResource(R.string.feat_pattern_note_nested)
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Einstellungen einer Körperspiegelung: Arbeitsebene oder Koordinatenebene mit Lage. */
@Composable
private fun MirrorBodyControls(state: WorkspaceState, f: MirrorBodyFeature) {
    Text(stringResource(R.string.feat_mirror_body_hint), style = MaterialTheme.typography.labelLarge)
    val datums = state.mirrorDatumOptions(f)
    if (datums.isNotEmpty()) {
        Text(stringResource(R.string.feat_mirror_plane), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = f.datum == null, onClick = { state.updateMirrorBody(f.copy(datum = null)) }, label = { Text(stringResource(R.string.feat_mirror_coordinate_plane)) })
            datums.forEach { d -> FilterChip(selected = f.datum == d.id, onClick = { state.updateMirrorBody(f.copy(datum = d.id)) }, label = { Text(d.name) }) }
        }
    }
    if (f.datum == null) {
        val axis = f.normal.dominantAxis()
        AxisChips(stringResource(R.string.feat_mirror_plane_normal), axis) { state.updateMirrorBody(f.copy(normal = axisVector(it))) }
        NumberField(stringResource(R.string.feat_mirror_plane_position, AXIS_NAMES[axis]), f.origin.component(axis)) { state.updateMirrorBody(f.copy(origin = f.origin.with(axis, it))) }
    } else {
        Text(stringResource(R.string.feat_mirror_follows), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        stringResource(R.string.feat_mirror_note),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AxisChips(title: String, selected: Int, disabled: Int? = null, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        AXIS_NAMES.forEachIndexed { i, name -> FilterChip(selected = selected == i, onClick = { onSelect(i) }, enabled = i != disabled, label = { Text(name) }) }
    }
}

@Composable
private fun CountSlider(count: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.feat_count), Modifier.weight(1f))
        Text("$count", color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = count.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
        onValueChange = { onChange(Math.round(it)) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
    )
}

/** Zahlenfeld, das eine laufende Eingabe nicht stört, aber Änderungen von außen übernimmt; ungültige Eingaben werden rot markiert und ignoriert. */
@Composable
private fun NumberField(label: String, value: Double, modifier: Modifier = Modifier.fillMaxWidth(), onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatValue(value)) }
    LaunchedEffect(value) { if (text.replace(',', '.').toDoubleOrNull() != value) text = formatValue(value) }
    val parsed = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    OutlinedTextField(
        value = text, onValueChange = { text = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v.isFinite() }?.let(onValue) },
        singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = parsed == null, label = { Text(label) },
    )
}

/**
 * Wie [NumberField], nimmt aber auch Ausdrücke der Parameter-Tabelle an (z. B. `wand * 2`): eine Zahl macht den Wert fest, ein
 * Ausdruck bindet ihn an [ref]. Liefert, ob der Wert gerade aus einem Ausdruck kommt (dann ist ein Schieberegler fehl am Platz).
 */
@Composable
private fun ExprField(state: WorkspaceState, label: String, value: Double, ref: ValueRef, onValue: (Double) -> Unit): Boolean {
    val expr = state.bindings[ref]
    var text by remember(ref) { mutableStateOf(expr ?: formatValue(value)) }
    // Wert von außen geändert (Schieberegler, Neuaufbau)? Anzeige angleichen – außer bei einem Ausdruck, der bleibt stehen
    LaunchedEffect(value, expr) { if (expr == null && text.replace(',', '.').toDoubleOrNull() != value) text = formatValue(value) }
    val number = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    val evaluated = number ?: state.evaluate(text)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val n = it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v.isFinite() }
            when {
                n != null -> { state.setBinding(ref, null); onValue(n) }
                state.evaluate(it) != null -> state.setBinding(ref, it)
            }
        },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = evaluated == null, label = { Text(label) },
        supportingText = if (number == null && evaluated != null) ({ Text(stringResource(R.string.feat_expr_follows, formatValue(evaluated))) }) else null,
    )
    return expr != null
}

/**
 * Parameter als eigenes Fenster mit Tabelle (Name | Ausdruck | Wert): genug Platz für lange Namen und Ausdrücke. Spätere Parameter dürfen
 * frühere verwenden; Modell bzw. Skizze dahinter rechnen bei jeder Änderung mit. Auf dem Smartphone fast bildschirmfüllend.
 */
@Composable
fun ParameterDialog(state: WorkspaceState, onDismiss: () -> Unit) {
    val list = state.parameters
    val colors = MaterialTheme.colorScheme
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 760.dp).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val closeCd = stringResource(R.string.common_close)
                    Text(stringResource(R.string.common_parameters), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = closeCd }) { Text("✕") }
                }
                // Kopfzeile statt Beschriftung in jedem Feld: spart Höhe, die Spalten bleiben ausgerichtet
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.common_name), Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(stringResource(R.string.feat_param_col_value), Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.width(PARAM_DELETE_WIDTH))
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (list.isEmpty()) Text(
                        stringResource(R.string.feat_param_empty),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    )
                    list.forEachIndexed { i, p ->
                        androidx.compose.runtime.key(i) {
                            ParameterRow(state, p, state.parameterValues[p.name], onChange = { q -> state.updateParameters(list.toMutableList().also { it[i] = q }) }) {
                                state.updateParameters(list.filterIndexed { k, _ -> k != i })
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        var n = list.size + 1
                        while (list.any { it.name == "p$n" }) n++
                        state.updateParameters(list + dev.androidcad.model.Parameter("p$n", "10"))
                    }) { Text(stringResource(R.string.feat_param_add)) }
                    val used = state.bindings.size
                    Text(
                        if (used > 0) pluralStringResource(R.plurals.feat_params_following, used, used) else "", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

private val PARAM_DELETE_WIDTH = 40.dp

@Composable
private fun ParameterRow(state: WorkspaceState, p: dev.androidcad.model.Parameter, value: Double?, onChange: (dev.androidcad.model.Parameter) -> Unit, onDelete: () -> Unit) {
    // Der Name wird erst übernommen, wenn er gültig ist; das Feld behält dazwischen seinen eigenen Stand (Zwischenstände wie „“).
    // Von außen geändert (Zeile darüber gelöscht): angleichen, aber nie mitten in eine ungültige Eingabe hinein.
    var name by remember { mutableStateOf(p.name) }
    var expr by remember { mutableStateOf(p.expression) }
    LaunchedEffect(p.name) { if (p.name != name && dev.androidcad.model.Parameter.nameProblem(name) == null) name = p.name }
    LaunchedEffect(p.expression) { if (p.expression != expr) expr = p.expression }
    val nameProblem = dev.androidcad.model.Parameter.nameProblem(name)?.text() ?: if (state.parameters.any { o -> o !== p && o.name == name }) stringResource(R.string.feat_param_name_taken, name) else null
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = name, onValueChange = { name = it; if (dev.androidcad.model.Parameter.nameProblem(it) == null && state.parameters.none { o -> o !== p && o.name == it }) onChange(p.copy(name = it)) },
                singleLine = true, modifier = Modifier.weight(1.2f), isError = nameProblem != null,
            )
            // Formeln dürfen umbrechen; ihr Ergebnis steht darunter – bei einer einfachen Zahl wäre es nur dieselbe Zahl noch einmal
            Column(Modifier.weight(1.6f)) {
                OutlinedTextField(
                    value = expr, onValueChange = { expr = it; onChange(p.copy(expression = it)) },
                    maxLines = 3, modifier = Modifier.fillMaxWidth(), isError = value == null,
                )
                if (value != null && expr.trim().replace(',', '.').toDoubleOrNull() == null) Text(
                    "= ${formatValue(value)}", Modifier.padding(start = 4.dp, top = 2.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val deleteCd = stringResource(R.string.common_delete)
            TextButton(onClick = onDelete, modifier = Modifier.width(PARAM_DELETE_WIDTH).padding(top = 4.dp).semantics { contentDescription = deleteCd }, contentPadding = PaddingValues(0.dp)) { Text("✕") }
        }
        val problem = nameProblem ?: if (value == null) stringResource(R.string.feat_param_expr_invalid) else null
        if (problem != null) Text(problem, Modifier.padding(start = 4.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** Abstände einer Bohrung zu ihren beiden Referenzkanten (mm); nichts, wenn die Position ein fester Punkt ist. */
@Composable
private fun AnchorFields(anchors: Pair<dev.androidcad.model.EdgeDistance, dev.androidcad.model.EdgeDistance>?, onChange: (Int, Double) -> Unit) {
    if (anchors == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(anchors.first, anchors.second).forEachIndexed { i, a ->
            val value = kotlin.math.abs(a.distance)
            var text by remember { mutableStateOf(formatValue(value)) }
            // Wert von außen geändert (z. B. Neuaufbau)? Dann Anzeige angleichen, ohne die laufende Eingabe zu stören
            LaunchedEffect(value) { if (text.replace(',', '.').toDoubleOrNull() != value) text = formatValue(value) }
            val parsed = text.replace(',', '.').toDoubleOrNull()
            OutlinedTextField(
                value = text, onValueChange = { text = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v >= 0 }?.let { v -> onChange(i, v) } },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = parsed == null || parsed < 0, label = { Text(stringResource(R.string.feat_anchor_edge, i + 1)) },
            )
        }
    }
}

/** Durchmesser, Tiefe und „Durchgehend“ einer Bohrung (für den Bohrungsmodus und das gewählte Bohrungs-Feature). */
@Composable
private fun HoleSizeControls(
    state: WorkspaceState, diameter: Double, depth: Double, through: Boolean,
    onDiameter: (Double) -> Unit, onDepth: (Double) -> Unit, onThrough: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Ø ${stringResource(R.string.feat_hole_diameter)}", Modifier.weight(1f))
        Text(stringResource(R.string.feat_value_mm, formatValue(diameter)), color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = diameter.toFloat().coerceIn(1f, 40f), onValueChange = { onDiameter(Math.round(it * 2) / 2.0) }, valueRange = 1f..40f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.feat_hole_through)); Switch(through, onThrough)
        Box(Modifier.weight(1f))
        if (!through) Text(stringResource(R.string.feat_hole_depth_value, formatValue(depth)), color = MaterialTheme.colorScheme.primary)
    }
    if (!through) Slider(value = depth.toFloat().coerceIn(1f, 100f), onValueChange = { onDepth(Math.round(it * 2) / 2.0) }, valueRange = 1f..100f)
}
