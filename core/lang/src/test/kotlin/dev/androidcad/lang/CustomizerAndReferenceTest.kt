package dev.androidcad.lang

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CustomizerAndReferenceTest {
    @Test
    fun sliderChoicesToggleAndGroups() {
        val src = """
            /* [Maße] */
            // Breite in mm
            width = 40; // [10:5:100]
            ratio = 0.5; // [0:1]
            name = "mid"; // [low, mid, high]
            mode = 2; // [1:Klein, 2:Mittel, 3:Groß]
            show = true;
            offset_v = [1, 2, 3];
            /* [Hidden] */
            secret = 1;
            module m() {}
            after = 5;
        """.trimIndent()
        val p = CadLang.parameters(src).associateBy { it.name }

        assertEquals(setOf("width", "ratio", "name", "mode", "show", "offset_v"), p.keys)
        assertEquals("Maße", p.getValue("width").group)
        assertEquals("Breite in mm", p.getValue("width").description)
        assertEquals(ParamKind.Slider(10.0, 100.0, 5.0, true), p.getValue("width").kind)
        assertEquals(0.01, assertIs<ParamKind.Slider>(p.getValue("ratio").kind).step, 1e-12)
        val names = assertIs<ParamKind.Choices>(p.getValue("name").kind)
        assertEquals(listOf("\"low\"", "\"mid\"", "\"high\""), names.options.map { it.literal })
        val modes = assertIs<ParamKind.Choices>(p.getValue("mode").kind)
        assertEquals(listOf(Choice("1", "Klein"), Choice("2", "Mittel"), Choice("3", "Groß")), modes.options)
        assertEquals(ParamKind.Toggle, p.getValue("show").kind)
        assertEquals(3, assertIs<ParamKind.VectorField>(p.getValue("offset_v").kind).size)
    }

    private fun referenceSource(): String? =
        listOf("../../samples/streaming_halterung.scad", "samples/streaming_halterung.scad")
            .map(::File).firstOrNull { it.exists() }?.readText()

    @Test
    fun referenceModelCustomizerParametersAreRecognised() {
        val src = referenceSource() ?: return
        val params = CadLang.parameters(src).associateBy { it.name }
        val mode = assertIs<ParamKind.Choices>(params.getValue("mode").kind)
        assertEquals(10, mode.options.size)
        assertEquals("\"assembly\"", mode.options.first().literal)
        assertEquals(ParamKind.Slider(-60.0, 90.0, 15.0, true), params.getValue("joint1_angle").kind)
        assertEquals(ParamKind.Slider(58.0, 98.0, 1.0, true), params.getValue("phone_clamp_width").kind)
    }

    @Test
    fun referenceModelParsesAndEvaluatesInEveryMode() {
        val src = referenceSource() ?: return
        val modes = listOf("assembly", "print_layout", "chest_plate", "arm", "phone_base", "phone_slider", "phone_bracket", "joint_bolt", "joint_nut", "clamp_screw")
        for (m in modes) {
            val r = CadLang.render(src, mapOf("mode" to "\"$m\""))
            val unsupported = r.warnings.filter { it.key == LangText.UNKNOWN_MODULE || it.key == LangText.UNKNOWN_FUNCTION }
            assertTrue(unsupported.isEmpty(), "Modus $m: $unsupported")
            assertTrue(countLeaves(r.root) > 0, "Modus $m liefert keine Geometrie")
        }
    }

    private fun countLeaves(n: Node): Int = when (n) {
        is Group -> n.children.sumOf(::countLeaves)
        is Transform -> n.children.sumOf(::countLeaves)
        is Boolean3D -> n.children.sumOf(::countLeaves)
        is Hull -> n.children.sumOf(::countLeaves)
        is Minkowski -> n.children.sumOf(::countLeaves)
        is LinearExtrude -> n.children.sumOf(::countLeaves)
        is RotateExtrude -> n.children.sumOf(::countLeaves)
        is Offset -> n.children.sumOf(::countLeaves)
        is Colored -> n.children.sumOf(::countLeaves)
        else -> 1
    }
}
