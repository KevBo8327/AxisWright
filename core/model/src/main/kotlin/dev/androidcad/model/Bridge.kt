package dev.androidcad.model

import dev.androidcad.brep.Profile
import dev.androidcad.brep.ProfileSegment
import dev.androidcad.sketch.Loop
import dev.androidcad.sketch.LoopSegment
import dev.androidcad.sketch.Region

/**
 * Brücke Skizze → Körper: übersetzt eine Skizzenfläche in ein Profil für die B-Rep-Extrusion.
 * Mit [feature] erhalten die Seitenflächen des Körpers dauerhafte Namen ([FaceRef], Rolle [FaceRole.SIDE], Element = Skizzenelement).
 */
fun Region.toProfile(feature: FeatureId? = null): Profile =
    Profile(outer.toSegments(feature), holes.map { it.toSegments(feature) })

private fun Loop.toSegments(feature: FeatureId?): List<ProfileSegment> = segments.map {
    val tag = if (feature != null && it.source > 0) faceTag(feature, FaceRole.SIDE, it.source) else 0L
    when (it) {
        is LoopSegment.Line -> ProfileSegment.Line(it.x1, it.y1, it.x2, it.y2, tag)
        is LoopSegment.Arc -> ProfileSegment.Arc(it.x1, it.y1, it.x2, it.y2, it.cx, it.cy, it.ccw, tag)
        is LoopSegment.Circle -> ProfileSegment.Circle(it.cx, it.cy, it.r, tag)
    }
}
