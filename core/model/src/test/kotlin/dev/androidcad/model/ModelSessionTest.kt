package dev.androidcad.model

import dev.androidcad.project.ModelRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelSessionTest {
    private lateinit var dir: File
    private var clock = 1L
    private fun repo() = ModelRepository(dir) { clock++ }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("session").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun doc(width: Double = 40.0): Document {
        val d = Document()
        val sk = d.addSketch("Skizze 1")
        val r = sk.sketch.addRectangle(0.0, 0.0, width, 20.0)
        sk.sketch.addLength(r.edges[0], width)
        d.addExtrude("Extrusion", sk, 8.0)
        return d
    }

    @Test
    fun nothingIsCreatedUntilTheFirstSave() {
        val s = ModelSession(repo())
        s.startNew()
        assertEquals(0, s.list().size)
        assertNull(s.restoreLast())
        s.save(doc())
        assertEquals(1, s.list().size)
        assertNotNull(s.currentId)
    }

    @Test
    fun modelSurvivesRestartLikeAfterProcessDeath() {
        val first = ModelSession(repo())
        first.startNew()
        first.save(doc(55.0))
        first.rename("Meine Platte")

        val second = ModelSession(repo()) // neue Instanz = neuer Prozess
        val restored = assertNotNull(second.restoreLast())
        assertNull(restored.notice)
        assertEquals("Meine Platte", second.name)
        val sk = restored.document!!.features.filterIsInstance<SketchFeature>().single()
        assertEquals(4 + 1, sk.sketch.pointIds.size) // Ursprung + Rechteck
        assertTrue(restored.document!!.features.last() is ExtrudeFeature)
    }

    @Test
    fun savingAgainOverwritesInsteadOfCreatingDuplicates() {
        val s = ModelSession(repo())
        s.startNew()
        s.save(doc(10.0)); s.save(doc(20.0)); s.save(doc(30.0))
        assertEquals(1, s.list().size)
    }

    @Test
    fun openSwitchesBetweenModelsAndRemembersTheLastOne() {
        val s = ModelSession(repo())
        s.startNew(); s.save(doc()); s.rename("A"); val a = s.currentId!!
        s.startNew(); s.save(doc(99.0)); s.rename("B"); val b = s.currentId!!
        assertEquals(setOf("A", "B"), s.list().map { it.name }.toSet())
        assertNotNull(s.open(a).document)
        assertEquals("A", s.name)
        assertEquals(a, ModelSession(repo()).also { it.restoreLast() }.currentId)
        assertTrue(a != b)
    }

    @Test
    fun brokenFileFallsBackToBackupAndNeverGetsOverwrittenSilently() {
        val s = ModelSession(repo())
        s.startNew(); s.save(doc(10.0)); s.save(doc(20.0)) // Sicherung = Stand mit 10
        val id = s.currentId!!
        File(dir, "$id/model.json").writeText("{ kaputt")
        val r = ModelSession(repo()).open(id)
        assertNotNull(r.document)
        assertTrue(r.notice!!.contains("Sicherung"))

        File(dir, "$id/model.json.bak").delete()
        val failed = ModelSession(repo()).open(id)
        assertNull(failed.document)
        assertTrue(failed.notice!!.contains("beschädigt"))
        assertEquals("{ kaputt", File(dir, "$id/model.json").readText()) // unberührt
    }

    @Test
    fun failedRestoreStartsFreshAndSavesAsNewModelWithoutTouchingTheBrokenOne() {
        val s = ModelSession(repo())
        s.startNew(); s.save(doc()); val broken = s.currentId!!
        File(dir, "$broken/model.json").writeText("kein json")
        val second = ModelSession(repo())
        val r = second.restoreLast()!!
        assertNull(r.document)
        assertNull(second.currentId)
        second.startNew()
        second.save(doc(5.0))
        assertEquals(2, second.list().size)
        assertEquals("kein json", File(dir, "$broken/model.json").readText())
    }

    @Test
    fun duplicateRenameDelete() {
        val s = ModelSession(repo())
        s.startNew(); s.save(doc()); s.rename("Teil")
        val original = s.currentId!!
        val copy = s.duplicate()
        assertNotNull(copy.document)
        assertTrue(s.currentId != original)
        assertEquals("Teil (Kopie)", s.name)
        assertEquals(2, s.list().size)
        s.rename("   ") // leer: ignoriert
        assertEquals("Teil (Kopie)", s.name)
        s.deleteCurrent()
        assertEquals(listOf(original), s.list().map { it.id })
        assertNull(s.currentId)
        s.delete(original)
        assertEquals(0, s.list().size)
    }

    @Test
    fun freshNamesDoNotCollide() {
        val s = ModelSession(repo())
        s.startNew(); s.save(doc())
        s.startNew(); s.save(doc())
        s.startNew()
        assertEquals(setOf("Modell 1", "Modell 2"), s.list().map { it.name }.toSet())
        assertEquals("Modell 3", s.name)
    }

    @Test
    fun duplicateOfUnsavedModelExplainsWhy() {
        val s = ModelSession(repo())
        s.startNew()
        assertNull(s.duplicate().document)
    }
}
