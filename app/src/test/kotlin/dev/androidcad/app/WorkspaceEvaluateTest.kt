package dev.androidcad.app

import dev.androidcad.model.Document
import dev.androidcad.model.EdgeRef
import dev.androidcad.model.FaceRef
import dev.androidcad.model.FaceRole
import dev.androidcad.model.HoleFeature
import dev.androidcad.model.ModelSession
import dev.androidcad.model.Parameter
import dev.androidcad.model.PatternFeature
import dev.androidcad.model.PatternKind
import dev.androidcad.model.PlaneDef
import dev.androidcad.model.PlaneFeature
import dev.androidcad.model.ValueRef
import dev.androidcad.model.Vec3
import dev.androidcad.model.Workplane
import dev.androidcad.project.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceEvaluateTest {
    private fun workspace(repo: ModelRepository = ModelRepository(Files.createTempDirectory("models").toFile())) = WorkspaceState(
        CoroutineScope(Job().apply { cancel() }),
        ModelSession(repo),
        workspaceTestStrings,
    )

    @Test
    fun evaluateHandlesDecimalCommaInPlainNumbersAndFormulas() {
        val ws = workspace()
        ws.updateParameters(listOf(Parameter("wand", "2,5")))
        assertEquals(2.5, ws.evaluate("2,5"))
        assertEquals(3.75, ws.evaluate("wand * 1,5"))
        assertEquals(5.0, ws.evaluate("max(wand, 1) * 2"))
    }

    @Test
    fun setBindingDrivesPatternPlaneAndHoleDepthThroughWorkspaceState() {
        val doc = Document()
        val sk = doc.addSketch("Grundriss")
        sk.sketch.addRectangle(0.0, 0.0, 40.0, 20.0)
        val ex = doc.addExtrude("Platte", sk, 10.0)
        val patLin = doc.addPattern(
            "Raster",
            ex.id,
            PatternKind.Linear(Vec3(1.0, 0.0, 0.0), 3, 15.0, direction2 = Vec3(0.0, 1.0, 0.0), count2 = 2, spacing2 = 10.0),
        )
        val patCirc = doc.addPattern("Kreis", ex.id, PatternKind.Circular(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), 4, 180.0))
        val top = FaceRef(ex.id, FaceRole.TOP)
        val planeOffset = doc.addPlane("Versatz", PlaneDef.Offset(top, 5.0), Workplane.XY)
        val edge = EdgeRef.of(top, FaceRef(ex.id, FaceRole.SIDE, 1))
        val planeEdge = doc.addPlane("Kante", PlaneDef.AtEdge(top, edge, 30.0), Workplane.XY)
        val hole = doc.addHole("Sackloch", top, 10.0, 10.0, 0.0, 6.0, depth = 4.0)

        val repo = ModelRepository(Files.createTempDirectory("models").toFile())
        ModelSession(repo).save(doc)
        val ws = workspace(repo)

        ws.updateParameters(listOf(Parameter("s", "22,5"), Parameter("w", "45"), Parameter("t", "12,5")))
        ws.setBinding(ValueRef.FeatureValue(patLin.id, 0), "s")
        ws.setBinding(ValueRef.FeatureValue(patLin.id, 1), "s * 2")
        ws.setBinding(ValueRef.FeatureValue(patCirc.id), "w * 2")
        ws.setBinding(ValueRef.FeatureValue(planeOffset.id), "-s")
        ws.setBinding(ValueRef.FeatureValue(planeEdge.id), "w")
        ws.setBinding(ValueRef.FeatureValue(hole.id, 1), "t * 0,8")

        assertNull(ws.notice)
        assertEquals(6, ws.bindings.size)

        ws.select(patLin.id)
        val lin = (ws.pattern!!.kind as PatternKind.Linear)
        assertEquals(22.5, lin.spacing)
        assertEquals(45.0, lin.spacing2)

        ws.select(patCirc.id)
        assertEquals(90.0, (ws.pattern!!.kind as PatternKind.Circular).angle)

        ws.select(planeOffset.id)
        assertEquals(-22.5, (ws.datum!!.def as PlaneDef.Offset).distance)

        ws.select(planeEdge.id)
        assertEquals(45.0, (ws.datum!!.def as PlaneDef.AtEdge).angle)

        ws.select(hole.id)
        assertEquals(10.0, ws.hole!!.depth)

        // Parameter ändern -> alle gebundenen Features folgen über WorkspaceState
        ws.updateParameters(listOf(Parameter("s", "10,5"), Parameter("w", "30"), Parameter("t", "15")))
        assertNull(ws.notice)
        val lin2 = ((ws.features.first { it.id == patLin.id } as PatternFeature).kind as PatternKind.Linear)
        assertEquals(10.5, lin2.spacing)
        assertEquals(21.0, lin2.spacing2)
        val circ2 = ((ws.features.first { it.id == patCirc.id } as PatternFeature).kind as PatternKind.Circular)
        assertEquals(60.0, circ2.angle)
        assertEquals(-10.5, ((ws.features.first { it.id == planeOffset.id } as PlaneFeature).def as PlaneDef.Offset).distance)
        assertEquals(30.0, ((ws.features.first { it.id == planeEdge.id } as PlaneFeature).def as PlaneDef.AtEdge).angle)
        assertEquals(12.0, (ws.features.first { it.id == hole.id } as HoleFeature).depth)

        // Bindung aufheben
        ws.setBinding(ValueRef.FeatureValue(hole.id, 1), null)
        assertEquals(5, ws.bindings.size)
    }
}
