package dev.androidcad.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Schlüssel in [UiPrefs]: die Einführung wurde gezeigt (beim ersten Start einmal von selbst). */
internal const val INTRO_SEEN = "intro.seen"

/**
 * Einführung und Hilfe: die drei Schritte vom Skizzieren zum Körper und die Bedienung mit Finger, Stift, Maus und Tastatur.
 * Mit [firstStart] zusätzlich „Beispiel öffnen“ und „Leer beginnen“ (sonst nur „Schließen“).
 */
@Composable
fun HelpDialog(hub: ProjectHub, firstStart: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        // beim ersten Start nur über die Knöpfe schließen: ein Tipp daneben soll die Einführung nicht unbemerkt wegnehmen
        onDismissRequest = { if (!firstStart) onDismiss() },
        title = { Text(stringResource(if (firstStart) R.string.help_title_welcome else R.string.help_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Section(stringResource(R.string.help_section_build))
                Line(stringResource(R.string.help_build_1))
                Line(stringResource(R.string.help_build_2))
                Line(stringResource(R.string.help_build_3))
                Line(stringResource(R.string.help_build_4))
                Section(stringResource(R.string.help_section_start))
                Line(stringResource(R.string.help_start_1))
                Section(stringResource(R.string.help_section_sketch))
                Line(stringResource(R.string.help_sketch_1))
                Line(stringResource(R.string.help_sketch_2))
                Line(stringResource(R.string.help_sketch_3))
                Line(stringResource(R.string.help_sketch_4))
                Section(stringResource(R.string.help_section_3d))
                Line(stringResource(R.string.help_3d_1))
                Line(stringResource(R.string.help_3d_2))
                // Version für Rückmeldungen (welcher Stand wurde getestet)
                val context = LocalContext.current
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                if (version != null) Text("ParamWorker $version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            if (firstStart) Button(onClick = { onDismiss(); hub.loadModelExample() }) { Text(stringResource(R.string.help_open_example)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        dismissButton = { if (firstStart) TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_start_empty)) } },
    )
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.heightIn(min = 28.dp))

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)
