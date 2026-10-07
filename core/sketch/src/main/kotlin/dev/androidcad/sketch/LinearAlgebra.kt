package dev.androidcad.sketch

import kotlin.math.abs
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

/** Eigenwerte und Eigenvektoren (Spalten von [vectors], zeilenweise gespeichert) einer symmetrischen Matrix. */
internal class Eigen(val values: DoubleArray, val vectors: DoubleArray, val n: Int) {
    /** i-te Komponente des k-ten Eigenvektors. */
    fun vec(i: Int, k: Int): Double = vectors[i * n + k]
}

/** Zyklisches Jacobi-Verfahren; robust und für die kleinen Matrizen einer Skizze schnell genug. */
internal fun jacobiEigen(matrix: DoubleArray, n: Int, maxSweeps: Int = 60): Eigen {
    val a = matrix.copyOf()
    val v = DoubleArray(n * n)
    for (i in 0 until n) v[i * n + i] = 1.0
    for (sweep in 0 until maxSweeps) {
        var off = 0.0
        for (i in 0 until n) for (j in i + 1 until n) off += a[i * n + j] * a[i * n + j]
        if (off < 1e-26) break
        for (p in 0 until n - 1) {
            for (q in p + 1 until n) {
                val apq = a[p * n + q]
                if (abs(apq) < 1e-300) continue
                val theta = (a[q * n + q] - a[p * n + p]) / (2 * apq)
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until n) {
                    val akp = a[k * n + p]; val akq = a[k * n + q]
                    a[k * n + p] = c * akp - s * akq
                    a[k * n + q] = s * akp + c * akq
                }
                for (k in 0 until n) {
                    val apk = a[p * n + k]; val aqk = a[q * n + k]
                    a[p * n + k] = c * apk - s * aqk
                    a[q * n + k] = s * apk + c * aqk
                }
                for (k in 0 until n) {
                    val vkp = v[k * n + p]; val vkq = v[k * n + q]
                    v[k * n + p] = c * vkp - s * vkq
                    v[k * n + q] = s * vkp + c * vkq
                }
            }
        }
    }
    return Eigen(DoubleArray(n) { a[it * n + it] }, v, n)
}
