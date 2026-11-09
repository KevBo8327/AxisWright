package dev.androidcad.model

/**
 * Normgrößen für Gewinde M3 bis M10.
 *
 * Quellenangaben:
 * - Durchgangsbohrung mittel: ISO 273 (DIN EN 20273) „Durchgangslöcher für Schrauben“, Reihe mittel.
 * - Zylindrische Senkung (Flachsenkung): DIN 974-1 „Senkungen für Zylinderschrauben“ (für ISO 4762 / DIN 912), Reihe 1.
 * - Kegelsenkung 90°: ISO 10642 (DIN EN ISO 10642 / DIN 74 Form F) für Senkschrauben mit Innensechskant.
 */
enum class HoleStandardSize(val label: String) {
    M3("M3"),
    M4("M4"),
    M5("M5"),
    M6("M6"),
    M8("M8"),
    M10("M10"),
}

/**
 * Maße einer Normgröße (alle Angaben in mm, Winkel in Grad).
 */
data class HoleStandardValues(
    val size: HoleStandardSize,
    /** Durchgangsbohrungsdurchmesser mittel nach ISO 273. */
    val diameter: Double,
    /** Senkdurchmesser für Zylinderschrauben nach DIN 974-1 (ISO 4762). */
    val counterboreDiameter: Double,
    /** Senktiefe für Zylinderschrauben nach DIN 974-1 (ISO 4762). */
    val counterboreDepth: Double,
    /** Senkdurchmesser 90°-Kegelsenkung für Senkschrauben nach ISO 10642 (DIN 74 Form F). */
    val countersinkDiameter: Double,
    /** Kegelsenkungswinkel nach ISO 10642 (Standard 90°). */
    val countersinkAngle: Double = 90.0,
)

object HoleStandards {
    /**
     * Normtabelle M3 bis M10:
     * - M3:  D = 3.4 mm, Senkung Ø 6.0 mm, Tiefe 3.4 mm, Kegelsenkung Ø 7.0 mm (90°)
     * - M4:  D = 4.5 mm, Senkung Ø 8.0 mm, Tiefe 4.4 mm, Kegelsenkung Ø 9.2 mm (90°)
     * - M5:  D = 5.5 mm, Senkung Ø 10.0 mm, Tiefe 5.4 mm, Kegelsenkung Ø 11.5 mm (90°)
     * - M6:  D = 6.6 mm, Senkung Ø 11.0 mm, Tiefe 6.4 mm, Kegelsenkung Ø 13.7 mm (90°)
     * - M8:  D = 9.0 mm, Senkung Ø 15.0 mm, Tiefe 8.6 mm, Kegelsenkung Ø 18.3 mm (90°)
     * - M10: D = 11.0 mm, Senkung Ø 18.0 mm, Tiefe 10.6 mm, Kegelsenkung Ø 22.7 mm (90°)
     */
    val ENTRIES: List<HoleStandardValues> = listOf(
        HoleStandardValues(HoleStandardSize.M3, diameter = 3.4, counterboreDiameter = 6.0, counterboreDepth = 3.4, countersinkDiameter = 7.0),
        HoleStandardValues(HoleStandardSize.M4, diameter = 4.5, counterboreDiameter = 8.0, counterboreDepth = 4.4, countersinkDiameter = 9.2),
        HoleStandardValues(HoleStandardSize.M5, diameter = 5.5, counterboreDiameter = 10.0, counterboreDepth = 5.4, countersinkDiameter = 11.5),
        HoleStandardValues(HoleStandardSize.M6, diameter = 6.6, counterboreDiameter = 11.0, counterboreDepth = 6.4, countersinkDiameter = 13.7),
        HoleStandardValues(HoleStandardSize.M8, diameter = 9.0, counterboreDiameter = 15.0, counterboreDepth = 8.6, countersinkDiameter = 18.3),
        HoleStandardValues(HoleStandardSize.M10, diameter = 11.0, counterboreDiameter = 18.0, counterboreDepth = 10.6, countersinkDiameter = 22.7),
    )

    fun forSize(size: HoleStandardSize): HoleStandardValues = ENTRIES.first { it.size == size }

    fun find(label: String): HoleStandardValues? =
        ENTRIES.firstOrNull { it.size.label.equals(label, ignoreCase = true) }
}
