package dev.androidcad.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Symbol der Projektart. */
internal fun ProjectKind.icon(): CadIcon = if (this == ProjectKind.MODEL) CadIcon.SKETCH else CadIcon.SCRIPT

/** Wie viele zuletzt bearbeitete Projekte das Menü direkt zeigt (alle über „Alle Projekte …“). */
private const val RECENT = 6

/**
 * Gemeinsames Projektmenü (Titel der App-Leiste in Modell- und Skript-Editor): neue Projekte beider Arten, Beispiele, Import, die
 * zuletzt bearbeiteten Projekte, „Alle Projekte …“ und Umbenennen/Duplizieren/Löschen des aktiven Projekts.
 */
@Composable
internal fun ProjectMenu(hub: ProjectHub) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectEntry?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var showExamples by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
            if (text != null) hub.import(displayName(context, uri)?.removeSuffix(".partwright") ?: "Import", text)
        }
    }
    val entries = hub.entries
    val current = entries.firstOrNull { hub.isCurrent(it) }

    Box {
        TextButton(onClick = { hub.refreshProjects(); open = true }) {
            CadIconView(hub.active.icon(), colors.primary, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("${hub.currentName} ▾", maxLines = 1, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("Neues Modell", CadIcon.SKETCH) { open = false; hub.newModel() }
            MenuItem("Neues Skript (OpenSCAD)", CadIcon.SCRIPT) { open = false; hub.newScript() }
            MenuItem("Beispiele …") { open = false; showExamples = true }
            MenuItem("Projekt importieren (Modell oder .scad) …") { open = false; importer.launch(arrayOf("*/*")) }
            HorizontalDivider()
            Text(
                "Zuletzt bearbeitet", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            )
            entries.take(RECENT).forEach { e ->
                DropdownMenuItem(
                    text = { Text(e.info.name + if (hub.isCurrent(e)) "  ✓" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { CadIconView(e.kind.icon(), colors.onSurfaceVariant, 20.dp) },
                    onClick = { open = false; if (!hub.isCurrent(e)) hub.open(e) },
                )
            }
            MenuItem("Alle Projekte (${entries.size}) …") { open = false; showAll = true }
            HorizontalDivider()
            MenuItem("Umbenennen …") { open = false; renaming = true }
            DropdownMenuItem(text = { Text("Duplizieren") }, enabled = current != null, onClick = { open = false; current?.let(hub::duplicate) })
            DropdownMenuItem(text = { Text("Löschen …") }, enabled = current != null, onClick = { open = false; deleting = current })
            HorizontalDivider()
            Text(
                "Darstellung", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            )
            ThemeMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label + if (hub.workspace.prefs.themeMode == m) "  ✓" else "") },
                    onClick = { open = false; hub.workspace.prefs.themeMode = m },
                )
            }
            HorizontalDivider()
            MenuItem("Lizenzen …") { open = false; showLicenses = true }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(hub.currentName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("${hub.active.label} umbenennen") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { renaming = false; hub.renameCurrent(text) }, enabled = text.isNotBlank()) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Abbrechen") } },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("„${e.info.name}“ löschen?") },
            text = { Text(if (e.kind == ProjectKind.MODEL) "Skizzen und Körper werden dauerhaft entfernt." else "Das Skript wird dauerhaft entfernt.") },
            confirmButton = { TextButton(onClick = { deleting = null; hub.delete(e) }) { Text("Löschen", color = colors.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }
    if (showAll) AllProjectsDialog(hub, onDelete = { deleting = it }, onDismiss = { showAll = false })
    if (showExamples) ExamplesDialog(hub, onDismiss = { showExamples = false })
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}

@Composable
private fun MenuItem(text: String, icon: CadIcon? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = icon?.let { { CadIconView(it, MaterialTheme.colorScheme.onSurfaceVariant, 20.dp) } },
        onClick = onClick,
    )
}

/** Alle Projekte beider Arten mit Art, Änderungsdatum, „Kopie“ und „Löschen“. */
@Composable
private fun AllProjectsDialog(hub: ProjectHub, onDelete: (ProjectEntry) -> Unit, onDismiss: () -> Unit) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alle Projekte") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(hub.entries, key = { "${it.kind}/${it.info.id}" }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onDismiss(); if (!hub.isCurrent(e)) hub.open(e) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CadIconView(e.kind.icon(), colors.primary, 22.dp)
                        Column(Modifier.weight(1f)) {
                            Text(e.info.name + if (hub.isCurrent(e)) "  (geöffnet)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${e.kind.label} · ${format.format(Date(e.info.updatedAt))}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        TextButton(onClick = { onDismiss(); hub.duplicate(e) }) { Text("Kopie") }
                        TextButton(onClick = { onDelete(e) }) { Text("Löschen", color = colors.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Beispiele beider Arten: das parametrische Modell und die mitgelieferten OpenSCAD-Skripte. */
@Composable
private fun ExamplesDialog(hub: ProjectHub, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scripts = remember { context.assets.list("examples")?.filter { it.endsWith(".scad") }?.sorted() ?: emptyList() }
    val colors = MaterialTheme.colorScheme
    @Composable
    fun Row(kind: ProjectKind, name: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onDismiss(); onClick() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CadIconView(kind.icon(), colors.primary, 22.dp)
            Column(Modifier.weight(1f)) {
                Text(name)
                Text(kind.label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Beispiele") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { Row(ProjectKind.MODEL, "Platte mit vier Bohrungen") { hub.loadModelExample() } }
                items(scripts) { file ->
                    Row(ProjectKind.SCRIPT, file.removeSuffix(".scad")) {
                        val text = context.assets.open("examples/$file").use { it.readBytes().toString(Charsets.UTF_8) }
                        hub.newScript(file.removeSuffix(".scad"), text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Dateiname ohne Endung aus einer Dokument-URI (z. B. „Halter“ aus „Halter.scad“). */
internal fun displayName(context: android.content.Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
    }
