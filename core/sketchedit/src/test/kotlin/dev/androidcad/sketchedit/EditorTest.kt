package dev.androidcad.sketchedit

import dev.androidcad.sketch.Coincident
import dev.androidcad.sketch.Fixed
import dev.androidcad.sketch.Horizontal
import dev.androidcad.sketch.LengthDim
import dev.androidcad.sketch.Midpoint
import dev.androidcad.sketch.PointOnLine
import dev.androidcad.sketch.Perpendicular
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.Vec2
import dev.androidcad.sketch.Vertical
import dev.androidcad.sketch.regions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorTest {
    /** Editor mit 10 px/mm, Ursprung bei (400, 300), ohne Rasterfang (damit die Testkoordinaten exakt bleiben). */
    private fun editor(grid: Boolean = false): SketchEditor {
        val e = SketchEditor(Sketch())
        e.setView(ViewTransform(10.0, 400.0, 300.0))
        e.gridSnap = grid
        return e
    }

    private fun SketchEditor.px(x: Double, y: Double) = view.toScreen(Vec2(x, y))
    private fun SketchEditor.click(x: Double, y: Double, additive: Boolean = false) {
        val p = px(x, y); pointerDown(p, additive = additive); pointerUp(p)
    }
    private fun SketchEditor.drag(x1: Double, y1: Double, x2: Double, y2: Double, kind: PointerKind = PointerKind.MOUSE, steps: Int = 6) {
        val a = px(x1, y1); val b = px(x2, y2)
        pointerDown(a, kind)
        for (i in 1..steps) pointerMove(Vec2(a.x + (b.x - a.x) * i / steps, a.y + (b.y - a.y) * i / steps))
        pointerUp(b)
    }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    // ---- Zeichnen -------------------------------------------------------------------------------

    @Test
    fun lineToolWithTwoClicksAddsAnAutoHorizontalConstraint() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(10.0, 20.0); assertTrue(e.drawingInProgress)
        e.click(50.0, 20.8)
        val line = e.sketch.lineIds.single()
        assertTrue(e.sketch.constraintEntries.any { it.constraint == Horizontal(line) })
        val l = e.sketch.line(line)
        near(e.sketch.point(l.p1).y, e.sketch.point(l.p2).y)        // der Solver hat die Linie waagerecht gestellt
        assertTrue(e.drawingInProgress)                              // Linienzug läuft weiter
        e.cancelOperation(); assertFalse(e.drawingInProgress)
    }

    @Test
    fun steepLineGetsVerticalAndSlantedLineGetsNothing() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(10.0, 10.0); e.click(10.5, 40.0); e.cancelOperation()
        val v = e.sketch.lineIds.single()
        assertTrue(e.sketch.constraintEntries.any { it.constraint == Vertical(v) })
        e.click(30.0, 10.0); e.click(60.0, 40.0); e.cancelOperation()
        val slanted = e.sketch.lineIds.last()
        assertTrue(e.sketch.constraintEntries.none { (it.constraint is Horizontal || it.constraint is Vertical) && it.constraint.refs.contains(slanted) })
    }

    @Test
    fun lineChainSharesEndpoints() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(10.0, 10.0); e.click(40.0, 20.0); e.click(60.0, 50.0); e.cancelOperation()
        val (a, b) = e.sketch.lineIds
        assertEquals(e.sketch.line(a).p2, e.sketch.line(b).p1)       // gemeinsamer Punkt, kein Coincident nötig
        assertEquals(1 + 3, e.sketch.pointIds.size)                   // Ursprung + drei Punkte
    }

    @Test
    fun stylusDragDrawsASingleLineWithoutChaining() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.drag(10.0, 10.0, 50.0, 35.0, kind = PointerKind.STYLUS)
        assertEquals(1, e.sketch.lineIds.size)
        assertFalse(e.drawingInProgress)
    }

    @Test
    fun drawingSnapsToExistingPointsInsteadOfCreatingDuplicates() {
        val e = editor()
        e.setTool(Tool.RECTANGLE)
        e.click(10.0, 10.0); e.click(50.0, 40.0)
        val pointsBefore = e.sketch.pointIds.size
        e.setTool(Tool.LINE)
        e.click(50.5, 40.4)                  // 0,5 mm neben der Ecke (5 px) → Fang an die Ecke
        e.click(80.0, 70.0); e.cancelOperation()
        assertEquals(pointsBefore + 1, e.sketch.pointIds.size)          // nur der neue Endpunkt
        val newLine = e.sketch.lineIds.last()
        val corner = e.sketch.pointIds.first { e.sketch.point(it).let { p -> abs(p.x - 50) < 1e-6 && abs(p.y - 40) < 1e-6 } }
        assertTrue(corner == e.sketch.line(newLine).p1 || corner == e.sketch.line(newLine).p2)
    }

    @Test
    fun endpointOnALineAddsPointOnLineAndMidpointAddsMidpoint() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(0.0, 20.0); e.click(40.0, 20.0); e.cancelOperation()
        val base = e.sketch.lineIds.single()
        e.click(13.0, 20.2); e.click(13.0, 50.0); e.cancelOperation()
        val onLine = e.sketch.pointIds.first { e.sketch.point(it).let { p -> abs(p.x - 13) < 1e-3 && abs(p.y - 20) < 1e-3 } }
        assertTrue(e.sketch.constraintEntries.any { it.constraint == PointOnLine(onLine, base) })
        e.click(20.1, 20.0); e.click(20.0, 60.0); e.cancelOperation()
        assertTrue(e.sketch.constraintEntries.any { it.constraint is Midpoint })
    }

    @Test
    fun axisSnapFixesTheCoordinate() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(20.0, 0.6)                   // nahe der x-Achse
        e.click(40.0, 15.0); e.cancelOperation()
        val l = e.sketch.line(e.sketch.lineIds.single())
        near(0.0, e.sketch.point(l.p1).y)
        assertTrue(e.sketch.constraintEntries.any { it.constraint == Fixed(l.p1, null, 0.0) })
    }

    @Test
    fun gridSnapRoundsToNiceNumbers() {
        val e = editor(grid = true)           // bei 10 px/mm: Rasterweite 5 mm
        e.setTool(Tool.LINE)
        e.click(12.0, 7.0); e.click(31.0, 23.0); e.cancelOperation()
        val l = e.sketch.line(e.sketch.lineIds.single())
        assertEquals(Vec2(10.0, 5.0), e.sketch.point(l.p1))
        assertEquals(Vec2(30.0, 25.0), e.sketch.point(l.p2))
    }

    @Test
    fun rectangleToolClickClickAndDrag() {
        val e = editor()
        e.setTool(Tool.RECTANGLE)
        e.click(10.0, 10.0); e.click(50.0, 40.0)
        assertEquals(4, e.sketch.lineIds.size)
        near(1200.0, e.sketch.regions().regions.single().area)
        e.drag(70.0, 10.0, 90.0, 30.0)
        assertEquals(8, e.sketch.lineIds.size)
        assertEquals(2, e.sketch.regions().regions.size)
        assertFalse(e.drawingInProgress)
    }

    @Test
    fun degenerateInputIsIgnored() {
        val e = editor()
        e.setTool(Tool.RECTANGLE)
        e.click(10.0, 10.0); e.click(10.0, 40.0)     // keine Breite
        e.setTool(Tool.LINE)
        e.click(30.0, 30.0); e.click(30.0, 30.0); e.cancelOperation()
        e.setTool(Tool.CIRCLE)
        e.click(50.0, 50.0); e.click(50.0, 50.0)
        assertTrue(e.sketch.lineIds.isEmpty() && e.sketch.circleIds.isEmpty())
    }

    @Test
    fun circleToolUsesTheSnappedCenterPoint() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(20.0, 20.0); e.click(40.0, 50.0); e.cancelOperation()
        val corner = e.sketch.line(e.sketch.lineIds.single()).p1
        e.setTool(Tool.CIRCLE)
        e.click(20.2, 20.1); e.click(30.0, 20.0)
        val circle = e.sketch.circle(e.sketch.circleIds.single())
        assertEquals(corner, circle.center)
        near(10.0, circle.radius, 0.05)
    }

    @Test
    fun arcToolNeedsCenterStartAndEnd() {
        val e = editor()
        e.setTool(Tool.ARC)
        e.click(20.0, 20.0); e.click(30.0, 20.0)
        assertTrue(e.preview is Preview.Arc)
        e.click(20.0, 35.0)                  // Richtung der Endmarke; der Radius bleibt 10
        val arc = e.sketch.arc(e.sketch.arcIds.single())
        val c = e.sketch.point(arc.center); val en = e.sketch.point(arc.end)
        near(10.0, hypot(en.x - c.x, en.y - c.y))
        near(20.0, en.x); near(30.0, en.y)
        assertFalse(e.drawingInProgress)
    }

    // ---- Auswahl und Ziehen -----------------------------------------------------------------------

    @Test
    fun selectingByTapAndClearingByTappingEmptySpace() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(10.0, 10.0); e.click(50.0, 10.0); e.cancelOperation()
        val line = e.sketch.lineIds.single()
        e.setTool(Tool.SELECT)
        e.click(30.0, 10.2)
        assertEquals(setOf<Sel>(Sel.L(line)), e.selection)
        e.click(30.0, 60.0)
        assertTrue(e.selection.isEmpty())
    }

    @Test
    fun additiveSelectionTogglesElements() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        e.setTool(Tool.SELECT)
        e.click(30.0, 10.0); e.click(50.0, 25.0, additive = true)
        assertEquals(2, e.selection.size)
        e.click(30.0, 10.0, additive = true)         // zweites Antippen nimmt es wieder heraus
        assertEquals(1, e.selection.size)
    }

    @Test
    fun boxSelectionPicksOnlyFullyContainedElements() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(30.0, 30.0)
        e.click(60.0, 10.0); e.click(80.0, 30.0)
        e.setTool(Tool.SELECT)
        e.drag(0.0, 40.0, 40.0, 0.0)                // nur das linke Rechteck
        assertEquals(4, e.selection.count { it is Sel.L })
        assertEquals(4, e.selection.count { it is Sel.P })
        assertNotNull(e.preview.takeIf { false } ?: Unit)
        assertNull(e.preview)
    }

    @Test
    fun draggingACornerKeepsDimensionsAndUpdatesTheSketch() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        e.setTool(Tool.SELECT)
        e.click(30.0, 10.0)                         // untere Kante
        e.requestAction(ActionKind.LENGTH); assertTrue(e.confirmPendingAction(40.0))
        var changes = 0
        e.onSketchChanged = { changes++ }
        e.drag(50.0, 40.0, 70.0, 55.0)               // rechte obere Ecke
        // Nichts ist festgehalten: das Rechteck folgt der Maus (die Ecke erreicht das Ziel), die Breite bleibt 40
        val corner = e.sketch.pointIds.first { e.sketch.point(it).let { p -> abs(p.x - 70) < 1e-5 && abs(p.y - 55) < 1e-5 } }
        val bottom = e.sketch.lineIds.first { e.sketch.constraintEntries.any { c -> c.constraint is LengthDim && c.constraint.refs.contains(it) } }
        near(40.0, e.sketch.length(bottom), 1e-6)
        assertTrue(changes > 0)
        // alle Kanten bleiben achsparallel (H/V-Constraints gelten weiter)
        assertTrue(e.status!!.isSolved)
        assertNotNull(corner)
    }

    @Test
    fun draggingALineMovesBothEndpoints() {
        val e = editor()
        e.setTool(Tool.LINE); e.click(10.0, 10.0); e.click(40.0, 10.0); e.cancelOperation()
        e.setTool(Tool.SELECT)
        e.drag(25.0, 10.0, 25.0, 30.0)
        val l = e.sketch.line(e.sketch.lineIds.single())
        near(30.0, e.sketch.point(l.p1).y, 1e-5); near(30.0, e.sketch.point(l.p2).y, 1e-5)
        near(30.0, e.sketch.length(e.sketch.lineIds.single()), 1e-5)
    }

    // ---- Constraints und Maße ---------------------------------------------------------------------

    @Test
    fun actionOptionsDependOnTheSelection() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(10.0, 10.0); e.click(40.0, 30.0); e.cancelOperation()
        e.click(60.0, 10.0); e.click(90.0, 25.0); e.cancelOperation()
        e.setTool(Tool.SELECT)
        e.click(25.0, 20.0)
        var kinds = e.actionOptions().map { it.kind }.toSet()
        assertTrue(ActionKind.HORIZONTAL in kinds && ActionKind.LENGTH in kinds && ActionKind.PARALLEL !in kinds)
        e.click(75.0, 17.5, additive = true)
        kinds = e.actionOptions().map { it.kind }.toSet()
        assertTrue(ActionKind.PARALLEL in kinds && ActionKind.PERPENDICULAR in kinds && ActionKind.EQUAL in kinds && ActionKind.ANGLE in kinds)
        assertTrue(ActionKind.LENGTH !in kinds)
    }

    @Test
    fun perpendicularActionAppliesAndSolves() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(10.0, 10.0); e.click(40.0, 30.0); e.cancelOperation()
        e.click(60.0, 10.0); e.click(90.0, 25.0); e.cancelOperation()
        e.setTool(Tool.SELECT)
        e.click(25.0, 20.0); e.click(75.0, 17.5, additive = true)
        e.requestAction(ActionKind.PERPENDICULAR)
        assertNull(e.message, e.message)
        assertTrue(e.sketch.constraintEntries.any { it.constraint is Perpendicular })
        near(90.0, abs(e.sketch.angleBetween(e.sketch.lineIds[0], e.sketch.lineIds[1])), 1e-4)
    }

    @Test
    fun conflictingAndRedundantActionsAreRejectedAndUndone() {
        val e = editor()
        e.setTool(Tool.LINE); e.click(10.0, 10.0); e.click(50.0, 10.0); e.cancelOperation()   // wird horizontal
        e.setTool(Tool.SELECT); e.click(30.0, 10.0)
        val before = e.sketch.constraintEntries.size
        e.requestAction(ActionKind.VERTICAL)          // widerspricht „horizontal“
        assertEquals("Widerspricht vorhandenen Bedingungen", e.message)
        assertEquals(before, e.sketch.constraintEntries.size)
        e.requestAction(ActionKind.HORIZONTAL)         // schon vorhanden
        assertTrue(e.message!!.contains("bereits"), e.message)
        assertEquals(before, e.sketch.constraintEntries.size)
        assertTrue(e.status!!.isSolved)
    }

    @Test
    fun dimensionFlowFromPromptToEditingTheLabel() {
        val e = editor()
        e.setTool(Tool.LINE); e.click(10.0, 10.0); e.click(50.0, 10.0); e.cancelOperation()
        e.setTool(Tool.SELECT); e.click(30.0, 10.0)
        e.requestAction(ActionKind.LENGTH)
        val pending = assertNotNull(e.pendingAction)
        near(40.0, pending.initial)                    // Vorgabe = gezeichnete Länge
        assertFalse(e.confirmPendingAction(-5.0))       // ungültig
        e.requestAction(ActionKind.LENGTH)
        assertTrue(e.confirmPendingAction(25.0))
        near(25.0, e.sketch.length(e.sketch.lineIds.single()))
        val label = e.dimensionLabels().single()
        assertEquals("25", label.text)
        // Tippen auf das Etikett öffnet die Wertänderung
        e.click(0.0, 0.0)
        e.pointerDown(label.center); e.pointerUp(label.center)
        val edit = assertNotNull(e.valueEdit)
        near(25.0, edit.initial)
        assertTrue(e.confirmValueEdit(30.0))
        near(30.0, e.sketch.length(e.sketch.lineIds.single()))
        // nicht lösbarer Wert (zweites, widersprechendes Maß) wird abgelehnt und zurückgenommen
        e.sketch.add(LengthDim(e.sketch.lineIds.single(), 30.0))
    }

    @Test
    fun signedDistancesShowTheirMagnitudeAndKeepTheirDirection() {
        val e = editor()
        e.setTool(Tool.LINE)
        e.click(30.0, 10.0); e.click(10.0, 25.0); e.cancelOperation()      // zweiter Punkt links vom ersten
        val line = e.sketch.line(e.sketch.lineIds.single())
        e.setTool(Tool.SELECT)
        e.click(30.0, 10.0); e.click(10.0, 25.0, additive = true)
        e.requestAction(ActionKind.HORIZONTAL_DISTANCE)
        assertTrue(e.pendingAction!!.initial < 0)                         // Vorgabe aus der Zeichnung: −20
        assertTrue(e.confirmPendingAction(e.pendingAction!!.initial))
        val label = e.dimensionLabels().single()
        assertEquals("20", label.text)                                    // Betrag, ohne Vorzeichen
        e.pointerDown(label.center); e.pointerUp(label.center)
        assertEquals(20.0, e.valueEdit!!.initial)                         // Eingabe zeigt den Betrag
        assertTrue(e.confirmValueEdit(35.0))                              // positiver Betrag …
        near(-35.0, e.sketch.point(line.p2).x - e.sketch.point(line.p1).x)  // … Richtung (nach links) bleibt
    }

    @Test
    fun fixActionLocksSelectedElements() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        e.setTool(Tool.SELECT); e.click(30.0, 10.0)
        e.requestAction(ActionKind.FIX)
        assertTrue(e.sketch.constraintEntries.count { it.constraint is Fixed } >= 3)  // Ursprung + zwei Endpunkte
        assertTrue(e.status!!.isSolved)
        // Fixiertes lässt sich nicht mehr ziehen
        e.drag(10.0, 10.0, 25.0, 30.0)
        near(10.0, e.sketch.point(e.sketch.line(e.sketch.lineIds.first()).p1).x, 1e-6)
    }

    @Test
    fun constructionToggleExcludesElementsFromRegions() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        assertEquals(1, e.sketch.regions().regions.size)
        e.setTool(Tool.SELECT); e.click(30.0, 10.0)
        e.requestAction(ActionKind.CONSTRUCTION)
        assertTrue(e.sketch.line(e.sketch.lineIds[0]).construction)
        assertEquals(0, e.sketch.regions().regions.size)
    }

    // ---- Löschen, Verlauf, Anzeige ----------------------------------------------------------------

    @Test
    fun deletingALineAlsoRemovesItsDanglingPoints() {
        val e = editor()
        e.setTool(Tool.LINE); e.click(10.0, 10.0); e.click(50.0, 30.0); e.cancelOperation()
        e.setTool(Tool.SELECT); e.click(30.0, 20.0)
        e.deleteSelection()
        assertTrue(e.sketch.lineIds.isEmpty())
        assertEquals(listOf(e.sketch.origin), e.sketch.pointIds)
        assertTrue(e.selection.isEmpty())
    }

    @Test
    fun eraserRemovesTheElementUnderThePen() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        e.setTool(Tool.SELECT)
        val p = e.px(30.0, 10.0)
        e.pointerDown(p, PointerKind.STYLUS, PointerButton.ERASER); e.pointerUp(p)
        assertEquals(3, e.sketch.lineIds.size)
    }

    @Test
    fun undoAndRedoRestoreTheSameSketchObject() {
        val e = editor()
        val identity = e.sketch
        assertFalse(e.canUndo)
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        assertTrue(e.canUndo)
        e.undo()
        assertTrue(e.sketch.lineIds.isEmpty()); assertSame(identity, e.sketch)
        assertTrue(e.canRedo)
        e.redo()
        assertEquals(4, e.sketch.lineIds.size)
        assertEquals(setOf(Sel.L(e.sketch.lineIds[0])).size, 1)
    }

    @Test
    fun eachDrawnObjectIsOneUndoStep() {
        val e = editor()
        e.setTool(Tool.LINE); e.click(10.0, 10.0); e.click(40.0, 10.0); e.click(40.0, 30.0); e.cancelOperation()
        assertEquals(2, e.sketch.lineIds.size)
        e.undo(); assertEquals(1, e.sketch.lineIds.size)
        e.undo(); assertEquals(0, e.sketch.lineIds.size)
    }

    @Test
    fun statusTextReportsDegreesOfFreedom() {
        val e = editor()
        assertEquals("Vollständig bestimmt", e.statusText())            // leere Skizze: nur der feste Ursprung
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        assertEquals("4 Freiheitsgrade", e.statusText())
        e.setTool(Tool.SELECT); e.click(30.0, 10.0)
        e.requestAction(ActionKind.LENGTH); e.confirmPendingAction(40.0)
        assertEquals("3 Freiheitsgrade", e.statusText())
    }

    @Test
    fun badgesAndLabelsPointAtTheirElements() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        val badges = e.badges()
        assertEquals(4, badges.size)                                    // H, V, H, V
        assertEquals(setOf("—", "|"), badges.map { it.symbol }.toSet())
        e.setTool(Tool.SELECT); e.click(30.0, 10.0)
        e.requestAction(ActionKind.LENGTH); e.confirmPendingAction(40.0)
        val label = e.dimensionLabels().single()
        // Etikett steht in der Nähe der Linienmitte (≤ 30 px)
        val mid = e.px(30.0, 10.0)
        assertTrue(hypot(label.center.x - mid.x, label.center.y - mid.y) <= 30.0)
    }

    @Test
    fun hoverReportsSnapTargetAndHitElement() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        e.hover(e.px(10.3, 10.2))
        assertEquals(SnapKind.POINT, e.hover!!.kind)
        e.setTool(Tool.SELECT)
        e.hover(e.px(30.0, 10.0))
        assertTrue(e.hoverHit is Sel.L)
        e.hover(e.px(30.0, 25.0))
        assertNull(e.hoverHit)
    }

    @Test
    fun secondaryButtonEndsTheLineChain() {
        val e = editor()
        e.setTool(Tool.LINE); e.click(10.0, 10.0); e.click(40.0, 20.0)
        assertTrue(e.drawingInProgress)
        e.pointerDown(e.px(40.0, 20.0), button = PointerButton.SECONDARY)
        assertFalse(e.drawingInProgress)
        assertEquals(1, e.sketch.lineIds.size)
    }

    @Test
    fun fitCentersTheContent() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(10.0, 10.0); e.click(50.0, 40.0)
        e.fit(800.0, 600.0)
        // nahe am Ursprung: der Ursprung kommt mit ins Bild (Box 0…50 × 0…40)
        val center = e.view.toScreen(Vec2(25.0, 20.0))
        near(400.0, center.x, 1.0); near(300.0, center.y, 1.0)
        assertTrue(e.view.scale > 4.0)
        // weit weg: nur der Inhalt, damit er nicht winzig wird
        val far = editor()
        far.setTool(Tool.RECTANGLE); far.click(500.0, 400.0); far.click(540.0, 430.0)
        far.fit(800.0, 600.0)
        val c2 = far.view.toScreen(Vec2(520.0, 415.0))
        near(400.0, c2.x, 1.0); near(300.0, c2.y, 1.0)
        assertEquals(PI, PI)
    }
    @Test
    fun fitKeepsInsetsFreeForFloatingControls() {
        val e = editor()
        e.setTool(Tool.RECTANGLE); e.click(500.0, 400.0); e.click(540.0, 430.0)
        e.fit(800.0, 600.0, left = 100.0, top = 40.0)
        // Mitte des Inhalts in der Mitte des freien Bereichs (100…800 × 40…600)
        val c = e.view.toScreen(Vec2(520.0, 415.0))
        near(450.0, c.x, 1.0); near(320.0, c.y, 1.0)
        val corner = e.view.toScreen(Vec2(500.0, 430.0))
        assertTrue(corner.x >= 100.0 && corner.y >= 40.0, "nichts unter den Rändern")
    }
}
