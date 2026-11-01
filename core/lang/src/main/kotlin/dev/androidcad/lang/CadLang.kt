package dev.androidcad.lang

import dev.androidcad.text.Msg
/** Einstiegspunkt des Sprachkerns: Quelltext → CSG-Baum. Reines Kotlin/JVM, keine Android-Abhängigkeit. */
public object CadLang {
    /**
     * Wertet [source] aus.
     *
     * @param overrides Customizer-Werte als OpenSCAD-Literale (`"breite" to "42"`); überschreiben
     *   Zuweisungen auf oberster Ebene.
     * @param files Quelle für `include`/`use`.
     * @param isCancelled wird regelmäßig geprüft; `true` bricht mit [CadLangException] ab.
     * @throws CadLangException bei Syntax- und Laufzeitfehlern.
     */
    public fun render(
        source: String,
        overrides: Map<String, String> = emptyMap(),
        files: SourceProvider? = null,
        isCancelled: () -> Boolean = { false },
    ): RenderResult {
        val helper = Evaluator(emptyMap(), null) { false }
        val values = overrides.mapValues { (name, lit) ->
            try {
                helper.eval(Parser(Lexer(lit).tokenize()).parseSingleExpr(), Scope(null))
            } catch (e: CadLangException) {
                throw CadLangException(Msg(LangText.INVALID_OVERRIDE, name, e.msg))
            }
        }
        // Parser und Auswertung sind rekursiv: Tiefe Verschachtelung (Rekursion unter der eigenen Grenze, tausende Klammern)
        // kann den Stack eines Hintergrund-Threads (1 MB unter Android) sprengen, bevor die Tiefengrenze greift.
        try {
            val program = Parser(Lexer(source).tokenize()).parseProgram()
            val ev = Evaluator(values, files, isCancelled)
            val root = ev.run(program)
            return RenderResult(root, ev.echoes, ev.warnings)
        } catch (_: StackOverflowError) {
            throw CadLangException(Msg(LangText.TOO_DEEP))
        }
    }

    /**
     * Wertet einen Zahlenausdruck aus, z. B. `breite / 2 + wand` (Syntax wie OpenSCAD: + − * / %, Klammern, sin, sqrt …).
     * [vars] sind die bekannten Namen.
     * @throws CadLangException bei Syntaxfehlern oder wenn kein endlicher Zahlenwert herauskommt (z. B. unbekannter Name)
     */
    public fun evalNumber(expression: String, vars: Map<String, Double> = emptyMap()): Double {
        val scope = Scope(null).also { s -> vars.forEach { (k, v) -> s.vars[k] = NumV(v) } }
        val v = Evaluator(emptyMap(), null) { false }.eval(Parser(Lexer(expression).tokenize()).parseSingleExpr(), scope)
        return (v as? NumV)?.v?.takeIf { it.isFinite() } ?: throw CadLangException(Msg(LangText.NOT_A_NUMBER, expression))
    }

    /** Customizer-Parameter der Datei (siehe [Customizer]). */
    public fun parameters(source: String): List<ParamSpec> = Customizer.parse(source)
}
