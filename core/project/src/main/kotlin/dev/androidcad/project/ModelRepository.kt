package dev.androidcad.project

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.UUID

public data class ModelData(val info: ProjectInfo, val content: String)

/**
 * Dateibasierte Ablage der Skizzen-/Körpermodelle unterhalb von [root]:
 * ```
 * root/<id>/model.json         Modell (UTF-8, Format siehe DocumentCodec in :core:model)
 * root/<id>/model.json.bak     vorheriger Stand (eine Sicherung)
 * root/<id>/meta.properties    name, updatedAt
 * root/last                    id des zuletzt geöffneten Modells
 * ```
 * Die Ablage kennt den Inhalt nicht (nur Text) und prüft ihn nicht; ein unlesbares Modell überschreibt sie nie von selbst.
 * Schreibzugriffe sind atomar. Plain-JVM, ohne Gerät testbar.
 */
public class ModelRepository(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        root.mkdirs()
    }

    private companion object {
        const val CONTENT = "model.json"
        const val BACKUP = "model.json.bak"
        const val META = "meta.properties"
        val ID = Regex("[a-z0-9]{4,32}")
    }

    /** Alle Modelle, zuletzt geänderte zuerst. Ordner ohne lesbare Metadaten werden übersprungen. */
    public fun list(): List<ProjectInfo> =
        (root.listFiles { f -> f.isDirectory && ID.matches(f.name) } ?: emptyArray())
            .mapNotNull { readInfo(it) }
            .sortedWith(compareByDescending<ProjectInfo> { it.updatedAt }.thenBy { it.name })

    public fun create(name: String, content: String): ModelData {
        var id: String
        do id = UUID.randomUUID().toString().replace("-", "").take(12) while (File(root, id).exists())
        File(root, id).mkdirs()
        return ModelData(write(id, name.ifBlank { "Unbenannt" }, content), content)
    }

    public fun load(id: String): ModelData? {
        val dir = dirOf(id) ?: return null
        val info = readInfo(dir) ?: return null
        val content = readOrNull(File(dir, CONTENT)) ?: return null
        return ModelData(info, content)
    }

    /** Inhalt der Sicherung (vorheriger Stand) – für die Wiederherstellung, wenn [load] unlesbaren Inhalt liefert. */
    public fun loadBackup(id: String): String? = dirOf(id)?.let { readOrNull(File(it, BACKUP)) }

    /** Speichert den Inhalt und (optional) einen neuen Namen; der bisherige Inhalt wird zur Sicherung. */
    public fun save(id: String, content: String, name: String? = null): ProjectInfo {
        val dir = dirOf(id) ?: throw IllegalArgumentException("Ungültige Modell-ID: $id")
        val currentName = name ?: readInfo(dir)?.name ?: "Unbenannt"
        return write(id, currentName.ifBlank { "Unbenannt" }, content)
    }

    public fun rename(id: String, name: String): ProjectInfo? {
        val dir = dirOf(id) ?: return null
        val content = readOrNull(File(dir, CONTENT)) ?: return null
        return save(id, content, name)
    }

    public fun duplicate(id: String): ModelData? {
        val data = load(id) ?: return null
        return create("${data.info.name} (Kopie)", data.content)
    }

    public fun delete(id: String): Boolean {
        val dir = dirOf(id) ?: return false
        if (lastOpenedId == id) lastOpenedId = null
        return dir.deleteRecursively()
    }

    /** ID des zuletzt geöffneten Modells; `null`, wenn keines (mehr) existiert. */
    public var lastOpenedId: String?
        get() = readOrNull(File(root, "last"))?.trim()?.takeIf { ID.matches(it) && File(root, it).isDirectory }
        set(value) {
            val f = File(root, "last")
            if (value == null) f.delete() else atomicWrite(f) { it.write(value.toByteArray(StandardCharsets.UTF_8)) }
        }

    // ---- intern -------------------------------------------------------------------------------

    private fun dirOf(id: String): File? = if (ID.matches(id)) File(root, id).takeIf { it.isDirectory } else null

    private fun readOrNull(f: File): String? = try {
        f.takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)
    } catch (_: IOException) {
        null
    }

    // Ein Schreiber zur Zeit: Autosave (Hintergrund) und flush() beim Verlassen der App (UI-Thread) nutzen dieselbe .tmp-Datei;
    // gleichzeitig würden sie ineinander schreiben und den Inhalt vermischen.
    @Synchronized
    private fun write(id: String, name: String, content: String): ProjectInfo {
        val dir = File(root, id)
        val updated = clock()
        val target = File(dir, CONTENT)
        // Sicherung des bisherigen Stands, nur wenn sich der Inhalt ändert (Autosave schreibt oft denselben Stand)
        if (target.isFile && readOrNull(target) != content) {
            atomicWrite(File(dir, BACKUP)) { out -> target.inputStream().use { it.copyTo(out) } }
        }
        atomicWrite(target) { it.write(content.toByteArray(StandardCharsets.UTF_8)) }
        val props = Properties()
        props["name"] = name
        props["updatedAt"] = updated.toString()
        atomicWrite(File(dir, META)) { out -> out.bufferedWriter(StandardCharsets.UTF_8).also { props.store(it, null) }.flush() }
        return ProjectInfo(id, name, updated)
    }

    private fun readInfo(dir: File): ProjectInfo? {
        val props = try {
            File(dir, META).takeIf { it.isFile }?.bufferedReader(StandardCharsets.UTF_8)?.use { r -> Properties().also { it.load(r) } }
        } catch (_: IOException) {
            null
        } ?: return null
        if (!File(dir, CONTENT).isFile) return null
        val updated = props.getProperty("updatedAt")?.toLongOrNull() ?: return null
        return ProjectInfo(dir.name, props.getProperty("name") ?: "Unbenannt", updated)
    }
}
