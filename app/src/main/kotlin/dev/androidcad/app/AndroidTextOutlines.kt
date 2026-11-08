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

        val measure = PathMeasure(rawPath, false)
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

            val segs = ArrayList<LoopSegment>()
            for (i in 0 until n - 1) {
                val x1 = approx[i * 3 + 1].toDouble()
                val y1 = -approx[i * 3 + 2].toDouble() // Android: Y nach unten, CAD: Y nach oben
                val x2 = approx[(i + 1) * 3 + 1].toDouble()
                val y2 = -approx[(i + 1) * 3 + 2].toDouble()
                if (hypot(x2 - x1, y2 - y1) > 1e-6) {
                    segs += LoopSegment.Line(x1, y1, x2, y2)
                }
            }
            val firstX = approx[1].toDouble(); val firstY = -approx[2].toDouble()
            val lastX = approx[(n - 1) * 3 + 1].toDouble(); val lastY = -approx[(n - 1) * 3 + 2].toDouble()
            if (hypot(firstX - lastX, firstY - lastY) > 1e-6) {
                segs += LoopSegment.Line(lastX, lastY, firstX, firstY)
            }
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
