package dev.androidcad.lang

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Liefert den Quelltext einer per `include <..>` / `use <..>` referenzierten Datei. */
public fun interface SourceProvider {
    public fun read(path: String): String?
}

public class RenderResult(
    public val root: Node,
    public val echoes: List<String>,
    public val warnings: List<String>,
)

internal class Scope(val parent: Scope?, val dyn: Scope? = null) {
    val vars = HashMap<String, Value>()
    val functions = HashMap<String, Pair<FunctionDef, Scope>>()
    val modules = HashMap<String, Pair<ModuleDef, Scope>>()

    fun lookup(name: String): Value? {
        vars[name]?.let { return it }
        if (name.startsWith("$")) dyn?.lookup(name)?.let { return it }
        return parent?.lookup(name)
    }

    fun function(name: String): Pair<FunctionDef, Scope>? = functions[name] ?: parent?.function(name)
    fun module(name: String): Pair<ModuleDef, Scope>? = modules[name] ?: parent?.module(name)
}

private class ChildCtx(val stmts: List<Stmt>, val scope: Scope)

internal class Evaluator(
    private val overrides: Map<String, Value>,
    private val files: SourceProvider?,
    private val isCancelled: () -> Boolean,
) {
    val echoes = ArrayList<String>()
    val warnings = ArrayList<String>()

    private val childStack = ArrayList<ChildCtx>()
    private var depth = 0
    private var includeDepth = 0
    private val maxDepth = 400
    private val maxRangeItems = 5_000_000

    fun run(program: List<Stmt>): Node {
        val global = Scope(null)
        global.vars["PI"] = NumV(PI)
        global.vars["\$fn"] = NumV(0.0)
        global.vars["\$fa"] = NumV(12.0)
        global.vars["\$fs"] = NumV(2.0)
        global.vars["\$t"] = NumV(0.0)
        val nodes = execBody(program, global, topLevel = true)
        return Group(nodes)
    }

    // ---- Statements ---------------------------------------------------------

    private fun hoist(stmts: List<Stmt>, scope: Scope) {
        for (s in stmts) when (s) {
            is ModuleDef -> scope.modules[s.name] = s to scope
            is FunctionDef -> scope.functions[s.name] = s to scope
            else -> Unit
        }
    }

    private fun execBody(stmts: List<Stmt>, scope: Scope, topLevel: Boolean = false): List<Node> {
        hoist(stmts, scope)
        val out = ArrayList<Node>()
        for (s in stmts) out += exec(s, scope, topLevel)
        return out
    }

    private fun exec(s: Stmt, scope: Scope, topLevel: Boolean = false): List<Node> {
        if (isCancelled()) throw CadLangException("Abgebrochen")
        return when (s) {
            EmptyStmt, is ModuleDef, is FunctionDef -> emptyList()
            is AssignStmt -> {
                val v = if (topLevel && s.name in overrides) overrides.getValue(s.name) else eval(s.value, scope)
                scope.vars[s.name] = v
                emptyList()
            }
            is BlockStmt -> execBody(s.body, Scope(scope))
            is IfStmt -> when {
                eval(s.cond, scope).truthy() -> exec(s.then, scope)
                s.otherwise != null -> exec(s.otherwise, scope)
                else -> emptyList()
            }
            is LetStmt -> exec(s.body, bindLet(s.assigns, scope))
            is ForStmt -> {
                val acc = ArrayList<Node>()
                forEachBinding(s.assigns, 0, scope) { sc -> acc += exec(s.body, sc) }
                if (acc.isEmpty()) emptyList() else listOf(Group(acc))
            }
            is IncludeStmt -> include(s, scope)
            is ModuleCall -> call(s, scope)
        }
    }

    private fun bindLet(assigns: List<Assignment>, scope: Scope): Scope {
        val sc = Scope(scope)
        for (a in assigns) sc.vars[a.name] = eval(a.value, sc)
        return sc
    }

    private fun forEachBinding(assigns: List<Assignment>, idx: Int, scope: Scope, body: (Scope) -> Unit) {
        if (idx == assigns.size) { body(scope); return }
        val a = assigns[idx]
        for (v in iterate(eval(a.value, scope))) {
            val sc = Scope(scope)
            sc.vars[a.name] = v
            forEachBinding(assigns, idx + 1, sc, body)
        }
    }

    private fun iterate(v: Value): Sequence<Value> = when (v) {
        is VecV -> v.items.asSequence()
        is RangeV -> rangeSequence(v)
        UndefV -> emptySequence()
        else -> sequenceOf(v)
    }

    private fun rangeSequence(r: RangeV): Sequence<Value> {
        if (r.step == 0.0 || r.start.isNaN() || r.end.isNaN()) return emptySequence()
        if ((r.step > 0 && r.start > r.end) || (r.step < 0 && r.start < r.end)) return emptySequence()
        val n = Math.floor((r.end - r.start) / r.step + 1e-9).toLong() + 1
        if (n > maxRangeItems) throw CadLangException("Bereich hat zu viele Elemente ($n)")
        return (0 until n).asSequence().map { NumV(r.start + it * r.step) }
    }

    private fun include(s: IncludeStmt, scope: Scope): List<Node> {
        val provider = files ?: run { warn("include/use <${s.path}> ignoriert (keine Dateiquelle)"); return emptyList() }
        val text = provider.read(s.path) ?: throw CadLangException("Datei '${s.path}' nicht gefunden", s.line)
        if (includeDepth > 16) throw CadLangException("include/use zu tief verschachtelt", s.line)
        val prog = Parser(Lexer(text).tokenize()).parseProgram()
        includeDepth++
        try {
            if (s.isUse) {
                // use: nur Definitionen übernehmen, in eigenem Scope ausgewertet (globale Variablen der Bibliothek).
                val libScope = Scope(null).also { sc -> sc.vars.putAll(scope.root().vars.filterKeys { it.startsWith("$") || it == "PI" }) }
                for (st in prog) if (st is AssignStmt) libScope.vars[st.name] = eval(st.value, libScope)
                hoist(prog, libScope)
                scope.modules.putAll(libScope.modules)
                scope.functions.putAll(libScope.functions)
                return emptyList()
            }
            return execBody(prog, scope)
        } finally {
            includeDepth--
        }
    }

    private fun Scope.root(): Scope = parent?.root() ?: this

    private fun warn(msg: String) { warnings += msg }

    // ---- Modulaufrufe -------------------------------------------------------

    private fun evalChildren(stmts: List<Stmt>, scope: Scope): List<Node> {
        val out = ArrayList<Node>()
        hoist(stmts, scope)
        for (c in stmts) out += exec(c, scope)
        return out
    }

    private fun call(c: ModuleCall, scope: Scope): List<Node> {
        if (c.modifier == '*') return emptyList()
        // '#', '%' und '!' beeinflussen aktuell nur die Darstellung; die Geometrie bleibt unverändert.
        if (c.modifier == '%') return emptyList()

        val user = scope.module(c.name)
        if (user != null) return callUserModule(user.first, user.second, c, scope)

        val pos = ArrayList<Value>()
        val named = LinkedHashMap<String, Value>()
        for (a in c.args) if (a.name == null) pos += eval(a.value, scope) else named[a.name] = eval(a.value, scope)
        val args = Args(pos, named)
        // `$fn`, `$fa`, `$fs` … als Aufrufargument gelten für den Aufruf und seine Kinder (dynamischer Scope).
        val sc = if (named.keys.any { it.startsWith("$") }) {
            Scope(scope).also { s -> named.forEach { (k, v) -> if (k.startsWith("$")) s.vars[k] = v } }
        } else scope
        val kids = { evalChildren(c.children, Scope(sc)) }
        val scope = sc

        return when (c.name) {
            "children" -> childrenBuiltin(args)
            "echo" -> { echo(c.args, scope); emptyList() }
            "assert" -> { if (!(pos.firstOrNull()?.truthy() ?: false)) throw CadLangException("assert fehlgeschlagen: ${named["message"]?.render() ?: pos.getOrNull(1)?.render() ?: ""}", c.line); emptyList() }
            "cube" -> listOf(cube(args))
            "sphere" -> listOf(sphere(args, scope))
            "cylinder" -> listOf(cylinder(args, scope))
            "polyhedron" -> listOf(polyhedron(args, c.line))
            "circle" -> listOf(circle(args, scope))
            "square" -> listOf(square(args))
            "polygon" -> listOf(polygon(args, c.line))
            "translate" -> listOf(Transform(translateMat(args), kids()))
            "rotate" -> listOf(Transform(rotateMat(args), kids()))
            "scale" -> listOf(Transform(scaleMat(args), kids()))
            "mirror" -> listOf(Transform(mirrorMat(args), kids()))
            "multmatrix" -> listOf(Transform(multMat(args, c.line), kids()))
            "color" -> listOf(Colored(colorOf(args), kids()))
            "union" -> listOf(Boolean3D(BoolOp.UNION, kids()))
            "difference" -> listOf(Boolean3D(BoolOp.DIFFERENCE, kids()))
            "intersection" -> listOf(Boolean3D(BoolOp.INTERSECTION, kids()))
            "hull" -> listOf(Hull(kids()))
            "minkowski" -> listOf(Minkowski(kids()))
            "linear_extrude" -> listOf(linearExtrude(args, scope, kids()))
            "rotate_extrude" -> listOf(RotateExtrude(args.num("angle", 0, 360.0), fragments(10.0, scope), kids()))
            "offset" -> listOf(offset(args, scope, kids()))
            "render", "group" -> listOf(Group(kids()))
            else -> {
                warn("Unbekanntes oder nicht unterstütztes Modul '${c.name}' (Zeile ${c.line}) – Kinder werden unverändert übernommen")
                kids()
            }
        }
    }

    private fun callUserModule(def: ModuleDef, defScope: Scope, c: ModuleCall, caller: Scope): List<Node> {
        if (++depth > maxDepth) { depth = 0; throw CadLangException("Rekursionstiefe überschritten in Modul '${def.name}'", c.line) }
        try {
            val sc = Scope(defScope, dyn = caller)
            bindParams(def.params, c.args, caller, sc)
            sc.vars["\$children"] = NumV(c.children.size.toDouble())
            childStack += ChildCtx(c.children, caller)
            try {
                val nodes = exec(def.body, sc)
                return if (nodes.size <= 1) nodes else listOf(Group(nodes))
            } finally {
                childStack.removeAt(childStack.lastIndex)
            }
        } finally {
            depth--
        }
    }

    private fun childrenBuiltin(args: Args): List<Node> {
        val ctx = childStack.lastOrNull() ?: return emptyList()
        // Während der Auswertung der Kinder ist der Kontext des Aufrufers aktiv.
        val saved = ArrayList(childStack)
        childStack.removeAt(childStack.lastIndex)
        try {
            val sel = args.get("index", 0)
            val stmts = when {
                sel == null -> ctx.stmts
                sel is NumV -> listOfNotNull(ctx.stmts.getOrNull(sel.v.toInt()))
                sel is VecV -> sel.items.mapNotNull { (it as? NumV)?.let { n -> ctx.stmts.getOrNull(n.v.toInt()) } }
                else -> ctx.stmts
            }
            val out = ArrayList<Node>()
            for (s in stmts) out += exec(s, Scope(ctx.scope))
            return out
        } finally {
            childStack.clear()
            childStack.addAll(saved)
        }
    }

    private fun echo(args: List<Arg>, scope: Scope) {
        val text = args.joinToString(", ") { a ->
            val v = eval(a.value, scope)
            if (a.name != null) "${a.name} = ${v.render(true)}" else v.render(true)
        }
        echoes += "ECHO: $text"
    }

    private fun bindParams(params: List<Param>, args: List<Arg>, evalScope: Scope, target: Scope) {
        val positional = ArrayList<Value>()
        val namedArgs = HashMap<String, Value>()
        for (a in args) {
            val v = eval(a.value, evalScope)
            if (a.name == null) positional += v else namedArgs[a.name] = v
        }
        // Spezialvariablen (`$fn = 48`) werden auch ohne passenden Parameter im Aufruf-Scope gesetzt.
        for ((k, v) in namedArgs) if (k.startsWith("$")) target.vars[k] = v
        var pi = 0
        for (p in params) {
            val v = namedArgs[p.name] ?: if (pi < positional.size) positional[pi++] else null
            target.vars[p.name] = v ?: p.default?.let { eval(it, target) } ?: UndefV
        }
    }

    // ---- Ausdrücke ----------------------------------------------------------

    fun eval(e: Expr, scope: Scope): Value = when (e) {
        is NumLit -> NumV(e.v)
        is StrLit -> StrV(e.v)
        is BoolLit -> BoolV(e.v)
        UndefLit -> UndefV
        is Ident -> scope.lookup(e.name) ?: run {
            if (!e.name.startsWith("$")) warn("Variable '${e.name}' ist nicht definiert (Zeile ${e.line})")
            UndefV
        }
        is RangeExpr -> {
            val s = num(eval(e.start, scope)); val en = num(eval(e.end, scope))
            val st = if (e.step == null) 1.0 else num(eval(e.step, scope))
            if (s == null || en == null || st == null) UndefV else RangeV(s, st, en)
        }
        is ListExpr -> {
            val out = ArrayList<Value>()
            for (el in e.elems) listElem(el, scope, out)
            VecV(out)
        }
        is Unary -> unary(e, scope)
        is Binary -> binary(e, scope)
        is Ternary -> if (eval(e.c, scope).truthy()) eval(e.a, scope) else eval(e.b, scope)
        is Index -> index(eval(e.e, scope), eval(e.idx, scope))
        is Member -> member(eval(e.e, scope), e.name)
        is LetExpr -> eval(e.body, bindLet(e.assigns, scope))
        is Call -> callFunction(e, scope)
    }

    private fun num(v: Value): Double? = (v as? NumV)?.v

    private fun listElem(el: ListElem, scope: Scope, out: MutableList<Value>) {
        when (el) {
            is SingleElem -> out += eval(el.e, scope)
            is EachElem -> {
                val inner = ArrayList<Value>()
                listElem(el.e, scope, inner)
                for (v in inner) out.addAll(iterate(v).toList().let { if (v is VecV || v is RangeV) it else listOf(v) })
            }
            is ForElem -> forEachBinding(el.assigns, 0, scope) { sc -> listElem(el.body, sc, out) }
            is IfElem -> when {
                eval(el.cond, scope).truthy() -> listElem(el.then, scope, out)
                el.otherwise != null -> listElem(el.otherwise, scope, out)
            }
            is LetElem -> listElem(el.body, bindLet(el.assigns, scope), out)
        }
    }

    private fun unary(e: Unary, scope: Scope): Value {
        val v = eval(e.e, scope)
        return when (e.op) {
            T.NOT -> BoolV(!v.truthy())
            T.PLUS -> v
            T.MINUS -> when (v) {
                is NumV -> NumV(-v.v)
                is VecV -> VecV(v.items.map { unaryMinus(it) })
                else -> UndefV
            }
            else -> UndefV
        }
    }

    private fun unaryMinus(v: Value): Value = when (v) {
        is NumV -> NumV(-v.v)
        is VecV -> VecV(v.items.map { unaryMinus(it) })
        else -> UndefV
    }

    private fun binary(e: Binary, scope: Scope): Value {
        // Kurzschlussauswertung
        if (e.op == T.AND) return BoolV(eval(e.l, scope).truthy() && eval(e.r, scope).truthy())
        if (e.op == T.OR) return BoolV(eval(e.l, scope).truthy() || eval(e.r, scope).truthy())
        val a = eval(e.l, scope)
        val b = eval(e.r, scope)
        return when (e.op) {
            T.EQ -> BoolV(valuesEqual(a, b))
            T.NE -> BoolV(!valuesEqual(a, b))
            T.LT, T.LE, T.GT, T.GE -> compare(e.op, a, b)
            T.PLUS -> elementwise(a, b) { x, y -> x + y }
            T.MINUS -> elementwise(a, b) { x, y -> x - y }
            T.STAR -> multiply(a, b)
            T.SLASH -> divide(a, b)
            T.PERCENT -> if (a is NumV && b is NumV) NumV(a.v % b.v) else UndefV
            T.CARET -> if (a is NumV && b is NumV) NumV(Math.pow(a.v, b.v)) else UndefV
            else -> UndefV
        }
    }

    private fun compare(op: T, a: Value, b: Value): Value {
        val c: Int = when {
            a is NumV && b is NumV -> a.v.compareTo(b.v).let { if (a.v.isNaN() || b.v.isNaN()) return BoolV(false) else it }
            a is StrV && b is StrV -> a.v.compareTo(b.v)
            else -> return BoolV(false)
        }
        return BoolV(when (op) { T.LT -> c < 0; T.LE -> c <= 0; T.GT -> c > 0; else -> c >= 0 })
    }

    private fun elementwise(a: Value, b: Value, f: (Double, Double) -> Double): Value = when {
        a is NumV && b is NumV -> NumV(f(a.v, b.v))
        a is VecV && b is VecV && a.items.size == b.items.size -> VecV(a.items.indices.map { elementwise(a.items[it], b.items[it], f) })
        else -> UndefV
    }

    private fun multiply(a: Value, b: Value): Value = when {
        a is NumV && b is NumV -> NumV(a.v * b.v)
        a is NumV && b is VecV -> VecV(b.items.map { multiply(a, it) })
        a is VecV && b is NumV -> VecV(a.items.map { multiply(it, b) })
        a is VecV && b is VecV -> matMul(a, b)
        else -> UndefV
    }

    private fun matMul(a: VecV, b: VecV): Value {
        val av = a.numVec(); val bv = b.numVec()
        if (av != null && bv != null) return if (av.size == bv.size) NumV(av.indices.sumOf { av[it] * bv[it] }) else UndefV
        val aRows = a.items.map { it.numVec() }
        if (aRows.any { it == null }) return UndefV
        val rows = aRows.filterNotNull()
        if (bv != null) { // Matrix * Vektor
            if (rows.any { it.size != bv.size }) return UndefV
            return VecV(rows.map { r -> NumV(r.indices.sumOf { r[it] * bv[it] }) })
        }
        val bRows = b.items.map { it.numVec() }
        if (bRows.any { it == null }) return UndefV
        val bm = bRows.filterNotNull()
        if (rows.any { it.size != bm.size }) return UndefV
        val cols = bm.firstOrNull()?.size ?: return UndefV
        return VecV(rows.map { r -> VecV((0 until cols).map { j -> NumV(r.indices.sumOf { k -> r[k] * bm[k][j] }) }) })
    }

    private fun divide(a: Value, b: Value): Value = when {
        a is NumV && b is NumV -> NumV(a.v / b.v)
        a is VecV && b is NumV -> VecV(a.items.map { divide(it, b) })
        else -> UndefV
    }

    private fun index(v: Value, i: Value): Value {
        val n = (i as? NumV)?.v ?: return UndefV
        val k = Math.floor(n).toInt()
        return when (v) {
            is VecV -> v.items.getOrNull(k) ?: UndefV
            is StrV -> v.v.getOrNull(k)?.let { StrV(it.toString()) } ?: UndefV
            else -> UndefV
        }
    }

    private fun member(v: Value, name: String): Value {
        val k = when (name) { "x" -> 0; "y" -> 1; "z" -> 2; else -> return UndefV }
        return (v as? VecV)?.items?.getOrNull(k) ?: UndefV
    }

    // ---- Funktionen ---------------------------------------------------------

    private fun callFunction(c: Call, scope: Scope): Value {
        scope.function(c.name)?.let { (def, defScope) ->
            if (++depth > maxDepth) { depth = 0; throw CadLangException("Rekursionstiefe überschritten in Funktion '${def.name}'", c.line) }
            try {
                val sc = Scope(defScope, dyn = scope)
                bindParams(def.params, c.args, scope, sc)
                return eval(def.body, sc)
            } finally {
                depth--
            }
        }
        val args = c.args.map { it.name to eval(it.value, scope) }
        return Builtins.call(c.name, args.map { it.second }, args.mapNotNull { a -> a.first?.let { it to a.second } }.toMap(), c.line, ::warn)
    }

    // ---- Primitive ----------------------------------------------------------

    private class Args(val pos: List<Value>, val named: Map<String, Value>) {
        fun get(name: String, index: Int): Value? = named[name] ?: pos.getOrNull(index)
        fun num(name: String, index: Int, default: Double): Double = (get(name, index) as? NumV)?.v ?: default
        fun bool(name: String, index: Int, default: Boolean): Boolean = get(name, index)?.truthy() ?: default
    }

    private fun fragments(r: Double, scope: Scope): Int {
        val fn = (scope.lookup("\$fn") as? NumV)?.v ?: 0.0
        val fa = (scope.lookup("\$fa") as? NumV)?.v ?: 12.0
        val fs = (scope.lookup("\$fs") as? NumV)?.v ?: 2.0
        if (r < 1e-9) return 3
        if (fn > 0.0) return max(fn.toInt(), 3)
        return ceil(max(min(360.0 / fa, r * 2 * PI / fs), 5.0)).toInt()
    }

    private fun radius(args: Args, rName: String, dName: String, rIdx: Int, default: Double): Double {
        (args.named[dName] as? NumV)?.let { return it.v / 2 }
        return (args.get(rName, rIdx) as? NumV)?.v ?: default
    }

    private fun cube(a: Args): Node {
        val s = a.get("size", 0)
        val size = when (s) {
            is NumV -> Triple(s.v, s.v, s.v)
            is VecV -> s.numVec()?.takeIf { it.size >= 3 }?.let { Triple(it[0], it[1], it[2]) }
            else -> null
        } ?: Triple(1.0, 1.0, 1.0)
        return Cube(size, a.bool("center", 1, false))
    }

    private fun sphere(a: Args, scope: Scope): Node {
        val r = radius(a, "r", "d", 0, 1.0)
        return Sphere(r, fragments(r, scope))
    }

    private fun cylinder(a: Args, scope: Scope): Node {
        val h = a.num("h", 0, 1.0)
        val dd = (a.named["d"] as? NumV)?.v?.div(2)
        val r = dd ?: (a.named["r"] as? NumV)?.v
        val r1 = (a.named["d1"] as? NumV)?.v?.div(2) ?: (a.named["r1"] as? NumV)?.v ?: (a.pos.getOrNull(1) as? NumV)?.v ?: r ?: 1.0
        val r2 = (a.named["d2"] as? NumV)?.v?.div(2) ?: (a.named["r2"] as? NumV)?.v ?: (a.pos.getOrNull(2) as? NumV)?.v ?: r ?: 1.0
        return Cylinder(h, r1, r2, a.bool("center", 3, false), fragments(max(r1, r2), scope))
    }

    private fun polyhedron(a: Args, line: Int): Node {
        val pts = (a.get("points", 0) as? VecV)?.items?.map { p -> p.numVec()?.takeIf { it.size >= 3 }?.let { Triple(it[0], it[1], it[2]) } }
            ?: throw CadLangException("polyhedron: 'points' fehlt", line)
        if (pts.any { it == null }) throw CadLangException("polyhedron: ungültiger Punkt", line)
        val facesV = (a.named["faces"] ?: a.named["triangles"] ?: a.pos.getOrNull(1)) as? VecV
            ?: throw CadLangException("polyhedron: 'faces' fehlt", line)
        val faces = facesV.items.map { f -> f.numVec()?.map { it.toInt() } ?: throw CadLangException("polyhedron: ungültige Fläche", line) }
        for (f in faces) for (i in f) if (i < 0 || i >= pts.size) throw CadLangException("polyhedron: Index $i außerhalb von 0..${pts.size - 1}", line)
        return Polyhedron(pts.filterNotNull(), faces)
    }

    private fun circle(a: Args, scope: Scope): Node {
        val r = radius(a, "r", "d", 0, 1.0)
        return Circle(r, fragments(r, scope))
    }

    private fun square(a: Args): Node {
        val s = a.get("size", 0)
        val size = when (s) {
            is NumV -> s.v to s.v
            is VecV -> s.numVec()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] }
            else -> null
        } ?: (1.0 to 1.0)
        return Square(size, a.bool("center", 1, false))
    }

    private fun polygon(a: Args, line: Int): Node {
        val pts = (a.get("points", 0) as? VecV)?.items?.map { p -> p.numVec()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } }
            ?: throw CadLangException("polygon: 'points' fehlt", line)
        if (pts.any { it == null }) throw CadLangException("polygon: ungültiger Punkt", line)
        val paths = (a.get("paths", 1) as? VecV)?.items?.map { p -> p.numVec()?.map { it.toInt() } ?: throw CadLangException("polygon: ungültiger Pfad", line) }
        return Polygon2D(pts.filterNotNull(), paths)
    }

    private fun linearExtrude(a: Args, scope: Scope, kids: List<Node>): Node {
        val h = a.num("height", 0, 100.0)
        val twist = a.num("twist", 3, 0.0)
        val sc = a.named["scale"] ?: a.pos.getOrNull(5)
        val (sx, sy) = when (sc) {
            is NumV -> sc.v to sc.v
            is VecV -> sc.numVec()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: (1.0 to 1.0)
            else -> 1.0 to 1.0
        }
        val fa = (scope.lookup("\$fa") as? NumV)?.v ?: 12.0
        val slices = ((a.named["slices"] ?: a.pos.getOrNull(4)) as? NumV)?.v?.toInt()
            ?: if (twist == 0.0) 1 else max(1, ceil(Math.abs(twist) / fa).toInt())
        return LinearExtrude(h, a.bool("center", 1, false), twist, slices, sx, sy, kids)
    }

    private fun offset(a: Args, scope: Scope, kids: List<Node>): Node {
        val delta = (a.named["delta"] as? NumV)?.v
        val r = (a.named["r"] as? NumV)?.v ?: (a.pos.firstOrNull() as? NumV)?.v
        val amount = delta ?: r ?: 0.0
        val round = delta == null
        return Offset(amount, round, a.bool("chamfer", 2, false), fragments(Math.abs(amount), scope), kids)
    }

    // ---- Transformationen ---------------------------------------------------

    private fun vec3(v: Value?, default: Double): Triple<Double, Double, Double> {
        v?.numVec()?.let {
            return Triple(it.getOrElse(0) { default }, it.getOrElse(1) { default }, it.getOrElse(2) { default })
        }
        return Triple(default, default, default)
    }

    private fun translateMat(a: Args): Mat4 {
        val (x, y, z) = vec3(a.get("v", 0), 0.0)
        return Mat4.translation(x, y, z)
    }

    private fun scaleMat(a: Args): Mat4 {
        val v = a.get("v", 0)
        if (v is NumV) return Mat4.scaling(v.v, v.v, v.v)
        val (x, y, z) = vec3(v, 1.0)
        return Mat4.scaling(x, y, z)
    }

    private fun mirrorMat(a: Args): Mat4 {
        val (x, y, z) = vec3(a.get("v", 0), 0.0)
        return Mat4.mirror(x, y, z)
    }

    private fun rotateMat(a: Args): Mat4 {
        val ang = a.get("a", 0)
        val axis = a.get("v", 1)?.numVec()
        if (ang is NumV) {
            if (axis != null && axis.size >= 3) return Mat4.axisAngleDegrees(ang.v, axis[0], axis[1], axis[2])
            return Mat4.rotZ(ang.v)
        }
        val (x, y, z) = vec3(ang, 0.0)
        return Mat4.eulerDegrees(x, y, z)
    }

    private fun multMat(a: Args, line: Int): Mat4 {
        val rows = (a.get("m", 0) as? VecV)?.items?.map { it.numVec() }
        if (rows == null || rows.size < 3 || rows.any { it == null || it.size < 3 }) throw CadLangException("multmatrix: 4x4- oder 3x4-Matrix erwartet", line)
        val m = DoubleArray(16)
        m[15] = 1.0
        for (i in 0..2) for (j in 0..3) m[i * 4 + j] = rows[i]!!.getOrElse(j) { 0.0 }
        return Mat4(m)
    }

    private fun colorOf(a: Args): List<Double> {
        val c = a.get("c", 0)
        val alpha = a.get("alpha", 1)?.asNum()
        c?.numVec()?.let { v -> return listOf(v.getOrElse(0) { 0.0 }, v.getOrElse(1) { 0.0 }, v.getOrElse(2) { 0.0 }, v.getOrNull(3) ?: alpha ?: 1.0) }
        if (c is StrV) return NamedColors.parse(c.v, alpha ?: 1.0) ?: listOf(0.8, 0.8, 0.8, alpha ?: 1.0)
        return listOf(0.8, 0.8, 0.8, alpha ?: 1.0)
    }
}
