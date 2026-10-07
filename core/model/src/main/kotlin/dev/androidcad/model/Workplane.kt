package dev.androidcad.model

import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length: Double get() = sqrt(dot(this))
    fun normalized(): Vec3 = length.let { require(it > 1e-12) { "Nullvektor" }; Vec3(x / it, y / it, z / it) }
}

/**
 * Skizzenebene im Raum. Lokale Achsen: x = [xDir], y = [normal] × [xDir], z = [normal] (rechtshändig).
 * Skizzenkoordinaten (x, y) werden so in den Raum abgebildet; Extrusionen laufen entlang [normal].
 */
class Workplane(val origin: Vec3, xDir: Vec3, normal: Vec3) {
    val normal: Vec3 = normal.normalized()
    val xDir: Vec3 = (xDir - this.normal * xDir.dot(this.normal)).normalized() // senkrecht zur Normalen
    val yDir: Vec3 = this.normal cross this.xDir

    /** 3×4-Matrix `[xDir yDir normal | origin]`, zeilenweise: bildet lokale Koordinaten in den Raum ab. */
    fun matrix3x4(): DoubleArray = doubleArrayOf(
        xDir.x, yDir.x, normal.x, origin.x,
        xDir.y, yDir.y, normal.y, origin.y,
        xDir.z, yDir.z, normal.z, origin.z,
    )

    /** Verschobene Kopie entlang der Normalen (z. B. Ebene auf der Oberseite eines Körpers). */
    fun offset(distance: Double) = Workplane(origin + normal * distance, xDir, normal)

    /** Lokaler Punkt (x, y) im Raum. */
    fun toWorld(x: Double, y: Double): Vec3 = origin + xDir * x + yDir * y

    companion object {
        /** Grundebene: x = X, y = Y, Normale +Z. */
        val XY = Workplane(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))

        /** Vorderansicht: x = X, y = Z, Normale −Y (zeigt zum Betrachter, der von −Y auf das Modell blickt). */
        val XZ = Workplane(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0))

        /** Seitenansicht: x = Y, y = Z, Normale +X. */
        val YZ = Workplane(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0))
    }
}

internal operator fun Vec3.minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
