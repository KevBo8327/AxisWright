package dev.androidcad.viewport

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mittelpunkt und Umkugel-Radius einer Szene (aus den Netzknoten); legt fest, wie die Kamera das Modell einpasst. */
public class SceneFrame(public val center: FloatArray, public val radius: Float) {
    public companion object {
        /** Umkugel um alle Punkte aus [positions] und [more] (x y z hintereinander; z. B. Netz und Skizzenlinien). */
        public fun of(positions: FloatArray, more: FloatArray? = null): SceneFrame {
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var any = false
            for (arr in listOfNotNull(positions, more)) for (i in 0 until arr.size / 3 * 3 step 3) for (k in 0..2) {
                val v = arr[i + k]
                if (v < lo[k]) lo[k] = v
                if (v > hi[k]) hi[k] = v
                any = true
            }
            if (!any) return SceneFrame(FloatArray(3), 1f)
            val c = FloatArray(3) { (lo[it] + hi[it]) / 2 }
            val dx = hi[0] - lo[0]; val dy = hi[1] - lo[1]; val dz = hi[2] - lo[2]
            return SceneFrame(c, (sqrt(dx * dx + dy * dy + dz * dz) / 2).coerceAtLeast(1e-3f))
        }
    }
}

/** Strahl im Raum: [origin] und Einheitsvektor [direction]. */
public class Ray(public val origin: FloatArray, public val direction: FloatArray)

/** Ein auf den Bildschirm projizierter Punkt: Pixel (x nach rechts, y nach unten) und Tiefe (kleiner = näher am Betrachter). */
public class ScreenPoint(public val x: Float, public val y: Float, public val depth: Float)

/**
 * Orthografische Orbit-Kamera des 3D-Viewers (Z zeigt nach oben). Die Matrizen sind spaltenweise wie in OpenGL und liefern
 * exakt dieselbe Ansicht, die der Renderer zeichnet – damit Antippen im Bild dieselben Punkte trifft.
 *
 * [yaw]/[pitch] in Radiant, [zoom] > 1 vergrößert; das Modell wird in die kürzere Bildschirmachse eingepasst.
 */
public class OrthoCamera(
    public val yaw: Float,
    public val pitch: Float,
    public val zoom: Float,
    public val frame: SceneFrame,
    public val width: Int,
    public val height: Int,
) {
    /** Welt → Sichtraum: Modellmitte in den Ursprung, um Z drehen, dann Z-up → Bildschirm-oben kippen. */
    public val view: FloatArray = run {
        val m = identity()
        m.timesAssign(rotation(Math.toDegrees((pitch - PI / 2)).toFloat(), 1f, 0f, 0f))
        m.timesAssign(rotation(Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 0f, 1f))
        m.timesAssign(translation(-frame.center[0], -frame.center[1], -frame.center[2]))
        m
    }

    private val halfW: Float
    private val halfH: Float

    init {
        val ext = frame.radius * 1.15f / zoom
        halfW = if (width < height) ext else ext * width / height
        halfH = if (width < height) ext * height / width else ext
    }

    public val projection: FloatArray = ortho(-halfW, halfW, -halfH, halfH, -frame.radius * 2f, frame.radius * 2f)

    public val mvp: FloatArray = multiply(projection, view)

    /** Einheitsvektor in Weltkoordinaten, der von der Szene zum Betrachter zeigt. */
    public val towardViewer: FloatArray = run {
        // Sichtraum-z zeigt zum Betrachter; die Zeilen der Rotation (Zeile 2 von `view`) sind die Weltrichtung dieser Achse.
        floatArrayOf(view[2], view[6], view[10])
    }

    private val inverseMvp: FloatArray by lazy { invert(mvp) }

    /**
     * Blickstrahl durch den Bildschirmpunkt ([x], [y]) in Pixeln: Ursprung vor der Szene (Nahebene), Einheitsrichtung in die
     * Szene hinein. Umkehrung von [project]; Grundlage für das Antippen von Flächen.
     */
    public fun ray(x: Float, y: Float): Ray {
        val nx = 2f * x / width - 1f
        val ny = 1f - 2f * y / height
        val a = unproject(nx, ny, -1f); val b = unproject(nx, ny, 1f)
        val d = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return Ray(a, floatArrayOf(d[0] / len, d[1] / len, d[2] / len))
    }

    private fun unproject(nx: Float, ny: Float, nz: Float): FloatArray {
        val m = inverseMvp
        val w = m[3] * nx + m[7] * ny + m[11] * nz + m[15]
        return floatArrayOf(
            (m[0] * nx + m[4] * ny + m[8] * nz + m[12]) / w,
            (m[1] * nx + m[5] * ny + m[9] * nz + m[13]) / w,
            (m[2] * nx + m[6] * ny + m[10] * nz + m[14]) / w,
        )
    }

    public fun project(x: Float, y: Float, z: Float): ScreenPoint {
        val cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
        val cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
        val cz = mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14]
        return ScreenPoint((cx * 0.5f + 0.5f) * width, (1f - (cy * 0.5f + 0.5f)) * height, cz)
    }

    public companion object {
        private fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

        private fun translation(x: Float, y: Float, z: Float) = identity().also { it[12] = x; it[13] = y; it[14] = z }

        /** Drehung um die Achse (x, y, z) wie `android.opengl.Matrix.setRotateM` (Winkel in Grad). */
        private fun rotation(deg: Float, x0: Float, y0: Float, z0: Float): FloatArray {
            val len = sqrt(x0 * x0 + y0 * y0 + z0 * z0)
            val x = x0 / len; val y = y0 / len; val z = z0 / len
            val rad = Math.toRadians(deg.toDouble())
            val c = cos(rad).toFloat(); val s = sin(rad).toFloat(); val nc = 1f - c
            val m = identity()
            m[0] = x * x * nc + c; m[4] = x * y * nc - z * s; m[8] = x * z * nc + y * s
            m[1] = y * x * nc + z * s; m[5] = y * y * nc + c; m[9] = y * z * nc - x * s
            m[2] = x * z * nc - y * s; m[6] = y * z * nc + x * s; m[10] = z * z * nc + c
            return m
        }

        private fun ortho(l: Float, r: Float, b: Float, t: Float, n: Float, f: Float) = FloatArray(16).also {
            it[0] = 2f / (r - l); it[5] = 2f / (t - b); it[10] = -2f / (f - n); it[15] = 1f
            it[12] = -(r + l) / (r - l); it[13] = -(t + b) / (t - b); it[14] = -(f + n) / (f - n)
        }

        private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (col in 0..3) for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
            return out
        }

        /** Inverse einer spaltenweisen 4x4-Matrix (Gauss-Jordan mit Pivotsuche). */
        internal fun invert(m: FloatArray): FloatArray {
            val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) m[c * 4 + r].toDouble() else if (c - 4 == r) 1.0 else 0.0 } }
            for (col in 0..3) {
                var piv = col
                for (r in col + 1..3) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[piv][col])) piv = r
                require(kotlin.math.abs(a[piv][col]) > 1e-12) { "Matrix nicht invertierbar" }
                val t = a[piv]; a[piv] = a[col]; a[col] = t
                val d = a[col][col]
                for (c in 0..7) a[col][c] /= d
                for (r in 0..3) if (r != col) {
                    val f = a[r][col]
                    if (f != 0.0) for (c in 0..7) a[r][c] -= f * a[col][c]
                }
            }
            return FloatArray(16) { i -> a[i % 4][4 + i / 4].toFloat() }
        }

        private fun FloatArray.timesAssign(o: FloatArray) = multiply(this, o).copyInto(this)
    }
}
