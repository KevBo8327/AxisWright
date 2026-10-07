package dev.androidcad.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Wie die k-te Kopie eines Musters aus dem Original hervorgeht. */
sealed interface Placement {
    class Translate(val dx: Double, val dy: Double, val dz: Double) : Placement

    /** Starre Drehung als 3×4-Matrix `[R | t]` (zeilenweise, wie [Workplane.matrix3x4]). */
    class Rotate(val matrix3x4: DoubleArray) : Placement

    class Reflect(val origin: Vec3, val normal: Vec3) : Placement
}

/** Reine Geometrie von [PatternKind]; ohne native Abhängigkeiten testbar. */
object PatternGeometry {
    const val MAX_COUNT = 200

    /** Fehlermeldung, wenn die Parameter unbrauchbar sind, sonst `null`. */
    fun validate(kind: PatternKind): String? {
        fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
        return when (kind) {
            is PatternKind.Linear -> when {
                kind.count !in 2..MAX_COUNT -> "Anzahl muss zwischen 2 und $MAX_COUNT liegen"
                !kind.spacing.isFinite() || kind.spacing == 0.0 -> "Abstand darf nicht 0 sein"
                !finite(kind.direction) || kind.direction.length < 1e-9 -> "Richtung fehlt"
                kind.direction2 == null -> null
                kind.count2 !in 2..MAX_COUNT -> "Anzahl in zweiter Richtung muss zwischen 2 und $MAX_COUNT liegen"
                kind.count * kind.count2 > MAX_COUNT -> "Höchstens $MAX_COUNT Exemplare (${kind.count} × ${kind.count2} = ${kind.count * kind.count2})"
                !kind.spacing2.isFinite() || kind.spacing2 == 0.0 -> "Abstand in zweiter Richtung darf nicht 0 sein"
                !finite(kind.direction2) || kind.direction2.length < 1e-9 -> "Zweite Richtung fehlt"
                kind.direction.normalized().cross(kind.direction2.normalized()).length < 1e-6 -> "Die zweite Richtung muss quer zur ersten liegen"
                else -> null
            }
            is PatternKind.Circular -> when {
                kind.count !in 2..MAX_COUNT -> "Anzahl muss zwischen 2 und $MAX_COUNT liegen"
                !kind.angle.isFinite() || kind.angle == 0.0 || abs(kind.angle) > 360.0 -> "Winkel muss zwischen −360° und 360° liegen und darf nicht 0 sein"
                !finite(kind.axis) || kind.axis.length < 1e-9 || !finite(kind.origin) -> "Drehachse fehlt"
                else -> null
            }
            is PatternKind.Mirror ->
                if (!finite(kind.normal) || kind.normal.length < 1e-9 || !finite(kind.origin)) "Spiegelebene fehlt" else null
        }
    }

    /** Winkel zwischen zwei Exemplaren eines Kreismusters in Grad. */
    fun angleStep(kind: PatternKind.Circular): Double =
        if (abs(kind.angle) >= 360.0 - 1e-9) kind.angle / kind.count else kind.angle / (kind.count - 1)

    /** Platzierungen der Kopien (ohne Original), `copies` Stück; Index 0 ist die erste Kopie. */
    fun placements(kind: PatternKind): List<Placement> = when (kind) {
        is PatternKind.Linear -> {
            val d = kind.direction.normalized() * kind.spacing
            val e = kind.direction2?.normalized()?.times(kind.spacing2) ?: Vec3(0.0, 0.0, 0.0)
            grid(kind).map { (a, b) -> Placement.Translate(d.x * a + e.x * b, d.y * a + e.y * b, d.z * a + e.z * b) }
        }
        is PatternKind.Circular -> {
            val step = angleStep(kind)
            (1..kind.copies).map { k -> Placement.Rotate(rotationAbout(kind.origin, kind.axis, Math.toRadians(step * k))) }
        }
        is PatternKind.Mirror -> listOf(Placement.Reflect(kind.origin, kind.normal))
    }

    /** Ob das Muster zwei Richtungen hat (ein Raster). Solche Muster benennen ihre Kopien wie verschachtelte. */
    fun isTwoWay(kind: PatternKind): Boolean = kind is PatternKind.Linear && kind.direction2 != null

    /** Lage der Kopien eines Linearmusters als (Index in Richtung 1, Index in Richtung 2), zeilenweise, ohne das Original (0, 0). */
    private fun grid(kind: PatternKind.Linear): List<Pair<Int, Int>> =
        (0 until kind.rows).flatMap { b -> (0 until kind.count).map { a -> a to b } }.drop(1)

    /**
     * Nummer jeder Kopie (in der Reihenfolge von [placements]) für die Flächennamen: einfache Muster `k`, Raster `b · 256 + a`
     * (Spalte a, Zeile b) – die erste Zeile heißt damit wie eine einfache Reihe, und Kopien behalten ihren Namen, wenn sich die
     * Anzahl in der anderen Richtung ändert.
     */
    fun instances(kind: PatternKind): List<Long> =
        if (kind is PatternKind.Linear && kind.direction2 != null) grid(kind).map { (a, b) -> b.toLong() * NEST + a }
        else (1..kind.copies).map { it.toLong() }

    /** Drehung um die Achse durch [origin] mit Richtung [axis] um [radians] (Rodrigues), als 3×4-Matrix. */
    fun rotationAbout(origin: Vec3, axis: Vec3, radians: Double): DoubleArray {
        val u = axis.normalized()
        val c = cos(radians); val s = sin(radians); val t = 1 - c
        val r = doubleArrayOf(
            t * u.x * u.x + c, t * u.x * u.y - s * u.z, t * u.x * u.z + s * u.y,
            t * u.x * u.y + s * u.z, t * u.y * u.y + c, t * u.y * u.z - s * u.x,
            t * u.x * u.z - s * u.y, t * u.y * u.z + s * u.x, t * u.z * u.z + c,
        )
        // t = p − R·p: der Achsenpunkt bleibt fest
        fun row(i: Int) = r[3 * i] * origin.x + r[3 * i + 1] * origin.y + r[3 * i + 2] * origin.z
        return doubleArrayOf(
            r[0], r[1], r[2], origin.x - row(0),
            r[3], r[4], r[5], origin.y - row(1),
            r[6], r[7], r[8], origin.z - row(2),
        )
    }

    /**
     * Neuer Flächenname für Kopie [instance] (1-basiert) des Musters [owner]: Rolle und Element des Originals bleiben erhalten,
     * das Feature wird das Muster, die Nummer der Kopie wandert in die oberen Bits des Elements. Unbenannte Flächen bleiben unbenannt.
     */
    fun copyTag(tag: Long, owner: FeatureId, instance: Int): Long = copyTag(tag, owner, instance.toLong())

    /** Wie [copyTag], mit der Kopienummer aus [instances] (bei Rastern `Zeile · 256 + Spalte`). */
    fun copyTag(tag: Long, owner: FeatureId, instance: Long): Long {
        val ref = FaceRef.fromTag(tag) ?: return 0L
        require(ref.element < BASE_LIMIT) { "Elementnummer ${ref.element} zu groß für ein Muster" }
        require(instance in 1 until NEST.toLong() * NEST) { "Kopie $instance außerhalb des Bereichs" }
        return faceTag(owner, ref.role, (instance shl INSTANCE_SHIFT) or ref.element)
    }

    /**
     * Flächenname für Kopie [instance] eines Musters [owner], das selbst ein Muster vervielfältigt (eine Verschachtelungsebene):
     * Das kopierte Werkzeug enthält Flächen des inneren Originals (Element < 2²⁰, innere Kopie 0) und der inneren Kopien
     * (innere Kopie k in den oberen Bits). Neu: `((instance · 256 + k) shl 20) or Element` – eindeutig für jede Kombination aus
     * äußerer und innerer Kopie und immer ≥ 256 in den oberen Bits (unterscheidbar von einfachen Mustern).
     */
    fun nestedCopyTag(tag: Long, owner: FeatureId, instance: Int): Long {
        val ref = FaceRef.fromTag(tag) ?: return 0L
        val inner = ref.element shr INSTANCE_SHIFT
        val base = ref.element and (BASE_LIMIT - 1)
        require(inner < NEST) { "Das vervielfältigte Muster ist selbst schon verschachtelt" }
        require(instance in 1 until NEST) { "Kopie $instance außerhalb des Bereichs" }
        return faceTag(owner, ref.role, ((instance.toLong() * NEST + inner) shl INSTANCE_SHIFT) or base)
    }

    /** Element einer Fläche in Kopie [instance] eines einfachen Musters (zum Ansprechen von Flächen, z. B. in Tests und Verrundungen). */
    fun copyElement(instance: Int, element: Long): Long = (instance.toLong() shl INSTANCE_SHIFT) or element

    /** Element einer Fläche in äußerer Kopie [outer] (0 = Original) und innerer Kopie [inner] (0 = inneres Original) eines verschachtelten Musters. */
    fun nestedElement(outer: Int, inner: Int, element: Long): Long = ((outer.toLong() * NEST + inner) shl INSTANCE_SHIFT) or element

    const val INSTANCE_SHIFT = 20
    private const val BASE_LIMIT = 1L shl INSTANCE_SHIFT

    /** Faktor der äußeren Kopie bei verschachtelten Mustern; zugleich Obergrenze der Kopien je Ebene (> [MAX_COUNT]). */
    const val NEST = 256
}
