package dev.androidcad.model

import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2

@JvmInline value class FeatureId(val value: Int)

/** Ein Schritt der Modellhistorie. Features werden der Reihe nach angewendet (siehe [Document.build]). */
sealed interface Feature {
    val id: FeatureId
    val name: String
}

/**
 * Eine Skizze auf einer [Workplane]. Die [sketch] ist veränderlich; Änderungen wirken beim nächsten [Document.build].
 *
 * Mit [onFace] hängt die Skizze an einer ebenen Körperfläche: [Document.build] berechnet die Ebene bei jedem Neuaufbau aus dieser
 * Fläche ([FacePlane]), sie folgt der Fläche also bei Änderungen am Körper. [plane] ist dann die zuletzt berechnete Ebene
 * (Anzeige, und Rückfall, wenn die Fläche nicht mehr existiert).
 */
class SketchFeature(
    override val id: FeatureId,
    override val name: String,
    val plane: Workplane,
    val sketch: Sketch,
    val onFace: FaceRef? = null,
    /** In die Skizze projizierte Körperkanten (feste Referenzgeometrie), siehe [EdgeProjection]. */
    val projections: List<ProjectedEdge> = emptyList(),
    /** Mit [onPlane] liegt die Skizze auf einer Arbeitsebene ([PlaneFeature]) und folgt ihr; [plane] ist dann deren letzte Lage. */
    val onPlane: FeatureId? = null,
) : Feature {
    fun copy(
        name: String = this.name,
        plane: Workplane = this.plane,
        sketch: Sketch = this.sketch,
        projections: List<ProjectedEdge> = this.projections,
    ) = SketchFeature(id, name, plane, sketch, onFace, projections, onPlane)

    /** Folgt die Ebene der Skizze dem Körper (Fläche oder Arbeitsebene)? Dann lässt sie sich nicht von Hand verschieben. */
    val planeIsDerived: Boolean get() = onFace != null || onPlane != null

    /** Features, deren Flächen oder Ebenen diese Skizze referenziert. */
    val references: Set<FeatureId>
        get() = buildSet {
            onFace?.let { add(it.feature) }
            onPlane?.let { add(it) }
            projections.forEach { add(it.edge.a.feature); add(it.edge.b.feature) }
        }
}

/** Wie eine Arbeitsebene aus dem Körper entsteht (siehe [PlaneFeature]). */
sealed interface PlaneDef {
    /** Die Ebene der Fläche [face], um [distance] mm entlang ihrer Außennormalen verschoben (negativ = in den Körper). */
    data class Offset(override val face: FaceRef, val distance: Double) : PlaneDef

    /**
     * Ebene durch die gerade Kante [edge] der Fläche [face], um [angle] Grad aus der Fläche gedreht (0° = Ebene der Fläche).
     * Drehachse ist die Kante in kanonischer Richtung; positive Winkel kippen die Normale zu `Kantenrichtung × Normale`.
     */
    data class AtEdge(override val face: FaceRef, val edge: EdgeRef, val angle: Double) : PlaneDef

    val face: FaceRef
}

/**
 * Arbeitsebene: eine Skizzenebene, die aus dem Körper abgeleitet ist ([def]) und bei jedem Neuaufbau neu berechnet wird.
 * [plane] ist die zuletzt berechnete Lage (Anzeige, Rückfall, wenn die Fläche/Kante nicht mehr existiert).
 */
data class PlaneFeature(
    override val id: FeatureId,
    override val name: String,
    val def: PlaneDef,
    val plane: Workplane,
) : Feature {
    val references: Set<FeatureId>
        get() = when (def) {
            is PlaneDef.Offset -> setOf(def.face.feature)
            is PlaneDef.AtEdge -> setOf(def.face.feature, def.edge.a.feature, def.edge.b.feature)
        }
}

/**
 * Eine Körperkante, die in die Skizze projiziert wurde: Die Skizze enthält dafür ein **festes Konstruktionselement** – eine Linie
 * ([line]) oder einen Kreis ([circle]) –, an das man mit Maßen und Constraints binden kann. Die Lage wird bei jedem Neuaufbau
 * aus der Kante neu berechnet (die Kante wird über ihre Nachbarflächen [edge] gefunden) und folgt dem Körper also.
 */
data class ProjectedEdge(val edge: EdgeRef, val line: dev.androidcad.sketch.LineId? = null, val circle: dev.androidcad.sketch.CircleId? = null)

enum class BodyOp {
    /** Material hinzufügen (beim ersten Feature entsteht der Körper). */
    ADD,

    /** Material abtragen. */
    CUT,

    /** Schnittmenge mit dem bisherigen Körper bilden. */
    INTERSECT,
}

/**
 * Extrusion von Skizzenflächen entlang der Ebenennormalen.
 *
 * @param regionPoints Auswahlpunkte in Skizzenkoordinaten: es werden die Flächen extrudiert, die diese Punkte enthalten
 *   (stabil gegenüber Maßänderungen). Leer = alle Flächen der Skizze.
 * @param distance Länge in mm; negativ = gegen die Normale. 0 ist ungültig.
 * @param symmetric Verteilt die Länge zu gleichen Teilen auf beide Seiten der Ebene.
 * @param throughAll „Durch alles“: die Länge reicht beim Aufbau immer durch den ganzen bisherigen Körper ([distance] gibt nur
 *   noch die Richtung an; mit [symmetric] in beide Richtungen).
 * @param distance2 Optionale zweite Länge für die Gegenseite (in mm; > 0).
 * @param untilFace Extrusion endet an dieser ebenen Fläche des Körpers (parallel zur Skizzenebene).
 */
data class ExtrudeFeature(
    override val id: FeatureId,
    override val name: String,
    val sketch: FeatureId,
    val distance: Double,
    val op: BodyOp = BodyOp.ADD,
    val regionPoints: List<Vec2> = emptyList(),
    val symmetric: Boolean = false,
    val throughAll: Boolean = false,
    val distance2: Double? = null,
    val untilFace: FaceRef? = null,
) : Feature {
    /** Features, die diese Extrusion referenziert (Skizze und optionale Zielfläche). */
    val references: Set<FeatureId>
        get() = buildSet {
            add(sketch)
            untilFace?.let { add(it.feature) }
        }
}

/** Drehachse eines [RevolveFeature], in der Ebene seiner Skizze. */
sealed interface RevolveAxis {
    /** Waagrechte Achse der Skizze (durch den Ursprung, Richtung +x). */
    data object SketchX : RevolveAxis

    /** Senkrechte Achse der Skizze (durch den Ursprung, Richtung +y). */
    data object SketchY : RevolveAxis

    /** Eine Linie der Skizze – meist eine Hilfslinie, auch eine Profilkante auf der Achse; Richtung vom ersten zum zweiten Punkt. */
    data class Line(val line: dev.androidcad.sketch.LineId) : RevolveAxis
}

/**
 * Drehung von Skizzenflächen um eine Achse in der Skizzenebene (Wellen, Knöpfe, Rotationsteile).
 *
 * @param angle Winkel in Grad, 0 < |angle| ≤ 360; positiv = gegen den Uhrzeigersinn um die Achsrichtung, 360 = volle Drehung.
 * @param regionPoints wie bei [ExtrudeFeature]: Auswahlpunkte der zu drehenden Flächen; leer = alle Flächen der Skizze.
 * @param symmetric verteilt den Winkel zu gleichen Teilen auf beide Seiten der Skizzenebene.
 *
 * Flächennamen: Mantelflächen wie bei der Extrusion `FaceRef(id, SIDE, Skizzenelement)`; bei einer Teildrehung die Stirnfläche in
 * der Skizzenebene `FaceRef(id, BOTTOM)` (bei [symmetric] die auf der negativen Seite) und die gedrehte `FaceRef(id, TOP)`.
 */
data class RevolveFeature(
    override val id: FeatureId,
    override val name: String,
    val sketch: FeatureId,
    val axis: RevolveAxis,
    val angle: Double = 360.0,
    val op: BodyOp = BodyOp.ADD,
    val regionPoints: List<Vec2> = emptyList(),
    val symmetric: Boolean = false,
) : Feature

/**
 * Eine Körperkante, bezeichnet durch die Namen ihrer **beiden Nachbarflächen** ([FaceRef]). Das ist stabil über Neuaufbauten:
 * Die Kante wird jedes Mal dort gefunden, wo diese beiden Flächen aufeinandertreffen.
 */
data class EdgeRef(val a: FaceRef, val b: FaceRef) {
    companion object {
        /** Kanonische Reihenfolge, damit dieselbe Kante immer gleich aussieht. */
        fun of(x: FaceRef, y: FaceRef) = if (x.tag <= y.tag) EdgeRef(x, y) else EdgeRef(y, x)
    }
}

enum class EdgeKind {
    /** Verrundung mit Radius. */
    ROUND,

    /** Fase mit symmetrischem Abstand. */
    CHAMFER,
}

/**
 * Verrundung oder Fase an den [edges]. [size] ist der Radius bzw. der Fasenabstand in mm. Findet sich eine Kante nicht mehr
 * (Fläche weggeschnitten …), meldet [Document.build] einen Fehler an diesem Feature; die übrige Historie wird weiter gebaut.
 */
data class FilletFeature(
    override val id: FeatureId,
    override val name: String,
    val edges: List<EdgeRef>,
    val size: Double,
    val kind: EdgeKind = EdgeKind.ROUND,
) : Feature {
    /** Features, deren Flächen dieses Feature referenziert. */
    val references: Set<FeatureId> get() = edges.flatMap { listOf(it.a.feature, it.b.feature) }.toSet()
}

/**
 * Aushöhlen: entfernt die Flächen [openFaces] (Öffnungen) und lässt vom Körper eine Wand der Stärke [thickness] mm nach innen stehen.
 *
 * Flächennamen: Außenflächen behalten ihre Namen, der Rand an einer Öffnung heißt wie die entfernte Fläche; die Innenfläche zu einer
 * Außenfläche mit Namen `t` heißt `FaceRef(id, SIDE, innerElement(t))` (siehe [ShellFeature.innerElement]).
 */
data class ShellFeature(
    override val id: FeatureId,
    override val name: String,
    val openFaces: List<FaceRef>,
    val thickness: Double,
) : Feature {
    val references: Set<FeatureId> get() = openFaces.map { it.feature }.toSet()

    companion object {
        /**
         * Element des Namens der Innenfläche zur Außenfläche mit dem Namen [outerTag]: stabil, solange die Außenfläche ihren Namen behält.
         * shortcut: 40-Bit-Streuwert des 64-Bit-Namens, Kollisionen sind bei Tausenden Flächen praktisch ausgeschlossen, aber nicht unmöglich.
         */
        fun innerElement(outerTag: Long): Long = (outerTag * -0x61c8864680b583ebL) ushr 24
    }
}

/** Wie ein Feature vervielfältigt wird (siehe [PatternFeature]). */
sealed interface PatternKind {
    /** Anzahl der Kopien **außer** dem Original. */
    val copies: Int

    /**
     * Lineares Muster: [count] Exemplare (inkl. Original) im Abstand [spacing] mm entlang [direction]. Mit [direction2] wird daraus
     * ein Raster: jede dieser Reihen [count2]-mal (inkl. der ersten) im Abstand [spacing2] mm entlang [direction2].
     */
    data class Linear(
        val direction: Vec3,
        val count: Int,
        val spacing: Double,
        val direction2: Vec3? = null,
        val count2: Int = 2,
        val spacing2: Double = 20.0,
    ) : PatternKind {
        /** Exemplare in zweiter Richtung (1 ohne zweite Richtung). */
        val rows: Int get() = if (direction2 != null) count2 else 1
        override val copies: Int get() = count * rows - 1
    }

    /**
     * Kreismuster um die Achse durch [origin] mit der Richtung [axis]: [count] Exemplare (inkl. Original) über [angle] Grad.
     * Bei 360° gleichmäßig auf den vollen Kreis verteilt (das letzte Exemplar liegt nicht auf dem ersten), sonst mit dem
     * letzten Exemplar genau bei [angle].
     */
    data class Circular(val origin: Vec3, val axis: Vec3, val count: Int, val angle: Double = 360.0) : PatternKind {
        override val copies: Int get() = count - 1
    }

    /** Spiegelbild an der Ebene durch [origin] mit der Normalen [normal]. */
    data class Mirror(val origin: Vec3, val normal: Vec3) : PatternKind {
        override val copies: Int get() = 1
    }
}

/**
 * Vervielfältigt das **Werkzeug** einer Extrusion oder Bohrung ([source]): Kopien werden verschoben, gedreht oder gespiegelt und
 * mit derselben Operation (Hinzufügen/Abtragen) auf den Körper angewendet. Alle Kopien werden zuerst zu einem Werkzeug vereinigt –
 * schlägt etwas fehl, bleibt der Körper unverändert.
 *
 * Flächennamen: Kopie `k` (1 = erste Kopie) nennt ihre Flächen `FaceRef(id, Rolle des Originals, (k shl 20) or Element des Originals)`.
 */
data class PatternFeature(
    override val id: FeatureId,
    override val name: String,
    val source: FeatureId,
    val kind: PatternKind,
    /**
     * Verrundungen und Fasen der Quelle auf jede Kopie übertragen (siehe [Document.patternFillets]): Jede Kante, die an einer Fläche
     * des ursprünglichen Features hängt, wird in jeder Kopie über die Flächennamen der Kopie wiedergefunden und genauso bearbeitet.
     * Rundungsflächen der Kopien heißen `FaceRef(id, ROUND, Kopie-Element von (Verrundung · 1024 + Kante))`.
     */
    val withFillets: Boolean = false,
) : Feature

/**
 * Spiegelt den **ganzen bisherigen Körper** an einer Ebene und vereinigt Original und Spiegelbild (z. B. ein halbes Teil
 * modellieren und zum symmetrischen ergänzen). Die Ebene ist die Arbeitsebene [datum] (folgt ihr beim Neuaufbau) oder, ohne
 * Arbeitsebene, die Ebene durch [origin] mit der Normalen [normal].
 *
 * Flächennamen des Spiegelbilds: siehe [MirrorNaming]; das Original behält seine Namen.
 */
data class MirrorBodyFeature(
    override val id: FeatureId,
    override val name: String,
    val origin: Vec3,
    val normal: Vec3,
    val datum: FeatureId? = null,
) : Feature {
    val references: Set<FeatureId> get() = setOfNotNull(datum)
}

enum class HoleKind {
    /** Einfache zylindrische Bohrung. */
    SIMPLE,

    /** Zylindrische Senkung (Flachsenkung mit Senkdurchmesser und Senktiefe). */
    COUNTERBORE,

    /** Kegelsenkung (mit Senkdurchmesser und Spitzenwinkel, Standard 90°). */
    COUNTERSINK,
}

/**
 * Bohrung senkrecht in eine ebene Körperfläche. Die [face] wird über ihren Namen gefunden (stabil über Neuaufbauten); die
 * Position ist ein Raumpunkt ([x], [y], [z]), der beim Bauen auf die Ebene der Fläche projiziert wird. [depth] = Tiefe in mm
 * ab der Fläche, `null` = durchgehend.
 *
 * Wandfläche der Hauptbohrung: `FaceRef(id, SIDE, 1)`, Boden einer Sacklochbohrung: `FaceRef(id, TOP)`.
 * Senkfläche einer Zylindersenkung: `FaceRef(id, COUNTERBORE, 1)`, Stufe: `FaceRef(id, COUNTERBORE_BOTTOM, 1)`.
 * Kegelfläche einer Kegelsenkung: `FaceRef(id, COUNTERSINK, 1)`.
 */
data class HoleFeature(
    override val id: FeatureId,
    override val name: String,
    val face: FaceRef,
    val x: Double,
    val y: Double,
    val z: Double,
    val diameter: Double,
    val depth: Double? = null,
    /**
     * Parametrische Position: Abstände zu zwei geraden Kanten der Fläche ([EdgeDistance]). Ist sie gesetzt, bestimmt sie die
     * Lage der Bohrung; [x], [y], [z] dienen dann nur noch zur Wahl des richtigen Flächenstücks. `null` = fester Raumpunkt.
     */
    val anchors: Pair<EdgeDistance, EdgeDistance>? = null,
    val kind: HoleKind = HoleKind.SIMPLE,
    val counterboreDiameter: Double? = null,
    val counterboreDepth: Double? = null,
    val countersinkDiameter: Double? = null,
    val countersinkAngle: Double = 90.0,
) : Feature {
    val references: Set<FeatureId>
        get() = buildSet {
            add(face.feature)
            anchors?.toList()?.forEach { add(it.edge.a.feature); add(it.edge.b.feature) }
        }
}
