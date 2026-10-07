package dev.androidcad.project

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Schreibt über eine temporäre Datei und benennt sie um, damit ein Abbruch keine halbe Datei hinterlässt.
 * Vor dem Umbenennen werden die Daten mit `fsync` auf den Datenträger gezwungen: Ohne das kann nach Stromausfall oder
 * hartem Beenden die umbenannte Datei leer sein (Rename wird vor den Daten festgeschrieben).
 */
internal fun atomicWrite(target: File, body: (OutputStream) -> Unit) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        body(out)
        out.flush()
        out.fd.sync()
    }
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
