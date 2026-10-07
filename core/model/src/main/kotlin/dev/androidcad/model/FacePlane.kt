package dev.androidcad.model

import dev.androidcad.brep.PlanarFace
import kotlin.math.abs

/**
 * Skizzenebene auf einer ebenen Körperfläche. Die Ebene hängt nur von der Fläche ab und ist damit nach Änderungen am Körper
 * reproduzierbar: **Normale** = Außennormale der Fläche, **Ursprung** = Fußpunkt des Weltursprungs auf der Ebene,
 * **x-Richtung** = `Z × Normale` (waagerecht; bei waagerechten Flächen +X). Für Draufsicht, Vorder- und Seitenansicht ergibt das
 * genau die Ebenen XY, XZ und YZ.
 */
object FacePlane {
    fun workplane(face: PlanarFace): Workplane {
        val n = Vec3(face.normal.first, face.normal.second, face.normal.third).normalized()
        val offset = n.dot(Vec3(face.origin.first, face.origin.second, face.origin.third))
        val xDir = if (abs(n.z) > 0.999) Vec3(1.0, 0.0, 0.0) else (Vec3(0.0, 0.0, 1.0) cross n).normalized()
        return Workplane(n * offset, xDir, n)
    }
}
