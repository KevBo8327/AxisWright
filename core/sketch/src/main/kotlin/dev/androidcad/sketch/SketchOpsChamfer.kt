package dev.androidcad.sketch

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Fase an der Ecke [corner] zwischen genau zwei Linien: Beide Linien werden um [distance] gekürzt und mit einer neuen geraden Linie
 * verbunden (gleiche Schenkellänge). Wie bei [SketchOps.fillet] entfallen die Bedingungen am Eckpunkt und die Längenmaße der gekürzten
 * Linien; die neue Linie bekommt ihr Längenmaß.
 */
public fun SketchOps.chamfer(s: Sketch, corner: PointId, distance: Double): OpResult {
    if (!(distance > 0) || !distance.isFinite()) return OpResult.fail(SketchText.DISTANCE_NOT_POSITIVE)
    val users = s.elementsUsing(corner)
    if (users.size != 2 || users.any { it !is LineId }) return OpResult.fail(SketchText.CORNER_NEEDS_TWO_LINES)
    val l1 = users[0] as LineId; val l2 = users[1] as LineId
    val d1 = s.line(l1); val d2 = s.line(l2)
    val c = s.point(corner); val p1 = s.point(if (d1.p1 == corner) d1.p2 else d1.p1); val p2 = s.point(if (d2.p1 == corner) d2.p2 else d2.p1)
    val len1 = hypot(p1.x - c.x, p1.y - c.y); val len2 = hypot(p2.x - c.x, p2.y - c.y)
    if (len1 < 1e-9 || len2 < 1e-9) return OpResult.fail(SketchText.A_LINE_NO_LENGTH)
    val u1x = (p1.x - c.x) / len1; val u1y = (p1.y - c.y) / len1
    val u2x = (p2.x - c.x) / len2; val u2y = (p2.y - c.y) / len2
    val theta = acos((u1x * u2x + u1y * u2y).coerceIn(-1.0, 1.0)) // Winkel zwischen den Linien
    if (theta < 1e-3 || theta > PI - 1e-3) return OpResult.fail(SketchText.LINES_COLLINEAR)
    if (distance >= len1 - 1e-9 || distance >= len2 - 1e-9) return OpResult.fail(SketchText.CHAMFER_TOO_LARGE)

    val pt1 = s.addPoint(c.x + u1x * distance, c.y + u1y * distance); val pt2 = s.addPoint(c.x + u2x * distance, c.y + u2y * distance)
    s.setLineEndpoints(l1, if (d1.p1 == corner) pt1 else d1.p1, if (d1.p2 == corner) pt1 else d1.p2)
    s.setLineEndpoints(l2, if (d2.p1 == corner) pt2 else d2.p1, if (d2.p2 == corner) pt2 else d2.p2)
    val edge = s.addLine(pt1, pt2)
    dropLengthConstraints(s, l1); dropLengthConstraints(s, l2)
    s.remove(corner) // Maße und Constraints am Eckpunkt entfallen
    s.add(LengthDim(edge, 2 * distance * sin(theta / 2)))
    return OpResult.ok()
}
