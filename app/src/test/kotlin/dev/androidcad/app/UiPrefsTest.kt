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

    @Test
    fun theThemeStartsDarkAndIsRemembered() {
        val file = File.createTempFile("uiprefs", ".properties").also { it.delete() }
        try {
            assertEquals(ThemeMode.DARK, UiPrefs(file).themeMode, "Standard: dunkel")
            UiPrefs(file).themeMode = ThemeMode.LIGHT
            assertEquals(ThemeMode.LIGHT, UiPrefs(file).themeMode)
            UiPrefs(file).themeMode = ThemeMode.SYSTEM
            assertEquals(ThemeMode.SYSTEM, UiPrefs(file).themeMode)
        } finally {
            file.delete()
        }
    }
}
