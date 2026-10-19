package dev.androidcad.model

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
        fun nameProblem(name: String): String? = if (NAME.matches(name)) null else "„$name“: nur Buchstaben, Ziffern und _, nicht mit einer Ziffer beginnend"
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
class ParameterResult(val values: Map<String, Double>, val errors: List<String>)

/** Werte der Parameter der Reihe nach; ein fehlerhafter Parameter fehlt in [ParameterResult.values], spätere können weiterrechnen. */
internal fun evaluateParameters(parameters: List<Parameter>): ParameterResult {
    val values = LinkedHashMap<String, Double>()
    val errors = ArrayList<String>()
    for (p in parameters) {
        try {
            values[p.name] = CadLang.evalNumber(p.expression, values)
        } catch (e: CadLangException) {
            errors += "Parameter ${p.name}: ${e.message}"
        }
    }
    return ParameterResult(values, errors)
}

/** Setzt den Wert [v] (Betrag) an [ref] im Dokument; liefert eine Fehlermeldung, wenn der Wert dort nicht passt. */
internal fun Document.applyValue(ref: ValueRef, v: Double): String? {
    fun signed(old: Double) = if (old < 0) -kotlin.math.abs(v) else kotlin.math.abs(v)
    when (ref) {
        is ValueRef.Dim -> {
            val sk = sketch(ref.sketch).sketch
            val c = sk.constraint(ref.constraint) as? Dimension ?: return null
            if (v <= 0 && c !is dev.androidcad.sketch.AngleDim) return "Maß muss größer als 0 sein"
            sk.setDimension(ref.constraint, signed(c.value))
        }
        is ValueRef.FeatureValue -> when (val f = feature(ref.feature)) {
            is ExtrudeFeature -> if (v > 0) update(f.copy(distance = signed(f.distance))) else return "Länge muss größer als 0 sein"
            is RevolveFeature -> if (v > 0 && v <= 360) update(f.copy(angle = signed(f.angle))) else return "Winkel muss zwischen 0 und 360° liegen"
            is ShellFeature -> if (v > 0) update(f.copy(thickness = v)) else return "Wandstärke muss größer als 0 sein"
            is FilletFeature -> if (v > 0) update(f.copy(size = v)) else return "Größe muss größer als 0 sein"
            else -> return null
        }
    }
    return null
}
