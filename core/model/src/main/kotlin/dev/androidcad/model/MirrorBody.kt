package dev.androidcad.model

/**
 * Namen der Flächen im Spiegelbild eines [MirrorBodyFeature]: Die Rolle bleibt, das Feature wird die Spiegelung, und im Element
 * stehen Feature und Element des Originals – `(Feature des Originals shl 25) or Element`. So bleibt jede gespiegelte Fläche
 * eindeutig und über Neuaufbauten stabil (z. B. für Verrundungen am Spiegelbild).
 *
 * Elemente ab 2²⁵ (Innenflächen einer Schale, Kopien großer oder verschachtelter Muster, Spiegelbilder einer früheren Spiegelung)
 * passen nicht hinein. Sie bekommen einen Streuwert des ganzen Originalnamens unter 2²⁵ – dieser Bereich (Feature-Teil 0) kommt
 * bei der Kodierung oben nie vor (Feature-IDs beginnen bei 1), die übrigen Namen bleiben also bitgleich wie vor dieser Erweiterung.
 */
object MirrorNaming {
    const val ELEMENT_BITS = 25
    private const val ELEMENT_LIMIT = 1L shl ELEMENT_BITS

    /** Name einer Fläche des Spiegelbilds der Spiegelung [owner]; 0 = unbenannt (nur wenn schon das Original unbenannt ist). */
    fun mirrorTag(tag: Long, owner: FeatureId): Long {
        val ref = FaceRef.fromTag(tag) ?: return 0L
        return faceTag(owner, ref.role, mirroredElement(ref))
    }

    /** Element, unter dem die Fläche [original] im Spiegelbild heißt (zum Ansprechen, z. B. in Tests). */
    fun mirroredElement(original: FaceRef): Long =
        if (original.element < ELEMENT_LIMIT) (original.feature.value.toLong() shl ELEMENT_BITS) or original.element
        // shortcut: 25-Bit-Streuwert, zwei solche Flächen gleicher Rolle in einer Spiegelung kollidieren mit ~n²/2²⁶ – bei Hunderten Flächen vernachlässigbar
        else mix(original.tag) ushr (64 - ELEMENT_BITS)

    /**
     * Die Originalfläche zu einer Fläche des Spiegelbilds (umgekehrt zu [mirroredElement]); `null` bei gestreuten Namen (Element < 2²⁵),
     * die sich nicht umkehren lassen. Der Aufbau braucht die Umkehrung nicht – Referenzen auf das Spiegelbild laufen über den Namen selbst.
     */
    fun original(mirrored: FaceRef): FaceRef? {
        if (mirrored.element < ELEMENT_LIMIT) return null
        return FaceRef(FeatureId((mirrored.element shr ELEMENT_BITS).toInt()), mirrored.role, mirrored.element and (ELEMENT_LIMIT - 1))
    }

    /** SplitMix64-Finalisierer: verteilt auch strukturierte Namen gleichmäßig auf die oberen Bits. */
    private fun mix(x: Long): Long {
        var z = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

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
