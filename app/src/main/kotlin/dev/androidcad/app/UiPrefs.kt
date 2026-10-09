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

    private var themeState by mutableStateOf(
        ThemeMode.entries.firstOrNull { it.name == props.getProperty(THEME) } ?: ThemeMode.DARK,
    )

    /** Darstellung (Dunkel/Hell/System); Standard Dunkel. Änderungen färben die App sofort um. */
    var themeMode: ThemeMode
        get() = themeState
        set(m) {
            themeState = m
            props.setProperty(THEME, m.name)
            save()
        }

    /** Gemerkter Text unter [key] (z. B. die zuletzt gewählte Zeichenvariante) oder `null`. */
    fun text(key: String): String? = props.getProperty(key)

    fun putText(key: String, value: String) {
        if (props.getProperty(key) == value) return
        props.setProperty(key, value)
        save()
    }

    private fun save() {
        val f = file ?: return
        try { f.outputStream().use { props.store(it, "Bedienvorlieben") } } catch (_: Exception) { }
    }

    private companion object {
        const val TREE_EXPANDED = "history.open"
        const val THEME = "theme.mode"
    }
}
