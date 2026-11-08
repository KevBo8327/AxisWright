package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Transformationen für Skizzen-Auswahlen: Verschieben, Drehen, Skalieren und Kopieren.
 *
 * Widersprüchliche Bedingungen (Fixierungen an veränderten Punkten, Maße zum Ursprung oder zu nicht gewählten Elementen,
 * Horizontal/Vertikal bei beliebiger Drehung) werden entfernt oder sinnvoll angepasst (z. B. 90°-Drehung tauscht Horizontal
 * und Vertikal; Längenmaße wachsen beim Skalieren mit dem Faktor mit).
 */

private const val EPS = 1e-9

/** Alle beteiligten Punkte einer Auswahl (inkl. End-, Mittel- und Bogenpunkte sowie Basispunkte von Texten). */
private fun collectPoints(
    s: Sketch,
    points: Collection<PointId>,
    lines: Collection<LineId>,
    rounds: Collection<RoundId>,
    texts: Collection<TextId> = emptyList(),
): LinkedHashSet<PointId> {
    val set = LinkedHashSet<PointId>()
    for (p in points) if (s.exists(p)) set.add(p)
    for (l in lines) if (s.exists(l)) {
        val d = s.line(l)
        set.add(d.p1); set.add(d.p2)
    }
    for (r in rounds) if (s.exists(r)) when (r) {
        is CircleId -> set.add(s.circle(r).center)
        is ArcId -> {
            val a = s.arc(r)
            set.add(a.center); set.add(a.start); set.add(a.end)
        }
    }
    for (t in texts) if (s.exists(t)) {
        set.add(s.text(t).location)
    }
    return set
}

/** Prüft, ob [angleDegrees] ein Vielfaches von 90° ist; gibt 0, 1 (90°), 2 (180°) oder 3 (270°) zurück, sonst `null`. */
private fun angleStep90(angleDegrees: Double): Int? {
    val k = Math.round(angleDegrees / 90.0).toInt()
    return if (abs(angleDegrees - k * 90.0) < 1e-6) ((k % 4) + 4) % 4 else null
}

/** Verschiebt die gewählten Elemente um [dx] und [dy] (in mm). */
public fun SketchOps.translate(
    s: Sketch,
    lines: Collection<LineId> = emptyList(),
    rounds: Collection<RoundId> = emptyList(),
    points: Collection<PointId> = emptyList(),
    texts: Collection<TextId> = emptyList(),
    dx: Double,
    dy: Double,
): OpResult {
    if (!dx.isFinite() || !dy.isFinite()) return OpResult.fail(SketchText.NOT_POSSIBLE)
    val pts = collectPoints(s, points, lines, rounds, texts)
    val selTexts = texts.filter { s.exists(it) }
    if (pts.isEmpty() && selTexts.isEmpty()) return OpResult.fail(SketchText.NOTHING_TO_TRANSFORM)
    if (abs(dx) < EPS && abs(dy) < EPS) return OpResult.ok()

    val selPts = pts.toSet()
    val selLines = lines.filter { s.exists(it) }.toSet()
    val selRounds = rounds.filter { s.exists(it) }.toSet()

    var removed = 0
    var adapted = 0

    // Widersprüchliche Constraints prüfen und bereinigen
    for (entry in s.constraintEntries) {
        val c = entry.constraint
        val touches = c.refs.any { it in selPts || it in selLines || it in selRounds }
        if (!touches) continue

        val hasExternalRef = c.refs.any { r ->
            r == s.origin || (r is PointId && r !in selPts) || (r is LineId && r !in selLines) || (r is RoundId && r !in selRounds)
        }

        if (hasExternalRef || c is Fixed) {
            s.remove(entry.id)
            removed++
        }
    }

    // Punkte verschieben
    for (p in pts) {
        if (p == s.origin) continue
        val pt = s.point(p)
        s.movePoint(p, pt.x + dx, pt.y + dy)
    }

    for (t in selTexts) {
        if (s.text(t).location == s.origin) {
            val pt = s.point(s.origin)
            s.setTextLocation(t, s.addPoint(pt.x + dx, pt.y + dy))
        }
    }

    return makeTransformResult(adapted, removed)
}

/** Dreht die gewählten Elemente um den Bezugspunkt [pivot] um [angleDegrees] Grad gegen den Uhrzeigersinn. */
public fun SketchOps.rotate(
    s: Sketch,
    lines: Collection<LineId> = emptyList(),
    rounds: Collection<RoundId> = emptyList(),
    points: Collection<PointId> = emptyList(),
    texts: Collection<TextId> = emptyList(),
    pivot: Vec2,
    angleDegrees: Double,
): OpResult {
    if (!angleDegrees.isFinite()) return OpResult.fail(SketchText.NOT_POSSIBLE)
    val pts = collectPoints(s, points, lines, rounds, texts)
    val selTexts = texts.filter { s.exists(it) }
    if (pts.isEmpty() && selTexts.isEmpty()) return OpResult.fail(SketchText.NOTHING_TO_TRANSFORM)
    val step90 = angleStep90(angleDegrees)
    if (step90 == 0) return OpResult.ok()

    val selPts = pts.toSet()
    val selLines = lines.filter { s.exists(it) }.toSet()
    val selRounds = rounds.filter { s.exists(it) }.toSet()

    var removed = 0
    var adapted = 0

    val rad = Math.toRadians(angleDegrees)
    val cosA = cos(rad)
    val sinA = sin(rad)

    // Constraints anpassen oder entfernen
    for (entry in s.constraintEntries) {
        val c = entry.constraint
        val touches = c.refs.any { it in selPts || it in selLines || it in selRounds }
        if (!touches) continue

        val hasExternalRef = c.refs.any { r ->
            r == s.origin || (r is PointId && r !in selPts) || (r is LineId && r !in selLines) || (r is RoundId && r !in selRounds)
        }

        if (hasExternalRef) {
            s.remove(entry.id)
            removed++
            continue
        }

        if (c is Fixed) {
            val pt = s.point(c.point)
            if (hypot(pt.x - pivot.x, pt.y - pivot.y) > EPS) {
                s.remove(entry.id)
                removed++
            }
            continue
        }

        // Richtungsbedingungen bei Drehung
        if (step90 != null) {
            if (step90 == 1 || step90 == 3) {
                when (c) {
                    is Horizontal -> { s.replace(entry.id, Vertical(c.line)); adapted++ }
                    is Vertical -> { s.replace(entry.id, Horizontal(c.line)); adapted++ }
                    is HorizontalPoints -> { s.replace(entry.id, VerticalPoints(c.a, c.b)); adapted++ }
                    is VerticalPoints -> { s.replace(entry.id, HorizontalPoints(c.a, c.b)); adapted++ }
                    is HorizontalDistanceDim -> {
                        s.replace(entry.id, VerticalDistanceDim(c.a, c.b, if (step90 == 1) c.value else -c.value))
                        adapted++
                    }
                    is VerticalDistanceDim -> {
                        s.replace(entry.id, HorizontalDistanceDim(c.a, c.b, if (step90 == 1) -c.value else c.value))
                        adapted++
                    }
                    else -> Unit
                }
            } else if (step90 == 2) {
                when (c) {
                    is HorizontalDistanceDim -> {
                        s.replace(entry.id, HorizontalDistanceDim(c.a, c.b, -c.value))
                        adapted++
                    }
                    is VerticalDistanceDim -> {
                        s.replace(entry.id, VerticalDistanceDim(c.a, c.b, -c.value))
                        adapted++
                    }
                    else -> Unit
                }
            }
        } else {
            // Nicht-90°-Drehung: Horizontale/Vertikale Bedingungen widersprechen und müssen weichen
            when (c) {
                is Horizontal, is Vertical, is HorizontalPoints, is VerticalPoints, is HorizontalDistanceDim, is VerticalDistanceDim -> {
                    s.remove(entry.id)
                    removed++
                }
                else -> Unit
            }
        }
    }

    // Punkte drehen
    for (p in pts) {
        if (p == s.origin) continue
        val pt = s.point(p)
        val rx = pt.x - pivot.x
        val ry = pt.y - pivot.y
        s.movePoint(p, pivot.x + rx * cosA - ry * sinA, pivot.y + rx * sinA + ry * cosA)
    }

    for (t in selTexts) {
        if (s.text(t).location == s.origin) {
            val pt = s.point(s.origin)
            val rx = pt.x - pivot.x
            val ry = pt.y - pivot.y
            s.setTextLocation(t, s.addPoint(pivot.x + rx * cosA - ry * sinA, pivot.y + rx * sinA + ry * cosA))
        }
        val d = s.text(t)
        s.setText(t, d.text, d.height, d.angle + angleDegrees)
    }

    return makeTransformResult(adapted, removed)
}

/** Skaliert die gewählten Elemente um den Bezugspunkt [pivot] um den Faktor [factor] (> 0). */
public fun SketchOps.scale(
    s: Sketch,
    lines: Collection<LineId> = emptyList(),
    rounds: Collection<RoundId> = emptyList(),
    points: Collection<PointId> = emptyList(),
    texts: Collection<TextId> = emptyList(),
    pivot: Vec2,
    factor: Double,
): OpResult {
    if (!factor.isFinite() || factor <= 0.0) return OpResult.fail(SketchText.SCALE_NOT_POSITIVE)
    val pts = collectPoints(s, points, lines, rounds, texts)
    val selTexts = texts.filter { s.exists(it) }
    if (pts.isEmpty() && selTexts.isEmpty()) return OpResult.fail(SketchText.NOTHING_TO_TRANSFORM)
    if (abs(factor - 1.0) < EPS) return OpResult.ok()

    val selPts = pts.toSet()
    val selLines = lines.filter { s.exists(it) }.toSet()
    val selRounds = rounds.filter { s.exists(it) }.toSet()

    var removed = 0
    var adapted = 0

    // Constraints anpassen oder entfernen
    for (entry in s.constraintEntries) {
        val c = entry.constraint
        val touches = c.refs.any { it in selPts || it in selLines || it in selRounds }
        if (!touches) continue

        val hasExternalRef = c.refs.any { r ->
            r == s.origin || (r is PointId && r !in selPts) || (r is LineId && r !in selLines) || (r is RoundId && r !in selRounds)
        }

        if (hasExternalRef) {
            s.remove(entry.id)
            removed++
            continue
        }

        if (c is Fixed) {
            val pt = s.point(c.point)
            if (hypot(pt.x - pivot.x, pt.y - pivot.y) > EPS) {
                s.remove(entry.id)
                removed++
            }
            continue
        }

        // Längenmaße anpassen
        when (c) {
            is LengthDim -> { s.replace(entry.id, c.copy(value = c.value * factor)); adapted++ }
            is DistanceDim -> { s.replace(entry.id, c.copy(value = c.value * factor)); adapted++ }
            is HorizontalDistanceDim -> { s.replace(entry.id, c.copy(value = c.value * factor)); adapted++ }
            is VerticalDistanceDim -> { s.replace(entry.id, c.copy(value = c.value * factor)); adapted++ }
            is RadiusDim -> { s.replace(entry.id, c.copy(value = c.value * factor)); adapted++ }
            is DiameterDim -> { s.replace(entry.id, c.copy(value = c.value * factor)); adapted++ }
            is PointLineDistanceDim -> { s.replace(entry.id, c.copy(value = c.value * factor)); adapted++ }
            else -> Unit
        }
    }

    // Punkte skalieren
    for (p in pts) {
        if (p == s.origin) continue
        val pt = s.point(p)
        s.movePoint(p, pivot.x + (pt.x - pivot.x) * factor, pivot.y + (pt.y - pivot.y) * factor)
    }

    // Kreisradien anpassen
    for (r in selRounds) if (r is CircleId) {
        val circ = s.circle(r)
        s.setRadius(r, circ.radius * factor)
    }

    for (t in selTexts) {
        if (s.text(t).location == s.origin) {
            val pt = s.point(s.origin)
            s.setTextLocation(t, s.addPoint(pivot.x + (pt.x - pivot.x) * factor, pivot.y + (pt.y - pivot.y) * factor))
        }
        val d = s.text(t)
        s.setText(t, d.text, d.height * factor, d.angle)
    }

    return makeTransformResult(adapted, removed)
}

/**
 * Erzeugt eine Kopie der gewählten Elemente, transformiert um [dx], [dy], [angleDegrees] und [scale].
 * Bedingungen und Maße innerhalb der Auswahl werden mitkopiert.
 */
public fun SketchOps.copy(
    s: Sketch,
    lines: Collection<LineId> = emptyList(),
    rounds: Collection<RoundId> = emptyList(),
    points: Collection<PointId> = emptyList(),
    texts: Collection<TextId> = emptyList(),
    dx: Double = 0.0,
    dy: Double = 0.0,
    pivot: Vec2 = Vec2(0.0, 0.0),
    angleDegrees: Double = 0.0,
    scale: Double = 1.0,
): OpResult {
    if (!scale.isFinite() || scale <= 0.0) return OpResult.fail(SketchText.SCALE_NOT_POSITIVE)
    if (!dx.isFinite() || !dy.isFinite() || !angleDegrees.isFinite()) return OpResult.fail(SketchText.NOT_POSSIBLE)
    val pts = collectPoints(s, points, lines, rounds, texts)
    val selTexts = texts.filter { s.exists(it) }
    if (pts.isEmpty() && selTexts.isEmpty()) return OpResult.fail(SketchText.NOTHING_TO_TRANSFORM)

    val selPts = pts.toSet()
    val selLines = lines.filter { s.exists(it) }.toSet()
    val selRounds = rounds.filter { s.exists(it) }.toSet()

    val rad = Math.toRadians(angleDegrees)
    val cosA = cos(rad)
    val sinA = sin(rad)
    val step90 = angleStep90(angleDegrees)

    fun transformPos(v: Vec2): Vec2 {
        val sx = pivot.x + (v.x - pivot.x) * scale
        val sy = pivot.y + (v.y - pivot.y) * scale
        val rx = sx - pivot.x
        val ry = sy - pivot.y
        return Vec2(pivot.x + rx * cosA - ry * sinA + dx, pivot.y + rx * sinA + ry * cosA + dy)
    }

    val pointMap = LinkedHashMap<PointId, PointId>()
    for (p in pts) {
        val q = transformPos(s.point(p))
        val copyP = s.addPoint(q.x, q.y)
        if (s.isStandalone(p)) s.markStandalone(copyP)
        pointMap[p] = copyP
    }

    val lineMap = LinkedHashMap<LineId, LineId>()
    for (l in selLines) {
        val d = s.line(l)
        lineMap[l] = s.addLine(pointMap.getValue(d.p1), pointMap.getValue(d.p2), d.construction)
    }

    val roundMap = LinkedHashMap<RoundId, RoundId>()
    for (r in selRounds) when (r) {
        is CircleId -> {
            val c = s.circle(r)
            roundMap[r] = s.addCircle(pointMap.getValue(c.center), c.radius * scale, c.construction)
        }
        is ArcId -> {
            val a = s.arc(r)
            roundMap[r] = s.addArc(pointMap.getValue(a.center), pointMap.getValue(a.start), pointMap.getValue(a.end), a.construction)
        }
    }

    // Bedingungen mitkopieren (nur wenn alle Referenzen innerhalb der Auswahl liegen)
    for (entry in s.constraintEntries) {
        val c = entry.constraint
        if (s.origin in c.refs) continue
        val allInside = c.refs.all { r ->
            (r is PointId && r in selPts) || (r is LineId && r in selLines) || (r is RoundId && r in selRounds)
        }
        if (!allInside) continue

        when (c) {
            is Horizontal -> {
                if (step90 != null) {
                    val line = lineMap.getValue(c.line)
                    if (step90 == 1 || step90 == 3) s.add(Vertical(line)) else s.add(Horizontal(line))
                }
            }
            is Vertical -> {
                if (step90 != null) {
                    val line = lineMap.getValue(c.line)
                    if (step90 == 1 || step90 == 3) s.add(Horizontal(line)) else s.add(Vertical(line))
                }
            }
            is HorizontalPoints -> {
                if (step90 != null) {
                    val pa = pointMap.getValue(c.a); val pb = pointMap.getValue(c.b)
                    if (step90 == 1 || step90 == 3) s.add(VerticalPoints(pa, pb)) else s.add(HorizontalPoints(pa, pb))
                }
            }
            is VerticalPoints -> {
                if (step90 != null) {
                    val pa = pointMap.getValue(c.a); val pb = pointMap.getValue(c.b)
                    if (step90 == 1 || step90 == 3) s.add(HorizontalPoints(pa, pb)) else s.add(VerticalPoints(pa, pb))
                }
            }
            is Parallel -> s.add(Parallel(lineMap.getValue(c.a), lineMap.getValue(c.b)))
            is Perpendicular -> s.add(Perpendicular(lineMap.getValue(c.a), lineMap.getValue(c.b)))
            is Collinear -> s.add(Collinear(lineMap.getValue(c.a), lineMap.getValue(c.b)))
            is EqualLength -> s.add(EqualLength(lineMap.getValue(c.a), lineMap.getValue(c.b)))
            is EqualRadius -> s.add(EqualRadius(roundMap.getValue(c.a), roundMap.getValue(c.b)))
            is Concentric -> s.add(Concentric(roundMap.getValue(c.a), roundMap.getValue(c.b)))
            is TangentLineRound -> s.add(TangentLineRound(lineMap.getValue(c.line), roundMap.getValue(c.round)))
            is TangentRoundRound -> s.add(TangentRoundRound(roundMap.getValue(c.a), roundMap.getValue(c.b), c.internal, c.sign))
            is PointOnLine -> s.add(PointOnLine(pointMap.getValue(c.point), lineMap.getValue(c.line)))
            is PointOnRound -> s.add(PointOnRound(pointMap.getValue(c.point), roundMap.getValue(c.round)))
            is Midpoint -> s.add(Midpoint(pointMap.getValue(c.point), lineMap.getValue(c.line)))
            is Coincident -> s.add(Coincident(pointMap.getValue(c.a), pointMap.getValue(c.b)))
            is Symmetric -> if (c.axis in lineMap) s.add(Symmetric(pointMap.getValue(c.a), pointMap.getValue(c.b), lineMap.getValue(c.axis)))
            is LengthDim -> s.add(LengthDim(lineMap.getValue(c.line), c.value * scale))
            is DistanceDim -> s.add(DistanceDim(pointMap.getValue(c.a), pointMap.getValue(c.b), c.value * scale))
            is HorizontalDistanceDim -> {
                val pa = pointMap.getValue(c.a); val pb = pointMap.getValue(c.b)
                when (step90) {
                    0 -> s.add(HorizontalDistanceDim(pa, pb, c.value * scale))
                    1 -> s.add(VerticalDistanceDim(pa, pb, c.value * scale))
                    2 -> s.add(HorizontalDistanceDim(pa, pb, -c.value * scale))
                    3 -> s.add(VerticalDistanceDim(pa, pb, -c.value * scale))
                    else -> s.add(DistanceDim(pa, pb, abs(c.value) * scale))
                }
            }
            is VerticalDistanceDim -> {
                val pa = pointMap.getValue(c.a); val pb = pointMap.getValue(c.b)
                when (step90) {
                    0 -> s.add(VerticalDistanceDim(pa, pb, c.value * scale))
                    1 -> s.add(HorizontalDistanceDim(pa, pb, -c.value * scale))
                    2 -> s.add(VerticalDistanceDim(pa, pb, -c.value * scale))
                    3 -> s.add(HorizontalDistanceDim(pa, pb, c.value * scale))
                    else -> s.add(DistanceDim(pa, pb, abs(c.value) * scale))
                }
            }
            is RadiusDim -> s.add(RadiusDim(roundMap.getValue(c.round), c.value * scale))
            is DiameterDim -> s.add(DiameterDim(roundMap.getValue(c.round), c.value * scale))
            is AngleDim -> s.add(AngleDim(lineMap.getValue(c.a), lineMap.getValue(c.b), c.value))
            is PointLineDistanceDim -> s.add(PointLineDistanceDim(pointMap.getValue(c.point), lineMap.getValue(c.line), c.value * scale, c.side))
            is Fixed -> Unit // Fixed wird nicht auf die Kopie übertragen
            is Translated, is Rotated -> Unit
        }
    }

    // Texte kopieren
    for (t in selTexts) {
        val d = s.text(t)
        val copyLoc = pointMap[d.location] ?: run {
            val q = transformPos(s.point(d.location))
            val newP = s.addPoint(q.x, q.y)
            pointMap[d.location] = newP
            newP
        }
        s.addText(copyLoc, d.text, d.height * scale, d.angle + angleDegrees, d.construction)
    }

    return OpResult.ok(SketchText.COPIES_CREATED, 1)
}

private fun makeTransformResult(adapted: Int, removed: Int): OpResult = when {
    removed > 0 -> OpResult.ok(SketchText.CONSTRAINTS_REMOVED, removed)
    adapted > 0 -> OpResult.ok(SketchText.CONSTRAINTS_ADAPTED, adapted)
    else -> OpResult.ok()
}
