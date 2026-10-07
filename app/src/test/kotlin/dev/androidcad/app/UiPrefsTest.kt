package dev.androidcad.app

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiPrefsTest {
    @Test
    fun theTreeStartsCollapsedAndRemembersTheChoice() {
        val file = File.createTempFile("uiprefs", ".properties").also { it.delete() }
        try {
            assertFalse(UiPrefs(file).historyOpen, "Standard: geschlossen")
            UiPrefs(file).historyOpen = true
            assertTrue(UiPrefs(file).historyOpen, "nach einem Neustart wieder offen")
            UiPrefs(file).historyOpen = false
            assertFalse(UiPrefs(file).historyOpen)
            assertFalse(UiPrefs().historyOpen, "ohne Datei: nur im Speicher")
        } finally {
            file.delete()
        }
    }
}
