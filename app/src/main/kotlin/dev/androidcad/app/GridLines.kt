package dev.androidcad.app

import dev.androidcad.model.Vec3
import dev.androidcad.model.Workplane
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/** Linien des Bodengitters: feine ([minor]) und jede fünfte kräftig ([major]), als Linienpaare x1 y1 z1 x2 y2 z2 …; [step] = Abstand in mm. */
class GridLines(val minor: FloatArray, val major: FloatArray, val step: Double)

/**
 * Gitter auf der Ebene [plane] um den Fußpunkt von [center], groß genug für einen Körper der Ausdehnung [extent] (mm). Der Abstand ist
 * ein runder Wert (1, 2, 5, 10, 20 … mm), so dass etwa 20 feine Felder über den Körper gehen; die Mitte rastet auf das Gitter ein,
 * damit es beim Ändern des Körpers nicht wandert.
 * shortcut: Abstand folgt der Körpergröße, nicht dem Zoom; beim starken Hineinzoomen bleibt das Gitter grob.
 */
fun gridLines(plane: Workplane, center: Vec3, extent: Double): GridLines {
    val step = niceStep(extent / 20)
    val major = step * 5
    val toC = Vec3(center.x - plane.origin.x, center.y - plane.origin.y, center.z - plane.origin.z)
    val cu = round(toC.dot(plane.xDir) / major) * major
    val cv = round(toC.dot(plane.yDir) / major) * major
    val half = ceil((extent * 0.75 + major / 2) / major) * major // + halbes Feld: das Einrasten verschiebt die Mitte um bis zu so viel
    val n = round(2 * half / step).toInt()
    val minor = ArrayList<Float>(); val strong = ArrayList<Float>()
    fun put(out: MutableList<Float>, a: Vec3, b: Vec3) { out += a.x.toFloat(); out += a.y.toFloat(); out += a.z.toFloat(); out += b.x.toFloat(); out += b.y.toFloat(); out += b.z.toFloat() }
    for (i in 0..n) {
        val t = -half + i * step
        val out = if (i % 5 == 0) strong else minor // half ist ein Vielfaches von major: Rand und Mitte sind kräftig
        put(out, plane.toWorld(cu + t, cv - half), plane.toWorld(cu + t, cv + half))
        put(out, plane.toWorld(cu - half, cv + t), plane.toWorld(cu + half, cv + t))
    }
    return GridLines(minor.toFloatArray(), strong.toFloatArray(), step)
}

/** Kleinster runder Wert (1, 2, 5 × 10ⁿ) ≥ [raw]; mindestens 0,1 mm. */
internal fun niceStep(raw: Double): Double {
    val r = raw.coerceAtLeast(0.1)
    val p = 10.0.pow(floor(log10(r)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= r - 1e-12 }
}
