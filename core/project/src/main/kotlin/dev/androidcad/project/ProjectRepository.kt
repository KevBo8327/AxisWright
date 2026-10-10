package dev.androidcad.project

import java.io.File

public data class ProjectInfo(val id: String, val name: String, val updatedAt: Long)

public data class ProjectData(val info: ProjectInfo, val source: String, val overrides: Map<String, String>)

/**
 * Dateibasierte Projektablage unterhalb von [root]:
 * ```
 * root/<id>/project.scad         Quelltext (UTF-8)
 * root/<id>/project.properties   name, updatedAt, ov.<parameter>=<Literal>
 * root/last                      id des zuletzt geöffneten Projekts
 * ```
 * Schreibzugriffe sind atomar (temporäre Datei + Umbenennen), damit ein Abbruch mitten im Speichern
 * kein Projekt zerstört. Plain-JVM, damit ohne Gerät testbar.
 */
public class ProjectRepository(
    root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = FolderStore(root, content = "project.scad", meta = "project.properties")

    /** Alle Projekte, zuletzt geänderte zuerst. Beschädigte Ordner werden übersprungen. */
    public fun list(): List<ProjectInfo> = store.list()

    public fun create(name: String, source: String, overrides: Map<String, String> = emptyMap()): ProjectData {
        val info = write(store.newId(), name.ifBlank { "Unbenannt" }, source, overrides)
        return ProjectData(info, source, overrides)
    }

    public fun load(id: String): ProjectData? {
        val dir = store.dirOf(id) ?: return null
        val info = store.readInfo(dir) ?: return null
        val source = store.readText(store.contentFile(dir)) ?: return null
        val props = store.readMeta(dir) ?: return null
        val overrides = props.stringPropertyNames()
            .filter { it.startsWith(OVERRIDE_PREFIX) }
            .associate { it.removePrefix(OVERRIDE_PREFIX) to props.getProperty(it) }
        return ProjectData(info, source, overrides)
    }

    /** Speichert Quelltext, Parameterwerte und (optional) einen neuen Namen. */
    @Synchronized // liest den bisherigen Namen: zusammen mit dem Schreiben, sonst stört das Lesen ein gleichzeitiges Ersetzen
    public fun save(id: String, source: String, overrides: Map<String, String>, name: String? = null): ProjectInfo {
        val dir = store.dirOf(id) ?: throw IllegalArgumentException("Ungültige Projekt-ID: $id")
        val currentName = name ?: store.readInfo(dir)?.name ?: "Unbenannt"
        return write(id, currentName.ifBlank { "Unbenannt" }, source, overrides)
    }

    public fun rename(id: String, name: String): ProjectInfo? {
        val data = load(id) ?: return null
        return save(id, data.source, data.overrides, name)
    }

    public fun duplicate(id: String): ProjectData? {
        val data = load(id) ?: return null
        return create("${data.info.name} (Kopie)", data.source, data.overrides)
    }

    public fun delete(id: String): Boolean = store.delete(id)

    /** ID des zuletzt geöffneten Projekts; `null`, wenn keines (mehr) existiert. */
    public var lastOpenedId: String?
        get() = store.lastOpenedId
        set(value) { store.lastOpenedId = value }

    // Ein Schreiber zur Zeit: Autosave (Hintergrund) und flush() beim Verlassen der App (UI-Thread) nutzen dieselbe .tmp-Datei;
    // gleichzeitig würden sie ineinander schreiben und den Inhalt vermischen.
    @Synchronized
    private fun write(id: String, name: String, source: String, overrides: Map<String, String>): ProjectInfo {
        val dir = store.dir(id)
        val updated = clock()
        store.writeText(store.contentFile(dir), source)
        store.writeMeta(dir, name, updated, overrides.mapKeys { OVERRIDE_PREFIX + it.key })
        return ProjectInfo(id, name, updated)
    }

    private companion object {
        const val OVERRIDE_PREFIX = "ov."
    }
}
