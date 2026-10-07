package dev.androidcad.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import dev.androidcad.model.Workplane
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sin

/**
 * Blickrichtung des 3D-Viewers (Orbit-Kamera, Z oben): [yaw] um die Z-Achse, [pitch] Neigung (π/2 = senkrecht von oben,
 * 0 = waagerecht). Als Zustandsobjekt, damit der Wechsel Skizze → Modell die Kamera sanft von der Skizzenebene in die
 * isometrische Ansicht schwenken kann.
 */
class ViewCamera {
    var yaw by mutableFloatStateOf(ISO_YAW)
    var pitch by mutableFloatStateOf(ISO_PITCH)
    var zoom by mutableFloatStateOf(1f)

    /** Sofort auf die angegebene Blickrichtung; der Zoom wird zurückgesetzt. */
    fun snapTo(yaw: Float, pitch: Float) {
        this.yaw = yaw
        this.pitch = pitch
        zoom = 1f
    }

    /** Schwenkt in [durationMs] zur Blickrichtung (der Gierwinkel nimmt den kürzeren Weg). */
    suspend fun animateTo(targetYaw: Float, targetPitch: Float, durationMs: Int = 500) {
        val y0 = yaw; val p0 = pitch; val z0 = zoom
        var dy = (targetYaw - y0) % TWO_PI
        if (dy > PI) dy -= TWO_PI
        if (dy < -PI) dy += TWO_PI
        val t0 = System.nanoTime()
        while (true) {
            val t = ((System.nanoTime() - t0) / 1e6f / durationMs).coerceIn(0f, 1f)
            val e = t * t * (3f - 2f * t) // weich anfahren und abbremsen
            yaw = y0 + dy.toFloat() * e
            pitch = p0 + (targetPitch - p0) * e
            zoom = z0 + (1f - z0) * e
            if (t >= 1f) break
            delay(16)
        }
    }

    companion object {
        const val ISO_YAW = -0.6f
        const val ISO_PITCH = 0.5f
        private const val TWO_PI = (2 * PI).toFloat()

        /**
         * Blickrichtung (Gierwinkel, Neigung) senkrecht auf die Skizzenebene, von außen auf sie gesehen: Die Skizze sieht aus wie in
         * der 2D-Ansicht (x nach rechts, y nach oben). Gilt für jede Ebene, deren x-Richtung waagerecht liegt (Grund-, Seiten- und
         * Flächenebenen nach [dev.androidcad.model.FacePlane]); die Kamera hat kein Rollen, deshalb passt die Ausrichtung dort exakt.
         */
        fun planeView(plane: Workplane): Pair<Float, Float> {
            val n = plane.normal
            val theta = -acos(n.z.coerceIn(-1.0, 1.0)) // Neigung gegen die Waagerechte, von oben gesehen negativ
            val sinT = sin(theta)
            val yaw = if (abs(sinT) < 1e-6) 0.0 else atan2(n.x / sinT, n.y / sinT)
            return yaw.toFloat() to (PI / 2 + theta).toFloat()
        }
    }
}
