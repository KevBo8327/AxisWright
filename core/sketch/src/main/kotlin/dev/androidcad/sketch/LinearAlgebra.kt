package dev.androidcad.sketch

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Löst `A·x = b` für symmetrisch positiv definite [a] (n×n, zeilenweise) per Cholesky-Zerlegung.
 * Liefert `null`, wenn die Matrix nicht positiv definit ist. [a] wird nicht verändert.
 */
internal fun choleskySolve(a: DoubleArray, n: Int, b: DoubleArray): DoubleArray? {
    val l = DoubleArray(n * n)
    for (i in 0 until n) {
        for (j in 0..i) {
            var s = a[i * n + j]
            for (k in 0 until j) s -= l[i * n + k] * l[j * n + k]
            if (i == j) {
                if (s <= 1e-300) return null
                l[i * n + i] = sqrt(s)
            } else {
                l[i * n + j] = s / l[j * n + j]
            }
        }
    }
    val y = DoubleArray(n)
    for (i in 0 until n) {
        var s = b[i]
        for (k in 0 until i) s -= l[i * n + k] * y[k]
        y[i] = s / l[i * n + i]
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var s = y[i]
        for (k in i + 1 until n) s -= l[k * n + i] * x[k]
        x[i] = s / l[i * n + i]
    }
    return x
}

/** Ergebnis der Rang- und Nullraumbestimmung direkt auf J: Rang und je Variable, ob sie bestimmt ist. */
internal class QrNullspace(val rank: Int, val determined: BooleanArray)

/**
 * Bestimmt Rang und bestimmte Variablen direkt auf der m×n-Jacobi-Matrix [jac] per spaltenpivotierter
 * Householder-QR-Zerlegung und anschließender QR-Zerlegung auf R^T (Complete Orthogonal Decomposition).
 * Vermeidet die Quadrierung der Konditionszahl durch J^T J.
 */
internal fun qrNullspace(jac: DoubleArray, m: Int, n: Int): QrNullspace {
    if (n == 0) return QrNullspace(0, BooleanArray(0))
    if (m == 0) return QrNullspace(0, BooleanArray(n) { false })

    val a = jac.copyOf()
    val perm = IntArray(n) { it }
    val colNormSq = DoubleArray(n)
    var maxNormSq = 0.0
    for (j in 0 until n) {
        var s = 0.0
        for (row in 0 until m) {
            val v = a[row * n + j]
            s += v * v
        }
        colNormSq[j] = s
        if (s > maxNormSq) maxNormSq = s
    }

    val tol = 1e-7 * max(1.0, sqrt(maxNormSq))
    val maxRank = minOf(m, n)
    var rank = 0

    for (k in 0 until maxRank) {
        var bestCol = k
        var bestSq = colNormSq[k]
        for (j in k + 1 until n) {
            if (colNormSq[j] > bestSq) {
                bestSq = colNormSq[j]
                bestCol = j
            }
        }
        if (sqrt(bestSq) <= tol) break

        if (bestCol != k) {
            for (row in 0 until m) {
                val idxA = row * n + k
                val idxB = row * n + bestCol
                val tmp = a[idxA]
                a[idxA] = a[idxB]
                a[idxB] = tmp
            }
            val tp = perm[k]; perm[k] = perm[bestCol]; perm[bestCol] = tp
            colNormSq[bestCol] = colNormSq[k]
        }

        var normSq = 0.0
        for (row in k until m) {
            val v = a[row * n + k]
            normSq += v * v
        }
        val norm = sqrt(normSq)
        if (norm <= tol) break

        val akk = a[k * n + k]
        val alpha = if (akk >= 0.0) -norm else norm
        val v0 = akk - alpha
        a[k * n + k] = v0
        var vNormSq = v0 * v0
        for (row in k + 1 until m) {
            val v = a[row * n + k]
            vNormSq += v * v
        }
        if (vNormSq > 0.0) {
            val beta = 2.0 / vNormSq
            for (j in k + 1 until n) {
                var dot = 0.0
                for (row in k until m) dot += a[row * n + k] * a[row * n + j]
                val scale = beta * dot
                for (row in k until m) a[row * n + j] -= scale * a[row * n + k]
                var rem = 0.0
                for (row in k + 1 until m) {
                    val t = a[row * n + j]
                    rem += t * t
                }
                colNormSq[j] = rem
            }
        }
        a[k * n + k] = alpha
        rank++
    }

    if (rank == 0) return QrNullspace(0, BooleanArray(n) { false })
    if (rank == n) return QrNullspace(n, BooleanArray(n) { true })

    // R_1^T (n × rank) in ursprünglicher Variablenreihenfolge aufbauen und per Householder-QR faktorisieren;
    // die letzten (n - rank) Spalten von Q bilden eine Orthonormalbasis des Nullraums ker(J).
    val rt = DoubleArray(n * rank)
    for (i in 0 until rank) {
        for (j in i until n) {
            rt[perm[j] * rank + i] = a[i * n + j]
        }
    }
    val betas = DoubleArray(rank)
    for (k in 0 until rank) {
        var normSq = 0.0
        for (row in k until n) {
            val v = rt[row * rank + k]
            normSq += v * v
        }
        val norm = sqrt(normSq)
        if (norm == 0.0) continue
        val akk = rt[k * rank + k]
        val alpha = if (akk >= 0.0) -norm else norm
        val v0 = akk - alpha
        rt[k * rank + k] = v0
        var vNormSq = v0 * v0
        for (row in k + 1 until n) {
            val v = rt[row * rank + k]
            vNormSq += v * v
        }
        val beta = 2.0 / vNormSq
        betas[k] = beta
        for (j in k + 1 until rank) {
            var dot = 0.0
            for (row in k until n) dot += rt[row * rank + k] * rt[row * rank + j]
            val scale = beta * dot
            for (row in k until n) rt[row * rank + j] -= scale * rt[row * rank + k]
        }
    }

    val dof = n - rank
    val z = DoubleArray(n * dof)
    for (c in 0 until dof) z[(rank + c) * dof + c] = 1.0
    for (k in rank - 1 downTo 0) {
        val beta = betas[k]
        if (beta == 0.0) continue
        for (c in 0 until dof) {
            var dot = 0.0
            for (row in k until n) dot += rt[row * rank + k] * z[row * dof + c]
            val scale = beta * dot
            for (row in k until n) z[row * dof + c] -= scale * rt[row * rank + k]
        }
    }

    val determined = BooleanArray(n) { i ->
        var p = 0.0
        for (c in 0 until dof) {
            val v = z[i * dof + c]
            p += v * v
        }
        p < 1e-6
    }
    return QrNullspace(rank, determined)
}
