package dev.androidcad.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Oberste Ebene der App. Welcher Editor sichtbar ist, bestimmt die gemeinsame Projektverwaltung ([ProjectHub]): ein **Modell**
 * (Skizze → Körper, Hauptweg) öffnet den Modell-Editor, ein **Skript** den OpenSCAD-Editor (optionale Erweiterung). Gewechselt wird
 * über das Projektmenü in der App-Leiste; die Zurück-Taste führt vom Skript zum Modell.
 *
 * Die Fläche reicht bis unter die Systemleisten (Hintergrund auch dort, passend zu hell/dunkel); der Inhalt bleibt innerhalb der
 * sicheren Zeichenfläche.
 */
@Composable
fun AppRoot(hub: ProjectHub) {
    val prefs = hub.workspace.prefs
    // Einführung einmal beim ersten Start; danach über Projektmenü › Hilfe
    var intro by remember { mutableStateOf(prefs.text(INTRO_SEEN) == null) }
    if (intro) HelpDialog(hub, firstStart = true) { intro = false; prefs.putText(INTRO_SEEN, "1") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (hub.active) {
                ProjectKind.SCRIPT -> {
                    BackHandler { hub.switchTo(ProjectKind.MODEL) }
                    Column(Modifier.fillMaxSize()) {
                        ScriptTopBar(hub)
                        HorizontalDivider()
                        Box(Modifier.weight(1f)) { CadScreen(hub.script) }
                    }
                }
                ProjectKind.MODEL -> WorkspaceScreen(hub)
            }
        }
    }
}
