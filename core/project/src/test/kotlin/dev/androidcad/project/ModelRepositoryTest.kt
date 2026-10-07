package dev.androidcad.project

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRepositoryTest {
    private lateinit var dir: File
    private var now = 1000L
    private lateinit var repo: ModelRepository

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("models").toFile()
        repo = ModelRepository(dir) { now++ }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun createLoadSaveRoundTrip() {
        val created = repo.create("Platte", """{"a":1}""")
        val loaded = assertNotNull(repo.load(created.info.id))
        assertEquals("Platte", loaded.info.name)
        assertEquals("""{"a":1}""", loaded.content)

        val info = repo.save(created.info.id, """{"a":2, "text": "Größe ü ß 日本"}""")
        assertEquals("Platte", info.name)
        assertTrue(info.updatedAt > created.info.updatedAt)
        assertEquals("""{"a":2, "text": "Größe ü ß 日本"}""", repo.load(created.info.id)?.content)
    }

    @Test
    fun blankNameBecomesUnbenannt() {
        assertEquals("Unbenannt", repo.create("  ", "{}").info.name)
    }

    @Test
    fun listIsSortedByLastChangeAndSkipsBrokenFolders() {
        val a = repo.create("A", "{}")
        val b = repo.create("B", "{}")
        repo.save(a.info.id, "{\"x\":1}")
        File(dir, "abcd1234").mkdirs() // Ordner ohne Metadaten
        File(dir, "Kein-Projekt").mkdirs()
        assertEquals(listOf(a.info.id, b.info.id), repo.list().map { it.id })
    }

    @Test
    fun saveKeepsPreviousContentAsBackupOnlyWhenContentChanges() {
        val m = repo.create("M", "v1")
        assertNull(repo.loadBackup(m.info.id))
        repo.save(m.info.id, "v1") // gleicher Inhalt: keine Sicherung nötig
        assertNull(repo.loadBackup(m.info.id))
        repo.save(m.info.id, "v2")
        assertEquals("v1", repo.loadBackup(m.info.id))
        repo.save(m.info.id, "v2") // Autosave desselben Standes darf die Sicherung nicht überschreiben
        assertEquals("v1", repo.loadBackup(m.info.id))
        repo.save(m.info.id, "v3")
        assertEquals("v2", repo.loadBackup(m.info.id))
        assertEquals("v3", repo.load(m.info.id)?.content)
    }

    @Test
    fun renameKeepsContent() {
        val m = repo.create("Alt", "inhalt")
        assertEquals("Neu", repo.rename(m.info.id, "Neu")?.name)
        assertEquals("inhalt", repo.load(m.info.id)?.content)
        assertNull(repo.rename("gibtesnicht", "x"))
    }

    @Test
    fun duplicateCopiesContentUnderNewId() {
        val m = repo.create("Teil", "inhalt")
        val copy = assertNotNull(repo.duplicate(m.info.id))
        assertEquals("Teil (Kopie)", copy.info.name)
        assertEquals("inhalt", copy.content)
        assertTrue(copy.info.id != m.info.id)
        assertEquals(2, repo.list().size)
    }

    @Test
    fun deleteRemovesModelAndLastPointer() {
        val m = repo.create("X", "{}")
        repo.lastOpenedId = m.info.id
        assertEquals(m.info.id, repo.lastOpenedId)
        assertTrue(repo.delete(m.info.id))
        assertNull(repo.lastOpenedId)
        assertNull(repo.load(m.info.id))
        assertFalse(repo.delete(m.info.id))
    }

    @Test
    fun lastOpenedIgnoresMissingOrInvalidTargets() {
        assertNull(repo.lastOpenedId)
        File(dir, "last").writeText("../../etc")
        assertNull(repo.lastOpenedId)
        File(dir, "last").writeText("abcd1234efgh")
        assertNull(repo.lastOpenedId) // Ordner existiert nicht
    }

    @Test
    fun rejectsPathTraversalIds() {
        assertNull(repo.load("../x"))
        assertNull(repo.loadBackup("../x"))
        assertFailsWith<IllegalArgumentException> { repo.save("../x", "evil") }
        assertFalse(repo.delete("../x"))
    }

    @Test
    fun noTemporaryFilesRemainAfterSaving() {
        val m = repo.create("T", "a")
        repo.save(m.info.id, "b")
        assertTrue(File(dir, m.info.id).listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun separateInstanceSeesPersistedData() {
        val m = repo.create("P", "persistiert")
        val second = ModelRepository(dir)
        assertEquals("persistiert", second.load(m.info.id)?.content)
        assertEquals(listOf(m.info.id), second.list().map { it.id })
    }
}
