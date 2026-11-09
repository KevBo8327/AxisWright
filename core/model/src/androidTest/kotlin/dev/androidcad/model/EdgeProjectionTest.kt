package dev.androidcad.model

import dev.androidcad.brep.BrepShape
import dev.androidcad.model.samples.PlateModel
import dev.androidcad.model.samples.PlateParams
import dev.androidcad.sketch.LineId
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Projizierte Körperkanten in Flächenskizzen: Umriss und Bohrungen der Beispielplatte werden zu festen Referenzen, die dem Körper folgen. */
class EdgeProjectionTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

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

    /** Linie der Skizze, deren beide Endpunkte auf der Geraden `x = value` bzw. `y = value` liegen. */
    private fun lineAt(sk: SketchFeature, axisX: Boolean, value: Double): LineId =
        sk.projections.mapNotNull { it.line }.first { id ->
            val l = sk.sketch.line(id)
            listOf(l.p1, l.p2).all { abs((if (axisX) sk.sketch.point(it).x else sk.sketch.point(it).y) - value) < 1e-6 }
        }

    private fun topSketch(model: PlateModel): SketchFeature {
        val top = FaceRef(model.extrusion.id, FaceRole.TOP)
        return model.document.addSketchOnFace("Aufsatz", top, PlaneSpec(BasePlane.XY, 8.0).toWorkplane())
    }

    @Test
    fun theTopFaceOffersItsOutlineAndHolesForProjection() {
        val model = PlateModel()
        val sk = topSketch(model)
        model.build().use { r ->
            val edges = r.projectable.getValue(sk.id)
            assertEquals(4, edges.count { it.line != null }, "vier Außenkanten")
            assertEquals(4, edges.count { it.circle != null }, "vier Bohrungen")
            val circle = edges.first { it.circle != null && abs(it.circle.first.x - 10.0) < 1e-6 && abs(it.circle.first.y - 10.0) < 1e-6 }
            near(3.0, circle.circle!!.second)
            // Außenkanten decken genau 80 × 50 ab
            val xs = edges.mapNotNull { it.line }.flatMap { listOf(it.first.x, it.second.x) }
            val ys = edges.mapNotNull { it.line }.flatMap { listOf(it.first.y, it.second.y) }
            near(0.0, xs.min()); near(80.0, xs.max()); near(0.0, ys.min()); near(50.0, ys.max())
            assertEquals(8, model.document.addProjections(sk.id, edges))
            assertEquals(0, model.document.addProjections(sk.id, edges), "zweites Projizieren legt nichts doppelt an")
        }
    }

    @Test
    fun dimensionsToProjectedEdgesFollowTheBodyWhenItChanges() {
        val model = PlateModel()
        val sk = topSketch(model)
        model.build().use { r -> model.document.addProjections(sk.id, r.projectable.getValue(sk.id)) }
        val feature = model.document.sketch(sk.id)
        val s = feature.sketch
        val right = lineAt(feature, axisX = true, value = 80.0)
        val top = lineAt(feature, axisX = false, value = 50.0)
        // Zapfen: Mitte je 15 mm von rechter und oberer Kante (gezeichnet bei (65, 35)), Ø 12
        val boss = s.addCircle(65.0, 35.0, 6.0)
        val c = s.circle(boss).center
        s.addPointLineDistance(c, right, 15.0)
        s.addPointLineDistance(c, top, 15.0)
        s.addRadius(boss, 6.0)
        val ext = model.document.addExtrude("Zapfen", feature, 5.0)

        for (p in listOf(PlateParams(), PlateParams(width = 120.0, height = 70.0), PlateParams(width = 60.0, height = 40.0, thickness = 12.0))) {
            model.apply(p)
            model.build().use { r ->
                assertTrue(r.isOk, "$p: ${r.errors}")
                val wall = FaceRef(ext.id, FaceRole.SIDE, boss.value)
                val m = center(r.body!!, r.faces(wall).single())
                near(p.width - 15.0, m[0], 0.05)
                near(p.height - 15.0, m[1], 0.05)
                near(p.expectedVolume + Math.PI * 36 * 5.0, r.body!!.volume(), p.expectedVolume * 1e-3)
                // die lebende Skizze folgt erst nach applyProjections
                model.document.applyProjections(r.projected)
                near(p.width, model.document.sketch(sk.id).sketch.point(model.document.sketch(sk.id).sketch.line(right).p1).x)
            }
        }
    }

    @Test
    fun projectedCirclesFollowPositionAndDiameterAndAreAppliedOnlyOnChange() {
        val model = PlateModel()
        val sk = topSketch(model)
        model.build().use { r -> model.document.addProjections(sk.id, r.projectable.getValue(sk.id)) }
        val s = model.document.sketch(sk.id).sketch
        model.apply(PlateParams(width = 100.0, margin = 15.0, holeDiameter = 10.0))
        // wie in der App: der Aufbau läuft auf einer Kopie, die lebende Skizze wird erst danach nachgeführt
        model.document.snapshot().build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            assertTrue(model.document.applyProjections(r.projected), "geänderte Kanten werden übernommen")
            val radii = s.circleIds.map { s.circle(it).radius }
            assertEquals(4, radii.size)
            assertTrue(radii.all { abs(it - 5.0) < 1e-6 }, radii.toString())
            val centers = s.circleIds.map { s.point(s.circle(it).center) }
            assertTrue(centers.any { abs(it.x - 85.0) < 1e-6 && abs(it.y - 35.0) < 1e-6 }, centers.toString())
            assertFalse(model.document.applyProjections(r.projected), "ein zweites Mal ändert sich nichts")
        }
    }

    @Test
    fun aMissingProjectedEdgeIsReportedAndTheRestStillBuilds() {
        val model = PlateModel()
        val sk = topSketch(model)
        val ghost = EdgeRef.of(FaceRef(model.extrusion.id, FaceRole.TOP), FaceRef(model.extrusion.id, FaceRole.SIDE, 99999))
        val g = EdgeProjection.Geometry(ghost, line = dev.androidcad.sketch.Vec2(0.0, 0.0) to dev.androidcad.sketch.Vec2(5.0, 0.0))
        model.document.addProjections(sk.id, listOf(g))
        sk.sketch.addCircle(40.0, 25.0, 6.0)
        model.document.addExtrude("Zapfen", model.document.sketch(sk.id), 5.0)
        model.build().use { r ->
            val msg = assertNotNull(r.errors[sk.id])
            assertEquals(ModelText.PROJECTIONS_MISSING, msg.key)
            near(PlateParams().expectedVolume + Math.PI * 36 * 5.0, r.body!!.volume(), 1.0)
        }
    }

    @Test
    fun deletingAProjectedElementIsNotAnError() {
        val model = PlateModel()
        val sk = topSketch(model)
        model.build().use { r -> model.document.addProjections(sk.id, r.projectable.getValue(sk.id)) }
        val feature = model.document.sketch(sk.id)
        feature.sketch.remove(feature.projections.first { it.line != null }.line!!)
        feature.sketch.addCircle(40.0, 25.0, 6.0)
        model.document.addExtrude("Zapfen", feature, 5.0)
        model.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            assertEquals(7, r.projected.getValue(sk.id).size)
        }
    }

    @Test
    fun projectionsSurviveSaveAndLoad() {
        val model = PlateModel()
        val sk = topSketch(model)
        model.build().use { r -> model.document.addProjections(sk.id, r.projectable.getValue(sk.id)) }
        val loaded = DocumentCodec.decode(DocumentCodec.encode(model.document))
        val a = model.document.sketch(sk.id); val b = loaded.sketch(sk.id)
        assertEquals(a.projections, b.projections)
        loaded.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            assertEquals(8, r.projected.getValue(sk.id).size)
        }
    }

    @Test
    fun aProjectedEdgeSurvivesWhenItsFaceIsMergedWithAnother() {
        // Block A (Höhe 10) und daneben Block B (Höhe 5); Skizze auf B oben mit der projizierten rechten Kante (x = 60)
        val doc = Document()
        doc.addExtrude("A", doc.addSketch("A").also { it.sketch.addRectangle(0.0, 0.0, 40.0, 20.0) }, 10.0)
        val skB = doc.addSketch("B")
        val rect = skB.sketch.addRectangle(40.0, 0.0, 60.0, 20.0)
        val b = doc.addExtrude("B", skB, 5.0)
        val topB = FaceRef(b.id, FaceRole.TOP)
        val edge = EdgeRef.of(topB, FaceRef(b.id, FaceRole.SIDE, rect.edges[1].value))
        val sk = doc.addSketchOnFace("Auf B", topB, Workplane.XY)
        doc.build().use { r -> assertEquals(1, doc.addProjections(sk.id, r.projectable.getValue(sk.id).filter { it.edge == edge })) }
        // B so hoch wie A: die Deckflächen werden eine (Hauptname von A, B oben nur noch Zweitname)
        doc.update(b.copy(distance = 10.0))
        doc.build().use { r ->
            assertTrue(r.isOk, r.errors.toString())
            assertEquals(listOf(edge), r.projected.getValue(sk.id).map { it.first.edge })
            assertTrue(r.projectable.getValue(sk.id).isNotEmpty(), "die zusammengeführte Fläche bietet ihren Umriss an")
        }
    }
}
