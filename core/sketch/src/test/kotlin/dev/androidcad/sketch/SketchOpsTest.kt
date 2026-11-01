package dev.androidcad.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SketchOpsTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")
    private fun near(expected: Vec2, actual: Vec2, tol: Double = 1e-6) { near(expected.x, actual.x, tol); near(expected.y, actual.y, tol) }

    private fun Sketch.ends(id: LineId) = line(id).let { point(it.p1) to point(it.p2) }
    private fun Sketch.solved() = assertTrue(solve().isSolved, "Skizze muss lösbar bleiben")

    // ---- Trimmen: Linien ------------------------------------------------------------------------

    private fun cross(): Triple<Sketch, LineId, LineId> {
        val s = Sketch()
        val h = s.addLine(-10.0, 0.0, 10.0, 0.0)
        val v = s.addLine(0.0, -10.0, 0.0, 10.0)
        return Triple(s, h, v)
    }

    @Test
    fun trimmingOneSideOfACrossKeepsTheOtherSideAndBindsTheNewEndToTheCrossingLine() {
        val (s, h, v) = cross()
        val r = SketchOps.trim(s, h, Vec2(-5.0, 0.0))
        assertTrue(r.ok, r.message.toString())
        val (a, b) = s.ends(h)
        near(Vec2(0.0, 0.0), a); near(Vec2(10.0, 0.0), b)
        val start = s.line(h).p1
        assertTrue(s.constraintEntries.any { (it.constraint as? PointOnLine)?.let { c -> c.point == start && c.line == v } == true })
        s.solved()
        near(Vec2(0.0, 0.0), s.point(start)) // bleibt auf der senkrechten Linie
    }

    @Test
    fun trimmingBetweenTwoCrossingsSplitsTheLineAndKeepsShapeConstraints() {
        val s = Sketch()
        val h = s.addLine(-10.0, 0.0, 10.0, 0.0)
        s.add(Horizontal(h))
        s.add(LengthDim(h, 20.0))
        val v1 = s.addLine(-2.0, -5.0, -2.0, 5.0)
        val v2 = s.addLine(2.0, -5.0, 2.0, 5.0)
        val r = SketchOps.trim(s, h, Vec2(0.0, 0.0))
        assertTrue(r.ok, r.message.toString())
        // zwei Hälften: die erste behält die Kennung
        near(Vec2(-10.0, 0.0), s.ends(h).first); near(Vec2(-2.0, 0.0), s.ends(h).second)
        val second = s.lineIds.single { it != h && it != v1 && it != v2 }
        near(Vec2(2.0, 0.0), s.ends(second).first); near(Vec2(10.0, 0.0), s.ends(second).second)
        // waagerecht gilt für beide, die Längenangabe der alten Linie ist weg
        assertTrue(s.constraintEntries.any { (it.constraint as? Horizontal)?.line == second })
        assertTrue(s.constraintEntries.none { it.constraint is LengthDim })
        s.solved()
    }

    @Test
    fun trimmingALineWithoutCrossingsDeletesIt() {
        val s = Sketch()
        val l = s.addLine(0.0, 0.0, 10.0, 0.0)
        s.addLine(0.0, 5.0, 10.0, 5.0)
        assertTrue(SketchOps.trim(s, l, Vec2(5.0, 0.0)).ok)
        assertEquals(1, s.lineIds.size)
    }

    @Test
    fun trimReusesAnExistingPointAtTheCrossing() {
        val s = Sketch()
        val h = s.addLine(-10.0, 0.0, 10.0, 0.0)
        val vp = s.addPoint(3.0, 0.0)
        val vq = s.addPoint(3.0, 8.0)
        s.addLine(vp, vq) // T-Stoß: endet genau auf der waagerechten Linie
        val before = s.pointIds.size
        assertTrue(SketchOps.trim(s, h, Vec2(-5.0, 0.0)).ok)
        assertEquals(vp, s.line(h).p1, "vorhandener Punkt wird wiederverwendet")
        assertTrue(s.pointIds.size <= before, "keine zusätzlichen Punkte")
    }

    // ---- Trimmen: Kreis und Bogen ---------------------------------------------------------------

    @Test
    fun trimmingACircleLeavesAnArcAndKeepsItsRadiusDimension() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 10.0)
        s.addRadius(c, 10.0)
        s.addLine(5.0, -20.0, 5.0, 20.0)
        s.addLine(-5.0, -20.0, -5.0, 20.0)
        val r = SketchOps.trim(s, c, Vec2(10.0, 0.0)) // rechter Teil
        assertTrue(r.ok, r.message.toString())
        assertTrue(s.circleIds.isEmpty())
        val arc = s.arcIds.single()
        val d = s.arc(arc)
        near(Vec2(5.0, sqrt(75.0)), s.point(d.start)); near(Vec2(5.0, -sqrt(75.0)), s.point(d.end)) // großer Bogen links
        assertTrue(s.constraintEntries.any { (it.constraint as? RadiusDim)?.round == arc }, "Radiusmaß ging auf den Bogen über")
        s.solved()
        near(10.0, s.radiusOf(arc), 1e-5)
    }

    @Test
    fun aCircleWithOneCrossingCannotBeTrimmedAndWithNoneIsDeleted() {
        val s = Sketch()
        val c = s.addCircle(0.0, 0.0, 10.0)
        s.addLine(0.0, 0.0, 20.0, 0.0) // Linie endet außerhalb, schneidet einmal
        val r = SketchOps.trim(s, c, Vec2(0.0, 10.0))
        assertFalse(r.ok)
        assertEquals(1, s.circleIds.size, "unverändert")
        val lone = Sketch()
        val c2 = lone.addCircle(0.0, 0.0, 5.0)
        assertTrue(SketchOps.trim(lone, c2, Vec2(5.0, 0.0)).ok)
        assertTrue(lone.circleIds.isEmpty())
    }

    @Test
    fun trimmingAnArcShortensTheSideThatWasClicked() {
        fun fresh(): Triple<Sketch, ArcId, LineId> {
            val s = Sketch()
            val arc = s.addArc(0.0, 0.0, 10.0, 0.0, -10.0, 0.0) // oberer Halbkreis
            val line = s.addLine(0.0, -20.0, 0.0, 20.0)
            return Triple(s, arc, line)
        }
        run {
            val (s, arc, _) = fresh()
            assertTrue(SketchOps.trim(s, arc, Vec2(sqrt(50.0), sqrt(50.0))).ok) // rechte Hälfte weg
            near(Vec2(0.0, 10.0), s.point(s.arc(arc).start)); near(Vec2(-10.0, 0.0), s.point(s.arc(arc).end))
            s.solved()
        }
        run {
            val (s, arc, _) = fresh()
            assertTrue(SketchOps.trim(s, arc, Vec2(-sqrt(50.0), sqrt(50.0))).ok) // linke Hälfte weg
            near(Vec2(10.0, 0.0), s.point(s.arc(arc).start)); near(Vec2(0.0, 10.0), s.point(s.arc(arc).end))
            s.solved()
        }
        run { // zwei Schnittpunkte: Mitte entfernen → zwei Bögen gleichen Radius
            val s = Sketch()
            val arc = s.addArc(0.0, 0.0, 10.0, 0.0, -10.0, 0.0)
            s.addLine(-6.0, 0.0, -6.0, 20.0); s.addLine(6.0, 0.0, 6.0, 20.0)
            assertTrue(SketchOps.trim(s, arc, Vec2(0.0, 10.0)).ok)
            assertEquals(2, s.arcIds.size)
            assertTrue(s.constraintEntries.any { it.constraint is EqualRadius })
            s.solved()
            assertTrue(s.arcIds.all { abs(s.radiusOf(it) - 10.0) < 1e-5 })
        }
    }

    // ---- Verlängern -----------------------------------------------------------------------------

    @Test
    fun extendingALineReachesTheNextElementAndBindsToIt() {
        val s = Sketch()
        val l = s.addLine(0.0, 0.0, 5.0, 0.0)
        val wall = s.addLine(10.0, -5.0, 10.0, 5.0)
        val r = SketchOps.extend(s, l, Vec2(5.0, 0.0))
        assertTrue(r.ok, r.message.toString())
        near(Vec2(10.0, 0.0), s.ends(l).second)
        assertTrue(s.constraintEntries.any { (it.constraint as? PointOnLine)?.let { c -> c.point == s.line(l).p2 && c.line == wall } == true })
        s.solved()
        // am anderen Ende verlängert nach links
        val s2 = Sketch()
        val l2 = s2.addLine(0.0, 0.0, 5.0, 0.0)
        s2.addLine(-7.0, -5.0, -7.0, 5.0)
        assertTrue(SketchOps.extend(s2, l2, Vec2(0.0, 0.0)).ok)
        near(Vec2(-7.0, 0.0), s2.ends(l2).first)
    }

    @Test
    fun extendingALineFailsWithoutTargetOrWithSharedEndpoint() {
        val s = Sketch()
        val l = s.addLine(0.0, 0.0, 5.0, 0.0)
        assertFalse(SketchOps.extend(s, l, Vec2(5.0, 0.0)).ok) // nichts da
        val end = s.line(l).p2
        s.addLine(end, s.addPoint(5.0, 5.0)) // Endpunkt ist verbunden
        s.addLine(20.0, -5.0, 20.0, 5.0)
        assertFalse(SketchOps.extend(s, l, Vec2(5.0, 0.0)).ok)
        near(Vec2(5.0, 0.0), s.ends(l).second)
    }

    @Test
    fun extendingAnArcFollowsItsCircle() {
        val s = Sketch()
        val arc = s.addArc(0.0, 0.0, 10.0, 0.0, 0.0, 10.0) // 0° bis 90°
        s.addLine(-5.0, 0.0, -5.0, 20.0)
        val r = SketchOps.extend(s, arc, Vec2(0.0, 10.0))
        assertTrue(r.ok, r.message.toString())
        near(Vec2(-5.0, sqrt(75.0)), s.point(s.arc(arc).end), 1e-6)
        s.solved()
        near(10.0, s.radiusOf(arc), 1e-5)
        // der Anfang zeigt nach unten: Verlängerung im Uhrzeigersinn bis zur Linie
        val s2 = Sketch()
        val arc2 = s2.addArc(0.0, 0.0, 10.0, 0.0, 0.0, 10.0)
        s2.addLine(-20.0, -5.0, 20.0, -5.0)
        assertTrue(SketchOps.extend(s2, arc2, Vec2(10.0, 0.0)).ok)
        near(Vec2(sqrt(75.0), -5.0), s2.point(s2.arc(arc2).start), 1e-6)
    }

    // ---- Versatz --------------------------------------------------------------------------------

    @Test
    fun offsettingACircleKeepsTheCenterAndChangesTheRadius() {
        val s = Sketch()
        val c = s.addCircle(3.0, 4.0, 10.0)
        assertTrue(SketchOps.offset(s, c, Vec2(30.0, 4.0), 3.0).ok) // außen
        assertTrue(SketchOps.offset(s, c, Vec2(3.0, 4.0), 4.0).ok) // innen
        val radii = s.circleIds.map { s.circle(it).radius }.sorted()
        assertEquals(listOf(6.0, 10.0, 13.0), radii)
        assertTrue(s.circleIds.all { s.circle(it).center == s.circle(c).center })
        assertFalse(SketchOps.offset(s, c, Vec2(3.0, 4.0), 11.0).ok, "mehr als der Radius")
        s.solved()
    }

    @Test
    fun offsettingARectangleEdgeOffsetsTheWholeClosedChain() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val old = s.pointIds.toSet()
        val r = SketchOps.offset(s, rect.edges[0], Vec2(10.0, -5.0), 2.0) // unter der unteren Kante = außen
        assertTrue(r.ok, r.message.toString())
        assertEquals(8, s.lineIds.size)
        s.solved()
        val fresh = s.pointIds.filter { it !in old }.map { s.point(it) }
        assertEquals(4, fresh.size)
        near(-2.0, fresh.minOf { it.x }); near(22.0, fresh.maxOf { it.x }); near(-2.0, fresh.minOf { it.y }); near(12.0, fresh.maxOf { it.y })
        // parametrisch: wird das Rechteck breiter, wandert der Versatz mit
        val br = rect.corners[1]; val tr = rect.corners[2]
        s.add(Fixed(rect.corners[0], 0.0, 0.0))
        val w = s.addLength(rect.edges[0], 30.0)
        assertTrue(s.solve().isSolved)
        val fresh2 = s.pointIds.filter { it !in old }.map { s.point(it) }
        near(32.0, fresh2.maxOf { it.x }, 1e-5)
        assertNotNull(w)
        assertTrue(s.point(br).x > 29.9 && s.point(tr).x > 29.9)
    }

    @Test
    fun offsettingTheOtherSideGoesInside() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val old = s.pointIds.toSet()
        assertTrue(SketchOps.offset(s, rect.edges[0], Vec2(10.0, 3.0), 2.0).ok) // über der unteren Kante = innen
        s.solved()
        val fresh = s.pointIds.filter { it !in old }.map { s.point(it) }
        near(2.0, fresh.minOf { it.x }); near(18.0, fresh.maxOf { it.x }); near(2.0, fresh.minOf { it.y }); near(8.0, fresh.maxOf { it.y })
    }

    @Test
    fun offsettingAnOpenChainKeepsItSolvableAndFollowsTheCorners() {
        // U-Form aus drei Linien, offen; Versatz nach außen
        val s = Sketch()
        val a = s.addPoint(0.0, 10.0); val b = s.addPoint(0.0, 0.0); val c = s.addPoint(20.0, 0.0); val d = s.addPoint(20.0, 10.0)
        val left = s.addLine(a, b); val bottom = s.addLine(b, c); s.addLine(c, d)
        val old = s.pointIds.toSet()
        assertTrue(SketchOps.offset(s, bottom, Vec2(10.0, -5.0), 3.0).ok)
        assertEquals(6, s.lineIds.size)
        s.solved()
        val fresh = s.pointIds.filter { it !in old }.map { s.point(it) }
        assertEquals(4, fresh.size)
        near(-3.0, fresh.minOf { it.x }); near(23.0, fresh.maxOf { it.x }); near(-3.0, fresh.minOf { it.y })
        assertNotNull(left)
    }

    @Test
    fun offsettingALoneLineGivesAParallelLineOfTheSameLength() {
        val s = Sketch()
        val l = s.addLine(0.0, 0.0, 10.0, 0.0)
        assertTrue(SketchOps.offset(s, l, Vec2(5.0, 3.0), 3.0).ok)
        assertEquals(2, s.lineIds.size)
        s.solved()
        val n = s.lineIds.single { it != l }
        near(3.0, abs(s.signedDistance(s.line(n).p1, l)), 1e-6)
        near(3.0, abs(s.signedDistance(s.line(n).p2, l)), 1e-6)
        near(10.0, s.length(n), 1e-6)
        assertFalse(SketchOps.offset(s, l, Vec2(5.0, 3.0), 0.0).ok)
    }

    // ---- Eckenverrundung ------------------------------------------------------------------------

    @Test
    fun filletingARectangleCornerAddsATangentArcOfTheGivenRadius() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        val corner = rect.corners[1] // rechts unten
        val r = SketchOps.fillet(s, corner, 3.0)
        assertTrue(r.ok, r.message.toString())
        assertFalse(s.exists(corner))
        val arc = s.arcIds.single()
        s.solved()
        near(3.0, s.radiusOf(arc), 1e-6)
        val c = s.point(s.arc(arc).center)
        near(Vec2(17.0, 3.0), c, 1e-6)
        // Bogen liegt in der Ecke: Start/Ende an den gekürzten Linien
        val pts = listOf(s.point(s.arc(arc).start), s.point(s.arc(arc).end)).sortedBy { it.x }
        near(Vec2(17.0, 0.0), pts[0], 1e-6); near(Vec2(20.0, 3.0), pts[1], 1e-6)
        // Umlaufsinn gegen den Uhrzeigersinn, kurzer Bogen (90°)
        val a0 = atan2(s.point(s.arc(arc).start).y - c.y, s.point(s.arc(arc).start).x - c.x)
        val a1 = atan2(s.point(s.arc(arc).end).y - c.y, s.point(s.arc(arc).end).x - c.x)
        var sweep = a1 - a0; while (sweep <= 0) sweep += 2 * PI
        near(PI / 2, sweep, 1e-6)
    }

    @Test
    fun filletOnAnObtuseCornerAndFailures() {
        val s = Sketch()
        val p = s.addPoint(0.0, 0.0)
        val l1 = s.addLine(p, s.addPoint(-10.0, 0.0))
        val l2 = s.addLine(p, s.addPoint(10.0, 10.0)) // 135° Innenwinkel
        assertTrue(SketchOps.fillet(s, p, 2.0).ok)
        s.solved()
        val arc = s.arcIds.single()
        near(2.0, s.radiusOf(arc), 1e-6)
        // beide Linien berühren den Bogen
        assertTrue(s.constraintEntries.count { it.constraint is TangentLineRound } == 2)
        assertNotNull(l1); assertNotNull(l2)

        val big = Sketch()
        val rect = big.addRectangle(0.0, 0.0, 20.0, 10.0)
        assertFalse(SketchOps.fillet(big, rect.corners[0], 15.0).ok, "Radius zu groß")
        assertEquals(0, big.arcIds.size)
        assertFalse(SketchOps.fillet(big, rect.corners[0], 0.0).ok)
        val lone = Sketch()
        val q = lone.addPoint(1.0, 1.0)
        assertFalse(SketchOps.fillet(lone, q, 1.0).ok)
        val straight = Sketch()
        val m = straight.addPoint(0.0, 0.0)
        straight.addLine(m, straight.addPoint(5.0, 0.0)); straight.addLine(m, straight.addPoint(-5.0, 0.0))
        assertFalse(SketchOps.fillet(straight, m, 1.0).ok, "gerade Linie hat keine Ecke")
    }

    @Test
    fun filletDropsLengthDimensionsOfTheShortenedLines() {
        val s = Sketch()
        val rect = s.addRectangle(0.0, 0.0, 20.0, 10.0)
        s.add(LengthDim(rect.edges[0], 20.0))
        assertTrue(SketchOps.fillet(s, rect.corners[1], 3.0).ok)
        assertTrue(s.constraintEntries.none { it.constraint is LengthDim })
        s.solved()
    }

    // ---- Spiegeln -------------------------------------------------------------------------------

    @Test
    fun mirroringALineAcrossTheYAxisMirrorsAndStaysLinked() {
        val s = Sketch()
        val l = s.addLine(2.0, 1.0, 6.0, 4.0)
        val axis = SketchOps.axisLine(s, horizontal = false)
        assertTrue(SketchOps.mirror(s, listOf(l), emptyList(), axis).ok)
        s.solved()
        val m = s.lineIds.single { it != l && it != axis }
        near(Vec2(-2.0, 1.0), s.ends(m).first); near(Vec2(-6.0, 4.0), s.ends(m).second)
        // Symmetrie ist ein Constraint: Verschiebt man das Original, folgt das Spiegelbild
        s.movePoint(s.line(l).p2, 8.0, 4.0)
        s.add(Fixed(s.line(l).p2, 8.0, 4.0))
        s.solved()
        near(Vec2(-8.0, 4.0), s.ends(m).second, 1e-5)
    }

    @Test
    fun mirroringCirclesAndArcsKeepsRadiusAndHandedness() {
        val s = Sketch()
        val c = s.addCircle(5.0, 5.0, 2.0)
        val a = s.addArc(3.0, 0.0, 5.0, 0.0, 3.0, 2.0) // gegen den Uhrzeigersinn von 0° bis 90° um (3, 0)
        val axis = SketchOps.axisLine(s, horizontal = false)
        assertTrue(SketchOps.mirror(s, emptyList(), listOf(c, a), axis).ok)
        s.solved()
        val mc = s.circleIds.single { it != c }
        near(Vec2(-5.0, 5.0), s.point(s.circle(mc).center), 1e-5); near(2.0, s.circle(mc).radius, 1e-5)
        val ma = s.arcIds.single { it != a }
        val d = s.arc(ma)
        near(Vec2(-3.0, 0.0), s.point(d.center), 1e-5)
        near(Vec2(-3.0, 2.0), s.point(d.start), 1e-5) // Start = Spiegelbild des alten Endes
        near(Vec2(-5.0, 0.0), s.point(d.end), 1e-5)
        near(2.0, s.radiusOf(ma), 1e-5)
    }

    @Test
    fun pointsOnTheAxisAreSharedBetweenOriginalAndMirror() {
        val s = Sketch()
        val p = s.addPoint(0.0, 5.0)
        val l = s.addLine(p, s.addPoint(4.0, 5.0))
        val axis = SketchOps.axisLine(s, horizontal = false)
        assertTrue(SketchOps.mirror(s, listOf(l), emptyList(), axis).ok)
        val m = s.lineIds.single { it != l && it != axis }
        assertTrue(s.line(m).p1 == p || s.line(m).p2 == p, "gemeinsamer Punkt auf der Achse")
        s.solved()
        // Spiegelachse darf selbst nicht mitgespiegelt werden; nichts Wählbares → Fehler
        assertFalse(SketchOps.mirror(s, listOf(axis), emptyList(), axis).ok)
    }

    @Test
    fun mirroringAClosedShapeKeepsItConnectedAndAxisLinesAreReused() {
        val s = Sketch()
        val rect = s.addRectangle(2.0, 0.0, 6.0, 4.0)
        val axis = SketchOps.axisLine(s, horizontal = false)
        assertEquals(axis, SketchOps.axisLine(s, horizontal = false))
        assertTrue(SketchOps.mirror(s, rect.edges, emptyList(), axis).ok)
        s.solved()
        assertEquals(1 + 8, s.lineIds.size)
        // eine echte zweite Achse (X) ist eine andere Linie
        assertTrue(SketchOps.axisLine(s, horizontal = true) != axis)
        // jede gespiegelte Ecke wird von zwei Linien geteilt (geschlossener Linienzug)
        val fresh = s.pointIds.filter { s.point(it).x < -1.0 }
        assertEquals(4, fresh.size)
        assertTrue(fresh.all { p -> s.elementsUsing(p).size == 2 })
        assertTrue(hypot(1.0, 1.0) > 0)
    }
}
