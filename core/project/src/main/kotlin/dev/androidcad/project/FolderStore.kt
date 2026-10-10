package dev.androidcad.project

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.UUID

/**
 * Gemeinsamer Unterbau der Ablagen ([ModelRepository], [ProjectRepository]): je Projekt ein Ordner `root/<id>/` mit einer
 * Inhaltsdatei [content] und Metadaten [meta] (Properties: name, updatedAt, weitere Schlüssel nach Bedarf), dazu `root/last` mit
 * der ID des zuletzt geöffneten Projekts. Schreibzugriffe sind atomar ([atomicWrite]).
 */
internal class FolderStore(private val root: File, private val content: String, private val meta: String) {
    init {
        root.mkdirs()
    }

    /** Alle Projekte, zuletzt geänderte zuerst. Ordner ohne lesbare Metadaten oder ohne Inhalt werden übersprungen. */
    fun list(): List<ProjectInfo> =
        (root.listFiles { f -> f.isDirectory && ID.matches(f.name) } ?: emptyArray())
            .mapNotNull { readInfo(it) }
            .sortedWith(compareByDescending<ProjectInfo> { it.updatedAt }.thenBy { it.name })

    /** Legt einen neuen, leeren Projektordner an und liefert seine ID. */
    fun newId(): String {
        var id: String
        do id = UUID.randomUUID().toString().replace("-", "").take(12) while (File(root, id).exists())
        File(root, id).mkdirs()
        return id
    }

    /** Ordner des Projekts [id]; `null` bei ungültiger ID (z. B. `../`) oder wenn es ihn nicht gibt. */
    fun dirOf(id: String): File? = if (ID.matches(id)) File(root, id).takeIf { it.isDirectory } else null

    fun dir(id: String): File = File(root, id)

    fun contentFile(dir: File): File = File(dir, content)

    fun readText(f: File): String? = try {
        f.takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)
    } catch (_: IOException) {
        null
    }

    fun readMeta(dir: File): Properties? = try {
        File(dir, meta).takeIf { it.isFile }?.bufferedReader(StandardCharsets.UTF_8)?.use { r -> Properties().also { it.load(r) } }
    } catch (_: IOException) {
        null
    }

    fun readInfo(dir: File): ProjectInfo? {
        val props = readMeta(dir) ?: return null
        if (!contentFile(dir).isFile) return null
        val updated = props.getProperty("updatedAt")?.toLongOrNull() ?: return null
        return ProjectInfo(dir.name, props.getProperty("name") ?: "Unbenannt", updated)
    }

    fun writeText(f: File, text: String) = atomicWrite(f) { it.write(text.toByteArray(StandardCharsets.UTF_8)) }

    /** Schreibt die Metadaten: [name], [updated] und weitere Einträge [extra]. */
    fun writeMeta(dir: File, name: String, updated: Long, extra: Map<String, String> = emptyMap()) {
        val props = Properties()
        props["name"] = name
        props["updatedAt"] = updated.toString()
        extra.forEach { (k, v) -> props[k] = v }
        atomicWrite(File(dir, meta)) { out -> out.bufferedWriter(StandardCharsets.UTF_8).also { props.store(it, null) }.flush() }
    }

    fun delete(id: String): Boolean {
        val dir = dirOf(id) ?: return false
        if (lastOpenedId == id) lastOpenedId = null
        return dir.deleteRecursively()
    }

    /** ID des zuletzt geöffneten Projekts; `null`, wenn keines (mehr) existiert. */
    var lastOpenedId: String?
        get() = readText(File(root, "last"))?.trim()?.takeIf { ID.matches(it) && File(root, it).isDirectory }
        set(value) {
            val f = File(root, "last")
            if (value == null) f.delete() else writeText(f, value)
        }

    private companion object {
        val ID = Regex("[a-z0-9]{4,32}")
    }
}
