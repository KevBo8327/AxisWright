package dev.androidcad.brep

class BrepException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal object NativeBrep {
    init {
        System.loadLibrary("cadbrep")
    }

    @JvmStatic external fun extrude(profile: DoubleArray, height: Double, segmentTags: LongArray, bottomTag: Long, topTag: Long): Long
    @JvmStatic external fun revolve(profile: DoubleArray, axis: DoubleArray, angle: Double, segmentTags: LongArray, startTag: Long, endTag: Long): Long
    @JvmStatic external fun boolean(a: Long, b: Long, op: Int): Long
    @JvmStatic external fun fillet(h: Long, edgeIds: IntArray, edgeTags: LongArray, radius: Double, kind: Int): Long
    @JvmStatic external fun translate(h: Long, x: Double, y: Double, z: Double): Long
    @JvmStatic external fun transform(h: Long, m: DoubleArray): Long
    @JvmStatic external fun mirror(h: Long, plane: DoubleArray): Long
    @JvmStatic external fun retag(h: Long, tags: LongArray, aliasPairs: LongArray): Long
    @JvmStatic external fun faceAliases(h: Long): LongArray
    @JvmStatic external fun facesWithTag(h: Long, tag: Long): IntArray
    @JvmStatic external fun edgesBetween(h: Long, a: Long, b: Long): IntArray
    @JvmStatic external fun unifyFaces(h: Long): Long
    @JvmStatic external fun volume(h: Long): Double
    @JvmStatic external fun counts(h: Long): IntArray
    @JvmStatic external fun edgeInfo(h: Long): DoubleArray
    @JvmStatic external fun tessellate(h: Long, linear: Double, angular: Double): Array<Any?>
    @JvmStatic external fun faceTags(h: Long): LongArray
    @JvmStatic external fun faceGeometry(h: Long): DoubleArray
    @JvmStatic external fun edgeFaceTags(h: Long): LongArray
    @JvmStatic external fun release(h: Long)
}

/** Geometrische Kenngrößen einer Kante (für die Auswahl; [id] ist nur innerhalb dieses Körpers gültig). */
data class EdgeInfo(
    val id: Int,
    val start: Triple<Double, Double, Double>,
    val end: Triple<Double, Double, Double>,
    val length: Double,
    /** `GeomAbs_CurveType`: 0 = Linie, 1 = Kreis, … */
    val curveType: Int,
    /** Mittelpunkt und Radius, wenn die Kante ein Kreis oder Kreisbogen ist. */
    val center: Triple<Double, Double, Double>? = null,
    val radius: Double = 0.0,
) {
    val isLine: Boolean get() = curveType == 0
    val isCircle: Boolean get() = curveType == 1
}

/** Ebene einer ebenen Körperfläche: ein Punkt darauf und die **nach außen** zeigende Normale (Einheitsvektor). */
data class PlanarFace(val origin: Triple<Double, Double, Double>, val normal: Triple<Double, Double, Double>) {
    /** Abstand eines Punktes von der Ebene (positiv auf der Außenseite). */
    fun distanceTo(x: Double, y: Double, z: Double): Double =
        (x - origin.first) * normal.first + (y - origin.second) * normal.second + (z - origin.third) * normal.third
}

/** Ergebnis der Tessellierung. Flächen- und Kanten-IDs sind 1-basiert und gelten nur für den Ursprungskörper. */
class BrepMesh(
    val positions: FloatArray,
    val triangles: IntArray,
    /** Flächen-ID je Dreieck (Länge = triangles.size / 3). */
    val triangleFace: IntArray,
    val edgePositions: FloatArray,
    val edgePointCounts: IntArray,
    val edgeIds: IntArray,
) {
    val triangleCount: Int get() = triangles.size / 3

    /** Volumen des Dreiecksnetzes (Divergenzsatz), mm³. */
    fun volume(): Double {
        var v = 0.0
        for (t in 0 until triangleCount) {
            val a = 3 * triangles[3 * t]; val b = 3 * triangles[3 * t + 1]; val c = 3 * triangles[3 * t + 2]
            val ax = positions[a].toDouble(); val ay = positions[a + 1].toDouble(); val az = positions[a + 2].toDouble()
            val bx = positions[b].toDouble(); val by = positions[b + 1].toDouble(); val bz = positions[b + 2].toDouble()
            val cx = positions[c].toDouble(); val cy = positions[c + 1].toDouble(); val cz = positions[c + 2].toDouble()
            v += ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)
        }
        return v / 6.0
    }
}

/**
 * Ein B-Rep-Körper (OpenCascade `TopoDS_Shape`) hinter einem nativen Handle. Unveränderlich: jede
 * Operation liefert einen neuen Körper. [close] gibt den nativen Speicher frei; `finalize` fängt
 * vergessene Aufrufe ab (`java.lang.ref.Cleaner` gibt es auf Android erst ab API 33).
 */
class BrepShape private constructor(private val handle: Long) : AutoCloseable {
    @Volatile private var closed = false

    val faceCount: Int get() = counts()[0]
    val edgeCount: Int get() = counts()[1]

    /** Volumen in mm³ (exakt aus der B-Rep-Geometrie, nicht aus dem Netz). */
    fun volume(): Double = call { NativeBrep.volume(handle) }

    fun translate(x: Double, y: Double, z: Double): BrepShape = wrap { NativeBrep.translate(handle, x, y, z) }

    /**
     * Starre Bewegung mit der 3×4-Matrix `[R | t]` (zeilenweise, 12 Werte): zuerst Drehung `R`, dann Verschiebung `t`.
     * `R` muss eine echte Drehung sein (orthonormal, Determinante +1).
     */
    fun transform(matrix3x4: DoubleArray): BrepShape {
        require(matrix3x4.size == 12) { "3x4-Matrix erwartet (12 Werte)" }
        if (!isRigid(matrix3x4)) throw BrepException("Transformation ist keine starre Bewegung (Drehung + Verschiebung)")
        return wrap { NativeBrep.transform(handle, matrix3x4) }
    }

    /**
     * Spiegelbild an der Ebene durch ([px], [py], [pz]) mit der Normalen ([nx], [ny], [nz]). Die Flächennamen werden mitgenommen;
     * das Ergebnis ist wieder ein gültiger Körper mit positivem Volumen.
     */
    fun mirror(px: Double, py: Double, pz: Double, nx: Double, ny: Double, nz: Double): BrepShape {
        require(nx * nx + ny * ny + nz * nz > 1e-18) { "Spiegelebene braucht eine Normale" }
        return wrap { NativeBrep.mirror(handle, doubleArrayOf(px, py, pz, nx, ny, nz)) }
    }

    /**
     * Derselbe Körper mit anderen Flächennamen: [map] bekommt jeden alten Namen einer Fläche – Haupt- wie Zweitnamen, 0 = unbenannt –
     * und liefert den neuen (0 = Name fällt weg).
     */
    fun retag(map: (Long) -> Long): BrepShape {
        val tags = faceTags().map(map).toLongArray()
        val old = call { NativeBrep.faceAliases(handle) }
        val pairs = ArrayList<Long>(old.size)
        for (i in 0 until old.size / 2) {
            val t = map(old[2 * i + 1])
            if (t != 0L) { pairs += old[2 * i]; pairs += t }
        }
        return wrap { NativeBrep.retag(handle, tags, pairs.toLongArray()) }
    }

    /**
     * Führt Flächen zusammen, die auf derselben Fläche liegen – z. B. die beiden Hälften einer Deckfläche nach dem Spiegeln (sonst
     * bleibt eine Naht). Die neue Fläche trägt den kleinsten beteiligten Namen (das älteste Feature) als Hauptnamen, die übrigen als
     * Zweitnamen: [facesWithTag] und [edgesBetween] finden sie unter jedem davon.
     */
    fun unifyFaces(): BrepShape = wrap { NativeBrep.unifyFaces(handle) }

    /** Zweitnamen je Fläche (Flächen-ID → Namen), nur Flächen mit Zweitnamen. */
    fun faceAliases(): Map<Int, List<Long>> {
        val t = call { NativeBrep.faceAliases(handle) }
        return (0 until t.size / 2).groupBy({ t[2 * it].toInt() }, { t[2 * it + 1] })
    }

    private fun isRigid(m: DoubleArray): Boolean {
        // Spalten von R: (m0,m4,m8), (m1,m5,m9), (m2,m6,m10) – orthonormal und rechtshändig (det = +1)
        val c = Array(3) { j -> doubleArrayOf(m[j], m[4 + j], m[8 + j]) }
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val tol = 1e-9
        for (i in 0..2) {
            if (kotlin.math.abs(dot(c[i], c[i]) - 1.0) > tol) return false
            for (j in i + 1..2) if (kotlin.math.abs(dot(c[i], c[j])) > tol) return false
        }
        val det = c[0][0] * (c[1][1] * c[2][2] - c[1][2] * c[2][1]) -
            c[1][0] * (c[0][1] * c[2][2] - c[0][2] * c[2][1]) +
            c[2][0] * (c[0][1] * c[1][2] - c[0][2] * c[1][1])
        return det > 0
    }

    infix fun union(other: BrepShape): BrepShape = wrap { NativeBrep.boolean(handle, other.handle, 0) }
    infix fun cut(other: BrepShape): BrepShape = wrap { NativeBrep.boolean(handle, other.handle, 1) }
    infix fun intersect(other: BrepShape): BrepShape = wrap { NativeBrep.boolean(handle, other.handle, 2) }

    /**
     * Rundet die Kanten mit den IDs [edgeIds] mit [radius] ab. Mit [faceTags] (je Kante ein Name, gleiche Reihenfolge) erhalten
     * die entstehenden Rundungsflächen diese Namen; ohne Angabe bleiben sie unbenannt.
     */
    fun fillet(edgeIds: Collection<Int>, radius: Double, faceTags: Collection<Long> = emptyList()): BrepShape {
        require(faceTags.isEmpty() || faceTags.size == edgeIds.size) { "Je Kante genau ein Name" }
        return wrap { NativeBrep.fillet(handle, edgeIds.toIntArray(), faceTags.toLongArray(), radius, 0) }
    }

    /** Fase mit symmetrischem [distance] auf den Kanten mit den IDs [edgeIds]; [faceTags] wie bei [fillet]. */
    fun chamfer(edgeIds: Collection<Int>, distance: Double, faceTags: Collection<Long> = emptyList()): BrepShape {
        require(faceTags.isEmpty() || faceTags.size == edgeIds.size) { "Je Kante genau ein Name" }
        return wrap { NativeBrep.fillet(handle, edgeIds.toIntArray(), faceTags.toLongArray(), distance, 1) }
    }

    /**
     * Pro Kante (Element `i` ↔ Kanten-ID `i + 1`) die Namen der beiden Nachbarflächen, aufsteigend sortiert; `a == b` bei Nähten.
     * Ein Name 0 heißt: die Nachbarfläche hat keinen Namen.
     */
    fun edgeFaceTags(): List<Pair<Long, Long>> {
        val t = call { NativeBrep.edgeFaceTags(handle) }
        return (0 until t.size / 2).map { t[2 * it] to t[2 * it + 1] }
    }

    /**
     * Namen der Flächen: Element `i` gehört zur Fläche mit der ID `i + 1` (wie in [BrepMesh.triangleFace]). 0 = ohne Namen.
     * Gleiche Namen dürfen mehrfach vorkommen (eine Fläche wurde von einer Operation zerteilt).
     *
     * Die Namen werden beim Extrudieren vergeben und über Boolesche Operationen, Transformationen und Verrundungen
     * mitgeführt; die IDs selbst sind dagegen nur innerhalb eines Körpers gültig und ändern sich bei jedem Neuaufbau.
     */
    fun faceTags(): LongArray = call { NativeBrep.faceTags(handle) }

    /**
     * Ebenengeometrie je Fläche (Element `i` ↔ Flächen-ID `i + 1`); `null` für gekrümmte Flächen. Die Normale zeigt aus dem
     * Körper heraus.
     */
    fun planarFaces(): List<PlanarFace?> {
        val d = call { NativeBrep.faceGeometry(handle) }
        return (0 until d.size / 7).map { i ->
            val o = 7 * i
            if (d[o] == 0.0) null else PlanarFace(Triple(d[o + 1], d[o + 2], d[o + 3]), Triple(d[o + 4], d[o + 5], d[o + 6]))
        }
    }

    /** IDs aller Flächen mit dem Haupt- oder Zweitnamen [tag] (leer, wenn es sie nicht mehr gibt). */
    fun facesWithTag(tag: Long): List<Int> = if (tag == 0L) emptyList() else call { NativeBrep.facesWithTag(handle, tag) }.toList()

    /**
     * IDs aller Kanten, an denen die Flächen mit den Namen [a] und [b] aufeinandertreffen (Reihenfolge egal; `a == b` findet
     * Kanten innerhalb einer benannten Fläche, z. B. die Naht eines Zylinders). Eine Kante lässt sich so über ihre beiden
     * Nachbarflächen benennen, ohne selbst einen Namen zu brauchen.
     */
    fun edgesBetween(a: Long, b: Long): List<Int> =
        if (a == 0L || b == 0L) emptyList() else call { NativeBrep.edgesBetween(handle, a, b) }.toList()

    fun edges(): List<EdgeInfo> {
        val d = call { NativeBrep.edgeInfo(handle) }
        return (0 until d.size / 13).map { i ->
            val o = 13 * i
            val type = d[o + 8].toInt()
            EdgeInfo(
                d[o].toInt(), Triple(d[o + 1], d[o + 2], d[o + 3]), Triple(d[o + 4], d[o + 5], d[o + 6]), d[o + 7], type,
                center = if (type == 1) Triple(d[o + 9], d[o + 10], d[o + 11]) else null,
                radius = if (type == 1) d[o + 12] else 0.0,
            )
        }
    }

    /**
     * Zerlegt den Körper in Dreiecke (Flächen) und Polylinien (Kanten).
     * @param linearDeflection größte Abweichung von der echten Fläche in mm
     * @param angularDeflection größter Winkel zwischen benachbarten Facetten in Radiant
     */
    fun tessellate(linearDeflection: Double = 0.1, angularDeflection: Double = 0.5): BrepMesh {
        val r = call { NativeBrep.tessellate(handle, linearDeflection, angularDeflection) }
        return BrepMesh(r[0] as FloatArray, r[1] as IntArray, r[2] as IntArray, r[3] as FloatArray, r[4] as IntArray, r[5] as IntArray)
    }

    override fun close() {
        if (!closed) {
            closed = true
            NativeBrep.release(handle)
        }
    }

    @Suppress("unused", "ProtectedInFinal")
    protected fun finalize() = close()

    private fun counts() = call { NativeBrep.counts(handle) }

    private inline fun <T> call(block: () -> T): T {
        check(!closed) { "Körper wurde bereits freigegeben" }
        return try {
            block()
        } catch (e: IllegalStateException) {
            throw BrepException(e.message ?: "B-Rep-Fehler", e)
        }
    }

    private inline fun wrap(block: () -> Long): BrepShape = BrepShape(call(block))

    companion object {
        /**
         * Extrudiert [profile] (in der XY-Ebene) um [height] mm entlang +Z; bei negativem [height] nach −Z.
         * Mit [symmetric] wird [height] gleichmäßig auf beide Seiten der Ebene verteilt (Gesamtdicke |height|).
         * [bottomTag]/[topTag] benennen die untere (kleineres z) und die obere Stirnfläche, die [ProfileSegment.tag]s die Seitenflächen.
         */
        fun extrude(profile: Profile, height: Double, symmetric: Boolean = false, bottomTag: Long = 0, topTag: Long = 0): BrepShape {
            if (height == 0.0) throw BrepException("Extrusionshöhe darf nicht 0 sein")
            val h = kotlin.math.abs(height)
            val up = try {
                BrepShape(NativeBrep.extrude(profile.encode(), h, profile.segmentTags(), bottomTag, topTag))
            } catch (e: IllegalStateException) {
                throw BrepException(e.message ?: "B-Rep-Fehler", e)
            }
            // Die Richtung wird durch Verschieben des positiv extrudierten Körpers erreicht (vermeidet ein verkehrtes Solid).
            val dz = when {
                symmetric -> -h / 2
                height < 0 -> -h
                else -> 0.0
            }
            return if (dz == 0.0) up else up.use { it.translate(0.0, 0.0, dz) }
        }

        /**
         * Dreht [profile] (in der XY-Ebene) um die Achse durch ([ax], [ay]) mit der Richtung ([dx], [dy]) – beides in der Profilebene –
         * um [angle] Grad, gegen den Uhrzeigersinn um die Achsrichtung; negativ = andersherum. Mit [symmetric] wird [angle] zu gleichen
         * Teilen auf beide Seiten der Profilebene verteilt. Bei 360° entsteht ein geschlossener Drehkörper ohne Stirnflächen.
         *
         * Das Profil darf die Achse berühren (eine Kante auf der Achse), aber nicht schneiden – das prüft der Aufrufer
         * (sonst entstünde ein sich selbst durchdringender Körper).
         * [startTag] benennt die Stirnfläche, die in der Profilebene liegt (bei [symmetric] die auf der negativen Seite), [endTag] die
         * andere; die [ProfileSegment.tag]s benennen die Mantelflächen.
         */
        fun revolve(
            profile: Profile, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double, symmetric: Boolean = false,
            startTag: Long = 0, endTag: Long = 0,
        ): BrepShape {
            val len = kotlin.math.hypot(dx, dy)
            if (len < 1e-12) throw BrepException("Drehachse braucht eine Richtung")
            if (angle == 0.0 || !angle.isFinite() || kotlin.math.abs(angle) > 360.0 + 1e-9) throw BrepException("Drehwinkel muss zwischen 0 und 360 Grad liegen")
            val a = kotlin.math.abs(angle).coerceAtMost(360.0)
            // andersherum = um die umgekehrte Achse; dabei tauschen Anfangs- und Endfläche nicht (Anfang bleibt in der Profilebene)
            val s = if (angle < 0) -1.0 else 1.0
            val ux = s * dx / len; val uy = s * dy / len
            val turned = try {
                BrepShape(NativeBrep.revolve(profile.encode(), doubleArrayOf(ax, ay, ux, uy), a, profile.segmentTags(), startTag, endTag))
            } catch (e: IllegalStateException) {
                throw BrepException(e.message ?: "B-Rep-Fehler", e)
            }
            if (!symmetric || a >= 360.0) return turned
            return turned.use { it.transform(rotationAbout(ax, ay, ux, uy, -a / 2)) }
        }

        /** 3×4-Matrix der Drehung um [deg] Grad um die Achse durch (px, py, 0) mit der Richtung (ux, uy, 0) (Einheitsvektor). */
        internal fun rotationAbout(px: Double, py: Double, ux: Double, uy: Double, deg: Double): DoubleArray {
            val t = Math.toRadians(deg)
            val c = kotlin.math.cos(t); val sn = kotlin.math.sin(t); val k = 1 - c
            // Rodrigues mit u = (ux, uy, 0)
            val r = doubleArrayOf(
                c + ux * ux * k, ux * uy * k, uy * sn,
                uy * ux * k, c + uy * uy * k, -ux * sn,
                -uy * sn, ux * sn, c,
            )
            // t = p − R·p
            val tx = px - (r[0] * px + r[1] * py)
            val ty = py - (r[3] * px + r[4] * py)
            val tz = -(r[6] * px + r[7] * py)
            return doubleArrayOf(r[0], r[1], r[2], tx, r[3], r[4], r[5], ty, r[6], r[7], r[8], tz)
        }
    }
}
