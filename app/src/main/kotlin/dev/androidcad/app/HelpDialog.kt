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
import androidx.compose.ui.Modifier
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
        onDismissRequest = onDismiss,
        title = { Text(if (firstStart) "Willkommen bei Partwright" else "Hilfe") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Section("So entsteht ein Bauteil")
                Line("1. Skizze zeichnen: Linie, Rechteck, Kreis … – Linien enden an Punkten, geschlossene Umrisse werden zur Fläche.")
                Line("2. Bemaßen: Linien oder Punkte antippen und Maße bzw. Bedingungen setzen, bis die Skizze „vollständig bestimmt“ ist.")
                Line("3. „Fertig“, dann Körper › Extrudieren (oder Drehen). Danach Bohrungen, Verrundungen, Muster, Schale …")
                Line("Jeder Schritt bleibt änderbar: im Verlauf antippen, Werte ändern – das Modell baut sich neu. Parameter machen Maße zu Formeln.")
                Section("Skizze")
                Line("Finger oder Stift: tippen und ziehen zum Zeichnen und Auswählen. Zwei Finger: verschieben und zoomen.")
                Line("Stift-Radierer: löscht, was er berührt. Maus: Rad zoomt, mittlere Taste verschiebt, rechte Taste bricht ab.")
                Line("Tastatur: L Linie, R Rechteck, C Kreis, A Bogen, S Auswahl · Entf löscht · Esc bricht ab · Strg+Z/Y rückgängig/wiederholen · Strg+A alles.")
                Section("3D-Ansicht")
                Line("Ziehen dreht, zwei Finger zoomen, das Mausrad auch. „Ansicht“ wechselt zu Oben/Vorne/Rechts und passt ein.")
                Line("Kanten und Flächen antippen, wenn ein Werkzeug danach fragt (Verrunden, Bohrung, Schale, Messen). ↶ ↷ machen Änderungen rückgängig.")
            }
        },
        confirmButton = {
            if (firstStart) Button(onClick = { onDismiss(); hub.loadModelExample() }) { Text("Beispiel öffnen") }
            else TextButton(onClick = onDismiss) { Text("Schließen") }
        },
        dismissButton = { if (firstStart) TextButton(onClick = onDismiss) { Text("Leer beginnen") } },
    )
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.heightIn(min = 28.dp))

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)
