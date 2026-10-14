package dev.androidcad.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Startseite nach dem Splash: Schnellstart (neues Modell oder Skript, Beispiele, Import, Hilfe) und die zuletzt bearbeiteten Projekte
 * als Karten. Ein Tipp auf ein Projekt öffnet es; zurück kommt man mit der Zurück-Taste oder über Projektmenü › Startseite.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartScreen(hub: ProjectHub) {
    val colors = MaterialTheme.colorScheme
    var showExamples by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val import = rememberImporter(hub)
    LaunchedEffect(Unit) { hub.refreshProjects() }
    val entries = hub.entries
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Partwright", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
                    Text("Parametrisch konstruieren – Skizze, Maße, Körper.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickTile("Neues Modell", "Skizze → Körper", CadIcon.SKETCH, primary = true) { hub.newModel() }
                    QuickTile("Neues Skript", "OpenSCAD", CadIcon.SCRIPT) { hub.newScript() }
                    QuickTile("Beispiele", "zum Ausprobieren", CadIcon.EXTRUDE) { showExamples = true }
                    QuickTile("Importieren", "Modell oder .scad", CadIcon.PLANE) { import() }
                    QuickTile("Hilfe & Gesten", "Einführung", CadIcon.MEASURE) { showHelp = true }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Zuletzt bearbeitet", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
            }
            if (entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Noch keine Projekte – starte mit „Neues Modell“ oder einem Beispiel.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            items(entries, key = { "${it.kind}/${it.info.id}" }) { e ->
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().background(colors.surfaceContainer, shape).border(1.dp, colors.outlineVariant, shape)
                        .clickable { hub.open(e) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        CadIconView(e.kind.icon(), colors.onPrimaryContainer, 24.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(e.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text("${e.kind.label} · ${format.format(Date(e.info.updatedAt))}", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showExamples) ExamplesDialog(hub, onDismiss = { showExamples = false })
    if (showHelp) HelpDialog(hub, firstStart = false) { showHelp = false }
}

@Composable
private fun QuickTile(title: String, subtitle: String, icon: CadIcon, primary: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val bg = if (primary) colors.primaryContainer else colors.surfaceContainerHigh
    val fg = if (primary) colors.onPrimaryContainer else colors.onSurface
    Column(
        Modifier.size(width = 160.dp, height = 112.dp).background(bg, shape).border(1.dp, if (primary) colors.primary else colors.outlineVariant, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        CadIconView(icon, if (primary) colors.onPrimaryContainer else colors.primary, 28.dp)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}
