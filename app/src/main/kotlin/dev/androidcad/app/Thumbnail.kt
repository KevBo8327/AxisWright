package dev.androidcad.app

import android.graphics.Bitmap
import dev.androidcad.geometry.Mesh
import dev.androidcad.viewport.OrthoCamera
import dev.androidcad.viewport.SceneFrame
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

// Lichtrichtung im Bildraum (normiert): etwas von links, deutlich von oben, überwiegend von vorn
private const val LIGHT_X = -0.27f
private const val LIGHT_Y = -0.55f
private const val LIGHT_Z = 0.79f

/** Farbe des Körpers im Vorschaubild (hell genug für beide Darstellungen; der Hintergrund bleibt durchsichtig). */
private const val THUMB_COLOR = 0xFFA9C0E2.toInt()

/**
 * Vorschaubild eines Körpers in der Schrägansicht der 3D-Ansicht, ohne GPU: die Dreiecke werden in Software gerastert, mit Tiefenpuffer
 * (sonst schieben sich lange Dreiecke falsch übereinander) und doppelter Auflösung zum Glätten der Kanten. Helligkeit nach der Neigung
 * zur Blickrichtung, durchsichtiger Hintergrund, Körper eingepasst.
 */
internal fun renderThumbnail(mesh: Mesh, size: Int): Bitmap? {
    val p = mesh.positions; val tri = mesh.triangles
    val n = p.size / 3
    if (n == 0 || tri.isEmpty()) return null
    val ss = 2 // Überabtastung
    val w = size * ss
    val v = OrthoCamera(ViewCamera.ISO_YAW, ViewCamera.ISO_PITCH, 1f, SceneFrame(FloatArray(3), 1f), 1, 1).view
    val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n)
    for (i in 0 until n) {
        val x = p[3 * i]; val y = p[3 * i + 1]; val z = p[3 * i + 2]
        sx[i] = v[0] * x + v[4] * y + v[8] * z
        sy[i] = -(v[1] * x + v[5] * y + v[9] * z) // Sichtraum-y nach oben, Bild-y nach unten
        sz[i] = v[2] * x + v[6] * y + v[10] * z   // größer = näher am Betrachter
    }
    val minX = sx.min(); val maxX = sx.max(); val minY = sy.min(); val maxY = sy.max()
    val scale = w * 0.86f / maxOf(maxX - minX, maxY - minY, 1e-6f)
    val ox = w / 2f - (minX + maxX) / 2 * scale; val oy = w / 2f - (minY + maxY) / 2 * scale
    for (i in 0 until n) { sx[i] = ox + sx[i] * scale; sy[i] = oy + sy[i] * scale }
    val depth = FloatArray(w * w) { Float.NEGATIVE_INFINITY }
    val color = IntArray(w * w)
    val r = (THUMB_COLOR shr 16) and 0xFF; val g = (THUMB_COLOR shr 8) and 0xFF; val b = THUMB_COLOR and 0xFF
    for (t in 0 until tri.size / 3) {
        val a = tri[3 * t]; val c1 = tri[3 * t + 1]; val c2 = tri[3 * t + 2]
        // Normale im Bildraum (z wie x und y skaliert, damit die Richtung stimmt)
        val ux = sx[c1] - sx[a]; val uy = sy[c1] - sy[a]; val uz = (sz[c1] - sz[a]) * scale
        val vx = sx[c2] - sx[a]; val vy = sy[c2] - sy[a]; val vz = (sz[c2] - sz[a]) * scale
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) continue
        // zum Betrachter gedreht (die Umlaufrichtung ist nicht festgelegt), dann Licht von schräg oben vorn (Bild-y zeigt nach unten)
        val f = if (nz < 0) -1f / len else 1f / len
        val light = 0.38f + 0.62f * maxOf(0f, nx * f * LIGHT_X + ny * f * LIGHT_Y + nz * f * LIGHT_Z)
        val col = (0xFF shl 24) or ((r * light).toInt() shl 16) or ((g * light).toInt() shl 8) or (b * light).toInt()
        // Rasterung über Baryzentrische Koordinaten im Hüllrechteck des Dreiecks
        val area = ux * vy - uy * vx
        if (abs(area) < 1e-9f) continue
        val x0 = maxOf(0, floor(minOf(sx[a], sx[c1], sx[c2])).toInt()); val x1 = minOf(w - 1, ceil(maxOf(sx[a], sx[c1], sx[c2])).toInt())
        val y0 = maxOf(0, floor(minOf(sy[a], sy[c1], sy[c2])).toInt()); val y1 = minOf(w - 1, ceil(maxOf(sy[a], sy[c1], sy[c2])).toInt())
        for (py in y0..y1) for (px in x0..x1) {
            val qx = px + 0.5f - sx[a]; val qy = py + 0.5f - sy[a]
            val l1 = (qx * vy - qy * vx) / area; val l2 = (ux * qy - uy * qx) / area
            if (l1 < 0f || l2 < 0f || l1 + l2 > 1f) continue
            val z = sz[a] + l1 * (sz[c1] - sz[a]) + l2 * (sz[c2] - sz[a])
            val k = py * w + px
            if (z > depth[k]) { depth[k] = z; color[k] = col }
        }
    }
    val big = Bitmap.createBitmap(color, w, w, Bitmap.Config.ARGB_8888)
    val small = Bitmap.createScaledBitmap(big, size, size, true) // Mittelung der Überabtastung glättet die Kanten
    if (small !== big) big.recycle()
    return small
}

/** Schreibt das Vorschaubild von [mesh] nach [file] (über eine temporäre Datei, damit nie ein halbes Bild gelesen wird). */
internal fun writeThumbnail(mesh: Mesh, file: File, size: Int = 320) {
    val bmp = renderThumbnail(mesh, size) ?: return
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
}
