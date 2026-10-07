package dev.androidcad.sketch

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Zufallstests: Aus einer realisierbaren Skizze („Wahrheit“) werden Maße gemessen, dann wird die Zeichnung
 * verwackelt. Der Solver muss sie wieder in Einklang mit allen Maßen bringen.
 */
class RobustnessTest {
    private class Case(val sketch: Sketch, val points: List<PointId>, val truth: List<Vec2>)

    /** Zufälliges Polygon mit Längen-, Winkel- und Diagonalmaßen, das exakt durch die „Wahrheit“ erfüllt wird. */
    private fun randomPolygon(rnd: Random, n: Int, wobble: Double): Case {
        val s = Sketch()
        val size = 50.0 + rnd.nextDouble() * 100
        val truth = (0 until n).map { i ->
            val a = 2 * PI * i / n + (rnd.nextDouble() - 0.5) * 0.3
            val r = size * (0.7 + 0.3 * rnd.nextDouble())
            Vec2(r * cos(a), r * sin(a))
        }
        // gezeichnet wird eine verwackelte Fassung
        val pts = truth.map { s.addPoint(it.x + (rnd.nextDouble() - 0.5) * 2 * wobble * size, it.y + (rnd.nextDouble() - 0.5) * 2 * wobble * size) }
        val lines = (0 until n).map { s.addLine(pts[it], pts[(it + 1) % n]) }
        // Maße aus der Wahrheit: setze die Skizze kurz auf die Wahrheit, messe, und stelle die Zeichnung wieder her
        val drawn = pts.map { s.point(it) }
        pts.forEachIndexed { i, p -> s.movePoint(p, truth[i].x, truth[i].y) }
        s.add(Fixed(pts[0], truth[0].x, truth[0].y))
        s.add(Fixed(pts[1], null, truth[1].y))
        lines.forEach { s.addLength(it) }
        for (i in 0 until n) s.addAngle(lines[i], lines[(i + 1) % n])
        for (i in 2 until n - 1) s.addDistance(pts[0], pts[i]) // Diagonalen machen das Polygon starr
        pts.forEachIndexed { i, p -> s.movePoint(p, drawn[i].x, drawn[i].y) }
        return Case(s, pts, truth)
    }

    private fun satisfied(s: Sketch): Boolean = s.constraintEntries.all { (_, c) ->
        when (c) {
            is Dimension -> {
                val v = s.measure(c)
                val target = c.value
                val diff = if (c is AngleDim) Math.floorMod(Math.round((v - target) * 1e9), 360_000_000_000L).let { d -> minOf(d, 360_000_000_000L - d) } / 1e9 else abs(v - target)
                diff < 1e-5
            }
            is Fixed -> true
            else -> true
        }
    }

    @Test
    fun randomPolygonsConvergeFromWobblyDrawings() {
        val rnd = Random(42)
        var solved = 0
        val total = 300
        val failures = ArrayList<String>()
        repeat(total) { k ->
            val n = 4 + rnd.nextInt(5)
            val c = randomPolygon(rnd, n, wobble = 0.05)
            val res = c.sketch.solve()
            if (res.isSolved && satisfied(c.sketch)) solved++ else failures += "Fall $k (n=$n): ${res.status}, Residuum ${res.maxResidual}"
        }
        println("Zufallspolygone: $solved/$total gelöst")
        assertTrue(solved >= total * 0.97, "Nur $solved/$total gelöst. Beispiele: ${failures.take(5)}")
    }

    @Test
    fun fullyDimensionedPolygonsReportZeroDegreesOfFreedom() {
        val rnd = Random(7)
        var checked = 0
        repeat(100) {
            val c = randomPolygon(rnd, 5 + rnd.nextInt(3), wobble = 0.02)
            val res = c.sketch.solve()
            if (res.isSolved) {
                checked++
                assertTrue(res.degreesOfFreedom == 0 || res.redundant.isNotEmpty(), "DOF=${res.degreesOfFreedom}")
            }
        }
        assertTrue(checked > 80, "zu wenige lösbare Fälle: $checked")
    }

    @Test
    fun draggingRandomPointsNeverBreaksConstraints() {
        val rnd = Random(99)
        var solvedSteps = 0
        var steps = 0
        repeat(40) {
            val n = 4 + rnd.nextInt(4)
            val c = randomPolygon(rnd, n, wobble = 0.0)
            // Gelockert: nur Längen behalten → Polygon ist biegsam (Gelenkkette) und lässt sich ziehen
            val s = c.sketch
            s.constraintEntries.filter { it.constraint is AngleDim || (it.constraint is DistanceDim) }.forEach { s.remove(it.id) }
            assertTrue(s.solve().isSolved)
            val drag = s.beginDrag()
            val p = c.points[2 + rnd.nextInt(n - 2)]
            repeat(10) {
                val before = s.constraintEntries
                val tx = s.point(p).x + (rnd.nextDouble() - 0.5) * 30
                val ty = s.point(p).y + (rnd.nextDouble() - 0.5) * 30
                val res = drag.dragTo(mapOf(p to Vec2(tx, ty)))
                steps++
                if (res.isSolved) solvedSteps++
                // Egal ob erreicht oder nicht: die Maße gelten immer (bei Misserfolg bleibt der letzte gültige Stand)
                assertTrue(satisfied(s), "Maße verletzt nach Ziehen (n=$n)")
                assertTrue(before == s.constraintEntries)
            }
            drag.finish()
        }
        println("Ziehschritte gelöst: $solvedSteps/$steps")
        assertTrue(solvedSteps >= steps * 0.9, "Nur $solvedSteps/$steps Ziehschritte gelöst")
    }

    @Test
    fun largerSketchSolvesAndDragsQuickly() {
        // Gitter aus 8x8 Punkten mit H/V-Linien, Abständen und Gleichheiten (~ 130 Variablen)
        val s = Sketch()
        val size = 8
        val grid = Array(size) { i -> Array(size) { j -> s.addPoint(i * 10.0 + (i * 7 + j * 3) % 3 * 0.4, j * 10.0 + (i * 5 + j) % 3 * 0.4) } }
        val hLines = ArrayList<LineId>(); val vLines = ArrayList<LineId>()
        for (i in 0 until size) for (j in 0 until size) {
            if (i + 1 < size) s.addLine(grid[i][j], grid[i + 1][j]).also { hLines += it; s.add(Horizontal(it)); s.add(LengthDim(it, 10.0)) }
            if (j + 1 < size) s.addLine(grid[i][j], grid[i][j + 1]).also { vLines += it; s.add(Vertical(it)); s.add(LengthDim(it, 10.0)) }
        }
        s.add(Coincident(s.origin, grid[0][0]))
        val t0 = System.nanoTime()
        val res = s.solve()
        val solveMs = (System.nanoTime() - t0) / 1e6
        assertTrue(res.isSolved, res.toString())
        println("Gitter 8x8: Lösen %.1f ms, %d Iterationen, DOF %d".format(solveMs, res.iterations, res.degreesOfFreedom))
        // Ziehen: Warmlauf, dann messen
        val drag = s.beginDrag()
        val p = grid[size - 1][size - 1]
        fun step(k: Int) = drag.dragTo(mapOf(p to Vec2(70.0 + 5 * sin(k / 5.0), 70.0 + 5 * cos(k / 5.0))))
        repeat(10) { step(it) }
        val t1 = System.nanoTime()
        repeat(60) { assertTrue(step(10 + it).isSolved) }
        val perStep = (System.nanoTime() - t1) / 1e6 / 60
        println("Gitter 8x8: Ziehschritt %.2f ms".format(perStep))
        assertTrue(perStep < 40, "Ziehschritt zu langsam: $perStep ms")
        assertTrue(solveMs < 2000, "Lösen zu langsam: $solveMs ms")
        drag.finish()
        assertTrue(hypot(s.point(grid[1][0]).x - s.point(grid[0][0]).x, s.point(grid[1][0]).y - s.point(grid[0][0]).y).let { abs(it - 10.0) < 1e-6 })
    }
}
