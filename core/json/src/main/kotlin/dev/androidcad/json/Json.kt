package dev.androidcad.json

public class JsonException(message: String) : RuntimeException(message)

/**
 * Minimaler JSON-Codec ohne Abhängigkeiten (RFC 8259). Werte sind einfache Kotlin-Typen:
 * `null`, `Boolean`, `Double` (alle Zahlen), `String`, `List<Any?>`, `Map<String, Any?>` (Reihenfolge bleibt erhalten).
 *
 * Gedacht für die versionierten Projektdateien: gut lesbar, diffbar und ohne zusätzliche Bibliothek.
 */
public object Json {
    private const val MAX_DEPTH = 200

    public fun parse(text: String): Any? = Parser(text).parseDocument()

    /** Schreibt [value] als JSON. [pretty] = eingerückt (2 Leerzeichen). Nicht darstellbar: NaN, ∞, fremde Typen. */
    public fun write(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, pretty, 0)
        return sb.toString()
    }

    // ---- Schreiben -------------------------------------------------------------------------------

    private fun writeValue(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        if (depth > MAX_DEPTH) throw JsonException("Zu tief verschachtelt")
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is String -> writeString(sb, v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> writeNumber(sb, v.toDouble())
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (k !is String) throw JsonException("Objektschlüssel müssen Strings sein: $k")
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, pretty, depth + 1)
                    writeString(sb, k)
                    sb.append(if (pretty) ": " else ":")
                    writeValue(sb, value, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                // Listen aus Zahlen kompakt in einer Zeile (Koordinaten)
                val inline = pretty && items.all { it is Number || it is Boolean || it == null }
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(if (inline) ", " else ",")
                    if (!inline) newline(sb, pretty, depth + 1)
                    writeValue(sb, item, pretty, depth + 1)
                }
                if (!inline) newline(sb, pretty, depth)
                sb.append(']')
            }
            else -> throw JsonException("Typ nicht darstellbar: ${v::class.simpleName}")
        }
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun writeNumber(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) throw JsonException("NaN/Unendlich ist in JSON nicht darstellbar")
        if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString()) // kürzeste verlustfreie Darstellung
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    // ---- Lesen -----------------------------------------------------------------------------------

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = value(0)
            skipWs()
            if (i < s.length) fail("Unerwartete Zeichen nach dem Wert")
            return v
        }

        private fun fail(msg: String): Nothing {
            var line = 1; var col = 1
            for (k in 0 until minOf(i, s.length)) if (s[k] == '\n') { line++; col = 1 } else col++
            throw JsonException("$msg (Zeile $line, Spalte $col)")
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Zu tief verschachtelt")
            if (i >= s.length) fail("Unerwartetes Ende")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Unerwartetes Zeichen '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Ungültiges Literal")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                if (peek() != '"') fail("Objektschlüssel (String) erwartet")
                val key = str()
                skipWs()
                if (peek() != ':') fail("':' erwartet")
                i++
                skipWs()
                out[key] = value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("',' oder '}' erwartet")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                skipWs()
                out += value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("',' oder ']' erwartet")
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("String nicht beendet")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Ungültige Escape-Sequenz")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Ungültige \\u-Sequenz")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("Ungültige \\u-Sequenz")
                                sb.append(code.toChar()); i += 4
                            }
                            else -> { i--; fail("Ungültige Escape-Sequenz '\\$e'") }
                        }
                    }
                    c < ' ' -> { i--; fail("Steuerzeichen im String") }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            if (peek() == '-') i++
            if (peek() == '0') i++
            else if (peek() in '1'..'9') while (peek() in '0'..'9') i++
            else fail("Ziffer erwartet")
            if (peek() == '.') {
                i++
                if (peek() !in '0'..'9') fail("Ziffer nach '.' erwartet")
                while (peek() in '0'..'9') i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek() !in '0'..'9') fail("Ziffer im Exponenten erwartet")
                while (peek() in '0'..'9') i++
            }
            return s.substring(start, i).toDouble()
        }
    }
}

// ---- Typisierter Zugriff auf geparste Objekte ---------------------------------------------------------

public typealias JsonObject = Map<String, Any?>

@Suppress("UNCHECKED_CAST")
public fun Any?.asObject(what: String = "Wert"): JsonObject = this as? JsonObject ?: throw JsonException("$what: Objekt erwartet")

public fun Any?.asList(what: String = "Wert"): List<Any?> = (this as? List<*>)?.let { it } ?: throw JsonException("$what: Liste erwartet")

public fun JsonObject.string(key: String): String = this[key] as? String ?: throw JsonException("Feld '$key': String erwartet")
public fun JsonObject.stringOrNull(key: String): String? = if (this[key] == null) null else string(key)

public fun JsonObject.double(key: String): Double = (this[key] as? Number)?.toDouble() ?: throw JsonException("Feld '$key': Zahl erwartet")
public fun JsonObject.doubleOrNull(key: String): Double? = if (this[key] == null) null else double(key)

public fun JsonObject.int(key: String): Int {
    val d = double(key)
    if (d != Math.floor(d) || Math.abs(d) > Int.MAX_VALUE) throw JsonException("Feld '$key': Ganzzahl erwartet")
    return d.toInt()
}

public fun JsonObject.bool(key: String, default: Boolean? = null): Boolean =
    (this[key] as? Boolean) ?: default ?: throw JsonException("Feld '$key': Boolean erwartet")

public fun JsonObject.list(key: String): List<Any?> = this[key].asList("Feld '$key'")
public fun JsonObject.obj(key: String): JsonObject = this[key].asObject("Feld '$key'")
