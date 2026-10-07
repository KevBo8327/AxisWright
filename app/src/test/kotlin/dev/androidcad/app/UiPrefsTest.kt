package dev.androidcad.app

import org.junit.Test
import java.io.File
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketchedit.ArcMode
import dev.androidcad.sketchedit.CircleMode
import dev.androidcad.sketchedit.RectMode
import dev.androidcad.sketchedit.SketchEditor
import kotlin.test.assertEquals
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

    @Test
    fun drawVariantsSurviveARestartAndUnknownValuesAreIgnored() {
        val file = File.createTempFile("uiprefs", ".properties").also { it.delete() }
        try {
            UiPrefs(file).putText("sketch.rect", "CENTER")
            UiPrefs(file).putText("sketch.circle", "gibt es nicht")
            UiPrefs(file).putText("sketch.arc", "TANGENT")
            val e = SketchEditor(Sketch())
            applyDrawModes(e, UiPrefs(file))
            assertEquals(RectMode.CENTER, e.rectMode)
            assertEquals(CircleMode.CENTER, e.circleMode, "unbekannt: Standard")
            assertEquals(ArcMode.TANGENT, e.arcMode)
        } finally {
            file.delete()
        }
    }
}
