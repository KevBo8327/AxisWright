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
import androidx.compose.ui.res.stringResource
import android.provider.Settings
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
    var showImprint by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val import = rememberImporter(hub)
    val scriptName = stringResource(R.string.menu_script_default_name)
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
            Section(stringResource(R.string.menu_section_start))
            MenuItem(stringResource(R.string.menu_home), CadIcon.HOME) { open = false; hub.showHome() }
            MenuItem(stringResource(R.string.start_tile_new_model), CadIcon.SKETCH) { open = false; hub.newModel() }
            MenuItem(stringResource(R.string.menu_new_script), CadIcon.SCRIPT) { open = false; hub.newScript(scriptName) }
            MenuItem(stringResource(R.string.menu_examples), CadIcon.EXAMPLES) { open = false; showExamples = true }
            MenuItem(stringResource(R.string.menu_import), CadIcon.IMPORT) { open = false; import() }
            MenuDivider()
            Section(stringResource(R.string.start_recent))
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
                    trailingIcon = if (isOpen) ({ Text(stringResource(R.string.menu_open_badge), style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer) }) else null,
                    modifier = if (isOpen) Modifier.padding(horizontal = 6.dp).background(colors.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(10.dp)) else Modifier,
                    onClick = { open = false; if (!isOpen) hub.open(e) },
                )
            }
            MenuItem(stringResource(R.string.menu_all_projects), CadIcon.LIST, trailing = "${entries.size}") { open = false; showAll = true }
            MenuDivider()
            Section(stringResource(R.string.menu_section_project))
            MenuItem(stringResource(R.string.menu_rename), CadIcon.RENAME) { open = false; renaming = true }
            MenuItem(stringResource(R.string.menu_duplicate), CadIcon.COPY, enabled = current != null) { open = false; current?.let(hub::duplicate) }
            MenuItem(stringResource(R.string.menu_delete), CadIcon.DELETE, enabled = current != null, danger = true) { open = false; deleting = current }
            MenuDivider()
            Section(stringResource(R.string.menu_section_app))
            // eine Zeile statt drei: Tippen wechselt zur nächsten Darstellung, das Menü bleibt offen
            val theme = hub.workspace.prefs.themeMode
            MenuItem(stringResource(R.string.menu_appearance), CadIcon.THEME, trailing = stringResource(theme.label)) { hub.workspace.prefs.themeMode = ThemeMode.entries[(theme.ordinal + 1) % ThemeMode.entries.size] }
            MenuItem(stringResource(R.string.menu_pro), CadIcon.PRO, trailing = when { Pro.isPro -> stringResource(R.string.menu_pro_active); Pro.FREE_DURING_TEST -> stringResource(R.string.menu_pro_trial); else -> Pro.price }) { open = false; Pro.dialogOpen = true }
            MenuItem(stringResource(R.string.menu_help), CadIcon.HELP) { open = false; showHelp = true }
            MenuItem(stringResource(R.string.menu_feedback), CadIcon.MAIL) { open = false; sendFeedback(context) }
            MenuItem(stringResource(R.string.menu_imprint), CadIcon.INFO) { open = false; showImprint = true }
            MenuItem(stringResource(R.string.menu_licenses), CadIcon.INFO) { open = false; showLicenses = true }
            // Sprache je App: die Systemseite dafür gibt es ab Android 13
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                MenuItem(stringResource(R.string.menu_language), CadIcon.INFO) { open = false; openLanguageSettings(context) }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(hub.currentName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(if (hub.active == ProjectKind.MODEL) R.string.menu_rename_model_title else R.string.menu_rename_script_title)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true, label = { Text(stringResource(R.string.common_name)) }) },
            confirmButton = { TextButton(onClick = { renaming = false; hub.renameCurrent(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.menu_delete_title, e.info.name)) },
            text = { Text(stringResource(if (e.kind == ProjectKind.MODEL) R.string.menu_delete_msg_model else R.string.menu_delete_msg_script)) },
            confirmButton = { TextButton(onClick = { deleting = null; hub.delete(e) }) { Text(stringResource(R.string.common_delete), color = colors.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (showAll) AllProjectsDialog(hub, onDelete = { deleting = it }, onDismiss = { showAll = false })
    if (showExamples) ExamplesDialog(hub, onDismiss = { showExamples = false })
    if (showHelp) HelpDialog(hub, firstStart = false) { showHelp = false }
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
    if (showImprint) ImprintDialog(onDismiss = { showImprint = false })
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
        title = { Text(stringResource(R.string.menu_all_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(hub.entries, key = { "${it.kind}/${it.info.id}" }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onDismiss(); if (!hub.isCurrent(e)) hub.open(e) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CadIconView(e.kind.icon(), colors.primary, 22.dp)
                        Column(Modifier.weight(1f)) {
                            Text(if (hub.isCurrent(e)) stringResource(R.string.menu_all_current, e.info.name) else e.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${stringResource(e.kind.label)} · ${format.format(Date(e.info.updatedAt))}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        TextButton(onClick = { onDismiss(); hub.duplicate(e) }) { Text(stringResource(R.string.menu_copy)) }
                        TextButton(onClick = { onDelete(e) }) { Text(stringResource(R.string.common_delete), color = colors.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
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
                Text(stringResource(kind.label), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_examples_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { Row(ProjectKind.MODEL, stringResource(R.string.menu_example_plate)) { hub.loadModelExample() } }
                items(scripts) { file ->
                    Row(ProjectKind.SCRIPT, file.removeSuffix(".scad")) {
                        val text = context.assets.open("examples/$file").use { it.readBytes().toString(Charsets.UTF_8) }
                        hub.newScript(file.removeSuffix(".scad"), text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/** Datei wählen und importieren (Modell oder .scad, siehe [ProjectHub.import]); liefert die Funktion, die die Auswahl öffnet. */
@Composable
internal fun rememberImporter(hub: ProjectHub): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultName = stringResource(R.string.menu_import_default_name)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
            if (text != null) hub.import(displayName(context, uri)?.removeSuffix(".paramworker")?.removeSuffix(".partwright") ?: defaultName, text)
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
        Toast.makeText(context, context.getString(R.string.menu_feedback_no_app, FEEDBACK_ADDRESS), Toast.LENGTH_LONG).show()
    }
}

/** Öffnet die Sprachseite dieser App in den Systemeinstellungen (Android 13+). */
private fun openLanguageSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // ohne Systemseite gibt es nichts anzubieten
    }
}
