package dev.androidcad.model

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellFeatureTest {
    @Test
    fun openBoxFollowsTheBlockAndItsInnerEdgesCanBeRounded() {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        val r = sk.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val box = doc.addExtrude("Block", sk, 20.0)
        val shell = doc.addShell("Schale", listOf(FaceRef(box.id, FaceRole.TOP)), 2.0)
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            assertTrue(abs(res.body!!.volume() - (40.0 * 30 * 20 - 36.0 * 26 * 18)) < 1e-6, "Volumen ${res.body.volume()}")
        }
        // Innenkante zwischen Innenboden und der inneren Vorderwand verrunden – über die Namen der Innenflächen
        fun inner(ref: FaceRef) = FaceRef(shell.id, FaceRole.SIDE, ShellFeature.innerElement(ref.tag))
        val floor = inner(FaceRef(box.id, FaceRole.BOTTOM))
        val front = inner(FaceRef(box.id, FaceRole.SIDE, r.edges[0].value))
        doc.addFillet("Innen", listOf(EdgeRef.of(floor, front)), 1.0)
        // dickere Wand: Namen bleiben gültig, die Verrundung wird mitgebaut
        doc.update(shell.copy(thickness = 3.0))
        doc.build().use { res -> assertTrue(res.isOk, res.errors.toString()) }
    }

    @Test
    fun tooThickWallIsReportedAtTheFeature() {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        sk.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val box = doc.addExtrude("Block", sk, 10.0)
        val shell = doc.addShell("Schale", listOf(FaceRef(box.id, FaceRole.TOP)), 20.0)
        doc.build().use { res ->
            assertTrue(res.errors[shell.id] != null, res.errors.toString())
            assertTrue(abs(res.body!!.volume() - 40.0 * 30 * 10) < 1e-6, "der Block bleibt unverändert")
        }
    }

    @Test
    fun innerFacesOfAMirroredShellAreNamedAndUsableAsSketchFaceAndForFillets() {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        val r = sk.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val box = doc.addExtrude("Block", sk, 20.0)
        val shell = doc.addShell("Schale", listOf(FaceRef(box.id, FaceRole.TOP)), 2.0)
        val mirror = doc.addMirrorBody("Spiegelung", Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        fun inner(ref: FaceRef) = FaceRef(shell.id, FaceRole.SIDE, ShellFeature.innerElement(ref.tag))
        fun mirrored(ref: FaceRef) = FaceRef(mirror.id, ref.role, MirrorNaming.mirroredElement(ref))
        val floor = mirrored(inner(FaceRef(box.id, FaceRole.BOTTOM)))
        val front = mirrored(inner(FaceRef(box.id, FaceRole.SIDE, r.edges[0].value)))
        val walls = 2 * (40.0 * 30 * 20 - 36.0 * 26 * 18)
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            assertTrue(abs(res.body!!.volume() - walls) < 1e-3, "Volumen ${res.body.volume()}")
            assertEquals(1, res.faces(floor).size, "Innenboden des Spiegelbilds ist benannt")
            assertEquals(1, res.faces(front).size, "innere Vorderwand des Spiegelbilds ist benannt")
        }
        // Skizze auf dem gespiegelten Innenboden (z = 2) und Verrundung seiner Innenkante (36 mm lang)
        val onFloor = doc.addSketchOnFace("Boden", floor, Workplane.XY)
        val round = doc.addFillet("Innen", listOf(EdgeRef.of(floor, front)), 1.0)
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            assertTrue(abs(res.sketchPlanes.getValue(onFloor.id).origin.z - 2.0) < 1e-6, "Skizze liegt auf dem Innenboden")
            assertEquals(1, res.faces(FaceRef(round.id, FaceRole.ROUND, 0)).size)
            val gain = (1 - Math.PI / 4) * 36.0 // Zwickel einer Innenrundung r = 1 entlang der Kante
            assertTrue(abs(res.body!!.volume() - (walls + gain)) < 0.05, "Volumen ${res.body.volume()}")
        }
    }
}
