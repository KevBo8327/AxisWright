package dev.androidcad.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Schreibt einen Export (STL, 3MF, STEP, Projekt, Skript) an den im Dateifenster gewählten Ort. [bytes] läuft im Hintergrund.
 * Jeder Fehler – Speicher voll, Cloud-Ziel nicht erreichbar, Geometriekern – wird zur Meldung statt zum Absturz; [cleanup] läuft immer.
 */
internal fun CoroutineScope.writeExport(context: Context, uri: Uri, cleanup: () -> Unit = {}, bytes: () -> ByteArray) {
    launch(Dispatchers.IO) {
        try {
            val data = bytes()
            val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("Ziel lässt sich nicht öffnen")
            out.use { it.write(data) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Export fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        } finally {
            cleanup()
        }
    }
}
