package dev.androidcad.app

import dev.androidcad.brep.BrepText
import dev.androidcad.geometry.GeometryText
import dev.androidcad.lang.LangText
import dev.androidcad.model.BasePlane
import dev.androidcad.model.ModelText
import dev.androidcad.sketch.SketchText
import dev.androidcad.sketchedit.ActionKind
import dev.androidcad.sketchedit.ArcMode
import dev.androidcad.sketchedit.CircleMode
import dev.androidcad.sketchedit.CornerMode
import dev.androidcad.sketchedit.EditText
import dev.androidcad.sketchedit.LineMode
import dev.androidcad.sketchedit.PolygonMode
import dev.androidcad.sketchedit.RectMode
import org.junit.Test
import java.io.File
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vollständigkeitsprüfung aller Übersetzungen (EN = Grundsprache in values/, DE in values-de/):
 * - Jeder Schlüssel der Kernmodule (Msg / Enum) hat eine Ressourcen-Zuordnung in CoreText.kt.
 * - Jede übersetzbare Zeichenkette in values/ ist in values-de/ vorhanden (und umgekehrt).
 * - Platzhalter (%1$s, %2$d usw.) stimmen zwischen EN und DE überein.
 */
class TranslationTest {
    @Test
    fun everyCoreEnumHasResourceMapping() {
        val allEnums: List<Enum<*>> = SketchText.entries +
            EditText.entries +
            ModelText.entries +
            LangText.entries +
            BrepText.entries +
            GeometryText.entries +
            ActionKind.entries +
            RectMode.entries +
            CircleMode.entries +
            PolygonMode.entries +
            LineMode.entries +
            ArcMode.entries +
            CornerMode.entries +
            BasePlane.entries

        for (e in allEnums) {
            val id = res(e)
            assertTrue(id != 0, "Ressource für ${e::class.simpleName}.${e.name} fehlt")
        }
    }

    @Test
    fun stringResourcesMatchBetweenEnglishAndGerman() {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val valuesDir = File(resDir, "values")
        val valuesDeDir = File(resDir, "values-de")

        val enStrings = parseStrings(valuesDir)
        val deStrings = parseStrings(valuesDeDir)

        // Jede nicht als translatable="false" markierte englische Ressource muss in values-de stehen
        for ((name, str) in enStrings) {
            if (!str.translatable) continue
            assertTrue(name in deStrings, "Deutsche Übersetzung fehlt für: $name in ${str.file}")
            val deStr = deStrings.getValue(name)
            val enPlaceholders = extractPlaceholders(str.value)
            val dePlaceholders = extractPlaceholders(deStr.value)
            assertEquals(
                enPlaceholders, dePlaceholders,
                "Platzhalter für '$name' stimmen nicht überein: EN=${str.value} DE=${deStr.value}",
            )
        }

        // Kein überflüssiger deutscher Eintrag ohne englisches Gegenstück
        for (name in deStrings.keys) {
            assertTrue(name in enStrings, "Überflüssige deutsche Übersetzung ohne englisches Original: $name")
        }
    }

    private data class StrEntry(val value: String, val translatable: Boolean, val file: String)

    private fun parseStrings(dir: File): Map<String, StrEntry> {
        val map = mutableMapOf<String, StrEntry>()
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        for (f in dir.listFiles { _, name -> name.startsWith("strings") && name.endsWith(".xml") } ?: emptyArray()) {
            val doc = builder.parse(f)
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val node = strings.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                val translatable = node.attributes.getNamedItem("translatable")?.nodeValue != "false"
                val text = node.textContent
                map[name] = StrEntry(text, translatable, f.name)
            }
        }
        return map
    }

    private fun extractPlaceholders(text: String): List<String> {
        val pattern = Pattern.compile("%(?:(\\d+)\\$)?([-#+ 0,(<]*)?(\\d+)?(?:\\.\\d+)?([a-zA-Z%])")
        val matcher = pattern.matcher(text)
        val res = mutableListOf<String>()
        while (matcher.find()) {
            val token = matcher.group()
            if (token != "%%") res.add(token)
        }
        return res
    }
}
