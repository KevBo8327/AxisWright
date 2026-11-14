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
     * Verrundung/Fase, Durchmesser/Tiefe einer Bohrung, Abstand/Winkel eines Musters sowie Abstand/Winkel einer Arbeitsebene.
     * Vorzeichen (Richtung) bleiben bei Extrusion/Drehung erhalten; [index] unterscheidet mehrere Werte eines Features
     * (0 = Hauptwert, 1 = zweite Länge einer Extrusion, zweiter Abstand eines Musters oder Tiefe einer Bohrung).
     */
    data class FeatureValue(val feature: FeatureId, val index: Int = 0) : ValueRef
}

/** Ergebnis von [Document.applyParameters]: die Werte der Parameter und Fehler je Parameter bzw. Ausdruck. */
class ParameterResult(val values: Map<String, Double>, val errors: List<Msg>)

/**
 * Wandelt Dezimalkommas („2,5“, „wand * 1,5“) in Dezimalpunkte um. Kommas in Funktionsaufrufen (`max(a, b)`) und Listen
 * (`[1, 2]`) sowie in Zeichenketten bleiben als Trennzeichen erhalten.
 */
fun decimalComma(expression: String): String {
    if (',' !in expression) return expression
    val out = StringBuilder(expression.length)
    val argStack = ArrayList<Boolean>()
    var inString = false
    var lastSig = '\u0000'
    for (i in expression.indices) {
        val c = expression[i]
        if (inString) {
            out.append(c)
            if (c == '"' && (i == 0 || expression[i - 1] != '\\')) inString = false
            continue
        }
        when {
            c == '"' -> { inString = true; out.append(c) }
            c == '(' -> {
                argStack.add(lastSig.isLetterOrDigit() || lastSig == '_' || lastSig == '$')
                out.append(c)
            }
            c == '[' -> { argStack.add(true); out.append(c) }
            c == ')' || c == ']' -> { if (argStack.isNotEmpty()) argStack.removeAt(argStack.lastIndex); out.append(c) }
            c == ',' && argStack.lastOrNull() != true &&
                i > 0 && expression[i - 1].isDigit() &&
                i + 1 < expression.length && expression[i + 1].isDigit() -> out.append('.')
            else -> out.append(c)
        }
        if (!c.isWhitespace()) lastSig = c
    }
    return out.toString()
}

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
            is ExtrudeFeature -> {
                if (ref.index == 1) {
                    if (v > 0) update(f.copy(distance2 = v)) else return Msg(ModelText.LENGTH_NOT_POSITIVE)
                } else {
                    if (v > 0) update(f.copy(distance = signed(f.distance))) else return Msg(ModelText.LENGTH_NOT_POSITIVE)
                }
            }
            is RevolveFeature -> if (v > 0 && v <= 360) update(f.copy(angle = signed(f.angle))) else return Msg(ModelText.REVOLVE_ANGLE_RANGE)
            is ShellFeature -> if (v > 0) update(f.copy(thickness = v)) else return Msg(ModelText.THICKNESS_NOT_POSITIVE)
            is FilletFeature -> if (v > 0) update(f.copy(size = v)) else return Msg(ModelText.SIZE_NOT_POSITIVE)
            is HoleFeature -> if (ref.index == 1) {
                if (v > 0) update(f.copy(depth = v)) else return Msg(ModelText.DEPTH_NOT_POSITIVE)
            } else {
                if (v > 0) update(f.copy(diameter = v)) else return Msg(ModelText.DIAMETER_NOT_POSITIVE)
            }
            is PatternFeature -> when (val k = f.kind) {
                is PatternKind.Linear -> if (ref.index == 1 && k.direction2 != null) {
                    if (v.isFinite() && v != 0.0) update(f.copy(kind = k.copy(spacing2 = v))) else return Msg(ModelText.SPACING2_ZERO)
                } else {
                    if (v.isFinite() && v != 0.0) update(f.copy(kind = k.copy(spacing = v))) else return Msg(ModelText.SPACING_ZERO)
                }
                is PatternKind.Circular -> if (v.isFinite() && v != 0.0 && kotlin.math.abs(v) <= 360.0) {
                    update(f.copy(kind = k.copy(angle = v)))
                } else return Msg(ModelText.ANGLE_RANGE_360)
                is PatternKind.Mirror -> return null
            }
            is PlaneFeature -> when (val d = f.def) {
                is PlaneDef.Offset -> if (v.isFinite()) update(f.copy(def = d.copy(distance = v))) else return Msg(ModelText.DISTANCE_INVALID)
                is PlaneDef.AtEdge -> if (v.isFinite() && kotlin.math.abs(v) < 180.0) update(f.copy(def = d.copy(angle = v))) else return Msg(ModelText.PLANE_ANGLE_RANGE)
            }
            else -> return null
        }
    }
    return null
}
