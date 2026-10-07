package dev.androidcad.model

import dev.androidcad.brep.BrepShape
import dev.androidcad.brep.PlanarFace
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Geometrie der Arbeitsebenen ([PlaneFeature]): reine Mathematik plus Auflösung gegen einen Körper. */
object DatumPlane {
    /** Fehlermeldung, wenn die Werte unbrauchbar sind, sonst `null`. */
    fun validate(def: PlaneDef): String? = when (def) {
        is PlaneDef.Offset -> if (!def.distance.isFinite()) "Abstand ungültig" else null
        is PlaneDef.AtEdge -> if (!def.angle.isFinite() || abs(def.angle) >= 180.0) "Winkel muss zwischen −180° und 180° liegen" else null
    }

    /** Ebene der Fläche, um [distance] entlang der Außennormalen verschoben (gleiche Achsen wie die Flächenskizze, [FacePlane]). */
    fun offset(face: PlanarFace, distance: Double): Workplane = FacePlane.workplane(face).offset(distance)

    /**
     * Ebene durch die Kante [a]–[b], aus der Ebene mit der Außennormalen [normal] um [angleDeg] gedreht. Ursprung ist der kanonische
     * Anfang der Kante, x zeigt entlang der Kante ([HolePlacement.canonical]) – so bleiben Skizzen auf der Ebene an der Kante.
     */
    fun atEdge(normal: Vec3, a: Vec3, b: Vec3, angleDeg: Double): Workplane {
        val n = normal.normalized()
        val (start, d) = HolePlacement.canonical(a, b)
        val t = Math.toRadians(angleDeg)
        // Drehung von n um d (d ⟂ n): n' = n·cos t + (d × n)·sin t
        val rotated = n * cos(t) + (d cross n) * sin(t)
        return Workplane(start, d, rotated)
    }

    /** Lage der Arbeitsebene im Körper; `null`, wenn Fläche oder Kante nicht (mehr) passend existieren. */
    fun resolve(body: BrepShape, def: PlaneDef): Workplane? {
        val ids = body.facesWithTag(def.face.tag)
        if (ids.isEmpty()) return null
        val planes = body.planarFaces()
        val face = ids.firstNotNullOfOrNull { planes.getOrNull(it - 1) } ?: return null
        return when (def) {
            is PlaneDef.Offset -> offset(face, def.distance)
            is PlaneDef.AtEdge -> {
                val infos = body.edges()
                fun v(t: Triple<Double, Double, Double>) = Vec3(t.first, t.second, t.third)
                val edge = body.edgesBetween(def.edge.a.tag, def.edge.b.tag)
                    .mapNotNull { id -> infos.firstOrNull { it.id == id } }
                    .firstOrNull { it.isLine && abs(face.distanceTo(it.start.first, it.start.second, it.start.third)) < 1e-4 && abs(face.distanceTo(it.end.first, it.end.second, it.end.third)) < 1e-4 }
                    ?: return null
                atEdge(Vec3(face.normal.first, face.normal.second, face.normal.third), v(edge.start), v(edge.end), def.angle)
            }
        }
    }
}
