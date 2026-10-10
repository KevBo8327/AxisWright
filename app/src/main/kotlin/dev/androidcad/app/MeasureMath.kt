package dev.androidcad.app

import dev.androidcad.model.Vec3
import kotlin.math.abs
import kotlin.math.acos

/** Winkel beim Messen: Richtungen ohne Vorzeichen, Ergebnis 0…90° (parallel = 0°, senkrecht = 90°). */
internal object MeasureMath {
    /** Winkel zwischen zwei Geraden – oder zwei Ebenen über ihre Normalen. */
    fun angle(a: Vec3, b: Vec3): Double =
        Math.toDegrees(acos((abs(a.dot(b)) / (a.length * b.length)).coerceAtMost(1.0)))

    /** Winkel zwischen einer Geraden mit Richtung [d] und einer Ebene mit Normale [n]. */
    fun lineToPlane(d: Vec3, n: Vec3): Double = 90.0 - angle(d, n)
}
