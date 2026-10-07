package dev.androidcad.model

/**
 * Namen der Flächen im Spiegelbild eines [MirrorBodyFeature]: Die Rolle bleibt, das Feature wird die Spiegelung, und im Element
 * stehen Feature und Element des Originals – `(Feature des Originals shl 25) or Element`. So bleibt jede gespiegelte Fläche
 * eindeutig und über Neuaufbauten stabil (z. B. für Verrundungen am Spiegelbild).
 *
 * Elemente ab 2²⁵ (Kopien großer oder verschachtelter Muster, Spiegelbilder einer früheren Spiegelung) passen nicht hinein; solche
 * Flächen bleiben im Spiegelbild unbenannt – der Körper stimmt trotzdem, nur lassen sie sich nicht als Referenz wählen.
 */
object MirrorNaming {
    const val ELEMENT_BITS = 25
    private const val ELEMENT_LIMIT = 1L shl ELEMENT_BITS

    /** Name einer Fläche des Spiegelbilds der Spiegelung [owner]; 0 = unbenannt. */
    fun mirrorTag(tag: Long, owner: FeatureId): Long {
        val ref = FaceRef.fromTag(tag) ?: return 0L
        if (ref.element >= ELEMENT_LIMIT) return 0L
        return faceTag(owner, ref.role, mirroredElement(ref))
    }

    /** Element, unter dem die Fläche [original] im Spiegelbild heißt (zum Ansprechen, z. B. in Tests). */
    fun mirroredElement(original: FaceRef): Long {
        require(original.element < ELEMENT_LIMIT) { "Element ${original.element} zu groß für eine Spiegelung" }
        return (original.feature.value.toLong() shl ELEMENT_BITS) or original.element
    }

    /** Die Originalfläche zu einer Fläche des Spiegelbilds (umgekehrt zu [mirroredElement]). */
    fun original(mirrored: FaceRef): FaceRef =
        FaceRef(FeatureId((mirrored.element shr ELEMENT_BITS).toInt()), mirrored.role, mirrored.element and (ELEMENT_LIMIT - 1))

    /** Fehlermeldung, wenn die Ebene unbrauchbar ist, sonst `null`. */
    fun validate(f: MirrorBodyFeature): String? {
        val o = f.origin; val n = f.normal
        return when {
            f.datum != null -> null
            !(o.x.isFinite() && o.y.isFinite() && o.z.isFinite()) -> "Lage der Spiegelebene fehlt"
            !(n.x.isFinite() && n.y.isFinite() && n.z.isFinite()) || n.length < 1e-9 -> "Richtung der Spiegelebene fehlt"
            else -> null
        }
    }
}
