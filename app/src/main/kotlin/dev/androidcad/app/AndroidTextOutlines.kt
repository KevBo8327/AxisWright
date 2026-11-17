package dev.androidcad.app

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import dev.androidcad.sketch.Loop
import dev.androidcad.sketch.LoopSegment
import dev.androidcad.sketch.TextOutlines
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Erzeugt Konturen für Text mithilfe von Androids [Paint.getTextPath] und [Path.approximate].
 */
object AndroidTextOutlines : TextOutlines {
    fun init() {
        TextOutlines.default = this
    }

    override fun outlines(text: String, height: Double): List<Loop> {
        if (text.isEmpty() || height <= 0.0) return emptyList()

        val paint = Paint().apply {
            typeface = Typeface.DEFAULT
            textSize = height.toFloat()
            isAntiAlias = true
        }

        val rawPath = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, rawPath)
        // Schriften (z. B. variable Roboto-Glyphen wie 'B') können überlappende Teilkonturen enthalten;
        // Vereinigung mit sich selbst löst Selbstüberschneidungen über Skia-PathOps in disjunkte Konturen auf.
        val path = Path()
        val simplified = path.op(rawPath, Path(rawPath), Path.Op.UNION) && !path.isEmpty
        val measure = PathMeasure(if (simplified) path else rawPath, false)
        val loops = ArrayList<Loop>()
        val contourPath = Path()
        // Akzeptabler Fehler für Path.approximate: 0.5% der Höhe, zwischen 0.01 mm und 0.1 mm
        val error = (height * 0.005).toFloat().coerceIn(0.01f, 0.1f)

        do {
            val len = measure.length
            if (len <= 0f) continue
            contourPath.reset()
            measure.getSegment(0f, len, contourPath, true)
            contourPath.close()

            val approx = contourPath.approximate(error)
            val n = approx.size / 3
            if (n < 3) continue

            val rawPoints = ArrayList<Pair<Double, Double>>(n)
            for (i in 0 until n) {
                rawPoints += approx[i * 3 + 1].toDouble() to -approx[i * 3 + 2].toDouble() // Android: Y nach unten, CAD: Y nach oben
            }
            val minSegLen = (error * 0.1).coerceAtLeast(1e-3)
            val collinearTol = (error * 0.25).coerceAtLeast(1e-4)
            val segs = simplifyClosedContour(rawPoints, minSegLen, collinearTol)
            if (segs.size >= 3) {
                val area = abs(signedArea(segs))
                if (area > 1e-6) {
                    loops += Loop(segs, area)
                }
            }
        } while (measure.nextContour())

        return loops
    }

    private fun signedArea(segs: List<LoopSegment>): Double {
        var a = 0.0
        for (s in segs) {
            if (s is LoopSegment.Line) {
                a += s.x1 * s.y2 - s.x2 * s.y1
            }
        }
        return a / 2.0
    }
}

/**
 * Bereinigt eine geschlossene Kontur aus [Path.approximate]: fasst Punkte mit Abstand ≤ [minSegmentLength]
 * zusammen, entfernt kollineare Zwischenpunkte sowie spitze Rücksprünge (Spikes) und garantiert eine
 * lückenlos geschlossene Segmentfolge.
 */
internal fun simplifyClosedContour(
    points: List<Pair<Double, Double>>,
    minSegmentLength: Double,
    collinearTolerance: Double,
): List<LoopSegment.Line> {
    if (points.size < 3) return emptyList()
    val pts = ArrayList<Pair<Double, Double>>(points.size)
    for (p in points) {
        if (pts.isEmpty()) {
            pts += p
        } else {
            val prev = pts.last()
            if (hypot(p.first - prev.first, p.second - prev.second) > minSegmentLength) {
                pts += p
            }
        }
    }
    while (pts.size >= 3) {
        val first = pts.first()
        val last = pts.last()
        if (hypot(first.first - last.first, first.second - last.second) <= minSegmentLength) {
            pts.removeAt(pts.lastIndex)
        } else {
            break
        }
    }
    if (pts.size < 3) return emptyList()

    var changed = true
    while (changed && pts.size > 3) {
        changed = false
        var i = 0
        while (i < pts.size && pts.size > 3) {
            val a = pts[(i - 1 + pts.size) % pts.size]
            val b = pts[i]
            val c = pts[(i + 1) % pts.size]
            val abx = b.first - a.first; val aby = b.second - a.second
            val acx = c.first - a.first; val acy = c.second - a.second
            val bcx = c.first - b.first; val bcy = c.second - b.second
            val abLen = hypot(abx, aby)
            val acLen = hypot(acx, acy)
            val bcLen = hypot(bcx, bcy)
            val cross = abs(abx * acy - aby * acx)
            val perpDist = if (acLen > 1e-12) cross / acLen else minOf(abLen, bcLen)
            val dotForward = abx * bcx + aby * bcy
            val shortOrSpike = abLen <= minSegmentLength || bcLen <= minSegmentLength || acLen <= minSegmentLength
            val collinearOrFoldback = perpDist <= collinearTolerance && (dotForward >= 0.0 || minOf(abLen, bcLen) <= collinearTolerance * 4.0)
            if (shortOrSpike || collinearOrFoldback) {
                pts.removeAt(i)
                changed = true
            } else {
                i++
            }
        }
    }
    if (pts.size < 3) return emptyList()

    val segs = ArrayList<LoopSegment.Line>(pts.size)
    for (i in pts.indices) {
        val p1 = pts[i]
        val p2 = pts[(i + 1) % pts.size]
        if (hypot(p2.first - p1.first, p2.second - p1.second) <= 1e-6) return emptyList()
        segs += LoopSegment.Line(p1.first, p1.second, p2.first, p2.second)
    }
    return segs
}
