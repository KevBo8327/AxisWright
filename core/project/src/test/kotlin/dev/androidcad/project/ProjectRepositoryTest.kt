package dev.androidcad.project

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectRepositoryTest {
    private val dir: File = Files.createTempDirectory("projects").toFile()
    private var now = 1_000L
    private val repo = ProjectRepository(dir) { now }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun createAndLoadRoundTrip() {
        val src = "/* [Maße] */\nwidth = 60; // [20:5:120]\ncube(width);\n"
        val created = repo.create("Box", src, mapOf("width" to "75"))
        val loaded = assertNotNull(repo.load(created.info.id))
        assertEquals("Box", loaded.info.name)
        assertEquals(src, loaded.source)
        assertEquals(mapOf("width" to "75"), loaded.overrides)
        assertEquals(1_000L, loaded.info.updatedAt)
    }

    @Test
    fun unicodeAndSpecialCharactersSurvive() {
        val src = "// Größe: ä ö ü ß – 日本語 \"quoted\" \\ backslash\r\nnote = \"a=b\\nc\";\n"
        val name = "Prüfung = Größe: 1\n2"
        val p = repo.create(name, src, mapOf("txt" to "\"Zeile=1\\n2 ü\"", "k=y" to "a b"))
        val l = assertNotNull(repo.load(p.info.id))
        assertEquals(src, l.source)
        assertEquals(name, l.info.name)
        assertEquals(mapOf("txt" to "\"Zeile=1\\n2 ü\"", "k=y" to "a b"), l.overrides)
    }

    @Test
    fun saveUpdatesContentAndTimestampButKeepsName() {
        val id = repo.create("Projekt", "a", emptyMap()).info.id
        now = 5_000
        val info = repo.save(id, "b", mapOf("x" to "1"))
        assertEquals("Projekt", info.name)
        assertEquals(5_000L, info.updatedAt)
        val l = assertNotNull(repo.load(id))
        assertEquals("b", l.source); assertEquals(mapOf("x" to "1"), l.overrides)
    }

    @Test
    fun saveCanRenameAndBlankNameFallsBack() {
        val id = repo.create("Alt", "a").info.id
        assertEquals("Neu", repo.save(id, "a", emptyMap(), name = "Neu").name)
        assertEquals("Unbenannt", repo.save(id, "a", emptyMap(), name = "   ").name)
        assertEquals("X", repo.rename(id, "X")?.name)
        assertEquals("a", repo.load(id)?.source)
    }

    @Test
    fun listIsSortedByMostRecentlyUpdated() {
        now = 1; val a = repo.create("A", "a").info.id
        now = 2; val b = repo.create("B", "b").info.id
        now = 3; val c = repo.create("C", "c").info.id
        assertEquals(listOf(c, b, a), repo.list().map { it.id })
        now = 10; repo.save(a, "a2", emptyMap())
        assertEquals(listOf(a, c, b), repo.list().map { it.id })
    }

    @Test
    fun duplicateCopiesContentWithNewId() {
        val orig = repo.create("Box", "cube(1);", mapOf("w" to "3"))
        val copy = assertNotNull(repo.duplicate(orig.info.id))
        assertNotEquals(orig.info.id, copy.info.id)
        assertEquals("Box (Kopie)", copy.info.name)
        assertEquals("cube(1);", repo.load(copy.info.id)?.source)
        assertEquals(mapOf("w" to "3"), repo.load(copy.info.id)?.overrides)
        assertEquals(2, repo.list().size)
    }

    @Test
    fun deleteRemovesProjectAndResetsLastOpened() {
        val id = repo.create("A", "a").info.id
        repo.lastOpenedId = id
        assertEquals(id, repo.lastOpenedId)
        assertTrue(repo.delete(id))
        assertNull(repo.load(id))
        assertNull(repo.lastOpenedId)
        assertTrue(repo.list().isEmpty())
        assertFalse(repo.delete(id))
    }

    @Test
    fun lastOpenedIgnoresMissingProjects() {
        val id = repo.create("A", "a").info.id
        repo.lastOpenedId = id
        File(dir, id).deleteRecursively()
        assertNull(repo.lastOpenedId)
    }

    @Test
    fun invalidIdsCannotEscapeTheRoot() {
        val outside = File(dir.parentFile, "outside_${System.nanoTime()}").also { it.mkdirs() }
        try {
            File(outside, "project.scad").writeText("x")
            assertNull(repo.load("../${outside.name}"))
            assertNull(repo.load("..")); assertNull(repo.load("a/b"))
            assertFalse(repo.delete("../${outside.name}"))
            assertTrue(outside.exists())
            assertFailsWith<IllegalArgumentException> { repo.save("../x", "s", emptyMap()) }
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun brokenProjectFoldersAreSkippedInList() {
        val ok = repo.create("OK", "a").info.id
        File(dir, "deadbeef0000").mkdirs()                       // leer
        File(dir, "deadbeef0001").apply { mkdirs(); File(this, "project.scad").writeText("x") } // ohne Meta
        assertEquals(listOf(ok), repo.list().map { it.id })
    }

    @Test
    fun noTemporaryFilesRemainAfterSaving() {
        val id = repo.create("A", "a").info.id
        repeat(3) { repo.save(id, "v$it", emptyMap()) }
        assertEquals(listOf("project.properties", "project.scad"), File(dir, id).list()!!.sorted())
    }

    @Test
    fun repositoryReopensExistingData() {
        val id = repo.create("Persist", "cube(2);", mapOf("a" to "1")).info.id
        repo.lastOpenedId = id
        val again = ProjectRepository(dir)
        assertEquals(id, again.lastOpenedId)
        assertEquals("cube(2);", again.load(id)?.source)
    }
}
