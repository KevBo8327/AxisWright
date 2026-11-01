package dev.androidcad.lang

import dev.androidcad.text.Msg
internal class Parser(private val toks: List<Token>) {
    private var p = 0

    private val cur get() = toks[p]
    private fun at(t: T) = cur.type == t
    private fun atId(name: String) = cur.type == T.ID && cur.text == name
    private fun next(): Token = toks[p++]
    private fun accept(t: T): Boolean = if (at(t)) { p++; true } else false

    /** [what]: das erwartete Zeichen (Text) oder ein [LangText] wie [LangText.MODULE_NAME]. */
    private fun expect(t: T, what: Any): Token {
        if (!at(t)) fail(Msg(LangText.EXPECTED_FOUND, what, found()))
        return next()
    }

    private fun fail(msg: Msg): Nothing = throw CadLangException(msg, cur.line)

    private fun found(): Any = cur.text.ifEmpty { LangText.END_OF_FILE }

    fun parseProgram(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (!at(T.EOF)) out += statement()
        return out
    }

    fun parseSingleExpr(): Expr {
        val e = expr()
        if (!at(T.EOF)) fail(Msg(LangText.TRAILING_CHARS))
        return e
    }

    // ---- Statements -------------------------------------------------------

    private fun statement(): Stmt {
        val t = cur
        when (t.type) {
            T.SEMI -> { next(); return EmptyStmt }
            T.LBRACE -> return block()
            T.INCLUDE_PATH -> fail(Msg(LangText.UNEXPECTED_PATH))
            else -> Unit
        }
        if (t.type == T.ID) {
            when (t.text) {
                "module" -> return moduleDef()
                "function" -> return functionDef()
                "include", "use" -> {
                    next()
                    val path = expect(T.INCLUDE_PATH, LangText.PATH)
                    return IncludeStmt(path.text, t.text == "use", t.line)
                }
                "for" -> {
                    next()
                    val a = assignmentList()
                    return ForStmt(a, statement())
                }
                "if" -> return ifStmt()
                "let" -> {
                    next()
                    val a = assignmentList()
                    return LetStmt(a, statement())
                }
            }
            if (toks[p + 1].type == T.ASSIGN) {
                next(); next()
                val v = expr()
                expect(T.SEMI, ";")
                return AssignStmt(t.text, v, t.line)
            }
        }
        return moduleCall()
    }

    private fun block(): Stmt {
        expect(T.LBRACE, "{")
        val body = ArrayList<Stmt>()
        while (!at(T.RBRACE)) {
            if (at(T.EOF)) fail(Msg(LangText.MISSING_BRACE))
            body += statement()
        }
        next()
        return BlockStmt(body)
    }

    private fun ifStmt(): Stmt {
        next()
        expect(T.LPAREN, "(")
        val c = expr()
        expect(T.RPAREN, ")")
        val then = statement()
        val other = if (atId("else")) { next(); statement() } else null
        return IfStmt(c, then, other)
    }

    private fun moduleDef(): Stmt {
        next()
        val name = expect(T.ID, LangText.MODULE_NAME).text
        val params = paramList()
        return ModuleDef(name, params, statement())
    }

    private fun functionDef(): Stmt {
        next()
        val name = expect(T.ID, LangText.FUNCTION_NAME).text
        val params = paramList()
        expect(T.ASSIGN, "=")
        val body = expr()
        expect(T.SEMI, ";")
        return FunctionDef(name, params, body)
    }

    private fun paramList(): List<Param> {
        expect(T.LPAREN, "(")
        val out = ArrayList<Param>()
        while (!at(T.RPAREN)) {
            val n = expect(T.ID, LangText.PARAMETER_NAME).text
            out += Param(n, if (accept(T.ASSIGN)) expr() else null)
            if (!accept(T.COMMA)) break
        }
        expect(T.RPAREN, ")")
        return out
    }

    private fun assignmentList(): List<Assignment> {
        expect(T.LPAREN, "(")
        val out = ArrayList<Assignment>()
        while (!at(T.RPAREN)) {
            val n = expect(T.ID, LangText.VARIABLE_NAME).text
            expect(T.ASSIGN, "=")
            out += Assignment(n, expr())
            if (!accept(T.COMMA)) break
        }
        expect(T.RPAREN, ")")
        return out
    }

    private fun moduleCall(): Stmt {
        var modifier: Char? = null
        while (true) {
            modifier = when {
                accept(T.HASH) -> '#'
                accept(T.PERCENT) -> '%'
                accept(T.NOT) -> '!'
                accept(T.STAR) -> '*'
                else -> break
            }
        }
        val name = expect(T.ID, LangText.MODULE_NAME).text
        val line = toks[p - 1].line
        val args = argList()
        val children = ArrayList<Stmt>()
        if (!accept(T.SEMI)) {
            when (val c = statement()) {
                is BlockStmt -> children += c.body
                else -> children += c
            }
        }
        return ModuleCall(name, args, children, modifier, line)
    }

    private fun argList(): List<Arg> {
        expect(T.LPAREN, "(")
        val out = ArrayList<Arg>()
        while (!at(T.RPAREN)) {
            if (at(T.ID) && toks[p + 1].type == T.ASSIGN) {
                val n = next().text
                next()
                out += Arg(n, expr())
            } else out += Arg(null, expr())
            if (!accept(T.COMMA)) break
        }
        expect(T.RPAREN, ")")
        return out
    }

    // ---- Ausdrücke (Priorität aufsteigend) ---------------------------------

    private fun expr(): Expr {
        val c = or()
        if (accept(T.QUESTION)) {
            val a = expr()
            expect(T.COLON, ":")
            return Ternary(c, a, expr())
        }
        return c
    }

    private fun binaryLevel(operand: () -> Expr, vararg ops: T): Expr {
        var l = operand()
        while (cur.type in ops) {
            val op = next()
            l = Binary(op.type, l, operand(), op.line)
        }
        return l
    }

    private fun or(): Expr = binaryLevel(::and, T.OR)
    private fun and(): Expr = binaryLevel(::equality, T.AND)
    private fun equality(): Expr = binaryLevel(::comparison, T.EQ, T.NE)
    private fun comparison(): Expr = binaryLevel(::additive, T.LT, T.LE, T.GT, T.GE)
    private fun additive(): Expr = binaryLevel(::multiplicative, T.PLUS, T.MINUS)
    private fun multiplicative(): Expr = binaryLevel(::unary, T.STAR, T.SLASH, T.PERCENT)

    private fun unary(): Expr {
        if (at(T.NOT) || at(T.MINUS) || at(T.PLUS)) {
            val op = next().type
            return Unary(op, unary())
        }
        return power()
    }

    private fun power(): Expr {
        val base = postfix()
        if (at(T.CARET)) {
            val op = next()
            return Binary(T.CARET, base, unary(), op.line) // rechtsassoziativ
        }
        return base
    }

    private fun postfix(): Expr {
        var e = primary()
        while (true) {
            e = when {
                at(T.LBRACK) -> {
                    next()
                    val i = expr()
                    expect(T.RBRACK, "]")
                    Index(e, i)
                }
                at(T.DOT) -> { next(); Member(e, expect(T.ID, LangText.NAME).text) }
                at(T.LPAREN) && e is Ident -> Call(e.name, argList(), e.line)
                else -> return e
            }
        }
    }

    private fun primary(): Expr {
        val t = cur
        return when (t.type) {
            T.NUM -> { next(); NumLit(t.number) }
            T.STR -> { next(); StrLit(t.text) }
            T.LPAREN -> {
                next()
                val e = expr()
                expect(T.RPAREN, ")")
                e
            }
            T.LBRACK -> listOrRange()
            T.ID -> {
                next()
                when (t.text) {
                    "true" -> BoolLit(true)
                    "false" -> BoolLit(false)
                    "undef" -> UndefLit
                    "let" -> {
                        val a = assignmentList()
                        LetExpr(a, expr())
                    }
                    else -> Ident(t.text, t.line)
                }
            }
            else -> fail(Msg(LangText.EXPRESSION_EXPECTED, found()))
        }
    }

    private fun listOrRange(): Expr {
        expect(T.LBRACK, "[")
        val elems = ArrayList<ListElem>()
        if (at(T.RBRACK)) { next(); return ListExpr(elems) }

        // Bereich [a:b] / [a:s:b] – nur wenn nach dem ersten Ausdruck ':' folgt.
        if (!isListComprehensionStart()) {
            val first = expr()
            if (at(T.COLON)) {
                next()
                val second = expr()
                val range = if (accept(T.COLON)) RangeExpr(first, second, expr()) else RangeExpr(first, null, second)
                expect(T.RBRACK, "]")
                return range
            }
            elems += SingleElem(first)
            if (!accept(T.COMMA)) { expect(T.RBRACK, "]"); return ListExpr(elems) }
        }
        while (!at(T.RBRACK)) {
            elems += listElem()
            if (!accept(T.COMMA)) break
        }
        expect(T.RBRACK, "]")
        return ListExpr(elems)
    }

    private fun isListComprehensionStart() =
        cur.type == T.ID && cur.text in setOf("for", "if", "each", "let")

    private fun listElem(): ListElem {
        if (cur.type == T.ID) {
            when (cur.text) {
                "for" -> { next(); val a = assignmentList(); return ForElem(a, listElem()) }
                "if" -> {
                    next()
                    expect(T.LPAREN, "(")
                    val c = expr()
                    expect(T.RPAREN, ")")
                    val then = listElem()
                    val other = if (atId("else")) { next(); listElem() } else null
                    return IfElem(c, then, other)
                }
                "each" -> { next(); return EachElem(listElem()) }
                "let" -> { next(); val a = assignmentList(); return LetElem(a, listElem()) }
            }
        }
        return SingleElem(expr())
    }
}
