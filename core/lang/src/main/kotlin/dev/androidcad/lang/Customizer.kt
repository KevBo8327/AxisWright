package dev.androidcad.lang

/** Eine Auswahloption: [literal] ist der OpenSCAD-Quelltext des Werts (Strings in Anführungszeichen). */
public data class Choice(val literal: String, val label: String)

public sealed interface ParamKind {
    public data class Slider(val min: Double, val max: Double, val step: Double, val isInteger: Boolean) : ParamKind
    public data class Choices(val options: List<Choice>) : ParamKind
    public data object Toggle : ParamKind
    public data object NumberField : ParamKind
    public data object TextField : ParamKind
    public data class VectorField(val size: Int, val min: Double?, val max: Double?) : ParamKind
}

/**
 * Ein per OpenSCAD-Customizer-Konvention deklarierter Parameter.
 * [defaultLiteral] ist der Quelltext des Standardwerts, wie er in der Datei steht.
 */
public data class ParamSpec(
    val name: String,
    val group: String,
    val description: String,
    val defaultLiteral: String,
    val kind: ParamKind,
)

/**
 * Liest Customizer-Parameter aus dem Quelltext:
 * ```
 * /* [Gruppe] */
 * // Beschreibung
 * breite = 10; // [5:50]
 * ```
 * Es werden nur Zuweisungen vor der ersten `module`/`function`-Definition berücksichtigt.
 * Die Gruppe `Hidden` wird übersprungen.
 */
public object Customizer {
    private val groupRe = Regex("""^\s*/\*\s*\[(.*?)]\s*\*/\s*$""")
    private val assignRe = Regex("""^\s*([A-Za-z_]\w*)\s*=\s*(.+?)\s*;\s*(?://\s*(.*))?$""")
    private val defRe = Regex("""^\s*(module|function)\s""")

    public fun parse(source: String): List<ParamSpec> {
        val out = ArrayList<ParamSpec>()
        var group = ""
        var pendingDesc = ""
        var inBlockComment = false
        for (raw in source.lines()) {
            val line = raw.trimEnd()
            if (inBlockComment) { if (line.contains("*/")) inBlockComment = false; continue }
            groupRe.matchEntire(line)?.let { group = it.groupValues[1].trim(); pendingDesc = ""; continue }
            if (line.trimStart().startsWith("/*") && !line.contains("*/")) { inBlockComment = true; continue }
            if (defRe.containsMatchIn(line)) break
            val t = line.trim()
            if (t.startsWith("//")) { pendingDesc = t.removePrefix("//").trim(); continue }
            if (t.isEmpty()) { pendingDesc = ""; continue }
            val m = assignRe.matchEntire(line) ?: run { pendingDesc = ""; continue }
            val (name, rhs, tail) = m.destructured
            val desc = pendingDesc
            pendingDesc = ""
            if (group.equals("Hidden", ignoreCase = true) || name.startsWith("$")) continue
            val value = literalValue(rhs) ?: continue
            out += ParamSpec(name, group, desc, rhs, kindOf(value, tail.trim()))
        }
        return out
    }

    private fun literalValue(rhs: String): Value? = try {
        Evaluator(emptyMap(), null) { false }.eval(Parser(Lexer(rhs).tokenize()).parseSingleExpr(), Scope(null))
            .takeIf { it !is UndefV }
    } catch (_: CadLangException) {
        null
    }

    private fun kindOf(value: Value, hint: String): ParamKind {
        val inner = hint.takeIf { it.startsWith("[") && it.contains("]") }?.substring(1, hint.indexOf(']'))?.trim()
        return when (value) {
            is BoolV -> ParamKind.Toggle
            is NumV -> numberKind(value, inner)
            is StrV -> inner?.let { choices(it, quoteValues = true) } ?: ParamKind.TextField
            is VecV -> {
                val nums = value.numVec()
                if (nums == null) ParamKind.TextField else {
                    val r = inner?.let(::range)
                    ParamKind.VectorField(nums.size, r?.first, r?.second)
                }
            }
            else -> ParamKind.TextField
        }
    }

    private fun numberKind(value: NumV, inner: String?): ParamKind {
        if (inner == null) return ParamKind.NumberField
        range3(inner)?.let { (min, rawStep, max) ->
            // [min:max] ohne Schrittweite, aber Dezimal-Standardwert → feinere Schrittweite (1/100 des Bereichs).
            val step = if (inner.count { it == ':' } == 1 && value.v != Math.floor(value.v)) (max - min) / 100 else rawStep
            val isInt = listOf(min, step, max, value.v).all { it == Math.floor(it) }
            return ParamKind.Slider(min, max, step, isInt)
        }
        return choices(inner, quoteValues = false) ?: ParamKind.NumberField
    }

    private fun range(s: String): Pair<Double, Double>? = range3(s)?.let { it.first to it.third }

    /** `min:max` oder `min:step:max` → (min, step, max). */
    private fun range3(s: String): Triple<Double, Double, Double>? {
        val parts = s.split(':').map { it.trim().toDoubleOrNull() }
        if (parts.any { it == null }) return null
        val p = parts.filterNotNull()
        return when (p.size) {
            2 -> Triple(p[0], if (p[0] == Math.floor(p[0]) && p[1] == Math.floor(p[1])) 1.0 else (p[1] - p[0]) / 100, p[1])
            3 -> Triple(p[0], p[1], p[2])
            else -> null
        }
    }

    private fun choices(s: String, quoteValues: Boolean): ParamKind.Choices? {
        val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val options = parts.map { p ->
            val idx = p.indexOf(':')
            val value = if (idx >= 0) p.substring(0, idx).trim() else p
            val label = if (idx >= 0) p.substring(idx + 1).trim() else value
            Choice(if (quoteValues) "\"${value.removeSurrounding("\"")}\"" else value, label)
        }
        return ParamKind.Choices(options)
    }
}
