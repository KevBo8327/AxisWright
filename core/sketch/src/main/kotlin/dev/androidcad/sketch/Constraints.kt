package dev.androidcad.sketch

/** Zweidimensionaler Punkt/Vektor (mm). */
public data class Vec2(val x: Double, val y: Double)

public sealed interface RoundId

@JvmInline public value class PointId(public val value: Int)
@JvmInline public value class LineId(public val value: Int)
@JvmInline public value class CircleId(public val value: Int) : RoundId
@JvmInline public value class ArcId(public val value: Int) : RoundId
@JvmInline public value class ConstraintId(public val value: Int)

/**
 * Eine Bedingung an die Skizze. Constraints sind unveränderliche Werte; geändert wird über
 * [Sketch.replace] (z. B. mit [Dimension.withValue]). Alle Längen in mm, Winkel in Grad.
 */
public sealed interface Constraint {
    /** Referenzierte Elemente (für Löschen mit Abhängigkeiten). */
    public val refs: List<Any>
}

/** Bemaßung mit einem Zahlenwert. */
public sealed interface Dimension : Constraint {
    public val value: Double
    public fun withValue(v: Double): Dimension
}

// ---- geometrische Constraints --------------------------------------------------------------------

public data class Coincident(val a: PointId, val b: PointId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

/** Punkt liegt auf der (unendlich verlängerten) Linie. */
public data class PointOnLine(val point: PointId, val line: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(point, line)
}

/** Punkt liegt auf dem Kreis bzw. dem Vollkreis des Bogens. */
public data class PointOnRound(val point: PointId, val round: RoundId) : Constraint {
    override val refs: List<Any> get() = listOf(point, round)
}

public data class Midpoint(val point: PointId, val line: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(point, line)
}

public data class Horizontal(val line: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(line)
}

public data class Vertical(val line: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(line)
}

/** Zwei Punkte auf gleicher Höhe (y gleich). */
public data class HorizontalPoints(val a: PointId, val b: PointId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

/** Zwei Punkte untereinander (x gleich). */
public data class VerticalPoints(val a: PointId, val b: PointId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

public data class Parallel(val a: LineId, val b: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

public data class Perpendicular(val a: LineId, val b: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

/** Beide Linien liegen auf einer gemeinsamen Geraden. */
public data class Collinear(val a: LineId, val b: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

public data class EqualLength(val a: LineId, val b: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

public data class EqualRadius(val a: RoundId, val b: RoundId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

public data class Concentric(val a: RoundId, val b: RoundId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

/** Linie berührt Kreis/Bogen (Abstand Mittelpunkt–Gerade = Radius). */
public data class TangentLineRound(val line: LineId, val round: RoundId) : Constraint {
    override val refs: List<Any> get() = listOf(line, round)
}

/**
 * Kreis/Bogen berührt Kreis/Bogen. [internal] = Berührung von innen (|c₁c₂| = |r₁−r₂|), sonst von außen
 * (|c₁c₂| = r₁+r₂); [sign] legt bei „innen“ fest, welcher Radius der größere ist (+1: a ≥ b).
 */
public data class TangentRoundRound(val a: RoundId, val b: RoundId, val internal: Boolean, val sign: Int = 1) : Constraint {
    override val refs: List<Any> get() = listOf(a, b)
}

/** Punkte [a] und [b] liegen spiegelbildlich zur Achse [axis]. */
public data class Symmetric(val a: PointId, val b: PointId, val axis: LineId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b, axis)
}

/**
 * Verschiebung: `b − a = to − from`. Bindet eine Kopie eines Skizzenmusters an ihren Vorgänger; der Vektor ist die
 * Hilfslinie [from] → [to] des Musters (ihre Länge = Abstand, ihre Richtung = Musterrichtung).
 */
public data class Translated(val a: PointId, val b: PointId, val from: PointId, val to: PointId) : Constraint {
    override val refs: List<Any> get() = listOf(a, b, from, to)
}

/** Drehung: [b] ist [a], um den Punkt [center] um [degrees] Grad gedreht (gegen den Uhrzeigersinn) – Kopie eines Kreismusters. */
public data class Rotated(val a: PointId, val b: PointId, val center: PointId, val degrees: Double) : Constraint {
    override val refs: List<Any> get() = listOf(a, b, center)
}

/** Punkt fest an Koordinaten; `null` = diese Achse bleibt frei. */
public data class Fixed(val point: PointId, val x: Double?, val y: Double?) : Constraint {
    override val refs: List<Any> get() = listOf(point)
}

// ---- Bemaßungen ---------------------------------------------------------------------------------

public data class DistanceDim(val a: PointId, val b: PointId, override val value: Double) : Dimension {
    override val refs: List<Any> get() = listOf(a, b)
    override fun withValue(v: Double): Dimension = copy(value = v)
}

/** x(b) − x(a) = value (mit Vorzeichen). */
public data class HorizontalDistanceDim(val a: PointId, val b: PointId, override val value: Double) : Dimension {
    override val refs: List<Any> get() = listOf(a, b)
    override fun withValue(v: Double): Dimension = copy(value = v)
}

/** y(b) − y(a) = value (mit Vorzeichen). */
public data class VerticalDistanceDim(val a: PointId, val b: PointId, override val value: Double) : Dimension {
    override val refs: List<Any> get() = listOf(a, b)
    override fun withValue(v: Double): Dimension = copy(value = v)
}

public data class LengthDim(val line: LineId, override val value: Double) : Dimension {
    override val refs: List<Any> get() = listOf(line)
    override fun withValue(v: Double): Dimension = copy(value = v)
}

/** Abstand eines Punktes von einer Geraden; [side] (±1) hält die Seite fest, auf der der Punkt liegt. */
public data class PointLineDistanceDim(val point: PointId, val line: LineId, override val value: Double, val side: Int = 1) : Dimension {
    override val refs: List<Any> get() = listOf(point, line)
    override fun withValue(v: Double): Dimension = copy(value = v)
}

public data class RadiusDim(val round: RoundId, override val value: Double) : Dimension {
    override val refs: List<Any> get() = listOf(round)
    override fun withValue(v: Double): Dimension = copy(value = v)
}

public data class DiameterDim(val round: RoundId, override val value: Double) : Dimension {
    override val refs: List<Any> get() = listOf(round)
    override fun withValue(v: Double): Dimension = copy(value = v)
}

/** Dasselbe Constraint mit [new] statt [old]; das gleiche Objekt, wenn es [old] nicht verwendet. */
internal fun Constraint.withRound(old: RoundId, new: RoundId): Constraint {
    fun r(x: RoundId) = if (x == old) new else x
    return when (this) {
        is PointOnRound -> if (round == old) copy(round = new) else this
        is EqualRadius -> if (a == old || b == old) EqualRadius(r(a), r(b)) else this
        is Concentric -> if (a == old || b == old) Concentric(r(a), r(b)) else this
        is TangentLineRound -> if (round == old) copy(round = new) else this
        is TangentRoundRound -> if (a == old || b == old) copy(a = r(a), b = r(b)) else this
        is RadiusDim -> if (round == old) copy(round = new) else this
        is DiameterDim -> if (round == old) copy(round = new) else this
        else -> this
    }
}

/** Winkel von der Richtung der Linie [a] zur Richtung der Linie [b] in Grad, gegen den Uhrzeigersinn positiv. */
public data class AngleDim(val a: LineId, val b: LineId, override val value: Double) : Dimension {
    override val refs: List<Any> get() = listOf(a, b)
    override fun withValue(v: Double): Dimension = copy(value = v)
}
