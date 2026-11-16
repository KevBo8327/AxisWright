package dev.androidcad.app

import dev.androidcad.geometry.Mesh
import dev.androidcad.viewport.SceneFrame
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MeshViewportTest {
    private class FakeShaderGl(
        private val failSecondCompile: Boolean = false,
        private val failLink: Boolean = false,
    ) : ShaderGl {
        private var nextId = 1
        val createdShaders = mutableListOf<Int>()
        val attached = mutableListOf<Pair<Int, Int>>()
        val detached = mutableListOf<Pair<Int, Int>>()
        val deletedShaders = mutableListOf<Int>()
        val deletedPrograms = mutableListOf<Int>()

        override fun createShader(type: Int): Int = (nextId++).also { createdShaders += it }
        override fun shaderSource(shader: Int, source: String) {}
        override fun compileShader(shader: Int) {}
        override fun getShaderStatus(shader: Int): Int = if (failSecondCompile && createdShaders.size == 2) 0 else 1
        override fun getShaderLog(shader: Int): String = "compile error"
        override fun deleteShader(shader: Int) { deletedShaders += shader }
        override fun createProgram(): Int = nextId++
        override fun attachShader(program: Int, shader: Int) { attached += program to shader }
        override fun linkProgram(program: Int) {}
        override fun detachShader(program: Int, shader: Int) { detached += program to shader }
        override fun getProgramStatus(program: Int): Int = if (failLink) 0 else 1
        override fun getProgramLog(program: Int): String = "link error"
        override fun deleteProgram(program: Int) { deletedPrograms += program }
    }

    @Test
    fun linkShaderProgramDetachesAndDeletesShadersAfterLinking() {
        val gl = FakeShaderGl()
        val p = linkShaderProgram(gl, "vs", "fs")
        assertEquals(2, gl.createdShaders.size)
        val expected = gl.createdShaders.map { p to it }.toSet()
        assertEquals(expected, gl.detached.toSet(), "Shader müssen nach glLinkProgram detached werden")
        assertEquals(gl.createdShaders.toSet(), gl.deletedShaders.toSet(), "Shader müssen nach glLinkProgram gelöscht werden")
        assertTrue(gl.deletedPrograms.isEmpty())
    }

    @Test
    fun linkShaderProgramCleansUpOnFailure() {
        val compileFail = FakeShaderGl(failSecondCompile = true)
        assertFailsWith<IllegalStateException> { linkShaderProgram(compileFail, "vs", "fs") }
        assertEquals(compileFail.createdShaders.toSet(), compileFail.deletedShaders.toSet())

        val linkFail = FakeShaderGl(failLink = true)
        assertFailsWith<IllegalStateException> { linkShaderProgram(linkFail, "vs", "fs") }
        assertEquals(linkFail.createdShaders.toSet(), linkFail.deletedShaders.toSet())
        assertEquals(1, linkFail.deletedPrograms.size)
    }

    @Test
    fun sceneFrameCacheReusesFrameAcrossRepeatedTapsAndRecomputesOnChange() {
        var computations = 0
        val cache = SceneFrameCache { pos, more ->
            computations++
            SceneFrame.of(pos, more)
        }
        val m1 = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), intArrayOf(0, 1, 2))
        val m2 = Mesh(floatArrayOf(0f, 0f, 0f, 20f, 0f, 0f, 0f, 20f, 0f), intArrayOf(0, 1, 2))
        val lines1 = floatArrayOf(0f, 0f, 0f, 5f, 0f, 0f)
        val lines2 = floatArrayOf(0f, 0f, 0f, 8f, 0f, 0f)

        val f1 = cache.get(m1, lines1)
        repeat(10) {
            assertSame(f1, cache.get(m1, lines1))
        }
        assertEquals(1, computations, "Wiederholte Tipps auf dasselbe Netz dürfen SceneFrame nicht neu berechnen")

        val f2 = cache.get(m1, lines2)
        assertEquals(2, computations)
        assertSame(f2, cache.get(m1, lines2))
        assertEquals(2, computations)

        cache.get(m2, lines2)
        assertEquals(3, computations)
    }

    @Test
    fun fragmentShaderUsesCapColorOnlyWhenClipEnabled() {
        // Die eigentliche GLSL-Ausführung braucht OpenGL ES auf dem Gerät; der JVM-Test prüft den Shader-Quelltext.
        val fs = MeshRenderer.FRAGMENT
        assertTrue(fs.contains("uniform int uClipEnabled;"))
        assertTrue(fs.contains("uniform vec3 uCapColor;"))
        assertFalse(
            fs.contains("gl_FrontFacing ? uColor : uCapColor"),
            "Ohne aktive Schnittebene dürfen Rückseiten nicht in Kappenfarbe gezeichnet werden",
        )
        assertTrue(
            fs.contains("uClipEnabled != 0 && !gl_FrontFacing"),
            "Kappenfarbe darf nur bei aktivem Schnitt (uClipEnabled != 0) für Rückseiten greifen",
        )
    }
}
