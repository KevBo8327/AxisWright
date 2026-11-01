package dev.androidcad.lang

import dev.androidcad.text.Msg
import kotlin.math.abs
import kotlin.math.sqrt

internal object Builtins {
    private fun d(v: Value?): Double? = (v as? NumV)?.v

    private fun math1(args: List<Value>, f: (Double) -> Double): Value =
        d(args.firstOrNull())?.let { NumV(f(it)) } ?: UndefV

    fun call(name: String, args: List<Value>, named: Map<String, Value>, line: Int, warn: (Msg) -> Unit): Value = when (name) {
        "sin" -> math1(args) { Math.sin(Math.toRadians(it)) }
        "cos" -> math1(args) { Math.cos(Math.toRadians(it)) }
        "tan" -> math1(args) { Math.tan(Math.toRadians(it)) }
        "asin" -> math1(args) { Math.toDegrees(Math.asin(it)) }
        "acos" -> math1(args) { Math.toDegrees(Math.acos(it)) }
        "atan" -> math1(args) { Math.toDegrees(Math.atan(it)) }
        "atan2" -> if (args.size >= 2 && d(args[0]) != null && d(args[1]) != null) NumV(Math.toDegrees(Math.atan2(d(args[0])!!, d(args[1])!!))) else UndefV
        "sqrt" -> math1(args) { sqrt(it) }
        "abs" -> math1(args) { abs(it) }
        "sign" -> math1(args) { Math.signum(it) }
        "floor" -> math1(args) { Math.floor(it) }
        "ceil" -> math1(args) { Math.ceil(it) }
        "round" -> math1(args) { if (it < 0) -Math.floor(-it + 0.5) else Math.floor(it + 0.5) }
        "exp" -> math1(args) { Math.exp(it) }
        "ln" -> math1(args) { Math.log(it) }
        "log" -> math1(args) { Math.log10(it) }
        "pow" -> if (args.size >= 2 && d(args[0]) != null && d(args[1]) != null) NumV(Math.pow(d(args[0])!!, d(args[1])!!)) else UndefV
        "min" -> extremum(args, true)
        "max" -> extremum(args, false)
        "len" -> when (val a = args.firstOrNull()) { is VecV -> NumV(a.items.size.toDouble()); is StrV -> NumV(a.v.length.toDouble()); else -> UndefV }
        "concat" -> VecV(args.flatMap { if (it is VecV) it.items else listOf(it) })
        "str" -> StrV(args.joinToString("") { it.render() })
        "norm" -> args.firstOrNull()?.numVec()?.let { v -> NumV(sqrt(v.sumOf { it * it })) } ?: UndefV
        "cross" -> cross(args)
        "is_undef" -> BoolV(args.firstOrNull() == null || args.first() == UndefV)
        "is_num" -> BoolV(args.firstOrNull() is NumV)
        "is_string" -> BoolV(args.firstOrNull() is StrV)
        "is_bool" -> BoolV(args.firstOrNull() is BoolV)
        "is_list" -> BoolV(args.firstOrNull() is VecV)
        "chr" -> StrV(args.mapNotNull { d(it)?.toInt()?.let { c -> String(Character.toChars(c)) } }.joinToString(""))
        "ord" -> (args.firstOrNull() as? StrV)?.v?.firstOrNull()?.let { NumV(it.code.toDouble()) } ?: UndefV
        "lookup" -> lookup(args)
        else -> {
            warn(Msg(LangText.UNKNOWN_FUNCTION, name, line))
            UndefV
        }
    }

    private fun extremum(args: List<Value>, min: Boolean): Value {
        val nums = if (args.size == 1 && args[0] is VecV) args[0].numVec() else args.map { d(it) }.let { l -> if (l.any { it == null }) null else l.filterNotNull() }
        if (nums.isNullOrEmpty()) return UndefV
        return NumV(if (min) nums.min() else nums.max())
    }

    private fun cross(args: List<Value>): Value {
        val a = args.getOrNull(0)?.numVec(); val b = args.getOrNull(1)?.numVec()
        if (a == null || b == null || a.size != 3 || b.size != 3) return UndefV
        return VecV(listOf(
            NumV(a[1] * b[2] - a[2] * b[1]),
            NumV(a[2] * b[0] - a[0] * b[2]),
            NumV(a[0] * b[1] - a[1] * b[0]),
        ))
    }

    /** `lookup(key, [[k0,v0],[k1,v1],...])` mit linearer Interpolation. */
    private fun lookup(args: List<Value>): Value {
        val key = d(args.getOrNull(0)) ?: return UndefV
        val table = (args.getOrNull(1) as? VecV)?.items?.map { it.numVec() } ?: return UndefV
        if (table.isEmpty() || table.any { it == null || it.size < 2 }) return UndefV
        val t = table.filterNotNull().sortedBy { it[0] }
        if (key <= t.first()[0]) return NumV(t.first()[1])
        if (key >= t.last()[0]) return NumV(t.last()[1])
        for (i in 0 until t.size - 1) {
            val a = t[i]; val b = t[i + 1]
            if (key >= a[0] && key <= b[0]) {
                val f = if (b[0] == a[0]) 0.0 else (key - a[0]) / (b[0] - a[0])
                return NumV(a[1] + f * (b[1] - a[1]))
            }
        }
        return UndefV
    }
}

internal object NamedColors {
    private val table = mapOf(
        "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF, "yellow" to 0xFFFF00,
        "orange" to 0xFFA500, "black" to 0x000000, "white" to 0xFFFFFF, "gray" to 0x808080,
        "grey" to 0x808080, "purple" to 0x800080, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
        "pink" to 0xFFC0CB, "brown" to 0xA52A2A, "silver" to 0xC0C0C0, "gold" to 0xFFD700,
    )

    fun parse(s: String, alpha: Double): List<Double>? {
        val rgb = if (s.startsWith("#") && (s.length == 7 || s.length == 4)) {
            val hex = if (s.length == 4) s.drop(1).map { "$it$it" }.joinToString("") else s.drop(1)
            hex.toIntOrNull(16)
        } else table[s.lowercase()]
        rgb ?: return null
        return listOf(((rgb shr 16) and 255) / 255.0, ((rgb shr 8) and 255) / 255.0, (rgb and 255) / 255.0, alpha)
    }
}
