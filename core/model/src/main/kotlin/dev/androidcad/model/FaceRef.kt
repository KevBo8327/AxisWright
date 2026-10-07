package dev.androidcad.model

/** Welche Fläche einer Extrusion gemeint ist. */
enum class FaceRole(val code: Int) {
    /** Seitenfläche, die aus einem Element der Skizzenkontur entsteht (siehe [FaceRef.element]). */
    SIDE(1),

    /** Stirnfläche auf der −Normalen-Seite der Skizzenebene (kleineres lokales z). */
    BOTTOM(2),

    /** Stirnfläche auf der +Normalen-Seite der Skizzenebene. */
    TOP(3),

    /** Rundungs- bzw. Fasenfläche eines [FilletFeature]; [FaceRef.element] = Index der Kante in [FilletFeature.edges]. */
    ROUND(4),
}

/**
 * Dauerhafter Name einer Körperfläche: **welches Feature** sie erzeugt hat, **welche Rolle** sie dort spielt und – bei
 * Seitenflächen – **aus welchem Skizzenelement** (Linie, Bogen, Kreis, per ID) sie entstanden ist.
 *
 * Der Name hängt nicht an Flächen-Indizes des B-Rep-Körpers (die sich bei jedem Neuaufbau ändern), sondern an der
 * Modellhistorie. Nach Parameteränderungen findet er dieselbe Fläche wieder; verschwindet die Fläche (weggeschnitten,
 * Element gelöscht), findet er keine – das lässt sich als Fehler am referenzierenden Feature melden.
 *
 * [element] hat 40 Bit: die unteren 20 Bit sind das Element des Originals, darüber steht bei Musterkopien die Nummer der Kopie
 * (siehe [PatternGeometry.copyTag]).
 */
data class FaceRef(val feature: FeatureId, val role: FaceRole, val element: Long = 0L) {
    constructor(feature: FeatureId, role: FaceRole, element: Int) : this(feature, role, element.toLong())

    /** Kodierung als 64-Bit-Name für [dev.androidcad.brep.BrepShape]: Feature (16 Bit) | Rolle (8 Bit) | Element (40 Bit). */
    val tag: Long
        get() = faceTag(feature, role, element)

    companion object {
        const val ELEMENT_BITS = 40
        const val ELEMENT_MASK = (1L shl ELEMENT_BITS) - 1

        fun fromTag(tag: Long): FaceRef? {
            if (tag == 0L) return null
            val role = FaceRole.entries.firstOrNull { it.code == ((tag shr 40) and 0xFF).toInt() } ?: return null
            return FaceRef(FeatureId(((tag shr 48) and 0x7FFF).toInt()), role, tag and ELEMENT_MASK)
        }
    }
}

internal fun faceTag(feature: FeatureId, role: FaceRole, element: Long = 0L): Long {
    require(feature.value in 1..0x7FFF) { "Feature-ID außerhalb des Namensbereichs" }
    require(element in 0..FaceRef.ELEMENT_MASK) { "Elementnummer außerhalb des Namensbereichs" }
    return (feature.value.toLong() shl 48) or (role.code.toLong() shl 40) or element
}

internal fun faceTag(feature: FeatureId, role: FaceRole, element: Int): Long = faceTag(feature, role, element.toLong())
