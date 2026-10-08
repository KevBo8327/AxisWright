package dev.androidcad.app

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** Jede gelistete Fremdbibliothek hat ihre Lizenztexte in den Assets – sonst zeigte die Lizenzseite „fehlt“. */
class LicensesTest {
    @Test
    fun everyListedLicenseTextIsShipped() {
        val dir = listOf(File("src/main/assets/licenses"), File("app/src/main/assets/licenses")).first { it.isDirectory }
        for (t in THIRD_PARTY) for (f in t.files) {
            val file = File(dir, f)
            assertTrue(file.isFile && file.length() > 200, "${t.name}: $f fehlt oder ist leer")
        }
        assertTrue(THIRD_PARTY.any { it.license.contains("LGPL") && it.note != null }, "LGPL-Hinweis zum Austausch der Bibliotheken")
    }
}
