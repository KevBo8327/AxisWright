package dev.androidcad.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import dev.androidcad.model.BasePlane
import dev.androidcad.model.BodyOp
import dev.androidcad.model.EdgeKind
import dev.androidcad.model.ExtrudeFeature
import dev.androidcad.model.RevolveAxis
import dev.androidcad.model.RevolveFeature
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
    is ExtrudeFeature -> if (op == BodyOp.ADD) CadIcon.EXTRUDE else CadIcon.CUT
    is RevolveFeature -> CadIcon.REVOLVE
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
internal fun planeLabel(s: SketchFeature): String = when {
    s.onFace != null -> "auf Fläche"
    s.onPlane != null -> "auf Ebene"
    else -> PlaneSpec.from(s.plane)?.label ?: "frei"
}

internal fun Feature.label(): String = when (this) {
    is SketchFeature -> "$name · ${planeLabel(this)}"
    is ExtrudeFeature -> "$name · ${formatValue(kotlin.math.abs(distance))} mm"
    is RevolveFeature -> "$name · ${formatValue(kotlin.math.abs(angle))}°"
    is FilletFeature -> "$name · ${formatValue(size)} mm"
    is HoleFeature -> "$name · Ø${formatValue(diameter)}"
    is PlaneFeature -> when (val d = def) {
        is PlaneDef.Offset -> "$name · ${formatValue(d.distance)} mm"
        is PlaneDef.AtEdge -> "$name · ${formatValue(d.angle)}° an Kante"
    }
    is PatternFeature -> when (val k = kind) {
        is PatternKind.Linear ->
            if (k.direction2 != null) "$name · ${k.count} × ${k.count2} · ${formatValue(k.spacing)}/${formatValue(k.spacing2)} mm"
            else "$name · ${k.count}× ${formatValue(k.spacing)} mm"
        is PatternKind.Circular -> "$name · ${k.count}× ${formatValue(k.angle)}°"
        is PatternKind.Mirror -> "$name · gespiegelt"
    }
    is MirrorBodyFeature ->
        if (datum != null) "$name · an Arbeitsebene"
        else normal.dominantAxis().let { a -> "$name · ${AXIS_NAMES[a]} = ${formatValue(origin.component(a))} mm" }
}

/**
 * Feature-Baum: die Modellhistorie senkrecht in Aufbau-Reihenfolge. Antippen wählt ein Feature (eine gewählte Skizze erneut
 * antippen öffnet sie zum Zeichnen); „⋯“ am Eintrag bearbeitet es. Features mit Fehler beim letzten Aufbau tragen ein ⚠.
 */
@Composable
fun FeatureList(state: WorkspaceState) {
    val errors = state.output?.errorIds ?: emptySet()
    Column(Modifier.fillMaxWidth()) {
        state.features.forEach { f -> FeatureRow(state, f, f.id in errors) }
    }
}

@Composable
private fun FeatureRow(state: WorkspaceState, f: Feature, hasError: Boolean) {
    val colors = MaterialTheme.colorScheme
    val isSelected = f.id == state.selectedId
    val isActiveSketch = f is SketchFeature && f.id == state.sketch.id
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { if (isSelected && f is SketchFeature) state.editSketch() else state.select(f.id) }
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp)) { CadIconView(f.icon(), if (isSelected) colors.onPrimaryContainer else colors.primary, 20.dp) }
        Text(
            f.label(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
            fontWeight = if (isActiveSketch) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (hasError) Text("⚠", Modifier.padding(horizontal = 4.dp), color = colors.error)
        FeatureMenu(state, f)
    }
}

/** Menü „⋯“ eines Eintrags im Feature-Baum: Skizze bearbeiten, Ebene ändern, Umbenennen, Löschen. */
@Composable
private fun FeatureMenu(state: WorkspaceState, f: Feature) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var changingPlane by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { state.select(f.id); menu = true }) { Text("⋯") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (f is SketchFeature) {
                DropdownMenuItem(text = { Text("Skizze bearbeiten") }, onClick = { menu = false; state.editSketch() })
                if (!f.planeIsDerived) DropdownMenuItem(text = { Text("Ebene ändern …") }, onClick = { menu = false; changingPlane = true })
            }
            DropdownMenuItem(text = { Text("Umbenennen …") }, onClick = { menu = false; renaming = true })
            DropdownMenuItem(text = { Text("Löschen …") }, onClick = { menu = false; deleting = true })
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Umbenennen") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { renaming = false; state.select(f.id); state.renameSelected(text) }, enabled = text.isNotBlank()) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Abbrechen") } },
        )
    }
    if (deleting) {
        val dependents = if (f is SketchFeature) state.features.count { (it is ExtrudeFeature && it.sketch == f.id) || (it is RevolveFeature && it.sketch == f.id) } else 0
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("„${f.name}“ löschen?") },
            text = { Text(if (dependents > 0) "Die Skizze und die $dependents Körper-Features, die auf ihr aufbauen, werden entfernt." else "Das Feature und alles, was darauf aufbaut, wird entfernt.") },
            confirmButton = { TextButton(onClick = { deleting = false; state.select(f.id); state.deleteSelected() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Abbrechen") } },
        )
    }
    if (changingPlane && f is SketchFeature) {
        PlaneDialog(
            title = "Ebene der Skizze ändern",
            initial = PlaneSpec.from(f.plane) ?: PlaneSpec(BasePlane.XY),
            onConfirm = { changingPlane = false; state.select(f.id); state.changePlane(it) },
            onDismiss = { changingPlane = false },
        )
    }
}


@Composable
internal fun PlaneDialog(title: String, initial: PlaneSpec, onConfirm: (PlaneSpec) -> Unit, onDismiss: () -> Unit) {
    var base by remember { mutableStateOf(initial.base) }
    var offsetText by remember { mutableStateOf(formatValue(initial.offset)) }
    val offset = offsetText.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasePlane.entries.forEach { b ->
                    FilterChip(selected = base == b, onClick = { base = b }, label = { Text(b.title) })
                }
                OutlinedTextField(
                    value = offsetText, onValueChange = { offsetText = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = offset == null, label = { Text("Versatz entlang der Normalen (mm)") },
                )
                Text("Tipp: Für eine Bohrung von oben die Skizze auf die Oberseite legen (Versatz = Höhe) und „Abtragen“ wählen.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { offset?.let { onConfirm(PlaneSpec(base, it)) } }, enabled = offset != null) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * Einstellungen in der Seitenleiste: je nach laufendem Modus (Fläche wählen, Bohrung setzen, Kanten wählen) bzw. gewähltem
 * Feature (Skizze, Extrusion, Verrundung/Fase, Bohrung, Muster). Die Befehle selbst stehen in den Werkzeuggruppen.
 */
@Composable
fun FeatureControls(state: WorkspaceState) {
    val ex = state.extrusion
    val revolve = state.revolve
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
        if (pattern != null && holeMode == null && mode == null && !state.faceSketchPick) {
            PatternControls(state, pattern)
        } else if (mirrorBody != null && holeMode == null && mode == null && !state.faceSketchPick && !state.planePick) {
            MirrorBodyControls(state, mirrorBody)
        } else if (state.planePick) {
            Text("Tippe im 3D-Bild auf die ebene Fläche, aus der die Arbeitsebene entstehen soll.", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = state::cancelPlanePick) { Text("Abbrechen") }
        } else if (datum != null && holeMode == null && mode == null && !state.faceSketchPick) {
            DatumControls(state, datum)
        } else if (state.faceSketchPick) {
            Text("Tippe im 3D-Bild auf die ebene Fläche, auf der die neue Skizze liegen soll.", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = state::cancelFaceSketchPick) { Text("Abbrechen") }
        } else if (holeMode != null) {
            Text(
                if (holeMode.face == null) "Tippe im 3D-Bild auf eine ebene Fläche – dort entsteht die Bohrung. Erneut tippen verschiebt sie." else "Bohrung gesetzt – Maße einstellen, dann „Anwenden“.",
                style = MaterialTheme.typography.labelLarge,
            )
            AnchorFields(holeMode.anchors, state::setAnchorDistance)
            HoleSizeControls(state, state.holeDiameter, state.holeDepth, state.holeThrough, state::changeHoleDiameter, { state.holeDepth = it }, { state.holeThrough = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = state::cancelHoleMode) { Text("Abbrechen") }
                Button(onClick = state::applyHoleMode, enabled = holeMode.face != null, modifier = Modifier.weight(1f)) { Text("Anwenden") }
            }
        } else if (hole != null && mode == null) {
            AnchorFields(hole.anchors, state::updateHoleAnchor)
            HoleSizeControls(
                state, hole.diameter, hole.depth ?: 10.0, hole.depth == null,
                { state.updateHole(it, hole.depth) }, { state.updateHole(hole.diameter, it) }, { state.updateHole(hole.diameter, if (it) null else (hole.depth ?: 10.0)) },
            )
            Text(
                if (hole.anchors != null) "Die Bohrung hängt an den beiden hervorgehobenen Kanten und wandert mit ihnen." else "Position und Fläche bleiben fest; zum Verschieben löschen und neu setzen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (mode != null) {
            val n = mode.selected.size
            Text(
                if (n == 0) "Tippe im 3D-Bild die Kanten an, die du bearbeiten willst." else "$n ${if (n == 1) "Kante" else "Kanten"} gewählt – erneut tippen hebt die Wahl auf.",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = mode.kind == EdgeKind.ROUND, onClick = { state.setEdgeKind(EdgeKind.ROUND) }, label = { Text("◜ Verrundung") })
                FilterChip(selected = mode.kind == EdgeKind.CHAMFER, onClick = { state.setEdgeKind(EdgeKind.CHAMFER) }, label = { Text("◸ Fase") })
            }
            NumberField(if (mode.kind == EdgeKind.ROUND) "Radius (mm)" else "Abstand (mm)", state.edgeSize) { if (it > 0) state.edgeSize = it }
            Slider(value = state.edgeSize.toFloat().coerceIn(0.5f, 20f), onValueChange = { state.edgeSize = Math.round(it * 2) / 2.0 }, valueRange = 0.5f..20f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = state::cancelEdgeMode) { Text("Abbrechen") }
                Button(onClick = state::applyEdgeMode, enabled = n > 0, modifier = Modifier.weight(1f)) { Text("Anwenden") }
            }
        } else if (fillet != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = fillet.kind == EdgeKind.ROUND, onClick = { state.updateFillet(fillet.size, EdgeKind.ROUND) }, label = { Text("◜ Verrundung") })
                FilterChip(selected = fillet.kind == EdgeKind.CHAMFER, onClick = { state.updateFillet(fillet.size, EdgeKind.CHAMFER) }, label = { Text("◸ Fase") })
            }
            NumberField(if (fillet.kind == EdgeKind.ROUND) "Radius (mm)" else "Abstand (mm)", fillet.size) { if (it > 0) state.updateFillet(it, fillet.kind) }
            Slider(value = fillet.size.toFloat().coerceIn(0.5f, 20f), onValueChange = { state.updateFillet(Math.round(it * 2) / 2.0, fillet.kind) }, valueRange = 0.5f..20f)
            Text("${fillet.edges.size} ${if (fillet.edges.size == 1) "Kante" else "Kanten"}; zum Ändern der Kanten löschen und neu wählen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (revolve != null) {
            RevolveControls(state, revolve)
        } else if (ex == null) {
            val n = state.regionCount
            Text("Ebene: ${planeLabel(state.sketch)}", style = MaterialTheme.typography.labelLarge)
            Text(
                when {
                    n == 0 -> "Noch keine geschlossene Fläche – öffne die Skizze und zeichne eine Kontur."
                    n == 1 -> "1 geschlossene Fläche – bereit für Körper › „Extrudieren“, „Abtragen“ oder „Drehen“."
                    else -> "$n geschlossene Flächen – bereit für Körper › „Extrudieren“, „Abtragen“ oder „Drehen“."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = state::editSketch, modifier = Modifier.fillMaxWidth()) { Text("Skizze bearbeiten") }
        } else {
            val reversed = ex.distance < 0
            val length = kotlin.math.abs(ex.distance)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = ex.op == BodyOp.ADD, onClick = { state.updateExtrusion(ex.distance, ex.symmetric, BodyOp.ADD) }, label = { Text("▲ Hinzufügen") })
                FilterChip(selected = ex.op == BodyOp.CUT, onClick = { state.updateExtrusion(ex.distance, ex.symmetric, BodyOp.CUT) }, label = { Text("▼ Abtragen") })
            }
            NumberField("Länge (mm)", length) { v -> if (v > 0) state.updateExtrusion(if (reversed) -v else v, ex.symmetric) }
            Slider(value = length.toFloat().coerceIn(1f, 100f), onValueChange = { v ->
                val d = Math.round(v).toDouble().coerceAtLeast(1.0)
                state.updateExtrusion(if (reversed) -d else d, ex.symmetric)
            }, valueRange = 1f..100f)
            SwitchRow("Symmetrisch zur Skizzenebene", ex.symmetric) { state.updateExtrusion(ex.distance, it) }
            SwitchRow("Umgekehrte Richtung", reversed) { state.updateExtrusion(if (it) -length else length, ex.symmetric) }
            OutlinedButton(onClick = state::editSketch, modifier = Modifier.fillMaxWidth()) { Text("Skizze bearbeiten") }
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
        FilterChip(selected = f.op == BodyOp.ADD, onClick = { state.updateRevolve(f.copy(op = BodyOp.ADD)) }, label = { Text("▲ Hinzufügen") })
        FilterChip(selected = f.op == BodyOp.CUT, onClick = { state.updateRevolve(f.copy(op = BodyOp.CUT)) }, label = { Text("▼ Abtragen") }, enabled = f.op == BodyOp.CUT || state.canCut)
    }
    Text("Drehachse", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lines = state.revolveAxisOptions(f.sketch)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = f.axis == RevolveAxis.SketchY, onClick = { state.updateRevolve(f.copy(axis = RevolveAxis.SketchY)) }, label = { Text("Senkrecht (y)") })
        FilterChip(selected = f.axis == RevolveAxis.SketchX, onClick = { state.updateRevolve(f.copy(axis = RevolveAxis.SketchX)) }, label = { Text("Waagrecht (x)") })
        lines.forEachIndexed { i, a ->
            FilterChip(selected = f.axis == a, onClick = { state.updateRevolve(f.copy(axis = a)) }, label = { Text("Hilfslinie ${i + 1}") })
        }
        // Achse ist eine Linie, die keine Hilfslinie (mehr) ist – trotzdem als gewählt anzeigen
        if (f.axis is RevolveAxis.Line && f.axis !in lines) FilterChip(selected = true, onClick = {}, label = { Text("Linie") })
    }
    NumberField("Winkel (°) – 360 = volle Drehung", angle) { v -> if (v > 0 && v <= 360) state.updateRevolve(withAngle(v)) }
    Slider(value = angle.toFloat().coerceIn(5f, 360f), onValueChange = { v -> state.updateRevolve(withAngle((Math.round(v / 5) * 5.0).coerceAtLeast(5.0))) }, valueRange = 5f..360f)
    if (angle < 360) {
        SwitchRow("Symmetrisch zur Skizzenebene", f.symmetric) { state.updateRevolve(f.copy(symmetric = it)) }
        SwitchRow("Umgekehrte Richtung", reversed) { state.updateRevolve(f.copy(angle = if (it) -angle else angle)) }
    }
    Text(
        "Die Fläche darf die Achse berühren, aber nicht schneiden. Eine andere Achse: in der Skizze eine Hilfslinie zeichnen.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = state::editSketch, modifier = Modifier.fillMaxWidth()) { Text("Skizze bearbeiten") }
}

/** Einstellungen der gewählten Arbeitsebene: Art (Abstand / gedreht an Kante), Wert, Drehachse, „Skizze auf dieser Ebene“. */
@Composable
private fun DatumControls(state: WorkspaceState, p: PlaneFeature) {
    val def = p.def
    val edges = state.planeEdgeOptions(def.face)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = def is PlaneDef.Offset, onClick = {
            state.updateDatum(PlaneDef.Offset(def.face, (def as? PlaneDef.Offset)?.distance ?: 10.0))
        }, label = { Text("Abstand") })
        FilterChip(selected = def is PlaneDef.AtEdge, onClick = {
            if (def !is PlaneDef.AtEdge) {
                if (edges.isEmpty()) state.notice = "Diese Fläche hat keine gerade Kante als Drehachse."
                else state.updateDatum(PlaneDef.AtEdge(def.face, edges.first(), 30.0))
            }
        }, label = { Text("Gedreht an Kante") })
    }
    when (def) {
        is PlaneDef.Offset -> NumberField("Abstand zur Fläche (mm, negativ = in den Körper)", def.distance) { state.updateDatum(def.copy(distance = it)) }
        is PlaneDef.AtEdge -> {
            NumberField("Winkel zur Fläche (°)", def.angle) { state.updateDatum(def.copy(angle = it)) }
            val i = edges.indexOf(def.edge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (i >= 0) "Drehachse: Kante ${i + 1} von ${edges.size} (hervorgehoben)" else "Drehachse nicht gefunden",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { state.updateDatum(def.copy(edge = edges[(i + 1).mod(edges.size)])) }, enabled = edges.size > 1) { Text("Nächste") }
            }
        }
    }
    Button(onClick = state::createSketchOnDatum, modifier = Modifier.fillMaxWidth()) {
        CadIconView(CadIcon.SKETCH, MaterialTheme.colorScheme.onPrimary, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text("Skizze auf dieser Ebene")
    }
    Text(
        "Die Ebene folgt der Fläche bei Maßänderungen – Skizzen darauf wandern mit.",
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
    Text("Vervielfältigt „${state.patternSourceName(p)}“", style = MaterialTheme.typography.labelLarge)
    when (val k = p.kind) {
        is PatternKind.Linear -> {
            val axis1 = k.direction.dominantAxis()
            val axis2 = k.direction2?.dominantAxis()
            AxisChips("Richtung", axis1, disabled = axis2) { state.updatePattern(k.copy(direction = axisVector(it))) }
            CountSlider(k.count, 2..20) { state.updatePattern(k.copy(count = it)) }
            NumberField("Abstand (mm, negativ = Gegenrichtung)", k.spacing) { state.updatePattern(k.copy(spacing = it)) }
            if (state.canTwoWay(p)) {
                SwitchRow("Zweite Richtung (Raster)", k.direction2 != null) { on ->
                    state.updatePattern(if (on) k.copy(direction2 = axisVector(if (axis1 == 0) 1 else 0), spacing2 = k.spacing) else k.copy(direction2 = null))
                }
            }
            if (axis2 != null) {
                AxisChips("Richtung 2", axis2, disabled = axis1) { state.updatePattern(k.copy(direction2 = axisVector(it))) }
                CountSlider(k.count2, 2..20) { state.updatePattern(k.copy(count2 = it)) }
                NumberField("Abstand 2 (mm, negativ = Gegenrichtung)", k.spacing2) { state.updatePattern(k.copy(spacing2 = it)) }
                if (k.count * k.count2 > PatternGeometry.MAX_COUNT) {
                    Text("Höchstens ${PatternGeometry.MAX_COUNT} Exemplare – Anzahl verringern.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        is PatternKind.Circular -> {
            AxisChips("Drehachse", k.axis.dominantAxis()) { state.updatePattern(k.copy(axis = axisVector(it))) }
            CountSlider(k.count, 2..24) { state.updatePattern(k.copy(count = it)) }
            Text("Punkt auf der Drehachse", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..2) NumberField("${AXIS_NAMES[i]} (mm)", k.origin.component(i), Modifier.weight(1f)) { state.updatePattern(k.copy(origin = k.origin.with(i, it))) }
            }
            NumberField("Winkel (°) – 360 = voller Kreis", k.angle) { state.updatePattern(k.copy(angle = it)) }
        }
        is PatternKind.Mirror -> {
            val axis = k.normal.dominantAxis()
            AxisChips("Spiegelebene senkrecht zu", axis) { state.updatePattern(k.copy(normal = axisVector(it))) }
            NumberField("Lage der Ebene auf der ${AXIS_NAMES[axis]}-Achse (mm)", k.origin.component(axis)) { state.updatePattern(k.copy(origin = k.origin.with(axis, it))) }
            OutlinedButton(onClick = state::mirrorWholeBodyInstead, modifier = Modifier.fillMaxWidth()) { Text("Stattdessen den ganzen Körper spiegeln") }
        }
    }
    val rounds = state.patternFilletCount(p)
    if (rounds > 0 || p.withFillets) {
        SwitchRow(
            if (rounds == 1) "1 Verrundung/Fase der Quelle mitnehmen" else "$rounds Verrundungen/Fasen der Quelle mitnehmen",
            p.withFillets,
        ) { state.setPatternWithFillets(it) }
    }
    Text(
        when {
            state.canPattern -> "Die Kopien wirken wie das Original (hinzufügen bzw. abtragen). Mit Muster › Reihe, Kreis oder Spiegeln lässt sich dieses Muster selbst vervielfältigen – z. B. eine Reihe im Kreis."
            PatternGeometry.isTwoWay(p.kind) -> "Die Kopien wirken wie das Original (hinzufügen bzw. abtragen). Ein Raster lässt sich nicht weiter vervielfältigen."
            else -> "Die Kopien wirken wie das Original (hinzufügen bzw. abtragen). Dieses Muster vervielfältigt schon ein Muster und lässt sich nicht weiter verschachteln."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Einstellungen einer Körperspiegelung: Arbeitsebene oder Koordinatenebene mit Lage. */
@Composable
private fun MirrorBodyControls(state: WorkspaceState, f: MirrorBodyFeature) {
    Text("Spiegelt den ganzen Körper bis hierher und vereinigt ihn mit seinem Spiegelbild.", style = MaterialTheme.typography.labelLarge)
    val datums = state.mirrorDatumOptions(f)
    if (datums.isNotEmpty()) {
        Text("Spiegelebene", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = f.datum == null, onClick = { state.updateMirrorBody(f.copy(datum = null)) }, label = { Text("Koordinatenebene") })
            datums.forEach { d -> FilterChip(selected = f.datum == d.id, onClick = { state.updateMirrorBody(f.copy(datum = d.id)) }, label = { Text(d.name) }) }
        }
    }
    if (f.datum == null) {
        val axis = f.normal.dominantAxis()
        AxisChips("Spiegelebene senkrecht zu", axis) { state.updateMirrorBody(f.copy(normal = axisVector(it))) }
        NumberField("Lage der Ebene auf der ${AXIS_NAMES[axis]}-Achse (mm)", f.origin.component(axis)) { state.updateMirrorBody(f.copy(origin = f.origin.with(axis, it))) }
    } else {
        Text("Die Spiegelung folgt der Arbeitsebene, wenn sich der Körper ändert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        "Berührt der Körper die Ebene, entsteht ein zusammenhängendes, symmetrisches Teil; liegt er davor, kommt ein getrenntes Spiegelbild hinzu.",
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
        Text("Anzahl (mit Original)", Modifier.weight(1f))
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
                isError = parsed == null || parsed < 0, label = { Text("Kante ${i + 1} (mm)") },
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
        Text("Ø Durchmesser", Modifier.weight(1f))
        Text("${formatValue(diameter)} mm", color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = diameter.toFloat().coerceIn(1f, 40f), onValueChange = { onDiameter(Math.round(it * 2) / 2.0) }, valueRange = 1f..40f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Durchgehend"); Switch(through, onThrough)
        Box(Modifier.weight(1f))
        if (!through) Text("Tiefe ${formatValue(depth)} mm", color = MaterialTheme.colorScheme.primary)
    }
    if (!through) Slider(value = depth.toFloat().coerceIn(1f, 100f), onValueChange = { onDepth(Math.round(it * 2) / 2.0) }, valueRange = 1f..100f)
}
