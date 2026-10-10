package dev.androidcad.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.androidcad.project.ProjectInfo
import java.io.File

/** Art eines Projekts: parametrisches Modell (Skizze → Körper) oder OpenSCAD-Skript. */
enum class ProjectKind(val label: String) { MODEL("Modell"), SCRIPT("Skript") }

/** Ein Eintrag der gemeinsamen Projektliste. */
data class ProjectEntry(val kind: ProjectKind, val info: ProjectInfo)

/**
 * Gemeinsame Projektverwaltung für Modelle und Skripte. Beide Arten behalten ihre eigene Ablage ([WorkspaceState] → `files/models`,
 * [CadState] → `files/projects`); dieser Hub führt sie für die Oberfläche zusammen: **eine** Liste aller Projekte (zuletzt geändert
 * zuerst), Öffnen wechselt in den passenden Editor, Neu/Umbenennen/Duplizieren/Löschen wirken auf das gerade aktive Projekt.
 *
 * Welche Art zuletzt aktiv war, wird in [stateFile] gemerkt und beim Start wieder geöffnet.
 */
class ProjectHub(val workspace: WorkspaceState, val script: CadState, private val stateFile: File) {
    var active by mutableStateOf(readActive())
        private set

    /** Alle Projekte beider Arten, zuletzt geänderte zuerst. */
    val entries: List<ProjectEntry>
        get() = merge(workspace.projects, script.projects)

    /** Das gerade bearbeitete Projekt (Art, ID – `null` bei einem noch nicht gespeicherten neuen Modell – und Name). */
    val currentName: String get() = if (active == ProjectKind.MODEL) workspace.projectName else script.project.name
    val currentId: String? get() = if (active == ProjectKind.MODEL) workspace.currentProjectId else script.project.id

    fun isCurrent(e: ProjectEntry): Boolean = e.kind == active && e.info.id == currentId

    /** Wechselt in den Editor der Art [kind] mit dessen zuletzt geöffnetem Projekt. */
    fun switchTo(kind: ProjectKind) {
        if (kind == active) return
        flushActive()
        activate(kind)
    }

    fun open(e: ProjectEntry) {
        flushActive()
        when (e.kind) {
            ProjectKind.MODEL -> workspace.openProject(e.info.id)
            ProjectKind.SCRIPT -> script.openProject(e.info.id)
        }
        activate(e.kind)
    }

    fun newModel() {
        flushActive()
        workspace.newDocument()
        activate(ProjectKind.MODEL)
    }

    fun newScript(name: String = "Neues Skript", source: String = "cube(10);\n") {
        flushActive()
        script.newProject(name, source)
        activate(ProjectKind.SCRIPT)
    }

    /** Importiert eine Datei: eine Partwright-Modelldatei als neues Modell, alles andere als OpenSCAD-Skript. */
    fun import(name: String, text: String) {
        if (dev.androidcad.model.DocumentCodec.looksLikeModel(text)) {
            flushActive()
            workspace.importModel(name, text)
            activate(ProjectKind.MODEL)
        } else {
            newScript(name, text)
        }
    }

    fun loadModelExample() {
        flushActive()
        workspace.loadPlateExample()
        activate(ProjectKind.MODEL)
    }

    fun renameCurrent(name: String) {
        val clean = name.trim().ifBlank { return }
        if (active == ProjectKind.MODEL) workspace.renameProject(clean) else script.renameProject(clean)
    }

    fun duplicate(e: ProjectEntry) {
        flushActive()
        when (e.kind) {
            ProjectKind.MODEL -> {
                if (!isCurrent(e)) workspace.openProject(e.info.id)
                workspace.duplicateProject()
            }
            ProjectKind.SCRIPT -> script.duplicateProject(e.info.id)
        }
        activate(e.kind)
    }

    /** Löscht ein Projekt dauerhaft. Ist es das aktive, bleibt der Editor seiner Art offen (mit einem anderen bzw. neuen Projekt). */
    fun delete(e: ProjectEntry) {
        when (e.kind) {
            ProjectKind.MODEL -> if (e.kind == active && e.info.id == workspace.currentProjectId) workspace.deleteProject() else workspace.deleteProjectById(e.info.id)
            ProjectKind.SCRIPT -> script.deleteProject(e.info.id)
        }
    }

    /** Liest die Projektlisten neu (beim Öffnen des Projektmenüs; das Autosave hält sie nicht laufend aktuell). */
    fun refreshProjects() = workspace.refreshProjects()

    /** Sichert beide Editoren (z. B. in `onStop`). */
    fun flush() {
        workspace.flush()
        script.flush()
    }

    private fun flushActive() = if (active == ProjectKind.MODEL) workspace.flush() else script.flush()

    private fun activate(kind: ProjectKind) {
        active = kind
        runCatching { stateFile.writeText(kind.name) }
    }

    private fun readActive(): ProjectKind =
        runCatching { ProjectKind.valueOf(stateFile.readText().trim()) }.getOrDefault(ProjectKind.MODEL)

    companion object {
        /** Führt die Listen beider Arten zusammen: zuletzt geändert zuerst, bei Gleichstand nach Name. */
        fun merge(models: List<ProjectInfo>, scripts: List<ProjectInfo>): List<ProjectEntry> =
            (models.map { ProjectEntry(ProjectKind.MODEL, it) } + scripts.map { ProjectEntry(ProjectKind.SCRIPT, it) })
                .sortedWith(compareByDescending<ProjectEntry> { it.info.updatedAt }.thenBy { it.info.name.lowercase() })
    }
}
