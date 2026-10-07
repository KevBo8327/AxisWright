package dev.androidcad.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Properties

/**
 * Kleine Bedienvorlieben, die über einen Neustart erhalten bleiben (z. B. ob der Modellaufbau ausgeklappt ist). Liegen als
 * Properties-Datei in [file]; ohne Datei (Tests) nur im Speicher. Lese- und Schreibfehler werden ignoriert – es sind Vorlieben.
 */
class UiPrefs(private val file: File? = null) {
    private val props = Properties().apply {
        try { file?.takeIf { it.exists() }?.inputStream()?.use { load(it) } } catch (_: Exception) { }
    }

    private var expandedState by mutableStateOf(props.getProperty(TREE_EXPANDED) == "true")

    /** Ob der Verlauf (Modellaufbau) als Karte über dem 3D-Bild offen ist. Standard: geschlossen. */
    var historyOpen: Boolean
        get() = expandedState
        set(on) {
            expandedState = on
            props.setProperty(TREE_EXPANDED, on.toString())
            save()
        }

    private fun save() {
        val f = file ?: return
        try { f.outputStream().use { props.store(it, "Bedienvorlieben") } } catch (_: Exception) { }
    }

    private companion object {
        const val TREE_EXPANDED = "history.open"
    }
}
