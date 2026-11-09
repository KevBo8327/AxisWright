package dev.androidcad.viewport

import kotlin.math.sqrt

/**
 * Schnittebene zur Begrenzung/Abschneidung im 3D-Raum (Hessesche Normalform).
 * Punkte mit [nx] * x + [ny] * y + [nz] * z + [d] < 0 werden abgeschnitten / verworfen.
 * Die Normale ([nx], [ny], [nz]) zeigt in den sichtbaren Halbraum.
 */
public class ClipPlane(
    public val nx: Float,
    public val ny: Float,
    public val nz: Float,
    public val d: Float,
) {
    /** Prüft, ob ein Punkt im sichtbaren (nicht weggeschnittenen) Halbraum liegt. */
    public fun isVisible(x: Float, y: Float, z: Float): Boolean =
        nx * x + ny * y + nz * z + d >= 0f

    /** Prüft, ob ein Punkt abgeschnitten (unsichtbar) ist. */
    public fun isClipped(x: Float, y: Float, z: Float): Boolean =
        nx * x + ny * y + nz * z + d < 0f

    /** Liefert die Ebenen-Parameter als 4-elementiges FloatArray [nx, ny, nz, d] für Shader-Uniforms. */
    public fun toFloatArray(): FloatArray = floatArrayOf(nx, ny, nz, d)

    public companion object {
        /** Erzeugt eine Schnittebene aus Stützpunkt ([px], [py], [pz]) und Normalenvektor ([nx], [ny], [nz]). */
        public fun fromPointAndNormal(
            px: Float, py: Float, pz: Float,
            nx: Float, ny: Float, nz: Float,
        ): ClipPlane {
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            val inv = if (len > 1e-6f) 1f / len else 1f
            val unx = nx * inv
            val uny = ny * inv
            val unz = nz * inv
            val d = -(unx * px + uny * py + unz * pz)
            return ClipPlane(unx, uny, unz, d)
        }
    }
}
