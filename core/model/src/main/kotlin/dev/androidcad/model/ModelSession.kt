package dev.androidcad.model

import dev.androidcad.text.Msg
import dev.androidcad.project.ModelRepository
import dev.androidcad.project.ProjectInfo

/** Ergebnis von [ModelSession.open]/[ModelSession.restoreLast]: entweder ein Dokument oder nur ein Hinweis, warum keines geöffnet wurde. */
class OpenResult(val document: Document?, val notice: Msg?)

/**
 * Das aktuell bearbeitete Modell und seine Ablage. Verbindet [Document]/[DocumentCodec] mit dem [ModelRepository]:
 * Wiederherstellen beim Start, Speichern, Öffnen, Umbenennen, Duplizieren, Löschen.
 *
 * Ein neues Modell wird erst beim ersten Speichern angelegt (kein Müll durch leere Projekte). Ein unlesbares Modell
 * wird nie überschrieben: [open] öffnet dann die Sicherung oder gar nichts und lässt die Datei unberührt.
 * Nicht thread-sicher; der Aufrufer serialisiert.
 */
class ModelSession(
    private val repo: ModelRepository,
    /** Grundname neuer Modelle („Model“ → „Model 1“, „Model 2“ …); die App gibt ihn in der Gerätesprache vor. */
    private val baseName: String = "Model",
) {
    var currentId: String? = null
        private set
    var name: String = "$baseName 1"
        private set

    fun list(): List<ProjectInfo> = repo.list()

    /** Vorschaubild-Datei des Modells [id] (siehe [ModelRepository.thumbnailFile]). */
    fun thumbnailFile(id: String): java.io.File? = repo.thumbnailFile(id)

    /** Öffnet das zuletzt geöffnete Modell; `null`, wenn es keines gibt (Neustart mit leerem Modell). */
    fun restoreLast(): OpenResult? = repo.lastOpenedId?.let(::open)

    fun open(id: String): OpenResult {
        val data = repo.load(id) ?: return OpenResult(null, Msg(ModelText.MODEL_NOT_FOUND))
        try {
            return adopt(data.info, DocumentCodec.decode(data.content), notice = null)
        } catch (e: Exception) { // nicht nur JSON-Fehler: ein unlesbares Modell darf die App nie beenden
            val backup = repo.loadBackup(id)?.let { runCatching { DocumentCodec.decode(it) }.getOrNull() }
                ?: return OpenResult(null, Msg(ModelText.MODEL_DAMAGED, data.info.name, e.message.orEmpty()))
            return adopt(data.info, backup, notice = Msg(ModelText.MODEL_RESTORED_FROM_BACKUP, data.info.name))
        }
    }

    private fun adopt(info: ProjectInfo, doc: Document, notice: Msg?): OpenResult {
        currentId = info.id
        name = info.name
        repo.lastOpenedId = info.id
        return OpenResult(doc, notice)
    }

    /** Beginnt ein neues, noch nicht gespeichertes Modell. */
    fun startNew() {
        currentId = null
        name = freshName()
    }

    /** Speichert [doc]; legt das Modell beim ersten Mal an. */
    fun save(doc: Document) {
        val text = DocumentCodec.encode(doc)
        val id = currentId
        if (id == null) {
            val created = repo.create(name, text)
            currentId = created.info.id
            repo.lastOpenedId = created.info.id
        } else {
            repo.save(id, text)
        }
    }

    /**
     * Schreibt den schon kodierten Stand [text] in das bereits angelegte Modell [id]. Ändert den Sitzungszustand nicht und darf deshalb
     * auf einem Hintergrund-Thread laufen (Autosave); das erste Speichern eines neuen Modells geht über [save].
     */
    fun write(id: String, text: String) {
        repo.save(id, text)
    }

    fun rename(newName: String) {
        val clean = newName.trim().ifBlank { return }
        name = clean
        currentId?.let { repo.rename(it, clean) }
    }

    /** Kopiert das gespeicherte Modell und macht die Kopie zum aktuellen. Der Aufrufer speichert vorher. */
    fun duplicate(): OpenResult {
        val id = currentId ?: return OpenResult(null, Msg(ModelText.SAVE_FIRST))
        val copy = repo.duplicate(id) ?: return OpenResult(null, Msg(ModelText.COPY_FAILED))
        return open(copy.info.id)
    }

    /** Löscht das aktuelle Modell dauerhaft und beginnt ein neues. */
    fun deleteCurrent() {
        currentId?.let { repo.delete(it) }
        startNew()
    }

    fun delete(id: String) {
        repo.delete(id)
        if (id == currentId) startNew()
    }

    private fun freshName(): String {
        val taken = repo.list().map { it.name }.toSet()
        var n = repo.list().size + 1
        while ("$baseName $n" in taken) n++
        return "$baseName $n"
    }
}
