package dev.androidcad.project

import java.io.File

public data class ModelData(val info: ProjectInfo, val content: String)

/**
 * Dateibasierte Ablage der Skizzen-/Körpermodelle unterhalb von [root]:
 * ```
 * root/<id>/model.json         Modell (UTF-8, Format siehe DocumentCodec in :core:model)
 * root/<id>/model.json.bak     vorheriger Stand (eine Sicherung)
 * root/<id>/meta.properties    name, updatedAt
 * root/<id>/thumb.png          Vorschaubild (von der App geschrieben, optional)
 * root/last                    id des zuletzt geöffneten Modells
 * ```
 * Die Ablage kennt den Inhalt nicht (nur Text) und prüft ihn nicht; ein unlesbares Modell überschreibt sie nie von selbst.
 * Schreibzugriffe sind atomar. Plain-JVM, ohne Gerät testbar.
 */
public class ModelRepository(
    root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = FolderStore(root, content = "model.json", meta = "meta.properties")

    /** Alle Modelle, zuletzt geänderte zuerst. Ordner ohne lesbare Metadaten werden übersprungen. */
    public fun list(): List<ProjectInfo> = store.list()

    public fun create(name: String, content: String): ModelData =
        ModelData(write(store.newId(), name.ifBlank { "Unbenannt" }, content), content)

    public fun load(id: String): ModelData? {
        val dir = store.dirOf(id) ?: return null
        val info = store.readInfo(dir) ?: return null
        val content = store.readText(store.contentFile(dir)) ?: return null
        return ModelData(info, content)
    }

    /** Inhalt der Sicherung (vorheriger Stand) – für die Wiederherstellung, wenn [load] unlesbaren Inhalt liefert. */
    public fun loadBackup(id: String): String? = store.dirOf(id)?.let { store.readText(File(it, BACKUP)) }

    /** Speichert den Inhalt und (optional) einen neuen Namen; der bisherige Inhalt wird zur Sicherung. */
    @Synchronized // liest den bisherigen Namen: zusammen mit dem Schreiben, sonst stört das Lesen ein gleichzeitiges Ersetzen
    public fun save(id: String, content: String, name: String? = null): ProjectInfo {
        val dir = store.dirOf(id) ?: throw IllegalArgumentException("Ungültige Modell-ID: $id")
        val currentName = name ?: store.readInfo(dir)?.name ?: "Unbenannt"
        return write(id, currentName.ifBlank { "Unbenannt" }, content)
    }

    public fun rename(id: String, name: String): ProjectInfo? {
        val dir = store.dirOf(id) ?: return null
        val content = store.readText(store.contentFile(dir)) ?: return null
        return save(id, content, name)
    }

    public fun duplicate(id: String): ModelData? {
        val data = load(id) ?: return null
        return create("${data.info.name} (Kopie)", data.content)
    }

    public fun delete(id: String): Boolean = store.delete(id)

    /** Datei des Vorschaubilds von [id] (muss nicht existieren); `null` für ein unbekanntes Modell. */
    public fun thumbnailFile(id: String): File? = store.dirOf(id)?.let { File(it, "thumb.png") }

    /** ID des zuletzt geöffneten Modells; `null`, wenn keines (mehr) existiert. */
    public var lastOpenedId: String?
        get() = store.lastOpenedId
        set(value) { store.lastOpenedId = value }

    // Ein Schreiber zur Zeit: Autosave (Hintergrund) und flush() beim Verlassen der App (UI-Thread) nutzen dieselbe .tmp-Datei;
    // gleichzeitig würden sie ineinander schreiben und den Inhalt vermischen.
    @Synchronized
    private fun write(id: String, name: String, content: String): ProjectInfo {
        val dir = store.dir(id)
        val updated = clock()
        val target = store.contentFile(dir)
        // Sicherung des bisherigen Stands, nur wenn sich der Inhalt ändert (Autosave schreibt oft denselben Stand)
        if (target.isFile && store.readText(target) != content) {
            atomicWrite(File(dir, BACKUP)) { out -> target.inputStream().use { it.copyTo(out) } }
        }
        store.writeText(target, content)
        store.writeMeta(dir, name, updated)
        return ProjectInfo(id, name, updated)
    }

    private companion object {
        const val BACKUP = "model.json.bak"
    }
}
