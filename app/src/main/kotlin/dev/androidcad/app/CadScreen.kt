package dev.androidcad.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SNIPPETS = listOf("{", "}", "(", ")", "[", "]", ";", "=", "$", "\"", ",", ":")

/**
 * Adaptives Hauptlayout nach Material-Breakpoints der Fensterbreite
 * (Googlebook-Fenster lassen sich frei skalieren, daher keine Gerätetyp-Abfrage):
 *  - kompakt (< 600 dp): Tabs Code / Parameter / 3D
 *  - mittel (< 840 dp): links Tabs Code | Parameter, rechts 3D
 *  - erweitert (≥ 840 dp): Code | 3D | Parameter nebeneinander
 */
@Composable
fun CadScreen(state: CadState) {
    Surface(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            // Globale Tastenkürzel (Googlebook / Tablet mit Tastatur): Strg+Z, Strg+Umschalt+Z / Strg+Y, Strg+S
            if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
            when {
                e.key == Key.Z && e.isShiftPressed -> { state.redo(); true }
                e.key == Key.Z -> { state.undo(); true }
                e.key == Key.Y -> { state.redo(); true }
                e.key == Key.S -> { state.flush(); true }
                else -> false
            }
        },
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            when {
                maxWidth >= 840.dp -> Row(Modifier.fillMaxSize()) {
                    EditorPane(state, Modifier.weight(1f).fillMaxHeight())
                    VerticalRule()
                    Viewport(state, Modifier.weight(1.3f).fillMaxHeight())
                    VerticalRule()
                    CustomizerPanel(state, Modifier.weight(0.8f).fillMaxHeight())
                }
                maxWidth >= 600.dp -> Row(Modifier.fillMaxSize()) {
                    var tab by remember { mutableIntStateOf(0) }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Tabs(listOf("Code", "Parameter"), tab) { tab = it }
                        if (tab == 0) EditorPane(state, Modifier.weight(1f)) else CustomizerPanel(state, Modifier.weight(1f))
                    }
                    VerticalRule()
                    Viewport(state, Modifier.weight(1f).fillMaxHeight())
                }
                else -> Column(Modifier.fillMaxSize()) {
                    var tab by remember { mutableIntStateOf(2) }
                    Tabs(listOf("Code", "Parameter", "3D"), tab) { tab = it }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> EditorPane(state, Modifier.fillMaxSize())
                            1 -> CustomizerPanel(state, Modifier.fillMaxSize())
                            else -> Viewport(state, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected) {
        titles.forEachIndexed { i, t -> Tab(selected = selected == i, onClick = { onSelect(i) }, text = { Text(t) }) }
    }
}

@Composable
private fun VerticalRule() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun Viewport(state: CadState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val mesh = state.mesh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri ->
        if (uri != null && mesh != null) scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(mesh.toBinaryStl()) }
        }
    }
    Box(modifier.background(colors.surfaceVariant.copy(alpha = 0.3f))) {
        if (mesh != null && !mesh.isEmpty) {
            MeshViewport(mesh, remember { ViewCamera() }, base = colors.primary, background = colors.surfaceVariant.copy(alpha = 0.3f).compositeOver(colors.background))
        } else {
            // Geometrie fehlgeschlagen oder leer: Hüllquader als Orientierungshilfe
            val boxes = remember(state.result) { state.result?.root?.let { collectBoxes(it) } ?: emptyList() }
            WireViewport(boxes, solid = colors.primary, dim = colors.outline, axis = colors.onSurface)
        }
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.rendering) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            if (mesh != null && !mesh.isEmpty) {
                AssistChip(onClick = {}, label = { Text("%,d Dreiecke".format(mesh.triangleCount)) })
                AssistChip(onClick = {}, label = { Text("%.2f cm³".format(mesh.volume() / 1000.0)) })
            }
        }
        state.meshError?.let {
            Text("Geometrie: $it", Modifier.align(Alignment.BottomStart).padding(8.dp), style = MaterialTheme.typography.labelMedium, color = colors.error)
        }
    }
}

@Composable
private fun EditorPane(state: CadState, modifier: Modifier) {
    Column(modifier) {
        OutlinedTextField(
            value = state.editor,
            onValueChange = state::onEditorChange,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                // F5 oder Strg+Enter: sofort neu auswerten
                .onPreviewKeyEvent { e ->
                    val hit = e.type == KeyEventType.KeyDown && (e.key == Key.F5 || (e.isCtrlPressed && e.key == Key.Enter))
                    if (hit) state.renderNow()
                    hit
                },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            label = { Text("OpenSCAD-Code") },
        )
        // Snippet-Leiste: Sonderzeichen sind auf der Bildschirmtastatur mühsam.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in SNIPPETS) AssistChip(onClick = { state.insertAtCursor(s) }, label = { Text(s, fontFamily = FontFamily.Monospace) })
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
        Console(state, Modifier.fillMaxWidth().heightIn(max = 120.dp).padding(8.dp))
    }
}

@Composable
private fun Console(state: CadState, modifier: Modifier) {
    val lines = buildList {
        state.error?.let { add("Fehler: $it") }
        state.result?.let { r ->
            addAll(r.warnings.map { "Warnung: $it" })
            addAll(r.echoes)
        }
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (lines.isEmpty()) Text("Bereit.", style = MaterialTheme.typography.bodySmall)
        for (l in lines) Text(
            l,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (l.startsWith("Fehler")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * App-Leiste des Skript-Editors – gleicher Aufbau wie im Modellmodus: gemeinsames Projektmenü, Speicherstatus, Rückgängig/Wiederholen,
 * „STL exportieren“ und ⋮ (Quelltext exportieren, zum Modell-Editor).
 */
@Composable
internal fun ScriptTopBar(hub: ProjectHub) {
    val state = hub.script
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val stlExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri ->
        val mesh = state.mesh
        if (uri != null && mesh != null) scope.launch(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(mesh.toBinaryStl()) } }
    }
    val scadExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val text = state.editor.text
            scope.launch(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
        }
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProjectMenu(hub)
        Text(
            when (state.saveState) {
                SaveState.SAVED -> "Gespeichert"
                SaveState.PENDING -> "Speichert …"
                SaveState.ERROR -> "Speichern fehlgeschlagen"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (state.saveState == SaveState.ERROR) colors.error else colors.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (state.rendering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        ActionButton(CadIcon.UNDO, "Rückgängig (Strg+Z)", enabled = state.canUndo) { state.undo() }
        ActionButton(CadIcon.REDO, "Wiederholen (Strg+Umschalt+Z)", enabled = state.canRedo) { state.redo() }
        TextButton(onClick = { stlExporter.launch("${state.project.name.ifBlank { "modell" }}.stl") }, enabled = state.mesh?.isEmpty == false) { Text("STL exportieren") }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Quelltext exportieren (.scad) …") }, onClick = { menu = false; scadExporter.launch("${state.project.name.ifBlank { "projekt" }}.scad") })
                DropdownMenuItem(text = { Text("Zum Modell-Editor") }, onClick = { menu = false; hub.switchTo(ProjectKind.MODEL) })
            }
        }
    }
}
