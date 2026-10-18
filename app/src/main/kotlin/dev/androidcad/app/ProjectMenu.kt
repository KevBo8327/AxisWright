package dev.androidcad.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
private const val RECENT = 4

/**
 * Gemeinsames Projektmenü (Titel der App-Leiste in Modell- und Skript-Editor): neue Projekte beider Arten, Beispiele, Import, die
 * zuletzt bearbeiteten Projekte, „Alle Projekte …“ und Umbenennen/Duplizieren/Löschen des aktiven Projekts.
 */
@Composable
internal fun ProjectMenu(hub: ProjectHub, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectEntry?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var showExamples by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val import = rememberImporter(hub)
    val entries = hub.entries
    val current = entries.firstOrNull { hub.isCurrent(it) }

    Box(modifier) {
        TextButton(onClick = { hub.refreshProjects(); open = true }) {
            CadIconView(hub.active.icon(), colors.primary, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("${hub.currentName} ▾", Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        }
        // Menü im Stil der übrigen Flächen: abgerundet, feiner heller Rand, Abschnitte mit Überschrift, jede Zeile mit Symbol
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 280.dp),
            shape = RoundedCornerShape(16.dp), containerColor = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, CadTheme.colors.glassBorder), shadowElevation = 12.dp,
        ) {
            Section("Start")
            MenuItem("Startseite", CadIcon.HOME) { open = false; hub.showHome() }
            MenuItem("Neues Modell", CadIcon.SKETCH) { open = false; hub.newModel() }
            MenuItem("Neues Skript (OpenSCAD)", CadIcon.SCRIPT) { open = false; hub.newScript() }
            MenuItem("Beispiele …", CadIcon.EXAMPLES) { open = false; showExamples = true }
            MenuItem("Importieren (Modell, .scad) …", CadIcon.IMPORT) { open = false; import() }
            MenuDivider()
            Section("Zuletzt bearbeitet")
            entries.take(RECENT).forEach { e ->
                val isOpen = hub.isCurrent(e)
                val thumb = if (e.kind == ProjectKind.MODEL) hub.workspace.thumbnailFile(e.info.id).let { f -> rememberThumbnail(f, f?.lastModified() ?: 0L) } else null
                DropdownMenuItem(
                    text = { Text(e.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isOpen) colors.onPrimaryContainer else colors.onSurface) },
                    leadingIcon = {
                        // kleines Vorschaubild wie auf der Startseite, sonst das Symbol der Art
                        Box(Modifier.size(28.dp).background(colors.surfaceContainerHighest, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            if (thumb != null) Image(thumb, null, Modifier.size(26.dp)) else CadIconView(e.kind.icon(), colors.primary, 18.dp)
                        }
                    },
                    trailingIcon = if (isOpen) ({ Text("geöffnet", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer) }) else null,
                    modifier = if (isOpen) Modifier.padding(horizontal = 6.dp).background(colors.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(10.dp)) else Modifier,
                    onClick = { open = false; if (!isOpen) hub.open(e) },
                )
            }
            MenuItem("Alle Projekte …", CadIcon.LIST, trailing = "${entries.size}") { open = false; showAll = true }
            MenuDivider()
            Section("Dieses Projekt")
            MenuItem("Umbenennen …", CadIcon.RENAME) { open = false; renaming = true }
            MenuItem("Duplizieren", CadIcon.COPY, enabled = current != null) { open = false; current?.let(hub::duplicate) }
            MenuItem("Löschen …", CadIcon.DELETE, enabled = current != null, danger = true) { open = false; deleting = current }
            MenuDivider()
            Section("App")
            // eine Zeile statt drei: Tippen wechselt zur nächsten Darstellung, das Menü bleibt offen
            val theme = hub.workspace.prefs.themeMode
            MenuItem("Darstellung", CadIcon.THEME, trailing = theme.label) { hub.workspace.prefs.themeMode = ThemeMode.entries[(theme.ordinal + 1) % ThemeMode.entries.size] }
            MenuItem("Hilfe & Gesten …", CadIcon.HELP) { open = false; showHelp = true }
            MenuItem("Feedback senden …", CadIcon.MAIL) { open = false; sendFeedback(context) }
            MenuItem("Lizenzen …", CadIcon.INFO) { open = false; showLicenses = true }
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
    if (showHelp) HelpDialog(hub, firstStart = false) { showHelp = false }
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}

/** Zeile des Projektmenüs: Symbol in der Akzentfarbe, optional ein Wert rechts; [danger] für Löschen (rot). */
@Composable
private fun MenuItem(text: String, icon: CadIcon, enabled: Boolean = true, danger: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = when { !enabled -> colors.onSurface.copy(alpha = 0.38f); danger -> colors.error; else -> colors.primary }
    DropdownMenuItem(
        text = { Text(text, color = if (danger && enabled) colors.error else if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)) },
        leadingIcon = { Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { CadIconView(icon, tint, 20.dp) } },
        trailingIcon = trailing?.let { { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) } },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Überschrift eines Abschnitts im Projektmenü. */
@Composable
private fun Section(title: String) = Text(
    title, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
)

@Composable
private fun MenuDivider() = HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

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
internal fun ExamplesDialog(hub: ProjectHub, onDismiss: () -> Unit) {
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

/** Datei wählen und importieren (Modell oder .scad, siehe [ProjectHub.import]); liefert die Funktion, die die Auswahl öffnet. */
@Composable
internal fun rememberImporter(hub: ProjectHub): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
            if (text != null) hub.import(displayName(context, uri)?.removeSuffix(".paramworker")?.removeSuffix(".partwright") ?: "Import", text)
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Dateiname ohne Endung aus einer Dokument-URI (z. B. „Halter“ aus „Halter.scad“). */
internal fun displayName(context: android.content.Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
    }

private const val FEEDBACK_ADDRESS = "kb-it-services@outlook.de"

/** Öffnet das E-Mail-Programm mit Adresse, Betreff und Version/Gerät (hilft beim Nachstellen); ohne E-Mail-App ein Hinweis mit der Adresse. */
private fun sendFeedback(context: Context) {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    val body = "\n\n---\nParamWorker $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, "ParamWorker $version – Feedback")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Keine E-Mail-App gefunden – Feedback bitte an $FEEDBACK_ADDRESS", Toast.LENGTH_LONG).show()
    }
}
