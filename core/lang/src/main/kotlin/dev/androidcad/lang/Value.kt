package dev.androidcad.lang

import kotlin.math.abs
import kotlin.math.floor

internal sealed interface Value
internal data class NumV(val v: Double) : Value
internal data class StrV(val v: String) : Value
internal data class BoolV(val v: Boolean) : Value
internal data class VecV(val items: List<Value>) : Value
internal data class RangeV(val start: Double, val step: Double, val end: Double) : Value
internal data object UndefV : Value

internal fun Value.truthy(): Boolean = when (this) {
    is BoolV -> v
    is NumV -> v != 0.0
    is StrV -> v.isNotEmpty()
    is VecV -> items.isNotEmpty()
    is RangeV -> true
    UndefV -> false
}

internal fun Value.asNum(): Double? = (this as? NumV)?.v

internal fun Value.asVec(): List<Value>? = (this as? VecV)?.items

/** Vektor aus lauter Zahlen, sonst `null`. */
internal fun Value.numVec(): List<Double>? {
    val items = (this as? VecV)?.items ?: return null
    val out = ArrayList<Double>(items.size)
    for (it in items) out += (it as? NumV)?.v ?: return null
    return out
}

internal fun fmtNum(v: Double): String = when {
    v.isNaN() -> "nan"
    v.isInfinite() -> if (v > 0) "inf" else "-inf"
    v == floor(v) && abs(v) < 1e15 -> v.toLong().toString()
    else -> {
        val s = "%.5f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
        if (s == "-0" || s.isEmpty()) "0" else s
    }
}

/** Darstellung für `str()` / `echo()`. */
internal fun Value.render(quoteStrings: Boolean = false): String = when (this) {
    is NumV -> fmtNum(v)
    is BoolV -> v.toString()
    is StrV -> if (quoteStrings) "\"$v\"" else v
    is VecV -> items.joinToString(", ", "[", "]") { it.render(true) }
    is RangeV -> "[${fmtNum(start)}:${fmtNum(step)}:${fmtNum(end)}]"
    UndefV -> "undef"
}

internal fun valuesEqual(a: Value, b: Value): Boolean = when {
    a is NumV && b is NumV -> a.v == b.v
    a is VecV && b is VecV -> a.items.size == b.items.size && a.items.indices.all { valuesEqual(a.items[it], b.items[it]) }
    else -> a == b
}
