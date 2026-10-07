package dev.androidcad.sketch

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

public enum class SolveStatus {
    /** Alle Constraints sind erfüllt. */
    SOLVED,

    /** Die Constraints widersprechen sich; [SolveResult.conflicting] nennt die Verdächtigen. */
    CONFLICT,

    /** Numerischer Fehler (NaN o. Ä.). */
    FAILED,
}

public data class SolveOptions(
    val maxIterations: Int = 100,
    /** Größtes zulässiges Residuum (mm bzw. Radiant). */
    val tolerance: Double = 1e-9,
    /** Bei Widerspruch den besten Zwischenstand übernehmen statt die Skizze unverändert zu lassen. */
    val applyOnFailure: Boolean = false,
    /** Freiheitsgrade, überflüssige Constraints und bestimmte Elemente berechnen. */
    val analyze: Boolean = true,
)

public data class SolveResult(
    val status: SolveStatus,
    val iterations: Int,
    val maxResidual: Double,
    /** Verbleibende Freiheitsgrade (inkl. Verschiebung/Drehung); −1, wenn nicht berechnet. */
    val degreesOfFreedom: Int = -1,
    /** Bei [SolveStatus.CONFLICT]: Constraints mit nicht erfülltem Residuum. */
    val conflicting: List<ConstraintId> = emptyList(),
    /** Constraints, die nichts Neues sagen (linear abhängig von früheren, aber erfüllt). */
    val redundant: List<ConstraintId> = emptyList(),
    val constrainedPoints: Set<PointId> = emptySet(),
    val constrainedLines: Set<LineId> = emptySet(),
    val constrainedCircles: Set<CircleId> = emptySet(),
    val constrainedArcs: Set<ArcId> = emptySet(),
) {
    public val isSolved: Boolean get() = status == SolveStatus.SOLVED
    public val isFullyConstrained: Boolean get() = isSolved && degreesOfFreedom == 0
}

internal class LmResult(val converged: Boolean, val iterations: Int)

/** Numerische Jacobi-Matrix eines Gleichungsblocks (zentrale Differenzen), `count × vars.size`, zeilenweise. */
internal fun jacobianBlock(e: Eq, x: DoubleArray, out: DoubleArray, plus: DoubleArray, minus: DoubleArray) {
    val k = e.vars.size
    for (vi in 0 until k) {
        val idx = e.vars[vi]
        val old = x[idx]
        val h = 1e-6 * max(1.0, abs(old))
        x[idx] = old + h; e.fn.eval(x, plus, 0)
        x[idx] = old - h; e.fn.eval(x, minus, 0)
        x[idx] = old
        for (row in 0 until e.count) out[row * k + vi] = (plus[row] - minus[row]) / (2 * h)
    }
}

/**
 * Levenberg-Marquardt auf `min ½‖F(x)‖²`. Bei unterbestimmten Systemen liefert die Dämpfung die kleinste
 * Änderung gegenüber dem Startwert – die Skizze „bleibt, wie gezeichnet“, soweit es die Constraints erlauben.
 *
 * [constraintScale] gewichtet die Constraint-Gleichungen gegenüber den [extra]-Gleichungen (weiche Ziehziele).
 */
internal class Lm(private val sys: CompiledSystem, private val extra: List<Eq> = emptyList(), private val constraintScale: Double = 1.0) {
    private val eqs: List<Eq> = sys.eqs + extra
    private val scales = DoubleArray(eqs.size) { if (it < sys.eqs.size) constraintScale else 1.0 }
    private val n = sys.map.n
    private val m = eqs.sumOf { it.count }
    private val maxCount = eqs.maxOfOrNull { it.count } ?: 1
    private val maxVars = eqs.maxOfOrNull { it.vars.size } ?: 1

    private fun residual(x: DoubleArray, r: DoubleArray) {
        var off = 0
        for ((i, e) in eqs.withIndex()) {
            e.fn.eval(x, r, off)
            val s = scales[i]
            if (s != 1.0) for (j in 0 until e.count) r[off + j] *= s
            off += e.count
        }
    }

    private fun accumulate(x: DoubleArray, r: DoubleArray, a: DoubleArray, g: DoubleArray) {
        java.util.Arrays.fill(a, 0.0)
        java.util.Arrays.fill(g, 0.0)
        val jac = DoubleArray(maxCount * maxVars)
        val plus = DoubleArray(maxCount)
        val minus = DoubleArray(maxCount)
        var off = 0
        for ((ei, e) in eqs.withIndex()) {
            val k = e.vars.size
            jacobianBlock(e, x, jac, plus, minus)
            val s = scales[ei]
            for (row in 0 until e.count) {
                val res = r[off + row]
                for (i in 0 until k) {
                    val ji = s * jac[row * k + i]
                    g[e.vars[i]] += ji * res
                    for (j in 0 until k) a[e.vars[i] * n + e.vars[j]] += ji * s * jac[row * k + j]
                }
            }
            off += e.count
        }
    }

    /**
     * Verbessert [x] in place. [tolerance] ≥ 0: Abbruch, sobald alle (unskalierten) Constraint-Residuen darunter liegen;
     * negativ: nur Kostenstillstand (für gewichtete Zwischenlösungen).
     */
    fun solve(x: DoubleArray, tolerance: Double, maxIter: Int): LmResult {
        if (n == 0 || m == 0) return LmResult(true, 0)
        val r = DoubleArray(m); val rNew = DoubleArray(m)
        val a = DoubleArray(n * n); val g = DoubleArray(n)
        val xNew = DoubleArray(n)
        residual(x, r)
        var cost = 0.5 * dot(r, r)
        if (cost.isNaN()) return LmResult(false, 0)
        var lambda = 1e-3
        var iter = 0
        while (iter < maxIter) {
            if (tolerance >= 0 && constraintMax(x) < tolerance) return LmResult(true, iter)
            accumulate(x, r, a, g)
            if (g.all { abs(it) < 1e-15 }) break
            var accepted = false
            for (attempt in 0 until 30) {
                val aa = a.copyOf()
                for (i in 0 until n) aa[i * n + i] += lambda
                val delta = choleskySolve(aa, n, DoubleArray(n) { -g[it] })
                if (delta == null) { lambda *= 10; continue }
                for (i in 0 until n) xNew[i] = x[i] + delta[i]
                residual(xNew, rNew)
                val newCost = 0.5 * dot(rNew, rNew)
                if (!newCost.isNaN() && newCost < cost) {
                    val relGain = (cost - newCost) / max(cost, 1e-300)
                    System.arraycopy(xNew, 0, x, 0, n)
                    System.arraycopy(rNew, 0, r, 0, m)
                    cost = newCost
                    lambda = max(lambda / 5, 1e-15)
                    accepted = true
                    if (tolerance < 0 && relGain < 1e-13) return LmResult(true, iter + 1)
                    break
                }
                lambda *= 10
            }
            iter++
            if (!accepted) break
        }
        val ok = if (tolerance >= 0) constraintMax(x) < tolerance else true
        return LmResult(ok, iter)
    }

    /** Größtes unskaliertes Residuum der Constraint-Gleichungen. */
    fun constraintMax(x: DoubleArray): Double {
        var worst = 0.0
        val tmp = DoubleArray(maxCount)
        for (e in sys.eqs) {
            e.fn.eval(x, tmp, 0)
            for (j in 0 until e.count) {
                val v = abs(tmp[j])
                if (v.isNaN()) return Double.POSITIVE_INFINITY
                if (v > worst) worst = v
            }
        }
        return worst
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

internal class Analysis(
    val rank: Int,
    val dof: Int,
    val determined: BooleanArray,
    val redundantOwners: List<Int>,
)

/** Freiheitsgrad-Analyse am Lösungspunkt [x]: Rang der Jacobi-Matrix, bestimmte Variablen, überflüssige Constraints. */
internal fun analyze(sys: CompiledSystem, x: DoubleArray): Analysis {
    val n = sys.map.n
    val m = sys.m
    if (n == 0) return Analysis(0, 0, BooleanArray(0), emptyList())
    // dichte Jacobi-Matrix (m × n)
    val jac = DoubleArray(m * n)
    val maxCount = sys.eqs.maxOfOrNull { it.count } ?: 1
    val maxVars = sys.eqs.maxOfOrNull { it.vars.size } ?: 1
    val block = DoubleArray(maxCount * maxVars); val plus = DoubleArray(maxCount); val minus = DoubleArray(maxCount)
    var off = 0
    val rowOwner = IntArray(m)
    for (e in sys.eqs) {
        jacobianBlock(e, x, block, plus, minus)
        for (row in 0 until e.count) {
            for (vi in e.vars.indices) jac[(off + row) * n + e.vars[vi]] = block[row * e.vars.size + vi]
            rowOwner[off + row] = e.owner
        }
        off += e.count
    }

    // JᵀJ → Eigenzerlegung → Rang und Nullraum
    val jtj = DoubleArray(n * n)
    for (row in 0 until m) {
        for (i in 0 until n) {
            val ji = jac[row * n + i]
            if (ji == 0.0) continue
            for (j in 0 until n) jtj[i * n + j] += ji * jac[row * n + j]
        }
    }
    val eig = jacobiEigen(jtj, n)
    val maxEig = eig.values.maxOrNull() ?: 0.0
    val nullTol = 1e-9 * max(1.0, maxEig)
    val rank = eig.values.count { it > nullTol }
    val determined = BooleanArray(n) { i ->
        var p = 0.0
        for (k in 0 until n) if (eig.values[k] <= nullTol) p += eig.vec(i, k) * eig.vec(i, k)
        p < 1e-6
    }

    // überflüssige Constraints: Zeilen der Reihe nach gegen eine orthonormale Basis prüfen.
    // Ein Constraint gilt nur dann als überflüssig, wenn **alle** seine Zeilen von früheren abhängen
    // (z. B. ist „fixiere Punkt“ nicht überflüssig, wenn nur seine y-Koordinate schon bestimmt war).
    val basis = ArrayList<DoubleArray>()
    val rowsTotal = LinkedHashMap<Int, Int>()
    val rowsDependent = HashMap<Int, Int>()
    for (row in 0 until m) {
        val v = DoubleArray(n) { jac[row * n + it] }
        val norm0 = sqrt(v.sumOf { it * it })
        val owner = rowOwner[row]
        rowsTotal.merge(owner, 1, Int::plus)
        if (norm0 < 1e-12) continue
        for (b in basis) {
            var d = 0.0
            for (i in 0 until n) d += v[i] * b[i]
            for (i in 0 until n) v[i] -= d * b[i]
        }
        val norm = sqrt(v.sumOf { it * it })
        if (norm < 1e-7 * norm0) {
            rowsDependent.merge(owner, 1, Int::plus)
        } else {
            for (i in 0 until n) v[i] /= norm
            basis += v
        }
    }
    val redundant = rowsTotal.filter { (owner, total) -> owner > 0 && rowsDependent[owner] == total }.keys.toList()
    return Analysis(rank, n - rank, determined, redundant)
}

internal object SketchSolver {
    fun solve(sketch: Sketch, options: SolveOptions): SolveResult {
        val sys = compile(sketch)
        val x = sys.map.initial(sketch)
        val lm = Lm(sys)
        val res = lm.solve(x, options.tolerance, options.maxIterations)
        val maxRes = lm.constraintMax(x)
        if (maxRes.isNaN() || maxRes.isInfinite() || x.any { it.isNaN() }) {
            return SolveResult(SolveStatus.FAILED, res.iterations, Double.POSITIVE_INFINITY)
        }
        if (!res.converged) {
            if (options.applyOnFailure) sys.map.apply(sketch, x)
            return SolveResult(SolveStatus.CONFLICT, res.iterations, maxRes, conflicting = conflicting(sys, x))
        }
        // „Horizontal + vertikal“ an einer Linie lässt sich nur lösen, indem sie zu einem Punkt wird – das ist ein Widerspruch.
        val degenerate = degenerate(sketch, sys.map, x)
        if (degenerate.isNotEmpty()) {
            val culprits = sketch.constraintEntries.filter { (_, c) -> c.refs.any { it in degenerate } }.map { it.id }
            return SolveResult(SolveStatus.CONFLICT, res.iterations, maxRes, conflicting = culprits)
        }
        sys.map.apply(sketch, x)
        return finish(sketch, sys, x, res.iterations, maxRes, options.analyze)
    }

    fun finish(sketch: Sketch, sys: CompiledSystem, x: DoubleArray, iterations: Int, maxRes: Double, analyze: Boolean): SolveResult {
        if (!analyze) return SolveResult(SolveStatus.SOLVED, iterations, maxRes)
        val a = analyze(sys, x)
        val map = sys.map
        fun pointDetermined(p: PointId) = a.determined[map.x(p)] && a.determined[map.y(p)]
        return SolveResult(
            status = SolveStatus.SOLVED,
            iterations = iterations,
            maxResidual = maxRes,
            degreesOfFreedom = a.dof,
            redundant = a.redundantOwners.map(::ConstraintId),
            constrainedPoints = sketch.pointIds.filter(::pointDetermined).toSet(),
            constrainedLines = sketch.lineIds.filter { pointDetermined(sketch.line(it).p1) && pointDetermined(sketch.line(it).p2) }.toSet(),
            constrainedCircles = sketch.circleIds.filter { pointDetermined(sketch.circle(it).center) && a.determined[map.rIndex.getValue(it.value)] }.toSet(),
            constrainedArcs = sketch.arcIds.filter { id -> sketch.arc(id).let { pointDetermined(it.center) && pointDetermined(it.start) && pointDetermined(it.end) } }.toSet(),
        )
    }

    /** Linien der Länge 0, Kreise/Bögen mit Radius 0 in der Lösung [x] (als IDs, damit sich Constraints zuordnen lassen). */
    fun degenerate(sketch: Sketch, map: VarMap, x: DoubleArray): Set<Any> {
        val eps = 1e-6
        val out = HashSet<Any>()
        for (id in sketch.lineIds) {
            val l = sketch.line(id)
            if (kotlin.math.hypot(x[map.x(l.p2)] - x[map.x(l.p1)], x[map.y(l.p2)] - x[map.y(l.p1)]) < eps) out += id
        }
        for (id in sketch.circleIds) if (x[map.rIndex.getValue(id.value)] < eps) out += id
        for (id in sketch.arcIds) {
            val a = sketch.arc(id)
            val r = kotlin.math.hypot(x[map.x(a.start)] - x[map.x(a.center)], x[map.y(a.start)] - x[map.y(a.center)])
            val chord = kotlin.math.hypot(x[map.x(a.end)] - x[map.x(a.start)], x[map.y(a.end)] - x[map.y(a.start)])
            if (r < eps || chord < eps) out += id
        }
        return out
    }

    /** Constraints, deren Residuum am Endpunkt nicht verschwindet. */
    private fun conflicting(sys: CompiledSystem, x: DoubleArray): List<ConstraintId> {
        val out = LinkedHashSet<Int>()
        val tmp = DoubleArray(sys.eqs.maxOfOrNull { it.count } ?: 1)
        for (e in sys.eqs) {
            if (e.owner <= 0) continue
            e.fn.eval(x, tmp, 0)
            if ((0 until e.count).any { abs(tmp[it]) > 1e-6 }) out += e.owner
        }
        return out.map(::ConstraintId)
    }
}

/**
 * Laufende Zieh-Interaktion: hält das kompilierte Gleichungssystem und den letzten gültigen Zustand, damit jede
 * Mausbewegung nur wenige Solver-Iterationen kostet. Die Skizze wird nach jedem [dragTo] aktualisiert.
 */
public class DragSession internal constructor(private val sketch: Sketch) {
    private val sys = compile(sketch)
    private val initial = sys.map.initial(sketch)
    private var x = initial.copyOf()
    private var lastGood = initial.copyOf()
    private var lastIterations = 0

    /**
     * Zieht die [targets] (Punkt → Wunschposition). Constraints haben Vorrang: Ist das Ziel nicht erreichbar, bleibt der Punkt
     * so nah wie möglich daran, und alle Constraints sind weiterhin erfüllt.
     */
    public fun dragTo(targets: Map<PointId, Vec2>): SolveResult {
        val soft = targets.map { (p, t) ->
            val ix = sys.map.x(p); val iy = sys.map.y(p)
            Eq(OWNER_DRAG, intArrayOf(ix, iy), 2) { v, o, k -> o[k] = v[ix] - t.x; o[k + 1] = v[iy] - t.y }
        }
        x = lastGood.copyOf()
        for ((p, t) in targets) { x[sys.map.x(p)] = t.x; x[sys.map.y(p)] = t.y }
        // Phase 1: Constraints stark gewichtet, Ziehziel weich → bester Kompromiss
        Lm(sys, soft, constraintScale = 100.0).solve(x, tolerance = -1.0, maxIter = 40)
        // Phase 2: Ziehziel loslassen und Constraints exakt erfüllen
        val polish = Lm(sys)
        val res = polish.solve(x, 1e-9, 60)
        lastIterations += res.iterations
        val maxRes = polish.constraintMax(x)
        if (!res.converged || x.any { it.isNaN() } || SketchSolver.degenerate(sketch, sys.map, x).isNotEmpty()) {
            x = lastGood.copyOf()
            return SolveResult(SolveStatus.CONFLICT, res.iterations, maxRes)
        }
        lastGood = x.copyOf()
        sys.map.apply(sketch, x)
        return SolveResult(SolveStatus.SOLVED, res.iterations, maxRes)
    }

    /** Beendet das Ziehen und liefert die vollständige Analyse (Freiheitsgrade usw.) für den Endzustand. */
    public fun finish(): SolveResult {
        sys.map.apply(sketch, lastGood)
        return SketchSolver.finish(sketch, sys, lastGood.copyOf(), lastIterations, 0.0, analyze = true)
    }

    /** Verwirft das Ziehen und stellt die Ausgangslage wieder her. */
    public fun cancel() {
        sys.map.apply(sketch, initial)
    }
}
