package dev.androidcad.brep

/** Ein Segment einer geschlossenen Skizzenkontur in der Skizzenebene (x, y; Einheit mm). */
sealed interface ProfileSegment {
    /** Frei vergebener Name der Seitenfläche, die aus diesem Segment entsteht (0 = ohne Namen); siehe [BrepShape.faceTags]. */
    val tag: Long

    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, override val tag: Long = 0) : ProfileSegment

    /** Kreisbogen von (x1,y1) nach (x2,y2) um (cx,cy); [ccw] = gegen den Uhrzeigersinn. */
    data class Arc(
        val x1: Double, val y1: Double, val x2: Double, val y2: Double,
        val cx: Double, val cy: Double, val ccw: Boolean, override val tag: Long = 0,
    ) : ProfileSegment

    /** Vollkreis (eine geschlossene Kontur für sich). */
    data class Circle(val cx: Double, val cy: Double, val r: Double, override val tag: Long = 0) : ProfileSegment
}

/**
 * Geschlossene Skizzenfläche: eine äußere Kontur plus beliebig viele Löcher. Die Segmente einer
 * Kontur müssen in Reihenfolge aneinanderstoßen (Endpunkt = nächster Startpunkt).
 */
class Profile(val outer: List<ProfileSegment>, val holes: List<List<ProfileSegment>> = emptyList()) {
    internal fun encode(): DoubleArray {
        val out = ArrayList<Double>()
        fun wire(segs: List<ProfileSegment>) {
            out += segs.size.toDouble()
            for (s in segs) when (s) {
                is ProfileSegment.Line -> out += listOf(0.0, s.x1, s.y1, s.x2, s.y2)
                is ProfileSegment.Arc -> out += listOf(1.0, s.x1, s.y1, s.x2, s.y2, s.cx, s.cy, if (s.ccw) 1.0 else 0.0)
                is ProfileSegment.Circle -> out += listOf(2.0, s.cx, s.cy, s.r)
            }
        }
        out += (1 + holes.size).toDouble()
        wire(outer)
        holes.forEach(::wire)
        return out.toDoubleArray()
    }

    /** Namen der Segmente in Kodierungsreihenfolge (äußere Kontur, dann die Löcher); leer, wenn kein Segment benannt ist. */
    internal fun segmentTags(): LongArray {
        val all = (outer + holes.flatten()).map { it.tag }
        return if (all.all { it == 0L }) LongArray(0) else all.toLongArray()
    }

    fun withHole(hole: List<ProfileSegment>) = Profile(outer, holes + listOf(hole))

    companion object {
        /** Achsenparalleles Rechteck mit Ecke (x, y) und Größe (w, h). */
        fun rectangle(x: Double, y: Double, w: Double, h: Double) = Profile(
            listOf(
                ProfileSegment.Line(x, y, x + w, y),
                ProfileSegment.Line(x + w, y, x + w, y + h),
                ProfileSegment.Line(x + w, y + h, x, y + h),
                ProfileSegment.Line(x, y + h, x, y),
            ),
        )

        fun circle(cx: Double, cy: Double, r: Double) = Profile(listOf(ProfileSegment.Circle(cx, cy, r)))
    }
}
