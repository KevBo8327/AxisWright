package dev.androidcad.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.androidcad.geometry.GeometryKernel
import dev.androidcad.geometry.Mesh
import dev.androidcad.lang.CadLang
import dev.androidcad.lang.CadLangException
import dev.androidcad.lang.ParamSpec
import dev.androidcad.lang.RenderResult
import dev.androidcad.project.ProjectData
import dev.androidcad.project.ProjectInfo
import dev.androidcad.project.ProjectRepository
import dev.androidcad.project.UndoHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SaveState { SAVED, PENDING, ERROR }

/** Ein Schritt im Undo-Verlauf: Quelltext samt Cursor und die Customizer-Werte. */
private data class Snapshot(val text: String, val selStart: Int, val selEnd: Int, val overrides: Map<String, String>)

/**
 * Zustand der App: das geöffnete Projekt (Quelltext, Customizer-Werte), Undo/Redo-Verlauf, Autosave
 * und das letzte Auswertungsergebnis.
 *
 *  - Jede Änderung startet nach kurzer Entprellung eine neue Auswertung auf [Dispatchers.Default];
 *    eine laufende Auswertung wird dabei abgebrochen.
 *  - Änderungen werden nach [AUTOSAVE_DELAY_MS] automatisch gespeichert; [flush] schreibt sofort
 *    (z. B. in `onStop`).
 *  - Tippen und Slider-Ziehen werden im Undo-Verlauf zu je einem Schritt zusammengefasst.
 */
class CadState(
    private val repo: ProjectRepository,
    private val scope: CoroutineScope,
    defaultName: String,
    private val defaultSource: String,
    /** Name des Projekts, das nach dem Löschen des letzten neu entsteht (wird in der aktuellen Sprache festgeschrieben). */
    private val newProjectName: String = "New project",
) {
    var project by mutableStateOf(ProjectInfo("", "", 0))
        private set
    /** Skript-Projekte für Listen und Startseite; das beim ersten Start angelegte, unveränderte Beispiel fehlt darin (siehe [listed]). */
    var projects by mutableStateOf(emptyList<ProjectInfo>())
        private set
    var saveState by mutableStateOf(SaveState.SAVED)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    var editor by mutableStateOf(TextFieldValue(""))
        private set
    var params by mutableStateOf(emptyList<ParamSpec>())
        private set
    var overrides by mutableStateOf(emptyMap<String, String>())
        private set
    var result by mutableStateOf<RenderResult?>(null)
        private set
    var error by mutableStateOf<dev.androidcad.text.Msg?>(null)
        private set
    var rendering by mutableStateOf(false)
        private set
    var lastRenderMillis by mutableStateOf<Long?>(null)
        private set

    /** Manifold-Netz zum letzten erfolgreichen Ergebnis; `null`, wenn die Geometrie fehlschlug ([meshError]). */
    var mesh by mutableStateOf<Mesh?>(null)
        private set
    var meshError by mutableStateOf<String?>(null)
        private set

    private var renderJob: Job? = null
    private var saveJob: Job? = null
    // Stand der Bearbeitung: dirtyVersion zählt Änderungen (UI-Thread), savedVersion den zuletzt gesicherten. Verglichen und gesetzt
    // nur unter saveLock – so überschreibt ein verspäteter Schreibvorgang im Hintergrund nie einen neueren, den flush() schon gesichert hat
    private var dirtyVersion = 0
    @Volatile private var savedVersion = 0
    private val saveLock = Any()
    private lateinit var history: UndoHistory<Snapshot>

    init {
        val data = repo.lastOpenedId?.let(repo::load)
            ?: repo.list().firstNotNullOfOrNull { repo.load(it.id) }
            ?: repo.create(defaultName, defaultSource)
        open(data)
    }

    // ---- Bearbeiten -----------------------------------------------------------------------------

    fun onEditorChange(v: TextFieldValue) {
        val textChanged = v.text != editor.text
        editor = v
        if (!textChanged) return
        refreshParams()
        record("text")
        scheduleRender()
        markDirty()
    }

    fun insertAtCursor(snippet: String) {
        val sel = editor.selection
        onEditorChange(TextFieldValue(editor.text.replaceRange(sel.min, sel.max, snippet), TextRange(sel.min + snippet.length)))
    }

    fun setParam(name: String, literal: String) {
        if (overrides[name] == literal) return
        overrides = overrides + (name to literal)
        record("param:$name")
        scheduleRender()
        markDirty()
    }

    fun resetParams() {
        if (overrides.isEmpty()) return
        overrides = emptyMap()
        history.breakCoalescing()
        record(null)
        scheduleRender()
        markDirty()
    }

    fun renderNow() = scheduleRender(delayMs = 0)

    // ---- Undo / Redo ----------------------------------------------------------------------------

    fun undo() = history.undo()?.let(::restore)

    fun redo() = history.redo()?.let(::restore)

    private fun snapshot() = Snapshot(editor.text, editor.selection.start, editor.selection.end, overrides)

    private fun record(coalesceKey: Any?) {
        history.record(snapshot(), coalesceKey)
        updateHistoryFlags()
    }

    private fun restore(s: Snapshot) {
        val len = s.text.length
        editor = TextFieldValue(s.text, TextRange(s.selStart.coerceIn(0, len), s.selEnd.coerceIn(0, len)))
        overrides = s.overrides
        params = CadLang.parameters(s.text)
        updateHistoryFlags()
        scheduleRender(delayMs = 0)
        markDirty()
    }

    private fun updateHistoryFlags() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    // ---- Projekte -------------------------------------------------------------------------------

    fun newProject(name: String, source: String) {
        flush()
        open(repo.create(name, source))
    }

    fun openProject(id: String) {
        if (id == project.id) return
        flush()
        repo.load(id)?.let(::open)
    }

    fun duplicateProject(id: String) {
        flush()
        repo.duplicate(id)?.let(::open)
    }

    fun deleteProject(id: String) {
        val wasCurrent = id == project.id
        if (wasCurrent) {
            saveJob?.cancel()
            saveState = SaveState.SAVED
        } else flush()
        repo.delete(id)
        if (wasCurrent) {
            val next = repo.list().firstNotNullOfOrNull { repo.load(it.id) } ?: repo.create(newProjectName, "cube(10);\n")
            open(next)
        } else projects = listed()
    }

    fun renameProject(name: String) {
        project = project.copy(name = name.take(80))
        markDirty()
    }

    private fun open(data: ProjectData) {
        project = data.info
        overrides = data.overrides
        editor = TextFieldValue(data.source)
        history = UndoHistory(snapshot())
        updateHistoryFlags()
        refreshParams()
        repo.lastOpenedId = data.info.id
        projects = listed()
        saveState = SaveState.SAVED
        scheduleRender(delayMs = 0)
    }

    // ---- Speichern ------------------------------------------------------------------------------

    private fun markDirty() {
        dirtyVersion++
        saveState = SaveState.PENDING
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val version = dirtyVersion
            val id = project.id; val name = project.name; val text = editor.text; val ov = overrides
            val saved = withContext(Dispatchers.IO) { runCatching { saveIfNewer(version, id, text, ov, name)?.let { it to listed() } } }
            if (version != dirtyVersion || id != project.id) return@launch // inzwischen weiter geändert / Projekt gewechselt
            saved.onSuccess { result ->
                val (info, list) = result ?: return@onSuccess // flush() hat schon einen neueren Stand gesichert
                project = info
                projects = list
                saveState = SaveState.SAVED
            }.onFailure { saveState = SaveState.ERROR }
        }
    }

    /**
     * Alle Projekte ohne ein unverändertes Standard-Skript: das legt die App beim ersten Start selbst an (der Editor braucht immer ein
     * Projekt) – für neue Nutzer wäre es in „Zuletzt bearbeitet“ nur verwirrend. Nach der ersten Änderung erscheint es.
     */
    private fun listed(): List<ProjectInfo> = repo.list().filterNot { info ->
        repo.load(info.id)?.let { it.source == defaultSource && it.overrides.isEmpty() } == true
    }

    /** Schreibt ausstehende Änderungen sofort (synchron). Für `onStop` und Projektwechsel. */
    fun flush() {
        saveJob?.cancel()
        if (saveState == SaveState.SAVED || project.id.isEmpty()) return
        runCatching { synchronized(saveLock) { repo.save(project.id, editor.text, overrides, project.name).also { savedVersion = dirtyVersion } } }
            .onSuccess { project = it; projects = listed(); saveState = SaveState.SAVED }
            .onFailure { saveState = SaveState.ERROR }
    }

    /** Sichert [text] als Stand [version] – nur, wenn noch kein neuerer gesichert ist (sonst `null`). Für das Autosave im Hintergrund. */
    internal fun saveIfNewer(version: Int, id: String, text: String, overrides: Map<String, String>, name: String): ProjectInfo? =
        synchronized(saveLock) {
            if (version <= savedVersion) null else repo.save(id, text, overrides, name).also { savedVersion = version }
        }

    // ---- Auswertung -----------------------------------------------------------------------------

    private fun refreshParams() {
        val parsed = CadLang.parameters(editor.text)
        params = parsed
        // Nur Überschreibungen behalten, deren Parameter noch existiert.
        val names = parsed.mapTo(HashSet()) { it.name }
        overrides = overrides.filterKeys { it in names }
    }

    private fun scheduleRender(delayMs: Long = 250) {
        renderJob?.cancel()
        val src = editor.text
        val ov = overrides
        renderJob = scope.launch {
            delay(delayMs)
            rendering = true
            val started = System.nanoTime()
            val self = currentCoroutineContext()[Job]
            try {
                val (r, m) = withContext(Dispatchers.Default) {
                    val r = CadLang.render(src, ov, isCancelled = { self?.isActive == false })
                    // Der native Aufruf ist nicht unterbrechbar; vorher noch einmal auf Abbruch prüfen.
                    if (self?.isActive == false) throw CadLangException(dev.androidcad.text.Msg(dev.androidcad.lang.LangText.CANCELLED))
                    r to runCatching { GeometryKernel.build(r.root) }
                }
                result = r
                error = null
                mesh = m.getOrNull()
                meshError = m.exceptionOrNull()?.message
                lastRenderMillis = (System.nanoTime() - started) / 1_000_000
            } catch (e: CadLangException) {
                if (self?.isActive == true) error = e.toMsg()
            } finally {
                if (self?.isActive == true) rendering = false
            }
        }
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 700L
    }
}
