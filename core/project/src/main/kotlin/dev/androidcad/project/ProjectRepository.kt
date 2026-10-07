package dev.androidcad.project

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.UUID

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
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        root.mkdirs()
    }

    private companion object {
        const val SOURCE = "project.scad"
        const val META = "project.properties"
        val ID = Regex("[a-z0-9]{4,32}")
        const val OVERRIDE_PREFIX = "ov."
    }

    /** Alle Projekte, zuletzt geänderte zuerst. Beschädigte Ordner werden übersprungen. */
    public fun list(): List<ProjectInfo> =
        (root.listFiles { f -> f.isDirectory && ID.matches(f.name) } ?: emptyArray())
            .mapNotNull { readInfo(it) }
            .sortedWith(compareByDescending<ProjectInfo> { it.updatedAt }.thenBy { it.name })

    public fun create(name: String, source: String, overrides: Map<String, String> = emptyMap()): ProjectData {
        var id: String
        do id = UUID.randomUUID().toString().replace("-", "").take(12) while (File(root, id).exists())
        File(root, id).mkdirs()
        val info = write(id, name.ifBlank { "Unbenannt" }, source, overrides)
        return ProjectData(info, source, overrides)
    }

    public fun load(id: String): ProjectData? {
        val dir = dirOf(id) ?: return null
        val info = readInfo(dir) ?: return null
        val source = File(dir, SOURCE).takeIf { it.isFile }?.readText(StandardCharsets.UTF_8) ?: return null
        val props = readProps(File(dir, META)) ?: return null
        val overrides = props.stringPropertyNames()
            .filter { it.startsWith(OVERRIDE_PREFIX) }
            .associate { it.removePrefix(OVERRIDE_PREFIX) to props.getProperty(it) }
        return ProjectData(info, source, overrides)
    }

    /** Speichert Quelltext, Parameterwerte und (optional) einen neuen Namen. */
    public fun save(id: String, source: String, overrides: Map<String, String>, name: String? = null): ProjectInfo {
        val dir = dirOf(id) ?: throw IllegalArgumentException("Ungültige Projekt-ID: $id")
        val currentName = name ?: readInfo(dir)?.name ?: "Unbenannt"
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

    public fun delete(id: String): Boolean {
        val dir = dirOf(id) ?: return false
        if (lastOpenedId == id) lastOpenedId = null
        return dir.deleteRecursively()
    }

    /** ID des zuletzt geöffneten Projekts; `null`, wenn keines (mehr) existiert. */
    public var lastOpenedId: String?
        get() = File(root, "last").takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)?.trim()
            ?.takeIf { ID.matches(it) && File(root, it).isDirectory }
        set(value) {
            val f = File(root, "last")
            if (value == null) f.delete() else atomicWrite(f) { it.write(value.toByteArray(StandardCharsets.UTF_8)) }
        }

    // ---- intern -------------------------------------------------------------------------------

    private fun dirOf(id: String): File? = if (ID.matches(id)) File(root, id).takeIf { it.isDirectory } else null

    private fun write(id: String, name: String, source: String, overrides: Map<String, String>): ProjectInfo {
        val dir = File(root, id)
        val updated = clock()
        atomicWrite(File(dir, SOURCE)) { it.write(source.toByteArray(StandardCharsets.UTF_8)) }
        val props = Properties()
        props["name"] = name
        props["updatedAt"] = updated.toString()
        overrides.forEach { (k, v) -> props[OVERRIDE_PREFIX + k] = v }
        atomicWrite(File(dir, META)) { out -> out.bufferedWriter(StandardCharsets.UTF_8).also { props.store(it, null) }.flush() }
        return ProjectInfo(id, name, updated)
    }

    private fun readProps(f: File): Properties? = try {
        f.takeIf { it.isFile }?.bufferedReader(StandardCharsets.UTF_8)?.use { r -> Properties().also { it.load(r) } }
    } catch (_: IOException) {
        null
    }

    private fun readInfo(dir: File): ProjectInfo? {
        val props = readProps(File(dir, META)) ?: return null
        if (!File(dir, SOURCE).isFile) return null
        val updated = props.getProperty("updatedAt")?.toLongOrNull() ?: return null
        return ProjectInfo(dir.name, props.getProperty("name") ?: "Unbenannt", updated)
    }
}
