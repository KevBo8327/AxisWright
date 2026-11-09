package dev.androidcad.model

import dev.androidcad.brep.BrepShape
import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Flächen der Beispielplatte werden über Feature + Skizzenelement benannt und nach Maßänderungen wiedergefunden. */
class FaceRefTest {
    /** Mittelpunkt der Netzknoten einer Fläche (reicht für ebene Flächen und symmetrische Bohrungswände). */
    private fun center(body: BrepShape, faceId: Int): DoubleArray {
        val m = body.tessellate(0.02, 0.2)
        val c = DoubleArray(3)
        var n = 0
        for (t in 0 until m.triangleCount) {
            if (m.triangleFace[t] != faceId) continue
            for (k in 0..2) { for (d in 0..2) c[d] += m.positions[3 * m.triangles[3 * t + k] + d].toDouble(); n++ }
        }
        return DoubleArray(3) { c[it] / n }
    }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-3) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    @Test
    fun faceRefTagRoundTrips() {
        val ref = FaceRef(FeatureId(7), FaceRole.SIDE, 123456)
        assertEquals(ref, FaceRef.fromTag(ref.tag))
        for (role in FaceRole.entries) assertEquals(FaceRef(FeatureId(32767), role, 0), FaceRef.fromTag(FaceRef(FeatureId(32767), role, 0).tag))
        assertEquals(null, FaceRef.fromTag(0))
        assertEquals(null, FaceRef.fromTag(12345)) // Rolle 0 ist ungültig
        assertTrue(FaceRef(FeatureId(1), FaceRole.SIDE, 1).tag != FaceRef(FeatureId(2), FaceRole.SIDE, 1).tag)
    }

    @Test
    fun plateFacesAreFoundAgainAfterEveryParameterChange() {
        val model = PlateModel()
        val sketch = model.sketch.sketch
        val ext = model.extrusion.id
        val right = FaceRef(ext, FaceRole.SIDE, sketch.lineIds[1].value) // zweite Rechteckkante: x = Breite
        val top = FaceRef(ext, FaceRole.TOP)
        val bottom = FaceRef(ext, FaceRole.BOTTOM)
        val hole0 = FaceRef(ext, FaceRole.SIDE, sketch.circleIds[0].value) // Bohrung in der Ecke (margin, margin)

        val cases = listOf(PlateParams(), PlateParams(120.0, 70.0, 15.0, 10.0, 20.0), PlateParams(40.0, 30.0, 6.0, 3.0, 2.0), PlateParams())
        for (p in cases) {
            model.apply(p)
            model.build().use { r ->
                assertTrue(r.isOk, r.errors.toString())
                val body = r.body!!
                near(p.width, center(body, r.faces(right).single())[0])
                near(p.thickness, center(body, r.faces(top).single())[2])
                near(0.0, center(body, r.faces(bottom).single())[2])
                val h = center(body, r.faces(hole0).single())
                near(p.margin, h[0], 0.01); near(p.margin, h[1], 0.01)
                // Kante Deckel/rechte Seite ist über die beiden Flächennamen auffindbar
                assertEquals(1, r.edgesBetween(top, right).size)
            }
        }
    }

    @Test
    fun filletAndChamferFollowParameterChangesAndReportBrokenEdges() {
        val model = PlateModel()
        val ext = model.extrusion.id
        val sketch = model.sketch.sketch
        val edge = EdgeRef.of(FaceRef(ext, FaceRole.TOP), FaceRef(ext, FaceRole.SIDE, sketch.lineIds[1].value)) // Deckel / rechte Seite
        val fillet = model.document.addFillet("Rundung", listOf(edge), 2.0)
        val roundFace = FaceRef(fillet.id, FaceRole.ROUND, 0)

        for (p in listOf(PlateParams(), PlateParams(120.0, 70.0, 15.0, 10.0, 20.0), PlateParams(40.0, 30.0, 6.0, 3.0, 12.0))) {
            model.apply(p)
            model.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                assertEquals(1, r.faces(roundFace).size)
                // Viertelzylinder entfernt (1 − π/4)·R²·Länge; Länge = Plattentiefe in y
                val removed = (1 - Math.PI / 4) * 2.0 * 2.0 * p.height
                near(p.expectedVolume - removed, r.body!!.volume(), p.expectedVolume * 1e-4)
            }
        }

        // Fase statt Rundung: Dreieckprisma
        model.document.update(fillet.copy(kind = EdgeKind.CHAMFER, size = 3.0))
        model.apply(PlateParams())
        model.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume - 0.5 * 3.0 * 3.0 * PlateParams().height, r.body!!.volume(), 1e-3 * PlateParams().expectedVolume)
        }

        // Defekte Referenz: Fehler am Fillet-Feature, Körper (ohne Rundung) bleibt erhalten
        model.document.update(fillet.copy(edges = listOf(EdgeRef.of(FaceRef(ext, FaceRole.TOP), FaceRef(ext, FaceRole.SIDE, 99999)))))
        model.build().use { r ->
            assertEquals(setOf(fillet.id), r.errors.keys)
            near(PlateParams().expectedVolume, r.body!!.volume(), 1e-3 * PlateParams().expectedVolume)
        }
    }

    @Test
    fun holesCutThroughOrBlindAndFollowTheirFace() {
        val model = PlateModel()
        val ext = model.extrusion.id
        val top = FaceRef(ext, FaceRole.TOP)
        val bottom = FaceRef(ext, FaceRole.BOTTOM)
        // Bohrung mitten in die Platte (80 × 50), Deckel bei z = Dicke; Punkt bewusst leicht neben der Ebene (wird projiziert)
        val through = model.document.addHole("Durchgang", top, 40.0, 25.0, 9.0, 10.0)
        val blind = model.document.addHole("Sackloch", top, 20.0, 25.0, 8.0, 6.0, depth = 3.0)
        val wall = FaceRef(through.id, FaceRole.SIDE, 1)
        val floor = FaceRef(blind.id, FaceRole.TOP)

        for (p in listOf(PlateParams(), PlateParams(120.0, 70.0, 15.0, 6.0, 20.0), PlateParams(80.0, 50.0, 10.0, 6.0, 12.0))) {
            model.apply(p)
            model.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                // Volumen: Platte − durchgehend Ø10 − Sackloch Ø6 × 3 (das Sackloch liegt bei x=20 – nur bei breiten Platten außerhalb der Ecklöcher)
                val expected = p.expectedVolume - Math.PI * 25 * p.thickness - Math.PI * 9 * 3.0
                near(expected, r.body!!.volume(), expected * 1e-4)
                assertEquals(1, r.faces(wall).size, "Bohrungswand wird über ihren Namen gefunden")
                val f = r.faces(floor)
                assertEquals(1, f.size)
                near(p.thickness - 3.0, center(r.body!!, f.single())[2], 0.01) // Boden 3 mm unter dem Deckel – folgt der Dicke
                assertEquals(1, r.faces(top).size)
                assertEquals(1, r.faces(bottom).size)
            }
        }
    }

    @Test
    fun sketchOnTheTopFaceFollowsTheFaceAndBuildsBossAndPocket() {
        val model = PlateModel()
        val ext = model.extrusion.id
        val top = FaceRef(ext, FaceRole.TOP)
        // Platte 80 × 50 × 8; Skizze auf dem Deckel (aktuelle Ebene = XY +8)
        val onTop = model.document.addSketchOnFace("Aufsatz", top, PlaneSpec(BasePlane.XY, 8.0).toWorkplane())
        onTop.sketch.addCircle(40.0, 25.0, 6.0) // Zapfen Ø12 mitten auf der Platte
        val boss = model.document.addExtrude("Zapfen", onTop, 5.0)

        for (p in listOf(PlateParams(), PlateParams(80.0, 50.0, 10.0, 6.0, 20.0), PlateParams(80.0, 50.0, 10.0, 6.0, 3.0))) {
            model.apply(p)
            model.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                val plane = r.sketchPlanes.getValue(onTop.id)
                near(p.thickness, plane.origin.z, 1e-9) // die Skizzenebene ist mit dem Deckel gewandert
                near(1.0, plane.normal.z, 1e-9)
                near(p.expectedVolume + Math.PI * 36 * 5.0, r.body!!.volume(), p.expectedVolume * 1e-4)
                // Deckel des Zapfens liegt 5 mm über dem Plattendeckel
                val topOfBoss = FaceRef(boss.id, FaceRole.TOP)
                near(p.thickness + 5.0, center(r.body!!, r.faces(topOfBoss).single())[2], 0.01)
            }
        }

        // Tasche statt Zapfen: gleiche Skizze, Abtragen (negative Richtung = in den Körper)
        model.apply(PlateParams())
        model.document.update((model.document.feature(boss.id) as ExtrudeFeature).copy(op = BodyOp.CUT, distance = -3.0))
        model.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            near(PlateParams().expectedVolume - Math.PI * 36 * 3.0, r.body!!.volume(), PlateParams().expectedVolume * 1e-4)
        }
    }

    @Test
    fun sketchOnASideFaceUsesTheFaceOrientation() {
        val doc = Document()
        val sk = doc.addSketch("Block")
        val r = sk.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val ext = doc.addExtrude("Block", sk, 20.0)
        val front = FaceRef(ext.id, FaceRole.SIDE, r.edges[0].value) // y = 0, Normale −Y
        // Ebene der Vorderseite = XZ (Ursprung (0,0,0), x = X, y = Z)
        val onFront = doc.addSketchOnFace("Front", front, Workplane.XZ)
        onFront.sketch.addCircle(20.0, 10.0, 4.0) // Mitte der Vorderseite
        val pocket = doc.addExtrude("Tasche", onFront, -6.0, BodyOp.CUT)
        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            near(40.0 * 30.0 * 20.0 - Math.PI * 16 * 6.0, res.body!!.volume(), 1.0)
            val wall = FaceRef(pocket.id, FaceRole.SIDE, onFront.sketch.circleIds[0].value)
            val c = center(res.body!!, res.faces(wall).single())
            near(20.0, c[0], 0.05) // x der Skizze = X
            near(10.0, c[2], 0.05) // y der Skizze = Z
        }
    }

    @Test
    fun missingFaceReportsAnErrorAtTheSketchButKeepsTheRestOfTheModel() {
        val model = PlateModel()
        val ext = model.extrusion.id
        val ghost = FaceRef(ext, FaceRole.SIDE, 99999)
        val onGhost = model.document.addSketchOnFace("Geist", ghost, PlaneSpec(BasePlane.XY, 8.0).toWorkplane())
        onGhost.sketch.addCircle(40.0, 25.0, 6.0)
        model.document.addExtrude("Zapfen", onGhost, 5.0)
        model.build().use { r ->
            assertTrue(onGhost.id in r.errors.keys, r.errors.toString())
            // Rückfall auf die zuletzt gespeicherte Ebene: der Zapfen entsteht trotzdem dort
            near(PlateParams().expectedVolume + Math.PI * 36 * 5.0, r.body!!.volume(), 1.0)
        }
    }

    @Test
    fun anchoredHoleMovesWithItsEdgesAndReportsBadAnchors() {
        val model = PlateModel()
        val ext = model.extrusion.id
        val sketch = model.sketch.sketch
        val top = FaceRef(ext, FaceRole.TOP)
        val frontFace = FaceRef(ext, FaceRole.SIDE, sketch.lineIds[0].value) // y = 0
        val rightFace = FaceRef(ext, FaceRole.SIDE, sketch.lineIds[1].value) // x = Breite
        val leftFace = FaceRef(ext, FaceRole.SIDE, sketch.lineIds[3].value) // x = 0
        // 25 mm von der rechten und 30 mm von der vorderen Kante (Plattenhöhe 8 → Deckel z = 8)
        val p0 = Vec3(55.0, 30.0, 8.0)
        val n = Vec3(0.0, 0.0, 1.0)
        val dFront = HolePlacement.signedDistance(n, p0, Vec3(0.0, 0.0, 8.0), Vec3(80.0, 0.0, 8.0))
        val dRight = HolePlacement.signedDistance(n, p0, Vec3(80.0, 0.0, 8.0), Vec3(80.0, 50.0, 8.0))
        val anchors = EdgeDistance(EdgeRef.of(top, frontFace), dFront) to EdgeDistance(EdgeRef.of(top, rightFace), dRight)
        val hole = model.document.addHole("verankert", top, p0.x, p0.y, p0.z, 4.0, anchors = anchors)
        val wall = FaceRef(hole.id, FaceRole.SIDE, 1)

        for (p in listOf(PlateParams(), PlateParams(120.0, 70.0, 15.0, 6.0, 20.0), PlateParams(60.0, 40.0, 10.0, 6.0, 5.0), PlateParams())) {
            model.apply(p)
            model.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                val c = center(r.body!!, r.faces(wall).single())
                near(p.width - 25.0, c[0], 0.05) // folgt der rechten Kante
                near(30.0, c[1], 0.05)           // 30 mm von der vorderen Kante
                near(p.expectedVolume - Math.PI * 4 * p.thickness, r.body!!.volume(), p.expectedVolume * 1e-4)
            }
        }

        // Parallele Referenzkanten (links + rechts) sind keine Position: Fehler am Feature, Körper bleibt erhalten
        model.apply(PlateParams())
        model.document.update(hole.copy(anchors = EdgeDistance(EdgeRef.of(top, leftFace), 10.0) to EdgeDistance(EdgeRef.of(top, rightFace), 10.0)))
        model.build().use { r ->
            assertEquals(setOf(hole.id), r.errors.keys)
            near(PlateParams().expectedVolume, r.body!!.volume(), 1e-3 * PlateParams().expectedVolume)
        }
        // Eine gekrümmte Kante (Rand einer Eckbohrung) taugt nicht als Referenz
        val cornerHoleEdge = EdgeRef.of(top, FaceRef(ext, FaceRole.SIDE, sketch.circleIds[0].value))
        model.document.update(hole.copy(anchors = EdgeDistance(cornerHoleEdge, 2.0) to EdgeDistance(EdgeRef.of(top, rightFace), 10.0)))
        model.build().use { r ->
            assertEquals(setOf(hole.id), r.errors.keys)
            assertEquals(ModelText.HOLE_REF_EDGE_NOT_STRAIGHT, r.errors.values.single().key)
        }
        // Referenzkante existiert nicht mehr
        val ghost = EdgeRef.of(top, FaceRef(ext, FaceRole.SIDE, 99999))
        model.document.update(hole.copy(anchors = EdgeDistance(ghost, 1.0) to EdgeDistance(EdgeRef.of(top, rightFace), 10.0)))
        model.build().use { r -> assertEquals(setOf(hole.id), r.errors.keys) }
    }

    @Test
    fun holeInASideFaceAndOnAnEarlierHolesFloorWork() {
        val doc = Document()
        val sk = doc.addSketch("Block")
        val rect = sk.sketch.addRectangle(0.0, 0.0, 40.0, 30.0)
        val ext = doc.addExtrude("Block", sk, 20.0)
        // seitliche Bohrung in die rechte Wand (x = 40), Mitte y = 15, z = 10, Tiefe 15
        val side = FaceRef(ext.id, FaceRole.SIDE, rect.edges[1].value)
        val h1 = doc.addHole("seitlich", side, 40.0, 15.0, 10.0, 8.0, depth = 15.0)
        // zweite Bohrung im Boden der ersten (Durchmesser kleiner, durchgehend weiter in +x… nach −x)
        val h2 = doc.addHole("tiefer", FaceRef(h1.id, FaceRole.TOP), 25.0, 15.0, 10.0, 3.0, depth = 5.0)
        doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            val expected = 40.0 * 30.0 * 20.0 - Math.PI * 16 * 15.0 - Math.PI * 2.25 * 5.0
            near(expected, r.body!!.volume(), expected * 1e-4)
            assertEquals(1, r.faces(FaceRef(h2.id, FaceRole.SIDE, 1)).size)
        }
        // eine Bohrung ins Leere (Fläche existiert nicht mehr) meldet einen Fehler am Feature und lässt den Körper unberührt
        val ghost = doc.addHole("Geist", FaceRef(ext.id, FaceRole.SIDE, 99999), 0.0, 0.0, 0.0, 2.0)
        doc.build().use { r ->
            assertEquals(setOf(ghost.id), r.errors.keys)
            near(40.0 * 30.0 * 20.0 - Math.PI * 16 * 15.0 - Math.PI * 2.25 * 5.0, r.body!!.volume(), 1.0)
        }
    }

    @Test
    fun aFailedBooleanKeepsTheBodyUsableForLaterFeatures() {
        val doc = Document()
        val sk = doc.addSketch("Block")
        sk.sketch.addRectangle(0.0, 0.0, 20.0, 20.0)
        doc.addExtrude("Block", sk, 10.0)
        // Ein Abtrag ohne geschlossene Fläche schlägt fehl (Fehler am Feature) …
        val bad = doc.addSketch("leer")
        val cut = doc.addExtrude("Abtrag", bad, 5.0, BodyOp.CUT)
        // … ein späteres Feature muss trotzdem auf dem unveränderten Körper arbeiten
        val boss = doc.addSketch("Zapfen")
        boss.sketch.addCircle(10.0, 10.0, 3.0)
        doc.addExtrude("Zapfen", boss, 5.0)
        val shifted = Workplane.XY.offset(10.0)
        doc.setPlane(boss.id, shifted)
        doc.build().use { r ->
            assertEquals(setOf(cut.id), r.errors.keys)
            near(20.0 * 20.0 * 10.0 + Math.PI * 9 * 5.0, r.body!!.volume(), 1e-3)
        }
    }

    @Test
    fun removingTheExtrusionLeavesNothingToResolve() {
        val model = PlateModel()
        val ref = FaceRef(model.extrusion.id, FaceRole.TOP)
        model.build().use { assertEquals(1, it.faces(ref).size) }
        model.document.remove(model.extrusion.id)
        model.build().use { assertTrue(it.faces(ref).isEmpty()) }
    }

    @Test
    fun aFaceRefToACutAwayWallBecomesUnresolvable() {
        val doc = Document()
        val sk = doc.addSketch("Block")
        val r = sk.sketch.addRectangle(0.0, 0.0, 20.0, 30.0)
        val base = doc.addExtrude("Block", sk, 10.0)
        val rightWall = FaceRef(base.id, FaceRole.SIDE, r.edges[1].value)

        // Fräsung über die rechte Hälfte
        val cutSketch = doc.addSketch("Fräsung")
        cutSketch.sketch.addRectangle(15.0, -5.0, 30.0, 35.0)
        val cut = doc.addExtrude("Abtrag", cutSketch, 20.0, BodyOp.CUT, symmetric = true)

        doc.build().use { res ->
            assertTrue(res.isOk, res.errors.toString())
            assertTrue(res.faces(rightWall).isEmpty(), "Die rechte Wand wurde weggeschnitten")
            // Die neue Wand gehört zum Abtrag-Feature und ist dort benannt
            val newWall = FaceRef(cut.id, FaceRole.SIDE, cutSketch.sketch.lineIds[3].value)
            val f = res.faces(newWall)
            assertEquals(1, f.size)
            near(15.0, center(res.body!!, f.single())[0])
        }
        // Ohne den Abtrag ist die Wand wieder da – dieselbe Referenz, derselbe Name
        doc.remove(cutSketch.id)
        doc.build().use { res -> assertEquals(1, res.faces(rightWall).size) }
    }
}
