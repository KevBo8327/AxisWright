package dev.androidcad.model.samples

import dev.androidcad.model.Document
import dev.androidcad.model.ExtrudeFeature
import dev.androidcad.model.SketchFeature
import dev.androidcad.model.BuildResult
import dev.androidcad.sketch.ConstraintId
import dev.androidcad.sketch.EqualRadius
import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.SolveResult

/** Maße der Beispielplatte (mm). */
data class PlateParams(
    val width: Double = 80.0,
    val height: Double = 50.0,
    /** Abstand der Bohrungsmitten vom nächsten Rand. */
    val margin: Double = 10.0,
    val holeDiameter: Double = 6.0,
    val thickness: Double = 8.0,
) {
    /** Erwartetes Volumen in mm³ (Platte minus vier Bohrungen) – zur Kontrolle in Tests. */
    val expectedVolume: Double get() = (width * height - 4 * Math.PI * (holeDiameter / 2) * (holeDiameter / 2)) * thickness
}

/**
 * Beispiel für den kompletten parametrischen Ablauf: eine vollständig bemaßte Skizze (Rechteck mit vier
 * Bohrungen, Randabstände und Durchmesser als Maße) wird zu einer Platte extrudiert. [apply] ändert nur die
 * Maße; [build] löst die Skizze neu und erzeugt den Körper aus der Historie.
 */
class PlateModel(initial: PlateParams = PlateParams()) {
    val document = Document()
    private val sketchFeature: SketchFeature = document.addSketch("Platte")

    /** Die Skizze des Beispiels (z. B. zum Laden in den Editor). */
    val sketch: SketchFeature get() = sketchFeature

    /** Die Extrusion des Beispiels. */
    val extrusion: ExtrudeFeature get() = extrude
    private val widthDim: ConstraintId
    private val heightDim: ConstraintId
    private val holeDiameterDim: ConstraintId
    private val holeDims = ArrayList<ConstraintId>() // (horizontal, vertikal) je Bohrung, in Eckenreihenfolge
    private var extrude: ExtrudeFeature

    init {
        val s = sketchFeature.sketch
        val p = initial
        // leicht verwackelt gezeichnet – der Solver bringt die Skizze auf die Maße
        val r = s.addRectangle(0.0, 0.0, p.width * 0.97, p.height * 1.03)
        s.add(Fixed(r.corners[0], 0.0, 0.0))
        widthDim = s.addLength(r.edges[0], p.width)
        heightDim = s.addLength(r.edges[1], p.height)
        val (bl, br, tr, tl) = r.corners
        val m = p.margin
        // Mitten der vier Bohrungen, jeweils vom nächstgelegenen Eckpunkt aus bemaßt
        val specs = listOf(Triple(bl, 1, 1), Triple(br, -1, 1), Triple(tr, -1, -1), Triple(tl, 1, -1))
        val holes = specs.map { (corner, sx, sy) ->
            val c = s.point(corner)
            val hole = s.addCircle(c.x + sx * m, c.y + sy * m, p.holeDiameter / 2)
            val center = s.circle(hole).center
            holeDims += s.addHorizontalDistance(corner, center, sx * m)
            holeDims += s.addVerticalDistance(corner, center, sy * m)
            hole
        }
        holeDiameterDim = s.addDiameter(holes[0], p.holeDiameter)
        for (h in holes.drop(1)) s.add(EqualRadius(holes[0], h))
        extrude = document.addExtrude("Extrusion", sketchFeature, p.thickness)
    }

    /** Überträgt die Maße in Skizze und Extrusion (ohne neu zu berechnen). */
    fun apply(p: PlateParams) {
        val s = sketchFeature.sketch
        s.setDimension(widthDim, p.width)
        s.setDimension(heightDim, p.height)
        s.setDimension(holeDiameterDim, p.holeDiameter)
        val signs = listOf(1 to 1, -1 to 1, -1 to -1, 1 to -1)
        signs.forEachIndexed { i, (sx, sy) ->
            s.setDimension(holeDims[2 * i], sx * p.margin)
            s.setDimension(holeDims[2 * i + 1], sy * p.margin)
        }
        extrude = extrude.copy(distance = p.thickness)
        document.update(extrude)
    }

    /** Löst die Skizze (Freiheitsgrade, Konflikte …). [build] löst sie ebenfalls; hier für die Anzeige des Ergebnisses. */
    fun solveSketch(): SolveResult = sketchFeature.sketch.solve()

    fun build(): BuildResult = document.build()
}
