package dev.androidcad.lang

/** Fehler beim Parsen oder Auswerten von Quelltext. [line] ist 1-basiert (0 = unbekannt). */
public class CadLangException(message: String, public val line: Int = 0) :
    RuntimeException(if (line > 0) "Zeile $line: $message" else message)

internal enum class T {
    NUM, STR, ID, INCLUDE_PATH,
    LPAREN, RPAREN, LBRACK, RBRACK, LBRACE, RBRACE,
    COMMA, SEMI, COLON, QUESTION, DOT,
    ASSIGN, EQ, NE, LT, LE, GT, GE, AND, OR, NOT,
    PLUS, MINUS, STAR, SLASH, PERCENT, CARET,
    HASH, BANG_MOD, PERCENT_MOD,
    EOF,
}

internal data class Token(val type: T, val text: String, val line: Int, val number: Double = 0.0)

internal class Lexer(private val src: String) {
    private var i = 0
    private var line = 1
    private val out = ArrayList<Token>()

    fun tokenize(): List<Token> {
        while (true) {
            skipTrivia()
            if (i >= src.length) break
            val c = src[i]
            when {
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> number()
                c == '"' -> string()
                c.isLetter() || c == '_' || c == '$' -> identifier()
                else -> symbol(c)
            }
        }
        out += Token(T.EOF, "", line)
        return out
    }

    private fun skipTrivia() {
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                c == '/' && peek(1) == '/' -> while (i < src.length && src[i] != '\n') i++
                c == '/' && peek(1) == '*' -> {
                    i += 2
                    while (i < src.length && !(src[i] == '*' && peek(1) == '/')) {
                        if (src[i] == '\n') line++
                        i++
                    }
                    if (i >= src.length) throw CadLangException("Kommentar nicht geschlossen", line)
                    i += 2
                }
                else -> return
            }
        }
    }

    private fun peek(o: Int): Char = if (i + o < src.length) src[i + o] else '\u0000'

    private fun number() {
        val s = i
        while (i < src.length && src[i].isDigit()) i++
        if (i < src.length && src[i] == '.') { i++; while (i < src.length && src[i].isDigit()) i++ }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            val save = i
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            if (i < src.length && src[i].isDigit()) {
                while (i < src.length && src[i].isDigit()) i++
            } else i = save
        }
        val text = src.substring(s, i)
        out += Token(T.NUM, text, line, text.toDouble())
    }

    private fun string() {
        i++
        val sb = StringBuilder()
        while (i < src.length && src[i] != '"') {
            var c = src[i]
            if (c == '\n') line++
            if (c == '\\' && i + 1 < src.length) {
                i++
                c = when (val e = src[i]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> e }
            }
            sb.append(c)
            i++
        }
        if (i >= src.length) throw CadLangException("Zeichenkette nicht geschlossen", line)
        i++
        out += Token(T.STR, sb.toString(), line)
    }

    private fun identifier() {
        val s = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
        val text = src.substring(s, i)
        out += Token(T.ID, text, line)
        if (text == "include" || text == "use") includePath()
    }

    /** `include <pfad>` / `use <pfad>`: Pfad bis `>` als eigenes Token lesen. */
    private fun includePath() {
        var j = i
        while (j < src.length && src[j].isWhitespace() && src[j] != '\n') j++
        if (j < src.length && src[j] == '<') {
            val end = src.indexOf('>', j)
            if (end < 0) throw CadLangException("include/use: '>' fehlt", line)
            out += Token(T.INCLUDE_PATH, src.substring(j + 1, end).trim(), line)
            i = end + 1
        }
    }

    private fun symbol(c: Char) {
        val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
        val (type, len) = when {
            two == "==" -> T.EQ to 2
            two == "!=" -> T.NE to 2
            two == "<=" -> T.LE to 2
            two == ">=" -> T.GE to 2
            two == "&&" -> T.AND to 2
            two == "||" -> T.OR to 2
            else -> when (c) {
                '(' -> T.LPAREN; ')' -> T.RPAREN; '[' -> T.LBRACK; ']' -> T.RBRACK
                '{' -> T.LBRACE; '}' -> T.RBRACE; ',' -> T.COMMA; ';' -> T.SEMI
                ':' -> T.COLON; '?' -> T.QUESTION; '.' -> T.DOT; '=' -> T.ASSIGN
                '<' -> T.LT; '>' -> T.GT; '!' -> T.NOT; '+' -> T.PLUS; '-' -> T.MINUS
                '*' -> T.STAR; '/' -> T.SLASH; '%' -> T.PERCENT; '^' -> T.CARET; '#' -> T.HASH
                else -> throw CadLangException("Unerwartetes Zeichen '$c'", line)
            } to 1
        }
        out += Token(type, src.substring(i, i + len), line)
        i += len
    }
}
