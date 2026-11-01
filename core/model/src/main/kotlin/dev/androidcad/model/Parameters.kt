package dev.androidcad.model

import dev.androidcad.text.Msg
import dev.androidcad.lang.CadLang
import dev.androidcad.lang.CadLangException
import dev.androidcad.sketch.ConstraintId
import dev.androidcad.sketch.Dimension

/** Ein Eintrag der Parameter-Tabelle: Name und Ausdruck; der Ausdruck darf Parameter verwenden, die davor stehen. */
data class Parameter(val name: String, val expression: String) {
    companion object {
        // alle Buchstaben (auch ä, ö, ü, ß): der Ausdrucks-Leser in :core:lang erkennt sie ebenfalls
        private val NAME = Regex("""[\p{L}_][\p{L}0-9_]*""")

        /** Warum [name] kein Parametername sein kann (`null` = gültig). */
        fun nameProblem(name: String): Msg? = if (NAME.matches(name)) null else Msg(ModelText.PARAMETER_NAME_INVALID, name)
    }
}

/** Ein Zahlenwert im Modell, der aus einem Ausdruck der Parameter-Tabelle kommen kann. */
sealed interface ValueRef {
    /** Das Maß [constraint] in der Skizze [sketch]. */
    data class Dim(val sketch: FeatureId, val constraint: ConstraintId) : ValueRef

    /**
     * Der Hauptwert des Features [feature]: Länge einer Extrusion, Winkel einer Drehung, Wandstärke einer Schale, Größe einer
     * Verrundung/Fase. Vorzeichen (Richtung) bleiben erhalten, der Ausdruck gibt den Betrag.
     */
    data class FeatureValue(val feature: FeatureId) : ValueRef
}

/** Ergebnis von [Document.applyParameters]: die Werte der Parameter und Fehler je Parameter bzw. Ausdruck. */
class ParameterResult(val values: Map<String, Double>, val errors: List<Msg>)

/** Eine reine Zahl mit Dezimalkomma („2,5“) als Zahl lesen; in Formeln trennt das Komma Argumente (`max(a, b)`) und bleibt. */
internal fun decimalComma(expression: String): String =
    if (Regex("""\s*[+-]?\d+,\d+\s*""").matches(expression)) expression.replace(',', '.') else expression

/** Werte der Parameter der Reihe nach; ein fehlerhafter Parameter fehlt in [ParameterResult.values], spätere können weiterrechnen. */
internal fun evaluateParameters(parameters: List<Parameter>): ParameterResult {
    val values = LinkedHashMap<String, Double>()
    val errors = ArrayList<Msg>()
    for (p in parameters) {
        try {
            values[p.name] = CadLang.evalNumber(decimalComma(p.expression), values)
        } catch (e: CadLangException) {
            errors += Msg(ModelText.PARAMETER_ERROR, p.name, e.msg)
        }
    }
    return ParameterResult(values, errors)
}

/** Setzt den Wert [v] (Betrag) an [ref] im Dokument; liefert eine Fehlermeldung, wenn der Wert dort nicht passt. */
internal fun Document.applyValue(ref: ValueRef, v: Double): Msg? {
    fun signed(old: Double) = if (old < 0) -kotlin.math.abs(v) else kotlin.math.abs(v)
    when (ref) {
        is ValueRef.Dim -> {
            val sk = sketch(ref.sketch).sketch
            val c = sk.constraint(ref.constraint) as? Dimension ?: return null
            if (v <= 0 && c !is dev.androidcad.sketch.AngleDim) return Msg(ModelText.DIMENSION_NOT_POSITIVE)
            sk.setDimension(ref.constraint, signed(c.value))
        }
        is ValueRef.FeatureValue -> when (val f = feature(ref.feature)) {
            is ExtrudeFeature -> if (v > 0) update(f.copy(distance = signed(f.distance))) else return Msg(ModelText.LENGTH_NOT_POSITIVE)
            is RevolveFeature -> if (v > 0 && v <= 360) update(f.copy(angle = signed(f.angle))) else return Msg(ModelText.REVOLVE_ANGLE_RANGE)
            is ShellFeature -> if (v > 0) update(f.copy(thickness = v)) else return Msg(ModelText.THICKNESS_NOT_POSITIVE)
            is FilletFeature -> if (v > 0) update(f.copy(size = v)) else return Msg(ModelText.SIZE_NOT_POSITIVE)
            else -> return null
        }
    }
    return null
}
